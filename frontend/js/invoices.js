/** S7-01: backend remains the source of truth for actual delivery, prices and numbering. */
(async()=>{
  if(!Auth.requireLogin())return;
  await Auth.ready;
  const $=id=>document.getElementById(id), U=InvoiceUtils, esc=U.escape;
  const view=()=>Auth.hasPermission('INVOICE_VIEW');
  const create=()=>view()&&Auth.hasPermission('INVOICE_CREATE');
  const adjust=()=>view()&&Auth.hasPermission('INVOICE_ADJUST');
  const state={page:0,totalPages:0,filters:{},dealers:[],eligible:[],selected:new Set(),preview:null,detail:null};
  let epoch=0,writeBusy=false,previewBusy=false,printReady=false,uncertainWrite=false;
  const requests=new Map(),downloads=new Set();
  function message(id,text=''){ $(id).textContent=text;$(id).hidden=!text; }
  function cancel(slot){const previous=requests.get(slot);previous?.abort();requests.delete(slot);}
  const stopped=()=>Object.assign(Error('Phản hồi đã hết hiệu lực.'),{name:'AbortError'});
  async function request(slot,path,options={}){
    const permission=options.permission||'INVOICE_VIEW';
    if(!view()||!Auth.hasPermission(permission))throw Error('Bạn không có quyền thực hiện thao tác này.');
    cancel(slot);const controller=new AbortController(),version=epoch;requests.set(slot,controller);
    const config={method:options.method||'GET',cache:'no-store'};
    if(config.method==='GET'||path==='/preview')config.signal=controller.signal;
    if(options.body)config.body=JSON.stringify(options.body);
    let response;
    try{response=await Auth.apiFetch('/invoices'+path,config);}
    catch(error){if(version!==epoch||requests.get(slot)!==controller||!view()||!Auth.hasPermission(permission))throw stopped();throw error;}
    if(version!==epoch||requests.get(slot)!==controller||!view()||!Auth.hasPermission(permission))throw stopped();
    if(options.binary||options.html){
      if(!response.ok){const payload=await response.json().catch(()=>({}));throw Error(payload.message||'Không tải được tài liệu.');}
      return {response,valid:()=>version===epoch&&requests.get(slot)===controller&&view()};
    }
    const payload=await response.json().catch(()=>({}));
    if(version!==epoch||requests.get(slot)!==controller||!view()||!Auth.hasPermission(permission))throw stopped();
    if(!response.ok||payload.success!==true){const error=Error(payload.message||'Không thể xử lý yêu cầu. Vui lòng thử lại.');error.status=response.status;throw error;}
    return payload.data;
  }
  function actions(){
    $('inv-reload').disabled=writeBusy;
    $('inv-preview').textContent=previewBusy?'Đang xem trước…':'Xem trước hóa đơn';
    $('inv-issue').textContent=writeBusy?'Đang xử lý…':'Xác nhận phát hành';
    $('inv-adjust-save').textContent=writeBusy?'Đang xử lý…':'Xác nhận lập chứng từ';
    $('inv-new').hidden=!create();$('inv-new').disabled=writeBusy||uncertainWrite;
    $('inv-preview').disabled=!create()||writeBusy||uncertainWrite||previewBusy||!state.selected.size;
    $('inv-issue').disabled=!create()||writeBusy||uncertainWrite||previewBusy||!state.preview||!$('inv-confirm').checked;
    $('inv-adjust-open').hidden=!adjust()||!state.detail||!['ISSUED','ADJUSTED'].includes(state.detail.status);
    $('inv-adjust-open').disabled=writeBusy||uncertainWrite;
    $('inv-adjust-save').disabled=!adjust()||writeBusy||uncertainWrite;
    $('inv-load-eligible').disabled=writeBusy;
    for(const element of document.querySelectorAll('#inv-create input:not(#inv-confirm), #inv-create select, #inv-create textarea'))element.disabled=writeBusy;
    for(const element of document.querySelectorAll('#inv-adjust input, #inv-adjust select, #inv-adjust textarea'))element.disabled=writeBusy;
    $('inv-confirm').disabled=writeBusy||previewBusy;
  }
  function invalidatePreview(){cancel('preview');state.preview=null;previewBusy=false;$('inv-preview-panel').hidden=true;$('inv-preview-body').replaceChildren();$('inv-confirm').checked=false;actions();}
  function clearPrint(){cancel('print');printReady=false;$('inv-print-now').disabled=true;$('inv-print-body').replaceChildren();$('inv-print').hidden=true;}
  function clearDetail(){cancel('detail');cancel('pdf');state.detail=null;clearPrint();$('inv-detail').hidden=true;$('inv-detail-body').replaceChildren();$('inv-detail-actions').hidden=true;$('inv-adjust').hidden=true;$('inv-adjust').reset();actions();}
  function clearCreate(){cancel('eligible');cancel('dealers');invalidatePreview();state.eligible=[];state.selected.clear();state.dealers=[];$('inv-dealer').replaceChildren(new Option('Chọn đại lý có phiếu chưa xuất hóa đơn',''));$('inv-eligible').replaceChildren();$('inv-selected').textContent='Chưa chọn phiếu.';$('inv-create').hidden=true;$('inv-eligible-filter').reset();for(const id of ['inv-date','inv-due','inv-note'])$(id).value='';message('inv-create-error');actions();}
  function table(items){
    return `<div class="inv-scroll" role="region" aria-label="Dòng hàng theo thực giao" tabindex="0"><table><thead><tr><th>Phiếu giao</th><th>Sản phẩm</th><th>Đơn vị</th><th>Đặt</th><th>Thực giao</th><th>Lập hóa đơn</th><th>Đơn giá</th><th>Chiết khấu</th><th>Thành tiền</th></tr></thead><tbody>${items.map(i=>`<tr><td>${esc(i.orderCode||'—')}</td><td>${esc(i.productName)}<small>${esc(i.productCode)}</small></td><td>${esc(i.unit||'—')}</td><td>${esc(U.quantity(i.orderedQuantity))}</td><td>${esc(U.quantity(i.deliveredQuantity))}</td><td>${esc(U.quantity(i.invoicedQuantity))}</td><td>${esc(U.money(i.unitPrice))}</td><td>${esc(U.money(i.discountAmount))}</td><td>${esc(U.money(i.totalAmount))}</td></tr>`).join('')}</tbody></table></div>`;
  }
  function invoiceBody(data){
    return `<dl class="inv-facts"><div><dt>Đại lý</dt><dd>${esc(data.dealerName)} · ${esc(data.dealerCode||'—')}</dd></div><div><dt>Mã số thuế</dt><dd>${esc(data.dealerTaxCode||'Chưa có dữ liệu')}</dd></div><div><dt>Địa chỉ</dt><dd>${esc(data.dealerAddress||'Chưa có dữ liệu')}</dd></div><div><dt>Tiền hàng thực giao</dt><dd>${esc(U.money(data.subtotalAmount))}</dd></div><div><dt>Chiết khấu</dt><dd>${esc(U.money(data.discountAmount))}</dd></div><div><dt>Tổng hóa đơn gốc</dt><dd class="inv-total">${esc(U.money(data.totalAmount))}</dd></div></dl>
      <p class="inv-muted">Phiếu giao nguồn: ${(data.orders||[]).map(o=>`${esc(o.orderCode)} (${esc(U.date(o.actualDeliveryDate))})`).join(' · ')||'Chưa có dữ liệu'}</p>${table(data.items||[])}${data.note?`<p>Ghi chú: ${esc(data.note)}</p>`:''}`;
  }
  async function list(){
    if(!view())return;
    message('inv-error');$('inv-list').innerHTML='<tr><td colspan="7" role="status">Đang tải hóa đơn…</td></tr>';$('inv-prev').disabled=true;$('inv-next').disabled=true;
    try{
      const query=new URLSearchParams({...state.filters,page:state.page,size:20});
      const data=await request('list','?'+query);
      if(!Array.isArray(data?.content)||!Number.isInteger(data.totalPages)||!Number.isInteger(data.totalElements))throw Error('Danh sách hóa đơn không hợp lệ.');
      state.totalPages=data.totalPages;$('inv-count').textContent=`${data.totalElements} hóa đơn`;$('inv-page').textContent=data.totalPages?`Trang ${state.page+1} / ${data.totalPages}`:'Không có hóa đơn';
      $('inv-prev').disabled=state.page===0;$('inv-next').disabled=state.page+1>=data.totalPages;
      $('inv-list').innerHTML=data.content.length?data.content.map(i=>`<tr><td><strong>${esc(i.invoiceNumber||i.invoiceCode)}</strong><small>${esc(i.invoiceCode)}</small></td><td>${esc(i.dealerName)}</td><td>${esc(U.date(i.invoiceDate))}</td><td><span class="inv-badge">${esc(i.statusLabel||i.status)}</span></td><td>${esc(U.money(i.totalAmount))}</td><td>${esc(U.money(i.adjustedTotalAmount))}</td><td><button type="button" class="btn btn--secondary" data-invoice="${esc(i.id)}">Xem hóa đơn</button></td></tr>`).join(''):'<tr><td colspan="7">Không có hóa đơn phù hợp. Thử đổi bộ lọc.</td></tr>';
    }catch(error){if(error.name!=='AbortError'){$('inv-list').replaceChildren();$('inv-count').textContent='';$('inv-page').textContent='';message('inv-error',error.message);}}
  }
  async function detail(id,focus=true){
    if(!view()||!U.id(id))return;clearDetail();$('inv-detail').hidden=false;$('inv-detail-title').textContent='Đang tải hóa đơn…';message('inv-detail-error');
    try{
      const data=await request('detail','/'+U.id(id));
      if(String(data?.id)!==String(id)||!Array.isArray(data.items)||!Array.isArray(data.orders))throw Error('Chi tiết hóa đơn không hợp lệ.');
      state.detail=data;$('inv-detail-title').textContent=`Hóa đơn ${data.invoiceNumber||data.invoiceCode}`;
      $('inv-detail-body').innerHTML=`<dl class="inv-facts"><div><dt>Trạng thái</dt><dd>${esc(data.statusLabel||data.status)}</dd></div><div><dt>Phát hành</dt><dd>${esc(U.date(data.invoiceDate))}</dd></div><div><dt>Hạn thanh toán</dt><dd>${esc(U.date(data.dueDate))}</dd></div><div><dt>Sau điều chỉnh</dt><dd>${esc(U.money(data.adjustedTotalAmount))}</dd></div></dl>${invoiceBody(data)}
        <h3>Chứng từ điều chỉnh đã lập</h3>${Array.isArray(data.adjustments)&&data.adjustments.length?data.adjustments.map(a=>`<article class="inv-source"><strong>${esc(a.adjustmentCode)}</strong><p>${esc(a.adjustmentTypeLabel||a.adjustmentType)} · ${esc(U.money(a.adjustmentAmount))} · ${esc(U.date(a.adjustmentDate))}</p><p>Lý do: ${esc(a.adjustmentReason)}</p>${a.newDealerName?`<p>Tên mới: ${esc(a.newDealerName)}</p>`:''}${a.newDealerTaxCode?`<p>MST mới: ${esc(a.newDealerTaxCode)}</p>`:''}${a.newDealerAddress?`<p>Địa chỉ mới: ${esc(a.newDealerAddress)}</p>`:''}<button class="btn btn--secondary" type="button" data-adjustment-pdf="${esc(a.id)}">Tải PDF chứng từ</button></article>`).join(''):'<p class="inv-muted">Chưa có chứng từ điều chỉnh.</p>'}`;
      $('inv-detail-actions').hidden=false;actions();if(focus){$('inv-detail-title').focus();$('inv-detail').scrollIntoView({block:'start'});}
    }catch(error){if(error.name!=='AbortError')message('inv-detail-error',error.message);}
  }
  async function openCreate(){
    if(!create()||writeBusy||uncertainWrite)return;clearCreate();$('inv-create').hidden=false;$('inv-create-title').focus();$('inv-create').scrollIntoView({block:'start'});
    try{
      const dealers=await request('dealers','/dealers-with-eligible-orders');
      if(!Array.isArray(dealers))throw Error('Danh sách đại lý không hợp lệ.');state.dealers=dealers;
      for(const dealer of dealers){const option=new Option(`${dealer.dealerCode} · ${dealer.dealerName} (${dealer.uninvoicedDeliveryCount} phiếu)`,String(dealer.dealerId));$('inv-dealer').add(option);}
      if(!dealers.length)message('inv-create-error','Chưa có đại lý có phiếu đã giao chưa xuất hóa đơn.');
    }catch(error){if(error.name!=='AbortError')message('inv-create-error',error.message);}
  }
  function resetEligible(){cancel('eligible');state.eligible=[];state.selected.clear();$('inv-eligible').replaceChildren();$('inv-selected').textContent='Chưa chọn phiếu.';invalidatePreview();}
  async function eligible(event){
    event.preventDefault();if(!create()||writeBusy)return;resetEligible();message('inv-create-error');
    try{
      const dealerId=U.id($('inv-dealer').value),from=$('inv-delivered-from').value,to=$('inv-delivered-to').value;
      U.period(from,to);if(!state.dealers.some(d=>U.id(d.dealerId)===dealerId))throw Error('Chọn đại lý từ danh sách phiếu chưa xuất hóa đơn.');
      const query=new URLSearchParams({dealerId});if(from)query.set('startDate',from);if(to)query.set('endDate',to);
      $('inv-eligible').textContent='Đang tải phiếu đã giao…';const rows=await request('eligible','/eligible-orders?'+query);
      if(!Array.isArray(rows)||rows.some(o=>!U.id(o.orderId)||!Array.isArray(o.items)))throw Error('Danh sách phiếu giao không hợp lệ.');
      state.eligible=rows;
      $('inv-eligible').innerHTML=rows.length?rows.map(o=>`<article class="inv-source"><label class="inv-check"><input type="checkbox" data-eligible="${esc(o.orderId)}"><span><strong>${esc(o.orderCode)}</strong> · Ngày giao ${esc(U.date(o.actualDeliveryDate))}<br>Giá trị thực giao: ${esc(U.money(o.deliveredTotalAmount))}</span></label><details><summary>Xem dòng hàng thực giao</summary>${table(o.items.map(i=>({...i,orderCode:o.orderCode,totalAmount:i.deliveredTotalAmount,invoicedQuantity:i.deliveredQuantity})))}</details></article>`).join(''):'<p class="inv-muted">Không có phiếu hợp lệ trong khoảng ngày này.</p>';
    }catch(error){if(error.name!=='AbortError'){$('inv-eligible').replaceChildren();message('inv-create-error',error.message);}}actions();
  }
  function selected(){
    const result=U.selection($('inv-dealer').value,[...state.selected]);
    if(result.orderIds.some(id=>!state.eligible.some(o=>U.id(o.orderId)===id)))throw Error('Phiếu đã chọn không còn trong danh sách hợp lệ.');return result;
  }
  function draftKey(){return JSON.stringify([selected(),...['inv-delivered-from','inv-delivered-to','inv-date','inv-due','inv-note'].map(id=>$(id).value)]);}
  function validPreview(data,selection){
    if(!data||String(data.dealerId)!==String(selection.dealerId)||!Array.isArray(data.items)||!data.items.length||!Array.isArray(data.orders)||!U.numeric(data.totalAmount))throw Error('Bản xem trước không hợp lệ.');
    const ids=data.orders.map(o=>U.id(o.orderId)).sort((a,b)=>a-b);
    if(JSON.stringify(ids)!==JSON.stringify(selection.orderIds))throw Error('Phiếu giao trong bản xem trước không khớp lựa chọn.');
    if(data.items.some(i=>!selection.orderIds.includes(U.id(i.orderId))||!U.numeric(i.invoicedQuantity)||Number(i.invoicedQuantity)<=0||!U.numeric(i.deliveredQuantity)||Number(i.invoicedQuantity)!==Number(i.deliveredQuantity)))throw Error('Dòng hóa đơn chưa khớp phiếu đã chọn hoặc số thực giao.');
    return data;
  }
  function showPreview(data,selection,key){state.preview={data,selection,key};$('inv-preview-body').innerHTML=invoiceBody(data);$('inv-preview-panel').hidden=false;$('inv-confirm').checked=false;actions();}
  async function preview(){
    if(!create()||writeBusy||uncertainWrite||previewBusy)return;message('inv-create-error');
    try{
      const selection=selected(),key=draftKey();invalidatePreview();previewBusy=true;actions();
      const data=await request('preview','/preview',{method:'POST',permission:'INVOICE_CREATE',body:selection});
      if(draftKey()!==key)throw stopped();showPreview(validPreview(data,selection),selection,key);$('inv-preview-title').focus();
    }catch(error){if(error.name!=='AbortError')message('inv-create-error',error.message);}finally{previewBusy=false;actions();}
  }
  async function issue(){
    if(!create()||writeBusy||uncertainWrite||previewBusy||!state.preview||!$('inv-confirm').checked)return;
    let sent=false;const startEpoch=epoch;message('inv-create-error');
    try{
      const previous=state.preview;if(draftKey()!==previous.key)throw Error('Thông tin đã thay đổi. Hãy xem trước lại.');
      if($('inv-note').value.length>500)throw Error('Ghi chú tối đa 500 ký tự.');
      const due=$('inv-due').value,date=$('inv-date').value;if(due&&date&&due<date.slice(0,10))throw Error('Hạn thanh toán không được trước ngày phát hành.');
      writeBusy=true;actions();
      // Re-check server totals immediately before issuing; changed amounts require fresh confirmation.
      const fresh=validPreview(await request('issue-preview','/preview',{method:'POST',permission:'INVOICE_CREATE',body:previous.selection}),previous.selection);
      if(U.signature(fresh)!==U.signature(previous.data)){showPreview(fresh,previous.selection,previous.key);throw Error('Dữ liệu thực giao hoặc giá trị đã thay đổi. Hãy đối chiếu và xác nhận bản mới.');}
      const body={...previous.selection};if(date)body.invoiceDate=date;if(due)body.dueDate=due;if($('inv-note').value.trim())body.note=$('inv-note').value.trim();
      sent=true;const result=await request('issue','',{method:'POST',permission:'INVOICE_CREATE',body});
      if(!U.id(result?.id)||!result.invoiceNumber)throw Error('Máy chủ chưa trả về hóa đơn đã cấp số.');
      clearCreate();message('inv-success',`Đã phát hành hóa đơn ${result.invoiceNumber}.`);await Promise.all([list(),detail(result.id)]);
    }catch(error){
      if(error.name!=='AbortError'){
        if(sent)invalidatePreview();
        if(sent&&(!error.status||error.status>=500))uncertainWrite=true;
        message('inv-create-error',uncertainWrite?'Chưa xác định được kết quả phát hành. Tải lại danh sách để kiểm tra trước khi thực hiện tiếp; không tự gửi lại.':error.message);
      }
    }finally{writeBusy=false;if(epoch===startEpoch)actions();else if(view())actions();}
  }
  function adjustmentFields(){const type=$('inv-adjust-type').value;$('inv-adjust-amount-label').hidden=!['INCREASE','DECREASE'].includes(type);$('inv-adjust-info').hidden=type!=='INFORMATION';$('inv-adjust-confirm').checked=false;}
  async function saveAdjustment(event){
    event.preventDefault();if(!adjust()||writeBusy||uncertainWrite||!state.detail||!['ISSUED','ADJUSTED'].includes(state.detail.status)||!$('inv-adjust-confirm').checked)return;
    let sent=false;message('inv-adjust-error');
    try{
      const id=state.detail.id,body=U.adjustment($('inv-adjust-type').value,$('inv-adjust-reason').value,$('inv-adjust-amount').value,{newDealerName:$('inv-adjust-name').value,newDealerTaxCode:$('inv-adjust-tax').value,newDealerAddress:$('inv-adjust-address').value});
      writeBusy=true;actions();sent=true;
      const data=await request('adjust','/'+id+'/adjustments',{method:'POST',permission:'INVOICE_ADJUST',body});
      if(!U.id(data?.id)||String(data.invoiceId)!==String(id))throw Error('Chưa nhận được chứng từ điều chỉnh hợp lệ.');
      message('inv-success',`Đã lập chứng từ ${data.adjustmentCode||data.id}.`);await Promise.all([list(),detail(id)]);
    }catch(error){if(error.name!=='AbortError'){
      if(sent&&(!error.status||error.status>=500)){uncertainWrite=true;$('inv-adjust').hidden=true;message('inv-detail-error','Chưa xác định được kết quả điều chỉnh. Tải lại hóa đơn trước khi tiếp tục; không tự gửi lại.');}
      else message('inv-adjust-error',error.message);
    }}finally{writeBusy=false;actions();}
  }
  async function pdf(adjustmentId=null){
    if(!view()||!state.detail)return;message('inv-detail-error');
    try{
      const detail=state.detail;if(adjustmentId&&!detail.adjustments?.some(a=>String(a.id)===String(adjustmentId)))throw Error('Chứng từ không thuộc hóa đơn đang xem.');
      const path='/'+detail.id+(adjustmentId?'/adjustments/'+U.id(adjustmentId):'')+'/pdf?download=true';
      const {response,valid}=await request('pdf',path,{binary:true});const blob=await response.blob();if(!valid())throw stopped();
      if(!blob.type.includes('application/pdf')||await blob.slice(0,5).text()!=='%PDF-')throw Error('Máy chủ chưa trả về PDF hợp lệ.');if(!valid())throw stopped();
      const url=URL.createObjectURL(blob);downloads.add(url);const link=document.createElement('a');link.href=url;link.download=`${adjustmentId?'chung-tu-'+adjustmentId:'hoa-don-'+detail.invoiceNumber}.pdf`;link.click();
      setTimeout(()=>{if(downloads.has(url)){URL.revokeObjectURL(url);downloads.delete(url);}},10000);
    }catch(error){if(error.name!=='AbortError')message('inv-detail-error',error.message);}
  }
  async function print(){
    if(!view()||!state.detail)return;clearPrint();message('inv-detail-error');
    try{
      const {response,valid}=await request('print','/'+state.detail.id+'/print-preview',{html:true});
      if(!response.headers.get('content-type')?.includes('text/html'))throw Error('Bản in không hợp lệ.');
      const html=await response.text();if(!valid())throw stopped();
      const documentHtml=new DOMParser().parseFromString(html,'text/html');
      documentHtml.querySelectorAll('script,iframe,object,embed,base,link,meta,form,.no-print').forEach(n=>n.remove());
      for(const node of documentHtml.querySelectorAll('*'))for(const attr of [...node.attributes])if(attr.name.toLowerCase().startsWith('on')||['href','src','srcdoc','action','formaction'].includes(attr.name.toLowerCase()))node.removeAttribute(attr.name);
      const policy=documentHtml.createElement('meta');policy.httpEquiv='Content-Security-Policy';policy.content="default-src 'none'; style-src 'unsafe-inline'; img-src data:; font-src data:; base-uri 'none'; form-action 'none'";documentHtml.head.prepend(policy);
      const frame=document.createElement('iframe');frame.title='Bản in hóa đơn từ hệ thống';frame.setAttribute('sandbox','allow-same-origin allow-modals');
      frame.addEventListener('load',()=>{if(valid()){printReady=true;$('inv-print-now').disabled=false;}});frame.srcdoc='<!doctype html>'+documentHtml.documentElement.outerHTML;
      $('inv-print-body').replaceChildren(frame);$('inv-print').hidden=false;$('inv-print-title').focus();$('inv-print').scrollIntoView({block:'start'});
    }catch(error){if(error.name!=='AbortError')message('inv-detail-error',error.message);}
  }
  $('inv-filter').addEventListener('submit',event=>{event.preventDefault();try{U.period($('inv-from').value,$('inv-to').value);state.filters={};for(const [id,key]of [['inv-keyword','keyword'],['inv-status','status'],['inv-from','startDate'],['inv-to','endDate']])if($(id).value.trim())state.filters[key]=$(id).value.trim();state.page=0;clearDetail();list();}catch(error){message('inv-error',error.message);}});
  $('inv-prev').addEventListener('click',()=>{if(state.page>0){state.page--;list();}});$('inv-next').addEventListener('click',()=>{if(state.page+1<state.totalPages){state.page++;list();}});
  $('inv-reload').addEventListener('click',()=>{if(writeBusy)return;uncertainWrite=false;clearCreate();clearDetail();message('inv-success');list();});
  $('inv-list').addEventListener('click',event=>{const button=event.target.closest('[data-invoice]');if(button&&!writeBusy)detail(button.dataset.invoice);});
  $('inv-new').addEventListener('click',openCreate);$('inv-create-close').addEventListener('click',()=>{if(!writeBusy){clearCreate();$('inv-new').focus();}});
  $('inv-eligible-filter').addEventListener('submit',eligible);
  for(const id of ['inv-dealer','inv-delivered-from','inv-delivered-to'])$(id).addEventListener('change',()=>{if(!writeBusy)resetEligible();});
  $('inv-eligible').addEventListener('change',event=>{const box=event.target.closest('[data-eligible]');if(!box||writeBusy||!create()||!state.eligible.some(o=>String(o.orderId)===box.dataset.eligible))return;if(box.checked)state.selected.add(box.dataset.eligible);else state.selected.delete(box.dataset.eligible);invalidatePreview();$('inv-selected').textContent=`Đã chọn ${state.selected.size} phiếu giao cùng đại lý.`;});
  for(const id of ['inv-date','inv-due','inv-note'])$(id).addEventListener('input',()=>{if(!writeBusy)invalidatePreview();});
  $('inv-preview').addEventListener('click',preview);$('inv-confirm').addEventListener('change',actions);$('inv-issue').addEventListener('click',issue);
  $('inv-detail-close').addEventListener('click',()=>{if(!writeBusy)clearDetail();});$('inv-pdf').addEventListener('click',()=>pdf());$('inv-print-open').addEventListener('click',print);
  $('inv-detail-body').addEventListener('click',event=>{const button=event.target.closest('[data-adjustment-pdf]');if(button)pdf(button.dataset.adjustmentPdf);});
  $('inv-adjust-open').addEventListener('click',()=>{if(!adjust()||writeBusy||uncertainWrite||!state.detail||!['ISSUED','ADJUSTED'].includes(state.detail.status))return;$('inv-adjust').reset();$('inv-adjust').hidden=false;message('inv-adjust-error');adjustmentFields();$('inv-adjust-type').focus();});
  $('inv-adjust-close').addEventListener('click',()=>{if(!writeBusy){$('inv-adjust').hidden=true;$('inv-adjust').reset();$('inv-adjust-open').focus();}});
  $('inv-adjust-type').addEventListener('change',adjustmentFields);$('inv-adjust').addEventListener('submit',saveAdjustment);
  $('inv-print-close').addEventListener('click',clearPrint);$('inv-print-now').addEventListener('click',()=>{if(view()&&printReady)$('inv-print-body').querySelector('iframe')?.contentWindow.print();});
  window.addEventListener('auth:permissions-changed',()=>{
    epoch++;for(const controller of requests.values())controller.abort();requests.clear();
    for(const url of downloads)URL.revokeObjectURL(url);downloads.clear();clearCreate();clearDetail();$('inv-list').replaceChildren();$('inv-count').textContent='';$('inv-page').textContent='';message('inv-error');message('inv-success');$('inv-workspace').hidden=!view();actions();if(view())list();
  });
  window.addEventListener('pagehide',()=>{epoch++;for(const controller of requests.values())controller.abort();for(const url of downloads)URL.revokeObjectURL(url);clearPrint();});
  $('inv-workspace').hidden=!view();actions();if(view())list();
})();
