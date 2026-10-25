package com.quanlybanhang.model;

public enum DeliveryFailureReason {
    CLOSED("Cửa hàng đóng cửa"), ABSENT("Không có người nhận"), REFUSED("Người nhận từ chối"),
    ADDRESS("Địa chỉ hoặc thông tin liên hệ chưa đúng"), OTHER("Lý do khác");
    private final String label;
    DeliveryFailureReason(String label) { this.label = label; }
    public String getLabel() { return label; }
}
