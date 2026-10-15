package com.quanlybanhang.service;

import com.quanlybanhang.dto.request.OrderRequest;
import com.quanlybanhang.dto.response.OrderResponse;

import java.util.List;

public interface OrderService {

    OrderResponse createOrder(OrderRequest request, String username);

    List<OrderResponse> getAllOrders();

    OrderResponse getOrderById(Long id);

    OrderResponse cancelOrder(Long id, String username);

    /**
     * Quản lý kinh doanh duyệt đơn hàng có dòng bán dưới giá sàn (S2-10).
     */
    OrderResponse approveOrder(Long id, String username);

    /**
     * Quản lý kinh doanh từ chối đơn hàng có dòng bán dưới giá sàn (S2-10).
     */
    OrderResponse rejectOrder(Long id, String reason, String username);

    /**
     * Tính giá và chiết khấu tự động khi thêm một dòng hàng cho đại lý (S4-01).
     * Chặn thêm dòng hàng nếu SKU chưa có trong bảng giá hiệu lực của nhóm khách hàng.
     */
    com.quanlybanhang.dto.response.OrderLineItemPricingResponse calculateLineItemPricing(
            com.quanlybanhang.dto.request.OrderLineItemPricingRequest request);

    /**
     * Tính lại toàn bộ giá và chiết khấu cho danh sách dòng hàng khi số lượng thay đổi hoặc sửa giá (S4-01).
     * Chặn nếu có SKU chưa có trong bảng giá hiệu lực của nhóm khách hàng.
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
}
