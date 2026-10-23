/** S6-03. Server owns route, eligibility, quantities, lifecycle and warehouse scope. */
(async () => {
  if (!Auth.requireLogin()) return;
  await Auth.ready;
  const $ = id => document.getElementById(id);
  const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const number = value => value == null ? 'Chưa nhập' : new Intl.NumberFormat('vi-VN', {maximumFractionDigits: 4}).format(Number(value));
  const date = value => value ? new Date(value).toLocaleString('vi-VN') : '—';
  const canWrite = () => Auth.hasPermission('WAREHOUSE_EXPORT');
  const canRead = () => canWrite() || Auth.hasPermission('WAREHOUSE_VIEW');
  const state = { options: null, detail: null, page: 0, pages: 0, orderPage: 0, orderPages: 0,
    filters: {}, selected: new Map(), saving: false, epoch: 0, reads: new Map(), versions: new Map() };
  const message = (id, value = '') => { $(id).textContent = value; $(id).hidden = !value; };
  const notify = value => { if (typeof Toast !== 'undefined') Toast.show(value, 'success'); };
  function abort(key) { state.reads.get(key)?.abort(); state.versions.set(key, (state.versions.get(key) || 0) + 1); }
  async function request(path, options = {}) {
    const res = await Auth.apiFetch('/warehouse/picking-batches' + path, { cache: 'no-store', ...options });
    const json = await res.json().catch(() => null);
    if (!res.ok || !json?.success) throw new Error(json?.message || 'Không tải/lưu được dữ liệu. Kiểm tra kết nối rồi thử lại.');
    return json.data;
  }
  async function read(key, path, apply, errorId = 'pick-error') {
    abort(key); const version = state.versions.get(key), epoch = state.epoch;
    const controller = new AbortController(); state.reads.set(key, controller);
    try {
      const result = await request(path, { signal: controller.signal });
      if (epoch === state.epoch && version === state.versions.get(key) && canRead()) apply(result);
    } catch (error) {
      if (error.name !== 'AbortError' && epoch === state.epoch && version === state.versions.get(key) && canRead()) {
        message(errorId, error.message);
        if (key === 'list') { state.pages = 0; $('pick-list').innerHTML = '<p class="pick-empty">Không tải được danh sách. Chọn Tải lại để thử lại.</p>'; $('pick-count').textContent = 'Chưa tải được dữ liệu'; controls(); }
        if (key === 'eligible') $('pick-eligible').innerHTML = '<p class="pick-empty">Không tải được đơn. Chọn Tìm đơn để thử lại.</p>';
      }
    }
  }
  function validatePage(value) {
    if (!Array.isArray(value?.content) || !Number.isInteger(value.totalPages) || !Number.isInteger(value.totalElements)) throw new Error('Dữ liệu phân trang không hợp lệ.');
    return value;
  }
  function selectOptions(id, rows, empty) {
    const select = $(id), previous = select.value;
    select.innerHTML = `<option value="">${esc(empty)}</option>` + rows.map(row => `<option value="${esc(row.id ?? row.value)}">${esc(row.code ? row.code + ' · ' + row.name : row.label)}</option>`).join('');
    if ([...select.options].some(option => option.value === previous)) select.value = previous;
  }
  function dirtyForms() { return [...$('pick-lines').querySelectorAll('form[data-dirty="true"]')]; }
  function controls() {
    const write = canWrite(), busy = state.saving, row = state.detail;
    $('pick-new').hidden = !write || !state.options?.canCreate;
    $('pick-new').disabled = busy;
    $('pick-create-form').querySelectorAll('input,select,button').forEach(el => el.disabled = busy || !write);
    $('pick-find-orders').disabled = busy || !write || !$('pick-source').value;
    $('pick-create-submit').disabled = busy || !write || !state.selected.size;
    $('pick-create-close').disabled = busy;
    $('pick-order-prev').disabled = busy || !write || state.orderPage <= 0;
    $('pick-order-next').disabled = busy || !write || state.orderPage + 1 >= state.orderPages;
    $('pick-prev').disabled = state.page <= 0 || !canRead(); $('pick-next').disabled = state.page + 1 >= state.pages || !canRead();
    $('pick-lines').querySelectorAll('input,button').forEach(el => el.disabled = busy || !write || !row?.canEdit);
    $('pick-complete').hidden = !write || !row?.canEdit;
    $('pick-complete').disabled = busy || !row?.canComplete || dirtyForms().length > 0;
    $('pick-cancel-form').hidden = !write || !row?.canCancel;
    $('pick-cancel-form').querySelectorAll('input,button').forEach(el => el.disabled = busy || !write || !row?.canCancel);
    $('pick-detail-reload').disabled = $('pick-detail-close').disabled = busy;
    $('pick-selected').textContent = state.selected.size ? `Đã chọn ${number(state.selected.size)} đơn: ${[...state.selected.values()].join(', ')}` : 'Chưa chọn đơn.';
  }
  async function initialize() {
    if (!canRead()) { controls(); return; }
    await read('options', '/options', data => {
      if (!Array.isArray(data?.warehouses) || !Array.isArray(data.statuses) || !Number.isInteger(data.maxOrders)
          || !Number.isInteger(data.maxLines) || !Number.isInteger(data.quantityScale) || data.quantityScale < 0 || data.quantityScale > 10)
        throw new Error('Không tải được bộ chọn phiếu soạn.');
      state.options = data;
      selectOptions('pick-source', data.warehouses, 'Chọn kho soạn');
      selectOptions('pick-filter-warehouse', data.warehouses, 'Tất cả kho được giao');
      selectOptions('pick-status', data.statuses, 'Tất cả trạng thái');
      $('pick-route-strategy').textContent = data.routeStrategy;
      controls(); loadList();
    });
  }
  function loadList() {
    message('pick-error'); $('pick-list').innerHTML = '<p class="pick-empty">Đang tải các lượt soạn…</p>';
    return read('list', '?' + new URLSearchParams({ ...state.filters, page: state.page, size: 20 }), data => {
      validatePage(data); state.pages = data.totalPages;
      $('pick-count').textContent = `${number(data.totalElements)} lượt soạn`;
      $('pick-page').textContent = data.totalPages ? `Trang ${state.page + 1} / ${data.totalPages}` : 'Không có kết quả';
      $('pick-list').innerHTML = data.content.length ? data.content.map(row => `<article class="pick-batch"><div><strong>${esc(row.code)}</strong>
        <small>${esc(row.warehouseCode)} · ${esc(row.warehouseName)}</small><small>${number(row.orderCount)} đơn · ${number(row.checkedCount)}/${number(row.lineCount)} dòng đã soạn · ${number(row.shortageCount)} dòng thiếu</small>
        <small>${esc(date(row.createdAt))}</small></div><span class="pick-badge pick-badge--${['IN_PROGRESS','COMPLETED','CANCELLED'].includes(row.status) ? row.status : ''}">${esc(row.statusLabel)}</span><button type="button" class="btn btn--secondary" data-detail="${esc(row.id)}">Mở lượt soạn</button></article>`).join('') : '<p class="pick-empty">Không có lượt soạn phù hợp.</p>';
      controls();
    });
  }
  function loadEligible() {
    if (!canWrite()) return;
    message('pick-create-error'); $('pick-eligible').innerHTML = '<p class="pick-empty">Đang tải đơn có thể soạn…</p>';
    if (!$('pick-source').value) { $('pick-eligible').textContent = 'Chọn kho để tải đơn.'; return; }
    return read('eligible', '/eligible-orders?' + new URLSearchParams({ warehouseId: $('pick-source').value,
      keyword: $('pick-order-keyword').value.trim(), page: state.orderPage, size: 20 }), data => {
      validatePage(data); state.orderPages = data.totalPages;
      $('pick-order-page').textContent = data.totalPages ? `${state.orderPage + 1} / ${data.totalPages}` : 'Không có đơn';
      $('pick-eligible').innerHTML = data.content.length ? data.content.map(order => `<label><input type="checkbox" data-order="${esc(order.id)}" data-code="${esc(order.orderCode)}" ${state.selected.has(String(order.id)) ? 'checked' : ''}><span><strong>${esc(order.orderCode)}</strong><small>${esc(order.customerName || 'Khách hàng')} · ${number(order.lineCount)} dòng · Giao dự kiến ${esc(order.expectedDeliveryDate || 'Chưa khai báo')}</small></span></label>`).join('') : '<p class="pick-empty">Không có đơn đã duyệt còn giữ chỗ để soạn tại kho này.</p>';
      controls();
    }, 'pick-create-error');
  }
  function clearDetail() {
    abort('detail'); state.detail = null; $('pick-detail').hidden = true; $('pick-lines').innerHTML = '';
    $('pick-detail-info').textContent = ''; $('pick-progress').textContent = ''; $('pick-cancel-reason').value = ''; controls();
  }
  function renderDetail(row, preserve = new Map()) {
    if (!row?.summary || !Array.isArray(row.items)) throw new Error('Chi tiết phiếu không hợp lệ.');
    state.detail = row; const summary = row.summary;
    $('pick-detail').hidden = false; $('pick-detail-title').textContent = summary.code;
    $('pick-detail-info').textContent = `${summary.warehouseCode} · ${summary.warehouseName} · ${summary.statusLabel}${row.note ? ' · ' + row.note : ''}`;
    message('pick-blocked', row.blockedReason || row.cancelReason || '');
    $('pick-progress').textContent = `${number(summary.checkedCount)}/${number(summary.lineCount)} dòng đã soạn · ${number(summary.shortageCount)} dòng thiếu · ${number(summary.orderCount)} đơn`;
    $('pick-lines').innerHTML = row.items.map(item => `<article class="pick-line ${item.checked ? 'pick-line--checked' : ''}">
      <div class="pick-stop">${number(item.routePosition)}. ${esc(item.locationDisplay)}${item.locationCode ? ' · ' + esc(item.locationCode) : ''}</div>
      <h3>${esc(item.productCode)} · ${esc(item.productName)}</h3><small>Đơn ${esc(item.orderCode)}</small>
      <p class="pick-quantity">Cần soạn: ${number(item.quantity)} ${esc(item.unit)}</p>
      <form data-line="${esc(item.id)}" data-dirty="false"><div class="pick-line-fields"><label>Thực soạn (${esc(item.unit)})<input data-quantity type="number" min="0" max="${esc(item.quantity)}" step="${esc(1 / 10 ** state.options.quantityScale)}" value="${esc(item.pickedQuantity ?? '')}" inputmode="decimal" placeholder="Chưa nhập" aria-label="Thực soạn ${esc(item.productCode)}"></label>
      <label>Lý do thiếu<input data-reason maxlength="500" value="${esc(item.shortageReason || '')}" placeholder="Bắt buộc khi soạn thiếu" aria-label="Lý do thiếu ${esc(item.productCode)}"></label></div>
      <div class="pick-actions"><label class="pick-check"><input data-checked type="checkbox" ${item.checked ? 'checked' : ''}>Đã soạn dòng này</label><button type="submit" class="btn btn--primary">Lưu dòng</button></div>
      <small data-save-state>${item.pickedQuantity == null ? 'Chưa có thực soạn được lưu.' : `Đã lưu: ${number(item.pickedQuantity)} ${esc(item.unit)}${Number(item.shortageQuantity) > 0 ? ' · Thiếu ' + number(item.shortageQuantity) : ''}`}</small><p data-line-error class="pick-error" role="alert" hidden></p></form></article>`).join('');
    for (const form of $('pick-lines').querySelectorAll('form[data-line]')) {
      const draft = preserve.get(form.dataset.line);
      if (draft) { form.querySelector('[data-quantity]').value = draft.quantity; form.querySelector('[data-reason]').value = draft.reason;
        form.querySelector('[data-checked]').checked = draft.checked; form.dataset.dirty = 'true'; form.querySelector('[data-save-state]').textContent = 'Có thay đổi chưa lưu.'; }
    }
    controls();
  }
  async function openDetail(id) {
    if (dirtyForms().length && !confirm('Có dòng chưa lưu. Bỏ thay đổi để mở lại phiếu?')) return;
    clearDetail(); $('pick-detail').hidden = false; $('pick-detail-info').textContent = 'Đang tải phiếu…';
    message('pick-detail-error');
    await read('detail', '/' + encodeURIComponent(id), row => { renderDetail(row); $('pick-detail').scrollIntoView({block:'start', behavior:'smooth'}); }, 'pick-detail-error');
  }
  async function mutate(path, method, body, success, errorId = 'pick-detail-error') {
    if (!canWrite() || state.saving) return;
    const epoch = state.epoch; state.saving = true; controls(); message(errorId);
    try {
      const result = await request(path, { method, body: JSON.stringify(body) });
      if (epoch !== state.epoch || !canWrite()) return;
      success(result); loadList();
    } catch (error) { if (epoch === state.epoch && canWrite()) message(errorId, error.message); }
    finally { state.saving = false; controls(); }
  }
  $('pick-new').addEventListener('click', () => {
    if (!canWrite() || state.saving) return;
    if (dirtyForms().length && !confirm('Có dòng chưa lưu. Bỏ thay đổi để tạo lượt soạn khác?')) return;
    clearDetail(); $('pick-create').hidden = false; $('pick-create').scrollIntoView({block:'start', behavior:'smooth'});
  });
  $('pick-create-close').addEventListener('click', () => { $('pick-create').hidden = true; abort('eligible'); state.selected.clear(); controls(); });
  $('pick-source').addEventListener('change', () => { state.selected.clear(); state.orderPage = 0; state.orderPages = 0; controls(); loadEligible(); });
  $('pick-find-orders').addEventListener('click', () => { state.orderPage = 0; loadEligible(); });
  $('pick-order-keyword').addEventListener('keydown', event => {
    if (event.key === 'Enter') { event.preventDefault(); state.orderPage = 0; loadEligible(); }
  });
  $('pick-order-prev').addEventListener('click', () => { if (state.orderPage > 0) {state.orderPage--; loadEligible();} });
  $('pick-order-next').addEventListener('click', () => { if (state.orderPage + 1 < state.orderPages) {state.orderPage++; loadEligible();} });
  $('pick-eligible').addEventListener('change', event => {
    const input = event.target.closest('[data-order]'); if (!input || !canWrite()) return;
    if (input.checked) state.selected.set(input.dataset.order, input.dataset.code); else state.selected.delete(input.dataset.order);
    controls();
  });
  $('pick-create-form').addEventListener('submit', event => {
    event.preventDefault(); if (!$('pick-create-form').reportValidity() || !state.selected.size) return;
    if (state.selected.size > state.options.maxOrders) { message('pick-create-error', `Tối đa ${state.options.maxOrders} đơn mỗi lượt.`); return; }
    mutate('', 'POST', { warehouseId: Number($('pick-source').value), orderIds: [...state.selected.keys()].map(Number), code: $('pick-code').value.trim(), note: $('pick-note').value.trim() }, row => {
      state.selected.clear(); $('pick-create').hidden = true; $('pick-code').value = $('pick-note').value = ''; abort('eligible');
      abort('detail');
      renderDetail(row); $('pick-detail').scrollIntoView({block:'start', behavior:'smooth'}); notify('Đã tạo lượt soạn và lưu tại máy chủ.');
    }, 'pick-create-error');
  });
  $('pick-list').addEventListener('click', event => { const button = event.target.closest('[data-detail]'); if (button && !state.saving) openDetail(button.dataset.detail); });
  $('pick-lines').addEventListener('input', event => {
    const form = event.target.closest('form[data-line]'); if (!form) return;
    form.dataset.dirty = 'true'; form.querySelector('[data-save-state]').textContent = 'Có thay đổi chưa lưu.'; controls();
  });
  $('pick-lines').addEventListener('submit', event => {
    event.preventDefault(); const form = event.target.closest('form[data-line]'), row = state.detail;
    if (!form || !row?.canEdit || !canWrite() || state.saving || !form.reportValidity()) return;
    const raw = form.querySelector('[data-quantity]').value.trim(), checked = form.querySelector('[data-checked]').checked;
    const reason = form.querySelector('[data-reason]').value.trim(), qty = raw === '' ? null : Number(raw);
    const line = row.items.find(item => String(item.id) === form.dataset.line);
    const error = form.querySelector('[data-line-error]'); error.hidden = true;
    if (checked && qty == null || qty != null && qty < Number(line.quantity) && !reason) {
      error.textContent = checked && qty == null ? 'Nhập thực soạn trước khi đánh dấu đã soạn.' : 'Soạn thiếu bắt buộc nhập lý do.'; error.hidden = false; return;
    }
    const preserve = new Map(dirtyForms().filter(other => other !== form).map(other => [other.dataset.line, {
      quantity: other.querySelector('[data-quantity]').value, reason: other.querySelector('[data-reason]').value, checked: other.querySelector('[data-checked]').checked,
    }]));
    mutate(`/${row.summary.id}/items/${form.dataset.line}`, 'PUT', { version: row.summary.version, pickedQuantity: qty, checked, shortageReason: reason }, result => {
      renderDetail(result, preserve); $('pick-lines').querySelector(`[data-line="${form.dataset.line}"]`)?.scrollIntoView({block:'nearest'}); notify('Đã lưu dòng thực soạn.');
    });
  });
  $('pick-complete').addEventListener('click', () => {
    const row = state.detail; if (!row?.canComplete || dirtyForms().length || !canWrite() || !confirm('Hoàn tất lượt soạn? Sau khi hoàn tất không sửa thực soạn được.')) return;
    mutate(`/${row.summary.id}/complete`, 'POST', { version: row.summary.version }, result => { renderDetail(result); notify('Đã hoàn tất lượt soạn.'); });
  });
  $('pick-cancel-form').addEventListener('submit', event => {
    event.preventDefault(); const row = state.detail;
    if (!row?.canCancel || !$('pick-cancel-form').reportValidity() || !confirm('Hủy lượt soạn? Các đơn còn hợp lệ có thể được đưa vào lượt soạn mới.')) return;
    mutate(`/${row.summary.id}/cancel`, 'POST', { version: row.summary.version, reason: $('pick-cancel-reason').value.trim() }, result => { renderDetail(result); notify('Đã hủy lượt soạn. Không thay đổi tồn kho.'); });
  });
  $('pick-detail-close').addEventListener('click', () => { if (!dirtyForms().length || confirm('Đóng phiếu và bỏ các thay đổi chưa lưu?')) clearDetail(); });
  $('pick-detail-reload').addEventListener('click', () => { if (state.detail) openDetail(state.detail.summary.id); });
  $('pick-filter').addEventListener('submit', event => {
    event.preventDefault(); state.filters = {};
    for (const [key, id] of Object.entries({ warehouseId:'pick-filter-warehouse', status:'pick-status', keyword:'pick-keyword' })) if ($(id).value.trim()) state.filters[key] = $(id).value.trim();
    state.page = 0; loadList();
  });
  $('pick-reset').addEventListener('click', () => { $('pick-filter').reset(); state.filters = {}; state.page = 0; loadList(); });
  $('pick-prev').addEventListener('click', () => { if (state.page > 0) {state.page--; loadList();} });
  $('pick-next').addEventListener('click', () => { if (state.page + 1 < state.pages) {state.page++; loadList();} });
  $('pick-reload').addEventListener('click', () => { initialize(); });
  window.addEventListener('beforeunload', event => { if (dirtyForms().length) {event.preventDefault(); event.returnValue = '';} });
  window.addEventListener('auth:permissions-changed', () => {
    state.epoch++; for (const key of state.versions.keys()) abort(key);
    state.options = null; state.selected.clear(); state.page = 0; state.pages = 0; state.orderPage = 0; state.orderPages = 0;
    $('pick-create').hidden = true; $('pick-eligible').innerHTML = ''; $('pick-list').innerHTML = ''; $('pick-count').textContent = '';
    $('pick-code').value = $('pick-note').value = ''; clearDetail();
    for (const id of ['pick-source','pick-filter-warehouse','pick-status']) $(id).innerHTML = '';
    controls(); if (canRead()) initialize();
  });
  controls(); await initialize();
})();
