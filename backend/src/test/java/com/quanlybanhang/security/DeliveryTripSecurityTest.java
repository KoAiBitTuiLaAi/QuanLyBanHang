package com.quanlybanhang.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanlybanhang.config.RolePermissionConfig;
import com.quanlybanhang.config.SecurityConfig;
import com.quanlybanhang.controller.DeliveryTripController;
import com.quanlybanhang.dto.request.CreateDeliveryTripRequest;
import com.quanlybanhang.dto.request.UpdateDeliveryStopStatusRequest;
import com.quanlybanhang.dto.response.DeliveryTripOptionsResponse;
import com.quanlybanhang.dto.response.DeliveryTripResponse;
import com.quanlybanhang.dto.response.DeliveryTripSummaryResponse;
import com.quanlybanhang.exception.GlobalExceptionHandler;
import com.quanlybanhang.model.DeliveryStopStatus;
import com.quanlybanhang.model.DeliveryTripStatus;
import com.quanlybanhang.service.DeliveryTripService;
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

@WebMvcTest(DeliveryTripController.class)
@Import({SecurityConfig.class, RolePermissionConfig.class, RestAccessDeniedHandler.class, AuthEntryPointJwt.class, GlobalExceptionHandler.class, ObjectMapper.class})
@DisplayName("DeliveryTripSecurityTest - Kiểm thử phân quyền ma trận cho chuyến giao hàng (S6-07)")
class DeliveryTripSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DeliveryTripService deliveryTripService;

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
    @DisplayName("Chưa đăng nhập -> 401 Unauthorized")
    void unauthenticated_Returns401() throws Exception {
        mockMvc.perform(get("/api/warehouse/delivery-trips/options"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Tài khoản không có quyền WAREHOUSE_VIEW hay WAREHOUSE_EXPORT -> 403 Forbidden")
    void withoutPermissions_Returns403() throws Exception {
        mockMvc.perform(get("/api/warehouse/delivery-trips/options")
                        .with(user("sales").authorities(
                                new SimpleGrantedAuthority("ROLE_SALES"),
                                new SimpleGrantedAuthority("ORDER_CREATE")
                        )))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Chỉ có quyền WAREHOUSE_VIEW: Được xem options, search, summary nhưng KHÔNG ĐƯỢC tạo chuyến -> 403")
    void viewer_CanRead_CannotCreateTrip_Returns403() throws Exception {
        var viewer = user("viewer").authorities(
                new SimpleGrantedAuthority("ROLE_VIEWER"),
                new SimpleGrantedAuthority("WAREHOUSE_VIEW")
        );

        when(deliveryTripService.getOptions()).thenReturn(DeliveryTripOptionsResponse.builder().build());
        when(deliveryTripService.searchTrips(any(), any(), any(), any(), eq(0), eq(20)))
                .thenReturn(new PageImpl<>(List.of(), org.springframework.data.domain.PageRequest.of(0, 20), 0));
        when(deliveryTripService.getSummary(any())).thenReturn(DeliveryTripSummaryResponse.builder().build());

        // Đọc thành công
        mockMvc.perform(get("/api/warehouse/delivery-trips/options").with(viewer))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/warehouse/delivery-trips").with(viewer))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/warehouse/delivery-trips/summary").with(viewer))
                .andExpect(status().isOk());

        // Thao tác tạo chuyến bị chặn 403
        CreateDeliveryTripRequest req = CreateDeliveryTripRequest.builder()
                .warehouseId(1L)
                .driverName("Tài xế")
                .vehiclePlate("29A-123.45")
                .expectedCompletionTime(LocalDateTime.now().plusHours(2))
                .build();

        mockMvc.perform(post("/api/warehouse/delivery-trips")
                        .with(viewer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Thủ kho có vai trò ROLE_WAREHOUSE_STAFF nhưng BỊ THU HỒI WAREHOUSE_EXPORT -> Bị chặn 403 (Không fallback role)")
    void warehouseRole_WithoutExportPermission_CannotCreate_Returns403() throws Exception {
        // Tài khoản có vai trò kho nhưng trong ma trận quyền chỉ có WAREHOUSE_VIEW, không có WAREHOUSE_EXPORT
        var staffWithoutExport = user("staff").authorities(
                new SimpleGrantedAuthority("ROLE_WAREHOUSE_STAFF"),
                new SimpleGrantedAuthority("WAREHOUSE_VIEW")
        );

        CreateDeliveryTripRequest req = CreateDeliveryTripRequest.builder()
                .warehouseId(1L)
                .driverName("Tài xế")
                .vehiclePlate("29A-123.45")
                .expectedCompletionTime(LocalDateTime.now().plusHours(2))
                .build();

        mockMvc.perform(post("/api/warehouse/delivery-trips")
                        .with(staffWithoutExport)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Cập nhật trạng thái điểm giao yêu cầu quyền WAREHOUSE_EXPORT -> Thiếu bị chặn 403")
    void updateStopStatus_RequiresWarehouseExport_Returns403() throws Exception {
        var viewer = user("viewer").authorities(
                new SimpleGrantedAuthority("ROLE_VIEWER"),
                new SimpleGrantedAuthority("WAREHOUSE_VIEW")
        );

        UpdateDeliveryStopStatusRequest req = UpdateDeliveryStopStatusRequest.builder()
                .status(DeliveryStopStatus.DELIVERED)
                .build();

        mockMvc.perform(put("/api/warehouse/delivery-trips/1/stops/101/status")
                        .with(viewer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Người dùng có WAREHOUSE_EXPORT: Được phép tạo chuyến và cập nhật điểm giao -> 200 OK")
    void userWithExportPermission_CanCreateAndUpdate_Returns200() throws Exception {
        var managerUser = user("manager").authorities(
                new SimpleGrantedAuthority("ROLE_WAREHOUSE_MANAGER"),
                new SimpleGrantedAuthority("WAREHOUSE_VIEW"),
                new SimpleGrantedAuthority("WAREHOUSE_EXPORT")
        );

        CreateDeliveryTripRequest req = CreateDeliveryTripRequest.builder()
                .warehouseId(1L)
                .driverName("Tài xế Hoàng")
                .vehiclePlate("29C-888.88")
                .expectedCompletionTime(LocalDateTime.now().plusHours(3))
                .build();

        when(deliveryTripService.createTrip(any(), any()))
                .thenReturn(DeliveryTripResponse.builder().id(1L).tripCode("CG-001").status(DeliveryTripStatus.IN_PROGRESS).build());

        mockMvc.perform(post("/api/warehouse/delivery-trips")
                        .with(managerUser)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());

        UpdateDeliveryStopStatusRequest updateReq = UpdateDeliveryStopStatusRequest.builder()
                .status(DeliveryStopStatus.DELIVERED)
                .build();

        when(deliveryTripService.updateStopStatus(eq(1L), eq(101L), any(), any()))
                .thenReturn(DeliveryTripResponse.builder().id(1L).deliveredPoints(1L).build());

        mockMvc.perform(put("/api/warehouse/delivery-trips/1/stops/101/status")
                        .with(managerUser)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateReq)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Quản trị viên (ROLE_ADMIN): Toàn quyền trên mọi endpoint -> 200 OK")
    void admin_HasAllPermissions_Returns200() throws Exception {
        var adminUser = user("admin").authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));

        when(deliveryTripService.completeTrip(eq(1L), any()))
                .thenReturn(DeliveryTripResponse.builder().id(1L).status(DeliveryTripStatus.COMPLETED).build());

        mockMvc.perform(post("/api/warehouse/delivery-trips/1/complete").with(adminUser))
                .andExpect(status().isOk());
    }
}
