/**
 * tests/stock-ledger.test.cjs
 * Bộ kiểm thử đơn vị cho chức năng S8-08: Xuất báo cáo tồn kho ra tệp Excel (Frontend).
 */
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const StockLedgerUtils = require('../frontend/js/stock-ledger-utils.js');
const menuSource = fs.readFileSync(path.join(__dirname, '../frontend/js/menu-config.js'), 'utf8');
const menu = vm.runInNewContext(`${menuSource}\nMenuConfig;`, { URLSearchParams });
const ids = user => Array.from(menu.getMenuForUser(user), item => item.id);

test('S8-08: StockLedgerUtils.buildExportUrl xây dựng URL API xuất Excel đúng chuẩn bộ lọc', () => {
  // Không có bộ lọc nào
  assert.equal(StockLedgerUtils.buildExportUrl({}), '/warehouse/stock-ledger/export');
  assert.equal(StockLedgerUtils.buildExportUrl(), '/warehouse/stock-ledger/export');

  // Lọc theo kho đơn lẻ
  assert.equal(StockLedgerUtils.buildExportUrl({ warehouseId: 1 }), '/warehouse/stock-ledger/export?warehouseId=1');

  // Lọc theo nhiều tham số kết hợp
  const fullUrl = StockLedgerUtils.buildExportUrl({
    warehouseId: 2,
    categoryId: 10,
    keyword: 'SKU-001',
    stockStatus: 'IN_STOCK',
    belowMinimum: true,
  });
  assert.ok(fullUrl.startsWith('/warehouse/stock-ledger/export?'));
  const params = new URLSearchParams(fullUrl.replace('/warehouse/stock-ledger/export?', ''));
  assert.equal(params.get('warehouseId'), '2');
  assert.equal(params.get('categoryId'), '10');
  assert.equal(params.get('keyword'), 'SKU-001');
  assert.equal(params.get('stockStatus'), 'IN_STOCK');
  assert.equal(params.get('belowMinimum'), 'true');

  // Lọc với từ khóa có khoảng trắng thừa và ký tự đặc biệt
  const trimmedUrl = StockLedgerUtils.buildExportUrl({ keyword: '  Bánh kẹo %  ' });
  assert.equal(trimmedUrl, '/warehouse/stock-ledger/export?keyword=B%C3%A1nh+k%E1%BA%B9o+%25');

  // Bỏ qua các trường rỗng
  const emptyFiltersUrl = StockLedgerUtils.buildExportUrl({
    warehouseId: '',
    categoryId: null,
    keyword: '   ',
    stockStatus: undefined,
    belowMinimum: false,
  });
  assert.equal(emptyFiltersUrl, '/warehouse/stock-ledger/export');
});

test('S8-08: StockLedgerUtils.parseContentDispositionFilename trích xuất tên tệp từ header Content-Disposition', () => {
  // Chuẩn RFC 5987 / RFC 6266 filename*=UTF-8''...
  const rfc5987 = "attachment; filename*=UTF-8''So-ton-kho_KHO-A_2026-10-25.xlsx";
  assert.equal(StockLedgerUtils.parseContentDispositionFilename(rfc5987), 'So-ton-kho_KHO-A_2026-10-25.xlsx');

  // Tên file có dấu tiếng Việt được URL-encode
  const encodedVn = "attachment; filename*=UTF-8''So-ton-kho_Kho-H%C3%A0-N%E1%BB%99i_2026-10-25.xlsx";
  assert.equal(StockLedgerUtils.parseContentDispositionFilename(encodedVn), 'So-ton-kho_Kho-Hà-Nội_2026-10-25.xlsx');

  // Chuẩn thông thường filename="..."
  const standard = 'attachment; filename="So-ton-kho_KHO-HCM_2026-10-25.xlsx"';
  assert.equal(StockLedgerUtils.parseContentDispositionFilename(standard), 'So-ton-kho_KHO-HCM_2026-10-25.xlsx');

  // Cả hai: filename*=UTF-8 được ưu tiên hơn filename fallback
  const combined = 'attachment; filename="fallback.xlsx"; filename*=UTF-8\'\'So-ton-kho_KHO-HN-KHO-HCM_2026-10-25.xlsx';
  assert.equal(StockLedgerUtils.parseContentDispositionFilename(combined), 'So-ton-kho_KHO-HN-KHO-HCM_2026-10-25.xlsx');

  // Header rỗng hoặc không có filename
  assert.equal(StockLedgerUtils.parseContentDispositionFilename(null), null);
  assert.equal(StockLedgerUtils.parseContentDispositionFilename(''), null);
  assert.equal(StockLedgerUtils.parseContentDispositionFilename('attachment'), null);
});

