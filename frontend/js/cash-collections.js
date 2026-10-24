/* S7-05: business data stays in the database, not browser storage. */
(async () => {
  if (!Auth.requireLogin()) return;
  await Auth.ready;
  const $ = id => document.getElementById(id), U = CashCollectionUtils, esc = U.escape;
  const has = permission => Auth.hasPermission(permission);
  const canRead = () => ['COLLECTION_CREATE','COLLECTION_CONFIRM','COLLECTION_RECONCILE'].some(has);
  const canReport = () => has('COLLECTION_CREATE') || has('COLLECTION_RECONCILE');
  const canReadAll = () => has('COLLECTION_CONFIRM') || has('COLLECTION_RECONCILE');
  const state = {page:0, pages:0, dealerPage:0, dealerPages:0, dealers:[], detail:null, filters:{}, report:[]};
  const requests = new Map();
  let epoch = 0, fileEpoch = 0, busy = false, fileBusy = false, readyFile = null;
  let previewUrl = null, receiptUrl = null, uncertain = false, listBusy = false, listEpoch = 0;
  function message(id, text = '') { $(id).textContent = text; $(id).hidden = !text; }
  const stopped = () => Object.assign(Error('Phản hồi đã hết hiệu lực.'), {name:'AbortError'});
  function cancel(slot) { requests.get(slot)?.abort(); requests.delete(slot); }
  async function request(slot, path, allowed = canRead, options = {}) {
    if (!allowed()) throw Error('Bạn không có quyền thực hiện thao tác này.');
    cancel(slot);
    const controller = new AbortController(), version = epoch;
    requests.set(slot, controller);
    const valid = () => version === epoch && requests.get(slot) === controller && allowed();
    const config = {method:options.method || 'GET', cache:'no-store'};
    // Do not pretend aborting a write rolls back a transaction already sent.
    if (config.method === 'GET') config.signal = controller.signal;
    if (options.body) config.body = options.body;
    try {
      const response = await Auth.apiFetch('/finance/cash-collections' + path, config);
      if (!valid()) throw stopped();
      if (options.binary && response.ok) return {response, valid};
      const text = await response.text();
      if (!valid()) throw stopped();
      let payload;
      try { payload = U.parse(text); } catch { throw Object.assign(Error('Máy chủ trả dữ liệu không hợp lệ.'), {status:response.status}); }
      if (!response.ok || payload.success !== true)
        throw Object.assign(Error(payload.message || 'Không xử lý được yêu cầu. Vui lòng thử lại.'), {status:response.status});
      return payload.data;
    } catch (error) {
      if (!valid()) throw stopped();
      throw error;
    }
  }
  function clearImage(kind) {
    const isPreview = kind === 'preview', url = isPreview ? previewUrl : receiptUrl;
    if (url) URL.revokeObjectURL(url);
    if (isPreview) previewUrl = null; else receiptUrl = null;
    $(isPreview ? 'cc-preview-image' : 'cc-receipt-image').removeAttribute('src');
    $(isPreview ? 'cc-receipt-preview' : 'cc-receipt-view').hidden = true;
  }
  function clearCreate() {
    cancel('dealers'); fileEpoch++; fileBusy = false; readyFile = null; clearImage('preview');
    $('cc-create-form').reset(); $('cc-dealer-search').reset();
    state.dealers = []; state.dealerPage = 0; state.dealerPages = 0;
    $('cc-dealer').replaceChildren(new Option('Chọn cửa hàng', ''));
    $('cc-dealer-page').textContent = ''; $('cc-preview-name').textContent = '';
    $('cc-create').hidden = true; message('cc-create-error'); actions();
  }
  function clearDetail() {
    cancel('detail'); cancel('receipt'); clearImage('receipt'); state.detail = null;
    $('cc-detail').hidden = true; $('cc-detail-body').replaceChildren();
    $('cc-confirm-check').checked = false; message('cc-detail-error'); actions();
  }
  function canConfirm() {
    const actorId = Auth.getUser()?.id;
    return has('COLLECTION_CONFIRM') && state.detail?.status === 'PENDING'
      && actorId != null && String(state.detail.employeeId) !== String(actorId);
  }
  function actions() {
    $('cc-workspace').hidden = !canRead();
    $('cc-new').hidden = !has('COLLECTION_CREATE'); $('cc-new').disabled = busy || uncertain;
    $('cc-reload').disabled = busy;
    $('cc-save').disabled = busy || uncertain || fileBusy || !readyFile || !has('COLLECTION_CREATE');
    $('cc-save').textContent = busy ? 'Đang xử lý…' : 'Lưu khoản thu — Chờ nộp';
    $('cc-create-close').disabled = busy; $('cc-detail-close').disabled = busy;
    for (const node of document.querySelectorAll('#cc-create input, #cc-create select, #cc-create textarea, #cc-create button:not(#cc-save):not(#cc-create-close)')) node.disabled = busy;
    $('cc-dealer-prev').disabled = busy || state.dealerPage === 0;
    $('cc-dealer-next').disabled = busy || state.dealerPage + 1 >= state.dealerPages;
    $('cc-prev').disabled = busy || listBusy || state.page === 0;
    $('cc-next').disabled = busy || listBusy || state.page + 1 >= state.pages;
    $('cc-filter-save').disabled = busy;
    $('cc-employee-label').hidden = !canReadAll();
    $('cc-scope').textContent = canReadAll() ? 'Xem khoản thu trong khoảng ngày đã chọn. Mã nhân viên giúp lọc đúng người cần đối chiếu.' : 'Chỉ hiển thị khoản thu và đối chiếu do chính bạn ghi.';
    $('cc-reconciliation').hidden = !canReport();
    $('cc-receipt-load').disabled = !state.detail || !canRead();
    $('cc-confirm-form').hidden = !canConfirm();
    $('cc-confirm').disabled = busy || uncertain || !canConfirm();
    $('cc-confirm').textContent = busy ? 'Đang xử lý…' : 'Xác nhận đã nhận tiền';
    $('cc-confirm-check').disabled = busy;
    $('cc-self-note').hidden = !(has('COLLECTION_CONFIRM') && state.detail?.status === 'PENDING'
      && String(state.detail.employeeId) === String(Auth.getUser()?.id));
  }
  function filters() {
    const from = $('cc-from').value, to = $('cc-to').value;
    U.period(from, to);
    const result = {from, to};
    if (canReadAll() && $('cc-employee').value.trim()) result.employeeId = U.id($('cc-employee').value.trim());
    return result;
  }
  const query = () => new URLSearchParams(state.filters);
  function badge(status) {
    const labels = {PENDING:'Chờ nộp', RECEIVED:'Đã nhận tiền'};
    return `<span class="cc-badge ${status === 'PENDING' ? 'cc-pending' : status === 'RECEIVED' ? 'cc-received' : ''}">${esc(labels[status] || status || 'Chưa có dữ liệu')}</span>`;
  }
  async function list() {
    if (!canRead()) return;
    listBusy = true; actions();
    const version = epoch, listVersion = ++listEpoch;
    $('cc-list').innerHTML = '<tr><td colspan="6">Đang tải khoản thu…</td></tr>';
    $('cc-count').textContent = ''; $('cc-page').textContent = ''; message('cc-error');
    try {
      const params = query(); params.set('page', state.page); params.set('size', 20);
      const data = await request('list', '?' + params);
      if (!data || !Array.isArray(data.content) || !Number.isSafeInteger(data.totalPages) || !Number.isSafeInteger(data.totalElements))
        throw Error('Máy chủ trả danh sách khoản thu không hợp lệ.');
      state.pages = Number(data.totalPages) || 0;
      if (state.page >= state.pages && state.page > 0) { state.page = Math.max(0, state.pages - 1); return list(); }
      const rows = data.content || [];
      $('cc-list').innerHTML = rows.length ? rows.map(row => `<tr><td>#${esc(row.id)}<small>${esc(U.dateLabel(row.collectionDate))}</small></td><td>${esc(row.dealerName)}<small>${esc(row.routeName || 'Chưa có tuyến/địa bàn')}</small></td><td>${esc(row.employeeName)}<small>Mã NV: ${esc(row.employeeId)}</small></td><td class="cc-money">${esc(U.money(row.amount))}</td><td>${badge(row.status)}</td><td><button type="button" class="btn btn--secondary" data-collection="${esc(U.id(row.id))}">Xem chi tiết</button></td></tr>`).join('') : '<tr><td colspan="6">Chưa có khoản thu trong khoảng ngày này. Thử đổi bộ lọc.</td></tr>';
      $('cc-count').textContent = `${data.totalElements || 0} khoản thu`;
      $('cc-page').textContent = state.pages ? `Trang ${state.page + 1}/${state.pages}` : '0 trang';
      return true;
    } catch (error) {
      if (error.name !== 'AbortError') { $('cc-list').innerHTML = '<tr><td colspan="6">Không tải được khoản thu. Bấm Tải lại để thử lại.</td></tr>'; message('cc-error', error.message); }
      return false;
    } finally { if (version === epoch && listVersion === listEpoch) { listBusy = false; actions(); } }
  }
  async function report() {
    cancel('report'); state.report = [];
    for (const key of ['collected','received','pending']) $('cc-total-' + key).textContent = '—';
    $('cc-report').replaceChildren(); message('cc-report-error');
    if (!canReport()) return;
    $('cc-report').innerHTML = '<tr><td colspan="6">Đang tải đối chiếu…</td></tr>';
    try {
      const rows = await request('report', '/reconciliation?' + query(), canReport);
      if (!Array.isArray(rows)) throw Error('Dữ liệu đối chiếu không hợp lệ.');
      const html = rows.map(row => `<tr><td>${esc(U.dateLabel(row.date))}</td><td>${esc(row.employeeName)}<small>Mã NV: ${esc(row.employeeId)}</small></td><td>${esc(row.count)}</td><td class="cc-money">${esc(U.money(row.collected))}</td><td class="cc-money">${esc(U.money(row.received))}</td><td class="cc-money">${esc(U.money(row.pending))}</td></tr>`).join('');
      const totals = Object.fromEntries(['collected','received','pending'].map(key => [key, U.total(rows, key)]));
      state.report = rows;
      $('cc-report').innerHTML = html || '<tr><td colspan="6">Chưa có khoản thu để đối chiếu trong khoảng ngày này.</td></tr>';
      for (const [key, value] of Object.entries(totals)) $('cc-total-' + key).textContent = value;
    } catch (error) {
      if (error.name !== 'AbortError') { $('cc-report').innerHTML = '<tr><td colspan="6">Không tải được đối chiếu.</td></tr>'; message('cc-report-error', error.message); }
    }
  }
  async function refresh() { return Promise.all([list(), report()]); }
  async function dealers() {
    if (!has('COLLECTION_CREATE')) return;
    state.dealers = []; $('cc-dealer').replaceChildren(new Option('Đang tải cửa hàng…', '')); message('cc-create-error');
    try {
      const params = new URLSearchParams({keyword:$('cc-dealer-keyword').value.trim(), page:state.dealerPage, size:20});
      const data = await request('dealers', '/dealers?' + params, () => has('COLLECTION_CREATE'));
      if (!data || !Array.isArray(data.content) || !Number.isSafeInteger(data.totalPages) || !Number.isSafeInteger(data.totalElements))
        throw Error('Máy chủ trả danh sách cửa hàng không hợp lệ.');
      state.dealers = data.content || []; state.dealerPages = Number(data.totalPages) || 0;
      $('cc-dealer').replaceChildren(new Option(state.dealers.length ? 'Chọn cửa hàng' : 'Không có cửa hàng phụ trách phù hợp', ''),
        ...state.dealers.map(row => new Option(`${row.name} (${row.code}) — ${row.routeName || 'Chưa có tuyến'}`, String(row.id))));
      $('cc-dealer-page').textContent = state.dealerPages ? `Trang ${state.dealerPage + 1}/${state.dealerPages} · ${data.totalElements} cửa hàng` : '0 cửa hàng';
    } catch (error) {
      if (error.name !== 'AbortError') { $('cc-dealer').replaceChildren(new Option('Không tải được cửa hàng', '')); message('cc-create-error', error.message); }
    } finally { actions(); }
  }
  async function openCreate() {
    if (!has('COLLECTION_CREATE') || busy || uncertain) return;
    clearCreate(); $('cc-date').value = U.today(); $('cc-date').max = U.today();
    $('cc-create').hidden = false; $('cc-create-title').focus(); $('cc-create').scrollIntoView({block:'start'});
    await dealers();
  }
  async function chooseImage() {
    const version = ++fileEpoch;
    readyFile = null; clearImage('preview'); message('cc-create-error');
    const file = $('cc-receipt-file').files[0];
    if (!file) { actions(); return; }
    fileBusy = true; actions(); let candidate = null;
    const valid = () => version === fileEpoch && has('COLLECTION_CREATE') && !$('cc-create').hidden;
    try {
      if (file.size === 0 || file.size > 5 * 1024 * 1024) throw Error('Ảnh biên lai bắt buộc có nội dung và không quá 5 MiB.');
      const dimensions = U.imageDimensions(new Uint8Array(await file.arrayBuffer()));
      if (!dimensions[0] || !dimensions[1] || dimensions[0] * dimensions[1] > 20000000) throw Error('Ảnh phải có kích thước hợp lệ và không quá 20 megapixel.');
      if (!valid()) throw stopped();
      candidate = URL.createObjectURL(file);
      await new Promise((resolve, reject) => {
        const image = new Image();
        image.onload = () => image.naturalWidth * image.naturalHeight > 20000000 ? reject(Error('Ảnh vượt quá 20 megapixel.')) : resolve();
        image.onerror = () => reject(Error('Không đọc được ảnh biên lai. Hãy chọn JPEG/PNG khác.'));
        image.src = candidate;
      });
      if (!valid()) throw stopped();
      previewUrl = candidate; candidate = null; readyFile = file;
      $('cc-preview-image').src = previewUrl; $('cc-preview-name').textContent = file.name;
      $('cc-receipt-preview').hidden = false;
    } catch (error) {
      if (valid() && error.name !== 'AbortError') { $('cc-receipt-file').value = ''; message('cc-create-error', error.message); }
    } finally { if (candidate) URL.revokeObjectURL(candidate); if (version === fileEpoch) { fileBusy = false; actions(); } }
  }
  function renderDetail() {
    const row = state.detail;
    if (!row) return;
    const facts = [['Cửa hàng',row.dealerName],['Tuyến tại thời điểm thu',row.routeName || 'Chưa có tuyến/địa bàn'],['Nhân viên ghi thu',`${row.employeeName} (Mã NV: ${row.employeeId})`],['Ngày thu',U.dateLabel(row.collectionDate)],['Số tiền',U.money(row.amount)],['Người xác nhận',row.receivedBy || 'Chưa nhận tiền'],['Thời điểm ghi thu',row.createdAt?.replace('T',' ') || '—'],['Thời điểm nhận',row.receivedAt?.replace('T',' ') || '—'],['Ghi chú',row.note || 'Không có']];
    $('cc-detail-title').textContent = `Khoản thu #${row.id}`;
    $('cc-detail-body').innerHTML = badge(row.status) + '<dl class="cc-facts">' + facts.map(([key,value]) => `<div><dt>${esc(key)}</dt><dd>${esc(value)}</dd></div>`).join('') + '</dl>';
    $('cc-confirm-amount').textContent = U.money(row.amount); $('cc-confirm-check').checked = false;
    $('cc-detail').hidden = false; actions();
  }
  async function detail(id) {
    if (!canRead() || busy) return;
    clearDetail();
    try {
      state.detail = await request('detail', '/' + U.id(id)); renderDetail();
      $('cc-detail-title').focus(); $('cc-detail').scrollIntoView({block:'start'});
    } catch (error) { if (error.name !== 'AbortError') message('cc-error', error.message); }
  }
  async function receipt() {
    const row = state.detail;
    if (!row || !canRead()) return;
    clearImage('receipt'); message('cc-detail-error'); $('cc-receipt-load').disabled = true;
    try {
      // Construct our own same-origin API path; never trust a response receiptUrl.
      const {response, valid} = await request('receipt', '/' + U.id(row.id) + '/receipt', canRead, {binary:true});
      const blob = await response.blob();
      if (!valid()) throw stopped();
      if (!['image/jpeg','image/png'].includes(blob.type) || blob.size > 5 * 1024 * 1024) throw Error('Máy chủ không trả ảnh biên lai JPEG/PNG hợp lệ.');
      U.imageType(new Uint8Array(await blob.slice(0,8).arrayBuffer()));
      if (!valid() || state.detail?.id !== row.id) throw stopped();
      receiptUrl = URL.createObjectURL(blob); $('cc-receipt-image').src = receiptUrl; $('cc-receipt-view').hidden = false;
    } catch (error) { if (error.name !== 'AbortError') message('cc-detail-error', error.message); }
    finally { actions(); }
  }
  function writeError(error, target) {
    if (error.name === 'AbortError' || !error.status || error.status === 408 || error.status >= 500 || (error.status >= 200 && error.status < 300)) {
      uncertain = true;
      message('cc-error', 'Chưa xác định thao tác đã được lưu hay chưa. Không gửi lại ngay. Bấm Tải lại và kiểm tra khoản thu, biên lai trước khi thao tác tiếp.');
    } else message(target, error.message);
  }
  async function save(event) {
    event.preventDefault();
    if (!has('COLLECTION_CREATE') || busy || uncertain || fileBusy || !readyFile) return;
    let body;
    try {
      if (!$('cc-create-form').reportValidity()) return;
      const dealerId = U.id($('cc-dealer').value), date = $('cc-date').value;
      if (!state.dealers.some(row => String(row.id) === dealerId)) throw Error('Vui lòng chọn cửa hàng đã tải từ hệ thống.');
      U.date(date); if (date > U.today()) throw Error('Ngày thu không được ở tương lai.');
      const data = {dealerId:Number(dealerId), amount:U.amount($('cc-amount').value), collectionDate:date, note:$('cc-note').value.trim() || null};
      body = new FormData(); body.append('data', new Blob([JSON.stringify(data)], {type:'application/json'})); body.append('receipt', readyFile, readyFile.name);
    } catch (error) { message('cc-create-error', error.message); return; }
    busy = true; actions(); message('cc-create-error'); message('cc-success');
    try {
      const row = await request('save', '', () => has('COLLECTION_CREATE'), {method:'POST', body});
      clearCreate(); $('cc-from').value = row.collectionDate; $('cc-to').value = row.collectionDate;
      $('cc-employee').value = canReadAll() ? U.id(row.employeeId) : '';
      state.filters = filters(); state.page = 0; clearDetail();
      message('cc-success', `Đã ghi khoản thu #${row.id} — Chờ nộp. Kế toán cần xác nhận sau khi nhận đủ tiền.`);
      await refresh();
    } catch (error) { writeError(error, 'cc-create-error'); }
    finally { busy = false; actions(); }
  }
  async function confirm(event) {
    event.preventDefault();
    if (!canConfirm() || busy || uncertain || !$('cc-confirm-form').reportValidity()) return;
    const id = U.id(state.detail.id); busy = true; actions(); message('cc-detail-error'); message('cc-success');
    try {
      state.detail = await request('confirm', '/' + id + '/confirm', canConfirm, {method:'POST'});
      renderDetail(); message('cc-success', `Đã xác nhận nhận đủ tiền cho khoản thu #${id}.`);
      await refresh();
    } catch (error) {
      writeError(error, 'cc-detail-error');
      if (error.status === 409) { clearDetail(); await refresh(); message('cc-error', 'Khoản thu đã được người khác xác nhận. Hãy mở chi tiết để kiểm tra.'); }
    } finally { busy = false; actions(); }
  }
  $('cc-filter').addEventListener('submit', event => {
    event.preventDefault(); if (busy || !canRead()) return;
    try { state.filters = filters(); state.page = 0; clearDetail(); refresh(); }
    catch (error) { message('cc-error', error.message); }
  });
  $('cc-reload').addEventListener('click', async () => {
    if (busy || !canRead()) return;
    clearCreate(); clearDetail(); message('cc-success');
    const [loaded] = await refresh(); if (loaded) { uncertain = false; actions(); }
  });
  $('cc-new').addEventListener('click', openCreate);
  $('cc-create-close').addEventListener('click', () => { if (!busy) { clearCreate(); $('cc-new').focus(); } });
  $('cc-dealer-search').addEventListener('submit', event => { event.preventDefault(); if (!busy) { state.dealerPage = 0; dealers(); } });
  $('cc-dealer-prev').addEventListener('click', () => { if (!busy && state.dealerPage > 0) { state.dealerPage--; dealers(); } });
  $('cc-dealer-next').addEventListener('click', () => { if (!busy && state.dealerPage + 1 < state.dealerPages) { state.dealerPage++; dealers(); } });
  $('cc-receipt-file').addEventListener('change', chooseImage); $('cc-create-form').addEventListener('submit', save);
  $('cc-prev').addEventListener('click', () => { if (!busy && !listBusy && state.page > 0) { state.page--; list(); } });
  $('cc-next').addEventListener('click', () => { if (!busy && !listBusy && state.page + 1 < state.pages) { state.page++; list(); } });
  $('cc-list').addEventListener('click', event => { const button = event.target.closest('[data-collection]'); if (button) detail(button.dataset.collection); });
  $('cc-detail-close').addEventListener('click', () => { if (!busy) clearDetail(); });
  $('cc-receipt-load').addEventListener('click', receipt); $('cc-confirm-form').addEventListener('submit', confirm);
  window.addEventListener('auth:permissions-changed', () => {
    epoch++; for (const controller of requests.values()) controller.abort(); requests.clear();
    clearCreate(); clearDetail(); state.report = []; state.page = 0; listBusy = false;
    $('cc-list').replaceChildren(); $('cc-report').replaceChildren(); $('cc-count').textContent = ''; $('cc-page').textContent = '';
    for (const key of ['collected','received','pending']) $('cc-total-' + key).textContent = '—';
    message('cc-success'); message('cc-error'); $('cc-employee').value = '';
    // Use the last applied range: an unfinished date edit must not break revocation.
    state.filters = {from:state.filters.from || U.today(), to:state.filters.to || U.today()};
    $('cc-from').value = state.filters.from; $('cc-to').value = state.filters.to;
    actions(); if (canRead()) refresh();
  });
  window.addEventListener('pagehide', () => { epoch++; fileEpoch++; for (const controller of requests.values()) controller.abort(); clearImage('preview'); clearImage('receipt'); });
  const today = U.today(); $('cc-from').value = today.slice(0,7) + '-01'; $('cc-to').value = today;
  actions(); if (canRead()) { state.filters = filters(); refresh(); }
})();
