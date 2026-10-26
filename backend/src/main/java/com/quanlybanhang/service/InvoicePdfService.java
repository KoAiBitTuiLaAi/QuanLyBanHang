package com.quanlybanhang.service;

/**
 * Service in và xuất PDF hóa đơn bán hàng và chứng từ điều chỉnh (S7-01).
 */
public interface InvoicePdfService {

    /**
     * Xuất file PDF hóa đơn bán hàng.
     *
     * @param invoiceId ID hóa đơn
     * @param username  người thực hiện
     * @return mảng byte file PDF
     */
    byte[] generateInvoicePdf(Long invoiceId, String username);

    /**
     * Xuất file PDF chứng từ điều chỉnh hóa đơn.
     *
     * @param adjustmentId ID chứng từ điều chỉnh
     * @param username     người thực hiện
     * @return mảng byte file PDF
     */
    byte[] generateAdjustmentPdf(Long adjustmentId, String username);

    /**
     * Tạo HTML xem trước và in trực tiếp trên trình duyệt (window.print).
     *
     * @param invoiceId ID hóa đơn
     * @param username  người thực hiện
     * @return chuỗi HTML mẫu in
     */
    String generateInvoicePrintHtml(Long invoiceId, String username);
}

