package com.quanlybanhang.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Entity Kho hàng (S1-05 & S5-03 & S8-01).
 * Mỗi kho có mã, tên, địa chỉ, người phụ trách, trạng thái (S5-03) và đánh dấu kho hàng lỗi (S8-01).
 */
@Entity
@Table(name = "warehouses", uniqueConstraints = {
        @UniqueConstraint(columnNames = "code")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Warehouse {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String code;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(length = 255)
    private String address;

    /** Người phụ trách kho (User) (S5-03). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "manager_user_id")
    private User manager;

    /** Tên người phụ trách (lưu trực tiếp hoặc fallback hiển thị) (S5-03). */
    @Column(name = "manager_name", length = 100)
    private String managerName;

    @Column(nullable = false)
    @Builder.Default
    private Boolean active = true;

    /** Đánh dấu kho hàng lỗi / hàng hỏng (S8-01). */
    @Column(name = "is_defective", nullable = false, columnDefinition = "boolean default false")
    @Builder.Default
    private Boolean defective = false;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public boolean isDefectiveWarehouse() {
        if (Boolean.TRUE.equals(this.defective)) {
            return true;
        }
        String c = this.code != null ? this.code.trim().toUpperCase(java.util.Locale.ROOT) : "";
        String n = this.name != null ? this.name.trim().toLowerCase(java.util.Locale.ROOT) : "";
        return c.contains("LOI") || c.contains("DEFECT") || c.contains("HONG") || c.contains("DAMAGE")
                || n.contains("hàng lỗi") || n.contains("kho lỗi") || n.contains("hàng hỏng") || n.contains("kho hỏng");
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (active == null) {
            active = true;
        }
        if (defective == null) {
            defective = false;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
        if (defective == null) {
            defective = false;
        }
    }
}
