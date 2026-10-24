// S5-09 in Chrome. HTTP fixtures only; the real database/matrix are never modified.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright');
const base = process.env.NAV_TEST_URL || 'http://127.0.0.1:5529';
const output = path.join(require('node:os').tmpdir(), 'qlbh-s509');
const user = { id: 5, username: 'minimum-manager', fullName: 'Quản lý ngưỡng tồn', roles: ['ROLE_VIEWER'], permissions: ['WAREHOUSE_MANAGE'], warehouses: [{id:1}], territories: [] };
const token = 'test.' + Buffer.from(JSON.stringify({exp:Date.now()/1000+3600})).toString('base64url') + '.test';
const warehouses = [{id:1,code:'KHO-HN',name:'Kho Hà Nội'}];
const categories = [{id:10,code:'NUOC',name:'Nước giải khát'}];
const statuses = [{value:'IN_STOCK',label:'Còn khả dụng'},{value:'OUT_OF_STOCK',label:'Hết tồn'},{value:'FULLY_RESERVED',label:'Đã giữ hết'}];
const rows = Array.from({length:24},(_,i)=>({warehouseId:1,warehouseCode:'KHO-HN',warehouseName:'Kho Hà Nội',productId:i+1,
  productCode:`SKU-${String(i+1).padStart(3,'0')}`,productName:i===23?'<img src=x onerror=alert(1)>':`Nước giải khát ${i+1}`,
  categoryId:10,categoryName:'Nước giải khát',baseUnit:'lon',minimumQuantity:null,minimumUnit:null,minimumUnitMatches:true,
  warehouseActive:true,productActive:true,updatedAt:null,updatedBy:null}));
let available = 7.25, failSave = false, failAlerts = false, noWarehouses = false;
const calls = [], errors = [];
const has = permission => user.roles.includes('ROLE_ADMIN') || user.permissions.includes(permission);
const stock = row => ({...row,physicalQuantity:10.5,reservedQuantity:10.5-available,availableQuantity:available,
  stockStatus:'IN_STOCK',stockStatusLabel:'Còn khả dụng',belowMinimum:row.minimumQuantity!=null && row.minimumUnitMatches && available<Number(row.minimumQuantity)});
const paged = (data,url) => {const page=Number(url.searchParams.get('page')||0),size=Number(url.searchParams.get('size')||20);
  return {content:data.slice(page*size,(page+1)*size),totalElements:data.length,totalPages:Math.ceil(data.length/size),number:page,last:(page+1)*size>=data.length};};
