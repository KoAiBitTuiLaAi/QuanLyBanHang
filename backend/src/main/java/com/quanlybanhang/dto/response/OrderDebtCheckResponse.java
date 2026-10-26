package com.quanlybanhang.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Kết quả kiểm tra công nợ và hạn mức khi tạo đơn (S4-02).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderDebtCheckResponse {

    private Long dealerId;
    private String dealerCode;
    private String dealerName;

    /** Công nợ hiện tại. */
    private BigDecimal currentDebt;

    /** Hạn mức công nợ được cấp. */
    private BigDecimal debtLimit;

    /** Hạn mức còn lại trước khi cộng đơn mới. */
    private BigDecimal remainingLimit;

    /** Số tiền đơn hàng mới. */
    private BigDecimal newOrderTotal;

    /** Dự phóng công nợ sau khi đặt đơn (currentDebt + newOrderTotal). */
    private BigDecimal projectedDebt;

    /** Tổng đơn cộng công nợ hiện tại có vượt hạn mức hay không. */
    private Boolean exceedsLimit;

    /** Đơn hàng có bị đánh dấu cần Quản lý duyệt hay không. */
    private Boolean requiresApproval;

    /** Lý do cần duyệt (nếu exceedsLimit = true). */
    private String approvalReason;

    /** Có khoản nợ quá hạn ngày cho phép hay không. */
    private Boolean hasOverdueDebt;

    /** Số ngày quá hạn lớn nhất. */
    private Integer maxOverdueDays;

    /** Số tiền nợ bị quá hạn. */
    private BigDecimal overdueAmount;

    /** Bị chặn tạo đơn hoàn toàn hay không (do quá hạn ngày hoặc bị khoá đại lý). */
    private Boolean isBlocked;

    /** Lý do chặn tạo đơn hoàn toàn. */
    private String blockReason;
    private Boolean isDebtFlagged;
    private com.quanlybanhang.model.DebtFlagType debtFlagType;
    private String debtFlagReason;
    private com.quanlybanhang.model.DebtFlagAction effectiveAction;

    /** Thông điệp cảnh báo hiển thị trên giao diện tạo đơn. */
    private String warningMessage;
}

