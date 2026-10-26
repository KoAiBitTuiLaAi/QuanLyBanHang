/** S6-07 monitoring only. Every business metric, status and scope comes from the API. */
(async () => {
  if (!Auth.requireLogin()) return;
  await Auth.ready;
  const $ = id => document.getElementById(id);
  const esc = v => String(v ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const number = v => v == null || !Number.isFinite(Number(v)) ? '—' : new Intl.NumberFormat('vi-VN',{maximumFractionDigits:4}).format(Number(v));
  const money = v => v == null || !Number.isFinite(Number(v)) ? '—' : new Intl.NumberFormat('vi-VN',{style:'currency',currency:'VND',maximumFractionDigits:2}).format(Number(v));
  const time = v => v && Number.isFinite(new Date(v).getTime()) ? new Date(v).toLocaleString('vi-VN') : 'Chưa có dữ liệu';
  const canRead = () => Auth.hasPermission('WAREHOUSE_VIEW') || Auth.hasPermission('WAREHOUSE_EXPORT');
  const state = {epoch:0, serial:new Map(), reads:new Map(), page:0, pages:0, options:null, detailId:null, filters:{}, loading:new Set()};
  const message = (id, value='') => {$(id).textContent=value;$(id).hidden=!value;};
  function abort(key){state.reads.get(key)?.abort();state.serial.set(key,(state.serial.get(key)||0)+1);state.loading.delete(key);}
  function controls(){
    $('trips-prev').disabled=!canRead() || state.loading.has('list') || state.page<=0;
    $('trips-next').disabled=!canRead() || state.loading.has('list') || state.page+1>=state.pages;
    $('trips-filter').querySelectorAll('input,select,button').forEach(el=>el.disabled=!canRead() || !state.options);
    $('trips-refresh').disabled=!canRead();
    $('trips-detail-refresh').disabled=!canRead() || state.detailId==null || state.loading.has('detail');
  }
  async function request(path,signal){
    const response=await Auth.apiFetch('/warehouse/delivery-trips'+path,{method:'GET',cache:'no-store',signal});
    const body=await response.json().catch(()=>null);
    if(!response.ok || !body?.success){const error=new Error(body?.message || 'Không tải được dữ liệu chuyến giao. Hãy thử Tải lại.');error.status=response.status;throw error;}
    return body.data;
  }
  async function read(key,path,apply,errorId){
    abort(key);const serial=state.serial.get(key),epoch=state.epoch,controller=new AbortController();state.reads.set(key,controller);state.loading.add(key);controls();
    try{const data=await request(path,controller.signal);if(epoch===state.epoch && serial===state.serial.get(key) && canRead())apply(data);}
    catch(error){if(error.name!=='AbortError' && epoch===state.epoch && serial===state.serial.get(key) && canRead()){
      message(errorId,error.message);
      if(key==='list'){$('trips-list').textContent='Không tải được danh sách. Chọn Tải lại để thử lại.';$('trips-count').textContent='Chưa tải được dữ liệu';}
    }}finally{if(epoch===state.epoch && serial===state.serial.get(key)){state.loading.delete(key);controls();}}
  }
  const metrics=['trips-outstanding-value','trips-outstanding-count','trips-running-count','trips-road-value','trips-point-count','trips-overdue-count'];
  function clearSummary(){metrics.forEach(id=>$(id).textContent='—');$('trips-closed-alert').hidden=true;$('trips-closed-text').textContent='';$('trips-updated').textContent='';}
  function clearDetail(){abort('detail');state.detailId=null;$('trips-detail').hidden=true;$('trips-detail-title').textContent='';$('trips-detail-meta').textContent='';$('trips-detail-body').replaceChildren();message('trips-detail-error');controls();}
  function summary(){
    clearSummary();message('trips-summary-error');
    const warehouse=state.options?.warehouses.find(w=>String(w.id)===state.filters.warehouseId);
    $('trips-summary-scope').textContent=(warehouse?warehouse.code+' · '+warehouse.name:'Tất cả kho được giao')+' — toàn bộ chuyến trong phạm vi kho, không theo trạng thái/từ khóa/bộ lọc danh sách.';
    return read('summary','/summary'+(state.filters.warehouseId?'?warehouseId='+encodeURIComponent(state.filters.warehouseId):''),d=>{
      const required=['totalOutstandingValue','totalOutstandingTrips','closedOutstandingValue','closedOutstandingTrips','totalRunningTrips','totalValueOnRoad','totalDeliveredPoints','totalRemainingPoints','totalOverdueTrips'];
      if(!d || required.some(k=>d[k]==null || !Number.isFinite(Number(d[k]))))throw Error('Backend chưa trả đủ số liệu theo dõi. Cần cập nhật/khởi động lại backend, không dùng tổng tiền suy đoán.');
      $('trips-outstanding-value').textContent=money(d.totalOutstandingValue);$('trips-outstanding-count').textContent=number(d.totalOutstandingTrips);
      $('trips-running-count').textContent=number(d.totalRunningTrips);$('trips-road-value').textContent=money(d.totalValueOnRoad);
      $('trips-point-count').textContent=number(d.totalDeliveredPoints)+' / '+number(d.totalRemainingPoints);$('trips-overdue-count').textContent=number(d.totalOverdueTrips);
      $('trips-updated').textContent='Cập nhật '+new Date().toLocaleTimeString('vi-VN');
      $('trips-closed-alert').hidden=Number(d.closedOutstandingTrips)<=0;
      $('trips-closed-text').textContent=`Có ${number(d.closedOutstandingTrips)} chuyến đã hoàn tất/hủy nhưng còn ${money(d.closedOutstandingValue)} tiền hàng chưa giao hoặc chưa xác nhận trả kho. Đổi trạng thái chuyến không tự hoàn tồn.`;
    },'trips-summary-error');
  }
  function warning(t){return t.isOverdue===true?`<p class="trips-warning">${esc(t.overdueWarning || 'Chuyến đã quá thời gian dự kiến '+number(t.overdueMinutes)+' phút')}</p>`:'';}
  function list(){
    message('trips-error');$('trips-list').textContent='Đang tải chuyến giao…';$('trips-count').textContent='';$('trips-page').textContent='';state.pages=0;
    const query=new URLSearchParams({...state.filters,page:state.page,size:20});
    return read('list','?'+query,d=>{
      if(!Array.isArray(d?.content) || !Number.isInteger(d.totalPages) || !Number.isInteger(d.totalElements))throw Error('Danh sách phân trang không hợp lệ.');
      state.pages=d.totalPages;$('trips-count').textContent=number(d.totalElements)+' chuyến';$('trips-page').textContent=d.totalPages?`Trang ${state.page+1} / ${d.totalPages}`:'Không có kết quả';
      $('trips-list').innerHTML=d.content.length?d.content.map(t=>`<article class="trips-trip ${t.isOverdue===true?'trips-trip--overdue':''}">
        <header class="trips-heading"><h3>${esc(t.tripCode)}</h3><span class="trips-badge">${esc(t.statusDisplayName || t.status)}</span></header>
        <p class="trips-muted">${esc(t.warehouseCode)} · ${esc(t.warehouseName)} · ${esc(t.driverName)} · ${esc(t.vehiclePlate)}</p>
        ${warning(t)}<dl><div><dt>Đã giao / còn lại / tổng điểm</dt><dd>${number(t.deliveredPoints)} / ${number(t.remainingPoints)} / ${number(t.totalPoints)}</dd></div>
        <div><dt>Tiền hàng còn thuộc chuyến</dt><dd>${money(t.remainingValue)}</dd></div><div><dt>Dự kiến hoàn thành</dt><dd>${esc(time(t.expectedCompletionTime))}</dd></div></dl>
        ${Number(t.totalPoints)>0?`<progress max="${esc(t.totalPoints)}" value="${esc(t.deliveredPoints ?? 0)}" aria-label="Đã giao ${esc(t.deliveredPoints)} trên ${esc(t.totalPoints)} điểm"></progress>`:''}
        ${t.status!=='IN_PROGRESS' && Number(t.remainingPoints)>0?'<p class="trips-warning">Chuyến đã kết thúc nhưng vẫn còn hàng chưa xử lý.</p>':''}
        <div class="trips-actions"><span class="trips-muted">${number(t.failedPoints)} điểm chưa giao được</span><button type="button" class="btn btn--secondary" data-trip="${esc(t.id)}">Xem điểm giao</button></div></article>`).join(''):'<p class="trips-empty">Không có chuyến phù hợp. Thử đổi trạng thái, kho hoặc từ khóa.</p>';
    },'trips-error');
  }
  function stop(s){
    const returned=s.returnedToWarehouse===true;
    const responsibility=returned?(s.returnedAt?'Đã xác nhận trả kho lúc '+time(s.returnedAt):'Đã xác nhận trả kho; chưa có thời gian xác nhận.'):s.status==='DELIVERED'?(s.deliveredAt?'Đã giao lúc '+time(s.deliveredAt):'Đã giao; chưa có thời gian xác nhận.'):'Hàng vẫn thuộc trách nhiệm chuyến; chưa có xác nhận trả kho.';
    return `<article class="trips-stop"><div class="trips-heading"><h3>Điểm ${esc(s.stopOrder)} · ${esc(s.orderCode || s.dealerName || s.receiverName || 'Chưa có tên người nhận')}</h3><span class="trips-badge">${esc(s.statusDisplayName || s.status)}${returned?' · Đã trả kho':''}</span></div>
      <p>${esc(s.deliveryAddress || 'Chưa có địa chỉ')}</p><p class="trips-muted">Người nhận: ${esc(s.receiverName || '—')} · Điện thoại: ${esc(s.receiverPhone || '—')}</p>
      <p>Giá trị điểm giao: <strong>${money(s.deliveryAmount)}</strong></p><p class="${s.status==='FAILED' && !returned?'trips-warning':'trips-muted'}">${esc(responsibility)}</p>
      ${s.failedReason?`<p>Lý do chưa giao được: ${esc(s.failedReason)}</p>`:''}${s.note?`<p>Ghi chú: ${esc(s.note)}</p>`:''}
      ${Array.isArray(s.items) && s.items.length?`<div class="trips-table-scroll" role="region" aria-label="Hàng tại điểm ${esc(s.stopOrder)}" tabindex="0"><table><thead><tr><th>Sản phẩm</th><th>Đơn vị</th><th>Số lượng</th><th>Thành tiền</th></tr></thead><tbody>${s.items.map(i=>`<tr><td>${esc(i.productCode)} · ${esc(i.productName)}</td><td>${esc(i.unit || '—')}</td><td>${number(i.quantity)}</td><td>${money(i.totalPrice)}</td></tr>`).join('')}</tbody></table></div>`:'<p class="trips-muted">Chưa có chi tiết dòng hàng từ hệ thống.</p>'}</article>`;
  }
  function detail(id,focus=false){
    abort('detail');state.detailId=id;$('trips-detail').hidden=false;$('trips-detail-title').textContent='Đang tải chuyến…';$('trips-detail-meta').textContent='';$('trips-detail-body').replaceChildren();message('trips-detail-error');
    return read('detail','/'+encodeURIComponent(id),d=>{
      if(!Array.isArray(d?.stops) || String(d.id)!==String(id))throw Error('Chi tiết chuyến không hợp lệ.');
      $('trips-detail-title').textContent=d.tripCode;$('trips-detail-meta').textContent=[d.warehouseName,d.driverName,d.driverPhone,d.vehiclePlate,d.statusDisplayName].filter(Boolean).join(' · ');
      $('trips-detail-body').innerHTML=warning(d)+`<dl class="trips-stats"><div><dt>Khởi hành</dt><dd>${esc(time(d.departureTime))}</dd></div><div><dt>Dự kiến hoàn thành</dt><dd>${esc(time(d.expectedCompletionTime))}</dd></div><div><dt>Đã giao / còn lại</dt><dd>${number(d.deliveredPoints)} / ${number(d.remainingPoints)}</dd></div><div><dt>Tiền hàng còn thuộc chuyến</dt><dd>${money(d.remainingValue)}</dd></div></dl>
        ${d.status!=='IN_PROGRESS' && Number(d.remainingPoints)>0?'<p class="trips-warning">Chuyến đã kết thúc nhưng hàng chưa giao/nhập trả vẫn còn thuộc trách nhiệm chuyến.</p>':''}
        ${d.note?`<p class="trips-muted">Ghi chú chuyến: ${esc(d.note)}</p>`:''}${d.cancelReason?`<p class="trips-muted">Lý do hủy: ${esc(d.cancelReason)}</p>`:''}
        <div class="trips-stops">${d.stops.length?[...d.stops].sort((a,b)=>Number(a.stopOrder)-Number(b.stopOrder)).map(stop).join(''):'<p class="trips-empty">Chưa có điểm giao.</p>'}</div>`;
      if(focus)$('trips-detail-title').focus();
    },'trips-detail-error');
  }
  function refresh(){if(!canRead() || !state.options)return;summary();list();if(state.detailId!=null)detail(state.detailId);}
  function apply(){
    clearDetail();state.page=0;state.filters={};
    for(const [id,key] of [['trips-warehouse','warehouseId'],['trips-status','status'],['trips-overdue','isOverdue'],['trips-keyword','keyword']]){const value=$(id).value.trim();if(value)state.filters[key]=value;}
    if($('trips-outstanding').checked)state.filters.outstandingOnly='true';refresh();
  }
  function initialize(){
    state.options=null;state.pages=0;abort('list');abort('summary');clearSummary();clearDetail();$('trips-list').replaceChildren();$('trips-count').textContent='';$('trips-page').textContent='';
    if(!canRead()){$('trips-workspace').hidden=true;message('trips-error','Bạn không có quyền xem chuyến giao theo ma trận.');controls();return;}
    $('trips-workspace').hidden=false;message('trips-error');
    return read('options','/options',d=>{
      if(!Array.isArray(d?.warehouses) || !Array.isArray(d.tripStatuses))throw Error('Không tải được bộ lọc chuyến giao.');
      state.options=d;
      $('trips-warehouse').innerHTML='<option value="">Tất cả kho được giao</option>'+d.warehouses.map(w=>`<option value="${esc(w.id)}">${esc(w.code)} · ${esc(w.name)}</option>`).join('');
      $('trips-status').innerHTML='<option value="">Tất cả trạng thái</option>'+d.tripStatuses.map(s=>`<option value="${esc(s.value)}">${esc(s.label)}</option>`).join('');
      $('trips-status').value=d.tripStatuses.some(s=>s.value==='IN_PROGRESS')?'IN_PROGRESS':'';$('trips-overdue').value='';$('trips-keyword').value='';$('trips-outstanding').checked=false;
      apply();const id=new URLSearchParams(location.search).get('tripId');if(id && /^\d+$/.test(id))detail(id,true);
    },'trips-error');
  }
  $('trips-filter').addEventListener('submit',e=>{e.preventDefault();if(canRead() && state.options)apply();});
  $('trips-reset').addEventListener('click',()=>{if(canRead()){clearDetail();initialize();}});
  $('trips-refresh').addEventListener('click',()=>state.options?refresh():initialize());
  $('trips-list').addEventListener('click',e=>{const b=e.target.closest('[data-trip]');if(b && canRead())detail(b.dataset.trip,true);});
  $('trips-detail-close').addEventListener('click',clearDetail);
  $('trips-detail-refresh').addEventListener('click',()=>{if(state.detailId!=null && canRead())detail(state.detailId);});
  $('trips-prev').addEventListener('click',()=>{if(!state.loading.has('list') && state.page>0){state.page--;list();}});
  $('trips-next').addEventListener('click',()=>{if(!state.loading.has('list') && state.page+1<state.pages){state.page++;list();}});
  $('trips-show-outstanding').addEventListener('click',()=>{if(canRead()){$('trips-status').value='';$('trips-overdue').value='';$('trips-keyword').value='';$('trips-outstanding').checked=true;apply();}});
  window.addEventListener('auth:permissions-changed',()=>{
    state.epoch++;for(const key of state.reads.keys())abort(key);state.options=null;state.pages=0;state.filters={};clearSummary();clearDetail();$('trips-list').replaceChildren();$('trips-count').textContent='';$('trips-page').textContent='';controls();initialize();
  });
  document.addEventListener('visibilitychange',()=>{if(!document.hidden)refresh();});
  const timer=setInterval(()=>{if(!document.hidden && !state.loading.size)refresh();},60000);
  window.addEventListener('pagehide',()=>{clearInterval(timer);state.epoch++;for(const key of state.reads.keys())abort(key);});
  controls();initialize();
})();
