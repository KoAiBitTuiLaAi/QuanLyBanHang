package com.quanlybanhang.dto.response;

import lombok.*;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvoiceAdjustmentItemResponse {
    private Long id;
    private Long productId;
    private String productCode;
    private String productName;
    private String unit;
    private BigDecimal adjustedQuantity;
    private BigDecimal adjustedPrice;
    private BigDecimal adjustmentAmount;
    private String reason;
}

