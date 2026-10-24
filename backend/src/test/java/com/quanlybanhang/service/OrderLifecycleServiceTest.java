package com.quanlybanhang.service;

import com.quanlybanhang.dto.response.OrderResponse;
import com.quanlybanhang.dto.response.OrderTrackingResponse;
import com.quanlybanhang.exception.OrderCannotBeCancelledException;
import com.quanlybanhang.model.*;
import com.quanlybanhang.repository.*;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Story S4-06: Vòng đời đơn hàng, huỷ đơn có lý do và bảo vệ đơn đã xuất kho")
class OrderLifecycleServiceTest {

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
    private WarehouseRepository warehouseRepository;

    @Mock
    private WarehouseInventoryRepository inventoryRepository;

    @Mock
    private OrderStatusHistoryRepository orderStatusHistoryRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private OrderServiceImpl orderService;

    private Warehouse warehouse;
    private Product product;
    private Dealer dealer;
    private User salesUser;
    private User otherSalesUser;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(orderRepository.findByIdForUpdate(org.mockito.ArgumentMatchers.anyLong()))
                .thenAnswer(call -> orderRepository.findById(call.getArgument(0)));
        warehouse = Warehouse.builder()
                .id(1L)
                .code("KHO-TONG")
                .name("Kho Tổng Hà Nội")
                .active(true)
                .build();

        product = Product.builder()
                .id(10L)
                .code("SP-KEN330")
                .name("Bia Heineken Sleek lon 330ml")
                .unit("lon")
                .price(new BigDecimal("19000"))
                .build();

        salesUser = User.builder()
                .id(101L)
                .username("sales01")
                .fullName("Nguyễn Văn Bán Hàng")
                .build();

        otherSalesUser = User.builder()
                .id(102L)
                .username("sales02")
                .fullName("Trần Văn Sales Khác")
                .build();

