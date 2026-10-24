package com.quanlybanhang.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanlybanhang.config.RolePermissionConfig;
import com.quanlybanhang.config.SecurityConfig;
import com.quanlybanhang.dto.response.PaymentReminderLogResponse;
import com.quanlybanhang.dto.response.PaymentReminderSummaryResponse;
import com.quanlybanhang.exception.GlobalExceptionHandler;
import com.quanlybanhang.model.ERole;
import com.quanlybanhang.model.PaymentReminderType;
import com.quanlybanhang.model.Role;
import com.quanlybanhang.model.User;
import com.quanlybanhang.repository.UserRepository;
import com.quanlybanhang.security.AuthEntryPointJwt;
import com.quanlybanhang.security.JwtAuthenticationFilter;
import com.quanlybanhang.security.RestAccessDeniedHandler;
import com.quanlybanhang.security.UserDetailsImpl;
import com.quanlybanhang.security.UserDetailsServiceImpl;
import com.quanlybanhang.service.PaymentReminderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = PaymentReminderController.class)
@Import({SecurityConfig.class, RestAccessDeniedHandler.class, AuthEntryPointJwt.class, GlobalExceptionHandler.class})
@DisplayName("PaymentReminderControllerTest - REST Endpoints Quản lý Nhắc hạn thanh toán (S7-08)")
class PaymentReminderControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private PaymentReminderService paymentReminderService;

    @MockBean
    private UserRepository userRepository;

    @MockBean
    private UserDetailsServiceImpl userDetailsService;

    @MockBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    private UsernamePasswordAuthenticationToken adminAuth;
    private UsernamePasswordAuthenticationToken accountantAuth;

    @BeforeEach
    void setUp() throws Exception {
        doAnswer(invocation -> {
            jakarta.servlet.FilterChain chain = invocation.getArgument(2);
            chain.doFilter(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(jwtAuthenticationFilter).doFilter(any(), any(), any());

        Role adminRole = Role.builder().name(ERole.ROLE_ADMIN).build();
        User adminUser = User.builder()
                .id(1L)
                .username("admin")
                .roles(Collections.singleton(adminRole))
                .active(true)
                .build();
        UserDetailsImpl adminDetails = UserDetailsImpl.build(adminUser, new RolePermissionConfig());
        adminAuth = new UsernamePasswordAuthenticationToken(adminDetails, null, adminDetails.getAuthorities());

        Role accountantRole = Role.builder().name(ERole.ROLE_ACCOUNTANT).build();
        User accountantUser = User.builder()
                .id(2L)
                .username("accountant")
                .roles(Collections.singleton(accountantRole))
                .active(true)
                .build();
        UserDetailsImpl accountantDetails = UserDetailsImpl.build(accountantUser, new RolePermissionConfig());
        accountantAuth = new UsernamePasswordAuthenticationToken(accountantDetails, null, accountantDetails.getAuthorities());
    }

    @Test
    @DisplayName("POST /api/finance/payment-reminders/trigger: Kích hoạt quét nhắc hạn thủ công -> 200 OK")
    void triggerReminders_Returns200() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(accountantAuth);

        PaymentReminderSummaryResponse summary = PaymentReminderSummaryResponse.builder()
                .targetDate(LocalDate.now())
                .totalInvoicesScanned(5)
                .beforeDueRemindersSent(2)
                .overdueRemindersSent(1)
                .skipped(2)
                .message("Thành công")
                .build();

        when(paymentReminderService.processPaymentReminders(any(LocalDate.class))).thenReturn(summary);

        mvc.perform(post("/api/finance/payment-reminders/trigger")
                        .principal(accountantAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.beforeDueRemindersSent").value(2))
                .andExpect(jsonPath("$.data.overdueRemindersSent").value(1));
    }

    @Test
    @DisplayName("GET /api/finance/payment-reminders/logs: Xem lịch sử nhật ký nhắc hạn -> 200 OK")
    void getReminderLogs_Returns200() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(accountantAuth);

        PaymentReminderLogResponse logResponse = PaymentReminderLogResponse.builder()
                .id(1L)
                .invoiceId(101L)
                .invoiceCode("HD-00000001")
                .dealerId(10L)
                .dealerCode("DL-001")
                .dealerName("Đại lý Minh Phát")
                .reminderDate(LocalDate.now())
                .reminderType(PaymentReminderType.BEFORE_DUE_3_DAYS)
                .reminderTypeLabel("Nhắc trước hạn 3 ngày")
                .daysCount(3)
                .amount(new BigDecimal("50000000"))
                .emailSent(true)
                .inAppNotificationSent(true)
                .sentAt(LocalDateTime.now())
                .build();

        when(paymentReminderService.getReminderLogs(any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(logResponse), PageRequest.of(0, 20), 1));

        mvc.perform(get("/api/finance/payment-reminders/logs")
                        .principal(accountantAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content[0].invoiceCode").value("HD-00000001"))
                .andExpect(jsonPath("$.data.content[0].daysCount").value(3));
    }
}
