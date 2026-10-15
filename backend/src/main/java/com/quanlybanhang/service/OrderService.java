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
}
