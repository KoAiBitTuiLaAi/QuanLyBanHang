package com.quanlybanhang.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Một dòng kết quả báo cáo doanh số đa chiều (S8-06).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.ALWAYS)
public class SalesReportRowResponse {

    private Long id;

    private String code;

    private String name;

    /**
     * Doanh số bán hàng (VNĐ).
     */
    @Builder.Default
    private BigDecimal revenue = BigDecimal.ZERO;

    /**
     * Sản lượng bán (đơn vị cơ sở).
     */
    @Builder.Default
    private BigDecimal quantity = BigDecimal.ZERO;

    /**
     * Biên lợi nhuận gộp (%).
     * Chỉ trả về khi người dùng có quyền xem biên lợi nhuận (FINANCE_VIEW_PROFIT_MARGIN hoặc ROLE_ADMIN).
     * Trả về null khi không có quyền xem margin để bảo mật giá vốn/lợi nhuận.
     */
    private BigDecimal grossMargin;

    /**
     * Doanh số cùng kỳ trước (VNĐ) khi bật so sánh (compare=true).
     */
    private BigDecimal previousRevenue;

    /**
     * Tỷ lệ tăng/giảm (%) so với cùng kỳ trước khi bật so sánh.
     */
    private BigDecimal changePercent;
}
