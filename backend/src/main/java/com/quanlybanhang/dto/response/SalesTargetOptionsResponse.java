package com.quanlybanhang.dto.response;

import lombok.*;

import java.util.List;

/**
 * Dữ liệu tùy chọn phục vụ đặt và lọc chỉ tiêu doanh số (S8-04).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SalesTargetOptionsResponse {

    private Integer currentYear;
    private Integer currentMonth;
    private String currentPeriod;

    private boolean canView;
    private boolean canManage;

    private List<EmployeeOption> employees;
    private List<TerritoryOption> territories;
    private List<OptionItem> scopeTypes;
    private List<OptionItem> progressStatuses;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class EmployeeOption {
        private Long id;
        private String username;
        private String fullName;
        private String email;
        private List<Long> territoryIds;
        private List<String> territoryNames;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class TerritoryOption {
        private Long id;
        private String code;
        private String name;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class OptionItem {
        private String code;
        private String label;
    }
}

