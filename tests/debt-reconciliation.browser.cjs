// Real Chrome with isolated HTTP fixtures. No real API or business database writes.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {spawn} = require('node:child_process');
const {chromium} = require('playwright');
const python = process.env.TEST_PYTHON || 'C:/Users/haido/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe';
const output = process.env.TEST_OUTPUT || 'E:/DevTemp/UserTemp/s706-browser';
const user = {id: 10, username: 'accountant-test', roles: ['ROLE_ACCOUNTANT'], permissions: ['FINANCE_VIEW_DEBT', 'FINANCE_MANAGE']};
const token = 'fixture.' + Buffer.from(JSON.stringify({exp: Date.now()/1000 + 3600})).toString('base64url') + '.fixture';
const makeRecord = id => ({id, version: 0, code: 'DC-FIXTURE-' + id, status: 'PENDING', createdBy: 'accountant-test',
  createdAt: '2026-10-25T09:00:00', claimedBalance: null, difference: null, responseReason: null,
  statement: {dealerId: 1, dealerCode: 'DL-01', dealerName: 'Đại lý Nguyễn Ánh', dealerAddress: 'Hà Nội', dealerPhone: '0912345678',
    from: '2026-10-01', to: '2026-10-25', openingBalance: '9007199254740993.25', totalIncrease: '10.00', totalDecrease: '5.00',
    closingBalance: '9007199254740998.25', entries: [{occurredAt: '2026-10-20T10:00:00', sourceType: 'PAYMENT', sourceId: 1,
      documentCode: 'PT-01', description: '<img src=x onerror=alert(1)>', increase: '10.00', decrease: '5.00', balance: '9007199254740998.25'}]}});
