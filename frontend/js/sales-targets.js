/**
 * sales-targets.js — S8-04: Quản lý chỉ tiêu doanh số.
 *
 * Quyền:
 *  - REPORT_SALES   : xem danh sách chỉ tiêu tất cả nhân viên (Quản lý)
 *  - ORDER_APPROVE : đặt / sửa chỉ tiêu (Quản lý)
 *  - REPORT_SALES: nhân viên xem tiến độ của chính mình
 *
 * Kỳ đã qua bị khoá tự động; sửa bắt buộc nhập lý do và ghi nhật ký.
 * Tuyệt đối không lưu dữ liệu kinh doanh vào localStorage / sessionStorage.
 * Tự phản ứng với auth:permissions-changed.
 */
(function () {
  'use strict';

  /* ── GUARD ─────────────────────────────────────────────────────────── */
  if (!window.SalesTargetUtils) {
    console.error('SalesTargetUtils chưa được tải.');
    return;
  }
  const U = window.SalesTargetUtils;

  if (!Auth.requireLogin()) return;

  /* ── QUYỀN ─────────────────────────────────────────────────────────── */
  const PERM_VIEW   = 'REPORT_SALES';
  const PERM_MANAGE = 'ORDER_APPROVE';
  const PERM_OWN    = 'REPORT_SALES';

  function canViewAll()  { return Auth.hasPermission(PERM_VIEW) || canManage(); }
  function canManage()   { return Auth.hasPermission(PERM_MANAGE); }
  function canViewOwn()  { return Auth.hasPermission(PERM_OWN) || canManage(); }
  function isAdmin()     { const u = Auth.getUser(); return (u?.roles || []).includes('ROLE_ADMIN'); }

  function hasAnyAccess() {
    return isAdmin() || canViewAll() || canViewOwn();
  }

  /* ── TRẠNG THÁI ─────────────────────────────────────────────────────── */
  const state = {
    period: new Date().toISOString().slice(0, 7),
    viewMode: 'employee',     // 'employee' | 'region'
    activeTab: 'manager',     // 'manager' | 'my'
    regionFilter: '',
    keyword: '',
    filterStatus: '',
    empPage: 0,
    empPageSize: 20,
    empTotal: 0,
    empPages: 0,
    myPage: 0,
    myPageSize: 20,
    myTotal: 0,
    myPages: 0,
    employees: [],
    options: null,
    editingTarget: null,
    periodLocked: false,
    regions: [],
    regionSummaries: [],
    auditLog: [],
    myProgress: null,
    myOrders: [],
    loading: false,
    submitting: false,
  };

  /* ── DOM REFS ───────────────────────────────────────────────────────── */
  const $ = id => document.getElementById(id);

  /* ── REQUESTS ───────────────────────────────────────────────────────── */
  const controllers = {};
  let generation = 0;
  function abort(key) { controllers[key]?.abort(); delete controllers[key]; }
  function abortAll() { Object.keys(controllers).forEach(abort); }

  async function apiFetch(path, opts = {}) {
    const c = new AbortController();
    const version = generation;
    const key = opts._key || path;
    abort(key);
    controllers[key] = c;
    try {
      const resp = await Auth.apiFetch(path, { signal: c.signal, cache: 'no-store', ...opts });
      const text = await resp.text();
      if (version !== generation || controllers[key] !== c || !hasAnyAccess()) throw Object.assign(new Error(), {name:'AbortError'});
      let payload;
      try { payload = JSON.parse(text); } catch { throw new Error('Máy chủ trả dữ liệu không hợp lệ.'); }
      if (!resp.ok || payload.success === false) {
        throw Object.assign(new Error(payload.message || `Lỗi ${resp.status}`), { status: resp.status });
      }
      return payload.data ?? payload;
    } finally {
      if (controllers[key] === c) delete controllers[key];
    }
  }

  /* ── HIỂN THỊ THÔNG BÁO ─────────────────────────────────────────────── */
  function showGlobalError(msg) {
    const el = $('st-global-error');
    if (!el) return;
    el.textContent = msg || '';
    el.hidden = !msg;
  }
  function showGlobalSuccess(msg) {
    const el = $('st-global-success');
    if (!el) return;
    el.textContent = msg || '';
    el.hidden = !msg;
    if (msg) setTimeout(() => { el.hidden = true; }, 5000);
  }
  function showModalError(msg) {
    const el = $('st-modal-error');
    if (!el) return;
    el.textContent = msg || '';
    el.hidden = !msg;
  }

  /* ── PHÂN QUYỀN HIỂN THỊ ───────────────────────────────────────────── */
  function updateAccess() {
    const access = hasAnyAccess();
    $('st-workspace').hidden = !access;
    $('st-access-denied').hidden = access;
    $('st-new-btn').hidden = !(isAdmin() || canManage());
    $('tab-manager').hidden = !(isAdmin() || canViewAll());
    $('tab-my').hidden = !(isAdmin() || canViewOwn());
    updateTabVisibility();
  }

  function updateTabVisibility() {
    const managerVisible = !(isAdmin() || canViewAll());
    // Nếu không có quyền quản lý nhưng có quyền xem của mình, ẩn tab manager
    if (managerVisible && state.activeTab === 'manager') {
      setTab('my');
    }
    // Nếu chỉ có quyền view all mà không có view own, ẩn tab my
    if (!isAdmin() && !canViewOwn() && state.activeTab === 'my') {
      setTab('manager');
    }
  }

  /* ── TABS ───────────────────────────────────────────────────────────── */
  function setTab(tab) {
    state.activeTab = tab;
    $('tab-manager').setAttribute('aria-selected', String(tab === 'manager'));
    $('tab-my').setAttribute('aria-selected', String(tab === 'my'));
    $('panel-manager').hidden = (tab !== 'manager');
    $('panel-my').hidden = (tab !== 'my');
  }

  $('tab-manager').addEventListener('click', () => { setTab('manager'); loadManager(); });
  $('tab-my').addEventListener('click', () => { setTab('my'); loadMyProgress(); });

  /* ── KỲ THÁNG ───────────────────────────────────────────────────────── */
  function currentPeriod() { return state.period; }

  function updatePeriodBadge(period) {
    const rel = U.comparePeriod(period);
    const badge = $('st-period-badge');
    const classes = { past: 'badge--locked', current: 'badge--current', future: 'badge--open' };
    badge.className = 'badge ' + (classes[rel] || '');
    badge.textContent = U.periodRelLabel(rel);
    $('st-period-snapshot').textContent = U.periodLabel(period);
    const locked = rel === 'past';
    $('st-locked-notice').hidden = !locked;
    return locked;
  }

  $('st-period-input').addEventListener('change', () => {
    const val = $('st-period-input').value;
    if (!U.isValidPeriod(val)) return;
    state.period = val;
    state.empPage = 0;
    state.myPage = 0;
    updatePeriodBadge(val);
    reload();
  });

  $('st-view-mode').addEventListener('change', () => {
    state.viewMode = $('st-view-mode').value;
    renderManagerView();
  });

  $('st-region-filter').addEventListener('change', () => {
    state.regionFilter = $('st-region-filter').value;
    state.empPage = 0;
    renderEmployeeTable();
  });

  /* ── RELOAD / LOAD ──────────────────────────────────────────────────── */
  $('st-reload').addEventListener('click', reload);

  async function reload() {
    if (!hasAnyAccess()) { updateAccess(); return; }
    showGlobalError('');
    if (state.activeTab === 'manager' && (isAdmin() || canViewAll())) {
      await loadManager();
    }
    if (state.activeTab === 'my' && (isAdmin() || canViewOwn())) {
      await loadMyProgress();
    }
  }

  /* ── LOAD MANAGER ───────────────────────────────────────────────────── */
  async function loadManager() {
    if (!isAdmin() && !canViewAll()) return;
    showGlobalError('');
    try {
      const params = new URLSearchParams({ period: state.period });
      const data = await apiFetch('/api/sales-targets/summary?' + params, { _key: 'summary' });
      const options = await apiFetch('/api/sales-targets/options', {_key:'options'});
      if (!canViewAll() || state.period !== data.period) return;
      state.options = options;
      state.periodLocked = Boolean(data.pastPeriod);
      const targets = data.targets || [];
      state.employees = (options.employees || []).map(employee => {
        const progress = (data.employeeProgress || []).find(row => row.userId === employee.id) || {};
        const target = targets.find(row => row.userId === employee.id && row.scopeType === 'EMPLOYEE');
        return {...progress, employeeId:employee.id,employeeName:employee.fullName||employee.username,
          username:employee.username,regionIds:employee.territoryIds||[],regionName:(employee.territoryNames||[]).join(', '),
          targetId:target?.id,locked:target?.locked||data.pastPeriod,targetAmount:target?.targetAmount??progress.targetAmount??0,actualSales:progress.actualAmount||0};
      });
      state.regionSummaries = (data.territoryProgress || []).map(row => ({...row,
        regionId:row.territoryId,regionName:row.territoryName,totalTarget:row.targetAmount,actualSales:row.actualAmount,
        targetId:targets.find(t=>t.scopeType==='TERRITORY'&&t.territoryId===row.territoryId)?.id,
        employeeCount:(options.employees||[]).filter(e=>(e.territoryIds||[]).includes(row.territoryId)).length}));
      state.auditLog = [];
      const histories = await Promise.all(targets.filter(t=>t.locked).map(t=>apiFetch('/api/sales-targets/'+t.id+'/audit-logs', {_key:'audit-'+t.id})));
      state.auditLog = histories.flat();
      // Populate region filter
      populateRegions((options.territories || []).map(row=>({regionId:row.id,regionName:row.name})));
      renderManagerView();
      renderAuditLog();
    } catch (err) {
      if (err.name === 'AbortError') return;
      if (err.status === 403) {
        updateAccess();
        return;
      }
      showGlobalError(err.message || 'Không tải được dữ liệu chỉ tiêu. Bấm Tải lại để thử lại.');
    }
  }

  function populateRegions(regions) {
    const select = $('st-region-filter');
    const prev = select.value;
    while (select.options.length > 1) select.remove(1);
    regions.forEach(r => {
      const opt = document.createElement('option');
      opt.value = r.regionId || r.regionName || '';
      opt.textContent = r.regionName || '(Chưa phân khu vực)';
      select.appendChild(opt);
    });
    select.value = prev && [...select.options].some(o => o.value === prev) ? prev : '';
    state.regionFilter = select.value;
  }

  /* ── MANAGER VIEW ───────────────────────────────────────────────────── */
  function renderManagerView() {
    const isRegion = state.viewMode === 'region';
    $('st-employee-panel').hidden = isRegion;
    $('st-region-panel').hidden = !isRegion;
    if (isRegion) {
      renderRegionView();
    } else {
      renderEmployeeTable();
    }
  }

  function applyFilters(employees) {
    let list = employees;
    if (state.regionFilter) {
      list = list.filter(e => (e.regionIds||[]).some(id=>String(id)===state.regionFilter));
    }
    if (state.keyword) {
      const kw = state.keyword.toLowerCase();
      list = list.filter(e =>
        (e.employeeName || '').toLowerCase().includes(kw) ||
        (e.username || '').toLowerCase().includes(kw)
      );
    }
    if (state.filterStatus === 'SET') {
      list = list.filter(e => e.targetAmount > 0);
    } else if (state.filterStatus === 'UNSET') {
      list = list.filter(e => !e.targetAmount || e.targetAmount === 0);
    }
    return list;
  }

  function renderEmployeeTable() {
    const locked = state.periodLocked;
    const filtered = applyFilters(state.employees);
    state.empTotal = filtered.length;
    state.empPages = Math.ceil(state.empTotal / state.empPageSize) || 1;
    if (state.empPage >= state.empPages) state.empPage = Math.max(0, state.empPages - 1);
    const page = filtered.slice(state.empPage * state.empPageSize, (state.empPage + 1) * state.empPageSize);

    $('st-employee-count').textContent = `${state.empTotal} nhân viên`;
    $('st-employee-page').textContent = state.empTotal ? `Trang ${state.empPage + 1}/${state.empPages}` : '0 trang';
    $('st-emp-prev').disabled = state.empPage === 0;
    $('st-emp-next').disabled = state.empPage + 1 >= state.empPages;

    const canEdit = isAdmin() || canManage();
    const esc = U.escape;

    if (!page.length) {
      $('st-employee-rows').innerHTML = '<tr><td colspan="7" class="st-muted" style="text-align:center;padding:24px;">Không có dữ liệu nhân viên nào.</td></tr>';
      return;
    }

    $('st-employee-rows').innerHTML = page.map(emp => {
      const pct = U.calcProgress(emp.actualSales, emp.targetAmount);
      const level = U.progressLevel(pct);
      const barClass = level === 'full' ? '' : level === 'ok' ? '' : level === 'caution' ? 'st-mini-bar--caution' : 'st-mini-bar--warn';
      const w = Math.min(pct, 100);
      const statusBadge = emp.targetAmount > 0
        ? `<span class="badge badge--open">Đã đặt</span>`
        : `<span class="badge badge--past">Chưa đặt</span>`;
      const lockedBadge = locked ? ` <span class="badge badge--locked" title="Kỳ đã qua, bị khoá">Khoá</span>` : '';

      const editBtn = canEdit
        ? `<button class="btn btn--secondary" style="font-size:.75rem;padding:5px 10px;"
             data-st-edit="${esc(emp.employeeId)}" type="button">Sửa chỉ tiêu</button>`
        : '';

      return `<tr>
        <td>${esc(emp.employeeName)}<small>${esc(emp.username || '')}</small></td>
        <td>${esc(emp.regionName || 'Chưa phân khu vực')}</td>
        <td class="st-num">${esc(U.formatMoney(emp.targetAmount || 0))}</td>
        <td class="st-num">${esc(U.formatMoney(emp.actualSales || 0))}</td>
        <td class="st-progress-cell">
          <span>${pct}%</span>
          <div class="st-mini-track" aria-hidden="true"><div class="st-mini-bar ${esc(barClass)}" style="width:${w}%"></div></div>
        </td>
        <td>${statusBadge}${lockedBadge}</td>
        <td>${editBtn}</td>
      </tr>`;
    }).join('');

    // Edit buttons
    document.querySelectorAll('[data-st-edit]').forEach(btn => {
      btn.addEventListener('click', () => {
        const empId = btn.dataset.stEdit;
        const emp = state.employees.find(e => String(e.employeeId) === String(empId));
        if (emp) openModal(emp);
      });
    });
  }

  /* ── REGION VIEW ─────────────────────────────────────────────────────── */
  function renderRegionView() {
    const esc = U.escape;
    const regions = state.regionSummaries;
    $('st-region-count').textContent = `${regions.length} khu vực`;

    // Cards
    if (regions.length) {
      $('st-region-cards').innerHTML = regions.map(r => {
        const pct = U.calcProgress(r.actualSales, r.totalTarget);
        const level = U.progressLevel(pct);
        const valClass = level === 'full' || level === 'ok' ? 'st-kpi__value--ok' : level === 'caution' ? '' : 'st-kpi__value--warn';
        return `<div class="st-region-card" role="listitem">
          <h3>${esc(r.regionName || 'Chưa phân khu vực')}</h3>
          <p class="st-kpi__label">Chỉ tiêu</p>
          <p class="st-kpi__value">${esc(U.formatMoney(r.totalTarget || 0))}</p>
          <p class="st-kpi__label">Thực tế</p>
          <p class="st-kpi__value ${valClass}">${esc(U.formatMoney(r.actualSales || 0))}</p>
          <p class="st-muted">${pct}% hoàn thành — ${r.employeeCount || 0} nhân viên</p>
        </div>`;
      }).join('');
    } else {
      $('st-region-cards').innerHTML = '<p class="st-muted">Chưa có dữ liệu khu vực.</p>';
    }

    // Table
    if (!regions.length) {
      $('st-region-rows').innerHTML = '<tr><td colspan="5" class="st-muted" style="text-align:center;padding:24px;">Chưa có dữ liệu.</td></tr>';
      return;
    }
    $('st-region-rows').innerHTML = regions.map(r => {
      const pct = U.calcProgress(r.actualSales, r.totalTarget);
      const w = Math.min(pct, 100);
      return `<tr>
        <td>${esc(r.regionName || 'Chưa phân khu vực')}</td>
        <td class="st-num">${esc(U.formatMoney(r.totalTarget || 0))}</td>
        <td class="st-num">${esc(U.formatMoney(r.actualSales || 0))}</td>
        <td class="st-progress-cell">
          <span>${pct}%</span>
          <div class="st-mini-track" aria-hidden="true"><div class="st-mini-bar" style="width:${w}%"></div></div>
        </td>
        <td>${esc(r.employeeCount || 0)} nhân viên${isAdmin()||canManage()?` <button class="btn btn--secondary" type="button" data-st-region="${esc(r.regionId)}">Sửa chỉ tiêu</button>`:''}</td>
      </tr>`;
    }).join('');
    document.querySelectorAll('[data-st-region]').forEach(btn=>btn.addEventListener('click',()=>{const row=state.regionSummaries.find(r=>String(r.regionId)===btn.dataset.stRegion);if(row)openModal({...row,employeeName:row.regionName,territoryId:row.regionId,targetAmount:row.totalTarget,locked:row.locked||state.periodLocked});}));
  }

  /* ── AUDIT LOG ───────────────────────────────────────────────────────── */
  function renderAuditLog() {
    const esc = U.escape;
    const logs = state.auditLog;
    if (!logs.length) {
      $('st-audit-list').innerHTML = '<li class="st-audit-item"><div class="st-audit-dot"></div><div class="st-audit-body st-muted">Chưa có thay đổi nào được ghi nhận trong kỳ này.</div></li>';
      return;
    }
    $('st-audit-list').innerHTML = logs.map(entry => `
      <li class="st-audit-item">
        <div class="st-audit-dot"></div>
        <div class="st-audit-body">
          <strong>${esc(entry.username || '—')}</strong>: ${esc(entry.action)}<br>
          ${esc(entry.oldValue || '—')} → ${esc(entry.newValue || '—')}
          <br>
          <small>Lý do: ${esc(entry.reason || '—')} &nbsp;·&nbsp; ${esc(entry.username || '—')} &nbsp;·&nbsp; ${esc(entry.createdAt || '')}</small>
        </div>
      </li>
    `).join('');
  }

  /* ── MY PROGRESS ─────────────────────────────────────────────────────── */
  async function loadMyProgress() {
    if (!isAdmin() && !canViewOwn()) return;
    showGlobalError('');
    try {
      const params = new URLSearchParams({ period: state.period });
      const data = await apiFetch('/api/sales-targets/my-progress?' + params, { _key: 'myprogress' });
      state.myProgress = {...data,targetAmount:data.totalTargetAmount,actualSales:data.actualAmount};
      state.myOrders = [...(data.personalTargets||[]),...(data.territoryTargets||[])].map(row=>({
        orderCode:row.scopeTypeLabel,orderDate:row.period,dealerName:row.territoryName||data.fullName,
        revenue:row.actualAmount,status:row.progressStatusLabel}));
      state.myTotal = state.myOrders.length;
      state.myPages = Math.ceil(state.myTotal / state.myPageSize) || 1;
      renderMyProgress();
    } catch (err) {
      if (err.name === 'AbortError') return;
      if (err.status === 403) { updateAccess(); return; }
      showGlobalError(err.message || 'Không tải được tiến độ. Bấm Tải lại để thử lại.');
    }
  }

  function renderMyProgress() {
    const d = state.myProgress;
    if (!d) return;

    const hasTarget = d.targetAmount > 0;
    $('st-my-pending').hidden = hasTarget;
    $('st-my-kpis').hidden = !hasTarget;
    $('st-my-progress-section').hidden = !hasTarget;

    renderMyOrders();

    if (!hasTarget) return;

    const pct = U.calcProgress(d.actualSales, d.targetAmount);
    const level = U.progressLevel(pct);
    const remaining = Math.max(0, (d.targetAmount || 0) - (d.actualSales || 0));

    // KPI cards
    $('kpi-target').textContent = U.formatMoney(d.targetAmount);
    $('kpi-period-label').textContent = U.periodLabel(state.period);
    $('kpi-actual').textContent = U.formatMoney(d.actualSales || 0);
    const pctEl = $('kpi-pct');
    pctEl.textContent = pct + '%';
    pctEl.className = 'st-kpi__value' + (level === 'full' || level === 'ok' ? ' st-kpi__value--ok' : level === 'danger' ? ' st-kpi__value--warn' : '');
    $('kpi-pct-label').textContent = level === 'full' ? '✓ Hoàn thành chỉ tiêu' : level === 'ok' ? 'Đang đạt tốt' : level === 'caution' ? 'Cần nỗ lực thêm' : 'Chưa đạt tiến độ';
    $('kpi-remaining').textContent = remaining > 0 ? U.formatMoney(remaining) : '—';

    // Progress bar
    const bar = $('st-progress-bar');
    const barClasses = { danger: 'st-progress-bar--low', caution: 'st-progress-bar--mid', ok: 'st-progress-bar--high', full: 'st-progress-bar--full' };
    bar.className = 'st-progress-bar ' + (barClasses[level] || '');
    bar.style.width = Math.min(pct, 100) + '%';
    const track = $('st-progress-track');
    track.setAttribute('aria-valuenow', String(Math.min(pct, 100)));
    track.setAttribute('aria-label', `Tiến độ doanh số: ${pct}%`);
    $('st-progress-month').textContent = U.periodLabel(state.period);
    $('st-progress-actual-label').textContent = U.formatMoney(d.actualSales || 0);
    $('st-progress-target-label').textContent = 'Chỉ tiêu: ' + U.formatMoney(d.targetAmount);

    renderMyOrders();
  }

  function renderMyOrders() {
    const esc = U.escape;
    if (state.myPage >= state.myPages) state.myPage = Math.max(0, state.myPages - 1);
    const page = state.myOrders.slice(state.myPage * state.myPageSize, (state.myPage + 1) * state.myPageSize);

    $('st-my-detail-count').textContent = `${state.myTotal} chỉ tiêu trong kỳ`;
    $('st-my-page').textContent = state.myTotal ? `Trang ${state.myPage + 1}/${state.myPages}` : '0 trang';
    $('st-my-prev').disabled = state.myPage === 0;
    $('st-my-next').disabled = state.myPage + 1 >= state.myPages;

    if (!page.length) {
      $('st-my-detail-rows').innerHTML = '<tr><td colspan="4" class="st-muted" style="text-align:center;padding:24px;">Chưa có chỉ tiêu chi tiết trong kỳ này.</td></tr>';
      return;
    }
    $('st-my-detail-rows').innerHTML = page.map(o => `
      <tr>
        <td>${esc(o.orderCode || o.orderId || '—')}<small>${esc(o.orderDate || '')}</small></td>
        <td>${esc(o.dealerName || '—')}</td>
        <td class="st-num">${esc(U.formatMoney(o.revenue || 0))}</td>
        <td>${esc(o.status || '—')}</td>
      </tr>
    `).join('');
  }

  /* ── PHÂN TRANG ─────────────────────────────────────────────────────── */
  $('st-emp-prev').addEventListener('click', () => {
    if (state.empPage > 0) { state.empPage--; renderEmployeeTable(); }
  });
  $('st-emp-next').addEventListener('click', () => {
    if (state.empPage + 1 < state.empPages) { state.empPage++; renderEmployeeTable(); }
  });
  $('st-my-prev').addEventListener('click', () => {
    if (state.myPage > 0) { state.myPage--; renderMyOrders(); }
  });
  $('st-my-next').addEventListener('click', () => {
    if (state.myPage + 1 < state.myPages) { state.myPage++; renderMyOrders(); }
  });

  /* ── BỘ LỌC ─────────────────────────────────────────────────────────── */
  $('st-filter-apply').addEventListener('click', () => {
    state.keyword = $('st-filter-keyword').value.trim();
    state.filterStatus = $('st-filter-status').value;
    state.empPage = 0;
    renderEmployeeTable();
  });
  $('st-filter-reset').addEventListener('click', () => {
    $('st-filter-keyword').value = '';
    $('st-filter-status').value = '';
    state.keyword = '';
    state.filterStatus = '';
    state.empPage = 0;
    renderEmployeeTable();
  });

  /* ── NÚT ĐẶT CHỈ TIÊU MỚI ──────────────────────────────────────────── */
  $('st-new-btn').addEventListener('click', () => {
    // Mở modal với nhân viên rỗng để chọn
    openModal(null);
  });

  /* ── MODAL ĐẶT / SỬA CHỈ TIÊU ──────────────────────────────────────── */
  function openModal(emp) {
    if (!isAdmin() && !canManage()) return;
    state.editingTarget = emp;
    const locked = Boolean(emp?.locked || state.periodLocked);
    showModalError('');

    // Hiển thị/ẩn thông báo khoá
    $('st-modal-locked-notice').hidden = !locked;
    const reasonLabel = $('st-modal-reason-label');
    const reasonField = $('st-modal-reason');
    reasonLabel.hidden = !locked;
    reasonField.required = locked;

    const subject=$('st-modal-subject');
    subject.innerHTML='<option value="">Chọn nhân viên hoặc khu vực</option>'+
      (state.options?.employees||[]).map(row=>`<option value="user:${row.id}">${U.escape(row.fullName||row.username)} (Nhân viên)</option>`).join('')+
      (state.options?.territories||[]).map(row=>`<option value="territory:${row.id}">${U.escape(row.name)} (Khu vực)</option>`).join('');
    subject.value=emp?(emp.territoryId?'territory:'+emp.territoryId:'user:'+emp.employeeId):'';
    subject.disabled=Boolean(emp);
    // Điền thông tin
    $('st-modal-employee-id').value = emp ? (emp.territoryId || emp.employeeId || '') : '';
    $('st-modal-employee-name').value = emp ? (emp.employeeName || emp.username || '') : '(Chọn nhân viên)';
    $('st-modal-period-display').value = U.periodLabel(state.period);
    $('st-modal-is-locked').value = locked ? '1' : '0';
    $('st-modal-amount').value = emp && emp.targetAmount ? emp.targetAmount : '';
    $('st-modal-reason').value = '';
    $('st-modal-reason-count').textContent = '0 / 500 ký tự';
    $('st-modal-title').textContent = emp && emp.targetAmount > 0 ? 'Sửa chỉ tiêu doanh số' : 'Đặt chỉ tiêu doanh số';
    $('st-modal-subtitle').textContent = `Nhân viên: ${emp ? (emp.employeeName || emp.username || '—') : '—'} — ${U.periodLabel(state.period)}`;

    $('st-modal-backdrop').hidden = false;
    setTimeout(() => $('st-modal-amount').focus(), 50);
  }

  $('st-modal-subject').addEventListener('change',()=>{
    const [type,id]=$('st-modal-subject').value.split(':');
    $('st-modal-employee-id').value=id||'';
    $('st-modal-employee-name').value=$('st-modal-subject').selectedOptions[0]?.textContent||'';
  });

  function closeModal() {
    $('st-modal-backdrop').hidden = true;
    showModalError('');
  }

  $('st-modal-cancel').addEventListener('click', closeModal);
  $('st-modal-backdrop').addEventListener('click', e => {
    if (e.target === $('st-modal-backdrop')) closeModal();
  });
  document.addEventListener('keydown', e => {
    if (e.key === 'Escape' && !$('st-modal-backdrop').hidden) closeModal();
  });

  $('st-modal-reason').addEventListener('input', () => {
    const len = $('st-modal-reason').value.length;
    $('st-modal-reason-count').textContent = `${len} / 500 ký tự`;
  });

  /* ── SUBMIT ─────────────────────────────────────────────────────────── */
  $('st-modal-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    if (state.submitting || (!isAdmin() && !canManage())) return;
    showModalError('');

    const isLocked = $('st-modal-is-locked').value === '1';
    const draft = {
      employeeId: $('st-modal-employee-id').value,
      period: state.period,
      targetAmount: $('st-modal-amount').value,
      isLocked,
      reason: $('st-modal-reason').value,
    };

    const { isValid, errors } = U.validateTargetDraft(draft);
    if (!isValid) {
      showModalError(errors.join(' '));
      return;
    }

    state.submitting = true;
    $('st-modal-submit').disabled = true;
    $('st-modal-submit').textContent = 'Đang lưu…';

    try {
      const body = {
        ...($('st-modal-subject').value.startsWith('territory:')?{territoryId:Number(draft.employeeId)}:{userId:Number(draft.employeeId)}),
        period: draft.period,
        targetAmount: Number(draft.targetAmount),
        ...(isLocked && draft.reason ? { reason: draft.reason.trim() } : {}),
      };

      await apiFetch('/api/sales-targets'+(state.editingTarget?.targetId?'/'+state.editingTarget.targetId:''), {
        method: state.editingTarget?.targetId?'PUT':'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
        _key: 'submit',
      });

      closeModal();
      showGlobalSuccess('Chỉ tiêu đã được lưu thành công.');
      await loadManager();
    } catch (err) {
      if (err.name === 'AbortError') return;
      showModalError(err.message || 'Không lưu được chỉ tiêu. Vui lòng thử lại.');
    } finally {
      state.submitting = false;
      $('st-modal-submit').disabled = false;
      $('st-modal-submit').textContent = 'Lưu chỉ tiêu';
    }
  });

  /* ── PHẢN ỨNG VỚI THAY ĐỔI QUYỀN ───────────────────────────────────── */
  window.addEventListener('auth:permissions-changed', () => {
    generation++;
    abortAll();
    closeModal();
    clearSensitiveData();
    updateAccess();
    reload();
  });

  function clearSensitiveData() {
    state.employees = [];
    state.regionSummaries = [];
    state.auditLog = [];
    state.myProgress = null;
    state.myOrders = [];
    state.options = null;
    state.editingTarget = null;
    $('st-modal-subject').replaceChildren();
    $('st-my-kpis').hidden = true;
    $('st-my-progress-section').hidden = true;
    $('st-my-pending').hidden = true;
    $('st-employee-rows').innerHTML = '';
    $('st-region-rows').innerHTML = '';
    $('st-region-cards').innerHTML = '';
    $('st-audit-list').innerHTML = '';
    $('st-my-detail-rows').innerHTML = '';
  }

  /* ── KHỞI TẠO ───────────────────────────────────────────────────────── */
  async function init() {
    await Auth.ready;

    // Điền kỳ hiện tại
    $('st-period-input').value = state.period;
    updatePeriodBadge(state.period);

    updateAccess();
    if (!hasAnyAccess()) return;

    // Mặc định tab
    if (isAdmin() || canViewAll()) {
      setTab('manager');
    } else {
      setTab('my');
    }

    await reload();
  }

  init();
})();

