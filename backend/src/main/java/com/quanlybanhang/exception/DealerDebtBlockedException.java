package com.quanlybanhang.exception;

import lombok.Getter;

/**
 * Ngoại lệ ném ra khi đại lý bị gắn cờ cảnh báo công nợ và hệ thống chặn tạo đơn mới theo cấu hình (S7-04).
 */
@Getter
public class DealerDebtBlockedException extends RuntimeException {

    private final String dealerCode;
    private final String dealerName;
    private final String reason;

    public DealerDebtBlockedException(String dealerCode, String dealerName, String reason) {
        super(String.format("Đại lý '%s' (%s) đang bị gắn cờ cảnh báo rủi ro công nợ: %s. Hệ thống tự động chặn tạo đơn mới theo cấu hình.",
                dealerName, dealerCode, reason != null ? reason : "Vượt hạn mức hoặc nợ quá hạn"));
        this.dealerCode = dealerCode;
        this.dealerName = dealerName;
        this.reason = reason;
    }
}

