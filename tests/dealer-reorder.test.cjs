const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const { test } = require("node:test");

/**
 * Helper khởi tạo môi trường test cho dealer-orders.js
 */
async function createDealerOrdersEnvironment(options = {}) {
  const {
    currentUser = {
      id: 10,
      username: "dealer01",
      fullName: "Đại lý Toàn Cầu",
      roles: ["ROLE_DEALER"],
      permissions: ["ORDER_VIEW", "ORDER_CREATE", "PRODUCT_VIEW"],
      customerGroup: "DAI_LY_CAP_1",
    },
    orders = [],
    orderDetail = null,
    catalogProducts = [],
    effectivePriceList = null,
    dealers = [{ id: 10, code: 'DL-10', name: 'Đại lý 10' }],
    search = '',
  } = options;

  const elements = new Map();
  function getOrCreateElement(id) {
    if (!elements.has(id)) {
      elements.set(id, {
        id,
        value: "",
        hidden: false,
        disabled: false,
        checked: false,
        indeterminate: false,
        textContent: "",
        innerHTML: "",
        children: [],
        replaceChildren(...children) { this.children = children; },
        append(child) { this.children.push(child); },
        dataset: {},
        listeners: {},
        classList: {
          classes: new Set(),
          add(c) { this.classes.add(c); },
          remove(c) { this.classes.delete(c); },
          toggle(c, force) {
            if (force === undefined) {
              if (this.classes.has(c)) this.classes.delete(c);
              else this.classes.add(c);
            } else if (force) {
              this.classes.add(c);
            } else {
              this.classes.delete(c);
            }
          },
          contains(c) { return this.classes.has(c); },
        },
        addEventListener(event, callback) {
          if (!this.listeners[event]) this.listeners[event] = [];
          this.listeners[event].push(callback);
        },
        dispatchEvent(event) {
          const type = event.type || event;
          const cbs = this.listeners[type] || [];
          for (const cb of cbs) cb(event);
        },
        querySelectorAll(selector) {
          return [];
        },
        querySelector(selector) {
          return null;
        },
        closest(selector) {
          return null;
        },
      });
    }
    return elements.get(id);
  }

  const apiCalls = [];
  const submittedPayloads = [];
  const windowListeners = {};

  const context = vm.createContext({
    document: {
      getElementById: (id) => getOrCreateElement(id),
      createElement: (tag) => getOrCreateElement(`dyn-${Math.random()}`),
      querySelectorAll: (sel) => [],
      querySelector: (sel) => null,
      addEventListener: () => {},
    },
    window: {
      location: { href: "/dealer-orders", pathname: "/dealer-orders", search },
      addEventListener: (type, callback) => { (windowListeners[type] ||= []).push(callback); },
      dispatchEvent: (event) => { for (const callback of windowListeners[event.type] || []) callback(event); },
    },
    location: { href: "/dealer-orders", pathname: "/dealer-orders", search: "" },
    sessionStorage: {
      getItem: () => null,
      setItem: () => {},
      removeItem: () => {},
    },
    Toast: {
      show: (msg, type) => {
        context.lastToast = { msg, type };
      },
    },
    MenuConfig: {
      getRoleDisplayName: (r) => (r === "ROLE_DEALER" ? "Đại lý" : r),
    },
    Intl,
    Date,
    Math,
    String,
    Number,
    Array,
    Set,
    Map,
    JSON,
    RegExp,
    URLSearchParams,
    AbortController,
    Boolean,
    CSS: { escape: (s) => s },
    setTimeout,
    clearTimeout,
    setImmediate,
    Auth: {
      requireLogin: () => true,
      getUser: () => currentUser,
      hasRole: (r) => (currentUser?.roles || []).includes(r),
      hasAnyRole: (...roles) => roles.some((r) => (currentUser?.roles || []).includes(r)),
      hasPermission: (p) =>
        (currentUser?.roles || []).includes("ROLE_ADMIN") ||
        (currentUser?.permissions || []).includes(p),
      logout: () => {},
      apiFetch: async (url, fetchOptions = {}) => {
        apiCalls.push({ url, options: fetchOptions });
        url = url.split('?')[0];
        if (url === '/portal/dealers') return { ok: true, json: async () => ({ success: true, data: dealers }) };
        if (fetchOptions.method === "POST") {
          try {
            submittedPayloads.push(JSON.parse(fetchOptions.body));
          } catch {}
          return {
            ok: true,
            status: 200,
            json: async () => ({
              success: true,
              message: "Đặt lại đơn hàng thành công",
              data: { order: { id: 999, orderCode: "DH-REORDER-999" }, excludedItems: [], warnings: [] },
            }),
          };
        }
        if (url === "/portal/orders" || url === "/orders") {
          return {
            ok: true,
            status: 200,
            json: async () => ({ success: true, data: orders }),
          };
        }
        if (url.endsWith('/reorder-preview')) {
          const availableItems = [], discontinuedItems = [];
          const prices = new Map((effectivePriceList?.items || []).map(item => [item.productId, item.sellingPrice]));
          for (const item of orderDetail?.items || []) {
            const product = catalogProducts.find(product => product.id === item.productId || product.code === item.productCode);
            if (!product || product.active === false || product.status === 'INACTIVE') discontinuedItems.push(item);
            else availableItems.push({ ...item, sourceItemId: item.id, currentPrice: prices.get(product.id) ?? product.price ?? 0 });
          }
          return { ok: true, status: 200, json: async () => ({ success: true, data: {
            sourceOrderId: orderDetail?.id, sourceOrderCode: orderDetail?.orderCode, deliveryPointId: orderDetail?.deliveryPointId,
            availableItems, discontinuedItems, defaultExpectedDeliveryDate: '2026-10-25', canReorder: availableItems.length > 0,
          } }) };
        }
        if (url.startsWith("/portal/orders/") || url.startsWith("/orders/")) {
          return {
            ok: true,
            status: 200,
            json: async () => ({ success: true, data: orderDetail }),
          };
        }
        if (url === '/portal/catalog') {
          const priceMap = new Map((effectivePriceList?.items || []).map(item => [item.productId, item.sellingPrice]));
          const products = catalogProducts.filter(product => product.active !== false).map(product => ({
            ...product, sku: product.code, baseUnit: product.unit || '—',
            units: [{unitName: product.unit || '—', factor: 1, price: priceMap.get(product.id) ?? product.price}],
          }));
          return {ok: true, status: 200, json: async () => ({success: true, data: products})};
        }
        if (url.startsWith("/products")) {
          return {
            ok: true,
            status: 200,
            json: async () => ({ success: true, data: catalogProducts }),
          };
        }
        if (url.startsWith("/price-lists/effective")) {
          return {
            ok: true,
            status: 200,
            json: async () => ({
              success: true,
              data: effectivePriceList || { items: [] },
            }),
          };
        }
        return {
          ok: true,
          status: 200,
          json: async () => ({ success: true, data: [] }),
        };
      },
    },
  });

  const scriptPath = path.join(__dirname, "../frontend/js/dealer-orders.js");
  const code = fs.readFileSync(scriptPath, "utf8");
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../frontend/js/dealer-portal-context.js'), 'utf8'), context);
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../frontend/js/dealer-delivery-tracking.js'), 'utf8'), context);
  vm.runInContext(code, context);

  // Chờ loadOrders hoàn tất
  await new Promise((resolve) => setTimeout(resolve, 20));

  return {
    context,
    element: getOrCreateElement,
    apiCalls,
    submittedPayloads,
  };
}

