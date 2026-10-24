package com.quanlybanhang.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanlybanhang.config.RolePermissionConfig;
import com.quanlybanhang.config.SecurityConfig;
import com.quanlybanhang.dto.response.NotificationResponse;
import com.quanlybanhang.exception.GlobalExceptionHandler;
import com.quanlybanhang.model.*;
import com.quanlybanhang.repository.DealerRepository;
import com.quanlybanhang.repository.UserRepository;
import com.quanlybanhang.security.AuthEntryPointJwt;
import com.quanlybanhang.security.JwtAuthenticationFilter;
import com.quanlybanhang.security.RestAccessDeniedHandler;
import com.quanlybanhang.security.UserDetailsImpl;
import com.quanlybanhang.security.UserDetailsServiceImpl;
import com.quanlybanhang.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = DealerNotificationController.class)
@Import({SecurityConfig.class, RestAccessDeniedHandler.class, AuthEntryPointJwt.class, GlobalExceptionHandler.class})
@DisplayName("DealerNotificationControllerTest - REST Endpoints Thông báo Cổng Đại lý (S7-08)")
class DealerNotificationControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private NotificationService notificationService;

    @MockBean
    private UserRepository userRepository;

    @MockBean
    private DealerRepository dealerRepository;

    @MockBean
    private UserDetailsServiceImpl userDetailsService;

    @MockBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    private Dealer dealer;
    private User dealerUser;
    private UsernamePasswordAuthenticationToken dealerAuth;

    @BeforeEach
    void setUp() throws Exception {
        doAnswer(invocation -> {
            jakarta.servlet.FilterChain chain = invocation.getArgument(2);
            chain.doFilter(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(jwtAuthenticationFilter).doFilter(any(), any(), any());

        dealer = Dealer.builder()
                .id(100L)
                .code("DL-TEST")
                .name("Đại lý Minh Phát")
                .email("minhphat@example.com")
                .active(true)
                .build();

        Role roleDealer = Role.builder().name(ERole.ROLE_DEALER).build();
        dealerUser = User.builder()
                .id(1L)
                .username("dealer.demo")
                .fullName("Đại diện Đại lý")
                .dealer(dealer)
                .roles(Collections.singleton(roleDealer))
                .active(true)
                .build();

        UserDetailsImpl userDetails = UserDetailsImpl.build(dealerUser, new RolePermissionConfig());
        dealerAuth = new UsernamePasswordAuthenticationToken(
                userDetails, null, userDetails.getAuthorities());
        when(userRepository.findByUsername("dealer.demo")).thenReturn(Optional.of(dealerUser));
    }

    @Test
    @DisplayName("GET /api/portal/notifications: Đại lý xem danh sách thông báo -> 200 OK")
    void getNotifications_Returns200() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(dealerAuth);

        NotificationResponse response = NotificationResponse.builder()
                .id(1L)
                .dealerId(100L)
                .dealerCode("DL-TEST")
                .title("Nhắc hạn thanh toán HD-00000001 (còn 3 ngày)")
                .message("Vui lòng thanh toán trước ngày 26/10/2026")
                .type(NotificationType.PAYMENT_DUE_BEFORE_3_DAYS)
                .typeLabel("Nhắc hạn thanh toán trước 3 ngày")
                .referenceType("INVOICE")
                .referenceId(101L)
                .referenceCode("HD-00000001")
                .isRead(false)
                .createdAt(LocalDateTime.now())
                .build();

        when(notificationService.getDealerNotifications(eq(100L), any(), any()))
                .thenReturn(new PageImpl<>(List.of(response), PageRequest.of(0, 20), 1));

        mvc.perform(get("/api/portal/notifications")
                        .principal(dealerAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content[0].id").value(1L))
                .andExpect(jsonPath("$.data.content[0].referenceCode").value("HD-00000001"))
                .andExpect(jsonPath("$.data.content[0].type").value("PAYMENT_DUE_BEFORE_3_DAYS"));
    }

    @Test
    @DisplayName("GET /api/portal/notifications/unread-count: Đếm thông báo chưa đọc -> 200 OK")
    void getUnreadCount_Returns200() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(dealerAuth);
        when(notificationService.getUnreadCount(100L)).thenReturn(4L);

        mvc.perform(get("/api/portal/notifications/unread-count")
                        .principal(dealerAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.unreadCount").value(4));
    }

    @Test
    @DisplayName("PUT /api/portal/notifications/{id}/read: Đánh dấu 1 thông báo là đã đọc -> 200 OK")
    void markAsRead_Returns200() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(dealerAuth);

        NotificationResponse response = NotificationResponse.builder()
                .id(1L)
                .dealerId(100L)
                .title("Nhắc hạn thanh toán")
                .isRead(true)
                .readAt(LocalDateTime.now())
                .build();

        when(notificationService.markAsRead(1L, 100L)).thenReturn(response);

        mvc.perform(put("/api/portal/notifications/1/read")
                        .principal(dealerAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.isRead").value(true));
    }

    @Test
    @DisplayName("PUT /api/portal/notifications/read-all: Đánh dấu tất cả thông báo là đã đọc -> 200 OK")
    void markAllAsRead_Returns200() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(dealerAuth);
        when(notificationService.markAllAsRead(100L)).thenReturn(3);

        mvc.perform(put("/api/portal/notifications/read-all")
                        .principal(dealerAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.updatedCount").value(3));
    }

    @Test
    @DisplayName("GET /api/portal/notifications: Chặn 401 khi chưa đăng nhập")
    void getNotifications_Unauthorized_Returns401() throws Exception {
        SecurityContextHolder.clearContext();

        mvc.perform(get("/api/portal/notifications"))
                .andExpect(status().isUnauthorized());
    }
}