const records = [makeRecord(1)];
let holdDetail = false, releaseDetail, failCreate = false, createCalls = 0, responseCalls = 0;
const calls = [], errors = [];
function summary(d) {return {...d, ...d.statement, statement: undefined, entries: undefined};}
async function main() {
  fs.mkdirSync(output, {recursive: true});
  const server = spawn(python, ['-B', 'serve_frontend.py', '--port', '0'], {cwd: path.resolve(__dirname, '..'), windowsHide: true});
  let browser;
  try {
    const base = await new Promise((resolve, reject) => {
      const timeout = setTimeout(() => reject(new Error('Fixture server timeout')), 10000);
      server.stdout.on('data', bytes => {const m = String(bytes).match(/localhost:(\d+)/); if (m) {clearTimeout(timeout); resolve('http://127.0.0.1:' + m[1]);}});
      server.on('error', reject);
    });
    browser = await chromium.launch({channel: 'chrome', headless: true});
    const context = await browser.newContext({viewport: {width: 1440, height: 1000}, reducedMotion: 'reduce'});
    await context.addInitScript(({user, token}) => {sessionStorage.setItem('user', JSON.stringify(user)); sessionStorage.setItem('accessToken', token);}, {user, token});
    await context.route('http://localhost:8080/api/**', async route => {
      const req = route.request(), url = new URL(req.url()), ep = url.pathname.replace('/api', '');
      const headers = {'Access-Control-Allow-Origin': base, 'Access-Control-Allow-Headers': 'Authorization,Content-Type', 'Access-Control-Allow-Methods': 'GET,POST,OPTIONS'};
      if (req.method() === 'OPTIONS') return route.fulfill({status: 204, headers});
      calls.push({ep, method: req.method()});
      let data = [], status = 200, message = '';
      if (ep === '/auth/permissions') data = {roles: user.roles, permissions: user.permissions};
      else if (ep === '/users/profile') data = user;
      else if (!user.permissions.includes('FINANCE_VIEW_DEBT') && !user.roles.includes('ROLE_ADMIN')) {status = 403; message = 'Quyền đã bị thu hồi';}
      else if (ep.endsWith('/debt-reconciliations/options')) data = {content: [{id: 1, code: 'DL-01', name: 'Đại lý Nguyễn Ánh'}], number: 0, totalPages: 1};
      else if (ep.endsWith('/response')) {
        responseCalls++;
        const body = req.postDataJSON(), d = records.find(d => String(d.id) === ep.split('/').at(-2));
        if (body.version !== d.version || d.status !== 'PENDING') {status = 409; message = 'Đã phản hồi';}
        else {
          d.status = body.status; d.version++; d.respondedBy = 'dealer-test'; d.respondedAt = '2026-10-25T10:00:00';
          d.claimedBalance = body.claimedBalance || d.statement.closingBalance;
          d.responseReason = body.reason || null;
          d.difference = body.status === 'CONFIRMED' ? '0.00' : '0.01'; data = d;
        }
      } else if (ep.endsWith('/pdf')) return route.fulfill({status: 200, headers, contentType: 'application/pdf', body: '%PDF-1.4 fixture'});
      else if (/debt-reconciliations\/\d+$/.test(ep)) {
        data = records.find(d => String(d.id) === ep.split('/').at(-1));
        if (holdDetail) await new Promise(resolve => releaseDetail = resolve);
      } else if (ep.endsWith('/debt-reconciliations') && req.method() === 'POST') {
        createCalls++;
        const body = req.postDataJSON(); assert.ok(body.requestKey); assert.equal(body.dealerId, '1');
        data = makeRecord(records.length + 1); records.push(data);
        if (failCreate) return route.abort('failed');
      } else if (ep.endsWith('/debt-reconciliations')) data = {content: records.map(summary), number: Number(url.searchParams.get('page') || 0), totalPages: 1, totalElements: records.length};
      return route.fulfill({status, headers, contentType: 'application/json', body: JSON.stringify({success: status === 200, data, message})});
    });
    const page = await context.newPage(); page.on('pageerror', error => errors.push(error.message)); page.on('dialog', dialog => dialog.accept());
    const navigate = async () => {await page.goto(base + '/finance/debt-reconciliation'); await page.getByRole('button', {name: 'Xem', exact: true}).first().waitFor();};
    await navigate();
    await page.getByRole('button', {name: 'Xem', exact: true}).first().click();
    await page.locator('#kpi-closing').getByText('9.007.199.254.740.998,25 ₫', {exact: true}).waitFor();
    assert.equal(await page.locator('#reconcile-tbody img').count(), 0);
    assert.equal(await page.locator('#dealer-actions-wrap').isVisible(), false);
    const downloadPromise = page.waitForEvent('download'); await page.locator('#btn-export-pdf').click();
    assert.match((await downloadPromise).suggestedFilename(), /Bien-ban-DC-FIXTURE-1\.pdf/);
    await page.locator('#filter-dealer').selectOption('1');
    await page.locator('#btn-create-reconcile').click();
    await page.locator('#doc-period-text').getByText(/DC-FIXTURE-2/).waitFor(); assert.equal(createCalls, 1);
    failCreate = true; await page.locator('#btn-create-reconcile').click();
    await page.locator('#reconcile-error').getByText(/Chưa xác định/).waitFor();
    assert.equal(await page.locator('#btn-create-reconcile').isDisabled(), true); assert.equal(createCalls, 2);
    failCreate = false; await page.locator('#btn-list-reconcile').click();
    await page.getByRole('button', {name: 'Xem', exact: true}).nth(2).waitFor(); assert.equal(createCalls, 2);
    user.permissions = ['FINANCE_VIEW_DEBT'];
    await page.evaluate(() => Auth.refreshPermissions());
    await page.getByRole('button', {name: 'Xem', exact: true}).first().waitFor();
    assert.equal(await page.locator('#btn-create-reconcile').isVisible(), false);
    user.permissions = ['FINANCE_VIEW_DEBT', 'FINANCE_MANAGE'];
    await page.evaluate(() => Auth.refreshPermissions()); await page.locator('#btn-create-reconcile').waitFor();
    user.roles = ['ROLE_DEALER']; user.permissions = ['FINANCE_VIEW_DEBT']; await navigate();
    await page.getByRole('button', {name: 'Xem', exact: true}).first().click();
    await page.locator('#dealer-actions-wrap').waitFor(); assert.equal(await page.locator('#btn-create-reconcile').isVisible(), false);
    await page.locator('#btn-dealer-report-diff').click();
    await page.locator('#modal-dealer-amount').fill('9007199254740998.24'); await page.locator('#modal-diff-reason').fill('Thiếu phiếu thu PT-02');
    assert.equal(await page.locator('#modal-diff-amount').inputValue(), '0,01 ₫');
    await page.locator('#btn-submit-discrepancy').click();
    await page.locator('#reconcile-status-badge').getByText('Phản hồi lệch số liệu', {exact: true}).waitFor(); assert.equal(responseCalls, 1);
    await navigate(); await page.getByRole('button', {name: 'Xem', exact: true}).first().click();
    await page.locator('#discrepancy-alert-content').getByText(/Thiếu phiếu thu PT-02/).waitFor();
    assert.equal(await page.locator('#dealer-actions-wrap').isVisible(), false);
    await page.getByRole('button', {name: 'Xem', exact: true}).nth(1).click();
    await page.locator('#btn-dealer-confirm-match').click();
    await page.locator('#reconcile-status-badge').getByText('Đã xác nhận khớp đúng', {exact: true}).waitFor(); assert.equal(responseCalls, 2);
    for (const width of [1440, 1024, 768, 390, 320]) {
      await page.setViewportSize({width, height: 1000});
      assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), 'outer overflow ' + width);
      await page.screenshot({path: path.join(output, 'reconciliation-' + width + '.png'), fullPage: true});
    }
    await page.setViewportSize({width: 1440, height: 1000});
    holdDetail = true; await page.getByRole('button', {name: 'Xem', exact: true}).first().click();
    await page.waitForFunction(() => document.querySelector('#reconcile-loading-state').hidden === false);
    while (!releaseDetail) await page.waitForTimeout(20);
    user.permissions = []; await page.evaluate(() => Auth.refreshPermissions()); releaseDetail();
    await page.waitForURL(/error\?status=403/);
    assert.equal(await page.locator('#reconcile-result-panel').count(), 0);
    assert.equal(await page.locator('#reconcile-history-rows').count(), 0);
    assert.equal(await page.locator('#modal-discrepancy').count(), 0);
    user.permissions = ['FINANCE_VIEW_DEBT']; holdDetail = false;
    await navigate();
    assert.deepEqual(errors, []);
    assert.ok(calls.some(c => c.ep.startsWith('/portal/debt-reconciliations')));
    console.log('PASS S7-06 Chrome: saved list, precision, escaped text, PDF, create, uncertain write recovery, dealer dispute/confirm persistence, five widths, late response after revoke and regrant. Screenshots: ' + output);
  } finally {if (browser) await browser.close(); server.kill();}
}
main().catch(error => {console.error(error); process.exitCode = 1;});
