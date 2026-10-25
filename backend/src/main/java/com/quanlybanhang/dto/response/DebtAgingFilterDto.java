package com.quanlybanhang.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Thông tin bộ lọc đã áp dụng cho báo cáo tuổi nợ (S8-09).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DebtAgingFilterDto {
    private String asOfDate;
    private Long employeeId;
    private Boolean unassignedOnly;
}

