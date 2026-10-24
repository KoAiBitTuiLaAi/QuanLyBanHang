const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs'), path = require('node:path');
const U = require('../frontend/js/dealer-return-utils.js');
const catalog = {warehouses:[{id:1}],defectiveWarehouses:[{id:2}],returnReasons:[{code:'DAMAGED',label:'Hỏng',defectiveDefault:true},{code:'EXCHANGE',label:'Đổi trả',defectiveDefault:false},{code:'OTHER',label:'Khác'}],statuses:[],canCreate:true,canConfirm:true};
const source = {invoiceId:10,status:'ISSUED',items:[{invoiceItemId:101,productCode:'SKU1',unit:'thùng',manageLot:true,deliveredQuantity:'10',previouslyReturnedQuantity:'4',returnableQuantity:'6',availableLots:[{lotNumber:'LO-01',expiryDate:'2027-12-31',manufacturingDate:'2025-01-01'}]}]};
const row = (overrides={}) => ({invoiceItemId:'101',quantity:'1',reason:'EXCHANGE',otherReason:'',defective:false,defectiveWarehouseId:'',lotNumber:'LO-01',expiryDate:'',manufacturingDate:'',note:'',...overrides});

test('four-decimal quantities compare without floating point or scientific coercion', () => {
  assert.equal(U.decimal('0,0001').value,1n); assert.equal(U.decimal('99999999999.9999').value,999999999999999n);
  for(const invalid of ['1.00001','1e3','Infinity','-1','12.000.000','100000000000','']) assert.throws(()=>U.decimal(invalid),invalid);
  assert.equal(U.quantity('1234.5000'),'1.234,5'); assert.equal(U.quantity(null),'Chưa có dữ liệu');
  const parsed=U.parse('{"returnableQuantity":99999999999.9999,"totalAmount":9007199254740993.01}');
  assert.equal(parsed.returnableQuantity,'99999999999.9999');assert.equal(parsed.totalAmount,'9007199254740993.01');
});
test('return cap includes cumulative split lines and server pending quantities', () => {
  const result=U.build(source,[row({quantity:'0.1'}),row({quantity:'0.2'})],catalog,'1','Ghi chú');
  assert.deepEqual(result.items.map(item=>item.returnQuantity),['0.1','0.2']);
  assert.throws(()=>U.build(source,[row({quantity:'3.5'}),row({quantity:'2.5001'})],catalog,'1',''),/vượt 6/);
  assert.throws(()=>U.build(source,[row({quantity:'0'})],catalog,'1',''),/lớn hơn 0/);
  assert.throws(()=>U.build(source,[],catalog,'1',''),/ít nhất/);
});
test('damaged reasons always route to declared defective warehouses, never normal stock', () => {
  const result=U.build(source,[row({reason:'DAMAGED',defective:false,defectiveWarehouseId:'2'})],catalog,'1','');
  assert.equal(result.items[0].defective,true);assert.equal(result.items[0].targetWarehouseId,2);
  assert.throws(()=>U.build(source,[row({reason:'DAMAGED',defectiveWarehouseId:'1'})],catalog,'1',''),/kho hàng lỗi/);
  assert.throws(()=>U.build(source,[row({reason:'DAMAGED'})],catalog,'1',''),/kho hàng lỗi/);
  assert.throws(()=>U.build(source,[row()],catalog,'2',''),/kho thường/);
});
test('reasons and real lot metadata are mandatory, not generated or silently inferred', () => {
  assert.throws(()=>U.build(source,[row({reason:''})],catalog,'1',''),/lý do/);
  assert.throws(()=>U.build(source,[row({reason:'OTHER',otherReason:'  '})],catalog,'1',''),/bắt buộc/);
  assert.throws(()=>U.build(source,[row({lotNumber:'LO-SAI'})],catalog,'1',''),/đúng lô/);
  const existing=U.build(source,[row({expiryDate:'2099-01-01'})],catalog,'1','').items[0];
  assert.equal(existing.expiryDate,'2027-12-31');assert.equal(existing.manufacturingDate,'2025-01-01');
  const unknown={...source,items:[{...source.items[0],availableLots:[]}]};
  assert.throws(()=>U.build(unknown,[row()],catalog,'1',''),/Ngày không hợp lệ/);
  const actual=U.build(unknown,[row({lotNumber:'THUC-TE',expiryDate:'2026-10-01',manufacturingDate:'2025-01-01'})],catalog,'1','').items[0];
  assert.equal(actual.lotNumber,'THUC-TE');assert.equal(actual.expiryDate,'2026-10-01');
  assert.throws(()=>U.build(unknown,[row({expiryDate:'2025-01-01',manufacturingDate:'2025-01-02'})],catalog,'1',''),/trước ngày sản xuất/);
});
test('payload uses invoice item IDs and original unit quantity; never client-computed balances/statuses', () => {
  const result=U.build(source,[row({quantity:'1,2500',reason:'OTHER',otherReason:'Khách trả nguyên kiện'})],catalog,'1','');
  assert.deepEqual(Object.keys(result).sort(),['invoiceId','items','note','warehouseId']);
  assert.equal(result.items[0].returnQuantity,'1.2500');assert.equal(result.items[0].invoiceItemId,101);
  for(const key of ['productId','baseReturnQuantity','unitPrice','status','inventoryAdded','deliveredQuantity']) assert.ok(!(key in result.items[0]));
  assert.throws(()=>U.build(source,[row({invoiceItemId:'999'})],catalog,'1',''),/không thuộc/);
  assert.throws(()=>U.build(source,[row({note:'a'.repeat(256)})],catalog,'1',''),/255/);
});
test('incoming returns are capped by original delivery, not current lot stock; missing lot dates require actual input', () => {
  const exhausted={...source,items:[{...source.items[0],availableLots:[{lotNumber:'LO-01',currentBaseQuantity:'0',expiryDate:null,manufacturingDate:null}]}]};
  assert.throws(()=>U.build(exhausted,[row()],catalog,'1',''),/Ngày không hợp lệ/);
  const result=U.build(exhausted,[row({quantity:'6',expiryDate:'2027-12-31'})],catalog,'1','');
  assert.equal(result.items[0].returnQuantity,'6');assert.equal(result.items[0].expiryDate,'2027-12-31');
});
test('malformed API data, duplicate sources and date ranges fail closed', () => {
  assert.throws(()=>U.page([]));assert.throws(()=>U.options({}));assert.throws(()=>U.source({...source,status:'CANCELLED'},10));
  assert.throws(()=>U.source({...source,items:[source.items[0],source.items[0]]},10),/trùng/);
  assert.throws(()=>U.source(source,20));assert.throws(()=>U.detail({id:1,status:'UNKNOWN',items:[]},1));
  U.period('','2026-10-24');U.date('2024-02-29');assert.throws(()=>U.date('2026-02-29'));assert.throws(()=>U.period('2026-10-25','2026-10-24'));
});
test('frontend is API-backed and business drafts never enter browser storage or backend files', () => {
  const js=fs.readFileSync(path.join(__dirname,'../frontend/js/dealer-returns.js'),'utf8');
  assert.match(js,/Auth\.apiFetch\('\/dealer-returns'/);assert.match(js,/fromDate/);assert.match(js,/toDate/);
  assert.doesNotMatch(js,/localStorage|sessionStorage/);assert.match(js,/auth:permissions-changed/);assert.match(js,/beforeunload/);
  const css=fs.readFileSync(path.join(__dirname,'../frontend/css/dealer-returns.css'),'utf8');
  assert.doesNotMatch(css,/--app-content-|\.dealer-returns-page\s*\{[^}]*\b(?:max-width|margin|padding)\s*:/);
});
