package com.quanlybanhang.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Tổng hợp công nợ quá hạn theo từng nhóm tuổi nợ (S8-09).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DebtAgingBucketTotalResponse {
    private String bucketKey;
    private String amount;
    private Integer dealerCount;
}

