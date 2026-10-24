package com.quanlybanhang.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.quanlybanhang.config.RolePermissionConfig;
import com.quanlybanhang.model.*;
import com.quanlybanhang.repository.*;
import com.quanlybanhang.service.AuditLogService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doThrow;

/** Real HTTP/JWT/security/JPA transactions, exclusively on disposable H2. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:import_vouchers;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
    "spring.jpa.open-in-view=false", "spring.jpa.show-sql=false",
    "app.jwt.secret=import-voucher-test-secret-at-least-32-characters",
    "app.bootstrap.admin-password=ImportVoucherTest2026", "app.bootstrap.admin-email=imports@example.invalid",
    "app.seed-demo-users=false", "app.seed-demo-data=false", "app.order.reservation-check-interval-ms=3600000"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ImportVoucherIntegrationTest {
    @Autowired TestRestTemplate http;
    @Autowired WarehouseRepository warehouses;
    @Autowired WarehouseInventoryRepository inventories;
    @Autowired WarehouseLotInventoryRepository lots;
    @Autowired WarehouseVoucherRepository vouchers;
    @Autowired ProductRepository products;
    @Autowired CategoryRepository categories;
    @Autowired UnitConversionRepository conversions;
    @Autowired SupplierRepository suppliers;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired PasswordEncoder passwords;
    @Autowired RolePermissionConfig matrix;
    @SpyBean AuditLogService audit;
    Warehouse warehouse, outside;
    Product product;
    Supplier supplier;
    String token, adminToken;
    UnitConversion conversion;

    @BeforeEach void fixtures() {
        String key = UUID.randomUUID().toString().substring(0, 8);
        warehouse = warehouses.save(Warehouse.builder().code("IMP-" + key).name("Import " + key).build());
        outside = warehouses.save(Warehouse.builder().code("OUT-" + key).name("Outside " + key).build());
        supplier = suppliers.save(Supplier.builder().code("SUP-" + key).name("Supplier " + key).build());
        Category category = categories.save(Category.builder().code("CAT-" + key).name("Import category").build());
        product = products.save(Product.builder().code("P-" + key).name("Lot product").category(category).unit("lon").manageLot(true).price(BigDecimal.TEN).build());
        conversion = conversions.save(UnitConversion.builder().product(product).unitName("thùng").conversionFactor(new BigDecimal("24")).build());
        inventories.save(WarehouseInventory.builder().warehouse(warehouse).product(product).baseUnit("lon")
                .baseQuantity(new BigDecimal("100")).reservedQuantity(new BigDecimal("50")).build());
        matrix.updateRolePermissions(ERole.ROLE_VIEWER, Set.of(Permission.WAREHOUSE_IMPORT, Permission.WAREHOUSE_APPROVE, Permission.WAREHOUSE_ADJUST));
        User actor = users.save(User.builder().username("import-" + key).email(key + "@example.invalid")
                .password(passwords.encode("ImportVoucherTest2026"))
                .roles(Set.of(roles.findByName(ERole.ROLE_VIEWER).orElseThrow())).warehouses(Set.of(warehouse)).build());
        token = login(actor.getUsername()); adminToken = login("admin");
    }

    @Test void draftPersistsHeaderAndLotsWithoutStockAndFreezesConversionAtConfirmation() {
        long id = draft(); assertStock("100"); JsonNode row = detail(id);
        assertThat(row.get("supplierId").asLong()).isEqualTo(supplier.getId());
        assertThat(row.get("documentNumber").asText()).isEqualTo("HD-202610");
        assertThat(row.get("voucherDate").asText()).startsWith("2026-10-21");
        assertThat(row.at("/items/0/lotNumber").asText()).isEqualTo("LOT-A");
        conversion.setConversionFactor(new BigDecimal("20")); conversions.save(conversion);
        JsonNode done = confirm(id, token, 200).get("data");
        assertThat(done.at("/items/0/baseQuantity").decimalValue()).isEqualByComparingTo("50"); assertStock("150");
        conversion.setConversionFactor(BigDecimal.TEN); conversions.save(conversion);
        assertThat(detail(id).at("/items/0/conversionFactor").decimalValue()).isEqualByComparingTo("20");
        assertThat(lots.findByWarehouseIdAndProductIdAndLotNumber(warehouse.getId(), product.getId(), "LOT-A").orElseThrow().getBaseQuantity()).isEqualByComparingTo("50");
    }

    @Test void updateAndDeleteDraftUseIdentityAndVersionWithoutTouchingStock() {
        long id = draft(); var body = body(); body.put("version", detail(id).get("version").asLong()); body.put("documentNumber", "EDITED");
        JsonNode updated = call(HttpMethod.PUT, voucher(id), token, body, 200).get("data");
        assertThat(updated.get("id").asLong()).isEqualTo(id); assertThat(updated.get("documentNumber").asText()).isEqualTo("EDITED");
        assertThat(updated.get("version").asLong()).isGreaterThan(0);
        call(HttpMethod.PUT, voucher(id), token, body, 400); assertStock("100");
        call(HttpMethod.DELETE, voucher(id), token, null, 200); assertThat(vouchers.existsById(id)).isFalse(); assertStock("100");
    }

    @Test void confirmedIsImmutableAndOnlyAdjustmentChangesStock() {
        long id = draft(); confirm(id, token, 200);
        call(HttpMethod.PUT, voucher(id), token, body(), 400); call(HttpMethod.DELETE, voucher(id), token, null, 400);
        confirm(id, token, 400); assertStock("160");
        JsonNode changed = call(HttpMethod.POST, voucher(id) + "/adjust", token, adjustment("-2"), 200).get("data");
        assertThat(changed.get("referenceVoucherId").asLong()).isEqualTo(id); assertStock("158");
        assertThat(changed.at("/items/0/baseQuantity").decimalValue()).isEqualByComparingTo("-2");
        assertThat(detail(id).at("/items/0/baseQuantity").decimalValue()).isEqualByComparingTo("60");
    }

    @Test void jwtGrantRevokeAndImportPermissionDoesNotImplicitlyApprove() {
        matrix.updateRolePermissions(ERole.ROLE_VIEWER, Set.of(Permission.WAREHOUSE_IMPORT)); long id = draft();
        call(HttpMethod.GET, "/api/warehouse/import/options", token, null, 200);
        call(HttpMethod.GET, "/api/warehouse/imports", token, null, 200);
        call(HttpMethod.GET, "/api/products/lookup", token, null, 200);
        confirm(id, token, 403); call(HttpMethod.POST, "/api/warehouse/import", token, body(), 403);
        matrix.updateRolePermissions(ERole.ROLE_VIEWER, Set.of(Permission.WAREHOUSE_APPROVE)); confirm(id, token, 200);
        matrix.updateRolePermissions(ERole.ROLE_VIEWER, Set.of());
        call(HttpMethod.GET, "/api/warehouse/import/options", token, null, 403);
        call(HttpMethod.GET, "/api/warehouse/imports", token, null, 403);
        call(HttpMethod.POST, "/api/warehouse/import/draft", token, body(), 403);
        call(HttpMethod.PUT, voucher(id), token, body(), 403); call(HttpMethod.DELETE, voucher(id), token, null, 403);
        call(HttpMethod.POST, voucher(id) + "/adjust", token, adjustment("1"), 403);
        call(HttpMethod.GET, "/api/warehouse/import/options", adminToken, null, 200);
    }

    @Test void warehouseScopeIsEnforcedAcrossImportActions() {
        assertThat(call(HttpMethod.GET, "/api/warehouse/import/options", token, null, 200).at("/data/warehouses").size()).isEqualTo(1);
        var foreign = body(); foreign.put("warehouseId", outside.getId());
        call(HttpMethod.POST, "/api/warehouse/import/draft", token, foreign, 403);
        long id = call(HttpMethod.POST, "/api/warehouse/import/draft", adminToken, foreign, 200).at("/data/id").asLong();
        confirm(id, token, 403); call(HttpMethod.GET, "/api/warehouse/imports?warehouseId=" + outside.getId(), token, null, 403);
        call(HttpMethod.PUT, voucher(id), token, body(), 403); call(HttpMethod.DELETE, voucher(id), token, null, 403);
        confirm(id, adminToken, 200); call(HttpMethod.POST, voucher(id) + "/adjust", token, adjustment("1"), 403);
    }

    @Test void concurrentConfirmationPostsOnlyOnce() throws Exception {
        long id = draft(); var pool = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
        try {
            Callable<Integer> action = () -> { start.await(); return exchange(HttpMethod.POST, voucher(id) + "/confirm", token, null).getStatusCode().value(); };
            var a = pool.submit(action); var b = pool.submit(action); start.countDown();
            assertThat(List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 400); assertStock("160");
        } finally { pool.shutdownNow(); }
    }

    @Test void simultaneousDifferentDraftsDoNotLoseStock() throws Exception {
        long a = draft(), b = draft(); var pool = Executors.newFixedThreadPool(2);
        try { var first = pool.submit(() -> confirm(a, token, 200)); var second = pool.submit(() -> confirm(b, token, 200));
            first.get(20, TimeUnit.SECONDS); second.get(20, TimeUnit.SECONDS); assertStock("220");
        } finally { pool.shutdownNow(); }
    }

    @Test void missingMetadataLotOrInvalidPrecisionNeverPostsAndInactiveSupplierCannotConfirm() {
        for (String field : List.of("supplierId", "documentNumber", "voucherDate")) {
            var invalid = body(); invalid.remove(field); call(HttpMethod.POST, "/api/warehouse/import", token, invalid, 400);
        }
        for (var badLine : List.of(Map.of("productId", product.getId(), "quantity", 1), Map.of("productId", product.getId(), "quantity", -1),
                Map.of("productId", product.getId(), "quantity", new BigDecimal("0.00001")))) {
            var invalid = body(); invalid.put("items", List.of(badLine)); call(HttpMethod.POST, "/api/warehouse/import/draft", token, invalid, 400);
        }
        long id = draft(); supplier.setActive(false); suppliers.save(supplier); confirm(id, token, 400); assertStock("100");
    }

    @Test void mismatchedLotExpiryRollsBackEntireConfirmation() {
        lots.save(WarehouseLotInventory.builder().warehouse(warehouse).product(product).lotNumber("LOT-A")
                .expiryDate(java.time.LocalDate.of(2028, 1, 1)).baseUnit("lon").baseQuantity(BigDecimal.TEN).build());
        long id = draft(); confirm(id, token, 400); assertStock("100"); assertThat(detail(id).get("status").asText()).isEqualTo("DRAFT");
    }

    @Test void adjustmentRequiresReasonSameWarehouseAndPreservesReservedAndNonnegativeLotStock() {
        long id = draft(); confirm(id, token, 200); var invalid = adjustment("-2"); invalid.put("adjustmentReason", " ");
        call(HttpMethod.POST, voucher(id) + "/adjust", token, invalid, 400);
        invalid = adjustment("1"); invalid.put("warehouseId", outside.getId()); call(HttpMethod.POST, voucher(id) + "/adjust", token, invalid, 400);
        call(HttpMethod.POST, voucher(id) + "/adjust", token, adjustment("-61"), 400);
        var inv = inventories.findByWarehouseIdAndProductId(warehouse.getId(), product.getId()).orElseThrow(); inv.setReservedQuantity(new BigDecimal("159")); inventories.save(inv);
        call(HttpMethod.POST, voucher(id) + "/adjust", token, adjustment("-2"), 400); assertStock("160");
    }

    @Test void auditFailureRollsBackInventoryLotAndVoucher() {
        long id = draft(); doThrow(new IllegalStateException("test audit failure")).when(audit).logAction(eq("INVENTORY"), anyString(), eq("IMPORT_CONFIRM"), anyString(), anyString(), anyString());
        confirm(id, token, 400); assertStock("100"); assertThat(detail(id).get("status").asText()).isEqualTo("DRAFT");
        assertThat(lots.findByWarehouseIdAndProductId(warehouse.getId(), product.getId())).isEmpty();
    }

    @Test void lotTargetAdjustmentChangesTotalByDifferenceNotByReplacingTotalWithOneLot() {
        long id = draft(); confirm(id, token, 200);
        var request = adjustment("55"); request.remove("delta");
        JsonNode changed = call(HttpMethod.POST, voucher(id) + "/adjust", token, request, 200).get("data");
        assertStock("155");
        assertThat(changed.at("/items/0/baseQuantity").decimalValue()).isEqualByComparingTo("-5");
        assertThat(lots.findByWarehouseIdAndProductIdAndLotNumber(warehouse.getId(), product.getId(), "LOT-A").orElseThrow().getBaseQuantity()).isEqualByComparingTo("55");
    }

    @Test void optionalManufacturingDateCanBeOmittedWhenReceivingAnExistingLotWithoutErasingIt() {
        var manufactured = java.time.LocalDate.of(2026, 10, 1);
        lots.save(WarehouseLotInventory.builder().warehouse(warehouse).product(product).lotNumber("LOT-A")
                .expiryDate(java.time.LocalDate.of(2027, 10, 21)).manufacturingDate(manufactured)
                .baseUnit("lon").baseQuantity(BigDecimal.TEN).build());
        confirm(draft(), token, 200);
        assertThat(lots.findByWarehouseIdAndProductIdAndLotNumber(warehouse.getId(), product.getId(), "LOT-A").orElseThrow().getManufacturingDate()).isEqualTo(manufactured);
    }

    @Test void importLookupDoesNotLeakExportVouchersOrTheirAdjustments() {
        long imported = draft();
        String key = UUID.randomUUID().toString();
        var exported = vouchers.save(WarehouseVoucher.builder().voucherCode("OUT-" + key).type(WarehouseVoucherType.EXPORT).warehouse(warehouse).build());
        var adjusted = vouchers.save(WarehouseVoucher.builder().voucherCode("ADJ-" + key).type(WarehouseVoucherType.ADJUSTMENT).warehouse(warehouse)
                .referenceVoucherId(exported.getId()).referenceVoucherCode(exported.getVoucherCode()).build());
        JsonNode rows = call(HttpMethod.GET, "/api/warehouse/imports?warehouseId=" + warehouse.getId(), token, null, 200).get("data");
        List<Long> ids = new ArrayList<>(); rows.forEach(row -> ids.add(row.get("id").asLong()));
        assertThat(ids).contains(imported).doesNotContain(exported.getId(), adjusted.getId());
    }

    private Map<String, Object> body() { return new HashMap<>(Map.of("warehouseId", warehouse.getId(), "supplierId", supplier.getId(),
        "documentNumber", "HD-202610", "voucherDate", "2026-10-21T09:30:00", "items", List.of(Map.of("productId", product.getId(), "unit", "thùng",
        "quantity", new BigDecimal("2.5"), "lotNumber", "LOT-A", "expiryDate", "2027-10-21")))); }
    private Map<String, Object> adjustment(String delta) { return new HashMap<>(Map.of("adjustmentReason", "Recount after unloading", "delta", new BigDecimal(delta),
        "items", List.of(Map.of("productId", product.getId(), "unit", "lon", "quantity", new BigDecimal(delta).abs(), "lotNumber", "LOT-A", "expiryDate", "2027-10-21")))); }
    private String voucher(long id) { return "/api/warehouse/vouchers/" + id; }
    private long draft() { return call(HttpMethod.POST, "/api/warehouse/import/draft", token, body(), 200).at("/data/id").asLong(); }
    private JsonNode confirm(long id, String jwt, int expected) { return call(HttpMethod.POST, voucher(id) + "/confirm", jwt, null, expected); }
    private JsonNode detail(long id) { return call(HttpMethod.GET, voucher(id), adminToken, null, 200).get("data"); }
    private void assertStock(String expected) { assertThat(inventories.findByWarehouseIdAndProductId(warehouse.getId(), product.getId()).orElseThrow().getBaseQuantity()).isEqualByComparingTo(expected); }
    private String login(String username) { return call(HttpMethod.POST, "/api/auth/login", null, Map.of("username", username, "password", "ImportVoucherTest2026"), 200).get("accessToken").asText(); }
    private ResponseEntity<JsonNode> exchange(HttpMethod method, String path, String jwt, Object body) {
        var headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON); if (jwt != null) headers.setBearerAuth(jwt);
        return http.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }
    private JsonNode call(HttpMethod method, String path, String jwt, Object body, int expected) {
        var result = exchange(method, path, jwt, body);
        assertThat(result.getStatusCode().value()).as("%s %s: %s", method, path, result.getBody()).isEqualTo(expected); return result.getBody();
    }
}
