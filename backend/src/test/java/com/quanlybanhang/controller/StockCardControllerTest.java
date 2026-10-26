package com.quanlybanhang.controller;

import com.quanlybanhang.config.SecurityConfig;
import com.quanlybanhang.dto.response.StockCardResponse;
import com.quanlybanhang.exception.GlobalExceptionHandler;
import com.quanlybanhang.security.*;
import com.quanlybanhang.service.StockCardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(StockCardController.class)
@Import({SecurityConfig.class, RestAccessDeniedHandler.class, AuthEntryPointJwt.class, GlobalExceptionHandler.class})
class StockCardControllerTest {

    @Autowired private MockMvc mvc;
    @MockBean private StockCardService service;
    @MockBean private UserDetailsServiceImpl userDetailsService;
    @MockBean private JwtAuthenticationFilter jwtAuthenticationFilter;

    @BeforeEach
    void passThrough() throws Exception {
        doAnswer(i -> {
            ((jakarta.servlet.FilterChain) i.getArgument(2)).doFilter(i.getArgument(0), i.getArgument(1));
            return null;
        }).when(jwtAuthenticationFilter).doFilter(any(), any(), any());
    }

    @Test
    void unauthenticatedRequestReturnsUnauthorized() throws Exception {
        mvc.perform(get("/api/warehouse/stock-card/100"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void missingPermissionReturnsForbidden() throws Exception {
        // User with ROLE_WAREHOUSE_STAFF but missing WAREHOUSE_VIEW
        mvc.perform(get("/api/warehouse/stock-card/100")
                .with(user("staff").authorities(
                        new SimpleGrantedAuthority("ROLE_WAREHOUSE_STAFF"),
                        new SimpleGrantedAuthority("WAREHOUSE_IMPORT"))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void warehouseViewAuthoritySucceeds() throws Exception {
        when(service.getStockCard(eq(100L), any(), any(), any(), eq(0), eq(50)))
                .thenReturn(new StockCardResponse(100L, "SP01", "Sữa tươi", "Hộp",
                        List.of(), 0, 1, 0, 50, null, null, null));

        mvc.perform(get("/api/warehouse/stock-card/100")
                .with(user("viewer").authorities(
                        new SimpleGrantedAuthority("ROLE_USER"),
                        new SimpleGrantedAuthority("WAREHOUSE_VIEW"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.productId").value(100))
                .andExpect(jsonPath("$.data.productCode").value("SP01"));

        verify(service).getStockCard(eq(100L), isNull(), isNull(), isNull(), eq(0), eq(50));
    }

    @Test
    void adminRoleAlwaysSucceeds() throws Exception {
        when(service.getStockCard(eq(100L), eq(2L), any(), any(), eq(1), eq(20)))
                .thenReturn(new StockCardResponse(100L, "SP01", "Sữa tươi", "Hộp",
                        List.of(), 0, 1, 1, 20, null, null, null));

        mvc.perform(get("/api/warehouse/stock-card/100")
                .with(user("admin").roles("ADMIN"))
                .param("warehouseId", "2")
                .param("page", "1")
                .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(service).getStockCard(eq(100L), eq(2L), isNull(), isNull(), eq(1), eq(20));
    }

    @Test
    void invalidParameterTypeReturnsBadRequest() throws Exception {
        mvc.perform(get("/api/warehouse/stock-card/invalid")
                .with(user("admin").roles("ADMIN")))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }
}
