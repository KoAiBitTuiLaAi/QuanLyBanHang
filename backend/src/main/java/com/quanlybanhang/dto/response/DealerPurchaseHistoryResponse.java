package com.quanlybanhang.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * DTO phản hồi lịch sử mua hàng của đại lý (S4-04).
 * Hiển thị các mặt hàng đại lý đã mua trong 3 tháng gần nhất kèm số lượng bình quân,
 * và đánh dấu nhóm hàng đã mua trong đơn hàng gần nhất để thêm nhanh vào đơn mới.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DealerPurchaseHistoryResponse {

    /** ID sản phẩm */
    private Long productId;

    /** Mã SKU sản phẩm */
    private String sku;

    /** Mã sản phẩm (đồng bộ với sku để frontend dễ dàng đọc) */
    private String code;

    /** Tên sản phẩm */
    private String name;

    /** Đơn vị đặt hàng phổ biến hoặc gần nhất (ví dụ: thùng, lốc, lon, cái) */
    private String unit;

    /** Số lượng bình quân mỗi đơn trong kỳ */
    private BigDecimal avgQty;

    /** Số đơn hàng có mua sản phẩm này trong kỳ */
    private Integer orderCount;

    /** Tổng số lượng sản phẩm đã mua trong kỳ (theo đơn vị hiển thị) */
    private BigDecimal totalQuantity;

    /** Ngày đặt hàng gần nhất có sản phẩm này */
    private LocalDate lastOrderDate;

    /** Số lượng đặt mua trong lần gần nhất */
    private BigDecimal lastOrderQuantity;

    /** Đơn vị đặt mua trong lần gần nhất */
    private String lastOrderUnit;

    /** Đánh dấu sản phẩm này có thuộc đơn hàng gần nhất của đại lý không */
    private Boolean inLastOrder;

    /** Mã đơn hàng gần nhất có sản phẩm này */
    private String lastOrderCode;
}

