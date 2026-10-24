// Actual Chrome/page scripts, HTTP fixtures only. Never writes to the user's database.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright');
const base = process.env.NAV_TEST_URL || 'http://127.0.0.1:5541';
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
const token = 'test.' + Buffer.from(JSON.stringify({ exp: Math.floor(Date.now() / 1000) + 3600 })).toString('base64url') + '.test';
const output = path.join(require('node:os').tmpdir(), 'qlbh-s5-fix');

(async () => {
  fs.mkdirSync(output, { recursive: true });
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  const errors = [], unexpected = [];
  async function setup(dealer) {
    const rights = { permissions: ['ORDER_VIEW', 'ORDER_CREATE', 'WAREHOUSE_EXPORT'] };
    const roles = dealer ? ['ROLE_DEALER'] : ['ROLE_VIEWER'];
    const user = { id: 12, username: 's5-fixture', fullName: 'S5 fixture', roles, permissions: rights.permissions };
    const order = { id: 55, orderCode: 'DH-S5-55', dealerId: 10, customerName: 'Đại lý kiểm thử',
      status: 'EXPORTED', nextLifecycleStatus: 'DELIVERED', canUpdateDelivery: true,
      totalAmount: 200, warehouseId: 1, createdAt: '2026-10-22T10:00:00',
      items: [{ id: 101, productId: 1, productCode: 'SKU1', productName: 'Hàng kiểm thử', unit: 'lon', quantity: 10, price: 20, totalAmount: 200 }] };
    const state = { calls: [], order, delayPreview: false, failDelivery: false, delayDelivery: false, delayDetail: false };
    const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, reducedMotion: 'reduce' });
    await context.addInitScript(({ user, token }) => {
      sessionStorage.setItem('user', JSON.stringify(user)); sessionStorage.setItem('accessToken', token);
    }, { user, token });
    await context.route('http://localhost:8080/api/**', async route => {
      const req = route.request(), endpoint = new URL(req.url()).pathname.slice(4), method = req.method();
      const headers = { 'Access-Control-Allow-Origin': base, 'Access-Control-Allow-Headers': 'Authorization,Content-Type',
        'Access-Control-Allow-Methods': 'GET,POST,PUT,DELETE,OPTIONS' };
      if (method === 'OPTIONS') return route.fulfill({ status: 204, headers });
      const body = req.postData() ? req.postDataJSON() : null;
      state.calls.push({ endpoint, method, body });
      let data, status = 200, message = 'Thành công';
      if (endpoint === '/auth/permissions') data = { roles, permissions: rights.permissions };
      else if (endpoint === '/portal/orders') data = [order];
      else if (endpoint === '/orders') data = [order];
      else if (endpoint === '/dealers/options') data = {};
      else if (endpoint === '/dealers') data = [{ id: 10, name: 'Đại lý kiểm thử' }];
      else if (endpoint === '/portal/orders/55/reorder-preview') {
        if (state.delayPreview) await pause(350);
        data = { sourceOrderId: 55, sourceOrderCode: order.orderCode, defaultExpectedDeliveryDate: '2026-10-25',
          availableItems: [{ ...order.items[0], sourceItemId: 101, currentPrice: 25 }], discontinuedItems: [], canReorder: true };
      } else if (endpoint === '/portal/orders/55/reorder') {
        await pause(160);
        data = { order: { id: 56, orderCode: 'DH-56' }, excludedItems: [], warnings: ['Giá hiện hành được kiểm tra lại.'] };
      } else if (endpoint === '/orders/55/delivery') {
        await pause(state.delayDelivery ? 350 : 100);
        if (state.failDelivery) { status = 400; message = 'Không lưu được thực giao kiểm thử'; }
        else {
          order.status = 'DELIVERED'; order.nextLifecycleStatus = 'CLOSED';
          order.actualDeliveryDate = body.actualDeliveryDate; order.deliveryNote = body.deliveryNote;
          Object.assign(order.items[0], { deliveredQuantity: body.items[0].deliveredQuantity, deliveryNote: body.items[0].note });
          order.hasShortage = body.items[0].deliveredQuantity < 10;
          data = order;
        }
      } else if (endpoint === '/orders/55/tracking') data = { currentStatus: order.status, steps: [], history: [] };
      else if (endpoint === '/orders/55' || endpoint === '/portal/orders/55') {
        if (state.delayDetail) await pause(350);
        data = { ...order, canUpdateDelivery: rights.permissions.includes('WAREHOUSE_EXPORT') && order.canUpdateDelivery };
      } else { status = 404; unexpected.push({ endpoint, method }); }
      await route.fulfill({ status, headers, contentType: 'application/json',
        body: JSON.stringify({ success: status === 200, message, data }) }).catch(() => {});
    });
    const page = await context.newPage();
    page.on('pageerror', error => errors.push(error.message));
    async function setRights(permissions) {
      rights.permissions = permissions;
      await page.evaluate(async () => { await Auth.refreshPermissions(); });
    }
    return { context, page, state, setRights };
  }
  try {
    const d = await setup(true);
    await d.page.goto(base + '/dealer-orders');
    await d.page.locator('#dealer-orders-body').getByText('Đã xuất kho', { exact: true }).waitFor();
    await d.page.locator('[data-reorder-id="55"]').click();
    await d.page.locator('.reorder-qty-input').waitFor();
    assert.equal(await d.page.locator('#reorder-delivery-date').inputValue(), '2026-10-25');
    // No PRODUCT_VIEW grant/catalog request is necessary for the dedicated preview.
    assert.ok(!d.state.calls.some(call => call.endpoint === '/portal/catalog'));
    await d.setRights(['ORDER_VIEW']);
    await d.page.locator('#dealer-reorder-modal').waitFor({ state: 'hidden' });
    assert.equal(await d.page.locator('[data-reorder-id]').count(), 0);
    assert.equal(await d.page.locator('#btn-confirm-reorder').isDisabled(), true);
    await d.setRights(['ORDER_VIEW', 'ORDER_CREATE']);
    d.state.delayPreview = true;
    await d.page.locator('[data-reorder-id="55"]').click();
    await d.setRights(['ORDER_VIEW']);
    await pause(450);
    assert.equal(await d.page.locator('#dealer-reorder-modal').isHidden(), true);
    assert.equal(await d.page.locator('#dealer-reorder-tbody').textContent(), '');
    d.state.delayPreview = false;
    await d.setRights(['ORDER_VIEW', 'ORDER_CREATE']);
    await d.page.locator('[data-reorder-id="55"]').click();
    await d.page.locator('.reorder-qty-input').waitFor();
    await d.page.locator('.reorder-qty-input').fill('2.5');
    await d.page.locator('.reorder-qty-input').dispatchEvent('change');
    await d.page.locator('#btn-confirm-reorder').evaluate(button => { button.click(); button.click(); });
    await d.page.locator('#dealer-reorder-modal').waitFor({ state: 'hidden' });
    const writes = d.state.calls.filter(call => call.method === 'POST');
    assert.equal(writes.length, 1);
    assert.equal(writes[0].endpoint, '/portal/orders/55/reorder');
    assert.equal(writes[0].body.items[0].quantity, 2.5);
    assert.ok(!('price' in writes[0].body.items[0]));
    await d.context.close();

    const s = await setup(false);
    await s.page.goto(base + '/orders');
    await s.page.locator('[data-action="view"][data-id="55"]').first().click();
    await s.page.locator('#btn-save-delivery').waitFor();
    const qty = s.page.locator('[data-delivery-qty="101"]');
    assert.equal(await qty.inputValue(), '', 'Do not invent actual quantities');
    await s.page.locator('#btn-detail-next-status').click();
    assert.ok(!s.state.calls.some(call => call.endpoint.endsWith('/status')));
    await s.page.locator('#ord-actual-delivery-date').fill('2026-10-22');
    await qty.fill('8');
    await s.page.locator('[data-delivery-note="101"]').fill('Thiếu 2 lon');
    s.state.failDelivery = true;
    await s.page.locator('#btn-save-delivery').click();
    await s.page.locator('#ord-delivery-error').waitFor();
    assert.equal(await qty.inputValue(), '8');
    assert.equal(s.state.order.status, 'EXPORTED');
    s.state.failDelivery = false;
    const before = s.state.calls.filter(call => call.endpoint.endsWith('/delivery')).length;
    await s.page.locator('#btn-save-delivery').evaluate(button => { button.click(); button.click(); });
    await s.page.waitForFunction(() => document.querySelector('#btn-detail-next-status').textContent.includes('Đóng'));
    assert.equal(s.state.calls.filter(call => call.endpoint.endsWith('/delivery')).length, before + 1);
    assert.equal(s.state.order.items[0].deliveredQuantity, 8);
    for (const width of [1440, 768, 390, 320]) {
      await s.page.setViewportSize({ width, height: 950 });
      await s.page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
      assert.ok(await s.page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), `delivery overflow ${width}`);
      if (width === 1440 || width === 390) await s.page.screenshot({ path: path.join(output, `delivery-${width}.png`), fullPage: true });
    }
    await s.setRights(['ORDER_VIEW']);
    await s.page.locator('#order-detail-modal').waitFor({ state: 'hidden' });
    await s.page.locator('[data-action="view"][data-id="55"]').first().click();
    await s.page.locator('#ord-delivery-rows tr').waitFor();
    assert.equal(await s.page.locator('#ord-delivery-actions').isHidden(), true);
    assert.equal(await s.page.locator('[data-delivery-qty="101"]').isDisabled(), true);
    assert.equal(await s.page.locator('[data-delivery-qty="101"]').inputValue(), '8');
    await s.setRights(['ORDER_VIEW', 'WAREHOUSE_EXPORT']);
    await s.page.locator('[data-action="view"][data-id="55"]').first().click();
    await s.page.locator('#btn-save-delivery').waitFor();
    s.state.delayDelivery = true;
    await s.page.locator('#btn-save-delivery').click();
    await s.setRights(['ORDER_VIEW']);
    await pause(450);
    assert.equal(await s.page.locator('#order-detail-modal').isHidden(), true);
    assert.equal(await s.page.locator('#ord-delivery-rows').textContent(), '');
    await s.setRights(['ORDER_VIEW', 'WAREHOUSE_EXPORT']);
    s.state.delayDetail = true;
    await s.page.locator('[data-action="view"][data-id="55"]').first().click();
    await s.setRights(['ORDER_VIEW']);
    await pause(450);
    assert.equal(await s.page.locator('#order-detail-modal').isHidden(), true);
    assert.equal(await s.page.locator('#ord-delivery-rows').textContent(), '');
    await s.context.close();
    // S6-05: both full-export and backorder actions navigate to the dedicated form,
    // never write a guessed full quantity through the legacy status endpoint.
    const shipment = await setup(false);
    await shipment.page.route(base + '/warehouse/order-shipments?orderId=55', route => route.fulfill({contentType:'text/html',body:'<title>Shipment destination fixture</title>'}));
    for (const backordered of [false, true]) {
      shipment.state.order.status = backordered ? 'EXPORTED' : 'PICKING';
      shipment.state.order.backordered = backordered;
      shipment.state.order.nextLifecycleStatus = backordered ? null : 'EXPORTED';
      shipment.state.order.canUpdateDelivery = false;
      await shipment.page.goto(base + '/orders');
      await shipment.page.locator('[data-action="view"][data-id="55"]').first().click();
      await shipment.page.locator('#btn-detail-next-status').getByText(backordered ? 'Xuất bù hàng còn nợ' : 'Lập phiếu xuất kho',{exact:true}).waitFor();
      if (backordered) assert.match(await shipment.page.locator('#ord-detail-status-badge').textContent(),/Còn nợ hàng/);
      await shipment.page.locator('#btn-detail-next-status').click();
      await shipment.page.waitForURL(base + '/warehouse/order-shipments?orderId=55');
    }
    assert.ok(!shipment.state.calls.some(call => call.endpoint.endsWith('/status')));
    await shipment.context.close();
    assert.deepEqual(errors, []); assert.deepEqual(unexpected, []);
    console.log('S5-01/02 Chrome passed: actual delivery/shortage, failure preserves input, duplicate writes blocked, scoped capability, live grant/revoke and stale reads, dedicated reorder/current prices/decimal quantity; 320–1440px. Fixtures only.');
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
