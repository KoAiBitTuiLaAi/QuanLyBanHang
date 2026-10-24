package com.quanlybanhang.dto.response;

import lombok.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * Tiến độ doanh số theo thời gian thực của chính nhân viên đang đăng nhập (S8-04).
 * Giúp nhân viên biết mình đang ở đâu so với mục tiêu ngay giữa tháng.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MySalesTargetProgressResponse {

    private Integer year;
    private Integer month;
    private String period;
    private Boolean pastPeriod;
    private Boolean currentPeriod;

    private Long userId;
    private String username;
    private String fullName;

    private Integer totalDaysInMonth;
    private Integer elapsedDays;
    private Integer remainingDays;
    private BigDecimal timeElapsedPercent;

    /**
     * Tổng mục tiêu doanh số của nhân viên trong tháng:
     * Ưu tiên chỉ tiêu tổng cá nhân (EMPLOYEE); nếu chỉ đặt theo từng khu vực (EMPLOYEE_TERRITORY) thì lấy tổng các khu vực.
     */
    private BigDecimal totalTargetAmount;

    /**
     * Doanh số thực tế nhân viên đã đạt trong tháng theo thời gian thực.
     */
    private BigDecimal actualAmount;
    private BigDecimal achievedAmount;
    private BigDecimal remainingAmount;
    private BigDecimal overachievedAmount;
    private BigDecimal completionRate;
    private BigDecimal progressPercent;

    /**
     * Mức doanh số cần đạt tính đến ngày hiện tại trong tháng (pro-rata).
     */
    private BigDecimal expectedAmountToDate;

    /**
     * Chênh lệch thực tế so với mức chuẩn giữa tháng (actualAmount - expectedAmountToDate).
     */
    private BigDecimal pacingVarianceAmount;

    /**
     * Doanh số bình quân mỗi ngày cần đạt trong những ngày còn lại của tháng.
     */
    private BigDecimal requiredDailyRunRate;

    private Integer orderCount;
    private String progressStatus;
    private String progressStatusLabel;

    /**
     * Danh sách các chỉ tiêu gắn trực tiếp với nhân viên này (EMPLOYEE và EMPLOYEE_TERRITORY).
     */
    private List<SalesTargetResponse> personalTargets;

    /**
     * Danh sách chỉ tiêu chung của các khu vực (TERRITORY) mà nhân viên được phân công.
     */
    private List<SalesTargetResponse> territoryTargets;

    /**
     * Bóc tách tiến độ doanh số của nhân viên theo từng khu vực trong kỳ.
     */
    private List<TerritoryBreakdownItem> territoryBreakdown;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class TerritoryBreakdownItem {
        private Long territoryId;
        private String territoryCode;
        private String territoryName;
        private BigDecimal employeeTargetAmount;
        private BigDecimal territoryTargetAmount;
        private BigDecimal employeeActualAmount;
        private BigDecimal territoryActualAmount;
        private BigDecimal employeeCompletionRate;
        private Integer employeeOrderCount;
    }
}

