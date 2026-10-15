package com.quanlybanhang.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Entity Phiếu kho (S2-07).
 * Đại diện cho các giao dịch nhập kho, xuất kho hoặc điều chỉnh kho.
 * Khi ghi sổ (recorded), các chi tiết phiếu quy về đơn vị cơ sở để cập nhật sổ sách kho.
 */
@Entity
@Table(name = "warehouse_vouchers", uniqueConstraints = {
        @UniqueConstraint(columnNames = "voucher_code")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WarehouseVoucher {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "voucher_code", nullable = false, length = 50, unique = true)
    private String voucherCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private WarehouseVoucherType type;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "warehouse_id", nullable = false)
    private Warehouse warehouse;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id")
    private Supplier supplier;

    @Column(length = 30)
    @Builder.Default
    private String status = "RECORDED";

    /**
     * Số chứng từ đi kèm (hóa đơn, phiếu giao hàng NCC - S5-04).
     */
    @Column(name = "document_number", length = 100)
    private String documentNumber;

    /**
     * Ngày nhập kho / ngày chứng từ (S5-04).
     */
    @Column(name = "voucher_date")
    private LocalDateTime voucherDate;

    /**
     * Tham chiếu đến phiếu kho gốc nếu là phiếu điều chỉnh (S5-04).
     */
    @Column(name = "reference_voucher_id")
    private Long referenceVoucherId;

    @Column(name = "reference_voucher_code", length = 50)
    private String referenceVoucherCode;

    /**
     * Lý do điều chỉnh (S5-04).
     */
    @Column(name = "adjustment_reason", length = 500)
    private String adjustmentReason;

    @Column(length = 500)
    private String note;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    /**
     * Thời điểm ghi sổ phiếu kho.
     */
    @Column(name = "recorded_at", nullable = false)
    private LocalDateTime recordedAt;

    @OneToMany(mappedBy = "voucher", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<WarehouseVoucherItem> items = new ArrayList<>();

    public void addItem(WarehouseVoucherItem item) {
        if (this.items == null) {
            this.items = new ArrayList<>();
        }
        this.items.add(item);
        item.setVoucher(this);
    }

    @PrePersist
    protected void onCreate() {
        if (this.recordedAt == null) {
            this.recordedAt = LocalDateTime.now();
        }
        if (this.voucherDate == null) {
            this.voucherDate = LocalDateTime.now();
        }
        if (this.status == null) {
            this.status = "RECORDED";
        }
    }
}
