package com.quanlybanhang.service;

import com.quanlybanhang.dto.request.CancelDealerReturnRequest;
import com.quanlybanhang.dto.request.ConfirmDealerReturnRequest;
import com.quanlybanhang.dto.request.CreateDealerReturnItemRequest;
import com.quanlybanhang.dto.request.CreateDealerReturnRequest;
import com.quanlybanhang.dto.response.DealerReturnResponse;
import com.quanlybanhang.model.*;
import com.quanlybanhang.repository.*;
import com.quanlybanhang.service.impl.DealerReturnServiceImpl;
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
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DealerReturnServiceTest - Kiểm thử nghiệp vụ Phiếu trả hàng từ đại lý và nhập lại kho (S8-01)")
class DealerReturnServiceTest {

    @Mock private DealerReturnRepository dealerReturnRepository;
    @Mock private DealerReturnItemRepository dealerReturnItemRepository;
    @Mock private InvoiceRepository invoiceRepository;
    @Mock private OrderItemRepository orderItemRepository;
    @Mock private WarehouseRepository warehouseRepository;
    @Mock private WarehouseInventoryRepository inventoryRepository;
    @Mock private WarehouseLotInventoryRepository lotInventoryRepository;
    @Mock private UserRepository userRepository;
    @Mock private UnitConversionService unitConversionService;
    @Mock private AuditLogService auditLogService;

    @InjectMocks
    private DealerReturnServiceImpl dealerReturnService;

    private User salesUser;
    private User warehouseManager;
    private Warehouse normalWarehouse;
    private Warehouse defectiveWarehouse;
    private Dealer dealer;
    private Product lotProduct;
    private Product regularProduct;
    private Order order;
    private OrderItem orderItemLot;
    private OrderItem orderItemRegular;
    private Invoice invoice;
    private InvoiceItem invoiceItemLot;
    private InvoiceItem invoiceItemRegular;

