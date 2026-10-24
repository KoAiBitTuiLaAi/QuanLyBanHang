package com.quanlybanhang.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Entity Hóa đơn bán hàng (S7-01, S7-07).
 *
 * Tiêu chí nghiệp vụ:
 * 1. Hoá đơn sinh từ số lượng thực giao, không phải số lượng đặt.
 * 2. Số hoá đơn tự sinh theo dãy liên tục, không trùng và không nhảy cóc.
 * 3. Gộp nhiều phiếu giao của cùng đại lý trong kỳ thành một hoá đơn được.
 * 4. Hoá đơn đã phát hành không sửa và không xoá, chỉ lập chứng từ điều chỉnh.
 * 5. In hoặc xuất PDF được.
 * 6. Lưu hạn thanh toán (dueDate) phục vụ theo dõi công nợ đại lý (S7-07).
 */
@Entity
@Table(name = "invoices", uniqueConstraints = {
        @UniqueConstraint(columnNames = "invoice_number"),
        @UniqueConstraint(columnNames = "invoice_code")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Invoice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Số hoá đơn tự sinh theo dãy liên tục, không trùng và không nhảy cóc (vd: "00000001").
     */
    @Column(name = "invoice_number", nullable = false, unique = true, length = 20)
    private String invoiceNumber;

    /**
     * Mã hóa đơn hiển thị kèm tiền tố (vd: "HD-00000001").
     */
    @Column(name = "invoice_code", nullable = false, unique = true, length = 50)
    private String invoiceCode;

    /**
     * Ngày phát hành hóa đơn.
     */
    @Column(name = "invoice_date", nullable = false)
    private LocalDateTime invoiceDate;

    /**
     * Hạn thanh toán của hóa đơn (S7-07).
     * Mặc định tính theo ngày phát hành + số ngày nợ tối đa của đại lý (maxDebtDays).
     */
    @Column(name = "due_date")
    private LocalDate dueDate;

    /**
     * Đại lý nhận hóa đơn.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dealer_id", nullable = false)
    private Dealer dealer;

    @Column(name = "dealer_code", nullable = false, length = 50)
    private String dealerCode;

    @Column(name = "dealer_name", nullable = false, length = 255)
    private String dealerName;

    @Column(name = "dealer_tax_code", length = 50)
    private String dealerTaxCode;

    @Column(name = "dealer_address", length = 500)
    private String dealerAddress;

    @Column(name = "dealer_phone", length = 50)
    private String dealerPhone;

    /**
     * Trạng thái hóa đơn: ISSUED, ADJUSTED, CANCELLED.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private InvoiceStatus status = InvoiceStatus.ISSUED;

    /**
     * Tổng tiền hàng trước chiết khấu (tính từ số lượng thực giao * đơn giá).
     */
    @Column(name = "subtotal_amount", precision = 15, scale = 2, nullable = false)
    @Builder.Default
    private BigDecimal subtotalAmount = BigDecimal.ZERO;

    /**
     * Tổng tiền chiết khấu (tính theo số lượng thực giao).
     */
    @Column(name = "discount_amount", precision = 15, scale = 2, nullable = false)
    @Builder.Default
    private BigDecimal discountAmount = BigDecimal.ZERO;

    /**
     * Tổng tiền thanh toán của hóa đơn gốc.
     */
    @Column(name = "total_amount", precision = 15, scale = 2, nullable = false)
    @Builder.Default
    private BigDecimal totalAmount = BigDecimal.ZERO;

    /**
     * Tổng tiền sau các chứng từ điều chỉnh (nếu có).
     */
    @Column(name = "adjusted_total_amount", precision = 15, scale = 2, nullable = false)
    @Builder.Default
    private BigDecimal adjustedTotalAmount = BigDecimal.ZERO;

    @Column(length = 500)
    private String note;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * Danh sách dòng hàng xuất hóa đơn (theo số lượng thực giao).
     */
    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<InvoiceItem> items = new ArrayList<>();

    /**
     * Danh sách phiếu giao (đơn hàng) gộp trong hóa đơn này.
     * Ràng buộc duy nhất trên order_id đảm bảo 1 phiếu giao không thể bị xuất hóa đơn nhiều lần!
     */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "invoice_orders",
            joinColumns = @JoinColumn(name = "invoice_id"),
            inverseJoinColumns = @JoinColumn(name = "order_id"),
            uniqueConstraints = @UniqueConstraint(columnNames = "order_id")
    )
    @Builder.Default
    private List<Order> orders = new ArrayList<>();

    /**
     * Danh sách chứng từ điều chỉnh liên quan đến hóa đơn này.
     */
    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<InvoiceAdjustment> adjustments = new ArrayList<>();

    public void addItem(InvoiceItem item) {
        if (this.items == null) {
            this.items = new ArrayList<>();
        }
        this.items.add(item);
        item.setInvoice(this);
    }

    public void addOrder(Order order) {
        if (this.orders == null) {
            this.orders = new ArrayList<>();
        }
        this.orders.add(order);
    }

    public void addAdjustment(InvoiceAdjustment adjustment) {
        if (this.adjustments == null) {
            this.adjustments = new ArrayList<>();
        }
        this.adjustments.add(adjustment);
        adjustment.setInvoice(this);
    }

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
        if (this.invoiceDate == null) {
            this.invoiceDate = LocalDateTime.now();
        }
        if (this.dueDate == null && this.invoiceDate != null) {
            int maxDays = (this.dealer != null && this.dealer.getMaxDebtDays() != null && this.dealer.getMaxDebtDays() > 0)
                    ? this.dealer.getMaxDebtDays() : 0;
            this.dueDate = this.invoiceDate.toLocalDate().plusDays(maxDays);
        }
        if (this.status == null) {
            this.status = InvoiceStatus.ISSUED;
        }
        if (this.adjustedTotalAmount == null) {
            this.adjustedTotalAmount = this.totalAmount != null ? this.totalAmount : BigDecimal.ZERO;
        }
    }
}
