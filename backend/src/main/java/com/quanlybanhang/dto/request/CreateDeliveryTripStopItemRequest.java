package com.quanlybanhang.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.math.BigDecimal;

/**
 * DTO chi tiết mặt hàng tại điểm giao (S6-07).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateDeliveryTripStopItemRequest {

    private Long productId;

    private String productCode;

    private String productName;

    private String unit;

    @NotNull(message = "Số lượng không được để trống")
    @DecimalMin(value = "0.0001", message = "Số lượng phải lớn hơn 0")
    private BigDecimal quantity;

    @DecimalMin(value = "0.0", message = "Đơn giá không được âm")
    private BigDecimal unitPrice;
}

