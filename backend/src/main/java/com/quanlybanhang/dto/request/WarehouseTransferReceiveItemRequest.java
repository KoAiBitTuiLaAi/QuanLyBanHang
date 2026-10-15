package com.quanlybanhang.dto.request;

import jakarta.validation.constraints.DecimalMin;
import lombok.*;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WarehouseTransferReceiveItemRequest {

    private Long itemId;

    private Long productId;

    /**
     * Số lượng thực nhận theo đơn vị đã xuất.
     * Nếu để trống/null, mặc định là nhận đủ bằng số lượng đã xuất.
     */
    @DecimalMin(value = "0.0", message = "Số lượng nhận không được âm")
    private BigDecimal receivedQuantity;

    /**
     * Lý do chênh lệch khi nhận hàng. BẮT BUỘC nếu số lượng thực nhận khác số lượng đã xuất!
     */
    private String discrepancyReason;

    private String note;
}

