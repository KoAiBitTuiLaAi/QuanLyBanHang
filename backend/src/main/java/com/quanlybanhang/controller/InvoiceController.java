package com.quanlybanhang.controller;

import com.quanlybanhang.dto.request.CreateInvoiceAdjustmentRequest;
import com.quanlybanhang.dto.request.CreateInvoiceRequest;
import com.quanlybanhang.dto.request.InvoicePreviewRequest;
import com.quanlybanhang.dto.response.*;
import com.quanlybanhang.model.InvoiceStatus;
import com.quanlybanhang.service.InvoicePdfService;
import com.quanlybanhang.service.InvoiceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.LocalDate;
import java.util.List;

/**
 * Controller Quản lý Phát hành Hóa đơn Bán hàng (S7-01).
 *
 * Tiêu chí nghiệp vụ:
 * 1. Hoá đơn sinh từ số lượng thực giao, không phải số lượng đặt.
 * 2. Số hoá đơn tự sinh theo dãy liên tục, không trùng và không nhảy cóc.
 * 3. Gộp nhiều phiếu giao của cùng đại lý trong kỳ thành một hoá đơn được.
 * 4. Hoá đơn đã phát hành không sửa và không xoá, chỉ lập chứng từ điều chỉnh.
 * 5. In hoặc xuất PDF được.
 *
 * Phân quyền ma trận database-backed:
 * - INVOICE_VIEW: Xem danh sách, chi tiết, xem trước, tải PDF và in hóa đơn.
 * - INVOICE_CREATE: Phát hành hóa đơn từ các phiếu giao hàng.
 * - INVOICE_ADJUST: Lập chứng từ điều chỉnh hóa đơn đã phát hành.
 * - ROLE_ADMIN: Luôn toàn quyền.
 */
@RestController
@RequestMapping("/api/invoices")
@RequiredArgsConstructor
public class InvoiceController {

    private final InvoiceService invoiceService;
    private final InvoicePdfService invoicePdfService;

    @GetMapping("/dealers-with-eligible-orders")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('INVOICE_VIEW')")
    public ApiResponse getDealersWithEligibleOrders() {
        List<EligibleDealerResponse> dealers = invoiceService.getDealersWithEligibleOrders();
        return ApiResponse.success("Danh sách đại lý có phiếu giao hàng chưa xuất hóa đơn", dealers);
    }

    @GetMapping("/eligible-orders")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('INVOICE_VIEW')")
    public ApiResponse getEligibleOrders(
            @RequestParam Long dealerId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        List<EligibleDeliveryOrderResponse> orders = invoiceService.getEligibleOrders(dealerId, startDate, endDate);
        return ApiResponse.success("Danh sách phiếu giao hợp lệ sẵn sàng xuất hóa đơn", orders);
    }

