package com.quanlybanhang.dto.response;

import lombok.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * Báo cáo tổng hợp chỉ tiêu và tiến độ doanh số theo tháng cho Quản lý kinh doanh (S8-04).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SalesTargetSummaryResponse {

    private Integer year;
    private Integer month;
    private String period;
    private Boolean pastPeriod;
    private Boolean currentPeriod;

    private Integer totalDaysInMonth;
    private Integer elapsedDays;
    private Integer remainingDays;
    private BigDecimal timeElapsedPercent;

    private Integer totalTargets;
    private Integer employeeTargetCount;
    private Integer territoryTargetCount;
    private Integer employeeTerritoryTargetCount;

    private Integer achievedCount;
    private Integer onTrackCount;
    private Integer behindCount;
    private Integer lockedCount;

    private BigDecimal totalTargetAmount;
    private BigDecimal totalActualAmount;
    private BigDecimal totalRemainingAmount;
    private BigDecimal overallCompletionRate;
    private BigDecimal expectedAmountToDate;
    private BigDecimal pacingVarianceAmount;

    private List<SalesTargetResponse> targets;
    private List<EmployeeProgressSummary> employeeProgress;
    private List<TerritoryProgressSummary> territoryProgress;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class EmployeeProgressSummary {
        private Long userId;
        private String username;
        private String fullName;
        private BigDecimal targetAmount;
        private BigDecimal actualAmount;
        private BigDecimal remainingAmount;
        private BigDecimal completionRate;
        private BigDecimal expectedAmountToDate;
        private BigDecimal pacingVarianceAmount;
        private BigDecimal requiredDailyRunRate;
        private Integer orderCount;
        private String progressStatus;
        private String progressStatusLabel;
        private Boolean locked;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class TerritoryProgressSummary {
        private Long territoryId;
        private String territoryCode;
        private String territoryName;
        private BigDecimal targetAmount;
        private BigDecimal actualAmount;
        private BigDecimal remainingAmount;
        private BigDecimal completionRate;
        private BigDecimal expectedAmountToDate;
        private BigDecimal pacingVarianceAmount;
        private BigDecimal requiredDailyRunRate;
        private Integer orderCount;
        private String progressStatus;
        private String progressStatusLabel;
        private Boolean locked;
    }
}

