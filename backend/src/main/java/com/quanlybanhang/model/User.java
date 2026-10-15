package com.quanlybanhang.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

/**
 * Entity User - lưu trữ thông tin tài khoản người dùng.
 *
 * Bao gồm:
 * - Thông tin cơ bản: username, email, password, fullName, phone
 * - Trạng thái: active, locked (khoá tạm do sai password)
 * - Lockout: đếm số lần sai, thời gian khoá, lý do khoá
 * - Reset password: token + expiry
 */
@Entity
@Table(name = "users",
       uniqueConstraints = {
           @UniqueConstraint(columnNames = "username"),
           @UniqueConstraint(columnNames = "email")
       })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String username;

    @Column(nullable = false, length = 100)
    private String email;

    @Column(nullable = false)
    private String password;

    @Column(length = 100)
    private String fullName;

    @Column(length = 15)
    private String phone;

    /** Tên file ảnh đại diện vuông đã xử lý; file vật lý nằm trong avatar storage. */
    @Column(length = 160)
    private String avatarFileName;

    /** Tên file thumbnail của ảnh đại diện. */
    @Column(length = 160)
    private String avatarThumbnailFileName;

    @Column(length = 20)
    private String avatarContentType;

    private LocalDateTime avatarUpdatedAt;

    /**
     * Tài khoản có đang hoạt động không.
     * false = bị khoá bởi admin (S1-10).
     */
    @Column(nullable = false)
    @Builder.Default
    private Boolean active = true;

    // ── Lockout fields (S1-01) ──

    /**
     * Số lần đăng nhập sai liên tiếp. Reset về 0 khi đăng nhập đúng.
     */
    @Column(nullable = false)
    @Builder.Default
    private Integer failedLoginAttempts = 0;

    /**
     * Thời điểm hết khoá tạm (null = không bị khoá tạm).
     * Khoá tạm 15 phút sau 5 lần sai liên tiếp.
     */
    private LocalDateTime lockTime;

    /**
     * Lý do bị khoá bởi admin (S1-10).
     */
    @Column(length = 500)
    private String lockReason;

    /** Tăng giá trị này để thu hồi đồng thời mọi access/refresh token đã phát hành. */
    @Column(nullable = false)
    @Builder.Default
    private Long tokenVersion = 0L;

    /** Tài khoản mới được cấp mật khẩu tạm và cần đổi ở lần đăng nhập đầu. */
    @Column(nullable = false)
    @Builder.Default
    private Boolean mustChangePassword = false;

    // ── Roles ──

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "user_roles",
               joinColumns = @JoinColumn(name = "user_id"),
               inverseJoinColumns = @JoinColumn(name = "role_id"))
    @Builder.Default
    private Set<Role> roles = new HashSet<>();

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "user_warehouses",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "warehouse_id"))
    @Builder.Default
    private Set<Warehouse> warehouses = new HashSet<>();

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "user_territories",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "territory_id"))
    @Builder.Default
    private Set<Territory> territories = new HashSet<>();

    /**
     * Đại lý liên kết với tài khoản này (dành cho người dùng đại lý - ROLE_DEALER) (S5-01).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dealer_id")
    private Dealer dealer;

    // ── Reset password ──

    @Column(length = 255)
    private String resetPasswordToken;

    private LocalDateTime resetPasswordExpiry;

    // ── Timestamps ──

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime updatedAt;

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // ── Helper methods ──

    /**
     * Kiểm tra tài khoản có đang bị khoá tạm (do sai password) không.
     */
    public boolean isTemporarilyLocked() {
        return lockTime != null && lockTime.isAfter(LocalDateTime.now());
    }

    /**
     * Kiểm tra tài khoản có thể đăng nhập được không.
     * Không được nếu: bị admin khoá (active=false) HOẶC bị khoá tạm.
     */
    public boolean canLogin() {
        return active && !isTemporarilyLocked();
    }
}
