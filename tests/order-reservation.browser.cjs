// Real Chrome and real page scripts, controlled HTTP fixtures only; never uses the user's DB.
const assert = require('node:assert/strict');
const { chromium } = require('playwright');
const base = process.env.NAV_TEST_URL || 'http://127.0.0.1:5538';
const token = 'test.' + Buffer.from(JSON.stringify({ exp: Math.floor(Date.now()/1000)+3600 })).toString('base64url') + '.test';
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
(async () => {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  const errors = [], unexpected = [];
  async function setup(rights) {
    const user = { id: 7, username: 'reservation-test', fullName: 'Kiểm thử giữ chỗ', roles: ['ROLE_VIEWER'], permissions: rights };
    const state = { rights, calls: [], fail: false, delayDetail: false, reserved: 30,
      orders: [
        { id: 1, orderCode: 'DH-1', status: 'PENDING_APPROVAL', inventoryReserved: false },
        { id: 2, orderCode: 'DH-2', status: 'EXPIRED', inventoryReserved: false },
        { id: 3, orderCode: 'DH-3', status: 'RECORDED', inventoryReserved: true, reservationExpiresAt: '2020-01-01T00:00:00' },
        { id: 4, orderCode: 'DH-4', status: 'RECORDED', inventoryReserved: false },
      ].map(o => ({ ...o, dealerName: 'Đại lý kiểm thử', warehouseName: 'Kho Hà Nội', createdAt: '2026-10-22T09:00:00', totalAmount: 300, items: [] })) };
    // Business tests disable animation; native transitions have their own navigation.browser.cjs suite.
    const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, reducedMotion: 'reduce' });
    await context.addInitScript(({user, token}) => { sessionStorage.setItem('user', JSON.stringify(user)); sessionStorage.setItem('accessToken', token); }, {user, token});
    await context.route('http://localhost:8080/api/**', async route => {
      const req = route.request(), endpoint = new URL(req.url()).pathname.slice(4), method = req.method();
      const headers = { 'Access-Control-Allow-Origin': base, 'Access-Control-Allow-Headers': 'Authorization,Content-Type', 'Access-Control-Allow-Methods': 'GET,POST,PATCH,PUT,DELETE,OPTIONS' };
      if (method === 'OPTIONS') return route.fulfill({status:204, headers});
      const body = req.postData() ? req.postDataJSON() : null;
      state.calls.push({endpoint, method, body});
      let data = null, status = 200, message = 'Thành công', direct;
      if (endpoint === '/auth/permissions') data = {roles:user.roles, permissions:state.rights};
      else if (endpoint === '/orders' && method === 'GET') direct = {content:state.orders, totalElements:4, totalPages:1};
      else if (/^\/orders\/\d+$/.test(endpoint)) {
        data = state.orders.find(o => o.id === Number(endpoint.split('/').at(-1)));
        if (state.delayDetail) await pause(350);
      } else if (/^\/orders\/\d+\/status$/.test(endpoint)) {
        await pause(160);
        if (state.fail) {status=400; message='Không đủ tồn khả dụng: chỉ còn 20 lon';}
        else if (!state.rights.includes('ORDER_APPROVE')) {status=403; message='Quyền đã bị thu hồi';}
        else { data=state.orders.find(o=>o.id===Number(endpoint.split('/').at(-2))); data.status='RECORDED'; data.inventoryReserved=true; }
      } else if (endpoint === '/dealers/active') data=[{id:1, code:'DL1', name:'Đại lý kiểm thử', customerGroup:'DAI_LY_CAP_1', warehouseId:1}];
      else if (endpoint === '/dealers/1/delivery-points' || endpoint === '/dealers/1/purchase-history') data=[];
      else if (endpoint === '/dealers/1/debt') data={currentDebt:0, debtLimit:1000000, remainingLimit:1000000};
      else if (endpoint === '/price-lists/effective') data={id:1, items:[{productId:10, unit:'lon', sellingPrice:10, floorPrice:9, conversionFactor:1}]};
      else if (endpoint === '/products/lookup') data=[{id:10, code:'SKU10', name:'Hàng kiểm thử', price:10, unit:'lon'}];
      else if (endpoint === '/products/10/unit-conversions') data=[{unitName:'lon', conversionFactor:1, isBaseUnit:true}];
      else if (endpoint === '/orders/draft') data=null;
      else if (endpoint === '/orders/check-debt') data={canCreateOrder:true, requiresApproval:false};
      else if (endpoint === '/orders/calculate') data={totalAmount:10, items:[{price:10, floorPrice:9, discountAmount:0}]};
      else if (endpoint === '/orders/check-inventory') data={valid:Number(body.quantity)<=100-state.reserved, physicalQuantity:100, reservedQuantity:state.reserved, availableQuantity:100-state.reserved, availableInUnit:100-state.reserved, maxAllowedInUnit:100-state.reserved, baseUnit:'lon', unit:'lon', message:'Vượt tồn khả dụng theo máy chủ'};
      else { status=404; unexpected.push({endpoint, method}); }
      await route.fulfill({status, headers, contentType:'application/json', body:JSON.stringify(direct || {success:status===200, data, message})}).catch(()=>{});
    });
    const page = await context.newPage(); page.on('pageerror', e=>errors.push(e.message));
    return {page, context, state};
  }
  try {
    const s = await setup(['ORDER_VIEW','ORDER_APPROVE']);
    await s.page.goto(base+'/order-approval'); await s.page.locator('[data-action="approve"]').waitFor();
    await s.page.locator('[data-action="detail"][data-id="1"]').click();
    await s.page.getByText('Chờ duyệt — chưa giữ chỗ', {exact:true}).waitFor();
    await s.page.locator('[data-close-order-detail]').first().click();
    for (const [id,label] of [[2,'Đã hết hạn giữ chỗ — tồn đã được nhả'],[3,'Quá hạn — chờ hệ thống nhả tồn'],[4,'Không còn giữ chỗ tồn kho']]) {
      await s.page.locator(`[data-action="detail"][data-id="${id}"]`).first().click();
      await s.page.locator('#order-detail-info').getByText(label, {exact:false}).waitFor();
      await s.page.locator('[data-close-order-detail]').first().click();
    }
    await s.page.locator('[data-action="approve"]').click(); await s.page.locator('#approve-comment').fill('Giữ ghi chú khi lỗi');
    s.state.fail=true; await s.page.locator('#btn-submit-approve').click();
    await s.page.getByText('Không đủ tồn khả dụng: chỉ còn 20 lon',{exact:true}).waitFor();
    assert.equal(await s.page.locator('#approve-comment').inputValue(),'Giữ ghi chú khi lỗi');
    s.state.fail=false;
    const before=s.state.calls.filter(c=>c.method==='PATCH').length;
    await s.page.locator('#btn-submit-approve').evaluate(btn=>{btn.click();btn.click();});
    await s.page.getByText('Đã duyệt đơn hàng và giữ chỗ tồn kho.',{exact:true}).waitFor();
    assert.equal(s.state.calls.filter(c=>c.method==='PATCH').length,before+1);
    for (const width of [1440,768,390]) {
      await s.page.setViewportSize({width,height:950});
      // Chrome applies viewport/media-query changes on the next rendering frame.
      await s.page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
      assert.ok(await s.page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),`approval overflow ${width}`);
    }
    s.state.delayDetail=true;
    await s.page.locator('[data-action="detail"][data-id="1"]').first().click();
    s.state.rights=[];
    await s.page.evaluate(()=>{const u=JSON.parse(sessionStorage.getItem('user'));u.permissions=[];sessionStorage.setItem('user',JSON.stringify(u));window.dispatchEvent(new CustomEvent('auth:permissions-changed'));});
    await pause(500);
    assert.equal(await s.page.locator('#order-detail-info').textContent().catch(()=>''),'');
    await s.context.close();

    const c=await setup(['ORDER_CREATE']);
    await c.page.goto(base+'/create-order'); await c.page.locator('#co-dealer option[value="1"]').waitFor({state:'attached'});
    await c.page.locator('#co-dealer').selectOption('1'); await c.page.locator('#co-product-search').fill('Hàng');
    await c.page.locator('.co-product-option').first().click(); await c.page.locator('.co-stock-badge').getByText('70 lon',{exact:true}).waitFor();
    const qty=c.page.locator('#co-items-body [data-field="qty"]'); await qty.fill('75'); await qty.dispatchEvent('change');
    await c.page.locator('.btn-suggest-max').waitFor(); await c.page.locator('.btn-suggest-max').click();
    assert.equal(await qty.inputValue(),'70');
    c.state.reserved=80; await qty.fill('71'); await qty.dispatchEvent('change');
    await c.page.locator('.btn-suggest-max').getByText('Đặt tối đa 20',{exact:true}).waitFor();
    assert.ok(!c.state.calls.some(call=>call.endpoint==='/orders'&&call.method==='GET'),'No partial order-list calculation or extra ORDER_VIEW dependency');
    for (const width of [1440,768,390]) {
      await c.page.setViewportSize({width,height:950});
      await c.page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
      assert.ok(await c.page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),`builder overflow ${width}`);
    }
    await c.context.close(); assert.deepEqual(errors,[]); assert.deepEqual(unexpected,[]);
    console.log('S5-06 Chrome passed: server hold/expiry truth, failed approval/input preservation, duplicate prevention, live revoke/late detail, CREATE-only live stock/max suggestion, desktop/tablet/mobile. API fixtures only.');
  } finally {await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
