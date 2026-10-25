package com.quanlybanhang.service;

import com.quanlybanhang.dto.request.DebtPaymentAdjustmentRequest;
import com.quanlybanhang.dto.request.DebtPaymentRequest;
import com.quanlybanhang.dto.request.DebtRiskConfigRequest;
import com.quanlybanhang.dto.request.OrderDebtCheckRequest;
import com.quanlybanhang.dto.request.PaymentAllocationItemRequest;
import com.quanlybanhang.dto.response.*;
import com.quanlybanhang.exception.DealerDebtBlockedException;
import com.quanlybanhang.exception.DealerDebtOverdueException;
import com.quanlybanhang.exception.DealerLockedException;
import com.quanlybanhang.model.*;
import com.quanlybanhang.repository.DealerRepository;
import com.quanlybanhang.repository.DebtPaymentAllocationRepository;
import com.quanlybanhang.repository.DebtPaymentRepository;
import com.quanlybanhang.repository.DebtRiskConfigRepository;
import com.quanlybanhang.repository.InvoiceRepository;
import com.quanlybanhang.repository.OrderRepository;
import com.quanlybanhang.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
@RequiredArgsConstructor
public class DealerDebtServiceImpl implements DealerDebtService {

    private final com.quanlybanhang.repository.ReturnOrderRepository returnOrderRepository;
    private final DealerRepository dealerRepository;
    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final DebtPaymentRepository debtPaymentRepository;
    private final DebtRiskConfigRepository debtRiskConfigRepository;
    private final AuditLogService auditLogService;
    private final com.quanlybanhang.repository.DebtPaymentAllocationRepository debtPaymentAllocationRepository;

    @Autowired(required = false)
    private InvoiceRepository invoiceRepository;

    @Autowired(required = false)
    private InvoiceService invoiceService;

    public void setInvoiceRepository(InvoiceRepository invoiceRepository) {
        this.invoiceRepository = invoiceRepository;
    }

    public void setInvoiceService(InvoiceService invoiceService) {
        this.invoiceService = invoiceService;
    }

