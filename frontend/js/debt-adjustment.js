/* S8-02 uses persisted financial returns, never fabricated warehouse vouchers. */
(async()=>{
 if(!Auth.requireLogin())return;await Auth.ready;
 const U=DebtAdjustmentUtils,$=id=>document.getElementById(id),esc=U.escape;
 const view=()=>Auth.hasPermission('INVOICE_VIEW'),adjust=()=>Auth.hasPermission('INVOICE_ADJUST');
 const allowed=()=>MenuConfig.getMenuForUser(Auth.getUser()).some(item=>item.id==='debt-adjustment');
 const state={page:0,pages:0,sourcePage:0,sourcePages:0,source:null,detail:null,dirty:false,writing:false,blocked:false};
 const requests=new Map();let epoch=0;
 const message=(id,text='')=>{$(id).textContent=text;$(id).hidden=!text;};
 function abort(slot){requests.get(slot)?.abort();requests.delete(slot);}
 function controls(){
  const can=allowed();$('fr-access').hidden=can;$('fr-history').hidden=!can||!view();$('fr-write-only').hidden=!can||view();$('fr-new').hidden=!can||!adjust();$('fr-new').disabled=state.writing||state.blocked;$('fr-reload').disabled=!can||state.writing;
  for(const id of ['fr-save','fr-confirm','fr-cancel'])$(id).disabled=!can||!adjust()||state.writing||state.blocked;
  $('fr-save').disabled=$('fr-save').disabled||!state.source?.items.some(row=>U.decimal(row.remainingQuantity)>0n);
  $('fr-draft-actions').hidden=!can||!adjust()||state.detail?.status!=='DRAFT';$('fr-prev').disabled=!view()||state.page===0;$('fr-next').disabled=!view()||state.page+1>=state.pages;$('fr-source-prev').disabled=!adjust()||state.sourcePage===0;$('fr-source-next').disabled=!adjust()||state.sourcePage+1>=state.sourcePages;
  for(const element of $('fr-create-form').querySelectorAll('input,textarea'))element.disabled=state.writing||element.dataset.zero==='true';
 }
 async function request(slot,path,options={}){
  if(!allowed())throw Error('Bạn không có quyền sử dụng chức năng.');abort(slot);const controller=new AbortController(),version=epoch;requests.set(slot,controller);
  const valid=()=>version===epoch&&requests.get(slot)===controller&&allowed();
  try{const response=await Auth.apiFetch('/invoices/returns'+path,{...options,signal:controller.signal});if(!valid())throw Object.assign(Error(),{name:'AbortError'});
   const text=await response.text();if(!valid())throw Object.assign(Error(),{name:'AbortError'});let payload;try{payload=U.parse(text);}catch{throw Error('Máy chủ trả dữ liệu không hợp lệ.');}
   if(!response.ok||payload?.success!==true)throw Object.assign(Error(payload?.message||'Không hoàn tất được yêu cầu.'),{status:response.status});return payload.data;
  }catch(error){if(!valid())throw Object.assign(Error(),{name:'AbortError'});throw error;}
 }
 function clearDetail(){state.detail=null;$('fr-detail').hidden=true;$('fr-detail-info').replaceChildren();$('fr-detail-lines').replaceChildren();$('fr-confirm-ack').checked=false;$('fr-cancel-reason').value='';message('fr-adjustment');message('fr-balances');}
 function clearEditor(){state.source=null;state.dirty=false;$('fr-editor').hidden=true;$('fr-create-form').hidden=true;$('fr-invoices').replaceChildren();$('fr-source-lines').replaceChildren();$('fr-source-title').textContent='';$('fr-source-page').textContent='';$('fr-keyword').value='';$('fr-reason').value='';$('fr-note').value='';$('fr-create-ack').checked=false;}
 function showDetail(data){
  state.detail=U.detail(data);$('fr-detail').hidden=false;$('fr-detail-title').textContent=`${data.returnCode} — ${data.statusLabel||data.status}`;
  $('fr-detail-info').innerHTML=`<p>Hóa đơn gốc: <strong>${esc(data.invoiceCode||data.invoiceNumber)}</strong>; đại lý: ${esc(data.dealerName)} (${esc(data.dealerCode)}).</p><p>Lý do: ${esc(data.returnReason)}</p><p>Tổng tiền trả: <strong>${esc(U.money(data.totalReturnAmount))}</strong>.</p><p class="fr-muted">${esc(data.note||'')}${data.status==='CANCELLED'?' · Lý do hủy: '+esc(data.cancelReason):''}</p>`;
  $('fr-detail-lines').innerHTML=data.items.map(row=>`<tr><td>${esc(row.productName)}<small>${esc(row.productCode)} · ${esc(row.unit)}</small></td><td>${esc(U.quantity(row.returnQuantity))}</td><td class="fr-money">${esc(U.money(row.invoiceUnitPrice))}</td><td>${esc(row.invoiceDiscountPerUnit??'Chưa có dữ liệu')}</td><td class="fr-money">${esc(U.money(row.returnAmount))}</td></tr>`).join('');
  const adj=data.invoiceAdjustment;message('fr-adjustment',adj?`Đã ghi chứng từ ${adj.adjustmentCode}, giảm ${U.money(data.totalReturnAmount)}, liên kết hóa đơn #${data.invoiceId}. Hóa đơn gốc không bị sửa. Phiếu đã xác nhận không thể sửa/hủy.`:'Phiếu chưa sinh chứng từ giảm công nợ.');
  message('fr-balances',data.currentDebtAfter!=null&&data.remainingLimitAfter!=null?`Sau xác nhận: công nợ ${U.money(data.currentDebtAfter)}; hạn mức còn lại ${U.money(data.remainingLimitAfter)}. Số liệu tại thời điểm giao dịch này.`:'');
  $('fr-confirm-ack').checked=false;$('fr-cancel-reason').value='';controls();
 }
 async function history(){
  if(!allowed()||!view()){controls();return;}let dealer;try{dealer=$('fr-dealer').value.trim();if(dealer)U.id(dealer);}catch(error){message('fr-error',error.message);return;}
  const params=new URLSearchParams({page:state.page,size:20});if(dealer)params.set('dealerId',dealer);if($('fr-status').value)params.set('status',$('fr-status').value);
  $('fr-rows').innerHTML='<tr><td colspan="6">Đang tải phiếu trả hàng…</td></tr>';message('fr-error');$('fr-count').textContent='';
  try{const page=U.page(await request('history','?'+params));state.pages=page.totalPages;if(state.page>0&&state.page>=state.pages){state.page=Math.max(0,state.pages-1);if(state.pages)return history();}
   $('fr-rows').innerHTML=page.content.length?page.content.map(row=>`<tr><td>${esc(row.returnCode)}</td><td>${esc(row.invoiceCode)}</td><td>${esc(row.dealerName)}</td><td class="fr-money">${esc(U.money(row.totalReturnAmount))}</td><td>${esc(row.statusLabel||row.status)}</td><td><button class="btn btn--secondary" type="button" data-return="${U.id(row.id)}">Xem chi tiết</button></td></tr>`).join(''):'<tr><td colspan="6">Chưa có phiếu phù hợp bộ lọc.</td></tr>';
   $('fr-count').textContent=`${page.totalElements} phiếu trong hệ thống`;$('fr-page').textContent=page.totalPages?`Trang ${state.page+1}/${page.totalPages}`:'0 trang';
  }catch(error){if(error.name!=='AbortError'){$('fr-rows').innerHTML='<tr><td colspan="6">Không tải được danh sách. Bấm Tải lại dữ liệu.</td></tr>';message('fr-error',error.message);}}finally{controls();}
 }
 async function sources(){
  if(!allowed()||!adjust())return;$('fr-invoices').innerHTML='<tr><td colspan="3">Đang tải hóa đơn nguồn…</td></tr>';
  try{const data=U.page(await request('sources','/eligible-invoices?'+new URLSearchParams({keyword:$('fr-keyword').value.trim(),page:state.sourcePage,size:20})));state.sourcePages=data.totalPages;
   $('fr-invoices').innerHTML=data.content.length?data.content.map(row=>`<tr><td>${esc(row.invoiceCode)}<small>${esc(row.invoiceNumber)}</small></td><td>${esc(row.dealerName)}<small>${esc(row.dealerCode)}</small></td><td><button class="btn btn--secondary" type="button" data-source="${U.id(row.invoiceId)}">Chọn hóa đơn</button></td></tr>`).join(''):'<tr><td colspan="3">Không tìm thấy hóa đơn nguồn phù hợp.</td></tr>';$('fr-source-page').textContent=data.totalPages?`Trang ${state.sourcePage+1}/${data.totalPages}`:'0 trang';
  }catch(error){if(error.name!=='AbortError'){$('fr-invoices').replaceChildren();message('fr-error',error.message);}}finally{controls();}
 }
 async function source(invoiceId,refresh=false){
  if(!allowed()||!adjust()||state.writing)return;const quantities={};if(refresh)$('fr-source-lines').querySelectorAll('[data-qty]').forEach(input=>quantities[input.dataset.qty]=input.value);state.source=null;$('fr-create-form').hidden=true;
  try{const data=U.source(await request('source','/eligible-invoices/'+U.id(invoiceId)));state.source=data;$('fr-source-title').textContent=`${data.invoiceCode} — ${data.dealerName}`;
   $('fr-source-lines').innerHTML=data.items.map(row=>`<tr><td>${esc(row.productName)}<small>${esc(row.productCode)} · ${esc(row.unit)}</small></td><td>${esc(U.quantity(row.invoicedQuantity))}</td><td>${esc(U.quantity(row.confirmedReturnQuantity))}</td><td>${esc(U.quantity(row.remainingQuantity))}</td><td class="fr-money">${esc(U.money(row.invoiceUnitPrice))}</td><td class="fr-money">${esc(U.money(row.originalLineTotal))}</td><td><input aria-label="Số lượng trả ${esc(row.productCode)}" data-qty="${row.invoiceItemId}" data-zero="${U.decimal(row.remainingQuantity)===0n}" inputmode="decimal" maxlength="17" value="${esc(quantities[row.invoiceItemId]??'')}" ${U.decimal(row.remainingQuantity)===0n?'disabled':''}></td></tr>`).join('');
   $('fr-create-form').hidden=false;$('fr-create-ack').checked=false;if(refresh)message('fr-success','Đã cập nhật số còn trả từ máy chủ; kiểm tra lại số lượng trước khi lưu.');controls();
  }catch(error){if(error.name!=='AbortError'){state.source=null;$('fr-source-lines').replaceChildren();message('fr-error',error.message);}}
 }
 async function write(path,body,kind){
  if(!allowed()||!adjust()||state.writing||state.blocked)return;const version=epoch;state.writing=true;controls();message('fr-error');message('fr-success');
  try{const data=U.detail(await request('write',path,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body??{})}));if(version!==epoch||!allowed()||!adjust())return;
   if(kind==='create')clearEditor();showDetail(data);message('fr-success',kind==='create'?`Đã lưu ${data.returnCode} ở trạng thái nháp, chưa giảm nợ.`:kind==='confirm'?`Đã xác nhận ${data.returnCode} và ghi chứng từ ${data.invoiceAdjustment.adjustmentCode}.`:`Đã hủy phiếu ${data.returnCode}.`);if(view())await history();
  }catch(error){if(error.name!=='AbortError'&&version===epoch){if(!error.status||error.status>=500){state.blocked=true;message('fr-error','Chưa xác định giao dịch đã được lưu hay chưa. Không gửi lại; tải lịch sử để đối chiếu bằng tài khoản INVOICE_VIEW. '+error.message);}else{message('fr-error',error.message);if(error.status===409&&state.detail&&view()){try{showDetail(await request('detail','/'+state.detail.id));}catch{clearDetail();}}}}}finally{state.writing=false;controls();}
 }
 $('fr-new').addEventListener('click',()=>{if(!adjust()||!allowed()||state.writing||state.blocked)return;if(state.dirty&&!window.confirm('Bỏ phần chưa lưu để lập phiếu mới?'))return;for(const slot of ['detail','source','sources'])abort(slot);clearDetail();clearEditor();$('fr-editor').hidden=false;state.sourcePage=0;sources();});
 $('fr-close-editor').addEventListener('click',()=>{if(state.writing)return;if(state.dirty&&!window.confirm('Bỏ các số lượng/lý do chưa lưu?'))return;abort('sources');abort('source');clearEditor();});
 $('fr-close-detail').addEventListener('click',()=>{if(!state.writing){abort('detail');clearDetail();}});
 $('fr-source-search').addEventListener('submit',event=>{event.preventDefault();state.sourcePage=0;sources();});
 $('fr-invoices').addEventListener('click',event=>{const button=event.target.closest('[data-source]');if(!button||state.writing||state.blocked||!adjust()||!allowed())return;if(state.dirty&&!window.confirm('Đổi hóa đơn sẽ bỏ số lượng chưa lưu. Tiếp tục?'))return;state.dirty=false;$('fr-reason').value='';$('fr-note').value='';source(button.dataset.source);});
 $('fr-source-refresh').addEventListener('click',()=>{if(state.source)source(state.source.invoiceId,true);});
 $('fr-create-form').addEventListener('input',event=>{state.dirty=true;if(event.target.id!=='fr-create-ack')$('fr-create-ack').checked=false;});
 $('fr-create-form').addEventListener('submit',event=>{event.preventDefault();if(!state.source||!adjust()||state.writing||state.blocked)return;try{if(!$('fr-create-ack').checked)throw Error('Tích xác nhận đã đối chiếu hóa đơn và số thực trả.');const values={};$('fr-source-lines').querySelectorAll('[data-qty]').forEach(input=>values[input.dataset.qty]=input.value);write('',U.payload(state.source,values,$('fr-reason').value,$('fr-note').value),'create');}catch(error){message('fr-error',error.message);}});
 $('fr-confirm').addEventListener('click',()=>{if(state.detail?.status!=='DRAFT')return;if(!$('fr-confirm-ack').checked){message('fr-error','Tích xác nhận đã đối chiếu nội dung phiếu.');return;}write('/'+state.detail.id+'/confirm',{},'confirm');});
 $('fr-cancel').addEventListener('click',()=>{if(state.detail?.status!=='DRAFT')return;const reason=$('fr-cancel-reason').value.trim();if(!reason||reason.length>500){message('fr-error','Nhập lý do hủy, tối đa 500 ký tự.');return;}if(window.confirm('Hủy phiếu nháp này?'))write('/'+state.detail.id+'/cancel',{cancelReason:reason},'cancel');});
 $('fr-rows').addEventListener('click',async event=>{const button=event.target.closest('[data-return]');if(!button||!view()||!allowed()||state.writing)return;if(state.dirty&&!window.confirm('Bỏ phần chưa lưu để xem phiếu này?'))return;abort('source');abort('sources');clearEditor();clearDetail();try{showDetail(await request('detail','/'+U.id(button.dataset.return)));}catch(error){if(error.name!=='AbortError')message('fr-error',error.message);}});
 $('fr-filter-form').addEventListener('submit',event=>{event.preventDefault();state.page=0;history();});
 $('fr-prev').addEventListener('click',()=>{if(state.page>0){state.page--;history();}});$('fr-next').addEventListener('click',()=>{if(state.page+1<state.pages){state.page++;history();}});
 $('fr-source-prev').addEventListener('click',()=>{if(state.sourcePage>0){state.sourcePage--;sources();}});$('fr-source-next').addEventListener('click',()=>{if(state.sourcePage+1<state.sourcePages){state.sourcePage++;sources();}});
 $('fr-reload').addEventListener('click',async()=>{if(state.writing)return;if(state.dirty&&!window.confirm('Tải lại và bỏ phần chưa lưu?'))return;for(const slot of ['history','detail','source','sources'])abort(slot);clearEditor();clearDetail();message('fr-error');if(view()){await history();if(!$('fr-error').textContent)state.blocked=false;}else if(state.blocked)message('fr-error','Cần INVOICE_VIEW để đối chiếu giao dịch chưa xác định kết quả.');controls();});
 function revoke(){epoch++;for(const controller of requests.values())controller.abort();requests.clear();clearEditor();clearDetail();$('fr-rows').replaceChildren();$('fr-count').textContent='';$('fr-page').textContent='';message('fr-error');message('fr-success');controls();if(view()&&allowed())history();}
 window.addEventListener('auth:permissions-changed',revoke);window.addEventListener('pagehide',()=>{epoch++;for(const controller of requests.values())controller.abort();clearEditor();clearDetail();$('fr-rows').replaceChildren();});
 window.addEventListener('pageshow',event=>{if(event.persisted){controls();if(allowed()&&view())history();}});
 window.addEventListener('beforeunload',event=>{if(state.dirty||state.writing){event.preventDefault();event.returnValue='';}});
 controls();if(allowed()&&view())history();
})();
