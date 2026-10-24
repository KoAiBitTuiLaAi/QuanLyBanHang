package com.quanlybanhang.service;

import com.quanlybanhang.model.Dealer;
import com.quanlybanhang.model.Invoice;
import com.quanlybanhang.model.PaymentReminderType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.time.format.DateTimeFormatter;

/**
 * Service gửi email nhắc hạn thanh toán cho Đại lý (S7-08).
 * Hỗ trợ 2 kịch bản:
 * 1. Trước hạn 3 ngày (BEFORE_DUE_3_DAYS)
 * 2. Khi quá hạn thanh toán (OVERDUE)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentReminderMailService {

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username:noreply@salestock.vn}")
    private String senderAddress;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DecimalFormat MONEY_FORMATTER = new DecimalFormat("#,###");

    public static class MailSendResult {
        private final boolean success;
        private final String recipient;
        private final String errorMessage;

        public MailSendResult(boolean success, String recipient, String errorMessage) {
            this.success = success;
            this.recipient = recipient;
            this.errorMessage = errorMessage;
        }

        public boolean isSuccess() {
            return success;
        }

        public String getRecipient() {
            return recipient;
        }

        public String getErrorMessage() {
            return errorMessage;
        }
    }

    /**
     * Gửi email nhắc hạn thanh toán.
     * Trả về MailSendResult ghi nhận kết quả gửi (không throw exception để không làm đứt chuỗi quét các hóa đơn khác).
     */
    public MailSendResult sendReminderEmail(Dealer dealer, Invoice invoice, PaymentReminderType reminderType, int daysCount) {
        String recipient = dealer.getEmail();
        if (recipient == null || recipient.isBlank()) {
            log.warn("Đại lý {} ({}) chưa cấu hình email, bỏ qua gửi email nhắc hạn", dealer.getCode(), dealer.getName());
            return new MailSendResult(false, null, "Đại lý chưa có email liên lạc");
        }

        String invoiceCode = invoice.getInvoiceCode();
        String formattedDate = invoice.getEffectiveDueDate().format(DATE_FORMATTER);
        BigDecimal amount = invoice.getAdjustedTotalAmount() != null ? invoice.getAdjustedTotalAmount() : invoice.getTotalAmount();
        String formattedAmount = MONEY_FORMATTER.format(amount) + " VNĐ";

        String subject;
        String content;

        if (reminderType == PaymentReminderType.BEFORE_DUE_3_DAYS) {
            subject = String.format("[SaleStock] Nhắc hạn thanh toán: Hóa đơn %s sắp đến hạn (còn %d ngày)", invoiceCode, daysCount);
            content = String.format(
                    "Kính gửi Quý đại lý %s,\n\n"
                            + "Hệ thống Quản lý Bán hàng SaleStock xin thông báo hóa đơn bán hàng của Quý đại lý sắp đến hạn thanh toán:\n\n"
                            + "  - Mã hóa đơn: %s\n"
                            + "  - Ngày phát hành: %s\n"
                            + "  - Hạn thanh toán: %s (Còn %d ngày)\n"
                            + "  - Số tiền cần thanh toán: %s\n\n"
                            + "Quý đại lý vui lòng thu xếp thanh toán trước hạn để tránh bị gián đoạn hoạt động đặt hàng và giao hàng.\n\n"
                            + "Trân trọng,\n"
                            + "Ban Quản lý Tài chính - SaleStock",
                    dealer.getName(),
                    invoiceCode,
                    invoice.getInvoiceDate() != null ? invoice.getInvoiceDate().format(DATE_FORMATTER) : "N/A",
                    formattedDate,
                    daysCount,
                    formattedAmount
            );
        } else {
            subject = String.format("[SaleStock] CẢNH BÁO: Hóa đơn %s đã quá hạn thanh toán %d ngày", invoiceCode, daysCount);
            content = String.format(
                    "Kính gửi Quý đại lý %s,\n\n"
                            + "Hệ thống Quản lý Bán hàng SaleStock xin thông báo hóa đơn sau của Quý đại lý ĐÃ QUÁ HẠN THANH TOÁN:\n\n"
                            + "  - Mã hóa đơn: %s\n"
                            + "  - Ngày phát hành: %s\n"
                            + "  - Hạn thanh toán: %s\n"
                            + "  - Số ngày quá hạn: %d ngày\n"
                            + "  - Số tiền quá hạn: %s\n\n"
                            + "CẢNH BÁO NGUY CƠ: Việc quá hạn thanh toán có thể dẫn đến việc đơn hàng mới bị chuyển sang trạng thái chờ duyệt hoặc bị khoá giao dịch tự động theo chính sách rủi ro công nợ.\n\n"
                            + "Quý đại lý vui lòng thanh toán ngay để tài khoản luôn duy trì trạng thái hoạt động tốt.\n\n"
                            + "Trân trọng,\n"
                            + "Ban Quản lý Tài chính - SaleStock",
                    dealer.getName(),
                    invoiceCode,
                    invoice.getInvoiceDate() != null ? invoice.getInvoiceDate().format(DATE_FORMATTER) : "N/A",
                    formattedDate,
                    daysCount,
                    formattedAmount
            );
        }

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(senderAddress);
            message.setTo(recipient.trim());
            message.setSubject(subject);
            message.setText(content);

            mailSender.send(message);
            log.info("Đã gửi email nhắc hạn ({}) cho đại lý {} tới {}", reminderType, dealer.getCode(), recipient);
            return new MailSendResult(true, recipient.trim(), null);
        } catch (Exception ex) {
            log.error("Lỗi khi gửi email nhắc hạn cho đại lý {} tới {}: {}", dealer.getCode(), recipient, ex.getMessage());
            return new MailSendResult(false, recipient.trim(), ex.getMessage());
        }
    }
}
