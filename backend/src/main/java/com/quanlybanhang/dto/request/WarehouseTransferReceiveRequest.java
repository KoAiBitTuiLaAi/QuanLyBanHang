package com.quanlybanhang.dto.request;

import jakarta.validation.Valid;
import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WarehouseTransferReceiveRequest {

    private String note;

    /**
     * Danh sách mặt hàng nhận thực tế.
     * Nếu để trống, hệ thống sẽ xác nhận nhận đủ toàn bộ theo số lượng đã chuyển.
     */
    private List<@Valid WarehouseTransferReceiveItemRequest> items;
}

