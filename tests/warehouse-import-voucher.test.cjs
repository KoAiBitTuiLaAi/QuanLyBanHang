const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');
const WarehouseStocktake = require('../frontend/js/warehouse-stocktake.js');

function createMockEnvironment(customOptions = {}) {
  const elements = new Map();
  const permissions = new Set(customOptions.permissions || ['WAREHOUSE_VIEW', 'WAREHOUSE_IMPORT', 'WAREHOUSE_APPROVE']);
  const roles = customOptions.roles || ['ROLE_WAREHOUSE_STAFF'];
  const hasPermission = permission => roles.includes('ROLE_ADMIN') || permissions.has(permission);
  function element(id) {
    if (!elements.has(id)) {
      elements.set(id, {
        value: '',
        hidden: false,
        disabled: false,
        textContent: '',
        innerHTML: '',
        placeholder: '',
        min: '',
        setAttribute(name, value) { this[name] = String(value); },
        removeAttribute(name) { delete this[name]; },
        selectedOptions: [{ value: '1', textContent: 'Kho Tổng TP.HCM', dataset: { name: 'Nhà máy Bia Heineken VN' } }],
        dataset: {},
        listeners: {},
        classList: {
          classes: new Set(),
          toggle(cls, force) {
            if (force === undefined) {
              this.classes.has(cls) ? this.classes.delete(cls) : this.classes.add(cls);
            } else if (force) {
              this.classes.add(cls);
            } else {
              this.classes.delete(cls);
            }
          },
          add(cls) { this.classes.add(cls); },
          remove(cls) { this.classes.delete(cls); },
          contains(cls) { return this.classes.has(cls); }
        },
        addEventListener(event, callback) { this.listeners[event] = callback; },
        remove() {},
        appendChild() {},
        closest(sel) {
          if (sel === '.form-field' || sel === 'label') return { hidden: false };
          return null;
        },
        querySelectorAll() { return []; },
        querySelector() { return null; },
      });
    }
    return elements.get(id);
  }

  const tabs = ['sku-units', 'create-receipt', 'ledger-audit', 'inventory-report', 'stocktake'].map(tab => {
    const item = element('tab-' + tab);
    item.dataset.tab = tab;
    return item;
  });
  const panels = tabs.map(tab => element('panel-' + tab.dataset.tab));
  const importRadio = element('radio-import');
  importRadio.value = 'IMPORT'; importRadio.checked = true;

  const calls = [];
  const success = (data) => ({ ok: true, json: async () => ({ success: true, data }) });

  const context = vm.createContext({
    document: {
      body: element('body'),
      getElementById: element,
      createElement: (tag) => element('__created_' + tag + '_' + Math.random().toString(36).slice(2)),
      addEventListener() {},
      querySelectorAll: (sel) => {
        if (sel === '.nav-tab') return tabs;
        if (sel === '.tab-panel') return panels;
        if (sel.startsWith('#receipt-type-radios input')) return [importRadio];
        return [];
      },
      querySelector: sel => {
        if (sel === '.nav-tab--active') return tabs.find(tab => tab.classList.contains('nav-tab--active')) || null;
        if (sel === '.units-metrics-grid') return element('units-metrics-grid');
        if (sel === '.units-main > h1') return element('page-title');
        if (sel.startsWith('#receipt-type-radios input')) return importRadio;
        return null;
      },
    },
    location: { search: '?tab=receipts', href: 'http://localhost/warehouse/units?tab=receipts' },
    window: { location: { search: '?tab=receipts', href: 'http://localhost/warehouse/units?tab=receipts' }, print() {}, addEventListener() {}, confirm: () => true, history: { replaceState() {} } },
    MenuConfig: { getRoleDisplayName: (r) => r },
    AppNavigation: { refresh() {} },
    WarehouseStocktake,
    requestAnimationFrame: (fn) => fn(),
    setTimeout,
    clearTimeout,
    URL,
    URLSearchParams,
    Intl,
    Math,
    Date,
    Number,
    String,
    Boolean,
    Array,
    Object,
    JSON,
    Map,
    Set,
    Promise,
    Error,
    RegExp,
    console,
    isNaN,
    isFinite,
    parseInt,
    parseFloat,
    // showToast is defined inside the IIFE, so we intercept via a Proxy on document.getElementById
    // to capture toast container creation. Instead, we'll patch after script runs.
    confirm: () => true,
    Auth: {
      requireLogin: () => true,
      ready: Promise.resolve(),
      hasAnyRole: (...requested) => requested.some(role => roles.includes(role)),
      hasRole: role => roles.includes(role),
      hasPermission,
      hasAnyPermission: (...requested) => requested.some(hasPermission),
      getUser: () => ({ id: 10, username: 'kho_staff', fullName: 'Thủ Kho', roles, permissions: [...permissions], warehouses: [{ id: 1, name: 'Kho Tổng TP.HCM' }] }),
      setProtectedImage: async () => false,
      apiFetch: async (url, options) => {
        calls.push({ url, options });
        if (url === '/warehouse/import/options') return success({
          warehouses: [{ id: 1, code: 'WH01', name: 'Kho Tổng TP.HCM' }],
          suppliers: [{ id: 1, code: 'NCC01', name: 'Nhà máy Bia Heineken VN' }],
        });
        if (url === '/warehouse/options') {
          return success([{ id: 1, code: 'WH01', name: 'Kho Tổng TP.HCM' }]);
        }
        if (url === '/products/lookup') {
          return success([
            { id: 101, code: 'BEER-HEIN-330', name: 'Bia Heineken Lon 330ml', unit: 'lon', categoryName: 'Bia', price: 20000, costPrice: 15000 }
          ]);
        }
        if (url === '/warehouse/inventory?warehouseId=1') {
          return success([{ productId: 101, baseQuantity: 500 }]);
        }
        if (url === '/warehouse/vouchers?warehouseId=1') {
          return success([]);
        }
        if (url === '/orders') {
          return success([]);
        }
        if (url === '/products/101/unit-conversions') {
          return success([
            { id: 1, unitName: 'thùng', isBaseUnit: false, conversionFactor: 24, barcode: '', description: '1 thùng = 24 lon' },
            { id: 2, unitName: 'lon', isBaseUnit: true, conversionFactor: 1, barcode: '', description: 'Đơn vị cơ sở' }
          ]);
        }
        if (url === '/suppliers?size=100') {
          return success({
            content: [
              { id: 1, code: 'NCC01', name: 'Nhà máy Bia Heineken VN', phone: '02838123456' }
            ]
          });
        }
        if (url === '/warehouse/import/draft' || url === '/warehouse/vouchers/1') {
          if (customOptions.beforeDraft) await customOptions.beforeDraft();
          const payload = JSON.parse(options.body);
          return success({ ...payload, id: 1, version: 0, voucherCode: payload.voucherCode || 'PNK-SERVER-1',
            type: 'IMPORT', status: 'DRAFT', supplierName: 'Nhà máy Bia Heineken VN', warehouseName: 'Kho Tổng TP.HCM',
            items: payload.items.map(item => ({ ...item, productCode: 'BEER-HEIN-330', productName: 'Bia Heineken', conversionFactor: 24, baseUnit: 'lon', baseQuantity: item.quantity * 24 })) });
        }
        if (url === '/warehouse/vouchers/1/confirm') {
          return success({
            id: 1,
            voucherCode: 'PNK-202610-001',
            type: 'IMPORT',
            status: 'RECORDED',
            items: []
          });
        }
        return success([]);
      }
    }
  });

  return { context, element, calls, elements, permissions };
}

