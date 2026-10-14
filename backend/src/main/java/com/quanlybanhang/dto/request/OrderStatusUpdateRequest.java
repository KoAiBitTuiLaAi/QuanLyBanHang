package com.quanlybanhang.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

/**
 * Request cập nhật trạng thái vòng đời đơn hàng (S4-06).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderStatusUpdateRequest {

    @NotBlank(message = "Trạng thái mới không được để trống")
    private String status;

    private String note;
}

