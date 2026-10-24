package com.quanlybanhang.dto.response;

import com.quanlybanhang.model.DebtFlagAction;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Response cấu hình kiểm soát rủi ro công nợ (S7-04).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DebtRiskConfigResponse {
    private Long id;
    private DebtFlagAction defaultAction;
    private DebtFlagAction overdueAction;
    private DebtFlagAction limitExceededAction;
    private Boolean autoFlagEnabled;
    private LocalDateTime updatedAt;
    private String updatedBy;
}

