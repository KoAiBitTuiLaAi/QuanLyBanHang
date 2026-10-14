package com.quanlybanhang.service;

import com.quanlybanhang.dto.request.OrderRequest;
import com.quanlybanhang.dto.response.OrderResponse;

import java.util.List;

public interface OrderService {

    OrderResponse createOrder(OrderRequest request, String username);

    List<OrderResponse> getAllOrders();

    OrderResponse getOrderById(Long id);

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
}
