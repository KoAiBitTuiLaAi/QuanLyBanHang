package com.quanlybanhang.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Chi tiết phiếu kho (S2-07).
 *
 * Lưu giữ giá trị đóng băng của đơn vị tính, hệ số quy đổi và số lượng cơ sở
 * tại thời điểm ghi sổ.
 * Đổi hệ số quy đổi sau này KHÔNG làm thay đổi số liệu trong bản ghi này!
 */
@Entity
@Table(name = "warehouse_voucher_items")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WarehouseVoucherItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "voucher_id", nullable = false)
    @JsonIgnore
    private WarehouseVoucher voucher;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    /**
     * Đơn vị tính nhân viên kho đã chọn khi nhập/xuất (vd: "thùng", "lốc", "lon").
     */
    @Column(nullable = false, length = 50)
    private String unit;

    /**
     * Số lượng theo đơn vị đã chọn (vd: 10 thùng).
     */
    @Column(precision = 15, scale = 4, nullable = false)
    private BigDecimal quantity;

    /**
     * Hệ số quy đổi về đơn vị cơ sở ĐÓNG BĂNG tại thời điểm ghi sổ (vd: 24.0).
     */
    @Column(name = "conversion_factor", precision = 12, scale = 4, nullable = false)
    private BigDecimal conversionFactor;

    /**
     * Tên đơn vị cơ sở ĐÓNG BĂNG tại thời điểm ghi sổ (vd: "lon").
     */
    @Column(name = "base_unit", nullable = false, length = 50)
    private String baseUnit;

    /**
     * Số lượng đã quy đổi về đơn vị cơ sở khi ghi sổ (vd: 240 lon = 10 * 24.0).
     * Đây là số lượng dùng để cộng/trừ vào sổ sách tồn kho.
     */
    @Column(name = "base_quantity", precision = 15, scale = 4, nullable = false)
    private BigDecimal baseQuantity;

    @Column(precision = 15, scale = 2)
    private BigDecimal price;

    @Column(name = "total_amount", precision = 15, scale = 2)
    private BigDecimal totalAmount;

    /**
     * Số lô hàng (S5-04).
     */
    @Column(name = "lot_number", length = 50)
    private String lotNumber;

    /**
     * Hạn sử dụng của lô hàng (S5-04).
     */
    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    /**
     * Ngày sản xuất (S5-04).
     */
    @Column(name = "manufacturing_date")
    private LocalDate manufacturingDate;

    @Column(length = 255)
    private String note;
}
