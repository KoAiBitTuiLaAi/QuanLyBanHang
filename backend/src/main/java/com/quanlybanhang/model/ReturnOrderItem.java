package com.quanlybanhang.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * Chi tiet dong hang cua phieu tra hang (S8-02).
 * Gia phai lay tu hoa don goc, khong phai gia hien hanh.
 */
@Entity
@Table(name = "return_order_items")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReturnOrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "return_order_id", nullable = false)
    @JsonIgnore
    private ReturnOrder returnOrder;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "product_code", nullable = false, length = 50)
    private String productCode;

    @Column(name = "product_name", nullable = false, length = 255)
    private String productName;

    @Column(nullable = false, length = 50)
    private String unit;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invoice_item_id", nullable = false)
    private InvoiceItem invoiceItem;

    /** So luong tra hang. */
    @Column(name = "return_quantity", precision = 15, scale = 4, nullable = false)
    private BigDecimal returnQuantity;

    /**
     * Don gia theo hoa don goc (khong phai gia hien hanh - S8-02).
     */
    @Column(name = "invoice_unit_price", precision = 15, scale = 2, nullable = false)
    private BigDecimal invoiceUnitPrice;

    /**
     * Chiet khau theo hoa don goc (so tien chiet khau / don vi).
     */
    @Column(name = "invoice_discount_per_unit", precision = 19, scale = 6, nullable = false)
    @Builder.Default
    private BigDecimal invoiceDiscountPerUnit = BigDecimal.ZERO;

    /**
     * Tien tra tinh theo ty le thanh tien dong hoa don goc; lam tron luy ke de bao toan so le.
     */
    @Column(name = "return_amount", precision = 15, scale = 2, nullable = false)
    private BigDecimal returnAmount;

    @Column(length = 255)
    private String note;
}