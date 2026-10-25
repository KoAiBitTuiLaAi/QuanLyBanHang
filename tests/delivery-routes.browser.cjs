// Intercepted API fixtures only; never writes production data.
const {chromium}=require('playwright'),assert=require('node:assert/strict');
const base=process.env.NAV_TEST_URL||'http://127.0.0.1:5500';
const user={id:22,username:'route-test',fullName:'Kiểm thử phân tuyến',roles:['ROLE_VIEWER'],permissions:['WAREHOUSE_VIEW','WAREHOUSE_EXPORT'],warehouses:[{id:1},{id:2}],territories:[]};
const token='test.'+Buffer.from(JSON.stringify({exp:Date.now()/1000+3600})).toString('base64url')+'.test';
let writes=[],releaseWrite,releaseRead,slowRead=false;
const rows=[101,102].map((v,i)=>({orderId:10,orderCode:'DH-1',shipmentVoucherId:v,shipmentVoucherCode:'PX-'+v,dealerName:'Đại lý',receiverName:'Người nhận',receiverPhone:'0900000000',deliveryAddress:'Quận 1, TP.HCM',totalAmount:100+i}));
(async()=>{const browser=await chromium.launch({headless:true,channel:'chrome'});
try{
 const context=await browser.newContext({viewport:{width:1440,height:1000}});
 await context.addInitScript(({user,token})=>{sessionStorage.setItem('user',JSON.stringify(user));sessionStorage.setItem('accessToken',token);},{user,token});
 await context.route('http://localhost:8080/api/**',async route=>{
  const req=route.request(),u=new URL(req.url()),p=u.pathname.replace('/api',''),m=req.method();
  const headers={'Access-Control-Allow-Origin':base,'Access-Control-Allow-Headers':'Authorization,Content-Type','Access-Control-Allow-Methods':'GET,POST,OPTIONS'};
  if(m==='OPTIONS')return route.fulfill({status:204,headers});
  let data;
  if(p==='/auth/permissions')data={roles:user.roles,permissions:user.permissions};
  else if(p.endsWith('/options'))data={warehouses:[{id:1,code:'K1',name:'Kho 1'},{id:2,code:'K2',name:'Kho 2'}]};
  else if(p.endsWith('/eligible-orders')){
   if(slowRead && u.searchParams.get('warehouseId')==='1')await new Promise(r=>releaseRead=r);
   data=u.searchParams.get('warehouseId')==='1'?rows:[];
  }else if(m==='POST'){
   writes.push(req.postDataJSON());await new Promise(r=>releaseWrite=r);
   data={id:1,tripCode:'CG-1',warehouseName:'Kho 1',driverName:'Tài xế',vehiclePlate:'TEST-1',stops:[{stopOrder:1,receiverName:'Người nhận',receiverPhone:'0900000000',deliveryAddress:'Quận 1'}]};
  }else data={content:[]};
  await route.fulfill({status:200,headers,contentType:'application/json',body:JSON.stringify({success:true,data})});
 });
 const page=await context.newPage(),errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.goto(base+'/warehouse/delivery-routes');await page.locator('.routes-order-card').nth(1).waitFor();
 await page.locator('#routes-select-all-area-btn').click();
 assert.equal(await page.locator('#routes-staged-stops .routes-stop-item').count(),2);
 await page.locator('#route-driver-name').fill('Tài xế');await page.locator('#route-vehicle-plate').fill('TEST-1');
 await page.locator('#routes-submit-btn').click();
 await page.waitForFunction(()=>document.getElementById('routes-warehouse-select').disabled);
 await page.locator('#routes-dispatch-form').evaluate(f=>f.dispatchEvent(new Event('submit',{bubbles:true,cancelable:true})));
 assert.equal(writes.length,1);assert.deepEqual(writes[0].stops.map(s=>s.shipmentVoucherId),[101,102]);
 releaseWrite();await page.locator('#routes-print-modal').waitFor({state:'visible'});await page.locator('#routes-modal-close-x').click();
 slowRead=true;await page.locator('#routes-reload-btn').click();
 while(!releaseRead)await new Promise(r=>setTimeout(r,10));
 await page.locator('#routes-warehouse-select').selectOption('2');
 releaseRead();await page.waitForTimeout(50);assert.equal(await page.locator('.routes-order-card').count(),0);
 slowRead=false;await page.locator('#routes-warehouse-select').selectOption('1');await page.locator('.routes-order-card').nth(1).waitFor();
 user.permissions=['WAREHOUSE_VIEW'];await page.evaluate(()=>Auth.syncPermissions());await page.locator('.routes-order-card').nth(1).waitFor();
 assert.equal(await page.locator('#routes-submit-btn').isDisabled(),true);
 assert.deepEqual(errors,[]);console.log('PASS S6-06: same order/two vouchers, exact source payload, duplicate submit, scope-switch stale read, VIEW-only.');
}finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
