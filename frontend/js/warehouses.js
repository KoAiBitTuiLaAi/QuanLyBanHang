/** S5-03: server-backed warehouses, zones/shelves and default dealer warehouses. */
(() => {
  if (!Auth.requireLogin()) return;
  const $ = id => document.getElementById(id);
  const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  })[c]);
  const state = { warehouses: [], locations: [], dealers: [], selected: null, detail: null,
    dealerPage: 0, dealerLast: true, listVersion: 0, detailVersion: 0, dealerVersion: 0,
    initialized: false, managing: false, saving: false, edit: null };
  const canManage = () => Auth.hasPermission('WAREHOUSE_MANAGE');
  const canView = () => Auth.hasAnyPermission('WAREHOUSE_VIEW', 'WAREHOUSE_MANAGE');
  const dialog = $('wh-dialog');
  const badge = (active, yes = 'Hoạt động', no = 'Ngừng hoạt động') =>
    `<span class="wh-badge${active ? ' wh-badge--active' : ''}">${active ? yes : no}</span>`;
  const empty = (columns, text) => `<tr><td class="wh-empty" colspan="${columns}">${esc(text)}</td></tr>`;
  const types = { ZONE: 'Khu vực', SHELF: 'Kệ', AISLE: 'Dãy / Lối đi', RACK: 'Giá kệ', BIN: 'Ngăn / Ô' };

  function message(id, text = '') {
    $(id).textContent = text;
    $(id).hidden = !text;
  }
  async function request(path, options = {}) {
    const response = await Auth.apiFetch(path, options);
    const result = await response.json().catch(() => null);
    if (!response.ok || !result?.success) {
      throw new Error(result?.message || (response.status === 403
        ? 'Bạn không còn quyền thực hiện thao tác này.' : 'Không tải được dữ liệu. Vui lòng thử lại.'));
    }
    return result.data;
  }
  async function requireManage() {
    if (!await Auth.syncPermissions()) throw new Error('Không xác minh được quyền với máy chủ. Vui lòng thử lại.');
    if (!canManage()) throw new Error('Bạn chưa được cấp quyền quản lý danh mục kho và vị trí.');
  }
  const action = (name, id, label) => `<button type="button" class="wh-link" data-action="${name}" data-id="${id}">${label}</button>`;

  function renderWarehouses() {
    const keyword = $('wh-search').value.trim().toLocaleLowerCase('vi');
    const active = $('wh-active').value;
    const rows = state.warehouses.filter(w => (!active || String(w.active) === active)
      && [w.code, w.name, w.address, w.managerName, w.managerFullName, w.managerUsername]
        .some(v => String(v ?? '').toLocaleLowerCase('vi').includes(keyword)));
    $('wh-count').textContent = `${rows.length} / ${state.warehouses.length} kho`;
    $('wh-rows').innerHTML = rows.map(w => `<tr aria-selected="${w.id === state.selected}">
      <td><strong>${esc(w.name)}</strong><span class="wh-muted">${esc(w.code)}</span></td>
      <td>${esc(w.address || 'Chưa khai báo')}</td><td>${esc(w.managerName || w.managerFullName || w.managerUsername || 'Chưa khai báo')}</td>
      <td>${badge(w.active)}</td><td>${esc(w.locationCount ?? 0)} vị trí<br><span class="wh-muted">${esc(w.defaultDealerCount ?? 0)} đại lý</span></td>
      <td>${action('locations', w.id, 'Xem vị trí')}${canManage() ? action('edit-warehouse', w.id, 'Sửa kho') : ''}</td></tr>`).join('')
      || empty(6, 'Không có kho phù hợp với bộ lọc.');
  }
  function renderLocations() {
    const keyword = $('wh-location-search').value.trim().toLocaleLowerCase('vi');
    const type = $('wh-location-type').value;
    const active = $('wh-location-active').value;
    const rows = state.locations.filter(l => (!type || l.locationType === type)
      && (!active || String(l.active) === active)
      && [l.code, l.name, l.description].some(v => String(v ?? '').toLocaleLowerCase('vi').includes(keyword)));
    $('wh-location-rows').innerHTML = rows.map(l => `<tr><td><strong>${esc(l.name)}</strong><span class="wh-muted">${esc(l.code)}</span><span class="wh-muted">Thứ tự soạn: ${l.pickingSequence == null ? 'Theo mã khu/kệ' : esc(l.pickingSequence)}</span></td>
      <td>${esc(l.locationTypeDisplayName || types[l.locationType] || l.locationType)}</td>
      <td>${esc(l.parentLocationName || 'Không có')}</td><td>${esc(l.description || '—')}</td>
      <td>${badge(l.active, 'Đang dùng', 'Tạm ngừng')}</td><td>${canManage() ? action('edit-location', l.id, 'Sửa vị trí') : 'Chỉ xem'}</td></tr>`).join('')
      || empty(6, state.locations.length ? 'Không có vị trí phù hợp với bộ lọc.' : 'Kho chưa có vị trí lưu.');
  }
  function renderDealers() {
    $('wh-dealer-rows').innerHTML = state.dealers.map(d => `<tr><td><strong>${esc(d.name)}</strong><span class="wh-muted">${esc(d.code)}</span></td>
      <td>${badge(d.active, 'Đang giao dịch', 'Ngừng giao dịch')}</td>
      <td>${esc(d.warehouseName || 'Chưa gán kho')}<br><span class="wh-muted">${esc(d.warehouseCode)}</span></td>
      <td>${canManage() ? action('assign-dealer', d.id, 'Gán kho') : ''}</td></tr>`).join('') || empty(4, 'Không có đại lý phù hợp.');
    $('wh-dealer-prev').disabled = state.dealerPage === 0;
    $('wh-dealer-next').disabled = state.dealerLast;
  }
  function syncUI() {
    const wasManaging = state.managing;
    state.managing = canManage();
    document.querySelectorAll('[data-wh-manage]').forEach(el => { el.hidden = !state.managing; });
    $('wh-readonly').hidden = state.managing;
    $('wh-dealers').hidden = !state.managing;
    if (!state.managing) {
      ++state.dealerVersion;
      state.dealers = [];
      $('wh-dealer-rows').replaceChildren();
      if (dialog.open) dialog.close();
    }
    if (!canView()) {
      ++state.listVersion;
      ++state.detailVersion;
      state.warehouses = [];
      state.locations = [];
      $('wh-detail').hidden = true;
      location.href = '/error?status=403';
      return;
    }
    renderWarehouses();
    renderLocations();
    if (state.initialized && state.managing && !wasManaging) void loadDealers();
  }
  async function loadWarehouses() {
    const version = ++state.listVersion;
    $('wh-reload').disabled = true;
    message('wh-message');
    try {
      const rows = await request('/warehouses');
      if (version !== state.listVersion || !canView()) return;
      if (!Array.isArray(rows)) throw new Error('Danh sách kho trả về không hợp lệ.');
      state.warehouses = rows;
      renderWarehouses();
      if (state.selected && rows.some(w => w.id === state.selected)) await selectWarehouse(state.selected);
      else if (rows.length) await selectWarehouse(rows[0].id);
      else { state.selected = null; $('wh-detail').hidden = true; }
    } catch (error) {
      if (version !== state.listVersion) return;
      state.warehouses = [];
      renderWarehouses();
      $('wh-rows').innerHTML = empty(6, 'Chưa tải được danh sách kho. Chọn Tải lại để thử lại.');
      $('wh-detail').hidden = true;
      message('wh-message', error.message);
    } finally { if (version === state.listVersion) $('wh-reload').disabled = false; }
  }
  async function selectWarehouse(id) {
    const version = ++state.detailVersion;
    state.selected = id;
    state.detail = null;
    state.locations = [];
    $('wh-detail').hidden = false;
    $('wh-detail-title').textContent = 'Vị trí lưu trong kho';
    $('wh-detail-meta').textContent = 'Đang tải…';
    $('wh-add-location').disabled = true;
    $('wh-location-rows').innerHTML = empty(6, 'Đang tải vị trí lưu…');
    message('wh-location-message');
    renderWarehouses();
    try {
      const warehouse = await request(`/warehouses/${id}`);
      if (version !== state.detailVersion || !canView()) return;
      if (!Array.isArray(warehouse?.locations)) throw new Error('Dữ liệu vị trí lưu trả về không hợp lệ.');
      state.detail = warehouse;
      state.locations = warehouse.locations;
      $('wh-detail-title').textContent = `Vị trí lưu · ${warehouse.name}`;
      $('wh-detail-meta').textContent = `${warehouse.code} — ${warehouse.locations.length} vị trí${warehouse.active ? '' : ' — Kho ngừng hoạt động'}`;
      $('wh-add-location').disabled = !warehouse.active;
      renderLocations();
    } catch (error) {
      if (version !== state.detailVersion) return;
      $('wh-detail-meta').textContent = '';
      $('wh-location-rows').innerHTML = empty(6, 'Chưa tải được vị trí lưu.');
      message('wh-location-message', error.message);
    }
  }
  async function loadDealers() {
    if (!canManage()) return;
    const version = ++state.dealerVersion;
    message('wh-dealer-message');
    $('wh-dealer-prev').disabled = $('wh-dealer-next').disabled = true;
    $('wh-dealer-rows').innerHTML = empty(4, 'Đang tải đại lý…');
    const params = new URLSearchParams({ page: state.dealerPage, size: 20, keyword: $('wh-dealer-search').value.trim() });
    try {
      const data = await request(`/warehouses/dealer-assignments?${params}`);
      if (version !== state.dealerVersion || !canManage()) return;
      if (!Array.isArray(data?.content)) throw new Error('Danh sách đại lý trả về không hợp lệ.');
      state.dealers = data.content;
      state.dealerLast = data.last;
      $('wh-dealer-count').textContent = `${data.totalElements} đại lý · Trang ${state.dealerPage + 1}`;
      renderDealers();
    } catch (error) {
      if (version !== state.dealerVersion || !canManage()) return;
      state.dealers = [];
      $('wh-dealer-count').textContent = '';
      $('wh-dealer-rows').innerHTML = empty(4, 'Chưa tải được danh sách đại lý.');
      $('wh-dealer-prev').disabled = state.dealerPage === 0;
      message('wh-dealer-message', error.message);
    }
  }
  const field = (label, id, control, full = false) => `<label class="wh-field${full ? ' wh-field--full' : ''}" for="${id}">${label}${control}</label>`;
  const input = (id, value, max, required = false) => `<input id="${id}" value="${esc(value)}" maxlength="${max}"${required ? ' required' : ''}>`;
  const activeField = (active, yes = 'Hoạt động', no = 'Ngừng hoạt động') => field('Trạng thái', 'wh-edit-active',
    `<select id="wh-edit-active"><option value="true"${active !== false ? ' selected' : ''}>${yes}</option><option value="false"${active === false ? ' selected' : ''}>${no}</option></select>`);

  function openDialog(title, fields, edit) {
    state.edit = edit;
    dialog.innerHTML = `<form id="wh-edit-form"><header><h2 id="wh-dialog-title">${esc(title)}</h2><button type="button" class="wh-close" data-close-dialog aria-label="Đóng">×</button></header>
      <div class="wh-form-grid">${fields}</div><p id="wh-form-message" class="wh-message" role="alert" hidden></p>
      <footer><button type="button" class="btn btn--secondary" data-close-dialog>Hủy</button><button type="submit" class="btn btn--primary" id="wh-save">Lưu thay đổi</button></footer></form>`;
    dialog.showModal();
  }
  async function openWarehouse(id) {
    await requireManage();
    const w = id ? state.warehouses.find(item => item.id === id) : {};
    if (!w) return;
    openDialog(id ? 'Sửa thông tin kho' : 'Thêm kho',
      field('Mã kho *', 'wh-edit-code', input('wh-edit-code', w.code, 50, true))
      + field('Tên kho *', 'wh-edit-name', input('wh-edit-name', w.name, 150, true))
      + field('Địa chỉ', 'wh-edit-address', input('wh-edit-address', w.address, 255), true)
      + field('Người phụ trách', 'wh-edit-manager', input('wh-edit-manager', w.managerName || w.managerFullName, 100))
      + activeField(w.active), { kind: 'warehouse', id });
  }
  async function openLocation(id) {
    await requireManage();
    if (!state.detail) return;
    const l = id ? state.locations.find(item => item.id === id) : { locationType: 'SHELF' };
    if (!l) return;
    // Preserve existing backend location types; new locations follow the backlog's zone/shelf model.
    const availableTypes = Object.entries(types).filter(([key]) => ['ZONE', 'SHELF', l.locationType].includes(key));
    const parents = state.locations.filter(item => item.locationType === 'ZONE' && item.id !== id);
    openDialog(id ? 'Sửa vị trí lưu' : 'Thêm vị trí lưu',
      field('Mã vị trí', 'wh-edit-code', `<input id="wh-edit-code" value="${esc(l.code)}" maxlength="50" aria-describedby="wh-code-help" placeholder="${id ? 'Để trống để giữ mã cũ' : 'Để trống để tự sinh mã'}"><small id="wh-code-help">${id ? 'Để trống sẽ giữ nguyên mã hiện tại. Nhập mã riêng nếu muốn đổi.' : 'Để trống thì hệ thống tự sinh. Có mã trên nhãn khu/kệ thì nhập mã đó.'}</small>`)
      + field('Tên vị trí *', 'wh-edit-name', input('wh-edit-name', l.name, 120, true))
      + field('Thứ tự đường đi khi soạn', 'wh-edit-picking-sequence', `<input id="wh-edit-picking-sequence" type="number" min="0" max="1000000" step="1" value="${esc(l.pickingSequence ?? '')}" placeholder="Để trống: theo mã khu/kệ"><small>Số nhỏ đi trước trong cùng khu; áp dụng cho lượt soạn tạo mới.</small>`)
      + field('Loại vị trí *', 'wh-edit-type', `<select id="wh-edit-type">${availableTypes.map(([key, label]) => `<option value="${key}"${key === l.locationType ? ' selected' : ''}>${label}</option>`).join('')}</select>`)
      + field('Khu vực cha', 'wh-edit-parent', `<select id="wh-edit-parent"><option value="0">Không có khu vực cha</option>${parents.map(p => `<option value="${p.id}"${p.id === l.parentLocationId ? ' selected' : ''}>${esc(p.code)} — ${esc(p.name)}</option>`).join('')}</select>`)
      + field('Mô tả', 'wh-edit-description', `<textarea id="wh-edit-description" rows="3" maxlength="500">${esc(l.description)}</textarea>`, true)
      + activeField(l.active, 'Đang dùng', 'Tạm ngừng'), { kind: 'location', id, warehouseId: state.selected });
    syncParent();
  }
  function syncParent() {
    const zone = $('wh-edit-type').value === 'ZONE';
    $('wh-edit-parent').disabled = zone;
    if (zone) $('wh-edit-parent').value = '0';
  }
  async function openAssignment(id) {
    await requireManage();
    const d = state.dealers.find(item => item.id === id);
    if (!d) return;
    const active = state.warehouses.filter(w => w.active);
    openDialog('Gán kho phục vụ mặc định',
      `<p class="wh-field--full">${esc(d.code)} — ${esc(d.name)}</p>`
      + field('Kho phục vụ *', 'wh-edit-warehouse', `<select id="wh-edit-warehouse" required><option value="">Chọn kho đang hoạt động</option>${active.map(w => `<option value="${w.id}"${w.id === d.warehouseId ? ' selected' : ''}>${esc(w.code)} — ${esc(w.name)}</option>`).join('')}</select>`, true)
      + '<small class="wh-field--full">Kho mới được áp dụng cho các lần đặt hàng tiếp theo; không thay đổi kho của đơn hàng đã có.</small>',
      { kind: 'dealer', id });
    if (!active.length) message('wh-form-message', 'Chưa có kho đang hoạt động. Hãy khai báo hoặc kích hoạt kho trước.');
  }
  async function save(event) {
    event.preventDefault();
    if (state.saving) return;
    const form = event.target;
    if (!form.reportValidity()) return;
    const edit = { ...state.edit };
    const value = id => $(id).value.trim();
    let url, body, method = edit.id ? 'PUT' : 'POST';
    if (edit.kind === 'warehouse') {
      url = '/warehouses' + (edit.id ? `/${edit.id}` : '');
      body = { code: value('wh-edit-code'), name: value('wh-edit-name'), address: value('wh-edit-address'),
        managerName: value('wh-edit-manager'), active: value('wh-edit-active') === 'true' };
    } else if (edit.kind === 'location') {
      url = edit.id ? `/warehouse-locations/${edit.id}` : `/warehouses/${edit.warehouseId}/locations`;
      body = { code: value('wh-edit-code'), name: value('wh-edit-name'), locationType: value('wh-edit-type'),
        pickingSequence: value('wh-edit-picking-sequence') === '' ? null : Number(value('wh-edit-picking-sequence')),
        parentLocationId: Number(value('wh-edit-parent')), description: value('wh-edit-description'), active: value('wh-edit-active') === 'true' };
    } else {
      url = `/warehouses/dealer-assignments/${edit.id}`;
      body = { warehouseId: Number(value('wh-edit-warehouse')) };
    }
    if ('name' in body && (!body.name || (edit.kind === 'warehouse' && !body.code))) {
      message('wh-form-message', edit.kind === 'warehouse'
        ? 'Mã và tên không được chỉ chứa khoảng trắng.' : 'Tên vị trí không được chỉ chứa khoảng trắng.');
      return;
    }
    state.saving = true;
    $('wh-save').disabled = true;
    message('wh-form-message');
    try {
      await requireManage();
      await request(url, { method, body: JSON.stringify(body) });
      dialog.close();
      Toast.show('Đã lưu thay đổi.', 'success');
      // Saving ends with the mutation. Background reloads must not lock a newly opened dialog.
      void Promise.all([loadWarehouses(), canManage() ? loadDealers() : Promise.resolve()]);
    } catch (error) {
      if (dialog.open) message('wh-form-message', error.message);
      else message('wh-message', error.message);
    } finally {
      state.saving = false;
      if ($('wh-save')) $('wh-save').disabled = false;
    }
  }
  const run = operation => { void operation().catch(error => message('wh-message', error.message)); };
  $('wh-create').addEventListener('click', () => run(() => openWarehouse(null)));
  $('wh-add-location').addEventListener('click', () => run(() => openLocation(null)));
  $('wh-filter').addEventListener('submit', e => { e.preventDefault(); renderWarehouses(); });
  $('wh-active').addEventListener('change', renderWarehouses);
  $('wh-location-filter').addEventListener('submit', e => { e.preventDefault(); renderLocations(); });
  ['wh-location-type', 'wh-location-active'].forEach(id => $(id).addEventListener('change', renderLocations));
  $('wh-dealer-filter').addEventListener('submit', e => { e.preventDefault(); state.dealerPage = 0; void loadDealers(); });
  $('wh-dealer-prev').addEventListener('click', () => { if (state.dealerPage > 0) { --state.dealerPage; void loadDealers(); } });
  $('wh-dealer-next').addEventListener('click', () => { if (!state.dealerLast) { ++state.dealerPage; void loadDealers(); } });
  document.querySelector('.wh-page').addEventListener('click', e => {
    const button = e.target.closest('[data-action]');
    if (!button) return;
    const id = Number(button.dataset.id);
    const operations = { locations: () => selectWarehouse(id), 'edit-warehouse': () => openWarehouse(id),
      'edit-location': () => openLocation(id), 'assign-dealer': () => openAssignment(id) };
    if (operations[button.dataset.action]) run(operations[button.dataset.action]);
  });
  dialog.addEventListener('submit', save);
  dialog.addEventListener('change', e => { if (e.target.id === 'wh-edit-type') syncParent(); });
  dialog.addEventListener('click', e => { if (e.target.closest('[data-close-dialog]') && !state.saving) dialog.close(); });
  dialog.addEventListener('cancel', e => { if (state.saving) e.preventDefault(); });
  window.addEventListener('auth:permissions-changed', syncUI);
  async function initialize() {
    if (!await Auth.ready) { message('wh-message', 'Không kết nối được máy chủ để xác minh quyền. Chọn Tải lại để thử lại.'); return; }
    if (!canView()) { location.href = '/error?status=403'; return; }
    syncUI();
    state.initialized = true;
    await Promise.all([loadWarehouses(), canManage() ? loadDealers() : Promise.resolve()]);
  }
  $('wh-reload').addEventListener('click', () => run(initialize));
  run(initialize);
})();
