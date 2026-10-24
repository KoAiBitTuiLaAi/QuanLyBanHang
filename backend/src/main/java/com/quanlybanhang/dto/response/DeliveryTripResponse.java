package com.quanlybanhang.dto.response;

import com.quanlybanhang.model.DeliveryTrip;
import com.quanlybanhang.model.DeliveryTripStatus;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Response chi tiết Chuyến giao hàng (S6-07).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeliveryTripResponse {

    private Long id;
    private String tripCode;

    private Long warehouseId;
    private String warehouseCode;
    private String warehouseName;

    private String driverName;
    private String driverPhone;
    private String vehiclePlate;

    private DeliveryTripStatus status;
    private String statusDisplayName;

    private LocalDateTime departureTime;
    private LocalDateTime expectedCompletionTime;
    private LocalDateTime actualCompletionTime;

    private String note;
    private String cancelReason;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // ── Metrics tiến trình giao hàng (S6-07) ──
    private Integer totalPoints;
    private Long deliveredPoints;
    private Long remainingPoints;
    private Long failedPoints;
    private Long pendingPoints;

    // ── Metrics tài chính trên đường (S6-07: bao nhiêu tiền hàng nằm trên đường) ──
    private BigDecimal totalValue;
    private BigDecimal deliveredValue;
    private BigDecimal remainingValue;
    private BigDecimal failedValue;

    // ── Cảnh báo chuyến quá thời gian dự kiến (S6-07) ──
    private Boolean isOverdue;
    private Long overdueMinutes;
    private String overdueWarning;

    private List<DeliveryTripStopResponse> stops;

    public static DeliveryTripResponse from(DeliveryTrip trip, List<DeliveryTripStopResponse> stopResponses) {
        boolean overdue = trip.isOverdue();
        long overdueMins = trip.getOverdueMinutes();
        String warning = overdue
                ? "Chuyến giao đã quá thời gian dự kiến " + overdueMins + " phút"
                : null;

        return DeliveryTripResponse.builder()
                .id(trip.getId())
                .tripCode(trip.getTripCode())
                .warehouseId(trip.getWarehouse() != null ? trip.getWarehouse().getId() : null)
                .warehouseCode(trip.getWarehouse() != null ? trip.getWarehouse().getCode() : null)
                .warehouseName(trip.getWarehouse() != null ? trip.getWarehouse().getName() : null)
                .driverName(trip.getDriverName())
                .driverPhone(trip.getDriverPhone())
                .vehiclePlate(trip.getVehiclePlate())
                .status(trip.getStatus())
                .statusDisplayName(trip.getStatus() != null ? trip.getStatus().getDisplayName() : null)
                .departureTime(trip.getDepartureTime())
                .expectedCompletionTime(trip.getExpectedCompletionTime())
                .actualCompletionTime(trip.getActualCompletionTime())
                .note(trip.getNote())
                .cancelReason(trip.getCancelReason())
                .createdBy(trip.getCreatedBy())
                .createdAt(trip.getCreatedAt())
                .updatedAt(trip.getUpdatedAt())
                .totalPoints(trip.getTotalPoints())
                .deliveredPoints(trip.getDeliveredPoints())
                .remainingPoints(trip.getRemainingPoints())
                .failedPoints(trip.getFailedPoints())
                .pendingPoints(trip.getPendingPoints())
                .totalValue(trip.getTotalValue())
                .deliveredValue(trip.getDeliveredValue())
                .remainingValue(trip.getRemainingValue())
                .failedValue(trip.getFailedValue())
                .isOverdue(overdue)
                .overdueMinutes(overdueMins)
                .overdueWarning(warning)
                .stops(stopResponses)
                .build();
    }
}

