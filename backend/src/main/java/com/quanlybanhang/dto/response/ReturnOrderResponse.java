package com.quanlybanhang.dto.response;

import com.quanlybanhang.model.ReturnOrderStatus;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Response phieu tra hang (S8-02).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReturnOrderResponse {
    private Long id;
    private String returnCode;
    private Long invoiceId;
    private String invoiceCode;
    private String invoiceNumber;
    private Long dealerId;
    private String dealerCode;
    private String dealerName;
    private ReturnOrderStatus status;
    private String statusLabel;
    private String returnReason;
    private LocalDateTime returnDate;
    private BigDecimal totalReturnAmount;
    /** Chung tu dieu chinh da sinh sau khi xac nhan. */
    private InvoiceAdjustmentResponse invoiceAdjustment;
    private String confirmedBy;
    private LocalDateTime confirmedAt;
    private String createdBy;
    private LocalDateTime createdAt;
    private String cancelReason;
    private String note;
    private List<ReturnOrderItemResponse> items;
}