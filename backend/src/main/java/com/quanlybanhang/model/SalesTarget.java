package com.quanlybanhang.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;

/**
 * Entity Chỉ tiêu doanh số theo tháng (S8-04).
 *
 * Tiêu chí nghiệp vụ:
 * 1. Đặt chỉ tiêu theo tháng cho từng nhân viên và từng khu vực (EMPLOYEE, TERRITORY, EMPLOYEE_TERRITORY).
 * 2. Nhân viên xem được tiến độ của chính mình theo thời gian thực.
 * 3. Chỉ tiêu đã qua kỳ thì khoá lại, sửa phải có lý do và được ghi nhật ký (AuditLog).
 */
@Entity
@Table(
        name = "sales_targets",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_sales_targets_target_code", columnNames = "target_code"),
                @UniqueConstraint(name = "uk_sales_targets_scope_key", columnNames = "scope_key")
        },
        indexes = {
                @Index(name = "idx_sales_targets_period", columnList = "target_year, target_month"),
                @Index(name = "idx_sales_targets_user", columnList = "user_id, target_year, target_month"),
                @Index(name = "idx_sales_targets_territory", columnList = "territory_id, target_year, target_month")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SalesTarget {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    @Column(nullable = false, columnDefinition = "bigint default 0")
    @Builder.Default
    private Long version = 0L;

    /**
     * Mã chỉ tiêu duy nhất (VD: "CT-202610-NV1-KV2").
     */
    @Column(name = "target_code", nullable = false, unique = true, length = 80)
    private String targetCode;

    /**
     * Khóa phạm vi chuẩn hóa không bao giờ null để đảm bảo duy nhất trên cả MySQL và H2
     * kể cả khi user_id hoặc territory_id là null (VD: "2026-10:U:5:T:ALL").
     */
    @Column(name = "scope_key", nullable = false, unique = true, length = 100)
    private String scopeKey;

    /**
     * Năm của kỳ chỉ tiêu (VD: 2026).
     */
    @Column(name = "target_year", nullable = false)
    private Integer targetYear;

    /**
     * Tháng của kỳ chỉ tiêu (1..12).
     */
    @Column(name = "target_month", nullable = false)
    private Integer targetMonth;

    /**
     * Phân loại chỉ tiêu: EMPLOYEE, TERRITORY, EMPLOYEE_TERRITORY.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false, length = 30)
    private SalesTargetScopeType scopeType;

    /**
     * Nhân viên kinh doanh nhận chỉ tiêu (có thể null nếu là chỉ tiêu chung cho toàn khu vực TERRITORY).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    /**
     * Khu vực / địa bàn nhận chỉ tiêu (có thể null nếu là chỉ tiêu tổng của nhân viên EMPLOYEE).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "territory_id")
    private Territory territory;

    /**
     * Mục tiêu doanh số trong tháng (VNĐ).
     */
    @Column(name = "target_amount", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal targetAmount = BigDecimal.ZERO;

    /**
     * Cờ khoá chỉ tiêu. Tự động khoá khi kỳ chỉ tiêu đã qua hoặc do quản lý khoá.
     */
    @Column(nullable = false)
    @Builder.Default
    private Boolean locked = false;

    @Column(name = "locked_at")
    private LocalDateTime lockedAt;

    @Column(name = "locked_by", length = 100)
    private String lockedBy;

    /**
     * Ghi chú khi đặt chỉ tiêu.
     */
    @Column(length = 500)
    private String note;

    /**
     * Lý do chỉnh sửa gần nhất (bắt buộc khi sửa chỉ tiêu đã qua kỳ / đã khoá).
     */
    @Column(name = "last_modify_reason", length = 500)
    private String lastModifyReason;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "updated_by", length = 100)
    private String updatedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public YearMonth getYearMonth() {
        return YearMonth.of(targetYear, targetMonth);
    }

    public String getPeriodString() {
        return String.format("%04d-%02d", targetYear, targetMonth);
    }

    public boolean isPastPeriod(YearMonth currentPeriod) {
        return getYearMonth().isBefore(currentPeriod);
    }

    public boolean isEffectiveLocked(YearMonth currentPeriod) {
        return Boolean.TRUE.equals(locked) || isPastPeriod(currentPeriod);
    }

    public static String buildScopeKey(int year, int month, Long userId, Long territoryId) {
        return String.format("%04d-%02d:U:%s:T:%s",
                year,
                month,
                userId != null ? userId.toString() : "ALL",
                territoryId != null ? territoryId.toString() : "ALL");
    }

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        if (this.updatedAt == null) {
            this.updatedAt = now;
        }
        if (this.locked == null) {
            this.locked = false;
        }
        if (this.targetAmount == null) {
            this.targetAmount = BigDecimal.ZERO;
        }
        if (this.version == null) {
            this.version = 0L;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}

