package com.quanlybanhang.dto.request;

import com.quanlybanhang.model.DeliveryStopStatus;
import jakarta.validation.constraints.NotNull;
import lombok.*;

/**
 * DTO cập nhật trạng thái điểm giao hàng (S6-07).
 *
 * Quy tắc cốt lõi:
 * - Khi status = FAILED (chưa giao được): Bắt buộc nhập failedReason.
 * - Hàng chưa giao được vẫn thuộc trách nhiệm của chuyến, KHÔNG tự động hoàn về kho.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateDeliveryStopStatusRequest {

    @NotNull(message = "Trạng thái giao hàng không được để trống")
    private DeliveryStopStatus status;

    /**
     * Lý do chưa giao được (bắt buộc khi status = FAILED).
     */
    private String failedReason;

    private String note;
}