test('Admin mở Đơn hàng của tôi khi chưa liên kết đại lý và chọn hồ sơ từ DB', async () => {
  const env = await createDealerOrdersEnvironment({ currentUser: { roles: ['ROLE_ADMIN'], permissions: [] } });
  assert.deepEqual(env.apiCalls.map(call => call.url), ['/portal/dealers']);
  assert.equal(env.context.window.location.href, '/dealer-orders');
  assert.equal(env.element('dealer-orders-switcher').hidden, false);
  assert.match(env.element('dealer-orders-body').innerHTML, /Chọn đại lý/);
  const select = env.element('dealer-orders-select');
  select.value = '10';
  select.dispatchEvent('change');
  assert.equal(env.context.window.location.href, '/dealer-orders?dealerId=10');
});

test('Admin theo dõi và đặt lại đơn với ngữ cảnh đại lý xuyên suốt', async () => {
  const order = { id: 55, orderCode: 'DH-55', dealerId: 10, status: 'COMPLETED',
    items: [{ id: 1, productId: 101, productCode: 'SP-101', quantity: 2, unit: 'lon' }] };
  const env = await createDealerOrdersEnvironment({ currentUser: { roles: ['ROLE_ADMIN'], permissions: [] },
    search: '?dealerId=10', orders: [order], orderDetail: order,
    catalogProducts: [{ id: 101, code: 'SP-101', name: 'Sản phẩm', active: true, unit: 'lon' }],
    effectivePriceList: { items: [{ productId: 101, sellingPrice: 15000 }] },
  });
  assert.ok(env.apiCalls.some(call => call.url === '/portal/orders?dealerId=10'));
  env.element('dealer-orders-body').dispatchEvent({ type: 'click', target: {
    closest: selector => selector === '[data-reorder-id]' ? { dataset: { reorderId: '55' } } : null,
  } });
  await new Promise(resolve => setTimeout(resolve, 20));
  assert.ok(env.apiCalls.some(call => call.url === '/portal/orders/55/reorder-preview?dealerId=10'));
  assert.ok(!env.apiCalls.some(call => call.url.startsWith('/portal/catalog')));
  env.element('reorder-delivery-date').value = '2026-10-25';
  for (const listener of env.element('btn-confirm-reorder').listeners.click) await listener();
  assert.ok(env.apiCalls.some(call => call.options.method === 'POST' && call.url === '/portal/orders/55/reorder?dealerId=10'));
});

