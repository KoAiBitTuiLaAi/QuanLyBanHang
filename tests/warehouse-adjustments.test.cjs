const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const Utils = require('../frontend/js/warehouse-adjustment-utils.js');
const menuSource = fs.readFileSync(path.join(__dirname, '../frontend/js/menu-config.js'), 'utf8');
const menu = vm.runInNewContext(`${menuSource}\nMenuConfig;`, { URLSearchParams });
const ids = (user) => Array.from(menu.getMenuForUser(user), (item) => item.id);

test('S8-03: Tính toán giá trị tổn thất và định dạng tiền tệ / số lượng chính xác', () => {
  assert.equal(Utils.formatMoney(1500000).replace(/\s/g, ' '), '1.500.000 ₫');
  assert.equal(Utils.formatMoney(0).replace(/\s/g, ' '), '0 ₫');
  assert.equal(Utils.formatMoney(null), '0 đ');

  assert.equal(Utils.formatQuantity(12.5), '12,5');
  assert.equal(Utils.formatQuantity('100.2500'), '100,25');
  assert.equal(Utils.formatQuantity(0), '0');

  assert.equal(Utils.calculateLineLoss(5, 50000), 250000);
  assert.equal(Utils.calculateLineLoss(2.5, 40000), 100000);
  assert.equal(Utils.calculateLineLoss(0, 50000), 0);
  assert.equal(Utils.calculateLineLoss(-2, 50000), 0);

  const items = [
    { quantity: 10, unitPrice: 20000 },
    { quantity: 5, unitPrice: 40000 },
  ];
  assert.equal(Utils.calculateTotalLoss(items), 400000);
});

test('S8-03: Kiểm tra hợp lệ form lập phiếu điều chỉnh (bắt buộc lý do và ghi rõ giá trị tổn thất)', () => {
  // Hợp lệ: Chọn lý do DAMAGED, có chi tiết, có hàng và giá trị tổn thất
  const validDraft = {
    warehouseId: 1,
    reason: 'DAMAGED',
    reasonDetail: 'Hàng bị rơi vỡ khi bốc dỡ',
    items: [
      { productId: 101, quantity: 2, availableQuantity: 10, unitPrice: 150000 },
    ],
  };
  const validRes = Utils.validateAdjustmentDraft(validDraft);
  assert.equal(validRes.isValid, true);
  assert.equal(validRes.errors.length, 0);

  // Không chọn kho
  const noWarehouse = { ...validDraft, warehouseId: null };
  assert.equal(Utils.validateAdjustmentDraft(noWarehouse).isValid, false);

  // Thiếu lý do
  const noReason = { ...validDraft, reason: '' };
  const resNoReason = Utils.validateAdjustmentDraft(noReason);
  assert.equal(resNoReason.isValid, false);
  assert.match(resNoReason.errors.join(' '), /Bắt buộc chọn lý do/);

  // Thiếu chi tiết lý do
  const noDetail = { ...validDraft, reasonDetail: '   ' };
  const resNoDetail = Utils.validateAdjustmentDraft(noDetail);
  assert.equal(resNoDetail.isValid, false);
  assert.match(resNoDetail.errors.join(' '), /Bắt buộc ghi rõ/);

  // Vượt quá 500 ký tự
  const longDetail = { ...validDraft, reasonDetail: 'a'.repeat(501) };
  assert.equal(Utils.validateAdjustmentDraft(longDetail).isValid, false);

  // Không có mặt hàng
  const noItems = { ...validDraft, items: [] };
  const resNoItems = Utils.validateAdjustmentDraft(noItems);
  assert.equal(resNoItems.isValid, false);
  assert.match(resNoItems.errors.join(' '), /ít nhất một mặt hàng/);

  // Số lượng vượt quá tồn khả dụng
  const overAvail = {
    ...validDraft,
    items: [{ productId: 101, quantity: 15, availableQuantity: 10, unitPrice: 150000 }],
  };
  const resOverAvail = Utils.validateAdjustmentDraft(overAvail);
  assert.equal(resOverAvail.isValid, false);
  assert.match(resOverAvail.errors.join(' '), /vượt quá tồn khả dụng/);

  // Giá trị tổn thất = 0
  const zeroLoss = {
    ...validDraft,
    items: [{ productId: 101, quantity: 5, availableQuantity: 10, unitPrice: 0 }],
  };
  const resZeroLoss = Utils.validateAdjustmentDraft(zeroLoss);
  assert.equal(resZeroLoss.isValid, false);
  assert.match(resZeroLoss.errors.join(' '), /giá trị tổn thất/);
});

