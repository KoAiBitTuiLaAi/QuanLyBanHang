package com.quanlybanhang.service;

import com.quanlybanhang.dto.response.DealerDebtAgingLedgerResponse;
import com.quanlybanhang.dto.response.DealerDebtAgingRowResponse;
import com.quanlybanhang.dto.response.DealerDebtAgingSummaryResponse;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.Locale;

/**
 * Dịch vụ xuất file Excel (.xlsx) cho Sổ công nợ theo đại lý với phân tích tuổi nợ (S7-03).
 */
@Service
public class DealerDebtAgingExcelExportService {

    public byte[] exportToExcel(DealerDebtAgingLedgerResponse ledger) {
        if (ledger == null) {
            throw new IllegalArgumentException("Dữ liệu sổ công nợ theo đại lý không được để trống.");
        }

        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {

            StyleBundle styles = createStyleBundle(workbook);
            Sheet sheet = workbook.createSheet("Sổ công nợ và tuổi nợ");
            sheet.setDisplayGridlines(true);

            // Tiêu đề báo cáo
            Row titleRow = sheet.createRow(0);
            titleRow.setHeightInPoints(28);
            Cell titleCell = titleRow.createCell(0);
            titleCell.setCellValue("SỔ CÔNG NỢ THEO ĐẠI LÝ VÀ PHÂN TÍCH TUỔI NỢ");
            titleCell.setCellStyle(styles.titleStyle);
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 8));

            // Khối thông tin chung
            int r = 2;
            r = addInfoRow(sheet, styles, r, "Ngày chốt công nợ", ledger.getAsOfDate());
            String filterDesc = Boolean.TRUE.equals(ledger.getOverdueOnly())
                    ? "Chỉ lọc các đại lý có nợ quá hạn"
                    : "Tất cả đại lý có phát sinh công nợ";
            r = addInfoRow(sheet, styles, r, "Bộ lọc áp dụng", filterDesc);
            if (ledger.getSearch() != null && !ledger.getSearch().isBlank()) {
                r = addInfoRow(sheet, styles, r, "Từ khóa tìm kiếm", ledger.getSearch());
            }
            r = addInfoRow(sheet, styles, r, "Thời điểm xuất", ledger.getGeneratedAt());
            r = addInfoRow(sheet, styles, r, "Đơn vị tiền tệ", ledger.getCurrency() + " (VND)");

            r++;

            // Tiêu đề các cột
            String[] headers = {
                    "STT",
                    "Mã đại lý",
                    "Tên đại lý",
                    "Số điện thoại",
                    "Nhân viên phụ trách",
                    "Hạn mức nợ (VND)",
                    "Hạn nợ (ngày)",
                    "Phải thu (VND)",
                    "Đã thu (VND)",
                    "Còn nợ (VND)",
                    "Nợ trong hạn (VND)",
                    "Nợ quá hạn (VND)",
                    "Tuổi nợ 0–30 ngày (VND)",
                    "Tuổi nợ 31–60 ngày (VND)",
                    "Tuổi nợ 61–90 ngày (VND)",
                    "Tuổi nợ >90 ngày (VND)",
                    "Tuổi nợ max (ngày)",
                    "Quá hạn max (ngày)",
                    "Trạng thái"
            };

            Row headerRow = sheet.createRow(r++);
            headerRow.setHeightInPoints(26);
            for (int i = 0; i < headers.length; i++) {
                Cell c = headerRow.createCell(i);
                c.setCellValue(headers[i]);
                c.setCellStyle(styles.headerStyle);
            }

