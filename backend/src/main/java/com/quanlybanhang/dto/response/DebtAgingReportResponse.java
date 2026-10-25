package com.quanlybanhang.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Báo cáo tổng hợp công nợ quá hạn theo tuổi nợ (S8-09).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DebtAgingReportResponse {
    private String asOfDate;
    private String generatedAt;
    private String timezone;
    private String currency;
    private Boolean complete;
    private DebtAgingFilterDto filters;
    private List<DebtAgingBucketResponse> buckets;
    private String riskBasis;
    private DebtAgingTotalsResponse totals;
    private List<DebtAgingBucketTotalResponse> bucketTotals;
    private List<DebtAgingEmployeeTotalResponse> employeeTotals;
    private List<DebtAgingDealerRowResponse> dealers;
}