test('Đặt lại dùng API chuyên biệt, không đòi PRODUCT_VIEW ngoài ORDER_VIEW và ORDER_CREATE', async () => {
  const env = await createDealerOrdersEnvironment({ currentUser: { roles: ['ROLE_DEALER'],
    permissions: ['ORDER_VIEW', 'ORDER_CREATE'] }, orders: [{ id: 1, status: 'COMPLETED' }] });
  assert.ok(env.element('dealer-orders-body').innerHTML.includes('dealer-order-reorder-btn'));
});

test('Thu hồi ORDER_CREATE đóng form đang mở, chặn submit và cấp lại khôi phục nút', async () => {
  const currentUser = { roles: ['ROLE_DEALER'], permissions: ['ORDER_VIEW', 'ORDER_CREATE'] };
  const order = { id: 55, orderCode: 'DH55', status: 'EXPORTED', items: [{ id: 1, productId: 101, unit: 'lon', quantity: 2 }] };
  const env = await createDealerOrdersEnvironment({ currentUser, orders: [order], orderDetail: order,
    catalogProducts: [{ id: 101, unit: 'lon', price: 10 }] });
  env.element('dealer-orders-body').dispatchEvent({ type: 'click', target: {
    closest: selector => selector === '[data-reorder-id]' ? { dataset: { reorderId: '55' } } : null,
  } });
  await new Promise(resolve => setTimeout(resolve, 20));
  assert.equal(env.element('dealer-reorder-modal').hidden, false);
  currentUser.permissions = ['ORDER_VIEW'];
  env.context.window.dispatchEvent({ type: 'auth:permissions-changed' });
  await new Promise(resolve => setTimeout(resolve, 20));
  assert.equal(env.element('dealer-reorder-modal').hidden, true);
  assert.equal(env.element('btn-confirm-reorder').disabled, true);
  assert.ok(!env.element('dealer-orders-body').innerHTML.includes('dealer-order-reorder-btn'));
  for (const listener of env.element('btn-confirm-reorder').listeners.click) await listener();
  assert.equal(env.submittedPayloads.length, 0);
  assert.match(env.element('dealer-orders-body').innerHTML, /Đã xuất kho/);
  currentUser.permissions.push('ORDER_CREATE');
  env.context.window.dispatchEvent({ type: 'auth:permissions-changed' });
  await new Promise(resolve => setTimeout(resolve, 20));
  assert.ok(env.element('dealer-orders-body').innerHTML.includes('dealer-order-reorder-btn'));
});

