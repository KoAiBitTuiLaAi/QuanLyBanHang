package com.quanlybanhang.service.impl;

import com.quanlybanhang.dto.response.PaymentReminderLogResponse;
import com.quanlybanhang.dto.response.PaymentReminderSummaryResponse;
import com.quanlybanhang.model.*;
import com.quanlybanhang.repository.InvoiceRepository;
import com.quanlybanhang.repository.PaymentReminderLogRepository;
import com.quanlybanhang.service.NotificationService;
import com.quanlybanhang.service.PaymentReminderMailService;
import com.quanlybanhang.service.PaymentReminderService;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentReminderServiceImpl implements PaymentReminderService {

    private final InvoiceRepository invoiceRepository;
    private final PaymentReminderLogRepository reminderLogRepository;
    private final NotificationService notificationService;
    private final PaymentReminderMailService mailService;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DecimalFormat MONEY_FORMATTER = new DecimalFormat("#,###");

    @Override
    @Transactional
    public PaymentReminderSummaryResponse processDailyPaymentReminders() {
        return processPaymentReminders(LocalDate.now());
    }

    @Override
    @Transactional
    public PaymentReminderSummaryResponse processPaymentReminders(LocalDate targetDate) {
        if (targetDate == null) {
            targetDate = LocalDate.now();
        }

        log.info("Bắt đầu quét nhắc hạn thanh toán cho ngày: {}", targetDate);

        List<Invoice> activeInvoices = invoiceRepository.findActiveUnpaidInvoicesWithDealer();
        int totalScanned = activeInvoices.size();
        int beforeDueSent = 0;
        int overdueSent = 0;
        int skipped = 0;
        int errors = 0;
        List<PaymentReminderLogResponse> details = new ArrayList<>();

        for (Invoice invoice : activeInvoices) {
            try {
                // Bỏ qua nếu hóa đơn đã hủy hoặc đã thanh toán
                if (invoice.getStatus() == InvoiceStatus.CANCELLED || Boolean.TRUE.equals(invoice.getIsPaid())) {
                    skipped++;
                    continue;
                }

                // Tiêu chí S7-08: Không gửi trùng nhiều lần cho cùng một hoá đơn trong cùng một ngày
                if (reminderLogRepository.existsByInvoiceIdAndReminderDate(invoice.getId(), targetDate)) {
                    log.debug("Hóa đơn {} đã được gửi nhắc trong ngày {}, bỏ qua chống gửi trùng",
                            invoice.getInvoiceCode(), targetDate);
                    skipped++;
                    continue;
                }

                Dealer dealer = invoice.getDealer();
                if (dealer == null) {
                    skipped++;
                    continue;
                }

                LocalDate dueDate = invoice.getEffectiveDueDate();
                long daysUntilDue = ChronoUnit.DAYS.between(targetDate, dueDate);

                PaymentReminderType reminderType = null;
                int daysCount = 0;

                // Kịch bản 1: Trước hạn 3 ngày (hoặc trong khoảng 1-3 ngày nếu chưa từng được gửi nhắc trước hạn)
                boolean isBeforeDueEligible = (daysUntilDue == 3)
                        || (daysUntilDue > 0 && daysUntilDue <= 3
                        && !reminderLogRepository.existsByInvoiceIdAndReminderType(invoice.getId(), PaymentReminderType.BEFORE_DUE_3_DAYS));

                if (isBeforeDueEligible) {
                    reminderType = PaymentReminderType.BEFORE_DUE_3_DAYS;
                    daysCount = (int) daysUntilDue;
                } else if (dueDate.isBefore(targetDate)) {
                    // Kịch bản 2: Khi quá hạn thanh toán
                    reminderType = PaymentReminderType.OVERDUE;
                    daysCount = (int) ChronoUnit.DAYS.between(dueDate, targetDate);
                }

                if (reminderType == null) {
                    // Chưa đến mốc nhắc trước hạn 3 ngày và chưa quá hạn
                    skipped++;
                    continue;
                }

                // Thực hiện gửi thông báo trong ứng dụng và gửi email
                PaymentReminderLog reminderLog = sendReminder(dealer, invoice, reminderType, daysCount, targetDate);
                if (reminderLog != null) {
                    if (reminderType == PaymentReminderType.BEFORE_DUE_3_DAYS) {
                        beforeDueSent++;
                    } else {
                        overdueSent++;
                    }
                    details.add(PaymentReminderLogResponse.fromEntity(reminderLog));
                }
            } catch (DataIntegrityViolationException ex) {
                // Đụng khóa unique (invoice_id, reminder_date) do luồng song song, bỏ qua an toàn
                log.warn("Đã có luồng khác gửi nhắc cho hóa đơn {} trong ngày {}: {}",
                        invoice.getInvoiceCode(), targetDate, ex.getMessage());
                skipped++;
            } catch (Exception ex) {
                log.error("Lỗi khi xử lý nhắc hạn cho hóa đơn {}: {}", invoice.getInvoiceCode(), ex.getMessage(), ex);
                errors++;
            }
        }

        String summaryMessage = String.format(
                "Hoàn tất quét nhắc hạn ngày %s. Tổng quét: %d, Trước hạn 3 ngày: %d, Quá hạn: %d, Bỏ qua: %d, Lỗi: %d",
                targetDate.format(DATE_FORMATTER), totalScanned, beforeDueSent, overdueSent, skipped, errors);

        log.info(summaryMessage);

        return PaymentReminderSummaryResponse.builder()
                .targetDate(targetDate)
                .totalInvoicesScanned(totalScanned)
                .beforeDueRemindersSent(beforeDueSent)
                .overdueRemindersSent(overdueSent)
                .skipped(skipped)
                .errors(errors)
                .message(summaryMessage)
                .details(details)
                .build();
    }

    /**
     * Gửi thông báo trong app, gửi email và ghi nhận lịch sử vào PaymentReminderLog.
     */
    private PaymentReminderLog sendReminder(Dealer dealer, Invoice invoice,
                                           PaymentReminderType reminderType, int daysCount,
                                           LocalDate targetDate) {
        BigDecimal amount = invoice.getAdjustedTotalAmount() != null
                ? invoice.getAdjustedTotalAmount()
                : invoice.getTotalAmount();
        String formattedAmount = MONEY_FORMATTER.format(amount) + " VNĐ";
        String formattedDueDate = invoice.getEffectiveDueDate().format(DATE_FORMATTER);

        String title;
        String message;
        NotificationType notifType;

        if (reminderType == PaymentReminderType.BEFORE_DUE_3_DAYS) {
            title = String.format("Nhắc hạn thanh toán: Hóa đơn %s sắp đến hạn (còn %d ngày)",
                    invoice.getInvoiceCode(), daysCount);
            message = String.format(
                    "Hóa đơn %s của Quý đại lý có hạn thanh toán vào ngày %s (còn %d ngày). Số tiền cần thanh toán: %s. Vui lòng thanh toán đúng hạn để tránh gián đoạn đặt hàng.",
                    invoice.getInvoiceCode(), formattedDueDate, daysCount, formattedAmount);
            notifType = NotificationType.PAYMENT_DUE_BEFORE_3_DAYS;
        } else {
            title = String.format("CẢNH BÁO: Hóa đơn %s đã quá hạn thanh toán %d ngày",
                    invoice.getInvoiceCode(), daysCount);
            message = String.format(
                    "Hóa đơn %s (hạn trả %s) ĐÃ QUÁ HẠN %d ngày. Số tiền quá hạn: %s. Đơn hàng mới có thể bị chuyển sang chờ duyệt hoặc bị khoá bán tự động. Vui lòng thanh toán ngay.",
                    invoice.getInvoiceCode(), formattedDueDate, daysCount, formattedAmount);
            notifType = NotificationType.PAYMENT_OVERDUE;
        }

        // 1. Gửi thông báo trong ứng dụng (In-app notification)
        Notification inAppNotification = null;
        boolean inAppSent = false;
        try {
            inAppNotification = notificationService.sendInAppNotification(
                    dealer,
                    title,
                    message,
                    notifType,
                    "INVOICE",
                    invoice.getId(),
                    invoice.getInvoiceCode()
            );
            inAppSent = inAppNotification != null;
        } catch (Exception e) {
            log.error("Không thể tạo thông báo trong ứng dụng cho đại lý {}: {}", dealer.getCode(), e.getMessage());
        }

        // 2. Gửi email
        PaymentReminderMailService.MailSendResult mailResult = mailService.sendReminderEmail(dealer, invoice, reminderType, daysCount);

        // 3. Ghi log lịch sử gửi nhắc (kèm ràng buộc unique invoice_id + reminder_date)
        PaymentReminderLog reminderLog = PaymentReminderLog.builder()
                .invoice(invoice)
                .dealer(dealer)
                .reminderDate(targetDate)
                .reminderType(reminderType)
                .daysCount(daysCount)
                .amount(amount)
                .emailSent(mailResult != null && mailResult.isSuccess())
                .emailRecipient(mailResult != null ? mailResult.getRecipient() : null)
                .emailError(mailResult != null ? mailResult.getErrorMessage() : null)
                .inAppNotificationSent(inAppSent)
                .notificationId(inAppNotification != null ? inAppNotification.getId() : null)
                .note(title)
                .sentAt(LocalDateTime.now())
                .build();

        return reminderLogRepository.save(reminderLog);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PaymentReminderLogResponse> getReminderLogs(Long dealerId, Long invoiceId,
                                                           PaymentReminderType type, Pageable pageable) {
        Specification<PaymentReminderLog> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (dealerId != null) {
                predicates.add(cb.equal(root.get("dealer").get("id"), dealerId));
            }
            if (invoiceId != null) {
                predicates.add(cb.equal(root.get("invoice").get("id"), invoiceId));
            }
            if (type != null) {
                predicates.add(cb.equal(root.get("reminderType"), type));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        return reminderLogRepository.findAll(spec, pageable).map(PaymentReminderLogResponse::fromEntity);
    }
}
