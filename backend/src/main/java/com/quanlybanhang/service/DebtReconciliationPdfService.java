package com.quanlybanhang.service;

import com.lowagie.text.*;
import com.lowagie.text.pdf.*;
import com.quanlybanhang.dto.DebtReconciliationDtos.Detail;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.text.*;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

@Service
public class DebtReconciliationPdfService {
    public byte[] pdf(Detail record) throws Exception {
        byte[] bytes;
        try (var input = new ClassPathResource("fonts/arial.ttf").getInputStream()) { bytes = input.readAllBytes(); }
        BaseFont base = BaseFont.createFont("arial.ttf", BaseFont.IDENTITY_H, BaseFont.EMBEDDED, false, bytes, null);
        Font normal = new Font(base, 9), bold = new Font(base, 9, Font.BOLD), title = new Font(base, 15, Font.BOLD);
        var format = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.forLanguageTag("vi-VN")));
        var date = DateTimeFormatter.ofPattern("dd/MM/yyyy");
        var time = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
        var s = record.statement();
        try (var out = new ByteArrayOutputStream()) {
            Document document = new Document(PageSize.A4, 30, 30, 30, 35);
            var writer = PdfWriter.getInstance(document, out);
            writer.setPageEvent(new PdfPageEventHelper() {
                @Override public void onEndPage(PdfWriter w, Document d) {
                    ColumnText.showTextAligned(w.getDirectContent(), Element.ALIGN_RIGHT,
                            new Phrase(record.code() + " | Trang " + w.getPageNumber(), normal), d.right(), 18, 0);
                }
            });
            document.open();
            try {
                document.add(new Paragraph("BIÊN BẢN ĐỐI CHIẾU CÔNG NỢ", title));
                document.add(new Paragraph("Số: " + record.code(), bold));
                document.add(new Paragraph("Kỳ: " + date.format(s.from()) + " – " + date.format(s.to()), normal));
                document.add(new Paragraph("BÊN A: SaleStock | Người lập: " + record.createdBy()
                        + " | Lập lúc: " + time.format(record.createdAt()), normal));
                document.add(new Paragraph("BÊN B (ĐẠI LÝ): " + s.dealerCode() + " – " + s.dealerName(), bold));
                document.add(new Paragraph("Địa chỉ: " + value(s.dealerAddress()) + " | Điện thoại: " + value(s.dealerPhone()), normal));
                PdfPTable table = new PdfPTable(new float[]{18, 25, 34, 20, 20, 22});
                table.setWidthPercentage(100); table.setSpacingBefore(12); table.setHeaderRows(1); table.setSplitLate(false);
                for (String h : new String[]{"Ngày", "Chứng từ", "Diễn giải", "Tăng", "Giảm", "Số dư"}) cell(table, h, bold);
                row(table, "", "", "Số dư đầu kỳ", "", "", amount(s.openingBalance(), format), bold);
                for (var e : s.entries()) row(table, time.format(e.occurredAt()), e.documentCode(), e.description(),
                        amount(e.increase(), format), amount(e.decrease(), format), amount(e.balance(), format), normal);
                row(table, "", "", "Tổng phát sinh / Cuối kỳ", amount(s.totalIncrease(), format),
                        amount(s.totalDecrease(), format), amount(s.closingBalance(), format), bold);
                document.add(table);
                document.add(new Paragraph("Đơn vị: VND. Số dư âm thể hiện tiền đại lý thanh toán dư.", normal));
                String status = switch (record.status()) {
                    case PENDING -> "Chờ đại lý xác nhận";
                    case CONFIRMED -> "Đại lý xác nhận khớp đúng";
                    case DISPUTED -> "Đại lý phản hồi lệch số liệu";
                };
                document.add(new Paragraph("Trạng thái: " + status, bold));
                if (record.respondedAt() != null) {
                    document.add(new Paragraph("Người phản hồi: " + record.respondedBy() + " | Lúc: " + time.format(record.respondedAt()), normal));
                    document.add(new Paragraph("Số dư theo đại lý: " + amount(record.claimedBalance(), format)
                            + " | Chênh lệch (hệ thống – đại lý): " + amount(record.difference(), format), normal));
                    if (record.responseReason() != null) document.add(new Paragraph("Lý do: " + record.responseReason(), normal));
                }
                document.add(new Paragraph("Biên bản lưu số liệu tại thời điểm lập; phản hồi không tự điều chỉnh công nợ. "
                        + "Xác nhận trên cổng không phải chữ ký số.", normal));
                var signatures = new PdfPTable(2); signatures.setWidthPercentage(100); signatures.setSpacingBefore(20);
                cell(signatures, "ĐẠI DIỆN BÊN A\n(Ký, ghi rõ họ tên)", bold);
                cell(signatures, "ĐẠI DIỆN ĐẠI LÝ\n(Ký, ghi rõ họ tên)", bold);
                document.add(signatures);
            } finally { document.close(); }
            return out.toByteArray();
        }
    }
    private static String value(String s) { return s == null ? "—" : s; }
    private static String amount(String s, DecimalFormat f) { return f.format(new BigDecimal(s)); }
    private static void cell(PdfPTable table, String text, Font font) {
        var cell = new PdfPCell(new Phrase(value(text), font)); cell.setPadding(5); table.addCell(cell);
    }
    private static void row(PdfPTable table, String date, String code, String text, String debit, String credit, String balance, Font font) {
        for (String s : new String[]{date, code, text, debit, credit, balance}) cell(table, s, font);
    }
}
