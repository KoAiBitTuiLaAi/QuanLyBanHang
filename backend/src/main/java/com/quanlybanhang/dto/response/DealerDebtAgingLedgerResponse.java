package com.quanlybanhang.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Toàn bộ dữ liệu Sổ công nợ theo đại lý với phân tích tuổi nợ (S7-03).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DealerDebtAgingLedgerResponse {

    private String asOfDate;
    private String generatedAt;
    private String timezone;
    private String currency;

    // Bộ lọc áp dụng
    private Boolean overdueOnly;
    private Long dealerId;
    private String search;

    // Danh sách dòng đại lý (đã sắp xếp nợ lâu nhất trước)
    private List<DealerDebtAgingRowResponse> dealers;

    // Tổng cộng chân bảng
    private DealerDebtAgingSummaryResponse summary;
}

