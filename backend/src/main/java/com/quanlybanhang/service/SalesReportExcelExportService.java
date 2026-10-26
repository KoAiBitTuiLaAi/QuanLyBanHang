package com.quanlybanhang.service;

import com.quanlybanhang.dto.request.SalesReportFilterRequest;
import com.quanlybanhang.dto.response.SalesReportPageResponse;
import com.quanlybanhang.dto.response.SalesReportRowResponse;
import com.quanlybanhang.dto.response.SalesReportSummaryResponse;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Service xuất file Excel (.xlsx) cho Báo cáo doanh số đa chiều (S8-06).
 */
@Service
public class SalesReportExcelExportService {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    public byte[] exportToExcel(SalesReportFilterRequest filter, SalesReportPageResponse report, boolean canSeeMargin) {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {

            Sheet sheet = workbook.createSheet("Báo cáo doanh số");
            sheet.setDisplayGridlines(true);

            StyleBundle styles = createStyleBundle(workbook);

            String groupLabel = getGroupLabel(filter != null ? filter.getGroupBy() : null);
            boolean showCompare = filter != null && Boolean.TRUE.equals(filter.getCompare());

            // 1. Tiêu đề chính
            Row titleRow = sheet.createRow(0);
            titleRow.setHeightInPoints(26);
            Cell titleCell = titleRow.createCell(0);
            titleCell.setCellValue("BÁO CÁO DOANH SỐ THEO " + groupLabel.toUpperCase());
            titleCell.setCellStyle(styles.titleStyle);

            int totalCols = 4 + (canSeeMargin ? 1 : 0) + (showCompare ? 2 : 0);
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, totalCols - 1));

            // 2. Thông tin bộ lọc
            int r = 2;
            r = addInfoRow(sheet, styles, r, "Khoảng thời gian",
                    (filter != null && filter.getFromDate() != null ? filter.getFromDate().toString() : "Tất cả")
                            + " đến " + (filter != null && filter.getToDate() != null ? filter.getToDate().toString() : "Hiện tại"));
            r = addInfoRow(sheet, styles, r, "Chiều phân tích", groupLabel);
            r = addInfoRow(sheet, styles, r, "Thời điểm xuất", LocalDateTime.now().format(DATE_TIME_FORMATTER));

            r++; // Dòng trống

            // 3. Tiêu đề bảng
            Row headerRow = sheet.createRow(r++);
            headerRow.setHeightInPoints(24);

            int col = 0;
            createHeaderCell(headerRow, col++, "STT", styles.headerStyle);
            createHeaderCell(headerRow, col++, "Mã", styles.headerStyle);
            createHeaderCell(headerRow, col++, groupLabel, styles.headerStyle);
            createHeaderCell(headerRow, col++, "Doanh số (VNĐ)", styles.headerRightStyle);
            createHeaderCell(headerRow, col++, "Sản lượng", styles.headerRightStyle);

            if (canSeeMargin) {
                createHeaderCell(headerRow, col++, "Biên LN gộp (%)", styles.headerRightStyle);
            }
            if (showCompare) {
                createHeaderCell(headerRow, col++, "DS cùng kỳ (VNĐ)", styles.headerRightStyle);
                createHeaderCell(headerRow, col++, "Tăng/giảm (%)", styles.headerRightStyle);
            }

            // 4. Dòng dữ liệu
            List<SalesReportRowResponse> rows = report != null && report.getContent() != null
                    ? report.getContent() : List.of();

            int stt = 1;
            for (SalesReportRowResponse rowData : rows) {
                Row row = sheet.createRow(r++);
                row.setHeightInPoints(20);
                boolean isEven = (stt % 2 == 0);
                CellStyle baseText = isEven ? styles.textEvenStyle : styles.textStyle;
                CellStyle baseCenter = isEven ? styles.centerEvenStyle : styles.centerStyle;
                CellStyle baseNumber = isEven ? styles.numberEvenStyle : styles.numberStyle;
                CellStyle baseCurrency = isEven ? styles.currencyEvenStyle : styles.currencyStyle;
                CellStyle basePercent = isEven ? styles.percentEvenStyle : styles.percentStyle;

                int c = 0;
                createCell(row, c++, stt++, baseCenter);
                createCell(row, c++, rowData.getCode() != null ? rowData.getCode() : "", baseCenter);
                createCell(row, c++, rowData.getName() != null ? rowData.getName() : "", baseText);
                createCell(row, c++, rowData.getRevenue() != null ? rowData.getRevenue().doubleValue() : 0.0, baseCurrency);
                createCell(row, c++, rowData.getQuantity() != null ? rowData.getQuantity().doubleValue() : 0.0, baseNumber);

                if (canSeeMargin) {
                    if (rowData.getGrossMargin() != null) {
                        createCell(row, c++, rowData.getGrossMargin().doubleValue(), basePercent);
                    } else {
                        createCell(row, c++, "—", baseCenter);
                    }
                }

                if (showCompare) {
                    createCell(row, c++, rowData.getPreviousRevenue() != null ? rowData.getPreviousRevenue().doubleValue() : 0.0, baseCurrency);
                    if (rowData.getChangePercent() != null) {
                        createCell(row, c++, rowData.getChangePercent().doubleValue(), basePercent);
                    } else {
                        createCell(row, c++, "—", baseCenter);
                    }
                }
            }

