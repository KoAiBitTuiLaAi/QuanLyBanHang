package com.quanlybanhang.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanlybanhang.config.RolePermissionConfig;
import com.quanlybanhang.model.*;
import com.quanlybanhang.repository.*;
import com.quanlybanhang.security.JwtUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration test cho S8-04: Đặt chỉ tiêu doanh số theo tháng cho từng nhân viên & khu vực,
 * theo dõi tiến độ thời gian thực ngay giữa tháng, khoá chỉ tiêu qua kỳ, sửa bắt buộc có lý do & ghi nhật ký,
 * và kiểm tra chặt chẽ ma trận phân quyền động (RolePermissionConfig).
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:sales_target_s804_test;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.show-sql=false",
        "app.jwt.secret=sales-target-s804-secret-key-at-least-32-chars",
        "app.bootstrap.admin-password=SalesTarget2026!",
        "app.bootstrap.admin-email=admin-s804@example.invalid",
        "app.seed-demo-users=false",
        "app.seed-demo-data=false"
})
@AutoConfigureMockMvc
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SalesTargetIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private TerritoryRepository territoryRepository;
    @Autowired
    private DealerRepository dealerRepository;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private SalesTargetRepository salesTargetRepository;
    @Autowired
    private RolePermissionConfig rolePermissionConfig;
    @Autowired
    private JwtUtils jwtUtils;

    private User managerUser;
    private User salesUser1;
    private User salesUser2;
    private Territory territorySouth;
    private Territory territoryNorth;
    private Dealer dealerSouth1;
    private Dealer dealerNorth2;

    private String adminToken;
    private String managerToken;
    private String sales1Token;
    private String sales2Token;

    @BeforeEach
    void setUp() {
        rolePermissionConfig.updateRolePermissions(ERole.ROLE_SALES_MANAGER,
                Set.of(Permission.ORDER_APPROVE, Permission.REPORT_SALES, Permission.ORDER_VIEW));
        rolePermissionConfig.updateRolePermissions(ERole.ROLE_SALES,
                Set.of(Permission.REPORT_SALES, Permission.ORDER_VIEW, Permission.ORDER_CREATE));

        Role managerRole = roleRepository.findByName(ERole.ROLE_SALES_MANAGER).orElseThrow();
        Role salesRole = roleRepository.findByName(ERole.ROLE_SALES).orElseThrow();

        territorySouth = territoryRepository.saveAndFlush(Territory.builder()
                .code("KV-MN-S804")
                .name("Khu vực Miền Nam")
                .active(true)
                .build());

        territoryNorth = territoryRepository.saveAndFlush(Territory.builder()
                .code("KV-MB-S804")
                .name("Khu vực Miền Bắc")
                .active(true)
                .build());

        managerUser = userRepository.saveAndFlush(User.builder()
                .username("manager_s804")
                .fullName("Quản lý Kinh doanh S804")
                .email("manager_s804@example.invalid")
                .password("unused")
                .active(true)
                .roles(new HashSet<>(Set.of(managerRole)))
                .build());

        salesUser1 = userRepository.saveAndFlush(User.builder()
                .username("sales1_s804")
                .fullName("Nhân viên KD 1")
                .email("sales1_s804@example.invalid")
                .password("unused")
                .active(true)
                .roles(new HashSet<>(Set.of(salesRole)))
                .territories(new HashSet<>(Set.of(territorySouth)))
                .build());

        salesUser2 = userRepository.saveAndFlush(User.builder()
                .username("sales2_s804")
                .fullName("Nhân viên KD 2")
                .email("sales2_s804@example.invalid")
                .password("unused")
                .active(true)
                .roles(new HashSet<>(Set.of(salesRole)))
                .territories(new HashSet<>(Set.of(territoryNorth)))
                .build());

        dealerSouth1 = dealerRepository.saveAndFlush(Dealer.builder()
                .code("DL-MN-01")
                .name("Đại lý Miền Nam 01")
                .assignedUser(salesUser1)
                .territory(territorySouth)
                .build());

        dealerNorth2 = dealerRepository.saveAndFlush(Dealer.builder()
                .code("DL-MB-02")
                .name("Đại lý Miền Bắc 02")
                .assignedUser(salesUser2)
                .territory(territoryNorth)
                .build());

        adminToken = jwtUtils.generateAccessToken("admin", 0L);
        managerToken = jwtUtils.generateAccessToken(managerUser.getUsername(), 0L);
        sales1Token = jwtUtils.generateAccessToken(salesUser1.getUsername(), 0L);
        sales2Token = jwtUtils.generateAccessToken(salesUser2.getUsername(), 0L);
    }

    @Test
    void fullFlow_createTargets_realTimeOrderProgress_andEmployeeSelfTracking() throws Exception {
        YearMonth currentYm = YearMonth.now();
        int year = currentYm.getYear();
        int month = currentYm.getMonthValue();

        // 1. Quản lý đặt chỉ tiêu tháng cho Nhân viên 1 (100 triệu)
        String empTargetPayload = objectMapper.writeValueAsString(Map.of(
                "year", year,
                "month", month,
                "userId", salesUser1.getId(),
                "targetAmount", 100000000,
                "note", "Chỉ tiêu cá nhân tháng này"
        ));

        String empTargetRespStr = mockMvc.perform(post("/api/sales-targets")
                        .header("Authorization", "Bearer " + managerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(empTargetPayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.scopeType").value("EMPLOYEE"))
                .andExpect(jsonPath("$.userId").value(salesUser1.getId()))
                .andExpect(jsonPath("$.targetAmount").value(100000000))
                .andExpect(jsonPath("$.actualAmount").value(0))
                .andExpect(jsonPath("$.locked").value(false))
                .andReturn().getResponse().getContentAsString();

        Long empTargetId = objectMapper.readTree(empTargetRespStr).path("id").asLong();

        // 2. Quản lý đặt chỉ tiêu tháng cho Khu vực Miền Nam (200 triệu)
        String territoryTargetPayload = objectMapper.writeValueAsString(Map.of(
                "year", year,
                "month", month,
                "territoryId", territorySouth.getId(),
                "targetAmount", 200000000,
                "note", "Chỉ tiêu Khu vực Miền Nam"
        ));

        mockMvc.perform(post("/api/sales-targets")
                        .header("Authorization", "Bearer " + managerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(territoryTargetPayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.scopeType").value("TERRITORY"))
                .andExpect(jsonPath("$.territoryId").value(territorySouth.getId()));

        // 3. Đặt thêm chỉ tiêu kết hợp Nhân viên 1 + Khu vực Miền Nam (80 triệu)
        String combinedPayload = objectMapper.writeValueAsString(Map.of(
                "year", year,
                "month", month,
                "userId", salesUser1.getId(),
                "territoryId", territorySouth.getId(),
                "targetAmount", 80000000
        ));

        mockMvc.perform(post("/api/sales-targets")
                        .header("Authorization", "Bearer " + managerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(combinedPayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.scopeType").value("EMPLOYEE_TERRITORY"));

        // 4. Phát sinh đơn hàng hợp lệ (DELIVERED: 60 triệu) và đơn nháp (DRAFT: 50 triệu - không tính)
        orderRepository.saveAndFlush(Order.builder()
                .orderCode("DH-S804-VALID-1")
                .dealer(dealerSouth1)
                .status(OrderStatus.DELIVERED.name())
                .totalAmount(new BigDecimal("60000000"))
                .recordedAt(LocalDateTime.now())
                .build());

        orderRepository.saveAndFlush(Order.builder()
                .orderCode("DH-S804-DRAFT-1")
                .dealer(dealerSouth1)
                .status(OrderStatus.DRAFT.name())
                .totalAmount(new BigDecimal("50000000"))
                .recordedAt(LocalDateTime.now())
                .build());

        // 5. Nhân viên 1 xem tiến độ của chính mình theo thời gian thực ngay giữa tháng
        mockMvc.perform(get("/api/sales-targets/my-progress")
                        .param("year", String.valueOf(year))
                        .param("month", String.valueOf(month))
                        .header("Authorization", "Bearer " + sales1Token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(salesUser1.getId()))
                .andExpect(jsonPath("$.totalTargetAmount").value(100000000))
                .andExpect(jsonPath("$.actualAmount").value(60000000))
                .andExpect(jsonPath("$.remainingAmount").value(40000000))
                .andExpect(jsonPath("$.completionRate").value(60.0))
                .andExpect(jsonPath("$.orderCount").value(1))
                .andExpect(jsonPath("$.expectedAmountToDate").isNumber())
                .andExpect(jsonPath("$.pacingVarianceAmount").isNumber())
                .andExpect(jsonPath("$.requiredDailyRunRate").isNumber())
                .andExpect(jsonPath("$.progressStatus").isNotEmpty());

        // 6. Phát sinh thêm đơn hàng APPROVED (50 triệu) -> tổng thực đạt = 110 triệu (vượt mục tiêu 100 triệu = 110%)
        orderRepository.saveAndFlush(Order.builder()
                .orderCode("DH-S804-VALID-2")
                .dealer(dealerSouth1)
                .status(OrderStatus.APPROVED.name())
                .totalAmount(new BigDecimal("50000000"))
                .recordedAt(LocalDateTime.now())
                .build());

        mockMvc.perform(get("/api/sales-targets/" + empTargetId)
                        .header("Authorization", "Bearer " + sales1Token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actualAmount").value(110000000))
                .andExpect(jsonPath("$.remainingAmount").value(0))
                .andExpect(jsonPath("$.overachievedAmount").value(10000000))
                .andExpect(jsonPath("$.completionRate").value(110.0))
                .andExpect(jsonPath("$.progressStatus").value("ACHIEVED"));

        // 7. Nhân viên 1 không được xem chỉ tiêu riêng của Nhân viên 2 (RLS 403)
        mockMvc.perform(get("/api/sales-targets")
                        .param("year", String.valueOf(year))
                        .param("month", String.valueOf(month))
                        .param("userId", String.valueOf(salesUser2.getId()))
                        .header("Authorization", "Bearer " + sales1Token))
                .andExpect(status().isForbidden());
    }

    @Test
    void pastPeriodTargets_areAutomaticallyLocked_requireReasonToModify_andRecordAuditLogs() throws Exception {
        YearMonth pastYm = YearMonth.now().minusMonths(1);
        int pastYear = pastYm.getYear();
        int pastMonth = pastYm.getMonthValue();

        // Giả lập một chỉ tiêu kỳ trước trong DB chưa gắn cờ locked (ví dụ vừa bước sang tháng mới)
        SalesTarget pastTarget = salesTargetRepository.saveAndFlush(SalesTarget.builder()
                .targetCode("CT-PAST-001")
                .scopeKey(SalesTarget.buildScopeKey(pastYear, pastMonth, salesUser1.getId(), null))
                .targetYear(pastYear)
                .targetMonth(pastMonth)
                .scopeType(SalesTargetScopeType.EMPLOYEE)
                .user(salesUser1)
                .targetAmount(new BigDecimal("90000000"))
                .locked(false)
                .createdBy("manager_s804")
                .build());

        // 1. Khi truy vấn hoặc sửa, hệ thống nhận diện chỉ tiêu đã qua kỳ (locked = true)
        mockMvc.perform(get("/api/sales-targets/" + pastTarget.getId())
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pastPeriod").value(true))
                .andExpect(jsonPath("$.locked").value(true));

        // 2. Thử sửa chỉ tiêu đã qua kỳ mà KHÔNG có lý do -> trả về 400 Bad Request
        String updateWithoutReason = objectMapper.writeValueAsString(Map.of(
                "targetAmount", 95000000,
                "note", "Sửa không lý do"
        ));

        mockMvc.perform(put("/api/sales-targets/" + pastTarget.getId())
                        .header("Authorization", "Bearer " + managerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateWithoutReason))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("bắt buộc phải nhập lý do")));

        // 3. Thử xóa chỉ tiêu đã qua kỳ / đã khóa -> trả về 400 Bad Request
        mockMvc.perform(delete("/api/sales-targets/" + pastTarget.getId())
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isBadRequest());

        // 4. Sửa chỉ tiêu đã qua kỳ CÓ lý do hợp lệ -> 200 OK và ghi nhật ký AuditLog
        String updateWithReason = objectMapper.writeValueAsString(Map.of(
                "targetAmount", 95000000,
                "note", "Điều chỉnh sau chốt sổ",
                "reason", "Cập nhật lại chỉ tiêu tháng trước theo biên bản đối chiếu doanh số"
        ));

        mockMvc.perform(put("/api/sales-targets/" + pastTarget.getId())
                        .header("Authorization", "Bearer " + managerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateWithReason))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetAmount").value(95000000))
                .andExpect(jsonPath("$.locked").value(true))
                .andExpect(jsonPath("$.lastModifyReason").value("Cập nhật lại chỉ tiêu tháng trước theo biên bản đối chiếu doanh số"))
                .andExpect(jsonPath("$.auditLogs").isArray());

        // 5. Kiểm tra endpoint lịch sử chỉnh sửa (AuditLog) của chỉ tiêu
        byte[] logsBytes = mockMvc.perform(get("/api/sales-targets/" + pastTarget.getId() + "/audit-logs")
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        JsonNode logsNode = objectMapper.readTree(logsBytes);
        assertThat(logsNode.isArray()).isTrue();
        assertThat(logsNode.size()).isGreaterThanOrEqualTo(1);
        assertThat(logsNode.get(0).path("action").asText()).isEqualTo("UPDATE_LOCKED_TARGET");
        assertThat(logsNode.get(0).path("reason").asText())
                .isEqualTo("Cập nhật lại chỉ tiêu tháng trước theo biên bản đối chiếu doanh số");
        assertThat(logsNode.get(0).path("oldValue").asText()).contains("90000000.00");
        assertThat(logsNode.get(0).path("newValue").asText()).contains("95000000.00");
    }

    @Test
    void permissionMatrixEnforcement_revokingPermissionsTakesEffectImmediatelyOnSameJwt() throws Exception {
        YearMonth ym = YearMonth.now();
        String createPayload = objectMapper.writeValueAsString(Map.of(
                "year", ym.getYear(),
                "month", ym.getMonthValue(),
                "userId", salesUser1.getId(),
                "targetAmount", 100000000
        ));

        // 1. Chưa đăng nhập -> 401 Unauthorized
        mockMvc.perform(get("/api/sales-targets"))
                .andExpect(status().isUnauthorized());

        // 2. Nhân viên Sales mặc định chỉ có REPORT_SALES (không có ORDER_APPROVE) -> POST bị 403 Forbidden
        mockMvc.perform(post("/api/sales-targets")
                        .header("Authorization", "Bearer " + sales1Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPayload))
                .andExpect(status().isForbidden());

        // 3. Quản lý KD mặc định có ORDER_APPROVE -> POST thành công (201)
        mockMvc.perform(post("/api/sales-targets")
                        .header("Authorization", "Bearer " + managerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPayload))
                .andExpect(status().isCreated());

        // 4. Thu hồi quyền ORDER_APPROVE khỏi ROLE_SALES_MANAGER trên ma trận quyền -> cùng JWT managerToken bị 403 ngay lập tức
        rolePermissionConfig.updateRolePermissions(ERole.ROLE_SALES_MANAGER, Set.of(Permission.REPORT_SALES));

        mockMvc.perform(post("/api/sales-targets")
                        .header("Authorization", "Bearer " + managerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPayload))
                .andExpect(status().isForbidden());

        // Nhưng vẫn xem được vì còn REPORT_SALES
        mockMvc.perform(get("/api/sales-targets")
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isOk());

        // 5. Thu hồi luôn REPORT_SALES khỏi ROLE_SALES_MANAGER và ROLE_SALES -> cả hai đều bị 403 khi xem
        rolePermissionConfig.updateRolePermissions(ERole.ROLE_SALES_MANAGER, Set.of());
        rolePermissionConfig.updateRolePermissions(ERole.ROLE_SALES, Set.of());

        mockMvc.perform(get("/api/sales-targets")
                        .header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/sales-targets/my-progress")
                        .header("Authorization", "Bearer " + sales1Token))
                .andExpect(status().isForbidden());

        // 6. ROLE_ADMIN luôn có toàn quyền bất kể cấu hình ma trận của các role khác
        mockMvc.perform(get("/api/sales-targets")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        // 7. Cấp lại REPORT_SALES cho ROLE_SALES -> sales1Token truy cập lại được ngay lập tức (200 OK)
        rolePermissionConfig.updateRolePermissions(ERole.ROLE_SALES, Set.of(Permission.REPORT_SALES));
        mockMvc.perform(get("/api/sales-targets/my-progress")
                        .header("Authorization", "Bearer " + sales1Token))
                .andExpect(status().isOk());
    }
}
