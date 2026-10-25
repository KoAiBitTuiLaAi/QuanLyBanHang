package com.quanlybanhang.controller;

import com.quanlybanhang.dto.response.ApiResponse;
import com.quanlybanhang.dto.response.DebtAgingOptionsResponse;
import com.quanlybanhang.dto.response.DebtAgingReportResponse;
import com.quanlybanhang.service.DebtAgingReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

/**
 * Controller Báo cáo công nợ quá hạn theo tuổi nợ (S8-09):
 * - Tổng hợp theo nhóm tuổi nợ và theo nhân viên phụ trách
 * - Chỉ ra đại lý có rủi ro cao nhất (thứ tự ưu tiên xử lý trước)
 * - Xuất Excel (.xlsx)
 *
 * <p>Phân quyền ma trận DB: Yêu cầu quyền {@code REPORT_FINANCE} hoặc {@code ROLE_ADMIN}.
 * Tuyệt đối không dùng role-based fallback.
 */
@RestController
@RequestMapping({
        "/api/reports/debt-aging",
        "/api/finance/reports/debt-aging"
})
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN') or hasAuthority('REPORT_FINANCE')")
public class DebtAgingReportController {

    private static final MediaType XLSX_MEDIA_TYPE = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final DebtAgingReportService reportService;

    /**
     * Lấy tùy chọn bộ lọc báo cáo tuổi nợ (ngày chốt mặc định, múi giờ, định nghĩa nhóm tuổi nợ, danh sách nhân viên).
     */
    @GetMapping("/options")
    public ResponseEntity<ApiResponse> getOptions() {
        DebtAgingOptionsResponse options = reportService.getOptions();
        return ResponseEntity.ok(ApiResponse.success("Tải tùy chọn báo cáo tuổi nợ thành công", options));
    }

    /**
     * Xem báo cáo tổng hợp công nợ quá hạn theo tuổi nợ:
     * - Tổng hợp toàn hệ thống
     * - Tổng hợp theo nhóm tuổi nợ (1-14 ngày, 15-44 ngày, từ 45 ngày)
     * - Tổng hợp theo nhân viên phụ trách
     * - Danh sách đại lý sắp xếp theo thứ tự mức độ rủi ro (ngày quá hạn & số tiền giảm dần)
     */
    @GetMapping
    public ResponseEntity<ApiResponse> getReport(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOfDate,
            @RequestParam(required = false) Long employeeId,
            @RequestParam(required = false, defaultValue = "false") Boolean unassignedOnly) {
        DebtAgingReportResponse report = reportService.getReport(asOfDate, employeeId, unassignedOnly);
        return ResponseEntity.ok(ApiResponse.success("Tải báo cáo công nợ quá hạn theo tuổi nợ thành công", report));
    }

    /**
     * Xuất báo cáo công nợ quá hạn theo tuổi nợ ra file Excel (.xlsx).
     */
    @GetMapping({"/export", "/export/excel", "/excel"})
    public ResponseEntity<byte[]> exportExcel(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOfDate,
            @RequestParam(required = false) Long employeeId,
            @RequestParam(required = false, defaultValue = "false") Boolean unassignedOnly) {
        DebtAgingReportResponse report = reportService.getReport(asOfDate, employeeId, unassignedOnly);
        byte[] excelBytes = reportService.exportExcel(asOfDate, employeeId, unassignedOnly);
        String filename = reportService.buildExportFilename(report);

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

