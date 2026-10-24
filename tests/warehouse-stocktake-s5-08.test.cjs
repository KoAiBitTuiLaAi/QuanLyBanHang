// Pure JS fixtures only: these regressions never connect to the real API/database.
const assert = require("node:assert/strict");
const { test } = require("node:test");
const S = require("../frontend/js/warehouse-stocktake.js");

const copy = value => JSON.parse(JSON.stringify(value));
const base = "/warehouse/stock-counts";
const viewer = () => ({ roles: ["ROLE_VIEWER"], permissions: ["WAREHOUSE_VIEW"] });
function deferred() {
  let resolve;
  const promise = new Promise(done => { resolve = done; });
  return { promise, resolve };
}
function fixture(initialUser = viewer()) {
  let user = initialUser, nextId = 30;
  const calls = [], rows = new Map(), failures = new Map(), gates = new Map(), observed = [];
  const options = {
    warehouses: [{ id: 7, name: "Kho <Hà Nội>" }, { id: 9, name: "Kho thứ hai" }],
    categories: [{ id: 42, name: "Nhóm thật" }],
    statuses: [{ code: "DRAFT", label: "Bản nháp" }, { code: "COMPLETED", label: "Đã chốt" }],
    canAdjust: S.canUserConfirmStocktake(user),
  };
  const decorate = row => copy({ ...row, canEdit: row.status === "DRAFT",
    canAdjust: row.status === "DRAFT" && S.canUserConfirmStocktake(user) });
  const request = async (path, init = {}) => {
    const method = init.method || "GET", body = init.body ? JSON.parse(init.body) : undefined;
    calls.push({ path, method, body });
    const key = method + " " + path.split("?")[0];
    if (gates.has(key)) await gates.get(key).promise;
    if (failures.has(key)) throw new Error(failures.get(key));
    if (path === base + "/options") return copy({ ...options, canAdjust: S.canUserConfirmStocktake(user) });
    if (path.startsWith(base + "?")) {
      const query = new URLSearchParams(path.split("?")[1]), page = Number(query.get("page")), size = Number(query.get("size"));
      const selected = [...rows.values()].filter(row => String(row.warehouseId) === query.get("warehouseId"));
      return { content: selected.slice(page * size, (page + 1) * size).map(decorate), number: page,
        totalPages: Math.ceil(selected.length / size), totalElements: selected.length };
    }
    if (path === base && method === "POST") {
      const id = ++nextId;
      const row = { id, code: "SERVER-CODE-" + id, warehouseId: body.warehouseId, warehouseName: "Kho từ máy chủ",
        categoryId: body.categoryId, categoryName: body.categoryId ? "Nhóm thật" : null,
        createdAt: "2026-10-21T08:00:00", createdBy: "server-user", status: "DRAFT", note: "",
        items: [{ productId: 101, productCode: "SP-A", productName: "Hàng A", categoryName: "Nhóm thật", baseUnit: "lon",
          bookQuantity: 11.25, actualQuantity: null, differenceQuantity: null, note: "" },
          { productId: 102, productCode: "SP-B", productName: "Hàng B", baseUnit: "chai",
            bookQuantity: 0, actualQuantity: null, differenceQuantity: null, note: "" }] };
      rows.set(id, row);
      return decorate(row);
    }
    const match = path.match(/^\/warehouse\/stock-counts\/(\d+)(?:\/(items|complete|cancel))?$/);
    assert.ok(match, "Unexpected API path: " + path);
    const row = rows.get(Number(match[1]));
    if (!row) throw new Error("Không tìm thấy phiếu");
    if (method === "GET") return decorate(row);
    assert.equal(row.status, "DRAFT");
    if (body.items) for (const item of body.items) Object.assign(row.items.find(i => i.productId === item.productId), item);
    if (body.note !== undefined) row.note = body.note;
    if (match[2] === "complete") { row.status = "COMPLETED"; row.adjustmentReason = body.reason; }
    if (match[2] === "cancel") { row.status = "CANCELLED"; row.cancelReason = body.reason; }
    return decorate(row);
  };
  const client = S.createClient({ request, getUser: () => user,
    onChange: () => observed.push({ pending: client.state.pending, current: copy(client.state.current) }) });
  return { client, calls, rows, failures, gates, options, observed, setUser(next) { user = next; } };
}
async function draft(user = { roles: ["ROLE_VIEWER"], permissions: ["WAREHOUSE_ADJUST"] }) {
  const f = fixture(user);
  await f.client.initialize();
  await f.client.create("42");
  return f;
}
function counted(client) {
  client.state.current.items[0].actualQuantity = 0;
  client.state.current.items[1].actualQuantity = "4.125";
  client.state.reason = " Kiểm kê cuối kỳ ";
  client.state.dirty = true;
}

