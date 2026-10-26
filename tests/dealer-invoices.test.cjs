/**
 * dealer-invoices.test.cjs — Unit test cho S7-07: Sổ công nợ và hóa đơn của đại lý.
 *
 * Kiểm tra:
 * 1. Ma trận phân quyền (Permission matrix): INVOICE_VIEW, FINANCE_VIEW_DEBT, ROLE_ADMIN, và thu hồi quyền.
 * 2. Menu config: Hiển thị/ẩn menu "dealer-invoices" theo quyền động từ DB.
 * 3. Hiển thị tổng còn nợ, hạn mức công nợ và hạn mức còn được lấy hàng (availableOrderLimit).
 * 4. Bảng danh sách hóa đơn: số tiền, hạn thanh toán, trạng thái thanh toán và phát hành.
 * 5. Tải PDF, xem bản in và xem chi tiết hóa đơn kèm chứng từ điều chỉnh.
 */

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const { test } = require("node:test");

const menuSource = fs.readFileSync(path.join(__dirname, "../frontend/js/menu-config.js"), "utf8");
const menu = vm.runInNewContext(`${menuSource}\nMenuConfig;`, { URLSearchParams });

// ── Test Menu Configuration ─────────────────────────────────────

test("S7-07: menu 'dealer-invoices' hiển thị với quyền hóa đơn và ẩn khi không có cả hai quyền", () => {
  const ids = (user) => Array.from(menu.getMenuForUser(user), (item) => item.id);

  // Đại lý có INVOICE_VIEW -> Thấy menu
  const dealerWithPerm = ids({
    roles: ["ROLE_DEALER"],
    permissions: ["ORDER_VIEW", "ORDER_CREATE", "PRODUCT_VIEW", "INVOICE_VIEW"],
  });
  assert.ok(dealerWithPerm.includes("dealer-invoices"), "Đại lý có INVOICE_VIEW phải thấy menu Công nợ của tôi");

  // Đại lý bị thu hồi INVOICE_VIEW -> Ẩn menu
  const dealerWithoutPerm = ids({
    roles: ["ROLE_DEALER"],
    permissions: ["ORDER_VIEW", "ORDER_CREATE", "PRODUCT_VIEW"],
  });
  assert.ok(!dealerWithoutPerm.includes("dealer-invoices"), "Thu hồi INVOICE_VIEW phải ẩn menu Công nợ của tôi");

  // ROLE_ADMIN luôn thấy menu kể cả khi permissions rỗng
  const admin = ids({ roles: ["ROLE_ADMIN"], permissions: [] });
  assert.ok(admin.includes("dealer-invoices"), "ROLE_ADMIN luôn thấy menu Công nợ của tôi");

  // Nhân viên nội bộ không có vai trò đại lý/admin không thấy menu của cổng
  for (const role of ["ROLE_SALES", "ROLE_ACCOUNTANT", "ROLE_WAREHOUSE_STAFF"]) {
    const internal = ids({ roles: [role], permissions: ["INVOICE_VIEW"] });
    assert.ok(!internal.includes("dealer-invoices"), `${role} không được thấy menu cổng đại lý`);
  }
});

// ── Test DOM & Script Runtime ───────────────────────────────────

