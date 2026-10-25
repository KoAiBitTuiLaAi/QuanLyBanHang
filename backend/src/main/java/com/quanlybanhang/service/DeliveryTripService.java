package com.quanlybanhang.service;

import com.quanlybanhang.dto.request.CreateDeliveryTripRequest;
import com.quanlybanhang.dto.request.ReturnStopGoodsRequest;
import com.quanlybanhang.dto.request.UpdateDeliveryStopStatusRequest;
import com.quanlybanhang.dto.response.*;
import com.quanlybanhang.model.DeliveryTripStatus;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Service quản lý Chuyến giao hàng & Theo dõi tiền hàng trên đường (S6-07).
 */
public interface DeliveryTripService {

    /**
     * Lấy các tùy chọn bộ lọc và thông tin phân quyền.
     */
    DeliveryTripResponse confirmDelivery(Long tripId, Long stopId, String receiverName,
            java.time.LocalDateTime deliveredAt, org.springframework.web.multipart.MultipartFile proof, String username);

    org.springframework.http.ResponseEntity<byte[]> getDeliveryProof(Long tripId, Long stopId);

    DeliveryTripOptionsResponse getOptions();

    /**
     * Tìm kiếm và phân trang danh sách chuyến giao hàng.
     * Hỗ trợ lọc theo kho, trạng thái (mặc định IN_PROGRESS), quá hạn dự kiến, từ khóa.
     */
    Page<DeliveryTripResponse> searchTrips(Long warehouseId, DeliveryTripStatus status,
                                          Boolean isOverdue, String keyword, int page, int size);

    /**
     * Thống kê tổng quan: số chuyến đang chạy, số chuyến quá hạn, tổng tiền hàng đang nằm trên đường.
     */
    DeliveryTripSummaryResponse getSummary(Long warehouseId);

    /**
     * Xem chi tiết chuyến giao hàng kèm danh sách điểm giao và các mặt hàng.
     */
    DeliveryTripResponse getTripById(Long id);

    /**
     * Tạo mới chuyến giao hàng từ danh sách đơn hàng đã xuất hoặc khai báo điểm dừng.
     */
    DeliveryTripResponse createTrip(CreateDeliveryTripRequest request, String username);

    /**
     * Cập nhật trạng thái điểm giao (DELIVERED hoặc FAILED).
     * Bắt buộc: Hàng chưa giao được vẫn thuộc trách nhiệm chuyến, KHÔNG tự về kho!
     */
    DeliveryTripResponse updateStopStatus(Long tripId, Long stopId,
                                         UpdateDeliveryStopStatusRequest request, String username);

    /**
     * Hoàn tất chuyến giao hàng.
     */
    DeliveryTripResponse completeTrip(Long id, String username);

    /**
     * Hủy chuyến giao hàng (bắt buộc nhập lý do).
     */
    DeliveryTripResponse cancelTrip(Long id, String cancelReason, String username);

    /**
     * Thao tác tường minh: Nhập kho trả hàng cho điểm giao thất bại.
     * Chỉ thực hiện khi có hành động nhập trả có kiểm soát; bình thường hàng FAILED không tự về kho.
     */
    DeliveryTripStopResponse returnStopGoodsToWarehouse(Long tripId, Long stopId,
                                                       ReturnStopGoodsRequest request, String username);

    /**
     * Danh sách đơn hàng đã xuất kho đủ điều kiện gán vào chuyến giao hàng mới.
     */
    List<DeliveryEligibleOrderResponse> getEligibleOrders(Long warehouseId);
}

