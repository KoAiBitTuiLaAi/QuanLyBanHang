// Chrome + intercepted API fixtures, never the live database.
const assert=require('node:assert/strict'),{chromium}=require('playwright'),fs=require('node:fs'),path=require('node:path');
const base=process.env.NAV_TEST_URL||'http://127.0.0.1:5543',output=path.join(require('node:os').tmpdir(),'qlbh-s604');
const user={id:19,username:'fefo-reader',fullName:'Nhân viên kiểm thử',roles:['ROLE_VIEWER'],permissions:['WAREHOUSE_VIEW'],warehouses:[{id:1}],territories:[]};
const token='test.'+Buffer.from(JSON.stringify({exp:Date.now()/1000+3600})).toString('base64url')+'.test';
const products=[{productId:10,productCode:'SKU-010',productName:'Sữa tươi tiệt trùng 1L',unit:'hộp',manageLot:true,effectiveThresholdDays:15}];
const warehouses=[{id:1,code:'KHO-HN',name:'Kho Hà Nội'}],calls=[],errors=[];
let fail=false,holdSuggestion=null,holdWrite=null,insufficient=false;
function preview(){return {warehouseId:1,productId:10,manageLot:true,requestedQuantity:10,requestedUnit:'hộp',requestedBaseQuantity:10,baseUnit:'hộp',
 suggestedLot:insufficient?null:{lotNumber:'LOT-B'},message:insufficient?'Không có lô đơn lẻ còn đủ số lượng và còn hạn sử dụng.':'Gợi ý xuất lô LOT-B (hạn dùng gần nhất còn đủ số lượng)',
 availableLots:[{lotNumber:'LOT-A',expiryDate:'2030-01-01',availableQuantity:5,baseUnit:'hộp',isExpired:false,sufficientForRequest:false},
 {lotNumber:'LOT-B',expiryDate:'2030-02-01',availableQuantity:20,baseUnit:'hộp',isExpired:false,sufficientForRequest:!insufficient},
 {lotNumber:'LOT-C',expiryDate:'2030-03-01',availableQuantity:50,baseUnit:'hộp',isExpired:false,sufficientForRequest:!insufficient},
 {lotNumber:'<img src=x onerror=alert(1)>',expiryDate:'2020-01-01',availableQuantity:99,baseUnit:'hộp',isExpired:true,sufficientForRequest:true},
 {lotNumber:'NO-DATE',expiryDate:null,availableQuantity:99,baseUnit:'hộp',isExpired:false,sufficientForRequest:true}]};}
