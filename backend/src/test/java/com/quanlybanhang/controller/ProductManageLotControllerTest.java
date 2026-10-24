package com.quanlybanhang.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanlybanhang.config.RolePermissionConfig;
import com.quanlybanhang.config.SecurityConfig;
import com.quanlybanhang.dto.response.ProductResponse;
import com.quanlybanhang.exception.GlobalExceptionHandler;
import com.quanlybanhang.security.AuthEntryPointJwt;
import com.quanlybanhang.security.JwtAuthenticationFilter;
import com.quanlybanhang.security.RestAccessDeniedHandler;
import com.quanlybanhang.security.UserDetailsServiceImpl;
import com.quanlybanhang.service.ProductService;
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

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ProductController.class)
@Import({SecurityConfig.class, RolePermissionConfig.class, RestAccessDeniedHandler.class, AuthEntryPointJwt.class, GlobalExceptionHandler.class, ObjectMapper.class})
@DisplayName("ProductManageLotControllerTest - Bật/tắt quản lý lô theo SKU (S6-01)")
class ProductManageLotControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ProductService productService;

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
        mockMvc.perform(patch("/api/products/10/manage-lot")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"manageLot\": true}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Tài khoản không có PRODUCT_EDIT hay WAREHOUSE_MANAGE -> 403 Forbidden")
    void withoutPermission_Returns403() throws Exception {
        var user = user("sales").authorities(
                new SimpleGrantedAuthority("ROLE_SALES"),
                new SimpleGrantedAuthority("PRODUCT_VIEW")
        );

        mockMvc.perform(patch("/api/products/10/manage-lot")
                        .with(user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"manageLot\": true}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Người dùng có PRODUCT_EDIT: Bật quản lý lô kèm ngưỡng cảnh báo -> 200 OK")
    void withProductEdit_EnableManageLot_Returns200() throws Exception {
        var user = user("editor").authorities(
                new SimpleGrantedAuthority("ROLE_SALES_MANAGER"),
                new SimpleGrantedAuthority("PRODUCT_EDIT")
        );

        when(productService.toggleManageLot(eq(10L), eq(true), eq(20)))
                .thenReturn(ProductResponse.builder().id(10L).manageLot(true).expiryWarningDays(20).build());

        mockMvc.perform(patch("/api/products/10/manage-lot")
                        .with(user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"manageLot\": true, \"expiryWarningDays\": 20}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.manageLot").value(true))
                .andExpect(jsonPath("$.data.expiryWarningDays").value(20));
    }

    @Test
    @DisplayName("Quản lý kho có WAREHOUSE_MANAGE: Tắt quản lý lô cho SKU -> 200 OK")
    void withWarehouseManage_DisableManageLot_Returns200() throws Exception {
        var user = user("manager").authorities(
                new SimpleGrantedAuthority("ROLE_WAREHOUSE_MANAGER"),
                new SimpleGrantedAuthority("WAREHOUSE_MANAGE")
        );

        when(productService.toggleManageLot(eq(10L), eq(false), isNull()))
                .thenReturn(ProductResponse.builder().id(10L).manageLot(false).expiryWarningDays(30).build());

        mockMvc.perform(patch("/api/products/10/manage-lot")
                        .with(user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"manageLot\": false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.manageLot").value(false));
    }
}

