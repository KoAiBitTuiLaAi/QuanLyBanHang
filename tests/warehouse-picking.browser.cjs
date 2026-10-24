// Actual Chrome, production page/scripts, intercepted HTTP fixtures only. No real DB writes.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright');
const base = process.env.NAV_TEST_URL || 'http://127.0.0.1:5542';
const output = path.join(require('node:os').tmpdir(), 'qlbh-s603');
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
const token = 'test.' + Buffer.from(JSON.stringify({ exp: Date.now() / 1000 + 3600 })).toString('base64url') + '.test';

(async () => {
  fs.mkdirSync(output, { recursive: true });
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  const errors = [], unexpected = [], calls = [];
  const state = { rights: ['WAREHOUSE_EXPORT'], batch: null, delayDetail: false, delaySave: false, failSave: false, conflict: false, failList: false };
  const statuses = [{ value:'IN_PROGRESS',label:'Đang soạn' }, {value:'COMPLETED',label:'Hoàn tất'}, {value:'CANCELLED',label:'Đã hủy'}];
  const sourceOrders = Array.from({ length: 21 }, (_, i) => ({ id: 101 + i, orderCode: 'DH-' + (101 + i), customerName:'Khách kiểm thử', status:'RECORDED', expectedDeliveryDate:'2026-10-25', lineCount:2 }));
  function paged(rows, page = 0, size = 20) { return { content: rows.slice(page * size, (page + 1) * size), totalElements:rows.length, totalPages:Math.ceil(rows.length / size), number:page }; }
  function result() {
    const batch = structuredClone(state.batch); if (!batch) return batch;
    batch.summary.checkedCount = batch.items.filter(item => item.checked).length;
    batch.summary.shortageCount = batch.items.filter(item => item.pickedQuantity != null && item.pickedQuantity < item.quantity).length;
    batch.summary.statusLabel = statuses.find(status => status.value === batch.summary.status).label;
    batch.canEdit = batch.summary.status === 'IN_PROGRESS' && state.rights.includes('WAREHOUSE_EXPORT');
    batch.canCancel = batch.canEdit; batch.canComplete = batch.canEdit && batch.items.every(item => item.checked);
    return batch;
  }
  const context = await browser.newContext({ viewport:{width:1440,height:1000}, reducedMotion:'reduce' });
  await context.addInitScript(({token}) => { sessionStorage.setItem('accessToken', token);
    sessionStorage.setItem('user', JSON.stringify({id:7,username:'picking-test',fullName:'Người soạn kiểm thử',roles:['ROLE_VIEWER'],permissions:['WAREHOUSE_EXPORT']})); }, {token});
  await context.route('http://localhost:8080/api/**', async route => {
    const request = route.request(), url = new URL(request.url()), endpoint = url.pathname.slice(4), method = request.method();
    const headers = {'Access-Control-Allow-Origin':base,'Access-Control-Allow-Headers':'Authorization,Content-Type','Access-Control-Allow-Methods':'GET,POST,PUT,DELETE,OPTIONS'};
    if (method === 'OPTIONS') return route.fulfill({ status:204, headers });
    const body = request.postData() ? request.postDataJSON() : null; calls.push({ endpoint, method, body });
    let data, status = 200, message = 'Thành công';
    if (endpoint === '/auth/permissions') data = {roles:['ROLE_VIEWER'],permissions:state.rights};
    else if (endpoint === '/warehouse/picking-batches/options') data = { warehouses:[{id:1,code:'KHO-HN',name:'Kho Hà Nội'}], statuses,
      canCreate:state.rights.includes('WAREHOUSE_EXPORT'),maxOrders:100,maxLines:500,quantityScale:4,routeStrategy:'Đi theo Khu A > Kệ 2 > Kệ 10; chưa gán vị trí xếp cuối' };
    else if (endpoint === '/warehouse/picking-batches/eligible-orders') {
      const filtered = sourceOrders.filter(order => !url.searchParams.get('keyword') || order.orderCode.includes(url.searchParams.get('keyword')));
      data = paged(filtered, Number(url.searchParams.get('page') || 0));
    } else if (endpoint === '/warehouse/picking-batches' && method === 'GET') {
      if (state.failList) {status=500; message='Không tải được danh sách kiểm thử';}
      else data = paged(state.batch && (!url.searchParams.get('status') || url.searchParams.get('status') === state.batch.summary.status) ? [result().summary] : []);
    } else if (endpoint === '/warehouse/picking-batches' && method === 'POST') {
      await pause(160);
      state.batch = { summary:{ id:1,version:0,code:'PSH-TEST',warehouseId:1,warehouseCode:'KHO-HN',warehouseName:'Kho Hà Nội',status:'IN_PROGRESS',
        orderCount:body.orderIds.length,lineCount:3,checkedCount:0,shortageCount:0,createdAt:'2026-10-23T10:00:00',createdBy:'picking-test' },note:body.note,
        items: [
          {id:1,orderId:101,orderCode:'DH-101',productId:1,productCode:'SKU-1',productName:'<img src=x onerror=alert(1)>',unit:'thùng',baseUnit:'lon',quantity:2,conversionFactor:24,locationId:2,locationCode:'KE-2',locationDisplay:'Khu A > Kệ 2',routePosition:1},
          {id:2,orderId:121,orderCode:'DH-121',productId:2,productCode:'SKU-2',productName:'Nước ngọt',unit:'lon',baseUnit:'lon',quantity:1.5,conversionFactor:1,locationId:10,locationCode:'KE-10',locationDisplay:'Khu A > Kệ 10',routePosition:2},
          {id:3,orderId:121,orderCode:'DH-121',productId:3,productCode:'SKU-3',productName:'Hàng chưa gán kệ',unit:'lon',baseUnit:'lon',quantity:5,conversionFactor:1,locationId:null,locationCode:null,locationDisplay:'Chưa gán vị trí',routePosition:3},
        ].map(item => ({...item,pickedQuantity:null,checked:false,shortageReason:null,shortageQuantity:null})) };
      data = result();
    } else if (endpoint === '/warehouse/picking-batches/1' && method === 'GET') {
      if (state.delayDetail) await pause(350); data = result();
    } else if (/^\/warehouse\/picking-batches\/1\/items\/\d+$/.test(endpoint)) {
      // Permission was checked when this in-flight request began. A later grant
      // change must invalidate its successful response on the frontend too.
      const accepted = state.rights.includes('WAREHOUSE_EXPORT');
      await pause(state.delaySave ? 350 : 100);
      if (!accepted) {status=403; message='Quyền đã bị thu hồi';}
      else if (state.conflict || body.version !== state.batch.summary.version) {status=409; message='Phiếu đã thay đổi. Tải lại trước khi sửa';}
      else if (state.failSave) {status=400; message='Lỗi lưu kiểm thử, giữ nguyên dữ liệu nhập';}
      else { const line = state.batch.items.find(item => item.id === Number(endpoint.split('/').at(-1)));
        line.pickedQuantity=body.pickedQuantity; line.checked=body.checked; line.shortageReason=body.shortageReason;
        line.shortageQuantity=body.pickedQuantity==null?null:line.quantity-body.pickedQuantity; state.batch.summary.version++; data=result(); }
    } else if (endpoint === '/warehouse/picking-batches/1/complete') {
      state.batch.summary.status='COMPLETED'; state.batch.summary.version++; data=result();
    } else if (endpoint === '/warehouse/picking-batches/1/cancel') {
      state.batch.summary.status='CANCELLED'; state.batch.cancelReason=body.reason; state.batch.summary.version++; data=result();
    } else { status=404; unexpected.push({endpoint,method}); }
    await route.fulfill({status,headers,contentType:'application/json',body:JSON.stringify({success:status===200,message,data})}).catch(()=>{});
  });
  const page = await context.newPage();
  page.on('pageerror', error => errors.push(error.message)); page.on('dialog', dialog => dialog.accept());
  async function rights(permissions) {state.rights=permissions; await page.evaluate(async()=>await Auth.refreshPermissions());}
  try {
    await page.goto(base+'/warehouse/picking'); await page.locator('#pick-new:not([hidden])').waitFor();
    assert.ok(await page.locator('#sidebar-nav [data-page="warehouse-picking"]').count());
    await page.locator('#pick-new').click(); await page.locator('#pick-source').selectOption('1');
    await page.locator('#pick-order-keyword').fill('DH-101'); await page.locator('#pick-order-keyword').press('Enter');
    await page.locator('[data-order="101"]').waitFor();
    assert.equal(calls.filter(call=>call.method==='POST').length,0,'Enter in order search must not create a batch');
    await page.locator('#pick-order-keyword').fill(''); await page.locator('#pick-find-orders').click();
    await page.locator('[data-order="101"]').check(); await page.locator('#pick-order-next').click();
    await page.locator('[data-order="121"]').check(); assert.match(await page.locator('#pick-selected').textContent(), /2 đơn/);
    await page.locator('#pick-create-submit').evaluate(button=>{button.click();button.click();});
    await page.locator('form[data-line="1"]').waitFor();
    const creates=calls.filter(call=>call.method==='POST'&&call.endpoint==='/warehouse/picking-batches');
    assert.equal(creates.length,1); assert.deepEqual(creates[0].body.orderIds,[101,121]);
    assert.equal(await page.locator('#pick-lines img').count(),0);
    assert.equal(await page.locator('form[data-line="1"] [data-quantity]').inputValue(),'');
    assert.equal(await page.locator('#pick-complete').isDisabled(),true);
    for(const width of [1920,1440,1024,768,390,320]) {
      await page.setViewportSize({width,height:1000});
      await page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))));
      assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),`picking overflow ${width}`);
      assert.ok(await page.locator('form[data-line="1"] button').evaluate(el=>el.getBoundingClientRect().height>=44));
      if(width===1440||width===390) {await page.locator('#pick-detail').scrollIntoViewIfNeeded(); await page.screenshot({path:path.join(output,`picking-${width}.png`),fullPage:true});}
    }
    const first=page.locator('form[data-line="1"]'), second=page.locator('form[data-line="2"]');
    await first.locator('[data-quantity]').fill('0'); await first.locator('[data-checked]').check();
    let writes=calls.filter(call=>call.method==='PUT').length; await first.locator('button').click();
    await first.locator('[data-line-error]').getByText('Soạn thiếu bắt buộc nhập lý do.').waitFor();
    assert.equal(calls.filter(call=>call.method==='PUT').length,writes);
    await first.locator('[data-reason]').fill('Không có hàng tại kệ');
    state.failSave=true; await first.locator('button').click(); await page.locator('#pick-detail-error').getByText(/Lỗi lưu kiểm thử/).waitFor();
    assert.equal(await first.locator('[data-quantity]').inputValue(),'0'); assert.equal(await first.locator('[data-reason]').inputValue(),'Không có hàng tại kệ');
    state.failSave=false; await second.locator('[data-quantity]').fill('1.5'); await second.locator('[data-checked]').check();
    writes=calls.filter(call=>call.method==='PUT').length;
    await first.locator('button').evaluate(button=>{button.click();button.click();});
    await page.waitForFunction(()=>document.querySelector('#pick-progress').textContent.includes('1/3'));
    assert.equal(calls.filter(call=>call.method==='PUT').length,writes+1);
    assert.equal(await second.locator('[data-quantity]').inputValue(),'1.5','Other unsaved line input must survive save');
    assert.equal(await second.getAttribute('data-dirty'),'true');
    await second.locator('button').click(); await page.waitForFunction(()=>document.querySelector('#pick-progress').textContent.includes('2/3'));
    const third=page.locator('form[data-line="3"]'); await third.locator('[data-quantity]').fill('5'); await third.locator('[data-checked]').check();
    state.conflict=true; await third.locator('button').click(); await page.locator('#pick-detail-error').getByText(/Phiếu đã thay đổi/).waitFor();
    assert.equal(await third.locator('[data-quantity]').inputValue(),'5'); state.conflict=false;
    await third.locator('button').click(); await page.waitForFunction(()=>document.querySelector('#pick-progress').textContent.includes('3/3'));
    await page.locator('#pick-complete:not([disabled])').waitFor(); await page.locator('#pick-complete').click();
    await page.waitForFunction(()=>document.querySelector('#pick-detail-info').textContent.includes('Hoàn tất'));
    assert.equal(await page.locator('#pick-complete').isHidden(),true); assert.equal(await first.locator('[data-quantity]').isDisabled(),true);
    await page.reload(); await page.locator('[data-detail="1"]').click(); await page.locator('form[data-line="1"]').waitFor();
    assert.equal(await first.locator('[data-quantity]').inputValue(),'0');
    state.batch.summary.status='IN_PROGRESS'; state.batch.items[0].checked=false;
    await page.locator('#pick-detail-reload').click(); await first.locator('button:not([disabled])').waitFor();
    state.delaySave=true; await first.locator('[data-quantity]').fill('1'); await first.locator('button').click();
    await rights(['WAREHOUSE_VIEW']); await pause(450);
    assert.equal(await page.locator('#pick-detail').isHidden(),true); assert.equal(await page.locator('#pick-lines').textContent(),'');
    assert.equal(await page.locator('#pick-new').isHidden(),true); state.delaySave=false;
    await page.locator('[data-detail="1"]').click(); await page.locator('form[data-line="1"]').waitFor();
    assert.equal(await first.locator('button').isDisabled(),true); assert.equal(await page.locator('#pick-cancel-form').isHidden(),true);
    await rights(['WAREHOUSE_EXPORT']); state.delayDetail=true;
    await page.locator('[data-detail="1"]').click(); await rights(['WAREHOUSE_VIEW']); await pause(450);
    assert.equal(await page.locator('#pick-detail').isHidden(),true); assert.equal(await page.locator('#pick-lines').textContent(),''); state.delayDetail=false;
    await rights(['WAREHOUSE_EXPORT']); await page.locator('[data-detail="1"]').click(); await first.locator('button:not([disabled])').waitFor();
    await page.locator('#pick-cancel-reason').fill('Đổi tuyến soạn'); await page.locator('#pick-cancel').click();
    await page.waitForFunction(()=>document.querySelector('#pick-detail-info').textContent.includes('Đã hủy'));
    state.failList=true; await page.locator('#pick-reload').click(); await page.locator('#pick-error').getByText(/Không tải được danh sách kiểm thử/).waitFor();
    state.failList=false; await page.locator('#pick-reload').click(); await page.locator('[data-detail="1"]').waitFor();
    await page.locator('#pick-filter-warehouse').selectOption('1'); await page.locator('#pick-status').selectOption('COMPLETED');
    await page.locator('#pick-filter button[type="submit"]').click(); await page.locator('#pick-list').getByText('Không có lượt soạn phù hợp.').waitFor();
    await page.locator('#pick-reset').click(); await page.locator('[data-detail="1"]').waitFor();
    await rights([]); await page.waitForURL(/error\?status=403/);
    assert.deepEqual(errors,[]); assert.deepEqual(unexpected,[]);
    console.log(JSON.stringify({passed:true,output,cases:'multi-order/paged selection, ordered stops, zero/shortage reason, input preservation, duplicate prevention, optimistic conflict, completion/reload/cancel, view-only, live grant/revoke and late reads/writes, XSS, retry/filter/empty, six widths'}));
  } finally {await context.close();await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
