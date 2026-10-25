package com.quanlybanhang.controller;

import com.quanlybanhang.dto.response.ApiResponse;
import com.quanlybanhang.model.StockStatus;
import com.quanlybanhang.service.StockLedgerExcelService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/warehouse/stock-ledger/export")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN') or hasAuthority('WAREHOUSE_VIEW')")
public class StockLedgerExportController {
    private final StockLedgerExcelService excel;
    @GetMapping
    public ResponseEntity<byte[]> export(@RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) Long categoryId, @RequestParam(required = false) String keyword,
            @RequestParam(required = false) StockStatus stockStatus,
            @RequestParam(defaultValue = "false") boolean belowMinimum) throws IOException {
        var result = excel.export(warehouseId, categoryId, keyword, stockStatus, belowMinimum);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(result.filename(), StandardCharsets.UTF_8).build().toString())
                .cacheControl(CacheControl.noStore()).body(result.bytes());
    }
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse> invalidFilter() {
        return ResponseEntity.badRequest().body(ApiResponse.error("Bộ lọc không hợp lệ"));
    }
}