package com.quanlybanhang.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.quanlybanhang.config.RolePermissionConfig;
import com.quanlybanhang.model.*;
import com.quanlybanhang.model.Order;
import com.quanlybanhang.repository.*;
import com.quanlybanhang.scheduler.OrderReservationScheduler;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doThrow;

/** Full HTTP/JWT/matrix/JPA/concurrency tests; exclusively disposable H2. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.datasource.url=jdbc:h2:mem:order_reservations;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
    "spring.jpa.open-in-view=false", "spring.jpa.show-sql=false",
    "app.jwt.secret=order-reservation-test-secret-at-least-32-characters",
    "app.bootstrap.admin-password=ReservationTest2026", "app.bootstrap.admin-email=reservation@example.invalid",
    "app.seed-demo-users=false", "app.seed-demo-data=false", "app.order.reservation-check-interval-ms=3600000"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OrderReservationIntegrationTest {
    @Autowired TestRestTemplate http;
    @Autowired WarehouseRepository warehouses;
    @Autowired WarehouseInventoryRepository inventories;
    @Autowired ProductRepository products;
    @Autowired CategoryRepository categories;
    @Autowired OrderRepository orders;
    @Autowired WarehouseVoucherRepository vouchers;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired PasswordEncoder passwords;
    @Autowired RolePermissionConfig matrix;
    @Autowired OrderReservationScheduler scheduler;
    @SpyBean AuditLogService audit;
    Warehouse warehouse, outside;
    Product first, second;
    User actor;
    String token, adminToken;

    @BeforeEach void fixtures() {
        String key = UUID.randomUUID().toString().substring(0, 8);
        warehouse = warehouses.save(Warehouse.builder().code("RES-" + key).name("Reservation " + key).build());
        outside = warehouses.save(Warehouse.builder().code("OUT-" + key).name("Outside " + key).build());
        Category category = categories.save(Category.builder().code("CAT-" + key).name("Reservation category").build());
        first = products.save(Product.builder().code("A-" + key).name("First product").category(category).unit("lon").price(BigDecimal.TEN).build());
        second = products.save(Product.builder().code("B-" + key).name("Second product").category(category).unit("lon").price(BigDecimal.TEN).build());
        for (Product product : List.of(first, second)) inventories.save(WarehouseInventory.builder().warehouse(warehouse).product(product)
                .baseUnit("lon").baseQuantity(new BigDecimal("100")).reservedQuantity(BigDecimal.ZERO).build());
        matrix.updateRolePermissions(ERole.ROLE_VIEWER, Set.of(Permission.ORDER_VIEW, Permission.ORDER_CREATE, Permission.ORDER_APPROVE,
                Permission.ORDER_CANCEL, Permission.WAREHOUSE_EXPORT));
        actor = users.save(User.builder().username("res-" + key).email(key + "@example.invalid").password(passwords.encode("ReservationTest2026"))
                .roles(Set.of(roles.findByName(ERole.ROLE_VIEWER).orElseThrow())).warehouses(Set.of(warehouse)).build());
        token = login(actor.getUsername()); adminToken = login("admin");
    }

    @Test void pendingDoesNotReserveAndApprovalLocksConvertedQuantityWithoutReducingPhysicalStock() {
        long id = pending(warehouse, "2.5", "24", false); assertStock(first, "100", "0");
        JsonNode approved = approve(id, token, 200).get("data");
        assertThat(approved.get("inventoryReserved").asBoolean()).isTrue();
        assertThat(approved.get("reservationExpiresAt").asText()).isNotBlank(); assertStock(first, "100", "60");
        JsonNode preview = call(HttpMethod.POST, "/api/orders/check-inventory", token,
                Map.of("warehouseId", warehouse.getId(), "productId", first.getId(), "quantity", 41, "unit", "lon"), 200).get("data");
        assertThat(preview.get("valid").asBoolean()).isFalse();
        assertThat(preview.get("availableQuantity").decimalValue()).isEqualByComparingTo("40");
    }

    @Test void cancellationReleasesOnlyItsOwnReservationOnce() {
        long a = pending(warehouse, "30", "1", false), b = pending(warehouse, "20", "1", false);
        approve(a, token, 200); approve(b, token, 200); cancel(a, token, 200); assertStock(first, "100", "20");
        cancel(a, token, 400); assertStock(first, "100", "20");
        assertThat(detail(b).get("inventoryReserved").asBoolean()).isTrue();
    }

    @Test void schedulerAutomaticallyReleasesExpiredHoldAndIsIdempotent() {
        long id = pending(warehouse, "30", "1", false); approve(id, token, 200); expire(id);
        scheduler.autoReleaseExpiredReservations(); assertStock(first, "100", "0");
        assertThat(detail(id).get("status").asText()).isEqualTo("EXPIRED");
        assertThat(detail(id).get("inventoryReserved").asBoolean()).isFalse();
        JsonNode tracking = call(HttpMethod.GET, path(id) + "/tracking", adminToken, null, 200).get("data");
        assertThat(tracking.get("currentStatusDisplayName").asText()).isEqualTo("Hết hạn giữ chỗ");
        assertThat(tracking.get("inventoryReserved").asBoolean()).isFalse();
        scheduler.autoReleaseExpiredReservations(); assertStock(first, "100", "0");
        approve(id, token, 400);
    }

    @Test void notYetExpiredHoldsRemainProtected() {
        long id = pending(warehouse, "30", "1", false); approve(id, token, 200);
        release(token, 200); assertStock(first, "100", "30");
        assertThat(detail(id).get("status").asText()).isEqualTo("RECORDED");
    }

    @Test void doubleConcurrentApprovalReservesExactlyOnce() throws Exception {
        long id = pending(warehouse, "60", "1", false);
        assertThat(race(() -> exchange(HttpMethod.PUT, path(id) + "/approve", token, null).getStatusCode().value(),
                () -> exchange(HttpMethod.PUT, path(id) + "/approve", token, null).getStatusCode().value())).containsExactlyInAnyOrder(200, 400);
        assertStock(first, "100", "60");
    }

    @Test void differentOrdersCompetingForLastStockCannotOversell() throws Exception {
        long a = pending(warehouse, "60", "1", false), b = pending(warehouse, "60", "1", false);
        assertThat(race(() -> exchange(HttpMethod.PUT, path(a) + "/approve", token, null).getStatusCode().value(),
                () -> exchange(HttpMethod.PUT, path(b) + "/approve", token, null).getStatusCode().value())).containsExactlyInAnyOrder(200, 400);
        assertStock(first, "100", "60");
    }

    @Test void concurrentCancellationAndExpiryDoNotReleaseOtherOrdersStock() throws Exception {
        long a = pending(warehouse, "30", "1", false), b = pending(warehouse, "20", "1", false);
        approve(a, token, 200); approve(b, token, 200); expire(a);
        race(() -> exchange(HttpMethod.PUT, path(a) + "/cancel", token, Map.of("reason", "Cancel test")).getStatusCode().value(),
                () -> exchange(HttpMethod.POST, "/api/orders/release-expired-reservations", token, null).getStatusCode().value());
        assertStock(first, "100", "20"); assertThat(detail(a).get("inventoryReserved").asBoolean()).isFalse();
        assertThat(detail(b).get("inventoryReserved").asBoolean()).isTrue();
    }

    @Test void twoConcurrentExpiryWorkersReleaseOnlyOnce() throws Exception {
        long a = pending(warehouse, "30", "1", false), b = pending(warehouse, "20", "1", false);
        approve(a, token, 200); approve(b, token, 200); expire(a);
        assertThat(race(() -> exchange(HttpMethod.POST, "/api/orders/release-expired-reservations", token, null).getStatusCode().value(),
                () -> exchange(HttpMethod.POST, "/api/orders/release-expired-reservations", token, null).getStatusCode().value())).containsOnly(200);
        assertStock(first, "100", "20");
    }

    @Test void insufficientSecondSkuRollsBackWholeApprovalAndDoesNotReserveFirstSku() {
        long id = pending(warehouse, "30", "1", true);
        var inventory = inventories.findByWarehouseIdAndProductId(warehouse.getId(), second.getId()).orElseThrow();
        inventory.setBaseQuantity(BigDecimal.ONE); inventories.save(inventory);
        approve(id, token, 400); assertStock(first, "100", "0"); assertStock(second, "1", "0");
        assertThat(detail(id).get("status").asText()).isEqualTo("PENDING_APPROVAL");
    }

    @Test void auditFailureRollsBackApprovalStatusReservationAndHistory() {
        long id = pending(warehouse, "30", "1", false);
        doThrow(new IllegalStateException("test audit failure")).when(audit).logAction(eq("INVOICE"), anyString(), eq("APPROVE"), anyString(), anyString(), anyString());
        approve(id, token, 400); assertStock(first, "100", "0");
        assertThat(detail(id).get("status").asText()).isEqualTo("PENDING_APPROVAL");
    }

    @Test void expiryAuditFailureRollsBackThatOrdersStockAndStatus() {
        long id = pending(warehouse, "30", "1", false); approve(id, token, 200); expire(id);
        doThrow(new IllegalStateException("test expiry audit")).when(audit).logAction(eq("INVOICE"), anyString(), eq("EXPIRE_RESERVATION"), anyString(), anyString(), anyString());
        release(token, 400); assertStock(first, "100", "30");
        assertThat(detail(id).get("status").asText()).isEqualTo("RECORDED");
    }

    @Test void jwtGrantAndRevokeWorkWithoutReloginAndAdminAlwaysAllowed() {
        long id = pending(warehouse, "30", "1", false);
        matrix.updateRolePermissions(ERole.ROLE_VIEWER, Set.of(Permission.ORDER_VIEW)); approve(id, token, 403); cancel(id, token, 403);
        matrix.updateRolePermissions(ERole.ROLE_VIEWER, Set.of(Permission.ORDER_VIEW, Permission.ORDER_APPROVE)); approve(id, token, 200);
        matrix.updateRolePermissions(ERole.ROLE_VIEWER, Set.of(Permission.ORDER_VIEW)); cancel(id, token, 403); assertStock(first, "100", "30");
        matrix.updateRolePermissions(ERole.ROLE_VIEWER, Set.of(Permission.ORDER_VIEW, Permission.ORDER_CANCEL)); cancel(id, token, 200); assertStock(first, "100", "0");
        matrix.updateRolePermissions(ERole.ROLE_ADMIN, Set.of()); approve(pending(warehouse, "10", "1", false), adminToken, 200);
    }

    @Test void permissionCannotBypassAssignedWarehouseScope() {
        inventories.save(WarehouseInventory.builder().warehouse(outside).product(first).baseUnit("lon").baseQuantity(new BigDecimal("100")).build());
        long id = pending(outside, "30", "1", false); approve(id, token, 403); approve(id, adminToken, 200); cancel(id, token, 403);
        expire(id); release(token, 200);
        assertThat(orders.findById(id).orElseThrow().getInventoryReserved()).isTrue();
        release(adminToken, 200); assertThat(orders.findById(id).orElseThrow().getInventoryReserved()).isFalse();
    }

    @Test void exportConsumesPhysicalAndReservedOnceAndLaterExpiryCannotFreeOtherHolds() {
        long a = pending(warehouse, "30", "1", false), b = pending(warehouse, "20", "1", false);
        approve(a, token, 200); approve(b, token, 200);
        call(HttpMethod.PUT, path(a) + "/status", token, Map.of("status", "PICKING"), 200);
        call(HttpMethod.PUT, path(a) + "/status", token, Map.of("status", "EXPORTED"), 200); assertStock(first, "70", "20");
        expire(a); release(token, 200); assertStock(first, "70", "20"); cancel(a, token, 400);
    }

    @Test void invalidReservationDurationRollsBackAnyInventoryChanges() {
        long id = pending(warehouse, "30", "1", false);
        call(HttpMethod.PUT, path(id) + "/approve?reservationHours=0", token, null, 400);
        assertStock(first, "100", "0"); assertThat(detail(id).get("status").asText()).isEqualTo("PENDING_APPROVAL");
    }

    @Test void directExportCannotConsumeAnotherOrdersReservationIncludingForAdmin() {
        approve(pending(warehouse, "80", "1", false), token, 200);
        var body = Map.of("warehouseId", warehouse.getId(), "productId", first.getId(), "quantity", 50, "unit", "lon");
        call(HttpMethod.POST, "/api/warehouse/export", token, body, 400);
        call(HttpMethod.POST, "/api/warehouse/export", adminToken, body, 400);
        assertStock(first, "100", "80");
        call(HttpMethod.POST, "/api/warehouse/export", token,
                Map.of("warehouseId", warehouse.getId(), "productId", first.getId(), "quantity", 20, "unit", "lon"), 200);
        assertStock(first, "80", "80");
    }

    @Test void genericAdjustmentCannotReduceStockBelowReservationsAndRevokeTakesEffectImmediately() {
        approve(pending(warehouse, "80", "1", false), token, 200);
        matrix.updateRolePermissions(ERole.ROLE_VIEWER, Set.of(Permission.WAREHOUSE_ADJUST));
        var body = Map.of("warehouseId", warehouse.getId(), "productId", first.getId(), "quantity", 10, "unit", "lon");
        call(HttpMethod.POST, "/api/warehouse/adjust", token, body, 400);
        call(HttpMethod.POST, "/api/warehouse/adjust", adminToken, body, 400);
        assertStock(first, "100", "80");
        var boundary = Map.of("warehouseId", warehouse.getId(), "productId", first.getId(), "quantity", 80, "unit", "lon");
        matrix.updateRolePermissions(ERole.ROLE_VIEWER, Set.of());
        call(HttpMethod.POST, "/api/warehouse/adjust", token, boundary, 403);
        assertStock(first, "100", "80");
        matrix.updateRolePermissions(ERole.ROLE_VIEWER, Set.of(Permission.WAREHOUSE_ADJUST));
        call(HttpMethod.POST, "/api/warehouse/adjust", token, boundary, 200);
        assertStock(first, "80", "80");
    }

    @Test void genericAdjustmentCannotWriteOutsideAssignedWarehouse() {
        inventories.save(WarehouseInventory.builder().warehouse(outside).product(first).baseUnit("lon")
                .baseQuantity(new BigDecimal("100")).build());
        matrix.updateRolePermissions(ERole.ROLE_VIEWER, Set.of(Permission.WAREHOUSE_ADJUST));
        var body = Map.of("warehouseId", outside.getId(), "productId", first.getId(), "quantity", 10, "unit", "lon");
        call(HttpMethod.POST, "/api/warehouse/adjust", token, body, 403);
        assertThat(inventories.findByWarehouseIdAndProductId(outside.getId(), first.getId()).orElseThrow().getBaseQuantity())
                .isEqualByComparingTo("100");
        call(HttpMethod.POST, "/api/warehouse/adjust", adminToken, body, 200);
    }

    @Test void deliveryValidatesOwnedUniqueLinesAndQuantityAndPersistsActualShortage() {
        long id = pending(warehouse, "30", "1", false);
        approve(id, token, 200);
        call(HttpMethod.PUT, path(id) + "/status", token, Map.of("status", "PICKING"), 200);
        call(HttpMethod.PUT, path(id) + "/status", token, Map.of("status", "EXPORTED"), 200);
        assertThat(detail(id).get("canUpdateDelivery").asBoolean()).isTrue();
        long itemId = detail(id).get("items").get(0).get("id").asLong();
        for (Object invalid : List.of(
                List.of(Map.of("orderItemId", Long.MAX_VALUE, "deliveredQuantity", 20)),
                List.of(Map.of("orderItemId", itemId, "deliveredQuantity", 31)),
                List.of(Map.of("orderItemId", itemId, "deliveredQuantity", -1)),
                List.of(Map.of("orderItemId", itemId, "deliveredQuantity", 0.00001)),
                List.of(Map.of("orderItemId", itemId, "deliveredQuantity", 20), Map.of("orderItemId", itemId, "deliveredQuantity", 10)))) {
            call(HttpMethod.PUT, path(id) + "/delivery", token, Map.of("items", invalid), 400);
            assertThat(detail(id).get("status").asText()).isEqualTo("EXPORTED");
        }
        var body = Map.of("actualDeliveryDate", "2026-10-22", "deliveryNote", "Giao thiếu",
                "items", List.of(Map.of("orderItemId", itemId, "deliveredQuantity", 20, "note", "Thiếu 10")));
        matrix.updateRolePermissions(ERole.ROLE_VIEWER, Set.of(Permission.ORDER_VIEW));
        call(HttpMethod.PUT, path(id) + "/delivery", token, body, 403);
        matrix.updateRolePermissions(ERole.ROLE_VIEWER, Set.of(Permission.ORDER_VIEW, Permission.WAREHOUSE_EXPORT));
        JsonNode saved = call(HttpMethod.PUT, path(id) + "/delivery", token, body, 200).get("data");
        assertThat(saved.get("status").asText()).isEqualTo("DELIVERED");
        assertThat(saved.get("hasShortage").asBoolean()).isTrue();
        assertThat(saved.get("items").get(0).get("deliveredQuantity").decimalValue()).isEqualByComparingTo("20");
        assertThat(saved.get("actualDeliveryDate").asText()).isEqualTo("2026-10-22");
        assertStock(first, "70", "0");
    }

    @Test void failedSecondExportLineRollsBackFirstLineAndDoesNotCreateVoucher() {
        approve(pending(warehouse, "80", "1", false), token, 200);
        long before = vouchers.count();
        call(HttpMethod.POST, "/api/warehouse/export", token, Map.of("warehouseId", warehouse.getId(),
                "items", List.of(Map.of("productId", second.getId(), "quantity", 10, "unit", "lon"),
                                 Map.of("productId", first.getId(), "quantity", 50, "unit", "lon"))), 400);
        assertStock(first, "100", "80"); assertStock(second, "100", "0");
        assertThat(vouchers.count()).isEqualTo(before);
    }

    @Test void approvalRacingDirectExportCannotCreateStockBelowReservations() throws Exception {
        long id = pending(warehouse, "80", "1", false);
        assertThat(race(() -> exchange(HttpMethod.PUT, path(id) + "/approve", token, null).getStatusCode().value(),
                () -> exchange(HttpMethod.POST, "/api/warehouse/export", token,
                        Map.of("warehouseId", warehouse.getId(), "productId", first.getId(), "quantity", 50, "unit", "lon"))
                        .getStatusCode().value())).containsExactlyInAnyOrder(200, 400);
        var row = inventories.findByWarehouseIdAndProductId(warehouse.getId(), first.getId()).orElseThrow();
        assertThat(row.getBaseQuantity()).isGreaterThanOrEqualTo(row.getReservedQuantity());
    }

    @Test void deliveryPermissionDoesNotBypassWarehouseScopeAndAdminRetainsCapability() {
        inventories.save(WarehouseInventory.builder().warehouse(outside).product(first).baseUnit("lon")
                .baseQuantity(new BigDecimal("100")).build());
        long id = pending(outside, "30", "1", false);
        approve(id, adminToken, 200);
        call(HttpMethod.PUT, path(id) + "/status", adminToken, Map.of("status", "PICKING"), 200);
        call(HttpMethod.PUT, path(id) + "/status", adminToken, Map.of("status", "EXPORTED"), 200);
        long line = detail(id).get("items").get(0).get("id").asLong();
        var body = Map.of("items", List.of(Map.of("orderItemId", line, "deliveredQuantity", 20)));
        call(HttpMethod.PUT, path(id) + "/delivery", token, body, 403);
        assertThat(detail(id).get("status").asText()).isEqualTo("EXPORTED");
        matrix.updateRolePermissions(ERole.ROLE_ADMIN, Set.of());
        call(HttpMethod.PUT, path(id) + "/delivery", adminToken, body, 200);
    }

    private long pending(Warehouse target, String quantity, String factor, boolean twoSkus) {
        var order = Order.builder().orderCode("RES-" + UUID.randomUUID()).warehouse(target).customerName("Test customer")
                .customerGroup(CustomerGroup.KHACH_LE).status("PENDING_APPROVAL").requiresApproval(true).inventoryReserved(false)
                .approvalReason("Test approval").totalAmount(BigDecimal.TEN).createdBy(actor.getUsername()).items(new ArrayList<>()).build();
        for (Product product : twoSkus ? List.of(first, second) : List.of(first)) order.addItem(OrderItem.builder().product(product)
                .unit("lon").quantity(new BigDecimal(quantity)).conversionFactor(new BigDecimal(factor)).baseQuantity(new BigDecimal(quantity).multiply(new BigDecimal(factor)))
                .baseUnit("lon").price(BigDecimal.TEN).totalAmount(BigDecimal.TEN).build());
        return orders.save(order).getId();
    }
    private void expire(long id) { var order = orders.findById(id).orElseThrow(); order.setReservationExpiresAt(LocalDateTime.now().minusMinutes(2)); orders.save(order); }
    private void assertStock(Product product, String physical, String reserved) { var row = inventories.findByWarehouseIdAndProductId(warehouse.getId(), product.getId()).orElseThrow();
        assertThat(row.getBaseQuantity()).isEqualByComparingTo(physical); assertThat(row.getReservedQuantity()).isEqualByComparingTo(reserved); }
    private String path(long id) { return "/api/orders/" + id; }
    private JsonNode approve(long id, String jwt, int expected) { return call(HttpMethod.PUT, path(id) + "/approve", jwt, null, expected); }
    private JsonNode cancel(long id, String jwt, int expected) { return call(HttpMethod.PUT, path(id) + "/cancel", jwt, Map.of("reason", "Cancelled by test"), expected); }
    private void release(String jwt, int expected) { call(HttpMethod.POST, "/api/orders/release-expired-reservations", jwt, null, expected); }
    private JsonNode detail(long id) { return call(HttpMethod.GET, path(id), adminToken, null, 200).get("data"); }
    private String login(String username) { return call(HttpMethod.POST, "/api/auth/login", null, Map.of("username", username, "password", "ReservationTest2026"), 200).get("accessToken").asText(); }
    private List<Integer> race(Callable<Integer> a, Callable<Integer> b) throws Exception {
        var pool = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
        try { var first = pool.submit(() -> { start.await(); return a.call(); }); var second = pool.submit(() -> { start.await(); return b.call(); });
            start.countDown(); return List.of(first.get(25, TimeUnit.SECONDS), second.get(25, TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); }
    }
    private ResponseEntity<JsonNode> exchange(HttpMethod method, String path, String jwt, Object body) {
        var headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON); if (jwt != null) headers.setBearerAuth(jwt);
        return http.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }
    private JsonNode call(HttpMethod method, String path, String jwt, Object body, int expected) { var response = exchange(method, path, jwt, body);
        assertThat(response.getStatusCode().value()).as("%s %s: %s", method, path, response.getBody()).isEqualTo(expected); return response.getBody(); }
}
