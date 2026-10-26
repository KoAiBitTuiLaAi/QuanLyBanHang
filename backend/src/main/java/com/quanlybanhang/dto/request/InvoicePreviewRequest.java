package com.quanlybanhang.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.util.List;

/**
 * Yêu cầu xem trước hóa đơn trước khi phát hành chính thức (S7-01).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvoicePreviewRequest {

    @NotNull(message = "ID đại lý không được để trống")
    private Long dealerId;

    @NotEmpty(message = "Danh sách phiếu giao hàng không được để trống")
    private List<Long> orderIds;
}

