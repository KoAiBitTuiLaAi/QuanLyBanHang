package com.quanlybanhang.service;

import com.quanlybanhang.dto.response.DebtAgingOptionsResponse;
import com.quanlybanhang.dto.response.DebtAgingReportResponse;

import java.time.LocalDate;

/**
 * Service Báo cáo công nợ quá hạn theo tuổi nợ (S8-09).
 */
public interface DebtAgingReportService {

    /**
     * Lấy tùy chọn bộ lọc báo cáo tuổi nợ (ngày chốt tối đa, múi giờ, định nghĩa nhóm tuổi nợ, danh sách nhân viên).
     */
    DebtAgingOptionsResponse getOptions();

    /**
     * Xem báo cáo tổng hợp công nợ quá hạn theo tuổi nợ.
     *
     * @param asOfDate       Ngày chốt báo cáo (mặc định hôm nay).
     * @param employeeId     Lọc theo ID nhân viên phụ trách (null nếu xem tất cả).
     * @param unassignedOnly Chỉ lọc các đại lý chưa phân công nhân viên.
     * @return Dữ liệu báo cáo tuổi nợ hoàn chỉnh.
     */
    DebtAgingReportResponse getReport(LocalDate asOfDate, Long employeeId, Boolean unassignedOnly);

    /**
     * Xuất báo cáo công nợ quá hạn theo tuổi nợ ra file Excel (.xlsx).
     */
    byte[] exportExcel(LocalDate asOfDate, Long employeeId, Boolean unassignedOnly);

    /**
     * Tạo tên file Excel đính kèm.
     */
    String buildExportFilename(DebtAgingReportResponse report);
}

