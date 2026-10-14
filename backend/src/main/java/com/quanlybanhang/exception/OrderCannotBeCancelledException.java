package com.quanlybanhang.exception;

/**
 * Ngoại lệ khi cố gắng huỷ đơn hàng đã xuất kho hoặc không thể huỷ (S4-06).
 * Đơn đã xuất kho thì không huỷ được, phải đi đường trả hàng.
 */
public class OrderCannotBeCancelledException extends RuntimeException {

    public OrderCannotBeCancelledException(String message) {
        super(message);
    }
}

