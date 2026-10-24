// Real HTML/scripts in Chrome against controlled HTTP fixtures, never the real database.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright');
const base = process.env.NAV_TEST_URL || 'http://127.0.0.1:5500';
const output = path.join(require('node:os').tmpdir(), 'qlbh-s508');
const token = 'test.' + Buffer.from(JSON.stringify({ exp: Math.floor(Date.now() / 1000) + 3600 })).toString('base64url') + '.test';
const warehouses = [{ id: 1, code: 'HN', name: 'Kho Hà Nội' }, { id: 2, code: 'HCM', name: 'Kho TP.HCM' }];
const categories = [{ id: 10, code: 'DRINK', name: 'Nước uống' }];
const copy = value => JSON.parse(JSON.stringify(value));

(async () => {
  fs.mkdirSync(output, { recursive: true });
  const browser = await chromium.launch({ headless: true, channel: 'chrome' });
  const errors = [];
  let activePage;
  async function setup(roles = ['ROLE_VIEWER'], permissions = ['WAREHOUSE_ADJUST']) {
    const user = { id: 88, username: 'stocktake-test', fullName: 'Người kiểm thử', roles, permissions, warehouses };
    const state = { rights: permissions, rows: [], calls: [], failSave: false, failComplete: false, failList: false, nextId: 1 };
    const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, reducedMotion: 'reduce' });
    await context.addInitScript(({ user, token }) => {
      sessionStorage.setItem('user', JSON.stringify(user)); sessionStorage.setItem('accessToken', token);
    }, { user, token });
    const has = permission => roles.includes('ROLE_ADMIN') || state.rights.includes(permission);
    const decorate = row => ({ ...copy(row), canEdit: row.status === 'DRAFT', canAdjust: row.status === 'DRAFT' && has('WAREHOUSE_ADJUST') });
    await context.route('http://localhost:8080/api/**', async route => {
      const request = route.request(), url = new URL(request.url()), endpoint = url.pathname.slice(4), method = request.method();
      const headers = { 'Access-Control-Allow-Origin': base, 'Access-Control-Allow-Headers': 'Authorization,Content-Type', 'Access-Control-Allow-Methods': 'GET,POST,PUT,OPTIONS' };
      if (method === 'OPTIONS') return route.fulfill({ status: 204, headers });
      const body = request.postData() ? request.postDataJSON() : null;
      state.calls.push({ endpoint, method, body, query: Object.fromEntries(url.searchParams) });
      let data, status = 200, message = 'Thành công';
      if (endpoint === '/auth/permissions') data = { roles, permissions: state.rights };
      else if (!(has('WAREHOUSE_VIEW') || has('WAREHOUSE_ADJUST'))) { status = 403; message = 'Quyền đã bị thu hồi'; }
      else if (endpoint === '/warehouse/stock-counts/options') data = { warehouses, categories, statuses: ['DRAFT', 'COMPLETED', 'CANCELLED'].map(code => ({ code, label: code })), canAdjust: has('WAREHOUSE_ADJUST') };
      else if (endpoint === '/warehouse/stock-counts' && method === 'GET') {
        if (state.failList) { status = 500; message = 'Lỗi tải danh sách kiểm thử'; }
        const selected = state.rows.filter(row => (!url.searchParams.get('warehouseId') || row.warehouseId === Number(url.searchParams.get('warehouseId'))) && (!url.searchParams.get('status') || row.status === url.searchParams.get('status')));
        const page = Number(url.searchParams.get('page') || 0), size = Number(url.searchParams.get('size') || 20);
        data = { content: selected.slice(page * size, (page + 1) * size).map(decorate), totalElements: selected.length, totalPages: Math.ceil(selected.length / size), number: page, first: page === 0, last: (page + 1) * size >= selected.length };
      } else if (endpoint === '/warehouse/stock-counts' && method === 'POST') {
        await new Promise(resolve => setTimeout(resolve, 70));
        const id = state.nextId++;
        data = { id, code: 'PKK-SERVER-' + id, warehouseId: Number(body.warehouseId), warehouseCode: 'HN', warehouseName: warehouses.find(w => w.id === Number(body.warehouseId)).name,
          categoryId: body.categoryId || null, categoryName: body.categoryId ? 'Nước uống' : null,
          status: 'DRAFT', statusLabel: 'Bản nháp', createdAt: '2026-10-21T13:00:00', createdBy: user.username,
          totalItems: 2, matchedItems: 0, diffItems: 0, uncountedItems: 2,
          items: [{ id: id * 10, productId: 101, productCode: 'SKU-101', productName: 'Nước <img src=x onerror="window.xss=true">', categoryName: 'Nước uống', baseUnit: 'lon', bookQuantity: 10.5, actualQuantity: null, differenceQuantity: null, note: '' },
            { id: id * 10 + 1, productId: 102, productCode: 'SKU-102', productName: 'Nước khoáng', categoryName: 'Nước uống', baseUnit: 'chai', bookQuantity: 5, actualQuantity: null, differenceQuantity: null, note: '' }] };
        state.rows.unshift(data); data = decorate(data);
      } else if (/^\/warehouse\/stock-counts\/\d+$/.test(endpoint) && method === 'GET') {
        if (state.detailDelay) await new Promise(resolve => setTimeout(resolve, state.detailDelay));
        const row = state.rows.find(row => row.id === Number(endpoint.split('/').at(-1)));
        if (!row) { status = 404; message = 'Không tìm thấy phiếu'; } else data = decorate(row);
      } else if (/^\/warehouse\/stock-counts\/\d+\/(items|complete|cancel)$/.test(endpoint)) {
        const row = state.rows.find(row => row.id === Number(endpoint.split('/').at(-2)));
        const action = endpoint.split('/').at(-1);
        await new Promise(resolve => setTimeout(resolve, 70));
        if (!row) { status = 404; message = 'Không tìm thấy phiếu'; }
        else if (row.status !== 'DRAFT') { status = 400; message = 'Phiếu không còn là bản nháp'; }
        else if (action === 'complete' && !has('WAREHOUSE_ADJUST')) { status = 403; message = 'Thiếu quyền chốt'; }
        else if (state.failSave && action === 'items') { status = 400; message = 'Không lưu được số đếm kiểm thử'; }
        else if (state.failComplete && action === 'complete') { status = 400; message = 'Tồn thực tế thấp hơn tồn đã giữ chỗ'; }
        else {
          if (body?.items) for (const line of body.items) {
            const item = row.items.find(item => item.productId === line.productId);
            assert.ok(item, 'Updates must identify items using backend product IDs');
            item.actualQuantity = line.actualQuantity == null ? null : Number(line.actualQuantity);
            item.differenceQuantity = item.actualQuantity == null ? null : item.actualQuantity - item.bookQuantity;
            item.note = line.note || '';
          }
          if (action === 'complete') {
            if (!body.reason?.trim() || row.items.some(item => item.actualQuantity == null)) { status = 400; message = 'Thiếu lý do hoặc số đếm'; }
            else Object.assign(row, { status: 'COMPLETED', statusLabel: 'Đã chốt', adjustmentReason: body.reason, completedAt: '2026-10-21T14:00:00' });
          }
          if (action === 'cancel') Object.assign(row, { status: 'CANCELLED', statusLabel: 'Đã hủy', cancelReason: body?.reason || '', cancelledAt: '2026-10-21T14:00:00' });
          data = decorate(row);
        }
      } else { status = 404; message = 'Unexpected prerequisite API ' + endpoint; }
      await route.fulfill({ status, headers, contentType: 'application/json', body: JSON.stringify({ success: status === 200, data, message }) }).catch(() => {});
    });
    const page = await context.newPage(); activePage = page; page.on('pageerror', error => errors.push(error.message));
    return { context, page, state };
  }
  const writes = (session, action) => session.state.calls.filter(call => call.method !== 'GET' && call.endpoint.endsWith(action));
  try {
    const s = await setup();
    await s.page.goto(base + '/warehouse/units?tab=stocktake');
    await s.page.locator('#btn-start-stocktake:not([disabled])').waitFor();
    assert.equal(await s.page.locator('#sidebar-nav [aria-current="page"]').getAttribute('data-page'), 'warehouse-stocktake');
    await s.page.locator('#stocktake-scope-filter').selectOption('10');
    await s.page.locator('#btn-start-stocktake').click();
    await s.page.locator('#stocktake-table-body .stocktake-actual-input').first().waitFor();
    assert.match(await s.page.locator('#stocktake-voucher-code').innerText(), /PKK-SERVER-1/);
    assert.equal(await s.page.locator('.stocktake-actual-input').first().inputValue(), '', 'New snapshot must not pretend all products were counted');
    assert.equal(await s.page.locator('#stocktake-table-body img').count(), 0);
    assert.equal(await s.page.evaluate(() => window.xss), undefined);
    const created = writes(s, '/stock-counts'); assert.equal(created.length, 1);
    assert.equal(created[0].body.categoryId, 10);
    assert.ok(!created[0].body.items?.length, 'Snapshot and counts must be supplied by server, not fabricated at creation');
    await s.page.locator('#btn-confirm-stocktake:not([disabled])').waitFor();
    await s.page.locator('#btn-confirm-stocktake').click();
    assert.equal(writes(s, '/complete').length, 0, 'Missing reason must block completion');
    await s.page.locator('#stocktake-adjustment-reason').fill('Kiểm tra dòng chưa đếm');
    await s.page.locator('#btn-confirm-stocktake').click();
    assert.equal(writes(s, '/complete').length, 0, 'Blank counts must not become zero');
    const actual = s.page.locator('.stocktake-actual-input');
    await actual.nth(0).fill('0'); await actual.nth(1).fill('4.5');
    await s.page.locator('.stocktake-note-input').first().fill('Đếm thực tế <script>');
    s.state.failSave = true;
    await s.page.locator('#btn-save-draft-stocktake').click();
    await s.page.getByText('Không lưu được số đếm kiểm thử', { exact: false }).first().waitFor();
    assert.equal(await actual.first().inputValue(), '0');
    s.state.failSave = false;
    await s.page.locator('#btn-save-draft-stocktake').click();
    await s.page.waitForFunction(() => !document.querySelector('#btn-save-draft-stocktake').disabled);
    assert.equal(writes(s, '/items').at(-1).body.items[0].actualQuantity, 0);
    await s.page.reload();
    await s.page.locator('#stocktake-history-body [data-view-stocktake]').first().waitFor();
    await s.page.locator('#stocktake-history-body [data-view-stocktake]').first().click();
    await s.page.waitForFunction(() => document.querySelector('.stocktake-actual-input')?.value === '0');
    assert.equal(await s.page.locator('.stocktake-actual-input').nth(1).inputValue(), '4.5');
    await s.page.locator('#stocktake-adjustment-reason').fill('Kiểm kê thực tế');
    s.state.failComplete = true;
    await s.page.locator('#btn-confirm-stocktake').click();
    await s.page.getByText('Tồn thực tế thấp hơn tồn đã giữ chỗ', { exact: false }).first().waitFor();
    assert.equal(await s.page.locator('#stocktake-adjustment-reason').inputValue(), 'Kiểm kê thực tế');
    assert.equal(await s.page.locator('.stocktake-actual-input').first().inputValue(), '0');
    s.state.failComplete = false;
    await s.page.locator('#btn-confirm-stocktake').click();
    await s.page.waitForFunction(() => document.querySelector('#stocktake-status-badge').textContent.includes('chốt') || document.querySelector('#stocktake-status-badge').textContent.includes('COMPLETED'));
    assert.equal(s.state.rows[0].status, 'COMPLETED');
    assert.equal(await s.page.locator('.stocktake-actual-input').first().isDisabled(), true);
    for (const width of [1920, 1440, 1024, 768, 390, 320]) {
      await s.page.setViewportSize({ width, height: 1000 });
      await s.page.waitForFunction(() => document.documentElement.scrollWidth <= innerWidth + 1);
      if ([1440, 390].includes(width)) await s.page.screenshot({ path: path.join(output, 'stocktake-' + width + '.png'), fullPage: true });
    }
    await s.page.setViewportSize({ width: 1440, height: 1000 });
    assert.ok(s.state.calls.every(call => call.endpoint === '/auth/permissions' || call.endpoint.startsWith('/warehouse/stock-counts')), 'ADJUST-only stocktaking must not depend on legacy product/order/warehouse APIs');
    s.state.rights = ['WAREHOUSE_VIEW']; await s.page.evaluate(() => Auth.refreshPermissions());
    await s.page.locator('#btn-start-stocktake:not([disabled])').waitFor();
    await s.page.locator('#btn-start-stocktake').click();
    await s.page.waitForFunction(() => document.querySelector('#stocktake-voucher-code').textContent.includes('PKK-SERVER-2'));
    assert.equal(await s.page.locator('#btn-confirm-stocktake').isDisabled(), true, 'VIEW alone cannot complete');
    s.state.rights = ['WAREHOUSE_ADJUST']; await s.page.evaluate(() => Auth.refreshPermissions());
    await s.page.locator('#stocktake-history-body [data-view-stocktake]:not([disabled])').first().waitFor();
    await s.page.locator('#stocktake-history-body [data-view-stocktake]').first().click();
    await s.page.waitForFunction(() => !document.querySelector('#btn-save-draft-stocktake').disabled);
    await s.page.locator('#stocktake-cancel-reason').fill('Hủy để kiểm đếm lại');
    s.page.once('dialog', dialog => dialog.accept());
    await s.page.locator('#btn-cancel-stocktake').click();
    await s.page.waitForFunction(() => document.querySelector('#stocktake-status-badge').textContent.includes('hủy') || document.querySelector('#stocktake-status-badge').textContent.includes('CANCELLED'));
    assert.equal(s.state.rows[0].status, 'CANCELLED');
    assert.equal(writes(s, '/cancel').length, 1);
    s.state.failList = true;
    await s.page.locator('#btn-start-stocktake:not([disabled])').click();
    await s.page.getByText(/chưa tải lại được lịch sử/).waitFor();
    assert.match(await s.page.locator('#stocktake-voucher-code').innerText(), /PKK-SERVER-3/);
    assert.equal(writes(s, '/stock-counts').length, 3, 'A list reload error must not repeat a successful create');
    s.state.failList = false;
    await s.page.locator('#btn-reload-stocktake').click();
    await s.page.locator('#active-warehouse-select:not([disabled])').waitFor();
    await s.page.locator('#active-warehouse-select').selectOption('2');
    await s.page.waitForFunction(() => document.querySelector('#stocktake-active-card').hidden && !document.querySelector('#active-warehouse-select').disabled);
    assert.equal(await s.page.locator('#stocktake-table-body tr').count(), 0, 'Changing warehouse must clear the previous draft');
    await s.page.locator('#active-warehouse-select').selectOption('1');
    await s.page.locator('#stocktake-history-body [data-view-stocktake]:not([disabled])').first().waitFor();
    s.state.detailDelay = 180;
    const detailRequest = s.page.waitForRequest(request => new URL(request.url()).pathname === '/api/warehouse/stock-counts/3');
    await s.page.locator('#stocktake-history-body [data-view-stocktake]').first().click();
    await detailRequest;
    s.state.rights = []; await s.page.evaluate(() => Auth.refreshPermissions());
    await s.page.waitForFunction(() => location.pathname === '/error' || !document.querySelector('#sidebar-nav [data-page="warehouse-stocktake"]'));
    assert.equal(await s.page.locator('#sidebar-nav [data-page="warehouse-stocktake"]').count(), 0);
    await s.page.waitForTimeout(250);
    assert.equal(await s.page.locator('#stocktake-table-body tr').count(), 0, 'A late response after revocation must not restore count data');
    await s.context.close();
    const admin = await setup(['ROLE_ADMIN'], []);
    await admin.page.goto(base + '/warehouse/units?tab=stocktake');
    await admin.page.locator('#btn-start-stocktake:not([disabled])').waitFor();
    await admin.page.evaluate(() => { const button = document.querySelector('#btn-start-stocktake'); button.click(); button.click(); });
    await admin.page.locator('.stocktake-actual-input').first().waitFor();
    assert.equal(await admin.page.locator('#stocktake-table-body img').count(), 0);
    assert.equal(writes(admin, '/stock-counts').length, 1, 'A pending create cannot be submitted twice');
    await admin.context.close();
    assert.deepEqual(errors, []);
    console.log('S5-08 browser passed: server snapshot, draft reload, zero/decimal counts, save/complete/list failure retention, cancel, warehouse isolation, duplicate writes, current-API-only, live matrix changes and stale responses, ADMIN, XSS and six viewport widths. Screenshots: ' + output);
  } catch (error) {
    if (activePage && !activePage.isClosed()) {
      console.error(JSON.stringify({ errors, url: activePage.url(), text: (await activePage.locator('body').innerText()).slice(0, 6500) }));
      await activePage.screenshot({ path: path.join(output, 'failure.png'), fullPage: true });
    }
    throw error;
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
