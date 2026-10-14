package com.quanlybanhang.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanlybanhang.dto.request.OrderCancelRequest;
import com.quanlybanhang.dto.request.OrderDebtCheckRequest;
import com.quanlybanhang.dto.request.OrderStatusUpdateRequest;
import com.quanlybanhang.dto.response.OrderDebtCheckResponse;
import com.quanlybanhang.dto.request.OrderCalculateRequest;
import com.quanlybanhang.dto.request.OrderLineItemPricingRequest;
import com.quanlybanhang.dto.request.OrderRequest;
import com.quanlybanhang.dto.response.ApiResponse;
import com.quanlybanhang.dto.response.OrderCalculateResponse;
import com.quanlybanhang.dto.response.OrderLineItemPricingResponse;
import com.quanlybanhang.dto.response.OrderResponse;
import com.quanlybanhang.dto.response.OrderTrackingResponse;
import com.quanlybanhang.model.OrderDraft;
import com.quanlybanhang.repository.OrderDraftRepository;
import com.quanlybanhang.service.DealerDebtService;
import com.quanlybanhang.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.security.Principal;
import java.util.List;

/**
 * Controller Quản lý Đơn hàng (S2-07).
 * Khi ghi sổ đơn hàng, dù đặt theo thùng hay lốc đều được quy về đơn vị cơ sở (số lon).
 */
