package com.quanlybanhang.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.quanlybanhang.config.SecurityConfig;
import com.quanlybanhang.dto.response.DealerPurchaseHistoryResponse;
import com.quanlybanhang.exception.GlobalExceptionHandler;
import com.quanlybanhang.security.AuthEntryPointJwt;
import com.quanlybanhang.security.JwtAuthenticationFilter;
import com.quanlybanhang.security.RestAccessDeniedHandler;
import com.quanlybanhang.security.UserDetailsServiceImpl;
import com.quanlybanhang.service.DealerDebtService;
import com.quanlybanhang.service.DealerService;
import com.quanlybanhang.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DealerController.class)
@Import({SecurityConfig.class, RestAccessDeniedHandler.class, AuthEntryPointJwt.class, GlobalExceptionHandler.class})
class DealerPurchaseHistoryControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private DealerService dealerService;

    @MockBean
    private DealerDebtService dealerDebtService;

    @MockBean
    private OrderService orderService;

    @MockBean
    private UserDetailsServiceImpl userDetailsService;

    @MockBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @BeforeEach
    void passThroughJwtFilter() throws Exception {
        doAnswer(invocation -> {
            jakarta.servlet.FilterChain chain = invocation.getArgument(2);
            chain.doFilter(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(jwtAuthenticationFilter).doFilter(any(), any(), any());
    }

    @Test
    @DisplayName("S4-04: GET /api/dealers/{id}/purchase-history thành công với vai trò SALES")
    void testGetPurchaseHistorySuccess() throws Exception {
        DealerPurchaseHistoryResponse item = DealerPurchaseHistoryResponse.builder()
                .productId(10L)
                .sku("HEINEKEN-LON")
                .code("HEINEKEN-LON")
                .name("Bia Heineken Lon 330ml")
                .unit("thùng")
                .avgQty(new BigDecimal("15"))
                .orderCount(2)
                .totalQuantity(new BigDecimal("30"))
                .lastOrderDate(LocalDate.now().minusDays(5))
                .lastOrderQuantity(new BigDecimal("10"))
                .lastOrderUnit("thùng")
                .inLastOrder(true)
                .lastOrderCode("DH-001")
                .build();

        when(dealerService.getDealerPurchaseHistory(eq(1L), eq(3), eq(false)))
                .thenReturn(List.of(item));

        mvc.perform(get("/api/dealers/1/purchase-history?months=3")
                        .with(user("sales01").roles("SALES")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].productId").value(10))
                .andExpect(jsonPath("$.data[0].sku").value("HEINEKEN-LON"))
                .andExpect(jsonPath("$.data[0].name").value("Bia Heineken Lon 330ml"))
                .andExpect(jsonPath("$.data[0].avgQty").value(15))
                .andExpect(jsonPath("$.data[0].unit").value("thùng"))
                .andExpect(jsonPath("$.data[0].inLastOrder").value(true));
    }

    @Test
    @DisplayName("S4-04: GET /api/dealers/{id}/purchase-history trả 403 khi SALES không phụ trách đại lý")
    void testGetPurchaseHistoryDeniedForUnassignedSales() throws Exception {
        when(dealerService.getDealerPurchaseHistory(eq(2L), anyInt(), anyBoolean()))
                .thenThrow(new AccessDeniedException("Bạn chỉ có quyền xem đại lý do mình phụ trách"));

        mvc.perform(get("/api/dealers/2/purchase-history")
                        .with(user("sales01").roles("SALES")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("S4-04: GET /api/dealers/{id}/purchase-history yêu cầu đăng nhập")
    void testGetPurchaseHistoryUnauthenticated() throws Exception {
        mvc.perform(get("/api/dealers/1/purchase-history"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("S4-04: GET /api/dealers/{id}/last-order-items lấy nhóm hàng đơn gần nhất")
    void testGetLastOrderItems() throws Exception {
        DealerPurchaseHistoryResponse item = DealerPurchaseHistoryResponse.builder()
                .productId(20L)
                .sku("TIGER-LON")
                .name("Bia Tiger Lon 330ml")
                .unit("thùng")
                .avgQty(new BigDecimal("5"))
                .inLastOrder(true)
                .build();

        when(dealerService.getDealerLastOrderItems(1L)).thenReturn(List.of(item));

        mvc.perform(get("/api/dealers/1/last-order-items")
                        .with(user("sales01").roles("SALES")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].productId").value(20))
                .andExpect(jsonPath("$.data[0].sku").value("TIGER-LON"));
    }

    @Test
    @DisplayName("S4-04: POST /api/dealers/{id}/last-order-to-draft thêm nhanh nhóm hàng mua lần trước vào bản nháp")
    void testCopyLastOrderToDraftSuccess() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode draftNode = mapper.createObjectNode();
        draftNode.put("dealerId", 1L);
        draftNode.putArray("items");

        when(orderService.copyLastOrderToDraft(eq(1L), anyString())).thenReturn(draftNode);

        mvc.perform(post("/api/dealers/1/last-order-to-draft")
                        .with(user("sales01").roles("SALES"))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Đã thêm nhanh nhóm hàng mua lần trước vào bản nháp tạo đơn"))
                .andExpect(jsonPath("$.data.dealerId").value(1));
    }
}

