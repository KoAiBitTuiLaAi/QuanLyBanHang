// Chrome UI + isolated HTTP fixtures. Never connects to the business database.
const assert = require('node:assert/strict');
const fs = require('node:fs'), path = require('node:path'), os = require('node:os');
const {chromium} = require('playwright');
const U = require('../frontend/js/cash-collection-utils.js');
const base = process.env.NAV_TEST_URL || 'http://127.0.0.1:5535';
const output = path.join(os.tmpdir(), 'qlbh-s705');
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl6yQAAAABJRU5ErkJggg==','base64');
const today = U.today();
const token = 'fixture.' + Buffer.from(JSON.stringify({exp:Math.floor(Date.now()/1000)+3600})).toString('base64url') + '.fixture';
const allPermissions = ['COLLECTION_CREATE','COLLECTION_CONFIRM','COLLECTION_RECONCILE'];
const exactJson = object => JSON.stringify(object).replace(/("(?:amount|collected|received|pending)":)"(-?\d+(?:\.\d+)?)"/g,'$1$2');
const decimal = value => `${value / 100n}.${String(value % 100n).padStart(2,'0')}`;
(async () => {
  fs.mkdirSync(output, {recursive:true});
  const browser = await chromium.launch({headless:true, channel:'chrome'});
  const errors = [], unexpected = [];
  let cases = 0;
  async function setup(permissions = allPermissions, actor = 9, admin = false) {
    const roles = admin ? ['ROLE_ADMIN'] : ['ROLE_VIEWER']; // grants must work outside default roles
    const state = {permissions:[...permissions], calls:[], rows:[
      {id:1,dealerId:11,dealerName:'Cửa hàng An Phát',employeeId:8,employeeName:'Nhân viên tuyến A',routeName:'Tuyến Đông',amount:'125000.50',collectionDate:today,status:'PENDING',note:'Thu tại cửa hàng',createdAt:today+'T09:00:00',receivedBy:null,receivedAt:null},
      {id:2,dealerId:12,dealerName:'<img src=x onerror=alert(1)>',employeeId:9,employeeName:'Người ghi khoản thu',routeName:'Tuyến Tây',amount:'75000.25',collectionDate:today,status:'PENDING',note:'<script>window.__cashXss=1</script>',createdAt:today+'T10:00:00'},
    ], saveStatus:201, confirmStatus:200, listStatus:200, reportStatus:200, detailDelay:0, receiptDelay:0, listDelay:0, dealerPages:1};
    const user = {id:actor,username:'cash-fixture',fullName:'Người kiểm thử',roles,permissions:state.permissions,warehouses:[],territories:[]};
    const context = await browser.newContext({viewport:{width:1440,height:1000},reducedMotion:'reduce'});
    await context.addInitScript(({user,token}) => {sessionStorage.setItem('user',JSON.stringify(user));sessionStorage.setItem('accessToken',token);}, {user,token});
    await context.route('http://localhost:8080/api/**', async route => {
      const request = route.request(), url = new URL(request.url()), endpoint = url.pathname.slice(4), method = request.method();
      const headers = {'Access-Control-Allow-Origin':base,'Access-Control-Allow-Headers':'Authorization,Content-Type','Access-Control-Allow-Methods':'GET,POST,OPTIONS'};
      if (method === 'OPTIONS') return route.fulfill({status:204,headers});
      state.calls.push({endpoint,method,query:url.search,raw:request.postDataBuffer(),headers:request.headers()});
      let status = 200, data, message = 'Thành công';
      const has = p => admin || state.permissions.includes(p);
      if (endpoint === '/auth/permissions') data = {roles,permissions:state.permissions};
      else if (!allPermissions.some(has)) {status=403;message='Quyền đã bị thu hồi';}
      else if (endpoint === '/finance/cash-collections/dealers') {
        if (!has('COLLECTION_CREATE')) status=403;
        else data={content:[{id:11,code:'DL11',name:'Cửa hàng An Phát',routeName:'Tuyến Đông'}],totalElements:state.dealerPages*20,totalPages:state.dealerPages};
      } else if (endpoint === '/finance/cash-collections/reconciliation') {
        if (!has('COLLECTION_CREATE') && !has('COLLECTION_RECONCILE')) status=403;
        else {
          status=state.reportStatus;
          const selected = state.rows.filter(row => (has('COLLECTION_RECONCILE') || has('COLLECTION_CONFIRM') || row.employeeId===actor) && (!url.searchParams.get('employeeId') || String(row.employeeId)===url.searchParams.get('employeeId')) && row.collectionDate>=url.searchParams.get('from') && row.collectionDate<=url.searchParams.get('to'));
          const groups = new Map();
          for (const row of selected) {
            const key=row.employeeId+':'+row.collectionDate;
            const group=groups.get(key)||{employeeId:row.employeeId,employeeName:row.employeeName,date:row.collectionDate,collected:0n,received:0n,count:0};
            group.collected+=U.cents(row.amount);if(row.status==='RECEIVED')group.received+=U.cents(row.amount);group.count++;groups.set(key,group);
          }
          data=[...groups.values()].map(g=>({...g,collected:decimal(g.collected),received:decimal(g.received),pending:decimal(g.collected-g.received)}));
        }
      } else if (endpoint === '/finance/cash-collections' && method === 'GET') {
        const snapshot=state.rows.filter(row=>(has('COLLECTION_CONFIRM')||has('COLLECTION_RECONCILE')||row.employeeId===actor)&&(!url.searchParams.get('employeeId')||String(row.employeeId)===url.searchParams.get('employeeId'))&&row.collectionDate>=url.searchParams.get('from')&&row.collectionDate<=url.searchParams.get('to'));
        if(state.listDelay)await pause(state.listDelay);
        const page=Number(url.searchParams.get('page')||0);
        status=state.listStatus;data={content:snapshot.slice(page*20,page*20+20),totalPages:Math.ceil(snapshot.length/20),totalElements:snapshot.length};
      } else if (endpoint === '/finance/cash-collections' && method === 'POST') {
        if(!has('COLLECTION_CREATE'))status=403;
        else {
          await pause(120);status=state.saveStatus;
          const raw=request.postDataBuffer().toString('utf8');
          assert.match(request.headers()['content-type'],/^multipart\/form-data; boundary=/);
          assert.match(raw,/name="data"[\s\S]+Content-Type: application\/json/);
          assert.match(raw,/name="receipt"; filename="bien-lai.png"/);
          const payload=JSON.parse(raw.match(/\r\n\r\n(\{[^\r\n]+\})\r\n/)[1]);
          assert.deepEqual(Object.keys(payload).sort(),['amount','collectionDate','dealerId','note']);
          if(status===201){data={...state.rows[0],...payload,id:3,employeeId:actor,employeeName:'Người kiểm thử',dealerName:'Cửa hàng An Phát',status:'PENDING'};state.rows.push(data);}
        }
      } else if (/^\/finance\/cash-collections\/\d+(?:\/receipt|\/confirm)?$/.test(endpoint)) {
        const id=Number(endpoint.split('/')[3]), row=state.rows.find(r=>r.id===id);
        if(!row)status=404;
        else if(endpoint.endsWith('/receipt')) {
          if(state.receiptDelay)await pause(state.receiptDelay);
          return route.fulfill({status:200,headers,contentType:'image/png',body:png}).catch(()=>{});
        } else if(endpoint.endsWith('/confirm')) {
          if(!has('COLLECTION_CONFIRM')||row.employeeId===actor)status=403;
          else {
            await pause(120);status=state.confirmStatus;
            if(status===200||status===409){row.status='RECEIVED';row.receivedBy='ke-toan';row.receivedAt=today+'T11:00:00';}data=row;
          }
        } else {data={...row};if(state.detailDelay)await pause(state.detailDelay);}
      } else {unexpected.push({endpoint,method});status=404;message='Unexpected fixture request';}
      if(status>=400)message='Lỗi kiểm thử '+status;
      await route.fulfill({status,headers,contentType:'application/json',body:exactJson({success:status<400,data,message})}).catch(()=>{});
    });
    const page=await context.newPage();page.on('pageerror',error=>errors.push(error.message));
    await page.goto(base+'/cash-collections');
    if(admin||permissions.length)await page.locator('#cc-count').filter({hasText:/khoản thu/}).waitFor();
    async function rights(next) {state.permissions=next;await page.evaluate(async()=>Auth.refreshPermissions());}
    async function open(id=1) {await page.locator(`[data-collection="${id}"]`).click();await page.locator('#cc-detail-title').filter({hasText:'#'+id}).waitFor();}
    async function prepare(amount='250000,50') {
      await page.locator('#cc-new').click();await page.locator('#cc-dealer option[value="11"]').waitFor({state:'attached'});
      await page.locator('#cc-dealer').selectOption('11');await page.locator('#cc-amount').fill(amount);
      await page.locator('#cc-receipt-file').setInputFiles({name:'bien-lai.png',mimeType:'image/png',buffer:png});
      await page.locator('#cc-receipt-preview').waitFor({state:'visible'});await page.locator('#cc-create-check').check();
    }
    return {page,context,state,rights,open,prepare};
  }
  const check=async(name,run)=>{await run();cases++;console.log('PASS '+name);};
  try {
    await check('CREATE-only user: owned data, multipart photo, exact amount and duplicate submit guard',async()=>{
      const s=await setup(['COLLECTION_CREATE'],8);assert.equal(await s.page.locator('#cc-employee-label').isVisible(),false);
      await s.prepare('99999999999999999,99');
      await s.page.evaluate(()=>{const form=document.getElementById('cc-create-form');form.dispatchEvent(new Event('submit',{cancelable:true}));form.dispatchEvent(new Event('submit',{cancelable:true}));});
      await s.page.locator('#cc-success').filter({hasText:'Đã ghi khoản thu #3'}).waitFor();
      const posts=s.state.calls.filter(c=>c.method==='POST');assert.equal(posts.length,1);
      assert.match(posts[0].raw.toString(),/"amount":"99999999999999999\.99"/);
      await s.page.locator('#cc-list').filter({hasText:'99.999.999.999.999.999,99'}).waitFor();
      assert.ok(s.state.calls.every(c=>c.headers.authorization==='Bearer '+token));
      assert.ok(!s.state.calls.some(c=>c.endpoint.startsWith('/dealers')||c.endpoint.startsWith('/admin')));
      await s.context.close();
    });
    await check('Accountant: authenticated receipt, received status and exact daily reconciliation',async()=>{
      const s=await setup(['COLLECTION_CONFIRM','COLLECTION_RECONCILE']);
      await s.page.locator('#cc-total-collected').filter({hasText:'200.000,75'}).waitFor();
      assert.equal(await s.page.locator('#cc-new').isVisible(),false);await s.open();
      await s.page.locator('#cc-receipt-load').click();await s.page.locator('#cc-receipt-view').waitFor({state:'visible'});
      assert.match(await s.page.locator('#cc-receipt-image').getAttribute('src'),/^blob:/);
      await s.page.locator('#cc-confirm-check').check();
      await s.page.evaluate(()=>{const form=document.getElementById('cc-confirm-form');form.dispatchEvent(new Event('submit',{cancelable:true}));form.dispatchEvent(new Event('submit',{cancelable:true}));});
      await s.page.locator('#cc-success').filter({hasText:'Đã xác nhận'}).waitFor();
      await s.page.locator('#cc-total-received').filter({hasText:'125.000,50'}).waitFor();
      assert.equal(s.state.calls.filter(c=>c.method==='POST').length,1);
      assert.match(await s.page.locator('#cc-detail-body').innerText(),/Đã nhận tiền/);
      assert.equal(await s.page.locator('#cc-confirm-form').isVisible(),false);
      await s.page.locator('#cc-detail-close').click();assert.equal(await s.page.locator('#cc-receipt-image').getAttribute('src'),null);
      await s.context.close();
    });
    await check('CONFIRM-only has no reconciliation call, self-confirm hidden even for ADMIN',async()=>{
      for(const admin of [false,true]){
        const s=await setup(['COLLECTION_CONFIRM'],8,admin);await s.open(1);
        assert.equal(await s.page.locator('#cc-confirm-form').isVisible(),false);assert.equal(await s.page.locator('#cc-self-note').isVisible(),true);
        if(!admin){assert.equal(await s.page.locator('#cc-reconciliation').isVisible(),false);assert.ok(!s.state.calls.some(c=>c.endpoint.endsWith('/reconciliation')));}
        await s.context.close();
      }
    });
    await check('RECONCILE-only is read-only; untrusted text is escaped',async()=>{
      const s=await setup(['COLLECTION_RECONCILE']);await s.open(2);
      assert.equal(await s.page.locator('#cc-new').isVisible(),false);assert.equal(await s.page.locator('#cc-confirm-form').isVisible(),false);
      assert.equal(await s.page.locator('#cc-detail-body img, #cc-detail-body script, #cc-list img').count(),0);
      assert.equal(await s.page.evaluate(()=>window.__cashXss),undefined);
      assert.equal(s.state.calls.filter(c=>c.method==='POST').length,0);await s.context.close();
    });
    await check('Photo validation rejects oversized, corrupt, wrong-format input; decimal and future-date validation',async()=>{
      const s=await setup(['COLLECTION_CREATE'],8);await s.prepare();
      await s.page.locator('#cc-receipt-file').setInputFiles({name:'fake.png',mimeType:'image/png',buffer:Buffer.from('<svg>bad</svg>')});
      await s.page.locator('#cc-create-error').filter({hasText:'JPEG hoặc PNG'}).waitFor();assert.equal(await s.page.locator('#cc-save').isDisabled(),true);
      await s.page.locator('#cc-receipt-file').setInputFiles({name:'large.png',mimeType:'image/png',buffer:Buffer.alloc(5*1024*1024+1)});
      await s.page.locator('#cc-create-error').filter({hasText:'5 MiB'}).waitFor();
      const huge=Buffer.from(png);huge.writeUInt32BE(20000,16);huge.writeUInt32BE(20000,20);
      await s.page.locator('#cc-receipt-file').setInputFiles({name:'too-many-pixels.png',mimeType:'image/png',buffer:huge});
      await s.page.locator('#cc-create-error').filter({hasText:'20 megapixel'}).waitFor();
      await s.page.locator('#cc-receipt-file').setInputFiles({name:'bien-lai.png',mimeType:'image/png',buffer:png});await s.page.locator('#cc-receipt-preview').waitFor({state:'visible'});
      await s.page.locator('#cc-amount').fill('1.001');await s.page.locator('#cc-save').click();await s.page.locator('#cc-create-error').filter({hasText:'Số tiền phải'}).waitFor();
      await s.page.locator('#cc-amount').fill('5');await s.page.locator('#cc-date').fill('2099-01-01');
      await s.page.evaluate(()=>{document.getElementById('cc-date').removeAttribute('max');document.getElementById('cc-create-form').dispatchEvent(new Event('submit',{cancelable:true}));});
      await s.page.locator('#cc-create-error').filter({hasText:'tương lai'}).waitFor();assert.equal(s.state.calls.filter(c=>c.method==='POST').length,0);await s.context.close();
    });
    await check('Employee/day filters, date validation and dealer pagination are server-backed',async()=>{
      const s=await setup();await s.page.locator('#cc-employee').fill('8');await s.page.locator('#cc-filter-save').click();
      await s.page.locator('#cc-count').filter({hasText:'1 khoản thu'}).waitFor();
      assert.ok(s.state.calls.some(c=>c.query.includes('employeeId=8')&&c.query.includes('from=')&&c.query.includes('to=')));
      await s.page.locator('#cc-from').fill('2024-01-01');await s.page.locator('#cc-to').fill('2025-01-02');await s.page.locator('#cc-filter-save').click();
      await s.page.locator('#cc-error').filter({hasText:'366 ngày'}).waitFor();
      s.state.dealerPages=2;await s.page.locator('#cc-new').click();await s.page.locator('#cc-dealer-page').filter({hasText:'1/2'}).waitFor();
      await s.page.locator('#cc-dealer-next').click();await s.page.locator('#cc-dealer-page').filter({hasText:'2/2'}).waitFor();
      assert.ok(s.state.calls.some(c=>c.endpoint.endsWith('/dealers')&&c.query.includes('page=1')));await s.context.close();
    });
    await check('Server list pagination and stale filter responses cannot overwrite current results',async()=>{
      const s=await setup(['COLLECTION_RECONCILE']);
      for(let i=3;i<=23;i++)s.state.rows.push({...s.state.rows[0],id:i});
      await s.page.locator('#cc-reload').click();await s.page.locator('#cc-page').filter({hasText:'1/2'}).waitFor();
      assert.equal(await s.page.locator('#cc-list tr').count(),20);
      await s.page.locator('#cc-next').click();await s.page.locator('#cc-page').filter({hasText:'2/2'}).waitFor();
      assert.equal(await s.page.locator('#cc-list tr').count(),3);assert.ok(s.state.calls.some(c=>c.endpoint==='/finance/cash-collections'&&c.query.includes('page=1')));
      s.state.listDelay=400;await s.page.locator('#cc-employee').fill('8');await s.page.locator('#cc-filter-save').click();await pause(60);
      s.state.listDelay=0;await s.page.locator('#cc-employee').fill('9');await s.page.locator('#cc-filter-save').click();
      await s.page.locator('#cc-count').filter({hasText:'1 khoản thu'}).waitFor();await pause(500);
      assert.equal(await s.page.locator('#cc-list tr').count(),1);assert.match(await s.page.locator('#cc-list').innerText(),/Mã NV: 9/);
      await s.context.close();
    });
    await check('409 confirmation conflict reloads data without retrying write',async()=>{
      const s=await setup();s.state.confirmStatus=409;await s.open();await s.page.locator('#cc-confirm-check').check();await s.page.locator('#cc-confirm').click();
      await s.page.locator('#cc-error').filter({hasText:'người khác xác nhận'}).waitFor();await s.page.locator('#cc-list').filter({hasText:'Đã nhận tiền'}).waitFor();
      assert.equal(s.state.calls.filter(c=>c.method==='POST').length,1);await s.context.close();
    });
    await check('Unknown write result blocks retry; read errors do not masquerade as empty data',async()=>{
      const s=await setup(['COLLECTION_CREATE'],8);s.state.saveStatus=500;await s.prepare();await s.page.locator('#cc-save').click();
      await s.page.locator('#cc-error').filter({hasText:'Chưa xác định'}).waitFor();assert.equal(await s.page.locator('#cc-save').isDisabled(),true);
      assert.equal(s.state.calls.filter(c=>c.method==='POST').length,1);s.state.listStatus=500;s.state.reportStatus=500;await s.page.locator('#cc-reload').click();
      await s.page.locator('#cc-list').filter({hasText:'Không tải được khoản thu'}).waitFor();
      assert.equal(await s.page.locator('#cc-total-collected').innerText(),'—');assert.equal(await s.page.locator('#cc-new').isDisabled(),true);
      await s.context.close();
    });
    await check('Revocation cancels stale detail/receipt and strips cached data; granting restores controls',async()=>{
      const s=await setup();await s.open();await s.page.locator('#cc-receipt-load').click();await s.page.locator('#cc-receipt-view').waitFor({state:'visible'});
      await s.page.locator('#cc-from').fill('');
      await s.rights(['COLLECTION_RECONCILE']);assert.equal(await s.page.locator('#cc-detail').isVisible(),false);assert.equal(await s.page.locator('#cc-new').isVisible(),false);assert.equal(await s.page.locator('#cc-receipt-image').getAttribute('src'),null);
      await s.page.locator('#cc-count').filter({hasText:'khoản thu'}).waitFor();s.state.detailDelay=400;
      await s.page.locator('[data-collection="1"]').click();await pause(60);await s.rights(['COLLECTION_CREATE']);await pause(500);
      assert.equal(await s.page.locator('#cc-detail').isVisible(),false);assert.equal(await s.page.locator('#cc-new').isVisible(),true);
      await s.rights([]);await s.page.waitForURL('**/error?status=403');assert.equal(await s.page.locator('a[href="/cash-collections"]').count(),0);
      await s.context.close();
    });
    await check('Shared layout fits desktop/tablet/mobile; receipt form remains usable',async()=>{
      const s=await setup();await s.prepare();
      for(const width of [1440,768,390,360]){
        await s.page.setViewportSize({width,height:1000});await s.page.evaluate(()=>window.scrollTo(0,0));await pause(80);
        const layout=await s.page.evaluate(()=>({width:document.documentElement.clientWidth,scroll:document.documentElement.scrollWidth,content:document.querySelector('main.content').getBoundingClientRect().toJSON(),parent:document.querySelector('.main-area').getBoundingClientRect().toJSON()}));
        assert.ok(layout.scroll<=layout.width+1,JSON.stringify(layout));
        assert.ok(Math.abs((layout.content.left-layout.parent.left)-(layout.parent.right-layout.content.right))<=2,JSON.stringify(layout));
        await s.page.screenshot({path:path.join(output,`cash-collections-${width}.png`),fullPage:true});
      }
      await s.context.close();
    });
    assert.deepEqual(unexpected,[]);assert.deepEqual(errors,[]);console.log(`${cases} S7-05 browser scenarios passed. Screenshots: ${output}`);
  } finally {await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