test("matrix permissions, union, grant/revoke and ADMIN have no manager role fallback", () => {
  for (const role of ["ROLE_VIEWER", "ROLE_SALES", "ROLE_WAREHOUSE_STAFF", "ROLE_WAREHOUSE_MANAGER"]) {
    const user = { roles: [role], permissions: [] };
    assert.equal(S.canUserReadStocktake(user), false);
    assert.equal(S.canUserConfirmStocktake(user), false);
    user.permissions = ["WAREHOUSE_VIEW"];
    assert.equal(S.canUserReadStocktake(user), true);
    assert.equal(S.canUserConfirmStocktake(user), false);
    user.permissions = ["WAREHOUSE_ADJUST"];
    assert.equal(S.canUserReadStocktake(user), true);
    assert.equal(S.canUserConfirmStocktake(user), true);
    user.permissions = [];
    assert.equal(S.canUserConfirmStocktake(user), false);
  }
  const multi = { roles: ["ROLE_SALES", "ROLE_VIEWER"], permissions: ["ORDER_VIEW", "WAREHOUSE_ADJUST"] };
  assert.equal(S.canUserConfirmStocktake(multi), true);
  assert.equal(S.canUserReadStocktake({ roles: ["ROLE_ADMIN"], permissions: [] }), true);
  assert.equal(S.canUserConfirmStocktake({ roles: ["ROLE_ADMIN"], permissions: [] }), true);
  assert.equal(S.canUserReadStocktake(null), false);
});

test("blank/null are uncounted, zero is counted, and differences display at backend precision", () => {
  for (const value of [null, undefined, "", "  "]) assert.deepEqual(S.calculateDifference(value, 3),
    { diff: null, status: "UNCOUNTED", label: "Chưa đếm" });
  assert.deepEqual(S.calculateDifference("0", 3), { diff: -3, status: "DEFICIT", label: "-3" });
  assert.deepEqual(S.calculateDifference(0.3, 0.1), { diff: 0.2, status: "SURPLUS", label: "+0.2" });
  assert.equal(S.calculateDifference(1.2345, 1.2345).status, "MATCH");
  assert.equal(S.calculateDifference("Infinity", 0).status, "INVALID");
  assert.deepEqual(S.calculateMetrics([
    { actualQuantity: null, bookQuantity: 0 }, { actualQuantity: 0, bookQuantity: 0 },
    { actualQuantity: 2, bookQuantity: 0 }, { actualQuantity: 0, bookQuantity: 3 },
    { actualQuantity: -1, bookQuantity: 0 },
  ]), { total: 5, match: 1, surplus: 1, deficit: 1, uncounted: 1, invalid: 1 });
});

test("ADJUST-only startup uses only stock-count options and a paged history", async () => {
  const f = fixture({ roles: ["ROLE_SALES"], permissions: ["WAREHOUSE_ADJUST"] });
  await f.client.initialize("9");
  assert.deepEqual(f.calls.map(call => call.path), [base + "/options", base + "?warehouseId=9&page=0&size=20"]);
  assert.equal(f.client.state.warehouseId, 9);
  assert.equal(f.client.state.ready, true);
  assert.equal(f.client.state.totalPages, 0);
});

test("create uses server IDs, snapshot, creator and code without generated quantities", async () => {
  const f = await draft();
  assert.deepEqual(f.calls.at(-1), { path: base, method: "POST", body: { warehouseId: 7, categoryId: 42 } });
  assert.equal(f.client.state.current.code, "SERVER-CODE-31");
  assert.equal(f.client.state.current.items[0].bookQuantity, 11.25);
  assert.equal(f.client.state.current.items[0].actualQuantity, null);
  assert.equal(f.client.state.current.createdBy, "server-user");
  await f.client.create("ALL");
  assert.deepEqual(f.calls.at(-1).body, { warehouseId: 7 });
  await assert.rejects(f.client.create("Nhóm thật"), /Nhóm hàng/);
  assert.equal(S.freezeBookStock, undefined);
  assert.equal(S.generateStocktakeCode, undefined);
});

