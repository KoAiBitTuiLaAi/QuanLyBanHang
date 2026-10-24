package com.quanlybanhang.service;

import com.lowagie.text.*;
import com.lowagie.text.Font;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.*;
import com.quanlybanhang.model.*;
import com.quanlybanhang.repository.InvoiceAdjustmentRepository;
import com.quanlybanhang.repository.InvoiceRepository;
import com.quanlybanhang.util.VietnameseNumberToWords;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Triển khai dịch vụ in và xuất PDF hóa đơn bán hàng và chứng từ điều chỉnh (S7-01).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InvoicePdfServiceImpl implements InvoicePdfService {

    private final InvoiceRepository invoiceRepository;
    private final InvoiceAdjustmentRepository adjustmentRepository;

    private static final Color PRIMARY_COLOR = new Color(24, 43, 73);      // Navy/Deep Blue
    private static final Color SECONDARY_COLOR = new Color(71, 85, 105);   // Slate 600
    private static final Color HEADER_BG = new Color(241, 245, 249);       // Slate 100
    private static final Color ALT_ROW_BG = new Color(248, 250, 252);      // Slate 50
    private static final Color BORDER_COLOR = new Color(203, 213, 225);    // Slate 300
    private static final Color TOTAL_COLOR = new Color(185, 28, 28);       // Crimson Red

    private static final DecimalFormat MONEY_FORMAT;
    private static final DecimalFormat QTY_FORMAT;
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    static {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(new Locale("vi", "VN"));
        symbols.setGroupingSeparator('.');
        symbols.setDecimalSeparator(',');
        MONEY_FORMAT = new DecimalFormat("#,##0 đ", symbols);
        QTY_FORMAT = new DecimalFormat("#,##0.##", symbols);
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] generateInvoicePdf(Long invoiceId, String username) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy hóa đơn với ID: " + invoiceId));

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            Document document = new Document(PageSize.A4, 28, 28, 30, 36);
            PdfWriter writer = PdfWriter.getInstance(document, baos);

            document.open();

            BaseFont baseFontRegular = getBaseFont(false);
            BaseFont baseFontBold = getBaseFont(true);

            Font fontTitle = new Font(baseFontBold, 16, Font.BOLD, PRIMARY_COLOR);
            Font fontSubTitle = new Font(baseFontRegular, 9, Font.ITALIC, SECONDARY_COLOR);
            Font fontCompanyHeader = new Font(baseFontBold, 11, Font.BOLD, PRIMARY_COLOR);
            Font fontCompanyInfo = new Font(baseFontRegular, 8.5f, Font.NORMAL, SECONDARY_COLOR);
            Font fontLabel = new Font(baseFontBold, 9, Font.BOLD, Color.BLACK);
            Font fontValue = new Font(baseFontRegular, 9, Font.NORMAL, Color.BLACK);
            Font fontTableHead = new Font(baseFontBold, 8.5f, Font.BOLD, PRIMARY_COLOR);
            Font fontTableBody = new Font(baseFontRegular, 8.5f, Font.NORMAL, Color.BLACK);
            Font fontTableBodyBold = new Font(baseFontBold, 8.5f, Font.BOLD, Color.BLACK);
            Font fontTotalLabel = new Font(baseFontBold, 10, Font.BOLD, PRIMARY_COLOR);
            Font fontTotalValue = new Font(baseFontBold, 11, Font.BOLD, TOTAL_COLOR);

            // 1. Header công ty & Tiêu đề hóa đơn
            PdfPTable headerTable = new PdfPTable(2);
            headerTable.setWidthPercentage(100);
            headerTable.setWidths(new float[]{60f, 40f});
            headerTable.setSpacingAfter(12);

            PdfPCell leftHeader = new PdfPCell();
            leftHeader.setBorder(Rectangle.NO_BORDER);
            leftHeader.addElement(new Paragraph("CÔNG TY CỔ PHẦN PHÂN PHỐI BIA NƯỚC GIẢI KHÁT", fontCompanyHeader));
            leftHeader.addElement(new Paragraph("Địa chỉ: 123 Đường Giải Phóng, Quận Hai Bà Trưng, Hà Nội", fontCompanyInfo));
            leftHeader.addElement(new Paragraph("Mã số thuế: 0101234567 | Hotline: 1900 6868", fontCompanyInfo));
            headerTable.addCell(leftHeader);

            PdfPCell rightHeader = new PdfPCell();
            rightHeader.setBorder(Rectangle.NO_BORDER);
            rightHeader.setHorizontalAlignment(Element.ALIGN_RIGHT);
            Paragraph pTitle = new Paragraph("HÓA ĐƠN BÁN HÀNG", fontTitle);
            pTitle.setAlignment(Element.ALIGN_RIGHT);
            rightHeader.addElement(pTitle);

            String statusText = invoice.getStatus() != null ? invoice.getStatus().getDisplayName() : "Đã phát hành";
            Paragraph pSub = new Paragraph("Số HĐ: " + invoice.getInvoiceNumber() + " | Trạng thái: " + statusText, fontSubTitle);
            pSub.setAlignment(Element.ALIGN_RIGHT);
            rightHeader.addElement(pSub);

            Paragraph pDate = new Paragraph("Ngày phát hành: " + (invoice.getInvoiceDate() != null ? invoice.getInvoiceDate().format(DATE_TIME_FORMATTER) : ""), fontCompanyInfo);
            pDate.setAlignment(Element.ALIGN_RIGHT);
            rightHeader.addElement(pDate);

            headerTable.addCell(rightHeader);
            document.add(headerTable);

            // Đường kẻ ngăn cách
            document.add(createDivider());

            // 2. Khối thông tin khách hàng / đại lý
            PdfPTable infoTable = new PdfPTable(2);
            infoTable.setWidthPercentage(100);
            infoTable.setWidths(new float[]{55f, 45f});
            infoTable.setSpacingBefore(8);
            infoTable.setSpacingAfter(10);

            PdfPCell custCell = new PdfPCell();
            custCell.setBorder(Rectangle.BOX);
            custCell.setBorderColor(BORDER_COLOR);
            custCell.setPadding(8);
            custCell.setBackgroundColor(HEADER_BG);

            custCell.addElement(new Paragraph("Đơn vị mua hàng: " + invoice.getDealerName(), fontLabel));
            custCell.addElement(new Paragraph("Mã đại lý: " + invoice.getDealerCode(), fontValue));
            custCell.addElement(new Paragraph("Mã số thuế: " + (invoice.getDealerTaxCode() != null ? invoice.getDealerTaxCode() : "---"), fontValue));
            custCell.addElement(new Paragraph("Địa chỉ: " + (invoice.getDealerAddress() != null ? invoice.getDealerAddress() : "---"), fontValue));
            custCell.addElement(new Paragraph("Điện thoại: " + (invoice.getDealerPhone() != null ? invoice.getDealerPhone() : "---"), fontValue));
            infoTable.addCell(custCell);

            PdfPCell orderCell = new PdfPCell();
            orderCell.setBorder(Rectangle.BOX);
            orderCell.setBorderColor(BORDER_COLOR);
            orderCell.setPadding(8);
            orderCell.setBackgroundColor(HEADER_BG);

            StringBuilder orderCodes = new StringBuilder();
            if (invoice.getOrders() != null && !invoice.getOrders().isEmpty()) {
                for (Order o : invoice.getOrders()) {
                    if (orderCodes.length() > 0) orderCodes.append(", ");
                    orderCodes.append(o.getOrderCode());
                }
            }
            orderCell.addElement(new Paragraph("Căn cứ phiếu giao hàng:", fontLabel));
            orderCell.addElement(new Paragraph(orderCodes.length() > 0 ? orderCodes.toString() : "Theo thỏa thuận giao hàng", fontValue));
            orderCell.addElement(new Paragraph("Mã hóa đơn: " + invoice.getInvoiceCode(), fontValue));
            orderCell.addElement(new Paragraph("Người lập: " + (invoice.getCreatedBy() != null ? invoice.getCreatedBy() : "Kế toán"), fontValue));
            if (invoice.getNote() != null && !invoice.getNote().isBlank()) {
                orderCell.addElement(new Paragraph("Ghi chú: " + invoice.getNote(), fontValue));
            }
            infoTable.addCell(orderCell);

            document.add(infoTable);

            // 3. Bảng chi tiết dòng hàng hóa đơn (theo số lượng thực giao)
            // Cột: STT (5%), Phiếu giao (15%), Mã hàng (12%), Tên sản phẩm (28%), ĐVT (8%), SL thực giao (10%), Đơn giá (10%), Thành tiền (12%)
            PdfPTable table = new PdfPTable(8);
            table.setWidthPercentage(100);
            table.setWidths(new float[]{5f, 15f, 12f, 26f, 8f, 10f, 11f, 13f});
            table.setSpacingBefore(5);
            table.setSpacingAfter(8);

            addCellHead(table, "STT", fontTableHead);
            addCellHead(table, "Phiếu giao", fontTableHead);
            addCellHead(table, "Mã hàng", fontTableHead);
            addCellHead(table, "Tên hàng hóa", fontTableHead);
            addCellHead(table, "ĐVT", fontTableHead);
            addCellHead(table, "SL thực giao", fontTableHead);
            addCellHead(table, "Đơn giá", fontTableHead);
            addCellHead(table, "Thành tiền", fontTableHead);

            int stt = 1;
            for (InvoiceItem item : invoice.getItems()) {
                Color bg = (stt % 2 == 0) ? ALT_ROW_BG : Color.WHITE;
                addCellBody(table, String.valueOf(stt++), fontTableBody, Element.ALIGN_CENTER, bg);
                addCellBody(table, item.getOrderCode() != null ? item.getOrderCode() : "-", fontTableBody, Element.ALIGN_LEFT, bg);
                addCellBody(table, item.getProductCode(), fontTableBody, Element.ALIGN_LEFT, bg);
                addCellBody(table, item.getProductName(), fontTableBody, Element.ALIGN_LEFT, bg);
                addCellBody(table, item.getUnit(), fontTableBody, Element.ALIGN_CENTER, bg);
                addCellBody(table, QTY_FORMAT.format(item.getInvoicedQuantity()), fontTableBodyBold, Element.ALIGN_RIGHT, bg);
                addCellBody(table, MONEY_FORMAT.format(item.getUnitPrice()), fontTableBody, Element.ALIGN_RIGHT, bg);
                addCellBody(table, MONEY_FORMAT.format(item.getTotalAmount()), fontTableBodyBold, Element.ALIGN_RIGHT, bg);
            }

            document.add(table);

            // 4. Tổng kết tiền hàng
            PdfPTable totalTable = new PdfPTable(2);
            totalTable.setWidthPercentage(100);
            totalTable.setWidths(new float[]{65f, 35f});
            totalTable.setSpacingAfter(8);

            PdfPCell wordsCell = new PdfPCell();
            wordsCell.setBorder(Rectangle.BOX);
            wordsCell.setBorderColor(BORDER_COLOR);
            wordsCell.setPadding(8);
            wordsCell.setBackgroundColor(HEADER_BG);

            BigDecimal finalTotal = invoice.getAdjustedTotalAmount() != null ? invoice.getAdjustedTotalAmount() : invoice.getTotalAmount();
            String words = VietnameseNumberToWords.convertToWords(finalTotal);
            wordsCell.addElement(new Paragraph("Số tiền viết bằng chữ:", fontLabel));
            wordsCell.addElement(new Paragraph(words, fontTableBodyBold));
            if (invoice.getStatus() == InvoiceStatus.ADJUSTED) {
                wordsCell.addElement(new Paragraph("* Hóa đơn đã có chứng từ điều chỉnh. Số tiền hiển thị là số tiền sau điều chỉnh.", fontSubTitle));
            }
            totalTable.addCell(wordsCell);

            PdfPCell amountCell = new PdfPCell();
            amountCell.setBorder(Rectangle.BOX);
            amountCell.setBorderColor(BORDER_COLOR);
            amountCell.setPadding(8);
            amountCell.setBackgroundColor(HEADER_BG);

            amountCell.addElement(createSummaryRow("Cộng tiền hàng:", MONEY_FORMAT.format(invoice.getSubtotalAmount()), fontTableBody, fontTableBodyBold));
            if (invoice.getDiscountAmount() != null && invoice.getDiscountAmount().compareTo(BigDecimal.ZERO) > 0) {
                amountCell.addElement(createSummaryRow("Chiết khấu:", "-" + MONEY_FORMAT.format(invoice.getDiscountAmount()), fontTableBody, fontTableBodyBold));
            }
            amountCell.addElement(createSummaryRow("Tổng cộng thanh toán:", MONEY_FORMAT.format(finalTotal), fontTotalLabel, fontTotalValue));
            totalTable.addCell(amountCell);

            document.add(totalTable);

            // 5. Chữ ký các bên
            PdfPTable signTable = new PdfPTable(3);
            signTable.setWidthPercentage(100);
            signTable.setWidths(new float[]{33f, 34f, 33f});
            signTable.setSpacingBefore(15);

            addSignatureBox(signTable, "NGƯỜI MUA HÀNG", "(Ký, ghi rõ họ tên)", fontLabel, fontSubTitle);
            addSignatureBox(signTable, "NGƯỜI LẬP HÓA ĐƠN", "(Ký, ghi rõ họ tên)", fontLabel, fontSubTitle);
            addSignatureBox(signTable, "THỦ TRƯỞNG ĐƠN VỊ", "(Ký, đóng dấu, ghi rõ họ tên)", fontLabel, fontSubTitle);

            document.add(signTable);

            document.close();
            return baos.toByteArray();
        } catch (Exception e) {
            log.error("Lỗi khi xuất PDF hóa đơn {}: {}", invoiceId, e.getMessage(), e);
            throw new RuntimeException("Không thể tạo file PDF hóa đơn: " + e.getMessage(), e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] generateAdjustmentPdf(Long adjustmentId, String username) {
        InvoiceAdjustment adj = adjustmentRepository.findById(adjustmentId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy chứng từ điều chỉnh với ID: " + adjustmentId));

        Invoice invoice = adj.getInvoice();

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            Document document = new Document(PageSize.A4, 28, 28, 30, 36);
            PdfWriter.getInstance(document, baos);
            document.open();

            BaseFont baseFontRegular = getBaseFont(false);
            BaseFont baseFontBold = getBaseFont(true);

            Font fontTitle = new Font(baseFontBold, 15, Font.BOLD, PRIMARY_COLOR);
            Font fontSubTitle = new Font(baseFontRegular, 9, Font.ITALIC, SECONDARY_COLOR);
            Font fontCompanyHeader = new Font(baseFontBold, 11, Font.BOLD, PRIMARY_COLOR);
            Font fontCompanyInfo = new Font(baseFontRegular, 8.5f, Font.NORMAL, SECONDARY_COLOR);
            Font fontLabel = new Font(baseFontBold, 9, Font.BOLD, Color.BLACK);
            Font fontValue = new Font(baseFontRegular, 9, Font.NORMAL, Color.BLACK);
            Font fontTableHead = new Font(baseFontBold, 8.5f, Font.BOLD, PRIMARY_COLOR);
            Font fontTableBody = new Font(baseFontRegular, 8.5f, Font.NORMAL, Color.BLACK);
            Font fontTableBodyBold = new Font(baseFontBold, 8.5f, Font.BOLD, Color.BLACK);
            Font fontTotalLabel = new Font(baseFontBold, 10, Font.BOLD, PRIMARY_COLOR);
            Font fontTotalValue = new Font(baseFontBold, 11, Font.BOLD, TOTAL_COLOR);

            // Header
            PdfPTable headerTable = new PdfPTable(2);
            headerTable.setWidthPercentage(100);
            headerTable.setWidths(new float[]{60f, 40f});
            headerTable.setSpacingAfter(10);

            PdfPCell leftHeader = new PdfPCell();
            leftHeader.setBorder(Rectangle.NO_BORDER);
            leftHeader.addElement(new Paragraph("CÔNG TY CỔ PHẦN PHÂN PHỐI BIA NƯỚC GIẢI KHÁT", fontCompanyHeader));
            leftHeader.addElement(new Paragraph("Mã số thuế: 0101234567 | Hotline: 1900 6868", fontCompanyInfo));
            headerTable.addCell(leftHeader);

            PdfPCell rightHeader = new PdfPCell();
            rightHeader.setBorder(Rectangle.NO_BORDER);
            rightHeader.setHorizontalAlignment(Element.ALIGN_RIGHT);
            Paragraph pTitle = new Paragraph("CHỨNG TỪ ĐIỀU CHỈNH HÓA ĐƠN", fontTitle);
            pTitle.setAlignment(Element.ALIGN_RIGHT);
            rightHeader.addElement(pTitle);

            Paragraph pCode = new Paragraph("Số CT: " + adj.getAdjustmentCode(), fontSubTitle);
            pCode.setAlignment(Element.ALIGN_RIGHT);
            rightHeader.addElement(pCode);
            headerTable.addCell(rightHeader);
            document.add(headerTable);

            document.add(createDivider());

            // Thông tin hóa đơn gốc và lý do
            PdfPTable infoTable = new PdfPTable(2);
            infoTable.setWidthPercentage(100);
            infoTable.setWidths(new float[]{50f, 50f});
            infoTable.setSpacingBefore(8);
            infoTable.setSpacingAfter(10);

            PdfPCell c1 = new PdfPCell();
            c1.setBorder(Rectangle.BOX);
            c1.setBorderColor(BORDER_COLOR);
            c1.setPadding(8);
            c1.setBackgroundColor(HEADER_BG);
            c1.addElement(new Paragraph("Hóa đơn gốc: " + invoice.getInvoiceCode() + " (Số " + invoice.getInvoiceNumber() + ")", fontLabel));
            c1.addElement(new Paragraph("Đại lý: " + invoice.getDealerName() + " (" + invoice.getDealerCode() + ")", fontValue));
            c1.addElement(new Paragraph("Ngày điều chỉnh: " + adj.getAdjustmentDate().format(DATE_TIME_FORMATTER), fontValue));
            c1.addElement(new Paragraph("Người điều chỉnh: " + adj.getAdjustedBy(), fontValue));
            infoTable.addCell(c1);

            PdfPCell c2 = new PdfPCell();
            c2.setBorder(Rectangle.BOX);
            c2.setBorderColor(BORDER_COLOR);
            c2.setPadding(8);
            c2.setBackgroundColor(HEADER_BG);
            c2.addElement(new Paragraph("Loại điều chỉnh: " + adj.getAdjustmentType().getDisplayName(), fontLabel));
            c2.addElement(new Paragraph("Lý do điều chỉnh (bắt buộc):", fontLabel));
            c2.addElement(new Paragraph(adj.getAdjustmentReason(), fontTableBodyBold));
            if (adj.getAdjustmentAmount() != null) {
                c2.addElement(new Paragraph("Số tiền điều chỉnh: " + MONEY_FORMAT.format(adj.getAdjustmentAmount()), fontTotalValue));
            }
            infoTable.addCell(c2);
            document.add(infoTable);

            // Bảng dòng điều chỉnh (nếu có)
            if (adj.getItems() != null && !adj.getItems().isEmpty()) {
                PdfPTable table = new PdfPTable(6);
                table.setWidthPercentage(100);
                table.setWidths(new float[]{8f, 18f, 32f, 12f, 15f, 15f});
                table.setSpacingAfter(10);

                addCellHead(table, "STT", fontTableHead);
                addCellHead(table, "Mã hàng", fontTableHead);
                addCellHead(table, "Tên hàng hóa", fontTableHead);
                addCellHead(table, "SL điều chỉnh", fontTableHead);
                addCellHead(table, "Giá điều chỉnh", fontTableHead);
                addCellHead(table, "Tiền điều chỉnh", fontTableHead);

                int stt = 1;
                for (InvoiceAdjustmentItem it : adj.getItems()) {
                    Color bg = (stt % 2 == 0) ? ALT_ROW_BG : Color.WHITE;
                    addCellBody(table, String.valueOf(stt++), fontTableBody, Element.ALIGN_CENTER, bg);
                    addCellBody(table, it.getProductCode() != null ? it.getProductCode() : "-", fontTableBody, Element.ALIGN_LEFT, bg);
                    addCellBody(table, it.getProductName() != null ? it.getProductName() : "-", fontTableBody, Element.ALIGN_LEFT, bg);
                    addCellBody(table, it.getAdjustedQuantity() != null ? QTY_FORMAT.format(it.getAdjustedQuantity()) : "-", fontTableBody, Element.ALIGN_RIGHT, bg);
                    addCellBody(table, it.getAdjustedPrice() != null ? MONEY_FORMAT.format(it.getAdjustedPrice()) : "-", fontTableBody, Element.ALIGN_RIGHT, bg);
                    addCellBody(table, MONEY_FORMAT.format(it.getAdjustmentAmount()), fontTableBodyBold, Element.ALIGN_RIGHT, bg);
                }
                document.add(table);
            }

            // Chữ ký
            PdfPTable signTable = new PdfPTable(3);
            signTable.setWidthPercentage(100);
            signTable.setWidths(new float[]{33f, 34f, 33f});
            signTable.setSpacingBefore(15);
            addSignatureBox(signTable, "NGƯỜI MUA HÀNG", "(Ký, ghi rõ họ tên)", fontLabel, fontSubTitle);
            addSignatureBox(signTable, "KẾ TOÁN CÔNG NỢ", "(Ký, ghi rõ họ tên)", fontLabel, fontSubTitle);
            addSignatureBox(signTable, "THỦ TRƯỞNG ĐƠN VỊ", "(Ký, đóng dấu, ghi rõ họ tên)", fontLabel, fontSubTitle);
            document.add(signTable);

            document.close();
            return baos.toByteArray();
        } catch (Exception e) {
            log.error("Lỗi khi xuất PDF chứng từ điều chỉnh {}: {}", adjustmentId, e.getMessage(), e);
            throw new RuntimeException("Không thể tạo file PDF chứng từ điều chỉnh: " + e.getMessage(), e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public String generateInvoicePrintHtml(Long invoiceId, String username) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy hóa đơn với ID: " + invoiceId));

        BigDecimal finalTotal = invoice.getAdjustedTotalAmount() != null ? invoice.getAdjustedTotalAmount() : invoice.getTotalAmount();
        String words = VietnameseNumberToWords.convertToWords(finalTotal);

        StringBuilder itemsHtml = new StringBuilder();
        int stt = 1;
        for (InvoiceItem item : invoice.getItems()) {
            itemsHtml.append(String.format("""
                <tr>
                    <td style="text-align:center;">%d</td>
                    <td>%s</td>
                    <td>%s</td>
                    <td>%s</td>
                    <td style="text-align:center;">%s</td>
                    <td style="text-align:right; font-weight:bold;">%s</td>
                    <td style="text-align:right;">%s</td>
                    <td style="text-align:right; font-weight:bold;">%s</td>
                </tr>
                """,
                    stt++,
                    item.getOrderCode() != null ? item.getOrderCode() : "-",
                    item.getProductCode(),
                    item.getProductName(),
                    item.getUnit(),
                    QTY_FORMAT.format(item.getInvoicedQuantity()),
                    MONEY_FORMAT.format(item.getUnitPrice()),
                    MONEY_FORMAT.format(item.getTotalAmount())
            ));
        }

        StringBuilder ordersList = new StringBuilder();
        if (invoice.getOrders() != null) {
            for (Order o : invoice.getOrders()) {
                if (ordersList.length() > 0) ordersList.append(", ");
                ordersList.append(o.getOrderCode());
            }
        }

        return String.format("""
            <!DOCTYPE html>
            <html lang="vi">
            <head>
                <meta charset="UTF-8">
                <title>Hóa đơn bán hàng %s</title>
                <style>
                    body { font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif; margin: 20px; color: #1e293b; }
                    .header { display: flex; justify-content: space-between; border-bottom: 2px solid #0f766e; padding-bottom: 12px; margin-bottom: 15px; }
                    .title { text-align: right; color: #182b49; }
                    .title h2 { margin: 0; font-size: 22px; }
                    .info-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 15px; margin-bottom: 15px; }
                    .info-box { border: 1px solid #cbd5e1; border-radius: 4px; padding: 10px; background: #f8fafc; font-size: 13px; line-height: 1.6; }
                    table { width: 100%%; border-collapse: collapse; margin-bottom: 15px; font-size: 13px; }
                    th, td { border: 1px solid #cbd5e1; padding: 6px 10px; }
                    th { background: #f1f5f9; color: #182b49; font-weight: 600; text-align: center; }
                    .totals { display: flex; justify-content: space-between; background: #f8fafc; border: 1px solid #cbd5e1; padding: 10px 15px; border-radius: 4px; margin-bottom: 20px; font-size: 14px; }
                    .signatures { display: grid; grid-template-columns: 1fr 1fr 1fr; text-align: center; margin-top: 30px; font-size: 13px; }
                    .sig-space { height: 70px; }
                    @media print {
                        .no-print { display: none; }
                        body { margin: 0; }
                    }
                </style>
            </head>
            <body>
                <div class="no-print" style="margin-bottom: 15px;">
                    <button onclick="window.print()" style="padding: 8px 16px; background:#0f766e; color:#fff; border:none; border-radius:4px; cursor:pointer;">In hóa đơn</button>
                </div>
                <div class="header">
                    <div>
                        <h3 style="margin:0 0 5px 0; color:#182b49;">CÔNG TY CỔ PHẦN PHÂN PHỐI BIA NƯỚC GIẢI KHÁT</h3>
                        <div style="font-size:12px; color:#64748b;">Địa chỉ: 123 Đường Giải Phóng, Hai Bà Trưng, Hà Nội</div>
                        <div style="font-size:12px; color:#64748b;">Mã số thuế: 0101234567 | Hotline: 1900 6868</div>
                    </div>
                    <div class="title">
                        <h2>HÓA ĐƠN BÁN HÀNG</h2>
                        <div>Số: <strong>%s</strong> (%s)</div>
                        <div style="font-size:12px; color:#64748b;">Ngày: %s</div>
                    </div>
                </div>
                <div class="info-grid">
                    <div class="info-box">
                        <strong>ĐƠN VỊ MUA HÀNG</strong><br>
                        Tên đại lý: <strong>%s</strong> (%s)<br>
                        Mã số thuế: %s<br>
                        Địa chỉ: %s<br>
                        Điện thoại: %s
                    </div>
                    <div class="info-box">
                        <strong>CHỨNG TỪ THAM CHIẾU</strong><br>
                        Phiếu giao hàng: %s<br>
                        Trạng thái: <strong>%s</strong><br>
                        Người lập: %s<br>
                        Ghi chú: %s
                    </div>
                </div>
                <table>
                    <thead>
                        <tr>
                            <th>STT</th>
                            <th>Phiếu giao</th>
                            <th>Mã hàng</th>
                            <th>Tên hàng hóa</th>
                            <th>ĐVT</th>
                            <th>SL thực giao</th>
                            <th>Đơn giá</th>
                            <th>Thành tiền</th>
                        </tr>
                    </thead>
                    <tbody>
                        %s
                    </tbody>
                </table>
                <div class="totals">
                    <div>
                        <strong>Số tiền viết bằng chữ:</strong><br>
                        <em>%s</em>
                    </div>
                    <div style="text-align: right;">
                        <div>Cộng tiền hàng: <strong>%s</strong></div>
                        %s
                        <div style="font-size: 16px; color:#b91c1c; margin-top:5px;"><strong>Tổng thanh toán: %s</strong></div>
                    </div>
                </div>
                <div class="signatures">
                    <div>
                        <strong>NGƯỜI MUA HÀNG</strong><br>
                        <small>(Ký, ghi rõ họ tên)</small>
                        <div class="sig-space"></div>
                    </div>
                    <div>
                        <strong>NGƯỜI LẬP HÓA ĐƠN</strong><br>
                        <small>(Ký, ghi rõ họ tên)</small>
                        <div class="sig-space"></div>
                    </div>
                    <div>
                        <strong>THỦ TRƯỞNG ĐƠN VỊ</strong><br>
                        <small>(Ký, đóng dấu)</small>
                        <div class="sig-space"></div>
                    </div>
                </div>
            </body>
            </html>
            """,
                invoice.getInvoiceCode(),
                invoice.getInvoiceNumber(),
                invoice.getInvoiceCode(),
                invoice.getInvoiceDate() != null ? invoice.getInvoiceDate().format(DATE_TIME_FORMATTER) : "",
                invoice.getDealerName(),
                invoice.getDealerCode(),
                invoice.getDealerTaxCode() != null ? invoice.getDealerTaxCode() : "---",
                invoice.getDealerAddress() != null ? invoice.getDealerAddress() : "---",
                invoice.getDealerPhone() != null ? invoice.getDealerPhone() : "---",
                ordersList.length() > 0 ? ordersList.toString() : "Theo thỏa thuận",
                invoice.getStatus() != null ? invoice.getStatus().getDisplayName() : "Đã phát hành",
                invoice.getCreatedBy() != null ? invoice.getCreatedBy() : "Kế toán",
                invoice.getNote() != null ? invoice.getNote() : "Không",
                itemsHtml.toString(),
                words,
                MONEY_FORMAT.format(invoice.getSubtotalAmount()),
                (invoice.getDiscountAmount() != null && invoice.getDiscountAmount().compareTo(BigDecimal.ZERO) > 0)
                        ? "<div>Chiết khấu: <strong>-" + MONEY_FORMAT.format(invoice.getDiscountAmount()) + "</strong></div>" : "",
                MONEY_FORMAT.format(finalTotal)
        );
    }

    private BaseFont getBaseFont(boolean bold) {
        String[] fontPaths = bold
                ? new String[]{"C:/Windows/Fonts/arialbd.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"}
                : new String[]{"C:/Windows/Fonts/arial.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"};

        for (String p : fontPaths) {
            try {
                File f = new File(p);
                if (f.exists()) {
                    return BaseFont.createFont(p, BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
                }
            } catch (Exception ignored) {
            }
        }
        try {
            return BaseFont.createFont(bold ? BaseFont.HELVETICA_BOLD : BaseFont.HELVETICA, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED);
        } catch (Exception e) {
            throw new IllegalStateException("Không thể khởi tạo font", e);
        }
    }

    private Paragraph createDivider() {
        Paragraph p = new Paragraph(" ");
        p.setSpacingBefore(1);
        p.setSpacingAfter(4);
        return p;
    }

    private void addCellHead(PdfPTable table, String text, Font font) {
        PdfPCell c = new PdfPCell(new Phrase(text, font));
        c.setBackgroundColor(HEADER_BG);
        c.setBorderColor(BORDER_COLOR);
        c.setHorizontalAlignment(Element.ALIGN_CENTER);
        c.setVerticalAlignment(Element.ALIGN_MIDDLE);
        c.setPadding(6);
        table.addCell(c);
    }

    private void addCellBody(PdfPTable table, String text, Font font, int align, Color bg) {
        PdfPCell c = new PdfPCell(new Phrase(text, font));
        c.setBackgroundColor(bg);
        c.setBorderColor(BORDER_COLOR);
        c.setHorizontalAlignment(align);
        c.setVerticalAlignment(Element.ALIGN_MIDDLE);
        c.setPadding(5);
        table.addCell(c);
    }

    private Paragraph createSummaryRow(String label, String value, Font labelFont, Font valueFont) {
        Paragraph p = new Paragraph();
        p.add(new Chunk(label + " ", labelFont));
        p.add(new Chunk(value, valueFont));
        p.setAlignment(Element.ALIGN_RIGHT);
        return p;
    }

    private void addSignatureBox(PdfPTable table, String title, String subtitle, Font titleFont, Font subFont) {
        PdfPCell c = new PdfPCell();
        c.setBorder(Rectangle.NO_BORDER);
        c.setHorizontalAlignment(Element.ALIGN_CENTER);
        Paragraph pTitle = new Paragraph(title, titleFont);
        pTitle.setAlignment(Element.ALIGN_CENTER);
        c.addElement(pTitle);

        Paragraph pSub = new Paragraph(subtitle, subFont);
        pSub.setAlignment(Element.ALIGN_CENTER);
        c.addElement(pSub);

        Paragraph space = new Paragraph("\n\n\n\n");
        c.addElement(space);

        table.addCell(c);
    }
}

