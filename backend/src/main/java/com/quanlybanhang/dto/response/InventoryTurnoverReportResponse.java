package com.quanlybanhang.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Báo cáo tổng hợp Tồn kho và Vòng quay hàng (S8-07):
 * - Giá trị tồn theo kho và theo nhóm hàng
 * - Số ngày tồn bình quân của từng SKU, chỉ ra hàng chậm luân chuyển đang chôn vốn
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InventoryTurnoverReportResponse {

    private LocalDate fromDate;
    private LocalDate toDate;
    private int periodDays;
    private int slowMovingThresholdDays;
    private LocalDateTime generatedAt;

    private Long warehouseId;
    private String warehouseCode;
    private String warehouseName;

    private Long categoryId;
    private String categoryCode;
    private String categoryName;

    private int totalWarehouses;
    private int totalCategories;
    private int totalSkus;
    private int inStockSkuCount;
    private int outOfStockSkuCount;
    private int fastMovingSkuCount;
    private int normalMovingSkuCount;
    private int slowMovingSkuCount;
    private int deadStockSkuCount;

    @Builder.Default
    private BigDecimal totalOpeningQuantity = BigDecimal.ZERO;
    @Builder.Default
    private BigDecimal totalInboundQuantityInPeriod = BigDecimal.ZERO;
    @Builder.Default
    private BigDecimal totalOutboundQuantityInPeriod = BigDecimal.ZERO;
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
    private BigDecimal totalReservedValue = BigDecimal.ZERO;
    @Builder.Default
    private BigDecimal totalAvailableValue = BigDecimal.ZERO;
    @Builder.Default
    private BigDecimal totalAverageInventoryValue = BigDecimal.ZERO;
    @Builder.Default
    private BigDecimal totalOutboundValueInPeriod = BigDecimal.ZERO;

    /** Tổng giá trị hàng chậm luân chuyển đang chôn vốn. */
    @Builder.Default
    private BigDecimal slowMovingInventoryValue = BigDecimal.ZERO;

    /** Tỷ lệ vốn bị chôn trong hàng chậm luân chuyển = slowMovingInventoryValue / totalInventoryValue * 100 (%). */
    @Builder.Default
    private BigDecimal tiedUpCapitalRatioPercent = BigDecimal.ZERO;

    /** Hệ số vòng quay hàng tồn kho toàn phạm vi trong kỳ. */
    @Builder.Default
    private BigDecimal overallTurnoverRate = BigDecimal.ZERO;

    /** Hệ số vòng quay hàng tồn kho quy đổi năm. */
    @Builder.Default
    private BigDecimal overallAnnualizedTurnoverRate = BigDecimal.ZERO;

    /** Số ngày tồn bình quân chung toàn phạm vi báo cáo. */
    @Builder.Default
    private BigDecimal overallAverageDaysInInventory = BigDecimal.ZERO;

    /** Giá trị tồn theo từng kho. */
    @Builder.Default
    private List<WarehouseInventoryValuationResponse> warehouseValuations = new ArrayList<>();

    /** Giá trị tồn theo từng nhóm hàng. */
    @Builder.Default
    private List<CategoryInventoryValuationResponse> categoryValuations = new ArrayList<>();

    /** Giá trị tồn chi tiết theo cặp Kho × Nhóm hàng. */
    @Builder.Default
    private List<WarehouseCategoryValuationResponse> warehouseCategoryValuations = new ArrayList<>();

    /** Danh sách chi tiết vòng quay và số ngày tồn bình quân của từng SKU. */
    @Builder.Default
    private List<SkuInventoryTurnoverResponse> skuItems = new ArrayList<>();

    /** Danh sách riêng các SKU chậm luân chuyển đang chôn vốn (sắp xếp theo giá trị chôn vốn giảm dần). */
    @Builder.Default
    private List<SkuInventoryTurnoverResponse> slowMovingItems = new ArrayList<>();

    public BigDecimal getTiedUpCapitalValue() {
        return slowMovingInventoryValue;
    }

    public List<WarehouseInventoryValuationResponse> getByWarehouse() {
        return warehouseValuations;
    }

    public List<CategoryInventoryValuationResponse> getByCategory() {
        return categoryValuations;
    }

    public List<WarehouseCategoryValuationResponse> getByWarehouseAndCategory() {
        return warehouseCategoryValuations;
    }

    public List<SkuInventoryTurnoverResponse> getItems() {
        return skuItems;
    }

    public List<SkuInventoryTurnoverResponse> getSlowMovingSkus() {
        return slowMovingItems;
    }
}

