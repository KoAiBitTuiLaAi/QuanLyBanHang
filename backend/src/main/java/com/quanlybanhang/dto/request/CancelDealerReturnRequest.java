package com.quanlybanhang.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request hủy phiếu trả hàng từ đại lý khi chưa xác nhận nhập kho (S8-01).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CancelDealerReturnRequest {

    private String reason;

    private String cancelReason;

    public String getEffectiveReason() {
        if (reason != null && !reason.isBlank()) {
            return reason.trim();
        }
        if (cancelReason != null && !cancelReason.isBlank()) {
            return cancelReason.trim();
        }
        return null;
    }
}

