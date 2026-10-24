/** S6-08 frontend only: GET real orders; draft forms/images never claim persistence. */
const DeliveryPreview = (() => {
  const allowed=permissions=>permissions('ORDER_VIEW') && permissions('WAREHOUSE_EXPORT');
  function createClient({request,permissions,onChange=()=>{}}){
    const state={rows:[],page:0,pages:0,total:0,loading:false,error:'',filters:{},epoch:0};let read=0;
    const emit=()=>onChange(state),canRead=()=>allowed(permissions);
    function clear(){state.epoch++;read++;Object.assign(state,{rows:[],pages:0,total:0,loading:false,error:''});emit();}
    async function load(page=0,filters=state.filters){
      if(!canRead())return false;
      const epoch=state.epoch,id=++read;Object.assign(state,{rows:[],page,filters:{...filters},loading:true,error:''});emit();
      try{
        const query=new URLSearchParams({page,size:20});if(filters.keyword)query.set('keyword',filters.keyword);
        if(filters.status)query.set('status',filters.status);
        const result=await request('/orders?'+query);
        if(epoch!==state.epoch || id!==read || !canRead())return false;
        if(!Array.isArray(result?.data))throw Error('Danh sách đơn trả về không hợp lệ.');
        state.rows=result.data;state.total=Number.isInteger(result.totalElements)?result.totalElements:result.data.length;
        state.pages=Number.isInteger(result.totalPages)?result.totalPages:(result.data.length?1:0);return true;
      }catch(e){if(epoch===state.epoch && id===read && canRead())state.error=e.message;return false;}
      finally{if(epoch===state.epoch && id===read){state.loading=false;emit();}}
    }
    return {state,canRead,load,clear};
  }
  return {allowed,createClient};
})();
if(typeof module!=='undefined')module.exports=DeliveryPreview;

