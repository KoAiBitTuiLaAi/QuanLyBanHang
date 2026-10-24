/**
 * tests/debt-payments.test.cjs
 * Bộ kiểm thử đơn vị cho chức năng S7-02: Ghi nhận thanh toán và đối trừ vào công nợ (Frontend).
 */
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const Utils = require('../frontend/js/debt-payments-utils.js');

test('S7-02: Định dạng tiền tệ VND và phân tích số tiền an toàn', () => {
  assert.equal(Utils.formatMoney(15000000).replace(/\s/g, ' '), '15.000.000 ₫');
  assert.equal(Utils.formatMoney(0).replace(/\s/g, ' '), '0 ₫');
  assert.equal(Utils.formatMoney(null), '—');
  assert.equal(Utils.formatMoney(undefined), '—');
  assert.equal(Utils.formatMoney(''), '—');

  assert.equal(Utils.parseMoney('15.000.000'), 15000000);
  assert.equal(Utils.parseMoney('15000000'), 15000000);
  assert.equal(Utils.parseMoney('15.000.000,50'), 15000000.5);
  assert.equal(Utils.parseMoney(500000), 500000);
  assert.equal(Utils.parseMoney(''), 0);
  assert.equal(Utils.parseMoney(null), 0);
});

test('S7-02: Định dạng ngày và ngày giờ chuẩn Việt Nam', () => {
  assert.equal(Utils.formatDate('2026-10-24'), '24/10/2026');
  assert.equal(Utils.formatDate('2026-05-02'), '02/05/2026');
  assert.equal(Utils.formatDate(null), '—');

  assert.equal(Utils.formatDateTime('2026-10-24T14:30:00'), '24/10/2026 14:30');
  assert.equal(Utils.formatDateTime(null), '—');
});

test('S7-02: Tiêu chí 1 & 4 - Ghi số tiền, hình thức thu, ngày thu, người thu và chặn thu vượt tổng nợ', () => {
  const validFifoPayload = {
    dealerId: 12,
    amount: 10000000,
    paymentMethod: 'CHUYEN_KHOAN',
    paymentDate: '2026-10-24',
    recordedBy: 'Kế toán Minh',
    referenceCode: 'UNC-20261024-01',
    note: 'Thu nợ đợt 1',
    autoAllocate: true,
  };

  // 1. Hợp lệ khi số tiền nhỏ hơn tổng nợ
  const res1 = Utils.validatePaymentPayload(validFifoPayload, 20000000);
  assert.equal(res1.isValid, true);
  assert.equal(res1.errors.length, 0);

  // 2. Chặn thu vượt tổng công nợ còn lại (Tiêu chí 4)
  const resOverDebt = Utils.validatePaymentPayload(validFifoPayload, 8000000);
  assert.equal(resOverDebt.isValid, false);
  assert.match(resOverDebt.errors.join(' '), /không được vượt quá tổng công nợ còn lại của đại lý/);

  // 3. Thiếu đại lý
  const resNoDealer = Utils.validatePaymentPayload({ ...validFifoPayload, dealerId: null }, 20000000);
  assert.equal(resNoDealer.isValid, false);
  assert.match(resNoDealer.errors.join(' '), /chọn đại lý thanh toán/);

  // 4. Số tiền <= 0
  const resZeroAmount = Utils.validatePaymentPayload({ ...validFifoPayload, amount: 0 }, 20000000);
  assert.equal(resZeroAmount.isValid, false);
  assert.match(resZeroAmount.errors.join(' '), /lớn hơn 0/);

  // 5. Hình thức thu không hợp lệ
  const resBadMethod = Utils.validatePaymentPayload({ ...validFifoPayload, paymentMethod: 'INVALID' }, 20000000);
  assert.equal(resBadMethod.isValid, false);
  assert.match(resBadMethod.errors.join(' '), /hình thức thu tiền hợp lệ/);

  // 6. Ngày thu sai định dạng
  const resBadDate = Utils.validatePaymentPayload({ ...validFifoPayload, paymentDate: '24-10-2026' }, 20000000);
  assert.equal(resBadDate.isValid, false);
  assert.match(resBadDate.errors.join(' '), /YYYY-MM-DD/);
});

