'use strict';
/**
 * sales-targets.test.cjs — S8-04: Kiểm thử utils chỉ tiêu doanh số.
 *
 * Các ca kiểm thử:
 *  1. Định dạng tiền tệ và tính toán tiến độ
 *  2. Kiểm tra hợp lệ form đặt chỉ tiêu (bắt buộc lý do khi kỳ khoá)
 *  3. Kiểm tra hợp lệ lý do sửa riêng
 *  4. So sánh kỳ và tự động khoá kỳ đã qua
 *  5. Ma trận phân quyền DB — không fallback vai trò
 *  6. Route /sales-targets được phục vụ đúng (menu-config)
 *  7. Không dùng localStorage/sessionStorage
 */

const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const Utils = require('../frontend/js/sales-targets-utils.js');
const menuSource = fs.readFileSync(path.join(__dirname, '../frontend/js/menu-config.js'), 'utf8');
const menu = vm.runInNewContext(`${menuSource}\nMenuConfig;`, { URLSearchParams });
const ids = (user) => Array.from(menu.getMenuForUser(user), (item) => item.id);

/* ── TEST 1: Định dạng tiền và tính toán tiến độ ────────────────────── */
test('S8-04: Định dạng tiền tệ và tính toán tiến độ', () => {
  // formatMoney — null/empty trả về '0 đ', số 0 trả '0 ₫'
  assert.ok(Utils.formatMoney(0).startsWith('0'), 'formatMoney(0) bắt đầu bằng 0');
  assert.ok(Utils.formatMoney(null) === '0 đ');
  assert.ok(Utils.formatMoney('') === '0 đ');
  // Kiểm tra có dấu chấm phân nhóm
  const m1 = Utils.formatMoney(1500000);
  assert.ok(m1.includes('1.500.000'), `formatMoney(1500000) = ${m1}`);
  assert.ok(m1.includes('₫'), 'Phải có ký hiệu ₫');

  const m2 = Utils.formatMoney(500000000);
  assert.ok(m2.includes('500.000.000'), `formatMoney(500000000) = ${m2}`);

  // Số âm
  const mNeg = Utils.formatMoney(-1000);
  assert.ok(mNeg.startsWith('-'), 'Số âm phải có dấu trừ');

  // calcProgress
  assert.equal(Utils.calcProgress(0, 1000000), 0);
  assert.equal(Utils.calcProgress(500000, 1000000), 50);
  assert.equal(Utils.calcProgress(1000000, 1000000), 100);
  assert.equal(Utils.calcProgress(1500000, 1000000), 150);
  assert.equal(Utils.calcProgress(100, 0), 0, 'Chia cho 0 → 0');

  // progressLevel
  assert.equal(Utils.progressLevel(100), 'full');
  assert.equal(Utils.progressLevel(120), 'full');
  assert.equal(Utils.progressLevel(80), 'ok');
  assert.equal(Utils.progressLevel(50), 'caution');
  assert.equal(Utils.progressLevel(20), 'danger');
  assert.equal(Utils.progressLevel(0), 'danger');
});

