const {test}=require('node:test');
const assert=require('node:assert/strict');
const U=require('../frontend/js/invoice-utils.js');
test('invoice selection contains only persisted IDs and unique orders, never quantities/prices/numbers',()=>{
  assert.deepEqual(U.selection('10',['2','1','2']),{dealerId:10,orderIds:[1,2]});
  for(const dealer of ['',0,-1,'x',true])assert.throws(()=>U.selection(dealer,[1]));
  assert.throws(()=>U.selection(10,[]));assert.throws(()=>U.selection(10,[1,'x']));
});
test('invoice formatting distinguishes missing from zero, preserves delivery decimals and date-only values',()=>{
  assert.equal(U.quantity(0),'0');assert.equal(U.quantity(2.5),'2,5');
  for(const v of [null,undefined,true,false,'',' ',Infinity]){assert.equal(U.quantity(v),'Chưa có dữ liệu');assert.equal(U.money(v),'—');}
  assert.doesNotMatch(U.date('2026-10-23'),/00:00/);assert.equal(U.date('invalid'),'Chưa có dữ liệu');
});
test('invoice periods reject reversed bounds and support unbounded searches',()=>{
  assert.throws(()=>U.period('2026-10-24','2026-10-23'));U.period('','2026-10-23');U.period('2026-10-23','2026-10-23');
});
test('invoice adjustment validates reason, positive monetary magnitude and separate information/cancel payloads',()=>{
  assert.deepEqual(U.adjustment('DECREASE',' thiếu hàng ','100.50'),{adjustmentType:'DECREASE',adjustmentReason:'thiếu hàng',adjustmentAmount:'100.50'});
  for(const amount of [0,-1,'bad','1.123',true])assert.throws(()=>U.adjustment('INCREASE','Lý do',amount));
  assert.throws(()=>U.adjustment('CANCEL',' ',0));assert.throws(()=>U.adjustment('CANCEL','x'.repeat(501),0));
  assert.throws(()=>U.adjustment('CANCEL',null,0));
  assert.throws(()=>U.adjustment('INFORMATION','Sai thông tin',''));
  assert.deepEqual(U.adjustment('INFORMATION','Đổi tên',0,{newDealerName:' Tên mới '}),{adjustmentType:'INFORMATION',adjustmentReason:'Đổi tên',newDealerName:'Tên mới'});
  assert.deepEqual(U.adjustment('CANCEL','Hủy có lý do',999),{adjustmentType:'CANCEL',adjustmentReason:'Hủy có lý do'});
});
test('invoice fingerprint tracks server actual quantities, price/totals and source grouping',()=>{
  const data={dealerId:1,totalAmount:100,orders:[{orderId:2},{orderId:1}],items:[{orderItemId:10,invoicedQuantity:2,unitPrice:50,totalAmount:100}]};
  assert.equal(U.signature(data),U.signature({...data,orders:[...data.orders].reverse()}));
  assert.notEqual(U.signature(data),U.signature({...data,totalAmount:120}));
  assert.notEqual(U.signature(data),U.signature({...data,items:[{...data.items[0],invoicedQuantity:3}]}));
  assert.equal(U.escape('<img src=x onerror="x">'),'&lt;img src=x onerror=&quot;x&quot;&gt;');
});
