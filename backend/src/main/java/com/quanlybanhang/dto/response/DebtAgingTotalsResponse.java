package com.quanlybanhang.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Tổng hợp toàn bộ báo cáo công nợ quá hạn theo tuổi nợ (S8-09).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DebtAgingTotalsResponse {
    private String overdueAmount;
    private Integer dealerCount;
    private Integer maxOverdueDays;
}

