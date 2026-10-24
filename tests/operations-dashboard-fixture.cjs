// Isolated HTTP fixtures, never imported by the application.
function dealer(index=0) {
  return {dealerId:index+1,dealerCode:'DL-'+(index+1),dealerName:'Đại lý '+(index+1),territoryId:1,territoryName:'Hà Nội',currentDebt:String(10000000-index*1000),overdueAmount:String(9000000-index*1000),maxOverdueDays:30-index%10};
}
function overview(total=45,month='2026-10',day=24) {
  const length=new Date(Date.UTC(Number(month.slice(0,4)),Number(month.slice(5)),0)).getUTCDate();
  return {date:month+'-'+String(day).padStart(2,'0'),month,generatedAt:month+'-'+String(day).padStart(2,'0')+'T08:00:00+07:00',timezone:'Asia/Ho_Chi_Minh',
    metrics:{todaySales:'3500000.25',monthSales:'125000000.75',pendingApprovalOrders:6,pendingExportOrders:8,overdueDebt:'21000000',belowMinimumSkus:3},
    dailySales:Array.from({length},(_,i)=>({date:month+'-'+String(i+1).padStart(2,'0'),sales:i<day?String((i+1)*200000):'0'})),
    territorySales:[{territoryId:1,territoryName:'Hà Nội',sales:'90000000.75'},{territoryId:2,territoryName:'Miền Trung',sales:'30000000'},{territoryId:null,territoryName:'Chưa phân khu vực',sales:'5000000'}],
    overdueDealers:Array.from({length:Math.min(20,total)},(_,i)=>dealer(i)),totalOverdueDealers:total};
}
module.exports={dealer,overview};
