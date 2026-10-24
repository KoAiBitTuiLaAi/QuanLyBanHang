const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');

async function openOrderForm(debtResponse, hasPermission = () => true) {
  const elements = new Map();
  function element(id) {
    if (!elements.has(id)) elements.set(id, {
      value: '', hidden: false, disabled: false, textContent: '', innerHTML: '',
      listeners: {}, classList: { toggle() {}, add() {}, remove() {} },
      addEventListener(event, callback) { this.listeners[event] = callback; },
      appendChild() {}, querySelectorAll() { return []; }, querySelector() { return null; },
    });
    return elements.get(id);
  }
  const calls = [];
  const success = (data) => ({ ok: true, json: async () => ({ success: true, data }) });
  const context = vm.createContext({
    document: { getElementById: element, createElement: () => element(Symbol()), addEventListener() {} },
    location: { search: '' },
    window: { location: { search: '' } },
    URLSearchParams,
    MenuConfig: {},
    requestAnimationFrame: (fn) => fn(), setTimeout() {}, clearTimeout() {},
    Auth: {
      refreshPermissions: async () => true,
      requireLogin: () => true, hasAnyRole: () => true, hasPermission,
      getUser: () => null,
      apiFetch: async (url) => {
        calls.push(url);
        if (url === '/dealers/active') return success([{ id: 1, code: 'DL01', name: 'Đại lý', customerGroup: 'DAI_LY_CAP_1' }]);
        if (url === '/dealers/1/debt') return debtResponse();
        if (url === '/orders/draft') return success(null);
        return success([]);
      },
    },
  });
  for (const key of ['localStorage', 'sessionStorage']) {
    Object.defineProperty(context, key, { get() { throw new Error('Không lưu nghiệp vụ vào browser storage'); } });
  }
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../frontend/js/create-order.js'), 'utf8'), context);
  await new Promise(setImmediate);
  element('co-dealer').value = '1';
  await element('co-dealer').listeners.change();
  return { element, calls };
}

const debt = (data) => () => ({ ok: true, json: async () => ({ success: true, data }) });

test('tạo đơn không gọi lịch sử mua hàng khi thiếu ORDER_VIEW', async () => {
  const { element, calls } = await openOrderForm(debt({ currentDebt: 0 }), permission => permission !== 'ORDER_VIEW');
  assert.ok(!calls.some(url => url.includes('purchase-history')));
  assert.equal(element('co-history-section').hidden, true);
  assert.equal(element('btn-add-all-history').hidden, true);
});

test('màn hình tạo đơn dùng API công nợ dành cho SALES và đọc DTO của backend S4-02', async () => {
  const { element, calls } = await openOrderForm(debt({ currentDebt: 300, debtLimit: 1000, remainingLimit: 700, hasOverdueDebt: false, isBlocked: false }));
  assert.ok(calls.includes('/dealers/1/debt'));
  assert.equal(element('co-debt-panel').hidden, false);
  assert.match(element('co-debt-current').textContent, /300/);
  assert.match(element('co-debt-remaining').textContent, /700/);
  assert.equal(element('btn-submit-order').disabled, false);
});

test('cờ quá hạn của backend chặn tạo đơn dù không có trường oldestOverdueDays', async () => {
  const { element } = await openOrderForm(debt({ currentDebt: 300, debtLimit: 1000, maxDebtDays: 30, hasOverdueDebt: true, maxOverdueDays: 2, isBlocked: true, blockReason: 'Quá hạn 2 ngày' }));
  assert.equal(element('btn-submit-order').disabled, true);
  assert.equal(element('co-debt-alert').textContent, 'Quá hạn 2 ngày');
});

test('API công nợ lỗi phải hiện lý do và chặn chốt đơn', async () => {
  const { element } = await openOrderForm(() => ({ ok: false, json: async () => ({ success: false, message: 'Không tải được công nợ' }) }));
  assert.equal(element('co-debt-panel').hidden, false);
  assert.equal(element('btn-submit-order').disabled, true);
  assert.equal(element('co-debt-alert').textContent, 'Không tải được công nợ');
});

test('bỏ chọn đại lý quá hạn xoá trạng thái chặn của đại lý trước', async () => {
  const { element } = await openOrderForm(debt({ hasOverdueDebt: true, isBlocked: true }));
  element('co-dealer').value = '';
  await element('co-dealer').listeners.change();
  assert.equal(element('co-debt-panel').hidden, true);
  assert.equal(element('btn-submit-order').disabled, false);
});
