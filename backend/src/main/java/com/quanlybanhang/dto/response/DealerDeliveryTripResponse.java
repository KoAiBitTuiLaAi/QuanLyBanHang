package com.quanlybanhang.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Thông tin chuyến giao hàng và phương tiện vận chuyển (S6-09).
 * Giúp đại lý biết rõ tài xế, biển số xe và giờ xe xuất phát/dự kiến tới để chủ động bố trí người nhận.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DealerDeliveryTripResponse {

    private Long tripId;
    private String tripCode;
    private String driverName;
    private String driverPhone;
    private String vehiclePlate;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime departureTime;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime expectedCompletionTime;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime actualCompletionTime;

    private String tripStatus;
    private String stopStatus;
    private Integer stopOrder;
    private String receiverName;
    private String receiverPhone;
    private String deliveryAddress;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime deliveredAt;

    private String note;
    private String failedReason;
}

