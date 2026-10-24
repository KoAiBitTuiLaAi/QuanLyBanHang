/* S8-05: a read-only dashboard. One initial API provides all panels. */
(async () => {
  if (!Auth.requireLogin()) return;
  await Auth.ready;
  const $=id=>document.getElementById(id), U=OperationsDashboardUtils, esc=U.escape;
  const allowed=()=>Auth.hasPermission('DASHBOARD_VIEW') && MenuConfig.getMenuForUser(Auth.getUser()).some(item=>item.id==='operations-dashboard');
  const state={page:0,size:20,pages:0,total:0,snapshot:null};
  const requests=new Map();
  let epoch=0,pageGeneration=0,loading=false,pageLoading=false,hasLoaded=false,navigationAttempt=true;
  function message(id,text='') { $(id).textContent=text;$(id).hidden=!text; }
  const stopped=()=>Object.assign(Error('Phản hồi đã hết hiệu lực.'),{name:'AbortError'});
  function abort(slot) { requests.get(slot)?.abort();requests.delete(slot); }
  async function request(slot,path) {
    if (!allowed()) throw Error('Bạn không có quyền xem dashboard điều hành.');
    abort(slot);const controller=new AbortController(),version=epoch;requests.set(slot,controller);
    const valid=()=>epoch===version&&requests.get(slot)===controller&&allowed();
    try {
      const response=await Auth.apiFetch('/reports/dashboard'+path,{signal:controller.signal,cache:'no-store'});
      if (!valid()) throw stopped();
      const text=await response.text();if(!valid())throw stopped();
      let payload;try{payload=U.parse(text);}catch{throw Error('Máy chủ trả dashboard không hợp lệ.');}
      if(!payload||typeof payload!=='object')throw Error('Máy chủ trả dashboard không hợp lệ.');
      if(!response.ok||payload.success!==true)throw Object.assign(Error(payload.message||'Không tải được dashboard. Bấm Tải lại để thử lại.'),{status:response.status});
      return payload.data;
    }catch(error){if(!valid())throw stopped();throw error;}
  }
  function actions() {
    $('ops-workspace').hidden=!allowed();$('ops-access').hidden=allowed();$('ops-reload').disabled=!allowed();
    $('ops-prev').disabled=!allowed()||loading||pageLoading||!hasLoaded||state.page===0;
    $('ops-next').disabled=!allowed()||loading||pageLoading||!hasLoaded||state.page+1>=state.pages;
    $('ops-size').disabled=!allowed()||loading||pageLoading||!hasLoaded;
    $('ops-workspace').setAttribute('aria-busy',String(loading));
  }
  function clearSensitive() {
    state.snapshot=null;state.page=0;state.pages=0;state.total=0;hasLoaded=false;
    for(const id of ['ops-today-sales','ops-month-sales','ops-pending-approval','ops-pending-export','ops-overdue-debt','ops-low-stock'])$(id).textContent='—';
    for(const id of ['ops-daily-chart','ops-daily-table','ops-territory-chart','ops-dealers'])$(id).replaceChildren();
    for(const id of ['ops-snapshot','ops-performance','ops-page','ops-dealer-count'])$(id).textContent='';
    $('ops-today-label').textContent='Theo ngày máy chủ';$('ops-month-label').textContent='Theo tháng máy chủ';
    $('ops-daily-description').textContent='Các ngày trong tháng do máy chủ cung cấp.';
    $('ops-daily-empty').hidden=true;$('ops-performance').className='';message('ops-page-error');
  }
  function daily(data) {
    const rows=data.dailySales,values=rows.map(row=>U.cents(row.sales)),maximum=values.reduce((a,b)=>a>b?a:b,0n);
    const left=82,right=700,top=24,bottom=232,width=right-left,height=bottom-top;
    const coords=values.map((value,index)=>({x:left+index*width/Math.max(1,rows.length-1),y:bottom-U.ratio(value,maximum)*height}));
    const ticks=[0n,maximum/2n,maximum].filter((value,index,array)=>array.indexOf(value)===index);
    const grid=ticks.map(value=>{const y=bottom-U.ratio(value,maximum)*height;return `<line class="ops-grid" x1="${left}" x2="${right}" y1="${y}" y2="${y}"/><text class="ops-axis" x="${left-10}" y="${y+4}" text-anchor="end">${esc(U.compact(U.decimalFromCents(value)))}</text>`;}).join('');
    const labels=rows.map((row,index)=>index%5===0||index===rows.length-1?`<text class="ops-axis" x="${coords[index].x}" y="258" text-anchor="middle">${esc(row.date.slice(8))}</text>`:'').join('');
    const points=rows.map((row,index)=>`<circle class="ops-point${row.date>data.date?' ops-future':''}" cx="${coords[index].x}" cy="${coords[index].y}" r="3.5" tabindex="0" role="img" aria-label="${esc(U.dateLabel(row.date)+': '+U.money(row.sales))}"><title>${esc(U.dateLabel(row.date)+' — '+U.money(row.sales))}</title></circle>`).join('');
    $('ops-daily-chart').innerHTML=`<svg viewBox="0 0 720 280" role="img" aria-labelledby="ops-svg-title ops-svg-description"><title id="ops-svg-title">Doanh số từng ngày tháng ${esc(data.month)}</title><desc id="ops-svg-description">Đơn vị VND. Có ${rows.length} ngày do máy chủ cung cấp. Xem bảng số liệu ngay dưới biểu đồ để đọc chính xác.</desc>${grid}<polyline class="ops-line" points="${coords.map(point=>point.x+','+point.y).join(' ')}"/>${points}${labels}<text class="ops-axis" x="15" y="15">VND</text></svg>`;
    $('ops-daily-chart').setAttribute('role','region');$('ops-daily-chart').setAttribute('aria-label','Biểu đồ ngày, cuộn ngang trên màn hình nhỏ');$('ops-daily-chart').tabIndex=0;
    $('ops-daily-table').innerHTML=rows.map(row=>`<tr><td>${esc(U.dateLabel(row.date))}${row.date>data.date?'<small>Ngày sau thời điểm chụp; giá trị từ API</small>':''}</td><td class="ops-money">${esc(U.money(row.sales))}</td></tr>`).join('');
    $('ops-daily-description').textContent=`Tháng ${data.month.slice(5)}/${data.month.slice(0,4)} — đủ ${rows.length} ngày; ngày sau ${U.dateLabel(data.date)} hiển thị theo API, không suy đoán doanh số.`;
    $('ops-daily-empty').hidden=maximum!==0n;
  }
  function territories(data) {
    const values=data.territorySales.map(row=>U.cents(row.sales)),max=values.reduce((a,b)=>a>b?a:b,0n);
    $('ops-territory-chart').innerHTML=data.territorySales.length?data.territorySales.map((row,index)=>`<div class="ops-territory"><header><strong>${esc(row.territoryName)}</strong><span>${esc(U.money(row.sales))}</span></header><div class="ops-track" aria-hidden="true"><div class="ops-bar" style="width:${U.ratio(values[index],max)*100}%"></div></div></div>`).join(''):'<p class="ops-muted">Chưa có doanh số theo khu vực trong tháng này.</p>';
  }
  function dealers(rows) {
    $('ops-dealers').innerHTML=rows.length?rows.map(row=>`<tr><td>${esc(row.dealerName)}<small>${esc(row.dealerCode)}</small></td><td>${esc(row.territoryName||'Chưa phân khu vực')}</td><td class="ops-money">${esc(U.money(row.currentDebt))}</td><td class="ops-money">${esc(U.money(row.overdueAmount))}</td><td>${esc(U.count(row.maxOverdueDays))} ngày</td></tr>`).join(''):'<tr><td colspan="5">Không có đại lý quá hạn công nợ tại lần đọc dữ liệu này.</td></tr>';
    $('ops-dealer-count').textContent=`${U.count(state.total)} đại lý quá hạn`;
    $('ops-page').textContent=state.pages?`Trang ${state.page+1}/${state.pages}`:'0 trang';actions();
  }
  function overview(data) {
    const metrics=data.metrics;
    for(const [id,key]of [['ops-today-sales','todaySales'],['ops-month-sales','monthSales'],['ops-overdue-debt','overdueDebt']])$(id).textContent=U.money(metrics[key]);
    for(const [id,key]of [['ops-pending-approval','pendingApprovalOrders'],['ops-pending-export','pendingExportOrders'],['ops-low-stock','belowMinimumSkus']])$(id).textContent=U.count(metrics[key]);
    $('ops-today-label').textContent=U.dateLabel(data.date);$('ops-month-label').textContent=`Tháng ${data.month.slice(5)}/${data.month.slice(0,4)}`;
    const timestamp=new Intl.DateTimeFormat('vi-VN',{timeZone:data.timezone,dateStyle:'short',timeStyle:'medium'}).format(new Date(data.generatedAt));
    $('ops-snapshot').textContent=`Dữ liệu chụp lúc ${timestamp} (${data.timezone}).`;
    daily(data);territories(data);
    state.page=0;state.total=data.totalOverdueDealers;state.pages=Math.ceil(state.total/state.size);state.snapshot=data;hasLoaded=true;
    dealers(data.overdueDealers);
  }
  async function load() {
    if(!allowed()){clearSensitive();actions();return;}
    const started=performance.now(),initial=navigationAttempt;navigationAttempt=false;
    epoch++;for(const controller of requests.values())controller.abort();requests.clear();pageGeneration++;
    const version=epoch;clearSensitive();loading=true;pageLoading=false;actions();message('ops-error');
    $('ops-snapshot').textContent='Đang tải toàn bộ chỉ số, biểu đồ và đại lý quá hạn…';
    $('ops-dealers').innerHTML='<tr><td colspan="5">Đang tải đại lý quá hạn…</td></tr>';
    try {
      const data=U.overview(await request('overview','?overdueLimit='+state.size),state.size);
      overview(data);
      // Include two animation frames so the timing ends after rendering, not just JSON parsing.
      await new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)));
      if(version!==epoch||!allowed())return;
      const elapsed=performance.now()-started,navigation=performance.now();
      const full=initial?navigation:elapsed;
      $('ops-performance').textContent=`${initial?'Mở trang → dữ liệu sẵn sàng':'Tải lại API và hiển thị'}: ${(full/1000).toLocaleString('vi-VN',{maximumFractionDigits:2})} giây${full>=2000?' — vượt mốc 2 giây.':''}`;
      $('ops-performance').className=full<2000?'ops-fast':'ops-slow';
      window.dispatchEvent(new CustomEvent('operations-dashboard:ready',{detail:{elapsedMs:elapsed,navigationMs:navigation,initial,withinTwoSeconds:full<2000}}));
    }catch(error){
      if(error.name!=='AbortError') {clearSensitive();message('ops-error',error.message);$('ops-snapshot').textContent='Chưa tải được dữ liệu. Không có số liệu để kết luận.';}
    }finally{if(version===epoch){loading=false;actions();}}
  }
  async function loadPage() {
    if(!allowed()||!hasLoaded||loading)return;
    const generation=++pageGeneration;pageLoading=true;actions();message('ops-page-error');
    $('ops-dealers').innerHTML='<tr><td colspan="5">Đang tải trang đại lý quá hạn…</td></tr>';$('ops-page').textContent='';
    try {
      const data=U.page(await request('dealers','/overdue-dealers?'+new URLSearchParams({page:state.page,size:state.size})),state.page,state.size);
      state.total=data.totalElements;state.pages=data.totalPages;
      if(state.page>0&&state.page>=state.pages){state.page=Math.max(0,state.pages-1);if(state.pages)return loadPage();}
      dealers(data.content);
    }catch(error){if(error.name!=='AbortError'){$('ops-dealers').innerHTML='<tr><td colspan="5">Không tải được trang đại lý. Bấm Tải lại dữ liệu để thử lại.</td></tr>';message('ops-page-error',error.message);}}
    finally{if(generation===pageGeneration){pageLoading=false;actions();}}
  }
  $('ops-reload').addEventListener('click',load);
  $('ops-prev').addEventListener('click',()=>{if(!loading&&!pageLoading&&state.page>0){state.page--;loadPage();}});
  $('ops-next').addEventListener('click',()=>{if(!loading&&!pageLoading&&state.page+1<state.pages){state.page++;loadPage();}});
  $('ops-size').addEventListener('change',()=>{const size=Number($('ops-size').value);if(!allowed()||loading||pageLoading||![10,20,50,100].includes(size))return;state.size=size;state.page=0;loadPage();});
  window.addEventListener('auth:permissions-changed',()=>{
    epoch++;pageGeneration++;for(const controller of requests.values())controller.abort();requests.clear();loading=false;pageLoading=false;clearSensitive();message('ops-error');actions();if(allowed())load();
  });
  window.addEventListener('pagehide',()=>{epoch++;for(const controller of requests.values())controller.abort();clearSensitive();});
  window.addEventListener('pageshow',event=>{if(event.persisted&&allowed())load();});
  actions();if(allowed())load();
})();
