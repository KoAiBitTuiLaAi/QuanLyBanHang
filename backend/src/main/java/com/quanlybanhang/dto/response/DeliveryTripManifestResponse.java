package com.quanlybanhang.dto.response;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * DTO bảng kê / danh sách điểm giao của chuyến giao hàng (S6-06).
 * Phục vụ in danh sách điểm giao kèm địa chỉ và số điện thoại người nhận.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeliveryTripManifestResponse {

    private Long tripId;
    private String tripCode;
    private String routeName;
    private Long territoryId;
    private String territoryName;

    private Long warehouseId;
    private String warehouseCode;
    private String warehouseName;
    private String warehouseAddress;

    private String driverName;
    private String driverPhone;
    private String vehiclePlate;

    private LocalDateTime departureTime;
    private LocalDateTime expectedCompletionTime;
    private String status;
    private String statusDisplayName;
    private String note;

    private Integer totalStops;
    private BigDecimal totalAmount;

    private List<ManifestStopResponse> stops;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ManifestStopResponse {
        private Long stopId;
        private Integer stopOrder;
        private Long orderId;
        private String orderCode;
        private String voucherCode;
        private Long dealerId;
        private String dealerName;
        private String receiverName;
        private String receiverPhone;
        private String deliveryAddress;
        private BigDecimal deliveryAmount;
        private String status;
        private String statusDisplayName;
        private String note;
        private List<ManifestItemResponse> items;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ManifestItemResponse {
        private Long itemId;
        private String productCode;
        private String productName;
        private String unit;
        private BigDecimal quantity;
        private BigDecimal unitPrice;
        private BigDecimal totalPrice;
    }
}
