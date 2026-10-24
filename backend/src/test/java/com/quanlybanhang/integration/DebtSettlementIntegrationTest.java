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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * S7-02 Integration: Ghi nhận thanh toán và đối trừ công nợ hoá đơn.
 * Kiểm thử toàn diện:
 * - Ghi nhận thanh toán đầy đủ thông tin: số tiền, hình thức, ngày thu, người thu, ghi chú
 * - Đối trừ tự động FIFO vào hoá đơn cũ nhất trước
 * - Chọn đối trừ vào hoá đơn cụ thể
 * - Thu một phần; chuyển trạng thái hoá đơn sang "Thanh toán một phần" (PARTIALLY_PAID)
 * - Chặn thu vượt tổng công nợ còn lại
 * - Chặn xoá phiếu thu; lập bút toán điều chỉnh (REVERSAL & PARTIAL_REVERSAL)
 * - Tra cứu để biết chính xác đại lý còn nợ hoá đơn nào
 * - Ma trận phân quyền động (FINANCE_VIEW_DEBT, FINANCE_MANAGE, ROLE_ADMIN)
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:debt_settlement_integration_test;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.show-sql=false",
        "app.jwt.secret=sprint7-debt-settlement-test-secret-at-least-32-chars",
        "app.bootstrap.admin-password=DebtSettlement2026",
        "app.bootstrap.admin-email=settlement@example.invalid",
        "app.seed-demo-users=false",
        "app.seed-demo-data=false"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("S7-02 Integration: Ghi nhận thanh toán và đối trừ vào công nợ")
class DebtSettlementIntegrationTest {

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
    @Autowired private DebtPaymentRepository debtPayments;
    @Autowired private DebtPaymentAllocationRepository debtAllocations;
    @Autowired private PasswordEncoder passwords;
    @Autowired private RolePermissionConfig matrix;