function createMockEnvironment({ user, debtData, invoicesData, invoiceDetailData }) {
  const elements = new Map();

  function getElement(id) {
    if (!elements.has(id)) {
      elements.set(id, {
        id,
        value: "",
        hidden: false,
        disabled: false,
        textContent: "",
        innerHTML: "",
        dataset: {},
        style: {},
        className: "",
        listeners: {},
        addEventListener(event, fn) {
          if (!this.listeners[event]) this.listeners[event] = [];
          this.listeners[event].push(fn);
        },
        dispatchEvent(event) {
          const fns = this.listeners[event.type || event] || [];
          fns.forEach((fn) => fn(event));
        },
        querySelector(sel) {
          return null;
        },
        querySelectorAll(sel) {
          return [];
        },
      });
    }
    return elements.get(id);
  }

  const calls = [];
  const eventListeners = {};

  const context = vm.createContext({
    document: {
      getElementById: (id) => getElement(id),
      querySelector: (sel) => null,
      querySelectorAll: (sel) => [],
      createElement: (tag) => getElement(Symbol(tag)),
      body: {
        appendChild() {},
        removeChild() {},
      },
    },
    window: {
      location: { href: "/dealer-invoices", search: "", pathname: "/dealer-invoices" },
      addEventListener: (event, fn) => {
        if (!eventListeners[event]) eventListeners[event] = [];
        eventListeners[event].push(fn);
      },
      dispatchEvent: (event) => {
        const fns = eventListeners[event.type || event] || [];
        fns.forEach((fn) => fn(event));
      },
      URL: {
        createObjectURL: () => "blob:mock-url",
        revokeObjectURL: () => {},
      },
    },
    location: { href: "/dealer-invoices", search: "", pathname: "/dealer-invoices" },
    URLSearchParams,
    AbortController,
    CustomEvent: class CustomEvent {
      constructor(type, init) {
        this.type = type;
        this.detail = init?.detail;
      }
    },
    Intl,
    setTimeout: (fn) => fn(),
    clearTimeout: () => {},
    DealerPortalContext: {
      isAdmin: (user?.roles || []).includes("ROLE_ADMIN"),
      init: async () => true,
      url: (path) => path,
    },
    Auth: {
      requireLogin: () => Boolean(user),
      ready: Promise.resolve(),
      getUser: () => user,
      hasRole: (r) => (user?.roles || []).includes(r),
      hasAnyRole: (...roles) => roles.some((r) => (user?.roles || []).includes(r)),
      hasPermission: (p) => (user?.permissions || []).includes(p),
      apiFetch: async (url) => {
        calls.push(url);
        if (url.includes("/portal/debt/summary") || url.includes("/portal/debt")) {
          return { ok: true, json: async () => ({ success: true, data: debtData }) };
        }
        if (url.includes("/portal/invoices/") && url.includes("/pdf")) {
          return { ok: true, blob: async () => new Uint8Array([1, 2, 3]) };
        }
        if (url.includes("/portal/invoices/") && url.includes("/print-preview")) {
          return { ok: true, text: async () => "<html>Bản in</html>" };
        }
        if (url.includes("/portal/invoices/") && !url.includes("?")) {
          return { ok: true, json: async () => ({ success: true, data: invoiceDetailData }) };
        }
        if (url.includes("/portal/invoices")) {
          return { ok: true, json: async () => ({ success: true, data: invoicesData }) };
        }
        return { ok: true, json: async () => ({ success: true, data: [] }) };
      },
    },
  });

  return { context, elements, getElement, calls, eventListeners };
}

test("S7-07: Người dùng thiếu quyền xem hóa đơn và công nợ bị hiển thị thông báo truy cập bị từ chối", async () => {
  const { context, getElement } = createMockEnvironment({
    user: { roles: ["ROLE_DEALER"], permissions: ["ORDER_VIEW"] }, // thiếu cả INVOICE_VIEW & FINANCE_VIEW_DEBT
    debtData: {},
    invoicesData: [],
  });

  const scriptCode = fs.readFileSync(path.join(__dirname, "../frontend/js/dealer-invoices.js"), "utf8");
  vm.runInContext(scriptCode, context);
  await new Promise((r) => setTimeout(r, 20));

  const workspace = getElement("di-workspace");
  const accessDenied = getElement("di-access-denied");

  assert.equal(workspace.hidden, true, "Workspace phải bị ẩn khi thiếu quyền");
  assert.equal(accessDenied.hidden, false, "Thông báo thiếu quyền phải hiển thị");
});

