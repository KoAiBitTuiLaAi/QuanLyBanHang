package com.quanlybanhang.model;

/**
 * Trang thai phieu tra hang (S8-02).
 */
public enum ReturnOrderStatus {
    DRAFT("Nhap"),
    CONFIRMED("Da xac nhan"),
    CANCELLED("Da huy");

    private final String displayName;

    ReturnOrderStatus(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}