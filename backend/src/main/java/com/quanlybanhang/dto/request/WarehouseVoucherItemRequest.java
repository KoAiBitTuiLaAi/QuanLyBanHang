package com.quanlybanhang.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WarehouseVoucherItemRequest {

    private Long productId;

    private String productCode;

    /**
     * Đơn vị tính khi nhập/xuất (vd: "thùng", "lốc", "lon").
     * Nếu bỏ trống, hệ thống sẽ mặc định dùng đơn vị cơ sở của sản phẩm.
     */
    private String unit;

    @NotNull(message = "Số lượng không được để trống")
    @DecimalMin(value = "0.0001", message = "Số lượng phải lớn hơn 0")
    private BigDecimal quantity;

    private BigDecimal price;

    /**
     * Số lô hàng (S5-04). Bắt buộc nếu sản phẩm có manageLot = true.
     */
    private String lotNumber;

    /**
     * Hạn sử dụng (S5-04). Bắt buộc nếu sản phẩm có manageLot = true.
     */
    private java.time.LocalDate expiryDate;

    /**
     * Ngày sản xuất (S5-04). Tùy chọn.
     */
    private java.time.LocalDate manufacturingDate;

    private String note;
}
