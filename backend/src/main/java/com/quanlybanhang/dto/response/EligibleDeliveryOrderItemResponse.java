package com.quanlybanhang.dto.response;

import lombok.*;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EligibleDeliveryOrderItemResponse {
    private Long orderItemId;
    private Long productId;
    private String productCode;
    private String productName;
    private String unit;
    private BigDecimal orderedQuantity;
    private BigDecimal deliveredQuantity;
    private BigDecimal unitPrice;
    private BigDecimal discountAmount;
    private BigDecimal deliveredTotalAmount;
    private String deliveryNote;
}

