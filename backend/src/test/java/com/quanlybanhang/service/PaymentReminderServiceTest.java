package com.quanlybanhang.service;

import com.quanlybanhang.dto.response.PaymentReminderLogResponse;
import com.quanlybanhang.dto.response.PaymentReminderSummaryResponse;
import com.quanlybanhang.model.*;
import com.quanlybanhang.repository.InvoiceRepository;
import com.quanlybanhang.repository.PaymentReminderLogRepository;
import com.quanlybanhang.service.impl.PaymentReminderServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentReminderServiceTest - Nghiệp vụ tự động nhắc hạn thanh toán cho Đại lý (S7-08)")
class PaymentReminderServiceTest {

    @Mock
    private InvoiceRepository invoiceRepository;
    @Mock
    private PaymentReminderLogRepository reminderLogRepository;
    @Mock
    private NotificationService notificationService;
    @Mock
    private PaymentReminderMailService mailService;

    @InjectMocks
    private PaymentReminderServiceImpl paymentReminderService;

    private Dealer dealerA;
    private Invoice invoiceBeforeDue3Days;
    private Invoice invoiceOverdue;
    private Invoice invoiceFarFuture;
    private Invoice invoicePaid;
    private Invoice invoiceCancelled;

    private final LocalDate today = LocalDate.of(2026, 10, 23);

    @BeforeEach
    void setUp() {
        dealerA = Dealer.builder()
                .id(1L)
                .code("DL-001")
                .name("Đại lý Minh Phát")
                .email("minhphat@example.com")
                .phone("0912345678")
                .maxDebtDays(30)
                .active(true)
                .build();

        // 1. Hóa đơn sắp đến hạn trong đúng 3 ngày (hạn ngày 26/10/2026)
        invoiceBeforeDue3Days = Invoice.builder()
                .id(101L)
                .invoiceNumber("00000001")
                .invoiceCode("HD-00000001")
                .invoiceDate(LocalDateTime.of(2026, 9, 26, 8, 0))
                .dueDate(today.plusDays(3))
                .dealer(dealerA)
                .status(InvoiceStatus.ISSUED)
                .totalAmount(new BigDecimal("50000000"))
                .adjustedTotalAmount(new BigDecimal("50000000"))
                .paidAmount(BigDecimal.ZERO)
                .isPaid(false)
                .build();

        // 2. Hóa đơn đã quá hạn 5 ngày (hạn ngày 18/10/2026)
        invoiceOverdue = Invoice.builder()
                .id(102L)
                .invoiceNumber("00000002")
                .invoiceCode("HD-00000002")
                .invoiceDate(LocalDateTime.of(2026, 9, 18, 8, 0))
                .dueDate(today.minusDays(5))
                .dealer(dealerA)
                .status(InvoiceStatus.ISSUED)
                .totalAmount(new BigDecimal("30000000"))
                .adjustedTotalAmount(new BigDecimal("30000000"))
                .paidAmount(BigDecimal.ZERO)
                .isPaid(false)
                .build();

        // 3. Hóa đơn còn tận 10 ngày nữa mới đến hạn (hạn ngày 02/11/2026)
        invoiceFarFuture = Invoice.builder()
                .id(103L)
                .invoiceNumber("00000003")
                .invoiceCode("HD-00000003")
                .invoiceDate(LocalDateTime.of(2026, 10, 2, 8, 0))
                .dueDate(today.plusDays(10))
                .dealer(dealerA)
                .status(InvoiceStatus.ISSUED)
                .totalAmount(new BigDecimal("20000000"))
                .adjustedTotalAmount(new BigDecimal("20000000"))
                .paidAmount(BigDecimal.ZERO)
                .isPaid(false)
                .build();

        // 4. Hóa đơn đã thanh toán đủ
        invoicePaid = Invoice.builder()
                .id(104L)
                .invoiceNumber("00000004")
                .invoiceCode("HD-00000004")
                .invoiceDate(LocalDateTime.of(2026, 9, 15, 8, 0))
                .dueDate(today.minusDays(2))
                .dealer(dealerA)
                .status(InvoiceStatus.ISSUED)
                .totalAmount(new BigDecimal("15000000"))
                .adjustedTotalAmount(new BigDecimal("15000000"))
                .paidAmount(new BigDecimal("15000000"))
                .isPaid(true)
                .build();

        // 5. Hóa đơn đã hủy
        invoiceCancelled = Invoice.builder()
                .id(105L)
                .invoiceNumber("00000005")
                .invoiceCode("HD-00000005")
                .invoiceDate(LocalDateTime.of(2026, 9, 10, 8, 0))
                .dueDate(today.minusDays(10))
                .dealer(dealerA)
                .status(InvoiceStatus.CANCELLED)
                .totalAmount(new BigDecimal("10000000"))
                .adjustedTotalAmount(new BigDecimal("10000000"))
                .paidAmount(BigDecimal.ZERO)
                .isPaid(false)
                .build();
    }

