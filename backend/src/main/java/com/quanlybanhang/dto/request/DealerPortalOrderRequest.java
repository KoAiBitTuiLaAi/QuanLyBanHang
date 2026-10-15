package com.quanlybanhang.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

/**
 * Request đặt hàng từ cổng đại lý (S4-10).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DealerPortalOrderRequest {

    private Long deliveryPointId;

    private LocalDate expectedDeliveryDate;

    private String note;

    @NotEmpty(message = "Đơn hàng phải có ít nhất 1 sản phẩm")
    @Valid
    private List<OrderItemRequest> items;
}

