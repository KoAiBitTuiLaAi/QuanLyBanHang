package com.quanlybanhang.dto.response;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Response DTO cho Product (S2-05).
 * Chứa đầy đủ thông tin:
 * - Mã SKU (code/sku)
 * - Tên sản phẩm, nhóm hàng
 * - Đơn vị tính cơ sở (unit), quy cách đóng gói (packingSpecification)
 * - Giá vốn (costPrice - chỉ có dữ liệu với Quản lý kinh doanh)
 * - Giá bán (price)
 * - Ảnh sản phẩm (imageUrl)
 * - Trạng thái kinh doanh (active, status)
 * - Đã phát sinh giao dịch (hasTransactions, canDelete)
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductResponse {

    private Long id;
    private String code;
    private String sku;
    private String name;
    private String description;

    private Long categoryId;
    private String categoryCode;
    private String categoryName;
    private Integer categoryLevel;

    private BigDecimal price;

    /**
     * Giá vốn - CHỈ HIỂN THỊ với Quản lý kinh doanh (ROLE_SALES_MANAGER).
     * Với các vai trò khác, trường này trả về null.
     */
    private BigDecimal costPrice;

    private BigDecimal revenue;
    private String unit;
    private String packingSpecification;
    private String imageUrl;

    private Boolean active;
    private String status;
    private Boolean manageLot;
    private Integer expiryWarningDays;

    /**
     * Sản phẩm đã phát sinh giao dịch thì không được xoá (S2-05).
     */
    private Boolean hasTransactions;
    private Boolean canDelete;

    private java.util.List<UnitConversionResponse> unitConversions;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
