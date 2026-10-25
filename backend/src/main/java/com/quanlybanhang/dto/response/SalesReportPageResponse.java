package com.quanlybanhang.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Kết quả phân trang và KPI báo cáo doanh số đa chiều (S8-06).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SalesReportPageResponse {

    @Builder.Default
    private List<SalesReportRowResponse> content = new ArrayList<>();

    @Builder.Default
    private int page = 0;

    @Builder.Default
    private int size = 20;

    @Builder.Default
    private long totalElements = 0L;

    @Builder.Default
    private int totalPages = 0;

    @Builder.Default
    private boolean last = true;

    private SalesReportSummaryResponse summary;
}
