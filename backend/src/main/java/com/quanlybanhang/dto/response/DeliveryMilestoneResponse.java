package com.quanlybanhang.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Chi tiết từng mốc trong 5 mốc theo dõi giao hàng (S6-09):
 * 1. Đã duyệt (approved)
 * 2. Đang soạn (picking)
 * 3. Đã xuất (exported)
 * 4. Đang giao (shipping)
 * 5. Đã giao (delivered)
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeliveryMilestoneResponse {

    /**
     * Mã định danh mốc: approved, picking, exported, shipping, delivered.
     */
    private String milestoneKey;

    /**
     * Nhãn hiển thị tiếng Việt: Đã duyệt, Đang soạn, Đã xuất, Đang giao, Đã giao.
     */
    private String label;

    /**
     * Trạng thái mốc: COMPLETED (đã hoàn thành), CURRENT (trạng thái hiện tại), PENDING (chưa tới), UNKNOWN (chưa có dữ liệu).
     */
    private String status;

    /**
     * Thời gian xác nhận mốc từ hệ thống.
     */
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime timestamp;

    /**
     * Ghi chú bổ sung hoặc chi tiết chứng từ / người thực hiện / xe giao.
     */
    private String description;
}

