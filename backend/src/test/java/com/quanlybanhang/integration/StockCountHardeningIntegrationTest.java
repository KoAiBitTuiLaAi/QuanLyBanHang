package com.quanlybanhang.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.quanlybanhang.config.RolePermissionConfig;
import com.quanlybanhang.model.*;
import com.quanlybanhang.repository.*;
import com.quanlybanhang.service.AuditLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doThrow;

/** Real HTTP/JWT/security/JPA transactions; only an isolated in-memory H2 database. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:stock_count_hardening;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa",
        "spring.datasource.password=", "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.open-in-view=false", "spring.jpa.show-sql=false",
        "app.jwt.secret=stock-count-test-secret-only-at-least-32-characters",
        "app.bootstrap.admin-password=StockCountTest2026", "app.bootstrap.admin-email=count@example.invalid",
        "app.seed-demo-users=false", "app.seed-demo-data=false",
        "app.order.reservation-check-interval-ms=3600000"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class StockCountHardeningIntegrationTest {
    private static final String API = "/api/warehouse/stock-counts";
    @Autowired TestRestTemplate http;
    @Autowired WarehouseRepository warehouses;
    @Autowired WarehouseInventoryRepository inventories;
    @Autowired WarehouseStockCountRepository counts;
    @Autowired ProductRepository products;
    @Autowired CategoryRepository categories;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired AuditLogRepository logs;
    @Autowired PasswordEncoder passwords;
    @Autowired RolePermissionConfig matrix;
    @Autowired PlatformTransactionManager transactionManager;
    @SpyBean AuditLogService audit;

    Warehouse warehouse, outside;
    Category category;
    Product first, second;
    String token, adminToken;
    TransactionTemplate tx;

    @BeforeEach
    void fixtures() {
        tx = new TransactionTemplate(transactionManager);
        String key = UUID.randomUUID().toString().substring(0, 8);
        warehouse = warehouses.save(Warehouse.builder().code("CNT-" + key).name("Count " + key).build());
        outside = warehouses.save(Warehouse.builder().code("OUT-" + key).name("Outside " + key).build());
        category = categories.save(Category.builder().code("CT-" + key).name("Count category " + key).build());
        first = product("A-" + key);
        second = product("B-" + key);
        inventories.save(WarehouseInventory.builder().warehouse(warehouse).product(first).baseUnit("lon")
                .baseQuantity(new BigDecimal("100")).build());
        inventories.save(WarehouseInventory.builder().warehouse(warehouse).product(second).baseUnit("lon")
                .baseQuantity(new BigDecimal("50")).build());
        matrix.updateRolePermissions(ERole.ROLE_VIEWER, Set.of(Permission.WAREHOUSE_VIEW, Permission.WAREHOUSE_ADJUST));
        User viewer = users.save(User.builder().username("count-" + key).email("count-" + key + "@example.invalid")
                .password(passwords.encode("StockCountTest2026")).roles(Set.of(roles.findByName(ERole.ROLE_VIEWER).orElseThrow()))
                .warehouses(Set.of(warehouse)).build());
        token = login(viewer.getUsername());
        adminToken = login("admin");
    }

    @Test
    void viewerWithAdjustCanCompleteAndJwtHonorsGrantRevokeAndScopeOnEveryEndpoint() {
        long id = draft();
        matrix.updateRolePermissions(ERole.ROLE_VIEWER, Set.of());
        for (String suffix : List.of("", "/options", "/" + id)) call(HttpMethod.GET, API + suffix, token, null, 403);
        call(HttpMethod.POST, API, token, createBody(warehouse), 403);
        call(HttpMethod.PUT, API + "/" + id + "/items", token, Map.of("items", lines(99, 50)), 403);
        call(HttpMethod.POST, API + "/" + id + "/cancel", token, null, 403);
        call(HttpMethod.POST, API + "/" + id + "/complete", token, completeBody(99, 50), 403);

        matrix.updateRolePermissions(ERole.ROLE_VIEWER, Set.of(Permission.WAREHOUSE_VIEW));
        JsonNode detail = call(HttpMethod.GET, API + "/" + id, token, null, 200).get("data");
        assertThat(detail.get("canEdit").asBoolean()).isTrue();
        assertThat(detail.get("canAdjust").asBoolean()).isFalse();
        call(HttpMethod.POST, API + "/" + id + "/complete", token, completeBody(99, 50), 403);

        matrix.updateRolePermissions(ERole.ROLE_VIEWER, Set.of(Permission.WAREHOUSE_ADJUST));
        assertThat(call(HttpMethod.GET, API + "/options", token, null, 200).at("/data/canAdjust").asBoolean()).isTrue();
        call(HttpMethod.POST, API, token, createBody(outside), 403);
        long foreignId = call(HttpMethod.POST, API, adminToken, createBody(outside), 200).at("/data/id").asLong();
        call(HttpMethod.GET, API + "/" + foreignId, token, null, 403);
        call(HttpMethod.POST, API + "/" + foreignId + "/complete", token, completeBody(0, 0), 403);
        call(HttpMethod.PUT, API + "/" + foreignId + "/items", token, Map.of("items", lines(0, 0)), 403);
        call(HttpMethod.POST, API + "/" + foreignId + "/cancel", token, null, 403);
        JsonNode result = call(HttpMethod.POST, API + "/" + id + "/complete", token, completeBody(99, 50), 200).get("data");
        assertThat(result.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(result.get("canEdit").asBoolean()).isFalse();
        assertThat(result.get("canAdjust").asBoolean()).isFalse();
        matrix.updateRolePermissions(ERole.ROLE_ADMIN, Set.of());
        call(HttpMethod.GET, API + "/options", adminToken, null, 200);
    }

    @Test
    void noPermissionRoleDoesNotBypassMatrixAndMultipleRolesUseUnion() {
        long id = draft();
        User user = users.findByUsername(username()).orElseThrow();
        matrix.updateRolePermissions(ERole.ROLE_WAREHOUSE_MANAGER, Set.of());
        user.setRoles(Set.of(roles.findByName(ERole.ROLE_WAREHOUSE_MANAGER).orElseThrow()));
        users.save(user);
        call(HttpMethod.GET, API + "/options", token, null, 403);
        call(HttpMethod.POST, API + "/" + id + "/complete", token, completeBody(99, 50), 403);
        user.setRoles(Set.of(roles.findByName(ERole.ROLE_WAREHOUSE_MANAGER).orElseThrow(),
                roles.findByName(ERole.ROLE_VIEWER).orElseThrow()));
        users.save(user);
        call(HttpMethod.POST, API + "/" + id + "/complete", token, completeBody(99, 50), 200);
    }

    @Test
    void zeroIsCountedNullClearsDraftAndRequiredReasonAndPrecisionAreValidated() {
        long id = draft();
        assertThat(detail(id).get("uncountedItems").asInt()).isEqualTo(2);
        call(HttpMethod.PUT, API + "/" + id + "/items", token, Map.of("items", lines(0, 50)), 200);
        assertThat(detail(id).get("uncountedItems").asInt()).isZero();
        Map<String, Object> cleared = new HashMap<>();
        cleared.put("productId", first.getId()); cleared.put("actualQuantity", null);
        call(HttpMethod.PUT, API + "/" + id + "/items", token, Map.of("items", List.of(cleared)), 200);
        assertThat(detail(id).get("uncountedItems").asInt()).isEqualTo(1);
        call(HttpMethod.POST, API + "/" + id + "/complete", token, Map.of("reason", "count"), 400);
        call(HttpMethod.POST, API + "/" + id + "/complete", token, Map.of("reason", "  "), 400);
        for (String bad : List.of("-1", "0.00001", "100000000000")) {
            call(HttpMethod.PUT, API + "/" + id + "/items", token, Map.of("items", List.of(line(first, new BigDecimal(bad)))), 400);
            var body = new HashMap<>(createBody(warehouse));
            body.put("items", List.of(line(first, new BigDecimal(bad))));
            call(HttpMethod.POST, API, token, body, 400);
            call(HttpMethod.POST, API + "/" + id + "/complete", token,
                    Map.of("reason", "count", "items", List.of(line(first, new BigDecimal(bad)))), 400);
        }
        JsonNode done = call(HttpMethod.POST, API + "/" + id + "/complete", token, completeBody(0, 50), 200).get("data");
        assertThat(done.get("uncountedItems").asInt()).isZero();
        assertThat(stock(first).getBaseQuantity()).isZero();
    }

    @Test
    void duplicateForeignAndNullRowsAreRejectedAndInlineCountsRollBack() {
        long id = draft();
        List<List<?>> badRows = List.of(List.of(line(first, 90), line(first, 80)),
                List.of(line(first, 90), Map.of("productId", Long.MAX_VALUE, "actualQuantity", 1)),
                Arrays.asList(line(first, 90), null), List.of(Map.of("actualQuantity", 2)));
        for (List<?> lines : badRows) {
            call(HttpMethod.PUT, API + "/" + id + "/items", token, Map.of("items", lines), 400);
            call(HttpMethod.POST, API + "/" + id + "/complete", token, Map.of("reason", "count", "items", lines), 400);
            var body = new HashMap<>(createBody(warehouse)); body.put("items", lines);
            call(HttpMethod.POST, API, token, body, 400);
            assertThat(detail(id).get("uncountedItems").asInt()).isEqualTo(2);
        }
        assertThat(counts.count()).isGreaterThanOrEqualTo(1);
        assertThat(stock(first).getBaseQuantity()).isEqualByComparingTo("100");
    }

    @Test
    void reservedSecondRowRejectsWholeCompletionWithoutAnyInventoryOrAuditWrites() {
        tx.executeWithoutResult(status -> inventories.reserveIfAvailable(warehouse.getId(), second.getId(), new BigDecimal("30")));
        long id = draft();
        long audits = logs.count();
        JsonNode rejected = call(HttpMethod.POST, API + "/" + id + "/complete", token, completeBody(90, 29), 400);
        assertThat(rejected.get("message").asText()).contains("giữ chỗ");
        assertThat(stock(first).getBaseQuantity()).isEqualByComparingTo("100");
        assertThat(stock(second).getBaseQuantity()).isEqualByComparingTo("50");
        assertThat(stock(second).getReservedQuantity()).isEqualByComparingTo("30");
        assertThat(detail(id).get("uncountedItems").asInt()).isEqualTo(2);
        assertThat(logs.count()).isEqualTo(audits);
        call(HttpMethod.POST, API + "/" + id + "/complete", token, completeBody(90, 30), 200);
        assertThat(stock(second).getAvailableQuantity()).isZero();
    }

    @Test
    void lateAuditFailureRollsBackInventoryInlineCountsStatusAndEarlierAudits() {
        long id = draft();
        long audits = logs.count();
        doThrow(new IllegalStateException("forced terminal audit failure")).when(audit).logAction(
                eq("STOCK_COUNT"), anyString(), eq("COMPLETE"), anyString(), anyString(), anyString(), anyString());
        call(HttpMethod.POST, API + "/" + id + "/complete", token, completeBody(90, 45), 400);
        assertThat(stock(first).getBaseQuantity()).isEqualByComparingTo("100");
        assertThat(stock(second).getBaseQuantity()).isEqualByComparingTo("50");
        assertThat(detail(id).get("status").asText()).isEqualTo("DRAFT");
        assertThat(detail(id).get("uncountedItems").asInt()).isEqualTo(2);
        assertThat(logs.count()).isEqualTo(audits);
    }

    @Test
    void changedInventoryAndNetZeroMovementsRequireCancelAndRecount() {
        long id = draft();
        changeQuantity(first, "101");
        changeQuantity(first, "100");
        long audits = logs.count();
        JsonNode rejected = call(HttpMethod.POST, API + "/" + id + "/complete", token, completeBody(90, 50), 400);
        assertThat(rejected.get("message").asText()).contains("đã thay đổi", "hủy phiếu", "tạo phiếu kiểm kê mới");
        assertThat(stock(first).getBaseQuantity()).isEqualByComparingTo("100");
        assertThat(logs.count()).isEqualTo(audits);
        call(HttpMethod.POST, API + "/" + id + "/cancel", token, Map.of("reason", "Recount"), 200);
        call(HttpMethod.POST, API + "/" + draft() + "/complete", token, completeBody(90, 50), 200);
    }

    @Test
    void inventoryCreatedSinceZeroSnapshotIsRejectedEvenWhenItsQuantityIsStillZero() {
        Product absent = product("ZERO-" + UUID.randomUUID().toString().substring(0, 8));
        long id = draft();
        inventories.save(WarehouseInventory.builder().warehouse(warehouse).product(absent).baseUnit("lon").build());
        var lines = new ArrayList<>(lines(100, 50)); lines.add(line(absent, 0));
        call(HttpMethod.POST, API + "/" + id + "/complete", token, Map.of("reason", "count", "items", lines), 400);
        assertThat(detail(id).get("status").asText()).isEqualTo("DRAFT");
    }

    @Test
    void absentInventoryCanBeCreatedByCompletionWithFrozenUnitDespiteProductRename() {
        Product absent = product("NEW-" + UUID.randomUUID().toString().substring(0, 8));
        WarehouseInventory inv = stock(first); inv.setBaseUnit("hộp"); inventories.save(inv);
        long id = draft();
        absent.setUnit("thùng"); products.save(absent);
        first.setUnit("thùng"); products.save(first);
        var lines = new ArrayList<>(lines(100, 50)); lines.add(line(absent, 3));
        call(HttpMethod.POST, API + "/" + id + "/complete", token, Map.of("reason", "count", "items", lines), 200);
        assertThat(stock(absent).getBaseQuantity()).isEqualByComparingTo("3");
        assertThat(stock(absent).getBaseUnit()).isEqualTo("lon");
        assertThat(stock(first).getBaseUnit()).isEqualTo("hộp");
    }

    @Test
    void legacyDraftWithoutSnapshotMarkerCannotOverwriteInventory() {
        long id = draft();
        tx.executeWithoutResult(status -> {
            WarehouseStockCount sc = counts.findById(id).orElseThrow();
            sc.getItems().forEach(item -> item.setSnapshotCaptured(false));
        });
        call(HttpMethod.POST, API + "/" + id + "/complete", token, completeBody(90, 50), 400);
        assertThat(stock(first).getBaseQuantity()).isEqualByComparingTo("100");
    }

    @Test
    void twoConcurrentCompletionsApplyAndAuditOnlyOnce() throws Exception {
        long id = draft();
        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> complete = () -> { start.await(5, TimeUnit.SECONDS);
                return exchange(HttpMethod.POST, API + "/" + id + "/complete", token, completeBody(90, 45)).getStatusCode().value(); };
            Future<Integer> a = pool.submit(complete), b = pool.submit(complete);
            assertThat(List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 400);
            assertThat(stock(first).getBaseQuantity()).isEqualByComparingTo("90");
            assertThat(logs.findAll().stream().filter(l -> l.getAction().equals("COMPLETE")
                    && l.getEntityId().equals(detail(id).get("code").asText())).count()).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }

    @Test
    void quantityOnlyReservationAfterSnapshotRemainsCompatibleWithJpaVersioning() {
        long id = draft();
        Long before = stock(first).getVersion();
        tx.executeWithoutResult(status -> assertThat(inventories.reserveIfAvailable(
                warehouse.getId(), first.getId(), new BigDecimal("30"))).isEqualTo(1));
        assertThat(stock(first).getVersion()).isEqualTo(before);
        call(HttpMethod.POST, API + "/" + id + "/complete", token, completeBody(90, 50), 200);
        assertThat(stock(first).getBaseQuantity()).isEqualByComparingTo("90");
        assertThat(stock(first).getReservedQuantity()).isEqualByComparingTo("30");
        assertThat(stock(first).getVersion()).isGreaterThan(before);
    }

    @Test
    void concurrentCompletionsOfDifferentDraftsCannotOverwriteTheSameInventory() throws Exception {
        long a = draft(), b = draft();
        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> one = pool.submit(() -> { start.await(5, TimeUnit.SECONDS); return exchange(HttpMethod.POST,
                    API + "/" + a + "/complete", token, completeBody(90, 45)).getStatusCode().value(); });
            Future<Integer> two = pool.submit(() -> { start.await(5, TimeUnit.SECONDS); return exchange(HttpMethod.POST,
                    API + "/" + b + "/complete", token, completeBody(95, 45)).getStatusCode().value(); });
            assertThat(List.of(one.get(15, TimeUnit.SECONDS), two.get(15, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 400);
            assertThat(stock(first).getBaseQuantity().intValueExact()).isIn(90, 95);
            assertThat(stock(second).getBaseQuantity()).isEqualByComparingTo("45");
            assertThat(List.of(detail(a).get("status").asText(), detail(b).get("status").asText()))
                    .containsExactlyInAnyOrder("DRAFT", "COMPLETED");
        } finally { pool.shutdownNow(); }
    }

    @Test
    void actualHttpCancelAndCompleteRaceHasOneTerminalWinner() throws Exception {
        long id = draft();
        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> complete = pool.submit(() -> { start.await(5, TimeUnit.SECONDS); return exchange(HttpMethod.POST,
                    API + "/" + id + "/complete", token, completeBody(90, 45)).getStatusCode().value(); });
            Future<Integer> cancel = pool.submit(() -> { start.await(5, TimeUnit.SECONDS); return exchange(HttpMethod.POST,
                    API + "/" + id + "/cancel", token, Map.of("reason", "Cancel race")).getStatusCode().value(); });
            int completed = complete.get(15, TimeUnit.SECONDS), cancelled = cancel.get(15, TimeUnit.SECONDS);
            assertThat(List.of(completed, cancelled)).containsExactlyInAnyOrder(200, 400);
            assertThat(detail(id).get("status").asText()).isEqualTo(completed == 200 ? "COMPLETED" : "CANCELLED");
            assertThat(stock(first).getBaseQuantity()).isEqualByComparingTo(completed == 200 ? "90" : "100");
        } finally { pool.shutdownNow(); }
    }

    @Test
    void concurrentItemUpdateAndCompleteUseOneConsistentSetOfCounts() throws Exception {
        long id = draft();
        call(HttpMethod.PUT, API + "/" + id + "/items", token, Map.of("items", lines(90, 45)), 200);
        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> complete = pool.submit(() -> { start.await(5, TimeUnit.SECONDS); return exchange(HttpMethod.POST,
                    API + "/" + id + "/complete", token, Map.of("reason", "Complete race")).getStatusCode().value(); });
            Future<Integer> update = pool.submit(() -> { start.await(5, TimeUnit.SECONDS); return exchange(HttpMethod.PUT,
                    API + "/" + id + "/items", token, Map.of("items", lines(80, 40))).getStatusCode().value(); });
            assertThat(complete.get(15, TimeUnit.SECONDS)).isEqualTo(200);
            int updated = update.get(15, TimeUnit.SECONDS);
            assertThat(updated).isIn(200, 400);
            assertThat(stock(first).getBaseQuantity()).isEqualByComparingTo(updated == 200 ? "80" : "90");
            assertThat(stock(second).getBaseQuantity()).isEqualByComparingTo(updated == 200 ? "40" : "45");
            assertThat(detail(id).get("status").asText()).isEqualTo("COMPLETED");
        } finally { pool.shutdownNow(); }
    }

    @Test
    void completionWaitingForHeaderReloadsLastCommittedChildCounts() throws Exception {
        long id = draft();
        call(HttpMethod.PUT, API + "/" + id + "/items", token, Map.of("items", lines(90, 45)), 200);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1);
        try {
            Future<?> writer = pool.submit(() -> tx.executeWithoutResult(status -> {
                WarehouseStockCount count = counts.findByIdForUpdate(id).orElseThrow();
                count.getItems().forEach(item -> {
                    item.setActualQuantity(new BigDecimal(item.getProduct().getId().equals(first.getId()) ? "80" : "40"));
                    item.calculateDifference();
                });
                counts.flush(); locked.countDown(); await(release);
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            Future<ResponseEntity<JsonNode>> completion = pool.submit(() -> exchange(HttpMethod.POST,
                    API + "/" + id + "/complete", token, Map.of("reason", "Read counts committed while waiting")));
            assertThatThrownBy(() -> completion.get(400, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown(); writer.get(10, TimeUnit.SECONDS);
            assertThat(completion.get(10, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
            assertThat(stock(first).getBaseQuantity()).isEqualByComparingTo("80");
            assertThat(stock(second).getBaseQuantity()).isEqualByComparingTo("40");
            assertThat(detail(id).get("status").asText()).isEqualTo("COMPLETED");
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test
    void updateAndCancelWaitForCountHeaderAndCannotChangeCompletedDraft() throws Exception {
        long id = draft();
        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1), started = new CountDownLatch(2);
        try {
            Future<?> winner = pool.submit(() -> tx.executeWithoutResult(status -> {
                WarehouseStockCount count = counts.findByIdForUpdate(id).orElseThrow();
                locked.countDown(); await(release);
                count.setStatus(StockCountStatus.COMPLETED);
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            Future<Integer> update = pool.submit(() -> { started.countDown(); return exchange(HttpMethod.PUT,
                    API + "/" + id + "/items", token, Map.of("items", lines(10, 10))).getStatusCode().value(); });
            Future<Integer> cancel = pool.submit(() -> { started.countDown(); return exchange(HttpMethod.POST,
                    API + "/" + id + "/cancel", token, null).getStatusCode().value(); });
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> update.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            assertThatThrownBy(() -> cancel.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown(); winner.get(10, TimeUnit.SECONDS);
            assertThat(update.get(10, TimeUnit.SECONDS)).isEqualTo(400);
            assertThat(cancel.get(10, TimeUnit.SECONDS)).isEqualTo(400);
            assertThat(detail(id).get("status").asText()).isEqualTo("COMPLETED");
            assertThat(detail(id).get("uncountedItems").asInt()).isEqualTo(2);
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test
    void realConcurrentReservationCommitsBeforeCompletionAndCannotBeOverwritten() throws Exception {
        long id = draft();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1);
        try {
            Future<?> reservation = pool.submit(() -> tx.executeWithoutResult(status -> {
                inventories.findByWarehouseIdAndProductIdForUpdate(warehouse.getId(), first.getId()).orElseThrow();
                assertThat(inventories.reserveIfAvailable(warehouse.getId(), first.getId(), new BigDecimal("80"))).isEqualTo(1);
                locked.countDown(); await(release);
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            Future<ResponseEntity<JsonNode>> completion = pool.submit(() -> exchange(HttpMethod.POST,
                    API + "/" + id + "/complete", token, completeBody(70, 45)));
            assertThatThrownBy(() -> completion.get(400, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown(); reservation.get(10, TimeUnit.SECONDS);
            ResponseEntity<JsonNode> result = completion.get(10, TimeUnit.SECONDS);
            assertThat(result.getStatusCode().value()).isEqualTo(400);
            assertThat(result.getBody().get("message").asText()).contains("giữ chỗ");
            assertThat(stock(first).getReservedQuantity()).isEqualByComparingTo("80");
            assertThat(stock(first).getBaseQuantity()).isEqualByComparingTo("100");
            assertThat(stock(second).getBaseQuantity()).isEqualByComparingTo("50");
            assertThat(detail(id).get("status").asText()).isEqualTo("DRAFT");
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test
    void itemNotesAllow500CharactersOnCreateUpdateAndCompleteAndReject501() {
        String accepted = "a".repeat(500), rejected = "a".repeat(501);
        var body = new HashMap<>(createBody(warehouse));
        body.put("items", List.of(Map.of("productId", first.getId(), "actualQuantity", 100, "note", accepted)));
        long id = call(HttpMethod.POST, API, token, body, 200).at("/data/id").asLong();
        assertThat(detail(id).at("/items/0/note").asText()).isEqualTo(accepted);
        for (String note : List.of(accepted, rejected)) {
            var items = List.of(Map.of("productId", second.getId(), "actualQuantity", 50, "note", note));
            call(HttpMethod.PUT, API + "/" + id + "/items", token, Map.of("items", items), note.length() == 500 ? 200 : 400);
        }
        var badItems = List.of(Map.of("productId", first.getId(), "actualQuantity", 100, "note", rejected));
        body.put("items", badItems);
        call(HttpMethod.POST, API, token, body, 400);
        call(HttpMethod.POST, API + "/" + id + "/complete", token, Map.of("reason", "count", "items", badItems), 400);
        call(HttpMethod.POST, API + "/" + id + "/complete", token, Map.of("reason", "count", "items",
                List.of(Map.of("productId", first.getId(), "actualQuantity", 100, "note", accepted))), 200);
        assertThat(detail(id).at("/items/0/note").asText()).isEqualTo(accepted);
        assertThat(detail(id).at("/items/1/note").asText()).isEqualTo(accepted);
    }

    @Test
    void listOptionsCategorySnapshotAndCancelPersistAcrossRequests() {
        long id = draft();
        JsonNode data = call(HttpMethod.GET, API + "?warehouseId=" + warehouse.getId() + "&categoryId=" + category.getId()
                + "&status=DRAFT&size=1", token, null, 200).get("data");
        assertThat(data.get("totalElements").asInt()).isEqualTo(1);
        assertThat(data.at("/content/0/id").asLong()).isEqualTo(id);
        assertThat(call(HttpMethod.GET, API + "/options", token, null, 200).at("/data/warehouses/0/id").asLong()).isEqualTo(warehouse.getId());
        assertThat(detail(id).get("totalItems").asInt()).isEqualTo(2);
        call(HttpMethod.POST, API + "/" + id + "/cancel", token, Map.of("reason", "Wrong scope"), 200);
        assertThat(detail(id).get("status").asText()).isEqualTo("CANCELLED");
        assertThat(detail(id).get("cancelReason").asText()).isEqualTo("Wrong scope");
        call(HttpMethod.PUT, API + "/" + id + "/items", token, Map.of("items", lines(0, 0)), 400);
        call(HttpMethod.POST, API + "/" + id + "/complete", token, completeBody(0, 0), 400);
        call(HttpMethod.POST, API + "/" + id + "/cancel", token, null, 400);
        assertThat(stock(first).getBaseQuantity()).isEqualByComparingTo("100");
    }

    private Product product(String code) {
        return products.save(Product.builder().code(code).name(code).category(category).unit("lon")
                .price(BigDecimal.TEN).costPrice(BigDecimal.ONE).build());
    }
    private String username() { return "count-" + warehouse.getCode().substring(4); }
    private String login(String username) {
        return call(HttpMethod.POST, "/api/auth/login", null,
                Map.of("username", username, "password", "StockCountTest2026"), 200).get("accessToken").asText();
    }
    private Map<String, Object> createBody(Warehouse target) {
        return Map.of("warehouseId", target.getId(), "categoryId", category.getId());
    }
    private long draft() { return call(HttpMethod.POST, API, token, createBody(warehouse), 200).at("/data/id").asLong(); }
    private JsonNode detail(long id) { return call(HttpMethod.GET, API + "/" + id, token, null, 200).get("data"); }
    private Map<String, Object> line(Product product, Object quantity) {
        return Map.of("productId", product.getId(), "actualQuantity", quantity);
    }
    private List<Map<String, Object>> lines(int a, int b) { return List.of(line(first, a), line(second, b)); }
    private Map<String, Object> completeBody(int a, int b) { return Map.of("reason", "Physical recount", "items", lines(a, b)); }
    private WarehouseInventory stock(Product product) {
        return inventories.findByWarehouseIdAndProductId(warehouse.getId(), product.getId()).orElseThrow();
    }
    private void changeQuantity(Product product, String quantity) {
        tx.executeWithoutResult(status -> {
            WarehouseInventory inv = inventories.findByWarehouseIdAndProductIdForUpdate(warehouse.getId(), product.getId()).orElseThrow();
            inv.setBaseQuantity(new BigDecimal(quantity));
        });
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out waiting for test transaction"); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
    }
    private ResponseEntity<JsonNode> exchange(HttpMethod method, String path, String jwt, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (jwt != null) headers.setBearerAuth(jwt);
        return http.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }
    private JsonNode call(HttpMethod method, String path, String jwt, Object body, int expected) {
        var response = exchange(method, path, jwt, body);
        assertThat(response.getStatusCode().value()).as("%s %s: %s", method, path, response.getBody()).isEqualTo(expected);
        return response.getBody();
    }
}
