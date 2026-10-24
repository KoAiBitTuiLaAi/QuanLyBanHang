package com.quanlybanhang.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Chứng từ điều chỉnh hóa đơn bán hàng (S7-01).
 *
 * Tiêu chí cốt lõi: Hóa đơn đã phát hành KHÔNG sửa và KHÔNG xóa, chỉ lập chứng từ điều chỉnh.
 * Bắt buộc nhập lý do điều chỉnh.
 */
@Entity
@Table(name = "invoice_adjustments", uniqueConstraints = {
        @UniqueConstraint(columnNames = "adjustment_code")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvoiceAdjustment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "adjustment_code", nullable = false, unique = true, length = 50)
    private String adjustmentCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invoice_id", nullable = false)
    @JsonIgnore
    private Invoice invoice;

    @Enumerated(EnumType.STRING)
    @Column(name = "adjustment_type", nullable = false, length = 30)
    private InvoiceAdjustmentType adjustmentType;

    /**
     * Lý do điều chỉnh (bắt buộc nhập theo tiêu chí nghiệm thu).
     */
    @Column(name = "adjustment_reason", nullable = false, length = 500)
    private String adjustmentReason;

    @Column(name = "adjustment_date", nullable = false)
    private LocalDateTime adjustmentDate;

    /**
     * Số tiền điều chỉnh:
     * - Dương nếu điều chỉnh tăng (INCREASE)
     * - Âm nếu điều chỉnh giảm (DECREASE)
     * - Bằng 0 nếu điều chỉnh thông tin (INFORMATION) hoặc hủy (CANCEL)
     */
    @Column(name = "adjustment_amount", precision = 15, scale = 2, nullable = false)
    @Builder.Default
    private BigDecimal adjustmentAmount = BigDecimal.ZERO;

    @Column(name = "new_dealer_name", length = 255)
    private String newDealerName;

    @Column(name = "new_dealer_tax_code", length = 50)
    private String newDealerTaxCode;

    @Column(name = "new_dealer_address", length = 500)
    private String newDealerAddress;

    @Column(name = "adjusted_by", length = 100)
    private String adjustedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(length = 500)
    private String note;

    @OneToMany(mappedBy = "adjustment", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<InvoiceAdjustmentItem> items = new ArrayList<>();

    public void addItem(InvoiceAdjustmentItem item) {
        if (this.items == null) {
            this.items = new ArrayList<>();
        }
        this.items.add(item);
        item.setAdjustment(this);
    }

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
        if (this.adjustmentDate == null) {
            this.adjustmentDate = LocalDateTime.now();
        }
        if (this.adjustmentAmount == null) {
            this.adjustmentAmount = BigDecimal.ZERO;
        }
    }
}

