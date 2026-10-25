package com.quanlybanhang.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

import java.time.LocalDateTime;

/**
 * DTO cập nhật tài xế, phương tiện và thông tin tuyến giao hàng (S6-06).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateDeliveryTripRequest {

    @NotBlank(message = "Tên tài xế không được để trống")
    private String driverName;

    private String driverPhone;

    @NotBlank(message = "Biển số xe không được để trống")
    private String vehiclePlate;

    /**
     * Tên tuyến giao hàng (S6-06).
     */
    private String routeName;

    /**
     * ID Khu vực / địa bàn phân tuyến (S6-06).
     */
    private Long territoryId;

    private LocalDateTime expectedCompletionTime;

    private String note;
}
