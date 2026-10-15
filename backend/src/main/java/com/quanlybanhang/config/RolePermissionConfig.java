package com.quanlybanhang.config;

import com.quanlybanhang.model.ERole;
import com.quanlybanhang.model.Permission;
import com.quanlybanhang.model.RolePermission;
import com.quanlybanhang.repository.RolePermissionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

import static com.quanlybanhang.model.Permission.*;

/**
 * Nguồn đọc/ghi ma trận phân quyền.
 *
 * Bộ quyền trong mã nguồn chỉ là dữ liệu khởi tạo lần đầu. Sau khi có bảng
 * role_permissions, mọi thay đổi từ màn hình quản trị được đọc lại từ DB.
 * ROLE_ADMIN luôn được chuẩn hoá thành toàn bộ Permission.
 */
@Component
public class RolePermissionConfig {

    private final Map<ERole, Set<Permission>> rolePermissions = new EnumMap<>(ERole.class);
    private RolePermissionRepository rolePermissionRepository;

    /** Giữ constructor không tham số để các unit test thuần Java vẫn dùng được. */
    public RolePermissionConfig() {
        rolePermissions.putAll(defaultPermissions());
        enforceAdminPermissions();
    }

    @Autowired(required = false)
    public void setRolePermissionRepository(RolePermissionRepository repository) {
        this.rolePermissionRepository = repository;
        loadFromDatabase();
    }

    private static Map<ERole, Set<Permission>> defaultPermissions() {
        Map<ERole, Set<Permission>> defaults = new EnumMap<>(ERole.class);
        defaults.put(ERole.ROLE_ADMIN, EnumSet.allOf(Permission.class));
        defaults.put(ERole.ROLE_SALES_MANAGER, EnumSet.of(
                PRODUCT_VIEW, PRODUCT_CREATE, PRODUCT_EDIT, PRODUCT_DELETE,
                ORDER_VIEW, ORDER_CREATE, ORDER_EDIT, ORDER_CANCEL, ORDER_APPROVE,
                CUSTOMER_VIEW, CUSTOMER_CREATE, CUSTOMER_EDIT, WAREHOUSE_VIEW,
                FINANCE_VIEW_COST_PRICE, FINANCE_VIEW_PROFIT_MARGIN,
                FINANCE_VIEW_DEBT, FINANCE_MANAGE,
                REPORT_SALES, REPORT_INVENTORY, REPORT_FINANCE
        ));
        defaults.put(ERole.ROLE_WAREHOUSE_MANAGER, EnumSet.of(
                PRODUCT_VIEW, ORDER_VIEW, WAREHOUSE_VIEW, WAREHOUSE_IMPORT,
                WAREHOUSE_EXPORT, WAREHOUSE_ADJUST, WAREHOUSE_APPROVE,
                REPORT_INVENTORY
        ));
        defaults.put(ERole.ROLE_SALES, EnumSet.of(
                PRODUCT_VIEW, ORDER_VIEW, ORDER_CREATE, ORDER_EDIT,
                CUSTOMER_VIEW, CUSTOMER_CREATE, CUSTOMER_EDIT, REPORT_SALES
        ));
        defaults.put(ERole.ROLE_WAREHOUSE_STAFF, EnumSet.of(
                PRODUCT_VIEW, ORDER_VIEW, WAREHOUSE_VIEW, WAREHOUSE_IMPORT,
                WAREHOUSE_EXPORT
        ));
        defaults.put(ERole.ROLE_ACCOUNTANT, EnumSet.of(
                PRODUCT_VIEW, ORDER_VIEW, WAREHOUSE_VIEW, FINANCE_VIEW_DEBT,
                FINANCE_MANAGE, REPORT_SALES, REPORT_INVENTORY, REPORT_FINANCE
        ));
        defaults.put(ERole.ROLE_VIEWER, EnumSet.of(
                PRODUCT_VIEW, ORDER_VIEW, WAREHOUSE_VIEW, CUSTOMER_VIEW,
                REPORT_SALES, REPORT_INVENTORY
        ));
        defaults.put(ERole.ROLE_DEALER, EnumSet.of(
                ORDER_VIEW, ORDER_CREATE, PRODUCT_VIEW
        ));
        return defaults;
    }

    private void loadFromDatabase() {
        synchronized (this) {
            List<RolePermission> stored = rolePermissionRepository.findAll();
            if (stored.isEmpty()) {
                persistAll(rolePermissions);
                return;
            }

            Map<ERole, Set<Permission>> loaded = new EnumMap<>(ERole.class);
            for (RolePermission item : stored) {
                if (item.getRole() != null && item.getPermission() != null) {
                    loaded.computeIfAbsent(item.getRole(), ignored -> EnumSet.noneOf(Permission.class))
                            .add(item.getPermission());
                }
            }
            Map<ERole, Set<Permission>> defaults = defaultPermissions();
            for (ERole role : ERole.values()) {
                loaded.putIfAbsent(role, EnumSet.copyOf(defaults.get(role)));
            }
            rolePermissions.clear();
            rolePermissions.putAll(copyMatrix(loaded));
            enforceAdminPermissions();
            // Bổ sung vai trò còn thiếu và sửa dữ liệu ADMIN cũ nếu cần.
            persistAll(rolePermissions);
        }
    }

