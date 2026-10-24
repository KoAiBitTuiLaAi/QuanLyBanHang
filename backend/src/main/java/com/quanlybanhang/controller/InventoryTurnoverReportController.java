package com.quanlybanhang.controller;

import com.quanlybanhang.dto.response.*;
import com.quanlybanhang.service.InventoryTurnoverExcelExportService;
import com.quanlybanhang.service.InventoryTurnoverReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

/**
 * Controller Báo cáo tồn kho và vòng quay hàng (S8-07):
 * - Giá trị tồn theo kho và theo nhóm hàng
 * - Số ngày tồn bình quân của từng SKU, chỉ ra hàng chậm luân chuyển đang chôn vốn
 * - Xuất Excel (.xlsx)
 *
 * <p>Phân quyền ma trận DB: yêu cầu quyền {@code REPORT_INVENTORY} hoặc {@code ROLE_ADMIN}.
 * Tuyệt đối không fallback theo role tĩnh.
 */
@RestController
@RequestMapping({
        "/api/reports/inventory-turnover",
        "/api/reports/inventory",
        "/api/warehouse/reports/inventory-turnover",
        "/api/warehouse/inventory-turnover"
})
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN') or hasAuthority('REPORT_INVENTORY')")
public class InventoryTurnoverReportController {

    private static final MediaType XLSX_MEDIA_TYPE = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final InventoryTurnoverReportService reportService;
    private final InventoryTurnoverExcelExportService excelExportService;

    /**
     * Lấy bộ lọc tùy chọn (kho được phân quyền, nhóm hàng, ngưỡng mặc định).
     */
    @GetMapping("/options")
    public ResponseEntity<ApiResponse> getOptions() {
        InventoryTurnoverOptionsResponse data = reportService.getOptions();
        return ResponseEntity.ok(ApiResponse.success("Tải bộ lọc báo cáo tồn kho và vòng quay hàng thành công", data));
    }

