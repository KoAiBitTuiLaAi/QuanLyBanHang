package com.quanlybanhang.dto;

import com.quanlybanhang.model.DebtReconciliation.Status;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

public final class DebtReconciliationDtos {
    private DebtReconciliationDtos() {}
    public record Create(@NotNull @Positive Long dealerId, @NotNull LocalDate from, @NotNull LocalDate to,
                         @NotNull UUID requestKey) {}
    public record Feedback(@NotNull @PositiveOrZero Long version, @NotNull Status status,
                           @Digits(integer = 22, fraction = 2) BigDecimal claimedBalance,
                           @Size(max = 500) String reason) {}
    // Money travels as decimal strings so a browser cannot round large balances.
    public record Entry(LocalDateTime occurredAt, String sourceType, Long sourceId, String documentCode,
                        String description, String increase, String decrease, String balance) {}
    public record Snapshot(Long dealerId, String dealerCode, String dealerName, String dealerAddress,
                           String dealerPhone, LocalDate from, LocalDate to, String openingBalance,
                           String totalIncrease, String totalDecrease, String closingBalance, List<Entry> entries) {}
    public record Summary(Long id, Long version, String code, Long dealerId, String dealerName,
                          LocalDate from, LocalDate to, String closingBalance, Status status,
                          String createdBy, LocalDateTime createdAt) {}
    public record Detail(Long id, Long version, String code, Status status, String createdBy,
                         LocalDateTime createdAt, String respondedBy, LocalDateTime respondedAt,
                         String claimedBalance, String difference, String responseReason, Snapshot statement) {}
    public record DealerOption(Long id, String code, String name) {}
}
