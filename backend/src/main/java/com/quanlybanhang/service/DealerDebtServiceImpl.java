package com.quanlybanhang.service;

import com.quanlybanhang.dto.request.OrderDebtCheckRequest;
import com.quanlybanhang.dto.response.DealerDebtResponse;
import com.quanlybanhang.dto.response.OrderDebtCheckResponse;
import com.quanlybanhang.exception.DealerDebtOverdueException;
import com.quanlybanhang.exception.DealerLockedException;
import com.quanlybanhang.model.Dealer;
import com.quanlybanhang.model.Order;
import com.quanlybanhang.model.User;
import com.quanlybanhang.repository.DealerRepository;
import com.quanlybanhang.repository.OrderRepository;
import com.quanlybanhang.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@RequiredArgsConstructor
public class DealerDebtServiceImpl implements DealerDebtService {

    private final DealerRepository dealerRepository;
    private final OrderRepository orderRepository;
    private final UserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public DealerDebtResponse getDealerDebtInfo(Long dealerId) {
        Dealer dealer = dealerRepository.findById(dealerId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đại lý với ID: " + dealerId));

        // S4-10: Đại lý chỉ nhìn thấy công nợ của chính mình
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

        BigDecimal currentDebt = orderRepository.sumTotalAmountByDealerIdAndStatus(dealer.getId(), "RECORDED");
        if (currentDebt == null) {
            currentDebt = BigDecimal.ZERO;
        }

        BigDecimal remainingLimit = debtLimit.subtract(currentDebt);

        List<Order> recordedOrders = orderRepository.findByDealerIdAndStatusOrderByRecordedAtAsc(dealer.getId(), "RECORDED");
        LocalDate today = LocalDate.now();

        boolean hasOverdueDebt = false;
        int maxOverdueDays = 0;
        BigDecimal overdueAmount = BigDecimal.ZERO;
        String oldestOrderCode = null;
        LocalDate oldestOrderDate = null;

        if (maxDebtDays > 0) {
            for (Order order : recordedOrders) {
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
                        if (order.getTotalAmount() != null) {
                            overdueAmount = overdueAmount.add(order.getTotalAmount());
                        }
                    }
                }
            }
        }

        boolean isBlocked = false;
        String blockReason = null;
        if (Boolean.TRUE.equals(dealer.getLocked())) {
            isBlocked = true;
            blockReason = String.format("Đại lý '%s' (%s) đã bị khoá giao dịch. Lý do: %s",
                    dealer.getName(), dealer.getCode(), dealer.getLockReason() != null ? dealer.getLockReason() : "Không nêu rõ");
        } else if (hasOverdueDebt) {
            isBlocked = true;
            blockReason = String.format("Đại lý '%s' (%s) có khoản nợ quá hạn %d ngày (vượt quá giới hạn cho phép %d ngày), bị chặn tạo đơn hoàn toàn.",
                    dealer.getName(), dealer.getCode(), maxOverdueDays, maxDebtDays);
        }

        return DealerDebtResponse.builder()
                .dealerId(dealer.getId())
                .dealerCode(dealer.getCode())
                .dealerName(dealer.getName())
                .currentDebt(currentDebt)
                .debtLimit(debtLimit)
                .remainingLimit(remainingLimit)
                .maxDebtDays(maxDebtDays)
                .hasOverdueDebt(hasOverdueDebt)
                .overdueAmount(overdueAmount)
                .maxOverdueDays(maxOverdueDays)
                .oldestOverdueOrderCode(oldestOrderCode)
                .oldestOverdueOrderDate(oldestOrderDate)
                .isBlocked(isBlocked)
                .blockReason(blockReason)
                .build();
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
        String approvalReason = null;
        if (exceedsLimit) {
            approvalReason = String.format("Vượt hạn mức công nợ: Công nợ hiện tại (%s) + Đơn mới (%s) = %s vượt hạn mức (%s)",
                    formatMoney(debtInfo.getCurrentDebt()),
                    formatMoney(newOrderTotal),
                    formatMoney(projectedDebt),
                    formatMoney(debtInfo.getDebtLimit()));
        }

        String warningMessage;
        if (Boolean.TRUE.equals(debtInfo.getIsBlocked())) {
            warningMessage = debtInfo.getBlockReason();
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
                .requiresApproval(exceedsLimit)
                .approvalReason(approvalReason)
                .hasOverdueDebt(debtInfo.getHasOverdueDebt())
                .maxOverdueDays(debtInfo.getMaxOverdueDays())
                .overdueAmount(debtInfo.getOverdueAmount())
                .isBlocked(debtInfo.getIsBlocked())
                .blockReason(debtInfo.getBlockReason())
                .warningMessage(warningMessage)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public void validateDealerCanCreateOrder(Dealer dealer) {
        if (dealer == null) {
            return;
        }

        if (Boolean.TRUE.equals(dealer.getLocked())) {
            throw new DealerLockedException(dealer.getCode(), dealer.getName(), dealer.getLockReason());
        }

        DealerDebtResponse debtInfo = getDealerDebtInfo(dealer);
        if (Boolean.TRUE.equals(debtInfo.getHasOverdueDebt())) {
            throw new DealerDebtOverdueException(
                    dealer.getCode(),
                    dealer.getName(),
                    debtInfo.getMaxOverdueDays(),
                    debtInfo.getMaxDebtDays(),
                    debtInfo.getOverdueAmount());
        }
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
        DecimalFormat df = new DecimalFormat("#,### đ");
        return df.format(amount);
    }
}

