// Real Chrome with intercepted API responses; no requests reach the business database or SMTP.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require('playwright');
const base = process.env.NAV_TEST_URL || 'http://127.0.0.1:5531';
const output = path.join(require('node:os').tmpdir(), 'qlbh-s708');
const token = 'fixture.' + Buffer.from(JSON.stringify({exp: Math.floor(Date.now()/1000)+3600})).toString('base64url') + '.fixture';

(async () => {
  fs.mkdirSync(output, {recursive: true});
  const browser = await chromium.launch({channel:'chrome', headless:true});
  const errors = [];
  try {
    async function setup(admin = false) {
      const roles = [admin ? 'ROLE_ADMIN' : 'ROLE_DEALER'];
      const user = {id:7, username:'s708-fixture', fullName:'Đại lý kiểm thử', roles, permissions:[]};
      const state = {rights:[], read:false, holdList:false, held:false, release:null, writes:0};
      const context = await browser.newContext({viewport:{width:1440,height:900}, reducedMotion:'reduce'});
      await context.addInitScript(({user, token}) => {
        sessionStorage.setItem('user', JSON.stringify(user));
        sessionStorage.setItem('accessToken', token);
      }, {user, token});
      await context.route('http://localhost:8080/api/**', async route => {
        const request = route.request(), url = new URL(request.url());
        const endpoint = url.pathname.slice(4), method = request.method();
        const headers = {'Access-Control-Allow-Origin':base, 'Access-Control-Allow-Headers':'Authorization,Content-Type',
          'Access-Control-Allow-Methods':'GET,PUT,OPTIONS'};
        if (method === 'OPTIONS') return route.fulfill({status:204, headers});
        let data = [], status = 200;
        if (endpoint === '/auth/permissions') data = {roles, permissions:state.rights};
        else if (endpoint === '/users/profile') data = user;
        else if (endpoint === '/portal/dealers') data = [{id:42, code:'DL42', name:'Đại lý kiểm thử'}];
        else if (endpoint.startsWith('/portal/notifications')) {
          if (!admin && !state.rights.includes('NOTIFICATION_VIEW')) status = 403;
          else {
            if (admin) assert.equal(url.searchParams.get('dealerId'), '42');
            if (endpoint.endsWith('/unread-count')) data = {unreadCount:state.read ? 0 : 1};
            else if (method === 'PUT') {state.read = true; state.writes++; data = {isRead:true, updatedCount:1};}
            else {
              const title = state.holdList ? 'Dữ liệu cũ phải bị loại bỏ' : 'Nhắc hạn hóa đơn thật từ API';
              const row = {id:1, title, message:'<img src=x onerror=alert(1)>', type:'PAYMENT_DUE_BEFORE_3_DAYS',
                typeLabel:'Nhắc hạn trước 3 ngày', isRead:state.read, createdAt:'2026-10-26T09:00:00'};
              data = {content:state.read && url.searchParams.get('unreadOnly') ? [] : [row], totalElements:state.read && url.searchParams.get('unreadOnly') ? 0 : 1, totalPages:1};
              if (state.holdList) {state.held = true; await new Promise(resolve => {state.release = resolve;});}
            }
          }
        }
        await route.fulfill({status, headers, contentType:'application/json', body:JSON.stringify({success:status===200, data, message:'Quyền truy cập đã thay đổi'})}).catch(() => {});
      });
      const page = await context.newPage();
      page.on('pageerror', error => errors.push(error.message));
      return {context, page, state};
    }

    const {context, page, state} = await setup();
    await page.goto(base + '/dealer-notifications');
    await page.waitForURL('**/error?status=403');
    await page.goto(base + '/dashboard');
    assert.equal(await page.locator('.sidebar a[href="/dealer-notifications"]').count(), 0);
    state.rights = ['NOTIFICATION_VIEW'];
    await page.evaluate(() => Auth.refreshPermissions());
    await page.locator('.sidebar a[href="/dealer-notifications"]').click();
    await page.getByRole('heading', {name:'Nhắc hạn hóa đơn thật từ API'}).waitFor();
    assert.equal(await page.locator('#dn-list img').count(), 0);

    // The older reload must not overwrite the more recent unread filter request.
    state.holdList = true;
    await page.locator('#btn-reload').click();
    while (!state.held) await page.waitForTimeout(20);
    state.holdList = false;
    await page.locator('#tab-unread').click();
    await page.getByRole('heading', {name:'Nhắc hạn hóa đơn thật từ API'}).waitFor();
    state.release();
    await page.waitForTimeout(150);
    assert.equal((await page.locator('#dn-list').textContent()).includes('Dữ liệu cũ phải bị loại bỏ'), false);

    // Enter the screen through its real sidebar item and exercise its controls.
    await page.goto(base + '/dashboard');
    await page.locator('.sidebar a[href="/dealer-notifications"]').click();
    await page.getByRole('heading', {name:'Nhắc hạn hóa đơn thật từ API'}).waitFor();
    await page.locator('#tab-unread').click();
    await page.locator('.btn-mark-read').click();
    await page.getByRole('heading', {name:'Chưa có thông báo nào'}).waitFor();
    assert.equal(state.writes, 1);
    state.rights = [];
    await page.evaluate(() => Auth.refreshPermissions());
    await page.waitForURL('**/error?status=403');
    state.rights = ['NOTIFICATION_VIEW'];
    await page.goto(base + '/dashboard');
    await page.locator('.sidebar a[href="/dealer-notifications"]').click();
    await page.locator('#tab-all').click();
    await page.getByRole('heading', {name:'Nhắc hạn hóa đơn thật từ API'}).waitFor();
    for (const width of [1440, 768, 390, 320]) {
      await page.setViewportSize({width, height:900});
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), true, `overflow at ${width}px`);
      await page.screenshot({path:path.join(output, `notifications-${width}.png`), fullPage:true});
    }
    await context.close();

    const admin = await setup(true);
    await admin.page.goto(base + '/dashboard');
    await admin.page.locator('.sidebar a[href="/dealer-notifications"]').click();
    await admin.page.locator('#dp-dealer-select option[value="42"]').waitFor({state:'attached'});
    await admin.page.locator('#dp-dealer-select').selectOption('42');
    await admin.page.getByRole('heading', {name:'Nhắc hạn hóa đơn thật từ API'}).waitFor();
    await admin.context.close();
    assert.deepEqual(errors, []);
    console.log('PASS S7-08 Chrome: sidebar entry, initial grant, revoke/regrant, stale response, read/unread, admin scope, XSS and four widths. Screenshots: ' + output);
  } finally {await browser.close();}
})().catch(error => {console.error(error); process.exitCode = 1;});
