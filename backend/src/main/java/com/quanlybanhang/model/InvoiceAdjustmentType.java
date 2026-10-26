package com.quanlybanhang.model;

/**
 * Loại chứng từ điều chỉnh hóa đơn (S7-01).
 *
 * Theo quy định hóa đơn:
 * - INCREASE: Điều chỉnh tăng tiền / số lượng
 * - DECREASE: Điều chỉnh giảm tiền / số lượng / chiết khấu
 * - INFORMATION: Điều chỉnh thông tin (tên, MST, địa chỉ...) không đổi tiền
 * - CANCEL: Hủy hóa đơn đã phát hành
 */
public enum InvoiceAdjustmentType {
    INCREASE("Điều chỉnh tăng"),
    DECREASE("Điều chỉnh giảm"),
    INFORMATION("Điều chỉnh thông tin"),
    CANCEL("Hủy hóa đơn");

    private final String displayName;

    InvoiceAdjustmentType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}

