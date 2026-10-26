// Chrome page + HTTP fixtures. Does not use or modify the live database.
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const {chromium}=require('playwright');const base=process.env.NAV_TEST_URL||'http://127.0.0.1:5539';
const output=path.join(require('node:os').tmpdir(),'qlbh-s602');
const user={id:11,username:'stock-reader',fullName:'Nhân viên kho',roles:['ROLE_VIEWER'],permissions:['WAREHOUSE_VIEW'],warehouses:[{id:1}],territories:[]};
const token='test.'+Buffer.from(JSON.stringify({exp:Date.now()/1000+3600})).toString('base64url')+'.test';
const warehouses=[{id:1,code:'KHO-HN',name:'Kho Hà Nội'}],products=[{id:2,code:'SKU-002',name:'Sữa tươi tiệt trùng 1L'},{id:3,code:'SKU-003',name:'<img src=x onerror=alert(1)>'}];
const entries=Array.from({length:53},(_,i)=>({rowNumber:i+1,eventTime:'2026-10-22T09:'+String(i).padStart(2,'0')+':00',eventType:i%2?'EXPORT':'IMPORT',eventTypeLabel:i%2?'Xuất kho':'Nhập kho',baseQuantityChange:i%2?-1:2,runningBaseBalance:100+2*(Math.floor(i/2)+1)-Math.ceil(i/2),baseUnit:'hộp',displayQuantity:i%2?1:2,displayUnit:'hộp',warehouseId:1,warehouseCode:'KHO-HN',warehouseName:'Kho Hà Nội',sourceType:'VOUCHER',sourceId:i+1,sourceCode:'PNK-'+String(i+1).padStart(3,'0'),note:i===0?'<img src=x onerror=alert(1)>':'Ghi nhận theo phiếu gốc'}));
const calls=[],errors=[];let fail=false,sourceFail=false,holdSource=false,releaseSource;
const allowed=()=>user.permissions.includes('WAREHOUSE_VIEW')||user.roles.includes('ROLE_ADMIN');
(async()=>{fs.mkdirSync(output,{recursive:true});const browser=await chromium.launch({channel:'chrome',headless:true});
 try{
 const context=await browser.newContext({viewport:{width:1440,height:1000},reducedMotion:'reduce'});
 await context.addInitScript(({user,token})=>{sessionStorage.setItem('user',JSON.stringify(user));sessionStorage.setItem('accessToken',token);},{user,token});
 await context.route('http://localhost:8080/api/**',async route=>{
  const req=route.request(),url=new URL(req.url()),ep=url.pathname.replace('/api','');
  const headers={'Access-Control-Allow-Origin':base,'Access-Control-Allow-Headers':'Authorization,Content-Type','Access-Control-Allow-Methods':'GET,OPTIONS'};
  if(req.method()==='OPTIONS')return route.fulfill({status:204,headers});calls.push({ep,method:req.method(),query:Object.fromEntries(url.searchParams)});
  let data=[],status=200,message='';
  if(ep==='/auth/permissions')data={roles:user.roles,permissions:user.permissions};
  else if(!allowed()){status=403;message='Quyền đã bị thu hồi';}
  else if(ep==='/warehouse/stock-card/options')data={products,warehouses,pageSizes:[20,50,100],defaultPageSize:50};
  else if(ep.includes('/sources/')){
   if(holdSource)await new Promise(r=>releaseSource=r);
   if(sourceFail){status=500;message='Không tải được chứng từ gốc';}
   const row=entries.find(r=>String(r.sourceId)===ep.split('/').at(-1));data={sourceId:row.sourceId,sourceType:row.sourceType,sourceCode:row.sourceCode,productCode:'SKU-002',productName:products[0].name,entries:[row]};
  }else if(ep.startsWith('/warehouse/stock-card/')){
   if(fail){status=500;message='Không kết nối được sổ kho';}
   const size=Number(url.searchParams.get('size')),p=Number(url.searchParams.get('page'));data={productId:2,productCode:'SKU-002',productName:products[0].name,baseUnit:'hộp',entries:entries.slice(p*size,(p+1)*size),currentPage:p,totalPages:Math.ceil(entries.length/size),totalElements:entries.length,pageSize:size,openingBalance:p?entries[p*size-1].runningBaseBalance:100};
  }else throw Error('Unexpected API '+ep);
  await route.fulfill({status,headers,contentType:'application/json',body:JSON.stringify({success:status===200,data,message})});
 });
 const page=await context.newPage();page.on('pageerror',e=>errors.push(e.message));
 await page.goto(base+'/warehouse/stock-card');await page.locator('#card-product:not([disabled])').waitFor();
 assert.equal(await page.locator('#sidebar-nav [data-page="warehouse-stock-card"]').count(),1);
 assert.equal(calls.filter(c=>c.ep.match(/stock-card\/\d/)).length,0);
 await page.locator('#card-product').selectOption('2');await page.locator('#card-warehouse').selectOption('1');await page.locator('#card-from').fill('2026-10-01');await page.locator('#card-to').fill('2026-10-22');await page.locator('#card-apply').click();
 await page.locator('#card-count').getByText('53 phát sinh',{exact:true}).waitFor();assert.equal(await page.locator('#card-rows tr').count(),50);assert.equal(await page.locator('#card-rows img').count(),0);
 assert.match(await page.locator('#card-opening').innerText(),/100 hộp/);assert.equal(calls.findLast(c=>c.ep.match(/stock-card\/\d/)).query.toDate,'2026-10-22T23:59:59.999999999');
 await page.locator('[data-card-source="0"]').click();await page.locator('#card-source-title').getByText('PNK-001',{exact:true}).waitFor();assert.equal(await page.locator('#card-source-content img').count(),0);await page.locator('#card-source-close').click();
 await page.locator('#card-next').click();await page.locator('#card-page').getByText('Trang 2 / 2',{exact:true}).waitFor();assert.equal(await page.locator('#card-rows tr').count(),3);assert.match(await page.locator('#card-opening').innerText(),/125 hộp/);
 await page.reload();await page.locator('#card-page').getByText('Trang 2 / 2',{exact:true}).waitFor();
 const href=await page.locator('[data-card-source="0"]').getAttribute('href');await page.goto(base+href);await page.locator('#card-source-title').getByText('PNK-051',{exact:true}).waitFor();await page.locator('#card-source-close').click();
 sourceFail=true;await page.locator('[data-card-source="0"]').click();await page.locator('#card-source-error').getByText(/Không tải được chứng từ/).waitFor();assert.equal(await page.locator('#card-source-content').innerText(),'');await page.locator('#card-source-close').click();sourceFail=false;
 for(const width of [1920,1440,1024,768,390,320]){
  await page.setViewportSize({width,height:1000});await page.screenshot({path:path.join(output,'stock-card-'+width+'.png'),fullPage:true});
  assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),'outer overflow '+width);
 }
 await page.setViewportSize({width:390,height:844});await page.locator('[data-card-source="0"]').click();await page.locator('#card-source-title').getByText('PNK-051',{exact:true}).waitFor();await page.screenshot({path:path.join(output,'source-mobile.png')});await page.locator('#card-source-close').click();
 fail=true;await page.locator('#card-reload').click();await page.locator('#card-error').getByText(/Không kết nối/).waitFor();assert.equal(await page.locator('[data-card-source]').count(),0);assert.equal(await page.locator('#card-product').inputValue(),'2');
 fail=false;await page.locator('#card-reload').click();await page.locator('[data-card-source="0"]').waitFor();
 holdSource=true;await page.locator('[data-card-source="0"]').click();while(!releaseSource)await page.waitForTimeout(20);
 user.permissions=[];await page.evaluate(()=>Auth.syncPermissions());releaseSource();
 await page.waitForURL(/error\?status=403/);assert.equal(await page.locator('#card-source-dialog').count(),0);
 user.permissions=['WAREHOUSE_VIEW'];await page.goto(base+'/warehouse/stock-card?productId=2');await page.locator('#card-count').getByText('53 phát sinh',{exact:true}).waitFor();
 assert.ok(calls.every(c=>c.method==='GET'),'read-only feature must never mutate');assert.deepEqual(errors,[]);
 console.log('PASS S6-02 Chrome: scoped selectors, filter interval, server balances/paging, reload/deep-link source, source error, XSS, 6 sizes, revoke late source + grant. Screenshots: '+output);
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
