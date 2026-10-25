/**
 * tests/debt-reconciliation.test.cjs
 * Bộ kiểm thử đơn vị cho chức năng S7-06: Biên bản đối chiếu công nợ định kỳ (Frontend).
 */
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const DebtReconciliationUtils = require('../frontend/js/debt-reconciliation-utils.js');

test('DebtReconciliationUtils: Định dạng tiền tệ VND chính xác, xử lý số âm và null/undefined', () => {
  assert.equal(DebtReconciliationUtils.formatMoney(null), '—');
  assert.equal(DebtReconciliationUtils.formatMoney(undefined), '—');
  assert.equal(DebtReconciliationUtils.formatMoney(''), '—');
  assert.equal(DebtReconciliationUtils.formatMoney(0), '0 ₫');
  assert.equal(DebtReconciliationUtils.formatMoney(10000000), '10.000.000 ₫');
  assert.equal(DebtReconciliationUtils.formatMoney('15500000'), '15.500.000 ₫');
  assert.equal(DebtReconciliationUtils.formatMoney(-2000000), '-2.000.000 ₫');
  assert.equal(DebtReconciliationUtils.formatMoney('-500000'), '-500.000 ₫');
});

test('DebtReconciliationUtils: Định dạng ngày và ngày giờ chuẩn Việt Nam', () => {
  assert.equal(DebtReconciliationUtils.formatDate('2026-10-31'), '31/10/2026');
  assert.equal(DebtReconciliationUtils.formatDate('2026-01-01'), '01/01/2026');
  assert.equal(DebtReconciliationUtils.formatDate(null), '—');

  assert.equal(DebtReconciliationUtils.formatDateTime('2026-10-20T10:30:00'), '20/10/2026 10:30');
  assert.equal(DebtReconciliationUtils.formatDateTime('2026-05-15T09:15:00'), '15/05/2026 09:15');
  assert.equal(DebtReconciliationUtils.formatDateTime(null), '—');
});

test('DebtReconciliationUtils: Xác thực khoảng ngày đối chiếu from - to', () => {
  const valid = DebtReconciliationUtils.validateDateRange('2026-10-01', '2026-10-31');
  assert.equal(valid.valid, true);

  const sameDay = DebtReconciliationUtils.validateDateRange('2026-10-15', '2026-10-15');
  assert.equal(sameDay.valid, true);

  const invalidOrder = DebtReconciliationUtils.validateDateRange('2026-10-31', '2026-10-01');
  assert.equal(invalidOrder.valid, false);
  assert.match(invalidOrder.error, /Ngày bắt đầu không được lớn hơn ngày kết thúc/);

  const missing = DebtReconciliationUtils.validateDateRange('', '2026-10-31');
  assert.equal(missing.valid, false);

  const badYear = DebtReconciliationUtils.validateDateRange('0999-01-01', '2026-10-31');
  assert.equal(badYear.valid, false);
});

test('DebtReconciliationUtils: Xác thực và tính toán phản hồi lệch của đại lý', () => {
  // Trường hợp lệch: Hệ thống 10.000.000, Đại lý báo 8.000.000 -> Chênh lệch 2.000.000
  const diffResult = DebtReconciliationUtils.validateDiscrepancyFeedback(10000000, 8000000, 'Chưa ghi nhận phiếu thu PT-001 ngày 25/10');
  assert.equal(diffResult.valid, true);
  assert.equal(diffResult.systemBalance, 10000000);
  assert.equal(diffResult.dealerClaimedAmount, 8000000);
  assert.equal(diffResult.difference, 2000000);
  assert.equal(diffResult.isMatched, false);
  assert.equal(diffResult.reason, 'Chưa ghi nhận phiếu thu PT-001 ngày 25/10');

  // Trường hợp khớp đúng: Hệ thống 10.000.000, Đại lý báo 10.000.000 -> Chênh lệch 0
  const matchResult = DebtReconciliationUtils.validateDiscrepancyFeedback(10000000, 10000000, 'Số liệu hoàn toàn khớp đúng');
  assert.equal(matchResult.valid, true);
  assert.equal(matchResult.difference, 0);
  assert.equal(matchResult.isMatched, true);

  // Lỗi thiếu số tiền đại lý
  const missingAmt = DebtReconciliationUtils.validateDiscrepancyFeedback(10000000, '', 'Lý do');
  assert.equal(missingAmt.valid, false);
  assert.match(missingAmt.error, /Vui lòng nhập số dư công nợ/);

  // Lỗi thiếu lý do
  const missingReason = DebtReconciliationUtils.validateDiscrepancyFeedback(10000000, 8000000, '   ');
  assert.equal(missingReason.valid, false);
  assert.match(missingReason.error, /Vui lòng nhập lý do/);

  // Lỗi lý do quá 500 ký tự
  const longReason = 'A'.repeat(501);
  const tooLong = DebtReconciliationUtils.validateDiscrepancyFeedback(10000000, 8000000, longReason);
  assert.equal(tooLong.valid, false);
  assert.match(tooLong.error, /không được vượt quá 500 ký tự/);
});

