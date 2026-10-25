/**
 * warehouse-delivery-routes.js — Nghiệp vụ Phân tuyến giao hàng và In danh sách điểm giao (S6-06).
 *
 * Tiêu chí:
 * 1. Gom nhiều phiếu xuất vào một chuyến giao theo khu vực
 * 2. Gán tài xế và phương tiện cho chuyến
 * 3. In danh sách điểm giao của chuyến kèm địa chỉ và số điện thoại người nhận
 *
 * Ma trận phân quyền:
 * - WAREHOUSE_VIEW: Xem danh sách phiếu xuất và các chuyến phân tuyến
 * - WAREHOUSE_EXPORT: Phân tuyến, gán tài xế, tạo chuyến giao
 * - ROLE_ADMIN: Toàn quyền
 * - Tự động đồng bộ khi quyền thay đổi qua `auth:permissions-changed`
 * - Tuyệt đối không lưu dữ liệu kinh doanh vào localStorage/sessionStorage
 */
(async () => {
  'use strict';

  if (!Auth.requireLogin()) return;
  await Auth.ready;

  const Utils = window.DeliveryRoutesUtils;
  const $ = id => document.getElementById(id);

  // ── STATE ──
  const state = {
    warehouses: [],
    currentWarehouseId: null,
    eligibleOrders: [],
    filteredOrders: [],
    selectedArea: '',
    searchKeyword: '',
    stagedStops: [], // Array of stops in sequence: { orderId, orderCode, receiverName, receiverPhone, deliveryAddress, totalAmount, note, ... }
    recentTrips: [],
    currentPrintTrip: null,
    isLoadingEligible: false,
    isLoadingRecent: false,
    isSubmitting: false
  };

  function canRead() {
    return Auth.hasPermission('WAREHOUSE_VIEW') || Auth.hasPermission('WAREHOUSE_EXPORT');
  }

  function canWrite() {
    return Auth.hasPermission('WAREHOUSE_EXPORT');
  }

  function showMessage(type, text = '') {
    const el = $(type === 'error' ? 'routes-error' : 'routes-success');
    if (!el) return;
    el.textContent = text;
    el.hidden = !text;
    if (text && type === 'success') {
      setTimeout(() => {
        if (el.textContent === text) el.hidden = true;
      }, 6000);
    }
  }

  function updatePermissionUI() {
    const hasRead = canRead();
    const hasWrite = canWrite();

    $('routes-access-denied').hidden = hasRead;
    $('routes-workspace').hidden = !hasRead;

    const submitBtn = $('routes-submit-btn');
    if (submitBtn) {
      submitBtn.disabled = !hasWrite || state.isSubmitting || state.stagedStops.length === 0;
      if (!hasWrite) {
        submitBtn.title = 'Bạn cần quyền WAREHOUSE_EXPORT để tạo chuyến giao hàng';
      } else {
        submitBtn.removeAttribute('title');
      }
    }
  }

  // ── TẢI TÙY CHỌN & KHO HÀNG ──
  async function loadOptions() {
    if (!canRead()) return;
    try {
      const res = await Auth.apiFetch('/warehouse/delivery-trips/options');
      const body = await res.json().catch(() => null);
      if (!res.ok || !body?.success) {
        throw new Error(body?.message || 'Không tải được tùy chọn kho hàng.');
      }

      state.warehouses = Array.isArray(body.data?.warehouses) ? body.data.warehouses : [];
      const select = $('routes-warehouse-select');
      select.replaceChildren();

      if (state.warehouses.length === 0) {
        const opt = document.createElement('option');
        opt.value = '';
        opt.textContent = 'Không có kho nào trong phạm vi được phân quyền';
        select.appendChild(opt);
        state.currentWarehouseId = null;
        return;
      }

      state.warehouses.forEach((w, index) => {
        const opt = document.createElement('option');
        opt.value = String(w.id);
        opt.textContent = `${w.code} · ${w.name}`;
        if (index === 0) {
          opt.selected = true;
          state.currentWarehouseId = w.id;
        }
        select.appendChild(opt);
      });

      if (state.currentWarehouseId) {
        loadEligibleOrders();
        loadRecentTrips();
      }
    } catch (err) {
      showMessage('error', err.message);
    }
  }

  // ── TẢI DANH SÁCH ĐƠN HÀNG ĐỦ ĐIỀU KIỆN (PHIẾU XUẤT CHỜ GIAO) ──
  async function loadEligibleOrders() {
    if (!state.currentWarehouseId || !canRead()) return;

    state.isLoadingEligible = true;
    $('routes-eligible-loading').hidden = false;
    $('routes-eligible-empty').hidden = true;
    $('routes-eligible-list').replaceChildren();
    showMessage('error');

    try {
      const res = await Auth.apiFetch(`/warehouse/delivery-trips/eligible-orders?warehouseId=${encodeURIComponent(state.currentWarehouseId)}`);
      const body = await res.json().catch(() => null);

      if (!res.ok || !body?.success) {
        throw new Error(body?.message || 'Không tải được danh sách phiếu xuất chờ giao.');
      }

      state.eligibleOrders = Array.isArray(body.data) ? body.data : [];
      updateAreaFilters();
      applyOrderFilters();
    } catch (err) {
      showMessage('error', err.message);
      $('routes-eligible-empty').textContent = 'Lỗi tải danh sách phiếu xuất: ' + err.message;
      $('routes-eligible-empty').hidden = false;
    } finally {
      state.isLoadingEligible = false;
      $('routes-eligible-loading').hidden = true;
    }
  }

  // ── KHU VỰC & BỘ LỌC ──
  function updateAreaFilters() {
    const areaSelect = $('routes-area-select');
    const chipsContainer = $('routes-area-chips-container');
    areaSelect.replaceChildren();
    chipsContainer.replaceChildren();

    const defaultOpt = document.createElement('option');
    defaultOpt.value = '';
    defaultOpt.textContent = 'Tất cả khu vực';
    areaSelect.appendChild(defaultOpt);

    const areasMap = {};
    for (const order of state.eligibleOrders) {
      const area = Utils.extractArea(order.deliveryAddress);
      areasMap[area] = (areasMap[area] || 0) + 1;
    }

    const areas = Object.keys(areasMap).sort((a, b) => a.localeCompare(b, 'vi'));

    for (const area of areas) {
      const opt = document.createElement('option');
      opt.value = area;
      opt.textContent = `${area} (${areasMap[area]} đơn)`;
      if (area === state.selectedArea) opt.selected = true;
      areaSelect.appendChild(opt);

      // Render Chip
      const chip = document.createElement('button');
      chip.type = 'button';
      chip.className = `routes-chip ${area === state.selectedArea ? 'routes-chip--active' : ''}`;
      chip.textContent = `${area} (${areasMap[area]})`;
      chip.addEventListener('click', () => {
        state.selectedArea = (state.selectedArea === area) ? '' : area;
        areaSelect.value = state.selectedArea;
        updateAreaChips();
        applyOrderFilters();
      });
      chipsContainer.appendChild(chip);
    }
  }

  function updateAreaChips() {
    const chips = $('routes-area-chips-container').querySelectorAll('.routes-chip');
    chips.forEach(chip => {
      const text = chip.textContent || '';
      const isActive = state.selectedArea && text.startsWith(state.selectedArea);
      chip.classList.toggle('routes-chip--active', !!isActive);
    });
  }

  function applyOrderFilters() {
    const kw = (state.searchKeyword || '').trim().toLowerCase();
    const area = state.selectedArea;

    state.filteredOrders = state.eligibleOrders.filter(order => {
      if (area) {
        const orderArea = Utils.extractArea(order.deliveryAddress);
        if (orderArea !== area) return false;
      }
      if (kw) {
        const target = [
          order.orderCode,
          order.dealerName,
          order.receiverName,
          order.receiverPhone,
          order.deliveryAddress
        ].filter(Boolean).join(' ').toLowerCase();
        if (!target.includes(kw)) return false;
      }
      return true;
    });

    renderEligibleOrders();
  }

  function renderEligibleOrders() {
    const container = $('routes-eligible-list');
    container.replaceChildren();

    const count = state.filteredOrders.length;
    $('routes-eligible-count').textContent = `${count} phiếu`;
    $('routes-eligible-empty').hidden = count > 0;

    const stagedOrderIds = new Set(state.stagedStops.map(s => s.orderId).filter(Boolean));

    for (const order of state.filteredOrders) {
      const isStaged = stagedOrderIds.has(order.orderId);
      const card = document.createElement('article');
      card.className = `routes-order-card ${isStaged ? 'routes-order-card--selected' : ''}`;

      const area = Utils.extractArea(order.deliveryAddress);

      card.innerHTML = `
        <div class="routes-card-top">
          <label style="display: flex; align-items: center; gap: 8px; cursor: pointer; font-weight: 700;">
            <input type="checkbox" class="order-check" data-order-id="${order.orderId}" ${isStaged ? 'checked' : ''}>
            <span class="routes-card-title">${Utils.escapeHtml(order.orderCode || 'DH-' + order.orderId)}</span>
          </label>
          <span class="routes-card-area">${Utils.escapeHtml(area)}</span>
        </div>
        <div class="routes-card-info">
          <div><strong>Người nhận:</strong> ${Utils.escapeHtml(order.receiverName || order.dealerName || '—')}</div>
          <div><strong>Điện thoại:</strong> ${Utils.escapeHtml(order.receiverPhone || '—')}</div>
          <div><strong>Địa chỉ:</strong> ${Utils.escapeHtml(order.deliveryAddress || 'Tại kho')}</div>
          ${order.expectedDeliveryDate ? `<div><strong>Ngày hẹn giao:</strong> ${Utils.escapeHtml(order.expectedDeliveryDate)}</div>` : ''}
        </div>
        <div class="routes-card-bottom">
          <span style="font-weight: 700; color: var(--teal-deep);">${Utils.formatMoney(order.totalAmount)}</span>
          <button type="button" class="btn btn--secondary btn-toggle-stage" data-order-id="${order.orderId}" style="min-height: 32px; padding: 4px 10px; font-size: 0.8rem;">
            ${isStaged ? 'Bỏ khỏi tuyến' : 'Gom vào tuyến'}
          </button>
        </div>
      `;

      // Event listeners
      const checkbox = card.querySelector('.order-check');
      const toggleBtn = card.querySelector('.btn-toggle-stage');

      const toggleAction = () => {
        if (isStaged) {
          removeStopByOrderId(order.orderId);
        } else {
          addOrderToStaged(order);
        }
      };

      checkbox.addEventListener('change', toggleAction);
      toggleBtn.addEventListener('click', toggleAction);

      container.appendChild(card);
    }
  }

  // ── QUẢN LÝ LỘ TRÌNH ĐIỂM DỪNG (STAGED STOPS) ──
  function addOrderToStaged(order) {
    if (state.stagedStops.some(s => s.orderId === order.orderId)) return;

    state.stagedStops.push({
      orderId: order.orderId,
      orderCode: order.orderCode,
      dealerId: order.dealerId,
      dealerName: order.dealerName,
      deliveryPointId: order.deliveryPointId,
      receiverName: order.receiverName || order.dealerName,
      receiverPhone: order.receiverPhone,
      deliveryAddress: order.deliveryAddress,
      deliveryAmount: order.totalAmount,
      totalAmount: order.totalAmount,
      note: ''
    });

    renderStagedStops();
    renderEligibleOrders();
  }

  function removeStopByOrderId(orderId) {
    state.stagedStops = state.stagedStops.filter(s => s.orderId !== orderId);
    renderStagedStops();
    renderEligibleOrders();
  }

  function removeStopByIndex(index) {
    state.stagedStops.splice(index, 1);
    renderStagedStops();
    renderEligibleOrders();
  }

  function moveStop(index, direction) {
    const target = index + direction;
    if (target < 0 || target >= state.stagedStops.length) return;
    const temp = state.stagedStops[index];
    state.stagedStops[index] = state.stagedStops[target];
    state.stagedStops[target] = temp;
    renderStagedStops();
  }

  function selectAllCurrentArea() {
    for (const order of state.filteredOrders) {
      if (!state.stagedStops.some(s => s.orderId === order.orderId)) {
        state.stagedStops.push({
          orderId: order.orderId,
          orderCode: order.orderCode,
          dealerId: order.dealerId,
          dealerName: order.dealerName,
          deliveryPointId: order.deliveryPointId,
          receiverName: order.receiverName || order.dealerName,
          receiverPhone: order.receiverPhone,
          deliveryAddress: order.deliveryAddress,
          deliveryAmount: order.totalAmount,
          totalAmount: order.totalAmount,
          note: ''
        });
      }
    }
    renderStagedStops();
    renderEligibleOrders();
  }

  function clearStagedStops() {
    state.stagedStops = [];
    renderStagedStops();
    renderEligibleOrders();
  }

  function renderStagedStops() {
    const container = $('routes-staged-stops');
    container.replaceChildren();

    const count = state.stagedStops.length;
    const totalAmount = state.stagedStops.reduce((sum, s) => sum + Number(s.deliveryAmount || 0), 0);

    $('routes-staged-badge').textContent = `${count} điểm dừng`;
    $('routes-kpi-stops').textContent = String(count);
    $('routes-kpi-amount').textContent = Utils.formatMoney(totalAmount);
    $('routes-staged-empty').hidden = count > 0;

    state.stagedStops.forEach((stop, index) => {
      const item = document.createElement('div');
      item.className = 'routes-stop-item';

      const isFirst = index === 0;
      const isLast = index === count - 1;

      item.innerHTML = `
        <div class="routes-stop-index">${index + 1}</div>
        <div class="routes-stop-content">
          <div class="routes-stop-title">
            ${Utils.escapeHtml(stop.orderCode || 'Điểm giao')} · ${Utils.escapeHtml(stop.receiverName || 'Khách hàng')}
          </div>
          <div class="routes-stop-details">
            <div><strong>Điện thoại:</strong> ${Utils.escapeHtml(stop.receiverPhone || '—')}</div>
            <div><strong>Địa chỉ:</strong> ${Utils.escapeHtml(stop.deliveryAddress || 'Tại kho')}</div>
            <div><strong>Tiền hàng:</strong> <span style="font-weight: 700; color: var(--teal-deep);">${Utils.formatMoney(stop.deliveryAmount)}</span></div>
          </div>
          <input type="text" class="routes-stop-note-input" placeholder="Ghi chú giao điểm này (ví dụ: giao trước 11h, gọi trước khi đến…)" value="${Utils.escapeHtml(stop.note || '')}" data-index="${index}">
        </div>
        <div class="routes-stop-controls">
          <button type="button" class="routes-btn-icon btn-move-up" data-index="${index}" title="Chuyển lên trước" ${isFirst ? 'disabled' : ''}>▲</button>
          <button type="button" class="routes-btn-icon btn-move-down" data-index="${index}" title="Chuyển xuống sau" ${isLast ? 'disabled' : ''}>▼</button>
          <button type="button" class="routes-btn-icon btn-remove" data-index="${index}" title="Xóa khỏi tuyến" style="color: var(--danger);">✕</button>
        </div>
      `;

      // Stop event listeners
      const noteInput = item.querySelector('.routes-stop-note-input');
      noteInput.addEventListener('input', e => {
        state.stagedStops[index].note = e.target.value;
      });

      item.querySelector('.btn-move-up')?.addEventListener('click', () => moveStop(index, -1));
      item.querySelector('.btn-move-down')?.addEventListener('click', () => moveStop(index, 1));
      item.querySelector('.btn-remove')?.addEventListener('click', () => removeStopByIndex(index));

      container.appendChild(item);
    });

    updatePermissionUI();
  }

  // ── SUBMIT CHUYẾN GIAO HÀNG ──
  async function submitDispatch(e) {
    e.preventDefault();
    if (!canWrite()) {
      showMessage('error', 'Bạn không có quyền WAREHOUSE_EXPORT để phân tuyến tạo chuyến.');
      return;
    }

    const warehouseId = state.currentWarehouseId;
    const driverName = $('route-driver-name').value.trim();
    const driverPhone = $('route-driver-phone').value.trim();
    const vehiclePlate = $('route-vehicle-plate').value.trim();
    const tripCode = $('route-trip-code').value.trim();
    const departureTime = $('route-departure-time').value || null;
    const expectedCompletionTime = $('route-expected-time').value;
    const note = $('route-trip-note').value.trim();

    const formData = {
      warehouseId,
      driverName,
      driverPhone,
      vehiclePlate,
      tripCode,
      departureTime,
      expectedCompletionTime,
      note
    };

    const validation = Utils.validateTripData({
      ...formData,
      stops: state.stagedStops
    });

    if (!validation.isValid) {
      showMessage('error', validation.errors.join(' '));
      return;
    }

    const payload = Utils.buildCreateTripPayload(formData, state.stagedStops);

    state.isSubmitting = true;
    updatePermissionUI();
    showMessage('error');

    try {
      const res = await Auth.apiFetch('/warehouse/delivery-trips', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload)
      });

      const body = await res.json().catch(() => null);

      if (!res.ok || !body?.success) {
        throw new Error(body?.message || 'Không tạo được chuyến giao hàng. Vui lòng thử lại.');
      }

      const createdTrip = body.data;
      showMessage('success', `Phân tuyến thành công chuyến ${createdTrip.tripCode} gồm ${createdTrip.totalPoints || state.stagedStops.length} điểm giao!`);

      // Reset form & staged stops
      $('routes-dispatch-form').reset();
      initFormTimes();
      clearStagedStops();

      // Refresh data
      loadEligibleOrders();
      loadRecentTrips();

      // Open print modal immediately for the new trip
      openPrintModal(createdTrip);
    } catch (err) {
      showMessage('error', err.message);
    } finally {
      state.isSubmitting = false;
      updatePermissionUI();
    }
  }

  // ── DANH SÁCH CHUYẾN PHÂN TUYẾN GẦN ĐÂY ──
  async function loadRecentTrips() {
    if (!state.currentWarehouseId || !canRead()) return;

    state.isLoadingRecent = true;
    const tbody = $('routes-recent-trips-tbody');
    tbody.innerHTML = '<tr><td colspan="8" class="routes-muted" style="text-align: center; padding: 24px;">Đang tải danh sách chuyến…</td></tr>';

    try {
      const res = await Auth.apiFetch(`/warehouse/delivery-trips?warehouseId=${encodeURIComponent(state.currentWarehouseId)}&size=10`);
      const body = await res.json().catch(() => null);

      if (!res.ok || !body?.success) {
        throw new Error(body?.message || 'Không tải được danh sách chuyến gần đây.');
      }

      state.recentTrips = Array.isArray(body.data?.content) ? body.data.content : [];
      renderRecentTrips();
    } catch (err) {
      tbody.innerHTML = `<tr><td colspan="8" class="routes-error" style="text-align: center;">${Utils.escapeHtml(err.message)}</td></tr>`;
    } finally {
      state.isLoadingRecent = false;
    }
  }

  function renderRecentTrips() {
    const tbody = $('routes-recent-trips-tbody');
    tbody.replaceChildren();

    if (state.recentTrips.length === 0) {
      tbody.innerHTML = '<tr><td colspan="8" class="routes-muted" style="text-align: center; padding: 24px;">Chưa có chuyến giao nào được tạo ở kho này.</td></tr>';
      return;
    }

    state.recentTrips.forEach(trip => {
      const tr = document.createElement('tr');
      tr.innerHTML = `
        <td class="font-bold font-mono">${Utils.escapeHtml(trip.tripCode)}</td>
        <td>${Utils.escapeHtml(trip.warehouseName || trip.warehouseCode || '—')}</td>
        <td>
          <strong>${Utils.escapeHtml(trip.driverName)}</strong>
          <div style="font-size: 0.8rem; color: var(--ink-600);">${Utils.escapeHtml(trip.vehiclePlate)} · ${Utils.escapeHtml(trip.driverPhone || '—')}</div>
        </td>
        <td class="text-center font-bold">${Utils.formatNumber(trip.totalPoints)}</td>
        <td style="font-weight: 700; color: var(--teal-deep);">${Utils.formatMoney(trip.totalValue)}</td>
        <td style="font-size: 0.8rem;">
          <div>Khởi hành: ${Utils.formatDateTime(trip.departureTime)}</div>
          <div>Dự kiến: ${Utils.formatDateTime(trip.expectedCompletionTime)}</div>
        </td>
        <td><span class="routes-badge">${Utils.escapeHtml(trip.statusDisplayName || trip.status)}</span></td>
        <td style="text-align: center;">
          <div class="routes-actions" style="justify-content: center;">
            <button type="button" class="btn btn--primary btn-print-trip" data-trip-id="${trip.id}" style="min-height: 32px; padding: 4px 10px; font-size: 0.8rem;">
              In danh sách điểm giao
            </button>
            <a href="/warehouse/delivery-trips" class="btn btn--secondary" style="min-height: 32px; padding: 4px 10px; font-size: 0.8rem;">
              Theo dõi
            </a>
          </div>
        </td>
      `;

      const printBtn = tr.querySelector('.btn-print-trip');
      printBtn.addEventListener('click', () => fetchAndPrintTrip(trip.id));

      tbody.appendChild(tr);
    });
  }

  // ── IN DANH SÁCH ĐIỂM GIAO CỦA CHUYẾN ──
  async function fetchAndPrintTrip(tripId) {
    showMessage('error');
    try {
      const res = await Auth.apiFetch(`/warehouse/delivery-trips/${encodeURIComponent(tripId)}`);
      const body = await res.json().catch(() => null);
      if (!res.ok || !body?.success || !body.data) {
        throw new Error(body?.message || 'Không tải được chi tiết chuyến để in.');
      }
      openPrintModal(body.data);
    } catch (err) {
      showMessage('error', err.message);
    }
  }

  function openPrintModal(trip) {
    state.currentPrintTrip = trip;
    const modal = $('routes-print-modal');
    const printable = $('routes-printable-area');

    printable.innerHTML = Utils.generateManifestHtml(trip);
    modal.hidden = false;
    $('routes-print-now-btn').focus();
  }

  function closePrintModal() {
    state.currentPrintTrip = null;
    $('routes-print-modal').hidden = true;
    $('routes-printable-area').replaceChildren();
  }

  function initFormTimes() {
    const now = new Date();
    $('route-departure-time').value = Utils.toDatetimeLocalValue(now);

    const completion = new Date(now.getTime() + 3 * 60 * 60 * 1000); // Mặc định +3 tiếng
    $('route-expected-time').value = Utils.toDatetimeLocalValue(completion);
  }

  // ── SETUP EVENT LISTENERS ──
  function setupEvents() {
    $('routes-warehouse-select').addEventListener('change', e => {
      state.currentWarehouseId = e.target.value ? Number(e.target.value) : null;
      state.selectedArea = '';
      state.stagedStops = [];
      renderStagedStops();
      loadEligibleOrders();
      loadRecentTrips();
    });

    $('routes-area-select').addEventListener('change', e => {
      state.selectedArea = e.target.value;
      updateAreaChips();
      applyOrderFilters();
    });

    $('routes-search-input').addEventListener('input', e => {
      state.searchKeyword = e.target.value;
      applyOrderFilters();
    });

    $('routes-select-all-area-btn').addEventListener('click', selectAllCurrentArea);
    $('routes-clear-filter-btn').addEventListener('click', () => {
      state.selectedArea = '';
      state.searchKeyword = '';
      $('routes-area-select').value = '';
      $('routes-search-input').value = '';
      updateAreaChips();
      applyOrderFilters();
    });

    $('routes-clear-staged-btn').addEventListener('click', clearStagedStops);
    $('routes-dispatch-form').addEventListener('submit', submitDispatch);

    $('routes-reload-btn').addEventListener('click', () => {
      loadOptions();
    });

    $('routes-refresh-recent-btn').addEventListener('click', () => {
      loadRecentTrips();
    });

    // Print Modal Events
    $('routes-modal-close-x').addEventListener('click', closePrintModal);
    $('routes-modal-close-btn').addEventListener('click', closePrintModal);
    $('routes-print-now-btn').addEventListener('click', () => {
      window.print();
    });

    // Close on backdrop click
    $('routes-print-modal').addEventListener('click', e => {
      if (e.target === $('routes-print-modal')) closePrintModal();
    });

    // Close on Escape key
    window.addEventListener('keydown', e => {
      if (e.key === 'Escape' && !$('routes-print-modal').hidden) {
        closePrintModal();
      }
    });

    // Synchronize permission matrix
    window.addEventListener('auth:permissions-changed', () => {
      updatePermissionUI();
      if (!canRead()) {
        state.stagedStops = [];
        renderStagedStops();
      } else {
        loadOptions();
      }
    });
  }

  // ── INITIALIZE ──
  initFormTimes();
  setupEvents();
  updatePermissionUI();

  if (canRead()) {
    loadOptions();
  }
})();
