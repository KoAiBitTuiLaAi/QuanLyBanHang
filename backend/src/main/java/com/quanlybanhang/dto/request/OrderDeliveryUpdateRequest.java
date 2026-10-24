package com.quanlybanhang.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
     * Trạng thái đơn hàng sau khi cập nhật giao hàng. Luồng giao hàng chỉ
     * được chuyển bước EXPORTED -> DELIVERED; các bước kho dùng API status.
     */
    private String status;

    /**
     * Ngày thực tế giao hàng (S5-01). Mặc định là ngày hiện tại nếu null.
     */
    private LocalDate actualDeliveryDate;

    /**
     * Ghi chú giao hàng tổng quan.
     */
    @Size(max = 500)
    private String deliveryNote;

    /**
     * Danh sách số lượng thực giao cho từng dòng hàng (S5-01).
     */
    @Valid
    @Size(max = 500)
    private List<@NotNull OrderItemDeliveryRequest> items;
}
