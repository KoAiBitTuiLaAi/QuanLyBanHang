package com.quanlybanhang.controller;

import com.quanlybanhang.dto.response.ApiResponse;
import com.quanlybanhang.dto.response.NotificationResponse;
import com.quanlybanhang.model.Dealer;
import com.quanlybanhang.model.ERole;
import com.quanlybanhang.model.User;
import com.quanlybanhang.repository.DealerRepository;
import com.quanlybanhang.repository.UserRepository;
import com.quanlybanhang.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.Map;

/**
 * Controller Thông báo dành cho Cổng Đại lý (S7-08).
 *
 * Tính năng chính:
 * - Xem danh sách thông báo của đại lý (GET /api/portal/notifications)
 * - Đếm số lượng thông báo chưa đọc để hiển thị badge (GET /api/portal/notifications/unread-count)
 * - Đánh dấu 1 thông báo là đã đọc (PUT /api/portal/notifications/{id}/read)
 * - Đánh dấu toàn bộ thông báo là đã đọc (PUT /api/portal/notifications/read-all)
 *
 * Phân quyền ma trận database-backed: bắt buộc quyền NOTIFICATION_VIEW hoặc vai trò ADMIN.
 */
@RestController
@RequestMapping("/api/portal/notifications")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('DEALER', 'ADMIN')")
public class DealerNotificationController {

    private final NotificationService notificationService;
    private final UserRepository userRepository;
    private final DealerRepository dealerRepository;

    private Dealer resolveAuthenticatedDealer(Principal principal, Long requestedDealerId) {
        if (principal == null) {
            throw new AccessDeniedException("Yêu cầu đăng nhập");
        }
        User currentUser = userRepository.findByUsername(principal.getName())
                .orElseThrow(() -> new AccessDeniedException("Không tìm thấy thông tin tài khoản: " + principal.getName()));

        boolean isAdmin = currentUser.getRoles().stream()
                .anyMatch(r -> r.getName() == ERole.ROLE_ADMIN);

        if (isAdmin && requestedDealerId != null) {
            return dealerRepository.findById(requestedDealerId)
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đại lý được chọn"));
        }

        if (currentUser.getDealer() == null) {
            if (isAdmin) {
                throw new IllegalArgumentException("Vui lòng chọn đại lý để xem thông báo cổng đại lý");
            }
            throw new AccessDeniedException("Tài khoản chưa được liên kết với hồ sơ đại lý nào");
        }
        if (requestedDealerId != null && !requestedDealerId.equals(currentUser.getDealer().getId())) {
            throw new AccessDeniedException("Bạn chỉ có quyền truy cập dữ liệu của chính đại lý mình");
        }
        return currentUser.getDealer();
    }

    /**
     * Danh sách thông báo của đại lý (có phân trang, có thể lọc chưa đọc).
     */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('NOTIFICATION_VIEW')")
    public ResponseEntity<ApiResponse> getNotifications(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Boolean unreadOnly,
            @RequestParam(required = false) Long dealerId,
            Principal principal) {
        Dealer dealer = resolveAuthenticatedDealer(principal, dealerId);
        Page<NotificationResponse> result = notificationService.getDealerNotifications(
                dealer.getId(), unreadOnly, PageRequest.of(page, size));
        return ResponseEntity.ok(ApiResponse.success("Danh sách thông báo của đại lý", result));
    }

    /**
     * Số lượng thông báo chưa đọc của đại lý.
     */
    @GetMapping("/unread-count")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('NOTIFICATION_VIEW')")
    public ResponseEntity<ApiResponse> getUnreadCount(
            @RequestParam(required = false) Long dealerId,
            Principal principal) {
        Dealer dealer = resolveAuthenticatedDealer(principal, dealerId);
        long count = notificationService.getUnreadCount(dealer.getId());
        return ResponseEntity.ok(ApiResponse.success("Số lượng thông báo chưa đọc", Map.of("unreadCount", count)));
    }

    /**
     * Đánh dấu một thông báo là đã đọc.
     */
    @PutMapping("/{id}/read")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('NOTIFICATION_VIEW')")
    public ResponseEntity<ApiResponse> markAsRead(
            @PathVariable Long id,
            @RequestParam(required = false) Long dealerId,
            Principal principal) {
        Dealer dealer = resolveAuthenticatedDealer(principal, dealerId);
        NotificationResponse response = notificationService.markAsRead(id, dealer.getId());
        return ResponseEntity.ok(ApiResponse.success("Đã đánh dấu thông báo là đã đọc", response));
    }

    /**
     * Đánh dấu toàn bộ thông báo của đại lý là đã đọc.
     */
    @PutMapping("/read-all")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('NOTIFICATION_VIEW')")
    public ResponseEntity<ApiResponse> markAllAsRead(
            @RequestParam(required = false) Long dealerId,
            Principal principal) {
        Dealer dealer = resolveAuthenticatedDealer(principal, dealerId);
        int count = notificationService.markAllAsRead(dealer.getId());
        return ResponseEntity.ok(ApiResponse.success("Đã đánh dấu toàn bộ thông báo là đã đọc", Map.of("updatedCount", count)));
    }
}