test("PUT persists partial draft with literal null and zero, notes, and restorable detail", async () => {
  const f = await draft(viewer()), c = f.client;
  c.state.current.items[0].actualQuantity = "0";
  c.state.current.items[0].note = '<img src=x onerror="alert(1)">';
  c.state.current.note = "Ghi chú phiếu";
  c.state.reason = "Giữ lý do sau khi lưu";
  await c.save();
  assert.deepEqual(f.calls.at(-1), { path: base + "/31/items", method: "PUT", body: {
    items: [{ productId: 101, actualQuantity: 0, note: '<img src=x onerror="alert(1)">' },
      { productId: 102, actualQuantity: null, note: "" }], note: "Ghi chú phiếu" } });
  assert.equal(c.state.reason, "Giữ lý do sau khi lưu");
  c.invalidate();
  await c.initialize(7);
  await c.history();
  assert.equal(c.state.history[0].id, 31);
  await c.open(31);
  assert.equal(c.state.current.items[0].actualQuantity, 0);
  assert.equal(c.state.current.items[1].actualQuantity, null);
  assert.equal(c.state.current.note, "Ghi chú phiếu");
});

test("complete requires reason and all counted; zero and decimal counts post to /complete", async () => {
  const { client: c, calls } = await draft();
  counted(c);
  c.state.reason = " ";
  await assert.rejects(c.complete(), /lý do/);
  c.state.reason = "Kiểm kê";
  c.state.current.items[1].actualQuantity = null;
  await assert.rejects(c.complete(), /Số lượng/);
  c.state.current.items[1].actualQuantity = "4.125";
  await c.complete();
  assert.deepEqual(calls.at(-1), { path: base + "/31/complete", method: "POST", body: {
    reason: "Kiểm kê", items: [{ productId: 101, actualQuantity: 0, note: "" },
      { productId: 102, actualQuantity: 4.125, note: "" }] } });
  assert.equal(c.state.current.status, "COMPLETED");
  assert.equal(c.canEdit(), false);
  assert.equal(c.canComplete(), false);
  for (const action of [() => c.save(), () => c.complete(), () => c.cancel()]) await assert.rejects(action(), /phiếu nháp/);
});

test("client rejects invalid, negative, nonfinite and overprecision quantities before writes", async () => {
  const { client: c, calls } = await draft();
  for (const value of [-1, "abc", Infinity, NaN, true, {}, 0.00001, 100000000000]) {
    c.state.current.items[0].actualQuantity = value;
    const before = calls.length;
    await assert.rejects(c.save());
    assert.equal(calls.length, before);
  }
  for (const value of ["0", 0, 0.0001, "1.2300", "4.1234"]) {
    c.state.current.items[0].actualQuantity = value;
    await c.save();
    assert.equal(c.state.current.items[0].actualQuantity, Number(value));
  }
});

test("completion saves an edited voucher note first, retaining counts/reason if completion fails", async () => {
  const f = await draft(), c = f.client;
  counted(c); c.state.current.note = "Ghi chú trước khi chốt";
  const expected = copy(c.state.current), reason = c.state.reason;
  f.failures.set("POST " + base + "/31/complete", "Tồn kho đã thay đổi");
  await assert.rejects(c.complete(), /Tồn kho đã thay đổi/);
  assert.deepEqual(f.calls.slice(-2).map(call => [call.method, call.path]), [
    ["PUT", base + "/31/items"], ["POST", base + "/31/complete"],
  ]);
  assert.equal(f.rows.get(31).note, expected.note);
  assert.deepEqual(c.state.current, expected);
  assert.equal(c.state.reason, reason);
  assert.equal(c.state.pending, false);
  f.failures.clear();
  await c.complete();
  assert.equal(c.state.current.note, expected.note);
  assert.equal(c.state.current.status, "COMPLETED");
  assert.equal(f.calls.filter(call => call.method === "PUT").length, 1, "Already persisted note needs no second save");
});

