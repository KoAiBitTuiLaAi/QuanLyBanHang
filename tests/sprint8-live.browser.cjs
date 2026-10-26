// Run against an already running frontend + backend with a local test account.
// QLBH_TEST_USERNAME / QLBH_TEST_PASSWORD are required. No API responses are mocked.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright');
const base = process.env.QLBH_BASE_URL || 'http://localhost:5500';
const output = process.env.QLBH_TEST_OUTPUT || path.join(require('node:os').tmpdir(), 'qlbh-sprint8-live');
const localDate = new Date();
const today = `${localDate.getFullYear()}-${String(localDate.getMonth()+1).padStart(2,'0')}-${String(localDate.getDate()).padStart(2,'0')}`;
const stories = [
  ['S8-01', '/warehouse/dealer-returns'], ['S8-02', '/finance/debt-adjustment'],
  ['S8-03', '/warehouse/adjustments'], ['S8-04', '/sales-targets'],
  ['S8-05', '/reports/dashboard'], ['S8-06', '/reports/sales'],
  ['S8-07', '/reports/inventory-turnover'], ['S8-08', '/warehouse/inventory'],
  ['S8-09', '/reports/debt-aging'], ['S8-10', '/finance/debt-statement'],
];

(async () => {
  assert.ok(process.env.QLBH_TEST_USERNAME && process.env.QLBH_TEST_PASSWORD, 'Set test login environment variables');
  fs.mkdirSync(output, { recursive: true });
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  const report = { startedAt: new Date().toISOString(), checks: [], errors: [], apiErrors: [], downloads: [] };
  const check = (name, data = {}) => { report.checks.push({ name, ...data }); console.log(name); };
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1000 }, acceptDownloads: true });
    page.on('pageerror', e => report.errors.push(e.message));
    page.on('response', r => {
      if (r.url().includes(':8080/api/') && r.status() >= 400) report.apiErrors.push({ url: r.url(), status: r.status() });
    });
    await page.goto(base + '/login');
    await page.locator('#username').fill(process.env.QLBH_TEST_USERNAME);
    await page.locator('#password').fill(process.env.QLBH_TEST_PASSWORD);
    await page.locator('#login-form button[type="submit"]').click();
    await page.waitForURL(u => !u.pathname.includes('login'));
    const settle = async () => { await page.waitForTimeout(100); await page.waitForLoadState('networkidle'); };
    async function open(href) {
      await page.locator(`#sidebar-nav a[href="${href}"]`).click();
      await page.waitForURL(base + href); await settle();
    }
    async function download(selector, label) {
      if (await page.locator(selector).isDisabled()) { check(label + ': disabled for empty data'); return; }
      const pending = page.waitForEvent('download'); await page.locator(selector).click();
      const file = await pending;
      assert.equal(await file.failure(), null);
      const filename = file.suggestedFilename();
      const destination = path.join(output, filename); await file.saveAs(destination);
      const bytes = fs.readFileSync(destination);
      assert.ok(bytes.length > 100);
      assert.equal(bytes.subarray(0, 2).toString(), filename.endsWith('.pdf') ? '%P' : 'PK');
      report.downloads.push({ label, filename, bytes: bytes.length }); check(label);
    }
    for (const [story, href] of stories) {
      const started = Date.now(); await open(href);
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1), false, story + ' desktop overflow');
      check(story + ': sidebar navigation', { readyMs: Date.now() - started });
      if (story === 'S8-01') {
        await page.locator('#dr-new').click(); await settle();
        assert.equal(await page.locator('#dr-invoice-list').isVisible(), true);
        check(story + ': source invoice picker');
      } else if (story === 'S8-02') {
        await page.locator('#fr-new').click(); await settle();
        assert.equal(await page.locator('#fr-invoices').isVisible(), true);
        check(story + ': source invoice picker');
      } else if (story === 'S8-03') {
        await page.locator('#adj-new').click();
        assert.equal(await page.locator('#adj-create-submit').isDisabled(), true);
        await page.locator('#adj-create-close').click();
        await page.locator('#adj-filter-keyword').fill('QA-NO-SUCH-ADJUSTMENT');
        await page.locator('#adj-filter-submit').click(); await settle();
        assert.equal(await page.locator('#adj-list button').count(), 0);
        await page.locator('#adj-filter-reset').click(); await settle();
        if (await page.locator('#adj-list-rows button').count()) {
          await page.locator('#adj-list-rows button').first().click();
          await page.locator('#adj-detail').waitFor({state:'visible'});
          assert.ok(await page.locator('#adj-detail-rows tr').count()>0);
          await page.locator('#adj-detail-close').click();
        }
        check(story + ': required input, empty filter and existing voucher detail');
      } else if (story === 'S8-04') {
        await page.locator('#st-view-mode').selectOption('region'); await settle();
        await page.locator('#tab-my').click(); await settle();
        check(story + ': region and own progress tabs');
      } else if (story === 'S8-05') {
        await page.locator('#ops-reload').click(); await settle();
        check(story + ': refresh actual dashboard');
      } else if (story === 'S8-06') {
        for (const value of await page.locator('#sr-group-by option').evaluateAll(opts => opts.map(o => o.value))) {
          await page.locator('#sr-group-by').selectOption(value);
          await page.locator('#sr-apply').click(); await settle();
          check(story + ': grouping ' + value);
        }
        await page.locator('#sr-compare').check(); await page.locator('#sr-apply').click(); await settle();
        await download('#sr-export', story + ': Excel comparison');
      } else if (story === 'S8-07') {
        for (const tab of ['category', 'matrix', 'slow-moving', 'skus', 'warehouse']) await page.locator('#tab-' + tab).click();
        const real = await page.evaluate(async () => (await (await Auth.apiFetch('/reports/inventory-turnover?' + new URLSearchParams({
          fromDate: document.querySelector('#it-filter-from').value, toDate: document.querySelector('#it-filter-to').value,
          slowMovingThresholdDays: document.querySelector('#it-filter-threshold').value,
        }))).json()).data);
        for (const row of real.warehouseValuations) {
          const cells = await page.locator('#it-warehouse-rows tr').filter({ hasText: row.warehouseCode }).locator('td').allTextContents();
          const expected = new Intl.NumberFormat('vi-VN', { maximumFractionDigits: 4 }).format(row.totalPhysicalQuantity);
          assert.equal(cells[2], expected, 'Physical quantity must match actual API field');
        }
        await download('#it-export', story + ': Excel');
        const warehouses = await page.locator('#it-filter-warehouse option').evaluateAll(opts => opts.filter(o => o.value).slice(0, 2).map(o => ({ id: o.value, code: o.textContent.split(' - ')[0] })));
        if (warehouses.length === 2) {
          let release, ready = false, finish;
          const gate = new Promise(r => release = r), delivered = new Promise(r => finish = r);
          const pattern = '**/api/reports/inventory-turnover?*';
          await page.route(pattern, async route => {
            if (new URL(route.request().url()).searchParams.get('warehouseId') === warehouses[0].id) {
              const response = await route.fetch(); ready = true; await gate;
              await route.fulfill({ response }); finish();
            } else await route.continue();
          });
          await page.locator('#it-filter-warehouse').selectOption(warehouses[0].id); await page.locator('#it-filter-submit').click();
          const deadline = Date.now() + 10000; while (!ready && Date.now() < deadline) await new Promise(r => setTimeout(r, 30));
          assert.equal(ready, true);
          await page.locator('#it-filter-warehouse').selectOption(warehouses[1].id); await page.locator('#it-filter-submit').click();
          await page.waitForFunction(code => document.querySelectorAll('#it-warehouse-rows tr').length === 1 && document.querySelector('#it-warehouse-rows').textContent.includes(code), warehouses[1].code);
          release(); await delivered; await settle();
          assert.equal((await page.locator('#it-warehouse-rows').innerText()).includes(warehouses[0].code), false);
          await page.unroute(pattern); check(story + ': delayed REAL response cannot overwrite latest filter');
        }
      } else if (story === 'S8-08') {
        const warehouse = await page.locator('#stock-warehouse option').evaluateAll(opts => opts.find(o => o.value)?.value);
        if (warehouse) await page.locator('#stock-warehouse').selectOption(warehouse);
        await page.locator('#stock-apply').click(); await settle(); await download('#stock-export', story + ': filtered stock Excel');
      } else if (story === 'S8-09') {
        await page.locator('#aging-group').selectOption('employee'); await settle();
        await page.locator('#aging-load').click(); await settle(); await download('#aging-export', story + ': aged debt Excel');
      } else if (story === 'S8-10') {
        await download('#ds-export', story + ': debt overview Excel');
        await page.locator('#debt-tab-statement').click();
        const dealer = await page.locator('#filter-dealer option').evaluateAll(opts => opts.find(o => o.value)?.value);
        if (dealer) {
          await page.locator('#filter-dealer').selectOption(dealer);
          await page.locator('#filter-from').fill(`${localDate.getFullYear()}-01-01`); await page.locator('#filter-to').fill(today);
          await page.locator('#btn-view-statement').click(); await settle();
          await page.locator('#statement-result-panel').waitFor({state:'visible',timeout:10000});
          await download('#btn-export-excel', story + ': statement Excel');
          await download('#btn-export-pdf', story + ': statement PDF');
        }
      }
      await page.screenshot({ path: path.join(output, story + '-tested.png'), fullPage: true });
    }
    await page.setViewportSize({ width: 390, height: 844 });
    for (const [story, href] of stories) {
      // Mobile: use actual destination and inspect shell/content, desktop already clicked each menu.
      await page.goto(base + href); await settle();
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 1), false, story + ' mobile overflow');
      await page.screenshot({ path: path.join(output, story + '-mobile.png'), fullPage: true });
    }
    check('All ten Sprint 8 destinations: mobile layout');
    assert.deepEqual(report.errors, []);
    assert.deepEqual(report.apiErrors, []);
  } catch (error) { report.failure = error.message; throw error; }
  finally { fs.writeFileSync(path.join(output, 'live-report.json'), JSON.stringify(report, null, 2)); await browser.close(); }
})().catch(e => { console.error(e.message); process.exitCode = 1; });

