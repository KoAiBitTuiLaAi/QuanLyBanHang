package com.quanlybanhang.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Request lập phiếu trả hàng từ đại lý (S8-01).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateDealerReturnRequest {

    /** Mã phiếu trả tùy chọn (nếu để trống hệ thống tự sinh PTH-...). */
    private String returnCode;

    /** ID hóa đơn bán hàng gốc (bắt buộc). */
    @NotNull(message = "Bắt buộc chọn hóa đơn gốc")
    private Long invoiceId;

    /** Kho nhận hàng tốt (nếu để trống sẽ tự lấy theo kho của đơn hàng trên hóa đơn hoặc kho mặc định của đại lý). */
    private Long warehouseId;

    /** Kho hàng lỗi để nhập hàng hỏng (nếu có dòng hàng hỏng). */
    private Long defectiveWarehouseId;

    /** Lý do trả hàng chung trên đầu phiếu (hỗ trợ mặc định cho dòng hàng hoặc ghi nhận chung). */
    private String returnReason;

    /** Alias cho returnReason. */
    private String reason;

    private String note;

    @NotEmpty(message = "Bắt buộc chọn ít nhất 1 dòng hàng trả")
    @Valid
    private List<CreateDealerReturnItemRequest> items;

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

