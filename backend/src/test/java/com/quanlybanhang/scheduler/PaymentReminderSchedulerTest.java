package com.quanlybanhang.scheduler;

import com.quanlybanhang.dto.response.PaymentReminderSummaryResponse;
import com.quanlybanhang.service.PaymentReminderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentReminderSchedulerTest - Tác vụ định kỳ nhắc hạn thanh toán (S7-08)")
class PaymentReminderSchedulerTest {

    @Mock
    private PaymentReminderService paymentReminderService;

    @InjectMocks
    private PaymentReminderScheduler scheduler;

    @Test
    @DisplayName("S7-08 Tiêu chí 2: Tác vụ định kỳ gọi service xử lý nhắc hạn hàng ngày")
    void testRunDailyPaymentReminder() {
        PaymentReminderSummaryResponse mockSummary = PaymentReminderSummaryResponse.builder()
                .targetDate(LocalDate.now())
                .totalInvoicesScanned(10)
                .beforeDueRemindersSent(2)
                .overdueRemindersSent(1)
                .skipped(7)
                .errors(0)
                .build();

        when(paymentReminderService.processDailyPaymentReminders()).thenReturn(mockSummary);

        scheduler.runDailyPaymentReminder();

        verify(paymentReminderService, times(1)).processDailyPaymentReminders();
    }

    @Test
    @DisplayName("S7-08: Tác vụ định kỳ xử lý ngoại lệ mà không làm crash ứng dụng")
    void testRunDailyPaymentReminderHandlesException() {
        when(paymentReminderService.processDailyPaymentReminders())
                .thenThrow(new RuntimeException("Database connection timeout"));

        // Không được throw exception ra ngoài
        scheduler.runDailyPaymentReminder();

        verify(paymentReminderService, times(1)).processDailyPaymentReminders();
    }
}
