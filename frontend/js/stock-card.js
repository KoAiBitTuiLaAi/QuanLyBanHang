/** Read-only S6-02 client. Stock arithmetic, scope and source validation belong to the server. */
const StockCard=(()=>{
  function createClient({request,allowed,onChange=()=>{}}){
    const state={options:null,filters:{},page:0,size:null,card:null,source:null,loading:false,sourceLoading:false,error:'',sourceError:'',epoch:0};
    let read=0,sourceRead=0;
    const emit=()=>onChange(state);
    function clear(){state.epoch++;read++;sourceRead++;Object.assign(state,{options:null,card:null,source:null,loading:false,sourceLoading:false,error:'',sourceError:''});emit();}
    function closeSource(){sourceRead++;state.source=null;state.sourceError='';state.sourceLoading=false;emit();}
    function validate(filters){
      if(!state.options.products.some(p=>String(p.id)===String(filters.productId)))throw Error('Chọn một sản phẩm trong danh mục.');
      if(filters.warehouseId && !state.options.warehouses.some(w=>String(w.id)===String(filters.warehouseId)))throw Error('Kho không còn trong phạm vi được phân công.');
      for(const key of ['fromDate','toDate'])if(filters[key] && !/^\d{4}-\d{2}-\d{2}$/.test(filters[key]))throw Error('Ngày lọc không hợp lệ.');
      if(filters.fromDate && filters.toDate && filters.fromDate>filters.toDate)throw Error('Từ ngày không được sau đến ngày.');
    }
    async function load(filters=state.filters,page=0,size=state.size){
      if(!allowed() || !state.options)return false;
      const id=++read,epoch=state.epoch;closeSource();Object.assign(state,{filters:{...filters},page,size,card:null,loading:true,error:''});emit();
      try{
        validate(filters);
        if(!Number.isInteger(page)||page<0||!state.options.pageSizes.includes(Number(size)))throw Error('Trang hoặc số dòng không hợp lệ.');
        const query=new URLSearchParams({page,size});
        if(filters.warehouseId)query.set('warehouseId',filters.warehouseId);
        if(filters.fromDate)query.set('fromDate',filters.fromDate+'T00:00:00');
        if(filters.toDate)query.set('toDate',filters.toDate+'T23:59:59.999999999');
        const card=await request('/warehouse/stock-card/'+encodeURIComponent(filters.productId)+'?'+query);
        if(epoch!==state.epoch || id!==read || !allowed())return false;
        if(!Array.isArray(card?.entries)||!Number.isInteger(card.totalElements)||!Number.isInteger(card.currentPage)||!Number.isInteger(card.totalPages))throw Error('Máy chủ trả dữ liệu thẻ kho không hợp lệ.');
        if(String(card.productId)!==String(filters.productId))throw Error('Dữ liệu không đúng sản phẩm đã chọn.');
        state.card=card;state.page=card.currentPage;return true;
      }catch(e){if(epoch===state.epoch && id===read && allowed())state.error=e.message;return false;}
      finally{if(epoch===state.epoch && id===read){state.loading=false;emit();}}
    }
    async function initialize(filters=state.filters,page=state.page,size=state.size){
      clear();if(!allowed())return false;const epoch=state.epoch;state.loading=true;state.filters={...filters};emit();
      try{
        const options=await request('/warehouse/stock-card/options');
        if(epoch!==state.epoch || !allowed())return false;
        if(!Array.isArray(options?.products)||!Array.isArray(options.warehouses)||!Array.isArray(options.pageSizes)||!options.pageSizes.includes(options.defaultPageSize))throw Error('Không tải được bộ chọn thẻ kho.');
        state.options=options;state.size=size==null?options.defaultPageSize:Number(size);state.loading=false;
        if(!options.warehouses.length){state.error='Chưa có kho trong phạm vi. Liên hệ quản trị viên để phân công.';emit();return false;}
        emit();if(filters.productId)return await load(filters,page,state.size);return true;
      }catch(e){if(epoch===state.epoch && allowed()){state.loading=false;state.error=e.message;emit();}return false;}
    }
    async function openSource(entry){
      if(!allowed() || state.loading || !state.card?.entries.includes(entry))return false;
      const id=++sourceRead,epoch=state.epoch;state.source=null;state.sourceError='';state.sourceLoading=true;emit();
      try{
        const query=new URLSearchParams();if(state.filters.warehouseId)query.set('warehouseId',state.filters.warehouseId);
        const source=await request('/warehouse/stock-card/'+encodeURIComponent(state.filters.productId)+'/sources/'+encodeURIComponent(entry.sourceType)+'/'+encodeURIComponent(entry.sourceId)+'?'+query);
        if(epoch!==state.epoch || id!==sourceRead || !allowed())return false;
        if(!Array.isArray(source?.entries)||String(source.sourceId)!==String(entry.sourceId)||source.sourceType!==entry.sourceType)throw Error('Chứng từ trả về không hợp lệ.');
        state.source=source;return true;
      }catch(e){if(epoch===state.epoch && id===sourceRead && allowed())state.sourceError=e.message;return false;}
      finally{if(epoch===state.epoch && id===sourceRead){state.sourceLoading=false;emit();}}
    }
    return {state,initialize,load,openSource,closeSource,clear};
  }
  return {createClient};
})();
if(typeof module!=='undefined')module.exports=StockCard;
if(typeof document!=='undefined')(async()=>{
  if(!Auth.requireLogin())return;await Auth.ready;
  if(!Auth.hasPermission('WAREHOUSE_VIEW')){location.href='/error?status=403';return;}
  const $=id=>document.getElementById(id),esc=v=>String(v??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const number=v=>v==null?'—':Number(v).toLocaleString('vi-VN',{maximumFractionDigits:4});
  const time=v=>/^\d{4}-\d{2}-\d{2}T/.test(v||'')?v.slice(8,10)+'/'+v.slice(5,7)+'/'+v.slice(0,4)+' '+v.slice(11,19):'Chưa có thời điểm';
  let optionsVersion=null;
  const allowed=()=>Auth.hasPermission('WAREHOUSE_VIEW');
  const client=StockCard.createClient({allowed,request:async path=>{
    const res=await Auth.apiFetch(path,{cache:'no-store'}),body=await res.json().catch(()=>null);
    if(!res.ok || !body?.success)throw Error(body?.message||'Không thể tải thẻ kho. Chọn Tải lại để thử lại.');return body.data;
  },onChange:render});
  function message(id,text){$(id).textContent=text||'';$(id).hidden=!text;}
  function formFilters(){return {productId:$('card-product').value,warehouseId:$('card-warehouse').value,fromDate:$('card-from').value,toDate:$('card-to').value};}
  function sourceUrl(row){return '/warehouse/stock-card?'+new URLSearchParams({...client.state.filters,page:client.state.page,size:client.state.size,sourceType:row.sourceType,sourceId:row.sourceId});}
  function render(){
    const s=client.state,blocked=s.loading||!s.options||!allowed();
    for(const id of ['card-product','card-warehouse','card-from','card-to','card-apply','card-reset','card-size'])$(id).disabled=blocked;
    $('card-reload').disabled=s.loading||!allowed();$('card-region').setAttribute('aria-busy',String(s.loading));
    message('card-error',s.error);
    if(!s.options){optionsVersion=null;for(const id of ['card-product','card-warehouse','card-size'])$(id).innerHTML='<option value="">Đang tải…</option>';}
    else if(s.options!==optionsVersion){
      optionsVersion=s.options;
      for(const [id,rows,label,value] of [['card-product',s.options.products,'Chọn sản phẩm để xem thẻ kho',s.filters.productId],['card-warehouse',s.options.warehouses,'Tất cả kho trong phạm vi',s.filters.warehouseId]]){
        $(id).innerHTML=`<option value="">${label}</option>`+rows.map(r=>`<option value="${esc(r.id)}">${esc(r.code)} · ${esc(r.name)}</option>`).join('');$(id).value=value||'';
      }
      $('card-size').innerHTML=s.options.pageSizes.map(size=>`<option value="${esc(size)}">${esc(size)}</option>`).join('');$('card-size').value=s.size;
      $('card-from').value=s.filters.fromDate||'';$('card-to').value=s.filters.toDate||'';
    }
    const c=s.card;
    $('card-title').textContent=c?c.productCode+' · '+c.productName:'Chọn một sản phẩm';$('card-unit').textContent=c?'Đơn vị cơ sở: '+c.baseUnit:'';
    $('card-count').textContent=s.loading?'Đang tải…':c?c.totalElements+' phát sinh':'';
    message('card-opening',c?'Tồn trước dòng đầu trang: '+number(c.openingBalance??0)+' '+c.baseUnit:'');
    $('card-rows').innerHTML=c?.entries.map((r,i)=>`<tr><td>${esc(r.rowNumber)}</td><td>${esc(time(r.eventTime))}</td><td><strong>${esc(r.eventTypeLabel)}</strong><small><a class="card-source-link" href="${esc(sourceUrl(r))}" data-card-source="${i}">${esc(r.sourceCode)}</a></small></td><td>${esc(r.warehouseName)}<small>${esc(r.warehouseCode)}</small></td><td>${number(r.displayQuantity)} ${esc(r.displayUnit)}</td><td class="stock-number ${Number(r.baseQuantityChange)<0?'card-minus':'card-plus'}">${Number(r.baseQuantityChange)>0?'+':''}${number(r.baseQuantityChange)} ${esc(r.baseUnit)}</td><td class="stock-number"><strong>${number(r.runningBaseBalance)}</strong> ${esc(r.baseUnit)}</td><td>${esc(r.note||'—')}</td></tr>`).join('')||`<tr><td colspan="8" class="stock-empty">${s.loading?'Đang tải phát sinh…':s.error?'Không tải được thẻ kho. Chọn Tải lại để thử lại.':c?'Không có phát sinh trong khoảng lọc.':'Chọn sản phẩm và nhấn Xem thẻ kho.'}</td></tr>`;
    $('card-page').textContent=c?`Trang ${c.currentPage+1} / ${c.totalPages}`:'';
    $('card-prev').disabled=blocked||!c||c.currentPage<=0;$('card-next').disabled=blocked||!c||c.currentPage+1>=c.totalPages;
    message('card-source-error',s.sourceError);
    $('card-source-title').textContent=s.source?s.source.sourceCode:'Chứng từ gốc';
    $('card-source-content').innerHTML=s.sourceLoading?'Đang tải chứng từ…':s.source?`<p>${esc(s.source.productCode)} · ${esc(s.source.productName)}</p>`+s.source.entries.map(r=>`<section class="card-source-entry"><strong>${esc(r.eventTypeLabel)}</strong><p>${esc(time(r.eventTime))} · ${esc(r.warehouseCode)} · ${esc(r.warehouseName)}</p><p>Số lượng chứng từ: ${number(r.displayQuantity)} ${esc(r.displayUnit)}</p><p>Biến động: ${number(r.baseQuantityChange)} ${esc(r.baseUnit)} · Tồn sau: ${number(r.runningBaseBalance)} ${esc(r.baseUnit)}</p><p>${esc(r.note||'')}</p></section>`).join(''):'';
  }
  function updateUrl(){const s=client.state;history.replaceState(history.state,'',location.pathname+'?'+new URLSearchParams({...s.filters,page:s.page,size:s.size}));}
  async function load(f,p=0,size=client.state.size){$('card-source-dialog').close();await client.load(f,p,size);updateUrl();}
  $('card-filter').addEventListener('submit',e=>{e.preventDefault();load(formFilters());});
  $('card-reset').addEventListener('click',()=>{for(const id of ['card-warehouse','card-from','card-to'])$(id).value='';load(formFilters());});
  $('card-prev').addEventListener('click',()=>load(client.state.filters,client.state.page-1));$('card-next').addEventListener('click',()=>load(client.state.filters,client.state.page+1));
  $('card-size').addEventListener('change',()=>load(client.state.filters,0,Number($('card-size').value)));
  $('card-reload').addEventListener('click',()=>client.initialize(client.state.filters,client.state.page,client.state.size));
  $('card-rows').addEventListener('click',e=>{const a=e.target.closest('[data-card-source]');if(!a||e.ctrlKey||e.metaKey||e.shiftKey)return;e.preventDefault();const row=client.state.card?.entries[Number(a.dataset.cardSource)];if(row){$('card-source-dialog').showModal();client.openSource(row);}});
  $('card-source-close').addEventListener('click',()=>{$('card-source-dialog').close();client.closeSource();});$('card-source-dialog').addEventListener('cancel',()=>client.closeSource());
  window.addEventListener('auth:permissions-changed',()=>{$('card-source-dialog').close();client.clear();if(allowed())client.initialize();});
  const q=new URLSearchParams(location.search),f=Object.fromEntries(['productId','warehouseId','fromDate','toDate'].map(key=>[key,q.get(key)||'']));
  await client.initialize(f,Number(q.get('page')||0),q.get('size')?Number(q.get('size')):null);
  if(q.get('sourceType') && q.get('sourceId') && client.state.card){
    // Deep links may refer to a source outside the current history page; the API rechecks SKU/scope.
    const row=client.state.card.entries.find(r=>r.sourceType===q.get('sourceType') && String(r.sourceId)===q.get('sourceId'));
    if(row){$('card-source-dialog').showModal();await client.openSource(row);}
  }
})();
