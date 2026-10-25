const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');

async function createMockEnvironment(apiResponses = {}, options = {}) {
  const currentUser = options.currentUser || { id: 99, username: 'dealer.test', roles: ['ROLE_DEALER'],
    permissions: ['ORDER_VIEW', 'ORDER_CREATE', 'PRODUCT_VIEW', 'FINANCE_VIEW_DEBT'], dealerId: 10 };
  const elements = new Map();
  function getEl(id) {
    if (!elements.has(id)) {
      elements.set(id, {
        id,
        value: '',
        hidden: false,
        disabled: false,
        textContent: '',
        innerHTML: '',
        children: [],
        replaceChildren(...children) { this.children = children; },
        append(child) { this.children.push(child); },
        selectedOptions: [{ value: 'thùng', dataset: { price: '360000', factor: '24' } }],
        listeners: {},
        classList: {
          toggle() {},
          add() {},
          remove() {},
        },
        dataset: {},
        focus() {},
        addEventListener(event, callback) {
          this.listeners[event] = callback;
        },
        querySelectorAll() {
          return [];
        },
        querySelector() {
          return null;
        },
      });
    }
    return elements.get(id);
  }

  const calls = [];
  const success = (data) => ({ ok: true, json: async () => ({ success: true, data }) });

  const windowObj = { location: { href: '/dealer-portal', pathname: '/dealer-portal', search: options.search || '' } };
  const context = vm.createContext({
    window: windowObj,
    document: {
      getElementById: getEl,
      createElement: () => getEl(Symbol('created')),
      addEventListener(event, cb) {
        if (event === 'DOMContentLoaded') cb();
      },
      readyState: 'complete',
      body: { style: {} },
    },
    location: windowObj.location,
    URLSearchParams,
    Intl,
    Math,
    Date,
    Number,
    String,
    parseInt,
    encodeURIComponent,
    console: { log() {}, warn() {}, error() {} },
    Auth: {
      refreshPermissions: async () => true,
      requireLogin: () => true,
      hasAnyRole: (...roles) => roles.some(role => currentUser.roles.includes(role)),
      hasPermission: permission => currentUser.roles.includes('ROLE_ADMIN') || currentUser.permissions.includes(permission),
      getUser: () => currentUser,
      apiFetch: async (url, opts = {}) => {
        calls.push({ url, method: opts.method || 'GET', body: opts.body ? JSON.parse(opts.body) : null });
        url = url.split('?')[0];
        if (url === '/portal/dealers') return success(options.dealers || [{ id: 10, code: 'DL-10', name: 'Đại lý 10' }]);
        if (typeof apiResponses[url] === 'function') return apiResponses[url](opts);
        if (apiResponses[url]) return success(apiResponses[url]);
        if (url === '/portal/orders/preview') {
          const items = JSON.parse(opts.body).items.map(item => {
            const price = (item.productId === 101 ? 15000 : 13000) * (item.unit === 'thùng' ? 24 : 1);
            return { ...item, price, subtotalAmount: price * item.quantity, discountAmount: 0, totalAmount: price * item.quantity };
          });
          const total = items.reduce((sum, item) => sum + item.totalAmount, 0);
          return success({ pricing: { items, subtotalAmount: total, discountAmount: 0, totalAmount: total },
            debt: { currentDebt: 30000000, debtLimit: 100000000, remainingLimit: 70000000, projectedDebt: 30000000 + total, isBlocked: false, hasOverdueDebt: false } });
        }
        if (url === '/portal/profile') {
          return success({ id: 10, code: 'DL-HOANGPHAT', name: 'Đại lý Hoàng Phát', customerGroup: 'DAI_LY_CAP_1', active: true });
        }
        if (url === '/dealers/active') {
          return success([
            { id: 10, code: 'DL-HOANGPHAT', name: 'Đại lý Hoàng Phát', customerGroup: 'DAI_LY_CAP_1' },
            { id: 20, code: 'DL-MINHTHANH', name: 'Đại lý Minh Thành', customerGroup: 'DAI_LY_CAP_2' },
          ]);
        }
        if (url.includes('/delivery-points')) {
          return success([{ id: 1, name: 'Kho chính', address: '123 Đường 1', isDefault: true }]);
        }
        if (url.includes('/debt')) {
          return success({
            debtLimit: 100000000,
            currentDebt: 30000000,
            remainingLimit: 70000000,
            hasOverdueDebt: false,
            isBlocked: false,
          });
        }
        if (url.includes('/price-lists/effective')) {
          return success({
            id: 1,
            code: 'BG-CAP1',
            customerGroup: 'DAI_LY_CAP_1',
            items: [
              { productId: 101, price: 15000, product: { id: 101, sku: 'KEN330', name: 'Bia Heineken 330ml' } },
              { productId: 102, price: 13000, product: { id: 102, sku: 'TIGER330', name: 'Bia Tiger 330ml' } },
            ],
          });
        }
        if (url === '/portal/catalog') {
          return success([
            { id: 101, sku: 'KEN330', name: 'Bia Heineken 330ml', baseUnit: 'lon', basePrice: 15000, categoryName: 'Bia', units: [{unitName:'lon',factor:1,price:15000},{unitName:'thùng',factor:24,price:360000}] },
            { id: 102, sku: 'TIGER330', name: 'Bia Tiger 330ml', baseUnit: 'lon', basePrice: 13000, categoryName: 'Bia', units: [{unitName:'lon',factor:1,price:13000}] },
          ]);
        }
        if (url.includes('/products?active=true')) {
          return success([
            { id: 101, sku: 'KEN330', name: 'Bia Heineken 330ml', baseUnit: 'lon', category: { name: 'Bia' } },
            { id: 102, sku: 'TIGER330', name: 'Bia Tiger 330ml', baseUnit: 'lon', category: { name: 'Bia' } },
            { id: 103, sku: 'COCA330', name: 'Coca Cola 330ml', baseUnit: 'lon', category: { name: 'Nước giải khát' } }, // NOT in price list!
          ]);
        }
        if (url.includes('/unit-conversions')) {
          return success([{ unitName: 'thùng', factor: 24 }]);
        }
        if (url === '/portal/orders' && opts.method === 'POST') {
          return success({
            id: 501,
            orderCode: 'DH-DL-20261014-001',
            status: 'PENDING_APPROVAL',
            totalAmount: 1800000,
          });
        }
        return success([]);
      },
    },
    Toast: { show() {} },
  });

  // Strict check: No localStorage or sessionStorage for business state
  for (const storage of ['localStorage', 'sessionStorage']) {
    Object.defineProperty(context, storage, {
      get() {
        throw new Error(`Không được lưu dữ liệu nghiệp vụ vào ${storage}`);
      },
    });
  }

  const script = fs.readFileSync(path.join(__dirname, '../frontend/js/dealer-portal.js'), 'utf8');
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../frontend/js/dealer-portal-context.js'), 'utf8'), context);
  vm.runInContext(script, context);
  await new Promise(setImmediate);

  return { context, elements, calls, get portal() { return windowObj.DealerPortal; } };
}

