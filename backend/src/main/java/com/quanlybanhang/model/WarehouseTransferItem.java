package com.quanlybanhang.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * Chi tiết sản phẩm trong phiếu chuyển kho nội bộ (S5-07).
 */
@Entity
@Table(name = "warehouse_transfer_items")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WarehouseTransferItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transfer_id", nullable = false)
    @JsonIgnore
    private WarehouseTransfer transfer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    /**
     * Đơn vị tính nhân viên kho chọn khi xuất chuyển (vd: "thùng", "lốc", "lon").
     */
    @Column(nullable = false, length = 50)
    private String unit;

    /**
     * Số lượng xuất chuyển theo đơn vị đã chọn.
     */
    @Column(precision = 15, scale = 4, nullable = false)
    private BigDecimal quantity;

    /**
     * Hệ số quy đổi về đơn vị cơ sở đóng băng tại thời điểm lập phiếu.
     */
    @Column(name = "conversion_factor", precision = 12, scale = 4, nullable = false)
    private BigDecimal conversionFactor;

    /**
     * Đơn vị cơ sở đóng băng.
     */
    @Column(name = "base_unit", nullable = false, length = 50)
    private String baseUnit;

    /**
     * Số lượng xuất chuyển tính theo đơn vị cơ sở (quantity * conversionFactor).
     */
    @Column(name = "base_quantity", precision = 15, scale = 4, nullable = false)
    private BigDecimal baseQuantity;

    /**
     * Số lượng thực nhận tại kho đến (theo đơn vị unit).
     */
    @Column(name = "received_quantity", precision = 15, scale = 4)
    private BigDecimal receivedQuantity;

    /**
     * Số lượng thực nhận tại kho đến tính theo đơn vị cơ sở.
     */
    @Column(name = "received_base_quantity", precision = 15, scale = 4)
    private BigDecimal receivedBaseQuantity;

    /**
     * Số lượng chênh lệch theo đơn vị unit (quantity - receivedQuantity).
     */
    @Column(name = "discrepancy_quantity", precision = 15, scale = 4)
    private BigDecimal discrepancyQuantity;

    /**
     * Số lượng chênh lệch theo đơn vị cơ sở (baseQuantity - receivedBaseQuantity).
     */
    @Column(name = "discrepancy_base_quantity", precision = 15, scale = 4)
    private BigDecimal discrepancyBaseQuantity;

    /**
     * Lý do chênh lệch khi nhận hàng (Bắt buộc nếu có chênh lệch).
     */
    @Column(name = "discrepancy_reason", length = 500)
    private String discrepancyReason;

    @Column(length = 255)
    private String note;
}

