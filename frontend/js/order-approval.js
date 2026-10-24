/**
 * order-approval.js — S4-05 Duyệt đơn vượt hạn mức / dưới giá sàn.
 *
 * Quyền: ORDER_APPROVE + ORDER_VIEW trong ma trận phân quyền (ROLE_ADMIN luôn được phép)
 * - Danh sách đơn chờ duyệt hiển thị rõ lý do cần duyệt và mức vi phạm
 * - Ba hành động: Duyệt, Từ chối, Trả lại sửa
 *   — Từ chối và Trả lại sửa bắt buộc nhập ý kiến
 * - Đơn được duyệt mới chuyển sang giữ chỗ tồn và chờ xuất kho
 * - Lịch sử duyệt không sửa và không xoá được
 */
 (async () => {
  if (!Auth.requireLogin()) return;
  await Auth.refreshPermissions();

  const user = Auth.getUser();

  // ── Permission guard ──
  if (!Auth.hasPermission("ORDER_APPROVE") || !Auth.hasPermission("ORDER_VIEW")) {
    sessionStorage.setItem("errorMessage", "Bạn cần quyền xem và duyệt đơn hàng để truy cập chức năng này.");
    window.location.href = "/error?status=403";
    return;
  }

  // ── Sidebar & topbar boilerplate ──
  const sidebar = document.getElementById("sidebar");
  const overlay = document.createElement("div");
  overlay.className = "sidebar-overlay";
  document.body.appendChild(overlay);

  function openSidebar(open) {
    sidebar.classList.toggle("sidebar--open", open);
    overlay.classList.toggle("sidebar-overlay--visible", open);
  }

  document.getElementById("sidebar-toggle")?.addEventListener("click", () => openSidebar(true));
  document.getElementById("sidebar-close")?.addEventListener("click", () => openSidebar(false));
  overlay.addEventListener("click", () => openSidebar(false));

  document.getElementById("user-name").textContent = user.fullName || user.username;
  const avatarInitial = (user.fullName || user.username || "U").trim().charAt(0).toUpperCase();
  document.getElementById("user-avatar").textContent = avatarInitial;
  const roleNames = (user.roles || []).map(MenuConfig.getRoleDisplayName);
  document.getElementById("user-role").textContent = roleNames.join(", ");

  document.querySelectorAll("#btn-logout, #btn-logout-topbar").forEach((btn) =>
    btn.addEventListener("click", Auth.logout),
  );

  // ── Helpers ──
  const escapeHtml = (value) =>
    String(value ?? "").replace(/[&<>'"]/g, (c) =>
      ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", "'": "&#39;", '"': "&quot;" })[c],
    );

  function formatCurrency(amount) {
    if (amount == null) return "—";
    return new Intl.NumberFormat("vi-VN", { style: "currency", currency: "VND" }).format(amount);
  }

  function formatDate(dateStr) {
    if (!dateStr) return "—";
    return new Intl.DateTimeFormat("vi-VN", {
      day: "2-digit",
      month: "2-digit",
      year: "numeric",
    }).format(new Date(dateStr));
  }

  function formatDateTime(dateStr) {
    if (!dateStr) return "—";
    return new Intl.DateTimeFormat("vi-VN", {
      day: "2-digit",
      month: "2-digit",
      year: "numeric",
      hour: "2-digit",
      minute: "2-digit",
    }).format(new Date(dateStr));
  }

  function showToast(message, type = "error") {
    let container = document.getElementById("toast-container");
    if (!container) {
      container = document.createElement("div");
      container.id = "toast-container";
      document.body.appendChild(container);
    }
    const toast = document.createElement("div");
    toast.className = `toast toast--${type}`;
    toast.innerHTML = `<span class="toast__message">${escapeHtml(message)}</span>`;
    container.appendChild(toast);
    requestAnimationFrame(() => toast.classList.add("toast--visible"));
    setTimeout(() => {
      toast.classList.remove("toast--visible");
      toast.addEventListener("transitionend", () => toast.remove());
    }, 4200);
  }

  // ── Reason & status maps ──
  const REASON_MAP = {
    OVER_DEBT_LIMIT: { label: "Vượt hạn mức công nợ", tagClass: "oa-reason-tag--debt" },
    BELOW_FLOOR_PRICE: { label: "Bán dưới giá sàn", tagClass: "oa-reason-tag--price" },
    OVER_QUANTITY_LIMIT: { label: "Vượt hạn mức số lượng", tagClass: "oa-reason-tag--quantity" },
  };

  const STATUS_MAP = {
    PENDING_APPROVAL: { label: "Chờ duyệt", cssClass: "oa-status-badge--pending" },
    APPROVED: { label: "Đã duyệt", cssClass: "oa-status-badge--approved" },
    RECORDED: { label: "Đã duyệt", cssClass: "oa-status-badge--approved" },
    EXPIRED: { label: "Hết hạn giữ chỗ", cssClass: "oa-status-badge--rejected" },
    PICKING: { label: "Đang soạn hàng", cssClass: "oa-status-badge--approved" },
    REJECTED: { label: "Từ chối", cssClass: "oa-status-badge--rejected" },
    RETURNED: { label: "Trả lại sửa", cssClass: "oa-status-badge--returned" },
  };

  // ── State ──
  let currentPage = 0;
  const pageSize = 20;
  let searchTimeout = null;
  let permissionEpoch = 0;
  let listRequest = 0;
  let detailRequest = 0;
  const canAccess = () => Auth.hasPermission("ORDER_APPROVE") && Auth.hasPermission("ORDER_VIEW");

  // ── DOM refs ──
  const tbody = document.getElementById("oa-tbody");
  const paginationEl = document.getElementById("oa-pagination");
  const countText = document.getElementById("oa-count-text");
  const searchInput = document.getElementById("oa-search");
  const searchClear = document.getElementById("oa-search-clear");
  const filterReason = document.getElementById("oa-filter-reason");
  const filterStatus = document.getElementById("oa-filter-status");
  const btnResetFilters = document.getElementById("btn-reset-oa-filters");

  // ── Load orders ──
  async function loadOrders() {
    if (!canAccess()) return;
    const request = ++listRequest, epoch = permissionEpoch;
    tbody.innerHTML = `<tr class="oa-loading-row"><td colspan="8"><div class="oa-loading">Đang tải dữ liệu…</div></td></tr>`;
    paginationEl.hidden = true;

    const params = new URLSearchParams();
    params.set("page", currentPage);
    params.set("size", pageSize);
    params.set("sort", "createdAt,desc");

    const status = filterStatus.value;
    if (status) params.set("status", status);

    const reason = filterReason.value;
    if (reason) params.set("approvalReason", reason);

    const search = searchInput.value.trim();
    if (search) params.set("search", search);

    // Only load orders that need/had approval
    if (!status) params.set("approvalRequired", "true");

    try {
      const response = await Auth.apiFetch(`/orders?${params}`);
      if (!response.ok) throw new Error("Không thể tải danh sách đơn hàng.");
      const data = await response.json();
      if (!canAccess() || epoch !== permissionEpoch || request !== listRequest) return;
      renderTable(data.content || data.data || []);
      renderPagination(data);
      updateCount(data);
    } catch (error) {
      if (!canAccess() || epoch !== permissionEpoch || request !== listRequest) return;
      tbody.innerHTML = `<tr><td colspan="8"><div class="oa-empty">
        <svg viewBox="0 0 24 24" width="40" height="40"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg>
        ${escapeHtml(error.message)}</div></td></tr>`;
      countText.textContent = "Lỗi khi tải dữ liệu";
    }
  }

  function updateCount(data) {
    const total = data.totalElements ?? data.total ?? 0;
    const status = filterStatus.value;
    const statusLabel = status ? (STATUS_MAP[status]?.label || status) : "cần duyệt";
    countText.textContent = `${total} đơn hàng ${statusLabel.toLowerCase()}`;
  }

  // ── Render table ──
  function renderTable(orders) {
    if (!orders.length) {
      tbody.innerHTML = `<tr><td colspan="8"><div class="oa-empty">
        <svg viewBox="0 0 24 24" width="40" height="40"><path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"/><polyline points="22 4 12 14.01 9 11.01"/></svg>
        Không có đơn hàng nào phù hợp.</div></td></tr>`;
      return;
    }

    tbody.innerHTML = orders.map((order) => {
      const reasons = order.approvalReasons || (order.approvalReason ? [order.approvalReason] : []);
      const status = order.status || "PENDING_APPROVAL";
      const statusInfo = STATUS_MAP[status] || STATUS_MAP.PENDING_APPROVAL;
      const isPending = status === "PENDING_APPROVAL";

      return `<tr data-order-id="${escapeHtml(order.id)}">
        <td>
          <button type="button" class="oa-order-code" data-action="detail" data-id="${escapeHtml(order.id)}">${escapeHtml(order.orderCode || order.id)}</button>
        </td>
        <td>
          <span class="oa-dealer-name">${escapeHtml(order.dealerName || "—")}</span>
          <span class="oa-dealer-code">${escapeHtml(order.dealerCode || "")}</span>
        </td>
        <td class="oa-amount">${formatCurrency(order.totalAmount || order.grandTotal)}</td>
        <td>${renderReasonTags(reasons)}</td>
        <td>${renderViolationSummary(order)}</td>
        <td>${formatDate(order.createdAt)}</td>
        <td>
          <span class="oa-status-badge ${statusInfo.cssClass}">
            <span class="oa-status-badge__dot"></span>
            ${escapeHtml(statusInfo.label)}
          </span>
        </td>
        <td>
          ${isPending ? renderActions(order.id) : renderHistoryLink(order.id)}
        </td>
      </tr>`;
    }).join("");
  }

  function renderReasonTags(reasons) {
    const values = Array.isArray(reasons) ? reasons : reasons ? [reasons] : [];
    if (!values.length) return `<span class="oa-reason-tag oa-reason-tag--debt">Chờ xác nhận</span>`;

    return values.map((reason) => {
      const raw = String(reason ?? "").trim();
      const upper = raw.toUpperCase();
      const mapped = REASON_MAP[upper];
      const inferred = mapped || (
        upper.includes("DEBT") || /hạn mức công nợ/i.test(raw)
          ? { label: "Vượt hạn mức công nợ", tagClass: "oa-reason-tag--debt" }
          : upper.includes("FLOOR") || /giá sàn|dưới giá/i.test(raw)
            ? { label: "Bán dưới giá sàn", tagClass: "oa-reason-tag--price" }
            : upper.includes("QUANTITY") || /số lượng/i.test(raw)
              ? { label: "Vượt hạn mức số lượng", tagClass: "oa-reason-tag--quantity" }
              : { label: raw.length > 32 ? `${raw.slice(0, 32)}…` : raw, tagClass: "oa-reason-tag--debt" }
      );
      const title = raw && raw !== inferred.label ? ` title="${escapeHtml(raw)}"` : "";
      return `<span class="oa-reason-tag ${inferred.tagClass}"${title}>${escapeHtml(inferred.label || "Chờ xác nhận")}</span>`;
    }).join(" ");
  }

  function renderViolationSummary(order) {
    const violations = order.violations || [];
    if (!violations.length) {
      // Fall back to basic info
      if (order.debtExceeded) {
        return `<div class="oa-violation-detail">
          <span class="oa-violation-value">${formatCurrency(order.debtExceeded)}</span>
          <span class="oa-violation-label">Vượt hạn mức</span>
        </div>`;
      }
      if (order.priceGap) {
        return `<div class="oa-violation-detail">
          <span class="oa-violation-value">${formatCurrency(order.priceGap)}</span>
          <span class="oa-violation-label">Dưới giá sàn</span>
        </div>`;
      }
      return `<span style="color:var(--ink-500); font-size:.75rem">Xem chi tiết</span>`;
    }
    return violations.map((v) => `<div class="oa-violation-detail" title="${escapeHtml(v.description || "")}">
      <span class="oa-violation-value">${escapeHtml(v.displayValue || v.value || "")}</span>
      <span class="oa-violation-label">${escapeHtml(v.description || "")}</span>
    </div>`).join("");
  }

  function renderActions(orderId) {
    return `<div class="oa-actions-wrap">
      <button type="button" class="oa-action-btn oa-action-btn--approve" data-action="approve" data-id="${escapeHtml(orderId)}" title="Duyệt đơn hàng">
        <svg viewBox="0 0 24 24"><polyline points="20 6 9 17 4 12"/></svg>
        Duyệt
      </button>
      <button type="button" class="oa-action-btn oa-action-btn--reject" data-action="reject" data-id="${escapeHtml(orderId)}" title="Từ chối đơn hàng">
        <svg viewBox="0 0 24 24"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
        Từ chối
      </button>
      <button type="button" class="oa-action-btn oa-action-btn--return" data-action="return" data-id="${escapeHtml(orderId)}" title="Trả lại sửa">
        <svg viewBox="0 0 24 24"><polyline points="1 4 1 10 7 10"/><path d="M3.51 15a9 9 0 1 0 2.13-9.36L1 10"/></svg>
        Trả lại
      </button>
    </div>`;
  }

  function renderHistoryLink(orderId) {
    return `<button type="button" class="oa-action-btn oa-action-btn--approve" data-action="detail" data-id="${escapeHtml(orderId)}" style="background:var(--surface)">
      <svg viewBox="0 0 24 24"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>
      Xem
    </button>`;
  }

  // ── Pagination ──
  function renderPagination(data) {
    const totalPages = data.totalPages ?? Math.ceil((data.total || 0) / pageSize);
    if (totalPages <= 1) {
      paginationEl.hidden = true;
      return;
    }
    paginationEl.hidden = false;

    let html = `<button data-page="${currentPage - 1}" ${currentPage === 0 ? "disabled" : ""}>&laquo;</button>`;

    const maxVisible = 5;
    let start = Math.max(0, currentPage - Math.floor(maxVisible / 2));
    let end = Math.min(totalPages, start + maxVisible);
    if (end - start < maxVisible) start = Math.max(0, end - maxVisible);

    for (let i = start; i < end; i++) {
      html += `<button data-page="${i}" class="${i === currentPage ? "active" : ""}">${i + 1}</button>`;
    }

    html += `<button data-page="${currentPage + 1}" ${currentPage >= totalPages - 1 ? "disabled" : ""}>&raquo;</button>`;
    paginationEl.innerHTML = html;
  }

  paginationEl.addEventListener("click", (e) => {
    const btn = e.target.closest("button[data-page]");
    if (!btn || btn.disabled) return;
    currentPage = parseInt(btn.dataset.page, 10);
    loadOrders();
  });

  // ── Search & filters ──
  searchInput.addEventListener("input", () => {
    searchClear.hidden = !searchInput.value;
    clearTimeout(searchTimeout);
    searchTimeout = setTimeout(() => {
      currentPage = 0;
      loadOrders();
    }, 350);
    updateResetButton();
  });

  searchClear.addEventListener("click", () => {
    searchInput.value = "";
    searchClear.hidden = true;
    currentPage = 0;
    loadOrders();
    updateResetButton();
  });

  filterReason.addEventListener("change", () => {
    currentPage = 0;
    loadOrders();
    updateResetButton();
  });

  filterStatus.addEventListener("change", () => {
    currentPage = 0;
    loadOrders();
    updateResetButton();
  });

  function updateResetButton() {
    const hasFilters = searchInput.value || filterReason.value || filterStatus.value !== "PENDING_APPROVAL";
    btnResetFilters.hidden = !hasFilters;
  }

  btnResetFilters.addEventListener("click", () => {
    searchInput.value = "";
    searchClear.hidden = true;
    filterReason.value = "";
    filterStatus.value = "PENDING_APPROVAL";
    currentPage = 0;
    loadOrders();
    updateResetButton();
  });

  // ── Table action delegation ──
  tbody.addEventListener("click", (e) => {
    if (!canAccess()) return;
    const btn = e.target.closest("[data-action]");
    if (!btn) return;

    const action = btn.dataset.action;
    const orderId = btn.dataset.id;

    switch (action) {
      case "detail":
        openOrderDetail(orderId);
        break;
      case "approve":
        openApproveModal(orderId);
        break;
      case "reject":
        openRejectModal(orderId);
        break;
      case "return":
        openReturnModal(orderId);
        break;
    }
  });

  // ── Order detail modal ──
  const detailModal = document.getElementById("order-detail-modal");

  document.querySelectorAll("[data-close-order-detail]").forEach((btn) =>
    btn.addEventListener("click", () => { detailModal.hidden = true; }),
  );

  async function openOrderDetail(orderId) {
    if (!canAccess()) return;
    const request = ++detailRequest, epoch = permissionEpoch;
    detailModal.hidden = false;
    document.getElementById("order-detail-sub").textContent = "Đang tải…";
    document.getElementById("order-detail-info").innerHTML = "";
    document.getElementById("order-detail-violations").innerHTML = "";
    document.getElementById("order-detail-items").innerHTML = "";
    document.getElementById("order-detail-total").innerHTML = "";
    document.getElementById("order-detail-history").innerHTML = "";

    try {
      const response = await Auth.apiFetch(`/orders/${orderId}`);
      if (!response.ok) throw new Error("Không thể tải chi tiết đơn hàng.");
      const order = await response.json();
      if (!canAccess() || epoch !== permissionEpoch || request !== detailRequest || detailModal.hidden) return;
      const data = order.data || order;
      renderOrderDetail(data);
    } catch (error) {
      if (!canAccess() || epoch !== permissionEpoch || request !== detailRequest || detailModal.hidden) return;
      document.getElementById("order-detail-sub").textContent = error.message;
    }
  }

  function renderOrderDetail(order) {
    document.getElementById("order-detail-sub").textContent = `Đơn hàng ${order.orderCode || order.id}`;

    // Info fields
    document.getElementById("order-detail-info").innerHTML = `
      <div class="order-detail-field">
        <span class="order-detail-field__label">Mã đơn</span>
        <span class="order-detail-field__value">${escapeHtml(order.orderCode || order.id)}</span>
      </div>
      <div class="order-detail-field">
        <span class="order-detail-field__label">Đại lý</span>
        <span class="order-detail-field__value">${escapeHtml(order.dealerName || "—")}</span>
      </div>
      <div class="order-detail-field">
        <span class="order-detail-field__label">Kho xuất</span>
        <span class="order-detail-field__value">${escapeHtml(order.warehouseName || order.warehouseUnitName || "—")}</span>
      </div>
      <div class="order-detail-field">
        <span class="order-detail-field__label">Bảng giá</span>
        <span class="order-detail-field__value">${escapeHtml(order.priceListName || "—")}</span>
      </div>
      <div class="order-detail-field">
        <span class="order-detail-field__label">Ngày tạo</span>
        <span class="order-detail-field__value">${formatDateTime(order.createdAt)}</span>
      </div>
      <div class="order-detail-field">
        <span class="order-detail-field__label">Người tạo</span>
        <span class="order-detail-field__value">${escapeHtml(order.createdBy || "—")}</span>
      </div>
      <div class="order-detail-field">
        <span class="order-detail-field__label">Tồn giữ chỗ (S5-06)</span>
        <span class="order-detail-field__value">
          ${(() => {
            const reservation = OrderReservation.describe(order);
            if (reservation.held) {
              const expireText = order.reservationExpiresAt ? ` (Hạn: ${formatDateTime(order.reservationExpiresAt)})` : "";
              return `<span class="oa-status-badge oa-status-badge--approved">${escapeHtml(reservation.label + expireText)}</span>`;
            }
            return `<span>${escapeHtml(reservation.label)}</span>`;
          })()}
        </span>
      </div>
      ${order.notes ? `<div class="order-detail-field" style="grid-column: 1/-1">
        <span class="order-detail-field__label">Ghi chú</span>
        <span class="order-detail-field__value">${escapeHtml(order.notes)}</span>
      </div>` : ""}
    `;

    // Violations
    const reasons = order.approvalReasons || (order.approvalReason ? [order.approvalReason] : []);
    const violations = order.violations || [];
    if (reasons.length || violations.length) {
      let violationHtml = "<h3>Lý do cần duyệt</h3>";
      if (violations.length) {
        violationHtml += violations.map((v) => {
          const type = v.type || "debt";
          const typeClass = type.includes("DEBT") ? "debt" : type.includes("PRICE") ? "price" : "quantity";
          return `<div class="oa-violation-card oa-violation-card--${typeClass}">
            <div class="oa-violation-card__icon">
              <svg viewBox="0 0 24 24">${typeClass === "debt"
                ? '<path d="M12 2v20M17 5H9.5a3.5 3.5 0 0 0 0 7h5a3.5 3.5 0 0 1 0 7H6"/>'
                : typeClass === "price"
                  ? '<line x1="12" y1="1" x2="12" y2="23"/><path d="M17 5H9.5a3.5 3.5 0 0 0 0 7h5a3.5 3.5 0 0 1 0 7H6"/>'
                  : '<path d="M3 3h18v18H3zM8 12h8"/>'
              }</svg>
            </div>
            <div class="oa-violation-card__text">
              <div class="oa-violation-card__title">${escapeHtml(v.title || REASON_MAP[v.type]?.label || v.type)}</div>
              <div class="oa-violation-card__desc">${escapeHtml(v.description || "")}</div>
            </div>
            <div class="oa-violation-card__value">${escapeHtml(v.displayValue || v.value || "")}</div>
          </div>`;
        }).join("");
      } else {
        violationHtml += reasons.map((r) => {
          const info = REASON_MAP[r] || { label: r };
          return `<div class="oa-violation-card oa-violation-card--debt">
            <div class="oa-violation-card__icon"><svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg></div>
            <div class="oa-violation-card__text">
              <div class="oa-violation-card__title">${escapeHtml(info.label)}</div>
            </div>
          </div>`;
        }).join("");
      }
      document.getElementById("order-detail-violations").innerHTML = violationHtml;
    }

    // Items
    const items = order.items || [];
    document.getElementById("order-detail-items").innerHTML = items.map((item) => {
      const lineTotal = (item.quantity || 0) * (item.unitPrice || 0) * (1 - (item.discountPercent || 0) / 100);
      const isBelowFloor = item.floorPrice && item.unitPrice < item.floorPrice;
      return `<tr>
        <td>${escapeHtml(item.productName || item.productCode || "—")}</td>
        <td>${item.quantity || 0}</td>
        <td class="${isBelowFloor ? "oa-price-below" : ""}">${formatCurrency(item.unitPrice)}</td>
        <td>${item.floorPrice ? formatCurrency(item.floorPrice) : "—"}</td>
        <td>${item.discountPercent ? item.discountPercent + "%" : "—"}</td>
        <td class="oa-amount">${formatCurrency(lineTotal)}</td>
      </tr>`;
    }).join("");

    // Total
    const subtotal = order.subtotal || order.totalAmount || 0;
    const discount = order.totalDiscount || 0;
    const grandTotal = order.grandTotal || order.totalAmount || subtotal;
    document.getElementById("order-detail-total").innerHTML = `
      <div class="order-detail-total__block">
        <div class="order-detail-total__row">
          <span>Tổng tiền hàng</span>
          <span>${formatCurrency(subtotal)}</span>
        </div>
        ${discount ? `<div class="order-detail-total__row">
          <span>Chiết khấu</span>
          <span>-${formatCurrency(discount)}</span>
        </div>` : ""}
        <div class="order-detail-total__row order-detail-total__row--grand">
          <span>Tổng thanh toán</span>
          <span>${formatCurrency(grandTotal)}</span>
        </div>
      </div>
    `;

    // Approval history (immutable)
    const history = order.approvalHistory || [];
    let historyHtml = "<h3>Lịch sử duyệt</h3>";
    if (history.length) {
      historyHtml += '<div class="oa-history-list">';
      historyHtml += history.map((entry) => {
        const actionMap = {
          APPROVED: { label: "Đã duyệt", css: "approved", icon: '<polyline points="20 6 9 17 4 12"/>' },
          REJECTED: { label: "Từ chối", css: "rejected", icon: '<line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>' },
          RETURNED: { label: "Trả lại sửa", css: "returned", icon: '<polyline points="1 4 1 10 7 10"/><path d="M3.51 15a9 9 0 1 0 2.13-9.36L1 10"/>' },
          PENDING_APPROVAL: { label: "Gửi duyệt", css: "pending", icon: '<circle cx="12" cy="12" r="10"/><path d="M12 6v6l4 2"/>' },
        };
        const info = actionMap[entry.action] || actionMap.PENDING_APPROVAL;
        return `<div class="oa-history-item oa-history-item--${info.css}">
          <div class="oa-history-item__icon"><svg viewBox="0 0 24 24">${info.icon}</svg></div>
          <div class="oa-history-item__body">
            <div class="oa-history-item__header">
              <span class="oa-history-item__action">${escapeHtml(info.label)}</span>
              <span class="oa-history-item__time">${formatDateTime(entry.timestamp || entry.createdAt)}</span>
            </div>
            <div class="oa-history-item__user">${escapeHtml(entry.username || entry.performedBy || "—")}</div>
            ${entry.comment ? `<div class="oa-history-item__comment">${escapeHtml(entry.comment)}</div>` : ""}
          </div>
        </div>`;
      }).join("");
      historyHtml += "</div>";
    } else {
      historyHtml += '<div class="oa-history-empty">Chưa có lịch sử duyệt.</div>';
    }
    historyHtml += `<div class="oa-history-notice">
      <svg viewBox="0 0 24 24"><rect x="3" y="11" width="18" height="11" rx="2" ry="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/></svg>
      Lịch sử duyệt không thể sửa hoặc xoá.
    </div>`;
    document.getElementById("order-detail-history").innerHTML = historyHtml;
  }

  // ── Approve modal ──
  const approveModal = document.getElementById("approve-modal");
  const approveForm = document.getElementById("approve-form");

  document.querySelectorAll("[data-close-approve]").forEach((btn) =>
    btn.addEventListener("click", () => { approveModal.hidden = true; }),
  );

  function openApproveModal(orderId) {
    document.getElementById("approve-order-id").value = orderId;
    document.getElementById("approve-modal-sub").textContent = `Đơn hàng #${orderId}`;
    document.getElementById("approve-comment").value = "";
    document.getElementById("approve-form-message").hidden = true;
    document.getElementById("btn-submit-approve").disabled = false;
    document.getElementById("btn-submit-approve").textContent = "Duyệt đơn hàng";
    approveModal.hidden = false;
  }

  approveForm.addEventListener("submit", async (e) => {
    e.preventDefault();
    if (!Auth.hasPermission("ORDER_APPROVE") || !Auth.hasPermission("ORDER_VIEW")) return;
    if (document.getElementById("btn-submit-approve").disabled) return;
    const epoch = permissionEpoch;
    const orderId = document.getElementById("approve-order-id").value;
    const comment = document.getElementById("approve-comment").value.trim();
    const btn = document.getElementById("btn-submit-approve");
    const msgEl = document.getElementById("approve-form-message");

    btn.disabled = true;
    btn.textContent = "Đang xử lý…";
    msgEl.hidden = true;

    try {
      const response = await Auth.apiFetch(`/orders/${orderId}/status`, {
        method: "PATCH",
        body: JSON.stringify({ status: "APPROVED", comment: comment || null }),
      });

      const result = await response.json().catch(() => null);
      if (!canAccess() || epoch !== permissionEpoch) return;
      if (!response.ok || result?.success === false) throw new Error(result?.message || "Không thể duyệt đơn hàng.");

      approveModal.hidden = true;
      showToast(result?.data?.inventoryReserved === true
        ? "Đã duyệt đơn hàng và giữ chỗ tồn kho."
        : (result?.message || "Đã xử lý đơn hàng. Tải lại chi tiết để kiểm tra tồn giữ chỗ."), "success");
      loadOrders();
    } catch (error) {
      if (!canAccess() || epoch !== permissionEpoch) return;
      msgEl.textContent = error.message;
      msgEl.className = "form-message form-message--error";
      msgEl.hidden = false;
    } finally {
      btn.disabled = !canAccess();
      btn.textContent = "Duyệt đơn hàng";
    }
  });

  // ── Reject modal ──
  const rejectModal = document.getElementById("reject-modal");
  const rejectForm = document.getElementById("reject-form");

  document.querySelectorAll("[data-close-reject]").forEach((btn) =>
    btn.addEventListener("click", () => { rejectModal.hidden = true; }),
  );

  function openRejectModal(orderId) {
    document.getElementById("reject-order-id").value = orderId;
    document.getElementById("reject-modal-sub").textContent = `Đơn hàng #${orderId}`;
    document.getElementById("reject-comment").value = "";
    document.getElementById("reject-form-message").hidden = true;
    document.getElementById("btn-submit-reject").disabled = false;
    document.getElementById("btn-submit-reject").textContent = "Từ chối đơn hàng";
    rejectModal.hidden = false;
    document.getElementById("reject-comment").focus();
  }

  rejectForm.addEventListener("submit", async (e) => {
    e.preventDefault();
    if (!canAccess() || document.getElementById("btn-submit-reject").disabled) return;
    const epoch = permissionEpoch;
    const orderId = document.getElementById("reject-order-id").value;
    const comment = document.getElementById("reject-comment").value.trim();
    const btn = document.getElementById("btn-submit-reject");
    const msgEl = document.getElementById("reject-form-message");

    if (!comment) {
      msgEl.textContent = "Vui lòng nhập lý do từ chối.";
      msgEl.className = "form-message form-message--error";
      msgEl.hidden = false;
      document.getElementById("reject-comment").focus();
      return;
    }

    btn.disabled = true;
    btn.textContent = "Đang xử lý…";
    msgEl.hidden = true;

    try {
      const response = await Auth.apiFetch(`/orders/${orderId}/status`, {
        method: "PATCH",
        body: JSON.stringify({ status: "REJECTED", comment }),
      });
      if (!canAccess() || epoch !== permissionEpoch) return;

      if (!response.ok) {
        const err = await response.json().catch(() => null);
        throw new Error(err?.message || "Không thể từ chối đơn hàng.");
      }

      rejectModal.hidden = true;
      showToast("Đã từ chối đơn hàng.", "success");
      loadOrders();
    } catch (error) {
      if (!canAccess() || epoch !== permissionEpoch) return;
      msgEl.textContent = error.message;
      msgEl.className = "form-message form-message--error";
      msgEl.hidden = false;
    } finally {
      btn.disabled = !canAccess();
      btn.textContent = "Từ chối đơn hàng";
    }
  });

  // ── Return modal ──
  const returnModal = document.getElementById("return-modal");
  const returnForm = document.getElementById("return-form");

  document.querySelectorAll("[data-close-return]").forEach((btn) =>
    btn.addEventListener("click", () => { returnModal.hidden = true; }),
  );

  function openReturnModal(orderId) {
    document.getElementById("return-order-id").value = orderId;
    document.getElementById("return-modal-sub").textContent = `Đơn hàng #${orderId}`;
    document.getElementById("return-comment").value = "";
    document.getElementById("return-form-message").hidden = true;
    document.getElementById("btn-submit-return").disabled = false;
    document.getElementById("btn-submit-return").textContent = "Trả lại sửa";
    returnModal.hidden = false;
    document.getElementById("return-comment").focus();
  }

  returnForm.addEventListener("submit", async (e) => {
    e.preventDefault();
    if (!canAccess() || document.getElementById("btn-submit-return").disabled) return;
    const epoch = permissionEpoch;
    const orderId = document.getElementById("return-order-id").value;
    const comment = document.getElementById("return-comment").value.trim();
    const btn = document.getElementById("btn-submit-return");
    const msgEl = document.getElementById("return-form-message");

    if (!comment) {
      msgEl.textContent = "Vui lòng nhập ý kiến chỉnh sửa.";
      msgEl.className = "form-message form-message--error";
      msgEl.hidden = false;
      document.getElementById("return-comment").focus();
      return;
    }

    btn.disabled = true;
    btn.textContent = "Đang xử lý…";
    msgEl.hidden = true;

    try {
      const response = await Auth.apiFetch(`/orders/${orderId}/status`, {
        method: "PATCH",
        body: JSON.stringify({ status: "RETURNED", comment }),
      });
      if (!canAccess() || epoch !== permissionEpoch) return;

      if (!response.ok) {
        const err = await response.json().catch(() => null);
        throw new Error(err?.message || "Không thể trả lại đơn hàng.");
      }

      returnModal.hidden = true;
      showToast("Đã trả lại đơn hàng để chỉnh sửa.", "success");
      loadOrders();
    } catch (error) {
      if (!canAccess() || epoch !== permissionEpoch) return;
      msgEl.textContent = error.message;
      msgEl.className = "form-message form-message--error";
      msgEl.hidden = false;
    } finally {
      btn.disabled = !canAccess();
      btn.textContent = "Trả lại sửa";
    }
  });

  // ── Close modals on overlay click ──
  document.querySelectorAll(".modal-overlay").forEach((overlay) => {
    overlay.addEventListener("click", (e) => {
      if (e.target === overlay) overlay.hidden = true;
    });
  });

  // ── Close modals on Escape ──
  document.addEventListener("keydown", (e) => {
    if (e.key === "Escape") {
      [detailModal, approveModal, rejectModal, returnModal].forEach((m) => {
        if (!m.hidden) m.hidden = true;
      });
    }
  });

  // ── Init ──
  window.addEventListener("auth:permissions-changed", () => {
    permissionEpoch++;
    const allowed = canAccess();
    for (const id of ["btn-submit-approve", "btn-submit-reject", "btn-submit-return"]) {
      const button = document.getElementById(id);
      if (button) { button.disabled = !allowed; button.hidden = !allowed; }
    }
    if (!allowed) {
      [detailModal, approveModal, rejectModal, returnModal].forEach(modal => { modal.hidden = true; });
      document.getElementById("order-detail-info").textContent = "";
      for (const id of ["order-detail-sub", "order-detail-violations", "order-detail-items", "order-detail-total", "order-detail-history", "oa-tbody", "oa-count-text", "oa-pagination"])
        document.getElementById(id).textContent = "";
      paginationEl.hidden = true;
    } else {
      loadOrders();
    }
  });
  loadOrders();
})();
