package com.quanlybanhang.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanlybanhang.config.RolePermissionConfig;
import com.quanlybanhang.config.SecurityConfig;
import com.quanlybanhang.controller.SalesTargetController;
import com.quanlybanhang.dto.response.MySalesTargetProgressResponse;
import com.quanlybanhang.dto.response.SalesTargetOptionsResponse;
import com.quanlybanhang.dto.response.SalesTargetResponse;
import com.quanlybanhang.exception.GlobalExceptionHandler;
import com.quanlybanhang.service.SalesTargetService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SalesTargetController.class)
@Import({SecurityConfig.class, RolePermissionConfig.class, RestAccessDeniedHandler.class, AuthEntryPointJwt.class, GlobalExceptionHandler.class, ObjectMapper.class})
@DisplayName("SalesTargetSecurityTest - Kiểm thử phân quyền ma trận cho Chỉ tiêu doanh số (S8-04)")
class SalesTargetSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SalesTargetService salesTargetService;

    @MockBean
    private UserDetailsServiceImpl userDetailsService;

    @MockBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @BeforeEach
    void passThrough() throws Exception {
        doAnswer(i -> {
            ((FilterChain) i.getArgument(2)).doFilter(i.getArgument(0), i.getArgument(1));
            return null;
        }).when(jwtAuthenticationFilter).doFilter(any(), any(), any());
    }

    @Test
    @DisplayName("Chưa đăng nhập -> 401 Unauthorized")
    void unauthenticated_Returns401() throws Exception {
        mockMvc.perform(get("/api/sales-targets"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/sales-targets/my-progress"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Có ROLE_SALES_MANAGER nhưng bị thu hồi quyền REPORT_SALES và ORDER_APPROVE -> 403 Forbidden (không role fallback)")
    void roleWithoutPermission_Returns403() throws Exception {
        var strippedManager = user("manager_stripped").authorities(
                new SimpleGrantedAuthority("ROLE_SALES_MANAGER")
        );

        mockMvc.perform(get("/api/sales-targets").with(strippedManager))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/sales-targets")
                        .with(strippedManager)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"year\":2026,\"month\":10,\"userId\":1,\"targetAmount\":50000000}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Nhân viên có REPORT_SALES: Được xem chỉ tiêu & tiến độ cá nhân (200), nhưng KHÔNG ĐƯỢC đặt/sửa/khóa/xóa chỉ tiêu (403)")
    void salesWithReportSales_CanView_CannotManage() throws Exception {
        var salesUser = user("sales01").authorities(
                new SimpleGrantedAuthority("ROLE_SALES"),
                new SimpleGrantedAuthority("REPORT_SALES")
        );

        when(salesTargetService.getOptions()).thenReturn(SalesTargetOptionsResponse.builder().build());
        when(salesTargetService.getMyProgress(any(), any(), any(), eq("sales01")))
                .thenReturn(MySalesTargetProgressResponse.builder().username("sales01").build());
        when(salesTargetService.searchTargets(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/sales-targets/options").with(salesUser))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/sales-targets/my-progress").with(salesUser))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/sales-targets").with(salesUser))
                .andExpect(status().isOk());

        // Thử đặt/cập nhật/khóa/xóa chỉ tiêu -> 403 Forbidden
        mockMvc.perform(post("/api/sales-targets")
                        .with(salesUser)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"year\":2026,\"month\":10,\"userId\":1,\"targetAmount\":50000000}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/sales-targets/1")
                        .with(salesUser)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetAmount\":60000000,\"reason\":\"Test\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/sales-targets/1/lock").with(salesUser))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/sales-targets/1").with(salesUser))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Quản lý kinh doanh có ORDER_APPROVE: Được phép đặt, cập nhật, khóa chỉ tiêu -> 201/200")
    void managerWithOrderApprove_CanCreateUpdateAndLock() throws Exception {
        var managerUser = user("manager01").authorities(
                new SimpleGrantedAuthority("ROLE_SALES_MANAGER"),
                new SimpleGrantedAuthority("ORDER_APPROVE"),
                new SimpleGrantedAuthority("REPORT_SALES")
        );

        SalesTargetResponse stubResponse = SalesTargetResponse.builder()
                .id(1L)
                .year(2026)
                .month(10)
                .targetAmount(new BigDecimal("100000000"))
                .build();

        when(salesTargetService.createTarget(any(), eq("manager01"))).thenReturn(stubResponse);
        when(salesTargetService.updateTarget(eq(1L), any(), eq("manager01"))).thenReturn(stubResponse);
        when(salesTargetService.lockTarget(eq(1L), any(), eq("manager01"))).thenReturn(stubResponse);

        mockMvc.perform(post("/api/sales-targets")
                        .with(managerUser)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"year\":2026,\"month\":10,\"userId\":1,\"targetAmount\":100000000}"))
                .andExpect(status().isCreated());

        mockMvc.perform(put("/api/sales-targets/1")
                        .with(managerUser)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetAmount\":120000000,\"reason\":\"Điều chỉnh chỉ tiêu\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/sales-targets/1/lock")
                        .with(managerUser)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Chốt kỳ\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("ROLE_ADMIN luôn có toàn quyền truy cập và thao tác trên mọi endpoint S8-04")
    void admin_AlwaysAllowed() throws Exception {
        var adminUser = user("admin").authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));

        SalesTargetResponse stubResponse = SalesTargetResponse.builder()
                .id(1L)
                .year(2026)
                .month(10)
                .targetAmount(new BigDecimal("100000000"))
                .build();

        when(salesTargetService.getOptions()).thenReturn(SalesTargetOptionsResponse.builder().build());
        when(salesTargetService.createTarget(any(), eq("admin"))).thenReturn(stubResponse);

        mockMvc.perform(get("/api/sales-targets/options").with(adminUser))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/sales-targets")
                        .with(adminUser)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"year\":2026,\"month\":10,\"userId\":1,\"targetAmount\":100000000}"))
                .andExpect(status().isCreated());
    }
}

