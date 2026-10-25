// Synthetic S8-09 API responses used only by isolated frontend tests.
const U=require('../frontend/js/debt-aging-utils.js');
function options(){return {defaultAsOfDate:'2026-10-24',timezone:'Asia/Ho_Chi_Minh',buckets:[{key:'recent',label:'Quá hạn 1–14 ngày',minDays:1,maxDays:14},{key:'middle',label:'Quá hạn 15–44 ngày',minDays:15,maxDays:44},{key:'old',label:'Quá hạn từ 45 ngày',minDays:45,maxDays:null}],employees:[{id:1,code:'NV01',name:'Nguyễn An'},{id:2,code:'NV02',name:'Trần Bình'}]};}
function report(filters={asOfDate:'2026-10-24',employeeId:null,unassignedOnly:false},size=45){
  const opts=options();
  const dealers=Array.from({length:size},(_,i)=>{
    const employeeId=[1,2,null][i%3],buckets={recent:'0.00',middle:'0.00',old:'0.00'};
    buckets[i===0?'old':opts.buckets[i%3].key]=i===0?'100.25':i===1?'9007199254740993.25':String((i+1)*10000);
    if(i===0)buckets.recent='50.00';
    const overdue=Object.values(buckets).reduce((sum,value)=>sum+U.amount(value),0n);
    return {dealerId:i+1,dealerCode:'DL-'+(i+1),dealerName:'Đại lý '+(i+1),employeeId,employeeName:opts.employees.find(row=>row.id===employeeId)?.name??'Chưa phân công',buckets,overdueAmount:U.decimal(overdue),maxOverdueDays:i===0?90:[10,30,50][i%3],riskRank:i+1,riskLabel:i===0?'Ưu tiên cao':'Theo dõi'};
  }).filter(row=>(filters.employeeId===null||row.employeeId===filters.employeeId)&&(!filters.unassignedOnly||row.employeeId===null));
  const employeeTotals=[];
  for(const id of [1,2,null]){
    const rows=dealers.filter(row=>row.employeeId===id);if(!rows.length)continue;
    employeeTotals.push({employeeId:id,employeeName:rows[0].employeeName,dealerCount:rows.length,overdueAmount:U.decimal(rows.reduce((sum,row)=>sum+U.amount(row.overdueAmount),0n)),buckets:Object.fromEntries(opts.buckets.map(bucket=>[bucket.key,U.decimal(rows.reduce((sum,row)=>sum+U.amount(row.buckets[bucket.key]),0n))]))});
  }
  return {asOfDate:filters.asOfDate,generatedAt:'2026-10-24T08:30:00+07:00',timezone:opts.timezone,currency:'VND',filters:{...filters},complete:true,buckets:opts.buckets,riskBasis:'Số ngày quá hạn giảm dần, sau đó tiêu chí rủi ro của máy chủ; không xếp theo số tiền.',totals:{overdueAmount:U.decimal(dealers.reduce((sum,row)=>sum+U.amount(row.overdueAmount),0n)),dealerCount:dealers.length,maxOverdueDays:Math.max(0,...dealers.map(row=>row.maxOverdueDays))},bucketTotals:opts.buckets.map(bucket=>({bucketKey:bucket.key,amount:U.decimal(dealers.reduce((sum,row)=>sum+U.amount(row.buckets[bucket.key]),0n)),dealerCount:dealers.filter(row=>U.amount(row.buckets[bucket.key])>0n).length})),employeeTotals,dealers};
}
module.exports={options,report};
