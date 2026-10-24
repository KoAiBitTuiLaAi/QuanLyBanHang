/**
 * dealer-orders.js — S5-01 Theo dõi trạng thái đơn hàng của đại lý.
 * S5-02: Đặt lại một đơn đã mua trước đây theo bảng giá hiện hành,
 * loại bỏ mặt hàng đã ngừng kinh doanh kèm thông báo, cho phép chọn toàn bộ hoặc một phần dòng hàng.
 * S6-09: Theo dõi giao hàng chỉ đọc, dựa trên dữ liệu API cổng đại lý sẵn có.
 */
(async () => {
  if (typeof Auth !== "undefined" && !Auth.requireLogin()) return;
  if (typeof Auth !== "undefined") await Auth.ready;

  const user = typeof Auth !== "undefined" ? Auth.getUser() : null;
  if (typeof Auth !== "undefined" && (!Auth.hasAnyRole("ROLE_ADMIN", "ROLE_DEALER") || !Auth.hasPermission("ORDER_VIEW"))) {
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

  let ordersVersion = 0;
  let detailVersion = 0;
  let reorderVersion = 0;
  let reorderBusy = false;
  let detailRequest = null;
  let selectedDetailId = null;
  let returnDetailFocus = null;

  const statusMap = {
    DRAFT: { label: "Nháp", className: "neutral" },
    PENDING_APPROVAL: { label: "Chờ duyệt", className: "pending" },
    APPROVED: { label: "Đã duyệt", className: "active" },
    RESERVED: { label: "Đã giữ hàng", className: "active" },
    RECORDED: { label: "Đã duyệt", className: "active" },
    PICKING: { label: "Đang soạn", className: "active" },
    PROCESSING: { label: "Đang soạn", className: "active" },
    EXPORTED: { label: "Đã xuất kho", className: "active" },
    SHIPPED: { label: "Đã xuất kho", className: "active" },
    SHIPPING: { label: "Đang giao", className: "active" },
    DELIVERED: { label: "Đã giao", className: "done" },
    COMPLETED: { label: "Hoàn tất", className: "done" },
    CLOSED: { label: "Đã đóng", className: "done" },
    EXPIRED: { label: "Hết hạn giữ chỗ", className: "danger" },
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

  /**
   * Kiểm tra quyền đặt lại đơn hàng (ORDER_CREATE).
   * API đặt lại áp giá từ database, không yêu cầu quyền đọc danh mục riêng.
   */
  function canReorder() {
    return Auth.hasPermission('ORDER_VIEW') && Auth.hasPermission('ORDER_CREATE');
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
      ["DELIVERED", "COMPLETED", "CLOSED"].includes(String(order.status).toUpperCase())
    ).length;
    const shortage = state.orders.filter(
      (order) => order.hasShortage || (order.items || []).some((item) => item.isShortage)
    ).length;
    const pending = state.orders.filter(
      (order) => !["DELIVERED", "COMPLETED", "CLOSED", "EXPIRED", "CANCELLED", "REJECTED"].includes(String(order.status).toUpperCase())
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
          <td><button class="dealer-order-code" type="button" data-order-id="${escapeHtml(order.id)}">${escapeHtml(order.orderCode || `Đơn #${order.id}`)}</button><span class="dealer-order-meta">${escapeHtml(order.totalLineItems ?? (order.items || []).length)} dòng hàng</span></td>
          <td><span class="dealer-order-status dealer-order-status--${info.className}">${escapeHtml(info.label)}</span>${hasShortage ? '<span class="dealer-order-shortage">Có giao thiếu</span>' : ""}</td>
          <td>${escapeHtml(formatDate(order.expectedDeliveryDate))}</td>
          <td class="dealer-order-amount">${escapeHtml(formatCurrency(order.totalAmount))}</td>
          <td>${escapeHtml(formatDate(updated))}</td>
          <td>
            <div class="dealer-orders-actions">
              <button class="dealer-order-detail-btn" type="button" data-order-id="${escapeHtml(order.id)}">Xem chi tiết</button>
              <button class="dealer-order-detail-btn" type="button" data-track-order-id="${escapeHtml(order.id)}">Theo dõi giao hàng</button>
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
    const version = ++ordersVersion;
    if (!Auth.hasPermission('ORDER_VIEW')) return;
    if (DealerPortalContext.isAdmin && !$('dealer-orders-select')?.value) return;
    const body = $("dealer-orders-body");
    if (body) {
      body.innerHTML = '<tr><td colspan="6"><div class="dealer-orders-loading">Đang tải danh sách đơn hàng…</div></td></tr>';
    }
    if ($("dealer-orders-message")) $("dealer-orders-message").hidden = true;
    try {
      const response = await Auth.apiFetch(DealerPortalContext.url('/portal/orders'));
      const payload = await response.json().catch(() => ({}));
      if (version !== ordersVersion || !Auth.hasPermission('ORDER_VIEW')) return;
      if (!response.ok || payload?.success !== true) throw new Error(payload?.message || "Không thể tải danh sách đơn hàng.");
      state.orders = getOrders(payload);
      renderStats();
      applyFilters();
    } catch (error) {
      if (version !== ordersVersion || !Auth.hasPermission('ORDER_VIEW')) return;
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
    const hasPositiveShortage = item => item.isShortage === true
      && ['number', 'string'].includes(typeof item.shortageQuantity)
      && Number.isFinite(Number(item.shortageQuantity)) && Number(item.shortageQuantity) > 0;
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
        ${DealerDeliveryTracking.render(order)}
        ${order.deliveryNote || order.note ? `<p class="dealer-order-note"><strong>Ghi chú:</strong> ${escapeHtml(order.deliveryNote || order.note)}</p>` : ""}
        <h3 id="dealer-delivered-lines-title">Số lượng thực giao từng dòng</h3>
        <p class="dealer-delivery-tracking__lines-note">“Chưa cập nhật” khác với thực giao bằng 0. Chỉ hiển thị số lượng do hệ thống trả về, không lấy số đặt thay cho số thực giao.</p>
        <div class="dealer-order-items-wrap"><table class="dealer-order-items" aria-labelledby="dealer-delivered-lines-title"><thead><tr><th>Sản phẩm</th><th>Đơn vị</th><th>Đặt</th><th>Thực giao</th><th>Thành tiền</th></tr></thead><tbody>
          ${items.length ? items.map((item) => `<tr><td><strong>${escapeHtml(item.productName || "Sản phẩm")}</strong><span class="dealer-order-meta">${escapeHtml(item.productCode || "")}</span>${item.deliveryNote ? `<span class="dealer-order-items__missing">${escapeHtml(item.deliveryNote)}</span>` : ""}</td><td>${escapeHtml(item.unit || item.baseUnit || "—")}</td><td>${escapeHtml(DealerDeliveryTracking.quantity(item.quantity))}</td><td>${escapeHtml(DealerDeliveryTracking.quantity(item.deliveredQuantity))}${hasPositiveShortage(item) ? `<span class="dealer-order-items__missing">Thiếu ${escapeHtml(DealerDeliveryTracking.quantity(item.shortageQuantity))}</span>` : ""}</td><td>${escapeHtml(formatCurrency(item.totalAmount))}</td></tr>`).join("") : '<tr><td colspan="5">Chưa có dữ liệu dòng hàng.</td></tr>'}
        </tbody></table></div>
        <div class="dealer-order-total"><span>Tổng cộng</span><strong>${escapeHtml(formatCurrency(order.totalAmount))}</strong></div>`;
    }

    const reorderBtn = $("btn-reorder-from-detail");
    if (reorderBtn) {
      reorderBtn.hidden = !canReorder();
      reorderBtn.dataset.orderId = String(order.id);
    }
  }

  async function openDetail(id, focusTracking = false) {
    if (!Auth.hasPermission('ORDER_VIEW')) return;
    const version = ++detailVersion;
    detailRequest?.abort();
    detailRequest = new AbortController();
    selectedDetailId = String(id);
    state.selectedOrder = null;
    $('btn-reorder-from-detail').hidden = true;
    $('btn-refresh-dealer-delivery').hidden = false;
    $('btn-refresh-dealer-delivery').disabled = true;
    if ($('dealer-order-detail-modal').hidden) returnDetailFocus = document.activeElement;
    const modal = $("dealer-order-detail-modal");
    if (modal) modal.hidden = false;
    $('close-dealer-order-detail')?.focus?.({preventScroll:true});
    if ($("dealer-order-detail-title")) $("dealer-order-detail-title").textContent = "Đang tải…";
    if ($("dealer-order-detail-content")) {
      $("dealer-order-detail-content").innerHTML = '<div class="dealer-orders-loading" role="status">Đang tải chi tiết đơn hàng…</div>';
    }
    try {
      const response = await Auth.apiFetch(DealerPortalContext.url(`/portal/orders/${encodeURIComponent(id)}`), {method:'GET',cache:'no-store',signal:detailRequest.signal});
      const payload = await response.json().catch(() => ({}));
      if (version !== detailVersion || !Auth.hasPermission('ORDER_VIEW')) return;
      if (!response.ok || payload?.success !== true) throw new Error(payload?.message || "Không thể tải chi tiết đơn hàng.");
      const order = unwrap(payload);
      if (!order || String(order.id) !== String(id) || (order.items != null && !Array.isArray(order.items))) throw new Error('Chi tiết đơn hàng không hợp lệ.');
      state.selectedOrder = order;
      renderDetail(state.selectedOrder);
      if (focusTracking) $('dealer-delivery-tracking-title')?.focus();
    } catch (error) {
      if (error.name === 'AbortError' || version !== detailVersion || !Auth.hasPermission('ORDER_VIEW')) return;
      if ($("dealer-order-detail-content")) {
        $("dealer-order-detail-content").innerHTML = `<div class="dealer-orders-empty" role="alert">${escapeHtml(error.message || "Không thể tải chi tiết đơn hàng.")}</div>`;
      }
    } finally {
      if (version === detailVersion) $('btn-refresh-dealer-delivery').disabled = !Auth.hasPermission('ORDER_VIEW');
    }
  }

  function closeDetail() {
    detailVersion++;
    detailRequest?.abort();
    selectedDetailId = null;
    $('btn-refresh-dealer-delivery').hidden = true;
    $('btn-reorder-from-detail').hidden = true;
    if ($("dealer-order-detail-modal")) $("dealer-order-detail-modal").hidden = true;
    if ($("dealer-order-detail-content")) $("dealer-order-detail-content").innerHTML = '';
    state.selectedOrder = null;
    if (returnDetailFocus?.isConnected) returnDetailFocus.focus();
    returnDetailFocus = null;
  }

  /* ─────────────────────────────────────────────────────────────
   * S5-02: Đặt lại đơn hàng đã mua trước đây (Re-order)
   * ───────────────────────────────────────────────────────────── */

  function closeReorderModal() {
    reorderVersion++;
    const modal = $("dealer-reorder-modal");
    if (modal) modal.hidden = true;
    if ($("dealer-reorder-tbody")) $("dealer-reorder-tbody").innerHTML = '';
    if ($("btn-confirm-reorder")) $("btn-confirm-reorder").disabled = true;
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
      confirmBtn.disabled = selectedCount === 0 || !canReorder() || reorderBusy;
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
              <input type="number" class="reorder-qty-input" data-item-id="${escapeHtml(item.id)}" value="${qty}" min="0.0001" step="0.0001" aria-label="Số lượng đặt" />
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
    if (reorderBusy) return;
    if (!canReorder()) {
      if (typeof Toast !== "undefined") {
        Toast.show("Bạn không có quyền tạo đơn đặt lại.", "warning");
      }
      return;
    }

    const modal = $("dealer-reorder-modal");
    if (!modal) return;
    closeReorderModal();
    const version = ++reorderVersion;
    modal.hidden = false;

    if ($("dealer-reorder-loading")) $("dealer-reorder-loading").hidden = false;
    if ($("dealer-reorder-content")) $("dealer-reorder-content").hidden = true;
    if ($("reorder-source-code")) $("reorder-source-code").textContent = "…";
    if ($("reorder-error-message")) $("reorder-error-message").hidden = true;

    try {
      // Backend chọn dòng còn kinh doanh và áp lại bảng giá/đơn vị hiện hành.
      const sourceResponse = await Auth.apiFetch(DealerPortalContext.url(`/portal/orders/${encodeURIComponent(orderId)}/reorder-preview`));
      const sourcePayload = await sourceResponse.json();
      if (version !== reorderVersion || !canReorder()) return;
      if (!sourceResponse.ok || !sourcePayload.success) {
        throw new Error(sourcePayload.message || 'Không tải được đơn hàng cũ');
      }
      const preview = unwrap(sourcePayload);
      if (!preview || !Array.isArray(preview.availableItems)) {
        throw new Error("Không thể tải thông tin đơn hàng cũ để đặt lại.");
      }
      const sourceOrder = {
        id: preview.sourceOrderId,
        orderCode: preview.sourceOrderCode,
        deliveryPointId: preview.deliveryPointId,
      };

      state.reorder.sourceOrderId = orderId;
      state.reorder.sourceOrder = sourceOrder;
      if ($("reorder-source-code")) {
        $("reorder-source-code").textContent = sourceOrder.orderCode || `Đơn #${sourceOrder.id}`;
      }

      const activeItems = preview.availableItems.map((item) => ({ ...item, id: String(item.sourceItemId) }));
      const discontinuedItems = preview.discontinuedItems || [];
      const selectedIds = new Set(activeItems.map((item) => item.id));
      const quantities = Object.fromEntries(activeItems.map((item) => [item.id, Number(item.quantity)]));

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

        // Dùng mặc định từ server, không tự suy diễn lịch giao ở trình duyệt.
        const dateInput = $("reorder-delivery-date");
        if (dateInput) {
          dateInput.value = preview.defaultExpectedDeliveryDate || '';
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
      if (version !== reorderVersion || !canReorder()) return;
      if ($("dealer-reorder-loading")) {
        $("dealer-reorder-loading").textContent = error.message || "Không thể tải thông tin đặt lại đơn.";
      }
    }
  }

  async function submitReorder() {
    if (!canReorder() || reorderBusy) return;
    const version = reorderVersion;
    const sourceOrderId = state.reorder.sourceOrderId;
    if (!sourceOrderId) return;
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
    if (selectedItems.some((item) => !Number.isFinite(quantities[item.id]) || quantities[item.id] <= 0
        || !/^\d+(?:\.\d{1,4})?$/.test(String(quantities[item.id])))) {
      if (errorEl) { errorEl.textContent = 'Số lượng đặt phải lớn hơn 0 và tối đa 4 số thập phân.'; errorEl.hidden = false; }
      return;
    }
    const confirmBtn = $("btn-confirm-reorder");
    reorderBusy = true;
    if (confirmBtn) confirmBtn.disabled = true;

    const payload = {
      deliveryPointId: sourceOrder?.deliveryPointId || sourceOrder?.deliveryPoint?.id || null,
      expectedDeliveryDate: deliveryDate,
      note: note,
      items: selectedItems.map((item) => ({
        productId: item.productId,
        productCode: item.productCode,
        unit: item.unit,
        quantity: quantities[item.id],
        note: "",
      })),
    };

    try {
      const response = await Auth.apiFetch(DealerPortalContext.url(`/portal/orders/${encodeURIComponent(sourceOrderId)}/reorder`), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
      });

      const resData = await response.json().catch(() => ({}));
      if (version !== reorderVersion || !canReorder()) return;
      if (!response.ok || (resData.success === false && !resData.data)) {
        throw new Error(resData?.message || "Không thể đặt lại đơn hàng. Vui lòng thử lại.");
      }

      const result = unwrap(resData);
      const newOrder = result.order;
      const newCode = newOrder?.orderCode || (newOrder?.id ? `Đơn #${newOrder.id}` : "");

      closeReorderModal();
      closeDetail();

      if (typeof Toast !== "undefined") {
        for (const warning of result.warnings || []) Toast.show(warning, 'warning');
        Toast.show(
          `Đặt lại đơn hàng thành công! ${newCode ? `Mã đơn mới: ${newCode}` : ""}`,
          "success"
        );
      }

      await loadOrders();
    } catch (error) {
      if (version !== reorderVersion || !canReorder()) return;
      if (errorEl) {
        errorEl.textContent = error.message || "Đặt lại đơn hàng thất bại. Vui lòng thử lại.";
        errorEl.hidden = false;
      }
    } finally {
      reorderBusy = false;
      updateReorderSummary();
    }
  }

  // ─────────────────────────────────────────────────────────────
  // Event Listeners
  // ─────────────────────────────────────────────────────────────

  setupShell();

  window.addEventListener('auth:permissions-changed', () => {
    ordersVersion++;
    closeDetail();
    closeReorderModal();
    state.orders = [];
    renderStats();
    applyFilters();
    if (Auth.hasPermission('ORDER_VIEW')) loadOrders();
  });

  $("orders-search")?.addEventListener("input", applyFilters);
  $("orders-status-filter")?.addEventListener("change", applyFilters);
  $("btn-refresh-orders")?.addEventListener("click", loadOrders);

  // Click handler trên bảng đơn hàng (Xem chi tiết hoặc Đặt lại đơn)
  $("dealer-orders-body")?.addEventListener("click", (event) => {
    const trackingBtn = event.target.closest('[data-track-order-id]');
    if (trackingBtn) { openDetail(trackingBtn.dataset.trackOrderId, true); return; }
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
  $('btn-refresh-dealer-delivery')?.addEventListener('click', () => {
    if (selectedDetailId && Auth.hasPermission('ORDER_VIEW')) openDetail(selectedDetailId);
  });
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
      const val = Number(qtyInput.value);
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
    const detailModal = $('dealer-order-detail-modal');
    if (event.key === 'Tab' && detailModal && !detailModal.hidden) {
      const buttons = [...detailModal.querySelectorAll('button:not(:disabled), [href], input:not(:disabled), select:not(:disabled), textarea:not(:disabled), [tabindex="0"]')]
        .filter(element => element.getClientRects().length > 0);
      const first = buttons[0], last = buttons.at(-1);
      if (first && ((event.shiftKey && (document.activeElement === first || !buttons.includes(document.activeElement)))
        || (!event.shiftKey && document.activeElement === last))) {
        event.preventDefault();
        (event.shiftKey ? last : first).focus();
      }
    }
    if (event.key === "Escape") {
      if ($("dealer-reorder-modal") && !$("dealer-reorder-modal").hidden) {
        closeReorderModal();
      } else if ($("dealer-order-detail-modal") && !$("dealer-order-detail-modal").hidden) {
        closeDetail();
      }
    }
  });

  async function init() {
    try {
      if (DealerPortalContext.isAdmin) {
        $('dealer-orders-title').textContent = 'Đơn hàng đại lý';
        $('dealer-orders-intro').textContent = 'Chọn đại lý để theo dõi trạng thái, ngày giao dự kiến và số lượng thực giao.';
        $('dealer-orders-scope').textContent = 'Đơn hàng của đại lý được chọn';
      }
      const ready = await DealerPortalContext.init('dealer-orders-switcher', 'dealer-orders-select');
      if (ready) {
        await loadOrders();
      } else {
        $('orders-summary').textContent = 'Chưa chọn đại lý';
        $('dealer-orders-body').innerHTML = '<tr><td colspan="6"><div class="dealer-orders-empty">Chọn đại lý để xem đơn hàng.</div></td></tr>';
      }
    } catch (error) {
      $('dealer-orders-body').innerHTML = '';
      $('dealer-orders-message').textContent = error.message;
      $('dealer-orders-message').hidden = false;
    }
  }

  init();
})();
