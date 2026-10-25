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
    private List<NamedResourceResponse> territories;
    private List<StatusOption> tripStatuses;
    private List<StatusOption> stopStatuses;
    private boolean canManage;
    private List<StatusOption> failureReasons;
    private long proofMaxBytes;
    private long proofMaxPixels;
    private List<String> proofContentTypes;

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

