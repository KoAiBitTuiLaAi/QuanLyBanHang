/** Pure formatting/validation only. Money, delivered quantities and invoice numbers come from backend. */
const InvoiceUtils=(()=>{
  const escape=value=>String(value??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const numeric=value=>['string','number'].includes(typeof value)&&String(value).trim()!==''&&Number.isFinite(Number(value));
  const quantity=value=>numeric(value)?new Intl.NumberFormat('vi-VN',{maximumFractionDigits:4}).format(Number(value)):'Chưa có dữ liệu';
  const money=value=>numeric(value)?new Intl.NumberFormat('vi-VN',{style:'currency',currency:'VND',maximumFractionDigits:2}).format(Number(value)):'—';
  const id=value=>/^\d+$/.test(String(value))&&Number.isSafeInteger(Number(value))&&Number(value)>0?Number(value):null;
  function date(value){
    if(typeof value!=='string'||!/^\d{4}-\d{2}-\d{2}(?:T.*)?$/.test(value))return 'Chưa có dữ liệu';
    const dateOnly=value.length===10,d=new Date(dateOnly?value+'T00:00:00':value);
    return Number.isFinite(d.getTime())?new Intl.DateTimeFormat('vi-VN',dateOnly?{dateStyle:'short'}:{dateStyle:'short',timeStyle:'short'}).format(d):'Chưa có dữ liệu';
  }
  function period(from,to){if(from&&to&&from>to)throw Error('Ngày bắt đầu phải trước hoặc bằng ngày kết thúc.');}
  function selection(dealerId,orderIds){
    const dealer=id(dealerId),orders=[...new Set(orderIds.map(id))];
    if(!dealer||!orders.length||orders.some(v=>v===null))throw Error('Chọn đại lý và ít nhất một phiếu giao hợp lệ.');
    return {dealerId:dealer,orderIds:orders.sort((a,b)=>a-b)};
  }
  function signature(data){
    return JSON.stringify({dealerId:data.dealerId,totalAmount:data.totalAmount,subtotalAmount:data.subtotalAmount,discountAmount:data.discountAmount,
      dealerName:data.dealerName,dealerTaxCode:data.dealerTaxCode,dealerAddress:data.dealerAddress,
      orders:[...(data.orders||[])].map(o=>o.orderId).sort((a,b)=>a-b),
      items:[...(data.items||[])].map(i=>({orderItemId:i.orderItemId,invoicedQuantity:i.invoicedQuantity,unitPrice:i.unitPrice,totalAmount:i.totalAmount,discountAmount:i.discountAmount})).sort((a,b)=>a.orderItemId-b.orderItemId)});
  }
  function adjustment(type,reason,amount,info={}){
    if(!['INCREASE','DECREASE','INFORMATION','CANCEL'].includes(type))throw Error('Loại điều chỉnh không hợp lệ.');
    if(!String(reason??'').trim()||String(reason).length>500)throw Error('Nhập lý do điều chỉnh, tối đa 500 ký tự.');
    const data={adjustmentType:type,adjustmentReason:String(reason).trim()};
    if(['INCREASE','DECREASE'].includes(type)){
      if(!numeric(amount)||Number(amount)<=0||!/^\d+(\.\d{1,2})?$/.test(String(amount)))throw Error('Nhập số tiền dương, tối đa 2 chữ số thập phân.');
      data.adjustmentAmount=String(amount);
    }else if(type==='INFORMATION'){
      for(const key of ['newDealerName','newDealerTaxCode','newDealerAddress'])if(String(info[key]||'').trim())data[key]=String(info[key]).trim();
      if(Object.keys(data).length===2)throw Error('Nhập ít nhất một thông tin mới của đại lý.');
    }
    return data;
  }
  return {escape,numeric,quantity,money,id,date,period,selection,signature,adjustment};
})();
if(typeof module!=='undefined')module.exports=InvoiceUtils;
