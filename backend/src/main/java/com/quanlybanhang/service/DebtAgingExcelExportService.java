package com.quanlybanhang.service;

import com.quanlybanhang.dto.response.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/**
 * Dịch vụ xuất file Excel (.xlsx) cho Báo cáo công nợ quá hạn theo tuổi nợ (S8-09).
 * - Sheet 1: Thông tin chung (KPI tổng quan, tiêu chí xếp hạng rủi ro)
 * - Sheet 2: Nhóm tuổi nợ
 * - Sheet 3: Theo nhân viên
 * - Sheet 4: Chi tiết đại lý rủi ro
 */
@Service
public class DebtAgingExcelExportService {

    public byte[] exportToExcel(DebtAgingReportResponse report) {
        if (report == null) {
            throw new IllegalArgumentException("Dữ liệu báo cáo công nợ theo tuổi nợ không được để trống.");
        }

        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {

            StyleBundle styles = createStyleBundle(workbook);

            buildOverviewSheet(workbook, styles, report);
            buildBucketTotalsSheet(workbook, styles, report);
            buildEmployeeTotalsSheet(workbook, styles, report);
            buildDealerDetailsSheet(workbook, styles, report);

            workbook.write(outputStream);
            return outputStream.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Không thể tạo file Excel báo cáo công nợ theo tuổi nợ.", ex);
        }
    }

    public String buildFileName(DebtAgingReportResponse report) {
        String asOf = report != null && report.getAsOfDate() != null ? report.getAsOfDate() : "ngay-chot";
        String scope = "tat-ca";
        if (report != null && report.getFilters() != null) {
            if (Boolean.TRUE.equals(report.getFilters().getUnassignedOnly())) {
                scope = "chua-phan-cong";
            } else if (report.getFilters().getEmployeeId() != null) {
                scope = "nv-" + report.getFilters().getEmployeeId();
            }
        }
        return "Cong-no-tuoi-no_" + asOf + "_" + scope + ".xlsx";
    }

