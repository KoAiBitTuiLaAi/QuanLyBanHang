package com.quanlybanhang.service;

import com.quanlybanhang.dto.response.StockLedgerEntryResponse;
import com.quanlybanhang.model.StockStatus;
import com.quanlybanhang.repository.StockLedgerRepository;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.io.*;
import java.time.*;
import java.util.*;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class StockLedgerExcelService {
    private static final String[] HEADERS = {"Mã SKU", "Sản phẩm", "Nhóm hàng", "Mã kho", "Kho", "Đơn vị",
            "Tồn thực tế", "Đang giữ chỗ", "Tồn khả dụng", "Trạng thái tồn", "Mức tối thiểu", "Cảnh báo tối thiểu"};
    private static final int BATCH_SIZE = 1000;
    private final StockLedgerService scope;
    private final StockLedgerRepository repository;
    private final Clock clock;
    private final int maxRows;
    public StockLedgerExcelService(StockLedgerService scope, StockLedgerRepository repository,
            @Qualifier("stockLedgerExportClock") Clock clock,
            @Value("${app.stock-ledger.export.max-rows:100000}") int maxRows) {
        if (maxRows < 1 || maxRows > 1_048_575) throw new IllegalArgumentException("Giới hạn xuất phải từ 1 đến 1048575 dòng");
        this.scope = scope; this.repository = repository; this.clock = clock; this.maxRows = maxRows;
    }
    public record Export(byte[] bytes, String filename) {}

    public Export export(Long warehouseId, Long categoryId, String keyword, StockStatus status, boolean belowMinimum) throws IOException {
        var query = scope.prepareQuery(warehouseId, categoryId, keyword, status, belowMinimum);
        LocalDate date = LocalDate.now(clock);
        SXSSFWorkbook workbook = new SXSSFWorkbook(100);
        workbook.setCompressTempFiles(true);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Sổ tồn kho");
            var headerStyle = workbook.createCellStyle();
            var font = workbook.createFont(); font.setBold(true); font.setColor(IndexedColors.WHITE.getIndex());
            headerStyle.setFont(font); headerStyle.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            var numberStyle = workbook.createCellStyle(); numberStyle.setDataFormat(workbook.createDataFormat().getFormat("#,##0.####"));
            var header = sheet.createRow(0);
            for (int column = 0; column < HEADERS.length; column++) {
                var cell = header.createCell(column); cell.setCellValue(HEADERS[column]); cell.setCellStyle(headerStyle);
                sheet.setColumnWidth(column, (column == 1 ? 36 : column == 11 ? 30 : 21) * 256);
            }
            int rowNumber = 1;
            if (!query.warehouseIds().isEmpty()) {
                for (int page = 0; ; page++) {
                    var rows = repository.findForExport(query.warehouseIds(), query.warehouseId(), query.categoryId(), query.keyword(),
                            query.stockStatus(), query.belowMinimum(), PageRequest.of(page, BATCH_SIZE));
                    for (var projection : rows) {
                        if (rowNumber > maxRows) throw new IllegalArgumentException("Kết quả vượt giới hạn " + maxRows + " dòng. Hãy thu hẹp bộ lọc để xuất đầy đủ.");
                        writeRow(sheet.createRow(rowNumber++), StockLedgerEntryResponse.from(projection), numberStyle);
                    }
                    if (!rows.hasNext()) break;
                }
            }
            sheet.createFreezePane(0, 1);
            sheet.setAutoFilter(new CellRangeAddress(0, Math.max(0, rowNumber - 1), 0, HEADERS.length - 1));
            sheet.setRepeatingRows(new CellRangeAddress(0, 0, -1, -1));
            workbook.getXSSFWorkbook().getProperties().getCoreProperties().setTitle("Sổ tồn kho - " + date);
            workbook.write(output);
            return new Export(output.toByteArray(), filename(query.warehouseCodes(), date));
        } finally {
            // SXSSF temp files must be removed even on a row limit, SQL or serialization error.
            try { workbook.dispose(); } finally { workbook.close(); }
        }
    }
    private void writeRow(Row row, StockLedgerEntryResponse entry, CellStyle numbers) {
        String[] text = {entry.productCode(), entry.productName(), entry.categoryName(), entry.warehouseCode(), entry.warehouseName(), entry.baseUnit()};
        for (int i = 0; i < text.length; i++) row.createCell(i, CellType.STRING).setCellValue(text[i] == null ? "" : text[i]);
        java.math.BigDecimal[] quantities = {entry.physicalQuantity(), entry.reservedQuantity(), entry.availableQuantity()};
        for (int i = 0; i < quantities.length; i++) {
            var cell = row.createCell(6 + i, CellType.NUMERIC); cell.setCellValue(quantities[i].doubleValue()); cell.setCellStyle(numbers);
        }
        row.createCell(9, CellType.STRING).setCellValue(entry.stockStatusLabel());
        var minimum = row.createCell(10);
        if (entry.minimumQuantity() != null) { minimum.setCellValue(entry.minimumQuantity().doubleValue()); minimum.setCellStyle(numbers); }
        row.createCell(11, CellType.STRING).setCellValue(!entry.minimumUnitMatches() ? "Đơn vị ngưỡng không khớp" : entry.belowMinimum() ? "Dưới mức tối thiểu" : "");
    }
    private String filename(List<String> codes, LocalDate date) {
        String code;
        if (codes.isEmpty()) code = "KHONG-CO-KHO";
        else {
            String all = String.join("-", codes);
            code = all.length() <= 100 ? all : codes.get(0) + "-VA-" + (codes.size() - 1) + "-KHO";
        }
        // No path separators, control characters or header injection from warehouse codes.
        code = code.replaceAll("[^\\p{L}\\p{N}._-]", "_");
        return "So-ton-kho_" + code + "_" + date + ".xlsx";
    }
}