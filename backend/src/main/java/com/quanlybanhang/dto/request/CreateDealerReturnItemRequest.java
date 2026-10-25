package com.quanlybanhang.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Chi tiết dòng hàng trả trên phiếu trả hàng từ đại lý (S8-01).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateDealerReturnItemRequest {

    /** ID dòng hàng trên hóa đơn gốc (ưu tiên dùng để định danh chính xác dòng hóa đơn). */
    private Long invoiceItemId;

    /** Hỗ trợ định danh dòng hóa đơn qua orderItemId nếu client gửi orderItemId. */
    private Long orderItemId;

    /** Hỗ trợ định danh dòng hóa đơn qua productId nếu hóa đơn chỉ có 1 dòng của SKU đó. */
    private Long productId;

    /** Số lượng trả (theo đơn vị trên dòng hóa đơn gốc). */
    private BigDecimal returnQuantity;

    /** Alias cho returnQuantity. */
    private BigDecimal quantity;

    /** Lý do trả hàng bắt buộc cho dòng hàng này. */
    private String returnReason;

    /** Alias cho returnReason. */
    private String reason;

    /** Đánh dấu hàng hỏng / lỗi (true = nhập vào kho hàng lỗi; false = hàng tốt nhập lại kho thương mại). */
    private Boolean defective;

    /** Alias cho defective. */
    private Boolean damaged;

    /** Tình trạng hàng: GOOD hoặc DEFECTIVE / DAMAGED. */
    private String condition;

    /** Kho nhận đích cho dòng hàng này (nếu chỉ định trực tiếp). */
    private Long targetWarehouseId;

    /** Số lô hàng trả về (bắt buộc nếu SKU có quản lý lô). */
    private String lotNumber;

    /** Hạn sử dụng của lô (nếu cung cấp kèm số lô). */
    private LocalDate expiryDate;

    /** Ngày sản xuất của lô (nếu cung cấp kèm số lô). */
    private LocalDate manufacturingDate;

    private String note;

    public BigDecimal getEffectiveReturnQuantity() {
        return returnQuantity != null ? returnQuantity : quantity;
    }

    public String getEffectiveReturnReason() {
        if (returnReason != null && !returnReason.isBlank()) {
            return returnReason.trim();
        }
        if (reason != null && !reason.isBlank()) {
            return reason.trim();
        }
        return null;
    }
}

