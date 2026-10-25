package com.quanlybanhang.service.impl;

import com.quanlybanhang.dto.response.*;
import com.quanlybanhang.model.*;
import com.quanlybanhang.repository.*;
import com.quanlybanhang.service.DebtAgingExcelExportService;
import com.quanlybanhang.service.DebtAgingReportService;
import com.quanlybanhang.service.FinancialReturnCredits;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Triển khai dịch vụ Báo cáo công nợ quá hạn theo tuổi nợ (S8-09).
 * - Tổng hợp theo nhóm tuổi nợ và theo nhân viên phụ trách
 * - Chỉ ra đại lý có rủi ro cao nhất (thứ tự ưu tiên xử lý trước)
 * - Xuất Excel (.xlsx)
 *
 * <p>Quyền ma trận DB: Yêu cầu quyền {@code REPORT_FINANCE} hoặc {@code ROLE_ADMIN}.
 * Không dùng role-based fallback. Tài khoản đại lý bị từ chối truy cập báo cáo nội bộ.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class DebtAgingReportServiceImpl implements DebtAgingReportService {

    public static final String TIMEZONE = "Asia/Ho_Chi_Minh";
    public static final String CURRENCY = "VND";
    public static final String RISK_BASIS = "Xếp hạng theo số ngày quá hạn lớn nhất giảm dần, kết hợp tổng tiền nợ quá hạn giảm dần";

    private static final List<DebtAgingBucketResponse> DEFAULT_BUCKETS = List.of(
            new DebtAgingBucketResponse("recent", "Quá hạn 1–14 ngày", 1, 14),
            new DebtAgingBucketResponse("middle", "Quá hạn 15–44 ngày", 15, 44),
            new DebtAgingBucketResponse("old", "Quá hạn từ 45 ngày", 45, null)
    );

    private final DealerRepository dealerRepository;
    private final UserRepository userRepository;
    private final InvoiceRepository invoiceRepository;
    private final OrderRepository orderRepository;
    private final DebtPaymentRepository debtPaymentRepository;
    private final ReturnOrderRepository returnOrderRepository;
    private final DebtAgingExcelExportService excelExportService;

    @Override
    public DebtAgingOptionsResponse getOptions() {
        validateAccessAndScope(null, false);
        LocalDate today = LocalDate.now(ZoneId.of(TIMEZONE));

        // Lấy danh sách nhân viên phụ trách
        List<User> salesUsers = userRepository.findAll().stream()
                .filter(u -> Boolean.TRUE.equals(u.getActive()))
                .filter(u -> u.getRoles() != null && u.getRoles().stream().anyMatch(r ->
                        r.getName() == ERole.ROLE_SALES || r.getName() == ERole.ROLE_SALES_MANAGER))
                .sorted(Comparator.comparing(User::getId))
                .toList();

        List<DebtAgingEmployeeOptionResponse> employees = new ArrayList<>();
        for (User u : salesUsers) {
            String name = (u.getFullName() != null && !u.getFullName().isBlank()) ? u.getFullName() : u.getUsername();
            employees.add(new DebtAgingEmployeeOptionResponse(u.getId(), u.getUsername(), name));
        }

        return DebtAgingOptionsResponse.builder()
                .defaultAsOfDate(today.toString())
                .timezone(TIMEZONE)
                .buckets(DEFAULT_BUCKETS)
                .employees(employees)
                .build();
    }

    @Override
    public DebtAgingReportResponse getReport(LocalDate asOfDate, Long employeeId, Boolean unassignedOnly) {
        User currentUser = validateAccessAndScope(employeeId, Boolean.TRUE.equals(unassignedOnly));

        LocalDate today = LocalDate.now(ZoneId.of(TIMEZONE));
        if (asOfDate == null) {
            asOfDate = today;
        } else if (asOfDate.isAfter(today)) {
            throw new IllegalArgumentException("Không chọn ngày sau ngày chốt tối đa do máy chủ cung cấp.");
        }

        boolean filterUnassigned = Boolean.TRUE.equals(unassignedOnly);

        // Lấy danh sách đại lý theo phạm vi phân quyền
        List<Dealer> allDealers = dealerRepository.findAll();
        List<Dealer> scopedDealers = new ArrayList<>();

        boolean isSalesStaffOnly = isSalesStaffOnly(currentUser);

        for (Dealer d : allDealers) {
            // Kiểm tra phân quyền cấp dòng của nhân viên kinh doanh
            if (isSalesStaffOnly) {
                if (d.getAssignedUser() == null || !d.getAssignedUser().getId().equals(currentUser.getId())) {
                    continue;
                }
            }

            // Áp dụng bộ lọc
            if (filterUnassigned) {
                if (d.getAssignedUser() != null) continue;
            } else if (employeeId != null) {
                if (d.getAssignedUser() == null || !d.getAssignedUser().getId().equals(employeeId)) continue;
            }

            scopedDealers.add(d);
        }

        // Tính công nợ quá hạn và phân bổ theo tuổi nợ cho từng đại lý
        List<DebtAgingDealerRowResponse> overdueDealers = new ArrayList<>();

        for (Dealer dealer : scopedDealers) {
            DebtAgingDealerRowResponse dealerRow = calculateDealerAging(dealer, asOfDate, DEFAULT_BUCKETS);
            if (dealerRow != null && dealerRow.getOverdueAmountRaw().compareTo(BigDecimal.ZERO) > 0) {
                overdueDealers.add(dealerRow);
            }
        }

        // 1. Sắp xếp danh sách đại lý theo mức độ rủi ro giảm dần:
        // - Số ngày quá hạn lớn nhất giảm dần
        // - Tổng tiền quá hạn giảm dần
        // - ID đại lý tăng dần để ổn định
        overdueDealers.sort((d1, d2) -> {
            int cmpDays = Integer.compare(d2.getMaxOverdueDays(), d1.getMaxOverdueDays());
            if (cmpDays != 0) return cmpDays;
            int cmpAmount = d2.getOverdueAmountRaw().compareTo(d1.getOverdueAmountRaw());
            if (cmpAmount != 0) return cmpAmount;
            return Long.compare(d1.getDealerId(), d2.getDealerId());
        });

        // 2. Gán hạng rủi ro (riskRank bắt đầu từ 1, tăng dần) và nhãn rủi ro (riskLabel)
        for (int i = 0; i < overdueDealers.size(); i++) {
            DebtAgingDealerRowResponse row = overdueDealers.get(i);
            if (i > 0) {
                DebtAgingDealerRowResponse prev = overdueDealers.get(i - 1);
                if (row.getMaxOverdueDays().equals(prev.getMaxOverdueDays())
                        && row.getOverdueAmountRaw().compareTo(prev.getOverdueAmountRaw()) == 0) {
                    row.setRiskRank(prev.getRiskRank());
                } else {
                    row.setRiskRank(i + 1);
                }
            } else {
                row.setRiskRank(1);
            }

            // Gán nhãn mức độ rủi ro
            if (row.getRiskRank() == 1 || row.getMaxOverdueDays() >= 45
                    || row.getOverdueAmountRaw().compareTo(new BigDecimal("50000000")) >= 0) {
                row.setRiskLabel("Ưu tiên cao");
            } else if (row.getMaxOverdueDays() >= 15
                    || row.getOverdueAmountRaw().compareTo(new BigDecimal("20000000")) >= 0) {
                row.setRiskLabel("Cần theo dõi");
            } else {
                row.setRiskLabel("Theo dõi");
            }
        }

        // 3. Tổng hợp toàn hệ thống
        BigDecimal totalOverdue = BigDecimal.ZERO;
        int maxOverdueDaysAll = 0;
        for (DebtAgingDealerRowResponse d : overdueDealers) {
            totalOverdue = totalOverdue.add(d.getOverdueAmountRaw());
            maxOverdueDaysAll = Math.max(maxOverdueDaysAll, d.getMaxOverdueDays());
        }

        DebtAgingTotalsResponse totals = DebtAgingTotalsResponse.builder()
                .overdueAmount(formatMoney(totalOverdue))
                .dealerCount(overdueDealers.size())
                .maxOverdueDays(maxOverdueDaysAll)
                .build();

        // 4. Tổng hợp theo nhóm tuổi nợ (bucketTotals)
        List<DebtAgingBucketTotalResponse> bucketTotals = new ArrayList<>();
        for (DebtAgingBucketResponse b : DEFAULT_BUCKETS) {
            BigDecimal bAmount = BigDecimal.ZERO;
            int bCount = 0;
            for (DebtAgingDealerRowResponse d : overdueDealers) {
                String amtStr = d.getBuckets().get(b.getKey());
                BigDecimal amt = new BigDecimal(amtStr);
                if (amt.compareTo(BigDecimal.ZERO) > 0) {
                    bAmount = bAmount.add(amt);
                    bCount++;
                }
            }
            bucketTotals.add(new DebtAgingBucketTotalResponse(b.getKey(), formatMoney(bAmount), bCount));
        }

        // 5. Tổng hợp theo nhân viên phụ trách (employeeTotals)
        // Nhóm theo employeeId (bao gồm cả null cho 'Chưa phân công')
        Map<Long, List<DebtAgingDealerRowResponse>> byEmployee = new LinkedHashMap<>();
        for (DebtAgingDealerRowResponse d : overdueDealers) {
            byEmployee.computeIfAbsent(d.getEmployeeId(), k -> new ArrayList<>()).add(d);
        }

        List<DebtAgingEmployeeTotalResponse> employeeTotals = new ArrayList<>();
        for (Map.Entry<Long, List<DebtAgingDealerRowResponse>> entry : byEmployee.entrySet()) {
            Long empId = entry.getKey();
            List<DebtAgingDealerRowResponse> dealersOfEmp = entry.getValue();

            String empName = dealersOfEmp.get(0).getEmployeeName();
            BigDecimal empTotalOverdue = BigDecimal.ZERO;
            Map<String, BigDecimal> empBucketsRaw = new LinkedHashMap<>();
            for (DebtAgingBucketResponse b : DEFAULT_BUCKETS) {
                empBucketsRaw.put(b.getKey(), BigDecimal.ZERO);
            }

            for (DebtAgingDealerRowResponse d : dealersOfEmp) {
                empTotalOverdue = empTotalOverdue.add(d.getOverdueAmountRaw());
                for (DebtAgingBucketResponse b : DEFAULT_BUCKETS) {
                    BigDecimal amt = new BigDecimal(d.getBuckets().get(b.getKey()));
                    empBucketsRaw.put(b.getKey(), empBucketsRaw.get(b.getKey()).add(amt));
                }
            }

            Map<String, String> empBucketsFormatted = new LinkedHashMap<>();
            for (DebtAgingBucketResponse b : DEFAULT_BUCKETS) {
                empBucketsFormatted.put(b.getKey(), formatMoney(empBucketsRaw.get(b.getKey())));
            }

            employeeTotals.add(DebtAgingEmployeeTotalResponse.builder()
                    .employeeId(empId)
                    .employeeName(empName)
                    .dealerCount(dealersOfEmp.size())
                    .overdueAmount(formatMoney(empTotalOverdue))
                    .buckets(empBucketsFormatted)
                    .build());
        }

        // Tạo chuỗi thời điểm hoàn tất
        OffsetDateTime now = OffsetDateTime.now(ZoneId.of(TIMEZONE));
        String generatedAt = now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        return DebtAgingReportResponse.builder()
                .asOfDate(asOfDate.toString())
                .generatedAt(generatedAt)
                .timezone(TIMEZONE)
                .currency(CURRENCY)
                .complete(true)
                .filters(new DebtAgingFilterDto(asOfDate.toString(), employeeId, filterUnassigned))
                .buckets(DEFAULT_BUCKETS)
                .riskBasis(RISK_BASIS)
                .totals(totals)
                .bucketTotals(bucketTotals)
                .employeeTotals(employeeTotals)
                .dealers(overdueDealers)
                .build();
    }

    @Override
    public byte[] exportExcel(LocalDate asOfDate, Long employeeId, Boolean unassignedOnly) {
        DebtAgingReportResponse report = getReport(asOfDate, employeeId, unassignedOnly);
        return excelExportService.exportToExcel(report);
    }

    @Override
    public String buildExportFilename(DebtAgingReportResponse report) {
        return excelExportService.buildFileName(report);
    }

    // ── TÍNH TOÁN CÔNG NỢ QUÁ HẠN TỪNG ĐẠI LÝ THEO FIFO ──

    private DebtAgingDealerRowResponse calculateDealerAging(Dealer dealer, LocalDate asOfDate, List<DebtAgingBucketResponse> buckets) {
        Long dealerId = dealer.getId();
        int maxDebtDays = dealer.getMaxDebtDays() != null ? dealer.getMaxDebtDays() : 0;

        // 1. Tổng tiền đã thu/thanh toán đến ngày chốt
        BigDecimal totalPaid = debtPaymentRepository != null
                ? debtPaymentRepository.sumAmountByDealerIdAndPaymentDateLessThanEqual(dealerId, asOfDate)
                : BigDecimal.ZERO;
        if (totalPaid == null) totalPaid = BigDecimal.ZERO;

        // 2. Chiết giảm từ phiếu trả hàng
        Map<Long, BigDecimal> invoiceCredits = FinancialReturnCredits.byInvoice(returnOrderRepository, dealerId);
        Map<Long, BigDecimal> orderReturnCredits = new HashMap<>();
        if (returnOrderRepository != null) {
            returnOrderRepository.creditsByOrder(dealerId, OrderStatus.debtBearingStatuses())
                    .forEach(row -> orderReturnCredits.put(row.getOrderId(), row.getAmount()));
        }

        // 3. Danh sách hóa đơn phát hành đến ngày chốt
        List<Invoice> rawInvoices = (invoiceRepository != null && dealerId != null)
                ? invoiceRepository.findByDealerIdOrderByInvoiceDateAscIdAsc(dealerId)
                : Collections.emptyList();

        List<Invoice> activeInvoices = rawInvoices.stream()
                .filter(inv -> inv.getStatus() != InvoiceStatus.CANCELLED)
                .filter(inv -> {
                    LocalDate invDate = inv.getInvoiceDate() != null ? inv.getInvoiceDate().toLocalDate()
                            : (inv.getCreatedAt() != null ? inv.getCreatedAt().toLocalDate() : null);
                    return invDate == null || !invDate.isAfter(asOfDate);
                })
                .toList();

        Set<Long> invoicedOrderIds = new HashSet<>();
        for (Invoice inv : activeInvoices) {
            if (inv.getOrders() != null) {
                for (Order o : inv.getOrders()) {
                    if (o != null && o.getId() != null) {
                        invoicedOrderIds.add(o.getId());
                    }
                }
            }
        }

        // 4. Danh sách đơn hàng ghi nợ chưa xuất hóa đơn đến ngày chốt
        List<Order> rawOrders = (orderRepository != null && dealerId != null)
                ? orderRepository.findByDealerIdAndStatusInOrderByRecordedAtAsc(dealerId, OrderStatus.debtBearingStatuses())
                : Collections.emptyList();

        List<Order> uninvoicedOrders = rawOrders.stream()
                .filter(o -> !invoicedOrderIds.contains(o.getId()))
                .filter(o -> {
                    LocalDateTime recTime = o.getRecordedAt() != null ? o.getRecordedAt() : o.getCreatedAt();
                    LocalDate oDate = recTime != null ? recTime.toLocalDate() : null;
                    return oDate == null || !oDate.isAfter(asOfDate);
                })
                .toList();

        // 5. Đối trừ FIFO và tính tuổi nợ quá hạn
        BigDecimal remainingPool = totalPaid;
        BigDecimal dealerOverdueAmount = BigDecimal.ZERO;
        int dealerMaxOverdueDays = 0;

        Map<String, BigDecimal> bucketAmounts = new LinkedHashMap<>();
        for (DebtAgingBucketResponse b : buckets) {
            bucketAmounts.put(b.getKey(), BigDecimal.ZERO);
        }

        // A. Xét từng hóa đơn theo thứ tự phát hành
        for (Invoice inv : activeInvoices) {
            BigDecimal effAmt = FinancialReturnCredits.netInvoice(inv, invoiceCredits);
            if (effAmt == null || effAmt.compareTo(BigDecimal.ZERO) <= 0) continue;

            BigDecimal paid = remainingPool.min(effAmt);
            remainingPool = remainingPool.subtract(paid).max(BigDecimal.ZERO);
            BigDecimal unpaid = effAmt.subtract(paid);

            if (unpaid.compareTo(BigDecimal.ZERO) > 0) {
                LocalDate dueDate = inv.getDueDate();
                if (dueDate == null) {
                    LocalDate baseDate = inv.getInvoiceDate() != null ? inv.getInvoiceDate().toLocalDate()
                            : (inv.getCreatedAt() != null ? inv.getCreatedAt().toLocalDate() : asOfDate);
                    dueDate = baseDate.plusDays(maxDebtDays);
                }

                if (dueDate != null && asOfDate.isAfter(dueDate)) {
                    int overdueDays = (int) ChronoUnit.DAYS.between(dueDate, asOfDate);
                    if (overdueDays >= 1) {
                        dealerOverdueAmount = dealerOverdueAmount.add(unpaid);
                        dealerMaxOverdueDays = Math.max(dealerMaxOverdueDays, overdueDays);
                        addAmountToMatchingBucket(buckets, bucketAmounts, overdueDays, unpaid);
                    }
                }
            }
        }

        // B. Xét từng đơn hàng chưa xuất hóa đơn (nếu có áp dụng ngày nợ tối đa)
        if (maxDebtDays > 0) {
            for (Order o : uninvoicedOrders) {
                BigDecimal amt = (o.getTotalAmount() != null ? o.getTotalAmount() : BigDecimal.ZERO)
                        .subtract(orderReturnCredits.getOrDefault(o.getId(), BigDecimal.ZERO)).max(BigDecimal.ZERO);
                if (amt.compareTo(BigDecimal.ZERO) <= 0) continue;

                BigDecimal paid = remainingPool.min(amt);
                remainingPool = remainingPool.subtract(paid).max(BigDecimal.ZERO);
                BigDecimal unpaid = amt.subtract(paid);

                if (unpaid.compareTo(BigDecimal.ZERO) > 0) {
                    LocalDateTime recTime = o.getRecordedAt() != null ? o.getRecordedAt() : o.getCreatedAt();
                    LocalDate oDate = recTime != null ? recTime.toLocalDate() : asOfDate;
                    LocalDate dueDate = oDate.plusDays(maxDebtDays);

                    if (asOfDate.isAfter(dueDate)) {
                        int overdueDays = (int) ChronoUnit.DAYS.between(dueDate, asOfDate);
                        if (overdueDays >= 1) {
                            dealerOverdueAmount = dealerOverdueAmount.add(unpaid);
                            dealerMaxOverdueDays = Math.max(dealerMaxOverdueDays, overdueDays);
                            addAmountToMatchingBucket(buckets, bucketAmounts, overdueDays, unpaid);
                        }
                    }
                }
            }
        }

        // Nếu đại lý không có nợ quá hạn
        if (dealerOverdueAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }

        // Định dạng chuỗi cho response
        Map<String, String> formattedBuckets = new LinkedHashMap<>();
        for (DebtAgingBucketResponse b : buckets) {
            formattedBuckets.put(b.getKey(), formatMoney(bucketAmounts.get(b.getKey())));
        }

        Long employeeId = dealer.getAssignedUser() != null ? dealer.getAssignedUser().getId() : null;
        String employeeName = "Chưa phân công";
        if (dealer.getAssignedUser() != null) {
            User u = dealer.getAssignedUser();
            employeeName = (u.getFullName() != null && !u.getFullName().isBlank()) ? u.getFullName() : u.getUsername();
        }

        return DebtAgingDealerRowResponse.builder()
                .dealerId(dealer.getId())
                .dealerCode(dealer.getCode())
                .dealerName(dealer.getName())
                .employeeId(employeeId)
                .employeeName(employeeName)
                .overdueAmount(formatMoney(dealerOverdueAmount))
                .overdueAmountRaw(dealerOverdueAmount)
                .maxOverdueDays(dealerMaxOverdueDays)
                .riskRank(1) // sẽ cập nhật lại sau khi sắp xếp toàn bộ danh sách
                .riskLabel("Theo dõi")
                .buckets(formattedBuckets)
                .build();
    }

    private void addAmountToMatchingBucket(List<DebtAgingBucketResponse> buckets, Map<String, BigDecimal> bucketAmounts, int overdueDays, BigDecimal amount) {
        for (DebtAgingBucketResponse b : buckets) {
            int min = b.getMinDays() != null ? b.getMinDays() : 1;
            Integer max = b.getMaxDays();
            if (overdueDays >= min && (max == null || overdueDays <= max)) {
                bucketAmounts.put(b.getKey(), bucketAmounts.get(b.getKey()).add(amount));
                return;
            }
        }
    }

    private String formatMoney(BigDecimal amount) {
        if (amount == null) return "0.00";
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    // ── PHÂN QUYỀN & KIỂM TRA PHẠM VI DỮ LIỆU ──

    private User validateAccessAndScope(Long filterEmployeeId, boolean unassignedOnly) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new AccessDeniedException("Chưa xác thực người dùng");
        }

        Set<String> authorities = new HashSet<>();
        for (GrantedAuthority a : auth.getAuthorities()) {
            authorities.add(a.getAuthority());
        }

        boolean isAdmin = authorities.contains("ROLE_ADMIN");
        boolean hasPermission = authorities.contains("REPORT_FINANCE");

        if (!isAdmin && !hasPermission) {
            throw new AccessDeniedException("Bạn không có quyền xem báo cáo tài chính (yêu cầu REPORT_FINANCE)");
        }

        if (!isAdmin && authorities.contains("ROLE_DEALER")) {
            throw new AccessDeniedException("Tài khoản đại lý không được phép xem báo cáo công nợ tuổi nợ nội bộ");
        }

        User currentUser = userRepository.findByUsername(auth.getName()).orElse(null);

        if (!isAdmin && isSalesStaffOnly(currentUser)) {
            if (unassignedOnly) {
                throw new AccessDeniedException("Nhân viên kinh doanh chỉ được xem đại lý do mình phụ trách");
            }
            if (filterEmployeeId != null && currentUser != null && !filterEmployeeId.equals(currentUser.getId())) {
                throw new AccessDeniedException("Bạn không được phép lọc dữ liệu theo nhân viên khác");
            }
        }

        return currentUser;
    }

    private boolean isSalesStaffOnly(User user) {
        if (user == null || user.getRoles() == null) return false;
        boolean hasSales = false;
        for (Role r : user.getRoles()) {
            if (r.getName() == ERole.ROLE_ADMIN || r.getName() == ERole.ROLE_SALES_MANAGER
                    || r.getName() == ERole.ROLE_ACCOUNTANT || r.getName() == ERole.ROLE_VIEWER) {
                return false;
            }
            if (r.getName() == ERole.ROLE_SALES) {
                hasSales = true;
            }
        }
        return hasSales;
    }
}

