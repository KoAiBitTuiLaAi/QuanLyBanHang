/* S8-01 frontend only. Backend owns balances, scopes, lot metadata and transactions. */
(async () => {
  if (!Auth.requireLogin()) return;
  await Auth.ready;
  const $ = id => document.getElementById(id), U = DealerReturnUtils, esc = U.escape;
  const has = permission => Auth.hasPermission(permission);
  const read = () => U.readPermissions.some(has), sourceRead = () => U.sourcePermissions.some(has);
  const state = {catalog:null, filters:{}, page:0, pages:0, invoicePage:0, invoicePages:0, invoiceFilters:{}, invoice:null, drafts:[], detail:null};
  const requests = new Map();
  let epoch = 0, rowSequence = 0, busy = false, uncertain = false, listBusy = false, invoiceBusy = false, listVersion = 0, invoiceVersion = 0;
  const create = () => has('ORDER_CREATE') && state.catalog?.canCreate === true;
  const confirm = () => has('WAREHOUSE_APPROVE') && state.catalog?.canConfirm === true && state.detail?.canConfirm === true && state.detail?.status === 'PENDING';
  const cancelReturn = () => read() && ['ORDER_CREATE','ORDER_CANCEL','WAREHOUSE_APPROVE'].some(has) && state.detail?.canCancel === true && state.detail?.status === 'PENDING';
  function message(id, text = '') { $(id).textContent = text; $(id).hidden = !text; }
  const stopped = () => Object.assign(Error('Phản hồi đã hết hiệu lực.'), {name:'AbortError'});
  function abort(slot) { requests.get(slot)?.abort(); requests.delete(slot); }
  async function request(slot, path, allowed = read, method = 'GET', body) {
    if (!allowed()) throw Error('Bạn không có quyền thực hiện thao tác này.');
    abort(slot); const controller = new AbortController(), version = epoch;
    requests.set(slot, controller);
    const valid = () => epoch === version && requests.get(slot) === controller && allowed();
    const config = {method, cache:'no-store'};
    if (method === 'GET') config.signal = controller.signal;
    if (body !== undefined) config.body = JSON.stringify(body);
    try {
      const response = await Auth.apiFetch('/dealer-returns' + path, config);
      if (!valid()) throw stopped();
      const text = await response.text();
      if (!valid()) throw stopped();
      let payload;
      try { payload = U.parse(text); }
      catch { throw Object.assign(Error('Máy chủ trả dữ liệu không hợp lệ.'), {status:response.status}); }
      if (!response.ok || payload.success !== true) throw Object.assign(Error(payload.message || 'Không xử lý được yêu cầu trả hàng.'), {status:response.status});
      return payload.data;
    } catch (error) { if (!valid()) throw stopped(); throw error; }
  }
  function selectOptions(element, rows, placeholder, value = '') {
    element.replaceChildren(new Option(placeholder,''), ...rows.map(row => new Option(`${row.name} (${row.code})`, String(row.id))));
    element.value = rows.some(row => String(row.id) === String(value)) ? String(value) : '';
  }
  function clearSource() { abort('source'); $('dr-source').hidden = true; $('dr-source-body').replaceChildren(); message('dr-source-error'); }
  function clearCreate() {
    abort('invoices'); abort('invoice'); invoiceVersion++; invoiceBusy = false;
    state.invoice = null; state.drafts = []; state.invoicePage = 0; state.invoicePages = 0; state.invoiceFilters = {};
    $('dr-create-form').reset(); $('dr-invoice-filter').reset();
    $('dr-invoice-list').replaceChildren(); $('dr-source-lines').replaceChildren(); $('dr-draft-lines').replaceChildren();
    $('dr-invoice-page').textContent = ''; $('dr-draft-summary').textContent = '';
    $('dr-create').hidden = true; $('dr-editor').hidden = true; message('dr-create-error'); actions();
  }
  function clearDetail() {
    abort('detail'); clearSource(); state.detail = null; $('dr-detail').hidden = true;
    $('dr-detail-body').replaceChildren(); $('dr-confirm-form').reset(); $('dr-cancel-form').reset();
    $('dr-confirm-form').hidden = true; $('dr-cancel-form').hidden = true; message('dr-detail-error'); actions();
  }
  function actions() {
    $('dr-workspace').hidden = !read(); $('dr-new').hidden = !create(); $('dr-new').disabled = busy || uncertain;
    $('dr-reload').disabled = busy;
    for (const node of document.querySelectorAll('#dr-create input, #dr-create select, #dr-create textarea, #dr-create button')) node.disabled = busy || !create();
    for (const line of document.querySelectorAll('[data-draft]')) {
      const draft = state.drafts.find(row => row.key === line.dataset.draft);
      const row = state.invoice?.items.find(item => String(item.invoiceItemId) === String(draft?.invoiceItemId));
      const forced = state.catalog?.returnReasons.find(item => item.code === draft?.reason)?.defectiveDefault === true;
      const condition = line.querySelector('[data-field="defective"]');
      if (condition) condition.disabled = busy || !create() || forced;
      for (const input of line.querySelectorAll('[data-field="expiryDate"], [data-field="manufacturingDate"]')) {
        const lot = row?.availableLots.find(item => item.lotNumber === draft?.lotNumber);
        input.readOnly = !!lot?.[input.dataset.field];
      }
    }
    $('dr-save').disabled = busy || uncertain || invoiceBusy || !create() || !state.invoice || !state.drafts.length;
    $('dr-save').textContent = busy ? 'Đang xử lý…' : 'Tạo phiếu — Chờ kho xác nhận';
    $('dr-invoice-prev').disabled = busy || invoiceBusy || state.invoicePage === 0;
    $('dr-invoice-next').disabled = busy || invoiceBusy || state.invoicePage + 1 >= state.invoicePages;
    $('dr-invoice-search').disabled = busy || !create();
    $('dr-invoice-refresh').disabled = busy || invoiceBusy || !create() || !state.invoice;
    $('dr-prev').disabled = busy || listBusy || state.page === 0;
    $('dr-next').disabled = busy || listBusy || state.page + 1 >= state.pages;
    $('dr-filter-save').disabled = busy;
    $('dr-view-invoice').hidden = !state.detail || !sourceRead(); $('dr-view-invoice').disabled = busy;
    $('dr-confirm-open').hidden = !confirm(); $('dr-cancel-open').hidden = !cancelReturn();
    $('dr-confirm-open').disabled = busy || uncertain; $('dr-cancel-open').disabled = busy || uncertain;
    if (!confirm()) $('dr-confirm-form').hidden = true;
    if (!cancelReturn()) $('dr-cancel-form').hidden = true;
    for (const node of document.querySelectorAll('#dr-confirm-form input, #dr-confirm-form select, #dr-confirm-form textarea, #dr-confirm-form button, #dr-cancel-form input, #dr-cancel-form textarea, #dr-cancel-form button')) node.disabled = busy || uncertain;
    $('dr-detail-close').disabled = busy; $('dr-create-close').disabled = busy;
    for (const button of document.querySelectorAll('[data-add-line], [data-invoice]')) button.disabled = busy || invoiceBusy || !create() || button.dataset.unavailable === 'true';
  }
  function badge(row) {
    const labels = {PENDING:'Chờ kho xác nhận',CONFIRMED:'Đã xác nhận nhập kho',CANCELLED:'Đã hủy'};
    const classes = {PENDING:'dr-pending',CONFIRMED:'dr-confirmed'};
    return `<span class="dr-badge ${classes[row.status] || ''}">${esc(row.statusDisplayName || labels[row.status] || row.status)}</span>`;
  }
  const inventory = row => row.inventoryAdded === true ? 'Đã cộng tồn' : row.inventoryAdded === false ? 'Chưa cộng tồn' : 'Chưa có dữ liệu tồn';
  function filter() {
    const result = {};
    U.period($('dr-from').value, $('dr-to').value);
    for (const [id,key] of [['dr-keyword','keyword'],['dr-status','status'],['dr-filter-warehouse','warehouseId'],['dr-from','fromDate'],['dr-to','toDate']]) {
      if ($(id).value.trim()) result[key] = $(id).value.trim();
    }
    if (result.keyword) U.note(result.keyword,150);
    if (result.warehouseId) U.id(result.warehouseId);
    return result;
  }
  async function options() {
    state.catalog = null; actions(); message('dr-options-error');
    try {
      const catalog = U.options(await request('options','/options'));
      state.catalog = catalog;
      const status = $('dr-status').value;
      $('dr-status').replaceChildren(new Option('Tất cả trạng thái',''), ...catalog.statuses.map(row => new Option(row.label,row.code)));
      $('dr-status').value = catalog.statuses.some(row => row.code === status) ? status : '';
      const unique = [...new Map([...catalog.warehouses,...catalog.defectiveWarehouses].map(row => [String(row.id),row])).values()];
      selectOptions($('dr-filter-warehouse'), unique, 'Tất cả kho', $('dr-filter-warehouse').value);
      selectOptions($('dr-warehouse'), catalog.warehouses, 'Chọn kho thường');
      selectOptions($('dr-confirm-warehouse'), catalog.defectiveWarehouses, 'Giữ kho hàng lỗi trên phiếu');
      return true;
    } catch (error) { if (error.name !== 'AbortError') message('dr-options-error',error.message); return false; }
    finally { actions(); }
  }
  async function list() {
    if (!read()) return false;
    const version = ++listVersion; listBusy = true; actions();
    $('dr-list').innerHTML = '<tr><td colspan="5">Đang tải phiếu trả hàng…</td></tr>';
    $('dr-count').textContent = ''; $('dr-page').textContent = '';
    try {
      const query = new URLSearchParams({...state.filters,page:state.page,size:20});
      const data = U.page(await request('list','?' + query)); state.pages = data.totalPages;
      if (state.page > 0 && state.page >= state.pages) { state.page = Math.max(0,state.pages - 1); return list(); }
      $('dr-list').innerHTML = data.content.length ? data.content.map(row => `<tr><td>${esc(row.returnCode)}<small>${esc(U.timeLabel(row.createdAt))}</small></td><td>${esc(row.invoiceCode || row.invoiceNumber)}<small>${esc(row.dealerName)} (${esc(row.dealerCode)})</small></td><td>${esc(row.warehouseName || 'Chưa có dữ liệu')}<small>${row.defectiveWarehouseName ? 'Kho lỗi: ' + esc(row.defectiveWarehouseName) : ''}</small></td><td>${badge(row)}<small>${esc(inventory(row))}</small></td><td><button type="button" class="btn btn--secondary" data-return="${esc(U.id(row.id))}">Xem chi tiết</button></td></tr>`).join('') : '<tr><td colspan="5">Chưa có phiếu trả phù hợp. Thử đổi bộ lọc hoặc lập phiếu từ hóa đơn gốc.</td></tr>';
      $('dr-count').textContent = `${data.totalElements} phiếu`; $('dr-page').textContent = state.pages ? `Trang ${state.page + 1}/${state.pages}` : '0 trang';
      return true;
    } catch (error) {
      if (error.name !== 'AbortError') { $('dr-list').innerHTML = '<tr><td colspan="5">Không tải được phiếu trả. Bấm Tải lại để thử lại.</td></tr>'; message('dr-error',error.message); }
      return false;
    } finally { if (version === listVersion) { listBusy = false; actions(); } }
  }
  async function invoices() {
    if (!create()) return;
    const version = ++invoiceVersion; invoiceBusy = true; actions(); message('dr-create-error');
    $('dr-invoice-list').innerHTML = '<tr><td colspan="5">Đang tải hóa đơn…</td></tr>';
    try {
      const data = U.page(await request('invoices','/eligible-invoices?' + new URLSearchParams({...state.invoiceFilters,page:state.invoicePage,size:20}), create));
      state.invoicePages = data.totalPages;
      $('dr-invoice-list').innerHTML = data.content.length ? data.content.map(row => `<tr><td>${esc(row.invoiceCode || row.invoiceNumber)}<small>${esc(U.dateLabel(row.invoiceDate))}</small></td><td>${esc(row.dealerName)}<small>${esc(row.dealerCode)}</small></td><td>${esc(row.warehouseName || 'Chưa có dữ liệu')}</td><td>${esc(U.quantity(row.totalReturnableQuantity))}<small>Tổng theo đơn vị hóa đơn; xem từng dòng trước khi lập</small></td><td><button type="button" class="btn btn--secondary" data-invoice="${esc(U.id(row.invoiceId))}" data-unavailable="${row.status === 'CANCELLED' || row.totalReturnableQuantity == null || U.decimal(row.totalReturnableQuantity).value <= 0n}">Chọn hóa đơn</button></td></tr>`).join('') : '<tr><td colspan="5">Chưa có hóa đơn phù hợp. Thử số hóa đơn hoặc tên đại lý khác.</td></tr>';
      $('dr-invoice-page').textContent = state.invoicePages ? `Trang ${state.invoicePage + 1}/${state.invoicePages}` : '0 trang';
    } catch (error) { if (error.name !== 'AbortError') { $('dr-invoice-list').innerHTML = '<tr><td colspan="5">Không tải được hóa đơn. Bấm Tìm hóa đơn để thử lại.</td></tr>'; message('dr-create-error',error.message); } }
    finally { if (version === invoiceVersion) { invoiceBusy = false; actions(); } }
  }
  function sourceTable(invoice, editable = false) {
    return `<div class="dr-scroll" role="region" aria-label="Dòng hóa đơn gốc" tabindex="0"><table><thead><tr><th>Sản phẩm / Đơn</th><th>Đơn vị / Quy đổi</th><th>Đã giao</th><th>Đã trả trước</th><th>Đang chờ trả</th><th>Còn được trả</th>${editable ? '<th>Thao tác</th>' : ''}</tr></thead><tbody>${invoice.items.map(row => `<tr><td>${esc(row.productName)}<small>${esc(row.productCode)} · ${esc(row.orderCode || '—')}</small></td><td>${esc(row.unit)}<small>${esc(U.quantity(row.conversionFactor))} ${esc(row.baseUnit)} / ${esc(row.unit)}</small></td><td>${esc(U.quantity(row.deliveredQuantity))}</td><td>${esc(U.quantity(row.previouslyReturnedQuantity))}</td><td>${esc(U.quantity(row.pendingReturnedQuantity))}</td><td>${esc(U.quantity(row.returnableQuantity))}</td>${editable ? `<td><button class="btn btn--secondary" type="button" data-add-line="${esc(U.id(row.invoiceItemId))}" data-unavailable="${U.decimal(row.returnableQuantity).value <= 0n}">Thêm dòng trả</button></td>` : ''}</tr>`).join('')}</tbody></table></div>`;
  }
  async function selectInvoice(id, keep = false) {
    if (!create() || busy || invoiceBusy) return;
    const warehouse = $('dr-warehouse').value;
    state.invoice = null; if (!keep) { state.drafts = []; $('dr-create-form').reset(); }
    $('dr-editor').hidden = true; $('dr-create-check').checked = false; invoiceBusy = true; actions(); message('dr-create-error');
    const version = ++invoiceVersion;
    try {
      const data = U.source(await request('invoice','/eligible-invoices/' + U.id(id),create),id);
      state.invoice = data;
      $('dr-invoice-title').textContent = `${data.invoiceCode || data.invoiceNumber} — ${data.dealerName}`;
      $('dr-source-lines').innerHTML = sourceTable(data,true);
      selectOptions($('dr-warehouse'),state.catalog.warehouses,'Chọn kho thường',keep ? warehouse : data.warehouseId);
      $('dr-editor').hidden = false; renderDrafts(); $('dr-invoice-title').focus();
      if (keep) message('dr-create-error','Đã cập nhật số còn được trả. Kiểm tra lại số lượng từng dòng trước khi tạo phiếu.');
    } catch (error) { if (error.name !== 'AbortError') message('dr-create-error',error.message); }
    finally { if (version === invoiceVersion) { invoiceBusy = false; actions(); } }
  }
  function rowHtml(draft) {
    const row = state.invoice?.items.find(item => String(item.invoiceItemId) === draft.invoiceItemId);
    if (!row) return `<fieldset class="dr-line" data-draft="${esc(draft.key)}"><legend>Dòng hóa đơn không còn hợp lệ</legend><p class="dr-error">Hãy bỏ dòng này rồi chọn lại từ hóa đơn đã cập nhật.</p><button type="button" class="btn btn--secondary" data-remove-line="${esc(draft.key)}">Bỏ dòng</button></fieldset>`;
    const selected = (value, current) => value === current ? ' selected' : '';
    const catalog = state.catalog;
    const lots = row.availableLots;
    const knownLot = lots.find(lot => lot.lotNumber === draft.lotNumber);
    const lotFields = !row.manageLot ? '<p class="dr-muted dr-wide">SKU không quản lý lô.</p>' :
      `${lots.length ? `<label>Lô trả về<select data-field="lotNumber" required><option value="">Chọn đúng lô thực nhận</option>${lots.map(lot => `<option value="${esc(lot.lotNumber)}"${selected(lot.lotNumber,draft.lotNumber)}>${esc(lot.lotNumber)} · HSD ${esc(U.dateLabel(lot.expiryDate))}</option>`).join('')}</select></label>` : `<label>Số lô thực tế<input data-field="lotNumber" maxlength="50" required value="${esc(draft.lotNumber)}"><small>Chưa có danh sách lô. Đối chiếu nhãn hàng thực tế; không tự suy đoán lô/HSD.</small></label>`}
      <label>Hạn sử dụng của lô<input data-field="expiryDate" type="date" required value="${esc(knownLot?.expiryDate || draft.expiryDate)}"${knownLot?.expiryDate ? ' readonly' : ''}></label><label>Ngày sản xuất (nếu có)<input data-field="manufacturingDate" type="date" value="${esc(knownLot?.manufacturingDate || draft.manufacturingDate)}"${knownLot?.manufacturingDate ? ' readonly' : ''}></label>`;
    return `<fieldset class="dr-line" data-draft="${esc(draft.key)}"><legend>${esc(row.productCode)} — ${esc(row.productName)}</legend><p class="dr-meta">Dòng hóa đơn #${esc(row.invoiceItemId)} · Tối đa ${esc(U.quantity(row.returnableQuantity))} ${esc(row.unit)} cho tất cả dòng trả cùng sản phẩm.</p><div class="dr-form">
      <label>Số lượng trả (${esc(row.unit)})<input data-field="quantity" type="text" inputmode="decimal" maxlength="16" required value="${esc(draft.quantity)}" placeholder="Tối đa 4 số thập phân"></label>
      <label>Lý do trả bắt buộc<select data-field="reason" required><option value="">Chọn lý do trả</option>${catalog.returnReasons.map(reason => `<option value="${esc(reason.code)}"${selected(reason.code,draft.reason)}>${esc(reason.label)}</option>`).join('')}</select></label>
      ${draft.reason === 'OTHER' ? `<label class="dr-wide">Nội dung lý do khác<textarea data-field="otherReason" maxlength="500" rows="2" required>${esc(draft.otherReason)}</textarea></label>` : ''}
      <label>Tình trạng hàng<select data-field="defective"><option value="false"${draft.defective ? '' : ' selected'}>Hàng tốt → Kho thường</option><option value="true"${draft.defective ? ' selected' : ''}>Hàng hỏng → Kho hàng lỗi</option></select></label>
      ${draft.defective ? `<label>Kho hàng lỗi bắt buộc<select data-field="defectiveWarehouseId" required><option value="">Chọn kho hàng lỗi</option>${catalog.defectiveWarehouses.map(warehouse => `<option value="${esc(warehouse.id)}"${selected(String(warehouse.id),draft.defectiveWarehouseId)}>${esc(warehouse.name)} (${esc(warehouse.code)})</option>`).join('')}</select><small>${catalog.defectiveWarehouses.length ? 'Không nhập hàng hỏng vào kho thường.' : 'Chưa có kho hàng lỗi. Liên hệ người quản lý cấu hình kho trước khi lập phiếu.'}</small></label>` : '<p class="dr-muted">Hàng tốt nhập vào kho thường đã chọn ở đầu phiếu.</p>'}
      ${lotFields}<label class="dr-wide">Ghi chú dòng (tối đa 255 ký tự)<input data-field="note" maxlength="255" value="${esc(draft.note)}"></label></div><button class="btn btn--secondary" type="button" data-remove-line="${esc(draft.key)}">Bỏ dòng</button></fieldset>`;
  }
  function renderDrafts() {
    $('dr-draft-lines').innerHTML = state.drafts.map(rowHtml).join('');
    $('dr-empty-draft').hidden = state.drafts.length > 0; summary(); actions();
  }
  function summary() {
    $('dr-draft-summary').textContent = state.drafts.length ? `${state.drafts.length} dòng trả. Tổng các dòng cùng dòng hóa đơn được kiểm tra cộng dồn trước khi gửi; tồn do backend cập nhật sau xác nhận.` : '';
  }
  async function openCreate() {
    if (!create() || busy || uncertain) return;
    clearCreate(); clearDetail(); $('dr-create').hidden = false; $('dr-create-title').focus(); $('dr-create').scrollIntoView({block:'start'}); await invoices();
  }
  function detailTable(items) {
    return `<div class="dr-scroll" role="region" aria-label="Dòng hàng trả và kho đích" tabindex="0"><table><thead><tr><th>Sản phẩm</th><th>Đã giao / Trả trước</th><th>Trả lần này</th><th>Tình trạng / Kho đích</th><th>Lô / HSD / NSX</th><th>Lý do / Ghi chú</th></tr></thead><tbody>${items.map(row => `<tr><td>${esc(row.productName)}<small>${esc(row.productCode)} · ${esc(row.orderCode || '—')}</small></td><td>${esc(U.quantity(row.deliveredQuantity))} / ${esc(U.quantity(row.previouslyReturnedQuantity))} ${esc(row.unit)}</td><td>${esc(U.quantity(row.returnQuantity))} ${esc(row.unit)}<small>${esc(U.quantity(row.baseReturnQuantity))} ${esc(row.baseUnit)} cơ sở</small></td><td>${row.defective === true ? 'Hàng hỏng' : row.defective === false ? 'Hàng tốt' : 'Chưa có dữ liệu'}<small>${esc(row.targetWarehouseName || 'Chưa có dữ liệu')} (${esc(row.targetWarehouseCode || '—')})</small></td><td>${esc(row.lotNumber || '—')}<small>HSD: ${esc(U.dateLabel(row.expiryDate))}<br>NSX: ${esc(U.dateLabel(row.manufacturingDate))}</small></td><td>${esc(state.catalog?.returnReasons.find(reason => reason.code === row.returnReason)?.label || row.returnReason || '—')}<small>${esc(row.note || '')}</small></td></tr>`).join('')}</tbody></table></div>`;
  }
  function renderDetail() {
    const row = state.detail; if (!row) return;
    $('dr-detail-title').textContent = row.returnCode;
    const facts = [['Hóa đơn gốc',row.invoiceCode || row.invoiceNumber],['Đại lý',`${row.dealerName} (${row.dealerCode})`],['Kho thường',row.warehouseName],['Kho hàng lỗi',row.defectiveWarehouseName || 'Không có'],['Tồn kho',inventory(row)],['Người lập / Thời điểm',`${row.createdBy || '—'} · ${U.timeLabel(row.createdAt)}`],['Xác nhận / Thời điểm',`${row.confirmedBy || '—'} · ${U.timeLabel(row.confirmedAt)}`],['Ghi chú phiếu',row.note || 'Không có'],['Ghi chú xác nhận',row.confirmNote || 'Không có'],['Lý do hủy',row.cancelReason || 'Không có']];
    $('dr-detail-body').innerHTML = badge(row) + `<dl class="dr-facts">${facts.map(([label,value]) => `<div><dt>${esc(label)}</dt><dd>${esc(value || '—')}</dd></div>`).join('')}</dl>` + detailTable(row.items);
    $('dr-confirm-form').hidden = true; $('dr-cancel-form').hidden = true;
    $('dr-confirm-form').reset(); $('dr-cancel-form').reset();
    $('dr-confirm-warehouse-label').hidden = !row.items.some(item => item.defective === true);
    $('dr-detail').hidden = false; actions();
  }
  async function detail(id) {
    if (!read() || busy) return;
    clearDetail();
    try { state.detail = U.detail(await request('detail','/' + U.id(id)),id); renderDetail(); $('dr-detail-title').focus(); $('dr-detail').scrollIntoView({block:'start'}); }
    catch (error) { if (error.name !== 'AbortError') message('dr-error',error.message); }
  }
  async function viewInvoice() {
    if (!sourceRead() || !state.detail || busy) return;
    const id = U.id(state.detail.invoiceId); clearSource(); $('dr-source').hidden = false; message('dr-source-error','Đang tải hóa đơn gốc…');
    try {
      const data = U.source(await request('source','/eligible-invoices/' + id,sourceRead),id);
      $('dr-source-title').textContent = `${data.invoiceCode || data.invoiceNumber} — ${data.dealerName}`;
      $('dr-source-body').innerHTML = sourceTable(data); message('dr-source-error');
      $('dr-source-title').focus(); $('dr-source').scrollIntoView({block:'start'});
    } catch (error) { if (error.name !== 'AbortError') message('dr-source-error',error.message); }
  }
  function writeError(error, target) {
    if (error.name === 'AbortError' || !error.status || error.status === 408 || error.status >= 500 || error.status < 300) {
      uncertain = true;
      message('dr-error','Chưa xác định thao tác đã được lưu hay chưa. Không gửi lại ngay; bấm Tải lại và đối chiếu danh sách/chi tiết trước khi thao tác tiếp.');
    } else message(target,error.message);
  }
  async function save(event) {
    event.preventDefault(); if (!create() || busy || uncertain || invoiceBusy) return;
    let payload;
    try {
      if (!$('dr-create-form').reportValidity()) return;
      payload = U.build(state.invoice,state.drafts,state.catalog,$('dr-warehouse').value,$('dr-note').value);
    } catch (error) { message('dr-create-error',error.message); return; }
    busy = true; actions(); message('dr-create-error'); message('dr-success');
    try {
      const result = await request('save','',create,'POST',payload);
      const row = U.detail(result,result?.id);
      clearCreate(); clearDetail(); state.detail = row; renderDetail();
      $('dr-filter').reset(); state.filters = {}; state.page = 0;
      message('dr-success',`Đã tạo phiếu ${row.returnCode}. ${inventory(row)}; xem trạng thái và chờ người có quyền xác nhận nhận hàng.`);
      await list(); $('dr-detail-title').focus(); $('dr-detail').scrollIntoView({block:'start'});
    } catch (error) { writeError(error,'dr-create-error'); }
    finally { busy = false; actions(); }
  }
  async function writeDetail(event, kind) {
    event.preventDefault(); const allowed = kind === 'confirm' ? confirm : cancelReturn;
    if (!allowed() || busy || uncertain) return;
    let body;
    try {
      if (!$('dr-' + kind + '-form').reportValidity()) return;
      if (kind === 'confirm') {
        body = {note:U.note($('dr-confirm-note').value) || null};
        if (!$('dr-confirm-warehouse-label').hidden && $('dr-confirm-warehouse').value) {
          const id = U.id($('dr-confirm-warehouse').value);
          if (!state.catalog.defectiveWarehouses.some(row => String(row.id) === id)) throw Error('Chọn kho hàng lỗi hợp lệ.');
          body.defectiveWarehouseId = Number(id);
        }
      } else body = {reason:U.note($('dr-cancel-reason').value,500,true)};
    } catch (error) { message('dr-detail-error',error.message); return; }
    const id = U.id(state.detail.id); busy = true; actions(); message('dr-detail-error'); message('dr-success');
    try {
      const result = await request('write-detail','/' + id + '/' + kind,allowed,'POST',body);
      const row = U.detail(result,id);
      if (row.status !== (kind === 'confirm' ? 'CONFIRMED' : 'CANCELLED')) throw Object.assign(Error('Trạng thái phản hồi chưa khớp thao tác.'),{status:200});
      state.detail = row; renderDetail();
      message('dr-success',kind === 'confirm' ? `Đã xác nhận phiếu ${row.returnCode}. ${inventory(row)}.` : `Đã hủy phiếu ${row.returnCode}. ${inventory(row)}.`);
      await list();
    } catch (error) {
      writeError(error,'dr-detail-error');
      if (error.status === 409) { clearDetail(); await list(); message('dr-error','Phiếu đã thay đổi trên máy chủ. Mở lại chi tiết để kiểm tra trước khi thao tác.'); }
    } finally { busy = false; actions(); }
  }
  $('dr-new').addEventListener('click',openCreate);
  $('dr-create-close').addEventListener('click',() => { if (!busy) { clearCreate(); $('dr-new').focus(); } });
  $('dr-invoice-filter').addEventListener('submit',event => {
    event.preventDefault(); if (!create() || busy) return;
    try {
      const filters = {}; const keyword = U.note($('dr-invoice-keyword').value,150), dealer = $('dr-invoice-dealer').value.trim();
      if (keyword) filters.keyword = keyword; if (dealer) filters.dealerId = U.id(dealer);
      abort('invoice'); state.invoice = null; state.drafts = []; $('dr-create-form').reset(); $('dr-editor').hidden = true;
      state.invoiceFilters = filters; state.invoicePage = 0; invoices();
    } catch (error) { message('dr-create-error',error.message); }
  });
  $('dr-invoice-list').addEventListener('click',event => { const button = event.target.closest('[data-invoice]'); if (button && !button.disabled) selectInvoice(button.dataset.invoice); });
  $('dr-invoice-prev').addEventListener('click',() => { if (!busy && !invoiceBusy && state.invoicePage > 0) { state.invoicePage--; invoices(); } });
  $('dr-invoice-next').addEventListener('click',() => { if (!busy && !invoiceBusy && state.invoicePage + 1 < state.invoicePages) { state.invoicePage++; invoices(); } });
  $('dr-invoice-refresh').addEventListener('click',() => { if (state.invoice) selectInvoice(state.invoice.invoiceId,true); });
  $('dr-source-lines').addEventListener('click',event => {
    const button = event.target.closest('[data-add-line]'); if (!button || button.disabled || !create() || busy || invoiceBusy) return;
    const row = state.invoice?.items.find(item => String(item.invoiceItemId) === button.dataset.addLine);
    if (!row || U.decimal(row.returnableQuantity).value <= 0n) return;
    state.drafts.push({key:String(++rowSequence),invoiceItemId:U.id(row.invoiceItemId),quantity:'',reason:'',otherReason:'',defective:false,defectiveWarehouseId:'',lotNumber:'',expiryDate:'',manufacturingDate:'',note:''});
    $('dr-create-check').checked = false; renderDrafts(); $('dr-draft-lines').lastElementChild.querySelector('input')?.focus();
  });
  $('dr-draft-lines').addEventListener('click',event => {
    const button = event.target.closest('[data-remove-line]'); if (!button || busy || !create()) return;
    state.drafts = state.drafts.filter(row => row.key !== button.dataset.removeLine); $('dr-create-check').checked = false; renderDrafts();
  });
  function editDraft(event) {
    const field = event.target.dataset.field, line = event.target.closest('[data-draft]');
    if (!field || !line || busy || !create()) return;
    const draft = state.drafts.find(row => row.key === line.dataset.draft); if (!draft) return;
    const rerender = event.type === 'change' && ['reason','defective','lotNumber'].includes(field);
    draft[field] = field === 'defective' ? event.target.value === 'true' : event.target.value;
    if (field === 'reason' && event.type === 'change') draft.defective = state.catalog.returnReasons.find(row => row.code === draft.reason)?.defectiveDefault === true;
    if (field === 'lotNumber' && event.target.tagName === 'SELECT') {
      const lot = state.invoice?.items.find(row => String(row.invoiceItemId) === draft.invoiceItemId)?.availableLots.find(row => row.lotNumber === draft.lotNumber);
      draft.expiryDate = lot?.expiryDate || ''; draft.manufacturingDate = lot?.manufacturingDate || '';
    }
    $('dr-create-check').checked = false;
    if (rerender) { const replacement = document.createElement('div'); replacement.innerHTML = rowHtml(draft); line.replaceWith(replacement.firstElementChild); actions(); }
  }
  $('dr-draft-lines').addEventListener('input',editDraft); $('dr-draft-lines').addEventListener('change',editDraft);
  for (const id of ['dr-warehouse','dr-note']) $(id).addEventListener('input',() => { $('dr-create-check').checked = false; });
  $('dr-create-form').addEventListener('submit',save);
  $('dr-filter').addEventListener('submit',event => { event.preventDefault(); if (busy || !read()) return; try { state.filters = filter(); state.page = 0; clearDetail(); message('dr-error'); list(); } catch (error) { message('dr-error',error.message); } });
  $('dr-prev').addEventListener('click',() => { if (!busy && !listBusy && state.page > 0) { state.page--; list(); } });
  $('dr-next').addEventListener('click',() => { if (!busy && !listBusy && state.page + 1 < state.pages) { state.page++; list(); } });
  $('dr-list').addEventListener('click',event => { const button = event.target.closest('[data-return]'); if (button) detail(button.dataset.return); });
  $('dr-detail-close').addEventListener('click',() => { if (!busy) clearDetail(); }); $('dr-view-invoice').addEventListener('click',viewInvoice); $('dr-source-close').addEventListener('click',clearSource);
  for (const kind of ['confirm','cancel']) {
    $('dr-' + kind + '-open').addEventListener('click',() => {
      if (busy || uncertain || !(kind === 'confirm' ? confirm() : cancelReturn())) return;
      $('dr-confirm-form').hidden = true; $('dr-cancel-form').hidden = true;
      $('dr-' + kind + '-form').reset(); $('dr-' + kind + '-form').hidden = false; message('dr-detail-error');
      $('dr-' + kind + (kind === 'confirm' ? '-check' : '-reason')).focus();
    });
    $('dr-' + kind + '-close').addEventListener('click',() => { if (!busy) { $('dr-' + kind + '-form').hidden = true; $('dr-' + kind + '-form').reset(); } });
    $('dr-' + kind + '-form').addEventListener('submit',event => writeDetail(event,kind));
  }
  $('dr-reload').addEventListener('click',async () => {
    if (busy || !read()) return; clearCreate(); clearDetail(); message('dr-error'); message('dr-success');
    const [,loaded] = await Promise.all([options(),list()]); if (loaded) uncertain = false; actions();
  });
  window.addEventListener('auth:permissions-changed',() => {
    epoch++; for (const controller of requests.values()) controller.abort(); requests.clear();
    clearCreate(); clearDetail(); state.catalog = null; state.page = 0; listVersion++; listBusy = false;
    $('dr-list').replaceChildren(); $('dr-count').textContent = ''; $('dr-page').textContent = '';
    $('dr-status').replaceChildren(new Option('Tất cả trạng thái','')); $('dr-filter-warehouse').replaceChildren(new Option('Tất cả kho','')); $('dr-warehouse').replaceChildren(new Option('Chọn kho thường','')); $('dr-confirm-warehouse').replaceChildren(new Option('Giữ kho hàng lỗi trên phiếu',''));
    $('dr-filter').reset(); state.filters = {}; message('dr-error'); message('dr-success'); message('dr-options-error');
    actions(); if (read()) { options(); list(); }
  });
  window.addEventListener('beforeunload',event => { if (state.drafts.length || busy) { event.preventDefault(); event.returnValue = ''; } });
  window.addEventListener('pagehide',() => { epoch++; for (const controller of requests.values()) controller.abort(); });
  actions(); if (read()) { options(); list(); }
})();
