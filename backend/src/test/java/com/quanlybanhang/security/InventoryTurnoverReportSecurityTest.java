package com.quanlybanhang.security;

import com.quanlybanhang.config.SecurityConfig;
import com.quanlybanhang.controller.InventoryTurnoverReportController;
import com.quanlybanhang.dto.response.*;
import com.quanlybanhang.exception.GlobalExceptionHandler;
import com.quanlybanhang.repository.RolePermissionConfigRepository;
import com.quanlybanhang.repository.UserRepository;
import com.quanlybanhang.service.InventoryTurnoverExcelExportService;
import com.quanlybanhang.service.InventoryTurnoverReportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Kiểm thử phân quyền ma trận DB (Security & Permission Matrix) cho Báo cáo tồn kho và vòng quay hàng (S8-07).
 *
 * Quy tắc:
 * - Bắt buộc có quyền REPORT_INVENTORY hoặc ROLE_ADMIN.
 * - Tuyệt đối không dùng role-based fallback (ví dụ chỉ có ROLE_WAREHOUSE_MANAGER nhưng bị gỡ REPORT_INVENTORY phải trả 403).
 */
@WebMvcTest(controllers = InventoryTurnoverReportController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, GlobalExceptionHandler.class})
class InventoryTurnoverReportSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private InventoryTurnoverReportService reportService;

    @MockBean
    private InventoryTurnoverExcelExportService excelExportService;

    @MockBean
    private JwtUtils jwtUtils;

    @MockBean
    private UserDetailsService userDetailsService;

    @MockBean
    private UserRepository userRepository;

    @MockBean
    private RolePermissionConfigRepository rolePermissionConfigRepository;

    @BeforeEach
    void setUp() {
        InventoryTurnoverReportResponse sampleReport = InventoryTurnoverReportResponse.builder()
                .fromDate(LocalDate.of(2026, 3, 1))
                .toDate(LocalDate.of(2026, 3, 30))
                .periodDays(30)
                .slowMovingThresholdDays(60)
                .totalInventoryValue(new BigDecimal("10000000"))
                .slowMovingInventoryValue(new BigDecimal("3500000"))
                .build();

        when(reportService.getOptions()).thenReturn(InventoryTurnoverOptionsResponse.builder().build());
        when(reportService.getReport(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(sampleReport);
        when(reportService.getValuationByWarehouse(any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of());
        when(reportService.getValuationByCategory(any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of());
        when(reportService.getValuationByWarehouseAndCategory(any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of());
        when(reportService.getSkuTurnovers(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(List.of()));
        when(reportService.getSlowMovingSkus(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of());
        when(excelExportService.exportToExcel(any())).thenReturn(new byte[]{1, 2, 3, 4});
        when(excelExportService.buildFileName(any())).thenReturn("Bao-cao-ton-kho-vong-quay.xlsx");
    }

    @Test
    @DisplayName("S8-07 Security: Chưa đăng nhập truy cập báo cáo tồn kho & vòng quay trả về 401")
    void unauthenticatedRequestReturns401() throws Exception {
        mockMvc.perform(get("/api/reports/inventory-turnover"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/reports/inventory-turnover/export"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("S8-07 Security: Có ROLE_WAREHOUSE_MANAGER nhưng bị gỡ quyền REPORT_INVENTORY phải trả về 403 (không fallback theo role)")
    void warehouseManagerRoleWithoutReportInventoryPermissionReturns403() throws Exception {
        var revokedManager = user("wh_mgr_revoked")
                .authorities(new SimpleGrantedAuthority("ROLE_WAREHOUSE_MANAGER"),
                        new SimpleGrantedAuthority("WAREHOUSE_VIEW"));

        mockMvc.perform(get("/api/reports/inventory-turnover").with(revokedManager))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/reports/inventory-turnover/options").with(revokedManager))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/reports/inventory-turnover/by-warehouse").with(revokedManager))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/reports/inventory-turnover/by-category").with(revokedManager))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/reports/inventory-turnover/skus").with(revokedManager))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/reports/inventory-turnover/slow-moving").with(revokedManager))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/reports/inventory-turnover/export").with(revokedManager))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("S8-07 Security: Có quyền REPORT_INVENTORY từ ma trận phân quyền DB trả về 200 cho tất cả endpoint")
    void userWithReportInventoryAuthorityReturns200() throws Exception {
        var authorizedUser = user("wh_mgr")
                .authorities(new SimpleGrantedAuthority("REPORT_INVENTORY"));

        mockMvc.perform(get("/api/reports/inventory-turnover").with(authorizedUser))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(get("/api/warehouse/reports/inventory-turnover").with(authorizedUser))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(get("/api/reports/inventory-turnover/options").with(authorizedUser))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/reports/inventory-turnover/by-warehouse").with(authorizedUser))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/reports/inventory-turnover/by-category").with(authorizedUser))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/reports/inventory-turnover/by-warehouse-category").with(authorizedUser))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/reports/inventory-turnover/skus").with(authorizedUser))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/reports/inventory-turnover/slow-moving").with(authorizedUser))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/reports/inventory-turnover/export").with(authorizedUser))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
    }

    @Test
    @DisplayName("S8-07 Security: ROLE_ADMIN luôn được phép truy cập")
    void adminRoleIsAlwaysAllowed() throws Exception {
        var adminUser = user("admin")
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));

        mockMvc.perform(get("/api/reports/inventory-turnover").with(adminUser))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(get("/api/reports/inventory-turnover/export").with(adminUser))
                .andExpect(status().isOk());
    }
}

