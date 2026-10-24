/** S5-05. Read-only stock ledger; quantities, statuses and scope come from the server. */
(() => {
  if (!Auth.requireLogin()) return;
  const $ = id => document.getElementById(id);
  const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
  const quantity = value => new Intl.NumberFormat('vi-VN', { maximumFractionDigits: 4 }).format(Number(value));
  const state = { rows: [], filters: {}, page: 0, size: 20, total: 0, pages: 0, loading: false,
    optionsReady: false, version: 0, initVersion: 0, controller: null, initController: null, exportController: null };
  const allowed = () => Auth.hasPermission('WAREHOUSE_VIEW');
  const filterIds = { warehouseId: 'stock-warehouse', categoryId: 'stock-category', stockStatus: 'stock-status', belowMinimum: 'stock-below' };
  function error(text = '') { $('stock-error').textContent = text; $('stock-error').hidden = !text; }
  function empty(text) { $('stock-rows').innerHTML = `<tr><td colspan="9" class="stock-empty">${esc(text)}</td></tr>`; }
  function controls() {
    $('stock-minimum-settings').hidden = !Auth.hasPermission('WAREHOUSE_MANAGE');
    $('stock-apply').disabled = $('stock-reset').disabled = !state.optionsReady || !allowed();
    $('stock-prev').disabled = state.loading || state.page <= 0 || !state.rows.length || !allowed();
    $('stock-next').disabled = state.loading || state.page + 1 >= state.pages || !state.rows.length || !allowed();
    $('stock-export').disabled = state.loading || !state.rows.length || Boolean(state.exportController) || !allowed();
    $('stock-table-region').setAttribute('aria-busy', String(state.loading));
  }
  async function request(path, signal) {
    const response = await Auth.apiFetch(path, { signal, cache: 'no-store' });
    const payload = await response.json().catch(() => null);
    if (!response.ok || !payload?.success) throw new Error(payload?.message || 'Không tải được sổ tồn kho. Vui lòng thử lại.');
    return payload.data;
  }
  function validatePage(data) {
    if (!Array.isArray(data?.content) || !Number.isFinite(data.totalElements) || !Number.isFinite(data.totalPages)
        || typeof data.last !== 'boolean') throw new Error('Dữ liệu sổ tồn trả về không hợp lệ.');
    for (const row of data.content) {
      if (['physicalQuantity', 'reservedQuantity', 'availableQuantity'].some(key => row[key] == null || !Number.isFinite(Number(row[key]))))
        throw new Error('Dữ liệu số lượng tồn chưa đầy đủ. Vui lòng tải lại.');
    }
    return data;
  }
  function query(filters, page, size) {
    const params = new URLSearchParams({ ...filters, page, size });
    return `/warehouse/stock-ledger?${params}`;
  }
  function filterValues() {
    const values = {};
    for (const [key, id] of Object.entries(filterIds)) if ($(id).value) values[key] = $(id).value;
    if ($('stock-keyword').value.trim()) values.keyword = $('stock-keyword').value.trim();
    return values;
  }
  function updateUrl() {
    const url = new URL(location.href);
    url.search = new URLSearchParams({ ...state.filters, ...(state.page ? { page: state.page } : {}),
      ...(state.size !== 20 ? { size: state.size } : {}) }).toString();
    history.replaceState(history.state, '', url);
  }
  function render() {
    $('stock-rows').innerHTML = state.rows.map(row => `<tr class="${row.belowMinimum === true ? 'stock-row--below-minimum' : ''}">
      <td><strong>${esc(row.productName)}</strong><small>${esc(row.productCode)}</small><small><a href="/warehouse/stock-card?productId=${encodeURIComponent(row.productId)}&amp;warehouseId=${encodeURIComponent(row.warehouseId)}">Xem thẻ kho</a></small></td>
      <td>${esc(row.categoryName || 'Chưa phân nhóm')}</td><td><strong>${esc(row.warehouseName)}</strong><small>${esc(row.warehouseCode)}</small></td>
      <td>${esc(row.baseUnit || '—')}</td><td class="stock-number">${quantity(row.physicalQuantity)}</td>
      <td class="stock-number">${quantity(row.reservedQuantity)}</td><td class="stock-number stock-available">${quantity(row.availableQuantity)}</td>
      <td class="stock-number">${row.minimumQuantity == null ? '<small>Chưa khai báo</small>' : `${quantity(row.minimumQuantity)}${row.minimumUnitMatches === false ? '<small class="stock-minimum-warning">Đơn vị ngưỡng không khớp; cần khai báo lại</small>' : ''}`}</td>
      <td>${row.belowMinimum === true ? '<strong class="stock-minimum-warning">Dưới mức tối thiểu</strong>' : ''}<span class="stock-badge stock-badge--${esc(row.stockStatus)}">${esc(row.stockStatusLabel || row.stockStatus)}</span></td></tr>`).join('');
    if (!state.rows.length) empty('Không có sản phẩm phù hợp với bộ lọc.');
    const start = state.rows.length ? state.page * state.size + 1 : 0;
    $('stock-count').textContent = `${start}–${start ? start + state.rows.length - 1 : 0} / ${state.total} dòng SKU–kho`;
    $('stock-page-label').textContent = state.pages ? `Trang ${state.page + 1} / ${state.pages}` : 'Không có kết quả';
    controls();
  }
  function cancelExport() {
    state.exportController?.abort(); state.exportController = null;
    $('stock-export').textContent = 'Xuất Excel';
  }
  async function load() {
    if (!allowed() || !state.optionsReady) return;
    state.controller?.abort(); cancelExport();
    const version = ++state.version;
    const controller = state.controller = new AbortController();
    state.loading = true; state.rows = []; error(); controls();
    empty('Đang tải tồn kho…'); $('stock-count').textContent = 'Đang tải dữ liệu…'; $('stock-page-label').textContent = '';
    updateUrl();
    try {
      const data = validatePage(await request(query(state.filters, state.page, state.size), controller.signal));
      if (version !== state.version || !allowed()) return;
      if (state.page >= data.totalPages && state.page > 0) { state.page = Math.max(0, data.totalPages - 1); void load(); return; }
      state.rows = data.content; state.total = data.totalElements; state.pages = data.totalPages;
      render();
    } catch (e) {
      if (e.name === 'AbortError' || version !== state.version) return;
      state.rows = []; state.total = state.pages = 0;
      $('stock-count').textContent = 'Chưa tải được dữ liệu'; $('stock-page-label').textContent = '';
      empty('Không tải được sổ tồn. Chọn Tải lại để thử lại.'); error(e.message);
    } finally { if (version === state.version) { state.loading = false; controls(); } }
  }
  function options(id, rows, allLabel, valueKey = 'id', nameKey = 'name') {
    $(id).innerHTML = `<option value="">${allLabel}</option>` + rows.map(row =>
      `<option value="${esc(row[valueKey])}">${esc(row.code ? `${row.code} — ${row[nameKey]}` : row[nameKey])}</option>`).join('');
    $(id).disabled = false;
  }
  async function initialize() {
    const version = ++state.initVersion;
    state.initController?.abort();
    const controller = state.initController = new AbortController();
    state.controller?.abort(); ++state.version; cancelExport();
    state.optionsReady = false; state.rows = []; state.loading = false; error(); controls();
    empty('Đang tải bộ lọc và sổ tồn…');
    try {
      if (!await Auth.ready) throw new Error('Không kết nối được máy chủ để xác minh quyền. Chọn Tải lại để thử lại.');
      if (!allowed()) { location.href = '/error?status=403'; return; }
      const data = await request('/warehouse/stock-ledger/options', controller.signal);
      if (version !== state.initVersion || !allowed()) return;
      if (![data?.warehouses, data?.categories, data?.stockStatuses].every(Array.isArray)) throw new Error('Không tải được danh mục bộ lọc.');
      options('stock-warehouse', data.warehouses, 'Tất cả kho trong phạm vi');
      options('stock-category', data.categories, 'Tất cả nhóm hàng');
      options('stock-status', data.stockStatuses, 'Tất cả trạng thái', 'value', 'label');
      if (!data.warehouses.length) {
        empty('Chưa có kho trong phạm vi tài khoản. Liên hệ quản trị viên để phân công kho.');
        $('stock-count').textContent = 'Chưa có kho'; $('stock-page-label').textContent = ''; return;
      }
      const params = new URLSearchParams(location.search);
      for (const [key, id] of Object.entries(filterIds)) {
        const selected = params.get(key) || '';
        if (selected && ![...$(id).options].some(option => option.value === selected)) throw new Error('Bộ lọc trong đường dẫn không còn hợp lệ hoặc nằm ngoài phạm vi tài khoản.');
        $(id).value = selected;
      }
      $('stock-keyword').value = params.get('keyword') || '';
      state.size = [20, 50, 100].includes(Number(params.get('size'))) ? Number(params.get('size')) : 20;
      $('stock-size').value = String(state.size);
      const page = Number(params.get('page'));
      state.page = Number.isSafeInteger(page) && page >= 0 ? page : 0;
      state.filters = filterValues(); state.optionsReady = true;
      await load();
    } catch (e) {
      if (e.name === 'AbortError' || version !== state.initVersion) return;
      empty('Chưa tải được dữ liệu. Chọn Tải lại để thử lại.'); $('stock-count').textContent = 'Chưa tải được dữ liệu';
      $('stock-page-label').textContent = ''; error(e.message);
    } finally { if (version === state.initVersion) controls(); }
  }
  async function exportExcel() {
    if (state.exportController || !state.rows.length) return;
    const controller = state.exportController = new AbortController();
    const version = state.version, filters = { ...state.filters };
    controls(); $('stock-export').textContent = 'Đang xuất…';
    try {
      if (!await Auth.syncPermissions() || !allowed()) throw new Error('Không xác minh được quyền xem tồn kho.');
      const rows = [];
      for (let page = 0; ; page++) {
        const data = validatePage(await request(query(filters, page, 200), controller.signal));
        if (version !== state.version || !allowed() || controller.signal.aborted) return;
        rows.push(...data.content);
        if (data.last) break;
        if (!data.content.length) throw new Error('Dữ liệu xuất thay đổi trong lúc tải. Vui lòng thử lại.');
      }
      const sheet = XLSX.utils.json_to_sheet(rows.map(r => ({ 'Mã SKU': r.productCode, 'Sản phẩm': r.productName,
        'Nhóm hàng': r.categoryName || '', 'Mã kho': r.warehouseCode, 'Kho': r.warehouseName, 'Đơn vị': r.baseUnit,
        'Tồn thực tế': Number(r.physicalQuantity), 'Đang giữ chỗ': Number(r.reservedQuantity),
        'Tồn khả dụng': Number(r.availableQuantity), 'Trạng thái tồn': r.stockStatusLabel,
        'Mức tối thiểu': r.minimumQuantity == null ? '' : Number(r.minimumQuantity),
        'Cảnh báo tối thiểu': r.minimumUnitMatches === false ? 'Đơn vị ngưỡng không khớp' : r.belowMinimum === true ? 'Dưới mức tối thiểu' : '' })));
      const book = XLSX.utils.book_new(); XLSX.utils.book_append_sheet(book, sheet, 'Sổ tồn kho');
      XLSX.writeFile(book, 'So-ton-kho.xlsx'); Toast.show('Đã xuất sổ tồn theo bộ lọc đã áp dụng.', 'success');
    } catch (e) { if (e.name !== 'AbortError' && !controller.signal.aborted) error(e.message); }
    finally { if (state.exportController === controller) { state.exportController = null; $('stock-export').textContent = 'Xuất Excel'; controls(); } }
  }
  $('stock-filter').addEventListener('submit', e => { e.preventDefault(); state.filters = filterValues(); state.page = 0; void load(); });
  $('stock-reset').addEventListener('click', () => { $('stock-filter').reset(); state.filters = {}; state.page = 0; void load(); });
  $('stock-size').addEventListener('change', () => { state.size = Number($('stock-size').value); state.page = 0; void load(); });
  $('stock-prev').addEventListener('click', () => { if (state.page > 0) { --state.page; void load(); } });
  $('stock-next').addEventListener('click', () => { if (state.page + 1 < state.pages) { ++state.page; void load(); } });
  $('stock-reload').addEventListener('click', () => void initialize());
  $('stock-export').addEventListener('click', () => void exportExcel());
  window.addEventListener('auth:permissions-changed', () => {
    controls();
    if (!allowed()) {
      ++state.version; ++state.initVersion; state.controller?.abort(); state.initController?.abort(); cancelExport();
      state.rows = []; state.optionsReady = false; state.loading = false; controls(); empty('Bạn không còn quyền xem tồn kho.');
      location.href = '/error?status=403';
    }
  });
  void initialize();
})();
