/**
 * create-order.js — Tạo đơn hàng, áp giá/chiết khấu và xem lịch sử mua hàng đại lý.
 *
 * Tính năng:
 * - Chọn đại lý (load từ /dealers/active), điểm giao hàng, ngày giao
 * - Thêm dòng hàng: tìm kiếm sản phẩm theo SKU/tên, chọn đơn vị tính & số lượng
 * - Hiển thị tổng tiền hàng, chiết khấu, tổng phải thu theo thời gian thực
 * - Lưu bản nháp vào database qua /orders/draft, mở lại tiếp tục
 * - [S4-04] Hiển thị lịch sử mua hàng 3 tháng gần nhất của đại lý kèm SL bình quân
 * - [S4-04] Nút "Thêm vào đơn" từng sản phẩm và "Thêm tất cả vào đơn"
 * - Responsive xuống 360px
 *
 * API:
 *   GET  /dealers/{id}/purchase-history?months=3  → lịch sử mua 3 tháng
 *   GET  /dealers/active                       → đại lý đang hoạt động
 *   GET  /dealers/{id}/delivery-points         → điểm giao hàng của đại lý
 *   GET  /products/lookup?search={q}&active=true → tìm sản phẩm tham chiếu
 *   GET  /products/{id}/unit-conversions       → đơn vị tính quy đổi
 *   GET  /price-lists/effective                → bảng giá hiệu lực theo nhóm đại lý
 *   POST /orders/calculate                     → áp giá và tính chiết khấu toàn bộ đơn
 *   POST /orders/check-inventory               → kiểm tra tồn khả dụng theo dòng hàng (S4-03)
 *   POST /orders                               → tạo đơn hàng
 */
 (async () => {
  /* ── Auth guard ─────────────────────────────────────────── */
  if (!Auth.requireLogin()) return;
  await Auth.refreshPermissions();

  if (!Auth.hasPermission('ORDER_CREATE')) {
    location.href = '/error?status=403';
    return;
  }

  /* ── DOM helpers ────────────────────────────────────────── */
  const $ = (id) => document.getElementById(id);
  const escHtml = (v) =>
    String(v ?? '').replace(/[&<>'"]/g, (c) =>
      ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', "'": '&#39;', '"': '&quot;' })[c],
    );

  const fmtMoney = (v) =>
    Number(v || 0).toLocaleString('vi-VN', { style: 'currency', currency: 'VND' });

  const todayInputValue = () => {
    const now = new Date();
    const pad = (value) => String(value).padStart(2, '0');
    return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
  };

  /* ── State ──────────────────────────────────────────────── */
  const state = {
    dealers: [],        // list of active dealers
    deliveryPoints: [], // list of delivery points for selected dealer
    selectedDealer: null,
    items: [],          // { productId, sku, name, unitConversions, selectedUnit, qty, unitPrice, floorPrice, manualPrice, discountAmount, inventory }
    productCache: {},   // productId → { unitConversions }
    priceList: null,    // bảng giá hiệu lực theo nhóm đại lý
    priceListError: '', // lý do không thể áp giá cho đại lý hiện tại
    pricingSummary: null, // kết quả tính giá/chiết khấu mới nhất từ backend
    pendingDeliveryPointId: null,
    dealerLoading: false,
    debtInfo: null,           // { currentDebt, debtLimit, maxDebtDays, oldestOverdueDays }
    debtBlocked: false,       // true = đại lý bị chặn tạo đơn hoàn toàn
    debtNeedsApproval: false, // true = đơn sẽ cần duyệt
    debtError: '',
  };

  let pricingRequestId = 0;
  let debtCheckRequestId = 0;
  let debtCheckTimer = null;

  /* ── Layout: Sidebar ─────────────────────────────────────── */
  (function initLayout() {
    const user = Auth.getUser?.() ?? null;
    if (!user) return;

    const initials = (user.fullName || user.username || '?')
      .split(' ')
      .map((w) => w[0])
      .slice(0, 2)
      .join('')
      .toUpperCase();
    $('user-avatar').textContent = initials;
    $('user-name').textContent = user.fullName || user.username;
    $('user-role').textContent = user.roles
      ?.map((r) => MenuConfig.getRoleDisplayName(r))
      .join(', ') ?? '';

    const doLogout = () => Auth.logout?.();
    $('btn-logout')?.addEventListener('click', doLogout);
    $('btn-logout-topbar')?.addEventListener('click', doLogout);

    $('sidebar-toggle')?.addEventListener('click', () => {
      $('sidebar').classList.toggle('sidebar--open');
      $('sidebar-overlay').classList.toggle('sidebar-overlay--visible');
    });
    $('sidebar-close')?.addEventListener('click', () => {
      $('sidebar').classList.remove('sidebar--open');
      $('sidebar-overlay').classList.remove('sidebar-overlay--visible');
    });
    $('sidebar-overlay')?.addEventListener('click', () => {
      $('sidebar').classList.remove('sidebar--open');
      $('sidebar-overlay').classList.remove('sidebar-overlay--visible');
    });
  })();

  /* ── Toast ──────────────────────────────────────────────── */
  function showToast(message, type = 'success') {
    const icons = {
      success: '<path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"/><polyline points="22 4 12 14.01 9 11.01"/>',
      error:   '<circle cx="12" cy="12" r="10"/><line x1="15" y1="9" x2="9" y2="15"/><line x1="9" y1="9" x2="15" y2="15"/>',
      warning: '<path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/>',
    };
    const container = $('toast-container');
    const toast = document.createElement('div');
    toast.className = `toast toast--${type}`;
    toast.innerHTML = `<span class="toast__icon"><svg width="16" height="16" viewBox="0 0 24 24">${icons[type] || icons.success}</svg></span>${escHtml(message)}`;
    container.appendChild(toast);
    requestAnimationFrame(() => toast.classList.add('toast--visible'));
    setTimeout(() => {
      toast.classList.add('toast--exit');
      setTimeout(() => toast.remove(), 300);
    }, 3800);
  }

  /* ── Load dealers ────────────────────────────────────────── */
  async function loadDealers() {
    try {
      const res = await Auth.apiFetch('/dealers/active');
      const result = await res.json();
      if (!res.ok || !result.success) throw new Error(result.message || 'Không tải được danh sách đại lý');
      state.dealers = Array.isArray(result.data) ? result.data : [];
      populateDealerSelect();
    } catch (e) {
      showToast(e.message || 'Lỗi tải danh sách đại lý', 'error');
    }
  }

  function populateDealerSelect() {
    const sel = $('co-dealer');
    sel.innerHTML = '<option value="">— Chọn đại lý —</option>';
    state.dealers.forEach((d) => {
      const opt = document.createElement('option');
      opt.value = d.id;
      opt.textContent = `${d.code} — ${d.name}`;
      opt.dataset.group = d.customerGroup || '';
      opt.dataset.address = d.address || '';
      sel.appendChild(opt);
    });
  }

  /* ── Dealer selection ────────────────────────────────────── */
  $('co-dealer').addEventListener('change', selectDealer);

  async function selectDealer() {
    const val = $('co-dealer').value;
    const dealerData = state.dealers.find((d) => String(d.id) === String(val)) ?? null;
    state.selectedDealer = dealerData;

    // Update info strip
    const infoStrip = $('co-dealer-info');
    if (dealerData) {
      $('co-dealer-group-badge').innerHTML = groupBadge(dealerData.customerGroup);
      $('co-dealer-address').textContent = dealerData.address || '';
      infoStrip.hidden = false;
    } else {
      infoStrip.hidden = true;
      // Hide purchase history when no dealer is selected
      $('co-history-section').hidden = true;
      $('btn-add-all-history').hidden = true;
      historyItems = [];
    }

    // Reset & reload delivery points
    const dpSel = $('co-delivery-point');
    dpSel.innerHTML = '<option value="">— Chọn điểm giao hàng —</option>';
    dpSel.disabled = !dealerData;
    state.deliveryPoints = [];
    state.priceList = null;
    state.debtInfo = null;
    state.debtBlocked = false;
    state.debtNeedsApproval = false;
    debtCheckRequestId += 1;
    clearTimeout(debtCheckTimer);
    $('co-debt-panel').hidden = true;
    state.debtError = '';
    state.priceListError = '';
    state.pricingSummary = null;
    syncBlockedState();
    setDeliveryStatus('');
    state.dealerLoading = true;

    if (dealerData) {
      await Promise.all([
        loadDeliveryPoints(dealerData.id),
        loadEffectivePriceList(dealerData.customerGroup),
        loadDebtInfo(dealerData.id),
        loadPurchaseHistory(dealerData.id),
      ]);
    }
    if (state.selectedDealer?.id !== dealerData?.id) return;
    state.dealerLoading = false;
    syncItemPrices();
    await refreshPricingPreview();
    await refreshInventoryPreview();
  }

  async function loadDeliveryPoints(dealerId) {
    const dpSel = $('co-delivery-point');
    try {
      const res = await Auth.apiFetch(`/dealers/${dealerId}/delivery-points`);
      const result = await res.json().catch(() => null);
      if (state.selectedDealer?.id !== dealerId) return;
      if (!res.ok) throw new Error(result?.message || 'Không tải được điểm giao hàng.');
      const points = Array.isArray(result) ? result : (Array.isArray(result?.data) ? result.data : []);
      state.deliveryPoints = points;
      dpSel.innerHTML = '<option value="">— Chọn điểm giao hàng —</option>';
      if (!points.length) {
        dpSel.innerHTML = '<option value="">— Chưa khai báo điểm giao hàng —</option>';
        return;
      }

      points.forEach((p) => {
        const opt = document.createElement('option');
        opt.value = p.id;
        opt.textContent = `${p.receiverName || `Điểm #${p.id}`} — ${p.address || 'Chưa có địa chỉ'}${p.isDefault ? ' (Mặc định)' : ''}`;
        dpSel.appendChild(opt);
      });
      const defaultPoint = points.find((point) => point.isDefault);
      const requestedPoint = points.find((point) => String(point.id) === String(state.pendingDeliveryPointId));
      if (requestedPoint || defaultPoint) dpSel.value = String((requestedPoint || defaultPoint).id);
      state.pendingDeliveryPointId = null;
      setDeliveryStatus('Có thể chọn điểm giao hàng của đại lý cho đơn này.');
    } catch (error) {
      if (state.selectedDealer?.id !== dealerId) return;
      state.deliveryPoints = [];
      dpSel.innerHTML = '<option value="">— Không tải được điểm giao hàng —</option>';
      dpSel.disabled = true;
      setDeliveryStatus(error.message || 'Không tải được điểm giao hàng.', true);
      showToast(error.message || 'Không tải được điểm giao hàng.', 'error');
    }
  }

  async function loadEffectivePriceList(customerGroup) {
    if (!customerGroup) {
      state.priceListError = 'Đại lý chưa được gán nhóm khách hàng nên chưa thể xác định bảng giá.';
      return;
    }
    try {
      const res = await Auth.apiFetch(`/price-lists/effective?customerGroup=${encodeURIComponent(customerGroup)}`);
      const result = await res.json().catch(() => null);
      if (state.selectedDealer?.customerGroup !== customerGroup) return;
      if (!res.ok || !result.success) {
        state.priceList = null;
        state.priceListError = result?.message || 'Chưa có bảng giá hiệu lực cho nhóm khách hàng này.';
        return;
      }
      state.priceList = result.data || null;
      state.priceListError = state.priceList ? '' : 'Chưa có bảng giá hiệu lực cho nhóm khách hàng này.';
    } catch {
      if (state.selectedDealer?.customerGroup !== customerGroup) return;
      state.priceList = null;
      state.priceListError = 'Không tải được bảng giá hiệu lực. Vui lòng thử lại trước khi thêm hàng.';
    }
  }

  function setDeliveryStatus(message, isError = false) {
    const el = $('co-delivery-status');
    if (!el) return;
    el.textContent = message;
    el.hidden = !message;
    el.classList.toggle('co-field-hint--error', isError);
    el.classList.toggle('co-field-hint--info', !isError && !!message);
  }

  /* ── Debt info (S4-02) ─────────────────────────────────── */

  async function loadDebtInfo(dealerId) {
    try {
      const res = await Auth.apiFetch(`/dealers/${dealerId}/debt`);
      const result = await res.json();
      if (state.selectedDealer?.id !== dealerId) return;
      if (!res.ok || !result.success) {
        throw new Error(result.message || 'Không tải được công nợ. Vui lòng chọn lại đại lý để thử lại.');
      }
      state.debtInfo = result.data || null;
      if (!state.debtInfo) throw new Error('Chưa nhận được dữ liệu công nợ. Vui lòng chọn lại đại lý.');
      state.debtError = '';
      updateDebtPanel();
    } catch (err) {
      if (state.selectedDealer?.id !== dealerId) return;
      state.debtInfo = null;
      state.debtError = err.message || 'Không tải được công nợ. Vui lòng chọn lại đại lý để thử lại.';
      updateDebtPanel();
    }
  }

  function updateDebtPanel({ recalculateApproval = true } = {}) {
    const panel = $('co-debt-panel');
    const alertEl = $('co-debt-alert');
    const info = state.debtInfo;

    if (state.debtError && state.selectedDealer) {
      panel.hidden = false;
      panel.className = 'co-debt-panel co-debt-panel--blocked';
      alertEl.hidden = false;
      alertEl.className = 'co-debt-panel__alert co-debt-panel__alert--blocked';
      alertEl.textContent = state.debtError;
      state.debtBlocked = true;
      syncBlockedState();
      return;
    }

    if (!info || !state.selectedDealer) {
      panel.hidden = true;
      state.debtBlocked = false;
      state.debtNeedsApproval = false;
      syncBlockedState();
      return;
    }

    panel.hidden = false;
    const currentDebt = Number(info.currentDebt || 0);
    const debtLimit = Number(info.debtLimit || 0);

    $('co-debt-current').textContent = fmtMoney(currentDebt);
    $('co-debt-limit').textContent = debtLimit > 0 ? fmtMoney(debtLimit) : 'Chưa khai báo';

    // Hạn mức còn lại = hạn mức - công nợ hiện tại
    const remaining = debtLimit - currentDebt;
    const remainEl = $('co-debt-remaining');
    remainEl.textContent = debtLimit > 0 ? fmtMoney(remaining) : '—';
    remainEl.classList.toggle('co-debt-panel__value--danger', remaining < 0);
    remainEl.classList.toggle('co-debt-panel__value--warning', remaining >= 0 && remaining < debtLimit * 0.2);

    // Quá hạn quá số ngày cho phép → chặn hoàn toàn
    state.debtBlocked = Boolean(info.isBlocked || info.hasOverdueDebt);

    // Khi chưa có kết quả server mới nhất, dùng phép tính tạm thời để phản hồi ngay.
    if (recalculateApproval) checkDebtApproval();

    // Cập nhật thông báo
    if (state.debtBlocked) {
      alertEl.hidden = false;
      alertEl.className = 'co-debt-panel__alert co-debt-panel__alert--blocked';
      alertEl.textContent = info.blockReason || 'Đại lý có khoản nợ quá hạn hoặc đã bị khoá giao dịch. Không thể tạo đơn.';
      panel.className = 'co-debt-panel co-debt-panel--blocked';
    } else if (state.debtNeedsApproval) {
      alertEl.hidden = false;
      alertEl.className = 'co-debt-panel__alert co-debt-panel__alert--warning';
      alertEl.innerHTML = `<svg viewBox="0 0 24 24" width="16" height="16"><path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/></svg><span>Tổng đơn cộng công nợ vượt hạn mức. Đơn sẽ cần duyệt trước khi xử lý.</span>`;
      panel.className = 'co-debt-panel co-debt-panel--warning';
    } else {
      alertEl.hidden = true;
      panel.className = 'co-debt-panel';
    }

    syncBlockedState();
  }

  function checkDebtApproval() {
    const info = state.debtInfo;
    if (!info) {
      state.debtNeedsApproval = false;
      return;
    }
    const currentDebt = Number(info.currentDebt || 0);
    const debtLimit = Number(info.debtLimit || 0);
    if (debtLimit <= 0) {
      state.debtNeedsApproval = false;
      return;
    }
    let orderTotal = 0;
    state.items.forEach((item) => {
      orderTotal += Math.max(0, item.qty * item.unitPrice - (item.discountAmount || 0));
    });
    state.debtNeedsApproval = (currentDebt + orderTotal) > debtLimit;
  }

  function syncBlockedState() {
    const inventoryBlocked = state.items.some((item) =>
      item.inventoryLoading || item.inventoryError || item.inventory?.valid === false);
    $('co-product-search').disabled = state.debtBlocked;
    $('btn-save-draft').disabled = state.debtBlocked;
    $('btn-submit-order').disabled = state.debtBlocked || inventoryBlocked;
  }

  function getCurrentOrderTotal() {
    return state.items.reduce((total, item) => total
      + Math.max(0, item.qty * item.unitPrice - (item.discountAmount || 0)), 0);
  }

  function scheduleDebtCheck() {
    clearTimeout(debtCheckTimer);
    if (!state.selectedDealer || !state.debtInfo || state.debtError || state.debtBlocked) return;
    debtCheckTimer = setTimeout(() => refreshDebtCheck(), 250);
  }

  async function refreshDebtCheck() {
    const dealerId = state.selectedDealer?.id;
    if (!dealerId || !state.debtInfo || state.debtError || state.debtBlocked) return;

    const requestId = ++debtCheckRequestId;
    try {
      const res = await Auth.apiFetch('/orders/check-debt', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          dealerId: Number(dealerId),
          orderAmount: getCurrentOrderTotal(),
        }),
      });
      const result = await res.json().catch(() => null);
      if (requestId !== debtCheckRequestId || state.selectedDealer?.id !== dealerId) return;
      if (!res.ok || !result?.success || !result.data) {
        throw new Error(result?.message || 'Không kiểm tra được hạn mức công nợ. Vui lòng thử lại.');
      }

      const check = result.data;
      state.debtInfo = {
        ...state.debtInfo,
        currentDebt: check.currentDebt,
        debtLimit: check.debtLimit,
        remainingLimit: check.remainingLimit,
        hasOverdueDebt: check.hasOverdueDebt,
        isBlocked: check.isBlocked,
        blockReason: check.blockReason,
      };
      state.debtNeedsApproval = Boolean(check.requiresApproval || check.exceedsLimit);
      state.debtError = '';
      updateDebtPanel({ recalculateApproval: false });
    } catch (error) {
      if (requestId !== debtCheckRequestId || state.selectedDealer?.id !== dealerId) return;
      // Không cho chốt đơn khi chưa xác nhận được trạng thái công nợ từ server.
      state.debtError = error.message || 'Không kiểm tra được hạn mức công nợ. Vui lòng thử lại.';
      state.debtBlocked = true;
      updateDebtPanel({ recalculateApproval: false });
    }
  }

  /* ── Group badge ─────────────────────────────────────────── */
  const GROUP_LABELS = {
    DAI_LY_CAP_1: 'Đại lý cấp 1',
    DAI_LY_CAP_2: 'Đại lý cấp 2',
    KHACH_LE: 'Khách lẻ',
  };
  const GROUP_BADGE_CLASS = {
    DAI_LY_CAP_1: 'group-badge--cap1',
    DAI_LY_CAP_2: 'group-badge--cap2',
    KHACH_LE: 'group-badge--le',
  };

  function groupBadge(group) {
    if (!group) return '';
    const label = GROUP_LABELS[group] || group;
    const cls = GROUP_BADGE_CLASS[group] || 'group-badge--unknown';
    return `<span class="group-badge ${cls}">${escHtml(label)}</span>`;
  }

  /* ── S4-04 Purchase history ─────────────────────────────── */
  // historyItems: [{ productId, sku, name, unit, avgQty, lastOrderDate }]
  let historyItems = [];

  async function loadPurchaseHistory(dealerId) {
    const section = $('co-history-section');
    const body = $('co-history-body');
    const addAllBtn = $('btn-add-all-history');

    if (!Auth.hasPermission('ORDER_VIEW')) {
      section.hidden = true;
      body.innerHTML = '';
      addAllBtn.hidden = true;
      historyItems = [];
      return;
    }
    section.hidden = false;
    addAllBtn.hidden = true;
    historyItems = [];
    body.innerHTML = '<div class="co-history-loading"><span>Đang tải lịch sử…</span></div>';

    try {
      const res = await Auth.apiFetch(`/dealers/${dealerId}/purchase-history?months=3`);
      // Guard: dealer changed while fetching
      if (state.selectedDealer?.id !== dealerId || !Auth.hasPermission('ORDER_VIEW')) return;

      const result = await res.json();
      if (!res.ok || !result.success) throw new Error(result.message || 'Không tải được lịch sử mua hàng');

      const items = Array.isArray(result.data) ? result.data : [];

      if (!items.length) {
        body.innerHTML = '<div class="co-history-empty">Chưa có dữ liệu mua hàng trong 3 tháng gần nhất.</div>';
        return;
      }

      historyItems = items;
      renderPurchaseHistory();
    } catch (err) {
      if (state.selectedDealer?.id !== dealerId) return;
      body.innerHTML = `<div class="co-history-error">${escHtml(err.message || 'Không tải được lịch sử mua hàng.')}</div>`;
    }
  }

  function renderPurchaseHistory() {
    const body = $('co-history-body');
    const addAllBtn = $('btn-add-all-history');

    if (!historyItems.length) return;

    const currentProductIds = new Set(state.items.map((it) => it.productId));

    body.innerHTML = `<div class="co-history-list">${historyItems.map((item) => {
      const added = currentProductIds.has(item.productId);
      return `
        <div class="co-history-item${added ? ' co-history-item--added' : ''}" data-product-id="${item.productId}">
          <span class="co-history-item__sku">${escHtml(item.sku || item.code || '')}</span>
          <span class="co-history-item__name">${escHtml(item.name)}</span>
          <span class="co-history-item__avg">TB: <strong>${Number(item.avgQty || 0).toLocaleString('vi-VN')} ${escHtml(item.unit || 'cái')}</strong>/đơn</span>
          <button class="co-history-item__add" type="button" data-id="${item.productId}"${added ? ' disabled' : ''}>
            <svg viewBox="0 0 24 24"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
            ${added ? 'Đã thêm' : 'Thêm vào đơn'}
          </button>
        </div>
      `;
    }).join('')}</div>`;

    // Bind add-one buttons
    body.querySelectorAll('.co-history-item__add:not([disabled])').forEach((btn) => {
      btn.addEventListener('click', async () => {
        const productId = Number(btn.dataset.id);
        const hItem = historyItems.find((i) => i.productId === productId);
        if (!hItem) return;
        btn.disabled = true;
        btn.textContent = '…';
        await addHistoryProduct(hItem);
        // Re-render to sync added states
        renderPurchaseHistory();
      });
    });

    // Show "add all" button only if there's anything not yet added
    const hasUnadded = historyItems.some((i) => i.inLastOrder && !currentProductIds.has(i.productId));
    addAllBtn.hidden = !hasUnadded;
  }

  /**
   * Fetch full product data and add it to the order.
   * Uses avgQty (rounded, min 1) as default quantity suggestion.
   */
  async function addHistoryProduct(hItem) {
    try {
      const res = await Auth.apiFetch(`/products/lookup/${hItem.productId}`);
      const result = await res.json();
      if (!res.ok || !result.success || result.data.active === false) {
        throw new Error(result.message || 'Sản phẩm đã ngừng kinh doanh');
      }
      const existing = state.items.find((item) => item.productId === hItem.productId);
      const previousQuantity = existing?.qty || 0;
      await addItem(result.data);
      const item = state.items.find((line) => line.productId === hItem.productId);
      if (!item) return;
      const historicalUnit = hItem.lastOrderUnit || hItem.unit;
      const unit = item.unitConversions.find((value) => value.unitName === historicalUnit);
      if (!existing && unit) item.selectedUnit = unit;
      item.qty = previousQuantity + Math.max(1, Number(hItem.lastOrderQuantity || hItem.avgQty || 1));
      item.unitPrice = getUnitPrice(item);
      item.floorPrice = getUnitFloorPrice(item);
      renderItems();
      updateSummary();
      scheduleInventoryCheck(item, true);
      await refreshPricingPreview();
    } catch (error) {
      showToast(error.message, 'error');
    }
  }

  // "Add all history items" button handler
  $('btn-add-all-history').addEventListener('click', async () => {
    const btn = $('btn-add-all-history');
    btn.disabled = true;
    const currentIds = new Set(state.items.map((it) => it.productId));
    const toAdd = historyItems.filter((i) => i.inLastOrder && !currentIds.has(i.productId));
    for (const hItem of toAdd) {
      await addHistoryProduct(hItem);
    }
    btn.disabled = false;
    renderPurchaseHistory();
    showToast(`Đã thêm ${toAdd.length} sản phẩm từ lịch sử vào đơn.`, 'success');
  });

  /**
   * Sync "already-added" visual state in the history panel
   * whenever items list changes.
   */
  function syncHistoryAddedState() {
    if (!historyItems.length) return;
    renderPurchaseHistory();
  }

  /* ── Product search ──────────────────────────────────────── */
  let searchTimer = null;
  const productInput = $('co-product-search');
  const dropdown = $('co-product-dropdown');

  productInput.addEventListener('input', () => {
    clearTimeout(searchTimer);
    const q = productInput.value.trim();
    if (!q) {
      dropdown.hidden = true;
      return;
    }
    searchTimer = setTimeout(() => searchProducts(q), 220);
  });

  productInput.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') {
      dropdown.hidden = true;
      productInput.value = '';
    }
  });

  document.addEventListener('click', (e) => {
    if (!productInput.contains(e.target) && !dropdown.contains(e.target)) {
      dropdown.hidden = true;
    }
  });

  async function searchProducts(q) {
    dropdown.hidden = false;
    dropdown.innerHTML = '<div class="co-dropdown-loading">Đang tìm…</div>';
    try {
      const res = await Auth.apiFetch(`/products/lookup?search=${encodeURIComponent(q)}&active=true&size=30`);
      const result = await res.json();
      if (!res.ok || !result.success) throw new Error(result.message || 'Lỗi tìm kiếm');
      const products = Array.isArray(result.data)
        ? result.data
        : (Array.isArray(result.data?.content) ? result.data.content : []);

      if (!products.length) {
        dropdown.innerHTML = '<div class="co-dropdown-empty">Không tìm thấy sản phẩm phù hợp.</div>';
        return;
      }

      dropdown.innerHTML = products.map((p) => `
        <div class="co-product-option" role="option" data-id="${p.id}" tabindex="0">
          <span class="co-product-option__sku">${escHtml(p.sku || p.code || '')}</span>
          <span class="co-product-option__name">${escHtml(p.name)}</span>
          <span class="co-product-option__price">${fmtMoney(p.sellingPrice || p.price || 0)}</span>
        </div>
      `).join('');

      dropdown.querySelectorAll('.co-product-option').forEach((opt, idx) => {
        const prod = products[idx];
        const addProduct = () => {
          addItem(prod);
          dropdown.hidden = true;
          productInput.value = '';
          productInput.focus();
        };
        opt.addEventListener('click', addProduct);
        opt.addEventListener('keydown', (e) => {
          if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); addProduct(); }
        });
      });
    } catch (e) {
      dropdown.innerHTML = `<div class="co-dropdown-empty">${escHtml(e.message || 'Lỗi tìm kiếm')}</div>`;
    }
  }

  /* ── Items management ────────────────────────────────────── */
  let itemSeq = 0;

  async function addItem(product) {
    if (!state.selectedDealer) {
      showToast('Vui lòng chọn đại lý trước để áp đúng bảng giá.', 'warning');
      $('co-dealer').focus();
      return;
    }
    if (state.dealerLoading) {
      showToast('Đang tải bảng giá của đại lý. Vui lòng thử lại sau giây lát.', 'warning');
      return;
    }
    if (!state.priceList) {
      showToast(state.priceListError || 'Chưa có bảng giá hiệu lực cho đại lý này.', 'error');
      return;
    }
    const priceLine = findPriceLine(product.id);
    if (!priceLine) {
      showToast(`SKU ${product.sku || product.code || product.name} chưa có trong bảng giá hiệu lực, không thể thêm vào đơn.`, 'error');
      return;
    }

    // Check duplicate
    const existing = state.items.find((it) => it.productId === product.id);
    if (existing) {
      // just bump qty
      existing.qty += 1;
      renderItems();
      updateSummary();
      refreshPricingPreview();
      scheduleInventoryCheck(existing);
      showToast(`Đã tăng số lượng «${product.name}» lên ${existing.qty}.`, 'success');
      return;
    }

    // Fetch unit conversions if not cached
    let units = [{ id: null, unitName: product.unit || 'cái', conversionFactor: 1 }];
    if (!state.productCache[product.id]) {
      try {
        const res = await Auth.apiFetch(`/products/${product.id}/unit-conversions`);
        if (res.ok) {
          const result = await res.json();
          if (result.success && Array.isArray(result.data) && result.data.length) {
            units = result.data;
            state.productCache[product.id] = units;
          }
        }
      } catch { /* use default */ }
    } else {
      units = state.productCache[product.id];
    }

    const item = {
      _key: ++itemSeq,
      productId: product.id,
      sku: product.sku || product.code || '',
      name: product.name,
      unitConversions: units,
      selectedUnit: units[0],
      qty: 1,
      basePrice: Number(product.sellingPrice || product.price || 0),
      unitPrice: 0,
      floorPrice: 0,
      manualPrice: false,
      discountAmount: 0,
    };
    item.unitPrice = getUnitPrice(item);
    item.floorPrice = getUnitFloorPrice(item);
    state.items.push(item);
    renderItems();
    updateSummary();
    refreshPricingPreview();
    scheduleInventoryCheck(item);
  }

  function removeItem(key) {
    state.items = state.items.filter((it) => it._key !== key);
    renderItems();
    updateSummary();
    refreshPricingPreview();
    syncHistoryAddedState();
  }

  function renderItems() {
    const tbody = $('co-items-body');
    const emptyRow = $('co-items-empty');

    if (!state.items.length) {
      // Remove existing item rows, show empty
      tbody.querySelectorAll('.co-item-row').forEach((r) => r.remove());
      emptyRow.hidden = false;
      return;
    }

    emptyRow.hidden = true;

    // Remove old item rows
    tbody.querySelectorAll('.co-item-row').forEach((r) => r.remove());

    state.items.forEach((item) => {
      const row = document.createElement('tr');
      row.className = 'co-item-row';
      row.dataset.key = item._key;

      const unitOptions = item.unitConversions.map((u) =>
        `<option value="${u.id ?? u.unitName}" ${u === item.selectedUnit ? 'selected' : ''}>${escHtml(u.unitName)}</option>`,
      ).join('');

      const lineTotal = calcLineTotal(item);

      row.innerHTML = `
        <td><span class="co-sku-badge">${escHtml(item.sku)}</span></td>
        <td style="min-width:160px;max-width:240px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;">${escHtml(item.name)}</td>
        <td>
          <select class="co-unit-select" data-field="unit">
            ${unitOptions}
          </select>
        </td>
        <td data-stock>
          <div class="co-stock-display">
            <span class="co-stock-detail">Đang kiểm tra…</span>
          </div>
        </td>
        <td class="co-qty-cell" style="text-align:right">
          <input class="co-qty-input" type="number" data-field="qty" min="1" step="1" value="${item.qty}" />
          <div class="co-qty-alert" data-qty-alert hidden></div>
        </td>
        <td style="text-align:right">
          <input class="co-price-input" type="number" data-field="price" data-price min="0" step="1" value="${escHtml(item.unitPrice)}" aria-label="Đơn giá ${escHtml(item.name)}" />
          <small class="co-floor-hint" data-floor>Giá sàn: ${fmtMoney(item.floorPrice)}</small>
        </td>
        <td style="text-align:right"><span class="co-discount-value" data-discount>${item.discountAmount > 0 ? '−' + fmtMoney(item.discountAmount) : '—'}</span></td>
        <td style="text-align:right">
          <span class="co-item-total" data-total>${fmtMoney(lineTotal)}</span>
        </td>
        <td style="text-align:center">
          <button class="co-del-btn" type="button" title="Xoá dòng này" aria-label="Xoá ${escHtml(item.name)}">
            <svg viewBox="0 0 24 24"><polyline points="3 6 5 6 21 6"/><path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"/><path d="M10 11v6M14 11v6"/></svg>
          </button>
        </td>
      `;

      // Delete
      row.querySelector('.co-del-btn').addEventListener('click', () => removeItem(item._key));

      // Qty
      row.querySelector('[data-field="qty"]').addEventListener('input', (e) => {
        const quantity = Number(e.target.value);
        if (!e.target.value || !Number.isFinite(quantity) || quantity <= 0) return;
        item.qty = quantity;
        refreshRowTotal(row, item);
        scheduleInventoryCheck(item);
        updateSummary();
        updateApprovalNotice();
        saveDraftSilently();
        refreshPricingPreview();
      });
      row.querySelector('[data-field="qty"]').addEventListener('change', (e) => {
        const quantity = Math.max(1, Number(e.target.value) || 1);
        const changed = item.qty !== quantity;
        item.qty = quantity;
        e.target.value = item.qty;
        // The input listener already scheduled validation. Do not remove the max-suggestion
        // button during blur between pointer-down and click when quantity hasn't changed.
        if (!changed) return;
        refreshRowTotal(row, item);
        scheduleInventoryCheck(item, true);
        updateSummary();
        updateApprovalNotice();
        saveDraftSilently();
        refreshPricingPreview();
      });

      // Cho phép sửa giá thủ công; backend sẽ đánh dấu đơn chờ duyệt nếu thấp hơn giá sàn.
      row.querySelector('[data-field="price"]').addEventListener('input', (e) => {
        const price = Number(e.target.value);
        if (!e.target.value || !Number.isFinite(price) || price < 0) return;
        item.unitPrice = price;
        item.manualPrice = true;
        refreshRowTotal(row, item);
        updateSummary();
        updateApprovalNotice();
        saveDraftSilently();
      });
      row.querySelector('[data-field="price"]').addEventListener('change', (e) => {
        const price = Math.max(0, Number(e.target.value) || 0);
        item.unitPrice = price;
        item.manualPrice = true;
        e.target.value = price;
        refreshRowTotal(row, item);
        updateSummary();
        updateApprovalNotice();
        saveDraftSilently();
      });

      // Unit
      row.querySelector('[data-field="unit"]').addEventListener('change', (e) => {
        const selected = item.unitConversions.find(
          (u) => String(u.id ?? u.unitName) === e.target.value,
        );
        if (selected) {
          item.selectedUnit = selected;
          item.manualPrice = false;
          item.unitPrice = getUnitPrice(item);
          item.floorPrice = getUnitFloorPrice(item);
          refreshRowTotal(row, item);
          scheduleInventoryCheck(item, true);
          updateSummary();
          refreshPricingPreview();
        }
        saveDraftSilently();
      });

      renderInventoryState(row, item);
      tbody.insertBefore(row, emptyRow);
    });
  }

  function formatQuantity(value) {
    return Number(value || 0).toLocaleString('vi-VN', {
      maximumFractionDigits: 2,
    });
  }

  function getInventoryUnit(item) {
    return item.selectedUnit?.unitName || item.selectedUnit?.unit || 'cái';
  }

  function renderInventoryState(row, item) {
    const stockCell = row?.querySelector('[data-stock]');
    const qtyInput = row?.querySelector('[data-field="qty"]');
    const alert = row?.querySelector('[data-qty-alert]');
    if (!stockCell || !qtyInput || !alert) return;

    let stockMarkup = '<div class="co-stock-display"><span class="co-stock-detail">Chưa kiểm tra</span></div>';
    const inventory = item.inventory;
    const isOverstock = Boolean(inventory && inventory.valid === false);

    if (item.inventoryLoading) {
      stockMarkup = '<div class="co-stock-display"><span class="co-stock-detail">Đang kiểm tra…</span></div>';
    } else if (item.inventoryError) {
      stockMarkup = `<div class="co-stock-display"><span class="co-stock-detail">${escHtml(item.inventoryError)}</span></div>`;
    } else if (inventory) {
      const available = Number(inventory.availableInUnit ?? 0);
      const badgeClass = available <= 0
        ? 'co-stock-badge--out'
        : (isOverstock ? 'co-stock-badge--low' : 'co-stock-badge--ok');
      const unit = inventory.unit || getInventoryUnit(item);
      stockMarkup = `
        <div class="co-stock-display">
          <span class="co-stock-badge ${badgeClass}">${formatQuantity(available)} ${escHtml(unit)}</span>
          <span class="co-stock-detail">Thực tế ${formatQuantity(inventory.physicalQuantity)} · Giữ chỗ ${formatQuantity(inventory.reservedQuantity)}</span>
        </div>
      `;
    }

    stockCell.innerHTML = stockMarkup;
    row.classList.toggle('co-item-row--overstock', isOverstock);
    syncBlockedState();
    qtyInput.classList.toggle('co-qty-input--error', isOverstock);
    alert.hidden = !isOverstock;
    alert.innerHTML = '';

    if (isOverstock) {
      const maxAllowed = Math.max(0, Number(inventory.maxAllowedInUnit || 0));
      const message = inventory.message || `Vượt tồn khả dụng. Tối đa ${formatQuantity(maxAllowed)} ${getInventoryUnit(item)}.`;
      alert.innerHTML = `<span class="co-qty-alert__msg">${escHtml(message)}</span>`;
      if (maxAllowed > 0) {
        const suggestButton = document.createElement('button');
        suggestButton.type = 'button';
        suggestButton.className = 'btn-suggest-max';
        suggestButton.textContent = `Đặt tối đa ${formatQuantity(maxAllowed)}`;
        suggestButton.addEventListener('click', () => {
          item.qty = maxAllowed;
          qtyInput.value = maxAllowed;
          refreshRowTotal(row, item);
          updateSummary();
          saveDraftSilently();
          scheduleInventoryCheck(item, true);
          refreshPricingPreview();
        });
        alert.appendChild(suggestButton);
      }
    }
  }

  function scheduleInventoryCheck(item, immediate = false) {
    clearTimeout(item.inventoryTimer);
    // Invalidate an older response immediately, including the debounce window.
    item.inventoryRequestId = (item.inventoryRequestId || 0) + 1;
    item.inventory = null;
    item.inventoryError = '';
    item.inventoryLoading = true;

    const row = $('co-items-body').querySelector(`[data-key="${item._key}"]`);
    if (row) renderInventoryState(row, item);

    const run = () => checkItemInventory(item);
    if (immediate) return run();
    item.inventoryTimer = setTimeout(run, 280);
    return Promise.resolve(true);
  }

  async function checkItemInventory(item) {
    const requestId = (item.inventoryRequestId || 0) + 1;
    item.inventoryRequestId = requestId;
    item.inventoryLoading = true;
    item.inventoryError = '';
    const row = $('co-items-body').querySelector(`[data-key="${item._key}"]`);
    if (row) renderInventoryState(row, item);

    try {
      const res = await Auth.apiFetch('/orders/check-inventory', {
        method: 'POST',
        body: JSON.stringify({
          dealerId: state.selectedDealer?.id ? Number(state.selectedDealer.id) : null,
          productId: Number(item.productId),
          unit: getInventoryUnit(item),
          quantity: Number(item.qty),
        }),
      });
      const result = await res.json().catch(() => null);
      if (!res.ok || !result?.success || !result.data) {
        throw new Error(result?.message || 'Không kiểm tra được tồn khả dụng.');
      }
      if (item.inventoryRequestId !== requestId) return false;
      item.inventory = result.data;
      return result.data.valid !== false;
    } catch (error) {
      if (item.inventoryRequestId !== requestId) return false;
      item.inventory = null;
      item.inventoryError = error.message || 'Không kiểm tra được tồn khả dụng.';
      return false;
    } finally {
      if (item.inventoryRequestId === requestId) {
        item.inventoryLoading = false;
        const currentRow = $('co-items-body').querySelector(`[data-key="${item._key}"]`);
        if (currentRow) renderInventoryState(currentRow, item);
      }
    }
  }

  async function refreshInventoryPreview() {
    if (!state.items.length || !state.selectedDealer) return true;
    const results = await Promise.all(state.items.map((item) => checkItemInventory(item)));
    if (results.every(Boolean)) return true;

    const invalidItem = state.items.find((item) => item.inventory?.valid === false);
    if (invalidItem) {
      showToast(invalidItem.inventory.message || `Sản phẩm ${invalidItem.name} vượt tồn khả dụng.`, 'error');
    } else {
      showToast('Chưa kiểm tra được tồn khả dụng. Vui lòng thử lại.', 'error');
    }
    return false;
  }

  function refreshRowTotal(row, item) {
    const total = calcLineTotal(item);
    const el = row.querySelector('[data-total]');
    if (el) el.textContent = fmtMoney(total);
    const priceEl = row.querySelector('[data-price]');
    if (priceEl) priceEl.value = item.unitPrice;
    const floorEl = row.querySelector('[data-floor]');
    if (floorEl) floorEl.textContent = `Giá sàn: ${fmtMoney(item.floorPrice)}`;
    row.classList.toggle('co-item-row--approval', isBelowFloor(item));
    const discountEl = row.querySelector('[data-discount]');
    if (discountEl) discountEl.textContent = item.discountAmount > 0 ? `−${fmtMoney(item.discountAmount)}` : '—';
  }

  function calcLineTotal(item) {
    return Math.max(0, item.qty * item.unitPrice - item.discountAmount);
  }

  function getUnitPrice(item) {
    const factor = Number(item.selectedUnit?.conversionFactor ?? 1) || 1;
    if (item.manualPrice) return Number(item.unitPrice || 0);
    const priceLine = findPriceLine(item.productId);
    if (priceLine?.sellingPrice != null) return Number(priceLine.sellingPrice) * factor;
    return Number(item.basePrice || 0) * factor;
  }

  function findPriceLine(productId) {
    return state.priceList?.items?.find((line) => Number(line.productId) === Number(productId)) || null;
  }

  function getUnitFloorPrice(item) {
    const factor = Number(item.selectedUnit?.conversionFactor ?? 1) || 1;
    const priceLine = findPriceLine(item.productId);
    return priceLine?.floorPrice != null ? Number(priceLine.floorPrice) * factor : 0;
  }

  function isBelowFloor(item) {
    return Number(item.floorPrice || 0) > 0 && Number(item.unitPrice || 0) < Number(item.floorPrice);
  }

  function updateApprovalNotice(pricingSummary = state.pricingSummary) {
    const statusEl = $('co-pricing-status');
    if (!statusEl || !state.items.length || !state.priceList) return;
    const approvalItems = state.items.filter(isBelowFloor);
    const requiresApproval = pricingSummary?.requiresApproval ?? approvalItems.length > 0;
    statusEl.className = requiresApproval
      ? 'co-pricing-status co-pricing-status--warning'
      : 'co-pricing-status co-pricing-status--success';
    statusEl.textContent = requiresApproval
      ? (pricingSummary?.approvalReason || `Có ${approvalItems.length} dòng dưới giá sàn. Đơn sẽ chuyển sang trạng thái chờ duyệt.`)
      : `Đã áp dụng bảng giá ${pricingSummary?.priceListCode || state.priceList.code || ''} và chiết khấu theo sản lượng.`;
    statusEl.hidden = false;
  }

  function syncItemPrices() {
    state.items.forEach((item) => {
      if (!item.manualPrice) item.unitPrice = getUnitPrice(item);
      item.floorPrice = getUnitFloorPrice(item);
    });
    renderItems();
    updateSummary();
  }

  /* ── Summary ─────────────────────────────────────────────── */
  function updateSummary() {
    let subtotal = 0;
    let totalDiscount = 0;
    state.items.forEach((item) => {
      subtotal += item.qty * item.unitPrice;
      totalDiscount += item.discountAmount || 0;
    });
    const grandTotal = Math.max(0, subtotal - totalDiscount);

    $('co-subtotal').textContent = fmtMoney(subtotal);
    $('co-total-discount').textContent = totalDiscount > 0 ? `−${fmtMoney(totalDiscount)}` : '−0 ₫';
    $('co-grand-total').textContent = fmtMoney(grandTotal);

    // S4-02: kiểm tra lại trạng thái công nợ khi tổng đơn thay đổi
    checkDebtApproval();
    updateDebtPanel();
    scheduleDebtCheck();
  }

  async function refreshPricingPreview() {
    const requestId = ++pricingRequestId;
    if (!state.items.length) {
      state.pricingSummary = null;
      const statusEl = $('co-pricing-status');
      if (state.selectedDealer && !state.priceList) {
        statusEl.hidden = false;
        statusEl.className = 'co-pricing-status co-pricing-status--error';
        statusEl.textContent = state.priceListError || 'Chưa có bảng giá hiệu lực cho đại lý này.';
      } else {
        statusEl.hidden = true;
      }
      return true;
    }

    if (!state.priceList) {
      state.pricingSummary = null;
      state.items.forEach((item) => { item.discountAmount = 0; });
      refreshItemAmounts();
      updateSummary();
      const statusEl = $('co-pricing-status');
      statusEl.hidden = false;
      statusEl.className = 'co-pricing-status co-pricing-status--error';
      statusEl.textContent = state.priceListError || 'Chưa có bảng giá hiệu lực cho đại lý này.';
      return false;
    }

    const statusEl = $('co-pricing-status');
    statusEl.hidden = false;
    statusEl.className = 'co-pricing-status co-pricing-status--info';
    statusEl.textContent = 'Đang cập nhật chiết khấu…';

    try {
      const payload = {
        dealerId: state.selectedDealer?.id ? Number(state.selectedDealer.id) : null,
        customerGroup: state.selectedDealer?.customerGroup || null,
        priceListId: state.priceList?.id || null,
        orderDate: todayInputValue(),
        items: state.items.map((item) => {
          const line = {
            productId: Number(item.productId),
            unit: item.selectedUnit?.unitName || item.selectedUnit?.unit || 'cái',
            quantity: item.qty,
          };
          // Chỉ gửi giá khi người dùng thật sự sửa thủ công.
          if (item.manualPrice) line.price = item.unitPrice;
          return line;
        }),
      };
      const res = await Auth.apiFetch('/orders/calculate', {
        method: 'POST',
        body: JSON.stringify(payload),
      });
      const result = await res.json();
      if (!res.ok || !result.success) throw new Error(result.message || 'Không tính được chiết khấu');
      if (requestId !== pricingRequestId) return false;

      state.pricingSummary = result.data || null;
      const calculatedItems = Array.isArray(result.data?.items) ? result.data.items : [];
      state.items.forEach((item, index) => {
        const calculated = calculatedItems[index];
        if (!calculated) return;
        if (!item.manualPrice && calculated.price != null) item.unitPrice = Number(calculated.price);
        if (calculated.floorPrice != null) item.floorPrice = Number(calculated.floorPrice);
        item.discountAmount = Number(calculated.discountAmount || 0);
      });
      refreshItemAmounts();
      updateSummary();
      updateApprovalNotice();
      return true;
    } catch {
      if (requestId !== pricingRequestId) return false;
      state.pricingSummary = null;
      state.items.forEach((item) => { item.discountAmount = 0; });
      refreshItemAmounts();
      updateSummary();
      statusEl.className = 'co-pricing-status co-pricing-status--error';
      statusEl.textContent = 'Chưa cập nhật được chiết khấu; tổng tiền đang hiển thị theo giá tạm tính.';
      return false;
    }
  }

  function refreshItemAmounts() {
    state.items.forEach((item) => {
      const row = $('co-items-body').querySelector(`[data-key="${item._key}"]`);
      if (row) refreshRowTotal(row, item);
    });
  }

  /* ── Draft: save / load ─────────────────────────────────── */
  function buildDraftPayload() {
    return {
      dealerId: $('co-dealer').value || null,
      deliveryPointId: $('co-delivery-point').value || null,
      deliveryDate: $('co-delivery-date').value || null,
      note: $('co-note').value || '',
      items: state.items.map((it) => ({
        _key: it._key,
        productId: it.productId,
        sku: it.sku,
        name: it.name,
        unitConversions: it.unitConversions,
        selectedUnitId: it.selectedUnit?.id ?? it.selectedUnit?.unitName ?? null,
        qty: it.qty,
        basePrice: it.basePrice,
        unitPrice: it.unitPrice,
        floorPrice: it.floorPrice,
        manualPrice: it.manualPrice,
        discountAmount: it.discountAmount || 0,
      })),
      _seq: itemSeq,
    };
  }

  let draftSaveTimer = null;
  let draftSaveRequest = Promise.resolve();

  function saveDraft() {
    const payload = buildDraftPayload();
    draftSaveRequest = draftSaveRequest.catch(() => {}).then(async () => {
      const res = await Auth.apiFetch('/orders/draft', {
        method: 'PUT',
        body: JSON.stringify(payload),
      });
      const result = await res.json();
      if (!res.ok || !result.success) throw new Error(result.message || 'Không lưu được bản nháp');
    });
    return draftSaveRequest;
  }

  function saveDraftSilently() {
    clearTimeout(draftSaveTimer);
    draftSaveTimer = setTimeout(async () => {
      try {
        await saveDraft();
      } catch {
        // Không chặn việc nhập đơn; nút Lưu nháp sẽ báo lỗi rõ ràng nếu cần.
      }
    }, 350);
  }

  async function hasDraft() {
    try {
      const res = await Auth.apiFetch('/orders/draft');
      const result = await res.json();
      return res.ok && result.success && !!result.data;
    } catch {
      return false;
    }
  }

  async function loadDraft() {
    try {
      const res = await Auth.apiFetch('/orders/draft');
      const result = await res.json();
      if (!res.ok || !result.success || !result.data) return false;
      const draft = result.data;
      // Restore form fields
      $('co-dealer').value = draft.dealerId || '';
      $('co-delivery-date').value = draft.deliveryDate || '';
      $('co-note').value = draft.note || '';

      // Trigger dealer change to load delivery points
      state.pendingDeliveryPointId = draft.deliveryPointId || null;

      // Restore items
      itemSeq = draft._seq || 0;
      state.items = await Promise.all((draft.items || []).map(async (it) => {
        const [productResponse, unitsResponse] = await Promise.all([
          Auth.apiFetch(`/products/lookup/${it.productId}`),
          Auth.apiFetch(`/products/${it.productId}/unit-conversions`),
        ]);
        const [productResult, unitsResult] = await Promise.all([
          productResponse.json(), unitsResponse.json(),
        ]);
        if (!productResponse.ok || !productResult.success || !unitsResponse.ok || !unitsResult.success) {
          throw new Error('Không tải được dữ liệu sản phẩm trong bản nháp');
        }
        const product = productResult.data;
        const units = unitsResult.data?.length ? unitsResult.data : [
          { id: null, unitName: product.unit, conversionFactor: 1 },
        ];
        const oldUnit = (it.unitConversions || []).find(
          (u) => String(u.id ?? u.unitName) === String(it.selectedUnitId),
        );
        const selected = units.find((u) => String(u.id ?? u.unitName) === String(it.selectedUnitId))
          || units.find((u) => u.unitName === oldUnit?.unitName)
          || units.find((u) => u.unitName === it.selectedUnitId)
          || units[0];
        if (oldUnit && oldUnit.unitName !== selected.unitName) {
          showToast(`Đơn vị của ${product.name} đã thay đổi. Vui lòng kiểm tra lại dòng hàng.`, 'warning');
        }
        state.productCache[it.productId] = units;
        return {
          _key: it._key,
          productId: it.productId,
          sku: product.sku || product.code,
          name: product.name,
          unitConversions: units,
          selectedUnit: selected,
          qty: it.qty,
          basePrice: Number(product.price ?? product.sellingPrice ?? 0),
          unitPrice: Number(it.unitPrice || 0),
          floorPrice: Number(it.floorPrice || 0),
          manualPrice: Boolean(it.manualPrice),
          discountAmount: Number(it.discountAmount || it.discount || 0),
        };
      }));

      renderItems();
      updateSummary();
      await selectDealer();
      if (draft.dealerId && !$('co-dealer').value) {
        setDeliveryStatus('Đại lý trong bản nháp không còn được phép đặt hàng. Vui lòng chọn đại lý khác.', true);
      }
      return true;
    } catch {
      return false;
    }
  }

  async function clearDraft() {
    clearTimeout(draftSaveTimer);
    await draftSaveRequest.catch(() => {});
    try {
      await Auth.apiFetch('/orders/draft', { method: 'DELETE' });
    } catch {
      // Không làm gián đoạn luồng tạo đơn nếu thao tác dọn bản nháp thất bại.
    }
  }

  /* ── Draft banner ────────────────────────────────────────── */
  function showDraftBanner() {
    const existing = document.querySelector('.co-draft-banner');
    if (existing) return;
    const banner = document.createElement('div');
    banner.className = 'co-draft-banner';
    banner.innerHTML = `
      <svg viewBox="0 0 24 24" width="16" height="16"><path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/></svg>
      <span>Bạn có đơn nháp chưa gửi.</span>
      <button type="button" id="btn-restore-draft">Tiếp tục soạn</button>
      <button type="button" id="btn-discard-draft" style="margin-left:8px;color:var(--ink-500)">Bỏ qua</button>
    `;
    const form = $('order-form');
    form.parentNode.insertBefore(banner, form);
    $('btn-load-draft').hidden = false;

    document.getElementById('btn-restore-draft').addEventListener('click', async () => {
      if (await loadDraft()) {
        banner.remove();
        $('btn-load-draft').hidden = true;
        showToast('Đã khôi phục đơn nháp.', 'success');
      } else {
        showToast('Không tải được bản nháp. Vui lòng thử lại.', 'error');
      }
    });

    document.getElementById('btn-discard-draft').addEventListener('click', async () => {
      await clearDraft();
      banner.remove();
      $('btn-load-draft').hidden = true;
    });
  }

  $('btn-load-draft').addEventListener('click', async () => {
    if (await loadDraft()) {
      document.querySelector('.co-draft-banner')?.remove();
      $('btn-load-draft').hidden = true;
      showToast('Đã khôi phục đơn nháp.', 'success');
    } else {
      showToast('Không tải được bản nháp. Vui lòng thử lại.', 'error');
    }
  });

  /* ── Save draft button ───────────────────────────────────── */
  $('btn-save-draft').addEventListener('click', async () => {
    if (!state.items.length) {
      showToast('Chưa có dòng hàng nào để lưu nháp.', 'warning');
      return;
    }
    const btn = $('btn-save-draft');
    btn.disabled = true;
    try {
      await saveDraft();
      showToast('Đã lưu nháp đơn hàng. Bạn có thể mở lại ở lần sau.', 'success');
    } catch (e) {
      showToast(e.message || 'Không lưu được bản nháp.', 'error');
    } finally {
      btn.disabled = false;
    }
  });

  /* ── Submit order ────────────────────────────────────────── */
  $('order-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    if (!validateForm()) return;
    const button = $('btn-submit-order');
    button.disabled = true;
    try {
      if (!await refreshPricingPreview()) {
        showToast('Chưa xác định được tổng tiền. Vui lòng cập nhật chiết khấu và thử lại.', 'error');
        return;
      }
      if (!await refreshInventoryPreview()) return;
      openConfirmModal();
    } finally {
      button.disabled = false;
    }
  });

  function validateForm() {
    if (state.dealerLoading) {
      showToast('Đang tải thông tin đại lý. Vui lòng thử lại sau khi tải xong.', 'warning');
      return false;
    }
    if (state.debtBlocked) {
      showToast('Đại lý có khoản nợ quá hạn. Không thể tạo đơn cho đại lý này.', 'error');
      return false;
    }
    if (!$('co-dealer').value) {
      showToast('Vui lòng chọn đại lý.', 'warning');
      $('co-dealer').focus();
      return false;
    }
    if (!$('co-delivery-date').value) {
      showToast('Vui lòng chọn ngày giao mong muốn.', 'warning');
      $('co-delivery-date').focus();
      return false;
    }
    if (state.deliveryPoints.length && !$('co-delivery-point').value) {
      showToast('Vui lòng chọn điểm giao hàng.', 'warning');
      $('co-delivery-point').focus();
      return false;
    }
    if (!state.items.length) {
      showToast('Vui lòng thêm ít nhất một dòng hàng.', 'warning');
      $('co-product-search').focus();
      return false;
    }
    if (!state.priceList) {
      showToast(state.priceListError || 'Chưa có bảng giá hiệu lực cho đại lý này.', 'error');
      return false;
    }
    const missingPriceLines = state.items.filter((item) => !findPriceLine(item.productId));
    if (missingPriceLines.length) {
      showToast(`Có sản phẩm chưa có trong bảng giá hiệu lực: ${missingPriceLines.map((item) => item.sku || item.name).join(', ')}.`, 'error');
      return false;
    }
    return true;
  }

  function openConfirmModal() {
    const dealer = state.selectedDealer;
    let subtotal = 0;
    let totalDiscount = 0;
    state.items.forEach((item) => {
      subtotal += item.qty * item.unitPrice;
      totalDiscount += item.discountAmount || 0;
    });
    const grandTotal = Math.max(0, subtotal - totalDiscount);

    $('confirm-modal-sub').textContent = dealer
      ? `${dealer.code} — ${dealer.name}`
      : '';

    $('confirm-summary').innerHTML = `
      <div class="co-cs-row"><span>Số dòng hàng</span><span>${state.items.length} sản phẩm</span></div>
      <div class="co-cs-row"><span>Tổng tiền hàng</span><span>${fmtMoney(subtotal)}</span></div>
      <div class="co-cs-row"><span>Chiết khấu</span><span>${totalDiscount > 0 ? '−' + fmtMoney(totalDiscount) : '—'}</span></div>
      <div class="co-cs-row"><span>Tổng phải thu</span><span>${fmtMoney(grandTotal)}</span></div>
    `;

    const debtNotice = document.querySelector('.co-confirm-debt-notice');
    if (debtNotice) debtNotice.remove();
    if (state.debtNeedsApproval) {
      const notice = document.createElement('div');
      notice.className = 'co-confirm-debt-notice';
      notice.innerHTML = '<svg viewBox="0 0 24 24" width="16" height="16"><path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/></svg><span>Đơn vượt hạn mức công nợ và sẽ cần duyệt trước khi xử lý.</span>';
      $('confirm-summary').after(notice);
    }

    $('confirm-msg').hidden = true;
    $('btn-confirm-order').disabled = false;
    $('btn-confirm-order').textContent = 'Xác nhận tạo đơn';
    $('confirm-modal').hidden = false;
  }

  function closeConfirmModal() {
    $('confirm-modal').hidden = true;
  }

  $('btn-close-confirm').addEventListener('click', closeConfirmModal);
  $('btn-cancel-confirm').addEventListener('click', closeConfirmModal);
  $('confirm-modal').addEventListener('click', (e) => {
    if (e.target === $('confirm-modal')) closeConfirmModal();
  });

  $('btn-confirm-order').addEventListener('click', async () => {
    const btn = $('btn-confirm-order');
    btn.disabled = true;
    btn.textContent = 'Đang tạo đơn…';

    try {
      const payload = buildOrderPayload();
      const res = await Auth.apiFetch('/orders', {
        method: 'POST',
        body: JSON.stringify(payload),
      });
      const result = await res.json();
      if (!res.ok || !result.success) throw new Error(result.message || 'Không tạo được đơn hàng');

      const createdOrder = result.data;

      closeConfirmModal();
      await clearDraft();

      // Reset form
      $('order-form').reset();
      state.items = [];
      state.selectedDealer = null;
      state.debtInfo = null;
      state.debtBlocked = false;
      state.debtNeedsApproval = false;
      state.debtError = '';
      $('co-debt-panel').hidden = true;
      $('co-dealer-info').hidden = true;
      $('co-delivery-point').innerHTML = '<option value="">— Chọn điểm giao hàng —</option>';
      $('co-delivery-point').disabled = true;
      state.deliveryPoints = [];
      state.priceList = null;
      state.pricingSummary = null;
      setDeliveryStatus('');
      $('co-pricing-status').hidden = true;
      renderItems();
      updateSummary();

      showToast(`Đã tạo đơn ${createdOrder.orderCode || createdOrder.id}.`, 'success');
      // Đơn đã được lưu; lỗi tải PDF không được biến thành lỗi tạo đơn.
      OrderPdf.open(createdOrder).catch((error) => showToast(error.message, 'error'));
    } catch (err) {
      const msgEl = $('confirm-msg');
      msgEl.textContent = err.message || 'Lỗi không xác định. Vui lòng thử lại.';
      msgEl.hidden = false;
      btn.disabled = false;
      btn.textContent = 'Thử lại';
    }
  });


  function buildOrderPayload() {
    const dealerId = $('co-dealer').value;
    const deliveryPointId = $('co-delivery-point').value || null;
    const deliveryDate = $('co-delivery-date').value || null;
    const note = $('co-note').value.trim() || null;

    return {
      dealerId: Number(dealerId),
      deliveryPointId: deliveryPointId ? Number(deliveryPointId) : null,
      expectedDeliveryDate: deliveryDate || null,
      priceListId: state.priceList?.id || null,
      note,
      items: state.items.map((it) => ({
        productId: it.productId,
        unit: it.selectedUnit?.unitName || it.selectedUnit?.unit || 'cái',
        quantity: it.qty,
        ...(it.manualPrice ? { price: it.unitPrice } : {}),
      })),
    };
  }

  /* ── Keyboard shortcut: Escape ───────────────────────────── */
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') {
      if (!$('confirm-modal').hidden) closeConfirmModal();
      if (!$('co-product-dropdown').hidden) {
        $('co-product-dropdown').hidden = true;
        productInput.value = '';
      }
    }
  });

  /* ── Auto-save on item changes ───────────────────────────── */
  // Listen for any form input change to auto-save draft
  $('order-form').addEventListener('change', () => {
    if (state.items.length > 0 || $('co-dealer').value) {
      saveDraftSilently();
    }
  });

  /* ── Init ────────────────────────────────────────────────── */
  async function init() {
    const today = todayInputValue();
    $('co-delivery-date').min = today;
    await loadDealers();

    const searchString = typeof window !== 'undefined' && window.location ? window.location.search : (typeof location !== 'undefined' ? location.search : '');
    const query = typeof URLSearchParams !== 'undefined' ? new URLSearchParams(searchString || '') : { get: () => null };
    const copiedDraft = query.get('copied') === '1';

    if (copiedDraft) {
      // A copied order is already saved in the database as a draft.
      // Loading it also recalculates current prices and discounts in selectDealer().
      if (await loadDraft()) {
        showToast('Đã sao chép đơn cũ thành bản nháp. Giá và chiết khấu đã được tính lại.', 'success');
      } else {
        showToast('Không tải được bản sao đơn hàng. Vui lòng thử lại.', 'error');
      }
      window.history.replaceState({}, document.title, window.location.pathname);
    } else if (await hasDraft()) {
      showDraftBanner();
    }
  }

  globalThis.addEventListener?.('auth:permissions-changed', () => {
    if (state.selectedDealer) loadPurchaseHistory(state.selectedDealer.id);
  });
  init();
})();