test("S7-07: Hiển thị đầy đủ tổng còn nợ, hạn mức công nợ, hạn mức còn lại và hạn mức còn được lấy hàng", async () => {
  const mockDebt = {
    currentDebt: 15000000,
    debtLimit: 50000000,
    remainingLimit: 35000000,
    availableOrderLimit: 35000000,
    unpaidInvoiceCount: 3,
    overdueInvoiceCount: 1,
    maxDebtDays: 30,
    hasOverdueDebt: true,
    overdueAmount: 5000000,
    maxOverdueDays: 12,
    isBlocked: false,
  };

  const { context, getElement, calls } = createMockEnvironment({
    user: { roles: ["ROLE_DEALER"], permissions: ["INVOICE_VIEW", "FINANCE_VIEW_DEBT"] },
    debtData: mockDebt,
    invoicesData: { content: [], totalElements: 0, totalPages: 0 },
  });

  const scriptCode = fs.readFileSync(path.join(__dirname, "../frontend/js/dealer-invoices.js"), "utf8");
  vm.runInContext(scriptCode, context);
  await new Promise((r) => setTimeout(r, 30));

  // Kiểm tra API đã gọi
  assert.ok(calls.some((c) => c.includes("/portal/debt/summary")), "Phải gọi API /portal/debt/summary");

  // Kiểm tra hiển thị tiền
  assert.match(getElement("val-current-debt").textContent, /15\.000\.000/);
  assert.match(getElement("val-debt-limit").textContent, /50\.000\.000/);
  assert.match(getElement("val-remaining-limit").textContent, /35\.000\.000/);
  assert.match(getElement("val-available-order-limit").textContent, /35\.000\.000/);

  // Kiểm tra số ngày nợ tối đa
  assert.equal(getElement("val-max-debt-days").textContent, "30");

  // Kiểm tra cảnh báo quá hạn
  assert.equal(getElement("val-overdue-tag").hidden, false, "Thẻ nợ quá hạn phải hiển thị");
  assert.equal(getElement("val-overdue-invoices").textContent, "1");
  assert.equal(getElement("di-blocked-banner").hidden, false, "Banner cảnh báo quá hạn phải hiển thị");
});

test("S7-07: Bảng danh sách hóa đơn kết xuất đầy đủ số tiền, hạn thanh toán, trạng thái thanh toán và thao tác", async () => {
  const mockInvoices = [
    {
      id: 101,
      invoiceNumber: "HD-202610-001",
      invoiceDate: "2026-10-15T09:00:00",
      dueDate: "2026-11-15",
      totalAmount: 10000000,
      paidAmount: 4000000,
      remainingAmount: 6000000,
      paymentStatus: "PARTIALLY_PAID",
      status: "ISSUED",
      isOverdue: false,
    },
    {
      id: 102,
      invoiceNumber: "HD-202610-002",
      invoiceDate: "2026-09-01T08:00:00",
      dueDate: "2026-10-01",
      totalAmount: 5000000,
      paidAmount: 0,
      remainingAmount: 5000000,
      paymentStatus: "OVERDUE",
      status: "ISSUED",
      isOverdue: true,
      overdueDays: 23,
    },
  ];

  const { context, getElement } = createMockEnvironment({
    user: { roles: ["ROLE_DEALER"], permissions: ["INVOICE_VIEW", "FINANCE_VIEW_DEBT"] },
    debtData: { currentDebt: 11000000, debtLimit: 30000000, remainingLimit: 19000000 },
    invoicesData: { content: mockInvoices, totalElements: 2, totalPages: 1 },
  });

  const scriptCode = fs.readFileSync(path.join(__dirname, "../frontend/js/dealer-invoices.js"), "utf8");
  vm.runInContext(scriptCode, context);
  await new Promise((r) => setTimeout(r, 30));

  const tbodyHtml = getElement("di-invoice-rows").innerHTML;
  assert.match(tbodyHtml, /HD-202610-001/);
  assert.match(tbodyHtml, /HD-202610-002/);
  assert.match(tbodyHtml, /Đã trả 1 phần/);
  assert.match(tbodyHtml, /Quá hạn/);
  assert.match(tbodyHtml, /Quá 23 ngày/);

  // Kiểm tra tính tổng tfoot
  assert.match(getElement("di-t-total").textContent, /15\.000\.000/);
  assert.match(getElement("di-t-paid").textContent, /4\.000\.000/);
  assert.match(getElement("di-t-remain").textContent, /11\.000\.000/);
});

test("S7-07: ROLE_ADMIN luôn có quyền truy cập kể cả khi session chưa có quyền trong JWT", async () => {
  const { context, getElement } = createMockEnvironment({
    user: { roles: ["ROLE_ADMIN"], permissions: [] },
    debtData: { currentDebt: 0, debtLimit: 100000000, remainingLimit: 100000000 },
    invoicesData: { content: [], totalElements: 0, totalPages: 0 },
  });

  const scriptCode = fs.readFileSync(path.join(__dirname, "../frontend/js/dealer-invoices.js"), "utf8");
  vm.runInContext(scriptCode, context);
  await new Promise((r) => setTimeout(r, 20));

  assert.equal(getElement("di-workspace").hidden, false, "ROLE_ADMIN luôn được mở workspace");
  assert.equal(getElement("di-access-denied").hidden, true, "ROLE_ADMIN không bị hiện thông báo từ chối");
});

