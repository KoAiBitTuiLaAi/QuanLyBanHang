package com.quanlybanhang.dto.response;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EligibleDeliveryOrderResponse {
    private Long orderId;
    private String orderCode;
    private LocalDate actualDeliveryDate;
    private String deliveryNote;
    private BigDecimal orderedTotalAmount;
    private BigDecimal deliveredTotalAmount;
    private int itemCount;
    private List<EligibleDeliveryOrderItemResponse> items;
}

