package com.quanlybanhang.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Tùy chọn bộ lọc báo cáo doanh số đa chiều (S8-06).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SalesReportOptionsResponse {

    @Builder.Default
    private List<NamedResourceResponse> regions = new ArrayList<>();

    @Builder.Default
    private List<NamedResourceResponse> dealers = new ArrayList<>();

    @Builder.Default
    private List<NamedResourceResponse> categories = new ArrayList<>();

    @Builder.Default
    private List<NamedResourceResponse> employees = new ArrayList<>();
}
