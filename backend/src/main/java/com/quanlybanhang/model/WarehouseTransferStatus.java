package com.quanlybanhang.model;

/**
 * Trạng thái phiếu chuyển kho nội bộ (S5-07):
 * - IN_TRANSIT: Hàng đang trên đường chuyển từ kho đi đến kho đến (đã trừ tồn kho đi, chưa cộng tồn kho đến).
 * - COMPLETED: Kho đến đã xác nhận nhận hàng (đã cộng tồn kho đến theo số lượng thực nhận, chênh lệch có giải trình lý do).
 * - CANCELLED: Đã hủy phiếu chuyển (hoàn lại tồn kho cho kho đi).
 */
public enum WarehouseTransferStatus {
    IN_TRANSIT,
    COMPLETED,
    CANCELLED
}

