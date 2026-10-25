package com.quanlybanhang.service;

import com.quanlybanhang.dto.request.DealerPortalReorderRequest;
import com.quanlybanhang.dto.request.OrderRequest;
import com.quanlybanhang.dto.response.DealerPortalReorderPreviewResponse;
import com.quanlybanhang.dto.response.DealerPortalReorderResponse;
import com.quanlybanhang.dto.response.OrderResponse;

import java.time.LocalDate;
import java.util.List;

public interface OrderService {

    OrderResponse createOrder(OrderRequest request, String username);

    /** Đơn gửi qua cổng luôn chờ duyệt, kể cả khi ADMIN hỗ trợ đại lý. */
    OrderResponse createPortalOrder(OrderRequest request, String username);

    /** Assigned representative confirms a non-violating portal order, without granting exception approval. */
    OrderResponse confirmPortalOrder(Long id, String username);

    List<OrderResponse> getAllOrders();

    /**
     * Lấy danh sách đơn hàng kèm bộ lọc và tính tổng tiền tập kết quả (S4-07).
     * Áp dụng phân quyền dòng dữ liệu: Nhân viên kinh doanh chỉ thấy đơn của đại lý mình phụ trách.
     */
    com.quanlybanhang.dto.response.OrderFilterResult getOrders(com.quanlybanhang.dto.request.OrderFilterRequest filter);

    OrderResponse getOrderById(Long id);

    OrderResponse getOrderById(Long id, String username);

    /**
     * Huỷ đơn hàng có lý do và người thực hiện (S4-06).
     * Bắt buộc có lý do (reason không được để trống).
     * Đơn đã xuất kho thì không huỷ được.
     * Tự động nhả tồn kho đã giữ chỗ (reservedQuantity).
     */
    OrderResponse cancelOrder(Long id, String reason, String username);

    /** Overload huỷ đơn giữ tính tương thích ngược */
    OrderResponse cancelOrder(Long id, String username);

    /**
     * Cập nhật trạng thái vòng đời của đơn hàng (S4-06):
     * Nháp -> Chờ duyệt -> Đã duyệt -> Đang soạn hàng -> Đã xuất -> Đã giao -> Đóng
     */
    OrderResponse updateOrderStatus(Long id, String status, String note, String username);

    /**
     * Theo dõi chi tiết vòng đời đơn hàng và lịch sử chuyển trạng thái (S4-06).
     */
    com.quanlybanhang.dto.response.OrderTrackingResponse getOrderTracking(Long id, String username);

    /**
     * Quản lý kinh doanh duyệt đơn hàng có dòng bán dưới giá sàn (S2-10).
     */
    OrderResponse approveOrder(Long id, String username);

    /**
     * Duyệt đơn hàng kèm tuỳ chỉnh số giờ giữ chỗ tồn kho (S5-06).
     */
    OrderResponse approveOrder(Long id, Integer reservationHours, String username);

    /**
     * S5-06: Tự động kiểm tra và nhả tồn kho giữ chỗ cho các đơn hàng đã quá hạn giữ chỗ.
     * @return số lượng đơn hàng đã được nhả tồn giữ chỗ
     */
    int releaseExpiredReservations();

    /**
     * Quản lý kinh doanh từ chối đơn hàng có dòng bán dưới giá sàn (S2-10).
     */

    /**
     * Quản lý kinh doanh duyệt đơn hàng kèm ý kiến phê duyệt tuỳ chọn (S4-05).
     */
    OrderResponse approveOrder(Long id, String comment, String username);

    /**
     * Quản lý kinh doanh từ chối đơn hàng (S2-10, S4-05). Bắt buộc nhập ý kiến/lý do.
     */
    OrderResponse rejectOrder(Long id, String reason, String username);

    /**
     * Kiểm tra tồn khả dụng của SKU trong kho phục vụ đại lý và gợi ý số lượng tối đa (S4-03).
     */
    com.quanlybanhang.dto.response.OrderInventoryCheckResponse checkInventory(
            com.quanlybanhang.dto.request.OrderInventoryCheckRequest request);
    /**
     * Quản lý kinh doanh trả lại sửa đơn hàng (S4-05). Bắt buộc nhập ý kiến/lý do.
     */
    OrderResponse returnOrder(Long id, String reason, String username);

