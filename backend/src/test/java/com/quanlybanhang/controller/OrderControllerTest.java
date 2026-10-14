package com.quanlybanhang.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanlybanhang.config.SecurityConfig;
import com.quanlybanhang.dto.request.OrderCalculateRequest;
import com.quanlybanhang.dto.request.OrderCancelRequest;
import com.quanlybanhang.dto.request.OrderItemRequest;
import com.quanlybanhang.dto.request.OrderLineItemPricingRequest;
import com.quanlybanhang.dto.request.OrderRequest;
import com.quanlybanhang.dto.request.OrderStatusUpdateRequest;
import com.quanlybanhang.dto.response.OrderCalculateItemResponse;
import com.quanlybanhang.dto.response.OrderCalculateResponse;
import com.quanlybanhang.dto.response.OrderItemResponse;
import com.quanlybanhang.dto.response.OrderLineItemPricingResponse;
import com.quanlybanhang.dto.response.OrderResponse;
import com.quanlybanhang.dto.response.OrderTrackingResponse;
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
    @DisplayName("Huỷ đơn hàng có lý do thành công (kèm cảnh báo nếu đại lý bị khoá) (S3-07, S4-06)")
    void cancelOrder_LockedDealer_Returns200WithWarning() throws Exception {
        OrderResponse resp = OrderResponse.builder()
                .id(1L)
                .orderCode("DH-001")
                .status("CANCELLED")
                .cancelReason("Đại lý ngừng hoạt động")
                .dealerLocked(true)
                .dealerLockWarning("Cảnh báo: Đại lý 'Đại lý Minh Phát' đang bị khoá giao dịch")
                .build();

        when(orderService.cancelOrder(eq(1L), eq("Đại lý ngừng hoạt động"), any())).thenReturn(resp);

        OrderCancelRequest cancelReq = OrderCancelRequest.builder()
                .reason("Đại lý ngừng hoạt động")
                .build();

        mockMvc.perform(put("/api/orders/1/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(cancelReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Hủy đơn hàng thành công (Cảnh báo: Đại lý đang bị khoá giao dịch)"))
                .andExpect(jsonPath("$.data.dealerLocked").value(true));
    }

    @Test
    @WithMockUser(username = "sales_user", roles = "SALES")
    @DisplayName("Huỷ đơn hàng không truyền lý do bị chặn 400 Bad Request (S4-06)")
    void cancelOrder_WithoutReason_Returns400() throws Exception {
        mockMvc.perform(put("/api/orders/1/cancel"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Lý do huỷ đơn hàng không được để trống"));
    }

    @Test
    @WithMockUser(username = "sales_user", roles = "SALES")
    @DisplayName("Theo dõi vòng đời đơn hàng GET /api/orders/1/tracking -> 200 OK (S4-06)")
    void getOrderTracking_Returns200() throws Exception {
        OrderTrackingResponse trackingResp = OrderTrackingResponse.builder()
                .orderId(1L)
                .orderCode("DH-001")
                .currentStatus("PICKING")
                .currentStatusDisplayName("Đang soạn hàng")
                .canCancel(true)
                .isExported(false)
                .steps(List.of())
                .history(List.of())
                .build();

        when(orderService.getOrderTracking(eq(1L), any())).thenReturn(trackingResp);

        mockMvc.perform(get("/api/orders/1/tracking"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.orderCode").value("DH-001"))
                .andExpect(jsonPath("$.data.currentStatus").value("PICKING"));
    }

    @Test
    @WithMockUser(username = "warehouse_user", roles = "WAREHOUSE_STAFF")
    @DisplayName("Cập nhật trạng thái vòng đời PUT /api/orders/1/status -> 200 OK (S4-06)")
    void updateOrderStatus_Returns200() throws Exception {
        OrderStatusUpdateRequest updateReq = OrderStatusUpdateRequest.builder()
                .status("PICKING")
                .note("Bắt đầu soạn hàng")
                .build();

        OrderResponse resp = OrderResponse.builder()
                .id(1L)
                .orderCode("DH-001")
                .status("PICKING")
                .statusDisplayName("Đang soạn hàng")
                .build();

        when(orderService.updateOrderStatus(eq(1L), eq("PICKING"), eq("Bắt đầu soạn hàng"), any())).thenReturn(resp);

        mockMvc.perform(put("/api/orders/1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("PICKING"));
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
}
