package com.quanlybanhang.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Một dòng phát sinh trong thẻ kho (S6-02).
 *
 * <p>Mỗi dòng tương ứng một sự kiện thay đổi tồn kho:
 * nhập, xuất, điều chỉnh, xuất chuyển, nhận chuyển hoặc hoàn phục khi hủy chuyển.
 */
public record StockCardEntryResponse(
        /** Thứ tự dòng trong trang hiện tại (1-based). */
        int rowNumber,

        /** Thời điểm phát sinh sự kiện (ghi sổ, điều phối, nhận, hủy). */
        LocalDateTime eventTime,

        /**
         * Loại sự kiện: IMPORT, EXPORT, ADJUSTMENT,
         * TRANSFER_DISPATCH, TRANSFER_RECEIVE, TRANSFER_CANCEL.
         */
        String eventType,

        /** Nhãn tiếng Việt của loại sự kiện. */
        String eventTypeLabel,

        /** Thay đổi số lượng theo đơn vị cơ sở (có dấu: dương = vào kho, âm = ra kho). */
        BigDecimal baseQuantityChange,

        /** Tồn kho (đơn vị cơ sở) SAU khi sự kiện được áp dụng. */
        BigDecimal runningBaseBalance,

        /** Đơn vị cơ sở của sản phẩm (đóng băng tại thời điểm phát sinh). */
        String baseUnit,

        /** Số lượng theo đơn vị hiển thị ghi trên chứng từ. */
        BigDecimal displayQuantity,

        /** Đơn vị hiển thị ghi trên chứng từ. */
        String displayUnit,

        /** ID kho liên quan đến sự kiện. */
        Long warehouseId,

        /** Mã kho liên quan. */
        String warehouseCode,

        /** Tên kho liên quan. */
        String warehouseName,

        /**
         * Loại chứng từ gốc: VOUCHER, TRANSFER.
         */
        String sourceType,

        /** ID chứng từ gốc (ID phiếu kho hoặc ID phiếu chuyển kho). */
        Long sourceId,

        /** Mã chứng từ gốc (voucherCode hoặc transferCode). */
        String sourceCode,

        /** Ghi chú trên chứng từ hoặc dòng hàng. */
        String note
) {
}