test("S5-02: Quyền ORDER_CREATE được liên kết với nút Đặt lại đơn hàng", async () => {
  // 1. Đại lý có quyền ORDER_CREATE
  const envWithPerm = await createDealerOrdersEnvironment({
    currentUser: {
      id: 1,
      username: "dealer1",
      roles: ["ROLE_DEALER"],
      permissions: ["ORDER_VIEW", "ORDER_CREATE", "PRODUCT_VIEW"],
    },
    orders: [{ id: 101, orderCode: "DH-101", totalAmount: 500000, status: "COMPLETED" }],
  });

  const bodyEl = envWithPerm.element("dealer-orders-body");
  assert.ok(bodyEl.innerHTML.includes("dealer-order-reorder-btn"));
  assert.ok(bodyEl.innerHTML.includes("Đặt lại"));

  // 2. Người dùng bị gỡ quyền ORDER_CREATE
  const envWithoutPerm = await createDealerOrdersEnvironment({
    currentUser: {
      id: 2,
      username: "viewer_dealer",
      roles: ["ROLE_DEALER"],
      permissions: ["ORDER_VIEW"], // Bị thu hồi ORDER_CREATE
    },
    orders: [{ id: 101, orderCode: "DH-101", totalAmount: 500000, status: "COMPLETED" }],
  });

  const bodyWithoutPerm = envWithoutPerm.element("dealer-orders-body");
  assert.ok(!bodyWithoutPerm.innerHTML.includes("dealer-order-reorder-btn"));
});

test("S5-02: Mặt hàng đã ngừng kinh doanh được loại khỏi đơn mới kèm thông báo rõ ràng", async () => {
  const oldOrder = {
    id: 200,
    orderCode: "DH-200",
    customerGroup: "DAI_LY_CAP_1",
    items: [
      {
        id: 1,
        productId: 10,
        productCode: "SP-ACTIVE",
        productName: "Nước tăng lực RedBull",
        quantity: 5,
        price: 200000, // Giá cũ
      },
      {
        id: 2,
        productId: 20,
        productCode: "SP-DISCONTINUED",
        productName: "Trà xanh C2 hương ổi cũ",
        quantity: 3,
        price: 80000,
      },
    ],
  };

  const catalogProducts = [
    { id: 10, code: "SP-ACTIVE", name: "Nước tăng lực RedBull", active: true, price: 220000 },
    { id: 20, code: "SP-DISCONTINUED", name: "Trà xanh C2 hương ổi cũ", active: false, status: "INACTIVE", price: 80000 },
  ];

  const effectivePriceList = {
    items: [
      { productId: 10, sellingPrice: 230000 }, // Giá hiện hành mới
    ],
  };

  const env = await createDealerOrdersEnvironment({
    orders: [oldOrder],
    orderDetail: oldOrder,
    catalogProducts,
    effectivePriceList,
  });

  // Gọi sự kiện click Đặt lại đơn
  const bodyEl = env.element("dealer-orders-body");
  const clickEvent = {
    target: {
      closest: (sel) => {
        if (sel === "[data-reorder-id]") return { dataset: { reorderId: "200" } };
        return null;
      },
    },
  };

  for (const listener of bodyEl.listeners["click"] || []) {
    listener(clickEvent);
  }
  await new Promise((resolve) => setTimeout(resolve, 30));

  // 1. Kiểm tra cảnh báo mặt hàng đã ngừng kinh doanh hiển thị
  const discAlert = env.element("dealer-reorder-discontinued-alert");
  assert.equal(discAlert.hidden, false, "Thông báo hàng ngừng kinh doanh phải hiển thị");

  const discList = env.element("dealer-reorder-discontinued-list");
  assert.ok(discList.innerHTML.includes("Trà xanh C2 hương ổi cũ"));
  assert.ok(discList.innerHTML.includes("SP-DISCONTINUED"));

  // 2. Mặt hàng ngừng kinh doanh không được đưa vào bảng chọn đơn mới
  const tbody = env.element("dealer-reorder-tbody");
  assert.ok(tbody.innerHTML.includes("Nước tăng lực RedBull"));
  assert.ok(!tbody.innerHTML.includes("Trà xanh C2 hương ổi cũ"));

  // 3. Giá được áp lại theo bảng giá hiện hành (230.000 đ chứ không phải giá cũ 200.000 đ)
  assert.ok(tbody.innerHTML.includes("230.000"), "Giá hiện hành 230.000 đ phải được áp");
  assert.ok(tbody.innerHTML.includes("Giá hiện hành"));
});

