/** S6-08: server-backed delivery confirmation. Business drafts remain in memory only. */
const DeliveryConfirmation = (() => {
  const base = '/warehouse/delivery-trips';
  const allowed = permissions => permissions('WAREHOUSE_VIEW', 'WAREHOUSE_EXPORT');
  function createClient({request, permissions, onChange = () => {}}) {
    const state = {options:null, rows:[], trips:[], detail:null, page:0, pages:0, total:0, filters:{}, loading:false,
      detailLoading:false, busy:false, uncertain:false, error:'', detailError:'', notice:'', epoch:0};
    let serial=0, detailSerial=0, writePending=false;
    const emit=()=>onChange(state), canRead=()=>allowed(permissions);
    const canWrite=()=>canRead() && permissions('WAREHOUSE_EXPORT') && state.options?.canManage===true;
    const path=(tripId,stopId)=>base+'/'+encodeURIComponent(tripId)+'/stops/'+encodeURIComponent(stopId);
    function clear() {
      state.epoch++;serial++;detailSerial++;
      Object.assign(state,{options:null,rows:[],trips:[],detail:null,pages:0,total:0,loading:false,detailLoading:false,
        busy:writePending,uncertain:false,error:'',detailError:'',notice:''});emit();
    }
    function close() { detailSerial++;state.detail=null;state.detailLoading=false;state.detailError='';state.uncertain=false;emit(); }
    async function load(page=0,filters=state.filters) {
      if (!canRead() || !state.options || writePending) return false;
      const id=++serial, epoch=state.epoch;close();
      Object.assign(state,{page,filters:{...filters},rows:[],trips:[],loading:true,error:'',notice:''});emit();
      try {
        const query=new URLSearchParams({page,size:20});
        for(const key of ['warehouseId','status','keyword'])if(filters[key])query.set(key,filters[key]);
        const result=await request(base+'?'+query);
        if(epoch!==state.epoch || id!==serial || !canRead())return false;
        if(!Array.isArray(result?.content) || !Number.isInteger(result.totalPages) || !Number.isInteger(result.totalElements))
          throw Error('Danh sách chuyến giao không hợp lệ.');
        state.trips=result.content;state.pages=result.totalPages;state.total=result.totalElements;
        state.rows=result.content.flatMap(trip=>(trip.stops||[]).map(stop=>({trip,stop})));return true;
      } catch(e) {if(epoch===state.epoch && id===serial && canRead())state.error=e.message;return false;}
      finally {if(epoch===state.epoch && id===serial){state.loading=false;emit();}}
    }
    async function initialize() {
      clear();if(!canRead())return false;const epoch=state.epoch;state.loading=true;emit();
      try {
        const options=await request(base+'/options');
        if(epoch!==state.epoch || !canRead())return false;
        if(!Array.isArray(options?.warehouses) || !Array.isArray(options.tripStatuses) || !Array.isArray(options.failureReasons)
          || !Array.isArray(options.proofContentTypes) || !(options.proofMaxBytes>0) || !(options.proofMaxPixels>0))
          throw Error('Không tải được tùy chọn giao nhận.');
        state.options=options;state.loading=false;emit();return writePending?true:await load(0);
      } catch(e) {if(epoch===state.epoch && canRead()){state.error=e.message;state.loading=false;emit();}return false;}
    }
    async function open(tripId,stopId) {
      if(!canRead() || writePending)return false;
      const id=++detailSerial,epoch=state.epoch;state.detail=null;state.detailLoading=true;state.detailError='';emit();
      try {
        const trip=await request(base+'/'+encodeURIComponent(tripId));
        if(epoch!==state.epoch || id!==detailSerial || !canRead())return false;
        const stop=trip?.stops?.find(s=>String(s.id)===String(stopId));
        if(String(trip?.id)!==String(tripId) || !stop || !Array.isArray(stop.items))throw Error('Điểm giao không còn trong chuyến.');
        state.detail={trip,stop};state.uncertain=false;return true;
      } catch(e) {if(epoch===state.epoch && id===detailSerial && canRead()){state.detailError=e.message;state.error=e.message;}return false;}
      finally {if(epoch===state.epoch && id===detailSerial){state.detailLoading=false;emit();}}
    }
    async function write(suffix,options) {
      if(!canWrite() || !state.detail || writePending || state.uncertain)return false;
      const {trip,stop}=state.detail, epoch=state.epoch;
      writePending=true;state.busy=true;state.detailError='';state.notice='';emit();
      try {
        const result=await request(path(trip.id,stop.id)+suffix,options);
        if(epoch!==state.epoch || !canRead())return false;
        const updated=Array.isArray(result?.stops)?result.stops.find(s=>String(s.id)===String(stop.id)):result;
        if(!updated || String(updated.id)!==String(stop.id))throw Error('Đã gửi thao tác nhưng chưa xác định được kết quả. Hãy tải lại điểm giao.');
        state.detail={trip:Array.isArray(result.stops)?result:trip,stop:updated};
        state.rows=state.rows.map(row=>String(row.trip.id)===String(trip.id) && String(row.stop.id)===String(stop.id)
          ? {trip:state.detail.trip,stop:updated}:row);
        state.notice='Đã lưu kết quả giao nhận lên hệ thống.';return true;
      } catch(e) {
        if(epoch===state.epoch && canRead()) {
          state.uncertain=!e.status || e.status>=500;
          state.detailError=state.uncertain?'Chưa rõ kết quả lưu. Tải lại điểm giao để kiểm tra trước khi gửi lại.':e.message;
        }
        return false;
      } finally {writePending=false;state.busy=false;emit();}
    }
    async function confirm(input) {
      if(!canWrite() || !state.detail?.stop.canConfirm || state.uncertain || writePending)return false;
      const file=input.proof, o=state.options, time=Date.parse(input.deliveredAt), start=Date.parse(state.detail.trip.departureTime);
      let error='';
      if(!input.receiverName?.trim() || input.receiverName.trim().length>100)error='Nhập người nhận thực tế, tối đa 100 ký tự.';
      else if(!Number.isFinite(time) || time>Date.now() || (Number.isFinite(start) && time<start))error='Thời điểm giao phải từ lúc xuất phát đến hiện tại.';
      else if(!file || !o.proofContentTypes.includes(file.type) || file.size<=0 || file.size>o.proofMaxBytes)error='Chọn ảnh PNG/JPEG trong giới hạn dung lượng.';
      if(error){state.detailError=error;emit();return false;}
      const form=new FormData();form.set('receiverName',input.receiverName.trim());form.set('deliveredAt',input.deliveredAt);form.set('proof',file);
      return write('/confirm-delivery',{method:'POST',body:form});
    }
    async function fail(code,detail) {
      if(!canWrite() || !state.detail?.stop.canConfirm || state.uncertain || writePending)return false;
      const reason=state.options.failureReasons.find(r=>r.value===code), text=String(detail||'').trim();
      if(!reason || !text || text.length>400){state.detailError='Chọn lý do và nhập chi tiết, tối đa 400 ký tự.';emit();return false;}
      return write('/status',{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify({status:'FAILED',failureReasonCode:code,failedReason:text})});
    }
    async function receive(reason,checked) {
      if(!canWrite() || !state.detail?.stop.canReturn || state.uncertain || writePending)return false;
      const text=String(reason||'').trim();
      if(!checked || !text || text.length>500){state.detailError='Xác nhận đã thực nhận hàng và nhập lý do, tối đa 500 ký tự.';emit();return false;}
      return write('/return-to-warehouse',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({returnReason:text})});
    }
    async function proof() {
      if(!canRead() || !state.detail?.stop.hasSignedProof)return null;
      const {trip,stop}=state.detail,epoch=state.epoch,id=detailSerial;
      try {
        const blob=await request(path(trip.id,stop.id)+'/proof',{responseType:'blob'});
        if(epoch!==state.epoch || id!==detailSerial || !canRead())return null;
        if(!['image/jpeg','image/png'].includes(blob.type))throw Error('Chứng từ không phải ảnh PNG/JPEG.');
        return blob;
      } catch(e) {if(epoch===state.epoch && id===detailSerial && canRead()){state.detailError=e.message;emit();}return null;}
    }
    return {state,canRead,canWrite,initialize,load,open,close,confirm,fail,receive,proof,clear};
  }
  return {allowed,createClient};
})();
if(typeof module!=='undefined')module.exports=DeliveryConfirmation;

