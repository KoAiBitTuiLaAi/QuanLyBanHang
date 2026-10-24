// Real Chrome rendering with intercepted API fixtures only. Never writes to the live DB.
const assert=require('node:assert/strict');
const {chromium}=require('playwright');
const fs=require('node:fs');
const path=require('node:path');
const base=process.env.NAV_TEST_URL||'http://127.0.0.1:5538';
const output=path.join(require('node:os').tmpdir(),'qlbh-s601');
const user={id:9,username:'lot-reader',fullName:'Nhân viên kho',roles:['ROLE_VIEWER'],permissions:['WAREHOUSE_VIEW'],warehouses:[{id:1}],territories:[]};
const token='test.'+Buffer.from(JSON.stringify({exp:Date.now()/1000+3600})).toString('base64url')+'.test';
const products=[{productId:10,productCode:'SKU-010',productName:'Sữa tươi tiệt trùng 1L',unit:'hộp',manageLot:true,effectiveThresholdDays:15},
 {productId:11,productCode:'SKU-011',productName:'<img src=x onerror=alert(1)>',unit:'lon',manageLot:false,effectiveThresholdDays:30}];
const rows=[{id:1,warehouseId:1,warehouseCode:'KHO-HN',warehouseName:'Kho Hà Nội',productId:10,productCode:'SKU-010',productName:products[0].productName,lotNumber:'LOT-EXPIRED',manufacturingDate:'2026-01-01',expiryDate:'2026-10-20',baseQuantity:12.5,baseUnit:'hộp',warningThresholdDays:15,daysUntilExpiry:-2,isExpired:true,status:'EXPIRED'},
 {id:2,warehouseId:1,warehouseCode:'KHO-HN',warehouseName:'Kho Hà Nội',productId:10,productCode:'SKU-010',productName:products[0].productName,lotNumber:'LOT-SOON',manufacturingDate:'2026-02-01',expiryDate:'2026-10-29',baseQuantity:150,baseUnit:'hộp',warningThresholdDays:15,daysUntilExpiry:7,isExpired:false,status:'EXPIRING_SOON'}];