test('S7-02: Tiêu chí 2 - Đối trừ tự động FIFO vs Chỉ định đối trừ vào hoá đơn cụ thể', () => {
  // 1. Chế độ FIFO (autoAllocate = true) không cần mảng allocations
  const fifoPayload = {
    dealerId: 10,
    amount: 5000000,
    paymentMethod: 'TIEN_MAT',
    paymentDate: '2026-10-24',
    autoAllocate: true,
  };
  const resFifo = Utils.validatePaymentPayload(fifoPayload, 10000000);
  assert.equal(resFifo.isValid, true);

  // 2. Chế độ thủ công (autoAllocate = false) nhưng không có allocations -> BỊ TỪ CHỐI
  const manualNoAlloc = {
    ...fifoPayload,
    autoAllocate: false,
    allocations: [],
  };
  const resNoAlloc = Utils.validatePaymentPayload(manualNoAlloc, 10000000);
  assert.equal(resNoAlloc.isValid, false);
  assert.match(resNoAlloc.errors.join(' '), /chỉ định ít nhất một hoá đơn/);

  // 3. Chế độ thủ công: Tổng phân bổ KHÔNG KHỚP số tiền phiếu thu (thu 5tr nhưng phân bổ 4tr) -> BỊ TỪ CHỐI
  const manualMismatch = {
    ...fifoPayload,
    autoAllocate: false,
    allocations: [
      { orderId: 101, amount: 4000000, orderRemainingAmount: 6000000 },
    ],
  };
  const resMismatch = Utils.validatePaymentPayload(manualMismatch, 10000000);
  assert.equal(resMismatch.isValid, false);
  assert.match(resMismatch.errors.join(' '), /phải bằng chính xác số tiền phiếu thu/);

  // 4. Chế độ thủ công: Số tiền đối trừ vượt nợ còn lại của hoá đơn đó -> BỊ TỪ CHỐI
  const manualOverInvoice = {
    ...fifoPayload,
    amount: 5000000,
    autoAllocate: false,
    allocations: [
      { orderId: 101, amount: 5000000, orderRemainingAmount: 3000000, orderCode: 'HD-001' },
    ],
  };
  const resOverInvoice = Utils.validatePaymentPayload(manualOverInvoice, 10000000);
  assert.equal(resOverInvoice.isValid, false);
  assert.match(resOverInvoice.errors.join(' '), /vượt quá số nợ còn lại của hoá đơn/);

  // 5. Chế độ thủ công: Hợp lệ khi tổng khớp và không vượt nợ từng hoá đơn
  const manualValid = {
    ...fifoPayload,
    amount: 5000000,
    autoAllocate: false,
    allocations: [
      { orderId: 101, amount: 3000000, orderRemainingAmount: 3000000, orderCode: 'HD-001' },
      { orderId: 102, amount: 2000000, orderRemainingAmount: 4000000, orderCode: 'HD-002' },
    ],
  };
  const resManualValid = Utils.validatePaymentPayload(manualValid, 10000000);
  assert.equal(resManualValid.isValid, true);
  assert.equal(resManualValid.errors.length, 0);
});

test('S7-02: Tiêu chí 3 - Thông tin trạng thái thanh toán hoá đơn (UNPAID, PARTIALLY_PAID, PAID)', () => {
  const unpaid = Utils.getInvoicePaymentStatusInfo('UNPAID');
  assert.equal(unpaid.label, 'Chưa thanh toán');
  assert.equal(unpaid.badgeClass, 'badge--unpaid');

  const partial = Utils.getInvoicePaymentStatusInfo('PARTIALLY_PAID');
  assert.equal(partial.label, 'Thanh toán một phần');
  assert.equal(partial.badgeClass, 'badge--partial');

  const paid = Utils.getInvoicePaymentStatusInfo('PAID');
  assert.equal(paid.label, 'Đã thanh toán');
  assert.equal(paid.badgeClass, 'badge--paid');
});

