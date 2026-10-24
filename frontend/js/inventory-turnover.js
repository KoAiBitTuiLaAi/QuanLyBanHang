(function () {
  "use strict";

  const Utils = window.InventoryTurnoverUtils;
  if (!Utils) {
    console.error("InventoryTurnoverUtils is not loaded.");
    return;
  }

  // State cuc bo trong bo nho phien lam viec (khong luu tru trinh duyet)
  const state = {
    options: null,
    reportData: null,
    activeTab: "warehouse",
    skuPage: 1,
    skuPageSize: 20,
    filters: {
      warehouseId: "",
      categoryId: "",
      fromDate: "",
      toDate: "",
      thresholdDays: 90,
      turnoverStatus: "",
      slowMovingOnly: false,
      keyword: "",
    },
  };

  function $(id) {
    return document.getElementById(id);
  }

  function escapeHtml(str) {
    if (str == null) return "";
    return String(str)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;")
      .replace(/'/g, "&#039;");
  }

  function showError(msg) {
    const el = $("it-error");
    if (!el) return;
    if (msg) {
      el.textContent = msg;
      el.hidden = false;
    } else {
      el.textContent = "";
      el.hidden = true;
    }
  }

  function showSuccess(msg) {
    const el = $("it-success");
    if (!el) return;
    if (msg) {
      el.textContent = msg;
      el.hidden = false;
      setTimeout(() => {
        el.hidden = true;
      }, 5000);
    } else {
      el.textContent = "";
      el.hidden = true;
    }
  }

  function checkPermissions() {
    const allowed = Utils.canViewInventoryReport(Auth);
    const accessDeniedEl = $("it-access-denied");
    const workspaceEl = $("it-workspace");
    const exportBtn = $("it-export");

    if (!allowed) {
      if (accessDeniedEl) accessDeniedEl.hidden = false;
      if (workspaceEl) workspaceEl.hidden = true;
      if (exportBtn) exportBtn.disabled = true;
      return false;
    }

    if (accessDeniedEl) accessDeniedEl.hidden = true;
    if (workspaceEl) workspaceEl.hidden = false;
    if (exportBtn) exportBtn.disabled = false;
    return true;
  }

  async function loadOptions() {
    try {
      const res = await Auth.apiFetch("/reports/inventory-turnover/options");
      if (res.ok) {
        const body = await res.json();
        state.options = body.data || {};

        populateFilterOptions();
      }
    } catch (e) {
      console.warn("Không tải được tùy chọn báo cáo:", e);
    }
  }

  function populateFilterOptions() {
    if (!state.options) return;

    // Kho hàng
    const whSelect = $("it-filter-warehouse");
    if (whSelect && Array.isArray(state.options.warehouses)) {
      whSelect.innerHTML = '<option value="">Tất cả kho được phân quyền</option>';
      state.options.warehouses.forEach((w) => {
        const opt = document.createElement("option");
        opt.value = w.id;
        opt.textContent = `${w.code} - ${w.name}`;
        whSelect.appendChild(opt);
      });
    }

    // Nhóm hàng
    const catSelect = $("it-filter-category");
    if (catSelect && Array.isArray(state.options.categories)) {
      catSelect.innerHTML = '<option value="">Tất cả nhóm hàng</option>';
      state.options.categories.forEach((c) => {
        const opt = document.createElement("option");
        opt.value = c.id;
        opt.textContent = `${c.code} - ${c.name}`;
        catSelect.appendChild(opt);
      });
    }

    // Ngày mặc định: 90 ngày trước tới hôm nay
    const today = new Date();
    const ninetyDaysAgo = new Date();
    ninetyDaysAgo.setDate(today.getDate() - 90);

    const formatDateIso = (d) => d.toISOString().slice(0, 10);

    if ($("it-filter-from") && !$("it-filter-from").value) {
      $("it-filter-from").value = formatDateIso(ninetyDaysAgo);
    }
    if ($("it-filter-to") && !$("it-filter-to").value) {
      $("it-filter-to").value = formatDateIso(today);
    }

    if (state.options.defaultSlowMovingThresholdDays && $("it-filter-threshold")) {
      $("it-filter-threshold").value = state.options.defaultSlowMovingThresholdDays;
    }
  }

  function buildQueryParams() {
    const q = new URLSearchParams();

    const wh = $("it-filter-warehouse")?.value;
    if (wh) q.set("warehouseId", wh);

    const cat = $("it-filter-category")?.value;
    if (cat) q.set("categoryId", cat);

    const fromDate = $("it-filter-from")?.value;
    if (fromDate) q.set("fromDate", fromDate);

    const toDate = $("it-filter-to")?.value;
    if (toDate) q.set("toDate", toDate);

    const th = $("it-filter-threshold")?.value;
    if (th) q.set("slowMovingThresholdDays", th);

    const status = $("it-filter-status")?.value;
    if (status) q.set("turnoverStatus", status);

    const slowOnly = $("it-filter-slow-moving")?.checked;
    if (slowOnly) q.set("slowMovingOnly", "true");

    const kw = $("it-filter-keyword")?.value?.trim();
    if (kw) q.set("keyword", kw);

    return q;
  }

  async function loadReport() {
    showError("");

    const fromDate = $("it-filter-from")?.value;
    const toDate = $("it-filter-to")?.value;
    const dateVal = Utils.validateDateRange(fromDate, toDate);
    if (!dateVal.valid) {
      showError(dateVal.error);
      return;
    }

    const query = buildQueryParams();

    try {
      const res = await Auth.apiFetch(`/reports/inventory-turnover?${query.toString()}`);
      if (!res.ok) {
        throw new Error("Không thể tải báo cáo tồn kho và vòng quay (mã " + res.status + ")");
      }

      const body = await res.json();
      state.reportData = body.data || {};
      state.skuPage = 1;

      renderAll();
    } catch (e) {
      showError(e.message || "Lỗi tải dữ liệu báo cáo");
    }
  }

  function renderAll() {
    if (!state.reportData) return;

    renderMetrics();
    renderWarehouses();
    renderCategories();
    renderMatrix();
    renderSlowMoving();
    renderSkus();

    if ($("it-report-time") && state.reportData.generatedAt) {
      const timeStr = new Date(state.reportData.generatedAt).toLocaleString("vi-VN");
      $("it-report-time").textContent = `Dữ liệu tính đến: ${timeStr}`;
    }
  }

  function renderMetrics() {
    const data = state.reportData;

    $("it-total-inventory-value").textContent = Utils.formatMoney(data.totalInventoryValue);
    $("it-total-qty-sub").textContent = `Tồn thực tế: ${Utils.formatQuantity(data.totalPhysicalQuantity)}`;

    $("it-tied-up-value").textContent = Utils.formatMoney(data.slowMovingInventoryValue);
    $("it-tied-up-percent-sub").textContent = `Tỷ lệ chôn vốn: ${Utils.formatPercent(data.tiedUpCapitalRatioPercent)}`;

    $("it-slow-moving-skus").textContent = (data.slowMovingSkuCount || 0) + (data.deadStockSkuCount ? ` (+${data.deadStockSkuCount} chết kho)` : "");
    $("it-total-skus-sub").textContent = `Tổng số: ${data.totalSkus || 0} SKU`;

    $("it-avg-days").textContent = `${Utils.formatDays(data.overallAverageDaysInInventory)} ngày`;
    $("it-period-days-sub").textContent = data.periodDays || 90;

    $("it-turnover-rate").textContent = `${Utils.formatTurnoverRate(data.overallTurnoverRate)} vòng`;
    $("it-annual-turnover-sub").textContent = `Quy đổi năm: ${Utils.formatTurnoverRate(data.overallAnnualizedTurnoverRate)} vòng/năm`;
  }

  function renderWarehouses() {
    const tbody = $("it-warehouse-rows");
    if (!tbody) return;

    const list = state.reportData.warehouseValuations || [];
    if (list.length === 0) {
      tbody.innerHTML = '<tr><td colspan="8" class="it-muted" style="text-align: center; padding: 20px;">Không có dữ liệu kho.</td></tr>';
      return;
    }

    let html = "";
    list.forEach((w) => {
      html += `<tr>
        <td><strong>${escapeHtml(w.warehouseCode)}</strong></td>
        <td>${escapeHtml(w.warehouseName)}</td>
        <td class="it-num">${escapeHtml(Utils.formatQuantity(w.physicalQuantity))}</td>
        <td class="it-num">${escapeHtml(Utils.formatQuantity(w.availableQuantity))}</td>
        <td class="it-num" style="font-weight: 600;">${escapeHtml(Utils.formatMoney(w.inventoryValue))}</td>
        <td class="it-num">${escapeHtml(Utils.formatPercent(w.valueSharePercent))}</td>
        <td class="it-num" style="color: var(--danger); font-weight: 600;">${escapeHtml(Utils.formatMoney(w.slowMovingValue))}</td>
        <td class="it-num">${escapeHtml(Utils.formatTurnoverRate(w.turnoverRate))}</td>
      </tr>`;
    });

    tbody.innerHTML = html;
  }

  function renderCategories() {
    const tbody = $("it-category-rows");
    if (!tbody) return;

    const list = state.reportData.categoryValuations || [];
    if (list.length === 0) {
      tbody.innerHTML = '<tr><td colspan="8" class="it-muted" style="text-align: center; padding: 20px;">Không có dữ liệu nhóm hàng.</td></tr>';
      return;
    }

    let html = "";
    list.forEach((c) => {
      html += `<tr>
        <td><strong>${escapeHtml(c.categoryCode)}</strong></td>
        <td>${escapeHtml(c.categoryName)}</td>
        <td class="it-num">${escapeHtml(Utils.formatQuantity(c.physicalQuantity))}</td>
        <td class="it-num" style="font-weight: 600;">${escapeHtml(Utils.formatMoney(c.inventoryValue))}</td>
        <td class="it-num">${escapeHtml(Utils.formatPercent(c.valueSharePercent))}</td>
        <td class="it-num" style="color: var(--danger); font-weight: 600;">${escapeHtml(Utils.formatMoney(c.slowMovingValue))}</td>
        <td class="it-num">${escapeHtml(c.slowMovingSkuCount || 0)}</td>
        <td class="it-num">${escapeHtml(Utils.formatTurnoverRate(c.turnoverRate))}</td>
      </tr>`;
    });

    tbody.innerHTML = html;
  }

  function renderMatrix() {
    const tbody = $("it-matrix-rows");
    if (!tbody) return;

    const list = state.reportData.warehouseCategoryValuations || [];
    if (list.length === 0) {
      tbody.innerHTML = '<tr><td colspan="6" class="it-muted" style="text-align: center; padding: 20px;">Không có dữ liệu ma trận kho - nhóm hàng.</td></tr>';
      return;
    }

    let html = "";
    list.forEach((m) => {
      html += `<tr>
        <td>${escapeHtml(m.warehouseName || m.warehouseCode)}</td>
        <td>${escapeHtml(m.categoryName || m.categoryCode)}</td>
        <td class="it-num">${escapeHtml(Utils.formatQuantity(m.physicalQuantity))}</td>
        <td class="it-num" style="font-weight: 600;">${escapeHtml(Utils.formatMoney(m.inventoryValue))}</td>
        <td class="it-num">${escapeHtml(Utils.formatPercent(m.valueShareInWarehousePercent))}</td>
        <td class="it-num" style="color: var(--danger); font-weight: 600;">${escapeHtml(Utils.formatMoney(m.slowMovingValue))}</td>
      </tr>`;
    });

    tbody.innerHTML = html;
  }

  function renderSlowMoving() {
    const tbody = $("it-slow-rows");
    if (!tbody) return;

    const list = state.reportData.slowMovingItems || [];
    if (list.length === 0) {
      tbody.innerHTML = '<tr><td colspan="9" class="it-muted" style="text-align: center; padding: 20px;">Không có mặt hàng nào bị cảnh báo chậm luân chuyển hoặc chôn vốn.</td></tr>';
      return;
    }

    let html = "";
    list.forEach((s) => {
      const statusInfo = Utils.getTurnoverStatus(s.turnoverStatus);
      const cardUrl = Utils.getStockCardUrl(s.productId, s.warehouseId);

      html += `<tr>
        <td><strong>${escapeHtml(s.productCode)}</strong></td>
        <td>${escapeHtml(s.productName)}</td>
        <td>${escapeHtml(s.categoryName || "—")}</td>
        <td class="it-num">${escapeHtml(Utils.formatQuantity(s.closingQuantity))} ${escapeHtml(s.baseUnit || "")}</td>
        <td class="it-num" style="color: var(--danger); font-weight: 700;">${escapeHtml(Utils.formatMoney(s.tiedUpCapitalValue))}</td>
        <td class="it-num" style="font-weight: 600;">${escapeHtml(Utils.formatDays(s.averageDaysInInventory))} ngày</td>
        <td><span class="it-badge ${statusInfo.badgeClass}">${escapeHtml(statusInfo.label)}</span></td>
        <td><small>${escapeHtml(s.slowMovingReason || "Vượt ngưỡng tồn bình quân không xuất")}</small></td>
        <td>
          <a href="${escapeHtml(cardUrl)}" class="btn btn--secondary" target="_blank" style="padding: 4px 10px; font-size: .78rem; text-decoration: none; display: inline-flex; align-items: center;">Xem thẻ kho</a>
        </td>
      </tr>`;
    });

    tbody.innerHTML = html;
  }

  function renderSkus() {
    const tbody = $("it-sku-rows");
    if (!tbody) return;

    const list = state.reportData.skuItems || [];
    const total = list.length;

    if (total === 0) {
      tbody.innerHTML = '<tr><td colspan="13" class="it-muted" style="text-align: center; padding: 20px;">Không có dữ liệu SKU phù hợp.</td></tr>';
      renderSkuPagination(0);
      return;
    }

    const start = (state.skuPage - 1) * state.skuPageSize;
    const end = Math.min(start + state.skuPageSize, total);
    const paged = list.slice(start, end);

    let html = "";
    paged.forEach((sku) => {
      const statusInfo = Utils.getTurnoverStatus(sku.turnoverStatus);
      const cardUrl = Utils.getStockCardUrl(sku.productId, sku.warehouseId);

      html += `<tr>
        <td><strong>${escapeHtml(sku.productCode)}</strong></td>
        <td>${escapeHtml(sku.productName)}</td>
        <td>${escapeHtml(sku.categoryName || "—")}</td>
        <td>${escapeHtml(sku.baseUnit || "")}</td>
        <td class="it-num">${escapeHtml(Utils.formatQuantity(sku.openingQuantity))}</td>
        <td class="it-num">${escapeHtml(Utils.formatQuantity(sku.outboundQuantity))}</td>
        <td class="it-num" style="font-weight: 600;">${escapeHtml(Utils.formatQuantity(sku.closingQuantity))}</td>
        <td class="it-num">${escapeHtml(Utils.formatMoney(sku.inventoryValue))}</td>
        <td class="it-num" style="font-weight: 600;">${escapeHtml(Utils.formatDays(sku.averageDaysInInventory))}</td>
        <td class="it-num">${escapeHtml(Utils.formatTurnoverRate(sku.turnoverRate))}</td>
        <td><span class="it-badge ${statusInfo.badgeClass}">${escapeHtml(statusInfo.label)}</span></td>
        <td class="it-num" style="color: ${sku.slowMoving ? "var(--danger)" : "var(--ink-600)"}; font-weight: ${sku.slowMoving ? "700" : "normal"};">
          ${escapeHtml(Utils.formatMoney(sku.tiedUpCapitalValue))}
        </td>
        <td>
          <a href="${escapeHtml(cardUrl)}" class="btn btn--secondary" target="_blank" style="padding: 4px 10px; font-size: .78rem; text-decoration: none; display: inline-flex; align-items: center;">Xem thẻ kho</a>
        </td>
      </tr>`;
    });

    tbody.innerHTML = html;
    renderSkuPagination(total);
  }

  function renderSkuPagination(total) {
    const totalPages = Math.ceil(total / state.skuPageSize) || 1;
    const pageEl = $("it-sku-page");
    const prevBtn = $("it-sku-prev");
    const nextBtn = $("it-sku-next");

    if (pageEl) {
      pageEl.textContent = `Trang ${state.skuPage} / ${totalPages} (${total} SKU)`;
    }
    if (prevBtn) {
      prevBtn.disabled = state.skuPage <= 1;
    }
    if (nextBtn) {
      nextBtn.disabled = state.skuPage >= totalPages;
    }
  }

  function switchTab(tabId) {
    state.activeTab = tabId;

    const tabs = ["warehouse", "category", "matrix", "slow-moving", "skus"];
    tabs.forEach((t) => {
      const btn = $(`tab-${t}`);
      const panel = $(`panel-${t}`);
      if (btn && panel) {
        const isActive = t === tabId;
        btn.classList.toggle("it-tab-btn--active", isActive);
        btn.setAttribute("aria-selected", isActive ? "true" : "false");
        panel.hidden = !isActive;
      }
    });
  }

  async function exportExcel() {
    showError("");
    const exportBtn = $("it-export");
    if (exportBtn) {
      exportBtn.disabled = true;
      exportBtn.textContent = "Đang xuất Excel...";
    }

    try {
      const query = buildQueryParams();
      const res = await Auth.apiFetch(`/reports/inventory-turnover/export?${query.toString()}`);
      if (!res.ok) {
        throw new Error("Lỗi máy chủ khi tạo file Excel (mã " + res.status + ")");
      }

      const blob = await res.blob();
      const downloadUrl = window.URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = downloadUrl;

      // Đặt tên file theo ngày và bộ lọc
      const dateStr = new Date().toISOString().slice(0, 10);
      a.download = `Bao-cao-ton-kho-vong-quay-${dateStr}.xlsx`;
      document.body.appendChild(a);
      a.click();
      a.remove();
      window.URL.revokeObjectURL(downloadUrl);

      showSuccess("Đã xuất báo cáo Excel thành công.");
    } catch (e) {
      showError(e.message || "Không thể xuất file Excel");
    } finally {
      if (exportBtn) {
        exportBtn.disabled = false;
        exportBtn.textContent = "Xuất Excel";
      }
    }
  }

  async function init() {
    if (!Auth.requireLogin()) return;
    await Auth.ready;

    if (!checkPermissions()) {
      return;
    }

    window.addEventListener("auth:permissions-changed", () => {
      if (checkPermissions()) {
        loadReport();
      }
    });

    const reloadBtn = $("it-reload");
    if (reloadBtn) {
      reloadBtn.addEventListener("click", () => {
        loadReport();
      });
    }

    const exportBtn = $("it-export");
    if (exportBtn) {
      exportBtn.addEventListener("click", exportExcel);
    }

    const filterForm = $("it-filters");
    if (filterForm) {
      filterForm.addEventListener("submit", (e) => {
        e.preventDefault();
        loadReport();
      });
    }

    const resetBtn = $("it-filter-reset");
    if (resetBtn) {
      resetBtn.addEventListener("click", () => {
        filterForm.reset();
        populateFilterOptions();
        loadReport();
      });
    }

    // Tabs
    ["warehouse", "category", "matrix", "slow-moving", "skus"].forEach((t) => {
      const btn = $(`tab-${t}`);
      if (btn) {
        btn.addEventListener("click", () => switchTab(t));
      }
    });

    // Pagination for SKU table
    const skuPrev = $("it-sku-prev");
    const skuNext = $("it-sku-next");
    if (skuPrev) {
      skuPrev.addEventListener("click", () => {
        if (state.skuPage > 1) {
          state.skuPage--;
          renderSkus();
        }
      });
    }
    if (skuNext) {
      skuNext.addEventListener("click", () => {
        const total = (state.reportData?.skuItems || []).length;
        const totalPages = Math.ceil(total / state.skuPageSize);
        if (state.skuPage < totalPages) {
          state.skuPage++;
          renderSkus();
        }
      });
    }

    await loadOptions();
    await loadReport();
  }

  document.addEventListener("DOMContentLoaded", init);
})();

