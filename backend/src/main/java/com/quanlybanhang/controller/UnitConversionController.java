package com.quanlybanhang.controller;

import com.quanlybanhang.dto.request.UnitConversionRequest;
import com.quanlybanhang.dto.response.ApiResponse;
import com.quanlybanhang.dto.response.UnitConversionResponse;
import com.quanlybanhang.service.UnitConversionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Controller quản lý đơn vị tính quy đổi của SKU (S2-07).
 * Phục vụ Nhân viên kho (ROLE_WAREHOUSE_STAFF), Quản lý kho, Quản lý KD và Admin.
 */
@RestController
@RequestMapping("/api/products/{productId}/unit-conversions")
@RequiredArgsConstructor
public class UnitConversionController {

    private final UnitConversionService unitConversionService;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('PRODUCT_VIEW', 'PRODUCT_CREATE', 'PRODUCT_EDIT', 'ORDER_CREATE', 'WAREHOUSE_VIEW', 'WAREHOUSE_MANAGE', 'WAREHOUSE_IMPORT', 'WAREHOUSE_EXPORT', 'WAREHOUSE_APPROVE', 'WAREHOUSE_ADJUST')")
    public ApiResponse getUnitConversions(@PathVariable Long productId) {
        List<UnitConversionResponse> conversions = unitConversionService.getConversionsByProductId(productId);
        return ApiResponse.success("Danh sách đơn vị quy đổi của sản phẩm", conversions);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN') or (hasAuthority('WAREHOUSE_MANAGE'))")
    public ResponseEntity<ApiResponse> addUnitConversion(
            @PathVariable Long productId,
            @Valid @RequestBody UnitConversionRequest request) {
        UnitConversionResponse response = unitConversionService.addConversion(productId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Thêm đơn vị quy đổi thành công", response));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or (hasAuthority('WAREHOUSE_MANAGE'))")
    public ApiResponse updateUnitConversion(
            @PathVariable Long productId,
            @PathVariable Long id,
            @Valid @RequestBody UnitConversionRequest request) {
        UnitConversionResponse response = unitConversionService.updateConversion(productId, id, request);
        return ApiResponse.success("Cập nhật đơn vị quy đổi thành công", response);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or (hasAuthority('WAREHOUSE_MANAGE'))")
    public ApiResponse deleteUnitConversion(
            @PathVariable Long productId,
            @PathVariable Long id) {
        unitConversionService.deleteConversion(productId, id);
        return ApiResponse.success("Xóa đơn vị quy đổi thành công", null);
    }
}
