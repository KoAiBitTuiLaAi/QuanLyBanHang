package com.quanlybanhang.dto.response;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Thông tin chi tiết một dòng đối trừ hoá đơn (S7-02).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DebtPaymentAllocationResponse {
    private Long id;
    private Long orderId;
    private String orderCode;
    private BigDecimal allocatedAmount;
    private BigDecimal orderTotalAmount;
    private BigDecimal orderPaidAmount;
    private BigDecimal orderRemainingAmount;
    private String orderPaymentStatus;
    private String orderPaymentStatusDisplayName;
    private LocalDateTime allocatedAt;
    private String note;
}
