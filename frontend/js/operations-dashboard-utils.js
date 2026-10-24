/* Exact financial text; Number is used only for bounded chart geometry. */
const OperationsDashboardUtils = (() => {
  const escape = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  function cents(value) {
    const text = String(value ?? '');
    if (!/^\d+(?:\.\d{1,2})?$/.test(text)) throw Error('Máy chủ trả số tiền không hợp lệ.');
    const [whole,fraction=''] = text.split('.');
    return BigInt(whole) * 100n + BigInt(fraction.padEnd(2,'0'));
  }
  function money(value) {
    const amount = cents(value), fraction = amount % 100n;
    return new Intl.NumberFormat('vi-VN').format(amount / 100n) + (fraction ? ',' + String(fraction).padStart(2,'0') : '') + ' ₫';
  }
  function count(value) {
    if (!Number.isSafeInteger(value) || value < 0) throw Error('Máy chủ trả số lượng không hợp lệ.');
    return new Intl.NumberFormat('vi-VN').format(value);
  }
  function parse(text) {
    return JSON.parse(text.replace(/("(?:todaySales|monthSales|overdueDebt|sales|currentDebt|overdueAmount)"\s*:\s*)(\d+(?:\.\d+)?)(?=\s*[,}])/g,'$1"$2"'));
  }
  function date(value) {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) throw Error('Ngày dashboard không hợp lệ.');
    const parsed = new Date(value+'T00:00:00Z');
    if (!Number.isFinite(parsed.getTime()) || parsed.toISOString().slice(0,10)!==value) throw Error('Ngày dashboard không hợp lệ.');
    return value;
  }
  const dateLabel = value => date(value).split('-').reverse().join('/');
  function monthDays(month) {
    if (!/^\d{4}-(0[1-9]|1[0-2])$/.test(month)) throw Error('Tháng dashboard không hợp lệ.');
    const year=Number(month.slice(0,4));
    if (year<1000) throw Error('Năm dashboard không hợp lệ.');
    return new Date(Date.UTC(year,Number(month.slice(5,7)),0)).getUTCDate();
  }
  function dealers(rows) {
    if (!Array.isArray(rows)) throw Error('Danh sách đại lý quá hạn chưa hợp lệ.');
    const ids=new Set(); let previous=null;
    for (const row of rows) {
      if (!Number.isSafeInteger(row.dealerId) || row.dealerId < 1 || ids.has(row.dealerId) || typeof row.dealerName !== 'string' || typeof row.dealerCode !== 'string') throw Error('Đại lý quá hạn thiếu hoặc trùng định danh.');
      ids.add(row.dealerId); cents(row.currentDebt); const amount=cents(row.overdueAmount); count(row.maxOverdueDays);
      if (amount <= 0n || (previous !== null && amount > previous)) throw Error('Danh sách quá hạn chưa được sắp xếp đúng theo số tiền.');
      previous=amount;
    }
    return rows;
  }
  function overview(data, limit=20) {
    if (!data || !data.metrics || !Array.isArray(data.dailySales) || !Array.isArray(data.territorySales)) throw Error('Dữ liệu dashboard chưa đầy đủ.');
    date(data.date); const length=monthDays(data.month);
    if (!data.date.startsWith(data.month+'-') || data.dailySales.length!==length) throw Error('Biểu đồ ngày chưa đủ tháng do máy chủ cung cấp.');
    if (!Number.isFinite(Date.parse(data.generatedAt)) || !/(Z|[+-]\d{2}:\d{2})$/.test(data.generatedAt) || typeof data.timezone !== 'string') throw Error('Thời điểm dashboard không hợp lệ.');
    new Intl.DateTimeFormat('vi-VN',{timeZone:data.timezone});
    for (const key of ['todaySales','monthSales','overdueDebt']) cents(data.metrics[key]);
    for (const key of ['pendingApprovalOrders','pendingExportOrders','belowMinimumSkus']) count(data.metrics[key]);
    data.dailySales.forEach((row,index) => { if (row.date!==`${data.month}-${String(index+1).padStart(2,'0')}`) throw Error('Các ngày trên biểu đồ bị thiếu hoặc trùng.'); cents(row.sales); });
    const territories=new Set();
    for (const row of data.territorySales) {
      if (row.territoryId!==null && (!Number.isSafeInteger(row.territoryId) || row.territoryId<1)) throw Error('Khu vực không hợp lệ.');
      if (territories.has(row.territoryId) || typeof row.territoryName!=='string') throw Error('Khu vực bị trùng hoặc thiếu tên.');
      territories.add(row.territoryId); cents(row.sales);
    }
    count(data.totalOverdueDealers); dealers(data.overdueDealers);
    if (data.overdueDealers.length!==Math.min(limit,data.totalOverdueDealers)) throw Error('Danh sách đầu chưa khớp tổng đại lý quá hạn.');
    return data;
  }
  function page(data, requestedPage, size) {
    if (!data || !Array.isArray(data.content) || !Number.isSafeInteger(data.totalPages) || data.totalPages<0) throw Error('Trang đại lý quá hạn không hợp lệ.');
    count(data.totalElements);
    const expectedLength=Math.max(0,Math.min(size,data.totalElements-requestedPage*size));
    if (data.totalPages!==Math.ceil(data.totalElements/size) || data.content.length!==expectedLength || (data.number!=null && data.number!==requestedPage)) throw Error('Thông tin phân trang chưa khớp yêu cầu.');
    dealers(data.content); return data;
  }
  function ratio(value,max) { return max===0n ? 0 : Number(value * 100000n / max) / 100000; }
  function compact(value) {
    const amount=cents(value);
    for (const [unit,label] of [[100000000000000n,'nghìn tỷ'],[100000000000n,'tỷ'],[100000000n,'triệu'],[100000n,'nghìn']]) {
      if (amount>=unit) { const scaled=amount*10n/unit;return `${scaled/10n}${scaled%10n ? ','+scaled%10n : ''} ${label}`; }
    }
    return new Intl.NumberFormat('vi-VN').format(amount/100n);
  }
  const decimalFromCents = value => `${value/100n}.${String(value%100n).padStart(2,'0')}`;
  return {escape,cents,money,count,parse,date,dateLabel,monthDays,dealers,overview,page,ratio,compact,decimalFromCents};
})();
if (typeof module!=='undefined') module.exports=OperationsDashboardUtils;
