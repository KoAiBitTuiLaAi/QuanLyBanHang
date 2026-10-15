package com.quanlybanhang.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Entity Theo dõi Tồn kho theo Lô (S5-04).
 * Lưu trữ số lượng tồn kho thực tế theo từng số lô và hạn sử dụng của từng sản phẩm tại từng kho.
 */
@Entity
@Table(name = "warehouse_lot_inventories", uniqueConstraints = {
        @UniqueConstraint(name = "uk_wh_prod_lot", columnNames = {"warehouse_id", "product_id", "lot_number"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WarehouseLotInventory {

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
     * Số lô hàng.
     */
    @Column(name = "lot_number", nullable = false, length = 50)
    private String lotNumber;

    /**
     * Hạn sử dụng.
     */
    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    /**
     * Ngày sản xuất.
     */
    @Column(name = "manufacturing_date")
    private LocalDate manufacturingDate;

    /**
     * Số lượng tồn theo đơn vị cơ sở (vd: lon).
     */
    @Column(name = "base_quantity", precision = 15, scale = 4, nullable = false)
    @Builder.Default
    private BigDecimal baseQuantity = BigDecimal.ZERO;

    /**
     * Tên đơn vị cơ sở.
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
}

