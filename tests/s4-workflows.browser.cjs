// Uses actual HTML/scripts in Chrome, mocked HTTP only; never touches the real database.
const assert = require('node:assert/strict');
const path = require('node:path');
const fs = require('node:fs');
const { chromium } = require('playwright');
const base = process.env.NAV_TEST_URL || 'http://127.0.0.1:5519';
const output = path.join(require('node:os').tmpdir(), 'qlbh-s4-workflows');
const token = 'test.' + Buffer.from(JSON.stringify({ exp: Math.floor(Date.now() / 1000) + 3600 })).toString('base64url') + '.test';
const product = { id: 101, sku: 'SKU-101', name: 'Sản phẩm kiểm thử', baseUnit: 'lon', basePrice: 100000,
  categoryName: 'Hàng hóa', units: [{ unitName: 'lon', factor: 1, price: 100000 }] };

(async () => {
  fs.mkdirSync(output, { recursive: true });
  const browser = await chromium.launch({ headless: true, channel: 'chrome' });
  const errors = [];
  async function setup(roles, permissions, handler) {
    // Isolate business flows from native cross-document transition cancellation on context teardown.
    // navigation.browser.cjs separately checks native motion/back/forward/reload.
    const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, reducedMotion: 'reduce' });
    const user = { id: 12, username: 'test-user', fullName: 'Người dùng kiểm thử', roles, permissions, warehouses: [], territories: [] };
    const rights = { permissions: [...permissions] };
    await context.addInitScript(({ user, token }) => {
      sessionStorage.setItem('user', JSON.stringify(user));
      sessionStorage.setItem('accessToken', token);
    }, { user, token });
    await context.route('http://localhost:8080/api/**', async route => {
      const request = route.request();
      const endpoint = new URL(request.url()).pathname.replace('/api', '');
      const result = endpoint === '/auth/permissions' ? { data: { roles, permissions: rights.permissions } }
        : await handler(endpoint, request);
      await route.fulfill({ status: result?.status || 200, contentType: 'application/json',
        headers: { 'Access-Control-Allow-Origin': base, 'Access-Control-Allow-Headers': 'Authorization,Content-Type',
          'Access-Control-Allow-Methods': 'GET,POST,PUT,DELETE,OPTIONS' },
        body: JSON.stringify({ success: result?.status !== 500, message: 'Kiểm thử', data: result?.data ?? [] }) });
    });
    const page = await context.newPage();
    page.on('pageerror', error => errors.push(error.message));
    page.on('dialog', dialog => dialog.accept());
    return { context, page, rights };
  }
  try {
    let failPreview = false;
    const portalData = async endpoint => {
      if (endpoint === '/portal/dealers') return { data: [{ id: 10, name: 'Đại lý kiểm thử', code: 'DL-10' }] };
      if (endpoint === '/portal/profile') return { data: { id: 10, name: 'Đại lý kiểm thử', code: 'DL-10', active: true, customerGroup: 'DAI_LY_CAP_1' } };
      if (endpoint === '/portal/debt') return { data: { currentDebt: 1000000, debtLimit: 100000000, remainingLimit: 99000000, hasOverdueDebt: false, isBlocked: false } };
      if (endpoint === '/portal/catalog') return { data: [product] };
      if (endpoint === '/portal/orders/preview') return failPreview ? { status: 500 } : { data: {
        pricing: { items: [{ productId: 101, price: 100000, totalAmount: 80000, exceedsStock: false }], subtotalAmount: 100000, discountAmount: 20000, totalAmount: 80000 },
        debt: { currentDebt: 1000000, debtLimit: 100000000, remainingLimit: 99000000, projectedDebt: 1080000, hasOverdueDebt: false, isBlocked: false } } };
      return { data: [] };
    };
    const portal = await setup(['ROLE_DEALER'], ['ORDER_VIEW', 'ORDER_CREATE', 'PRODUCT_VIEW'], portalData);
    await portal.page.goto(base + '/dealer-portal');
    await portal.page.waitForFunction(() => window.DealerPortal?.state.currentDealer && window.DealerPortal?.state.debtInfo);
    await portal.page.waitForFunction(() => !document.querySelector('#sidebar-nav').inert);
    assert.equal(await portal.page.locator('#sidebar').count(), 1);
    assert.equal(await portal.page.locator('#sidebar').isVisible(), true);
    assert.equal(await portal.page.locator('#sidebar-nav [aria-current="page"]').getAttribute('data-page'), 'dealer-portal');
    assert.equal(await portal.page.locator('#btn-open-cart').count(), 1);
    assert.equal(await portal.page.locator('#sidebar-nav [data-page="users"]').count(), 0, 'Dealer must not receive admin menu items');
    await portal.page.screenshot({ path: path.join(output, 'portal-shell-desktop.png') });
    await portal.page.evaluate(product => {
      DealerPortal.addToCart(product, 'lon', 1, 100000, 1);
    }, product);
    await portal.page.locator('#btn-open-cart').click();
    await portal.page.waitForFunction(() => !document.getElementById('btn-submit-order').disabled);
    assert.match(await portal.page.locator('#dp-discount-val').innerText(), /20[.\s]000/);
    assert.match(await portal.page.locator('#dp-total-val').innerText(), /80[.\s]000/);
    await portal.page.screenshot({ path: path.join(output, 'portal-desktop.png') });
    await portal.page.setViewportSize({ width: 390, height: 844 });
    assert.ok(await portal.page.locator('#dp-total-val').isVisible());
    await portal.page.screenshot({ path: path.join(output, 'portal-mobile.png') });
    await portal.page.locator('#btn-close-cart').click();
    await portal.page.locator('#sidebar-toggle').click();
    assert.equal(await portal.page.locator('#sidebar').evaluate(sidebar => sidebar.classList.contains('sidebar--open')), true);
    await portal.page.waitForFunction(() => Math.abs(document.querySelector('#sidebar').getBoundingClientRect().left) < 1);
    assert.equal(await portal.page.locator('.main-area').evaluate(main => main.inert), true);
    assert.equal(await portal.page.locator('#dp-mobile-bottom-bar').evaluate(bar => bar.closest('.main-area').inert), true);
    await portal.page.screenshot({ path: path.join(output, 'portal-shell-mobile-menu.png') });
    await portal.page.locator('#sidebar-close').click();
    await portal.page.locator('#btn-mobile-view-cart').click();
    assert.equal(await portal.page.locator('#dp-cart-drawer').isVisible(), true);
    await portal.page.waitForFunction(() => !window.DealerPortal.state.previewRequest);
    failPreview = true;
    await portal.page.evaluate(() => DealerPortal.refreshCartPreview(true));
    assert.equal(await portal.page.locator('#btn-submit-order').isDisabled(), true);
    assert.equal(await portal.page.locator('#btn-refresh-cart-preview').isVisible(), true);
    portal.rights.permissions = ['ORDER_VIEW', 'PRODUCT_VIEW'];
    await portal.page.evaluate(() => Auth.syncPermissions());
    await portal.page.waitForURL(base + '/error?status=403');
    await portal.context.close();

    failPreview = false;
    const admin = await setup(['ROLE_ADMIN'], [], portalData);
    await admin.page.goto(base + '/dealer-portal');
    await admin.page.waitForFunction(() => !document.querySelector('#sidebar-nav').inert);
    await admin.page.locator('#dp-dealer-select option[value="10"]').waitFor({ state: 'attached' });
    assert.equal(await admin.page.locator('#sidebar-nav [data-page="users"]').count(), 1);
    assert.equal(await admin.page.locator('#topbar-dealer-name').innerText(), 'Chọn đại lý');
    await admin.page.locator('#dp-dealer-select').selectOption('10');
    await admin.page.waitForURL(base + '/dealer-portal?dealerId=10');
    await admin.page.waitForFunction(() => window.DealerPortal?.state.catalog.length === 1);
    assert.equal(await admin.page.locator('#topbar-dealer-name').innerText(), 'Đại lý kiểm thử');
    assert.equal(await admin.page.locator('#btn-open-cart').count(), 1);
    await admin.page.screenshot({ path: path.join(output, 'portal-shell-admin.png') });
    await admin.context.close();

    for (const kind of ['confirm', 'lifecycle']) {
      const permission = kind === 'confirm' ? 'ORDER_CONFIRM' : 'WAREHOUSE_EXPORT';
      let changed = false;
      let order = { id: 1, orderCode: 'DH-KIEM-THU', status: kind === 'confirm' ? 'PENDING_APPROVAL' : 'RECORDED',
        totalAmount: 80000, dealerId: 10, dealerName: 'Đại lý kiểm thử', createdAt: '2026-10-20T09:00:00',
        items: [{ productName: product.name, productCode: product.sku, unit: 'lon', quantity: 1, price: 100000, totalAmount: 80000 }],
        canConfirmPortalOrder: kind === 'confirm', nextLifecycleStatus: kind === 'lifecycle' ? 'PICKING' : null };
      const screen = await setup(['ROLE_VIEWER'], ['ORDER_VIEW', permission], async (endpoint, request) => {
        if (endpoint === '/orders/1/confirm' || endpoint === '/orders/1/status') {
          if (request.method() === 'PUT') {
            changed = true;
            if (kind === 'lifecycle') assert.equal(request.postDataJSON().status, 'PICKING');
            order = { ...order, status: kind === 'confirm' ? 'RECORDED' : 'PICKING',
              canConfirmPortalOrder: false, nextLifecycleStatus: kind === 'lifecycle' ? 'EXPORTED' : null };
          }
          return { data: order };
        }
        if (endpoint === '/orders') return { data: [order] };
        if (endpoint === '/orders/1') return { data: order };
        if (endpoint === '/orders/1/tracking') return { data: { status: order.status, steps: [], history: [] } };
        if (endpoint === '/dealers/options') return { data: { territories: [], assignedUsers: [] } };
        return { data: [] };
      });
      await screen.page.goto(base + '/orders');
      await screen.page.locator('[data-action="view"][data-id="1"]').first().click();
      const button = screen.page.locator(kind === 'confirm' ? '#btn-detail-confirm-portal' : '#btn-detail-next-status');
      await button.waitFor({ state: 'visible' });
      await button.click();
      await screen.page.waitForFunction(() => document.querySelector('#toast-container .toast'));
      assert.equal(changed, true);
      if (kind === 'confirm') {
        order = { ...order, status: 'PENDING_APPROVAL', canConfirmPortalOrder: true };
        await screen.page.reload();
        await screen.page.locator('[data-action="view"][data-id="1"]').first().click();
      }
      await button.waitFor({ state: 'visible' });
      screen.rights.permissions = ['ORDER_VIEW'];
      await screen.page.evaluate(() => Auth.syncPermissions());
      assert.equal(await button.isVisible(), false);
      assert.equal(await screen.page.locator('#order-detail-modal').isVisible(), false);
      await screen.context.close();
    }
    assert.deepEqual(errors, []);
    console.log(JSON.stringify({ passed: true, checked: ['server discount', 'preview failure', 'desktop/mobile', 'confirmation', 'lifecycle', 'live permission revocation'], output }));
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
