package com.quanlybanhang.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanlybanhang.config.RolePermissionConfig;
import com.quanlybanhang.dto.request.CreateInvoiceAdjustmentRequest;
import com.quanlybanhang.dto.request.CreateInvoiceRequest;
import com.quanlybanhang.dto.request.InvoicePreviewRequest;
import com.quanlybanhang.model.*;
import com.quanlybanhang.repository.*;
import com.quanlybanhang.security.JwtUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.springframework.test.annotation.DirtiesContext;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:invoice_integration_test;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.show-sql=false",
        "app.jwt.secret=sprint-audit-test-secret-only-at-least-32-characters",
        "app.bootstrap.admin-password=SprintTest2026",
        "app.bootstrap.admin-email=audit@example.invalid",
        "app.seed-demo-users=false",
        "app.seed-demo-data=false"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Transactional
@DisplayName("InvoiceIntegrationTest - Kiểm thử tích hợp toàn diện Hóa đơn bán hàng từ phiếu đã giao (S7-01)")
class InvoiceIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private DealerRepository dealerRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private InvoiceRepository invoiceRepository;

    @Autowired
    private InvoiceSequenceRepository invoiceSequenceRepository;

    @Autowired
    private RolePermissionConfig rolePermissionConfig;

    private User accountantUser;
    private String accountantToken;
    private Dealer dealer;
    private Product product1;
    private Product product2;
    private Order order1;
    private Order order2;

    @BeforeEach
    void setUp() {
        // Tạo Role ACCOUNTANT nếu chưa có
        Role accountantRole = roleRepository.findByName(ERole.ROLE_ACCOUNTANT)
                .orElseGet(() -> roleRepository.save(new Role(ERole.ROLE_ACCOUNTANT)));

        // Đảm bảo ROLE_ACCOUNTANT có đủ quyền mặc định
        rolePermissionConfig.updateRolePermissions(ERole.ROLE_ACCOUNTANT, EnumSet.of(
                Permission.INVOICE_VIEW,
                Permission.INVOICE_CREATE,
                Permission.INVOICE_ADJUST,
                Permission.ORDER_VIEW
        ));

        // Tạo user kế toán
        accountantUser = userRepository.findByUsername("ketoan_test")
                .orElseGet(() -> {
                    User u = User.builder()
                            .username("ketoan_test")
                            .email("ketoan_test@example.com")
                            .fullName("Kế toán công nợ Test")
                            .password(passwordEncoder.encode("Password123!"))
                            .active(true)
                            .roles(new HashSet<>(Set.of(accountantRole)))
                            .tokenVersion(0L)
                            .build();
                    return userRepository.save(u);
                });

        accountantToken = jwtUtils.generateAccessToken(accountantUser.getUsername(), accountantUser.getTokenVersion());

        // Tạo nhóm hàng & sản phẩm
        Category cat = categoryRepository.save(Category.builder()
                .code("CAT-BEER")
                .name("Bia các loại")
                .build());

        product1 = productRepository.save(Product.builder()
                .code("SP-BIA-HN")
                .name("Bia Hà Nội Lon 330ml")
                .category(cat)
                .unit("thùng")
                .price(new BigDecimal("300000.00"))
                .active(true)
                .build());

        product2 = productRepository.save(Product.builder()
                .code("SP-BIA-TG")
                .name("Bia Tiger Lon 330ml")
                .category(cat)
                .unit("thùng")
                .price(new BigDecimal("350000.00"))
                .active(true)
                .build());

        // Tạo đại lý
        dealer = dealerRepository.save(Dealer.builder()
                .code("DL-TEST-001")
                .name("Đại lý Bia Mai Linh")
                .taxCode("0109988776")
                .address("456 Đường Láng, Đống Đa, Hà Nội")
                .phone("0987654321")
                .active(true)
                .build());

        // Phiếu giao 1 (Order 1): Đặt 10 Bia HN, thực giao 7 (thiếu 3)
        OrderItem o1Item = OrderItem.builder()
                .product(product1)
                .unit("thùng")
                .quantity(new BigDecimal("10"))
                .deliveredQuantity(new BigDecimal("7")) // Thực giao 7
                .conversionFactor(new BigDecimal("24"))
                .baseUnit("lon")
                .baseQuantity(new BigDecimal("240"))
                .deliveredBaseQuantity(new BigDecimal("168"))
                .price(new BigDecimal("300000.00"))
                .subtotalAmount(new BigDecimal("3000000.00"))
                .totalAmount(new BigDecimal("3000000.00"))
                .deliveryNote("Xe tải đầy, giao trước 7 thùng")
                .build();

        order1 = Order.builder()
                .orderCode("PG-20261022-001")
                .dealer(dealer)
                .status("DELIVERED")
                .actualDeliveryDate(LocalDate.of(2026, 10, 21))
                .subtotalAmount(new BigDecimal("3000000.00"))
                .totalAmount(new BigDecimal("3000000.00"))
                .items(new ArrayList<>())
                .build();
        order1.addItem(o1Item);
        order1 = orderRepository.save(order1);

        // Phiếu giao 2 (Order 2): Đặt 5 Bia Tiger, thực giao 5 (đủ)
        OrderItem o2Item = OrderItem.builder()
                .product(product2)
                .unit("thùng")
                .quantity(new BigDecimal("5"))
                .deliveredQuantity(new BigDecimal("5"))
                .conversionFactor(new BigDecimal("24"))
                .baseUnit("lon")
                .baseQuantity(new BigDecimal("120"))
                .deliveredBaseQuantity(new BigDecimal("120"))
                .price(new BigDecimal("350000.00"))
                .subtotalAmount(new BigDecimal("1750000.00"))
                .totalAmount(new BigDecimal("1750000.00"))
                .build();

        order2 = Order.builder()
                .orderCode("PG-20261022-002")
                .dealer(dealer)
                .status("DELIVERED")
                .actualDeliveryDate(LocalDate.of(2026, 10, 22))
                .subtotalAmount(new BigDecimal("1750000.00"))
                .totalAmount(new BigDecimal("1750000.00"))
                .items(new ArrayList<>())
                .build();
        order2.addItem(o2Item);
        order2 = orderRepository.save(order2);
    }

    @Test
    @DisplayName("Toàn trình S7-01: Gộp nhiều phiếu giao -> Hóa đơn theo lượng thực giao -> Số liên tục -> Chặn sửa/xóa -> Lập điều chỉnh -> Xuất PDF")
    void fullLifecycle_InvoiceFromDeliveredOrders() throws Exception {
        // 1. Xem trước hóa đơn gộp 2 phiếu giao
        InvoicePreviewRequest previewReq = InvoicePreviewRequest.builder()
                .dealerId(dealer.getId())
                .orderIds(List.of(order1.getId(), order2.getId()))
                .build();

        mockMvc.perform(post("/api/invoices/preview")
                        .header("Authorization", "Bearer " + accountantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(previewReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.totalOrders").value(2))
                // Đơn 1: 7 * 300,000 = 2,100,000
                // Đơn 2: 5 * 350,000 = 1,750,000
                // Tổng = 3,850,000 (thay vì đặt 10*300k + 5*350k = 4,750,000)
                .andExpect(jsonPath("$.data.totalAmount").value(3850000.00));

        // 2. Phát hành hóa đơn chính thức
        CreateInvoiceRequest issueReq = CreateInvoiceRequest.builder()
                .dealerId(dealer.getId())
                .orderIds(List.of(order1.getId(), order2.getId()))
                .note("Hóa đơn bán hàng tháng 10 đợt 1")
                .build();

        MvcResult issueResult = mockMvc.perform(post("/api/invoices")
                        .header("Authorization", "Bearer " + accountantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(issueReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.invoiceNumber").isNotEmpty())
                .andExpect(jsonPath("$.data.invoiceCode").isNotEmpty())
                .andExpect(jsonPath("$.data.status").value("ISSUED"))
                .andExpect(jsonPath("$.data.totalAmount").value(3850000.00))
                .andExpect(jsonPath("$.data.items").isArray())
                .andReturn();

        String json = issueResult.getResponse().getContentAsString();
        Long invoiceId = objectMapper.readTree(json).path("data").path("id").asLong();
        String invoiceNumber = objectMapper.readTree(json).path("data").path("invoiceNumber").asText();
        String invoiceCode = objectMapper.readTree(json).path("data").path("invoiceCode").asText();

        // Kiểm tra số hóa đơn sinh theo mẫu 8 chữ số
        assertThat(invoiceNumber).matches("\\d{8}");
        assertThat(invoiceCode).isEqualTo("HD-" + invoiceNumber);

        // 3. Kiểm tra chặn xuất lặp đơn hàng đã xuất hóa đơn
        mockMvc.perform(post("/api/invoices")
                        .header("Authorization", "Bearer " + accountantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(issueReq)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("đã được xuất hóa đơn trước đó")));

        // 4. Kiểm tra chặn sửa và chặn xóa hóa đơn đã phát hành
        mockMvc.perform(put("/api/invoices/" + invoiceId)
                        .header("Authorization", "Bearer " + accountantToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("không được phép chỉnh sửa")));

        mockMvc.perform(delete("/api/invoices/" + invoiceId)
                        .header("Authorization", "Bearer " + accountantToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("không được phép xóa")));

        // 5. Lập chứng từ điều chỉnh (DECREASE: giảm 50,000 do hàng bị xước vỏ thùng)
        CreateInvoiceAdjustmentRequest adjReq = CreateInvoiceAdjustmentRequest.builder()
                .adjustmentType(InvoiceAdjustmentType.DECREASE)
                .adjustmentReason("Chiết khấu hỗ trợ đại lý 50,000 do 2 thùng bia bị cấn móp vỏ carton")
                .adjustmentAmount(new BigDecimal("50000.00"))
                .build();

        mockMvc.perform(post("/api/invoices/" + invoiceId + "/adjustments")
                        .header("Authorization", "Bearer " + accountantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(adjReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.adjustmentCode").isNotEmpty())
                .andExpect(jsonPath("$.data.adjustmentAmount").value(-50000.00));

        // Kiểm tra chi tiết hóa đơn sau điều chỉnh
        mockMvc.perform(get("/api/invoices/" + invoiceId)
                        .header("Authorization", "Bearer " + accountantToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ADJUSTED"))
                .andExpect(jsonPath("$.data.totalAmount").value(3850000.00)) // Tiền gốc giữ nguyên
                .andExpect(jsonPath("$.data.adjustedTotalAmount").value(3800000.00)) // Tiền sau điều chỉnh: 3,850,000 - 50,000
                .andExpect(jsonPath("$.data.adjustments").isArray());

        // 6. Tải file PDF hóa đơn thành công
        MvcResult pdfResult = mockMvc.perform(get("/api/invoices/" + invoiceId + "/pdf")
                        .header("Authorization", "Bearer " + accountantToken))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andReturn();

        byte[] pdfBytes = pdfResult.getResponse().getContentAsByteArray();
        assertThat(pdfBytes).isNotEmpty();
        assertThat(new String(pdfBytes, 0, 5)).isEqualTo("%PDF-");

        // 7. Xem trước bản in HTML
        mockMvc.perform(get("/api/invoices/" + invoiceId + "/print-preview")
                        .header("Authorization", "Bearer " + accountantToken))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("HÓA ĐƠN BÁN HÀNG")));
    }

    @Test
    @DisplayName("Kiểm thử ma trận quyền Database-Backed: Thu hồi INVOICE_CREATE trên token đang hoạt động lập tức trả 403 Forbidden")
    void revokingInvoiceCreate_ImmediatelyReturns403OnSameToken() throws Exception {
        CreateInvoiceRequest issueReq = CreateInvoiceRequest.builder()
                .dealerId(dealer.getId())
                .orderIds(List.of(order1.getId()))
                .build();

        // Lần 1: Có quyền -> Thành công
        mockMvc.perform(post("/api/invoices/preview")
                        .header("Authorization", "Bearer " + accountantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(issueReq)))
                .andExpect(status().isOk());

        // Giả lập Admin thu hồi quyền INVOICE_CREATE từ vai trò ROLE_ACCOUNTANT trên ma trận DB
        rolePermissionConfig.updateRolePermissions(ERole.ROLE_ACCOUNTANT, EnumSet.of(
                Permission.INVOICE_VIEW,
                Permission.ORDER_VIEW
        ));

        // Lần 2: Dùng lại CHÍNH TOKEN ĐANG ĐĂNG NHẬP ĐÓ gửi request -> Bị chặn 403 ngay lập tức, không fallback theo role!
        mockMvc.perform(post("/api/invoices")
                        .header("Authorization", "Bearer " + accountantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(issueReq)))
                .andExpect(status().isForbidden());

        rolePermissionConfig.updateRolePermissions(ERole.ROLE_ACCOUNTANT, EnumSet.of(
                Permission.INVOICE_VIEW, Permission.INVOICE_CREATE));
        mockMvc.perform(post("/api/invoices/preview")
                        .header("Authorization", "Bearer " + accountantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(issueReq)))
                .andExpect(status().isOk());
    }

    @Test
    void viewAndAdjustGrantRevokeApplyToSameSingleRoleToken() throws Exception {
        CreateInvoiceRequest issueReq = CreateInvoiceRequest.builder()
                .dealerId(dealer.getId()).orderIds(List.of(order1.getId())).build();
        MvcResult issued = mockMvc.perform(post("/api/invoices")
                        .header("Authorization", "Bearer " + accountantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(issueReq)))
                .andExpect(status().isOk()).andReturn();
        long invoiceId = objectMapper.readTree(issued.getResponse().getContentAsString())
                .path("data").path("id").asLong();

        rolePermissionConfig.updateRolePermissions(ERole.ROLE_ACCOUNTANT, EnumSet.of(
                Permission.INVOICE_CREATE, Permission.INVOICE_ADJUST));
        mockMvc.perform(get("/api/invoices/" + invoiceId)
                        .header("Authorization", "Bearer " + accountantToken))
                .andExpect(status().isForbidden());
        rolePermissionConfig.updateRolePermissions(ERole.ROLE_ACCOUNTANT, EnumSet.of(
                Permission.INVOICE_VIEW, Permission.INVOICE_CREATE));
        mockMvc.perform(get("/api/invoices/" + invoiceId)
                        .header("Authorization", "Bearer " + accountantToken))
                .andExpect(status().isOk());

        CreateInvoiceAdjustmentRequest adjustment = CreateInvoiceAdjustmentRequest.builder()
                .adjustmentType(InvoiceAdjustmentType.INCREASE)
                .adjustmentReason("Kiểm thử cấp/thu hồi quyền trên cùng JWT")
                .adjustmentAmount(new BigDecimal("1000")).build();
        mockMvc.perform(post("/api/invoices/" + invoiceId + "/adjustments")
                        .header("Authorization", "Bearer " + accountantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(adjustment)))
                .andExpect(status().isForbidden());
        rolePermissionConfig.updateRolePermissions(ERole.ROLE_ACCOUNTANT, EnumSet.of(
                Permission.INVOICE_VIEW, Permission.INVOICE_CREATE, Permission.INVOICE_ADJUST));
        mockMvc.perform(post("/api/invoices/" + invoiceId + "/adjustments")
                        .header("Authorization", "Bearer " + accountantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(adjustment)))
                .andExpect(status().isOk());
    }
}
