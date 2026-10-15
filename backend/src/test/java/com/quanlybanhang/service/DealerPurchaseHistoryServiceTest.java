package com.quanlybanhang.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanlybanhang.dto.response.DealerPurchaseHistoryResponse;
import com.quanlybanhang.exception.DealerLockedException;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DealerPurchaseHistoryServiceTest {

    @Mock
    private DealerRepository dealerRepository;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private TerritoryRepository territoryRepository;

    @Mock
    private DealerAssignmentHistoryRepository dealerAssignmentHistoryRepository;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private DealerServiceImpl dealerService;

    private Dealer dealer;
    private User salesUser;
    private User otherSalesUser;
    private Product productA;
    private Product productB;

    @BeforeEach
    void setUp() {
        salesUser = User.builder()
                .id(10L)
                .username("sales01")
                .fullName("Nhân viên Sales 01")
                .active(true)
                .build();

        otherSalesUser = User.builder()
                .id(20L)
                .username("sales02")
                .fullName("Nhân viên Sales 02")
                .active(true)
                .build();

        dealer = Dealer.builder()
                .id(1L)
                .code("DL-TEST-01")
                .name("Đại lý Bia Sài Gòn")
                .assignedUser(salesUser)
                .active(true)
                .locked(false)
                .customerGroup(CustomerGroup.DAI_LY_CAP_1)
                .build();

        productA = Product.builder()
                .id(101L)
                .code("HEINEKEN-LON")
                .name("Bia Heineken Lon 330ml")
                .unit("thùng")
                .build();

        productB = Product.builder()
                .id(102L)
                .code("TIGER-LON")
                .name("Bia Tiger Lon 330ml")
                .unit("thùng")
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate(String username, String role) {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                username, "password", List.of(new SimpleGrantedAuthority(role)));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    @DisplayName("S4-04: Lấy lịch sử mua hàng 3 tháng gần nhất tính đúng số lượng bình quân")
    void testGetDealerPurchaseHistorySuccess() {
        authenticate("sales01", "ROLE_SALES");
        when(dealerRepository.findById(1L)).thenReturn(Optional.of(dealer));
        when(userRepository.findByUsername("sales01")).thenReturn(Optional.of(salesUser));

        // Đơn 1 (Gần nhất, 5 ngày trước): Mua 10 thùng Heineken và 5 thùng Tiger
        Order order1 = Order.builder()
                .id(1001L)
                .orderCode("DH-001")
                .dealer(dealer)
                .status("RECORDED")
                .createdAt(LocalDateTime.now().minusDays(5))
                .recordedAt(LocalDateTime.now().minusDays(5))
                .build();
        OrderItem item1A = OrderItem.builder()
                .id(1L)
                .order(order1)
                .product(productA)
                .unit("thùng")
                .quantity(new BigDecimal("10"))
                .conversionFactor(new BigDecimal("24"))
                .baseQuantity(new BigDecimal("240"))
                .build();
        OrderItem item1B = OrderItem.builder()
                .id(2L)
                .order(order1)
                .product(productB)
                .unit("thùng")
                .quantity(new BigDecimal("5"))
                .conversionFactor(new BigDecimal("24"))
                .baseQuantity(new BigDecimal("120"))
                .build();
        order1.setItems(List.of(item1A, item1B));

        // Đơn 2 (20 ngày trước): Mua 20 thùng Heineken
        Order order2 = Order.builder()
                .id(1002L)
                .orderCode("DH-002")
                .dealer(dealer)
                .status("RECORDED")
                .createdAt(LocalDateTime.now().minusDays(20))
                .recordedAt(LocalDateTime.now().minusDays(20))
                .build();
        OrderItem item2A = OrderItem.builder()
                .id(3L)
                .order(order2)
                .product(productA)
                .unit("thùng")
                .quantity(new BigDecimal("20"))
                .conversionFactor(new BigDecimal("24"))
                .baseQuantity(new BigDecimal("480"))
                .build();
        order2.setItems(List.of(item2A));

        when(orderRepository.findByDealerIdAndStatusNotInOrderByCreatedAtDesc(eq(1L), anyCollection()))
                .thenReturn(List.of(order1, order2));
        when(orderRepository.findPurchasedOrdersInPeriod(eq(1L), anyCollection(), any(LocalDateTime.class)))
                .thenReturn(List.of(order1, order2));

        List<DealerPurchaseHistoryResponse> history = dealerService.getDealerPurchaseHistory(1L, 3, false);

        assertNotNull(history);
        assertEquals(2, history.size());

        // Heineken: Mua 2 lần (10 + 20 = 30 thùng), bình quân 30/2 = 15 thùng/đơn
        DealerPurchaseHistoryResponse heinekenResp = history.stream()
                .filter(h -> h.getProductId().equals(101L))
                .findFirst().orElseThrow();
        assertEquals(new BigDecimal("15"), heinekenResp.getAvgQty());
        assertEquals(2, heinekenResp.getOrderCount());
        assertEquals(new BigDecimal("30"), heinekenResp.getTotalQuantity());
        assertEquals("thùng", heinekenResp.getUnit());
        assertTrue(heinekenResp.getInLastOrder());
        assertEquals(new BigDecimal("10"), heinekenResp.getLastOrderQuantity());

        // Tiger: Mua 1 lần (5 thùng), bình quân 5 thùng/đơn
        DealerPurchaseHistoryResponse tigerResp = history.stream()
                .filter(h -> h.getProductId().equals(102L))
                .findFirst().orElseThrow();
        assertEquals(new BigDecimal("5"), tigerResp.getAvgQty());
        assertEquals(1, tigerResp.getOrderCount());
        assertEquals(new BigDecimal("5"), tigerResp.getTotalQuantity());
        assertTrue(tigerResp.getInLastOrder());
    }

    @Test
    @DisplayName("S4-04: Lấy nhóm hàng mua lần trước (lastOrderOnly = true)")
    void testGetDealerLastOrderItems() {
        authenticate("sales01", "ROLE_SALES");
        when(dealerRepository.findById(1L)).thenReturn(Optional.of(dealer));
        when(userRepository.findByUsername("sales01")).thenReturn(Optional.of(salesUser));

        Order order1 = Order.builder()
                .id(1001L)
                .orderCode("DH-LAST")
                .dealer(dealer)
                .status("RECORDED")
                .createdAt(LocalDateTime.now().minusDays(2))
                .build();
        OrderItem item1 = OrderItem.builder()
                .id(1L)
                .order(order1)
                .product(productA)
                .unit("thùng")
                .quantity(new BigDecimal("12"))
                .build();
        order1.setItems(List.of(item1));

        when(orderRepository.findByDealerIdAndStatusNotInOrderByCreatedAtDesc(eq(1L), anyCollection()))
                .thenReturn(List.of(order1));

        List<DealerPurchaseHistoryResponse> lastOrderItems = dealerService.getDealerLastOrderItems(1L);

        assertNotNull(lastOrderItems);
        assertEquals(1, lastOrderItems.size());
        assertEquals(101L, lastOrderItems.get(0).getProductId());
        assertEquals(new BigDecimal("12"), lastOrderItems.get(0).getAvgQty());
        assertTrue(lastOrderItems.get(0).getInLastOrder());
    }

    @Test
    @DisplayName("S4-04: Phân quyền RLS - Nhân viên kinh doanh khác không được xem đại lý không thuộc quyền phụ trách")
    void testSalesAccessDeniedForUnassignedDealer() {
        authenticate("sales02", "ROLE_SALES");
        when(dealerRepository.findById(1L)).thenReturn(Optional.of(dealer)); // dealer được phân công cho sales01
        when(userRepository.findByUsername("sales02")).thenReturn(Optional.of(otherSalesUser));

        assertThrows(AccessDeniedException.class, () -> dealerService.getDealerPurchaseHistory(1L, 3, false));
    }

    @Test
    @DisplayName("S4-04: Quản lý kinh doanh (ROLE_SALES_MANAGER) xem được lịch sử của bất kỳ đại lý nào")
    void testSalesManagerCanViewAnyDealer() {
        authenticate("manager01", "ROLE_SALES_MANAGER");
        when(dealerRepository.findById(1L)).thenReturn(Optional.of(dealer));
        when(orderRepository.findByDealerIdAndStatusNotInOrderByCreatedAtDesc(eq(1L), anyCollection()))
                .thenReturn(Collections.emptyList());
        when(orderRepository.findPurchasedOrdersInPeriod(eq(1L), anyCollection(), any(LocalDateTime.class)))
                .thenReturn(Collections.emptyList());

        List<DealerPurchaseHistoryResponse> history = dealerService.getDealerPurchaseHistory(1L, 3, false);
        assertNotNull(history);
        assertTrue(history.isEmpty());
    }

    @Test
    @DisplayName("S4-04: Báo lỗi khi không tìm thấy đại lý")
    void testDealerNotFound() {
        when(dealerRepository.findById(999L)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> dealerService.getDealerPurchaseHistory(999L, 3, false));
    }
}

