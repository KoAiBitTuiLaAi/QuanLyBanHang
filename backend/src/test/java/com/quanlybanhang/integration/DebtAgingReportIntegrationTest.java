package com.quanlybanhang.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanlybanhang.config.RolePermissionConfig;
import com.quanlybanhang.model.*;
import com.quanlybanhang.repository.*;
import com.quanlybanhang.security.JwtUtils;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:debt_aging_integration_test;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.show-sql=false",
        "app.jwt.secret=debt-aging-test-secret-at-least-32-characters",
        "app.bootstrap.admin-password=AgingAdmin2026!",
        "app.bootstrap.admin-email=agingadmin@example.invalid",
        "app.seed-demo-users=false",
        "app.seed-demo-data=false"
})
@AutoConfigureMockMvc
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("Integration test: Báo cáo công nợ theo tuổi nợ & Ma trận phân quyền (S8-09)")
class DebtAgingReportIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private DealerRepository dealerRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private InvoiceRepository invoiceRepository;
    @Autowired private OrderRepository orderRepository;
    @Autowired private DebtPaymentRepository paymentRepository;
    @Autowired private RolePermissionConfig permissionMatrix;
    @Autowired private JwtUtils jwtUtils;

    private User accountantUser;
    private User dealerUser;
    private Dealer dealer1;
    private Dealer dealer2;
    private String accountantToken;
    private String adminToken;
    private String dealerToken;

    @BeforeEach
    void setUp() {
        // Cấu hình ma trận phân quyền: ROLE_ACCOUNTANT có REPORT_FINANCE
        permissionMatrix.updateRolePermissions(ERole.ROLE_ACCOUNTANT, Set.of(Permission.REPORT_FINANCE, Permission.FINANCE_VIEW_DEBT));

        Role accountantRole = roleRepository.findByName(ERole.ROLE_ACCOUNTANT).orElseThrow();
        Role dealerRole = roleRepository.findByName(ERole.ROLE_DEALER).orElseThrow();

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        accountantUser = userRepository.saveAndFlush(User.builder()
                .username("acct_" + suffix)
                .email("acct_" + suffix + "@example.invalid")
                .password("password123")
                .fullName("Kế toán công nợ")
                .roles(new HashSet<>(Set.of(accountantRole)))
                .active(true)
                .build());

        dealerUser = userRepository.saveAndFlush(User.builder()
                .username("dlr_" + suffix)
                .email("dlr_" + suffix + "@example.invalid")
                .password("password123")
                .fullName("Đại diện đại lý")
                .roles(new HashSet<>(Set.of(dealerRole)))
                .active(true)
                .build());

        dealer1 = dealerRepository.saveAndFlush(Dealer.builder()
                .code("DL-01-" + suffix)
                .name("Đại lý Sài Gòn")
                .assignedUser(accountantUser)
                .maxDebtDays(30)
                .active(true)
                .build());

        dealer2 = dealerRepository.saveAndFlush(Dealer.builder()
                .code("DL-02-" + suffix)
                .name("Đại lý Hà Nội")
                .assignedUser(null)
                .maxDebtDays(15)
                .active(true)
                .build());

        accountantToken = jwtUtils.generateAccessToken(accountantUser.getUsername(), 0L);
        adminToken = jwtUtils.generateAccessToken("admin", 0L);
        dealerToken = jwtUtils.generateAccessToken(dealerUser.getUsername(), 0L);
    }

    private void seedInvoices() {
        // Dealer 1: Hóa đơn 50 triệu, phát hành 2026-08-01, hạn 2026-08-31 (quá hạn ~55 ngày so với 2026-10-25 -> nhóm "old")
        invoiceRepository.saveAndFlush(Invoice.builder()
                .invoiceNumber(UUID.randomUUID().toString().substring(0, 8))
                .invoiceCode("HD-01-" + UUID.randomUUID().toString().substring(0, 5))
                .dealer(dealer1)
                .dealerCode(dealer1.getCode())
                .dealerName(dealer1.getName())
                .invoiceDate(LocalDateTime.of(2026, 8, 1, 8, 0))
                .dueDate(LocalDate.of(2026, 8, 31))
                .totalAmount(new BigDecimal("50000000.00"))
                .adjustedTotalAmount(new BigDecimal("50000000.00"))
                .status(InvoiceStatus.ISSUED)
                .build());

        // Dealer 2: Hóa đơn 15 triệu, phát hành 2026-10-10, hạn 2026-10-20 (quá hạn 5 ngày so với 2026-10-25 -> nhóm "recent")
        invoiceRepository.saveAndFlush(Invoice.builder()
                .invoiceNumber(UUID.randomUUID().toString().substring(0, 8))
                .invoiceCode("HD-02-" + UUID.randomUUID().toString().substring(0, 5))
                .dealer(dealer2)
                .dealerCode(dealer2.getCode())
                .dealerName(dealer2.getName())
                .invoiceDate(LocalDateTime.of(2026, 10, 10, 8, 0))
                .dueDate(LocalDate.of(2026, 10, 20))
                .totalAmount(new BigDecimal("15000000.00"))
                .adjustedTotalAmount(new BigDecimal("15000000.00"))
                .status(InvoiceStatus.ISSUED)
                .build());
    }

    @Test
    @DisplayName("Kế toán có quyền REPORT_FINANCE gọi options thành công 200 OK")
    void testGetOptionsWithPermission() throws Exception {
        MockHttpServletResponse res = mvc.perform(get("/api/reports/debt-aging/options")
                        .header("Authorization", "Bearer " + accountantToken))
                .andReturn().getResponse();

        assertThat(res.getStatus()).isEqualTo(200);
        JsonNode root = json.readTree(res.getContentAsByteArray());
        assertThat(root.path("success").asBoolean()).isTrue();
        assertThat(root.path("data").path("timezone").asText()).isEqualTo("Asia/Ho_Chi_Minh");
        assertThat(root.path("data").path("buckets").size()).isEqualTo(3);
    }

    @Test
    @DisplayName("Xem báo cáo tuổi nợ thành công 200 OK và kiểm tra cấu trúc/logic xếp hạng rủi ro")
    void testGetReportSuccessAndRiskOrder() throws Exception {
        seedInvoices();

        MockHttpServletResponse res = mvc.perform(get("/api/reports/debt-aging?asOfDate=2026-10-25")
                        .header("Authorization", "Bearer " + accountantToken))
                .andReturn().getResponse();

        assertThat(res.getStatus()).isEqualTo(200);
        JsonNode data = json.readTree(res.getContentAsByteArray()).path("data");

        assertThat(data.path("complete").asBoolean()).isTrue();
        assertThat(data.path("currency").asText()).isEqualTo("VND");
        assertThat(data.path("totals").path("dealerCount").asInt()).isEqualTo(2);
        assertThat(data.path("totals").path("overdueAmount").asText()).isEqualTo("65000000.00");
        assertThat(data.path("totals").path("maxOverdueDays").asInt()).isGreaterThanOrEqualTo(45);

        // Đại lý 1 rủi ro cao hơn (quá hạn nhiều ngày hơn và tiền lớn hơn) nên xếp rank 1
        JsonNode dealers = data.path("dealers");
        assertThat(dealers.size()).isEqualTo(2);
        assertThat(dealers.get(0).path("dealerId").asLong()).isEqualTo(dealer1.getId());
        assertThat(dealers.get(0).path("riskRank").asInt()).isEqualTo(1);
        assertThat(dealers.get(0).path("riskLabel").asText()).isEqualTo("Ưu tiên cao");

        assertThat(dealers.get(1).path("dealerId").asLong()).isEqualTo(dealer2.getId());
        assertThat(dealers.get(1).path("riskRank").asInt()).isEqualTo(2);

        // Tổng hợp nhóm tuổi nợ
        JsonNode bucketTotals = data.path("bucketTotals");
        assertThat(bucketTotals.size()).isEqualTo(3);
        assertThat(bucketTotals.get(0).path("bucketKey").asText()).isEqualTo("recent");
        assertThat(bucketTotals.get(0).path("amount").asText()).isEqualTo("15000000.00");
        assertThat(bucketTotals.get(0).path("dealerCount").asInt()).isEqualTo(1);

        assertThat(bucketTotals.get(2).path("bucketKey").asText()).isEqualTo("old");
        assertThat(bucketTotals.get(2).path("amount").asText()).isEqualTo("50000000.00");
        assertThat(bucketTotals.get(2).path("dealerCount").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("Admin luôn có toàn quyền xem báo cáo 200 OK")
    void testAdminAlwaysHasAccess() throws Exception {
        MockHttpServletResponse res = mvc.perform(get("/api/reports/debt-aging")
                        .header("Authorization", "Bearer " + adminToken))
                .andReturn().getResponse();

        assertThat(res.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("Thu hồi quyền REPORT_FINANCE khỏi vai trò trên cùng JWT lập tức bị 403 Forbidden")
    void testPermissionRevocationReturns403() throws Exception {
        // Thu hồi REPORT_FINANCE khỏi ROLE_ACCOUNTANT
        permissionMatrix.updateRolePermissions(ERole.ROLE_ACCOUNTANT, Set.of(Permission.FINANCE_VIEW_DEBT));

        MockHttpServletResponse res = mvc.perform(get("/api/reports/debt-aging")
                        .header("Authorization", "Bearer " + accountantToken))
                .andReturn().getResponse();

        assertThat(res.getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("Tài khoản đại lý (ROLE_DEALER) bị từ chối truy cập báo cáo nội bộ 403 Forbidden")
    void testDealerRoleDenied403() throws Exception {
        MockHttpServletResponse res = mvc.perform(get("/api/reports/debt-aging")
                        .header("Authorization", "Bearer " + dealerToken))
                .andReturn().getResponse();

        assertThat(res.getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("Xuất Excel (.xlsx) trả về mã 200, Content-Type và đọc được 4 sheet hợp lệ")
    void testExportExcelReturnsValidWorkbook() throws Exception {
        seedInvoices();

        MockHttpServletResponse res = mvc.perform(get("/api/reports/debt-aging/export?asOfDate=2026-10-25")
                        .header("Authorization", "Bearer " + accountantToken))
                .andReturn().getResponse();

        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(res.getContentType()).contains("spreadsheetml.sheet");
        assertThat(res.getHeader("Content-Disposition")).contains("attachment;").contains("Cong-no-tuoi-no_2026-10-25_tat-ca.xlsx");

        byte[] bytes = res.getContentAsByteArray();
        assertThat(bytes.length).isGreaterThan(100);

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(4);
            assertThat(workbook.getSheetAt(0).getSheetName()).isEqualTo("Thông tin chung");
            assertThat(workbook.getSheetAt(1).getSheetName()).isEqualTo("Nhóm tuổi nợ");
            assertThat(workbook.getSheetAt(2).getSheetName()).isEqualTo("Theo nhân viên");
            assertThat(workbook.getSheetAt(3).getSheetName()).isEqualTo("Chi tiết đại lý rủi ro");
        }
    }
}

