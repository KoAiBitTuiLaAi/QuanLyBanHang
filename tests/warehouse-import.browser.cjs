// Exercise real page/scripts in Chrome with controlled API fixtures, not the user's DB.
const assert = require('node:assert/strict');
const path = require('node:path');
const fs = require('node:fs');
const { chromium } = require('playwright');
const base = process.env.NAV_TEST_URL || 'http://127.0.0.1:5538';
const output = path.join(require('node:os').tmpdir(), 'qlbh-s504');
const token = 'test.' + Buffer.from(JSON.stringify({ exp: Math.floor(Date.now() / 1000) + 3600 })).toString('base64url') + '.test';
const warehouses = [{ id: 1, code: 'WH1', name: 'Kho Hà Nội' }];
const supplier = { id: 5, code: 'NCC-5', name: 'Nhà cung cấp kiểm thử' };
const copy = value => JSON.parse(JSON.stringify(value));
(async () => {
  fs.mkdirSync(output, { recursive: true });
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  const errors = [];
  let current;
  async function setup(rights, seed = []) {
    const user = { id: 91, username: 'import-test', fullName: 'Nhân viên kiểm thử', roles: ['ROLE_VIEWER'], permissions: rights, warehouses };
    const state = { rights, rows: copy(seed), calls: [], failConfirm: false };
    const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, reducedMotion: 'reduce' });
    await context.addInitScript(({ user, token }) => { sessionStorage.setItem('user', JSON.stringify(user)); sessionStorage.setItem('accessToken', token); }, { user, token });
    await context.route('http://localhost:8080/api/**', async route => {
      const req = route.request(), url = new URL(req.url()), endpoint = url.pathname.slice(4), method = req.method();
      const headers = { 'Access-Control-Allow-Origin': base, 'Access-Control-Allow-Headers': 'Authorization,Content-Type', 'Access-Control-Allow-Methods': 'GET,POST,PUT,DELETE,OPTIONS' };
      if (method === 'OPTIONS') return route.fulfill({ status: 204, headers });
      const body = req.postData() ? req.postDataJSON() : null;
      state.calls.push({ endpoint, method, body });
      let data, status = 200, message = 'Thành công';
      const has = permission => state.rights.includes(permission);
      if (endpoint === '/auth/permissions') data = { roles: user.roles, permissions: state.rights, warehouses };
      else if (!state.rights.some(p => ['WAREHOUSE_IMPORT', 'WAREHOUSE_APPROVE', 'WAREHOUSE_ADJUST'].includes(p))) { status = 403; message = 'Quyền đã bị thu hồi'; }
      else if (endpoint === '/warehouse/import/options') data = { warehouses, suppliers: [supplier] };
      else if (endpoint === '/warehouse/imports') data = copy(state.rows);
      else if (endpoint === '/products/lookup') data = [{ id: 101, code: 'SKU-101', name: 'Nước <img src=x onerror="window.xss=true">', unit: 'lon', active: true, manageLot: true }];
      else if (endpoint === '/products/101/unit-conversions') data = [{ unitName: 'lon', conversionFactor: 1, isBaseUnit: true }, { id: 1, unitName: 'thùng', conversionFactor: 24, isBaseUnit: false }];
      else if (endpoint === '/warehouse/import/draft' || /^\/warehouse\/vouchers\/\d+$/.test(endpoint)) {
        if (!has('WAREHOUSE_IMPORT')) { status = 403; message = 'Thiếu quyền nhập kho'; }
        else if (method === 'DELETE') { state.rows = state.rows.filter(row => row.id !== Number(endpoint.split('/').at(-1))); data = null; }
        else {
          await new Promise(resolve => setTimeout(resolve, 80));
          const id = method === 'PUT' ? Number(endpoint.split('/').at(-1)) : state.rows.length + 1;
          data = { ...body, id, version: method === 'PUT' ? Number(body.version) + 1 : 0, voucherCode: body.voucherCode || 'PNK-SERVER-' + id,
            type: 'IMPORT', status: 'DRAFT', warehouseName: warehouses[0].name, supplierName: supplier.name, createdBy: user.username,
            items: body.items.map(item => ({ ...item, productCode: 'SKU-101', productName: 'Nước <img src=x>', conversionFactor: item.unit === 'thùng' ? 24 : 1,
              baseUnit: 'lon', baseQuantity: item.quantity * (item.unit === 'thùng' ? 24 : 1) })) };
          state.rows = [data, ...state.rows.filter(row => row.id !== id)]; data = copy(data);
        }
      } else if (/^\/warehouse\/vouchers\/\d+\/confirm$/.test(endpoint)) {
        await new Promise(resolve => setTimeout(resolve, 80));
        const row = state.rows.find(row => row.id === Number(endpoint.split('/').at(-2)));
        if (!has('WAREHOUSE_APPROVE')) { status = 403; message = 'Thiếu quyền xác nhận'; }
        else if (state.failConfirm) { status = 400; message = 'Lô hàng cần được kiểm tra lại'; }
        else if (row.status !== 'DRAFT') { status = 400; message = 'Phiếu đã xác nhận'; }
        else { row.status = 'CONFIRMED'; data = copy(row); }
      } else if (/^\/warehouse\/vouchers\/\d+\/adjust$/.test(endpoint)) {
        if (!has('WAREHOUSE_ADJUST')) { status = 403; message = 'Thiếu quyền điều chỉnh'; }
        else {
          const row = state.rows.find(row => row.id === Number(endpoint.split('/').at(-2)));
          row.status = 'ADJUSTED'; data = { ...copy(row), id: 99, voucherCode: 'PDC-SERVER-99', type: 'ADJUSTMENT', referenceVoucherCode: row.voucherCode, adjustmentReason: body.adjustmentReason };
          state.rows.unshift(data);
        }
      } else { status = 404; message = 'Unexpected API ' + endpoint; }
      await route.fulfill({ status, headers, contentType: 'application/json', body: JSON.stringify({ success: status === 200, data, message }) }).catch(() => {});
    });
    const page = await context.newPage(); page.on('pageerror', error => errors.push(error.message));
    page.on('dialog', dialog => dialog.accept());
    current = { context, page, state }; return current;
  }
  const writes = s => s.state.calls.filter(c => c.method !== 'GET');
  async function fill(s) {
    await s.page.locator('#btn-save-draft-receipt:not([disabled])').waitFor();
    await s.page.locator('#receipt-supplier-select').selectOption('5');
    await s.page.locator('#receipt-date').fill('2026-10-21');
    await s.page.locator('#receipt-doc-number').fill('HD-504');
    await s.page.locator('.line-qty-input').fill('2.5');
    await s.page.locator('.line-batch-input').fill('LOT-A');
    await s.page.locator('.line-expiry-input').fill('2027-10-21');
    await s.page.locator('.line-manufacturing-input').fill('2026-10-21');
  }
  try {
    const s = await setup(['WAREHOUSE_IMPORT', 'WAREHOUSE_APPROVE', 'WAREHOUSE_ADJUST']);
    await s.page.goto(base + '/warehouse/units?tab=receipts'); await fill(s);
    assert.equal(await s.page.locator('#line-items-body img').count(), 0);
    assert.equal(await s.page.locator('#summary-base-qty-total').innerText(), '60 (Tổng lon/cơ sở)');
    await s.page.locator('.line-batch-input').fill(''); await s.page.locator('#btn-save-draft-receipt').click();
    assert.equal(writes(s).length, 0, 'Missing lot must block saving');
    await s.page.locator('.line-batch-input').fill('LOT-A');
    await s.page.evaluate(() => { document.getElementById('btn-save-draft-receipt').click(); document.getElementById('btn-save-draft-receipt').click(); });
    await s.page.locator('[data-edit-draft="PNK-SERVER-1"]').waitFor();
    assert.equal(writes(s).length, 1); assert.equal(writes(s)[0].endpoint, '/warehouse/import/draft');
    assert.equal(writes(s)[0].body.documentNumber, 'HD-504'); assert.equal(writes(s)[0].body.supplierId, 5);
    assert.equal(writes(s)[0].body.items[0].lotNumber, 'LOT-A'); assert.equal(writes(s)[0].body.items[0].expiryDate, '2027-10-21');
    assert.equal(writes(s)[0].body.items[0].manufacturingDate, '2026-10-21');
    await s.page.reload(); await s.page.locator('[data-edit-draft="PNK-SERVER-1"]').click();
    assert.equal(await s.page.locator('#receipt-code').inputValue(), 'PNK-SERVER-1');
    assert.equal(await s.page.locator('#receipt-doc-number').inputValue(), 'HD-504');
    assert.equal(await s.page.locator('.line-batch-input').inputValue(), 'LOT-A');
    assert.equal(await s.page.locator('.line-manufacturing-input').inputValue(), '2026-10-21');
    await s.page.locator('.line-qty-input').fill('3.5'); await s.page.locator('#btn-save-draft-receipt').click();
    await s.page.locator('[data-view-receipt="PNK-SERVER-1"]').click();
    assert.equal(writes(s).at(-1).method, 'PUT'); assert.equal(writes(s).at(-1).endpoint, '/warehouse/vouchers/1');
    s.state.failConfirm = true;
    await s.page.locator('[data-confirm-import]').click(); await s.page.locator('.toast__message').filter({ hasText: 'Lô hàng cần' }).waitFor();
    assert.equal(s.state.rows[0].status, 'DRAFT'); s.state.failConfirm = false;
    await s.page.locator('[data-confirm-import]').click(); await s.page.locator('#import-adjustment-form').waitFor();
    assert.equal(await s.page.locator('[data-confirm-import]').count(), 0); assert.equal(await s.page.locator('[data-edit-draft]').count(), 0);
    await s.page.locator('.toast').last().waitFor({ state: 'detached' });
    await s.page.screenshot({ path: path.join(output, 'confirmed-desktop.png'), fullPage: true });
    await s.page.locator('#import-adjustment-delta').fill('-1'); await s.page.locator('#import-adjustment-reason').fill('Hàng vỡ khi dỡ xe');
    await s.page.locator('#import-adjustment-form button[type=submit]').click();
    await s.page.locator('#modal-receipt-detail').waitFor({ state: 'hidden' });
    assert.equal(writes(s).at(-1).body.delta, -1); assert.equal(writes(s).at(-1).body.items[0].lotNumber, 'LOT-A');
    assert.ok(!s.state.calls.some(c => ['/suppliers', '/orders', '/warehouse/inventory'].some(p => c.endpoint.startsWith(p))));
    await s.page.locator('#tab-create-receipt').click();
    assert.equal(await s.page.locator('#receipt-code').inputValue(), '', 'Confirming a draft clears its edit identity');
    await fill(s);
    await s.page.locator('#btn-save-receipt').click();
    await s.page.locator('[data-view-receipt="PNK-SERVER-3"]').waitFor();
    assert.deepEqual(writes(s).slice(-2).map(call => call.endpoint), ['/warehouse/import/draft', '/warehouse/vouchers/3/confirm']);
    assert.equal(s.state.rows.find(row => row.id === 3).status, 'CONFIRMED');

    const only = await setup(['WAREHOUSE_IMPORT']); await only.page.goto(base + '/warehouse/units?tab=receipts'); await fill(only);
    assert.equal(await only.page.locator('#btn-save-receipt').isVisible(), false);
    await only.page.locator('#btn-save-draft-receipt').click(); await only.page.locator('[data-view-receipt]').click();
    assert.equal(await only.page.locator('[data-confirm-import]').count(), 0);
    const approve = await setup(['WAREHOUSE_APPROVE'], only.state.rows); await approve.page.goto(base + '/warehouse/units?tab=ledger');
    await approve.page.locator('[data-view-receipt]').click(); assert.equal(await approve.page.locator('[data-edit-draft]').count(), 0);
    await approve.page.locator('[data-confirm-import]').click(); await approve.page.locator('[data-confirm-import]').waitFor({ state: 'detached' });
    assert.equal(writes(approve).length, 1);
    only.state.rights = [];
    await only.page.evaluate(() => { const user = JSON.parse(sessionStorage.getItem('user')); user.permissions = []; sessionStorage.setItem('user', JSON.stringify(user)); window.dispatchEvent(new CustomEvent('auth:permissions-changed')); });
    await only.page.waitForURL(url => url.pathname === '/error' && url.searchParams.get('status') === '403');
    assert.equal(await only.page.locator('#btn-save-draft-receipt').count(), 0);
    await s.page.locator('.toast').last().waitFor({ state: 'detached' });
    for (const width of [768, 390]) {
      await s.page.setViewportSize({ width, height: 900 });
      await s.page.evaluate(() => scrollTo(0, 0));
      await s.page.screenshot({ path: path.join(output, `ledger-${width}.png`), fullPage: true });
      assert.ok(await s.page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), `No page overflow at ${width}`);
      await s.page.locator('#tab-create-receipt').click();
      await s.page.evaluate(() => scrollTo(0, 0));
      await s.page.screenshot({ path: path.join(output, `builder-${width}.png`), fullPage: true });
      assert.ok(await s.page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), `No builder overflow at ${width}`);
      assert.ok(await s.page.locator('.line-sku-select').first().evaluate(el => el.clientWidth >= 160), 'SKU controls must remain readable inside a scrollable table');
      await s.page.locator('#tab-ledger-audit').click();
    }
    assert.deepEqual(errors, []);
    for (const session of [s, only, approve]) await session.context.close();
    console.log('S5-04 Chrome: DB draft/reload/update/confirm/errors/adjustment, matrix import-only/approve-only/revoke, XSS and desktop/tablet/mobile passed. Screenshots: ' + output);
  } catch (error) {
    if (current) { console.error(JSON.stringify({ url: current.page.url(), calls: current.state.calls, toasts: await current.page.locator('.toast__message').allTextContents(), errors })); await current.page.screenshot({ path: path.join(output, 'failure.png'), fullPage: true }); }
    throw error;
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
