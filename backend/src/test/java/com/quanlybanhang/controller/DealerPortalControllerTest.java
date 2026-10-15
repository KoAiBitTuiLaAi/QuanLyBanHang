package com.quanlybanhang.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanlybanhang.config.SecurityConfig;
import com.quanlybanhang.dto.response.OrderItemResponse;
import com.quanlybanhang.dto.response.OrderResponse;
import com.quanlybanhang.exception.GlobalExceptionHandler;
import com.quanlybanhang.security.AuthEntryPointJwt;
import com.quanlybanhang.security.JwtAuthenticationFilter;
import com.quanlybanhang.security.JwtUtils;
import com.quanlybanhang.security.RestAccessDeniedHandler;
import com.quanlybanhang.security.UserDetailsServiceImpl;
import com.quanlybanhang.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = DealerPortalController.class)
@Import({SecurityConfig.class, RestAccessDeniedHandler.class, AuthEntryPointJwt.class, GlobalExceptionHandler.class, ObjectMapper.class})
@DisplayName("DealerPortalController - API Cổng Đại lý theo dõi đơn hàng (S5-01)")
class DealerPortalControllerTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper objectMapper;

    private MockMvc mockMvc;

    @MockBean
    private OrderService orderService;

    @MockBean
    private UserDetailsServiceImpl userDetailsService;

    @MockBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @MockBean
    private JwtUtils jwtUtils;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

        org.mockito.Mockito.doAnswer(invocation -> {
            jakarta.servlet.ServletRequest request = invocation.getArgument(0);
            jakarta.servlet.ServletResponse response = invocation.getArgument(1);
            jakarta.servlet.FilterChain chain = invocation.getArgument(2);
            chain.doFilter(request, response);
            return null;
        }).when(jwtAuthenticationFilter).doFilter(any(), any(), any());

        this.mockMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    @Test
    @WithMockUser(username = "dealer_user", roles = "DEALER")
    @DisplayName("GET /api/portal/orders - Đại lý xem danh sách đơn hàng kèm trạng thái, ngày giao dự kiến, tổng tiền")
    void getMyOrders_Dealer_Returns200WithOrderList() throws Exception {
        OrderResponse order1 = OrderResponse.builder()
                .id(101L)
                .orderCode("DH-MINHPHAT-001")
                .dealerId(1L)
                .customerName("Đại lý Minh Phát")
                .status("DELIVERED")
                .expectedDeliveryDate(LocalDate.now().plusDays(1))
                .actualDeliveryDate(LocalDate.now())
                .totalAmount(new BigDecimal("15000000.00"))
                .hasShortage(true)
                .totalLineItems(2)
                .build();

        OrderResponse order2 = OrderResponse.builder()
                .id(102L)
                .orderCode("DH-MINHPHAT-002")
                .dealerId(1L)
                .customerName("Đại lý Minh Phát")
                .status("PROCESSING")
                .expectedDeliveryDate(LocalDate.now().plusDays(3))
                .totalAmount(new BigDecimal("22500000.00"))
                .hasShortage(false)
                .totalLineItems(3)
                .build();

        when(orderService.getOrdersForDealer("dealer_user")).thenReturn(List.of(order1, order2));

        mockMvc.perform(get("/api/portal/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].orderCode").value("DH-MINHPHAT-001"))
                .andExpect(jsonPath("$.data[0].status").value("DELIVERED"))
                .andExpect(jsonPath("$.data[0].totalAmount").value(15000000.00))
                .andExpect(jsonPath("$.data[0].hasShortage").value(true))
                .andExpect(jsonPath("$.data[1].orderCode").value("DH-MINHPHAT-002"))
                .andExpect(jsonPath("$.data[1].status").value("PROCESSING"));
    }

    @Test
    @WithMockUser(username = "dealer_user", roles = "DEALER")
    @DisplayName("GET /api/portal/orders/{id} - Xem chi tiết dòng hàng và số lượng thực giao nếu giao thiếu")
    void getMyOrderDetail_Dealer_Returns200WithDeliveredAndShortageInfo() throws Exception {
        OrderItemResponse item1 = OrderItemResponse.builder()
                .id(1L)
                .productId(10L)
                .productCode("SP-KEN330")
                .productName("Bia Heineken lon 330ml")
                .unit("thùng")
                .quantity(new BigDecimal("10.0000"))
                .price(new BigDecimal("450000.00"))
                .totalAmount(new BigDecimal("4500000.00"))
                .deliveredQuantity(new BigDecimal("8.0000"))
                .shortageQuantity(new BigDecimal("2.0000"))
                .isShortage(true)
                .deliveryNote("Tạm thiếu 2 thùng do kho chưa kịp về hàng")
                .build();

        OrderItemResponse item2 = OrderItemResponse.builder()
                .id(2L)
                .productId(11L)
                .productCode("SP-TIGER330")
                .productName("Bia Tiger lon 330ml")
                .unit("thùng")
                .quantity(new BigDecimal("5.0000"))
                .price(new BigDecimal("380000.00"))
                .totalAmount(new BigDecimal("1900000.00"))
                .deliveredQuantity(new BigDecimal("5.0000"))
                .shortageQuantity(BigDecimal.ZERO)
                .isShortage(false)
                .deliveryNote("Giao đủ")
                .build();

        OrderResponse order = OrderResponse.builder()
                .id(101L)
                .orderCode("DH-MINHPHAT-001")
                .dealerId(1L)
                .customerName("Đại lý Minh Phát")
                .status("DELIVERED")
                .expectedDeliveryDate(LocalDate.now().plusDays(1))
                .actualDeliveryDate(LocalDate.now())
                .totalAmount(new BigDecimal("6400000.00"))
                .deliveryNote("Đã giao đợt 1 có thiếu 2 thùng Heineken")
                .hasShortage(true)
                .totalLineItems(2)
                .items(List.of(item1, item2))
                .build();

        when(orderService.getOrderByIdForDealer(101L, "dealer_user")).thenReturn(order);

        mockMvc.perform(get("/api/portal/orders/101"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.orderCode").value("DH-MINHPHAT-001"))
                .andExpect(jsonPath("$.data.hasShortage").value(true))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].productCode").value("SP-KEN330"))
                .andExpect(jsonPath("$.data.items[0].quantity").value(10.0))
                .andExpect(jsonPath("$.data.items[0].deliveredQuantity").value(8.0))
                .andExpect(jsonPath("$.data.items[0].shortageQuantity").value(2.0))
                .andExpect(jsonPath("$.data.items[0].isShortage").value(true))
                .andExpect(jsonPath("$.data.items[0].deliveryNote").value("Tạm thiếu 2 thùng do kho chưa kịp về hàng"))
                .andExpect(jsonPath("$.data.items[1].productCode").value("SP-TIGER330"))
                .andExpect(jsonPath("$.data.items[1].quantity").value(5.0))
                .andExpect(jsonPath("$.data.items[1].deliveredQuantity").value(5.0))
                .andExpect(jsonPath("$.data.items[1].shortageQuantity").value(0))
                .andExpect(jsonPath("$.data.items[1].isShortage").value(false));
    }

    @Test
    @WithMockUser(username = "dealer_user", roles = "DEALER")
    @DisplayName("GET /api/portal/orders/{id} - Đại lý cố truy cập đơn của đại lý khác bị trả về 403 Forbidden")
    void getMyOrderDetail_OtherDealerOrder_Returns403Forbidden() throws Exception {
        when(orderService.getOrderByIdForDealer(999L, "dealer_user"))
                .thenThrow(new AccessDeniedException("Bạn không có quyền xem đơn hàng của đại lý khác"));

        mockMvc.perform(get("/api/portal/orders/999"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Bạn không có quyền thực hiện chức năng này"));
    }

    @Test
    @DisplayName("GET /api/portal/orders - Chưa đăng nhập trả về 401 Unauthorized")
    void getMyOrders_Unauthenticated_Returns401() throws Exception {
        mockMvc.perform(get("/api/portal/orders"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "sales_user", roles = "SALES")
    @DisplayName("GET /api/portal/orders - Nhân viên SALES không có vai trò DEALER bị chặn 403")
    void getMyOrders_SalesRole_Returns403() throws Exception {
        mockMvc.perform(get("/api/portal/orders"))
                .andExpect(status().isForbidden());
    }
}
