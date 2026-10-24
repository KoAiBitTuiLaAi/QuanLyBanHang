const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),{test}=require('node:test');
const U=require('../frontend/js/debt-aging-utils.js'),F=require('./debt-aging-fixture.cjs');
const filters={asOfDate:'2026-10-24',employeeId:null,unassignedOnly:false};
test('S8-09 production adapter is explicitly disconnected and contains no guessed request/demo data',async()=>{
  const source=fs.readFileSync(path.join(__dirname,'../frontend/js/debt-aging-api.js'),'utf8');
  const adapter=vm.runInNewContext(source+';DebtAgingApi');assert.equal(adapter.available,false);await assert.rejects(()=>adapter.options(),/chưa được kết nối/);await assert.rejects(()=>adapter.report(),/chưa được kết nối/);
  assert.doesNotMatch(source,/fetch\(|apiFetch\(|localStorage|sessionStorage/);
});
test('S8-09 financial strings preserve digits, fractions, zero and reject unsafe numeric values',()=>{
  assert.equal(U.money('9007199254740993.25'),'9.007.199.254.740.993,25 ₫');assert.equal(U.money('0.00'),'0 ₫');assert.equal(U.decimal(U.amount('12.3')),'12.30');
  for(const value of [12,null,'-1','1e4','1.123','',Number.MAX_SAFE_INTEGER])assert.throws(()=>U.amount(value));
});
test('S8-09 filters use server business day and employee IDs, including unassigned',()=>{
  const options=U.options(F.options());assert.deepEqual(U.filters('2026-10-24','all',options),filters);
  assert.equal(U.filters('2026-10-23','1',options).employeeId,1);assert.equal(U.filters('2026-10-23','unassigned',options).unassignedOnly,true);
  for(const [day,id]of [['2026-10-25','all'],['2026-02-29','all'],['2026-10-24','999'],['2026-10-24','1.0']])assert.throws(()=>U.filters(day,id,options));
});
test('S8-09 accepts server-defined buckets, full summaries and risk order instead of sorting by amount',()=>{
  const options=F.options(),data=F.report();assert.equal(U.report(data,filters,options),data);
  assert.ok(U.amount(data.dealers[0].overdueAmount)<U.amount(data.dealers[1].overdueAmount));assert.equal(data.dealers[0].riskRank,1);
  assert.ok(data.bucketTotals.reduce((sum,row)=>sum+row.dealerCount,0)>data.totals.dealerCount);
  const reordered=structuredClone(data);reordered.buckets=reordered.buckets.map(row=>({label:row.label,key:row.key,maxDays:row.maxDays,minDays:row.minDays}));assert.ok(U.report(reordered,filters,options));
});
test('S8-09 rejects missing/overlapping groups and duplicate employee options',()=>{
  for(const change of [d=>d.buckets[1].minDays=14,d=>d.buckets[0].minDays=0,d=>d.buckets[2].maxDays=90,d=>d.employees.push(d.employees[0]),d=>d.timezone='Not/Zone']){const options=F.options();change(options);assert.throws(()=>U.options(options));}
});
test('S8-09 validates filter scope, complete data, unique dealers and every summary before render/export',()=>{
  for(const change of [d=>d.complete=false,d=>d.currency='USD',d=>d.filters.employeeId=2,d=>d.dealers.push(d.dealers[0]),d=>d.dealers[0].riskRank=99,d=>d.dealers[0].employeeId=999,d=>d.dealers[0].buckets.old='0',d=>d.totals.overdueAmount='0',d=>d.totals.maxOverdueDays=0,d=>d.bucketTotals[0].amount='0',d=>d.employeeTotals.pop(),d=>d.employeeTotals[0].buckets.old='0',d=>d.generatedAt='2026-10-24T08:00:00']){
    const data=F.report();change(data);assert.throws(()=>U.report(data,filters,F.options()));
  }
});
test('S8-09 empty results and employee/unassigned subsets are valid, not invented totals',()=>{
  assert.equal(U.report(F.report(filters,0),filters,F.options()).totals.overdueAmount,'0.00');
  for(const employee of ['1','unassigned']){const requested=U.filters('2026-10-24',employee,F.options());const data=F.report(requested);assert.ok(U.report(data,requested,F.options()));assert.ok(data.dealers.every(row=>row.employeeId===(employee==='1'?1:null)));}
});
test('S8-09 workbook exports every dealer, applied date/filter, exact money and untrusted strings without formulas',()=>{
  const XLSX=require('../frontend/js/vendor/xlsx.full.min.js'),data=U.report(F.report(),filters,F.options());data.dealers[0].dealerName='=HYPERLINK("https://invalid","x")';
  const book=U.workbook(data,XLSX),bytes=XLSX.write(book,{bookType:'xlsx',type:'buffer'}),roundtrip=XLSX.read(bytes,{type:'buffer'});
  assert.equal(roundtrip.SheetNames.length,4);const rows=XLSX.utils.sheet_to_json(roundtrip.Sheets['Dai ly rui ro'],{header:1});assert.equal(rows.length,46);assert.equal(rows[1][2],data.dealers[0].dealerName);assert.ok(rows[2].includes('9007199254740993.25'));
  for(const sheet of Object.values(roundtrip.Sheets))for(const [key,cell]of Object.entries(sheet))if(!key.startsWith('!')){assert.equal(cell.t,'s');assert.equal(cell.f,undefined);assert.equal(cell.l,undefined);}
  assert.match(XLSX.utils.sheet_to_csv(roundtrip.Sheets['Thong tin']),/2026-10-24/);
});
test('S8-09 formula-injection corpus roundtrips as text without formulas or hyperlinks',()=>{
  const XLSX=require('../frontend/js/vendor/xlsx.full.min.js');
  for(const payload of ['=1+1','+SUM(1,2)','-1+2','@SUM(1,2)','\t=1+1','\r=1+1']){
    const data=F.report();data.dealers[0].dealerName=payload;
    const bytes=XLSX.write(U.workbook(data,XLSX),{bookType:'xlsx',type:'buffer'}),cell=XLSX.read(bytes,{type:'buffer'}).Sheets['Dai ly rui ro'].C2;
    assert.equal(cell.t,'s');assert.equal(cell.v,payload);assert.equal(cell.f,undefined);assert.equal(cell.l,undefined);
  }
});
test('S8-09 screen is isolated from backend files/business storage and uses local shared shell assets',()=>{
  const root=path.join(__dirname,'../frontend'),html=fs.readFileSync(path.join(root,'debt-aging.html'),'utf8'),script=fs.readFileSync(path.join(root,'js/debt-aging.js'),'utf8');
  assert.match(html,/app-shell.js/);assert.match(script,/auth:permissions-changed/);assert.doesNotMatch(script,/localStorage|sessionStorage|new Date\(\)/);assert.doesNotMatch(html,/(?:src|href)="https?:/);
  assert.ok(html.indexOf('app-navigation.css')>html.indexOf('debt-aging.css'));
});
