/**
 * tests/debt-statement.test.cjs
 * Bộ kiểm thử đơn vị cho chức năng S8-10: Sổ chi tiết công nợ đại lý (Frontend).
 */
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const DebtStatementUtils = require('../frontend/js/debt-statement-utils.js');

test('Statement downloads prefer RFC 5987 names over MIME words and preserve file extension', () => {
  assert.equal(DebtStatementUtils.exportFilename('attachment; filename="=?UTF-8?Q?So=5Fcong.pdf?="; filename*=UTF-8\'\'So_cong_%C4%91%E1%BA%A1i_l%C3%BD.pdf', 'fallback.pdf'), 'So_cong_đại_lý.pdf');
  assert.equal(DebtStatementUtils.exportFilename('attachment; filename="=?UTF-8?Q?So=5Fcong.pdf?="', 'fallback.pdf'), 'fallback.pdf');
  assert.equal(DebtStatementUtils.exportFilename('attachment; filename="statement.xlsx"', 'fallback.xlsx'), 'statement.xlsx');
  assert.equal(DebtStatementUtils.exportFilename("attachment; filename*=UTF-8''%ZZ.pdf", 'fallback.pdf'), 'fallback.pdf');
});

test('DebtStatementUtils: Định dạng tiền tệ VND chính xác, xử lý số âm và null/undefined', () => {
  assert.equal(DebtStatementUtils.formatMoney(null), '—');
  assert.equal(DebtStatementUtils.formatMoney(undefined), '—');
  assert.equal(DebtStatementUtils.formatMoney(''), '—');
  assert.equal(DebtStatementUtils.formatMoney(0), '0 ₫');
  assert.equal(DebtStatementUtils.formatMoney(1500000), '1.500.000 ₫');
  assert.equal(DebtStatementUtils.formatMoney('2500000'), '2.500.000 ₫');
  assert.equal(DebtStatementUtils.formatMoney(-750000), '-750.000 ₫');
  assert.equal(DebtStatementUtils.formatMoney('-1200000'), '-1.200.000 ₫');
});

test('DebtStatementUtils: Định dạng ngày và ngày giờ chuẩn Việt Nam', () => {
  assert.equal(DebtStatementUtils.formatDate('2026-10-15'), '15/10/2026');
  assert.equal(DebtStatementUtils.formatDate('2026-05-01'), '01/05/2026');
  assert.equal(DebtStatementUtils.formatDate(null), '—');

  assert.equal(DebtStatementUtils.formatDateTime('2026-10-15T14:30:00'), '15/10/2026 14:30');
  assert.equal(DebtStatementUtils.formatDateTime('2026-01-05T08:05:00'), '05/01/2026 08:05');
  assert.equal(DebtStatementUtils.formatDateTime(null), '—');
});

test('DebtStatementUtils: Xác thực khoảng ngày lọc from - to', () => {
  // Khoảng hợp lệ
  const valid = DebtStatementUtils.validateDateRange('2026-10-01', '2026-10-31');
  assert.equal(valid.valid, true);

  // Cùng ngày hợp lệ
  const sameDay = DebtStatementUtils.validateDateRange('2026-10-15', '2026-10-15');
  assert.equal(sameDay.valid, true);

  // from > to không hợp lệ
  const invalidOrder = DebtStatementUtils.validateDateRange('2026-10-31', '2026-10-01');
  assert.equal(invalidOrder.valid, false);
  assert.match(invalidOrder.error, /Ngày bắt đầu không được lớn hơn ngày kết thúc/);

  // Thiếu ngày
  const missing = DebtStatementUtils.validateDateRange('', '2026-10-31');
  assert.equal(missing.valid, false);

  // Sai định dạng
  const badFormat = DebtStatementUtils.validateDateRange('15-10-2026', '2026-10-31');
  assert.equal(badFormat.valid, false);
});

