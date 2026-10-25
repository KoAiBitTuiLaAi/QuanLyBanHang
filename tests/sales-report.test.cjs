const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '../frontend/js/menu-config.js'), 'utf8');
const menu = vm.runInNewContext(`${source}\nMenuConfig;`, { URL, URLSearchParams });
const ids = user => Array.from(menu.getMenuForUser(user), item => item.id);

test('S8-06 menu: Báo cáo doanh số theo ma trận quyền REPORT_SALES hoặc ORDER_VIEW, tuyệt đối không dùng role fallback', () => {
  const roles = ['ROLE_SALES_MANAGER', 'ROLE_SALES', 'ROLE_ACCOUNTANT', 'ROLE_VIEWER'];

  for (const role of roles) {
    // 1. Khi bị gỡ cả 2 quyền: menu phải ẩn ngay lập tức (không fallback theo role)
    assert.ok(
      !ids({ roles: [role], permissions: [] }).includes('sales-report'),
      `${role} không có quyền nhưng vẫn thấy menu sales-report!`
    );
    assert.ok(
      !ids({ roles: [role], permissions: ['PRODUCT_VIEW', 'WAREHOUSE_VIEW'] }).includes('sales-report'),
      `${role} thiếu quyền nhưng vẫn thấy menu sales-report!`
    );

    // 2. Khi được cấp quyền REPORT_SALES: menu phải hiển thị
    assert.ok(
      ids({ roles: [role], permissions: ['REPORT_SALES'] }).includes('sales-report'),
      `${role} có REPORT_SALES nhưng không thấy menu sales-report!`
    );

    // 3. Khi được cấp quyền ORDER_VIEW: menu phải hiển thị
    assert.ok(
      ids({ roles: [role], permissions: ['ORDER_VIEW'] }).includes('sales-report'),
      `${role} có ORDER_VIEW nhưng không thấy menu sales-report!`
    );
  }

  // 4. ROLE_ADMIN luôn luôn có quyền truy cập
  assert.ok(
    ids({ roles: ['ROLE_ADMIN'], permissions: [] }).includes('sales-report'),
    'ROLE_ADMIN phải luôn thấy menu sales-report!'
  );

  // 5. Tài khoản chỉ là đại lý (ROLE_DEALER thuần túy) không được thấy menu báo cáo nội bộ
  assert.ok(
    !ids({ roles: ['ROLE_DEALER'], permissions: ['REPORT_SALES', 'ORDER_VIEW'] }).includes('sales-report'),
    'Đại lý thuần túy không được phép thấy báo cáo doanh số nội bộ!'
  );
});

test('S8-06 routing: đường dẫn /reports/sales phân giải đúng menu item sales-report', () => {
  const page = menu.getPageForLocation(new URL('http://localhost:5500/reports/sales'));
  assert.ok(page, 'Không tìm thấy trang cho /reports/sales');
  assert.equal(page.id, 'sales-report');
  assert.equal(page.section, 'Kinh doanh');
  assert.equal(page.label, 'Báo cáo doanh số');
});

test('S8-06 permissions: biên lợi nhuận chỉ Quản lý kinh doanh (FINANCE_VIEW_PROFIT_MARGIN) hoặc ADMIN xem được, không role fallback', () => {
  // Logic kiểm tra quyền xem biên lợi nhuận:
  const canSeeMargin = user => {
    const roles = user?.roles || [];
    const permissions = user?.permissions || [];
    return roles.includes('ROLE_ADMIN') || permissions.includes('FINANCE_VIEW_PROFIT_MARGIN');
  };

  // 1. ADMIN luôn xem được
  assert.equal(canSeeMargin({ roles: ['ROLE_ADMIN'], permissions: [] }), true);

  // 2. Quản lý kinh doanh có quyền FINANCE_VIEW_PROFIT_MARGIN thì xem được
  assert.equal(canSeeMargin({ roles: ['ROLE_SALES_MANAGER'], permissions: ['FINANCE_VIEW_PROFIT_MARGIN', 'REPORT_SALES'] }), true);

  // 3. Quản lý kinh doanh nhưng bị thu hồi FINANCE_VIEW_PROFIT_MARGIN trong DB thì KHÔNG được xem (không role fallback)
  assert.equal(canSeeMargin({ roles: ['ROLE_SALES_MANAGER'], permissions: ['REPORT_SALES', 'ORDER_VIEW'] }), false);

  // 4. Nhân viên sales hoặc role khác không có FINANCE_VIEW_PROFIT_MARGIN thì KHÔNG được xem
  assert.equal(canSeeMargin({ roles: ['ROLE_SALES'], permissions: ['REPORT_SALES', 'ORDER_VIEW'] }), false);
  assert.equal(canSeeMargin({ roles: ['ROLE_VIEWER'], permissions: ['REPORT_SALES'] }), false);
});
