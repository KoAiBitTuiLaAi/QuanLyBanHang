package com.quanlybanhang.service;

import com.lowagie.text.*;
import com.lowagie.text.pdf.*;
import com.quanlybanhang.dto.response.DebtPaymentResponse;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import java.io.ByteArrayOutputStream;
import java.math.*;
import java.text.*;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Both representations use the same persisted receipt, exact decimal amounts and Unicode text. */
@Service
public class DebtReceiptRenderer {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final String[] DIGITS = {"không", "một", "hai", "ba", "bốn", "năm", "sáu", "bảy", "tám", "chín"};

    public byte[] pdf(DebtPaymentResponse p) throws Exception {
        byte[] fontBytes;
        try (var in = new ClassPathResource("fonts/arial.ttf").getInputStream()) { fontBytes = in.readAllBytes(); }
        BaseFont base = BaseFont.createFont("arial.ttf", BaseFont.IDENTITY_H, BaseFont.EMBEDDED, false, fontBytes, null);
        Font normal = new Font(base, 10), bold = new Font(base, 10, Font.BOLD), title = new Font(base, 17, Font.BOLD);
        try (var out = new ByteArrayOutputStream()) {
            Document document = new Document(PageSize.A4, 40, 40, 35, 40);
            PdfWriter writer = PdfWriter.getInstance(document, out);
            writer.setPageEvent(new PdfPageEventHelper() {
                @Override public void onEndPage(PdfWriter w, Document d) {
                    ColumnText.showTextAligned(w.getDirectContent(), Element.ALIGN_RIGHT,
                        new Phrase("Trang " + w.getPageNumber(), normal), d.right(), 20, 0);
                }
            });
            document.open();
            try {
                document.add(new Paragraph("SaleStock", bold));
                Paragraph heading = new Paragraph(title(p), title);
                heading.setAlignment(Element.ALIGN_CENTER); heading.setSpacingAfter(12); document.add(heading);
                line(document, "Số chứng từ", p.getPaymentCode(), bold);
                line(document, "Ngày thu / điều chỉnh", DATE.format(p.getPaymentDate()), normal);
                line(document, "Đại lý", value(p.getDealerCode()) + " – " + value(p.getDealerName()), normal);
                line(document, "Số tiền (VND)", amount(p.getAmount()), bold);
                line(document, "Bằng chữ", words(p.getAmount()), normal);
                line(document, "Hình thức", method(p.getPaymentMethod()), normal);
                line(document, "Người thu / người lập", p.getRecordedBy(), normal);
                line(document, "Tham chiếu", p.getReferenceCode(), normal);
                if (adjustment(p)) {
                    line(document, "Phiếu thu gốc", p.getOriginalPaymentCode(), bold);
                    line(document, "Lý do điều chỉnh", p.getAdjustmentReason(), normal);
                    document.add(new Paragraph("Bút toán điều chỉnh có dấu; không phải khoản thu mới.", bold));
                }
                line(document, "Ghi chú", p.getNote(), normal);
                if (p.getAllocations() != null && !p.getAllocations().isEmpty()) {
                    PdfPTable table = new PdfPTable(new float[]{1, 4, 3, 5});
                    table.setWidthPercentage(100); table.setSpacingBefore(14); table.setHeaderRows(1); table.setSplitLate(false);
                    for (String h : new String[]{"STT", "Đơn hàng", "Số tiền đối trừ", "Ghi chú"}) cell(table, h, bold);
                    int i = 0;
                    for (var a : p.getAllocations()) {
                        cell(table, String.valueOf(++i), normal); cell(table, a.getOrderCode(), normal);
                        cell(table, amount(a.getAllocatedAmount()), normal); cell(table, a.getNote(), normal);
                    }
                    document.add(table);
                }
                Paragraph signatures = new Paragraph("Người nộp tiền                         Người thu tiền\n(Ký, ghi rõ họ tên)                     (Ký, ghi rõ họ tên)", normal);
                signatures.setSpacingBefore(25); signatures.setAlignment(Element.ALIGN_CENTER); document.add(signatures);
            } finally { document.close(); }
            return out.toByteArray();
        }
    }

