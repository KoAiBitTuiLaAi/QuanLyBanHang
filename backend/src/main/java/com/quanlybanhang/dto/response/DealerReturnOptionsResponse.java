package com.quanlybanhang.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Bộ tùy chọn cho chức năng lập phiếu trả hàng từ đại lý và nhập lại kho (S8-01).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DealerReturnOptionsResponse {

    private List<NamedResourceResponse> warehouses;
    private List<NamedResourceResponse> defectiveWarehouses;
    private List<ReturnReasonOption> returnReasons;
    private List<StatusOption> statuses;
    private Boolean canCreate;
    private Boolean canConfirm;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReturnReasonOption {
        private String code;
        private String label;
        private Boolean defectiveDefault;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StatusOption {
        private String code;
        private String label;
    }
}

