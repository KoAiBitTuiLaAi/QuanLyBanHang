// Click the real financial menus using read-only API fixtures in Chrome.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require('playwright');
const base = process.env.NAV_TEST_URL || 'http://127.0.0.1:5531';
const output = path.join(require('node:os').tmpdir(), 'qlbh-s7-finance-menu');
const token = 'fixture.' + Buffer.from(JSON.stringify({exp:Math.floor(Date.now()/1000)+3600})).toString('base64url') + '.fixture';

(async () => {
  fs.mkdirSync(output, {recursive:true});
  const browser = await chromium.launch({channel:'chrome',headless:true});
  const errors = [], calls = [];
  try {
    const context = await browser.newContext({viewport:{width:1440,height:1000},reducedMotion:'reduce'});
    const user = {id:1,username:'finance-fixture',fullName:'Kế toán kiểm thử',roles:['ROLE_ADMIN'],permissions:[]};
    await context.addInitScript(({user,token}) => {
      sessionStorage.setItem('user',JSON.stringify(user));sessionStorage.setItem('accessToken',token);
    }, {user,token});
    const dealer = {id:42,dealerId:42,code:'DL42',dealerCode:'DL42',name:'Đại lý Sprint 7',dealerName:'Đại lý Sprint 7',
      customerName:'Đại lý Sprint 7',currentDebt:2000,debtLimit:5000000,maxDebtDays:30,active:true,locked:false};
    const payment = {id:1,paymentCode:'PT-S7-001',dealerId:42,dealerCode:'DL42',dealerName:dealer.name,amount:1000,
      paymentDate:'2026-10-26',paymentMethod:'CASH',paymentType:'PAYMENT',recordedBy:'Kế toán kiểm thử',allocations:[]};
    const invoice = {orderId:11,orderCode:'DH-S7-001',remainingAmount:2000,invoiceDate:'2026-10-01',recordedAt:'2026-10-01T09:00:00',totalAmount:3000,paidAmount:1000};
    await context.route('http://localhost:8080/api/**', async route => {
      const request=route.request(), url=new URL(request.url()), endpoint=url.pathname.slice(4);
      const headers={'Access-Control-Allow-Origin':base,'Access-Control-Allow-Headers':'Authorization,Content-Type','Access-Control-Allow-Methods':'GET,OPTIONS'};
      if(request.method()==='OPTIONS') return route.fulfill({status:204,headers});
      assert.equal(request.method(),'GET',`unexpected write: ${endpoint}`);
      calls.push(endpoint+url.search);
      let data=[];
      if(endpoint==='/auth/permissions') data={roles:user.roles,permissions:[]};
      else if(endpoint==='/users/profile') data=user;
      else if(endpoint==='/dealers') data={content:[dealer],totalElements:1,totalPages:1};
      else if(endpoint==='/dealers/active'||endpoint==='/finance/debt') data=[dealer];
      else if(endpoint==='/finance/debt/flagged-dealers') data=[{...dealer,debtFlagType:'OVERDUE',effectiveAction:'BLOCK',overdueAmount:2000,maxOverdueDays:10,debtFlagReason:'Quá hạn kiểm thử'}];
      else if(endpoint==='/finance/debt/config') data={autoFlagEnabled:true,limitExceededAction:'BLOCK',overdueAction:'BLOCK',defaultAction:'BLOCK'};
      else if(endpoint==='/finance/debt-payments') data={content:[payment],totalElements:1,totalPages:1};
      else if(endpoint==='/finance/debt-payments/1') data=payment;
      else if(endpoint==='/finance/dealers/42/unpaid-invoices') data=[invoice];
      else if(endpoint==='/finance/debt-aging-ledger') data={asOfDate:'2026-10-26',dealers:[{...dealer,isOverdue:true,totalReceivable:3000,totalPaid:1000,remainingDebt:2000,bucket0To30:2000,bucket31To60:0,bucket61To90:0,bucketOver90:0}],summary:{totalReceivable:3000,totalPaid:1000,totalRemainingDebt:2000,totalBucket0To30:2000,totalBucket31To60:0,totalBucket61To90:0,totalBucketOver90:0,overdueDealerCount:1}};
      else if(endpoint==='/finance/debt/42/statement') data={dealerId:42,dealerName:dealer.name,dealerCode:'DL42',from:'2026-10-01',to:'2026-10-26',openingBalance:3000,totalIncrease:0,totalDecrease:1000,closingBalance:2000,entries:[{sourceType:'PAYMENT',documentCode:'PT-S7-001',occurredAt:'2026-10-26T09:00:00',description:'Khoản thu kiểm thử',increase:0,decrease:1000,balance:2000}]};
      await route.fulfill({status:200,headers,contentType:'application/json',body:JSON.stringify({success:true,data})});
    });
    const page = await context.newPage();
    page.on('pageerror',e=>errors.push(e.message));
    await page.goto(base+'/dashboard');
    await page.locator('.sidebar a[href="/dashboard?view=debt-limits"]').click();
    await page.locator('#debt-alerts-rows').getByText(dealer.name).waitFor();
    await page.locator('#tab-btn-config').click();
    await page.waitForFunction(()=>document.getElementById('cfg-auto-flag').checked);
    await page.locator('#tab-btn-limits').click();
    await page.locator('#debt-limits-rows').getByText(dealer.name).waitFor();
    await page.locator('#tab-btn-payments').click();
    await page.screenshot({path:path.join(output,'s704-tabs.png'),fullPage:true});

    await page.locator('.sidebar a[href="/finance/debt-payments"]').click();
    await page.locator('#payments-tbody').getByText('PT-S7-001').waitFor();
    await page.locator('[data-action="view-detail"][data-id="1"]').click();
    await page.locator('#payment-detail-info').getByText('PT-S7-001').waitFor();
    await page.locator('[data-close-modal="payment-detail"]').first().click();
    await page.locator('#dp-btn-create-payment').click();
    await page.locator('#create-dealer-select').selectOption('42');
    await page.locator('#dealer-debt-info-banner').waitFor({state:'visible'});
    assert.equal(await page.evaluate(()=>Boolean(document.elementFromPoint(20,200)?.closest('#modal-payment-create'))),true,'payment dialog must cover the sidebar as well');
    for(const width of [1440,768,390,320]) {
      await page.setViewportSize({width,height:1000});
      const box=await page.locator('#modal-payment-create .dp-modal').boundingBox();
      assert.ok(box.x>=0 && box.x+box.width<=width,`dialog clipped at ${width}px`);
      const submit=await page.locator('#btn-submit-payment').boundingBox();
      assert.ok(submit.y+submit.height<=box.y+box.height && submit.x+submit.width<=box.x+box.width,`dialog footer clipped at ${width}px`);
      assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),true,`page overflow at ${width}px`);
      await page.screenshot({path:path.join(output,`s702-create-${width}.png`),fullPage:true});
    }
    await page.setViewportSize({width:1440,height:1000});
    await page.locator('[data-close-modal="payment-create"]').first().click();

    await page.locator('.sidebar a[href="/finance/debt-statement"]').click();
    await page.locator('#ds-reload').click();
    await page.locator('#ds-rows').getByText(dealer.name).waitFor();
    await page.locator('#ds-overdue').check();
    await page.locator('#ds-apply').click();
    await page.waitForFunction(()=>document.getElementById('ds-progress').hidden);
    await page.locator('#debt-tab-statement').click();
    await page.locator('#filter-dealer').selectOption('42');
    await page.locator('#filter-from').fill('2026-10-01');
    await page.locator('#filter-to').fill('2026-10-26');
    await page.locator('#btn-view-statement').click();
    await page.locator('#statement-tbody').getByText('Khoản thu kiểm thử').waitFor();
    await page.screenshot({path:path.join(output,'s703-statement.png'),fullPage:true});
    assert.ok(calls.some(url=>url.includes('debt-aging-ledger?')&&url.includes('overdueOnly=true')));
    assert.deepEqual(errors,[]);
    console.log('PASS S7-02/03/04 Chrome: click sidebar menus, risk tabs/config, payment history/detail/create form, aging filters/totals and detailed ledger. GET fixtures only. Screenshots: '+output);
  } finally {await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
