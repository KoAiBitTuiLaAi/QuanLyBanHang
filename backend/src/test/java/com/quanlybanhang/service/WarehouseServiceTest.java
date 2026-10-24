package com.quanlybanhang.service;

import com.quanlybanhang.dto.request.*;
import com.quanlybanhang.dto.response.*;
import com.quanlybanhang.model.*;
import com.quanlybanhang.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("WarehouseService - Quản lý kho, vị trí lưu trữ, soạn hàng (S5-03) & Nhập xuất tồn (S2-07)")
class WarehouseServiceTest {

    @Mock
    private WarehouseRepository warehouseRepository;

    @Mock
    private WarehouseLocationRepository warehouseLocationRepository;

    @Mock
    private WarehouseLocationProductRepository locationProductRepository;

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
    private UserRepository userRepository;

    @Mock
    private DealerRepository dealerRepository;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private AuditLogService auditLogService;

    @Mock private SupplierRepository supplierRepository;
    @Mock private WarehouseLotInventoryRepository lotInventoryRepository;

    @InjectMocks
    private WarehouseServiceImpl warehouseService;

    private Warehouse khoHcm;
    private Product biaHeineken;

    @Test
    void missingQuantityDoesNotInventOneUnit() {
        WarehouseVoucherRequest request = WarehouseVoucherRequest.builder().productId(10L).warehouseId(1L).build();
        assertThatThrownBy(() -> warehouseService.importGoods(request, "staff"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Số lượng không được để trống");
        verifyNoInteractions(warehouseRepository, inventoryRepository, voucherRepository);
    }

    @Test
    void missingWarehouseDoesNotCreateDefaultBusinessRecord() {
        WarehouseVoucherRequest request = WarehouseVoucherRequest.builder().productId(10L).quantity(BigDecimal.ONE).build();
        when(warehouseRepository.findAllByActiveTrueOrderByNameAsc()).thenReturn(List.of());
        assertThatThrownBy(() -> warehouseService.importGoods(request, "staff"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Chưa có kho đang hoạt động");
        verify(warehouseRepository, never()).save(any());
        verifyNoInteractions(inventoryRepository, voucherRepository);
    }

    @Test
    void multipleWarehousesRequireExplicitSelection() {
        WarehouseVoucherRequest request = WarehouseVoucherRequest.builder().productId(10L).quantity(BigDecimal.ONE).build();
        when(warehouseRepository.findAllByActiveTrueOrderByNameAsc()).thenReturn(List.of(khoHcm, Warehouse.builder().id(2L).build()));
        assertThatThrownBy(() -> warehouseService.importGoods(request, "staff"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("warehouseId");
        verifyNoInteractions(inventoryRepository, voucherRepository);
    }

    @Test
    void emptyVoucherCannotWriteInventoryOrCreateWarehouse() {
        assertThatThrownBy(() -> warehouseService.importGoods(new WarehouseVoucherRequest(), "staff"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ít nhất 1 sản phẩm");
        verifyNoInteractions(warehouseRepository, inventoryRepository, voucherRepository);
    }

    @org.junit.jupiter.api.AfterEach
    void clearAuth() { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }

    @BeforeEach
    void setUp() {
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("admin", "", java.util.List.of(
                        new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))));
        khoHcm = Warehouse.builder().id(1L).code("KHO-HCM").name("Kho HCM").build();
        lenient().when(warehouseRepository.findByIdForUpdate(1L)).thenAnswer(i -> Optional.of(khoHcm));
        lenient().when(supplierRepository.findById(5L)).thenReturn(Optional.of(Supplier.builder().id(5L).name("Supplier").active(true).build()));
        biaHeineken = Product.builder()
                .id(10L)
                .code("SP-KEN330")
                .name("Bia Heineken Sleek lon 330ml")
                .unit("lon")
                .price(new BigDecimal("19000"))
                .costPrice(new BigDecimal("15000"))
                .build();
    }

    @Test
    void shelfParentCanBeRemovedWithoutLookingUpZero() {
        WarehouseLocation parent = WarehouseLocation.builder().id(20L).warehouse(khoHcm).locationType(LocationType.ZONE).build();
        WarehouseLocation shelf = WarehouseLocation.builder().id(21L).code("S1").name("Shelf")
                .warehouse(khoHcm).locationType(LocationType.SHELF).parent(parent).build();
        when(warehouseLocationRepository.findById(21L)).thenReturn(Optional.of(shelf));
        when(warehouseLocationRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        var result = warehouseService.updateLocation(21L, WarehouseLocationRequest.builder().parentLocationId(0L).build(), "staff");
        assertThat(result.getParentLocationId()).isNull();
        verify(warehouseLocationRepository, never()).findById(0L);
    }

    @Test
    void zoneContainingShelvesCannotChangeIntoShelf() {
        WarehouseLocation zone = WarehouseLocation.builder().id(20L).warehouse(khoHcm).locationType(LocationType.ZONE).build();
        when(warehouseLocationRepository.findById(20L)).thenReturn(Optional.of(zone));
        when(warehouseLocationRepository.countByParentId(20L)).thenReturn(1L);
        assertThatThrownBy(() -> warehouseService.updateLocation(20L,
                WarehouseLocationRequest.builder().locationType(LocationType.SHELF).build(), "staff"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("trực thuộc");
        verify(warehouseLocationRepository, never()).save(any());
    }

    @Test
    void dealerWarehouseAssignmentRejectsInactiveWarehouseAndUsesMinimalResponse() {
        Dealer dealer = Dealer.builder().id(5L).code("DL5").name("Dealer").build();
        khoHcm.setActive(false);
        when(dealerRepository.findById(5L)).thenReturn(Optional.of(dealer));
        when(warehouseRepository.findById(1L)).thenReturn(Optional.of(khoHcm));
        assertThatThrownBy(() -> warehouseService.assignDealerWarehouse(5L, 1L, "manager"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("hoạt động");
        verify(dealerRepository, never()).save(any());
        khoHcm.setActive(true);
        when(dealerRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        var response = warehouseService.assignDealerWarehouse(5L, 1L, "manager");
        assertThat(response.warehouseId()).isEqualTo(1L);
        assertThat(response.warehouseCode()).isEqualTo("KHO-HCM");
        verify(auditLogService).logAction("DEALER", "5", "ASSIGN_WAREHOUSE", null, "KHO-HCM", "manager");
    }

    @Test
    @DisplayName("Thủ kho nhập 10 thùng (hệ số 24) -> sổ sách tồn kho ghi nhận 240 lon")
    void importGoods_ByCarton_LedgerRecordsInCans() {
        WarehouseVoucherRequest request = WarehouseVoucherRequest.builder()
                .warehouseId(1L)
                .voucherCode("PNK-001").supplierId(5L).documentNumber("DOC-001").voucherDate(LocalDateTime.now())
                .note("Nhập hàng theo thùng")
                .items(List.of(WarehouseVoucherItemRequest.builder()
                        .productId(10L)
                        .unit("thùng")
                        .quantity(new BigDecimal("10"))
                        .build()))
                .build();

        when(warehouseRepository.findById(1L)).thenReturn(Optional.of(khoHcm));
        when(productRepository.findById(10L)).thenReturn(Optional.of(biaHeineken));
        when(unitConversionService.getConversionFactor(biaHeineken, "thùng")).thenReturn(new BigDecimal("24.0000"));

        WarehouseInventory inventory = WarehouseInventory.builder()
                .id(50L)
                .warehouse(khoHcm)
                .product(biaHeineken)
                .baseQuantity(BigDecimal.ZERO)
                .baseUnit("lon")
                .build();

        when(inventoryRepository.findByWarehouseIdAndProductIdForUpdate(1L, 10L)).thenReturn(Optional.of(inventory));
        when(voucherRepository.save(any(WarehouseVoucher.class))).thenAnswer(invocation -> invocation.getArgument(0));

        WarehouseVoucherResponse response = warehouseService.importGoods(request, "thukho01");

        assertThat(response).isNotNull();
        assertThat(response.getItems()).hasSize(1);

        // Chi tiết phiếu: số lượng nhập = 10 thùng, hệ số = 24.0, số lượng cơ sở = 240 lon
        assertThat(response.getItems().get(0).getUnit()).isEqualTo("thùng");
        assertThat(response.getItems().get(0).getQuantity()).isEqualByComparingTo(new BigDecimal("10"));
        assertThat(response.getItems().get(0).getConversionFactor()).isEqualByComparingTo(new BigDecimal("24.0000"));
        assertThat(response.getItems().get(0).getBaseUnit()).isEqualTo("lon");
        assertThat(response.getItems().get(0).getBaseQuantity()).isEqualByComparingTo(new BigDecimal("240.0000"));

        // Sổ sách tồn kho được cộng đúng 240 lon
        assertThat(inventory.getBaseQuantity()).isEqualByComparingTo(new BigDecimal("240.0000"));
        verify(inventoryRepository).save(inventory);
    }

    @Test
    @DisplayName("Thủ kho xuất 5 lốc (hệ số 6 = 30 lon) -> sổ sách tồn kho trừ đúng 30 lon")
    void exportGoods_ByPack_LedgerDeductsInCans() {
        WarehouseVoucherRequest request = WarehouseVoucherRequest.builder()
                .warehouseId(1L)
                .voucherCode("PXK-001")
                .note("Xuất hàng theo lốc")
                .items(List.of(WarehouseVoucherItemRequest.builder()
                        .productId(10L)
                        .unit("lốc")
                        .quantity(new BigDecimal("5"))
                        .build()))
                .build();

        when(warehouseRepository.findById(1L)).thenReturn(Optional.of(khoHcm));
        when(productRepository.findById(10L)).thenReturn(Optional.of(biaHeineken));
        when(unitConversionService.getConversionFactor(biaHeineken, "lốc")).thenReturn(new BigDecimal("6.0000"));

        // Hiện tại trong kho đang có 100 lon
        WarehouseInventory inventory = WarehouseInventory.builder()
                .id(50L)
                .warehouse(khoHcm)
                .product(biaHeineken)
                .baseQuantity(new BigDecimal("100.0000"))
                .baseUnit("lon")
                .build();

        when(inventoryRepository.findByWarehouseIdAndProductIdForUpdate(1L, 10L)).thenReturn(Optional.of(inventory));
        when(voucherRepository.save(any(WarehouseVoucher.class))).thenAnswer(invocation -> invocation.getArgument(0));

        WarehouseVoucherResponse response = warehouseService.exportGoods(request, "thukho01");

        // Chi tiết phiếu: 5 lốc -> 30 lon
        assertThat(response.getItems().get(0).getBaseQuantity()).isEqualByComparingTo(new BigDecimal("30.0000"));

        // Tồn kho: 100 - 30 = 70 lon
        assertThat(inventory.getBaseQuantity()).isEqualByComparingTo(new BigDecimal("70.0000"));
    }

    @Test
    @DisplayName("Xuất hàng vượt quá tồn kho cơ sở -> ném ngoại lệ chặn xuất âm")
    void exportGoods_InsufficientBaseStock_ThrowsException() {
        WarehouseVoucherRequest request = WarehouseVoucherRequest.builder()
                .warehouseId(1L)
                .items(List.of(WarehouseVoucherItemRequest.builder()
                        .productId(10L)
                        .unit("thùng")
                        .quantity(new BigDecimal("10")) // 10 thùng = 240 lon
                        .build()))
                .build();

        when(warehouseRepository.findById(1L)).thenReturn(Optional.of(khoHcm));
        when(productRepository.findById(10L)).thenReturn(Optional.of(biaHeineken));
        when(unitConversionService.getConversionFactor(biaHeineken, "thùng")).thenReturn(new BigDecimal("24.0000"));

        // Tồn kho chỉ còn 50 lon
        WarehouseInventory inventory = WarehouseInventory.builder()
                .warehouse(khoHcm)
                .product(biaHeineken)
                .baseQuantity(new BigDecimal("50.0000"))
                .baseUnit("lon")
                .build();

        when(inventoryRepository.findByWarehouseIdAndProductIdForUpdate(1L, 10L)).thenReturn(Optional.of(inventory));

        assertThatThrownBy(() -> warehouseService.exportGoods(request, "thukho01"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Không đủ tồn kho để xuất");
    }

    @Test
    @DisplayName("Kho chưa có tồn: đọc dữ liệu không tự tạo số lượng tồn giả")
    void getInventory_EmptyWarehouseNeverCreatesStock() {
        when(inventoryRepository.findByWarehouseId(1L)).thenReturn(List.of());
        assertThat(warehouseService.getInventory(1L)).isEmpty();
        verify(inventoryRepository, never()).save(any());
        verifyNoInteractions(productRepository, warehouseRepository);
    }

    @Test
    @DisplayName("Toàn hệ thống chưa có tồn: không tạo tồn mặc định cho sản phẩm")
    void getInventory_EmptySystemNeverCreatesStock() {
        when(inventoryRepository.findAll()).thenReturn(List.of());
        assertThat(warehouseService.getInventory(null)).isEmpty();
        verify(inventoryRepository, never()).save(any());
        verifyNoInteractions(productRepository, warehouseRepository);
    }

    @Test
    @DisplayName("Xem tồn kho: hiển thị số lon thực tế và quy đổi tương đương theo thùng, lốc")
    void getInventory_ShowsBaseQuantityAndConvertedViews() {
        WarehouseInventory inventory = WarehouseInventory.builder()
                .id(50L)
                .warehouse(khoHcm)
                .product(biaHeineken)
                .baseQuantity(new BigDecimal("240.0000"))
                .baseUnit("lon")
                .build();

        when(inventoryRepository.findByWarehouseId(1L)).thenReturn(List.of(inventory));

        UnitConversion loc = UnitConversion.builder().unitName("lốc").conversionFactor(new BigDecimal("6.0000")).build();
        UnitConversion thung = UnitConversion.builder().unitName("thùng").conversionFactor(new BigDecimal("24.0000")).build();
        when(unitConversionRepository.findByProductIdOrderByConversionFactorAsc(10L)).thenReturn(List.of(loc, thung));

        List<WarehouseInventoryResponse> responses = warehouseService.getInventory(1L);

        assertThat(responses).hasSize(1);
        WarehouseInventoryResponse item = responses.get(0);

        assertThat(item.getBaseQuantity()).isEqualByComparingTo(new BigDecimal("240.0000"));
        assertThat(item.getBaseUnit()).isEqualTo("lon");

        // Quy đổi tương đương: 240 lon = 10 thùng = 40 lốc
        assertThat(item.getConvertedViews().get("lon")).isEqualByComparingTo(new BigDecimal("240.0000"));
        assertThat(item.getConvertedViews().get("thùng")).isEqualByComparingTo(new BigDecimal("10.00"));
        assertThat(item.getConvertedViews().get("lốc")).isEqualByComparingTo(new BigDecimal("40.00"));
    }

    // ═══════════════════════════════════════════════════════════════
    // S5-03: QUẢN LÝ KHO, VỊ TRÍ LƯU VÀ SOẠN HÀNG
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[S5-03] Tạo mới kho hàng thành công với đầy đủ mã, tên, địa chỉ, người phụ trách, trạng thái")
    void createWarehouse_success() {
        WarehouseRequest req = WarehouseRequest.builder()
                .code("KHO-HN")
                .name("Kho Hà Nội")
                .address("123 Phố Huế, Hà Nội")
                .managerUserId(2L)
                .managerName("Trần Quản Lý")
                .active(true)
                .build();

        User manager = User.builder().id(2L).username("qlhn").fullName("Trần Quản Lý").build();

        when(warehouseRepository.existsByCode("KHO-HN")).thenReturn(false);
        when(userRepository.findById(2L)).thenReturn(Optional.of(manager));
        when(warehouseRepository.save(any(Warehouse.class))).thenAnswer(invocation -> {
            Warehouse w = invocation.getArgument(0);
            w.setId(100L);
            return w;
        });

        WarehouseResponse res = warehouseService.createWarehouse(req, "admin");

        assertThat(res).isNotNull();
        assertThat(res.getCode()).isEqualTo("KHO-HN");
        assertThat(res.getName()).isEqualTo("Kho Hà Nội");
        assertThat(res.getAddress()).isEqualTo("123 Phố Huế, Hà Nội");
        assertThat(res.getManagerUserId()).isEqualTo(2L);
        assertThat(res.getManagerName()).isEqualTo("Trần Quản Lý");
        assertThat(res.getActive()).isTrue();

        verify(warehouseRepository).save(any(Warehouse.class));
        verify(auditLogService).logAction(eq("WAREHOUSE"), eq("100"), eq("CREATE_WAREHOUSE"), any(), any(), eq("admin"));
    }

    @Test
    @DisplayName("[S5-03] Tạo mới kho hàng trùng mã -> ném ngoại lệ")
    void createWarehouse_duplicateCode_throwsException() {
        WarehouseRequest req = WarehouseRequest.builder()
                .code("KHO-HCM")
                .name("Kho Trùng")
                .build();

        when(warehouseRepository.existsByCode("KHO-HCM")).thenReturn(true);

        assertThatThrownBy(() -> warehouseService.createWarehouse(req, "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("đã tồn tại");
        verify(warehouseRepository, never()).save(any());
    }

    @Test
    @DisplayName("[S5-03] Cập nhật kho hàng thành công")
    void updateWarehouse_success() {
        Warehouse warehouse = Warehouse.builder()
                .id(1L)
                .code("KHO-HCM")
                .name("Kho Cũ")
                .address("Địa chỉ cũ")
                .active(true)
                .build();

        WarehouseRequest req = WarehouseRequest.builder()
                .name("Kho HCM Mới")
                .address("456 Lê Lợi, Q1, HCM")
                .active(true)
                .build();

        when(warehouseRepository.findById(1L)).thenReturn(Optional.of(warehouse));
        when(warehouseRepository.save(any(Warehouse.class))).thenAnswer(invocation -> invocation.getArgument(0));

        WarehouseResponse res = warehouseService.updateWarehouse(1L, req, "admin");

        assertThat(res.getName()).isEqualTo("Kho HCM Mới");
        assertThat(res.getAddress()).isEqualTo("456 Lê Lợi, Q1, HCM");
        verify(warehouseRepository).save(warehouse);
        verify(auditLogService).logAction(eq("WAREHOUSE"), eq("1"), eq("UPDATE_WAREHOUSE"), any(), any(), eq("admin"));
    }

    @Test
    @DisplayName("[S5-03] Xóa kho đã có phiếu kho -> chỉ chuyển trạng thái ngừng hoạt động")
    void deleteWarehouse_withVouchers_deactivatesInsteadOfDeleting() {
        Warehouse warehouse = Warehouse.builder().id(1L).code("KHO-HCM").name("Kho HCM").active(true).build();
        WarehouseVoucher voucher = WarehouseVoucher.builder().id(99L).warehouse(warehouse).build();

        when(warehouseRepository.findById(1L)).thenReturn(Optional.of(warehouse));
        when(voucherRepository.findByWarehouseIdOrderByRecordedAtDesc(1L)).thenReturn(List.of(voucher));

        warehouseService.deleteWarehouse(1L, "admin");

        assertThat(warehouse.getActive()).isFalse();
        verify(warehouseRepository).save(warehouse);
        verify(warehouseRepository, never()).delete(any(Warehouse.class));
        verify(auditLogService).logAction(eq("WAREHOUSE"), eq("1"), eq("DEACTIVATE_WAREHOUSE"), any(), any(), eq("admin"));
    }

    @Test
    @DisplayName("[S5-03] Xóa kho sạch chưa có dữ liệu ràng buộc -> xóa thành công")
    void deleteWarehouse_clean_deletesSuccessfully() {
        Warehouse warehouse = Warehouse.builder().id(1L).code("KHO-HCM").name("Kho HCM").active(true).build();

        when(warehouseRepository.findById(1L)).thenReturn(Optional.of(warehouse));
        when(voucherRepository.findByWarehouseIdOrderByRecordedAtDesc(1L)).thenReturn(Collections.emptyList());
        when(warehouseLocationRepository.countByWarehouseId(1L)).thenReturn(0L);

        warehouseService.deleteWarehouse(1L, "admin");

        verify(warehouseRepository).delete(warehouse);
        verify(auditLogService).logAction(eq("WAREHOUSE"), eq("1"), eq("DELETE_WAREHOUSE"), any(), any(), eq("admin"));
    }

    @Test
    @DisplayName("[S5-03] Khai báo vị trí Khu vực (ZONE) thành công")
    void createLocation_zone_success() {
        WarehouseLocationRequest req = WarehouseLocationRequest.builder()
                .warehouseId(1L)
                .code("ZONE-A")
                .name("Khu vực Đồ uống")
                .locationType(LocationType.ZONE)
                .build();

        when(warehouseRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(khoHcm));
        when(warehouseLocationRepository.existsByWarehouseIdAndCode(1L, "ZONE-A")).thenReturn(false);
        when(warehouseLocationRepository.save(any(WarehouseLocation.class))).thenAnswer(invocation -> {
            WarehouseLocation loc = invocation.getArgument(0);
            loc.setId(201L);
            return loc;
        });

        WarehouseLocationResponse res = warehouseService.createLocation(1L, req, "thukho");

        assertThat(res).isNotNull();
        assertThat(res.getCode()).isEqualTo("ZONE-A");
        assertThat(res.getLocationType()).isEqualTo(LocationType.ZONE);
        assertThat(res.getParentLocationId()).isNull();
        verify(warehouseLocationRepository).save(any(WarehouseLocation.class));
    }

    @Test
    @DisplayName("[S5-03] Khai báo vị trí Kệ (SHELF) thuộc Khu vực (ZONE) thành công")
    void createLocation_shelfWithParentZone_success() {
        WarehouseLocation zoneA = WarehouseLocation.builder()
                .id(201L)
                .warehouse(khoHcm)
                .code("ZONE-A")
                .name("Khu vực A")
                .locationType(LocationType.ZONE)
                .build();

        WarehouseLocationRequest req = WarehouseLocationRequest.builder()
                .warehouseId(1L)
                .code("SHELF-A1")
                .name("Kệ A1")
                .locationType(LocationType.SHELF)
                .parentLocationId(201L)
                .build();

        when(warehouseRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(khoHcm));
        when(warehouseLocationRepository.existsByWarehouseIdAndCode(1L, "SHELF-A1")).thenReturn(false);
        when(warehouseLocationRepository.findById(201L)).thenReturn(Optional.of(zoneA));
        when(warehouseLocationRepository.save(any(WarehouseLocation.class))).thenAnswer(invocation -> {
            WarehouseLocation loc = invocation.getArgument(0);
            loc.setId(202L);
            return loc;
        });

        WarehouseLocationResponse res = warehouseService.createLocation(1L, req, "thukho");

        assertThat(res).isNotNull();
        assertThat(res.getCode()).isEqualTo("SHELF-A1");
        assertThat(res.getLocationType()).isEqualTo(LocationType.SHELF);
        assertThat(res.getParentLocationId()).isEqualTo(201L);
        assertThat(res.getParentLocationCode()).isEqualTo("ZONE-A");
        assertThat(res.getFullDisplayName()).isEqualTo("Khu vực A > Kệ A1");
        verify(warehouseLocationRepository).save(any(WarehouseLocation.class));
    }

    @Test
    @DisplayName("[S5-03] Khai báo kệ con nhưng cha không phải là ZONE -> ném ngoại lệ")
    void createLocation_shelfWithNonZoneParent_throwsException() {
        WarehouseLocation parentShelf = WarehouseLocation.builder()
                .id(202L)
                .warehouse(khoHcm)
                .code("SHELF-A1")
                .name("Kệ A1")
                .locationType(LocationType.SHELF)
                .build();

        WarehouseLocationRequest req = WarehouseLocationRequest.builder()
                .warehouseId(1L)
                .code("SHELF-A1-1")
                .name("Ngăn con")
                .locationType(LocationType.SHELF)
                .parentLocationId(202L)
                .build();

        when(warehouseRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(khoHcm));
        when(warehouseLocationRepository.existsByWarehouseIdAndCode(1L, "SHELF-A1-1")).thenReturn(false);
        when(warehouseLocationRepository.findById(202L)).thenReturn(Optional.of(parentShelf));

        assertThatThrownBy(() -> warehouseService.createLocation(1L, req, "thukho"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Vị trí cha phải là Khu vực (ZONE)");
        verify(warehouseLocationRepository, never()).save(any());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"ZONE,KHU-001", "SHELF,KE-001"})
    void blankLocationCodeIsGeneratedOnServer(LocationType type, String expected) {
        when(warehouseRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(khoHcm));
        when(warehouseLocationRepository.findCodesByWarehouseId(1L)).thenReturn(List.of("MA-RIENG", "KE-A"));
        when(warehouseLocationRepository.save(any())).thenAnswer(i -> {
            WarehouseLocation l = i.getArgument(0); l.setId(700L); return l;
        });
        var response = warehouseService.createLocation(1L, WarehouseLocationRequest.builder()
                .name("Vị trí mới").code("  ").locationType(type).build(), "staff");
        assertThat(response.getCode()).isEqualTo(expected);
        verify(warehouseRepository).findByIdForUpdate(1L);
    }

    @Test
    void generatedCodeSkipsManualNumericCodesAndContinuesPast999() {
        when(warehouseRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(khoHcm));
        when(warehouseLocationRepository.findCodesByWarehouseId(1L))
                .thenReturn(List.of("KE-001", "KE-999", "KE-TANG-2", "KHU-2000"));
        when(warehouseLocationRepository.save(any())).thenAnswer(i -> {
            WarehouseLocation l = i.getArgument(0); l.setId(700L); return l;
        });
        var response = warehouseService.createLocation(1L, WarehouseLocationRequest.builder()
                .name("Kệ mới").locationType(LocationType.SHELF).build(), "staff");
        assertThat(response.getCode()).isEqualTo("KE-1000");
    }

    @Test
    void duplicateManualLocationCodeIsRejectedNotReplacedWithAutoCode() {
        when(warehouseRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(khoHcm));
        when(warehouseLocationRepository.existsByWarehouseIdAndCode(1L, "KE-RIENG")).thenReturn(true);
        assertThatThrownBy(() -> warehouseService.createLocation(1L, WarehouseLocationRequest.builder()
                .name("Kệ mới").code(" ke-rieng ").locationType(LocationType.SHELF).build(), "staff"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("đã tồn tại");
        verify(warehouseLocationRepository, never()).findCodesByWarehouseId(any());
        verify(warehouseLocationRepository, never()).save(any());
    }

    @Test
    void blankCodeOnUpdateKeepsExistingCode() {
        WarehouseLocation shelf = WarehouseLocation.builder().id(21L).warehouse(khoHcm)
                .code("MA-NHAN-CU").name("Kệ cũ").locationType(LocationType.SHELF).build();
        when(warehouseLocationRepository.findById(21L)).thenReturn(Optional.of(shelf));
        when(warehouseLocationRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        var response = warehouseService.updateLocation(21L, WarehouseLocationRequest.builder().code(" ").name("Kệ sửa").build(), "staff");
        assertThat(response.getCode()).isEqualTo("MA-NHAN-CU");
        verify(warehouseLocationRepository, never()).findCodesByWarehouseId(any());
    }

    @Test
    @DisplayName("[S5-03] Gán sản phẩm vào kệ lưu trữ và tự động cập nhật vị trí mặc định")
    void assignProductToLocation_resetsOldDefaultInSameWarehouse() {
        WarehouseLocation zoneA = WarehouseLocation.builder()
                .id(201L)
                .warehouse(khoHcm)
                .code("ZONE-A")
                .name("Khu vực A")
                .locationType(LocationType.ZONE)
                .build();

        WarehouseLocation shelf1 = WarehouseLocation.builder()
                .id(202L)
                .warehouse(khoHcm)
                .code("SHELF-A1")
                .name("Kệ A1")
                .locationType(LocationType.SHELF)
                .parent(zoneA)
                .build();

        WarehouseLocation shelf2 = WarehouseLocation.builder()
                .id(203L)
                .warehouse(khoHcm)
                .code("SHELF-A2")
                .name("Kệ A2")
                .locationType(LocationType.SHELF)
                .parent(zoneA)
                .build();

        // Đã có gán mặc định cũ tại shelf1
        WarehouseLocationProduct oldMapping = WarehouseLocationProduct.builder()
                .id(1L)
                .location(shelf1)
                .product(biaHeineken)
                .isDefault(true)
                .build();

        when(warehouseLocationRepository.findById(203L)).thenReturn(Optional.of(shelf2));
        when(productRepository.findById(10L)).thenReturn(Optional.of(biaHeineken));
        when(locationProductRepository.findByWarehouseIdAndProductId(1L, 10L)).thenReturn(List.of(oldMapping));
        when(locationProductRepository.findByLocationIdAndProductId(203L, 10L)).thenReturn(Optional.empty());
        when(locationProductRepository.save(any(WarehouseLocationProduct.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ProductLocationRequest req = ProductLocationRequest.builder()
                .productId(10L)
                .isDefault(true)
                .note("Vị trí chính mới")
                .build();

        ProductLocationResponse res = warehouseService.assignProductToLocation(203L, req, "thukho");

        // Old mapping phải bị gỡ mặc định
        assertThat(oldMapping.getIsDefault()).isFalse();
        verify(locationProductRepository).save(oldMapping);

        // New mapping phải là mặc định
        assertThat(res.getLocationCode()).isEqualTo("SHELF-A2");
        assertThat(res.getIsDefault()).isTrue();
        assertThat(res.getParentZoneName()).isEqualTo("Khu vực A");
    }

    @Test
    @DisplayName("[S5-03] Lấy danh sách soạn hàng (Picking List) cho đơn hàng hiển thị chính xác Kệ và Khu")
    void getOrderPickingList_showsExactShelfAndZone() {
        Dealer dealer = Dealer.builder().id(5L).code("DL-HN").name("Đại lý Hà Nội").warehouse(khoHcm).build();

        WarehouseLocation zoneA = WarehouseLocation.builder()
                .id(201L)
                .warehouse(khoHcm)
                .code("ZONE-A")
                .name("Khu Đồ Uống")
                .locationType(LocationType.ZONE)
                .build();

        WarehouseLocation shelfA1 = WarehouseLocation.builder()
                .id(202L)
                .warehouse(khoHcm)
                .code("SHELF-A1")
                .name("Kệ A1 - Tầng 2")
                .locationType(LocationType.SHELF)
                .parent(zoneA)
                .build();

        WarehouseLocationProduct mapping = WarehouseLocationProduct.builder()
                .id(101L)
                .location(shelfA1)
                .product(biaHeineken)
                .isDefault(true)
                .build();

        OrderItem item = OrderItem.builder()
                .id(501L)
                .product(biaHeineken)
                .quantity(new BigDecimal("10"))
                .unit("thùng")
                .price(new BigDecimal("19000"))
                .totalAmount(new BigDecimal("190000"))
                .build();

        Order order = Order.builder()
                .id(1001L)
                .orderCode("ORD-20261018-001")
                .dealer(dealer)
                .warehouse(khoHcm)
                .status("PENDING")
                .expectedDeliveryDate(LocalDate.now().plusDays(2))
                .items(List.of(item))
                .build();

        when(orderRepository.findById(1001L)).thenReturn(Optional.of(order));
        when(locationProductRepository.findByWarehouseIdAndProductId(1L, 10L)).thenReturn(List.of(mapping));
        when(inventoryRepository.findByWarehouseIdAndProductId(1L, 10L)).thenReturn(Optional.of(
                WarehouseInventory.builder().baseQuantity(new BigDecimal("500")).build()
        ));

        OrderPickingListResponse pickingList = warehouseService.getOrderPickingList(1001L);

        assertThat(pickingList).isNotNull();
        assertThat(pickingList.getOrderCode()).isEqualTo("ORD-20261018-001");
        assertThat(pickingList.getWarehouseCode()).isEqualTo("KHO-HCM");
        assertThat(pickingList.getItems()).hasSize(1);

        OrderPickingItemResponse pickingItem = pickingList.getItems().get(0);
        assertThat(pickingItem.getProductCode()).isEqualTo("SP-KEN330");
        assertThat(pickingItem.getLocationCode()).isEqualTo("SHELF-A1");
        assertThat(pickingItem.getShelfName()).isEqualTo("Kệ A1 - Tầng 2");
        assertThat(pickingItem.getZoneName()).isEqualTo("Khu Đồ Uống");
        assertThat(pickingItem.getLocationDisplay()).isEqualTo("Khu Đồ Uống > Kệ A1 - Tầng 2");
        assertThat(pickingItem.getAvailableQuantity()).isEqualByComparingTo(new BigDecimal("500"));
    }
}
