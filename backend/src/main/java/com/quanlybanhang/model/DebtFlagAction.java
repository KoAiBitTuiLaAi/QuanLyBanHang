package com.quanlybanhang.model;

/**
 * Hành vi xử lý đơn hàng mới khi đại lý bị gắn cờ cảnh báo rủi ro công nợ (S7-04).
 */
public enum DebtFlagAction {
    /** Áp dụng theo cấu hình mặc định của hệ thống */
    DEFAULT("Mặc định theo hệ thống"),

    /** Khoá bán: chặn tạo đơn mới hoàn toàn */
    BLOCK("Khoá bán / Chặn tạo đơn mới"),

    /** Chuyển đơn mới sang trạng thái Chờ duyệt */
    REQUIRE_APPROVAL("Chuyển sang cần duyệt");

    private final String description;

    DebtFlagAction(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}

