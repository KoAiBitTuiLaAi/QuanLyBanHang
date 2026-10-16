package com.quanlybanhang.service;

/**
 * Service tạo và xuất tài liệu PDF, mã vạch cho Đơn hàng (S4-08).
 * Nhân viên kinh doanh in hoặc xuất PDF đơn hàng đưa đại lý xem lại và ký nhận tại chỗ.
 * Kho sử dụng mã đơn và mã vạch để tra cứu nhanh khi xuất hàng.
 */
public interface OrderPdfService {

    /**
     * Xuất phiếu xác nhận đơn hàng định dạng PDF.
     * Mẫu in gồm thông tin đại lý, điểm giao, chi tiết dòng hàng, chiết khấu, tổng tiền,
     * mã đơn hàng và mã vạch Code 128 để kho tra cứu nhanh.
     *
     * @param orderId  ID đơn hàng cần in/xuất PDF
     * @param username Tên đăng nhập người yêu cầu (kiểm tra phân quyền RLS cho ROLE_SALES)
     * @return Mảng byte chứa nội dung file PDF
     */
    byte[] generateOrderPdf(Long orderId, String username);

    /**
     * Tạo ảnh mã vạch (Barcode Code 128) độc lập theo mã đơn hàng.
     *
     * @param orderId  ID đơn hàng
     * @param username Tên đăng nhập người yêu cầu
     * @return Mảng byte ảnh PNG mã vạch
     */
    byte[] generateOrderBarcode(Long orderId, String username);
}