@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;
    private final DealerDebtService dealerDebtService;
    private final OrderDraftRepository orderDraftRepository;
    private final ObjectMapper objectMapper;

    private static final String DRAFT_ROLES = "hasAnyRole('SALES', 'SALES_MANAGER', 'ADMIN', 'WAREHOUSE_MANAGER', 'WAREHOUSE_STAFF')";

    @GetMapping
    public ApiResponse getAllOrders() {
        List<OrderResponse> orders = orderService.getAllOrders();
        return ApiResponse.success("Danh sách đơn hàng", orders);
    }

    /** Lấy bản nháp tạo đơn của chính người dùng hiện tại (S3-09). */
    @GetMapping("/draft")
    @PreAuthorize(DRAFT_ROLES)
    public ApiResponse getDraft(Principal principal) throws Exception {
        return orderDraftRepository.findByUsername(principal.getName())
                .map(draft -> {
                    try {
                        return ApiResponse.success("Lấy bản nháp thành công", objectMapper.readTree(draft.getDraftData()));
                    } catch (Exception e) {
                        throw new IllegalStateException("Không đọc được bản nháp đã lưu", e);
                    }
                })
                .orElseGet(() -> ApiResponse.success("Chưa có bản nháp", null));
    }

    /** Lưu đè bản nháp của chính người dùng hiện tại (S3-09). */
    @PutMapping("/draft")
    @PreAuthorize(DRAFT_ROLES)
    public ApiResponse saveDraft(@RequestBody JsonNode draftData, Principal principal) throws Exception {
        if (draftData == null || draftData.isNull() || !draftData.isObject()) {
            throw new IllegalArgumentException("Dữ liệu bản nháp không hợp lệ");
        }

        OrderDraft draft = orderDraftRepository.findByUsername(principal.getName())
                .orElseGet(() -> OrderDraft.builder().username(principal.getName()).build());
        draft.setDraftData(objectMapper.writeValueAsString(draftData));
        orderDraftRepository.save(draft);
        return ApiResponse.success("Đã lưu bản nháp tạo đơn", draftData);
    }

    /** Xoá bản nháp sau khi ghi nhận đơn hoặc người dùng bỏ qua (S3-09). */
    @DeleteMapping("/draft")
    @PreAuthorize(DRAFT_ROLES)
    public ApiResponse deleteDraft(Principal principal) {
        orderDraftRepository.findByUsername(principal.getName()).ifPresent(orderDraftRepository::delete);
        return ApiResponse.success("Đã xoá bản nháp tạo đơn", null);
    }

    @GetMapping("/{id}")
    public ApiResponse getOrderById(@PathVariable Long id) {
        OrderResponse order = orderService.getOrderById(id);
        return ApiResponse.success("Chi tiết đơn hàng", order);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SALES', 'SALES_MANAGER', 'ADMIN', 'WAREHOUSE_MANAGER', 'WAREHOUSE_STAFF')")
    public ResponseEntity<ApiResponse> createOrder(
            @Valid @RequestBody OrderRequest request,
            Principal principal) {
        String username = principal != null ? principal.getName() : "system";
        OrderResponse response = orderService.createOrder(request, username);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Tạo đơn hàng thành công", response));
    }

    /**
     * S4-01: Tính giá và chiết khấu áp tự động khi thêm dòng hàng cho đại lý.
     * Giá lấy từ bảng giá đang hiệu lực của nhóm khách hàng mà đại lý thuộc về.
     * Chiết khấu theo sản lượng tính lại tự động.
     * Sửa giá thủ công được nhưng dưới giá sàn thì cảnh báo cần duyệt.
     * Không có bảng giá hiệu lực cho SKU đó thì chặn thêm dòng hàng kèm thông báo rõ lý do.
     */
    @PostMapping("/line-item/pricing")
    @PreAuthorize("hasAnyRole('SALES', 'SALES_MANAGER', 'ADMIN', 'WAREHOUSE_MANAGER', 'WAREHOUSE_STAFF')")
    public ApiResponse calculateLineItemPricing(@Valid @RequestBody OrderLineItemPricingRequest request) {
        OrderLineItemPricingResponse response = orderService.calculateLineItemPricing(request);
        return ApiResponse.success("Áp giá và chiết khấu dòng hàng thành công", response);
    }

    /**
     * S4-01: Tính lại toàn bộ giá và chiết khấu cho danh sách dòng hàng khi số lượng thay đổi hoặc sửa giá thủ công.
     * Chặn thêm dòng hàng nếu có SKU chưa có trong bảng giá hiệu lực.
     */
    @PostMapping("/calculate")
    @PreAuthorize("hasAnyRole('SALES', 'SALES_MANAGER', 'ADMIN', 'WAREHOUSE_MANAGER', 'WAREHOUSE_STAFF')")
    public ApiResponse calculateOrder(@Valid @RequestBody OrderCalculateRequest request) {
        OrderCalculateResponse response = orderService.calculateOrder(request);
        return ApiResponse.success("Tính toán giá và chiết khấu đơn hàng thành công", response);
    }

    /**
     * S4-06: Huỷ đơn hàng có lý do và nhả tồn kho giữ chỗ.
     * Bắt buộc có lý do (reason không được để trống).
     * Đơn đã xuất kho thì không huỷ được, phải đi đường trả hàng.
     */
    @PutMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('SALES', 'SALES_MANAGER', 'ADMIN')")
    public ApiResponse cancelOrder(
            @PathVariable Long id,
            @RequestBody(required = false) OrderCancelRequest request,
            @RequestParam(required = false) String reason,
            Principal principal) {
        String finalReason = null;
        if (request != null && request.getReason() != null && !request.getReason().trim().isBlank()) {
            finalReason = request.getReason().trim();
        } else if (reason != null && !reason.trim().isBlank()) {
            finalReason = reason.trim();
        }

        if (finalReason == null) {
            throw new IllegalArgumentException("Lý do huỷ đơn hàng không được để trống");
        }

        String username = principal != null ? principal.getName() : "system";
        OrderResponse response = orderService.cancelOrder(id, finalReason, username);
        String message = Boolean.TRUE.equals(response.getDealerLocked())
                ? "Hủy đơn hàng thành công (Cảnh báo: Đại lý đang bị khoá giao dịch)"
                : "Hủy đơn hàng thành công";
        return ApiResponse.success(message, response);
    }

    /**
     * S4-06: Theo dõi chi tiết vòng đời đơn hàng và lịch sử chuyển trạng thái.
     * Nhân viên kinh doanh theo dõi trực tiếp khâu xử lý mà không cần gọi hỏi kho.
     */
    @GetMapping("/{id}/tracking")
    @PreAuthorize("hasAnyRole('SALES', 'SALES_MANAGER', 'ADMIN', 'WAREHOUSE_MANAGER', 'WAREHOUSE_STAFF', 'ACCOUNTANT', 'VIEWER')")
    public ApiResponse getOrderTracking(@PathVariable Long id, Principal principal) {
        String username = principal != null ? principal.getName() : "system";
        OrderTrackingResponse tracking = orderService.getOrderTracking(id, username);
        return ApiResponse.success("Theo dõi vòng đời đơn hàng", tracking);
    }

    /**
     * S4-06: Cập nhật trạng thái vòng đời của đơn hàng (Soạn hàng, Xuất kho, Giao hàng, Đóng).
     */
    @PutMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('WAREHOUSE_STAFF', 'WAREHOUSE_MANAGER', 'SALES_MANAGER', 'ADMIN')")
    public ApiResponse updateOrderStatus(
            @PathVariable Long id,
            @Valid @RequestBody OrderStatusUpdateRequest request,
            Principal principal) {
        String username = principal != null ? principal.getName() : "system";
        OrderResponse response = orderService.updateOrderStatus(id, request.getStatus(), request.getNote(), username);
        return ApiResponse.success("Cập nhật trạng thái đơn hàng thành công", response);
    }

    /**
     * Quản lý kinh doanh phê duyệt đơn hàng bán dưới giá sàn (S2-10).
     * Đơn hàng của đại lý bị khoá vẫn được xử lý tiếp nhưng có cảnh báo (S3-07).
     */
    @PutMapping("/{id}/approve")
    @PreAuthorize("hasAnyRole('SALES_MANAGER', 'ADMIN')")
    public ApiResponse approveOrder(@PathVariable Long id, Principal principal) {
        String username = principal != null ? principal.getName() : "system";
        OrderResponse response = orderService.approveOrder(id, username);
        String message = Boolean.TRUE.equals(response.getDealerLocked())
                ? "Phê duyệt đơn hàng thành công (Cảnh báo: Đại lý đang bị khoá giao dịch)"
                : "Phê duyệt đơn hàng thành công";
        return ApiResponse.success(message, response);
    }

    /**
     * Quản lý kinh doanh từ chối đơn hàng bán dưới giá sàn (S2-10).
     * Đơn hàng của đại lý bị khoá vẫn được xử lý tiếp nhưng có cảnh báo (S3-07).
     */
    @PutMapping("/{id}/reject")
    @PreAuthorize("hasAnyRole('SALES_MANAGER', 'ADMIN')")
    public ApiResponse rejectOrder(
            @PathVariable Long id,
            @RequestParam(required = false) String reason,
            Principal principal) {
        String username = principal != null ? principal.getName() : "system";
        OrderResponse response = orderService.rejectOrder(id, reason, username);
        String message = Boolean.TRUE.equals(response.getDealerLocked())
                ? "Từ chối đơn hàng thành công (Cảnh báo: Đại lý đang bị khoá giao dịch)"
                : "Từ chối đơn hàng thành công";
        return ApiResponse.success(message, response);
    }

    /**
     * Kiểm tra sơ bộ hạn mức và điều kiện nợ khi đặt đơn cho đại lý (S4-02).
     */
    @PostMapping("/check-debt")
    @PreAuthorize("hasAnyRole('SALES', 'SALES_MANAGER', 'ADMIN', 'WAREHOUSE_MANAGER', 'WAREHOUSE_STAFF')")
    public ApiResponse checkOrderDebt(@Valid @RequestBody OrderDebtCheckRequest request) {
        OrderDebtCheckResponse response = dealerDebtService.checkOrderDebt(request);
        return ApiResponse.success("Kiểm tra công nợ thành công", response);
    }

    /**
     * Tra cứu nhanh hạn mức công nợ theo query param (S4-02).
     */
    @GetMapping("/check-debt")
    @PreAuthorize("hasAnyRole('SALES', 'SALES_MANAGER', 'ADMIN', 'WAREHOUSE_MANAGER', 'WAREHOUSE_STAFF')")
    public ApiResponse checkOrderDebtByParam(
            @RequestParam Long dealerId,
            @RequestParam(required = false, defaultValue = "0") BigDecimal orderAmount) {
        OrderDebtCheckResponse response = dealerDebtService.checkOrderDebt(dealerId, orderAmount);
        return ApiResponse.success("Kiểm tra công nợ thành công", response);
    }
}
