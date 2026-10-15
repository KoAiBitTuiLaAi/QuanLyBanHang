package com.quanlybanhang.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanlybanhang.dto.response.OrderItemResponse;
import com.quanlybanhang.dto.response.OrderResponse;
import com.quanlybanhang.model.CustomerGroup;
import com.quanlybanhang.model.Dealer;
import com.quanlybanhang.model.Order;
import com.quanlybanhang.model.OrderItem;
import com.quanlybanhang.model.PriceList;
import com.quanlybanhang.model.PriceListItem;
import com.quanlybanhang.model.Product;
import com.quanlybanhang.model.User;
import com.quanlybanhang.repository.DealerDeliveryPointRepository;
import com.quanlybanhang.repository.DealerRepository;
import com.quanlybanhang.repository.OrderDraftRepository;
import com.quanlybanhang.repository.OrderRepository;
import com.quanlybanhang.repository.PriceListRepository;
import com.quanlybanhang.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("S4-09: Sao chép đơn hàng cũ thành đơn mới (Order Copy)")
class OrderCopyTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private UnitConversionService unitConversionService;

    @Mock
    private DealerRepository dealerRepository;

    @Mock
    private DealerDeliveryPointRepository deliveryPointRepository;

    @Mock
    private PriceListRepository priceListRepository;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private VolumeDiscountPolicyService volumeDiscountPolicyService;

    @Mock
    private DealerDebtService dealerDebtService;

    @Mock
    private OrderDraftRepository orderDraftRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private OrderServiceImpl orderService;

    private Dealer dealer;
    private Product biaKen;
    private Order sourceOrder;

    @BeforeEach
    void setUp() {
        dealer = Dealer.builder()
                .id(1L)
                .code("DL01")
                .name("Đại lý Minh Phát")
                .customerGroup(CustomerGroup.DAI_LY_CAP_1)
                .active(true)
                .locked(false)
                .assignedUser(User.builder().id(100L).username("sales01").build())
                .build();

        biaKen = Product.builder()
                .id(10L)
                .code("SP-KEN330")
                .name("Bia Heineken Lon 330ml")
                .unit("lon")
                .price(new BigDecimal("18000"))
                .active(true)
                .build();

        OrderItem item = OrderItem.builder()
                .id(101L)
                .product(biaKen)
                .unit("thùng")
                .quantity(new BigDecimal("5"))
                .conversionFactor(new BigDecimal("24"))
                .baseUnit("lon")
                .baseQuantity(new BigDecimal("120"))
                .price(new BigDecimal("400000")) // Giá cũ trong đơn trước
                .floorPrice(new BigDecimal("360000"))
                .subtotalAmount(new BigDecimal("2000000"))
                .discountAmount(new BigDecimal("100000")) // Chiết khấu cũ
                .discountPercent(new BigDecimal("5"))
                .totalAmount(new BigDecimal("1900000"))
                .build();

        sourceOrder = Order.builder()
                .id(1L)
                .orderCode("DH-20261001-001")
                .dealer(dealer)
                .customerName("Đại lý Minh Phát")
                .status("DELIVERED")
                .subtotalAmount(new BigDecimal("2000000"))
                .discountAmount(new BigDecimal("100000"))
                .totalAmount(new BigDecimal("1900000"))
                .createdBy("sales01")
                .items(new ArrayList<>(List.of(item)))
                .build();

        item.setOrder(sourceOrder);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "sales01", "pass", List.of(new SimpleGrantedAuthority("ROLE_SALES"), new SimpleGrantedAuthority("ORDER_CREATE"))
                )
        );
    }

    @Test
    @DisplayName("Sao chép đơn thành công: Trạng thái DRAFT, giá và chiết khấu áp theo bảng giá hiện hành")
    void copyOrder_AppliesCurrentPriceAndDiscount_SetsStatusDraft() {
        when(orderRepository.findById(1L)).thenReturn(Optional.of(sourceOrder));
        when(unitConversionService.getConversionFactor(biaKen, "thùng")).thenReturn(new BigDecimal("24"));

        // Bảng giá hiện hành: giá bán mới là 20.000 / lon -> thùng (hệ số 24) = 480.000
        PriceListItem priceListItem = PriceListItem.builder()
                .product(biaKen)
                .sellingPrice(new BigDecimal("20000"))
                .floorPrice(new BigDecimal("17000"))
                .build();
        PriceList currentPriceList = PriceList.builder()
                .id(5L)
                .customerGroup(CustomerGroup.DAI_LY_CAP_1)
                .items(List.of(priceListItem))
                .build();
        when(priceListRepository.findEffectivePriceLists(eq(CustomerGroup.DAI_LY_CAP_1), any(LocalDate.class)))
                .thenReturn(List.of(currentPriceList));

        // Chiết khấu sản lượng hiện hành: giảm 50.000
        VolumeDiscountPolicyService.ItemDiscountResult discountResult = VolumeDiscountPolicyService.ItemDiscountResult.builder()
                .discountAmount(new BigDecimal("50000"))
                .discountPercent(new BigDecimal("2.08"))
                .appliedPolicyId(99L)
                .appliedPolicyCode("CK-SL-01")
                .appliedPolicyName("Chiết khấu đại lý cấp 1")
                .build();
        VolumeDiscountPolicyService.OrderDiscountSummary discountSummary = VolumeDiscountPolicyService.OrderDiscountSummary.builder()
                .subtotalAmount(new BigDecimal("2400000")) // 5 * 480.000
                .discountAmount(new BigDecimal("50000"))
                .totalAmount(new BigDecimal("2350000"))
                .itemResults(Map.of(0, discountResult))
                .build();
        when(volumeDiscountPolicyService.calculateOrderDiscount(any(), eq(CustomerGroup.DAI_LY_CAP_1), any(LocalDate.class)))
                .thenReturn(discountSummary);

        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> {
            Order o = invocation.getArgument(0);
            o.setId(2L);
            return o;
        });

        OrderResponse copied = orderService.copyOrder(1L, "sales01");

        assertThat(copied).isNotNull();
        // Bản sao luôn bắt đầu ở trạng thái Nháp (DRAFT)
        assertThat(copied.getStatus()).isEqualTo("DRAFT");
        assertThat(copied.getOrderCode()).isNotEqualTo(sourceOrder.getOrderCode());

        // Giá áp theo bảng giá hiện hành (480.000 thùng, không phải 400.000 giá cũ)
        assertThat(copied.getItems()).hasSize(1);
        OrderItemResponse copiedItem = copied.getItems().get(0);
        assertThat(copiedItem.getPrice()).isEqualByComparingTo(new BigDecimal("480000"));
        assertThat(copiedItem.getDiscountAmount()).isEqualByComparingTo(new BigDecimal("50000"));
        assertThat(copiedItem.getAppliedPolicyCode()).isEqualTo("CK-SL-01");

        // Tổng tiền đơn hàng
        assertThat(copied.getSubtotalAmount()).isEqualByComparingTo(new BigDecimal("2400000"));
        assertThat(copied.getDiscountAmount()).isEqualByComparingTo(new BigDecimal("50000"));
        assertThat(copied.getTotalAmount()).isEqualByComparingTo(new BigDecimal("2350000"));

        verify(auditLogService).logAction(eq("INVOICE"), any(), eq("COPY_FROM"), eq("DH-20261001-001"), eq("DRAFT"), eq("sales01"));
    }

    @Test
    @DisplayName("Sao chép đơn sang draft JSON (copyOrderToDraft) trả về các dòng hàng với giá đặt lại 0 để FE áp bảng giá")
    void copyOrderToDraft_ReturnsValidDraftJson() {
        when(orderRepository.findById(1L)).thenReturn(Optional.of(sourceOrder));

        JsonNode draftNode = orderService.copyOrderToDraft(1L, "sales01");

        assertThat(draftNode).isNotNull();
        assertThat(draftNode.get("dealerId").asLong()).isEqualTo(1L);
        assertThat(draftNode.get("items").isArray()).isTrue();
        assertThat(draftNode.get("items").size()).isEqualTo(1);

        JsonNode item0 = draftNode.get("items").get(0);
        assertThat(item0.get("productId").asLong()).isEqualTo(10L);
        assertThat(item0.get("selectedUnitId").asText()).isEqualTo("thùng");
        assertThat(item0.get("qty").asDouble()).isEqualTo(5.0);
        assertThat(item0.get("unitPrice").asDouble()).isEqualTo(0.0); // Reset giá để FE tự động nhận bảng giá hiện hành
        assertThat(item0.get("discountAmount").asDouble()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("Sao chép đơn không có dòng hàng sẽ báo lỗi IllegalArgumentException")
    void copyOrder_EmptyItems_ThrowsException() {
        sourceOrder.setItems(Collections.emptyList());
        when(orderRepository.findById(1L)).thenReturn(Optional.of(sourceOrder));

        assertThatThrownBy(() -> orderService.copyOrder(1L, "sales01"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("không có dòng hàng");
    }

    @Test
    @DisplayName("Nhân viên sales khác không phụ trách đại lý không được phép sao chép đơn (RLS)")
    void copyOrder_OtherSalesUser_ThrowsAccessDenied() {
        when(orderRepository.findById(1L)).thenReturn(Optional.of(sourceOrder));

        // Caller is sales02, but order is createdBy sales01 and dealer assigned to sales01
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "sales02", "pass", List.of(new SimpleGrantedAuthority("ROLE_SALES"), new SimpleGrantedAuthority("ORDER_CREATE"))
                )
        );

        assertThatThrownBy(() -> orderService.copyOrder(1L, "sales02"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Bạn không có quyền sao chép đơn hàng này");
    }

    @Test
    @DisplayName("S4-04: Thêm nhanh nhóm hàng từ đơn gần nhất vào bản nháp tạo đơn")
    void copyLastOrderToDraft_Success() {
        when(dealerRepository.findById(1L)).thenReturn(Optional.of(dealer));
        when(orderRepository.findByDealerIdAndStatusNotInOrderByCreatedAtDesc(eq(1L), anyCollection()))
                .thenReturn(List.of(sourceOrder));
        when(orderRepository.findById(1L)).thenReturn(Optional.of(sourceOrder));

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "sales01", "pass", List.of(new SimpleGrantedAuthority("ROLE_SALES"), new SimpleGrantedAuthority("ORDER_CREATE"))
                )
        );

        JsonNode draftNode = orderService.copyLastOrderToDraft(1L, "sales01");

        assertThat(draftNode).isNotNull();
        assertThat(draftNode.get("dealerId").asLong()).isEqualTo(1L);
        assertThat(draftNode.get("items")).isNotEmpty();
        assertThat(draftNode.get("items").get(0).get("productId").asLong()).isEqualTo(10L);
        assertThat(draftNode.get("items").get(0).get("qty").asInt()).isEqualTo(5);
    }

    @Test
    @DisplayName("S4-04: Báo lỗi khi đại lý chưa có đơn hàng nào trước đây để thêm nhanh")
    void copyLastOrderToDraft_NoPreviousOrders_ThrowsIllegalArgument() {
        when(dealerRepository.findById(1L)).thenReturn(Optional.of(dealer));
        when(orderRepository.findByDealerIdAndStatusNotInOrderByCreatedAtDesc(eq(1L), anyCollection()))
                .thenReturn(Collections.emptyList());

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "sales01", "pass", List.of(new SimpleGrantedAuthority("ROLE_SALES"), new SimpleGrantedAuthority("ORDER_CREATE"))
                )
        );

        assertThatThrownBy(() -> orderService.copyLastOrderToDraft(1L, "sales01"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("chưa có đơn hàng nào trước đây");
    }

    @Test
    @DisplayName("S4-04: Phân quyền RLS - Chặn nhân viên kinh doanh khác thêm nhanh đơn của đại lý không phụ trách")
    void copyLastOrderToDraft_SalesNotAssigned_ThrowsAccessDenied() {
        when(dealerRepository.findById(1L)).thenReturn(Optional.of(dealer));

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "sales02", "pass", List.of(new SimpleGrantedAuthority("ROLE_SALES"), new SimpleGrantedAuthority("ORDER_CREATE"))
                )
        );

        assertThatThrownBy(() -> orderService.copyLastOrderToDraft(1L, "sales02"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Bạn không có quyền thao tác với đại lý này");
    }
}

