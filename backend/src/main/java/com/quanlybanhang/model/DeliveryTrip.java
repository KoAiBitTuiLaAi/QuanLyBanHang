package com.quanlybanhang.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Entity Chuyến giao hàng (S6-07).
 * Phục vụ Quản lý kho theo dõi hàng đã xuất kho nhưng chưa giao xong,
 * biết rõ bao nhiêu tiền hàng đang nằm trên đường.
 */
@Entity
@Table(name = "delivery_trips", uniqueConstraints = {
        @UniqueConstraint(name = "uk_delivery_trip_code", columnNames = "trip_code")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeliveryTrip {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trip_code", nullable = false, length = 50, unique = true)
    private String tripCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "warehouse_id", nullable = false)
    private Warehouse warehouse;

    @Column(name = "driver_name", nullable = false, length = 100)
    private String driverName;

    @Column(name = "driver_phone", length = 20)
    private String driverPhone;

    @Column(name = "vehicle_plate", nullable = false, length = 30)
    private String vehiclePlate;

    /**
     * Tên tuyến giao hàng (S6-06).
     * Phục vụ Quản lý kho phân tuyến giao hàng theo khu vực.
     */
    @Column(name = "route_name", length = 150)
    private String routeName;

    /**
     * Khu vực / địa bàn phân tuyến của chuyến (S6-06).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "territory_id")
    private Territory territory;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    @Builder.Default
    private DeliveryTripStatus status = DeliveryTripStatus.IN_PROGRESS;

    @Column(name = "departure_time", nullable = false)
    private LocalDateTime departureTime;

    /**
     * Thời gian dự kiến hoàn thành chuyến giao (S6-07).
     * Dùng để cảnh báo khi chuyến quá thời gian dự kiến.
     */
    @Column(name = "expected_completion_time", nullable = false)
    private LocalDateTime expectedCompletionTime;

    @Column(name = "actual_completion_time")
    private LocalDateTime actualCompletionTime;

    @Column(name = "note", length = 500)
    private String note;

    @Column(name = "cancel_reason", length = 500)
    private String cancelReason;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "trip", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<DeliveryTripStop> stops = new ArrayList<>();

    public void addStop(DeliveryTripStop stop) {
        if (this.stops == null) {
            this.stops = new ArrayList<>();
        }
        this.stops.add(stop);
        stop.setTrip(this);
    }

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
        if (this.updatedAt == null) {
            this.updatedAt = LocalDateTime.now();
        }
        if (this.departureTime == null) {
            this.departureTime = LocalDateTime.now();
        }
        if (this.status == null) {
            this.status = DeliveryTripStatus.IN_PROGRESS;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * Cảnh báo chuyến quá thời gian dự kiến (S6-07).
     * Áp dụng khi chuyến đang chạy (IN_PROGRESS) và thời điểm hiện tại đã vượt qua thời gian dự kiến.
     */
    public boolean isOverdue() {
        return this.status == DeliveryTripStatus.IN_PROGRESS
                && this.expectedCompletionTime != null
                && LocalDateTime.now().isAfter(this.expectedCompletionTime);
    }

    /**
     * Số phút quá hạn so với dự kiến (0 nếu chưa quá hạn).
     */
    public long getOverdueMinutes() {
        if (!isOverdue()) {
            return 0L;
        }
        return Duration.between(this.expectedCompletionTime, LocalDateTime.now()).toMinutes();
    }

    /**
     * Tổng số điểm giao của chuyến.
     */
    public int getTotalPoints() {
        return this.stops != null ? this.stops.size() : 0;
    }

    /**
     * Số điểm đã giao thành công (DELIVERED).
     */
    public long getDeliveredPoints() {
        if (this.stops == null) return 0L;
        return this.stops.stream()
                .filter(s -> s.getStatus() == DeliveryStopStatus.DELIVERED)
                .count();
    }

    /**
     * Số điểm chưa xử lý xong (PENDING + FAILED, loại điểm đã xác nhận trả kho).
     * Theo S6-07: Hàng chưa giao được vẫn thuộc trách nhiệm của chuyến, không tự về kho.
     */
    public long getRemainingPoints() {
        if (this.stops == null) return 0L;
        return this.stops.stream()
                .filter(s -> s.getStatus() != DeliveryStopStatus.DELIVERED && !Boolean.TRUE.equals(s.getReturnedToWarehouse()))
                .count();
    }

    /**
     * Số điểm chưa giao được (FAILED) - vẫn thuộc trách nhiệm của chuyến.
     */
    public long getFailedPoints() {
        if (this.stops == null) return 0L;
        return this.stops.stream()
                .filter(s -> s.getStatus() == DeliveryStopStatus.FAILED)
                .count();
    }

    /**
     * Số điểm đang chờ giao (PENDING).
     */
    public long getPendingPoints() {
        if (this.stops == null) return 0L;
        return this.stops.stream()
                .filter(s -> s.getStatus() == DeliveryStopStatus.PENDING)
                .count();
    }

    /**
     * Tổng tiền hàng xuất phát của chuyến giao.
     */
    public BigDecimal getTotalValue() {
        if (this.stops == null || this.stops.isEmpty()) return BigDecimal.ZERO;
        return this.stops.stream()
                .map(s -> s.getDeliveryAmount() != null ? s.getDeliveryAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Tổng tiền hàng đã giao xong đến nơi nhận.
     */
    public BigDecimal getDeliveredValue() {
        if (this.stops == null || this.stops.isEmpty()) return BigDecimal.ZERO;
        return this.stops.stream()
                .filter(s -> s.getStatus() == DeliveryStopStatus.DELIVERED)
                .map(s -> s.getDeliveryAmount() != null ? s.getDeliveryAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Tiền hàng đang nằm trên đường (S6-07).
     * Bao gồm:
     * - Các điểm chờ giao (PENDING)
     * - Các điểm chưa giao được (FAILED) vì hàng chưa giao được vẫn thuộc trách nhiệm chuyến, không tự về kho.
     * Chỉ loại ra khi đã có xác nhận returnedToWarehouse, không suy từ trạng thái chuyến.
     */
    public BigDecimal getRemainingValue() {
        if (this.stops == null || this.stops.isEmpty()) return BigDecimal.ZERO;
        return this.stops.stream()
                .filter(s -> s.getStatus() != DeliveryStopStatus.DELIVERED && !Boolean.TRUE.equals(s.getReturnedToWarehouse()))
                .map(s -> s.getDeliveryAmount() != null ? s.getDeliveryAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Giá trị tiền hàng của các điểm chưa giao được (FAILED).
     */
    public BigDecimal getFailedValue() {
        if (this.stops == null || this.stops.isEmpty()) return BigDecimal.ZERO;
        return this.stops.stream()
                .filter(s -> s.getStatus() == DeliveryStopStatus.FAILED)
                .map(s -> s.getDeliveryAmount() != null ? s.getDeliveryAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}