/**
 * Helper: Load script and wait for Store.init() to complete
 */
async function loadAndInit(context) {
  const code = fs.readFileSync(path.join(__dirname, '../frontend/js/warehouse-units.js'), 'utf8');
  vm.runInContext(code, context);
  // Wait enough ticks for all async init (Store.init + Promise.all) to complete
  for (let i = 0; i < 30; i++) await new Promise(setImmediate);
}

test('S5-04: loads narrow supplier and warehouse options without supplier-management grants', async () => {
  const { context, calls } = createMockEnvironment();
  await loadAndInit(context);

  // Verify suppliers API was called
  const supplierCall = calls.find(c => c.url === '/warehouse/import/options');
  assert.ok(supplierCall);
  assert.ok(!calls.some(c => c.url.startsWith('/suppliers')));

  const supplierSelect = context.document.getElementById('receipt-supplier-select');
  assert.ok(supplierSelect.innerHTML.includes('Nhà máy Bia Heineken VN'), 'Dropdown nhà cung cấp phải chứa danh sách NCC từ API');
});

test('S5-04: Lưu nháp không gọi API nhập kho ghi sổ và lưu trạng thái DRAFT', async () => {
  const { context, element, calls, elements } = createMockEnvironment();
  await loadAndInit(context);

  // Set form values
  element('receipt-code').value = 'PNK-TEST-DRAFT-001';
  element('receipt-date').value = '2026-10-16';
  element('receipt-warehouse').value = '1';
  element('receipt-partner').value = 'Nhà máy Bia Heineken VN';
  element('receipt-doc-number').value = 'HĐ-88219';
  element('receipt-supplier-select').value = '1';

  const btnDraft = element('btn-save-draft-receipt');
  assert.ok(btnDraft.listeners.click, 'Nút Lưu bản nháp phải có click listener');

  // Clear API calls before clicking to isolate draft behavior
  const callsBeforeDraft = calls.length;
  await btnDraft.listeners.click();

  // Kiểm tra không gọi POST /warehouse/import
  const importCall = calls.slice(callsBeforeDraft).find(c => c.url === '/warehouse/import');
  assert.equal(importCall, undefined, 'Lưu nháp tuyệt đối không được gọi POST /warehouse/import để tránh làm sai lệch tồn kho');
  assert.ok(calls.slice(callsBeforeDraft).some(c => c.url === '/warehouse/import/draft' && JSON.parse(c.options.body).draft === true));
  assert.ok(!calls.slice(callsBeforeDraft).some(c => c.url.endsWith('/confirm')));

  // showToast is defined inside the IIFE and creates DOM elements via
  // document.createElement. Check that a created element has innerHTML
  // containing 'bản nháp' (the toast message).
  let foundToast = false;
  for (const [, el] of elements) {
    if (el.innerHTML && typeof el.innerHTML === 'string' && el.innerHTML.includes('bản nháp')) {
      foundToast = true;
      break;
    }
  }
  assert.ok(foundToast, 'Lưu nháp thành công phải thông báo cho thủ kho');
});

