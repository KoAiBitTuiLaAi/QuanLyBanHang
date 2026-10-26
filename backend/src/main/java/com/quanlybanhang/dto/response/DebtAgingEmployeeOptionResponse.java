package com.quanlybanhang.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Tùy chọn nhân viên phụ trách cho bộ lọc báo cáo tuổi nợ (S8-09).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DebtAgingEmployeeOptionResponse {
    private Long id;
    private String code;
    private String name;
}