    /**
     * Xem báo cáo tổng hợp tồn kho và vòng quay hàng:
     * - Giá trị tồn theo kho
     * - Giá trị tồn theo nhóm hàng
     * - Giá trị tồn theo Kho × Nhóm hàng
     * - Số ngày tồn bình quân của từng SKU & danh sách hàng chậm luân chuyển đang chôn vốn
     */
    @GetMapping
    public ResponseEntity<ApiResponse> getReport(
            @RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) Integer periodDays,
            @RequestParam(required = false) Integer slowMovingThresholdDays,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean slowMovingOnly,
            @RequestParam(required = false) String turnoverStatus,
            @RequestParam(required = false) Boolean includeZeroStock) {
        InventoryTurnoverReportResponse report = reportService.getReport(
                warehouseId,
                categoryId,
                fromDate,
                toDate,
                periodDays,
                slowMovingThresholdDays,
                keyword,
                slowMovingOnly,
                turnoverStatus,
                includeZeroStock);
        return ResponseEntity.ok(ApiResponse.success("Tải báo cáo tồn kho và vòng quay hàng thành công", report));
    }

    /**
     * Giá trị tồn và tốc độ luân chuyển tổng hợp theo từng kho.
     */
    @GetMapping({"/by-warehouse", "/warehouses"})
    public ResponseEntity<ApiResponse> getValuationByWarehouse(
            @RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) Integer periodDays,
            @RequestParam(required = false) Integer slowMovingThresholdDays) {
        List<WarehouseInventoryValuationResponse> data = reportService.getValuationByWarehouse(
                warehouseId,
                categoryId,
                fromDate,
                toDate,
                periodDays,
                slowMovingThresholdDays);
        return ResponseEntity.ok(ApiResponse.success("Giá trị tồn kho theo kho", data));
    }

    /**
     * Giá trị tồn và tốc độ luân chuyển tổng hợp theo từng nhóm hàng.
     */
    @GetMapping({"/by-category", "/categories"})
    public ResponseEntity<ApiResponse> getValuationByCategory(
            @RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) Integer periodDays,
            @RequestParam(required = false) Integer slowMovingThresholdDays) {
        List<CategoryInventoryValuationResponse> data = reportService.getValuationByCategory(
                warehouseId,
                categoryId,
                fromDate,
                toDate,
                periodDays,
                slowMovingThresholdDays);
        return ResponseEntity.ok(ApiResponse.success("Giá trị tồn kho theo nhóm hàng", data));
    }

    /**
     * Giá trị tồn chi tiết theo cặp Kho × Nhóm hàng.
     */
    @GetMapping("/by-warehouse-category")
    public ResponseEntity<ApiResponse> getValuationByWarehouseAndCategory(
            @RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) Integer periodDays,
            @RequestParam(required = false) Integer slowMovingThresholdDays) {
        List<WarehouseCategoryValuationResponse> data = reportService.getValuationByWarehouseAndCategory(
                warehouseId,
                categoryId,
                fromDate,
                toDate,
                periodDays,
                slowMovingThresholdDays);
        return ResponseEntity.ok(ApiResponse.success("Giá trị tồn kho theo kho và nhóm hàng", data));
    }

    /**
     * Số ngày tồn bình quân và hệ số vòng quay của từng SKU (hỗ trợ phân trang).
     */
    @GetMapping({"/skus", "/items"})
    public ResponseEntity<ApiResponse> getSkuTurnovers(
            @RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) Integer periodDays,
            @RequestParam(required = false) Integer slowMovingThresholdDays,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean slowMovingOnly,
            @RequestParam(required = false) String turnoverStatus,
            @RequestParam(required = false) Boolean includeZeroStock,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        Page<SkuInventoryTurnoverResponse> data = reportService.getSkuTurnovers(
                warehouseId,
                categoryId,
                fromDate,
                toDate,
                periodDays,
                slowMovingThresholdDays,
                keyword,
                slowMovingOnly,
                turnoverStatus,
                includeZeroStock,
                page,
                size);
        return ResponseEntity.ok(ApiResponse.success("Danh sách số ngày tồn bình quân và vòng quay theo SKU", data));
    }

    /**
     * Danh sách các SKU chậm luân chuyển đang chôn vốn (sắp xếp theo giá trị chôn vốn giảm dần).
     */
    @GetMapping("/slow-moving")
    public ResponseEntity<ApiResponse> getSlowMovingSkus(
            @RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) Integer periodDays,
            @RequestParam(required = false) Integer slowMovingThresholdDays,
            @RequestParam(required = false) String keyword) {
        List<SkuInventoryTurnoverResponse> data = reportService.getSlowMovingSkus(
                warehouseId,
                categoryId,
                fromDate,
                toDate,
                periodDays,
                slowMovingThresholdDays,
                keyword);
        return ResponseEntity.ok(ApiResponse.success("Danh sách hàng chậm luân chuyển đang chôn vốn", data));
    }

    /**
     * Xuất báo cáo tồn kho và vòng quay hàng ra file Excel (.xlsx).
     */
    @GetMapping({"/export", "/export/excel", "/excel"})
    public ResponseEntity<byte[]> exportExcel(
            @RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) Integer periodDays,
            @RequestParam(required = false) Integer slowMovingThresholdDays,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean slowMovingOnly,
            @RequestParam(required = false) String turnoverStatus,
            @RequestParam(required = false) Boolean includeZeroStock) {
        InventoryTurnoverReportResponse report = reportService.getReport(
                warehouseId,
                categoryId,
                fromDate,
                toDate,
                periodDays,
                slowMovingThresholdDays,
                keyword,
                slowMovingOnly,
                turnoverStatus,
                includeZeroStock);

        byte[] bytes = excelExportService.exportToExcel(report);
        String filename = excelExportService.buildFileName(report);
        ContentDisposition contentDisposition = ContentDisposition.attachment()
                .filename(filename, StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .contentType(XLSX_MEDIA_TYPE)
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition.toString())
                .contentLength(bytes.length)
                .body(bytes);
    }
}