    private void persistAll(Map<ERole, Set<Permission>> matrix) {
        if (rolePermissionRepository == null) return;
        for (ERole role : ERole.values()) {
            replaceStoredPermissions(role, matrix.getOrDefault(role, Set.of()));
        }
    }

    private void replaceStoredPermissions(ERole role, Set<Permission> permissions) {
        if (rolePermissionRepository == null) return;
        rolePermissionRepository.deleteByRole(role);
        List<RolePermission> rows = permissions.stream()
                .map(permission -> RolePermission.builder().role(role).permission(permission).build())
                .toList();
        if (!rows.isEmpty()) rolePermissionRepository.saveAll(rows);
    }

    private void enforceAdminPermissions() {
        rolePermissions.put(ERole.ROLE_ADMIN, EnumSet.allOf(Permission.class));
    }

    private static Map<ERole, Set<Permission>> copyMatrix(Map<ERole, Set<Permission>> source) {
        Map<ERole, Set<Permission>> copy = new EnumMap<>(ERole.class);
        source.forEach((role, permissions) -> copy.put(role,
                permissions.isEmpty() ? EnumSet.noneOf(Permission.class) : EnumSet.copyOf(permissions)));
        return copy;
    }

    /** Cập nhật quyền; ADMIN luôn được chuẩn hoá thành toàn bộ quyền. */
    public synchronized void updateRolePermissions(ERole role, Set<Permission> newPermissions) {
        if (role == null) throw new IllegalArgumentException("Vai trò không được để trống");
        if (role == ERole.ROLE_ADMIN) {
            rolePermissions.put(role, EnumSet.allOf(Permission.class));
            replaceStoredPermissions(role, rolePermissions.get(role));
            return;
        }
        if (newPermissions == null) newPermissions = Collections.emptySet();
        if (newPermissions.contains(FINANCE_VIEW_COST_PRICE)
                || newPermissions.contains(FINANCE_VIEW_PROFIT_MARGIN)) {
            if (role != ERole.ROLE_SALES_MANAGER) {
                throw new IllegalArgumentException(
                        "Giá vốn và biên lợi nhuận chỉ được phép cấp cho vai trò Quản lý kinh doanh");
            }
        }
        if (role == ERole.ROLE_SALES && newPermissions.contains(WAREHOUSE_ADJUST)) {
            throw new IllegalArgumentException("Nhân viên kinh doanh không được phép sửa tồn kho");
        }
        if (role == ERole.ROLE_DEALER && (newPermissions.contains(WAREHOUSE_ADJUST)
                || newPermissions.contains(WAREHOUSE_IMPORT)
                || newPermissions.contains(WAREHOUSE_EXPORT)
                || newPermissions.contains(WAREHOUSE_APPROVE))) {
            throw new IllegalArgumentException("Đại lý không được phép thao tác kho");
        }
        Set<Permission> permissions = newPermissions.isEmpty()
                ? EnumSet.noneOf(Permission.class) : EnumSet.copyOf(newPermissions);
        rolePermissions.put(role, permissions);
        replaceStoredPermissions(role, permissions);
    }

    public synchronized void resetToDefault() {
        rolePermissions.clear();
        rolePermissions.putAll(defaultPermissions());
        enforceAdminPermissions();
        persistAll(rolePermissions);
    }

    public synchronized Set<Permission> getPermissions(ERole role) {
        Set<Permission> permissions = rolePermissions.getOrDefault(role, EnumSet.noneOf(Permission.class));
        return Collections.unmodifiableSet(permissions.isEmpty()
                ? EnumSet.noneOf(Permission.class) : EnumSet.copyOf(permissions));
    }

    public synchronized boolean hasPermission(ERole role, Permission permission) {
        return role == ERole.ROLE_ADMIN || rolePermissions.getOrDefault(role, Set.of()).contains(permission);
    }

    public Set<Permission> getPermissions(Collection<ERole> roles) {
        Set<Permission> combined = EnumSet.noneOf(Permission.class);
        if (roles != null) for (ERole role : roles) combined.addAll(getPermissions(role));
        return Collections.unmodifiableSet(combined);
    }

    public synchronized Map<ERole, Set<Permission>> getAllPermissions() {
        return Collections.unmodifiableMap(copyMatrix(rolePermissions));
    }
}
