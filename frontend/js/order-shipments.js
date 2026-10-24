/** S6-05: only server-owned quantities, reservations, scope and confirmed documents. */
(async () => {
  if (!Auth.requireLogin()) return;
  await Auth.ready;
  const $ = id => document.getElementById(id);
  const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const number = value => new Intl.NumberFormat('vi-VN', {maximumFractionDigits: 4}).format(Number(value));
  const canWrite = () => Auth.hasPermission('WAREHOUSE_EXPORT');
  const canRead = () => canWrite() || Auth.hasPermission('WAREHOUSE_VIEW');
  const state = {page: 0, pages: 0, filters: {}, detail: null, options: null, busy: false, uncertain: false, epoch: 0, reads: new Map(), serial: new Map()};
  function message(id, value = '') { $(id).textContent = value; $(id).hidden = !value; }
  function abort(key) { state.reads.get(key)?.abort(); state.serial.set(key, (state.serial.get(key) || 0) + 1); }
  async function request(path, options = {}) {
    const res = await Auth.apiFetch('/warehouse/order-shipments' + path, {cache:'no-store', ...options});
    const json = await res.json().catch(() => null);
    if (!res.ok || !json?.success) { const e = new Error(json?.message || 'Không nhận được kết quả. Tải lại đơn để đối chiếu phiếu trước khi thử lại.'); e.status = res.status; throw e; }
    return json.data;
  }
  async function read(key, path, apply, errorId = 'ship-error') {
    abort(key); const serial = state.serial.get(key), epoch = state.epoch, c = new AbortController(); state.reads.set(key, c);
    try { const data = await request(path, {signal:c.signal}); if (epoch === state.epoch && serial === state.serial.get(key) && canRead()) apply(data); }
    catch (e) { if (e.name !== 'AbortError' && epoch === state.epoch && serial === state.serial.get(key) && canRead()) {
      message(errorId, e.message);
      if (key === 'list') { $('ship-list').textContent = 'Không tải được danh sách. Chọn Tải lại để thử lại.'; $('ship-count').textContent = 'Chưa tải được dữ liệu'; $('ship-page').textContent = ''; controls(); }
    } }
  }
  function controls() {
    const edit = canWrite() && state.options?.canConfirm && state.detail?.canConfirm && !state.uncertain;
    $('ship-confirm').hidden = !edit;
    $('ship-confirm-form').querySelectorAll('input,select,button').forEach(el => el.disabled = !edit || state.busy);
    for (const id of ['ship-close','ship-detail-reload','ship-reload']) $(id).disabled = state.busy;
    $('ship-filter').querySelectorAll('input,select,button').forEach(el => el.disabled = !canRead() || state.busy);
    $('ship-list').querySelectorAll('button').forEach(el => el.disabled = state.busy);
    $('ship-prev').disabled = state.busy || state.page <= 0 || !canRead(); $('ship-next').disabled = state.busy || state.page + 1 >= state.pages || !canRead();
  }
  function clearDetail() { abort('detail'); state.detail = null; state.uncertain = false; $('ship-detail').hidden = true; $('ship-lines').replaceChildren(); $('ship-receipts').replaceChildren(); $('ship-note').value = ''; controls(); }
  function renderDetail(d) {
    if (!Array.isArray(d?.items) || !Array.isArray(d.receipts)) throw new Error('Chi tiết đơn không hợp lệ');
    state.detail = d; state.uncertain = false; $('ship-detail').hidden = false;
    $('ship-title').textContent = d.orderCode;
    $('ship-info').textContent = [d.warehouseName, d.customerName, d.statusLabel].filter(Boolean).join(' · ');
    message('ship-blocked', d.blockedReason || (!canWrite() ? 'Chỉ xem. Bạn chưa có quyền xác nhận xuất kho.' : ''));
    $('ship-note').value = '';
    $('ship-lines').innerHTML = d.items.map(i => `<article class="pick-line" data-line="${esc(i.id)}"><h3>${esc(i.code)} · ${esc(i.name)}</h3>
      <p class="ship-totals">Đặt: ${number(i.ordered)} · Đã xuất: ${number(i.exported)} · Còn nợ: ${number(i.remaining)} ${esc(i.unit)}</p>
      <div class="ship-line-grid"><label>Xuất lượt này (${esc(i.unit)})<input data-quantity type="number" min="0" max="${esc(i.remaining)}" step="any" value="${esc(i.remaining)}" required></label>
      ${i.lotManaged ? `<label>Lô xuất<select data-lot><option value="">Tự động theo FEFO</option>${i.lots.map(l => `<option value="${esc(l.lotNumber)}">${esc(l.lotNumber)} · HSD ${esc(l.expiryDate)} · ${number(l.baseQuantity)} ${esc(i.baseUnit)}</option>`).join('')}</select></label>` : ''}
      <label>Lý do xuất thiếu / đổi lô<input data-reason maxlength="255"></label></div></article>`).join('');
    $('ship-receipts').innerHTML = d.receipts.length ? d.receipts.map(v => `<article class="pick-line ship-receipt"><h4>${esc(v.code)}</h4><p>Đã xác nhận · ${esc(new Date(v.confirmedAt).toLocaleString('vi-VN'))} · ${esc(v.confirmedBy)}</p><p>${esc(v.note)}</p>
      <ul>${v.items.map(i => `<li>${esc(i.productCode)} · ${number(i.quantity)} ${esc(i.unit)}${i.lotNumber ? ' · Lô ' + esc(i.lotNumber) : ''}${i.reason ? ' · ' + esc(i.reason) : ''}</li>`).join('')}</ul><small>Phiếu chỉ đọc, không sửa/xóa.</small></article>`).join('') : '<p>Chưa có phiếu xuất theo đơn.</p>';
    controls();
  }
  function openDetail(id) {
    clearDetail(); message('ship-detail-error');
    return read('detail', '/' + encodeURIComponent(id), d => {renderDetail(d); $('ship-title').focus();}, 'ship-error');
  }
  function loadList() {
    $('ship-list').textContent = 'Đang tải…'; state.pages = 0; controls(); message('ship-error');
    return read('list', '?' + new URLSearchParams({...state.filters, page:state.page, size:20}), data => {
      if (!Array.isArray(data?.content) || !Number.isInteger(data.totalPages)) throw new Error('Danh sách không hợp lệ');
      state.pages = data.totalPages; $('ship-count').textContent = `${number(data.totalElements)} đơn`;
      $('ship-page').textContent = data.totalPages ? `${state.page + 1} / ${data.totalPages}` : 'Không có kết quả';
      $('ship-list').innerHTML = data.content.length ? data.content.map(o => `<article class="pick-batch"><div><strong>${esc(o.orderCode)}</strong><small>${esc(o.warehouseName)} · ${esc(o.customerName)}</small><small>${esc(o.statusLabel)}</small></div><button class="btn btn--secondary" type="button" data-open="${esc(o.id)}">Mở đơn</button></article>`).join('') : '<p class="pick-empty">Không có đơn phù hợp.</p>';
      controls();
    });
  }
  async function initialize() {
    if (!canRead()) { $('ship-list-panel').hidden = true; message('ship-error','Bạn không có quyền xem xuất kho theo đơn.'); return; }
    $('ship-list-panel').hidden = false;
    await read('options','/options', data => {
      if (!Array.isArray(data?.warehouses) || !Number.isInteger(data.quantityScale)) throw new Error('Bộ chọn không hợp lệ');
      state.options = data; $('ship-policy').textContent = data.reservationPolicy;
      $('ship-warehouse').innerHTML = '<option value="">Tất cả kho được giao</option>' + data.warehouses.map(w => `<option value="${esc(w.id)}">${esc(w.code)} · ${esc(w.name)}</option>`).join('');
      state.filters = {}; state.page = 0; $('ship-queue').value = 'READY'; $('ship-keyword').value = ''; controls(); loadList();
      const id = new URLSearchParams(location.search).get('orderId'); if (id && /^\d+$/.test(id)) openDetail(id);
    });
  }
  $('ship-filter').addEventListener('submit', e => { e.preventDefault(); if (state.busy || !canRead()) return;
    state.filters = {queue:$('ship-queue').value, keyword:$('ship-keyword').value.trim()}; if ($('ship-warehouse').value) state.filters.warehouseId = $('ship-warehouse').value;
    state.page = 0; clearDetail(); loadList(); });
  $('ship-list').addEventListener('click', e => {const b=e.target.closest('[data-open]'); if(b && !state.busy) openDetail(b.dataset.open);});
  $('ship-prev').addEventListener('click', () => {if(!state.busy && state.page>0){state.page--;loadList();}});
  $('ship-next').addEventListener('click', () => {if(!state.busy && state.page+1<state.pages){state.page++;loadList();}});
  $('ship-close').addEventListener('click', () => {if(!state.busy)clearDetail();});
  $('ship-detail-reload').addEventListener('click', () => {if(!state.busy && state.detail)openDetail(state.detail.id);});
  $('ship-reload').addEventListener('click', () => {if(!state.busy){clearDetail();initialize();}});
  $('ship-confirm-form').addEventListener('submit', async e => {
    e.preventDefault(); if(state.busy || !canWrite() || !state.detail?.canConfirm || state.uncertain) return;
    message('ship-detail-error');
    const detail = state.detail, items = [];
    for(const i of detail.items){
      const row = [...$('ship-lines').children].find(el => el.dataset.line === String(i.id));
      const field = row.querySelector('[data-quantity]'), raw = field.value, quantity = Number(raw), reason = row.querySelector('[data-reason]').value.trim();
      if(!raw.trim() || !Number.isFinite(quantity) || quantity<0 || quantity>Number(i.remaining) || (raw.includes('.') && raw.split('.')[1].length>state.options.quantityScale)) {message('ship-detail-error','Lượng xuất phải từ 0 đến phần còn nợ, đúng độ chính xác của hệ thống.');field.focus();return;}
      if(quantity<Number(i.remaining) && !reason){message('ship-detail-error','Nhập lý do cho từng dòng xuất thiếu.');row.querySelector('[data-reason]').focus();return;}
      items.push({orderItemId:i.id,quantity:raw,lotNumber:row.querySelector('[data-lot]')?.value || null,reason:reason || null});
    }
    if(!items.some(i=>Number(i.quantity)>0)){message('ship-detail-error','Phải xuất ít nhất một mặt hàng.');return;}
    if(!window.confirm(`Xác nhận xuất kho cho ${detail.orderCode}? Phiếu không sửa/xóa sau xác nhận.`))return;
    const epoch=state.epoch; state.busy=true;controls();
    try{
      const data=await request('/'+detail.id+'/confirm',{method:'POST',body:JSON.stringify({version:detail.version,requestKey:crypto.randomUUID(),note:$('ship-note').value.trim() || null,items})});
      if(epoch!==state.epoch || !canRead())return;
      renderDetail(data);message('ship-detail-error'); if(typeof Toast!=='undefined')Toast.show('Đã xác nhận phiếu xuất kho.','success');loadList();
    }catch(error){if(epoch===state.epoch && canRead()){
      state.uncertain=true;message('ship-detail-error',error.message+' Tải lại đơn để đối chiếu số lượng và các phiếu đã xác nhận.');
    }}finally{state.busy=false;controls();}
  });
  window.addEventListener('auth:permissions-changed', () => {
    state.epoch++; for(const key of state.reads.keys())abort(key); clearDetail(); state.options=null; $('ship-list').replaceChildren(); $('ship-policy').textContent=''; controls(); initialize();
  });
  controls(); initialize();
})();
