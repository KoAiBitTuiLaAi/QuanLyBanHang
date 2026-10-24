const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const Utils = require('../frontend/js/inventory-turnover-utils.js');
const menuSource = fs.readFileSync(path.join(__dirname, '../frontend/js/menu-config.js'), 'utf8');
const menu = vm.runInNewContext(`${menuSource}\nMenuConfig;`, { URLSearchParams });
const ids = (user) => Array.from(menu.getMenuForUser(user), (item) => item.id);

test('S8-07: Định dạng tiền tệ, số lượng, ngày tồn và hệ số vòng quay chính xác', () => {
  assert.equal(Utils.formatMoney(50000000).replace(/\s/g, ' '), '50.000.000 ₫');
  assert.equal(Utils.formatMoney(0).replace(/\s/g, ' '), '0 ₫');
  assert.equal(Utils.formatMoney(null), '0 đ');

  assert.equal(Utils.formatQuantity(120.5), '120,5');
  assert.equal(Utils.formatQuantity('1500.2500'), '1.500,25');
  assert.equal(Utils.formatQuantity(0), '0');

  assert.equal(Utils.formatTurnoverRate(4.25), '4,25');
  assert.equal(Utils.formatTurnoverRate('0.75'), '0,75');
  assert.equal(Utils.formatTurnoverRate(null), '0.00');

  assert.equal(Utils.formatDays(85.4), '85');
  assert.equal(Utils.formatDays(120), '120');

  assert.equal(Utils.formatPercent(15.5), '15,5%');
  assert.equal(Utils.formatPercent(null), '0%');
});

test('S8-07: Kiểm tra tính hợp lệ của khoảng thời gian bộ lọc', () => {
  assert.equal(Utils.validateDateRange('2026-01-01', '2026-03-31').valid, true);
  assert.equal(Utils.validateDateRange('', '').valid, true);

  const invalidOrder = Utils.validateDateRange('2026-05-01', '2026-04-01');
  assert.equal(invalidOrder.valid, false);
  assert.match(invalidOrder.error, /Từ ngày không được sau Đến ngày/);

  const invalidFormat = Utils.validateDateRange('sai-dinh-dang', '2026-04-01');
  assert.equal(invalidFormat.valid, false);
  assert.match(invalidFormat.error, /không hợp lệ/);
});

test('S8-07: Phân loại trạng thái luân chuyển và URL Thẻ kho SKU', () => {
  const fast = Utils.getTurnoverStatus('FAST_MOVING');
  assert.equal(fast.label, 'Luân chuyển nhanh');
  assert.equal(fast.badgeClass, 'badge--success');

  const slow = Utils.getTurnoverStatus('SLOW_MOVING');
  assert.equal(slow.label, 'Chậm luân chuyển');
  assert.equal(slow.badgeClass, 'badge--warning');

  const dead = Utils.getTurnoverStatus('DEAD_STOCK');
  assert.equal(dead.label, 'Chết kho (không xuất)');
  assert.equal(dead.badgeClass, 'badge--danger');

  const url = Utils.getStockCardUrl(55, 3);
  assert.equal(url, '/warehouse/stock-card?productId=55&warehouseId=3');
});

test('S8-07: Ma trận phân quyền REPORT_INVENTORY, không role-based fallback', () => {
  const mockAuthAdmin = {
    hasRole: (r) => r === 'ROLE_ADMIN',
    hasPermission: () => false,
  };
  assert.equal(Utils.canViewInventoryReport(mockAuthAdmin), true);

  const mockAuthGranted = {
    hasRole: () => false,
    hasPermission: (p) => p === 'REPORT_INVENTORY',
  };
  assert.equal(Utils.canViewInventoryReport(mockAuthGranted), true);

  // Người dùng có vai trò Quản lý kho nhưng BỊ THU HỒI quyền REPORT_INVENTORY trong ma trận DB -> BỊ CHẶN!
  const mockAuthRevoked = {
    hasRole: (r) => r === 'ROLE_WAREHOUSE_MANAGER',
    hasPermission: () => false,
  };
  assert.equal(Utils.canViewInventoryReport(mockAuthRevoked), false);

  // Người dùng chỉ có quyền báo cáo tài chính REPORT_FINANCE không được xem báo cáo tồn kho
  const mockAuthFinanceOnly = {
    hasRole: () => false,
    hasPermission: (p) => p === 'REPORT_FINANCE',
  };
  assert.equal(Utils.canViewInventoryReport(mockAuthFinanceOnly), false);
});

test('S8-07: Ma trận phân quyền menu-config và route cho Tồn kho & Vòng quay (inventory-turnover)', () => {
  // Quyền REPORT_INVENTORY mở menu cho mọi vai trò được gán
  for (const role of ['ROLE_WAREHOUSE_MANAGER', 'ROLE_SALES_MANAGER', 'ROLE_ACCOUNTANT', 'ROLE_VIEWER']) {
    assert.ok(
      ids({ roles: [role], permissions: ['REPORT_INVENTORY'] }).includes('inventory-turnover'),
      `Role ${role} với quyền REPORT_INVENTORY phải thấy menu inventory-turnover`
    );
    assert.ok(
      !ids({ roles: [role], permissions: ['REPORT_SALES', 'REPORT_FINANCE'] }).includes('inventory-turnover'),
      `Role ${role} thiếu REPORT_INVENTORY không được thấy menu inventory-turnover`
    );
  }

  // Quản trị viên luôn thấy menu
  assert.ok(ids({ roles: ['ROLE_ADMIN'], permissions: [] }).includes('inventory-turnover'));

  // Đại lý không thấy menu báo cáo nội bộ
  assert.ok(!ids({ roles: ['ROLE_DEALER'], permissions: ['REPORT_INVENTORY'] }).includes('inventory-turnover'));

  // Điều hướng đúng route
  const page = menu.getPageForLocation(new URL('http://test/reports/inventory-turnover'));
  assert.equal(page.id, 'inventory-turnover');
  assert.equal(page.href, '/reports/inventory-turnover');
});

test('S8-07: Tuân thủ bảo mật và không lưu dữ liệu kinh doanh vào localStorage/sessionStorage', () => {
  const js = fs.readFileSync(path.join(__dirname, '../frontend/js/inventory-turnover.js'), 'utf8');
  assert.match(js, /Auth\.apiFetch\(["'`]\/reports\/inventory-turnover/);
  assert.match(js, /Auth\.apiFetch\(["'`]\/reports\/inventory-turnover\/export/);
  assert.doesNotMatch(js, /localStorage|sessionStorage/);
  assert.match(js, /auth:permissions-changed/);

  const css = fs.readFileSync(path.join(__dirname, '../frontend/css/inventory-turnover.css'), 'utf8');
  assert.doesNotMatch(css, /--app-content-|\.turnover-page\s*\{[^}]*\b(?:max-width|margin|padding)\s*:/);
});

