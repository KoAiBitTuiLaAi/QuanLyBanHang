package com.quanlybanhang.model;

/**
 * Enum định nghĩa tất cả quyền (permission) trong hệ thống.
 *
 * Nhóm theo module nghiệp vụ:
 * - USER_*: quản lý tài khoản
 * - PRODUCT_*: quản lý sản phẩm
 * - ORDER_*: quản lý đơn hàng
 * - WAREHOUSE_*: quản lý kho, nhập/xuất
 * - CUSTOMER_*: quản lý khách hàng
 * - FINANCE_*: tài chính, công nợ, giá vốn
 * - REPORT_*: xem báo cáo
 */
public enum Permission {

    // ── Quản lý tài khoản ──
    USER_VIEW("Xem tài khoản"),
    USER_CREATE("Tạo tài khoản"),
    USER_EDIT("Sửa tài khoản"),
    USER_LOCK("Khóa tài khoản"),
    USER_ASSIGN_ROLE("Gán vai trò"),

    // ── Sản phẩm ──
    PRODUCT_VIEW("Xem sản phẩm"),
    PRODUCT_CREATE("Thêm sản phẩm"),
    PRODUCT_EDIT("Sửa sản phẩm"),
    PRODUCT_DELETE("Xóa sản phẩm"),

    // ── Đơn hàng ──
    ORDER_VIEW("Xem đơn hàng"),
    ORDER_CREATE("Tạo đơn hàng"),
    ORDER_EDIT("Sửa đơn hàng"),
    ORDER_CANCEL("Hủy đơn hàng"),
    ORDER_APPROVE("Duyệt đơn hàng"),

    // ── Kho & tồn kho ──
    WAREHOUSE_VIEW("Xem tồn kho"),
    WAREHOUSE_IMPORT("Nhập kho"),
    WAREHOUSE_EXPORT("Xuất kho"),
    WAREHOUSE_ADJUST("Điều chỉnh tồn kho"),
    WAREHOUSE_APPROVE("Duyệt phiếu kho"),
    WAREHOUSE_TRANSFER("Chuyển kho nội bộ"),

    // ── Khách hàng / Đại lý ──
    CUSTOMER_VIEW("Xem khách hàng"),
    CUSTOMER_CREATE("Thêm khách hàng"),
    CUSTOMER_EDIT("Sửa khách hàng"),

    // ── Tài chính (nhạy cảm) ──
    FINANCE_VIEW_COST_PRICE("Xem giá vốn"),
    FINANCE_VIEW_PROFIT_MARGIN("Xem biên lợi nhuận"),
    FINANCE_VIEW_DEBT("Xem công nợ"),
    FINANCE_MANAGE("Quản lý công nợ"),

    // ── Báo cáo ──
    REPORT_SALES("Xem báo cáo bán hàng"),
    REPORT_INVENTORY("Xem báo cáo tồn kho"),
    REPORT_FINANCE("Xem báo cáo tài chính");

    private final String displayName;

    Permission(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
