package com.quanlybanhang.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanlybanhang.config.RolePermissionConfig;
import com.quanlybanhang.config.SecurityConfig;
import com.quanlybanhang.controller.DealerNotificationController;
import com.quanlybanhang.controller.PaymentReminderController;
import com.quanlybanhang.dto.response.PaymentReminderSummaryResponse;
import com.quanlybanhang.exception.GlobalExceptionHandler;
import com.quanlybanhang.model.Dealer;
import com.quanlybanhang.model.ERole;
import com.quanlybanhang.model.Role;
import com.quanlybanhang.model.User;
import com.quanlybanhang.repository.DealerRepository;
import com.quanlybanhang.repository.UserRepository;
import com.quanlybanhang.service.NotificationService;
import com.quanlybanhang.service.PaymentReminderService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({DealerNotificationController.class, PaymentReminderController.class})
@Import({SecurityConfig.class, RolePermissionConfig.class, RestAccessDeniedHandler.class, AuthEntryPointJwt.class, GlobalExceptionHandler.class, ObjectMapper.class})
@DisplayName("PaymentReminderSecurityTest - Kiểm thử ma trận phân quyền cho tính năng Nhắc hạn thanh toán (S7-08)")
class PaymentReminderSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private NotificationService notificationService;

    @MockBean
    private PaymentReminderService paymentReminderService;

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

    @BeforeEach
    void setUp() throws Exception {
        doAnswer(i -> {
            ((FilterChain) i.getArgument(2)).doFilter(i.getArgument(0), i.getArgument(1));
            return null;
        }).when(jwtAuthenticationFilter).doFilter(any(), any(), any());

        dealer = Dealer.builder()
                .id(100L)
                .code("DL-001")
                .name("Đại lý Minh Phát")
                .active(true)
                .build();

        dealerUser = User.builder()
                .id(10L)
                .username("dealer.user")
                .dealer(dealer)
                .roles(Collections.singleton(Role.builder().name(ERole.ROLE_DEALER).build()))
                .active(true)
                .build();

        when(userRepository.findByUsername("dealer.user")).thenReturn(Optional.of(dealerUser));
        when(dealerRepository.findById(100L)).thenReturn(Optional.of(dealer));
    }

    // ──────────────────────── PORTAL NOTIFICATIONS ────────────────────────

    @Test
    @DisplayName("Chưa xác thực -> 401 Unauthorized khi truy cập danh sách thông báo")
    void unauthenticated_Returns401() throws Exception {
        mockMvc.perform(get("/api/portal/notifications"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("AGENTS.md: Tài khoản ROLE_DEALER nhưng bị thu hồi quyền NOTIFICATION_VIEW -> 403 Forbidden (Không fallback role)")
    void dealerWithoutNotificationView_Returns403() throws Exception {
        var dealerWithoutPerm = user("dealer.user").authorities(
                new SimpleGrantedAuthority("ROLE_DEALER"),
                new SimpleGrantedAuthority("ORDER_VIEW")
                // thiếu NOTIFICATION_VIEW
        );

        mockMvc.perform(get("/api/portal/notifications").with(dealerWithoutPerm))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Tài khoản có quyền NOTIFICATION_VIEW -> 200 OK")
    void dealerWithNotificationView_Returns200() throws Exception {
        var dealerWithPerm = user("dealer.user").authorities(
                new SimpleGrantedAuthority("ROLE_DEALER"),
                new SimpleGrantedAuthority("NOTIFICATION_VIEW")
        );

        when(notificationService.getDealerNotifications(eq(100L), any(), any()))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/portal/notifications").with(dealerWithPerm))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("AGENTS.md: ROLE_ADMIN luôn có toàn quyền truy cập thông báo -> 200 OK")
    void admin_AlwaysAllowed_Returns200() throws Exception {
        var admin = user("admin").authorities(
                new SimpleGrantedAuthority("ROLE_ADMIN")
        );

        when(notificationService.getDealerNotifications(eq(100L), any(), any()))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/portal/notifications?dealerId=100").with(admin))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Đánh dấu đã đọc thông báo: thiếu NOTIFICATION_VIEW -> 403 Forbidden")
    void markAsRead_WithoutPermission_Returns403() throws Exception {
        var dealerWithoutPerm = user("dealer.user").authorities(
                new SimpleGrantedAuthority("ROLE_DEALER"),
                new SimpleGrantedAuthority("ORDER_VIEW")
        );

        mockMvc.perform(put("/api/portal/notifications/1/read").with(dealerWithoutPerm))
                .andExpect(status().isForbidden());
    }

    // ──────────────────────── FINANCE REMINDERS TRIGGER & LOGS ────────────────────────

    @Test
    @DisplayName("POST /api/finance/payment-reminders/trigger: Thiếu FINANCE_MANAGE -> 403 Forbidden")
    void triggerReminders_WithoutFinanceManage_Returns403() throws Exception {
        var userWithoutPerm = user("viewer").authorities(
                new SimpleGrantedAuthority("ROLE_VIEWER"),
                new SimpleGrantedAuthority("FINANCE_VIEW_DEBT")
        );

        mockMvc.perform(post("/api/finance/payment-reminders/trigger").with(userWithoutPerm))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/finance/payment-reminders/trigger: Có FINANCE_MANAGE -> 200 OK")
    void triggerReminders_WithFinanceManage_Returns200() throws Exception {
        var accountant = user("accountant").authorities(
                new SimpleGrantedAuthority("ROLE_ACCOUNTANT"),
                new SimpleGrantedAuthority("FINANCE_MANAGE")
        );

        when(paymentReminderService.processPaymentReminders(any(LocalDate.class)))
                .thenReturn(PaymentReminderSummaryResponse.builder().targetDate(LocalDate.now()).build());

        mockMvc.perform(post("/api/finance/payment-reminders/trigger").with(accountant))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /api/finance/payment-reminders/logs: Thiếu FINANCE_VIEW_DEBT -> 403 Forbidden")
    void getReminderLogs_WithoutFinanceViewDebt_Returns403() throws Exception {
        var salesUser = user("sales").authorities(
                new SimpleGrantedAuthority("ROLE_SALES"),
                new SimpleGrantedAuthority("ORDER_VIEW")
        );

        mockMvc.perform(get("/api/finance/payment-reminders/logs").with(salesUser))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/finance/payment-reminders/logs: Có FINANCE_VIEW_DEBT -> 200 OK")
    void getReminderLogs_WithFinanceViewDebt_Returns200() throws Exception {
        var accountant = user("accountant").authorities(
                new SimpleGrantedAuthority("ROLE_ACCOUNTANT"),
                new SimpleGrantedAuthority("FINANCE_VIEW_DEBT")
        );

        when(paymentReminderService.getReminderLogs(any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/finance/payment-reminders/logs").with(accountant))
                .andExpect(status().isOk());
    }
}
