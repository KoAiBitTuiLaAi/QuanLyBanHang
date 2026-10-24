package com.quanlybanhang.controller;

import com.quanlybanhang.dto.response.ApiResponse;
import com.quanlybanhang.dto.response.PaymentReminderLogResponse;
import com.quanlybanhang.dto.response.PaymentReminderSummaryResponse;
import com.quanlybanhang.model.PaymentReminderType;
import com.quanlybanhang.service.PaymentReminderService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

/**
 * Controller Quản lý nhắc hạn thanh toán hóa đơn (S7-08).
 *
 * Tính năng chính:
 * - Kích hoạt tác vụ quét và gửi nhắc hạn thanh toán thủ công hoặc theo ngày chỉ định (POST /api/finance/payment-reminders/trigger)
 * - Tra cứu nhật ký lịch sử các lần gửi nhắc hạn thanh toán (GET /api/finance/payment-reminders/logs)
 *
 * Phân quyền ma trận database-backed:
 * - Kích hoạt quét nhắc nợ: FINANCE_MANAGE hoặc ADMIN.
 * - Xem nhật ký gửi nhắc: FINANCE_VIEW_DEBT hoặc ADMIN.
 */
@RestController
@RequestMapping("/api/finance/payment-reminders")
@RequiredArgsConstructor
public class PaymentReminderController {

    private final PaymentReminderService paymentReminderService;

    /**
     * Kích hoạt quét và gửi nhắc hạn thanh toán tự động (S7-08).
     * Cho phép truyền targetDate tùy chọn để kiểm thử hoặc chạy bù.
     */
    @PostMapping("/trigger")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('FINANCE_MANAGE')")
    public ResponseEntity<ApiResponse> triggerReminders(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate targetDate) {
        LocalDate dateToRun = targetDate != null ? targetDate : LocalDate.now();
        PaymentReminderSummaryResponse summary = paymentReminderService.processPaymentReminders(dateToRun);
        return ResponseEntity.ok(ApiResponse.success("Thực hiện tác vụ quét nhắc hạn thanh toán thành công", summary));
    }

    /**
     * Tra cứu nhật ký lịch sử các lần gửi nhắc hạn thanh toán (S7-08).
     */
    @GetMapping("/logs")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('FINANCE_VIEW_DEBT')")
    public ResponseEntity<ApiResponse> getReminderLogs(
            @RequestParam(required = false) Long dealerId,
            @RequestParam(required = false) Long invoiceId,
            @RequestParam(required = false) PaymentReminderType reminderType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<PaymentReminderLogResponse> logs = paymentReminderService.getReminderLogs(
                dealerId,
                invoiceId,
                reminderType,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "sentAt"))
        );
        return ResponseEntity.ok(ApiResponse.success("Lịch sử nhắc hạn thanh toán", logs));
    }
}
