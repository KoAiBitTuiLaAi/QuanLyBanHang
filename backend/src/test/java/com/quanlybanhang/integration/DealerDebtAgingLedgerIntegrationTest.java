package com.quanlybanhang.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanlybanhang.config.RolePermissionConfig;
import com.quanlybanhang.model.*;
import com.quanlybanhang.repository.*;
import com.quanlybanhang.security.JwtUtils;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:dealer_debt_aging_integration_test;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.show-sql=false",
        "app.jwt.secret=dealer-debt-aging-test-secret-at-least-32-chars",
        "app.bootstrap.admin-password=AgingAdmin2026!",
        "app.bootstrap.admin-email=agingadmin@example.invalid",
        "app.seed-demo-users=false",
        "app.seed-demo-data=false"
})
@AutoConfigureMockMvc
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("Integration test: Sổ công nợ theo đại lý với phân tích tuổi nợ & Ma trận phân quyền (S7-03)")
class DealerDebtAgingLedgerIntegrationTest {

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
    private User noPermUser;
    private Dealer dealer1;
    private Dealer dealer2;
    private String accountantToken;
    private String adminToken;
    private String noPermToken;

    @BeforeEach
    void setUp() {
        // Cấu hình ma trận phân quyền: ROLE_ACCOUNTANT có FINANCE_VIEW_DEBT
        permissionMatrix.updateRolePermissions(ERole.ROLE_ACCOUNTANT, Set.of(Permission.FINANCE_VIEW_DEBT));
        permissionMatrix.updateRolePermissions(ERole.ROLE_VIEWER, Set.of(Permission.PRODUCT_VIEW));

        Role accountantRole = roleRepository.findByName(ERole.ROLE_ACCOUNTANT).orElseThrow();
        Role adminRole = roleRepository.findByName(ERole.ROLE_ADMIN).orElseThrow();
        Role viewerRole = roleRepository.findByName(ERole.ROLE_VIEWER).orElseThrow();

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        accountantUser = userRepository.saveAndFlush(User.builder()
                .username("acct_" + suffix)
                .email("acct_" + suffix + "@example.invalid")
                .password("password123")
                .fullName("Kế toán công nợ")
                .roles(new HashSet<>(Set.of(accountantRole)))
                .active(true)
                .build());

        User adminUser = userRepository.saveAndFlush(User.builder()
                .username("admin_" + suffix)
                .email("admin_" + suffix + "@example.invalid")
                .password("password123")
                .fullName("Quản trị hệ thống")
                .roles(new HashSet<>(Set.of(adminRole)))
                .active(true)
                .build());

        noPermUser = userRepository.saveAndFlush(User.builder()
                .username("viewer_" + suffix)
                .email("viewer_" + suffix + "@example.invalid")
                .password("password123")
                .fullName("Người xem")
                .roles(new HashSet<>(Set.of(viewerRole)))
                .active(true)
                .build());

        accountantToken = "Bearer " + jwtUtils.generateAccessToken(accountantUser.getUsername(), 0L);
        adminToken = "Bearer " + jwtUtils.generateAccessToken(adminUser.getUsername(), 0L);
        noPermToken = "Bearer " + jwtUtils.generateAccessToken(noPermUser.getUsername(), 0L);

        // Tạo 2 đại lý
        dealer1 = dealerRepository.saveAndFlush(Dealer.builder()
                .code("DL-01-" + suffix)
                .name("Đại lý Miền Bắc")
                .phone("0901111222")
                .debtLimit(new BigDecimal("200000000"))
                .maxDebtDays(30)
                .active(true)
                .build());

        dealer2 = dealerRepository.saveAndFlush(Dealer.builder()
                .code("DL-02-" + suffix)
                .name("Đại lý Miền Nam")
                .phone("0903333444")
                .debtLimit(new BigDecimal("100000000"))
                .maxDebtDays(15)
                .active(true)
                .build());

        LocalDate today = LocalDate.now();

        // Đại lý 1: Hóa đơn 70 ngày trước (tuổi 61-90), 50tr, quá hạn 40 ngày
        Invoice inv1 = Invoice.builder()
                .invoiceNumber(UUID.randomUUID().toString().substring(0, 8))
                .invoiceCode("HD-01-" + suffix)
                .dealer(dealer1)
                .dealerCode(dealer1.getCode())
                .dealerName(dealer1.getName())
                .totalAmount(new BigDecimal("50000000.00"))
                .adjustedTotalAmount(new BigDecimal("50000000.00"))
                .invoiceDate(today.minusDays(70).atStartOfDay())
                .dueDate(today.minusDays(40))
                .status(InvoiceStatus.ISSUED)
                .build();
        invoiceRepository.saveAndFlush(inv1);

        // Đại lý 2: Hóa đơn 10 ngày trước (tuổi 0-30), 20tr, trong hạn
        Invoice inv2 = Invoice.builder()
                .invoiceNumber(UUID.randomUUID().toString().substring(0, 8))
                .invoiceCode("HD-02-" + suffix)
                .dealer(dealer2)
                .dealerCode(dealer2.getCode())
                .dealerName(dealer2.getName())
                .totalAmount(new BigDecimal("20000000.00"))
                .adjustedTotalAmount(new BigDecimal("20000000.00"))
                .invoiceDate(today.minusDays(10).atStartOfDay())
                .dueDate(today.plusDays(5))
                .status(InvoiceStatus.ISSUED)
                .build();
        invoiceRepository.saveAndFlush(inv2);

        // Thanh toán 10tr cho đại lý 1
        paymentRepository.saveAndFlush(DebtPayment.builder()
                .paymentCode("PT-01-" + suffix)
                .dealer(dealer1)
                .amount(new BigDecimal("10000000"))
                .paymentDate(today.minusDays(5))
                .build());
    }

