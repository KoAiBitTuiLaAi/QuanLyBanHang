/** S5-08: server-backed stock counts. No inventory snapshots or codes are generated here. */
(function (root, factory) {
  if (typeof module === "object" && module.exports) module.exports = factory();
  else root.WarehouseStocktake = factory();
})(typeof self !== "undefined" ? self : this, function () {
  "use strict";
  const BASE = "/warehouse/stock-counts";

  function hasPermission(user, permission) {
    return Boolean(user?.roles?.includes("ROLE_ADMIN") || user?.permissions?.includes(permission));
  }
  function canUserConfirmStocktake(user) { return hasPermission(user, "WAREHOUSE_ADJUST"); }
  function canUserReadStocktake(user) {
    return hasPermission(user, "WAREHOUSE_VIEW") || canUserConfirmStocktake(user);
  }
  function quantity(value) {
    if (value == null || (typeof value === "string" && !value.trim())) return null;
    if (typeof value !== "number" && typeof value !== "string") return NaN;
    return Number(value);
  }
  function calculateDifference(actualQty, bookQty) {
    const actual = quantity(actualQty), book = quantity(bookQty);
    if (actual === null) return { diff: null, status: "UNCOUNTED", label: "Chưa đếm" };
    if (!Number.isFinite(actual) || actual < 0 || book === null || !Number.isFinite(book)) {
      return { diff: null, status: "INVALID", label: "Không hợp lệ" };
    }
    const diff = Number((actual - book).toFixed(4));
    return { diff, status: diff === 0 ? "MATCH" : diff > 0 ? "SURPLUS" : "DEFICIT", label: diff > 0 ? `+${diff}` : String(diff) };
  }
  function calculateMetrics(items) {
    const metrics = { total: 0, match: 0, surplus: 0, deficit: 0, uncounted: 0, invalid: 0 };
    for (const item of items || []) {
      metrics.total++;
      metrics[calculateDifference(item.actualQuantity, item.bookQuantity).status.toLowerCase()]++;
    }
    return metrics;
  }
  function validateText(value, label) {
    if (typeof value !== "string" || value.length > 500) throw new Error(`${label} tối đa 500 ký tự.`);
    return value;
  }
  function buildItemsPayload(stocktake, requireCounted = false) {
    if (stocktake?.status !== "DRAFT" || stocktake.canEdit !== true) throw new Error("Chỉ được sửa phiếu nháp có quyền kiểm đếm.");
    if (!stocktake.items?.length) throw new Error("Phiếu kiểm kê không có mặt hàng nào.");
    return stocktake.items.map(item => {
      const actual = quantity(item.actualQuantity);
      if ((requireCounted && actual === null) || (actual !== null && (!Number.isFinite(actual) || actual < 0))) {
        throw new Error(`Số lượng thực tế của mặt hàng "${item.productName || item.productCode}" phải được nhập và >= 0.`);
      }
      if (actual !== null && (actual > 99999999999.9999 || Number(actual.toFixed(4)) !== actual)) {
        throw new Error("Số thực đếm chỉ được có tối đa 11 chữ số nguyên và 4 chữ số thập phân.");
      }
      return { productId: item.productId, actualQuantity: actual, note: validateText(item.note ?? "", "Ghi chú") };
    });
  }
  function buildCompletionPayload({ stocktake, reason }) {
    const cleanReason = validateText(reason ?? "", "Lý do").trim();
    if (!cleanReason) throw new Error("Vui lòng nhập lý do kiểm kê & điều chỉnh tồn kho (bắt buộc khi chốt phiếu).");
    return { reason: cleanReason, items: buildItemsPayload(stocktake, true) };
  }
  function validateStocktakeConfirmation({ stocktake, reason, user }) {
    try {
      if (!canUserConfirmStocktake(user) || stocktake?.canAdjust !== true) throw new Error("Bạn cần quyền WAREHOUSE_ADJUST để chốt phiếu kiểm kê.");
      buildCompletionPayload({ stocktake, reason });
      return { valid: true };
    } catch (error) { return { valid: false, error: error.message }; }
  }

  // The epoch also protects against a request resolving after permission revocation.
  function createClient({ request, getUser, onChange = () => {} }) {
    const state = {
      options: null, warehouseId: null, current: null, history: [], page: 0, totalPages: 0,
      totalElements: 0, pending: false, ready: false, dirty: false, reason: "", cancelReason: "",
    };
    let epoch = 0, active = null, savedNote = "";
    function clearCurrent() { state.current = null; state.dirty = false; state.reason = ""; state.cancelReason = ""; savedNote = ""; }
    function invalidate() {
      epoch++;
      state.options = null; state.warehouseId = null; state.history = [];
      state.page = 0; state.totalPages = 0; state.totalElements = 0; state.ready = false;
      clearCurrent(); onChange();
    }
    function requireRead() {
      if (!canUserReadStocktake(getUser())) throw new Error("Bạn không có quyền kiểm kê kho.");
    }
    function requireEditable() {
      requireRead();
      if (state.current?.status !== "DRAFT" || state.current.canEdit !== true) throw new Error("Chỉ được sửa phiếu nháp có quyền kiểm đếm.");
    }
    async function run(task) {
      requireRead();
      if (state.pending) throw new Error("Đang xử lý phiếu kiểm kê. Vui lòng chờ.");
      const token = epoch;
      state.pending = true; onChange();
      function checkCurrentPermission() {
        if (token !== epoch || !canUserReadStocktake(getUser())) {
          const error = new Error("Quyền kiểm kê đã thay đổi. Vui lòng tải lại dữ liệu.");
          error.code = "STALE_STOCKTAKE";
          throw error;
        }
      }
      const checkedRequest = async (path, options) => {
        checkCurrentPermission();
        const data = await request(path, options);
        checkCurrentPermission();
        return data;
      };
      active = (async () => {
        try { return await task(checkedRequest); }
        finally { state.pending = false; active = null; onChange(); }
      })();
      return active;
    }
    function acceptDetail(data) {
      if (!data?.id || !Array.isArray(data.items)) throw new Error("Chi tiết phiếu kiểm kê từ máy chủ không hợp lệ. Hãy tải lại lịch sử.");
      state.current = data; state.dirty = false; state.reason = data.adjustmentReason || "";
      state.cancelReason = data.cancelReason || "";
      savedNote = data.note ?? "";
    }
    async function loadHistory(api, page = state.page) {
      const query = new URLSearchParams({ warehouseId: String(state.warehouseId), page: String(page), size: "20" });
      const result = await api(`${BASE}?${query}`);
      if (!Array.isArray(result?.content)) throw new Error("Danh sách phiếu kiểm kê từ máy chủ không hợp lệ.");
      state.history = result.content; state.page = result.number ?? page;
      state.totalPages = result.totalPages; state.totalElements = result.totalElements;
    }
    function json(method, body) { return { method, body: JSON.stringify(body) }; }
    return {
      state, invalidate,
      idle: () => active ? active.catch(() => {}) : Promise.resolve(),
      canRead: () => canUserReadStocktake(getUser()),
      canEdit: () => canUserReadStocktake(getUser()) && state.current?.status === "DRAFT" && state.current.canEdit === true,
      canComplete: () => canUserConfirmStocktake(getUser()) && state.options?.canAdjust === true
        && state.current?.status === "DRAFT" && state.current.canEdit === true && state.current.canAdjust === true,
      initialize(preferredWarehouse) {
        return run(async api => {
          const options = await api(`${BASE}/options`);
          state.options = options;
          state.warehouseId = options.warehouses.find(w => String(w.id) === String(preferredWarehouse))?.id
            ?? options.warehouses[0]?.id ?? null;
          if (state.current && String(state.current.warehouseId) !== String(state.warehouseId)) clearCurrent();
          state.ready = true;
          if (state.warehouseId !== null) await loadHistory(api, 0);
        });
      },
      selectWarehouse(id) {
        return run(async api => {
          const warehouse = state.options?.warehouses.find(w => String(w.id) === String(id));
          if (!warehouse) throw new Error("Vui lòng chọn kho từ danh sách được cấp quyền.");
          if (String(state.warehouseId) !== String(warehouse.id)) {
            clearCurrent(); state.history = []; state.page = 0; state.totalPages = 0; state.totalElements = 0;
            state.warehouseId = warehouse.id;
          }
          await loadHistory(api, 0);
        });
      },
      history(page = state.page) { return run(api => loadHistory(api, page)); },
      open(id) { return run(async api => { acceptDetail(await api(`${BASE}/${encodeURIComponent(id)}`)); }); },
      create(categoryId) {
        return run(async api => {
          const warehouse = state.options?.warehouses.find(w => String(w.id) === String(state.warehouseId));
          if (!warehouse) throw new Error("Vui lòng chọn kho kiểm kê.");
          const body = { warehouseId: warehouse.id };
          if (categoryId !== "" && categoryId != null && categoryId !== "ALL") {
            const category = state.options.categories.find(c => String(c.id) === String(categoryId));
            if (!category) throw new Error("Nhóm hàng không hợp lệ. Vui lòng tải lại tùy chọn.");
            body.categoryId = category.id;
          }
          acceptDetail(await api(BASE, json("POST", body)));
        });
      },
      save() {
        return run(async api => {
          requireEditable();
          const reason = state.reason, cancelReason = state.cancelReason;
          const body = { items: buildItemsPayload(state.current), note: validateText(state.current.note ?? "", "Ghi chú phiếu") };
          acceptDetail(await api(`${BASE}/${state.current.id}/items`, json("PUT", body)));
          state.reason = reason; state.cancelReason = cancelReason;
        });
      },
      complete() {
        return run(async api => {
          requireEditable();
          const validation = validateStocktakeConfirmation({ stocktake: state.current, reason: state.reason, user: getUser() });
          if (!validation.valid || state.options?.canAdjust !== true) throw new Error(validation.error || "Bạn cần quyền WAREHOUSE_ADJUST để chốt phiếu.");
          const payload = buildCompletionPayload({ stocktake: state.current, reason: state.reason });
          const note = validateText(state.current.note ?? "", "Ghi chú phiếu");
          // Complete has no voucher-note field. Persist a changed note without resetting the entered reason/counts.
          if (note !== savedNote) {
            await api(`${BASE}/${state.current.id}/items`, json("PUT", { items: payload.items, note }));
            savedNote = note;
          }
          acceptDetail(await api(`${BASE}/${state.current.id}/complete`, json("POST", payload)));
        });
      },
      cancel() {
        return run(async api => {
          requireEditable();
          const reason = validateText(state.cancelReason, "Lý do hủy").trim();
          acceptDetail(await api(`${BASE}/${state.current.id}/cancel`, json("POST", { reason })));
        });
      },
    };
  }
  return { calculateDifference, calculateMetrics, canUserReadStocktake, canUserConfirmStocktake,
    validateStocktakeConfirmation, buildItemsPayload, buildCompletionPayload, createClient };
});

