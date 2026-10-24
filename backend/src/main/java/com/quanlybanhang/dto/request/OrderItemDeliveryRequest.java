package com.quanlybanhang.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.math.BigDecimal;

/**
 * DTO cập nhật số lượng thực giao cho từng dòng đơn hàng (S5-01).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderItemDeliveryRequest {

    @NotNull(message = "ID dòng hàng không được để trống")
    private Long orderItemId;

    /**
     * Số lượng thực tế giao theo đơn vị đặt hàng (S5-01).
     * Có thể bằng số lượng đặt (giao đủ) hoặc nhỏ hơn (giao thiếu).
     */
    @NotNull(message = "Số lượng thực giao không được để trống")
    @DecimalMin(value = "0.0", message = "Số lượng thực giao không được âm")
    @Digits(integer = 11, fraction = 4)
    private BigDecimal deliveredQuantity;

    /**
     * Ghi chú cho dòng này (ví dụ: lý do giao thiếu).
     */
    @Size(max = 500)
    private String note;
}
