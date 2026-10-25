package com.quanlybanhang.dto.response;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;

public record DashboardResponse(LocalDate date, YearMonth month, OffsetDateTime generatedAt,
        String timezone, Metrics metrics, List<DailySales> dailySales,
        List<TerritorySales> territorySales, List<OverdueDealer> overdueDealers, long totalOverdueDealers) {
    public record Metrics(BigDecimal todaySales, BigDecimal monthSales, long pendingApprovalOrders,
            long pendingExportOrders, BigDecimal overdueDebt, long belowMinimumSkus) {}
    public record DailySales(LocalDate date, BigDecimal sales) {}
    public record TerritorySales(Long territoryId, String territoryName, BigDecimal sales) {}
    public record OverdueDealer(Long dealerId, String dealerCode, String dealerName, Long territoryId,
            String territoryName, BigDecimal currentDebt, BigDecimal overdueAmount, int maxOverdueDays) {}
}