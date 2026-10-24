package com.quanlybanhang.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.math.BigDecimal;

/**
 * Request đặt mới hoặc cập nhật chỉ tiêu doanh số theo tháng (S8-04).
 *
 * Hỗ trợ:
 * - Đặt chỉ tiêu theo tháng (year + month hoặc period "YYYY-MM")
 * - Cho từng nhân viên (userId != null, territoryId == null)
 * - Cho từng khu vực (userId == null, territoryId != null)
 * - Cho nhân viên tại khu vực cụ thể (userId != null, territoryId != null)
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SalesTargetRequest {

    /**
     * Năm của kỳ chỉ tiêu (VD: 2026). Có thể truyền qua period "YYYY-MM".
     */
    private Integer year;

    /**
     * Tháng của kỳ chỉ tiêu (1..12). Có thể truyền qua period "YYYY-MM".
     */
    private Integer month;

    /**
     * Chuỗi kỳ chỉ tiêu định dạng "YYYY-MM" (VD: "2026-10").
     */
    private String period;

    /**
     * ID nhân viên kinh doanh nhận chỉ tiêu (tùy chọn nếu đặt chỉ tiêu khu vực).
     */
    private Long userId;

    /**
     * ID khu vực / địa bàn nhận chỉ tiêu (tùy chọn nếu đặt chỉ tiêu nhân viên).
     */
    private Long territoryId;

    /**
     * Mục tiêu doanh số (VNĐ), bắt buộc > 0.
     */
    @NotNull(message = "Mục tiêu doanh số không được để trống")
    @DecimalMin(value = "0.01", message = "Mục tiêu doanh số phải lớn hơn 0")
    private BigDecimal targetAmount;

    /**
     * Ghi chú thêm cho chỉ tiêu.
     */
    private String note;

    /**
     * Lý do chỉnh sửa (bắt buộc khi cập nhật chỉ tiêu đã qua kỳ / đã khoá).
     */
    private String reason;
}

