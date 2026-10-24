package com.quanlybanhang.dto.response;

import lombok.*;

import java.math.BigDecimal;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvoicePreviewResponse {
    private Long dealerId;
    private String dealerCode;
    private String dealerName;
    private String dealerTaxCode;
    private String dealerAddress;
    private String dealerPhone;

    private int totalOrders;
    private int totalItems;

    private BigDecimal subtotalAmount;
    private BigDecimal discountAmount;
    private BigDecimal totalAmount;

    private List<InvoiceItemResponse> items;
    private List<InvoiceOrderSummaryResponse> orders;
}

