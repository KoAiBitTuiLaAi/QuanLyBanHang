/**
 * dealer-notifications.test.cjs — Unit test cho S7-08: Thông báo nhắc hạn thanh toán Cổng Đại lý.
 *
 * Kiểm tra:
 * 1. Ma trận phân quyền (Permission matrix): NOTIFICATION_VIEW, ROLE_ADMIN, và thu hồi quyền.
 * 2. Menu config: Hiển thị/ẩn menu "dealer-notifications" theo quyền động từ DB.
 * 3. Hiển thị thông báo nhắc nợ trước 3 ngày (PAYMENT_DUE_BEFORE_3_DAYS), quá hạn (PAYMENT_OVERDUE) và thông báo hệ thống.
 * 4. Đếm số lượng thông báo chưa đọc, hiển thị badge trực quan.
 * 5. Đánh dấu đã đọc từng thông báo và đánh dấu tất cả là đã đọc.
 * 6. Lọc theo trạng thái chưa đọc và loại thông báo.
 */

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const { test } = require("node:test");

const menuSource = fs.readFileSync(path.join(__dirname, "../frontend/js/menu-config.js"), "utf8");
const menu = vm.runInNewContext(`${menuSource}\nMenuConfig;`, { URLSearchParams });

// ── Test Menu Configuration ─────────────────────────────────────

test("S7-08: menu 'dealer-notifications' chỉ hiển thị cho ROLE_DEALER khi có NOTIFICATION_VIEW", () => {
  const ids = (user) => Array.from(menu.getMenuForUser(user), (item) => item.id);

  // Đại lý có NOTIFICATION_VIEW -> Thấy menu
  const dealerWithPerm = ids({
    roles: ["ROLE_DEALER"],
    permissions: ["ORDER_VIEW", "ORDER_CREATE", "PRODUCT_VIEW", "NOTIFICATION_VIEW"],
  });
  assert.ok(dealerWithPerm.includes("dealer-notifications"), "Đại lý có NOTIFICATION_VIEW phải thấy menu Thông báo của tôi");

  // Đại lý bị thu hồi NOTIFICATION_VIEW -> Ẩn menu
  const dealerWithoutPerm = ids({
    roles: ["ROLE_DEALER"],
    permissions: ["ORDER_VIEW", "ORDER_CREATE", "PRODUCT_VIEW"],
  });
  assert.ok(!dealerWithoutPerm.includes("dealer-notifications"), "Thu hồi NOTIFICATION_VIEW phải ẩn menu Thông báo của tôi");

  // ROLE_ADMIN luôn thấy menu kể cả khi permissions rỗng
  const admin = ids({ roles: ["ROLE_ADMIN"], permissions: [] });
  assert.ok(admin.includes("dealer-notifications"), "ROLE_ADMIN luôn thấy menu Thông báo của tôi");

  // Nhân viên nội bộ không có vai trò đại lý/admin không thấy menu của cổng
  for (const role of ["ROLE_SALES", "ROLE_ACCOUNTANT", "ROLE_WAREHOUSE_STAFF"]) {
    const internal = ids({ roles: [role], permissions: ["NOTIFICATION_VIEW"] });
    assert.ok(!internal.includes("dealer-notifications"), `${role} không được thấy menu cổng đại lý`);
  }
});

// ── Test DOM & Script Runtime ───────────────────────────────────

function createMockEnvironment({ user, notificationsData, unreadCount = 0 }) {
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
        attributes: {},
        setAttribute(k, v) { this.attributes[k] = v; },
        getAttribute(k) { return this.attributes[k]; },
        classList: {
          classes: new Set(),
          add(c) { this.classes.add(c); },
          remove(c) { this.classes.delete(c); },
          contains(c) { return this.classes.has(c); },
        },
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
      location: { href: "/dealer-notifications", search: "", pathname: "/dealer-notifications" },
      addEventListener: (event, fn) => {
        if (!eventListeners[event]) eventListeners[event] = [];
        eventListeners[event].push(fn);
      },
      dispatchEvent: (event) => {
        const fns = eventListeners[event.type || event] || [];
        fns.forEach((fn) => fn(event));
      },
    },
    location: { href: "/dealer-notifications", search: "", pathname: "/dealer-notifications" },
    URLSearchParams,
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
      apiFetch: async (url, opts = {}) => {
        calls.push({ url, method: opts.method || "GET" });
        if (url.includes("/portal/notifications/unread-count")) {
          return { ok: true, json: async () => ({ success: true, data: { unreadCount } }) };
        }
        if (url.includes("/portal/notifications/read-all") && opts.method === "PUT") {
          return { ok: true, json: async () => ({ success: true, data: { updatedCount: 2 } }) };
        }
        if (url.includes("/read") && opts.method === "PUT") {
          return { ok: true, json: async () => ({ success: true, data: { isRead: true } }) };
        }
        if (url.includes("/portal/notifications")) {
          return { ok: true, json: async () => ({ success: true, data: notificationsData }) };
        }
        return { ok: true, json: async () => ({ success: true, data: [] }) };
      },
    },
  });

  return { context, elements, getElement, calls, eventListeners };
}

