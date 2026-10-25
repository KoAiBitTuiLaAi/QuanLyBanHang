package com.quanlybanhang.service;

import com.quanlybanhang.dto.request.DealerPortalReorderRequest;
import com.quanlybanhang.dto.request.OrderRequest;
import com.quanlybanhang.dto.response.DealerDeliveryTrackingResponse;
import com.quanlybanhang.dto.response.DealerPortalReorderPreviewResponse;
import com.quanlybanhang.dto.response.DealerPortalReorderResponse;
import com.quanlybanhang.dto.response.OrderResponse;

import java.time.LocalDate;
import java.util.List;

import com.quanlybanhang.dto.response.DealerDeliveryTrackingResponse;
public interface OrderService {

    OrderResponse createOrder(OrderRequest request, String username);

    OrderResponse createPortalOrder(OrderRequest request, String username);

    OrderResponse confirmPortalOrder(Long id, String username);

    List<OrderResponse> getAllOrders();

    /**
     * S4-04: Lấy danh sách đơn hàng đã lọc và phân trang theo phạm vi quyền hạn của người dùng.
     * Áp dụng bảo mật đa tầng: WAREHOUSE_VIEW giới hạn kho, SALES chỉ thấy đơn của khách được phân công,
     * DEALER chỉ thấy đơn của chính đại lý mình.
     */
    org.springframework.data.domain.Page<OrderResponse> getOrders(com.quanlybanhang.dto.request.OrderFilterRequest filter, String currentUsername);

    OrderResponse getOrderById(Long id);

    OrderResponse getOrderById(Long id, String username);

    /**
     * S5-06: Gia hạn thời gian giữ chỗ tồn kho cho đơn hàng (tối đa 48h từ hiện tại).
     * Chỉ áp dụng cho đơn chưa xuất kho và có giữ chỗ tồn hợp lệ.
     */
    OrderResponse extendReservation(Long id, int additionalHours, String username);

    OrderResponse cancelOrder(Long id, String reason, String username);

    OrderResponse cancelOrder(Long id, String username);

    /**
     * S4-06: Cập nhật trạng thái vòng đời đơn hàng và ghi nhận lịch sử vào order_status_histories.
     * Tự động quản lý giữ chỗ tồn kho (nhả tồn khi huỷ, trừ tồn khi xuất kho).
     */
    OrderResponse updateOrderStatus(Long id, String status, String note, String username);

    /**
     * S4-06: Theo dõi chi tiết vòng đời đơn hàng gồm trạng thái hiện tại, các bước và lịch sử đầy đủ.
     */
    com.quanlybanhang.dto.response.OrderTrackingResponse getOrderTracking(Long id, String username);

    /**
     * Duyệt đơn hàng: chuyển status sang APPROVED / RECORDED và giữ chỗ tồn kho.
     */
    OrderResponse approveOrder(Long id, String username);

    /**
     * S5-06: Duyệt đơn hàng kèm thời hạn giữ chỗ tồn kho tuỳ chỉnh (tính bằng giờ, tối đa 72h).
     */
    OrderResponse approveOrder(Long id, Integer reservationHours, String username);

    /**
     * S4-05: Duyệt đơn hàng vi phạm (bán dưới giá sàn hoặc vượt hạn mức công nợ).
     * Yêu cầu quyền ORDER_APPROVE (hoặc ROLE_ADMIN / ROLE_SALES_MANAGER).
     * Ghi nhận lịch sử duyệt bất biến vào bảng order_approval_histories (ai duyệt, lúc nào, ghi chú).
     * Tự động kích hoạt giữ chỗ tồn kho (inventory_reserved = true).
     *
     * @param id ID đơn hàng cần duyệt
     * @param comment Ghi chú phê duyệt của Quản lý kinh doanh (bắt buộc)
     * @param username Tên đăng nhập người duyệt
     * @return OrderResponse đã cập nhật trạng thái APPROVED
     */
    OrderResponse approveOrder(Long id, String comment, String username);

    /**
     * S4-05: Từ chối duyệt đơn hàng vi phạm.
     * Yêu cầu quyền ORDER_APPROVE và bắt buộc có lý do từ chối.
     * Ghi nhận lịch sử từ chối bất biến vào order_approval_histories.
     * Đơn hàng chuyển sang trạng thái REJECTED (kết thúc vòng đời).
     */
    OrderResponse rejectOrder(Long id, String reason, String username);

    /**
     * S4-05: Trả về yêu cầu nhân viên kinh doanh chỉnh sửa đơn hàng vi phạm.
     * Bắt buộc có lý do/hướng dẫn chỉnh sửa cụ thể.
     * Ghi nhận lịch sử vào order_approval_histories.
     * Đơn hàng chuyển sang trạng thái RETURNED để NVKD có thể sửa lại.
     */
    OrderResponse returnOrder(Long id, String reason, String username);

    OrderResponse updateOrderStatus(Long id, com.quanlybanhang.dto.request.OrderStatusUpdateRequest request, String username);

