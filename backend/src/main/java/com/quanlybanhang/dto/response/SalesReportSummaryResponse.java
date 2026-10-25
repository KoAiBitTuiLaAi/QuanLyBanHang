package com.quanlybanhang.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Tổng hợp số liệu KPI đầu bảng của báo cáo doanh số đa chiều (S8-06).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SalesReportSummaryResponse {

    /**
     * Tổng doanh số của tập kết quả theo bộ lọc (VNĐ).
     */
    @Builder.Default
    private BigDecimal totalRevenue = BigDecimal.ZERO;

    /**
     * Tổng sản lượng của tập kết quả theo bộ lọc.
     */
    @Builder.Default
    private BigDecimal totalQuantity = BigDecimal.ZERO;

    /**
     * Biên lợi nhuận gộp trung bình toàn tập kết quả (%).
     * Chỉ trả về khi người dùng có quyền xem biên lợi nhuận (FINANCE_VIEW_PROFIT_MARGIN hoặc ROLE_ADMIN).
     * Trả về null khi không có quyền xem margin.
     */
    private BigDecimal averageGrossMargin;
}
