package com.quanlybanhang.model;

/**
 * Loại cờ cảnh báo công nợ tự động (S7-04).
 */
public enum DebtFlagType {
    NONE("Bình thường"),
    DEBT_LIMIT_EXCEEDED("Vượt hạn mức công nợ"),
    OVERDUE("Quá hạn thanh toán"),
    BOTH("Vượt hạn mức và quá hạn thanh toán");

    private final String description;

    DebtFlagType(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}