    // ── SHEET 1: THÔNG TIN CHUNG & KPI ──
    private void buildOverviewSheet(XSSFWorkbook workbook, StyleBundle styles, DebtAgingReportResponse report) {
        Sheet sheet = workbook.createSheet("Thông tin chung");
        sheet.setDisplayGridlines(true);

        // Title
        Row titleRow = sheet.createRow(0);
        titleRow.setHeightInPoints(28);
        Cell titleCell = titleRow.createCell(0);
        titleCell.setCellValue("BÁO CÁO CÔNG NỢ QUÁ HẠN THEO TUỔI NỢ");
        titleCell.setCellStyle(styles.titleStyle);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 4));

        int r = 2;
        r = addInfoRow(sheet, styles, r, "Ngày chốt báo cáo", report.getAsOfDate());
        r = addInfoRow(sheet, styles, r, "Thời điểm tạo dữ liệu", report.getGeneratedAt());
        r = addInfoRow(sheet, styles, r, "Múi giờ", report.getTimezone());
        r = addInfoRow(sheet, styles, r, "Đơn vị tiền tệ", report.getCurrency());

        String employeeScope = "Tất cả nhân viên";
        if (report.getFilters() != null) {
            if (Boolean.TRUE.equals(report.getFilters().getUnassignedOnly())) {
                employeeScope = "Chưa phân công";
            } else if (report.getFilters().getEmployeeId() != null) {
                employeeScope = "Nhân viên ID: " + report.getFilters().getEmployeeId();
            }
        }
        r = addInfoRow(sheet, styles, r, "Phạm vi nhân viên", employeeScope);
        r = addInfoRow(sheet, styles, r, "Tiêu chí xếp hạng rủi ro", report.getRiskBasis());

        r++;
        // KPI Section
        Row kpiHeader = sheet.createRow(r++);
        Cell kpiHeaderCell = kpiHeader.createCell(0);
        kpiHeaderCell.setCellValue("TỔNG HỢP CHỈ SỐ RỦI RO");
        kpiHeaderCell.setCellStyle(styles.sectionHeaderStyle);
        sheet.addMergedRegion(new CellRangeAddress(r - 1, r - 1, 0, 3));

        DebtAgingTotalsResponse totals = report.getTotals();
        BigDecimal totalAmount = parseAmount(totals != null ? totals.getOverdueAmount() : null);
        int dealerCount = totals != null && totals.getDealerCount() != null ? totals.getDealerCount() : 0;
        int maxDays = totals != null && totals.getMaxOverdueDays() != null ? totals.getMaxOverdueDays() : 0;

        r = addKpiRow(sheet, styles, r, "Tổng tiền nợ quá hạn (VND)", totalAmount, true);
        r = addKpiRow(sheet, styles, r, "Tổng số đại lý có nợ quá hạn", BigDecimal.valueOf(dealerCount), false);
        r = addKpiRow(sheet, styles, r, "Số ngày quá hạn lớn nhất", BigDecimal.valueOf(maxDays), false);

        sheet.setColumnWidth(0, 32 * 256);
        sheet.setColumnWidth(1, 40 * 256);
        sheet.setColumnWidth(2, 20 * 256);
        sheet.setColumnWidth(3, 20 * 256);
        sheet.setColumnWidth(4, 20 * 256);
    }

    // ── SHEET 2: NHÓM TUỔI NỢ ──
    private void buildBucketTotalsSheet(XSSFWorkbook workbook, StyleBundle styles, DebtAgingReportResponse report) {
        Sheet sheet = workbook.createSheet("Nhóm tuổi nợ");
        sheet.setDisplayGridlines(true);

        Row titleRow = sheet.createRow(0);
        titleRow.setHeightInPoints(24);
        Cell titleCell = titleRow.createCell(0);
        titleCell.setCellValue("TỔNG HỢP CÔNG NỢ THEO NHÓM TUỔI NỢ");
        titleCell.setCellStyle(styles.titleStyle);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 4));

        String[] headers = {"Nhóm tuổi nợ", "Từ ngày quá hạn", "Đến ngày quá hạn", "Tiền quá hạn (VND)", "Số đại lý"};
        Row headerRow = sheet.createRow(2);
        headerRow.setHeightInPoints(22);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(styles.headerStyle);
        }

        int r = 3;
        List<DebtAgingBucketResponse> buckets = report.getBuckets();
        List<DebtAgingBucketTotalResponse> bucketTotals = report.getBucketTotals();

        if (bucketTotals != null && buckets != null) {
            for (int i = 0; i < bucketTotals.size(); i++) {
                DebtAgingBucketTotalResponse bt = bucketTotals.get(i);
                DebtAgingBucketResponse bDef = i < buckets.size() ? buckets.get(i) : null;

                Row row = sheet.createRow(r++);
                row.setHeightInPoints(19);

                Cell c0 = row.createCell(0);
                c0.setCellValue(escapeFormula(bDef != null ? bDef.getLabel() : bt.getBucketKey()));
                c0.setCellStyle(styles.textStyle);

                Cell c1 = row.createCell(1);
                c1.setCellValue(bDef != null && bDef.getMinDays() != null ? bDef.getMinDays() : 1);
                c1.setCellStyle(styles.integerStyle);

                Cell c2 = row.createCell(2);
                if (bDef != null && bDef.getMaxDays() != null) {
                    c2.setCellValue(bDef.getMaxDays());
                    c2.setCellStyle(styles.integerStyle);
                } else {
                    c2.setCellValue("Không giới hạn");
                    c2.setCellStyle(styles.textStyle);
                }

                Cell c3 = row.createCell(3);
                writeAmountCell(c3, parseAmount(bt.getAmount()), styles);

                Cell c4 = row.createCell(4);
                c4.setCellValue(bt.getDealerCount() != null ? bt.getDealerCount() : 0);
                c4.setCellStyle(styles.integerStyle);
            }
        }

        // Freeze header
        sheet.createFreezePane(0, 3);
        sheet.setRepeatingRows(new CellRangeAddress(2, 2, 0, 4));

        sheet.setColumnWidth(0, 30 * 256);
        sheet.setColumnWidth(1, 18 * 256);
        sheet.setColumnWidth(2, 20 * 256);
        sheet.setColumnWidth(3, 26 * 256);
        sheet.setColumnWidth(4, 16 * 256);
    }

    // ── SHEET 3: THEO NHÂN VIÊN ──
    private void buildEmployeeTotalsSheet(XSSFWorkbook workbook, StyleBundle styles, DebtAgingReportResponse report) {
        Sheet sheet = workbook.createSheet("Theo nhân viên");
        sheet.setDisplayGridlines(true);

        Row titleRow = sheet.createRow(0);
        titleRow.setHeightInPoints(24);
        Cell titleCell = titleRow.createCell(0);
        titleCell.setCellValue("CÔNG NỢ QUÁ HẠN THEO NHÂN VIÊN PHỤ TRÁCH");
        titleCell.setCellStyle(styles.titleStyle);

        List<DebtAgingBucketResponse> buckets = report.getBuckets() != null ? report.getBuckets() : List.of();
        int totalCols = 2 + buckets.size() + 1;
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, Math.max(0, totalCols - 1)));

        Row headerRow = sheet.createRow(2);
        headerRow.setHeightInPoints(22);

        int col = 0;
        createHeaderCell(headerRow, col++, "Nhân viên", styles);
        createHeaderCell(headerRow, col++, "Số đại lý", styles);
        for (DebtAgingBucketResponse b : buckets) {
            createHeaderCell(headerRow, col++, b.getLabel(), styles);
        }
        createHeaderCell(headerRow, col++, "Tổng tiền quá hạn (VND)", styles);

        int r = 3;
        List<DebtAgingEmployeeTotalResponse> empTotals = report.getEmployeeTotals();
        if (empTotals != null) {
            for (DebtAgingEmployeeTotalResponse emp : empTotals) {
                Row row = sheet.createRow(r++);
                row.setHeightInPoints(19);

                col = 0;
                Cell cName = row.createCell(col++);
                cName.setCellValue(escapeFormula(emp.getEmployeeName()));
                cName.setCellStyle(styles.textStyle);

                Cell cCount = row.createCell(col++);
                cCount.setCellValue(emp.getDealerCount() != null ? emp.getDealerCount() : 0);
                cCount.setCellStyle(styles.integerStyle);

                for (DebtAgingBucketResponse b : buckets) {
                    Cell cBucket = row.createCell(col++);
                    String amtStr = emp.getBuckets() != null ? emp.getBuckets().get(b.getKey()) : null;
                    writeAmountCell(cBucket, parseAmount(amtStr), styles);
                }

                Cell cTotal = row.createCell(col++);
                writeAmountCell(cTotal, parseAmount(emp.getOverdueAmount()), styles);
            }
        }

        sheet.createFreezePane(0, 3);
        sheet.setRepeatingRows(new CellRangeAddress(2, 2, 0, Math.max(0, totalCols - 1)));

        sheet.setColumnWidth(0, 28 * 256);
        sheet.setColumnWidth(1, 14 * 256);
        for (int i = 0; i < buckets.size(); i++) {
            sheet.setColumnWidth(2 + i, 24 * 256);
        }
        sheet.setColumnWidth(2 + buckets.size(), 26 * 256);
    }

    // ── SHEET 4: CHI TIẾT ĐẠI LÝ RỦI RO ──
    private void buildDealerDetailsSheet(XSSFWorkbook workbook, StyleBundle styles, DebtAgingReportResponse report) {
        Sheet sheet = workbook.createSheet("Chi tiết đại lý rủi ro");
        sheet.setDisplayGridlines(true);

        Row titleRow = sheet.createRow(0);
        titleRow.setHeightInPoints(24);
        Cell titleCell = titleRow.createCell(0);
        titleCell.setCellValue("DANH SÁCH ĐẠI LÝ THEO MỨC ĐỘ RỦI RO CÔNG NỢ");
        titleCell.setCellStyle(styles.titleStyle);

        List<DebtAgingBucketResponse> buckets = report.getBuckets() != null ? report.getBuckets() : List.of();
        int totalCols = 4 + buckets.size() + 3;
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, Math.max(0, totalCols - 1)));

        Row headerRow = sheet.createRow(2);
        headerRow.setHeightInPoints(22);

        int col = 0;
        createHeaderCell(headerRow, col++, "Hạng rủi ro", styles);
        createHeaderCell(headerRow, col++, "Mã đại lý", styles);
        createHeaderCell(headerRow, col++, "Tên đại lý", styles);
        createHeaderCell(headerRow, col++, "Nhân viên phụ trách", styles);
        for (DebtAgingBucketResponse b : buckets) {
            createHeaderCell(headerRow, col++, b.getLabel(), styles);
        }
        createHeaderCell(headerRow, col++, "Tiền quá hạn (VND)", styles);
        createHeaderCell(headerRow, col++, "Quá hạn lớn nhất (ngày)", styles);
        createHeaderCell(headerRow, col++, "Mức độ rủi ro", styles);

        int r = 3;
        List<DebtAgingDealerRowResponse> dealers = report.getDealers();
        if (dealers != null) {
            for (DebtAgingDealerRowResponse d : dealers) {
                Row row = sheet.createRow(r++);
                row.setHeightInPoints(19);

                col = 0;
                Cell cRank = row.createCell(col++);
                cRank.setCellValue(d.getRiskRank() != null ? d.getRiskRank() : 0);
                cRank.setCellStyle(styles.integerStyle);

                Cell cCode = row.createCell(col++);
                cCode.setCellValue(escapeFormula(d.getDealerCode()));
                cCode.setCellStyle(styles.textStyle);

                Cell cName = row.createCell(col++);
                cName.setCellValue(escapeFormula(d.getDealerName()));
                cName.setCellStyle(styles.textStyle);

                Cell cEmp = row.createCell(col++);
                cEmp.setCellValue(escapeFormula(d.getEmployeeName()));
                cEmp.setCellStyle(styles.textStyle);

                for (DebtAgingBucketResponse b : buckets) {
                    Cell cBucket = row.createCell(col++);
                    String amtStr = d.getBuckets() != null ? d.getBuckets().get(b.getKey()) : null;
                    writeAmountCell(cBucket, parseAmount(amtStr), styles);
                }

                Cell cOverdue = row.createCell(col++);
                writeAmountCell(cOverdue, parseAmount(d.getOverdueAmount()), styles);

                Cell cDays = row.createCell(col++);
                cDays.setCellValue(d.getMaxOverdueDays() != null ? d.getMaxOverdueDays() : 0);
                cDays.setCellStyle(styles.integerStyle);

                Cell cLabel = row.createCell(col++);
                cLabel.setCellValue(escapeFormula(d.getRiskLabel()));
                cLabel.setCellStyle(styles.textStyle);
            }
        }

        sheet.createFreezePane(0, 3);
        sheet.setRepeatingRows(new CellRangeAddress(2, 2, 0, Math.max(0, totalCols - 1)));

        col = 0;
        sheet.setColumnWidth(col++, 14 * 256); // Hạng
        sheet.setColumnWidth(col++, 18 * 256); // Mã DL
        sheet.setColumnWidth(col++, 32 * 256); // Tên DL
        sheet.setColumnWidth(col++, 24 * 256); // NV
        for (int i = 0; i < buckets.size(); i++) {
            sheet.setColumnWidth(col++, 24 * 256); // Buckets
        }
        sheet.setColumnWidth(col++, 26 * 256); // Tiền quá hạn
        sheet.setColumnWidth(col++, 24 * 256); // Quá hạn max
        sheet.setColumnWidth(col++, 18 * 256); // Nhãn rủi ro
    }

    // ── HELPER METHODS ──

    private int addInfoRow(Sheet sheet, StyleBundle styles, int rowNum, String label, String value) {
        Row row = sheet.createRow(rowNum);
        row.setHeightInPoints(18);
        Cell cLabel = row.createCell(0);
        cLabel.setCellValue(label);
        cLabel.setCellStyle(styles.labelStyle);

        Cell cValue = row.createCell(1);
        cValue.setCellValue(escapeFormula(value));
        cValue.setCellStyle(styles.textStyle);
        return rowNum + 1;
    }

    private int addKpiRow(Sheet sheet, StyleBundle styles, int rowNum, String label, BigDecimal value, boolean isCurrency) {
        Row row = sheet.createRow(rowNum);
        row.setHeightInPoints(20);
        Cell cLabel = row.createCell(0);
        cLabel.setCellValue(label);
        cLabel.setCellStyle(styles.labelStyle);

        Cell cVal = row.createCell(1);
        if (isCurrency) {
            writeAmountCell(cVal, value, styles);
        } else {
            cVal.setCellValue(value != null ? value.longValue() : 0);
            cVal.setCellStyle(styles.integerStyle);
        }
        return rowNum + 1;
    }

    private void createHeaderCell(Row row, int col, String title, StyleBundle styles) {
        Cell cell = row.createCell(col);
        cell.setCellValue(title);
        cell.setCellStyle(styles.headerStyle);
    }

    private void writeAmountCell(Cell cell, BigDecimal amount, StyleBundle styles) {
        if (amount == null) {
            cell.setCellValue(0.0);
            cell.setCellStyle(styles.currencyStyle);
            return;
        }
        if (amount.stripTrailingZeros().precision() > 15) {
            cell.setCellValue(amount.toPlainString());
            cell.setCellStyle(styles.textStyle);
        } else {
            cell.setCellValue(amount.doubleValue());
            cell.setCellStyle(styles.currencyStyle);
        }
    }

    private BigDecimal parseAmount(String str) {
        if (str == null || str.isBlank()) return BigDecimal.ZERO;
        try {
            return new BigDecimal(str);
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private String escapeFormula(String input) {
        if (input == null) return "";
        String s = input.trim();
        if (s.startsWith("=") || s.startsWith("+") || s.startsWith("-") || s.startsWith("@")) {
            return "'" + s;
        }
        return s;
    }

    private StyleBundle createStyleBundle(Workbook wb) {
        DataFormat df = wb.createDataFormat();

        Font fontRegular = wb.createFont();
        fontRegular.setFontName("Calibri");
        fontRegular.setFontHeightInPoints((short) 11);

        Font fontBold = wb.createFont();
        fontBold.setFontName("Calibri");
        fontBold.setFontHeightInPoints((short) 11);
        fontBold.setBold(true);

        Font fontTitle = wb.createFont();
        fontTitle.setFontName("Calibri");
        fontTitle.setFontHeightInPoints((short) 16);
        fontTitle.setBold(true);
        fontTitle.setColor(IndexedColors.DARK_BLUE.getIndex());

        Font fontHeader = wb.createFont();
        fontHeader.setFontName("Calibri");
        fontHeader.setFontHeightInPoints((short) 11);
        fontHeader.setBold(true);
        fontHeader.setColor(IndexedColors.WHITE.getIndex());

        CellStyle titleStyle = wb.createCellStyle();
        titleStyle.setFont(fontTitle);
        titleStyle.setVerticalAlignment(VerticalAlignment.CENTER);

        CellStyle sectionHeaderStyle = wb.createCellStyle();
        sectionHeaderStyle.setFont(fontBold);
        sectionHeaderStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        sectionHeaderStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        sectionHeaderStyle.setVerticalAlignment(VerticalAlignment.CENTER);

        CellStyle headerStyle = wb.createCellStyle();
        headerStyle.setFont(fontHeader);
        headerStyle.setFillForegroundColor(IndexedColors.ROYAL_BLUE.getIndex());
        headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        headerStyle.setAlignment(HorizontalAlignment.CENTER);
        headerStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        headerStyle.setBorderBottom(BorderStyle.THIN);
        headerStyle.setBorderTop(BorderStyle.THIN);
        headerStyle.setBorderLeft(BorderStyle.THIN);
        headerStyle.setBorderRight(BorderStyle.THIN);

        CellStyle labelStyle = wb.createCellStyle();
        labelStyle.setFont(fontBold);
        labelStyle.setVerticalAlignment(VerticalAlignment.CENTER);

        CellStyle textStyle = wb.createCellStyle();
        textStyle.setFont(fontRegular);
        textStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        textStyle.setBorderBottom(BorderStyle.THIN);
        textStyle.setBorderTop(BorderStyle.THIN);
        textStyle.setBorderLeft(BorderStyle.THIN);
        textStyle.setBorderRight(BorderStyle.THIN);

        CellStyle integerStyle = wb.createCellStyle();
        integerStyle.setFont(fontRegular);
        integerStyle.setDataFormat(df.getFormat("#,##0"));
        integerStyle.setAlignment(HorizontalAlignment.RIGHT);
        integerStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        integerStyle.setBorderBottom(BorderStyle.THIN);
        integerStyle.setBorderTop(BorderStyle.THIN);
        integerStyle.setBorderLeft(BorderStyle.THIN);
        integerStyle.setBorderRight(BorderStyle.THIN);

        CellStyle currencyStyle = wb.createCellStyle();
        currencyStyle.setFont(fontRegular);
        currencyStyle.setDataFormat(df.getFormat("#,##0.00;[Red]-#,##0.00"));
        currencyStyle.setAlignment(HorizontalAlignment.RIGHT);
        currencyStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        currencyStyle.setBorderBottom(BorderStyle.THIN);
        currencyStyle.setBorderTop(BorderStyle.THIN);
        currencyStyle.setBorderLeft(BorderStyle.THIN);
        currencyStyle.setBorderRight(BorderStyle.THIN);

        return new StyleBundle(titleStyle, sectionHeaderStyle, headerStyle, labelStyle, textStyle, integerStyle, currencyStyle);
    }

    private record StyleBundle(
            CellStyle titleStyle,
            CellStyle sectionHeaderStyle,
            CellStyle headerStyle,
            CellStyle labelStyle,
            CellStyle textStyle,
            CellStyle integerStyle,
            CellStyle currencyStyle
    ) {}
}