test("S5-02: Cho phép đặt lại toàn bộ hoặc một phần dòng hàng và tính lại tổng tiền", async () => {
  const oldOrder = {
    id: 300,
    orderCode: "DH-300",
    customerGroup: "DAI_LY_CAP_1",
    items: [
      { id: 1, productId: 101, productCode: "SP-1", productName: "Bia Heineken", quantity: 10, price: 300000 },
      { id: 2, productId: 102, productCode: "SP-2", productName: "Bia Tiger", quantity: 5, price: 250000 },
    ],
  };

  const catalogProducts = [
    { id: 101, code: "SP-1", name: "Bia Heineken", active: true },
    { id: 102, code: "SP-2", name: "Bia Tiger", active: true },
  ];

  const effectivePriceList = {
    items: [
      { productId: 101, sellingPrice: 320000 }, // Giá hiện hành SP-1: 320k
      { productId: 102, sellingPrice: 260000 }, // Giá hiện hành SP-2: 260k
    ],
  };

  const env = await createDealerOrdersEnvironment({
    orders: [oldOrder],
    orderDetail: oldOrder,
    catalogProducts,
    effectivePriceList,
  });

  const bodyEl = env.element("dealer-orders-body");
  const clickEvent = {
    target: {
      closest: (sel) => (sel === "[data-reorder-id]" ? { dataset: { reorderId: "300" } } : null),
    },
  };
  for (const listener of bodyEl.listeners["click"] || []) {
    listener(clickEvent);
  }
  await new Promise((resolve) => setTimeout(resolve, 30));

  // Ban đầu cả 2 dòng hàng đều được chọn (đặt lại toàn bộ dòng hàng)
  // Tổng: (10 * 320.000) + (5 * 260.000) = 3.200.000 + 1.300.000 = 4.500.000 đ
  assert.equal(env.element("reorder-selected-count").textContent, 2);
  assert.equal(env.element("reorder-total-count").textContent, 2);
  assert.ok(env.element("reorder-summary-total").textContent.includes("4.500.000"));

  // Bỏ chọn 1 dòng hàng (chọn một phần dòng hàng)
  const reorderTbody = env.element("dealer-reorder-tbody");
  const changeEvent = {
    target: {
      closest: (sel) => {
        if (sel === ".reorder-item-checkbox") {
          return { dataset: { itemId: "2" }, checked: false, closest: () => null };
        }
        return null;
      },
    },
  };
  for (const listener of reorderTbody.listeners["change"] || []) {
    listener(changeEvent);
  }

  // Bây giờ chỉ còn 1 dòng hàng được chọn (SP-1)
  // Tổng mới: 10 * 320.000 = 3.200.000 đ
  assert.equal(env.element("reorder-selected-count").textContent, 1);
  assert.ok(env.element("reorder-summary-total").textContent.includes("3.200.000"));

  // Gửi đơn đặt lại
  env.element("reorder-delivery-date").value = "2026-10-25";
  const confirmBtn = env.element("btn-confirm-reorder");
  for (const listener of confirmBtn.listeners["click"] || []) {
    await listener();
  }

  // Đơn gửi lên chỉ có đúng 1 dòng hàng đã chọn với giá hiện hành
  assert.equal(env.submittedPayloads.length, 1);
  const payload = env.submittedPayloads[0];
  assert.equal(payload.items.length, 1);
  assert.equal(payload.items[0].productId, 101);
  assert.equal(payload.items[0].quantity, 10);
  assert.equal(payload.items[0].price, undefined);
  assert.ok(env.apiCalls.filter(call => call.options.method === 'POST').every(call => /^\/portal\/orders\/\d+\/reorder$/.test(call.url)));
  assert.ok(env.apiCalls.every(call => call.url.startsWith('/portal/')));
});

