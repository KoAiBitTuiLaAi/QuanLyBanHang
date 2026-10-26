package com.quanlybanhang.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanlybanhang.config.RolePermissionConfig;
import com.quanlybanhang.config.SecurityConfig;
import com.quanlybanhang.controller.WarehouseController;
import com.quanlybanhang.dto.response.LotExpiryAlertResponse;
import com.quanlybanhang.dto.response.LotThresholdResponse;
import com.quanlybanhang.dto.response.WarehouseVoucherResponse;
import com.quanlybanhang.exception.GlobalExceptionHandler;
import com.quanlybanhang.service.WarehouseService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WarehouseController.class)
@Import({SecurityConfig.class, RolePermissionConfig.class, RestAccessDeniedHandler.class, AuthEntryPointJwt.class, GlobalExceptionHandler.class, ObjectMapper.class})
@DisplayName("WarehouseLotSecurityTest - Kiểm thử phân quyền ma trận cho Lô & Cảnh báo hạn sử dụng (S6-01)")
class WarehouseLotSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private WarehouseService warehouseService;

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
        mockMvc.perform(get("/api/warehouse/inventory/lots"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/warehouse/lot-expiry-alerts"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/warehouse/lot-thresholds"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Tài khoản không có quyền kho (ROLE_SALES, ORDER_CREATE) -> 403 Forbidden")
    void withoutWarehousePermission_Returns403() throws Exception {
        var salesUser = user("sales").authorities(
                new SimpleGrantedAuthority("ROLE_SALES"),
                new SimpleGrantedAuthority("ORDER_CREATE")
        );

        mockMvc.perform(get("/api/warehouse/inventory/lots").with(salesUser))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/warehouse/lot-expiry-alerts").with(salesUser))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/warehouse/lot-thresholds").with(salesUser))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Thủ kho có WAREHOUSE_VIEW: Được xem tồn theo lô và cảnh báo hạn dùng -> 200 OK")
    void warehouseStaff_WithWarehouseView_CanViewLotsAndAlerts_Returns200() throws Exception {
        var staffUser = user("staff").authorities(
                new SimpleGrantedAuthority("ROLE_WAREHOUSE_STAFF"),
                new SimpleGrantedAuthority("WAREHOUSE_VIEW")
        );

        when(warehouseService.getLotInventory(any(), any(), any(), any(), any()))
                .thenReturn(Collections.emptyList());
        when(warehouseService.getLotExpiryAlerts(any(), any(), any(), any()))
                .thenReturn(LotExpiryAlertResponse.builder().items(Collections.emptyList()).build());

        mockMvc.perform(get("/api/warehouse/inventory/lots").with(staffUser))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/warehouse/lot-expiry-alerts").with(staffUser))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Thủ kho KHÔNG CÓ quyền WAREHOUSE_MANAGE: Thử sửa cấu hình ngưỡng cảnh báo SKU -> 403 Forbidden")
    void warehouseStaff_WithoutWarehouseManage_CannotUpdateThreshold_Returns403() throws Exception {
        var staffUser = user("staff").authorities(
                new SimpleGrantedAuthority("ROLE_WAREHOUSE_STAFF"),
                new SimpleGrantedAuthority("WAREHOUSE_VIEW"),
                new SimpleGrantedAuthority("WAREHOUSE_EXPORT")
        );

        mockMvc.perform(put("/api/warehouse/lot-thresholds/10")
                        .with(staffUser)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"thresholdDays\": 20}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Quản lý kho có WAREHOUSE_MANAGE: Được sửa cấu hình ngưỡng cảnh báo SKU -> 200 OK")
    void warehouseManager_WithWarehouseManage_CanUpdateThreshold_Returns200() throws Exception {
        var managerUser = user("manager").authorities(
                new SimpleGrantedAuthority("ROLE_WAREHOUSE_MANAGER"),
                new SimpleGrantedAuthority("WAREHOUSE_MANAGE")
        );

        when(warehouseService.updateLotThreshold(eq(10L), eq(20), any()))
                .thenReturn(LotThresholdResponse.builder().productId(10L).expiryWarningDays(20).build());

        mockMvc.perform(put("/api/warehouse/lot-thresholds/10")
                        .with(managerUser)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"thresholdDays\": 20}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Thủ kho (chỉ có WAREHOUSE_EXPORT, không có WAREHOUSE_APPROVE) xuất lô quá hạn -> Bị chặn 403 Forbidden")
    void warehouseStaff_ExportingExpiredLot_BlockedWith403() throws Exception {
        var staffUser = user("staff").authorities(
                new SimpleGrantedAuthority("ROLE_WAREHOUSE_STAFF"),
                new SimpleGrantedAuthority("WAREHOUSE_EXPORT")
        );

        when(warehouseService.exportGoods(any(), any()))
                .thenThrow(new AccessDeniedException("Lô hàng đã quá hạn sử dụng. Chỉ Quản lý kho mới có quyền xác nhận ghi đè xuất lô đã quá hạn."));

        mockMvc.perform(post("/api/warehouse/export")
                        .with(staffUser)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"warehouseId\": 1, \"items\": [{\"productId\": 10, \"lotNumber\": \"LOT-EXP\", \"quantity\": 5}]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Quản lý kho có quyền WAREHOUSE_APPROVE xuất lô quá hạn có xác nhận ghi đè kèm lý do -> 200 OK")
    void warehouseManager_ExportingExpiredLotWithOverride_Returns200() throws Exception {
        var managerUser = user("manager").authorities(
                new SimpleGrantedAuthority("ROLE_WAREHOUSE_MANAGER"),
                new SimpleGrantedAuthority("WAREHOUSE_EXPORT"),
                new SimpleGrantedAuthority("WAREHOUSE_APPROVE")
        );

        when(warehouseService.exportGoods(any(), any()))
                .thenReturn(WarehouseVoucherResponse.builder()
                        .id(1L)
                        .overrideExpiredLot(true)
                        .expiredLotOverrideReason("Xuất tiêu hủy theo biên bản")
                        .build());

        mockMvc.perform(post("/api/warehouse/export")
                        .with(managerUser)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"warehouseId\": 1, \"overrideExpiredLot\": true, \"expiredLotOverrideReason\": \"Xuất tiêu hủy theo biên bản\", \"items\": [{\"productId\": 10, \"lotNumber\": \"LOT-EXP\", \"quantity\": 5}]}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Ma trận phân quyền: Thu hồi WAREHOUSE_APPROVE từ Quản lý kho -> xuất lô quá hạn lập tức bị chặn 403 (không role fallback)")
    void revokingWarehouseApprove_BlocksExpiredLotExport_Returns403() throws Exception {
        // Tài khoản có vai trò ROLE_WAREHOUSE_MANAGER nhưng quyền WAREHOUSE_APPROVE đã bị thu hồi khỏi ma trận
        var revokedManager = user("revoked-manager").authorities(
                new SimpleGrantedAuthority("ROLE_WAREHOUSE_MANAGER"),
                new SimpleGrantedAuthority("WAREHOUSE_EXPORT"),
                new SimpleGrantedAuthority("WAREHOUSE_VIEW")
        );

        when(warehouseService.exportGoods(any(), any()))
                .thenThrow(new AccessDeniedException("Lô hàng đã quá hạn sử dụng. Chỉ Quản lý kho mới có quyền xác nhận ghi đè xuất lô đã quá hạn."));

        mockMvc.perform(post("/api/warehouse/export")
                        .with(revokedManager)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"warehouseId\": 1, \"overrideExpiredLot\": true, \"expiredLotOverrideReason\": \"Thử xuất sau khi bị thu hồi quyền\", \"items\": [{\"productId\": 10, \"lotNumber\": \"LOT-EXP\", \"quantity\": 5}]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("ROLE_ADMIN luôn có toàn quyền quản lý lô, xem cảnh báo và ghi đè xuất lô quá hạn -> 200 OK")
    void admin_AlwaysHasFullAccess_Returns200() throws Exception {
        var adminUser = user("admin").authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));

        when(warehouseService.getLotExpiryAlerts(any(), any(), any(), any()))
                .thenReturn(LotExpiryAlertResponse.builder().items(Collections.emptyList()).build());
        when(warehouseService.updateLotThreshold(eq(10L), eq(30), any()))
                .thenReturn(LotThresholdResponse.builder().productId(10L).expiryWarningDays(30).build());

        mockMvc.perform(get("/api/warehouse/lot-expiry-alerts").with(adminUser))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/warehouse/lot-thresholds/10")
                        .with(adminUser)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"thresholdDays\": 30}"))
                .andExpect(status().isOk());
    }
}

