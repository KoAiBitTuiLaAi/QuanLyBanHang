package com.quanlybanhang.dto.response;

import com.quanlybanhang.model.CustomerGroup;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Thông tin hồ sơ đại lý dùng cho cổng đại lý (S4-10).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DealerPortalProfileResponse {
    private Long id;
    private String code;
    private String name;
    private CustomerGroup customerGroup;
    private String customerGroupName;
    private String phone;
    private String email;
    private String address;
    private String taxCode;
    private Boolean active;
    private Boolean locked;
    private String lockReason;

    // Người phụ trách (nhân viên kinh doanh)
    private Long salesUserId;
    private String salesUserName;
    private String salesUserFullName;
    private String salesUserPhone;
    private String salesUserEmail;

    // Công nợ & hạn mức
    private BigDecimal debtLimit;
    private BigDecimal currentDebt;
    private BigDecimal remainingLimit;
    private Boolean hasOverdueDebt;
    private Boolean isBlocked;
    private String blockReason;

    // Điểm giao hàng
    private List<DealerDeliveryPointResponse> deliveryPoints;
}