test("S5-02: Khi toàn bộ mặt hàng trong đơn cũ đã ngừng kinh doanh thì chặn gửi đơn", async () => {
  const oldOrder = {
    id: 400,
    orderCode: "DH-400",
    customerGroup: "DAI_LY_CAP_1",
    items: [
      { id: 1, productId: 88, productCode: "SP-OLD-1", productName: "Sản phẩm cũ 1", quantity: 2, price: 100000 },
      { id: 2, productId: 89, productCode: "SP-OLD-2", productName: "Sản phẩm cũ 2", quantity: 4, price: 150000 },
    ],
  };

  const catalogProducts = [
    { id: 88, code: "SP-OLD-1", name: "Sản phẩm cũ 1", active: false, status: "INACTIVE" },
    { id: 89, code: "SP-OLD-2", name: "Sản phẩm cũ 2", active: false, status: "INACTIVE" },
  ];

  const env = await createDealerOrdersEnvironment({
    orders: [oldOrder],
    orderDetail: oldOrder,
    catalogProducts,
  });

  const bodyEl = env.element("dealer-orders-body");
  const clickEvent = {
    target: {
      closest: (sel) => (sel === "[data-reorder-id]" ? { dataset: { reorderId: "400" } } : null),
    },
  };
  for (const listener of bodyEl.listeners["click"] || []) {
    listener(clickEvent);
  }
  await new Promise((resolve) => setTimeout(resolve, 30));

  assert.equal(env.element("dealer-reorder-empty-alert").hidden, false);
  assert.equal(env.element("dealer-reorder-selection").hidden, true);
  assert.equal(env.element("btn-confirm-reorder").disabled, true);
});

test("S5-02: Thay đổi số lượng mặt hàng cập nhật trực tiếp thành tiền dòng và tổng tiền đơn", async () => {
  const oldOrder = {
    id: 500,
    orderCode: "DH-500",
    customerGroup: "DAI_LY_CAP_1",
    items: [
      { id: 1, productId: 201, productCode: "SP-MILK", productName: "Sữa tươi TH True Milk", quantity: 2, price: 300000 },
    ],
  };

  const catalogProducts = [
    { id: 201, code: "SP-MILK", name: "Sữa tươi TH True Milk", active: true, price: 310000 },
  ];

  const effectivePriceList = {
    items: [{ productId: 201, sellingPrice: 310000 }],
  };

  const env = await createDealerOrdersEnvironment({
    orders: [oldOrder],
    orderDetail: oldOrder,
    catalogProducts,
    effectivePriceList,
  });

  const bodyEl = env.element("dealer-orders-body");
  for (const listener of bodyEl.listeners["click"] || []) {
    listener({
      target: {
        closest: (sel) => (sel === "[data-reorder-id]" ? { dataset: { reorderId: "500" } } : null),
      },
    });
  }
  await new Promise((resolve) => setTimeout(resolve, 30));

  // Ban đầu: 2 * 310.000 = 620.000 đ
  assert.ok(env.element("reorder-summary-total").textContent.includes("620.000"));

  // Thay đổi số lượng thành 5
  const reorderTbody = env.element("dealer-reorder-tbody");
  for (const listener of reorderTbody.listeners["change"] || []) {
    listener({
      target: {
        closest: (sel) =>
          sel === ".reorder-qty-input"
            ? { dataset: { itemId: "1" }, value: "5" }
            : null,
      },
    });
  }

  // Tổng mới: 5 * 310.000 = 1.550.000 đ
  assert.ok(env.element("reorder-summary-total").textContent.includes("1.550.000"));
});
