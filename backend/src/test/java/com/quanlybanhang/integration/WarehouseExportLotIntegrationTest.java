package com.quanlybanhang.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.quanlybanhang.config.RolePermissionConfig;
import com.quanlybanhang.model.*;
import com.quanlybanhang.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration Test cho tính năng Gợi ý lô xuất FEFO và Ma trận phân quyền (S6-04).
 * Chạy trên cơ sở dữ liệu in-memory H2, kiểm thử qua HTTP, Spring Security và JPA thực tế.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:export_lot_fefo;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
    "spring.jpa.open-in-view=false", "spring.jpa.show-sql=false",
    "app.jwt.secret=export-lot-fefo-test-secret-at-least-32-chars",
    "app.bootstrap.admin-password=ExportLotTest2026", "app.bootstrap.admin-email=exportlot@example.invalid",
    "app.seed-demo-users=false", "app.seed-demo-data=false", "app.order.reservation-check-interval-ms=3600000"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WarehouseExportLotIntegrationTest {

    @Autowired TestRestTemplate http;
    @Autowired WarehouseRepository warehouses;
    @Autowired WarehouseInventoryRepository inventories;
    @Autowired WarehouseLotInventoryRepository lots;
    @Autowired WarehouseVoucherRepository vouchers;
    @Autowired ProductRepository products;
    @Autowired CategoryRepository categories;
    @Autowired UnitConversionRepository conversions;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired PasswordEncoder passwords;
    @Autowired RolePermissionConfig matrix;
    @Autowired AuditLogRepository auditLogs;
    @Autowired WarehouseStockCountRepository stockCounts;
    @Autowired OrderRepository stockOrders;
    @Autowired OrderStatusHistoryRepository stockHistories;
    @Autowired WarehouseTransferRepository stockTransfers;

    Warehouse warehouse, outsideWarehouse;
    Product product;
    String staffToken, adminToken;
    WarehouseLotInventory lotA, lotB, lotC;
    User staffUser;

    @BeforeEach
    void setUp() {
        String key = UUID.randomUUID().toString().substring(0, 8);
        warehouse = warehouses.save(Warehouse.builder().code("WH-EX-" + key).name("Kho Xuất " + key).active(true).build());
        outsideWarehouse = warehouses.save(Warehouse.builder().code("WH-OUT-" + key).name("Kho Ngoài " + key).active(true).build());

        Category category = categories.save(Category.builder().code("CAT-" + key).name("Category " + key).build());
        product = products.save(Product.builder()
                .code("P-LOT-" + key)
                .name("Sữa tươi có lô " + key)
                .category(category)
                .unit("lon")
                .manageLot(true)
                .price(new BigDecimal("20000"))
                .active(true)
                .build());

        // Tổng tồn: 75 lon
        inventories.save(WarehouseInventory.builder()
                .warehouse(warehouse)
                .product(product)
                .baseUnit("lon")
                .baseQuantity(new BigDecimal("75.0000"))
                .reservedQuantity(BigDecimal.ZERO)
                .build());

        // Lot A: 5 lon, HSD còn 10 ngày
        lotA = lots.save(WarehouseLotInventory.builder()
                .warehouse(warehouse)
                .product(product)
                .lotNumber("LOT-A-" + key)
                .expiryDate(LocalDate.now().plusDays(10))
                .manufacturingDate(LocalDate.now().minusDays(20))
                .baseQuantity(new BigDecimal("5.0000"))
                .baseUnit("lon")
                .build());

        // Lot B: 20 lon, HSD còn 30 ngày
        lotB = lots.save(WarehouseLotInventory.builder()
                .warehouse(warehouse)
                .product(product)
                .lotNumber("LOT-B-" + key)
                .expiryDate(LocalDate.now().plusDays(30))
                .manufacturingDate(LocalDate.now().minusDays(10))
                .baseQuantity(new BigDecimal("20.0000"))
                .baseUnit("lon")
                .build());

        // Lot C: 50 lon, HSD còn 90 ngày
        lotC = lots.save(WarehouseLotInventory.builder()
                .warehouse(warehouse)
                .product(product)
                .lotNumber("LOT-C-" + key)
                .expiryDate(LocalDate.now().plusDays(90))
                .manufacturingDate(LocalDate.now().minusDays(5))
                .baseQuantity(new BigDecimal("50.0000"))
                .baseUnit("lon")
                .build());

        // Gán quyền WAREHOUSE_EXPORT cho ROLE_WAREHOUSE_STAFF trong ma trận
        matrix.updateRolePermissions(ERole.ROLE_WAREHOUSE_STAFF, Set.of(Permission.WAREHOUSE_EXPORT, Permission.WAREHOUSE_VIEW));

        User staff = users.save(User.builder()
                .username("staff-" + key)
                .email(key + "@staff.invalid")
                .password(passwords.encode("ExportLotTest2026"))
                .roles(Set.of(roles.findByName(ERole.ROLE_WAREHOUSE_STAFF).orElseThrow()))
                .warehouses(Set.of(warehouse)) // Phân công ở warehouse, không có outsideWarehouse
                .build());

        staffToken = login(staff.getUsername(), "ExportLotTest2026");
        staffUser = staff;
        adminToken = login("admin", "ExportLotTest2026");
    }

    @Test
    @DisplayName("S6-04 AC1: API GET /api/warehouse/export/suggest-lot gợi ý lô có HSD gần nhất còn đủ số lượng")
    void suggestLot_FEFO_ReturnsLotWithNearestExpiryAndSufficientStock() {
        // Cần 10 lon -> Lot A (5 lon) không đủ -> gợi ý Lot B (20 lon, HSD gần nhất trong các lô đủ hàng)
        String url = "/api/warehouse/export/suggest-lot?warehouseId=" + warehouse.getId() +
                "&productId=" + product.getId() + "&quantity=10&unit=lon";

        JsonNode response = call(HttpMethod.GET, url, staffToken, null, 200);
        JsonNode data = response.get("data");

        assertThat(data.get("hasSufficientStock").asBoolean()).isTrue();
        assertThat(data.at("/suggestedLot/lotNumber").asText()).isEqualTo(lotB.getLotNumber());
        assertThat(data.at("/suggestedLot/availableQuantity").decimalValue()).isEqualByComparingTo("20");
        assertThat(data.get("availableLots").size()).isEqualTo(3);
    }

    @Test
    @DisplayName("S6-04 AC1: API POST /api/warehouse/export/suggest-lots gợi ý hàng loạt")
    void suggestLots_Batch_ReturnsSuggestions() {
        Map<String, Object> body = Map.of(
                "warehouseId", warehouse.getId(),
                "items", List.of(Map.of("productId", product.getId(), "quantity", 3, "unit", "lon"))
        );

        JsonNode response = call(HttpMethod.POST, "/api/warehouse/export/suggest-lots", staffToken, body, 200);
        JsonNode list = response.get("data");
        assertThat(list.isArray()).isTrue();
        assertThat(list.size()).isEqualTo(1);
        // Cần 3 lon -> Lot A (5 lon) đủ số lượng và có HSD gần nhất
        assertThat(list.get(0).at("/suggestedLot/lotNumber").asText()).isEqualTo(lotA.getLotNumber());
    }

    @Test
    @DisplayName("S6-04 AC2: Xuất kho chọn lô khác (Lot C) nhưng KHÔNG nhập lý do -> trả 400 Bad Request")
    void exportGoods_SelectDifferentLotWithoutReason_Returns400() {
        Map<String, Object> body = Map.of(
                "warehouseId", warehouse.getId(),
                "type", "EXPORT",
                "items", List.of(Map.of(
                        "productId", product.getId(),
                        "quantity", 10,
                        "unit", "lon",
                        "lotNumber", lotC.getLotNumber() // Lô gợi ý cho 10 lon là Lot B, đây chọn Lot C
                        // Không có overrideReason
                ))
        );

        JsonNode response = call(HttpMethod.POST, "/api/warehouse/export", staffToken, body, 400);
        assertThat(response.get("message").asText()).contains("bắt buộc phải nhập lý do");
    }

    @Test
    @DisplayName("S6-04 AC2 & AC3: Xuất kho chọn lô khác (Lot C) và CÓ nhập lý do -> 200 OK và trừ đúng tồn lô C")
    void exportGoods_SelectDifferentLotWithReason_SuccessAndDeductsSelectedLot() {
        Map<String, Object> body = Map.of(
                "warehouseId", warehouse.getId(),
                "type", "EXPORT",
                "voucherCode", "PXK-" + UUID.randomUUID(),
                "items", List.of(Map.of(
                        "productId", product.getId(),
                        "quantity", 10,
                        "unit", "lon",
                        "lotNumber", lotC.getLotNumber(),
                        "overrideReason", "Khách hàng yêu cầu date xa để vận chuyển đường dài"
                ))
        );

        JsonNode response = call(HttpMethod.POST, "/api/warehouse/export", staffToken, body, 200);
        JsonNode item = response.at("/data/items/0");

        assertThat(item.get("lotNumber").asText()).isEqualTo(lotC.getLotNumber());
        assertThat(item.get("suggestedLotNumber").asText()).isEqualTo(lotB.getLotNumber());
        assertThat(item.get("overrideReason").asText()).isEqualTo("Khách hàng yêu cầu date xa để vận chuyển đường dài");

        // Kiểm tra trừ đúng tồn cơ sở của Lot C: 50 - 10 = 40
        var updatedLotC = lots.findByWarehouseIdAndProductIdAndLotNumber(warehouse.getId(), product.getId(), lotC.getLotNumber()).orElseThrow();
        assertThat(updatedLotC.getBaseQuantity()).isEqualByComparingTo("40");

        // Lot B không bị trừ: 20
        var unchangedLotB = lots.findByWarehouseIdAndProductIdAndLotNumber(warehouse.getId(), product.getId(), lotB.getLotNumber()).orElseThrow();
        assertThat(unchangedLotB.getBaseQuantity()).isEqualByComparingTo("20");
    }

    @Test
    @DisplayName("Ma trận phân quyền: Thu hồi WAREHOUSE_EXPORT -> API trả ngay 403 Forbidden; cấp lại -> trả 200 OK")
    void permissionMatrix_RevokeAndGrant_ImmediateEnforcement() {
        String suggestUrl = "/api/warehouse/export/suggest-lot?warehouseId=" + warehouse.getId() +
                "&productId=" + product.getId() + "&quantity=5&unit=lon";

        // Trước khi thu hồi: 200 OK
        call(HttpMethod.GET, suggestUrl, staffToken, null, 200);

        // Thu hồi toàn bộ quyền kho khỏi ROLE_WAREHOUSE_STAFF trong DB
        matrix.updateRolePermissions(ERole.ROLE_WAREHOUSE_STAFF, Collections.emptySet());

        // Request tiếp theo trên token hiện tại phải bị 403 ngay lập tức!
        call(HttpMethod.GET, suggestUrl, staffToken, null, 403);

        Map<String, Object> exportBody = Map.of(
                "warehouseId", warehouse.getId(),
                "type", "EXPORT",
                "items", List.of(Map.of("productId", product.getId(), "quantity", 5, "unit", "lon"))
        );
        call(HttpMethod.POST, "/api/warehouse/export", staffToken, exportBody, 403);

        // Cấp lại quyền WAREHOUSE_EXPORT
        matrix.updateRolePermissions(ERole.ROLE_WAREHOUSE_STAFF, Set.of(Permission.WAREHOUSE_EXPORT));

        // Request tiếp theo thành công 200 OK
        call(HttpMethod.GET, suggestUrl, staffToken, null, 200);
    }

    @Test
    @DisplayName("Phạm vi dữ liệu kho: Truy cập kho ngoài phạm vi phân công -> 403 Forbidden")
    void warehouseScope_OutsideWarehouse_Forbidden() {
        String suggestUrl = "/api/warehouse/export/suggest-lot?warehouseId=" + outsideWarehouse.getId() +
                "&productId=" + product.getId() + "&quantity=5&unit=lon";

        // Nhân viên kho chỉ được phân công ở warehouse, gọi outsideWarehouse -> 403
        call(HttpMethod.GET, suggestUrl, staffToken, null, 403);

        // Admin luôn được phép truy cập mọi kho
        call(HttpMethod.GET, suggestUrl, adminToken, null, 200);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helper Methods
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void s601_optionsAndAllLotsRespectAssignedWarehouseAndLiveMatrix() {
        lots.save(WarehouseLotInventory.builder().warehouse(outsideWarehouse).product(product)
                .lotNumber("PRIVATE").expiryDate(LocalDate.now().plusDays(5)).baseQuantity(BigDecimal.ONE).baseUnit("lon").build());
        JsonNode options = call(HttpMethod.GET, "/api/warehouse/lots/options", staffToken, null, 200).get("data");
        assertThat(options.get("warehouses").size()).isEqualTo(1);
        assertThat(options.at("/warehouses/0/id").asLong()).isEqualTo(warehouse.getId());
        assertThat(options.get("canConfigure").asBoolean()).isFalse();
        JsonNode all = call(HttpMethod.GET, "/api/warehouse/inventory/lots", staffToken, null, 200).get("data");
        for (JsonNode lot : all) assertThat(lot.get("warehouseId").asLong()).isEqualTo(warehouse.getId());
        call(HttpMethod.GET, "/api/warehouse/inventory/lots?warehouseId=" + outsideWarehouse.getId(), staffToken, null, 403);
        matrix.updateRolePermissions(ERole.ROLE_WAREHOUSE_STAFF, Set.of());
        call(HttpMethod.GET, "/api/warehouse/lots/options", staffToken, null, 403);
        matrix.updateRolePermissions(ERole.ROLE_WAREHOUSE_STAFF, Set.of(Permission.WAREHOUSE_VIEW));
        call(HttpMethod.GET, "/api/warehouse/lots/options", staffToken, null, 200);
        call(HttpMethod.GET, "/api/warehouse/lots/options", adminToken, null, 200);
    }

    @Test
    void s601_expiredLotCannotUseSpoofedClientDateAndOverrideRequiresGrantConfirmationReason() {
        lotA.setExpiryDate(LocalDate.now().minusDays(1));lots.save(lotA);
        Map<String,Object> body = new HashMap<>();body.put("warehouseId",warehouse.getId());
        body.put("items",List.of(Map.of("productId",product.getId(),"quantity",1,"unit","lon",
                "lotNumber",lotA.getLotNumber(),"expiryDate",LocalDate.now().plusYears(2).toString(),"overrideReason","Xử lý lô quá hạn")));
        call(HttpMethod.POST,"/api/warehouse/export",staffToken,body,403);
        matrix.updateRolePermissions(ERole.ROLE_WAREHOUSE_STAFF,Set.of(Permission.WAREHOUSE_VIEW,Permission.WAREHOUSE_EXPORT,Permission.WAREHOUSE_APPROVE));
        call(HttpMethod.POST,"/api/warehouse/export",staffToken,body,400);
        body.put("overrideExpiredLot",true);
        call(HttpMethod.POST,"/api/warehouse/export",staffToken,body,400);
        assertThat(lots.findById(lotA.getId()).orElseThrow().getBaseQuantity()).isEqualByComparingTo("5");
        body.put("expiredLotOverrideReason","Xuất xử lý hàng quá hạn");
        JsonNode result=call(HttpMethod.POST,"/api/warehouse/export",staffToken,body,200);
        assertThat(result.at("/data/overrideExpiredLot").asBoolean()).isTrue();
        assertThat(result.at("/data/items/0/expiryDate").asText()).isEqualTo(LocalDate.now().minusDays(1).toString());
        assertThat(lots.findById(lotA.getId()).orElseThrow().getBaseQuantity()).isEqualByComparingTo("4");
        assertThat(auditLogs.findAll()).anySatisfy(log -> {
            assertThat(log.getAction()).isEqualTo("EXPIRED_LOT_OVERRIDE");
            assertThat(log.getEntityId()).isEqualTo(warehouse.getId()+":"+product.getId()+":"+lotA.getLotNumber());
            assertThat(log.getNewValue()).contains("Xuất xử lý hàng quá hạn");
        });
        matrix.updateRolePermissions(ERole.ROLE_WAREHOUSE_STAFF,Set.of(Permission.WAREHOUSE_VIEW,Permission.WAREHOUSE_EXPORT));
        call(HttpMethod.POST,"/api/warehouse/export",staffToken,body,403);
        assertThat(lots.findById(lotA.getId()).orElseThrow().getBaseQuantity()).isEqualByComparingTo("4");
    }

    @Test
    void s601_todayIsNotExpiredAndSkuThresholdControlsAlerts() {
        lotA.setExpiryDate(LocalDate.now());lots.save(lotA);
        product.setExpiryWarningDays(1);products.save(product);
        JsonNode data=call(HttpMethod.GET,"/api/warehouse/inventory/lots?productId="+product.getId(),staffToken,null,200).get("data");
        JsonNode today=data.get(0);
        assertThat(today.get("daysUntilExpiry").asLong()).isZero();
        assertThat(today.get("isExpired").asBoolean()).isFalse();
        assertThat(today.get("isExpiringSoon").asBoolean()).isTrue();
        JsonNode alerts=call(HttpMethod.GET,"/api/warehouse/lot-expiry-alerts?productId="+product.getId(),staffToken,null,200).get("data");
        assertThat(alerts.get("expiringSoonCount").asInt()).isEqualTo(1);
        call(HttpMethod.GET,"/api/warehouse/inventory/lots?thresholdDays=0",staffToken,null,400);
        call(HttpMethod.GET,"/api/warehouse/inventory/lots?status=WRONG",staffToken,null,400);
    }

    @Test
    void s601_toggleStoresSettingsButCannotDisablePositiveLotStock() throws Exception {
        patchSettings(staffToken,true,15,403);
        matrix.updateRolePermissions(ERole.ROLE_WAREHOUSE_STAFF,Set.of(Permission.WAREHOUSE_VIEW,Permission.PRODUCT_EDIT));
        call(HttpMethod.PUT,"/api/products/"+product.getId(),staffToken,Map.of("code",product.getCode(),
                "name",product.getName(),"categoryId",product.getCategory().getId(),"unit","lon","price",20000),200);
        assertThat(products.findById(product.getId()).orElseThrow().getManageLot()).isTrue();
        patchSettings(staffToken,true,15,200);
        assertThat(products.findById(product.getId()).orElseThrow().getExpiryWarningDays()).isEqualTo(15);
        patchSettings(staffToken,false,15,400);
        patchSettings(staffToken,true,0,400);
        for (WarehouseLotInventory lot : lots.findByWarehouseIdAndProductId(warehouse.getId(),product.getId())) {
            lot.setBaseQuantity(BigDecimal.ZERO);lots.save(lot);
        }
        patchSettings(staffToken,false,7,200);
        assertThat(products.findById(product.getId()).orElseThrow().getManageLot()).isFalse();
        matrix.updateRolePermissions(ERole.ROLE_WAREHOUSE_STAFF,Set.of(Permission.WAREHOUSE_VIEW));
        patchSettings(staffToken,true,7,403);
    }

    @Test
    void s601_legacyDisabledSkuCannotBypassExpiryAndUnknownExpiryIsBlocked() {
        product.setManageLot(false);products.save(product);
        lotA.setExpiryDate(LocalDate.now().minusDays(1));lots.save(lotA);
        Map<String,Object> body=Map.of("warehouseId",warehouse.getId(),"items",List.of(Map.of(
                "productId",product.getId(),"quantity",1,"unit","lon","lotNumber",lotA.getLotNumber(),"overrideReason","Xử lý lô")));
        call(HttpMethod.POST,"/api/warehouse/export",staffToken,body,403);
        lotA.setExpiryDate(null);lots.save(lotA);
        call(HttpMethod.POST,"/api/warehouse/export",adminToken,body,400);
        assertThat(lots.findById(lotA.getId()).orElseThrow().getBaseQuantity()).isEqualByComparingTo("5");
    }

    @Test
    void s602_optionsAndBothReadEndpointsFollowLiveMatrixWithoutProductView() {
        JsonNode options=call(HttpMethod.GET,"/api/warehouse/stock-card/options",staffToken,null,200).get("data");
        assertThat(options.get("warehouses").size()).isEqualTo(1);
        assertThat(options.at("/warehouses/0/id").asLong()).isEqualTo(warehouse.getId());
        matrix.updateRolePermissions(ERole.ROLE_WAREHOUSE_STAFF,Set.of());
        call(HttpMethod.GET,"/api/warehouse/stock-card/options",staffToken,null,403);
        call(HttpMethod.GET,"/api/warehouse/stock-card/"+product.getId(),staffToken,null,403);
        call(HttpMethod.GET,"/api/warehouse/stock-card/"+product.getId()+"/sources/VOUCHER/1",staffToken,null,403);
        matrix.updateRolePermissions(ERole.ROLE_WAREHOUSE_STAFF,Set.of(Permission.WAREHOUSE_VIEW));
        call(HttpMethod.GET,"/api/warehouse/stock-card/options",staffToken,null,200);
        call(HttpMethod.GET,"/api/warehouse/stock-card/options",adminToken,null,200);
    }

    @Test
    void s602_dateFilterAndPaginationDoNotResetOpeningStockAndSourcesAreScoped() {
        var t=java.time.LocalDateTime.of(2026,1,1,10,0);
        var incoming=stockVoucher(WarehouseVoucherType.IMPORT,"CONFIRMED",20,t,warehouse);
        var outgoing=stockVoucher(WarehouseVoucherType.EXPORT,"RECORDED",5,t.plusDays(1),warehouse);
        stockVoucher(WarehouseVoucherType.IMPORT,"DRAFT",999,t.plusDays(2),warehouse);
        var inv=inventories.findByWarehouseIdAndProductId(warehouse.getId(),product.getId()).orElseThrow();inv.setBaseQuantity(new BigDecimal("90"));inventories.save(inv);
        String path="/api/warehouse/stock-card/"+product.getId();
        JsonNode first=call(HttpMethod.GET,path+"?size=1",staffToken,null,200).get("data");
        assertThat(first.get("totalElements").asInt()).isEqualTo(2);assertThat(first.get("openingBalance").decimalValue()).isEqualByComparingTo("75");
        assertThat(first.at("/entries/0/runningBaseBalance").decimalValue()).isEqualByComparingTo("95");
        JsonNode second=call(HttpMethod.GET,path+"?page=999&size=1",staffToken,null,200).get("data");
        assertThat(second.get("currentPage").asInt()).isEqualTo(1);assertThat(second.get("openingBalance").decimalValue()).isEqualByComparingTo("95");
        JsonNode filtered=call(HttpMethod.GET,path+"?fromDate=2026-01-02T00:00:00&toDate=2026-01-02T23:59:59.999999999",staffToken,null,200).get("data");
        assertThat(filtered.get("openingBalance").decimalValue()).isEqualByComparingTo("95");assertThat(filtered.at("/entries/0/runningBaseBalance").decimalValue()).isEqualByComparingTo("90");
        JsonNode original=call(HttpMethod.GET,path+"/sources/VOUCHER/"+outgoing.getId(),staffToken,null,200).get("data");
        assertThat(original.get("sourceCode").asText()).isEqualTo(outgoing.getVoucherCode());assertThat(original.has("price")).isFalse();
        var outside=stockVoucher(WarehouseVoucherType.IMPORT,"CONFIRMED",2,t,outsideWarehouse);
        call(HttpMethod.GET,path+"?warehouseId="+outsideWarehouse.getId(),staffToken,null,403);
        call(HttpMethod.GET,path+"/sources/VOUCHER/"+outside.getId(),staffToken,null,403);
        call(HttpMethod.GET,path+"?fromDate=2026-02-01T00:00:00&toDate=2026-01-01T00:00:00",staffToken,null,400);
        JsonNode before=call(HttpMethod.GET,path+"?toDate=2025-01-01T00:00:00",staffToken,null,200).get("data");
        assertThat(before.get("entries").size()).isZero();assertThat(before.get("openingBalance").decimalValue()).isEqualByComparingTo("75");
    }

    @Test
    void s602_stockCountOrderExportAndTransferAllAppearOnceWithSignedChanges() {
        var t=java.time.LocalDateTime.of(2026,2,1,10,0);
        var count=WarehouseStockCount.builder().code("COUNT-"+UUID.randomUUID()).warehouse(warehouse).status(StockCountStatus.COMPLETED).completedAt(t).adjustmentReason("Thiếu thực tế").build();
        count.addItem(WarehouseStockCountItem.builder().product(product).baseUnit("lon").bookQuantity(new BigDecimal("75")).actualQuantity(new BigDecimal("70")).differenceQuantity(new BigDecimal("-5")).build());stockCounts.save(count);
        var order=Order.builder().orderCode("ORDER-"+UUID.randomUUID()).warehouse(warehouse).status("EXPORTED").build();
        var item=OrderItem.builder().product(product).unit("thùng").quantity(BigDecimal.ONE).conversionFactor(new BigDecimal("6")).baseUnit("lon").baseQuantity(new BigDecimal("6")).build();item.setOrder(order);order.getItems().add(item);stockOrders.save(order);
        stockHistories.save(OrderStatusHistory.builder().order(order).newStatus("EXPORTED").changedAt(t.plusHours(1)).build());
        stockHistories.save(OrderStatusHistory.builder().order(order).newStatus("EXPORTED").changedAt(t.plusHours(2)).build());
        var transfer=WarehouseTransfer.builder().transferCode("TR-"+UUID.randomUUID()).fromWarehouse(warehouse).toWarehouse(outsideWarehouse).status(WarehouseTransferStatus.COMPLETED).dispatchedAt(t.plusHours(3)).receivedAt(t.plusHours(4)).build();
        transfer.addItem(WarehouseTransferItem.builder().product(product).unit("lon").quantity(new BigDecimal("3")).conversionFactor(BigDecimal.ONE).baseQuantity(new BigDecimal("3")).baseUnit("lon").receivedQuantity(new BigDecimal("3")).receivedBaseQuantity(new BigDecimal("3")).build());stockTransfers.save(transfer);
        var inv=inventories.findByWarehouseIdAndProductId(warehouse.getId(),product.getId()).orElseThrow();inv.setBaseQuantity(new BigDecimal("61"));inventories.save(inv);
        JsonNode card=call(HttpMethod.GET,"/api/warehouse/stock-card/"+product.getId(),staffToken,null,200).get("data");
        assertThat(card.get("entries").size()).isEqualTo(3);
        assertThat(card.at("/entries/0/baseQuantityChange").decimalValue()).isEqualByComparingTo("-5");
        assertThat(card.at("/entries/1/baseQuantityChange").decimalValue()).isEqualByComparingTo("-6");
        assertThat(card.at("/entries/2/runningBaseBalance").decimalValue()).isEqualByComparingTo("61");
        call(HttpMethod.GET,"/api/warehouse/stock-card/"+product.getId()+"/sources/STOCK_COUNT/"+count.getId(),staffToken,null,200);
        call(HttpMethod.GET,"/api/warehouse/stock-card/"+product.getId()+"/sources/ORDER/"+order.getId(),staffToken,null,200);
        call(HttpMethod.GET,"/api/warehouse/stock-card/"+product.getId()+"/sources/TRANSFER/"+transfer.getId(),staffToken,null,200);
        JsonNode admin=call(HttpMethod.GET,"/api/warehouse/stock-card/"+product.getId(),adminToken,null,200).get("data");
        assertThat(admin.get("entries").size()).isEqualTo(4); // source and destination each carry their own transfer event
    }

    @Test
    void s602_adjustToTargetPersistsActualNegativeMovementRatherThanTarget() {
        product.setManageLot(false);products.save(product);
        matrix.updateRolePermissions(ERole.ROLE_WAREHOUSE_STAFF,Set.of(Permission.WAREHOUSE_VIEW,Permission.WAREHOUSE_ADJUST));
        JsonNode result=call(HttpMethod.POST,"/api/warehouse/adjust",staffToken,Map.of("warehouseId",warehouse.getId(),"productId",product.getId(),"quantity",70,"unit","lon"),200);
        assertThat(result.at("/data/items/0/baseQuantity").decimalValue()).isEqualByComparingTo("-5");
        JsonNode card=call(HttpMethod.GET,"/api/warehouse/stock-card/"+product.getId(),staffToken,null,200).get("data");
        assertThat(card.at("/entries/0/baseQuantityChange").decimalValue()).isEqualByComparingTo("-5");
        assertThat(card.at("/entries/0/runningBaseBalance").decimalValue()).isEqualByComparingTo("70");
    }

    @Test
    void s604_excludesExpiredAndMissingExpiryFromAutomaticSuggestionsAndWrites() {
        lotA.setExpiryDate(LocalDate.now().minusDays(1));lots.save(lotA);
        lotB.setExpiryDate(null);lots.save(lotB);
        lotC.setBaseQuantity(BigDecimal.ZERO);lots.save(lotC);
        String query="/api/warehouse/export/suggest-lot?warehouseId="+warehouse.getId()+"&productId="+product.getId()+"&quantity=1";
        JsonNode data=call(HttpMethod.GET,query,staffToken,null,200).get("data");
        assertThat(data.get("suggestedLot").isNull()).isTrue();
        assertThat(data.get("hasSufficientStock").asBoolean()).isFalse();
        assertThat(data.get("availableLots").size()).isEqualTo(2);
        call(HttpMethod.POST,"/api/warehouse/export",adminToken,Map.of("warehouseId",warehouse.getId(),"items",List.of(Map.of("productId",product.getId(),"quantity",1))),400);
        assertThat(inventories.findByWarehouseIdAndProductId(warehouse.getId(),product.getId()).orElseThrow().getBaseQuantity()).isEqualByComparingTo("75");
    }

    @Test
    void s604_rejectsInvalidQuantitiesAndUsesStoredLegacyLots() {
        String path="/api/warehouse/export/suggest-lot?warehouseId="+warehouse.getId()+"&productId="+product.getId();
        for(String q:List.of("0","-1","1.00001","100000000000"))call(HttpMethod.GET,path+"&quantity="+q,staffToken,null,400);
        product.setManageLot(false);products.save(product);
        JsonNode data=call(HttpMethod.GET,path+"&quantity=10",staffToken,null,200).get("data");
        assertThat(data.get("manageLot").asBoolean()).isTrue();
        assertThat(data.at("/suggestedLot/lotNumber").asText()).isEqualTo(lotB.getLotNumber());
        call(HttpMethod.GET,path.replace("warehouseId="+warehouse.getId(),"warehouseId="+outsideWarehouse.getId()),staffToken,null,403);
    }

    @Test
    void s604_liveSingleRoleMatrixGrantRevokeAndAdminBypass() {
        staffUser.setRoles(Set.of(roles.findByName(ERole.ROLE_VIEWER).orElseThrow()));users.save(staffUser);
        String token=login(staffUser.getUsername(),"ExportLotTest2026");
        String path="/api/warehouse/export/suggest-lot?warehouseId="+warehouse.getId()+"&productId="+product.getId()+"&quantity=10";
        Map<String,Object> body=Map.of("warehouseId",warehouse.getId(),"items",List.of(Map.of("productId",product.getId(),"quantity",10,"lotNumber",lotB.getLotNumber())));
        matrix.updateRolePermissions(ERole.ROLE_VIEWER,Set.of(Permission.WAREHOUSE_VIEW));
        call(HttpMethod.GET,path,token,null,200);call(HttpMethod.POST,"/api/warehouse/export",token,body,403);
        matrix.updateRolePermissions(ERole.ROLE_VIEWER,Set.of(Permission.WAREHOUSE_VIEW,Permission.WAREHOUSE_EXPORT));
        call(HttpMethod.POST,"/api/warehouse/export",token,body,200);
        matrix.updateRolePermissions(ERole.ROLE_VIEWER,Set.of(Permission.WAREHOUSE_VIEW));
        call(HttpMethod.POST,"/api/warehouse/export",token,body,403);
        matrix.updateRolePermissions(ERole.ROLE_VIEWER,Set.of());
        call(HttpMethod.GET,path,token,null,403);call(HttpMethod.GET,"/api/warehouse/lots/options",token,null,403);
        call(HttpMethod.GET,path,adminToken,null,200);
    }

    @Test
    void s604_writeRechecksChangedDefaultAndIgnoresForgedSuggestion() {
        WarehouseLotInventory newer=lots.save(WarehouseLotInventory.builder().warehouse(warehouse).product(product).lotNumber("NEAR-"+UUID.randomUUID())
                .expiryDate(LocalDate.now().plusDays(1)).baseQuantity(new BigDecimal("20")).baseUnit("lon").build());
        var item=new HashMap<String,Object>();item.put("productId",product.getId());item.put("quantity",10);item.put("lotNumber",lotB.getLotNumber());
        item.put("suggestedLotNumber",lotB.getLotNumber());
        Map<String,Object> body=Map.of("warehouseId",warehouse.getId(),"items",List.of(item));
        call(HttpMethod.POST,"/api/warehouse/export",staffToken,body,400);
        assertThat(lots.findById(lotB.getId()).orElseThrow().getBaseQuantity()).isEqualByComparingTo("20");
        item.put("overrideReason","Giữ nguyên lô khách đã kiểm tra");
        JsonNode saved=call(HttpMethod.POST,"/api/warehouse/export",staffToken,body,200).get("data");
        assertThat(saved.at("/items/0/lotNumber").asText()).isEqualTo(lotB.getLotNumber());
        assertThat(saved.at("/items/0/suggestedLotNumber").asText()).isEqualTo(newer.getLotNumber());
        assertThat(lots.findById(newer.getId()).orElseThrow().getBaseQuantity()).isEqualByComparingTo("20");
    }

    @Test
    void s604_competingExportsDeductSelectedLotOnlyOnceAndRollBackLoser() throws Exception {
        var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
        var start=new java.util.concurrent.CountDownLatch(1);
        Map<String,Object> body=Map.of("warehouseId",warehouse.getId(),"items",List.of(Map.of("productId",product.getId(),"quantity",15,
                "lotNumber",lotB.getLotNumber(),"overrideReason","Chỉ định lô đã chọn")));
        java.util.concurrent.Callable<Integer> task=()->{start.await();return exchange(HttpMethod.POST,"/api/warehouse/export",staffToken,body).getStatusCode().value();};
        try {
            var a=executor.submit(task);var b=executor.submit(task);start.countDown();
            assertThat(List.of(a.get(25,java.util.concurrent.TimeUnit.SECONDS),b.get(25,java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,400);
            assertThat(lots.findById(lotB.getId()).orElseThrow().getBaseQuantity()).isEqualByComparingTo("5");
            assertThat(lots.findById(lotC.getId()).orElseThrow().getBaseQuantity()).isEqualByComparingTo("50");
            var stock=inventories.findByWarehouseIdAndProductId(warehouse.getId(),product.getId()).orElseThrow();
            assertThat(stock.getBaseQuantity()).isEqualByComparingTo("60");assertThat(stock.getReservedQuantity()).isEqualByComparingTo("0");
            assertThat(vouchers.searchVouchers(warehouse.getId(),WarehouseVoucherType.EXPORT,null,null)).hasSize(1);
        } finally {executor.shutdownNow();}
    }

    @Test
    void s604_cannotConsumeReservedStockAndFailureRollsBackAllLotChanges() {
        var stock=inventories.findByWarehouseIdAndProductId(warehouse.getId(),product.getId()).orElseThrow();
        stock.setReservedQuantity(new BigDecimal("74"));inventories.save(stock);
        call(HttpMethod.POST,"/api/warehouse/export",staffToken,Map.of("warehouseId",warehouse.getId(),"items",List.of(Map.of("productId",product.getId(),"quantity",10,"lotNumber",lotB.getLotNumber()))),400);
        assertThat(lots.findById(lotB.getId()).orElseThrow().getBaseQuantity()).isEqualByComparingTo("20");
        assertThat(inventories.findById(stock.getId()).orElseThrow().getBaseQuantity()).isEqualByComparingTo("75");
        assertThat(vouchers.searchVouchers(warehouse.getId(),WarehouseVoucherType.EXPORT,null,null)).isEmpty();
    }

    private WarehouseVoucher stockVoucher(WarehouseVoucherType type,String status,int quantity,java.time.LocalDateTime time,Warehouse target) {
        var v=WarehouseVoucher.builder().voucherCode("CARD-"+UUID.randomUUID()).type(type).status(status).warehouse(target).voucherDate(time).recordedAt(time).build();
        v.addItem(WarehouseVoucherItem.builder().product(product).unit("lon").quantity(new BigDecimal(quantity)).baseUnit("lon").conversionFactor(BigDecimal.ONE).baseQuantity(new BigDecimal(quantity)).build());
        return vouchers.save(v);
    }

    private void patchSettings(String token, boolean enabled, int days, int status) throws Exception {
        var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create(http.getRootUri()+"/api/products/"+product.getId()+"/manage-lot"))
                .timeout(java.time.Duration.ofSeconds(20))
                .header("Authorization","Bearer "+token).header("Content-Type","application/json")
                .method("PATCH",java.net.http.HttpRequest.BodyPublishers.ofString("{\"manageLot\":"+enabled+",\"expiryWarningDays\":"+days+"}")).build();
        var response=java.net.http.HttpClient.newHttpClient().send(request,java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
    }

    private String login(String username, String password) {
        ResponseEntity<JsonNode> resp = exchange(HttpMethod.POST, "/api/auth/login", null,
                Map.of("username", username, "password", password));
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        return resp.getBody().get("accessToken").asText();
    }

    private ResponseEntity<JsonNode> exchange(HttpMethod method, String path, String jwt, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (jwt != null) {
            headers.setBearerAuth(jwt);
        }
        return http.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private JsonNode call(HttpMethod method, String path, String jwt, Object body, int expectedStatus) {
        ResponseEntity<JsonNode> resp = exchange(method, path, jwt, body);
        assertThat(resp.getStatusCode().value())
                .as("%s %s response: %s", method, path, resp.getBody())
                .isEqualTo(expectedStatus);
        return resp.getBody();
    }
}

