package com.quanlybanhang.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.math.BigDecimal;

/**
 * Request tạo / sửa sản phẩm (S2-05).
 * Hỗ trợ khai báo:
 * - Mã SKU (code / sku) - duy nhất
 * - Tên sản phẩm
 * - Nhóm hàng (categoryId)
 * - Đơn vị tính cơ sở (unit)
 * - Quy cách đóng gói (packingSpecification)
 * - Giá vốn (costPrice - chỉ Quản lý kinh doanh xem và sửa được)
 * - Giá bán (price)
 * - Ảnh (imageUrl)
 * - Trạng thái (active: đang kinh doanh / ngừng kinh doanh)
 */
@Getter
@Setter
@com.fasterxml.jackson.annotation.JsonIgnoreProperties({"revenue", "hasTransactions"})
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductRequest {

    /**
     * Mã SKU / Mã sản phẩm (duy nhất).
     */
    @Size(max = 50, message = "Mã SKU tối đa 50 ký tự")
    private String code;

    /**
     * Alias cho SKU.
     */
    @Size(max = 50, message = "Mã SKU tối đa 50 ký tự")
    private String sku;

    @NotBlank(message = "Tên sản phẩm không được để trống")
    @Size(max = 255, message = "Tên sản phẩm tối đa 255 ký tự")
    private String name;

    @Size(max = 1000, message = "Mô tả tối đa 1000 ký tự")
    private String description;

    @NotNull(message = "Nhóm hàng không được để trống")
    private Long categoryId;

    @PositiveOrZero(message = "Giá bán không được âm")
    @Builder.Default
    private BigDecimal price = BigDecimal.ZERO;

    /**
     * Giá vốn - chỉ Quản lý kinh doanh được phép xem và sửa.
     */
    @PositiveOrZero(message = "Giá vốn không được âm")
    @Builder.Default
    private BigDecimal costPrice = BigDecimal.ZERO;

    /**
     * Đơn vị tính cơ sở: cái, chai, hộp, lon, kg, gói, vỉ...
     */
    @Builder.Default
    private String unit = "cái";

    /**
     * Quy cách đóng gói (S2-05): Thùng 24 lon, Hộp 10 vỉ, Lốc 6 chai...
     */
    @Size(max = 255, message = "Quy cách đóng gói tối đa 255 ký tự")
    private String packingSpecification;

    /**
     * Đường dẫn ảnh sản phẩm (S2-05).
     */
    @Size(max = 500, message = "Đường dẫn ảnh tối đa 500 ký tự")
    private String imageUrl;

    /**
     * Trạng thái kinh doanh: true = Đang kinh doanh, false = Ngừng kinh doanh.
     */
    @Builder.Default
    private Boolean active = true;

    /**
     * Cờ quản lý theo số lô và hạn sử dụng (S5-04).
     */
    @Builder.Default
    private Boolean manageLot = false;

    /**
     * Danh sách đơn vị quy đổi khai báo kèm sản phẩm (S2-07).
     * Ví dụ: lon (cơ sở), lốc (hệ số 6), thùng (hệ số 24).
     */
    private java.util.List<UnitConversionRequest> unitConversions;

    /**
     * Lấy mã SKU hợp lệ (ưu tiên sku, nếu trống thì lấy code).
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public String getEffectiveSku() {
        if (sku != null && !sku.isBlank()) {
            return sku.trim();
        }
        if (code != null && !code.isBlank()) {
            return code.trim();
        }
        return "";
    }
}

