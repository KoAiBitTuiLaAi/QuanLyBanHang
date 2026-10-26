package com.quanlybanhang.model;

/**
 * Trạng thái hóa đơn bán hàng (S7-01).
 *
 * ISSUED: Đã phát hành chính thức (bất biến, không sửa, không xóa).
 * ADJUSTED: Đã có chứng từ điều chỉnh (tăng/giảm/thông tin).
 * CANCELLED: Đã hủy thông qua chứng từ điều chỉnh hủy hóa đơn.
 */
public enum InvoiceStatus {
    ISSUED("Đã phát hành"),
    ADJUSTED("Đã điều chỉnh"),
    CANCELLED("Đã hủy");

    private final String displayName;

    InvoiceStatus(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}

