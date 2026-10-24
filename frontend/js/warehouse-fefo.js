/** S6-04: the server owns FEFO ordering, stock, expiry and the selected-lot write. */
const WarehouseFefo = (() => {
  function createClient({request, permissions, onChange = () => {}}) {
    const state = {options:null, loading:false, suggesting:false, busy:false, error:'', result:null,
      preview:null, selected:'', reason:'', confirmed:false, input:{warehouseId:'',productId:'',quantity:''}, epoch:0};
    let read=0;
    const emit=()=>onChange(state);
    const allowed=()=>permissions('WAREHOUSE_VIEW','WAREHOUSE_MANAGE') && permissions('WAREHOUSE_VIEW','WAREHOUSE_EXPORT');
    const canExport=()=>allowed() && permissions('WAREHOUSE_EXPORT') && state.options?.canExport===true;
    const selected=()=>state.preview?.availableLots.find(l=>String(l.lotNumber)===state.selected);
    const selectable=lot=>!!lot && !!lot.expiryDate && lot.isExpired===false && lot.sufficientForRequest===true;
    const different=()=>!!state.preview?.suggestedLot && state.selected!==state.preview.suggestedLot.lotNumber;
    function clearPreview(){read++;state.suggesting=false;state.preview=null;state.selected='';state.reason='';state.confirmed=false;}
    function invalidate(){state.epoch++;clearPreview();state.options=null;state.loading=false;state.input={warehouseId:'',productId:'',quantity:''};state.error='';state.result=null;emit();}
    function input(values){
      if(state.busy)return;
      state.input={...state.input,...values};clearPreview();state.error='';state.result=null;emit();
    }
    async function initialize(){
      if(state.busy)return false;
      invalidate();if(!allowed())return false;
      const epoch=state.epoch,id=++read;state.loading=true;emit();
      try{
        const options=await request('/warehouse/lots/options');
        if(epoch!==state.epoch || id!==read || !allowed())return false;
        if(!Array.isArray(options?.warehouses)||!Array.isArray(options?.products))throw Error('Bộ chọn FEFO không hợp lệ.');
        state.options=options;
        if(!options.warehouses.length)throw Error('Chưa có kho trong phạm vi được phân công.');
        return true;
      }catch(e){if(epoch===state.epoch && id===read && allowed())state.error=e.message;return false;}
      finally{if(epoch===state.epoch && id===read){state.loading=false;emit();}}
    }
    async function suggest(){
      if(!allowed() || !state.options || state.loading || state.suggesting || state.busy)return false;
      clearPreview();state.error='';state.result=null;
      const epoch=state.epoch,id=++read, form={...state.input};state.suggesting=true;emit();
      try{
        const product=state.options.products.find(p=>String(p.productId)===String(form.productId));
        if(!product || !state.options.warehouses.some(w=>String(w.id)===String(form.warehouseId)))throw Error('Chọn kho và sản phẩm trong danh sách.');
        if(!/^\d{1,11}(\.\d{1,4})?$/.test(String(form.quantity)) || !(Number(form.quantity)>0))throw Error('Số lượng phải lớn hơn 0, tối đa 11 chữ số nguyên và 4 số lẻ.');
        const query=new URLSearchParams({warehouseId:form.warehouseId,productId:form.productId,quantity:form.quantity,unit:product.unit});
        const data=await request('/warehouse/export/suggest-lot?'+query);
        if(epoch!==state.epoch || id!==read || !allowed())return false;
        if(String(data?.warehouseId)!==String(form.warehouseId) || String(data.productId)!==String(form.productId)
            || !Array.isArray(data.availableLots) || Number(data.requestedQuantity)!==Number(form.quantity)
            || data.requestedUnit!==product.unit)throw Error('Máy chủ trả gợi ý không khớp yêu cầu. Hãy lấy lại gợi ý.');
        if(data.suggestedLot && !data.availableLots.some(l=>l.lotNumber===data.suggestedLot.lotNumber && selectable(l)))throw Error('Lô gợi ý không còn hợp lệ. Hãy lấy lại gợi ý.');
        state.preview=data;state.selected=data.suggestedLot?.lotNumber||'';return true;
      }catch(e){if(epoch===state.epoch && id===read && allowed())state.error=e.message;return false;}
      finally{if(epoch===state.epoch && id===read){state.suggesting=false;emit();}}
    }
    function choose(lotNumber){
      if(state.busy || !selectable(state.preview?.availableLots.find(l=>l.lotNumber===lotNumber)))return false;
      state.selected=lotNumber;state.reason='';state.confirmed=false;state.error='';emit();return true;
    }
    function reason(value){if(!state.busy){state.reason=value;state.confirmed=false;state.error='';emit();}}
    function confirm(value){if(!state.busy){state.confirmed=!!value;emit();}}
    async function save(){
      if(!canExport() || state.busy || state.loading || state.suggesting || !state.preview)return false;
      state.error='';const clean=state.reason.trim();
      if(!selectable(selected()))state.error='Chọn một lô còn hạn và đủ số lượng.';
      else if(clean.length>255)state.error='Lý do tối đa 255 ký tự.';
      else if(different() && !clean)state.error='Bắt buộc nhập lý do khi chọn khác lô FEFO gợi ý.';
      else if(!state.confirmed)state.error='Đánh dấu xác nhận đã kiểm tra lô và số lượng trước khi xuất.';
      if(state.error){emit();return false;}
      const epoch=state.epoch,preview=state.preview,lotNumber=state.selected;state.busy=true;emit();
      try{
        const data=await request('/warehouse/export',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({
          warehouseId:preview.warehouseId,items:[{productId:preview.productId,unit:preview.requestedUnit,
            quantity:String(preview.requestedQuantity),lotNumber,suggestedLotNumber:preview.suggestedLot?.lotNumber||null,overrideReason:clean||null}]
        })});
        if(epoch!==state.epoch || !canExport())return false;
        if(!data?.voucherCode || !Array.isArray(data.items))throw Error('Không đọc được phiếu xuất đã ghi sổ.');
        state.result=data;clearPreview();return true;
      }catch(e){
        if(epoch===state.epoch && allowed()){
          // A network/5xx failure may arrive after the server committed. Do not
          // leave a one-click retry of a potentially successful stock deduction.
          if(!e.status || e.status>=500){clearPreview();state.error=e.message+' Chưa xác định được kết quả ghi sổ. Kiểm tra Sổ cái kho trước khi lấy lại gợi ý; không xuất lại ngay.';}
          else state.error=e.message;
        }
        return false;
      }
      finally{state.busy=false;emit();}
    }
    return {state,allowed,canExport,selected,selectable,different,initialize,invalidate,input,suggest,choose,reason,confirm,save};
  }
  return {createClient};
})();
if(typeof module!=='undefined')module.exports=WarehouseFefo;