test('DebtReconciliationUtils: Kiểm tra ma trận phân quyền DB (không fallback role)', () => {
  // 1. Quản trị viên luôn có quyền
  assert.equal(DebtReconciliationUtils.canAccessReconciliation({
    roles: ['ROLE_ADMIN'],
    permissions: [],
  }), true);

  // 2. Kế toán có quyền FINANCE_VIEW_DEBT
  assert.equal(DebtReconciliationUtils.canAccessReconciliation({
    roles: ['ROLE_ACCOUNTANT'],
    permissions: ['FINANCE_VIEW_DEBT'],
  }), true);

  // 3. Đại lý có quyền FINANCE_VIEW_DEBT
  assert.equal(DebtReconciliationUtils.canAccessReconciliation({
    roles: ['ROLE_DEALER'],
    permissions: ['FINANCE_VIEW_DEBT', 'ORDER_VIEW'],
  }), true);

  // 4. Kế toán bị thu hồi quyền FINANCE_VIEW_DEBT trong ma trận DB -> BỊ TỪ CHỐI (Không role fallback)
  assert.equal(DebtReconciliationUtils.canAccessReconciliation({
    roles: ['ROLE_ACCOUNTANT'],
    permissions: ['INVOICE_VIEW'],
  }), false);

  // 5. Đại lý không có quyền FINANCE_VIEW_DEBT -> BỊ TỪ CHỐI
  assert.equal(DebtReconciliationUtils.canAccessReconciliation({
    roles: ['ROLE_DEALER'],
    permissions: ['ORDER_VIEW'],
  }), false);

  // 6. User null / undefined
  assert.equal(DebtReconciliationUtils.canAccessReconciliation(null), false);
  assert.equal(DebtReconciliationUtils.canAccessReconciliation(undefined), false);

  // 7. Nhận diện đại lý
  assert.equal(DebtReconciliationUtils.isDealerUser({ roles: ['ROLE_DEALER'] }), true);
  assert.equal(DebtReconciliationUtils.isDealerUser({ roles: ['ROLE_ADMIN', 'ROLE_DEALER'] }), false);
  assert.equal(DebtReconciliationUtils.isDealerUser({ roles: ['ROLE_ACCOUNTANT'] }), false);
});

test('Cấu hình route và Menu cho S7-06', () => {
  const servePy = fs.readFileSync(path.join(__dirname, '../serve_frontend.py'), 'utf-8');
  assert.match(servePy, /"\/finance\/debt-reconciliation":\s*"finance\/debt-reconciliation\.html"/);

  const menuConfig = fs.readFileSync(path.join(__dirname, '../frontend/js/menu-config.js'), 'utf-8');
  assert.match(menuConfig, /id:\s*"debt-reconciliation"/);
  assert.match(menuConfig, /href:\s*"\/finance\/debt-reconciliation"/);
  assert.match(menuConfig, /permission:\s*"FINANCE_VIEW_DEBT"/);
  assert.match(menuConfig, /\["dashboard".*?"debt-reconciliation".*?\]\.includes\(item\.id\)/);

  assert.ok(fs.existsSync(path.join(__dirname, '../frontend/finance/debt-reconciliation.html')));
  assert.ok(fs.existsSync(path.join(__dirname, '../frontend/js/debt-reconciliation.js')));
  assert.ok(fs.existsSync(path.join(__dirname, '../frontend/css/debt-reconciliation.css')));
});

test('Không sử dụng lưu trữ trình duyệt cho dữ liệu đối chiếu công nợ', () => {
  const jsContent = fs.readFileSync(path.join(__dirname, '../frontend/js/debt-reconciliation.js'), 'utf-8');
  const utilsContent = fs.readFileSync(path.join(__dirname, '../frontend/js/debt-reconciliation-utils.js'), 'utf-8');

  const combined = jsContent + '\n' + utilsContent;
  const storageCalls = combined.match(/\b(localStorage|sessionStorage)\.(setItem|getItem|removeItem|clear)\b/g);
  assert.equal(storageCalls, null, 'Không được phép gọi API lưu trữ trình duyệt cho dữ liệu nghiệp vụ');
});
