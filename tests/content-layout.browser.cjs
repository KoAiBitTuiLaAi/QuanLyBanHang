// Actual page layout in Chrome with mocked HTTP; no backend/database writes.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright');
const base = process.env.NAV_TEST_URL || 'http://127.0.0.1:5519';
const output = path.join(require('node:os').tmpdir(), 'qlbh-content-layout');
const cases = [
  '/dashboard', '/dashboard?view=debt-limits', '/profile', '/products', '/product-groups',
  '/create-order', '/orders', '/order-approval', '/dealer-orders', '/dealer-portal', '/price-lists',
  '/discount-policies', '/dealers', '/suppliers', '/warehouse/units?tab=inventory',
  '/warehouse/units?tab=units', '/warehouse/units?tab=receipts', '/warehouse/units?tab=ledger',
  '/admin/users', '/admin/audit-logs', '/warehouses', '/warehouse/inventory', '/warehouse/transfers', '/warehouse/minimums',
  '/warehouse/units?tab=stocktake', '/warehouse/lots', '/warehouse/stock-card',
  '/warehouse/picking', '/warehouse/delivery', '/warehouse/order-shipments', '/warehouse/delivery-trips',
];
const user = { id: 1, username: 'admin', fullName: 'Quản trị viên', email: 'admin@example.invalid',
  phone: '0900000000', roles: ['ROLE_ADMIN'], permissions: [], warehouses: [], territories: [] };
const token = 'test.' + Buffer.from(JSON.stringify({ exp: Math.floor(Date.now() / 1000) + 3600 })).toString('base64url') + '.test';
const dealers = ['Minh Phát', 'Tân Bình', 'Hà Nội', 'Khách lẻ', 'Hoàng Phát'].map((name, index) => ({
  id: index + 1, dealerId: index + 1, code: 'DL-' + (index + 1), name: 'Đại lý ' + name,
  dealerName: 'Đại lý ' + name, customerName: 'Đại lý ' + name, active: true, customerGroup: 'DAI_LY_CAP_1',
  debtLimit: 100000000, maxDebtDays: 30, currentDebt: 1000000, remainingLimit: 99000000,
}));

