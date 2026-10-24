package com.quanlybanhang.service;

import com.quanlybanhang.dto.response.NotificationResponse;
import com.quanlybanhang.model.Dealer;
import com.quanlybanhang.model.Notification;
import com.quanlybanhang.model.NotificationType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface NotificationService {

    /**
     * Tạo và lưu thông báo mới trong ứng dụng cho đại lý (S7-08).
     */
    Notification sendInAppNotification(Dealer dealer, String title, String message,
                                       NotificationType type, String refType, Long refId, String refCode);

    /**
     * Lấy danh sách thông báo của đại lý (có phân trang, có thể lọc theo chưa đọc).
     */
    Page<NotificationResponse> getDealerNotifications(Long dealerId, Boolean unreadOnly, Pageable pageable);

    /**
     * Đếm số lượng thông báo chưa đọc của đại lý.
     */
    long getUnreadCount(Long dealerId);

    /**
     * Đánh dấu một thông báo là đã đọc.
     */
    NotificationResponse markAsRead(Long notificationId, Long dealerId);

    /**
     * Đánh dấu toàn bộ thông báo của đại lý là đã đọc.
     */
    int markAllAsRead(Long dealerId);
}
