const {test}=require('node:test');const assert=require('node:assert/strict');
const {createClient}=require('../frontend/js/stock-card.js');
const options={warehouses:[{id:1}],products:[{id:2}],pageSizes:[20,50],defaultPageSize:50};
const entry={sourceType:'VOUCHER',sourceId:3,baseQuantityChange:-2,runningBaseBalance:125};
function fixture(){let grant=true,failure='',hold=null;const calls=[];const client=createClient({allowed:()=>grant,request:async path=>{
 calls.push(path);if(hold)return hold;if(failure)throw Error(failure);
 if(path.endsWith('/options'))return options;
 if(path.includes('/sources/'))return {sourceType:'VOUCHER',sourceId:3,entries:[entry]};
 return {productId:2,entries:[entry],totalElements:1,totalPages:1,currentPage:0,openingBalance:127};
}});return {client,calls,grant:v=>grant=v,fail:v=>failure=v,hold:v=>hold=v};}
test('options are independent of PRODUCT_VIEW and no SKU selected does not request history',async()=>{
 const f=fixture();await f.client.initialize();assert.deepEqual(f.calls,['/warehouse/stock-card/options']);
 assert.equal(f.client.state.card,null);
});
test('server balances preserved, inclusive date interval passed and filters scoped before querying',async()=>{
 const f=fixture();await f.client.initialize({productId:'2',warehouseId:'1',fromDate:'2026-01-01',toDate:'2026-01-31'});
 assert.equal(f.client.state.card.entries[0].runningBaseBalance,125);
 const q=new URL(f.calls.at(-1),'http://test').searchParams;assert.equal(q.get('toDate'),'2026-01-31T23:59:59.999999999');
 assert.equal(await f.client.load({productId:2,warehouseId:999}),false);assert.match(f.client.state.error,/phạm vi/);
 assert.equal(await f.client.load({productId:2,fromDate:'2026-02-01',toDate:'2026-01-01'}),false);
});
test('source uses persisted identity and current warehouse, only a displayed row can be opened',async()=>{
 const f=fixture();await f.client.initialize({productId:2,warehouseId:1});assert.equal(await f.client.openSource({...entry}),false);
 await f.client.openSource(f.client.state.card.entries[0]);assert.match(f.calls.at(-1),/\/2\/sources\/VOUCHER\/3\?warehouseId=1/);
 assert.ok(f.client.state.source);f.client.closeSource();assert.equal(f.client.state.source,null);
});
test('load failure drops old numbers, retains filter and supports retry',async()=>{
 const f=fixture();await f.client.initialize({productId:2});f.fail('Kho không kết nối');await f.client.load({productId:2});
 assert.equal(f.client.state.card,null);assert.match(f.client.state.error,/không kết nối/);assert.equal(f.client.state.filters.productId,2);
 f.fail('');assert.ok(await f.client.load());
});
test('late options, history and source cannot restore data after matrix revocation',async()=>{
 for(const kind of ['options','history','source']){
  const f=fixture();if(kind!=='options')await f.client.initialize({productId:2});let release;f.hold(new Promise(r=>release=r));
  const pending=kind==='options'?f.client.initialize({productId:2}):kind==='history'?f.client.load({productId:2}):f.client.openSource(f.client.state.card.entries[0]);
  f.grant(false);f.client.clear();release(kind==='options'?options:kind==='history'?{productId:2,entries:[entry],totalElements:1,currentPage:0,totalPages:1}:{sourceType:'VOUCHER',sourceId:3,entries:[entry]});
  assert.equal(await pending,false);assert.equal(f.client.state.card,null);assert.equal(f.client.state.source,null);assert.equal(f.client.state.options,null);
 }
});
test('latest search wins, and granting permission again can initialize on same client',async()=>{
 const f=fixture();await f.client.initialize();let release;f.hold(new Promise(r=>release=r));const old=f.client.load({productId:2});
 f.hold(null);await f.client.load({productId:2});release({productId:2,entries:[],totalElements:0,currentPage:0,totalPages:1});await old;
 assert.equal(f.client.state.card.entries.length,1);f.grant(false);f.client.clear();assert.equal(await f.client.initialize(),false);f.grant(true);assert.ok(await f.client.initialize({productId:2}));
});
