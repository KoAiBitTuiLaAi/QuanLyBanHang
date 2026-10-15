package com.quanlybanhang.service;

import com.quanlybanhang.dto.request.OrderFilterRequest;
import com.quanlybanhang.dto.response.OrderFilterResult;
import com.quanlybanhang.dto.response.OrderResponse;
import com.quanlybanhang.model.Dealer;
import com.quanlybanhang.model.Order;
import com.quanlybanhang.model.Territory;
import com.quanlybanhang.model.User;
import com.quanlybanhang.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("OrderServiceImpl - Lọc đơn hàng & Phân quyền dữ liệu (S4-07)")
class OrderFilterTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private UserRepository userRepository;

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

    @InjectMocks
    private OrderServiceImpl orderService;

    private User sales1;
    private User sales2;
    private Dealer dealer1;
    private Dealer dealer2;
    private Territory north;
    private Territory south;
    private Order order1;
    private Order order2;
    private Order order3;

    @BeforeEach
    void setUp() {
        orderService.setUserRepository(userRepository);
        north = Territory.builder().id(101L).code("MB").name("Miền Bắc").build();
        south = Territory.builder().id(102L).code("MN").name("Miền Nam").build();

        sales1 = User.builder().id(11L).username("sales01").fullName("Nhân viên 01").build();
        sales2 = User.builder().id(12L).username("sales02").fullName("Nhân viên 02").build();

        dealer1 = Dealer.builder()
                .id(1L)
                .code("DL01")
                .name("Đại lý Bắc Thăng Long")
                .assignedUser(sales1)
                .territory(north)
                .build();

        dealer2 = Dealer.builder()
                .id(2L)
                .code("DL02")
                .name("Đại lý Nam Sài Gòn")
                .assignedUser(sales2)
                .territory(south)
                .build();

        order1 = Order.builder()
                .id(1001L)
                .orderCode("DH-MB-001")
                .dealer(dealer1)
                .status("RECORDED")
                .totalAmount(new BigDecimal("15000000"))
                .createdAt(LocalDateTime.of(2026, 10, 10, 9, 0))
                .createdBy("sales01")
                .build();

        order2 = Order.builder()
                .id(1002L)
                .orderCode("DH-MB-002")
                .dealer(dealer1)
                .status("PENDING_APPROVAL")
                .totalAmount(new BigDecimal("25000000"))
                .createdAt(LocalDateTime.of(2026, 10, 12, 14, 0))
                .createdBy("sales01")
                .build();

        order3 = Order.builder()
                .id(1003L)
                .orderCode("DH-MN-003")
                .dealer(dealer2)
                .status("RECORDED")
                .totalAmount(new BigDecimal("40000000"))
                .createdAt(LocalDateTime.of(2026, 10, 14, 16, 30))
                .createdBy("sales02")
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("Quản lý kinh doanh xem danh sách đơn: tính đúng tổng tiền của tập kết quả đang lọc")
    void getOrders_AsSalesManager_CalculatesTotalAmount() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("manager", "pass",
                        List.of(new SimpleGrantedAuthority("ROLE_SALES_MANAGER"), new SimpleGrantedAuthority("ORDER_VIEW")))
        );

        when(orderRepository.findAll(any(Specification.class), any(Sort.class)))
                .thenReturn(List.of(order1, order2, order3));

        OrderFilterRequest filter = OrderFilterRequest.builder()
                .status("RECORDED")
                .build();

        OrderFilterResult result = orderService.getOrders(filter);

        assertThat(result).isNotNull();
        assertThat(result.getOrders()).hasSize(3);
        // 15,000,000 + 25,000,000 + 40,000,000 = 80,000,000
        assertThat(result.getTotalAmount()).isEqualByComparingTo("80000000");
        assertThat(result.getTotalOrders()).isEqualTo(3L);

        // Kiểm tra thông tin làm giàu cho màn hình quản lý
        OrderResponse first = result.getOrders().get(0);
        assertThat(first.getDealerCode()).isEqualTo("DL01");
        assertThat(first.getDealerName()).isEqualTo("Đại lý Bắc Thăng Long");
        assertThat(first.getSalesUserId()).isEqualTo(11L);
        assertThat(first.getSalesUserName()).isEqualTo("sales01");
        assertThat(first.getTerritoryName()).isEqualTo("Miền Bắc");
    }

    @Test
    @DisplayName("Nhân viên kinh doanh (ROLE_SALES) chỉ thấy đơn của đại lý do mình phụ trách")
    void getOrders_AsSalesStaff_ScopedToAssignedDealer() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("sales01", "pass",
                        List.of(new SimpleGrantedAuthority("ROLE_SALES"), new SimpleGrantedAuthority("ORDER_VIEW")))
        );

        when(userRepository.findByUsername("sales01")).thenReturn(Optional.of(sales1));
        // Giả lập DB trả về chỉ các đơn của đại lý sales01 phụ trách sau khi áp dụng Spec
        when(orderRepository.findAll(any(Specification.class), any(Sort.class)))
                .thenReturn(List.of(order1, order2));

        OrderFilterRequest filter = new OrderFilterRequest();
        OrderFilterResult result = orderService.getOrders(filter);

        assertThat(result.getOrders()).hasSize(2);
        assertThat(result.getOrders()).extracting(OrderResponse::getOrderCode)
                .containsExactly("DH-MB-001", "DH-MB-002");
        assertThat(result.getTotalAmount()).isEqualByComparingTo("40000000");
        assertThat(result.getTotalOrders()).isEqualTo(2L);
    }

    @Test
    @DisplayName("Nhân viên kinh doanh xem chi tiết đơn của đại lý khác -> 403 AccessDeniedException")
    void getOrderById_AsSalesStaff_OtherDealer_ThrowsAccessDenied() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("sales01", "pass",
                        List.of(new SimpleGrantedAuthority("ROLE_SALES"), new SimpleGrantedAuthority("ORDER_VIEW")))
        );

        when(orderRepository.findById(1003L)).thenReturn(Optional.of(order3)); // Đơn của dealer2 (sales02 phụ trách)

        assertThatThrownBy(() -> orderService.getOrderById(1003L, "sales01"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Bạn chỉ có quyền xem đơn hàng của đại lý do mình phụ trách");
    }

    @Test
    @DisplayName("Nhân viên kinh doanh xem chi tiết đơn của chính đại lý mình -> Thành công")
    void getOrderById_AsSalesStaff_OwnDealer_Success() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("sales01", "pass",
                        List.of(new SimpleGrantedAuthority("ROLE_SALES"), new SimpleGrantedAuthority("ORDER_VIEW")))
        );

        when(orderRepository.findById(1001L)).thenReturn(Optional.of(order1));

        OrderResponse response = orderService.getOrderById(1001L, "sales01");

        assertThat(response).isNotNull();
        assertThat(response.getOrderCode()).isEqualTo("DH-MB-001");
        assertThat(response.getDealerCode()).isEqualTo("DL01");
        assertThat(response.getSalesUserName()).isEqualTo("sales01");
    }

    @Test
    @DisplayName("Quản lý kinh doanh xem chi tiết bất kỳ đơn hàng nào -> Thành công")
    void getOrderById_AsSalesManager_AnyOrder_Success() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("manager", "pass",
                        List.of(new SimpleGrantedAuthority("ROLE_SALES_MANAGER"), new SimpleGrantedAuthority("ORDER_VIEW")))
        );

        when(orderRepository.findById(1003L)).thenReturn(Optional.of(order3));

        OrderResponse response = orderService.getOrderById(1003L, "manager");

        assertThat(response).isNotNull();
        assertThat(response.getOrderCode()).isEqualTo("DH-MN-003");
        assertThat(response.getDealerCode()).isEqualTo("DL02");
    }
}
