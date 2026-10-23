package com.quanlybanhang.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request Quản lý kho xác nhận nhận hàng trả từ đại lý (S8-01).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConfirmDealerReturnRequest {

    /** Ghi chú của Quản lý kho khi xác nhận nhận hàng và cộng tồn. */
    private String note;

    /** Kho hàng lỗi tùy chọn nếu Quản lý kho chỉ định kho hàng lỗi cụ thể khi xác nhận. */
    private Long defectiveWarehouseId;
}

