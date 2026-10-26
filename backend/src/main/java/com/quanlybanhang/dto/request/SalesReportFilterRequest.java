package com.quanlybanhang.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

/**
 * Request bộ lọc báo cáo doanh số đa chiều (S8-06).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SalesReportFilterRequest {

    /**
     * Chiều nhóm: EMPLOYEE, REGION, DEALER, CATEGORY, SKU.
     * Mặc định là SKU.
     */
    @Builder.Default
    private String groupBy = "SKU";

    /**
     * Từ ngày (YYYY-MM-DD).
     */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate fromDate;

    /**
     * Đến ngày (YYYY-MM-DD).
     */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate toDate;

    /**
     * Lọc theo khu vực (Territory ID).
     */
    private Long regionId;

    /**
     * Lọc theo đại lý (Dealer ID).
     */
    private Long dealerId;

    /**
     * Lọc theo nhóm hàng (Category ID).
     */
    private Long categoryId;

    /**
     * Lọc theo nhân viên phụ trách (User ID).
     */
    private Long employeeId;

    /**
     * Bật so sánh với cùng kỳ trước.
     */
    @Builder.Default
    private Boolean compare = false;

    /**
     * Trang hiện tại (0-based).
     */
    @Builder.Default
    private Integer page = 0;

    /**
     * Số dòng trên mỗi trang.
     */
    @Builder.Default
    private Integer size = 20;
}
