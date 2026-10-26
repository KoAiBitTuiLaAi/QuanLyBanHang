package com.quanlybanhang.integration;

import com.quanlybanhang.config.RolePermissionConfig;
import com.quanlybanhang.model.*;
import com.quanlybanhang.repository.*;
import com.quanlybanhang.security.JwtUtils;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration Test toàn trình cho S8-07:
 * "Là Quản lý kho, tôi muốn xem báo cáo tồn kho và vòng quay hàng,
 *  để phát hiện hàng chậm luân chuyển đang chôn vốn."
 *
 * Kiểm tra đầy đủ:
 * 1. Giá trị tồn theo kho và theo nhóm hàng.
 * 2. Số ngày tồn bình quân của từng SKU, chỉ ra hàng chậm luân chuyển đang chôn vốn.
 * 3. Xuất Excel (.xlsx).
 * 4. Thực thi ma trận phân quyền DB (RolePermissionConfig): thu hồi quyền REPORT_INVENTORY
 *    lập tức trả 403 trên cùng JWT đang đăng nhập, không fallback theo role; ROLE_ADMIN luôn được phép.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:s807_inventory_turnover_db;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "app.jwt.secret=s807-test-secret-at-least-thirty-two-characters",
        "app.bootstrap.admin-password=S807Test2026!",
        "app.bootstrap.admin-email=s807@example.invalid",
        "app.seed-demo-users=false",
        "app.seed-demo-data=false",
        "app.payment-reminder.enabled=false"
})
class InventoryTurnoverReportIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private RolePermissionConfig rolePermissionConfig;

    @Autowired
    private WarehouseRepository warehouseRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private WarehouseInventoryRepository inventoryRepository;

    @Autowired
    private WarehouseVoucherRepository voucherRepository;

    private Role adminRole;
    private Role warehouseManagerRole;
    private Role warehouseStaffRole;

    private String adminToken;
    private String warehouseManagerToken;
    private String warehouseStaffToken;

    private Warehouse warehouseHanoi;
    private Warehouse warehouseHcm;

    private Category categoryBeverage;
    private Category categoryConfectionery;

    private Product skuFast;
    private Product skuSlow;
    private Product skuDeadStock;

    @BeforeEach
    void setUp() {
        adminRole = ensureRole(ERole.ROLE_ADMIN);
        warehouseManagerRole = ensureRole(ERole.ROLE_WAREHOUSE_MANAGER);
        warehouseStaffRole = ensureRole(ERole.ROLE_WAREHOUSE_STAFF);

        saveRolePermissions(ERole.ROLE_ADMIN, EnumSet.allOf(Permission.class));
        saveRolePermissions(ERole.ROLE_WAREHOUSE_MANAGER, EnumSet.of(
                Permission.WAREHOUSE_VIEW,
                Permission.REPORT_INVENTORY
        ));
        saveRolePermissions(ERole.ROLE_WAREHOUSE_STAFF, EnumSet.of(
                Permission.WAREHOUSE_VIEW
        ));

        User admin = createUser("admin_s807", "admin_s807@test.local", Set.of(adminRole), Set.of());
        User warehouseManager = createUser("wh_mgr_s807", "wh_mgr_s807@test.local", Set.of(warehouseManagerRole), Set.of());
        User warehouseStaff = createUser("wh_staff_s807", "wh_staff_s807@test.local", Set.of(warehouseStaffRole), Set.of());

        adminToken = jwtUtils.generateAccessToken(admin.getUsername(), admin.getTokenVersion());
        warehouseManagerToken = jwtUtils.generateAccessToken(warehouseManager.getUsername(), warehouseManager.getTokenVersion());
        warehouseStaffToken = jwtUtils.generateAccessToken(warehouseStaff.getUsername(), warehouseStaff.getTokenVersion());

        warehouseHanoi = warehouseRepository.save(Warehouse.builder()
                .code("KHO-HN-S807")
                .name("Kho Tổng Hà Nội")
                .address("Long Biên, Hà Nội")
                .managerName("Quản lý Kho HN")
                .active(true)
                .build());

        warehouseHcm = warehouseRepository.save(Warehouse.builder()
                .code("KHO-HCM-S807")
                .name("Kho Tổng TP.HCM")
                .address("Thủ Đức, TP.HCM")
                .managerName("Quản lý Kho HCM")
                .active(true)
                .build());

        categoryBeverage = categoryRepository.save(Category.builder()
                .code("CAT-BEV-S807")
                .name("Nước giải khát")
                .level(1)
                .build());

        categoryConfectionery = categoryRepository.save(Category.builder()
                .code("CAT-CONF-S807")
                .name("Bánh kẹo cao cấp")
                .level(1)
                .build());

        // 1. SKU luân chuyển nhanh: đơn giá vốn 12,000 VND
        skuFast = productRepository.save(Product.builder()
                .code("SKU-FAST-807")
                .name("Nước khoáng thiên nhiên 500ml")
                .unit("Chai")
                .costPrice(new BigDecimal("12000"))
                .price(new BigDecimal("18000"))
                .category(categoryBeverage)
                .active(true)
                .build());

        // 2. SKU chậm luân chuyển (có xuất nhưng rất ít, DIO > 60 ngày): đơn giá vốn 50,000 VND
        skuSlow = productRepository.save(Product.builder()
                .code("SKU-SLOW-807")
                .name("Nước ép lựu nhập khẩu 1L")
                .unit("Hộp")
                .costPrice(new BigDecimal("50000"))
                .price(new BigDecimal("75000"))
                .category(categoryBeverage)
                .active(true)
                .build());

        // 3. SKU tồn đọng không xuất (Dead stock, xuất = 0 trong kỳ): đơn giá vốn 200,000 VND
        skuDeadStock = productRepository.save(Product.builder()
                .code("SKU-DEAD-807")
                .name("Hộp quà bánh quy bơ Đan Mạch")
                .unit("Hộp")
                .costPrice(new BigDecimal("200000"))
                .price(new BigDecimal("290000"))
                .category(categoryConfectionery)
                .active(true)
                .build());

        // Tồn kho hiện tại tại Kho Hà Nội:
        // - skuFast: 100 chai * 12,000 = 1,200,000 VND
        // - skuSlow: 190 hộp * 50,000 = 9,500,000 VND
        inventoryRepository.save(WarehouseInventory.builder()
                .warehouse(warehouseHanoi)
                .product(skuFast).baseUnit("Chai")
                .baseQuantity(new BigDecimal("100"))
                .reservedQuantity(new BigDecimal("10"))
                .build());

        inventoryRepository.save(WarehouseInventory.builder()
                .warehouse(warehouseHanoi)
                .product(skuSlow).baseUnit("Hộp")
                .baseQuantity(new BigDecimal("190"))
                .reservedQuantity(BigDecimal.ZERO)
                .build());

        // Tồn kho hiện tại tại Kho TP.HCM:
        // - skuDeadStock: 40 hộp * 200,000 = 8,000,000 VND
        inventoryRepository.save(WarehouseInventory.builder()
                .warehouse(warehouseHcm)
                .product(skuDeadStock).baseUnit("Hộp")
                .baseQuantity(new BigDecimal("40"))
                .reservedQuantity(BigDecimal.ZERO)
                .build());

        // Chứng từ xuất kho đã xác nhận trong kỳ 01/03/2026 - 30/03/2026 (30 ngày) tại Kho Hà Nội:
        // - skuFast: xuất 300 chai => tồn đầu = 400, tồn cuối = 100, tồn BQ = 250 => DIO = (250*30)/300 = 25 ngày (NORMAL/FAST, không chậm)
        // - skuSlow: xuất 20 hộp  => tồn đầu = 210, tồn cuối = 190, tồn BQ = 200 => DIO = (200*30)/20 = 300 ngày (>= 60 ngày => SLOW_MOVING)
        WarehouseVoucher exportVoucher = WarehouseVoucher.builder()
                .voucherCode("PX-S807-001")
                .type(WarehouseVoucherType.EXPORT)
                .status("CONFIRMED")
                .warehouse(warehouseHanoi)
                .voucherDate(LocalDateTime.of(2026, 3, 18, 14, 30)).recordedAt(LocalDateTime.of(2026, 3, 18, 14, 30))
                .createdBy("wh_mgr_s807")
                .build();

        WarehouseVoucherItem itemFast = WarehouseVoucherItem.builder()
                .voucher(exportVoucher)
                .product(skuFast).baseUnit("Chai")
                .unit("Chai")
                .quantity(new BigDecimal("300"))
                .conversionFactor(BigDecimal.ONE)
                .baseQuantity(new BigDecimal("300"))
                .build();

        WarehouseVoucherItem itemSlow = WarehouseVoucherItem.builder()
                .voucher(exportVoucher)
                .product(skuSlow).baseUnit("Hộp")
                .unit("Hộp")
                .quantity(new BigDecimal("20"))
                .conversionFactor(BigDecimal.ONE)
                .baseQuantity(new BigDecimal("20"))
                .build();

        exportVoucher.setItems(List.of(itemFast, itemSlow));
        voucherRepository.save(exportVoucher);
    }

    @Test
    @DisplayName("S8-07 AC1 & AC2: Xem báo cáo giá trị tồn theo kho, theo nhóm hàng, số ngày tồn bình quân SKU và hàng chậm luân chuyển đang chôn vốn")
    void shouldReturnInventoryValuationAndTurnoverReportWithSlowMovingDetection() throws Exception {
        mockMvc.perform(get("/api/reports/inventory-turnover")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + warehouseManagerToken)
                        .param("fromDate", "2026-03-01")
                        .param("toDate", "2026-03-30")
                        .param("slowMovingThresholdDays", "60"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.periodDays").value(30))
                .andExpect(jsonPath("$.data.slowMovingThresholdDays").value(60))
                // Tổng giá trị tồn = 1,200,000 + 9,500,000 + 8,000,000 = 18,700,000 VND
                .andExpect(jsonPath("$.data.totalInventoryValue").value(18700000.0))
                // Tổng giá trị hàng chậm luân chuyển đang chôn vốn = 9,500,000 (skuSlow) + 8,000,000 (skuDeadStock) = 17,500,000 VND
                .andExpect(jsonPath("$.data.slowMovingInventoryValue").value(17500000.0))
                .andExpect(jsonPath("$.data.slowMovingSkuCount").value(2))
                .andExpect(jsonPath("$.data.deadStockSkuCount").value(1))
                // Giá trị tồn theo kho (2 kho: Kho HN = 10,700,000; Kho HCM = 8,000,000)
                .andExpect(jsonPath("$.data.warehouseValuations", hasSize(2)))
                .andExpect(jsonPath("$.data.warehouseValuations[0].warehouseCode").value("KHO-HN-S807"))
                .andExpect(jsonPath("$.data.warehouseValuations[0].inventoryValue").value(10700000.0))
                .andExpect(jsonPath("$.data.warehouseValuations[0].slowMovingInventoryValue").value(9500000.0))
                .andExpect(jsonPath("$.data.warehouseValuations[1].warehouseCode").value("KHO-HCM-S807"))
                .andExpect(jsonPath("$.data.warehouseValuations[1].inventoryValue").value(8000000.0))
                .andExpect(jsonPath("$.data.warehouseValuations[1].slowMovingInventoryValue").value(8000000.0))
                // Giá trị tồn theo nhóm hàng (Nước giải khát = 10,700,000; Bánh kẹo cao cấp = 8,000,000)
                .andExpect(jsonPath("$.data.categoryValuations", hasSize(2)))
                .andExpect(jsonPath("$.data.categoryValuations[0].categoryCode").value("CAT-BEV-S807"))
                .andExpect(jsonPath("$.data.categoryValuations[0].inventoryValue").value(10700000.0))
                .andExpect(jsonPath("$.data.categoryValuations[0].slowMovingInventoryValue").value(9500000.0))
                .andExpect(jsonPath("$.data.categoryValuations[1].categoryCode").value("CAT-CONF-S807"))
                .andExpect(jsonPath("$.data.categoryValuations[1].inventoryValue").value(8000000.0))
                .andExpect(jsonPath("$.data.categoryValuations[1].slowMovingInventoryValue").value(8000000.0))
                // Danh sách hàng chậm luân chuyển đang chôn vốn (sắp xếp giảm dần theo vốn chôn)
                .andExpect(jsonPath("$.data.slowMovingItems", hasSize(2)))
                .andExpect(jsonPath("$.data.slowMovingItems[0].productCode").value("SKU-SLOW-807"))
                .andExpect(jsonPath("$.data.slowMovingItems[0].averageDaysInInventory").value(300.0))
                .andExpect(jsonPath("$.data.slowMovingItems[0].tiedUpCapitalValue").value(9500000.0))
                .andExpect(jsonPath("$.data.slowMovingItems[0].slowMoving").value(true))
                .andExpect(jsonPath("$.data.slowMovingItems[1].productCode").value("SKU-DEAD-807"))
                .andExpect(jsonPath("$.data.slowMovingItems[1].turnoverStatus").value("DEAD_STOCK"))
                .andExpect(jsonPath("$.data.slowMovingItems[1].tiedUpCapitalValue").value(8000000.0))
                .andExpect(jsonPath("$.data.slowMovingItems[1].slowMoving").value(true));
    }

    @Test
    @DisplayName("S8-07 AC3: Xuất Excel (.xlsx) báo cáo tồn kho và vòng quay hàng trả về file hợp lệ gồm 4 sheet")
    void shouldExportInventoryTurnoverReportToExcel() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/reports/inventory-turnover/export")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + warehouseManagerToken)
                        .param("fromDate", "2026-03-01")
                        .param("toDate", "2026-03-30")
                        .param("slowMovingThresholdDays", "60"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE,
                        containsString("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        containsString("Bao-cao-ton-kho-vong-quay_TatCaKho_2026-03-01_2026-03-30.xlsx")))
                .andReturn();

        byte[] excelBytes = result.getResponse().getContentAsByteArray();
        assertThat(excelBytes).isNotEmpty();

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(excelBytes))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(4);
            assertThat(workbook.getSheetName(0)).isEqualTo("Giá trị tồn theo kho");
            assertThat(workbook.getSheetName(1)).isEqualTo("Giá trị tồn theo nhóm hàng");
            assertThat(workbook.getSheetName(2)).isEqualTo("Vòng quay & Số ngày tồn SKU");
            assertThat(workbook.getSheetName(3)).isEqualTo("Hàng chậm luân chuyển");
        }
    }

    @Test
    @DisplayName("S8-07 Permission Matrix: Thu hồi quyền REPORT_INVENTORY khỏi ROLE_WAREHOUSE_MANAGER phải lập tức trả 403 trên cùng JWT, cấp lại trả 200")
    void revokingReportInventoryPermissionFromDatabaseImmediatelyReturns403WithoutRoleFallback() throws Exception {
        // 1. Ban đầu ROLE_WAREHOUSE_MANAGER có REPORT_INVENTORY -> 200 OK
        mockMvc.perform(get("/api/reports/inventory-turnover")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + warehouseManagerToken))
                .andExpect(status().isOk());

        // 2. ROLE_WAREHOUSE_STAFF không có REPORT_INVENTORY -> 403 Forbidden
        mockMvc.perform(get("/api/reports/inventory-turnover")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + warehouseStaffToken))
                .andExpect(status().isForbidden());

        // 3. Thu hồi quyền REPORT_INVENTORY khỏi ROLE_WAREHOUSE_MANAGER trong DB
        saveRolePermissions(ERole.ROLE_WAREHOUSE_MANAGER, EnumSet.of(Permission.WAREHOUSE_VIEW));

        // Gọi lại với cùng JWT của Quản lý kho -> lập tức 403 Forbidden trên mọi endpoint S8-07
        mockMvc.perform(get("/api/reports/inventory-turnover")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + warehouseManagerToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/reports/inventory-turnover/by-warehouse")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + warehouseManagerToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/reports/inventory-turnover/by-category")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + warehouseManagerToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/reports/inventory-turnover/skus")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + warehouseManagerToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/reports/inventory-turnover/slow-moving")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + warehouseManagerToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/reports/inventory-turnover/export")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + warehouseManagerToken))
                .andExpect(status().isForbidden());

        // 4. ROLE_ADMIN luôn có quyền truy cập ngay cả khi bảng cấu hình thay đổi
        mockMvc.perform(get("/api/reports/inventory-turnover")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());

        // 5. Cấp lại REPORT_INVENTORY cho ROLE_WAREHOUSE_MANAGER -> 200 OK trở lại
        saveRolePermissions(ERole.ROLE_WAREHOUSE_MANAGER, EnumSet.of(
                Permission.WAREHOUSE_VIEW,
                Permission.REPORT_INVENTORY
        ));

        mockMvc.perform(get("/api/reports/inventory-turnover")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + warehouseManagerToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("S8-07 Data Scope: Quản lý kho được phân công kho cụ thể chỉ xem được kho trong phạm vi và bị chặn 403 khi truy vấn kho khác")
    void warehouseManagerAssignedToSpecificWarehouseOnlySeesAssignedScope() throws Exception {
        User hanoiOnlyManager = createUser(
                "wh_mgr_hanoi_only",
                "wh_mgr_hanoi_only@test.local",
                Set.of(warehouseManagerRole),
                Set.of(warehouseHanoi)
        );
        String hanoiManagerToken = jwtUtils.generateAccessToken(hanoiOnlyManager.getUsername(), hanoiOnlyManager.getTokenVersion());

        // Khi không truyền warehouseId, hệ thống tự động giới hạn trong Kho Hà Nội
        mockMvc.perform(get("/api/reports/inventory-turnover")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + hanoiManagerToken)
                        .param("fromDate", "2026-03-01")
                        .param("toDate", "2026-03-30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.warehouseValuations", hasSize(1)))
                .andExpect(jsonPath("$.data.warehouseValuations[0].warehouseCode").value("KHO-HN-S807"))
                .andExpect(jsonPath("$.data.totalInventoryValue").value(10700000.0));

        // Khi cố tình truyền warehouseId của Kho TP.HCM (ngoài phạm vi) -> trả về 403 Forbidden
        mockMvc.perform(get("/api/reports/inventory-turnover")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + hanoiManagerToken)
                        .param("warehouseId", String.valueOf(warehouseHcm.getId()))
                        .param("fromDate", "2026-03-01")
                        .param("toDate", "2026-03-30"))
                .andExpect(status().isForbidden());
    }

    private Role ensureRole(ERole roleEnum) {
        return roleRepository.findByName(roleEnum)
                .orElseGet(() -> roleRepository.save(Role.builder().name(roleEnum).build()));
    }

    private void saveRolePermissions(ERole roleEnum, Set<Permission> permissions) {
        rolePermissionConfig.updateRolePermissions(roleEnum, permissions);
    }

    private User createUser(String username, String email, Set<Role> roles, Set<Warehouse> warehouses) {
        return userRepository.saveAndFlush(User.builder()
                .username(username)
                .email(email)
                .fullName("Test User " + username)
                .password(passwordEncoder.encode("Password@123"))
                .active(true)
                .roles(roles)
                .warehouses(warehouses)
                .build());
    }
}
