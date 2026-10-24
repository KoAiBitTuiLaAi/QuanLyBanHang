package com.quanlybanhang.controller;

import com.quanlybanhang.dto.PickingBatchDtos.*;
import com.quanlybanhang.dto.response.ApiResponse;
import com.quanlybanhang.service.PickingBatchService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/warehouse/picking-batches") @RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN') or hasAuthority('WAREHOUSE_VIEW') or hasAuthority('WAREHOUSE_EXPORT')")
public class PickingBatchController {
    private final PickingBatchService service;
    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public org.springframework.http.ResponseEntity<ApiResponse> statusError(org.springframework.web.server.ResponseStatusException ex) {
        return org.springframework.http.ResponseEntity.status(ex.getStatusCode()).body(ApiResponse.error(ex.getReason()));
    }
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public org.springframework.http.ResponseEntity<ApiResponse> duplicateClaim() {
        return org.springframework.http.ResponseEntity.status(409).body(ApiResponse.error("Đơn hoặc mã phiếu vừa được dùng bởi một lượt soạn khác. Hãy tải lại danh sách."));
    }
    @GetMapping("/options") public ApiResponse options() { return ApiResponse.success("Bộ chọn phiếu soạn", service.options()); }
    @GetMapping("/eligible-orders") public ApiResponse eligible(@RequestParam Long warehouseId,
            @RequestParam(required = false) String keyword, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success("Đơn có thể soạn", service.eligible(warehouseId, keyword, page, size));
    }
    @GetMapping public ApiResponse list(@RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) String status, @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success("Danh sách lượt soạn", service.search(warehouseId, status, keyword, page, size));
    }
    @GetMapping("/{id}") public ApiResponse get(@PathVariable Long id) { return ApiResponse.success("Chi tiết lượt soạn", service.get(id)); }
    @PostMapping @PreAuthorize("hasRole('ADMIN') or hasAuthority('WAREHOUSE_EXPORT')")
    public ApiResponse create(@Valid @RequestBody Create request) { return ApiResponse.success("Đã tạo lượt soạn", service.create(request)); }
    @PutMapping("/{id}/items/{itemId}") @PreAuthorize("hasRole('ADMIN') or hasAuthority('WAREHOUSE_EXPORT')")
    public ApiResponse update(@PathVariable Long id, @PathVariable Long itemId, @Valid @RequestBody UpdateLine request) {
        return ApiResponse.success("Đã lưu thực soạn", service.update(id, itemId, request));
    }
    @PostMapping("/{id}/complete") @PreAuthorize("hasRole('ADMIN') or hasAuthority('WAREHOUSE_EXPORT')")
    public ApiResponse complete(@PathVariable Long id, @Valid @RequestBody Version request) {
        return ApiResponse.success("Đã hoàn tất lượt soạn", service.complete(id, request.version()));
    }
    @PostMapping("/{id}/cancel") @PreAuthorize("hasRole('ADMIN') or hasAuthority('WAREHOUSE_EXPORT')")
    public ApiResponse cancel(@PathVariable Long id, @Valid @RequestBody Cancel request) {
        return ApiResponse.success("Đã hủy lượt soạn", service.cancel(id, request));
    }
}
