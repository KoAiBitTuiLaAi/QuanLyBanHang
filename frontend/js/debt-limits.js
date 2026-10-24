/**
 * debt-limits.js — Kiểm soát rủi ro công nợ & Hạn mức (S3-05 & S7-04).
 *
 * Tính năng:
 * - S3-05: Khai báo hạn mức tiền & số ngày nợ tối đa theo đại lý.
 * - S7-04: Hệ thống tự cảnh báo và khoá bán khi đại lý vượt hạn mức hoặc quá hạn:
 *   + AC1: Danh sách đại lý gắn cờ tự động (quá hạn, vượt hạn mức, cả hai) kèm lý do chi tiết.
 *   + AC2: Cấu hình hành vi khoá bán (BLOCK) hoặc chuyển cần duyệt (REQUIRE_APPROVAL) toàn hệ thống và theo từng đại lý.
 *   + AC3: Tác vụ đánh giá lại định kỳ hoặc thủ công; tự động chạy lại đánh giá và gỡ cờ ngay sau khi ghi nhận thu tiền.
 * - Phân quyền:
 *   + FINANCE_VIEW_DEBT: Xem thông tin hạn mức, danh sách đại lý gắn cờ, cấu hình và lịch sử thu tiền.
 *   + FINANCE_MANAGE: Khai báo hạn mức, lưu cấu hình hệ thống, cấu hình hành vi đại lý, đánh giá lại, ghi nhận thu tiền.
 *   + ROLE_ADMIN: Toàn quyền truy cập.
 */