            int stt = 1;
            if (ledger.getDealers() != null) {
                for (DealerDebtAgingRowResponse rowData : ledger.getDealers()) {
                    Row row = sheet.createRow(r++);
                    row.setHeightInPoints(20);

                    // 0: STT
                    createCell(row, 0, stt++, styles.centerStyle);
                    // 1: Mã đại lý
                    createSafeTextCell(row, 1, rowData.getDealerCode(), styles.textStyle);
                    // 2: Tên đại lý
                    createSafeTextCell(row, 2, rowData.getDealerName(), styles.textStyle);
                    // 3: SĐT
                    createSafeTextCell(row, 3, rowData.getPhone(), styles.centerStyle);
                    // 4: NV phụ trách
                    createSafeTextCell(row, 4, rowData.getAssignedUserName(), styles.textStyle);

                    // 5: Hạn mức nợ
                    createMoneyCell(row, 5, rowData.getDebtLimit(), styles.moneyStyle);
                    // 6: Hạn nợ
                    createCell(row, 6, rowData.getMaxDebtDays() != null ? rowData.getMaxDebtDays() : 0, styles.centerStyle);

                    // 7: Phải thu
                    createMoneyCell(row, 7, rowData.getTotalReceivable(), styles.moneyStyle);
                    // 8: Đã thu
                    createMoneyCell(row, 8, rowData.getTotalPaid(), styles.moneyStyle);
                    // 9: Còn nợ
                    createMoneyCell(row, 9, rowData.getRemainingDebt(), styles.moneyBoldStyle);
                    // 10: Nợ trong hạn
                    createMoneyCell(row, 10, rowData.getInTermDebt(), styles.moneyStyle);
                    // 11: Nợ quá hạn
                    createMoneyCell(row, 11, rowData.getOverdueDebt(),
                            (rowData.getOverdueDebt() != null && rowData.getOverdueDebt().compareTo(BigDecimal.ZERO) > 0)
                                    ? styles.moneyRedStyle : styles.moneyStyle);

                    // 12-15: 4 nhóm tuổi nợ
                    createMoneyCell(row, 12, rowData.getBucket0To30(), styles.moneyStyle);
                    createMoneyCell(row, 13, rowData.getBucket31To60(), styles.moneyStyle);
                    createMoneyCell(row, 14, rowData.getBucket61To90(), styles.moneyStyle);
                    createMoneyCell(row, 15, rowData.getBucketOver90(),
                            (rowData.getBucketOver90() != null && rowData.getBucketOver90().compareTo(BigDecimal.ZERO) > 0)
                                    ? styles.moneyRedStyle : styles.moneyStyle);

                    // 16: Tuổi nợ tối đa
                    createCell(row, 16, rowData.getMaxDebtAgeDays() != null ? rowData.getMaxDebtAgeDays() : 0, styles.centerStyle);
                    // 17: Quá hạn tối đa
                    createCell(row, 17, rowData.getMaxOverdueDays() != null ? rowData.getMaxOverdueDays() : 0, styles.centerStyle);

                    // 18: Trạng thái
                    String statusLabel = Boolean.TRUE.equals(rowData.getIsOverdue()) ? "Quá hạn"
                            : (rowData.getRemainingDebt() != null && rowData.getRemainingDebt().compareTo(BigDecimal.ZERO) > 0) ? "Trong hạn" : "Hết nợ";
                    CellStyle statusStyle = Boolean.TRUE.equals(rowData.getIsOverdue()) ? styles.overdueStatusStyle : styles.centerStyle;
                    createSafeTextCell(row, 18, statusLabel, statusStyle);
                }
            }

            // DÒNG TỔNG CỘNG Ở CHÂN BẢNG
            DealerDebtAgingSummaryResponse summary = ledger.getSummary();
            Row totalRow = sheet.createRow(r++);
            totalRow.setHeightInPoints(24);

            Cell summaryLabelCell = totalRow.createCell(0);
            summaryLabelCell.setCellValue("TỔNG CỘNG");
            summaryLabelCell.setCellStyle(styles.summaryHeaderStyle);
            for (int i = 1; i <= 4; i++) {
                Cell emptyCell = totalRow.createCell(i);
                emptyCell.setCellStyle(styles.summaryHeaderStyle);
            }
            sheet.addMergedRegion(new CellRangeAddress(r - 1, r - 1, 0, 4));

