package com.quanlybanhang.service;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import com.quanlybanhang.dto.response.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.*;

class DebtReceiptRendererTest {
    DebtReceiptRenderer renderer = new DebtReceiptRenderer();
    DebtPaymentResponse receipt() {
        return DebtPaymentResponse.builder().paymentCode("PT-TEST-2026").dealerCode("DL-TEST")
            .dealerName("Đại lý Nguyễn Ánh").amount(new BigDecimal("15000000.25"))
            .paymentDate(LocalDate.of(2026,10,25)).paymentMethod("TIEN_MAT").recordedBy("Lê Thị Thu")
            .note("Thu công nợ tháng 10").build();
    }
    @Test void pdfPreservesVietnameseAndAllRequiredFields() throws Exception {
        PdfReader pdf = new PdfReader(renderer.pdf(receipt()));
        try { assertThat(new PdfTextExtractor(pdf).getTextFromPage(1))
            .contains("PHIẾU THU", "PT-TEST-2026", "Đại lý Nguyễn Ánh", "15.000.000,25", "Tiền mặt", "Lê Thị Thu", "Mười lăm triệu đồng, hai mươi lăm xu");
        } finally { pdf.close(); }
    }
    @Test void exactDecimalsAndLargeNumbersNeverUseFloatingPointOrLoseSign() {
        assertThat(DebtReceiptRenderer.amount(new BigDecimal("99999999999999999.99"))).isEqualTo("99.999.999.999.999.999,99");
        assertThat(DebtReceiptRenderer.words(new BigDecimal("-1000000001.05"))).contains("Âm một tỷ không trăm lẻ một đồng", "không năm xu");
        assertThat(DebtReceiptRenderer.words(BigDecimal.ZERO)).isEqualTo("Không đồng chẵn");
    }
    @Test void escapesAllDynamicHtmlAndLabelsAdjustmentsInsteadOfNewCollection() throws Exception {
        var p=receipt(); p.setPaymentType("ADJUSTMENT"); p.setOriginalPaymentCode("PT-GOC"); p.setAmount(new BigDecimal("-10.25"));
        p.setAdjustmentReason("<script>alert(1)</script>"); p.setRecordedBy("<img src=x onerror=alert(1)>");
        p.setAllocations(java.util.List.of(DebtPaymentAllocationResponse.builder().orderCode("<iframe>").allocatedAmount(new BigDecimal("-10.25")).note("& <> '").build()));
        assertThat(renderer.html(p)).contains("ĐIỀU CHỈNH", "PT-GOC", "-10,25", "&lt;script&gt;", "&lt;img", "&lt;iframe&gt;").doesNotContain("<script>", "<img", "<iframe>");
        PdfReader pdf=new PdfReader(renderer.pdf(p)); try { assertThat(new PdfTextExtractor(pdf).getTextFromPage(1)).contains("ĐIỀU CHỈNH", "PT-GOC", "-10,25"); } finally {pdf.close();}
    }
    @Test void allocationsWrapAcrossPagesWithoutDroppingFinalEntry() throws Exception {
        var p=receipt(); p.setAllocations(IntStream.rangeClosed(1,150).mapToObj(i -> DebtPaymentAllocationResponse.builder()
            .orderCode("DH-"+i).allocatedAmount(BigDecimal.TEN).note("Ghi chú đối trừ tiếng Việt, dòng có nội dung dài để kiểm tra xuống dòng và ngắt trang.").build()).toList());
        PdfReader pdf=new PdfReader(renderer.pdf(p)); try {
            assertThat(pdf.getNumberOfPages()).isGreaterThan(2); var text=new StringBuilder();
            for(int i=1;i<=pdf.getNumberOfPages();i++) text.append(new PdfTextExtractor(pdf).getTextFromPage(i));
            assertThat(text.toString()).contains("DH-1", "DH-150", "Người nộp tiền", "Ghi chú đối trừ tiếng Việt");
        } finally {pdf.close();}
    }
}
