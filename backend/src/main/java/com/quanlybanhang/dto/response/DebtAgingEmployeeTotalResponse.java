package com.quanlybanhang.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Tổng hợp công nợ quá hạn theo nhân viên phụ trách (S8-09).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DebtAgingEmployeeTotalResponse {
    private Long employeeId;
    private String employeeName;
    private Integer dealerCount;
    private String overdueAmount;
    private Map<String, String> buckets;
}

