// Real Chrome/page/scripts. API fixtures only; reject every non-GET business request.
const assert=require('node:assert/strict');
const {chromium}=require('playwright');
const fs=require('node:fs'),path=require('node:path');
const base=process.env.NAV_TEST_URL || 'http://127.0.0.1:5527';
const output=path.join(require('node:os').tmpdir(),'qlbh-s607');
const pause=ms=>new Promise(r=>setTimeout(r,ms));
const token='test.'+Buffer.from(JSON.stringify({exp:Date.now()/1000+3600})).toString('base64url')+'.test';
(async()=>{
  fs.mkdirSync(output,{recursive:true});const browser=await chromium.launch({headless:true,channel:'chrome'});
  const state={rights:['WAREHOUSE_VIEW'],roles:['ROLE_VIEWER'],failOptions:false,failSummary:false,failList:false,failDetail:false,delayList:false,delaySummary:false,delayDetail:false};
  const calls=[],unexpected=[],errors=[];
  const statuses=[{value:'IN_PROGRESS',label:'Đang chạy'},{value:'COMPLETED',label:'Hoàn tất'},{value:'CANCELLED',label:'Đã hủy'}];
  const stop=(id,status,amount,returned=false)=>({id,stopOrder:id,status,statusDisplayName:{DELIVERED:'Đã giao',PENDING:'Chờ giao',FAILED:'Chưa giao được'}[status],deliveryAddress:'Địa chỉ điểm '+id,
    orderCode:'DH-'+id,receiverName:'Người nhận',receiverPhone:'0900000000',deliveryAmount:amount,returnedToWarehouse:returned,returnedAt:returned?'2026-10-23T12:00:00':null,
    failedReason:status==='FAILED'?'Khách đóng cửa <script>alert(1)</script>':null,items:[{productCode:'SKU-01',productName:'Sản phẩm kiểm thử',quantity:2,unit:'thùng',totalPrice:amount}]});
  const rows=Array.from({length:21},(_,i)=>({id:i+1,tripCode:'CG-'+String(i+1).padStart(2,'0'),warehouseId:1,warehouseCode:'KHO-HN',warehouseName:'Kho Hà Nội',driverName:i===0?'<img src=x onerror=alert(1)>':'Tài xế '+(i+1),driverPhone:'0900000000',vehiclePlate:'29C-'+(i+1),status:'IN_PROGRESS',
    departureTime:'2026-10-23T08:00:00',expectedCompletionTime:'2020-01-01T10:00:00',isOverdue:i===0,overdueMinutes:i===0?45:0,overdueWarning:i===0?'Chuyến giao đã quá thời gian dự kiến 45 phút':null,
    stops:i===0?[stop(1,'DELIVERED',100),stop(2,'FAILED',200),stop(3,'PENDING',300)]:[stop(1,'PENDING',100)]}));
  rows.push({...rows[1],id:22,tripCode:'CG-CLOSED',status:'COMPLETED',stops:[stop(1,'FAILED',400)]});
  rows.push({...rows[1],id:23,tripCode:'CG-RETURNED',stops:[stop(1,'FAILED',500,true)]});
  rows.push({...rows[1],id:99,tripCode:'CG-HCM',warehouseId:2,warehouseCode:'KHO-HCM',warehouseName:'Kho TP.HCM',stops:[stop(1,'PENDING',2000)]});
  function detail(t){const unresolved=t.stops.filter(s=>s.status!=='DELIVERED'&&!s.returnedToWarehouse);return {...t,statusDisplayName:statuses.find(s=>s.value===t.status).label,
    totalPoints:t.stops.length,deliveredPoints:t.stops.filter(s=>s.status==='DELIVERED').length,remainingPoints:unresolved.length,failedPoints:t.stops.filter(s=>s.status==='FAILED').length,
    pendingPoints:t.stops.filter(s=>s.status==='PENDING').length,totalValue:t.stops.reduce((n,s)=>n+s.deliveryAmount,0),remainingValue:unresolved.reduce((n,s)=>n+s.deliveryAmount,0)};}
  const context=await browser.newContext({viewport:{width:1440,height:1000},reducedMotion:'reduce'});
  await context.addInitScript(({token})=>{sessionStorage.setItem('accessToken',token);sessionStorage.setItem('user',JSON.stringify({id:7,username:'trip-test',roles:['ROLE_VIEWER'],permissions:['WAREHOUSE_VIEW']}));},{token});
  await context.route('http://localhost:8080/api/**',async route=>{
    const req=route.request(),u=new URL(req.url()),p=u.pathname.slice(4),method=req.method();
    const headers={'Access-Control-Allow-Origin':base,'Access-Control-Allow-Headers':'Authorization,Content-Type','Access-Control-Allow-Methods':'GET,OPTIONS'};
    if(method==='OPTIONS')return route.fulfill({status:204,headers});calls.push({p,method,query:Object.fromEntries(u.searchParams)});
    let data,status=200,message='Thành công',delay=0;
    const selected=rows.filter(t=>!u.searchParams.get('warehouseId')||String(t.warehouseId)===u.searchParams.get('warehouseId')).map(detail);
    if(method!=='GET'){status=405;unexpected.push({p,method});}
    else if(p==='/auth/permissions')data={roles:state.roles,permissions:state.rights};
    else if(p==='/warehouse/delivery-trips/options'){
      data={warehouses:[{id:1,code:'KHO-HN',name:'Kho Hà Nội'},{id:2,code:'KHO-HCM',name:'Kho TP.HCM'}],tripStatuses:statuses,stopStatuses:[],canManage:state.rights.includes('WAREHOUSE_EXPORT')};
      if(state.failOptions){status=500;message='Không tải bộ lọc';}
    }else if(p==='/warehouse/delivery-trips/summary'){
      const active=selected.filter(t=>t.status==='IN_PROGRESS'),owed=selected.filter(t=>t.remainingPoints>0),closed=owed.filter(t=>t.status!=='IN_PROGRESS');
      data={totalOutstandingValue:owed.reduce((n,t)=>n+t.remainingValue,0),totalOutstandingTrips:owed.length,closedOutstandingValue:closed.reduce((n,t)=>n+t.remainingValue,0),closedOutstandingTrips:closed.length,
        totalRunningTrips:active.length,totalValueOnRoad:active.reduce((n,t)=>n+t.remainingValue,0),totalDeliveredPoints:active.reduce((n,t)=>n+t.deliveredPoints,0),totalRemainingPoints:active.reduce((n,t)=>n+t.remainingPoints,0),totalOverdueTrips:active.filter(t=>t.isOverdue).length};
      if(state.failSummary){status=500;message='Không tải tổng hợp';}if(state.delaySummary)delay=400;
    }else if(p==='/warehouse/delivery-trips'){
      const filtered=selected.filter(t=>(!u.searchParams.get('status')||t.status===u.searchParams.get('status'))
        &&(!u.searchParams.has('isOverdue')||String(t.isOverdue)===u.searchParams.get('isOverdue'))
        &&(u.searchParams.get('outstandingOnly')!=='true'||t.remainingPoints>0)
        &&(!u.searchParams.get('keyword')||[t.tripCode,t.driverName,t.vehiclePlate].join(' ').toLowerCase().includes(u.searchParams.get('keyword').toLowerCase())));
      const page=Number(u.searchParams.get('page')||0),size=Number(u.searchParams.get('size')||20);data={content:filtered.slice(page*size,(page+1)*size),totalPages:Math.ceil(filtered.length/size),totalElements:filtered.length};
      if(state.failList){status=500;message='Không tải danh sách';}if(state.delayList)delay=450;
    }else if(/^\/warehouse\/delivery-trips\/\d+$/.test(p)){
      data=detail(rows.find(t=>String(t.id)===p.split('/').at(-1)));if(state.failDetail){status=500;message='Không tải chi tiết';}if(state.delayDetail)delay=450;
    }else{status=404;unexpected.push({p,method});}
    const body=JSON.stringify({success:status===200,message,data});if(delay)await pause(delay);
    await route.fulfill({status,headers,contentType:'application/json',body}).catch(()=>{});
  });
  const page=await context.newPage();page.on('pageerror',e=>errors.push(e.message));
  const rights=async r=>{state.rights=r;await page.evaluate(()=>Auth.refreshPermissions());};
  const apply=async()=>page.locator('#trips-filter button[type=submit]').click();
  async function ready(){await page.locator('[data-trip="1"]').waitFor();await page.waitForFunction(()=>document.getElementById('trips-outstanding-value').textContent!=='—');}
  try{
    await page.goto(base+'/warehouse/delivery-trips');await ready();
    assert.equal(await page.locator('#trips-status').inputValue(),'IN_PROGRESS');assert.equal(await page.locator('#trips-count').textContent(),'23 chuyến');
    assert.ok(await page.locator('#sidebar-nav [data-page="delivery-trips"]').count());assert.equal(await page.locator('#trips-list img').count(),0);
    const total=await page.locator('#trips-outstanding-value').textContent();assert.match(total,/4\.900/);
    await page.locator('#trips-next').click();await page.locator('[data-trip="21"]').waitFor();assert.equal(await page.locator('#trips-outstanding-value').textContent(),total,'Never sum the current page');
    await page.locator('#trips-prev').click();await page.locator('[data-trip="1"]').waitFor();
    assert.equal(await page.locator('[data-trip="2"]').locator('xpath=ancestor::article').locator('.trips-warning').count(),0,'Trust backend overdue flag, not browser date arithmetic');
    await page.locator('[data-trip="1"]').click();await page.locator('#trips-detail-title').getByText('CG-01',{exact:true}).waitFor();
    assert.equal(await page.locator('#trips-detail-body script').count(),0);assert.match(await page.locator('#trips-detail-body').textContent(),/Hàng vẫn thuộc trách nhiệm chuyến/);
    await page.screenshot({path:path.join(output,'trips-desktop.png'),fullPage:true});
    await page.screenshot({path:path.join(output,'trips-detail-desktop.png')});
    for(const width of [1920,1440,1024,768,390,320]){await page.setViewportSize({width,height:960});await pause(120);assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),'No page overflow '+width);}
    await page.screenshot({path:path.join(output,'trips-mobile.png'),fullPage:true});
    await page.locator('#trips-detail').evaluate(el=>el.scrollIntoView({block:'start'}));await pause(100);
    await page.screenshot({path:path.join(output,'trips-detail-mobile.png')});
    await page.locator('#trips-show-outstanding').click();await page.locator('#trips-count').getByText('23 chuyến',{exact:true}).waitFor();
    assert.equal(await page.locator('#trips-status').inputValue(),'');assert.ok(await page.locator('#trips-outstanding').isChecked());
    assert.ok(calls.some(c=>c.query.outstandingOnly==='true'));
    await page.locator('#trips-keyword').fill('CLOSED');await apply();await page.locator('[data-trip="22"]').click();
    await page.locator('#trips-detail-title').getByText('CG-CLOSED',{exact:true}).waitFor();assert.match(await page.locator('#trips-detail-body').textContent(),/Chuyến đã kết thúc nhưng hàng/);
    await page.locator('#trips-outstanding').uncheck();await page.locator('#trips-keyword').fill('RETURNED');await apply();await page.locator('[data-trip="23"]').click();
    await page.locator('#trips-detail-title').getByText('CG-RETURNED',{exact:true}).waitFor();assert.match(await page.locator('#trips-detail-body').textContent(),/Đã xác nhận trả kho/);
    state.failDetail=true;await page.locator('#trips-detail-refresh').click();await page.locator('#trips-detail-error:not([hidden])').waitFor();assert.equal(await page.locator('#trips-detail-body').textContent(),'');state.failDetail=false;
    state.failSummary=true;state.failList=true;await page.locator('#trips-refresh').click();await page.locator('#trips-summary-error:not([hidden])').waitFor();assert.equal(await page.locator('#trips-outstanding-value').textContent(),'—');
    await page.locator('#trips-list').getByText('Không tải được danh sách. Chọn Tải lại để thử lại.',{exact:true}).waitFor();state.failSummary=false;state.failList=false;
    await page.locator('#trips-reset').click();await ready();
    state.delayList=true;await page.locator('#trips-keyword').fill('CG-01');await apply();await pause(50);state.delayList=false;
    await page.locator('#trips-keyword').fill('CG-21');await apply();await page.locator('[data-trip="21"]').waitFor();await pause(500);assert.equal(await page.locator('[data-trip="1"]').count(),0,'Late lists cannot overwrite newer filter');
    state.delaySummary=true;await page.selectOption('#trips-warehouse','1');await apply();await pause(50);state.delaySummary=false;
    await page.selectOption('#trips-warehouse','2');await apply();await page.waitForFunction(()=>document.getElementById('trips-outstanding-value').textContent.includes('2.000'));await pause(500);
    assert.match(await page.locator('#trips-summary-scope').textContent(),/Kho TP.HCM/);assert.match(await page.locator('#trips-outstanding-value').textContent(),/2\.000/);
    await page.locator('#trips-reset').click();await ready();state.delayDetail=true;await page.locator('[data-trip="1"]').click();await page.locator('#trips-detail-close').click();await pause(500);assert.ok(await page.locator('#trips-detail').isHidden());
    await page.locator('[data-trip="1"]').click();await rights([]);await page.waitForURL('**/error?status=403');state.delayDetail=false;
    assert.equal(await page.locator('#sidebar-nav [data-page="delivery-trips"]').count(),0);
    state.rights=['WAREHOUSE_EXPORT'];await page.goto(base+'/warehouse/delivery-trips');await ready();
    state.roles=['ROLE_ADMIN'];state.rights=[];await page.evaluate(()=>Auth.refreshPermissions());await ready();
    assert.equal(calls.filter(c=>c.method!=='GET').length,0,'Monitoring is GET-only even for admin');
    state.failOptions=true;await page.locator('#trips-reset').click();await page.locator('#trips-error:not([hidden])').waitFor();assert.ok(await page.locator('#trips-warehouse').isDisabled());assert.equal(await page.locator('#trips-outstanding-value').textContent(),'—');
    state.failOptions=false;await page.locator('#trips-refresh').click();await ready();
    assert.deepEqual(errors,[]);assert.deepEqual(unexpected,[]);
    console.log('PASS S6-07: server totals/paging/filters, failed vs explicit return, closed liabilities, GET-only incl admin, errors/retries, stale reads/scope, live revoke/grant, XSS, six widths. '+output);
  }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
