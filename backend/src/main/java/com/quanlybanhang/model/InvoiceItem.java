package com.quanlybanhang.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * Chi tiết dòng hàng hóa đơn bán hàng (S7-01).
 *
 * Tiêu chí cốt lõi: Hóa đơn sinh từ số lượng thực giao (deliveredQuantity), không phải số lượng đặt (orderedQuantity).
 */
@Entity
@Table(name = "invoice_items")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvoiceItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invoice_id", nullable = false)
    @JsonIgnore
    private Invoice invoice;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id")
    private Order order;

    @Column(name = "order_code", length = 50)
    private String orderCode;

    @Column(name = "order_item_id")
    private Long orderItemId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "product_code", nullable = false, length = 50)
    private String productCode;

    @Column(name = "product_name", nullable = false, length = 255)
    private String productName;

    @Column(nullable = false, length = 50)
    private String unit;

    /**
     * Số lượng đặt trên đơn hàng ban đầu.
     */
    @Column(name = "ordered_quantity", precision = 15, scale = 4, nullable = false)
    private BigDecimal orderedQuantity;

    /**
     * Số lượng thực tế giao tại phiếu giao hàng (S5-01).
     */
    @Column(name = "delivered_quantity", precision = 15, scale = 4, nullable = false)
    private BigDecimal deliveredQuantity;

    /**
     * Số lượng xuất hóa đơn (bằng đúng delivered_quantity theo tiêu chí S7-01).
     */
    @Column(name = "invoiced_quantity", precision = 15, scale = 4, nullable = false)
    private BigDecimal invoicedQuantity;

    /**
     * Đơn giá chốt từ đơn hàng / bảng giá.
     */
    @Column(name = "unit_price", precision = 15, scale = 2, nullable = false)
    private BigDecimal unitPrice;

    /**
     * Thành tiền trước chiết khấu = invoicedQuantity * unitPrice.
     */
    @Column(name = "subtotal_amount", precision = 15, scale = 2, nullable = false)
    private BigDecimal subtotalAmount;

    @Column(name = "discount_percent", precision = 7, scale = 2)
    private BigDecimal discountPercent;

    @Column(name = "discount_amount", precision = 15, scale = 2, nullable = false)
    @Builder.Default
    private BigDecimal discountAmount = BigDecimal.ZERO;

    /**
     * Thành tiền sau chiết khấu = subtotalAmount - discountAmount.
     */
    @Column(name = "total_amount", precision = 15, scale = 2, nullable = false)
    private BigDecimal totalAmount;

    @Column(length = 255)
    private String note;
}

