package com.quanlybanhang.model;

/**
 * Loại phạm vi đặt chỉ tiêu doanh số tháng (S8-04):
 * - EMPLOYEE: Đặt chỉ tiêu tổng theo tháng cho từng nhân viên kinh doanh.
 * - TERRITORY: Đặt chỉ tiêu tổng theo tháng cho từng khu vực / địa bàn.
 * - EMPLOYEE_TERRITORY: Đặt chỉ tiêu theo tháng cho nhân viên tại một khu vực cụ thể.
 */
public enum SalesTargetScopeType {
    EMPLOYEE("Theo nhân viên"),
    TERRITORY("Theo khu vực"),
    EMPLOYEE_TERRITORY("Theo nhân viên & khu vực");

    private final String displayName;

    SalesTargetScopeType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}

