package com.quanlybanhang.scheduler;

import com.quanlybanhang.dto.response.DebtEvaluationSummaryResponse;
import com.quanlybanhang.service.DealerDebtService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DebtAssessmentScheduler - Tác vụ đánh giá lại công nợ hàng ngày (S7-04)")
class DebtAssessmentSchedulerTest {

    @Mock
    private DealerDebtService dealerDebtService;

    @InjectMocks
    private DebtAssessmentScheduler scheduler;

    @Test
    @DisplayName("Chạy tác vụ đánh giá hàng ngày thành công và gọi service")
    void runDailyDebtEvaluation_success() {
        DebtEvaluationSummaryResponse summary = DebtEvaluationSummaryResponse.builder()
                .evaluatedAt(LocalDateTime.now())
                .totalEvaluated(10)
                .totalFlagged(2)
                .newlyFlagged(1)
                .unflagged(0)
                .flaggedDealers(List.of())
                .build();

        when(dealerDebtService.evaluateAllDealersDebt()).thenReturn(summary);

        scheduler.runDailyDebtEvaluation();

        verify(dealerDebtService).evaluateAllDealersDebt();
    }

    @Test
    @DisplayName("Xử lý lỗi an toàn khi tác vụ gặp ngoại lệ, không để sập scheduler")
    void runDailyDebtEvaluation_handlesException() {
        doThrow(new RuntimeException("Database error")).when(dealerDebtService).evaluateAllDealersDebt();

        // Không ném ngoại lệ ra ngoài
        scheduler.runDailyDebtEvaluation();

        verify(dealerDebtService).evaluateAllDealersDebt();
    }
}