    @Override
    @Transactional(readOnly = true)
    public DealerDebtResponse getDealerDebtInfo(Long dealerId) {
        Dealer dealer = dealerRepository.findById(dealerId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đại lý với ID: " + dealerId));

        // S4-10 / S7-07: Đại lý chỉ nhìn thấy công nợ của chính mình
        if (isDealerUser()) {
            User currentUser = getCurrentUser();
            if (currentUser != null && (currentUser.getDealer() == null || !currentUser.getDealer().getId().equals(dealerId))) {
                throw new AccessDeniedException("Bạn chỉ có quyền xem công nợ của chính đại lý mình");
            }
        }

        // S3-06 / S4-02: Nhân viên kinh doanh chỉ nhìn thấy công nợ đại lý mình phụ trách
        if (isSalesOnlyUser()) {
            User currentUser = getCurrentUser();
            if (currentUser != null && (dealer.getAssignedUser() == null || !dealer.getAssignedUser().getId().equals(currentUser.getId()))) {
                throw new AccessDeniedException("Bạn chỉ có quyền xem đại lý do mình phụ trách");
            }
        }

        return getDealerDebtInfo(dealer);
    }

    @Override
    @Transactional(readOnly = true)
    public DealerDebtResponse getDealerDebtInfo(Dealer dealer) {
        BigDecimal debtLimit = dealer.getDebtLimit() != null ? dealer.getDebtLimit() : BigDecimal.ZERO;
        int maxDebtDays = dealer.getMaxDebtDays() != null ? dealer.getMaxDebtDays() : 0;

        // Tổng công nợ từ các đơn hàng ghi sổ
        BigDecimal totalOrderDebt = orderRepository.sumTotalAmountByDealerIdAndStatusIn(dealer.getId(), OrderStatus.debtBearingStatuses());
        if (totalOrderDebt == null) {
            totalOrderDebt = BigDecimal.ZERO;
        }

        // Tổng số tiền đã thanh toán / thu nợ (S7-04)
        BigDecimal totalPaid = BigDecimal.ZERO;
        if (debtPaymentRepository != null) {
            BigDecimal sumPaid = debtPaymentRepository.sumAmountByDealerId(dealer.getId());
            if (sumPaid != null) {
                totalPaid = sumPaid;
            }
        }

        java.util.Map<Long, BigDecimal> returnCredits = new java.util.HashMap<>();
        if (returnOrderRepository != null) {
            returnOrderRepository.creditsByOrder(dealer.getId(), OrderStatus.debtBearingStatuses())
                    .forEach(row -> returnCredits.put(row.getOrderId(), row.getAmount()));
        }
        BigDecimal totalReturns = returnCredits.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<Long, BigDecimal> invoiceCredits = FinancialReturnCredits.byInvoice(returnOrderRepository, dealer.getId());

        List<Order> recordedOrders = orderRepository.findByDealerIdAndStatusInOrderByRecordedAtAsc(dealer.getId(), OrderStatus.debtBearingStatuses());
        if (recordedOrders == null) {
            recordedOrders = Collections.emptyList();
        }

        // S7-07: Tích hợp danh sách hóa đơn đã phát hành và chứng từ điều chỉnh
        List<Invoice> dealerInvoices = (invoiceRepository != null && dealer.getId() != null)
                ? invoiceRepository.findByDealerIdOrderByInvoiceDateAscIdAsc(dealer.getId())
                : Collections.emptyList();
        if (dealerInvoices == null) {
            dealerInvoices = Collections.emptyList();
        }

        BigDecimal totalInvoicedAmount = BigDecimal.ZERO;
        Set<Long> invoicedOrderIds = new HashSet<>();
        for (Invoice inv : dealerInvoices) {
            if (inv.getStatus() != InvoiceStatus.CANCELLED) {
                BigDecimal effAmt = FinancialReturnCredits.netInvoice(inv, invoiceCredits);
                if (effAmt != null && effAmt.compareTo(BigDecimal.ZERO) > 0) {
                    totalInvoicedAmount = totalInvoicedAmount.add(effAmt);
                }
                if (inv.getOrders() != null) {
                    for (Order o : inv.getOrders()) {
                        if (o != null && o.getId() != null) {
                            invoicedOrderIds.add(o.getId());
                        }
                    }
                }
            }
        }

        BigDecimal uninvoicedOrderDebt;
        BigDecimal grossDebt;
        if (!dealerInvoices.isEmpty()) {
            if (!recordedOrders.isEmpty()) {
                uninvoicedOrderDebt = BigDecimal.ZERO;
                for (Order o : recordedOrders) {
                    if (o.getId() == null || !invoicedOrderIds.contains(o.getId())) {
                        BigDecimal amt = (o.getTotalAmount() != null ? o.getTotalAmount() : BigDecimal.ZERO)
                                .subtract(returnCredits.getOrDefault(o.getId(), BigDecimal.ZERO)).max(BigDecimal.ZERO);
                        uninvoicedOrderDebt = uninvoicedOrderDebt.add(amt);
                    }
                }
            } else if (invoicedOrderIds.isEmpty() && totalOrderDebt.compareTo(BigDecimal.ZERO) > 0) {
                uninvoicedOrderDebt = totalOrderDebt.subtract(totalReturns).max(BigDecimal.ZERO);
            } else {
                uninvoicedOrderDebt = BigDecimal.ZERO;
            }
            grossDebt = totalInvoicedAmount.add(uninvoicedOrderDebt);
        } else {
            uninvoicedOrderDebt = totalOrderDebt.subtract(totalReturns).max(BigDecimal.ZERO);
            grossDebt = uninvoicedOrderDebt;
        }

        // Credits were applied to their exact invoice/uninvoiced order above; do not deduct twice.
        BigDecimal currentDebt = grossDebt.subtract(totalPaid).max(BigDecimal.ZERO);
        BigDecimal remainingLimit = debtLimit.subtract(currentDebt);

        LocalDate today = LocalDate.now();
        boolean hasOverdueDebt = false;
        int maxOverdueDays = 0;
        BigDecimal overdueAmount = BigDecimal.ZERO;
        String oldestOrderCode = null;
        LocalDate oldestOrderDate = null;

        // Phân bổ FIFO cho danh sách hóa đơn của đại lý (S7-07)
        List<InvoiceResponse> invoiceResponses = buildInvoiceResponsesWithFifo(dealerInvoices, totalPaid, maxDebtDays, invoiceCredits);

        if (!dealerInvoices.isEmpty()) {
            // 1. Kiểm tra quá hạn trên các hóa đơn đã phát hành (theo dueDate của từng hóa đơn)
            BigDecimal allocatedToInvoices = BigDecimal.ZERO;
            for (InvoiceResponse invResp : invoiceResponses) {
                if (invResp.getPaidAmount() != null) {
                    allocatedToInvoices = allocatedToInvoices.add(invResp.getPaidAmount());
                }
                if (Boolean.TRUE.equals(invResp.getIsOverdue())
                        && invResp.getRemainingAmount() != null
                        && invResp.getRemainingAmount().compareTo(BigDecimal.ZERO) > 0) {
                    hasOverdueDebt = true;
                    overdueAmount = overdueAmount.add(invResp.getRemainingAmount());
                    int invOverdueDays = invResp.getOverdueDays() != null ? invResp.getOverdueDays() : 0;
                    if (invOverdueDays > maxOverdueDays) {
                        maxOverdueDays = invOverdueDays;
                        oldestOrderCode = invResp.getInvoiceCode();
                        oldestOrderDate = invResp.getInvoiceDate() != null
                                ? invResp.getInvoiceDate().toLocalDate()
                                : invResp.getDueDate();
                    }
                }
            }

            // 2. Kiểm tra quá hạn trên các đơn hàng ghi sổ chưa xuất hóa đơn (nếu có)
            if (maxDebtDays > 0) {
                BigDecimal remainingPaymentForOrders = totalPaid.subtract(allocatedToInvoices).max(BigDecimal.ZERO);
                for (Order order : recordedOrders) {
                    if (order.getId() != null && invoicedOrderIds.contains(order.getId())) {
                        continue; // Đã tính trên hóa đơn
                    }
                    BigDecimal orderAmt = (order.getTotalAmount() != null ? order.getTotalAmount() : BigDecimal.ZERO)
                            .subtract(returnCredits.getOrDefault(order.getId(), BigDecimal.ZERO)).max(BigDecimal.ZERO);
                    if (remainingPaymentForOrders.compareTo(orderAmt) >= 0) {
                        remainingPaymentForOrders = remainingPaymentForOrders.subtract(orderAmt);
                        continue;
                    }
                    BigDecimal unpaidOrderAmt = orderAmt.subtract(remainingPaymentForOrders);
                    remainingPaymentForOrders = BigDecimal.ZERO;

                    LocalDateTime recordTime = order.getRecordedAt() != null ? order.getRecordedAt() : order.getCreatedAt();
                    if (recordTime != null) {
                        LocalDate orderDate = recordTime.toLocalDate();
                        long days = ChronoUnit.DAYS.between(orderDate, today);
                        if (days > maxDebtDays) {
                            hasOverdueDebt = true;
                            int overdueDiff = (int) (days - maxDebtDays);
                            if (overdueDiff > maxOverdueDays) {
                                maxOverdueDays = overdueDiff;
                                oldestOrderCode = order.getOrderCode();
                                oldestOrderDate = orderDate;
                            }
                            overdueAmount = overdueAmount.add(unpaidOrderAmt);
                        }
                    }
                }
            }
        } else if (maxDebtDays > 0) {
            BigDecimal unallocatedPayment = totalPaid;
            for (Order order : recordedOrders) {
                BigDecimal orderAmt = (order.getTotalAmount() != null ? order.getTotalAmount() : BigDecimal.ZERO)
                        .subtract(returnCredits.getOrDefault(order.getId(), BigDecimal.ZERO)).max(BigDecimal.ZERO);
                if (unallocatedPayment.compareTo(orderAmt) >= 0) {
                    // Đơn này đã được thanh toán hết (FIFO)
                    unallocatedPayment = unallocatedPayment.subtract(orderAmt);
                    continue;
                }
                // Đơn này còn số dư chưa thanh toán
                BigDecimal unpaidOrderAmt = orderAmt.subtract(unallocatedPayment);
                unallocatedPayment = BigDecimal.ZERO;

                LocalDateTime recordTime = order.getRecordedAt() != null ? order.getRecordedAt() : order.getCreatedAt();
                if (recordTime != null) {
                    LocalDate orderDate = recordTime.toLocalDate();
                    long days = ChronoUnit.DAYS.between(orderDate, today);
                    if (days > maxDebtDays) {
                        hasOverdueDebt = true;
                        int overdueDiff = (int) (days - maxDebtDays);
                        if (overdueDiff > maxOverdueDays) {
                            maxOverdueDays = overdueDiff;
                            oldestOrderCode = order.getOrderCode();
                            oldestOrderDate = orderDate;
                        }
                        overdueAmount = overdueAmount.add(unpaidOrderAmt);
                    }
                }
            }
        }

        DebtFlagAction effectiveAction = resolveEffectiveAction(dealer);

        boolean isBlocked = false;
        String blockReason = null;
        if (Boolean.TRUE.equals(dealer.getLocked())) {
            isBlocked = true;
            blockReason = String.format("Đại lý '%s' (%s) đã bị khoá giao dịch. Lý do: %s",
                    dealer.getName(), dealer.getCode(), dealer.getLockReason() != null ? dealer.getLockReason() : "Không nêu rõ");
        } else if (Boolean.TRUE.equals(dealer.getDebtFlagged()) && effectiveAction == DebtFlagAction.BLOCK) {
            isBlocked = true;
            blockReason = String.format("Đại lý '%s' (%s) đang bị gắn cờ cảnh báo rủi ro công nợ: %s (Hệ thống chặn bán theo cấu hình).",
                    dealer.getName(), dealer.getCode(), dealer.getDebtFlagReason());
        } else if (hasOverdueDebt && effectiveAction == DebtFlagAction.BLOCK) {
            isBlocked = true;
            blockReason = String.format("Đại lý '%s' (%s) có khoản nợ quá hạn %d ngày (vượt quá giới hạn cho phép %d ngày), bị chặn tạo đơn hoàn toàn.",
                    dealer.getName(), dealer.getCode(), maxOverdueDays, maxDebtDays);
        }

        BigDecimal availableOrderLimit = isBlocked ? BigDecimal.ZERO : remainingLimit.max(BigDecimal.ZERO);

        int totalInvoiceCount = (int) invoiceResponses.stream()
                .filter(i -> i.getStatus() != InvoiceStatus.CANCELLED)
                .count();
        int unpaidInvoiceCount = (int) invoiceResponses.stream()
                .filter(i -> i.getRemainingAmount() != null && i.getRemainingAmount().compareTo(BigDecimal.ZERO) > 0)
                .count();
        int overdueInvoiceCount = (int) invoiceResponses.stream()
                .filter(i -> Boolean.TRUE.equals(i.getIsOverdue()))
                .count();
        int paidInvoiceCount = (int) invoiceResponses.stream()
                .filter(i -> "PAID".equals(i.getPaymentStatus()))
                .count();

        List<DebtPaymentResponse> paymentResponses = Collections.emptyList();
        if (debtPaymentRepository != null && dealer.getId() != null) {
            List<DebtPayment> rawPayments = debtPaymentRepository.findByDealerIdOrderByPaymentDateDescCreatedAtDesc(dealer.getId());
            if (rawPayments != null) {
                paymentResponses = rawPayments.stream().map(this::toPaymentResponse).toList();
            }
        }

        return DealerDebtResponse.builder()
                .dealerId(dealer.getId())
                .dealerCode(dealer.getCode())
                .dealerName(dealer.getName())
                .currentDebt(currentDebt)
                .debtLimit(debtLimit)
                .remainingLimit(remainingLimit)
                .availableOrderLimit(availableOrderLimit)
                .totalInvoicedAmount(totalInvoicedAmount)
                .uninvoicedOrderDebt(uninvoicedOrderDebt)
                .totalInvoiceCount(totalInvoiceCount)
                .unpaidInvoiceCount(unpaidInvoiceCount)
                .overdueInvoiceCount(overdueInvoiceCount)
                .paidInvoiceCount(paidInvoiceCount)
                .invoices(invoiceResponses)
                .payments(paymentResponses)
                .maxDebtDays(maxDebtDays)
                .hasOverdueDebt(hasOverdueDebt)
                .overdueAmount(overdueAmount)
                .maxOverdueDays(maxOverdueDays)
                .oldestOverdueOrderCode(oldestOrderCode)
                .oldestOverdueOrderDate(oldestOrderDate)
                .isBlocked(isBlocked)
                .blockReason(blockReason)
                .totalPaid(totalPaid)
                .isDebtFlagged(Boolean.TRUE.equals(dealer.getDebtFlagged()))
                .debtFlagType(dealer.getDebtFlagType() != null ? dealer.getDebtFlagType() : DebtFlagType.NONE)
                .debtFlagReason(dealer.getDebtFlagReason())
                .debtFlaggedAt(dealer.getDebtFlaggedAt())
                .lastDebtEvaluatedAt(dealer.getLastDebtEvaluatedAt())
                .effectiveAction(effectiveAction)
                .build();
    }

    private List<InvoiceResponse> buildInvoiceResponsesWithFifo(List<Invoice> ascInvoices, BigDecimal totalPaid, int defaultMaxDebtDays, Map<Long, BigDecimal> invoiceCredits) {
        if (ascInvoices == null || ascInvoices.isEmpty()) {
            return Collections.emptyList();
        }
        if (invoiceService != null && ascInvoices.get(0).getDealer() != null && ascInvoices.get(0).getDealer().getId() != null) {
            List<InvoiceResponse> fromService = invoiceService.getDealerInvoiceList(ascInvoices.get(0).getDealer().getId());
            if (fromService != null && !fromService.isEmpty()) {
                return fromService;
            }
        }

        BigDecimal remainingPool = totalPaid != null && totalPaid.compareTo(BigDecimal.ZERO) > 0 ? totalPaid : BigDecimal.ZERO;
        LocalDate today = LocalDate.now();
        List<InvoiceResponse> builtAsc = new ArrayList<>();

        for (Invoice inv : ascInvoices) {
            BigDecimal effectiveAmount = FinancialReturnCredits.netInvoice(inv, invoiceCredits);
            if (effectiveAmount == null || effectiveAmount.compareTo(BigDecimal.ZERO) < 0) {
                effectiveAmount = BigDecimal.ZERO;
            }

            BigDecimal paidAmount = BigDecimal.ZERO;
            if (inv.getStatus() != InvoiceStatus.CANCELLED && effectiveAmount.compareTo(BigDecimal.ZERO) > 0 && remainingPool.compareTo(BigDecimal.ZERO) > 0) {
                paidAmount = remainingPool.min(effectiveAmount);
                remainingPool = remainingPool.subtract(paidAmount).max(BigDecimal.ZERO);
            }
            BigDecimal remainingAmount = effectiveAmount.subtract(paidAmount).max(BigDecimal.ZERO);

            LocalDate dueDate = inv.getDueDate();
            if (dueDate == null) {
                LocalDate baseDate = inv.getInvoiceDate() != null
                        ? inv.getInvoiceDate().toLocalDate()
                        : (inv.getCreatedAt() != null ? inv.getCreatedAt().toLocalDate() : today);
                dueDate = baseDate.plusDays(Math.max(0, defaultMaxDebtDays));
            }

            String paymentStatus;
            String paymentStatusLabel;
            boolean isOverdue = false;
            int overdueDays = 0;
            int daysUntilDue = 0;

            if (inv.getStatus() == InvoiceStatus.CANCELLED) {
                paymentStatus = "CANCELLED";
                paymentStatusLabel = "Đã hủy";
            } else if (remainingAmount.compareTo(BigDecimal.ZERO) <= 0) {
                paymentStatus = "PAID";
                paymentStatusLabel = "Đã thanh toán";
            } else if (dueDate != null && today.isAfter(dueDate)) {
                isOverdue = true;
                overdueDays = (int) ChronoUnit.DAYS.between(dueDate, today);
                paymentStatus = "OVERDUE";
                paymentStatusLabel = "Quá hạn thanh toán";
            } else {
                if (dueDate != null && !today.isAfter(dueDate)) {
                    daysUntilDue = (int) ChronoUnit.DAYS.between(today, dueDate);
                }
                if (paidAmount.compareTo(BigDecimal.ZERO) > 0) {
                    paymentStatus = "PARTIALLY_PAID";
                    paymentStatusLabel = "Thanh toán một phần";
                } else {
                    paymentStatus = "UNPAID";
                    paymentStatusLabel = "Chưa thanh toán";
                }
            }

            builtAsc.add(InvoiceResponse.builder()
                    .id(inv.getId())
                    .invoiceNumber(inv.getInvoiceNumber())
                    .invoiceCode(inv.getInvoiceCode())
                    .invoiceDate(inv.getInvoiceDate())
                    .dueDate(dueDate)
                    .status(inv.getStatus())
                    .statusLabel(inv.getStatus() != null ? inv.getStatus().getDisplayName() : null)
                    .dealerId(inv.getDealer() != null ? inv.getDealer().getId() : null)
                    .dealerCode(inv.getDealerCode())
                    .dealerName(inv.getDealerName())
                    .dealerTaxCode(inv.getDealerTaxCode())
                    .dealerAddress(inv.getDealerAddress())
                    .dealerPhone(inv.getDealerPhone())
                    .subtotalAmount(inv.getSubtotalAmount())
                    .discountAmount(inv.getDiscountAmount())
                    .totalAmount(inv.getTotalAmount())
                    .adjustedTotalAmount(inv.getAdjustedTotalAmount())
                    .effectiveAmount(effectiveAmount)
                    .isPaid(inv.getStatus() != InvoiceStatus.CANCELLED && remainingAmount.signum() == 0)
                    .paidAmount(paidAmount)
                    .remainingAmount(remainingAmount)
                    .paymentStatus(paymentStatus)
                    .paymentStatusLabel(paymentStatusLabel)
                    .isOverdue(isOverdue)
                    .overdueDays(overdueDays)
                    .daysUntilDue(daysUntilDue)
                    .note(inv.getNote())
                    .createdBy(inv.getCreatedBy())
                    .createdAt(inv.getCreatedAt())
                    .build());
        }

        builtAsc.sort(Comparator
                .comparing(InvoiceResponse::getInvoiceDate, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(InvoiceResponse::getId, Comparator.nullsLast(Comparator.reverseOrder())));
        return builtAsc;
    }

    @Override
    @Transactional(readOnly = true)
    public OrderDebtCheckResponse checkOrderDebt(OrderDebtCheckRequest request) {
        if (request == null || request.getDealerId() == null) {
            throw new IllegalArgumentException("Dữ liệu kiểm tra công nợ không hợp lệ");
        }
        return checkOrderDebt(request.getDealerId(), request.getOrderAmount() != null ? request.getOrderAmount() : BigDecimal.ZERO);
    }

    @Override
    @Transactional(readOnly = true)
    public OrderDebtCheckResponse checkOrderDebt(Long dealerId, BigDecimal orderAmount) {
        Dealer dealer = dealerRepository.findById(dealerId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đại lý với ID: " + dealerId));

        if (isSalesOnlyUser()) {
            User currentUser = getCurrentUser();
            if (currentUser != null && (dealer.getAssignedUser() == null || !dealer.getAssignedUser().getId().equals(currentUser.getId()))) {
                throw new AccessDeniedException("Bạn chỉ có quyền xem đại lý do mình phụ trách");
            }
        }

        DealerDebtResponse debtInfo = getDealerDebtInfo(dealer);
        BigDecimal newOrderTotal = orderAmount != null ? orderAmount : BigDecimal.ZERO;
        BigDecimal projectedDebt = debtInfo.getCurrentDebt().add(newOrderTotal);

        boolean exceedsLimit = checkProjectedDebtExceedsLimit(dealer, newOrderTotal, debtInfo.getCurrentDebt());
        DebtFlagAction effectiveAction = debtInfo.getEffectiveAction();
        boolean isFlagged = Boolean.TRUE.equals(debtInfo.getIsDebtFlagged());

        boolean requiresApproval = false;
        String approvalReason = null;

        if (isFlagged && effectiveAction == DebtFlagAction.REQUIRE_APPROVAL) {
            requiresApproval = true;
            approvalReason = String.format("Đại lý đang bị gắn cờ rủi ro công nợ: %s (Hệ thống chuyển cần duyệt theo cấu hình)",
                    debtInfo.getDebtFlagReason());
        } else if (exceedsLimit) {
            requiresApproval = true;
            approvalReason = String.format("Vượt hạn mức công nợ: Công nợ hiện tại (%s) + Đơn mới (%s) = %s vượt hạn mức (%s)",
                    formatMoney(debtInfo.getCurrentDebt()),
                    formatMoney(newOrderTotal),
                    formatMoney(projectedDebt),
                    formatMoney(debtInfo.getDebtLimit()));
        }

        String warningMessage;
        if (Boolean.TRUE.equals(debtInfo.getIsBlocked())) {
            warningMessage = debtInfo.getBlockReason();
        } else if (isFlagged && effectiveAction == DebtFlagAction.REQUIRE_APPROVAL) {
            warningMessage = String.format("CẢNH BÁO: Đại lý đang bị gắn cờ rủi ro (%s). Đơn hàng sẽ cần Quản lý duyệt.",
                    debtInfo.getDebtFlagReason());
        } else if (exceedsLimit) {
            warningMessage = String.format("Tổng đơn cộng công nợ hiện tại (%s) vượt hạn mức (%s). Đơn hàng sẽ cần Quản lý duyệt.",
                    formatMoney(projectedDebt), formatMoney(debtInfo.getDebtLimit()));
        } else if (debtInfo.getDebtLimit().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal afterOrderRemaining = debtInfo.getDebtLimit().subtract(projectedDebt);
            warningMessage = String.format("Hạn mức công nợ hợp lệ. Hạn mức còn lại sau đơn này: %s",
                    formatMoney(afterOrderRemaining));
        } else {
            warningMessage = "Đại lý chưa thiết lập hạn mức công nợ.";
        }

        return OrderDebtCheckResponse.builder()
                .dealerId(dealer.getId())
                .dealerCode(dealer.getCode())
                .dealerName(dealer.getName())
                .currentDebt(debtInfo.getCurrentDebt())
                .debtLimit(debtInfo.getDebtLimit())
                .remainingLimit(debtInfo.getRemainingLimit())
                .newOrderTotal(newOrderTotal)
                .projectedDebt(projectedDebt)
                .exceedsLimit(exceedsLimit)
                .requiresApproval(requiresApproval)
                .approvalReason(approvalReason)
                .hasOverdueDebt(debtInfo.getHasOverdueDebt())
                .maxOverdueDays(debtInfo.getMaxOverdueDays())
                .overdueAmount(debtInfo.getOverdueAmount())
                .isBlocked(debtInfo.getIsBlocked())
                .blockReason(debtInfo.getBlockReason())
                .warningMessage(warningMessage)
                .isDebtFlagged(isFlagged)
                .debtFlagType(debtInfo.getDebtFlagType())
                .debtFlagReason(debtInfo.getDebtFlagReason())
                .effectiveAction(effectiveAction)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public void validateDealerCanCreateOrder(Dealer dealer) {
        if (dealer == null) {
            return;
        }

        // S3-07: Khoá thủ công luôn là Hard Block
        if (Boolean.TRUE.equals(dealer.getLocked())) {
            throw new DealerLockedException(dealer.getCode(), dealer.getName(), dealer.getLockReason());
        }

        DebtFlagAction effectiveAction = resolveEffectiveAction(dealer);

        // S7-04: Nếu đại lý bị gắn cờ và hành vi cấu hình là BLOCK
        if (Boolean.TRUE.equals(dealer.getDebtFlagged()) && effectiveAction == DebtFlagAction.BLOCK) {
            throw new DealerDebtBlockedException(
                    dealer.getCode(),
                    dealer.getName(),
                    dealer.getDebtFlagReason());
        }

        // Kiểm tra nợ quá hạn thời gian thực (nếu cấu hình là BLOCK)
        DealerDebtResponse debtInfo = getDealerDebtInfo(dealer);
        if (Boolean.TRUE.equals(debtInfo.getHasOverdueDebt()) && effectiveAction == DebtFlagAction.BLOCK) {
            throw new DealerDebtOverdueException(
                    dealer.getCode(),
                    dealer.getName(),
                    debtInfo.getMaxOverdueDays(),
                    debtInfo.getMaxDebtDays(),
                    debtInfo.getOverdueAmount());
        }
        // Nếu cấu hình là REQUIRE_APPROVAL, không chặn tại đây mà chuyển sang cần duyệt trong createOrder
    }

    @Override
    public boolean checkProjectedDebtExceedsLimit(Dealer dealer, BigDecimal newOrderTotal, BigDecimal currentDebt) {
        if (dealer == null || dealer.getDebtLimit() == null || dealer.getDebtLimit().compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }

        BigDecimal baseDebt = currentDebt != null ? currentDebt : BigDecimal.ZERO;
        BigDecimal orderAmount = newOrderTotal != null ? newOrderTotal : BigDecimal.ZERO;
        BigDecimal projectedDebt = baseDebt.add(orderAmount);

        return projectedDebt.compareTo(dealer.getDebtLimit()) > 0;
    }

    // ──────────────────────── S7-04: ĐÁNH GIÁ LẠI & GẮN CỜ ────────────────────────

    @Override
    @Transactional
    public FlaggedDealerResponse evaluateDealerDebt(Long dealerId) {
        Dealer dealer = dealerRepository.findById(dealerId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đại lý với ID: " + dealerId));
        return evaluateDealerDebtInternal(dealer);
    }

    private FlaggedDealerResponse evaluateDealerDebtInternal(Dealer dealer) {
        DebtRiskConfig config = getOrCreateConfig();
        boolean autoFlagEnabled = Boolean.TRUE.equals(config.getAutoFlagEnabled());

        DealerDebtResponse debtInfo = getDealerDebtInfo(dealer);
        BigDecimal currentDebt = debtInfo.getCurrentDebt();
        BigDecimal debtLimit = debtInfo.getDebtLimit();
        boolean hasOverdue = Boolean.TRUE.equals(debtInfo.getHasOverdueDebt());
        boolean exceedsLimit = debtLimit.compareTo(BigDecimal.ZERO) > 0 && currentDebt.compareTo(debtLimit) > 0;

        DebtFlagType newFlagType;
        if (exceedsLimit && hasOverdue) {
            newFlagType = DebtFlagType.BOTH;
        } else if (exceedsLimit) {
            newFlagType = DebtFlagType.DEBT_LIMIT_EXCEEDED;
        } else if (hasOverdue) {
            newFlagType = DebtFlagType.OVERDUE;
        } else {
            newFlagType = DebtFlagType.NONE;
        }

        boolean shouldFlag = autoFlagEnabled && (newFlagType != DebtFlagType.NONE);
        boolean wasFlagged = Boolean.TRUE.equals(dealer.getDebtFlagged());

        List<String> reasons = new ArrayList<>();
        if (exceedsLimit) {
            BigDecimal excess = currentDebt.subtract(debtLimit);
            reasons.add(String.format("Vượt hạn mức nợ: Nợ hiện tại (%s) vượt hạn mức (%s) một khoản %s",
                    formatMoney(currentDebt), formatMoney(debtLimit), formatMoney(excess)));
        }
        if (hasOverdue) {
            reasons.add(String.format("Nợ quá hạn %d ngày (vượt quá số ngày cho phép %d ngày) với tổng nợ quá hạn %s (đơn cũ nhất: %s)",
                    debtInfo.getMaxOverdueDays(), debtInfo.getMaxDebtDays(), formatMoney(debtInfo.getOverdueAmount()),
                    debtInfo.getOldestOverdueOrderCode() != null ? debtInfo.getOldestOverdueOrderCode() : "N/A"));
        }
        String flagReason = shouldFlag ? String.join("; ", reasons) : null;

        dealer.setDebtFlagged(shouldFlag);
        dealer.setDebtFlagType(newFlagType);
        dealer.setDebtFlagReason(flagReason);
        dealer.setLastDebtEvaluatedAt(LocalDateTime.now());
        if (shouldFlag && !wasFlagged) {
            dealer.setDebtFlaggedAt(LocalDateTime.now());
        } else if (!shouldFlag) {
            dealer.setDebtFlaggedAt(null);
        }

        dealerRepository.save(dealer);

        if (wasFlagged != shouldFlag) {
            auditLogService.logAction(
                    "DEALER_DEBT_FLAG",
                    dealer.getId().toString(),
                    shouldFlag ? "FLAG" : "UNFLAG",
                    wasFlagged ? "FLAGGED" : "NORMAL",
                    shouldFlag ? ("FLAGGED: " + newFlagType.name()) : "NORMAL",
                    flagReason != null ? flagReason : "Đã thanh toán đủ nợ hoặc không còn nợ quá hạn; tự động gỡ cờ rủi ro",
                    "system");
        }

        DebtFlagAction effectiveAction = resolveEffectiveAction(dealer);
        BigDecimal excessAmount = exceedsLimit ? currentDebt.subtract(debtLimit) : BigDecimal.ZERO;

        return FlaggedDealerResponse.builder()
                .dealerId(dealer.getId())
                .dealerCode(dealer.getCode())
                .dealerName(dealer.getName())
                .currentDebt(currentDebt)
                .debtLimit(debtLimit)
                .excessDebtAmount(excessAmount)
                .maxDebtDays(dealer.getMaxDebtDays())
                .maxOverdueDays(debtInfo.getMaxOverdueDays())
                .overdueAmount(debtInfo.getOverdueAmount())
                .debtFlagType(newFlagType)
                .debtFlagReason(flagReason)
                .debtFlaggedAt(dealer.getDebtFlaggedAt())
                .lastDebtEvaluatedAt(dealer.getLastDebtEvaluatedAt())
                .configuredAction(dealer.getDebtActionConfig())
                .effectiveAction(effectiveAction)
                .build();
    }

    @Override
    @Transactional
    public DebtEvaluationSummaryResponse evaluateAllDealersDebt() {
        List<Dealer> dealers = dealerRepository.findByActiveTrueOrderByNameAsc();
        int totalEvaluated = 0;
        int totalFlagged = 0;
        int newlyFlagged = 0;
        int unflagged = 0;
        int limitExceededCount = 0;
        int overdueCount = 0;
        int bothCount = 0;
        List<FlaggedDealerResponse> flaggedList = new ArrayList<>();

        for (Dealer dealer : dealers) {
            boolean wasFlagged = Boolean.TRUE.equals(dealer.getDebtFlagged());
            FlaggedDealerResponse res = evaluateDealerDebtInternal(dealer);
            totalEvaluated++;
            if (Boolean.TRUE.equals(dealer.getDebtFlagged())) {
                totalFlagged++;
                if (!wasFlagged) {
                    newlyFlagged++;
                }
                if (dealer.getDebtFlagType() == DebtFlagType.DEBT_LIMIT_EXCEEDED) {
                    limitExceededCount++;
                } else if (dealer.getDebtFlagType() == DebtFlagType.OVERDUE) {
                    overdueCount++;
                } else if (dealer.getDebtFlagType() == DebtFlagType.BOTH) {
                    bothCount++;
                }
                flaggedList.add(res);
            } else if (wasFlagged) {
                unflagged++;
            }
        }

        return DebtEvaluationSummaryResponse.builder()
                .evaluatedAt(LocalDateTime.now())
                .totalEvaluated(totalEvaluated)
                .totalFlagged(totalFlagged)
                .newlyFlagged(newlyFlagged)
                .unflagged(unflagged)
                .limitExceededCount(limitExceededCount)
                .overdueCount(overdueCount)
                .bothCount(bothCount)
                .flaggedDealers(flaggedList)
                .build();
    }

    // ──────────────────────── S7-02 & S7-04: GHI NHẬN THU TIỀN & ĐỐI TRỪ CÔNG NỢ ────────────────────────

    @Override
    @Transactional
    public DebtPaymentResponse recordPayment(DebtPaymentRequest request, String username) {
        if (request == null || request.getDealerId() == null) {
            throw new IllegalArgumentException("Thông tin thu tiền không hợp lệ");
        }
        if (request.getAmount() == null || request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Số tiền thu phải lớn hơn 0");
        }

        Dealer dealer = dealerRepository.findById(request.getDealerId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đại lý với ID: " + request.getDealerId()));

        List<Order> recordedOrders = orderRepository.findByDealerIdAndStatusInOrderByRecordedAtAsc(
                dealer.getId(), OrderStatus.debtBearingStatuses());

        BigDecimal totalRemainingDebt = BigDecimal.ZERO;
        for (Order o : recordedOrders) {
            BigDecimal orderAmt = o.getTotalAmount() != null ? o.getTotalAmount() : BigDecimal.ZERO;
            BigDecimal paidAmt = o.getPaidAmount() != null ? o.getPaidAmount() : BigDecimal.ZERO;
            BigDecimal rem = orderAmt.subtract(paidAmt).max(BigDecimal.ZERO);
            totalRemainingDebt = totalRemainingDebt.add(rem);
        }

        // Tiêu chí S7-02: Không cho thu vượt tổng công nợ còn lại
        if (!recordedOrders.isEmpty()) {
            if (totalRemainingDebt.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException(String.format("Đại lý '%s' hiện không còn công nợ để thu tiền.", dealer.getName()));
            }
            if (request.getAmount().compareTo(totalRemainingDebt) > 0) {
                throw new IllegalArgumentException(String.format(
                        "Số tiền thu (%s) không được vượt quá tổng công nợ còn lại của đại lý (%s).",
                        formatMoney(request.getAmount()), formatMoney(totalRemainingDebt)));
            }
        }

        LocalDate paymentDate = request.getPaymentDate() != null ? request.getPaymentDate() : LocalDate.now();
        String paymentCode = generatePaymentCode(paymentDate);

        String collector = request.getRecordedBy() != null && !request.getRecordedBy().isBlank()
                ? request.getRecordedBy().trim()
                : (username != null ? username : "system");

        DebtPayment payment = DebtPayment.builder()
                .paymentCode(paymentCode)
                .dealer(dealer)
                .amount(request.getAmount())
                .paymentDate(paymentDate)
                .paymentMethod(request.getPaymentMethod() != null ? request.getPaymentMethod().trim() : "CHUYEN_KHOAN")
                .referenceCode(request.getReferenceCode() != null ? request.getReferenceCode().trim() : null)
                .note(request.getNote() != null ? request.getNote().trim() : null)
                .recordedBy(collector)
                .paymentType("PAYMENT")
                .build();

        debtPaymentRepository.save(payment);

        List<DebtPaymentAllocation> createdAllocations = new ArrayList<>();

        boolean hasSpecificAllocations = request.getAllocations() != null && !request.getAllocations().isEmpty()
                && !Boolean.TRUE.equals(request.getAutoAllocate());

        if (hasSpecificAllocations) {
            // Tiêu chí S7-02: Chọn đối trừ vào hoá đơn cụ thể
            BigDecimal sumAllocated = BigDecimal.ZERO;
            for (PaymentAllocationItemRequest item : request.getAllocations()) {
                if (item.getOrderId() == null) {
                    throw new IllegalArgumentException("Mã đơn hàng/hoá đơn đối trừ không được để trống");
                }
                if (item.getAmount() == null || item.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
                    throw new IllegalArgumentException("Số tiền đối trừ cho mỗi hoá đơn phải lớn hơn 0");
                }
                Order order = orderRepository.findById(item.getOrderId())
                        .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy hoá đơn với ID: " + item.getOrderId()));

                if (!order.getDealer().getId().equals(dealer.getId())) {
                    throw new IllegalArgumentException("Hoá đơn '" + order.getOrderCode() + "' không thuộc đại lý '" + dealer.getName() + "'");
                }
                if (!OrderStatus.debtBearingStatuses().contains(order.getStatus())) {
                    throw new IllegalArgumentException("Hoá đơn '" + order.getOrderCode() + "' không ở trạng thái ghi nhận nợ");
                }

                BigDecimal orderTotal = order.getTotalAmount() != null ? order.getTotalAmount() : BigDecimal.ZERO;
                BigDecimal currentPaid = order.getPaidAmount() != null ? order.getPaidAmount() : BigDecimal.ZERO;
                BigDecimal orderRemaining = orderTotal.subtract(currentPaid).max(BigDecimal.ZERO);

                if (orderRemaining.compareTo(BigDecimal.ZERO) <= 0) {
                    throw new IllegalArgumentException("Hoá đơn '" + order.getOrderCode() + "' đã được thanh toán đủ, không thể đối trừ thêm");
                }
                if (item.getAmount().compareTo(orderRemaining) > 0) {
                    throw new IllegalArgumentException(String.format(
                            "Số tiền đối trừ (%s) vượt quá số nợ còn lại của hoá đơn '%s' (%s).",
                            formatMoney(item.getAmount()), order.getOrderCode(), formatMoney(orderRemaining)));
                }

                sumAllocated = sumAllocated.add(item.getAmount());

                BigDecimal newPaid = currentPaid.add(item.getAmount());
                order.setPaidAmount(newPaid);
                order.updatePaymentStatusFromPaidAmount();
                orderRepository.save(order);

                DebtPaymentAllocation alloc = DebtPaymentAllocation.builder()
                        .payment(payment)
                        .order(order)
                        .orderCode(order.getOrderCode())
                        .amount(item.getAmount())
                        .note(item.getNote())
                        .build();
                if (debtPaymentAllocationRepository != null) {
                    debtPaymentAllocationRepository.save(alloc);
                }
                createdAllocations.add(alloc);
            }

            if (sumAllocated.compareTo(request.getAmount()) != 0) {
                throw new IllegalArgumentException(String.format(
                        "Tổng số tiền đối trừ các hoá đơn (%s) phải bằng tổng số tiền thu ghi nhận (%s).",
                        formatMoney(sumAllocated), formatMoney(request.getAmount())));
            }
        } else {
            // Tiêu chí S7-02: Tự động đối trừ theo nguyên tắc hoá đơn cũ nhất trước (FIFO)
            BigDecimal remainingToAllocate = request.getAmount();

            for (Order order : recordedOrders) {
                if (remainingToAllocate.compareTo(BigDecimal.ZERO) <= 0) {
                    break;
                }
                BigDecimal orderTotal = order.getTotalAmount() != null ? order.getTotalAmount() : BigDecimal.ZERO;
                BigDecimal currentPaid = order.getPaidAmount() != null ? order.getPaidAmount() : BigDecimal.ZERO;
                BigDecimal orderRemaining = orderTotal.subtract(currentPaid).max(BigDecimal.ZERO);

                if (orderRemaining.compareTo(BigDecimal.ZERO) <= 0) {
                    continue; // Đơn này đã trả đủ
                }

                BigDecimal allocAmt = remainingToAllocate.min(orderRemaining);
                BigDecimal newPaid = currentPaid.add(allocAmt);
                order.setPaidAmount(newPaid);
                order.updatePaymentStatusFromPaidAmount();
                orderRepository.save(order);

                DebtPaymentAllocation alloc = DebtPaymentAllocation.builder()
                        .payment(payment)
                        .order(order)
                        .orderCode(order.getOrderCode())
                        .amount(allocAmt)
                        .note("Tự động đối trừ theo hoá đơn cũ nhất (FIFO)")
                        .build();
                if (debtPaymentAllocationRepository != null) {
                    debtPaymentAllocationRepository.save(alloc);
                }
                createdAllocations.add(alloc);

                remainingToAllocate = remainingToAllocate.subtract(allocAmt);
            }
        }

        payment.setAllocations(createdAllocations);

        auditLogService.logAction(
                "DEBT_PAYMENT",
                dealer.getId().toString(),
                "CREATE",
                null,
                formatMoney(request.getAmount()) + " (" + paymentCode + ")",
                request.getNote() != null ? request.getNote() : "Ghi nhận thu tiền công nợ đại lý",
                collector);

        // Tác vụ đánh giá lại chạy lại sau mỗi lần ghi nhận thu tiền
        FlaggedDealerResponse evaluation = evaluateDealerDebtInternal(dealer);

        DebtPaymentResponse response = toPaymentResponse(payment);
        response.setRemainingDebt(evaluation.getCurrentDebt());
        response.setDealerStillFlagged(Boolean.TRUE.equals(dealer.getDebtFlagged()));
        response.setDebtFlagReason(dealer.getDebtFlagReason());
        return response;
    }

    @Override
    @Transactional
    public DebtPaymentResponse adjustPayment(Long paymentId, DebtPaymentAdjustmentRequest request, String username) {
        if (paymentId == null) {
            throw new IllegalArgumentException("ID phiếu thu cần điều chỉnh không được để trống");
        }
        if (request == null || request.getReason() == null || request.getReason().trim().isBlank()) {
            throw new IllegalArgumentException("Lý do điều chỉnh không được để trống");
        }

        DebtPayment originalPayment = debtPaymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu thu với ID: " + paymentId));

        if ("ADJUSTMENT".equalsIgnoreCase(originalPayment.getPaymentType())) {
            throw new IllegalArgumentException("Không thể lập bút toán điều chỉnh cho một bút toán điều chỉnh khác");
        }

        Dealer dealer = originalPayment.getDealer();
        LocalDate adjustmentDate = request.getAdjustmentDate() != null ? request.getAdjustmentDate() : LocalDate.now();
        String adjCode = generateAdjustmentCode(adjustmentDate);

        String collector = request.getRecordedBy() != null && !request.getRecordedBy().isBlank()
                ? request.getRecordedBy().trim()
                : (username != null ? username : "system");

        String adjType = request.getAdjustmentType() != null ? request.getAdjustmentType().trim().toUpperCase() : "REVERSAL";

        BigDecimal adjustmentAmount;
        if ("PARTIAL_REVERSAL".equals(adjType)) {
            if (request.getAdjustmentAmount() == null || request.getAdjustmentAmount().compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("Số tiền điều chỉnh giảm phải lớn hơn 0");
            }
            if (request.getAdjustmentAmount().compareTo(originalPayment.getAmount()) > 0) {
                throw new IllegalArgumentException("Số tiền điều chỉnh giảm không được vượt quá số tiền của phiếu thu gốc");
            }
            adjustmentAmount = request.getAdjustmentAmount().negate();
        } else {
            // REVERSAL: Hoàn trả/huỷ toàn bộ khoản thu gốc
            adjustmentAmount = originalPayment.getAmount().negate();
        }

        DebtPayment adjPayment = DebtPayment.builder()
                .paymentCode(adjCode)
                .dealer(dealer)
                .amount(adjustmentAmount)
                .paymentDate(adjustmentDate)
                .paymentMethod(originalPayment.getPaymentMethod())
                .referenceCode("DC-" + originalPayment.getPaymentCode())
                .note(String.format("Bút toán điều chỉnh phiếu %s: %s", originalPayment.getPaymentCode(), request.getReason().trim()))
                .recordedBy(collector)
                .paymentType("ADJUSTMENT")
                .originalPayment(originalPayment)
                .adjustmentReason(request.getReason().trim())
                .build();

        debtPaymentRepository.save(adjPayment);

        List<DebtPaymentAllocation> originalAllocations = debtPaymentAllocationRepository != null
                ? debtPaymentAllocationRepository.findByPaymentIdOrderByCreatedAtAsc(originalPayment.getId())
                : originalPayment.getAllocations();

        List<DebtPaymentAllocation> adjAllocations = new ArrayList<>();

        if ("PARTIAL_REVERSAL".equals(adjType)) {
            // Giảm một phần: hoàn trả từ các dòng allocation mới nhất lùi về trước
            BigDecimal remainingToRevert = request.getAdjustmentAmount();
            for (int i = originalAllocations.size() - 1; i >= 0 && remainingToRevert.compareTo(BigDecimal.ZERO) > 0; i--) {
                DebtPaymentAllocation origAlloc = originalAllocations.get(i);
                BigDecimal revertAmt = remainingToRevert.min(origAlloc.getAmount());

                Order order = origAlloc.getOrder();
                BigDecimal currentPaid = order.getPaidAmount() != null ? order.getPaidAmount() : BigDecimal.ZERO;
                BigDecimal newPaid = currentPaid.subtract(revertAmt).max(BigDecimal.ZERO);
                order.setPaidAmount(newPaid);
                order.updatePaymentStatusFromPaidAmount();
                orderRepository.save(order);

                DebtPaymentAllocation adjAlloc = DebtPaymentAllocation.builder()
                        .payment(adjPayment)
                        .order(order)
                        .orderCode(order.getOrderCode())
                        .amount(revertAmt.negate())
                        .note("Điều chỉnh giảm phân bổ phiếu " + originalPayment.getPaymentCode())
                        .build();
                if (debtPaymentAllocationRepository != null) {
                    debtPaymentAllocationRepository.save(adjAlloc);
                }
                adjAllocations.add(adjAlloc);

                remainingToRevert = remainingToRevert.subtract(revertAmt);
            }
        } else {
            // REVERSAL: Hoàn trả toàn bộ phân bổ của phiếu gốc
            for (DebtPaymentAllocation origAlloc : originalAllocations) {
                Order order = origAlloc.getOrder();
                BigDecimal currentPaid = order.getPaidAmount() != null ? order.getPaidAmount() : BigDecimal.ZERO;
                BigDecimal newPaid = currentPaid.subtract(origAlloc.getAmount()).max(BigDecimal.ZERO);
                order.setPaidAmount(newPaid);
                order.updatePaymentStatusFromPaidAmount();
                orderRepository.save(order);

                DebtPaymentAllocation adjAlloc = DebtPaymentAllocation.builder()
                        .payment(adjPayment)
                        .order(order)
                        .orderCode(order.getOrderCode())
                        .amount(origAlloc.getAmount().negate())
                        .note("Hoàn trả phân bổ phiếu " + originalPayment.getPaymentCode())
                        .build();
                if (debtPaymentAllocationRepository != null) {
                    debtPaymentAllocationRepository.save(adjAlloc);
                }
                adjAllocations.add(adjAlloc);
            }
        }

        adjPayment.setAllocations(adjAllocations);

        auditLogService.logAction(
                "DEBT_PAYMENT_ADJUSTMENT",
                dealer.getId().toString(),
                "ADJUST",
                originalPayment.getPaymentCode(),
                adjCode + " (" + formatMoney(adjustmentAmount) + ")",
                request.getReason().trim(),
                collector);

        FlaggedDealerResponse evaluation = evaluateDealerDebtInternal(dealer);

        DebtPaymentResponse response = toPaymentResponse(adjPayment);
        response.setRemainingDebt(evaluation.getCurrentDebt());
        response.setDealerStillFlagged(Boolean.TRUE.equals(dealer.getDebtFlagged()));
        response.setDebtFlagReason(dealer.getDebtFlagReason());
        return response;
    }

    @Override
    @Transactional(readOnly = true)
    public DebtPaymentResponse getPaymentById(Long paymentId) {
        DebtPayment payment = debtPaymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu thu với ID: " + paymentId));
        return toPaymentResponse(payment);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DealerInvoiceResponse> getDealerInvoices(Long dealerId, String paymentStatusFilter) {
        Dealer dealer = dealerRepository.findById(dealerId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đại lý với ID: " + dealerId));

        List<Order> orders = orderRepository.findByDealerIdAndStatusInOrderByRecordedAtAsc(
                dealerId, OrderStatus.debtBearingStatuses());

        LocalDate today = LocalDate.now();
        int maxDebtDays = dealer.getMaxDebtDays() != null ? dealer.getMaxDebtDays() : 0;

        List<DealerInvoiceResponse> result = new ArrayList<>();
        for (Order order : orders) {
            BigDecimal totalAmt = order.getTotalAmount() != null ? order.getTotalAmount() : BigDecimal.ZERO;
            BigDecimal paidAmt = order.getPaidAmount() != null ? order.getPaidAmount() : BigDecimal.ZERO;
            BigDecimal remainingAmt = totalAmt.subtract(paidAmt).max(BigDecimal.ZERO);

            String pStatus = order.getPaymentStatus();
            if (pStatus == null || pStatus.isBlank()) {
                pStatus = (paidAmt.compareTo(BigDecimal.ZERO) == 0) ? "UNPAID"
                        : (paidAmt.compareTo(totalAmt) >= 0 ? "PAID" : "PARTIALLY_PAID");
            }

            if (paymentStatusFilter != null && !paymentStatusFilter.isBlank() && !"ALL".equalsIgnoreCase(paymentStatusFilter)) {
                if (!pStatus.equalsIgnoreCase(paymentStatusFilter.trim())) {
                    continue;
                }
            }

            LocalDateTime recordTime = order.getRecordedAt() != null ? order.getRecordedAt() : order.getCreatedAt();
            LocalDate invoiceDate = recordTime != null ? recordTime.toLocalDate() : today;

            boolean isOverdue = false;
            int overdueDays = 0;
            if (remainingAmt.compareTo(BigDecimal.ZERO) > 0 && maxDebtDays > 0) {
                long days = ChronoUnit.DAYS.between(invoiceDate, today);
                if (days > maxDebtDays) {
                    isOverdue = true;
                    overdueDays = (int) (days - maxDebtDays);
                }
            }

            List<DebtPaymentAllocationResponse> allocResponses = null;
            if (debtPaymentAllocationRepository != null) {
                allocResponses = debtPaymentAllocationRepository.findByOrderIdOrderByCreatedAtDesc(order.getId()).stream()
                        .map(this::toAllocationResponse)
                        .toList();
            }

            result.add(DealerInvoiceResponse.builder()
                    .orderId(order.getId())
                    .orderCode(order.getOrderCode())
                    .dealerId(dealer.getId())
                    .dealerCode(dealer.getCode())
                    .dealerName(dealer.getName())
                    .recordedAt(recordTime)
                    .invoiceDate(invoiceDate)
                    .totalAmount(totalAmt)
                    .paidAmount(paidAmt)
                    .remainingAmount(remainingAmt)
                    .paymentStatus(pStatus)
                    .paymentStatusDisplayName(formatPaymentStatusDisplayName(pStatus))
                    .orderStatus(order.getStatus())
                    .orderStatusDisplayName(OrderStatus.getDisplayName(order.getStatus()))
                    .overdueDays(overdueDays)
                    .isOverdue(isOverdue)
                    .allocations(allocResponses)
                    .build());
        }

        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public List<DealerInvoiceResponse> getDealerUnpaidInvoices(Long dealerId) {
        return getDealerInvoices(dealerId, null).stream()
                .filter(inv -> inv.getRemainingAmount().compareTo(BigDecimal.ZERO) > 0)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentAllocationPreviewResponse previewPaymentAllocation(Long dealerId, BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Số tiền thanh toán phải lớn hơn 0");
        }
        Dealer dealer = dealerRepository.findById(dealerId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đại lý với ID: " + dealerId));

        List<Order> orders = orderRepository.findByDealerIdAndStatusInOrderByRecordedAtAsc(
                dealerId, OrderStatus.debtBearingStatuses());

        BigDecimal totalDebt = BigDecimal.ZERO;
        for (Order o : orders) {
            BigDecimal total = o.getTotalAmount() != null ? o.getTotalAmount() : BigDecimal.ZERO;
            BigDecimal paid = o.getPaidAmount() != null ? o.getPaidAmount() : BigDecimal.ZERO;
            totalDebt = totalDebt.add(total.subtract(paid).max(BigDecimal.ZERO));
        }

        BigDecimal remainingToAllocate = amount;
        BigDecimal totalAllocated = BigDecimal.ZERO;
        List<DebtPaymentAllocationResponse> previewAllocations = new ArrayList<>();

        for (Order order : orders) {
            if (remainingToAllocate.compareTo(BigDecimal.ZERO) <= 0) break;

            BigDecimal total = order.getTotalAmount() != null ? order.getTotalAmount() : BigDecimal.ZERO;
            BigDecimal paid = order.getPaidAmount() != null ? order.getPaidAmount() : BigDecimal.ZERO;
            BigDecimal remaining = total.subtract(paid).max(BigDecimal.ZERO);

            if (remaining.compareTo(BigDecimal.ZERO) <= 0) continue;

            BigDecimal toAlloc = remainingToAllocate.min(remaining);
            BigDecimal newPaid = paid.add(toAlloc);
            BigDecimal newRemaining = total.subtract(newPaid).max(BigDecimal.ZERO);

            String newStatus = newRemaining.compareTo(BigDecimal.ZERO) == 0 ? "PAID" : "PARTIALLY_PAID";

            previewAllocations.add(DebtPaymentAllocationResponse.builder()
                    .orderId(order.getId())
                    .orderCode(order.getOrderCode())
                    .allocatedAmount(toAlloc)
                    .orderTotalAmount(total)
                    .orderPaidAmount(newPaid)
                    .orderRemainingAmount(newRemaining)
                    .orderPaymentStatus(newStatus)
                    .orderPaymentStatusDisplayName(formatPaymentStatusDisplayName(newStatus))
                    .note("Xem trước đối trừ FIFO")
                    .build());

            remainingToAllocate = remainingToAllocate.subtract(toAlloc);
            totalAllocated = totalAllocated.add(toAlloc);
        }

        return PaymentAllocationPreviewResponse.builder()
                .dealerId(dealer.getId())
                .dealerCode(dealer.getCode())
                .dealerName(dealer.getName())
                .currentTotalDebt(totalDebt)
                .paymentAmount(amount)
                .totalAllocated(totalAllocated)
                .remainingPayment(remainingToAllocate)
                .remainingDebtAfterPayment(totalDebt.subtract(totalAllocated).max(BigDecimal.ZERO))
                .allocations(previewAllocations)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public List<DebtPaymentAllocationResponse> getOrderSettlements(Long orderId) {
        if (debtPaymentAllocationRepository == null) return List.of();
        return debtPaymentAllocationRepository.findByOrderIdOrderByCreatedAtDesc(orderId).stream()
                .map(this::toAllocationResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<DebtPaymentResponse> getPaymentHistory(Long dealerId) {
        return debtPaymentRepository.findByDealerIdOrderByPaymentDateDescCreatedAtDesc(dealerId).stream()
                .map(this::toPaymentResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Page<DebtPaymentResponse> getPayments(Long dealerId, Pageable pageable) {
        Page<DebtPayment> page = (dealerId != null)
                ? debtPaymentRepository.findByDealerId(dealerId, pageable)
                : debtPaymentRepository.findAll(pageable);
        return page.map(this::toPaymentResponse);
    }

    // ──────────────────────── S7-04: CẤU HÌNH KIỂM SOÁT RỦI RO ────────────────────────

    @Override
    @Transactional(readOnly = true)
    public DebtRiskConfigResponse getDebtRiskConfig() {
        DebtRiskConfig config = getOrCreateConfig();
        return toConfigResponse(config);
    }

    @Override
    @Transactional
    public DebtRiskConfigResponse updateDebtRiskConfig(DebtRiskConfigRequest request, String username) {
        if (request == null || request.getDefaultAction() == null) {
            throw new IllegalArgumentException("Hành vi mặc định không được để trống");
        }
        DebtRiskConfig config = getOrCreateConfig();
        String oldConfigStr = String.format("default=%s, overdue=%s, limit=%s, autoFlag=%s",
                config.getDefaultAction(), config.getOverdueAction(), config.getLimitExceededAction(), config.getAutoFlagEnabled());

        config.setDefaultAction(request.getDefaultAction());
        if (request.getOverdueAction() != null) {
            config.setOverdueAction(request.getOverdueAction());
        }
        if (request.getLimitExceededAction() != null) {
            config.setLimitExceededAction(request.getLimitExceededAction());
        }
        if (request.getAutoFlagEnabled() != null) {
            config.setAutoFlagEnabled(request.getAutoFlagEnabled());
        }
        config.setUpdatedBy(username != null ? username : "system");
        debtRiskConfigRepository.save(config);

        String newConfigStr = String.format("default=%s, overdue=%s, limit=%s, autoFlag=%s",
                config.getDefaultAction(), config.getOverdueAction(), config.getLimitExceededAction(), config.getAutoFlagEnabled());

        auditLogService.logAction(
                "DEBT_RISK_CONFIG",
                config.getId().toString(),
                "UPDATE",
                oldConfigStr,
                newConfigStr,
                "Cập nhật cấu hình kiểm soát rủi ro công nợ",
                username != null ? username : "system");

        return toConfigResponse(config);
    }

    @Override
    @Transactional
    public DealerResponse updateDealerActionConfig(Long dealerId, DebtFlagAction action, String username) {
        Dealer dealer = dealerRepository.findById(dealerId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đại lý với ID: " + dealerId));
        if (action == null) {
            action = DebtFlagAction.DEFAULT;
        }
        String oldAction = dealer.getDebtActionConfig() != null ? dealer.getDebtActionConfig().name() : "DEFAULT";
        dealer.setDebtActionConfig(action);
        dealerRepository.save(dealer);

        auditLogService.logAction(
                "DEALER_DEBT_ACTION_CONFIG",
                dealer.getId().toString(),
                "UPDATE",
                oldAction,
                action.name(),
                "Cập nhật hành vi xử lý khi đại lý bị gắn cờ công nợ",
                username != null ? username : "system");

        DealerDebtResponse debtInfo = getDealerDebtInfo(dealer);
        return mapDealerToResponse(dealer, debtInfo);
    }

    @Override
    @Transactional(readOnly = true)
    public List<FlaggedDealerResponse> getFlaggedDealers() {
        return dealerRepository.findByDebtFlaggedTrueOrderByDebtFlaggedAtDesc().stream()
                .map(d -> {
                    DealerDebtResponse debtInfo = getDealerDebtInfo(d);
                    DebtFlagAction effective = resolveEffectiveAction(d);
                    BigDecimal excess = (d.getDebtLimit() != null && debtInfo.getCurrentDebt().compareTo(d.getDebtLimit()) > 0)
                            ? debtInfo.getCurrentDebt().subtract(d.getDebtLimit())
                            : BigDecimal.ZERO;
                    return FlaggedDealerResponse.builder()
                            .dealerId(d.getId())
                            .dealerCode(d.getCode())
                            .dealerName(d.getName())
                            .currentDebt(debtInfo.getCurrentDebt())
                            .debtLimit(d.getDebtLimit())
                            .excessDebtAmount(excess)
                            .maxDebtDays(d.getMaxDebtDays())
                            .maxOverdueDays(debtInfo.getMaxOverdueDays())
                            .overdueAmount(debtInfo.getOverdueAmount())
                            .debtFlagType(d.getDebtFlagType())
                            .debtFlagReason(d.getDebtFlagReason())
                            .debtFlaggedAt(d.getDebtFlaggedAt())
                            .lastDebtEvaluatedAt(d.getLastDebtEvaluatedAt())
                            .configuredAction(d.getDebtActionConfig())
                            .effectiveAction(effective)
                            .build();
                })
                .toList();
    }

    @Override
    public DebtFlagAction resolveEffectiveAction(Dealer dealer) {
        if (dealer == null) {
            return DebtFlagAction.BLOCK;
        }
        if (dealer.getDebtActionConfig() != null && dealer.getDebtActionConfig() != DebtFlagAction.DEFAULT) {
            return dealer.getDebtActionConfig();
        }
        DebtRiskConfig config = getOrCreateConfig();
        DebtFlagType type = dealer.getDebtFlagType();
        if (type == DebtFlagType.OVERDUE && config.getOverdueAction() != null) {
            return config.getOverdueAction();
        }
        if (type == DebtFlagType.DEBT_LIMIT_EXCEEDED && config.getLimitExceededAction() != null) {
            return config.getLimitExceededAction();
        }
        if (type == DebtFlagType.BOTH) {
            if (config.getOverdueAction() == DebtFlagAction.BLOCK || config.getLimitExceededAction() == DebtFlagAction.BLOCK) {
                return DebtFlagAction.BLOCK;
            }
            return config.getDefaultAction() != null ? config.getDefaultAction() : DebtFlagAction.REQUIRE_APPROVAL;
        }
        return config.getDefaultAction() != null ? config.getDefaultAction() : DebtFlagAction.BLOCK;
    }

    // ──────────────────────── HELPER METHODS ────────────────────────

    private DebtRiskConfig getOrCreateConfig() {
        DebtRiskConfig defaultConfig = DebtRiskConfig.builder()
                .defaultAction(DebtFlagAction.BLOCK)
                .overdueAction(DebtFlagAction.BLOCK)
                .limitExceededAction(DebtFlagAction.REQUIRE_APPROVAL)
                .autoFlagEnabled(true)
                .build();
        if (debtRiskConfigRepository == null) {
            return defaultConfig;
        }
        // This helper is also called by read-only debt/order/config queries. Never INSERT
        // defaults from a read: MySQL rejects the write, marks the enclosing transaction
        // rollback-only, and catching that failure only hides the original cause.
        // Persist defaults/overrides only through updateDebtRiskConfig's write transaction.
        var opt = debtRiskConfigRepository.findFirstByOrderByIdAsc();
        return opt != null && opt.isPresent() ? opt.get() : defaultConfig;
    }

    private DebtRiskConfigResponse toConfigResponse(DebtRiskConfig config) {
        return DebtRiskConfigResponse.builder()
                .id(config.getId())
                .defaultAction(config.getDefaultAction())
                .overdueAction(config.getOverdueAction())
                .limitExceededAction(config.getLimitExceededAction())
                .autoFlagEnabled(config.getAutoFlagEnabled())
                .updatedAt(config.getUpdatedAt())
                .updatedBy(config.getUpdatedBy())
                .build();
    }

    private DebtPaymentResponse toPaymentResponse(DebtPayment payment) {
        List<DebtPaymentAllocationResponse> allocResponses = null;
        if (payment.getAllocations() != null && !payment.getAllocations().isEmpty()) {
            allocResponses = payment.getAllocations().stream()
                    .map(this::toAllocationResponse)
                    .toList();
        } else if (debtPaymentAllocationRepository != null && payment.getId() != null) {
            allocResponses = debtPaymentAllocationRepository.findByPaymentIdOrderByCreatedAtAsc(payment.getId()).stream()
                    .map(this::toAllocationResponse)
                    .toList();
        }

        return DebtPaymentResponse.builder()
                .id(payment.getId())
                .paymentCode(payment.getPaymentCode())
                .dealerId(payment.getDealer() != null ? payment.getDealer().getId() : null)
                .dealerCode(payment.getDealer() != null ? payment.getDealer().getCode() : null)
                .dealerName(payment.getDealer() != null ? payment.getDealer().getName() : null)
                .amount(payment.getAmount())
                .paymentDate(payment.getPaymentDate())
                .paymentMethod(payment.getPaymentMethod())
                .referenceCode(payment.getReferenceCode())
                .note(payment.getNote())
                .recordedBy(payment.getRecordedBy())
                .paymentType(payment.getPaymentType() != null ? payment.getPaymentType() : "PAYMENT")
                .originalPaymentId(payment.getOriginalPayment() != null ? payment.getOriginalPayment().getId() : null)
                .originalPaymentCode(payment.getOriginalPayment() != null ? payment.getOriginalPayment().getPaymentCode() : null)
                .adjustmentReason(payment.getAdjustmentReason())
                .allocations(allocResponses)
                .createdAt(payment.getCreatedAt())
                .build();
    }

    private DebtPaymentAllocationResponse toAllocationResponse(DebtPaymentAllocation alloc) {
        Order order = alloc.getOrder();
        BigDecimal total = order != null && order.getTotalAmount() != null ? order.getTotalAmount() : BigDecimal.ZERO;
        BigDecimal paid = order != null && order.getPaidAmount() != null ? order.getPaidAmount() : BigDecimal.ZERO;
        BigDecimal rem = total.subtract(paid).max(BigDecimal.ZERO);
        String pStatus = order != null ? order.getPaymentStatus() : "UNPAID";

        return DebtPaymentAllocationResponse.builder()
                .id(alloc.getId())
                .orderId(order != null ? order.getId() : null)
                .orderCode(alloc.getOrderCode() != null ? alloc.getOrderCode() : (order != null ? order.getOrderCode() : null))
                .allocatedAmount(alloc.getAmount())
                .orderTotalAmount(total)
                .orderPaidAmount(paid)
                .orderRemainingAmount(rem)
                .orderPaymentStatus(pStatus)
                .orderPaymentStatusDisplayName(formatPaymentStatusDisplayName(pStatus))
                .allocatedAt(alloc.getCreatedAt())
                .note(alloc.getNote())
                .build();
    }

    private String formatPaymentStatusDisplayName(String status) {
        if (status == null || status.isBlank()) return "Chưa thanh toán";
        return switch (status.trim().toUpperCase()) {
            case "PAID" -> "Đã thanh toán";
            case "PARTIALLY_PAID" -> "Thanh toán một phần";
            default -> "Chưa thanh toán";
        };
    }

    private String generateAdjustmentCode(LocalDate date) {
        String prefix = "BTDC-" + date.format(DateTimeFormatter.ofPattern("yyyyMMdd")) + "-";
        return prefix + java.util.UUID.randomUUID().toString().replace("-", "").toUpperCase(java.util.Locale.ROOT);
    }

    private DealerResponse mapDealerToResponse(Dealer dealer, DealerDebtResponse debtInfo) {
        return DealerResponse.builder()
                .id(dealer.getId())
                .code(dealer.getCode())
                .name(dealer.getName())
                .taxCode(dealer.getTaxCode())
                .customerGroup(dealer.getCustomerGroup())
                .customerGroupDisplayName(dealer.getCustomerGroup() != null ? dealer.getCustomerGroup().getDisplayName() : null)
                .territoryId(dealer.getTerritory() != null ? dealer.getTerritory().getId() : null)
                .territoryCode(dealer.getTerritory() != null ? dealer.getTerritory().getCode() : null)
                .territoryName(dealer.getTerritory() != null ? dealer.getTerritory().getName() : null)
                .warehouseId(dealer.getWarehouse() != null ? dealer.getWarehouse().getId() : null)
                .warehouseCode(dealer.getWarehouse() != null ? dealer.getWarehouse().getCode() : null)
                .warehouseName(dealer.getWarehouse() != null ? dealer.getWarehouse().getName() : null)
                .defaultWarehouseId(dealer.getWarehouse() != null ? dealer.getWarehouse().getId() : null)
                .defaultWarehouseCode(dealer.getWarehouse() != null ? dealer.getWarehouse().getCode() : null)
                .defaultWarehouseName(dealer.getWarehouse() != null ? dealer.getWarehouse().getName() : null)
                .assignedUserId(dealer.getAssignedUser() != null ? dealer.getAssignedUser().getId() : null)
                .assignedUsername(dealer.getAssignedUser() != null ? dealer.getAssignedUser().getUsername() : null)
                .assignedUserFullName(dealer.getAssignedUser() != null ? dealer.getAssignedUser().getFullName() : null)
                .active(dealer.getActive())
                .handoverRequired(dealer.getHandoverRequired())
                .debtLimit(dealer.getDebtLimit())
                .maxDebtDays(dealer.getMaxDebtDays() != null ? dealer.getMaxDebtDays() : 0)
                .currentDebt(debtInfo != null ? debtInfo.getCurrentDebt() : null)
                .remainingDebtLimit(debtInfo != null ? debtInfo.getRemainingLimit() : null)
                .hasOverdueDebt(debtInfo != null ? debtInfo.getHasOverdueDebt() : false)
                .isBlockedDueToDebt(debtInfo != null ? debtInfo.getIsBlocked() : false)
                .maxOverdueDays(debtInfo != null ? debtInfo.getMaxOverdueDays() : 0)
                .debtFlagged(dealer.getDebtFlagged())
                .debtFlagType(dealer.getDebtFlagType())
                .debtFlagReason(dealer.getDebtFlagReason())
                .debtFlaggedAt(dealer.getDebtFlaggedAt())
                .lastDebtEvaluatedAt(dealer.getLastDebtEvaluatedAt())
                .debtActionConfig(dealer.getDebtActionConfig())
                .locked(dealer.getLocked())
                .lockReason(dealer.getLockReason())
                .lockedAt(dealer.getLockedAt())
                .lockedBy(dealer.getLockedBy())
                .phone(dealer.getPhone())
                .email(dealer.getEmail())
                .address(dealer.getAddress())
                .createdAt(dealer.getCreatedAt())
                .updatedAt(dealer.getUpdatedAt())
                .build();
    }

    private String generatePaymentCode(LocalDate date) {
        String prefix = "PT-" + date.format(DateTimeFormatter.ofPattern("yyyyMMdd")) + "-";
        return prefix + java.util.UUID.randomUUID().toString().replace("-", "").toUpperCase(java.util.Locale.ROOT);
    }

    private boolean isSalesOnlyUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        boolean hasSales = auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_SALES"));
        boolean hasManagerOrAdmin = auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")
                        || a.getAuthority().equals("ROLE_SALES_MANAGER")
                        || a.getAuthority().equals("ROLE_ACCOUNTANT")
                        || a.getAuthority().equals("ROLE_VIEWER"));
        return hasSales && !hasManagerOrAdmin;
    }

    private boolean isDealerUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        return auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_DEALER"));
    }

    private User getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null) {
            return null;
        }
        return userRepository.findByUsername(auth.getName()).orElse(null);
    }

    private String formatMoney(BigDecimal amount) {
        if (amount == null) return "0 đ";
        java.text.DecimalFormatSymbols symbols = new java.text.DecimalFormatSymbols(java.util.Locale.US);
        symbols.setGroupingSeparator(',');
        DecimalFormat df = new DecimalFormat("#,### đ", symbols);
        return df.format(amount);
    }
}
