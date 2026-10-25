package com.quanlybanhang.controller;

import com.quanlybanhang.dto.request.CreateDeliveryTripRequest;
import com.quanlybanhang.dto.request.ReturnStopGoodsRequest;
import com.quanlybanhang.dto.request.UpdateDeliveryStopStatusRequest;
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
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<DeliveryTripResponse> result = deliveryTripService.searchTrips(warehouseId, status, isOverdue, keyword, page, size);
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

    @PostMapping(value = "/{id}/stops/{stopId}/confirm-delivery", consumes = "multipart/form-data")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('WAREHOUSE_EXPORT')")
    public ApiResponse confirmDelivery(@PathVariable Long id, @PathVariable Long stopId,
            @RequestParam String receiverName,
            @RequestParam @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME)
            java.time.LocalDateTime deliveredAt,
            @RequestPart("proof") org.springframework.web.multipart.MultipartFile proof, Principal principal) {
        return ApiResponse.success("Xác nhận giao hàng thành công", deliveryTripService.confirmDelivery(
                id, stopId, receiverName, deliveredAt, proof, principal.getName()));
    }

    @GetMapping("/{id}/stops/{stopId}/proof")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('WAREHOUSE_VIEW', 'WAREHOUSE_EXPORT')")
    public org.springframework.http.ResponseEntity<byte[]> getProof(@PathVariable Long id, @PathVariable Long stopId) {
        return deliveryTripService.getDeliveryProof(id, stopId);
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

    @GetMapping("/eligible-orders")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('WAREHOUSE_VIEW', 'WAREHOUSE_EXPORT')")
    public ApiResponse getEligibleOrders(@RequestParam Long warehouseId) {
        List<DeliveryEligibleOrderResponse> orders = deliveryTripService.getEligibleOrders(warehouseId);
        return ApiResponse.success("Danh sách đơn hàng đủ điều kiện gán chuyến", orders);
    }
}

