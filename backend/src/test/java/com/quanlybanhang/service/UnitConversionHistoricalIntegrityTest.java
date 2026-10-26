package com.quanlybanhang.service;

import com.quanlybanhang.dto.request.OrderItemRequest;
import com.quanlybanhang.dto.request.OrderRequest;
import com.quanlybanhang.dto.request.WarehouseVoucherItemRequest;
import com.quanlybanhang.dto.request.WarehouseVoucherRequest;
import com.quanlybanhang.dto.response.OrderResponse;
import com.quanlybanhang.dto.response.WarehouseVoucherResponse;
import com.quanlybanhang.model.*;
import com.quanlybanhang.repository.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Kiểm thử quy tắc nghiệp vụ cốt lõi (S2-07):
 * "Đổi hệ số quy đổi không làm sai lệch các giao dịch đã ghi trước đó"
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Kiểm thử tính toàn vẹn lịch sử giao dịch khi đổi hệ số quy đổi (S2-07)")
class UnitConversionHistoricalIntegrityTest {

    @Mock
    private WarehouseRepository warehouseRepository;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private UnitConversionRepository unitConversionRepository;

    @Mock
    private UnitConversionService unitConversionService;

    @Mock
    private WarehouseVoucherRepository voucherRepository;

    @Mock
    private WarehouseInventoryRepository inventoryRepository;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private OrderRepository orderRepository;

    @InjectMocks
    private WarehouseServiceImpl warehouseService;

    @InjectMocks
    private OrderServiceImpl orderService;

