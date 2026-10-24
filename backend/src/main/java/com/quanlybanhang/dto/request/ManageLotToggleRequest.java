package com.quanlybanhang.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.*;

/**
 * Request bật/tắt quản lý lô theo từng SKU (S6-01).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ManageLotToggleRequest {

    @NotNull(message = "Trạng thái quản lý lô không được để trống")
    private Boolean manageLot;

    /**
     * Ngưỡng cảnh báo sắp hết hạn (số ngày). Tùy chọn.
     */
    @jakarta.validation.constraints.Min(value = 1, message = "Ngưỡng cảnh báo phải ít nhất 1 ngày")
    private Integer expiryWarningDays;
}

