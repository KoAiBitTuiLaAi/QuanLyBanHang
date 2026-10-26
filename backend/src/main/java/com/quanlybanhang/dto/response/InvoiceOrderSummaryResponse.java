package com.quanlybanhang.dto.response;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvoiceOrderSummaryResponse {
    private Long orderId;
    private String orderCode;
    private LocalDate actualDeliveryDate;
    private BigDecimal deliveredTotalAmount;
    private int itemCount;
}