if(typeof document!=='undefined')(async()=>{
  if(!Auth.requireLogin())return;await Auth.ready;
  const $=id=>document.getElementById(id),esc=v=>String(v??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const quantity=v=>Number(v).toLocaleString('vi-VN',{maximumFractionDigits:4});
  const date=v=>/^\d{4}-\d{2}-\d{2}$/.test(v||'')?v.split('-').reverse().join('/'):'Chưa có hạn dùng';
  let optionsVersion=null,previewVersion=null;
  const client=WarehouseFefo.createClient({permissions:(...p)=>Auth.hasAnyPermission(...p),onChange:render,request:async(path,options)=>{
    const response=await Auth.apiFetch(path,options),body=await response.json().catch(()=>null);
    if(!response.ok || !body?.success){const error=Error(body?.message||'Không kết nối được máy chủ. Hãy thử lại.');error.status=response.status;throw error;}return body.data;
  }});
  function message(id,value){$(id).textContent=value||'';$(id).hidden=!value;}
  function render(){
    const s=client.state,canRead=client.allowed(),blocked=s.loading||s.busy||!canRead,ready=!!s.options;
    $('lot-tab-fefo').hidden=!canRead;
    if(!canRead && !$('lot-fefo-panel').hidden){$('lot-fefo-panel').hidden=true;$('lot-tab-inventory').click();}
    message('fefo-error',s.error);message('fefo-success',s.result?'Đã ghi sổ phiếu '+s.result.voucherCode+'. Lô thực xuất: '+s.result.items.map(i=>i.lotNumber||'—').join(', ')+'. Lấy lại gợi ý để xem tồn mới.':'');
    $('fefo-readonly').hidden=!ready||client.canExport();
    if(optionsVersion!==s.options){
      optionsVersion=s.options;
      for(const [id,rows,value,label] of [['fefo-warehouse',s.options?.warehouses||[],w=>w.id,w=>w.code+' · '+w.name],['fefo-product',s.options?.products||[],p=>p.productId,p=>p.productCode+' · '+p.productName]]){
        $(id).innerHTML='<option value="">'+(ready?'Chọn trong danh sách':'Chưa có dữ liệu')+'</option>'+rows.map(r=>`<option value="${esc(value(r))}">${esc(label(r))}</option>`).join('');
      }
    }
    for(const [id,key] of [['fefo-warehouse','warehouseId'],['fefo-product','productId'],['fefo-quantity','quantity']]){
      $(id).disabled=blocked||!ready;if($(id).value!==s.input[key])$(id).value=s.input[key];
    }
    const product=s.options?.products.find(p=>String(p.productId)===String(s.input.productId));$('fefo-unit').textContent=product?'('+product.unit+')':'';
    $('fefo-reload').disabled=blocked;$('fefo-suggest').disabled=blocked||!ready||s.suggesting;$('fefo-suggest').textContent=s.suggesting?'Đang lấy gợi ý…':'Lấy gợi ý FEFO';
    $('fefo-request').setAttribute('aria-busy',String(s.suggesting));$('fefo-export').setAttribute('aria-busy',String(s.busy));
    $('fefo-result').textContent=s.suggesting?'Đang đối chiếu tồn và hạn dùng trên máy chủ…':s.preview?.message||'Chọn kho, sản phẩm và số lượng để lấy gợi ý.';
    $('fefo-preview').hidden=!s.preview;
    if(previewVersion!==s.preview){
      previewVersion=s.preview;
      $('fefo-rows').innerHTML=(s.preview?.availableLots||[]).map(l=>`<tr><td><input type="radio" name="fefo-lot" value="${esc(l.lotNumber)}" aria-label="Chọn lô ${esc(l.lotNumber)}"></td><td><strong>${esc(l.lotNumber)}</strong>${l.lotNumber===s.preview.suggestedLot?.lotNumber?'<small class="lot-state">Lô FEFO gợi ý</small>':''}</td><td>${date(l.expiryDate)}</td><td class="stock-number">${quantity(l.availableQuantity)} ${esc(l.baseUnit)}</td><td>${!l.expiryDate?'Thiếu hạn dùng':l.isExpired?'Đã quá hạn':l.sufficientForRequest?'Đủ số lượng':'Không đủ số lượng'}</td></tr>`).join('')||'<tr><td colspan="5" class="stock-empty">Không có lô còn hàng.</td></tr>';
    }
    for(const radio of $('fefo-rows').querySelectorAll('input')){
      radio.checked=radio.value===s.selected;radio.disabled=blocked||!client.selectable(s.preview?.availableLots.find(l=>l.lotNumber===radio.value));
    }
    $('fefo-selected').textContent=s.preview?(s.selected?'Lô sẽ xuất: '+s.selected+' · '+quantity(s.preview.requestedQuantity)+' '+s.preview.requestedUnit:'Chưa có lô phù hợp để xuất.') : '';
    $('fefo-reason-required').hidden=!client.different();$('fefo-reason').required=client.different();
    $('fefo-reason').disabled=blocked||!client.canExport();if($('fefo-reason').value!==s.reason)$('fefo-reason').value=s.reason;
    $('fefo-confirm').disabled=blocked||!client.canExport();$('fefo-confirm').checked=s.confirmed;
    $('fefo-save').hidden=!client.canExport();$('fefo-save').disabled=blocked||s.suggesting||!client.selectable(client.selected());$('fefo-save').textContent=s.busy?'Đang ghi sổ…':'Xác nhận xuất lô';
  }
  for(const [id,key] of [['fefo-warehouse','warehouseId'],['fefo-product','productId'],['fefo-quantity','quantity']])$(id).addEventListener(id==='fefo-quantity'?'input':'change',()=>client.input({[key]:$(id).value}));
  $('fefo-request').addEventListener('submit',e=>{e.preventDefault();client.suggest();});
  $('fefo-reload').addEventListener('click',()=>client.initialize());
  $('fefo-rows').addEventListener('change',e=>{if(e.target.name==='fefo-lot')client.choose(e.target.value);});
  $('fefo-reason').addEventListener('input',()=>client.reason($('fefo-reason').value));$('fefo-confirm').addEventListener('change',()=>client.confirm($('fefo-confirm').checked));
  $('fefo-export').addEventListener('submit',async e=>{e.preventDefault();if(await client.save())window.dispatchEvent(new Event('warehouse:stock-changed'));});
  window.addEventListener('auth:permissions-changed',()=>{client.invalidate();if(client.allowed()&&!client.state.busy)client.initialize();});
  await client.initialize();
})();