test('S8-08: StockLedgerUtils.buildFallbackFilename tạo tên tệp chứa mã kho và ngày xuất', () => {
  // Kho cụ thể và ngày xuất
  const name1 = StockLedgerUtils.buildFallbackFilename('KHO-HN', '2026-10-25');
  assert.equal(name1, 'So-ton-kho_KHO-HN_2026-10-25.xlsx');

  // Nhiều kho hoặc mã kho có ký tự đặc biệt
  const name2 = StockLedgerUtils.buildFallbackFilename('KHO HN / HCM', '2026-10-25');
  assert.equal(name2, 'So-ton-kho_KHO_HN___HCM_2026-10-25.xlsx');

  // Không chỉ định kho -> TAT-CA-KHO
  const name3 = StockLedgerUtils.buildFallbackFilename('', '2026-10-25');
  assert.equal(name3, 'So-ton-kho_TAT-CA-KHO_2026-10-25.xlsx');

  // Tên tệp luôn có đuôi .xlsx và chứa ngày dạng YYYY-MM-DD
  const name4 = StockLedgerUtils.buildFallbackFilename('KHO-A');
  assert.match(name4, /^So-ton-kho_KHO-A_\d{4}-\d{2}-\d{2}\.xlsx$/);
});

test('S8-08: Ma trận phân quyền WAREHOUSE_VIEW cho Tồn kho, không role fallback', () => {
  // 1. Quản trị viên (ROLE_ADMIN) luôn có quyền xem và xuất tồn kho
  assert.equal(StockLedgerUtils.canViewStockLedger({
    hasRole: r => r === 'ROLE_ADMIN',
    hasPermission: () => false,
  }), true);

  // 2. Người dùng có quyền WAREHOUSE_VIEW được phép
  assert.equal(StockLedgerUtils.canViewStockLedger({
    hasRole: () => false,
    hasPermission: p => p === 'WAREHOUSE_VIEW',
  }), true);

  // 3. Người dùng có vai trò Quản lý kho / Thủ kho nhưng KHÔNG có WAREHOUSE_VIEW trong ma trận -> BỊ CHẶN
  assert.equal(StockLedgerUtils.canViewStockLedger({
    hasRole: r => r === 'ROLE_WAREHOUSE_MANAGER',
    hasPermission: () => false,
  }), false);

  assert.equal(StockLedgerUtils.canViewStockLedger({
    hasRole: r => r === 'ROLE_WAREHOUSE_STAFF',
    hasPermission: () => false,
  }), false);

  assert.equal(StockLedgerUtils.canViewStockLedger({
    hasRole: r => r === 'ROLE_ACCOUNTANT',
    hasPermission: () => false,
  }), false);

  // 4. Phiên người dùng bị thu hồi quyền lập tức trả về false
  let currentPermissions = ['WAREHOUSE_VIEW'];
  const liveAuth = {
    hasRole: () => false,
    hasPermission: p => currentPermissions.includes(p),
  };
  assert.equal(StockLedgerUtils.canViewStockLedger(liveAuth), true);
  currentPermissions = []; // Thu hồi quyền
  assert.equal(StockLedgerUtils.canViewStockLedger(liveAuth), false);
});

