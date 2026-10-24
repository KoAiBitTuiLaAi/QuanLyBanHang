package com.quanlybanhang.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * DTO tạo mới Chuyến giao hàng (S6-07).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateDeliveryTripRequest {

    @NotNull(message = "Kho xuất phát không được để trống")
    private Long warehouseId;

    /**
     * Mã chuyến giao (tùy chọn; nếu để trống hệ thống tự sinh CG-yyyyMMdd-XXX).
     */
    private String tripCode;

    @NotBlank(message = "Tên tài xế không được để trống")
    private String driverName;

    private String driverPhone;

    @NotBlank(message = "Biển số xe không được để trống")
    private String vehiclePlate;

    /**
     * Thời điểm xuất phát (mặc định là hiện tại nếu để trống).
     */
    private LocalDateTime departureTime;

    /**
     * Thời gian dự kiến hoàn thành chuyến giao (bắt buộc).
     * Phục vụ cảnh báo chuyến quá thời gian dự kiến (S6-07).
     */
    @NotNull(message = "Thời gian dự kiến hoàn thành không được để trống")
    private LocalDateTime expectedCompletionTime;

    private String note;

    /**
     * Danh sách ID đơn hàng đã xuất kho gán vào chuyến này.
     * Hệ thống sẽ tự động tạo điểm giao từ các đơn hàng tương ứng.
     */
    private List<Long> orderIds;

    /**
     * Hoặc danh sách các điểm giao tùy chỉnh.
     */
    @Valid
    private List<CreateDeliveryTripStopRequest> stops;
}

