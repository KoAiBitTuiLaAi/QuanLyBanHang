package com.quanlybanhang.service.impl;

import com.quanlybanhang.dto.response.NotificationResponse;
import com.quanlybanhang.model.Dealer;
import com.quanlybanhang.model.Notification;
import com.quanlybanhang.model.NotificationType;
import com.quanlybanhang.repository.NotificationRepository;
import com.quanlybanhang.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private final NotificationRepository notificationRepository;

    @Override
    @Transactional
    public Notification sendInAppNotification(Dealer dealer, String title, String message,
                                               NotificationType type, String refType, Long refId, String refCode) {
        if (dealer == null) {
            throw new IllegalArgumentException("Đại lý không được để trống khi gửi thông báo");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Tiêu đề thông báo không được để trống");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("Nội dung thông báo không được để trống");
        }

        Notification notification = Notification.builder()
                .dealer(dealer)
                .title(title.trim())
                .message(message.trim())
                .type(type != null ? type : NotificationType.SYSTEM)
                .referenceType(refType)
                .referenceId(refId)
                .referenceCode(refCode)
                .isRead(false)
                .createdAt(LocalDateTime.now())
                .build();

        Notification saved = notificationRepository.save(notification);
        log.info("Đã tạo thông báo trong ứng dụng id={} cho đại lý {} ({}): {}",
                saved.getId(), dealer.getCode(), dealer.getName(), title);
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<NotificationResponse> getDealerNotifications(Long dealerId, Boolean unreadOnly, Pageable pageable) {
        if (dealerId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ID đại lý không được để trống");
        }

        Page<Notification> page;
        if (Boolean.TRUE.equals(unreadOnly)) {
            page = notificationRepository.findByDealerIdAndIsReadFalseOrderByCreatedAtDesc(dealerId, pageable);
        } else {
            page = notificationRepository.findByDealerIdOrderByCreatedAtDesc(dealerId, pageable);
        }

        return page.map(NotificationResponse::fromEntity);
    }

    @Override
    @Transactional(readOnly = true)
    public long getUnreadCount(Long dealerId) {
        if (dealerId == null) {
            return 0;
        }
        return notificationRepository.countByDealerIdAndIsReadFalse(dealerId);
    }

    @Override
    @Transactional
    public NotificationResponse markAsRead(Long notificationId, Long dealerId) {
        if (notificationId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ID thông báo không hợp lệ");
        }

        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy thông báo với ID: " + notificationId));

        if (dealerId != null && (notification.getDealer() == null || !dealerId.equals(notification.getDealer().getId()))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Bạn không có quyền chỉnh sửa thông báo của đại lý khác");
        }

        if (!Boolean.TRUE.equals(notification.getIsRead())) {
            notification.setIsRead(true);
            notification.setReadAt(LocalDateTime.now());
            notificationRepository.save(notification);
        }

        return NotificationResponse.fromEntity(notification);
    }

    @Override
    @Transactional
    public int markAllAsRead(Long dealerId) {
        if (dealerId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ID đại lý không hợp lệ");
        }
        return notificationRepository.markAllAsReadByDealerId(dealerId, LocalDateTime.now());
    }
}
