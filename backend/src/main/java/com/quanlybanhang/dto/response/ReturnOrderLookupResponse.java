package com.quanlybanhang.dto.response;

import com.quanlybanhang.model.InvoiceStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** Narrow source lookup for INVOICE_ADJUST; no customer administration permission needed. */
public final class ReturnOrderLookupResponse {
    private ReturnOrderLookupResponse() {}
    public record InvoiceOption(Long invoiceId, String invoiceCode, String invoiceNumber, Long dealerId,
            String dealerCode, String dealerName, LocalDateTime invoiceDate, InvoiceStatus status) {}
    public record Source(Long invoiceId, String invoiceCode, String invoiceNumber, Long dealerId,
            String dealerCode, String dealerName, List<Item> items) {}
    public record Item(Long invoiceItemId, String productCode, String productName, String unit,
            BigDecimal invoicedQuantity, BigDecimal confirmedReturnQuantity, BigDecimal remainingQuantity,
            BigDecimal invoiceUnitPrice, BigDecimal originalDiscountAmount, BigDecimal originalLineTotal,
            BigDecimal previouslyCreditedAmount) {}
}
