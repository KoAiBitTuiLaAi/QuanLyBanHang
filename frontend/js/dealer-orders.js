/**
 * dealer-orders.js — S5-01 Theo dõi trạng thái đơn hàng của đại lý.
 *
 * Chỉ dùng API của cổng đại lý; việc giới hạn dữ liệu theo dealer_id do backend
 * thực hiện tại /portal/orders và /portal/orders/{id}.
 */
(() => {
  if (!Auth.requireLogin()) return;

  const user = Auth.getUser();
  if (!Auth.hasAnyRole("ROLE_ADMIN", "ROLE_DEALER")) {
    sessionStorage.setItem("errorMessage", "Bạn không có quyền truy cập cổng đại lý.");
    window.location.href = "/error?status=403";
    return;
  }

  const $ = (id) => document.getElementById(id);
  const state = { orders: [], filtered: [], selectedOrder: null };
  const statusMap = {
    DRAFT: { label: "Nháp", className: "neutral" },
    PENDING_APPROVAL: { label: "Chờ duyệt", className: "pending" },
    APPROVED: { label: "Đã duyệt", className: "active" },
    RESERVED: { label: "Đã giữ hàng", className: "active" },
    RECORDED: { label: "Đã ghi sổ", className: "active" },
    SHIPPING: { label: "Đang giao", className: "active" },
    DELIVERED: { label: "Đã giao", className: "done" },
    COMPLETED: { label: "Hoàn tất", className: "done" },
    CANCELLED: { label: "Đã huỷ", className: "danger" },
    REJECTED: { label: "Từ chối", className: "danger" },
    RETURNED: { label: "Cần chỉnh sửa", className: "pending" },
  };

  const escapeHtml = (value) => String(value ?? "").replace(/[&<>'"]/g, (char) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", "'": "&#39;", '"': "&quot;",
  })[char]);

  const formatCurrency = (value) => value == null ? "—" : new Intl.NumberFormat("vi-VN", {
    style: "currency", currency: "VND", maximumFractionDigits: 0,
  }).format(Number(value));

  const formatDate = (value) => {
    if (!value) return "Chưa xác định";
    const date = new Date(String(value).length === 10 ? `${value}T00:00:00` : value);
    return Number.isNaN(date.getTime()) ? "Chưa xác định" : new Intl.DateTimeFormat("vi-VN", {
      day: "2-digit", month: "2-digit", year: "numeric",
    }).format(date);
  };

  const formatQuantity = (value) => {
    if (value == null) return "Chưa cập nhật";
    return new Intl.NumberFormat("vi-VN", { maximumFractionDigits: 4 }).format(Number(value));
  };

  const unwrap = (payload) => payload?.data ?? payload?.result ?? payload;
  const getOrders = (payload) => {
    const data = unwrap(payload);
    if (Array.isArray(data)) return data;
    return data?.content || data?.orders || data?.items || [];
  };

  function setupShell() {
    const sidebar = $("sidebar");
    const overlay = $("sidebar-overlay");
    const toggleSidebar = (open) => {
      sidebar?.classList.toggle("sidebar--open", open);
      overlay?.classList.toggle("sidebar-overlay--visible", open);
    };
    $("sidebar-toggle")?.addEventListener("click", () => toggleSidebar(true));
    $("sidebar-close")?.addEventListener("click", () => toggleSidebar(false));
    overlay?.addEventListener("click", () => toggleSidebar(false));
    $("user-name").textContent = user.fullName || user.username || "Đại lý";
    $("user-avatar").textContent = (user.fullName || user.username || "U").trim().charAt(0).toUpperCase();
    $("user-role").textContent = (user.roles || []).map(MenuConfig.getRoleDisplayName).join(", ");
    document.querySelectorAll("#btn-logout, #btn-logout-topbar").forEach((button) => button.addEventListener("click", Auth.logout));
  }

  function statusInfo(status) {
    const normalized = String(status || "").toUpperCase();
    return statusMap[normalized] || { label: normalized ? normalized.replaceAll("_", " ") : "Chưa xác định", className: "neutral" };
  }

  function renderStats() {
    const total = state.orders.length;
    const delivered = state.orders.filter((order) => ["DELIVERED", "COMPLETED"].includes(String(order.status).toUpperCase())).length;
    const shortage = state.orders.filter((order) => order.hasShortage || (order.items || []).some((item) => item.isShortage)).length;
    const pending = state.orders.filter((order) => !["DELIVERED", "COMPLETED", "CANCELLED", "REJECTED"].includes(String(order.status).toUpperCase())).length;
    $("stat-total").textContent = total;
    $("stat-pending").textContent = pending;
    $("stat-delivered").textContent = delivered;
    $("stat-shortage").textContent = shortage;
  }

  function renderRows() {
    const body = $("dealer-orders-body");
    if (!state.filtered.length) {
      body.innerHTML = `<tr><td colspan="6"><div class="dealer-orders-empty">Không tìm thấy đơn hàng phù hợp.</div></td></tr>`;
      return;
    }
    body.innerHTML = state.filtered.map((order) => {
      const info = statusInfo(order.status);
      const hasShortage = Boolean(order.hasShortage) || (order.items || []).some((item) => item.isShortage);
      const updated = order.actualDeliveryDate || order.createdAt || order.recordedAt;
      return `<tr>
        <td><button class="dealer-order-code" type="button" data-order-id="${escapeHtml(order.id)}">${escapeHtml(order.orderCode || `Đơn #${order.id}`)}</button><span class="dealer-order-meta">${order.totalLineItems ?? (order.items || []).length} dòng hàng</span></td>
        <td><span class="dealer-order-status dealer-order-status--${info.className}">${escapeHtml(info.label)}</span>${hasShortage ? '<span class="dealer-order-shortage">Có giao thiếu</span>' : ""}</td>
        <td>${escapeHtml(formatDate(order.expectedDeliveryDate))}</td>
        <td class="dealer-order-amount">${escapeHtml(formatCurrency(order.totalAmount))}</td>
        <td>${escapeHtml(formatDate(updated))}</td>
        <td><button class="dealer-order-detail-btn" type="button" data-order-id="${escapeHtml(order.id)}">Xem chi tiết</button></td>
      </tr>`;
    }).join("");
  }

  function applyFilters() {
    const search = $("orders-search").value.trim().toLowerCase();
    const status = $("orders-status-filter").value;
    state.filtered = state.orders.filter((order) => {
      const code = String(order.orderCode || order.id || "").toLowerCase();
      return (!search || code.includes(search)) && (!status || String(order.status || "").toUpperCase() === status);
    });
    $("orders-summary").textContent = `${state.filtered.length} / ${state.orders.length} đơn hàng của bạn`;
    renderRows();
  }

  async function loadOrders() {
    const body = $("dealer-orders-body");
    body.innerHTML = '<tr><td colspan="6"><div class="dealer-orders-loading">Đang tải danh sách đơn hàng…</div></td></tr>';
    $("dealer-orders-message").hidden = true;
    try {
      const response = await Auth.apiFetch("/portal/orders");
      const payload = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(payload?.message || "Không thể tải danh sách đơn hàng.");
      state.orders = getOrders(payload);
      renderStats();
      applyFilters();
    } catch (error) {
      state.orders = [];
      renderStats();
      $("orders-summary").textContent = "Chưa tải được dữ liệu";
      body.innerHTML = '<tr><td colspan="6"><div class="dealer-orders-empty">Không thể tải danh sách đơn hàng.</div></td></tr>';
      const message = $("dealer-orders-message");
      message.textContent = error.message || "Vui lòng thử lại sau.";
      message.hidden = false;
    }
  }

  function renderDetail(order) {
    const info = statusInfo(order.status);
    const items = Array.isArray(order.items) ? order.items : [];
    $("dealer-order-detail-title").textContent = order.orderCode || `Đơn #${order.id}`;
    $("dealer-order-detail-content").innerHTML = `
      <div class="dealer-order-overview">
        <div class="dealer-order-overview__item"><span class="dealer-order-overview__label">Trạng thái</span><span class="dealer-order-status dealer-order-status--${info.className}">${escapeHtml(info.label)}</span></div>
        <div class="dealer-order-overview__item"><span class="dealer-order-overview__label">Ngày giao dự kiến</span><span class="dealer-order-overview__value">${escapeHtml(formatDate(order.expectedDeliveryDate))}</span></div>
        <div class="dealer-order-overview__item"><span class="dealer-order-overview__label">Ngày giao thực tế</span><span class="dealer-order-overview__value">${escapeHtml(formatDate(order.actualDeliveryDate))}</span></div>
        <div class="dealer-order-overview__item"><span class="dealer-order-overview__label">Tổng tiền</span><span class="dealer-order-overview__value">${escapeHtml(formatCurrency(order.totalAmount))}</span></div>
      </div>
      ${order.deliveryNote || order.note ? `<p class="dealer-order-note"><strong>Ghi chú:</strong> ${escapeHtml(order.deliveryNote || order.note)}</p>` : ""}
      <div class="dealer-order-items-wrap"><table class="dealer-order-items"><thead><tr><th>Sản phẩm</th><th>Đơn vị</th><th>Đặt</th><th>Thực giao</th><th>Thành tiền</th></tr></thead><tbody>
        ${items.length ? items.map((item) => `<tr><td><strong>${escapeHtml(item.productName || "Sản phẩm")}</strong><span class="dealer-order-meta">${escapeHtml(item.productCode || "")}</span>${item.deliveryNote ? `<span class="dealer-order-items__missing">${escapeHtml(item.deliveryNote)}</span>` : ""}</td><td>${escapeHtml(item.unit || item.baseUnit || "—")}</td><td>${escapeHtml(formatQuantity(item.quantity))}</td><td>${escapeHtml(formatQuantity(item.deliveredQuantity))}${item.isShortage ? `<span class="dealer-order-items__missing">Thiếu ${escapeHtml(formatQuantity(item.shortageQuantity))}</span>` : ""}</td><td>${escapeHtml(formatCurrency(item.totalAmount))}</td></tr>`).join("") : '<tr><td colspan="5">Đơn hàng chưa có dòng hàng.</td></tr>'}
      </tbody></table></div>
      <div class="dealer-order-total"><span>Tổng cộng</span><strong>${escapeHtml(formatCurrency(order.totalAmount))}</strong></div>`;
  }

  async function openDetail(id) {
    const modal = $("dealer-order-detail-modal");
    modal.hidden = false;
    $("dealer-order-detail-title").textContent = "Đang tải…";
    $("dealer-order-detail-content").innerHTML = '<div class="dealer-orders-loading">Đang tải chi tiết đơn hàng…</div>';
    try {
      const response = await Auth.apiFetch(`/portal/orders/${encodeURIComponent(id)}`);
      const payload = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(payload?.message || "Không thể tải chi tiết đơn hàng.");
      state.selectedOrder = unwrap(payload);
      renderDetail(state.selectedOrder);
    } catch (error) {
      $("dealer-order-detail-content").innerHTML = `<div class="dealer-orders-empty">${escapeHtml(error.message || "Không thể tải chi tiết đơn hàng.")}</div>`;
    }
  }

  function closeDetail() { $("dealer-order-detail-modal").hidden = true; state.selectedOrder = null; }

  setupShell();
  $("orders-search").addEventListener("input", applyFilters);
  $("orders-status-filter").addEventListener("change", applyFilters);
  $("btn-refresh-orders").addEventListener("click", loadOrders);
  $("dealer-orders-body").addEventListener("click", (event) => {
    const button = event.target.closest("[data-order-id]");
    if (button) openDetail(button.dataset.orderId);
  });
  $("close-dealer-order-detail").addEventListener("click", closeDetail);
  $("dealer-order-detail-modal").addEventListener("click", (event) => { if (event.target === event.currentTarget) closeDetail(); });
  document.addEventListener("keydown", (event) => { if (event.key === "Escape" && !$("dealer-order-detail-modal").hidden) closeDetail(); });
  loadOrders();
})();
