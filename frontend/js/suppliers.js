/**
 * suppliers.js — S2-09 Quản lý danh mục nhà cung cấp.
 *
 * Tính năng:
 * - Danh sách NCC với tìm kiếm và lọc theo trạng thái
 * - Thêm / Sửa NCC (mã, tên, MST, người liên hệ, điều khoản TT, ...)
 * - NCC đã có phiếu nhập kho → KHÔNG xoá được, chỉ ngưng giao dịch
 * - Khôi phục giao dịch (ACTIVE ↔ INACTIVE)
 * - Phân trang phía client
 *
 * API endpoints (expected):
 *   GET    /suppliers                → { data: [...], total: N }
 *   POST   /suppliers                → create
 *   PUT    /suppliers/:id            → update
 *   PATCH  /suppliers/:id/status     → { status: "ACTIVE"|"INACTIVE" }
 */
(() => {
  /* ── Auth guard ─────────────────────────────────────────── */
  if (!Auth.requireLogin()) return;

  const CAN_MANAGE = Auth.hasPermission('SUPPLIER_MANAGE');

  /* ── DOM helpers ────────────────────────────────────────── */
  const $ = (id) => document.getElementById(id);
  const escHtml = (v) =>
    String(v ?? '').replace(/[&<>'"]/g, (c) =>
      ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', "'": '&#39;', '"': '&quot;' })[c],
    );

  /* ── State ──────────────────────────────────────────────── */
  const PAGE_SIZE = 20;

  const state = {
    /** @type {Array} all loaded suppliers */
    all: [],
    /** filtered + searched subset */
    filtered: [],
    /** current page (1-indexed) */
    page: 1,
    searchQuery: '',
    statusFilter: '',
  };

  /* ── Sidebar / layout (reuse dashboard pattern) ─────────── */
  (function initLayout() {
    const user = Auth.getUser?.() ?? null;
    if (!user) return;

    // Avatar initials
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

    // Build sidebar nav
    const nav = $('sidebar-nav');
    const items = MenuConfig.getMenuForUser(user);
    let lastSection = null;
    items.forEach((item) => {
      if (item.section && item.section !== lastSection) {
        lastSection = item.section;
        const label = document.createElement('div');
        label.className = 'sidebar__section-label';
        label.textContent = item.section;
        nav.appendChild(label);
      }
      const a = document.createElement('a');
      a.className = 'sidebar__link' + (item.id === 'suppliers' ? ' sidebar__link--active' : '');
      a.href = item.href;
      a.innerHTML = `<svg viewBox="0 0 24 24">${item.icon}</svg><span>${escHtml(item.label)}</span>`;
      nav.appendChild(a);
    });

    // Logout
    const doLogout = () => Auth.logout?.();
    $('btn-logout')?.addEventListener('click', doLogout);
    $('btn-logout-topbar')?.addEventListener('click', doLogout);

    // Mobile sidebar toggle
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

  /* ── Payment terms label ────────────────────────────────── */
  const PAYMENT_LABELS = {
    COD:   'Thanh toán ngay',
    NET7:  'Net 7 ngày',
    NET15: 'Net 15 ngày',
    NET30: 'Net 30 ngày',
    NET45: 'Net 45 ngày',
    NET60: 'Net 60 ngày',
  };

  /* ── Render table ───────────────────────────────────────── */
  function applyFilter() {
    const q = state.searchQuery.toLowerCase().trim();
    const s = state.statusFilter;

    state.filtered = state.all.filter((sup) => {
      const matchSearch =
        !q ||
        (sup.name || '').toLowerCase().includes(q) ||
        (sup.code || '').toLowerCase().includes(q) ||
        (sup.taxCode || '').toLowerCase().includes(q);
      const matchStatus = !s || sup.status === s;
      return matchSearch && matchStatus;
    });

    state.page = 1;
    renderTable();
    renderPagination();
  }

  function renderTable() {
    const tbody = $('sup-tbody');
    const start = (state.page - 1) * PAGE_SIZE;
    const slice = state.filtered.slice(start, start + PAGE_SIZE);

    if (!slice.length) {
      tbody.innerHTML = `
        <tr>
          <td colspan="7">
            <div class="sup-empty">
              <svg width="48" height="48" viewBox="0 0 24 24" stroke-width="1.2">
                <path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/>
                <circle cx="9" cy="7" r="4"/>
                <path d="M23 21v-2a4 4 0 0 0-3-3.87M16 3.13a4 4 0 0 1 0 7.75"/>
              </svg>
              <p>${state.all.length === 0 ? 'Chưa có nhà cung cấp nào. Bấm «Thêm» để bắt đầu.' : 'Không tìm thấy nhà cung cấp phù hợp.'}</p>
            </div>
          </td>
        </tr>`;
      return;
    }

    tbody.innerHTML = slice.map((sup) => {
      const isActive = sup.status === 'ACTIVE';
      const payLabel = PAYMENT_LABELS[sup.paymentTerms] ?? '';

      const statusBadge = isActive
        ? `<span class="status-badge status-badge--active"><span class="status-badge__dot"></span>Đang giao dịch</span>`
        : `<span class="status-badge status-badge--inactive"><span class="status-badge__dot"></span>Ngưng giao dịch</span>`;

      const payBadge = payLabel
        ? `<span class="payment-badge">${escHtml(payLabel)}</span>`
        : `<span class="payment-badge payment-badge--none">—</span>`;

      const actions = CAN_MANAGE
        ? `<div class="sup-actions">
            <button class="sup-action-btn" data-action="edit" data-id="${sup.id}" title="Sửa">
              <svg viewBox="0 0 24 24"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/></svg>
            </button>
            ${isActive
              ? `<button class="sup-action-btn sup-action-btn--warn" data-action="deactivate" data-id="${sup.id}"
                  title="${sup.hasImportReceipts ? 'Ngưng giao dịch (đã có phiếu nhập)' : 'Ngưng giao dịch'}">
                  <svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="10"/><line x1="8" y1="12" x2="16" y2="12"/></svg>
                </button>`
              : `<button class="sup-action-btn sup-action-btn--success" data-action="activate" data-id="${sup.id}" title="Khôi phục giao dịch">
                  <svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="16"/><line x1="8" y1="12" x2="16" y2="12"/></svg>
                </button>`}
           </div>`
        : '';

      return `
        <tr>
          <td><span class="sup-code">${escHtml(sup.code)}</span></td>
          <td>
            <div class="sup-name" title="${escHtml(sup.name)}">${escHtml(sup.name)}</div>
          </td>
          <td>${escHtml(sup.taxCode || '—')}</td>
          <td>
            <div class="sup-contact">${escHtml(sup.contactName || '—')}</div>
            ${sup.contactPhone ? `<div class="sup-contact-phone">${escHtml(sup.contactPhone)}</div>` : ''}
          </td>
          <td>${payBadge}</td>
          <td>${statusBadge}</td>
          <td>${actions}</td>
        </tr>`;
    }).join('');

    // Bind action buttons
    tbody.querySelectorAll('[data-action="edit"]').forEach((btn) => {
      btn.addEventListener('click', () => {
        const sup = state.all.find((s) => s.id === Number(btn.dataset.id));
        if (sup) openSupplierModal(sup);
      });
    });

    tbody.querySelectorAll('[data-action="deactivate"]').forEach((btn) => {
      btn.addEventListener('click', () => openStatusModal(Number(btn.dataset.id), 'INACTIVE'));
    });

    tbody.querySelectorAll('[data-action="activate"]').forEach((btn) => {
      btn.addEventListener('click', () => openStatusModal(Number(btn.dataset.id), 'ACTIVE'));
    });
  }

  /* ── Pagination ─────────────────────────────────────────── */
  function renderPagination() {
    const el = $('sup-pagination');
    const total = state.filtered.length;
    const totalPages = Math.ceil(total / PAGE_SIZE);

    if (totalPages <= 1) {
      el.hidden = true;
      return;
    }

    el.hidden = false;
    const start = (state.page - 1) * PAGE_SIZE + 1;
    const end = Math.min(state.page * PAGE_SIZE, total);

    const pageButtons = [];
    for (let p = 1; p <= totalPages; p++) {
      pageButtons.push(
        `<button class="sup-page-btn${p === state.page ? ' sup-page-btn--active' : ''}" data-page="${p}">${p}</button>`,
      );
    }

    el.innerHTML = `
      <span class="sup-pagination__info">${start}–${end} / ${total} nhà cung cấp</span>
      <div class="sup-pagination__controls">
        <button class="sup-page-btn" data-page="${state.page - 1}" ${state.page === 1 ? 'disabled' : ''}>‹</button>
        ${pageButtons.join('')}
        <button class="sup-page-btn" data-page="${state.page + 1}" ${state.page === totalPages ? 'disabled' : ''}>›</button>
      </div>`;

    el.querySelectorAll('[data-page]').forEach((btn) => {
      btn.addEventListener('click', () => {
        state.page = Number(btn.dataset.page);
        renderTable();
        renderPagination();
      });
    });
  }

  /* ── Load all suppliers ─────────────────────────────────── */
  async function loadSuppliers() {
    $('sup-tbody').innerHTML = `<tr class="sup-loading-row"><td colspan="7"><div class="sup-loading">Đang tải dữ liệu…</div></td></tr>`;

    try {
      const res    = await Auth.apiFetch('/suppliers');
      const result = await res.json();
      if (!res.ok) throw new Error(result.message || 'Không tải được dữ liệu');

      state.all = result.data || [];
      applyFilter();
    } catch (e) {
      $('sup-tbody').innerHTML = `
        <tr><td colspan="7"><div class="sup-empty">
          <p>${escHtml(e.message || 'Lỗi tải dữ liệu')}</p>
        </div></td></tr>`;
      showToast(e.message || 'Không tải được danh sách nhà cung cấp', 'error');
    }
  }

  /* ── Supplier modal ─────────────────────────────────────── */
  function openSupplierModal(sup = null) {
    if (!CAN_MANAGE) {
      showToast('Bạn không có quyền quản lý nhà cung cấp.', 'error');
      return;
    }

    $('supplier-form').reset();
    $('supplier-form-message').hidden = true;

    if (sup) {
      $('supplier-modal-title').textContent = 'Sửa nhà cung cấp';
      $('supplier-modal-sub').textContent   = 'Cập nhật thông tin nhà cung cấp.';
      $('sup-id').value              = sup.id;
      $('sup-code').value            = sup.code ?? '';
      $('sup-code').disabled         = true; // Mã không thay đổi sau khi tạo
      $('sup-name').value            = sup.name ?? '';
      $('sup-tax').value             = sup.taxCode ?? '';
      $('sup-contact-name').value    = sup.contactName ?? '';
      $('sup-contact-phone').value   = sup.contactPhone ?? '';
      $('sup-email').value           = sup.email ?? '';
      $('sup-payment-terms').value   = sup.paymentTerms ?? '';
      $('sup-address').value         = sup.address ?? '';
      $('sup-notes').value           = sup.notes ?? '';
    } else {
      $('supplier-modal-title').textContent = 'Thêm nhà cung cấp';
      $('supplier-modal-sub').textContent   = 'Khai báo thông tin nhà cung cấp mới.';
      $('sup-id').value      = '';
      $('sup-code').disabled = false;
    }

    $('supplier-modal').hidden = false;
    $('sup-name').focus();
  }

  function closeSupplierModal() {
    $('supplier-modal').hidden = true;
    $('sup-code').disabled = false;
  }

  $('supplier-form').addEventListener('submit', async (e) => {
    e.preventDefault();
    const id = $('sup-id').value;

    const payload = {
      code:         $('sup-code').value.trim(),
      name:         $('sup-name').value.trim(),
      taxCode:      $('sup-tax').value.trim() || null,
      contactName:  $('sup-contact-name').value.trim() || null,
      contactPhone: $('sup-contact-phone').value.trim() || null,
      email:        $('sup-email').value.trim() || null,
      paymentTerms: $('sup-payment-terms').value || null,
      address:      $('sup-address').value.trim() || null,
      notes:        $('sup-notes').value.trim() || null,
    };

    if (!payload.name) {
      showFormError('supplier-form-message', 'Vui lòng nhập tên nhà cung cấp.');
      return;
    }
    if (!id && !payload.code) {
      showFormError('supplier-form-message', 'Vui lòng nhập mã nhà cung cấp.');
      return;
    }

    const btn = $('btn-save-supplier');
    btn.disabled    = true;
    btn.textContent = 'Đang lưu…';

    try {
      const res    = await Auth.apiFetch(
        id ? `/suppliers/${id}` : '/suppliers',
        { method: id ? 'PUT' : 'POST', body: JSON.stringify(payload) },
      );
      const result = await res.json();
      if (!res.ok || !result.success) throw new Error(result.message || 'Không lưu được');

      closeSupplierModal();
      showToast(id ? 'Đã cập nhật nhà cung cấp.' : 'Đã thêm nhà cung cấp mới.', 'success');
      await loadSuppliers();
    } catch (err) {
      showFormError('supplier-form-message', err.message || 'Lỗi không xác định');
    } finally {
      btn.disabled    = false;
      btn.textContent = 'Lưu';
    }
  });

  /* ── Status (ngưng / khôi phục) modal ──────────────────── */
  function openStatusModal(supId, targetStatus) {
    if (!CAN_MANAGE) {
      showToast('Bạn không có quyền thay đổi trạng thái nhà cung cấp.', 'error');
      return;
    }

    const sup = state.all.find((s) => s.id === supId);
    if (!sup) return;

    const isDeactivate = targetStatus === 'INACTIVE';
    $('status-modal-title').textContent = isDeactivate ? 'Ngưng giao dịch' : 'Khôi phục giao dịch';
    $('status-modal-sub').textContent   = escHtml(sup.name);
    $('status-modal-body').textContent  = isDeactivate
      ? `Nhà cung cấp «${sup.name}» sẽ bị ngưng giao dịch. Các phiếu nhập kho hiện có sẽ không bị ảnh hưởng. Bạn có thể khôi phục bất kỳ lúc nào.`
      : `Khôi phục giao dịch với nhà cung cấp «${sup.name}». Nhà cung cấp sẽ trở lại trạng thái đang giao dịch.`;

    $('status-sup-id').value = supId;
    $('status-target').value = targetStatus;
    $('status-form-message').hidden = true;

    const confirmBtn = $('btn-confirm-status');
    confirmBtn.className = isDeactivate
      ? 'btn btn--primary'
      : 'btn btn--primary';
    confirmBtn.textContent = isDeactivate ? 'Ngưng giao dịch' : 'Khôi phục';

    $('status-modal').hidden = false;
  }

  function closeStatusModal() {
    $('status-modal').hidden = true;
  }

  $('btn-confirm-status').addEventListener('click', async () => {
    const supId        = Number($('status-sup-id').value);
    const targetStatus = $('status-target').value;

    const btn = $('btn-confirm-status');
    btn.disabled    = true;
    btn.textContent = 'Đang xử lý…';

    try {
      const res    = await Auth.apiFetch(`/suppliers/${supId}/status`, {
        method: 'PATCH',
        body: JSON.stringify({ status: targetStatus }),
      });
      const result = await res.json().catch(() => ({}));
      if (!res.ok || !result.success) throw new Error(result.message || 'Không thay đổi được trạng thái');

      closeStatusModal();
      showToast(
        targetStatus === 'INACTIVE' ? 'Đã ngưng giao dịch nhà cung cấp.' : 'Đã khôi phục giao dịch.',
        'success',
      );
      await loadSuppliers();
    } catch (err) {
      showFormError('status-form-message', err.message || 'Lỗi không xác định');
    } finally {
      btn.disabled    = false;
      btn.textContent = targetStatus === 'INACTIVE' ? 'Ngưng giao dịch' : 'Khôi phục';
    }
  });

  /* ── Form error helper ──────────────────────────────────── */
  function showFormError(elId, text) {
    const el      = $(elId);
    el.hidden     = false;
    el.textContent = text;
  }

  /* ── Event bindings ─────────────────────────────────────── */

  // Add button
  $('btn-add-supplier')?.addEventListener('click', () => openSupplierModal(null));

  // Search
  let searchTimer;
  $('sup-search').addEventListener('input', () => {
    clearTimeout(searchTimer);
    searchTimer = setTimeout(() => {
      state.searchQuery = $('sup-search').value;
      applyFilter();
    }, 200);
  });

  // Status filter
  $('sup-filter-status').addEventListener('change', () => {
    state.statusFilter = $('sup-filter-status').value;
    applyFilter();
  });

  // Modal close — supplier
  document.querySelectorAll('[data-close-supplier]').forEach((btn) =>
    btn.addEventListener('click', closeSupplierModal),
  );
  $('supplier-modal').addEventListener('click', (e) => {
    if (e.target === $('supplier-modal')) closeSupplierModal();
  });

  // Modal close — status
  document.querySelectorAll('[data-close-status]').forEach((btn) =>
    btn.addEventListener('click', closeStatusModal),
  );
  $('status-modal').addEventListener('click', (e) => {
    if (e.target === $('status-modal')) closeStatusModal();
  });

  /* ── Init ───────────────────────────────────────────────── */
  loadSuppliers();
})();
