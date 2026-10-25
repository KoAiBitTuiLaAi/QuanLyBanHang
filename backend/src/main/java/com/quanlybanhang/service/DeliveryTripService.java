package com.quanlybanhang.service;

import com.quanlybanhang.dto.request.CreateDeliveryTripRequest;
import com.quanlybanhang.dto.request.ReturnStopGoodsRequest;
import com.quanlybanhang.dto.request.UpdateDeliveryStopStatusRequest;
import com.quanlybanhang.dto.request.UpdateDeliveryTripRequest;
import com.quanlybanhang.dto.response.*;
import com.quanlybanhang.model.DeliveryTripStatus;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Service quản lý Chuyến giao hàng & Phân tuyến giao hàng (S6-06, S6-07).
 */
public interface DeliveryTripService {

    /**
     * Lấy các tùy chọn bộ lọc và thông tin phân quyền (bao gồm danh sách kho và khu vực).
     */
    DeliveryTripOptionsResponse getOptions();

    /**
     * Tìm kiếm và phân trang danh sách chuyến giao hàng.
     * Hỗ trợ lọc theo kho, trạng thái (mặc định IN_PROGRESS), quá hạn dự kiến, từ khóa.
     */
    Page<DeliveryTripResponse> searchTrips(Long warehouseId, DeliveryTripStatus status,
                                          Boolean isOverdue, String keyword, int page, int size);

    Page<DeliveryTripResponse> searchTrips(Long warehouseId, DeliveryTripStatus status,
            Boolean isOverdue, String keyword, int page, int size, boolean outstandingOnly);

    /**
     * Thống kê tổng quan: số chuyến đang chạy, số chuyến quá hạn, tổng tiền hàng đang nằm trên đường.
     */
    DeliveryTripSummaryResponse getSummary(Long warehouseId);

    /**
     * Xem chi tiết chuyến giao hàng kèm danh sách điểm giao và các mặt hàng.
     */
    DeliveryTripResponse getTripById(Long id);

    /**
     * Tạo mới chuyến giao hàng: gom nhiều phiếu xuất vào một chuyến giao theo khu vực, gán tài xế và xe (S6-06).
     */
    DeliveryTripResponse createTrip(CreateDeliveryTripRequest request, String username);

    /**
     * Cập nhật tài xế, phương tiện và phân tuyến cho chuyến giao hàng (S6-06).
     */
    DeliveryTripResponse updateTrip(Long id, UpdateDeliveryTripRequest request, String username);

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

    /**
     * Gom nhiều phiếu xuất/đơn hàng vào một chuyến theo khu vực và tìm kiếm từ khóa (S6-06).
     */
    List<DeliveryEligibleOrderResponse> getEligibleOrders(Long warehouseId, Long territoryId, String keyword);

    /**
     * Lấy dữ liệu bảng kê điểm giao của chuyến hàng (S6-06).
     */
    DeliveryTripManifestResponse getManifest(Long tripId, String username);

    /**
     * Xuất file PDF bảng kê danh sách điểm giao của chuyến kèm địa chỉ và SĐT người nhận (S6-06).
     */
    byte[] generateManifestPdf(Long tripId, String username);

    /**
     * Xem trước và in trực tiếp danh sách điểm giao của chuyến (S6-06).
     */
    String generateManifestPrintHtml(Long tripId, String username);
}
