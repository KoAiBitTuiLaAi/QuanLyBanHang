package com.quanlybanhang.controller;

import com.quanlybanhang.dto.request.CancelDealerReturnRequest;
import com.quanlybanhang.dto.request.ConfirmDealerReturnRequest;
import com.quanlybanhang.dto.request.CreateDealerReturnRequest;
import com.quanlybanhang.dto.response.*;
import com.quanlybanhang.model.DealerReturnStatus;
import com.quanlybanhang.service.DealerReturnService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.time.LocalDate;

/**
 * Controller Quản lý Phiếu trả hàng từ đại lý và nhập lại kho (S8-01).
 *
 * Tiêu chí nghiệp vụ:
 * 1. Chọn hoá đơn gốc, chọn dòng hàng và số lượng trả, bắt buộc chọn lý do trả.
 * 2. Số lượng trả không vượt số lượng đã giao trừ phần đã trả trước đó.
 * 3. Hàng trả về nhập lại đúng lô nếu SKU có quản lý lô; hàng hỏng nhập vào kho hàng lỗi.
 * 4. Phiếu trả phải được Quản lý kho xác nhận nhận hàng mới cộng tồn.
 *
 * Phân quyền ma trận database-backed:
 * - Lập phiếu trả: ORDER_CREATE (hoặc ROLE_ADMIN)
 * - Xác nhận nhận hàng trả & cộng tồn kho: WAREHOUSE_APPROVE (hoặc ROLE_ADMIN)
 * - Tra cứu / Xem phiếu trả & hóa đơn gốc đủ điều kiện: ORDER_VIEW, ORDER_CREATE, INVOICE_VIEW, WAREHOUSE_VIEW, WAREHOUSE_APPROVE, WAREHOUSE_IMPORT (hoặc ROLE_ADMIN)
 */
@RestController
@RequestMapping({"/api/dealer-returns", "/api/warehouse/dealer-returns", "/api/returns"})
@RequiredArgsConstructor
public class DealerReturnController {

    private final DealerReturnService dealerReturnService;

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResponse> handleResponseStatusException(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).body(ApiResponse.error(ex.getReason()));
    }

    @GetMapping("/options")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('ORDER_VIEW', 'ORDER_CREATE', 'INVOICE_VIEW', 'WAREHOUSE_VIEW', 'WAREHOUSE_APPROVE', 'WAREHOUSE_IMPORT')")
    public ApiResponse getOptions() {
        DealerReturnOptionsResponse options = dealerReturnService.getOptions();
        return ApiResponse.success("Bộ tùy chọn lập phiếu trả hàng từ đại lý", options);
    }

    @GetMapping("/eligible-invoices")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('ORDER_VIEW', 'ORDER_CREATE', 'INVOICE_VIEW', 'WAREHOUSE_VIEW', 'WAREHOUSE_APPROVE')")
    public ApiResponse getEligibleInvoices(
            @RequestParam(required = false) Long dealerId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<EligibleReturnInvoiceResponse> result = dealerReturnService.getEligibleInvoices(dealerId, keyword, page, size);
        return ApiResponse.success("Danh sách hóa đơn gốc đủ điều kiện lập phiếu trả hàng", result);
    }

    @GetMapping("/eligible-invoices/{invoiceId}")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('ORDER_VIEW', 'ORDER_CREATE', 'INVOICE_VIEW', 'WAREHOUSE_VIEW', 'WAREHOUSE_APPROVE')")
    public ApiResponse getEligibleInvoiceById(@PathVariable Long invoiceId) {
        EligibleReturnInvoiceResponse result = dealerReturnService.getEligibleInvoiceById(invoiceId);
        return ApiResponse.success("Chi tiết hóa đơn gốc và số lượng còn được trả", result);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('ORDER_CREATE')")
    public ApiResponse createReturn(
            @Valid @RequestBody CreateDealerReturnRequest request,
            Principal principal) {
        String username = principal != null ? principal.getName() : "system";
        DealerReturnResponse response = dealerReturnService.createReturn(request, username);
        return ApiResponse.success("Lập phiếu trả hàng thành công (chờ Quản lý kho xác nhận nhận hàng mới cộng tồn)", response);
    }

    @RequestMapping(value = "/{id}/confirm", method = {RequestMethod.POST, RequestMethod.PUT})
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('WAREHOUSE_APPROVE')")
    public ApiResponse confirmReturn(
            @PathVariable Long id,
            @RequestBody(required = false) ConfirmDealerReturnRequest request,
            Principal principal) {
        String username = principal != null ? principal.getName() : "system";
        DealerReturnResponse response = dealerReturnService.confirmReturn(id, request, username);
        return ApiResponse.success("Quản lý kho xác nhận nhận hàng trả thành công, đã cộng tồn kho", response);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('ORDER_CREATE', 'ORDER_CANCEL', 'WAREHOUSE_APPROVE')")
    public ApiResponse cancelReturn(
            @PathVariable Long id,
            @RequestBody(required = false) CancelDealerReturnRequest request,
            Principal principal) {
        String username = principal != null ? principal.getName() : "system";
        DealerReturnResponse response = dealerReturnService.cancelReturn(id, request, username);
        return ApiResponse.success("Đã hủy phiếu trả hàng", response);
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('ORDER_VIEW', 'ORDER_CREATE', 'INVOICE_VIEW', 'WAREHOUSE_VIEW', 'WAREHOUSE_APPROVE', 'WAREHOUSE_IMPORT')")
    public ApiResponse search(
            @RequestParam(required = false) Long dealerId,
            @RequestParam(required = false) Long invoiceId,
            @RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) DealerReturnStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<DealerReturnResponse> result = dealerReturnService.search(
                dealerId, invoiceId, warehouseId, status, fromDate, toDate, keyword, page, size);
        return ApiResponse.success("Danh sách phiếu trả hàng từ đại lý", result);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('ORDER_VIEW', 'ORDER_CREATE', 'INVOICE_VIEW', 'WAREHOUSE_VIEW', 'WAREHOUSE_APPROVE', 'WAREHOUSE_IMPORT')")
    public ApiResponse getById(@PathVariable Long id) {
        DealerReturnResponse response = dealerReturnService.getById(id);
        return ApiResponse.success("Chi tiết phiếu trả hàng từ đại lý", response);
    }
}

