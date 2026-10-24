package com.quanlybanhang.model;

/**
 * Trạng thái phiếu trả hàng từ đại lý (S8-01).
 *
 * - PENDING: Phiếu trả do Nhân viên kinh doanh lập, đang chờ Quản lý kho xác nhận nhận hàng (CHƯA cộng tồn kho).
 * - CONFIRMED: Quản lý kho đã xác nhận nhận hàng, đã cộng tồn kho (hàng tốt vào kho thường đúng lô, hàng hỏng vào kho hàng lỗi).
 * - CANCELLED: Phiếu trả đã hủy khi chưa xác nhận nhập kho.
 */
public enum DealerReturnStatus {
    PENDING("Chờ Quản lý kho xác nhận"),
    CONFIRMED("Đã xác nhận nhập kho"),
    CANCELLED("Đã hủy");

    private final String displayName;

    DealerReturnStatus(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}

