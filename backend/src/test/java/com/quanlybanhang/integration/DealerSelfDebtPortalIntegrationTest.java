package com.quanlybanhang.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanlybanhang.config.RolePermissionConfig;
import com.quanlybanhang.dto.request.CreateInvoiceAdjustmentRequest;
import com.quanlybanhang.dto.request.CreateInvoiceRequest;
import com.quanlybanhang.dto.request.DebtPaymentRequest;
import com.quanlybanhang.dto.request.LoginRequest;
import com.quanlybanhang.model.*;
import com.quanlybanhang.repository.*;
import com.quanlybanhang.service.DealerDebtService;
import com.quanlybanhang.service.InvoiceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Kiểm thử tích hợp End-to-End cho tính năng S7-07:
 * "Là Đại lý, tôi muốn xem công nợ của chính mình, để biết còn nợ bao nhiêu và còn được lấy hàng bao nhiêu."
 *
 * Kiểm chứng đầy đủ 3 tiêu chí nghiệm thu & quy tắc ma trận phân quyền (AGENTS.md):
 * 1. Danh sách hoá đơn kèm số tiền, hạn thanh toán, trạng thái (trạng thái phát hành & trạng thái thanh toán FIFO).
 * 2. Xem và tải lại hoá đơn đã phát hành (chi tiết JSON, bản in HTML, xuất lại PDF hoá đơn & chứng từ điều chỉnh).
 * 3. Hiển thị tổng còn nợ (currentDebt) và hạn mức còn lại (remainingLimit, availableOrderLimit).
 * 4. Bảo mật cấp dòng dữ liệu (RLS): Đại lý chỉ xem/tải được công nợ và hóa đơn của chính mình; truy cập hóa đơn đại lý khác trả về 403.
 * 5. Thực thi ma trận phân quyền database-backed (RolePermissionConfig): Thu hồi quyền FINANCE_VIEW_DEBT hoặc INVOICE_VIEW
 *    của ROLE_DEALER lập tức trả về 403 trên phiên JWT hiện hành; ROLE_ADMIN luôn có toàn quyền.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("S7-07 Integration Test - Đại lý tự xem công nợ, hạn mức còn lại và tải lại hóa đơn")
class DealerSelfDebtPortalIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RolePermissionConfig rolePermissionConfig;

    @Autowired
    private DealerRepository dealerRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private InvoiceService invoiceService;

    @Autowired
    private DealerDebtService dealerDebtService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Dealer dealerA;
    private Dealer dealerB;
    private Product productBia;
    private Order orderA1;
    private Order orderA2;
    private Order orderB1;
    private String dealerAToken;
    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        rolePermissionConfig.resetToDefault();

        Role roleDealer = roleRepository.findByName(ERole.ROLE_DEALER)
                .orElseGet(() -> roleRepository.save(Role.builder().name(ERole.ROLE_DEALER).build()));
        Role roleAdmin = roleRepository.findByName(ERole.ROLE_ADMIN)
                .orElseGet(() -> roleRepository.save(Role.builder().name(ERole.ROLE_ADMIN).build()));

        String suffix = String.valueOf(System.nanoTime() % 100000);

        dealerA = dealerRepository.save(Dealer.builder()
                .code("DL-S707-A-" + suffix)
                .name("Đại lý Minh Phát S707")
                .taxCode("0101112223")
                .customerGroup(CustomerGroup.DAI_LY_CAP_1)
                .address("100 Nguyễn Trãi, Hà Nội")
                .phone("0901112222")
                .debtLimit(new BigDecimal("50000000.00"))
                .maxDebtDays(30)
                .active(true)
                .locked(false)
                .build());

        dealerB = dealerRepository.save(Dealer.builder()
                .code("DL-S707-B-" + suffix)
                .name("Đại lý Hoàng Gia S707")
                .taxCode("0109998887")
                .customerGroup(CustomerGroup.DAI_LY_CAP_2)
                .address("200 Lê Lợi, Đà Nẵng")
                .phone("0909998888")
                .debtLimit(new BigDecimal("40000000.00"))
                .maxDebtDays(15)
                .active(true)
                .locked(false)
                .build());

        String dealerAUsername = "dealer_a_s707_" + suffix;
        userRepository.save(User.builder()
                .username(dealerAUsername)
                .password(passwordEncoder.encode("Password@123"))
                .fullName("Đại diện Đại lý Minh Phát")
                .email(dealerAUsername + "@example.com")
                .dealer(dealerA)
                .roles(new HashSet<>(Set.of(roleDealer)))
                .active(true)
                .build());

        String adminUsername = "admin_s707_" + suffix;
        userRepository.save(User.builder()
                .username(adminUsername)
                .password(passwordEncoder.encode("Password@123"))
                .fullName("Quản trị viên S707")
                .email(adminUsername + "@example.com")
                .roles(new HashSet<>(Set.of(roleAdmin)))
                .active(true)
                .build());

        productBia = productRepository.save(Product.builder()
                .code("SP-S707-" + suffix)
                .name("Bia Lon Cao Cấp 330ml")
                .unit("thùng")
                .price(new BigDecimal("500000.00"))
                .costPrice(new BigDecimal("400000.00"))
                .active(true)
                .build());

        // Đơn hàng 1 của Đại lý A: đã giao 20 thùng * 500,000 = 10,000,000 (phát sinh 40 ngày trước -> quá hạn 10 ngày)
        orderA1 = createDeliveredOrder("DH-S707-A1-" + suffix, dealerA, new BigDecimal("20"), new BigDecimal("500000.00"),
                LocalDate.now().minusDays(40));

        // Đơn hàng 2 của Đại lý A: đã giao 30 thùng * 500,000 = 15,000,000 (phát sinh 5 ngày trước -> còn trong hạn)
        orderA2 = createDeliveredOrder("DH-S707-A2-" + suffix, dealerA, new BigDecimal("30"), new BigDecimal("500000.00"),
                LocalDate.now().minusDays(5));

        // Đơn hàng 1 của Đại lý B: đã giao 10 thùng * 500,000 = 5,000,000
        orderB1 = createDeliveredOrder("DH-S707-B1-" + suffix, dealerB, new BigDecimal("10"), new BigDecimal("500000.00"),
                LocalDate.now().minusDays(3));

        dealerAToken = loginAndGetToken(dealerAUsername, "Password@123");
        adminToken = loginAndGetToken(adminUsername, "Password@123");
    }

    private Order createDeliveredOrder(String code, Dealer dealer, BigDecimal qty, BigDecimal price, LocalDate deliveryDate) {
        BigDecimal total = qty.multiply(price);
        Order order = Order.builder()
                .orderCode(code)
                .dealer(dealer)
                .status("DELIVERED")
                .totalAmount(total)
                .recordedAt(deliveryDate.atTime(9, 0))
                .actualDeliveryDate(deliveryDate)
                .createdAt(deliveryDate.atTime(8, 0))
                .items(new ArrayList<>())
                .build();

        OrderItem item = OrderItem.builder()
                .order(order)
                .product(productBia)
                .unit("thùng")
                .quantity(qty)
                .deliveredQuantity(qty)
                .price(price)
                .discountPercent(BigDecimal.ZERO)
                .discountAmount(BigDecimal.ZERO)
                .build();
        order.getItems().add(item);
        return orderRepository.save(order);
    }

    private String loginAndGetToken(String username, String password) throws Exception {
        LoginRequest req = new LoginRequest();
        req.setUsername(username);
        req.setPassword(password);

        MvcResult res = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode root = objectMapper.readTree(res.getResponse().getContentAsString());
        return root.path("data").path("token").asText();
    }

    @Test
    @DisplayName("Tiêu chí 1 & 3: Đại lý xem tổng còn nợ, hạn mức còn lại và danh sách hóa đơn kèm số tiền, hạn thanh toán, trạng thái")
    void dealerViewsOwnDebtSummaryAndInvoicesWithDueDateAndPaymentStatus() throws Exception {
        // Phát hành Hóa đơn 1 cho Đơn A1 (ngày phát hành 40 ngày trước, hạn thanh toán = 40 - 30 = 10 ngày trước -> OVERDUE)
        var inv1 = invoiceService.issueInvoice(CreateInvoiceRequest.builder()
                .dealerId(dealerA.getId())
                .orderIds(List.of(orderA1.getId()))
                .invoiceDate(LocalDateTime.now().minusDays(40))
                .note("Hóa đơn đợt 1")
                .build(), "accountant");

        // Phát hành Hóa đơn 2 cho Đơn A2 (ngày phát hành 5 ngày trước, hạn thanh toán = +25 ngày nữa -> UNPAID)
        var inv2 = invoiceService.issueInvoice(CreateInvoiceRequest.builder()
                .dealerId(dealerA.getId())
                .orderIds(List.of(orderA2.getId()))
                .invoiceDate(LocalDateTime.now().minusDays(5))
                .note("Hóa đơn đợt 2")
                .build(), "accountant");

        // 1. Khi chưa thanh toán:
        // Hóa đơn 1 = 10tr (OVERDUE), Hóa đơn 2 = 15tr (UNPAID) -> Tổng nợ = 25tr, Hạn mức = 50tr, Còn lại = 25tr
        // Nhưng vì Hóa đơn 1 đang quá hạn 10 ngày (cấu hình mặc định BLOCK khi quá hạn) -> availableOrderLimit = 0
        mockMvc.perform(get("/api/portal/debt/summary")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + dealerAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dealerId").value(dealerA.getId()))
                .andExpect(jsonPath("$.data.debtLimit").value(50000000))
                .andExpect(jsonPath("$.data.currentDebt").value(25000000))
                .andExpect(jsonPath("$.data.remainingLimit").value(25000000))
                .andExpect(jsonPath("$.data.availableOrderLimit").value(0))
                .andExpect(jsonPath("$.data.hasOverdueDebt").value(true))
                .andExpect(jsonPath("$.data.overdueInvoiceCount").value(1))
                .andExpect(jsonPath("$.data.unpaidInvoiceCount").value(2))
                .andExpect(jsonPath("$.data.totalInvoiceCount").value(2));

        // 2. Ghi nhận đại lý A thanh toán 12,000,000 VNĐ:
        // Theo FIFO:
        // - Hóa đơn 1 (10,000,000) được trả đủ 10,000,000 -> PAID (gỡ quá hạn!)
        // - Hóa đơn 2 (15,000,000) được trả 2,000,000, còn nợ 13,000,000 -> PARTIALLY_PAID (trong hạn)
        // - Tổng còn nợ (currentDebt) = 25,000,000 - 12,000,000 = 13,000,000
        // - Hạn mức còn lại (remainingLimit) = 50,000,000 - 13,000,000 = 37,000,000
        // - Hạn mức còn được lấy hàng (availableOrderLimit) = 37,000,000 (vì đã hết nợ quá hạn!)
        dealerDebtService.recordPayment(DebtPaymentRequest.builder()
                .dealerId(dealerA.getId())
                .amount(new BigDecimal("12000000.00"))
                .paymentMethod("CHUYEN_KHOAN")
                .referenceCode("UNC-S707-001")
                .note("Đại lý Minh Phát thanh toán đợt 1")
                .build(), "accountant");

        mockMvc.perform(get("/api/portal/debt")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + dealerAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentDebt").value(13000000))
                .andExpect(jsonPath("$.data.debtLimit").value(50000000))
                .andExpect(jsonPath("$.data.remainingLimit").value(37000000))
                .andExpect(jsonPath("$.data.availableOrderLimit").value(37000000))
                .andExpect(jsonPath("$.data.totalPaid").value(12000000))
                .andExpect(jsonPath("$.data.hasOverdueDebt").value(false))
                .andExpect(jsonPath("$.data.isBlocked").value(false))
                .andExpect(jsonPath("$.data.paidInvoiceCount").value(1))
                .andExpect(jsonPath("$.data.unpaidInvoiceCount").value(1))
                .andExpect(jsonPath("$.data.overdueInvoiceCount").value(0));

        // 3. Kiểm tra danh sách hóa đơn qua GET /api/portal/invoices
        MvcResult invListRes = mockMvc.perform(get("/api/portal/invoices")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + dealerAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andReturn();

        JsonNode content = objectMapper.readTree(invListRes.getResponse().getContentAsString())
                .path("data").path("content");
        // Sắp xếp mới nhất trước: inv2 (đợt 2) ở vị trí 0, inv1 (đợt 1) ở vị trí 1
        JsonNode latestInv = content.get(0);
        assertThat(latestInv.path("id").asLong()).isEqualTo(inv2.getId());
        assertThat(latestInv.path("totalAmount").decimalValue()).isEqualByComparingTo("15000000.00");
        assertThat(latestInv.path("paidAmount").decimalValue()).isEqualByComparingTo("2000000.00");
        assertThat(latestInv.path("remainingAmount").decimalValue()).isEqualByComparingTo("13000000.00");
        assertThat(latestInv.path("paymentStatus").asText()).isEqualTo("PARTIALLY_PAID");
        assertThat(latestInv.path("paymentStatusLabel").asText()).isEqualTo("Thanh toán một phần");
        assertThat(latestInv.path("dueDate").asText()).isEqualTo(LocalDate.now().minusDays(5).plusDays(30).toString());

        JsonNode olderInv = content.get(1);
        assertThat(olderInv.path("id").asLong()).isEqualTo(inv1.getId());
        assertThat(olderInv.path("totalAmount").decimalValue()).isEqualByComparingTo("10000000.00");
        assertThat(olderInv.path("paidAmount").decimalValue()).isEqualByComparingTo("10000000.00");
        assertThat(olderInv.path("remainingAmount").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(olderInv.path("paymentStatus").asText()).isEqualTo("PAID");
        assertThat(olderInv.path("paymentStatusLabel").asText()).isEqualTo("Đã thanh toán");

        // 4. Lọc danh sách hóa đơn theo trạng thái thanh toán paymentStatus=PAID
        mockMvc.perform(get("/api/portal/invoices")
                        .param("paymentStatus", "PAID")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + dealerAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].id").value(inv1.getId()));

        // 5. Xem lịch sử thanh toán qua GET /api/portal/debt/payments
        mockMvc.perform(get("/api/portal/debt/payments")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + dealerAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].amount").value(12000000))
                .andExpect(jsonPath("$.data[0].referenceCode").value("UNC-S707-001"));
    }

    @Test
    @DisplayName("Tiêu chí 2 & Bảo mật RLS: Đại lý xem chi tiết, bản in HTML và tải lại PDF hóa đơn của mình; chặn 403 với hóa đơn của đại lý khác")
    void dealerCanViewAndRedownloadOwnIssuedInvoiceAndIsBlockedFromOtherDealersInvoice() throws Exception {
        // Phát hành hóa đơn cho Đại lý A
        var invA = invoiceService.issueInvoice(CreateInvoiceRequest.builder()
                .dealerId(dealerA.getId())
                .orderIds(List.of(orderA1.getId()))
                .note("Hóa đơn của Đại lý A")
                .build(), "accountant");

        // Lập chứng từ điều chỉnh giảm 1,000,000 cho hóa đơn của Đại lý A
        var adjA = invoiceService.createAdjustment(invA.getId(), CreateInvoiceAdjustmentRequest.builder()
                .adjustmentType(InvoiceAdjustmentType.DECREASE)
                .adjustmentReason("Chiết khấu thương mại bổ sung")
                .adjustmentAmount(new BigDecimal("1000000.00"))
                .build(), "accountant");

        // Phát hành hóa đơn cho Đại lý B
        var invB = invoiceService.issueInvoice(CreateInvoiceRequest.builder()
                .dealerId(dealerB.getId())
                .orderIds(List.of(orderB1.getId()))
                .note("Hóa đơn của Đại lý B")
                .build(), "accountant");

        // 1. Đại lý A xem chi tiết hóa đơn đã phát hành của mình -> 200 OK
        mockMvc.perform(get("/api/portal/invoices/" + invA.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + dealerAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(invA.getId()))
                .andExpect(jsonPath("$.data.invoiceCode").value(invA.getInvoiceCode()))
                .andExpect(jsonPath("$.data.status").value("ADJUSTED"))
                .andExpect(jsonPath("$.data.totalAmount").value(10000000))
                .andExpect(jsonPath("$.data.adjustedTotalAmount").value(9000000))
                .andExpect(jsonPath("$.data.remainingAmount").value(9000000))
                .andExpect(jsonPath("$.data.adjustments.length()").value(1));

        // 2. Đại lý A tải lại PDF hóa đơn của mình -> 200 OK, đúng định dạng %PDF
        MvcResult pdfRes = mockMvc.perform(get("/api/portal/invoices/" + invA.getId() + "/pdf")
                        .param("download", "true")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + dealerAToken))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"hoa-don-" + invA.getInvoiceNumber() + ".pdf\""))
                .andReturn();
        byte[] pdfBytes = pdfRes.getResponse().getContentAsByteArray();
        assertThat(pdfBytes).isNotEmpty();
        assertThat(new String(pdfBytes, 0, 4)).isEqualTo("%PDF");

        // 3. Đại lý A xem bản in HTML hóa đơn của mình -> 200 OK
        MvcResult htmlRes = mockMvc.perform(get("/api/portal/invoices/" + invA.getId() + "/print-preview")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + dealerAToken))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(htmlRes.getResponse().getContentAsString()).contains(invA.getInvoiceNumber());

        // 4. Đại lý A tải lại PDF chứng từ điều chỉnh của hóa đơn mình -> 200 OK
        MvcResult adjPdfRes = mockMvc.perform(get("/api/portal/invoices/" + invA.getId() + "/adjustments/" + adjA.getId() + "/pdf")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + dealerAToken))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andReturn();
        assertThat(adjPdfRes.getResponse().getContentAsByteArray()).isNotEmpty();

        // 5. RLS: Đại lý A cố tình xem chi tiết, bản in hoặc tải PDF hóa đơn của Đại lý B -> Bị chặn 403 Forbidden!
        mockMvc.perform(get("/api/portal/invoices/" + invB.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + dealerAToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/portal/invoices/" + invB.getId() + "/pdf")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + dealerAToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/portal/invoices/" + invB.getId() + "/print-preview")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + dealerAToken))
                .andExpect(status().isForbidden());

        // 6. Đại lý A cố tình truyền ?dealerId=<dealerB.id> trên các API công nợ/hóa đơn -> Bị chặn 403 Forbidden!
        mockMvc.perform(get("/api/portal/debt/summary")
                        .param("dealerId", dealerB.getId().toString())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + dealerAToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/portal/invoices")
                        .param("dealerId", dealerB.getId().toString())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + dealerAToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Thực thi Ma trận phân quyền (AGENTS.md): Thu hồi quyền FINANCE_VIEW_DEBT và INVOICE_VIEW có hiệu lực tức thì trên JWT đang mở; ROLE_ADMIN luôn được phép")
    void permissionMatrixRevocationImmediatelyBlocksDealerAndAdminIsAlwaysAllowed() throws Exception {
        var invA = invoiceService.issueInvoice(CreateInvoiceRequest.builder()
                .dealerId(dealerA.getId())
                .orderIds(List.of(orderA1.getId()))
                .build(), "accountant");

        Set<Permission> initialDealerPerms = new HashSet<>(rolePermissionConfig.getPermissions(ERole.ROLE_DEALER));
        try {
            // 1. Mặc định ROLE_DEALER có FINANCE_VIEW_DEBT và INVOICE_VIEW -> 200 OK
            mockMvc.perform(get("/api/portal/debt/summary")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + dealerAToken))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/portal/invoices")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + dealerAToken))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/portal/invoices/" + invA.getId() + "/pdf")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + dealerAToken))
                    .andExpect(status().isOk());

            // 2. Thu hồi quyền INVOICE_VIEW khỏi ROLE_DEALER -> Lập tức trả về 403 Forbidden trên cùng token!
            rolePermissionConfig.updateRolePermissions(ERole.ROLE_DEALER,
                    EnumSet.of(Permission.ORDER_VIEW, Permission.ORDER_CREATE, Permission.PRODUCT_VIEW, Permission.FINANCE_VIEW_DEBT));

            mockMvc.perform(get("/api/portal/invoices")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + dealerAToken))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/api/portal/invoices/" + invA.getId())
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + dealerAToken))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/api/portal/invoices/" + invA.getId() + "/pdf")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + dealerAToken))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/api/portal/invoices/" + invA.getId() + "/print-preview")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + dealerAToken))
                    .andExpect(status().isForbidden());

            // Trong khi đó FINANCE_VIEW_DEBT vẫn còn nên xem tổng quan công nợ vẫn trả 200 OK
            mockMvc.perform(get("/api/portal/debt/summary")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + dealerAToken))
                    .andExpect(status().isOk());

            // 3. Thu hồi tiếp quyền FINANCE_VIEW_DEBT và ORDER_VIEW khỏi ROLE_DEALER -> API công nợ trả về 403 Forbidden!
            rolePermissionConfig.updateRolePermissions(ERole.ROLE_DEALER,
                    EnumSet.of(Permission.ORDER_CREATE, Permission.PRODUCT_VIEW));

            mockMvc.perform(get("/api/portal/debt/summary")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + dealerAToken))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/api/portal/debt/payments")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + dealerAToken))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/api/portal/debt")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + dealerAToken))
                    .andExpect(status().isForbidden());

            // 4. ROLE_ADMIN luôn có toàn quyền truy cập hỗ trợ đại lý bất kể cấu hình ma trận
            rolePermissionConfig.updateRolePermissions(ERole.ROLE_ADMIN, EnumSet.noneOf(Permission.class));
            mockMvc.perform(get("/api/portal/debt/summary")
                            .param("dealerId", dealerA.getId().toString())
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/portal/invoices/" + invA.getId() + "/pdf")
                            .param("dealerId", dealerA.getId().toString())
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                    .andExpect(status().isOk());
        } finally {
            rolePermissionConfig.updateRolePermissions(ERole.ROLE_DEALER, initialDealerPerms);
        }
    }
}

