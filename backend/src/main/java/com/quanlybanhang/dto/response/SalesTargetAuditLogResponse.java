package com.quanlybanhang.dto.response;

import lombok.*;

import java.time.LocalDateTime;

/**
 * Thông tin một dòng nhật ký chỉnh sửa chỉ tiêu doanh số (S8-04).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SalesTargetAuditLogResponse {
    private Long id;
    private String entityType;
    private String entityId;
    private String action;
    private String oldValue;
    private String newValue;
    private String reason;
    private String username;
    private LocalDateTime createdAt;
}

