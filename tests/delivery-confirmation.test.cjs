const {test}=require('node:test'),assert=require('node:assert/strict');
const {allowed,createClient}=require('../frontend/js/delivery-confirmation.js');
test('delivery preview requires live ORDER_VIEW and EXPORT, not a warehouse role',()=>{
 const grants=new Set(['ROLE_WAREHOUSE_MANAGER']);const has=p=>grants.has(p);assert.equal(allowed(has),false);
 grants.add('ORDER_VIEW');assert.equal(allowed(has),false);grants.add('WAREHOUSE_EXPORT');assert.equal(allowed(has),true);
 grants.delete('ORDER_VIEW');assert.equal(allowed(has),false);
});
test('delivery only reads server pages, preserves real metadata and filters',async()=>{
 const calls=[];const c=createClient({permissions:()=>true,request:async path=>{calls.push(path);return {data:[{id:1,status:'EXPORTED'}],totalPages:3,totalElements:42};}});
 assert.equal(await c.load(1,{keyword:'ĐL',status:'EXPORTED'}),true);assert.match(calls[0],/page=1&size=20/);
 assert.equal(c.state.pages,3);assert.equal(c.state.total,42);assert.equal(c.state.rows[0].id,1);
});
test('delivery revocation clears data and invalidates late reads',async()=>{
 let grant=true,resolve;const c=createClient({permissions:()=>grant,request:()=>new Promise(r=>resolve=r)});
 const pending=c.load();grant=false;c.clear();resolve({data:[{id:1}],totalPages:1});assert.equal(await pending,false);assert.deepEqual(c.state.rows,[]);
});
test('delivery read errors are visible and do not become an empty successful result',async()=>{
 const c=createClient({permissions:()=>true,request:async()=>{throw Error('Server không trả được đơn');}});
 assert.equal(await c.load(),false);assert.match(c.state.error,/Server/);assert.deepEqual(c.state.rows,[]);assert.equal(c.state.loading,false);
});
test('malformed delivery responses are rejected, there is no fallback demo list',async()=>{
 const c=createClient({permissions:()=>true,request:async()=>({data:{content:[{id:'FAKE'}]}})});
 assert.equal(await c.load(),false);assert.deepEqual(c.state.rows,[]);
});