if(typeof document!=='undefined')(async()=>{
  if(!Auth.requireLogin())return;await Auth.ready;
  const $=id=>document.getElementById(id), allowed=(...p)=>Auth.hasAnyPermission(...p);
  const esc=v=>String(v??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const money=v=>v==null?'Chưa có số liệu':new Intl.NumberFormat('vi-VN',{style:'currency',currency:'VND',maximumFractionDigits:2}).format(Number(v));
  const quantity=v=>v==null?'Chưa có số liệu':new Intl.NumberFormat('vi-VN',{maximumFractionDigits:4}).format(Number(v));
  const show=(id,text)=>{$(id).textContent=text||'';$(id).hidden=!text;};
  let shownOptions=null,shownDetail='',previewUrl=null,proofUrl=null,previewFile=null,imageEpoch=0;
  async function request(path,options={}) {
    const {responseType,...rest}=options, response=await Auth.apiFetch(path,{cache:'no-store',...rest});
    if(responseType==='blob') {if(!response.ok)throw Object.assign(Error('Không tải được ảnh ký nhận.'),{status:response.status});return response.blob();}
    const payload=await response.json().catch(()=>null);
    if(!response.ok || !payload?.success)throw Object.assign(Error(payload?.message||'Không tải/lưu được dữ liệu.'),{status:response.status});
    return payload.data;
  }
  const client=DeliveryConfirmation.createClient({request,permissions:allowed,onChange:render});
  function clearImages() {
    imageEpoch++;if(previewUrl)URL.revokeObjectURL(previewUrl);if(proofUrl)URL.revokeObjectURL(proofUrl);
    previewUrl=proofUrl=null;previewFile=null;
    for(const id of ['dc-preview','dc-stored-proof']){$(id).removeAttribute('src');$(id).hidden=true;}
    $('dc-proof-file').value='';$('dc-download-proof').removeAttribute('href');$('dc-download-proof').hidden=true;
  }
  function render() {
    const s=client.state,read=client.canRead(),write=client.canWrite(),busy=s.busy;
    $('dc-denied').hidden=read;$('dc-workspace').hidden=!read;
    show('dc-error',s.error);show('dc-notice',s.notice);show('dc-detail-error',s.detailError);
    if(s.options!==shownOptions) {
      shownOptions=s.options;
      $('dc-warehouse').innerHTML='<option value="">Tất cả kho được giao</option>'+(s.options?.warehouses||[]).map(w=>'<option value="'+esc(w.id)+'">'+esc(w.name)+'</option>').join('');
      $('dc-trip-status').innerHTML='<option value="">Tất cả trạng thái</option>'+(s.options?.tripStatuses||[]).map(x=>'<option value="'+esc(x.value)+'">'+esc(x.label)+'</option>').join('');
      $('dc-fail-reason').innerHTML='<option value="">Chọn lý do</option>'+(s.options?.failureReasons||[]).map(x=>'<option value="'+esc(x.value)+'">'+esc(x.label)+'</option>').join('');
      $('dc-proof-policy').textContent=s.options?'Ảnh PNG/JPEG tối đa '+(s.options.proofMaxBytes/1024/1024)+' MB, '+s.options.proofMaxPixels.toLocaleString('vi-VN')+' điểm ảnh. Ảnh chỉ được lưu khi xác nhận thành công.':'';
    }
    $('dc-count').textContent=s.loading?'Đang tải chuyến giao…':s.error?'Chưa tải được dữ liệu':s.rows.length+' điểm giao trên trang '+(s.page+1)+'; tổng '+s.total+' chuyến theo bộ lọc.';
    $('dc-page').textContent=s.pages?'Trang '+(s.page+1)+' / '+s.pages:'Không có chuyến';
    $('dc-prev').disabled=busy||s.loading||!read||s.page===0;$('dc-next').disabled=busy||s.loading||!read||s.page+1>=s.pages;
    $('dc-reload').disabled=busy||!read;
    $('dc-filter').querySelectorAll('input,select,button').forEach(el=>el.disabled=busy||s.loading||!s.options||!read);
    $('dc-rows').innerHTML=s.rows.map(({trip,stop})=>'<tr><td><strong>'+esc(trip.tripCode)+'</strong><small>'+esc(stop.voucherCode||stop.orderCode||'Điểm giao')+'</small><small>'+esc(trip.warehouseName)+'</small></td><td>'+esc(stop.receiverName||stop.dealerName)+'<small>'+esc(stop.deliveryAddress)+'</small></td><td>'+esc(money(stop.deliveryAmount))+'</td><td>'+esc(stop.statusDisplayName||stop.status)+(stop.returnedToWarehouse?'<small>Đã nhập trả kho</small>':'')+'</td><td>'+esc(stop.orderFullyDelivered?'Đã giao đủ đơn':stop.orderStatusDisplayName||(stop.orderId?'Chưa giao đủ đơn':'Điểm dừng không gắn đơn'))+'</td><td><button type="button" class="btn btn--secondary" data-trip="'+esc(trip.id)+'" data-stop="'+esc(stop.id)+'" '+(busy?'disabled':'')+'>Mở điểm giao</button></td></tr>').join('')||'<tr><td colspan="6">'+(s.loading?'Đang tải…':s.error?'Không tải được dữ liệu. Chọn Tải lại.':'Không có điểm giao trên trang này.')+'</td></tr>';
    const d=s.detail,key=d?[d.trip.id,d.stop.id,d.stop.status,d.stop.returnedToWarehouse,d.stop.hasSignedProof].join(':'):'';
    $('dc-detail').hidden=!read||!d;
    if(key!==shownDetail) {
      shownDetail=key;clearImages();
      for(const id of ['dc-confirm-form','dc-fail-form','dc-return-form'])$(id).reset();
      if(d) {
        $('dc-receiver').value=d.stop.receiverName||'';
        const date=new Date(Date.now()-new Date().getTimezoneOffset()*60000);$('dc-delivered-at').value=date.toISOString().slice(0,19);
      }
    }
    if(!d)return;
    $('dc-detail-title').textContent=(d.stop.voucherCode||d.stop.orderCode||'Điểm giao')+' · '+d.trip.tripCode;
    $('dc-detail-info').textContent=[d.trip.warehouseName,d.stop.receiverName,d.stop.receiverPhone,d.stop.deliveryAddress].filter(Boolean).join(' · ');
    $('dc-detail-state').textContent=[d.stop.statusDisplayName||d.stop.status,d.stop.failedReason,d.stop.returnedToWarehouse?'Đã nhập trả: '+(d.stop.returnReason||''):null,
      s.uncertain?'Cần tải lại để xác định kết quả lưu':!write?'Chỉ xem; chưa có quyền thực hiện':null].filter(Boolean).join(' · ');
    $('dc-lines').innerHTML=d.stop.items.map(item=>'<article class="pick-line"><strong>'+esc(item.productCode)+' — '+esc(item.productName)+'</strong><p>'+esc(quantity(item.quantity))+' '+esc(item.unit)+'; cơ sở: '+esc(quantity(item.baseQuantity))+' '+esc(item.baseUnit||'')+(item.lotNumber?'; lô '+esc(item.lotNumber):'')+'</p></article>').join('')||'<p>Điểm giao cũ chưa có dòng hàng/chứng từ gốc. Cần đối soát trước khi nhập trả.</p>';
    $('dc-confirm-form').hidden=!write||!d.stop.canConfirm;$('dc-fail-form').hidden=!write||!d.stop.canConfirm;$('dc-return-form').hidden=!write||!d.stop.canReturn;
    for(const id of ['dc-confirm-form','dc-fail-form','dc-return-form'])$(id).querySelectorAll('input,select,button').forEach(el=>el.disabled=busy||s.detailLoading||s.uncertain||!write);
    $('dc-detail-reload').disabled=busy||!read;$('dc-close').disabled=busy;
    $('dc-proof-actions').hidden=!d.stop.hasSignedProof;$('dc-view-proof').disabled=busy||!read;
    $('dc-signed-info').textContent=d.stop.hasSignedProof?[d.stop.receiverName,d.stop.deliveredAt,d.stop.confirmedBy].filter(Boolean).join(' · '):'';
  }
  $('dc-filter').addEventListener('submit',e=>{e.preventDefault();client.load(0,{warehouseId:$('dc-warehouse').value,status:$('dc-trip-status').value,keyword:$('dc-keyword').value.trim()});});
  $('dc-prev').addEventListener('click',()=>client.load(client.state.page-1));$('dc-next').addEventListener('click',()=>client.load(client.state.page+1));
  $('dc-reload').addEventListener('click',()=>client.state.options?client.load(client.state.page):client.initialize());
  $('dc-rows').addEventListener('click',async e=>{const b=e.target.closest('[data-trip][data-stop]');if(b && await client.open(b.dataset.trip,b.dataset.stop))$('dc-detail-title').focus();});
  $('dc-close').addEventListener('click',()=>client.close());
  $('dc-detail-reload').addEventListener('click',()=>{const d=client.state.detail;if(d)client.open(d.trip.id,d.stop.id);});
  $('dc-proof-file').addEventListener('change',async e=>{
    const file=e.target.files?.[0],o=client.state.options,epoch=++imageEpoch;
    if(previewUrl)URL.revokeObjectURL(previewUrl);previewUrl=null;previewFile=null;$('dc-preview').hidden=true;
    if(!file)return;
    if(!o?.proofContentTypes.includes(file.type)||file.size>o.proofMaxBytes||!file.size){show('dc-detail-error','Chọn ảnh PNG/JPEG trong giới hạn dung lượng.');return;}
    const url=URL.createObjectURL(file),img=new Image();img.src=url;
    try {
      await img.decode();
      if(epoch!==imageEpoch||!client.canWrite()||!client.state.detail){URL.revokeObjectURL(url);return;}
      if(img.naturalWidth*img.naturalHeight>o.proofMaxPixels)throw Error('Ảnh vượt giới hạn điểm ảnh.');
      previewUrl=url;previewFile=file;$('dc-preview').src=url;$('dc-preview').hidden=false;show('dc-detail-error','');
    }catch(err){URL.revokeObjectURL(url);if(epoch===imageEpoch)show('dc-detail-error',err.message||'Ảnh không hợp lệ.');}
  });
  $('dc-confirm-form').addEventListener('submit',e=>{e.preventDefault();client.confirm({receiverName:$('dc-receiver').value,deliveredAt:$('dc-delivered-at').value,proof:previewFile});});
  $('dc-fail-form').addEventListener('submit',e=>{e.preventDefault();client.fail($('dc-fail-reason').value,$('dc-fail-detail').value);});
  $('dc-return-form').addEventListener('submit',e=>{e.preventDefault();client.receive($('dc-return-reason').value,$('dc-return-checked').checked);});
  $('dc-view-proof').addEventListener('click',async()=>{
    const blob=await client.proof();if(!blob)return;if(proofUrl)URL.revokeObjectURL(proofUrl);proofUrl=URL.createObjectURL(blob);
    $('dc-stored-proof').src=proofUrl;$('dc-stored-proof').hidden=false;$('dc-download-proof').href=proofUrl;$('dc-download-proof').download='bien-ban-giao-hang.'+(blob.type==='image/png'?'png':'jpg');$('dc-download-proof').hidden=false;
  });
  window.addEventListener('auth:permissions-changed',()=>{clearImages();client.initialize();});window.addEventListener('pagehide',clearImages);
  await client.initialize();
  const query=new URLSearchParams(location.search);
  if(query.get('tripId') && query.get('stopId'))await client.open(query.get('tripId'),query.get('stopId'));
})();