    /**
     * Cập nhật trạng thái duyệt đơn hàng qua request chung (S4-05).
     */
    OrderResponse updateOrderStatus(Long id, com.quanlybanhang.dto.request.OrderStatusUpdateRequest request, String username);

    /**
     * Lấy danh sách đơn hàng có phân trang và bộ lọc đầy đủ (S4-05, S4-07).
     */
    org.springframework.data.domain.Page<OrderResponse> getOrders(
            String status,
            String approvalReason,
            String search,
            Boolean approvalRequired,
            Long dealerId,
            String createdBy,
            Long territoryId,
            LocalDate dateFrom,
            LocalDate dateTo,
            org.springframework.data.domain.Pageable pageable);

    /**
     * Tương thích ngược - gọi getOrders đầy đủ.
     */
    default org.springframework.data.domain.Page<OrderResponse> getOrders(
            String status,
            String approvalReason,
            String search,
            Boolean approvalRequired,
            org.springframework.data.domain.Pageable pageable) {
        return getOrders(status, approvalReason, search, approvalRequired, null, null, null, null, null, pageable);
    }

    /**
     * Lấy lịch sử duyệt đơn hàng (S4-05).
     */
    List<com.quanlybanhang.dto.response.OrderApprovalHistoryResponse> getOrderApprovalHistory(Long orderId);

    /**
     * Tính giá và chiết khấu tự động khi thêm một dòng hàng cho đại lý (S4-01).
     */
    com.quanlybanhang.dto.response.OrderLineItemPricingResponse calculateLineItemPricing(
            com.quanlybanhang.dto.request.OrderLineItemPricingRequest request);

    /**
     * Tính lại toàn bộ giá và chiết khấu cho danh sách dòng hàng (S4-01).
     */
    com.quanlybanhang.dto.response.OrderCalculateResponse calculateOrder(
            com.quanlybanhang.dto.request.OrderCalculateRequest request);

    /**
    /**
     * S4-09: Sao chép một đơn cũ thành đơn mới ở trạng thái Nháp (DRAFT).
     * Toàn bộ dòng hàng của đơn đã chọn được sao chép.
     * Giá và chiết khấu được áp lại theo bảng giá và chính sách hiện hành, không kế thừa giá cũ.
     * Bản sao luôn bắt đầu ở trạng thái Nháp.
     */
    OrderResponse copyOrder(Long id, String username);

    /**
     * S4-09: Sao chép các dòng hàng của đơn cũ trực tiếp vào bản nháp tạo đơn của người dùng.
     */
    com.fasterxml.jackson.databind.JsonNode copyOrderToDraft(Long id, String username);

    /**
     * S4-04: Thêm nhanh cả nhóm hàng đã mua lần trước vào bản nháp tạo đơn mới của người dùng.
     * Tự động tìm đơn hàng hợp lệ gần nhất của đại lý và nạp các dòng hàng vào bản nháp.
     */
    com.fasterxml.jackson.databind.JsonNode copyLastOrderToDraft(Long dealerId, String username);
    /**
     * Lấy lịch sử mua hàng của đại lý trong N tháng gần nhất (S4-04).
     */
    DealerPurchaseHistoryResult getDealerPurchaseHistory(Long dealerId, int months, String currentUsername);

    /**
     * Sao chép đơn hàng cũ thành đơn nháp mới, áp lại giá/chiết khấu hiện hành (S4-09).
     */


    /**
     * Tạo nội dung HTML để in/xuất PDF đơn hàng (S4-08).
     */
    String generateOrderPrintHtml(Long orderId);

    // ── Data records (S4-04) ──

    record DealerPurchaseHistoryResult(
            Long dealerId,
            String dealerName,
            String dealerCode,
            int months,
            java.time.LocalDate fromDate,
            java.time.LocalDate toDate,
            List<ProductPurchaseSummary> products,
            List<RecentOrderSummary> recentOrders
    ) {}