test("failed voucher-note save prevents completion and leaves all unsaved inputs intact", async () => {
  const f = await draft(), c = f.client;
  counted(c); c.state.current.note = "Ghi chú chưa lưu";
  f.failures.set("PUT " + base + "/31/items", "Không lưu được ghi chú");
  await assert.rejects(c.complete(), /Không lưu được ghi chú/);
  assert.equal(c.state.current.note, "Ghi chú chưa lưu");
  assert.equal(c.state.current.items[0].actualQuantity, 0);
  assert.equal(c.state.reason, " Kiểm kê cuối kỳ ");
  assert.equal(f.calls.filter(call => call.path.endsWith("/complete")).length, 0);
});

test("all notes and reasons enforce the 500-character limit", async () => {
  const { client: c, calls } = await draft();
  const over = "x".repeat(501);
  c.state.current.items[0].note = over;
  await assert.rejects(c.save(), /500/);
  c.state.current.items[0].note = "";
  c.state.current.note = over;
  await assert.rejects(c.save(), /500/);
  c.state.current.note = "";
  counted(c); c.state.reason = over;
  await assert.rejects(c.complete(), /500/);
  c.state.cancelReason = over;
  await assert.rejects(c.cancel(), /500/);
  assert.equal(calls.filter(call => call.method === "PUT").length, 0);
  c.state.current.items[0].note = "x".repeat(500);
  await c.save();
});

test("server canEdit/canAdjust/options flags restrict actions in addition to current matrix", async () => {
  const f = await draft(), c = f.client;
  counted(c);
  c.state.current.canEdit = false;
  await assert.rejects(c.save(), /phiếu nháp/);
  c.state.current.canEdit = true;
  c.state.current.canAdjust = false;
  await assert.rejects(c.complete(), /WAREHOUSE_ADJUST/);
  c.state.current.canAdjust = true;
  c.state.options.canAdjust = false;
  await assert.rejects(c.complete(), /WAREHOUSE_ADJUST/);
  f.setUser(viewer()); c.state.options.canAdjust = true;
  await assert.rejects(c.complete(), /WAREHOUSE_ADJUST/);
  assert.equal(c.canEdit(), true);
  assert.equal(c.canComplete(), false);
});

test("server failures preserve counts, nulls, notes and reason; busy render sees those same inputs", async () => {
  const f = await draft(), c = f.client;
  counted(c);
  c.state.current.items[0].note = "Giữ ghi chú";
  const before = copy(c.state);
  f.failures.set("PUT " + base + "/31/items", "Không lưu được số đếm");
  await assert.rejects(c.save(), /Không lưu được số đếm/);
  assert.deepEqual(c.state.current, before.current);
  assert.equal(c.state.reason, before.reason);
  assert.equal(c.state.dirty, true);
  assert.equal(c.state.pending, false);
  f.failures.set("POST " + base + "/31/complete", "Có phát sinh nhập xuất sau thời điểm chốt sổ");
  await assert.rejects(c.complete(), /phát sinh nhập xuất/);
  assert.deepEqual(c.state.current, before.current);
  assert.equal(c.state.reason, before.reason);
  assert.ok(f.observed.some(row => row.pending && row.current?.items[0].actualQuantity === 0
    && row.current.items[0].note === "Giữ ghi chú"));
});

test("pending write prevents duplicate submissions and disables until settled", async () => {
  const f = fixture(), gate = deferred();
  await f.client.initialize();
  f.gates.set("POST " + base, gate);
  const first = f.client.create("42");
  assert.equal(f.client.state.pending, true);
  await assert.rejects(f.client.create("42"), /Đang xử lý/);
  await assert.rejects(f.client.history(), /Đang xử lý/);
  assert.equal(f.calls.filter(call => call.method === "POST").length, 1);
  gate.resolve(); await first;
  assert.equal(f.client.state.pending, false);
});

test("successful completion remains immutable when subsequent history reload fails", async () => {
  const f = await draft(), c = f.client;
  counted(c); await c.complete();
  f.failures.set("GET " + base, "Lỗi tải lại lịch sử");
  await assert.rejects(c.history(), /Lỗi tải lại/);
  assert.equal(c.state.current.status, "COMPLETED");
  assert.equal(c.canComplete(), false);
  assert.equal(f.calls.filter(call => call.path.endsWith("/complete")).length, 1);
});