if(typeof document!=='undefined')(async()=>{
  if(!Auth.requireLogin())return;await Auth.ready;
  const $=id=>document.getElementById(id),permission=p=>Auth.hasPermission(p),canRead=()=>DeliveryPreview.allowed(permission);
  if(!canRead()){location.href='/error?status=403';return;}
  const esc=v=>String(v??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const money=v=>v!=null&&Number.isFinite(Number(v))?Number(v).toLocaleString('vi-VN',{style:'currency',currency:'VND'}):'Chưa có dữ liệu';
  const date=v=>/^\d{4}-\d{2}-\d{2}/.test(v||'')?v.slice(0,10).split('-').reverse().join('/'):'Chưa có dữ liệu';
  const pending=o=>['EXPORTED','SHIPPED'].includes(o.status),delivered=o=>['DELIVERED','CLOSED','COMPLETED'].includes(o.status);
  const note='Chưa có API S6-08. Nội dung và ảnh chỉ xem trước, chưa được lưu; không thay đổi đơn, tồn kho hoặc hóa đơn.';
  let blobUrl=null,imageVersion=0,detailVersion=0,previousFocus=null;
  async function request(path){const response=await Auth.apiFetch(path,{cache:'no-store'}),body=await response.json().catch(()=>null);if(!response.ok||!body?.success)throw Error(body?.message||'Không tải được dữ liệu. Chọn Làm mới để thử lại.');return body;}
  const client=DeliveryPreview.createClient({request,permissions:permission,onChange:render});
  function message(text){$('dc-error').textContent=text||'';$('dc-error').hidden=!text;}
  function imageError(text){$('err-proof-image').textContent=text||'';$('err-proof-image').classList.toggle('dc-field-error--show',!!text);}
  function clearImage(){imageVersion++;if(blobUrl)URL.revokeObjectURL(blobUrl);blobUrl=null;$('confirm-proof-file').value='';$('proof-preview-img').removeAttribute('src');$('img-zoom-fullscreen').removeAttribute('src');$('proof-preview-container').hidden=true;$('proof-dropzone').hidden=false;$('proof-file-name').textContent='';$('proof-file-size').textContent='';$('modal-image-zoom').hidden=true;imageError('');}
  function closeForms(){detailVersion++;clearImage();for(const id of ['modal-confirm-delivery','modal-failed-delivery','modal-proof-detail','modal-failed-detail'])$(id).hidden=true;for(const id of ['form-confirm-delivery','form-failed-delivery'])$(id).reset();previousFocus?.focus?.();}
  function options(rows){
    const select=$('active-warehouse-select'),value=select.value,warehouses=new Map(rows.filter(o=>o.warehouseId!=null).map(o=>[String(o.warehouseId),o.warehouseName||o.warehouseCode||String(o.warehouseId)]));
    select.innerHTML='<option value="">Tất cả kho trên trang này</option>'+[...warehouses].map(([id,name])=>`<option value="${esc(id)}">${esc(name)}</option>`).join('');if(warehouses.has(value))select.value=value;
  }
  function render(){
    const s=client.state,permitted=canRead();if(s.loading||!permitted)closeForms();options(s.rows);
    const warehouse=$('active-warehouse-select').value,rows=s.rows.filter(o=>!warehouse||String(o.warehouseId)===warehouse);
    message(s.error);$('dc-table-meta').textContent=s.loading?'Đang tải đơn…':s.error?'Không tải được danh sách':`${rows.length} đơn trên trang ${s.page+1}; tổng ${s.total} đơn theo bộ lọc máy chủ. Ảnh/chứng từ S6-08 chưa có API.`;
    $('dc-page').textContent=s.pages?`Trang ${s.page+1} / ${s.pages}`:'Không có dữ liệu';
    $('dc-prev').disabled=s.loading||!permitted||s.page===0;$('dc-next').disabled=s.loading||!permitted||s.page+1>=s.pages;
    for(const id of ['dc-btn-refresh','dc-filter-status','dc-search','active-warehouse-select'])$(id).disabled=s.loading||!permitted;
    $('dc-search-clear').hidden=!$('dc-search').value;
    $('stat-pending').textContent=s.loading||s.error?'—':rows.filter(pending).length;$('stat-delivered').textContent=s.loading||s.error?'—':rows.filter(delivered).length;
    $('stat-failed').textContent='—';$('stat-invoice-ready').textContent='—';
    $('dc-orders-tbody').innerHTML=rows.map(o=>`<tr><td><strong>${esc(o.orderCode||o.id)}</strong><small>${esc(o.warehouseName||o.warehouseCode||'Chưa gắn kho')}</small></td><td>${esc(o.dealerName||o.customerName||'Chưa có tên đại lý')}</td><td>Hẹn: ${date(o.expectedDeliveryDate)}<br>Giao: ${date(o.actualDeliveryDate)}</td><td>${money(o.totalAmount)}</td><td>${esc(o.statusDisplayName||o.status)}</td><td>Chưa có API ảnh ký nhận</td><td>Chưa kết nối điều kiện S6-08</td><td>${pending(o)&&permitted?`<button type="button" class="dc-btn dc-btn--secondary dc-btn--sm" data-confirm-order="${esc(o.id)}">Mở form giao nhận</button><button type="button" class="dc-btn dc-btn--secondary dc-btn--sm" data-failed-order="${esc(o.id)}">Mở form giao thất bại</button>`:'Chỉ xem trạng thái đơn'}</td></tr>`).join('')||`<tr><td colspan="8" class="dc-empty">${s.loading?'Đang tải…':s.error?'Không tải được dữ liệu. Chọn Làm mới.':'Không có đơn phù hợp trên trang này.'}</td></tr>`;
    $('btn-submit-confirm').disabled=true;$('btn-submit-failed').disabled=true;
  }
  async function openForm(id,kind,button){
    if(!canRead()||client.state.loading)return;closeForms();const version=++detailVersion,epoch=client.state.epoch;button.disabled=true;message('');
    try{
      const result=await request('/orders/'+encodeURIComponent(id));
      if(version!==detailVersion||epoch!==client.state.epoch||!canRead())return;
      const order=result.data;if(String(order?.id)!==String(id)||!pending(order))throw Error('Đơn không còn ở bước xuất/chờ giao. Hãy tải lại danh sách.');
      const prefix=kind==='confirm'?'summary-confirm-':'summary-failed-';
      $(prefix+'code').textContent=order.orderCode||order.id;$(prefix+'dealer').textContent=order.dealerName||order.customerName||'Chưa có dữ liệu';
      $(prefix+'address').textContent='API đơn hiện chưa trả địa chỉ giao nhận tại đây';$(prefix+'amount').textContent=money(order.totalAmount);
      $('confirm-warehouse-name').value=order.warehouseName||order.warehouseCode||'';
      $('confirm-delivery-time').value='';$('failed-return-time').value='';$('failed-voucher-code').value='Chưa tạo phiếu';
      previousFocus=button;const modal=$(kind==='confirm'?'modal-confirm-delivery':'modal-failed-delivery');modal.hidden=false;modal.setAttribute('aria-modal','true');
      const focus=modal.querySelector('input:not([readonly]):not([disabled]),select:not([disabled]),button:not([disabled])');focus?.focus();
    }catch(e){if(version===detailVersion&&canRead())message(e.message);}finally{button.disabled=false;}
  }
  $('dc-orders-tbody').addEventListener('click',e=>{const confirm=e.target.closest('[data-confirm-order]'),failed=e.target.closest('[data-failed-order]');if(confirm)openForm(confirm.dataset.confirmOrder,'confirm',confirm);else if(failed)openForm(failed.dataset.failedOrder,'failed',failed);});
  $('dc-btn-refresh').addEventListener('click',()=>client.load(client.state.page));
  $('dc-prev').addEventListener('click',()=>{if(client.state.page>0)client.load(client.state.page-1);});$('dc-next').addEventListener('click',()=>{if(client.state.page+1<client.state.pages)client.load(client.state.page+1);});
  const search=()=>client.load(0,{keyword:$('dc-search').value.trim(),status:$('dc-filter-status').value});
  $('dc-filter-status').addEventListener('change',search);$('dc-search').addEventListener('keydown',e=>{if(e.key==='Enter'){e.preventDefault();search();}});
  $('dc-search').addEventListener('input',()=>{$('dc-search-clear').hidden=!$('dc-search').value;});$('dc-search-clear').addEventListener('click',()=>{$('dc-search').value='';search();});
  $('active-warehouse-select').addEventListener('change',render);
  for(const attr of ['data-close-confirm','data-close-failed','data-close-proof','data-close-failed-detail'])document.querySelectorAll('['+attr+']').forEach(b=>b.addEventListener('click',closeForms));
  for(const id of ['form-confirm-delivery','form-failed-delivery'])$(id).addEventListener('submit',e=>{e.preventDefault();message(note);});
  const drop=$('proof-dropzone');drop.tabIndex=0;drop.setAttribute('role','button');drop.setAttribute('aria-label','Chọn ảnh chỉ để xem trước, chưa lưu');
  drop.addEventListener('click',()=>{if(canRead())$('confirm-proof-file').click();});drop.addEventListener('keydown',e=>{if(['Enter',' '].includes(e.key)){e.preventDefault();drop.click();}});
  async function preview(file){
    if(!canRead()||$('modal-confirm-delivery').hidden||!file)return;
    const version=++imageVersion;message('');imageError('');
    if(!['image/png','image/jpeg','image/webp'].includes(file.type)||file.size>10*1024*1024){imageError('Ảnh xem trước phải là JPG/PNG/WEBP, tối đa 10MB. Chưa có tệp nào được lưu.');return;}
    const candidate=URL.createObjectURL(file),image=new Image();image.src=candidate;
    try{await image.decode();if(version!==imageVersion||!canRead()||$('modal-confirm-delivery').hidden){URL.revokeObjectURL(candidate);return;}
      if(blobUrl)URL.revokeObjectURL(blobUrl);blobUrl=candidate;$('proof-preview-img').src=candidate;$('proof-file-name').textContent=file.name;$('proof-file-size').textContent=(file.size/1024).toFixed(1)+' KB — chưa tải lên';$('proof-preview-container').hidden=false;drop.hidden=true;
    }catch{URL.revokeObjectURL(candidate);if(version===imageVersion)imageError('Không đọc được ảnh. Chọn ảnh hợp lệ khác; chưa có tệp nào được lưu.');}
  }
  $('confirm-proof-file').addEventListener('change',e=>preview(e.target.files?.[0]));drop.addEventListener('dragover',e=>e.preventDefault());drop.addEventListener('drop',e=>{e.preventDefault();preview(e.dataTransfer.files?.[0]);});
  $('btn-change-proof').addEventListener('click',()=>$('confirm-proof-file').click());$('btn-remove-proof').addEventListener('click',clearImage);
  $('btn-zoom-proof').addEventListener('click',()=>{if(blobUrl&&canRead()){$('img-zoom-fullscreen').src=blobUrl;$('modal-image-zoom').hidden=false;}});
  $('modal-image-zoom').addEventListener('click',()=>{$('modal-image-zoom').hidden=true;});
  document.addEventListener('keydown',e=>{
    if(e.key==='Escape'){if(!$('modal-image-zoom').hidden)$('modal-image-zoom').hidden=true;else closeForms();}
    if(e.key==='Tab'){
      const modal=['modal-confirm-delivery','modal-failed-delivery'].map($).find(m=>!m.hidden);if(!modal)return;
      const controls=[...modal.querySelectorAll('button:not([disabled]),input:not([disabled]),select:not([disabled]),textarea:not([disabled]),[tabindex="0"]')].filter(el=>el.getClientRects().length);
      const first=controls[0],last=controls.at(-1);if(e.shiftKey&&document.activeElement===first){e.preventDefault();last.focus();}else if(!e.shiftKey&&document.activeElement===last){e.preventDefault();first.focus();}
    }
  });
  for(const id of ['modal-confirm-delivery','modal-failed-delivery'])$(id).addEventListener('click',e=>{if(e.target===$(id))closeForms();});
  $('btn-logout').addEventListener('click',()=>Auth.logout());$('btn-logout-topbar').addEventListener('click',()=>Auth.logout());$('btn-avatar').addEventListener('click',()=>location.href='/profile');
  const overlay=document.createElement('div');overlay.className='sidebar-overlay';document.body.append(overlay);
  const sidebar=open=>{$('sidebar').classList.toggle('sidebar--open',open);overlay.classList.toggle('sidebar-overlay--visible',open);};
  $('sidebar-toggle').addEventListener('click',()=>sidebar(true));$('sidebar-close').addEventListener('click',()=>sidebar(false));overlay.addEventListener('click',()=>sidebar(false));
  function identity(){const user=Auth.getUser();$('user-name').textContent=user.fullName||user.username;$('user-role').textContent=(user.roles||[]).map(MenuConfig.getRoleDisplayName).join(', ');$('user-avatar-fallback').textContent=(user.fullName||user.username||'U').charAt(0);}
  window.addEventListener('auth:permissions-changed',()=>{client.clear();closeForms();if(canRead()){identity();client.load();}});window.addEventListener('pagehide',closeForms);
  identity();await client.load();
})();