            // Cột 5, 6: Trống
            totalRow.createCell(5).setCellStyle(styles.summaryMoneyStyle);
            totalRow.createCell(6).setCellStyle(styles.summaryMoneyStyle);

            if (summary != null) {
                // 7: Tổng phải thu
                createMoneyCell(totalRow, 7, summary.getTotalReceivable(), styles.summaryMoneyStyle);
                // 8: Tổng đã thu
                createMoneyCell(totalRow, 8, summary.getTotalPaid(), styles.summaryMoneyStyle);
                // 9: Tổng còn nợ
                createMoneyCell(totalRow, 9, summary.getTotalRemainingDebt(), styles.summaryMoneyBoldStyle);
                // 10: Tổng nợ trong hạn
                createMoneyCell(totalRow, 10, summary.getTotalInTermDebt(), styles.summaryMoneyStyle);
                // 11: Tổng nợ quá hạn
                createMoneyCell(totalRow, 11, summary.getTotalOverdueDebt(), styles.summaryMoneyRedStyle);

                // 12-15: Tổng 4 nhóm tuổi nợ
                createMoneyCell(totalRow, 12, summary.getTotalBucket0To30(), styles.summaryMoneyStyle);
                createMoneyCell(totalRow, 13, summary.getTotalBucket31To60(), styles.summaryMoneyStyle);
                createMoneyCell(totalRow, 14, summary.getTotalBucket61To90(), styles.summaryMoneyStyle);
                createMoneyCell(totalRow, 15, summary.getTotalBucketOver90(), styles.summaryMoneyRedStyle);
            } else {
                for (int i = 7; i <= 15; i++) {
                    totalRow.createCell(i).setCellStyle(styles.summaryMoneyStyle);
                }
            }

            // Cột 16-18 chân bảng: để trống có viền tổng
            for (int i = 16; i < headers.length; i++) {
                Cell c = totalRow.createCell(i);
                c.setCellStyle(styles.summaryMoneyStyle);
            }

            // Đặt độ rộng các cột tối ưu
            int[] colWidths = {6, 14, 30, 14, 22, 18, 14, 18, 18, 18, 18, 18, 20, 20, 20, 20, 16, 16, 14};
            for (int i = 0; i < colWidths.length && i < headers.length; i++) {
                sheet.setColumnWidth(i, colWidths[i] * 256);
            }

            sheet.createFreezePane(0, r - (ledger.getDealers() != null ? ledger.getDealers().size() : 0) - 1);

