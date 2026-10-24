package com.quanlybanhang.service;

import com.quanlybanhang.dto.request.OrderItemRequest;
import com.quanlybanhang.dto.request.OrderRequest;
import com.quanlybanhang.dto.response.DealerDebtResponse;
import com.quanlybanhang.dto.response.OrderResponse;
import com.quanlybanhang.exception.DealerDebtOverdueException;
import com.quanlybanhang.model.Dealer;
import com.quanlybanhang.model.Order;
import com.quanlybanhang.model.OrderItem;
import com.quanlybanhang.model.Product;
import com.quanlybanhang.repository.DealerRepository;
import com.quanlybanhang.repository.OrderRepository;
import com.quanlybanhang.repository.ProductRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import com.quanlybanhang.dto.request.OrderInventoryCheckRequest;
import com.quanlybanhang.dto.response.OrderInventoryCheckResponse;
import com.quanlybanhang.model.Warehouse;
import com.quanlybanhang.model.WarehouseInventory;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("OrderService - Đơn hàng quy về đơn vị cơ sở khi ghi sổ (S2-07)")
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private UnitConversionService unitConversionService;

    @Mock
    private DealerRepository dealerRepository;

    @Mock
    private com.quanlybanhang.repository.DealerDeliveryPointRepository deliveryPointRepository;

    @Mock
    private com.quanlybanhang.repository.PriceListRepository priceListRepository;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private VolumeDiscountPolicyService volumeDiscountPolicyService;

    @Mock
    private DealerDebtService dealerDebtService;

    @Mock
    private com.quanlybanhang.repository.WarehouseRepository warehouseRepository;

    @Mock
    private com.quanlybanhang.repository.WarehouseInventoryRepository inventoryRepository;
    @Mock
    private com.quanlybanhang.repository.UserRepository userRepository;

    @InjectMocks
    private OrderServiceImpl orderService;

    private Product biaHeineken;

    @BeforeEach
    void setUp() {
        orderService.setUserRepository(userRepository);
        org.mockito.Mockito.lenient().when(userRepository.findByUsername(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(call -> Optional.of(com.quanlybanhang.model.User.builder().id(99L).username(call.getArgument(0)).build()));
        org.mockito.Mockito.lenient().when(orderRepository.findByIdForUpdate(org.mockito.ArgumentMatchers.anyLong()))
                .thenAnswer(call -> orderRepository.findById(call.getArgument(0)));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("manager", "",
                List.of(new SimpleGrantedAuthority("ORDER_APPROVE"))));
        biaHeineken = Product.builder()
                .id(10L)
                .code("SP-KEN330")
                .name("Bia Heineken Sleek lon 330ml")
                .unit("lon")
                .price(new BigDecimal("19000"))
                .build();
        Warehouse configuredWarehouse = Warehouse.builder().id(1L).code("TEST-WH").name("Kho kiểm thử").build();
        org.mockito.Mockito.lenient().when(warehouseRepository.findById(1L)).thenReturn(Optional.of(configuredWarehouse));
        org.mockito.Mockito.lenient().when(warehouseRepository.findAllByActiveTrueOrderByNameAsc()).thenReturn(List.of(configuredWarehouse));
        WarehouseInventory configuredStock = WarehouseInventory.builder().warehouse(configuredWarehouse).product(biaHeineken)
                .baseQuantity(new BigDecimal("1000000")).reservedQuantity(BigDecimal.ZERO).build();
        org.mockito.Mockito.lenient().when(inventoryRepository.findByWarehouseIdAndProductIdInForUpdate(eq(1L), any()))
                .thenReturn(List.of(configuredStock));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Đặt 2 thùng bia (hệ số 24) -> chi tiết đơn hàng ghi đúng số lượng cơ sở 48 lon")
    void createOrder_ByCarton_RecordsInBaseCans() {
        OrderRequest request = OrderRequest.builder()
                .orderCode("DH-001")
                .customerName("Đại lý Minh Tâm")
                .items(List.of(OrderItemRequest.builder()
                        .productId(10L)
                        .unit("thùng")
                        .quantity(new BigDecimal("2"))
                        .price(new BigDecimal("435000"))
                        .build()))
                .build();

        when(productRepository.findById(10L)).thenReturn(Optional.of(biaHeineken));
        when(unitConversionService.getConversionFactor(biaHeineken, "thùng")).thenReturn(new BigDecimal("24.0000"));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OrderResponse response = orderService.createOrder(request, "sales01");

        assertThat(response).isNotNull();
        assertThat(response.getItems()).hasSize(1);

        // Chi tiết dòng hàng
        assertThat(response.getItems().get(0).getUnit()).isEqualTo("thùng");
        assertThat(response.getItems().get(0).getQuantity()).isEqualByComparingTo(new BigDecimal("2"));
        assertThat(response.getItems().get(0).getConversionFactor()).isEqualByComparingTo(new BigDecimal("24.0000"));
        assertThat(response.getItems().get(0).getBaseUnit()).isEqualTo("lon");
        assertThat(response.getItems().get(0).getBaseQuantity()).isEqualByComparingTo(new BigDecimal("48.0000"));

        // Tổng tiền = 2 thùng * 435,000 = 870,000
        assertThat(response.getTotalAmount()).isEqualByComparingTo(new BigDecimal("870000"));
    }

    @Test
    @DisplayName("Đại lý bị khoá giao dịch thì KHÔNG tạo được đơn mới, ném DealerLockedException (S3-07)")
    void createOrder_DealerLocked_ThrowsDealerLockedException() {
        Dealer lockedDealer = Dealer.builder()
                .id(1L)
                .code("DL001")
                .name("Đại lý Minh Phát")
                .locked(true)
                .lockReason("Nợ quá hạn khó đòi")
                .build();

        OrderRequest request = OrderRequest.builder()
                .dealerId(1L)
                .items(List.of(OrderItemRequest.builder()
                        .productId(10L)
                        .quantity(BigDecimal.TEN)
                        .build()))
                .build();

        when(dealerRepository.findById(1L)).thenReturn(Optional.of(lockedDealer));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> orderService.createOrder(request, "sales01"))
                .isInstanceOf(com.quanlybanhang.exception.DealerLockedException.class)
                .hasMessageContaining("Đại lý 'Đại lý Minh Phát' (DL001) đã bị khoá giao dịch, không thể tạo đơn hàng mới. Lý do khoá: Nợ quá hạn khó đòi");

        verify(orderRepository, never()).save(any());
    }

    @Test
    @DisplayName("Đơn đang dở của đại lý bị khoá VẪN xử lý tiếp được (phê duyệt) nhưng có cảnh báo (S3-07)")
    void approveOrder_DealerLocked_SucceedsWithWarning() {
        Dealer lockedDealer = Dealer.builder()
                .id(1L)
                .code("DL001")
                .name("Đại lý Minh Phát")
                .locked(true)
                .lockReason("Nợ quá hạn khó đòi")
                .build();

        Order pendingOrder = Order.builder()
                .id(50L)
                .orderCode("DH-050")
                .status("PENDING_APPROVAL")
                .dealer(lockedDealer)
                .requiresApproval(true)
                .items(new java.util.ArrayList<>())
                .build();

        when(orderRepository.findById(50L)).thenReturn(Optional.of(pendingOrder));
        pendingOrder.setWarehouse(Warehouse.builder().id(1L).code("TEST-WH").name("Kho kiểm thử").build());
        pendingOrder.addItem(OrderItem.builder().product(biaHeineken).quantity(BigDecimal.ONE)
                .unit("lon").baseUnit("lon").conversionFactor(BigDecimal.ONE).baseQuantity(BigDecimal.ONE)
                .price(new BigDecimal("19000")).subtotalAmount(new BigDecimal("19000")).build());
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OrderResponse response = orderService.approveOrder(50L, "sales_mgr");

        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo("RECORDED");
        assertThat(response.getApprovedBy()).isEqualTo("sales_mgr");
        assertThat(response.getDealerLocked()).isTrue();
        assertThat(response.getDealerLockWarning()).contains("Cảnh báo: Đại lý 'Đại lý Minh Phát'");
        assertThat(response.getDealerLockWarning()).contains("Nợ quá hạn khó đòi");

        verify(auditLogService).logAction(eq("INVOICE"), eq("DH-050"), eq("APPROVE"), eq("PENDING_APPROVAL"),
                contains("[Cảnh báo: Đại lý 'Đại lý Minh Phát' đang bị khoá giao dịch"), eq("sales_mgr"));
    }

    @Test
    @DisplayName("Đơn đang dở của đại lý bị khoá VẪN huỷ được bình thường và có cảnh báo (S3-07)")
    void cancelOrder_DealerLocked_SucceedsWithWarning() {
        Dealer lockedDealer = Dealer.builder()
                .id(1L)
                .code("DL001")
                .name("Đại lý Minh Phát")
                .locked(true)
                .lockReason("Mất khả năng thanh toán")
                .build();

        Order recordedOrder = Order.builder()
                .id(51L)
                .orderCode("DH-051")
                .status("RECORDED")
                .dealer(lockedDealer)
                .items(new java.util.ArrayList<>())
                .build();

        when(orderRepository.findById(51L)).thenReturn(Optional.of(recordedOrder));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OrderResponse response = orderService.cancelOrder(51L, "sales01");

        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo("CANCELLED");
        assertThat(response.getDealerLocked()).isTrue();
        assertThat(response.getDealerLockWarning()).contains("Cảnh báo: Đại lý 'Đại lý Minh Phát'");
    }

    @Test
    @DisplayName("S4-02: Đại lý có nợ quá hạn vượt quá số ngày cho phép -> Chặn tạo đơn hoàn toàn")
    void createOrder_DealerHasOverdueDebt_ThrowsDealerDebtOverdueException() {
        Dealer overdueDealer = Dealer.builder()
                .id(2L)
                .code("DL-OVERDUE")
                .name("Đại lý Nợ Quá Hạn")
                .debtLimit(new BigDecimal("100000000"))
                .maxDebtDays(30)
                .active(true)
                .locked(false)
                .build();

        OrderRequest request = OrderRequest.builder()
                .dealerId(2L)
                .items(List.of(OrderItemRequest.builder()
                        .productId(10L)
                        .quantity(BigDecimal.ONE)
                        .price(new BigDecimal("19000"))
                        .build()))
                .build();

        when(dealerRepository.findById(2L)).thenReturn(Optional.of(overdueDealer));
        org.mockito.Mockito.doThrow(new DealerDebtOverdueException("DL-OVERDUE", "Đại lý Nợ Quá Hạn", 45, 30, new BigDecimal("15000000")))
                .when(dealerDebtService).validateDealerCanCreateOrder(overdueDealer);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> orderService.createOrder(request, "sales01"))
                .isInstanceOf(DealerDebtOverdueException.class)
                .hasMessageContaining("Đại lý 'Đại lý Nợ Quá Hạn' (DL-OVERDUE) có khoản nợ quá hạn 45 ngày");

        verify(orderRepository, never()).save(any());
    }

    @Test
    @DisplayName("S4-02: Tổng đơn cộng công nợ hiện tại vượt hạn mức -> Đơn bị đánh dấu PENDING_APPROVAL")
    void createOrder_ExceedsDebtLimit_SetsPendingApproval() {
        Dealer dealerWithLimit = Dealer.builder()
                .id(3L)
                .code("DL-LIMIT")
                .name("Đại lý Hạn Mức")
                .debtLimit(new BigDecimal("10000000"))
                .maxDebtDays(30)
                .active(true)
                .locked(false)
                .build();

        OrderRequest request = OrderRequest.builder()
                .orderCode("DH-DEBT-01")
                .dealerId(3L)
                .items(List.of(OrderItemRequest.builder()
                        .productId(10L)
                        .unit("thùng")
                        .quantity(new BigDecimal("2"))
                        .price(new BigDecimal("435000"))
                        .build()))
                .build();

        when(dealerRepository.findById(3L)).thenReturn(Optional.of(dealerWithLimit));
        mockEffectivePrices(dealerWithLimit);
        when(productRepository.findById(10L)).thenReturn(Optional.of(biaHeineken));
        when(unitConversionService.getConversionFactor(biaHeineken, "thùng")).thenReturn(new BigDecimal("24.0000"));

        when(dealerDebtService.getDealerDebtInfo(dealerWithLimit)).thenReturn(
                DealerDebtResponse.builder()
                        .dealerId(3L)
                        .dealerCode("DL-LIMIT")
                        .dealerName("Đại lý Hạn Mức")
                        .currentDebt(new BigDecimal("9500000"))
                        .debtLimit(new BigDecimal("10000000"))
                        .remainingLimit(new BigDecimal("500000"))
                        .build());
        when(dealerDebtService.checkProjectedDebtExceedsLimit(eq(dealerWithLimit), any(BigDecimal.class), eq(new BigDecimal("9500000"))))
                .thenReturn(true);
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OrderResponse response = orderService.createOrder(request, "sales01");

        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo("PENDING_APPROVAL");
        assertThat(response.getRequiresApproval()).isTrue();
        assertThat(response.getApprovalReason()).contains("Vượt hạn mức công nợ");
        assertThat(response.getExceedsDebtLimit()).isTrue();
        assertThat(response.getCurrentDebt()).isEqualByComparingTo(new BigDecimal("9500000"));
        assertThat(response.getDebtLimit()).isEqualByComparingTo(new BigDecimal("10000000"));
    }

    @Test
    @DisplayName("S4-02: Tổng đơn cộng công nợ hiện tại trong hạn mức -> Đơn ghi sổ RECORDED bình thường")
    void createOrder_WithinDebtLimit_SetsRecorded() {
        Dealer dealerWithLimit = Dealer.builder()
                .id(4L)
                .code("DL-OK")
                .name("Đại lý Chuẩn")
                .debtLimit(new BigDecimal("50000000"))
                .maxDebtDays(30)
                .active(true)
                .locked(false)
                .build();

        OrderRequest request = OrderRequest.builder()
                .orderCode("DH-DEBT-02")
                .dealerId(4L)
                .items(List.of(OrderItemRequest.builder()
                        .productId(10L)
                        .unit("thùng")
                        .quantity(new BigDecimal("2"))
                        .price(new BigDecimal("435000"))
                        .build()))
                .build();

        when(dealerRepository.findById(4L)).thenReturn(Optional.of(dealerWithLimit));
        mockEffectivePrices(dealerWithLimit);
        when(productRepository.findById(10L)).thenReturn(Optional.of(biaHeineken));
        when(unitConversionService.getConversionFactor(biaHeineken, "thùng")).thenReturn(new BigDecimal("24.0000"));

        when(dealerDebtService.getDealerDebtInfo(dealerWithLimit)).thenReturn(
                DealerDebtResponse.builder()
                        .dealerId(4L)
                        .dealerCode("DL-OK")
                        .dealerName("Đại lý Chuẩn")
                        .currentDebt(new BigDecimal("5000000"))
                        .debtLimit(new BigDecimal("50000000"))
                        .remainingLimit(new BigDecimal("45000000"))
                        .build());
        when(dealerDebtService.checkProjectedDebtExceedsLimit(eq(dealerWithLimit), any(BigDecimal.class), eq(new BigDecimal("5000000"))))
                .thenReturn(false);
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OrderResponse response = orderService.createOrder(request, "sales01");

        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo("RECORDED");
        assertThat(response.getRequiresApproval()).isFalse();
        assertThat(response.getExceedsDebtLimit()).isFalse();
        assertThat(response.getCurrentDebt()).isEqualByComparingTo(new BigDecimal("5000000"));
        assertThat(response.getDebtLimit()).isEqualByComparingTo(new BigDecimal("50000000"));
    }

    // ==========================================
    // STORY S4-10: CỔNG TỰ ĐẶT HÀNG DÀNH CHO ĐẠI LÝ
    // ==========================================

    @Test
    @DisplayName("S4-10: Đơn hàng do Đại lý tạo luôn ở trạng thái Chờ duyệt (PENDING_APPROVAL)")
    void createOrder_DealerUser_AlwaysSetsPendingApproval() {
        Dealer dealer = Dealer.builder()
                .id(100L)
                .code("DL-PORTAL")
                .name("Đại lý Portal")
                .debtLimit(new BigDecimal("100000000"))
                .locked(false)
                .build();

        com.quanlybanhang.model.User dealerUser = com.quanlybanhang.model.User.builder()
                .id(99L)
                .username("dealer.portal")
                .dealer(dealer)
                .build();

        // Setup SecurityContextHolder as ROLE_DEALER
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("dealer.portal", null,
                        List.of(new SimpleGrantedAuthority("ROLE_DEALER")))
        );

        orderService.setUserRepository(userRepository);
        when(userRepository.findByUsername("dealer.portal")).thenReturn(Optional.of(dealerUser));
        mockEffectivePrices(dealer);
        when(productRepository.findById(10L)).thenReturn(Optional.of(biaHeineken));
        when(unitConversionService.getConversionFactor(biaHeineken, "lon")).thenReturn(BigDecimal.ONE);
        when(dealerDebtService.getDealerDebtInfo(dealer)).thenReturn(
                DealerDebtResponse.builder()
                        .dealerId(100L)
                        .currentDebt(BigDecimal.ZERO)
                        .debtLimit(new BigDecimal("100000000"))
                        .remainingLimit(new BigDecimal("100000000"))
                        .build()
        );
        when(dealerDebtService.checkProjectedDebtExceedsLimit(eq(dealer), any(BigDecimal.class), eq(BigDecimal.ZERO)))
                .thenReturn(false);
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OrderRequest request = OrderRequest.builder()
                .dealerId(100L)
                .items(List.of(OrderItemRequest.builder()
                        .productId(10L)
                        .unit("lon")
                        .quantity(BigDecimal.TEN)
                        .price(new BigDecimal("19000"))
                        .build()))
                .build();

        OrderResponse response = orderService.createOrder(request, "dealer.portal");

        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo("PENDING_APPROVAL");
        assertThat(response.getRequiresApproval()).isTrue();
        assertThat(response.getApprovalReason()).contains("Đại lý tự đặt qua cổng");
    }

    @Test
    @DisplayName("S4-10: NVKD phụ trách đại lý có quyền xác nhận đơn hàng đại lý tự đặt")
    void approveOrder_AssignedSales_Succeeds() {
        com.quanlybanhang.model.User salesUser = com.quanlybanhang.model.User.builder()
                .id(50L)
                .username("sales.assigned")
                .build();

        Dealer dealer = Dealer.builder()
                .id(100L)
                .code("DL-PORTAL")
                .name("Đại lý Portal")
                .assignedUser(salesUser)
                .build();

        Order order = Order.builder()
                .id(1001L)
                .orderCode("DH-DEALER-01")
                .dealer(dealer)
                .status("PENDING_APPROVAL")
                .requiresApproval(true)
                .approvalReason("Đơn hàng tự đặt từ Cổng Đại lý, chờ NVKD phụ trách xác nhận")
                .build();

        when(orderRepository.findById(1001L)).thenReturn(Optional.of(order));
        order.setWarehouse(Warehouse.builder().id(1L).code("TEST-WH").name("Kho kiểm thử").build());
        order.addItem(OrderItem.builder().product(biaHeineken).quantity(BigDecimal.ONE)
                .unit("lon").baseUnit("lon").conversionFactor(BigDecimal.ONE).baseQuantity(BigDecimal.ONE)
                .price(new BigDecimal("19000")).subtotalAmount(new BigDecimal("19000")).build());
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // NVKD phụ trách xác nhận
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("sales.assigned", null,
                        List.of(new SimpleGrantedAuthority("ROLE_SALES"), new SimpleGrantedAuthority("ORDER_CONFIRM")))
        );

        OrderResponse response = orderService.confirmPortalOrder(1001L, "sales.assigned");

        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo("RECORDED");
        assertThat(response.getApprovedBy()).isEqualTo("sales.assigned");
    }

    @Test
    @DisplayName("S4-10: NVKD không phụ trách đại lý bị chặn xác nhận đơn hàng (AccessDeniedException)")
    void approveOrder_UnassignedSales_ThrowsAccessDenied() {
        com.quanlybanhang.model.User salesAssigned = com.quanlybanhang.model.User.builder()
                .id(50L)
                .username("sales.assigned")
                .build();

        Dealer dealer = Dealer.builder()
                .id(100L)
                .code("DL-PORTAL")
                .name("Đại lý Portal")
                .assignedUser(salesAssigned)
                .build();

        Order order = Order.builder()
                .id(1001L)
                .orderCode("DH-DEALER-01")
                .dealer(dealer)
                .status("PENDING_APPROVAL")
                .requiresApproval(true)
                .approvalReason("Đơn hàng tự đặt từ Cổng Đại lý, chờ NVKD phụ trách xác nhận")
                .build();

        when(orderRepository.findById(1001L)).thenReturn(Optional.of(order));

        // NVKD khác cố tình xác nhận
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("sales.other", null,
                        List.of(new SimpleGrantedAuthority("ROLE_SALES"), new SimpleGrantedAuthority("ORDER_CONFIRM")))
        );

        assertThatThrownBy(() -> orderService.confirmPortalOrder(1001L, "sales.other"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("đại lý mình phụ trách");
    }

    @Test
    @DisplayName("S4-10: Đại lý chỉ xem được đơn hàng của chính mình (RLS)")
    void getOrderByIdForDealer_WrongDealer_ThrowsAccessDenied() {
        Dealer dealerA = Dealer.builder().id(100L).code("DL-A").build();
        Dealer dealerB = Dealer.builder().id(200L).code("DL-B").build();

        com.quanlybanhang.model.User userA = com.quanlybanhang.model.User.builder()
                .id(1L)
                .username("dealerA")
                .dealer(dealerA)
                .build();

        Order orderB = Order.builder()
                .id(500L)
                .orderCode("DH-B")
                .dealer(dealerB)
                .build();

        orderService.setUserRepository(userRepository);
        when(userRepository.findByUsername("dealerA")).thenReturn(Optional.of(userA));
        when(orderRepository.findById(500L)).thenReturn(Optional.of(orderB));

        assertThatThrownBy(() -> orderService.getOrderByIdForDealer(500L, "dealerA"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("chính đại lý mình");
    }

    private void mockEffectivePrices(Dealer dealer) {
        if (dealer.getWarehouse() == null) {
            dealer.setWarehouse(Warehouse.builder().id(1L).code("TEST-WH").name("Kho kiểm thử").build());
        }
        var priceList = com.quanlybanhang.model.PriceList.builder()
                .id(1L).customerGroup(dealer.getCustomerGroup())
                .items(List.of(com.quanlybanhang.model.PriceListItem.builder()
                        .product(biaHeineken).sellingPrice(new BigDecimal("19000"))
                        .floorPrice(new BigDecimal("16000")).build()))
                .build();
        when(priceListRepository.findEffectivePriceLists(eq(dealer.getCustomerGroup()), any()))
                .thenReturn(List.of(priceList));
    }

    // ═══════════════════════════════════════════════════════════════
    // S4-03: TỒN KHẢ DỤNG & CHẶN ĐẶT VƯỢT TỒN KHO PHỤC VỤ
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[S4-03] Đặt vượt tồn khả dụng tại kho phục vụ -> Bị chặn với thông báo và gợi ý số lượng tối đa")
    void createOrder_ExceedsAvailableStock_BlockedWithSuggestion() {
        Warehouse warehouse = Warehouse.builder().id(1L).code("KHO-TONG").name("Kho Tổng Hà Nội").active(true).build();
        Dealer dealer = Dealer.builder()
                .id(5L).code("DL-05").name("Đại lý Cầu Giấy")
                .warehouse(warehouse)
                .active(true).locked(false).build();

        OrderRequest request = OrderRequest.builder()
                .orderCode("DH-STOCK-01")
                .dealerId(5L)
                .warehouseId(1L)
                .items(List.of(OrderItemRequest.builder()
                        .productId(10L)
                        .unit("thùng")
                        .quantity(new BigDecimal("3")) // 3 thùng = 72 lon
                        .price(new BigDecimal("435000"))
                        .build()))
                .build();

        when(dealerRepository.findById(5L)).thenReturn(Optional.of(dealer));
        when(warehouseRepository.findById(1L)).thenReturn(Optional.of(warehouse));
        mockEffectivePrices(dealer);
        when(productRepository.findById(10L)).thenReturn(Optional.of(biaHeineken));
        when(unitConversionService.getConversionFactor(biaHeineken, "thùng")).thenReturn(new BigDecimal("24.0000"));

        // Kho có 60 lon thực tế, đang giữ chỗ 12 lon -> tồn khả dụng = 48 lon (tối đa 2 thùng)
        WarehouseInventory inventory = WarehouseInventory.builder()
                .id(100L)
                .warehouse(warehouse)
                .product(biaHeineken)
                .baseQuantity(new BigDecimal("60.0000"))
                .reservedQuantity(new BigDecimal("12.0000"))
                .build();

        when(inventoryRepository.findByWarehouseIdAndProductIdInForUpdate(eq(1L), any()))
                .thenReturn(List.of(inventory));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                orderService.createOrder(request, "sales01"));

        assertThat(ex.getMessage()).contains("Vượt tồn khả dụng");
        assertThat(ex.getMessage()).contains("Số lượng tối đa có thể đặt là 2 thùng");
    }

    @Test
    @DisplayName("[S4-03] Kho đã hết tồn khả dụng (available = 0) -> Bị chặn với thông báo rõ ràng")
    void createOrder_ZeroAvailableStock_Blocked() {
        Warehouse warehouse = Warehouse.builder().id(1L).code("KHO-TONG").name("Kho Tổng Hà Nội").active(true).build();
        Dealer dealer = Dealer.builder()
                .id(5L).code("DL-05").name("Đại lý Cầu Giấy")
                .warehouse(warehouse)
                .active(true).locked(false).build();

        OrderRequest request = OrderRequest.builder()
                .orderCode("DH-STOCK-02")
                .dealerId(5L)
                .items(List.of(OrderItemRequest.builder()
                        .productId(10L)
                        .unit("thùng")
                        .quantity(new BigDecimal("1"))
                        .price(new BigDecimal("435000"))
                        .build()))
                .build();

        when(dealerRepository.findById(5L)).thenReturn(Optional.of(dealer));
        mockEffectivePrices(dealer);
        when(productRepository.findById(10L)).thenReturn(Optional.of(biaHeineken));
        when(unitConversionService.getConversionFactor(biaHeineken, "thùng")).thenReturn(new BigDecimal("24.0000"));

        // Kho có 24 lon nhưng đã giữ chỗ hết 24 lon -> tồn khả dụng = 0
        WarehouseInventory inventory = WarehouseInventory.builder()
                .id(100L)
                .warehouse(warehouse)
                .product(biaHeineken)
                .baseQuantity(new BigDecimal("24.0000"))
                .reservedQuantity(new BigDecimal("24.0000"))
                .build();

        when(inventoryRepository.findByWarehouseIdAndProductIdInForUpdate(eq(1L), any()))
                .thenReturn(List.of(inventory));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                orderService.createOrder(request, "sales01"));

        assertThat(ex.getMessage()).contains("đã hết tồn khả dụng");
        assertThat(ex.getMessage()).contains("Số lượng tối đa có thể đặt là 0 thùng");
    }

    @Test
    @DisplayName("[S4-03] Đủ tồn khả dụng -> Ghi nhận đơn hàng và giữ chỗ thành công")
    void createOrder_StockAvailable_ReservesStockSuccessfully() {
        Warehouse warehouse = Warehouse.builder().id(1L).code("KHO-TONG").name("Kho Tổng Hà Nội").active(true).build();
        Dealer dealer = Dealer.builder()
                .id(5L).code("DL-05").name("Đại lý Cầu Giấy")
                .warehouse(warehouse)
                .active(true).locked(false).build();

        OrderRequest request = OrderRequest.builder()
                .orderCode("DH-STOCK-03")
                .dealerId(5L)
                .warehouseId(1L)
                .items(List.of(OrderItemRequest.builder()
                        .productId(10L)
                        .unit("thùng")
                        .quantity(new BigDecimal("2")) // 2 thùng = 48 lon
                        .price(new BigDecimal("435000"))
                        .build()))
                .build();

        when(dealerRepository.findById(5L)).thenReturn(Optional.of(dealer));
        when(warehouseRepository.findById(1L)).thenReturn(Optional.of(warehouse));
        mockEffectivePrices(dealer);
        when(productRepository.findById(10L)).thenReturn(Optional.of(biaHeineken));
        when(unitConversionService.getConversionFactor(biaHeineken, "thùng")).thenReturn(new BigDecimal("24.0000"));

        WarehouseInventory inventory = WarehouseInventory.builder()
                .id(100L)
                .warehouse(warehouse)
                .product(biaHeineken)
                .baseQuantity(new BigDecimal("100.0000"))
                .reservedQuantity(new BigDecimal("10.0000"))
                .build();

        when(inventoryRepository.findByWarehouseIdAndProductIdInForUpdate(eq(1L), any()))
                .thenReturn(List.of(inventory));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OrderResponse response = orderService.createOrder(request, "sales01");

        assertThat(response).isNotNull();
        assertThat(response.getWarehouseId()).isEqualTo(1L);
        assertThat(response.getWarehouseCode()).isEqualTo("KHO-TONG");
        // Giữ chỗ thêm 48 lon: 10 + 48 = 58 lon
        assertThat(inventory.getReservedQuantity()).isEqualByComparingTo(new BigDecimal("58.0000"));
        verify(inventoryRepository).save(inventory);
    }

    @Test
    @DisplayName("[S4-03] Hủy đơn hàng đã giữ chỗ -> Hoàn trả lại tồn giữ chỗ (reservedQuantity)")
    void cancelOrder_ReleasesReservedInventory() {
        Warehouse warehouse = Warehouse.builder().id(1L).code("KHO-TONG").name("Kho Tổng").build();
        com.quanlybanhang.model.OrderItem item = com.quanlybanhang.model.OrderItem.builder()
                .id(1L)
                .product(biaHeineken)
                .quantity(new BigDecimal("2"))
                .baseQuantity(new BigDecimal("48.0000"))
                .build();

        Order order = Order.builder()
                .id(99L)
                .orderCode("DH-CANCEL-01")
                .warehouse(warehouse)
                .status("RECORDED")
                .inventoryReserved(true)
                .items(List.of(item))
                .build();

        WarehouseInventory inventory = WarehouseInventory.builder()
                .id(100L)
                .warehouse(warehouse)
                .product(biaHeineken)
                .baseQuantity(new BigDecimal("100.0000"))
                .reservedQuantity(new BigDecimal("58.0000"))
                .build();

        when(orderRepository.findById(99L)).thenReturn(Optional.of(order));
        when(inventoryRepository.findByWarehouseIdAndProductIdInForUpdate(eq(1L), any()))
                .thenReturn(List.of(inventory));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OrderResponse response = orderService.cancelOrder(99L, "sales01");

        assertThat(response.getStatus()).isEqualTo("CANCELLED");
        // Hoàn trả 48 lon: 58 - 48 = 10 lon
        assertThat(inventory.getReservedQuantity()).isEqualByComparingTo(new BigDecimal("10.0000"));
        assertThat(order.getInventoryReserved()).isFalse();
        verify(inventoryRepository).save(inventory);
    }

    @Test
    @DisplayName("[S4-03] Kiểm tra tồn khả dụng qua checkInventory API trả về đầy đủ gợi ý")
    void checkInventory_ReturnsAccurateAvailabilityAndSuggestion() {
        Warehouse warehouse = Warehouse.builder().id(1L).code("KHO-TONG").name("Kho Tổng").build();
        when(warehouseRepository.findById(1L)).thenReturn(Optional.of(warehouse));
        when(productRepository.findById(10L)).thenReturn(Optional.of(biaHeineken));
        when(unitConversionService.getConversionFactor(biaHeineken, "thùng")).thenReturn(new BigDecimal("24.0000"));

        WarehouseInventory inventory = WarehouseInventory.builder()
                .id(100L)
                .warehouse(warehouse)
                .product(biaHeineken)
                .baseQuantity(new BigDecimal("60.0000"))
                .reservedQuantity(new BigDecimal("12.0000"))
                .build();
        when(inventoryRepository.findByWarehouseIdAndProductId(1L, 10L)).thenReturn(Optional.of(inventory));

        OrderInventoryCheckRequest req = OrderInventoryCheckRequest.builder()
                .warehouseId(1L)
                .productId(10L)
                .unit("thùng")
                .quantity(new BigDecimal("3")) // yêu cầu 3 thùng = 72 lon > 48 lon
                .build();

        OrderInventoryCheckResponse res = orderService.checkInventory(req);

        assertThat(res.getValid()).isFalse();
        assertThat(res.getPhysicalQuantity()).isEqualByComparingTo(new BigDecimal("60.0000"));
        assertThat(res.getReservedQuantity()).isEqualByComparingTo(new BigDecimal("12.0000"));
        assertThat(res.getAvailableQuantity()).isEqualByComparingTo(new BigDecimal("48.0000"));
        assertThat(res.getMaxAllowedInUnit()).isEqualByComparingTo(new BigDecimal("2")); // 48 / 24 = 2 thùng
        assertThat(res.getMessage()).contains("Số lượng tối đa có thể đặt là 2 thùng");
    }

    @Test
    @DisplayName("[S4-03] Hai người cùng chốt đơn trên SKU sắp hết -> Chỉ 1 người thành công, 1 người bị chặn, không âm tồn")
    void concurrentOrders_OnLowStockSku_OneSucceeds_OneRejected_NeverNegative() throws Exception {
        Warehouse warehouse = Warehouse.builder().id(1L).code("KHO-TONG").name("Kho Tổng").build();
        Dealer dealer = Dealer.builder()
                .id(5L)
                .code("DL-CONCURRENT")
                .name("Đại lý Thử Concurrency")
                .warehouse(warehouse)
                .locked(false)
                .customerGroup(com.quanlybanhang.model.CustomerGroup.KHACH_LE)
                .build();

        // Kho có 50 lon, đặt 2 thùng = 48 lon -> Chỉ 1 đơn chốt được, đơn thứ hai yêu cầu 48 lon sẽ bị chặn
        WarehouseInventory inventory = WarehouseInventory.builder()
                .id(100L)
                .warehouse(warehouse)
                .product(biaHeineken)
                .baseQuantity(new BigDecimal("50.0000"))
                .reservedQuantity(new BigDecimal("0.0000"))
                .build();

        when(dealerRepository.findById(5L)).thenReturn(Optional.of(dealer));
        when(warehouseRepository.findById(1L)).thenReturn(Optional.of(warehouse));
        mockEffectivePrices(dealer);
        when(productRepository.findById(10L)).thenReturn(Optional.of(biaHeineken));
        when(unitConversionService.getConversionFactor(biaHeineken, "thùng")).thenReturn(new BigDecimal("24.0000"));

        // Giả lập khóa bi quan: mỗi lần gọi findByWarehouseIdAndProductIdInForUpdate trả về bản ghi với synchronized
        when(inventoryRepository.findByWarehouseIdAndProductIdInForUpdate(eq(1L), any()))
                .thenAnswer(invocation -> List.of(inventory));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OrderRequest req1 = OrderRequest.builder()
                .orderCode("DH-CONCURRENT-01")
                .dealerId(5L)
                .warehouseId(1L)
                .items(List.of(OrderItemRequest.builder()
                        .productId(10L)
                        .unit("thùng")
                        .quantity(new BigDecimal("2")) // 48 lon
                        .price(new BigDecimal("435000"))
                        .build()))
                .build();

        OrderRequest req2 = OrderRequest.builder()
                .orderCode("DH-CONCURRENT-02")
                .dealerId(5L)
                .warehouseId(1L)
                .items(List.of(OrderItemRequest.builder()
                        .productId(10L)
                        .unit("thùng")
                        .quantity(new BigDecimal("2")) // 48 lon
                        .price(new BigDecimal("435000"))
                        .build()))
                .build();

        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.atomic.AtomicInteger successCount = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger failureCount = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.CountDownLatch startLatch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch doneLatch = new java.util.concurrent.CountDownLatch(2);

        // Sử dụng monitor trên inventory để đồng bộ mô phỏng pessimistic write lock
        Object lock = new Object();

        Runnable task1 = () -> {
            try {
                startLatch.await();
                synchronized (lock) {
                    orderService.createOrder(req1, "user1");
                }
                successCount.incrementAndGet();
            } catch (IllegalArgumentException e) {
                failureCount.incrementAndGet();
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                doneLatch.countDown();
            }
        };

        Runnable task2 = () -> {
            try {
                startLatch.await();
                synchronized (lock) {
                    orderService.createOrder(req2, "user2");
                }
                successCount.incrementAndGet();
            } catch (IllegalArgumentException e) {
                failureCount.incrementAndGet();
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                doneLatch.countDown();
            }
        };

        executor.submit(task1);
        executor.submit(task2);
        startLatch.countDown(); // Khởi chạy 2 luồng đồng thời
        doneLatch.await(5, java.util.concurrent.TimeUnit.SECONDS);
        executor.shutdown();

        // 1 đơn thành công, 1 đơn thất bại
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failureCount.get()).isEqualTo(1);

        // Tồn kho không bị âm: Tồn giữ chỗ đúng bằng 48 lon, tồn khả dụng còn lại 2 lon >= 0
        assertThat(inventory.getReservedQuantity()).isEqualByComparingTo(new BigDecimal("48.0000"));
        BigDecimal available = inventory.getBaseQuantity().subtract(inventory.getReservedQuantity());
        assertThat(available).isEqualByComparingTo(new BigDecimal("2.0000"));
        assertThat(available).isGreaterThanOrEqualTo(BigDecimal.ZERO);
    }

    // ==================== S5-06: Giữ chỗ tồn kho khi duyệt đơn & tự động nhả tồn khi huỷ/hết hạn ====================

    @Test
    @DisplayName("[S5-06] Duyệt đơn hàng: Tồn tương ứng được giữ chỗ tự động và thời hạn giữ chỗ được thiết lập")
    void approveOrder_SufficientStock_ReservesInventoryAndSetsExpiry() {
        Warehouse warehouse = Warehouse.builder().id(1L).code("KHO-HCM").name("Kho HCM").build();
        WarehouseInventory inventory = WarehouseInventory.builder()
                .id(101L)
                .warehouse(warehouse)
                .product(biaHeineken)
                .baseQuantity(new BigDecimal("100.0000"))
                .reservedQuantity(new BigDecimal("10.0000"))
                .build();

        OrderItem item = OrderItem.builder()
                .product(biaHeineken)
                .unit("thùng")
                .quantity(new BigDecimal("2"))
                .conversionFactor(new BigDecimal("24.0000"))
                .baseQuantity(new BigDecimal("48.0000"))
                .baseUnit("lon")
                .price(new BigDecimal("435000"))
                .totalAmount(new BigDecimal("870000"))
                .build();

        Order order = Order.builder()
                .id(99L)
                .orderCode("DH-S506-01")
                .status("PENDING_APPROVAL")
                .warehouse(warehouse)
                .requiresApproval(true)
                .inventoryReserved(false)
                .items(new java.util.ArrayList<>(List.of(item)))
                .build();
        item.setOrder(order);

        when(orderRepository.findById(99L)).thenReturn(Optional.of(order));
        when(inventoryRepository.findByWarehouseIdAndProductIdInForUpdate(eq(1L), any()))
                .thenReturn(List.of(inventory));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Phê duyệt với reservationHours = 24h
        OrderResponse response = orderService.approveOrder(99L, 24, "sales_mgr");

        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo("RECORDED");
        assertThat(response.getInventoryReserved()).isTrue();
        assertThat(response.getReservedAt()).isNotNull();
        assertThat(response.getReservationExpiresAt()).isNotNull();
        // Thời hạn giữ chỗ ~ 24h sau
        assertThat(response.getReservationExpiresAt()).isAfter(response.getReservedAt());

        // Kiểm tra tồn giữ chỗ trong kho tăng từ 10 lên 58 (10 + 48)
        assertThat(inventory.getReservedQuantity()).isEqualByComparingTo(new BigDecimal("58.0000"));
        verify(inventoryRepository).save(inventory);
        verify(auditLogService).logAction(eq("INVOICE"), eq("DH-S506-01"), eq("APPROVE"), eq("PENDING_APPROVAL"),
                contains("Giữ chỗ tồn: Thành công"), eq("sales_mgr"));
    }

    @Test
    @DisplayName("[S5-06] Duyệt đơn hàng: Tồn khả dụng không đủ -> Bị chặn, ném ngoại lệ và hoàn lại toàn bộ giao dịch")
    void approveOrder_InsufficientStock_ThrowsExceptionAndRollsBack() {
        Warehouse warehouse = Warehouse.builder().id(1L).code("KHO-HCM").name("Kho HCM").build();
        // Kho có 50 lon vật lý nhưng đã giữ chỗ 30 lon -> tồn khả dụng chỉ còn 20 lon
        WarehouseInventory inventory = WarehouseInventory.builder()
                .id(101L)
                .warehouse(warehouse)
                .product(biaHeineken)
                .baseQuantity(new BigDecimal("50.0000"))
                .reservedQuantity(new BigDecimal("30.0000"))
                .build();

        OrderItem item = OrderItem.builder()
                .product(biaHeineken)
                .unit("thùng")
                .quantity(new BigDecimal("2")) // 2 thùng = 48 lon > 20 lon khả dụng
                .conversionFactor(new BigDecimal("24.0000"))
                .baseQuantity(new BigDecimal("48.0000"))
                .baseUnit("lon")
                .price(new BigDecimal("435000"))
                .totalAmount(new BigDecimal("870000"))
                .build();

        Order order = Order.builder()
                .id(99L)
                .orderCode("DH-S506-FAIL")
                .status("PENDING_APPROVAL")
                .warehouse(warehouse)
                .requiresApproval(true)
                .inventoryReserved(false)
                .items(new java.util.ArrayList<>(List.of(item)))
                .build();
        item.setOrder(order);

        when(orderRepository.findById(99L)).thenReturn(Optional.of(order));
        when(inventoryRepository.findByWarehouseIdAndProductIdInForUpdate(eq(1L), any()))
                .thenReturn(List.of(inventory));

        // Phê duyệt phải ném IllegalArgumentException
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                orderService.approveOrder(99L, "sales_mgr")
        );

        assertThat(ex.getMessage()).contains("Vượt tồn khả dụng");
        assertThat(ex.getMessage()).contains("Tồn khả dụng hiện tại: 20 lon");
        assertThat(ex.getMessage()).contains("Số lượng tối đa có thể đặt là 0 thùng");

        // Tồn kho không bị thay đổi
        assertThat(inventory.getReservedQuantity()).isEqualByComparingTo(new BigDecimal("30.0000"));
        verify(inventoryRepository, never()).save(any());
        // Đơn hàng không bị lưu thành RECORDED
        verify(orderRepository, never()).save(any());
    }

    @Test
    @DisplayName("[S5-06] Huỷ đơn hàng: Tồn giữ chỗ được nhả tự động và hạn giữ chỗ được xoá")
    void cancelOrder_ReleasesReservedInventoryAutomatically() {
        Warehouse warehouse = Warehouse.builder().id(1L).code("KHO-HCM").name("Kho HCM").build();
        WarehouseInventory inventory = WarehouseInventory.builder()
                .id(101L)
                .warehouse(warehouse)
                .product(biaHeineken)
                .baseQuantity(new BigDecimal("100.0000"))
                .reservedQuantity(new BigDecimal("48.0000"))
                .build();

        OrderItem item = OrderItem.builder()
                .product(biaHeineken)
                .unit("thùng")
                .quantity(new BigDecimal("2"))
                .conversionFactor(new BigDecimal("24.0000"))
                .baseQuantity(new BigDecimal("48.0000"))
                .baseUnit("lon")
                .build();

        Order order = Order.builder()
                .id(100L)
                .orderCode("DH-S506-CANCEL")
                .status("RECORDED")
                .warehouse(warehouse)
                .inventoryReserved(true)
                .reservedAt(java.time.LocalDateTime.now().minusHours(2))
                .reservationExpiresAt(java.time.LocalDateTime.now().plusHours(46))
                .items(new java.util.ArrayList<>(List.of(item)))
                .build();
        item.setOrder(order);

        when(orderRepository.findById(100L)).thenReturn(Optional.of(order));
        when(inventoryRepository.findByWarehouseIdAndProductIdInForUpdate(eq(1L), any()))
                .thenReturn(List.of(inventory));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OrderResponse response = orderService.cancelOrder(100L, "sales01");

        assertThat(response.getStatus()).isEqualTo("CANCELLED");
        assertThat(order.getInventoryReserved()).isFalse();
        assertThat(order.getReservationExpiresAt()).isNull();
        // Reserved quantity giảm từ 48 về 0
        assertThat(inventory.getReservedQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
        verify(inventoryRepository).save(inventory);
        verify(auditLogService).logAction(eq("INVOICE"), eq("DH-S506-CANCEL"), eq("CANCEL"), eq("RECORDED"),
                contains("Huỷ bởi người dùng sales01"), eq("sales01"));
    }

    @Test
    @DisplayName("[S5-06] Đơn quá hạn giữ chỗ: releaseExpiredReservations tự động nhả tồn và đánh dấu EXPIRED")
    void releaseExpiredReservations_AutomaticallyReleasesStockAndMarksExpired() {
        Warehouse warehouse = Warehouse.builder().id(1L).code("KHO-HCM").name("Kho HCM").build();
        WarehouseInventory inventory = WarehouseInventory.builder()
                .id(101L)
                .warehouse(warehouse)
                .product(biaHeineken)
                .baseQuantity(new BigDecimal("100.0000"))
                .reservedQuantity(new BigDecimal("48.0000"))
                .build();

        OrderItem item = OrderItem.builder()
                .product(biaHeineken)
                .unit("thùng")
                .quantity(new BigDecimal("2"))
                .conversionFactor(new BigDecimal("24.0000"))
                .baseQuantity(new BigDecimal("48.0000"))
                .baseUnit("lon")
                .build();

        Order expiredOrder = Order.builder()
                .id(105L)
                .orderCode("DH-S506-EXPIRED")
                .status("RECORDED")
                .warehouse(warehouse)
                .inventoryReserved(true)
                .reservedAt(java.time.LocalDateTime.now().minusHours(50))
                .reservationExpiresAt(java.time.LocalDateTime.now().minusHours(2)) // Đã hết hạn 2 tiếng trước
                .items(new java.util.ArrayList<>(List.of(item)))
                .build();
        item.setOrder(expiredOrder);

        when(orderRepository.findExpiredReservationIds(any(java.time.LocalDateTime.class)))
                .thenReturn(List.of(105L));
        when(orderRepository.findById(105L)).thenReturn(Optional.of(expiredOrder));
        when(inventoryRepository.findByWarehouseIdAndProductIdInForUpdate(eq(1L), any()))
                .thenReturn(List.of(inventory));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        int releasedCount = orderService.releaseExpiredReservations();

        assertThat(releasedCount).isEqualTo(1);
        assertThat(expiredOrder.getStatus()).isEqualTo("EXPIRED");
        assertThat(expiredOrder.getInventoryReserved()).isFalse();
        assertThat(expiredOrder.getNote()).contains("Đơn hàng quá hạn giữ chỗ");
        // Tồn giữ chỗ nhả về 0
        assertThat(inventory.getReservedQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
        verify(inventoryRepository).save(inventory);
        verify(auditLogService).logAction(eq("INVOICE"), eq("DH-S506-EXPIRED"), eq("EXPIRE_RESERVATION"), eq("RECORDED"),
                contains("tự động nhả tồn"), eq("system"));
    }

    @Test
    @DisplayName("[S5-06] Tạo đơn cần duyệt (PENDING_APPROVAL): KHÔNG giữ chỗ tồn kho cho tới khi được duyệt")
    void createOrder_RequiresApproval_DoesNotReserveUntilApproved() {
        Dealer dealer = Dealer.builder()
                .id(2L)
                .code("DL-PENDING")
                .name("Đại lý Chờ Duyệt")
                .warehouse(Warehouse.builder().id(1L).code("KHO-HCM").name("Kho HCM").build())
                .locked(false)
                .customerGroup(com.quanlybanhang.model.CustomerGroup.KHACH_LE)
                .build();

        Warehouse warehouse = Warehouse.builder().id(1L).code("KHO-HCM").name("Kho HCM").build();
        WarehouseInventory inventory = WarehouseInventory.builder()
                .id(101L)
                .warehouse(warehouse)
                .product(biaHeineken)
                .baseQuantity(new BigDecimal("100.0000"))
                .reservedQuantity(new BigDecimal("0.0000"))
                .build();

        when(dealerRepository.findById(2L)).thenReturn(Optional.of(dealer));
        when(warehouseRepository.findById(1L)).thenReturn(Optional.of(warehouse));
        when(productRepository.findById(10L)).thenReturn(Optional.of(biaHeineken));
        when(unitConversionService.getConversionFactor(biaHeineken, "thùng")).thenReturn(new BigDecimal("24.0000"));

        // Giá sàn: 20,000/lon * 24 = 480,000/thùng. Đặt 400,000/thùng -> Dưới giá sàn -> PENDING_APPROVAL
        com.quanlybanhang.model.PriceList priceList = com.quanlybanhang.model.PriceList.builder()
                .id(1L)
                .code("BG-TEST")
                .customerGroup(com.quanlybanhang.model.CustomerGroup.KHACH_LE)
                .active(true)
                .items(List.of(com.quanlybanhang.model.PriceListItem.builder()
                        .product(biaHeineken)
                        .sellingPrice(new BigDecimal("19000"))
                        .floorPrice(new BigDecimal("20000"))
                        .build()))
                .build();
        when(priceListRepository.findEffectivePriceLists(eq(com.quanlybanhang.model.CustomerGroup.KHACH_LE), any()))
                .thenReturn(List.of(priceList));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OrderRequest request = OrderRequest.builder()
                .orderCode("DH-S506-BELOW-FLOOR")
                .dealerId(2L)
                .warehouseId(1L)
                .items(List.of(OrderItemRequest.builder()
                        .productId(10L)
                        .unit("thùng")
                        .quantity(new BigDecimal("2"))
                        .price(new BigDecimal("400000")) // dưới giá sàn
                        .build()))
                .build();

        OrderResponse response = orderService.createOrder(request, "sales01");

        assertThat(response.getStatus()).isEqualTo("PENDING_APPROVAL");
        assertThat(response.getRequiresApproval()).isTrue();
        assertThat(response.getInventoryReserved()).isFalse();
        // Không giữ chỗ khi đang chờ duyệt
        assertThat(inventory.getReservedQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
        verify(inventoryRepository, never()).save(any());
    }
}
