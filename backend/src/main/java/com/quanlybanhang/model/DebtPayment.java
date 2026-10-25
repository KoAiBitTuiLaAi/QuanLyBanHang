package com.quanlybanhang.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Phiếu ghi nhận thu tiền / thanh toán công nợ của đại lý (S7-04).
 * Sau mỗi lần ghi nhận thu tiền, hệ thống tự động chạy lại tác vụ đánh giá công nợ.
 */
@Entity
@Table(name = "debt_payments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DebtPayment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Mã phiếu thu tiền - duy nhất (VD: PT-20261022-0001) */
    @Column(name = "payment_code", nullable = false, unique = true, length = 50)
    private String paymentCode;

    /** Đại lý thanh toán nợ */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dealer_id", nullable = false)
    private Dealer dealer;

    /** Số tiền thu nợ (bắt buộc > 0) */
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    /** Ngày thu tiền */
    @Column(name = "payment_date", nullable = false)
    private LocalDate paymentDate;

    /** Phương thức thanh toán (TIEN_MAT, CHUYEN_KHOAN, KHAC) */
    @Column(name = "payment_method", length = 50)
    private String paymentMethod;

    /** Mã tham chiếu / số uỷ nhiệm chi / sao kê ngân hàng */
    @Column(name = "reference_code", length = 100)
    private String referenceCode;

    /** Ghi chú nội dung thu tiền */
    @Column(length = 500)
    private String note;

    /** Người thực hiện ghi nhận */
    @Column(name = "recorded_by", length = 100)
    private String recordedBy;

    /** Loại phiếu: PAYMENT (phiếu thu) hoặc ADJUSTMENT (bút toán điều chỉnh) (S7-02) */
    @Column(name = "payment_type", length = 30)
    @Builder.Default
    private String paymentType = "PAYMENT";

    /** Phiếu thu gốc được điều chỉnh (nếu đây là bút toán điều chỉnh) (S7-02) */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "original_payment_id")
    private DebtPayment originalPayment;

    /** Lý do lập bút toán điều chỉnh (S7-02) */
    @Column(name = "adjustment_reason", length = 500)
    private String adjustmentReason;

    /** Chi tiết đối trừ vào các hoá đơn / đơn hàng (S7-02) */
    @OneToMany(mappedBy = "payment", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private java.util.List<DebtPaymentAllocation> allocations = new java.util.ArrayList<>();

    public void addAllocation(DebtPaymentAllocation allocation) {
        if (this.allocations == null) {
            this.allocations = new java.util.ArrayList<>();
        }
        this.allocations.add(allocation);
        allocation.setPayment(this);
    }

    public void setAllocations(java.util.List<DebtPaymentAllocation> newAllocations) {
        if (this.allocations == null) {
            this.allocations = new java.util.ArrayList<>();
        } else {
            this.allocations.clear();
        }
        if (newAllocations != null) {
            for (DebtPaymentAllocation alloc : newAllocations) {
                this.allocations.add(alloc);
                alloc.setPayment(this);
            }
        }
    }

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (paymentDate == null) {
            paymentDate = LocalDate.now();
        }
        if (paymentType == null) {
            paymentType = "PAYMENT";
        }
    }
}