            workbook.write(outputStream);
            return outputStream.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Không thể tạo file Excel sổ công nợ theo đại lý.", ex);
        }
    }

    public String buildFileName(DealerDebtAgingLedgerResponse ledger) {
        String asOf = ledger != null && ledger.getAsOfDate() != null ? ledger.getAsOfDate() : "ngay-chot";
        String filterPart = (ledger != null && Boolean.TRUE.equals(ledger.getOverdueOnly())) ? "qua-han" : "tat-ca";
        if (ledger != null && ledger.getDealerId() != null) {
            filterPart += "_dl-" + ledger.getDealerId();
        }
        return "So-cong-no-tuoi-no_" + asOf + "_" + filterPart + ".xlsx";
    }

    private int addInfoRow(Sheet sheet, StyleBundle styles, int rowIdx, String label, String value) {
        Row row = sheet.createRow(rowIdx);
        Cell c0 = row.createCell(0);
        c0.setCellValue(label + ":");
        c0.setCellStyle(styles.infoLabelStyle);

        Cell c1 = row.createCell(1);
        c1.setCellValue(sanitizeFormula(value != null ? value : ""));
        c1.setCellStyle(styles.infoValueStyle);
        return rowIdx + 1;
    }

    private void createCell(Row row, int col, int value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    private void createSafeTextCell(Row row, int col, String text, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(sanitizeFormula(text != null ? text : ""));
        cell.setCellStyle(style);
    }

    private void createMoneyCell(Row row, int col, BigDecimal amount, CellStyle style) {
        Cell cell = row.createCell(col);
        if (amount == null) {
            cell.setCellValue(0.0);
            cell.setCellStyle(style);
            return;
        }

        // Nếu vượt quá 15 chữ số có nghĩa (giới hạn phần cứng IEEE-754 của Excel), lưu dạng text an toàn
        if (amount.stripTrailingZeros().precision() > 15) {
            cell.setCellValue(amount.toPlainString());
            cell.setCellStyle(style);
        } else {
            cell.setCellValue(amount.doubleValue());
            cell.setCellStyle(style);
        }
    }

    private String sanitizeFormula(String input) {
        if (input == null || input.isBlank()) return "";
        String trimmed = input.trim();
        if (trimmed.startsWith("=") || trimmed.startsWith("+") || trimmed.startsWith("-") || trimmed.startsWith("@")) {
            return "'" + trimmed;
        }
        return trimmed;
    }

    private StyleBundle createStyleBundle(XSSFWorkbook wb) {
        StyleBundle bundle = new StyleBundle();

        DataFormat dataFormat = wb.createDataFormat();
        short moneyFormat = dataFormat.getFormat("#,##0.00;[Red]-#,##0.00");

        // Font
        Font fontTitle = wb.createFont();
        fontTitle.setFontName("Arial");
        fontTitle.setFontHeightInPoints((short) 16);
        fontTitle.setBold(true);
        fontTitle.setColor(IndexedColors.DARK_BLUE.getIndex());

        Font fontHeader = wb.createFont();
        fontHeader.setFontName("Arial");
        fontHeader.setFontHeightInPoints((short) 10);
        fontHeader.setBold(true);
        fontHeader.setColor(IndexedColors.WHITE.getIndex());

        Font fontBold = wb.createFont();
        fontBold.setFontName("Arial");
        fontBold.setFontHeightInPoints((short) 10);
        fontBold.setBold(true);

        Font fontRed = wb.createFont();
        fontRed.setFontName("Arial");
        fontRed.setFontHeightInPoints((short) 10);
        fontRed.setColor(IndexedColors.RED.getIndex());
        fontRed.setBold(true);

        Font fontNormal = wb.createFont();
        fontNormal.setFontName("Arial");
        fontNormal.setFontHeightInPoints((short) 10);

        // Title Style
        bundle.titleStyle = wb.createCellStyle();
        bundle.titleStyle.setFont(fontTitle);
        bundle.titleStyle.setVerticalAlignment(VerticalAlignment.CENTER);

        // Header Style
        bundle.headerStyle = wb.createCellStyle();
        bundle.headerStyle.setFont(fontHeader);
        bundle.headerStyle.setFillForegroundColor(IndexedColors.ROYAL_BLUE.getIndex());
        bundle.headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        bundle.headerStyle.setAlignment(HorizontalAlignment.CENTER);
        bundle.headerStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        bundle.headerStyle.setWrapText(true);
        setBorders(bundle.headerStyle);

        // Info Styles
        bundle.infoLabelStyle = wb.createCellStyle();
        bundle.infoLabelStyle.setFont(fontBold);

        bundle.infoValueStyle = wb.createCellStyle();
        bundle.infoValueStyle.setFont(fontNormal);

        // Data Styles
        bundle.textStyle = wb.createCellStyle();
        bundle.textStyle.setFont(fontNormal);
        bundle.textStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        setBorders(bundle.textStyle);

        bundle.centerStyle = wb.createCellStyle();
        bundle.centerStyle.setFont(fontNormal);
        bundle.centerStyle.setAlignment(HorizontalAlignment.CENTER);
        bundle.centerStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        setBorders(bundle.centerStyle);

        bundle.overdueStatusStyle = wb.createCellStyle();
        bundle.overdueStatusStyle.setFont(fontRed);
        bundle.overdueStatusStyle.setAlignment(HorizontalAlignment.CENTER);
        bundle.overdueStatusStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        setBorders(bundle.overdueStatusStyle);

        bundle.moneyStyle = wb.createCellStyle();
        bundle.moneyStyle.setFont(fontNormal);
        bundle.moneyStyle.setDataFormat(moneyFormat);
        bundle.moneyStyle.setAlignment(HorizontalAlignment.RIGHT);
        bundle.moneyStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        setBorders(bundle.moneyStyle);

        bundle.moneyBoldStyle = wb.createCellStyle();
        bundle.moneyBoldStyle.setFont(fontBold);
        bundle.moneyBoldStyle.setDataFormat(moneyFormat);
        bundle.moneyBoldStyle.setAlignment(HorizontalAlignment.RIGHT);
        bundle.moneyBoldStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        setBorders(bundle.moneyBoldStyle);

        bundle.moneyRedStyle = wb.createCellStyle();
        bundle.moneyRedStyle.setFont(fontRed);
        bundle.moneyRedStyle.setDataFormat(moneyFormat);
        bundle.moneyRedStyle.setAlignment(HorizontalAlignment.RIGHT);
        bundle.moneyRedStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        setBorders(bundle.moneyRedStyle);

        // Summary Styles
        bundle.summaryHeaderStyle = wb.createCellStyle();
        bundle.summaryHeaderStyle.setFont(fontBold);
        bundle.summaryHeaderStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        bundle.summaryHeaderStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        bundle.summaryHeaderStyle.setAlignment(HorizontalAlignment.CENTER);
        bundle.summaryHeaderStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        setBorders(bundle.summaryHeaderStyle);

        bundle.summaryMoneyStyle = wb.createCellStyle();
        bundle.summaryMoneyStyle.setFont(fontBold);
        bundle.summaryMoneyStyle.setDataFormat(moneyFormat);
        bundle.summaryMoneyStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        bundle.summaryMoneyStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        bundle.summaryMoneyStyle.setAlignment(HorizontalAlignment.RIGHT);
        bundle.summaryMoneyStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        setBorders(bundle.summaryMoneyStyle);

        bundle.summaryMoneyBoldStyle = wb.createCellStyle();
        bundle.summaryMoneyBoldStyle.setFont(fontBold);
        bundle.summaryMoneyBoldStyle.setDataFormat(moneyFormat);
        bundle.summaryMoneyBoldStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        bundle.summaryMoneyBoldStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        bundle.summaryMoneyBoldStyle.setAlignment(HorizontalAlignment.RIGHT);
        bundle.summaryMoneyBoldStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        setBorders(bundle.summaryMoneyBoldStyle);

        bundle.summaryMoneyRedStyle = wb.createCellStyle();
        bundle.summaryMoneyRedStyle.setFont(fontRed);
        bundle.summaryMoneyRedStyle.setDataFormat(moneyFormat);
        bundle.summaryMoneyRedStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        bundle.summaryMoneyRedStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        bundle.summaryMoneyRedStyle.setAlignment(HorizontalAlignment.RIGHT);
        bundle.summaryMoneyRedStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        setBorders(bundle.summaryMoneyRedStyle);

        return bundle;
    }

    private void setBorders(CellStyle style) {
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        style.setTopBorderColor(IndexedColors.GREY_40_PERCENT.getIndex());
        style.setBottomBorderColor(IndexedColors.GREY_40_PERCENT.getIndex());
        style.setLeftBorderColor(IndexedColors.GREY_40_PERCENT.getIndex());
        style.setRightBorderColor(IndexedColors.GREY_40_PERCENT.getIndex());
    }

    private static class StyleBundle {
        CellStyle titleStyle;
        CellStyle headerStyle;
        CellStyle infoLabelStyle;
        CellStyle infoValueStyle;
        CellStyle textStyle;
        CellStyle centerStyle;
        CellStyle overdueStatusStyle;
        CellStyle moneyStyle;
        CellStyle moneyBoldStyle;
        CellStyle moneyRedStyle;
        CellStyle summaryHeaderStyle;
        CellStyle summaryMoneyStyle;
        CellStyle summaryMoneyBoldStyle;
        CellStyle summaryMoneyRedStyle;
    }
}

