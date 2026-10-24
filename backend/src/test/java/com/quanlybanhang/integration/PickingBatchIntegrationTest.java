package com.quanlybanhang.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.quanlybanhang.config.RolePermissionConfig;
import com.quanlybanhang.model.*;
import com.quanlybanhang.model.Order;
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
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real HTTP + JWT + DB matrix + JPA locks, exclusively isolated H2; no production writes. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:picking_batches;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
    "spring.jpa.open-in-view=false", "spring.jpa.show-sql=false",
    "app.jwt.secret=picking-batch-test-secret-at-least-32-characters",
    "app.bootstrap.admin-password=PickingTest2026", "app.bootstrap.admin-email=picking@example.invalid",
    "app.seed-demo-users=false", "app.seed-demo-data=false", "app.order.reservation-check-interval-ms=3600000"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PickingBatchIntegrationTest {
    @Autowired TestRestTemplate http;
    @Autowired WarehouseRepository warehouses;
    @Autowired WarehouseLocationRepository locations;
    @Autowired WarehouseLocationProductRepository mappings;
    @Autowired WarehouseInventoryRepository inventories;
    @Autowired ProductRepository products;
    @Autowired CategoryRepository categories;
    @Autowired OrderRepository orders;
    @Autowired PickingBatchRepository batches;
    @Autowired PickingOrderClaimRepository claims;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired PasswordEncoder passwords;
    @Autowired RolePermissionConfig matrix;
    @SpyBean AuditLogService audit;
    Warehouse warehouse, outside;
    Product first, second, unlocated;
    WarehouseLocation zone, shelf2, shelf10;
    String token, adminToken, key;

    @BeforeEach void fixtures() {
        key = UUID.randomUUID().toString().substring(0, 8);
        warehouse = warehouses.save(Warehouse.builder().code("PICK-" + key).name("Kho soạn").build());
        outside = warehouses.save(Warehouse.builder().code("OUT-" + key).name("Kho ngoài").build());
        var cat = categories.save(Category.builder().code("CAT-" + key).name("Picking category").build());
        first = products.save(Product.builder().code("A-" + key).name("First").category(cat).unit("lon").price(BigDecimal.TEN).build());
        second = products.save(Product.builder().code("B-" + key).name("Second").category(cat).unit("lon").price(BigDecimal.TEN).build());
        unlocated = products.save(Product.builder().code("C-" + key).name("No location").category(cat).unit("lon").price(BigDecimal.TEN).build());
        zone = locations.save(WarehouseLocation.builder().warehouse(warehouse).code("KHU-1").name("Khu A").locationType(LocationType.ZONE).build());
        shelf2 = locations.save(WarehouseLocation.builder().warehouse(warehouse).parent(zone).code("KE-2").name("Kệ 2").build());
        shelf10 = locations.save(WarehouseLocation.builder().warehouse(warehouse).parent(zone).code("KE-10").name("Kệ 10").build());
        mappings.save(WarehouseLocationProduct.builder().product(first).location(shelf10).build());
        mappings.save(WarehouseLocationProduct.builder().product(second).location(shelf2).build());
        matrix.updateRolePermissions(ERole.ROLE_VIEWER, Set.of(Permission.WAREHOUSE_EXPORT, Permission.WAREHOUSE_VIEW, Permission.WAREHOUSE_MANAGE));
        var actor = users.save(User.builder().username("pick-" + key).email(key + "@example.invalid").password(passwords.encode("PickingTest2026"))
                .roles(Set.of(roles.findByName(ERole.ROLE_VIEWER).orElseThrow())).warehouses(Set.of(warehouse)).build());
        token = login(actor.getUsername()); adminToken = login("admin");
    }
    @Test void groupsApprovedOrdersAndUsesNaturalWarehouseRouteWithUnknownLocationLast() {
        long a = ready(warehouse, first, "2", "24"), b = ready(warehouse, second, "5", "1"), c = ready(warehouse, unlocated, "1", "1");
        JsonNode batch = create(List.of(a, b, c), token, 200);
        assertThat(batch.get("summary").get("orderCount").asInt()).isEqualTo(3);
        assertThat(batch.get("items").findValuesAsText("productCode")).containsExactly(second.getCode(), first.getCode(), unlocated.getCode());
        assertThat(batch.get("items").get(0).get("locationDisplay").asText()).isEqualTo("Khu A > Kệ 2");
        assertThat(batch.get("items").get(2).get("locationDisplay").asText()).isEqualTo("Chưa gán vị trí");
        assertThat(orders.findById(a).orElseThrow().getStatus()).isEqualTo("PICKING");
        JsonNode reloaded = get(id(batch), token, 200);
        assertThat(reloaded.get("items")).isEqualTo(batch.get("items"));
        stock(first, "1000", "48");
    }
    @Test void configurableSequenceOverridesCodesAndSnapshotsSurviveLaterLocationChanges() {
        shelf10.setPickingSequence(1); locations.save(shelf10); shelf2.setPickingSequence(2); locations.save(shelf2);
        JsonNode batch = create(List.of(ready(warehouse, first, "2", "24"), ready(warehouse, second, "5", "1")), token, 200);
        assertThat(batch.get("items").get(0).get("productCode").asText()).isEqualTo(first.getCode());
        shelf10.setName("Tên đổi sau khi tạo"); shelf10.setPickingSequence(99); locations.save(shelf10);
        assertThat(get(id(batch), token, 200).get("items").get(0).get("locationDisplay").asText()).isEqualTo("Khu A > Kệ 10");
    }
    @Test void locationSequenceRoundTripsThroughActualManagementApi() {
        call(HttpMethod.PUT, "/api/warehouse-locations/" + shelf10.getId(), token,
                Map.of("name", shelf10.getName(), "locationType", "SHELF", "pickingSequence", 7), 200);
        assertThat(locations.findById(shelf10.getId()).orElseThrow().getPickingSequence()).isEqualTo(7);
        call(HttpMethod.PUT, "/api/warehouse-locations/" + shelf10.getId(), token,
                Map.of("name", shelf10.getName(), "locationType", "SHELF", "pickingSequence", -1), 400);
    }
    @Test void inactiveDefaultLocationIsNotPresentedAsAValidPickingStop() {
        shelf10.setActive(false); locations.save(shelf10);
        JsonNode batch = create(List.of(ready(warehouse, first, "2", "24")), token, 200);
        assertThat(batch.get("items").get(0).get("locationId").isNull()).isTrue();
    }
    @Test void rejectsDraftMixedWarehouseDuplicateOrdersAndExpiredReservationsAtomically() {
        long good = ready(warehouse, first, "2", "24"), bad = ready(warehouse, second, "5", "1");
        var invalid = orders.findById(bad).orElseThrow(); invalid.setStatus("DRAFT"); invalid = orders.save(invalid);
        create(List.of(good, bad), token, 400);
        assertThat(orders.findById(good).orElseThrow().getStatus()).isEqualTo("RECORDED");
        assertThat(claims.existsByOrderId(good)).isFalse();
        create(List.of(good, good), token, 400);
        create(List.of(good, ready(outside, second, "5", "1")), adminToken, 400);
        invalid.setStatus("RECORDED"); invalid.setReservationExpiresAt(LocalDateTime.now().minusSeconds(10)); orders.save(invalid);
        create(List.of(bad), token, 400);
        call(HttpMethod.GET, base() + "/eligible-orders?warehouseId=" + warehouse.getId(), token, null, 200);
    }
    @Test void lineValidationAndCompletionPersistShortageWithoutChangingStockOrDelivery() {
        long orderId = ready(warehouse, first, "2", "24");
        JsonNode batch = create(List.of(orderId), token, 200); long lineId = batch.get("items").get(0).get("id").asLong();
        for (Object qty : List.of(-1, 3, 0.00001)) update(batch, lineId, Map.of("pickedQuantity", qty, "checked", true), token, 400);
        update(batch, lineId, Map.of("pickedQuantity", 1, "checked", true), token, 400);
        update(batch, lineId, Map.of("checked", true), token, 400);
        call(HttpMethod.POST, base() + "/" + id(batch) + "/complete", token, Map.of("version", version(batch)), 400);
        batch = update(batch, lineId, Map.of("pickedQuantity", 1.5, "checked", true, "shortageReason", "Thiếu tại kệ"), token, 200);
        assertThat(batch.get("items").get(0).get("pickedBaseQuantity").decimalValue()).isEqualByComparingTo("36");
        assertThat(batch.get("summary").get("shortageCount").asInt()).isEqualTo(1);
        batch = call(HttpMethod.POST, base() + "/" + id(batch) + "/complete", token, Map.of("version", version(batch)), 200).get("data");
        assertThat(batch.get("summary").get("status").asText()).isEqualTo("COMPLETED");
        assertThat(batch.get("canEdit").asBoolean()).isFalse();
        update(batch, lineId, Map.of("pickedQuantity", 2, "checked", true), token, 400);
        create(List.of(orderId), token, 400);
        stock(first, "1000", "48");
        assertThat(orders.findById(orderId).orElseThrow().getActualDeliveryDate()).isNull();
    }
    @Test void zeroActualPickRequiresReasonAndIsPersistedInsteadOfDefaultingToOrderedQuantity() {
        JsonNode batch = create(List.of(ready(warehouse, first, "2", "24")), token, 200);
        long lineId = batch.get("items").get(0).get("id").asLong();
        update(batch, lineId, Map.of("pickedQuantity", 0, "checked", true), token, 400);
        batch = update(batch, lineId, Map.of("pickedQuantity", 0, "checked", true, "shortageReason", "Kệ trống"), token, 200);
        assertThat(get(id(batch), token, 200).get("items").get(0).get("pickedQuantity").decimalValue()).isZero();
    }
    @Test void staleVersionAndForeignLineCannotOverwriteAnotherPickersWork() {
        JsonNode old = create(List.of(ready(warehouse, first, "2", "24")), token, 200);
        long line = old.get("items").get(0).get("id").asLong();
        update(old, line, Map.of("pickedQuantity", 2, "checked", true), token, 200);
        update(old, line, Map.of("pickedQuantity", 1, "checked", true, "shortageReason", "Old"), token, 409);
        JsonNode current = get(id(old), token, 200);
        update(current, Long.MAX_VALUE, Map.of("pickedQuantity", 2, "checked", true), token, 400);
        assertThat(current.get("items").get(0).get("pickedQuantity").decimalValue()).isEqualByComparingTo("2");
    }
    @Test void cancelReleasesOrderClaimButNotReservationAndRequiresReason() {
        long orderId = ready(warehouse, first, "2", "24"); JsonNode batch = create(List.of(orderId), token, 200);
        call(HttpMethod.POST, base() + "/" + id(batch) + "/cancel", token, Map.of("version", version(batch), "reason", " "), 400);
        JsonNode cancelled = call(HttpMethod.POST, base() + "/" + id(batch) + "/cancel", token, Map.of("version", version(batch), "reason", "Đổi lượt"), 200).get("data");
        assertThat(cancelled.get("summary").get("status").asText()).isEqualTo("CANCELLED");
        assertThat(claims.existsByOrderId(orderId)).isFalse(); create(List.of(orderId), token, 200); stock(first, "1000", "48");
    }
    @Test void orderExpiryOrCancellationBlocksFurtherPickingButAllowsCancellingTheBatch() {
        long orderId = ready(warehouse, first, "2", "24"); JsonNode batch = create(List.of(orderId), token, 200);
        var order = orders.findById(orderId).orElseThrow(); order.setStatus("CANCELLED"); order.setInventoryReserved(false); orders.save(order);
        update(batch, batch.get("items").get(0).get("id").asLong(), Map.of("pickedQuantity", 2, "checked", true), token, 400);
        assertThat(get(id(batch), token, 200).get("canEdit").asBoolean()).isFalse();
        call(HttpMethod.POST, base() + "/" + id(batch) + "/cancel", token, Map.of("version", version(batch), "reason", "Đơn bị hủy"), 200);
    }
    @Test void liveMatrixGrantRevokeScopeAdminAndNoRoleFallback() {
        long orderId = ready(warehouse, first, "2", "24");
        matrix.updateRolePermissions(ERole.ROLE_VIEWER, Set.of(Permission.WAREHOUSE_VIEW));
        call(HttpMethod.GET, base() + "/options", token, null, 200);
        create(List.of(orderId), token, 403);
        matrix.updateRolePermissions(ERole.ROLE_VIEWER, Set.of(Permission.WAREHOUSE_EXPORT));
        JsonNode batch = create(List.of(orderId), token, 200);
        matrix.updateRolePermissions(ERole.ROLE_VIEWER, Set.of(Permission.WAREHOUSE_VIEW));
        update(batch, batch.get("items").get(0).get("id").asLong(), Map.of("pickedQuantity", 2, "checked", true), token, 403);
        assertThat(get(id(batch), token, 200).get("canEdit").asBoolean()).isFalse();
        matrix.updateRolePermissions(ERole.ROLE_VIEWER, Set.of()); get(id(batch), token, 403);
        matrix.updateRolePermissions(ERole.ROLE_VIEWER, Set.of(Permission.WAREHOUSE_EXPORT));
        var hidden = createOutside(adminToken);
        get(id(hidden), token, 403);
        call(HttpMethod.GET, base() + "/eligible-orders?warehouseId=" + outside.getId(), token, null, 403);
        matrix.updateRolePermissions(ERole.ROLE_ADMIN, Set.of()); get(id(hidden), adminToken, 200);
    }
    @Test void simultaneousCreateClaimsOrderOnlyOnce() throws Exception {
        long order = ready(warehouse, first, "2", "24"); long before = batches.count();
        var pool = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
        Callable<Integer> action = () -> { start.await(); return exchange(HttpMethod.POST, base(), token,
                Map.of("warehouseId", warehouse.getId(), "orderIds", List.of(order))).getStatusCode().value(); };
        try {
            var a = pool.submit(action); var b = pool.submit(action); start.countDown();
            var results = List.of(a.get(25, TimeUnit.SECONDS), b.get(25, TimeUnit.SECONDS));
            assertThat(results).contains(200); assertThat(results.stream().filter(code -> code == 200).count()).isEqualTo(1);
            assertThat(results).allMatch(code -> Set.of(200, 400, 409).contains(code));
        } finally { pool.shutdownNow(); }
        assertThat(batches.count()).isEqualTo(before + 1); stock(first, "1000", "48");
    }
    @Test void createAuditFailureRollsBackBatchClaimAndOrderLifecycle() {
        long order = ready(warehouse, first, "2", "24"), before = batches.count();
        doThrow(new IllegalStateException("Picking audit failure")).when(audit).logAction(eq("PICKING_BATCH"), anyString(), eq("CREATE"), any(), anyString(), anyString(), anyString());
        create(List.of(order), token, 400);
        assertThat(batches.count()).isEqualTo(before); assertThat(claims.existsByOrderId(order)).isFalse();
        assertThat(orders.findById(order).orElseThrow().getStatus()).isEqualTo("RECORDED"); stock(first, "1000", "48");
    }
    @Test void inactiveWarehouseBlocksSavingAndCompletingButAllowsSafeCancellation() {
        JsonNode batch = create(List.of(ready(warehouse, first, "2", "24")), token, 200);
        warehouse.setActive(false); warehouses.save(warehouse);
        update(batch, batch.get("items").get(0).get("id").asLong(), Map.of("pickedQuantity", 2, "checked", true), token, 400);
        call(HttpMethod.POST, base() + "/" + id(batch) + "/complete", token, Map.of("version", version(batch)), 400);
        assertThat(get(id(batch), token, 200).get("canEdit").asBoolean()).isFalse();
        call(HttpMethod.POST, base() + "/" + id(batch) + "/cancel", token, Map.of("version", version(batch), "reason", "Kho ngừng hoạt động"), 200);
    }

    @Test void failedLineAuditRollsBackQuantityCheckedAndParentVersion() {
        JsonNode batch = create(List.of(ready(warehouse, first, "2", "24")), token, 200);
        doThrow(new IllegalStateException("Save audit failure")).when(audit).logAction(eq("PICKING_BATCH"), anyString(), eq("SAVE_LINE"), any(), anyString(), anyString(), anyString());
        update(batch, batch.get("items").get(0).get("id").asLong(), Map.of("pickedQuantity", 2, "checked", true), token, 400);
        JsonNode reloaded = get(id(batch), token, 200);
        assertThat(version(reloaded)).isEqualTo(version(batch));
        assertThat(reloaded.get("items").get(0).get("pickedQuantity").isNull()).isTrue();
        assertThat(reloaded.get("items").get(0).get("checked").asBoolean()).isFalse();
    }

    @Test void failedCompletionAuditRollsBackStatusAndVersion() {
        JsonNode batch = create(List.of(ready(warehouse, first, "2", "24")), token, 200);
        batch = update(batch, batch.get("items").get(0).get("id").asLong(), Map.of("pickedQuantity", 2, "checked", true), token, 200);
        doThrow(new IllegalStateException("Completion audit failure")).when(audit).logAction(eq("PICKING_BATCH"), anyString(), eq("COMPLETE"), anyString(), anyString(), anyString(), anyString());
        call(HttpMethod.POST, base() + "/" + id(batch) + "/complete", token, Map.of("version", version(batch)), 400);
        JsonNode reloaded = get(id(batch), token, 200);
        assertThat(reloaded.get("summary").get("status").asText()).isEqualTo("IN_PROGRESS");
        assertThat(version(reloaded)).isEqualTo(version(batch));
    }

    @Test void exportGrantWithoutAnyAssignedWarehouseDoesNotCreateGlobalDataAccess() {
        JsonNode batch = create(List.of(ready(warehouse, first, "2", "24")), token, 200);
        var actor = users.findByUsername("pick-" + key).orElseThrow(); actor.setWarehouses(Set.of()); users.save(actor);
        JsonNode options = call(HttpMethod.GET, base() + "/options", token, null, 200).get("data");
        assertThat(options.get("warehouses").size()).isZero(); get(id(batch), token, 403);
    }

    private JsonNode createOutside(String jwt) { return call(HttpMethod.POST, base(), jwt, Map.of("warehouseId", outside.getId(), "orderIds", List.of(ready(outside, first, "2", "24"))), 200).get("data"); }
    private long ready(Warehouse w, Product p, String qty, String factor) {
        BigDecimal quantity = new BigDecimal(qty), conversion = new BigDecimal(factor), base = quantity.multiply(conversion);
        var stock = inventories.findByWarehouseIdAndProductId(w.getId(), p.getId()).orElseGet(() -> WarehouseInventory.builder().warehouse(w).product(p)
                .baseUnit("lon").baseQuantity(new BigDecimal("1000")).reservedQuantity(BigDecimal.ZERO).build());
        stock.setReservedQuantity(stock.getReservedQuantity().add(base)); inventories.save(stock);
        Order order = Order.builder().orderCode("PICK-" + UUID.randomUUID()).warehouse(w).customerName("Khách kiểm thử")
                .customerGroup(CustomerGroup.KHACH_LE).status("RECORDED").inventoryReserved(true).reservationExpiresAt(LocalDateTime.now().plusHours(24))
                .createdBy("admin").items(new ArrayList<>()).build();
        order.addItem(OrderItem.builder().product(p).unit("lon").quantity(quantity).conversionFactor(conversion).baseQuantity(base).baseUnit("lon").build());
        return orders.save(order).getId();
    }
    private void stock(Product product, String physical, String reserved) {
        var stock = inventories.findByWarehouseIdAndProductId(warehouse.getId(), product.getId()).orElseThrow();
        assertThat(stock.getBaseQuantity()).isEqualByComparingTo(physical); assertThat(stock.getReservedQuantity()).isEqualByComparingTo(reserved);
    }
    private JsonNode update(JsonNode b, long line, Map<String, Object> values, String jwt, int expected) {
        var body = new HashMap<>(values); body.put("version", version(b));
        return call(HttpMethod.PUT, base() + "/" + id(b) + "/items/" + line, jwt, body, expected).get("data");
    }
    private JsonNode create(List<Long> ids, String jwt, int expected) { return call(HttpMethod.POST, base(), jwt, Map.of("warehouseId", warehouse.getId(), "orderIds", ids), expected).get("data"); }
    private long id(JsonNode value) { return value.get("summary").get("id").asLong(); }
    private long version(JsonNode value) { return value.get("summary").get("version").asLong(); }
    private String base() { return "/api/warehouse/picking-batches"; }
    private JsonNode get(long id, String jwt, int expected) { return call(HttpMethod.GET, base() + "/" + id, jwt, null, expected).get("data"); }
    private String login(String username) { return call(HttpMethod.POST, "/api/auth/login", null, Map.of("username", username, "password", "PickingTest2026"), 200).get("accessToken").asText(); }
    private ResponseEntity<JsonNode> exchange(HttpMethod method, String path, String jwt, Object body) {
        var headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON); if (jwt != null) headers.setBearerAuth(jwt);
        return http.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }
    private JsonNode call(HttpMethod method, String path, String jwt, Object body, int expected) {
        var response = exchange(method, path, jwt, body); assertThat(response.getStatusCode().value()).as("%s %s: %s", method, path, response.getBody()).isEqualTo(expected); return response.getBody();
    }
}
