package com.quanlybanhang.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Thông tin công nợ và hạn mức của đại lý (S4-02, S7-07).
 * Phục vụ hiển thị trên màn hình tạo đơn, kiểm soát rủi ro bán hàng và Cổng Đại lý tự tra cứu công nợ.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DealerDebtResponse {

    private Long dealerId;
    private String dealerCode;
    private String dealerName;

    /** Công nợ hiện tại (tổng tiền các đơn hàng ghi sổ / hóa đơn chưa thanh toán). */
    private BigDecimal currentDebt;

    /** Hạn mức công nợ tối đa được cấp (S3-05). */
    private BigDecimal debtLimit;

    /** Phần hạn mức còn lại (debtLimit - currentDebt). */
    private BigDecimal remainingLimit;

    /** Hạn mức còn được lấy hàng thực tế (0 nếu bị khoá/chặn hoặc vượt hạn mức, ngược lại bằng remainingLimit) (S7-07). */
    private BigDecimal availableOrderLimit;

    /** Tổng giá trị hóa đơn đã phát hành hiệu lực của đại lý (S7-07). */
    private BigDecimal totalInvoicedAmount;

    /** Công nợ phát sinh từ đơn hàng ghi sổ chưa xuất hóa đơn (S7-07). */
    private BigDecimal uninvoicedOrderDebt;

    /** Tổng số hóa đơn của đại lý (S7-07). */
    private Integer totalInvoiceCount;

    /** Số lượng hóa đơn chưa thanh toán hết (S7-07). */
    private Integer unpaidInvoiceCount;

    /** Số lượng hóa đơn quá hạn thanh toán (S7-07). */
    private Integer overdueInvoiceCount;

    /** Số lượng hóa đơn đã thanh toán đủ (S7-07). */
    private Integer paidInvoiceCount;

    /** Danh sách hóa đơn của đại lý kèm số tiền, hạn thanh toán và trạng thái (S7-07). */
    private List<InvoiceResponse> invoices;

    /** Lịch sử các đợt thanh toán công nợ của đại lý (S7-07). */
    private List<DebtPaymentResponse> payments;

    /** Số ngày nợ tối đa cho phép (S3-05). */
    private Integer maxDebtDays;

    /** Có khoản nợ nào quá số ngày cho phép hay không. */
    private Boolean hasOverdueDebt;

    /** Tổng số tiền nợ bị quá hạn. */
    private BigDecimal overdueAmount;

    /** Số ngày quá hạn lớn nhất (nếu có đơn nợ / hóa đơn quá hạn). */
    private Integer maxOverdueDays;

    /** Mã đơn hàng / hóa đơn nợ quá hạn lâu nhất. */
    private String oldestOverdueOrderCode;

    /** Ngày phát sinh đơn hàng / hóa đơn nợ quá hạn lâu nhất. */
    private LocalDate oldestOverdueOrderDate;

    /** Trạng thái bị chặn tạo đơn hoàn toàn (do nợ quá hạn hoặc bị khoá giao dịch). */
    private Boolean isBlocked;

    /** Lý do chặn tạo đơn (nếu isBlocked = true). */
    private String blockReason;

    private BigDecimal totalPaid;
    private Boolean isDebtFlagged;
    private com.quanlybanhang.model.DebtFlagType debtFlagType;
    private String debtFlagReason;
    private java.time.LocalDateTime debtFlaggedAt;
    private java.time.LocalDateTime lastDebtEvaluatedAt;
    private com.quanlybanhang.model.DebtFlagAction effectiveAction;
    public java.util.Map<String, String> getExactAmounts() {
        return com.quanlybanhang.util.ExactAmounts.of("currentDebt", currentDebt, "debtLimit", debtLimit,
                "remainingLimit", remainingLimit, "availableOrderLimit", availableOrderLimit,
                "totalInvoicedAmount", totalInvoicedAmount, "uninvoicedOrderDebt", uninvoicedOrderDebt,
                "overdueAmount", overdueAmount, "totalPaid", totalPaid);
    }
}
