package com.quanlybanhang.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.oned.Code128Writer;
import com.lowagie.text.*;
import com.lowagie.text.Font;
import com.lowagie.text.Image;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.*;
import com.quanlybanhang.model.*;
import com.quanlybanhang.repository.OrderRepository;
import com.quanlybanhang.util.VietnameseNumberToWords;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
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
 * Triển khai dịch vụ xuất PDF và mã vạch cho Đơn hàng (S4-08).
 * Đáp ứng yêu cầu:
 * - Vai trò: Nhân viên kinh doanh (ROLE_SALES) và các vai trò liên quan.
 * - Mẫu in gồm thông tin đại lý, điểm giao, chi tiết dòng hàng, chiết khấu, tổng tiền.
 * - Có mã đơn và mã vạch (Code 128) để kho tra cứu nhanh.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderPdfServiceImpl implements OrderPdfService {

    private final OrderRepository orderRepository;

    private static final Color PRIMARY_COLOR = new Color(24, 43, 73);      // Navy/Deep Blue
    private static final Color SECONDARY_COLOR = new Color(71, 85, 105);   // Slate 600
    private static final Color HEADER_BG = new Color(241, 245, 249);       // Slate 100
    private static final Color ALT_ROW_BG = new Color(248, 250, 252);      // Slate 50
    private static final Color BORDER_COLOR = new Color(203, 213, 225);    // Slate 300
    private static final Color ACCENT_COLOR = new Color(15, 118, 110);     // Teal

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
    public byte[] generateOrderPdf(Long orderId, String username) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn hàng với ID: " + orderId));

        validateSalesViewAccess(order, username);

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            // Khổ A4, lề 28pt (khoảng 1cm) để tối ưu không gian in ấn phiếu
            Document document = new Document(PageSize.A4, 28, 28, 30, 36);
            PdfWriter writer = PdfWriter.getInstance(document, baos);

            // Bổ sung sự kiện đánh số trang ở footer
            writer.setPageEvent(new PdfFooterEvent(getBaseFont(false)));

            document.open();

            // Font styles
            BaseFont baseFontRegular = getBaseFont(false);
            BaseFont baseFontBold = getBaseFont(true);

            Font fontTitle = new Font(baseFontBold, 15, Font.BOLD, PRIMARY_COLOR);
            Font fontSubTitle = new Font(baseFontRegular, 8.5f, Font.ITALIC, SECONDARY_COLOR);
            Font fontCompanyHeader = new Font(baseFontBold, 11, Font.BOLD, PRIMARY_COLOR);
            Font fontCompanyInfo = new Font(baseFontRegular, 8, Font.NORMAL, SECONDARY_COLOR);
            Font fontSectionTitle = new Font(baseFontBold, 10, Font.BOLD, PRIMARY_COLOR);
            Font fontLabel = new Font(baseFontBold, 8.5f, Font.BOLD, Color.BLACK);
            Font fontValue = new Font(baseFontRegular, 8.5f, Font.NORMAL, Color.BLACK);
            Font fontTableHead = new Font(baseFontBold, 8.5f, Font.BOLD, PRIMARY_COLOR);
            Font fontTableBody = new Font(baseFontRegular, 8, Font.NORMAL, Color.BLACK);
            Font fontTableBodyBold = new Font(baseFontBold, 8, Font.BOLD, Color.BLACK);
            Font fontTotalLabel = new Font(baseFontBold, 9.5f, Font.BOLD, PRIMARY_COLOR);
            Font fontTotalValue = new Font(baseFontBold, 11, Font.BOLD, new Color(185, 28, 28)); // Đỏ sẫm nổi bật
            Font fontWords = new Font(baseFontRegular, 8.5f, Font.ITALIC, Color.BLACK);
            Font fontSignTitle = new Font(baseFontBold, 9, Font.BOLD, PRIMARY_COLOR);
            Font fontSignNote = new Font(baseFontRegular, 7.5f, Font.ITALIC, SECONDARY_COLOR);

            // 1. HEADER: Thông tin công ty & Mã vạch tra cứu
            PdfPTable headerTable = new PdfPTable(2);
            headerTable.setWidthPercentage(100);
            headerTable.setWidths(new float[]{60, 40});

            // Cột trái: Thông tin doanh nghiệp
            PdfPCell leftHeaderCell = new PdfPCell();
            leftHeaderCell.setBorder(Rectangle.NO_BORDER);
            leftHeaderCell.addElement(new Paragraph("CÔNG TY CỔ PHẦN PHÂN PHỐI BÁN HÀNG SALESTOCK", fontCompanyHeader));
            leftHeaderCell.addElement(new Paragraph("Địa chỉ: 123 Đường Điện Biên Phủ, Phường 15, Q. Bình Thạnh, TP. Hồ Chí Minh", fontCompanyInfo));
            leftHeaderCell.addElement(new Paragraph("Hotline: 1900 6868  |  Email: contact@salestock.vn  |  Website: salestock.vn", fontCompanyInfo));
            headerTable.addCell(leftHeaderCell);

            // Cột phải: Mã vạch Code 128 và mã đơn hàng
            PdfPCell rightHeaderCell = new PdfPCell();
            rightHeaderCell.setBorder(Rectangle.NO_BORDER);
            rightHeaderCell.setHorizontalAlignment(Element.ALIGN_RIGHT);

            byte[] barcodeBytes = generateBarcodePngBytes(order.getOrderCode(), 260, 48);
            if (barcodeBytes != null && barcodeBytes.length > 0) {
                Image barcodeImg = Image.getInstance(barcodeBytes);
                barcodeImg.scalePercent(65);
                barcodeImg.setAlignment(Element.ALIGN_RIGHT);
                rightHeaderCell.addElement(barcodeImg);
            }

            Paragraph orderCodePara = new Paragraph("Mã đơn: " + order.getOrderCode(), fontLabel);
            orderCodePara.setAlignment(Element.ALIGN_RIGHT);
            rightHeaderCell.addElement(orderCodePara);

            String createdDateStr = order.getCreatedAt() != null ? DATE_TIME_FORMATTER.format(order.getCreatedAt()) : "---";
            Paragraph createdDatePara = new Paragraph("Ngày tạo: " + createdDateStr, fontCompanyInfo);
            createdDatePara.setAlignment(Element.ALIGN_RIGHT);
            rightHeaderCell.addElement(createdDatePara);

            headerTable.addCell(rightHeaderCell);
            document.add(headerTable);

            // Đường kẻ ngang phân cách nhẹ
            document.add(createDivider());

            // 2. TIÊU ĐỀ PHIẾU
            Paragraph titlePara = new Paragraph("PHIẾU XÁC NHẬN ĐƠN HÀNG", fontTitle);
            titlePara.setAlignment(Element.ALIGN_CENTER);
            titlePara.setSpacingBefore(3f);
            document.add(titlePara);

            Paragraph subTitlePara = new Paragraph("(Mẫu in kiểm tra đơn hàng tại chỗ & mã vạch tra cứu kho)", fontSubTitle);
            subTitlePara.setAlignment(Element.ALIGN_CENTER);
            subTitlePara.setSpacingAfter(8f);
            document.add(subTitlePara);

            // 3. THÔNG TIN ĐẠI LÝ VÀ ĐIỂM GIAO HÀNG (2 CỘT)
            PdfPTable infoBoxTable = new PdfPTable(2);
            infoBoxTable.setWidthPercentage(100);
            infoBoxTable.setWidths(new float[]{50, 50});
            infoBoxTable.setSpacingAfter(8f);

            Dealer dealer = order.getDealer();
            DealerDeliveryPoint dp = order.getDeliveryPoint();

            // Cột Đại lý
            PdfPCell dealerCell = new PdfPCell();
            dealerCell.setBackgroundColor(HEADER_BG);
            dealerCell.setBorderColor(BORDER_COLOR);
            dealerCell.setPadding(6f);
            dealerCell.addElement(new Paragraph("THÔNG TIN ĐẠI LÝ / KHÁCH HÀNG", fontSectionTitle));

            String dealerName = dealer != null ? dealer.getName() : (order.getCustomerName() != null ? order.getCustomerName() : "Khách lẻ");
            String dealerCode = dealer != null ? dealer.getCode() : "---";
            String dealerGroup = order.getCustomerGroup() != null ? order.getCustomerGroup().getDisplayName() : (dealer != null && dealer.getCustomerGroup() != null ? dealer.getCustomerGroup().getDisplayName() : "---");
            String taxCode = dealer != null && dealer.getTaxCode() != null ? dealer.getTaxCode() : "---";
            String territoryName = dealer != null && dealer.getTerritory() != null ? dealer.getTerritory().getName() : "---";
            String salesStaff = dealer != null && dealer.getAssignedUser() != null ? dealer.getAssignedUser().getFullName() : (order.getCreatedBy() != null ? order.getCreatedBy() : "---");
            String dealerPhone = dealer != null && dealer.getPhone() != null ? dealer.getPhone() : "---";
            String dealerAddress = dealer != null && dealer.getAddress() != null ? dealer.getAddress() : "---";

            addInfoRow(dealerCell, "Tên đại lý:", dealerName, fontLabel, fontValue);
            addInfoRow(dealerCell, "Mã đại lý:", dealerCode + "  (" + dealerGroup + ")", fontLabel, fontValue);
            addInfoRow(dealerCell, "Mã số thuế:", taxCode, fontLabel, fontValue);
            addInfoRow(dealerCell, "Khu vực:", territoryName, fontLabel, fontValue);
            addInfoRow(dealerCell, "NVKD phụ trách:", salesStaff, fontLabel, fontValue);
            addInfoRow(dealerCell, "SĐT / Địa chỉ:", dealerPhone + " - " + dealerAddress, fontLabel, fontValue);

            infoBoxTable.addCell(dealerCell);

            // Cột Điểm giao hàng
            PdfPCell deliveryCell = new PdfPCell();
            deliveryCell.setBackgroundColor(HEADER_BG);
            deliveryCell.setBorderColor(BORDER_COLOR);
            deliveryCell.setPadding(6f);
            deliveryCell.addElement(new Paragraph("THÔNG TIN ĐIỂM GIAO HÀNG", fontSectionTitle));

            String receiverName = dp != null ? dp.getReceiverName() : dealerName;
            String receiverPhone = dp != null ? dp.getPhone() : dealerPhone;
            String deliveryAddress = dp != null ? dp.getAddress() : dealerAddress;
            String directionNote = dp != null && dp.getDirectionNote() != null ? dp.getDirectionNote() : "---";
            String expectedDeliveryDate = order.getExpectedDeliveryDate() != null ? DATE_FORMATTER.format(order.getExpectedDeliveryDate()) : "Giao sớm nhất";
            String statusStr = mapStatusToVietnamese(order.getStatus());

            addInfoRow(deliveryCell, "Người nhận hàng:", receiverName, fontLabel, fontValue);
            addInfoRow(deliveryCell, "SĐT nhận hàng:", receiverPhone, fontLabel, fontValue);
            addInfoRow(deliveryCell, "Địa chỉ giao:", deliveryAddress, fontLabel, fontValue);
            addInfoRow(deliveryCell, "Chỉ dẫn giao hàng:", directionNote, fontLabel, fontValue);
            addInfoRow(deliveryCell, "Ngày giao dự kiến:", expectedDeliveryDate, fontLabel, fontValue);
            addInfoRow(deliveryCell, "Trạng thái đơn:", statusStr, fontLabel, fontValue);

            infoBoxTable.addCell(deliveryCell);
            document.add(infoBoxTable);

            // 4. CHI TIẾT DÒNG HÀNG (ITEMS TABLE)
            Paragraph tableTitle = new Paragraph("CHI TIẾT DÒNG SẢN PHẨM", fontSectionTitle);
            tableTitle.setSpacingAfter(4f);
            document.add(tableTitle);

            // 10 cột: STT, Mã SKU, Tên sản phẩm, ĐVT, SL, Quy đổi cơ sở, Đơn giá, Thành tiền, Chiết khấu, Tổng cộng
            PdfPTable itemsTable = new PdfPTable(10);
            itemsTable.setWidthPercentage(100);
            itemsTable.setWidths(new float[]{24, 52, 140, 42, 38, 55, 62, 70, 55, 75});
            itemsTable.setHeaderRows(1);

            // Headers
            addHeaderCell(itemsTable, "STT", fontTableHead, Element.ALIGN_CENTER);
            addHeaderCell(itemsTable, "Mã SKU", fontTableHead, Element.ALIGN_CENTER);
            addHeaderCell(itemsTable, "Tên sản phẩm", fontTableHead, Element.ALIGN_LEFT);
            addHeaderCell(itemsTable, "ĐVT", fontTableHead, Element.ALIGN_CENTER);
            addHeaderCell(itemsTable, "SL", fontTableHead, Element.ALIGN_RIGHT);
            addHeaderCell(itemsTable, "Quy đổi", fontTableHead, Element.ALIGN_RIGHT);
            addHeaderCell(itemsTable, "Đơn giá", fontTableHead, Element.ALIGN_RIGHT);
            addHeaderCell(itemsTable, "Thành tiền", fontTableHead, Element.ALIGN_RIGHT);
            addHeaderCell(itemsTable, "Chiết khấu", fontTableHead, Element.ALIGN_RIGHT);
            addHeaderCell(itemsTable, "Tổng cộng", fontTableHead, Element.ALIGN_RIGHT);

            int index = 1;
            BigDecimal totalSubtotal = BigDecimal.ZERO;
            BigDecimal totalDiscount = BigDecimal.ZERO;
            BigDecimal finalTotal = BigDecimal.ZERO;

            if (order.getItems() != null && !order.getItems().isEmpty()) {
                for (OrderItem item : order.getItems()) {
                    Color rowBg = (index % 2 == 0) ? ALT_ROW_BG : Color.WHITE;

                    String sku = item.getProduct() != null ? item.getProduct().getCode() : "---";
                    String name = item.getProduct() != null ? item.getProduct().getName() : "---";
                    String unit = item.getUnit() != null ? item.getUnit() : "---";
                    String qtyStr = item.getQuantity() != null ? QTY_FORMAT.format(item.getQuantity()) : "0";

                    // Quy đổi về đơn vị cơ sở
                    String baseConversionStr = "";
                    if (item.getBaseQuantity() != null && item.getBaseUnit() != null) {
                        baseConversionStr = QTY_FORMAT.format(item.getBaseQuantity()) + " " + item.getBaseUnit();
                    } else {
                        baseConversionStr = qtyStr + " " + unit;
                    }

                    BigDecimal price = item.getPrice() != null ? item.getPrice() : BigDecimal.ZERO;
                    BigDecimal subtotal = item.getSubtotalAmount() != null ? item.getSubtotalAmount() : price.multiply(item.getQuantity() != null ? item.getQuantity() : BigDecimal.ZERO);
                    BigDecimal discount = item.getDiscountAmount() != null ? item.getDiscountAmount() : BigDecimal.ZERO;
                    BigDecimal itemTotal = item.getTotalAmount() != null ? item.getTotalAmount() : subtotal.subtract(discount);

                    totalSubtotal = totalSubtotal.add(subtotal);
                    totalDiscount = totalDiscount.add(discount);
                    finalTotal = finalTotal.add(itemTotal);

                    addBodyCell(itemsTable, String.valueOf(index++), fontTableBody, Element.ALIGN_CENTER, rowBg);
                    addBodyCell(itemsTable, sku, fontTableBodyBold, Element.ALIGN_CENTER, rowBg);
                    addBodyCell(itemsTable, name, fontTableBody, Element.ALIGN_LEFT, rowBg);
                    addBodyCell(itemsTable, unit, fontTableBody, Element.ALIGN_CENTER, rowBg);
                    addBodyCell(itemsTable, qtyStr, fontTableBodyBold, Element.ALIGN_RIGHT, rowBg);
                    addBodyCell(itemsTable, baseConversionStr, fontTableBody, Element.ALIGN_RIGHT, rowBg);
                    addBodyCell(itemsTable, MONEY_FORMAT.format(price), fontTableBody, Element.ALIGN_RIGHT, rowBg);
                    addBodyCell(itemsTable, MONEY_FORMAT.format(subtotal), fontTableBody, Element.ALIGN_RIGHT, rowBg);
                    addBodyCell(itemsTable, discount.compareTo(BigDecimal.ZERO) > 0 ? ("-" + MONEY_FORMAT.format(discount)) : "0 đ", fontTableBody, Element.ALIGN_RIGHT, rowBg);
                    addBodyCell(itemsTable, MONEY_FORMAT.format(itemTotal), fontTableBodyBold, Element.ALIGN_RIGHT, rowBg);
                }
            } else {
                PdfPCell emptyCell = new PdfPCell(new Phrase("Không có dòng sản phẩm nào trong đơn hàng", fontTableBody));
                emptyCell.setColspan(10);
                emptyCell.setHorizontalAlignment(Element.ALIGN_CENTER);
                emptyCell.setPadding(10f);
                itemsTable.addCell(emptyCell);
            }

            document.add(itemsTable);

            // 5. TỔNG TIỀN VÀ CHIẾT KHẤU ĐƠN HÀNG
            BigDecimal orderSubtotal = order.getSubtotalAmount() != null ? order.getSubtotalAmount() : totalSubtotal;
            BigDecimal orderDiscount = order.getDiscountAmount() != null ? order.getDiscountAmount() : totalDiscount;
            BigDecimal orderTotal = order.getTotalAmount() != null ? order.getTotalAmount() : finalTotal;

            PdfPTable summaryTable = new PdfPTable(2);
            summaryTable.setWidthPercentage(100);
            summaryTable.setWidths(new float[]{60, 40});
            summaryTable.setSpacingBefore(6f);

            // Cột trái: Ghi chú đơn hàng & số tiền bằng chữ
            PdfPCell notesCell = new PdfPCell();
            notesCell.setBorder(Rectangle.NO_BORDER);
            notesCell.setPaddingRight(10f);

            Paragraph inWords = new Paragraph("Số tiền bằng chữ: " + VietnameseNumberToWords.convertToWords(orderTotal), fontWords);
            notesCell.addElement(inWords);

            if (order.getNote() != null && !order.getNote().isBlank()) {
                Paragraph notePara = new Paragraph("Ghi chú đơn hàng: " + order.getNote(), fontTableBody);
                notePara.setSpacingBefore(3f);
                notesCell.addElement(notePara);
            }
            summaryTable.addCell(notesCell);

            // Cột phải: Bảng tổng hợp tiền
            PdfPCell totalsCell = new PdfPCell();
            totalsCell.setBorder(Rectangle.BOX);
            totalsCell.setBorderColor(BORDER_COLOR);
            totalsCell.setBackgroundColor(HEADER_BG);
            totalsCell.setPadding(6f);

            PdfPTable totalsInnerTable = new PdfPTable(2);
            totalsInnerTable.setWidthPercentage(100);
            totalsInnerTable.setWidths(new float[]{55, 45});

            addSummaryRow(totalsInnerTable, "Tổng tiền hàng:", MONEY_FORMAT.format(orderSubtotal), fontValue, fontTableBodyBold);
            addSummaryRow(totalsInnerTable, "Chiết khấu sản lượng:", orderDiscount.compareTo(BigDecimal.ZERO) > 0 ? ("-" + MONEY_FORMAT.format(orderDiscount)) : "0 đ", fontValue, fontTableBodyBold);
            addSummaryRow(totalsInnerTable, "TỔNG TIỀN THANH TOÁN:", MONEY_FORMAT.format(orderTotal), fontTotalLabel, fontTotalValue);

            totalsCell.addElement(totalsInnerTable);
            summaryTable.addCell(totalsCell);

            document.add(summaryTable);

            // 6. PHẦN CHỮ KÝ XÁC NHẬN (ĐẠI LÝ, NVKD, KHO)
            document.add(new Paragraph(" ", new Font(baseFontRegular, 4)));

            PdfPTable signatureTable = new PdfPTable(3);
            signatureTable.setWidthPercentage(100);
            signatureTable.setWidths(new float[]{33.33f, 33.33f, 33.34f});
            signatureTable.setKeepTogether(true);
            signatureTable.setSpacingBefore(8f);

            addSignatureBox(signatureTable, "ĐẠI DIỆN ĐẠI LÝ", "(Ký & ghi rõ họ tên)", "(Đã kiểm tra và đồng ý các thông tin trên)", fontSignTitle, fontSignNote);
            addSignatureBox(signatureTable, "NHÂN VIÊN KINH DOANH", "(Ký & ghi rõ họ tên)", "(Cam kết đúng chính sách giá & chiết khấu)", fontSignTitle, fontSignNote);
            addSignatureBox(signatureTable, "BỘ PHẬN KHO / GIAO NHẬN", "(Ký & ghi rõ họ tên)", "(Quét mã vạch tra cứu khi xuất hàng)", fontSignTitle, fontSignNote);

            document.add(signatureTable);

            document.close();
            return baos.toByteArray();
        } catch (AccessDeniedException e) {
            throw e;
        } catch (Exception e) {
            log.error("Lỗi khi tạo file PDF cho đơn hàng ID {}: {}", orderId, e.getMessage(), e);
            throw new IllegalStateException("Không thể tạo file PDF đơn hàng: " + e.getMessage(), e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] generateOrderBarcode(Long orderId, String username) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn hàng với ID: " + orderId));

        validateSalesViewAccess(order, username);

        byte[] barcodeBytes = generateBarcodePngBytes(order.getOrderCode(), 320, 90);
        if (barcodeBytes == null || barcodeBytes.length == 0) {
            throw new IllegalStateException("Không thể tạo mã vạch cho mã đơn: " + order.getOrderCode());
        }
        return barcodeBytes;
    }

    private byte[] generateBarcodePngBytes(String code, int width, int height) {
        if (code == null || code.isBlank()) {
            return new byte[0];
        }
        try {
            Code128Writer writer = new Code128Writer();
            BitMatrix bitMatrix = writer.encode(code, BarcodeFormat.CODE_128, width, height);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(bitMatrix, "PNG", baos);
            return baos.toByteArray();
        } catch (Exception e) {
            log.warn("Lỗi tạo ảnh mã vạch Code 128 cho chuỗi '{}': {}", code, e.getMessage());
            return new byte[0];
        }
    }

    private void addInfoRow(PdfPCell parentCell, String label, String value, Font fontLabel, Font fontValue) {
        Paragraph p = new Paragraph();
        p.add(new Chunk(label + " ", fontLabel));
        p.add(new Chunk(value != null ? value : "---", fontValue));
        p.setSpacingAfter(2f);
        parentCell.addElement(p);
    }

    private void addHeaderCell(PdfPTable table, String text, Font font, int alignment) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setBackgroundColor(HEADER_BG);
        cell.setBorderColor(BORDER_COLOR);
        cell.setHorizontalAlignment(alignment);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        cell.setPaddingTop(5f);
        cell.setPaddingBottom(5f);
        table.addCell(cell);
    }

    private void addBodyCell(PdfPTable table, String text, Font font, int alignment, Color bg) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setBackgroundColor(bg);
        cell.setBorderColor(BORDER_COLOR);
        cell.setHorizontalAlignment(alignment);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        cell.setPaddingTop(4f);
        cell.setPaddingBottom(4f);
        cell.setPaddingLeft(3f);
        cell.setPaddingRight(3f);
        table.addCell(cell);
    }

    private void addSummaryRow(PdfPTable table, String label, String value, Font labelFont, Font valueFont) {
        PdfPCell lCell = new PdfPCell(new Phrase(label, labelFont));
        lCell.setBorder(Rectangle.NO_BORDER);
        lCell.setPadding(2.5f);
        table.addCell(lCell);

        PdfPCell vCell = new PdfPCell(new Phrase(value, valueFont));
        vCell.setBorder(Rectangle.NO_BORDER);
        vCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        vCell.setPadding(2.5f);
        table.addCell(vCell);
    }

    private void addSignatureBox(PdfPTable table, String title, String subTitle, String note, Font fontTitle, Font fontNote) {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);

        Paragraph pTitle = new Paragraph(title, fontTitle);
        pTitle.setAlignment(Element.ALIGN_CENTER);
        cell.addElement(pTitle);

        Paragraph pSub = new Paragraph(subTitle, fontNote);
        pSub.setAlignment(Element.ALIGN_CENTER);
        cell.addElement(pSub);

        Paragraph pNote = new Paragraph(note, fontNote);
        pNote.setAlignment(Element.ALIGN_CENTER);
        cell.addElement(pNote);

        // Khoảng trống ký tên
        Paragraph space = new Paragraph("\n\n\n", fontNote);
        cell.addElement(space);

        table.addCell(cell);
    }

    private Paragraph createDivider() {
        Paragraph divider = new Paragraph();
        divider.setSpacingBefore(3f);
        divider.setSpacingAfter(3f);
        PdfPTable line = new PdfPTable(1);
        line.setWidthPercentage(100);
        PdfPCell c = new PdfPCell();
        c.setBorder(Rectangle.BOTTOM);
        c.setBorderColor(BORDER_COLOR);
        c.setFixedHeight(1f);
        line.addCell(c);
        divider.add(line);
        return divider;
    }

    private String mapStatusToVietnamese(String status) {
        if (status == null) return "Chưa xác định";
        return switch (status.toUpperCase()) {
            case "RECORDED" -> "Đã ghi nhận";
            case "PENDING_APPROVAL" -> "Chờ duyệt (Dưới giá sàn / Quá hạn mức)";
            case "DRAFT" -> "Bản nháp";
            case "CANCELLED" -> "Đã hủy";
            case "REJECTED" -> "Bị từ chối";
            default -> status;
        };
    }

    /**
     * Nạp font hỗ trợ tiếng Việt đầy đủ.
     * Ưu tiên nạp font từ classpath (src/main/resources/fonts/arial.ttf)
     * hoặc fallback sang thư mục font hệ thống Windows / Linux / Standard font.
     */
    private BaseFont getBaseFont(boolean bold) {
        String fontFile = bold ? "fonts/arialbd.ttf" : "fonts/arial.ttf";
        try {
            ClassPathResource resource = new ClassPathResource(fontFile);
            if (resource.exists()) {
                byte[] fontBytes = resource.getInputStream().readAllBytes();
                return BaseFont.createFont(fontFile, BaseFont.IDENTITY_H, BaseFont.EMBEDDED, true, fontBytes, null);
            }
        } catch (Exception e) {
            log.debug("Không nạp được font từ classpath ({}): {}", fontFile, e.getMessage());
        }

        // Thử tìm trong Windows Fonts
        try {
            String winFont = bold ? "C:/Windows/Fonts/arialbd.ttf" : "C:/Windows/Fonts/arial.ttf";
            File f = new File(winFont);
            if (f.exists()) {
                return BaseFont.createFont(winFont, BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
            }
        } catch (Exception e) {
            log.debug("Không nạp được font Windows: {}", e.getMessage());
        }

        // Thử tìm trong Linux Fonts
        String[] linuxFonts = bold
                ? new String[]{"/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf"}
                : new String[]{"/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf"};
        for (String path : linuxFonts) {
            try {
                File f = new File(path);
                if (f.exists()) {
                    return BaseFont.createFont(path, BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
                }
            } catch (Exception ignored) {
            }
        }

        // Fallback chuẩn Helvetica
        try {
            return BaseFont.createFont(bold ? BaseFont.HELVETICA_BOLD : BaseFont.HELVETICA, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED);
        } catch (Exception e) {
            throw new IllegalStateException("Không thể khởi tạo font chữ cho PDF", e);
        }
    }

    /**
     * Kiểm tra phân quyền truy cập dòng dữ liệu (Row-Level Security) cho Nhân viên kinh doanh (ROLE_SALES).
     * Nhân viên kinh doanh chỉ được xem và in đơn hàng của đại lý mình phụ trách (hoặc do mình tạo).
     * Các vai trò Quản lý / Kế toán / Kho / Admin có quyền xem toàn công ty.
     */
    private void validateSalesViewAccess(Order order, String username) {
        if (username == null || username.isBlank() || "system".equalsIgnoreCase(username) || "admin".equalsIgnoreCase(username)) {
            return;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return;
        }
        boolean isElevated = auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority())
                        || "ROLE_SALES_MANAGER".equals(a.getAuthority())
                        || "ROLE_ACCOUNTANT".equals(a.getAuthority())
                        || "ROLE_VIEWER".equals(a.getAuthority())
                        || "ROLE_WAREHOUSE_MANAGER".equals(a.getAuthority())
                        || "ROLE_WAREHOUSE_STAFF".equals(a.getAuthority()));
        if (isElevated) {
            return;
        }

        boolean isSales = auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_SALES".equals(a.getAuthority()));
        if (!isSales) {
            return;
        }

        if (order.getCreatedBy() != null && order.getCreatedBy().equalsIgnoreCase(username)) {
            return;
        }

        Dealer dealer = order.getDealer();
        if (dealer != null && dealer.getAssignedUser() != null
                && dealer.getAssignedUser().getUsername() != null
                && dealer.getAssignedUser().getUsername().equalsIgnoreCase(username)) {
            return;
        }

        throw new AccessDeniedException("Bạn chỉ có quyền xem và in đơn hàng của đại lý do mình phụ trách");
    }

    /**
     * Sự kiện vẽ chân trang: Đánh số trang và ghi chú bản quyền.
     */
    private static class PdfFooterEvent extends PdfPageEventHelper {
        private final BaseFont font;

        public PdfFooterEvent(BaseFont font) {
            this.font = font;
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            PdfContentByte cb = writer.getDirectContent();
            cb.saveState();

            // Đường line mỏng ở footer
            cb.setLineWidth(0.5f);
            cb.setColorStroke(BORDER_COLOR);
            cb.moveTo(document.left(), document.bottom() - 6);
            cb.lineTo(document.right(), document.bottom() - 6);
            cb.stroke();

            // Text footer
            cb.beginText();
            cb.setFontAndSize(font, 8);
            cb.setColorFill(SECONDARY_COLOR);

            String footerNote = "Phiếu xác nhận đơn hàng - SaleStock Distribution System. Vui lòng kiểm tra kỹ trước khi ký nhận.";
            cb.showTextAligned(Element.ALIGN_LEFT, footerNote, document.left(), document.bottom() - 17, 0);

            String pageText = "Trang " + writer.getPageNumber();
            cb.showTextAligned(Element.ALIGN_RIGHT, pageText, document.right(), document.bottom() - 17, 0);

            cb.endText();
            cb.restoreState();
        }
    }
}
