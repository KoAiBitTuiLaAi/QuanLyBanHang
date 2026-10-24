const assert=require('node:assert/strict'),{test}=require('node:test'),fs=require('node:fs'),path=require('node:path');
const U=require('../frontend/js/operations-dashboard-utils.js'),F=require('./operations-dashboard-fixture.cjs');
test('S8-05 preserves exact BigDecimal numeric JSON and fraction formatting beyond safe integer',()=>{
  const parsed=U.parse('{"todaySales":9007199254740993.25,"sales":0,"pendingApprovalOrders":6}');
  assert.equal(parsed.todaySales,'9007199254740993.25');assert.equal(parsed.sales,'0');assert.equal(parsed.pendingApprovalOrders,6);
  assert.equal(U.money(parsed.todaySales),'9.007.199.254.740.993,25 ₫');assert.equal(U.cents('0.01'),1n);assert.equal(U.money('0.00'),'0 ₫');
  for(const value of ['-1','NaN','1e4','1.001',null,''])assert.throws(()=>U.cents(value));
});
test('S8-05 respects all server month days and leap year, never invents or duplicates days',()=>{
  for(const [month,length]of [['2026-10',31],['2026-02',28],['2028-02',29],['2026-04',30]]){
    assert.equal(U.monthDays(month),length);assert.equal(U.overview(F.overview(0,month)).dailySales.length,length);
  }
  const data=F.overview();data.dailySales.pop();assert.throws(()=>U.overview(data));
  data.dailySales.push(data.dailySales[0]);assert.throws(()=>U.overview(data));
  assert.throws(()=>U.date('2026-02-29'));assert.throws(()=>U.monthDays('2026-13'));
});
test('S8-05 requires six valid KPIs, server time zone, territory identity and complete overview',()=>{
  for(const field of ['todaySales','monthSales','pendingApprovalOrders','pendingExportOrders','overdueDebt','belowMinimumSkus']){
    const data=F.overview();delete data.metrics[field];assert.throws(()=>U.overview(data));
  }
  for(const change of [d=>d.timezone='Invalid/Zone',d=>d.generatedAt='2026-10-24T08:00:00',d=>d.date='2026-09-24',d=>d.territorySales.push(d.territorySales[0]),d=>d.overdueDealers.pop(),d=>d.metrics.pendingApprovalOrders=0.5]){
    const data=F.overview();change(data);assert.throws(()=>U.overview(data));
  }
});
test('S8-05 validates descending overdue list without sorting away server mistakes',()=>{
  const rows=Array.from({length:3},(_,i)=>F.dealer(i));assert.equal(U.dealers(rows),rows);
  assert.throws(()=>U.dealers([...rows].reverse()));assert.throws(()=>U.dealers([rows[0],rows[0]]));
  assert.throws(()=>U.dealers([{...rows[0],overdueAmount:'0'}]));assert.throws(()=>U.dealers([{...rows[0],dealerId:0}]));
});
test('S8-05 accepts empty and last/out-of-range pages but rejects inconsistent pagination',()=>{
  assert.ok(U.page({content:[],number:0,totalPages:0,totalElements:0},0,20));
  assert.ok(U.page({content:[F.dealer(40)],number:2,totalPages:3,totalElements:41},2,20));
  assert.ok(U.page({content:[],number:3,totalPages:3,totalElements:41},3,20));
  assert.throws(()=>U.page({content:[],number:0,totalPages:3,totalElements:41},0,20));
  assert.throws(()=>U.page({content:[],number:3,totalPages:2,totalElements:41},3,20));
});
test('S8-05 geometry is bounded while money remains BigInt and all external text is escaped',()=>{
  assert.equal(U.ratio(0n,0n),0);assert.equal(U.ratio(1n,4n),0.25);assert.equal(U.ratio(99999999999999999999999n,99999999999999999999999n),1);
  assert.equal(U.compact('1250000'),'1,2 triệu');assert.equal(U.decimalFromCents(12345n),'123.45');
  assert.equal(U.escape('<script>"&\''),'&lt;script&gt;&quot;&amp;&#39;');
});
test('S8-05 source is read-only, local native charts and shared layout, no business storage or CDN',()=>{
  const root=path.join(__dirname,'../frontend'),script=fs.readFileSync(path.join(root,'js/operations-dashboard.js'),'utf8'),html=fs.readFileSync(path.join(root,'operations-dashboard.html'),'utf8'),css=fs.readFileSync(path.join(root,'css/operations-dashboard.css'),'utf8');
  assert.doesNotMatch(script,/localStorage|sessionStorage|method:\s*['"](?:POST|PUT|DELETE|PATCH)/);
  assert.doesNotMatch(html,/(?:src|href)="https?:/);assert.match(script,/<svg/);assert.match(script,/auth:permissions-changed/);
  assert.ok(html.indexOf('/css/app-navigation.css')>html.indexOf('/css/operations-dashboard.css'));
  assert.doesNotMatch(css,/(?:\.content|\.operations-dashboard-page)\s*\{[^}]*max-width/);
});