test('S8-08: Cấu hình Menu và Navigation cho trang Tồn kho (/warehouse/inventory)', () => {
  // Có quyền WAREHOUSE_VIEW -> thấy menu "warehouse" (Tồn kho)
  assert.ok(ids({ roles: ['ROLE_VIEWER'], permissions: ['WAREHOUSE_VIEW'] }).includes('warehouse'));
  assert.ok(ids({ roles: ['ROLE_WAREHOUSE_STAFF'], permissions: ['WAREHOUSE_VIEW'] }).includes('warehouse'));
  assert.ok(ids({ roles: ['ROLE_WAREHOUSE_MANAGER'], permissions: ['WAREHOUSE_VIEW'] }).includes('warehouse'));

  // Mất quyền WAREHOUSE_VIEW -> menu "warehouse" bị ẩn
  assert.ok(!ids({ roles: ['ROLE_WAREHOUSE_STAFF'], permissions: [] }).includes('warehouse'));
  assert.ok(!ids({ roles: ['ROLE_WAREHOUSE_MANAGER'], permissions: [] }).includes('warehouse'));
  assert.ok(!ids({ roles: ['ROLE_VIEWER'], permissions: [] }).includes('warehouse'));

  // ROLE_ADMIN luôn thấy menu
  assert.ok(ids({ roles: ['ROLE_ADMIN'], permissions: [] }).includes('warehouse'));

  // ROLE_DEALER không thấy menu kho nội bộ
  assert.ok(!ids({ roles: ['ROLE_DEALER'], permissions: ['WAREHOUSE_VIEW'] }).includes('warehouse'));

  // Điều hướng đúng route
  const page = menu.getPageForLocation(new URL('http://test/warehouse/inventory'));
  assert.equal(page.id, 'warehouse');
  assert.equal(page.href, '/warehouse/inventory');
});

test('S8-08: Frontend tuân thủ bảo mật, kết nối API xuất và không dùng localStorage/sessionStorage cho tồn kho', () => {
  const js = fs.readFileSync(path.join(__dirname, '../frontend/js/stock-ledger.js'), 'utf8');

  // Gọi API xuất máy chủ /warehouse/stock-ledger/export
  assert.match(js, /\/warehouse\/stock-ledger\/export/);
  assert.match(js, /Auth\.apiFetch\(/);

  // Đọc header Content-Disposition để lấy tên file từ server
  assert.match(js, /Content-Disposition/i);

  // Lắng nghe sự kiện thu hồi quyền auth:permissions-changed
  assert.match(js, /auth:permissions-changed/);

  // Tuyệt đối không lưu dữ liệu tồn kho vào localStorage hoặc sessionStorage
  assert.doesNotMatch(js, /localStorage\./);
  assert.doesNotMatch(js, /sessionStorage\.(?:setItem|rows|content)/);

  // Không hardcode bypass vai trò
  assert.doesNotMatch(js, /ROLE_ADMIN.*===.*role/);
});

test('S8-08: Giữ nguyên ba cột tồn (Tồn thực tế, Đang giữ chỗ, Tồn khả dụng) trên UI và Backend', () => {
  // Kiểm tra UI HTML
  const html = fs.readFileSync(path.join(__dirname, '../frontend/warehouse/inventory.html'), 'utf8');
  assert.match(html, /<th[^>]*>Tồn thực tế<\/th>/);
  assert.match(html, /<th[^>]*>Đang giữ chỗ<\/th>/);
  assert.match(html, /<th[^>]*>Tồn khả dụng<\/th>/);

  // Kiểm tra backend export headers
  const javaService = fs.readFileSync(
    path.join(__dirname, '../backend/src/main/java/com/quanlybanhang/service/StockLedgerExcelService.java'),
    'utf8'
  );
  assert.match(javaService, /"Tồn thực tế"/);
  assert.match(javaService, /"Đang giữ chỗ"/);
  assert.match(javaService, /"Tồn khả dụng"/);
});

test('S8-08: CSS tuân thủ khung giao diện chung, không gây tràn ngang hay ghi đè layout shell', () => {
  const css = fs.readFileSync(path.join(__dirname, '../frontend/css/stock-ledger.css'), 'utf8');
  // Không có max-width cố định ngoài khung shared
  assert.doesNotMatch(css, /\.stock-page\s*\{[^}]*\bmax-width\s*:\s*\d+/);
  // Bảng có thanh cuộn riêng biệt
  assert.match(css, /\.stock-table-scroll\s*\{[^}]*overflow-x\s*:\s*auto/);
});

