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
  };

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

    if (!hasPermission) {
      if (accessNotice) accessNotice.hidden = false;
      if (filterCard) filterCard.hidden = true;
      if (resultPanel) resultPanel.hidden = true;
      if (initialState) initialState.hidden = true;
      if (errorNotice) errorNotice.hidden = true;
      state.currentStatement = null;
      return false;
    }

    if (accessNotice) accessNotice.hidden = true;
    if (filterCard) filterCard.hidden = false;
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
      await syncPermissions();
    });
  }

  // Chạy khởi tạo
  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();

