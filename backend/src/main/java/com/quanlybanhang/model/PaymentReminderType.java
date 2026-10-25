package com.quanlybanhang.model;

/**
 * Loại nhắc hạn thanh toán hóa đơn (S7-08).
 * - BEFORE_DUE_3_DAYS: Nhắc trước hạn 3 ngày.
 * - OVERDUE: Nhắc khi hóa đơn đã quá hạn thanh toán.
 */
public enum PaymentReminderType {
    BEFORE_DUE_3_DAYS("Nhắc trước hạn 3 ngày"),
    OVERDUE("Nhắc quá hạn thanh toán");

    private final String displayName;

    PaymentReminderType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
