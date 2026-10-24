/** S8-06. Báo cáo doanh số đa chiều — chỉ frontend, gọi API backend khi có. */
(() => {
  if (!Auth.requireLogin()) return;
  const $ = id => document.getElementById(id);
  const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
  const money = value => new Intl.NumberFormat('vi-VN', { style: 'currency', currency: 'VND', maximumFractionDigits: 0 }).format(Number(value));
  const number = value => new Intl.NumberFormat('vi-VN', { maximumFractionDigits: 2 }).format(Number(value));
  const percent = value => new Intl.NumberFormat('vi-VN', { style: 'percent', minimumFractionDigits: 1, maximumFractionDigits: 1 }).format(Number(value) / 100);

  const GROUP_LABELS = { EMPLOYEE: 'nhân viên', REGION: 'khu vực', DEALER: 'đại lý', CATEGORY: 'nhóm hàng', SKU: 'mặt hàng' };

  const state = {
    rows: [], filters: {}, page: 0, size: 20, total: 0, pages: 0,
    loading: false, optionsReady: false, version: 0, initVersion: 0,
    controller: null, initController: null, exportController: null,
    showMargin: false, showCompare: false
  };

  /** Quyền xem báo cáo: ORDER_VIEW (doanh số nói chung). */
  const allowed = () => Auth.hasPermission('ORDER_VIEW');
  /** Quyền xem biên lợi nhuận: chỉ Quản lý kinh doanh (ROLE_SALES_MANAGER) hoặc ADMIN. */
  const canSeeMargin = () => Auth.hasRole('ROLE_ADMIN') || Auth.hasRole('ROLE_SALES_MANAGER');

  function error(text = '') { $('sr-error').textContent = text; $('sr-error').hidden = !text; }
  function empty(text) {
    const colCount = 3 + (state.showMargin ? 1 : 0) + (state.showCompare ? 2 : 0);
    $('sr-rows').innerHTML = `<tr><td colspan="${colCount}" class="sr-empty">${esc(text)}</td></tr>`;
  }

  function updateMarginVisibility() {
    state.showMargin = canSeeMargin();
    document.querySelectorAll('.sr-margin-col').forEach(el => { el.hidden = !state.showMargin; });
    $('sr-kpi-margin').hidden = !state.showMargin;
  }

  function updateCompareVisibility() {
    state.showCompare = $('sr-compare').checked;
    document.querySelectorAll('.sr-compare-col').forEach(el => { el.hidden = !state.showCompare; });
  }

  function controls() {
    $('sr-apply').disabled = $('sr-reset').disabled = !state.optionsReady || !allowed();
    $('sr-prev').disabled = state.loading || state.page <= 0 || !state.rows.length || !allowed();
    $('sr-next').disabled = state.loading || state.page + 1 >= state.pages || !state.rows.length || !allowed();
    $('sr-export').disabled = state.loading || !state.rows.length || Boolean(state.exportController) || !allowed();
    $('sr-table-region').setAttribute('aria-busy', String(state.loading));
  }

  async function request(path, signal) {
    const response = await Auth.apiFetch(path, { signal, cache: 'no-store' });
    const payload = await response.json().catch(() => null);
    if (!response.ok || !payload?.success) throw new Error(payload?.message || 'Không tải được dữ liệu báo cáo. Vui lòng thử lại.');
    return payload.data;
  }

  function filterValues() {
    const values = { groupBy: $('sr-group-by').value };
    if ($('sr-from-date').value) values.fromDate = $('sr-from-date').value;
    if ($('sr-to-date').value) values.toDate = $('sr-to-date').value;
    if ($('sr-region').value) values.regionId = $('sr-region').value;
    if ($('sr-dealer').value) values.dealerId = $('sr-dealer').value;
    if ($('sr-category').value) values.categoryId = $('sr-category').value;
    if ($('sr-employee').value) values.employeeId = $('sr-employee').value;
    if ($('sr-compare').checked) values.compare = 'true';
    return values;
  }

  function query(filters, page, size) {
    const params = new URLSearchParams({ ...filters, page, size });
    return `/reports/sales?${params}`;
  }

  function setDefaults() {
    const now = new Date();
    const firstDay = new Date(now.getFullYear(), now.getMonth(), 1);
    $('sr-from-date').value = firstDay.toISOString().slice(0, 10);
    $('sr-to-date').value = now.toISOString().slice(0, 10);
  }

  function renderSummary(summary) {
    if (!summary) { $('sr-summary').hidden = true; return; }
    $('sr-summary').hidden = false;
    $('sr-total-revenue').textContent = money(summary.totalRevenue || 0);
    $('sr-total-quantity').textContent = number(summary.totalQuantity || 0);
    if (state.showMargin) {
      $('sr-total-margin').textContent = percent(summary.averageGrossMargin || 0);
    }
  }

  function changeClass(value) {
    if (value > 0) return 'sr-change--up';
    if (value < 0) return 'sr-change--down';
    return 'sr-change--zero';
  }

  function changeArrow(value) {
    if (value > 0) return '▲';
    if (value < 0) return '▼';
    return '—';
  }

  function render() {
    const thead = $('sr-thead');
    const groupLabel = GROUP_LABELS[$('sr-group-by').value] || 'mục';
    thead.innerHTML = `<tr>
      <th scope="col">${esc(groupLabel.charAt(0).toUpperCase() + groupLabel.slice(1))}</th>
      <th scope="col" class="sr-number">Doanh số</th>
      <th scope="col" class="sr-number">Sản lượng</th>
      ${state.showMargin ? '<th scope="col" class="sr-number sr-margin-col">Biên LN gộp (%)</th>' : ''}
      ${state.showCompare ? '<th scope="col" class="sr-number sr-compare-col">DS cùng kỳ</th><th scope="col" class="sr-number sr-compare-col">Tăng/giảm (%)</th>' : ''}
    </tr>`;

    const colCount = 3 + (state.showMargin ? 1 : 0) + (state.showCompare ? 2 : 0);

    $('sr-rows').innerHTML = state.rows.map(row => {
      const changeVal = row.changePercent ?? 0;
      return `<tr>
        <td><span class="sr-group-name">${esc(row.name)}</span>${row.code ? `<span class="sr-group-code">${esc(row.code)}</span>` : ''}</td>
        <td class="sr-number">${money(row.revenue)}</td>
        <td class="sr-number">${number(row.quantity)}</td>
        ${state.showMargin ? `<td class="sr-number sr-margin-col">${percent(row.grossMargin ?? 0)}</td>` : ''}
        ${state.showCompare ? `<td class="sr-number sr-compare-col">${money(row.previousRevenue ?? 0)}</td>
          <td class="sr-number sr-compare-col"><span class="${changeClass(changeVal)}">${changeArrow(changeVal)} ${number(Math.abs(changeVal))}%</span></td>` : ''}
      </tr>`;
    }).join('');

    if (!state.rows.length) empty(`Không có dữ liệu ${groupLabel} phù hợp với bộ lọc.`);
    const start = state.rows.length ? state.page * state.size + 1 : 0;
    $('sr-count').textContent = `${start}–${start ? start + state.rows.length - 1 : 0} / ${state.total} ${groupLabel}`;
    $('sr-page-label').textContent = state.pages ? `Trang ${state.page + 1} / ${state.pages}` : '';
    $('sr-table-title').textContent = `Báo cáo doanh số theo ${groupLabel}`;
    controls();
  }

  function cancelExport() {
    state.exportController?.abort(); state.exportController = null;
    $('sr-export').textContent = 'Xuất Excel';
  }

  async function load() {
    if (!allowed() || !state.optionsReady) return;
    if (!$('sr-from-date').value || !$('sr-to-date').value) { error('Vui lòng chọn khoảng thời gian.'); return; }
    state.controller?.abort(); cancelExport();
    const version = ++state.version;
    const controller = state.controller = new AbortController();
    state.loading = true; state.rows = []; error(); controls();
    updateMarginVisibility(); updateCompareVisibility();
    const groupLabel = GROUP_LABELS[state.filters.groupBy] || 'mục';
    empty(`Đang tải báo cáo doanh số theo ${groupLabel}…`);
    $('sr-count').textContent = 'Đang tải dữ liệu…'; $('sr-page-label').textContent = '';
    try {
      const data = await request(query(state.filters, state.page, state.size), controller.signal);
      if (version !== state.version || !allowed()) return;
      if (!Array.isArray(data?.content)) throw new Error('Dữ liệu báo cáo trả về không hợp lệ.');
      if (state.page >= data.totalPages && state.page > 0) { state.page = Math.max(0, data.totalPages - 1); void load(); return; }
      state.rows = data.content; state.total = data.totalElements; state.pages = data.totalPages;
      renderSummary(data.summary);
      render();
    } catch (e) {
      if (e.name === 'AbortError' || version !== state.version) return;
      state.rows = []; state.total = state.pages = 0;
      $('sr-count').textContent = 'Chưa tải được dữ liệu'; $('sr-page-label').textContent = '';
      empty('Không tải được báo cáo. Chọn Tải lại để thử lại.'); error(e.message);
      $('sr-summary').hidden = true;
    } finally { if (version === state.version) { state.loading = false; controls(); } }
  }

  function populateSelect(id, items, allLabel, valueKey = 'id', nameKey = 'name') {
    $(id).innerHTML = `<option value="">${allLabel}</option>` + items.map(item =>
      `<option value="${esc(item[valueKey])}">${esc(item.code ? `${item.code} — ${item[nameKey]}` : item[nameKey])}</option>`).join('');
    $(id).disabled = false;
  }

  async function initialize() {
    const version = ++state.initVersion;
    state.initController?.abort();
    const controller = state.initController = new AbortController();
    state.controller?.abort(); ++state.version; cancelExport();
    state.optionsReady = false; state.rows = []; state.loading = false; error(); controls();
    empty('Đang tải bộ lọc…');
    setDefaults();
    updateMarginVisibility();
    try {
      if (!await Auth.ready) throw new Error('Không kết nối được máy chủ để xác minh quyền. Chọn Tải lại để thử lại.');
      if (!allowed()) { location.href = '/error?status=403'; return; }
      updateMarginVisibility();
      const data = await request('/reports/sales/options', controller.signal);
      if (version !== state.initVersion || !allowed()) return;
      populateSelect('sr-region', data?.regions || [], 'Tất cả khu vực');
      populateSelect('sr-dealer', data?.dealers || [], 'Tất cả đại lý');
      populateSelect('sr-category', data?.categories || [], 'Tất cả nhóm hàng');
      populateSelect('sr-employee', data?.employees || [], 'Tất cả nhân viên');
      state.optionsReady = true;
      state.filters = filterValues();
      controls();
      // Không tự động load mà đợi người dùng bấm Xem báo cáo
      empty('Chọn bộ lọc và bấm Xem báo cáo.');
    } catch (e) {
      if (e.name === 'AbortError' || version !== state.initVersion) return;
      empty('Chưa tải được bộ lọc. Chọn Tải lại để thử lại.'); $('sr-count').textContent = 'Chưa tải được dữ liệu';
      error(e.message);
    } finally { if (version === state.initVersion) controls(); }
  }

  async function exportExcel() {
    if (state.exportController || !state.rows.length) return;
    const controller = state.exportController = new AbortController();
    const version = state.version, filters = { ...state.filters };
    controls(); $('sr-export').textContent = 'Đang xuất…';
    try {
      if (!await Auth.syncPermissions() || !allowed()) throw new Error('Không xác minh được quyền xem báo cáo.');
      const allRows = [];
      for (let page = 0; ; page++) {
        const data = await request(query(filters, page, 200), controller.signal);
        if (version !== state.version || !allowed() || controller.signal.aborted) return;
        allRows.push(...(data.content || []));
        if (data.last) break;
        if (!data.content?.length) throw new Error('Dữ liệu thay đổi trong lúc tải. Vui lòng thử lại.');
      }
      const showMargin = canSeeMargin();
      const showCompare = filters.compare === 'true';
      const groupLabel = GROUP_LABELS[filters.groupBy] || 'Mục';

      const sheetData = allRows.map(r => {
        const row = {};
        row[groupLabel] = r.name || '';
        if (r.code) row['Mã'] = r.code;
        row['Doanh số'] = Number(r.revenue);
        row['Sản lượng'] = Number(r.quantity);
        if (showMargin) row['Biên LN gộp (%)'] = Number(r.grossMargin ?? 0);
        if (showCompare) {
          row['DS cùng kỳ'] = Number(r.previousRevenue ?? 0);
          row['Tăng/giảm (%)'] = Number(r.changePercent ?? 0);
        }
        return row;
      });
      const sheet = XLSX.utils.json_to_sheet(sheetData);
      const book = XLSX.utils.book_new();
      XLSX.utils.book_append_sheet(book, sheet, 'Báo cáo doanh số');
      const fromDate = filters.fromDate || '';
      const toDate = filters.toDate || '';
      XLSX.writeFile(book, `Bao-cao-doanh-so_${fromDate}_${toDate}.xlsx`);
      Toast.show('Đã xuất báo cáo doanh số theo bộ lọc đã áp dụng.', 'success');
    } catch (e) { if (e.name !== 'AbortError' && !controller.signal.aborted) error(e.message); }
    finally { if (state.exportController === controller) { state.exportController = null; $('sr-export').textContent = 'Xuất Excel'; controls(); } }
  }

  // Events
  $('sr-filter').addEventListener('submit', e => { e.preventDefault(); state.filters = filterValues(); state.page = 0; void load(); });
  $('sr-reset').addEventListener('click', () => { $('sr-filter').reset(); setDefaults(); state.filters = filterValues(); state.page = 0; state.rows = []; state.total = state.pages = 0; $('sr-summary').hidden = true; updateCompareVisibility(); render(); empty('Chọn bộ lọc và bấm Xem báo cáo.'); });
  $('sr-size').addEventListener('change', () => { state.size = Number($('sr-size').value); state.page = 0; if (state.rows.length) void load(); });
  $('sr-prev').addEventListener('click', () => { if (state.page > 0) { --state.page; void load(); } });
  $('sr-next').addEventListener('click', () => { if (state.page + 1 < state.pages) { ++state.page; void load(); } });
  $('sr-reload').addEventListener('click', () => void initialize());
  $('sr-export').addEventListener('click', () => void exportExcel());
  $('sr-compare').addEventListener('change', () => { updateCompareVisibility(); if (state.rows.length) { state.filters = filterValues(); state.page = 0; void load(); } });

  window.addEventListener('auth:permissions-changed', () => {
    updateMarginVisibility();
    controls();
    if (!allowed()) {
      ++state.version; ++state.initVersion; state.controller?.abort(); state.initController?.abort(); cancelExport();
      state.rows = []; state.optionsReady = false; state.loading = false; controls(); empty('Bạn không còn quyền xem báo cáo doanh số.');
      location.href = '/error?status=403';
    }
    // Nếu đang hiển thị dữ liệu và trạng thái margin thay đổi, cập nhật hiển thị
    if (state.rows.length) render();
  });

  void initialize();
})();
