package com.quanlybanhang.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Chi tiết đối trừ công nợ của phiếu thu vào từng hoá đơn / đơn hàng (S7-02).
 */
@Entity
@Table(name = "debt_payment_allocations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DebtPaymentAllocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Phiếu thu tiền công nợ */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_id", nullable = false)
    private DebtPayment payment;

    /** Hoá đơn / đơn hàng được đối trừ */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    /** Số tiền đối trừ vào hoá đơn này (có thể âm nếu là bút toán điều chỉnh giảm) */
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    /** Mã hoá đơn / đơn hàng lưu trữ tiện tra cứu */
    @Column(name = "order_code", length = 50)
    private String orderCode;

    /** Ghi chú chi tiết cho dòng đối trừ */
    @Column(length = 255)
    private String note;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (order != null && (orderCode == null || orderCode.isBlank())) {
            orderCode = order.getOrderCode();
        }
    }
}
