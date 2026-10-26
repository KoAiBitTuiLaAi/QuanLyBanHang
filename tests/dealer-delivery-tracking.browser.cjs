// Chrome runs real frontend scripts with isolated GET fixtures; never touches the business database.
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const {chromium}=require('playwright');
const base=process.env.NAV_TEST_URL||'http://127.0.0.1:5529';
const output=path.join(require('node:os').tmpdir(),'qlbh-s609');
const pause=ms=>new Promise(r=>setTimeout(r,ms));
const token='test.'+Buffer.from(JSON.stringify({exp:Math.floor(Date.now()/1000)+3600})).toString('base64url')+'.test';
(async()=>{
  fs.mkdirSync(output,{recursive:true});
  const browser=await chromium.launch({headless:true,channel:'chrome'});
  const errors=[],unexpected=[];
  async function setup(admin=false) {
    const roles=admin?['ROLE_ADMIN']:['ROLE_DEALER'];
    const rights={permissions:admin?[]:['ORDER_VIEW']};
    const user={id:10,username:'s609-fixture',fullName:'Đại lý kiểm thử',roles,permissions:rights.permissions};
    const order={id:55,dealerId:10,orderCode:'DH-S609-55',status:'EXPORTED',backordered:true,
      approvedAt:'2026-10-23T09:12:45',expectedDeliveryDate:'2026-10-25',totalAmount:120000,
      items:[{id:1,productName:'Hàng chưa cập nhật',productCode:'SKU1',quantity:10,unit:'lon',deliveredQuantity:null},
        {id:2,productName:'Hàng chưa giao',quantity:5,unit:'lon',deliveredQuantity:0,isShortage:true,shortageQuantity:0},
        {id:3,productName:'Hàng đã giao một phần',quantity:5,unit:'kg',deliveredQuantity:2.5,
          isShortage:true,shortageQuantity:2.5,deliveryNote:'<img src=x onerror=alert(1)>'}]};
    const state={order,calls:[],delay:0,detailStatus:200};
    const context=await browser.newContext({viewport:{width:1440,height:1000},reducedMotion:'reduce'});
    await context.addInitScript(({user,token})=>{
      sessionStorage.setItem('user',JSON.stringify(user)); sessionStorage.setItem('accessToken',token);
    },{user,token});
    await context.route('http://localhost:8080/api/**',async route=>{
      const request=route.request(),url=new URL(request.url()),endpoint=url.pathname.slice(4),method=request.method();
      const headers={'Access-Control-Allow-Origin':base,'Access-Control-Allow-Headers':'Authorization,Content-Type',
        'Access-Control-Allow-Methods':'GET,OPTIONS'};
      if(method==='OPTIONS')return route.fulfill({status:204,headers});
      state.calls.push({endpoint,method,query:url.search});
      let data,status=200,message='Thành công';
      if(method!=='GET') {unexpected.push({endpoint,method});status=405;}
      else if(endpoint==='/auth/permissions')data={roles,permissions:rights.permissions};
      else if(endpoint==='/portal/dealers'&&admin)data=[{id:10,code:'DL10',name:'Đại lý 10'}];
      else if(endpoint==='/portal/orders')data=[order];
      else if(endpoint==='/portal/orders/55'){
        data=JSON.parse(JSON.stringify(order));status=state.detailStatus;
        message=status===403?'Không có quyền xem đơn hàng này':status===500?'Không thể tải dữ liệu kiểm thử':'Thành công';
        if(state.delay)await pause(state.delay);
      } else {unexpected.push({endpoint,method});status=404;}
      await route.fulfill({status,headers,contentType:'application/json',body:JSON.stringify({success:status===200,message,data})}).catch(()=>{});
    });
    const page=await context.newPage();
    page.on('pageerror',e=>errors.push(e.message));
    const setRights=async permissions=>{rights.permissions=permissions;await page.evaluate(async()=>{await Auth.refreshPermissions();});};
    return {context,page,state,rights,setRights};
  }
  try {
    const d=await setup();
    await d.page.goto(base+'/dealer-orders');
    const action=d.page.locator('[data-track-order-id="55"]');
    await action.click();
    const milestones=d.page.locator('[data-delivery-milestone]');
    await milestones.first().waitFor();
    assert.deepEqual(await milestones.locator('strong').allTextContents(),['Đã duyệt','Đang soạn','Đã xuất','Đang giao','Đã giao']);
    assert.match(await d.page.locator('[data-delivery-milestone="exported"]').textContent(),/Trạng thái hiện tại/);
    assert.match(await d.page.locator('[data-delivery-milestone="shipping"]').textContent(),/Chưa có dữ liệu/);
    assert.match(await d.page.locator('.dealer-delivery-tracking__warning').textContent(),/còn nợ hàng/);
    const rows=d.page.locator('.dealer-order-items tbody tr');
    assert.equal(await rows.nth(0).locator('td').nth(3).textContent(),'Chưa cập nhật');
    assert.equal(await rows.nth(1).locator('td').nth(3).textContent(),'0');
    assert.match(await rows.nth(2).locator('td').nth(3).textContent(),/2,5/);
    assert.equal(await d.page.locator('#dealer-order-detail-content img').count(),0);
    assert.equal(await d.page.locator('#btn-reorder-from-detail').isHidden(),true);
    assert.equal(await d.page.evaluate(()=>document.activeElement.id),'dealer-delivery-tracking-title');
    await d.page.keyboard.press('Shift+Tab');
    assert.equal(await d.page.evaluate(()=>document.activeElement.id),'btn-refresh-dealer-delivery');
    await d.page.keyboard.press('Tab');
    assert.equal(await d.page.evaluate(()=>document.activeElement.id),'close-dealer-order-detail');
    for(const width of [1920,1440,1024,768,390,320]){
      await d.page.setViewportSize({width,height:1000});
      await d.page.evaluate(()=>new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r))));
      assert.ok(await d.page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),`page overflow ${width}`);
      assert.ok(await d.page.locator('.dealer-order-detail').evaluate(el=>el.scrollWidth<=el.clientWidth+1),`dialog overflow ${width}`);
      const wrapper=d.page.locator('.dealer-order-items-wrap');
      assert.equal(await wrapper.evaluate(el=>getComputedStyle(el).overflowX),'auto');
      if([1440,390].includes(width))await d.page.screenshot({path:path.join(output,`tracking-${width}.png`),fullPage:true});
    }
    await d.page.setViewportSize({width:1440,height:1000});
    d.state.order.status='DELIVERED';d.state.order.actualDeliveryDate='2026-10-23';d.state.order.backordered=false;
    await d.page.locator('#btn-refresh-dealer-delivery').click();
    await d.page.locator('[data-delivery-milestone="delivered"] time').waitFor();
    assert.doesNotMatch(await d.page.locator('[data-delivery-milestone="delivered"] time').textContent(),/00:00/);
    assert.match(await d.page.locator('[data-delivery-milestone="picking"]').textContent(),/Chưa có dữ liệu/);
    d.state.order.pickingAt='2026-10-23T09:30:00';d.state.order.exportedAt='2026-10-23T10:00:00';d.state.order.shippingAt='2026-10-23T11:00:00';
    d.state.order.deliveryMilestones=[{milestoneKey:'picking',timestamp:'2026-10-23T09:35:00',status:'COMPLETED'}];
    await d.page.locator('#btn-refresh-dealer-delivery').click();
    await d.page.locator('[data-delivery-milestone="picking"] time').waitFor();
    assert.equal(await d.page.locator('[data-delivery-milestone="picking"] time').getAttribute('datetime'),'2026-10-23T09:35:00');
    assert.equal(await d.page.locator('[data-delivery-milestone="shipping"] time').getAttribute('datetime'),'2026-10-23T11:00:00');
    d.state.order.status='SHIPPING';d.state.order.deliveredAt='2026-10-23T12:00:00';
    await d.page.locator('#btn-refresh-dealer-delivery').click();
    await d.page.locator('[data-delivery-milestone="shipping"]').getByText('Trạng thái hiện tại').waitFor();
    assert.equal(await d.page.locator('[data-delivery-milestone="delivered"] time').count(),0);
    const originalItems=d.state.order.items;
    d.state.order.items=null;
    await d.page.locator('#btn-refresh-dealer-delivery').click();
    await d.page.locator('.dealer-order-items').getByText('Chưa có dữ liệu dòng hàng.',{exact:true}).waitFor();
    d.state.order.items=originalItems;
    d.state.detailStatus=500;
    await d.page.locator('#btn-refresh-dealer-delivery').click();
    await d.page.locator('.dealer-orders-empty').getByText('Không thể tải dữ liệu kiểm thử',{exact:true}).waitFor();
    assert.equal(await milestones.count(),0,'error clears stale milestones');
    d.state.detailStatus=200;
    await d.page.locator('#btn-refresh-dealer-delivery').click();
    await milestones.first().waitFor();
    await d.page.keyboard.press('Escape');
    assert.equal(await d.page.locator('#dealer-order-detail-modal').isHidden(),true);
    assert.equal(await action.evaluate(el=>el===document.activeElement),true,'return keyboard focus');
    // Revocation during an outstanding request must not resurrect order data.
    d.state.delay=350;
    await action.click();
    await d.setRights([]);
    await d.page.waitForURL('**/error?status=403');
    await pause(500);
    assert.equal(await d.page.locator('#dealer-order-detail-modal').isHidden(),true);
    assert.equal(await d.page.locator('[data-delivery-milestone]').count(),0);
    assert.equal(await d.page.locator('[data-track-order-id]').count(),0);
    d.state.delay=0;
    d.rights.permissions=['ORDER_VIEW'];
    await d.page.goto(base+'/dealer-orders');
    await action.waitFor();
    // A server-side 403 is authoritative even if the old UI session still grants ORDER_VIEW.
    d.state.detailStatus=403;
    await action.click();
    await d.page.waitForURL('**/error?status=403');
    assert.equal(await milestones.count(),0);
    assert.ok(!d.state.calls.some(c=>c.endpoint.includes('/tracking')||c.endpoint.startsWith('/warehouse')));
    assert.ok(d.state.calls.every(c=>c.method==='GET'));
    await d.context.close();
    const a=await setup(true);
    await a.page.goto(base+'/dealer-orders?dealerId=10');
    await a.page.locator('[data-track-order-id="55"]').click();
    await a.page.locator('[data-delivery-milestone]').first().waitFor();
    assert.ok(a.state.calls.filter(c=>c.endpoint.startsWith('/portal/orders')).every(c=>c.query==='?dealerId=10'));
    assert.equal(await a.page.locator('#btn-refresh-dealer-delivery').isEnabled(),true,'ADMIN needs no explicit permission array');
    assert.equal(await a.page.locator('#btn-reorder-from-detail').isVisible(),true,'detail reorder remains visible with ORDER_CREATE/ADMIN');
    await a.context.close();
    assert.deepEqual(errors,[]);assert.deepEqual(unexpected,[]);
    console.log('S6-09 Chrome PASS: dealer ORDER_VIEW only, ADMIN scoped dealer, five honest milestones, actual quantities/XSS, refresh/error/403, grant/revoke + stale requests, keyboard focus, six viewports 320–1920px. API fixtures GET-only; no DB writes.');
  } finally {await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
