package com.quanlybanhang.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * Chi tiết từng dòng sản phẩm trong phiếu kiểm kê kho (S5-08).
 * Chốt số tồn sổ tại thời điểm tạo, ghi nhận số thực đếm và tính chênh lệch.
 */
@Entity
@Table(name = "warehouse_stock_count_items", uniqueConstraints = {
        @UniqueConstraint(name = "uk_stock_count_product", columnNames = {"stock_count_id", "product_id"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WarehouseStockCountItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stock_count_id", nullable = false)
    private WarehouseStockCount stockCount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    /**
     * Đơn vị cơ sở của sản phẩm (lon, chai, cái...).
     */
    @Column(name = "base_unit", nullable = false, length = 50)
    private String baseUnit;

    /**
     * Số lượng tồn sổ sách tại thời điểm tạo phiếu (theo đơn vị cơ sở).
     */
    @Column(name = "book_quantity", precision = 15, scale = 4, nullable = false)
    @Builder.Default
    private BigDecimal bookQuantity = BigDecimal.ZERO;

    // Internal snapshot metadata. Old drafts without this marker must be recreated.
    @Column(name = "snapshot_captured", nullable = false, columnDefinition = "boolean default false")
    @Builder.Default
    private boolean snapshotCaptured = false;

    @Column(name = "snapshot_inventory_id")
    private Long snapshotInventoryId;

    @Column(name = "snapshot_inventory_version")
    private Long snapshotInventoryVersion;

    /**
     * Số lượng đếm thực tế (theo đơn vị cơ sở).
     */
    @Column(name = "actual_quantity", precision = 15, scale = 4)
    private BigDecimal actualQuantity;

    /**
     * Chênh lệch = actualQuantity - bookQuantity.
     * Dương: thừa; Âm: thiếu; 0: khớp.
     */
    @Column(name = "difference_quantity", precision = 15, scale = 4)
    private BigDecimal differenceQuantity;

    @Column(name = "note", length = 500)
    private String note;

    public void calculateDifference() {
        if (this.actualQuantity != null && this.bookQuantity != null) {
            this.differenceQuantity = this.actualQuantity.subtract(this.bookQuantity);
        } else {
            this.differenceQuantity = null;
        }
    }
}
