package com.quanlybanhang.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Kết quả lọc danh sách đơn hàng kèm tổng tiền của tập kết quả đang lọc (S4-07).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderFilterResult {

    @Builder.Default
    private List<OrderResponse> orders = new ArrayList<>();

    /** Tổng tiền của toàn bộ tập kết quả đang lọc. */
    @Builder.Default
    private BigDecimal totalAmount = BigDecimal.ZERO;

    /** Tổng số đơn hàng trong tập kết quả đang lọc. */
    @Builder.Default
    private Long totalOrders = 0L;
}

