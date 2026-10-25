const {test}=require('node:test'),assert=require('node:assert/strict');
const {allowed,createClient}=require('../frontend/js/delivery-confirmation.js');
const options={warehouses:[{id:1,name:'Kho 1'}],tripStatuses:[],failureReasons:[{value:'ABSENT',label:'Không có người nhận'}],proofContentTypes:['image/png','image/jpeg'],proofMaxBytes:5242880,proofMaxPixels:20000000,canManage:true};
const trip=()=>({id:1,tripCode:'CG-1',departureTime:new Date(Date.now()-3600000).toISOString(),stops:[{id:2,canConfirm:true,canReturn:false,status:'PENDING',items:[{quantity:1,baseQuantity:24}]}]});
function setup(handler=async()=>trip()){
 const calls=[],grants=new Set(['WAREHOUSE_VIEW','WAREHOUSE_EXPORT']);
 const client=createClient({permissions:(...p)=>p.some(k=>grants.has(k)),request:async(path,init)=>{
 calls.push({path,init});
 if(path.endsWith('/options'))return structuredClone(options);
 if(path.includes('?'))return {content:[trip()],totalPages:3,totalElements:42};
 return handler(path,init);
 }});
 return {client,calls,grants};
}
const input=()=>({receiverName:' Người nhận ',deliveredAt:new Date(Date.now()-1000).toISOString(),proof:new Blob(['png'],{type:'image/png'})});
test('delivery read follows VIEW or EXPORT matrix, never a role fallback',()=>{
 const grants=new Set(['ROLE_WAREHOUSE_MANAGER']), has=(...p)=>p.some(k=>grants.has(k));
 assert.equal(allowed(has),false);grants.add('ORDER_VIEW');assert.equal(allowed(has),false);
 grants.add('WAREHOUSE_VIEW');assert.equal(allowed(has),true);grants.delete('WAREHOUSE_VIEW');grants.add('WAREHOUSE_EXPORT');assert.equal(allowed(has),true);
});
test('server trip pages, filters and stop details retain real voucher rows',async()=>{
 const {client:c,calls}=setup();assert.equal(await c.initialize(),true);
 assert.equal(c.state.rows[0].stop.items[0].baseQuantity,24);assert.equal(c.state.total,42);
 await c.load(1,{keyword:'ĐL',warehouseId:1,status:'IN_PROGRESS'});
 assert.match(calls.at(-1).path,/page=1&size=20/);assert.match(calls.at(-1).path,/status=IN_PROGRESS/);
 assert.equal(await c.open(1,2),true);assert.equal(c.state.detail.stop.id,2);
});
test('confirm sends actual receiver/time and binary multipart proof, then accepts server result',async()=>{
 const {client:c,calls}=setup(async(path,init)=>{const t=trip();if(init)t.stops[0]={...t.stops[0],status:'DELIVERED',hasSignedProof:true,canConfirm:false};return t;});
 await c.initialize();await c.open(1,2);assert.equal(await c.confirm(input()),true);
 const last=calls.at(-1);assert.match(last.path,/\/1\/stops\/2\/confirm-delivery$/);
 assert.equal(last.init.method,'POST');assert.equal(last.init.body.get('receiverName'),'Người nhận');
 assert.ok(last.init.body.get('proof') instanceof Blob);assert.equal(last.init.headers,undefined);
 assert.equal(c.state.detail.stop.status,'DELIVERED');
});
test('invalid form and VIEW-only cannot send mutations',async()=>{
 const {client:c,calls,grants}=setup();await c.initialize();await c.open(1,2);
 const before=calls.length;assert.equal(await c.confirm({...input(),proof:null}),false);
 grants.delete('WAREHOUSE_EXPORT');assert.equal(await c.confirm(input()),false);assert.equal(await c.fail('ABSENT','Không nhận'),false);
 assert.equal(calls.length,before);
});
test('failure and explicit received checkbox are separate requests; no auto return',async()=>{
 const {client:c,calls}=setup(async(path,init)=>{const t=trip();if(init){t.stops[0].status='FAILED';t.stops[0].canReturn=true;}return t;});
 await c.initialize();await c.open(1,2);await c.fail('ABSENT','Gọi không nghe máy');
 assert.equal(calls.filter(c=>c.init).length,1);assert.match(calls.at(-1).path,/\/status$/);
 const n=calls.length;assert.equal(await c.receive('Hàng đã về kho',false),false);assert.equal(calls.length,n);
 assert.equal(await c.receive('Hàng đã về kho',true),true);assert.match(calls.at(-1).path,/return-to-warehouse$/);
});
test('network uncertainty blocks retries until a fresh detail read',async()=>{
 let rejectWrite=true;
 const {client:c,calls}=setup(async(path,init)=>{if(init&&rejectWrite)throw Error('network lost');return trip();});
 await c.initialize();await c.open(1,2);assert.equal(await c.confirm(input()),false);assert.equal(c.state.uncertain,true);
 const n=calls.length;assert.equal(await c.confirm(input()),false);assert.equal(calls.length,n);
 rejectWrite=false;await c.open(1,2);assert.equal(c.state.uncertain,false);assert.equal(await c.confirm(input()),true);
});
test('duplicate submit is blocked; permission revocation clears data and ignores late success',async()=>{
 let resolve;
 const {client:c,grants,calls}=setup((path,init)=>init?new Promise(r=>resolve=r):Promise.resolve(trip()));
 await c.initialize();await c.open(1,2);const pending=c.confirm(input());const n=calls.length;
 assert.equal(await c.confirm(input()),false);assert.equal(calls.length,n);
 grants.clear();c.clear();resolve(trip());assert.equal(await pending,false);assert.equal(c.state.detail,null);assert.deepEqual(c.state.rows,[]);
});
test('malformed lists and detail errors are visible; no fabricated data',async()=>{
 const c=createClient({permissions:()=>true,request:async path=>path.endsWith('/options')?options:{data:{content:[]}}});
 assert.equal(await c.initialize(),false);assert.match(c.state.error,/không hợp lệ/);assert.deepEqual(c.state.rows,[]);
 const {client:d}=setup(async()=>{throw Error('Điểm giao không còn tồn tại');});await d.initialize();assert.equal(await d.open(1,2),false);assert.match(d.state.error,/không còn/);
});
