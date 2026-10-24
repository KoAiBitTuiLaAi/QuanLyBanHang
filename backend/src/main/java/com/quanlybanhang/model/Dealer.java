package com.quanlybanhang.model;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Đại lý / Khách hàng (S3-03).
 * Quản lý hồ sơ đại lý chuẩn gồm mã đại lý (duy nhất), tên, mã số thuế,
 * nhóm khách hàng, khu vực, người phụ trách, trạng thái hoạt động.
 */
@Entity
@Table(name = "dealers", uniqueConstraints = {
        @UniqueConstraint(columnNames = "code")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Dealer {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Mã đại lý - bắt buộc, duy nhất (S3-03). */
    @Column(name = "code", nullable = false, unique = true, length = 50)
    private String code;

    /** Tên đại lý - bắt buộc (S3-03). */
    @Column(nullable = false, length = 150)
    private String name;

    /** Mã số thuế của đại lý (S3-03). */
    @Column(name = "tax_code", length = 50)
    private String taxCode;

    /** Người phụ trách đại lý (nhân viên kinh doanh) (S3-03). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_user_id")
    private User assignedUser;

    /** Khu vực / địa bàn của đại lý (S3-03). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "territory_id")
    private Territory territory;

    /** Kho phục vụ đại lý (S4-03). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "warehouse_id")
    private Warehouse warehouse;

    /** Trạng thái giao dịch: true = Đang giao dịch, false = Ngừng giao dịch (S3-03). */
    @Column(nullable = false)
    @Builder.Default
    private Boolean active = true;

    /**
     * Trạng thái khoá giao dịch (S3-07):
     * true = Khoá giao dịch với đại lý (dừng bán ngay do có dấu hiệu mất khả năng thanh toán)
     * false = Mở giao dịch bình thường
     */
    @Column(nullable = false)
    @Builder.Default
    private Boolean locked = false;

    /**
     * Lý do khoá giao dịch (S3-07) - Bắt buộc nhập khi khoá.
     */
    @Column(name = "lock_reason", length = 500)
    private String lockReason;

    /**
     * Thời điểm khoá giao dịch (S3-07).
     */
    @Column(name = "locked_at")
    private LocalDateTime lockedAt;

    /**
     * Người thực hiện khoá giao dịch (S3-07).
     */
    @Column(name = "locked_by", length = 100)
    private String lockedBy;

    @Column(nullable = false)
    @Builder.Default
    private Boolean handoverRequired = false;

    /** Hạn mức công nợ tối đa được cấp cho đại lý. */
    @Column(name = "debt_limit", nullable = false, precision = 19, scale = 2)
    @Builder.Default
    private BigDecimal debtLimit = BigDecimal.ZERO;

    /** Số ngày nợ tối đa cho phép của đại lý (S3-05). */
    @Column(name = "max_debt_days", nullable = false)
    @Builder.Default
    private Integer maxDebtDays = 0;

    /**
     * Cờ cảnh báo rủi ro công nợ tự động (S7-04):
     * true = Đại lý bị gắn cờ tự động khi vượt hạn mức tiền hoặc có khoản nợ quá hạn
     * false = Bình thường
     */
    @Column(name = "debt_flagged", nullable = false)
    @Builder.Default
    private Boolean debtFlagged = false;

    /**
     * Loại vi phạm dẫn đến gắn cờ tự động (S7-04).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "debt_flag_type", length = 30)
    @Builder.Default
    private DebtFlagType debtFlagType = DebtFlagType.NONE;

    /**
     * Lý do gắn cờ tự động chi tiết (S7-04).
     */
    @Column(name = "debt_flag_reason", length = 1000)
    private String debtFlagReason;

    /**
     * Thời điểm đại lý bị gắn cờ công nợ gần nhất (S7-04).
     */
    @Column(name = "debt_flagged_at")
    private LocalDateTime debtFlaggedAt;

    /**
     * Thời điểm hệ thống đánh giá lại công nợ gần nhất (S7-04).
     */
    @Column(name = "last_debt_evaluated_at")
    private LocalDateTime lastDebtEvaluatedAt;

    /**
     * Cấu hình hành vi riêng cho đại lý khi bị gắn cờ (S7-04):
     * DEFAULT: áp dụng cấu hình chung của hệ thống.
     * BLOCK: luôn chặn tạo đơn mới.
     * REQUIRE_APPROVAL: luôn chuyển sang cần duyệt.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "debt_action_config", length = 30)
    @Builder.Default
    private DebtFlagAction debtActionConfig = DebtFlagAction.DEFAULT;

    /**
     * Nhóm khách hàng của đại lý (mặc định là Đại lý cấp 1 - DAI_LY_CAP_1).
     * Quyết định bảng giá được áp dụng (S3-03, S2-10).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "customer_group", length = 30)
    @Builder.Default
    private CustomerGroup customerGroup = CustomerGroup.DAI_LY_CAP_1;

    @Column(length = 20)
    private String phone;

    @Column(length = 100)
    private String email;

    @Column(length = 255)
    private String address;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (active == null) {
            active = true;
        }
        if (debtLimit == null) {
            debtLimit = BigDecimal.ZERO;
        }
        if (maxDebtDays == null) {
            maxDebtDays = 0;
        }
        if (handoverRequired == null) {
            handoverRequired = false;
        }
        if (customerGroup == null) {
            customerGroup = CustomerGroup.DAI_LY_CAP_1;
        }
        if (locked == null) {
            locked = false;
        }
        if (debtFlagged == null) {
            debtFlagged = false;
        }
        if (debtFlagType == null) {
            debtFlagType = DebtFlagType.NONE;
        }
        if (debtActionConfig == null) {
            debtActionConfig = DebtFlagAction.DEFAULT;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
