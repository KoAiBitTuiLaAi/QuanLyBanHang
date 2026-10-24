/**
 * dealer-invoices.js — S7-07: Sổ công nợ và danh sách hóa đơn dành cho Đại lý.
 * 
 * Nghiệp vụ S7-07:
 * - Hiển thị tổng còn nợ, hạn mức công nợ và hạn mức còn được lấy hàng (availableOrderLimit).
 * - Danh sách hóa đơn của chính mình kèm số tiền, hạn thanh toán và trạng thái.
 * - Xem chi tiết và tải lại hóa đơn đã phát hành (PDF, HTML in, chứng từ điều chỉnh).
 * - Tuân thủ ma trận phân quyền cơ sở dữ liệu: INVOICE_VIEW và FINANCE_VIEW_DEBT.
 * - Quản trị viên (ROLE_ADMIN) có thể chọn đại lý hỗ trợ qua DealerPortalContext.
 */

(async () => {
  if (typeof Auth !== "undefined" && !Auth.requireLogin()) return;
  if (typeof Auth !== "undefined") await Auth.ready;

  // ── Helper functions ─────────────────────────────────────────
  const $ = (id) => document.getElementById(id);

  const formatVND = (value) => {
    if (value == null) return "—";
    return new Intl.NumberFormat("vi-VN", {
      style: "currency",
      currency: "VND",
      maximumFractionDigits: 0,
    }).format(Number(value));
  };

  const formatDate = (value) => {
    if (!value) return "Chưa xác định";
    const str = String(value);
    const date = new Date(str.length === 10 ? `${str}T00:00:00` : str);
    return Number.isNaN(date.getTime())
      ? "Chưa xác định"
      : new Intl.DateTimeFormat("vi-VN", {
          day: "2-digit",
          month: "2-digit",
          year: "numeric",
        }).format(date);
  };

  const formatQuantity = (value) => {
    if (value == null) return "0";
    return new Intl.NumberFormat("vi-VN", { maximumFractionDigits: 4 }).format(Number(value));
  };

  const escapeHtml = (value) =>
    String(value ?? "").replace(/[&<>'"]/g, (char) => ({
      "&": "&amp;",
      "<": "&lt;",
      ">": "&gt;",
      "'": "&#39;",
      '"': "&quot;",
    })[char]);

  // ── Permission & Role Guard ──────────────────────────────────
  function checkPermissions() {
    const isAdmin = Auth.hasRole("ROLE_ADMIN");
    const isDealer = Auth.hasRole("ROLE_DEALER");

    if (!isAdmin && !isDealer) {
      if (typeof sessionStorage !== "undefined") {
        sessionStorage.setItem("errorMessage", "Trang này chỉ dành cho Đại lý và Quản trị viên.");
      }
      window.location.href = "/error?status=403";
      return false;
    }

    // Kiểm tra ma trận quyền DB: bắt buộc có INVOICE_VIEW hoặc FINANCE_VIEW_DEBT
    const hasInvoiceView = isAdmin || Auth.hasPermission("INVOICE_VIEW");
    const hasDebtView = isAdmin || Auth.hasPermission("FINANCE_VIEW_DEBT");

    const workspace = $("di-workspace");
    const accessNotice = $("di-access-denied");

    if (!hasInvoiceView && !hasDebtView) {
      if (workspace) workspace.hidden = true;
      if (accessNotice) {
        accessNotice.hidden = false;
        accessNotice.textContent = "Bạn chưa có quyền xem hóa đơn (INVOICE_VIEW) hoặc công nợ (FINANCE_VIEW_DEBT). Liên hệ quản trị viên để cấp quyền.";
      }
      closeModals();
      return false;
    }

    if (workspace) workspace.hidden = false;
    if (accessNotice) accessNotice.hidden = true;
    return true;
  }

  if (!checkPermissions()) return;

  // Lắng nghe sự kiện thay đổi ma trận quyền từ server
  window.addEventListener("auth:permissions-changed", () => {
    if (!checkPermissions()) return;
    loadAllData();
  });

  // ── State Management ────────────────────────────────────────
  const state = {
    invoices: [],
    totalElements: 0,
    totalPages: 0,
    currentPage: 0,
    pageSize: 20,
    selectedInvoice: null,
    debtSummary: null,
    loading: false,
  };

  // ── Alert Helper ─────────────────────────────────────────────
  function showAlert(message, type = "error") {
    const alertBox = $("di-alert");
    if (!alertBox) return;
    if (!message) {
      alertBox.hidden = true;
      alertBox.textContent = "";
      alertBox.className = "di-alert";
      return;
    }
    alertBox.textContent = message;
    alertBox.className = `di-alert di-alert--${type}`;
    alertBox.hidden = false;
    if (type === "success") {
      setTimeout(() => {
        alertBox.hidden = true;
      }, 5000);
    }
  }

  // ── Admin Dealer Switcher ────────────────────────────────────
  let hasValidDealer = true;
  if (DealerPortalContext.isAdmin) {
    try {
      hasValidDealer = await DealerPortalContext.init("dp-dealer-switcher-wrap", "dp-dealer-select");
      if (!hasValidDealer) {
        showAlert("Vui lòng chọn đại lý cần xem công nợ từ danh sách ở góc phải.", "error");
        const tbody = $("di-invoice-rows");
        if (tbody) {
          tbody.innerHTML = `<tr><td colspan="9" class="di-empty">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/></svg>
            <h3>Chưa chọn đại lý</h3>
            <p>Vui lòng chọn đại lý từ thanh công cụ ở trên để xem công nợ và hóa đơn.</p>
          </td></tr>`;
        }
        return;
      }
    } catch (err) {
      showAlert(err.message || "Không thể khởi tạo danh sách đại lý.", "error");
      return;
    }
  }

  // ── Load Financial Overview / Debt Summary (S7-07) ───────────
  async function loadDebtSummary() {
    const isAdmin = Auth.hasRole("ROLE_ADMIN");
    const hasDebtView = isAdmin || Auth.hasPermission("FINANCE_VIEW_DEBT");

    const summaryCards = document.querySelector(".di-summary-cards");
    if (!hasDebtView) {
      // Nếu không có quyền công nợ, ẩn các thẻ công nợ
      if (summaryCards) summaryCards.hidden = true;
      return;
    }
    if (summaryCards) summaryCards.hidden = false;

    try {
      let url = "/portal/debt/summary";
      let response = await Auth.apiFetch(DealerPortalContext.url(url));

      // Fallback sang /portal/debt nếu endpoint summary không có sẵn
      if (!response.ok) {
        url = "/portal/debt";
        response = await Auth.apiFetch(DealerPortalContext.url(url));
      }

      if (!response.ok) {
        throw new Error("Không thể tải thông tin công nợ");
      }

      const res = await response.json();
      const debt = res.data || res;
      state.debtSummary = debt;
      renderDebtSummary(debt);
    } catch (err) {
      console.warn("Lỗi khi tải thông tin công nợ:", err);
      // Hiển thị trạng thái lỗi nhẹ trên thẻ
      $("val-current-debt").textContent = "—";
      $("val-debt-limit").textContent = "—";
      $("val-remaining-limit").textContent = "—";
      $("val-available-order-limit").textContent = "—";
    }
  }

  function renderDebtSummary(debt) {
    if (!debt) return;

    // 1. Tổng còn nợ
    const currentDebt = debt.currentDebt != null ? Number(debt.currentDebt) : 0;
    $("val-current-debt").textContent = formatVND(currentDebt);

    const unpaidCount = debt.unpaidInvoiceCount ?? 0;
    $("val-unpaid-invoices").textContent = `Chưa thanh toán: ${unpaidCount} HĐ`;

    const overdueCount = debt.overdueInvoiceCount ?? 0;
    const overdueTag = $("val-overdue-tag");
    if (overdueCount > 0) {
      overdueTag.hidden = false;
      $("val-overdue-invoices").textContent = String(overdueCount);
    } else {
      overdueTag.hidden = true;
    }

    // 2. Hạn mức công nợ
    const debtLimit = debt.debtLimit != null ? Number(debt.debtLimit) : 0;
    $("val-debt-limit").textContent = formatVND(debtLimit);
    $("val-max-debt-days").textContent = String(debt.maxDebtDays ?? 30);

    // 3. Hạn mức còn lại
    const remainingLimit = debt.remainingLimit != null ? Number(debt.remainingLimit) : Math.max(0, debtLimit - currentDebt);
    $("val-remaining-limit").textContent = formatVND(remainingLimit);

    let usedPercent = 0;
    if (debtLimit > 0) {
      usedPercent = Math.min(100, Math.round((currentDebt / debtLimit) * 100));
    }
    $("val-limit-used-percent").textContent = `${usedPercent}%`;

    // 4. Còn được lấy hàng (availableOrderLimit)
    const availableOrder = debt.availableOrderLimit != null ? Number(debt.availableOrderLimit) : (debt.isBlocked ? 0 : Math.max(0, remainingLimit));
    const availElem = $("val-available-order-limit");
    availElem.textContent = formatVND(availableOrder);

    const orderBadge = $("val-order-status-badge");
    const blockedBanner = $("di-blocked-banner");

    if (debt.isBlocked || debt.hasOverdueDebt || availableOrder <= 0) {
      availElem.style.color = "var(--danger)";
      orderBadge.className = "di-card__badge di-card__badge--danger";
      orderBadge.textContent = debt.isBlocked ? "Bị chặn đặt hàng" : "Hết hạn mức lấy hàng";

      blockedBanner.hidden = false;
      $("di-blocked-title").textContent = debt.isBlocked ? "Tài khoản bị tạm chặn giao dịch" : "Cảnh báo nợ quá hạn";
      $("di-blocked-message").textContent = debt.blockReason ||
        (debt.hasOverdueDebt
          ? `Đại lý có khoản nợ quá hạn ${formatVND(debt.overdueAmount || 0)} (${debt.maxOverdueDays || 0} ngày). Vui lòng thanh toán để tiếp tục đặt hàng.`
          : "Hạn mức khả dụng đã hết. Vui lòng thanh toán bớt công nợ để tiếp tục lên đơn.");
    } else {
      availElem.style.color = "var(--teal-deep)";
      orderBadge.className = "di-card__badge di-card__badge--success";
      orderBadge.textContent = "Có thể đặt hàng";
      blockedBanner.hidden = true;
    }
  }

  // ── Load Invoices List (S7-07) ──────────────────────────────
  async function loadInvoices(page = 0) {
    const isAdmin = Auth.hasRole("ROLE_ADMIN");
    if (!isAdmin && !Auth.hasPermission("INVOICE_VIEW")) {
      const tbody = $("di-invoice-rows");
      if (tbody) {
        tbody.innerHTML = `<tr><td colspan="9" class="di-empty">
          <h3>Thiếu quyền truy cập hóa đơn</h3>
          <p>Tài khoản cần có quyền INVOICE_VIEW để xem danh sách hóa đơn bán hàng.</p>
        </td></tr>`;
      }
      return;
    }

    state.currentPage = page;
    state.loading = true;

    const tbody = $("di-invoice-rows");
    tbody.innerHTML = `<tr><td colspan="9" class="di-loading">Đang tải danh sách hóa đơn…</td></tr>`;

    const keyword = $("di-keyword").value.trim();
    const paymentStatus = $("di-payment-status").value;
    const status = $("di-status").value;
    const startDate = $("di-start-date").value;
    const endDate = $("di-end-date").value;

    const params = new URLSearchParams({
      page: String(page),
      size: String(state.pageSize),
    });
    if (keyword) params.append("keyword", keyword);
    if (paymentStatus) params.append("paymentStatus", paymentStatus);
    if (status) params.append("status", status);
    if (startDate) params.append("startDate", startDate);
    if (endDate) params.append("endDate", endDate);

    try {
      const endpoint = `/portal/invoices?${params.toString()}`;
      const response = await Auth.apiFetch(DealerPortalContext.url(endpoint));

      if (!response.ok) {
        if (response.status === 403) {
          throw new Error("Bạn không có quyền truy cập hóa đơn (403 Forbidden).");
        }
        throw new Error(`Máy chủ phản hồi lỗi ${response.status}`);
      }

      const res = await response.json();
      const pageData = res.data || res;

      state.invoices = pageData.content || pageData.invoices || (Array.isArray(pageData) ? pageData : []);
      state.totalElements = pageData.totalElements ?? state.invoices.length;
      state.totalPages = pageData.totalPages ?? (Math.ceil(state.totalElements / state.pageSize) || 1);

      renderInvoicesTable(state.invoices);
      renderPagination();
      updateExportButton();
    } catch (err) {
      showAlert(err.message || "Lỗi tải danh sách hóa đơn.", "error");
      tbody.innerHTML = `<tr><td colspan="9" class="di-empty">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg>
        <h3>Không thể tải dữ liệu</h3>
        <p>${escapeHtml(err.message)}</p>
      </td></tr>`;
      $("di-totals").hidden = true;
      updateExportButton();
    } finally {
      state.loading = false;
    }
  }

  function renderInvoicesTable(invoices) {
    const tbody = $("di-invoice-rows");
    const countElem = $("di-invoices-count");
    if (countElem) {
      countElem.textContent = `${state.totalElements} hóa đơn`;
    }

    if (!invoices || invoices.length === 0) {
      tbody.innerHTML = `<tr><td colspan="9" class="di-empty">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>
        <h3>Không tìm thấy hóa đơn nào</h3>
        <p>Thử thay đổi bộ lọc hoặc từ khóa tìm kiếm.</p>
      </td></tr>`;
      $("di-totals").hidden = true;
      return;
    }

    let pageTotal = 0;
    let pagePaid = 0;
    let pageRemain = 0;

    const rowsHtml = invoices.map((inv) => {
      const total = Number(inv.adjustedTotalAmount ?? inv.totalAmount ?? 0);
      const paid = Number(inv.paidAmount ?? (inv.isPaid ? total : 0));
      const remain = Number(inv.remainingAmount ?? Math.max(0, total - paid));

      pageTotal += total;
      pagePaid += paid;
      pageRemain += remain;

      // Trạng thái thanh toán
      let paymentPill = "";
      const pStatus = inv.paymentStatus || (inv.isPaid ? "PAID" : "UNPAID");
      if (pStatus === "PAID") {
        paymentPill = `<span class="di-pill di-pill--paid">Đã thanh toán</span>`;
      } else if (pStatus === "PARTIALLY_PAID") {
        paymentPill = `<span class="di-pill di-pill--partial">Đã trả 1 phần</span>`;
      } else if (pStatus === "OVERDUE" || inv.isOverdue) {
        paymentPill = `<span class="di-pill di-pill--overdue">Quá hạn</span>`;
      } else {
        paymentPill = `<span class="di-pill di-pill--unpaid">Chưa thanh toán</span>`;
      }

      // Trạng thái hóa đơn
      let statusPill = "";
      const invStatus = inv.status || "ISSUED";
      if (invStatus === "CANCELLED") {
        statusPill = `<span class="di-pill di-pill--cancelled">Đã hủy</span>`;
      } else if (invStatus === "ADJUSTED") {
        statusPill = `<span class="di-pill di-pill--adjusted">Đã điều chỉnh</span>`;
      } else {
        statusPill = `<span class="di-pill di-pill--issued">Đã phát hành</span>`;
      }

      // Hạn thanh toán & Tuổi nợ
      let dueDisplay = formatDate(inv.dueDate);
      if (remain > 0) {
        if (inv.isOverdue) {
          dueDisplay += `<span class="di-tag-overdue">Quá ${inv.overdueDays || 1} ngày</span>`;
        } else if (inv.daysUntilDue != null && inv.daysUntilDue <= 7 && inv.daysUntilDue >= 0) {
          dueDisplay += `<span class="di-tag-days">Còn ${inv.daysUntilDue} ngày</span>`;
        }
      }

      return `
        <tr data-invoice-id="${inv.id}">
          <td>
            <strong>${escapeHtml(inv.invoiceNumber || inv.invoiceCode || `HĐ-${inv.id}`)}</strong>
          </td>
          <td>${formatDate(inv.invoiceDate)}</td>
          <td>${dueDisplay}</td>
          <td class="num">${formatVND(total)}</td>
          <td class="num" style="color: var(--teal-deep);">${formatVND(paid)}</td>
          <td class="num" style="color: ${remain > 0 ? 'var(--danger)' : 'var(--ink-700)'}; font-weight: 700;">
            ${formatVND(remain)}
          </td>
          <td>${paymentPill}</td>
          <td>${statusPill}</td>
          <td>
            <div class="di-btn-group">
              <button type="button" class="di-btn-sm btn-view-invoice" data-id="${inv.id}" title="Xem chi tiết hóa đơn">
                Xem
              </button>
              <button type="button" class="di-btn-sm btn-print-invoice" data-id="${inv.id}" title="Xem bản in">
                In
              </button>
              <button type="button" class="di-btn-sm btn-download-invoice" data-id="${inv.id}" data-num="${escapeHtml(inv.invoiceNumber || inv.id)}" title="Tải file PDF">
                PDF
              </button>
            </div>
          </td>
        </tr>
      `;
    }).join("");

    tbody.innerHTML = rowsHtml;

    // Render footer totals
    $("di-t-total").textContent = formatVND(pageTotal);
    $("di-t-paid").textContent = formatVND(pagePaid);
    $("di-t-remain").textContent = formatVND(pageRemain);
    $("di-totals").hidden = false;

    // Attach row events
    tbody.querySelectorAll(".btn-view-invoice").forEach((btn) => {
      btn.addEventListener("click", () => openInvoiceDetail(btn.dataset.id));
    });

    tbody.querySelectorAll(".btn-print-invoice").forEach((btn) => {
      btn.addEventListener("click", () => openPrintModal(btn.dataset.id));
    });

    tbody.querySelectorAll(".btn-download-invoice").forEach((btn) => {
      btn.addEventListener("click", () => downloadInvoicePdf(btn.dataset.id, btn.dataset.num));
    });
  }

  // ── Pagination ──────────────────────────────────────────────
  function renderPagination() {
    const prevBtn = $("di-prev-page");
    const nextBtn = $("di-next-page");
    const pageInfo = $("di-page-info");
    const summary = $("di-page-summary");

    const start = state.totalElements === 0 ? 0 : state.currentPage * state.pageSize + 1;
    const end = Math.min((state.currentPage + 1) * state.pageSize, state.totalElements);

    summary.textContent = `Hiển thị ${start}–${end} trên tổng ${state.totalElements} hóa đơn`;
    pageInfo.textContent = `Trang ${state.currentPage + 1} / ${Math.max(1, state.totalPages)}`;

    prevBtn.disabled = state.currentPage <= 0;
    nextBtn.disabled = state.currentPage >= state.totalPages - 1;
  }

  $("di-prev-page")?.addEventListener("click", () => {
    if (state.currentPage > 0) loadInvoices(state.currentPage - 1);
  });

  $("di-next-page")?.addEventListener("click", () => {
    if (state.currentPage < state.totalPages - 1) loadInvoices(state.currentPage + 1);
  });

  // ── Invoice Detail Modal (S7-07) ─────────────────────────────
  async function openInvoiceDetail(invoiceId) {
    if (!invoiceId) return;
    showAlert("");

    const modal = $("di-detail-modal");
    const body = $("di-detail-body");
    body.innerHTML = `<div class="di-loading">Đang tải chi tiết hóa đơn #${invoiceId}…</div>`;
    modal.hidden = false;

    try {
      const endpoint = `/portal/invoices/${invoiceId}`;
      const response = await Auth.apiFetch(DealerPortalContext.url(endpoint));

      if (!response.ok) {
        throw new Error(`Không thể xem hóa đơn #${invoiceId} (${response.status})`);
      }

      const res = await response.json();
      const invoice = res.data || res;
      state.selectedInvoice = invoice;

      renderInvoiceDetail(invoice);
    } catch (err) {
      body.innerHTML = `<div class="di-notice di-notice--danger">${escapeHtml(err.message)}</div>`;
    }
  }

  function renderInvoiceDetail(inv) {
    const body = $("di-detail-body");
    $("di-detail-title").textContent = `Hóa đơn ${inv.invoiceNumber || inv.invoiceCode || `#${inv.id}`}`;

    const total = Number(inv.adjustedTotalAmount ?? inv.totalAmount ?? 0);
    const paid = Number(inv.paidAmount ?? (inv.isPaid ? total : 0));
    const remain = Number(inv.remainingAmount ?? Math.max(0, total - paid));

    let itemsHtml = "";
    if (Array.isArray(inv.items) && inv.items.length > 0) {
      itemsHtml = `
        <h4 style="margin: 18px 0 10px 0; font-size: 0.9375rem;">Chi tiết mặt hàng</h4>
        <div class="di-table-wrap" style="max-height: 240px; overflow-y: auto;">
          <table class="di-table">
            <thead>
              <tr>
                <th>Mặt hàng</th>
                <th>ĐVT</th>
                <th class="num">Số lượng</th>
                <th class="num">Đơn giá</th>
                <th class="num">Thành tiền</th>
              </tr>
            </thead>
            <tbody>
              ${inv.items.map(item => `
                <tr>
                  <td>
                    <strong>${escapeHtml(item.productName || item.productCode || 'Sản phẩm')}</strong>
                    ${item.productCode ? `<br><small style="color:var(--ink-500);">${escapeHtml(item.productCode)}</small>` : ''}
                  </td>
                  <td>${escapeHtml(item.unitName || item.unit || '—')}</td>
                  <td class="num">${formatQuantity(item.quantity)}</td>
                  <td class="num">${formatVND(item.unitPrice)}</td>
                  <td class="num" style="font-weight: 600;">${formatVND(item.lineTotal ?? (item.quantity * item.unitPrice))}</td>
                </tr>
              `).join('')}
            </tbody>
          </table>
        </div>
      `;
    }

    // Danh sách chứng từ điều chỉnh (nếu có)
    let adjustmentsHtml = "";
    if (Array.isArray(inv.adjustments) && inv.adjustments.length > 0) {
      adjustmentsHtml = `
        <h4 style="margin: 18px 0 10px 0; font-size: 0.9375rem; color: #92400e;">Chứng từ điều chỉnh liên quan</h4>
        <div class="di-table-wrap">
          <table class="di-table">
            <thead>
              <tr>
                <th>Mã chứng từ</th>
                <th>Ngày lập</th>
                <th>Loại điều chỉnh</th>
                <th class="num">Số tiền điều chỉnh</th>
                <th>Lý do</th>
                <th>Tải</th>
              </tr>
            </thead>
            <tbody>
              ${inv.adjustments.map(adj => `
                <tr>
                  <td><strong>${escapeHtml(adj.adjustmentCode || adj.code || `#${adj.id}`)}</strong></td>
                  <td>${formatDate(adj.adjustmentDate || adj.createdAt)}</td>
                  <td>${escapeHtml(adj.typeLabel || adj.type || 'Điều chỉnh')}</td>
                  <td class="num">${formatVND(adj.adjustmentAmount || adj.amount)}</td>
                  <td>${escapeHtml(adj.reason || '—')}</td>
                  <td>
                    <button type="button" class="di-btn-sm btn-download-adj" data-inv="${inv.id}" data-adj="${adj.id}" data-code="${escapeHtml(adj.adjustmentCode || adj.id)}">
                      PDF
                    </button>
                  </td>
                </tr>
              `).join('')}
            </tbody>
          </table>
        </div>
      `;
    }

    body.innerHTML = `
      <div class="di-facts-grid">
        <div class="di-fact">
          <dt>Số hóa đơn</dt>
          <dd>${escapeHtml(inv.invoiceNumber || inv.invoiceCode || `HĐ-${inv.id}`)}</dd>
        </div>
        <div class="di-fact">
          <dt>Ngày phát hành</dt>
          <dd>${formatDate(inv.invoiceDate)}</dd>
        </div>
        <div class="di-fact">
          <dt>Hạn thanh toán</dt>
          <dd>${formatDate(inv.dueDate)}</dd>
        </div>
        <div class="di-fact">
          <dt>Trạng thái thanh toán</dt>
          <dd>${escapeHtml(inv.paymentStatusLabel || (inv.isPaid ? 'Đã thanh toán' : 'Chưa thanh toán'))}</dd>
        </div>
        <div class="di-fact">
          <dt>Tổng tiền hóa đơn</dt>
          <dd>${formatVND(total)}</dd>
        </div>
        <div class="di-fact">
          <dt>Đã thanh toán</dt>
          <dd style="color: var(--teal-deep);">${formatVND(paid)}</dd>
        </div>
        <div class="di-fact">
          <dt>Còn nợ</dt>
          <dd style="color: ${remain > 0 ? 'var(--danger)' : 'var(--ink-700)'};">${formatVND(remain)}</dd>
        </div>
        <div class="di-fact">
          <dt>Trạng thái phát hành</dt>
          <dd>${escapeHtml(inv.statusLabel || inv.status || 'Đã phát hành')}</dd>
        </div>
      </div>

      ${itemsHtml}
      ${adjustmentsHtml}
    `;

    // Bind adjustment PDF download
    body.querySelectorAll(".btn-download-adj").forEach((btn) => {
      btn.addEventListener("click", () => {
        downloadAdjustmentPdf(btn.dataset.inv, btn.dataset.adj, btn.dataset.code);
      });
    });
  }

  // ── Download Invoice PDF (S7-07) ─────────────────────────────
  async function downloadInvoicePdf(invoiceId, invoiceNumber) {
    try {
      showAlert("");
      const endpoint = `/portal/invoices/${invoiceId}/pdf?download=true`;
      const response = await Auth.apiFetch(DealerPortalContext.url(endpoint));

      if (!response.ok) {
        throw new Error(`Không thể tải PDF hóa đơn (${response.status})`);
      }

      const blob = await response.blob();
      const url = window.URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = `hoa-don-${invoiceNumber || invoiceId}.pdf`;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      window.URL.revokeObjectURL(url);
    } catch (err) {
      showAlert(err.message || "Lỗi khi tải tệp PDF.", "error");
    }
  }

  // ── Download Adjustment PDF ──────────────────────────────────
  async function downloadAdjustmentPdf(invoiceId, adjustmentId, adjustmentCode) {
    try {
      showAlert("");
      const endpoint = `/portal/invoices/${invoiceId}/adjustments/${adjustmentId}/pdf?download=true`;
      const response = await Auth.apiFetch(DealerPortalContext.url(endpoint));

      if (!response.ok) {
        throw new Error(`Không thể tải PDF chứng từ điều chỉnh (${response.status})`);
      }

      const blob = await response.blob();
      const url = window.URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = `chung-tu-dieu-chinh-${adjustmentCode || adjustmentId}.pdf`;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      window.URL.revokeObjectURL(url);
    } catch (err) {
      showAlert(err.message || "Lỗi khi tải tệp PDF chứng từ.", "error");
    }
  }

  // ── Print Preview Modal (S7-07) ──────────────────────────────
  async function openPrintModal(invoiceId) {
    const printModal = $("di-print-modal");
    const frame = $("di-print-frame");

    try {
      showAlert("");
      printModal.hidden = false;
      const endpoint = `/portal/invoices/${invoiceId}/print-preview`;
      const response = await Auth.apiFetch(DealerPortalContext.url(endpoint));

      if (!response.ok) {
        throw new Error(`Không thể tải bản in hóa đơn (${response.status})`);
      }

      const html = await response.text();
      frame.srcdoc = html;
    } catch (err) {
      showAlert(err.message || "Lỗi khi tải bản in hóa đơn.", "error");
      printModal.hidden = true;
    }
  }

  $("btn-print-frame-now")?.addEventListener("click", () => {
    const frame = $("di-print-frame");
    if (frame && frame.contentWindow) {
      frame.contentWindow.focus();
      frame.contentWindow.print();
    }
  });

  // ── Export Excel (S7-07) ─────────────────────────────────────
  function updateExportButton() {
    const exportBtn = $("btn-export");
    if (exportBtn) {
      exportBtn.disabled = !state.invoices || state.invoices.length === 0;
    }
  }

  function exportToExcel() {
    if (!state.invoices || state.invoices.length === 0) return;
    if (typeof XLSX === "undefined") {
      showAlert("Thư viện xuất Excel chưa sẵn sàng.", "error");
      return;
    }

    const exportRows = state.invoices.map((inv) => {
      const total = Number(inv.adjustedTotalAmount ?? inv.totalAmount ?? 0);
      const paid = Number(inv.paidAmount ?? (inv.isPaid ? total : 0));
      const remain = Number(inv.remainingAmount ?? Math.max(0, total - paid));

      return {
        "Số hóa đơn": inv.invoiceNumber || inv.invoiceCode || `HĐ-${inv.id}`,
        "Ngày phát hành": formatDate(inv.invoiceDate),
        "Hạn thanh toán": formatDate(inv.dueDate),
        "Tổng tiền (VND)": total,
        "Đã thanh toán (VND)": paid,
        "Còn nợ (VND)": remain,
        "Trạng thái thanh toán": inv.paymentStatusLabel || (inv.isPaid ? "Đã thanh toán" : "Chưa thanh toán"),
        "Trạng thái hóa đơn": inv.statusLabel || inv.status || "Đã phát hành",
        "Quá hạn": inv.isOverdue ? `Quá ${inv.overdueDays || 0} ngày` : "Không",
      };
    });

    const worksheet = XLSX.utils.json_to_sheet(exportRows);
    const workbook = XLSX.utils.book_new();
    XLSX.utils.book_append_sheet(workbook, worksheet, "HoaDon_CongNo");

    const dateStr = new Date().toISOString().slice(0, 10);
    XLSX.writeFile(workbook, `CongNo_HoaDon_${dateStr}.xlsx`);
  }

  // ── Modal Controls ───────────────────────────────────────────
  function closeModals() {
    if ($("di-detail-modal")) $("di-detail-modal").hidden = true;
    if ($("di-print-modal")) $("di-print-modal").hidden = true;
  }

  $("btn-close-detail")?.addEventListener("click", closeModals);
  $("btn-modal-close")?.addEventListener("click", closeModals);
  $("btn-close-print")?.addEventListener("click", closeModals);
  $("btn-close-print-btn")?.addEventListener("click", closeModals);

  $("btn-download-pdf")?.addEventListener("click", () => {
    if (state.selectedInvoice) {
      downloadInvoicePdf(state.selectedInvoice.id, state.selectedInvoice.invoiceNumber);
    }
  });

  $("btn-print-preview")?.addEventListener("click", () => {
    if (state.selectedInvoice) {
      openPrintModal(state.selectedInvoice.id);
    }
  });

  // ── Event Listeners ──────────────────────────────────────────
  $("btn-reload")?.addEventListener("click", () => {
    loadAllData();
  });

  $("btn-export")?.addEventListener("click", exportToExcel);

  $("di-filter-form")?.addEventListener("submit", (e) => {
    e.preventDefault();
    loadInvoices(0);
  });

  $("btn-reset-filter")?.addEventListener("click", () => {
    $("di-keyword").value = "";
    $("di-payment-status").value = "";
    $("di-status").value = "";
    $("di-start-date").value = "";
    $("di-end-date").value = "";
    loadInvoices(0);
  });

  async function loadAllData() {
    showAlert("");
    await Promise.all([loadDebtSummary(), loadInvoices(0)]);
  }

  // Initial Load
  loadAllData();
})();

