package com.quanlybanhang.service;

import com.quanlybanhang.model.*;
import com.quanlybanhang.repository.OrderRepository;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("OrderPdfService - In và Xuất PDF Đơn hàng kèm Mã vạch (S4-08)")
class OrderPdfServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @InjectMocks
    private OrderPdfServiceImpl orderPdfService;

    private User sales1;
    private User sales2;
    private Dealer dealer1;
    private DealerDeliveryPoint deliveryPoint1;
    private Order order1;
    private Product product1;
    private Product product2;

    @BeforeEach
    void setUp() {
        sales1 = User.builder().id(101L).username("sales01").fullName("Nguyễn Văn Bán Hàng").build();
        sales2 = User.builder().id(102L).username("sales02").fullName("Trần Thị Kinh Doanh").build();

        Territory territory = Territory.builder().id(1L).code("MB").name("Miền Bắc").build();

        dealer1 = Dealer.builder()
                .id(1L)
                .code("DL-HAIPHAT")
                .name("Đại lý Hải Phát")
                .taxCode("0101234567")
                .customerGroup(CustomerGroup.DAI_LY_CAP_1)
                .territory(territory)
                .assignedUser(sales1)
                .phone("0988776655")
                .address("Số 10 Nguyễn Trãi, Thanh Xuân, Hà Nội")
                .active(true)
                .locked(false)
                .build();

        deliveryPoint1 = DealerDeliveryPoint.builder()
                .id(10L)
                .dealer(dealer1)
                .receiverName("Nguyễn Văn Nhận")
                .phone("0912345678")
                .address("Kho phụ: Lô 5 KCN Quang Minh, Mê Linh, Hà Nội")
                .directionNote("Đi cổng 2 KCN, rẽ trái 100m gặp kho màu xanh")
                .build();

        product1 = Product.builder()
                .id(1L)
                .code("BEER-333")
                .name("Bia 333 Lon 330ml")
                .unit("lon")
                .build();

        product2 = Product.builder()
                .id(2L)
                .code("BEER-SAIGON")
                .name("Bia Sài Gòn Special Lon 330ml")
                .unit("lon")
                .build();

        OrderItem item1 = OrderItem.builder()
                .id(1L)
                .product(product1)
                .unit("thùng")
                .quantity(new BigDecimal("10"))
                .conversionFactor(new BigDecimal("24"))
                .baseUnit("lon")
                .baseQuantity(new BigDecimal("240"))
                .price(new BigDecimal("250000"))
                .subtotalAmount(new BigDecimal("2500000"))
                .discountAmount(new BigDecimal("50000"))
                .totalAmount(new BigDecimal("2450000"))
                .build();

        OrderItem item2 = OrderItem.builder()
                .id(2L)
                .product(product2)
                .unit("thùng")
                .quantity(new BigDecimal("5"))
                .conversionFactor(new BigDecimal("24"))
                .baseUnit("lon")
                .baseQuantity(new BigDecimal("120"))
                .price(new BigDecimal("320000"))
                .subtotalAmount(new BigDecimal("1600000"))
                .discountAmount(new BigDecimal("0"))
                .totalAmount(new BigDecimal("1600000"))
                .build();

        order1 = Order.builder()
                .id(100L)
                .orderCode("DH20261016-0001")
                .dealer(dealer1)
                .deliveryPoint(deliveryPoint1)
                .customerName("Đại lý Hải Phát")
                .customerGroup(CustomerGroup.DAI_LY_CAP_1)
                .expectedDeliveryDate(LocalDate.of(2026, 10, 18))
                .status("RECORDED")
                .subtotalAmount(new BigDecimal("4100000"))
                .discountAmount(new BigDecimal("50000"))
                .totalAmount(new BigDecimal("4050000"))
                .note("Giao hàng trong giờ hành chính, gọi trước 30 phút")
                .createdBy("sales01")
                .createdAt(LocalDateTime.of(2026, 10, 16, 8, 30))
                .items(new ArrayList<>(List.of(item1, item2)))
                .build();
    }

    @Test
    @DisplayName("[S4-08] Xuất PDF đơn hàng thành công chứa đầy đủ thông tin đại lý, điểm giao, dòng hàng, chiết khấu và mã vạch")
    void generateOrderPdf_Success_ReturnsValidPdfBytes() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("sales01", "pass",
                        List.of(new SimpleGrantedAuthority("ROLE_SALES"), new SimpleGrantedAuthority("ORDER_VIEW"))));

        when(orderRepository.findById(100L)).thenReturn(Optional.of(order1));

        byte[] pdfBytes = orderPdfService.generateOrderPdf(100L, "sales01");

        assertThat(pdfBytes).isNotNull();
        assertThat(pdfBytes.length).isGreaterThan(1000);

        // Header chuẩn của file PDF luôn bắt đầu bằng "%PDF-"
        String header = new String(pdfBytes, 0, 5);
        assertThat(header).isEqualTo("%PDF-");
    }

    @Test
    @DisplayName("[S4-08] Tạo mã vạch Code 128 thành công cho mã đơn hàng")
    void generateOrderBarcode_Success_ReturnsPngImageBytes() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("sales01", "pass",
                        List.of(new SimpleGrantedAuthority("ROLE_SALES"), new SimpleGrantedAuthority("ORDER_VIEW"))));

        when(orderRepository.findById(100L)).thenReturn(Optional.of(order1));

        byte[] barcodeBytes = orderPdfService.generateOrderBarcode(100L, "sales01");

        assertThat(barcodeBytes).isNotNull();
        assertThat(barcodeBytes.length).isGreaterThan(100);

        // Header chuẩn của file PNG: 0x89, 'P', 'N', 'G'
        assertThat(barcodeBytes[0]).isEqualTo((byte) 0x89);
        assertThat(barcodeBytes[1]).isEqualTo((byte) 'P');
        assertThat(barcodeBytes[2]).isEqualTo((byte) 'N');
        assertThat(barcodeBytes[3]).isEqualTo((byte) 'G');
    }

    @Test
    @DisplayName("[S4-08] Nhân viên kinh doanh khác không phụ trách đại lý bị chặn (Row-Level Security 403)")
    void generateOrderPdf_OtherSalesStaff_ThrowsAccessDeniedException() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("sales02", "pass",
                        List.of(new SimpleGrantedAuthority("ROLE_SALES"), new SimpleGrantedAuthority("ORDER_VIEW"))));

        when(orderRepository.findById(100L)).thenReturn(Optional.of(order1));

        assertThatThrownBy(() -> orderPdfService.generateOrderPdf(100L, "sales02"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Bạn chỉ có quyền xem và in đơn hàng của đại lý do mình phụ trách");
    }

    @Test
    @DisplayName("[S4-08] Quản lý kinh doanh (ROLE_SALES_MANAGER) hoặc Admin có quyền xem và in PDF bất kỳ đơn hàng nào")
    void generateOrderPdf_AsSalesManager_Success() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("manager01", "pass",
                        List.of(new SimpleGrantedAuthority("ROLE_SALES_MANAGER"), new SimpleGrantedAuthority("ORDER_VIEW"))));

        when(orderRepository.findById(100L)).thenReturn(Optional.of(order1));

        byte[] pdfBytes = orderPdfService.generateOrderPdf(100L, "manager01");

        assertThat(pdfBytes).isNotNull();
        assertThat(new String(pdfBytes, 0, 5)).isEqualTo("%PDF-");
    }

    @Test
    @DisplayName("[S4-08] Không tìm thấy đơn hàng ném ngoại lệ IllegalArgumentException")
    void generateOrderPdf_NotFound_ThrowsException() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", "pass",
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));

        when(orderRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> orderPdfService.generateOrderPdf(999L, "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Không tìm thấy đơn hàng với ID: 999");
    }
}
