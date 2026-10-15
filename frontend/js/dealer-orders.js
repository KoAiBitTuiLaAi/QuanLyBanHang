/**
 * dealer-orders.js — S5-01 Theo dõi trạng thái đơn hàng của đại lý.
 * S5-02: Đặt lại một đơn đã mua trước đây theo bảng giá hiện hành,
 * loại bỏ mặt hàng đã ngừng kinh doanh kèm thông báo, cho phép chọn toàn bộ hoặc một phần dòng hàng.
 */
(() => {
  if (typeof Auth !== "undefined" && !Auth.requireLogin()) return;

  const user = typeof Auth !== "undefined" ? Auth.getUser() : null;
  if (typeof Auth !== "undefined" && !Auth.hasAnyRole("ROLE_ADMIN", "ROLE_DEALER")) {
    if (typeof sessionStorage !== "undefined") {
      sessionStorage.setItem("errorMessage", "Bạn không có quyền truy cập cổng đại lý.");
    }
    if (typeof window !== "undefined") {
      window.location.href = "/error?status=403";
    }
    return;
  }

  const $ = (id) => (typeof document !== "undefined" ? document.getElementById(id) : null);
  const state = {
    orders: [],
    filtered: [],
    selectedOrder: null,
    reorder: {
      sourceOrderId: null,
      sourceOrder: null,
      activeItems: [],
      discontinuedItems: [],
      selectedIds: new Set(),
      quantities: {},
    },
  };

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

  const escapeHtml = (value) =>
    String(value ?? "").replace(/[&<>'"]/g, (char) => ({
      "&": "&amp;", "<": "&lt;", ">": "&gt;", "'": "&#39;", '"': "&quot;",
    })[char]);

  const formatCurrency = (value) =>
    value == null
      ? "—"
      : new Intl.NumberFormat("vi-VN", {
          style: "currency",
          currency: "VND",
          maximumFractionDigits: 0,
        }).format(Number(value));

  const formatDate = (value) => {
    if (!value) return "Chưa xác định";
    const date = new Date(String(value).length === 10 ? `${value}T00:00:00` : value);
    return Number.isNaN(date.getTime())
      ? "Chưa xác định"
      : new Intl.DateTimeFormat("vi-VN", {
          day: "2-digit",
          month: "2-digit",
          year: "numeric",
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

  const getFutureDateString = (daysAhead = 3) => {
    const d = new Date();
    d.setDate(d.getDate() + daysAhead);
    const pad = (n) => String(n).padStart(2, "0");
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
  };

  /**
   * Kiểm tra quyền đặt lại đơn hàng (ORDER_CREATE).
   * Đại lý hoặc Quản trị viên có quyền ORDER_CREATE mới được thực hiện đặt lại.
   */
  function canReorder() {
    if (typeof Auth === "undefined") return true;
    const u = Auth.getUser();
    if (!u) return false;
    if (Auth.hasRole("ROLE_ADMIN")) return true;
    if (Array.isArray(u.permissions)) {
      return u.permissions.includes("ORDER_CREATE");
    }
    return Auth.hasRole("ROLE_DEALER");
  }

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
    if ($("user-name") && user) {
      $("user-name").textContent = user.fullName || user.username || "Đại lý";
    }
    if ($("user-avatar") && user) {
      $("user-avatar").textContent = (user.fullName || user.username || "U").trim().charAt(0).toUpperCase();
    }
    if ($("user-role") && user) {
      $("user-role").textContent = (user.roles || []).map(MenuConfig?.getRoleDisplayName || String).join(", ");
    }
    document.querySelectorAll("#btn-logout, #btn-logout-topbar").forEach((button) =>
      button.addEventListener("click", Auth.logout)
    );
  }

  function statusInfo(status) {
    const normalized = String(status || "").toUpperCase();
    return (
      statusMap[normalized] || {
        label: normalized ? normalized.replaceAll("_", " ") : "Chưa xác định",
        className: "neutral",
      }
    );
  }

  function renderStats() {
    const total = state.orders.length;
    const delivered = state.orders.filter((order) =>
      ["DELIVERED", "COMPLETED"].includes(String(order.status).toUpperCase())
    ).length;
    const shortage = state.orders.filter(
      (order) => order.hasShortage || (order.items || []).some((item) => item.isShortage)
    ).length;
    const pending = state.orders.filter(
      (order) => !["DELIVERED", "COMPLETED", "CANCELLED", "REJECTED"].includes(String(order.status).toUpperCase())
    ).length;
    if ($("stat-total")) $("stat-total").textContent = total;
    if ($("stat-pending")) $("stat-pending").textContent = pending;
    if ($("stat-delivered")) $("stat-delivered").textContent = delivered;
    if ($("stat-shortage")) $("stat-shortage").textContent = shortage;
  }

  function renderRows() {
    const body = $("dealer-orders-body");
    if (!body) return;
    if (!state.filtered.length) {
      body.innerHTML = `<tr><td colspan="6"><div class="dealer-orders-empty">Không tìm thấy đơn hàng phù hợp.</div></td></tr>`;
      return;
    }
    const allowReorder = canReorder();
    body.innerHTML = state.filtered
      .map((order) => {
        const info = statusInfo(order.status);
        const hasShortage = Boolean(order.hasShortage) || (order.items || []).some((item) => item.isShortage);
        const updated = order.actualDeliveryDate || order.createdAt || order.recordedAt;
        return `<tr>
          <td><button class="dealer-order-code" type="button" data-order-id="${escapeHtml(order.id)}">${escapeHtml(order.orderCode || `Đơn #${order.id}`)}</button><span class="dealer-order-meta">${order.totalLineItems ?? (order.items || []).length} dòng hàng</span></td>
          <td><span class="dealer-order-status dealer-order-status--${info.className}">${escapeHtml(info.label)}</span>${hasShortage ? '<span class="dealer-order-shortage">Có giao thiếu</span>' : ""}</td>
          <td>${escapeHtml(formatDate(order.expectedDeliveryDate))}</td>
          <td class="dealer-order-amount">${escapeHtml(formatCurrency(order.totalAmount))}</td>
          <td>${escapeHtml(formatDate(updated))}</td>
          <td>
            <div class="dealer-orders-actions">
              <button class="dealer-order-detail-btn" type="button" data-order-id="${escapeHtml(order.id)}">Xem chi tiết</button>
              ${
                allowReorder
                  ? `<button class="dealer-order-reorder-btn" type="button" data-reorder-id="${escapeHtml(order.id)}" title="Đặt lại đơn này">
                      <svg viewBox="0 0 24 24" width="13" height="13" aria-hidden="true"><path d="M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8"/><path d="M21 3v5h-5"/><path d="M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16"/><path d="M3 21v-5h5"/></svg>
                      <span>Đặt lại</span>
                    </button>`
                  : ""
              }
            </div>
          </td>
        </tr>`;
      })
      .join("");
  }

  function applyFilters() {
    const search = $("orders-search")?.value?.trim().toLowerCase() || "";
    const status = $("orders-status-filter")?.value || "";
    state.filtered = state.orders.filter((order) => {
      const code = String(order.orderCode || order.id || "").toLowerCase();
      return (!search || code.includes(search)) && (!status || String(order.status || "").toUpperCase() === status);
    });
    if ($("orders-summary")) {
      $("orders-summary").textContent = `${state.filtered.length} / ${state.orders.length} đơn hàng của bạn`;
    }
    renderRows();
  }

  async function loadOrders() {
    const body = $("dealer-orders-body");
    if (body) {
      body.innerHTML = '<tr><td colspan="6"><div class="dealer-orders-loading">Đang tải danh sách đơn hàng…</div></td></tr>';
    }
    if ($("dealer-orders-message")) $("dealer-orders-message").hidden = true;
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
      if ($("orders-summary")) $("orders-summary").textContent = "Chưa tải được dữ liệu";
      if (body) {
        body.innerHTML = '<tr><td colspan="6"><div class="dealer-orders-empty">Không thể tải danh sách đơn hàng.</div></td></tr>';
      }
      const message = $("dealer-orders-message");
      if (message) {
        message.textContent = error.message || "Vui lòng thử lại sau.";
        message.hidden = false;
      }
    }
  }

  function renderDetail(order) {
    const info = statusInfo(order.status);
    const items = Array.isArray(order.items) ? order.items : [];
    if ($("dealer-order-detail-title")) {
      $("dealer-order-detail-title").textContent = order.orderCode || `Đơn #${order.id}`;
    }
    const content = $("dealer-order-detail-content");
    if (content) {
      content.innerHTML = `
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

    const reorderBtn = $("btn-reorder-from-detail");
    if (reorderBtn) {
      reorderBtn.hidden = !canReorder();
      reorderBtn.dataset.orderId = String(order.id);
    }
  }

  async function openDetail(id) {
    const modal = $("dealer-order-detail-modal");
    if (modal) modal.hidden = false;
    if ($("dealer-order-detail-title")) $("dealer-order-detail-title").textContent = "Đang tải…";
    if ($("dealer-order-detail-content")) {
      $("dealer-order-detail-content").innerHTML = '<div class="dealer-orders-loading">Đang tải chi tiết đơn hàng…</div>';
    }
    try {
      const response = await Auth.apiFetch(`/portal/orders/${encodeURIComponent(id)}`);
      const payload = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(payload?.message || "Không thể tải chi tiết đơn hàng.");
      state.selectedOrder = unwrap(payload);
      renderDetail(state.selectedOrder);
    } catch (error) {
      if ($("dealer-order-detail-content")) {
        $("dealer-order-detail-content").innerHTML = `<div class="dealer-orders-empty">${escapeHtml(error.message || "Không thể tải chi tiết đơn hàng.")}</div>`;
      }
    }
  }

  function closeDetail() {
    if ($("dealer-order-detail-modal")) $("dealer-order-detail-modal").hidden = true;
    state.selectedOrder = null;
  }

  /* ─────────────────────────────────────────────────────────────
   * S5-02: Đặt lại đơn hàng đã mua trước đây (Re-order)
   * ───────────────────────────────────────────────────────────── */

  function closeReorderModal() {
    const modal = $("dealer-reorder-modal");
    if (modal) modal.hidden = true;
    state.reorder = {
      sourceOrderId: null,
      sourceOrder: null,
      activeItems: [],
      discontinuedItems: [],
      selectedIds: new Set(),
      quantities: {},
    };
  }

  function updateReorderSummary() {
    const { activeItems, selectedIds, quantities } = state.reorder;
    const selectedCount = selectedIds.size;
    const totalCount = activeItems.length;

    let totalQty = 0;
    let totalAmount = 0;

    for (const item of activeItems) {
      if (selectedIds.has(item.id)) {
        const qty = quantities[item.id] || 0;
        totalQty += qty;
        totalAmount += qty * (item.currentPrice || 0);
      }
    }

    if ($("reorder-selected-count")) $("reorder-selected-count").textContent = selectedCount;
    if ($("reorder-total-count")) $("reorder-total-count").textContent = totalCount;
    if ($("reorder-summary-lines")) $("reorder-summary-lines").textContent = `${selectedCount} dòng`;
    if ($("reorder-summary-qty")) $("reorder-summary-qty").textContent = formatQuantity(totalQty);
    if ($("reorder-summary-total")) $("reorder-summary-total").textContent = formatCurrency(totalAmount);

    const selectAllCheckbox = $("reorder-select-all");
    if (selectAllCheckbox) {
      selectAllCheckbox.checked = selectedCount > 0 && selectedCount === totalCount;
      selectAllCheckbox.indeterminate = selectedCount > 0 && selectedCount < totalCount;
    }

    const confirmBtn = $("btn-confirm-reorder");
    if (confirmBtn) {
      confirmBtn.disabled = selectedCount === 0;
    }
  }

  function renderReorderItems() {
    const tbody = $("dealer-reorder-tbody");
    if (!tbody) return;
    const { activeItems, selectedIds, quantities } = state.reorder;

    if (!activeItems.length) {
      tbody.innerHTML = `<tr><td colspan="6"><div class="dealer-orders-empty">Không có sản phẩm nào khả dụng để đặt lại.</div></td></tr>`;
      return;
    }

    tbody.innerHTML = activeItems
      .map((item) => {
        const isSelected = selectedIds.has(item.id);
        const qty = quantities[item.id] || item.quantity || 1;
        const lineTotal = qty * (item.currentPrice || 0);

        return `<tr class="${isSelected ? "" : "row--unselected"}" data-row-id="${escapeHtml(item.id)}">
          <td class="col-select">
            <input type="checkbox" class="reorder-item-checkbox" data-item-id="${escapeHtml(item.id)}" ${isSelected ? "checked" : ""} aria-label="Chọn ${escapeHtml(item.productName)}" />
          </td>
          <td>
            <span class="dealer-reorder-product-name">${escapeHtml(item.productName)}</span>
            <span class="dealer-reorder-product-code">${escapeHtml(item.productCode || "")}</span>
          </td>
          <td>${escapeHtml(item.unit || "—")}</td>
          <td>
            <span class="dealer-reorder-price-current">${escapeHtml(formatCurrency(item.currentPrice))}</span>
            <span class="dealer-reorder-price-badge">Giá hiện hành</span>
          </td>
          <td>
            <div class="dealer-reorder-qty-stepper">
              <button type="button" class="btn-qty-minus" data-item-id="${escapeHtml(item.id)}" aria-label="Giảm số lượng">-</button>
              <input type="number" class="reorder-qty-input" data-item-id="${escapeHtml(item.id)}" value="${qty}" min="1" step="1" aria-label="Số lượng đặt" />
              <button type="button" class="btn-qty-plus" data-item-id="${escapeHtml(item.id)}" aria-label="Tăng số lượng">+</button>
            </div>
          </td>
          <td class="text-right">
            <strong class="dealer-reorder-line-total" id="line-total-${escapeHtml(item.id)}">${escapeHtml(formatCurrency(lineTotal))}</strong>
          </td>
        </tr>`;
      })
      .join("");
  }

  async function openReorderModal(orderId) {
    if (!canReorder()) {
      if (typeof Toast !== "undefined") {
        Toast.show("Bạn không có quyền tạo đơn đặt lại.", "warning");
      }
      return;
    }

    const modal = $("dealer-reorder-modal");
    if (!modal) return;
    modal.hidden = false;

    if ($("dealer-reorder-loading")) $("dealer-reorder-loading").hidden = false;
    if ($("dealer-reorder-content")) $("dealer-reorder-content").hidden = true;
    if ($("reorder-source-code")) $("reorder-source-code").textContent = "…";
    if ($("reorder-error-message")) $("reorder-error-message").hidden = true;

    try {
      // 1. Tải chi tiết đơn hàng cũ
      let sourceOrder = null;
      try {
        const res = await Auth.apiFetch(`/portal/orders/${encodeURIComponent(orderId)}`);
        const payload = await res.json().catch(() => ({}));
        if (res.ok) sourceOrder = unwrap(payload);
      } catch {}
      if (!sourceOrder) {
        try {
          const res = await Auth.apiFetch(`/orders/${encodeURIComponent(orderId)}`);
          const payload = await res.json().catch(() => ({}));
          if (res.ok) sourceOrder = unwrap(payload);
        } catch {}
      }
      if (!sourceOrder || !Array.isArray(sourceOrder.items)) {
        throw new Error("Không thể tải thông tin đơn hàng cũ để đặt lại.");
      }

      state.reorder.sourceOrderId = orderId;
      state.reorder.sourceOrder = sourceOrder;
      if ($("reorder-source-code")) {
        $("reorder-source-code").textContent = sourceOrder.orderCode || `Đơn #${sourceOrder.id}`;
      }

      // 2. Tải danh mục sản phẩm hiện hành để kiểm tra sản phẩm đã ngừng kinh doanh
      let catalog = [];
      try {
        const res = await Auth.apiFetch("/products");
        const payload = await res.json().catch(() => ({}));
        if (res.ok) catalog = unwrap(payload) || [];
      } catch {}

      const catalogById = new Map();
      const catalogByCode = new Map();
      for (const p of catalog) {
        if (p.id != null) catalogById.set(Number(p.id), p);
        if (p.code) catalogByCode.set(String(p.code).trim().toUpperCase(), p);
        if (p.sku) catalogByCode.set(String(p.sku).trim().toUpperCase(), p);
      }

      // 3. Tải bảng giá hiệu lực hiện hành theo nhóm khách hàng của đại lý
      const customerGroup =
        sourceOrder.customerGroup ||
        sourceOrder.dealer?.customerGroup ||
        user?.customerGroup ||
        "DAI_LY_CAP_1";

      const effectivePriceMap = new Map();
      try {
        const res = await Auth.apiFetch(
          `/price-lists/effective?customerGroup=${encodeURIComponent(customerGroup)}`
        );
        const payload = await res.json().catch(() => ({}));
        if (res.ok && payload.data && Array.isArray(payload.data.items)) {
          for (const pli of payload.data.items) {
            const pid = pli.productId ?? pli.product?.id;
            if (pid != null) {
              effectivePriceMap.set(Number(pid), Number(pli.sellingPrice ?? pli.price));
            }
          }
        }
      } catch {}

      // 4. Phân loại dòng hàng: mặt hàng ngừng kinh doanh vs mặt hàng khả dụng
      const activeItems = [];
      const discontinuedItems = [];
      const selectedIds = new Set();
      const quantities = {};

      sourceOrder.items.forEach((item, index) => {
        const itemId = String(item.id || `item-${index}`);
        const catalogItem =
          (item.productId != null ? catalogById.get(Number(item.productId)) : null) ||
          (item.productCode ? catalogByCode.get(String(item.productCode).trim().toUpperCase()) : null);

        // Kiểm tra ngừng kinh doanh:
        // - Sản phẩm trong catalog có active === false hoặc status === 'INACTIVE'
        // - Hoặc item cũ có cờ active === false / status === 'INACTIVE'
        const isDiscontinued = catalogItem
          ? catalogItem.active === false || String(catalogItem.status).toUpperCase() === "INACTIVE"
          : item.active === false || String(item.status).toUpperCase() === "INACTIVE";

        if (isDiscontinued) {
          discontinuedItems.push({
            id: itemId,
            productCode: item.productCode || catalogItem?.code || "",
            productName: item.productName || catalogItem?.name || "Sản phẩm ngừng kinh doanh",
            unit: item.unit || item.baseUnit || catalogItem?.unit || "—",
          });
        } else {
          // Áp lại theo giá hiện hành:
          // Ưu tiên bảng giá hiệu lực hiện hành; nếu không có thì lấy giá catalog hiện tại
          const pid = item.productId || catalogItem?.id;
          let currentPrice = pid != null && effectivePriceMap.has(Number(pid))
            ? effectivePriceMap.get(Number(pid))
            : (catalogItem?.price != null ? Number(catalogItem.price) : Number(item.price || 0));

          // Tính theo hệ số quy đổi đơn vị nếu có
          if (item.conversionFactor && item.conversionFactor > 1 && effectivePriceMap.has(Number(pid))) {
            currentPrice = currentPrice * Number(item.conversionFactor);
          }

          const qty = Math.max(1, Math.round(Number(item.quantity) || 1));
          const activeItem = {
            id: itemId,
            productId: pid,
            productCode: item.productCode || catalogItem?.code || "",
            productName: item.productName || catalogItem?.name || "Sản phẩm",
            unit: item.unit || item.baseUnit || catalogItem?.unit || "—",
            oldPrice: Number(item.price || 0),
            currentPrice: Number(currentPrice || 0),
            quantity: qty,
          };
          activeItems.push(activeItem);
          selectedIds.add(itemId);
          quantities[itemId] = qty;
        }
      });

      state.reorder.activeItems = activeItems;
      state.reorder.discontinuedItems = discontinuedItems;
      state.reorder.selectedIds = selectedIds;
      state.reorder.quantities = quantities;

      // 5. Hiển thị thông báo mặt hàng đã ngừng kinh doanh
      const discAlert = $("dealer-reorder-discontinued-alert");
      const discList = $("dealer-reorder-discontinued-list");
      if (discAlert && discList) {
        if (discontinuedItems.length > 0) {
          discList.innerHTML = discontinuedItems
            .map(
              (d) =>
                `<li><strong>${escapeHtml(d.productName)}</strong> (${escapeHtml(d.productCode || "—")}) — Đơn vị: ${escapeHtml(d.unit)}</li>`
            )
            .join("");
          discAlert.hidden = false;
        } else {
          discAlert.hidden = true;
        }
      }

      // 6. Kiểm tra nếu tất cả mặt hàng đều đã ngừng kinh doanh
      const emptyAlert = $("dealer-reorder-empty-alert");
      const selectionArea = $("dealer-reorder-selection");
      const confirmBtn = $("btn-confirm-reorder");

      if (activeItems.length === 0) {
        if (emptyAlert) emptyAlert.hidden = false;
        if (selectionArea) selectionArea.hidden = true;
        if (confirmBtn) confirmBtn.disabled = true;
      } else {
        if (emptyAlert) emptyAlert.hidden = true;
        if (selectionArea) selectionArea.hidden = false;
        if (confirmBtn) confirmBtn.disabled = false;

        // Khởi tạo ngày giao dự kiến mong muốn (mặc định hôm nay + 3 ngày, tối thiểu hôm nay)
        const dateInput = $("reorder-delivery-date");
        if (dateInput) {
          const minDate = getFutureDateString(0);
          dateInput.min = minDate;
          dateInput.value = getFutureDateString(3);
        }

        const noteInput = $("reorder-note");
        if (noteInput) {
          noteInput.value = `Đặt lại định kỳ từ đơn ${sourceOrder.orderCode || "#" + sourceOrder.id}`;
        }

        renderReorderItems();
        updateReorderSummary();
      }

      if ($("dealer-reorder-loading")) $("dealer-reorder-loading").hidden = true;
      if ($("dealer-reorder-content")) $("dealer-reorder-content").hidden = false;
    } catch (error) {
      if ($("dealer-reorder-loading")) {
        $("dealer-reorder-loading").textContent = error.message || "Không thể tải thông tin đặt lại đơn.";
      }
    }
  }

  async function submitReorder() {
    const { sourceOrder, activeItems, selectedIds, quantities } = state.reorder;
    const errorEl = $("reorder-error-message");
    if (errorEl) errorEl.hidden = true;

    const selectedItems = activeItems.filter((item) => selectedIds.has(item.id));
    if (!selectedItems.length) {
      if (errorEl) {
        errorEl.textContent = "Vui lòng chọn ít nhất một dòng hàng để đặt lại.";
        errorEl.hidden = false;
      }
      return;
    }

    const deliveryDate = $("reorder-delivery-date")?.value;
    if (!deliveryDate) {
      if (errorEl) {
        errorEl.textContent = "Vui lòng chọn ngày giao dự kiến mong muốn.";
        errorEl.hidden = false;
      }
      return;
    }

    const note = $("reorder-note")?.value?.trim() || "";
    const confirmBtn = $("btn-confirm-reorder");
    if (confirmBtn) confirmBtn.disabled = true;

    const payload = {
      dealerId: sourceOrder?.dealerId || sourceOrder?.dealer?.id || null,
      deliveryPointId: sourceOrder?.deliveryPointId || sourceOrder?.deliveryPoint?.id || null,
      expectedDeliveryDate: deliveryDate,
      note: note,
      items: selectedItems.map((item) => ({
        productId: item.productId,
        productCode: item.productCode,
        unit: item.unit,
        quantity: quantities[item.id] || 1,
        price: item.currentPrice,
        note: "",
      })),
    };

    try {
      // Ưu tiên endpoint cổng đại lý nếu có, fallback sang /api/orders
      let response = await Auth.apiFetch("/portal/orders/reorder", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
      }).catch(() => null);

      if (!response || response.status === 404) {
        response = await Auth.apiFetch("/portal/orders", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(payload),
        }).catch(() => null);
      }

      if (!response || response.status === 404) {
        response = await Auth.apiFetch("/orders", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(payload),
        });
      }

      const resData = await response.json().catch(() => ({}));
      if (!response.ok || (resData.success === false && !resData.data)) {
        throw new Error(resData?.message || "Không thể đặt lại đơn hàng. Vui lòng thử lại.");
      }

      const newOrder = unwrap(resData);
      const newCode = newOrder?.orderCode || (newOrder?.id ? `Đơn #${newOrder.id}` : "");

      closeReorderModal();
      closeDetail();

      if (typeof Toast !== "undefined") {
        Toast.show(
          `Đặt lại đơn hàng thành công! ${newCode ? `Mã đơn mới: ${newCode}` : ""}`,
          "success"
        );
      }

      await loadOrders();
    } catch (error) {
      if (errorEl) {
        errorEl.textContent = error.message || "Đặt lại đơn hàng thất bại. Vui lòng thử lại.";
        errorEl.hidden = false;
      }
    } finally {
      if (confirmBtn) confirmBtn.disabled = false;
    }
  }

  // ─────────────────────────────────────────────────────────────
  // Event Listeners
  // ─────────────────────────────────────────────────────────────

  setupShell();

  $("orders-search")?.addEventListener("input", applyFilters);
  $("orders-status-filter")?.addEventListener("change", applyFilters);
  $("btn-refresh-orders")?.addEventListener("click", loadOrders);

  // Click handler trên bảng đơn hàng (Xem chi tiết hoặc Đặt lại đơn)
  $("dealer-orders-body")?.addEventListener("click", (event) => {
    const reorderBtn = event.target.closest("[data-reorder-id]");
    if (reorderBtn) {
      openReorderModal(reorderBtn.dataset.reorderId);
      return;
    }
    const detailBtn = event.target.closest("[data-order-id]");
    if (detailBtn) {
      openDetail(detailBtn.dataset.orderId);
    }
  });

  // Modal Chi tiết đơn hàng
  $("close-dealer-order-detail")?.addEventListener("click", closeDetail);
  $("btn-close-detail-modal")?.addEventListener("click", closeDetail);
  $("dealer-order-detail-modal")?.addEventListener("click", (event) => {
    if (event.target === event.currentTarget) closeDetail();
  });
  $("btn-reorder-from-detail")?.addEventListener("click", (event) => {
    const orderId = event.currentTarget.dataset.orderId || state.selectedOrder?.id;
    if (orderId) {
      closeDetail();
      openReorderModal(orderId);
    }
  });

  // Modal Đặt lại đơn hàng S5-02
  $("close-dealer-reorder")?.addEventListener("click", closeReorderModal);
  $("btn-close-reorder")?.addEventListener("click", closeReorderModal);
  $("dealer-reorder-modal")?.addEventListener("click", (event) => {
    if (event.target === event.currentTarget) closeReorderModal();
  });

  // Chọn tất cả dòng hàng
  $("reorder-select-all")?.addEventListener("change", (event) => {
    const isChecked = event.target.checked;
    const { activeItems, selectedIds } = state.reorder;
    if (isChecked) {
      for (const item of activeItems) selectedIds.add(item.id);
    } else {
      selectedIds.clear();
    }
    document.querySelectorAll(".reorder-item-checkbox").forEach((cb) => {
      cb.checked = isChecked;
    });
    document.querySelectorAll("#dealer-reorder-tbody tr").forEach((tr) => {
      tr.classList.toggle("row--unselected", !isChecked);
    });
    updateReorderSummary();
  });

  // Checkbox từng dòng hàng và stepper số lượng
  $("dealer-reorder-tbody")?.addEventListener("click", (event) => {
    const minusBtn = event.target.closest(".btn-qty-minus");
    if (minusBtn) {
      const itemId = minusBtn.dataset.itemId;
      const current = Number(state.reorder.quantities[itemId] || 1);
      if (current > 1) {
        state.reorder.quantities[itemId] = current - 1;
        const input = document.querySelector(`.reorder-qty-input[data-item-id="${CSS.escape(itemId)}"]`);
        if (input) input.value = current - 1;
        const item = state.reorder.activeItems.find((i) => i.id === itemId);
        const lineTotalEl = $(`line-total-${itemId}`);
        if (lineTotalEl && item) {
          lineTotalEl.textContent = formatCurrency((current - 1) * item.currentPrice);
        }
        updateReorderSummary();
      }
      return;
    }

    const plusBtn = event.target.closest(".btn-qty-plus");
    if (plusBtn) {
      const itemId = plusBtn.dataset.itemId;
      const current = Number(state.reorder.quantities[itemId] || 1);
      state.reorder.quantities[itemId] = current + 1;
      const input = document.querySelector(`.reorder-qty-input[data-item-id="${CSS.escape(itemId)}"]`);
      if (input) input.value = current + 1;
      const item = state.reorder.activeItems.find((i) => i.id === itemId);
      const lineTotalEl = $(`line-total-${itemId}`);
      if (lineTotalEl && item) {
        lineTotalEl.textContent = formatCurrency((current + 1) * item.currentPrice);
      }
      updateReorderSummary();
    }
  });

  $("dealer-reorder-tbody")?.addEventListener("change", (event) => {
    const cb = event.target.closest(".reorder-item-checkbox");
    if (cb) {
      const itemId = cb.dataset.itemId;
      if (cb.checked) {
        state.reorder.selectedIds.add(itemId);
      } else {
        state.reorder.selectedIds.delete(itemId);
      }
      const tr = cb.closest("tr");
      if (tr) tr.classList.toggle("row--unselected", !cb.checked);
      updateReorderSummary();
      return;
    }

    const qtyInput = event.target.closest(".reorder-qty-input");
    if (qtyInput) {
      const itemId = qtyInput.dataset.itemId;
      let val = Math.max(1, Math.round(Number(qtyInput.value) || 1));
      qtyInput.value = val;
      state.reorder.quantities[itemId] = val;
      const item = state.reorder.activeItems.find((i) => i.id === itemId);
      const lineTotalEl = $(`line-total-${itemId}`);
      if (lineTotalEl && item) {
        lineTotalEl.textContent = formatCurrency(val * item.currentPrice);
      }
      updateReorderSummary();
    }
  });

  $("btn-confirm-reorder")?.addEventListener("click", submitReorder);

  document.addEventListener("keydown", (event) => {
    if (event.key === "Escape") {
      if ($("dealer-reorder-modal") && !$("dealer-reorder-modal").hidden) {
        closeReorderModal();
      } else if ($("dealer-order-detail-modal") && !$("dealer-order-detail-modal").hidden) {
        closeDetail();
      }
    }
  });

  loadOrders();
})();

