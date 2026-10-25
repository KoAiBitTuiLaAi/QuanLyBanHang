package com.quanlybanhang.service;

import com.quanlybanhang.dto.response.DealerDebtAgingLedgerResponse;

import java.time.LocalDate;

/**
 * Service Sổ công nợ theo đại lý với phân tích tuổi nợ (S7-03):
 * - Xem các cột: phải thu, đã thu, còn nợ, chia theo 4 nhóm tuổi nợ (0–30, 31–60, 61–90 và trên 90 ngày)
 * - Lọc riêng nhóm quá hạn (overdueOnly)
 * - Hiển thị tổng cộng ở chân bảng (summary)
 * - Sắp xếp tập trung đòi nợ khoản lâu nhất trước
 * - Xuất Excel (.xlsx)
 */
public interface DealerDebtAgingLedgerService {

    /**
     * Lấy dữ liệu sổ công nợ theo đại lý với phân tích tuổi nợ.
     *
     * @param asOfDate    Ngày chốt công nợ (mặc định hôm nay theo giờ Việt Nam)
     * @param overdueOnly Nếu true, chỉ lấy các đại lý có nợ quá hạn
     * @param dealerId    Lọc theo đại lý cụ thể (nếu có)
     * @param search      Tìm kiếm theo mã, tên hoặc SĐT đại lý
     * @return Bảng sổ công nợ kèm tổng cộng ở chân bảng
     */
    DealerDebtAgingLedgerResponse getLedger(LocalDate asOfDate, Boolean overdueOnly, Long dealerId, String search);

    /**
     * Xuất sổ công nợ theo đại lý với phân tích tuổi nợ ra file Excel (.xlsx).
     */
    byte[] exportExcel(LocalDate asOfDate, Boolean overdueOnly, Long dealerId, String search);

    /**
     * Tạo tên file xuất Excel chuẩn mực.
     */
    String buildExportFilename(DealerDebtAgingLedgerResponse ledger);
}

