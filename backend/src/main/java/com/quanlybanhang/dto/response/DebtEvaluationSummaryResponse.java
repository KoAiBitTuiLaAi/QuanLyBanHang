package com.quanlybanhang.dto.response;

import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Tóm tắt kết quả chạy tác vụ đánh giá lại công nợ (S7-04).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DebtEvaluationSummaryResponse {
    private LocalDateTime evaluatedAt;
    private int totalEvaluated;
    private int totalFlagged;
    private int newlyFlagged;
    private int unflagged;
    private int limitExceededCount;
    private int overdueCount;
    private int bothCount;
    private List<FlaggedDealerResponse> flaggedDealers;
}

