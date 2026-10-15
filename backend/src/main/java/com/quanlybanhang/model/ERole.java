package com.quanlybanhang.model;

/**
 * Enum định nghĩa 7 vai trò nghiệp vụ trong hệ thống.
 *
 * Phân cấp quyền:
 * - ADMIN: toàn quyền hệ thống, quản lý user, cấu hình
 * - SALES_MANAGER: quản lý kinh doanh — xem được giá vốn, biên lợi nhuận
 * - WAREHOUSE_MANAGER: quản lý kho — phê duyệt nhập/xuất kho
 * - SALES: nhân viên kinh doanh — tạo đơn, quản lý khách hàng
 * - WAREHOUSE_STAFF: thủ kho — thao tác nhập/xuất trên kho được gán
 * - ACCOUNTANT: kế toán — xem báo cáo tài chính, công nợ
 * - VIEWER: chỉ xem — xem báo cáo, không thao tác dữ liệu
 */
public enum ERole {
    ROLE_ADMIN,              // Quản trị hệ thống
    ROLE_SALES_MANAGER,      // Quản lý kinh doanh
    ROLE_WAREHOUSE_MANAGER,  // Quản lý kho
    ROLE_SALES,              // Nhân viên kinh doanh
    ROLE_WAREHOUSE_STAFF,    // Thủ kho
    ROLE_ACCOUNTANT,         // Kế toán
    ROLE_VIEWER,             // Chỉ xem báo cáo
    ROLE_DEALER              // Đại lý (S4-10, S5-01)
}
