package com.quanlybanhang.dto.response;

import com.quanlybanhang.model.InvoiceStatus;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvoiceResponse {
    private Long id;
    private String invoiceNumber;
    private String invoiceCode;
    private LocalDateTime invoiceDate;
    private LocalDate dueDate;
    private Boolean isPaid;
    private InvoiceStatus status;
    private String statusLabel;

    private Long dealerId;
    private String dealerCode;
    private String dealerName;
    private String dealerTaxCode;
    private String dealerAddress;
    private String dealerPhone;

    private BigDecimal subtotalAmount;
    private BigDecimal discountAmount;
    private BigDecimal totalAmount;
    private BigDecimal adjustedTotalAmount;

    /** Số tiền đã thanh toán phân bổ cho hóa đơn này (FIFO) (S7-07). */
    private BigDecimal paidAmount;

    /** Số tiền còn nợ của hóa đơn này (S7-07). */
    private BigDecimal remainingAmount;

    /** Trạng thái thanh toán của hóa đơn: UNPAID, PARTIALLY_PAID, PAID, OVERDUE, CANCELLED (S7-07). */
    private String paymentStatus;

    /** Nhãn hiển thị tiếng Việt của trạng thái thanh toán (S7-07). */
    private String paymentStatusLabel;

    /** Hóa đơn có đang quá hạn thanh toán hay không (S7-07). */
    private Boolean isOverdue;

    /** Số ngày quá hạn thanh toán (nếu isOverdue = true) (S7-07). */
    private Integer overdueDays;

    /** Số ngày còn lại đến hạn thanh toán (nếu chưa quá hạn và còn nợ) (S7-07). */
    private Integer daysUntilDue;

    private String note;
    private String createdBy;
    private LocalDateTime createdAt;

    private List<InvoiceItemResponse> items;
    private List<InvoiceOrderSummaryResponse> orders;
    private List<InvoiceAdjustmentResponse> adjustments;
}
