package com.quanlybanhang.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockCountItemUpdateRequest {

    @NotNull(message = "ID sản phẩm không được để trống")
    private Long productId;

    @DecimalMin(value = "0.0", inclusive = true, message = "Số lượng đếm thực tế phải lớn hơn hoặc bằng 0")
    @Digits(integer = 11, fraction = 4)
    private BigDecimal actualQuantity;

    @Size(max = 500)
    private String note;
}
