package com.quanlybanhang.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

/**
 * Request huỷ đơn hàng (S4-06).
 * Bắt buộc nhập lý do huỷ đơn hàng.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderCancelRequest {

    @NotBlank(message = "Lý do huỷ đơn hàng không được để trống")
    private String reason;
}

