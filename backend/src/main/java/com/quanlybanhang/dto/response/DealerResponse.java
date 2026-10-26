package com.quanlybanhang.dto.response;

import com.quanlybanhang.model.CustomerGroup;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Response chi tiết hồ sơ đại lý (S3-03).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DealerResponse {
    private Long id;
    private String code;
    private String name;
    private String taxCode;
    private CustomerGroup customerGroup;
    private String customerGroupDisplayName;

    // Territory info
    private Long territoryId;
    private String territoryCode;
    private String territoryName;

    // Warehouse info (S4-03 & S5-03: kho phục vụ mặc định)
    private Long warehouseId;
    private String warehouseCode;
    private String warehouseName;
    private Long defaultWarehouseId;
    private String defaultWarehouseCode;
    private String defaultWarehouseName;

    // Assigned User info
    private Long assignedUserId;
    private String assignedUsername;
    private String assignedUserFullName;

    private Boolean active;
    private Boolean handoverRequired;
    private BigDecimal debtLimit;
    private Integer maxDebtDays;

    /** Thông tin công nợ và quá hạn (S4-02). */
    private BigDecimal currentDebt;
    private BigDecimal remainingDebtLimit;
    private Boolean hasOverdueDebt;
    private Boolean isBlockedDueToDebt;
    private Integer maxOverdueDays;

    /** Thông tin gắn cờ rủi ro công nợ tự động (S7-04). */
    private Boolean debtFlagged;
    private com.quanlybanhang.model.DebtFlagType debtFlagType;
    private String debtFlagReason;
    private LocalDateTime debtFlaggedAt;
    private LocalDateTime lastDebtEvaluatedAt;
    private com.quanlybanhang.model.DebtFlagAction debtActionConfig;

    /** Trạng thái khoá giao dịch (S3-07): true = Bị khoá, false = Đang mở */
    private Boolean locked;
    private String lockReason;
    private LocalDateTime lockedAt;
    private String lockedBy;

    /** Số lượng đơn hàng đang dở dang (S3-07). */
    private Long inProgressOrderCount;

    private String phone;
    private String email;
    private String address;

    /** Đại lý đã phát sinh giao dịch thì true; không thể xóa mà chỉ ngừng giao dịch. */
    private boolean hasTransactions;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
