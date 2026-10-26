package com.quanlybanhang.scheduler;

import com.quanlybanhang.dto.response.DebtEvaluationSummaryResponse;
import com.quanlybanhang.service.DealerDebtService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Tác vụ định kỳ đánh giá lại công nợ đại lý mỗi ngày một lần (S7-04).
 * Tự động gắn cờ đại lý vượt hạn mức hoặc quá hạn, gỡ cờ khi đại lý đã giải quyết nợ.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.debt.assessment-scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class DebtAssessmentScheduler {

    private final DealerDebtService dealerDebtService;

    /**
     * Chạy mỗi ngày một lần (mặc định vào 01:00 sáng).
     * Cron expression: "0 0 1 * * ?"
     */
    @Scheduled(cron = "${app.debt.evaluation.cron:0 0 1 * * ?}")
    public void runDailyDebtEvaluation() {
        log.info("DebtAssessmentScheduler: Bắt đầu tác vụ đánh giá lại công nợ hàng ngày...");
        try {
            DebtEvaluationSummaryResponse summary = dealerDebtService.evaluateAllDealersDebt();
            log.info("DebtAssessmentScheduler: Hoàn tất đánh giá công nợ. Tổng đại lý: {}, Bị gắn cờ: {} (Mới: {}), Gỡ cờ: {}",
                    summary.getTotalEvaluated(),
                    summary.getTotalFlagged(),
                    summary.getNewlyFlagged(),
                    summary.getUnflagged());
        } catch (Exception e) {
            log.error("DebtAssessmentScheduler: Lỗi khi thực hiện tác vụ đánh giá công nợ hàng ngày", e);
        }
    }
}

