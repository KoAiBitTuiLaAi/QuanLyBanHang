package com.quanlybanhang.dto.response;

import lombok.*;

/**
 * Response thông tin ngưỡng cảnh báo hạn sử dụng của SKU (S6-01).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LotThresholdResponse {
    private Long productId;
    private String productCode;
    private String productName;
    private String unit;
    private Boolean manageLot;
    private Integer expiryWarningDays;
    private Integer effectiveThresholdDays;
}

