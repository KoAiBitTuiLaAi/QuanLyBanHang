const {test}=require('node:test');
const assert=require('node:assert/strict');
const {createClient}=require('../frontend/js/warehouse-lots.js');
const options={warehouses:[{id:1}],products:[{productId:10}],statuses:[{value:'EXPIRED'}],minimumThresholdDays:1,maximumThresholdDays:2147483647,canConfigure:true,canExport:true,canOverrideExpiredLot:true};
const row={id:2,warehouseId:1,productId:10,lotNumber:'LOT-X',baseQuantity:5,baseUnit:'lon',expiryDate:'2020-01-01',isExpired:true,status:'EXPIRED'};
function fixture(){
  const grants=new Set(['WAREHOUSE_VIEW','PRODUCT_EDIT','WAREHOUSE_EXPORT','WAREHOUSE_APPROVE']),calls=[];
  let failure='',pending=null;
  const client=createClient({permissions:(...p)=>p.some(x=>grants.has(x)),request:async(path,init)=>{
    calls.push({path,init});if(init){if(pending)return pending;if(failure)throw new Error(failure);return {voucherCode:'PX-1'};}
    if(path.endsWith('/options'))return structuredClone(options);
    if(path.includes('lot-expiry-alerts'))return {expiredCount:1,expiringSoonCount:0};
    return [structuredClone(row)];
  }});
  return {client,calls,grants,fail:s=>failure=s,hold:p=>pending=p};
}
test('loads server options and status, validates scoped filters before API call',async()=>{
 const f=fixture();assert.equal(await f.client.initialize(),true);assert.equal(f.client.state.rows.length,1);
 assert.equal(f.client.expired(f.client.state.rows[0]),true);
 const before=f.calls.length;assert.equal(await f.client.load({warehouseId:999}),false);assert.equal(f.calls.length,before);assert.match(f.client.state.error,/phạm vi/);
 assert.equal(await f.client.load({thresholdDays:0.5}),false);
 assert.equal(await f.client.load({status:'unknown'}),false);
});
test('settings are persisted via PATCH; invalid input and missing matrix permission cannot write',async()=>{
 const f=fixture();await f.client.initialize();assert.equal(await f.client.saveSettings(10,true,'0'),false);
 assert.ok(await f.client.saveSettings(10,false,'15'));const c=f.calls.at(-1);assert.equal(c.init.method,'PATCH');assert.deepEqual(JSON.parse(c.init.body),{manageLot:false,expiryWarningDays:15});
 f.grants.delete('PRODUCT_EDIT');const before=f.calls.length;assert.equal(await f.client.saveSettings(10,true,30),false);assert.equal(f.calls.length,before);
});
test('expired export requires matrix grant, explicit confirmation and reason; dates never sent',async()=>{
 const f=fixture();await f.client.initialize();
 assert.equal(await f.client.exportLot(2,1,'',true),false);assert.equal(await f.client.exportLot(2,1,'Hủy hàng',false),false);
 f.grants.delete('WAREHOUSE_APPROVE');assert.equal(await f.client.exportLot(2,1,'Hủy hàng',true),false);f.grants.add('WAREHOUSE_APPROVE');
 assert.ok(await f.client.exportLot(2,1,'Hủy hàng',true));const body=JSON.parse(f.calls.at(-1).init.body);
 assert.equal(body.overrideExpiredLot,true);assert.equal(body.expiredLotOverrideReason,'Hủy hàng');assert.equal(body.items[0].expiryDate,undefined);assert.equal(body.items[0].lotNumber,'LOT-X');
});
test('server errors retain form error, allow retry and never claim success',async()=>{
 const f=fixture();await f.client.initialize();f.fail('SKU còn tồn theo lô');assert.equal(await f.client.saveSettings(10,false,15),false);
 assert.match(f.client.state.formError,/còn tồn/);assert.equal(f.client.state.notice,'');assert.equal(f.client.state.busy,false);
 f.fail('');assert.ok(await f.client.saveSettings(10,true,15));assert.equal(f.client.state.formError,'');
});
test('in-flight duplicate rejected; revoked session ignores late write and clears cached data',async()=>{
 const f=fixture();await f.client.initialize();let release;f.hold(new Promise(r=>release=r));
 const write=f.client.exportLot(2,1,'Hủy hàng',true);assert.equal(await f.client.exportLot(2,1,'Hủy hàng',true),false);
 f.grants.clear();f.client.invalidate();release({voucherCode:'STALE'});assert.equal(await write,false);
 assert.equal(f.client.state.options,null);assert.deepEqual(f.client.state.rows,[]);assert.equal(f.client.state.notice,'');
 f.grants.add('WAREHOUSE_VIEW');assert.equal(await f.client.initialize(),true);assert.equal(f.client.exportAllowed(),false);
});
test('server expiry status is authoritative, not browser time',async()=>{
 const f=fixture();await f.client.initialize();const r=f.client.state.rows[0];r.isExpired=false;r.status='ACTIVE';
 f.grants.delete('WAREHOUSE_APPROVE');assert.ok(await f.client.exportLot(2,1,'',false));assert.equal(JSON.parse(f.calls.at(-1).init.body).overrideExpiredLot,false);
});
