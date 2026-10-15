package com.quanlybanhang.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Entity Phiếu chuyển kho nội bộ (S5-07).
 * Điều chuyển hàng giữa hai kho nội bộ trong hệ thống.
 */
@Entity
@Table(name = "warehouse_transfers", uniqueConstraints = {
        @UniqueConstraint(name = "uk_transfer_code", columnNames = "transfer_code")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WarehouseTransfer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transfer_code", nullable = false, length = 50, unique = true)
    private String transferCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "from_warehouse_id", nullable = false)
    private Warehouse fromWarehouse;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "to_warehouse_id", nullable = false)
    private Warehouse toWarehouse;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    @Builder.Default
    private WarehouseTransferStatus status = WarehouseTransferStatus.IN_TRANSIT;

    @Column(name = "note", length = 500)
    private String note;

    @Column(name = "receive_note", length = 500)
    private String receiveNote;

    @Column(name = "cancel_reason", length = 500)
    private String cancelReason;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "dispatched_at")
    private LocalDateTime dispatchedAt;

    @Column(name = "received_by", length = 100)
    private String receivedBy;

    @Column(name = "received_at")
    private LocalDateTime receivedAt;

    @Column(name = "cancelled_by", length = 100)
    private String cancelledBy;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @OneToMany(mappedBy = "transfer", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<WarehouseTransferItem> items = new ArrayList<>();

    public void addItem(WarehouseTransferItem item) {
        if (this.items == null) {
            this.items = new ArrayList<>();
        }
        this.items.add(item);
        item.setTransfer(this);
    }

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
        if (this.status == null) {
            this.status = WarehouseTransferStatus.IN_TRANSIT;
        }
        if (this.dispatchedAt == null) {
            this.dispatchedAt = LocalDateTime.now();
        }
    }
}

