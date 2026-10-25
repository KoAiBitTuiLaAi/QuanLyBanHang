package com.quanlybanhang.dto.response;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderResponse {

    private Long id;
    private Boolean canConfirmPortalOrder;
    private String nextLifecycleStatus;
    private String orderCode;
    private String customerName;
    private com.quanlybanhang.model.CustomerGroup customerGroup;
    private Long dealerId;
    private String dealerCode;
    private String dealerName;
    private Long salesUserId;
    private String salesUserName;
    private String salesUserFullName;
    private Long territoryId;
    private String territoryCode;
    private String territoryName;
    private Long deliveryPointId;
    private Long warehouseId;
    private String warehouseCode;
    private String warehouseName;
    private LocalDate expectedDeliveryDate;
    private Long priceListId;
    private String priceListCode;
    private String status;
    private String statusDisplayName;
    private Boolean backordered;
    private Boolean canCancel;
    private Boolean isExported;
    private Boolean inventoryReserved;
    private String cancelReason;
    private String cancelledBy;
    private LocalDateTime cancelledAt;
    @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime reservedAt;

    @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime reservationExpiresAt;

    private Boolean requiresApproval;
    private String approvalReason;
    private String approvedBy;

    @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime approvedAt;

    private BigDecimal subtotalAmount;
    private BigDecimal discountAmount;
    private BigDecimal totalAmount;
    private String note;
    private String createdBy;

    @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime createdAt;

    @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime recordedAt;
    private List<OrderItemResponse> items;

    /** Cảnh báo đơn của đại lý bị khoá giao dịch (S3-07). */
    private Boolean dealerLocked;
    private String dealerLockWarning;
    private String warning;

    /** Cảnh báo và thông tin công nợ vượt hạn mức (S4-02). */
    private Boolean exceedsDebtLimit;
    private BigDecimal currentDebt;
    private BigDecimal debtLimit;
    private BigDecimal remainingDebtLimit;


    /** Alias tổng thanh toán phục vụ frontend order-approval (S4-05). */
    private BigDecimal grandTotal;

    /** Danh sách mã lý do cần duyệt: OVER_DEBT_LIMIT, BELOW_FLOOR_PRICE, v.v. (S4-05). */
    private List<String> approvalReasons;

    /** Chi tiết các mức vi phạm (S4-05). */
    private List<OrderViolationResponse> violations;

    /** Số tiền công nợ vượt hạn mức (S4-05). */
    private BigDecimal debtExceeded;

    /** Chênh lệch bán dưới giá sàn (S4-05). */
    private BigDecimal priceGap;

    /** Lịch sử các bước duyệt đơn hàng (S4-05) - bất biến, không thể sửa/xoá. */
    private List<OrderApprovalHistoryResponse> approvalHistory;



    /** Ngày thực tế giao hàng (S5-01). */
    private LocalDate actualDeliveryDate;

    /** Matrix permission, warehouse scope and lifecycle capability for delivery editing. */
    private Boolean canUpdateDelivery;

    /** Ghi chú giao hàng tổng thể (S5-01). */
    private String deliveryNote;

    /** Đánh dấu đơn hàng có dòng bị giao thiếu (S5-01). */
    private Boolean hasShortage;

    /** Tổng số dòng hàng trong đơn (S5-01). */
    private Integer totalLineItems;

    /** Thời điểm bắt đầu soạn hàng (S6-09). */
    @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime pickingAt;

    /** Thời điểm xuất kho (S6-09). */
    @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime exportedAt;

    /** Thời điểm bắt đầu giao hàng / xe xuất phát (S6-09). */
    @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime shippingAt;

    /** Thời điểm đã giao hàng thực tế (S6-09). */
    @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime deliveredAt;

    /** 5 mốc giao hàng theo thứ tự: đã duyệt, đang soạn, đã xuất, đang giao, đã giao (S6-09). */
    private List<DeliveryMilestoneResponse> deliveryMilestones;

    /** Thông tin xe/tài xế/chuyến giao hàng (S6-09). */
    private DealerDeliveryTripResponse deliveryTrip;
}