test('S5-04: saves structured supplier/document/date and explicitly confirms the persisted draft', async () => {
  const { context, element, calls } = createMockEnvironment();
  await loadAndInit(context);

  // Set supplier select to have the correct selectedOptions with dataset.name
  const supplierSel = element('receipt-supplier-select');
  supplierSel.value = '1';
  supplierSel.selectedOptions = [{ value: '1', textContent: 'NCC01 - Nhà máy Bia Heineken VN', dataset: { name: 'Nhà máy Bia Heineken VN' } }];

  element('receipt-code').value = 'PNK-202610-001';
  element('receipt-date').value = '2026-10-16';
  element('receipt-warehouse').value = '1';
  element('receipt-partner').value = 'Nhà máy Bia Heineken VN';
  element('receipt-doc-number').value = 'PXK-NCC-9981';
  element('receipt-notes').value = 'Kiểm hàng tại bến dỡ xe số 3';

  const btnSave = element('btn-save-receipt');
  assert.ok(btnSave.listeners.click, 'Nút Xác nhận ghi sổ kho phải có click listener');

  await btnSave.listeners.click();
  // Wait for async recordReceipt to complete
  for (let i = 0; i < 30; i++) await new Promise(setImmediate);

  // Kiểm tra đã gọi POST /warehouse/import
  const importCall = calls.find(c => c.url === '/warehouse/import/draft');
  assert.ok(importCall);
  assert.equal(importCall.options.method, 'POST');

  const payload = JSON.parse(importCall.options.body);
  assert.equal(payload.voucherCode, 'PNK-202610-001');
  assert.equal(payload.documentNumber, 'PXK-NCC-9981');
  assert.equal(payload.supplierId, 1);
  assert.equal(payload.voucherDate, '2026-10-16T00:00:00');
  assert.ok(calls.some(c => c.url === '/warehouse/vouchers/1/confirm' && c.options.method === 'POST'));
});

