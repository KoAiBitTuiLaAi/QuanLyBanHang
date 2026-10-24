package com.quanlybanhang.service;

import com.quanlybanhang.dto.request.OrderCalculateRequest;
import com.quanlybanhang.dto.request.OrderItemRequest;
import com.quanlybanhang.dto.request.OrderLineItemPricingRequest;
import com.quanlybanhang.dto.request.OrderRequest;
import com.quanlybanhang.dto.response.OrderCalculateResponse;
import com.quanlybanhang.dto.response.OrderLineItemPricingResponse;
import com.quanlybanhang.dto.response.OrderResponse;
import com.quanlybanhang.model.*;
import com.quanlybanhang.repository.DealerDeliveryPointRepository;
import com.quanlybanhang.repository.DealerRepository;
import com.quanlybanhang.repository.OrderRepository;
import com.quanlybanhang.repository.PriceListRepository;
import com.quanlybanhang.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("OrderService - Tự động nhận đúng giá theo nhóm đại lý & Kiểm soát giá sàn (S2-10)")
class OrderPriceListTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private com.quanlybanhang.repository.UserRepository userRepository;

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
    private com.quanlybanhang.repository.WarehouseRepository warehouseRepository;

    @InjectMocks
    private OrderServiceImpl orderService;

    private Product biaHeineken;
    private Dealer dealerCap1;
    private Dealer dealerCap2;
    private PriceList priceListDl1;
    private PriceList priceListDl2;

    @org.junit.jupiter.api.AfterEach
    void clearAuthentication() { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }

    @BeforeEach
    void setUp() {
        orderService.setUserRepository(userRepository);
        org.mockito.Mockito.lenient().when(userRepository.findByUsername(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(call -> Optional.of(User.builder().id(99L).username(call.getArgument(0)).build()));
        org.mockito.Mockito.lenient().when(orderRepository.findByIdForUpdate(org.mockito.ArgumentMatchers.anyLong()))
                .thenAnswer(call -> orderRepository.findById(call.getArgument(0)));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("manager01", "",
                        List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ORDER_APPROVE"))));
        var warehouse = Warehouse.builder().id(1L).code("TEST-WH").name("Kho kiểm thử").build();
        org.mockito.Mockito.lenient().when(warehouseRepository.findAllByActiveTrueOrderByNameAsc()).thenReturn(List.of(warehouse));
        biaHeineken = Product.builder()
                .id(10L)
                .code("SP-KEN330")
                .name("Bia Heineken Sleek lon 330ml")
                .unit("lon")
                .price(new BigDecimal("19000"))
                .build();

        dealerCap1 = Dealer.builder()
                .id(1L)
                .name("Đại lý Cấp 1 Minh Phát")
                .customerGroup(CustomerGroup.DAI_LY_CAP_1)
                .warehouse(warehouse)
                .build();

        dealerCap2 = Dealer.builder()
                .id(2L)
                .name("Đại lý Cấp 2 Tân Bình")
                .customerGroup(CustomerGroup.DAI_LY_CAP_2)
                .warehouse(warehouse)
                .build();

        // Bảng giá Đại lý cấp 1: Giá bán 16,500 / lon, Giá sàn 15,500 / lon
        priceListDl1 = PriceList.builder()
                .id(101L)
                .code("BG-DLC1-2026")
                .name("Bảng giá Đại lý cấp 1")
                .customerGroup(CustomerGroup.DAI_LY_CAP_1)
                .startDate(LocalDate.of(2026, 1, 1))
                .endDate(LocalDate.of(2026, 12, 31))
                .version(1)
                .active(true)
                .hasOrders(false)
                .items(new ArrayList<>())
                .build();
        priceListDl1.addItem(PriceListItem.builder()
                .product(biaHeineken)
                .sellingPrice(new BigDecimal("16500"))
                .floorPrice(new BigDecimal("15500"))
                .build());

        // Bảng giá Đại lý cấp 2: Giá bán 17,500 / lon, Giá sàn 16,500 / lon
        priceListDl2 = PriceList.builder()
                .id(102L)
                .code("BG-DLC2-2026")
                .name("Bảng giá Đại lý cấp 2")
                .customerGroup(CustomerGroup.DAI_LY_CAP_2)
                .startDate(LocalDate.of(2026, 1, 1))
                .endDate(LocalDate.of(2026, 12, 31))
                .version(1)
                .active(true)
                .hasOrders(false)
                .items(new ArrayList<>())
                .build();
        priceListDl2.addItem(PriceListItem.builder()
                .product(biaHeineken)
                .sellingPrice(new BigDecimal("17500"))
                .floorPrice(new BigDecimal("16500"))
                .build());
    }

    @Test
    @DisplayName("Đại lý cấp 1 tự động nhận đúng giá bán 16,500đ/lon theo bảng giá cấp 1")
    void createOrder_DealerCap1_AutoReceivesPriceListCap1() {
        OrderRequest request = OrderRequest.builder()
                .dealerId(1L)
                .items(List.of(OrderItemRequest.builder()
                        .productId(10L)
                        .unit("lon")
                        .quantity(new BigDecimal("100"))
                        // không truyền price -> tự động lấy từ bảng giá cấp 1
                        .build()))
                .build();

        when(dealerRepository.findById(1L)).thenReturn(Optional.of(dealerCap1));
        when(priceListRepository.findEffectivePriceLists(eq(CustomerGroup.DAI_LY_CAP_1), any(LocalDate.class)))
                .thenReturn(List.of(priceListDl1));
        when(productRepository.findById(10L)).thenReturn(Optional.of(biaHeineken));
        when(unitConversionService.getConversionFactor(biaHeineken, "lon")).thenReturn(BigDecimal.ONE);
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OrderResponse response = orderService.createOrder(request, "sales01");

        assertThat(response).isNotNull();
        assertThat(response.getCustomerGroup()).isEqualTo(CustomerGroup.DAI_LY_CAP_1);
        assertThat(response.getPriceListCode()).isEqualTo("BG-DLC1-2026");
        assertThat(response.getStatus()).isEqualTo("RECORDED");
        assertThat(response.getRequiresApproval()).isFalse();

        // Kiểm tra giá tự động nhận: 16,500đ/lon
        assertThat(response.getItems().get(0).getPrice()).isEqualByComparingTo(new BigDecimal("16500"));
        assertThat(response.getItems().get(0).getFloorPrice()).isEqualByComparingTo(new BigDecimal("15500"));
        assertThat(response.getTotalAmount()).isEqualByComparingTo(new BigDecimal("1650000")); // 100 * 16,500

        // Kiểm tra bảng giá được đánh dấu đã phát sinh đơn (hasOrders = true)
        assertThat(priceListDl1.getHasOrders()).isTrue();
        verify(priceListRepository).save(priceListDl1);
    }

    @Test
    @DisplayName("Đại lý cấp 2 tự động nhận đúng giá bán 17,500đ/lon theo bảng giá cấp 2")
    void createOrder_DealerCap2_AutoReceivesPriceListCap2() {
        OrderRequest request = OrderRequest.builder()
                .dealerId(2L)
                .items(List.of(OrderItemRequest.builder()
                        .productId(10L)
                        .unit("lon")
                        .quantity(new BigDecimal("100"))
                        // không truyền price -> tự động lấy từ bảng giá cấp 2
                        .build()))
                .build();

        when(dealerRepository.findById(2L)).thenReturn(Optional.of(dealerCap2));
        when(priceListRepository.findEffectivePriceLists(eq(CustomerGroup.DAI_LY_CAP_2), any(LocalDate.class)))
                .thenReturn(List.of(priceListDl2));
        when(productRepository.findById(10L)).thenReturn(Optional.of(biaHeineken));
        when(unitConversionService.getConversionFactor(biaHeineken, "lon")).thenReturn(BigDecimal.ONE);
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OrderResponse response = orderService.createOrder(request, "sales01");

        assertThat(response).isNotNull();
        assertThat(response.getCustomerGroup()).isEqualTo(CustomerGroup.DAI_LY_CAP_2);
        assertThat(response.getPriceListCode()).isEqualTo("BG-DLC2-2026");

        // Kiểm tra giá tự động nhận: 17,500đ/lon
        assertThat(response.getItems().get(0).getPrice()).isEqualByComparingTo(new BigDecimal("17500"));
        assertThat(response.getItems().get(0).getFloorPrice()).isEqualByComparingTo(new BigDecimal("16500"));
        assertThat(response.getTotalAmount()).isEqualByComparingTo(new BigDecimal("1750000")); // 100 * 17,500
    }

    @Test
    @DisplayName("S4-01: Không cho dùng bảng giá của nhóm đại lý khác khi tính đơn")
    void calculateOrder_RejectsPriceListFromAnotherCustomerGroup() {
        OrderCalculateRequest request = OrderCalculateRequest.builder()
                .dealerId(1L)
                .priceListId(priceListDl2.getId())
                .orderDate(LocalDate.of(2026, 6, 1))
                .items(List.of(OrderItemRequest.builder()
                        .productId(10L)
                        .unit("lon")
                        .quantity(BigDecimal.ONE)
                        .build()))
                .build();

        when(dealerRepository.findById(1L)).thenReturn(Optional.of(dealerCap1));
        when(priceListRepository.findById(priceListDl2.getId())).thenReturn(Optional.of(priceListDl2));

        assertThatThrownBy(() -> orderService.calculateOrder(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("không thuộc nhóm khách hàng");
    }

    @Test
    @DisplayName("S4-01: Không cho dùng bảng giá đã hết hiệu lực khi tính đơn")
    void calculateOrder_RejectsInactivePriceList() {
        priceListDl1.setActive(false);
        OrderCalculateRequest request = OrderCalculateRequest.builder()
                .customerGroup(CustomerGroup.DAI_LY_CAP_1)
                .priceListId(priceListDl1.getId())
                .orderDate(LocalDate.of(2026, 6, 1))
                .items(List.of(OrderItemRequest.builder()
                        .productId(10L)
                        .unit("lon")
                        .quantity(BigDecimal.ONE)
                        .build()))
                .build();

        when(priceListRepository.findById(priceListDl1.getId())).thenReturn(Optional.of(priceListDl1));

        assertThatThrownBy(() -> orderService.calculateOrder(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("không có hiệu lực");
    }

    @Test
    @DisplayName("Bán DƯỚI GIÁ SÀN -> Đơn hàng chuyển sang PENDING_APPROVAL và cần Quản lý duyệt")
    void createOrder_BelowFloorPrice_RequiresApproval() {
        // Giá sàn Đại lý cấp 1 là 15,500đ; nhân viên nhập giá bán 14,000đ (dưới giá sàn)
        OrderRequest request = OrderRequest.builder()
                .customerGroup(CustomerGroup.DAI_LY_CAP_1)
                .customerName("Đại lý Cấp 1 Minh Phát")
                .items(List.of(OrderItemRequest.builder()
                        .productId(10L)
                        .unit("lon")
                        .quantity(new BigDecimal("50"))
                        .price(new BigDecimal("14000")) // < Giá sàn 15,500
                        .build()))
                .build();

        when(priceListRepository.findEffectivePriceLists(eq(CustomerGroup.DAI_LY_CAP_1), any(LocalDate.class)))
                .thenReturn(List.of(priceListDl1));
        when(productRepository.findById(10L)).thenReturn(Optional.of(biaHeineken));
        when(unitConversionService.getConversionFactor(biaHeineken, "lon")).thenReturn(BigDecimal.ONE);
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OrderResponse response = orderService.createOrder(request, "sales01");

        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo("PENDING_APPROVAL");
        assertThat(response.getRequiresApproval()).isTrue();
        assertThat(response.getApprovalReason()).contains("bán giá 14000 < giá sàn 15500");
        assertThat(response.getItems().get(0).getBelowFloorPrice()).isTrue();
    }

    @Test
    @DisplayName("Đặt theo THÙNG (hệ số 24) bán dưới giá sàn quy đổi -> Chờ duyệt")
    void createOrder_ByCarton_BelowFloorPrice_RequiresApproval() {
        // Giá sàn 1 lon = 15,500 -> Giá sàn 1 thùng (24 lon) = 15,500 * 24 = 372,000
        // Nhân viên bán giá 360,000 / thùng (< 372,000)
        OrderRequest request = OrderRequest.builder()
                .customerGroup(CustomerGroup.DAI_LY_CAP_1)
                .items(List.of(OrderItemRequest.builder()
                        .productId(10L)
                        .unit("thùng")
                        .quantity(new BigDecimal("5"))
                        .price(new BigDecimal("360000")) // < 372,000
                        .build()))
                .build();

        when(priceListRepository.findEffectivePriceLists(eq(CustomerGroup.DAI_LY_CAP_1), any(LocalDate.class)))
                .thenReturn(List.of(priceListDl1));
        when(productRepository.findById(10L)).thenReturn(Optional.of(biaHeineken));
        when(unitConversionService.getConversionFactor(biaHeineken, "thùng")).thenReturn(new BigDecimal("24.0000"));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OrderResponse response = orderService.createOrder(request, "sales01");

        assertThat(response.getStatus()).isEqualTo("PENDING_APPROVAL");
        assertThat(response.getRequiresApproval()).isTrue();
        assertThat(response.getItems().get(0).getFloorPrice()).isEqualByComparingTo(new BigDecimal("372000.0000"));
        assertThat(response.getItems().get(0).getBelowFloorPrice()).isTrue();
    }

    @Test
    @DisplayName("Quản lý kinh doanh phê duyệt đơn hàng bán dưới giá sàn thành công -> RECORDED")
    void approveOrder_Success() {
        Order pendingOrder = Order.builder()
                .id(200L)
                .orderCode("DH-BELOW-FLOOR")
                .status("PENDING_APPROVAL")
                .requiresApproval(true)
                .approvalReason("Bán dưới giá sàn")
                .build();

        when(orderRepository.findById(200L)).thenReturn(Optional.of(pendingOrder));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OrderResponse resp = orderService.approveOrder(200L, "sales.manager");

        assertThat(resp.getStatus()).isEqualTo("RECORDED");
        assertThat(resp.getApprovedBy()).isEqualTo("sales.manager");
        assertThat(resp.getApprovedAt()).isNotNull();
    }

    @Test
    @DisplayName("Quản lý kinh doanh từ chối đơn hàng bán dưới giá sàn -> REJECTED")
    void rejectOrder_Success() {
        Order pendingOrder = Order.builder()
                .id(200L)
                .orderCode("DH-BELOW-FLOOR")
                .status("PENDING_APPROVAL")
                .requiresApproval(true)
                .approvalReason("Bán dưới giá sàn")
                .build();

        when(orderRepository.findById(200L)).thenReturn(Optional.of(pendingOrder));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OrderResponse resp = orderService.rejectOrder(200L, "Lỗ quá mức cho phép", "sales.manager");

        assertThat(resp.getStatus()).isEqualTo("REJECTED");
        assertThat(resp.getApprovedBy()).isEqualTo("sales.manager");
        assertThat(resp.getNote()).contains("Lý do từ chối: Lỗ quá mức cho phép");
    }

    // ==========================================
    // STORY S4-01 TESTS: TỰ ĐỘNG ÁP DỤNG BẢNG GIÁ, CHIẾT KHẤU SẢN LƯỢNG & CHẶN SKU NGOÀI BẢNG GIÁ
    // ==========================================

    @Test
    @DisplayName("S4-01: Chặn tạo đơn khi không tìm thấy bảng giá có hiệu lực cho nhóm khách hàng của đại lý")
    void createOrder_NoEffectivePriceList_ThrowsException() {
        OrderRequest request = OrderRequest.builder()
                .dealerId(1L)
                .items(List.of(OrderItemRequest.builder()
                        .productId(10L)
                        .unit("lon")
                        .quantity(new BigDecimal("10"))
                        .build()))
                .build();

        when(dealerRepository.findById(1L)).thenReturn(Optional.of(dealerCap1));
        when(priceListRepository.findEffectivePriceLists(eq(CustomerGroup.DAI_LY_CAP_1), any(LocalDate.class)))
                .thenReturn(List.of()); // Không có bảng giá nào hiệu lực

        assertThatThrownBy(() -> orderService.createOrder(request, "sales01"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Không tìm thấy bảng giá hiệu lực cho nhóm khách hàng 'Đại lý cấp 1'");
    }

    @Test
    @DisplayName("S4-01: Chặn thêm dòng hàng khi SKU không có trong bảng giá hiệu lực kèm thông báo rõ lý do")
    void createOrder_SkuNotInPriceList_ThrowsExceptionWithClearReason() {
        Product biaTiger = Product.builder()
                .id(20L)
                .code("SP-TIGER330")
                .name("Bia Tiger lon 330ml")
                .unit("lon")
                .price(new BigDecimal("18000"))
                .build();

        OrderRequest request = OrderRequest.builder()
                .dealerId(1L)
                .items(List.of(OrderItemRequest.builder()
                        .productId(20L)
                        .unit("lon")
                        .quantity(new BigDecimal("10"))
                        .build()))
                .build();

        when(dealerRepository.findById(1L)).thenReturn(Optional.of(dealerCap1));
        when(priceListRepository.findEffectivePriceLists(eq(CustomerGroup.DAI_LY_CAP_1), any(LocalDate.class)))
                .thenReturn(List.of(priceListDl1)); // priceListDl1 chỉ có SP-KEN330
        when(productRepository.findById(20L)).thenReturn(Optional.of(biaTiger));

        assertThatThrownBy(() -> orderService.createOrder(request, "sales01"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Sản phẩm 'Bia Tiger lon 330ml' (mã: SP-TIGER330) chưa có trong bảng giá hiệu lực của nhóm khách hàng 'Đại lý cấp 1'. Không thể thêm dòng hàng.");
    }

    @Test
    @DisplayName("S4-01: calculateLineItemPricing tự động lấy giá bán từ bảng giá hiệu lực và tính chiết khấu sản lượng")
    void calculateLineItemPricing_AutoReceivesPriceListAndDiscount() {
        OrderLineItemPricingRequest request = OrderLineItemPricingRequest.builder()
                .dealerId(1L)
                .productId(10L)
                .unit("lon")
                .quantity(new BigDecimal("100"))
                .build();

        when(dealerRepository.findById(1L)).thenReturn(Optional.of(dealerCap1));
        when(priceListRepository.findEffectivePriceLists(eq(CustomerGroup.DAI_LY_CAP_1), any(LocalDate.class)))
                .thenReturn(List.of(priceListDl1));
        when(productRepository.findById(10L)).thenReturn(Optional.of(biaHeineken));
        when(unitConversionService.getConversionFactor(biaHeineken, "lon")).thenReturn(BigDecimal.ONE);

        // Giả lập chiết khấu sản lượng 5% = 82,500đ
        VolumeDiscountPolicyService.ItemDiscountResult discountResult = VolumeDiscountPolicyService.ItemDiscountResult.builder()
                .discountAmount(new BigDecimal("82500"))
                .discountPercent(new BigDecimal("5.00"))
                .appliedPolicyId(1L)
                .appliedPolicyCode("CK-SL-01")
                .appliedPolicyName("Chiết khấu SL Đại lý Cấp 1")
                .bestPolicyReason("Chiết khấu theo bậc số lượng >= 100 lon")
                .build();

        VolumeDiscountPolicyService.OrderDiscountSummary summary = VolumeDiscountPolicyService.OrderDiscountSummary.builder()
                .discountAmount(new BigDecimal("82500"))
                .itemResults(Map.of(0, discountResult))
                .build();

        when(volumeDiscountPolicyService.calculateOrderDiscount(any(), eq(CustomerGroup.DAI_LY_CAP_1), any(LocalDate.class)))
                .thenReturn(summary);

        OrderLineItemPricingResponse resp = orderService.calculateLineItemPricing(request);

        assertThat(resp).isNotNull();
        assertThat(resp.getCustomerGroup()).isEqualTo(CustomerGroup.DAI_LY_CAP_1);
        assertThat(resp.getPriceListCode()).isEqualTo("BG-DLC1-2026");
        assertThat(resp.getPriceListSellingPrice()).isEqualByComparingTo(new BigDecimal("16500"));
        assertThat(resp.getFloorPrice()).isEqualByComparingTo(new BigDecimal("15500"));
        assertThat(resp.getUnitPrice()).isEqualByComparingTo(new BigDecimal("16500"));
        assertThat(resp.getIsManualPrice()).isFalse();
        assertThat(resp.getBelowFloorPrice()).isFalse();
        assertThat(resp.getRequiresApproval()).isFalse();
        assertThat(resp.getSubtotalAmount()).isEqualByComparingTo(new BigDecimal("1650000")); // 100 * 16,500
        assertThat(resp.getDiscountAmount()).isEqualByComparingTo(new BigDecimal("82500"));
        assertThat(resp.getTotalAmount()).isEqualByComparingTo(new BigDecimal("1567500")); // 1,650,000 - 82,500
        assertThat(resp.getAppliedPolicyCode()).isEqualTo("CK-SL-01");
    }

    @Test
    @DisplayName("S4-01: calculateLineItemPricing sửa giá thủ công dưới giá sàn bị đánh dấu cần duyệt")
    void calculateLineItemPricing_ManualPriceBelowFloor_RequiresApproval() {
        OrderLineItemPricingRequest request = OrderLineItemPricingRequest.builder()
                .dealerId(1L)
                .productId(10L)
                .unit("lon")
                .quantity(new BigDecimal("50"))
                .manualPrice(new BigDecimal("14000")) // < 15,500
                .build();

        when(dealerRepository.findById(1L)).thenReturn(Optional.of(dealerCap1));
        when(priceListRepository.findEffectivePriceLists(eq(CustomerGroup.DAI_LY_CAP_1), any(LocalDate.class)))
                .thenReturn(List.of(priceListDl1));
        when(productRepository.findById(10L)).thenReturn(Optional.of(biaHeineken));
        when(unitConversionService.getConversionFactor(biaHeineken, "lon")).thenReturn(BigDecimal.ONE);

        OrderLineItemPricingResponse resp = orderService.calculateLineItemPricing(request);

        assertThat(resp).isNotNull();
        assertThat(resp.getUnitPrice()).isEqualByComparingTo(new BigDecimal("14000"));
        assertThat(resp.getIsManualPrice()).isTrue();
        assertThat(resp.getBelowFloorPrice()).isTrue();
        assertThat(resp.getRequiresApproval()).isTrue();
        assertThat(resp.getApprovalReason()).contains("bán giá 14000 < giá sàn 15500");
    }

    @Test
    @DisplayName("S4-01: calculateLineItemPricing chặn khi SKU không có trong bảng giá hiệu lực")
    void calculateLineItemPricing_SkuNotInPriceList_ThrowsException() {
        Product biaTiger = Product.builder()
                .id(20L)
                .code("SP-TIGER330")
                .name("Bia Tiger lon 330ml")
                .unit("lon")
                .price(new BigDecimal("18000"))
                .build();

        OrderLineItemPricingRequest request = OrderLineItemPricingRequest.builder()
                .dealerId(1L)
                .productId(20L)
                .unit("lon")
                .quantity(new BigDecimal("10"))
                .build();

        when(dealerRepository.findById(1L)).thenReturn(Optional.of(dealerCap1));
        when(priceListRepository.findEffectivePriceLists(eq(CustomerGroup.DAI_LY_CAP_1), any(LocalDate.class)))
                .thenReturn(List.of(priceListDl1));
        when(productRepository.findById(20L)).thenReturn(Optional.of(biaTiger));

        assertThatThrownBy(() -> orderService.calculateLineItemPricing(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Sản phẩm 'Bia Tiger lon 330ml' (mã: SP-TIGER330) chưa có trong bảng giá hiệu lực của nhóm khách hàng 'Đại lý cấp 1'. Không thể thêm dòng hàng.");
    }

    @Test
    @DisplayName("S4-01: calculateOrder tính lại chiết khấu sản lượng tự động khi số lượng thay đổi trên toàn đơn")
    void calculateOrder_RecalculatesDiscountsAndItemTotals() {
        OrderCalculateRequest request = OrderCalculateRequest.builder()
                .dealerId(1L)
                .items(List.of(OrderItemRequest.builder()
                        .productId(10L)
                        .unit("lon")
                        .quantity(new BigDecimal("200")) // Số lượng tăng lên 200
                        .build()))
                .build();

        when(dealerRepository.findById(1L)).thenReturn(Optional.of(dealerCap1));
        when(priceListRepository.findEffectivePriceLists(eq(CustomerGroup.DAI_LY_CAP_1), any(LocalDate.class)))
                .thenReturn(List.of(priceListDl1));
        when(productRepository.findById(10L)).thenReturn(Optional.of(biaHeineken));
        when(unitConversionService.getConversionFactor(biaHeineken, "lon")).thenReturn(BigDecimal.ONE);

        // Giả lập chiết khấu mức cao hơn: 10% = 330,000đ cho 200 lon
        VolumeDiscountPolicyService.ItemDiscountResult discountResult = VolumeDiscountPolicyService.ItemDiscountResult.builder()
                .discountAmount(new BigDecimal("330000"))
                .discountPercent(new BigDecimal("10.00"))
                .appliedPolicyId(1L)
                .appliedPolicyCode("CK-SL-02")
                .appliedPolicyName("Chiết khấu SL Tier 2")
                .bestPolicyReason("Chiết khấu theo bậc số lượng >= 200 lon (10%)")
                .build();

        VolumeDiscountPolicyService.OrderDiscountSummary summary = VolumeDiscountPolicyService.OrderDiscountSummary.builder()
                .discountAmount(new BigDecimal("330000"))
                .itemResults(Map.of(0, discountResult))
                .build();

        when(volumeDiscountPolicyService.calculateOrderDiscount(any(), eq(CustomerGroup.DAI_LY_CAP_1), any(LocalDate.class)))
                .thenReturn(summary);

        OrderCalculateResponse resp = orderService.calculateOrder(request);

        assertThat(resp).isNotNull();
        assertThat(resp.getCustomerGroup()).isEqualTo(CustomerGroup.DAI_LY_CAP_1);
        assertThat(resp.getSubtotalAmount()).isEqualByComparingTo(new BigDecimal("3300000")); // 200 * 16,500
        assertThat(resp.getDiscountAmount()).isEqualByComparingTo(new BigDecimal("330000"));
        assertThat(resp.getTotalAmount()).isEqualByComparingTo(new BigDecimal("2970000")); // 3,300,000 - 330,000
        assertThat(resp.getRequiresApproval()).isFalse();

        assertThat(resp.getItems()).hasSize(1);
        assertThat(resp.getItems().get(0).getDiscountAmount()).isEqualByComparingTo(new BigDecimal("330000"));
        assertThat(resp.getItems().get(0).getTotalAmount()).isEqualByComparingTo(new BigDecimal("2970000"));
    }

    @Test
    @DisplayName("S4-01: calculateOrder chặn khi có sản phẩm không nằm trong bảng giá hiệu lực")
    void calculateOrder_SkuNotInPriceList_ThrowsException() {
        Product biaTiger = Product.builder()
                .id(20L)
                .code("SP-TIGER330")
                .name("Bia Tiger lon 330ml")
                .unit("lon")
                .price(new BigDecimal("18000"))
                .build();

        OrderCalculateRequest request = OrderCalculateRequest.builder()
                .dealerId(1L)
                .items(List.of(
                        OrderItemRequest.builder()
                                .productId(10L)
                                .unit("lon")
                                .quantity(new BigDecimal("10"))
                                .build(),
                        OrderItemRequest.builder()
                                .productId(20L)
                                .unit("lon")
                                .quantity(new BigDecimal("5"))
                                .build()
                ))
                .build();

        when(dealerRepository.findById(1L)).thenReturn(Optional.of(dealerCap1));
        when(priceListRepository.findEffectivePriceLists(eq(CustomerGroup.DAI_LY_CAP_1), any(LocalDate.class)))
                .thenReturn(List.of(priceListDl1));
        when(productRepository.findById(10L)).thenReturn(Optional.of(biaHeineken));
        when(productRepository.findById(20L)).thenReturn(Optional.of(biaTiger));
        when(unitConversionService.getConversionFactor(any(), any())).thenReturn(BigDecimal.ONE);

        assertThatThrownBy(() -> orderService.calculateOrder(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Sản phẩm 'Bia Tiger lon 330ml' (mã: SP-TIGER330) chưa có trong bảng giá hiệu lực của nhóm khách hàng 'Đại lý cấp 1'. Không thể thêm dòng hàng.");
    }
}
