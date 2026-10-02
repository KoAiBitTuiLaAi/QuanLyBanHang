/**
 * menu-config.js — Cấu hình menu theo vai trò (S1-06).
 *
 * Mục menu không thuộc quyền thì không hiển thị.
 * Mỗi item có: id, label, icon (SVG path), href, và danh sách roles được phép.
 */
const MenuConfig = (() => {
  const ALL_ROLES = [
    "ROLE_ADMIN",
    "ROLE_SALES_MANAGER",
    "ROLE_WAREHOUSE_MANAGER",
    "ROLE_SALES",
    "ROLE_WAREHOUSE_STAFF",
    "ROLE_ACCOUNTANT",
    "ROLE_VIEWER",
  ];

  const menuItems = [
    {
      id: "dashboard",
      label: "Tổng quan",
      icon: '<path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/><polyline points="9 22 9 12 15 12 15 22"/>',
      href: "dashboard.html",
      roles: ALL_ROLES,
    },
    {
      id: "profile",
      label: "Hồ sơ cá nhân",
      icon: '<circle cx="12" cy="8" r="4"/><path d="M4 22a8 8 0 0 1 16 0"/>',
      href: "profile.html",
      roles: ALL_ROLES,
    },
    {
      id: "orders",
      section: "Kinh doanh",
      label: "Đơn hàng",
      icon: '<path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="16" y1="13" x2="8" y2="13"/><line x1="16" y1="17" x2="8" y2="17"/>',
      href: "#orders",
      permission: "ORDER_VIEW",
      roles: [
        "ROLE_ADMIN",
        "ROLE_SALES_MANAGER",
        "ROLE_SALES",
        "ROLE_WAREHOUSE_MANAGER",
        "ROLE_WAREHOUSE_STAFF",
        "ROLE_ACCOUNTANT",
        "ROLE_VIEWER",
      ],
    },
    {
      id: "customers",
      label: "Khách hàng",
      icon: '<path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M23 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/>',
      href: "#customers",
      permission: "CUSTOMER_VIEW",
      roles: ["ROLE_ADMIN", "ROLE_SALES_MANAGER", "ROLE_SALES", "ROLE_VIEWER"],
    },
    {
      id: "products",
      label: "Sản phẩm",
      icon: '<path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"/><polyline points="3.27 6.96 12 12.01 20.73 6.96"/><line x1="12" y1="22.08" x2="12" y2="12"/>',
      href: "products.html",
      permission: "PRODUCT_VIEW",
      roles: ALL_ROLES,
    },
    {
      id: "product-groups",
      label: "Nhóm hàng",
      icon: '<path d="M3 6h18M3 12h12M3 18h8"/>',
      href: "product-groups.html",
      permission: "PRODUCT_GROUP_VIEW",
      roles: ["ROLE_ADMIN", "ROLE_SALES_MANAGER", "ROLE_VIEWER"],
    },
    {
      id: "warehouse",
      section: "Kho hàng",
      label: "Tồn kho",
      icon: '<path d="M3 9.5 12 4l9 5.5V20a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V9.5Z"/><path d="M8 21v-7h8v7M8 11h8"/>',
      href: "#warehouse",
      permission: "WAREHOUSE_VIEW",
      roles: [
        "ROLE_ADMIN",
        "ROLE_WAREHOUSE_MANAGER",
        "ROLE_WAREHOUSE_STAFF",
        "ROLE_SALES_MANAGER",
        "ROLE_VIEWER",
      ],
    },
    {
      id: "suppliers",
      label: "Nhà cung cấp",
      icon: '<path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M22 21v-2a4 4 0 0 0-3-3.87M16 3.13a4 4 0 0 1 0 7.75"/>',
      href: "suppliers.html",
      permission: "SUPPLIER_VIEW",
      roles: [
        "ROLE_ADMIN",
        "ROLE_WAREHOUSE_MANAGER",
        "ROLE_WAREHOUSE_STAFF",
      ],
    },
    {
      id: "import-export",
      label: "Nhập / Xuất kho",
      icon: '<polyline points="17 1 21 5 17 9"/><path d="M3 11V9a4 4 0 0 1 4-4h14"/><polyline points="7 23 3 19 7 15"/><path d="M21 13v2a4 4 0 0 1-4 4H3"/>',
      href: "warehouse/units.html?tab=receipts",
      permission: "WAREHOUSE_IMPORT",
      roles: [
        "ROLE_ADMIN",
        "ROLE_WAREHOUSE_MANAGER",
        "ROLE_WAREHOUSE_STAFF",
      ],
    },
    {
      id: "finance",
      section: "Tài chính",
      label: "Công nợ",
      icon: '<line x1="12" y1="1" x2="12" y2="23"/><path d="M17 5H9.5a3.5 3.5 0 0 0 0 7h5a3.5 3.5 0 0 1 0 7H6"/>',
      href: "#finance",
      permission: "FINANCE_VIEW_DEBT",
      roles: ["ROLE_ADMIN", "ROLE_SALES_MANAGER", "ROLE_ACCOUNTANT"],
    },
    {
      id: "reports",
      section: "Báo cáo",
      label: "Báo cáo",
      icon: '<line x1="18" y1="20" x2="18" y2="10"/><line x1="12" y1="20" x2="12" y2="4"/><line x1="6" y1="20" x2="6" y2="14"/>',
      href: "#reports",
      permissions: ["REPORT_SALES", "REPORT_INVENTORY", "REPORT_FINANCE"],
      roles: ALL_ROLES,
    },
    {
      id: "users",
      section: "Hệ thống",
      label: "Quản lý tài khoản",
      icon: '<path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><line x1="19" y1="8" x2="19" y2="14"/><line x1="22" y1="11" x2="16" y2="11"/>',
      href: "admin/users.html",
      roles: ["ROLE_ADMIN"],
      permission: "USER_VIEW",
    },
  ];

  /**
   * Lấy menu items đã lọc theo roles của user.
   */
  function getMenuForRoles(userRoles) {
    return menuItems.filter((item) =>
      item.roles.some((role) => userRoles.includes(role)),
    );
  }

  function getMenuForUser(user) {
    const roles = user?.roles || [];
    const permissions = user?.permissions || [];
    return menuItems.filter((item) => {
      if (item.permission) return permissions.includes(item.permission);
      if (item.permissions) return item.permissions.some((permission) => permissions.includes(permission));
      return item.roles.some((role) => roles.includes(role));
    });
  }

  /**
   * Chuẩn hóa đường dẫn href dựa vào vị trí thư mục của trang hiện tại.
   */
  function resolveHref(href) {
    if (!href || href.startsWith("#") || href.startsWith("http")) return href;
    const path = window.location.pathname;
    const inWarehouse = path.includes("/warehouse/");
    const inAdmin = path.includes("/admin/");

    if (inWarehouse) {
      if (href.startsWith("warehouse/")) {
        return href.replace("warehouse/", "");
      }
      return `../${href}`;
    }

    if (inAdmin) {
      if (href.startsWith("admin/")) {
        return href.replace("admin/", "");
      }
      return `../${href}`;
    }

    return href;
  }

  /**
   * Lấy tên hiển thị cho role.
   */
  function getRoleDisplayName(role) {
    const names = {
      ROLE_ADMIN: "Quản trị viên",
      ROLE_SALES_MANAGER: "Quản lý kinh doanh",
      ROLE_WAREHOUSE_MANAGER: "Quản lý kho",
      ROLE_SALES: "Nhân viên kinh doanh",
      ROLE_WAREHOUSE_STAFF: "Thủ kho",
      ROLE_ACCOUNTANT: "Kế toán",
      ROLE_VIEWER: "Xem báo cáo",
    };
    return names[role] || role;
  }

  return { getMenuForRoles, getMenuForUser, getRoleDisplayName, resolveHref, menuItems };
})();
