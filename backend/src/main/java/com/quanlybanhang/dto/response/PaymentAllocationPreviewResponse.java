package com.quanlybanhang.dto.response;

import lombok.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * DTO xem trước đối trừ tự động theo nguyên tắc hoá đơn cũ nhất trước (S7-02).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentAllocationPreviewResponse {

    private Long dealerId;
    private String dealerCode;
    private String dealerName;
    private BigDecimal currentTotalDebt;
    private BigDecimal paymentAmount;
    private BigDecimal totalAllocated;
    private BigDecimal remainingPayment;
    private BigDecimal remainingDebtAfterPayment;

    private List<DebtPaymentAllocationResponse> allocations;
}
