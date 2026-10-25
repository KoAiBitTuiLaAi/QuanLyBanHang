// All HTTP calls are intercepted: verifies the real UI contract without production writes.
const {chromium}=require('playwright'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const base=process.env.NAV_TEST_URL||'http://127.0.0.1:5500',output=path.join(require('node:os').tmpdir(),'qlbh-s608-live');
const user={id:22,username:'delivery-test',fullName:'Nhân viên kiểm thử',roles:['ROLE_VIEWER'],permissions:['WAREHOUSE_VIEW','WAREHOUSE_EXPORT'],warehouses:[{id:1}],territories:[]};
const token='test.'+Buffer.from(JSON.stringify({exp:Date.now()/1000+3600})).toString('base64url')+'.test';
const png=Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=','base64');
let trip={id:1,tripCode:'CG-REAL-1',warehouseName:'Kho thực từ API',departureTime:new Date(Date.now()-3600000).toISOString(),status:'IN_PROGRESS',stops:[
{id:11,orderId:10,orderCode:'DH-REAL-1',voucherCode:'PX-1',receiverName:'<img src=x onerror=alert(1)>',receiverPhone:'0900000000',deliveryAddress:'Địa chỉ giao 1',deliveryAmount:240000,status:'PENDING',canConfirm:true,canReturn:false,items:[{productCode:'SKU-1',productName:'Thùng hàng',quantity:1,unit:'thùng',baseQuantity:24,baseUnit:'lon',lotNumber:'LOT-A'}]},
{id:12,orderId:20,orderCode:'DH-REAL-2',voucherCode:'PX-2',receiverName:'Người nhận 2',deliveryAddress:'Địa chỉ giao 2',deliveryAmount:100000,status:'PENDING',canConfirm:true,canReturn:false,items:[]}
]};
const calls=[],errors=[];let failure=false,hold=null;
(async()=>{fs.mkdirSync(output,{recursive:true});const browser=await chromium.launch({headless:true,channel:'chrome'});
try{
 const context=await browser.newContext({viewport:{width:1440,height:1000},reducedMotion:'reduce'});
 await context.addInitScript(({user,token})=>{sessionStorage.setItem('user',JSON.stringify(user));sessionStorage.setItem('accessToken',token);},{user,token});
 await context.route('http://localhost:8080/api/**',async route=>{
  const req=route.request(),u=new URL(req.url()),endpoint=u.pathname.replace('/api',''),method=req.method();
  const headers={'Access-Control-Allow-Origin':base,'Access-Control-Allow-Headers':'Authorization,Content-Type','Access-Control-Allow-Methods':'GET,POST,PUT,OPTIONS'};
  if(method==='OPTIONS')return route.fulfill({status:204,headers});
  calls.push({endpoint,method,query:Object.fromEntries(u.searchParams),body:req.postData()});
  let data,status=200,body;
  if(endpoint==='/auth/permissions')data={roles:user.roles,permissions:user.permissions};
  else if(endpoint.endsWith('/options'))data={warehouses:[{id:1,name:'Kho thực từ API'}],tripStatuses:[{value:'IN_PROGRESS',label:'Đang giao'}],failureReasons:[{value:'ABSENT',label:'Không có người nhận'}],canManage:user.permissions.includes('WAREHOUSE_EXPORT'),proofMaxBytes:5242880,proofMaxPixels:20000000,proofContentTypes:['image/png','image/jpeg']};
  else if(endpoint==='/warehouse/delivery-trips'){
   if(hold)await hold;
   if(failure){status=500;body={success:false,message:'Lỗi tải chuyến thật'};}else data={content:[trip],totalPages:2,totalElements:21};
  }else if(endpoint==='/warehouse/delivery-trips/1')data=trip;
  else if(endpoint.endsWith('/proof'))return route.fulfill({status:200,headers,contentType:'image/png',body:png});
  else if(method!=='GET'){
   assert.ok(user.permissions.includes('WAREHOUSE_EXPORT'));
   const stop=trip.stops.find(s=>endpoint.includes('/stops/'+s.id+'/'));assert.ok(stop);
   if(endpoint.endsWith('/confirm-delivery')){
    assert.match(req.headers()['content-type'],/multipart\/form-data/);assert.match(req.postData(),/receiverName/);assert.match(req.postData(),/proof/);
    Object.assign(stop,{status:'DELIVERED',canConfirm:false,hasSignedProof:true,receiverName:'Người nhận thử',deliveredAt:new Date().toISOString(),confirmedBy:'delivery-test',orderFullyDelivered:false});data=trip;
   }else if(endpoint.endsWith('/status')){
    const b=req.postDataJSON();assert.equal(b.failureReasonCode,'ABSENT');assert.ok(b.failedReason);
    Object.assign(stop,{status:'FAILED',canReturn:true,failedReason:b.failedReason});data=trip;
   }else if(endpoint.endsWith('/return-to-warehouse')){
    assert.ok(req.postDataJSON().returnReason);Object.assign(stop,{returnedToWarehouse:true,canReturn:false,canConfirm:false,returnReason:req.postDataJSON().returnReason});data=stop;
   }else throw Error('Unexpected write '+endpoint);
  }else throw Error('Unexpected API '+endpoint);
  await route.fulfill({status,headers,contentType:'application/json',body:JSON.stringify(body||{success:true,data})});
 });
 const page=await context.newPage();page.on('pageerror',e=>errors.push(e.message));
 await page.goto(base+'/warehouse/delivery');await page.locator('[data-trip="1"][data-stop="11"]').waitFor();
 assert.equal(await page.locator('#sidebar-nav [data-page="delivery-confirmation"]').count(),1);
 assert.equal(await page.locator('#dc-rows img').count(),0);
 await page.locator('[data-stop="11"]').click();await page.locator('#dc-confirm-form').waitFor({state:'visible'});
 await page.locator('#dc-proof-file').setInputFiles({name:'bad.svg',mimeType:'image/svg+xml',buffer:Buffer.from('<svg/>')});
 await page.locator('#dc-detail-error').getByText(/PNG/).waitFor();
 await page.locator('#dc-receiver').fill('Người nhận thử');
 await page.locator('#dc-proof-file').setInputFiles({name:'signed.png',mimeType:'image/png',buffer:png});
 await page.locator('#dc-preview').waitFor({state:'visible'});
 await page.screenshot({path:path.join(output,'delivery-form-1440.png'),fullPage:true});
 await page.locator('#dc-confirm').click();await page.locator('#dc-proof-actions').waitFor({state:'visible'});
 await page.locator('#dc-view-proof').click();await page.locator('#dc-stored-proof').waitFor({state:'visible'});
 assert.equal(calls.filter(c=>c.endpoint.endsWith('/confirm-delivery')).length,1);
 await page.locator('#dc-close').click();assert.equal(await page.locator('#dc-preview').getAttribute('src'),null);
 await page.locator('[data-stop="12"]').click();await page.locator('#dc-fail-reason').selectOption('ABSENT');
 await page.locator('#dc-fail-detail').fill('Không có người nhận tại cửa hàng');
 await page.locator('#dc-fail').click();await page.locator('#dc-return-form').waitFor({state:'visible'});
 assert.equal(calls.filter(c=>c.endpoint.endsWith('/return-to-warehouse')).length,0);
 await page.locator('#dc-return-reason').fill('Thủ kho đã thực nhận hàng nguyên lô');
 await page.locator('#dc-return-checked').check();await page.locator('#dc-return').click();
 await page.locator('#dc-detail-state').getByText(/Đã nhập trả/).waitFor();
 await page.locator('#dc-close').click();
 await page.locator('#dc-next').click();await page.locator('#dc-page').getByText('Trang 2 / 2').waitFor();
 await page.locator('#dc-keyword').fill('ĐL');await page.locator('#dc-keyword').press('Enter');await page.locator('#dc-page').getByText('Trang 1 / 2').waitFor();
 assert.equal(calls.findLast(c=>c.endpoint==='/warehouse/delivery-trips').query.keyword,'ĐL');
 for(const width of [1920,1440,1024,768,390,320]){
  await page.setViewportSize({width,height:1000});
  assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),'outer overflow '+width);
 }
 await page.setViewportSize({width:390,height:844});await page.screenshot({path:path.join(output,'delivery-390.png'),fullPage:true});
 user.permissions=['WAREHOUSE_VIEW'];await page.evaluate(()=>Auth.syncPermissions());await page.locator('[data-stop="11"]').waitFor();
 await page.locator('[data-stop="12"]').click();assert.equal(await page.locator('#dc-fail-form').isVisible(),false);
 failure=true;await page.locator('#dc-reload').click();await page.locator('#dc-error').getByText('Lỗi tải chuyến thật').waitFor();
 failure=false;await page.locator('#dc-reload').click();await page.locator('[data-stop="11"]').waitFor();
 let release;hold=new Promise(r=>release=r);await page.locator('#dc-reload').click();user.permissions=[];await page.evaluate(()=>Auth.syncPermissions());release();hold=null;
 await page.waitForURL(/error\?status=403/);assert.deepEqual(errors,[]);
 console.log('PASS S6-08 real trip/stop API contract, multipart proof, failure and explicit return, VIEW-only, revocation, XSS, paging/filter, 6 widths. '+output);
}finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
