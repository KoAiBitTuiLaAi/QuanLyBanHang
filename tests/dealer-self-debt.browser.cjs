// Isolated Chrome API fixtures; no requests reach the business database.
const assert = require('node:assert/strict'), fs = require('node:fs'), path = require('node:path');
const {spawn} = require('node:child_process');
const {chromium} = require('playwright');
const output = 'E:/DevTemp/UserTemp/s707-browser';
const user = {id: 7, username: 'dealer-s707', roles: ['ROLE_DEALER'], permissions: ['FINANCE_VIEW_DEBT', 'INVOICE_VIEW']};
const token = 'fixture.' + Buffer.from(JSON.stringify({exp: Date.now()/1000 + 3600})).toString('base64url') + '.fixture';
const summary = {currentDebt: 9007199254740992, debtLimit: 9007199254741992, remainingLimit: 1000, availableOrderLimit: 1000,
  exactAmounts: {currentDebt: '9007199254740993.25', debtLimit: '9007199254741993.25', remainingLimit: '1000.00', availableOrderLimit: '1000.00', overdueAmount: '0.25'},
  hasOverdueDebt: true, isBlocked: false, overdueAmount: 0.25, unpaidInvoiceCount: 1, overdueInvoiceCount: 1, maxDebtDays: 30};
const invoice = {id: 11, invoiceNumber: 'HD-S707-001', invoiceCode: 'INV-001', invoiceDate: '2026-10-20T09:00:00', dueDate: '2026-11-20',
  status: 'ADJUSTED', statusLabel: 'Đã điều chỉnh', paymentStatus: 'PARTIALLY_PAID', paymentStatusLabel: 'Thanh toán một phần',
  totalAmount: 100.15, adjustedTotalAmount: 100.15, effectiveAmount: 80.10, paidAmount: 30.02, remainingAmount: 50.08,
  exactAmounts: {totalAmount: '100.15', adjustedTotalAmount: '100.15', effectiveAmount: '80.10', paidAmount: '30.02', remainingAmount: '50.08'},
  items: [{productName: '<img src=x onerror=alert(1)>', productCode: 'SP-1', unit: 'thùng', invoicedQuantity: 3.5, unitPrice: 10.25, totalAmount: 35.88,
    exactAmounts: {unitPrice: '10.25', totalAmount: '35.88', invoicedQuantity: '3.5'}}],
  adjustments: [{id: 9, adjustmentCode: 'DCTH-9', adjustmentTypeLabel: 'Giảm', adjustmentReason: 'Trả hàng lỗi', adjustmentAmount: -20.05,
    adjustmentDate: '2026-10-21T09:00:00', exactAmounts: {adjustmentAmount: '-20.05'}}]};
