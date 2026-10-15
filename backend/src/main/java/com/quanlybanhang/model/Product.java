package com.quanlybanhang.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Entity Product - Quản lý sản phẩm.
 * Thuộc về một nhóm hàng (Category). Có thể chuyển đổi giữa các nhóm hàng.
 */
@Entity
@Table(name = "products", uniqueConstraints = {
        @UniqueConstraint(columnNames = "code")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50, unique = true)
    private String code;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(length = 1000)
    private String description;

    /**
     * Nhóm hàng của sản phẩm.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    /**
     * Giá bán của sản phẩm.
     */
    @Column(precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal price = BigDecimal.ZERO;

    /**
     * Giá vốn của sản phẩm (bảo mật theo S1-05).
     */
    @Column(name = "cost_price", precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal costPrice = BigDecimal.ZERO;

    /**
     * Cột cũ giữ để tương thích database. Doanh số trả qua API được tổng hợp từ order_items.
     */
    @Column(precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal revenue = BigDecimal.ZERO;

    /**
     * Đơn vị tính cơ sở: chiếc, cái, hộp, kg, lon...
     */
    @Column(length = 50)
    @Builder.Default
    private String unit = "cái";

    /**
     * Quy cách đóng gói (S2-05): Thùng 24 lon, Hộp 10 vỉ, Lốc 6 chai...
     */
    @Column(name = "packing_specification", length = 255)
    private String packingSpecification;

    /**
     * Đường dẫn / URL ảnh sản phẩm (S2-05).
     */
    @Column(name = "image_url", length = 500)
    private String imageUrl;

    /**
     * Cột cũ giữ để tương thích database. Quyền xoá dựa vào dòng đơn hàng/phiếu kho thực tế.
     */
    @Column(name = "has_transactions", nullable = false)
    @Builder.Default
    private Boolean hasTransactions = false;

    /**
     * Cờ quản lý theo số lô và hạn sử dụng (S5-04).
     * Nếu true, khi nhập kho bắt buộc phải ghi nhận số lô và hạn sử dụng.
     */
    @Column(name = "manage_lot", nullable = false)
    @Builder.Default
    private Boolean manageLot = false;

    /**
     * Danh sách các đơn vị quy đổi của SKU (S2-07).
     * Ví dụ: SKU có đơn vị cơ sở "lon", có thể thêm "lốc" (hệ số 6), "thùng" (hệ số 24).
     */
    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private java.util.List<UnitConversion> unitConversions = new java.util.ArrayList<>();

    public void addUnitConversion(UnitConversion conversion) {
        if (this.unitConversions == null) {
            this.unitConversions = new java.util.ArrayList<>();
        }
        this.unitConversions.add(conversion);
        conversion.setProduct(this);
    }

    public void removeUnitConversion(UnitConversion conversion) {
        if (this.unitConversions != null) {
            this.unitConversions.remove(conversion);
            conversion.setProduct(null);
        }
    }

    @Column(nullable = false)
    @Builder.Default
    private Boolean active = true;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public String getSku() {
        return this.code;
    }

    public void setSku(String sku) {
        this.code = sku;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        if (this.active == null) {
            this.active = true;
        }
        if (this.price == null) {
            this.price = BigDecimal.ZERO;
        }
        if (this.costPrice == null) {
            this.costPrice = BigDecimal.ZERO;
        }
        if (this.revenue == null) {
            this.revenue = BigDecimal.ZERO;
        }
        if (this.hasTransactions == null) {
            this.hasTransactions = false;
        }
        if (this.manageLot == null) {
            this.manageLot = false;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