/* ── TEST 2: Validate form đặt chỉ tiêu ────────────────────────────── */
test('S8-04: Kiểm tra hợp lệ form đặt/sửa chỉ tiêu doanh số', () => {
  // Hợp lệ cơ bản
  const valid = {
    employeeId: 'emp-1',
    period: '2026-10',
    targetAmount: '5000000',
    isLocked: false,
    reason: '',
  };
  const res = Utils.validateTargetDraft(valid);
  assert.equal(res.isValid, true, `Expect valid: ${res.errors.join('; ')}`);
  assert.equal(res.errors.length, 0);

  // Thiếu employeeId
  const noEmp = { ...valid, employeeId: '' };
  const r1 = Utils.validateTargetDraft(noEmp);
  assert.equal(r1.isValid, false);
  assert.ok(r1.errors.some(e => e.includes('nhân viên')), 'Phải báo thiếu nhân viên');

  // Kỳ không hợp lệ
  const badPeriod = { ...valid, period: '10/2026' };
  const r2 = Utils.validateTargetDraft(badPeriod);
  assert.equal(r2.isValid, false);
  assert.ok(r2.errors.some(e => e.includes('Kỳ tháng')), 'Phải báo kỳ không hợp lệ');

  // Chỉ tiêu âm
  const negAmount = { ...valid, targetAmount: '-100' };
  const r3 = Utils.validateTargetDraft(negAmount);
  assert.equal(r3.isValid, false);
  assert.ok(r3.errors.some(e => e.includes('lớn hơn 0')), 'Phải báo chỉ tiêu âm');

  // Vượt trần 999 tỷ
  const bigAmount = { ...valid, targetAmount: '1000000000000' };
  const r4 = Utils.validateTargetDraft(bigAmount);
  assert.equal(r4.isValid, false);
  assert.ok(r4.errors.some(e => e.includes('999 tỷ')), 'Phải báo vượt trần');

  // Kỳ đã khoá, không nhập lý do → invalid
  const lockedNoReason = { ...valid, isLocked: true, reason: '' };
  const r5 = Utils.validateTargetDraft(lockedNoReason);
  assert.equal(r5.isValid, false);
  assert.ok(r5.errors.some(e => e.includes('lý do')), 'Phải báo thiếu lý do khi kỳ khoá');

  // Kỳ đã khoá, lý do quá dài
  const lockedLongReason = { ...valid, isLocked: true, reason: 'a'.repeat(501) };
  const r6 = Utils.validateTargetDraft(lockedLongReason);
  assert.equal(r6.isValid, false);
  assert.ok(r6.errors.some(e => e.includes('500')), 'Phải báo lý do quá dài');

  // Kỳ đã khoá, có lý do hợp lệ → valid
  const lockedWithReason = { ...valid, isLocked: true, reason: 'Điều chỉnh theo thực tế kinh doanh quý.' };
  const r7 = Utils.validateTargetDraft(lockedWithReason);
  assert.equal(r7.isValid, true, `Expect valid: ${r7.errors.join('; ')}`);
});

/* ── TEST 3: Validate lý do sửa riêng ──────────────────────────────── */
test('S8-04: Validate lý do sửa chỉ tiêu kỳ đã khoá (validateEditReason)', () => {
  const r1 = Utils.validateEditReason('');
  assert.equal(r1.isValid, false);
  assert.ok(r1.error.includes('Bắt buộc'), 'Lý do rỗng → báo bắt buộc');

  const r2 = Utils.validateEditReason('   ');
  assert.equal(r2.isValid, false);

  const r3 = Utils.validateEditReason('a'.repeat(501));
  assert.equal(r3.isValid, false);
  assert.ok(r3.error.includes('500'), 'Lý do quá dài → báo giới hạn');

  const r4 = Utils.validateEditReason('Lý do hợp lệ');
  assert.equal(r4.isValid, true);
  assert.equal(r4.error, '');
});

/* ── TEST 4: So sánh và khoá kỳ ────────────────────────────────────── */
test('S8-04: So sánh kỳ và khoá tự động kỳ đã qua', () => {
  const NOW = '2026-10';

  assert.equal(Utils.comparePeriod('2026-09', NOW), 'past');
  assert.equal(Utils.comparePeriod('2026-10', NOW), 'current');
  assert.equal(Utils.comparePeriod('2026-11', NOW), 'future');
  assert.equal(Utils.comparePeriod('2025-01', NOW), 'past');
  assert.equal(Utils.comparePeriod('2027-12', NOW), 'future');

  assert.equal(Utils.isPeriodLocked('2026-09', NOW), true, 'Tháng trước → khoá');
  assert.equal(Utils.isPeriodLocked('2026-10', NOW), false, 'Tháng hiện tại → không khoá');
  assert.equal(Utils.isPeriodLocked('2026-11', NOW), false, 'Tháng tương lai → không khoá');

  // isValidPeriod
  assert.equal(Utils.isValidPeriod('2026-10'), true);
  assert.equal(Utils.isValidPeriod('2026-1'), false, 'Tháng phải 2 chữ số');
  assert.equal(Utils.isValidPeriod('26-10'), false, 'Năm phải 4 chữ số');
  assert.equal(Utils.isValidPeriod('2026-13'), false, 'Tháng 13 không hợp lệ');
  assert.equal(Utils.isValidPeriod('2026-00'), false, 'Tháng 00 không hợp lệ');
  assert.equal(Utils.isValidPeriod(''), false);
  assert.equal(Utils.isValidPeriod(null), false);
  assert.equal(Utils.isValidPeriod('10/2026'), false);

  // periodLabel
  assert.equal(Utils.periodLabel('2026-10'), 'Tháng 10/2026');
  assert.equal(Utils.periodLabel('2026-01'), 'Tháng 1/2026');
  assert.equal(Utils.periodLabel(''), '');

  // periodRelLabel
  assert.ok(Utils.periodRelLabel('past').includes('khoá'));
  assert.ok(Utils.periodRelLabel('current').includes('hiện tại'));
  assert.ok(Utils.periodRelLabel('future').length > 0);
});

