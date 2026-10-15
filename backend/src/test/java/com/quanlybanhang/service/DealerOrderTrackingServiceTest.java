package com.quanlybanhang.service;

import com.quanlybanhang.config.RolePermissionConfig;
import com.quanlybanhang.dto.request.OrderDeliveryUpdateRequest;
import com.quanlybanhang.dto.request.OrderItemDeliveryRequest;
import com.quanlybanhang.dto.response.OrderResponse;
import com.quanlybanhang.model.*;
import com.quanlybanhang.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DealerOrderTrackingServiceTest - Nghiệp vụ theo dõi đơn hàng đại lý & ma trận phân quyền (S5-01)")
class DealerOrderTrackingServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private DealerRepository dealerRepository;

    @Mock
    private DealerDeliveryPointRepository deliveryPointRepository;

    @Mock
    private PriceListRepository priceListRepository;

    @Mock
    private UnitConversionService unitConversionService;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private VolumeDiscountPolicyService volumeDiscountPolicyService;

    @Mock
    private DealerDebtService dealerDebtService;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private OrderServiceImpl orderService;

    private Dealer dealerMinhPhat;
    private Dealer dealerTanBinh;
    private User userDealerMinhPhat;
    private User userDealerTanBinh;
    private User userSales;
    private User userAdmin;
    private Product beerKen;
    private Product beerTiger;

    @BeforeEach
    void setUp() {
        orderService.setUserRepository(userRepository);

        dealerMinhPhat = Dealer.builder()
                .id(1L)
                .code("DL-MINHPHAT")
                .name("Đại lý Minh Phát")
                .active(true)
                .locked(false)
                .build();

        dealerTanBinh = Dealer.builder()
                .id(2L)
                .code("DL-TANBINH")
                .name("Đại lý Tân Bình")
                .active(true)
                .locked(false)
                .build();

        Role dealerRole = new Role(ERole.ROLE_DEALER);
        Role salesRole = new Role(ERole.ROLE_SALES);
        Role adminRole = new Role(ERole.ROLE_ADMIN);

        userDealerMinhPhat = User.builder()
                .id(10L)
                .username("dealer.minhphat")
                .email("minhphat@dealer.vn")
                .dealer(dealerMinhPhat)
                .roles(Set.of(dealerRole))
                .active(true)
                .build();

        userDealerTanBinh = User.builder()
                .id(20L)
                .username("dealer.tanbinh")
                .email("tanbinh@dealer.vn")
                .dealer(dealerTanBinh)
                .roles(Set.of(dealerRole))
                .active(true)
                .build();

        userSales = User.builder()
                .id(30L)
                .username("sales.user")
                .email("sales@company.com")
                .roles(Set.of(salesRole))
                .active(true)
                .build();

        userAdmin = User.builder()
                .id(1L)
                .username("admin")
                .email("admin@company.com")
                .roles(Set.of(adminRole))
                .active(true)
                .build();

        dealerMinhPhat.setAssignedUser(userSales);

        beerKen = Product.builder()
                .id(100L)
                .code("SP-KEN330")
                .name("Bia Heineken lon 330ml")
                .unit("lon")
                .price(new BigDecimal("20000"))
                .build();

        beerTiger = Product.builder()
                .id(101L)
                .code("SP-TIGER330")
                .name("Bia Tiger lon 330ml")
                .unit("lon")
                .price(new BigDecimal("18000"))
                .build();
    }

    private Order createTestOrder(Long id, String code, Dealer dealer, String status,
                                 BigDecimal qtyKen, BigDecimal deliveredQtyKen,
                                 BigDecimal qtyTiger, BigDecimal deliveredQtyTiger) {
        Order order = Order.builder()
                .id(id)
                .orderCode(code)
                .dealer(dealer)
                .customerName(dealer.getName())
                .status(status)
                .expectedDeliveryDate(LocalDate.now().plusDays(2))
                .actualDeliveryDate("DELIVERED".equals(status) ? LocalDate.now().minusDays(1) : null)
                .totalAmount(new BigDecimal("5000000"))
                .subtotalAmount(new BigDecimal("5000000"))
                .createdAt(LocalDateTime.now())
                .recordedAt(LocalDateTime.now())
                .items(new ArrayList<>())
                .build();

        OrderItem item1 = OrderItem.builder()
                .id(id * 10 + 1)
                .order(order)
                .product(beerKen)
                .unit("thùng")
                .quantity(qtyKen)
                .conversionFactor(new BigDecimal("24"))
                .baseUnit("lon")
                .baseQuantity(qtyKen.multiply(new BigDecimal("24")))
                .price(new BigDecimal("450000"))
                .totalAmount(qtyKen.multiply(new BigDecimal("450000")))
                .deliveredQuantity(deliveredQtyKen)
                .deliveredBaseQuantity(deliveredQtyKen != null ? deliveredQtyKen.multiply(new BigDecimal("24")) : null)
                .deliveryNote(deliveredQtyKen != null && deliveredQtyKen.compareTo(qtyKen) < 0 ? "Thiếu do xe tải đầy" : null)
                .build();

        OrderItem item2 = OrderItem.builder()
                .id(id * 10 + 2)
                .order(order)
                .product(beerTiger)
                .unit("thùng")
                .quantity(qtyTiger)
                .conversionFactor(new BigDecimal("24"))
                .baseUnit("lon")
                .baseQuantity(qtyTiger.multiply(new BigDecimal("24")))
                .price(new BigDecimal("400000"))
                .totalAmount(qtyTiger.multiply(new BigDecimal("400000")))
                .deliveredQuantity(deliveredQtyTiger)
                .deliveredBaseQuantity(deliveredQtyTiger != null ? deliveredQtyTiger.multiply(new BigDecimal("24")) : null)
                .build();

        order.addItem(item1);
        order.addItem(item2);
        return order;
    }

    @Test
    @DisplayName("S5-01: Đại lý chỉ thấy danh sách đơn hàng của chính mình")
    void getOrdersForDealer_OnlyReturnsDealerOwnOrders() {
        when(userRepository.findByUsername("dealer.minhphat")).thenReturn(Optional.of(userDealerMinhPhat));

        Order order1 = createTestOrder(1L, "DH-001", dealerMinhPhat, "RECORDED",
                new BigDecimal("10"), null, new BigDecimal("5"), null);
        Order order2 = createTestOrder(2L, "DH-002", dealerMinhPhat, "DELIVERED",
                new BigDecimal("8"), new BigDecimal("6"), new BigDecimal("4"), new BigDecimal("4"));

        when(orderRepository.findByDealerIdOrderByCreatedAtDesc(1L)).thenReturn(List.of(order1, order2));

        List<OrderResponse> results = orderService.getOrdersForDealer("dealer.minhphat");

        assertThat(results).hasSize(2);
        assertThat(results.get(0).getOrderCode()).isEqualTo("DH-001");
        assertThat(results.get(0).getStatus()).isEqualTo("RECORDED");
        assertThat(results.get(0).getExpectedDeliveryDate()).isNotNull();
        assertThat(results.get(0).getTotalAmount()).isEqualByComparingTo("5000000");

        assertThat(results.get(1).getOrderCode()).isEqualTo("DH-002");
        assertThat(results.get(1).getStatus()).isEqualTo("DELIVERED");
        assertThat(results.get(1).getHasShortage()).isTrue();

        verify(orderRepository).findByDealerIdOrderByCreatedAtDesc(1L);
        verify(orderRepository, never()).findByDealerIdOrderByCreatedAtDesc(2L);
    }

    @Test
    @DisplayName("S5-01: Tài khoản đại lý chưa liên kết Dealer bị từ chối 403")
    void getOrdersForDealer_UserWithoutDealer_ThrowsAccessDenied() {
        User unlinkedDealerUser = User.builder()
                .username("unlinked.dealer")
                .roles(Set.of(new Role(ERole.ROLE_DEALER)))
                .dealer(null)
                .build();

        when(userRepository.findByUsername("unlinked.dealer")).thenReturn(Optional.of(unlinkedDealerUser));

        assertThatThrownBy(() -> orderService.getOrdersForDealer("unlinked.dealer"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("chưa được liên kết với hồ sơ đại lý");
    }

    @Test
    @DisplayName("S5-01: Xem chi tiết đơn hàng tính đúng số lượng thực giao và thiếu khi giao thiếu")
    void getOrderByIdForDealer_WithShortage_CalculatesShortageCorrectly() {
        when(userRepository.findByUsername("dealer.minhphat")).thenReturn(Optional.of(userDealerMinhPhat));

        // Ken đặt 10 thùng, thực giao 7 thùng (thiếu 3 thùng)
        // Tiger đặt 5 thùng, thực giao 5 thùng (giao đủ, thiếu 0)
        Order order = createTestOrder(1L, "DH-001", dealerMinhPhat, "DELIVERED",
                new BigDecimal("10"), new BigDecimal("7"),
                new BigDecimal("5"), new BigDecimal("5"));

        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

        OrderResponse response = orderService.getOrderByIdForDealer(1L, "dealer.minhphat");

        assertThat(response).isNotNull();
        assertThat(response.getOrderCode()).isEqualTo("DH-001");
        assertThat(response.getHasShortage()).isTrue();
        assertThat(response.getItems()).hasSize(2);

        // Kiểm tra dòng 1: Giao thiếu
        var item1 = response.getItems().get(0);
        assertThat(item1.getProductCode()).isEqualTo("SP-KEN330");
        assertThat(item1.getQuantity()).isEqualByComparingTo("10");
        assertThat(item1.getDeliveredQuantity()).isEqualByComparingTo("7");
        assertThat(item1.getShortageQuantity()).isEqualByComparingTo("3");
        assertThat(item1.getIsShortage()).isTrue();
        assertThat(item1.getDeliveryNote()).isEqualTo("Thiếu do xe tải đầy");

        // Kiểm tra dòng 2: Giao đủ
        var item2 = response.getItems().get(1);
        assertThat(item2.getProductCode()).isEqualTo("SP-TIGER330");
        assertThat(item2.getQuantity()).isEqualByComparingTo("5");
        assertThat(item2.getDeliveredQuantity()).isEqualByComparingTo("5");
        assertThat(item2.getShortageQuantity()).isEqualByComparingTo("0");
        assertThat(item2.getIsShortage()).isFalse();
    }

    @Test
    @DisplayName("S5-01: Đại lý A cố xem đơn hàng của Đại lý B bị từ chối 403 Forbidden")
    void getOrderByIdForDealer_AccessOtherDealerOrder_ThrowsAccessDenied() {
        when(userRepository.findByUsername("dealer.minhphat")).thenReturn(Optional.of(userDealerMinhPhat));

        // Đơn của đại lý Tân Bình (dealerId = 2)
        Order tanBinhOrder = createTestOrder(99L, "DH-TANBINH-099", dealerTanBinh, "RECORDED",
                new BigDecimal("5"), null, new BigDecimal("5"), null);

        when(orderRepository.findById(99L)).thenReturn(Optional.of(tanBinhOrder));

        assertThatThrownBy(() -> orderService.getOrderByIdForDealer(99L, "dealer.minhphat"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Bạn không có quyền xem đơn hàng của đại lý khác");
    }

    @Test
    @DisplayName("S5-01: Thủ kho / Quản lý cập nhật tiến trình giao hàng và số lượng thực giao")
    void updateOrderDelivery_UpdatesDeliveredQuantitiesAndStatus() {
        Order order = createTestOrder(5L, "DH-005", dealerMinhPhat, "RECORDED",
                new BigDecimal("20"), null, new BigDecimal("10"), null);

        when(orderRepository.findById(5L)).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OrderDeliveryUpdateRequest request = OrderDeliveryUpdateRequest.builder()
                .status("DELIVERED")
                .actualDeliveryDate(LocalDate.of(2026, 10, 15))
                .deliveryNote("Giao đợt 1 thành công")
                .items(List.of(
                        OrderItemDeliveryRequest.builder()
                                .orderItemId(51L) // ID của item 1
                                .deliveredQuantity(new BigDecimal("15")) // Giao thiếu 5 thùng
                                .note("Kho tạm xuất 15 thùng")
                                .build(),
                        OrderItemDeliveryRequest.builder()
                                .orderItemId(52L) // ID của item 2
                                .deliveredQuantity(new BigDecimal("10")) // Giao đủ
                                .note("Giao đủ 10 thùng")
                                .build()
                ))
                .build();

        OrderResponse updated = orderService.updateOrderDelivery(5L, request, "warehouse.staff");

        assertThat(updated.getStatus()).isEqualTo("DELIVERED");
        assertThat(updated.getActualDeliveryDate()).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(updated.getDeliveryNote()).isEqualTo("Giao đợt 1 thành công");
        assertThat(updated.getHasShortage()).isTrue();

        var item1 = updated.getItems().get(0);
        assertThat(item1.getDeliveredQuantity()).isEqualByComparingTo("15");
        assertThat(item1.getShortageQuantity()).isEqualByComparingTo("5");
        assertThat(item1.getIsShortage()).isTrue();
        assertThat(item1.getDeliveryNote()).isEqualTo("Kho tạm xuất 15 thùng");

        verify(auditLogService).logAction(eq("ORDER"), eq("DH-005"), eq("DELIVERY_UPDATE"),
                eq("RECORDED"), eq("DELIVERED"), anyString(), eq("warehouse.staff"));
    }

    @Test
    @DisplayName("S5-01: Kiểm tra kết nối Ma trận phân quyền - ROLE_DEALER có quyền ORDER_VIEW và ORDER_CREATE")
    void permissionMatrix_DealerRolePermissions_ProperlyConfigured() {
        RolePermissionConfig config = new RolePermissionConfig();

        Set<Permission> dealerPermissions = config.getPermissions(ERole.ROLE_DEALER);
        assertThat(dealerPermissions).contains(Permission.ORDER_VIEW, Permission.ORDER_CREATE);
        assertThat(config.hasPermission(ERole.ROLE_DEALER, Permission.ORDER_VIEW)).isTrue();
        assertThat(config.hasPermission(ERole.ROLE_DEALER, Permission.ORDER_CREATE)).isTrue();

        // Đảm bảo ROLE_DEALER không được phép thao tác kho (tuân thủ bảo mật tốt nhất)
        assertThatThrownBy(() -> config.updateRolePermissions(ERole.ROLE_DEALER, Set.of(Permission.WAREHOUSE_ADJUST)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Đại lý không được phép thao tác kho");
    }
}