test('S5-04 merge uses lookup and matrix grants without ORDER_VIEW or a fixed warehouse role', async () => {
  const { context, calls, element } = createMockEnvironment({ roles: ['ROLE_VIEWER'] });
  await loadAndInit(context);
  assert.ok(calls.some(call => call.url === '/products/lookup'));
  assert.ok(!calls.some(call => call.url === '/products' || call.url === '/orders'));
  assert.equal(element('btn-save-receipt').disabled, false);
  assert.equal(element('btn-save-draft-receipt').disabled, false);
});

test('S5-04 merge respects missing or revoked IMPORT permission even if the role remains unchanged', async () => {
  const readOnly = createMockEnvironment({ permissions: ['WAREHOUSE_VIEW'] });
  await loadAndInit(readOnly.context);
  assert.equal(readOnly.element('btn-save-receipt').disabled, true);
  assert.equal(readOnly.element('btn-save-draft-receipt').disabled, true);
  assert.ok(!readOnly.calls.some(call => call.url.startsWith('/suppliers')));

  const session = createMockEnvironment();
  await loadAndInit(session.context);
  session.permissions.delete('WAREHOUSE_IMPORT');
  const previous = session.calls.length;
  await session.element('btn-save-draft-receipt').listeners.click();
  await session.element('btn-save-receipt').listeners.click();
  assert.ok(!session.calls.slice(previous).some(call => call.options?.method === 'POST'));
  assert.ok([...session.elements.values()].some(el => el.innerHTML?.includes('không có quyền')));
});

test('IMPORT-only saves DB drafts without being allowed to confirm stock', async () => {
  const session = createMockEnvironment({ permissions: ['WAREHOUSE_IMPORT'], roles: ['ROLE_VIEWER'] });
  await loadAndInit(session.context);
  assert.equal(session.element('btn-save-receipt').hidden, true);
  assert.equal(session.element('btn-save-draft-receipt').disabled, false);
  assert.ok(session.calls.some(call => call.url === '/warehouse/imports?warehouseId=1'));
  assert.ok(!session.calls.some(call => call.url.startsWith('/suppliers') || call.url.startsWith('/warehouse/inventory')));
});

test('late draft response after IMPORT revocation cannot restore a draft or expose its server code', async () => {
  let release;
  const pending = new Promise(resolve => { release = resolve; });
  const session = createMockEnvironment({ beforeDraft: () => pending });
  await loadAndInit(session.context);
  session.element('receipt-supplier-select').value = '1';
  session.element('receipt-doc-number').value = 'HD-LATE';
  session.element('receipt-date').value = '2026-10-21';
  session.element('receipt-warehouse').value = '1';
  const save = session.element('btn-save-draft-receipt').listeners.click();
  session.permissions.delete('WAREHOUSE_IMPORT'); release(); await save;
  assert.equal(session.element('receipt-code').value, '');
  assert.ok(!session.element('ledger-table-body').innerHTML.includes('PNK-SERVER-1'));
  assert.ok([...session.elements.values()].some(el => el.innerHTML?.includes('Quyền hoặc phạm vi đã thay đổi')));
});
