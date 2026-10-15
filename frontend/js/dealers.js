/**
 * dealers.js — S3-03 đến S3-08: hồ sơ, điểm giao và phân công đại lý.
 *
 * Tính năng:
 * - Dành cho Nhân viên kinh doanh (ROLE_SALES), Quản lý kinh doanh, Kế toán, Admin, Viewer
 * - Tìm nhanh theo mã, tên, số điện thoại (hỗ trợ gọi ngay trên di động khi đang đứng ngoài đường)
 * - Lọc theo khu vực, nhóm khách hàng, người phụ trách, trạng thái
 * - Quick chips: Tất cả, Khách của tôi, Đang giao dịch
 * - Thêm / Sửa đại lý & Ngừng / Khôi phục giao dịch (ROLE_ADMIN, ROLE_SALES_MANAGER, ROLE_ACCOUNTANT)
 * - Phân công từng đại lý, bàn giao hàng loạt và tra cứu lịch sử (S3-06)
 * - Khai báo nhiều điểm giao hàng, chọn mặc định và gắn vào đơn hàng (S3-04)
 * - Phân trang phía client, tải danh mục tham chiếu từ /dealers/options
 */
(() => {
  /* ── Auth guard ─────────────────────────────────────────── */
  if (!Auth.requireLogin()) return;

  if (!Auth.hasAnyRole('ROLE_ADMIN', 'ROLE_SALES_MANAGER', 'ROLE_ACCOUNTANT', 'ROLE_SALES', 'ROLE_VIEWER')) {
    location.href = '/error?status=403';
    return;
  }
  if (!Auth.hasPermission('CUSTOMER_VIEW')) {
    location.href = '/error?status=403';
    return;
  }
  const CAN_MANAGE = Auth.hasAnyRole('ROLE_ADMIN', 'ROLE_SALES_MANAGER', 'ROLE_ACCOUNTANT');
  const CAN_ASSIGN = Auth.hasAnyRole('ROLE_ADMIN', 'ROLE_SALES_MANAGER');
  const CAN_VIEW_ASSIGNMENT_HISTORY = Auth.hasAnyRole(
    'ROLE_ADMIN', 'ROLE_SALES_MANAGER', 'ROLE_ACCOUNTANT', 'ROLE_VIEWER',
  );
  const CAN_MANAGE_DELIVERY_POINTS = Auth.hasAnyRole(
    'ROLE_ADMIN', 'ROLE_SALES_MANAGER', 'ROLE_ACCOUNTANT', 'ROLE_SALES',
  );
  const CAN_COPY_ORDER = Auth.hasPermission('ORDER_CREATE');

  /* ── DOM helpers ────────────────────────────────────────── */
  const $ = (id) => document.getElementById(id);
  const escHtml = (v) =>
    String(v ?? '').replace(/[&<>'"]/g, (c) =>
      ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', "'": '&#39;', '"': '&quot;' })[c],
    );

  /* ── Customer group labels & badge classes ──────────────── */
  const GROUP_LABELS = {
    DAI_LY_CAP_1: 'Đại lý cấp 1',
    DAI_LY_CAP_2: 'Đại lý cấp 2',
    KHACH_LE:     'Khách lẻ',
  };

  const GROUP_BADGE_CLASS = {
    DAI_LY_CAP_1: 'group-badge--cap1',
    DAI_LY_CAP_2: 'group-badge--cap2',
    KHACH_LE:     'group-badge--le',
  };

  /* ── State ──────────────────────────────────────────────── */
  const PAGE_SIZE = 20;

  const state = {
    /** @type {Array} all loaded dealers */
    all: [],
    /** filtered + searched subset */
    filtered: [],
    /** metadata from /dealers/options */
    options: {
      customerGroups: [],
      territories: [],
      assignedUsers: [],
    },
    /** current page (1-indexed) */
    page: 1,
    searchQuery: '',
    regionFilter: '',
    groupFilter: '',
    ownerFilter: '',
    statusFilter: '',
    activeChip: 'all',
    assignment: {
      pending: [],
      historyPage: 0,
      historyTotalPages: 0,
    },
    deliveryPoints: [],
    deliveryDealerId: null,
    editingDeliveryPointId: null,
  };

  /* ── Sidebar / layout ───────────────────────────────────── */
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

  /* ── Load reference options (/dealers/options) ──────────── */
  async function loadOptions() {
    try {
      const res = await Auth.apiFetch('/dealers/options');
      const result = await res.json();
      if (res.ok && result.success && result.data) {
        state.options = result.data;
      }
    } catch (e) {
      console.warn('Không tải được danh mục tham chiếu đại lý:', e);
    }
    populateFilterDropdowns();
    populateModalDropdowns();
    populateAssignmentReferenceSelects();
  }

  /* ── Populate filter dropdowns ──────────────────────────── */
  function populateFilterDropdowns() {
    // 1. Khu vực (Region / Territory)
    const regionSelect = $('dlr-filter-region');
    if (regionSelect) {
      const curVal = regionSelect.value;
      const territoryMap = new Map();

      // From options API
      (state.options.territories || []).forEach((t) => {
        if (t && t.name) territoryMap.set(String(t.id ?? t.name), t.name);
      });

      // From loaded dealers (fallback & supplement)
      state.all.forEach((d) => {
        const key = d.territoryId ? String(d.territoryId) : (d.territoryName || d.region || '');
        const name = d.territoryName || d.region || '';
        if (key && name && !territoryMap.has(key)) {
          territoryMap.set(key, name);
        }
      });

      const optionsHtml = ['<option value="">Tất cả khu vực</option>'];
      territoryMap.forEach((name, val) => {
        optionsHtml.push(`<option value="${escHtml(val)}">${escHtml(name)}</option>`);
      });
      regionSelect.innerHTML = optionsHtml.join('');
      if (curVal && territoryMap.has(curVal)) {
        regionSelect.value = curVal;
      }
    }

    // 2. Người phụ trách (Assigned User / Owner)
    const ownerSelect = $('dlr-filter-owner');
    if (ownerSelect) {
      const curVal = ownerSelect.value;
      const userMap = new Map();

      // From options API
      (state.options.assignedUsers || []).forEach((u) => {
        if (u) {
          const key = String(u.id ?? u.username);
          const name = u.fullName || u.username;
          if (key && name) userMap.set(key, name);
        }
      });

      // From loaded dealers (fallback & supplement)
      state.all.forEach((d) => {
        const key = d.assignedUserId ? String(d.assignedUserId) : (d.assignedUserFullName || d.owner || d.assignedUsername || '');
        const name = d.assignedUserFullName || d.owner || d.assignedUsername || '';
        if (key && name && !userMap.has(key)) {
          userMap.set(key, name);
        }
      });

      const optionsHtml = ['<option value="">Tất cả người phụ trách</option>'];
      userMap.forEach((name, val) => {
        optionsHtml.push(`<option value="${escHtml(val)}">${escHtml(name)}</option>`);
      });
      ownerSelect.innerHTML = optionsHtml.join('');
      if (curVal && (curVal === '__ME__' || userMap.has(curVal))) {
        ownerSelect.value = curVal;
      }
    }
  }

  /* ── Populate modal dropdowns ───────────────────────────── */
  function populateModalDropdowns() {
    // Modal Khu vực
    const modalRegion = $('dlr-region');
    if (modalRegion && modalRegion.tagName === 'SELECT') {
      const territoryMap = new Map();
      (state.options.territories || []).forEach((t) => {
        if (t && t.name) territoryMap.set(String(t.id ?? t.name), t.name);
      });
      state.all.forEach((d) => {
        const key = d.territoryId ? String(d.territoryId) : (d.territoryName || d.region || '');
        const name = d.territoryName || d.region || '';
        if (key && name && !territoryMap.has(key)) territoryMap.set(key, name);
      });
      const opts = ['<option value="">— Chọn khu vực —</option>'];
      territoryMap.forEach((name, val) => {
        opts.push(`<option value="${escHtml(val)}">${escHtml(name)}</option>`);
      });
      modalRegion.innerHTML = opts.join('');
    }

    // Modal Người phụ trách
    const modalOwner = $('dlr-owner');
    if (modalOwner && modalOwner.tagName === 'SELECT') {
      const userMap = new Map();
      (state.options.assignedUsers || []).forEach((u) => {
        if (u) {
          const key = String(u.id ?? u.username);
          const name = u.fullName || u.username;
          if (key && name) userMap.set(key, name);
        }
      });
      state.all.forEach((d) => {
        const key = d.assignedUserId ? String(d.assignedUserId) : (d.assignedUserFullName || d.owner || d.assignedUsername || '');
        const name = d.assignedUserFullName || d.owner || d.assignedUsername || '';
        if (key && name && !userMap.has(key)) userMap.set(key, name);
      });
      const opts = ['<option value="">— Chọn người phụ trách —</option>'];
      userMap.forEach((name, val) => {
        opts.push(`<option value="${escHtml(val)}">${escHtml(name)}</option>`);
      });
      modalOwner.innerHTML = opts.join('');
    }
  }

  /* ── Filter & Search matching rules ─────────────────────── */
  function matchesSearch(dlr, query) {
    if (!query) return true;
    const q = query.toLowerCase().trim();
    if (!q) return true;

    // 1. Mã đại lý
    if ((dlr.code || '').toLowerCase().includes(q)) return true;

    // 2. Tên đại lý
    if ((dlr.name || '').toLowerCase().includes(q)) return true;

    // 3. Số điện thoại (tìm nhanh theo chuỗi hoặc định dạng số)
    const phone = String(dlr.phone || '');
    if (phone.toLowerCase().includes(q)) return true;
    const qDigits = q.replace(/\D/g, '');
    const phoneDigits = phone.replace(/\D/g, '');
    if (qDigits && phoneDigits && phoneDigits.includes(qDigits)) return true;

    // 4. Mã số thuế
    if ((dlr.taxCode || '').toLowerCase().includes(q)) return true;

    return false;
  }

  function matchesRegion(dlr, regionVal) {
    if (!regionVal) return true;
    const target = String(regionVal).toLowerCase();
    if (dlr.territoryId && String(dlr.territoryId) === regionVal) return true;
    if (dlr.territoryCode && dlr.territoryCode.toLowerCase() === target) return true;
    if (dlr.territoryName && dlr.territoryName.toLowerCase() === target) return true;
    if (dlr.region && dlr.region.toLowerCase() === target) return true;
    return false;
  }

  function matchesGroup(dlr, groupVal) {
    if (!groupVal) return true;
    return dlr.customerGroup === groupVal;
  }

  function matchesOwner(dlr, ownerVal) {
    if (!ownerVal) return true;
    if (ownerVal === '__ME__') {
      const curUser = Auth.getUser?.();
      if (!curUser) return true;
      if (dlr.assignedUserId && curUser.id && dlr.assignedUserId === curUser.id) return true;
      if (dlr.assignedUsername && curUser.username && dlr.assignedUsername.toLowerCase() === curUser.username.toLowerCase()) return true;
      if (dlr.assignedUserFullName && curUser.fullName && dlr.assignedUserFullName.toLowerCase() === curUser.fullName.toLowerCase()) return true;
      if (dlr.owner && curUser.fullName && dlr.owner.toLowerCase() === curUser.fullName.toLowerCase()) return true;
      return false;
    }
    const target = String(ownerVal).toLowerCase();
    if (dlr.assignedUserId && String(dlr.assignedUserId) === ownerVal) return true;
    if (dlr.assignedUsername && dlr.assignedUsername.toLowerCase() === target) return true;
    if (dlr.assignedUserFullName && dlr.assignedUserFullName.toLowerCase() === target) return true;
    if (dlr.owner && dlr.owner.toLowerCase() === target) return true;
    return false;
  }

  function matchesStatus(dlr, statusVal) {
    if (!statusVal) return true;
    if (statusVal === 'LOCKED') return Boolean(dlr.locked);
    if (statusVal === 'ACTIVE') return Boolean(dlr.active) && !dlr.locked;
    return !dlr.active;
  }

  /* ── Filter application & toolbar meta ──────────────────── */
  function applyFilter() {
    state.filtered = state.all.filter((dlr) => {
      return (
        matchesSearch(dlr, state.searchQuery) &&
        matchesRegion(dlr, state.regionFilter) &&
        matchesGroup(dlr, state.groupFilter) &&
        matchesOwner(dlr, state.ownerFilter) &&
        matchesStatus(dlr, state.statusFilter)
      );
    });

    state.page = 1;
    renderTable();
    renderPagination();
    updateToolbarMeta();
  }

  function updateToolbarMeta() {
    const hasFilter = Boolean(
      state.searchQuery ||
      state.regionFilter ||
      state.groupFilter ||
      state.ownerFilter ||
      state.statusFilter
    );

    const resetBtn = $('btn-reset-filters');
    if (resetBtn) resetBtn.hidden = !hasFilter;

    const countText = $('dlr-count-text');
    if (countText) {
      if (state.all.length === 0) {
        countText.textContent = '0 đại lý';
      } else if (hasFilter) {
        countText.textContent = `Tìm thấy ${state.filtered.length} / ${state.all.length} đại lý`;
      } else {
        countText.textContent = `Tổng cộng ${state.all.length} đại lý`;
      }
    }
  }

  function groupBadge(group) {
    if (!group) return '<span class="group-badge group-badge--unknown">—</span>';
    const label = GROUP_LABELS[group] || group;
    const cls   = GROUP_BADGE_CLASS[group] || 'group-badge--unknown';
    return `<span class="group-badge ${cls}">${escHtml(label)}</span>`;
  }

  /* ── Render table & mobile view ─────────────────────────── */
  function renderTable() {
    const tbody = $('dlr-tbody');
    const start = (state.page - 1) * PAGE_SIZE;
    const slice = state.filtered.slice(start, start + PAGE_SIZE);

    if (!slice.length) {
      const hasFilter = Boolean(
        state.searchQuery ||
        state.regionFilter ||
        state.groupFilter ||
        state.ownerFilter ||
        state.statusFilter
      );

      tbody.innerHTML = `
        <tr>
          <td colspan="8">
            <div class="dlr-empty">
              <svg width="48" height="48" viewBox="0 0 24 24" stroke-width="1.2" fill="none" stroke="currentColor">
                <path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/>
                <circle cx="9" cy="7" r="4"/>
                <path d="M23 21v-2a4 4 0 0 0-3-3.87M16 3.13a4 4 0 0 1 0 7.75"/>
              </svg>
              <p>${
                state.all.length === 0
                  ? 'Chưa có đại lý nào trong hệ thống.'
                  : hasFilter
                    ? 'Không tìm thấy đại lý phù hợp với bộ lọc hiện tại.'
                    : 'Không có dữ liệu.'
              }</p>
              ${
                hasFilter
                  ? `<button type="button" class="btn btn--secondary" id="btn-empty-reset" style="margin-top:12px">Xoá bộ lọc</button>`
                  : ''
              }
            </div>
          </td>
        </tr>`;

      $('btn-empty-reset')?.addEventListener('click', () => {
        $('btn-reset-filters')?.click();
      });
      return;
    }

    tbody.innerHTML = slice.map((dlr) => {
      const isActive = Boolean(dlr.active);

      const isLocked = Boolean(dlr.locked);
      const statusBadge = isLocked
        ? `<span class="status-badge status-badge--locked" title="${escHtml(dlr.lockReason)}">Bị khoá giao dịch</span><span class="dlr-lock-reason">${escHtml(dlr.lockReason)}</span>`
        : isActive
        ? `<span class="status-badge status-badge--active"><span class="status-badge__dot"></span>Đang giao dịch</span>`
        : `<span class="status-badge status-badge--inactive"><span class="status-badge__dot"></span>Ngừng giao dịch</span>`;

      const actions = `<div class="dlr-actions">
            <button class="dlr-action-btn" data-action="orders" data-id="${dlr.id}" title="Đơn đang dở" aria-label="Đơn đang dở của ${escHtml(dlr.name)}">
              <svg viewBox="0 0 24 24"><path d="M9 5H5v16h14V5h-4M9 3h6v4H9zM8 12h8M8 16h5"/></svg>
            </button>
            <button class="dlr-action-btn" data-action="order-history" data-id="${dlr.id}" title="Lịch sử đơn hàng" aria-label="Lịch sử đơn hàng của ${escHtml(dlr.name)}">
              <svg viewBox="0 0 24 24"><path d="M4 5h16v14H4zM8 9h8M8 13h5"/><path d="M7 3v4M17 3v4"/></svg>
            </button>
            ${CAN_ASSIGN ? `
            <button class="dlr-action-btn dlr-action-btn--assign" data-action="assign" data-id="${dlr.id}" title="Phân công người phụ trách" aria-label="Phân công người phụ trách cho ${escHtml(dlr.name)}">
              <svg viewBox="0 0 24 24"><circle cx="9" cy="7" r="4"/><path d="M3 21v-2a6 6 0 0 1 12 0v2M19 8v6M22 11h-6"/></svg>
            </button>` : ''}
            <button class="dlr-action-btn" data-action="delivery-points" data-id="${dlr.id}" title="Điểm giao hàng" aria-label="Điểm giao hàng của ${escHtml(dlr.name)}">
              <svg viewBox="0 0 24 24"><path d="M20 10c0 5-8 11-8 11S4 15 4 10a8 8 0 1 1 16 0Z"/><circle cx="12" cy="10" r="2.5"/></svg>
            </button>
            ${CAN_MANAGE ? `
            <button class="dlr-action-btn" data-action="edit" data-id="${dlr.id}" title="Sửa thông tin">
              <svg viewBox="0 0 24 24"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/></svg>
            </button>
            ${isActive
              ? `<button class="dlr-action-btn dlr-action-btn--warn" data-action="deactivate" data-id="${dlr.id}" title="Ngừng giao dịch">
                  <svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="10"/><line x1="8" y1="12" x2="16" y2="12"/></svg>
                </button>`
              : `<button class="dlr-action-btn dlr-action-btn--success" data-action="activate" data-id="${dlr.id}" title="Khôi phục giao dịch">
                  <svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="16"/><line x1="8" y1="12" x2="16" y2="12"/></svg>
                </button>`}
            <button class="dlr-action-btn ${isLocked ? 'dlr-action-btn--success' : 'dlr-action-btn--lock'}" data-action="${isLocked ? 'unlock' : 'lock'}" data-id="${dlr.id}" title="${isLocked ? 'Mở khoá giao dịch' : 'Khoá giao dịch'}" aria-label="${isLocked ? 'Mở khoá' : 'Khoá'} giao dịch ${escHtml(dlr.name)}">
              <svg viewBox="0 0 24 24"><rect x="3" y="11" width="18" height="10" rx="2"/><path d="${isLocked ? 'M7 11V7a5 5 0 0 1 9-3' : 'M7 11V7a5 5 0 0 1 10 0v4'}"/></svg>
            </button>` : ''}
           </div>`;

      const phoneLinkHtml = dlr.phone
        ? `<a href="tel:${escHtml(dlr.phone.replace(/\s+/g, ''))}" class="dlr-phone-link" title="Gọi điện cho đại lý">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72 12.84 12.84 0 0 0 .7 2.81 2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45 12.84 12.84 0 0 0 2.81.7A2 2 0 0 1 22 16.92z"/>
            </svg>
            <span>${escHtml(dlr.phone)}</span>
           </a>`
        : `<span class="dlr-phone-muted">Chưa có SĐT</span>`;

      const addressHtml = dlr.address
        ? `<div class="dlr-address" title="${escHtml(dlr.address)}">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0 1 18 0z"/>
              <circle cx="12" cy="10" r="3"/>
            </svg>
            <span>${escHtml(dlr.address)}</span>
           </div>`
        : '';

      const regionText = escHtml(dlr.territoryName || dlr.region || '—');
      const ownerText = escHtml(dlr.assignedUserFullName || dlr.owner || dlr.assignedUsername || '—');

      return `
        <tr>
          <td>
            <div class="dlr-mobile-card-header">
              <span class="dlr-code">${escHtml(dlr.code)}</span>
              <span class="dlr-mobile-status">${statusBadge}</span>
            </div>
          </td>
          <td>
            <div class="dlr-mobile-name-area">
              <div class="dlr-name" title="${escHtml(dlr.name)}">${escHtml(dlr.name)}</div>
              ${phoneLinkHtml}
              ${addressHtml}
            </div>
          </td>
          <td>
            <span class="dlr-desktop-only">${escHtml(dlr.taxCode || '—')}</span>
          </td>
          <td>
            <span class="dlr-mobile-meta-item">
              <span class="dlr-mobile-meta-label">Nhóm:</span>
              ${groupBadge(dlr.customerGroup)}
            </span>
          </td>
          <td>
            <span class="dlr-mobile-meta-item">
              <span class="dlr-mobile-meta-label">Khu vực:</span>
              <span class="dlr-region-text">${regionText}</span>
            </span>
          </td>
          <td>
            <span class="dlr-mobile-meta-item">
              <span class="dlr-mobile-meta-label">Phụ trách:</span>
              <span class="dlr-owner-text">${ownerText}</span>
            </span>
          </td>
          <td>
            <span class="dlr-desktop-only">${statusBadge}</span>
          </td>
          <td>
            ${actions ? `<div class="dlr-mobile-actions-area">${actions}</div>` : ''}
          </td>
        </tr>`;
    }).join('');

    // Bind action buttons
    tbody.querySelectorAll('[data-action="edit"]').forEach((btn) => {
      btn.addEventListener('click', () => {
        const dlr = state.all.find((d) => d.id === Number(btn.dataset.id));
        if (dlr) openDealerModal(dlr);
      });
    });

    tbody.querySelectorAll('[data-action="deactivate"]').forEach((btn) => {
      btn.addEventListener('click', () => openStatusModal(Number(btn.dataset.id), 'INACTIVE'));
    });

    tbody.querySelectorAll('[data-action="activate"]').forEach((btn) => {
      btn.addEventListener('click', () => openStatusModal(Number(btn.dataset.id), 'ACTIVE'));
    });
    tbody.querySelectorAll('[data-action="lock"]').forEach((btn) => {
      btn.addEventListener('click', () => openLockModal(Number(btn.dataset.id)));
    });
    tbody.querySelectorAll('[data-action="unlock"]').forEach((btn) => {
      btn.addEventListener('click', () => openStatusModal(Number(btn.dataset.id), 'UNLOCK'));
    });
    tbody.querySelectorAll('[data-action="orders"]').forEach((btn) => {
      btn.addEventListener('click', () => openInProgressOrders(Number(btn.dataset.id)));
    });
    tbody.querySelectorAll('[data-action="order-history"]').forEach((btn) => {
      btn.addEventListener('click', () => openOrderHistory(Number(btn.dataset.id)));
    });
    tbody.querySelectorAll('[data-action="assign"]').forEach((btn) => {
      btn.addEventListener('click', () => {
        const dlr = state.all.find((d) => d.id === Number(btn.dataset.id));
        if (dlr) openAssignmentModal(dlr);
      });
    });
    tbody.querySelectorAll('[data-action="delivery-points"]').forEach((btn) => {
      btn.addEventListener('click', () => openDeliveryPointsModal(Number(btn.dataset.id)));
    });
  }

  /* ── Pagination ─────────────────────────────────────────── */
  function renderPagination() {
    const el = $('dlr-pagination');
    const total      = state.filtered.length;
    const totalPages = Math.ceil(total / PAGE_SIZE);

    if (totalPages <= 1) {
      el.hidden = true;
      return;
    }

    el.hidden = false;
    const start = (state.page - 1) * PAGE_SIZE + 1;
    const end   = Math.min(state.page * PAGE_SIZE, total);

    const pageButtons = [];
    for (let p = 1; p <= totalPages; p++) {
      pageButtons.push(
        `<button class="dlr-page-btn${p === state.page ? ' dlr-page-btn--active' : ''}" data-page="${p}">${p}</button>`,
      );
    }

    el.innerHTML = `
      <span class="dlr-pagination__info">${start}–${end} / ${total} đại lý</span>
      <div class="dlr-pagination__controls">
        <button class="dlr-page-btn" data-page="${state.page - 1}" ${state.page === 1 ? 'disabled' : ''}>‹</button>
        ${pageButtons.join('')}
        <button class="dlr-page-btn" data-page="${state.page + 1}" ${state.page === totalPages ? 'disabled' : ''}>›</button>
      </div>`;

    el.querySelectorAll('[data-page]').forEach((btn) => {
      btn.addEventListener('click', () => {
        state.page = Number(btn.dataset.page);
        renderTable();
        renderPagination();
      });
    });
  }

  /* ── S3-06: Assignment & handover ─────────────────────── */
  function userLabel(user) {
    return user?.fullName || user?.username || `Nhân viên #${user?.id ?? ''}`;
  }

  function salesUsers() {
    return (state.options.assignedUsers || []).filter((user) => user?.id != null);
  }

  function handoverSourceUsers() {
    const users = new Map(salesUsers().map((user) => [String(user.id), user]));
    for (const dealer of state.all) {
      if (dealer.assignedUserId == null || users.has(String(dealer.assignedUserId))) continue;
      users.set(String(dealer.assignedUserId), {
        id: dealer.assignedUserId,
        username: dealer.assignedUsername,
        fullName: dealer.assignedUserFullName,
        departing: true,
      });
    }
    return [...users.values()];
  }

  function fillUserSelect(select, placeholder, includeAll = false, users = salesUsers()) {
    if (!select) return;
    const selected = select.value;
    const options = [`<option value="">${escHtml(placeholder)}</option>`];
    users.forEach((user) => {
      options.push(`<option value="${escHtml(user.id)}">${escHtml(userLabel(user))} · ${escHtml(user.username || '')}${user.departing ? ' (cần bàn giao)' : ''}</option>`);
    });
    if (includeAll) options[0] = `<option value="">${escHtml(placeholder)}</option>`;
    select.innerHTML = options.join('');
    if (selected && [...select.options].some((option) => option.value === selected)) {
      select.value = selected;
    }
  }

  function populateAssignmentReferenceSelects() {
    fillUserSelect($('assignment-owner'), '— Chọn người phụ trách —');
    fillUserSelect($('handover-from-user'), '— Chọn nhân viên bàn giao —', false, handoverSourceUsers());
    fillUserSelect($('handover-to-user'), '— Chọn nhân viên tiếp nhận —');
    fillUserSelect($('history-from-user'), 'Tất cả người bàn giao', true, handoverSourceUsers());
    fillUserSelect($('history-to-user'), 'Tất cả người tiếp nhận', true);

    const territoryOptions = ['<option value="">— Chọn khu vực —</option>'];
    (state.options.territories || []).forEach((territory) => {
      territoryOptions.push(`<option value="${escHtml(territory.id)}">${escHtml(territory.name)}</option>`);
    });
    const historyTerritoryOptions = ['<option value="">Tất cả khu vực</option>', ...territoryOptions.slice(1)];
    if ($('handover-territory')) $('handover-territory').innerHTML = territoryOptions.join('');
    if ($('history-territory')) $('history-territory').innerHTML = historyTerritoryOptions.join('');
  }

  function renderPendingHandover() {
    const list = $('assignment-pending-list');
    const count = $('assignment-pending-count');
    if (!list || !count) return;

    const pending = state.assignment.pending;
    count.textContent = String(pending.length);
    if (!pending.length) {
      list.innerHTML = '<p class="assignment-empty">Không có đại lý nào đang chờ bàn giao.</p>';
      return;
    }

    list.innerHTML = pending.map((dealer) => `
      <div class="assignment-pending__item">
        <div>
          <strong>${escHtml(dealer.name || dealer.code)}</strong>
          <span>${escHtml(dealer.code || `ID ${dealer.id}`)} · ${escHtml(dealer.territoryName || 'Chưa có khu vực')}</span>
          <small>Đang phụ trách: ${escHtml(dealer.assignedUserFullName || dealer.assignedUsername || 'Chưa phân công')}</small>
        </div>
        <button type="button" class="btn btn--secondary btn--compact" data-pending-assign="${escHtml(dealer.id)}">Phân công</button>
      </div>`).join('');

    list.querySelectorAll('[data-pending-assign]').forEach((button) => {
      button.addEventListener('click', () => {
        const dealer = state.all.find((item) => item.id === Number(button.dataset.pendingAssign))
          || pending.find((item) => item.id === Number(button.dataset.pendingAssign));
        if (dealer) openAssignmentModal(dealer);
      });
    });
  }

  async function loadAssignmentPanel() {
    if (!CAN_ASSIGN) return;
    const message = $('assignment-panel-message');
    if (message) message.hidden = true;
    try {
      const response = await Auth.apiFetch('/dealers/handover/pending');
      const result = await response.json().catch(() => ({}));
      if (!response.ok || !result.success || !Array.isArray(result.data)) {
        throw new Error(result.message || 'Không tải được danh sách đại lý cần bàn giao');
      }
      state.assignment.pending = result.data;
      renderPendingHandover();
    } catch (error) {
      state.assignment.pending = [];
      renderPendingHandover();
      if (message) {
        message.hidden = false;
        message.textContent = error.message || 'Không tải được danh sách cần bàn giao.';
      }
    }
  }

  function openAssignmentModal(dealer) {
    if (!CAN_ASSIGN) return;
    populateAssignmentReferenceSelects();
    $('assignment-dealer-id').value = dealer.id;
    $('dealer-assignment-modal-sub').textContent = `${dealer.name || 'Đại lý'} · ${dealer.code || `ID ${dealer.id}`}`;
    $('assignment-current-owner').textContent = `Người phụ trách hiện tại: ${dealer.assignedUserFullName || dealer.assignedUsername || 'Chưa phân công'}`;
    $('assignment-owner').value = dealer.assignedUserId ? String(dealer.assignedUserId) : '';
    $('assignment-reason').value = '';
    $('assignment-form-message').hidden = true;
    $('dealer-assignment-modal').hidden = false;
    setTimeout(() => $('assignment-owner').focus(), 50);
  }

  function closeAssignmentModal() {
    if ($('dealer-assignment-modal')) $('dealer-assignment-modal').hidden = true;
  }

  function updateHandoverDealers() {
    const fromId = Number($('handover-from-user')?.value || 0);
    const select = $('handover-dealers');
    if (!select) return;
    const dealers = state.all
      .filter((dealer) => Number(dealer.assignedUserId) === fromId)
      .sort((a, b) => String(a.name || '').localeCompare(String(b.name || ''), 'vi'));
    select.innerHTML = dealers.length
      ? dealers.map((dealer) => `<option value="${escHtml(dealer.id)}">${escHtml(dealer.name)} · ${escHtml(dealer.code || `ID ${dealer.id}`)}</option>`).join('')
      : '<option value="" disabled>Nhân viên này chưa có đại lý</option>';
    updateHandoverPreview();
  }

  function updateHandoverScopeFields() {
    const scope = $('handover-scope')?.value || 'all';
    if ($('handover-territory-field')) $('handover-territory-field').hidden = scope !== 'territory';
    if ($('handover-dealers-field')) $('handover-dealers-field').hidden = scope !== 'selected';
    updateHandoverPreview();
  }

  function updateHandoverPreview() {
    const preview = $('handover-preview');
    if (!preview) return;
    const fromId = Number($('handover-from-user')?.value || 0);
    const scope = $('handover-scope')?.value || 'all';
    const ownedDealers = state.all.filter((dealer) => Number(dealer.assignedUserId) === fromId);
    let count = ownedDealers.length;
    let description = 'toàn bộ đại lý của nhân viên bàn giao';
    if (scope === 'territory') {
      const territoryId = Number($('handover-territory')?.value || 0);
      count = ownedDealers.filter((dealer) => Number(dealer.territoryId) === territoryId).length;
      description = $('handover-territory')?.selectedOptions[0]?.textContent || 'khu vực đã chọn';
    } else if (scope === 'selected') {
      count = [...($('handover-dealers')?.selectedOptions || [])].length;
      description = 'danh sách đại lý đã chọn';
    }
    preview.textContent = fromId ? `Sẽ chuyển ${count} đại lý theo ${description}.` : 'Chưa chọn nhân viên bàn giao.';
  }

  function openBulkHandoverModal() {
    if (!CAN_ASSIGN) return;
    $('bulk-handover-form').reset();
    $('bulk-handover-message').hidden = true;
    populateAssignmentReferenceSelects();
    updateHandoverDealers();
    updateHandoverScopeFields();
    $('bulk-handover-modal').hidden = false;
    setTimeout(() => $('handover-from-user').focus(), 50);
  }

  function closeBulkHandoverModal() {
    if ($('bulk-handover-modal')) $('bulk-handover-modal').hidden = true;
  }

  function formatAssignmentDate(value) {
    if (!value) return '—';
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString('vi-VN', { dateStyle: 'short', timeStyle: 'short' });
  }

  function assignmentTypeLabel(value) {
    return value === 'BULK_HANDOVER' ? 'Bàn giao hàng loạt' : 'Phân công';
  }

  function renderAssignmentHistory(page) {
    const body = $('assignment-history-body');
    const summary = $('assignment-history-summary');
    const pagination = $('assignment-history-pagination');
    if (!body || !summary || !pagination) return;
    const rows = page?.content || [];
    summary.textContent = `${page?.totalElements || 0} bản ghi`;
    body.innerHTML = rows.length ? rows.map((row) => `
      <tr>
        <td>${escHtml(formatAssignmentDate(row.transferredAt))}</td>
        <td><strong>${escHtml(row.dealerName || '—')}</strong><small>${escHtml(row.dealerCode || `ID ${row.dealerId || '—'}`)}</small></td>
        <td>${escHtml(row.fromUserFullName || row.fromUsername || 'Chưa có')}</td>
        <td>${escHtml(row.toUserFullName || row.toUsername || 'Chưa có')}</td>
        <td><span class="assignment-type">${escHtml(assignmentTypeLabel(row.transferType))}</span></td>
        <td>${escHtml(row.reason || '—')}</td>
      </tr>`).join('') : '<tr><td colspan="6" class="assignment-empty-cell">Chưa có lịch sử phù hợp.</td></tr>';

    const totalPages = Number(page?.totalPages || 0);
    state.assignment.historyPage = Number(page?.number || 0);
    state.assignment.historyTotalPages = totalPages;
    pagination.innerHTML = totalPages > 1 ? `
      <button type="button" class="dlr-page-btn" data-history-page="${state.assignment.historyPage - 1}" ${state.assignment.historyPage === 0 ? 'disabled' : ''}>‹</button>
      <span>Trang ${state.assignment.historyPage + 1} / ${totalPages}</span>
      <button type="button" class="dlr-page-btn" data-history-page="${state.assignment.historyPage + 1}" ${state.assignment.historyPage >= totalPages - 1 ? 'disabled' : ''}>›</button>` : '';
    pagination.querySelectorAll('[data-history-page]').forEach((button) => {
      button.addEventListener('click', () => loadAssignmentHistory(Number(button.dataset.historyPage)));
    });
  }

  async function loadAssignmentHistory(pageNumber = 0) {
    if (!CAN_VIEW_ASSIGNMENT_HISTORY) return;
    const message = $('assignment-history-message');
    if (message) message.hidden = true;
    const params = new URLSearchParams({ page: String(pageNumber), size: '10' });
    const fromUser = $('history-from-user')?.value;
    const toUser = $('history-to-user')?.value;
    const territory = $('history-territory')?.value;
    if (fromUser) params.set('fromUserId', fromUser);
    if (toUser) params.set('toUserId', toUser);
    if (territory) params.set('territoryId', territory);
    $('assignment-history-body').innerHTML = '<tr><td colspan="6">Đang tải lịch sử…</td></tr>';
    try {
      const response = await Auth.apiFetch(`/dealers/handover/history?${params.toString()}`);
      const result = await response.json().catch(() => ({}));
      if (!response.ok || !result.success || !result.data) {
        throw new Error(result.message || 'Không tải được lịch sử phân công');
      }
      renderAssignmentHistory(result.data);
    } catch (error) {
      $('assignment-history-body').innerHTML = '<tr><td colspan="6" class="assignment-empty-cell">Không tải được lịch sử.</td></tr>';
      if (message) {
        message.hidden = false;
        message.textContent = error.message || 'Không tải được lịch sử phân công.';
      }
    }
  }

  function openAssignmentHistoryModal() {
    if (!CAN_VIEW_ASSIGNMENT_HISTORY) return;
    populateAssignmentReferenceSelects();
    $('assignment-history-modal').hidden = false;
    loadAssignmentHistory(0);
  }

  function closeAssignmentHistoryModal() {
    if ($('assignment-history-modal')) $('assignment-history-modal').hidden = true;
  }

  /* ── Load all dealers ───────────────────────────────────── */
  async function loadDealers() {
    $('dlr-tbody').innerHTML = `<tr class="dlr-loading-row"><td colspan="8"><div class="dlr-loading">Đang tải dữ liệu…</div></td></tr>`;

    try {
      const dealers = [];
      let page = 0;
      let last = false;
      do {
        const res    = await Auth.apiFetch(`/dealers?page=${page}&size=200`);
        const result = await res.json();
        if (!res.ok || !result.success || !Array.isArray(result.data?.content)) {
          throw new Error(result.message || 'Không tải được dữ liệu');
        }
        dealers.push(...result.data.content);
        last = result.data.last !== false;
        page++;
      } while (!last);

      state.all = dealers;
      populateFilterDropdowns();
      populateModalDropdowns();
      applyFilter();
      await loadAssignmentPanel();
    } catch (e) {
      $('dlr-tbody').innerHTML = `
        <tr><td colspan="8"><div class="dlr-empty">
          <p>${escHtml(e.message || 'Lỗi tải dữ liệu')}</p>
        </div></td></tr>`;
      showToast(e.message || 'Không tải được danh sách đại lý', 'error');
    }
  }

  /* ── Dealer modal (Add / Edit) ──────────────────────────── */
  function openDealerModal(dlr = null) {
    if (!CAN_MANAGE) {
      showToast('Bạn không có quyền quản lý đại lý.', 'error');
      return;
    }

    $('dealer-form').reset();
    $('dealer-form-message').hidden = true;

    if (dlr) {
      $('dealer-modal-title').textContent = 'Sửa đại lý';
      $('dealer-modal-sub').textContent   = 'Cập nhật thông tin đại lý.';
      $('dlr-id').value        = dlr.id;
      $('dlr-code').value      = dlr.code ?? '';
      $('dlr-code').disabled   = true; // Mã không thay đổi sau khi tạo
      $('dlr-name').value      = dlr.name ?? '';
      $('dlr-tax').value       = dlr.taxCode ?? '';
      $('dlr-group').value     = dlr.customerGroup ?? '';
      $('dlr-region').value    = dlr.territoryId ? String(dlr.territoryId) : '';
      $('dlr-owner').value     = dlr.assignedUserId ? String(dlr.assignedUserId) : '';
      $('dlr-phone').value     = dlr.phone ?? '';
      $('dlr-address').value   = dlr.address ?? '';
      if ($('dlr-notes')) $('dlr-notes').value = dlr.notes ?? '';
    } else {
      $('dealer-modal-title').textContent = 'Thêm đại lý';
      $('dealer-modal-sub').textContent   = 'Khai báo thông tin đại lý mới.';
      $('dlr-id').value      = '';
      $('dlr-code').disabled = false;
    }

    $('dealer-modal').hidden = false;
    setTimeout(() => $('dlr-name').focus(), 50);
  }

  function closeDealerModal() {
    $('dealer-modal').hidden = true;
    $('dlr-code').disabled   = false;
  }

  $('dealer-form')?.addEventListener('submit', async (e) => {
    e.preventDefault();
    const id = $('dlr-id').value;

    const payload = {
      code:          $('dlr-code').value.trim().toUpperCase(),
      name:          $('dlr-name').value.trim(),
      taxCode:       $('dlr-tax').value.trim() || null,
      customerGroup: $('dlr-group').value || null,
      territoryId:   $('dlr-region').value ? Number($('dlr-region').value) : null,
      assignedUserId:$('dlr-owner').value ? Number($('dlr-owner').value) : null,
      phone:         $('dlr-phone').value.trim() || null,
      address:       $('dlr-address').value.trim() || null,
    };

    if (!payload.name) {
      showFormError('dealer-form-message', 'Vui lòng nhập tên đại lý.');
      return;
    }
    if (!id && !payload.code) {
      showFormError('dealer-form-message', 'Vui lòng nhập mã đại lý.');
      return;
    }
    if (!payload.customerGroup) {
      showFormError('dealer-form-message', 'Vui lòng chọn nhóm khách hàng.');
      return;
    }

    const btn = $('btn-save-dealer');
    btn.disabled    = true;
    btn.textContent = 'Đang lưu…';

    try {
      const res = await Auth.apiFetch(
        id ? `/dealers/${id}` : '/dealers',
        { method: id ? 'PUT' : 'POST', body: JSON.stringify(payload) },
      );
      const result = await res.json();
      if (!res.ok || !result.success) throw new Error(result.message || 'Không lưu được');

      closeDealerModal();
      showToast(id ? 'Đã cập nhật đại lý.' : 'Đã thêm đại lý mới.', 'success');
      await loadDealers();
    } catch (err) {
      showFormError('dealer-form-message', err.message || 'Lỗi không xác định');
    } finally {
      btn.disabled    = false;
      btn.textContent = 'Lưu';
    }
  });

  $('dealer-assignment-form')?.addEventListener('submit', async (event) => {
    event.preventDefault();
    if (!CAN_ASSIGN) return;
    const dealerId = Number($('assignment-dealer-id').value);
    const assignedUserId = Number($('assignment-owner').value);
    const reason = $('assignment-reason').value.trim();
    if (!dealerId || !assignedUserId) {
      showFormError('assignment-form-message', 'Vui lòng chọn nhân viên phụ trách mới.');
      return;
    }

    const button = $('btn-save-assignment');
    button.disabled = true;
    button.textContent = 'Đang lưu…';
    $('assignment-form-message').hidden = true;
    try {
      const response = await Auth.apiFetch(`/dealers/${dealerId}/assign`, {
        method: 'POST',
        body: JSON.stringify({ assignedUserId, reason: reason || null }),
      });
      const result = await response.json().catch(() => ({}));
      if (!response.ok || !result.success) throw new Error(result.message || 'Không lưu được phân công');
      closeAssignmentModal();
      showToast('Đã cập nhật người phụ trách và lưu lịch sử.', 'success');
      await loadDealers();
    } catch (error) {
      showFormError('assignment-form-message', error.message || 'Không lưu được phân công.');
    } finally {
      button.disabled = false;
      button.textContent = 'Lưu phân công';
    }
  });

  $('bulk-handover-form')?.addEventListener('submit', async (event) => {
    event.preventDefault();
    if (!CAN_ASSIGN) return;
    const fromUserId = Number($('handover-from-user').value);
    const toUserId = Number($('handover-to-user').value);
    const scope = $('handover-scope').value;
    const reason = $('handover-reason').value.trim();
    const notes = $('handover-notes').value.trim();
    if (!fromUserId || !toUserId) {
      showFormError('bulk-handover-message', 'Vui lòng chọn đủ nhân viên bàn giao và tiếp nhận.');
      return;
    }
    if (fromUserId === toUserId) {
      showFormError('bulk-handover-message', 'Nhân viên tiếp nhận phải khác nhân viên bàn giao.');
      return;
    }
    if (!reason) {
      showFormError('bulk-handover-message', 'Vui lòng nhập lý do chuyển giao.');
      return;
    }

    const payload = { fromUserId, toUserId, reason, notes: notes || null };
    if (scope === 'territory') {
      const territoryId = Number($('handover-territory').value);
      if (!territoryId) {
        showFormError('bulk-handover-message', 'Vui lòng chọn khu vực cần chuyển giao.');
        return;
      }
      payload.territoryId = territoryId;
    } else if (scope === 'selected') {
      const dealerIds = [...$('handover-dealers').selectedOptions].map((option) => Number(option.value)).filter(Boolean);
      if (!dealerIds.length) {
        showFormError('bulk-handover-message', 'Vui lòng chọn ít nhất một đại lý.');
        return;
      }
      payload.dealerIds = dealerIds;
    }

    const button = $('btn-save-bulk-handover');
    button.disabled = true;
    button.textContent = 'Đang chuyển giao…';
    $('bulk-handover-message').hidden = true;
    try {
      const response = await Auth.apiFetch('/dealers/handover/bulk', {
        method: 'POST',
        body: JSON.stringify(payload),
      });
      const result = await response.json().catch(() => ({}));
      if (!response.ok || !result.success) throw new Error(result.message || 'Không chuyển giao được đại lý');
      closeBulkHandoverModal();
      const transferred = Number(result.data?.totalTransferred || 0);
      showToast(`Đã chuyển giao ${transferred} đại lý và lưu lịch sử.`, 'success');
      await loadDealers();
    } catch (error) {
      showFormError('bulk-handover-message', error.message || 'Không chuyển giao được đại lý.');
    } finally {
      button.disabled = false;
      button.textContent = 'Chuyển giao';
    }
  });

  /* ── Status modal (ngừng / khôi phục) ──────────────────── */
  function openStatusModal(dlrId, targetStatus) {
    if (!CAN_MANAGE) {
      showToast('Bạn không có quyền thay đổi trạng thái đại lý.', 'error');
      return;
    }

    const dlr = state.all.find((d) => d.id === dlrId);
    if (!dlr) return;

    const isDeactivate = targetStatus === 'INACTIVE';
    const isUnlock = targetStatus === 'UNLOCK';
    $('dlr-status-modal-title').textContent = isUnlock ? 'Mở khoá giao dịch' : isDeactivate ? 'Ngừng giao dịch' : 'Khôi phục giao dịch';
    $('dlr-status-modal-sub').textContent   = dlr.name;
    $('dlr-status-modal-body').textContent  = isUnlock
      ? `Mở khoá giao dịch với đại lý «${dlr.name}»? Trạng thái ngừng giao dịch, nếu có, vẫn được giữ.`
      : isDeactivate
      ? `Đại lý «${dlr.name}» sẽ bị ngừng giao dịch. Các hoá đơn hiện có sẽ không bị ảnh hưởng. Bạn có thể khôi phục bất kỳ lúc nào.`
      : `Khôi phục giao dịch với đại lý «${dlr.name}». Đại lý sẽ trở lại trạng thái đang giao dịch.`;

    $('dlr-status-id').value = dlrId;
    $('dlr-status-target').value = targetStatus;
    $('dlr-status-form-message').hidden = true;

    const confirmBtn = $('btn-confirm-dlr-status');
    confirmBtn.textContent = isUnlock ? 'Mở khoá' : isDeactivate ? 'Ngừng giao dịch' : 'Khôi phục';

    $('dlr-status-modal').hidden = false;
  }

  function closeStatusModal() {
    $('dlr-status-modal').hidden = true;
  }

  $('btn-confirm-dlr-status')?.addEventListener('click', async () => {
    const dlrId        = Number($('dlr-status-id').value);
    const targetStatus = $('dlr-status-target').value;

    const btn = $('btn-confirm-dlr-status');
    btn.disabled    = true;
    btn.textContent = 'Đang xử lý…';

    try {
      if (targetStatus === 'UNLOCK') {
        await DealerTransactions.unlock(dlrId);
      } else {
        const res = await Auth.apiFetch(`/dealers/${dlrId}/status?active=${targetStatus === 'ACTIVE'}`, { method: 'PATCH' });
        const result = await res.json().catch(() => ({}));
        if (!res.ok || !result.success) throw new Error(result.message || 'Không thay đổi được trạng thái');
      }

      closeStatusModal();
      showToast(
        targetStatus === 'UNLOCK' ? 'Đã mở khoá giao dịch.' : targetStatus === 'INACTIVE' ? 'Đã ngừng giao dịch đại lý.' : 'Đã khôi phục giao dịch.',
        'success',
      );
      await loadDealers();
    } catch (err) {
      showFormError('dlr-status-form-message', err.message || 'Lỗi không xác định');
    } finally {
      btn.disabled    = false;
      btn.textContent = targetStatus === 'UNLOCK' ? 'Mở khoá' : targetStatus === 'INACTIVE' ? 'Ngừng giao dịch' : 'Khôi phục';
    }
  });

  /* S3-07: giữ trạng thái ở server; chỉ cập nhật bảng sau khi API thành công. */
  let transactionPreviousFocus;
  function openTransactionModal(id) {
    transactionPreviousFocus = document.activeElement;
    $(id).hidden = false;
    document.querySelector('.app-shell').inert = true;
    $(id).querySelector('button, textarea')?.focus();
  }
  function closeTransactionModal(id) {
    $(id).hidden = true;
    document.querySelector('.app-shell').inert = false;
    transactionPreviousFocus?.focus();
  }
  function openLockModal(id) {
    if (!CAN_MANAGE) return;
    const dealer = state.all.find(row => row.id === id);
    if (!dealer) return;
    $('lock-dlr-id').value = id;
    $('lock-modal-sub').textContent = dealer.name;
    $('lock-reason').value = '';
    $('lock-form-msg').hidden = true;
    openTransactionModal('lock-modal');
    $('lock-reason').focus();
  }
  $('lock-form').addEventListener('submit', async event => {
    event.preventDefault();
    if (!CAN_MANAGE) return;
    const button = $('btn-confirm-lock');
    button.disabled = true;
    $('lock-form-msg').hidden = true;
    try {
      const dealer = await DealerTransactions.lock(Number($('lock-dlr-id').value), $('lock-reason').value);
      closeTransactionModal('lock-modal');
      showToast(Number(dealer.inProgressOrderCount) > 0
        ? `Đã khoá giao dịch. Có ${dealer.inProgressOrderCount} đơn đang dở cần theo dõi.` : 'Đã khoá giao dịch.', 'success');
      await loadDealers();
    } catch (error) {
      showFormError('lock-form-msg', error.message);
    } finally { button.disabled = false; }
  });

  let orderDealerId = null;
  let activeCancelOrder = null;

  const ORDER_LIFECYCLE_CONFIG = {
    DRAFT: { label: 'Nháp', cls: 'order-status-badge--draft', canCancel: true },
    PENDING_APPROVAL: { label: 'Chờ duyệt', cls: 'order-status-badge--pending', canCancel: true },
    RECORDED: { label: 'Đã duyệt', cls: 'order-status-badge--approved', canCancel: true },
    APPROVED: { label: 'Đã duyệt', cls: 'order-status-badge--approved', canCancel: true },
    PROCESSING: { label: 'Đang soạn hàng', cls: 'order-status-badge--picking', canCancel: true },
    PICKING: { label: 'Đang soạn hàng', cls: 'order-status-badge--picking', canCancel: true },
    EXPORTED: { label: 'Đã xuất', cls: 'order-status-badge--exported', canCancel: false, exported: true },
    SHIPPED: { label: 'Đã xuất', cls: 'order-status-badge--exported', canCancel: false, exported: true },
    DELIVERED: { label: 'Đã giao', cls: 'order-status-badge--delivered', canCancel: false, exported: true },
    CLOSED: { label: 'Đóng', cls: 'order-status-badge--closed', canCancel: false, exported: true },
    COMPLETED: { label: 'Đóng', cls: 'order-status-badge--closed', canCancel: false, exported: true },
    CANCELLED: { label: 'Đã huỷ', cls: 'order-status-badge--cancelled', canCancel: false },
    REJECTED: { label: 'Từ chối duyệt', cls: 'order-status-badge--rejected', canCancel: false },
  };

  function formatOrderLifecycleBadge(status) {
    const norm = String(status || '').toUpperCase();
    const config = ORDER_LIFECYCLE_CONFIG[norm] || {
      label: status || 'Chưa xác định',
      cls: 'order-status-badge--draft',
      canCancel: false,
    };
    return `<span class="order-status-badge ${config.cls}"><span class="order-status-badge__dot"></span>${escHtml(config.label)}</span>`;
  }

  function renderDealerOrders(orders) {
    const canApprove = Auth.hasAnyRole('ROLE_ADMIN', 'ROLE_SALES_MANAGER');
    const canCancelRole = Auth.hasAnyRole('ROLE_SALES', 'ROLE_SALES_MANAGER', 'ROLE_ADMIN');

    $('dealer-orders-body').innerHTML = orders.length ? orders.map(order => {
      const normStatus = String(order.status || '').toUpperCase();
      const config = ORDER_LIFECYCLE_CONFIG[normStatus] || { canCancel: false, exported: false };

      const actionButtons = [];
      if (canApprove && normStatus === 'PENDING_APPROVAL') {
        actionButtons.push(`<button class="btn btn--secondary" data-order-action="approve" data-order-id="${order.id}">Duyệt</button>`);
        actionButtons.push(`<button class="btn btn--secondary" data-order-action="reject" data-order-id="${order.id}">Từ chối</button>`);
      }

      if (canCancelRole) {
        if (config.canCancel) {
          actionButtons.push(`<button class="btn btn--danger" data-order-cancel="${order.id}" data-order-code="${escHtml(order.orderCode)}">Huỷ đơn</button>`);
        } else if (config.exported) {
          actionButtons.push(`<span class="order-return-guide" title="Đơn đã xuất kho không thể huỷ trực tiếp, phải thực hiện thủ tục nhập trả hàng">Đã xuất kho (đi đường trả hàng)</span>`);
        }
      }

      if (CAN_COPY_ORDER) {
        actionButtons.push(`<button class="btn btn--secondary" data-order-copy="${order.id}">Sao chép</button>`);
      }

      return `<tr>
        <td><strong>${escHtml(order.orderCode)}</strong></td>
        <td>${formatOrderLifecycleBadge(order.status)}</td>
        <td>${escHtml(Number(order.totalAmount || 0).toLocaleString('vi-VN'))} ₫</td>
        <td><div class="order-actions-wrap">${actionButtons.length ? actionButtons.join(' ') : '—'}</div></td>
      </tr>`;
    }).join('') : '<tr><td colspan="4">Không có đơn hàng.</td></tr>';
  }

  async function openOrderList(id, history = false) {
    orderDealerId = id;
    const dealer = state.all.find(row => row.id === id);
    $('dealer-orders-title').textContent = `${history ? 'Lịch sử đơn hàng' : 'Vòng đời đơn hàng'} · ${dealer?.name || ''}`;
    if ($('dealer-orders-modal').hidden) openTransactionModal('dealer-orders-modal');
    $('dealer-orders-message').hidden = true;
    $('dealer-orders-warning').hidden = !dealer?.locked;
    $('dealer-orders-warning').textContent = dealer?.locked
      ? `Đại lý đang bị khoá: ${dealer.lockReason || ''}. Đơn đã tạo vẫn được xử lý tiếp, cần theo dõi công nợ.` : '';
    $('dealer-orders-body').innerHTML = '<tr><td colspan="4">Đang tải đơn hàng…</td></tr>';
    try {
      const orders = history
        ? await DealerTransactions.getOrdersForDealer(id)
        : await DealerTransactions.getInProgressOrders(id);
      if (orderDealerId !== id || $('dealer-orders-modal').hidden) return;
      const warning = orders.find(order => order.dealerLocked)?.dealerLockWarning;
      if (warning) {
        $('dealer-orders-warning').hidden = false;
        $('dealer-orders-warning').textContent = warning;
      }
      renderDealerOrders(orders);
    } catch (error) {
      if (orderDealerId !== id) return;
      $('dealer-orders-body').innerHTML = '<tr><td colspan="4">Không tải được đơn hàng.</td></tr>';
      showFormError('dealer-orders-message', error.message);
    }
  }

  function openInProgressOrders(id) {
    return openOrderList(id, false);
  }

  function openOrderHistory(id) {
    return openOrderList(id, true);
  }

  function openCancelOrderModal(orderId, orderCode) {
    activeCancelOrder = { id: orderId, code: orderCode };
    $('cancel-order-id').value = orderId;
    $('cancel-order-sub').textContent = `Mã đơn: ${orderCode}`;
    $('cancel-order-reason').value = '';
    $('cancel-order-message').hidden = true;
    $('btn-submit-cancel-order').disabled = false;
    $('cancel-order-modal').hidden = false;
    setTimeout(() => $('cancel-order-reason').focus(), 50);
  }

  function closeCancelOrderModal() {
    $('cancel-order-modal').hidden = true;
    activeCancelOrder = null;
  }

  $('dealer-orders-body').addEventListener('click', async event => {
    const copyBtn = event.target.closest('[data-order-copy]');
    if (copyBtn) {
      if (!CAN_COPY_ORDER) return;
      copyBtn.disabled = true;
      try {
        await DealerTransactions.copyOrderToDraft(Number(copyBtn.dataset.orderCopy));
        window.location.href = '/create-order.html?copied=1';
      } catch (error) {
        showFormError('dealer-orders-message', error.message);
        copyBtn.disabled = false;
      }
      return;
    }

    const cancelBtn = event.target.closest('[data-order-cancel]');
    if (cancelBtn) {
      const orderId = Number(cancelBtn.dataset.orderCancel);
      const orderCode = cancelBtn.dataset.orderCode;
      openCancelOrderModal(orderId, orderCode);
      return;
    }

    const button = event.target.closest('[data-order-action]');
    if (!button || !Auth.hasAnyRole('ROLE_ADMIN', 'ROLE_SALES_MANAGER')) return;
    const dealerId = orderDealerId;
    button.disabled = true;
    try {
      await DealerTransactions.processOrder(Number(button.dataset.orderId), button.dataset.orderAction);
      showToast(button.dataset.orderAction === 'approve' ? 'Đã duyệt đơn hàng.' : 'Đã từ chối đơn hàng.');
      await loadDealers();
      if (orderDealerId === dealerId) await openInProgressOrders(dealerId);
    } catch (error) { showFormError('dealer-orders-message', error.message); }
    finally { button.disabled = false; }
  });

  $('cancel-order-form')?.addEventListener('submit', async event => {
    event.preventDefault();
    if (!activeCancelOrder) return;
    const orderId = Number($('cancel-order-id').value || activeCancelOrder.id);
    const reason = $('cancel-order-reason').value.trim();
    if (!reason) {
      showFormError('cancel-order-message', 'Vui lòng nhập lý do huỷ đơn hàng.');
      $('cancel-order-reason').focus();
      return;
    }
    const submitBtn = $('btn-submit-cancel-order');
    submitBtn.disabled = true;
    $('cancel-order-message').hidden = true;
    try {
      const cancelledOrderCode = activeCancelOrder.code;
      await DealerTransactions.cancelOrder(orderId, reason);
      closeCancelOrderModal();
      showToast(`Đã huỷ đơn hàng ${cancelledOrderCode}.`);
      await loadDealers();
      if (orderDealerId) await openInProgressOrders(orderDealerId);
    } catch (error) {
      showFormError('cancel-order-message', error.message);
    } finally {
      submitBtn.disabled = false;
    }
  });

  document.querySelectorAll('[data-close-cancel-order]').forEach(btn =>
    btn.addEventListener('click', closeCancelOrderModal)
  );

  document.querySelectorAll('[data-close-transaction]').forEach(button => button.addEventListener('click', () => {
    if (button.dataset.closeTransaction === 'dealer-orders-modal') orderDealerId = null;
    if (button.dataset.closeTransaction === 'delivery-points-modal') state.deliveryDealerId = null;
    closeTransactionModal(button.dataset.closeTransaction);
  }));

  ['lock-modal', 'dealer-orders-modal', 'delivery-points-modal', 'cancel-order-modal'].forEach(id => $(id)?.addEventListener('click', event => {
    if (event.target !== $(id)) return;
    if (id === 'dealer-orders-modal') orderDealerId = null;
    if (id === 'delivery-points-modal') state.deliveryDealerId = null;
    if (id === 'cancel-order-modal') { closeCancelOrderModal(); return; }
    closeTransactionModal(id);
  }));

  document.addEventListener('keydown', event => {
    const id = ['cancel-order-modal', 'lock-modal', 'dealer-orders-modal', 'delivery-points-modal'].find(name => $(name) && !$(name).hidden);
    if (!id) return;
    if (event.key === 'Escape') {
      if (id === 'cancel-order-modal') { closeCancelOrderModal(); return; }
      if (id === 'dealer-orders-modal') orderDealerId = null;
      if (id === 'delivery-points-modal') state.deliveryDealerId = null;
      closeTransactionModal(id);
    }
    if (event.key === 'Tab') {
      const controls = [...$(id).querySelectorAll('button:not(:disabled), textarea, input:not([type="hidden"]), select')]
        .filter(control => !control.closest('[hidden]'));
      const first = controls[0], last = controls[controls.length - 1];
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus(); }
      if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus(); }
    }
  });

  /* ── S3-04: Delivery points ─────────────────────────────── */
  function responseData(payload) {
    return payload && Object.prototype.hasOwnProperty.call(payload, 'data') ? payload.data : payload;
  }

  function responseMessage(payload, fallback) {
    return payload?.message || payload?.error || fallback;
  }

  async function readJsonResponse(res) {
    return res.json().catch(() => null);
  }

  async function loadDeliveryPointsForDealer(dealerId) {
    const res = await Auth.apiFetch(`/dealers/${dealerId}/delivery-points`);
    const payload = await readJsonResponse(res);
    if (!res.ok) throw new Error(responseMessage(payload, 'Không tải được điểm giao hàng.'));

    const data = responseData(payload);
    state.deliveryPoints = Array.isArray(data) ? data : [];
    renderDeliveryPoints();
  }

  function renderDeliveryPoints() {
    const list = $('delivery-points-list');
    if (!list) return;

    if (!state.deliveryPoints.length) {
      list.innerHTML = '<div class="delivery-points-empty">Đại lý này chưa có điểm giao hàng nào.</div>';
      return;
    }

    list.innerHTML = state.deliveryPoints.map((point) => `
      <article class="delivery-point-card">
        <div class="delivery-point-card__main">
          <h3 class="delivery-point-card__title">
            ${escHtml(point.receiverName || 'Chưa có người nhận')}
            ${point.isDefault ? '<span class="delivery-point-badge">Mặc định</span>' : ''}
          </h3>
          <div class="delivery-point-card__meta">
            <div><strong>Số điện thoại:</strong> ${escHtml(point.phone || '—')}</div>
            <div><strong>Địa chỉ:</strong> ${escHtml(point.address || '—')}</div>
            ${point.directionNote ? `<div class="delivery-point-card__note"><strong>Ghi chú:</strong> ${escHtml(point.directionNote)}</div>` : ''}
          </div>
        </div>
        ${CAN_MANAGE_DELIVERY_POINTS ? `<div class="delivery-point-card__actions">
          <button type="button" class="btn btn--secondary" data-delivery-action="edit" data-id="${point.id}">Sửa</button>
          ${point.isDefault ? '' : `<button type="button" class="btn btn--secondary" data-delivery-action="default" data-id="${point.id}">Đặt mặc định</button>`}
          <button type="button" class="btn btn--secondary" data-delivery-action="delete" data-id="${point.id}">Xoá</button>
        </div>` : ''}
      </article>
    `).join('');
  }

  function closeDeliveryPointForm() {
    $('delivery-point-form').hidden = true;
    $('delivery-point-form-message').hidden = true;
    state.editingDeliveryPointId = null;
  }

  function openDeliveryPointForm(point = null) {
    if (!CAN_MANAGE_DELIVERY_POINTS) {
      showToast('Bạn không có quyền thay đổi điểm giao hàng.', 'error');
      return;
    }

    state.editingDeliveryPointId = point?.id ?? null;
    $('delivery-point-form').reset();
    $('delivery-point-form-message').hidden = true;
    $('delivery-point-id').value = point?.id ?? '';
    $('delivery-point-receiver').value = point?.receiverName ?? '';
    $('delivery-point-phone').value = point?.phone ?? '';
    $('delivery-point-address').value = point?.address ?? '';
    $('delivery-point-note').value = point?.directionNote ?? '';
    $('delivery-point-default').checked = Boolean(point?.isDefault);
    $('delivery-point-default').disabled = Boolean(point?.isDefault);
    $('delivery-point-form-title').textContent = point ? 'Sửa điểm giao hàng' : 'Thêm điểm giao hàng';
    $('btn-save-delivery-point').textContent = point ? 'Lưu thay đổi' : 'Lưu điểm giao';
    $('delivery-point-form').hidden = false;
    $('delivery-point-receiver').focus();
  }

  async function openDeliveryPointsModal(dealerId) {
    const dealer = state.all.find((row) => row.id === dealerId);
    if (!dealer) return;

    state.deliveryDealerId = dealerId;
    state.deliveryPoints = [];
    closeDeliveryPointForm();
    $('delivery-points-title').textContent = 'Điểm giao hàng';
    $('delivery-points-subtitle').textContent = `${dealer.code || ''} — ${dealer.name || ''}`;
    $('delivery-points-message').hidden = true;
    $('btn-add-delivery-point').hidden = !CAN_MANAGE_DELIVERY_POINTS;
    $('delivery-points-list').innerHTML = '<div class="delivery-points-loading">Đang tải điểm giao hàng…</div>';
    openTransactionModal('delivery-points-modal');

    try {
      await loadDeliveryPointsForDealer(dealerId);
    } catch (error) {
      if (state.deliveryDealerId !== dealerId) return;
      $('delivery-points-list').innerHTML = '<div class="delivery-points-empty">Không tải được danh sách điểm giao hàng.</div>';
      showFormError('delivery-points-message', error.message || 'Lỗi không xác định');
    }
  }

  $('btn-add-delivery-point')?.addEventListener('click', () => openDeliveryPointForm());
  $('btn-cancel-delivery-point')?.addEventListener('click', closeDeliveryPointForm);

  $('delivery-points-list')?.addEventListener('click', async (event) => {
    const button = event.target.closest('[data-delivery-action]');
    if (!button || !CAN_MANAGE_DELIVERY_POINTS) return;

    const pointId = Number(button.dataset.id);
    const point = state.deliveryPoints.find((item) => item.id === pointId);
    if (!point) return;

    if (button.dataset.deliveryAction === 'edit') {
      openDeliveryPointForm(point);
      return;
    }

    if (button.dataset.deliveryAction === 'delete') {
      if (point.isDefault) {
        showToast('Hãy đặt điểm khác làm mặc định trước khi xoá điểm này.', 'warning');
        return;
      }
      if (!window.confirm(`Xoá điểm giao hàng của ${point.receiverName || 'người nhận này'}?`)) return;
      button.disabled = true;
      try {
        const res = await Auth.apiFetch(`/dealers/delivery-points/${pointId}`, { method: 'DELETE' });
        const payload = await readJsonResponse(res);
        if (!res.ok) throw new Error(responseMessage(payload, 'Không xoá được điểm giao hàng.'));
        showToast('Đã xoá điểm giao hàng.', 'success');
        await loadDeliveryPointsForDealer(state.deliveryDealerId);
      } catch (error) {
        showFormError('delivery-points-message', error.message || 'Lỗi không xác định');
      } finally {
        button.disabled = false;
      }
      return;
    }

    button.disabled = true;
    try {
      const res = await Auth.apiFetch(`/dealers/${state.deliveryDealerId}/delivery-points/${pointId}/default`, { method: 'PATCH' });
      const payload = await readJsonResponse(res);
      if (!res.ok) throw new Error(responseMessage(payload, 'Không đặt được điểm mặc định.'));
      showToast('Đã đặt điểm giao hàng mặc định.', 'success');
      await loadDeliveryPointsForDealer(state.deliveryDealerId);
    } catch (error) {
      showFormError('delivery-points-message', error.message || 'Lỗi không xác định');
    } finally {
      button.disabled = false;
    }
  });

  $('delivery-point-form')?.addEventListener('submit', async (event) => {
    event.preventDefault();
    if (!CAN_MANAGE_DELIVERY_POINTS || !state.deliveryDealerId) return;

    const receiverName = $('delivery-point-receiver').value.trim();
    const phone = $('delivery-point-phone').value.trim();
    const address = $('delivery-point-address').value.trim();
    if (!receiverName || !phone || !address) {
      showFormError('delivery-point-form-message', 'Vui lòng nhập người nhận, số điện thoại và địa chỉ.');
      return;
    }

    const button = $('btn-save-delivery-point');
    button.disabled = true;
    $('delivery-point-form-message').hidden = true;
    const pointId = state.editingDeliveryPointId;
    const payload = {
      receiverName,
      phone,
      address,
      directionNote: $('delivery-point-note').value.trim() || null,
      isDefault: $('delivery-point-default').checked,
    };

    try {
      const res = await Auth.apiFetch(
        pointId ? `/dealers/delivery-points/${pointId}` : `/dealers/${state.deliveryDealerId}/delivery-points`,
        { method: pointId ? 'PUT' : 'POST', body: JSON.stringify(payload) },
      );
      const response = await readJsonResponse(res);
      if (!res.ok) throw new Error(responseMessage(response, 'Không lưu được điểm giao hàng.'));
      showToast(pointId ? 'Đã cập nhật điểm giao hàng.' : 'Đã thêm điểm giao hàng.', 'success');
      closeDeliveryPointForm();
      await loadDeliveryPointsForDealer(state.deliveryDealerId);
    } catch (error) {
      showFormError('delivery-point-form-message', error.message || 'Lỗi không xác định');
    } finally {
      button.disabled = false;
      button.textContent = pointId ? 'Lưu thay đổi' : 'Lưu điểm giao';
    }
  });

  /* ── Form error helper ──────────────────────────────────── */
  function showFormError(elId, text) {
    const el = $(elId);
    if (!el) return;
    el.hidden      = false;
    el.textContent = text;
  }

  /* ── Manage privileges UI ────────────────────────────────── */
  if (CAN_MANAGE) {
    const addBtn = $('btn-add-dealer');
    if (addBtn) addBtn.hidden = false;
  }
  if (CAN_ASSIGN) {
    const panel = $('dealer-assignment-panel');
    if (panel) panel.hidden = false;
  }

  /* ── Event bindings ─────────────────────────────────────── */

  $('btn-add-dealer')?.addEventListener('click', () => openDealerModal(null));
  $('btn-open-bulk-handover')?.addEventListener('click', openBulkHandoverModal);
  $('btn-open-assignment-history')?.addEventListener('click', openAssignmentHistoryModal);
  $('btn-refresh-assignment')?.addEventListener('click', async () => {
    await loadAssignmentPanel();
    showToast('Đã làm mới danh sách cần bàn giao.', 'success');
  });

  $('handover-from-user')?.addEventListener('change', updateHandoverDealers);
  $('handover-scope')?.addEventListener('change', updateHandoverScopeFields);
  $('handover-territory')?.addEventListener('change', updateHandoverPreview);
  $('handover-dealers')?.addEventListener('change', updateHandoverPreview);
  $('assignment-history-filters')?.addEventListener('submit', (event) => {
    event.preventDefault();
    loadAssignmentHistory(0);
  });

  // Quick search
  let searchTimer;
  $('dlr-search')?.addEventListener('input', () => {
    clearTimeout(searchTimer);
    const val = $('dlr-search').value;
    const clearBtn = $('dlr-search-clear');
    if (clearBtn) clearBtn.hidden = !val;

    searchTimer = setTimeout(() => {
      state.searchQuery = val;
      applyFilter();
    }, 150);
  });

  $('dlr-search-clear')?.addEventListener('click', () => {
    const searchInput = $('dlr-search');
    if (searchInput) {
      searchInput.value = '';
      searchInput.focus();
    }
    $('dlr-search-clear').hidden = true;
    state.searchQuery = '';
    applyFilter();
  });

  // Khu vực filter
  $('dlr-filter-region')?.addEventListener('change', () => {
    state.regionFilter = $('dlr-filter-region').value;
    applyFilter();
  });

  // Nhóm KH filter
  $('dlr-filter-group')?.addEventListener('change', () => {
    state.groupFilter = $('dlr-filter-group').value;
    applyFilter();
  });

  // Người phụ trách filter
  $('dlr-filter-owner')?.addEventListener('change', () => {
    state.ownerFilter = $('dlr-filter-owner').value;
    if (state.activeChip === 'my-dealers' && state.ownerFilter !== '__ME__') {
      setActiveChip(null);
    }
    applyFilter();
  });

  // Trạng thái filter
  $('dlr-filter-status')?.addEventListener('change', () => {
    state.statusFilter = $('dlr-filter-status').value;
    if (state.activeChip === 'active-only' && state.statusFilter !== 'ACTIVE') {
      setActiveChip(null);
    }
    applyFilter();
  });

  // Đặt lại bộ lọc (Reset)
  $('btn-reset-filters')?.addEventListener('click', () => {
    state.searchQuery = '';
    state.regionFilter = '';
    state.groupFilter = '';
    state.ownerFilter = '';
    state.statusFilter = '';
    setActiveChip('all');

    if ($('dlr-search')) $('dlr-search').value = '';
    if ($('dlr-search-clear')) $('dlr-search-clear').hidden = true;
    if ($('dlr-filter-region')) $('dlr-filter-region').value = '';
    if ($('dlr-filter-group')) $('dlr-filter-group').value = '';
    if ($('dlr-filter-owner')) $('dlr-filter-owner').value = '';
    if ($('dlr-filter-status')) $('dlr-filter-status').value = '';

    applyFilter();
  });

  // Quick Chips
  function setActiveChip(chipKey) {
    state.activeChip = chipKey;
    document.querySelectorAll('.dlr-chip').forEach((btn) => {
      btn.classList.toggle('dlr-chip--active', btn.dataset.chip === chipKey);
    });
  }

  document.querySelectorAll('.dlr-chip').forEach((btn) => {
    btn.addEventListener('click', () => {
      const chip = btn.dataset.chip;
      setActiveChip(chip);

      if (chip === 'all') {
        state.regionFilter = '';
        state.groupFilter = '';
        state.ownerFilter = '';
        state.statusFilter = '';
        if ($('dlr-filter-region')) $('dlr-filter-region').value = '';
        if ($('dlr-filter-group')) $('dlr-filter-group').value = '';
        if ($('dlr-filter-owner')) $('dlr-filter-owner').value = '';
        if ($('dlr-filter-status')) $('dlr-filter-status').value = '';
      } else if (chip === 'my-dealers') {
        state.ownerFilter = '__ME__';
        // Select in dropdown if user match exists
        const curUser = Auth.getUser?.();
        if (curUser && $('dlr-filter-owner')) {
          const ownerSelect = $('dlr-filter-owner');
          for (const opt of ownerSelect.options) {
            if (opt.value && (opt.value === String(curUser.id) || opt.text.includes(curUser.fullName || curUser.username))) {
              ownerSelect.value = opt.value;
              break;
            }
          }
        }
      } else if (chip === 'active-only') {
        state.statusFilter = 'ACTIVE';
        if ($('dlr-filter-status')) $('dlr-filter-status').value = 'ACTIVE';
      }
      applyFilter();
    });
  });

  // Modal close — dealer form
  document.querySelectorAll('[data-close-dealer]').forEach((btn) =>
    btn.addEventListener('click', closeDealerModal),
  );
  $('dealer-modal')?.addEventListener('click', (e) => {
    if (e.target === $('dealer-modal')) closeDealerModal();
  });

  document.querySelectorAll('[data-close-assignment]').forEach((button) =>
    button.addEventListener('click', closeAssignmentModal),
  );
  $('dealer-assignment-modal')?.addEventListener('click', (event) => {
    if (event.target === $('dealer-assignment-modal')) closeAssignmentModal();
  });

  document.querySelectorAll('[data-close-bulk-handover]').forEach((button) =>
    button.addEventListener('click', closeBulkHandoverModal),
  );
  $('bulk-handover-modal')?.addEventListener('click', (event) => {
    if (event.target === $('bulk-handover-modal')) closeBulkHandoverModal();
  });

  document.querySelectorAll('[data-close-assignment-history]').forEach((button) =>
    button.addEventListener('click', closeAssignmentHistoryModal),
  );
  $('assignment-history-modal')?.addEventListener('click', (event) => {
    if (event.target === $('assignment-history-modal')) closeAssignmentHistoryModal();
  });

  // Modal close — status
  document.querySelectorAll('[data-close-dlr-status]').forEach((btn) =>
    btn.addEventListener('click', closeStatusModal),
  );
  $('dlr-status-modal')?.addEventListener('click', (e) => {
    if (e.target === $('dlr-status-modal')) closeStatusModal();
  });

  // Keyboard shortcut (Escape)
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape') {
      if ($('dealer-modal') && !$('dealer-modal').hidden) closeDealerModal();
      if ($('dlr-status-modal') && !$('dlr-status-modal').hidden) closeStatusModal();
      if ($('dealer-assignment-modal') && !$('dealer-assignment-modal').hidden) closeAssignmentModal();
      if ($('bulk-handover-modal') && !$('bulk-handover-modal').hidden) closeBulkHandoverModal();
      if ($('assignment-history-modal') && !$('assignment-history-modal').hidden) closeAssignmentHistoryModal();
    }
  });

  /* ── Init ───────────────────────────────────────────────── */
  loadOptions();
  loadDealers();
})();
