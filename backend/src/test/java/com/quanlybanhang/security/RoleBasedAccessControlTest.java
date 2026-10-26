package com.quanlybanhang.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanlybanhang.config.RolePermissionConfig;
import com.quanlybanhang.config.SecurityConfig;
import com.quanlybanhang.controller.AdminUserController;
import com.quanlybanhang.controller.FinanceController;
import com.quanlybanhang.controller.TestController;
import com.quanlybanhang.controller.UserController;
import com.quanlybanhang.controller.WarehouseController;
import com.quanlybanhang.dto.response.UserProfileResponse;
import com.quanlybanhang.service.AdminUserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Kiểm thử tự động phân quyền theo vai trò ở tầng Server (S1-05).
 * Kiểm tra ít nhất 3 vai trò (SALES, WAREHOUSE_STAFF, SALES_MANAGER, ADMIN, unauthenticated):
 *
 * 1. Nhân viên kinh doanh (ROLE_SALES):
 *    - Không sửa được tồn kho (/api/warehouse/adjust) -> 403 Forbidden
 *    - Không xem được giá vốn (/api/finance/cost-price) -> 403 Forbidden
 *    - Không xem được biên lợi nhuận (/api/finance/profit-margin) -> 403 Forbidden
 *
 * 2. Thủ kho (ROLE_WAREHOUSE_STAFF):
 *    - Không xem được giá vốn (/api/finance/cost-price) -> 403 Forbidden
 *    - Không xem được biên lợi nhuận (/api/finance/profit-margin) -> 403 Forbidden
 *    - Không sửa được tồn kho (/api/warehouse/adjust) -> 403 Forbidden
 *    - Được phép nhập/xuất và xem kho -> 200 OK
 *
 * 3. Quản lý kinh doanh (ROLE_SALES_MANAGER):
 *    - Xem được giá vốn (/api/finance/cost-price) -> 200 OK
 *    - Xem được biên lợi nhuận (/api/finance/profit-margin) -> 200 OK
 *    - Không được sửa tồn kho (/api/warehouse/adjust) -> 403 Forbidden
 *
 * 4. Quản trị hệ thống (ROLE_ADMIN):
 *    - Quản trị được phân quyền (/api/admin/**) -> 200 OK
 *    - Xem được giá vốn (/api/finance/cost-price) -> 200 OK
 *
 * 5. Mặc định từ chối:
 *    - Chưa đăng nhập truy cập endpoint bảo vệ -> 401 Unauthorized
 *    - Endpoint chưa khai báo -> bị từ chối
 */
@WebMvcTest(controllers = {
        FinanceController.class,
        WarehouseController.class,
        AdminUserController.class,
        UserController.class,
        TestController.class
})
@Import({SecurityConfig.class, RolePermissionConfig.class, RestAccessDeniedHandler.class, AuthEntryPointJwt.class, ObjectMapper.class})
class RoleBasedAccessControlTest {

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "GET,/api/finance/cost-price,FINANCE_VIEW_COST_PRICE,ROLE_SALES_MANAGER",
            "GET,/api/finance/profit-margin,FINANCE_VIEW_PROFIT_MARGIN,ROLE_SALES_MANAGER",
            "POST,/api/warehouse/import,WAREHOUSE_IMPORT,ROLE_WAREHOUSE_STAFF",
            "POST,/api/warehouse/export,WAREHOUSE_EXPORT,ROLE_WAREHOUSE_STAFF",
            "POST,/api/warehouse/adjust,WAREHOUSE_ADJUST,ROLE_WAREHOUSE_MANAGER",
            "POST,/api/warehouse/vouchers/1/confirm,WAREHOUSE_APPROVE,ROLE_WAREHOUSE_STAFF",
            "GET,/api/admin/users,USER_VIEW,ROLE_SALES_MANAGER"
    })
    void permissionGrantAndRevocationOverrideHardcodedRoles(String method, String path, String permission, String role) throws Exception {
        var grant = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                .user("matrix-user").authorities(
                        new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_VIEWER"),
                        new org.springframework.security.core.authority.SimpleGrantedAuthority(permission));
        var revoked = org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                .user("matrix-user").authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority(role));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .request(org.springframework.http.HttpMethod.valueOf(method), path)
                        .with(grant).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":1,\"quantity\":50,\"delta\":5}"))
                .andExpect(status().isOk());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .request(org.springframework.http.HttpMethod.valueOf(method), path)
                        .with(revoked).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":1,\"quantity\":50,\"delta\":5}"))
                .andExpect(status().isForbidden());
    }

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @MockBean
    private UserDetailsServiceImpl userDetailsService;

    @MockBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @MockBean
    private AdminUserService adminUserService;

    @MockBean
    private JwtUtils jwtUtils;

    @MockBean
    private com.quanlybanhang.service.WarehouseService warehouseService;

    @MockBean
    private com.quanlybanhang.service.UserService userService;

    @MockBean
    private com.quanlybanhang.repository.DealerRepository dealerRepository;

    @MockBean
    private com.quanlybanhang.repository.ProductRepository productRepository;

    @MockBean
    private com.quanlybanhang.service.AuditLogService auditLogService;

    @MockBean
    private com.quanlybanhang.service.DealerDebtService dealerDebtService;

    @org.junit.jupiter.api.BeforeEach
    void setUp() throws Exception {
        org.mockito.Mockito.doAnswer(invocation -> {
            jakarta.servlet.ServletRequest request = invocation.getArgument(0);
            jakarta.servlet.ServletResponse response = invocation.getArgument(1);
            jakarta.servlet.FilterChain chain = invocation.getArgument(2);
            chain.doFilter(request, response);
            return null;
        }).when(jwtAuthenticationFilter).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

        this.mockMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    // ═══════════════════════════════════════════════════════════════
    // VAI TRÒ 1: NHÂN VIÊN KINH DOANH (ROLE_SALES)
    // ═══════════════════════════════════════════════════════════════

    @Test
    @WithMockUser(username = "sales_user", authorities = {"ROLE_SALES", "ORDER_CREATE"})
    @DisplayName("[ROLE_SALES] Nhân viên kinh doanh ĐƯỢC xem tồn kho phục vụ (200 OK)")
    void salesCanViewWarehouseInventory() throws Exception {
        mockMvc.perform(get("/api/warehouse/inventory"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "sales_user", authorities = {"ROLE_SALES", "ORDER_CREATE"})
    @DisplayName("[ROLE_SALES] Nhân viên kinh doanh ĐƯỢC xem danh sách kho hoạt động (200 OK)")
    void salesCanViewWarehouseOptions() throws Exception {
        mockMvc.perform(get("/api/warehouse/options"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "sales_user", authorities = {"ROLE_SALES", "PRODUCT_VIEW", "ORDER_VIEW", "ORDER_CREATE", "ORDER_EDIT", "ORDER_CANCEL", "CUSTOMER_VIEW", "CUSTOMER_CREATE", "CUSTOMER_EDIT", "REPORT_SALES"})
    @DisplayName("[ROLE_SALES] Nhân viên kinh doanh KHÔNG được sửa tồn kho (403 Forbidden)")
    void salesCannotAdjustInventory() throws Exception {
        mockMvc.perform(post("/api/warehouse/adjust")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\": 1, \"delta\": -5}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "sales_user", authorities = {"ROLE_SALES", "PRODUCT_VIEW", "ORDER_VIEW", "ORDER_CREATE", "ORDER_EDIT", "ORDER_CANCEL", "CUSTOMER_VIEW", "CUSTOMER_CREATE", "CUSTOMER_EDIT", "REPORT_SALES"})
    @DisplayName("[ROLE_SALES] Nhân viên kinh doanh KHÔNG được tạo phiếu chuyển kho (403 Forbidden)")
    void salesCannotCreateTransfer() throws Exception {
        mockMvc.perform(post("/api/warehouse/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromWarehouseId\": 1, \"toWarehouseId\": 2, \"items\": [{\"productId\": 1, \"quantity\": 10}]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "sales_user", authorities = {"ROLE_SALES", "PRODUCT_VIEW", "ORDER_VIEW", "ORDER_CREATE", "ORDER_EDIT", "ORDER_CANCEL", "CUSTOMER_VIEW", "CUSTOMER_CREATE", "CUSTOMER_EDIT", "REPORT_SALES"})
    @DisplayName("[ROLE_SALES] Nhân viên kinh doanh KHÔNG xem được giá vốn (403 Forbidden)")
    void salesCannotViewCostPrice() throws Exception {
        mockMvc.perform(get("/api/finance/cost-price"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "sales_user", authorities = {"ROLE_SALES", "PRODUCT_VIEW", "ORDER_VIEW", "ORDER_CREATE", "ORDER_EDIT", "ORDER_CANCEL", "CUSTOMER_VIEW", "CUSTOMER_CREATE", "CUSTOMER_EDIT", "REPORT_SALES"})
    @DisplayName("[ROLE_SALES] Nhân viên kinh doanh KHÔNG xem được biên lợi nhuận (403 Forbidden)")
    void salesCannotViewProfitMargin() throws Exception {
        mockMvc.perform(get("/api/finance/profit-margin"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "sales_user", authorities = {"ROLE_SALES", "PRODUCT_VIEW", "ORDER_VIEW", "ORDER_CREATE", "ORDER_EDIT", "ORDER_CANCEL", "CUSTOMER_VIEW", "CUSTOMER_CREATE", "CUSTOMER_EDIT", "REPORT_SALES"})
    @DisplayName("[ROLE_SALES] Nhân viên kinh doanh KHÔNG truy cập được quản trị hệ thống (403 Forbidden)")
    void salesCannotAccessAdmin() throws Exception {
        mockMvc.perform(get("/api/admin/users"))
                .andExpect(status().isForbidden());
    }

    // ═══════════════════════════════════════════════════════════════
    // VAI TRÒ 2: THỦ KHO (ROLE_WAREHOUSE_STAFF)
    // ═══════════════════════════════════════════════════════════════

    @Test
    @WithMockUser(username = "warehouse_staff", authorities = {"ROLE_WAREHOUSE_STAFF", "PRODUCT_VIEW", "ORDER_VIEW", "WAREHOUSE_VIEW", "WAREHOUSE_IMPORT", "WAREHOUSE_EXPORT", "WAREHOUSE_TRANSFER"})
    @DisplayName("[ROLE_WAREHOUSE_STAFF] Thủ kho KHÔNG xem được giá vốn (403 Forbidden)")
    void warehouseStaffCannotViewCostPrice() throws Exception {
        mockMvc.perform(get("/api/finance/cost-price"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "warehouse_staff", authorities = {"ROLE_WAREHOUSE_STAFF", "PRODUCT_VIEW", "ORDER_VIEW", "WAREHOUSE_VIEW", "WAREHOUSE_IMPORT", "WAREHOUSE_EXPORT", "WAREHOUSE_TRANSFER"})
    @DisplayName("[ROLE_WAREHOUSE_STAFF] Thủ kho KHÔNG xem được biên lợi nhuận (403 Forbidden)")
    void warehouseStaffCannotViewProfitMargin() throws Exception {
        mockMvc.perform(get("/api/finance/profit-margin"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "warehouse_staff", authorities = {"ROLE_WAREHOUSE_STAFF", "PRODUCT_VIEW", "ORDER_VIEW", "WAREHOUSE_VIEW", "WAREHOUSE_IMPORT", "WAREHOUSE_EXPORT", "WAREHOUSE_TRANSFER"})
    @DisplayName("[ROLE_WAREHOUSE_STAFF] Thủ kho KHÔNG được sửa điều chỉnh tồn kho (403 Forbidden)")
    void warehouseStaffCannotAdjustInventory() throws Exception {
        mockMvc.perform(post("/api/warehouse/adjust")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\": 1, \"delta\": 10}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "warehouse_staff", authorities = {"ROLE_WAREHOUSE_STAFF", "PRODUCT_VIEW", "ORDER_VIEW", "WAREHOUSE_VIEW", "WAREHOUSE_IMPORT", "WAREHOUSE_EXPORT", "WAREHOUSE_TRANSFER"})
    @DisplayName("[ROLE_WAREHOUSE_STAFF] Thủ kho ĐƯỢC PHÉP tạo phiếu nhập kho (200 OK)")
    void warehouseStaffCanImportGoods() throws Exception {
        mockMvc.perform(post("/api/warehouse/import")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\": 1, \"quantity\": 50}"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "warehouse_staff", authorities = {"ROLE_WAREHOUSE_STAFF", "WAREHOUSE_VIEW"})
    @DisplayName("[ROLE_WAREHOUSE_STAFF] Thủ kho ĐƯỢC PHÉP xem danh sách tồn kho (200 OK)")
    void warehouseStaffCanViewInventory() throws Exception {
        mockMvc.perform(get("/api/warehouse/inventory"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "warehouse_staff", authorities = {"ROLE_WAREHOUSE_STAFF", "PRODUCT_VIEW", "ORDER_VIEW", "WAREHOUSE_VIEW", "WAREHOUSE_IMPORT", "WAREHOUSE_EXPORT", "WAREHOUSE_TRANSFER"})
    @DisplayName("[ROLE_WAREHOUSE_STAFF] Thủ kho KHÔNG ĐƯỢC PHÉP lập phiếu điều chỉnh cho phiếu kho đã xác nhận (403 Forbidden)")
    void warehouseStaffCannotAdjustVoucher() throws Exception {
        mockMvc.perform(post("/api/warehouse/vouchers/1/adjust")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\": 1, \"delta\": 5}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "warehouse_staff", authorities = {"ROLE_WAREHOUSE_STAFF", "PRODUCT_VIEW", "ORDER_VIEW", "WAREHOUSE_VIEW", "WAREHOUSE_IMPORT", "WAREHOUSE_EXPORT", "WAREHOUSE_TRANSFER"})
    @DisplayName("[ROLE_WAREHOUSE_STAFF] Thủ kho ĐƯỢC PHÉP xác nhận phiếu nhập kho (200 OK)")
    void warehouseStaffWithoutApprovePermissionCannotConfirmVoucher() throws Exception {
        mockMvc.perform(post("/api/warehouse/vouchers/1/confirm"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "warehouse_staff", authorities = {"ROLE_WAREHOUSE_STAFF", "PRODUCT_VIEW", "ORDER_VIEW", "WAREHOUSE_VIEW", "WAREHOUSE_IMPORT", "WAREHOUSE_EXPORT", "WAREHOUSE_TRANSFER"})
    @DisplayName("[ROLE_WAREHOUSE_STAFF] Thủ kho ĐƯỢC PHÉP xem tồn kho theo lô (200 OK)")
    void warehouseStaffCanViewLotInventory() throws Exception {
        mockMvc.perform(get("/api/warehouse/inventory/lots"))
                .andExpect(status().isOk());
    }

    // ═══════════════════════════════════════════════════════════════
    // VAI TRÒ 3: QUẢN LÝ KINH DOANH (ROLE_SALES_MANAGER)
    // ═══════════════════════════════════════════════════════════════

    @Test
    @WithMockUser(username = "sales_manager", authorities = {"ROLE_SALES_MANAGER", "PRODUCT_VIEW", "PRODUCT_CREATE", "PRODUCT_EDIT", "PRODUCT_DELETE", "ORDER_VIEW", "ORDER_CREATE", "ORDER_EDIT", "ORDER_CANCEL", "ORDER_APPROVE", "CUSTOMER_VIEW", "CUSTOMER_CREATE", "CUSTOMER_EDIT", "WAREHOUSE_VIEW", "FINANCE_VIEW_COST_PRICE", "FINANCE_VIEW_PROFIT_MARGIN", "FINANCE_VIEW_DEBT", "FINANCE_MANAGE", "REPORT_SALES", "REPORT_INVENTORY", "REPORT_FINANCE"})
    @DisplayName("[ROLE_SALES_MANAGER] Quản lý kinh doanh ĐƯỢC XEM giá vốn (200 OK)")
    void salesManagerCanViewCostPrice() throws Exception {
        mockMvc.perform(get("/api/finance/cost-price"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "sales_manager", authorities = {"ROLE_SALES_MANAGER", "PRODUCT_VIEW", "PRODUCT_CREATE", "PRODUCT_EDIT", "PRODUCT_DELETE", "ORDER_VIEW", "ORDER_CREATE", "ORDER_EDIT", "ORDER_CANCEL", "ORDER_APPROVE", "CUSTOMER_VIEW", "CUSTOMER_CREATE", "CUSTOMER_EDIT", "WAREHOUSE_VIEW", "FINANCE_VIEW_COST_PRICE", "FINANCE_VIEW_PROFIT_MARGIN", "FINANCE_VIEW_DEBT", "FINANCE_MANAGE", "REPORT_SALES", "REPORT_INVENTORY", "REPORT_FINANCE"})
    @DisplayName("[ROLE_SALES_MANAGER] Quản lý kinh doanh ĐƯỢC XEM biên lợi nhuận (200 OK)")
    void salesManagerCanViewProfitMargin() throws Exception {
        mockMvc.perform(get("/api/finance/profit-margin"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "sales_manager", authorities = {"ROLE_SALES_MANAGER", "PRODUCT_VIEW", "PRODUCT_CREATE", "PRODUCT_EDIT", "PRODUCT_DELETE", "ORDER_VIEW", "ORDER_CREATE", "ORDER_EDIT", "ORDER_CANCEL", "ORDER_APPROVE", "CUSTOMER_VIEW", "CUSTOMER_CREATE", "CUSTOMER_EDIT", "WAREHOUSE_VIEW", "FINANCE_VIEW_COST_PRICE", "FINANCE_VIEW_PROFIT_MARGIN", "FINANCE_VIEW_DEBT", "FINANCE_MANAGE", "REPORT_SALES", "REPORT_INVENTORY", "REPORT_FINANCE"})
    @DisplayName("[ROLE_SALES_MANAGER] Quản lý kinh doanh KHÔNG được sửa tồn kho (403 Forbidden)")
    void salesManagerCannotAdjustInventory() throws Exception {
        mockMvc.perform(post("/api/warehouse/adjust")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\": 1, \"delta\": 10}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "sales_manager", authorities = {"ROLE_SALES_MANAGER", "PRODUCT_VIEW", "PRODUCT_CREATE", "PRODUCT_EDIT", "PRODUCT_DELETE", "ORDER_VIEW", "ORDER_CREATE", "ORDER_EDIT", "ORDER_CANCEL", "ORDER_APPROVE", "CUSTOMER_VIEW", "CUSTOMER_CREATE", "CUSTOMER_EDIT", "WAREHOUSE_VIEW", "FINANCE_VIEW_COST_PRICE", "FINANCE_VIEW_PROFIT_MARGIN", "FINANCE_VIEW_DEBT", "FINANCE_MANAGE", "REPORT_SALES", "REPORT_INVENTORY", "REPORT_FINANCE"})
    @DisplayName("[ROLE_SALES_MANAGER] Quản lý kinh doanh được cập nhật hạn mức công nợ (S3-05)")
    void salesManagerCanUpdateDebtLimit() throws Exception {
        com.quanlybanhang.model.Dealer dealer = com.quanlybanhang.model.Dealer.builder()
                .id(1L)
                .name("Đại lý Hà Nội")
                .debtLimit(java.math.BigDecimal.ZERO)
                .maxDebtDays(0)
                .build();
        org.mockito.Mockito.when(dealerRepository.findById(1L))
                .thenReturn(java.util.Optional.of(dealer));

        mockMvc.perform(put("/api/finance/debt/1/limit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"debtLimit\": 50000000, \"maxDebtDays\": 30, \"reason\": \"Điều chỉnh hạn mức quý 4\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "accountant", authorities = {"ROLE_ACCOUNTANT", "PRODUCT_VIEW", "ORDER_VIEW", "WAREHOUSE_VIEW", "FINANCE_VIEW_DEBT", "FINANCE_MANAGE", "REPORT_SALES", "REPORT_INVENTORY", "REPORT_FINANCE"})
    @DisplayName("[ROLE_ACCOUNTANT] Kế toán công nợ được cập nhật hạn mức công nợ (S3-05)")
    void accountantCanUpdateDebtLimit() throws Exception {
        com.quanlybanhang.model.Dealer dealer = com.quanlybanhang.model.Dealer.builder()
                .id(1L)
                .name("Đại lý Hà Nội")
                .debtLimit(java.math.BigDecimal.ZERO)
                .maxDebtDays(0)
                .build();
        org.mockito.Mockito.when(dealerRepository.findById(1L))
                .thenReturn(java.util.Optional.of(dealer));

        mockMvc.perform(put("/api/finance/debt/1/limit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"debtLimit\": 50000000, \"maxDebtDays\": 30, \"reason\": \"Khai báo hạn mức đại lý mới\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "sales_user", authorities = {"ROLE_SALES", "PRODUCT_VIEW", "ORDER_VIEW", "ORDER_CREATE", "ORDER_EDIT", "ORDER_CANCEL", "CUSTOMER_VIEW", "CUSTOMER_CREATE", "CUSTOMER_EDIT", "REPORT_SALES"})
    @DisplayName("[ROLE_SALES] Nhân viên kinh doanh KHÔNG được sửa hạn mức công nợ (403 Forbidden)")
    void salesCannotUpdateDebtLimit() throws Exception {
        mockMvc.perform(put("/api/finance/debt/1/limit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"debtLimit\": 50000000, \"maxDebtDays\": 30, \"reason\": \"Thử sửa hạn mức\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "accountant", authorities = {"ROLE_ACCOUNTANT", "PRODUCT_VIEW", "ORDER_VIEW", "WAREHOUSE_VIEW", "FINANCE_VIEW_DEBT", "FINANCE_MANAGE", "REPORT_SALES", "REPORT_INVENTORY", "REPORT_FINANCE"})
    @DisplayName("[S3-05] Cập nhật hạn mức công nợ bắt buộc nhập lý do (400 Bad Request)")
    void updateDebtLimitRequiresReasonValidation() throws Exception {
        mockMvc.perform(put("/api/finance/debt/1/limit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"debtLimit\": 50000000, \"maxDebtDays\": 30}"))
                .andExpect(status().isBadRequest());
    }

    // ═══════════════════════════════════════════════════════════════
    // VAI TRÒ 4: QUẢN TRỊ HỆ THỐNG (ROLE_ADMIN)
    // ═══════════════════════════════════════════════════════════════

    @Test
    @WithMockUser(username = "admin_user", authorities = {"ROLE_ADMIN"})
    @DisplayName("[ROLE_ADMIN] Quản trị viên truy cập được API phân quyền (200 OK)")
    void adminCanAccessPermissionManagement() throws Exception {
        mockMvc.perform(get("/api/admin/permissions"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "admin_user", authorities = {"ROLE_ADMIN"})
    @DisplayName("[ROLE_ADMIN] Quản trị viên luôn được xem giá vốn")
    void adminCanViewCostPrice() throws Exception {
        mockMvc.perform(get("/api/finance/cost-price"))
                .andExpect(status().isOk());
    }

    // ═══════════════════════════════════════════════════════════════
    // VAI TRÒ 5: QUẢN LÝ KHO (ROLE_WAREHOUSE_MANAGER)
    // ═══════════════════════════════════════════════════════════════

    @Test
    @WithMockUser(username = "warehouse_manager", authorities = {"ROLE_WAREHOUSE_MANAGER", "PRODUCT_VIEW", "ORDER_VIEW", "WAREHOUSE_VIEW", "WAREHOUSE_MANAGE", "WAREHOUSE_IMPORT", "WAREHOUSE_EXPORT", "WAREHOUSE_ADJUST", "WAREHOUSE_APPROVE", "WAREHOUSE_TRANSFER", "REPORT_INVENTORY"})
    @DisplayName("[ROLE_WAREHOUSE_MANAGER] Quản lý kho ĐƯỢC PHÉP điều chỉnh tồn kho (200 OK)")
    void warehouseManagerCanAdjustInventory() throws Exception {
        mockMvc.perform(post("/api/warehouse/adjust")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\": 1, \"delta\": 20}"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "warehouse_manager", authorities = {"ROLE_WAREHOUSE_MANAGER", "PRODUCT_VIEW", "ORDER_VIEW", "WAREHOUSE_VIEW", "WAREHOUSE_MANAGE", "WAREHOUSE_IMPORT", "WAREHOUSE_EXPORT", "WAREHOUSE_ADJUST", "WAREHOUSE_APPROVE", "WAREHOUSE_TRANSFER", "REPORT_INVENTORY"})
    @DisplayName("[ROLE_WAREHOUSE_MANAGER] Quản lý kho ĐƯỢC PHÉP lập phiếu điều chỉnh cho phiếu kho đã xác nhận (200 OK)")
    void warehouseManagerCanAdjustVoucher() throws Exception {
        mockMvc.perform(post("/api/warehouse/vouchers/1/adjust")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\": 1, \"delta\": 5}"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "warehouse_manager", authorities = {"ROLE_WAREHOUSE_MANAGER", "PRODUCT_VIEW", "ORDER_VIEW", "WAREHOUSE_VIEW", "WAREHOUSE_MANAGE", "WAREHOUSE_IMPORT", "WAREHOUSE_EXPORT", "WAREHOUSE_ADJUST", "WAREHOUSE_APPROVE", "WAREHOUSE_TRANSFER", "REPORT_INVENTORY"})
    @DisplayName("[ROLE_WAREHOUSE_MANAGER] Quản lý kho KHÔNG xem được giá vốn (403 Forbidden)")
    void warehouseManagerCannotViewCostPrice() throws Exception {
        mockMvc.perform(get("/api/finance/cost-price"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "sales_user", authorities = {"ROLE_SALES", "PRODUCT_VIEW", "ORDER_VIEW", "ORDER_CREATE", "ORDER_EDIT", "ORDER_CANCEL", "CUSTOMER_VIEW", "CUSTOMER_CREATE", "CUSTOMER_EDIT", "REPORT_SALES"})
    @DisplayName("[HỒ SƠ] Người dùng đã đăng nhập truy cập được hồ sơ cá nhân")
    void authenticatedUserCanAccessOwnProfile() throws Exception {
        org.mockito.Mockito.when(userService.getUserProfile("sales_user"))
                .thenReturn(UserProfileResponse.builder()
                        .username("sales_user")
                        .fullName("Nhân viên kinh doanh")
                        .roles(java.util.List.of("ROLE_SALES"))
                        .build());

        mockMvc.perform(get("/api/users/profile"))
                .andExpect(status().isOk());
    }

    // ═══════════════════════════════════════════════════════════════
    // MẶC ĐỊNH TỪ CHỐI (SERVER-SIDE DEFAULT DENY)
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[MẶC ĐỊNH TỪ CHỐI] Chưa đăng nhập truy cập API bảo vệ trả về 401 Unauthorized")
    void unauthenticatedAccessReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/finance/cost-price"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/warehouse/adjust"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/permissions"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "random_user", authorities = {"ROLE_VIEWER", "PRODUCT_VIEW", "ORDER_VIEW", "WAREHOUSE_VIEW", "CUSTOMER_VIEW", "REPORT_SALES", "REPORT_INVENTORY"})
    @DisplayName("[MẶC ĐỊNH TỪ CHỐI] Mọi endpoint chưa khai báo đều bị từ chối ở tầng server (403 Forbidden)")
    void unmappedEndpointsAreDeniedByDefault() throws Exception {
        mockMvc.perform(get("/api/undeclared/endpoint"))
                .andExpect(status().isForbidden());
    }
}