test('S8-03: Ràng buộc phê duyệt: Chỉ Quản lý kho được duyệt; người lập và người duyệt không được là một', () => {
  const voucher = {
    id: 1,
    voucherCode: 'PDC-001',
    status: 'PENDING_APPROVAL',
    createdBy: 'kho_staff_01',
    items: [{ productId: 101, quantity: 5, unitPrice: 50000 }],
  };

  const mockAuthManager = {
    hasRole: (r) => r === 'ROLE_ADMIN',
    hasPermission: (p) => p === 'WAREHOUSE_APPROVE',
  };

  const mockAuthStaffNoApprove = {
    hasRole: () => false,
    hasPermission: (p) => p === 'WAREHOUSE_ADJUST',
  };

  // Trường hợp 1: Người duyệt là người khác (kho_manager) và có quyền WAREHOUSE_APPROVE -> ĐƯỢC DUYỆT
  const managerUser = { username: 'kho_manager_01' };
  const allowRes = Utils.canApproveAdjustment(voucher, managerUser, mockAuthManager);
  assert.equal(allowRes.allowed, true);

  // Trường hợp 2: Người duyệt chính là người lập phiếu (kho_staff_01) -> BỊ CHẶN!
  const creatorUser = { username: 'kho_staff_01' };
  const blockedRes = Utils.canApproveAdjustment(voucher, creatorUser, mockAuthManager);
  assert.equal(blockedRes.allowed, false);
  assert.equal(blockedRes.isCreator, true);
  assert.match(blockedRes.reason, /người lập và người duyệt không được là một/i);

  // Trường hợp 3: Người duyệt là người khác nhưng KHÔNG có quyền WAREHOUSE_APPROVE -> BỊ CHẶN!
  const otherUserNoPerm = { username: 'kho_staff_02' };
  const noPermRes = Utils.canApproveAdjustment(voucher, otherUserNoPerm, mockAuthStaffNoApprove);
  assert.equal(noPermRes.allowed, false);
  assert.match(noPermRes.reason, /quyền WAREHOUSE_APPROVE/);

  // Trường hợp 4: Phiếu đã duyệt trước đó -> Không thể duyệt lại
  const approvedVoucher = { ...voucher, status: 'APPROVED' };
  assert.equal(Utils.canApproveAdjustment(approvedVoucher, managerUser, mockAuthManager).allowed, false);
});

test('S8-03: Mọi phiếu điều chỉnh đều có link liên kết xuất hiện trên thẻ kho', () => {
  const url = Utils.getStockCardUrl(105, 2);
  assert.equal(url, '/warehouse/stock-card?productId=105&warehouseId=2');

  const parsed1 = Utils.parseAdjustmentReason('[DAMAGED] Hàng vỡ do rơi kệ');
  assert.equal(parsed1.reasonCode, 'DAMAGED');
  assert.equal(parsed1.detail, 'Hàng vỡ do rơi kệ');

  const parsed2 = Utils.parseAdjustmentReason('[EXPIRED] Hàng hết hạn tháng 10/2026');
  assert.equal(parsed2.reasonCode, 'EXPIRED');

  const parsed3 = Utils.parseAdjustmentReason('[LOST] Hao hụt khi kiểm kê định kỳ');
  assert.equal(parsed3.reasonCode, 'LOST');
});

test('S8-03: Ma trận phân quyền menu-config cho Phiếu điều chỉnh (warehouse-adjustments)', () => {
  // Người dùng có bất kỳ quyền nào trong [WAREHOUSE_VIEW, WAREHOUSE_ADJUST, WAREHOUSE_APPROVE] đều xem được menu
  for (const perm of ['WAREHOUSE_VIEW', 'WAREHOUSE_ADJUST', 'WAREHOUSE_APPROVE']) {
    assert.ok(
      ids({ roles: ['ROLE_WAREHOUSE_STAFF'], permissions: [perm] }).includes('warehouse-adjustments'),
      `Role với quyền ${perm} phải thấy menu warehouse-adjustments`
    );
  }

  // Quản trị viên luôn thấy menu
  assert.ok(ids({ roles: ['ROLE_ADMIN'], permissions: [] }).includes('warehouse-adjustments'));

  // Người dùng không có quyền kho không thấy menu
  assert.ok(!ids({ roles: ['ROLE_SALES'], permissions: ['ORDER_CREATE', 'CUSTOMER_VIEW'] }).includes('warehouse-adjustments'));

  // Đại lý không thấy menu điều chỉnh kho nội bộ
  assert.ok(!ids({ roles: ['ROLE_DEALER'], permissions: ['WAREHOUSE_VIEW', 'WAREHOUSE_ADJUST'] }).includes('warehouse-adjustments'));

  // Điều hướng đúng route
  const page = menu.getPageForLocation(new URL('http://test/warehouse/adjustments'));
  assert.equal(page.id, 'warehouse-adjustments');
  assert.equal(page.href, '/warehouse/adjustments');
});

test('S8-03: Tuân thủ bảo mật và không lưu dữ liệu kinh doanh vào localStorage/sessionStorage', () => {
  const js = fs.readFileSync(path.join(__dirname, '../frontend/js/warehouse-adjustments.js'), 'utf8');
  assert.match(js, /Auth\.apiFetch\(["'`]\/warehouse\/adjust/);
  assert.match(js, /Auth\.apiFetch\(["'`]\/warehouse\/vouchers/);
  assert.doesNotMatch(js, /localStorage|sessionStorage/);
  assert.match(js, /auth:permissions-changed/);

  const css = fs.readFileSync(path.join(__dirname, '../frontend/css/warehouse-adjustments.css'), 'utf8');
  assert.doesNotMatch(css, /--app-content-|\.adjustment-page\s*\{[^}]*\b(?:max-width|margin|padding)\s*:/);
});
