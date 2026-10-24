package com.quanlybanhang.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Entity Nhật ký nhắc hạn thanh toán hóa đơn (S7-08).
 * Đảm bảo tiêu chí: "Không gửi trùng nhiều lần cho cùng một hoá đơn trong cùng một ngày".
 * Ràng buộc Unique trên (invoice_id, reminder_date) đảm bảo an toàn tuyệt đối.
 */
@Entity
@Table(name = "payment_reminder_logs", uniqueConstraints = {
        @UniqueConstraint(name = "uk_invoice_reminder_date", columnNames = {"invoice_id", "reminder_date"})
}, indexes = {
        @Index(name = "idx_reminder_logs_dealer_date", columnList = "dealer_id, reminder_date"),
        @Index(name = "idx_reminder_logs_invoice", columnList = "invoice_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentReminderLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Hóa đơn được nhắc hạn.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invoice_id", nullable = false)
    private Invoice invoice;

    /**
     * Đại lý nhận nhắc hạn.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dealer_id", nullable = false)
    private Dealer dealer;

    /**
     * Ngày gửi nhắc (dùng để chặn gửi trùng lặp trong ngày).
     */
    @Column(name = "reminder_date", nullable = false)
    private LocalDate reminderDate;

    /**
     * Loại nhắc: BEFORE_DUE_3_DAYS hoặc OVERDUE.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "reminder_type", nullable = false, length = 30)
    private PaymentReminderType reminderType;

    /**
     * Số ngày liên quan: 3 nếu trước hạn 3 ngày; số ngày quá hạn nếu là quá hạn.
     */
    @Column(name = "days_count", nullable = false)
    private Integer daysCount;

    /**
     * Số tiền hóa đơn cần thanh toán tại thời điểm nhắc.
     */
    @Column(precision = 15, scale = 2, nullable = false)
    private BigDecimal amount;

    /**
     * Đã gửi email thành công hay chưa.
     */
    @Column(name = "email_sent", nullable = false)
    @Builder.Default
    private Boolean emailSent = false;

    /**
     * Địa chỉ email người nhận.
     */
    @Column(name = "email_recipient", length = 100)
    private String emailRecipient;

    /**
     * Chi tiết lỗi nếu gửi email không thành công.
     */
    @Column(name = "email_error", length = 500)
    private String emailError;

    /**
     * Đã gửi thông báo trong ứng dụng thành công hay chưa.
     */
    @Column(name = "in_app_notification_sent", nullable = false)
    @Builder.Default
    private Boolean inAppNotificationSent = false;

    /**
     * ID thông báo trong ứng dụng tương ứng (nếu có).
     */
    @Column(name = "notification_id")
    private Long notificationId;

    /**
     * Ghi chú bổ sung.
     */
    @Column(length = 500)
    private String note;

    /**
     * Thời điểm gửi nhắc thực tế.
     */
    @Column(name = "sent_at", nullable = false)
    private LocalDateTime sentAt;

    @PrePersist
    protected void onCreate() {
        if (this.sentAt == null) {
            this.sentAt = LocalDateTime.now();
        }
        if (this.reminderDate == null) {
            this.reminderDate = LocalDate.now();
        }
        if (this.emailSent == null) {
            this.emailSent = false;
        }
        if (this.inAppNotificationSent == null) {
            this.inAppNotificationSent = false;
        }
    }
}
