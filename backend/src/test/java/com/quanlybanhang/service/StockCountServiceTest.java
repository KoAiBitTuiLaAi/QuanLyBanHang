package com.quanlybanhang.service;

import com.quanlybanhang.dto.request.*;
import com.quanlybanhang.dto.response.*;
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
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("StockCountService - Quản lý kiểm kê kho và ghi nhận chênh lệch (S5-08)")
class StockCountServiceTest {

    @Mock
    private WarehouseStockCountRepository stockCounts;

    @Mock
    private WarehouseRepository warehouses;

    @Mock
    private WarehouseInventoryRepository inventories;

    @Mock
    private ProductRepository products;

    @Mock
    private CategoryRepository categories;

    @Mock
    private UserRepository users;

    @Mock
    private AuditLogService audit;

    @InjectMocks
    private StockCountService stockCountService;

    private Warehouse khoChinh;
    private Warehouse khoPhu;
    private Category catBia;
    private Category catNuocNgot;
    private Product biaTiger;
    private Product biaHeineken;
    private Product cocacola;
    private WarehouseInventory invTiger;
    private WarehouseInventory invHeineken;

    @BeforeEach
    void setUp() {
        setSecurityContext("admin", "ROLE_ADMIN");

        khoChinh = Warehouse.builder().id(1L).code("KHO-CHINH").name("Kho Chính").active(true).build();
        khoPhu = Warehouse.builder().id(2L).code("KHO-PHU").name("Kho Phụ").active(true).build();

        catBia = Category.builder().id(10L).code("BIA").name("Bia các loại").build();
        catNuocNgot = Category.builder().id(20L).code("NUOC_NGOT").name("Nước ngọt").build();

        biaTiger = Product.builder().id(101L).code("SP-TIGER").name("Bia Tiger 330ml").unit("lon").category(catBia).active(true).build();
        biaHeineken = Product.builder().id(102L).code("SP-KEN").name("Bia Heineken 330ml").unit("lon").category(catBia).active(true).build();
        cocacola = Product.builder().id(201L).code("SP-COCA").name("Coca Cola 320ml").unit("lon").category(catNuocNgot).active(true).build();

        invTiger = WarehouseInventory.builder()
                .id(1L)
                .warehouse(khoChinh)
                .product(biaTiger)
                .baseQuantity(new BigDecimal("100.0000"))
                .baseUnit("lon")
                .build();

        invHeineken = WarehouseInventory.builder()
                .id(2L)
                .warehouse(khoChinh)
                .product(biaHeineken)
                .baseQuantity(new BigDecimal("50.0000"))
                .baseUnit("lon")
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void setSecurityContext(String username, String... authorities) {
        var auths = Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, "unused", auths));
    }

    @Test
    @DisplayName("Tạo phiếu kiểm kê theo kho: chốt số tồn sổ tại thời điểm tạo")
    void create_AllProductsInWarehouse_SnapshotsBookQuantity() {
        when(warehouses.findByIdForUpdate(1L)).thenReturn(Optional.of(khoChinh));
        when(products.findByActiveTrueOrderByNameAsc()).thenReturn(List.of(biaTiger, biaHeineken, cocacola));
        when(inventories.findByWarehouseIdAndProductIdForUpdate(1L, 101L)).thenReturn(Optional.of(invTiger));
        when(inventories.findByWarehouseIdAndProductIdForUpdate(1L, 102L)).thenReturn(Optional.of(invHeineken));
        when(inventories.findByWarehouseIdAndProductIdForUpdate(1L, 201L)).thenReturn(Optional.empty()); // chưa có tồn => tồn sổ 0
        when(stockCounts.save(any(WarehouseStockCount.class))).thenAnswer(i -> {
            WarehouseStockCount sc = i.getArgument(0);
            sc.setId(1L);
            return sc;
        });

        StockCountCreateRequest req = StockCountCreateRequest.builder()
                .warehouseId(1L)
                .note("Kiểm kê toàn kho cuối tháng")
                .build();

        StockCountResponse res = stockCountService.create(req, "admin");

        assertThat(res).isNotNull();
        assertThat(res.getStatus()).isEqualTo(StockCountStatus.DRAFT);
        assertThat(res.getWarehouseCode()).isEqualTo("KHO-CHINH");
        assertThat(res.getTotalItems()).isEqualTo(3);
        assertThat(res.getItems()).hasSize(3);

        // Kiểm tra tồn sổ từng sản phẩm
        var itemTiger = res.getItems().stream().filter(i -> i.getProductId().equals(101L)).findFirst().orElseThrow();
        assertThat(itemTiger.getBookQuantity()).isEqualByComparingTo("100.0000");
        assertThat(itemTiger.getActualQuantity()).isNull();
        assertThat(itemTiger.getDifferenceQuantity()).isNull();

        var itemKen = res.getItems().stream().filter(i -> i.getProductId().equals(102L)).findFirst().orElseThrow();
        assertThat(itemKen.getBookQuantity()).isEqualByComparingTo("50.0000");

        var itemCoca = res.getItems().stream().filter(i -> i.getProductId().equals(201L)).findFirst().orElseThrow();
        assertThat(itemCoca.getBookQuantity()).isEqualByComparingTo("0.0000");

        verify(audit).logAction(eq("STOCK_COUNT"), anyString(), eq("CREATE"), any(), eq("DRAFT"), anyString(), eq("admin"));
    }

    @Test
    @DisplayName("Tạo phiếu kiểm kê theo nhóm hàng: chỉ lấy sản phẩm thuộc nhóm hàng đó")
    void create_ByCategory_OnlyIncludesCategoryProducts() {
        when(warehouses.findByIdForUpdate(1L)).thenReturn(Optional.of(khoChinh));
        when(categories.findById(10L)).thenReturn(Optional.of(catBia));
        when(products.findByCategoryId(10L)).thenReturn(List.of(biaTiger, biaHeineken));
        when(inventories.findByWarehouseIdAndProductIdForUpdate(1L, 101L)).thenReturn(Optional.of(invTiger));
        when(inventories.findByWarehouseIdAndProductIdForUpdate(1L, 102L)).thenReturn(Optional.of(invHeineken));
        when(stockCounts.save(any(WarehouseStockCount.class))).thenAnswer(i -> {
            WarehouseStockCount sc = i.getArgument(0);
            sc.setId(2L);
            return sc;
        });

        StockCountCreateRequest req = StockCountCreateRequest.builder()
                .warehouseId(1L)
                .categoryId(10L)
                .note("Kiểm kê nhóm bia")
                .build();

        StockCountResponse res = stockCountService.create(req, "admin");

        assertThat(res.getTotalItems()).isEqualTo(2);
        assertThat(res.getItems()).extracting(StockCountItemResponse::getProductCode)
                .containsExactlyInAnyOrder("SP-TIGER", "SP-KEN");
    }

    @Test
    @DisplayName("Tạo phiếu kiểm kê trên kho đã ngừng hoạt động -> lỗi IllegalArgumentException")
    void create_InactiveWarehouse_ThrowsException() {
        khoChinh.setActive(false);
        when(warehouses.findByIdForUpdate(1L)).thenReturn(Optional.of(khoChinh));

        StockCountCreateRequest req = StockCountCreateRequest.builder().warehouseId(1L).build();
        assertThatThrownBy(() -> stockCountService.create(req, "admin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("đã ngừng hoạt động");
    }

    @Test
    @DisplayName("Nhập số đếm thực tế: hệ thống tính chênh lệch từng dòng (actual - book)")
    void updateItems_CalculatesDifference() {
        WarehouseStockCount sc = WarehouseStockCount.builder()
                .id(1L)
                .code("PKK-001")
                .warehouse(khoChinh)
                .status(StockCountStatus.DRAFT)
                .build();

        WarehouseStockCountItem item1 = WarehouseStockCountItem.builder()
                .id(11L)
                .stockCount(sc)
                .product(biaTiger)
                .baseUnit("lon")
                .bookQuantity(new BigDecimal("100.0000"))
                .build();

        WarehouseStockCountItem item2 = WarehouseStockCountItem.builder()
                .id(12L)
                .stockCount(sc)
                .product(biaHeineken)
                .baseUnit("lon")
                .bookQuantity(new BigDecimal("50.0000"))
                .build();

        sc.addItem(item1);
        sc.addItem(item2);

        when(stockCounts.findByIdForUpdate(1L)).thenReturn(Optional.of(sc));
        when(stockCounts.save(any(WarehouseStockCount.class))).thenAnswer(i -> i.getArgument(0));

        // Tiger đếm thực tế 95 (thiếu 5 => difference = -5)
        // Heineken đếm thực tế 53 (thừa 3 => difference = +3)
        StockCountUpdateItemsRequest req = StockCountUpdateItemsRequest.builder()
                .items(List.of(
                        StockCountItemUpdateRequest.builder().productId(101L).actualQuantity(new BigDecimal("95.0000")).note("Hao hụt rách vỏ").build(),
                        StockCountItemUpdateRequest.builder().productId(102L).actualQuantity(new BigDecimal("53.0000")).note("Thừa chưa nhập kho").build()
                ))
                .build();

        StockCountResponse res = stockCountService.updateItems(1L, req, "staff");

        assertThat(res.getItems()).hasSize(2);
        var resTiger = res.getItems().stream().filter(i -> i.getProductId().equals(101L)).findFirst().orElseThrow();
        assertThat(resTiger.getActualQuantity()).isEqualByComparingTo("95.0000");
        assertThat(resTiger.getDifferenceQuantity()).isEqualByComparingTo("-5.0000");
        assertThat(resTiger.getNote()).isEqualTo("Hao hụt rách vỏ");

        var resKen = res.getItems().stream().filter(i -> i.getProductId().equals(102L)).findFirst().orElseThrow();
        assertThat(resKen.getActualQuantity()).isEqualByComparingTo("53.0000");
        assertThat(resKen.getDifferenceQuantity()).isEqualByComparingTo("3.0000");
        assertThat(resKen.getNote()).isEqualTo("Thừa chưa nhập kho");
    }

    @Test
    @DisplayName("Nhập số đếm thực tế âm -> lỗi IllegalArgumentException")
    void updateItems_NegativeActualQuantity_ThrowsException() {
        WarehouseStockCount sc = WarehouseStockCount.builder()
                .id(1L)
                .code("PKK-001")
                .warehouse(khoChinh)
                .status(StockCountStatus.DRAFT)
                .build();
        sc.addItem(WarehouseStockCountItem.builder().id(11L).stockCount(sc).product(biaTiger).baseUnit("lon").bookQuantity(new BigDecimal("100")).build());

        when(stockCounts.findByIdForUpdate(1L)).thenReturn(Optional.of(sc));

        StockCountUpdateItemsRequest req = StockCountUpdateItemsRequest.builder()
                .items(List.of(StockCountItemUpdateRequest.builder().productId(101L).actualQuantity(new BigDecimal("-1")).build()))
                .build();

        assertThatThrownBy(() -> stockCountService.updateItems(1L, req, "staff"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("không được âm");
    }

    @Test
    @DisplayName("Chốt kiểm kê: điều chỉnh tồn về số thực đếm và bắt buộc nhập lý do")
    void complete_AdjustsInventory_AndRequiresReason() {
        WarehouseStockCount sc = WarehouseStockCount.builder()
                .id(1L)
                .code("PKK-001")
                .warehouse(khoChinh)
                .status(StockCountStatus.DRAFT)
                .build();

        WarehouseStockCountItem item1 = WarehouseStockCountItem.builder()
                .id(11L)
                .stockCount(sc)
                .product(biaTiger)
                .baseUnit("lon")
                .bookQuantity(new BigDecimal("100.0000"))
                .actualQuantity(new BigDecimal("95.0000"))
                .differenceQuantity(new BigDecimal("-5.0000"))
                .build();

        WarehouseStockCountItem item2 = WarehouseStockCountItem.builder()
                .id(12L)
                .stockCount(sc)
                .product(biaHeineken)
                .baseUnit("lon")
                .bookQuantity(new BigDecimal("50.0000"))
                .actualQuantity(new BigDecimal("50.0000"))
                .differenceQuantity(BigDecimal.ZERO)
                .build();

        sc.addItem(item1);
        sc.addItem(item2);

        when(stockCounts.findByIdForUpdate(1L)).thenReturn(Optional.of(sc));
        when(inventories.findByWarehouseIdAndProductIdForUpdate(1L, 101L)).thenReturn(Optional.of(invTiger));
        when(inventories.findByWarehouseIdAndProductIdForUpdate(1L, 102L)).thenReturn(Optional.of(invHeineken));
        when(stockCounts.save(any(WarehouseStockCount.class))).thenAnswer(i -> i.getArgument(0));

        markSnapshots(sc);
        StockCountCompleteRequest req = StockCountCompleteRequest.builder()
                .reason("Cân đối kho sau kiểm kê định kỳ tháng 10")
                .build();

        StockCountResponse res = stockCountService.complete(1L, req, "manager");

        assertThat(res.getStatus()).isEqualTo(StockCountStatus.COMPLETED);
        assertThat(res.getAdjustmentReason()).isEqualTo("Cân đối kho sau kiểm kê định kỳ tháng 10");
        assertThat(res.getCompletedBy()).isEqualTo("manager");

        // Kiểm tra tồn kho đã được điều chỉnh về số thực đếm
        assertThat(invTiger.getBaseQuantity()).isEqualByComparingTo("95.0000");
        assertThat(invHeineken.getBaseQuantity()).isEqualByComparingTo("50.0000");
        verify(inventories).save(invTiger);
        verify(inventories).save(invHeineken);

        // Audit log ghi nhận điều chỉnh cho dòng bị lệch
        verify(audit).logAction(eq("INVENTORY"), eq("KHO-CHINH:SP-TIGER"), eq("STOCK_COUNT_ADJUST"),
                eq("100.0000 lon"), eq("95.0000 lon"), eq("Cân đối kho sau kiểm kê định kỳ tháng 10"), eq("manager"));

        // Audit log cho phiếu kiểm kê
        verify(audit).logAction(eq("STOCK_COUNT"), eq("PKK-001"), eq("COMPLETE"),
                eq("DRAFT"), eq("COMPLETED"), anyString(), eq("manager"));
    }

    @Test
    @DisplayName("Chốt kiểm kê thiếu lý do -> lỗi IllegalArgumentException")
    void complete_MissingReason_ThrowsException() {
        WarehouseStockCount sc = WarehouseStockCount.builder()
                .id(1L)
                .code("PKK-001")
                .warehouse(khoChinh)
                .status(StockCountStatus.DRAFT)
                .build();

        when(stockCounts.findByIdForUpdate(1L)).thenReturn(Optional.of(sc));

        StockCountCompleteRequest req = StockCountCompleteRequest.builder()
                .reason("   ") // rỗng
                .build();

        assertThatThrownBy(() -> stockCountService.complete(1L, req, "manager"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Bắt buộc nhập lý do khi chốt kiểm kê");
    }

    @Test
    @DisplayName("Chốt kiểm kê khi còn sản phẩm chưa nhập số đếm thực tế -> lỗi IllegalStateException")
    void complete_UncountedItems_ThrowsException() {
        WarehouseStockCount sc = WarehouseStockCount.builder()
                .id(1L)
                .code("PKK-001")
                .warehouse(khoChinh)
                .status(StockCountStatus.DRAFT)
                .build();

        sc.addItem(WarehouseStockCountItem.builder()
                .id(11L)
                .stockCount(sc)
                .product(biaTiger)
                .baseUnit("lon")
                .bookQuantity(new BigDecimal("100"))
                .actualQuantity(null) // chưa đếm
                .build());

        when(stockCounts.findByIdForUpdate(1L)).thenReturn(Optional.of(sc));

        StockCountCompleteRequest req = StockCountCompleteRequest.builder()
                .reason("Chốt khi chưa đếm hết")
                .build();

        assertThatThrownBy(() -> stockCountService.complete(1L, req, "manager"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Vui lòng nhập số lượng đếm thực tế cho tất cả sản phẩm");
    }

    @Test
    @DisplayName("Chỉ Quản lý kho (WAREHOUSE_ADJUST) được chốt phiếu kiểm kê: Nhân viên không có quyền bị từ chối 403")
    void complete_WithoutAdjustPermission_ThrowsAccessDenied() {
        // User chỉ có quyền WAREHOUSE_VIEW, không có WAREHOUSE_ADJUST hay ROLE_ADMIN
        setSecurityContext("staff", "WAREHOUSE_VIEW", "ROLE_WAREHOUSE_STAFF");

        StockCountCompleteRequest req = StockCountCompleteRequest.builder()
                .reason("Nhân viên thử chốt kiểm kê")
                .build();

        assertThatThrownBy(() -> stockCountService.complete(1L, req, "staff"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Bạn cần quyền WAREHOUSE_ADJUST");
    }

    @Test
    @DisplayName("Quản lý kho có quyền WAREHOUSE_ADJUST được phép chốt phiếu kiểm kê")
    void complete_WithAdjustPermission_Allowed() {
        // Quản lý kho có WAREHOUSE_ADJUST
        setSecurityContext("manager", "WAREHOUSE_ADJUST", "ROLE_WAREHOUSE_MANAGER");

        User managerUser = User.builder().username("manager").warehouses(Set.of(khoChinh)).build();
        when(users.findByUsername("manager")).thenReturn(Optional.of(managerUser));

        WarehouseStockCount sc = WarehouseStockCount.builder()
                .id(1L)
                .code("PKK-001")
                .warehouse(khoChinh)
                .status(StockCountStatus.DRAFT)
                .build();
        sc.addItem(WarehouseStockCountItem.builder()
                .id(11L)
                .stockCount(sc)
                .product(biaTiger)
                .baseUnit("lon")
                .bookQuantity(new BigDecimal("100"))
                .actualQuantity(new BigDecimal("100"))
                .differenceQuantity(BigDecimal.ZERO)
                .build());

        when(stockCounts.findByIdForUpdate(1L)).thenReturn(Optional.of(sc));
        when(inventories.findByWarehouseIdAndProductIdForUpdate(1L, 101L)).thenReturn(Optional.of(invTiger));
        when(stockCounts.save(any())).thenAnswer(i -> i.getArgument(0));

        markSnapshots(sc);
        StockCountCompleteRequest req = StockCountCompleteRequest.builder()
                .reason("Quản lý kho chốt kiểm kê")
                .build();

        StockCountResponse res = stockCountService.complete(1L, req, "manager");
        assertThat(res.getStatus()).isEqualTo(StockCountStatus.COMPLETED);
    }

    @Test
    @DisplayName("Kiểm tra phạm vi kho: không thể thao tác trên kho không được phân công -> 403 AccessDenied")
    void scope_WarehouseOutOfScope_ThrowsAccessDenied() {
        // User chỉ được giao kho Chính (id=1), không được giao kho Phụ (id=2)
        setSecurityContext("manager", "WAREHOUSE_ADJUST", "ROLE_WAREHOUSE_MANAGER");
        User managerUser = User.builder().username("manager").warehouses(Set.of(khoChinh)).build();
        when(users.findByUsername("manager")).thenReturn(Optional.of(managerUser));

        StockCountCreateRequest req = StockCountCreateRequest.builder().warehouseId(2L).build();

        assertThatThrownBy(() -> stockCountService.create(req, "manager"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Kho này không thuộc phạm vi được giao");
    }

    @Test
    @DisplayName("Hủy phiếu kiểm kê khi đang ở trạng thái DRAFT")
    void cancel_DraftStockCount_Success() {
        WarehouseStockCount sc = WarehouseStockCount.builder()
                .id(1L)
                .code("PKK-001")
                .warehouse(khoChinh)
                .status(StockCountStatus.DRAFT)
                .build();

        when(stockCounts.findByIdForUpdate(1L)).thenReturn(Optional.of(sc));
        when(stockCounts.save(any())).thenAnswer(i -> i.getArgument(0));

        StockCountResponse res = stockCountService.cancel(1L, new StockCountCancelRequest("Hủy do chọn nhầm kho"), "admin");

        assertThat(res.getStatus()).isEqualTo(StockCountStatus.CANCELLED);
        assertThat(res.getCancelReason()).isEqualTo("Hủy do chọn nhầm kho");
        assertThat(res.getCancelledBy()).isEqualTo("admin");
    }

    @Test
    @DisplayName("Hủy phiếu kiểm kê đã COMPLETED -> lỗi IllegalStateException")
    void cancel_CompletedStockCount_ThrowsException() {
        WarehouseStockCount sc = WarehouseStockCount.builder()
                .id(1L)
                .code("PKK-001")
                .warehouse(khoChinh)
                .status(StockCountStatus.COMPLETED)
                .build();

        when(stockCounts.findByIdForUpdate(1L)).thenReturn(Optional.of(sc));

        assertThatThrownBy(() -> stockCountService.cancel(1L, new StockCountCancelRequest("Thử hủy"), "admin"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Chỉ được hủy phiếu kiểm kê ở trạng thái mới tạo");
    }

    @Test
    void complete_ChangedVersionEvenWithSameQuantity_RejectsBeforeWriting() {
        WarehouseStockCount sc = countedDraft();
        markSnapshots(sc);
        invTiger.setVersion(2L);
        when(stockCounts.findByIdForUpdate(1L)).thenReturn(Optional.of(sc));
        when(inventories.findByWarehouseIdAndProductIdForUpdate(1L, 101L)).thenReturn(Optional.of(invTiger));

        assertThatThrownBy(() -> stockCountService.complete(1L,
                new StockCountCompleteRequest("recount", null), "admin"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("hủy phiếu");
        verify(inventories, never()).save(any());
        verifyNoInteractions(audit);
    }

    @Test
    void complete_BelowReserved_RejectsWithoutInventoryOrAuditWrites() {
        WarehouseStockCount sc = countedDraft();
        markSnapshots(sc);
        invTiger.setReservedQuantity(new BigDecimal("96"));
        when(stockCounts.findByIdForUpdate(1L)).thenReturn(Optional.of(sc));
        when(inventories.findByWarehouseIdAndProductIdForUpdate(1L, 101L)).thenReturn(Optional.of(invTiger));

        assertThatThrownBy(() -> stockCountService.complete(1L,
                new StockCountCompleteRequest("recount", null), "admin"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("giữ chỗ");
        assertThat(invTiger.getBaseQuantity()).isEqualByComparingTo("100");
        verify(inventories, never()).save(any());
        verifyNoInteractions(audit);
    }

    @Test
    void updateItems_ZeroIsCountedAndNullRestoresUncountedState() {
        WarehouseStockCount sc = countedDraft();
        when(stockCounts.findByIdForUpdate(1L)).thenReturn(Optional.of(sc));
        when(stockCounts.save(any())).thenAnswer(i -> i.getArgument(0));
        var zero = new StockCountUpdateItemsRequest(List.of(new StockCountItemUpdateRequest(101L, BigDecimal.ZERO, null)), null);
        StockCountResponse counted = stockCountService.updateItems(1L, zero, "admin");
        assertThat(counted.getUncountedItems()).isZero();
        assertThat(counted.getItems().get(0).getDifferenceQuantity()).isEqualByComparingTo("-100");
        var cleared = new StockCountUpdateItemsRequest(List.of(new StockCountItemUpdateRequest(101L, null, null)), null);
        StockCountResponse uncounted = stockCountService.updateItems(1L, cleared, "admin");
        assertThat(uncounted.getUncountedItems()).isEqualTo(1);
        assertThat(uncounted.getItems().get(0).getDifferenceQuantity()).isNull();
    }

    @Test
    void complete_AdjustPermissionOnViewerRole_IsSufficient() {
        setSecurityContext("viewer", "ROLE_VIEWER", "WAREHOUSE_ADJUST");
        when(users.findByUsername("viewer")).thenReturn(Optional.of(User.builder()
                .username("viewer").warehouses(Set.of(khoChinh)).build()));
        WarehouseStockCount sc = countedDraft();
        markSnapshots(sc);
        when(stockCounts.findByIdForUpdate(1L)).thenReturn(Optional.of(sc));
        when(inventories.findByWarehouseIdAndProductIdForUpdate(1L, 101L)).thenReturn(Optional.of(invTiger));
        when(stockCounts.save(any())).thenAnswer(i -> i.getArgument(0));
        assertThat(stockCountService.complete(1L, new StockCountCompleteRequest("recount", null), "viewer").getStatus())
                .isEqualTo(StockCountStatus.COMPLETED);
    }

    private WarehouseStockCount countedDraft() {
        WarehouseStockCount sc = WarehouseStockCount.builder().id(1L).code("PKK-HARDEN")
                .warehouse(khoChinh).build();
        WarehouseStockCountItem item = WarehouseStockCountItem.builder().product(biaTiger).baseUnit("lon")
                .bookQuantity(new BigDecimal("100")).actualQuantity(new BigDecimal("95")).build();
        item.calculateDifference();
        sc.addItem(item);
        return sc;
    }

    private void markSnapshots(WarehouseStockCount sc) {
        when(warehouses.findByIdForUpdate(1L)).thenReturn(Optional.of(khoChinh));
        for (WarehouseStockCountItem item : sc.getItems()) {
            WarehouseInventory inv = item.getProduct().getId().equals(101L) ? invTiger : invHeineken;
            item.setSnapshotCaptured(true);
            item.setSnapshotInventoryId(inv.getId());
            item.setSnapshotInventoryVersion(inv.getVersion());
        }
    }
}
