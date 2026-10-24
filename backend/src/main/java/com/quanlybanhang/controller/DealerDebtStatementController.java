package com.quanlybanhang.controller;

import com.quanlybanhang.dto.response.ApiResponse;
import com.quanlybanhang.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/finance/debt/{dealerId}/statement")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN') or hasAuthority('FINANCE_VIEW_DEBT')")
public class DealerDebtStatementController {
    private final DealerDebtStatementService statements;
    private final DealerDebtStatementExportService exports;
    @GetMapping
    public ApiResponse statement(@PathVariable Long dealerId,@RequestParam LocalDate from,@RequestParam LocalDate to) {
        return ApiResponse.success("Sổ chi tiết công nợ",statements.statement(dealerId,from,to));
    }
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@PathVariable Long dealerId,@RequestParam LocalDate from,@RequestParam LocalDate to,
            @RequestParam String format) throws Exception {
        if(!format.equalsIgnoreCase("pdf")&&!format.equalsIgnoreCase("xlsx"))throw new IllegalArgumentException("Định dạng phải là pdf hoặc xlsx");
        var report=statements.statement(dealerId,from,to);boolean pdf=format.equalsIgnoreCase("pdf");
        byte[] bytes=pdf?exports.pdf(report):exports.excel(report);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(pdf?"application/pdf":"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(exports.filename(report,pdf?"pdf":"xlsx"),StandardCharsets.UTF_8).build().toString())
                .cacheControl(CacheControl.noStore()).body(bytes);
    }
    @ExceptionHandler({org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class})
    public ResponseEntity<ApiResponse> invalidParameters(){return ResponseEntity.badRequest().body(ApiResponse.error("Thiếu hoặc sai định dạng đại lý/khoảng ngày/định dạng xuất"));}
    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<ApiResponse> status(org.springframework.web.server.ResponseStatusException ex){return ResponseEntity.status(ex.getStatusCode()).body(ApiResponse.error(ex.getReason()));}
}