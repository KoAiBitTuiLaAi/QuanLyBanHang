package com.quanlybanhang.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * DTO khai báo điểm giao trong chuyến (S6-07).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateDeliveryTripStopRequest {

    private Long orderId;

    private String orderCode;

    private Long dealerId;

    private String dealerName;

    private Long deliveryPointId;

    private String receiverName;

    private String receiverPhone;

    @NotBlank(message = "Địa chỉ giao hàng không được để trống")
    private String deliveryAddress;

    @NotNull(message = "Giá trị tiền hàng không được để trống")
    @DecimalMin(value = "0.0", message = "Giá trị tiền hàng không được âm")
    private BigDecimal deliveryAmount;

    private Integer stopOrder;

    private String note;

    @Valid
    private List<CreateDeliveryTripStopItemRequest> items;
}

