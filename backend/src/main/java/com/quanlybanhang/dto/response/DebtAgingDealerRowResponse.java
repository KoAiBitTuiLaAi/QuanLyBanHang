package com.quanlybanhang.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Chi tiết công nợ quá hạn và xếp hạng rủi ro của từng đại lý (S8-09).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DebtAgingDealerRowResponse {
    private Long dealerId;
    private String dealerCode;
    private String dealerName;
    private Long employeeId;
    private String employeeName;
    private String overdueAmount;
    private Integer maxOverdueDays;
    private Integer riskRank;
    private String riskLabel;
    private Map<String, String> buckets;

    @JsonIgnore
    private BigDecimal overdueAmountRaw;
}

