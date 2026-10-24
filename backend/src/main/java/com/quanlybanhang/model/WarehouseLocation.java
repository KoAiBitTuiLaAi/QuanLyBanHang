package com.quanlybanhang.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Entity Vị trí lưu trong kho (S5-03).
 * Khai báo được vị trí lưu trong kho ở mức kệ hoặc khu (ZONE hoặc SHELF).
 */
@Entity
@Table(name = "warehouse_locations", uniqueConstraints = {
        @UniqueConstraint(name = "uk_warehouse_location_code", columnNames = {"warehouse_id", "code"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WarehouseLocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "warehouse_id", nullable = false)
    private Warehouse warehouse;

    /** Mã vị trí (duy nhất trong cùng một kho), vd: "KHU-A", "KE-01", "A-01" */
    @Column(nullable = false, length = 50)
    private String code;

    /** Tên vị trí, vd: "Khu mát", "Kệ 01 hàng bia" */
    @Column(nullable = false, length = 120)
    private String name;

    /** Loại vị trí: ZONE (Khu) hoặc SHELF (Kệ) */
    @Enumerated(EnumType.STRING)
    @Column(name = "location_type", length = 20, nullable = false)
    @Builder.Default
    private LocationType locationType = LocationType.SHELF;

    /** Vị trí cha (vd: Kệ nằm trong Khu vực cha) */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private WarehouseLocation parent;

    @Column(length = 500)
    private String description;

    /** Optional walking sequence within its parent zone; natural codes are the fallback. */
    private Integer pickingSequence;

    @Column(nullable = false)
    @Builder.Default
    private Boolean active = true;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (active == null) {
            active = true;
        }
        if (locationType == null) {
            locationType = LocationType.SHELF;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
