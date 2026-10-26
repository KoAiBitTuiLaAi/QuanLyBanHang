package com.quanlybanhang.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanlybanhang.config.RolePermissionConfig;
import com.quanlybanhang.config.SecurityConfig;
import com.quanlybanhang.controller.FinanceController;
import com.quanlybanhang.dto.request.DealerDebtActionConfigRequest;
import com.quanlybanhang.dto.request.DebtPaymentRequest;
import com.quanlybanhang.dto.request.DebtRiskConfigRequest;
import com.quanlybanhang.dto.response.DebtEvaluationSummaryResponse;
import com.quanlybanhang.dto.response.DebtPaymentResponse;
import com.quanlybanhang.dto.response.DebtRiskConfigResponse;
import com.quanlybanhang.dto.response.FlaggedDealerResponse;
import com.quanlybanhang.exception.GlobalExceptionHandler;
import com.quanlybanhang.model.DebtFlagAction;
import com.quanlybanhang.model.DebtFlagType;
import com.quanlybanhang.repository.DealerRepository;
import com.quanlybanhang.repository.ProductRepository;
import com.quanlybanhang.service.AuditLogService;
import com.quanlybanhang.service.DealerDebtService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FinanceController.class)
@Import({SecurityConfig.class, RolePermissionConfig.class, RestAccessDeniedHandler.class, AuthEntryPointJwt.class, GlobalExceptionHandler.class, ObjectMapper.class})
@DisplayName("DebtRiskSecurityTest - Kiểm thử ma trận phân quyền cho hệ thống cảnh báo và khoá bán công nợ (S7-04)")
class DebtRiskSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DealerDebtService dealerDebtService;

    @MockBean
    private DealerRepository dealerRepository;

    @MockBean
    private AuditLogService auditLogService;

    @MockBean
    private ProductRepository productRepository;

    @MockBean
    private UserDetailsServiceImpl userDetailsService;

    @MockBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void passThrough() throws Exception {
        objectMapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        doAnswer(i -> {
            ((FilterChain) i.getArgument(2)).doFilter(i.getArgument(0), i.getArgument(1));
            return null;
        }).when(jwtAuthenticationFilter).doFilter(any(), any(), any());
    }

    @Test
    @DisplayName("Chưa xác thực -> 401 Unauthorized khi truy cập API cấu hình rủi ro")
    void unauthenticated_Returns401() throws Exception {
        mockMvc.perform(get("/api/finance/debt/config"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Tài khoản không có quyền FINANCE_VIEW_DEBT hoặc FINANCE_MANAGE -> 403 Forbidden")
    void withoutPermissions_Returns403() throws Exception {
        var salesUser = user("sales").authorities(
                new SimpleGrantedAuthority("ROLE_SALES"),
                new SimpleGrantedAuthority("ORDER_CREATE")
        );

        mockMvc.perform(get("/api/finance/debt/config").with(salesUser))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/finance/debt/flagged-dealers").with(salesUser))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/finance/debt-payments").with(salesUser))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/finance/debt/evaluate").with(salesUser))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/finance/debt-payments").with(salesUser)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dealerId\":1,\"amount\":1000}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Chỉ có quyền FINANCE_VIEW_DEBT: Xem được config, ds gắn cờ, ds thu tiền; KHÔNG THỂ sửa config, ghi thu tiền hay đánh giá lại -> 403")
    void viewerWithOnlyFinanceViewDebt_CanRead_CannotWriteOrManage() throws Exception {
        var viewer = user("viewer").authorities(
                new SimpleGrantedAuthority("ROLE_VIEWER"),
                new SimpleGrantedAuthority("FINANCE_VIEW_DEBT")
        );

        when(dealerDebtService.getDebtRiskConfig()).thenReturn(DebtRiskConfigResponse.builder().build());
        when(dealerDebtService.getFlaggedDealers()).thenReturn(List.of());
        when(dealerDebtService.getPayments(any(), any()))
                .thenReturn(new PageImpl<>(List.of(), org.springframework.data.domain.PageRequest.of(0, 20), 0));

        // Đọc dữ liệu thành công với quyền FINANCE_VIEW_DEBT
        mockMvc.perform(get("/api/finance/debt/config").with(viewer))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/finance/debt/flagged-dealers").with(viewer))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/finance/debt-payments").with(viewer))
                .andExpect(status().isOk());

        // Các hành động quản lý/ghi nhận bị chặn 403 do thiếu FINANCE_MANAGE (không fallback theo role)
        DebtRiskConfigRequest configReq = DebtRiskConfigRequest.builder()
                .defaultAction(DebtFlagAction.REQUIRE_APPROVAL)
                .build();
        mockMvc.perform(put("/api/finance/debt/config")
                        .with(viewer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(configReq)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/finance/debt/evaluate").with(viewer))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/finance/debt/1/evaluate").with(viewer))
                .andExpect(status().isForbidden());

        DebtPaymentRequest payReq = DebtPaymentRequest.builder()
                .dealerId(1L)
                .amount(new BigDecimal("10000000"))
                .build();
        mockMvc.perform(post("/api/finance/debt-payments")
                        .with(viewer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payReq)))
                .andExpect(status().isForbidden());

        DealerDebtActionConfigRequest actionReq = DealerDebtActionConfigRequest.builder()
                .action(DebtFlagAction.BLOCK)
                .build();
        mockMvc.perform(put("/api/finance/debt/1/action-config")
                        .with(viewer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(actionReq)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("ROLE_ACCOUNTANT bị thu hồi FINANCE_MANAGE -> Bị chặn 403 trên các API cập nhật và thu tiền (Không fallback vai trò)")
    void accountantRole_RevokedFinanceManage_Returns403() throws Exception {
        // Tài khoản kế toán nhưng trong ma trận quyền DB đã thu hồi FINANCE_MANAGE
        var accountantWithoutManage = user("accountant").authorities(
                new SimpleGrantedAuthority("ROLE_ACCOUNTANT"),
                new SimpleGrantedAuthority("FINANCE_VIEW_DEBT")
        );

        DebtRiskConfigRequest configReq = DebtRiskConfigRequest.builder()
                .defaultAction(DebtFlagAction.BLOCK)
                .build();
        mockMvc.perform(put("/api/finance/debt/config")
                        .with(accountantWithoutManage)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(configReq)))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/finance/debt/evaluate").with(accountantWithoutManage))
                .andExpect(status().isForbidden());

        DebtPaymentRequest payReq = DebtPaymentRequest.builder()
                .dealerId(1L)
                .amount(new BigDecimal("5000000"))
                .build();
        mockMvc.perform(post("/api/finance/debt-payments")
                        .with(accountantWithoutManage)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payReq)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Có quyền FINANCE_MANAGE -> Thao tác cập nhật cấu hình, đánh giá lại và ghi nhận thu tiền thành công")
    void withFinanceManage_Allowed() throws Exception {
        var accountantWithManage = user("accountant").authorities(
                new SimpleGrantedAuthority("ROLE_ACCOUNTANT"),
                new SimpleGrantedAuthority("FINANCE_VIEW_DEBT"),
                new SimpleGrantedAuthority("FINANCE_MANAGE")
        );

        DebtRiskConfigRequest configReq = DebtRiskConfigRequest.builder()
                .defaultAction(DebtFlagAction.REQUIRE_APPROVAL)
                .build();
        when(dealerDebtService.updateDebtRiskConfig(any(), any()))
                .thenReturn(DebtRiskConfigResponse.builder().defaultAction(DebtFlagAction.REQUIRE_APPROVAL).build());

        mockMvc.perform(put("/api/finance/debt/config")
                        .with(accountantWithManage)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(configReq)))
                .andExpect(status().isOk());

        when(dealerDebtService.evaluateAllDealersDebt())
                .thenReturn(DebtEvaluationSummaryResponse.builder().totalEvaluated(3).totalFlagged(0).build());

        mockMvc.perform(post("/api/finance/debt/evaluate").with(accountantWithManage))
                .andExpect(status().isOk());

        when(dealerDebtService.evaluateDealerDebt(1L))
                .thenReturn(FlaggedDealerResponse.builder().dealerId(1L).dealerCode("DL001").build());

        mockMvc.perform(post("/api/finance/debt/1/evaluate").with(accountantWithManage))
                .andExpect(status().isOk());

        DebtPaymentRequest payReq = DebtPaymentRequest.builder()
                .dealerId(1L)
                .amount(new BigDecimal("10000000"))
                .build();
        when(dealerDebtService.recordPayment(any(), any()))
                .thenReturn(DebtPaymentResponse.builder().paymentCode("PT-001").amount(new BigDecimal("10000000")).build());

        mockMvc.perform(post("/api/finance/debt-payments")
                        .with(accountantWithManage)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payReq)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("ROLE_ADMIN luôn có quyền truy cập tất cả API kiểm soát rủi ro công nợ")
    void admin_AlwaysAllowed() throws Exception {
        var admin = user("admin").authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));

        when(dealerDebtService.getDebtRiskConfig()).thenReturn(DebtRiskConfigResponse.builder().build());
        when(dealerDebtService.evaluateAllDealersDebt()).thenReturn(DebtEvaluationSummaryResponse.builder().build());

        mockMvc.perform(get("/api/finance/debt/config").with(admin))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/finance/debt/evaluate").with(admin))
                .andExpect(status().isOk());
    }
}
