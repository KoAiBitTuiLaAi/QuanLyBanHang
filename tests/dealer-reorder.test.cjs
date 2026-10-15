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
      permissions: ["ORDER_VIEW", "ORDER_CREATE"],
      customerGroup: "DAI_LY_CAP_1",
    },
    orders = [],
    orderDetail = null,
    catalogProducts = [],
    effectivePriceList = null,
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

  const context = vm.createContext({
    document: {
      getElementById: (id) => getOrCreateElement(id),
      createElement: (tag) => getOrCreateElement(`dyn-${Math.random()}`),
      querySelectorAll: (sel) => [],
      querySelector: (sel) => null,
      addEventListener: () => {},
    },
    window: {
      location: { href: "/dealer-orders", pathname: "/dealer-orders", search: "" },
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
              data: { id: 999, orderCode: "DH-REORDER-999" },
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
        if (url.startsWith("/portal/orders/") || url.startsWith("/orders/")) {
          return {
            ok: true,
            status: 200,
            json: async () => ({ success: true, data: orderDetail }),
          };
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

test("S5-02: Quyền ORDER_CREATE được liên kết với nút Đặt lại đơn hàng", async () => {
  // 1. Đại lý có quyền ORDER_CREATE
  const envWithPerm = await createDealerOrdersEnvironment({
    currentUser: {
      id: 1,
      username: "dealer1",
      roles: ["ROLE_DEALER"],
      permissions: ["ORDER_VIEW", "ORDER_CREATE"],
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
  assert.equal(payload.items[0].price, 320000);
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