test('S7-02: Tiêu chí 5 - Đã ghi nhận thì không xoá, chỉ lập bút toán điều chỉnh (REVERSAL & PARTIAL_REVERSAL)', () => {
  // 1. REVERSAL: Huỷ bỏ toàn bộ khoản thu, bắt buộc lý do điều chỉnh
  const validReversal = {
    adjustmentType: 'REVERSAL',
    reason: 'Khách hàng chuyển nhầm đại lý, hoàn trả toàn bộ nợ',
    adjustmentDate: '2026-10-24',
    recordedBy: 'Kế toán trưởng',
  };
  const resRev = Utils.validateAdjustmentPayload(validReversal, 15000000);
  assert.equal(resRev.isValid, true);

  // 2. REVERSAL thiếu lý do -> BỊ TỪ CHỐI
  const noReasonRev = { ...validReversal, reason: '   ' };
  const resNoReason = Utils.validateAdjustmentPayload(noReasonRev, 15000000);
  assert.equal(resNoReason.isValid, false);
  assert.match(resNoReason.errors.join(' '), /Bắt buộc nhập lý do/);

  // 3. PARTIAL_REVERSAL: Giảm bớt số tiền thu, bắt buộc số tiền giảm <= số tiền phiếu thu gốc
  const validPartial = {
    adjustmentType: 'PARTIAL_REVERSAL',
    adjustmentAmount: 5000000,
    reason: 'Điều chỉnh giảm do thừa tiền',
    adjustmentDate: '2026-10-24',
  };
  const resPartial = Utils.validateAdjustmentPayload(validPartial, 15000000);
  assert.equal(resPartial.isValid, true);

  // 4. PARTIAL_REVERSAL: Số tiền giảm vượt quá số tiền phiếu thu gốc -> BỊ TỪ CHỐI
  const overPartial = {
    ...validPartial,
    adjustmentAmount: 20000000,
  };
  const resOverPartial = Utils.validateAdjustmentPayload(overPartial, 15000000);
  assert.equal(resOverPartial.isValid, false);
  assert.match(resOverPartial.errors.join(' '), /không được vượt quá số tiền phiếu thu gốc/);

  // 5. PARTIAL_REVERSAL: Số tiền giảm <= 0 -> BỊ TỪ CHỐI
  const zeroPartial = {
    ...validPartial,
    adjustmentAmount: 0,
  };
  const resZeroPartial = Utils.validateAdjustmentPayload(zeroPartial, 15000000);
  assert.equal(resZeroPartial.isValid, false);
  assert.match(resZeroPartial.errors.join(' '), /lớn hơn 0/);
});

test('S7-02: Ma trận phân quyền động (Permission Matrix) & Tuyệt đối không role fallback', () => {
  // 1. ROLE_ADMIN luôn có toàn bộ quyền
  const adminUser = { roles: ['ROLE_ADMIN'], permissions: [] };
  assert.equal(Utils.canViewDebtPayments(adminUser), true);
  assert.equal(Utils.canManageDebtPayments(adminUser), true);

  // 2. Quyền xem: FINANCE_VIEW_DEBT
  const viewerUser = { roles: ['ROLE_USER'], permissions: ['FINANCE_VIEW_DEBT'] };
  assert.equal(Utils.canViewDebtPayments(viewerUser), true);
  assert.equal(Utils.canManageDebtPayments(viewerUser), false);

  // 3. Quyền quản lý/ghi thu/điều chỉnh: FINANCE_MANAGE
  const manageUser = { roles: ['ROLE_USER'], permissions: ['FINANCE_MANAGE'] };
  assert.equal(Utils.canViewDebtPayments(manageUser), false);
  assert.equal(Utils.canManageDebtPayments(manageUser), true);

  // 4. ROLE_ACCOUNTANT nhưng bị thu hồi cả FINANCE_VIEW_DEBT lẫn FINANCE_MANAGE trong DB
  // -> PHẢI BỊ CHẶN (Không fallback theo role cứng!)
  const accountantNoPerm = { roles: ['ROLE_ACCOUNTANT'], permissions: ['ORDER_VIEW'] };
  assert.equal(Utils.canViewDebtPayments(accountantNoPerm), false);
  assert.equal(Utils.canManageDebtPayments(accountantNoPerm), false);

  // 5. User null / undefined
  assert.equal(Utils.canViewDebtPayments(null), false);
  assert.equal(Utils.canManageDebtPayments(null), false);

  // 6. Nhiều vai trò và hợp nhất quyền
  const unionUser = { roles: ['ROLE_SALES', 'ROLE_VIEWER'], permissions: ['FINANCE_VIEW_DEBT', 'FINANCE_MANAGE'] };
  assert.equal(Utils.canViewDebtPayments(unionUser), true);
  assert.equal(Utils.canManageDebtPayments(unionUser), true);
});

