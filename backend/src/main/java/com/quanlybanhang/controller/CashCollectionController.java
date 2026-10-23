package com.quanlybanhang.controller;

import com.quanlybanhang.dto.request.CashCollectionRequest;
import com.quanlybanhang.dto.response.ApiResponse;
import com.quanlybanhang.service.CashCollectionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.time.LocalDate;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/finance/cash-collections")
public class CashCollectionController {
    private final CashCollectionService service;
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('COLLECTION_CREATE')")
    public ResponseEntity<ApiResponse> create(@Valid @RequestPart("data") CashCollectionRequest data,
            @RequestPart("receipt") MultipartFile receipt, Authentication a) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("Đã ghi nhận khoản thu, chờ nộp", service.create(data, receipt, a)));
    }
    @PostMapping("/{id}/confirm")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('COLLECTION_CONFIRM')")
    public ApiResponse confirm(@PathVariable Long id, Authentication a) {
        return ApiResponse.success("Đã xác nhận nhận tiền", service.confirm(id, a));
    }
    @GetMapping
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('COLLECTION_CREATE','COLLECTION_CONFIRM','COLLECTION_RECONCILE')")
    public ApiResponse list(@RequestParam LocalDate from, @RequestParam LocalDate to,
            @RequestParam(required = false) Long employeeId, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size, Authentication a) {
        return ApiResponse.success("Danh sách khoản thu", service.list(from, to, employeeId, page, size, a));
    }
    @GetMapping("/reconciliation")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('COLLECTION_CREATE','COLLECTION_RECONCILE')")
    public ApiResponse reconcile(@RequestParam LocalDate from, @RequestParam LocalDate to,
            @RequestParam(required = false) Long employeeId, Authentication a) {
        return ApiResponse.success("Đối chiếu theo nhân viên và ngày thu", service.reconcile(from, to, employeeId, a));
    }
    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('COLLECTION_CREATE','COLLECTION_CONFIRM','COLLECTION_RECONCILE')")
    public ApiResponse detail(@PathVariable Long id, Authentication a) {
        return ApiResponse.success("Chi tiết khoản thu", service.detail(id, a));
    }
    @GetMapping("/{id}/receipt")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('COLLECTION_CREATE','COLLECTION_CONFIRM','COLLECTION_RECONCILE')")
    public ResponseEntity<byte[]> receipt(@PathVariable Long id, Authentication a) {
        var receipt = service.receipt(id, a);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(receipt.contentType()))
                .cacheControl(CacheControl.noStore()).header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=receipt")
                .body(receipt.bytes());
    }
}