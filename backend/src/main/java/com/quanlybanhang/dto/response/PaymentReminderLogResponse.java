package com.quanlybanhang.dto.response;

import com.quanlybanhang.model.PaymentReminderLog;
import com.quanlybanhang.model.PaymentReminderType;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentReminderLogResponse {
    private Long id;
    private Long invoiceId;
    private String invoiceCode;
    private Long dealerId;
    private String dealerCode;
    private String dealerName;
    private LocalDate reminderDate;
    private PaymentReminderType reminderType;
    private String reminderTypeLabel;
    private Integer daysCount;
    private BigDecimal amount;
    private Boolean emailSent;
    private String emailRecipient;
    private String emailError;
    private Boolean inAppNotificationSent;
    private Long notificationId;
    private String note;
    private LocalDateTime sentAt;

    public static PaymentReminderLogResponse fromEntity(PaymentReminderLog log) {
        if (log == null) return null;
        return PaymentReminderLogResponse.builder()
                .id(log.getId())
                .invoiceId(log.getInvoice() != null ? log.getInvoice().getId() : null)
                .invoiceCode(log.getInvoice() != null ? log.getInvoice().getInvoiceCode() : null)
                .dealerId(log.getDealer() != null ? log.getDealer().getId() : null)
                .dealerCode(log.getDealer() != null ? log.getDealer().getCode() : null)
                .dealerName(log.getDealer() != null ? log.getDealer().getName() : null)
                .reminderDate(log.getReminderDate())
                .reminderType(log.getReminderType())
                .reminderTypeLabel(log.getReminderType() != null ? log.getReminderType().getDisplayName() : null)
                .daysCount(log.getDaysCount())
                .amount(log.getAmount())
                .emailSent(log.getEmailSent())
                .emailRecipient(log.getEmailRecipient())
                .emailError(log.getEmailError())
                .inAppNotificationSent(log.getInAppNotificationSent())
                .notificationId(log.getNotificationId())
                .note(log.getNote())
                .sentAt(log.getSentAt())
                .build();
    }
}