            // 5. Dòng tổng cộng
            if (report != null && report.getSummary() != null) {
                SalesReportSummaryResponse summary = report.getSummary();
                Row totalRow = sheet.createRow(r++);
                totalRow.setHeightInPoints(22);

                createCell(totalRow, 0, "", styles.totalCenterStyle);
                createCell(totalRow, 1, "", styles.totalCenterStyle);
                createCell(totalRow, 2, "TỔNG CỘNG", styles.totalStyle);
                sheet.addMergedRegion(new CellRangeAddress(totalRow.getRowNum(), totalRow.getRowNum(), 0, 2));

                int c = 3;
                createCell(totalRow, c++, summary.getTotalRevenue() != null ? summary.getTotalRevenue().doubleValue() : 0.0, styles.totalCurrencyStyle);
                createCell(totalRow, c++, summary.getTotalQuantity() != null ? summary.getTotalQuantity().doubleValue() : 0.0, styles.totalNumberStyle);

                if (canSeeMargin) {
                    if (summary.getAverageGrossMargin() != null) {
                        createCell(totalRow, c++, summary.getAverageGrossMargin().doubleValue(), styles.totalPercentStyle);
                    } else {
                        createCell(totalRow, c++, "—", styles.totalCenterStyle);
                    }
                }

                if (showCompare) {
                    createCell(totalRow, c++, "", styles.totalCenterStyle);
                    createCell(totalRow, c++, "", styles.totalCenterStyle);
                }
            }

            // Tự động căn chỉnh độ rộng cột
            for (int i = 0; i < totalCols; i++) {
                sheet.autoSizeColumn(i);
                sheet.setColumnWidth(i, Math.max(sheet.getColumnWidth(i) + 1200, 3200));
            }

