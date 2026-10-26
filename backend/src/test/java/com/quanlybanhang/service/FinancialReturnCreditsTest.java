package com.quanlybanhang.service;

import com.quanlybanhang.model.Invoice;
import com.quanlybanhang.model.InvoiceStatus;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class FinancialReturnCreditsTest {
    @Test
    void fullDecreaseMustNotRestoreOriginalDebt() {
        Invoice invoice = invoice(BigDecimal.ZERO);
        assertThat(FinancialReturnCredits.netInvoice(invoice, Map.of())).isZero();
        assertThat(FinancialReturnCredits.netInvoice(invoice, Map.of(1L, new BigDecimal("3000000")))).isZero();
    }

    @Test
    void onlyMissingAdjustedAmountFallsBackToOriginal() {
        assertThat(FinancialReturnCredits.netInvoice(invoice(null), Map.of(1L, new BigDecimal("3000000"))))
                .isEqualByComparingTo("7000000");
        assertThat(FinancialReturnCredits.netInvoice(invoice(new BigDecimal("4000000")),
                Map.of(1L, new BigDecimal("3000000")))).isEqualByComparingTo("1000000");
    }

    @Test
    void unadjustedLegacyInvoiceWithDefaultZeroRetainsOriginalAmount() {
        Invoice invoice = invoice(BigDecimal.ZERO);
        invoice.setStatus(InvoiceStatus.ISSUED);
        assertThat(FinancialReturnCredits.netInvoice(invoice, Map.of())).isEqualByComparingTo("10000000");
    }

    @Test
    void cancelledInvoiceNeverCarriesDebt() {
        Invoice invoice = invoice(new BigDecimal("10000000"));
        invoice.setStatus(InvoiceStatus.CANCELLED);
        assertThat(FinancialReturnCredits.netInvoice(invoice, Map.of())).isZero();
    }

    private Invoice invoice(BigDecimal adjusted) {
        return Invoice.builder().id(1L).status(InvoiceStatus.ADJUSTED)
                .totalAmount(new BigDecimal("10000000")).adjustedTotalAmount(adjusted).build();
    }
}