    public String html(DebtPaymentResponse p) {
        StringBuilder body = new StringBuilder("<!doctype html><html lang=\"vi\"><head><meta charset=\"utf-8\"><title>")
            .append(escape(p.getPaymentCode())).append("</title><style>")
            .append("*{box-sizing:border-box}body{margin:0;padding:24px;font:14px Arial,sans-serif;color:#172c3c;background:white;overflow-wrap:anywhere}h1{text-align:center;font-size:23px}p{line-height:1.6;margin:8px 0}table{width:100%;border-collapse:collapse;margin:18px 0}th,td{border:1px solid #94a3b8;padding:8px;text-align:left}thead{display:table-header-group}tr{break-inside:avoid}.sign{display:flex;justify-content:space-around;text-align:center;margin:32px 0 70px}@page{size:A4;margin:15mm}@media print{body{padding:0;color:black}h1{font-size:20px}}")
            .append("</style></head><body><strong>SaleStock</strong><h1>").append(title(p)).append("</h1>");
        field(body, "Số chứng từ", p.getPaymentCode());
        field(body, "Ngày thu / điều chỉnh", DATE.format(p.getPaymentDate()));
        field(body, "Đại lý", value(p.getDealerCode()) + " – " + value(p.getDealerName()));
        field(body, "Số tiền (VND)", amount(p.getAmount())); field(body, "Bằng chữ", words(p.getAmount()));
        field(body, "Hình thức", method(p.getPaymentMethod())); field(body, "Người thu / người lập", p.getRecordedBy());
        field(body, "Tham chiếu", p.getReferenceCode());
        if (adjustment(p)) {
            field(body, "Phiếu thu gốc", p.getOriginalPaymentCode()); field(body, "Lý do điều chỉnh", p.getAdjustmentReason());
            body.append("<p><strong>Bút toán điều chỉnh có dấu; không phải khoản thu mới.</strong></p>");
        }
        field(body, "Ghi chú", p.getNote());
        if (p.getAllocations() != null && !p.getAllocations().isEmpty()) {
            body.append("<table><thead><tr><th>STT</th><th>Đơn hàng</th><th>Số tiền đối trừ</th><th>Ghi chú</th></tr></thead><tbody>");
            int i = 0;
            for (var a : p.getAllocations()) body.append("<tr><td>").append(++i).append("</td><td>").append(escape(a.getOrderCode()))
                .append("</td><td>").append(escape(amount(a.getAllocatedAmount()))).append("</td><td>").append(escape(a.getNote())).append("</td></tr>");
            body.append("</tbody></table>");
        }
        return body.append("<div class=\"sign\"><div>Người nộp tiền<br>(Ký, ghi rõ họ tên)</div><div>Người thu tiền<br>(Ký, ghi rõ họ tên)</div></div></body></html>").toString();
    }

    private static void field(StringBuilder s, String label, String value) {
        s.append("<p><strong>").append(label).append(":</strong> ").append(escape(value)).append("</p>");
    }
    private static void line(Document d, String label, String value, Font f) throws DocumentException {
        Paragraph p = new Paragraph(label + ": " + value(value), f); p.setSpacingAfter(6); d.add(p);
    }
    private static void cell(PdfPTable t, String value, Font f) {
        PdfPCell c = new PdfPCell(new Phrase(value(value), f)); c.setPadding(6); t.addCell(c);
    }
    private static boolean adjustment(DebtPaymentResponse p) { return "ADJUSTMENT".equals(p.getPaymentType()); }
    private static String title(DebtPaymentResponse p) { return adjustment(p) ? "CHỨNG TỪ ĐIỀU CHỈNH PHIẾU THU" : "PHIẾU THU"; }
    private static String value(String s) { return s == null || s.isBlank() ? "Chưa có dữ liệu" : s; }
    private static String escape(String s) { return value(s).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;"); }
    private static String method(String s) {
        if (s == null) return value(null);
        return switch (s) { case "TIEN_MAT" -> "Tiền mặt"; case "CHUYEN_KHOAN" -> "Chuyển khoản"; default -> s; };
    }
    static String amount(BigDecimal value) {
        DecimalFormat f = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.forLanguageTag("vi-VN")));
        return value == null ? "Chưa có dữ liệu" : f.format(value);
    }
    static String words(BigDecimal amount) {
        if (amount == null) return value(null);
        BigDecimal exact = amount.abs().setScale(2, RoundingMode.UNNECESSARY);
        BigInteger whole = exact.toBigInteger();
        int fraction = exact.remainder(BigDecimal.ONE).movePointRight(2).intValueExact();
        String text = (amount.signum() < 0 ? "Âm " : "") + integerWords(whole) + " đồng";
        if (fraction != 0) text += ", " + (fraction < 10 ? "không " : "") + group(fraction, false) + " xu";
        if (fraction == 0) text += " chẵn";
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
    private static String integerWords(BigInteger n) {
        if (n.signum() == 0) return "không";
        java.util.List<Integer> groups = new java.util.ArrayList<>();
        BigInteger thousand = BigInteger.valueOf(1000);
        while (n.signum() > 0) { BigInteger[] qr = n.divideAndRemainder(thousand); groups.add(qr[1].intValue()); n = qr[0]; }
        StringBuilder s = new StringBuilder();
        for (int i = groups.size() - 1; i >= 0; i--) {
            int g = groups.get(i);
            if (g == 0) continue;
            if (!s.isEmpty()) s.append(' ');
            s.append(group(g, i < groups.size() - 1));
            if (i % 3 == 1) s.append(" nghìn");
            if (i % 3 == 2) s.append(" triệu");
            s.append(" tỷ".repeat(i / 3));
        }
        return s.toString();
    }
    private static String group(int n, boolean full) {
        int h = n / 100, t = n / 10 % 10, u = n % 10;
        StringBuilder s = new StringBuilder();
        if (h > 0 || full) s.append(DIGITS[h]).append(" trăm");
        if (t > 1) { if (!s.isEmpty()) s.append(' '); s.append(DIGITS[t]).append(" mươi"); }
        else if (t == 1) { if (!s.isEmpty()) s.append(' '); s.append("mười"); }
        else if (!s.isEmpty() && u > 0) s.append(" lẻ");
        if (u > 0) {
            if (!s.isEmpty()) s.append(' ');
            s.append(u == 1 && t > 1 ? "mốt" : u == 5 && t > 0 ? "lăm" : DIGITS[u]);
        }
        return s.toString();
    }
}