    @PostMapping("/preview")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('INVOICE_CREATE')")
    public ApiResponse previewInvoice(
            @Valid @RequestBody InvoicePreviewRequest request,
            Principal principal) {
        String username = principal != null ? principal.getName() : "system";
        InvoicePreviewResponse preview = invoiceService.previewInvoice(request, username);
        return ApiResponse.success("Xem trước thông tin hóa đơn", preview);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('INVOICE_CREATE')")
    public ApiResponse issueInvoice(
            @Valid @RequestBody CreateInvoiceRequest request,
            Principal principal) {
        String username = principal != null ? principal.getName() : "system";
        InvoiceResponse response = invoiceService.issueInvoice(request, username);
        return ApiResponse.success("Phát hành hóa đơn thành công", response);
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('INVOICE_VIEW')")
    public ApiResponse search(
            @RequestParam(required = false) Long dealerId,
            @RequestParam(required = false) InvoiceStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<InvoiceResponse> result = invoiceService.search(dealerId, status, startDate, endDate, keyword, page, size);
        return ApiResponse.success("Danh sách hóa đơn bán hàng", result);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('INVOICE_VIEW')")
    public ApiResponse getById(@PathVariable Long id) {
        InvoiceResponse invoice = invoiceService.getById(id);
        return ApiResponse.success("Chi tiết hóa đơn bán hàng", invoice);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('INVOICE_CREATE')")
    public ApiResponse update(@PathVariable Long id) {
        // Chặn sửa theo tiêu chí S7-01: Hóa đơn đã phát hành không sửa và không xóa
        invoiceService.updateInvoice(id);
        return ApiResponse.error("Hóa đơn đã phát hành không được phép chỉnh sửa");
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('INVOICE_CREATE')")
    public ApiResponse delete(@PathVariable Long id) {
        // Chặn xóa theo tiêu chí S7-01: Hóa đơn đã phát hành không sửa và không xóa
        invoiceService.deleteInvoice(id);
        return ApiResponse.error("Hóa đơn đã phát hành không được phép xóa");
    }

    @PostMapping("/{id}/adjustments")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('INVOICE_ADJUST')")
    public ApiResponse createAdjustment(
            @PathVariable Long id,
            @Valid @RequestBody CreateInvoiceAdjustmentRequest request,
            Principal principal) {
        String username = principal != null ? principal.getName() : "system";
        InvoiceAdjustmentResponse response = invoiceService.createAdjustment(id, request, username);
        return ApiResponse.success("Lập chứng từ điều chỉnh thành công", response);
    }

    @GetMapping("/{id}/adjustments")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('INVOICE_VIEW')")
    public ApiResponse getAdjustments(@PathVariable Long id) {
        List<InvoiceAdjustmentResponse> adjustments = invoiceService.getAdjustments(id);
        return ApiResponse.success("Danh sách chứng từ điều chỉnh của hóa đơn", adjustments);
    }

    @GetMapping(value = "/{id}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('INVOICE_VIEW')")
    public ResponseEntity<byte[]> getInvoicePdf(
            @PathVariable Long id,
            @RequestParam(defaultValue = "false") boolean download,
            Principal principal) {
        String username = principal != null ? principal.getName() : "system";
        byte[] pdfBytes = invoicePdfService.generateInvoicePdf(id, username);

        InvoiceResponse inv = invoiceService.getById(id);
        String filename = "hoa-don-" + inv.getInvoiceNumber() + ".pdf";
        String disposition = download ? "attachment; filename=\"" + filename + "\"" : "inline; filename=\"" + filename + "\"";

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdfBytes);
    }

    @GetMapping(value = "/{id}/print-preview", produces = MediaType.TEXT_HTML_VALUE)
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('INVOICE_VIEW')")
    public ResponseEntity<String> getInvoicePrintHtml(
            @PathVariable Long id,
            Principal principal) {
        String username = principal != null ? principal.getName() : "system";
        String html = invoicePdfService.generateInvoicePrintHtml(id, username);
        return ResponseEntity.ok()
                .contentType(MediaType.valueOf("text/html;charset=UTF-8"))
                .body(html);
    }

    @GetMapping(value = "/{id}/adjustments/{adjustmentId}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('INVOICE_VIEW')")
    public ResponseEntity<byte[]> getAdjustmentPdf(
            @PathVariable Long id,
            @PathVariable Long adjustmentId,
            @RequestParam(defaultValue = "false") boolean download,
            Principal principal) {
        // A nested document URL must never return an adjustment of a different invoice.
        boolean belongsToInvoice = invoiceService.getAdjustments(id).stream()
                .anyMatch(adjustment -> adjustmentId.equals(adjustment.getId()));
        if (!belongsToInvoice) {
            throw new IllegalArgumentException("Chứng từ điều chỉnh không thuộc hóa đơn này");
        }
        String username = principal != null ? principal.getName() : "system";
        byte[] pdfBytes = invoicePdfService.generateAdjustmentPdf(adjustmentId, username);

        String filename = "chung-tu-dieu-chinh-" + adjustmentId + ".pdf";
        String disposition = download ? "attachment; filename=\"" + filename + "\"" : "inline; filename=\"" + filename + "\"";

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdfBytes);
    }
}

