package com.quanlybanhang.service.impl;

import com.quanlybanhang.dto.response.DealerDebtAgingLedgerResponse;
import com.quanlybanhang.dto.response.DealerDebtAgingRowResponse;
import com.quanlybanhang.dto.response.DealerDebtAgingSummaryResponse;
import com.quanlybanhang.model.*;
import com.quanlybanhang.repository.*;
import com.quanlybanhang.service.DealerDebtAgingExcelExportService;
import com.quanlybanhang.service.DealerDebtAgingLedgerService;
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
 * Triển khai dịch vụ Sổ công nợ theo đại lý với phân tích tuổi nợ (S7-03).
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class DealerDebtAgingLedgerServiceImpl implements DealerDebtAgingLedgerService {

    public static final String TIMEZONE = "Asia/Ho_Chi_Minh";
    public static final String CURRENCY = "VND";

    private final DealerRepository dealerRepository;
    private final UserRepository userRepository;
    private final InvoiceRepository invoiceRepository;
    private final OrderRepository orderRepository;
    private final DebtPaymentRepository debtPaymentRepository;
    private final ReturnOrderRepository returnOrderRepository;
    private final DealerDebtAgingExcelExportService excelExportService;

    @Override
    public DealerDebtAgingLedgerResponse getLedger(LocalDate asOfDate, Boolean overdueOnly, Long dealerId, String search) {
        User currentUser = validateAccessAndScope(dealerId);

        LocalDate today = LocalDate.now(ZoneId.of(TIMEZONE));
        if (asOfDate == null) {
            asOfDate = today;
        } else if (asOfDate.isAfter(today)) {
            throw new IllegalArgumentException("Không được chọn ngày chốt trong tương lai vượt quá ngày hiện tại.");
        }

        boolean filterOverdueOnly = Boolean.TRUE.equals(overdueOnly);
        String searchKeyword = (search != null && !search.trim().isBlank()) ? search.trim().toLowerCase() : null;

        // 1. Lấy danh sách đại lý theo phạm vi phân quyền
        List<Dealer> dealersToProcess = getScopedDealers(currentUser, dealerId, searchKeyword);

        // 2. Tính toán công nợ và tuổi nợ cho từng đại lý
        List<DealerDebtAgingRowResponse> rows = new ArrayList<>();
        for (Dealer dealer : dealersToProcess) {
            DealerDebtAgingRowResponse row = calculateDealerDebtAging(dealer, asOfDate);
            if (row == null) continue;

            if (filterOverdueOnly && !Boolean.TRUE.equals(row.getIsOverdue())) {
                continue;
            }

            // Nếu không phải lọc đại lý cụ thể, bỏ qua đại lý hoàn toàn không phát sinh công nợ
            if (dealerId == null && searchKeyword == null
                    && row.getTotalReceivable().compareTo(BigDecimal.ZERO) == 0
                    && row.getTotalPaid().compareTo(BigDecimal.ZERO) == 0
                    && row.getRemainingDebt().compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }

            rows.add(row);
        }

        // 3. Sắp xếp: "để tập trung đòi những khoản nợ lâu nhất trước"
        // Ưu tiên nợ trên 90 ngày -> 61-90 ngày -> 31-60 ngày -> tuổi nợ tối đa -> tổng nợ -> ID đại lý
        rows.sort((r1, r2) -> {
            int cmp90 = r2.getBucketOver90().compareTo(r1.getBucketOver90());
            if (cmp90 != 0) return cmp90;

            int cmp61 = r2.getBucket61To90().compareTo(r1.getBucket61To90());
            if (cmp61 != 0) return cmp61;

            int cmp31 = r2.getBucket31To60().compareTo(r1.getBucket31To60());
            if (cmp31 != 0) return cmp31;

            int cmpAge = Integer.compare(r2.getMaxDebtAgeDays(), r1.getMaxDebtAgeDays());
            if (cmpAge != 0) return cmpAge;

            int cmpOverdue = Integer.compare(r2.getMaxOverdueDays(), r1.getMaxOverdueDays());
            if (cmpOverdue != 0) return cmpOverdue;

            int cmpRemain = r2.getRemainingDebt().compareTo(r1.getRemainingDebt());
            if (cmpRemain != 0) return cmpRemain;

            return Long.compare(r1.getDealerId(), r2.getDealerId());
        });

        // 4. Tổng cộng ở chân bảng (Summary)
        DealerDebtAgingSummaryResponse summary = buildSummary(rows);

        OffsetDateTime now = OffsetDateTime.now(ZoneId.of(TIMEZONE));
        String generatedAt = now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        return DealerDebtAgingLedgerResponse.builder()
                .asOfDate(asOfDate.toString())
                .generatedAt(generatedAt)
                .timezone(TIMEZONE)
                .currency(CURRENCY)
                .overdueOnly(filterOverdueOnly)
                .dealerId(dealerId)
                .search(search)
                .dealers(rows)
                .summary(summary)
                .build();
    }

    @Override
    public byte[] exportExcel(LocalDate asOfDate, Boolean overdueOnly, Long dealerId, String search) {
        DealerDebtAgingLedgerResponse ledger = getLedger(asOfDate, overdueOnly, dealerId, search);
        return excelExportService.exportToExcel(ledger);
    }

    @Override
    public String buildExportFilename(DealerDebtAgingLedgerResponse ledger) {
        return excelExportService.buildFileName(ledger);
    }

    // ── TÍNH TOÁN CÔNG NỢ & TUỔI NỢ TỪNG ĐẠI LÝ THEO FIFO ──

    private record DebtRecord(
            String documentType,
            Long documentId,
            String documentCode,
            BigDecimal amount,
            LocalDate debtDate,
            LocalDate dueDate
    ) {}

    private DealerDebtAgingRowResponse calculateDealerDebtAging(Dealer dealer, LocalDate asOfDate) {
        Long dealerId = dealer.getId();
        int maxDebtDays = dealer.getMaxDebtDays() != null ? dealer.getMaxDebtDays() : 0;
        BigDecimal debtLimit = dealer.getDebtLimit() != null ? dealer.getDebtLimit() : BigDecimal.ZERO;

        // 1. Tổng tiền đã thu/thanh toán đến ngày chốt
        BigDecimal totalPaid = (debtPaymentRepository != null && dealerId != null)
                ? debtPaymentRepository.sumAmountByDealerIdAndPaymentDateLessThanEqual(dealerId, asOfDate)
                : BigDecimal.ZERO;
        if (totalPaid == null) totalPaid = BigDecimal.ZERO;

        // 2. Chiết giảm từ phiếu trả hàng
        Map<Long, BigDecimal> invoiceCredits = FinancialReturnCredits.byInvoice(returnOrderRepository, dealerId);
        Map<Long, BigDecimal> orderReturnCredits = new HashMap<>();
        if (returnOrderRepository != null && dealerId != null) {
            var orderCredits = returnOrderRepository.creditsByOrder(dealerId, OrderStatus.debtBearingStatuses());
            if (orderCredits != null) {
                orderCredits.forEach(row -> orderReturnCredits.put(row.getOrderId(), row.getAmount()));
            }
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

        // 5. Tập hợp các khoản ghi nợ và sắp xếp theo ngày phát sinh tăng dần (FIFO)
        List<DebtRecord> debtRecords = new ArrayList<>();

        for (Invoice inv : activeInvoices) {
            BigDecimal effAmt = FinancialReturnCredits.netInvoice(inv, invoiceCredits);
            if (effAmt == null || effAmt.compareTo(BigDecimal.ZERO) <= 0) continue;

            LocalDate docDate = inv.getInvoiceDate() != null ? inv.getInvoiceDate().toLocalDate()
                    : (inv.getCreatedAt() != null ? inv.getCreatedAt().toLocalDate() : asOfDate);
            LocalDate dueDate = inv.getDueDate();
            if (dueDate == null && maxDebtDays > 0) {
                dueDate = docDate.plusDays(maxDebtDays);
            }

            debtRecords.add(new DebtRecord("INVOICE", inv.getId(), inv.getInvoiceCode(), effAmt, docDate, dueDate));
        }

        for (Order o : uninvoicedOrders) {
            BigDecimal baseAmount = o.getTotalAmount() != null ? o.getTotalAmount() : BigDecimal.ZERO;
            BigDecimal credit = orderReturnCredits.getOrDefault(o.getId(), BigDecimal.ZERO);
            BigDecimal netOrderAmount = baseAmount.subtract(credit).max(BigDecimal.ZERO);
            if (netOrderAmount.compareTo(BigDecimal.ZERO) <= 0) continue;

            LocalDateTime recTime = o.getRecordedAt() != null ? o.getRecordedAt() : o.getCreatedAt();
            LocalDate docDate = recTime != null ? recTime.toLocalDate() : asOfDate;
            LocalDate dueDate = maxDebtDays > 0 ? docDate.plusDays(maxDebtDays) : null;

            debtRecords.add(new DebtRecord("ORDER", o.getId(), o.getOrderCode(), netOrderAmount, docDate, dueDate));
        }

        debtRecords.sort(Comparator.comparing(DebtRecord::debtDate).thenComparing(DebtRecord::documentId));

        // 6. Tính tổng phát sinh phải thu
        BigDecimal totalReceivable = BigDecimal.ZERO;
        for (DebtRecord rec : debtRecords) {
            totalReceivable = totalReceivable.add(rec.amount());
        }

        // Còn nợ sau khi trừ tổng đã thu
        BigDecimal remainingDebt = totalReceivable.subtract(totalPaid).max(BigDecimal.ZERO);

        // 7. Đối trừ FIFO và phân tích 4 nhóm tuổi nợ
        BigDecimal remainingPaidPool = totalPaid;
        BigDecimal bucket0To30 = BigDecimal.ZERO;
        BigDecimal bucket31To60 = BigDecimal.ZERO;
        BigDecimal bucket61To90 = BigDecimal.ZERO;
        BigDecimal bucketOver90 = BigDecimal.ZERO;

        BigDecimal inTermDebt = BigDecimal.ZERO;
        BigDecimal overdueDebt = BigDecimal.ZERO;
        int maxDebtAgeDays = 0;
        int maxOverdueDays = 0;

        for (DebtRecord rec : debtRecords) {
            BigDecimal covered = remainingPaidPool.min(rec.amount());
            remainingPaidPool = remainingPaidPool.subtract(covered).max(BigDecimal.ZERO);
            BigDecimal unpaid = rec.amount().subtract(covered);

            if (unpaid.compareTo(BigDecimal.ZERO) > 0) {
                // Tuổi nợ (Debt Age): tính từ ngày phát sinh nợ đến ngày chốt
                int ageDays = (int) ChronoUnit.DAYS.between(rec.debtDate(), asOfDate);
                if (ageDays < 0) ageDays = 0;
                maxDebtAgeDays = Math.max(maxDebtAgeDays, ageDays);

                // Phân bổ vào 4 nhóm tuổi nợ chuẩn: 0–30, 31–60, 61–90, trên 90 ngày
                if (ageDays <= 30) {
                    bucket0To30 = bucket0To30.add(unpaid);
                } else if (ageDays <= 60) {
                    bucket31To60 = bucket31To60.add(unpaid);
                } else if (ageDays <= 90) {
                    bucket61To90 = bucket61To90.add(unpaid);
                } else {
                    bucketOver90 = bucketOver90.add(unpaid);
                }

                // Xét nợ quá hạn
                if (rec.dueDate() != null && asOfDate.isAfter(rec.dueDate())) {
                    int overdueDays = (int) ChronoUnit.DAYS.between(rec.dueDate(), asOfDate);
                    if (overdueDays >= 1) {
                        overdueDebt = overdueDebt.add(unpaid);
                        maxOverdueDays = Math.max(maxOverdueDays, overdueDays);
                    } else {
                        inTermDebt = inTermDebt.add(unpaid);
                    }
                } else {
                    inTermDebt = inTermDebt.add(unpaid);
                }
            }
        }

        boolean isOverdue = overdueDebt.compareTo(BigDecimal.ZERO) > 0;

        Long assignedUserId = dealer.getAssignedUser() != null ? dealer.getAssignedUser().getId() : null;
        String assignedUserName = "Chưa phân công";
        if (dealer.getAssignedUser() != null) {
            User u = dealer.getAssignedUser();
            assignedUserName = (u.getFullName() != null && !u.getFullName().isBlank()) ? u.getFullName() : u.getUsername();
        }

        return DealerDebtAgingRowResponse.builder()
                .dealerId(dealer.getId())
                .dealerCode(dealer.getCode())
                .dealerName(dealer.getName())
                .phone(dealer.getPhone())
                .address(dealer.getAddress())
                .assignedUserId(assignedUserId)
                .assignedUserName(assignedUserName)
                .debtLimit(debtLimit)
                .maxDebtDays(maxDebtDays)
                .totalReceivable(totalReceivable)
                .totalPaid(totalPaid)
                .remainingDebt(remainingDebt)
                .inTermDebt(inTermDebt)
                .overdueDebt(overdueDebt)
                .maxDebtAgeDays(maxDebtAgeDays)
                .maxOverdueDays(maxOverdueDays)
                .isOverdue(isOverdue)
                .bucket0To30(bucket0To30)
                .bucket31To60(bucket31To60)
                .bucket61To90(bucket61To90)
                .bucketOver90(bucketOver90)
                .totalReceivableFormatted(formatMoney(totalReceivable))
                .totalPaidFormatted(formatMoney(totalPaid))
                .remainingDebtFormatted(formatMoney(remainingDebt))
                .inTermDebtFormatted(formatMoney(inTermDebt))
                .overdueDebtFormatted(formatMoney(overdueDebt))
                .bucket0To30Formatted(formatMoney(bucket0To30))
                .bucket31To60Formatted(formatMoney(bucket31To60))
                .bucket61To90Formatted(formatMoney(bucket61To90))
                .bucketOver90Formatted(formatMoney(bucketOver90))
                .build();
    }

    private DealerDebtAgingSummaryResponse buildSummary(List<DealerDebtAgingRowResponse> rows) {
        BigDecimal sumReceivable = BigDecimal.ZERO;
        BigDecimal sumPaid = BigDecimal.ZERO;
        BigDecimal sumRemaining = BigDecimal.ZERO;
        BigDecimal sumInTerm = BigDecimal.ZERO;
        BigDecimal sumOverdue = BigDecimal.ZERO;
        BigDecimal sumB0 = BigDecimal.ZERO;
        BigDecimal sumB31 = BigDecimal.ZERO;
        BigDecimal sumB61 = BigDecimal.ZERO;
        BigDecimal sumB90 = BigDecimal.ZERO;
        int overdueCount = 0;

        for (DealerDebtAgingRowResponse r : rows) {
            sumReceivable = sumReceivable.add(r.getTotalReceivable());
            sumPaid = sumPaid.add(r.getTotalPaid());
            sumRemaining = sumRemaining.add(r.getRemainingDebt());
            sumInTerm = sumInTerm.add(r.getInTermDebt());
            sumOverdue = sumOverdue.add(r.getOverdueDebt());
            sumB0 = sumB0.add(r.getBucket0To30());
            sumB31 = sumB31.add(r.getBucket31To60());
            sumB61 = sumB61.add(r.getBucket61To90());
            sumB90 = sumB90.add(r.getBucketOver90());
            if (Boolean.TRUE.equals(r.getIsOverdue())) {
                overdueCount++;
            }
        }

        return DealerDebtAgingSummaryResponse.builder()
                .totalReceivable(sumReceivable)
                .totalPaid(sumPaid)
                .totalRemainingDebt(sumRemaining)
                .totalInTermDebt(sumInTerm)
                .totalOverdueDebt(sumOverdue)
                .totalBucket0To30(sumB0)
                .totalBucket31To60(sumB31)
                .totalBucket61To90(sumB61)
                .totalBucketOver90(sumB90)
                .dealerCount(rows.size())
                .overdueDealerCount(overdueCount)
                .totalReceivableFormatted(formatMoney(sumReceivable))
                .totalPaidFormatted(formatMoney(sumPaid))
                .totalRemainingDebtFormatted(formatMoney(sumRemaining))
                .totalInTermDebtFormatted(formatMoney(sumInTerm))
                .totalOverdueDebtFormatted(formatMoney(sumOverdue))
                .totalBucket0To30Formatted(formatMoney(sumB0))
                .totalBucket31To60Formatted(formatMoney(sumB31))
                .totalBucket61To90Formatted(formatMoney(sumB61))
                .totalBucketOver90Formatted(formatMoney(sumB90))
                .build();
    }

    private String formatMoney(BigDecimal amount) {
        if (amount == null) return "0.00";
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    // ── XÁC THỰC VÀ PHÂN QUYỀN MA TRẬN DB ──

    private User validateAccessAndScope(Long requestedDealerId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new AccessDeniedException("Chưa xác thực danh tính người dùng.");
        }

        Set<String> authorities = new HashSet<>();
        for (GrantedAuthority ga : auth.getAuthorities()) {
            authorities.add(ga.getAuthority());
        }

        boolean isAdmin = authorities.contains("ROLE_ADMIN");
        boolean hasDebtView = authorities.contains(Permission.FINANCE_VIEW_DEBT.name());

        if (!isAdmin && !hasDebtView) {
            throw new AccessDeniedException("Không có quyền xem công nợ (FINANCE_VIEW_DEBT).");
        }

        User user = userRepository.findByUsername(auth.getName()).orElse(null);

        // Đại lý chỉ được xem công nợ của chính mình
        if (!isAdmin && authorities.contains(ERole.ROLE_DEALER.name())) {
            if (user == null || user.getDealer() == null) {
                throw new AccessDeniedException("Tài khoản đại lý chưa liên kết thông tin đại lý.");
            }
            if (requestedDealerId == null || !requestedDealerId.equals(user.getDealer().getId())) {
                throw new AccessDeniedException("Tài khoản đại lý chỉ được xem sổ công nợ của chính mình.");
            }
        }

        return user;
    }

    private List<Dealer> getScopedDealers(User currentUser, Long dealerId, String searchKeyword) {
        if (dealerId != null) {
            Dealer d = dealerRepository.findById(dealerId)
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đại lý có ID: " + dealerId));

            // Kiểm tra phân quyền nhân viên kinh doanh
            if (isSalesStaffOnly(currentUser)) {
                if (d.getAssignedUser() == null || !d.getAssignedUser().getId().equals(currentUser.getId())) {
                    throw new AccessDeniedException("Đại lý không thuộc phạm vi phụ trách của nhân viên.");
                }
            }
            return List.of(d);
        }

        List<Dealer> allDealers = dealerRepository.findAll();
        List<Dealer> result = new ArrayList<>();
        boolean salesStaffOnly = isSalesStaffOnly(currentUser);

        for (Dealer d : allDealers) {
            // Row-level security cho Sales
            if (salesStaffOnly) {
                if (d.getAssignedUser() == null || !d.getAssignedUser().getId().equals(currentUser.getId())) {
                    continue;
                }
            }

            // Keyword search
            if (searchKeyword != null) {
                boolean matchCode = d.getCode() != null && d.getCode().toLowerCase().contains(searchKeyword);
                boolean matchName = d.getName() != null && d.getName().toLowerCase().contains(searchKeyword);
                boolean matchPhone = d.getPhone() != null && d.getPhone().toLowerCase().contains(searchKeyword);
                if (!matchCode && !matchName && !matchPhone) {
                    continue;
                }
            }

            result.add(d);
        }

        return result;
    }

    private boolean isSalesStaffOnly(User user) {
        if (user == null || user.getRoles() == null) return false;
        boolean hasSales = user.getRoles().stream().anyMatch(r -> r.getName() == ERole.ROLE_SALES);
        boolean hasBroadRole = user.getRoles().stream().anyMatch(r ->
                r.getName() == ERole.ROLE_ADMIN ||
                r.getName() == ERole.ROLE_SALES_MANAGER ||
                r.getName() == ERole.ROLE_ACCOUNTANT ||
                r.getName() == ERole.ROLE_VIEWER);
        return hasSales && !hasBroadRole;
    }
}
