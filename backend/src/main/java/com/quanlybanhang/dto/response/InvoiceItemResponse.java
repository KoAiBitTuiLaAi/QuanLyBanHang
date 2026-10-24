package com.quanlybanhang.dto.response;

import lombok.*;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvoiceItemResponse {
    private Long id;
    private Long orderId;
    private String orderCode;
    private Long orderItemId;
    private Long productId;
    private String productCode;
    private String productName;
    private String unit;
    private BigDecimal orderedQuantity;
    private BigDecimal deliveredQuantity;
    private BigDecimal invoicedQuantity;
    private BigDecimal unitPrice;
    private BigDecimal subtotalAmount;
    private BigDecimal discountPercent;
    private BigDecimal discountAmount;
    private BigDecimal totalAmount;
    private String note;
}

