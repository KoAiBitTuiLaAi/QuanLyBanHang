package com.quanlybanhang.scheduler;

import com.quanlybanhang.service.SalesTargetService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduler tự động khoá các chỉ tiêu doanh số đã qua kỳ tháng (S8-04).
 */
@Component
@RequiredArgsConstructor
public class SalesTargetLockScheduler {

    private static final Logger logger = LoggerFactory.getLogger(SalesTargetLockScheduler.class);

    private final SalesTargetService salesTargetService;

    /**
     * Chạy định kỳ lúc 00:05 mỗi ngày để khoá các chỉ tiêu của các tháng đã qua kỳ.
     */
    @Scheduled(cron = "${app.sales-target.lock-cron:0 5 0 * * *}")
    public void lockPastPeriodTargetsDaily() {
        try {
            int lockedCount = salesTargetService.lockPastPeriodTargets("SYSTEM_SCHEDULER");
            if (lockedCount > 0) {
                logger.info("[S8-04] Đã tự động khoá {} chỉ tiêu doanh số đã qua kỳ", lockedCount);
            }
        } catch (Exception ex) {
            logger.error("[S8-04] Lỗi khi tự động khoá chỉ tiêu doanh số qua kỳ: {}", ex.getMessage(), ex);
        }
    }
}

