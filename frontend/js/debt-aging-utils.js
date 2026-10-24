/* Presentation/validation only: no age-bucket or business-risk calculation. */
const DebtAgingUtils = (() => {
  const escape = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const fail = message => { throw new Error(message || 'Dữ liệu báo cáo tuổi nợ chưa đầy đủ hoặc không nhất quán.'); };
  function amount(value) {
    // The agreed future adapter must preserve BigDecimal tokens as strings.
    if (typeof value !== 'string' || !/^\d{1,35}(?:\.\d{1,2})?$/.test(value)) fail('Số tiền báo cáo chưa hợp lệ; cần chuỗi thập phân chính xác.');
    const [whole, fraction=''] = value.split('.');
    return BigInt(whole)*100n + BigInt(fraction.padEnd(2,'0'));
  }
  function decimal(value) { return `${value/100n}.${String(value%100n).padStart(2,'0')}`; }
  function money(value) {
    const cents=amount(value), fraction=cents%100n;
    return new Intl.NumberFormat('vi-VN').format(cents/100n)+(fraction?','+String(fraction).padStart(2,'0'):'')+' ₫';
  }
  function integer(value, min=0) { if (!Number.isSafeInteger(value)||value<min) fail(); return value; }
  function text(value) { if (typeof value!=='string'||!value.trim()||value.length>1000) fail(); return value; }
  function date(value) {
    if (typeof value!=='string'||!/^\d{4}-\d{2}-\d{2}$/.test(value)) fail('Ngày chốt báo cáo chưa hợp lệ.');
    const parsed=new Date(value+'T00:00:00Z');
    if (!Number.isFinite(parsed.getTime())||parsed.toISOString().slice(0,10)!==value) fail('Ngày chốt báo cáo chưa hợp lệ.');
    return value;
  }
  const dateLabel=value=>date(value).split('-').reverse().join('/');
  function zone(value) { text(value); try { new Intl.DateTimeFormat('vi-VN',{timeZone:value}); } catch { fail('Múi giờ báo cáo chưa hợp lệ.'); } }
  function bucketDefinitions(buckets) {
    if (!Array.isArray(buckets)||!buckets.length||buckets.length>20) fail('Chưa có định nghĩa nhóm tuổi nợ từ máy chủ.');
    const keys=new Set();let next=1;
    buckets.forEach((bucket,index)=>{
      if (!bucket||!/^[a-zA-Z][a-zA-Z0-9_]{0,39}$/.test(bucket.key)||keys.has(bucket.key)) fail();
      keys.add(bucket.key);text(bucket.label);integer(bucket.minDays,1);
      if (bucket.minDays!==next) fail('Nhóm tuổi nợ bị thiếu hoặc chồng lấn.');
      if (bucket.maxDays===null) { if(index!==buckets.length-1)fail();next=null; }
      else { integer(bucket.maxDays,bucket.minDays);next=bucket.maxDays+1; }
    });
    if (next!==null) fail('Nhóm cuối phải bao gồm toàn bộ tuổi nợ còn lại.');
    return buckets;
  }
  function options(data) {
    if (!data||!Array.isArray(data.employees)||data.employees.length>10000) fail();
    date(data.defaultAsOfDate);zone(data.timezone);bucketDefinitions(data.buckets);
    const ids=new Set();
    for(const employee of data.employees){integer(employee.id,1);text(employee.code);text(employee.name);if(ids.has(employee.id))fail();ids.add(employee.id);}
    return data;
  }
  function filters(asOfDate, employeeValue, opts) {
    date(asOfDate);
    if (asOfDate>opts.defaultAsOfDate) fail('Không chọn ngày sau ngày chốt tối đa do máy chủ cung cấp.');
    if (employeeValue==='all') return {asOfDate,employeeId:null,unassignedOnly:false};
    if (employeeValue==='unassigned') return {asOfDate,employeeId:null,unassignedOnly:true};
    if (!/^\d+$/.test(employeeValue)||!opts.employees.some(row=>String(row.id)===employeeValue)) fail('Chọn nhân viên trong danh sách được cấp quyền.');
    return {asOfDate,employeeId:Number(employeeValue),unassignedOnly:false};
  }
  function sameFilters(a,b) { return a?.asOfDate===b?.asOfDate&&a?.employeeId===b?.employeeId&&a?.unassignedOnly===b?.unassignedOnly; }
  function bucketAmounts(values,buckets) {
    if(!values||typeof values!=='object'||Array.isArray(values)||Object.keys(values).length!==buckets.length)fail();
    return buckets.reduce((sum,bucket)=>sum+amount(values[bucket.key]),0n);
  }
  function report(data,requested,opts) {
    if(!data||data.complete!==true||data.currency!=='VND'||!Array.isArray(data.dealers)||data.dealers.length>5000||!Array.isArray(data.employeeTotals)||!Array.isArray(data.bucketTotals)||!data.totals) fail('Báo cáo chưa đầy đủ. Không thể hiển thị/xuất một phần như toàn bộ dữ liệu.');
    if(!sameFilters(data.filters,requested)||data.asOfDate!==requested.asOfDate)fail('Báo cáo không khớp bộ lọc đã yêu cầu.');
    date(data.asOfDate);zone(data.timezone);if(data.timezone!==opts.timezone)fail();
    if(typeof data.generatedAt!=='string'||!/(Z|[+-]\d{2}:\d{2})$/.test(data.generatedAt)||!Number.isFinite(Date.parse(data.generatedAt)))fail();
    text(data.riskBasis);bucketDefinitions(data.buckets);
    if(data.buckets.length!==opts.buckets.length||data.buckets.some((bucket,index)=>['key','label','minDays','maxDays'].some(key=>bucket[key]!==opts.buckets[index][key])))fail('Định nghĩa nhóm tuổi nợ đã thay đổi. Hãy tải lại bộ lọc.');
    const buckets=data.buckets, ids=new Set(), employees=new Map(), bucketSums=new Map(buckets.map(row=>[row.key,{amount:0n,count:0}]));
    let total=0n,maxDays=0,lastRank=0;
    for(const row of data.dealers){
      integer(row.dealerId,1);text(row.dealerCode);text(row.dealerName);text(row.employeeName);text(row.riskLabel);integer(row.riskRank,1);integer(row.maxOverdueDays,1);
      if(ids.has(row.dealerId)||row.riskRank<lastRank)fail('Danh sách rủi ro bị trùng hoặc chưa đúng thứ tự từ máy chủ.');
      ids.add(row.dealerId);lastRank=row.riskRank;
      if(row.employeeId!==null&&!opts.employees.some(employee=>employee.id===row.employeeId))fail('Nhân viên trong báo cáo nằm ngoài danh sách được cấp quyền.');
      if((requested.unassignedOnly&&row.employeeId!==null)||(requested.employeeId!==null&&row.employeeId!==requested.employeeId))fail('Báo cáo chứa đại lý ngoài bộ lọc.');
      const overdue=amount(row.overdueAmount);if(overdue===0n||bucketAmounts(row.buckets,buckets)!==overdue)fail('Tổng tiền theo tuổi nợ không khớp đại lý.');
      total+=overdue;maxDays=Math.max(maxDays,row.maxOverdueDays);
      let group=employees.get(row.employeeId);
      if(!group){group={amount:0n,count:0,buckets:new Map(buckets.map(bucket=>[bucket.key,0n]))};employees.set(row.employeeId,group);}
      group.amount+=overdue;group.count++;
      for(const bucket of buckets){const value=amount(row.buckets[bucket.key]);const sum=bucketSums.get(bucket.key);sum.amount+=value;if(value>0n)sum.count++;group.buckets.set(bucket.key,group.buckets.get(bucket.key)+value);}
    }
    if(amount(data.totals.overdueAmount)!==total||integer(data.totals.dealerCount)!==data.dealers.length||integer(data.totals.maxOverdueDays)!==maxDays)fail('Tổng báo cáo không khớp chi tiết.');
    if(data.bucketTotals.length!==buckets.length)fail();
    data.bucketTotals.forEach((row,index)=>{const expected=bucketSums.get(buckets[index].key);if(row.bucketKey!==buckets[index].key||amount(row.amount)!==expected.amount||integer(row.dealerCount)!==expected.count)fail('Tổng nhóm tuổi nợ không khớp chi tiết.');});
    const employeeIds=new Set();
    for(const row of data.employeeTotals){
      const expected=employees.get(row.employeeId);text(row.employeeName);
      if(!expected||employeeIds.has(row.employeeId)||amount(row.overdueAmount)!==expected.amount||integer(row.dealerCount)!==expected.count)fail('Tổng nhân viên không khớp chi tiết.');
      employeeIds.add(row.employeeId);bucketAmounts(row.buckets,buckets);
      for(const bucket of buckets)if(amount(row.buckets[bucket.key])!==expected.buckets.get(bucket.key))fail();
    }
    if(employeeIds.size!==employees.size)fail('Tổng nhân viên chưa đầy đủ.');
    return data;
  }
  function sheets(data) {
    // All cells, including external text and decimal amounts, remain strings.
    const moneyColumns=row=>data.buckets.map(bucket=>decimal(amount(row.buckets[bucket.key])));
    return [
      {name:'Thong tin',rows:[['Báo cáo công nợ quá hạn theo tuổi nợ'],['Ngày chốt',data.asOfDate],['Thời điểm tạo',data.generatedAt],['Múi giờ',data.timezone],['Đơn vị','VND; tiền lưu dạng văn bản để giữ đủ chữ số'],['Nhân viên',data.filters.unassignedOnly?'Chưa phân công':data.filters.employeeId===null?'Tất cả':String(data.filters.employeeId)],['Tiêu chí rủi ro',data.riskBasis],['Tổng quá hạn',decimal(amount(data.totals.overdueAmount))],['Số đại lý',String(data.totals.dealerCount)],['Số ngày quá hạn lớn nhất',String(data.totals.maxOverdueDays)]]},
      {name:'Nhom tuoi no',rows:[['Nhóm tuổi nợ','Từ ngày quá hạn','Đến ngày quá hạn','Tiền quá hạn (VND)','Số đại lý'],...data.bucketTotals.map((row,i)=>[data.buckets[i].label,String(data.buckets[i].minDays),data.buckets[i].maxDays===null?'Không giới hạn':String(data.buckets[i].maxDays),decimal(amount(row.amount)),String(row.dealerCount)])]},
      {name:'Nhan vien',rows:[['Nhân viên','Số đại lý',...data.buckets.map(bucket=>bucket.label),'Tiền quá hạn (VND)'],...data.employeeTotals.map(row=>[row.employeeName,String(row.dealerCount),...moneyColumns(row),decimal(amount(row.overdueAmount))])]},
      {name:'Dai ly rui ro',rows:[['Hạng rủi ro','Mã đại lý','Tên đại lý','Nhân viên',...data.buckets.map(bucket=>bucket.label),'Tiền quá hạn (VND)','Quá hạn lớn nhất (ngày)','Mức rủi ro'],...data.dealers.map(row=>[String(row.riskRank),row.dealerCode,row.dealerName,row.employeeName,...moneyColumns(row),decimal(amount(row.overdueAmount)),String(row.maxOverdueDays),row.riskLabel])]},
    ];
  }
  function workbook(data,xlsx) {
    const book=xlsx.utils.book_new();
    for(const sheet of sheets(data)){
      const worksheet=xlsx.utils.aoa_to_sheet(sheet.rows);
      for(const [key,cell]of Object.entries(worksheet)){if(key.startsWith('!'))continue;cell.t='s';cell.v=String(cell.v??'');delete cell.f;delete cell.l;}
      worksheet['!cols']=sheet.rows[0].map((_,index)=>({wch:index===0?28:24}));
      xlsx.utils.book_append_sheet(book,worksheet,sheet.name);
    }
    return book;
  }
  return {escape,amount,decimal,money,date,dateLabel,options,filters,sameFilters,report,sheets,workbook};
})();
if(typeof module!=='undefined')module.exports=DebtAgingUtils;
