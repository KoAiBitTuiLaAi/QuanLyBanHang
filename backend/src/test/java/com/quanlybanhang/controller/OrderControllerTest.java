package com.quanlybanhang.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanlybanhang.config.SecurityConfig;
import com.quanlybanhang.dto.request.OrderCalculateRequest;
import com.quanlybanhang.dto.request.OrderItemRequest;
import com.quanlybanhang.dto.request.OrderLineItemPricingRequest;
import com.quanlybanhang.dto.request.OrderRequest;
import com.quanlybanhang.dto.response.OrderCalculateItemResponse;
import com.quanlybanhang.dto.response.OrderCalculateResponse;
import com.quanlybanhang.dto.response.OrderItemResponse;
import com.quanlybanhang.dto.response.OrderLineItemPricingResponse;
import com.quanlybanhang.dto.response.OrderResponse;
import com.quanlybanhang.exception.GlobalExceptionHandler;
import com.quanlybanhang.model.CustomerGroup;
import com.quanlybanhang.repository.OrderDraftRepository;
import com.quanlybanhang.security.AuthEntryPointJwt;
import com.quanlybanhang.security.JwtAuthenticationFilter;
import com.quanlybanhang.security.JwtUtils;
import com.quanlybanhang.security.RestAccessDeniedHandler;
import com.quanlybanhang.security.UserDetailsServiceImpl;
import com.quanlybanhang.service.DealerDebtService;
import com.quanlybanhang.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = OrderController.class)
@Import({SecurityConfig.class, RestAccessDeniedHandler.class, AuthEntryPointJwt.class, GlobalExceptionHandler.class, ObjectMapper.class})
@DisplayName("OrderController - API Quản lý Đơn hàng (S2-07)")
class OrderControllerTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper objectMapper;

    private MockMvc mockMvc;

    @MockBean
    private OrderService orderService;

    @MockBean
    private DealerDebtService dealerDebtService;

    @MockBean
    private OrderDraftRepository orderDraftRepository;

    @MockBean
    private UserDetailsServiceImpl userDetailsService;

    @MockBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @MockBean
    private JwtUtils jwtUtils;

    @BeforeEach
    void setUp() throws Exception {
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
    @WithMockUser(username = "sales_user", roles = "SALES")
    @DisplayName("GET /api/orders trả về danh sách đơn hàng")
    void getAllOrders_Authenticated_Returns200() throws Exception {
        OrderResponse resp = OrderResponse.builder()
                .id(1L)
                .orderCode("DH-001")
                .customerName("Đại lý A")
                .totalAmount(new BigDecimal("870000"))
                .build();

        when(orderService.getAllOrders()).thenReturn(List.of(resp));

        mockMvc.perform(get("/api/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].orderCode").value("DH-001"));
    }

    @Test
    @WithMockUser(username = "sales_user", roles = "SALES")
    @DisplayName("POST /api/orders tạo đơn hàng thành công -> 201 Created")
    void createOrder_Sales_Returns201() throws Exception {
        OrderRequest req = OrderRequest.builder()
                .orderCode("DH-001")
                .customerName("Đại lý A")
                .items(List.of(OrderItemRequest.builder()
                        .productId(10L)
                        .unit("thùng")
                        .quantity(new BigDecimal("2"))
                        .build()))
                .build();

        OrderResponse resp = OrderResponse.builder()
                .id(1L)
                .orderCode("DH-001")
                .customerName("Đại lý A")
                .items(List.of(OrderItemResponse.builder()
                        .productCode("SP-KEN330")
                        .unit("thùng")
                        .quantity(new BigDecimal("2"))
                        .conversionFactor(new BigDecimal("24.0000"))
                        .baseUnit("lon")
                        .baseQuantity(new BigDecimal("48.0000"))
                        .build()))
                .build();

        when(orderService.createOrder(any(), any())).thenReturn(resp);

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.orderCode").value("DH-001"))
                .andExpect(jsonPath("$.data.items[0].unit").value("thùng"))
                .andExpect(jsonPath("$.data.items[0].baseQuantity").value(48.0));
    }

    @Test
    @WithMockUser(username = "sales_user", roles = "SALES")
    @DisplayName("Tạo đơn hàng cho đại lý bị khoá giao dịch bị từ chối 400 Bad Request (S3-07)")
    void createOrder_LockedDealer_Returns400() throws Exception {
        OrderRequest req = OrderRequest.builder()
                .dealerId(1L)
                .items(List.of(OrderItemRequest.builder()
                        .productId(10L)
                        .quantity(new BigDecimal("2"))
                        .build()))
                .build();

        when(orderService.createOrder(any(), any()))
                .thenThrow(new com.quanlybanhang.exception.DealerLockedException("DL001", "Đại lý Minh Phát", "Mất khả năng thanh toán"));

        mockMvc.perform(post("/api/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(
                        "Đại lý 'Đại lý Minh Phát' (DL001) đã bị khoá giao dịch, không thể tạo đơn hàng mới. Lý do khoá: Mất khả năng thanh toán"));
    }

    @Test
    @WithMockUser(username = "manager_user", roles = "SALES_MANAGER")
    @DisplayName("Phê duyệt đơn hàng của đại lý bị khoá vẫn xử lý tiếp được kèm cảnh báo (S3-07)")
    void approveOrder_LockedDealer_Returns200WithWarning() throws Exception {
        OrderResponse resp = OrderResponse.builder()
                .id(1L)
                .orderCode("DH-001")
                .status("RECORDED")
                .dealerLocked(true)
                .dealerLockWarning("Cảnh báo: Đại lý 'Đại lý Minh Phát' đang bị khoá giao dịch")
                .build();

        when(orderService.approveOrder(eq(1L), any())).thenReturn(resp);

        mockMvc.perform(put("/api/orders/1/approve"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Phê duyệt đơn hàng thành công (Cảnh báo: Đại lý đang bị khoá giao dịch)"))
                .andExpect(jsonPath("$.data.dealerLocked").value(true));
    }

    @Test
    @WithMockUser(username = "sales_user", roles = "SALES")
    @DisplayName("Huỷ đơn hàng của đại lý bị khoá vẫn xử lý tiếp được kèm cảnh báo (S3-07)")
    void cancelOrder_LockedDealer_Returns200WithWarning() throws Exception {
        OrderResponse resp = OrderResponse.builder()
                .id(1L)
                .orderCode("DH-001")
                .status("CANCELLED")
                .dealerLocked(true)
                .dealerLockWarning("Cảnh báo: Đại lý 'Đại lý Minh Phát' đang bị khoá giao dịch")
                .build();

        when(orderService.cancelOrder(eq(1L), any())).thenReturn(resp);

        mockMvc.perform(put("/api/orders/1/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Hủy đơn hàng thành công (Cảnh báo: Đại lý đang bị khoá giao dịch)"))
                .andExpect(jsonPath("$.data.dealerLocked").value(true));
    }

    @Test
    @WithMockUser(username = "sales_user", roles = "SALES")
    @DisplayName("POST /api/orders/line-item/pricing thành công -> 200 OK")
    void calculateLineItemPricing_Success_Returns200() throws Exception {
        OrderLineItemPricingRequest req = OrderLineItemPricingRequest.builder()
                .dealerId(1L)
                .productId(10L)
                .unit("lon")
                .quantity(new BigDecimal("100"))
                .build();

        OrderLineItemPricingResponse resp = OrderLineItemPricingResponse.builder()
                .productId(10L)
                .productCode("SP-KEN330")
                .productName("Bia Heineken Sleek lon 330ml")
                .customerGroup(CustomerGroup.DAI_LY_CAP_1)
                .customerGroupName("Đại lý Cấp 1")
                .priceListCode("BG-DLC1-2026")
                .unit("lon")
                .quantity(new BigDecimal("100"))
                .priceListSellingPrice(new BigDecimal("16500"))
                .floorPrice(new BigDecimal("15500"))
                .unitPrice(new BigDecimal("16500"))
                .isManualPrice(false)
                .belowFloorPrice(false)
                .requiresApproval(false)
                .subtotalAmount(new BigDecimal("1650000"))
                .discountAmount(new BigDecimal("82500"))
                .totalAmount(new BigDecimal("1567500"))
                .build();

        when(orderService.calculateLineItemPricing(any())).thenReturn(resp);

        mockMvc.perform(post("/api/orders/line-item/pricing")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.productCode").value("SP-KEN330"))
                .andExpect(jsonPath("$.data.priceListSellingPrice").value(16500))
                .andExpect(jsonPath("$.data.unitPrice").value(16500))
                .andExpect(jsonPath("$.data.totalAmount").value(1567500));
    }

    @Test
    @WithMockUser(username = "sales_user", roles = "SALES")
    @DisplayName("POST /api/orders/line-item/pricing chặn khi SKU chưa có trong bảng giá hiệu lực -> 400 Bad Request")
    void calculateLineItemPricing_SkuNotInPriceList_Returns400() throws Exception {
        OrderLineItemPricingRequest req = OrderLineItemPricingRequest.builder()
                .dealerId(1L)
                .productId(20L)
                .unit("lon")
                .quantity(new BigDecimal("10"))
                .build();

        when(orderService.calculateLineItemPricing(any()))
                .thenThrow(new IllegalArgumentException("Sản phẩm 'Bia Tiger' (mã: SP-TIGER) chưa có trong bảng giá hiệu lực của nhóm khách hàng 'Đại lý Cấp 1'. Không thể thêm dòng hàng."));

        mockMvc.perform(post("/api/orders/line-item/pricing")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Sản phẩm 'Bia Tiger' (mã: SP-TIGER) chưa có trong bảng giá hiệu lực của nhóm khách hàng 'Đại lý Cấp 1'. Không thể thêm dòng hàng."));
    }

    @Test
    @WithMockUser(username = "sales_user", roles = "SALES")
    @DisplayName("POST /api/orders/calculate tính lại đơn hàng thành công -> 200 OK")
    void calculateOrder_Success_Returns200() throws Exception {
        OrderCalculateRequest req = OrderCalculateRequest.builder()
                .dealerId(1L)
                .items(List.of(OrderItemRequest.builder()
                        .productId(10L)
                        .unit("lon")
                        .quantity(new BigDecimal("200"))
                        .build()))
                .build();

        OrderCalculateResponse resp = OrderCalculateResponse.builder()
                .customerGroup(CustomerGroup.DAI_LY_CAP_1)
                .customerGroupName("Đại lý Cấp 1")
                .priceListCode("BG-DLC1-2026")
                .subtotalAmount(new BigDecimal("3300000"))
                .discountAmount(new BigDecimal("330000"))
                .totalAmount(new BigDecimal("2970000"))
                .requiresApproval(false)
                .items(List.of(OrderCalculateItemResponse.builder()
                        .productId(10L)
                        .productCode("SP-KEN330")
                        .unit("lon")
                        .quantity(new BigDecimal("200"))
                        .price(new BigDecimal("16500"))
                        .subtotalAmount(new BigDecimal("3300000"))
                        .discountAmount(new BigDecimal("330000"))
                        .totalAmount(new BigDecimal("2970000"))
                        .build()))
                .build();

        when(orderService.calculateOrder(any())).thenReturn(resp);

        mockMvc.perform(post("/api/orders/calculate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.subtotalAmount").value(3300000))
                .andExpect(jsonPath("$.data.discountAmount").value(330000))
                .andExpect(jsonPath("$.data.totalAmount").value(2970000))
                .andExpect(jsonPath("$.data.items[0].productCode").value("SP-KEN330"));
    }
    @Test
    @WithMockUser(username = "sales01", roles = {"SALES"})
    @DisplayName("[S4-02] POST /api/orders/check-debt - Kiểm tra công nợ khi tạo đơn")
    void checkDebt_Post_Success() throws Exception {
        com.quanlybanhang.dto.response.OrderDebtCheckResponse checkResponse = com.quanlybanhang.dto.response.OrderDebtCheckResponse.builder()
                .dealerId(1L)
                .dealerCode("DL01")
                .dealerName("Đại lý Minh Phát")
                .currentDebt(new BigDecimal("15000000"))
                .debtLimit(new BigDecimal("50000000"))
                .remainingLimit(new BigDecimal("35000000"))
                .newOrderTotal(new BigDecimal("10000000"))
                .projectedDebt(new BigDecimal("25000000"))
                .exceedsLimit(false)
                .hasOverdueDebt(false)
                .isBlocked(false)
                .requiresApproval(false)
                .build();

        when(dealerDebtService.checkOrderDebt(any(com.quanlybanhang.dto.request.OrderDebtCheckRequest.class))).thenReturn(checkResponse);

        com.quanlybanhang.dto.request.OrderDebtCheckRequest req = new com.quanlybanhang.dto.request.OrderDebtCheckRequest();
        req.setDealerId(1L);
        req.setOrderAmount(new BigDecimal("10000000"));

        mockMvc.perform(post("/api/orders/check-debt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.dealerCode").value("DL01"))
                .andExpect(jsonPath("$.data.isBlocked").value(false))
                .andExpect(jsonPath("$.data.requiresApproval").value(false));
    }

    @Test
    @WithMockUser(username = "sales01", roles = {"SALES"})
    @DisplayName("[S4-02] GET /api/orders/check-debt - Kiểm tra công nợ đại lý qua query params")
    void checkDebt_Get_Success() throws Exception {
        com.quanlybanhang.dto.response.OrderDebtCheckResponse checkResponse = com.quanlybanhang.dto.response.OrderDebtCheckResponse.builder()
                .dealerId(1L)
                .dealerCode("DL01")
                .dealerName("Đại lý Minh Phát")
                .currentDebt(new BigDecimal("45000000"))
                .debtLimit(new BigDecimal("50000000"))
                .remainingLimit(new BigDecimal("5000000"))
                .newOrderTotal(new BigDecimal("10000000"))
                .projectedDebt(new BigDecimal("55000000"))
                .exceedsLimit(true)
                .hasOverdueDebt(false)
                .isBlocked(false)
                .requiresApproval(true)
                .approvalReason("Đơn hàng làm vượt hạn mức công nợ")
                .build();

        when(dealerDebtService.checkOrderDebt(eq(1L), any(BigDecimal.class))).thenReturn(checkResponse);

        mockMvc.perform(get("/api/orders/check-debt")
                        .param("dealerId", "1")
                        .param("orderAmount", "10000000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.exceedsLimit").value(true))
                .andExpect(jsonPath("$.data.requiresApproval").value(true));
    }

    @Test
    @WithMockUser(username = "sales01", authorities = {"ROLE_SALES", "ORDER_CREATE"})
    @DisplayName("[S4-09] POST /api/orders/{id}/copy - Sao chép đơn hàng thành đơn mới ở trạng thái DRAFT")
    void copyOrder_Returns201Created() throws Exception {
        OrderResponse copyResp = OrderResponse.builder()
                .id(2L)
                .orderCode("DH-20261014-002")
                .status("DRAFT")
                .customerName("Đại lý Minh Phát")
                .totalAmount(new BigDecimal("2350000"))
                .build();

        when(orderService.copyOrder(eq(1L), eq("sales01"))).thenReturn(copyResp);

        mockMvc.perform(post("/api/orders/1/copy"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(2))
                .andExpect(jsonPath("$.data.orderCode").value("DH-20261014-002"))
                .andExpect(jsonPath("$.data.status").value("DRAFT"));
    }

    @Test
    @WithMockUser(username = "sales01", authorities = {"ROLE_SALES", "ORDER_CREATE"})
    @DisplayName("[S4-09] POST /api/orders/{id}/copy-to-draft - Sao chép các dòng hàng sang draft của người dùng")
    void copyOrderToDraft_Returns200Ok() throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode draftNode = objectMapper.createObjectNode();
        draftNode.put("dealerId", 1L);
        draftNode.putArray("items");

        when(orderService.copyOrderToDraft(eq(1L), eq("sales01"))).thenReturn(draftNode);

        mockMvc.perform(post("/api/orders/1/copy-to-draft"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.dealerId").value(1));
    }

    @Test
    @WithMockUser(username = "sales_manager", authorities = {"ROLE_SALES_MANAGER", "ORDER_VIEW"})
    @DisplayName("[S4-07] GET /api/orders kèm bộ lọc - Quản lý kinh doanh xem danh sách và tổng tiền đang lọc")
    void getOrders_WithFilters_ReturnsFilteredListAndTotalAmount() throws Exception {
        OrderResponse order1 = OrderResponse.builder()
                .id(1L)
                .orderCode("DH-MB-001")
                .dealerId(10L)
                .dealerCode("DL10")
                .dealerName("Đại lý Thăng Long")
                .salesUserId(15L)
                .salesUserName("sales01")
                .territoryId(1L)
                .territoryName("Miền Bắc")
                .status("RECORDED")
                .totalAmount(new BigDecimal("15000000"))
                .build();

        OrderResponse order2 = OrderResponse.builder()
                .id(2L)
                .orderCode("DH-MB-002")
                .dealerId(10L)
                .dealerCode("DL10")
                .dealerName("Đại lý Thăng Long")
                .salesUserId(15L)
                .salesUserName("sales01")
                .territoryId(1L)
                .territoryName("Miền Bắc")
                .status("RECORDED")
                .totalAmount(new BigDecimal("25000000"))
                .build();

        com.quanlybanhang.dto.response.OrderFilterResult filterResult = com.quanlybanhang.dto.response.OrderFilterResult.builder()
                .orders(List.of(order1, order2))
                .totalAmount(new BigDecimal("40000000"))
                .totalOrders(2L)
                .build();

        when(orderService.getOrders(any())).thenReturn(filterResult);

        mockMvc.perform(get("/api/orders")
                        .param("dealerId", "10")
                        .param("status", "RECORDED")
                        .param("salesUserId", "15")
                        .param("territoryId", "1")
                        .param("startDate", "2026-10-01")
                        .param("endDate", "2026-10-15"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].orderCode").value("DH-MB-001"))
                .andExpect(jsonPath("$.data[0].dealerCode").value("DL10"))
                .andExpect(jsonPath("$.data[0].salesUserName").value("sales01"))
                .andExpect(jsonPath("$.data[0].territoryName").value("Miền Bắc"))
                .andExpect(jsonPath("$.data[1].orderCode").value("DH-MB-002"))
                .andExpect(jsonPath("$.totalAmount").value(40000000))
                .andExpect(jsonPath("$.totalOrders").value(2))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    @WithMockUser(username = "sales_manager", authorities = {"ROLE_SALES_MANAGER", "ORDER_VIEW"})
    @DisplayName("[S4-07] GET /api/orders/summary - Quản lý kinh doanh xem thống kê tổng tiền theo bộ lọc")
    void getOrdersSummary_WithFilters_ReturnsSummary() throws Exception {
        com.quanlybanhang.dto.response.OrderFilterResult filterResult = com.quanlybanhang.dto.response.OrderFilterResult.builder()
                .orders(List.of())
                .totalAmount(new BigDecimal("55000000"))
                .totalOrders(5L)
                .build();

        when(orderService.getOrders(any())).thenReturn(filterResult);

        mockMvc.perform(get("/api/orders/summary")
                        .param("territoryId", "1")
                        .param("status", "RECORDED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.totalAmount").value(55000000))
                .andExpect(jsonPath("$.data.totalOrders").value(5));
    }

    @Test
    @WithMockUser(username = "unauthorized_user", authorities = {"ROLE_GUEST"})
    @DisplayName("[S4-07] GET /api/orders không có quyền ORDER_VIEW -> 403 Forbidden")
    void getOrders_UnauthorizedRole_Returns403() throws Exception {
        mockMvc.perform(get("/api/orders"))
                .andExpect(status().isForbidden());
    }
}