    org.springframework.data.domain.Page<OrderResponse> getOrders(
            String status,
            String customerName,
            LocalDate startDate,
            LocalDate endDate,
            Long dealerId,
            org.springframework.data.domain.Pageable pageable);

    default org.springframework.data.domain.Page<OrderResponse> getOrders(
            com.quanlybanhang.dto.request.OrderFilterRequest filter) {
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(
                filter.getPage(),
                filter.getSize(),
                org.springframework.data.domain.Sort.by(
                        org.springframework.data.domain.Sort.Direction.fromString(filter.getSortDirection()),
                        filter.getSortBy()
                )
        );
        return getOrders(
                filter.getStatus(),
                filter.getCustomerName(),
                filter.getStartDate(),
                filter.getEndDate(),
                filter.getDealerId(),
                pageable
        );
    }

    /**
     * Tạo đơn hàng mới bằng cách sao chép từ một đơn hàng đã có (S2-09).
     *
     * @param id ID của đơn hàng gốc cần sao chép
     * @param username Tên đăng nhập người thực hiện (lấy từ JWT principal)
     * @return OrderResponse của đơn hàng mới tạo
     */
    OrderResponse copyOrder(Long id, String username);

    /**
     * S2-09: Sao chép đơn hàng gần nhất của đại lý vào bản nháp đơn hàng.
     * Tìm đơn hàng mới nhất (theo createdAt) của đại lý, áp dụng bảng giá hiện hành,
     * loại bỏ các sản phẩm đã ngừng kinh doanh và lưu vào bảng order_drafts.
     *
     * @param dealerId ID đại lý cần sao chép đơn
     * @param username Tên đăng nhập người thực hiện
     * @return JsonNode chứa dữ liệu bản nháp vừa tạo
     */
    com.fasterxml.jackson.databind.JsonNode copyLastOrderToDraft(Long dealerId, String username);

    /**
     * S2-09: Lấy lịch sử mua hàng của đại lý trong N tháng gần nhất.
     */
    DealerPurchaseHistoryResult getDealerPurchaseHistory(Long dealerId, int months, String currentUsername);

    record DealerPurchaseHistoryItem(
            Long orderId,
            String orderCode,
            LocalDate orderDate,
            String status,
            String statusDisplayName,
            java.math.BigDecimal totalAmount,
            int totalItems,
            List<DealerPurchaseHistoryDetailItem> items
    ) {}

    record DealerPurchaseHistoryDetailItem(
            Long productId,
            String productCode,
            String productName,
            String unit,
            java.math.BigDecimal quantity,
            java.math.BigDecimal unitPrice,
            java.math.BigDecimal totalAmount,
            boolean productActive
    ) {}

    record DealerPurchaseHistoryResult(
            Long dealerId,
            String dealerName,
            String dealerCode,
            int months,
            int totalOrders,
            java.math.BigDecimal totalSpent,
            List<DealerPurchaseHistoryItem> orders
    ) {}

    /**
     * S4-04: Lấy danh sách đơn hàng cho người dùng có thể là SALES, DEALER, ADMIN, v.v.
     * - Nhân viên bán hàng (ROLE_SALES): chỉ thấy đơn của khách hàng mình phụ trách.
     * - Đại lý (ROLE_DEALER): chỉ thấy đơn của chính đại lý mình.
     * - Quản lý / Kế toán / Admin: thấy toàn bộ đơn hàng.
     */
    List<OrderResponse> getOrdersForUser(String username);

    /**
     * S4-04: Xem chi tiết đơn hàng kèm kiểm tra quyền sở hữu đối với SALES / DEALER.
     */
    OrderResponse getOrderByIdForUser(Long id, String username);

    /**
     * S4-10, S5-01: Lấy danh sách đơn hàng của riêng đại lý đăng nhập.
     */
    List<OrderResponse> getOrdersForDealer(String username);

    /**
     * S4-10, S5-01: Xem chi tiết đơn hàng của riêng đại lý đăng nhập (bảo vệ bằng DEALER ID).
     */
    OrderResponse getOrderByIdForDealer(Long id, String username);

    /**
     * S5-01: Cập nhật thông tin thực giao hàng (ngày giao, số lượng thực giao, ghi chú).
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
     * S6-09: Lấy thông tin theo dõi giao hàng của đơn dành cho Đại lý.
     * Hiển thị 5 mốc thời gian: đã duyệt, đang soạn, đã xuất, đang giao, đã giao.
     * Xem được số lượng thực giao từng dòng hàng và thông tin xe/tài xế.
     * Ràng buộc chặt chẽ theo phạm vi đại lý sở hữu đơn hàng (ORDER_VIEW).
     */
    DealerDeliveryTrackingResponse getDealerOrderDeliveryTracking(Long orderId, Long dealerId);

    /**
     * S6-09: Lấy thông tin theo dõi giao hàng dành cho nhân viên kinh doanh / quản lý.
     */
    DealerDeliveryTrackingResponse getOrderDeliveryTrackingForStaff(Long orderId, String username);
}

