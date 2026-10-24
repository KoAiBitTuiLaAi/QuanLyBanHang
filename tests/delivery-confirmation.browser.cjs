// S6-08 frontend preview only. Every API is intercepted; no business writes allowed.
const {chromium}=require('playwright'),assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const base=process.env.NAV_TEST_URL||'http://127.0.0.1:5500',output=path.join(require('node:os').tmpdir(),'qlbh-s608-preview');
const user={id:22,username:'delivery-reader',fullName:'Nhân viên kiểm thử',roles:['ROLE_VIEWER'],permissions:['ORDER_VIEW','WAREHOUSE_EXPORT'],warehouses:[{id:1}],territories:[]};
const token='test.'+Buffer.from(JSON.stringify({exp:Date.now()/1000+3600})).toString('base64url')+'.test';
const order={id:1,orderCode:'DH-REAL-01',customerName:'<img src=x onerror=alert(1)>',warehouseId:1,warehouseName:'Kho thực từ API',status:'EXPORTED',statusDisplayName:'Đã xuất kho',totalAmount:120000,expectedDeliveryDate:'2026-10-24',items:[]};
const calls=[],errors=[];let failure=false,hold=null;
(async()=>{fs.mkdirSync(output,{recursive:true});const browser=await chromium.launch({headless:true,channel:'chrome'});
 try{
  const context=await browser.newContext({viewport:{width:1440,height:1000},reducedMotion:'reduce'});
  await context.addInitScript(({user,token})=>{sessionStorage.setItem('user',JSON.stringify(user));sessionStorage.setItem('accessToken',token);},{user,token});
  await context.route('http://localhost:8080/api/**',async route=>{
   const req=route.request(),url=new URL(req.url()),endpoint=url.pathname.replace('/api',''),method=req.method();
   const headers={'Access-Control-Allow-Origin':base,'Access-Control-Allow-Headers':'Authorization,Content-Type','Access-Control-Allow-Methods':'GET,OPTIONS'};
   if(method==='OPTIONS')return route.fulfill({status:204,headers});calls.push({endpoint,method,query:Object.fromEntries(url.searchParams)});
   assert.equal(method,'GET','S6-08 preview must never write');let body,status=200;
   if(endpoint==='/auth/permissions')body={success:true,data:{roles:user.roles,permissions:user.permissions}};
   else if(endpoint==='/orders'){if(hold)await hold;if(failure){status=500;body={success:false,message:'Lỗi tải đơn thật'};}else body={success:true,data:[order],totalElements:21,totalPages:2};}
   else if(endpoint==='/orders/1')body={success:true,data:order};else throw Error('Unexpected API '+endpoint);
   await route.fulfill({status,headers,contentType:'application/json',body:JSON.stringify(body)});
  });
  const page=await context.newPage();page.on('pageerror',e=>errors.push(e.message));
  await page.goto(base+'/warehouse/delivery');await page.locator('[data-confirm-order="1"]').waitFor();
  assert.equal(await page.locator('#sidebar-nav [data-page="delivery-confirmation"]').count(),1);
  assert.equal(await page.locator('#dc-orders-tbody img').count(),0);assert.equal(await page.locator('#stat-invoice-ready').innerText(),'—');
  await page.locator('[data-confirm-order="1"]').click();await page.locator('#modal-confirm-delivery').waitFor({state:'visible'});
  assert.equal(await page.locator('#confirm-warehouse-name').inputValue(),'Kho thực từ API');assert.equal(await page.locator('#btn-submit-confirm').isDisabled(),true);
  await page.locator('#confirm-recipient-name').fill('Người nhận thử');
  await page.locator('#confirm-proof-file').setInputFiles({name:'wrong.svg',mimeType:'image/svg+xml',buffer:Buffer.from('<svg/>')});
  await page.locator('#err-proof-image.dc-field-error--show').waitFor();
  await page.locator('#confirm-proof-file').setInputFiles({name:'not-image.png',mimeType:'image/png',buffer:Buffer.from('not an image')});await page.locator('#err-proof-image').getByText(/Không đọc được ảnh/).waitFor();
  const png=Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=','base64');
  await page.locator('#confirm-proof-file').setInputFiles({name:'<img src=x>.png',mimeType:'image/png',buffer:png});
  await page.locator('#proof-preview-container').waitFor({state:'visible'});assert.match(await page.locator('#proof-preview-img').getAttribute('src'),/^blob:/);
  assert.match(await page.locator('.dc-proof-preview__badge').innerText(),/chưa lưu/);assert.equal(await page.locator('#proof-file-name img').count(),0);
  await page.locator('#btn-zoom-proof').click();await page.locator('#modal-image-zoom').waitFor({state:'visible'});await page.keyboard.press('Escape');assert.equal(await page.locator('#modal-image-zoom').isVisible(),false);
  await page.screenshot({path:path.join(output,'delivery-form-1440.png')});
  await page.locator('[data-close-confirm]').first().click();assert.equal(await page.locator('#proof-preview-img').getAttribute('src'),null);
  await page.locator('[data-failed-order="1"]').click();await page.locator('#modal-failed-delivery').waitFor({state:'visible'});
  assert.equal(await page.locator('#btn-submit-failed').isDisabled(),true);assert.equal(await page.locator('#failed-voucher-code').inputValue(),'Chưa tạo phiếu');
  await page.locator('[data-close-failed]').first().click();
  await page.locator('#dc-next').click();await page.locator('#dc-page').getByText('Trang 2 / 2').waitFor();assert.equal(calls.findLast(c=>c.endpoint==='/orders').query.page,'1');
  await page.locator('#dc-search').fill('ĐL');await page.locator('#dc-search').press('Enter');await page.locator('#dc-page').getByText('Trang 1 / 2').waitFor();assert.equal(calls.findLast(c=>c.endpoint==='/orders').query.keyword,'ĐL');
  for(const width of [1920,1440,1024,768,390,320]){
   await page.setViewportSize({width,height:1000});
   const layout=await page.evaluate(()=>({ok:document.documentElement.scrollWidth<=innerWidth+1,overflow:[...document.querySelectorAll('body *')].filter(e=>e.getBoundingClientRect().right>innerWidth+1 && getComputedStyle(e).position!=='fixed').slice(0,12).map(e=>[e.tagName,e.id,e.className,e.getBoundingClientRect().right])}));
   assert.ok(layout.ok,'delivery outer overflow '+width+' '+JSON.stringify(layout.overflow));
  }
  await page.setViewportSize({width:390,height:844});await page.screenshot({path:path.join(output,'delivery-390.png'),fullPage:true});
  await page.locator('[data-confirm-order="1"]').click();await page.locator('#modal-confirm-delivery').waitFor({state:'visible'});await page.screenshot({path:path.join(output,'delivery-form-390.png')});
  user.permissions=['ORDER_VIEW'];await page.evaluate(()=>Auth.syncPermissions());await page.waitForURL(/error\?status=403/);
  assert.equal(calls.filter(c=>c.method!=='GET').length,0);assert.deepEqual(errors,[]);
  user.permissions.push('WAREHOUSE_EXPORT');await page.goto(base+'/warehouse/delivery');await page.locator('[data-confirm-order="1"]').waitFor();
  failure=true;await page.locator('#dc-btn-refresh').click();await page.locator('#dc-error').getByText('Lỗi tải đơn thật').waitFor();assert.equal(await page.locator('[data-confirm-order]').count(),0);
  failure=false;await page.locator('#dc-btn-refresh').click();await page.locator('[data-confirm-order="1"]').waitFor();
  let release;hold=new Promise(r=>release=r);await page.locator('#dc-btn-refresh').click();user.permissions=[];await page.evaluate(()=>Auth.syncPermissions());release();hold=null;await page.waitForURL(/error\?status=403/);
  assert.equal(calls.filter(c=>c.method!=='GET').length,0);assert.deepEqual(errors,[]);console.log('PASS S6-08 frontend: menu, GET-only orders/pagination/search, XSS, draft forms, valid/invalid image previews, disabled writes, errors/revocation/late reads, 6 widths. '+output);
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
