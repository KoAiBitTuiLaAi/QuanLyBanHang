package com.quanlybanhang.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Tổng cộng ở chân bảng Sổ công nợ theo đại lý với phân tích tuổi nợ (S7-03).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DealerDebtAgingSummaryResponse {

    private BigDecimal totalReceivable;
    private BigDecimal totalPaid;
    private BigDecimal totalRemainingDebt;
    private BigDecimal totalInTermDebt;
    private BigDecimal totalOverdueDebt;

    // Tổng 4 nhóm tuổi nợ
    private BigDecimal totalBucket0To30;
    private BigDecimal totalBucket31To60;
    private BigDecimal totalBucket61To90;
    private BigDecimal totalBucketOver90;

    // Số lượng
    private Integer dealerCount;
    private Integer overdueDealerCount;

    // Chuỗi số định dạng 2 chữ số thập phân
    private String totalReceivableFormatted;
    private String totalPaidFormatted;
    private String totalRemainingDebtFormatted;
    private String totalInTermDebtFormatted;
    private String totalOverdueDebtFormatted;

    private String totalBucket0To30Formatted;
    private String totalBucket31To60Formatted;
    private String totalBucket61To90Formatted;
    private String totalBucketOver90Formatted;
}

