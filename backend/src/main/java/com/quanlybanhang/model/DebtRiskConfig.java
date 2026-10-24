package com.quanlybanhang.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Cấu hình kiểm soát rủi ro công nợ toàn hệ thống (S7-04).
 * Thiết lập hành vi khi đại lý bị gắn cờ rủi ro (Chặn bán hoặc Cần duyệt).
 */
@Entity
@Table(name = "debt_risk_configs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DebtRiskConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Hành vi mặc định khi đại lý bị gắn cờ:
     * BLOCK (Khoá bán / chặn tạo đơn) hoặc REQUIRE_APPROVAL (Chuyển sang cần duyệt).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "default_action", nullable = false, length = 30)
    @Builder.Default
    private DebtFlagAction defaultAction = DebtFlagAction.BLOCK;

    /**
     * Hành vi khi đại lý bị nợ quá hạn:
     * BLOCK hoặc REQUIRE_APPROVAL.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "overdue_action", nullable = false, length = 30)
    @Builder.Default
    private DebtFlagAction overdueAction = DebtFlagAction.BLOCK;

    /**
     * Hành vi khi đại lý vượt hạn mức tiền:
     * BLOCK hoặc REQUIRE_APPROVAL.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "limit_exceeded_action", nullable = false, length = 30)
    @Builder.Default
    private DebtFlagAction limitExceededAction = DebtFlagAction.REQUIRE_APPROVAL;

    /**
     * Cho phép tự động gắn cờ đại lý vi phạm.
     */
    @Column(name = "auto_flag_enabled", nullable = false)
    @Builder.Default
    private Boolean autoFlagEnabled = true;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "updated_by", length = 100)
    private String updatedBy;

    @PrePersist
    @PreUpdate
    protected void onSave() {
        if (defaultAction == null) {
            defaultAction = DebtFlagAction.BLOCK;
        }
        if (overdueAction == null) {
            overdueAction = DebtFlagAction.BLOCK;
        }
        if (limitExceededAction == null) {
            limitExceededAction = DebtFlagAction.REQUIRE_APPROVAL;
        }
        if (autoFlagEnabled == null) {
            autoFlagEnabled = true;
        }
        updatedAt = LocalDateTime.now();
    }
}

