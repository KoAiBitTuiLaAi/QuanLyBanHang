package com.quanlybanhang.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanlybanhang.config.SecurityConfig;
import com.quanlybanhang.dto.request.DealerPortalOrderRequest;
import com.quanlybanhang.dto.request.OrderItemRequest;
import com.quanlybanhang.dto.response.DealerDebtResponse;
import com.quanlybanhang.dto.response.OrderResponse;
import com.quanlybanhang.exception.DealerLockedException;
import com.quanlybanhang.exception.GlobalExceptionHandler;
import com.quanlybanhang.model.*;
import com.quanlybanhang.repository.*;
import com.quanlybanhang.security.AuthEntryPointJwt;
import com.quanlybanhang.security.JwtAuthenticationFilter;
import com.quanlybanhang.security.RestAccessDeniedHandler;
import com.quanlybanhang.security.UserDetailsImpl;
import com.quanlybanhang.security.UserDetailsServiceImpl;
import com.quanlybanhang.service.DealerDebtService;
import com.quanlybanhang.service.DealerDeliveryPointService;
import com.quanlybanhang.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DealerPortalController.class)
@Import({SecurityConfig.class, RestAccessDeniedHandler.class, AuthEntryPointJwt.class, GlobalExceptionHandler.class})
@DisplayName("DealerPortalController - Cổng tự đặt hàng dành cho Đại lý (S4-10)")
class DealerPortalControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private UserRepository userRepository;

    @MockBean
    private DealerRepository dealerRepository;

    @MockBean
    private DealerDebtService dealerDebtService;

    @MockBean
    private DealerDeliveryPointService deliveryPointService;

    @MockBean
    private ProductRepository productRepository;

    @MockBean
    private PriceListRepository priceListRepository;

    @MockBean
    private UnitConversionRepository unitConversionRepository;

    @MockBean
    private OrderService orderService;

    @MockBean
    private UserDetailsServiceImpl userDetailsService;

    @MockBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    private Dealer dealer;
    private User dealerUser;
    private UsernamePasswordAuthenticationToken dealerAuth;

    @BeforeEach
    void setUp() throws Exception {
        doAnswer(invocation -> {
            jakarta.servlet.FilterChain chain = invocation.getArgument(2);
            chain.doFilter(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(jwtAuthenticationFilter).doFilter(any(), any(), any());

        dealer = Dealer.builder()
                .id(100L)
                .code("DL-TEST")
                .name("Đại lý Minh Phát")
                .customerGroup(CustomerGroup.DAI_LY_CAP_1)
                .debtLimit(new BigDecimal("50000000"))
                .locked(false)
                .build();

        Role roleDealer = Role.builder().name(ERole.ROLE_DEALER).build();
        dealerUser = User.builder()
                .id(1L)
                .username("dealer.demo")
                .fullName("Đại diện Đại lý")
                .dealer(dealer)
                .roles(Collections.singleton(roleDealer))
                .active(true)
                .build();

        UserDetailsImpl userDetails = UserDetailsImpl.build(dealerUser);
        dealerAuth = new UsernamePasswordAuthenticationToken(
                userDetails, null, List.of(new SimpleGrantedAuthority("ROLE_DEALER")));
    }

    @Test
    @DisplayName("GET /api/portal/profile: Đại lý xem thông tin hồ sơ và công nợ -> 200 OK")
    void getProfile_Dealer_Returns200() throws Exception {
        when(userRepository.findByUsername("dealer.demo")).thenReturn(Optional.of(dealerUser));
        when(dealerDebtService.getDealerDebtInfo(any(Dealer.class))).thenReturn(
                DealerDebtResponse.builder()
                        .dealerId(100L)
                        .debtLimit(new BigDecimal("50000000"))
                        .currentDebt(new BigDecimal("15000000"))
                        .remainingLimit(new BigDecimal("35000000"))
                        .isBlocked(false)
                        .build()
        );

        mvc.perform(get("/api/portal/profile").with(authentication(dealerAuth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(100))
                .andExpect(jsonPath("$.data.code").value("DL-TEST"))
                .andExpect(jsonPath("$.data.name").value("Đại lý Minh Phát"))
                .andExpect(jsonPath("$.data.customerGroup").value("DAI_LY_CAP_1"))
                .andExpect(jsonPath("$.data.debtLimit").value(50000000))
                .andExpect(jsonPath("$.data.currentDebt").value(15000000))
                .andExpect(jsonPath("$.data.remainingLimit").value(35000000));
    }

    @Test
    @DisplayName("GET /api/portal/profile: Chưa đăng nhập -> 401 Unauthorized")
    void getProfile_Unauthenticated_Returns401() throws Exception {
        mvc.perform(get("/api/portal/profile"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/portal/profile: Role không phải DEALER -> 403 Forbidden")
    void getProfile_WrongRole_Returns403() throws Exception {
        UsernamePasswordAuthenticationToken salesAuth = new UsernamePasswordAuthenticationToken(
                "sales_user", null, List.of(new SimpleGrantedAuthority("ROLE_SALES")));

        mvc.perform(get("/api/portal/profile").with(authentication(salesAuth)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/portal/debt: Đại lý tra cứu công nợ còn lại -> 200 OK")
    void getDebtInfo_Dealer_Returns200() throws Exception {
        when(userRepository.findByUsername("dealer.demo")).thenReturn(Optional.of(dealerUser));
        when(dealerDebtService.getDealerDebtInfo(any(Dealer.class))).thenReturn(
                DealerDebtResponse.builder()
                        .dealerId(100L)
                        .debtLimit(new BigDecimal("50000000"))
                        .currentDebt(new BigDecimal("20000000"))
                        .remainingLimit(new BigDecimal("30000000"))
                        .isBlocked(false)
                        .build()
        );

        mvc.perform(get("/api/portal/debt").with(authentication(dealerAuth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.debtLimit").value(50000000))
                .andExpect(jsonPath("$.data.currentDebt").value(20000000))
                .andExpect(jsonPath("$.data.remainingLimit").value(30000000));
    }

    @Test
    @DisplayName("GET /api/portal/catalog: Chỉ hiển thị sản phẩm và giá theo bảng giá của nhóm đại lý")
    void getCatalog_OnlyShowsProductsInEffectivePriceList() throws Exception {
        when(userRepository.findByUsername("dealer.demo")).thenReturn(Optional.of(dealerUser));

        Product p1 = Product.builder().id(10L).code("BIA-01").name("Bia Heineken lon 330ml").unit("lon").active(true).build();
        Product p2 = Product.builder().id(20L).code("NUOC-01").name("Nước ngọt Coca").unit("lon").active(true).build();
        when(productRepository.findByActiveTrueOrderByNameAsc()).thenReturn(List.of(p1, p2));

        PriceListItem pli1 = PriceListItem.builder()
                .product(p1)
                .sellingPrice(new BigDecimal("18000"))
                .floorPrice(new BigDecimal("15000"))
                .build();

        PriceList priceList = PriceList.builder()
                .id(1L)
                .code("BG-DL-CAP1")
                .name("Bảng giá Đại lý Cấp 1")
                .customerGroup(CustomerGroup.DAI_LY_CAP_1)
                .startDate(LocalDate.now().minusDays(10))
                .items(List.of(pli1))
                .build();

        when(priceListRepository.findEffectivePriceLists(eq(CustomerGroup.DAI_LY_CAP_1), any(LocalDate.class)))
                .thenReturn(List.of(priceList));

        UnitConversion ucThung = UnitConversion.builder()
                .product(p1)
                .unitName("thùng")
                .conversionFactor(new BigDecimal("24"))
                .isBaseUnit(false)
                .price(new BigDecimal("430000"))
                .build();
        when(unitConversionRepository.findByProductIdOrderByConversionFactorAsc(10L)).thenReturn(List.of(ucThung));

        mvc.perform(get("/api/portal/catalog").with(authentication(dealerAuth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].sku").value("BIA-01"))
                .andExpect(jsonPath("$.data[0].basePrice").value(18000))
                .andExpect(jsonPath("$.data[0].units.length()").value(2))
                .andExpect(jsonPath("$.data[0].units[0].unitName").value("lon"))
                .andExpect(jsonPath("$.data[0].units[1].unitName").value("thùng"))
                .andExpect(jsonPath("$.data[0].units[1].price").value(430000));
    }

    @Test
    @DisplayName("POST /api/portal/orders: Đơn hàng tự đặt luôn ở trạng thái Chờ duyệt (PENDING_APPROVAL)")
    void placeOrder_AlwaysPendingApproval() throws Exception {
        when(userRepository.findByUsername("dealer.demo")).thenReturn(Optional.of(dealerUser));

        OrderResponse mockOrderResponse = OrderResponse.builder()
                .id(999L)
                .orderCode("DH-DEALER-001")
                .dealerId(100L)
                .dealerName("Đại lý Minh Phát")
                .status("PENDING_APPROVAL")
                .requiresApproval(true)
                .approvalReason("Đơn hàng tự đặt từ Cổng Đại lý, chờ NVKD phụ trách xác nhận")
                .totalAmount(new BigDecimal("4300000"))
                .build();

        when(orderService.createOrder(any(), any())).thenReturn(mockOrderResponse);

        DealerPortalOrderRequest request = DealerPortalOrderRequest.builder()
                .note("Giao giờ hành chính")
                .items(List.of(
                        OrderItemRequest.builder()
                                .productId(10L)
                                .unit("thùng")
                                .quantity(new BigDecimal("10"))
                                .build()
                ))
                .build();

        mvc.perform(post("/api/portal/orders")
                        .with(authentication(dealerAuth))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(999))
                .andExpect(jsonPath("$.data.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.data.requiresApproval").value(true))
                .andExpect(jsonPath("$.data.approvalReason").value("Đơn hàng tự đặt từ Cổng Đại lý, chờ NVKD phụ trách xác nhận"));
    }

    @Test
    @DisplayName("POST /api/portal/orders: Đại lý bị khoá giao dịch không thể đặt hàng -> 400 Bad Request")
    void placeOrder_DealerLocked_Returns400() throws Exception {
        dealer.setLocked(true);
        dealer.setLockReason("Quá hạn công nợ");
        when(userRepository.findByUsername("dealer.demo")).thenReturn(Optional.of(dealerUser));
        when(orderService.createOrder(any(), any())).thenThrow(new DealerLockedException("Đại lý đang bị khoá giao dịch"));

        DealerPortalOrderRequest request = DealerPortalOrderRequest.builder()
                .items(List.of(
                        OrderItemRequest.builder()
                                .productId(10L)
                                .unit("thùng")
                                .quantity(BigDecimal.ONE)
                                .build()
                ))
                .build();

        mvc.perform(post("/api/portal/orders")
                        .with(authentication(dealerAuth))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/portal/orders: Đại lý xem lịch sử đơn hàng của chính mình -> 200 OK")
    void getOrders_Dealer_Returns200() throws Exception {
        OrderResponse o1 = OrderResponse.builder()
                .id(1L)
                .orderCode("DH-001")
                .dealerId(100L)
                .status("PENDING_APPROVAL")
                .build();

        when(orderService.getOrdersForDealer("dealer.demo")).thenReturn(List.of(o1));

        mvc.perform(get("/api/portal/orders").with(authentication(dealerAuth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].orderCode").value("DH-001"));
    }

    @Test
    @DisplayName("GET /api/portal/orders/{id}: Đại lý xem chi tiết đơn hàng của mình -> 200 OK")
    void getOrderById_Dealer_Returns200() throws Exception {
        OrderResponse o1 = OrderResponse.builder()
                .id(1L)
                .orderCode("DH-001")
                .dealerId(100L)
                .status("PENDING_APPROVAL")
                .build();

        when(orderService.getOrderByIdForDealer(1L, "dealer.demo")).thenReturn(o1);

        mvc.perform(get("/api/portal/orders/1").with(authentication(dealerAuth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderCode").value("DH-001"));
    }
}
