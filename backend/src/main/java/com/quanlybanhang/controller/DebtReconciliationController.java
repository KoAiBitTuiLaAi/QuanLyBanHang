package com.quanlybanhang.controller;

import com.quanlybanhang.dto.DebtReconciliationDtos.*;
import com.quanlybanhang.dto.response.ApiResponse;
import com.quanlybanhang.service.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

@RestController @RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN') or hasAuthority('FINANCE_VIEW_DEBT')")
public class DebtReconciliationController {
    private final DebtReconciliationService service;
    private final DebtReconciliationPdfService pdf;
    private boolean portal(HttpServletRequest request) {
        return request.getRequestURI().substring(request.getContextPath().length()).startsWith("/api/portal/");
    }

    @GetMapping({"/api/finance/debt-reconciliations/options", "/api/portal/debt-reconciliations/options"})
    public ApiResponse options(@RequestParam(required = false) String keyword, @RequestParam(defaultValue = "0") int page,
                               Authentication a, HttpServletRequest request) {
        return ApiResponse.success("Đại lý được đối chiếu", service.options(keyword, page, a, portal(request)));
    }
    @PostMapping("/api/finance/debt-reconciliations")
    @PreAuthorize("hasRole('ADMIN') or (hasAuthority('FINANCE_VIEW_DEBT') and hasAuthority('FINANCE_MANAGE'))")
    public ApiResponse create(@Valid @RequestBody Create body, Authentication a) {
        return ApiResponse.success("Đã lưu biên bản đối chiếu", service.create(body, a));
    }
    @GetMapping({"/api/finance/debt-reconciliations", "/api/portal/debt-reconciliations"})
    public ApiResponse list(@RequestParam(required = false) Long dealerId, @RequestParam(required = false) LocalDate from,
                           @RequestParam(required = false) LocalDate to, @RequestParam(defaultValue = "0") int page,
                           Authentication a, HttpServletRequest request) {
        return ApiResponse.success("Danh sách biên bản", service.list(dealerId, from, to, page, a, portal(request)));
    }
    @GetMapping({"/api/finance/debt-reconciliations/{id}", "/api/portal/debt-reconciliations/{id}"})
    public ApiResponse get(@PathVariable Long id, Authentication a, HttpServletRequest request) {
        return ApiResponse.success("Biên bản đối chiếu", service.get(id, a, portal(request)));
    }
    @PostMapping("/api/portal/debt-reconciliations/{id}/response")
    public ApiResponse respond(@PathVariable Long id, @Valid @RequestBody Feedback body, Authentication a) {
        return ApiResponse.success("Đã lưu phản hồi của đại lý", service.respond(id, body, a));
    }
    @GetMapping({"/api/finance/debt-reconciliations/{id}/pdf", "/api/portal/debt-reconciliations/{id}/pdf"})
    public ResponseEntity<byte[]> export(@PathVariable Long id, Authentication a, HttpServletRequest request) throws Exception {
        var record = service.get(id, a, portal(request));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("Bien-ban-" + record.code() + ".pdf", StandardCharsets.UTF_8).build().toString())
                .body(pdf.pdf(record));
    }
    @ExceptionHandler({org.springframework.dao.DataIntegrityViolationException.class,
            org.springframework.dao.OptimisticLockingFailureException.class,
            org.springframework.dao.PessimisticLockingFailureException.class})
    public ResponseEntity<ApiResponse> conflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error("Yêu cầu trùng hoặc biên bản đang thay đổi; hãy tải lại danh sách"));
    }
}
