package com.quanlybanhang.service;

import com.quanlybanhang.dto.request.SalesReportFilterRequest;
import com.quanlybanhang.dto.response.SalesReportOptionsResponse;
import com.quanlybanhang.dto.response.SalesReportPageResponse;

/**
 * Service Báo cáo doanh số đa chiều (S8-06).
 */
public interface SalesReportService {

    /**
     * Lấy các tùy chọn bộ lọc (khu vực, đại lý, nhóm hàng, nhân viên).
     */
    SalesReportOptionsResponse getOptions();

    /**
     * Lấy dữ liệu báo cáo doanh số đa chiều theo bộ lọc, chiều nhóm và phân trang.
     */
    SalesReportPageResponse getReport(SalesReportFilterRequest filter);

    /**
     * Xuất báo cáo doanh số đa chiều ra định dạng Excel (.xlsx).
     */
    byte[] exportExcel(SalesReportFilterRequest filter);

    /**
     * Xây dựng tên file tải về cho file Excel.
     */
    String buildExportFilename(SalesReportFilterRequest filter);
}
