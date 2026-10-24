package com.quanlybanhang.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Bộ chọn và cấu hình mặc định cho Báo cáo tồn kho và vòng quay hàng (S8-07).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InventoryTurnoverOptionsResponse {

    private List<NamedResourceResponse> warehouses;
    private List<CategoryOption> categories;
    private List<Integer> periodDaysOptions;
    private int defaultPeriodDays;
    private List<Integer> slowMovingThresholdDaysOptions;
    private int defaultSlowMovingThresholdDays;
    private List<TurnoverStatusOption> turnoverStatuses;
    private boolean canViewReport;
    private boolean canExportExcel;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CategoryOption {
        private Long id;
        private String code;
        private String name;
        private Integer level;
        private Long parentId;
        private String parentName;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TurnoverStatusOption {
        private String code;
        private String label;
        private String description;
    }
}

