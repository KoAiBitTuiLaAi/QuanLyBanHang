package com.quanlybanhang.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO response chung cho tất cả API - giúp frontend xử lý nhất quán.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse {
    private boolean success;
    private String message;
    private Object data;

    /** Tổng tiền của tập kết quả đang lọc (S4-07). */
    private java.math.BigDecimal totalAmount;

    /** Tổng số đơn hàng / phần tử trong tập kết quả lọc. */
    private Long totalOrders;

    /** Tổng số phần tử (dành cho phân trang). */
    private Long totalElements;

    public static ApiResponse success(String message) {
        return ApiResponse.builder()
                .success(true)
                .message(message)
                .build();
    }

    public static ApiResponse success(String message, Object data) {
        return ApiResponse.builder()
                .success(true)
                .message(message)
                .data(data)
                .build();
    }

    public static ApiResponse error(String message) {
        return ApiResponse.builder()
                .success(false)
                .message(message)
                .build();
    }
}
