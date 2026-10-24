package com.quanlybanhang.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Tổng hợp giá trị tồn kho và chỉ số vòng quay theo từng Kho (S8-07).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WarehouseInventoryValuationResponse {

    private Long warehouseId;
    private String warehouseCode;
    private String warehouseName;
    private Boolean defective;
    private Boolean active;

    private int skuCount;
    private int inStockSkuCount;
    private int slowMovingSkuCount;
    private int deadStockSkuCount;

    @Builder.Default
    private BigDecimal openingQuantity = BigDecimal.ZERO;
    @Builder.Default
    private BigDecimal inboundQuantityInPeriod = BigDecimal.ZERO;
    @Builder.Default
    private BigDecimal outboundQuantityInPeriod = BigDecimal.ZERO;
    @Builder.Default
    private BigDecimal totalPhysicalQuantity = BigDecimal.ZERO;
    @Builder.Default
    private BigDecimal totalReservedQuantity = BigDecimal.ZERO;
    @Builder.Default
    private BigDecimal totalAvailableQuantity = BigDecimal.ZERO;
    @Builder.Default
    private BigDecimal totalInTransitQuantity = BigDecimal.ZERO;

    @Builder.Default
    private BigDecimal totalInventoryValue = BigDecimal.ZERO;
    @Builder.Default
    private BigDecimal reservedInventoryValue = BigDecimal.ZERO;
    @Builder.Default
    private BigDecimal availableInventoryValue = BigDecimal.ZERO;
    @Builder.Default
    private BigDecimal averageInventoryValue = BigDecimal.ZERO;
    @Builder.Default
    private BigDecimal outboundValueInPeriod = BigDecimal.ZERO;

    /** Giá trị tồn kho của các mặt hàng chậm luân chuyển đang chôn vốn tại kho này. */
    @Builder.Default
    private BigDecimal slowMovingInventoryValue = BigDecimal.ZERO;

    /** Tỷ lệ vốn chôn tại kho = slowMovingInventoryValue / totalInventoryValue * 100 (%). */
    @Builder.Default
    private BigDecimal slowMovingValueRatioPercent = BigDecimal.ZERO;

    /** Tỷ trọng giá trị tồn của kho này trên tổng giá trị tồn toàn phạm vi báo cáo (%). */
    @Builder.Default
    private BigDecimal valueSharePercent = BigDecimal.ZERO;

    /** Hệ số vòng quay tồn kho trong kỳ tại kho này. */
    @Builder.Default
    private BigDecimal turnoverRate = BigDecimal.ZERO;

    /** Hệ số vòng quay tồn kho quy đổi năm. */
    @Builder.Default
    private BigDecimal annualizedTurnoverRate = BigDecimal.ZERO;

    /** Số ngày tồn bình quân chung tại kho này. */
    @Builder.Default
    private BigDecimal averageDaysInInventory = BigDecimal.ZERO;

    public BigDecimal getInventoryValue() {
        return totalInventoryValue;
    }

    public BigDecimal getTotalValue() {
        return totalInventoryValue;
    }

    public BigDecimal getTiedUpCapitalValue() {
        return slowMovingInventoryValue;
    }
}

