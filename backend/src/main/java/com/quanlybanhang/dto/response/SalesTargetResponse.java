package com.quanlybanhang.dto.response;

import com.quanlybanhang.model.SalesTargetScopeType;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * DTO phản hồi chi tiết một chỉ tiêu doanh số kèm tiến độ thực đạt theo thời gian thực (S8-04).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SalesTargetResponse {

    private Long id;
    private Long version;
    private String targetCode;

    private Integer year;
    private Integer month;
    private String period;

    private SalesTargetScopeType scopeType;
    private String scopeTypeLabel;

    private Long userId;
    private String username;
    private String userFullName;

    private Long territoryId;
    private String territoryCode;
    private String territoryName;

    /**
     * Mục tiêu doanh số được giao trong tháng (VNĐ).
     */
    private BigDecimal targetAmount;

    /**
     * Doanh số thực tế đạt được tính theo thời gian thực (VNĐ).
     */
    private BigDecimal actualAmount;

    /**
     * Alias tương thích cho actualAmount.
     */
    private BigDecimal achievedAmount;

    /**
     * Số tiền còn phải đạt để hoàn thành chỉ tiêu = max(0, targetAmount - actualAmount).
     */
    private BigDecimal remainingAmount;

    /**
     * Số tiền vượt chỉ tiêu = max(0, actualAmount - targetAmount).
     */
    private BigDecimal overachievedAmount;

    /**
     * Tỷ lệ hoàn thành chỉ tiêu (%) = (actualAmount / targetAmount) * 100.
     */
    private BigDecimal completionRate;

    /**
     * Alias tương thích cho completionRate.
     */
    private BigDecimal progressPercent;

    /**
     * Số đơn hàng hợp lệ đã ghi nhận đóng góp vào chỉ tiêu trong kỳ.
     */
    private Integer orderCount;

    // ── Chỉ số theo dõi tiến độ giữa tháng (Mid-month Pacing) ──

    private Integer totalDaysInMonth;
    private Integer elapsedDays;
    private Integer remainingDays;

    /**
     * Tỷ lệ thời gian đã trôi qua trong tháng (%).
     */
    private BigDecimal timeElapsedPercent;

    /**
     * Doanh số chuẩn cần đạt tính đến thời điểm hiện tại theo tiến độ tuyến tính trong tháng.
     */
    private BigDecimal expectedAmountToDate;

    /**
     * Chênh lệch giữa thực đạt hiện tại và mức chuẩn cần đạt đến thời điểm hiện tại (actualAmount - expectedAmountToDate).
     * Dương = vượt tiến độ thời gian; Âm = chậm hơn tiến độ thời gian.
     */
    private BigDecimal pacingVarianceAmount;

    /**
     * Doanh số bình quân mỗi ngày cần đạt trong những ngày còn lại của tháng để về đích.
     */
    private BigDecimal requiredDailyRunRate;

    /**
     * Mã trạng thái tiến độ: ACHIEVED, ON_TRACK, BEHIND, AT_RISK, NOT_STARTED.
     */
    private String progressStatus;

    /**
     * Nhãn tiếng Việt của trạng thái tiến độ (VD: "Đã đạt chỉ tiêu", "Đúng tiến độ", "Chậm tiến độ").
     */
    private String progressStatusLabel;

    // ── Trạng thái khoá kỳ & Nhật ký ──

    /**
     * Kỳ chỉ tiêu đã qua hay chưa (YearMonth < currentYearMonth).
     */
    private Boolean pastPeriod;

    /**
     * Có phải kỳ hiện tại không.
     */
    private Boolean currentPeriod;

    /**
     * Trạng thái khoá chỉ tiêu (true nếu đã qua kỳ hoặc bị khoá).
     * Khi locked = true, mọi chỉnh sửa bắt buộc phải kèm lý do và ghi AuditLog.
     */
    private Boolean locked;

    private LocalDateTime lockedAt;
    private String lockedBy;

    private String note;
    private String lastModifyReason;

    private String createdBy;
    private String updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * Nhật ký chỉnh sửa của chỉ tiêu này (được nạp khi xem chi tiết hoặc sau khi sửa).
     */
    private List<SalesTargetAuditLogResponse> auditLogs;
}

