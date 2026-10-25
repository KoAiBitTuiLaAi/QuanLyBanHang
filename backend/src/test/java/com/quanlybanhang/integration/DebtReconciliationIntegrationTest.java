package com.quanlybanhang.integration;

import com.fasterxml.jackson.databind.*;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import com.quanlybanhang.config.RolePermissionConfig;
import com.quanlybanhang.model.*;
import com.quanlybanhang.repository.*;
import com.quanlybanhang.security.JwtUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(properties = {
 "spring.datasource.url=jdbc:h2:mem:reconciliation_test;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
 "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
 "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
 "spring.jpa.show-sql=false", "app.jwt.secret=reconciliation-test-secret-at-least-32-characters",
 "app.bootstrap.admin-password=Reconcile2026!", "app.bootstrap.admin-email=reconcile@example.invalid",
 "app.seed-demo-users=false", "app.seed-demo-data=false", "app.payment-reminder.scheduler.enabled=false",
 "app.debt-statement.max-rows=100"})
@AutoConfigureMockMvc @Transactional @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DebtReconciliationIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired DealerRepository dealers;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired OrderRepository orders;
    @Autowired DebtPaymentRepository payments;
    @Autowired InvoiceRepository invoices;
    @Autowired InvoiceAdjustmentRepository adjustments;
    @Autowired DebtReconciliationRepository reconciliations;
    @Autowired RolePermissionConfig matrix;
    @Autowired JwtUtils jwt;
    Dealer dealer, other;
    String staff, own, stranger, admin;
    final String finance = "/api/finance/debt-reconciliations", portal = "/api/portal/debt-reconciliations";

    @BeforeEach void setup() {
        matrix.updateRolePermissions(ERole.ROLE_ACCOUNTANT, Set.of(Permission.FINANCE_VIEW_DEBT, Permission.FINANCE_MANAGE));
        matrix.updateRolePermissions(ERole.ROLE_DEALER, Set.of(Permission.FINANCE_VIEW_DEBT));
        dealer = dealers.saveAndFlush(Dealer.builder().code("DL-" + UUID.randomUUID().toString().substring(0, 8))
                .name("Đại lý Nguyễn Ánh").address("Hà Nội").phone("0912345678").build());
        other = dealers.saveAndFlush(Dealer.builder().code("DL-" + UUID.randomUUID().toString().substring(0, 8)).name("Đại lý khác").build());
        staff = token(ERole.ROLE_ACCOUNTANT, null); own = token(ERole.ROLE_DEALER, dealer);
        stranger = token(ERole.ROLE_DEALER, other); admin = jwt.generateAccessToken("admin", 0L);
    }
    String token(ERole role, Dealer d) {
        String name = "rc-" + UUID.randomUUID();
        users.saveAndFlush(User.builder().username(name).email(name + "@example.invalid").password("unused")
                .dealer(d).roles(new HashSet<>(Set.of(roles.findByName(role).orElseThrow()))).build());
        return jwt.generateAccessToken(name, 0L);
    }
    MockHttpServletResponse call(String method, String path, String token, Object body, int expected) throws Exception {
        var req = switch (method) {case "POST" -> post(path); case "PUT" -> put(path); case "DELETE" -> delete(path); default -> get(path);};
        if (token != null) req.header("Authorization", "Bearer " + token);
        if (body != null) {
            req.contentType(MediaType.APPLICATION_JSON);
            if (body instanceof String text) req.content(text); else req.content(json.writeValueAsBytes(body));
        }
        var response = mvc.perform(req).andReturn().getResponse();
        assertThat(response.getStatus()).as("%s %s: %s", method, path,
                String.valueOf(response.getContentType()).contains("json") ? response.getContentAsString() : "binary").isEqualTo(expected);
        return response;
    }
    Map<String, Object> createBody() {
        return new HashMap<>(Map.of("dealerId", dealer.getId(), "from", "2026-10-01", "to", "2026-10-31", "requestKey", UUID.randomUUID()));
    }
    JsonNode data(MockHttpServletResponse response) throws Exception { return json.readTree(response.getContentAsByteArray()).path("data"); }
    JsonNode create() throws Exception { return data(call("POST", finance, staff, createBody(), 200)); }
    void order(String date, String amount, String status) {
        orders.saveAndFlush(Order.builder().orderCode("ORD-" + UUID.randomUUID()).dealer(dealer)
                .recordedAt(LocalDateTime.parse(date)).totalAmount(new BigDecimal(amount)).status(status).build());
    }
    void payment(String date, String amount) {
        payments.saveAndFlush(DebtPayment.builder().paymentCode("PAY-" + UUID.randomUUID()).dealer(dealer)
                .paymentDate(LocalDate.parse(date)).amount(new BigDecimal(amount)).build());
    }
    @Test void periodSnapshotBalancesIgnoreDraftsAndKeepNegativeBalance() throws Exception {
        order("2026-09-30T23:59:59", "1000", "RECORDED"); payment("2026-09-30", "200");
        order("2026-10-01T00:00:00", "100", "RECORDED"); payment("2026-10-31", "950");
        order("2026-11-01T00:00:00", "999", "RECORDED"); order("2026-10-20T00:00:00", "999", "DRAFT");
        order("2026-10-20T00:00:00", "999", "CANCELLED");
        var r = create(); var s = r.path("statement");
        assertThat(s.path("openingBalance").asText()).isEqualTo("800.00");
        assertThat(s.path("totalIncrease").asText()).isEqualTo("100.00");
        assertThat(s.path("totalDecrease").asText()).isEqualTo("950.00");
        assertThat(s.path("closingBalance").asText()).isEqualTo("-50.00");
        assertThat(s.path("entries").size()).isEqualTo(2);
        assertThat(r.path("status").asText()).isEqualTo("PENDING");
    }
    @Test void savedStatementDoesNotChangeWithNewBackdatedLedgerEntriesOrDealerRename() throws Exception {
        order("2026-10-15T10:00:00", "100.25", "RECORDED"); var r = create();
        payment("2026-10-20", "30.10"); dealer.setName("Tên mới"); dealers.saveAndFlush(dealer);
        var fetched = data(call("GET", finance + "/" + r.path("id").asLong(), staff, null, 200));
        assertThat(fetched.path("statement")).isEqualTo(r.path("statement"));
        assertThat(fetched.path("version")).isEqualTo(r.path("version"));
    }
    @Test void monetaryStringsPreserveLargeAmountsAndCents() throws Exception {
        order("2026-10-15T10:00:00", "999999999999.99", "RECORDED"); payment("2026-10-16", "0.01");
        var r = create();
        assertThat(r.path("statement").path("closingBalance").isTextual()).isTrue();
        assertThat(r.path("statement").path("closingBalance").asText()).isEqualTo("999999999999.98");
    }
    @Test void adjustmentsIncludingReturnCreditAreIncludedOnce() throws Exception {
        order("2026-10-01T00:00:00", "100", "DELIVERED");
        var invoice = invoices.saveAndFlush(Invoice.builder().invoiceNumber("INV-" + UUID.randomUUID().toString().substring(0, 12))
                .invoiceCode("INV-" + UUID.randomUUID()).dealer(dealer).dealerCode(dealer.getCode()).dealerName(dealer.getName())
                .invoiceDate(LocalDateTime.parse("2026-10-01T00:00:00")).totalAmount(new BigDecimal("100")).build());
        adjustments.saveAndFlush(InvoiceAdjustment.builder().invoice(invoice).adjustmentCode("DCTH-" + UUID.randomUUID())
                .adjustmentDate(LocalDateTime.parse("2026-10-02T00:00:00")).adjustmentType(InvoiceAdjustmentType.DECREASE)
                .adjustmentAmount(new BigDecimal("-25")).adjustmentReason("Trả hàng").build());
        assertThat(create().path("statement").path("closingBalance").asText()).isEqualTo("75.00");
    }
    @Test void requestKeyIsIdempotentButCannotBeReusedForAnotherPeriod() throws Exception {
        var body = createBody(); var first = data(call("POST", finance, staff, body, 200));
        var second = data(call("POST", finance, staff, body, 200));
        assertThat(second.path("id")).isEqualTo(first.path("id"));
        body.put("from", "2026-10-02"); call("POST", finance, staff, body, 409);
    }
    @Test void invalidInputCannotCreateOrEditSavedStatements() throws Exception {
        var body = createBody(); body.put("to", "2026-09-30"); call("POST", finance, staff, body, 400);
        body.put("to", "bad"); call("POST", finance, staff, body, 400);
        body = createBody(); body.remove("requestKey"); call("POST", finance, staff, body, 400);
        var r = create(); String path = finance + "/" + r.path("id").asLong();
        call("PUT", path, staff, Map.of("statement", "tampered"), 405);
        call("DELETE", path, staff, null, 405);
        call("GET", finance + "?page=-1", staff, null, 400);
    }
    @Test void ownDealerCanConfirmAndResponseSurvivesReload() throws Exception {
        order("2026-10-15T00:00:00", "25.10", "RECORDED"); var r = create();
        String path = portal + "/" + r.path("id").asLong();
        var updated = data(call("POST", path + "/response", own, Map.of("version", 0, "status", "CONFIRMED"), 200));
        assertThat(updated.path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(updated.path("claimedBalance").asText()).isEqualTo("25.10");
        assertThat(updated.path("difference").asText()).isEqualTo("0.00");
        assertThat(updated.path("respondedBy").asText()).isNotBlank();
        assertThat(data(call("GET", path, own, null, 200))).isEqualTo(updated);
        assertThat(data(call("GET", finance + "/" + r.path("id").asLong(), staff, null, 200))).isEqualTo(updated);
    }
    @Test void discrepancyRequiresRealDifferenceReasonAndUsesServerBalance() throws Exception {
        order("2026-10-15T00:00:00", "100", "RECORDED"); var r = create();
        String path = portal + "/" + r.path("id").asLong() + "/response";
        call("POST", path, own, Map.of("version", 0, "status", "DISPUTED", "claimedBalance", "90", "reason", " "), 400);
        call("POST", path, own, Map.of("version", 0, "status", "DISPUTED", "claimedBalance", "100", "reason", "Giống"), 400);
        call("POST", path, own, Map.of("version", 0, "status", "DISPUTED", "claimedBalance", "90.001", "reason", "Lệch"), 400);
        call("POST", path, own, Map.of("version", 0, "status", "DISPUTED", "claimedBalance", "90", "reason", "X".repeat(501)), 400);
        var result = data(call("POST", path, own, Map.of("version", 0, "status", "DISPUTED", "claimedBalance", "90.25", "reason", "  Chưa ghi nhận phiếu thu  ", "difference", "999"), 200));
        assertThat(result.path("difference").asText()).isEqualTo("9.75");
        assertThat(result.path("responseReason").asText()).isEqualTo("Chưa ghi nhận phiếu thu");
    }
    @Test void staleOrRepeatedResponseCannotOverwriteOriginal() throws Exception {
        var r = create(); String path = portal + "/" + r.path("id").asLong() + "/response";
        call("POST", path, own, Map.of("version", 1, "status", "CONFIRMED"), 409);
        call("POST", path, own, Map.of("version", 0, "status", "PENDING"), 400);
        call("POST", path, own, Map.of("version", 0, "status", "CONFIRMED"), 200);
        call("POST", path, own, Map.of("version", 1, "status", "DISPUTED", "claimedBalance", "1", "reason", "Lệch"), 409);
    }
    @Test void staffAdminAndUnrelatedDealerCannotImpersonateConfirmation() throws Exception {
        var r = create(); String path = portal + "/" + r.path("id").asLong();
        call("GET", path, stranger, null, 403); call("GET", path + "/pdf", stranger, null, 403);
        for (String token : List.of(staff, admin, stranger))
            call("POST", path + "/response", token, Map.of("version", 0, "status", "CONFIRMED"), 403);
        call("POST", finance, own, createBody(), 403);
        call("GET", finance + "/" + r.path("id").asLong(), own, null, 403);
        var ownList = data(call("GET", portal, own, null, 200));
        assertThat(ownList.path("content").findValuesAsText("id")).contains(r.path("id").asText());
        assertThat(data(call("GET", portal, stranger, null, 200)).path("totalElements").asInt()).isZero();
        call("GET", portal + "?dealerId=" + other.getId(), own, null, 403);
        call("GET", finance, null, null, 401);
    }
    @Test void grantAndRevokeAreEffectiveOnTheSameJwt() throws Exception {
        var r = create(); String path = finance + "/" + r.path("id").asLong();
        matrix.updateRolePermissions(ERole.ROLE_ACCOUNTANT, Set.of(Permission.FINANCE_VIEW_DEBT));
        call("GET", path, staff, null, 200); call("POST", finance, staff, createBody(), 403);
        matrix.updateRolePermissions(ERole.ROLE_ACCOUNTANT, Set.of());
        call("GET", path, staff, null, 403); call("GET", path + "/pdf", staff, null, 403);
        call("GET", finance + "/options", staff, null, 403);
        matrix.updateRolePermissions(ERole.ROLE_ACCOUNTANT, Set.of(Permission.FINANCE_VIEW_DEBT, Permission.FINANCE_MANAGE));
        call("GET", path, staff, null, 200);
        matrix.updateRolePermissions(ERole.ROLE_DEALER, Set.of());
        call("GET", portal, own, null, 403);
        call("POST", portal + "/" + r.path("id").asLong() + "/response", own, Map.of("version", 0, "status", "CONFIRMED"), 403);
        matrix.updateRolePermissions(ERole.ROLE_DEALER, Set.of(Permission.FINANCE_VIEW_DEBT));
        call("POST", portal + "/" + r.path("id").asLong() + "/response", own, Map.of("version", 0, "status", "CONFIRMED"), 200);
        call("POST", finance, admin, createBody(), 200);
    }
    @Test void optionsAndListsAreScopedAndPaginatedWithoutOtherPermissions() throws Exception {
        var ownOptions = data(call("GET", portal + "/options", own, null, 200));
        assertThat(ownOptions.path("content").size()).isEqualTo(1);
        assertThat(ownOptions.path("content").get(0).path("id").asLong()).isEqualTo(dealer.getId());
        assertThat(data(call("GET", finance + "/options?keyword=" + dealer.getCode(), staff, null, 200)).path("content").size()).isEqualTo(1);
        create(); assertThat(data(call("GET", finance + "?dealerId=" + dealer.getId() + "&from=2026-10-01&to=2026-10-31", staff, null, 200)).path("totalElements").asInt()).isEqualTo(1);
        call("GET", portal, staff, null, 403);
        call("GET", portal, token(ERole.ROLE_DEALER, null), null, 403);
    }
    @Test void salespersonOnlySeesAssignedDealersEvenWithViewPermission() throws Exception {
        var r = create(); matrix.updateRolePermissions(ERole.ROLE_SALES, Set.of(Permission.FINANCE_VIEW_DEBT, Permission.FINANCE_MANAGE));
        String sales = token(ERole.ROLE_SALES, null);
        call("GET", finance + "/" + r.path("id").asLong(), sales, null, 403);
        assertThat(data(call("GET", finance, sales, null, 200)).path("totalElements").asInt()).isZero();
        dealer.setAssignedUser(users.findByUsername(jwt.getUsernameFromToken(sales)).orElseThrow()); dealers.saveAndFlush(dealer);
        call("GET", finance + "/" + r.path("id").asLong(), sales, null, 200);
        call("POST", finance, sales, createBody(), 200);
    }
    @Test void pdfUsesSavedDocumentVietnameseStatusAndSignatureBlocks() throws Exception {
        order("2026-10-01T00:00:00", "123.45", "RECORDED"); var r = create();
        payment("2026-10-02", "23.45");
        var response = call("GET", portal + "/" + r.path("id").asLong() + "/pdf", own, null, 200);
        assertThat(response.getContentType()).isEqualTo("application/pdf");
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
        var reader = new PdfReader(response.getContentAsByteArray());
        try {
            String text = new PdfTextExtractor(reader).getTextFromPage(1);
            assertThat(text).contains("BIÊN BẢN ĐỐI CHIẾU CÔNG NỢ", "Đại lý Nguyễn Ánh", r.path("code").asText(),
                    "123,45", "ĐẠI DIỆN BÊN A", "ĐẠI DIỆN ĐẠI LÝ", "Chờ đại lý xác nhận");
        } finally { reader.close(); }
    }
    @Test @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void simultaneousCreateWithSameRequestKeySavesOnlyOneDocument() throws Exception {
        var body = createBody();
        var pool = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
        try {
            var futures = new ArrayList<Future<MockHttpServletResponse>>();
            for (int n = 0; n < 2; n++) futures.add(pool.submit(() -> {
                start.await();
                return mvc.perform(post(finance).header("Authorization", "Bearer " + staff).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(body))).andReturn().getResponse();
            }));
            start.countDown(); int successes = 0; Long savedId = null;
            for (var future : futures) {
                var response = future.get(20, TimeUnit.SECONDS);
                assertThat(response.getStatus()).isIn(200, 409);
                if (response.getStatus() == 200) {
                    successes++; long id = data(response).path("id").asLong();
                    if (savedId != null) assertThat(id).isEqualTo(savedId); savedId = id;
                }
            }
            assertThat(successes).isGreaterThanOrEqualTo(1);
            var persisted = reconciliations.findByRequestKey(body.get("requestKey").toString()).orElseThrow();
            assertThat(persisted.getId()).isEqualTo(savedId);
            assertThat(persisted.getStatus()).isEqualTo(DebtReconciliation.Status.PENDING);
            assertThat(persisted.getVersion()).isZero();
        } finally { pool.shutdownNow(); }
    }
    @Test void rowLimitFailsWithoutSavingTruncatedDocument() throws Exception {
        for (int n = 0; n < 101; n++) order("2026-10-15T00:00:00", "1", "RECORDED");
        var body = createBody(); call("POST", finance, staff, body, 400);
        assertThat(reconciliations.findByRequestKey(body.get("requestKey").toString())).isEmpty();
    }
    @Test @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void simultaneousDealerResponsesOnlyOneCanWin() throws Exception {
        var r = create(); String path = portal + "/" + r.path("id").asLong() + "/response";
        var pool = Executors.newFixedThreadPool(4); var start = new CountDownLatch(1);
        try {
            var futures = new ArrayList<Future<Integer>>();
            for (int n = 0; n < 4; n++) futures.add(pool.submit(() -> {
                start.await();
                return mvc.perform(post(path).header("Authorization", "Bearer " + own).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"status\":\"CONFIRMED\"}")).andReturn().getResponse().getStatus();
            }));
            start.countDown(); var codes = new ArrayList<Integer>();
            for (var future : futures) codes.add(future.get(20, TimeUnit.SECONDS));
            assertThat(codes).containsExactlyInAnyOrder(200, 409, 409, 409);
            assertThat(reconciliations.findById(r.path("id").asLong()).orElseThrow().getVersion()).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }
}