(async () => {
  "use strict";

  const urlParams = new URLSearchParams(location.search);
  if (urlParams.get("view") !== "debt-limits") return;
  if (!Auth.requireLogin()) return;

  await Auth.refreshPermissions();
  const currentUser = Auth.getUser();
  if (!MenuConfig.getMenuForUser(currentUser).some(item => item.id === "debt-limits")) {
    location.href = "/error?status=403";
    return;
  }

  const $ = id => document.getElementById(id);
  const escapeHtml = value => String(value ?? "").replace(/[&<>'"]/g, char => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", "'": "&#39;", '"': "&quot;",
  })[char]);

  const currency = new Intl.NumberFormat("vi-VN", { style: "currency", currency: "VND", maximumFractionDigits: 2 });
  const pageSize = 10;

  // Quyền thao tác
  let canManage = Auth.hasPermission("FINANCE_MANAGE");
  let canView = Auth.hasPermission("FINANCE_VIEW_DEBT");

  // State
  const state = {
    activeTab: urlParams.get("tab") || "alerts", // 'alerts' | 'limits' | 'config' | 'payments'

    // S7-04 Flagged dealers
    flaggedRows: [],
    flaggedLoading: false,
    flaggedError: "",
    flaggedPage: 0,
    selectedDealerForAction: null,

    // S3-05 Limits
    limitRows: [],
    limitPage: 0,
    limitLoading: false,
    limitError: "",
    selectedLimitRow: null,
    pendingDeclaration: null,
    savingLimit: false,

    // S7-04 System Config
    systemConfig: null,
    configLoading: false,
    configSaving: false,

    // S7-04 Payments
    paymentRows: [],
    paymentPage: 0,
    paymentTotalPages: 1,
    paymentLoading: false,
    paymentError: "",
    dealersList: [],
    savingPayment: false,
  };

  document.title = "Kiểm soát rủi ro & Hạn mức công nợ | SaleStock";
  $("welcome-card").hidden = true;
  document.querySelector(".dashboard-work").hidden = true;
  $("debt-limits-view").hidden = false;

  // ──────────────────────── UTILITIES ────────────────────────

  function amountNumber(value) {
    if (value == null || String(value).trim() === "") return null;
    const number = Number(value);
    return Number.isFinite(number) && number >= 0 ? number : null;
  }

  function formatAmount(value) {
    const number = amountNumber(value);
    return number === null ? "Chưa có dữ liệu" : currency.format(number);
  }

  function formatDays(value) {
    const number = Number(value);
    return Number.isInteger(number) && number >= 0
      ? `${number.toLocaleString("vi-VN")} ngày`
      : "Chưa có dữ liệu";
  }

  function formatDate(isoString) {
    if (!isoString) return "—";
    try {
      const date = new Date(isoString);
      return isNaN(date.getTime()) ? String(isoString) : date.toLocaleString("vi-VN");
    } catch {
      return String(isoString);
    }
  }

  function showToast(message, type = "error") {
    let container = $("toast-container");
    if (!container) {
      container = document.createElement("div");
      container.id = "toast-container";
      document.body.appendChild(container);
    }
    const toast = document.createElement("div");
    toast.className = `toast toast--${type}`;
    toast.setAttribute("role", "alert");
    toast.textContent = String(message ?? "");
    container.appendChild(toast);
    requestAnimationFrame(() => toast.classList.add("toast--visible"));
    setTimeout(() => {
      toast.classList.remove("toast--visible");
      toast.classList.add("toast--exit");
      setTimeout(() => toast.remove(), 300);
    }, 4200);
  }

  function syncPermissionsUI() {
    canManage = Auth.hasPermission("FINANCE_MANAGE");
    canView = Auth.hasPermission("FINANCE_VIEW_DEBT");

    // Header buttons
    $("btn-trigger-evaluate-all").hidden = !canManage;
    $("btn-open-payment-modal").hidden = !canManage;
    $("debt-limits-readonly").hidden = canManage;
    $("debt-config-readonly").hidden = canManage;

    // Config form
    const configForm = $("debt-system-config-form");
    if (configForm) {
      configForm.querySelectorAll("input, select").forEach(el => {
        el.disabled = !canManage;
      });
      $("btn-save-debt-config").disabled = !canManage || state.configSaving;
    }

    // Payment new button
    $("btn-new-payment").hidden = !canManage;
  }

  // ──────────────────────── TAB NAVIGATION ────────────────────────

  const tabButtons = {
    alerts: $("tab-btn-alerts"),
    limits: $("tab-btn-limits"),
    config: $("tab-btn-config"),
    payments: $("tab-btn-payments"),
  };

  const tabPanes = {
    alerts: $("debt-pane-alerts"),
    limits: $("debt-pane-limits"),
    config: $("debt-pane-config"),
    payments: $("debt-pane-payments"),
  };

  function switchTab(tabKey) {
    if (!tabButtons[tabKey]) tabKey = "alerts";
    state.activeTab = tabKey;

    Object.keys(tabButtons).forEach(key => {
      const active = key === tabKey;
      tabButtons[key].classList.toggle("debt-tab--active", active);
      tabButtons[key].setAttribute("aria-selected", String(active));
      tabPanes[key].hidden = !active;
    });

    const currentUrl = new URL(location.href);
    currentUrl.searchParams.set("tab", tabKey);
    history.replaceState(null, "", currentUrl.toString());

    if (tabKey === "alerts") loadFlaggedDealers();
    else if (tabKey === "limits") loadLimits();
    else if (tabKey === "config") loadSystemRiskConfig();
    else if (tabKey === "payments") {
      loadPaymentHistory();
      loadDealersList();
    }
  }

  Object.entries(tabButtons).forEach(([key, btn]) => {
    btn.addEventListener("click", () => switchTab(key));
  });

  // ──────────────────────── S7-04: TAB 1 - CẢNH BÁO RỦI RO & KHOÁ BÁN ────────────────────────

  function filteredFlaggedRows() {
    const keyword = ($("debt-alerts-search")?.value || "").trim().toLocaleLowerCase("vi");
    const flagFilter = $("debt-alerts-flag-type")?.value || "all";
    const actionFilter = $("debt-alerts-action-type")?.value || "all";

    return state.flaggedRows.filter(row => {
      const matchKeyword = !keyword || [row.dealerName, row.dealerCode, row.dealerId].some(v =>
        String(v ?? "").toLocaleLowerCase("vi").includes(keyword));

      const type = row.debtFlagType || "NONE";
      const matchFlag = flagFilter === "all" || type === flagFilter;

      const action = row.effectiveAction || "DEFAULT";
      const matchAction = actionFilter === "all" || action === actionFilter;

      return matchKeyword && matchFlag && matchAction;
    });
  }

  function renderFlaggedDealers() {
    const rows = filteredFlaggedRows();
    const totalPages = Math.max(1, Math.ceil(rows.length / pageSize));
    state.flaggedPage = Math.min(state.flaggedPage, totalPages - 1);

    // Update stats
    const totalFlagged = state.flaggedRows.length;
    const blockedCount = state.flaggedRows.filter(r => r.effectiveAction === "BLOCK").length;
    const approvalCount = state.flaggedRows.filter(r => r.effectiveAction === "REQUIRE_APPROVAL").length;
    const overdueTotal = state.flaggedRows.reduce((sum, r) => sum + Number(r.overdueAmount || 0), 0);

    $("stat-flagged-count").textContent = totalFlagged.toLocaleString("vi-VN");
    $("stat-blocked-count").textContent = blockedCount.toLocaleString("vi-VN");
    $("stat-approval-count").textContent = approvalCount.toLocaleString("vi-VN");
    $("stat-overdue-total").textContent = formatAmount(overdueTotal);

    const badge = $("debt-flagged-count-badge");
    badge.textContent = String(totalFlagged);
    badge.hidden = totalFlagged === 0;

    $("debt-alerts-prev").disabled = state.flaggedLoading || !!state.flaggedError || state.flaggedPage === 0;
    $("debt-alerts-next").disabled = state.flaggedLoading || !!state.flaggedError || state.flaggedPage >= totalPages - 1;
    $("debt-alerts-page").textContent = state.flaggedLoading || state.flaggedError ? "—" : `Trang ${state.flaggedPage + 1} / ${totalPages}`;

    const tbody = $("debt-alerts-rows");
    if (state.flaggedLoading || state.flaggedError || !rows.length) {
      const title = state.flaggedLoading
        ? "Đang tải danh sách cảnh báo công nợ…"
        : state.flaggedError
          ? "Không thể tải danh sách đại lý cảnh báo"
          : state.flaggedRows.length
            ? "Không tìm thấy đại lý phù hợp với bộ lọc"
            : "Hiện tại không có đại lý nào bị gắn cờ rủi ro công nợ";

      $("debt-alerts-summary").textContent = state.flaggedLoading
        ? "Đang tải dữ liệu…"
        : state.flaggedError
          ? "Lỗi tải dữ liệu"
          : `0 / ${totalFlagged} đại lý bị gắn cờ`;

      tbody.innerHTML = `<tr><td colspan="8" class="debt-limits__empty">
        <strong>${escapeHtml(title)}</strong>
        ${state.flaggedError ? `<span>${escapeHtml(state.flaggedError)}</span><button class="btn btn--secondary" type="button" id="btn-retry-flagged">Thử tải lại</button>` : ""}
      </td></tr>`;
      return;
    }

    const start = state.flaggedPage * pageSize;
    const end = Math.min(start + pageSize, rows.length);
    $("debt-alerts-summary").textContent = `${start + 1}–${end} / ${rows.length.toLocaleString("vi-VN")} đại lý cảnh báo`;

    tbody.innerHTML = rows.slice(start, end).map(row => {
      const flagType = row.debtFlagType;
      let flagBadgeHtml = "";
      if (flagType === "OVERDUE") {
        flagBadgeHtml = '<span class="debt-badge debt-badge--danger">Quá hạn nợ</span>';
      } else if (flagType === "DEBT_LIMIT_EXCEEDED") {
        flagBadgeHtml = '<span class="debt-badge debt-badge--warning">Vượt hạn mức</span>';
      } else if (flagType === "BOTH") {
        flagBadgeHtml = '<span class="debt-badge debt-badge--danger">Quá hạn &amp; Vượt HM</span>';
      } else {
        flagBadgeHtml = '<span class="debt-badge debt-badge--neutral">Chưa gắn cờ</span>';
      }

      const action = row.effectiveAction;
      let actionBadgeHtml = "";
      if (action === "BLOCK") {
        actionBadgeHtml = '<span class="debt-badge debt-badge--danger">Khoá bán</span>';
      } else if (action === "REQUIRE_APPROVAL") {
        actionBadgeHtml = '<span class="debt-badge debt-badge--warning">Chuyển cần duyệt</span>';
      } else {
        actionBadgeHtml = '<span class="debt-badge debt-badge--neutral">Mặc định</span>';
      }

      const overdueText = Number(row.overdueAmount || 0) > 0
        ? `<strong>${formatAmount(row.overdueAmount)}</strong><br><small style="color:var(--danger)">Quá hạn ${row.maxOverdueDays || 0} ngày</small>`
        : '<span class="debt-limits__missing">0 đ</span>';

      const debtLimitText = `Nợ: ${formatAmount(row.currentDebt)}<br><small style="color:var(--ink-500)">HM: ${formatAmount(row.debtLimit)}</small>`;

      return `<tr>
        <td>
          <span class="debt-limits__name">${escapeHtml(row.dealerName || "Đại lý")}</span>
          <span class="debt-limits__id">${escapeHtml(row.dealerCode || "")} · ID: ${escapeHtml(row.dealerId)}</span>
        </td>
        <td>${flagBadgeHtml}</td>
        <td>${debtLimitText}</td>
        <td>${overdueText}</td>
        <td>${actionBadgeHtml}</td>
        <td style="max-width: 200px; font-size: .8125rem;">${escapeHtml(row.debtFlagReason || "—")}</td>
        <td style="font-size: .75rem; white-space: nowrap;">${formatDate(row.lastDebtEvaluatedAt || row.debtFlaggedAt)}</td>
        <td>
          ${canManage ? `
            <div class="debt-row-actions">
              <button class="btn btn--secondary" type="button" data-action-config="${escapeHtml(row.dealerId)}" title="Cấu hình hành vi riêng">Hành vi</button>
              <button class="btn btn--secondary" type="button" data-evaluate-dealer="${escapeHtml(row.dealerId)}" title="Đánh giá lại ngay">Đánh giá</button>
              <button class="btn btn--primary" type="button" data-pay-dealer="${escapeHtml(row.dealerId)}" title="Ghi nhận thu tiền">Thu tiền</button>
            </div>
          ` : '<span class="debt-limits__missing">Chỉ xem</span>'}
        </td>
      </tr>`;
    }).join("");
  }

  async function loadFlaggedDealers() {
    if (state.flaggedLoading) return;
    state.flaggedLoading = true;
    state.flaggedError = "";
    renderFlaggedDealers();

    try {
      const response = await Auth.apiFetch("/finance/debt/flagged-dealers");
      const result = await response.json().catch(() => null);
      if (!response.ok || !result?.success) {
        throw new Error(result?.message || "Không thể tải danh sách đại lý cảnh báo rủi ro.");
      }
      state.flaggedRows = Array.isArray(result.data) ? result.data : [];
    } catch (error) {
      state.flaggedError = error.message || "Lỗi kết nối máy chủ khi tải danh sách cảnh báo.";
    } finally {
      state.flaggedLoading = false;
      renderFlaggedDealers();
    }
  }

  // ──────────────────────── S7-04: TÁC VỤ ĐÁNH GIÁ LẠI CÔNG NỢ ────────────────────────

  async function evaluateAllDealers() {
    if (!canManage) return;
    const btn = $("btn-trigger-evaluate-all");
    btn.disabled = true;
    const origText = btn.textContent;
    btn.textContent = "Đang quét…";

    try {
      const response = await Auth.apiFetch("/finance/debt/evaluate", { method: "POST" });
      const result = await response.json().catch(() => null);
      if (!response.ok || !result?.success) {
        throw new Error(result?.message || "Không thể thực hiện tác vụ đánh giá lại.");
      }
      const data = result.data;
      showToast(`Đã đánh giá lại toàn bộ đại lý: ${data?.flaggedDealersCount ?? 0} đại lý bị gắn cờ / ${data?.evaluatedDealersCount ?? 0} đại lý.`, "success");
      loadFlaggedDealers();
      loadLimits();
    } catch (error) {
      showToast(error.message || "Lỗi đánh giá lại công nợ", "error");
    } finally {
      btn.disabled = false;
      btn.textContent = origText;
    }
  }

  async function evaluateSingleDealer(dealerId) {
    if (!canManage || !dealerId) return;
    try {
      const response = await Auth.apiFetch(`/finance/debt/${dealerId}/evaluate`, { method: "POST" });
      const result = await response.json().catch(() => null);
      if (!response.ok || !result?.success) {
        throw new Error(result?.message || "Không thể đánh giá lại đại lý.");
      }
      showToast("Đã đánh giá lại công nợ đại lý thành công.", "success");
      loadFlaggedDealers();
      loadLimits();
    } catch (error) {
      showToast(error.message || "Lỗi đánh giá lại công nợ đại lý", "error");
    }
  }

  // ──────────────────────── S7-04: CẤU HÌNH HÀNH VI RIÊNG ĐẠI LÝ ────────────────────────

  const actionModal = $("dealer-action-config-modal");
  const actionForm = $("dealer-action-config-form");

  function openDealerActionModal(row) {
    if (!canManage) return;
    state.selectedDealerForAction = row;
    $("dealer-action-modal-dealer").textContent = `${row.dealerName || "Đại lý"} (${row.dealerCode || ""}) · ID ${row.dealerId}`;
    $("dealer-action-select").value = row.effectiveAction || "DEFAULT";
    $("dealer-action-form-message").hidden = true;
    actionModal.hidden = false;
    document.querySelector(".app-shell").inert = true;
    $("dealer-action-select").focus();
  }

  function closeDealerActionModal() {
    actionModal.hidden = true;
    document.querySelector(".app-shell").inert = false;
    state.selectedDealerForAction = null;
    actionForm.reset();
  }

  actionForm.addEventListener("submit", async event => {
    event.preventDefault();
    if (!canManage || !state.selectedDealerForAction) return;

    const action = $("dealer-action-select").value;
    const saveBtn = $("save-dealer-action");
    saveBtn.disabled = true;
    saveBtn.textContent = "Đang lưu…";
    $("dealer-action-form-message").hidden = true;

    try {
      const response = await Auth.apiFetch(`/finance/debt/${state.selectedDealerForAction.dealerId}/action-config`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ action }),
      });
      const result = await response.json().catch(() => null);
      if (!response.ok || !result?.success) {
        throw new Error(result?.message || "Không thể lưu cấu hình hành vi đại lý.");
      }
      showToast("Đã cập nhật cấu hình hành vi cho đại lý.", "success");
      closeDealerActionModal();
      loadFlaggedDealers();
    } catch (error) {
      $("dealer-action-form-message").textContent = error.message || "Lỗi khi lưu cấu hình.";
      $("dealer-action-form-message").hidden = false;
    } finally {
      saveBtn.disabled = false;
      saveBtn.textContent = "Lưu hành vi";
    }
  });

  actionModal.querySelectorAll("[data-close-dealer-action]").forEach(btn =>
    btn.addEventListener("click", closeDealerActionModal));

  // ──────────────────────── S7-04: TAB 3 - CẤU HÌNH KIỂM SOÁT RỦI RO HỆ THỐNG ────────────────────────

  async function loadSystemRiskConfig() {
    if (state.configLoading) return;
    state.configLoading = true;
    try {
      const response = await Auth.apiFetch("/finance/debt/config");
      const result = await response.json().catch(() => null);
      if (!response.ok || !result?.success || !result.data) {
        throw new Error(result?.message || "Không thể tải cấu hình rủi ro công nợ.");
      }
      state.systemConfig = result.data;
      renderSystemConfig();
    } catch (error) {
      showToast(error.message || "Lỗi tải cấu hình rủi ro.", "error");
    } finally {
      state.configLoading = false;
    }
  }

  function renderSystemConfig() {
    const cfg = state.systemConfig;
    if (!cfg) return;

    $("cfg-auto-flag").checked = Boolean(cfg.autoFlagEnabled);
    $("cfg-limit-action").value = cfg.limitExceededAction || "BLOCK";
    $("cfg-overdue-action").value = cfg.overdueAction || "BLOCK";
    $("cfg-default-action").value = cfg.defaultAction || "BLOCK";

    $("cfg-updated-at").textContent = formatDate(cfg.updatedAt);
    $("cfg-updated-by").textContent = cfg.updatedBy || "Hệ thống";

    $("btn-save-debt-config").disabled = !canManage;
  }

  $("debt-system-config-form").addEventListener("submit", async event => {
    event.preventDefault();
    if (!canManage || state.configSaving) return;

    const payload = {
      autoFlagEnabled: $("cfg-auto-flag").checked,
      limitExceededAction: $("cfg-limit-action").value,
      overdueAction: $("cfg-overdue-action").value,
      defaultAction: $("cfg-default-action").value,
    };

    state.configSaving = true;
    const btn = $("btn-save-debt-config");
    btn.disabled = true;
    btn.textContent = "Đang lưu cấu hình…";

    try {
      const response = await Auth.apiFetch("/finance/debt/config", {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
      });
      const result = await response.json().catch(() => null);
      if (!response.ok || !result?.success) {
        throw new Error(result?.message || "Không thể cập nhật cấu hình hệ thống.");
      }
      state.systemConfig = result.data || payload;
      renderSystemConfig();
      showToast("Đã lưu cấu hình kiểm soát rủi ro công nợ hệ thống.", "success");
    } catch (error) {
      showToast(error.message || "Lỗi lưu cấu hình hệ thống.", "error");
    } finally {
      state.configSaving = false;
      btn.disabled = !canManage;
      btn.textContent = "Lưu cấu hình hệ thống";
    }
  });

  // ──────────────────────── S7-04: TAB 4 - GHI NHẬN THU TIỀN CÔNG NỢ ────────────────────────

  const paymentModal = $("debt-payment-modal");
  const paymentForm = $("debt-payment-form");

  async function loadDealersList() {
    try {
      const response = await Auth.apiFetch("/dealers/active");
      const result = await response.json().catch(() => null);
      if (response.ok && result?.success && Array.isArray(result.data)) {
        state.dealersList = result.data;
      } else {
        // Fallback to debt endpoint
        const res2 = await Auth.apiFetch("/finance/debt");
        const res2Json = await res2.json().catch(() => null);
        if (res2.ok && Array.isArray(res2Json?.data)) {
          state.dealersList = res2Json.data.map(d => ({ id: d.dealerId, name: d.customerName, code: "" }));
        }
      }
    } catch {
      state.dealersList = [];
    }

    // Populate modal dealer dropdown
    const select = $("payment-dealer-id");
    const filterSelect = $("debt-payments-dealer-filter");
    const optionsHtml = '<option value="">-- Chọn đại lý --</option>' +
      state.dealersList.map(d => `<option value="${d.id}">${escapeHtml(d.name || d.customerName || `Đại lý #${d.id}`)} (ID ${d.id})</option>`).join("");

    select.innerHTML = optionsHtml;
    filterSelect.innerHTML = '<option value="">Tất cả đại lý</option>' +
      state.dealersList.map(d => `<option value="${d.id}">${escapeHtml(d.name || d.customerName || `Đại lý #${d.id}`)}</option>`).join("");
  }

  function openPaymentModal(preselectedDealerId = null) {
    if (!canManage) return;
    paymentForm.reset();
    $("debt-payment-form-message").hidden = true;
    $("payment-date").value = new Date().toISOString().split("T")[0];

    if (preselectedDealerId) {
      $("payment-dealer-id").value = String(preselectedDealerId);
      updatePaymentDealerHint(preselectedDealerId);
    } else {
      $("payment-dealer-current-debt").textContent = "";
    }

    paymentModal.hidden = false;
    document.querySelector(".app-shell").inert = true;
    $("payment-dealer-id").focus();
  }

  function closePaymentModal() {
    paymentModal.hidden = true;
    document.querySelector(".app-shell").inert = false;
    paymentForm.reset();
  }

  async function updatePaymentDealerHint(dealerId) {
    if (!dealerId) {
      $("payment-dealer-current-debt").textContent = "";
      return;
    }
    try {
      const res = await Auth.apiFetch(`/dealers/${dealerId}/debt`);
      const result = await res.json().catch(() => null);
      if (res.ok && result?.success && result.data) {
        const debt = result.data;
        $("payment-dealer-current-debt").innerHTML = `Công nợ hiện tại: <strong>${formatAmount(debt.currentDebt)}</strong> · Quá hạn: <strong style="color:var(--danger)">${formatAmount(debt.overdueAmount)}</strong>`;
      }
    } catch {
      $("payment-dealer-current-debt").textContent = "";
    }
  }

  $("payment-dealer-id").addEventListener("change", event => {
    updatePaymentDealerHint(event.target.value);
  });

  paymentForm.addEventListener("submit", async event => {
    event.preventDefault();
    if (!canManage || state.savingPayment) return;

    const dealerId = $("payment-dealer-id").value;
    const amountVal = $("payment-amount").value.trim();
    const paymentDate = $("payment-date").value;
    const paymentMethod = $("payment-method").value;
    const referenceCode = $("payment-reference").value.trim();
    const note = $("payment-note").value.trim();

    if (!dealerId) {
      showToast("Vui lòng chọn đại lý thanh toán.", "error");
      $("payment-dealer-id").focus();
      return;
    }
    const amount = Number(amountVal);
    if (!amountVal || isNaN(amount) || amount <= 0) {
      showToast("Số tiền thu phải lớn hơn 0.", "error");
      $("payment-amount").focus();
      return;
    }

    const payload = {
      dealerId: Number(dealerId),
      amount: amount,
      paymentDate: paymentDate || null,
      paymentMethod: paymentMethod || null,
      referenceCode: referenceCode || null,
      note: note || null,
    };

    state.savingPayment = true;
    const submitBtn = $("save-debt-payment");
    submitBtn.disabled = true;
    submitBtn.textContent = "Đang ghi nhận & đánh giá lại…";
    $("debt-payment-form-message").hidden = true;

    try {
      const response = await Auth.apiFetch("/finance/debt-payments", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
      });
      const result = await response.json().catch(() => null);
      if (!response.ok || !result?.success) {
        throw new Error(result?.message || "Không thể ghi nhận thu tiền.");
      }

      const paymentResp = result.data;
      let msg = `Đã ghi nhận phiếu thu ${paymentResp.paymentCode}. Công nợ còn lại: ${formatAmount(paymentResp.remainingDebt)}.`;
      if (paymentResp.dealerStillFlagged) {
        msg += ` Đại lý vẫn còn cờ vi phạm: ${paymentResp.debtFlagReason || ""}`;
      } else {
        msg += " Đại lý đã về mức an toàn và được gỡ cờ rủi ro!";
      }

      showToast(msg, "success");
      closePaymentModal();

      // Refresh data across tabs
      loadPaymentHistory();
      loadFlaggedDealers();
      loadLimits();
    } catch (error) {
      $("debt-payment-form-message").textContent = error.message || "Lỗi ghi nhận thu tiền.";
      $("debt-payment-form-message").hidden = false;
    } finally {
      state.savingPayment = false;
      submitBtn.disabled = false;
      submitBtn.textContent = "Xác nhận ghi thu tiền";
    }
  });

  paymentModal.querySelectorAll("[data-close-debt-payment]").forEach(btn =>
    btn.addEventListener("click", closePaymentModal));

  async function loadPaymentHistory() {
    if (state.paymentLoading) return;
    state.paymentLoading = true;
    state.paymentError = "";
    renderPaymentHistory();

    const dealerFilter = $("debt-payments-dealer-filter")?.value || "";
    let url = `/finance/debt-payments?page=${state.paymentPage}&size=10`;
    if (dealerFilter) url += `&dealerId=${dealerFilter}`;

    try {
      const response = await Auth.apiFetch(url);
      const result = await response.json().catch(() => null);
      if (!response.ok || !result?.success) {
        throw new Error(result?.message || "Không thể tải lịch sử thu tiền.");
      }
      const data = result.data;
      if (Array.isArray(data)) {
        state.paymentRows = data;
        state.paymentTotalPages = 1;
      } else if (data && Array.isArray(data.content)) {
        state.paymentRows = data.content;
        state.paymentTotalPages = Math.max(1, data.totalPages || 1);
      } else {
        state.paymentRows = [];
        state.paymentTotalPages = 1;
      }
    } catch (error) {
      state.paymentError = error.message || "Lỗi tải lịch sử thu tiền.";
    } finally {
      state.paymentLoading = false;
      renderPaymentHistory();
    }
  }

  function renderPaymentHistory() {
    const rows = state.paymentRows;
    const tbody = $("debt-payments-rows");

    $("debt-payments-prev").disabled = state.paymentLoading || !!state.paymentError || state.paymentPage === 0;
    $("debt-payments-next").disabled = state.paymentLoading || !!state.paymentError || state.paymentPage >= state.paymentTotalPages - 1;
    $("debt-payments-page").textContent = state.paymentLoading || state.paymentError ? "—" : `Trang ${state.paymentPage + 1} / ${state.paymentTotalPages}`;

    if (state.paymentLoading || state.paymentError || !rows.length) {
      const title = state.paymentLoading
        ? "Đang tải lịch sử phiếu thu…"
        : state.paymentError
          ? "Không thể tải lịch sử thu tiền"
          : "Chưa có phiếu thu tiền nào được ghi nhận";

      $("debt-payments-summary").textContent = state.paymentLoading ? "Đang tải…" : "0 phiếu thu";
      tbody.innerHTML = `<tr><td colspan="9" class="debt-limits__empty">
        <strong>${escapeHtml(title)}</strong>
        ${state.paymentError ? `<span>${escapeHtml(state.paymentError)}</span>` : ""}
      </td></tr>`;
      return;
    }

    $("debt-payments-summary").textContent = `Hiển thị ${rows.length} phiếu thu`;
    tbody.innerHTML = rows.map(p => {
      const statusBadge = p.dealerStillFlagged
        ? '<span class="debt-badge debt-badge--danger" title="Đại lý vẫn còn nợ vi phạm">Còn cờ rủi ro</span>'
        : '<span class="debt-badge debt-badge--success" title="Công nợ đã về mức an toàn">An toàn</span>';

      const methodLabel = p.paymentMethod === "BANK_TRANSFER"
        ? "Chuyển khoản"
        : p.paymentMethod === "CASH"
          ? "Tiền mặt"
          : p.paymentMethod || "—";

      return `<tr>
        <td><strong>${escapeHtml(p.paymentCode || `#${p.id}`)}</strong></td>
        <td>
          <span class="debt-limits__name">${escapeHtml(p.dealerName || "Đại lý")}</span>
          <span class="debt-limits__id">${escapeHtml(p.dealerCode || "")} · ID: ${escapeHtml(p.dealerId)}</span>
        </td>
        <td><strong style="color:var(--teal-deep)">${formatAmount(p.amount)}</strong></td>
        <td>${escapeHtml(p.paymentDate || "—")}</td>
        <td>${escapeHtml(methodLabel)}</td>
        <td>${escapeHtml(p.referenceCode || "—")}</td>
        <td>${escapeHtml(p.recordedBy || "—")}</td>
        <td>${formatAmount(p.remainingDebt)}</td>
        <td>${statusBadge}</td>
      </tr>`;
    }).join("");
  }

  // ──────────────────────── S3-05: TAB 2 - KHAI BÁO HẠN MỨC CÔNG NỢ ────────────────────────

  const modal = $("debt-limit-modal");
  const form = $("debt-limit-form");
  let previousFocus;
  let previousOverflow;

  function filteredLimitRows() {
    const keyword = $("debt-limit-search").value.trim().toLocaleLowerCase("vi");
    const filter = $("debt-limit-filter").value;
    return state.limitRows.filter(row => {
      const matchesKeyword = !keyword || [row.customerName, row.dealerId].some(value =>
        String(value ?? "").toLocaleLowerCase("vi").includes(keyword));
      const amount = amountNumber(row.debtLimit);
      return matchesKeyword && (filter === "all" || (filter === "positive" && amount !== null && amount > 0)
        || (filter === "zero" && amount === 0));
    });
  }

  function renderLimits() {
    const rows = filteredLimitRows();
    const totalPages = Math.max(1, Math.ceil(rows.length / pageSize));
    state.limitPage = Math.min(state.limitPage, totalPages - 1);

    $("reload-debt-limits").disabled = state.limitLoading;
    $("debt-limits-view").setAttribute("aria-busy", String(state.limitLoading));
    $("debt-limits-prev").disabled = state.limitLoading || !!state.limitError || state.limitPage === 0;
    $("debt-limits-next").disabled = state.limitLoading || !!state.limitError || state.limitPage >= totalPages - 1;
    $("debt-limits-page").textContent = state.limitLoading || state.limitError ? "—" : `Trang ${state.limitPage + 1} / ${totalPages}`;

    if (state.limitLoading || state.limitError || !rows.length) {
      const title = state.limitLoading ? "Đang tải hạn mức công nợ…" : state.limitError ? "Không tải được danh sách đại lý"
        : state.limitRows.length ? "Không tìm thấy đại lý phù hợp" : "Chưa có đại lý để khai báo hạn mức";
      $("debt-limits-summary").textContent = state.limitLoading ? "Đang tải dữ liệu…"
        : state.limitError ? "Chưa tải được dữ liệu" : "0 đại lý";
      $("debt-limits-rows").innerHTML = `<tr><td colspan="4" class="debt-limits__empty">
        <strong>${title}</strong>
        ${state.limitError ? `<span>${escapeHtml(state.limitError)}</span><button class="btn btn--secondary" type="button" data-retry-debt-limits>Thử tải lại</button>` : ""}
      </td></tr>`;
      return;
    }
    const start = state.limitPage * pageSize;
    const end = Math.min(start + pageSize, rows.length);
    $("debt-limits-summary").textContent = `${start + 1}–${end} / ${rows.length.toLocaleString("vi-VN")} đại lý`;
    $("debt-limits-rows").innerHTML = rows.slice(start, end).map(row => {
      const flagInfo = row.debtFlagged
        ? ` <span class="debt-badge debt-badge--danger" style="margin-left:4px" title="${escapeHtml(row.debtFlagReason || "")}">Cờ rủi ro</span>`
        : "";

      return `<tr>
        <td>
          <span class="debt-limits__name">${escapeHtml(row.customerName || "Chưa có tên đại lý")}${flagInfo}</span>
          <span class="debt-limits__id">ID: ${escapeHtml(row.dealerId)}</span>
        </td>
        <td><span class="debt-limits__amount">${escapeHtml(formatAmount(row.debtLimit))}</span></td>
        <td><span class="debt-limits__days">${escapeHtml(formatDays(row.maxDebtDays))}</span></td>
        <td>${canManage ? `<button class="btn btn--secondary" type="button" data-declare-debt-limit="${escapeHtml(row.dealerId)}" aria-label="${escapeHtml(`Khai báo hạn mức cho ${row.customerName || row.dealerId}`)}">Khai báo</button>` : '<span class="debt-limits__missing">Chỉ xem</span>'}</td>
      </tr>`;
    }).join("");
  }

  async function loadLimits() {
    if (state.limitLoading) return;
    state.limitLoading = true;
    state.limitError = "";
    state.limitRows = [];
    state.limitPage = 0;
    renderLimits();
    try {
      const response = await Auth.apiFetch("/finance/debt");
      const result = await response.json().catch(() => null);
      if (!response.ok || !result?.success || !Array.isArray(result.data)
        || result.data.some(row => !row || row.dealerId == null)) {
        throw new Error(result?.message || "Máy chủ không trả về danh sách hạn mức hợp lệ.");
      }
      state.limitRows = result.data;
    } catch (error) {
      state.limitError = error.message || "Không thể kết nối máy chủ. Vui lòng thử lại.";
    } finally {
      state.limitLoading = false;
      renderLimits();
    }
  }

  function clearDeclarationCheck() {
    $("debt-limit-form-message").hidden = true;
    $("debt-limit-preview").hidden = true;
    $("save-debt-limit").disabled = true;
    form.querySelectorAll('[aria-invalid="true"]').forEach(field => field.removeAttribute("aria-invalid"));
    state.pendingDeclaration = null;
  }

  function openDeclaration(row) {
    if (!canManage) return;
    state.selectedLimitRow = row;
    previousFocus = document.activeElement;
    previousOverflow = document.body.style.overflow;
    form.reset();
    clearDeclarationCheck();
    $("debt-limit-modal-dealer").textContent = `${row.customerName || "Đại lý"} · ID ${row.dealerId}`;
    $("debt-limit-current").textContent = `Hạn mức hiện tại: ${formatAmount(row.debtLimit)} · Số ngày nợ: ${formatDays(row.maxDebtDays)}`;
    $("debt-limit-amount").value = amountNumber(row.debtLimit) === null ? "" : String(row.debtLimit);
    $("debt-limit-days").value = Number.isInteger(Number(row.maxDebtDays)) ? String(row.maxDebtDays) : "";
    $("debt-limit-days-help").textContent = `Giá trị hiện tại: ${formatDays(row.maxDebtDays)}. Nhập số ngày nguyên không âm.`;
    modal.hidden = false;
    document.querySelector(".app-shell").inert = true;
    document.body.style.overflow = "hidden";
    $("debt-limit-amount").focus();
  }

  function closeDeclaration() {
    if (modal.hidden) return;
    modal.hidden = true;
    document.querySelector(".app-shell").inert = false;
    document.body.style.overflow = previousOverflow;
    state.selectedLimitRow = null;
    state.pendingDeclaration = null;
    state.savingLimit = false;
    form.reset();
    clearDeclarationCheck();
    previousFocus?.focus();
  }

  form.addEventListener("submit", event => {
    event.preventDefault();
    if (!canManage || !state.selectedLimitRow) return;
    clearDeclarationCheck();
    const amountField = $("debt-limit-amount");
    const daysField = $("debt-limit-days");
    const reasonField = $("debt-limit-reason");
    const amount = amountField.value.trim();
    const days = daysField.value.trim();
    const reason = reasonField.value.trim();
    const errors = [];
    if (!amount || amountNumber(amount) === null || !/^\d+(?:\.\d{1,2})?$/.test(amount)) {
      errors.push([amountField, "Nhập hạn mức tiền không âm, tối đa 2 chữ số thập phân."]);
    }
    if (!days || !/^\d+$/.test(days) || !Number.isSafeInteger(Number(days))) {
      errors.push([daysField, "Nhập số ngày nợ tối đa là số nguyên không âm."]);
    }
    if (!reason) errors.push([reasonField, "Vui lòng nhập lý do khai báo hoặc thay đổi hạn mức."]);
    if (errors.length) {
      errors.forEach(([field]) => field.setAttribute("aria-invalid", "true"));
      $("debt-limit-form-message").textContent = errors.map(([, message]) => message).join(" ");
      $("debt-limit-form-message").hidden = false;
      errors[0][0].focus();
      return;
    }
    const values = [
      ["Hạn mức tiền", `${formatAmount(state.selectedLimitRow.debtLimit)} → ${formatAmount(amount)}`],
      ["Số ngày nợ tối đa", `${Number(days).toLocaleString("vi-VN")} ngày`],
      ["Lý do", reason],
    ];
    $("debt-limit-preview-values").innerHTML = values.map(([label, value]) =>
      `<div><dt>${escapeHtml(label)}</dt><dd>${escapeHtml(value)}</dd></div>`).join("");
    $("debt-limit-preview").hidden = false;
    state.pendingDeclaration = {
      debtLimit: Number(amount),
      maxDebtDays: Number(days),
      reason,
    };
    $("save-debt-limit").disabled = false;
  });

  $("save-debt-limit").addEventListener("click", async () => {
    if (!canManage || !state.selectedLimitRow || !state.pendingDeclaration || state.savingLimit) return;
    const button = $("save-debt-limit");
    state.savingLimit = true;
    button.disabled = true;
    button.textContent = "Đang lưu…";
    $("debt-limit-form-message").hidden = true;

    try {
      const response = await Auth.apiFetch(`/finance/debt/${state.selectedLimitRow.dealerId}/limit`, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(state.pendingDeclaration),
      });
      const result = await response.json().catch(() => null);
      if (!response.ok || !result?.success) {
        throw new Error(result?.message || "Không lưu được hạn mức công nợ.");
      }

      const saved = result.data || state.pendingDeclaration;
      const row = state.limitRows.find(item => String(item.dealerId) === String(state.selectedLimitRow.dealerId));
      if (row) {
        row.debtLimit = saved.debtLimit;
        row.maxDebtDays = saved.maxDebtDays;
      }
      closeDeclaration();
      renderLimits();
      loadFlaggedDealers(); // Cập nhật lại đánh giá rủi ro
      showToast(result.message || "Đã cập nhật hạn mức công nợ.", "success");
    } catch (error) {
      $("debt-limit-form-message").textContent = error.message || "Không thể lưu hạn mức công nợ.";
      $("debt-limit-form-message").hidden = false;
    } finally {
      state.savingLimit = false;
      button.disabled = !state.pendingDeclaration;
      button.textContent = "Lưu khai báo";
    }
  });

  form.addEventListener("input", clearDeclarationCheck);

  // ──────────────────────── EVENT LISTENERS & SETUP ────────────────────────

  // Global header reload
  $("reload-debt-limits").addEventListener("click", () => {
    if (state.activeTab === "alerts") loadFlaggedDealers();
    else if (state.activeTab === "limits") loadLimits();
    else if (state.activeTab === "config") loadSystemRiskConfig();
    else if (state.activeTab === "payments") loadPaymentHistory();
  });

  // Header quick buttons
  $("btn-trigger-evaluate-all").addEventListener("click", evaluateAllDealers);
  $("btn-open-payment-modal").addEventListener("click", () => openPaymentModal());
  $("btn-new-payment").addEventListener("click", () => openPaymentModal());

  // Flagged table events
  $("debt-alerts-rows").addEventListener("click", event => {
    const target = event.target;
    if (target.id === "btn-retry-flagged") {
      loadFlaggedDealers();
      return;
    }
    const btnAction = target.closest("[data-action-config]");
    if (btnAction) {
      const dealerId = btnAction.dataset.actionConfig;
      const row = state.flaggedRows.find(r => String(r.dealerId) === String(dealerId));
      if (row) openDealerActionModal(row);
      return;
    }
    const btnEval = target.closest("[data-evaluate-dealer]");
    if (btnEval) {
      evaluateSingleDealer(btnEval.dataset.evaluateDealer);
      return;
    }
    const btnPay = target.closest("[data-pay-dealer]");
    if (btnPay) {
      openPaymentModal(btnPay.dataset.payDealer);
      return;
    }
  });

  // Flagged filters
  [$("debt-alerts-search"), $("debt-alerts-flag-type"), $("debt-alerts-action-type")].forEach(el => {
    if (el) {
      el.addEventListener(el.tagName === "INPUT" ? "input" : "change", () => {
        state.flaggedPage = 0;
        renderFlaggedDealers();
      });
    }
  });
  $("reset-debt-alerts-filters")?.addEventListener("click", () => {
    $("debt-alerts-filters")?.reset();
    state.flaggedPage = 0;
    renderFlaggedDealers();
  });
  $("debt-alerts-prev")?.addEventListener("click", () => {
    if (state.flaggedPage > 0) { state.flaggedPage--; renderFlaggedDealers(); }
  });
  $("debt-alerts-next")?.addEventListener("click", () => {
    if ((state.flaggedPage + 1) * pageSize < filteredFlaggedRows().length) {
      state.flaggedPage++;
      renderFlaggedDealers();
    }
  });

  // Limits table events
  $("debt-limits-rows").addEventListener("click", event => {
    if (event.target.closest("[data-retry-debt-limits]")) { loadLimits(); return; }
    const button = event.target.closest("[data-declare-debt-limit]");
    if (!button) return;
    const row = state.limitRows.find(item => String(item.dealerId) === button.dataset.declareDebtLimit);
    if (row) openDeclaration(row);
  });
  $("debt-limits-filters").addEventListener("submit", event => event.preventDefault());
  [$("debt-limit-search"), $("debt-limit-filter")].forEach(control => {
    control.addEventListener(control.tagName === "INPUT" ? "input" : "change", () => { state.limitPage = 0; renderLimits(); });
  });
  $("reset-debt-limit-filters").addEventListener("click", () => {
    $("debt-limits-filters").reset();
    state.limitPage = 0;
    renderLimits();
  });
  $("debt-limits-prev").addEventListener("click", () => { if (state.limitPage > 0) { state.limitPage--; renderLimits(); } });
  $("debt-limits-next").addEventListener("click", () => {
    if ((state.limitPage + 1) * pageSize < filteredLimitRows().length) { state.limitPage++; renderLimits(); }
  });

  modal.querySelectorAll("[data-close-debt-limit]").forEach(button => button.addEventListener("click", closeDeclaration));
  modal.addEventListener("click", event => { if (event.target === modal) closeDeclaration(); });

  // Payments filter & pagination
  $("debt-payments-dealer-filter")?.addEventListener("change", () => {
    state.paymentPage = 0;
    loadPaymentHistory();
  });
  $("reset-debt-payments-filters")?.addEventListener("click", () => {
    $("debt-payments-filters")?.reset();
    state.paymentPage = 0;
    loadPaymentHistory();
  });
  $("debt-payments-prev")?.addEventListener("click", () => {
    if (state.paymentPage > 0) { state.paymentPage--; loadPaymentHistory(); }
  });
  $("debt-payments-next")?.addEventListener("click", () => {
    if (state.paymentPage < state.paymentTotalPages - 1) { state.paymentPage++; loadPaymentHistory(); }
  });

  // ESC key for modals
  document.addEventListener("keydown", event => {
    if (event.key === "Escape") {
      if (!modal.hidden) closeDeclaration();
      if (!actionModal.hidden) closeDealerActionModal();
      if (!paymentModal.hidden) closePaymentModal();
    }
  });

  // Permission changed handler
  window.addEventListener("auth:permissions-changed", async () => {
    await Auth.refreshPermissions();
    if (!MenuConfig.getMenuForUser(Auth.getUser()).some(item => item.id === "debt-limits")) {
      location.href = "/error?status=403";
      return;
    }
    syncPermissionsUI();
    renderFlaggedDealers();
    renderLimits();
    renderSystemConfig();
  });

  // Initialization
  syncPermissionsUI();
  switchTab(state.activeTab);
  loadDealersList();
})();
