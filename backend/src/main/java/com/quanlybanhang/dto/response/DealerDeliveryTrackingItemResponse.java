package com.quanlybanhang.dto.response;

import lombok.*;

import java.math.BigDecimal;

/**
 * Chi tiết số lượng thực giao từng dòng hàng (S6-09).
 * Hiển thị chính xác số lượng đặt, số lượng thực giao, số lượng thiếu và lý do thiếu.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DealerDeliveryTrackingItemResponse {

    private Long orderItemId;
    private Long productId;
    private String productCode;
    private String productName;
    private String unit;
    private String baseUnit;

    /**
     * Số lượng theo đơn vị đặt hàng.
     */
    private BigDecimal quantity;

    /**
     * Số lượng đã xuất kho theo đơn vị đặt hàng.
     */
    private BigDecimal exportedQuantity;

    /**
     * Số lượng còn nợ / chưa xuất kho.
     */
    private BigDecimal remainingExportQuantity;

    /**
     * Số lượng thực giao theo đơn vị đặt hàng. Null nếu chưa giao; >= 0 nếu đã ghi nhận.
     */
    private BigDecimal deliveredQuantity;

    /**
     * Số lượng giao thiếu theo đơn vị đặt hàng (quantity - deliveredQuantity). Null nếu chưa giao.
     */
    private BigDecimal shortageQuantity;

    /**
     * Đánh dấu dòng hàng bị giao thiếu.
     */
    private Boolean isShortage;

    /**
     * Ghi chú giao hàng / lý do giao thiếu cho dòng sản phẩm.
     */
    private String deliveryNote;

    private BigDecimal unitPrice;
    private BigDecimal totalAmount;
}

