package com.quanlybanhang.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Entity Phiếu trả hàng từ đại lý và nhập lại kho (S8-01).
 *
 * Tiêu chí nghiệp vụ:
 * 1. Chọn hoá đơn gốc, chọn dòng hàng và số lượng trả, bắt buộc chọn lý do trả.
 * 2. Số lượng trả không vượt số lượng đã giao trừ phần đã trả trước đó.
 * 3. Hàng trả về nhập lại đúng lô nếu SKU có quản lý lô; hàng hỏng nhập vào kho hàng lỗi.
 * 4. Phiếu trả phải được Quản lý kho xác nhận nhận hàng mới cộng tồn.
 */
@Entity
@Table(name = "dealer_returns", uniqueConstraints = {
        @UniqueConstraint(columnNames = "return_code")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DealerReturn {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    @Column(nullable = false, columnDefinition = "bigint default 0")
    @Builder.Default
    private Long version = 0L;

    @Column(name = "return_code", nullable = false, unique = true, length = 50)
    private String returnCode;

    /** Hóa đơn bán hàng gốc được chọn để trả hàng. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invoice_id", nullable = false)
    private Invoice invoice;

    @Column(name = "invoice_code", nullable = false, length = 50)
    private String invoiceCode;

    /** Đại lý trả hàng. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dealer_id", nullable = false)
    private Dealer dealer;

    @Column(name = "dealer_code", nullable = false, length = 50)
    private String dealerCode;

    @Column(name = "dealer_name", nullable = false, length = 255)
    private String dealerName;

    /** Kho thương mại nhận hàng tốt trả về. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "warehouse_id", nullable = false)
    private Warehouse warehouse;

    /** Kho hàng lỗi nhận hàng hỏng trả về (nếu có dòng hàng hỏng). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "defective_warehouse_id")
    private Warehouse defectiveWarehouse;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private DealerReturnStatus status = DealerReturnStatus.PENDING;

    /** Lý do trả hàng chung trên đầu phiếu (bổ trợ lý do từng dòng). */
    @Column(name = "return_reason", length = 500)
    private String returnReason;

    @Column(name = "total_return_quantity", precision = 15, scale = 4, nullable = false)
    @Builder.Default
    private BigDecimal totalReturnQuantity = BigDecimal.ZERO;

    @Column(name = "total_amount", precision = 15, scale = 2, nullable = false)
    @Builder.Default
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Column(length = 500)
    private String note;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "confirmed_by", length = 100)
    private String confirmedBy;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @Column(name = "confirm_note", length = 500)
    private String confirmNote;

    @Column(name = "cancelled_by", length = 100)
    private String cancelledBy;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Column(name = "cancel_reason", length = 500)
    private String cancelReason;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "dealerReturn", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<DealerReturnItem> items = new ArrayList<>();

    public void addItem(DealerReturnItem item) {
        if (this.items == null) {
            this.items = new ArrayList<>();
        }
        this.items.add(item);
        item.setDealerReturn(this);
    }

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        if (this.updatedAt == null) {
            this.updatedAt = now;
        }
        if (this.status == null) {
            this.status = DealerReturnStatus.PENDING;
        }
        if (this.totalReturnQuantity == null) {
            this.totalReturnQuantity = BigDecimal.ZERO;
        }
        if (this.totalAmount == null) {
            this.totalAmount = BigDecimal.ZERO;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}

