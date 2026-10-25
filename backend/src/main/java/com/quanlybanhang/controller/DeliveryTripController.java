package com.quanlybanhang.controller;

import com.quanlybanhang.dto.request.CreateDeliveryTripRequest;
import com.quanlybanhang.dto.request.ReturnStopGoodsRequest;
import com.quanlybanhang.dto.request.UpdateDeliveryStopStatusRequest;
import com.quanlybanhang.dto.request.UpdateDeliveryTripRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import com.quanlybanhang.dto.response.*;
import com.quanlybanhang.model.DeliveryTripStatus;
import com.quanlybanhang.service.DeliveryTripService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Map;

/**
 * Controller Quản lý Chuyến giao hàng & Theo dõi tiền hàng trên đường (S6-07).
 *
 * Nghiệp vụ:
 * - Danh sách chuyến giao đang chạy kèm số điểm đã giao và còn lại.
 * - Theo dõi tổng tiền hàng đang nằm trên đường.
 * - Hàng chưa giao được vẫn thuộc trách nhiệm chuyến, KHÔNG tự về kho.
 * - Cảnh báo chuyến quá thời gian dự kiến.
 * - Phân quyền theo ma trận DB: WAREHOUSE_VIEW (xem), WAREHOUSE_EXPORT (quản lý/cập nhật), ROLE_ADMIN (toàn quyền).
 */
@RestController
@RequestMapping("/api/warehouse/delivery-trips")
@RequiredArgsConstructor
public class DeliveryTripController {

    private final DeliveryTripService deliveryTripService;

