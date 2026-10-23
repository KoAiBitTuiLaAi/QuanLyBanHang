package com.quanlybanhang.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Response chi tiết dòng hàng trên phiếu trả hàng từ đại lý (S8-01).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DealerReturnItemResponse {

    private Long id;
    private Long invoiceItemId;
    private Long orderId;
    private String orderCode;
    private Long orderItemId;
    private Long productId;
    private String productCode;
    private String productName;
    private Boolean manageLot;
    private String unit;
    private BigDecimal conversionFactor;
    private String baseUnit;

    /** Số lượng đã giao trên hóa đơn gốc. */
    private BigDecimal deliveredQuantity;

    /** Phần đã trả trước đó tại thời điểm lập phiếu. */
    private BigDecimal previouslyReturnedQuantity;

    /** Số lượng trả trên dòng phiếu này. */
    private BigDecimal returnQuantity;

    /** Số lượng trả quy đổi về đơn vị cơ sở. */
    private BigDecimal baseReturnQuantity;

    private BigDecimal unitPrice;
    private BigDecimal totalAmount;

    /** Lý do trả bắt buộc. */
    private String returnReason;

    /** Đánh dấu hàng hỏng (nhập vào kho hàng lỗi). */
    private Boolean defective;

    /** Tình trạng hàng: GOOD / DEFECTIVE. */
    private String itemCondition;

    /** Kho đích nhận hàng (kho thương mại hoặc kho hàng lỗi). */
    private Long targetWarehouseId;
    private String targetWarehouseCode;
    private String targetWarehouseName;
    private Boolean targetWarehouseDefective;

    /** Số lô nhập lại (nếu SKU quản lý lô). */
    private String lotNumber;
    private LocalDate expiryDate;
    private LocalDate manufacturingDate;

    private String note;
}

