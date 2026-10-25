package com.quanlybanhang.service;

import com.quanlybanhang.dto.response.DeliveryTripManifestResponse;

/**
 * Service in ấn và xuất PDF danh sách điểm giao của chuyến hàng (S6-06).
 */
public interface DeliveryTripPdfService {

    /**
     * Lấy dữ liệu bảng kê điểm giao của chuyến giao hàng (S6-06).
     */
    DeliveryTripManifestResponse getManifest(Long tripId, String username);

    /**
     * Xuất file PDF danh sách điểm giao hàng của chuyến kèm địa chỉ và số điện thoại người nhận (S6-06).
     */
    byte[] generateManifestPdf(Long tripId, String username);

    /**
     * Tạo nội dung HTML xem trước và in trực tiếp danh sách điểm giao hàng (S6-06).
     */
    String generateManifestPrintHtml(Long tripId, String username);
}