(async()=>{
  fs.mkdirSync(output,{recursive:true});
  const browser = await chromium.launch({channel:'chrome',headless:true});
  try {
    const context = await browser.newContext({reducedMotion:'reduce',viewport:{width:1440,height:1000}});
    await context.addInitScript(({user,token})=>{sessionStorage.setItem('user',JSON.stringify(user));sessionStorage.setItem('accessToken',token);},{user,token});
    await context.route('http://localhost:8080/api/**',async route=>{
      const req=route.request(),url=new URL(req.url()),endpoint=url.pathname.replace('/api',''),method=req.method();
      const headers={'Access-Control-Allow-Origin':base,'Access-Control-Allow-Headers':'Authorization,Content-Type','Access-Control-Allow-Methods':'GET,PUT,DELETE,OPTIONS'};
      if(method==='OPTIONS')return route.fulfill({status:204,headers});
      calls.push({endpoint,method,query:Object.fromEntries(url.searchParams),body:req.postDataJSON()});
      let data,status=200,message='';
      const minimum=endpoint.startsWith('/warehouse/stock-minimums');
      if(endpoint==='/auth/permissions')data={roles:user.roles,permissions:user.permissions};
      else if(!has(minimum?'WAREHOUSE_MANAGE':'WAREHOUSE_VIEW')){status=403;message='Không có quyền';}
      else if(endpoint==='/warehouse/stock-minimums/options')data={warehouses:noWarehouses?[]:warehouses,categories};
      else if(endpoint==='/warehouse/stock-ledger/options')data={warehouses,categories,stockStatuses:statuses};
      else if(/^\/warehouse\/stock-minimums\/1\/\d+$/.test(endpoint)){
        const row=rows.find(r=>r.productId===Number(endpoint.split('/').at(-1)));
        if(failSave){status=400;message='Đơn vị cơ sở đã thay đổi. Tải lại để khai báo.';}
        else {row.minimumQuantity=method==='DELETE'?null:req.postDataJSON().minimumQuantity;row.minimumUnit=method==='DELETE'?null:row.baseUnit;row.updatedBy=user.username;row.updatedAt='2026-10-20T14:00:00';data=row;}
      } else if(['/warehouse/stock-minimums','/warehouse/stock-ledger','/warehouse/stock-alerts'].includes(endpoint)){
        let selected=rows.filter(r=>!url.searchParams.get('keyword') || (r.productCode+r.productName).toLowerCase().includes(url.searchParams.get('keyword').toLowerCase()));
        if(!minimum)selected=selected.map(stock);
        if(endpoint.endsWith('stock-alerts') || url.searchParams.get('belowMinimum')==='true')selected=selected.filter(r=>r.belowMinimum);
        if(endpoint.endsWith('stock-alerts') && failAlerts){status=500;message='Không tải được cảnh báo tồn kho.';}
        data=paged(selected,url);
      } else throw new Error('Unexpected prerequisite API: '+endpoint);
      await route.fulfill({status,headers,contentType:'application/json',body:JSON.stringify({success:status===200,data,message})}).catch(e=>{if(!req.failure())throw e;});
    });
    const page=await context.newPage();page.on('pageerror',e=>errors.push(e.message));
    await page.goto(base+'/warehouse/minimums');await page.locator('#min-next:not([disabled])').waitFor();
    assert.equal(await page.locator('#min-rows tr').count(),20);
    assert.equal(await page.locator('#sidebar-nav [data-page="stock-minimums"]').count(),1);
    assert.equal(await page.locator('#sidebar-nav [data-page="warehouse"]').count(),0);
    assert.ok(calls.every(c=>c.endpoint==='/auth/permissions' || c.endpoint.startsWith('/warehouse/stock-minimums')),'MANAGE-only configuration must not request VIEW/PRODUCT APIs');
    await page.locator('#min-next').click();await page.waitForFunction(()=>document.querySelector('#min-page').textContent==='Trang 2 / 2');
    assert.equal(await page.locator('#min-rows img').count(),0);assert.match(await page.locator('#min-rows').innerText(),/<img src=x/);
    await page.locator('#min-keyword').fill('SKU-001');await page.locator('#min-apply').click();
    await page.waitForFunction(()=>document.querySelector('#min-count').textContent==='1 dòng SKU–kho');
    assert.match(await page.locator('#min-rows').innerText(),/Chưa khai báo/);
    await page.locator('[data-edit]').click();await page.locator('#min-quantity').fill('7.2501');await page.locator('#min-save').click();
    await page.waitForFunction(()=>!document.querySelector('#min-dialog').open && document.querySelector('#min-rows').textContent.includes('7,2501'));
    assert.equal(calls.findLast(c=>c.method==='PUT').body.baseUnit,'lon');
    assert.ok(await page.locator('.toast--success').count()>0);
    failSave=true;await page.locator('[data-edit]').click();await page.locator('#min-quantity').fill('8');await page.locator('#min-save').click();
    await page.locator('#min-form-error').getByText(/Đơn vị cơ sở/).waitFor();assert.equal(await page.locator('#min-quantity').inputValue(),'8');
    assert.equal(await page.locator('#min-dialog').evaluate(e=>e.open),true);failSave=false;
    await page.locator('#min-quantity').fill('-1');const writes=calls.filter(c=>c.method==='PUT').length;await page.locator('#min-save').click();
    assert.equal(await page.locator('#min-quantity').evaluate(e=>e.validity.valid),false);assert.equal(calls.filter(c=>c.method==='PUT').length,writes);
    await page.locator('#min-quantity').fill('0');await page.locator('#min-save').click();
    await page.waitForFunction(()=>!document.querySelector('#min-dialog').open && document.querySelector('#min-rows td:nth-child(5)')?.textContent==='0');
    await page.locator('[data-edit]').click();await page.locator('#min-clear').click();
    await page.waitForFunction(()=>!document.querySelector('#min-dialog').open && document.querySelector('#min-rows').textContent.includes('Chưa khai báo'));
    await page.locator('[data-edit]').click();await page.locator('#min-quantity').fill('8');await page.locator('#min-save').click();
    await page.waitForFunction(()=>!document.querySelector('#min-dialog').open && document.querySelector('#min-rows td:nth-child(5)')?.textContent==='8');
    await page.waitForFunction(()=>document.querySelectorAll('.toast').length===0);
    for(const width of [1920,1440,1024,768,390,320]){
      await page.setViewportSize({width,height:1000});
      await page.waitForFunction(()=>document.documentElement.scrollWidth<=document.documentElement.clientWidth+1);
      if([1920,390].includes(width))await page.screenshot({path:path.join(output,`minimums-${width}.png`),fullPage:true});
    }
    await page.locator('[data-edit]').click();await page.screenshot({path:path.join(output,'minimum-dialog-320.png')});await page.locator('#min-close').click();
    noWarehouses=true;await page.locator('#min-reload').click();await page.locator('#min-rows').getByText(/Chưa có kho trong phạm vi/).waitFor();noWarehouses=false;
    await page.locator('#min-reload').click();await page.locator('[data-edit]').first().waitFor();
    user.permissions=['WAREHOUSE_VIEW'];await page.evaluate(()=>Auth.syncPermissions());await page.waitForURL('**/error?status=403');
    await page.goto(base+'/warehouse/inventory?keyword=SKU-001&belowMinimum=true');
    await page.locator('.stock-row--below-minimum').waitFor();
    assert.equal(await page.locator('#stock-below').inputValue(),'true');assert.equal(await page.locator('#stock-minimum-settings').isVisible(),false);
    assert.match(await page.locator('#stock-rows').innerText(),/Dưới mức tối thiểu/);
    assert.equal(await page.locator('#stock-rows tr').count(),1);
    user.permissions=['WAREHOUSE_MANAGE','WAREHOUSE_VIEW'];await page.evaluate(()=>Auth.syncPermissions());await page.locator('#stock-minimum-settings').waitFor();
    for(const width of [1920,390]){await page.setViewportSize({width,height:1000});await page.waitForFunction(()=>document.documentElement.scrollWidth<=document.documentElement.clientWidth+1);await page.screenshot({path:path.join(output,`alert-ledger-${width}.png`),fullPage:true});}
    await page.goto(base+'/dashboard');await page.locator('.dashboard-stock-alert-link').waitFor();
    assert.match(await page.locator('#dashboard-stock-alert-state').innerText(),/1 dòng SKU–kho/);
    assert.match(await page.locator('.dashboard-stock-alert-link').getAttribute('href'),/warehouseId=1&keyword=SKU-001&belowMinimum=true/);
    for(const width of [1920,390,320]){await page.setViewportSize({width,height:1000});await page.waitForFunction(()=>document.documentElement.scrollWidth<=document.documentElement.clientWidth+1);if(width!==320)await page.screenshot({path:path.join(output,`alert-dashboard-${width}.png`),fullPage:true});}
    available=8;await page.locator('#dashboard-stock-alert-reload').click();await page.locator('#dashboard-stock-alert-state').getByText(/Không có SKU dưới/).waitFor();
    assert.equal(await page.locator('.dashboard-stock-alert-link').count(),0);
    available=7.25;await page.locator('#dashboard-stock-alert-reload').click();await page.locator('.dashboard-stock-alert-link').waitFor();
    failAlerts=true;await page.locator('#dashboard-stock-alert-reload').click();await page.locator('#dashboard-stock-alert-error').waitFor();
    assert.match(await page.locator('#dashboard-stock-alert-state').innerText(),/Chưa xác định/);assert.equal(await page.locator('.dashboard-stock-alert-link').count(),0);failAlerts=false;
    user.permissions=['WAREHOUSE_MANAGE'];await page.evaluate(()=>Auth.syncPermissions());assert.equal(await page.locator('#dashboard-stock-alerts').isVisible(),false);
    assert.equal(await page.locator('.dashboard-stock-alert-link').count(),0);
    user.permissions=['WAREHOUSE_VIEW'];await page.evaluate(()=>Auth.syncPermissions());await page.locator('.dashboard-stock-alert-link').waitFor();
    user.roles=['ROLE_WAREHOUSE_MANAGER'];user.permissions=[];await page.evaluate(()=>Auth.syncPermissions());assert.equal(await page.locator('#dashboard-stock-alerts').isVisible(),false);
    await page.goto(base+'/warehouse/minimums');await page.waitForURL('**/error?status=403');
    user.roles=['ROLE_ADMIN'];await page.goto(base+'/warehouse/minimums');await page.locator('[data-edit]').first().waitFor();
    assert.deepEqual(errors,[]);
    console.log(JSON.stringify({passed:true,output,cases:'SKU/warehouse configuration, pagination/filter, zero vs unset, save/delete, validation/error recovery, XSS, ledger filter/highlight, dashboard replenishment/error, live matrix grant/revoke, no role fallback, Admin, six widths'}));
  } finally {await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