test('S4-10: Cổng đại lý chỉ hiển thị sản phẩm và giá thuộc bảng giá nhóm khách hàng của mình', async () => {
  const { elements, calls } = await createMockEnvironment();
  await new Promise((r) => setTimeout(r, 60));

  assert.ok(calls.some((call) => call.url === '/portal/catalog'));
  assert.ok(calls.every((call) => call.url.startsWith('/portal/')));

  // Verify dealer name and group displayed
  assert.equal(elements.get('info-dealer-name').textContent, 'Đại lý Hoàng Phát');
  assert.equal(elements.get('topbar-group-badge').textContent, 'Đại lý cấp 1');

  // Verify catalog count: only products 101 and 102 (Heineken, Tiger) are in price list; 103 (Coca) is omitted
  assert.equal(elements.get('dp-catalog-count').textContent, '2 sản phẩm');
});

test('Admin chưa liên kết đại lý mở cổng và chọn đại lý, không gọi API thiếu ngữ cảnh', async () => {
  const { context, elements, calls } = await createMockEnvironment({}, {
    currentUser: { roles: ['ROLE_ADMIN'], permissions: [] },
  });
  await new Promise(resolve => setTimeout(resolve, 20));
  assert.deepEqual(calls.map(call => call.url), ['/portal/dealers']);
  assert.equal(context.window.location.href, '/dealer-portal');
  assert.equal(elements.get('dp-dealer-switcher-wrap').hidden, false);
  assert.equal(elements.get('btn-submit-order').disabled, true);
  const select = elements.get('dp-dealer-select');
  select.value = '10';
  select.listeners.change();
  assert.equal(context.window.location.href, '/dealer-portal?dealerId=10');
});

