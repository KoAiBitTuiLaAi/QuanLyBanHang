package com.quanlybanhang.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Chi tiết tồn kho, giá trị tồn, hệ số vòng quay và số ngày tồn bình quân của từng SKU (S8-07).
 * Dùng để chỉ ra mặt hàng chậm luân chuyển đang chôn vốn.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkuInventoryTurnoverResponse {

    private Long productId;
    private String productCode;
    private String productName;
    private String baseUnit;
    private String packingSpecification;
    private Boolean active;

    private Long categoryId;
    private String categoryCode;
    private String categoryName;

    private Long warehouseId;
    private String warehouseCode;
    private String warehouseName;
    private int warehouseCount;

    @Builder.Default
    private BigDecimal unitCost = BigDecimal.ZERO;
    @Builder.Default
    private BigDecimal sellingPrice = BigDecimal.ZERO;

    @Builder.Default
    private BigDecimal openingQuantity = BigDecimal.ZERO;
    @Builder.Default
    private BigDecimal inboundQuantity = BigDecimal.ZERO;
    @Builder.Default
    private BigDecimal outboundQuantity = BigDecimal.ZERO;
    @Builder.Default
    private BigDecimal closingQuantity = BigDecimal.ZERO;
    @Builder.Default
    private BigDecimal reservedQuantity = BigDecimal.ZERO;
    @Builder.Default
    private BigDecimal availableQuantity = BigDecimal.ZERO;
    @Builder.Default
    private BigDecimal inTransitQuantity = BigDecimal.ZERO;
    @Builder.Default
    private BigDecimal averageInventoryQuantity = BigDecimal.ZERO;

    @Builder.Default
    private BigDecimal openingValue = BigDecimal.ZERO;
    @Builder.Default
    private BigDecimal inboundValue = BigDecimal.ZERO;
    @Builder.Default
    private BigDecimal outboundValue = BigDecimal.ZERO;
    @Builder.Default
    private BigDecimal inventoryValue = BigDecimal.ZERO;
    @Builder.Default
    private BigDecimal reservedValue = BigDecimal.ZERO;
    @Builder.Default
    private BigDecimal availableValue = BigDecimal.ZERO;
    @Builder.Default
    private BigDecimal averageInventoryValue = BigDecimal.ZERO;

    /** Lượng xuất bình quân mỗi ngày trong kỳ (baseUnit/ngày). */
    @Builder.Default
    private BigDecimal dailyOutboundRate = BigDecimal.ZERO;

    /** Giá vốn xuất bình quân mỗi ngày trong kỳ (VND/ngày). */
    @Builder.Default
    private BigDecimal dailyOutboundValue = BigDecimal.ZERO;

    /** Hệ số vòng quay hàng tồn kho trong kỳ = outboundQuantity / averageInventoryQuantity. */
    @Builder.Default
    private BigDecimal turnoverRate = BigDecimal.ZERO;

    /** Hệ số vòng quay quy đổi năm = turnoverRate * 365 / periodDays. */
    @Builder.Default
    private BigDecimal annualizedTurnoverRate = BigDecimal.ZERO;

    /** Số ngày tồn bình quân của SKU (Days Inventory Outstanding / Average Days in Inventory). */
    @Builder.Default
    private BigDecimal averageDaysInInventory = BigDecimal.ZERO;

    /** Số ngày đủ bán ước tính theo lượng tồn cuối kỳ hiện tại = closingQuantity / dailyOutboundRate. */
    @Builder.Default
    private BigDecimal estimatedDaysOfSupply = BigDecimal.ZERO;

    /** Số ngày kể từ lần xuất kho gần nhất tính đến cuối kỳ báo cáo. */
    private Long daysSinceLastOutbound;
    private LocalDateTime lastOutboundAt;
    private LocalDateTime lastInboundAt;

    /** Đánh dấu mặt hàng chậm luân chuyển đang chôn vốn. */
    private boolean slowMoving;

    /** Phân loại tốc độ luân chuyển: FAST_MOVING, NORMAL_MOVING, SLOW_MOVING, DEAD_STOCK, OUT_OF_STOCK. */
    private String turnoverStatus;
    private String turnoverStatusLabel;

    /** Giá trị vốn đang bị chôn ở SKU này (= inventoryValue nếu slowMoving == true, ngược lại 0). */
    @Builder.Default
    private BigDecimal tiedUpCapitalValue = BigDecimal.ZERO;

    /** Tỷ trọng giá trị tồn của SKU trong tổng giá trị tồn toàn phạm vi (%). */
    @Builder.Default
    private BigDecimal valueSharePercent = BigDecimal.ZERO;

    /** Lý do / thuyết minh vì sao SKU bị đánh dấu chậm luân chuyển hoặc chôn vốn. */
    private String slowMovingReason;

    /** Bóc tách tồn kho của SKU theo từng kho (nếu xem tổng hợp nhiều kho). */
    @Builder.Default
    private List<WarehouseSkuBreakdown> warehouseBreakdown = new ArrayList<>();

    public String getSku() {
        return productCode;
    }

    public BigDecimal getCostPrice() {
        return unitCost;
    }

    public BigDecimal getPhysicalQuantity() {
        return closingQuantity;
    }

    public BigDecimal getTotalValue() {
        return inventoryValue;
    }

    public BigDecimal getAverageHoldingDays() {
        return averageDaysInInventory;
    }

    public BigDecimal getDaysInventoryOutstanding() {
        return averageDaysInInventory;
    }

    public BigDecimal getDaysOnHand() {
        return averageDaysInInventory;
    }

    public boolean isCapitalTiedUp() {
        return slowMoving && inventoryValue != null && inventoryValue.signum() > 0;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WarehouseSkuBreakdown {
        private Long warehouseId;
        private String warehouseCode;
        private String warehouseName;
        @Builder.Default
        private BigDecimal physicalQuantity = BigDecimal.ZERO;
        @Builder.Default
        private BigDecimal reservedQuantity = BigDecimal.ZERO;
        @Builder.Default
        private BigDecimal availableQuantity = BigDecimal.ZERO;
        @Builder.Default
        private BigDecimal outboundQuantity = BigDecimal.ZERO;
        @Builder.Default
        private BigDecimal inventoryValue = BigDecimal.ZERO;
    }
}

