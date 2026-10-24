package com.quanlybanhang.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanlybanhang.config.RolePermissionConfig;
import com.quanlybanhang.model.*;
import com.quanlybanhang.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * S7-04 Integration: Hệ thống tự cảnh báo và khoá bán công nợ.
 * Kiểm thử tích hợp toàn diện HTTP/JWT/JPA/H2/Ma trận quyền:
 * - Đánh giá tự động vượt hạn mức và nợ quá hạn
 * - Chặn (BLOCK) hoặc chuyển cần duyệt (REQUIRE_APPROVAL) khi tạo đơn hàng mới
 * - Ghi nhận thu tiền tự động kích hoạt đánh giá lại và gỡ cờ
 * - Thực thi ma trận phân quyền động (FINANCE_VIEW_DEBT, FINANCE_MANAGE)
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:debt_risk_integration_test;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.show-sql=false",
        "app.jwt.secret=sprint7-debt-risk-test-secret-at-least-32-characters",
        "app.bootstrap.admin-password=DebtRiskTest2026",
        "app.bootstrap.admin-email=debtrisk@example.invalid",
        "app.seed-demo-users=false",
        "app.seed-demo-data=false"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("S7-04 Integration: Hệ thống tự cảnh báo và khoá bán công nợ")
class DebtRiskIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private UserRepository users;
    @Autowired private RoleRepository roles;
    @Autowired private WarehouseRepository warehouses;
    @Autowired private ProductRepository products;
    @Autowired private CategoryRepository categories;
    @Autowired private DealerRepository dealers;
    @Autowired private PriceListRepository prices;
    @Autowired private OrderRepository orders;
    @Autowired private DebtRiskConfigRepository debtRiskConfigs;
    @Autowired private DebtPaymentRepository debtPayments;
    @Autowired private PasswordEncoder passwords;
    @Autowired private RolePermissionConfig matrix;

    private Warehouse warehouse;
    private Product product;
    private Dealer dealer;
    private User salesUser;
    private User accountantUser;
    private String adminToken;
    private String salesToken;
    private String accountantToken;

    @BeforeEach
    void setUp() throws Exception {
        String key = UUID.randomUUID().toString().substring(0, 8);

        warehouse = warehouses.save(Warehouse.builder()
                .code("WH-" + key)
                .name("Kho trung tâm " + key)
                .active(true)
                .build());

        Category category = categories.save(Category.builder()
                .code("CAT-" + key)
                .name("Danh mục " + key)
                .build());

        product = products.save(Product.builder()
                .code("SKU-" + key)
                .name("Sản phẩm mẫu " + key)
                .category(category)
                .unit("thùng")
                .price(new BigDecimal("500000"))
                .costPrice(new BigDecimal("400000"))
                .active(true)
                .build());

        // Bảng giá hiệu lực
        PriceList priceList = PriceList.builder()
                .code("BG-" + key)
                .name("Bảng giá cấp 1 " + key)
                .customerGroup(CustomerGroup.DAI_LY_CAP_1)
                .startDate(LocalDate.now().minusDays(2))
                .endDate(LocalDate.now().plusDays(30))
                .active(true)
                .build();
        priceList.addItem(PriceListItem.builder()
                .product(product)
                .sellingPrice(new BigDecimal("500000"))
                .floorPrice(new BigDecimal("450000"))
                .build());
        prices.save(priceList);

        // Tạo user Sales
        salesUser = users.save(User.builder()
                .username("sales-" + key)
                .email("sales-" + key + "@example.invalid")
                .fullName("Sales " + key)
                .password(passwords.encode("DebtRiskTest2026"))
                .roles(Set.of(roles.findByName(ERole.ROLE_SALES).orElseThrow()))
                .warehouses(Set.of(warehouse))
                .active(true)
                .build());

        // Tạo user Kế toán
        accountantUser = users.save(User.builder()
                .username("acc-" + key)
                .email("acc-" + key + "@example.invalid")
                .fullName("Accountant " + key)
                .password(passwords.encode("DebtRiskTest2026"))
                .roles(Set.of(roles.findByName(ERole.ROLE_ACCOUNTANT).orElseThrow()))
                .warehouses(Set.of(warehouse))
                .active(true)
                .build());

        // Tạo Đại lý
        dealer = dealers.save(Dealer.builder()
                .code("DL-" + key)
                .name("Đại lý Thắng Lợi " + key)
                .customerGroup(CustomerGroup.DAI_LY_CAP_1)
                .assignedUser(salesUser)
                .warehouse(warehouse)
                .debtLimit(new BigDecimal("5000000")) // Hạn mức 5 triệu
                .maxDebtDays(30)                      // Cho phép nợ tối đa 30 ngày
                .debtActionConfig(DebtFlagAction.DEFAULT)
                .debtFlagged(false)
                .debtFlagType(DebtFlagType.NONE)
                .active(true)
                .locked(false)
                .build());

        // Phân quyền cho ROLE_SALES để có thể tạo đơn
        matrix.updateRolePermissions(ERole.ROLE_SALES, Set.of(
                Permission.ORDER_CREATE,
                Permission.ORDER_VIEW,
                Permission.CUSTOMER_VIEW,
                Permission.PRODUCT_VIEW
        ));

        // Phân quyền ban đầu cho ROLE_ACCOUNTANT
        matrix.updateRolePermissions(ERole.ROLE_ACCOUNTANT, Set.of(
                Permission.FINANCE_VIEW_DEBT,
                Permission.FINANCE_MANAGE
        ));

        adminToken = login("admin");
        salesToken = login(salesUser.getUsername());
        accountantToken = login(accountantUser.getUsername());
    }

    @Test
    @DisplayName("Luồng S7-04 đầy đủ: Tự gắn cờ vượt hạn mức -> Khóa bán (BLOCK) -> Đổi sang Cần duyệt -> Thu tiền gỡ cờ")
    void testDebtLimitExceeded_AutoFlag_BlockOrder_AndUnflagAfterPayment() throws Exception {
        // 1. Cấu hình kiểm soát rủi ro toàn hệ thống: vượt hạn mức -> BLOCK, quá hạn -> REQUIRE_APPROVAL
        Map<String, Object> configReq = Map.of(
                "defaultAction", "BLOCK",
                "limitExceededAction", "BLOCK",
                "overdueAction", "REQUIRE_APPROVAL",
                "autoFlagEnabled", true
        );
        call(put("/api/finance/debt/config"), adminToken, configReq, 200);

        // 2. Tạo một đơn hàng cũ chưa thanh toán vượt hạn mức 5.000.000: Đơn trị giá 7.000.000
        Order oldOrder = Order.builder()
                .orderCode("DH-OLD-" + UUID.randomUUID().toString().substring(0, 8))
                .dealer(dealer)
                .customerName(dealer.getName())
                .customerGroup(dealer.getCustomerGroup())
                .warehouse(warehouse)
                .status("DELIVERED")
                .subtotalAmount(new BigDecimal("7000000"))
                .discountAmount(BigDecimal.ZERO)
                .totalAmount(new BigDecimal("7000000"))
                .createdBy(salesUser.getUsername())
                .createdAt(LocalDateTime.now().minusDays(5))
                .recordedAt(LocalDateTime.now().minusDays(5))
                .build();
        orders.save(oldOrder);

        // 3. Chạy tác vụ đánh giá công nợ thủ công qua API
        JsonNode evalRes = call(post("/api/finance/debt/evaluate"), adminToken, null, 200);
        assertThat(evalRes.at("/data/totalEvaluated").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(evalRes.at("/data/totalFlagged").asInt()).isGreaterThanOrEqualTo(1);

        // Kiểm tra danh sách đại lý bị gắn cờ
        JsonNode flaggedRes = call(get("/api/finance/debt/flagged-dealers"), adminToken, null, 200);
        assertThat(flaggedRes.at("/data").isArray()).isTrue();
        boolean foundDealer = false;
        for (JsonNode item : flaggedRes.at("/data")) {
            if (item.at("/dealerId").asLong() == dealer.getId()) {
                foundDealer = true;
                assertThat(item.at("/debtFlagType").asText()).isEqualTo("DEBT_LIMIT_EXCEEDED");
                assertThat(item.at("/effectiveAction").asText()).isEqualTo("BLOCK");
                break;
            }
        }
        assertThat(foundDealer).isTrue();

        // 4. Sales thử tạo đơn hàng mới cho đại lý đã bị gắn cờ BLOCK -> Bị chặn với lỗi 400 Bad Request
        Map<String, Object> orderReq = Map.of(
                "dealerId", dealer.getId(),
                "warehouseId", warehouse.getId(),
                "items", List.of(
                        Map.of(
                                "productId", product.getId(),
                                "unit", "thùng",
                                "quantity", 2,
                                "price", 500000
                        )
                )
        );
        JsonNode blockErr = call(post("/api/orders"), salesToken, orderReq, 400);
        assertThat(blockErr.at("/message").asText()).contains("chặn");

        // 5. Quản lý tài chính / Kế toán cập nhật cấu hình riêng cho đại lý thành REQUIRE_APPROVAL
        Map<String, Object> actionReq = Map.of("action", "REQUIRE_APPROVAL");
        JsonNode updateActionRes = call(put("/api/finance/debt/" + dealer.getId() + "/action-config"), adminToken, actionReq, 200);
        assertThat(updateActionRes.at("/data/debtActionConfig").asText()).isEqualTo("REQUIRE_APPROVAL");

        // 6. Sales tạo lại đơn hàng -> Đơn được tạo thành công nhưng chuyển trạng thái PENDING_APPROVAL
        JsonNode orderCreatedRes = call(post("/api/orders"), salesToken, orderReq, 201);
        assertThat(orderCreatedRes.at("/data/status").asText()).isEqualTo("PENDING_APPROVAL");
        assertThat(orderCreatedRes.at("/data/warning").asText()).contains("Đại lý bị gắn cờ rủi ro công nợ");
        assertThat(orderCreatedRes.at("/data/approvalReasons/0").asText()).isEqualTo("OVER_DEBT_LIMIT");

        // 7. Ghi nhận thu tiền 4.000.000 (công nợ giảm từ 7.000.000 xuống 3.000.000, dưới hạn mức 5.000.000)
        Map<String, Object> paymentReq = Map.of(
                "dealerId", dealer.getId(),
                "amount", 4000000,
                "paymentMethod", "BANK_TRANSFER",
                "referenceCode", "UNC-9999",
                "note", "Thu tiền chuyển khoản thanh toán đợt 1"
        );
        JsonNode paymentRes = call(post("/api/finance/debt-payments"), accountantToken, paymentReq, 201);
        assertThat(paymentRes.at("/data/paymentCode").asText()).startsWith("PT-");
        assertThat(paymentRes.at("/data/amount").asDouble()).isEqualTo(4000000.0);

        // 8. Tác vụ tự động chạy lại sau khi thu tiền: kiểm tra đại lý đã được GỠ CỜ (debtFlagged = false)
        Dealer updatedDealer = dealers.findById(dealer.getId()).orElseThrow();
        assertThat(updatedDealer.getDebtFlagged()).isFalse();
        assertThat(updatedDealer.getDebtFlagType()).isEqualTo(DebtFlagType.NONE);

        // Kiểm tra danh sách đại lý gắn cờ không còn đại lý này nữa
        JsonNode flaggedAfterPayment = call(get("/api/finance/debt/flagged-dealers"), adminToken, null, 200);
        for (JsonNode item : flaggedAfterPayment.at("/data")) {
            assertThat(item.at("/dealerId").asLong()).isNotEqualTo(dealer.getId());
        }

        // Lịch sử thu tiền tra cứu được
        JsonNode paymentListRes = call(get("/api/finance/debt-payments?dealerId=" + dealer.getId()), accountantToken, null, 200);
        assertThat(paymentListRes.at("/data/content").isArray()).isTrue();
        assertThat(paymentListRes.at("/data/content/0/amount").asDouble()).isEqualTo(4000000.0);
    }

    @Test
    @DisplayName("Luồng S7-04 Nợ quá hạn: Tự gắn cờ OVERDUE -> Ghi nhận thu tiền hết nợ quá hạn -> Tự động gỡ cờ")
    void testOverdueDebt_AutoFlag_AndClearAfterPayment() throws Exception {
        // 1. Cấu hình hệ thống: overdueAction = REQUIRE_APPROVAL
        Map<String, Object> configReq = Map.of(
                "defaultAction", "REQUIRE_APPROVAL",
                "limitExceededAction", "BLOCK",
                "overdueAction", "REQUIRE_APPROVAL",
                "autoFlagEnabled", true
        );
        call(put("/api/finance/debt/config"), adminToken, configReq, 200);

        // 2. Tạo đơn hàng 1.000.000 (dưới hạn mức 5 triệu) nhưng giao từ 40 ngày trước (quá hạn 30 ngày)
        Order overdueOrder = Order.builder()
                .orderCode("DH-OVERDUE-" + UUID.randomUUID().toString().substring(0, 8))
                .dealer(dealer)
                .customerName(dealer.getName())
                .customerGroup(dealer.getCustomerGroup())
                .warehouse(warehouse)
                .status("DELIVERED")
                .subtotalAmount(new BigDecimal("1000000"))
                .discountAmount(BigDecimal.ZERO)
                .totalAmount(new BigDecimal("1000000"))
                .createdBy(salesUser.getUsername())
                .createdAt(LocalDateTime.now().minusDays(40))
                .recordedAt(LocalDateTime.now().minusDays(40))
                .build();
        orders.save(overdueOrder);

        // 3. Đánh giá đại lý cụ thể
        call(post("/api/finance/debt/" + dealer.getId() + "/evaluate"), adminToken, null, 200);

        Dealer flaggedDealer = dealers.findById(dealer.getId()).orElseThrow();
        assertThat(flaggedDealer.getDebtFlagged()).isTrue();
        assertThat(flaggedDealer.getDebtFlagType()).isEqualTo(DebtFlagType.OVERDUE);
        assertThat(flaggedDealer.getDebtFlagReason()).contains("nợ quá hạn");

        // 4. Thu đủ 1.000.000 để xoá nợ quá hạn
        Map<String, Object> paymentReq = Map.of(
                "dealerId", dealer.getId(),
                "amount", 1000000,
                "paymentMethod", "CASH",
                "note", "Thu tiền mặt thanh toán hết nợ quá hạn"
        );
        call(post("/api/finance/debt-payments"), accountantToken, paymentReq, 201);

        Dealer clearedDealer = dealers.findById(dealer.getId()).orElseThrow();
        assertThat(clearedDealer.getDebtFlagged()).isFalse();
        assertThat(clearedDealer.getDebtFlagType()).isEqualTo(DebtFlagType.NONE);
    }

    @Test
    @DisplayName("Ma trận phân quyền động: Thu hồi quyền tức thì trả về 403, ADMIN luôn được phép")
    void testDynamicPermissionMatrixEnforcement() throws Exception {
        // 1. Chỉ cấp FINANCE_VIEW_DEBT cho ROLE_ACCOUNTANT
        matrix.updateRolePermissions(ERole.ROLE_ACCOUNTANT, Set.of(Permission.FINANCE_VIEW_DEBT));

        // GET thành công (200)
        call(get("/api/finance/debt/config"), accountantToken, null, 200);
        call(get("/api/finance/debt/flagged-dealers"), accountantToken, null, 200);
        call(get("/api/finance/debt-payments"), accountantToken, null, 200);

        // Các thao tác ghi cần FINANCE_MANAGE -> Trả về 403 Forbidden
        Map<String, Object> configReq = Map.of(
                "defaultAction", "BLOCK",
                "limitExceededAction", "BLOCK",
                "overdueAction", "REQUIRE_APPROVAL",
                "autoFlagEnabled", true
        );
        call(put("/api/finance/debt/config"), accountantToken, configReq, 403);

        Map<String, Object> paymentReq = Map.of(
                "dealerId", dealer.getId(),
                "amount", 500000,
                "paymentMethod", "CASH"
        );
        call(post("/api/finance/debt-payments"), accountantToken, paymentReq, 403);
        call(post("/api/finance/debt/evaluate"), accountantToken, null, 403);

        // 2. Cấp thêm quyền FINANCE_MANAGE cho ROLE_ACCOUNTANT
        matrix.updateRolePermissions(ERole.ROLE_ACCOUNTANT, Set.of(
                Permission.FINANCE_VIEW_DEBT,
                Permission.FINANCE_MANAGE
        ));

        // Cùng token đó giờ thao tác ghi thành công
        call(put("/api/finance/debt/config"), accountantToken, configReq, 200);
        call(post("/api/finance/debt-payments"), accountantToken, paymentReq, 201);
        call(post("/api/finance/debt/evaluate"), accountantToken, null, 200);

        // 3. Thu hồi TOÀN BỘ quyền khỏi ROLE_ACCOUNTANT
        matrix.updateRolePermissions(ERole.ROLE_ACCOUNTANT, Set.of());

        // Lập tức trên cùng một token, mọi endpoint đều trả về 403 Forbidden
        call(get("/api/finance/debt/config"), accountantToken, null, 403);
        call(get("/api/finance/debt/flagged-dealers"), accountantToken, null, 403);
        call(get("/api/finance/debt-payments"), accountantToken, null, 403);
        call(post("/api/finance/debt-payments"), accountantToken, paymentReq, 403);

        // 4. ROLE_ADMIN luôn có toàn quyền bất kể ma trận
        call(get("/api/finance/debt/config"), adminToken, null, 200);
        call(get("/api/finance/debt/flagged-dealers"), adminToken, null, 200);
        call(post("/api/finance/debt/evaluate"), adminToken, null, 200);
    }

    @Test
    void readingOrdersDebtAndMissingConfigDoesNotCreateConfigInsideReadOnlyTransactions() throws Exception {
        debtRiskConfigs.deleteAll();
        call(get("/api/finance/debt/config"), adminToken, null, 200);
        call(get("/api/dealers/" + dealer.getId() + "/debt"), adminToken, null, 200);
        orders.save(Order.builder().orderCode("READONLY-" + UUID.randomUUID()).dealer(dealer).warehouse(warehouse)
                .status("PENDING_APPROVAL").customerName("Read-only fixture").totalAmount(BigDecimal.TEN).build());
        call(get("/api/orders"), adminToken, null, 200);
        assertThat(debtRiskConfigs.count()).isZero();
        call(put("/api/finance/debt/config"), adminToken, Map.of("defaultAction", "REQUIRE_APPROVAL"), 200);
        assertThat(debtRiskConfigs.count()).isEqualTo(1);
        assertThat(call(get("/api/finance/debt/config"), adminToken, null, 200).get("data").get("defaultAction").asText())
                .isEqualTo("REQUIRE_APPROVAL");
    }

    private String login(String username) throws Exception {
        return call(post("/api/auth/login"), null, Map.of("username", username, "password", "DebtRiskTest2026"), 200)
                .get("accessToken").asText();
    }

    private JsonNode call(MockHttpServletRequestBuilder request, String token, Object body, int expected) throws Exception {
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        var response = mvc.perform(request).andReturn().getResponse();
        String payload = response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(response.getStatus()).as("%s", payload).isEqualTo(expected);
        if (payload != null && !payload.isBlank()) {
            return json.readTree(payload);
        }
        return null;
    }
}
