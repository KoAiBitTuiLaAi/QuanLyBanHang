package com.quanlybanhang.controller;

import com.quanlybanhang.dto.response.ApiResponse;
import com.quanlybanhang.dto.response.DealerDebtAgingLedgerResponse;
import com.quanlybanhang.service.DealerDebtAgingLedgerService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

/**
 * Controller Sổ công nợ theo đại lý với phân tích tuổi nợ (S7-03):
 * - Xem các cột: phải thu, đã thu, còn nợ, chia theo nhóm tuổi nợ 0–30, 31–60, 61–90 và trên 90 ngày
 * - Lọc riêng nhóm quá hạn (overdueOnly)
 * - Hiển thị tổng cộng ở chân bảng (summary)
 * - Tập trung đòi những khoản nợ lâu nhất trước (ưu tiên nhóm nợ lâu ngày lên đầu)
 * - Xuất được ra Excel (.xlsx)
 *
 * <p>Ma trận phân quyền DB: Yêu cầu quyền {@code FINANCE_VIEW_DEBT} hoặc {@code ROLE_ADMIN}.
 * Tuyệt đối không dùng role-based fallback.
 */
@RestController
@RequestMapping({
        "/api/finance/debt-aging-ledger",
        "/api/finance/dealer-debt-aging"
})
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN') or hasAuthority('FINANCE_VIEW_DEBT')")
public class DealerDebtAgingLedgerController {

    private static final MediaType XLSX_MEDIA_TYPE = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final DealerDebtAgingLedgerService ledgerService;

    /**
     * Xem sổ công nợ theo đại lý với phân tích tuổi nợ.
     */
    @GetMapping
    public ResponseEntity<ApiResponse> getLedger(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOfDate,
            @RequestParam(required = false, defaultValue = "false") Boolean overdueOnly,
            @RequestParam(required = false) Long dealerId,
            @RequestParam(required = false) String search) {
        DealerDebtAgingLedgerResponse ledger = ledgerService.getLedger(asOfDate, overdueOnly, dealerId, search);
        return ResponseEntity.ok(ApiResponse.success("Tải sổ công nợ theo đại lý với phân tích tuổi nợ thành công", ledger));
    }

    /**
     * Xuất sổ công nợ theo đại lý với phân tích tuổi nợ ra file Excel (.xlsx).
     */
    @GetMapping({"/export", "/export/excel", "/excel"})
    public ResponseEntity<byte[]> exportExcel(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOfDate,
            @RequestParam(required = false, defaultValue = "false") Boolean overdueOnly,
            @RequestParam(required = false) Long dealerId,
            @RequestParam(required = false) String search) {
        DealerDebtAgingLedgerResponse ledger = ledgerService.getLedger(asOfDate, overdueOnly, dealerId, search);
        byte[] excelBytes = ledgerService.exportExcel(asOfDate, overdueOnly, dealerId, search);
        String filename = ledgerService.buildExportFilename(ledger);

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

