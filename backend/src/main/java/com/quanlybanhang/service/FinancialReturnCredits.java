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
        if (invoice == null || invoice.getStatus() == InvoiceStatus.CANCELLED) return BigDecimal.ZERO;
        // Legacy ISSUED rows may contain the model's default zero. ADJUSTED zero
        // is a real full decrease and must never fall back to the original amount.
        BigDecimal original = invoice.getAdjustedTotalAmount() != null
                && (invoice.getStatus() == InvoiceStatus.ADJUSTED || invoice.getAdjustedTotalAmount().signum() != 0)
                ? invoice.getAdjustedTotalAmount()
                : (invoice.getTotalAmount() != null ? invoice.getTotalAmount() : BigDecimal.ZERO);
        return original.subtract(credits != null ? credits.getOrDefault(invoice.getId(), BigDecimal.ZERO) : BigDecimal.ZERO).max(BigDecimal.ZERO);
    }
}
