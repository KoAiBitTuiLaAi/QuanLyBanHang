// Chrome + mocked HTTP: no production database or permission-matrix writes.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright');
const base = process.env.NAV_TEST_URL || 'http://127.0.0.1:5519';
const output = path.join(require('node:os').tmpdir(), 'qlbh-s503');
const user = { id: 7, username: 'matrix-viewer', fullName: 'Người quản lý kho', roles: ['ROLE_VIEWER'],
  permissions: ['WAREHOUSE_MANAGE'], warehouses: [], territories: [] };
const token = 'test.' + Buffer.from(JSON.stringify({ exp: Date.now() / 1000 + 3600 })).toString('base64url') + '.test';

(async () => {
  fs.mkdirSync(output, { recursive: true });
  const browser = await chromium.launch({ headless: true, channel: 'chrome' });
  const errors = [], calls = [];
  let failSave = false, failList = false, nextId = 10;
  const warehouses = [
    { id: 1, code: 'KHO-HCM', name: 'Kho TP. Hồ Chí Minh', address: '12 Nguyễn Văn Linh', managerName: 'Nguyễn Văn An', active: true },
    { id: 2, code: 'KHO-HN', name: 'Kho Hà Nội', address: '24 Giải Phóng', managerName: 'Trần Thị Bình', active: true },
    { id: 3, code: 'KHO-CU', name: 'Kho tạm ngừng', active: false },
  ];
  const locations = [{ id: 4, warehouseId: 1, code: 'KHU-A', name: 'Khu đồ uống', locationType: 'ZONE', active: true,
    description: 'Hàng khô, thoáng mát' }];
  const dealers = [{ id: 5, code: 'DL-05', name: 'Đại lý Minh Phát', warehouseId: null, active: true }];
  try {
    const context = await browser.newContext({ reducedMotion: 'reduce' });
    await context.addInitScript(({ user, token }) => {
      sessionStorage.setItem('user', JSON.stringify(user)); sessionStorage.setItem('accessToken', token);
    }, { user, token });
    await context.route('http://localhost:8080/api/**', async route => {
      const req = route.request(), url = new URL(req.url()), endpoint = url.pathname.replace('/api', ''), method = req.method();
      const headers = { 'Access-Control-Allow-Origin': base, 'Access-Control-Allow-Headers': 'Authorization,Content-Type',
        'Access-Control-Allow-Methods': 'GET,POST,PUT,OPTIONS' };
      if (method === 'OPTIONS') return route.fulfill({ status: 204, headers });
      const body = ['PUT', 'POST'].includes(method) ? req.postDataJSON() : null;
      calls.push({ endpoint, method, body });
      let data = [], status = 200, message = 'OK';
      const manage = user.roles.includes('ROLE_ADMIN') || user.permissions.includes('WAREHOUSE_MANAGE');
      const view = manage || user.permissions.includes('WAREHOUSE_VIEW');
      if (endpoint === '/auth/permissions') data = { roles: user.roles, permissions: user.permissions };
      else if (!view || ((method !== 'GET' || endpoint.includes('dealer-assignments')) && !manage)) { status = 403; message = 'Quyền đã bị thu hồi'; }
      else if (endpoint === '/warehouses' && method === 'GET') {
        if (failList) { status = 500; message = 'Không tải được danh sách kho'; }
        data = warehouses.map(w => ({ ...w, locationCount: locations.filter(l => l.warehouseId === w.id).length,
          defaultDealerCount: dealers.filter(d => d.warehouseId === w.id).length }));
      } else if (endpoint === '/warehouses' && method === 'POST') {
        if (failSave) { status = 400; message = 'Mã kho đã tồn tại'; }
        else { data = { ...body, id: nextId++ }; warehouses.push(data); }
      } else if (/^\/warehouses\/\d+$/.test(endpoint)) {
        const w = warehouses.find(w => w.id === Number(endpoint.split('/').pop()));
        if (method === 'PUT') Object.assign(w, body);
        data = { ...w, locations: locations.filter(l => l.warehouseId === w.id) };
      } else if (/^\/warehouses\/\d+\/locations$/.test(endpoint)) {
        data = { ...body, id: nextId++, warehouseId: Number(endpoint.split('/')[2]) };
        if (!data.code?.trim()) data.code = `SERVER-${data.id}`;
        data.parentLocationName = locations.find(l => l.id === data.parentLocationId)?.name;
        locations.push(data);
      } else if (/^\/warehouse-locations\/\d+$/.test(endpoint)) {
        data = locations.find(l => l.id === Number(endpoint.split('/').pop()));
        const previousCode = data.code;
        Object.assign(data, body);
        if (!body.code?.trim()) data.code = previousCode;
        data.parentLocationName = locations.find(l => l.id === data.parentLocationId)?.name;
      } else if (endpoint === '/warehouses/dealer-assignments') {
        const content = dealers.filter(d => (d.code + d.name).includes(url.searchParams.get('keyword') || ''));
        data = { content, last: true, number: 0, totalElements: content.length };
      } else if (/^\/warehouses\/dealer-assignments\/\d+$/.test(endpoint)) {
        data = dealers.find(d => d.id === Number(endpoint.split('/').pop()));
        const w = warehouses.find(w => w.id === body.warehouseId);
        Object.assign(data, { warehouseId: w.id, warehouseCode: w.code, warehouseName: w.name });
      } else if (!endpoint.startsWith('/auth/')) throw new Error('Unexpected endpoint: ' + endpoint);
      await route.fulfill({ status, headers, contentType: 'application/json', body: JSON.stringify({ success: status === 200, message, data }) });
    });
    const page = await context.newPage();
    context.setDefaultTimeout(45000);
    context.on('weberror', error => console.error('Browser error:', error.error().message));
    page.on('requestfailed', req => console.error('Request failed:', req.url(), req.failure()?.errorText));
    page.on('pageerror', error => errors.push(error.message));
    await page.goto(base + '/warehouses');
    await page.locator('#wh-dealer-rows [data-action="assign-dealer"]').waitFor();
    await page.locator('#wh-add-location:not([disabled])').waitFor();
    assert.equal(await page.locator('#sidebar-nav [data-page="warehouses"]').count(), 1);
    assert.match(await page.locator('.topbar__breadcrumb').innerText(), /Kho & vị trí lưu/);
    assert.equal(await page.locator('#wh-create').isVisible(), true);
    // No USER_VIEW / CUSTOMER_VIEW / CUSTOMER_EDIT was needed for any prerequisite.
    await page.locator('#wh-create').click();
    await page.locator('#wh-edit-code').fill('KHO-DN'); await page.locator('#wh-edit-name').fill('Kho Đà Nẵng');
    await page.locator('#wh-edit-address').fill('15 Lê Duẩn'); await page.locator('#wh-edit-manager').fill('Lê Minh');
    await page.locator('#wh-save').click(); await page.waitForFunction(() => !document.querySelector('#wh-dialog').open);
    await page.locator('#wh-rows').getByText('Kho Đà Nẵng', { exact: true }).waitFor();
    const created = calls.find(c => c.endpoint === '/warehouses' && c.method === 'POST').body;
    assert.deepEqual(created, { code: 'KHO-DN', name: 'Kho Đà Nẵng', address: '15 Lê Duẩn', managerName: 'Lê Minh', active: true });
    await page.locator('[data-action="edit-warehouse"][data-id="2"]').click();
    await page.locator('#wh-edit-manager').fill('Trần Thu Hà'); await page.locator('#wh-save').click();
    await page.waitForFunction(() => !document.querySelector('#wh-dialog').open);
    await page.locator('#wh-rows').getByText('Trần Thu Hà', { exact: true }).waitFor();
    await page.locator('#wh-search').fill('KHO-HN'); await page.locator('#wh-filter').evaluate(el => el.requestSubmit());
    assert.equal(await page.locator('#wh-rows tr').count(), 1);
    await page.locator('#wh-search').fill(''); await page.locator('#wh-filter').evaluate(el => el.requestSubmit());
    await page.locator('#wh-add-location').click();
    await page.locator('#wh-edit-code').fill('KE-A1'); await page.locator('#wh-edit-name').fill('Kệ A1');
    await page.locator('#wh-edit-parent').selectOption('4'); await page.locator('#wh-save').click();
    await page.waitForFunction(() => !document.querySelector('#wh-dialog').open);
    await page.locator('#wh-location-rows').getByText('Kệ A1', { exact: true }).waitFor();
    const shelf = locations.find(l => l.code === 'KE-A1');
    assert.equal(shelf.warehouseId, 1); assert.equal(shelf.parentLocationId, 4);
    await page.locator(`[data-action="edit-location"][data-id="${shelf.id}"]`).click();
    await page.locator('#wh-edit-parent').selectOption('0'); await page.locator('#wh-save').click();
    await page.waitForFunction(() => !document.querySelector('#wh-dialog').open);
    assert.equal(shelf.parentLocationId, 0);
    await page.locator('#wh-add-location:not([disabled])').waitFor();
    await page.locator('#wh-add-location').click();
    await page.locator('#wh-edit-type').selectOption('ZONE'); assert.equal(await page.locator('#wh-edit-parent').isDisabled(), true);
    await page.locator('#wh-edit-code').fill('KHU-B'); await page.locator('#wh-edit-name').fill('Khu B');
    await page.locator('#wh-save').click(); await page.waitForFunction(() => !document.querySelector('#wh-dialog').open);
    await page.locator('#wh-location-rows').getByText('Khu B', { exact: true }).waitFor();
    await page.locator('#wh-location-type').selectOption('ZONE'); assert.equal(await page.locator('#wh-location-rows tr').count(), 2);
    await page.locator('#wh-location-type').selectOption('');
    await page.locator('#wh-add-location:not([disabled])').click();
    await page.locator('#wh-edit-name').fill('Kệ có mã tự sinh');
    await page.locator('#wh-edit-picking-sequence').fill('7');
    assert.equal(await page.locator('#wh-edit-code').getAttribute('required'), null);
    assert.match(await page.locator('#wh-code-help').innerText(), /Để trống/);
    await page.screenshot({ path: path.join(output, 'location-auto-code.png') });
    await page.locator('#wh-save').click();
    await page.waitForFunction(() => !document.querySelector('#wh-dialog').open);
    await page.locator('#wh-location-rows').getByText('Kệ có mã tự sinh', { exact: true }).waitFor();
    const auto = locations.find(l => l.name === 'Kệ có mã tự sinh');
    assert.equal(auto.pickingSequence, 7);
    assert.equal(calls.findLast(c => c.method === 'POST' && c.endpoint.endsWith('/locations')).body.code, '');
    await page.locator('#wh-location-rows').getByText(auto.code, { exact: true }).waitFor();
    await page.locator(`[data-action="edit-location"][data-id="${auto.id}"]`).click();
    assert.equal(await page.locator('#wh-edit-picking-sequence').inputValue(), '7');
    await page.locator('#wh-dialog').waitFor({ state: 'visible' });
    assert.equal(await page.locator('#wh-edit-code').inputValue(), auto.code);
    const oldCode = auto.code;
    await page.locator('#wh-edit-code').fill('');
    await page.locator('#wh-save').click();
    await page.waitForFunction(() => !document.querySelector('#wh-dialog').open);
    assert.equal(auto.code, oldCode);
    await page.locator('#wh-location-rows').getByText(oldCode, { exact: true }).waitFor();
    await page.locator('[data-action="assign-dealer"]').click();
    assert.equal(await page.locator('#wh-edit-warehouse option[value="3"]').count(), 0);
    await page.locator('#wh-edit-warehouse').selectOption('2'); await page.locator('#wh-save').click();
    await page.waitForFunction(() => !document.querySelector('#wh-dialog').open);
    await page.locator('#wh-dealer-rows').getByText(/Kho Hà Nội/).waitFor();
    assert.equal(dealers[0].warehouseId, 2);
    failSave = true; await page.locator('#wh-create').click();
    await page.locator('#wh-edit-code').fill('KHO-DN'); await page.locator('#wh-edit-name').fill('Tên giữ lại khi lỗi');
    await page.locator('#wh-save').click(); await page.locator('#wh-form-message').getByText('Mã kho đã tồn tại').waitFor();
    assert.equal(await page.locator('#wh-edit-name').inputValue(), 'Tên giữ lại khi lỗi');
    await page.keyboard.press('Escape');
    await page.waitForFunction(() => !document.querySelector('#wh-dialog').open);
    failSave = false;
    for (const width of [1920, 1440, 1024, 768, 390, 320]) {
      await page.setViewportSize({ width, height: 1000 });
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth + 1), false);
      if ([1920, 390].includes(width)) {
        await page.waitForFunction(() => document.querySelectorAll('.toast').length === 0);
        await page.screenshot({ path: path.join(output, `warehouses-${width}.png`), fullPage: true });
      }
      await page.locator('#wh-create').click();
      await page.locator('#wh-dialog').waitFor({ state: 'visible' });
      assert.equal(await page.evaluate(() => { const r = document.querySelector('#wh-dialog').getBoundingClientRect(); return r.left >= 0 && r.right <= innerWidth; }), true);
      await page.keyboard.press('Escape');
      await page.waitForFunction(() => !document.querySelector('#wh-dialog').open);
    }
    await page.locator('#wh-create').click();
    await page.waitForFunction(() => document.querySelector('#wh-dialog').open);
    user.permissions = ['WAREHOUSE_VIEW'];
    await page.evaluate(() => Auth.syncPermissions());
    assert.equal(await page.locator('#wh-dialog').evaluate(el => el.open), false);
    assert.equal(await page.locator('#wh-create').isVisible(), false);
    assert.equal(await page.locator('#wh-dealers').isVisible(), false);
    assert.equal(await page.locator('[data-action="edit-warehouse"]').count(), 0);
    assert.equal(await page.locator('#wh-readonly').isVisible(), true);
    const viewStart = calls.length;
    await page.reload(); await page.locator('#wh-location-rows').getByText('Khu B', { exact: true }).waitFor();
    assert.ok(!calls.slice(viewStart).some(c => c.endpoint.includes('dealer-assignments')));
    failList = true; await page.locator('#wh-reload').click();
    await page.locator('#wh-message').getByText('Không tải được danh sách kho').waitFor();
    failList = false; await page.locator('#wh-reload').click();
    await page.locator('#wh-rows').getByText('Kho TP. Hồ Chí Minh', { exact: true }).waitFor();
    user.permissions = ['WAREHOUSE_MANAGE']; await page.evaluate(() => Auth.syncPermissions());
    await page.locator('[data-action="assign-dealer"]').waitFor(); assert.equal(await page.locator('#wh-create').isVisible(), true);
    user.permissions = []; await page.evaluate(() => Auth.syncPermissions());
    await page.waitForURL('**/error?status=403');
    user.roles = ['ROLE_ADMIN']; await page.goto(base + '/warehouses');
    await page.locator('[data-action="assign-dealer"]').waitFor(); assert.equal(await page.locator('#wh-create').isVisible(), true);
    assert.deepEqual(errors, []);
    console.log(JSON.stringify({ passed: true, output, cases: 'warehouse CRUD, zone/shelf/parent, assignment, filters, errors, six widths, grant/revoke existing session, Admin' }));
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
