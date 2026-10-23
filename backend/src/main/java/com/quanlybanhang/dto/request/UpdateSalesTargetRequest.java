package com.quanlybanhang.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.math.BigDecimal;

/**
 * Request cập nhật chỉ tiêu doanh số (S8-04).
 * Khi chỉ tiêu đã qua kỳ hoặc đã bị khoá, bắt buộc phải truyền reason (lý do chỉnh sửa).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateSalesTargetRequest {

    @NotNull(message = "Mục tiêu doanh số không được để trống")
    @DecimalMin(value = "0.01", message = "Mục tiêu doanh số phải lớn hơn 0")
    private BigDecimal targetAmount;

    /**
     * Ghi chú bổ sung.
     */
    private String note;

    /**
     * Lý do chỉnh sửa — bắt buộc khi chỉ tiêu đã qua kỳ hoặc đang ở trạng thái khoá.
     */
    private String reason;
}

