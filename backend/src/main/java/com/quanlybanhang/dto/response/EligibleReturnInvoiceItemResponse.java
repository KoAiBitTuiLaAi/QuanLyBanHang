package com.quanlybanhang.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Thông tin dòng hàng trên hóa đơn gốc đủ điều kiện trả hàng (S8-01).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EligibleReturnInvoiceItemResponse {

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
    private BigDecimal unitPrice;

    /** Số lượng đã giao thực tế trên hóa đơn gốc. */
    private BigDecimal deliveredQuantity;

    /** Phần đã trả trước đó (tổng các phiếu trả hợp lệ PENDING + CONFIRMED). */
    private BigDecimal previouslyReturnedQuantity;

    /** Phần đã được Quản lý kho xác nhận nhập kho. */
    private BigDecimal confirmedReturnedQuantity;

    /** Phần đang chờ Quản lý kho xác nhận. */
    private BigDecimal pendingReturnedQuantity;

    /** Số lượng tối đa còn được trả = deliveredQuantity - previouslyReturnedQuantity. */
    private BigDecimal returnableQuantity;

    /** Danh sách lô của sản phẩm tại kho gốc để chọn nhập lại đúng lô. */
    private List<LotOption> availableLots;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LotOption {
        private String lotNumber;
        private LocalDate expiryDate;
        private LocalDate manufacturingDate;
        private BigDecimal currentBaseQuantity;
        private String baseUnit;
    }
}

