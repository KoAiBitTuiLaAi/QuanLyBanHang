package com.quanlybanhang.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Tổng hợp giá trị tồn kho và vòng quay theo cặp Kho × Nhóm hàng (S8-07).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WarehouseCategoryValuationResponse {

    private Long warehouseId;
    private String warehouseCode;
    private String warehouseName;

    private Long categoryId;
    private String categoryCode;
    private String categoryName;

    private int skuCount;
    private int inStockSkuCount;
    private int slowMovingSkuCount;

    @Builder.Default
    private BigDecimal totalPhysicalQuantity = BigDecimal.ZERO;
    @Builder.Default
    private BigDecimal totalReservedQuantity = BigDecimal.ZERO;
    @Builder.Default
    private BigDecimal totalAvailableQuantity = BigDecimal.ZERO;
    @Builder.Default
    private BigDecimal outboundQuantityInPeriod = BigDecimal.ZERO;

    @Builder.Default
    private BigDecimal totalInventoryValue = BigDecimal.ZERO;
    @Builder.Default
    private BigDecimal availableInventoryValue = BigDecimal.ZERO;
    @Builder.Default
    private BigDecimal outboundValueInPeriod = BigDecimal.ZERO;
    @Builder.Default
    private BigDecimal slowMovingInventoryValue = BigDecimal.ZERO;

    @Builder.Default
    private BigDecimal valueSharePercent = BigDecimal.ZERO;
    @Builder.Default
    private BigDecimal turnoverRate = BigDecimal.ZERO;
    @Builder.Default
    private BigDecimal averageDaysInInventory = BigDecimal.ZERO;

    public BigDecimal getInventoryValue() {
        return totalInventoryValue;
    }

    public BigDecimal getTotalValue() {
        return totalInventoryValue;
    }
}