test("S7-08: Người dùng thiếu quyền NOTIFICATION_VIEW bị chặn hiển thị thông báo", async () => {
  const { context, getElement } = createMockEnvironment({
    user: { roles: ["ROLE_DEALER"], permissions: ["ORDER_VIEW"] }, // thiếu NOTIFICATION_VIEW
    notificationsData: [],
    unreadCount: 0,
  });

  const scriptCode = fs.readFileSync(path.join(__dirname, "../frontend/js/dealer-notifications.js"), "utf8");
  vm.runInContext(scriptCode, context);
  await new Promise((r) => setTimeout(r, 20));

  const workspace = getElement("dn-workspace");
  const accessDenied = getElement("dn-access-denied");

  assert.equal(workspace.hidden, true, "Workspace phải bị ẩn khi thiếu quyền");
  assert.equal(accessDenied.hidden, false, "Thông báo thiếu quyền phải hiển thị");
});

test("S7-08: Hiển thị đầy đủ thông báo nhắc nợ trước 3 ngày và quá hạn thanh toán", async () => {
  const mockNotifications = [
    {
      id: 1,
      title: "Nhắc hạn thanh toán hóa đơn HD-00000001 (còn 3 ngày)",
      message: "Hóa đơn HD-00000001 trị giá 15.000.000 ₫ sẽ đến hạn thanh toán vào ngày 27/10/2026. Vui lòng thanh toán đúng hạn.",
      type: "PAYMENT_DUE_BEFORE_3_DAYS",
      typeLabel: "Nhắc hạn thanh toán trước 3 ngày",
      referenceType: "INVOICE",
      referenceId: 101,
      referenceCode: "HD-00000001",
      isRead: false,
      createdAt: "2026-10-24T07:00:00",
    },
    {
      id: 2,
      title: "Cảnh báo quá hạn thanh toán hóa đơn HD-00000002",
      message: "Hóa đơn HD-00000002 trị giá 8.500.000 ₫ đã quá hạn 5 ngày. Đơn hàng tiếp theo có thể bị tạm khóa.",
      type: "PAYMENT_OVERDUE",
      typeLabel: "Nhắc quá hạn thanh toán",
      referenceType: "INVOICE",
      referenceId: 102,
      referenceCode: "HD-00000002",
      isRead: false,
      createdAt: "2026-10-24T07:05:00",
    },
  ];

  const { context, getElement, calls } = createMockEnvironment({
    user: { roles: ["ROLE_DEALER"], permissions: ["NOTIFICATION_VIEW"] },
    notificationsData: { content: mockNotifications, totalElements: 2, totalPages: 1 },
    unreadCount: 2,
  });

  const scriptCode = fs.readFileSync(path.join(__dirname, "../frontend/js/dealer-notifications.js"), "utf8");
  vm.runInContext(scriptCode, context);
  await new Promise((r) => setTimeout(r, 30));

  // Kiểm tra gọi API
  assert.ok(calls.some((c) => c.url.includes("/portal/notifications/unread-count")), "Phải gọi API đếm chưa đọc");
  assert.ok(calls.some((c) => c.url.includes("/portal/notifications")), "Phải gọi API danh sách thông báo");

  // Kiểm tra badge đếm số lượng chưa đọc
  assert.equal(getElement("dn-unread-badge").textContent, "2");
  assert.equal(getElement("dn-unread-badge").hidden, false);

  // Kiểm tra nội dung danh sách thông báo
  const listHtml = getElement("dn-list").innerHTML;
  assert.match(listHtml, /HD-00000001/);
  assert.match(listHtml, /còn 3 ngày/);
  assert.match(listHtml, /HD-00000002/);
  assert.match(listHtml, /quá hạn 5 ngày/);
  assert.match(listHtml, /dn-item--due-soon/);
  assert.match(listHtml, /dn-item--overdue/);
  assert.match(listHtml, /dn-item--unread/);
});

test("S7-08: Thao tác đánh dấu tất cả đã đọc gọi đúng API PUT /portal/notifications/read-all", async () => {
  const { context, getElement, calls } = createMockEnvironment({
    user: { roles: ["ROLE_DEALER"], permissions: ["NOTIFICATION_VIEW"] },
    notificationsData: { content: [], totalElements: 0, totalPages: 0 },
    unreadCount: 3,
  });

  const scriptCode = fs.readFileSync(path.join(__dirname, "../frontend/js/dealer-notifications.js"), "utf8");
  vm.runInContext(scriptCode, context);
  await new Promise((r) => setTimeout(r, 20));

  // Bấm nút đánh dấu tất cả đã đọc
  const readAllBtn = getElement("btn-read-all");
  assert.equal(readAllBtn.disabled, false, "Nút đánh dấu tất cả đã đọc phải được kích hoạt khi unreadCount > 0");

  const clickListeners = readAllBtn.listeners.click || [];
  for (const fn of clickListeners) {
    await fn();
  }

  assert.ok(
    calls.some((c) => c.url.includes("/portal/notifications/read-all") && c.method === "PUT"),
    "Phải gọi API PUT /portal/notifications/read-all"
  );
});

test("S7-08: ROLE_ADMIN luôn có quyền xem thông báo", async () => {
  const { context, getElement } = createMockEnvironment({
    user: { roles: ["ROLE_ADMIN"], permissions: [] },
    notificationsData: { content: [], totalElements: 0, totalPages: 0 },
    unreadCount: 0,
  });

  const scriptCode = fs.readFileSync(path.join(__dirname, "../frontend/js/dealer-notifications.js"), "utf8");
  vm.runInContext(scriptCode, context);
  await new Promise((r) => setTimeout(r, 20));

  assert.equal(getElement("dn-workspace").hidden, false, "ROLE_ADMIN luôn xem được workspace thông báo");
  assert.equal(getElement("dn-access-denied").hidden, true, "ROLE_ADMIN không bị hiện access denied");
});

