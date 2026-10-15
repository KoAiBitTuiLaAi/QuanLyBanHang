package com.quanlybanhang.config;

import com.quanlybanhang.model.ERole;
import com.quanlybanhang.model.Permission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Kiểm thử ma trận phân quyền 7 vai trò nghiệp vụ (S1-05).
 *
 * Tiêu chí nghiệm thu:
 * - Khai báo được quyền cho từng vai trò trong bảy vai trò nghiệp vụ
 * - Mọi chức năng đều kiểm quyền ở tầng server, mặc định là từ chối
 * - Giá vốn và biên lợi nhuận chỉ lộ ra với vai trò Quản lý kinh doanh
 * - Nhân viên kinh doanh không sửa được tồn kho và thủ kho không xem được giá vốn
 */
class RolePermissionConfigTest {
    private RolePermissionConfig permissions;

    @BeforeEach
    void setUp() {
        permissions = new RolePermissionConfig();
    }

    @Test
    @DisplayName("Cả 7 vai trò nghiệp vụ đều được khai báo quyền trong hệ thống")
    void allSevenRolesAreConfigured() {
        for (ERole role : ERole.values()) {
            Set<Permission> perms = permissions.getPermissions(role);
            assertThat(perms)
                    .as("Role %s phải được khai báo quyền", role)
                    .isNotEmpty();
        }
    }

    @Test
    @DisplayName("Quản lý kinh doanh và quản trị viên xem được giá vốn, biên lợi nhuận")
    void salesManagerAndAdminCanSeeCostAndMargin() {
        // Quản lý kinh doanh và quản trị viên xem được giá vốn, biên lợi nhuận
        assertThat(permissions.hasPermission(ERole.ROLE_SALES_MANAGER, Permission.FINANCE_VIEW_COST_PRICE)).isTrue();
        assertThat(permissions.hasPermission(ERole.ROLE_SALES_MANAGER, Permission.FINANCE_VIEW_PROFIT_MARGIN)).isTrue();

        assertThat(permissions.hasPermission(ERole.ROLE_ADMIN, Permission.FINANCE_VIEW_COST_PRICE)).isTrue();
        assertThat(permissions.hasPermission(ERole.ROLE_ADMIN, Permission.FINANCE_VIEW_PROFIT_MARGIN)).isTrue();

        // Các vai trò còn lại không xem được giá vốn
        assertThat(permissions.hasPermission(ERole.ROLE_ACCOUNTANT, Permission.FINANCE_VIEW_COST_PRICE)).isFalse();
        assertThat(permissions.hasPermission(ERole.ROLE_WAREHOUSE_MANAGER, Permission.FINANCE_VIEW_COST_PRICE)).isFalse();
        assertThat(permissions.hasPermission(ERole.ROLE_SALES, Permission.FINANCE_VIEW_COST_PRICE)).isFalse();
        assertThat(permissions.hasPermission(ERole.ROLE_WAREHOUSE_STAFF, Permission.FINANCE_VIEW_COST_PRICE)).isFalse();
        assertThat(permissions.hasPermission(ERole.ROLE_VIEWER, Permission.FINANCE_VIEW_COST_PRICE)).isFalse();

        // Tất cả 6 vai trò còn lại KHÔNG xem được biên lợi nhuận
        assertThat(permissions.hasPermission(ERole.ROLE_ACCOUNTANT, Permission.FINANCE_VIEW_PROFIT_MARGIN)).isFalse();
        assertThat(permissions.hasPermission(ERole.ROLE_WAREHOUSE_MANAGER, Permission.FINANCE_VIEW_PROFIT_MARGIN)).isFalse();
        assertThat(permissions.hasPermission(ERole.ROLE_SALES, Permission.FINANCE_VIEW_PROFIT_MARGIN)).isFalse();
        assertThat(permissions.hasPermission(ERole.ROLE_WAREHOUSE_STAFF, Permission.FINANCE_VIEW_PROFIT_MARGIN)).isFalse();
        assertThat(permissions.hasPermission(ERole.ROLE_VIEWER, Permission.FINANCE_VIEW_PROFIT_MARGIN)).isFalse();
    }

