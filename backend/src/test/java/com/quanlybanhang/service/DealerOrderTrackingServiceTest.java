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

    @Mock
    private OrderStatusHistoryRepository orderStatusHistoryRepository;

    @Mock
    private WarehouseVoucherRepository warehouseVoucherRepository;

    @Mock
    private DeliveryTripStopRepository deliveryTripStopRepository;

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
        org.mockito.Mockito.lenient().when(orderRepository.findByIdForUpdate(org.mockito.ArgumentMatchers.anyLong()))
                .thenAnswer(call -> orderRepository.findById(call.getArgument(0)));
        orderService.setUserRepository(userRepository);
        orderService.setOrderStatusHistoryRepository(orderStatusHistoryRepository);
        orderService.setWarehouseVoucherRepository(warehouseVoucherRepository);
        orderService.setDeliveryTripStopRepository(deliveryTripStopRepository);

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
                .hasMessageContaining("chính đại lý mình");
    }

    @Test
    @DisplayName("S5-01: Thủ kho / Quản lý cập nhật tiến trình giao hàng và số lượng thực giao")
    void updateOrderDelivery_UpdatesDeliveredQuantitiesAndStatus() {
        Order order = createTestOrder(5L, "DH-005", dealerMinhPhat, "EXPORTED",
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
                eq("EXPORTED"), eq("DELIVERED"), anyString(), eq("warehouse.staff"));
    }

    @Test
    @DisplayName("S5-01: Kiểm tra kết nối Ma trận phân quyền - ROLE_DEALER có quyền ORDER_VIEW và ORDER_CREATE")
    void permissionMatrix_DealerRolePermissions_ProperlyConfigured() {
        RolePermissionConfig config = new RolePermissionConfig();

        Set<Permission> dealerPermissions = config.getPermissions(ERole.ROLE_DEALER);
        assertThat(dealerPermissions).contains(Permission.ORDER_VIEW, Permission.ORDER_CREATE);
        assertThat(config.hasPermission(ERole.ROLE_DEALER, Permission.ORDER_VIEW)).isTrue();
        assertThat(config.hasPermission(ERole.ROLE_DEALER, Permission.ORDER_CREATE)).isTrue();

        config.updateRolePermissions(ERole.ROLE_DEALER, Set.of());
        assertThat(config.getPermissions(ERole.ROLE_DEALER)).isEmpty();
    }

    // ══════════════════════════════════════════════════════════════════════════════
    // S6-09: THEO DÕI TRẠNG THÁI GIAO HÀNG (5 MỐC & THỰC GIAO TỪNG DÒNG)
    // ══════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("S6-09: Hiển thị đầy đủ 5 mốc thời gian: đã duyệt, đang soạn, đã xuất, đang giao, đã giao")
    void getDealerOrderDeliveryTracking_FiveMilestonesPresentAndOrdered() {
        Order order = createTestOrder(55L, "DH-S609-001", dealerMinhPhat, "DELIVERED",
                new BigDecimal("10"), new BigDecimal("8"), new BigDecimal("5"), new BigDecimal("5"));
        order.setApprovedAt(LocalDateTime.of(2026, 10, 20, 8, 30, 0));
        order.setApprovedBy("qlkd.demo");
        order.setActualDeliveryDate(LocalDate.of(2026, 10, 22));

        when(orderRepository.findById(55L)).thenReturn(Optional.of(order));

        // Lịch sử soạn hàng (PICKING)
        OrderStatusHistory pickHist = OrderStatusHistory.builder()
                .order(order)
                .previousStatus("APPROVED")
                .newStatus("PICKING")
                .statusDisplayName("Đang soạn hàng")
                .changedBy("staff.kho")
                .changedAt(LocalDateTime.of(2026, 10, 20, 14, 0, 0))
                .build();
        when(orderStatusHistoryRepository.findByOrderIdOrderByChangedAtAsc(55L))
                .thenReturn(List.of(pickHist));

        // Phiếu xuất kho (EXPORTED)
        WarehouseVoucher voucher = WarehouseVoucher.builder()
                .id(101L)
                .voucherCode("PXK-20261021-001")
                .recordedAt(LocalDateTime.of(2026, 10, 21, 9, 15, 0))
                .build();
        when(warehouseVoucherRepository.findByShipmentOrderIdOrderByIdAsc(55L))
                .thenReturn(List.of(voucher));

        // Chuyến giao hàng (SHIPPING, DELIVERED)
        DeliveryTrip trip = DeliveryTrip.builder()
                .id(201L)
                .tripCode("TRIP-20261022-01")
                .driverName("Nguyễn Văn Tài")
                .driverPhone("0909123456")
                .vehiclePlate("29C-888.99")
                .departureTime(LocalDateTime.of(2026, 10, 22, 7, 0, 0))
                .expectedCompletionTime(LocalDateTime.of(2026, 10, 22, 11, 30, 0))
                .actualCompletionTime(LocalDateTime.of(2026, 10, 22, 10, 45, 0))
                .status(DeliveryTripStatus.COMPLETED)
                .build();

        DeliveryTripStop stop = DeliveryTripStop.builder()
                .id(301L)
                .trip(trip)
                .order(order)
                .orderCode(order.getOrderCode())
                .stopOrder(1)
                .status(DeliveryStopStatus.DELIVERED)
                .deliveredAt(LocalDateTime.of(2026, 10, 22, 10, 15, 0))
                .receiverName("Trần Thị Nhận")
                .receiverPhone("0988777666")
                .deliveryAddress("123 Lê Lợi, Q.1, TP.HCM")
                .build();
        when(deliveryTripStopRepository.findByOrderIdOrderByIdDesc(55L))
                .thenReturn(List.of(stop));

        var response = orderService.getDealerOrderDeliveryTracking(55L, 1L);

        assertThat(response).isNotNull();
        assertThat(response.getOrderId()).isEqualTo(55L);
        assertThat(response.getOrderCode()).isEqualTo("DH-S609-001");
        assertThat(response.getDealerId()).isEqualTo(1L);

        // Kiểm tra 5 mốc thời gian cốt lõi
        assertThat(response.getApprovedAt()).isEqualTo(LocalDateTime.of(2026, 10, 20, 8, 30, 0));
        assertThat(response.getPickingAt()).isEqualTo(LocalDateTime.of(2026, 10, 20, 14, 0, 0));
        assertThat(response.getExportedAt()).isEqualTo(LocalDateTime.of(2026, 10, 21, 9, 15, 0));
        assertThat(response.getShippingAt()).isEqualTo(LocalDateTime.of(2026, 10, 22, 7, 0, 0));
        assertThat(response.getDeliveredAt()).isEqualTo(LocalDateTime.of(2026, 10, 22, 10, 15, 0));

        // Kiểm tra danh sách 5 mốc theo thứ tự chuẩn
        assertThat(response.getMilestones()).hasSize(5);
        assertThat(response.getMilestones().get(0).getMilestoneKey()).isEqualTo("approved");
        assertThat(response.getMilestones().get(0).getLabel()).isEqualTo("Đã duyệt");
        assertThat(response.getMilestones().get(0).getTimestamp()).isNotNull();

        assertThat(response.getMilestones().get(1).getMilestoneKey()).isEqualTo("picking");
        assertThat(response.getMilestones().get(1).getLabel()).isEqualTo("Đang soạn");
        assertThat(response.getMilestones().get(1).getTimestamp()).isNotNull();

        assertThat(response.getMilestones().get(2).getMilestoneKey()).isEqualTo("exported");
        assertThat(response.getMilestones().get(2).getLabel()).isEqualTo("Đã xuất");
        assertThat(response.getMilestones().get(2).getTimestamp()).isNotNull();

        assertThat(response.getMilestones().get(3).getMilestoneKey()).isEqualTo("shipping");
        assertThat(response.getMilestones().get(3).getLabel()).isEqualTo("Đang giao");
        assertThat(response.getMilestones().get(3).getTimestamp()).isNotNull();

        assertThat(response.getMilestones().get(4).getMilestoneKey()).isEqualTo("delivered");
        assertThat(response.getMilestones().get(4).getLabel()).isEqualTo("Đã giao");
        assertThat(response.getMilestones().get(4).getTimestamp()).isNotNull();

        // Kiểm tra thông tin chuyến xe & tài xế giúp đại lý chủ động bố trí người nhận
        assertThat(response.getDeliveryTrip()).isNotNull();
        assertThat(response.getDeliveryTrip().getDriverName()).isEqualTo("Nguyễn Văn Tài");
        assertThat(response.getDeliveryTrip().getDriverPhone()).isEqualTo("0909123456");
        assertThat(response.getDeliveryTrip().getVehiclePlate()).isEqualTo("29C-888.99");
        assertThat(response.getDeliveryTrip().getDepartureTime()).isEqualTo(LocalDateTime.of(2026, 10, 22, 7, 0, 0));
        assertThat(response.getDeliveryTrip().getExpectedCompletionTime()).isEqualTo(LocalDateTime.of(2026, 10, 22, 11, 30, 0));
        assertThat(response.getDeliveryTrip().getReceiverName()).isEqualTo("Trần Thị Nhận");
        assertThat(response.getDeliveryTrip().getDeliveryAddress()).isEqualTo("123 Lê Lợi, Q.1, TP.HCM");

        // Kiểm tra số lượng thực giao từng dòng hàng
        assertThat(response.getItems()).hasSize(2);
        var item1 = response.getItems().get(0);
        assertThat(item1.getQuantity()).isEqualByComparingTo("10");
        assertThat(item1.getDeliveredQuantity()).isEqualByComparingTo("8");
        assertThat(item1.getShortageQuantity()).isEqualByComparingTo("2");
        assertThat(item1.getIsShortage()).isTrue();

        var item2 = response.getItems().get(1);
        assertThat(item2.getQuantity()).isEqualByComparingTo("5");
        assertThat(item2.getDeliveredQuantity()).isEqualByComparingTo("5");
        assertThat(item2.getShortageQuantity()).isEqualByComparingTo("0");
        assertThat(item2.getIsShortage()).isFalse();
    }

    @Test
    @DisplayName("S6-09: Đại lý không thể xem theo dõi giao hàng của đại lý khác -> 403 AccessDenied")
    void getDealerOrderDeliveryTracking_DifferentDealer_ThrowsAccessDenied() {
        Order order = createTestOrder(55L, "DH-S609-001", dealerMinhPhat, "DELIVERED",
                new BigDecimal("10"), new BigDecimal("10"), new BigDecimal("5"), new BigDecimal("5"));

        when(orderRepository.findById(55L)).thenReturn(Optional.of(order));

        // Đại lý Tân Bình (ID 2L) cố tình truy cập đơn của Đại lý Minh Phát (ID 1L)
        assertThatThrownBy(() -> orderService.getDealerOrderDeliveryTracking(55L, 2L))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Đơn hàng không thuộc đại lý đang chọn");
    }
}
