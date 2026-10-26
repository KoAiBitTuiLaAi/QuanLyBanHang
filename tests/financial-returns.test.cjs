const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),{test}=require('node:test');
const U=require('../frontend/js/debt-adjustment-utils.js'),F=require('./financial-returns-fixture.cjs');
test('S8-02 exact money and quantities preserve large numbers, cents and historical discount precision',()=>{
 const data=U.parse('{"totalReturnAmount":9007199254740993.25,"returnQuantity":1.2345,"invoiceDiscountPerUnit":0.006667,"adjustmentAmount":-33.33}');
 assert.equal(U.money(data.totalReturnAmount),'9.007.199.254.740.993,25 ₫');assert.equal(U.quantity(data.returnQuantity),'1,2345');assert.equal(data.invoiceDiscountPerUnit,'0.006667');assert.equal(U.money(data.adjustmentAmount),'-33,33 ₫');assert.equal(U.money(null),'Chưa có dữ liệu');
 for(const value of ['1.23456','1e3','NaN','','-1','1,2.3'])assert.throws(()=>U.decimal(value));
});
test('S8-02 source and write payload use invoiceItemId, actual entered quantity, reason; never price/status from client',()=>{
 const source=U.withWarehouseReturn(U.source(F.source()),30),data=U.payload(source,{101:'1,2500'},'  Hàng trả  ',' Ghi chú ');
 assert.deepEqual(data,{invoiceId:10,dealerReturnId:30,returnReason:'Hàng trả',note:'Ghi chú',items:[{invoiceItemId:101,returnQuantity:'1.2500'}]});
 for(const values of [{},{101:'0'},{101:'4'},{101:'1.00001'}])assert.throws(()=>U.payload(source,values,'Lý do',''));
 assert.throws(()=>U.payload(source,{101:'1'},' ',''));assert.throws(()=>U.payload(source,{101:'1'},'a'.repeat(501),''));
 const invalid=F.source();invalid.items[0].remainingQuantity='2';assert.throws(()=>U.source(invalid));
});
test('S8-02 confirmed return must link one matching DECREASE document with exact total',()=>{
 assert.ok(U.detail(F.detail()));assert.ok(U.detail(F.detail(1,'CONFIRMED')));assert.ok(U.detail(F.detail(1,'CANCELLED')));
 for(const change of [d=>d.invoiceAdjustment=null,d=>d.invoiceAdjustment.invoiceId=99,d=>d.invoiceAdjustment.adjustmentAmount='-1',d=>d.items[0].returnAmount='1',d=>d.items.push(d.items[0]),d=>d.status='PENDING']){const data=F.detail(1,'CONFIRMED');change(data);assert.throws(()=>U.detail(data));}
 const draft=F.detail();draft.invoiceAdjustment=F.detail(1,'CONFIRMED').invoiceAdjustment;assert.throws(()=>U.detail(draft));
});

test('Warehouse receipt caps financial quantities even when the invoice has more remaining',()=>{
 const source=F.source();source.warehouseReturns[0].items[0].remainingQuantity='0.25';
 const selected=U.withWarehouseReturn(U.source(source),30);
 assert.throws(()=>U.payload(selected,{101:'1'},'Trả hàng',''));
 assert.equal(U.payload(selected,{101:'0.25'},'Trả hàng','').dealerReturnId,30);
 assert.throws(()=>U.withWarehouseReturn(source,999));
 assert.throws(()=>U.payload(U.source(source),{101:'0.25'},'Trả hàng',''));
});
test('S8-02 source never fabricates vouchers, return quantities, fake balances or PDF artifacts',()=>{
 const script=fs.readFileSync(path.join(__dirname,'../frontend/js/debt-adjustment.js'),'utf8'),html=fs.readFileSync(path.join(__dirname,'../frontend/finance/debt-adjustment.html'),'utf8');
 assert.doesNotMatch(script,/buildReturnVouchersFromInvoices|localStorage|sessionStorage|Math\.round|Math\.min\(.*2\)|\/dealers|\/adjustments`|application\/pdf/);
 assert.match(script,/\/invoices\/returns/);assert.match(script,/auth:permissions-changed/);assert.match(html,/app-shell.js/);assert.match(html,/app-transition.js/);
 assert.ok(html.indexOf('app-navigation.css')>html.indexOf('debt-adjustment.css'));
});
