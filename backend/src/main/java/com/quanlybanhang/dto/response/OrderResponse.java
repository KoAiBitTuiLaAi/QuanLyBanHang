package com.quanlybanhang.dto.response;

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
public class OrderResponse {

    private Long id;
    private String orderCode;
    private String customerName;
    private com.quanlybanhang.model.CustomerGroup customerGroup;
    private Long dealerId;
    private Long deliveryPointId;
    private LocalDate expectedDeliveryDate;
    private Long priceListId;
    private String priceListCode;
    private String status;
    private Boolean requiresApproval;
    private String approvalReason;
    private String approvedBy;
    private LocalDateTime approvedAt;
    private BigDecimal subtotalAmount;
    private BigDecimal discountAmount;
    private BigDecimal totalAmount;
    private String note;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime recordedAt;
    private List<OrderItemResponse> items;

    /** Cảnh báo đơn của đại lý bị khoá giao dịch (S3-07). */
    private Boolean dealerLocked;
    private String dealerLockWarning;
    private String warning;

    /** Cảnh báo và thông tin công nợ vượt hạn mức (S4-02). */
    private Boolean exceedsDebtLimit;
    private BigDecimal currentDebt;
    private BigDecimal debtLimit;
    private BigDecimal remainingDebtLimit;

    /** Ngày thực tế giao hàng (S5-01). */
    private LocalDate actualDeliveryDate;

    /** Ghi chú giao hàng tổng thể (S5-01). */
    private String deliveryNote;

    /** Đánh dấu đơn hàng có dòng bị giao thiếu (S5-01). */
    private Boolean hasShortage;

    /** Tổng số dòng hàng trong đơn (S5-01). */
    private Integer totalLineItems;
}
