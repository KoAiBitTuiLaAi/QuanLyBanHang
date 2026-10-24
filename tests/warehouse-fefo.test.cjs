const {test}=require('node:test');
const assert=require('node:assert/strict');
const {createClient}=require('../frontend/js/warehouse-fefo.js');
const options={warehouses:[{id:1,code:'K1',name:'Kho 1'}],products:[{productId:10,unit:'lon'}],canExport:true};
const lot=(lotNumber,quantity=20)=>({lotNumber,lotId:lotNumber,expiryDate:'2030-01-01',availableQuantity:quantity,baseUnit:'lon',isExpired:false,sufficientForRequest:true});
function fixture(){
 const grants=new Set(['WAREHOUSE_VIEW','WAREHOUSE_EXPORT']),calls=[];
 const preview={warehouseId:1,productId:10,requestedQuantity:'10',requestedUnit:'lon',requestedBaseQuantity:10,baseUnit:'lon',manageLot:true,
   suggestedLot:lot('B'),availableLots:[{...lot('A',5),sufficientForRequest:false},lot('B'),lot('C'),{...lot('OLD'),isExpired:true}],message:'Gợi ý B'};
 let fail='',held=null;
 const c=createClient({permissions:(...p)=>p.some(x=>grants.has(x)),request:async(path,init)=>{
   calls.push({path,init});if(held && !path.endsWith('/options'))return held;
   if(fail){const e=Error(fail);e.status=400;throw e;}
   if(path.endsWith('/options'))return structuredClone(options);
   if(init)return {voucherCode:'PX-1',items:[{lotNumber:JSON.parse(init.body).items[0].lotNumber}]};
   return structuredClone(preview);
 }});
 return {c,grants,calls,preview,fail:s=>fail=s,hold:p=>held=p};
}
async function ready(f){await f.c.initialize();f.c.input({warehouseId:'1',productId:'10',quantity:'10'});assert.equal(await f.c.suggest(),true);}
test('uses server FEFO order and default, without calling catalog or recalculating expiry',async()=>{
 const f=fixture();await ready(f);assert.equal(f.c.state.selected,'B');assert.equal(f.c.state.preview.availableLots[0].lotNumber,'A');
 assert.match(f.calls.at(-1).path,/quantity=10&unit=lon/);assert.equal(f.c.choose('A'),false);assert.equal(f.c.choose('OLD'),false);
});
test('different lot requires a reason, explicit confirmation; sends the exact selected lot and quantity',async()=>{
 const f=fixture();await ready(f);f.c.choose('C');f.c.confirm(true);assert.equal(await f.c.save(),false);assert.match(f.c.state.error,/lý do/);
 f.c.reason('Ưu tiên vị trí thuận tiện');assert.equal(f.c.state.confirmed,false);assert.equal(await f.c.save(),false);f.c.confirm(true);
 assert.equal(await f.c.save(),true);const b=JSON.parse(f.calls.at(-1).init.body);
 assert.deepEqual(b.items,[{productId:10,unit:'lon',quantity:'10',lotNumber:'C',suggestedLotNumber:'B',overrideReason:'Ưu tiên vị trí thuận tiện'}]);
 assert.equal(f.c.state.preview,null);assert.equal(f.c.state.result.voucherCode,'PX-1');
});
test('default lot does not require an override reason and duplicate write is rejected',async()=>{
 const f=fixture();await ready(f);f.c.confirm(true);let release;f.hold(new Promise(r=>release=r));const pending=f.c.save();
 assert.equal(await f.c.save(),false);release({voucherCode:'PX-2',items:[]});assert.equal(await pending,true);
 assert.equal(f.calls.filter(c=>c.init).length,1);
});
test('input changes invalidate previews and delayed suggestions cannot restore them',async()=>{
 const f=fixture();await f.c.initialize();f.c.input({warehouseId:'1',productId:'10',quantity:'10'});let release;f.hold(new Promise(r=>release=r));
 const old=f.c.suggest();f.c.input({quantity:'11'});release(f.preview);assert.equal(await old,false);assert.equal(f.c.state.preview,null);
 assert.equal(await f.c.save(),false);
});
test('invalid inputs/scope/precision are blocked before suggestion API',async()=>{
 for(const values of [{warehouseId:'99'}, {productId:'99'}, {quantity:'0'}, {quantity:'-1'}, {quantity:'1.00001'}, {quantity:'100000000000'}, {quantity:'1e2'}]){
  const f=fixture();await f.c.initialize();f.c.input({warehouseId:'1',productId:'10',quantity:'10',...values});
  assert.equal(await f.c.suggest(),false);assert.equal(f.calls.length,1);
 }
});
test('no suitable single lot means no default and no export; unknown/expired lot cannot be selected',async()=>{
 const f=fixture();f.preview.suggestedLot=null;f.preview.availableLots=f.preview.availableLots.map(l=>({...l,sufficientForRequest:false}));
 await ready(f);assert.equal(f.c.state.selected,'');assert.equal(f.c.choose('C'),false);f.c.confirm(true);assert.equal(await f.c.save(),false);
});
test('server 400 preserves selected lot, reason and quantity for correction',async()=>{
 const f=fixture();await ready(f);f.c.choose('C');f.c.reason('Lý do có thật');f.c.confirm(true);f.fail('Tồn đã thay đổi');
 assert.equal(await f.c.save(),false);assert.equal(f.c.state.selected,'C');assert.equal(f.c.state.reason,'Lý do có thật');assert.equal(f.c.state.input.quantity,'10');
 f.fail('');assert.equal(await f.c.save(),true);
});
test('live revoke removes export while read grant remains; hardcoded roles cannot grant write',async()=>{
 const f=fixture();await ready(f);f.grants.delete('WAREHOUSE_EXPORT');assert.equal(f.c.canExport(),false);f.c.confirm(true);assert.equal(await f.c.save(),false);
 f.grants.clear();f.grants.add('ROLE_WAREHOUSE_MANAGER');f.c.invalidate();assert.equal(f.c.allowed(),false);assert.equal(await f.c.initialize(),false);
});
test('revocation clears cached forms and ignores late writes even if permission is regranted',async()=>{
 const f=fixture();await ready(f);f.c.confirm(true);let release;f.hold(new Promise(r=>release=r));const pending=f.c.save();
 f.grants.clear();f.c.invalidate();f.grants.add('WAREHOUSE_VIEW');f.grants.add('WAREHOUSE_EXPORT');
 release({voucherCode:'OLD',items:[]});assert.equal(await pending,false);assert.equal(f.c.state.result,null);assert.equal(f.c.state.preview,null);assert.equal(f.c.state.busy,false);
});
test('mismatched server context or unsafe suggested lot is rejected',async()=>{
 for(const edit of [p=>p.warehouseId=99,p=>p.requestedQuantity=11,p=>p.suggestedLot={...lot('OLD'),isExpired:true}]){
  const f=fixture();edit(f.preview);await f.c.initialize();f.c.input({warehouseId:'1',productId:'10',quantity:'10'});assert.equal(await f.c.suggest(),false);assert.equal(f.c.state.preview,null);
 }
});

test('ambiguous write response clears the export so a retry cannot double-deduct stock',async()=>{
 const f=fixture();await ready(f);f.c.confirm(true);f.hold(Promise.reject(Error('Mất kết nối sau gửi')));
 assert.equal(await f.c.save(),false);assert.equal(f.c.state.preview,null);assert.match(f.c.state.error,/Kiểm tra Sổ cái/);
 const before=f.calls.length;assert.equal(await f.c.save(),false);assert.equal(f.calls.length,before);
});
