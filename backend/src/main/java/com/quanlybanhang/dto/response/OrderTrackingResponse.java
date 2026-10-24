package com.quanlybanhang.dto.response;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Response DTO theo dõi toàn diện vòng đời đơn hàng (S4-06).
 * Giúp Nhân viên kinh doanh biết rõ đơn đang nằm ở khâu nào mà không phải gọi hỏi kho.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderTrackingResponse {

    private Long orderId;
    private String orderCode;
    private String customerName;
    private Long dealerId;
    private String dealerName;
    private String dealerCode;

    private Long warehouseId;
    private String warehouseCode;
    private String warehouseName;

    private String currentStatus;
    private String currentStatusDisplayName;
    private int currentStep;

    /** Đơn có thể huỷ được hay không (đơn đã xuất kho thì không huỷ được) */
    private boolean canCancel;

    /** Đơn đã xuất kho hoặc sau xuất kho hay chưa */
    private boolean isExported;

    /** Tồn kho đã được giữ chỗ hay chưa */
    private Boolean inventoryReserved;
    private LocalDateTime reservedAt;
    private LocalDateTime reservationExpiresAt;

    /** Thông tin huỷ đơn nếu đơn bị huỷ */
    private String cancelReason;
    private String cancelledBy;
    private LocalDateTime cancelledAt;

    private LocalDate expectedDeliveryDate;
    private LocalDateTime createdAt;
    private LocalDateTime recordedAt;
    private BigDecimal totalAmount;

    /** Danh sách các bước trong chu trình vòng đời */
    private List<OrderLifecycleStepResponse> steps;

    /** Lịch sử chi tiết từng sự kiện chuyển trạng thái */
    private List<OrderStatusHistoryResponse> history;
}
