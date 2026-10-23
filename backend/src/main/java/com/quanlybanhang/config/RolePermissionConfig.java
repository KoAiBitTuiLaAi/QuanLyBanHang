package com.quanlybanhang.config;

import com.quanlybanhang.model.ERole;
import com.quanlybanhang.model.Permission;
import com.quanlybanhang.model.RolePermission;
import com.quanlybanhang.model.RolePermissionState;
import com.quanlybanhang.repository.RolePermissionRepository;
import com.quanlybanhang.repository.RolePermissionStateRepository;
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
    private RolePermissionStateRepository rolePermissionStateRepository;
    @Autowired(required = false)
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    /** Giữ constructor không tham số để các unit test thuần Java vẫn dùng được. */
    public RolePermissionConfig() {
        rolePermissions.putAll(defaultPermissions());
        enforceAdminPermissions();
    }

    @Autowired(required = false)
    public void setRolePermissionRepository(RolePermissionRepository repository) {
        this.rolePermissionRepository = repository;
        // Chờ state repository nếu ứng dụng có bean này để không khôi phục
        // nhầm quyền mặc định trước khi đọc được marker role rỗng.
        if (rolePermissionStateRepository != null) loadFromDatabase();
    }

    @Autowired(required = false)
    public void setRolePermissionStateRepository(RolePermissionStateRepository repository) {
        this.rolePermissionStateRepository = repository;
        if (rolePermissionRepository != null) loadFromDatabase();
    }

    private static Map<ERole, Set<Permission>> defaultPermissions() {
        Map<ERole, Set<Permission>> defaults = new EnumMap<>(ERole.class);
        defaults.put(ERole.ROLE_ADMIN, EnumSet.allOf(Permission.class));
        defaults.put(ERole.ROLE_SALES_MANAGER, EnumSet.of(
                PRODUCT_VIEW, PRODUCT_CREATE, PRODUCT_EDIT, PRODUCT_DELETE,
                ORDER_VIEW, ORDER_CREATE, ORDER_EDIT, ORDER_CANCEL, ORDER_APPROVE, ORDER_CONFIRM,
                CUSTOMER_VIEW, CUSTOMER_CREATE, CUSTOMER_EDIT, WAREHOUSE_VIEW,
                FINANCE_VIEW_COST_PRICE, FINANCE_VIEW_PROFIT_MARGIN,
                FINANCE_VIEW_DEBT, FINANCE_MANAGE, INVOICE_VIEW, COLLECTION_RECONCILE,
                DASHBOARD_VIEW, REPORT_SALES, REPORT_INVENTORY, REPORT_FINANCE
        ));
        defaults.put(ERole.ROLE_WAREHOUSE_MANAGER, EnumSet.of(
                PRODUCT_VIEW, ORDER_VIEW, WAREHOUSE_VIEW, WAREHOUSE_MANAGE, WAREHOUSE_IMPORT,
                WAREHOUSE_EXPORT, WAREHOUSE_ADJUST, WAREHOUSE_APPROVE,
                WAREHOUSE_TRANSFER,
                REPORT_INVENTORY
        ));
        defaults.put(ERole.ROLE_SALES, EnumSet.of(
                PRODUCT_VIEW, ORDER_VIEW, ORDER_CREATE, ORDER_EDIT, ORDER_CANCEL, ORDER_CONFIRM,
                CUSTOMER_VIEW, CUSTOMER_CREATE, CUSTOMER_EDIT, REPORT_SALES, COLLECTION_CREATE
        ));
        defaults.put(ERole.ROLE_WAREHOUSE_STAFF, EnumSet.of(
                PRODUCT_VIEW, ORDER_VIEW, WAREHOUSE_VIEW, WAREHOUSE_IMPORT,
                WAREHOUSE_EXPORT, WAREHOUSE_TRANSFER
        ));
        defaults.put(ERole.ROLE_ACCOUNTANT, EnumSet.of(
                PRODUCT_VIEW, ORDER_VIEW, WAREHOUSE_VIEW, FINANCE_VIEW_DEBT,
                FINANCE_MANAGE, INVOICE_VIEW, INVOICE_CREATE, INVOICE_ADJUST, COLLECTION_CONFIRM, COLLECTION_RECONCILE,
                REPORT_SALES, REPORT_INVENTORY, REPORT_FINANCE
        ));
        defaults.put(ERole.ROLE_VIEWER, EnumSet.of(
                PRODUCT_VIEW, ORDER_VIEW, WAREHOUSE_VIEW, CUSTOMER_VIEW,
                REPORT_SALES, REPORT_INVENTORY
        ));
        defaults.put(ERole.ROLE_DEALER, EnumSet.of(
                ORDER_VIEW, ORDER_CREATE, PRODUCT_VIEW, FINANCE_VIEW_DEBT, INVOICE_VIEW
        ));
        return defaults;
    }

    private void loadFromDatabase() {
        synchronized (this) {
            if (rolePermissionRepository == null) return;
            List<RolePermission> stored = rolePermissionRepository.findAll();
            List<RolePermissionState> states = rolePermissionStateRepository == null
                    ? List.of() : rolePermissionStateRepository.findAll();
            if (stored.isEmpty() && states.isEmpty()) {
                persistAll(rolePermissions);
                return;
            }

            Map<ERole, Set<Permission>> loaded = new EnumMap<>(ERole.class);
            Set<ERole> storedRoles = EnumSet.noneOf(ERole.class);
            for (RolePermission item : stored) {
                if (item.getRole() != null) {
                    storedRoles.add(item.getRole());
                }
                if (item.getRole() != null && item.getPermission() != null) {
                    loaded.computeIfAbsent(item.getRole(), ignored -> EnumSet.noneOf(Permission.class))
                            .add(item.getPermission());
                }
            }
            Set<ERole> configuredRoles = states.stream()
                    .filter(RolePermissionState::isConfigured)
                    .map(RolePermissionState::getRole)
                    .filter(Objects::nonNull)
                    .collect(java.util.stream.Collectors.toCollection(() -> EnumSet.noneOf(ERole.class)));
            Map<ERole, Set<Permission>> defaults = defaultPermissions();
            for (ERole role : ERole.values()) {
                if (configuredRoles.contains(role) || storedRoles.contains(role)) {
                    loaded.putIfAbsent(role, EnumSet.noneOf(Permission.class));
                } else {
                    loaded.putIfAbsent(role, EnumSet.copyOf(defaults.get(role)));
                }
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
        Runnable write = () -> matrix.forEach((role, permissions) -> {
            replaceStoredPermissions(role, permissions);
            markRoleConfigured(role);
        });
        if (transactionManager == null) {
            write.run();
        } else {
            new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> write.run());
        }
    }

    private void markRoleConfigured(ERole role) {
        if (rolePermissionStateRepository == null || role == null) return;
        rolePermissionStateRepository.save(RolePermissionState.builder()
                .role(role).configured(true).build());
    }

    private void replaceStoredPermissions(ERole role, Set<Permission> permissions) {
        if (rolePermissionRepository == null) return;
        rolePermissionRepository.deleteByRole(role);
        // Hibernate phải thực hiện DELETE trước INSERT để không trùng unique(role, permission).
        rolePermissionRepository.flush();
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

    /** Chỉ công bố quyền mới sau khi toàn bộ thay đổi đã được lưu thành công. */
    public synchronized void updateRolePermissions(ERole role, Set<Permission> permissions) {
        if (role == null) throw new IllegalArgumentException("Vai trò không được để trống");
        Map<ERole, Set<Permission>> changes = new EnumMap<>(ERole.class);
        changes.put(role, permissions == null ? Set.of() : permissions);
        updatePermissions(changes);
    }

    public synchronized void updatePermissions(Map<ERole, Set<Permission>> changes) {
        if (changes == null || changes.isEmpty() || changes.containsKey(null)) {
            throw new IllegalArgumentException("Ma trận quyền không hợp lệ");
        }
        Map<ERole, Set<Permission>> normalized = new EnumMap<>(ERole.class);
        changes.forEach((role, permissions) -> normalized.put(role, permissions == null || permissions.isEmpty()
                ? EnumSet.noneOf(Permission.class) : EnumSet.copyOf(permissions)));
        normalized.put(ERole.ROLE_ADMIN, EnumSet.allOf(Permission.class));
        persistAll(normalized);
        rolePermissions.putAll(normalized);
    }

    public synchronized void resetToDefault() {
        Map<ERole, Set<Permission>> defaults = defaultPermissions();
        persistAll(defaults);
        rolePermissions.clear();
        rolePermissions.putAll(defaults);
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
