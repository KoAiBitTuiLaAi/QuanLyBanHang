// Regression: an unrelated broken /orders endpoint must not block SKU/unit reads.
const assert = require('node:assert/strict');
const {chromium} = require('playwright');
const base = process.env.NAV_TEST_URL || 'http://127.0.0.1:5538';
const token='test.'+Buffer.from(JSON.stringify({exp:Math.floor(Date.now()/1000)+3600})).toString('base64url')+'.test';
(async()=>{
  const browser=await chromium.launch({channel:'chrome',headless:true});
  const rights=['WAREHOUSE_VIEW','WAREHOUSE_MANAGE','ORDER_VIEW'];
  const user={id:7,username:'units-test',fullName:'Unit read test',roles:['ROLE_VIEWER'],permissions:rights};
  const calls=[],errors=[]; let failOrders=true;
  try {
    const context=await browser.newContext({viewport:{width:1440,height:1000},reducedMotion:'reduce'});
    await context.addInitScript(({user,token})=>{sessionStorage.setItem('user',JSON.stringify(user));sessionStorage.setItem('accessToken',token);},{user,token});
    await context.route('http://localhost:8080/api/**',async route=>{
      const request=route.request(), endpoint=new URL(request.url()).pathname.slice(4),method=request.method();
      const headers={'Access-Control-Allow-Origin':base,'Access-Control-Allow-Headers':'Authorization,Content-Type','Access-Control-Allow-Methods':'GET,OPTIONS'};
      if(method==='OPTIONS')return route.fulfill({status:204,headers});
      calls.push({endpoint,method}); let data=[],status=200,message='OK';
      if(endpoint==='/auth/permissions')data={roles:user.roles,permissions:rights};
      else if(endpoint==='/warehouse/options')data=[{id:1,code:'WH1',name:'Kho kiểm thử'}];
      else if(endpoint==='/products/lookup')data=[{id:10,code:'SKU10',name:'Nước kiểm thử',unit:'lon',categoryName:'Nước uống',price:10}];
      else if(endpoint==='/warehouse/inventory')data=[{productId:10,baseQuantity:100,baseUnit:'lon',reservedQuantity:30,availableQuantity:70}];
      else if(endpoint==='/products/10/unit-conversions')data=[{unitName:'lon',conversionFactor:1,isBaseUnit:true},{id:1,unitName:'thùng',conversionFactor:24,isBaseUnit:false}];
      else if(endpoint==='/orders'){
        if(failOrders){status=400;message='Transaction silently rolled back because it has been marked as rollback-only';}
        else data=[{id:5,orderCode:'DH-5',customerName:'Đại lý kiểm thử',status:'RECORDED',createdAt:'2026-10-22T09:00:00',
          items:[{productCode:'SKU10',productName:'Nước kiểm thử',unit:'lon',quantity:2,baseQuantity:2,conversionFactor:1,baseUnit:'lon'}]}];
      }else assert.equal(endpoint,'/warehouse/vouchers','Unexpected API');
      await route.fulfill({status,headers,contentType:'application/json',body:JSON.stringify({success:status===200,data,message})});
    });
    const page=await context.newPage();page.on('pageerror',e=>errors.push(e.message));
    await page.goto(base+'/warehouse/units?tab=units');
    await page.locator('#sku-table-body').getByText('SKU10',{exact:true}).waitFor();
    assert.equal(await page.locator('#metric-sku-count').textContent(),'1');
    assert.equal(calls.filter(c=>c.endpoint==='/orders').length,0);
    assert.equal(await page.locator('.toast--error').count(),0);
    await page.locator('#tab-ledger-audit').click();
    await page.locator('.toast--error').getByText(/Transaction silently rolled back/).waitFor();
    assert.equal(calls.filter(c=>c.endpoint==='/orders').length,1,'Sales history is lazy and still reports real failures');
    await page.locator('#tab-sku-units').click();
    await page.locator('#sku-table-body').getByText('SKU10',{exact:true}).waitFor();
    failOrders=false;await page.locator('#tab-ledger-audit').click();
    await page.locator('#ledger-table-body').getByText('DH-5',{exact:true}).waitFor();
    assert.equal(calls.filter(c=>c.endpoint==='/orders').length,2,'History can recover after the backend is fixed');
    assert.ok(calls.every(c=>c.method==='GET'),'Reads must never save a voucher or order');
    assert.deepEqual(errors,[]);await context.close();
    console.log('Units Chrome passed: unit products load despite broken sales API, no initial orders dependency, lazy ledger error/recovery, real errors not hidden, GET-only.');
  } finally {await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
