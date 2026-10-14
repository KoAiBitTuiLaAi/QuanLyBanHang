package com.quanlybanhang.dto.response;

import lombok.*;

import java.time.LocalDateTime;

/**
 * Chi tiết từng bước trong vòng đời đơn hàng (S4-06):
 * Nháp -> Chờ duyệt -> Đã duyệt -> Đang soạn hàng -> Đã xuất -> Đã giao -> Đóng
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderLifecycleStepResponse {

    private int stepOrder;
    private String status;
    private String displayName;
    private String description;

    /**
     * Trạng thái của bước:
     * - COMPLETED: Đã hoàn tất bước này
     * - CURRENT: Đang ở bước này
     * - UPCOMING: Bước sắp tới
     * - SKIPPED: Bước được bỏ qua (vd: không cần duyệt hoặc huỷ trước đó)
     * - CANCELLED: Đơn hàng bị huỷ tại bước này
     */
    private String state;

    private LocalDateTime timestamp;
    private String operator;
    private String note;
}