    @org.junit.jupiter.api.BeforeEach
    void authorize() { org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("admin", "", java.util.List.of(
                        new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN")))); }

    @org.junit.jupiter.api.AfterEach
    void clearAuth() { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }

    @Mock private SupplierRepository supplierRepository;

    @Test
    @DisplayName("Đổi hệ số quy đổi (thùng từ 24 lon thành 20 lon) không làm sai lệch phiếu kho và đơn hàng đã lập trước đó")
    void changeConversionFactor_DoesNotDistortPastTransactions() {
        when(supplierRepository.findById(5L)).thenReturn(Optional.of(Supplier.builder().id(5L).name("Supplier").active(true).build()));
        // GIAI ĐOẠN 1: Sản phẩm có đơn vị cơ sở "lon", 1 thùng = 24 lon
        Warehouse warehouse = Warehouse.builder().id(1L).code("KHO-HCM").name("Kho HCM").build();
        Product product = Product.builder()
                .id(10L)
                .code("SP-KEN330")
                .name("Bia Heineken Sleek lon 330ml")
                .unit("lon")
                .price(new BigDecimal("19000"))
                .build();

        when(warehouseRepository.findById(1L)).thenReturn(Optional.of(warehouse));
        when(warehouseRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(warehouse));
        when(warehouseRepository.findAllByActiveTrueOrderByNameAsc()).thenReturn(List.of(warehouse));
        when(productRepository.findById(10L)).thenReturn(Optional.of(product));

        // Tại thời điểm T1: Hệ số quy đổi "thùng" = 24
        when(unitConversionService.getConversionFactor(product, "thùng"))
                .thenReturn(new BigDecimal("24.0000"));

        WarehouseInventory inventory = WarehouseInventory.builder()
                .id(1L)
                .warehouse(warehouse)
                .product(product)
                .baseQuantity(BigDecimal.ZERO)
                .baseUnit("lon")
                .build();
        when(inventoryRepository.findByWarehouseIdAndProductIdForUpdate(1L, 10L)).thenReturn(Optional.of(inventory));
        org.mockito.Mockito.lenient().when(inventoryRepository.findByWarehouseIdAndProductId(1L, 10L)).thenReturn(Optional.of(inventory));
        when(inventoryRepository.findByWarehouseIdAndProductIdInForUpdate(eq(1L), any())).thenReturn(List.of(inventory));
        when(voucherRepository.save(any(WarehouseVoucher.class))).thenAnswer(inv -> inv.getArgument(0));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        // 1. Lập phiếu nhập kho 10 thùng tại thời điểm T1
        WarehouseVoucherRequest importReq = WarehouseVoucherRequest.builder()
                .warehouseId(1L)
                .voucherCode("PNK-T1").supplierId(5L).documentNumber("DOC-T1").voucherDate(java.time.LocalDateTime.now())
                .items(List.of(WarehouseVoucherItemRequest.builder()
                        .productId(10L)
                        .unit("thùng")
                        .quantity(new BigDecimal("10"))
                        .build()))
                .build();

        WarehouseVoucherResponse voucherT1 = warehouseService.importGoods(importReq, "thukho");

        // 2. Lập đơn hàng 2 thùng tại thời điểm T1
        OrderRequest orderReq = OrderRequest.builder()
                .orderCode("DH-T1")
                .customerName("Khách hàng A")
                .items(List.of(OrderItemRequest.builder()
                        .productId(10L)
                        .unit("thùng")
                        .quantity(new BigDecimal("2"))
                        .price(new BigDecimal("435000"))
                        .build()))
                .build();

        OrderResponse orderT1 = orderService.createOrder(orderReq, "sales01");

        // Kiểm tra kết quả tại thời điểm T1:
        assertThat(voucherT1.getItems().get(0).getConversionFactor()).isEqualByComparingTo(new BigDecimal("24.0000"));
        assertThat(voucherT1.getItems().get(0).getBaseQuantity()).isEqualByComparingTo(new BigDecimal("240.0000"));
        assertThat(orderT1.getItems().get(0).getConversionFactor()).isEqualByComparingTo(new BigDecimal("24.0000"));
        assertThat(orderT1.getItems().get(0).getBaseQuantity()).isEqualByComparingTo(new BigDecimal("48.0000"));
        assertThat(inventory.getBaseQuantity()).isEqualByComparingTo(new BigDecimal("240.0000"));

        // GIAI ĐOẠN 2: Nhà sản xuất thay đổi quy cách đóng thùng: 1 thùng nay chỉ còn 20 lon!
        // Người dùng cập nhật hệ số quy đổi của "thùng" thành 20.0
        when(unitConversionService.getConversionFactor(product, "thùng"))
                .thenReturn(new BigDecimal("20.0000"));

        // XÁC MINH BẤT BIẾN:
        // 1. Phiếu kho T1 đã ghi trước đó KHÔNG hề bị thay đổi
        assertThat(voucherT1.getItems().get(0).getQuantity()).isEqualByComparingTo(new BigDecimal("10"));
        assertThat(voucherT1.getItems().get(0).getConversionFactor()).isEqualByComparingTo(new BigDecimal("24.0000"));
        assertThat(voucherT1.getItems().get(0).getBaseQuantity()).isEqualByComparingTo(new BigDecimal("240.0000"));

        // 2. Đơn hàng T1 đã ghi trước đó KHÔNG hề bị thay đổi
        assertThat(orderT1.getItems().get(0).getQuantity()).isEqualByComparingTo(new BigDecimal("2"));
        assertThat(orderT1.getItems().get(0).getConversionFactor()).isEqualByComparingTo(new BigDecimal("24.0000"));
        assertThat(orderT1.getItems().get(0).getBaseQuantity()).isEqualByComparingTo(new BigDecimal("48.0000"));

        // 3. Sổ sách tồn kho trước đó vẫn giữ nguyên 240 lon (không bị biến thành 200 lon)
        assertThat(inventory.getBaseQuantity()).isEqualByComparingTo(new BigDecimal("240.0000"));

        // GIAI ĐOẠN 3: Giao dịch MỚI tại thời điểm T2 sẽ áp dụng hệ số mới (20 lon/thùng)
        OrderRequest orderReqT2 = OrderRequest.builder()
                .orderCode("DH-T2")
                .customerName("Khách hàng B")
                .items(List.of(OrderItemRequest.builder()
                        .productId(10L)
                        .unit("thùng")
                        .quantity(new BigDecimal("2"))
                        .build()))
                .build();

        OrderResponse orderT2 = orderService.createOrder(orderReqT2, "sales01");

        // Giao dịch mới ghi nhận theo hệ số mới: 2 thùng * 20 = 40 lon
        assertThat(orderT2.getItems().get(0).getConversionFactor()).isEqualByComparingTo(new BigDecimal("20.0000"));
        assertThat(orderT2.getItems().get(0).getBaseQuantity()).isEqualByComparingTo(new BigDecimal("40.0000"));

        // Giao dịch cũ vẫn giữ nguyên 48 lon
        assertThat(orderT1.getItems().get(0).getBaseQuantity()).isEqualByComparingTo(new BigDecimal("48.0000"));
    }
}
