package com.quanlybanhang.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Chi tiết dòng sổ công nợ theo đại lý kèm phân tích tuổi nợ (S7-03).
 *
 * Các cột nghiệp vụ:
 * - Phải thu (totalReceivable)
 * - Đã thu (totalPaid)
 * - Còn nợ (remainingDebt)
 * - Phân tích tuổi nợ theo 4 nhóm: 0–30, 31–60, 61–90 và trên 90 ngày
 * - Nợ quá hạn (overdueDebt) và nợ trong hạn (inTermDebt)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DealerDebtAgingRowResponse {

    private Long dealerId;
    private String dealerCode;
    private String dealerName;
    private String phone;
    private String address;

    private Long assignedUserId;
    private String assignedUserName;

    private BigDecimal debtLimit;
    private Integer maxDebtDays;

    // Các cột tài chính cốt lõi
    private BigDecimal totalReceivable;
    private BigDecimal totalPaid;
    private BigDecimal remainingDebt;
    private BigDecimal inTermDebt;
    private BigDecimal overdueDebt;

    // Tuổi nợ & Quá hạn
    private Integer maxDebtAgeDays;
    private Integer maxOverdueDays;
    private Boolean isOverdue;

    // 4 nhóm tuổi nợ chuẩn theo S7-03
    private BigDecimal bucket0To30;
    private BigDecimal bucket31To60;
    private BigDecimal bucket61To90;
    private BigDecimal bucketOver90;

    // Chuỗi số định dạng 2 chữ số thập phân (bảo toàn độ chính xác cho frontend / kiểm toán)
    private String totalReceivableFormatted;
    private String totalPaidFormatted;
    private String remainingDebtFormatted;
    private String inTermDebtFormatted;
    private String overdueDebtFormatted;

    private String bucket0To30Formatted;
    private String bucket31To60Formatted;
    private String bucket61To90Formatted;
    private String bucketOver90Formatted;
}

