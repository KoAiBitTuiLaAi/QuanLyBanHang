package com.quanlybanhang.dto.response;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Đơn hàng đủ điều kiện thêm vào chuyến giao hàng (S6-07).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeliveryEligibleOrderResponse {
    private Long orderId;
    private String orderCode;
    private Long dealerId;
    private String dealerName;
    private Long deliveryPointId;
    private String receiverName;
    private String receiverPhone;
    private String deliveryAddress;
    private LocalDate expectedDeliveryDate;
    private BigDecimal totalAmount;
    private String status;
}