    @Test
    @DisplayName("ROLE_ADMIN: Xem sổ công nợ theo đại lý thành công (200 OK) với đầy đủ cấu trúc DTO")
    void testGetLedgerWithAdminRole() throws Exception {
        MockHttpServletResponse res = mvc.perform(get("/api/finance/debt-aging-ledger")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn().getResponse();

        JsonNode root = json.readTree(res.getContentAsString());
        assertThat(root.get("success").asBoolean()).isTrue();

        JsonNode data = root.get("data");
        assertThat(data.has("asOfDate")).isTrue();
        assertThat(data.has("summary")).isTrue();
        assertThat(data.has("dealers")).isTrue();

        JsonNode dealers = data.get("dealers");
        assertThat(dealers.size()).isGreaterThanOrEqualTo(2);

        JsonNode summary = data.get("summary");
        assertThat(summary.get("totalReceivable").asDouble()).isGreaterThan(0);
        assertThat(summary.get("totalPaid").asDouble()).isGreaterThan(0);
        assertThat(summary.get("totalRemainingDebt").asDouble()).isGreaterThan(0);
        assertThat(summary.get("totalBucket61To90").asDouble()).isGreaterThan(0);
        assertThat(summary.get("totalBucket0To30").asDouble()).isGreaterThan(0);
    }

    @Test
    @DisplayName("ROLE_ACCOUNTANT có quyền FINANCE_VIEW_DEBT: Xem sổ công nợ thành công")
    void testGetLedgerWithAccountantRole() throws Exception {
        MockHttpServletResponse res = mvc.perform(get("/api/finance/debt-aging-ledger")
                        .header(HttpHeaders.AUTHORIZATION, accountantToken)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn().getResponse();

        JsonNode root = json.readTree(res.getContentAsString());
        assertThat(root.get("success").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("Lọc riêng nhóm quá hạn: overdueOnly=true chỉ trả về đại lý có nợ quá hạn")
    void testGetLedgerFilterOverdueOnly() throws Exception {
        MockHttpServletResponse res = mvc.perform(get("/api/finance/debt-aging-ledger")
                        .param("overdueOnly", "true")
                        .header(HttpHeaders.AUTHORIZATION, accountantToken)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn().getResponse();

        JsonNode root = json.readTree(res.getContentAsString());
        JsonNode data = root.get("data");
        JsonNode dealers = data.get("dealers");

        // Đại lý 1 có nợ quá hạn, đại lý 2 chỉ có nợ trong hạn
        for (JsonNode d : dealers) {
            assertThat(d.get("isOverdue").asBoolean()).isTrue();
            assertThat(d.get("overdueDebt").asDouble()).isGreaterThan(0);
        }
    }

    @Test
    @DisplayName("Xuất Excel (.xlsx): Trả về file hợp lệ, kiểm tra tiêu đề và dòng TỔNG CỘNG chân bảng")
    void testExportExcel() throws Exception {
        MockHttpServletResponse res = mvc.perform(get("/api/finance/debt-aging-ledger/export")
                        .header(HttpHeaders.AUTHORIZATION, accountantToken))
                .andExpect(status().isOk())
                .andReturn().getResponse();

        assertThat(res.getContentType()).contains("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(res.getHeader(HttpHeaders.CONTENT_DISPOSITION)).contains("attachment;").contains(".xlsx");
        assertThat(res.getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");

        byte[] bytes = res.getContentAsByteArray();
        assertThat(bytes.length).isGreaterThan(100);

        // Mở workbook bằng Apache POI để xác thực nội dung
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheet("Sổ công nợ và tuổi nợ");
            assertThat(sheet).isNotNull();

            // Kiểm tra tiêu đề báo cáo
            String title = sheet.getRow(0).getCell(0).getStringCellValue();
            assertThat(title).contains("SỔ CÔNG NỢ THEO ĐẠI LÝ VÀ PHÂN TÍCH TUỔI NỢ");

            // Tìm dòng TỔNG CỘNG ở chân bảng
            boolean foundSummaryRow = false;
            for (int r = 0; r <= sheet.getLastRowNum(); r++) {
                if (sheet.getRow(r) != null && sheet.getRow(r).getCell(0) != null) {
                    org.apache.poi.ss.usermodel.Cell cell = sheet.getRow(r).getCell(0);
                    if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.STRING
                            && "TỔNG CỘNG".equals(cell.getStringCellValue())) {
                        foundSummaryRow = true;
                        break;
                    }
                }
            }
            assertThat(foundSummaryRow).isTrue();
        }
    }

    @Test
    @DisplayName("Ma trận phân quyền: Thu hồi FINANCE_VIEW_DEBT từ ROLE_ACCOUNTANT lập tức trả 403 Forbidden")
    void testPermissionRevocationReturns403() throws Exception {
        // Trước khi thu hồi: 200 OK
        mvc.perform(get("/api/finance/debt-aging-ledger")
                        .header(HttpHeaders.AUTHORIZATION, accountantToken))
                .andExpect(status().isOk());

        // Thu hồi quyền FINANCE_VIEW_DEBT khỏi ROLE_ACCOUNTANT
        permissionMatrix.updateRolePermissions(ERole.ROLE_ACCOUNTANT, Set.of());

        // Cùng token JWT đó lập tức bị chặn 403 Forbidden
        mvc.perform(get("/api/finance/debt-aging-ledger")
                        .header(HttpHeaders.AUTHORIZATION, accountantToken))
                .andExpect(status().isForbidden());

        // Xuất Excel cũng bị chặn 403 Forbidden
        mvc.perform(get("/api/finance/debt-aging-ledger/export")
                        .header(HttpHeaders.AUTHORIZATION, accountantToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Tài khoản không có quyền FINANCE_VIEW_DEBT: Bị từ chối 403 Forbidden")
    void testNoPermissionReturns403() throws Exception {
        mvc.perform(get("/api/finance/debt-aging-ledger")
                        .header(HttpHeaders.AUTHORIZATION, noPermToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Chưa đăng nhập: Trả về 401 Unauthorized")
    void testUnauthenticatedReturns401() throws Exception {
        mvc.perform(get("/api/finance/debt-aging-ledger"))
                .andExpect(status().isUnauthorized());
    }
}