test('Admin xem và gửi đơn qua cổng với đúng đại lý đã chọn', async () => {
  const { elements, calls, portal } = await createMockEnvironment({}, {
    currentUser: { roles: ['ROLE_ADMIN'], permissions: [] }, search: '?dealerId=10',
  });
  await new Promise(resolve => setTimeout(resolve, 20));
  for (const path of ['profile', 'delivery-points', 'debt', 'catalog']) {
    assert.ok(calls.some(call => call.url === `/portal/${path}?dealerId=10`), path);
  }
  assert.equal(elements.get('dp-dealer-switcher-wrap').hidden, false);
  portal.addToCart({ id: 101, sku: 'KEN330', name: 'Bia Heineken', basePrice: 15000 }, 'lon', 1, 15000, 1);
  await portal.submitDealerOrder();
  assert.ok(calls.some(call => call.method === 'POST' && call.url === '/portal/orders?dealerId=10'));
});

test('Thu hồi quyền cần cho cổng chặn trang trước khi gọi API', async () => {
  for (const revoked of ['ORDER_VIEW', 'ORDER_CREATE', 'PRODUCT_VIEW']) {
    const { context, calls } = await createMockEnvironment({}, { currentUser: {
      roles: ['ROLE_DEALER'], permissions: ['ORDER_VIEW', 'ORDER_CREATE', 'PRODUCT_VIEW'].filter(p => p !== revoked),
    } });
    await new Promise(setImmediate);
    assert.equal(context.window.location.href, '/error?status=403', revoked);
    assert.equal(calls.length, 0);
  }
});

test('S4-10: Thêm sản phẩm vào giỏ, xem tổng tiền và tính toán công nợ còn lại trước khi gửi đơn', async () => {
  const { elements, portal } = await createMockEnvironment();
  await new Promise((r) => setTimeout(r, 60));

  // Verify debt metric values
  assert.match(elements.get('val-debt-limit').textContent, /100\.000\.000/);
  assert.match(elements.get('val-current-debt').textContent, /30\.000\.000/);
  assert.match(elements.get('val-avail-debt').textContent, /70\.000\.000/);

  // Add 5 thùng Heineken (unitPrice = 360,000đ; total = 1,800,000đ)
  portal.addToCart({ id: 101, sku: 'KEN330', name: 'Bia Heineken 330ml', basePrice: 15000 }, 'thùng', 24, 360000, 5);
  await portal.refreshCartPreview();

  // Verify cart totals
  assert.equal(elements.get('cart-badge-count').textContent, 5);
  assert.match(elements.get('dp-subtotal-val').textContent, /1\.800\.000/);
  assert.match(elements.get('dp-total-val').textContent, /1\.800\.000/);

  // Verify projected remaining credit: 70,000,000 - 1,800,000 = 68,200,000 ₫
  assert.match(elements.get('dp-projected-remaining-debt').textContent, /68\.200\.000/);
  assert.equal(elements.get('dp-debt-warning-msg').hidden, true);
});

test('S4-10: Cảnh báo khi đơn hàng làm vượt hạn mức công nợ còn lại của đại lý', async () => {
  const { elements, portal } = await createMockEnvironment();
  await new Promise((r) => setTimeout(r, 60));

  // Add huge quantity exceeding available credit (200 thùng = 72,000,000đ > 70,000,000đ)
  portal.addToCart({ id: 101, sku: 'KEN330', name: 'Bia Heineken 330ml', basePrice: 15000 }, 'thùng', 24, 360000, 200);
  await portal.refreshCartPreview();

  assert.equal(elements.get('dp-debt-warning-msg').hidden, false);
  assert.match(elements.get('dp-debt-warning-msg').textContent, /vượt hạn mức công nợ còn lại/);
});

