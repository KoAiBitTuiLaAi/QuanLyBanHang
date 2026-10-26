package com.quanlybanhang.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Tùy chọn và cấu hình bộ lọc Báo cáo tuổi nợ (S8-09).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DebtAgingOptionsResponse {
    private String defaultAsOfDate;
    private String timezone;
    private List<DebtAgingBucketResponse> buckets;
    private List<DebtAgingEmployeeOptionResponse> employees;
}

