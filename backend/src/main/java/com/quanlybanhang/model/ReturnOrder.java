package com.quanlybanhang.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Phieu tra hang (S8-02) - Ke toan cong no lap de giam tru cong no khi dai ly tra hang.
 *
 * Tieu chi nghiep vu:
 * 1. Lien ket toi hoa don goc (invoice).
 * 2. Khi xac nhan (CONFIRMED), he thong tu dong sinh InvoiceAdjustment(DECREASE) dung gia tren hoa don goc.
 * 3. Cong no va han muc kha dung cua dai ly duoc cap nhat ngay sau xac nhan.
 * 4. Hoa don goc khong bi sua.
 */
@Entity
@Table(name = "return_orders", uniqueConstraints = {
        @UniqueConstraint(columnNames = "return_code")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReturnOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private Long version;

    /** Ma phieu tra hang - duy nhat (vd: TR-20261022-0001). */
    @Column(name = "return_code", nullable = false, unique = true, length = 50)
    private String returnCode;

    /** Hoa don goc ma phieu tra hang nay lien ket toi. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invoice_id", nullable = false)
    @JsonIgnore
    private Invoice invoice;

    /** Dai ly tra hang. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dealer_id", nullable = false)
    private Dealer dealer;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ReturnOrderStatus status = ReturnOrderStatus.DRAFT;

    /**
     * Ly do tra hang (bat buoc).
     */
    @Column(name = "return_reason", nullable = false, length = 500)
    private String returnReason;

    /** Ngay lap phieu tra hang. */
    @Column(name = "return_date", nullable = false)
    private LocalDateTime returnDate;

    /**
     * Tong tien hang tra (tinh tu gia hoa don goc).
     * Sau khi xac nhan, day la so tien cong no duoc giam.
     */
    @Column(name = "total_return_amount", precision = 15, scale = 2, nullable = false)
    @Builder.Default
    private BigDecimal totalReturnAmount = BigDecimal.ZERO;

    /** Lien ket chung tu dieu chinh da sinh (sau khi CONFIRMED). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invoice_adjustment_id", unique = true)
    private InvoiceAdjustment invoiceAdjustment;

    @Column(name = "confirmed_by", length = 100)
    private String confirmedBy;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "cancel_reason", length = 500)
    private String cancelReason;

    @Column(length = 500)
    private String note;

    @OneToMany(mappedBy = "returnOrder", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<ReturnOrderItem> items = new ArrayList<>();

    public void addItem(ReturnOrderItem item) {
        if (this.items == null) {
            this.items = new ArrayList<>();
        }
        this.items.add(item);
        item.setReturnOrder(this);
    }

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
        if (this.returnDate == null) {
            this.returnDate = LocalDateTime.now();
        }
        if (this.status == null) {
            this.status = ReturnOrderStatus.DRAFT;
        }
        if (this.totalReturnAmount == null) {
            this.totalReturnAmount = BigDecimal.ZERO;
        }
    }
}