package com.quanlybanhang.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Mục danh mục sản phẩm và bảng giá dành cho đại lý (S4-10).
 * Chỉ bao gồm các sản phẩm có trong bảng giá hiệu lực của nhóm khách hàng đại lý.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DealerPortalCatalogItemResponse {
    private Long id;
    private String sku;
    private String name;
    private Long categoryId;
    private String categoryName;
    private String baseUnit;
    private BigDecimal basePrice;
    private BigDecimal floorPrice;
    private String imageUrl;

    @Builder.Default
    private List<PackagingUnitInfo> units = new ArrayList<>();

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PackagingUnitInfo {
        private String unitName;
        private BigDecimal factor;
        private BigDecimal price;
    }
}

