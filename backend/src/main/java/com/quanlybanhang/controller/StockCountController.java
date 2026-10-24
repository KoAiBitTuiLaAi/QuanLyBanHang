package com.quanlybanhang.controller;

import com.quanlybanhang.dto.request.*;
import com.quanlybanhang.dto.response.ApiResponse;
import com.quanlybanhang.dto.response.StockCountOptionsResponse;
import com.quanlybanhang.dto.response.StockCountResponse;
import com.quanlybanhang.model.StockCountStatus;
import com.quanlybanhang.service.StockCountService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

/**
 * Controller Quản lý Phiếu Kiểm Kê Kho (S5-08).
 *
 * Nghiệp vụ:
 * - Tạo phiếu kiểm kê theo kho hoặc theo nhóm hàng, hệ thống chốt số tồn sổ tại thời điểm tạo.
 * - Nhập số đếm thực tế, hệ thống tính chênh lệch từng dòng.
 * - Chốt kiểm kê điều chỉnh tồn về số thực đếm và bắt buộc nhập lý do.
 * - Chốt phiếu cần ROLE_ADMIN hoặc quyền WAREHOUSE_ADJUST từ ma trận, không cần vai trò kho.
 */
@RestController
@RequestMapping("/api/warehouse/stock-counts")
@RequiredArgsConstructor
public class StockCountController {

    private final StockCountService stockCountService;

    @GetMapping("/options")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('WAREHOUSE_VIEW', 'WAREHOUSE_ADJUST')")
    public ApiResponse getOptions() {
        StockCountOptionsResponse options = stockCountService.getOptions();
        return ApiResponse.success("Tùy chọn kiểm kê kho", options);
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('WAREHOUSE_VIEW', 'WAREHOUSE_ADJUST')")
    public ApiResponse search(
            @RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) StockCountStatus status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<StockCountResponse> result = stockCountService.search(warehouseId, categoryId, status, keyword, page, size);
        return ApiResponse.success("Danh sách phiếu kiểm kê kho", result);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('WAREHOUSE_VIEW', 'WAREHOUSE_ADJUST')")
    public ApiResponse getById(@PathVariable Long id) {
        StockCountResponse result = stockCountService.getById(id);
        return ApiResponse.success("Chi tiết phiếu kiểm kê kho", result);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('WAREHOUSE_VIEW', 'WAREHOUSE_ADJUST')")
    public ApiResponse create(
            @Valid @RequestBody StockCountCreateRequest request,
            Principal principal) {
        String username = principal != null ? principal.getName() : "system";
        StockCountResponse result = stockCountService.create(request, username);
        return ApiResponse.success("Tạo phiếu kiểm kê kho thành công", result);
    }

    @PutMapping("/{id}/items")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('WAREHOUSE_VIEW', 'WAREHOUSE_ADJUST')")
    public ApiResponse updateItems(
            @PathVariable Long id,
            @Valid @RequestBody StockCountUpdateItemsRequest request,
            Principal principal) {
        String username = principal != null ? principal.getName() : "system";
        StockCountResponse result = stockCountService.updateItems(id, request, username);
        return ApiResponse.success("Cập nhật số đếm thực tế thành công", result);
    }

    /**
     * Chốt phiếu kiểm kê kho và điều chỉnh tồn về số thực đếm.
     * Bắt buộc nhập lý do giải trình.
     * Chốt bằng quyền WAREHOUSE_ADJUST hoặc ROLE_ADMIN, không dùng vai trò kho để cấp quyền.
     */
    @PostMapping("/{id}/complete")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('WAREHOUSE_ADJUST')")
    public ApiResponse complete(
            @PathVariable Long id,
            @Valid @RequestBody StockCountCompleteRequest request,
            Principal principal) {
        String username = principal != null ? principal.getName() : "system";
        StockCountResponse result = stockCountService.complete(id, request, username);
        return ApiResponse.success("Chốt kiểm kê và điều chỉnh tồn kho thành công", result);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('WAREHOUSE_VIEW', 'WAREHOUSE_ADJUST')")
    public ApiResponse cancel(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) StockCountCancelRequest request,
            Principal principal) {
        String username = principal != null ? principal.getName() : "system";
        StockCountResponse result = stockCountService.cancel(id, request, username);
        return ApiResponse.success("Hủy phiếu kiểm kê kho thành công", result);
    }
}
