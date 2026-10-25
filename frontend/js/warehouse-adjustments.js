(function () {
  "use strict";

  const Utils = window.WarehouseAdjustmentUtils;
  if (!Utils) {
    console.error("WarehouseAdjustmentUtils is not loaded.");
    return;
  }

  // State cuc bo trong bo nho phien lam viec (khong luu tru trinh duyet)
  const state = {
    warehouses: [],
    inventoryProducts: [],
    vouchers: [],
    filteredVouchers: [],
    currentVoucher: null,
    createItems: [],
    selectedWarehouseId: null,
    page: 1,
    pageSize: 10,
    filters: {
      warehouseId: "",
      status: "",
      reason: "",
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
    const el = $("adj-error");
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
    const el = $("adj-success");
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

  function showCreateError(msg) {
    const el = $("adj-create-error");
    if (!el) return;
    if (msg) {
      el.textContent = msg;
      el.hidden = false;
    } else {
      el.textContent = "";
      el.hidden = true;
    }
  }

  function showDetailError(msg) {
    const el = $("adj-detail-error");
    if (!el) return;
    if (msg) {
      el.textContent = msg;
      el.hidden = false;
    } else {
      el.textContent = "";
      el.hidden = true;
    }
  }

  // ══════════════════════════════════════════════════
  // KIỂM TRA PHÂN QUYỀN MA TRẬN
  // ══════════════════════════════════════════════════
  function checkPermissions() {
    const canView = Auth.hasRole("ROLE_ADMIN") || Auth.hasPermission("WAREHOUSE_VIEW");
    const canCreate = Auth.hasRole("ROLE_ADMIN") || Auth.hasPermission("WAREHOUSE_ADJUST");
    const canApprove = Auth.hasRole("ROLE_ADMIN") || Auth.hasPermission("WAREHOUSE_APPROVE");

    const accessDeniedEl = $("adj-access-denied");
    const workspaceEl = $("adj-workspace");
    const newBtn = $("adj-new");

    if (!canView) {
      if (accessDeniedEl) accessDeniedEl.hidden = false;
      if (workspaceEl) workspaceEl.hidden = true;
      return false;
    }

    if (accessDeniedEl) accessDeniedEl.hidden = true;
    if (workspaceEl) workspaceEl.hidden = false;

    if (newBtn) {
      newBtn.hidden = !canCreate;
    }

    // Nếu đang mở form tạo mà bị mất quyền WAREHOUSE_ADJUST
    if (!canCreate && $("adj-create") && !$("adj-create").hidden) {
      $("adj-create").hidden = true;
    }

    // Nếu đang xem chi tiết và quyền duyệt thay đổi
    if ($("adj-approval-section")) {
      $("adj-approval-section").hidden = !canApprove;
    }

    return true;
  }

  // ══════════════════════════════════════════════════
  // TẢI DỮ LIỆU TỪ SERVER (KHÔNG DÙNG LOCALSTORAGE)
  // ══════════════════════════════════════════════════
  async function loadWarehouses() {
    try {
      const res = await Auth.apiFetch("/warehouse/warehouses");
      if (res.ok) {
        const body = await res.json();
        state.warehouses = Array.isArray(body.data) ? body.data : [];
        populateWarehouseSelects();
      }
    } catch (e) {
      console.warn("Không tải được danh mục kho:", e);
    }
  }

  function populateWarehouseSelects() {
    const filterSelect = $("adj-filter-warehouse");
    const createSelect = $("adj-create-warehouse");

    if (filterSelect) {
      const currentVal = filterSelect.value;
      filterSelect.innerHTML = '<option value="">Tất cả kho</option>';
      state.warehouses.forEach((w) => {
        const opt = document.createElement("option");
        opt.value = w.id;
        opt.textContent = `${w.code} - ${w.name}`;
        filterSelect.appendChild(opt);
      });
      filterSelect.value = currentVal;
    }

    if (createSelect) {
      const currentVal = createSelect.value;
      createSelect.innerHTML = '<option value="">Chọn kho hàng</option>';
      state.warehouses.forEach((w) => {
        const opt = document.createElement("option");
        opt.value = w.id;
        opt.textContent = `${w.code} - ${w.name}`;
        createSelect.appendChild(opt);
      });
      createSelect.value = currentVal;
    }
  }

  async function loadInventoryForWarehouse(warehouseId) {
    if (!warehouseId) {
      state.inventoryProducts = [];
      populateProductSelect();
      return;
    }

    try {
      const res = await Auth.apiFetch(`/warehouse/inventory?warehouseId=${warehouseId}`);
      if (res.ok) {
        const body = await res.json();
        state.inventoryProducts = Array.isArray(body.data) ? body.data : [];
        populateProductSelect();
      }
    } catch (e) {
      console.warn("Lỗi tải tồn kho sản phẩm:", e);
    }
  }

  function populateProductSelect() {
    const select = $("adj-item-product");
    if (!select) return;

    select.innerHTML = '<option value="">Chọn sản phẩm từ kho</option>';
    state.inventoryProducts.forEach((inv) => {
      const opt = document.createElement("option");
      opt.value = inv.productId;
      const baseQty = inv.availableQuantity != null ? inv.availableQuantity : inv.baseQuantity || 0;
      opt.textContent = `${inv.productCode} - ${inv.productName} (Tồn: ${Utils.formatQuantity(baseQty)} ${inv.baseUnit || ""})`;
      opt.dataset.unit = inv.baseUnit || "";
      opt.dataset.available = baseQty;
      opt.dataset.price = inv.price || 0;
      select.appendChild(opt);
    });

    onProductSelected();
  }

  function onProductSelected() {
    const select = $("adj-item-product");
    const unitInput = $("adj-item-unit");
    const availInput = $("adj-item-available");
    const priceInput = $("adj-item-price");
    const qtyInput = $("adj-item-qty");

    if (!select || !select.value) {
      if (unitInput) unitInput.value = "";
      if (availInput) availInput.value = "";
      if (priceInput) priceInput.value = "";
      if (qtyInput) qtyInput.value = "";
      return;
    }

    const selectedOpt = select.selectedOptions[0];
    if (selectedOpt) {
      if (unitInput) unitInput.value = selectedOpt.dataset.unit || "";
      if (availInput) availInput.value = Utils.formatQuantity(selectedOpt.dataset.available);
      if (priceInput) priceInput.value = selectedOpt.dataset.price || "";
    }
  }

  async function loadAdjustmentVouchers() {
    showError("");
    const rows = $("adj-list-rows");
    if (rows) {
      rows.innerHTML = '<tr><td colspan="8" class="adj-muted" style="text-align: center; padding: 24px;">Đang tải dữ liệu...</td></tr>';
    }

    try {
      const query = new URLSearchParams();
      query.set("type", "ADJUSTMENT");
      if (state.filters.warehouseId) {
        query.set("warehouseId", state.filters.warehouseId);
      }
      if (state.filters.status) {
        query.set("status", state.filters.status);
      }

      const res = await Auth.apiFetch(`/warehouse/vouchers?${query.toString()}`);
      if (!res.ok) {
        throw new Error("Không thể tải danh sách phiếu điều chỉnh (mã phản hồi: " + res.status + ")");
      }

      const body = await res.json();
      const rawVouchers = Array.isArray(body.data) ? body.data : [];

      // Chuẩn hóa và làm giàu dữ liệu phiếu điều chỉnh
      state.vouchers = rawVouchers.map((v) => {
        const parsedReason = Utils.parseAdjustmentReason(v.adjustmentReason || v.note);
        const totalLoss = Utils.calculateTotalLoss(v.items || []);
        let status = (v.status || "PENDING_APPROVAL").toUpperCase();
        if (status === "RECORDED" || status === "CONFIRMED") {
          status = "APPROVED";
        }

        return {
          ...v,
          status,
          reasonCode: parsedReason.reasonCode,
          reasonLabel: parsedReason.reasonLabel,
          reasonDetail: parsedReason.detail,
          totalLoss,
        };
      });

      applyFilters();
    } catch (e) {
      showError(e.message || "Lỗi nạp danh sách phiếu điều chỉnh");
      if (rows) {
        rows.innerHTML = `<tr><td colspan="8" class="adj-error" style="text-align: center;">${escapeHtml(e.message)}</td></tr>`;
      }
    }
  }

  function applyFilters() {
    const { warehouseId, status, reason, keyword } = state.filters;
    const kw = (keyword || "").trim().toLowerCase();

    state.filteredVouchers = state.vouchers.filter((v) => {
      if (warehouseId && String(v.warehouseId) !== String(warehouseId)) return false;
      if (status && v.status !== status) return false;
      if (reason && v.reasonCode !== reason) return false;
      if (kw) {
        const matchCode = (v.voucherCode || "").toLowerCase().includes(kw);
        const matchCreator = (v.createdBy || "").toLowerCase().includes(kw);
        const matchDetail = (v.reasonDetail || "").toLowerCase().includes(kw);
        const matchItems = Array.isArray(v.items) && v.items.some(
          it => (it.productName || "").toLowerCase().includes(kw) || (it.productCode || "").toLowerCase().includes(kw)
        );
        if (!matchCode && !matchCreator && !matchDetail && !matchItems) return false;
      }
      return true;
    });

    state.page = 1;
    renderList();
  }

  function renderList() {
    const tbody = $("adj-list-rows");
    const countEl = $("adj-count");
    if (!tbody) return;

    const total = state.filteredVouchers.length;
    if (countEl) {
      countEl.textContent = `Tổng cộng: ${total} phiếu`;
    }

    if (total === 0) {
      tbody.innerHTML = '<tr><td colspan="8" class="adj-muted" style="text-align: center; padding: 24px;">Không tìm thấy phiếu điều chỉnh nào phù hợp.</td></tr>';
      renderPagination(0);
      return;
    }

    const start = (state.page - 1) * state.pageSize;
    const end = Math.min(start + state.pageSize, total);
    const paged = state.filteredVouchers.slice(start, end);

    let html = "";
    paged.forEach((v) => {
      const statusInfo = Utils.STATUSES[v.status] || {
        label: v.status,
        badgeClass: "badge--draft",
      };

      const dateStr = v.voucherDate || v.recordedAt || "—";
      const formattedDate = dateStr !== "—" ? new Date(dateStr).toLocaleString("vi-VN") : "—";
      const approver = v.status === "APPROVED" ? (v.approvedBy || "Quản lý kho") : (v.status === "REJECTED" ? "Từ chối" : "—");

      let reasonBadgeClass = "adj-reason-tag";
      if (v.reasonCode === "DAMAGED") reasonBadgeClass += " adj-reason-tag--damaged";
      else if (v.reasonCode === "LOST") reasonBadgeClass += " adj-reason-tag--lost";
      else if (v.reasonCode === "EXPIRED") reasonBadgeClass += " adj-reason-tag--expired";

      html += `<tr>
        <td>
          <strong>${escapeHtml(v.voucherCode || `PDC-#${v.id}`)}</strong>
          <small>${escapeHtml(formattedDate)}</small>
        </td>
        <td>${escapeHtml(v.warehouseName || v.warehouseCode || `Kho #${v.warehouseId}`)}</td>
        <td>
          <span class="${reasonBadgeClass}">${escapeHtml(v.reasonLabel)}</span>
          <small title="${escapeHtml(v.reasonDetail)}">${escapeHtml(v.reasonDetail || "")}</small>
        </td>
        <td class="adj-num" style="font-weight: 600; color: var(--danger);">${escapeHtml(Utils.formatMoney(v.totalLoss))}</td>
        <td>${escapeHtml(v.createdBy || "system")}</td>
        <td>${escapeHtml(approver)}</td>
        <td><span class="adj-badge ${statusInfo.badgeClass}">${escapeHtml(statusInfo.label)}</span></td>
        <td>
          <button class="btn btn--secondary btn-view-detail" data-id="${v.id}" type="button" style="padding: 6px 12px; min-height: 36px; font-size: .8rem;">Xem chi tiết</button>
        </td>
      </tr>`;
    });

    tbody.innerHTML = html;
    renderPagination(total);

    // Gắn sự kiện xem chi tiết
    tbody.querySelectorAll(".btn-view-detail").forEach((btn) => {
      btn.addEventListener("click", () => {
        const id = Number(btn.dataset.id);
        const voucher = state.vouchers.find((item) => item.id === id);
        if (voucher) {
          showDetail(voucher);
        }
      });
    });
  }

  function renderPagination(total) {
    const totalPages = Math.ceil(total / state.pageSize) || 1;
    const pageEl = $("adj-page");
    const prevBtn = $("adj-prev");
    const nextBtn = $("adj-next");

    if (pageEl) {
      pageEl.textContent = `Trang ${state.page} / ${totalPages}`;
    }
    if (prevBtn) {
      prevBtn.disabled = state.page <= 1;
    }
    if (nextBtn) {
      nextBtn.disabled = state.page >= totalPages;
    }
  }

  // ══════════════════════════════════════════════════
  // FORM LẬP PHIẾU ĐIỀU CHỈNH
  // ══════════════════════════════════════════════════
  function openCreatePanel() {
    showCreateError("");
    state.createItems = [];
    renderCreateItems();

    const form = $("adj-create-form");
    if (form) form.reset();

    $("adj-create-total-loss").textContent = "0 đ";
    $("adj-create-submit").disabled = true;

    if ($("adj-item-product")) {
      $("adj-item-product").innerHTML = '<option value="">Chọn sản phẩm từ kho</option>';
    }
    onProductSelected();

    $("adj-create").hidden = false;
    $("adj-create-title").focus();
  }

  function closeCreatePanel() {
    $("adj-create").hidden = true;
    showCreateError("");
  }

  function addItemToDraft() {
    showCreateError("");
    const warehouseSelect = $("adj-create-warehouse");
    if (!warehouseSelect || !warehouseSelect.value) {
      showCreateError("Vui lòng chọn kho trước khi thêm sản phẩm.");
      return;
    }

    const prodSelect = $("adj-item-product");
    const qtyInput = $("adj-item-qty");
    const priceInput = $("adj-item-price");
    const unitInput = $("adj-item-unit");

    const productId = Number(prodSelect.value);
    if (!productId) {
      showCreateError("Vui lòng chọn sản phẩm cần điều chỉnh.");
      return;
    }

    const qty = Number(qtyInput.value);
    if (!Number.isFinite(qty) || qty <= 0) {
      showCreateError("Số lượng giảm phải lớn hơn 0.");
      return;
    }

    const opt = prodSelect.selectedOptions[0];
    const available = opt ? Number(opt.dataset.available || 0) : 0;
    if (qty > available) {
      showCreateError(`Số lượng giảm (${qty}) không được vượt quá tồn khả dụng (${available}).`);
      return;
    }

    const price = Number(priceInput.value || 0);
    const unit = unitInput.value || "đơn vị";

    // Kiểm tra trùng SKU
    const existingIndex = state.createItems.findIndex((it) => it.productId === productId);
    if (existingIndex >= 0) {
      showCreateError("Sản phẩm này đã có trong danh sách. Vui lòng xoá dòng cũ nếu muốn chọn lại.");
      return;
    }

    const lineLoss = Utils.calculateLineLoss(qty, price);

    state.createItems.push({
      productId,
      productCode: opt.textContent.split(" - ")[0],
      productName: opt.textContent.split(" (Tồn:")[0],
      unit,
      availableQuantity: available,
      quantity: qty,
      unitPrice: price,
      lossValue: lineLoss,
    });

    renderCreateItems();

    // Reset các ô nhập của dòng sản phẩm
    prodSelect.value = "";
    qtyInput.value = "";
    priceInput.value = "";
    unitInput.value = "";
    $("adj-item-available").value = "";
  }

  function removeCreateItem(index) {
    if (index >= 0 && index < state.createItems.length) {
      state.createItems.splice(index, 1);
      renderCreateItems();
    }
  }

  function renderCreateItems() {
    const tbody = $("adj-create-rows");
    const totalLossEl = $("adj-create-total-loss");
    const submitBtn = $("adj-create-submit");
    if (!tbody) return;

    if (state.createItems.length === 0) {
      tbody.innerHTML = '<tr><td colspan="7" class="adj-muted" style="text-align: center; padding: 18px;">Chưa có mặt hàng nào được chọn.</td></tr>';
      if (totalLossEl) totalLossEl.textContent = "0 đ";
      if (submitBtn) submitBtn.disabled = true;
      return;
    }

    let html = "";
    state.createItems.forEach((item, index) => {
      html += `<tr>
        <td><strong>${escapeHtml(item.productCode)}</strong></td>
        <td>${escapeHtml(item.productName)}</td>
        <td>${escapeHtml(item.unit)}</td>
        <td class="adj-num">${escapeHtml(Utils.formatQuantity(item.quantity))}</td>
        <td class="adj-num">${escapeHtml(Utils.formatMoney(item.unitPrice))}</td>
        <td class="adj-num" style="font-weight: 600; color: var(--danger);">${escapeHtml(Utils.formatMoney(item.lossValue))}</td>
        <td>
          <button type="button" class="btn btn--secondary btn-remove-item" data-index="${index}" style="padding: 4px 8px; font-size: .75rem; min-height: 32px;">Xoá</button>
        </td>
      </tr>`;
    });

    tbody.innerHTML = html;

    const totalLoss = Utils.calculateTotalLoss(state.createItems);
    if (totalLossEl) {
      totalLossEl.textContent = Utils.formatMoney(totalLoss);
    }

    if (submitBtn) {
      const isChecked = $("adj-create-check") && $("adj-create-check").checked;
      submitBtn.disabled = !isChecked || state.createItems.length === 0 || totalLoss <= 0;
    }

    tbody.querySelectorAll(".btn-remove-item").forEach((btn) => {
      btn.addEventListener("click", () => {
        removeCreateItem(Number(btn.dataset.index));
      });
    });
  }

  async function submitCreateAdjustment(event) {
    event.preventDefault();
    showCreateError("");

    const warehouseId = $("adj-create-warehouse").value;
    const reason = $("adj-create-reason").value;
    const reasonDetail = $("adj-create-reason-detail").value.trim();
    const note = $("adj-create-note").value.trim();

    const validation = Utils.validateAdjustmentDraft({
      warehouseId,
      reason,
      reasonDetail,
      items: state.createItems,
    });

    if (!validation.isValid) {
      showCreateError(validation.errors.join(". "));
      return;
    }

    const totalLoss = Utils.calculateTotalLoss(state.createItems);

    const submitBtn = $("adj-create-submit");
    submitBtn.disabled = true;
    submitBtn.textContent = "Đang xử lý...";

    try {
      const payload = {
        warehouseId: Number(warehouseId),
        type: "ADJUSTMENT",
        status: "DRAFT",
        draft: true,
        adjustmentReason: `[${reason}] ${reasonDetail}`,
        note: `Giá trị tổn thất: ${Utils.formatMoney(totalLoss)}${note ? " | " + note : ""}`,
        items: state.createItems.map((it) => ({
          productId: it.productId,
          quantity: it.quantity,
          unit: it.unit,
          price: it.unitPrice,
          lossValue: it.lossValue,
          delta: -Math.abs(Number(it.quantity)),
          note: `Tổn thất: ${Utils.formatMoney(it.lossValue)}`,
        })),
      };

      const res = await Auth.apiFetch("/warehouse/adjustment-vouchers", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload),
      });

      if (!res.ok) {
        const errorBody = await res.json().catch(() => null);
        throw new Error(
          errorBody?.message || "Lỗi khi lập phiếu điều chỉnh trên máy chủ (mã " + res.status + ")"
        );
      }

      showSuccess("Lập phiếu điều chỉnh thành công! Phiếu đã được chuyển sang trạng thái Chờ phê duyệt.");
      closeCreatePanel();
      await loadAdjustmentVouchers();
    } catch (e) {
      showCreateError(e.message || "Không thể lưu phiếu điều chỉnh");
    } finally {
      submitBtn.disabled = false;
      submitBtn.textContent = "Lập phiếu — Chờ phê duyệt";
    }
  }

  // ══════════════════════════════════════════════════
  // XEM CHI TIẾT & PHÊ DUYỆT PHIẾU
  // ══════════════════════════════════════════════════
  function showDetail(voucher) {
    state.currentVoucher = voucher;
    showDetailError("");

    const detailPanel = $("adj-detail");
    const codeEl = $("adj-detail-code");
    const factsEl = $("adj-facts");
    const rowsEl = $("adj-detail-items");
    const approvalSec = $("adj-approval-section");
    const creatorWarning = $("adj-creator-warning");
    const approveBtn = $("adj-btn-approve");
    const rejectBtn = $("adj-btn-reject");
    const checkEl = $("adj-approve-check");

    if (codeEl) codeEl.textContent = voucher.voucherCode || `PDC-#${voucher.id}`;

    const dateStr = voucher.voucherDate || voucher.recordedAt || "—";
    const formattedDate = dateStr !== "—" ? new Date(dateStr).toLocaleString("vi-VN") : "—";
    const statusInfo = Utils.STATUSES[voucher.status] || { label: voucher.status, badgeClass: "badge--draft" };

    factsEl.innerHTML = `
      <div><dt>Kho hàng</dt><dd>${escapeHtml(voucher.warehouseName || `Kho #${voucher.warehouseId}`)}</dd></div>
      <div><dt>Ngày lập</dt><dd>${escapeHtml(formattedDate)}</dd></div>
      <div><dt>Người lập phiếu</dt><dd>${escapeHtml(voucher.createdBy || "system")}</dd></div>
      <div><dt>Trạng thái</dt><dd><span class="adj-badge ${statusInfo.badgeClass}">${escapeHtml(statusInfo.label)}</span></dd></div>
      <div><dt>Lý do điều chỉnh</dt><dd><strong>${escapeHtml(voucher.reasonLabel)}</strong><br><small>${escapeHtml(voucher.reasonDetail || "—")}</small></dd></div>
      <div><dt>Tổng giá trị tổn thất</dt><dd class="adj-loss-highlight">${escapeHtml(Utils.formatMoney(voucher.totalLoss))}</dd></div>
      <div><dt>Người phê duyệt</dt><dd>${escapeHtml(voucher.approvedBy || (voucher.status === "APPROVED" ? "Quản lý kho" : "Chưa duyệt"))}</dd></div>
    `;

    // Render danh sách mặt hàng kèm nút dẫn tới thẻ kho
    const items = voucher.items || [];
    let itemsHtml = "";
    if (items.length === 0) {
      itemsHtml = '<tr><td colspan="7" class="adj-muted" style="text-align: center; padding: 18px;">Không có thông tin chi tiết dòng hàng.</td></tr>';
    } else {
      items.forEach((item) => {
        const lineLoss = item.totalAmount != null
          ? Number(item.totalAmount)
          : Utils.calculateLineLoss(item.quantity, item.price);

        const cardUrl = Utils.getStockCardUrl(item.productId, voucher.warehouseId);

        itemsHtml += `<tr>
          <td><strong>${escapeHtml(item.productCode || `SP-#${item.productId}`)}</strong></td>
          <td>${escapeHtml(item.productName || "—")}</td>
          <td>${escapeHtml(item.unit || item.baseUnit || "đơn vị")}</td>
          <td class="adj-num">${escapeHtml(Utils.formatQuantity(item.quantity || item.baseQuantity))}</td>
          <td class="adj-num">${escapeHtml(Utils.formatMoney(item.price))}</td>
          <td class="adj-num" style="font-weight: 600; color: var(--danger);">${escapeHtml(Utils.formatMoney(lineLoss))}</td>
          <td>
            <a href="${escapeHtml(cardUrl)}" class="btn btn--secondary" target="_blank" style="padding: 4px 10px; font-size: .78rem; text-decoration: none; display: inline-flex; align-items: center; gap: 4px;">
              Xem thẻ kho
            </a>
          </td>
        </tr>`;
      });
    }
    rowsEl.innerHTML = itemsHtml;

    // Phê duyệt: Ràng buộc người lập và người duyệt không được là một
    const currentUser = Auth.getUser ? Auth.getUser() : null;
    const approvalCheck = Utils.canApproveAdjustment(voucher, currentUser, Auth);

    if (approvalSec) {
      // Chỉ hiện khu vực duyệt nếu người dùng có quyền WAREHOUSE_APPROVE và phiếu chưa được xử lý xong
      const canApproveRole = Auth.hasRole("ROLE_ADMIN") || Auth.hasPermission("WAREHOUSE_APPROVE");
      const isPending = voucher.status === "PENDING_APPROVAL" || voucher.status === "DRAFT";

      approvalSec.hidden = !canApproveRole || !isPending;

      if (!approvalSec.hidden) {
        if (checkEl) checkEl.checked = false;
        if ($("adj-reject-reason")) $("adj-reject-reason").value = "";

        if (approvalCheck.isCreator) {
          // Bị chặn do là người lập phiếu!
          if (creatorWarning) creatorWarning.hidden = false;
          if (approveBtn) approveBtn.disabled = true;
          if (rejectBtn) rejectBtn.disabled = true;
          if ($("adj-approve-check-label")) $("adj-approve-check-label").style.opacity = "0.5";
        } else {
          if (creatorWarning) creatorWarning.hidden = true;
          if (approveBtn) approveBtn.disabled = true; // Chờ tick checkbox xác nhận
          if (rejectBtn) rejectBtn.disabled = false;
          if ($("adj-approve-check-label")) $("adj-approve-check-label").style.opacity = "1";
        }
      }
    }

    detailPanel.hidden = false;
    $("adj-detail-title").focus();
  }

  function closeDetailPanel() {
    $("adj-detail").hidden = true;
    state.currentVoucher = null;
    showDetailError("");
  }

  async function approveCurrentVoucher() {
    if (!state.currentVoucher) return;
    showDetailError("");

    const currentUser = Auth.getUser ? Auth.getUser() : null;
    const check = Utils.canApproveAdjustment(state.currentVoucher, currentUser, Auth);
    if (!check.allowed) {
      showDetailError(check.reason);
      return;
    }

    const approveBtn = $("adj-btn-approve");
    approveBtn.disabled = true;
    approveBtn.textContent = "Đang duyệt...";

    try {
      // Ghi sổ phê duyệt điều chỉnh lên backend
      const res = await Auth.apiFetch(`/warehouse/adjustment-vouchers/${state.currentVoucher.id}/approve`, {
        method: "POST",
      });

      if (!res.ok) {
        const errorBody = await res.json().catch(() => null);
        throw new Error(errorBody?.message || "Lỗi khi phê duyệt phiếu (mã " + res.status + ")");
      }

      showSuccess(`Đã phê duyệt phiếu điều chỉnh ${state.currentVoucher.voucherCode || ""} thành công. Tồn kho đã được cập nhật và xuất hiện trên thẻ kho.`);
      closeDetailPanel();
      await loadAdjustmentVouchers();
    } catch (e) {
      showDetailError(e.message || "Lỗi phê duyệt phiếu điều chỉnh");
    } finally {
      approveBtn.disabled = false;
      approveBtn.textContent = "Phê duyệt điều chỉnh";
    }
  }

  async function rejectCurrentVoucher() {
    if (!state.currentVoucher) return;
    showDetailError("");

    const reasonEl = $("adj-reject-reason");
    const rejectReason = reasonEl ? reasonEl.value.trim() : "";
    if (!rejectReason) {
      showDetailError("Bắt buộc nhập lý do từ chối phiếu điều chỉnh.");
      return;
    }

    const rejectBtn = $("adj-btn-reject");
    rejectBtn.disabled = true;
    rejectBtn.textContent = "Đang xử lý...";

    try {
      const res = await Auth.apiFetch(`/warehouse/adjustment-vouchers/${state.currentVoucher.id}/reject`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ reason: rejectReason }),
      });
      if (!res.ok) {
        const errorBody = await res.json().catch(() => null);
        throw new Error(errorBody?.message || `Không thể từ chối phiếu (mã ${res.status})`);
      }

      showSuccess(`Đã từ chối phiếu điều chỉnh ${state.currentVoucher.voucherCode || ""}.`);
      closeDetailPanel();
      await loadAdjustmentVouchers();
    } catch (e) {
      showDetailError(e.message || "Lỗi khi từ chối phiếu");
    } finally {
      rejectBtn.disabled = false;
      rejectBtn.textContent = "Từ chối phiếu";
    }
  }

  // ══════════════════════════════════════════════════
  // KHỞI TẠO VÀ GẮN SỰ KIỆN
  // ══════════════════════════════════════════════════
  async function init() {
    if (!Auth.requireLogin()) return;
    await Auth.ready;

    if (!checkPermissions()) {
      return;
    }

    // Sự kiện thay đổi quyền động từ Auth
    window.addEventListener("auth:permissions-changed", () => {
      checkPermissions();
      loadAdjustmentVouchers();
    });

    // Các nút thao tác chung
    const reloadBtn = $("adj-reload");
    if (reloadBtn) {
      reloadBtn.addEventListener("click", () => {
        loadAdjustmentVouchers();
      });
    }

    const newBtn = $("adj-new");
    if (newBtn) {
      newBtn.addEventListener("click", openCreatePanel);
    }

    const closeCreateBtn = $("adj-create-close");
    if (closeCreateBtn) {
      closeCreateBtn.addEventListener("click", closeCreatePanel);
    }

    const closeDetailBtn = $("adj-detail-close");
    if (closeDetailBtn) {
      closeDetailBtn.addEventListener("click", closeDetailPanel);
    }

    // Bộ lọc
    const filterForm = $("adj-filter");
    if (filterForm) {
      filterForm.addEventListener("submit", (e) => {
        e.preventDefault();
        state.filters.warehouseId = $("adj-filter-warehouse").value;
        state.filters.status = $("adj-filter-status").value;
        state.filters.reason = $("adj-filter-reason").value;
        state.filters.keyword = $("adj-filter-keyword").value;
        applyFilters();
      });
    }

    const resetFilterBtn = $("adj-filter-reset");
    if (resetFilterBtn) {
      resetFilterBtn.addEventListener("click", () => {
        filterForm.reset();
        state.filters = { warehouseId: "", status: "", reason: "", keyword: "" };
        applyFilters();
      });
    }

    // Phân trang
    const prevBtn = $("adj-prev");
    const nextBtn = $("adj-next");
    if (prevBtn) {
      prevBtn.addEventListener("click", () => {
        if (state.page > 1) {
          state.page--;
          renderList();
        }
      });
    }
    if (nextBtn) {
      nextBtn.addEventListener("click", () => {
        const totalPages = Math.ceil(state.filteredVouchers.length / state.pageSize);
        if (state.page < totalPages) {
          state.page++;
          renderList();
        }
      });
    }

    // Sự kiện trên form tạo phiếu
    const createWarehouseSelect = $("adj-create-warehouse");
    if (createWarehouseSelect) {
      createWarehouseSelect.addEventListener("change", (e) => {
        const whId = e.target.value;
        state.createItems = [];
        renderCreateItems();
        loadInventoryForWarehouse(whId);
      });
    }

    const itemProductSelect = $("adj-item-product");
    if (itemProductSelect) {
      itemProductSelect.addEventListener("change", onProductSelected);
    }

    const addItemBtn = $("adj-item-add");
    if (addItemBtn) {
      addItemBtn.addEventListener("click", addItemToDraft);
    }

    const createCheck = $("adj-create-check");
    if (createCheck) {
      createCheck.addEventListener("change", () => {
        const submitBtn = $("adj-create-submit");
        if (submitBtn) {
          const totalLoss = Utils.calculateTotalLoss(state.createItems);
          submitBtn.disabled = !createCheck.checked || state.createItems.length === 0 || totalLoss <= 0;
        }
      });
    }

    const createForm = $("adj-create-form");
    if (createForm) {
      createForm.addEventListener("submit", submitCreateAdjustment);
    }

    // Sự kiện phê duyệt
    const approveCheck = $("adj-approve-check");
    if (approveCheck) {
      approveCheck.addEventListener("change", () => {
        const approveBtn = $("adj-btn-approve");
        if (approveBtn && state.currentVoucher) {
          const currentUser = Auth.getUser ? Auth.getUser() : null;
          const check = Utils.canApproveAdjustment(state.currentVoucher, currentUser, Auth);
          approveBtn.disabled = !approveCheck.checked || !check.allowed;
        }
      });
    }

    const approveBtn = $("adj-btn-approve");
    if (approveBtn) {
      approveBtn.addEventListener("click", approveCurrentVoucher);
    }

    const rejectBtn = $("adj-btn-reject");
    if (rejectBtn) {
      rejectBtn.addEventListener("click", rejectCurrentVoucher);
    }

    // Tải dữ liệu ban đầu
    await loadWarehouses();
    await loadAdjustmentVouchers();
  }

  document.addEventListener("DOMContentLoaded", init);
})();
