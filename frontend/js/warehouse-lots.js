/** S6-01: server-backed lot reads/settings/exports. Dates, stock and permissions are authoritative on the API. */
const WarehouseLots = (() => {
  function createClient({request, permissions, onChange = () => {}}) {
    const state = {options:null, rows:[], alerts:null, filters:{}, loading:false, busy:false, error:'', formError:'', notice:'', epoch:0};
    let readId=0;
    const allowed=()=>permissions('WAREHOUSE_VIEW','WAREHOUSE_MANAGE');
    const configure=()=>allowed() && !!state.options?.canConfigure && permissions('PRODUCT_EDIT','WAREHOUSE_MANAGE');
    const exportAllowed=()=>allowed() && !!state.options?.canExport && permissions('WAREHOUSE_EXPORT');
    const overrideAllowed=()=>exportAllowed() && !!state.options?.canOverrideExpiredLot && permissions('WAREHOUSE_APPROVE','WAREHOUSE_MANAGE');
    const expired=row=>row?.isExpired===true || row?.status==='EXPIRED';
    const emit=()=>onChange(state);
    function invalidate() {
      state.epoch++;readId++;state.options=null;state.rows=[];state.alerts=null;state.loading=false;state.error='';state.formError='';state.notice='';emit();
    }
    function validateFilters(filters) {
      if(filters.warehouseId && !state.options.warehouses.some(w=>String(w.id)===String(filters.warehouseId)))throw new Error('Kho không còn trong phạm vi được phân công.');
      if(filters.productId && !state.options.products.some(p=>String(p.productId)===String(filters.productId)))throw new Error('SKU không còn trong danh mục.');
      if(filters.status && !state.options.statuses.some(s=>s.value===filters.status))throw new Error('Trạng thái hạn sử dụng không hợp lệ.');
      if(filters.thresholdDays && (!Number.isInteger(Number(filters.thresholdDays)) || Number(filters.thresholdDays)<state.options.minimumThresholdDays || Number(filters.thresholdDays)>state.options.maximumThresholdDays))throw new Error('Ngưỡng xem nhanh phải là số ngày nguyên hợp lệ.');
    }
    async function load(filters=state.filters) {
      if(!allowed() || !state.options || state.busy)return false;
      const id=++readId, epoch=state.epoch;
      state.filters={...filters};state.loading=true;state.error='';state.rows=[];state.alerts=null;emit();
      try {
        validateFilters(filters);
        const params=new URLSearchParams(Object.entries(filters).filter(([,v])=>v!=='' && v!=null));
        const alerts=new URLSearchParams([...params].filter(([key])=>['warehouseId','productId','thresholdDays'].includes(key)));
        const [rows,summary]=await Promise.all([request('/warehouse/inventory/lots?'+params),request('/warehouse/lot-expiry-alerts?'+alerts)]);
        if(epoch!==state.epoch || id!==readId || !allowed())return false;
        if(!Array.isArray(rows) || !summary || !Number.isInteger(summary.expiredCount) || !Number.isInteger(summary.expiringSoonCount))throw new Error('Máy chủ trả dữ liệu lô không hợp lệ.');
        state.rows=rows;state.alerts=summary;return true;
      } catch(e){if(epoch===state.epoch && id===readId && allowed())state.error=e.message;return false;}
      finally {if(epoch===state.epoch && id===readId){state.loading=false;emit();}}
    }
    async function initialize(filters=state.filters) {
      if(!allowed())return false;
      const epoch=++state.epoch, id=++readId;
      state.options=null;state.rows=[];state.alerts=null;state.loading=true;state.error='';state.filters={...filters};emit();
      try {
        const options=await request('/warehouse/lots/options');
        if(epoch!==state.epoch || id!==readId || !allowed())return false;
        if(!options || !Array.isArray(options.warehouses) || !Array.isArray(options.products) || !Array.isArray(options.statuses)
            || !Number.isInteger(options.minimumThresholdDays) || !Number.isInteger(options.maximumThresholdDays))throw new Error('Không tải được bộ chọn quản lý lô.');
        state.options=options;state.loading=false;
        if(!options.warehouses.length){state.error='Chưa có kho trong phạm vi. Liên hệ quản trị viên để phân công kho.';emit();return false;}
        return await load(filters);
      } catch(e){if(epoch===state.epoch && id===readId && allowed()){state.error=e.message;state.loading=false;emit();}return false;}
    }
    async function write(path,body,kind) {
      if(state.busy || state.loading)return false;
      const epoch=state.epoch;state.busy=true;state.formError='';state.notice='';emit();
      try {
        const result=await request(path,{method:kind==='config'?'PATCH':'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});
        if(epoch!==state.epoch || !allowed() || (kind==='config'?!configure():!exportAllowed()))return false;
        state.notice=kind==='config'?'Đã lưu cấu hình quản lý lô.':'Đã ghi sổ phiếu xuất '+(result?.voucherCode || '')+'.';
        return result || true;
      } catch(e){if(epoch===state.epoch && allowed())state.formError=e.message;return false;}
      finally {state.busy=false;emit();}
    }
    async function saveSettings(productId,enabled,days) {
      if(!configure() || state.busy || state.loading)return false;
      const product=state.options.products.find(p=>String(p.productId)===String(productId));
      if(!product)return false;
      if(days==='' || !Number.isInteger(Number(days)) || Number(days)<state.options.minimumThresholdDays || Number(days)>state.options.maximumThresholdDays){state.formError='Nhập ngưỡng cảnh báo là số ngày nguyên hợp lệ.';emit();return false;}
      return write('/products/'+encodeURIComponent(productId)+'/manage-lot',{manageLot:!!enabled,expiryWarningDays:Number(days)},'config');
    }
    async function exportLot(lotId,quantity,reason,confirmed) {
      if(!exportAllowed() || state.busy || state.loading)return false;
      const row=state.rows.find(r=>String(r.id)===String(lotId));
      if(!row || !row.expiryDate || !(Number(row.baseQuantity)>0))return false;
      const clean=String(reason||'').trim();
      let error='';
      if(quantity==='' || !Number.isFinite(Number(quantity)) || Number(quantity)<=0 || Number(quantity)>Number(row.baseQuantity))error='Nhập số lượng lớn hơn 0 và không vượt tồn lô đang hiển thị.';
      if(clean.length>255)error='Lý do không được vượt quá 255 ký tự.';
      if(expired(row) && (!overrideAllowed() || !confirmed || !clean))error='Lô đã quá hạn: cần quyền ghi đè, đánh dấu xác nhận và nhập lý do.';
      if(error){state.formError=error;emit();return false;}
      const overdue=expired(row);
      return write('/warehouse/export',{warehouseId:row.warehouseId,overrideExpiredLot:overdue,expiredLotOverrideReason:overdue?clean:null,
        items:[{productId:row.productId,lotNumber:row.lotNumber,unit:row.baseUnit,quantity:String(quantity),overrideReason:clean||null,
          overrideExpiredLot:overdue,expiredLotOverrideReason:overdue?clean:null}]},'export');
    }
    return {state,allowed,configure,exportAllowed,overrideAllowed,expired,initialize,load,saveSettings,exportLot,invalidate};
  }
  return {createClient};
})();
if(typeof module!=='undefined')module.exports=WarehouseLots;

if(typeof document!=='undefined') (async()=>{
  if(!Auth.requireLogin())return;
  await Auth.ready;
  if(!Auth.hasAnyPermission('WAREHOUSE_VIEW','WAREHOUSE_MANAGE')){location.href='/error?status=403';return;}
  const $=id=>document.getElementById(id);
  const esc=v=>String(v??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const date=value=>/^\d{4}-\d{2}-\d{2}$/.test(value||'')?value.split('-').reverse().join('/'):'Chưa khai báo';
  const qty=value=>Number(value||0).toLocaleString('vi-VN',{maximumFractionDigits:4});
  let page=0, activeConfig=null, activeExport=null, optionsVersion=null;
  const pageSize=20;
  const client=WarehouseLots.createClient({permissions:(...p)=>Auth.hasAnyPermission(...p),request:async(path,options)=>{
    const response=await Auth.apiFetch(path,options),result=await response.json().catch(()=>null);
    if(!response.ok || !result?.success)throw new Error(result?.message || 'Không thể tải dữ liệu từ máy chủ.');return result.data;
  },onChange:render});
  function message(id,text){$(id).textContent=text||'';$(id).hidden=!text;}
  function filters(){return Object.fromEntries(Object.entries({warehouseId:'lot-warehouse',productId:'lot-product',status:'lot-status',keyword:'lot-keyword',thresholdDays:'lot-threshold'}).map(([key,id])=>[key,$(id).value.trim()]));}
  function select(id,rows,value,label){$(id).innerHTML=`<option value="">${label}</option>`+rows.map(row=>`<option value="${esc(row.value)}">${esc(row.label)}</option>`).join('');$(id).value=value||'';}
  function render(){
    const s=client.state, ready=!!s.options, blocked=s.loading||s.busy||!client.allowed();
    if(!ready){
      optionsVersion=null;
      for(const id of ['lot-warehouse','lot-product','lot-status'])$(id).innerHTML='<option value="">Đang tải…</option>';
    }
    message('lot-error',s.error);message('lot-notice',s.notice);message('lot-config-error',activeConfig?s.formError:'');message('lot-export-error',activeExport?s.formError:'');
    for(const id of ['lot-reload','lot-apply','lot-reset','lot-warehouse','lot-product','lot-status','lot-threshold','lot-keyword'])$(id).disabled=blocked||!ready;
    $('lot-reload').disabled=s.busy||!client.allowed();
    $('lot-config-save').disabled=blocked||!client.configure();$('lot-export-save').disabled=blocked||!client.exportAllowed();
    $('lot-config-close').disabled=s.busy;$('lot-export-close').disabled=s.busy;
    for(const id of ['lot-config-enabled','lot-config-days','lot-export-quantity','lot-export-reason','lot-export-confirm'])$(id).disabled=s.busy;
    if(ready && optionsVersion!==s.options){
      optionsVersion=s.options;
      select('lot-warehouse',s.options.warehouses.map(w=>({value:w.id,label:w.code+' · '+w.name})),s.filters.warehouseId,'Tất cả kho trong phạm vi');
      select('lot-product',s.options.products.map(p=>({value:p.productId,label:p.productCode+' · '+p.productName})),s.filters.productId,'Tất cả SKU');
      select('lot-status',s.options.statuses,s.filters.status,'Tất cả trạng thái');
      $('lot-config-days').min=s.options.minimumThresholdDays;$('lot-config-days').max=s.options.maximumThresholdDays;
      $('lot-threshold').min=s.options.minimumThresholdDays;$('lot-threshold').max=s.options.maximumThresholdDays;
      $('lot-keyword').value=s.filters.keyword||'';$('lot-threshold').value=s.filters.thresholdDays||'';
    }
    $('lot-expired-count').textContent=s.alerts?s.alerts.expiredCount:'—';$('lot-soon-count').textContent=s.alerts?s.alerts.expiringSoonCount:'—';
    $('lot-as-of').textContent=ready?'Đối chiếu ngày '+date(s.options.asOfDate):'';
    const pages=Math.ceil(s.rows.length/pageSize);page=Math.max(0,Math.min(page,Math.max(0,pages-1)));
    $('lot-count').textContent=s.loading?'Đang tải…':s.error?'Không tải được dữ liệu':s.rows.length+' lô';
    $('lot-page-info').textContent=pages?`Trang ${page+1} / ${pages}`:'';$('lot-prev').disabled=blocked||page===0;$('lot-next').disabled=blocked||page+1>=pages;
    const labels=Object.fromEntries((s.options?.statuses||[]).map(x=>[x.value,x.label]));
    $('lot-rows').innerHTML=s.rows.slice(page*pageSize,(page+1)*pageSize).map(row=>{
      const canExport=client.exportAllowed() && Number(row.baseQuantity)>0 && !!row.expiryDate && (!client.expired(row)||client.overrideAllowed());
      return `<tr class="lot-row--${esc(['EXPIRED','EXPIRING_SOON','ACTIVE'].includes(row.status)?row.status:'ACTIVE')}"><td><strong>${esc(row.productName)}</strong><small>${esc(row.productCode)}</small></td><td><strong>${esc(row.lotNumber)}</strong><small>${esc(row.warehouseCode)} · ${esc(row.warehouseName)}</small></td><td>${date(row.manufacturingDate)}</td><td>${date(row.expiryDate)}<small>Ngưỡng: ${esc(row.warningThresholdDays)} ngày</small></td><td><span class="lot-state lot-state--${esc(['EXPIRED','EXPIRING_SOON','ACTIVE'].includes(row.status)?row.status:'ACTIVE')}">${esc(labels[row.status]||'Chưa xác định')}</span><small>${row.daysUntilExpiry==null?'Chưa có hạn dùng':Number(row.daysUntilExpiry)<0?'Quá hạn '+esc(Math.abs(row.daysUntilExpiry))+' ngày':'Còn '+esc(row.daysUntilExpiry)+' ngày'}</small></td><td class="stock-number"><strong>${qty(row.baseQuantity)}</strong>${esc(row.baseUnit)}</td><td>${canExport?`<button type="button" class="btn btn--secondary" data-lot-export="${esc(row.id)}" ${blocked?'disabled':''}>${client.expired(row)?'Xuất có ghi đè':'Xuất lô'}</button>`:`<small>${client.expired(row)?'Chặn xuất quá hạn':!row.expiryDate?'Thiếu hạn sử dụng':Number(row.baseQuantity)<=0?'Đã hết lô':'Chỉ xem'}</small>`}</td></tr>`;
    }).join('')||`<tr><td colspan="7" class="stock-empty">${s.loading?'Đang tải lô…':s.error?'Không tải được lô. Chọn Tải lại để thử lại.':'Không có lô phù hợp. Lô được ghi nhận khi xác nhận phiếu nhập hàng có quản lý lô.'}</td></tr>`;
    renderSettings();
  }
  function renderSettings(){
    const search=$('lot-setting-search').value.trim().toLocaleLowerCase('vi');
    const rows=(client.state.options?.products||[]).filter(p=>(p.productCode+' '+p.productName).toLocaleLowerCase('vi').includes(search));
    $('lot-settings-count').textContent=rows.length+' SKU';
    $('lot-settings-rows').innerHTML=rows.map(p=>`<tr><td><strong>${esc(p.productName)}</strong><small>${esc(p.productCode)}</small></td><td>${esc(p.unit)}</td><td>${p.manageLot?'Đang bật':'Đang tắt'}</td><td>${esc(p.effectiveThresholdDays)} ngày</td><td>${client.configure()?`<button type="button" class="btn btn--secondary" data-lot-config="${esc(p.productId)}" ${client.state.loading||client.state.busy?'disabled':''}>Cấu hình</button>`:'<small>Chỉ xem</small>'}</td></tr>`).join('')||'<tr><td colspan="5" class="stock-empty">Không có SKU phù hợp.</td></tr>';
  }
  const lotTabs=[['lot-tab-inventory','lot-inventory-panel'],['lot-tab-settings','lot-settings-panel'],['lot-tab-fefo','lot-fefo-panel']];
  for(const [button,panel] of lotTabs)$(button).addEventListener('click',()=>{
    if($(button).hidden)return;
    for(const [b,p] of lotTabs){$(b).setAttribute('aria-pressed',String(b===button));$(p).hidden=p!==panel;}
    const url=new URL(location.href);url.searchParams.set('tab',button.replace('lot-tab-',''));history.replaceState(history.state,'',url);
  });
  if(new URLSearchParams(location.search).get('tab')==='fefo' && Auth.hasAnyPermission('WAREHOUSE_VIEW','WAREHOUSE_EXPORT')){
    $('lot-tab-fefo').hidden=false;$('lot-tab-fefo').click();
  }
  window.addEventListener('warehouse:stock-changed',()=>{if(client.allowed() && !client.state.busy)client.initialize(client.state.filters);});
  $('lot-filter').addEventListener('submit',async e=>{e.preventDefault();page=0;const f=filters();const url=new URL(location.href);url.search=new URLSearchParams(Object.entries(f).filter(([,v])=>v)).toString();history.replaceState(history.state,'',url);await client.load(f);});
  $('lot-reset').addEventListener('click',()=>{$('lot-filter').reset();page=0;history.replaceState(history.state,'',location.pathname);client.load({});});
  $('lot-reload').addEventListener('click',()=>{if(!client.state.busy)client.initialize(client.state.filters);});
  $('lot-prev').addEventListener('click',()=>{if(page>0){page--;render();}});$('lot-next').addEventListener('click',()=>{page++;render();});
  $('lot-setting-search').addEventListener('input',renderSettings);
  $('lot-settings-rows').addEventListener('click',e=>{
    const button=e.target.closest('[data-lot-config]');if(!button || !client.configure() || client.state.busy)return;
    const product=client.state.options.products.find(p=>String(p.productId)===button.dataset.lotConfig);if(!product)return;
    activeConfig=product;client.state.formError='';$('lot-config-context').textContent=product.productCode+' · '+product.productName;
    $('lot-config-enabled').checked=!!product.manageLot;$('lot-config-days').value=product.effectiveThresholdDays;render();$('lot-config-dialog').showModal();$('lot-config-days').focus();
  });
  $('lot-rows').addEventListener('click',e=>{
    const button=e.target.closest('[data-lot-export]');if(!button || !client.exportAllowed() || client.state.busy)return;
    const row=client.state.rows.find(r=>String(r.id)===button.dataset.lotExport);if(!row || client.expired(row)&&!client.overrideAllowed())return;
    activeExport=row;client.state.formError='';$('lot-export-context').textContent=`${row.productCode} · ${row.productName} — Lô ${row.lotNumber} — ${row.warehouseName} — HSD ${date(row.expiryDate)} — Còn ${qty(row.baseQuantity)} ${row.baseUnit}`;
    $('lot-export-unit').textContent='('+row.baseUnit+')';$('lot-export-quantity').value='';$('lot-export-quantity').max=row.baseQuantity;$('lot-export-reason').value='';$('lot-export-reason').required=client.expired(row);$('lot-export-confirm').checked=false;
    $('lot-export-confirm-label').hidden=!client.expired(row);message('lot-export-warning',client.expired(row)?'Lô này đã quá hạn. Xuất có ghi đè là ngoại lệ có lưu vết; không dùng để giao nhầm hàng cho đại lý.':'');render();$('lot-export-dialog').showModal();$('lot-export-quantity').focus();
  });
  for(const kind of ['config','export']){
    $(`lot-${kind}-close`).addEventListener('click',()=>{if(!client.state.busy){$(`lot-${kind}-dialog`).close();if(kind==='config')activeConfig=null;else activeExport=null;}});
    $(`lot-${kind}-dialog`).addEventListener('cancel',e=>{if(client.state.busy)e.preventDefault();else if(kind==='config')activeConfig=null;else activeExport=null;});
  }
  $('lot-config-form').addEventListener('submit',async e=>{e.preventDefault();if(!activeConfig)return;const epoch=client.state.epoch;
    if(await client.saveSettings(activeConfig.productId,$('lot-config-enabled').checked,$('lot-config-days').value) && epoch===client.state.epoch){$('lot-config-dialog').close();activeConfig=null;await client.initialize();}});
  $('lot-export-form').addEventListener('submit',async e=>{e.preventDefault();if(!activeExport)return;const epoch=client.state.epoch;
    if(await client.exportLot(activeExport.id,$('lot-export-quantity').value,$('lot-export-reason').value,$('lot-export-confirm').checked) && epoch===client.state.epoch){$('lot-export-dialog').close();activeExport=null;await client.initialize();}});
  window.addEventListener('auth:permissions-changed',()=>{
    for(const kind of ['config','export']){
      $(`lot-${kind}-dialog`).close();$(`lot-${kind}-form`).reset();$(`lot-${kind}-context`).textContent='';
    }
    activeConfig=null;activeExport=null;client.invalidate();if(client.allowed())client.initialize();
  });
  const initial=Object.fromEntries([...new URLSearchParams(location.search)].filter(([key])=>['warehouseId','productId','status','keyword','thresholdDays'].includes(key)));
  await client.initialize(initial);
})();
