package com.quanlybanhang.scheduler;

import com.quanlybanhang.dto.response.PaymentReminderSummaryResponse;
import com.quanlybanhang.service.PaymentReminderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Tác vụ định kỳ nhắc hạn thanh toán tự động cho Đại lý mỗi ngày một lần (S7-08).
 * Tiêu chí:
 * - Gửi email và thông báo trong ứng dụng trước hạn 3 ngày và khi quá hạn.
 * - Tác vụ định kỳ chạy mỗi ngày một lần.
 * - Không gửi trùng nhiều lần cho cùng một hoá đơn trong cùng một ngày.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.payment-reminder.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class PaymentReminderScheduler {

    private final PaymentReminderService paymentReminderService;

    /**
     * Chạy mỗi ngày một lần (mặc định vào 07:00 sáng hàng ngày).
     * Cron expression: "0 0 7 * * ?"
     */
    @Scheduled(cron = "${app.payment-reminder.cron:0 0 7 * * ?}")
    public void runDailyPaymentReminder() {
        log.info("PaymentReminderScheduler: Bắt đầu tác vụ quét nhắc hạn thanh toán hàng ngày...");
        try {
            PaymentReminderSummaryResponse summary = paymentReminderService.processDailyPaymentReminders();
            log.info("PaymentReminderScheduler: Hoàn tất nhắc hạn thanh toán ngày {}. Quét: {}, Trước hạn 3 ngày: {}, Quá hạn: {}, Bỏ qua: {}, Lỗi: {}",
                    summary.getTargetDate(),
                    summary.getTotalInvoicesScanned(),
                    summary.getBeforeDueRemindersSent(),
                    summary.getOverdueRemindersSent(),
                    summary.getSkipped(),
                    summary.getErrors());
        } catch (Exception e) {
            log.error("PaymentReminderScheduler: Lỗi trong tác vụ nhắc hạn thanh toán định kỳ", e);
        }
    }
}