/* ── TEST 5: Ma trận phân quyền DB — không fallback vai trò ─────────── */
test('S8-04: Ma trận phân quyền DB — không fallback vai trò', () => {
  const ADMIN = { roles: ['ROLE_ADMIN'], permissions: [] };
  const MANAGER_WITH_PERM = {
    roles: ['ROLE_SALES_MANAGER'],
    permissions: ['REPORT_SALES', 'ORDER_APPROVE'],
  };
  const SALES_WITH_OWN = {
    roles: ['ROLE_SALES'],
    permissions: ['REPORT_SALES'],
  };
  const SALES_NO_PERM = {
    roles: ['ROLE_SALES'],
    permissions: [],
  };
  const VIEWER = {
    roles: ['ROLE_VIEWER'],
    permissions: ['REPORT_SALES'],
  };
  const NO_PERM = {
    roles: ['ROLE_WAREHOUSE_STAFF'],
    permissions: [],
  };

  // Admin luôn thấy menu
  assert.ok(ids(ADMIN).includes('sales-targets'), 'ADMIN phải thấy chỉ tiêu');

  // Manager có đúng quyền
  assert.ok(ids(MANAGER_WITH_PERM).includes('sales-targets'), 'Manager có REPORT_SALES phải thấy');

  // Nhân viên có quyền VIEW_OWN
  assert.ok(ids(SALES_WITH_OWN).includes('sales-targets'), 'Nhân viên có REPORT_SALES phải thấy');

  // Nhân viên KHÔNG có quyền → không thấy
  assert.ok(!ids(SALES_NO_PERM).includes('sales-targets'), 'Nhân viên không có quyền → ẩn menu');

  // VIEWER có VIEW → thấy
  assert.ok(ids(VIEWER).includes('sales-targets'), 'VIEWER có REPORT_SALES phải thấy');

  // Không có quyền nào → ẩn
  assert.ok(!ids(NO_PERM).includes('sales-targets'), 'Không có quyền → ẩn menu');

  // Không dùng role fallback: ROLE_SALES_MANAGER không có quyền → ẩn
  const MANAGER_NO_PERM = {
    roles: ['ROLE_SALES_MANAGER'],
    permissions: [],
  };
  assert.ok(!ids(MANAGER_NO_PERM).includes('sales-targets'), 'ROLE_SALES_MANAGER không có quyền → không thấy (không fallback)');
});

/* ── TEST 6: Route /sales-targets được định nghĩa trong menu-config ─── */
test('S8-04: Route /sales-targets được phục vụ bởi menu-config', () => {
  const item = menu.menuItems.find(i => i.id === 'sales-targets');
  assert.ok(item, 'Phải có menu item sales-targets');
  assert.equal(item.href, '/sales-targets', 'href phải là /sales-targets');
  assert.ok(item.section === 'Kinh doanh', 'Phải thuộc section Kinh doanh');
  assert.ok(Array.isArray(item.permissions), 'Phải dùng permissions[] (không dùng permission đơn)');
  assert.ok(item.permissions.includes('REPORT_SALES'), 'Phải có REPORT_SALES');
  assert.ok(item.permissions.includes('ORDER_APPROVE'), 'Phải có ORDER_APPROVE');
  assert.ok(item.permissions.includes('REPORT_SALES'), 'Phải có REPORT_SALES');
});

/* ── TEST 7: Không dùng localStorage/sessionStorage ─────────────────── */
test('S8-04: Không lưu dữ liệu kinh doanh vào browser storage', () => {
  const jsPath = path.join(__dirname, '../frontend/js/sales-targets.js');
  const jsContent = fs.readFileSync(jsPath, 'utf8');
  const storagePatterns = [/localStorage\.setItem/g, /sessionStorage\.setItem/g];
  for (const pattern of storagePatterns) {
    const matches = jsContent.match(pattern);
    assert.ok(!matches, `Không được dùng ${pattern.source} để lưu dữ liệu kinh doanh`);
  }

  const utilsPath = path.join(__dirname, '../frontend/js/sales-targets-utils.js');
  const utilsContent = fs.readFileSync(utilsPath, 'utf8');
  for (const pattern of storagePatterns) {
    const matches = utilsContent.match(pattern);
    assert.ok(!matches, `Utils không được dùng ${pattern.source}`);
  }
});