test('S4-10: Đơn gửi lên luôn ở trạng thái Chờ duyệt (PENDING_APPROVAL), gửi đúng payload và reset giỏ hàng', async () => {
  const { context, elements, calls, portal } = await createMockEnvironment();
  await new Promise((r) => setTimeout(r, 60));

  const pointEl = context.document.getElementById('dp-delivery-point-select');
  pointEl.value = '1';
  const dateEl = context.document.getElementById('dp-delivery-date-input');
  dateEl.value = '2026-10-15';
  const noteEl = context.document.getElementById('dp-order-note');
  noteEl.value = 'Giao trước 8h sáng';

  // Add 2 thùng Heineken
  portal.addToCart({ id: 101, sku: 'KEN330', name: 'Bia Heineken 330ml', basePrice: 15000 }, 'thùng', 24, 360000, 2);

  // Submit dealer order
  await portal.submitDealerOrder();

  // Check POST /orders payload
  const postOrder = calls.find((c) => c.url === '/portal/orders' && c.method === 'POST');
  assert.ok(postOrder, 'Phải gửi POST /orders');
  assert.equal(postOrder.body.dealerId, undefined);
  assert.equal(postOrder.body.deliveryPointId, 1);
  assert.equal(postOrder.body.expectedDeliveryDate, '2026-10-15');
  assert.match(postOrder.body.note, /Đại lý tự đặt qua cổng/);
  assert.deepEqual(postOrder.body.items, [{ productId: 101, unit: 'thùng', quantity: 2 }]);

  // Check success modal shows order code and Chờ duyệt
  assert.equal(elements.get('dp-success-code').textContent, 'DH-DL-20261014-001');
  assert.equal(elements.get('dp-success-modal').hidden, false);
  assert.equal(elements.get('cart-badge-count').textContent, 0);
});

test('S4-10: tổng tiền và công nợ dùng chiết khấu backend, không lấy tổng giỏ tự tính', async () => {
  const preview = { pricing: { subtotalAmount: 100000, discountAmount: 20000, totalAmount: 80000,
    items: [{ productId: 101, price: 100000, totalAmount: 80000 }] },
    debt: { currentDebt: 30000000, remainingLimit: 70000000, debtLimit: 100000000, isBlocked: false } };
  const { portal, elements } = await createMockEnvironment({ '/portal/orders/preview': preview });
  await new Promise(setImmediate);
  portal.addToCart({ id: 101, sku: 'KEN330', name: 'Bia', basePrice: 15000 }, 'lon', 1, 15000, 1);
  await portal.refreshCartPreview();
  assert.match(elements.get('dp-discount-val').textContent, /20\.000/);
  assert.match(elements.get('dp-total-val').textContent, /80\.000/);
  assert.match(elements.get('dp-projected-remaining-debt').textContent, /69\.920\.000/);
  assert.equal(elements.get('btn-submit-order').disabled, false);
});

test('S4-10: preview lỗi chặn gửi đơn và cho phép tính lại, không dùng tổng tiền cũ', async () => {
  const { portal, elements, calls } = await createMockEnvironment({ '/portal/orders/preview': () =>
    ({ ok: false, json: async () => ({ success: false, message: 'Bảng giá đã hết hiệu lực' }) }) });
  await new Promise(setImmediate);
  portal.addToCart({ id: 101, sku: 'KEN330', name: 'Bia', basePrice: 15000 }, 'lon', 1, 15000, 1);
  await portal.refreshCartPreview();
  assert.equal(elements.get('btn-submit-order').disabled, true);
  assert.equal(elements.get('btn-refresh-cart-preview').hidden, false);
  await portal.submitDealerOrder();
  assert.ok(!calls.some(call => call.url === '/portal/orders' && call.method === 'POST'));
});

