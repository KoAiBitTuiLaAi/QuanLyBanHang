package com.quanlybanhang.model;

import lombok.Getter;

/**
 * Định nghĩa vòng đời chuẩn của đơn hàng (S4-06):
 * Nháp -> Chờ duyệt -> Đã duyệt -> Đang soạn hàng -> Đã xuất -> Đã giao -> Đóng, và nhánh Huỷ.
 */
@Getter
public enum OrderStatus {

    /** 1. Đơn hàng đang ở trạng thái nháp */
    DRAFT("Nháp", 1, true, false, "Đơn hàng đang ở trạng thái nháp"),

    /** 2. Đơn hàng chờ Quản lý kinh doanh duyệt (bán dưới giá sàn hoặc vượt hạn mức công nợ) */
    PENDING_APPROVAL("Chờ duyệt", 2, true, false, "Đơn hàng chờ Quản lý kinh doanh phê duyệt"),

    /** 3. Đơn hàng đã được duyệt / ghi nhận hợp lệ, đã giữ chỗ tồn kho */
    APPROVED("Đã duyệt", 3, true, false, "Đơn hàng đã được duyệt, sẵn sàng soạn hàng"),
    RECORDED("Đã duyệt", 3, true, false, "Đơn hàng đã ghi nhận/phê duyệt"),

    /** 4. Kho đang soạn hàng và đóng gói */
    PICKING("Đang soạn hàng", 4, true, false, "Kho đang thực hiện soạn hàng và đóng gói"),

    /** 5. Đã xuất kho, đang vận chuyển đến đại lý (ĐÃ XUẤT KHO THÌ KHÔNG THỂ HUỶ) */
    EXPORTED("Đã xuất", 5, false, true, "Đã xuất kho, đang vận chuyển đến đại lý"),

    /** 6. Đã giao hàng thành công tới đại lý */
    DELIVERED("Đã giao", 6, false, true, "Đã giao hàng thành công đến đại lý"),

    /** 7. Đơn hàng hoàn tất và đóng */
    CLOSED("Đóng", 7, false, true, "Đơn hàng đã hoàn tất toàn bộ chu trình và đóng"),

    /** Nhánh Huỷ: Đơn hàng bị huỷ trước khi xuất kho (bắt buộc có lý do, tự động nhả tồn) */
    CANCELLED("Huỷ", -1, false, false, "Đơn hàng đã bị huỷ"),

    /** Nhánh Từ chối: Quản lý từ chối đơn hàng khi ở trạng thái Chờ duyệt */
    REJECTED("Từ chối duyệt", -2, false, false, "Đơn hàng bị từ chối duyệt");

    private final String displayName;
    private final int stepOrder;
    private final boolean canCancel;
    private final boolean exported;
    private final String description;

    OrderStatus(String displayName, int stepOrder, boolean canCancel, boolean exported, String description) {
        this.displayName = displayName;
        this.stepOrder = stepOrder;
        this.canCancel = canCancel;
        this.exported = exported;
        this.description = description;
    }

    public static String getDisplayName(String status) {
        if (status == null || status.isBlank()) {
            return "Không xác định";
        }
        String s = status.trim().toUpperCase();
        try {
            return valueOf(s).getDisplayName();
        } catch (IllegalArgumentException e) {
            if ("PROCESSING".equals(s)) return "Đang soạn hàng";
            if ("SHIPPED".equals(s)) return "Đã xuất";
            if ("COMPLETED".equals(s)) return "Đóng";
            return status;
        }
    }

    public static boolean canCancel(String status) {
        if (status == null || status.isBlank()) {
            return false;
        }
        String s = status.trim().toUpperCase();
        if ("PROCESSING".equals(s)) return true;
        try {
            return valueOf(s).isCanCancel();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static boolean isExportedOrLater(String status) {
        if (status == null || status.isBlank()) {
            return false;
        }
        String s = status.trim().toUpperCase();
        if ("SHIPPED".equals(s) || "COMPLETED".equals(s)) return true;
        try {
            return valueOf(s).isExported();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static boolean isApprovedOrRecorded(String status) {
        if (status == null || status.isBlank()) return false;
        String s = status.trim().toUpperCase();
        return "APPROVED".equals(s) || "RECORDED".equals(s);
    }
}

