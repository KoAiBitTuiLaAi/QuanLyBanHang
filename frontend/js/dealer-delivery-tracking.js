/** S6-09 frontend adapter. Only the scoped /portal/orders/{id} response is consumed.
 * Unknown history is NOT an upcoming/finished event. No warehouse/tracking API calls. */
const DealerDeliveryTracking = (() => {
  const escape = v => String(v ?? '').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const quantity = v => !['number','string'].includes(typeof v) || String(v).trim()==='' || !Number.isFinite(Number(v)) || Number(v)<0
    ? 'Chưa cập nhật' : new Intl.NumberFormat('vi-VN',{maximumFractionDigits:4}).format(Number(v));
  function timestamp(v) {
    if(typeof v!=='string' || !/^\d{4}-\d{2}-\d{2}(?:T.*)?$/.test(v) || !Number.isFinite(new Date(v).getTime()))return null;
    const dateOnly=/^\d{4}-\d{2}-\d{2}$/.test(String(v));
    const date=new Date(dateOnly?v+'T00:00:00':v);
    return {value:String(v),label:new Intl.DateTimeFormat('vi-VN',dateOnly?{day:'2-digit',month:'2-digit',year:'numeric'}
      :{day:'2-digit',month:'2-digit',year:'numeric',hour:'2-digit',minute:'2-digit',second:'2-digit'}).format(date),dateOnly};
  }
  function model(order) {
    order=order || {};
    const status=String(order.status || '').toUpperCase();
    const approval=(Array.isArray(order.approvalHistory)?order.approvalHistory:[])
      .filter(h=>h && String(h.action).toUpperCase()==='APPROVED' && timestamp(h.timestamp || h.createdAt)).at(-1);
    // Legacy approvedAt is also set on rejection; only trust it in approved lifecycle states.
    const approvedAt=approval?.timestamp || approval?.createdAt ||
      (['APPROVED','RECORDED','PICKING','PROCESSING','EXPORTED','SHIPPED','SHIPPING','DELIVERED','CLOSED','COMPLETED','EXPIRED'].includes(status)?order.approvedAt:null);
    // OrderStatus.getDisplayName maps legacy SHIPPED to Đã xuất, NOT Đang giao.
    const steps=[
      {key:'approved',label:'Đã duyệt',current:['APPROVED','RECORDED'].includes(status),at:timestamp(approvedAt)},
      {key:'picking',label:'Đang soạn',current:['PICKING','PROCESSING'].includes(status),at:null},
      {key:'exported',label:'Đã xuất',current:['EXPORTED','SHIPPED'].includes(status),at:null},
      {key:'shipping',label:'Đang giao',current:status==='SHIPPING',at:null},
      {key:'delivered',label:'Đã giao',current:['DELIVERED','CLOSED','COMPLETED'].includes(status),at:['DELIVERED','CLOSED','COMPLETED'].includes(status)?timestamp(order.actualDeliveryDate):null},
    ].map(s=>({...s,state:s.current?'current':s.at?'recorded':'unknown'}));
    return {steps,backordered:order.backordered===true,
      stopped:['CANCELLED','REJECTED','EXPIRED','RETURNED'].includes(status),
      note:'Lịch sử giao hàng chưa đầy đủ. Chỉ hiển thị mốc đã được hệ thống xác nhận; “Chưa có dữ liệu” không có nghĩa là bước đó chưa diễn ra.'};
  }
  function render(order) {
    const data=model(order);
    return `<section class="dealer-delivery-tracking" aria-labelledby="dealer-delivery-tracking-title">
      <header class="dealer-delivery-tracking__heading"><h3 id="dealer-delivery-tracking-title" tabindex="-1">Theo dõi giao hàng</h3><span class="dealer-delivery-tracking__readonly">Chỉ xem</span></header>
      <p class="dealer-delivery-tracking__notice">${escape(data.note)}</p>
      ${data.backordered?'<p class="dealer-delivery-tracking__warning">Đơn còn nợ hàng. Đã xuất kho không đồng nghĩa đã xuất đủ hoặc đã giao đủ.</p>':''}
      ${data.stopped?'<p class="dealer-delivery-tracking__warning">Đơn đang dừng xử lý theo trạng thái hiện tại. Không tự đánh dấu các mốc giao hàng đã hoàn thành.</p>':''}
      <ol class="dealer-delivery-timeline">${data.steps.map(s=>`<li class="dealer-delivery-timeline__step dealer-delivery-timeline__step--${s.state}" data-delivery-milestone="${s.key}">
        <span class="dealer-delivery-timeline__marker" aria-hidden="true"></span><div><strong>${escape(s.label)}</strong>
        <span class="dealer-delivery-timeline__state">${s.current?'Trạng thái hiện tại':s.at?'Có dữ liệu xác nhận':'Chưa có dữ liệu mốc này'}</span>
        ${s.at?`<time datetime="${escape(s.at.value)}">${escape(s.at.label)}</time>${s.at.dateOnly?'<small>Chỉ có ngày, chưa có giờ xác nhận.</small>':''}`
          :`<small>${s.current?'Chưa có thời gian xác nhận từ hệ thống.':'Chưa có thời gian xác nhận cho mốc này.'}</small>`}</div></li>`).join('')}</ol>
      <p class="dealer-delivery-tracking__notice">Ngày giao dự kiến chưa thể hiện giờ xe tới.</p>
    </section>`;
  }
  return {model,render,timestamp,quantity};
})();
if(typeof module!=='undefined')module.exports=DealerDeliveryTracking;
