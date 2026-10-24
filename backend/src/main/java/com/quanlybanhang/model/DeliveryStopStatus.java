package com.quanlybanhang.model;

import lombok.Getter;

/**
 * Trạng thái điểm giao hàng trong chuyến (S6-07).
 */
@Getter
public enum DeliveryStopStatus {
    PENDING("Chờ giao", "Điểm giao chưa được ghé thăm hoặc đang trên đường"),
    DELIVERED("Đã giao", "Đã giao hàng thành công đến điểm nhận"),
    FAILED("Chưa giao được", "Giao hàng thất bại / không giao được; hàng vẫn thuộc trách nhiệm chuyến và KHÔNG tự về kho");

    private final String displayName;
    private final String description;

    DeliveryStopStatus(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }
}

