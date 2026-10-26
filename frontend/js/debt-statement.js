/**
 * debt-statement.js — Quản lý logic Sổ chi tiết công nợ đại lý (S8-10).
 *
 * Tính năng:
 * - Đối chiếu phát sinh tăng giảm theo thời gian và tính số dư lũy kế.
 * - Lọc theo đại lý và khoảng thời gian (from, to).
 * - Xuất báo cáo dưới định dạng PDF và Excel (.xlsx).
 * - Thực thi phân quyền ma trận DB: Yêu cầu FINANCE_VIEW_DEBT hoặc ROLE_ADMIN.
 * - Phản ứng tức thì khi ma trận quyền thay đổi (auth:permissions-changed).
 * - Tuyệt đối không lưu dữ liệu kinh doanh vào localStorage hay sessionStorage.
 */
(async () => {
  "use strict";

  // Kiểm tra đăng nhập
  if (typeof Auth !== "undefined" && !Auth.requireLogin()) {
    return;
  }

  const $ = id => document.getElementById(id);
  const escapeHtml = DebtStatementUtils.escapeHtml;
  const { formatMoney, formatDateTime, formatDate, validateDateRange, canViewDebtStatement, getSourceTypeInfo, buildExportUrl } = DebtStatementUtils;

  // State bộ nhớ tạm
  const state = {
    dealers: [],
    selectedDealerId: null,
    currentStatement: null,
    loading: false,
    exportingPdf: false,
    exportingExcel: false,
    activeTab: "aging",
    agingRequest: 0,
    agingData: null,
    agingBusy: false,
  };

  const agingEscape = value => String(value ?? "").replace(/[&<>"']/g, char => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[char]);
  const agingMoney = (formatted, value) => {
    if (typeof formatted === "string" && formatted.trim()) return formatted;
    const number = Number(value);
    return Number.isFinite(number) ? new Intl.NumberFormat("vi-VN", { maximumFractionDigits: 2 }).format(number) + " ₫" : "—";
  };

  function setActiveTab(name, focus = false) {
    if (!["aging", "statement"].includes(name)) return;
    state.activeTab = name;
    const isAging = name === "aging";
    const agingTab = $("debt-tab-aging");
    const statementTab = $("debt-tab-statement");
    const agingPanel = $("debt-panel-aging");
    const statementPanel = $("debt-panel-statement");
    if (!agingTab || !statementTab || !agingPanel || !statementPanel) return;
    agingTab.setAttribute("aria-selected", String(isAging));
    statementTab.setAttribute("aria-selected", String(!isAging));
    agingTab.tabIndex = isAging ? 0 : -1;
    statementTab.tabIndex = isAging ? -1 : 0;
    agingPanel.hidden = !isAging;
    statementPanel.hidden = isAging;
    if (focus) (isAging ? agingTab : statementTab).focus();
  }

  function renderAgingLedger(data) {
    const rows = Array.isArray(data?.dealers) ? data.dealers : [];
    const summary = data?.summary || {};
    const cells = rows.map(row => `<tr${row.isOverdue ? ' class="ds-overdue-row"' : ""}>
      <td>${agingEscape(row.dealerName || `Đại lý #${row.dealerId}`)}${row.isOverdue ? '<span class="ds-overdue-badge">Quá hạn</span>' : ""}</td>
      <td>${agingEscape(row.dealerCode || "—")}</td>
      <td>${agingEscape(agingMoney(row.totalReceivableFormatted, row.totalReceivable))}</td>
      <td>${agingEscape(agingMoney(row.totalPaidFormatted, row.totalPaid))}</td>
      <td>${agingEscape(agingMoney(row.remainingDebtFormatted, row.remainingDebt))}</td>
      <td>${agingEscape(agingMoney(row.bucket0To30Formatted, row.bucket0To30))}</td>
      <td>${agingEscape(agingMoney(row.bucket31To60Formatted, row.bucket31To60))}</td>
      <td>${agingEscape(agingMoney(row.bucket61To90Formatted, row.bucket61To90))}</td>
      <td>${agingEscape(agingMoney(row.bucketOver90Formatted, row.bucketOver90))}</td>
    </tr>`);
    $("ds-rows").innerHTML = cells.length
      ? cells.join("")
      : '<tr><td colspan="9" class="ds-empty">Không có đại lý phù hợp với bộ lọc.</td></tr>';
    const totals = {
      "ds-t-recv": [summary.totalReceivableFormatted, summary.totalReceivable],
      "ds-t-paid": [summary.totalPaidFormatted, summary.totalPaid],
      "ds-t-remain": [summary.totalRemainingDebtFormatted, summary.totalRemainingDebt],
      "ds-t-0": [summary.totalBucket0To30Formatted, summary.totalBucket0To30],
      "ds-t-1": [summary.totalBucket31To60Formatted, summary.totalBucket31To60],
      "ds-t-2": [summary.totalBucket61To90Formatted, summary.totalBucket61To90],
      "ds-t-3": [summary.totalBucketOver90Formatted, summary.totalBucketOver90],
    };
    Object.entries(totals).forEach(([id, [formatted, value]]) => { $(id).textContent = agingMoney(formatted, value); });
    $("ds-totals").hidden = false;
    $("ds-summary").textContent = `${rows.length} đại lý · ${Number(summary.overdueDealerCount || 0)} đại lý quá hạn · Ngày chốt ${data.asOfDate || "—"}`;
    $("ds-export").disabled = rows.length === 0;
  }

  async function loadAgingLedger() {
    if (!await syncPermissions()) return;
    const requestId = ++state.agingRequest;
    const progress = $("ds-progress");
    const error = $("ds-error");
    const reload = $("ds-reload");
    const apply = $("ds-apply");
    state.agingBusy = true;
    if (progress) progress.hidden = false;
    if (error) { error.hidden = true; error.textContent = ""; }
    if (reload) reload.disabled = true;
    if (apply) apply.disabled = true;
    $("ds-export").disabled = true;
    try {
      const params = new URLSearchParams();
      const search = $("ds-search").value.trim();
      const asOfDate = $("ds-as-of").value;
      if (search) params.set("search", search);
      if (asOfDate) params.set("asOfDate", asOfDate);
      params.set("overdueOnly", String($("ds-overdue").checked));
      const response = await Auth.apiFetch(`/finance/debt-aging-ledger?${params.toString()}`);
      const json = await response.json().catch(() => null);
      if (!response.ok || !json?.success || !json?.data || !Array.isArray(json.data.dealers)) {
        throw new Error(json?.message || `Không tải được tổng quan công nợ (HTTP ${response.status}).`);
      }
      if (requestId !== state.agingRequest || !await syncPermissions()) return;
      state.agingData = json.data;
      renderAgingLedger(json.data);
    } catch (err) {
      if (requestId === state.agingRequest) {
        state.agingData = null;
        $("ds-totals").hidden = true;
        $("ds-rows").innerHTML = '<tr><td colspan="9" class="ds-empty">Chưa tải được dữ liệu. Hãy thử lại.</td></tr>';
        if (error) { error.textContent = err.message || "Không tải được dữ liệu công nợ."; error.hidden = false; }
      }
    } finally {
      if (requestId === state.agingRequest) {
        state.agingBusy = false;
        if (progress) progress.hidden = true;
        if (reload) reload.disabled = false;
        if (apply) apply.disabled = false;
      }
    }
  }

  async function exportAgingLedger() {
    if (!state.agingData || !state.agingData.dealers?.length || !await syncPermissions()) return;
    const requestId = state.agingRequest;
    const button = $("ds-export");
    button.disabled = true;
    try {
      const params = new URLSearchParams();
      const search = $("ds-search").value.trim();
      const asOfDate = $("ds-as-of").value;
      if (search) params.set("search", search);
      if (asOfDate) params.set("asOfDate", asOfDate);
      params.set("overdueOnly", String($("ds-overdue").checked));
      const response = await Auth.apiFetch(`/finance/debt-aging-ledger/export?${params.toString()}`);
      if (!response.ok) {
        const json = await response.json().catch(() => null);
        throw new Error(json?.message || `Không xuất được Excel (HTTP ${response.status}).`);
      }
      const blob = await response.blob();
      if (requestId !== state.agingRequest || !await syncPermissions()) return;
      const url = URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = url;
      link.download = `So-cong-no-tuoi-no_${$("ds-as-of").value || "hien-tai"}.xlsx`;
      document.body.append(link); link.click(); link.remove();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (err) {
      const error = $("ds-error");
      error.textContent = err.message || "Không xuất được Excel.";
      error.hidden = false;
    } finally {
      button.disabled = !state.agingData?.dealers?.length;
    }
  }

  /**
   * Cập nhật hiển thị theo quyền hạn hiện tại của người dùng
   */
  async function syncPermissions() {
    if (typeof Auth !== "undefined") {
      await Auth.refreshPermissions();
    }
    const currentUser = typeof Auth !== "undefined" ? Auth.getUser() : null;
    const hasPermission = canViewDebtStatement(currentUser);

    const accessNotice = $("statement-access");
    const filterCard = document.querySelector(".statement-filter-card");
    const resultPanel = $("statement-result-panel");
    const initialState = $("statement-initial-state");
    const errorNotice = $("statement-error");
    const tabs = document.querySelector(".debt-tabs");
    const agingPanel = $("debt-panel-aging");
    const statementTabPanel = $("debt-panel-statement");
    const agingWorkspace = $("ds-workspace");

    if (!hasPermission) {
      if (accessNotice) accessNotice.hidden = false;
      if (filterCard) filterCard.hidden = true;
      if (resultPanel) resultPanel.hidden = true;
      if (initialState) initialState.hidden = true;
      if (errorNotice) errorNotice.hidden = true;
      if (tabs) tabs.hidden = true;
      if (agingPanel) agingPanel.hidden = true;
      if (statementTabPanel) statementTabPanel.hidden = true;
      if (agingWorkspace) agingWorkspace.hidden = true;
      state.currentStatement = null;
      state.agingRequest++;
      state.agingData = null;
      state.agingBusy = false;
      return false;
    }

    if (accessNotice) accessNotice.hidden = true;
    if (filterCard) filterCard.hidden = false;
    if (tabs) tabs.hidden = false;
    if (agingWorkspace) agingWorkspace.hidden = false;
    setActiveTab(state.activeTab);
    return true;
  }

  /**
   * Hiển thị thông báo lỗi
   */
  function showError(msg) {
    const errorEl = $("statement-error");
    if (!errorEl) return;
    if (!msg) {
      errorEl.hidden = true;
      errorEl.textContent = "";
    } else {
      errorEl.hidden = false;
      errorEl.textContent = String(msg);
      errorEl.scrollIntoView({ behavior: "smooth", block: "nearest" });
    }
  }

  /**
   * Tải danh sách đại lý (ưu tiên API dealers, fallback sang API debt)
   */
  async function loadDealers() {
    const select = $("filter-dealer");
    if (!select) return;

    select.innerHTML = '<option value="">-- Đang tải danh sách đại lý... --</option>';

    try {
      let dealerList = [];

      // Thử gọi /dealers?size=200 trước
      try {
        const res = await Auth.apiFetch("/dealers?size=200");
        const json = await res.json().catch(() => null);
        if (res.ok && json?.success && json?.data?.content && Array.isArray(json.data.content)) {
          dealerList = json.data.content.map(d => ({
            id: d.id,
            code: d.code || "",
            name: d.name || d.customerName || `Đại lý #${d.id}`,
          }));
        } else if (res.ok && json?.success && Array.isArray(json.data)) {
          dealerList = json.data.map(d => ({
            id: d.id,
            code: d.code || "",
            name: d.name || d.customerName || `Đại lý #${d.id}`,
          }));
        }
      } catch (e) {
        // Fallback
      }

      // Nếu chưa có, fallback sang /finance/debt
      if (!dealerList.length) {
        const res2 = await Auth.apiFetch("/finance/debt");
        const json2 = await res2.json().catch(() => null);
        if (res2.ok && json2?.success && Array.isArray(json2.data)) {
          dealerList = json2.data.map(d => ({
            id: d.dealerId,
            code: d.dealerCode || "",
            name: d.customerName || `Đại lý #${d.dealerId}`,
          }));
        }
      }

      state.dealers = dealerList;

      // Kiểm tra xem người dùng hiện tại có vai trò ROLE_DEALER không
      const user = Auth.getUser();
      const userRoles = Array.isArray(user?.roles) ? user.roles : [];
      const isDealerUser = userRoles.includes("ROLE_DEALER") && !userRoles.includes("ROLE_ADMIN");
      const assignedDealerId = user?.dealerId || user?.dealer?.id;

      if (isDealerUser && assignedDealerId) {
        // Chỉ cho xem chính mình
        state.dealers = state.dealers.filter(d => String(d.id) === String(assignedDealerId));
      }

      select.innerHTML = '<option value="">-- Chọn đại lý đối chiếu --</option>' +
        state.dealers.map(d => {
          const display = d.code ? `[${d.code}] ${d.name}` : d.name;
          return `<option value="${d.id}">${escapeHtml(display)}</option>`;
        }).join("");

      if (isDealerUser && assignedDealerId) {
        select.value = String(assignedDealerId);
        select.disabled = true;
      }
    } catch (err) {
      select.innerHTML = '<option value="">-- Không thể tải danh sách đại lý --</option>';
      showError("Lỗi tải danh mục đại lý: " + (err.message || "Vui lòng thử lại."));
    }
  }

  /**
   * Thiết lập ngày theo các nút chọn nhanh
   */
  function setQuickRange(type) {
    const fromInput = $("filter-from");
    const toInput = $("filter-to");
    if (!fromInput || !toInput) return;

    const now = new Date();
    const pad = n => String(n).padStart(2, "0");
    const formatDateObj = d => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;

    let fromDate, toDate;

    switch (type) {
      case "this-month":
        fromDate = new Date(now.getFullYear(), now.getMonth(), 1);
        toDate = now;
        break;
      case "last-month":
        fromDate = new Date(now.getFullYear(), now.getMonth() - 1, 1);
        toDate = new Date(now.getFullYear(), now.getMonth(), 0);
        break;
      case "last-30-days":
        fromDate = new Date(now.getTime() - 30 * 24 * 60 * 60 * 1000);
        toDate = now;
        break;
      case "this-quarter": {
        const quarterMonth = Math.floor(now.getMonth() / 3) * 3;
        fromDate = new Date(now.getFullYear(), quarterMonth, 1);
        toDate = now;
        break;
      }
      case "this-year":
        fromDate = new Date(now.getFullYear(), 0, 1);
        toDate = now;
        break;
      default:
        return;
    }

    fromInput.value = formatDateObj(fromDate);
    toInput.value = formatDateObj(toDate);

    document.querySelectorAll(".btn-chip").forEach(btn => {
      btn.classList.toggle("btn-chip--active", btn.dataset.range === type);
    });
  }

  /**
   * Tải sổ chi tiết công nợ từ server
   */
  async function loadStatement(dealerId, from, to) {
    showError(null);
    state.loading = true;

    const initialState = $("statement-initial-state");
    const loadingState = $("statement-loading-state");
    const resultPanel = $("statement-result-panel");
    const btnSubmit = $("btn-view-statement");
    const btnExcel = $("btn-export-excel");
    const btnPdf = $("btn-export-pdf");

    if (initialState) initialState.hidden = true;
    if (loadingState) loadingState.hidden = false;
    if (resultPanel) resultPanel.hidden = true;
    if (btnSubmit) btnSubmit.disabled = true;
    if (btnExcel) btnExcel.disabled = true;
    if (btnPdf) btnPdf.disabled = true;

    try {
      const url = `/finance/debt/${encodeURIComponent(dealerId)}/statement?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`;
      const response = await Auth.apiFetch(url);
      const json = await response.json().catch(() => null);

      if (!response.ok || !json?.success || !json?.data) {
        const errorMsg = json?.message || `Lỗi tải sổ chi tiết công nợ (HTTP ${response.status}).`;
        throw new Error(errorMsg);
      }

      state.currentStatement = json.data;
      renderStatement(json.data);

      if (btnExcel) btnExcel.disabled = false;
      if (btnPdf) btnPdf.disabled = false;
    } catch (err) {
      showError(err.message || "Không thể tải sổ chi tiết công nợ.");
      if (initialState) initialState.hidden = false;
    } finally {
      state.loading = false;
      if (loadingState) loadingState.hidden = true;
      if (btnSubmit) btnSubmit.disabled = false;
    }
  }

  /**
   * Hiển thị dữ liệu sổ lên giao diện
   */
  function renderStatement(data) {
    const resultPanel = $("statement-result-panel");
    if (!resultPanel) return;

    // 1. Banner đại lý & kỳ
    const bannerTitle = $("banner-dealer-title");
    const bannerCode = $("banner-dealer-code");
    const bannerPeriod = $("banner-period-badge");

    if (bannerTitle) bannerTitle.textContent = data.dealerName || `Đại lý #${data.dealerId}`;
    if (bannerCode) bannerCode.textContent = data.dealerCode ? `[${data.dealerCode}]` : "";
    if (bannerPeriod) bannerPeriod.textContent = `Kỳ đối chiếu: ${formatDate(data.from)} — ${formatDate(data.to)}`;

    // 2. Metric KPI Cards
    const metricOpening = $("metric-opening");
    const metricIncrease = $("metric-increase");
    const metricDecrease = $("metric-decrease");
    const metricClosing = $("metric-closing");

    if (metricOpening) metricOpening.textContent = formatMoney(data.openingBalance);
    if (metricIncrease) metricIncrease.textContent = formatMoney(data.totalIncrease);
    if (metricDecrease) metricDecrease.textContent = formatMoney(data.totalDecrease);

    if (metricClosing) {
      metricClosing.textContent = formatMoney(data.closingBalance);
      const isCredit = Number(data.closingBalance) < 0;
      metricClosing.classList.toggle("metric-value--credit", isCredit);
    }

    // 3. Table Rows
    const tbody = $("statement-tbody");
    if (!tbody) return;

    let html = "";

    // Hàng 1: Số dư đầu kỳ
    html += `
      <tr class="row-opening">
        <td>—</td>
        <td><span class="badge badge--default">Đầu kỳ</span></td>
        <td><strong>Số dư nợ đầu kỳ</strong> (trước ngày ${formatDate(data.from)})</td>
        <td class="text-right">—</td>
        <td class="text-right">—</td>
        <td class="text-right amount-balance ${Number(data.openingBalance) < 0 ? 'amount-balance--credit' : ''}">
          ${formatMoney(data.openingBalance)}
        </td>
      </tr>
    `;

    const entries = Array.isArray(data.entries) ? data.entries : [];
    if (!entries.length) {
      html += `
        <tr>
          <td colspan="6" class="statement-empty" style="padding: 24px;">
            Không có phát sinh giao dịch nào trong kỳ từ ${formatDate(data.from)} đến ${formatDate(data.to)}.
          </td>
        </tr>
      `;
    } else {
      for (const entry of entries) {
        const typeInfo = getSourceTypeInfo(entry.sourceType);
        const hasIncrease = Number(entry.increase) > 0;
        const hasDecrease = Number(entry.decrease) > 0;
        const isCreditBalance = Number(entry.balance) < 0;

        html += `
          <tr>
            <td>${formatDateTime(entry.occurredAt)}</td>
            <td>
              <span class="badge ${typeInfo.badgeClass}">${typeInfo.label}</span>
              <strong style="margin-left: 6px;">${escapeHtml(entry.documentCode || "—")}</strong>
            </td>
            <td>${escapeHtml(entry.description || "—")}</td>
            <td class="text-right ${hasIncrease ? 'amount-increase' : ''}">
              ${hasIncrease ? formatMoney(entry.increase) : "—"}
            </td>
            <td class="text-right ${hasDecrease ? 'amount-decrease' : ''}">
              ${hasDecrease ? formatMoney(entry.decrease) : "—"}
            </td>
            <td class="text-right amount-balance ${isCreditBalance ? 'amount-balance--credit' : ''}">
              ${formatMoney(entry.balance)}
            </td>
          </tr>
        `;
      }
    }

    // Hàng tổng kết cuối: Tổng phát sinh / Số dư cuối kỳ
    html += `
      <tr class="row-total">
        <td>—</td>
        <td><strong>Tổng cộng</strong></td>
        <td><strong>Tổng phát sinh trong kỳ &amp; Số dư nợ cuối kỳ</strong></td>
        <td class="text-right amount-increase">${formatMoney(data.totalIncrease)}</td>
        <td class="text-right amount-decrease">${formatMoney(data.totalDecrease)}</td>
        <td class="text-right amount-balance ${Number(data.closingBalance) < 0 ? 'amount-balance--credit' : ''}">
          ${formatMoney(data.closingBalance)}
        </td>
      </tr>
    `;

    tbody.innerHTML = html;
    resultPanel.hidden = false;
  }

  /**
   * Xuất file PDF hoặc Excel
   */
  async function exportStatement(format) {
    const isPdf = format.toLowerCase() === "pdf";
    if (isPdf && state.exportingPdf) return;
    if (!isPdf && state.exportingExcel) return;

    if (!state.currentStatement) {
      showError("Chưa có dữ liệu sổ để xuất.");
      return;
    }

    const { dealerId, dealerCode, from, to } = state.currentStatement;
    const btn = isPdf ? $("btn-export-pdf") : $("btn-export-excel");
    const originalText = btn ? btn.textContent : "";

    try {
      if (isPdf) state.exportingPdf = true;
      else state.exportingExcel = true;

      if (btn) {
        btn.disabled = true;
        btn.textContent = isPdf ? "Đang xuất PDF..." : "Đang xuất Excel...";
      }

      const exportUrl = buildExportUrl(dealerId, from, to, format);
      const response = await Auth.apiFetch(exportUrl);

      if (!response.ok) {
        const errorJson = await response.json().catch(() => null);
        throw new Error(errorJson?.message || `Lỗi tải tệp xuất ${format.toUpperCase()} (HTTP ${response.status})`);
      }

      const blob = await response.blob();

      // Lấy filename từ Content-Disposition header hoặc tự tạo
      let filename = "";
      const disposition = response.headers.get("Content-Disposition");
      if (disposition && disposition.includes("filename=")) {
        const match = disposition.match(/filename\*?=(?:UTF-8'')?["']?([^"';]+)["']?/i);
        if (match && match[1]) {
          filename = decodeURIComponent(match[1]);
        }
      }

      if (!filename) {
        const cleanCode = (dealerCode || String(dealerId)).replace(/[^\w.-]/g, "_");
        filename = `So-cong-no_${cleanCode}_${from}_${to}.${format}`;
      }

      // Kích hoạt tải về
      const downloadUrl = window.URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.style.display = "none";
      a.href = downloadUrl;
      a.download = filename;
      document.body.appendChild(a);
      a.click();
      setTimeout(() => {
        document.body.removeChild(a);
        window.URL.revokeObjectURL(downloadUrl);
      }, 200);

    } catch (err) {
      showError("Lỗi xuất tệp: " + (err.message || "Vui lòng thử lại."));
    } finally {
      if (isPdf) state.exportingPdf = false;
      else state.exportingExcel = false;

      if (btn) {
        btn.disabled = false;
        btn.textContent = originalText;
      }
    }
  }

  /**
   * Khởi tạo trang
   */
  async function init() {
    const hasPerm = await syncPermissions();
    if (!hasPerm) return;

    const agingDate = $("ds-as-of");
    if (agingDate && !agingDate.value) {
      const today = new Date();
      agingDate.value = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, "0")}-${String(today.getDate()).padStart(2, "0")}`;
    }
    $("debt-tab-aging")?.addEventListener("click", () => setActiveTab("aging"));
    $("debt-tab-statement")?.addEventListener("click", () => setActiveTab("statement"));
    document.querySelector(".debt-tabs")?.addEventListener("keydown", event => {
      if (event.key === "ArrowRight" || event.key === "ArrowLeft") {
        event.preventDefault();
        setActiveTab(state.activeTab === "aging" ? "statement" : "aging", true);
      }
    });
    $("ds-filters")?.addEventListener("submit", event => { event.preventDefault(); state.agingData = null; loadAgingLedger(); });
    $("ds-reload")?.addEventListener("click", () => { state.agingData = null; loadAgingLedger(); });
    $("ds-export")?.addEventListener("click", exportAgingLedger);
    ["ds-search", "ds-as-of", "ds-overdue"].forEach(id => $(id)?.addEventListener("input", () => {
      state.agingRequest++;
      state.agingBusy = false;
      state.agingData = null;
      $("ds-progress").hidden = true;
      $("ds-reload").disabled = false;
      $("ds-apply").disabled = false;
      $("ds-export").disabled = true;
      $("ds-summary").textContent = "Bộ lọc đã thay đổi. Hãy áp dụng để tải số liệu mới.";
    }));

    // Gắn giá trị ngày mặc định
    const defaultRange = DebtStatementUtils.getDefaultDateRange();
    const fromInput = $("filter-from");
    const toInput = $("filter-to");
    if (fromInput) fromInput.value = defaultRange.from;
    if (toInput) toInput.value = defaultRange.to;

    // Đánh dấu nút chip "Tháng này" là active
    const btnThisMonth = document.querySelector('.btn-chip[data-range="this-month"]');
    if (btnThisMonth) btnThisMonth.classList.add("btn-chip--active");

    // Tải danh sách đại lý
    await loadDealers();

    // Đọc URL query params
    const params = new URLSearchParams(window.location.search);
    const paramDealer = params.get("dealerId");
    const paramFrom = params.get("from");
    const paramTo = params.get("to");

    if (paramFrom && fromInput) fromInput.value = paramFrom;
    if (paramTo && toInput) toInput.value = paramTo;

    if (paramDealer) {
      const select = $("filter-dealer");
      if (select) {
        select.value = paramDealer;
        // Nếu có dealerId trong query, tự động tải
        const validation = validateDateRange(fromInput.value, toInput.value);
        if (validation.valid) {
          loadStatement(paramDealer, fromInput.value, toInput.value);
        }
      }
    }

    // Sự kiện Quick ranges
    document.querySelectorAll(".btn-chip").forEach(btn => {
      btn.addEventListener("click", () => {
        setQuickRange(btn.dataset.range);
      });
    });

    // Sự kiện submit bộ lọc
    const filterForm = $("statement-filter-form");
    if (filterForm) {
      filterForm.addEventListener("submit", (e) => {
        e.preventDefault();
        const dealerId = $("filter-dealer")?.value;
        const from = $("filter-from")?.value;
        const to = $("filter-to")?.value;

        if (!dealerId) {
          showError("Vui lòng chọn đại lý cần xem sổ công nợ.");
          return;
        }

        const validation = validateDateRange(from, to);
        if (!validation.valid) {
          showError(validation.error);
          return;
        }

        loadStatement(dealerId, from, to);
      });
    }

    // Sự kiện Export Excel
    const btnExcel = $("btn-export-excel");
    if (btnExcel) {
      btnExcel.addEventListener("click", () => exportStatement("xlsx"));
    }

    // Sự kiện Export PDF
    const btnPdf = $("btn-export-pdf");
    if (btnPdf) {
      btnPdf.addEventListener("click", () => exportStatement("pdf"));
    }

    // Lắng nghe thay đổi ma trận quyền tức thì
    window.addEventListener("auth:permissions-changed", async () => {
      state.agingRequest++;
      state.agingData = null;
      state.agingBusy = false;
      const allowed = await syncPermissions();
      if (allowed && state.activeTab === "aging") loadAgingLedger();
    });
    loadAgingLedger();
  }

  // Chạy khởi tạo
  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();

