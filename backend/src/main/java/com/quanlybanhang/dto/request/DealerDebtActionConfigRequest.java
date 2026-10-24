package com.quanlybanhang.dto.request;

import com.quanlybanhang.model.DebtFlagAction;
import jakarta.validation.constraints.NotNull;
import lombok.*;

/**
 * Request cấu hình hành vi riêng cho đại lý khi bị gắn cờ (S7-04).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DealerDebtActionConfigRequest {

    @NotNull(message = "Cấu hình hành vi không được để trống")
    private DebtFlagAction action;
}

