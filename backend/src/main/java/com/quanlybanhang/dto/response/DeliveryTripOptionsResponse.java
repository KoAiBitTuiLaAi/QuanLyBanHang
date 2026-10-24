package com.quanlybanhang.dto.response;

import lombok.*;

import java.util.List;

/**
 * Tùy chọn bộ lọc và cấu hình cho Chuyến giao hàng (S6-07).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeliveryTripOptionsResponse {

    private List<NamedResourceResponse> warehouses;
    private List<StatusOption> tripStatuses;
    private List<StatusOption> stopStatuses;
    private boolean canManage;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class StatusOption {
        private String value;
        private String label;
    }
}