    @Test
    @DisplayName("S7-08 Tiêu chí 1: Gửi email và thông báo trong app trước hạn 3 ngày cho hóa đơn")
    void testSendReminderBeforeDue3Days() {
        when(invoiceRepository.findActiveUnpaidInvoicesWithDealer()).thenReturn(List.of(invoiceBeforeDue3Days));
        when(reminderLogRepository.existsByInvoiceIdAndReminderDate(101L, today)).thenReturn(false);

        Notification mockNotification = Notification.builder().id(999L).build();
        when(notificationService.sendInAppNotification(eq(dealerA), anyString(), anyString(),
                eq(NotificationType.PAYMENT_DUE_BEFORE_3_DAYS), eq("INVOICE"), eq(101L), eq("HD-00000001")))
                .thenReturn(mockNotification);

        when(mailService.sendReminderEmail(eq(dealerA), eq(invoiceBeforeDue3Days),
                eq(PaymentReminderType.BEFORE_DUE_3_DAYS), eq(3)))
                .thenReturn(new PaymentReminderMailService.MailSendResult(true, "minhphat@example.com", null));

        when(reminderLogRepository.save(any(PaymentReminderLog.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        PaymentReminderSummaryResponse summary = paymentReminderService.processPaymentReminders(today);

        assertEquals(1, summary.getTotalInvoicesScanned());
        assertEquals(1, summary.getBeforeDueRemindersSent());
        assertEquals(0, summary.getOverdueRemindersSent());
        assertEquals(0, summary.getSkipped());

        // Kiểm chứng thông báo trong ứng dụng được gọi
        verify(notificationService, times(1)).sendInAppNotification(
                eq(dealerA),
                contains("sắp đến hạn (còn 3 ngày)"),
                contains("HD-00000001"),
                eq(NotificationType.PAYMENT_DUE_BEFORE_3_DAYS),
                eq("INVOICE"),
                eq(101L),
                eq("HD-00000001")
        );

        // Kiểm chứng gửi email được gọi
        verify(mailService, times(1)).sendReminderEmail(
                eq(dealerA),
                eq(invoiceBeforeDue3Days),
                eq(PaymentReminderType.BEFORE_DUE_3_DAYS),
                eq(3)
        );

        // Kiểm chứng ghi log lưu vết
        ArgumentCaptor<PaymentReminderLog> logCaptor = ArgumentCaptor.forClass(PaymentReminderLog.class);
        verify(reminderLogRepository, times(1)).save(logCaptor.capture());
        PaymentReminderLog savedLog = logCaptor.getValue();
        assertEquals(today, savedLog.getReminderDate());
        assertEquals(PaymentReminderType.BEFORE_DUE_3_DAYS, savedLog.getReminderType());
        assertEquals(3, savedLog.getDaysCount());
        assertTrue(savedLog.getEmailSent());
        assertTrue(savedLog.getInAppNotificationSent());
    }

    @Test
    @DisplayName("S7-08 Tiêu chí 1: Gửi email và thông báo trong app khi hóa đơn quá hạn thanh toán")
    void testSendReminderWhenOverdue() {
        when(invoiceRepository.findActiveUnpaidInvoicesWithDealer()).thenReturn(List.of(invoiceOverdue));
        when(reminderLogRepository.existsByInvoiceIdAndReminderDate(102L, today)).thenReturn(false);

        Notification mockNotification = Notification.builder().id(998L).build();
        when(notificationService.sendInAppNotification(eq(dealerA), anyString(), anyString(),
                eq(NotificationType.PAYMENT_OVERDUE), eq("INVOICE"), eq(102L), eq("HD-00000002")))
                .thenReturn(mockNotification);

        when(mailService.sendReminderEmail(eq(dealerA), eq(invoiceOverdue),
                eq(PaymentReminderType.OVERDUE), eq(5)))
                .thenReturn(new PaymentReminderMailService.MailSendResult(true, "minhphat@example.com", null));

        when(reminderLogRepository.save(any(PaymentReminderLog.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        PaymentReminderSummaryResponse summary = paymentReminderService.processPaymentReminders(today);

        assertEquals(1, summary.getTotalInvoicesScanned());
        assertEquals(0, summary.getBeforeDueRemindersSent());
        assertEquals(1, summary.getOverdueRemindersSent());
        assertEquals(0, summary.getSkipped());

        verify(notificationService, times(1)).sendInAppNotification(
                eq(dealerA),
                contains("quá hạn thanh toán 5 ngày"),
                contains("khoá bán tự động"),
                eq(NotificationType.PAYMENT_OVERDUE),
                eq("INVOICE"),
                eq(102L),
                eq("HD-00000002")
        );

        verify(mailService, times(1)).sendReminderEmail(
                eq(dealerA),
                eq(invoiceOverdue),
                eq(PaymentReminderType.OVERDUE),
                eq(5)
        );
    }

    @Test
    @DisplayName("S7-08 Tiêu chí 3: Không gửi trùng nhiều lần cho cùng một hoá đơn trong cùng một ngày")
    void testDoNotSendDuplicateRemindersOnSameDay() {
        when(invoiceRepository.findActiveUnpaidInvoicesWithDealer()).thenReturn(List.of(invoiceBeforeDue3Days));
        // Đã có log gửi trong ngày hôm nay!
        when(reminderLogRepository.existsByInvoiceIdAndReminderDate(101L, today)).thenReturn(true);

        PaymentReminderSummaryResponse summary = paymentReminderService.processPaymentReminders(today);

        assertEquals(1, summary.getTotalInvoicesScanned());
        assertEquals(0, summary.getBeforeDueRemindersSent());
        assertEquals(0, summary.getOverdueRemindersSent());
        assertEquals(1, summary.getSkipped());

        // Đảm bảo KHÔNG gửi thêm email hay tạo thêm thông báo trong app
        verify(notificationService, never()).sendInAppNotification(any(), any(), any(), any(), any(), any(), any());
        verify(mailService, never()).sendReminderEmail(any(), any(), any(), anyInt());
        verify(reminderLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("S7-08: Bỏ qua hóa đơn còn xa hạn (chưa đến mốc trước 3 ngày)")
    void testSkipInvoiceNotYetWithinReminderWindow() {
        when(invoiceRepository.findActiveUnpaidInvoicesWithDealer()).thenReturn(List.of(invoiceFarFuture));
        when(reminderLogRepository.existsByInvoiceIdAndReminderDate(103L, today)).thenReturn(false);

        PaymentReminderSummaryResponse summary = paymentReminderService.processPaymentReminders(today);

        assertEquals(1, summary.getTotalInvoicesScanned());
        assertEquals(0, summary.getBeforeDueRemindersSent());
        assertEquals(0, summary.getOverdueRemindersSent());
        assertEquals(1, summary.getSkipped());

        verify(notificationService, never()).sendInAppNotification(any(), any(), any(), any(), any(), any(), any());
        verify(mailService, never()).sendReminderEmail(any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("S7-08: Bỏ qua hóa đơn đã thanh toán hoặc đã hủy")
    void testSkipPaidOrCancelledInvoices() {
        when(invoiceRepository.findActiveUnpaidInvoicesWithDealer()).thenReturn(List.of(invoicePaid, invoiceCancelled));

        PaymentReminderSummaryResponse summary = paymentReminderService.processPaymentReminders(today);

        assertEquals(2, summary.getTotalInvoicesScanned());
        assertEquals(0, summary.getBeforeDueRemindersSent());
        assertEquals(0, summary.getOverdueRemindersSent());
        assertEquals(2, summary.getSkipped());

        verify(notificationService, never()).sendInAppNotification(any(), any(), any(), any(), any(), any(), any());
        verify(mailService, never()).sendReminderEmail(any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("S7-08: Xử lý an toàn khi gửi email thất bại (vẫn tạo in-app notification và ghi log lỗi email)")
    void testHandleEmailFailureGracefully() {
        when(invoiceRepository.findActiveUnpaidInvoicesWithDealer()).thenReturn(List.of(invoiceOverdue));
        when(reminderLogRepository.existsByInvoiceIdAndReminderDate(102L, today)).thenReturn(false);

        Notification mockNotification = Notification.builder().id(998L).build();
        when(notificationService.sendInAppNotification(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockNotification);

        // Giả lập lỗi gửi email (ví dụ lỗi SMTP hoặc mạng)
        when(mailService.sendReminderEmail(any(), any(), any(), anyInt()))
                .thenReturn(new PaymentReminderMailService.MailSendResult(false, "minhphat@example.com", "SMTP connection timeout"));

        when(reminderLogRepository.save(any(PaymentReminderLog.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        PaymentReminderSummaryResponse summary = paymentReminderService.processPaymentReminders(today);

        assertEquals(1, summary.getOverdueRemindersSent());

        ArgumentCaptor<PaymentReminderLog> logCaptor = ArgumentCaptor.forClass(PaymentReminderLog.class);
        verify(reminderLogRepository).save(logCaptor.capture());
        PaymentReminderLog log = logCaptor.getValue();
        assertFalse(log.getEmailSent());
        assertEquals("SMTP connection timeout", log.getEmailError());
        assertTrue(log.getInAppNotificationSent());
    }

    @Test
    @DisplayName("S7-08: Tra cứu nhật ký nhắc hạn thanh toán có phân trang và lọc")
    void testGetReminderLogs() {
        PaymentReminderLog log = PaymentReminderLog.builder()
                .id(1L)
                .invoice(invoiceBeforeDue3Days)
                .dealer(dealerA)
                .reminderDate(today)
                .reminderType(PaymentReminderType.BEFORE_DUE_3_DAYS)
                .daysCount(3)
                .amount(new BigDecimal("50000000"))
                .emailSent(true)
                .inAppNotificationSent(true)
                .sentAt(LocalDateTime.now())
                .build();

        Page<PaymentReminderLog> page = new PageImpl<>(List.of(log));
        when(reminderLogRepository.findAll(any(Specification.class), any(PageRequest.class))).thenReturn(page);

        Page<PaymentReminderLogResponse> result = paymentReminderService.getReminderLogs(
                1L, 101L, PaymentReminderType.BEFORE_DUE_3_DAYS, PageRequest.of(0, 10));

        assertNotNull(result);
        assertEquals(1, result.getTotalElements());
        assertEquals("HD-00000001", result.getContent().get(0).getInvoiceCode());
        assertEquals(PaymentReminderType.BEFORE_DUE_3_DAYS, result.getContent().get(0).getReminderType());
    }
}