    private Warehouse warehouse;
    private Product product;
    private Dealer dealer;
    private User accountantUser;
    private User viewerUser;
    private String adminToken;
    private String accountantToken;
    private String viewerToken;

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
                .name("Sản phẩm S702 " + key)
                .category(category)
                .unit("thùng")
                .price(new BigDecimal("10000"))
                .costPrice(new BigDecimal("8000"))
                .active(true)
                .build());

        prices.save(PriceList.builder()
                .code("PL-" + key)
                .name("Bảng giá S702 " + key)
                .customerGroup(CustomerGroup.DAI_LY_CAP_1)
                .active(true)
                .startDate(LocalDate.now().minusDays(10))
                .endDate(LocalDate.now().plusDays(30))
                .build());

        dealer = dealers.save(Dealer.builder()
                .code("DL-" + key)
                .name("Đại lý Hải Nam " + key)
                .customerGroup(CustomerGroup.DAI_LY_CAP_1)
                .debtLimit(new BigDecimal("50000000"))
                .maxDebtDays(30)
                .active(true)
                .warehouse(warehouse)
                .build());

        Role accountantRole = roles.findByName(ERole.ROLE_ACCOUNTANT).orElseThrow();
        accountantUser = users.save(User.builder()
                .username("acc_" + key)
                .password(passwords.encode("Password123@"))
                .fullName("Kế toán công nợ " + key)
                .email("acc_" + key + "@test.invalid")
                .active(true)
                .roles(Set.of(accountantRole))
                .build());

        Role viewerRole = roles.findByName(ERole.ROLE_VIEWER).orElseThrow();
        viewerUser = users.save(User.builder()
                .username("view_" + key)
                .password(passwords.encode("Password123@"))
                .fullName("Người xem " + key)
                .email("view_" + key + "@test.invalid")
                .active(true)
                .roles(Set.of(viewerRole))
                .build());

        matrix.updateRolePermissions(ERole.ROLE_ACCOUNTANT, Set.of(
                Permission.FINANCE_VIEW_DEBT,
                Permission.FINANCE_MANAGE,
                Permission.ORDER_VIEW
        ));

        matrix.updateRolePermissions(ERole.ROLE_VIEWER, Set.of(
                Permission.FINANCE_VIEW_DEBT
        ));

        adminToken = login("admin", "DebtSettlement2026");
        accountantToken = login(accountantUser.getUsername(), "Password123@");
        viewerToken = login(viewerUser.getUsername(), "Password123@");
    }

    private String login(String username, String password) throws Exception {
        String body = json.writeValueAsString(Map.of("username", username, "password", password));
        var res = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .characterEncoding("UTF-8")
                        .content(body))
                .andReturn().getResponse();
        assertThat(res.getStatus()).isEqualTo(200);
        JsonNode root = json.readTree(res.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        if (root.has("accessToken")) {
            return root.get("accessToken").asText();
        }
        if (root.has("data") && root.get("data").has("accessToken")) {
            return root.get("data").get("accessToken").asText();
        }
        return root.at("/data/token").asText();
    }

    private JsonNode call(MockHttpServletRequestBuilder req, String token, Object body, int expectedStatus) throws Exception {
        if (token != null) {
            req.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            req.contentType(MediaType.APPLICATION_JSON);
            req.characterEncoding("UTF-8");
            req.content(json.writeValueAsString(body));
        }
        var res = mvc.perform(req).andReturn().getResponse();
        String content = res.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(res.getStatus())
                .withFailMessage("Expected HTTP %d but got %d: %s", expectedStatus, res.getStatus(), content)
                .isEqualTo(expectedStatus);
        if (content.isBlank()) {
            return null;
        }
        return json.readTree(content);
    }

    private Order createRecordedOrder(String code, BigDecimal amount, LocalDateTime recordedAt) {
        Order order = Order.builder()
                .orderCode(code)
                .dealer(dealer)
                .warehouse(warehouse)
                .customerName(dealer.getName())
                .customerGroup(CustomerGroup.DAI_LY_CAP_1)
                .totalAmount(amount)
                .subtotalAmount(amount)
                .status("RECORDED")
                .recordedAt(recordedAt)
                .paidAmount(BigDecimal.ZERO)
                .paymentStatus("UNPAID")
                .build();
        return orders.save(order);
    }

    @Test
    @DisplayName("S7-02: Ghi nhận thu tiền tự động đối trừ FIFO vào hoá đơn cũ nhất trước")
    void testAutoAllocation_FIFO_OldestFirst() throws Exception {
        // Tạo 2 hoá đơn: Đơn 1 cũ hơn (10tr), Đơn 2 mới hơn (15tr)
        LocalDateTime now = LocalDateTime.now();
        Order ord1 = createRecordedOrder("DH-FIFO-01", new BigDecimal("10000000"), now.minusDays(10));
        Order ord2 = createRecordedOrder("DH-FIFO-02", new BigDecimal("15000000"), now.minusDays(5));

        // Thu 12.000.000 (sẽ trả hết đơn 1 10tr và trả 2tr cho đơn 2)
        Map<String, Object> paymentReq = Map.of(
                "dealerId", dealer.getId(),
                "amount", 12000000,
                "paymentDate", LocalDate.now().toString(),
                "paymentMethod", "CHUYEN_KHOAN",
                "referenceCode", "UNC-S702-01",
                "note", "Thu tiền thanh toán đợt 1",
                "recordedBy", "Kế toán Nguyễn Văn A"
        );

        JsonNode res = call(post("/api/finance/debt-payments"), accountantToken, paymentReq, 201);
        assertThat(res.at("/data/paymentCode").asText()).startsWith("PT-");
        assertThat(res.at("/data/amount").asDouble()).isEqualTo(12000000.0);
        assertThat(res.at("/data/recordedBy").asText()).isEqualTo("Kế toán Nguyễn Văn A");
        assertThat(res.at("/data/remainingDebt").asDouble()).isEqualTo(13000000.0);

        // Kiểm tra phân bổ đối trừ trong response
        JsonNode allocs = res.at("/data/allocations");
        assertThat(allocs.isArray()).isTrue();
        assertThat(allocs.size()).isEqualTo(2);

        // Dòng 1: trả hết đơn cũ nhất DH-FIFO-01
        assertThat(allocs.get(0).at("/orderCode").asText()).isEqualTo("DH-FIFO-01");
        assertThat(allocs.get(0).at("/allocatedAmount").asDouble()).isEqualTo(10000000.0);
        assertThat(allocs.get(0).at("/orderPaymentStatus").asText()).isEqualTo("PAID");
        assertThat(allocs.get(0).at("/orderPaymentStatusDisplayName").asText()).isEqualTo("Đã thanh toán");

        // Dòng 2: trả 2tr cho đơn mới hơn DH-FIFO-02 -> chuyển sang Thanh toán một phần
        assertThat(allocs.get(1).at("/orderCode").asText()).isEqualTo("DH-FIFO-02");
        assertThat(allocs.get(1).at("/allocatedAmount").asDouble()).isEqualTo(2000000.0);
        assertThat(allocs.get(1).at("/orderRemainingAmount").asDouble()).isEqualTo(13000000.0);
        assertThat(allocs.get(1).at("/orderPaymentStatus").asText()).isEqualTo("PARTIALLY_PAID");
        assertThat(allocs.get(1).at("/orderPaymentStatusDisplayName").asText()).isEqualTo("Thanh toán một phần");

        // Kiểm tra trong DB: Đơn 1 đã thanh toán đủ, Đơn 2 thanh toán một phần
        Order dbOrd1 = orders.findById(ord1.getId()).orElseThrow();
        assertThat(dbOrd1.getPaidAmount()).isEqualByComparingTo(new BigDecimal("10000000"));
        assertThat(dbOrd1.getPaymentStatus()).isEqualTo("PAID");

        Order dbOrd2 = orders.findById(ord2.getId()).orElseThrow();
        assertThat(dbOrd2.getPaidAmount()).isEqualByComparingTo(new BigDecimal("2000000"));
        assertThat(dbOrd2.getPaymentStatus()).isEqualTo("PARTIALLY_PAID");
    }

    @Test
    @DisplayName("S7-02: Chọn đối trừ vào hoá đơn cụ thể và cho phép thu một phần")
    void testSpecificInvoiceAllocation_PartialPayment() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        Order ord1 = createRecordedOrder("DH-SPEC-01", new BigDecimal("8000000"), now.minusDays(7));
        Order ord2 = createRecordedOrder("DH-SPEC-02", new BigDecimal("12000000"), now.minusDays(3));

        // Kế toán chọn chỉ đối trừ vào đơn DH-SPEC-02 với số tiền 5.000.000 (thu một phần)
        Map<String, Object> paymentReq = Map.of(
                "dealerId", dealer.getId(),
                "amount", 5000000,
                "paymentDate", LocalDate.now().toString(),
                "paymentMethod", "TIEN_MAT",
                "note", "Thu tiền mặt cho đơn 2",
                "recordedBy", "Kế toán Trần Thị B",
                "autoAllocate", false,
                "allocations", List.of(
                        Map.of("orderId", ord2.getId(), "amount", 5000000, "note", "Đối trừ đơn SPEC-02")
                )
        );

        JsonNode res = call(post("/api/finance/debt-payments"), accountantToken, paymentReq, 201);
        assertThat(res.at("/data/amount").asDouble()).isEqualTo(5000000.0);
        assertThat(res.at("/data/allocations/0/orderCode").asText()).isEqualTo("DH-SPEC-02");
        assertThat(res.at("/data/allocations/0/allocatedAmount").asDouble()).isEqualTo(5000000.0);
        assertThat(res.at("/data/allocations/0/orderPaymentStatus").asText()).isEqualTo("PARTIALLY_PAID");
        assertThat(res.at("/data/allocations/0/orderPaymentStatusDisplayName").asText()).isEqualTo("Thanh toán một phần");

        // Đơn 1 chưa bị đối trừ (vẫn UNPAID)
        Order dbOrd1 = orders.findById(ord1.getId()).orElseThrow();
        assertThat(dbOrd1.getPaidAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(dbOrd1.getPaymentStatus()).isEqualTo("UNPAID");

        // Đơn 2 đã trả 5tr, còn nợ 7tr, trạng thái PARTIALLY_PAID
        Order dbOrd2 = orders.findById(ord2.getId()).orElseThrow();
        assertThat(dbOrd2.getPaidAmount()).isEqualByComparingTo(new BigDecimal("5000000"));
        assertThat(dbOrd2.getPaymentStatus()).isEqualTo("PARTIALLY_PAID");
    }

    @Test
    @DisplayName("S7-02: Không cho thu vượt tổng công nợ còn lại của đại lý -> 400 Bad Request")
    void testCannotExceedTotalRemainingDebt() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        createRecordedOrder("DH-LIMIT-01", new BigDecimal("7000000"), now.minusDays(5));

        // Tổng công nợ là 7.000.000, kế toán cố tình thu 8.000.000
        Map<String, Object> paymentReq = Map.of(
                "dealerId", dealer.getId(),
                "amount", 8000000,
                "paymentMethod", "TIEN_MAT",
                "note", "Thu vượt công nợ"
        );

        JsonNode errRes = call(post("/api/finance/debt-payments"), accountantToken, paymentReq, 400);
        assertThat(errRes.at("/message").asText()).contains("không được vượt quá tổng công nợ còn lại");
    }

    @Test
    @DisplayName("S7-02: Không cho đối trừ vượt số nợ còn lại của hoá đơn cụ thể -> 400 Bad Request")
    void testCannotExceedOrderRemainingDebtInManualAllocation() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        Order ord1 = createRecordedOrder("DH-OVERALLOC-01", new BigDecimal("5000000"), now.minusDays(5));
        Order ord2 = createRecordedOrder("DH-OVERALLOC-02", new BigDecimal("10000000"), now.minusDays(2));

        // Đơn 1 chỉ có 5.000.000 nợ, nhưng cố tình đối trừ 6.000.000 vào đơn 1
        Map<String, Object> paymentReq = Map.of(
                "dealerId", dealer.getId(),
                "amount", 6000000,
                "autoAllocate", false,
                "allocations", List.of(
                        Map.of("orderId", ord1.getId(), "amount", 6000000)
                )
        );

        JsonNode errRes = call(post("/api/finance/debt-payments"), accountantToken, paymentReq, 400);
        assertThat(errRes.at("/message").asText()).contains("vượt quá số nợ còn lại của hoá đơn");
    }

    @Test
    @DisplayName("S7-02: Đã ghi nhận thì không xoá (DELETE bị chặn 400) -> Chỉ lập bút toán điều chỉnh")
    void testDeleteForbidden_AdjustmentEntryAllowed() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        Order ord = createRecordedOrder("DH-ADJ-01", new BigDecimal("10000000"), now.minusDays(3));

        // 1. Ghi nhận phiếu thu 6.000.000
        Map<String, Object> paymentReq = Map.of(
                "dealerId", dealer.getId(),
                "amount", 6000000,
                "paymentMethod", "CHUYEN_KHOAN",
                "note", "Thu tiền cần điều chỉnh"
        );
        JsonNode payRes = call(post("/api/finance/debt-payments"), accountantToken, paymentReq, 201);
        long paymentId = payRes.at("/data/id").asLong();
        String paymentCode = payRes.at("/data/paymentCode").asText();

        Order ordAfterPay = orders.findById(ord.getId()).orElseThrow();
        assertThat(ordAfterPay.getPaidAmount()).isEqualByComparingTo(new BigDecimal("6000000"));
        assertThat(ordAfterPay.getPaymentStatus()).isEqualTo("PARTIALLY_PAID");

        // 2. Thử xoá phiếu thu bằng DELETE -> BỊ CHẶN 400 Bad Request
        JsonNode delErr = call(delete("/api/finance/debt-payments/" + paymentId), accountantToken, null, 400);
        assertThat(delErr.at("/message").asText()).contains("Đã ghi nhận thanh toán thì không được xoá, chỉ lập bút toán điều chỉnh");

        // 3. Lập bút toán điều chỉnh hoàn trả toàn bộ (REVERSAL) do nhập nhầm
        Map<String, Object> adjReq = Map.of(
                "reason", "Kế toán nhập nhầm đại lý, lập bút toán điều chỉnh hoàn trả",
                "adjustmentType", "REVERSAL",
                "recordedBy", "Kế toán trưởng"
        );
        JsonNode adjRes = call(post("/api/finance/debt-payments/" + paymentId + "/adjust"), accountantToken, adjReq, 201);
        assertThat(adjRes.at("/data/paymentCode").asText()).startsWith("BTDC-");
        assertThat(adjRes.at("/data/paymentType").asText()).isEqualTo("ADJUSTMENT");
        assertThat(adjRes.at("/data/amount").asDouble()).isEqualTo(-6000000.0);
        assertThat(adjRes.at("/data/originalPaymentCode").asText()).isEqualTo(paymentCode);

        // 4. Kiểm tra hoá đơn: số tiền đã trả được hoàn lại về 0, trạng thái trở lại UNPAID
        Order ordAfterAdj = orders.findById(ord.getId()).orElseThrow();
        assertThat(ordAfterAdj.getPaidAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(ordAfterAdj.getPaymentStatus()).isEqualTo("UNPAID");

        // Công nợ đại lý được khôi phục nguyên vẹn 10.000.000
        assertThat(adjRes.at("/data/remainingDebt").asDouble()).isEqualTo(10000000.0);
    }

    @Test
    @DisplayName("S7-02: Bút toán điều chỉnh giảm một phần (PARTIAL_REVERSAL)")
    void testPartialAdjustmentEntry() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        Order ord = createRecordedOrder("DH-PART-ADJ-01", new BigDecimal("10000000"), now.minusDays(4));

        // Ghi nhận thu 6.000.000
        Map<String, Object> paymentReq = Map.of(
                "dealerId", dealer.getId(),
                "amount", 6000000,
                "paymentMethod", "CHUYEN_KHOAN"
        );
        JsonNode payRes = call(post("/api/finance/debt-payments"), accountantToken, paymentReq, 201);
        long paymentId = payRes.at("/data/id").asLong();

        // Lập bút toán điều chỉnh giảm 2.000.000 (số tiền thực thu là 4.000.000)
        Map<String, Object> adjReq = Map.of(
                "reason", "Khách hàng chuyển thừa 2.000.000, điều chỉnh giảm",
                "adjustmentType", "PARTIAL_REVERSAL",
                "adjustmentAmount", 2000000
        );
        JsonNode adjRes = call(post("/api/finance/debt-payments/" + paymentId + "/adjust"), accountantToken, adjReq, 201);
        assertThat(adjRes.at("/data/amount").asDouble()).isEqualTo(-2000000.0);

        // Hoá đơn còn lại 4.000.000 đã trả, nợ 6.000.000, trạng thái PARTIALLY_PAID
        Order dbOrd = orders.findById(ord.getId()).orElseThrow();
        assertThat(dbOrd.getPaidAmount()).isEqualByComparingTo(new BigDecimal("4000000"));
        assertThat(dbOrd.getPaymentStatus()).isEqualTo("PARTIALLY_PAID");
    }

    @Test
    @DisplayName("S7-02: Tra cứu danh sách hoá đơn và hoá đơn còn nợ của đại lý")
    void testGetDealerInvoicesAndUnpaidList() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        Order ord1 = createRecordedOrder("DH-TRA-CUU-01", new BigDecimal("5000000"), now.minusDays(10));
        Order ord2 = createRecordedOrder("DH-TRA-CUU-02", new BigDecimal("8000000"), now.minusDays(5));

        // Trả hết đơn 1
        Map<String, Object> payReq = Map.of(
                "dealerId", dealer.getId(),
                "amount", 5000000
        );
        call(post("/api/finance/debt-payments"), accountantToken, payReq, 201);

        // 1. Tra cứu toàn bộ hoá đơn
        JsonNode allInvoices = call(get("/api/finance/dealers/" + dealer.getId() + "/invoices"), accountantToken, null, 200);
        assertThat(allInvoices.at("/data").isArray()).isTrue();
        assertThat(allInvoices.at("/data").size()).isEqualTo(2);

        // 2. Tra cứu danh sách hoá đơn CÒN NỢ (unpaid) -> chỉ có đơn 2
        JsonNode unpaidInvoices = call(get("/api/finance/dealers/" + dealer.getId() + "/unpaid-invoices"), accountantToken, null, 200);
        assertThat(unpaidInvoices.at("/data").isArray()).isTrue();
        assertThat(unpaidInvoices.at("/data").size()).isEqualTo(1);
        assertThat(unpaidInvoices.at("/data/0/orderCode").asText()).isEqualTo("DH-TRA-CUU-02");
        assertThat(unpaidInvoices.at("/data/0/remainingAmount").asDouble()).isEqualTo(8000000.0);
        assertThat(unpaidInvoices.at("/data/0/paymentStatusDisplayName").asText()).isEqualTo("Chưa thanh toán");

        // 3. Tra cứu lịch sử đối trừ của đơn 1
        JsonNode ord1Settlements = call(get("/api/finance/orders/" + ord1.getId() + "/settlements"), accountantToken, null, 200);
        assertThat(ord1Settlements.at("/data").isArray()).isTrue();
        assertThat(ord1Settlements.at("/data/0/allocatedAmount").asDouble()).isEqualTo(5000000.0);
    }

    @Test
    @DisplayName("S7-02: Xem trước đối trừ tự động theo nguyên tắc hoá đơn cũ nhất trước (preview-allocation)")
    void testPreviewPaymentAllocation() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        createRecordedOrder("DH-PREVIEW-01", new BigDecimal("6000000"), now.minusDays(15));
        createRecordedOrder("DH-PREVIEW-02", new BigDecimal("10000000"), now.minusDays(5));

        // Xem trước đối trừ 8.000.000
        JsonNode preview = call(post("/api/finance/debt-payments/preview-allocation?dealerId=" + dealer.getId() + "&amount=8000000"),
                accountantToken, null, 200);

        assertThat(preview.at("/data/paymentAmount").asDouble()).isEqualTo(8000000.0);
        assertThat(preview.at("/data/totalAllocated").asDouble()).isEqualTo(8000000.0);
        assertThat(preview.at("/data/allocations/0/orderCode").asText()).isEqualTo("DH-PREVIEW-01");
        assertThat(preview.at("/data/allocations/0/allocatedAmount").asDouble()).isEqualTo(6000000.0);
        assertThat(preview.at("/data/allocations/0/orderPaymentStatus").asText()).isEqualTo("PAID");

        assertThat(preview.at("/data/allocations/1/orderCode").asText()).isEqualTo("DH-PREVIEW-02");
        assertThat(preview.at("/data/allocations/1/allocatedAmount").asDouble()).isEqualTo(2000000.0);
        assertThat(preview.at("/data/allocations/1/orderPaymentStatus").asText()).isEqualTo("PARTIALLY_PAID");
    }

    @Test
    @DisplayName("S7-02: Thực thi ma trận phân quyền động (FINANCE_MANAGE, FINANCE_VIEW_DEBT, ROLE_ADMIN)")
    void testDynamicPermissionMatrix_S702() throws Exception {
        createRecordedOrder("DH-PERM-01", new BigDecimal("5000000"), LocalDateTime.now());

        Map<String, Object> paymentReq = Map.of(
                "dealerId", dealer.getId(),
                "amount", 2000000
        );

        // 1. Viewer chỉ có FINANCE_VIEW_DEBT -> Xem được hoá đơn/thu tiền, bị 403 khi ghi thu tiền
        call(get("/api/finance/dealers/" + dealer.getId() + "/invoices"), viewerToken, null, 200);
        call(get("/api/finance/debt-payments"), viewerToken, null, 200);
        call(post("/api/finance/debt-payments"), viewerToken, paymentReq, 403);

        // 2. Thu hồi FINANCE_MANAGE khỏi ROLE_ACCOUNTANT -> Lập tức 403 khi ghi thu tiền trên cùng token
        matrix.updateRolePermissions(ERole.ROLE_ACCOUNTANT, Set.of(Permission.FINANCE_VIEW_DEBT));
        call(post("/api/finance/debt-payments"), accountantToken, paymentReq, 403);

        // 3. Cấp lại FINANCE_MANAGE cho ROLE_ACCOUNTANT -> Thành công 201 trên cùng token
        matrix.updateRolePermissions(ERole.ROLE_ACCOUNTANT, Set.of(
                Permission.FINANCE_VIEW_DEBT,
                Permission.FINANCE_MANAGE
        ));
        JsonNode payRes = call(post("/api/finance/debt-payments"), accountantToken, paymentReq, 201);
        long paymentId = payRes.at("/data/id").asLong();

        // 4. Thu hồi cả FINANCE_VIEW_DEBT khỏi ROLE_ACCOUNTANT -> Đọc hoá đơn bị 403
        matrix.updateRolePermissions(ERole.ROLE_ACCOUNTANT, Set.of(Permission.FINANCE_MANAGE));
        call(get("/api/finance/dealers/" + dealer.getId() + "/invoices"), accountantToken, null, 403);
        call(get("/api/finance/dealers/" + dealer.getId() + "/unpaid-invoices"), accountantToken, null, 403);

        // 5. ROLE_ADMIN luôn có toàn quyền mọi lúc
        call(get("/api/finance/dealers/" + dealer.getId() + "/invoices"), adminToken, null, 200);
        call(get("/api/finance/debt-payments/" + paymentId), adminToken, null, 200);
    }
}
