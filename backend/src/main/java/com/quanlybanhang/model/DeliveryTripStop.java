package com.quanlybanhang.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Entity Điểm giao hàng của chuyến (S6-07).
 */
@Entity
@Table(name = "delivery_trip_stops")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeliveryTripStop {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trip_id", nullable = false)
    private DeliveryTrip trip;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id")
    private Order order;

    @Column(name = "order_code", length = 50)
    private String orderCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dealer_id")
    private Dealer dealer;

    @Column(name = "dealer_name", length = 255)
    private String dealerName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "delivery_point_id")
    private DealerDeliveryPoint deliveryPoint;

    @Column(name = "receiver_name", length = 100)
    private String receiverName;

    @Column(name = "receiver_phone", length = 20)
    private String receiverPhone;

    @Column(name = "delivery_address", nullable = false, length = 500)
    private String deliveryAddress;

    @Column(name = "stop_order", nullable = false)
    @Builder.Default
    private Integer stopOrder = 1;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    @Builder.Default
    private DeliveryStopStatus status = DeliveryStopStatus.PENDING;

    /**
     * Tiền hàng tại điểm giao này.
     */
    @Column(name = "delivery_amount", precision = 15, scale = 2, nullable = false)
    @Builder.Default
    private BigDecimal deliveryAmount = BigDecimal.ZERO;

    @Column(name = "delivered_at")
    private LocalDateTime deliveredAt;

    /**
     * Lý do chưa giao được khi status = FAILED (bắt buộc).
     */
    @Column(name = "failed_reason", length = 500)
    private String failedReason;

    @Column(name = "failed_at")
    private LocalDateTime failedAt;

    /**
     * Đánh dấu hàng đã được trả về kho (chỉ qua thao tác nhập kho hoàn trả tường minh).
     * Hàng chưa giao được vẫn thuộc trách nhiệm của chuyến, KHÔNG tự về kho!
     */
    @Column(name = "returned_to_warehouse", nullable = false)
    @Builder.Default
    private Boolean returnedToWarehouse = false;

    @Column(name = "returned_at")
    private LocalDateTime returnedAt;

    @Column(name = "note", length = 500)
    private String note;

    @OneToMany(mappedBy = "stop", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<DeliveryTripStopItem> items = new ArrayList<>();

    public void addItem(DeliveryTripStopItem item) {
        if (this.items == null) {
            this.items = new ArrayList<>();
        }
        this.items.add(item);
        item.setStop(this);
    }

    @PrePersist
    protected void onCreate() {
        if (this.status == null) {
            this.status = DeliveryStopStatus.PENDING;
        }
        if (this.deliveryAmount == null) {
            this.deliveryAmount = BigDecimal.ZERO;
        }
        if (this.stopOrder == null) {
            this.stopOrder = 1;
        }
        if (this.returnedToWarehouse == null) {
            this.returnedToWarehouse = false;
        }
    }
}

