package com.quanlybanhang.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * Chi tiết dòng chứng từ điều chỉnh hóa đơn (S7-01).
 */
@Entity
@Table(name = "invoice_adjustment_items")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvoiceAdjustmentItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "adjustment_id", nullable = false)
    @JsonIgnore
    private InvoiceAdjustment adjustment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id")
    private Product product;

    @Column(name = "product_code", length = 50)
    private String productCode;

    @Column(name = "product_name", length = 255)
    private String productName;

    @Column(length = 50)
    private String unit;

    /**
     * Số lượng điều chỉnh (+ hoặc -).
     */
    @Column(name = "adjusted_quantity", precision = 15, scale = 4)
    private BigDecimal adjustedQuantity;

    /**
     * Đơn giá điều chỉnh.
     */
    @Column(name = "adjusted_price", precision = 15, scale = 2)
    private BigDecimal adjustedPrice;

    /**
     * Tiền điều chỉnh của dòng (+ hoặc -).
     */
    @Column(name = "adjustment_amount", precision = 15, scale = 2, nullable = false)
    private BigDecimal adjustmentAmount;

    @Column(length = 255)
    private String reason;
}

