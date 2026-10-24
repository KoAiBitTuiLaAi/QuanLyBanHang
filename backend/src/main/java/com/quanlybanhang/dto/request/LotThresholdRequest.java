package com.quanlybanhang.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.*;

/**
 * Request khai báo ngưỡng cảnh báo hạn sử dụng cho SKU (S6-01).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LotThresholdRequest {

    @NotNull(message = "Ngưỡng cảnh báo hạn sử dụng không được để trống")
    @Min(value = 1, message = "Ngưỡng cảnh báo phải lớn hơn hoặc bằng 1 ngày")
    private Integer thresholdDays;
}

