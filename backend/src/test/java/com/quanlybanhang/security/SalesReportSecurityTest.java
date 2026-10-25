package com.quanlybanhang.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanlybanhang.config.RolePermissionConfig;
import com.quanlybanhang.config.SecurityConfig;
import com.quanlybanhang.controller.SalesReportController;
import com.quanlybanhang.dto.response.SalesReportOptionsResponse;
import com.quanlybanhang.dto.response.SalesReportPageResponse;
import com.quanlybanhang.dto.response.SalesReportSummaryResponse;
import com.quanlybanhang.exception.GlobalExceptionHandler;
import com.quanlybanhang.repository.UserRepository;
import com.quanlybanhang.service.SalesReportService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Kiểm thử phân quyền ma trận DB (Security & Permission Matrix) cho Báo cáo doanh số đa chiều (S8-06).
 *
 * Quy tắc:
 * - Bắt buộc có quyền REPORT_SALES hoặc ORDER_VIEW hoặc ROLE_ADMIN.
 * - Tuyệt đối không dùng role-based fallback (nếu có ROLE_SALES_MANAGER nhưng bị thu hồi cả REPORT_SALES lẫn ORDER_VIEW thì phải trả 403).
 */
@WebMvcTest(SalesReportController.class)
@Import({SecurityConfig.class, RolePermissionConfig.class, RestAccessDeniedHandler.class, AuthEntryPointJwt.class, GlobalExceptionHandler.class, ObjectMapper.class})
@DisplayName("SalesReportSecurityTest - Kiểm thử phân quyền ma trận cho Báo cáo doanh số (S8-06)")
class SalesReportSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SalesReportService salesReportService;

    @MockBean
    private UserDetailsServiceImpl userDetailsService;

    @MockBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @MockBean
    private UserRepository userRepository;

    @BeforeEach
    void setUp() throws Exception {
        doAnswer(i -> {
            ((FilterChain) i.getArgument(2)).doFilter(i.getArgument(0), i.getArgument(1));
            return null;
        }).when(jwtAuthenticationFilter).doFilter(any(), any(), any());

        SalesReportPageResponse sampleReport = SalesReportPageResponse.builder()
                .content(List.of())
                .page(0)
                .size(20)
                .totalElements(0)
                .totalPages(0)
                .last(true)
                .summary(SalesReportSummaryResponse.builder()
                        .totalRevenue(BigDecimal.ZERO)
                        .totalQuantity(BigDecimal.ZERO)
                        .build())
                .build();

        when(salesReportService.getOptions()).thenReturn(SalesReportOptionsResponse.builder().build());
        when(salesReportService.getReport(any())).thenReturn(sampleReport);
        when(salesReportService.exportExcel(any())).thenReturn(new byte[]{1, 2, 3, 4});
        when(salesReportService.buildExportFilename(any())).thenReturn("Bao-cao-doanh-so.xlsx");
    }

    @Test
    @DisplayName("S8-06 Security: Chưa đăng nhập truy cập báo cáo doanh số trả về 401")
    void unauthenticatedRequestReturns401() throws Exception {
        mockMvc.perform(get("/api/reports/sales"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/reports/sales/options"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/reports/sales/export"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("S8-06 Security: Có ROLE_SALES_MANAGER nhưng bị gỡ quyền REPORT_SALES và ORDER_VIEW -> 403 Forbidden (không role fallback)")
    void salesManagerRoleWithoutPermissionsReturns403() throws Exception {
        var revokedManager = user("sales_mgr_revoked")
                .authorities(new SimpleGrantedAuthority("ROLE_SALES_MANAGER"),
                        new SimpleGrantedAuthority("PRODUCT_VIEW"));

        mockMvc.perform(get("/api/reports/sales").with(revokedManager))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/reports/sales/options").with(revokedManager))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/reports/sales/export").with(revokedManager))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("S8-06 Security: Có quyền REPORT_SALES từ ma trận phân quyền DB trả về 200 cho tất cả endpoint")
    void userWithReportSalesAuthorityReturns200() throws Exception {
        var authorizedUser = user("sales_user")
                .authorities(new SimpleGrantedAuthority("REPORT_SALES"));

        mockMvc.perform(get("/api/reports/sales").with(authorizedUser))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(get("/api/reports/sales/options").with(authorizedUser))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(get("/api/reports/sales/export").with(authorizedUser))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
    }

    @Test
    @DisplayName("S8-06 Security: Có quyền ORDER_VIEW từ ma trận phân quyền DB trả về 200 cho tất cả endpoint")
    void userWithOrderViewAuthorityReturns200() throws Exception {
        var authorizedUser = user("order_viewer")
                .authorities(new SimpleGrantedAuthority("ORDER_VIEW"));

        mockMvc.perform(get("/api/reports/sales").with(authorizedUser))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(get("/api/reports/sales/options").with(authorizedUser))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @DisplayName("S8-06 Security: ROLE_ADMIN luôn có toàn quyền truy cập")
    void adminRoleIsAlwaysAllowed() throws Exception {
        var adminUser = user("admin")
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));

        mockMvc.perform(get("/api/reports/sales").with(adminUser))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(get("/api/reports/sales/export").with(adminUser))
                .andExpect(status().isOk());
    }
}
