package com.quanlybanhang.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * DTO theo dõi trạng thái giao hàng của đơn hàng dành cho Đại lý (S6-09).
 * - Hiển thị 5 mốc thời gian: đã duyệt, đang soạn, đã xuất, đang giao, đã giao.
 * - Xem được số lượng thực giao từng dòng hàng.
 * - Thông tin chuyến xe/tài xế để chủ động bố trí người nhận đúng lúc xe tới.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DealerDeliveryTrackingResponse {

    private Long orderId;
    private String orderCode;
    private Long dealerId;
    private String dealerCode;
    private String dealerName;

    private String status;
    private String statusDisplayName;

    /** Đơn hàng còn nợ hàng sau khi xuất kho. */
    private Boolean backordered;

    /** Đơn hàng có dòng sản phẩm bị giao thiếu. */
    private Boolean hasShortage;

    /** Ghi chú giao hàng chung của đơn. */
    private String deliveryNote;

    private LocalDate expectedDeliveryDate;
    private LocalDate actualDeliveryDate;

    // ── 5 mốc thời gian cốt lõi (S6-09) ──
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime approvedAt;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime pickingAt;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime exportedAt;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime shippingAt;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime deliveredAt;

    /**
     * Danh sách cấu trúc 5 mốc giao hàng theo thứ tự nghiệp vụ:
     * 1. Đã duyệt -> 2. Đang soạn -> 3. Đã xuất -> 4. Đang giao -> 5. Đã giao.
     */
    private List<DeliveryMilestoneResponse> milestones;

    /**
     * Thông tin chuyến giao, tài xế và biển số xe (nếu đơn đã được phân chuyến).
     */
    private DealerDeliveryTripResponse deliveryTrip;

    /**
     * Danh sách số lượng thực giao từng dòng hàng.
     */
    private List<DealerDeliveryTrackingItemResponse> items;
}

