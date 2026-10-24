package com.quanlybanhang.dto.response;

import lombok.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentReminderSummaryResponse {
    private LocalDate targetDate;
    private int totalInvoicesScanned;
    private int beforeDueRemindersSent;
    private int overdueRemindersSent;
    private int skipped;
    private int errors;
    private String message;

    @Builder.Default
    private List<PaymentReminderLogResponse> details = new ArrayList<>();
}
