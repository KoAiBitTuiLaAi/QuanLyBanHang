package com.quanlybanhang.dto.request;

import com.quanlybanhang.model.DebtFlagAction;
import jakarta.validation.constraints.NotNull;
import lombok.*;

/**
 * Request cập nhật cấu hình kiểm soát rủi ro công nợ (S7-04).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DebtRiskConfigRequest {

    @NotNull(message = "Hành vi mặc định không được để trống")
    private DebtFlagAction defaultAction;

    private DebtFlagAction overdueAction;

    private DebtFlagAction limitExceededAction;

    private Boolean autoFlagEnabled;
}

