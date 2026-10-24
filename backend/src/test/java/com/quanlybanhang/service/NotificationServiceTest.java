package com.quanlybanhang.service;

import com.quanlybanhang.dto.response.NotificationResponse;
import com.quanlybanhang.model.Dealer;
import com.quanlybanhang.model.Notification;
import com.quanlybanhang.model.NotificationType;
import com.quanlybanhang.repository.NotificationRepository;
import com.quanlybanhang.service.impl.NotificationServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationServiceTest - Quản lý thông báo trong ứng dụng cho Đại lý (S7-08)")
class NotificationServiceTest {

    @Mock
    private NotificationRepository notificationRepository;

    @InjectMocks
    private NotificationServiceImpl notificationService;

    private Dealer dealer;
    private Notification notifUnread;

    @BeforeEach
    void setUp() {
        dealer = Dealer.builder()
                .id(1L)
                .code("DL-001")
                .name("Đại lý Minh Phát")
                .build();

        notifUnread = Notification.builder()
                .id(10L)
                .dealer(dealer)
                .title("Nhắc hạn thanh toán HD-00000001")
                .message("Hạn trả ngày 26/10/2026")
                .type(NotificationType.PAYMENT_DUE_BEFORE_3_DAYS)
                .referenceType("INVOICE")
                .referenceId(101L)
                .referenceCode("HD-00000001")
                .isRead(false)
                .createdAt(LocalDateTime.now())
                .build();
    }

    @Test
    @DisplayName("Tạo thông báo trong ứng dụng thành công")
    void testSendInAppNotificationSuccess() {
        when(notificationRepository.save(any(Notification.class))).thenAnswer(i -> {
            Notification n = i.getArgument(0);
            n.setId(10L);
            return n;
        });

        Notification result = notificationService.sendInAppNotification(
                dealer,
                "Nhắc hạn thanh toán HD-00000001",
                "Hạn trả ngày 26/10/2026",
                NotificationType.PAYMENT_DUE_BEFORE_3_DAYS,
                "INVOICE",
                101L,
                "HD-00000001"
        );

        assertNotNull(result);
        assertEquals(10L, result.getId());
        assertEquals("Nhắc hạn thanh toán HD-00000001", result.getTitle());
        assertFalse(result.getIsRead());
        verify(notificationRepository, times(1)).save(any(Notification.class));
    }

    @Test
    @DisplayName("Lấy danh sách thông báo của đại lý có phân trang")
    void testGetDealerNotifications() {
        Page<Notification> page = new PageImpl<>(List.of(notifUnread));
        when(notificationRepository.findByDealerIdOrderByCreatedAtDesc(eq(1L), any(PageRequest.class)))
                .thenReturn(page);

        Page<NotificationResponse> result = notificationService.getDealerNotifications(1L, false, PageRequest.of(0, 10));

        assertEquals(1, result.getTotalElements());
        assertEquals(10L, result.getContent().get(0).getId());
        assertFalse(result.getContent().get(0).getIsRead());
    }

    @Test
    @DisplayName("Lọc danh sách chỉ các thông báo chưa đọc")
    void testGetDealerNotificationsUnreadOnly() {
        Page<Notification> page = new PageImpl<>(List.of(notifUnread));
        when(notificationRepository.findByDealerIdAndIsReadFalseOrderByCreatedAtDesc(eq(1L), any(PageRequest.class)))
                .thenReturn(page);

        Page<NotificationResponse> result = notificationService.getDealerNotifications(1L, true, PageRequest.of(0, 10));

        assertEquals(1, result.getTotalElements());
        verify(notificationRepository).findByDealerIdAndIsReadFalseOrderByCreatedAtDesc(eq(1L), any(PageRequest.class));
    }

    @Test
    @DisplayName("Đếm số lượng thông báo chưa đọc")
    void testGetUnreadCount() {
        when(notificationRepository.countByDealerIdAndIsReadFalse(1L)).thenReturn(5L);

        long count = notificationService.getUnreadCount(1L);

        assertEquals(5L, count);
    }

    @Test
    @DisplayName("Đánh dấu một thông báo là đã đọc")
    void testMarkAsReadSuccess() {
        when(notificationRepository.findById(10L)).thenReturn(Optional.of(notifUnread));
        when(notificationRepository.save(any(Notification.class))).thenAnswer(i -> i.getArgument(0));

        NotificationResponse response = notificationService.markAsRead(10L, 1L);

        assertTrue(response.getIsRead());
        assertNotNull(response.getReadAt());
        verify(notificationRepository).save(any(Notification.class));
    }

    @Test
    @DisplayName("Chặn khi đại lý cố tình đánh dấu thông báo của đại lý khác")
    void testMarkAsReadOtherDealerForbidden() {
        when(notificationRepository.findById(10L)).thenReturn(Optional.of(notifUnread));

        assertThrows(ResponseStatusException.class, () -> notificationService.markAsRead(10L, 999L));
        verify(notificationRepository, never()).save(any());
    }

    @Test
    @DisplayName("Đánh dấu toàn bộ thông báo của đại lý là đã đọc")
    void testMarkAllAsRead() {
        when(notificationRepository.markAllAsReadByDealerId(eq(1L), any(LocalDateTime.class))).thenReturn(3);

        int updated = notificationService.markAllAsRead(1L);

        assertEquals(3, updated);
        verify(notificationRepository).markAllAsReadByDealerId(eq(1L), any(LocalDateTime.class));
    }
}