test("cancel persists optional reason at /cancel and makes the detail immutable", async () => {
  const f = await draft(viewer()), c = f.client;
  c.state.cancelReason = " Nhầm phạm vi ";
  await c.cancel();
  assert.deepEqual(f.calls.at(-1), { path: base + "/31/cancel", method: "POST", body: { reason: "Nhầm phạm vi" } });
  assert.equal(c.state.current.status, "CANCELLED");
  assert.equal(c.canEdit(), false);
  await assert.rejects(c.save(), /phiếu nháp/);
  await c.open(31);
  assert.equal(c.state.cancelReason, "Nhầm phạm vi");
});

test("warehouse change clears draft/counts/reasons even when new history request fails", async () => {
  const f = await draft(), c = f.client;
  counted(c);
  f.failures.set("GET " + base, "Không tải được kho");
  await assert.rejects(c.selectWarehouse("9"), /Không tải được kho/);
  assert.equal(c.state.warehouseId, 9);
  assert.equal(c.state.current, null);
  assert.equal(c.state.reason, "");
  assert.deepEqual(c.state.history, []);
  assert.equal(c.state.dirty, false);
  await assert.rejects(c.selectWarehouse("42"), /chọn kho/);
});

test("history pages use backend IDs and counts; options reload clears unavailable warehouse draft", async () => {
  const f = await draft(), c = f.client;
  const row = copy(c.state.current);
  for (let i = 100; i < 124; i++) f.rows.set(i, { ...row, id: i, code: "SERVER-" + i });
  await c.history(1);
  assert.equal(c.state.page, 1);
  assert.equal(c.state.totalPages, 2);
  assert.equal(c.state.totalElements, 25);
  assert.equal(c.state.history.length, 5);
  assert.match(f.calls.at(-1).path, /warehouseId=7&page=1&size=20/);
  f.options.warehouses = [{ id: 9, name: "Kho còn được cấp" }];
  await c.initialize(7);
  assert.equal(c.state.warehouseId, 9);
  assert.equal(c.state.current, null);
});

for (const action of ["options", "detail", "create", "save", "complete"]) {
  test("late " + action + " response cannot restore sensitive draft after permission revocation", async () => {
    const f = action === "options" ? fixture() : await draft(), c = f.client;
    const gate = deferred();
    let path, method, invoke;
    if (action === "options") { path = base + "/options"; method = "GET"; invoke = () => c.initialize(); }
    if (action === "detail") { path = base + "/31"; method = "GET"; invoke = () => c.open(31); }
    if (action === "create") { path = base; method = "POST"; invoke = () => c.create("42"); }
    if (action === "save") { path = base + "/31/items"; method = "PUT"; invoke = () => c.save(); }
    if (action === "complete") { counted(c); path = base + "/31/complete"; method = "POST"; invoke = () => c.complete(); }
    f.gates.set(method + " " + path, gate);
    const pending = invoke();
    f.setUser({ roles: ["ROLE_VIEWER"], permissions: [] });
    c.invalidate();
    assert.equal(c.state.current, null);
    assert.equal(c.state.options, null);
    assert.equal(c.state.reason, "");
    assert.deepEqual(c.state.history, []);
    gate.resolve();
    await assert.rejects(pending, error => error.code === "STALE_STOCKTAKE");
    assert.equal(c.state.current, null);
    assert.equal(c.state.options, null);
    assert.equal(c.canRead(), false);
    const before = f.calls.length;
    await assert.rejects(c.create("42"), /không có quyền/);
    assert.equal(f.calls.length, before);
  });
}

test("revoking ADJUST while keeping VIEW invalidates delayed detail, and fresh grants work", async () => {
  const f = await draft(), c = f.client, gate = deferred();
  f.gates.set("GET " + base + "/31", gate);
  const delayed = c.open(31);
  f.setUser(viewer()); c.invalidate(); gate.resolve();
  await assert.rejects(delayed, error => error.code === "STALE_STOCKTAKE");
  assert.equal(c.state.current, null);
  f.gates.clear();
  await c.initialize(); await c.open(31);
  assert.equal(c.canEdit(), true);
  assert.equal(c.canComplete(), false);
  f.setUser({ roles: ["ROLE_VIEWER"], permissions: ["WAREHOUSE_ADJUST"] });
  c.invalidate(); await c.initialize(); await c.open(31);
  assert.equal(c.canComplete(), true);
});

