// S5-05 & S8-08 in Chrome. All HTTP is mocked; production DB and matrix remain untouched.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright');
const base = process.env.NAV_TEST_URL || 'http://127.0.0.1:5519';
const output = path.join(require('node:os').tmpdir(), 'qlbh-s505');
const user = { id: 5, username: 'stock-viewer', fullName: 'Nhân viên xem tồn', roles: ['ROLE_VIEWER'], permissions: ['WAREHOUSE_VIEW'], warehouses: [], territories: [] };
const token = 'test.' + Buffer.from(JSON.stringify({ exp: Date.now()/1000 + 3600 })).toString('base64url') + '.test';
const warehouses = [{ id: 1, code: 'KHO-HN', name: 'Kho Hà Nội' }, { id: 2, code: 'KHO-HCM', name: 'Kho TP. Hồ Chí Minh' }];
const categories = [{ id: 10, code: 'NUOC', name: 'Nước giải khát' }, { id: 20, code: 'BANH', name: 'Bánh kẹo' }];
const stockStatuses = [{ value: 'IN_STOCK', label: 'Còn khả dụng' }, { value: 'OUT_OF_STOCK', label: 'Hết tồn' }, { value: 'FULLY_RESERVED', label: 'Đã giữ hết' }];
const rows = warehouses.flatMap(w => Array.from({ length: 24 }, (_, i) => {
  const status = stockStatuses[i % 3], category = categories[i < 12 ? 0 : 1];
  return { warehouseId: w.id, warehouseCode: w.code, warehouseName: w.name, productId: i + 1,
    productCode: `SKU-${String(i+1).padStart(3,'0')}`, productName: i === 23 ? '<img src=x onerror=alert(1)>' : `Sản phẩm kho ${i+1}`,
    categoryId: category.id, categoryName: category.name, baseUnit: 'lon',
    physicalQuantity: i % 3 === 1 ? 0 : 100.5, reservedQuantity: i % 3 === 2 ? 100.5 : i % 3 === 1 ? 0 : 20.25,
    availableQuantity: i % 3 === 0 ? 80.25 : 0, stockStatus: status.value, stockStatusLabel: status.label };
}));
(async () => {
  fs.mkdirSync(output, { recursive: true });
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  const calls = [], errors = [];
  let fail = false, noWarehouses = false, slowRelease, slowStarted = false;
  try {
    const context = await browser.newContext({ reducedMotion: 'reduce', acceptDownloads: true });
    await context.addInitScript(({user,token}) => { sessionStorage.setItem('accessToken', token); sessionStorage.setItem('user', JSON.stringify(user)); }, {user,token});
    await context.route('http://localhost:8080/api/**', async route => {
      const req = route.request(), url = new URL(req.url()), endpoint = url.pathname.replace('/api','');
      const headers = { 'Access-Control-Allow-Origin': base, 'Access-Control-Allow-Headers': 'Authorization,Content-Type', 'Access-Control-Allow-Methods': 'GET,OPTIONS' };
      if (req.method() === 'OPTIONS') return route.fulfill({ status: 204, headers });
      calls.push({ endpoint, query: Object.fromEntries(url.searchParams) });
      let data, status = 200;
      if (endpoint === '/auth/permissions') data = { roles: user.roles, permissions: user.permissions };
      else if (!user.roles.includes('ROLE_ADMIN') && !user.permissions.includes('WAREHOUSE_VIEW')) { status = 403; }
      else if (endpoint === '/warehouse/stock-ledger/options') data = { warehouses: noWarehouses ? [] : warehouses, categories, stockStatuses };
      else if (endpoint === '/warehouse/stock-ledger/export') {
        const warehouseId = url.searchParams.get('warehouseId');
        const code = warehouseId === '2' ? 'KHO-HCM' : warehouseId === '1' ? 'KHO-HN' : 'KHO-HN-KHO-HCM';
        const date = '2026-10-23';
        const filename = `So-ton-kho_${code}_${date}.xlsx`;
        return route.fulfill({
          status: 200,
          headers: {
            ...headers,
            'Content-Type': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
            'Content-Disposition': `attachment; filename="${filename}"`,
            'Access-Control-Expose-Headers': 'Content-Disposition'
          },
          body: Buffer.from('mock-excel-binary-data')
        });
      }
      else if (endpoint === '/warehouse/stock-ledger') {
        if (fail) status = 500;
        let selected = rows.filter(row => (!url.searchParams.get('warehouseId') || row.warehouseId === Number(url.searchParams.get('warehouseId')))
          && (!url.searchParams.get('categoryId') || row.categoryId === Number(url.searchParams.get('categoryId')))
          && (!url.searchParams.get('stockStatus') || row.stockStatus === url.searchParams.get('stockStatus'))
          && (!url.searchParams.get('keyword') || (row.productCode + row.productName).toLowerCase().includes(url.searchParams.get('keyword').toLowerCase())));
        if (url.searchParams.get('keyword') === 'slow') {
          slowStarted = true; await new Promise(resolve => { slowRelease = resolve; }); selected = rows.slice(0,1);
        }
        const page = Number(url.searchParams.get('page') || 0), size = Number(url.searchParams.get('size') || 20);
        data = { content: selected.slice(page*size,(page+1)*size), totalElements: selected.length, totalPages: Math.ceil(selected.length/size), number: page, last: (page+1)*size >= selected.length };
      } else throw new Error('Unexpected prerequisite API: ' + endpoint);
      await route.fulfill({ status, headers, contentType: 'application/json', body: JSON.stringify({ success: status === 200, data, message: status === 500 ? 'Không tải được sổ tồn từ server' : 'Không có quyền' }) }).catch(e => { if (!req.failure()) throw e; });
    });
    const page = await context.newPage();
    page.on('pageerror', e => errors.push(e.message));
    await page.goto(base + '/warehouse/units?tab=inventory');
    await page.waitForURL('**/warehouse/inventory');
    await page.locator('#stock-next:not([disabled])').waitFor();
    assert.equal(await page.locator('#stock-rows tr').count(), 20);
    assert.match(await page.locator('#stock-count').innerText(), /48/);
    assert.equal(await page.locator('#sidebar-nav [data-page="warehouse"]').getAttribute('aria-current'), 'page');
    const firstCells = await page.locator('#stock-rows tr').first().locator('td').allTextContents();
    assert.equal(firstCells[4], '100,5'); assert.equal(firstCells[5], '20,25'); assert.equal(firstCells[6], '80,25');
    await page.locator('#stock-next').click(); await page.waitForFunction(() => document.querySelector('#stock-page-label').textContent === 'Trang 2 / 3');
    assert.equal(calls.at(-1).query.page, '1');
    await page.locator('#stock-warehouse').selectOption('2'); await page.locator('#stock-category').selectOption('20');
    await page.locator('#stock-status').selectOption('FULLY_RESERVED'); await page.locator('#stock-keyword').fill('SKU-015');
    await page.locator('#stock-apply').click(); await page.waitForFunction(() => document.querySelector('#stock-count').textContent.includes('/ 1 dòng'));
    const query = calls.at(-1).query;
    assert.deepEqual(query, { warehouseId:'2',categoryId:'20',stockStatus:'FULLY_RESERVED',keyword:'SKU-015',page:'0',size:'20' });
    assert.match(await page.locator('#stock-rows').innerText(), /Kho TP. Hồ Chí Minh/);
    assert.equal(await page.locator('#stock-rows tr').first().locator('td').nth(6).innerText(), '0');
    await page.locator('#stock-reset').click(); await page.locator('#stock-next:not([disabled])').waitFor();
    await page.evaluate(() => { window.exported = null; });
    await page.locator('#stock-export').click(); await page.waitForFunction(() => window.exported !== null);
    const exported = await page.evaluate(() => window.exported);
    assert.match(exported.name, /^So-ton-kho_.*\.xlsx$/);
    assert.ok(calls.some(c => c.endpoint === '/warehouse/stock-ledger/export'));
    await page.locator('#stock-keyword').fill('không có SKU này'); await page.locator('#stock-apply').click();
    await page.locator('#stock-rows').getByText('Không có sản phẩm phù hợp với bộ lọc.').waitFor();
    assert.equal(await page.locator('#stock-export').isDisabled(), true);
    fail = true; await page.locator('#stock-reset').click(); await page.locator('#stock-error').getByText('Không tải được sổ tồn từ server').waitFor();
    assert.equal(await page.locator('#stock-export').isDisabled(), true);
    assert.match(await page.locator('#stock-count').innerText(), /Chưa tải/);
    fail = false; await page.locator('#stock-reload').click(); await page.locator('#stock-next:not([disabled])').waitFor();
    await page.locator('#stock-keyword').fill('slow'); await page.locator('#stock-apply').click();
    while (!slowStarted) await new Promise(resolve => setTimeout(resolve,10));
    await page.locator('#stock-keyword').fill('SKU-024'); await page.locator('#stock-apply').click();
    await page.waitForFunction(() => document.querySelector('#stock-count').textContent.includes('/ 2 dòng'));
    slowRelease();
    assert.equal(await page.locator('#stock-rows img').count(), 0);
    assert.match(await page.locator('#stock-rows').innerText(), /<img src=x/);
    assert.equal(await page.locator('#stock-rows tr').count(), 2);
    await page.locator('#stock-reset').click(); await page.locator('#stock-next:not([disabled])').waitFor();
    for (const width of [1920,1440,1024,768,390,320]) {
      await page.setViewportSize({ width, height: 1000 });
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth + 1), false);
      const gutters = await page.evaluate(() => { const c = document.querySelector('.content').getBoundingClientRect(), m = document.querySelector('.main-area').getBoundingClientRect(); return [c.left-m.left,m.right-c.right]; });
      assert.ok(Math.abs(gutters[0]-gutters[1]) < 2);
      if ([1920,390].includes(width)) {
        await page.waitForFunction(() => document.querySelectorAll('.toast').length === 0);
        await page.evaluate(() => { window.scrollTo(0,0); return new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))); });
        await page.screenshot({ path: path.join(output,`stock-${width}.png`), fullPage: true });
      }
    }
    noWarehouses = true; await page.locator('#stock-reload').click(); await page.locator('#stock-rows').getByText(/Chưa có kho trong phạm vi/).waitFor();
    assert.equal(await page.locator('#stock-export').isDisabled(),true);
    noWarehouses = false; await page.locator('#stock-reload').click(); await page.locator('#stock-next:not([disabled])').waitFor();
    user.permissions = []; await page.evaluate(() => Auth.syncPermissions()); await page.waitForURL('**/error?status=403');
    user.permissions = ['WAREHOUSE_VIEW']; await page.goto(base + '/warehouse/inventory'); await page.locator('#stock-next:not([disabled])').waitFor();
    user.roles = ['ROLE_ADMIN']; user.permissions = []; await page.reload(); await page.locator('#stock-next:not([disabled])').waitFor();
    assert.ok(calls.every(c => c.endpoint === '/auth/permissions' || c.endpoint.startsWith('/warehouse/stock-ledger')));
    assert.deepEqual(errors, []);
    console.log(JSON.stringify({ passed: true, output, cases: 'three quantities, server filters/pagination, export, retry, empty, stale request, escaped text, six widths, grant/revoke, Admin, old URL' }));
  } finally { slowRelease?.(); await browser.close(); }
})().catch(e => { console.error(e); process.exitCode=1; });