    @BeforeEach
    void setUp() {
        salesUser = User.builder().id(10L).username("sales1").fullName("NV Kinh Doanh 1").active(true).build();
        normalWarehouse = Warehouse.builder()
                .id(1L).code("KHO-HCM").name("Kho TP.HCM").active(true).defective(false).build();
        defectiveWarehouse = Warehouse.builder()
                .id(2L).code("KHO-LOI").name("Kho hàng lỗi TP.HCM").active(true).defective(true).build();
        warehouseManager = User.builder()
                .id(20L).username("wh_manager").fullName("Quản lý kho").active(true)
                .warehouses(Set.of(normalWarehouse, defectiveWarehouse)).build();

        dealer = Dealer.builder()
                .id(100L).code("DL-01").name("Đại lý Minh Phát")
                .assignedUser(salesUser).warehouse(normalWarehouse).active(true).build();

        lotProduct = Product.builder()
                .id(501L).code("SP-BIA").name("Bia Heineken").unit("lon").manageLot(true).active(true).build();
        regularProduct = Product.builder()
                .id(502L).code("SP-LY").name("Ly thủy tinh").unit("cái").manageLot(false).active(true).build();

        order = Order.builder()
                .id(300L).orderCode("DH-001").dealer(dealer).warehouse(normalWarehouse)
                .status("DELIVERED").createdBy("sales1").build();

        orderItemLot = OrderItem.builder()
                .id(301L).order(order).product(lotProduct).unit("thùng")
                .conversionFactor(new BigDecimal("24")).baseUnit("lon")
                .quantity(new BigDecimal("10")).deliveredQuantity(new BigDecimal("10")).build();

        orderItemRegular = OrderItem.builder()
                .id(302L).order(order).product(regularProduct).unit("cái")
                .conversionFactor(BigDecimal.ONE).baseUnit("cái")
                .quantity(new BigDecimal("20")).deliveredQuantity(new BigDecimal("15")).build();

        invoice = Invoice.builder()
                .id(900L).invoiceNumber("00000001").invoiceCode("HD-00000001")
                .invoiceDate(LocalDateTime.now().minusDays(2))
                .dealer(dealer).dealerCode(dealer.getCode()).dealerName(dealer.getName())
                .status(InvoiceStatus.ISSUED).build();
        invoice.addOrder(order);

        invoiceItemLot = InvoiceItem.builder()
                .id(901L).invoice(invoice).order(order).orderCode(order.getOrderCode())
                .orderItemId(orderItemLot.getId()).product(lotProduct)
                .productCode(lotProduct.getCode()).productName(lotProduct.getName())
                .unit("thùng").orderedQuantity(new BigDecimal("10"))
                .deliveredQuantity(new BigDecimal("10")).invoicedQuantity(new BigDecimal("10"))
                .unitPrice(new BigDecimal("450000")).subtotalAmount(new BigDecimal("4500000"))
                .discountAmount(BigDecimal.ZERO).totalAmount(new BigDecimal("4500000")).build();

        invoiceItemRegular = InvoiceItem.builder()
                .id(902L).invoice(invoice).order(order).orderCode(order.getOrderCode())
                .orderItemId(orderItemRegular.getId()).product(regularProduct)
                .productCode(regularProduct.getCode()).productName(regularProduct.getName())
                .unit("cái").orderedQuantity(new BigDecimal("20"))
                .deliveredQuantity(new BigDecimal("15")).invoicedQuantity(new BigDecimal("15"))
                .unitPrice(new BigDecimal("20000")).subtotalAmount(new BigDecimal("300000"))
                .discountAmount(BigDecimal.ZERO).totalAmount(new BigDecimal("300000")).build();

        invoice.addItem(invoiceItemLot);
        invoice.addItem(invoiceItemRegular);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String username, String... authorities) {
        var auths = java.util.Arrays.stream(authorities)
                .map(SimpleGrantedAuthority::new)
                .toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, "N/A", auths));
    }

    @Test
    @DisplayName("Tiêu chí 1: Bắt buộc chọn lý do trả hàng - thiếu lý do trả sẽ bị chặn")
    void createReturn_MissingReason_ThrowsException() {
        authenticateAs("sales1", "ROLE_SALES", "ORDER_CREATE");
        when(invoiceRepository.findByIdForUpdate(900L)).thenReturn(Optional.of(invoice));

        CreateDealerReturnRequest request = CreateDealerReturnRequest.builder()
                .invoiceId(900L)
                .items(List.of(CreateDealerReturnItemRequest.builder()
                        .invoiceItemId(902L)
                        .returnQuantity(new BigDecimal("2"))
                        .returnReason("   ")
                        .build()))
                .build();

        assertThatThrownBy(() -> dealerReturnService.createReturn(request, "sales1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Bắt buộc chọn lý do trả hàng");

        verifyNoInteractions(inventoryRepository, dealerReturnRepository);
    }

    @Test
    @DisplayName("Tiêu chí 2: Số lượng trả không vượt số lượng đã giao trừ phần đã trả trước đó")
    void createReturn_ExceedsRemainingReturnable_ThrowsException() {
        authenticateAs("sales1", "ROLE_SALES", "ORDER_CREATE");
        when(invoiceRepository.findByIdForUpdate(900L)).thenReturn(Optional.of(invoice));
        // Đã giao 15 cái, đã trả trước đó 12 cái -> chỉ còn được trả tối đa 3 cái
        when(dealerReturnItemRepository.sumReturnQuantityByInvoiceItemIdAndStatuses(eq(902L), any()))
                .thenReturn(new BigDecimal("12"));

        CreateDealerReturnRequest request = CreateDealerReturnRequest.builder()
                .invoiceId(900L)
                .items(List.of(CreateDealerReturnItemRequest.builder()
                        .invoiceItemId(902L)
                        .returnQuantity(new BigDecimal("4"))
                        .returnReason("Đổi trả theo thỏa thuận")
                        .build()))
                .build();

        assertThatThrownBy(() -> dealerReturnService.createReturn(request, "sales1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("vượt quá số lượng tối đa còn được trả");

        verify(dealerReturnRepository, never()).save(any());
    }

    @Test
    @DisplayName("Tiêu chí 3a: SKU có quản lý lô bắt buộc phải nhập đúng số lô đã xuất")
    void createReturn_LotManagedProduct_RequiresValidLot() {
        authenticateAs("sales1", "ROLE_SALES", "ORDER_CREATE");
        when(invoiceRepository.findByIdForUpdate(900L)).thenReturn(Optional.of(invoice));
        when(dealerReturnItemRepository.sumReturnQuantityByInvoiceItemIdAndStatuses(eq(901L), any()))
                .thenReturn(BigDecimal.ZERO);
        when(orderItemRepository.findById(301L)).thenReturn(Optional.of(orderItemLot));

        // Trường hợp 1: Không nhập số lô -> chặn
        CreateDealerReturnRequest missingLotReq = CreateDealerReturnRequest.builder()
                .invoiceId(900L)
                .items(List.of(CreateDealerReturnItemRequest.builder()
                        .invoiceItemId(901L)
                        .returnQuantity(new BigDecimal("2"))
                        .returnReason("Giao dư")
                        .build()))
                .build();

        assertThatThrownBy(() -> dealerReturnService.createReturn(missingLotReq, "sales1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("có quản lý lô, bắt buộc phải chọn/nhập đúng số lô trả về");

        // Trường hợp 2: Nhập sai lô không khớp hồ sơ lô tại kho xuất -> chặn
        WarehouseLotInventory existingLot = WarehouseLotInventory.builder()
                .warehouse(normalWarehouse).product(lotProduct)
                .lotNumber("LOT-KEN-2026A")
                .expiryDate(LocalDate.of(2027, 6, 30))
                .baseQuantity(new BigDecimal("240")).baseUnit("lon").build();
        when(lotInventoryRepository.findByWarehouseIdAndProductId(1L, 501L))
                .thenReturn(List.of(existingLot));

        CreateDealerReturnRequest wrongLotReq = CreateDealerReturnRequest.builder()
                .invoiceId(900L)
                .items(List.of(CreateDealerReturnItemRequest.builder()
                        .invoiceItemId(901L)
                        .returnQuantity(new BigDecimal("2"))
                        .returnReason("Giao dư")
                        .lotNumber("LOT-SAI-999")
                        .build()))
                .build();

        assertThatThrownBy(() -> dealerReturnService.createReturn(wrongLotReq, "sales1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("không đúng với lô đã quản lý/xuất");
    }

    @Test
    @DisplayName("Tiêu chí 3b & 4: Lập phiếu trả lưu trạng thái PENDING (chưa cộng tồn), hàng hỏng định tuyến vào kho hàng lỗi")
    void createReturn_PendingDoesNotAddStock_AndRoutesDamagedToDefectiveWarehouse() {
        authenticateAs("sales1", "ROLE_SALES", "ORDER_CREATE");
        when(invoiceRepository.findByIdForUpdate(900L)).thenReturn(Optional.of(invoice));
        when(dealerReturnItemRepository.sumReturnQuantityByInvoiceItemIdAndStatuses(anyLong(), any()))
                .thenReturn(BigDecimal.ZERO);
        when(orderItemRepository.findById(301L)).thenReturn(Optional.of(orderItemLot));
        when(orderItemRepository.findById(302L)).thenReturn(Optional.of(orderItemRegular));

        WarehouseLotInventory existingLot = WarehouseLotInventory.builder()
                .warehouse(normalWarehouse).product(lotProduct)
                .lotNumber("LOT-KEN-2026A")
                .expiryDate(LocalDate.of(2027, 6, 30))
                .manufacturingDate(LocalDate.of(2026, 6, 30))
                .baseQuantity(new BigDecimal("240")).baseUnit("lon").build();
        when(lotInventoryRepository.findByWarehouseIdAndProductId(1L, 501L))
                .thenReturn(List.of(existingLot));
        when(warehouseRepository.findById(2L)).thenReturn(Optional.of(defectiveWarehouse));
        when(dealerReturnRepository.save(any(DealerReturn.class))).thenAnswer(inv -> {
            DealerReturn r = inv.getArgument(0);
            r.setId(77L);
            return r;
        });

        CreateDealerReturnRequest request = CreateDealerReturnRequest.builder()
                .returnCode("PTH-TEST-001")
                .invoiceId(900L)
                .defectiveWarehouseId(2L)
                .items(List.of(
                        // Dòng 1: Hàng tốt có quản lý lô -> vào kho thường đúng lô
                        CreateDealerReturnItemRequest.builder()
                                .invoiceItemId(901L)
                                .returnQuantity(new BigDecimal("2")) // 2 thùng = 48 lon
                                .returnReason("Đổi trả theo thỏa thuận")
                                .defective(false)
                                .lotNumber("LOT-KEN-2026A")
                                .build(),
                        // Dòng 2: Hàng hỏng -> vào kho hàng lỗi
                        CreateDealerReturnItemRequest.builder()
                                .invoiceItemId(902L)
                                .returnQuantity(new BigDecimal("3"))
                                .returnReason("Hàng hỏng do vận chuyển")
                                .defective(true)
                                .build()
                ))
                .build();

        DealerReturnResponse response = dealerReturnService.createReturn(request, "sales1");

        assertThat(response.getStatus()).isEqualTo(DealerReturnStatus.PENDING);
        assertThat(response.getInventoryAdded()).isFalse();
        assertThat(response.getItems()).hasSize(2);

        // Dòng 1: Hàng tốt -> Kho thường (ID 1), quy đổi 2 thùng = 48 lon, đúng lô LOT-KEN-2026A
        var item1 = response.getItems().get(0);
        assertThat(item1.getDefective()).isFalse();
        assertThat(item1.getTargetWarehouseId()).isEqualTo(1L);
        assertThat(item1.getLotNumber()).isEqualTo("LOT-KEN-2026A");
        assertThat(item1.getBaseReturnQuantity()).isEqualByComparingTo("48");

        // Dòng 2: Hàng hỏng -> Kho hàng lỗi (ID 2)
        var item2 = response.getItems().get(1);
        assertThat(item2.getDefective()).isTrue();
        assertThat(item2.getTargetWarehouseId()).isEqualTo(2L);
        assertThat(item2.getTargetWarehouseDefective()).isTrue();

        // QUAN TRỌNG: Khi mới lập phiếu (PENDING), tuyệt đối CHƯA cộng tồn kho!
        verifyNoInteractions(inventoryRepository);
        verify(lotInventoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("Tiêu chí 4: Quản lý kho xác nhận nhận hàng mới cộng tồn kho và tồn lô đúng kho đích")
    void confirmReturn_ByWarehouseManager_AddsStockToCorrectLotAndDefectiveWarehouse() {
        authenticateAs("wh_manager", "ROLE_WAREHOUSE_MANAGER", "WAREHOUSE_APPROVE");
        when(userRepository.findByUsername("wh_manager")).thenReturn(Optional.of(warehouseManager));

        DealerReturn pendingReturn = DealerReturn.builder()
                .id(77L).returnCode("PTH-TEST-001")
                .invoice(invoice).invoiceCode(invoice.getInvoiceCode())
                .dealer(dealer).dealerCode(dealer.getCode()).dealerName(dealer.getName())
                .warehouse(normalWarehouse).defectiveWarehouse(defectiveWarehouse)
                .status(DealerReturnStatus.PENDING)
                .build();

        DealerReturnItem goodLotItem = DealerReturnItem.builder()
                .id(1001L).invoiceItem(invoiceItemLot).order(order).orderCode(order.getOrderCode())
                .orderItemId(301L).product(lotProduct).productCode(lotProduct.getCode()).productName(lotProduct.getName())
                .manageLot(true).unit("thùng").conversionFactor(new BigDecimal("24")).baseUnit("lon")
                .deliveredQuantity(new BigDecimal("10")).previouslyReturnedQuantity(BigDecimal.ZERO)
                .returnQuantity(new BigDecimal("2")).baseReturnQuantity(new BigDecimal("48"))
                .returnReason("Trả hàng dư").defective(false).itemCondition("GOOD")
                .targetWarehouse(normalWarehouse)
                .lotNumber("LOT-KEN-2026A").expiryDate(LocalDate.of(2027, 6, 30))
                .build();

        DealerReturnItem damagedItem = DealerReturnItem.builder()
                .id(1002L).invoiceItem(invoiceItemRegular).order(order).orderCode(order.getOrderCode())
                .orderItemId(302L).product(regularProduct).productCode(regularProduct.getCode()).productName(regularProduct.getName())
                .manageLot(false).unit("cái").conversionFactor(BigDecimal.ONE).baseUnit("cái")
                .deliveredQuantity(new BigDecimal("15")).previouslyReturnedQuantity(BigDecimal.ZERO)
                .returnQuantity(new BigDecimal("3")).baseReturnQuantity(new BigDecimal("3"))
                .returnReason("Hàng hỏng").defective(true).itemCondition("DEFECTIVE")
                .targetWarehouse(defectiveWarehouse)
                .build();

        pendingReturn.addItem(goodLotItem);
        pendingReturn.addItem(damagedItem);

        when(dealerReturnRepository.findByIdForUpdate(77L)).thenReturn(Optional.of(pendingReturn));
        when(invoiceRepository.findByIdForUpdate(900L)).thenReturn(Optional.of(invoice));
        when(dealerReturnItemRepository.sumReturnQuantityByInvoiceItemIdExcludingReturn(anyLong(), eq(77L), any()))
                .thenReturn(BigDecimal.ZERO);
        when(warehouseRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(normalWarehouse));
        when(warehouseRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(defectiveWarehouse));

        WarehouseInventory normalStock = WarehouseInventory.builder()
                .id(11L).warehouse(normalWarehouse).product(lotProduct)
                .baseQuantity(new BigDecimal("100")).baseUnit("lon").build();
        WarehouseLotInventory normalLotStock = WarehouseLotInventory.builder()
                .id(21L).warehouse(normalWarehouse).product(lotProduct)
                .lotNumber("LOT-KEN-2026A").expiryDate(LocalDate.of(2027, 6, 30))
                .baseQuantity(new BigDecimal("100")).baseUnit("lon").build();

        when(inventoryRepository.findByWarehouseIdAndProductIdForUpdate(1L, 501L))
                .thenReturn(Optional.of(normalStock));
        when(lotInventoryRepository.findByWarehouseIdAndProductIdAndLotNumber(1L, 501L, "LOT-KEN-2026A"))
                .thenReturn(Optional.of(normalLotStock));

        WarehouseInventory defectiveStock = WarehouseInventory.builder()
                .id(12L).warehouse(defectiveWarehouse).product(regularProduct)
                .baseQuantity(new BigDecimal("5")).baseUnit("cái").build();
        when(inventoryRepository.findByWarehouseIdAndProductIdForUpdate(2L, 502L))
                .thenReturn(Optional.of(defectiveStock));

        when(dealerReturnRepository.save(any(DealerReturn.class))).thenAnswer(inv -> inv.getArgument(0));

        DealerReturnResponse confirmed = dealerReturnService.confirmReturn(
                77L, new ConfirmDealerReturnRequest("Đã kiểm đếm và nhận hàng trả", null), "wh_manager");

        assertThat(confirmed.getStatus()).isEqualTo(DealerReturnStatus.CONFIRMED);
        assertThat(confirmed.getInventoryAdded()).isTrue();
        assertThat(confirmed.getConfirmedBy()).isEqualTo("wh_manager");
        assertThat(confirmed.getConfirmedAt()).isNotNull();

        // Tồn kho thường và tồn lô LOT-KEN-2026A tăng thêm 48 lon (100 + 48 = 148)
        assertThat(normalStock.getBaseQuantity()).isEqualByComparingTo("148");
        assertThat(normalLotStock.getBaseQuantity()).isEqualByComparingTo("148");

        // Tồn kho hàng lỗi tăng thêm 3 cái (5 + 3 = 8)
        assertThat(defectiveStock.getBaseQuantity()).isEqualByComparingTo("8");
    }

    @Test
    @DisplayName("Không có quyền WAREHOUSE_APPROVE thì không thể xác nhận nhận hàng trả (403 Forbidden)")
    void confirmReturn_WithoutWarehouseApprovePermission_ThrowsAccessDenied() {
        authenticateAs("wh_staff", "ROLE_WAREHOUSE_STAFF", "WAREHOUSE_IMPORT", "WAREHOUSE_VIEW");

        assertThatThrownBy(() -> dealerReturnService.confirmReturn(77L, null, "wh_staff"))
                .isInstanceOf(AccessDeniedException.class);

        verifyNoInteractions(dealerReturnRepository, inventoryRepository);
    }

    @Test
    @DisplayName("Phiếu trả đã xác nhận không thể hủy")
    void cancelReturn_AlreadyConfirmed_ThrowsException() {
        authenticateAs("sales1", "ROLE_SALES", "ORDER_CREATE", "ORDER_CANCEL");
        DealerReturn confirmedReturn = DealerReturn.builder()
                .id(77L).returnCode("PTH-TEST-001").invoice(invoice).dealer(dealer)
                .warehouse(normalWarehouse).status(DealerReturnStatus.CONFIRMED).build();
        when(dealerReturnRepository.findByIdForUpdate(77L)).thenReturn(Optional.of(confirmedReturn));

        assertThatThrownBy(() -> dealerReturnService.cancelReturn(77L, new CancelDealerReturnRequest("Đổi ý", null), "sales1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("đã được Quản lý kho xác nhận nhập kho, không thể hủy");
    }
}

