package com.quanlybanhang.service;

import com.quanlybanhang.model.Invoice;
import com.quanlybanhang.model.InvoiceStatus;
import com.quanlybanhang.repository.ReturnOrderRepository;
import java.math.BigDecimal;
import java.util.*;

/** Read confirmed financial returns without mutating the source invoice. */
public final class FinancialReturnCredits {
    private FinancialReturnCredits() {}
    public static Map<Long, BigDecimal> byInvoice(ReturnOrderRepository repository, Long dealerId) {
        Map<Long, BigDecimal> result = new HashMap<>();
        if (repository != null && dealerId != null) {
            var rows = repository.creditsByInvoice(dealerId);
            if (rows != null) rows.forEach(row -> result.put(row.getInvoiceId(), row.getAmount()));
        }
        return result;
    }
    public static BigDecimal netInvoice(Invoice invoice, Map<Long, BigDecimal> credits) {
        if (invoice.getStatus() == InvoiceStatus.CANCELLED) return BigDecimal.ZERO;
        BigDecimal original = invoice.getAdjustedTotalAmount() != null ? invoice.getAdjustedTotalAmount() : invoice.getTotalAmount();
        return (original == null ? BigDecimal.ZERO : original)
                .subtract(credits.getOrDefault(invoice.getId(), BigDecimal.ZERO)).max(BigDecimal.ZERO);
    }
}