    @GetMapping("/options")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('WAREHOUSE_VIEW', 'WAREHOUSE_EXPORT')")
    public ApiResponse getOptions() {
        DeliveryTripOptionsResponse options = deliveryTripService.getOptions();
        return ApiResponse.success("Tùy chọn chuyến giao hàng", options);
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('WAREHOUSE_VIEW', 'WAREHOUSE_EXPORT')")
    public ApiResponse searchTrips(
            @RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) DeliveryTripStatus status,
            @RequestParam(required = false) Boolean isOverdue,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "false") boolean outstandingOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<DeliveryTripResponse> result = outstandingOnly
                ? deliveryTripService.searchTrips(warehouseId, status, isOverdue, keyword, page, size, true)
                : deliveryTripService.searchTrips(warehouseId, status, isOverdue, keyword, page, size);
        return ApiResponse.success("Danh sách chuyến giao hàng", result);
    }

    @GetMapping("/summary")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('WAREHOUSE_VIEW', 'WAREHOUSE_EXPORT')")
    public ApiResponse getSummary(@RequestParam(required = false) Long warehouseId) {
        DeliveryTripSummaryResponse summary = deliveryTripService.getSummary(warehouseId);
        return ApiResponse.success("Thống kê chuyến giao hàng và tiền hàng trên đường", summary);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('WAREHOUSE_VIEW', 'WAREHOUSE_EXPORT')")
    public ApiResponse getTripById(@PathVariable Long id) {
        DeliveryTripResponse trip = deliveryTripService.getTripById(id);
        return ApiResponse.success("Chi tiết chuyến giao hàng", trip);
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('WAREHOUSE_EXPORT')")
    public ApiResponse createTrip(
            @Valid @RequestBody CreateDeliveryTripRequest request,
            Principal principal) {
        String username = principal != null ? principal.getName() : "system";
        DeliveryTripResponse trip = deliveryTripService.createTrip(request, username);
        return ApiResponse.success("Tạo chuyến giao hàng thành công", trip);
    }

    @PutMapping("/{id}/stops/{stopId}/status")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('WAREHOUSE_EXPORT')")
    public ApiResponse updateStopStatus(
            @PathVariable Long id,
            @PathVariable Long stopId,
            @Valid @RequestBody UpdateDeliveryStopStatusRequest request,
            Principal principal) {
        String username = principal != null ? principal.getName() : "system";
        DeliveryTripResponse trip = deliveryTripService.updateStopStatus(id, stopId, request, username);
        return ApiResponse.success("Cập nhật trạng thái điểm giao thành công", trip);
    }

    @PostMapping("/{id}/complete")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('WAREHOUSE_EXPORT')")
    public ApiResponse completeTrip(
            @PathVariable Long id,
            Principal principal) {
        String username = principal != null ? principal.getName() : "system";
        DeliveryTripResponse trip = deliveryTripService.completeTrip(id, username);
        return ApiResponse.success("Hoàn tất chuyến giao hàng thành công", trip);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('WAREHOUSE_EXPORT')")
    public ApiResponse cancelTrip(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, String> body,
            @RequestParam(required = false) String cancelReason,
            Principal principal) {
        String reason = cancelReason;
        if (reason == null && body != null) {
            reason = body.get("cancelReason");
        }
        String username = principal != null ? principal.getName() : "system";
        DeliveryTripResponse trip = deliveryTripService.cancelTrip(id, reason, username);
        return ApiResponse.success("Hủy chuyến giao hàng thành công", trip);
    }

    @PostMapping("/{id}/stops/{stopId}/return-to-warehouse")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('WAREHOUSE_EXPORT')")
    public ApiResponse returnStopGoodsToWarehouse(
            @PathVariable Long id,
            @PathVariable Long stopId,
            @Valid @RequestBody ReturnStopGoodsRequest request,
            Principal principal) {
        String username = principal != null ? principal.getName() : "system";
        DeliveryTripStopResponse response = deliveryTripService.returnStopGoodsToWarehouse(id, stopId, request, username);
        return ApiResponse.success("Nhập kho trả hàng thành công", response);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('WAREHOUSE_EXPORT')")
    public ApiResponse updateTrip(
            @PathVariable Long id,
            @Valid @RequestBody UpdateDeliveryTripRequest request,
            Principal principal) {
        String username = principal != null ? principal.getName() : "system";
        DeliveryTripResponse trip = deliveryTripService.updateTrip(id, request, username);
        return ApiResponse.success("Cập nhật thông tin chuyến giao hàng thành công", trip);
    }

    @GetMapping("/{id}/manifest")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('WAREHOUSE_VIEW', 'WAREHOUSE_EXPORT')")
    public ApiResponse getManifest(@PathVariable Long id, Principal principal) {
        String username = principal != null ? principal.getName() : "system";
        DeliveryTripManifestResponse manifest = deliveryTripService.getManifest(id, username);
        return ApiResponse.success("Bảng kê điểm giao hàng của chuyến", manifest);
    }

    @GetMapping(value = "/{id}/manifest/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('WAREHOUSE_VIEW', 'WAREHOUSE_EXPORT')")
    public ResponseEntity<byte[]> downloadManifestPdf(
            @PathVariable Long id,
            @RequestParam(required = false, defaultValue = "false") boolean download,
            Principal principal) {
        String username = principal != null ? principal.getName() : "system";
        byte[] pdfBytes = deliveryTripService.generateManifestPdf(id, username);
        DeliveryTripResponse trip = deliveryTripService.getTripById(id);
        String disposition = download ? "attachment" : "inline";
        String code = trip != null && trip.getTripCode() != null ? trip.getTripCode() : String.valueOf(id);
        String filename = "bang-ke-chuyen-giao-" + code + ".pdf";

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition + "; filename=\"" + filename + "\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdfBytes);
    }

    @GetMapping(value = "/{id}/manifest/print", produces = "text/html;charset=UTF-8")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('WAREHOUSE_VIEW', 'WAREHOUSE_EXPORT')")
    public ResponseEntity<String> getManifestPrintHtml(
            @PathVariable Long id,
            Principal principal) {
        String username = principal != null ? principal.getName() : "system";
        String html = deliveryTripService.generateManifestPrintHtml(id, username);
        return ResponseEntity.ok()
                .contentType(MediaType.valueOf("text/html;charset=UTF-8"))
                .body(html);
    }

    @GetMapping("/eligible-orders")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('WAREHOUSE_VIEW', 'WAREHOUSE_EXPORT')")
    public ApiResponse getEligibleOrders(
            @RequestParam Long warehouseId,
            @RequestParam(required = false) Long territoryId,
            @RequestParam(required = false) String keyword) {
        List<DeliveryEligibleOrderResponse> orders = deliveryTripService.getEligibleOrders(warehouseId, territoryId, keyword);
        return ApiResponse.success("Danh sách đơn hàng đủ điều kiện gán chuyến", orders);
    }
}

