package com.quanlybanhang.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Entity Thông báo trong ứng dụng (S7-08).
 * Cho phép Đại lý nhận thông báo nhắc hạn thanh toán, cảnh báo quá hạn và các thông báo khác.
 */
@Entity
@Table(name = "notifications", indexes = {
        @Index(name = "idx_notifications_dealer_created", columnList = "dealer_id, created_at"),
        @Index(name = "idx_notifications_dealer_read", columnList = "dealer_id, is_read")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Đại lý nhận thông báo (S7-08).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dealer_id", nullable = false)
    private Dealer dealer;

    /**
     * Tiêu đề thông báo ngắn gọn.
     */
    @Column(nullable = false, length = 255)
    private String title;

    /**
     * Nội dung chi tiết thông báo.
     */
    @Column(nullable = false, length = 1000)
    private String message;

    /**
     * Loại thông báo.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private NotificationType type;

    /**
     * Loại đối tượng tham chiếu (vd: "INVOICE", "ORDER", "DEBT").
     */
    @Column(name = "reference_type", length = 50)
    private String referenceType;

    /**
     * ID đối tượng tham chiếu (vd: ID hóa đơn).
     */
    @Column(name = "reference_id")
    private Long referenceId;

    /**
     * Mã hiển thị đối tượng tham chiếu (vd: "HD-00000001").
     */
    @Column(name = "reference_code", length = 50)
    private String referenceCode;

    /**
     * Trạng thái đã đọc hay chưa.
     */
    @Column(name = "is_read", nullable = false)
    @Builder.Default
    private Boolean isRead = false;

    /**
     * Thời điểm đọc thông báo.
     */
    @Column(name = "read_at")
    private LocalDateTime readAt;

    /**
     * Thời điểm tạo thông báo.
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
        if (this.isRead == null) {
            this.isRead = false;
        }
    }
}