let failSave=false,failLoad=false;const calls=[],errors=[];
const has=(...p)=>user.roles.includes('ROLE_ADMIN')||p.some(x=>user.permissions.includes(x));
(async()=>{
 fs.mkdirSync(output,{recursive:true});const browser=await chromium.launch({headless:true,channel:'chrome'});
 try{
  const context=await browser.newContext({viewport:{width:1440,height:1000},reducedMotion:'reduce'});
  await context.addInitScript(({user,token})=>{sessionStorage.setItem('user',JSON.stringify(user));sessionStorage.setItem('accessToken',token);},{user,token});
  await context.route('http://localhost:8080/api/**',async route=>{
   const req=route.request(),url=new URL(req.url()),endpoint=url.pathname.replace('/api',''),method=req.method();
   const headers={'Access-Control-Allow-Origin':base,'Access-Control-Allow-Headers':'Authorization,Content-Type','Access-Control-Allow-Methods':'GET,POST,PATCH,OPTIONS'};
   if(method==='OPTIONS')return route.fulfill({status:204,headers});
   calls.push({endpoint,method,body:req.postDataJSON(),query:Object.fromEntries(url.searchParams)});
   let status=200,message='',data=[];
   if(endpoint==='/auth/permissions')data={roles:user.roles,permissions:user.permissions};
   else if(endpoint==='/warehouse/lots/options')data={warehouses:[{id:1,code:'KHO-HN',name:'Kho Hà Nội'}],products,statuses:[{value:'ACTIVE',label:'Còn hạn'},{value:'EXPIRING_SOON',label:'Sắp hết hạn'},{value:'EXPIRED',label:'Đã quá hạn'}],minimumThresholdDays:1,maximumThresholdDays:2147483647,canConfigure:has('PRODUCT_EDIT','WAREHOUSE_MANAGE'),canExport:has('WAREHOUSE_EXPORT'),canOverrideExpiredLot:has('WAREHOUSE_APPROVE','WAREHOUSE_MANAGE'),asOfDate:'2026-10-22'};
   else if(endpoint==='/warehouse/inventory/lots'){if(failLoad){status=500;message='Không kết nối được kho';}data=rows.filter(r=>!url.searchParams.get('status')||r.status===url.searchParams.get('status'));}
   else if(endpoint==='/warehouse/lot-expiry-alerts')data={expiredCount:1,expiringSoonCount:1};
   else if(method==='PATCH'){if(failSave){status=400;message='SKU còn tồn theo lô';}else{const b=req.postDataJSON();Object.assign(products[0],{manageLot:b.manageLot,effectiveThresholdDays:b.expiryWarningDays});data=products[0];}}
   else if(endpoint==='/warehouse/export'){if(failSave){status=400;message='Tồn khả dụng đã thay đổi';}else data={voucherCode:'PX-TEST'};}
   else throw new Error('Unexpected API '+endpoint);
   await route.fulfill({status,headers,contentType:'application/json',body:JSON.stringify({success:status===200,message,data})});
  });
  const page=await context.newPage();page.on('pageerror',e=>errors.push(e.message));
  await page.goto(base+'/warehouse/lots');await page.locator('#lot-count').getByText('2 lô',{exact:true}).waitFor();
  assert.equal(await page.locator('#sidebar-nav [data-page="warehouse-lots"]').count(),1);
  assert.equal(await page.locator('[data-lot-export]').count(),0);
  await page.locator('#lot-tab-settings').click();assert.equal(await page.locator('[data-lot-config]').count(),0);assert.equal(await page.locator('#lot-settings-rows img').count(),0);
  user.permissions.push('PRODUCT_EDIT','WAREHOUSE_EXPORT');await page.evaluate(()=>Auth.syncPermissions());
  await page.locator('[data-lot-config="10"]:not([disabled])').waitFor();await page.locator('[data-lot-config="10"]').click();
  await page.locator('#lot-config-days').fill('7');failSave=true;await page.locator('#lot-config-save').click();await page.locator('#lot-config-error').getByText('SKU còn tồn theo lô').waitFor();
  assert.equal(await page.locator('#lot-config-days').inputValue(),'7');failSave=false;await page.locator('#lot-config-save').click();
  await page.waitForFunction(()=>!document.querySelector('#lot-config-dialog').open);await page.locator('[data-lot-config="10"]:not([disabled])').waitFor();
  await page.locator('[data-lot-config="10"]').click();assert.equal(await page.locator('#lot-config-days').inputValue(),'7');await page.locator('#lot-config-close').click();
  await page.locator('#lot-tab-inventory').click();assert.equal(await page.locator('[data-lot-export="1"]').count(),0);assert.equal(await page.locator('[data-lot-export="2"]').count(),1);
  user.permissions.push('WAREHOUSE_APPROVE');await page.evaluate(()=>Auth.syncPermissions());await page.locator('[data-lot-export="1"]:not([disabled])').waitFor();
  await page.locator('[data-lot-export="1"]').click();await page.locator('#lot-export-quantity').fill('1');await page.locator('#lot-export-reason').fill('Xuất xử lý hàng quá hạn');
  await page.locator('#lot-export-save').click();await page.locator('#lot-export-error').getByText(/đánh dấu xác nhận/).waitFor();assert.equal(calls.filter(c=>c.method==='POST').length,0);
  await page.locator('#lot-export-confirm').check();failSave=true;await page.locator('#lot-export-save').click();await page.locator('#lot-export-error').getByText(/Tồn khả dụng/).waitFor();assert.equal(await page.locator('#lot-export-quantity').inputValue(),'1');
  failSave=false;await page.locator('#lot-export-save').click();await page.waitForFunction(()=>!document.querySelector('#lot-export-dialog').open);await page.locator('[data-lot-export="1"]:not([disabled])').waitFor();
  const body=calls.findLast(c=>c.method==='POST').body;assert.equal(body.overrideExpiredLot,true);assert.equal(body.items[0].expiryDate,undefined);
  for(const width of [1920,1440,1024,768,390,320]){
   await page.setViewportSize({width,height:1000});await page.screenshot({path:path.join(output,'lots-'+width+'.png'),fullPage:true});
   assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),'outer overflow '+width);
  }
  await page.setViewportSize({width:390,height:844});await page.locator('[data-lot-export="1"]').click();await page.screenshot({path:path.join(output,'expired-dialog-mobile.png')});
  user.permissions=['WAREHOUSE_VIEW'];await page.evaluate(()=>Auth.syncPermissions());await page.waitForFunction(()=>!document.querySelector('#lot-export-dialog').open);
  await page.locator('#lot-count').getByText('2 lô',{exact:true}).waitFor();assert.equal(await page.locator('[data-lot-export]').count(),0);assert.equal(await page.locator('#lot-export-context').innerText(),'');
  failLoad=true;await page.locator('#lot-reload').click();await page.locator('#lot-error').getByText(/Không kết nối/).waitFor();assert.equal(await page.locator('[data-lot-export]').count(),0);failLoad=false;
  await page.locator('#lot-reload').click();await page.locator('#lot-count').getByText('2 lô',{exact:true}).waitFor();
  assert.deepEqual(errors,[]);console.log('PASS S6-01 Chrome: reader, grant/revoke, persisted config, errors, expired confirmation, XSS and 6 responsive sizes. Screenshots: '+output);
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
