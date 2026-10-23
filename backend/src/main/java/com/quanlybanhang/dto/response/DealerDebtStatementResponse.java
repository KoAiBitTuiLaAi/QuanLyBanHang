package com.quanlybanhang.dto.response;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;

public record DealerDebtStatementResponse(Long dealerId, String dealerCode, String dealerName,
        LocalDate from, LocalDate to, BigDecimal openingBalance, BigDecimal totalIncrease,
        BigDecimal totalDecrease, BigDecimal closingBalance, List<Entry> entries) {
    public record Entry(LocalDateTime occurredAt, String sourceType, Long sourceId, String documentCode,
            String description, BigDecimal increase, BigDecimal decrease, BigDecimal balance) {}
}