(async () => {
  fs.mkdirSync(output, { recursive: true });
  const browser = await chromium.launch({ headless: true, channel: 'chrome' });
  const errors = [];
  const results = [];
  try {
    const context = await browser.newContext({ reducedMotion: 'reduce' });
    await context.addInitScript(({ user, token }) => {
      sessionStorage.setItem('user', JSON.stringify(user));
      sessionStorage.setItem('accessToken', token);
    }, { user, token });
    await context.route('http://localhost:8080/api/**', async route => {
      const endpoint = new URL(route.request().url()).pathname.replace('/api', '');
      let data = [];
      if (endpoint === '/auth/permissions') data = { roles: user.roles, permissions: user.permissions };
      else if (['/users/profile', '/auth/me'].includes(endpoint)) data = user;
      else if (endpoint === '/admin/assignment-options') data = { roles: user.roles, warehouses: [], territories: [], dealers: [] };
      else if (['/admin/users', '/admin/audit-logs'].includes(endpoint)) data = { content: [], totalElements: 0, totalPages: 1, number: 0, first: true, last: true };
      else if (endpoint === '/warehouses/dealer-assignments') data = { content: [], totalElements: 0, totalPages: 1, number: 0, first: true, last: true };
      else if (endpoint === '/warehouse/stock-ledger/options') data = { warehouses: [{ id: 1, code: 'KHO-HN', name: 'Kho Hà Nội' }], categories: [], stockStatuses: [] };
      else if (endpoint === '/warehouse/stock-ledger') data = { content: [], totalElements: 0, totalPages: 0, number: 0, first: true, last: true };
      else if (endpoint === '/warehouse/stock-alerts') data = {content: [], totalElements: 0, totalPages: 0};
      else if (endpoint === '/warehouse/stock-minimums/options') data = {warehouses: [], categories: []};
      else if (endpoint === '/warehouse/stock-minimums') data = {content: [], totalElements: 0, totalPages: 0};
      else if (endpoint === '/warehouse/transfers/options') data = {sourceWarehouses: [], destinationWarehouses: [], statuses: [], canCreate: true};
      else if (endpoint === '/warehouse/transfers/search') data = {content: [], totalElements: 0, totalPages: 0};
      else if (endpoint === '/warehouse/picking-batches/options') data = {warehouses: [], statuses: [], canCreate: true, quantityScale: 4, maxOrders: 100, maxLines: 500, routeStrategy: 'Thứ tự khu/kệ'};
      else if (endpoint === '/warehouse/picking-batches') data = {content: [], totalElements: 0, totalPages: 0};
      else if (endpoint === '/warehouse/order-shipments/options') data = {warehouses: [], canConfirm: true, quantityScale: 4, reservationPolicy: 'Phần còn nợ tiếp tục giữ chỗ'};
      else if (endpoint === '/warehouse/order-shipments') data = {content: [], totalElements: 0, totalPages: 0};
      else if (endpoint === '/warehouse/delivery-trips/options') data = {warehouses: [], tripStatuses: [], stopStatuses: [], canManage: false};
      else if (endpoint === '/warehouse/delivery-trips/summary') data = {totalOutstandingValue:0,totalOutstandingTrips:0,closedOutstandingValue:0,closedOutstandingTrips:0,totalRunningTrips:0,totalValueOnRoad:0,totalDeliveredPoints:0,totalRemainingPoints:0,totalOverdueTrips:0};
      else if (endpoint === '/warehouse/delivery-trips') data = {content: [], totalElements: 0, totalPages: 0};
      else if (endpoint === '/dealers/options') data = { assignedUsers: [], territories: [] };
      else if (['/dealers', '/dealers/active', '/finance/debt', '/portal/dealers'].includes(endpoint)) data = dealers;
      else if (endpoint === '/orders/draft') data = null;
      else if (endpoint === '/warehouse/options') data = [{ id: 1, code: 'KHO-HN', name: 'Kho Hà Nội' }];
        else if (endpoint === '/warehouse/import/options') data = {warehouses: [{id: 1, code: 'KHO-HN', name: 'Kho Hà Nội'}], suppliers: []};
      else if (endpoint === '/warehouse/stock-counts/options') data = {warehouses: [{id: 1, code: 'KHO-HN', name: 'Kho Hà Nội'}], categories: [], statuses: [], canAdjust: true};
      else if (endpoint === '/warehouse/stock-counts') data = {content: [], totalElements: 0, totalPages: 0, number: 0, first: true, last: true};
      await route.fulfill({ status: 200, contentType: 'application/json',
        headers: { 'Access-Control-Allow-Origin': base, 'Access-Control-Allow-Headers': 'Authorization,Content-Type',
          'Access-Control-Allow-Methods': 'GET,POST,PUT,DELETE,OPTIONS' },
        body: JSON.stringify({ success: true, data, totalElements: 0, totalPages: 0 }) });
    });
    const page = await context.newPage();
    page.on('pageerror', error => errors.push({ page: page.url(), message: error.message }));
    for (const width of [1920, 1440, 1024, 768, 390, 320]) {
      await page.setViewportSize({ width, height: 900 });
      for (const route of cases) {
        await page.goto(base + route);
        await page.waitForFunction(() => document.querySelector('#sidebar-nav [data-page]') && !document.querySelector('#sidebar-nav').inert);
        if (route.includes('view=debt-limits')) await page.locator('#debt-limits-view').waitFor({ state: 'visible' });
        const dimensions = await page.evaluate(() => {
          const main = document.querySelector('.main-area');
          const content = main.querySelector(':scope > :is(.content,.admin-main,.catalog-main,.price-main,.disc-main,.profile-layout)');
          const rect = content.getBoundingClientRect();
          const outer = main.getBoundingClientRect();
          const style = getComputedStyle(content);
          return { left: rect.left - outer.left + parseFloat(style.paddingLeft),
            right: outer.right - rect.right + parseFloat(style.paddingRight),
            width: rect.width, gutter: Number.parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--app-content-gutter')),
            sidebarWidth: document.querySelector('#sidebar').getBoundingClientRect().width,
            documentWidth: document.documentElement.scrollWidth, viewport: document.documentElement.clientWidth };
        });
        results.push({ viewport: width, route, ...dimensions });
        assert.ok(Math.abs(dimensions.left - dimensions.right) < 2, `Unequal margins: ${JSON.stringify(results.at(-1))}`);
        assert.ok(dimensions.left >= dimensions.gutter - 1, `Insufficient gutter: ${JSON.stringify(results.at(-1))}`);
        assert.ok(dimensions.width <= 1241, `Frame too wide: ${JSON.stringify(results.at(-1))}`);
        if (dimensions.documentWidth > dimensions.viewport + 1) {
          const overflowing = await page.evaluate(() => [...document.querySelectorAll('.main-area *')]
            .filter(element => element.getBoundingClientRect().right > document.documentElement.clientWidth + 1
              && element.getBoundingClientRect().width > 0 && getComputedStyle(element).position !== 'fixed')
            .slice(0, 8).map(element => ({ tag: element.tagName, class: element.className, text: element.textContent.slice(0, 60) })));
          assert.fail(`Page overflow: ${JSON.stringify({ ...results.at(-1), overflowing })}`);
        }
        assert.equal(dimensions.sidebarWidth, 260, 'Sidebar sizing must not change');
        assert.equal(await page.locator('.main-area').count(), 1);
        if ([1920, 390].includes(width) && ['/dashboard?view=debt-limits', '/discount-policies', '/profile', '/orders', '/dealer-portal'].includes(route)) {
          await page.screenshot({ path: path.join(output, `${route.split('?')[0].replaceAll('/', '-')}-${width}.png`) });
        }
      }
    }
    assert.deepEqual(errors, [], 'Layout changes must not break page scripts');
    fs.writeFileSync(path.join(output, 'measurements.json'), JSON.stringify(results, null, 2));
    console.log(JSON.stringify({ passed: true, checks: results.length, pages: cases.length, viewports: [1920,1440,1024,768,390,320], output }));
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
