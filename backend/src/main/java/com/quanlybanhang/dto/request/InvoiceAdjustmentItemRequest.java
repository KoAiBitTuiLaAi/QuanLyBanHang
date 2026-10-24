package com.quanlybanhang.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.math.BigDecimal;

/**
 * DTO chi tiết dòng điều chỉnh sản phẩm (S7-01).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvoiceAdjustmentItemRequest {

    private Long productId;
    private String productCode;
    private String productName;
    private String unit;

    /**
     * Số lượng điều chỉnh (+ hoặc -).
     */
    private BigDecimal adjustedQuantity;

    /**
     * Đơn giá điều chỉnh.
     */
    private BigDecimal adjustedPrice;

    /**
     * Tiền điều chỉnh của dòng (+ hoặc -).
     */
    @NotNull(message = "Số tiền điều chỉnh của dòng không được để trống")
    private BigDecimal adjustmentAmount;

    private String reason;
}