        dealer = Dealer.builder()
                .id(20L)
                .code("DL-HAIDANG")
                .name("Đại lý Hải Đăng")
                .customerGroup(CustomerGroup.DAI_LY_CAP_1)
                .assignedUser(salesUser)
                .active(true)
                .locked(false)
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Huỷ đơn: Bắt buộc nhập lý do (null hoặc rỗng trả lỗi IllegalArgumentException)")
    void cancelOrder_WithoutReason_ThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> orderService.cancelOrder(1L, null, "sales01"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Lý do huỷ đơn hàng không được để trống");

        assertThatThrownBy(() -> orderService.cancelOrder(1L, "   ", "sales01"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Lý do huỷ đơn hàng không được để trống");

        verify(orderRepository, never()).save(any());
    }

    @Test
    @DisplayName("Huỷ đơn hợp lệ: Cập nhật CANCELLED, lưu lý do, người huỷ và nhả tồn kho giữ chỗ")
    void cancelOrder_ValidReason_ReleasesReservedStockAndSavesMetadata() {
        OrderItem item = OrderItem.builder()
                .id(1L)
                .product(product)
                .unit("lon")
                .quantity(new BigDecimal("100"))
                .baseQuantity(new BigDecimal("100"))
                .price(new BigDecimal("19000"))
                .subtotalAmount(new BigDecimal("1900000"))
                .totalAmount(new BigDecimal("1900000"))
                .build();

        Order order = Order.builder()
                .id(1L)
                .orderCode("DH-2026-001")
                .status("RECORDED")
                .dealer(dealer)
                .warehouse(warehouse)
                .inventoryReserved(true)
                .createdBy("sales01")
                .items(new ArrayList<>(List.of(item)))
                .build();

        WarehouseInventory inventory = WarehouseInventory.builder()
                .id(1L)
                .warehouse(warehouse)
                .product(product)
                .baseQuantity(new BigDecimal("1000"))
                .reservedQuantity(new BigDecimal("100"))
                .baseUnit("lon")
                .build();

        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));
        when(inventoryRepository.findByWarehouseIdAndProductIdInForUpdate(1L, List.of(10L))).thenReturn(List.of(inventory));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        OrderResponse response = orderService.cancelOrder(1L, "Khách đổi ý sang dòng sản phẩm khác", "sales01");

        assertThat(response.getStatus()).isEqualTo("CANCELLED");
        assertThat(response.getCancelReason()).isEqualTo("Khách đổi ý sang dòng sản phẩm khác");
        assertThat(response.getCancelledBy()).isEqualTo("sales01");
        assertThat(response.getInventoryReserved()).isFalse();

        // Tồn giữ chỗ phải được trừ đi 100 lon
        assertThat(inventory.getReservedQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
        verify(inventoryRepository).save(inventory);
        verify(orderStatusHistoryRepository).save(any(OrderStatusHistory.class));
        verify(auditLogService).logAction(eq("INVOICE"), eq("DH-2026-001"), eq("CANCEL"), eq("RECORDED"),
                contains("Khách đổi ý sang dòng sản phẩm khác"), eq("sales01"));
    }

    @Test
    @DisplayName("Đơn đã xuất kho (EXPORTED) thì KHÔNG huỷ được -> ném OrderCannotBeCancelledException")
    void cancelOrder_WhenAlreadyExported_ThrowsOrderCannotBeCancelledException() {
        Order exportedOrder = Order.builder()
                .id(2L)
                .orderCode("DH-EXPORTED")
                .status("EXPORTED")
                .dealer(dealer)
                .warehouse(warehouse)
                .inventoryReserved(false)
                .createdBy("sales01")
                .build();

        when(orderRepository.findById(2L)).thenReturn(Optional.of(exportedOrder));

        assertThatThrownBy(() -> orderService.cancelOrder(2L, "Khách huỷ", "sales01"))
                .isInstanceOf(OrderCannotBeCancelledException.class)
                .hasMessage("Đơn đã xuất kho thì không huỷ được, phải đi đường trả hàng");

        verify(orderRepository, never()).save(any());
        verify(inventoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("Đơn đã giao (DELIVERED) hoặc đã đóng (CLOSED) thì KHÔNG huỷ được")
    void cancelOrder_WhenDeliveredOrClosed_ThrowsOrderCannotBeCancelledException() {
        Order deliveredOrder = Order.builder()
                .id(3L)
                .orderCode("DH-DELIVERED")
                .status("DELIVERED")
                .dealer(dealer)
                .createdBy("sales01")
                .build();

        Order closedOrder = Order.builder()
                .id(4L)
                .orderCode("DH-CLOSED")
                .status("CLOSED")
                .dealer(dealer)
                .createdBy("sales01")
                .build();

        when(orderRepository.findById(3L)).thenReturn(Optional.of(deliveredOrder));
        when(orderRepository.findById(4L)).thenReturn(Optional.of(closedOrder));

        assertThatThrownBy(() -> orderService.cancelOrder(3L, "Huỷ", "sales01"))
                .isInstanceOf(OrderCannotBeCancelledException.class)
                .hasMessage("Đơn đã xuất kho thì không huỷ được, phải đi đường trả hàng");

        assertThatThrownBy(() -> orderService.cancelOrder(4L, "Huỷ", "sales01"))
                .isInstanceOf(OrderCannotBeCancelledException.class)
                .hasMessage("Đơn đã xuất kho thì không huỷ được, phải đi đường trả hàng");
    }

    @Test
    @DisplayName("Cập nhật trạng thái: Sang EXPORTED sẽ trừ tồn kho thực tế và nhả giữ chỗ")
    void updateOrderStatus_ToExported_DeductsActualStockAndClearsReservation() {
        OrderItem item = OrderItem.builder()
                .id(1L)
                .product(product)
                .unit("lon")
                .quantity(new BigDecimal("50"))
                .baseQuantity(new BigDecimal("50"))
                .build();

        Order pickingOrder = Order.builder()
                .id(5L)
                .orderCode("DH-PICKING")
                .status("PICKING")
                .warehouse(warehouse)
                .inventoryReserved(true)
                .items(new ArrayList<>(List.of(item)))
                .build();

        WarehouseInventory inventory = WarehouseInventory.builder()
                .id(1L)
                .warehouse(warehouse)
                .product(product)
                .baseQuantity(new BigDecimal("500"))
                .reservedQuantity(new BigDecimal("50"))
                .baseUnit("lon")
                .build();

        when(orderRepository.findById(5L)).thenReturn(Optional.of(pickingOrder));
        when(inventoryRepository.findByWarehouseIdAndProductIdInForUpdate(1L, List.of(10L))).thenReturn(List.of(inventory));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        OrderResponse response = orderService.updateOrderStatus(5L, "EXPORTED", "Đã bàn giao đơn vị vận chuyển", "warehouse01");

        assertThat(response.getStatus()).isEqualTo("EXPORTED");
        assertThat(pickingOrder.getInventoryReserved()).isFalse();

        // Tồn thực tế giảm 50: 500 - 50 = 450 lon
        assertThat(inventory.getBaseQuantity()).isEqualByComparingTo(new BigDecimal("450"));
        // Tồn giữ chỗ giảm 50: 50 - 50 = 0 lon
        assertThat(inventory.getReservedQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
        verify(inventoryRepository).save(inventory);
        verify(orderStatusHistoryRepository).save(any(OrderStatusHistory.class));
    }

    @Test
    @DisplayName("Theo dõi vòng đời: Trả về đầy đủ các bước chuẩn và trạng thái từng bước")
    void getOrderTracking_ReturnsFullLifecycleStepsAndHistory() {
        Order order = Order.builder()
                .id(10L)
                .orderCode("DH-TRACK-01")
                .status("PICKING")
                .dealer(dealer)
                .warehouse(warehouse)
                .inventoryReserved(true)
                .requiresApproval(false)
                .createdBy("sales01")
                .createdAt(LocalDateTime.of(2026, 10, 10, 8, 0))
                .recordedAt(LocalDateTime.of(2026, 10, 10, 8, 30))
                .totalAmount(new BigDecimal("5000000"))
                .build();

        OrderStatusHistory h1 = OrderStatusHistory.builder()
                .id(1L)
                .order(order)
                .previousStatus(null)
                .newStatus("RECORDED")
                .statusDisplayName("Đã duyệt")
                .changedBy("sales01")
                .changedAt(LocalDateTime.of(2026, 10, 10, 8, 30))
                .build();

        OrderStatusHistory h2 = OrderStatusHistory.builder()
                .id(2L)
                .order(order)
                .previousStatus("RECORDED")
                .newStatus("PICKING")
                .statusDisplayName("Đang soạn hàng")
                .changedBy("warehouse01")
                .changedAt(LocalDateTime.of(2026, 10, 10, 9, 0))
                .build();

        when(orderRepository.findById(10L)).thenReturn(Optional.of(order));
        when(orderStatusHistoryRepository.findByOrderIdOrderByChangedAtAsc(10L)).thenReturn(List.of(h1, h2));

        OrderTrackingResponse tracking = orderService.getOrderTracking(10L, "sales01");

        assertThat(tracking.getOrderId()).isEqualTo(10L);
        assertThat(tracking.getCurrentStatus()).isEqualTo("PICKING");
        assertThat(tracking.getCurrentStatusDisplayName()).isEqualTo("Đang soạn hàng");
        assertThat(tracking.isCanCancel()).isTrue();
        assertThat(tracking.isExported()).isFalse();
        assertThat(tracking.getSteps()).hasSize(7);

        // Bước 1 DRAFT: COMPLETED
        assertThat(tracking.getSteps().get(0).getStatus()).isEqualTo("DRAFT");
        // Bước 2 PENDING_APPROVAL: SKIPPED (vì đơn không cần duyệt)
        assertThat(tracking.getSteps().get(1).getStatus()).isEqualTo("PENDING_APPROVAL");
        assertThat(tracking.getSteps().get(1).getState()).isEqualTo("SKIPPED");
        // Bước 3 APPROVED: COMPLETED
        assertThat(tracking.getSteps().get(2).getStatus()).isEqualTo("APPROVED");
        assertThat(tracking.getSteps().get(2).getState()).isEqualTo("COMPLETED");
        // Bước 4 PICKING: CURRENT
        assertThat(tracking.getSteps().get(3).getStatus()).isEqualTo("PICKING");
        assertThat(tracking.getSteps().get(3).getState()).isEqualTo("CURRENT");
        // Bước 5 EXPORTED: UPCOMING
        assertThat(tracking.getSteps().get(4).getStatus()).isEqualTo("EXPORTED");
        assertThat(tracking.getSteps().get(4).getState()).isEqualTo("UPCOMING");

        assertThat(tracking.getHistory()).hasSize(2);
    }

    @Test
    @DisplayName("Theo dõi đơn bị huỷ: Có nhánh Huỷ và giữ đúng bước dừng trước khi huỷ")
    void getOrderTracking_CancelledOrder_ReturnsCancellationBranch() {
        Order order = Order.builder()
                .id(11L)
                .orderCode("DH-TRACK-CANCELLED")
                .status("CANCELLED")
                .dealer(dealer)
                .warehouse(warehouse)
                .inventoryReserved(false)
                .requiresApproval(false)
                .createdBy("sales01")
                .createdAt(LocalDateTime.of(2026, 10, 10, 8, 0))
                .cancelReason("Khách đổi kế hoạch")
                .cancelledBy("sales01")
                .cancelledAt(LocalDateTime.of(2026, 10, 10, 8, 40))
                .build();

        OrderStatusHistory approved = OrderStatusHistory.builder()
                .id(11L)
                .order(order)
                .previousStatus("PENDING_APPROVAL")
                .newStatus("RECORDED")
                .statusDisplayName("Đã duyệt")
                .changedBy("sales01")
                .changedAt(LocalDateTime.of(2026, 10, 10, 8, 30))
                .build();
        OrderStatusHistory cancelled = OrderStatusHistory.builder()
                .id(12L)
                .order(order)
                .previousStatus("RECORDED")
                .newStatus("CANCELLED")
                .statusDisplayName("Huỷ")
                .note("Huỷ đơn hàng. Lý do: Khách đổi kế hoạch")
                .changedBy("sales01")
                .changedAt(order.getCancelledAt())
                .build();

        when(orderRepository.findById(11L)).thenReturn(Optional.of(order));
        when(orderStatusHistoryRepository.findByOrderIdOrderByChangedAtAsc(11L))
                .thenReturn(List.of(approved, cancelled));

        OrderTrackingResponse tracking = orderService.getOrderTracking(11L, "sales01");

        assertThat(tracking.getCurrentStep()).isEqualTo(8);
        assertThat(tracking.getSteps()).hasSize(8);
        assertThat(tracking.getSteps().get(2).getState()).isEqualTo("COMPLETED");
        assertThat(tracking.getSteps().get(3).getState()).isEqualTo("SKIPPED");
        assertThat(tracking.getSteps().get(7).getStatus()).isEqualTo("CANCELLED");
        assertThat(tracking.getSteps().get(7).getState()).isEqualTo("CANCELLED");
        assertThat(tracking.getSteps().get(7).getNote()).contains("Khách đổi kế hoạch");
    }

    @Test
    @DisplayName("Cập nhật giao hàng không được bỏ qua bước Đang soạn và Đã xuất")
    void updateOrderDelivery_FromRecordedOrder_ThrowsLifecycleError() {
        Order order = Order.builder()
                .id(12L)
                .orderCode("DH-DELIVERY-SKIP")
                .status("RECORDED")
                .warehouse(warehouse)
                .build();
        when(orderRepository.findById(12L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.updateOrderDelivery(
                        12L,
                        com.quanlybanhang.dto.request.OrderDeliveryUpdateRequest.builder()
                                .status("DELIVERED")
                                .build(),
                        "warehouse01"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bước kế tiếp");

        verify(orderRepository, never()).save(any());
    }

    @Test
    @DisplayName("Bảo mật RLS: SALES không được xem hoặc huỷ đơn thuộc đại lý không phụ trách và không do mình tạo")
    void salesUser_CannotAccessOtherSalesOrder_ThrowsAccessDeniedException() {
        // Thiết lập SecurityContext giả lập ROLE_SALES cho sales02
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                "sales02", "password", List.of(new SimpleGrantedAuthority("ROLE_SALES")));
        SecurityContextHolder.getContext().setAuthentication(auth);

        Dealer unassignedDealer = Dealer.builder()
                .id(99L)
                .code("DL-OTHER")
                .name("Đại lý Khác")
                .assignedUser(salesUser) // Phụ trách bởi sales01
                .build();

        Order order = Order.builder()
                .id(88L)
                .orderCode("DH-OTHER")
                .status("RECORDED")
                .dealer(unassignedDealer)
                .createdBy("sales01") // Tạo bởi sales01
                .build();

        when(userRepository.findByUsername("sales02")).thenReturn(Optional.of(otherSalesUser));
        when(orderRepository.findById(88L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> orderService.getOrderTracking(88L, "sales02"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Bạn chỉ có quyền theo dõi đơn hàng do mình tạo hoặc thuộc đại lý do mình phụ trách");

        assertThatThrownBy(() -> orderService.cancelOrder(88L, "Lý do huỷ", "sales02"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Bạn chỉ có quyền huỷ đơn hàng do mình tạo hoặc thuộc đại lý do mình phụ trách");
    }
}
