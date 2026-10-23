package com.quanlybanhang.dto.response;

import lombok.*;

import java.math.BigDecimal;

/**
 * Response thống kê tổng quan Chuyến giao hàng và Tiền hàng trên đường (S6-07).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeliveryTripSummaryResponse {

    /** Unresolved goods across ALL trip states; closing a trip does not return goods. */
    private BigDecimal totalOutstandingValue;
    private Long totalOutstandingTrips;
    private BigDecimal closedOutstandingValue;
    private Long closedOutstandingTrips;

    /**
     * Tổng số chuyến giao đang chạy (IN_PROGRESS).
     */
    private Long totalRunningTrips;

    /**
     * Số chuyến giao đang chạy bị quá thời gian dự kiến (Cảnh báo S6-07).
     */
    private Long totalOverdueTrips;

    /**
     * TỔNG TIỀN HÀNG ĐANG NẰM TRÊN ĐƯỜNG (S6-07).
     * Bao gồm giá trị hàng chưa giao xong (chờ giao + chưa giao được vẫn thuộc trách nhiệm chuyến).
     */
    private BigDecimal totalValueOnRoad;

    /**
     * Tổng tiền hàng đã giao thành công của các chuyến đang chạy.
     */
    private BigDecimal totalDeliveredValue;

    /**
     * Tổng tiền hàng xuất phát của các chuyến đang chạy.
     */
    private BigDecimal totalTripValue;

    /**
     * Tổng số điểm giao của các chuyến đang chạy.
     */
    private Long totalPoints;

    /**
     * Tổng số điểm đã giao thành công.
     */
    private Long totalDeliveredPoints;

    /**
     * Tổng số điểm còn lại chưa giao xong.
     */
    private Long totalRemainingPoints;

    /**
     * Tổng số điểm chưa giao được (FAILED, vẫn thuộc trách nhiệm chuyến, không tự về kho).
     */
    private Long totalFailedPoints;

    /**
     * Tổng số điểm đang chờ giao (PENDING).
     */
    private Long totalPendingPoints;
}

