package com.quanlybanhang.model;

/**
 * Loại thông báo trong ứng dụng (S7-08).
 */
public enum NotificationType {
    PAYMENT_DUE_BEFORE_3_DAYS("Nhắc hạn thanh toán trước 3 ngày"),
    PAYMENT_OVERDUE("Nhắc quá hạn thanh toán"),
    SYSTEM("Thông báo hệ thống");

    private final String displayName;

    NotificationType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
