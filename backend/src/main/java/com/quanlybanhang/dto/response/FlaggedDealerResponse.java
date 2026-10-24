package com.quanlybanhang.dto.response;

import com.quanlybanhang.model.DebtFlagAction;
import com.quanlybanhang.model.DebtFlagType;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * DTO trả về thông tin đại lý bị gắn cờ rủi ro công nợ (S7-04).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FlaggedDealerResponse {
    private Long dealerId;
    private String dealerCode;
    private String dealerName;
    private BigDecimal currentDebt;
    private BigDecimal debtLimit;
    private BigDecimal excessDebtAmount;
    private Integer maxDebtDays;
    private Integer maxOverdueDays;
    private BigDecimal overdueAmount;
    private DebtFlagType debtFlagType;
    private String debtFlagReason;
    private LocalDateTime debtFlaggedAt;
    private LocalDateTime lastDebtEvaluatedAt;
    private DebtFlagAction configuredAction;
    private DebtFlagAction effectiveAction;
}

