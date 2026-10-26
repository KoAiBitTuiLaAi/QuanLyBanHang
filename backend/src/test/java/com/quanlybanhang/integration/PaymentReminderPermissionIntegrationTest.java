package com.quanlybanhang.integration;

import com.quanlybanhang.config.RolePermissionConfig;
import com.quanlybanhang.dto.response.PaymentReminderSummaryResponse;
import com.quanlybanhang.model.*;
import com.quanlybanhang.repository.*;
import com.quanlybanhang.security.JwtUtils;
import com.quanlybanhang.service.PaymentReminderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.HttpHeaders;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real JWT/filter/database permission checks; reminder delivery is mocked to avoid outbound mail. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:payment_reminder_permissions;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.show-sql=false", "app.jwt.secret=payment-reminder-permission-fixture-secret-32-characters",
        "app.bootstrap.admin-password=ReminderFixture2026!", "app.bootstrap.admin-email=reminder@example.invalid",
        "app.seed-demo-users=false", "app.seed-demo-data=false", "app.payment-reminder.scheduler.enabled=false"
})
@AutoConfigureMockMvc
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PaymentReminderPermissionIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired RolePermissionConfig permissions;
    @Autowired DealerRepository dealers;
    @Autowired NotificationRepository notifications;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired JwtUtils jwt;
    @MockBean PaymentReminderService reminders;

    private Dealer dealer;
    private Dealer otherDealer;
    private Long notificationId;
    private String dealerToken;
    private String accountantToken;
    private String adminToken;

    @BeforeEach void setUp() {
        permissions.resetToDefault();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        dealer = dealers.saveAndFlush(Dealer.builder().code("S708-A-" + suffix).name("Đại lý của tôi").active(true).build());
        otherDealer = dealers.saveAndFlush(Dealer.builder().code("S708-B-" + suffix).name("Đại lý khác").active(true).build());
        notificationId = notifications.saveAndFlush(Notification.builder().dealer(dealer).title("Nhắc hạn của tôi")
                .message("Hóa đơn đến hạn sau 3 ngày").type(NotificationType.PAYMENT_DUE_BEFORE_3_DAYS).build()).getId();
        notifications.saveAndFlush(Notification.builder().dealer(otherDealer).title("Thông báo đại lý khác")
                .message("Nội dung riêng").type(NotificationType.PAYMENT_OVERDUE).build());
        dealerToken = token("dealer-" + suffix, ERole.ROLE_DEALER, dealer);
        accountantToken = token("accountant-" + suffix, ERole.ROLE_ACCOUNTANT, null);
        adminToken = token("admin-" + suffix, ERole.ROLE_ADMIN, null);
        when(reminders.processPaymentReminders(any(LocalDate.class))).thenAnswer(invocation ->
                PaymentReminderSummaryResponse.builder().targetDate(invocation.getArgument(0)).build());
        when(reminders.getReminderLogs(any(), any(), any(), any())).thenAnswer(invocation ->
                new PageImpl<>(List.of(), invocation.getArgument(3), 0));
    }

    private String token(String username, ERole role, Dealer owner) {
        var user = users.saveAndFlush(User.builder().username(username).email(username + "@example.invalid")
                .password("unused-fixture-password").active(true).tokenVersion(0L).dealer(owner)
                .roles(new HashSet<>(Set.of(roles.findByName(role).orElseThrow()))).build());
        return jwt.generateAccessToken(user.getUsername(), user.getTokenVersion());
    }

    @Test void notificationPermissionCanBeGrantedRevokedAndRegrantedOnSameJwt() throws Exception {
        permissions.updateRolePermissions(ERole.ROLE_DEALER, Set.of());
        assertNotificationAccessForbidden();
        permissions.updateRolePermissions(ERole.ROLE_DEALER, Set.of(Permission.NOTIFICATION_VIEW));
        mvc.perform(get("/api/portal/notifications").header(HttpHeaders.AUTHORIZATION, "Bearer " + dealerToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].title").value("Nhắc hạn của tôi"));
        mvc.perform(get("/api/portal/notifications").param("dealerId", otherDealer.getId().toString())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + dealerToken)).andExpect(status().isForbidden());
        mvc.perform(put("/api/portal/notifications/" + notificationId + "/read")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + dealerToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.isRead").value(true));
        permissions.updateRolePermissions(ERole.ROLE_DEALER, Set.of());
        assertNotificationAccessForbidden();
        permissions.updateRolePermissions(ERole.ROLE_DEALER, Set.of(Permission.NOTIFICATION_VIEW));
        mvc.perform(get("/api/portal/notifications/unread-count").header(HttpHeaders.AUTHORIZATION, "Bearer " + dealerToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.unreadCount").value(0));
        mvc.perform(put("/api/portal/notifications/read-all").header(HttpHeaders.AUTHORIZATION, "Bearer " + dealerToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.updatedCount").value(0));
    }

    private void assertNotificationAccessForbidden() throws Exception {
        for (String path : List.of("/api/portal/notifications", "/api/portal/notifications/unread-count"))
            mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + dealerToken)).andExpect(status().isForbidden());
        for (String path : List.of("/api/portal/notifications/" + notificationId + "/read", "/api/portal/notifications/read-all"))
            mvc.perform(put(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + dealerToken)).andExpect(status().isForbidden());
    }

    @Test void financePermissionsRemainIndependentAndRevokeOnSameJwt() throws Exception {
        permissions.updateRolePermissions(ERole.ROLE_ACCOUNTANT, Set.of(Permission.FINANCE_MANAGE));
        mvc.perform(post("/api/finance/payment-reminders/trigger").param("targetDate", "2026-10-26")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accountantToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.targetDate").value("2026-10-26"));
        mvc.perform(get("/api/finance/payment-reminders/logs").header(HttpHeaders.AUTHORIZATION, "Bearer " + accountantToken))
                .andExpect(status().isForbidden());
        permissions.updateRolePermissions(ERole.ROLE_ACCOUNTANT, Set.of(Permission.FINANCE_VIEW_DEBT));
        mvc.perform(post("/api/finance/payment-reminders/trigger").header(HttpHeaders.AUTHORIZATION, "Bearer " + accountantToken))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/finance/payment-reminders/logs").header(HttpHeaders.AUTHORIZATION, "Bearer " + accountantToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(0));
        permissions.updateRolePermissions(ERole.ROLE_ACCOUNTANT, Set.of());
        mvc.perform(get("/api/finance/payment-reminders/logs").header(HttpHeaders.AUTHORIZATION, "Bearer " + accountantToken))
                .andExpect(status().isForbidden());
    }

    @Test void adminCanSelectDealerAndUseFinanceEndpointsWithoutDealerLink() throws Exception {
        permissions.updateRolePermissions(ERole.ROLE_ADMIN, Set.of());
        mvc.perform(get("/api/portal/notifications").param("dealerId", dealer.getId().toString())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1));
        mvc.perform(get("/api/portal/notifications").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/finance/payment-reminders/trigger").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());
        mvc.perform(get("/api/finance/payment-reminders/logs").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    @Test void internalAccountCannotUseDealerNotificationScopeEvenWithLinkedDealerAndPermission() throws Exception {
        permissions.updateRolePermissions(ERole.ROLE_ACCOUNTANT, Set.of(Permission.NOTIFICATION_VIEW));
        String staffToken = token("staff-" + UUID.randomUUID().toString().substring(0, 8), ERole.ROLE_ACCOUNTANT, dealer);
        mvc.perform(get("/api/portal/notifications").header(HttpHeaders.AUTHORIZATION, "Bearer " + staffToken))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/portal/notifications/" + notificationId + "/read")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + staffToken)).andExpect(status().isForbidden());
    }
}