test('S7-02: Cấu hình Menu và Route cho chức năng Ghi nhận thanh toán', () => {
  // 1. Kiểm tra route trong serve_frontend.py
  const servePy = fs.readFileSync(path.join(__dirname, '../serve_frontend.py'), 'utf-8');
  assert.match(servePy, /"\/finance\/debt-payments":\s*"finance\/debt-payments\.html"/);

  // 2. Kiểm tra menu-config.js
  const menuConfig = fs.readFileSync(path.join(__dirname, '../frontend/js/menu-config.js'), 'utf-8');
  assert.match(menuConfig, /id:\s*"debt-payments"/);
  assert.match(menuConfig, /href:\s*"\/finance\/debt-payments"/);
  assert.match(menuConfig, /label:\s*"Ghi nhận thanh toán"/);

  // Kiểm tra MenuConfig.getMenuForUser lọc theo quyền
  const menuSource = fs.readFileSync(path.join(__dirname, '../frontend/js/menu-config.js'), 'utf8');
  const menuContext = vm.runInNewContext(`${menuSource}\nMenuConfig;`, { URLSearchParams });
  const ids = (user) => Array.from(menuContext.getMenuForUser(user), (item) => item.id);

  // Admin có menu
  assert.ok(ids({ roles: ['ROLE_ADMIN'], permissions: [] }).includes('debt-payments'));

  // User có FINANCE_VIEW_DEBT có menu
  assert.ok(ids({ roles: ['ROLE_USER'], permissions: ['FINANCE_VIEW_DEBT'] }).includes('debt-payments'));

  // User có FINANCE_MANAGE có menu
  assert.ok(ids({ roles: ['ROLE_USER'], permissions: ['FINANCE_MANAGE'] }).includes('debt-payments'));

  // User thiếu cả hai quyền KHÔNG CÓ menu
  assert.ok(!ids({ roles: ['ROLE_ACCOUNTANT'], permissions: ['ORDER_VIEW'] }).includes('debt-payments'));

  // 3. Kiểm tra file HTML, JS, CSS tồn tại
  assert.ok(fs.existsSync(path.join(__dirname, '../frontend/finance/debt-payments.html')));
  assert.ok(fs.existsSync(path.join(__dirname, '../frontend/js/debt-payments.js')));
  assert.ok(fs.existsSync(path.join(__dirname, '../frontend/js/debt-payments-utils.js')));
  assert.ok(fs.existsSync(path.join(__dirname, '../frontend/css/debt-payments.css')));
});

test('S7-02: Không sử dụng lưu trữ trình duyệt (localStorage/sessionStorage) cho dữ liệu kinh doanh', () => {
  const jsContent = fs.readFileSync(path.join(__dirname, '../frontend/js/debt-payments.js'), 'utf-8');
  const utilsContent = fs.readFileSync(path.join(__dirname, '../frontend/js/debt-payments-utils.js'), 'utf-8');

  const combined = jsContent + '\n' + utilsContent;
  const storageCalls = combined.match(/\b(localStorage|sessionStorage)\.(setItem|getItem|removeItem|clear)\b/g);
  assert.equal(storageCalls, null, 'Tuyệt đối không được gọi API lưu trữ trình duyệt cho dữ liệu kinh doanh');
});

