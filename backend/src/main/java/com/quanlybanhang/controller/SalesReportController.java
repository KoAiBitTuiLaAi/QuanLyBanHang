package com.quanlybanhang.controller;

import com.quanlybanhang.dto.request.SalesReportFilterRequest;
import com.quanlybanhang.dto.response.ApiResponse;
import com.quanlybanhang.dto.response.SalesReportOptionsResponse;
import com.quanlybanhang.dto.response.SalesReportPageResponse;
import com.quanlybanhang.service.SalesReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.nio.charset.StandardCharsets;

/**
 * Controller Báo cáo doanh số đa chiều (S8-06):
 * - Nhóm theo nhân viên, khu vực, đại lý, nhóm hàng hoặc SKU
 * - Hiển thị doanh số, sản lượng và biên lợi nhuận gộp
 * - Biên lợi nhuận chỉ Quản lý kinh doanh (FINANCE_VIEW_PROFIT_MARGIN / ROLE_ADMIN) xem được
 * - So sánh với cùng kỳ trước và xuất Excel
 *
 * <p>Phân quyền ma trận DB: Yêu cầu quyền {@code REPORT_SALES} hoặc {@code ORDER_VIEW} hoặc {@code ROLE_ADMIN}.
 * Tuyệt đối không dùng role-based fallback.
 */
@RestController
@RequestMapping({"/api/reports/sales", "/reports/sales"})
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN') or hasAuthority('REPORT_SALES') or hasAuthority('ORDER_VIEW')")
public class SalesReportController {

    private static final MediaType XLSX_MEDIA_TYPE = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final SalesReportService salesReportService;

    /**
     * Lấy các tùy chọn cho bộ lọc (khu vực, đại lý, nhóm hàng, nhân viên).
     */
    @GetMapping("/options")
    public ResponseEntity<ApiResponse> getOptions() {
        SalesReportOptionsResponse options = salesReportService.getOptions();
        return ResponseEntity.ok(ApiResponse.success("Tải tùy chọn bộ lọc báo cáo doanh số thành công", options));
    }

    /**
     * Xem báo cáo doanh số đa chiều theo bộ lọc, chiều nhóm và phân trang.
     */
    @GetMapping
    public ResponseEntity<ApiResponse> getReport(@ModelAttribute SalesReportFilterRequest filter) {
        SalesReportPageResponse report = salesReportService.getReport(filter);
        return ResponseEntity.ok(ApiResponse.success("Tải báo cáo doanh số đa chiều thành công", report));
    }

    /**
     * Xuất báo cáo doanh số ra file Excel (.xlsx).
     */
    @GetMapping({"/export", "/excel"})
    public ResponseEntity<byte[]> exportExcel(@ModelAttribute SalesReportFilterRequest filter) {
        byte[] excelBytes = salesReportService.exportExcel(filter);
        String filename = salesReportService.buildExportFilename(filter);

        return ResponseEntity.ok()
                .contentType(XLSX_MEDIA_TYPE)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build().toString())
                .cacheControl(CacheControl.noStore())
                .body(excelBytes);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.badRequest().body(ApiResponse.error("Tham số yêu cầu không hợp lệ hoặc sai định dạng: " + ex.getName()));
    }
}
