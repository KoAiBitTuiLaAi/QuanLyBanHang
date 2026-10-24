package com.quanlybanhang.controller;

import com.quanlybanhang.dto.request.*;
import com.quanlybanhang.dto.response.ApiResponse;
import com.quanlybanhang.service.ReturnOrderService;
import com.quanlybanhang.model.ReturnOrderStatus;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;

@RestController
@RequestMapping("/api/invoices/returns")
@RequiredArgsConstructor
public class ReturnOrderController {
    private final ReturnOrderService service;
    @GetMapping("/options")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('INVOICE_VIEW','INVOICE_ADJUST')")
    public ApiResponse options() {
        return ApiResponse.success("Trạng thái phiếu trả hàng", java.util.Map.of("statuses",
                java.util.Arrays.stream(ReturnOrderStatus.values()).map(status -> java.util.Map.of("code", status.name(), "label", status.getDisplayName())).toList()));
    }
    @GetMapping("/eligible-invoices")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('INVOICE_ADJUST')")
    public ApiResponse sourceInvoices(@RequestParam(required=false) String keyword,
            @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size) {
        return ApiResponse.success("Hóa đơn nguồn", service.sourceInvoices(keyword, paging(page,size)));
    }
    @GetMapping("/eligible-invoices/{invoiceId}")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('INVOICE_ADJUST')")
    public ApiResponse sourceInvoice(@PathVariable Long invoiceId) {
        return ApiResponse.success("Số lượng và giá gốc còn được trả", service.sourceInvoice(invoiceId));
    }
    @PostMapping
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('INVOICE_ADJUST')")
    public ResponseEntity<ApiResponse> create(@Valid @RequestBody CreateReturnOrderRequest request, Principal principal) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("Đã lập phiếu trả hàng nháp", service.create(request, principal.getName())));
    }
    @PostMapping("/{id}/confirm")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('INVOICE_ADJUST')")
    public ApiResponse confirm(@PathVariable Long id, Principal principal) {
        return ApiResponse.success("Đã xác nhận trả hàng và lập chứng từ giảm công nợ", service.confirm(id, principal.getName()));
    }
    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('INVOICE_ADJUST')")
    public ApiResponse cancel(@PathVariable Long id, @Valid @RequestBody CancelReturnOrderRequest request, Principal principal) {
        return ApiResponse.success("Đã hủy phiếu trả hàng nháp", service.cancel(id, request, principal.getName()));
    }
    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('INVOICE_VIEW')")
    public ApiResponse detail(@PathVariable Long id) { return ApiResponse.success("Chi tiết phiếu trả hàng", service.getById(id)); }
    @GetMapping("/by-invoice/{invoiceId}")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('INVOICE_VIEW')")
    public ApiResponse byInvoice(@PathVariable Long invoiceId) { return ApiResponse.success("Phiếu trả theo hóa đơn", service.getByInvoiceId(invoiceId)); }
    @GetMapping
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('INVOICE_VIEW')")
    public ApiResponse search(@RequestParam(required=false) Long dealerId, @RequestParam(required=false) Long invoiceId,
            @RequestParam(required=false) String status, @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size) {
        return ApiResponse.success("Danh sách phiếu trả hàng", service.search(dealerId, invoiceId, status,
                paging(page,size)));
    }
    private PageRequest paging(int page,int size) {
        if(page<0||size<1||size>100)throw new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST,"Trang phải không âm, số dòng từ 1 đến 100");
        return PageRequest.of(page,size,Sort.by("id").descending());
    }
}
