package com.quanlybanhang.model;

/**
 * Trang thai phieu tra hang (S8-02).
 */
public enum ReturnOrderStatus {
    DRAFT("Nháp"),
    CONFIRMED("Đã xác nhận"),
    CANCELLED("Đã hủy");

    private final String displayName;

    ReturnOrderStatus(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