let failList = false, holdDetail = false, releaseDetail, holdSlow = false, releaseSlow;
const calls = [], errors = [];
async function main() {
  fs.mkdirSync(output, {recursive: true});
  const server = spawn('C:/Users/haido/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe',
    ['-B', 'serve_frontend.py', '--port', '0'], {cwd: path.resolve(__dirname, '..'), windowsHide: true});
  let browser;
  try {
    const base = await new Promise((resolve, reject) => {
      const timeout = setTimeout(() => reject(new Error('Server timeout')), 10000);
      server.stdout.on('data', data => {const m = String(data).match(/localhost:(\d+)/); if (m) {clearTimeout(timeout); resolve('http://127.0.0.1:' + m[1]);}});
      server.on('error', reject);
    });
    browser = await chromium.launch({channel: 'chrome', headless: true});
    const context = await browser.newContext({viewport: {width: 1440, height: 1000}, reducedMotion: 'reduce'});
    await context.addInitScript(({user, token}) => {if (!sessionStorage.getItem('user')) sessionStorage.setItem('user', JSON.stringify(user)); sessionStorage.setItem('accessToken', token);}, {user, token});
    await context.route('http://localhost:8080/api/**', async route => {
      const req = route.request(), url = new URL(req.url()), ep = url.pathname.replace('/api', '');
      const headers = {'Access-Control-Allow-Origin': base, 'Access-Control-Allow-Headers': 'Authorization,Content-Type', 'Access-Control-Allow-Methods': 'GET,OPTIONS'};
      if (req.method() === 'OPTIONS') return route.fulfill({status: 204, headers});
      calls.push({ep, method: req.method(), query: Object.fromEntries(url.searchParams)});
      let data = [], status = 200, message = '';
      if (ep === '/auth/permissions') data = {roles: user.roles, permissions: user.permissions};
      else if (ep === '/users/profile') data = user;
      else if (ep === '/portal/dealers') data = [{id: 1, code: 'DL-01', name: 'Đại lý Nguyễn Ánh'}];
      else if (ep === '/portal/debt/summary') {
        if (user.permissions.includes('FINANCE_VIEW_DEBT') || user.roles.includes('ROLE_ADMIN')) data = summary;
        else {status = 403; message = 'Không có quyền xem công nợ';}
      } else if (ep.startsWith('/portal/invoices')) {
        if (!user.permissions.includes('INVOICE_VIEW') && !user.roles.includes('ROLE_ADMIN')) {status = 403; message = 'Không có quyền hóa đơn';}
        else if (ep.endsWith('/pdf')) return route.fulfill({headers, contentType: 'application/pdf', body: '%PDF-1.4 fixture'});
        else if (ep.endsWith('/print-preview')) return route.fulfill({headers, contentType: 'text/html', body: '<html><body>Hóa đơn HD-S707-001<script>parent.injected=true</script></body></html>'});
        else if (ep === '/portal/invoices') {
          const row = url.searchParams.get('keyword') === 'slow' ? {...invoice, invoiceNumber: 'STALE-INVOICE'} : invoice;
          data = {content: [row], totalElements: 1, totalPages: 1};
          if (holdSlow && url.searchParams.get('keyword') === 'slow') await new Promise(resolve => releaseSlow = resolve);
          if (failList) {status = 500; message = 'Không tải được hóa đơn';}
        } else {data = invoice; if (holdDetail) await new Promise(resolve => releaseDetail = resolve);}
      }
      return route.fulfill({status, headers, contentType: 'application/json', body: JSON.stringify({success: status === 200, data, message})});
    });
    const page = await context.newPage(); page.on('pageerror', e => errors.push(e.message));
    const navigate = async (query = '') => {await page.goto(base + '/dealer-invoices' + query); await page.locator('#di-invoice-rows').getByText('HD-S707-001', {exact: true}).waitFor();};
    await navigate();
    await page.locator('#val-current-debt').getByText('9.007.199.254.740.993,25 ₫', {exact: true}).waitFor();
    assert.equal(await page.locator('#val-available-order-limit').textContent(), '1.000 ₫');
    assert.equal(await page.locator('#val-order-status-badge').textContent(), 'Có nợ quá hạn');
    assert.equal(await page.locator('#di-t-remain').textContent(), '50,08 ₫');
    await page.locator('.btn-view-invoice').click();
    await page.locator('#di-detail-body').getByText('3,5', {exact: true}).waitFor();
    assert.equal(await page.locator('#di-detail-body img').count(), 0);
    assert.ok((await page.locator('#di-detail-body').textContent()).includes('35,88 ₫'));
    assert.ok((await page.locator('#di-detail-body').textContent()).includes('Trả hàng lỗi'));
    const downloaded = page.waitForEvent('download'); await page.locator('.btn-download-adj').click();
    assert.match((await downloaded).suggestedFilename(), /chung-tu-dieu-chinh-DCTH-9/);
    await page.locator('#btn-close-detail').click();
    await page.locator('.btn-print-invoice').click();
    await page.waitForFunction(() => document.querySelector('#di-print-frame').srcdoc.includes('HD-S707-001'));
    assert.equal(await page.evaluate(() => window.injected), undefined);
    assert.equal(await page.locator('#di-print-frame').getAttribute('sandbox'), 'allow-same-origin allow-modals');
    await page.locator('#btn-close-print').click();
    const pdf = page.waitForEvent('download'); await page.locator('.btn-download-invoice').click();
    assert.match((await pdf).suggestedFilename(), /hoa-don-HD-S707-001/);
    failList = true; await page.locator('#btn-reload').click();
    await page.locator('#di-invoice-rows').getByText('Không thể tải dữ liệu', {exact: true}).waitFor();
    assert.equal(await page.locator('#btn-export').isDisabled(), true);
    assert.equal(await page.locator('#di-totals').isVisible(), false);
    failList = false; await page.locator('#btn-reload').click(); await page.locator('.btn-view-invoice').waitFor();
    holdSlow = true; await page.locator('#di-keyword').fill('slow'); await page.locator('#di-filter-form').evaluate(f => f.requestSubmit());
    while (!releaseSlow) await page.waitForTimeout(20);
    await page.locator('#di-keyword').fill('fast'); await page.locator('#di-filter-form').evaluate(f => f.requestSubmit());
    await page.locator('#di-invoice-rows').getByText('HD-S707-001', {exact: true}).waitFor(); releaseSlow();
    await page.waitForTimeout(100); assert.equal(await page.getByText('STALE-INVOICE', {exact: true}).count(), 0);
    holdSlow = false;
    for (const width of [1440, 1024, 768, 390, 320]) {
      await page.setViewportSize({width, height: 1000});
      assert.ok(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), 'outer overflow ' + width);
      await page.screenshot({path: path.join(output, 'dealer-debt-' + width + '.png'), fullPage: true});
    }
    holdDetail = true; await page.locator('.btn-view-invoice').click();
    while (!releaseDetail) await page.waitForTimeout(20);
    user.permissions = ['FINANCE_VIEW_DEBT']; await page.evaluate(() => Auth.refreshPermissions()); releaseDetail();
    await page.waitForFunction(() => document.querySelector('#di-invoices-panel').hidden);
    assert.equal(await page.locator('#di-detail-body').textContent(), '');
    assert.equal(await page.locator('#di-detail-modal').isVisible(), false);
    assert.equal(await page.locator('#di-print-frame').getAttribute('srcdoc'), '');
    assert.equal(await page.locator('a[href="/dealer-invoices"]').count(), 1);
    holdDetail = false; user.permissions = ['INVOICE_VIEW']; await page.evaluate(() => Auth.refreshPermissions());
    await page.locator('.btn-view-invoice').waitFor(); assert.equal(await page.locator('.di-summary-cards').isVisible(), false);
    assert.equal(await page.locator('#val-current-debt').textContent(), '—');
    user.roles = ['ROLE_ADMIN']; user.permissions = [];
    await page.evaluate(user => sessionStorage.setItem('user', JSON.stringify(user)), user);
    await navigate('?dealerId=1');
    assert.ok(calls.filter(c => c.ep === '/portal/invoices' && c.query.dealerId === '1').length > 0);
    assert.deepEqual(errors, []);
    assert.ok(calls.every(c => c.method === 'GET'));
    console.log('PASS S7-07 Chrome: exact debt/cents, returned effective totals, quantity/adjustment fields, PDF/HTML sandbox, list errors, stale filters, five widths, late detail after revocation, independent permissions, admin scope. Screenshots: ' + output);
  } finally {if (browser) await browser.close(); server.kill();}
}
main().catch(e => {console.error(e); process.exitCode = 1;});