    @Test
    @DisplayName("Nhân viên kinh doanh (ROLE_SALES) tạo được đơn nhưng KHÔNG sửa được tồn kho")
    void salesCanCreateOrdersButCannotChangeInventory() {
        assertThat(permissions.hasPermission(ERole.ROLE_SALES, Permission.ORDER_CREATE)).isTrue();
        assertThat(permissions.hasPermission(ERole.ROLE_SALES, Permission.ORDER_VIEW)).isTrue();
        assertThat(permissions.hasPermission(ERole.ROLE_SALES, Permission.CUSTOMER_VIEW)).isTrue();

        // Không sửa / điều chỉnh tồn kho
        assertThat(permissions.hasPermission(ERole.ROLE_SALES, Permission.WAREHOUSE_ADJUST)).isFalse();
        assertThat(permissions.hasPermission(ERole.ROLE_SALES, Permission.WAREHOUSE_IMPORT)).isFalse();
        assertThat(permissions.hasPermission(ERole.ROLE_SALES, Permission.WAREHOUSE_EXPORT)).isFalse();
        assertThat(permissions.hasPermission(ERole.ROLE_SALES, Permission.WAREHOUSE_TRANSFER)).isFalse();
    }

    @Test
    @DisplayName("Thủ kho (ROLE_WAREHOUSE_STAFF) nhập/xuất kho được nhưng KHÔNG xem được giá vốn và KHÔNG sửa tồn kho")
    void warehouseStaffCanMoveStockButCannotSeeFinancialDataOrAdjustStock() {
        // Nhập / xuất kho
        assertThat(permissions.hasPermission(ERole.ROLE_WAREHOUSE_STAFF, Permission.WAREHOUSE_IMPORT)).isTrue();
        assertThat(permissions.hasPermission(ERole.ROLE_WAREHOUSE_STAFF, Permission.WAREHOUSE_EXPORT)).isTrue();
        assertThat(permissions.hasPermission(ERole.ROLE_WAREHOUSE_STAFF, Permission.WAREHOUSE_VIEW)).isTrue();
        assertThat(permissions.hasPermission(ERole.ROLE_WAREHOUSE_STAFF, Permission.WAREHOUSE_TRANSFER)).isTrue();

        // Không sửa / điều chỉnh tồn kho (chỉ Quản lý kho mới được sửa)
        assertThat(permissions.hasPermission(ERole.ROLE_WAREHOUSE_STAFF, Permission.WAREHOUSE_ADJUST)).isFalse();

        // Không xem giá vốn, biên lợi nhuận, công nợ
        assertThat(permissions.hasPermission(ERole.ROLE_WAREHOUSE_STAFF, Permission.FINANCE_VIEW_COST_PRICE)).isFalse();
        assertThat(permissions.hasPermission(ERole.ROLE_WAREHOUSE_STAFF, Permission.FINANCE_VIEW_PROFIT_MARGIN)).isFalse();
        assertThat(permissions.hasPermission(ERole.ROLE_WAREHOUSE_STAFF, Permission.FINANCE_VIEW_DEBT)).isFalse();
    }

    @Test
    @DisplayName("Quản lý kho (ROLE_WAREHOUSE_MANAGER) được sửa tồn kho và phê duyệt nhưng KHÔNG xem giá vốn")
    void warehouseManagerCanAdjustStockWithoutCostPrice() {
        assertThat(permissions.hasPermission(ERole.ROLE_WAREHOUSE_MANAGER, Permission.WAREHOUSE_ADJUST)).isTrue();
        assertThat(permissions.hasPermission(ERole.ROLE_WAREHOUSE_MANAGER, Permission.WAREHOUSE_APPROVE)).isTrue();
        assertThat(permissions.hasPermission(ERole.ROLE_WAREHOUSE_MANAGER, Permission.WAREHOUSE_TRANSFER)).isTrue();
        assertThat(permissions.hasPermission(ERole.ROLE_WAREHOUSE_MANAGER, Permission.FINANCE_VIEW_COST_PRICE)).isFalse();
        assertThat(permissions.hasPermission(ERole.ROLE_WAREHOUSE_MANAGER, Permission.FINANCE_VIEW_PROFIT_MARGIN)).isFalse();
    }

