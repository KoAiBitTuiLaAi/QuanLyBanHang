// Real Chrome + running backend/DB. Only authentication writes; no business records changed.
const assert=require('node:assert/strict'), fs=require('node:fs'), path=require('node:path');
const {chromium}=require('playwright');
const base=process.env.QLBH_BASE_URL||'http://localhost:5500';
const api=process.env.QLBH_API_URL||'http://localhost:8080/api';
const output=process.env.QLBH_TEST_OUTPUT||path.join(require('node:os').tmpdir(),'qlbh-auth-tabs');
const report={checks:[],errors:[]};
const check=name=>{report.checks.push(name);console.log(name);};
(async()=>{
 assert.ok(process.env.QLBH_TEST_USERNAME&&process.env.QLBH_TEST_PASSWORD,'Set test login variables');
 fs.mkdirSync(output,{recursive:true});
 const browser=await chromium.launch({channel:'chrome',headless:true});
 const context=await browser.newContext({viewport:{width:1440,height:1000},reducedMotion:'reduce'});
 context.on('page',page=>page.on('pageerror',error=>report.errors.push(error.message)));
 const settle=async page=>{
   await page.waitForLoadState('domcontentloaded');
   if (new URL(page.url()).pathname==='/login') {
     await page.waitForFunction(()=>document.querySelector('button[type="submit"]')?.disabled===false);
   } else {
     await page.waitForFunction(()=>typeof Auth!=='undefined'&&Auth.isLoggedIn()
       &&document.querySelector('#sidebar-nav a')&&!document.querySelector('#sidebar-nav').inert);
   }
 };
 async function authenticated(page,pathname) {
   await page.waitForURL(base+pathname);await settle(page);
   await page.waitForFunction(()=>typeof Auth!=='undefined'&&Auth.isLoggedIn());
   assert.equal(new URL(page.url()).pathname,pathname);
 }
 try {
   const guest=await browser.newContext();const denied=await guest.newPage();
   let guestLogins=0;denied.on('framenavigated',frame=>{if(frame===denied.mainFrame()&&new URL(frame.url()).pathname==='/login')guestLogins++;});
   await denied.goto(base+'/finance/debt-statement');await denied.waitForURL(base+'/login');await settle(denied);
   assert.equal(guestLogins,1);check('Guest without cookie reaches login once, without redirect loop');await guest.close();

   const first=await context.newPage();await first.goto(base+'/login');
   await first.locator('#username').fill(process.env.QLBH_TEST_USERNAME);
   await first.locator('#password').fill(process.env.QLBH_TEST_PASSWORD);
   await first.locator('button[type="submit"]').click();await authenticated(first,'/dashboard');
   const cookie=(await context.cookies(api+'/auth/refresh')).find(c=>c.name==='qlbh_refresh');
   assert.ok(cookie?.httpOnly);assert.equal(cookie.sameSite,'Strict');assert.equal(cookie.path,'/api/auth');
   assert.equal(await first.evaluate(()=>sessionStorage.getItem('refreshToken')),null);
   assert.equal(await first.evaluate(()=>document.cookie.includes('qlbh_refresh')),false);
   check('Actual login sets HttpOnly scoped cookie; refresh credential absent from JS storage');

   await first.locator('#sidebar-nav a[href="/finance/debt-statement"]').click();await authenticated(first,'/finance/debt-statement');
   // Ensure the second tab receives an access token issued in a different second.
   await first.waitForTimeout(1100);
   const popup=context.waitForEvent('page');
   await first.locator('#sidebar-nav a[href="/orders"]').click({modifiers:['Control']});
   const second=await popup;await second.bringToFront();await authenticated(second,'/orders');
   assert.equal(await second.evaluate(()=>Auth.getUser().username),process.env.QLBH_TEST_USERNAME);
   await second.waitForFunction(()=>{
     const count=document.getElementById('ord-count-text')?.textContent;
     return count&&!count.includes('Đang tải')&&!document.querySelector('#ord-tbody .dlr-loading');
   });
   const listStatus=await second.evaluate(async()=> (await Auth.apiFetch('/orders?size=1')).status);
   assert.equal(listStatus,200);
   check('Real Ctrl+click from debt statement opens Orders authenticated and loads business data');
   await second.screenshot({path:path.join(output,'ctrl-click-orders.png'),fullPage:true});

   let restores=0;context.on('request',r=>{if(r.url()===api+'/auth/refresh')restores++;});
   const direct=await context.newPage();await direct.goto(base+'/reports/dashboard');await authenticated(direct,'/reports/dashboard');
   assert.ok(restores>0);check('Independent tab with empty sessionStorage restores through backend cookie');
   const beforeCloseToken=await direct.evaluate(()=>Auth.getAccessToken());
   const tabs=await Promise.all(['/warehouse/inventory','/finance/debt-statement'].map(async destination=>{
     const page=await context.newPage();await page.goto(base+destination);await authenticated(page,destination);return page;
   }));
   check('Two tabs restore concurrently without invalidating each other');
   await first.close();await second.reload();await authenticated(second,'/orders');
   const afterClose=await context.newPage();await afterClose.goto(base+'/sales-targets');await authenticated(afterClose,'/sales-targets');
   check('Session survives closing original tab and opening another destination');

   let release, started;const gate=new Promise(r=>release=r), responseReady=new Promise(r=>started=r);
   const pending=await context.newPage();
   await pending.route(api+'/auth/refresh',async route=>{
     const actual=await route.fetch();started();await gate;
     await route.fulfill({response:actual}).catch(()=>{});
   });
   await pending.goto(base+'/warehouse/inventory');await responseReady;
   await second.locator('#btn-logout').click();await second.waitForURL(base+'/login');await settle(second);
   release();await pending.waitForURL(base+'/login');
   for(const page of [direct,...tabs,afterClose,pending]) {
     await page.waitForURL(base+'/login');await settle(page);
     assert.equal(await page.evaluate(()=>sessionStorage.getItem('accessToken')),null);
     assert.equal(await page.evaluate(()=>sessionStorage.getItem('user')),null);
   }
   check('Sidebar logout signs out all tabs; delayed real refresh cannot restore the session');
   const revoked=await context.request.get(api+'/auth/me',{headers:{Authorization:'Bearer '+beforeCloseToken}});
   assert.equal(revoked.status(),401);check('Access token previously issued to another tab is revoked in DB');
   const fresh=await context.newPage();await fresh.goto(base+'/orders');await fresh.waitForURL(base+'/login');await settle(fresh);
   check('New tab after logout cannot restore a revoked cookie');
   assert.deepEqual(report.errors,[]);
 } catch(error) {report.failure=error.message;throw error;}
 finally {fs.writeFileSync(path.join(output,'auth-tabs-report.json'),JSON.stringify(report,null,2));await browser.close();}
})().catch(error=>{console.error(error.message);process.exitCode=1;});
