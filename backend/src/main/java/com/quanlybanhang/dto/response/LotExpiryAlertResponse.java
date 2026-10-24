package com.quanlybanhang.dto.response;

import lombok.*;

import java.util.List;

/**
 * Response cảnh báo lô sắp hết hạn / quá hạn (S6-01).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LotExpiryAlertResponse {
    private int totalAlerts;
    private int expiredCount;
    private int expiringSoonCount;
    private Integer defaultThresholdDays;
    private List<WarehouseLotInventoryResponse> items;
}

