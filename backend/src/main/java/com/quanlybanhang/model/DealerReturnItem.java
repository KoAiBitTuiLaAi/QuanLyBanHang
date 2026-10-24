package com.quanlybanhang.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Chi tiết dòng hàng trả từ đại lý (S8-01).
 *
 * Lưu trữ đóng băng thông tin đơn vị, hệ số quy đổi, lý do trả bắt buộc,
 * tình trạng hàng (hàng tốt / hàng hỏng), kho đích thực tế nhận hàng và số lô trả về.
 */
@Entity
@Table(name = "dealer_return_items")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DealerReturnItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dealer_return_id", nullable = false)
    @JsonIgnore
    private DealerReturn dealerReturn;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invoice_item_id", nullable = false)
    private InvoiceItem invoiceItem;

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

    @Column(name = "manage_lot", nullable = false)
    @Builder.Default
    private Boolean manageLot = false;

    @Column(nullable = false, length = 50)
    private String unit;

    @Column(name = "conversion_factor", precision = 12, scale = 4, nullable = false)
    private BigDecimal conversionFactor;

    @Column(name = "base_unit", nullable = false, length = 50)
    private String baseUnit;

    /** Số lượng đã giao trên dòng hóa đơn gốc. */
    @Column(name = "delivered_quantity", precision = 15, scale = 4, nullable = false)
    private BigDecimal deliveredQuantity;

    /** Phần đã trả trước đó của dòng hóa đơn này tại thời điểm lập phiếu. */
    @Column(name = "previously_returned_quantity", precision = 15, scale = 4, nullable = false)
    @Builder.Default
    private BigDecimal previouslyReturnedQuantity = BigDecimal.ZERO;

    /** Số lượng trả lần này theo đơn vị dòng hóa đơn. */
    @Column(name = "return_quantity", precision = 15, scale = 4, nullable = false)
    private BigDecimal returnQuantity;

    /** Số lượng trả đã quy đổi về đơn vị cơ sở để cộng sổ tồn kho khi xác nhận. */
    @Column(name = "base_return_quantity", precision = 15, scale = 4, nullable = false)
    private BigDecimal baseReturnQuantity;

    @Column(name = "unit_price", precision = 15, scale = 2, nullable = false)
    @Builder.Default
    private BigDecimal unitPrice = BigDecimal.ZERO;

    @Column(name = "total_amount", precision = 15, scale = 2, nullable = false)
    @Builder.Default
    private BigDecimal totalAmount = BigDecimal.ZERO;

    /** Lý do trả bắt buộc cho từng dòng hàng. */
    @Column(name = "return_reason", nullable = false, length = 500)
    private String returnReason;

    /** Đánh dấu hàng hỏng (true = nhập vào kho hàng lỗi; false = hàng tốt nhập lại kho thường). */
    @Column(name = "is_defective", nullable = false)
    @Builder.Default
    private Boolean defective = false;

    /** Tình trạng hàng: GOOD hoặc DEFECTIVE. */
    @Column(name = "item_condition", nullable = false, length = 30)
    @Builder.Default
    private String itemCondition = "GOOD";

    /** Kho đích nhận dòng hàng này (Kho thường nếu GOOD, Kho hàng lỗi nếu DEFECTIVE). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_warehouse_id", nullable = false)
    private Warehouse targetWarehouse;

    /** Số lô nhập lại (bắt buộc nếu SKU có quản lý lô). */
    @Column(name = "lot_number", length = 50)
    private String lotNumber;

    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    @Column(name = "manufacturing_date")
    private LocalDate manufacturingDate;

    @Column(length = 255)
    private String note;
}

