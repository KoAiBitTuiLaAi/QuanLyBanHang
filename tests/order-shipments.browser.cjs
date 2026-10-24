// Real Chrome/page/scripts, intercepted API fixtures only; no production DB writes.
const assert = require('node:assert/strict');
const {chromium} = require('playwright');
const fs = require('node:fs');
const path = require('node:path');
const base = process.env.NAV_TEST_URL || 'http://127.0.0.1:5525';
const output = path.join(require('node:os').tmpdir(),'qlbh-s605');
const pause = ms => new Promise(r => setTimeout(r,ms));
const token = 'test.'+Buffer.from(JSON.stringify({exp:Date.now()/1000+3600})).toString('base64url')+'.test';
(async()=>{
  fs.mkdirSync(output,{recursive:true});const browser=await chromium.launch({channel:'chrome',headless:true});
  const state={rights:['WAREHOUSE_EXPORT'],version:0,exported:0,receipts:[],fail:false,delay:false};
  const calls=[],errors=[],unexpected=[];
  const context=await browser.newContext({viewport:{width:1440,height:1000},reducedMotion:'reduce'});
  await context.addInitScript(({token})=>{sessionStorage.setItem('accessToken',token);sessionStorage.setItem('user',JSON.stringify({id:1,username:'shipment-test',roles:['ROLE_VIEWER'],permissions:['WAREHOUSE_EXPORT']}));},{token});
  function detail(){return {id:1,version:state.version,orderCode:'DH-TEST',customerName:'Khách kiểm thử',warehouseName:'Kho Hà Nội',statusLabel:state.exported>0&&state.exported<10?'Đã xuất một phần · Còn nợ hàng':'Đã duyệt',backordered:state.exported>0&&state.exported<10,canConfirm:state.rights.includes('WAREHOUSE_EXPORT')&&state.exported<10,blockedReason:state.exported===10?'Đã xuất đủ':null,
    items:[{id:11,code:'SKU-01',name:'<img src=x onerror=alert(1)>',unit:'thùng',baseUnit:'lon',conversionFactor:24,ordered:10,exported:state.exported,remaining:10-state.exported,lotManaged:true,lots:[{lotNumber:'LOT-A',expiryDate:'2027-01-01',baseQuantity:240}]}],receipts:state.receipts};}
  await context.route('http://localhost:8080/api/**',async route=>{
    const req=route.request(),u=new URL(req.url()),p=u.pathname.slice(4),m=req.method();
    const headers={'Access-Control-Allow-Origin':base,'Access-Control-Allow-Headers':'Authorization,Content-Type','Access-Control-Allow-Methods':'GET,POST,OPTIONS'};
    if(m==='OPTIONS')return route.fulfill({status:204,headers});
    calls.push({p,m,body:req.postData()?req.postDataJSON():null});let data,status=200,message='Thành công';
    if(p==='/auth/permissions')data={roles:['ROLE_VIEWER'],permissions:state.rights};
    else if(p==='/warehouse/order-shipments/options')data={warehouses:[{id:1,code:'KHO-HN',name:'Kho Hà Nội'}],canConfirm:state.rights.includes('WAREHOUSE_EXPORT'),quantityScale:4,reservationPolicy:'Phần còn nợ tiếp tục giữ chỗ.'};
    else if(p==='/warehouse/order-shipments'&&m==='GET'){
      const q=u.searchParams.get('queue')||'READY';
      const visible=q==='READY'?state.exported===0:q==='BACKORDER'?state.exported>0&&state.exported<10:state.receipts.length>0;
      data={content:visible?[{...detail()}]:[],totalElements:visible?1:0,totalPages:visible?1:0};
    }else if(p==='/warehouse/order-shipments/1'&&m==='GET')data=detail();
    else if(p==='/warehouse/order-shipments/1/confirm'&&m==='POST'){
      const accepted=state.rights.includes('WAREHOUSE_EXPORT');if(state.delay)await pause(450);
      if(!accepted){status=403;message='Quyền bị thu hồi';}
      else if(state.fail){status=500;message='Mất kết nối khi ghi phiếu';}
      else{
        const b=req.postDataJSON();assert.equal(b.version,state.version);assert.match(b.requestKey,/^[\da-f-]{36}$/);
        const i=b.items[0];state.exported+=Number(i.quantity);state.version++;
        state.receipts.push({id:state.version,code:'PXDH-TEST-'+state.version,status:'CONFIRMED',confirmedAt:'2026-10-23T12:00:00',confirmedBy:'shipment-test',note:b.note,items:[{orderItemId:11,productCode:'SKU-01',productName:'Hàng kiểm thử',unit:'thùng',quantity:Number(i.quantity),baseQuantity:Number(i.quantity)*24,lotNumber:'LOT-A',reason:i.reason}]});data=detail();
      }
    }else{status=404;unexpected.push({p,m});}
    await route.fulfill({status,headers,contentType:'application/json',body:JSON.stringify({success:status===200,message,data})}).catch(()=>{});
  });
  const page=await context.newPage();page.on('pageerror',e=>errors.push(e.message));page.on('dialog',d=>d.accept());
  async function rights(r){state.rights=r;await page.evaluate(()=>Auth.refreshPermissions());}
  async function filter(q){await page.selectOption('#ship-queue',q);await page.locator('#ship-filter button').click();}
  async function open(){await page.locator('[data-open="1"]').click();await page.locator('#ship-confirm:not([hidden])').waitFor();}
  try{
    await page.goto(base+'/warehouse/order-shipments');await page.locator('[data-open="1"]').waitFor();
    assert.ok(await page.locator('#sidebar-nav [data-page="warehouse-order-shipments"]').count());
    await open();assert.equal(await page.locator('#ship-lines img').count(),0,'Escape server-provided strings');
    await page.locator('[data-quantity]').fill('4');await page.locator('#ship-confirm').click();
    assert.match(await page.locator('#ship-detail-error').textContent(),/lý do/);assert.equal(calls.filter(c=>c.m==='POST').length,0);
    await page.locator('[data-reason]').fill('Thiếu hàng, xuất bù sau');await page.locator('#ship-confirm').click();
    await page.locator('#ship-receipts').getByText('PXDH-TEST-1',{exact:true}).waitFor();assert.equal(state.exported,4);
    await filter('BACKORDER');await open();assert.match(await page.locator('#ship-lines').textContent(),/Còn nợ: 6/);
    await page.screenshot({path:path.join(output,'shipment-desktop.png'),fullPage:true});
    for(const width of [1440,1024,768,390,320]){
      await page.setViewportSize({width,height:960});await pause(100);
      assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),'No page overflow at '+width);
    }
    await page.screenshot({path:path.join(output,'shipment-mobile.png'),fullPage:true});
    await page.locator('#ship-confirm').click();await page.locator('#ship-receipts').getByText('PXDH-TEST-2',{exact:true}).waitFor();
    assert.equal(state.exported,10);assert.ok(await page.locator('#ship-confirm').isHidden());
    assert.equal(await page.locator('#ship-receipts button,#ship-receipts input').count(),0,'History cannot be edited');
    await filter('BACKORDER');await page.getByText('Không có đơn phù hợp.',{exact:true}).waitFor();
    await filter('HISTORY');await page.locator('[data-open="1"]').waitFor();
    state.exported=0;state.version=0;state.receipts=[];await filter('READY');await open();
    state.fail=true;await page.locator('#ship-confirm').click();await page.locator('#ship-detail-error:not([hidden])').waitFor();
    assert.ok(await page.locator('#ship-confirm').isHidden(),'Ambiguous writes require reload, not blind retry');state.fail=false;
    await page.locator('#ship-detail-reload').click();await page.locator('#ship-confirm:not([hidden])').waitFor();
    state.delay=true;await page.locator('#ship-confirm').click();await rights(['WAREHOUSE_VIEW']);await pause(650);
    assert.ok(await page.locator('#ship-detail').isHidden(),'Late successful save cannot resurrect writable detail');
    assert.ok(await page.locator('#ship-confirm').isHidden());
    await rights([]);await pause(150);assert.equal(await page.locator('#sidebar-nav [data-page="warehouse-order-shipments"]').count(),0);
    assert.ok(await page.locator('#ship-list-panel').isHidden());
    state.rights=['WAREHOUSE_VIEW'];await page.goto(base+'/warehouse/order-shipments');await page.locator('#ship-count').getByText('0 đơn',{exact:true}).waitFor();assert.ok(await page.locator('#ship-list-panel').isVisible());
    assert.equal(calls.filter(c=>c.m==='PUT'||c.m==='DELETE').length,0);assert.deepEqual(errors,[]);assert.deepEqual(unexpected,[]);
    console.log('PASS: partial/followup queues, immutable history, escaping, ambiguous saves, live revoke/late response, five responsive widths; screenshots: '+output);
  }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
