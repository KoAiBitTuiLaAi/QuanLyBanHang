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
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Dịch vụ xuất Excel (.xlsx) cho Báo cáo tồn kho và vòng quay hàng (S8-07):
 * - Sheet 1: Giá trị tồn theo kho (tổng quan KPI + theo kho + theo kho x nhóm hàng)
 * - Sheet 2: Giá trị tồn theo nhóm hàng
 * - Sheet 3: Vòng quay & Số ngày tồn SKU
 * - Sheet 4: Hàng chậm luân chuyển (đang chôn vốn)
 */
@Service
public class InventoryTurnoverExcelExportService {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    public byte[] exportToExcel(InventoryTurnoverReportResponse report) {
        if (report == null) {
            throw new IllegalArgumentException("Dữ liệu báo cáo tồn kho và vòng quay hàng không được để trống.");
        }

        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {

            StyleBundle styles = createStyleBundle(workbook);

            buildWarehouseValuationSheet(workbook, styles, report);
            buildCategoryValuationSheet(workbook, styles, report);
            buildSkuTurnoverSheet(workbook, styles, report);
            buildSlowMovingSheet(workbook, styles, report);

            workbook.write(outputStream);
            return outputStream.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Không thể tạo file Excel báo cáo tồn kho và vòng quay hàng.", ex);
        }
    }

    public String buildFileName(InventoryTurnoverReportResponse report) {
        String scope = report != null && report.getWarehouseCode() != null && !report.getWarehouseCode().isBlank()
                ? sanitizeFilenameSegment(report.getWarehouseCode())
                : "TatCaKho";
        String from = report != null && report.getFromDate() != null
                ? report.getFromDate().toString()
                : "from";
        String to = report != null && report.getToDate() != null
                ? report.getToDate().toString()
                : "to";
        return String.format("Bao-cao-ton-kho-vong-quay_%s_%s_%s.xlsx", scope, from, to);
    }

