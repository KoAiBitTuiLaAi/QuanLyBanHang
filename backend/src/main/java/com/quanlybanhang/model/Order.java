package com.quanlybanhang.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Entity Đơn hàng (S2-07).
 * Khi ghi sổ đơn hàng, các dòng sản phẩm dù đặt theo thùng hay lốc
 * đều được quy về đơn vị cơ sở (số lon) để phục vụ kiểm soát bán hàng và trừ kho.
 */
@Entity
@Table(name = "orders", uniqueConstraints = {
        @UniqueConstraint(columnNames = "order_code")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    @Column(nullable = false, columnDefinition = "bigint default 0")
    private Long version;

    /** S6-05: outstanding quantities after an actual, immutable shipment. */
    @Column(name = "backordered", columnDefinition = "boolean default false")
    @Builder.Default
    private Boolean backordered = false;

    /** Makes every shipment increment the order version, even repeated partial exports. */
    @Column(name = "shipment_sequence", columnDefinition = "bigint default 0")
    @Builder.Default
    private Long shipmentSequence = 0L;

    @Column(name = "order_code", nullable = false, length = 50, unique = true)
    private String orderCode;

    @Column(name = "customer_name", length = 255)
    private String customerName;

    @Enumerated(EnumType.STRING)
    @Column(name = "customer_group", length = 30)
    private CustomerGroup customerGroup;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dealer_id")
    private Dealer dealer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "delivery_point_id")
    private DealerDeliveryPoint deliveryPoint;

    /** Kho phục vụ / xuất hàng cho đơn (S4-03). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "warehouse_id")
    private Warehouse warehouse;

    /** Ngày giao mong muốn của đơn hàng (S3-09). */
    @Column(name = "expected_delivery_date")
    private LocalDate expectedDeliveryDate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "price_list_id")
    private PriceList priceList;


    /**
     * Trạng thái đơn hàng theo vòng đời (S4-06):
     * DRAFT, PENDING_APPROVAL, RECORDED/APPROVED, PICKING, EXPORTED, DELIVERED, CLOSED, CANCELLED, REJECTED
     */
    @Column(length = 30)
    @Builder.Default
    private String status = "RECORDED";

    /**
     * Đánh dấu đơn hàng đã được giữ chỗ tồn kho trong kho (S4-06).
     * Sẽ tự động nhả giữ chỗ khi huỷ đơn hoặc trừ tồn thực tế khi xuất kho.
     */
    @Column(name = "inventory_reserved", nullable = false)
    @Builder.Default
    private Boolean inventoryReserved = false;

    /**
     * Lý do huỷ đơn hàng (bắt buộc khi huỷ) (S4-06).
     */
    @Column(name = "cancel_reason", length = 500)
    private String cancelReason;

    /**
     * Người thực hiện huỷ đơn (S4-06).
     */
    @Column(name = "cancelled_by", length = 100)
    private String cancelledBy;

    /**
     * Thời điểm huỷ đơn (S4-06).
     */
    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    /**
     * Thời điểm bắt đầu giữ chỗ tồn kho (S5-06).
     */
    @Column(name = "reserved_at")
    private LocalDateTime reservedAt;

    /**
     * Thời hạn giữ chỗ tồn kho (S5-06).
     * Khi vượt quá thời điểm này mà đơn chưa xuất kho hoặc huỷ, hệ thống sẽ tự động nhả tồn giữ chỗ.
     */
    @Column(name = "reservation_expires_at")
    private LocalDateTime reservationExpiresAt;

    /**
     * Đánh dấu đơn hàng có dòng sản phẩm bán dưới giá sàn cần qua duyệt (S2-10).
     */
    @Column(name = "requires_approval", nullable = false)
    @Builder.Default
    private Boolean requiresApproval = false;

    @Column(name = "approval_reason", length = 1000)
    private String approvalReason;

    @Column(name = "approved_by", length = 100)
    private String approvedBy;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    /**
     * Tổng tiền hàng trước chiết khấu (S3-01).
     */
    @Column(name = "subtotal_amount", precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal subtotalAmount = BigDecimal.ZERO;

    /**
     * Tổng tiền chiết khấu theo sản lượng của đơn hàng (S3-01).
     */
    @Column(name = "discount_amount", precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal discountAmount = BigDecimal.ZERO;

    @Column(name = "total_amount", precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Column(length = 500)
    private String note;




    /**
     * Ngày thực tế giao hàng (S5-01).
     */
    @Column(name = "actual_delivery_date")
    private LocalDate actualDeliveryDate;

    /**
     * Ghi chú giao hàng tổng thể / tình trạng giao (S5-01).
     */
    @Column(name = "delivery_note", length = 500)
    private String deliveryNote;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /**
     * Thời điểm ghi sổ đơn hàng.
     */
    @Column(name = "recorded_at")
    private LocalDateTime recordedAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<OrderItem> items = new ArrayList<>();

    public void addItem(OrderItem item) {
        if (this.items == null) {
            this.items = new ArrayList<>();
        }
        this.items.add(item);
        item.setOrder(this);
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        if (this.recordedAt == null) {
            this.recordedAt = LocalDateTime.now();
        }
        if (this.status == null) {
            this.status = "RECORDED";
        }
        if (this.inventoryReserved == null) {
            this.inventoryReserved = false;
        }
        if (this.subtotalAmount == null) {
            this.subtotalAmount = BigDecimal.ZERO;
        }
        if (this.discountAmount == null) {
            this.discountAmount = BigDecimal.ZERO;
        }
        if (this.totalAmount == null) {
            this.totalAmount = BigDecimal.ZERO;
        }
    }
}
