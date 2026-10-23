package com.quanlybanhang.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.*;

import java.util.List;

/**
 * Request đặt / cập nhật hàng loạt chỉ tiêu doanh số trong tháng cho nhiều nhân viên và khu vực (S8-04).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BatchSalesTargetRequest {

    /**
     * Năm mặc định cho các dòng không truyền riêng year/period.
     */
    private Integer year;

    /**
     * Tháng mặc định (1..12) cho các dòng không truyền riêng month/period.
     */
    private Integer month;

    /**
     * Kỳ mặc định định dạng "YYYY-MM".
     */
    private String period;

    /**
     * Lý do chỉnh sửa chung (áp dụng nếu cập nhật các chỉ tiêu đã qua kỳ / đã khoá).
     */
    private String reason;

    @NotEmpty(message = "Danh sách chỉ tiêu không được để trống")
    @Valid
    private List<SalesTargetRequest> targets;
}