test('DebtStatementUtils: Phân loại loại chứng từ và badge hiển thị', () => {
  const order = DebtStatementUtils.getSourceTypeInfo('ORDER');
  assert.equal(order.label, 'Đơn hàng');
  assert.equal(order.badgeClass, 'badge--order');

  const payment = DebtStatementUtils.getSourceTypeInfo('PAYMENT');
  assert.equal(payment.label, 'Phiếu thu');
  assert.equal(payment.badgeClass, 'badge--payment');

  const adj = DebtStatementUtils.getSourceTypeInfo('INVOICE_ADJUSTMENT');
  assert.equal(adj.label, 'Điều chỉnh HĐ');
  assert.equal(adj.badgeClass, 'badge--adjustment');

  const unknown = DebtStatementUtils.getSourceTypeInfo('OTHER');
  assert.equal(unknown.label, 'OTHER');
  assert.equal(unknown.badgeClass, 'badge--default');
});

test('DebtStatementUtils: Kiểm tra ma trận phân quyền DB (không fallback role)', () => {
  // 1. Quản trị viên luôn có quyền
  assert.equal(DebtStatementUtils.canViewDebtStatement({
    roles: ['ROLE_ADMIN'],
    permissions: [],
  }), true);

  // 2. Người dùng có quyền FINANCE_VIEW_DEBT
  assert.equal(DebtStatementUtils.canViewDebtStatement({
    roles: ['ROLE_ACCOUNTANT'],
    permissions: ['FINANCE_VIEW_DEBT'],
  }), true);

  // 3. Người dùng có vai trò ROLE_ACCOUNTANT nhưng KHÔNG có quyền FINANCE_VIEW_DEBT
  // (Đã bị thu hồi trong ma trận DB) -> PHẢI BỊ TỪ CHỐI (Không role fallback)
  assert.equal(DebtStatementUtils.canViewDebtStatement({
    roles: ['ROLE_ACCOUNTANT'],
    permissions: ['REPORT_SALES'],
  }), false);

  // 4. Người dùng không có vai trò phù hợp và không có quyền
  assert.equal(DebtStatementUtils.canViewDebtStatement({
    roles: ['ROLE_DEALER'],
    permissions: ['ORDER_VIEW'],
  }), false);

  // 5. User null / undefined
  assert.equal(DebtStatementUtils.canViewDebtStatement(null), false);
  assert.equal(DebtStatementUtils.canViewDebtStatement(undefined), false);

  // 6. Nhiều vai trò và hợp nhất quyền
  assert.equal(DebtStatementUtils.canViewDebtStatement({
    roles: ['ROLE_SALES', 'ROLE_VIEWER'],
    permissions: ['PRODUCT_VIEW', 'FINANCE_VIEW_DEBT'],
  }), true);
});

test('Cấu hình route và Menu cho S8-10', () => {
  // Kiểm tra route trong serve_frontend.py
  const servePy = fs.readFileSync(path.join(__dirname, '../serve_frontend.py'), 'utf-8');
  assert.match(servePy, /"\/finance\/debt-statement":\s*"finance\/debt-statement\.html"/);

  // Kiểm tra menu item trong menu-config.js
  const menuConfig = fs.readFileSync(path.join(__dirname, '../frontend/js/menu-config.js'), 'utf-8');
  assert.match(menuConfig, /id:\s*"debt-statement"/);
  assert.match(menuConfig, /href:\s*"\/finance\/debt-statement"/);
  assert.match(menuConfig, /permission:\s*"FINANCE_VIEW_DEBT"/);

  // Kiểm tra file HTML và JS tồn tại
  assert.ok(fs.existsSync(path.join(__dirname, '../frontend/finance/debt-statement.html')));
  assert.ok(fs.existsSync(path.join(__dirname, '../frontend/js/debt-statement.js')));
  assert.ok(fs.existsSync(path.join(__dirname, '../frontend/css/debt-statement.css')));
});

test('Không sử dụng lưu trữ trình duyệt cho dữ liệu sổ công nợ', () => {
  const jsContent = fs.readFileSync(path.join(__dirname, '../frontend/js/debt-statement.js'), 'utf-8');
  const utilsContent = fs.readFileSync(path.join(__dirname, '../frontend/js/debt-statement-utils.js'), 'utf-8');

  // Đảm bảo không gọi localStorage hoặc sessionStorage để lưu dữ liệu
  const combined = jsContent + '\n' + utilsContent;
  const storageCalls = combined.match(/\b(localStorage|sessionStorage)\.(setItem|getItem|removeItem|clear)\b/g);
  assert.equal(storageCalls, null, 'Không được phép gọi API lưu trữ trình duyệt cho dữ liệu nghiệp vụ');
});