(async()=>{
 fs.mkdirSync(output,{recursive:true});const browser=await chromium.launch({headless:true,channel:'chrome'});
 try{
  const context=await browser.newContext({viewport:{width:1440,height:1000},reducedMotion:'reduce'});
  await context.addInitScript(({user,token})=>{sessionStorage.setItem('user',JSON.stringify(user));sessionStorage.setItem('accessToken',token);},{user,token});
  await context.route('http://localhost:8080/api/**',async route=>{
   const req=route.request(),url=new URL(req.url()),endpoint=url.pathname.replace('/api',''),method=req.method();
   const headers={'Access-Control-Allow-Origin':base,'Access-Control-Allow-Headers':'Authorization,Content-Type','Access-Control-Allow-Methods':'GET,POST,OPTIONS'};
   if(method==='OPTIONS')return route.fulfill({status:204,headers});
   calls.push({endpoint,method,body:req.postDataJSON(),query:Object.fromEntries(url.searchParams)});
   let data,status=200,message='';
   if(endpoint==='/auth/permissions')data={roles:user.roles,permissions:user.permissions};
   else if(endpoint==='/warehouse/lots/options')data={warehouses,products,statuses:[],minimumThresholdDays:1,maximumThresholdDays:365,canExport:user.permissions.includes('WAREHOUSE_EXPORT'),asOfDate:'2026-10-23'};
   else if(endpoint==='/warehouse/inventory/lots')data=[];
   else if(endpoint==='/warehouse/lot-expiry-alerts')data={expiredCount:0,expiringSoonCount:0};
   else if(endpoint==='/warehouse/export/suggest-lot'){
    const snapshot=preview();if(holdSuggestion)await holdSuggestion;
    data=snapshot;
   }else if(endpoint==='/warehouse/export'){
    if(holdWrite)await holdWrite;
    if(fail){status=400;message='Tồn lô đã thay đổi, lấy lại gợi ý.';}else data={voucherCode:'PX-TEST-604',items:req.postDataJSON().items};
   }else throw Error('Unexpected API '+endpoint);
   await route.fulfill({status,headers,contentType:'application/json',body:JSON.stringify({success:status===200,data,message})});
  });
  const page=await context.newPage();page.on('pageerror',e=>errors.push(e.message));
  await page.goto(base+'/warehouse/lots?tab=fefo');await page.locator('#fefo-warehouse option[value="1"]').waitFor({state:'attached'});
  const form=async()=>{await page.locator('#fefo-warehouse').selectOption('1');await page.locator('#fefo-product').selectOption('10');await page.locator('#fefo-quantity').fill('10');};
  const suggest=async()=>{await form();await page.locator('#fefo-suggest').click();await page.locator('#fefo-rows input[value="LOT-B"]').waitFor();};
  await suggest();assert.equal(await page.locator('#fefo-rows input[value="LOT-B"]').isChecked(),true);
  assert.equal(await page.locator('#fefo-save').isVisible(),false);assert.equal(await page.locator('#fefo-readonly').isVisible(),true);
  assert.equal(await page.locator('#fefo-rows input[value="LOT-A"]').isDisabled(),true);assert.equal(await page.locator('#fefo-rows input[value="NO-DATE"]').isDisabled(),true);
  assert.equal(await page.locator('#fefo-rows img').count(),0);
  user.permissions.push('WAREHOUSE_EXPORT');await page.evaluate(()=>Auth.syncPermissions());await page.locator('#fefo-warehouse option[value="1"]').waitFor({state:'attached'});
  await suggest();await page.locator('#fefo-rows input[value="LOT-C"]').check();await page.locator('#fefo-confirm').check();await page.locator('#fefo-save').click();
  assert.equal(calls.filter(c=>c.endpoint==='/warehouse/export').length,0);assert.equal(await page.locator('#fefo-reason').evaluate(e=>e.validity.valueMissing),true);
  await page.locator('#fefo-reason').fill('Khách chỉ định lô xa hạn hơn');await page.locator('#fefo-confirm').check();fail=true;
  await page.locator('#fefo-save').click();await page.locator('#fefo-error').getByText(/Tồn lô đã thay đổi/).waitFor();
  assert.equal(await page.locator('#fefo-reason').inputValue(),'Khách chỉ định lô xa hạn hơn');assert.equal(await page.locator('#fefo-rows input[value="LOT-C"]').isChecked(),true);
  fail=false;await page.locator('#fefo-save').click();await page.locator('#fefo-success').getByText(/PX-TEST-604/).waitFor();
  const write=calls.findLast(c=>c.endpoint==='/warehouse/export');assert.equal(write.body.items[0].lotNumber,'LOT-C');assert.equal(write.body.items[0].quantity,'10');
  assert.equal(write.body.items[0].overrideReason,'Khách chỉ định lô xa hạn hơn');assert.equal(write.body.items[0].expiryDate,undefined);
  await suggest();
  for(const width of [1920,1440,1024,768,390,320]){
   await page.setViewportSize({width,height:1000});assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),'outer overflow '+width);
   if([1440,390].includes(width))await page.screenshot({path:path.join(output,'fefo-'+width+'.png'),fullPage:true});
  }
  await page.setViewportSize({width:1440,height:1000});let release;holdSuggestion=new Promise(r=>release=r);
  await page.locator('#fefo-suggest').click();await page.locator('#fefo-quantity').fill('11');release();holdSuggestion=null;
  await page.waitForTimeout(100);assert.equal(await page.locator('#fefo-preview').isVisible(),false);
  await suggest();user.permissions=['WAREHOUSE_VIEW'];await page.evaluate(()=>Auth.syncPermissions());await page.waitForFunction(()=>document.getElementById('fefo-preview').hidden);
  await page.locator('#fefo-warehouse option[value="1"]').waitFor({state:'attached'});await suggest();assert.equal(await page.locator('#fefo-save').isVisible(),false);
  user.permissions.push('WAREHOUSE_EXPORT');await page.evaluate(()=>Auth.syncPermissions());await page.locator('#fefo-warehouse option[value="1"]').waitFor({state:'attached'});
  insufficient=true;await suggest();assert.equal(await page.locator('#fefo-save').isDisabled(),true);insufficient=false;
  await suggest();await page.locator('#fefo-confirm').check();let finish;holdWrite=new Promise(r=>finish=r);
  const before=calls.filter(c=>c.endpoint==='/warehouse/export').length;await page.locator('#fefo-save').click();
  await page.waitForFunction(()=>document.getElementById('fefo-save').disabled);assert.equal(calls.filter(c=>c.endpoint==='/warehouse/export').length,before+1);
  user.permissions=['WAREHOUSE_VIEW'];await page.evaluate(()=>Auth.syncPermissions());finish();holdWrite=null;
  await page.waitForFunction(()=>!document.getElementById('fefo-reload').disabled);assert.equal(await page.locator('#fefo-success').isVisible(),false);
  user.permissions=[];await page.evaluate(()=>Auth.syncPermissions());assert.equal(await page.locator('#lot-tab-fefo').isVisible(),false);
  assert.deepEqual(errors,[]);console.log('PASS S6-04 Chrome: server FEFO/default, override reason, exact payload, errors, read-only, grant/revoke, late requests, duplicates, XSS, no-single-lot and 6 widths. '+output);
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