            workbook.write(outputStream);
            return outputStream.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Không thể tạo file Excel báo cáo doanh số.", e);
        }
    }

    private String getGroupLabel(String groupBy) {
        if (groupBy == null) return "Mặt hàng";
        return switch (groupBy.toUpperCase()) {
            case "EMPLOYEE" -> "Nhân viên";
            case "REGION" -> "Khu vực";
            case "DEALER" -> "Đại lý";
            case "CATEGORY" -> "Nhóm hàng";
            default -> "Mặt hàng (SKU)";
        };
    }

    private int addInfoRow(Sheet sheet, StyleBundle styles, int rowIdx, String label, String value) {
        Row row = sheet.createRow(rowIdx);
        Cell c0 = row.createCell(0);
        c0.setCellValue(label + ":");
        c0.setCellStyle(styles.infoLabelStyle);

        Cell c1 = row.createCell(1);
        c1.setCellValue(value != null ? value : "—");
        c1.setCellStyle(styles.infoValueStyle);
        return rowIdx + 1;
    }

    private void createHeaderCell(Row row, int col, String value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    private void createCell(Row row, int col, Object value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellStyle(style);
        if (value instanceof Number n) {
            cell.setCellValue(n.doubleValue());
        } else {
            cell.setCellValue(value != null ? value.toString() : "");
        }
    }

    private StyleBundle createStyleBundle(XSSFWorkbook wb) {
        StyleBundle b = new StyleBundle();
        DataFormat df = wb.createDataFormat();

        Font fontTitle = wb.createFont();
        fontTitle.setFontName("Arial");
        fontTitle.setFontHeightInPoints((short) 14);
        fontTitle.setBold(true);
        fontTitle.setColor(IndexedColors.DARK_BLUE.getIndex());

        b.titleStyle = wb.createCellStyle();
        b.titleStyle.setFont(fontTitle);
        b.titleStyle.setAlignment(HorizontalAlignment.LEFT);
        b.titleStyle.setVerticalAlignment(VerticalAlignment.CENTER);

        Font fontHeader = wb.createFont();
        fontHeader.setFontName("Arial");
        fontHeader.setFontHeightInPoints((short) 10);
        fontHeader.setBold(true);
        fontHeader.setColor(IndexedColors.WHITE.getIndex());

        b.headerStyle = wb.createCellStyle();
        b.headerStyle.setFont(fontHeader);
        b.headerStyle.setFillForegroundColor(IndexedColors.ROYAL_BLUE.getIndex());
        b.headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        b.headerStyle.setAlignment(HorizontalAlignment.CENTER);
        b.headerStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        setBorders(b.headerStyle);

        b.headerRightStyle = wb.createCellStyle();
        b.headerRightStyle.cloneStyleFrom(b.headerStyle);
        b.headerRightStyle.setAlignment(HorizontalAlignment.RIGHT);

        Font fontRegular = wb.createFont();
        fontRegular.setFontName("Arial");
        fontRegular.setFontHeightInPoints((short) 10);

        b.textStyle = wb.createCellStyle();
        b.textStyle.setFont(fontRegular);
        b.textStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        setBorders(b.textStyle);

        b.textEvenStyle = wb.createCellStyle();
        b.textEvenStyle.cloneStyleFrom(b.textStyle);
        b.textEvenStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        b.textEvenStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        b.centerStyle = wb.createCellStyle();
        b.centerStyle.cloneStyleFrom(b.textStyle);
        b.centerStyle.setAlignment(HorizontalAlignment.CENTER);

        b.centerEvenStyle = wb.createCellStyle();
        b.centerEvenStyle.cloneStyleFrom(b.textEvenStyle);
        b.centerEvenStyle.setAlignment(HorizontalAlignment.CENTER);

        b.numberStyle = wb.createCellStyle();
        b.numberStyle.cloneStyleFrom(b.textStyle);
        b.numberStyle.setAlignment(HorizontalAlignment.RIGHT);
        b.numberStyle.setDataFormat(df.getFormat("#,##0.##"));

        b.numberEvenStyle = wb.createCellStyle();
        b.numberEvenStyle.cloneStyleFrom(b.textEvenStyle);
        b.numberEvenStyle.setAlignment(HorizontalAlignment.RIGHT);
        b.numberEvenStyle.setDataFormat(df.getFormat("#,##0.##"));

        b.currencyStyle = wb.createCellStyle();
        b.currencyStyle.cloneStyleFrom(b.textStyle);
        b.currencyStyle.setAlignment(HorizontalAlignment.RIGHT);
        b.currencyStyle.setDataFormat(df.getFormat("#,##0"));

        b.currencyEvenStyle = wb.createCellStyle();
        b.currencyEvenStyle.cloneStyleFrom(b.textEvenStyle);
        b.currencyEvenStyle.setAlignment(HorizontalAlignment.RIGHT);
        b.currencyEvenStyle.setDataFormat(df.getFormat("#,##0"));

        b.percentStyle = wb.createCellStyle();
        b.percentStyle.cloneStyleFrom(b.textStyle);
        b.percentStyle.setAlignment(HorizontalAlignment.RIGHT);
        b.percentStyle.setDataFormat(df.getFormat("#,##0.0"));

        b.percentEvenStyle = wb.createCellStyle();
        b.percentEvenStyle.cloneStyleFrom(b.textEvenStyle);
        b.percentEvenStyle.setAlignment(HorizontalAlignment.RIGHT);
        b.percentEvenStyle.setDataFormat(df.getFormat("#,##0.0"));

        Font fontBold = wb.createFont();
        fontBold.setFontName("Arial");
        fontBold.setFontHeightInPoints((short) 10);
        fontBold.setBold(true);

        b.totalStyle = wb.createCellStyle();
        b.totalStyle.setFont(fontBold);
        b.totalStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        b.totalStyle.setFillForegroundColor(IndexedColors.LEMON_CHIFFON.getIndex());
        b.totalStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        setBorders(b.totalStyle);

        b.totalCenterStyle = wb.createCellStyle();
        b.totalCenterStyle.cloneStyleFrom(b.totalStyle);
        b.totalCenterStyle.setAlignment(HorizontalAlignment.CENTER);

        b.totalCurrencyStyle = wb.createCellStyle();
        b.totalCurrencyStyle.cloneStyleFrom(b.totalStyle);
        b.totalCurrencyStyle.setAlignment(HorizontalAlignment.RIGHT);
        b.totalCurrencyStyle.setDataFormat(df.getFormat("#,##0"));

        b.totalNumberStyle = wb.createCellStyle();
        b.totalNumberStyle.cloneStyleFrom(b.totalStyle);
        b.totalNumberStyle.setAlignment(HorizontalAlignment.RIGHT);
        b.totalNumberStyle.setDataFormat(df.getFormat("#,##0.##"));

        b.totalPercentStyle = wb.createCellStyle();
        b.totalPercentStyle.cloneStyleFrom(b.totalStyle);
        b.totalPercentStyle.setAlignment(HorizontalAlignment.RIGHT);
        b.totalPercentStyle.setDataFormat(df.getFormat("#,##0.0"));

        b.infoLabelStyle = wb.createCellStyle();
        b.infoLabelStyle.setFont(fontBold);

        b.infoValueStyle = wb.createCellStyle();
        b.infoValueStyle.setFont(fontRegular);

        return b;
    }

    private void setBorders(CellStyle style) {
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
    }

    private static class StyleBundle {
        CellStyle titleStyle;
        CellStyle headerStyle;
        CellStyle headerRightStyle;
        CellStyle textStyle;
        CellStyle textEvenStyle;
        CellStyle centerStyle;
        CellStyle centerEvenStyle;
        CellStyle numberStyle;
        CellStyle numberEvenStyle;
        CellStyle currencyStyle;
        CellStyle currencyEvenStyle;
        CellStyle percentStyle;
        CellStyle percentEvenStyle;
        CellStyle totalStyle;
        CellStyle totalCenterStyle;
        CellStyle totalCurrencyStyle;
        CellStyle totalNumberStyle;
        CellStyle totalPercentStyle;
        CellStyle infoLabelStyle;
        CellStyle infoValueStyle;
    }
}
