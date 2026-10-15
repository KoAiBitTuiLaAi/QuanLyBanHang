package com.quanlybanhang.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Entity Sổ sách Tồn kho (S2-07).
 *
 * Sổ sách tồn kho LUÔN LUÔN ghi nhận và lưu trữ số lượng tồn theo đơn vị cơ sở
 * (vd: ghi đúng số lon, dù thủ kho nhập/xuất theo thùng hay lốc).
 */
@Entity
@Table(name = "warehouse_inventories", uniqueConstraints = {
        @UniqueConstraint(name = "uk_warehouse_product", columnNames = {"warehouse_id", "product_id"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WarehouseInventory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "warehouse_id", nullable = false)
    private Warehouse warehouse;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    /**
     * Số lượng tồn kho thực tế tính theo đơn vị cơ sở (số lon).
     */
    @Column(name = "base_quantity", precision = 15, scale = 4, nullable = false)
    @Builder.Default
    private BigDecimal baseQuantity = BigDecimal.ZERO;

    /**
     * Số lượng hàng đang trên đường chuyển đến kho này theo đơn vị cơ sở (chưa xác nhận nhận).
     */
    @Column(name = "in_transit_quantity", precision = 15, scale = 4, nullable = false)
    @Builder.Default
    private BigDecimal inTransitQuantity = BigDecimal.ZERO;

    /**
     * Tên đơn vị cơ sở (vd: "lon").
     */
    @Column(name = "base_unit", nullable = false, length = 50)
    private String baseUnit;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
        if (this.baseQuantity == null) {
            this.baseQuantity = BigDecimal.ZERO;
        }
        if (this.inTransitQuantity == null) {
            this.inTransitQuantity = BigDecimal.ZERO;
        }
    }

    public void addBaseQuantity(BigDecimal amount) {
        if (amount != null) {
            if (this.baseQuantity == null) {
                this.baseQuantity = BigDecimal.ZERO;
            }
            this.baseQuantity = this.baseQuantity.add(amount);
        }
    }

    public void subtractBaseQuantity(BigDecimal amount) {
        if (amount != null) {
            if (this.baseQuantity == null) {
                this.baseQuantity = BigDecimal.ZERO;
            }
            this.baseQuantity = this.baseQuantity.subtract(amount);
        }
    }

    public void addInTransitQuantity(BigDecimal amount) {
        if (amount != null) {
            if (this.inTransitQuantity == null) {
                this.inTransitQuantity = BigDecimal.ZERO;
            }
            this.inTransitQuantity = this.inTransitQuantity.add(amount);
        }
    }

    public void subtractInTransitQuantity(BigDecimal amount) {
        if (amount != null) {
            if (this.inTransitQuantity == null) {
                this.inTransitQuantity = BigDecimal.ZERO;
            }
            this.inTransitQuantity = this.inTransitQuantity.subtract(amount);
            if (this.inTransitQuantity.compareTo(BigDecimal.ZERO) < 0) {
                this.inTransitQuantity = BigDecimal.ZERO;
            }
        }
    }
}