test('S7-07: không có quyền công nợ thì không gọi API tổng nợ, vẫn kiểm tra giỏ hàng qua preview', async () => {
  const user = {roles:['ROLE_DEALER'], permissions:['ORDER_VIEW','ORDER_CREATE','PRODUCT_VIEW'], dealerId:10};
  const {portal, calls, elements} = await createMockEnvironment({}, {currentUser:user});
  await new Promise(setImmediate);
  assert.ok(!calls.some(call => call.url === '/portal/debt'));
  assert.equal(elements.get('val-current-debt').textContent, 'Không có quyền xem');
  assert.equal(elements.get('val-debt-limit').textContent, 'Không có quyền xem');
  portal.addToCart({id:101, sku:'KEN330', name:'Bia', basePrice:15000}, 'lon', 1, 15000, 1);
  await portal.refreshCartPreview();
  assert.equal(elements.get('btn-submit-order').disabled, false);
});

test('S4-10: bỏ quyền sau khi mở trang không gửi preview hay tạo đơn mới', async () => {
  const user = { roles: ['ROLE_DEALER'], permissions: ['ORDER_VIEW', 'PRODUCT_VIEW', 'ORDER_CREATE'], dealerId: 10 };
  const { portal, calls } = await createMockEnvironment({}, { currentUser: user });
  await new Promise(setImmediate);
  portal.addToCart({ id: 101, sku: 'KEN330', name: 'Bia', basePrice: 15000 }, 'lon', 1, 15000, 1);
  await portal.refreshCartPreview();
  const before = calls.length;
  user.permissions = user.permissions.filter(permission => permission !== 'ORDER_CREATE');
  assert.equal(await portal.refreshCartPreview(true), false);
  await portal.submitDealerOrder();
  assert.equal(calls.length, before);
});

test('S4-10: phản hồi preview trễ không ghi đè giỏ mới', async () => {
  const pending = [];
  const { portal, elements } = await createMockEnvironment({ '/portal/orders/preview': () =>
    new Promise(resolve => pending.push(resolve)) });
  await new Promise(setImmediate);
  portal.addToCart({ id: 101, sku: 'KEN330', name: 'Bia', basePrice: 15000 }, 'lon', 1, 15000, 1);
  const oldRequest = portal.refreshCartPreview();
  portal.state.cart[0].quantity = 2;
  portal.updateCartUI();
  const newRequest = portal.refreshCartPreview();
  const response = amount => ({ ok: true, json: async () => ({ success: true, data: {
    pricing: { subtotalAmount: amount, discountAmount: 0, totalAmount: amount, items: [{ price: 15000, totalAmount: amount }] },
    debt: { currentDebt: 0, remainingLimit: 100000000, debtLimit: 100000000, isBlocked: false } } }) });
  pending[1](response(30000));
  await newRequest;
  pending[0](response(15000));
  await oldRequest;
  assert.equal(portal.state.preview.pricing.totalAmount, 30000);
  assert.match(elements.get('dp-total-val').textContent, /30\.000/);
});

test('S4-10: giá thay đổi sau khi xác nhận phải xem lại tổng mới, chưa tạo đơn', async () => {
  let amount = 15000;
  const { portal, context, calls } = await createMockEnvironment({ '/portal/orders/preview': () =>
    ({ ok: true, json: async () => ({ success: true, data: {
      pricing: { subtotalAmount: amount, discountAmount: 0, totalAmount: amount, items: [{ price: amount, totalAmount: amount }] },
      debt: { currentDebt: 0, remainingLimit: 100000000, debtLimit: 100000000, isBlocked: false } } }) }) });
  await new Promise(setImmediate);
  context.document.getElementById('dp-delivery-date-input').value = '2026-10-25';
  portal.addToCart({ id: 101, sku: 'KEN330', name: 'Bia', basePrice: 15000 }, 'lon', 1, 15000, 1);
  await portal.openConfirmModal();
  amount = 18000;
  await portal.submitDealerOrder();
  assert.ok(!calls.some(call => call.url === '/portal/orders' && call.method === 'POST'));
  assert.equal(portal.state.confirmedPreview.total, 18000);
  await portal.submitDealerOrder();
  assert.equal(calls.filter(call => call.url === '/portal/orders' && call.method === 'POST').length, 1);
});
