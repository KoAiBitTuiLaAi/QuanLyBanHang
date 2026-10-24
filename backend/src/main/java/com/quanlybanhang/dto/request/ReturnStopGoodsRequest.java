package com.quanlybanhang.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

/**
 * DTO xác nhận nhập kho trả hàng cho điểm giao thất bại (S6-07).
 * Vì hàng chưa giao được KHÔNG tự về kho, việc nhập lại kho phải thông qua hành động tường minh này.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReturnStopGoodsRequest {

    @NotBlank(message = "Lý do hoàn trả kho không được để trống")
    private String returnReason;

    private String note;
}

