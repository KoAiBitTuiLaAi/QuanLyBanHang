package com.quanlybanhang.dto.response;

import com.quanlybanhang.model.DeliveryStopStatus;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeliveryTripStopResponse {
    private Long id;
    private Long orderId;
    private String orderCode;
    private Long dealerId;
    private String dealerName;
    private Long deliveryPointId;
    private String receiverName;
    private String receiverPhone;
    private String deliveryAddress;
    private Integer stopOrder;
    private DeliveryStopStatus status;
    private String statusDisplayName;
    private BigDecimal deliveryAmount;
    private LocalDateTime deliveredAt;
    private String failedReason;
    private LocalDateTime failedAt;
    private Boolean returnedToWarehouse;
    private LocalDateTime returnedAt;
    private String note;
    private List<DeliveryTripStopItemResponse> items;
}

