/* S8-09 frontend. The backend supplies authoritative aging groups and risk order. */
(async()=>{
  if(!Auth.requireLogin())return;
  await Auth.ready;
  const $=id=>document.getElementById(id),U=DebtAgingUtils,esc=U.escape;
  const allowed=()=>Auth.hasPermission('REPORT_FINANCE')&&MenuConfig.getMenuForUser(Auth.getUser()).some(item=>item.id==='debt-aging');
  const state={options:null,report:null,page:0,size:20,busy:false,exporting:false};
  let generation=0,controller=null;
  const message=(id,text='')=>{$(id).textContent=text;$(id).hidden=!text;};
  const count=value=>new Intl.NumberFormat('vi-VN').format(value);
  function controls(){
    const can=allowed(),ready=can&&DebtAgingApi.available&&!!state.options;
    $('aging-workspace').hidden=!can;$('aging-access').hidden=can;$('aging-pending').hidden=!can||DebtAgingApi.available;
    $('aging-reload').disabled=!can||state.exporting;
    for(const id of ['aging-as-of','aging-employee'])$(id).disabled=!ready||state.exporting;
    $('aging-load').disabled=!ready||state.busy||state.exporting;
    $('aging-export').disabled=!ready||!state.report||state.busy||state.exporting||!state.report.dealers.length;
    $('aging-size').disabled=!can||!state.report;
    $('aging-prev').disabled=!can||!state.report||state.page===0;
    $('aging-next').disabled=!can||!state.report||(state.page+1)*state.size>=state.report.dealers.length;
    $('aging-workspace').setAttribute('aria-busy',String(state.busy));
    $('aging-export').textContent=state.exporting?'Đang kiểm tra và xuất…':'Xuất Excel';
  }
  function cancel(){generation++;controller?.abort();controller=null;state.busy=false;state.exporting=false;}
  function clearReport(note='Chưa có báo cáo để hiển thị.'){
    state.report=null;state.page=0;
    for(const id of ['aging-total','aging-count','aging-oldest'])$(id).textContent='—';
    $('aging-snapshot').textContent=note;$('aging-risk-basis').textContent='Chờ tiêu chí và thứ hạng rủi ro từ máy chủ, không tự suy đoán từ số tiền.';
    $('aging-priority').innerHTML='<p class="aging-empty">Chưa có dữ liệu đánh giá rủi ro.</p>';
    $('aging-buckets').innerHTML='<tr><td colspan="3">Chưa có định nghĩa nhóm và số liệu từ máy chủ.</td></tr>';
    $('aging-employees-head').innerHTML='<tr><th scope="col">Nhân viên</th><th scope="col">Số đại lý</th><th scope="col">Tiền quá hạn</th></tr>';
    $('aging-employees').innerHTML='<tr><td colspan="3">Chưa có dữ liệu theo nhân viên.</td></tr>';
    $('aging-dealers-head').innerHTML='<tr><th scope="col">Hạng rủi ro</th><th scope="col">Đại lý</th><th scope="col">Nhân viên</th><th scope="col">Tiền quá hạn</th><th scope="col">Quá hạn lớn nhất</th></tr>';
    $('aging-dealers').innerHTML='<tr><td colspan="5">Chưa có báo cáo để hiển thị.</td></tr>';
    $('aging-dealer-count').textContent='';$('aging-page').textContent='Chưa có trang';controls();
  }
  function clearOptions(){state.options=null;$('aging-as-of').value='';$('aging-as-of').removeAttribute('max');$('aging-employee').innerHTML='<option value="all">Chờ danh sách nhân viên</option>';}
  const moneyCell=value=>`<td class="aging-money">${esc(U.money(value))}</td>`;
  function tablePage(){
    const data=state.report;if(!data)return;
    const topRank=data.dealers[0]?.riskRank,rows=data.dealers.slice(state.page*state.size,(state.page+1)*state.size);
    $('aging-dealers').innerHTML=rows.length?rows.map(row=>`<tr data-dealer="${row.dealerId}" class="${row.riskRank===topRank?'aging-top-row':''}"><td>${row.riskRank}<small>${esc(row.riskLabel)}</small></td><td>${esc(row.dealerName)}<small>${esc(row.dealerCode)}</small></td><td>${esc(row.employeeName)}</td>${data.buckets.map(bucket=>moneyCell(row.buckets[bucket.key])).join('')}${moneyCell(row.overdueAmount)}<td>${count(row.maxOverdueDays)} ngày</td></tr>`).join(''):`<tr><td colspan="${5+data.buckets.length}">Không có đại lý còn nợ quá hạn trong phạm vi báo cáo này.</td></tr>`;
    const pages=Math.ceil(data.dealers.length/state.size);
    $('aging-page').textContent=pages?`Trang ${state.page+1}/${pages}`:'0 trang';$('aging-dealer-count').textContent=`${count(data.dealers.length)} đại lý, xếp theo thứ hạng rủi ro`;controls();
  }
  function render(data){
    state.report=data;state.page=0;
    $('aging-total').textContent=U.money(data.totals.overdueAmount);$('aging-count').textContent=count(data.totals.dealerCount);$('aging-oldest').textContent=count(data.totals.maxOverdueDays)+' ngày';
    const timestamp=new Intl.DateTimeFormat('vi-VN',{timeZone:data.timezone,dateStyle:'short',timeStyle:'medium'}).format(new Date(data.generatedAt));
    const employee=data.filters.unassignedOnly?'Chưa phân công':data.filters.employeeId===null?'Tất cả nhân viên':state.options.employees.find(row=>row.id===data.filters.employeeId).name;
    $('aging-snapshot').textContent=`Ngày chốt ${U.dateLabel(data.asOfDate)}; ${employee}. Dữ liệu tạo lúc ${timestamp} (${data.timezone}).`;
    $('aging-risk-basis').textContent='Tiêu chí xếp hạng từ máy chủ: '+data.riskBasis;
    const top=data.dealers.filter(row=>row.riskRank===data.dealers[0]?.riskRank);
    $('aging-priority').innerHTML=top.length?top.slice(0,3).map(row=>`<div class="aging-priority-row"><div><button type="button" data-priority="${row.dealerId}">${esc(row.dealerName)}</button><small>${esc(row.dealerCode)} · ${esc(row.employeeName)} · Quá hạn lớn nhất ${count(row.maxOverdueDays)} ngày</small></div><span>${esc(U.money(row.overdueAmount))} <span class="aging-risk">${esc(row.riskLabel)}</span></span></div>`).join('')+(top.length>3?`<p class="aging-help">Có ${count(top.length)} đại lý cùng hạng ưu tiên cao nhất. Xem đầy đủ trong danh sách.</p>`:''):'<p class="aging-empty">Không có đại lý quá hạn để ưu tiên trong phạm vi này.</p>';
    $('aging-buckets').innerHTML=data.bucketTotals.map((row,index)=>`<tr><td>${esc(data.buckets[index].label)}</td>${moneyCell(row.amount)}<td>${count(row.dealerCount)}</td></tr>`).join('');
    const bucketHeaders=data.buckets.map(bucket=>`<th scope="col">${esc(bucket.label)}</th>`).join('');
    $('aging-employees-head').innerHTML=`<tr><th scope="col">Nhân viên</th><th scope="col">Số đại lý</th>${bucketHeaders}<th scope="col">Tiền quá hạn</th></tr>`;
    $('aging-employees').innerHTML=data.employeeTotals.length?data.employeeTotals.map(row=>`<tr><td>${esc(row.employeeName)}</td><td>${count(row.dealerCount)}</td>${data.buckets.map(bucket=>moneyCell(row.buckets[bucket.key])).join('')}${moneyCell(row.overdueAmount)}</tr>`).join(''):`<tr><td colspan="${3+data.buckets.length}">Không có công nợ quá hạn theo nhân viên.</td></tr>`;
    $('aging-dealers-head').innerHTML=`<tr><th scope="col" aria-sort="ascending">Hạng rủi ro</th><th scope="col">Đại lý</th><th scope="col">Nhân viên</th>${bucketHeaders}<th scope="col">Tiền quá hạn</th><th scope="col">Quá hạn lớn nhất</th></tr>`;
    tablePage();
  }
  async function initialize(){
    cancel();clearOptions();clearReport();message('aging-error');controls();
    if(!allowed()||!DebtAgingApi.available)return;
    const version=generation,signal=(controller=new AbortController()).signal;state.busy=true;controls();
    try{
      const options=U.options(await DebtAgingApi.options({signal}));
      if(version!==generation||signal.aborted||!allowed())return;
      state.options=options;$('aging-as-of').value=options.defaultAsOfDate;$('aging-as-of').max=options.defaultAsOfDate;
      $('aging-employee').innerHTML='<option value="all">Tất cả nhân viên</option><option value="unassigned">Chưa phân công</option>'+options.employees.map(row=>`<option value="${row.id}">${esc(row.name+' ('+row.code+')')}</option>`).join('');
      $('aging-snapshot').textContent='Bộ lọc đã sẵn sàng. Bấm Xem báo cáo để tải dữ liệu.';
    }catch(error){if(version===generation&&error.name!=='AbortError')message('aging-error',error.message);}
    finally{if(version===generation){state.busy=false;controls();}}
  }
  async function load(event){
    event?.preventDefault();if(!allowed()||!DebtAgingApi.available||!state.options||state.busy||state.exporting)return;
    let filters;try{filters=U.filters($('aging-as-of').value,$('aging-employee').value,state.options);}catch(error){message('aging-error',error.message);return;}
    cancel();const version=generation,signal=(controller=new AbortController()).signal;
    clearReport('Đang tải báo cáo…');message('aging-error');state.busy=true;controls();
    try{
      const response=await DebtAgingApi.report(filters,{signal});
      if(version!==generation||signal.aborted||!allowed())return;
      render(U.report(response,filters,state.options));
    }catch(error){if(version===generation&&error.name!=='AbortError'){clearReport('Chưa tải được báo cáo. Không có số liệu để kết luận.');message('aging-error',error.message);}}
    finally{if(version===generation){state.busy=false;controls();}}
  }
  async function exportExcel(){
    if(!allowed()||!state.report||state.busy||state.exporting||!state.report.dealers.length)return;
    const version=generation,snapshot=state.report;state.exporting=true;controls();message('aging-error');
    try{
      if(!await Auth.syncPermissions()||!allowed())throw Error('Không xác minh được quyền xuất báo cáo.');
      if(version!==generation||snapshot!==state.report)return;
      const book=U.workbook(snapshot,XLSX),bytes=XLSX.write(book,{bookType:'xlsx',type:'array'});
      if(!await Auth.syncPermissions()||!allowed())throw Error('Không xác minh được quyền xuất báo cáo.');
      if(version!==generation||snapshot!==state.report)return;
      const url=URL.createObjectURL(new Blob([bytes],{type:'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'}));
      const link=document.createElement('a');link.href=url;link.download=`Cong-no-tuoi-no_${snapshot.asOfDate}_${snapshot.filters.unassignedOnly?'chua-phan-cong':snapshot.filters.employeeId??'tat-ca'}.xlsx`;document.body.append(link);link.click();link.remove();setTimeout(()=>URL.revokeObjectURL(url),1000);
    }catch(error){if(version===generation&&allowed())message('aging-error',error.message);}
    finally{if(version===generation){state.exporting=false;controls();}}
  }
  $('aging-filters').addEventListener('submit',load);$('aging-reload').addEventListener('click',initialize);$('aging-export').addEventListener('click',exportExcel);
  for(const id of ['aging-as-of','aging-employee'])$(id).addEventListener('input',()=>{cancel();clearReport('Bộ lọc đã thay đổi. Bấm Xem báo cáo để tải lại.');message('aging-error');});
  $('aging-group').addEventListener('change',()=>{const employee=$('aging-group').value==='employee';$('aging-bucket-view').hidden=employee;$('aging-employee-view').hidden=!employee;});
  $('aging-size').addEventListener('change',()=>{const size=Number($('aging-size').value);if(!allowed()||![20,50,100].includes(size)||!state.report)return;state.size=size;state.page=0;tablePage();});
  $('aging-prev').addEventListener('click',()=>{if(allowed()&&state.report&&state.page>0){state.page--;tablePage();}});
  $('aging-next').addEventListener('click',()=>{if(allowed()&&state.report&&(state.page+1)*state.size<state.report.dealers.length){state.page++;tablePage();}});
  $('aging-priority').addEventListener('click',event=>{const button=event.target.closest('[data-priority]');if(!button||!allowed()||!state.report)return;const index=state.report.dealers.findIndex(row=>String(row.dealerId)===button.dataset.priority);if(index<0)return;state.page=Math.floor(index/state.size);tablePage();const row=$('aging-dealers').querySelector(`[data-dealer="${Number(button.dataset.priority)}"]`);row?.scrollIntoView({block:'center',behavior:'instant'});if(row){row.tabIndex=-1;row.focus({preventScroll:true});}});
  window.addEventListener('auth:permissions-changed',()=>{state.exporting=false;initialize();});
  window.addEventListener('pagehide',()=>{cancel();clearOptions();clearReport();});
  window.addEventListener('pageshow',event=>{if(event.persisted)initialize();});
  initialize();
})();
