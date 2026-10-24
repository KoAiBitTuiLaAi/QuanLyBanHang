package com.quanlybanhang.service;

import com.quanlybanhang.dto.response.PaymentReminderLogResponse;
import com.quanlybanhang.dto.response.PaymentReminderSummaryResponse;
import com.quanlybanhang.model.PaymentReminderType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;

/**
 * Service Quản lý nhắc hạn thanh toán tự động cho Đại lý (S7-08).
 *
 * Tiêu chí:
 * 1. Gửi email và thông báo trong ứng dụng trước hạn 3 ngày và khi quá hạn.
 * 2. Tác vụ định kỳ chạy mỗi ngày một lần.
 * 3. Không gửi trùng nhiều lần cho cùng một hoá đơn trong cùng một ngày.
 */
public interface PaymentReminderService {

    /**
     * Tác vụ định kỳ quét và xử lý nhắc hạn thanh toán trong ngày hôm nay.
     */
    PaymentReminderSummaryResponse processDailyPaymentReminders();

    /**
     * Quét và xử lý nhắc hạn thanh toán tại ngày mục tiêu (hỗ trợ kiểm thử và chạy bù).
     */
    PaymentReminderSummaryResponse processPaymentReminders(LocalDate targetDate);

    /**
     * Lấy danh sách lịch sử các lượt gửi nhắc hạn thanh toán.
     */
    Page<PaymentReminderLogResponse> getReminderLogs(Long dealerId, Long invoiceId,
                                                    PaymentReminderType type, Pageable pageable);
}
