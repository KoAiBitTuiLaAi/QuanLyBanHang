package com.quanlybanhang.dto.response;

import lombok.*;

import java.time.LocalDateTime;

/**
 * Response DTO cho lịch sử thay đổi trạng thái đơn hàng (S4-06).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderStatusHistoryResponse {

    private Long id;
    private Long orderId;
    private String previousStatus;
    private String newStatus;
    private String statusDisplayName;
    private String note;
    private String changedBy;
    private LocalDateTime changedAt;
}