    private void buildWarehouseValuationSheet(
            XSSFWorkbook workbook,
            StyleBundle styles,
            InventoryTurnoverReportResponse report) {
        Sheet sheet = workbook.createSheet("Giá trị tồn theo kho");
        int rowIdx = 0;

        Row titleRow = sheet.createRow(rowIdx++);
        titleRow.setHeightInPoints(24f);
        createTextCell(titleRow, 0, "BÁO CÁO GIÁ TRỊ TỒN KHO VÀ VÒNG QUAY HÀNG (S8-07)", styles.titleStyle);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 12));

        Row periodRow = sheet.createRow(rowIdx++);
        String warehouseLabel = report.getWarehouseName() != null
                ? report.getWarehouseCode() + " - " + report.getWarehouseName()
                : "Tất cả kho được phân quyền";
        String categoryLabel = report.getCategoryName() != null
                ? report.getCategoryCode() + " - " + report.getCategoryName()
                : "Tất cả nhóm hàng";
        createTextCell(periodRow, 0, String.format(
                "Kỳ phân tích: %s - %s (%d ngày) | Ngưỡng chậm luân chuyển: >= %d ngày | Kho: %s | Nhóm hàng: %s",
                report.getFromDate() != null ? report.getFromDate().format(DATE_FORMATTER) : "-",
                report.getToDate() != null ? report.getToDate().format(DATE_FORMATTER) : "-",
                report.getPeriodDays(),
                report.getSlowMovingThresholdDays(),
                warehouseLabel,
                categoryLabel), styles.subTitleStyle);
        sheet.addMergedRegion(new CellRangeAddress(1, 1, 0, 12));

        rowIdx++;

        // KPI Summary Block
        Row kpiHeaderRow = sheet.createRow(rowIdx++);
        createTextCell(kpiHeaderRow, 0, "CHỈ TIÊU TỔNG HỢP TOÀN PHẠM VI", styles.sectionStyle);
        sheet.addMergedRegion(new CellRangeAddress(kpiHeaderRow.getRowNum(), kpiHeaderRow.getRowNum(), 0, 5));

        rowIdx = createKpiRow(sheet, rowIdx, styles,
                "Tổng giá trị tồn kho (VND)", report.getTotalInventoryValue(), true,
                "Giá trị hàng chậm luân chuyển đang chôn vốn (VND)", report.getSlowMovingInventoryValue(), true);
        rowIdx = createKpiRow(sheet, rowIdx, styles,
                "Tỷ lệ vốn bị chôn (%)", report.getTiedUpCapitalRatioPercent(), false,
                "Số ngày tồn bình quân chung (ngày)", report.getOverallAverageDaysInInventory(), false);
        rowIdx = createKpiRow(sheet, rowIdx, styles,
                "Hệ số vòng quay trong kỳ (vòng)", report.getOverallTurnoverRate(), false,
                "Tổng số SKU đang chậm luân chuyển / Tổng SKU",
                BigDecimal.valueOf(report.getSlowMovingSkuCount()), false);

        rowIdx++;

        // Section 1: Giá trị tồn theo kho
        Row whSectionRow = sheet.createRow(rowIdx++);
        createTextCell(whSectionRow, 0, "1. GIÁ TRỊ TỒN KHO THEO KHO", styles.sectionStyle);
        sheet.addMergedRegion(new CellRangeAddress(whSectionRow.getRowNum(), whSectionRow.getRowNum(), 0, 12));

        String[] whHeaders = {
                "STT",
                "Mã kho",
                "Tên kho",
                "Số SKU có tồn",
                "Số SKU chậm luân chuyển",
                "Tồn thực tế",
                "Đang giữ chỗ",
                "Tồn khả dụng",
                "Xuất trong kỳ",
                "Giá trị tồn kho (VND)",
                "Tỷ trọng giá trị (%)",
                "Giá trị chôn vốn (VND)",
                "Số ngày tồn bình quân (ngày)"
        };
        Row whHeaderRow = sheet.createRow(rowIdx++);
        whHeaderRow.setHeightInPoints(22f);
        for (int i = 0; i < whHeaders.length; i++) {
            createTextCell(whHeaderRow, i, whHeaders[i], styles.headerStyle);
        }

        List<WarehouseInventoryValuationResponse> warehouses = report.getWarehouseValuations();
        int stt = 1;
        if (warehouses != null) {
            for (WarehouseInventoryValuationResponse wh : warehouses) {
                Row row = sheet.createRow(rowIdx++);
                createIntCell(row, 0, stt++, styles.centerStyle);
                createTextCell(row, 1, wh.getWarehouseCode(), styles.textStyle);
                createTextCell(row, 2, wh.getWarehouseName(), styles.textStyle);
                createIntCell(row, 3, wh.getInStockSkuCount(), styles.integerStyle);
                createIntCell(row, 4, wh.getSlowMovingSkuCount(), styles.integerStyle);
                createDecimalCell(row, 5, wh.getTotalPhysicalQuantity(), styles.decimalStyle);
                createDecimalCell(row, 6, wh.getTotalReservedQuantity(), styles.decimalStyle);
                createDecimalCell(row, 7, wh.getTotalAvailableQuantity(), styles.decimalStyle);
                createDecimalCell(row, 8, wh.getOutboundQuantityInPeriod(), styles.decimalStyle);
                createDecimalCell(row, 9, wh.getInventoryValue(), styles.currencyStyle);
                createDecimalCell(row, 10, wh.getValueSharePercent(), styles.decimalStyle);
                createDecimalCell(row, 11, wh.getSlowMovingInventoryValue(), styles.currencyStyle);
                createDecimalCell(row, 12, wh.getAverageDaysInInventory(), styles.decimalStyle);
            }
        }

        Row whTotalRow = sheet.createRow(rowIdx++);
        createTextCell(whTotalRow, 0, "", styles.totalTextStyle);
        createTextCell(whTotalRow, 1, "TỔNG CỘNG", styles.totalTextStyle);
        createTextCell(whTotalRow, 2, "", styles.totalTextStyle);
        createIntCell(whTotalRow, 3, report.getInStockSkuCount(), styles.totalIntegerStyle);
        createIntCell(whTotalRow, 4, report.getSlowMovingSkuCount(), styles.totalIntegerStyle);
        createDecimalCell(whTotalRow, 5, report.getTotalPhysicalQuantity(), styles.totalDecimalStyle);
        createDecimalCell(whTotalRow, 6, report.getTotalReservedQuantity(), styles.totalDecimalStyle);
        createDecimalCell(whTotalRow, 7, report.getTotalAvailableQuantity(), styles.totalDecimalStyle);
        createDecimalCell(whTotalRow, 8, report.getTotalOutboundQuantityInPeriod(), styles.totalDecimalStyle);
        createDecimalCell(whTotalRow, 9, report.getTotalInventoryValue(), styles.totalCurrencyStyle);
        createDecimalCell(whTotalRow, 10,
                report.getTotalInventoryValue() != null && report.getTotalInventoryValue().signum() > 0
                        ? new BigDecimal("100.00") : BigDecimal.ZERO,
                styles.totalDecimalStyle);
        createDecimalCell(whTotalRow, 11, report.getSlowMovingInventoryValue(), styles.totalCurrencyStyle);
        createDecimalCell(whTotalRow, 12, report.getOverallAverageDaysInInventory(), styles.totalDecimalStyle);

        rowIdx++;

        // Section 2: Giá trị tồn theo Kho x Nhóm hàng
        Row whCatSectionRow = sheet.createRow(rowIdx++);
        createTextCell(whCatSectionRow, 0, "2. GIÁ TRỊ TỒN CHI TIẾT THEO KHO × NHÓM HÀNG", styles.sectionStyle);
        sheet.addMergedRegion(new CellRangeAddress(whCatSectionRow.getRowNum(), whCatSectionRow.getRowNum(), 0, 11));

        String[] whCatHeaders = {
                "STT",
                "Mã kho",
                "Tên kho",
                "Mã nhóm hàng",
                "Tên nhóm hàng",
                "Số SKU có tồn",
                "Số SKU chậm luân chuyển",
                "Tồn thực tế",
                "Tồn khả dụng",
                "Xuất trong kỳ",
                "Giá trị tồn kho (VND)",
                "Giá trị chôn vốn (VND)"
        };
        Row whCatHeaderRow = sheet.createRow(rowIdx++);
        whCatHeaderRow.setHeightInPoints(22f);
        for (int i = 0; i < whCatHeaders.length; i++) {
            createTextCell(whCatHeaderRow, i, whCatHeaders[i], styles.headerStyle);
        }

        List<WarehouseCategoryValuationResponse> whCatList = report.getWarehouseCategoryValuations();
        int whCatStt = 1;
        if (whCatList != null) {
            for (WarehouseCategoryValuationResponse wc : whCatList) {
                Row row = sheet.createRow(rowIdx++);
                createIntCell(row, 0, whCatStt++, styles.centerStyle);
                createTextCell(row, 1, wc.getWarehouseCode(), styles.textStyle);
                createTextCell(row, 2, wc.getWarehouseName(), styles.textStyle);
                createTextCell(row, 3, wc.getCategoryCode(), styles.textStyle);
                createTextCell(row, 4, wc.getCategoryName(), styles.textStyle);
                createIntCell(row, 5, wc.getInStockSkuCount(), styles.integerStyle);
                createIntCell(row, 6, wc.getSlowMovingSkuCount(), styles.integerStyle);
                createDecimalCell(row, 7, wc.getTotalPhysicalQuantity(), styles.decimalStyle);
                createDecimalCell(row, 8, wc.getTotalAvailableQuantity(), styles.decimalStyle);
                createDecimalCell(row, 9, wc.getOutboundQuantityInPeriod(), styles.decimalStyle);
                createDecimalCell(row, 10, wc.getInventoryValue(), styles.currencyStyle);
                createDecimalCell(row, 11, wc.getSlowMovingInventoryValue(), styles.currencyStyle);
            }
        }

        autoSizeColumns(sheet, 13);
    }

    private void buildCategoryValuationSheet(
            XSSFWorkbook workbook,
            StyleBundle styles,
            InventoryTurnoverReportResponse report) {
        Sheet sheet = workbook.createSheet("Giá trị tồn theo nhóm hàng");
        int rowIdx = 0;

        Row titleRow = sheet.createRow(rowIdx++);
        titleRow.setHeightInPoints(24f);
        createTextCell(titleRow, 0, "GIÁ TRỊ TỒN KHO VÀ VÒNG QUAY THEO NHÓM HÀNG", styles.titleStyle);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 12));

        rowIdx++;

        String[] headers = {
                "STT",
                "Mã nhóm hàng",
                "Tên nhóm hàng",
                "Số SKU có tồn",
                "Số SKU chậm luân chuyển",
                "Tồn thực tế",
                "Đang giữ chỗ",
                "Tồn khả dụng",
                "Xuất trong kỳ",
                "Giá trị tồn kho (VND)",
                "Tỷ trọng giá trị (%)",
                "Giá trị chôn vốn (VND)",
                "Số ngày tồn bình quân (ngày)"
        };
        Row headerRow = sheet.createRow(rowIdx++);
        headerRow.setHeightInPoints(22f);
        for (int i = 0; i < headers.length; i++) {
            createTextCell(headerRow, i, headers[i], styles.headerStyle);
        }

        List<CategoryInventoryValuationResponse> categories = report.getCategoryValuations();
        int stt = 1;
        if (categories != null) {
            for (CategoryInventoryValuationResponse cat : categories) {
                Row row = sheet.createRow(rowIdx++);
                createIntCell(row, 0, stt++, styles.centerStyle);
                createTextCell(row, 1, cat.getCategoryCode(), styles.textStyle);
                createTextCell(row, 2, cat.getCategoryName(), styles.textStyle);
                createIntCell(row, 3, cat.getInStockSkuCount(), styles.integerStyle);
                createIntCell(row, 4, cat.getSlowMovingSkuCount(), styles.integerStyle);
                createDecimalCell(row, 5, cat.getTotalPhysicalQuantity(), styles.decimalStyle);
                createDecimalCell(row, 6, cat.getTotalReservedQuantity(), styles.decimalStyle);
                createDecimalCell(row, 7, cat.getTotalAvailableQuantity(), styles.decimalStyle);
                createDecimalCell(row, 8, cat.getOutboundQuantityInPeriod(), styles.decimalStyle);
                createDecimalCell(row, 9, cat.getInventoryValue(), styles.currencyStyle);
                createDecimalCell(row, 10, cat.getValueSharePercent(), styles.decimalStyle);
                createDecimalCell(row, 11, cat.getSlowMovingInventoryValue(), styles.currencyStyle);
                createDecimalCell(row, 12, cat.getAverageDaysInInventory(), styles.decimalStyle);
            }
        }

        Row totalRow = sheet.createRow(rowIdx);
        createTextCell(totalRow, 0, "", styles.totalTextStyle);
        createTextCell(totalRow, 1, "TỔNG CỘNG", styles.totalTextStyle);
        createTextCell(totalRow, 2, "", styles.totalTextStyle);
        createIntCell(totalRow, 3, report.getInStockSkuCount(), styles.totalIntegerStyle);
        createIntCell(totalRow, 4, report.getSlowMovingSkuCount(), styles.totalIntegerStyle);
        createDecimalCell(totalRow, 5, report.getTotalPhysicalQuantity(), styles.totalDecimalStyle);
        createDecimalCell(totalRow, 6, report.getTotalReservedQuantity(), styles.totalDecimalStyle);
        createDecimalCell(totalRow, 7, report.getTotalAvailableQuantity(), styles.totalDecimalStyle);
        createDecimalCell(totalRow, 8, report.getTotalOutboundQuantityInPeriod(), styles.totalDecimalStyle);
        createDecimalCell(totalRow, 9, report.getTotalInventoryValue(), styles.totalCurrencyStyle);
        createDecimalCell(totalRow, 10,
                report.getTotalInventoryValue() != null && report.getTotalInventoryValue().signum() > 0
                        ? new BigDecimal("100.00") : BigDecimal.ZERO,
                styles.totalDecimalStyle);
        createDecimalCell(totalRow, 11, report.getSlowMovingInventoryValue(), styles.totalCurrencyStyle);
        createDecimalCell(totalRow, 12, report.getOverallAverageDaysInInventory(), styles.totalDecimalStyle);

        autoSizeColumns(sheet, headers.length);
    }

    private void buildSkuTurnoverSheet(
            XSSFWorkbook workbook,
            StyleBundle styles,
            InventoryTurnoverReportResponse report) {
        Sheet sheet = workbook.createSheet("Vòng quay & Số ngày tồn SKU");
        int rowIdx = 0;

        Row titleRow = sheet.createRow(rowIdx++);
        titleRow.setHeightInPoints(24f);
        createTextCell(titleRow, 0, "CHI TIẾT VÒNG QUAY VÀ SỐ NGÀY TỒN BÌNH QUÂN CỦA TỪNG SKU", styles.titleStyle);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 18));

        rowIdx++;

        String[] headers = {
                "STT",
                "Mã SKU",
                "Tên sản phẩm",
                "ĐVT",
                "Nhóm hàng",
                "Phạm vi kho",
                "Đơn giá vốn (VND)",
                "Tồn đầu kỳ",
                "Nhập trong kỳ",
                "Xuất trong kỳ",
                "Tồn cuối kỳ",
                "Tồn bình quân",
                "Giá trị tồn cuối kỳ (VND)",
                "Vòng quay kỳ (vòng)",
                "Vòng quay năm (vòng/năm)",
                "Số ngày tồn bình quân (ngày)",
                "Trạng thái luân chuyển",
                "Chậm luân chuyển",
                "Giá trị chôn vốn (VND)",
                "Thuyết minh"
        };
        Row headerRow = sheet.createRow(rowIdx++);
        headerRow.setHeightInPoints(22f);
        for (int i = 0; i < headers.length; i++) {
            createTextCell(headerRow, i, headers[i], styles.headerStyle);
        }

        List<SkuInventoryTurnoverResponse> skus = report.getSkuItems();
        int stt = 1;
        if (skus != null) {
            for (SkuInventoryTurnoverResponse sku : skus) {
                Row row = sheet.createRow(rowIdx++);
                createIntCell(row, 0, stt++, styles.centerStyle);
                createTextCell(row, 1, sku.getProductCode(), styles.textStyle);
                createTextCell(row, 2, sku.getProductName(), styles.textStyle);
                createTextCell(row, 3, sku.getBaseUnit(), styles.centerStyle);
                createTextCell(row, 4, sku.getCategoryName(), styles.textStyle);
                createTextCell(row, 5, sku.getWarehouseName(), styles.textStyle);
                createDecimalCell(row, 6, sku.getUnitCost(), styles.currencyStyle);
                createDecimalCell(row, 7, sku.getOpeningQuantity(), styles.decimalStyle);
                createDecimalCell(row, 8, sku.getInboundQuantity(), styles.decimalStyle);
                createDecimalCell(row, 9, sku.getOutboundQuantity(), styles.decimalStyle);
                createDecimalCell(row, 10, sku.getClosingQuantity(), styles.decimalStyle);
                createDecimalCell(row, 11, sku.getAverageInventoryQuantity(), styles.decimalStyle);
                createDecimalCell(row, 12, sku.getInventoryValue(), styles.currencyStyle);
                createDecimalCell(row, 13, sku.getTurnoverRate(), styles.decimalStyle);
                createDecimalCell(row, 14, sku.getAnnualizedTurnoverRate(), styles.decimalStyle);
                if (sku.getAverageDaysInInventory() != null) {
                    createDecimalCell(row, 15, sku.getAverageDaysInInventory(), styles.decimalStyle);
                } else {
                    createTextCell(row, 15, "Không phát sinh xuất", styles.centerStyle);
                }
                createTextCell(row, 16, sku.getTurnoverStatusLabel(), styles.textStyle);
                createTextCell(row, 17, sku.isSlowMoving() ? "Có" : "Không", styles.centerStyle);
                createDecimalCell(row, 18, sku.getTiedUpCapitalValue(), styles.currencyStyle);
                createTextCell(row, 19, sku.getSlowMovingReason(), styles.textStyle);
            }
        }

        autoSizeColumns(sheet, headers.length);
    }

    private void buildSlowMovingSheet(
            XSSFWorkbook workbook,
            StyleBundle styles,
            InventoryTurnoverReportResponse report) {
        Sheet sheet = workbook.createSheet("Hàng chậm luân chuyển");
        int rowIdx = 0;

        Row titleRow = sheet.createRow(rowIdx++);
        titleRow.setHeightInPoints(24f);
        createTextCell(titleRow, 0, "DANH SÁCH HÀNG CHẬM LUÂN CHUYỂN ĐANG CHÔN VỐN", styles.titleStyle);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 14));

        Row subtitleRow = sheet.createRow(rowIdx++);
        createTextCell(subtitleRow, 0, String.format(
                "Ngưỡng cảnh báo: Số ngày tồn bình quân >= %d ngày hoặc không phát sinh xuất kho trong kỳ | Tổng vốn bị chôn: %s VND",
                report.getSlowMovingThresholdDays(),
                report.getSlowMovingInventoryValue() != null ? report.getSlowMovingInventoryValue().toPlainString() : "0"),
                styles.subTitleStyle);
        sheet.addMergedRegion(new CellRangeAddress(1, 1, 0, 14));

        rowIdx++;

        String[] headers = {
                "STT",
                "Mã SKU",
                "Tên sản phẩm",
                "ĐVT",
                "Nhóm hàng",
                "Phạm vi kho",
                "Tồn cuối kỳ",
                "Xuất trong kỳ",
                "Đơn giá vốn (VND)",
                "Giá trị chôn vốn (VND)",
                "Số ngày tồn bình quân (ngày)",
                "Số ngày từ lần xuất cuối",
                "Lần xuất gần nhất",
                "Phân loại",
                "Nguyên nhân chôn vốn"
        };
        Row headerRow = sheet.createRow(rowIdx++);
        headerRow.setHeightInPoints(22f);
        for (int i = 0; i < headers.length; i++) {
            createTextCell(headerRow, i, headers[i], styles.headerStyle);
        }

        List<SkuInventoryTurnoverResponse> slowItems = report.getSlowMovingItems();
        int stt = 1;
        if (slowItems != null) {
            for (SkuInventoryTurnoverResponse sku : slowItems) {
                Row row = sheet.createRow(rowIdx++);
                createIntCell(row, 0, stt++, styles.centerStyle);
                createTextCell(row, 1, sku.getProductCode(), styles.textStyle);
                createTextCell(row, 2, sku.getProductName(), styles.textStyle);
                createTextCell(row, 3, sku.getBaseUnit(), styles.centerStyle);
                createTextCell(row, 4, sku.getCategoryName(), styles.textStyle);
                createTextCell(row, 5, sku.getWarehouseName(), styles.textStyle);
                createDecimalCell(row, 6, sku.getClosingQuantity(), styles.decimalStyle);
                createDecimalCell(row, 7, sku.getOutboundQuantity(), styles.decimalStyle);
                createDecimalCell(row, 8, sku.getUnitCost(), styles.currencyStyle);
                createDecimalCell(row, 9, sku.getTiedUpCapitalValue(), styles.currencyStyle);
                if (sku.getAverageDaysInInventory() != null) {
                    createDecimalCell(row, 10, sku.getAverageDaysInInventory(), styles.decimalStyle);
                } else {
                    createTextCell(row, 10, "Không phát sinh xuất", styles.centerStyle);
                }
                if (sku.getDaysSinceLastOutbound() != null) {
                    createDecimalCell(row, 11, BigDecimal.valueOf(sku.getDaysSinceLastOutbound()), styles.integerStyle);
                } else {
                    createTextCell(row, 11, "Chưa từng xuất", styles.centerStyle);
                }
                createTextCell(row, 12,
                        sku.getLastOutboundAt() != null ? sku.getLastOutboundAt().format(DATETIME_FORMATTER) : "-",
                        styles.centerStyle);
                createTextCell(row, 13, sku.getTurnoverStatusLabel(), styles.textStyle);
                createTextCell(row, 14, sku.getSlowMovingReason(), styles.textStyle);
            }
        }

        Row totalRow = sheet.createRow(rowIdx);
        createTextCell(totalRow, 0, "", styles.totalTextStyle);
        createTextCell(totalRow, 1, "TỔNG VỐN CHÔN", styles.totalTextStyle);
        for (int c = 2; c <= 8; c++) {
            createTextCell(totalRow, c, "", styles.totalTextStyle);
        }
        createDecimalCell(totalRow, 9, report.getSlowMovingInventoryValue(), styles.totalCurrencyStyle);
        for (int c = 10; c < headers.length; c++) {
            createTextCell(totalRow, c, "", styles.totalTextStyle);
        }

        autoSizeColumns(sheet, headers.length);
    }

    private int createKpiRow(
            Sheet sheet,
            int rowIdx,
            StyleBundle styles,
            String label1,
            BigDecimal value1,
            boolean currency1,
            String label2,
            BigDecimal value2,
            boolean currency2) {
        Row row = sheet.createRow(rowIdx++);
        createTextCell(row, 0, label1, styles.textStyle);
        createDecimalCell(row, 1, value1, currency1 ? styles.currencyStyle : styles.decimalStyle);
        createTextCell(row, 3, label2, styles.textStyle);
        createDecimalCell(row, 4, value2, currency2 ? styles.currencyStyle : styles.decimalStyle);
        return rowIdx;
    }

    private void createTextCell(Row row, int col, String value, CellStyle style) {
        Cell cell = row.createCell(col, CellType.STRING);
        cell.setCellValue(sanitizeForSpreadsheet(value));
        cell.setCellStyle(style);
    }

    private void createIntCell(Row row, int col, int value, CellStyle style) {
        Cell cell = row.createCell(col, CellType.NUMERIC);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    private void createDecimalCell(Row row, int col, BigDecimal value, CellStyle style) {
        Cell cell = row.createCell(col, CellType.NUMERIC);
        cell.setCellValue(value != null ? value.doubleValue() : 0d);
        cell.setCellStyle(style);
    }

    static String sanitizeForSpreadsheet(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        char first = raw.charAt(0);
        if (first == '=' || first == '+' || first == '-' || first == '@') {
            return "'" + raw;
        }
        return raw;
    }

    private String sanitizeFilenameSegment(String input) {
        if (input == null || input.isBlank()) {
            return "TatCaKho";
        }
        String normalized = Normalizer.normalize(input.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .replace('đ', 'd')
                .replace('Đ', 'D');
        String cleaned = normalized.replaceAll("[^a-zA-Z0-9_-]+", "-").replaceAll("^-+|-+$", "");
        return cleaned.isEmpty() ? "TatCaKho" : cleaned.toUpperCase(Locale.ROOT);
    }

    private void autoSizeColumns(Sheet sheet, int columnCount) {
        for (int col = 0; col < columnCount; col++) {
            sheet.autoSizeColumn(col);
            int width = sheet.getColumnWidth(col);
            sheet.setColumnWidth(col, Math.min(Math.max(width + 900, 3200), 15000));
        }
    }

    private StyleBundle createStyleBundle(Workbook workbook) {
        DataFormat dataFormat = workbook.createDataFormat();

        Font titleFont = workbook.createFont();
        titleFont.setBold(true);
        titleFont.setFontHeightInPoints((short) 14);

        Font sectionFont = workbook.createFont();
        sectionFont.setBold(true);
        sectionFont.setFontHeightInPoints((short) 11);

        Font headerFont = workbook.createFont();
        headerFont.setBold(true);
        headerFont.setFontHeightInPoints((short) 10);

        Font regularFont = workbook.createFont();
        regularFont.setFontHeightInPoints((short) 10);

        CellStyle titleStyle = workbook.createCellStyle();
        titleStyle.setFont(titleFont);
        titleStyle.setAlignment(HorizontalAlignment.LEFT);
        titleStyle.setVerticalAlignment(VerticalAlignment.CENTER);

        CellStyle subTitleStyle = workbook.createCellStyle();
        subTitleStyle.setFont(regularFont);
        subTitleStyle.setAlignment(HorizontalAlignment.LEFT);

        CellStyle sectionStyle = workbook.createCellStyle();
        sectionStyle.setFont(sectionFont);
        sectionStyle.setAlignment(HorizontalAlignment.LEFT);

        CellStyle headerStyle = workbook.createCellStyle();
        headerStyle.setFont(headerFont);
        headerStyle.setAlignment(HorizontalAlignment.CENTER);
        headerStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        applyThinBorder(headerStyle);

        CellStyle textStyle = workbook.createCellStyle();
        textStyle.setFont(regularFont);
        textStyle.setAlignment(HorizontalAlignment.LEFT);
        textStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        applyThinBorder(textStyle);

        CellStyle centerStyle = workbook.createCellStyle();
        centerStyle.setFont(regularFont);
        centerStyle.setAlignment(HorizontalAlignment.CENTER);
        centerStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        applyThinBorder(centerStyle);

        CellStyle integerStyle = workbook.createCellStyle();
        integerStyle.setFont(regularFont);
        integerStyle.setAlignment(HorizontalAlignment.RIGHT);
        integerStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        integerStyle.setDataFormat(dataFormat.getFormat("#,##0"));
        applyThinBorder(integerStyle);

        CellStyle decimalStyle = workbook.createCellStyle();
        decimalStyle.setFont(regularFont);
        decimalStyle.setAlignment(HorizontalAlignment.RIGHT);
        decimalStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        decimalStyle.setDataFormat(dataFormat.getFormat("#,##0.##"));
        applyThinBorder(decimalStyle);

        CellStyle currencyStyle = workbook.createCellStyle();
        currencyStyle.setFont(regularFont);
        currencyStyle.setAlignment(HorizontalAlignment.RIGHT);
        currencyStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        currencyStyle.setDataFormat(dataFormat.getFormat("#,##0"));
        applyThinBorder(currencyStyle);

        CellStyle totalTextStyle = workbook.createCellStyle();
        totalTextStyle.setFont(headerFont);
        totalTextStyle.setAlignment(HorizontalAlignment.LEFT);
        totalTextStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        totalTextStyle.setFillForegroundColor(IndexedColors.LEMON_CHIFFON.getIndex());
        totalTextStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        applyThinBorder(totalTextStyle);

        CellStyle totalIntegerStyle = workbook.createCellStyle();
        totalIntegerStyle.cloneStyleFrom(integerStyle);
        totalIntegerStyle.setFont(headerFont);
        totalIntegerStyle.setFillForegroundColor(IndexedColors.LEMON_CHIFFON.getIndex());
        totalIntegerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        CellStyle totalDecimalStyle = workbook.createCellStyle();
        totalDecimalStyle.cloneStyleFrom(decimalStyle);
        totalDecimalStyle.setFont(headerFont);
        totalDecimalStyle.setFillForegroundColor(IndexedColors.LEMON_CHIFFON.getIndex());
        totalDecimalStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        CellStyle totalCurrencyStyle = workbook.createCellStyle();
        totalCurrencyStyle.cloneStyleFrom(currencyStyle);
        totalCurrencyStyle.setFont(headerFont);
        totalCurrencyStyle.setFillForegroundColor(IndexedColors.LEMON_CHIFFON.getIndex());
        totalCurrencyStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        return new StyleBundle(
                titleStyle,
                subTitleStyle,
                sectionStyle,
                headerStyle,
                textStyle,
                centerStyle,
                integerStyle,
                decimalStyle,
                currencyStyle,
                totalTextStyle,
                totalIntegerStyle,
                totalDecimalStyle,
                totalCurrencyStyle
        );
    }

    private void applyThinBorder(CellStyle style) {
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
    }

    private record StyleBundle(
            CellStyle titleStyle,
            CellStyle subTitleStyle,
            CellStyle sectionStyle,
            CellStyle headerStyle,
            CellStyle textStyle,
            CellStyle centerStyle,
            CellStyle integerStyle,
            CellStyle decimalStyle,
            CellStyle currencyStyle,
            CellStyle totalTextStyle,
            CellStyle totalIntegerStyle,
            CellStyle totalDecimalStyle,
            CellStyle totalCurrencyStyle
    ) {
    }
}

