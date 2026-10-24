package com.quanlybanhang.model;

import lombok.Getter;

/**
 * Trạng thái của Chuyến giao hàng (S6-07).
 */
@Getter
public enum DeliveryTripStatus {
    READY("Sẵn sàng", "Chuyến giao đã lập, chờ khởi hành"),
    IN_PROGRESS("Đang chạy", "Chuyến giao đang trên đường thực hiện"),
    COMPLETED("Hoàn thành", "Chuyến giao đã hoàn tất toàn bộ điểm"),
    CANCELLED("Đã hủy", "Chuyến giao đã bị hủy");

    private final String displayName;
    private final String description;

    DeliveryTripStatus(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }
}

