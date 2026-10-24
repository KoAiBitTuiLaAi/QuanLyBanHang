package com.quanlybanhang.dto.response;

import com.quanlybanhang.model.Notification;
import com.quanlybanhang.model.NotificationType;
import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationResponse {
    private Long id;
    private Long dealerId;
    private String dealerCode;
    private String title;
    private String message;
    private NotificationType type;
    private String typeLabel;
    private String referenceType;
    private Long referenceId;
    private String referenceCode;
    private Boolean isRead;
    private LocalDateTime readAt;
    private LocalDateTime createdAt;

    public static NotificationResponse fromEntity(Notification entity) {
        if (entity == null) return null;
        return NotificationResponse.builder()
                .id(entity.getId())
                .dealerId(entity.getDealer() != null ? entity.getDealer().getId() : null)
                .dealerCode(entity.getDealer() != null ? entity.getDealer().getCode() : null)
                .title(entity.getTitle())
                .message(entity.getMessage())
                .type(entity.getType())
                .typeLabel(entity.getType() != null ? entity.getType().getDisplayName() : null)
                .referenceType(entity.getReferenceType())
                .referenceId(entity.getReferenceId())
                .referenceCode(entity.getReferenceCode())
                .isRead(entity.getIsRead())
                .readAt(entity.getReadAt())
                .createdAt(entity.getCreatedAt())
                .build();
    }
}
