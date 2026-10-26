package com.quanlybanhang.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanlybanhang.config.RolePermissionConfig;
import com.quanlybanhang.config.SecurityConfig;
import com.quanlybanhang.controller.DealerReturnController;
import com.quanlybanhang.dto.request.CreateDealerReturnItemRequest;
import com.quanlybanhang.dto.request.CreateDealerReturnRequest;
import com.quanlybanhang.dto.response.DealerReturnResponse;
import com.quanlybanhang.exception.GlobalExceptionHandler;
import com.quanlybanhang.model.DealerReturnStatus;
import com.quanlybanhang.service.DealerReturnService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DealerReturnController.class)
@Import({SecurityConfig.class, RolePermissionConfig.class, RestAccessDeniedHandler.class, AuthEntryPointJwt.class, GlobalExceptionHandler.class})
@DisplayName("DealerReturnSecurityTest - Kiểm thử phân quyền ma trận Database-Backed cho Phiếu trả hàng từ đại lý (S8-01)")
class DealerReturnSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private DealerReturnService dealerReturnService;

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

    private CreateDealerReturnRequest validCreateRequest() {
        return CreateDealerReturnRequest.builder()
                .invoiceId(1L)
                .items(List.of(CreateDealerReturnItemRequest.builder()
                        .invoiceItemId(10L)
                        .returnQuantity(new BigDecimal("2"))
                        .returnReason("Hàng hỏng")
                        .defective(true)
                        .build()))
                .build();
    }

    @Test
    @DisplayName("Chưa đăng nhập -> 401 Unauthorized")
    void unauthenticated_Returns401() throws Exception {
        mockMvc.perform(get("/api/dealer-returns"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/dealer-returns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Nhân viên kinh doanh bị thu hồi ORDER_CREATE -> POST /api/dealer-returns trả 403 Forbidden (không fallback theo role)")
    void createReturn_WithoutOrderCreatePermission_Returns403() throws Exception {
        mockMvc.perform(post("/api/dealer-returns")
                        .with(user("sales").authorities(
                                new SimpleGrantedAuthority("ROLE_SALES"),
                                new SimpleGrantedAuthority("ORDER_VIEW")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validCreateRequest())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Có quyền ORDER_CREATE -> POST /api/dealer-returns trả 200 OK")
    void createReturn_WithOrderCreatePermission_Returns200() throws Exception {
        when(dealerReturnService.createReturn(any(), any()))
                .thenReturn(DealerReturnResponse.builder()
                        .id(1L).returnCode("PTH-001").status(DealerReturnStatus.PENDING).build());

        mockMvc.perform(post("/api/dealer-returns")
                        .with(user("sales").authorities(
                                new SimpleGrantedAuthority("ROLE_SALES"),
                                new SimpleGrantedAuthority("ORDER_CREATE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validCreateRequest())))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Nhân viên kinh doanh hoặc Thủ kho không có WAREHOUSE_APPROVE -> POST /api/dealer-returns/1/confirm trả 403 Forbidden")
    void confirmReturn_WithoutWarehouseApprovePermission_Returns403() throws Exception {
        // ROLE_SALES không có WAREHOUSE_APPROVE
        mockMvc.perform(post("/api/dealer-returns/1/confirm")
                        .with(user("sales").authorities(
                                new SimpleGrantedAuthority("ROLE_SALES"),
                                new SimpleGrantedAuthority("ORDER_CREATE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());

        // ROLE_WAREHOUSE_STAFF không có WAREHOUSE_APPROVE
        mockMvc.perform(post("/api/dealer-returns/1/confirm")
                        .with(user("staff").authorities(
                                new SimpleGrantedAuthority("ROLE_WAREHOUSE_STAFF"),
                                new SimpleGrantedAuthority("WAREHOUSE_IMPORT")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());

        // ROLE_WAREHOUSE_MANAGER nhưng bị thu hồi quyền WAREHOUSE_APPROVE trên ma trận -> vẫn bị chặn 403
        mockMvc.perform(post("/api/dealer-returns/1/confirm")
                        .with(user("manager").authorities(
                                new SimpleGrantedAuthority("ROLE_WAREHOUSE_MANAGER"),
                                new SimpleGrantedAuthority("WAREHOUSE_VIEW")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Quản lý kho có quyền WAREHOUSE_APPROVE -> POST /api/dealer-returns/1/confirm trả 200 OK")
    void confirmReturn_WithWarehouseApprovePermission_Returns200() throws Exception {
        when(dealerReturnService.confirmReturn(eq(1L), any(), any()))
                .thenReturn(DealerReturnResponse.builder()
                        .id(1L).returnCode("PTH-001").status(DealerReturnStatus.CONFIRMED).inventoryAdded(true).build());

        mockMvc.perform(post("/api/dealer-returns/1/confirm")
                        .with(user("wh_manager").authorities(
                                new SimpleGrantedAuthority("ROLE_WAREHOUSE_MANAGER"),
                                new SimpleGrantedAuthority("WAREHOUSE_APPROVE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"Đã nhận hàng\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Quản trị viên ROLE_ADMIN luôn có toàn quyền lập và xác nhận phiếu trả hàng")
    void admin_AlwaysAllowed() throws Exception {
        when(dealerReturnService.createReturn(any(), any()))
                .thenReturn(DealerReturnResponse.builder().id(1L).returnCode("PTH-001").status(DealerReturnStatus.PENDING).build());
        when(dealerReturnService.confirmReturn(eq(1L), any(), any()))
                .thenReturn(DealerReturnResponse.builder().id(1L).returnCode("PTH-001").status(DealerReturnStatus.CONFIRMED).build());

        mockMvc.perform(post("/api/dealer-returns")
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validCreateRequest())))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/dealer-returns/1/confirm")
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
    }
}

