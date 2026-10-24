package com.quanlybanhang.dto.response;

import lombok.*;
import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReturnOrderItemResponse {
    private Long id;
    private Long productId;
    private Long invoiceItemId;
    private String productCode;
    private String productName;
    private String unit;
    private BigDecimal returnQuantity;
    /** Don gia lay tu hoa don goc. */
    private BigDecimal invoiceUnitPrice;
    /** Chiet khau don vi lay tu hoa don goc. */
    private BigDecimal invoiceDiscountPerUnit;
    /** Tien tra tinh theo ty le thanh tien dong hoa don goc; lam tron luy ke de bao toan so le. */
    private BigDecimal returnAmount;
    private String note;
}