    record ProductPurchaseSummary(
            Long productId,
            String productCode,
            String productName,
            String categoryName,
            java.math.BigDecimal totalBaseQuantity,
            String baseUnit,
            java.math.BigDecimal averageMonthlyQuantity,
            int orderCount,
            java.math.BigDecimal lastPrice,
            java.time.LocalDateTime lastOrderAt
    ) {}

    record RecentOrderSummary(
            Long orderId,
            String orderCode,
            String status,
            java.math.BigDecimal totalAmount,
            java.time.LocalDateTime createdAt,
            List<String> productNames
    ) {}
    /**
     * Lấy danh sách đơn hàng theo ngữ cảnh người dùng hiện tại (S4-07, S5-01):
     * - Đại lý (ROLE_DEALER): chỉ thấy đơn của chính đại lý mình.
     * - Nhân viên kinh doanh (ROLE_SALES): chỉ thấy đơn của đại lý mình phụ trách.
     * - Quản trị, Quản lý, Kế toán, Kho, Viewer: thấy toàn bộ đơn hàng.
     */
    List<OrderResponse> getOrdersForUser(String username);

    /**
     * Lấy chi tiết đơn hàng theo ngữ cảnh người dùng hiện tại (S4-07, S5-01).
     * Ném lỗi AccessDeniedException (403) nếu cố truy cập đơn của đại lý khác.
     */
    OrderResponse getOrderByIdForUser(Long id, String username);

    /**
     * S5-01: Lấy danh sách đơn hàng của chính đại lý đăng nhập.
     * Kèm trạng thái, ngày giao dự kiến, tổng tiền.
     */
    List<OrderResponse> getOrdersForDealer(String username);

    /**
     * S5-01: Xem chi tiết dòng hàng và số lượng thực giao nếu giao thiếu cho đại lý.
     */
    OrderResponse getOrderByIdForDealer(Long id, String username);

    /**
     * Cập nhật thông tin giao hàng và số lượng thực giao (S5-01).
     */
    OrderResponse updateOrderDelivery(Long id, com.quanlybanhang.dto.request.OrderDeliveryUpdateRequest request, String username);
    /**
     * S4-08: In hoặc xuất PDF đơn hàng, kèm thông tin đại lý, điểm giao, chi tiết dòng hàng, chiết khấu, tổng tiền và mã vạch để kho tra cứu.
     */
    byte[] generateOrderPdf(Long id, String username);

    /**
     * S4-08: Tạo ảnh mã vạch Code 128 tra cứu cho đơn hàng.
     */
    byte[] generateOrderBarcode(Long id, String username);

    /**
     * S5-02: Xem trước đơn đặt lại từ đơn hàng cũ cho Đại lý.
     * Tự động phân loại mặt hàng còn kinh doanh vs đã ngừng kinh doanh / không còn trong bảng giá,
     * áp lại giá theo bảng giá hiệu lực hiện hành.
     */
    DealerPortalReorderPreviewResponse getPortalReorderPreview(Long sourceOrderId, String username);

    /**
     * S5-02: Đại lý đặt lại toàn bộ hoặc một phần đơn hàng cũ.
     * Mặt hàng đã ngừng kinh doanh được loại khỏi đơn mới kèm thông báo.
     * Giá được áp lại theo bảng giá hiện hành, đơn mới tạo ở trạng thái Chờ duyệt.
     */
    DealerPortalReorderResponse createPortalReorder(Long sourceOrderId, DealerPortalReorderRequest request, String username);

    /**
     * S6-09: Delivery milestones and actual quantities within the selected dealer scope.
     */
    com.quanlybanhang.dto.response.DealerDeliveryTrackingResponse getDealerOrderDeliveryTracking(Long orderId, Long dealerId);

    /** S6-09: Delivery tracking within the authenticated staff member's order scope. */
    com.quanlybanhang.dto.response.DealerDeliveryTrackingResponse getOrderDeliveryTrackingForStaff(Long orderId, String username);
}