    @Test
    @DisplayName("Kế toán (ROLE_ACCOUNTANT) quản lý công nợ và tài chính nhưng KHÔNG xem giá vốn")
    void accountantCanManageDebtWithoutCostPrice() {
        assertThat(permissions.hasPermission(ERole.ROLE_ACCOUNTANT, Permission.FINANCE_MANAGE)).isTrue();
        assertThat(permissions.hasPermission(ERole.ROLE_ACCOUNTANT, Permission.FINANCE_VIEW_DEBT)).isTrue();
        assertThat(permissions.hasPermission(ERole.ROLE_ACCOUNTANT, Permission.FINANCE_VIEW_COST_PRICE)).isFalse();
        assertThat(permissions.hasPermission(ERole.ROLE_ACCOUNTANT, Permission.FINANCE_VIEW_PROFIT_MARGIN)).isFalse();
    }

    @Test
    @DisplayName("Người xem (ROLE_VIEWER) chỉ xem báo cáo, không có bất kỳ quyền thao tác dữ liệu nào")
    void viewerHasReadOnlyPermissions() {
        assertThat(permissions.hasPermission(ERole.ROLE_VIEWER, Permission.REPORT_SALES)).isTrue();
        assertThat(permissions.hasPermission(ERole.ROLE_VIEWER, Permission.PRODUCT_VIEW)).isTrue();
        assertThat(permissions.hasPermission(ERole.ROLE_VIEWER, Permission.ORDER_CREATE)).isFalse();
        assertThat(permissions.hasPermission(ERole.ROLE_VIEWER, Permission.WAREHOUSE_ADJUST)).isFalse();
        assertThat(permissions.hasPermission(ERole.ROLE_VIEWER, Permission.FINANCE_VIEW_COST_PRICE)).isFalse();
    }

    @Test
    @DisplayName("Cập nhật quyền từ chối gán giá vốn/biên lợi nhuận cho vai trò không phù hợp")
    void updatePermissionsRejectsCostPriceForNonSalesManager() {
        Set<Permission> illegalPerms = EnumSet.of(Permission.PRODUCT_VIEW, Permission.FINANCE_VIEW_COST_PRICE);
        assertThatThrownBy(() -> permissions.updateRolePermissions(ERole.ROLE_WAREHOUSE_STAFF, illegalPerms))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Giá vốn và biên lợi nhuận chỉ được phép cấp cho vai trò Quản lý kinh doanh");

        permissions.updateRolePermissions(ERole.ROLE_ADMIN, EnumSet.noneOf(Permission.class));
        assertThat(permissions.getPermissions(ERole.ROLE_ADMIN))
                .containsExactlyInAnyOrder(Permission.values());
    }

    @Test
    @DisplayName("Cập nhật quyền từ chối gán quyền sửa tồn kho (WAREHOUSE_ADJUST) cho Nhân viên kinh doanh")
    void updatePermissionsRejectsWarehouseAdjustForSales() {
        Set<Permission> illegalPerms = EnumSet.of(Permission.ORDER_CREATE, Permission.WAREHOUSE_ADJUST);
        assertThatThrownBy(() -> permissions.updateRolePermissions(ERole.ROLE_SALES, illegalPerms))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Nhân viên kinh doanh không được phép sửa tồn kho");
    }

    @Test
    @DisplayName("Khôi phục ma trận phân quyền về mặc định thành công")
    void resetToDefaultRestoresStandardMatrix() {
        permissions.updateRolePermissions(ERole.ROLE_VIEWER, EnumSet.of(Permission.PRODUCT_VIEW));
        assertThat(permissions.getPermissions(ERole.ROLE_VIEWER)).hasSize(1);

        permissions.resetToDefault();
        assertThat(permissions.getPermissions(ERole.ROLE_VIEWER)).contains(Permission.REPORT_SALES);
    }
}
