package com.quanlybanhang.dto.request;

import jakarta.validation.Valid;
import lombok.*;

import java.time.LocalDate;
import java.util.List;

/**
 * DTO cập nhật tiến trình giao hàng và số lượng thực giao của đơn hàng (S5-01).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderDeliveryUpdateRequest {

    /**
     * Trạng thái đơn hàng sau khi cập nhật giao hàng:
     * ví dụ "PROCESSING", "SHIPPED", "DELIVERED", "COMPLETED".
     */
    private String status;

    /**
     * Ngày thực tế giao hàng (S5-01). Mặc định là ngày hiện tại nếu null.
     */
    private LocalDate actualDeliveryDate;

    /**
     * Ghi chú giao hàng tổng quan.
     */
    private String deliveryNote;

    /**
     * Danh sách số lượng thực giao cho từng dòng hàng (S5-01).
     */
    @Valid
    private List<OrderItemDeliveryRequest> items;
}

