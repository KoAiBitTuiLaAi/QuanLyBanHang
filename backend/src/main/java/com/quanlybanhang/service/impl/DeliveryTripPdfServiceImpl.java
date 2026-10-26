package com.quanlybanhang.service.impl;

import com.lowagie.text.*;
import com.lowagie.text.Font;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.*;
import com.quanlybanhang.dto.response.DeliveryTripManifestResponse;
import com.quanlybanhang.dto.response.DeliveryTripManifestResponse.ManifestItemResponse;
import com.quanlybanhang.dto.response.DeliveryTripManifestResponse.ManifestStopResponse;
import com.quanlybanhang.model.DeliveryTrip;
import com.quanlybanhang.model.DeliveryTripStop;
import com.quanlybanhang.model.User;
import com.quanlybanhang.repository.DeliveryTripRepository;
import com.quanlybanhang.repository.UserRepository;
import com.quanlybanhang.service.DeliveryTripPdfService;
import com.quanlybanhang.util.VietnameseNumberToWords;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;

/**
 * Triển khai in danh sách điểm giao của chuyến kèm địa chỉ và số điện thoại người nhận (S6-06).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeliveryTripPdfServiceImpl implements DeliveryTripPdfService {

    private final DeliveryTripRepository tripRepository;
    private final UserRepository userRepository;

    private static final Color PRIMARY_COLOR = new Color(24, 43, 73);      // Navy Blue
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

    private void checkAccess(DeliveryTrip trip) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new AccessDeniedException("Bạn chưa được xác thực");
        }

        Set<String> grants = new HashSet<>();
        auth.getAuthorities().forEach(a -> grants.add(a.getAuthority()));

        boolean admin = grants.contains("ROLE_ADMIN");
        boolean read = admin || grants.contains("WAREHOUSE_VIEW") || grants.contains("WAREHOUSE_EXPORT");

        if (!read) {
            throw new AccessDeniedException("Bạn không có quyền xem thông tin chuyến giao hàng (yêu cầu WAREHOUSE_VIEW hoặc WAREHOUSE_EXPORT)");
        }

        if (admin) {
            return;
        }

        User user = userRepository.findByUsername(auth.getName())
                .orElseThrow(() -> new AccessDeniedException("Không tìm thấy tài khoản người dùng"));

        Set<Long> assignedWarehouseIds = new HashSet<>();
        if (user.getWarehouses() != null) {
            user.getWarehouses().forEach(w -> assignedWarehouseIds.add(w.getId()));
        }

        boolean warehouseRole = grants.contains("ROLE_WAREHOUSE_STAFF") || grants.contains("ROLE_WAREHOUSE_MANAGER");
        if (assignedWarehouseIds.isEmpty() && !warehouseRole) {
            return; // Global non-warehouse staff
        }

        if (trip.getWarehouse() != null && !assignedWarehouseIds.contains(trip.getWarehouse().getId())) {
            throw new AccessDeniedException("Kho của chuyến giao này không thuộc phạm vi được giao");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public DeliveryTripManifestResponse getManifest(Long tripId, String username) {
        DeliveryTrip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy chuyến giao hàng với ID: " + tripId));

        checkAccess(trip);

        List<ManifestStopResponse> stopResponses = trip.getStops() != null
                ? trip.getStops().stream()
                .sorted(Comparator.comparing(DeliveryTripStop::getStopOrder, Comparator.nullsLast(Integer::compareTo)))
                .map(stop -> {
                    List<ManifestItemResponse> items = stop.getItems() != null
                            ? stop.getItems().stream().map(item -> ManifestItemResponse.builder()
                            .itemId(item.getId())
                            .productCode(item.getProductCode())
                            .productName(item.getProductName())
                            .unit(item.getUnit())
                            .quantity(item.getQuantity())
                            .unitPrice(item.getUnitPrice())
                            .totalPrice(item.getTotalPrice())
                            .build()).toList()
                            : List.of();

                    return ManifestStopResponse.builder()
                            .stopId(stop.getId())
                            .stopOrder(stop.getStopOrder())
                            .orderId(stop.getOrder() != null ? stop.getOrder().getId() : null)
                            .orderCode(stop.getOrderCode())
                            .voucherCode(stop.getVoucherCode())
                            .dealerId(stop.getDealer() != null ? stop.getDealer().getId() : null)
                            .dealerName(stop.getDealerName())
                            .receiverName(stop.getReceiverName())
                            .receiverPhone(stop.getReceiverPhone())
                            .deliveryAddress(stop.getDeliveryAddress())
                            .deliveryAmount(stop.getDeliveryAmount())
                            .status(stop.getStatus() != null ? stop.getStatus().name() : null)
                            .statusDisplayName(stop.getStatus() != null ? stop.getStatus().getDisplayName() : null)
                            .note(stop.getNote())
                            .items(items)
                            .build();
                }).toList()
                : List.of();

        return DeliveryTripManifestResponse.builder()
                .tripId(trip.getId())
                .tripCode(trip.getTripCode())
                .routeName(trip.getRouteName())
                .territoryId(trip.getTerritory() != null ? trip.getTerritory().getId() : null)
                .territoryName(trip.getTerritory() != null ? trip.getTerritory().getName() : null)
                .warehouseId(trip.getWarehouse() != null ? trip.getWarehouse().getId() : null)
                .warehouseCode(trip.getWarehouse() != null ? trip.getWarehouse().getCode() : null)
                .warehouseName(trip.getWarehouse() != null ? trip.getWarehouse().getName() : null)
                .warehouseAddress(trip.getWarehouse() != null ? trip.getWarehouse().getAddress() : null)
                .driverName(trip.getDriverName())
                .driverPhone(trip.getDriverPhone())
                .vehiclePlate(trip.getVehiclePlate())
                .departureTime(trip.getDepartureTime())
                .expectedCompletionTime(trip.getExpectedCompletionTime())
                .status(trip.getStatus() != null ? trip.getStatus().name() : null)
                .statusDisplayName(trip.getStatus() != null ? trip.getStatus().getDisplayName() : null)
                .note(trip.getNote())
                .totalStops(trip.getStops() != null ? trip.getStops().size() : 0)
                .totalAmount(trip.getTotalValue())
                .stops(stopResponses)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] generateManifestPdf(Long tripId, String username) {
        DeliveryTripManifestResponse manifest = getManifest(tripId, username);

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            Document document = new Document(PageSize.A4, 25, 25, 25, 30);
            PdfWriter writer = PdfWriter.getInstance(document, baos);

            document.open();

            BaseFont baseFontRegular = getBaseFont(false);
            BaseFont baseFontBold = getBaseFont(true);

            Font fontTitle = new Font(baseFontBold, 15, Font.BOLD, PRIMARY_COLOR);
            Font fontSubTitle = new Font(baseFontRegular, 8.5f, Font.ITALIC, SECONDARY_COLOR);
            Font fontCompanyHeader = new Font(baseFontBold, 10, Font.BOLD, PRIMARY_COLOR);
            Font fontCompanyInfo = new Font(baseFontRegular, 8f, Font.NORMAL, SECONDARY_COLOR);
            Font fontSectionHeader = new Font(baseFontBold, 9.5f, Font.BOLD, PRIMARY_COLOR);
            Font fontLabel = new Font(baseFontBold, 8.5f, Font.BOLD, Color.BLACK);
            Font fontValue = new Font(baseFontRegular, 8.5f, Font.NORMAL, Color.BLACK);
            Font fontTableHead = new Font(baseFontBold, 8f, Font.BOLD, PRIMARY_COLOR);
            Font fontTableBody = new Font(baseFontRegular, 8f, Font.NORMAL, Color.BLACK);
            Font fontTableBodyBold = new Font(baseFontBold, 8f, Font.BOLD, Color.BLACK);
            Font fontTotalLabel = new Font(baseFontBold, 9f, Font.BOLD, PRIMARY_COLOR);

            // 1. Header công ty & kho xuất
            PdfPTable headerTable = new PdfPTable(2);
            headerTable.setWidthPercentage(100);
            headerTable.setWidths(new float[]{60, 40});

            PdfPCell leftCell = new PdfPCell();
            leftCell.setBorder(Rectangle.NO_BORDER);
            leftCell.addElement(new Paragraph("HỆ THỐNG PHÂN PHỐI & BÁN HÀNG", fontCompanyHeader));
            String whInfo = "Kho xuất phát: " + (manifest.getWarehouseName() != null ? manifest.getWarehouseName() : "Kho trung tâm");
            if (manifest.getWarehouseAddress() != null && !manifest.getWarehouseAddress().isBlank()) {
                whInfo += " - " + manifest.getWarehouseAddress();
            }
            leftCell.addElement(new Paragraph(whInfo, fontCompanyInfo));
            headerTable.addCell(leftCell);

            PdfPCell rightCell = new PdfPCell();
            rightCell.setBorder(Rectangle.NO_BORDER);
            rightCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
            Paragraph dateP = new Paragraph("Ngày in: " + LocalDateTime.now().format(DATE_TIME_FORMATTER), fontCompanyInfo);
            dateP.setAlignment(Element.ALIGN_RIGHT);
            rightCell.addElement(dateP);
            headerTable.addCell(rightCell);

            document.add(headerTable);
            document.add(new Paragraph(" ", new Font(baseFontRegular, 4)));

            // 2. Tiêu đề
            Paragraph title = new Paragraph("BẢNG KÊ ĐIỂM GIAO HÀNG THEO CHUYẾN", fontTitle);
            title.setAlignment(Element.ALIGN_CENTER);
            document.add(title);

            String subInfo = "Mã chuyến: " + manifest.getTripCode();
            if (manifest.getRouteName() != null && !manifest.getRouteName().isBlank()) {
                subInfo += " | Tuyến: " + manifest.getRouteName();
            }
            if (manifest.getTerritoryName() != null && !manifest.getTerritoryName().isBlank()) {
                subInfo += " | Khu vực: " + manifest.getTerritoryName();
            }
            Paragraph sub = new Paragraph(subInfo, fontSubTitle);
            sub.setAlignment(Element.ALIGN_CENTER);
            sub.setSpacingAfter(8);
            document.add(sub);

            // 3. Thông tin chuyến & tài xế / xe
            PdfPTable tripInfoTable = new PdfPTable(4);
            tripInfoTable.setWidthPercentage(100);
            tripInfoTable.setWidths(new float[]{20, 30, 20, 30});

            addInfoCell(tripInfoTable, "Tài xế:", manifest.getDriverName(), fontLabel, fontValue);
            addInfoCell(tripInfoTable, "Biển số xe:", manifest.getVehiclePlate(), fontLabel, fontValue);
            addInfoCell(tripInfoTable, "SĐT tài xế:", manifest.getDriverPhone() != null ? manifest.getDriverPhone() : "Chưa cập nhật", fontLabel, fontValue);
            addInfoCell(tripInfoTable, "Khởi hành:", manifest.getDepartureTime() != null ? manifest.getDepartureTime().format(DATE_TIME_FORMATTER) : "Ngay", fontLabel, fontValue);
            addInfoCell(tripInfoTable, "Dự kiến xong:", manifest.getExpectedCompletionTime() != null ? manifest.getExpectedCompletionTime().format(DATE_TIME_FORMATTER) : "---", fontLabel, fontValue);
            addInfoCell(tripInfoTable, "Ghi chú chuyến:", manifest.getNote() != null ? manifest.getNote() : "Không", fontLabel, fontValue);

            document.add(tripInfoTable);
            document.add(new Paragraph(" ", new Font(baseFontRegular, 6)));

            // 4. Bảng danh sách điểm giao kèm địa chỉ và SĐT người nhận (S6-06)
            PdfPTable table = new PdfPTable(7);
            table.setWidthPercentage(100);
            table.setWidths(new float[]{5, 14, 18, 18, 25, 11, 9});

            addCellHead(table, "STT", fontTableHead);
            addCellHead(table, "Mã đơn/Phiếu", fontTableHead);
            addCellHead(table, "Đại lý / Khách hàng", fontTableHead);
            addCellHead(table, "Người nhận & SĐT", fontTableHead);
            addCellHead(table, "Địa chỉ giao hàng", fontTableHead);
            addCellHead(table, "Tiền thu hộ", fontTableHead);
            addCellHead(table, "Ký nhận", fontTableHead);

            int idx = 1;
            List<ManifestStopResponse> stops = manifest.getStops() != null ? manifest.getStops() : List.of();
            for (ManifestStopResponse stop : stops) {
                Color bg = (idx % 2 == 0) ? ALT_ROW_BG : Color.WHITE;

                addCellBody(table, String.valueOf(stop.getStopOrder() != null ? stop.getStopOrder() : idx), fontTableBody, Element.ALIGN_CENTER, bg);

                String code = stop.getOrderCode() != null ? stop.getOrderCode() : (stop.getVoucherCode() != null ? stop.getVoucherCode() : "---");
                addCellBody(table, code, fontTableBodyBold, Element.ALIGN_CENTER, bg);

                addCellBody(table, stop.getDealerName() != null ? stop.getDealerName() : "Khách lẻ", fontTableBody, Element.ALIGN_LEFT, bg);

                String contact = (stop.getReceiverName() != null ? stop.getReceiverName() : "")
                        + (stop.getReceiverPhone() != null && !stop.getReceiverPhone().isBlank() ? "\nSĐT: " + stop.getReceiverPhone() : "");
                addCellBody(table, contact, fontTableBody, Element.ALIGN_LEFT, bg);

                addCellBody(table, stop.getDeliveryAddress() != null ? stop.getDeliveryAddress() : "Tại kho", fontTableBody, Element.ALIGN_LEFT, bg);

                String amountStr = stop.getDeliveryAmount() != null ? MONEY_FORMAT.format(stop.getDeliveryAmount()) : "0 đ";
                addCellBody(table, amountStr, fontTableBodyBold, Element.ALIGN_RIGHT, bg);

                addCellBody(table, "", fontTableBody, Element.ALIGN_CENTER, bg); // Ô ký nhận trống
                idx++;
            }

            // Dòng tổng kết
            PdfPCell totalLabelCell = new PdfPCell(new Phrase("TỔNG CỘNG (" + stops.size() + " điểm giao):", fontTotalLabel));
            totalLabelCell.setColspan(5);
            totalLabelCell.setBackgroundColor(HEADER_BG);
            totalLabelCell.setBorderColor(BORDER_COLOR);
            totalLabelCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
            totalLabelCell.setPadding(5);
            table.addCell(totalLabelCell);

            PdfPCell totalAmountCell = new PdfPCell(new Phrase(MONEY_FORMAT.format(manifest.getTotalAmount() != null ? manifest.getTotalAmount() : BigDecimal.ZERO), fontTotalLabel));
            totalAmountCell.setBackgroundColor(HEADER_BG);
            totalAmountCell.setBorderColor(BORDER_COLOR);
            totalAmountCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
            totalAmountCell.setPadding(5);
            table.addCell(totalAmountCell);

            PdfPCell emptyTotalCell = new PdfPCell(new Phrase("", fontTableBody));
            emptyTotalCell.setBackgroundColor(HEADER_BG);
            emptyTotalCell.setBorderColor(BORDER_COLOR);
            table.addCell(emptyTotalCell);

            document.add(table);

            // Bằng chữ
            if (manifest.getTotalAmount() != null && manifest.getTotalAmount().compareTo(BigDecimal.ZERO) > 0) {
                String words = VietnameseNumberToWords.convertToWords(manifest.getTotalAmount());
                Paragraph pWords = new Paragraph("Số tiền bằng chữ: " + words, new Font(baseFontRegular, 8.5f, Font.ITALIC, SECONDARY_COLOR));
                pWords.setSpacingBefore(4);
                pWords.setSpacingAfter(10);
                document.add(pWords);
            } else {
                document.add(new Paragraph(" ", new Font(baseFontRegular, 6)));
            }

            // 5. Khối chữ ký (3 ô: Người lập, Tài xế, Quản lý kho)
            PdfPTable signTable = new PdfPTable(3);
            signTable.setWidthPercentage(100);
            signTable.setWidths(new float[]{33, 33, 34});

            addSignatureBox(signTable, "NGƯỜI LẬP BẢNG KÊ", "(Ký, ghi rõ họ tên)", fontLabel, fontSubTitle);
            addSignatureBox(signTable, "TÀI XẾ NHẬN HÀNG", "(Ký, ghi rõ họ tên)", fontLabel, fontSubTitle);
            addSignatureBox(signTable, "QUẢN LÝ KHO DUYỆT", "(Ký, ghi rõ họ tên)", fontLabel, fontSubTitle);

            document.add(signTable);

            document.close();
            return baos.toByteArray();
        } catch (Exception e) {
            log.error("Lỗi khi tạo file PDF bảng kê điểm giao ID {}: {}", tripId, e.getMessage(), e);
            throw new IllegalStateException("Không thể tạo file PDF bảng kê điểm giao: " + e.getMessage(), e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public String generateManifestPrintHtml(Long tripId, String username) {
        DeliveryTripManifestResponse m = getManifest(tripId, username);

        StringBuilder rows = new StringBuilder();
        int idx = 1;
        List<ManifestStopResponse> stops = m.getStops() != null ? m.getStops() : List.of();
        for (ManifestStopResponse s : stops) {
            String contact = "<strong>" + escape(s.getReceiverName() != null ? s.getReceiverName() : "") + "</strong>"
                    + (s.getReceiverPhone() != null && !s.getReceiverPhone().isBlank() ? "<br><span class='phone'>SĐT: " + escape(s.getReceiverPhone()) + "</span>" : "");

            rows.append("<tr>")
                    .append("<td class='text-center'>").append(s.getStopOrder() != null ? s.getStopOrder() : idx).append("</td>")
                    .append("<td class='text-center'><strong>").append(escape(s.getOrderCode() != null ? s.getOrderCode() : (s.getVoucherCode() != null ? s.getVoucherCode() : "---"))).append("</strong></td>")
                    .append("<td>").append(escape(s.getDealerName() != null ? s.getDealerName() : "Khách lẻ")).append("</td>")
                    .append("<td>").append(contact).append("</td>")
                    .append("<td>").append(escape(s.getDeliveryAddress() != null ? s.getDeliveryAddress() : "Tại kho")).append("</td>")
                    .append("<td class='text-right'><strong>").append(s.getDeliveryAmount() != null ? MONEY_FORMAT.format(s.getDeliveryAmount()) : "0 đ").append("</strong></td>")
                    .append("<td class='sign-box'></td>")
                    .append("</tr>");
            idx++;
        }

        String totalWords = (m.getTotalAmount() != null && m.getTotalAmount().compareTo(BigDecimal.ZERO) > 0)
                ? VietnameseNumberToWords.convertToWords(m.getTotalAmount())
                : "";

        return """
<!DOCTYPE html>
<html lang="vi">
<head>
    <meta charset="UTF-8">
    <title>Bảng kê điểm giao hàng - %s</title>
    <style>
        body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif; font-size: 13px; color: #1e293b; margin: 0; padding: 20px; }
        .manifest-container { max-width: 900px; margin: 0 auto; background: #fff; }
        .header { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 15px; border-bottom: 2px solid #0284c7; padding-bottom: 10px; }
        .title-block { text-align: center; margin: 15px 0; }
        .title { font-size: 20px; font-weight: bold; color: #0f172a; margin: 0; }
        .sub-title { font-size: 13px; color: #64748b; margin-top: 5px; }
        .info-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 8px 20px; background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 6px; padding: 12px 16px; margin-bottom: 15px; font-size: 13px; }
        .info-item { display: flex; }
        .info-label { font-weight: 600; width: 120px; color: #334155; }
        .info-value { flex: 1; color: #0f172a; }
        table { width: 100%%; border-collapse: collapse; margin-bottom: 15px; }
        th, td { border: 1px solid #cbd5e1; padding: 7px 8px; font-size: 12px; }
        th { background: #f1f5f9; color: #0f172a; font-weight: 600; text-align: center; }
        .text-center { text-align: center; }
        .text-right { text-align: right; }
        .phone { color: #0369a1; font-weight: 500; }
        .sign-box { height: 35px; min-width: 70px; }
        .total-row { background: #f8fafc; font-weight: bold; }
        .words { font-style: italic; color: #475569; margin-bottom: 25px; }
        .signatures { display: grid; grid-template-columns: repeat(3, 1fr); text-align: center; margin-top: 30px; page-break-inside: avoid; }
        .sign-title { font-weight: bold; font-size: 13px; }
        .sign-sub { font-size: 11px; color: #64748b; margin-top: 3px; }
        .sign-space { height: 60px; }
        @media print {
            body { padding: 0; }
            .no-print { display: none; }
            @page { size: A4; margin: 12mm; }
        }
    </style>
</head>
<body>
    <div class="manifest-container">
        <div class="header">
            <div>
                <strong style="font-size: 15px; color: #0f172a;">HỆ THỐNG PHÂN PHỐI & BÁN HÀNG</strong><br>
                <span>Kho: %s</span><br>
                <small style="color: #64748b;">%s</small>
            </div>
            <div style="text-align: right; font-size: 12px; color: #64748b;">
                Ngày in: %s
            </div>
        </div>

        <div class="title-block">
            <h1 class="title">BẢNG KÊ ĐIỂM GIAO HÀNG THEO CHUYẾN</h1>
            <div class="sub-title">Mã chuyến: <strong>%s</strong> %s %s</div>
        </div>

        <div class="info-grid">
            <div class="info-item"><span class="info-label">Tài xế:</span><span class="info-value"><strong>%s</strong></span></div>
            <div class="info-item"><span class="info-label">Biển số xe:</span><span class="info-value"><strong>%s</strong></span></div>
            <div class="info-item"><span class="info-label">SĐT tài xế:</span><span class="info-value">%s</span></div>
            <div class="info-item"><span class="info-label">Khởi hành:</span><span class="info-value">%s</span></div>
            <div class="info-item"><span class="info-label">Dự kiến xong:</span><span class="info-value">%s</span></div>
            <div class="info-item"><span class="info-label">Ghi chú:</span><span class="info-value">%s</span></div>
        </div>

        <table>
            <thead>
                <tr>
                    <th style="width: 5%%;">STT</th>
                    <th style="width: 14%%;">Mã đơn / Phiếu</th>
                    <th style="width: 18%%;">Đại lý / Khách hàng</th>
                    <th style="width: 18%%;">Người nhận & SĐT</th>
                    <th style="width: 25%%;">Địa chỉ giao hàng</th>
                    <th style="width: 11%%;">Tiền thu hộ</th>
                    <th style="width: 9%%;">Ký nhận</th>
                </tr>
            </thead>
            <tbody>
                %s
                <tr class="total-row">
                    <td colspan="5" class="text-right">TỔNG CỘNG (%d điểm giao):</td>
                    <td class="text-right">%s</td>
                    <td></td>
                </tr>
            </tbody>
        </table>

        <div class="words">Số tiền bằng chữ: %s</div>

        <div class="signatures">
            <div>
                <div class="sign-title">NGƯỜI LẬP BẢNG KÊ</div>
                <div class="sign-sub">(Ký, ghi rõ họ tên)</div>
                <div class="sign-space"></div>
            </div>
            <div>
                <div class="sign-title">TÀI XẾ NHẬN HÀNG</div>
                <div class="sign-sub">(Ký, ghi rõ họ tên)</div>
                <div class="sign-space"></div>
            </div>
            <div>
                <div class="sign-title">QUẢN LÝ KHO DUYỆT</div>
                <div class="sign-sub">(Ký, ghi rõ họ tên)</div>
                <div class="sign-space"></div>
            </div>
        </div>
    </div>
</body>
</html>
""".formatted(
                escape(m.getTripCode()),
                escape(m.getWarehouseName() != null ? m.getWarehouseName() : "Kho trung tâm"),
                escape(m.getWarehouseAddress() != null ? m.getWarehouseAddress() : ""),
                LocalDateTime.now().format(DATE_TIME_FORMATTER),
                escape(m.getTripCode()),
                m.getRouteName() != null ? " | Tuyến: " + escape(m.getRouteName()) : "",
                m.getTerritoryName() != null ? " | Khu vực: " + escape(m.getTerritoryName()) : "",
                escape(m.getDriverName()),
                escape(m.getVehiclePlate()),
                escape(m.getDriverPhone() != null ? m.getDriverPhone() : "Chưa cập nhật"),
                m.getDepartureTime() != null ? m.getDepartureTime().format(DATE_TIME_FORMATTER) : "Ngay",
                m.getExpectedCompletionTime() != null ? m.getExpectedCompletionTime().format(DATE_TIME_FORMATTER) : "---",
                escape(m.getNote() != null ? m.getNote() : "Không"),
                rows.toString(),
                stops.size(),
                MONEY_FORMAT.format(m.getTotalAmount() != null ? m.getTotalAmount() : BigDecimal.ZERO),
                totalWords.isBlank() ? "Không đồng" : totalWords
        );
    }

    private void addInfoCell(PdfPTable table, String label, String value, Font labelFont, Font valueFont) {
        PdfPCell cLabel = new PdfPCell(new Phrase(label, labelFont));
        cLabel.setBorder(Rectangle.NO_BORDER);
        cLabel.setPadding(3);
        table.addCell(cLabel);

        PdfPCell cValue = new PdfPCell(new Phrase(value != null ? value : "", valueFont));
        cValue.setBorder(Rectangle.NO_BORDER);
        cValue.setPadding(3);
        table.addCell(cValue);
    }

    private void addCellHead(PdfPTable table, String text, Font font) {
        PdfPCell c = new PdfPCell(new Phrase(text, font));
        c.setBackgroundColor(HEADER_BG);
        c.setBorderColor(BORDER_COLOR);
        c.setHorizontalAlignment(Element.ALIGN_CENTER);
        c.setVerticalAlignment(Element.ALIGN_MIDDLE);
        c.setPadding(5);
        table.addCell(c);
    }

    private void addCellBody(PdfPTable table, String text, Font font, int align, Color bg) {
        PdfPCell c = new PdfPCell(new Phrase(text != null ? text : "", font));
        c.setBackgroundColor(bg);
        c.setBorderColor(BORDER_COLOR);
        c.setHorizontalAlignment(align);
        c.setVerticalAlignment(Element.ALIGN_MIDDLE);
        c.setPadding(5);
        table.addCell(c);
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

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}

