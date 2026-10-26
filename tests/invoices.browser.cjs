// Real Chrome/frontend, isolated HTTP fixtures. Never posts to the business database.
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const {chromium}=require('playwright');
const base=process.env.NAV_TEST_URL||'http://127.0.0.1:5531';
const output=path.join(require('node:os').tmpdir(),'qlbh-s701');
const pause=ms=>new Promise(r=>setTimeout(r,ms));
const token='fixture.'+Buffer.from(JSON.stringify({exp:Math.floor(Date.now()/1000)+3600})).toString('base64url')+'.fixture';
(async()=>{
  fs.mkdirSync(output,{recursive:true});const browser=await chromium.launch({channel:'chrome',headless:true});const errors=[],unexpected=[];
  async function setup(admin=false){
    const roles=admin?['ROLE_ADMIN']:['ROLE_ACCOUNTANT'];const rights={permissions:admin?[]:['INVOICE_VIEW','INVOICE_CREATE','INVOICE_ADJUST']};
    const user={id:9,username:'s701-fixture',fullName:'Kế toán kiểm thử',roles,permissions:rights.permissions};
    const items=[{id:1,orderItemId:11,orderId:101,orderCode:'DH101',productName:'Hàng giao thiếu',productCode:'SKU1',orderedQuantity:10,deliveredQuantity:7.5,invoicedQuantity:7.5,unit:'thùng',unitPrice:100,discountAmount:0,totalAmount:750},
      {id:2,orderItemId:22,orderId:102,orderCode:'DH102',productName:'<img src=x onerror=alert(1)>',productCode:'SKU2',orderedQuantity:5,deliveredQuantity:2,invoicedQuantity:2,unit:'thùng',unitPrice:100,discountAmount:0,totalAmount:200}];
    const preview={dealerId:10,dealerCode:'DL10',dealerName:'Đại lý số 10',dealerTaxCode:'010123',dealerAddress:'Địa chỉ kiểm thử',subtotalAmount:950,discountAmount:0,totalAmount:950,
      items,orders:[{orderId:101,orderCode:'DH101',actualDeliveryDate:'2026-10-20'},{orderId:102,orderCode:'DH102',actualDeliveryDate:'2026-10-22'}]};
    const invoice={...preview,id:1,invoiceNumber:'00000001',invoiceCode:'HD-00000001',invoiceDate:'2026-10-23T10:00:00',dueDate:'2026-11-23',status:'ISSUED',statusLabel:'Đã phát hành',adjustedTotalAmount:950,adjustments:[]};
    const state={calls:[],preview,invoice,issued:false,failIssue:0,failAdjustment:0,failList:false,failPdf:false,detailDelay:0,previewDelay:0,printDelay:0,changePrice:false};
    const context=await browser.newContext({viewport:{width:1440,height:1000},reducedMotion:'reduce',acceptDownloads:true});
    await context.addInitScript(({user,token})=>{sessionStorage.setItem('user',JSON.stringify(user));sessionStorage.setItem('accessToken',token);},{user,token});
    await context.route('http://localhost:8080/api/**',async route=>{
      const request=route.request(),url=new URL(request.url()),endpoint=url.pathname.slice(4),method=request.method();
      const headers={'Access-Control-Allow-Origin':base,'Access-Control-Allow-Headers':'Authorization,Content-Type','Access-Control-Allow-Methods':'GET,POST,OPTIONS'};
      if(method==='OPTIONS')return route.fulfill({status:204,headers});
      const body=request.postData()?request.postDataJSON():null;state.calls.push({endpoint,method,body,query:url.search,auth:request.headers().authorization});
      let data,status=200,message='Thành công';
      const permission=method==='GET'?'INVOICE_VIEW':endpoint.endsWith('/adjustments')?'INVOICE_ADJUST':'INVOICE_CREATE';
      if(endpoint==='/auth/permissions')data={roles,permissions:rights.permissions};
      else if(endpoint==='/users/profile')data=user;
      else if(!admin&&!rights.permissions.includes(permission)){status=403;message='Quyền đã bị thu hồi';}
      else if(endpoint==='/invoices'&&method==='GET'){if(state.failList){status=500;message='Lỗi tải danh sách kiểm thử';}else data={content:state.issued?[invoice]:[],totalPages:state.issued?1:0,totalElements:state.issued?1:0};}
      else if(endpoint==='/invoices/dealers-with-eligible-orders')data=[{dealerId:10,dealerCode:'DL10',dealerName:'Đại lý số 10',uninvoicedDeliveryCount:2},{dealerId:20,dealerCode:'DL20',dealerName:'Đại lý số 20',uninvoicedDeliveryCount:1}];
      else if(endpoint==='/invoices/eligible-orders')data=state.issued?[]:preview.orders.map(o=>({...o,deliveredTotalAmount:items.find(i=>i.orderId===o.orderId).totalAmount,items:items.filter(i=>i.orderId===o.orderId).map(i=>({...i,deliveredTotalAmount:i.totalAmount}))}));
      else if(endpoint==='/invoices/preview'&&method==='POST'){
        if(state.previewDelay)await pause(state.previewDelay);
        const selected=preview.orders.filter(o=>body.orderIds.includes(o.orderId));
        data={...preview,dealerId:body.dealerId,orders:selected,items:items.filter(i=>body.orderIds.includes(i.orderId))};
        if(state.changePrice)data={...data,totalAmount:1000};
      } else if(endpoint==='/invoices'&&method==='POST'){
        await pause(120);if(state.failIssue){status=state.failIssue;message='Lỗi phát hành kiểm thử';}else{state.issued=true;data=invoice;}
      } else if(endpoint==='/invoices/1'&&method==='GET'){
        data=JSON.parse(JSON.stringify(invoice));if(state.detailDelay)await pause(state.detailDelay);
      } else if(endpoint==='/invoices/1/adjustments'&&method==='POST'){
        await pause(100);if(state.failAdjustment){status=state.failAdjustment;message='Lỗi điều chỉnh kiểm thử';}
        else {data={id:41,invoiceId:1,adjustmentCode:'DC-00000001-01',adjustmentType:body.adjustmentType,adjustmentReason:body.adjustmentReason,adjustmentAmount:-Number(body.adjustmentAmount||0),adjustmentDate:'2026-10-23T11:00:00'};invoice.adjustments.push(data);invoice.status='ADJUSTED';invoice.statusLabel='Đã điều chỉnh';invoice.adjustedTotalAmount=850;}
      } else if(/^\/invoices\/1(?:\/adjustments\/41)?\/pdf$/.test(endpoint)){
        return route.fulfill({status:200,headers,contentType:state.failPdf?'text/html':'application/pdf',body:state.failPdf?'<html>not PDF</html>':'%PDF-1.4\nfixture\n%%EOF'}).catch(()=>{});
      } else if(endpoint==='/invoices/1/print-preview'){
        if(state.printDelay)await pause(state.printDelay);
        return route.fulfill({status:200,headers,contentType:'text/html',body:'<!doctype html><html><head><style>body{color:rgb(15,118,110)}</style></head><body><h1>HÓA ĐƠN 00000001</h1><div class="no-print"><button onclick="window.print()">In</button></div><script>parent.__invoiceXss=1</script><img src="https://invalid.example/x" onerror="parent.__invoiceXss=2"><p>Thực giao 7,5 thùng</p></body></html>'}).catch(()=>{});
      } else {unexpected.push({endpoint,method});status=404;message='Unexpected fixture request';}
      await route.fulfill({status,headers,contentType:'application/json',body:JSON.stringify({success:status===200,data,message})}).catch(()=>{});
    });
    const page=await context.newPage();page.on('pageerror',e=>errors.push(e.message));
    const setRights=async permissions=>{rights.permissions=permissions;await page.evaluate(async()=>Auth.refreshPermissions());};
    async function prepare(){
      await page.locator('#inv-new').click();await page.locator('#inv-dealer option[value="10"]').waitFor({state:'attached'});
      await page.locator('#inv-dealer').selectOption('10');await page.locator('#inv-delivered-from').fill('2026-10-01');await page.locator('#inv-delivered-to').fill('2026-10-31');
      await page.locator('#inv-load-eligible').click();await page.locator('[data-eligible="101"]').check();await page.locator('[data-eligible="102"]').check();
      await page.locator('#inv-preview').click();await page.locator('#inv-preview-panel').waitFor({state:'visible'});
    }
    return {context,page,state,setRights,prepare};
  }
  try{
    const s=await setup();await s.page.goto(base+'/invoices');await s.prepare();
    assert.equal(await s.page.locator('#inv-preview-body img').count(),0);
    assert.match(await s.page.locator('#inv-preview-body').textContent(),/7,5/);
    assert.equal(s.state.calls.filter(c=>c.endpoint==='/invoices'&&c.method==='POST').length,0,'preview never issues');
    assert.equal(await s.page.locator('#inv-issue').isDisabled(),true);
    assert.ok(s.state.calls.some(c=>c.endpoint==='/invoices/eligible-orders'&&c.query.includes('startDate=2026-10-01')&&c.query.includes('endDate=2026-10-31')));
    // Any issue metadata change invalidates previously reviewed values.
    await s.page.locator('#inv-note').fill('Gộp phiếu trong tháng');assert.equal(await s.page.locator('#inv-preview-panel').isHidden(),true);
    await s.page.locator('#inv-preview').click();await s.page.locator('#inv-preview-panel').waitFor();
    s.state.changePrice=true;await s.page.locator('#inv-confirm').check();await s.page.locator('#inv-issue').click();
    await s.page.locator('#inv-create-error').getByText(/Dữ liệu thực giao hoặc giá trị đã thay đổi/).waitFor();
    assert.equal(s.state.calls.filter(c=>c.endpoint==='/invoices'&&c.method==='POST').length,0,'changed total requires reconfirmation');
    assert.equal(await s.page.locator('#inv-confirm').isChecked(),false);
    s.state.changePrice=false;await s.page.locator('#inv-preview').click();await s.page.locator('#inv-preview-panel').waitFor();
    await s.page.locator('#inv-confirm').check();await s.page.locator('#inv-issue').evaluate(b=>{b.click();b.click();});
    await s.page.locator('#inv-detail-title').getByText('Hóa đơn 00000001',{exact:true}).waitFor();
    const issued=s.state.calls.filter(c=>c.endpoint==='/invoices'&&c.method==='POST');assert.equal(issued.length,1);
    assert.deepEqual(issued[0].body,{dealerId:10,orderIds:[101,102],note:'Gộp phiếu trong tháng'});
    assert.ok(!s.state.calls.some(c=>['PUT','PATCH','DELETE'].includes(c.method)));
    assert.equal(await s.page.locator('#inv-detail input').count()>0,true,'adjustment fields exist but initially hidden');
    for(const width of [1920,1440,1024,768,390,320]){
      await s.page.setViewportSize({width,height:1000});await s.page.evaluate(()=>new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r))));
      assert.ok(await s.page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),`overflow ${width}`);
      if([1440,390].includes(width)){await s.page.evaluate(()=>window.scrollTo(0,0));await s.page.screenshot({path:path.join(output,`invoices-${width}.png`),fullPage:true});}
    }
    await s.page.setViewportSize({width:1440,height:1000});
    await s.page.locator('#inv-print-open').click();await s.page.locator('#inv-print-now').waitFor({state:'visible'});
    await s.page.waitForFunction(()=>!document.querySelector('#inv-print-now').disabled);
    const frame=s.page.frameLocator('#inv-print iframe');await frame.getByText('HÓA ĐƠN 00000001',{exact:true}).waitFor();
    assert.equal(await s.page.evaluate(()=>window.__invoiceXss),undefined);
    assert.equal(await frame.locator('script,[onerror],[onclick],.no-print').count(),0);
    assert.equal(await frame.locator('h1').evaluate(e=>getComputedStyle(e).color),'rgb(15, 118, 110)');
    await s.page.locator('#inv-print-close').click();
    let downloaded=s.page.waitForEvent('download');await s.page.locator('#inv-pdf').click();assert.equal((await downloaded).suggestedFilename(),'hoa-don-00000001.pdf');
    s.state.failPdf=true;await s.page.locator('#inv-pdf').click();await s.page.locator('#inv-detail-error').getByText('Máy chủ chưa trả về PDF hợp lệ.',{exact:true}).waitFor();s.state.failPdf=false;
    await s.page.locator('#inv-adjust-open').click();await s.page.locator('#inv-adjust-type').selectOption('DECREASE');await s.page.locator('#inv-adjust-amount').fill('100');await s.page.locator('#inv-adjust-reason').fill('Điều chỉnh tiền theo biên bản');await s.page.locator('#inv-adjust-confirm').check();
    await s.page.locator('#inv-adjust-save').evaluate(b=>{b.click();b.click();});await s.page.locator('[data-adjustment-pdf="41"]').waitFor();
    assert.equal(s.state.calls.filter(c=>c.endpoint.endsWith('/adjustments')&&c.method==='POST').length,1);
    downloaded=s.page.waitForEvent('download');await s.page.locator('[data-adjustment-pdf="41"]').click();assert.equal((await downloaded).suggestedFilename(),'chung-tu-41.pdf');
    await s.setRights(['INVOICE_VIEW']);await s.page.locator('[data-invoice="1"]').click();await s.page.locator('#inv-detail-actions').waitFor();
    assert.equal(await s.page.locator('#inv-new').isHidden(),true);assert.equal(await s.page.locator('#inv-adjust-open').isHidden(),true);
    await s.setRights(['INVOICE_VIEW','INVOICE_CREATE','INVOICE_ADJUST']);await s.page.locator('[data-invoice="1"]').click();await s.page.locator('#inv-detail-actions').waitFor();
    s.state.printDelay=350;await s.page.locator('#inv-print-open').click();await s.setRights(['INVOICE_VIEW']);await pause(450);assert.equal(await s.page.locator('#inv-print iframe').count(),0);
    await s.setRights([]);await s.page.waitForURL('**/error?status=403');assert.equal(await s.page.locator('[data-invoice]').count(),0);
    assert.ok(s.state.calls.every(c=>c.auth==='Bearer '+token));await s.context.close();
    const failed=await setup();await failed.page.goto(base+'/invoices');await failed.prepare();failed.state.failIssue=504;
    await failed.page.locator('#inv-confirm').check();await failed.page.locator('#inv-issue').click();await failed.page.locator('#inv-create-error').getByText(/Chưa xác định được kết quả phát hành/).waitFor();
    assert.equal(await failed.page.locator('#inv-new').isDisabled(),true);assert.equal(await failed.page.locator('#inv-preview').isDisabled(),true);
    await failed.page.locator('#inv-reload').click();assert.equal(await failed.page.locator('#inv-new').isEnabled(),true);
    failed.state.failList=true;await failed.page.locator('#inv-reload').click();await failed.page.locator('#inv-error').getByText('Lỗi tải danh sách kiểm thử',{exact:true}).waitFor();
    assert.equal(await failed.page.locator('[data-invoice]').count(),0);
    failed.state.failList=false;await failed.page.locator('#inv-reload').click();await failed.page.locator('#inv-list').getByText(/Không có hóa đơn phù hợp/).waitFor();
    await failed.context.close();
    const stale=await setup();await stale.page.goto(base+'/invoices');await stale.prepare();
    stale.state.previewDelay=350;
    await stale.page.locator('#inv-note').fill('Đang đổi ghi chú');await stale.page.locator('#inv-preview').click();
    await stale.setRights(['INVOICE_VIEW']);await pause(450);
    assert.equal(await stale.page.locator('#inv-create').isHidden(),true);
    assert.equal(await stale.page.locator('#inv-preview-body').textContent(),'');
    assert.equal(stale.state.calls.filter(c=>c.endpoint==='/invoices'&&c.method==='POST').length,0);
    await stale.setRights(['INVOICE_VIEW','INVOICE_CREATE','INVOICE_ADJUST']);
    stale.state.previewDelay=0;await stale.prepare();
    await stale.page.locator('#inv-dealer').selectOption('20');
    assert.equal(await stale.page.locator('#inv-preview-panel').isHidden(),true);
    assert.equal(await stale.page.locator('[data-eligible]').count(),0,'dealer change cannot retain another dealer selection');
    await stale.page.locator('#inv-create-close').click();
    stale.state.issued=true;await stale.page.locator('#inv-reload').click();await stale.page.locator('[data-invoice="1"]').click();
    await stale.page.locator('#inv-adjust-open').click();
    await stale.page.locator('#inv-adjust-type').selectOption('INCREASE');await stale.page.locator('#inv-adjust-amount').fill('100');await stale.page.locator('#inv-adjust-reason').fill('Thử lại lỗi nghiệp vụ');await stale.page.locator('#inv-adjust-confirm').check();
    stale.state.failAdjustment=400;await stale.page.locator('#inv-adjust-save').click();await stale.page.locator('#inv-adjust-error').getByText('Lỗi điều chỉnh kiểm thử',{exact:true}).waitFor();
    assert.equal(await stale.page.locator('#inv-adjust-reason').inputValue(),'Thử lại lỗi nghiệp vụ');
    stale.state.failAdjustment=504;await stale.page.locator('#inv-adjust-save').click();await stale.page.locator('#inv-detail-error').getByText(/Chưa xác định được kết quả điều chỉnh/).waitFor();
    assert.equal(await stale.page.locator('#inv-adjust-open').isDisabled(),true);
    await stale.context.close();
    const admin=await setup(true);await admin.page.goto(base+'/invoices');await admin.page.locator('#inv-new').waitFor({state:'visible'});assert.equal(await admin.page.locator('#inv-new').isEnabled(),true);await admin.context.close();
    assert.deepEqual(errors,[]);assert.deepEqual(unexpected,[]);
    console.log('S7-01 Chrome PASS: server actual-delivery preview, same-dealer grouping/period, fresh-total confirmation, single issue/adjust, immutable detail, authenticated/sandboxed print/PDF, matrix grant/revoke, uncertain 504 lock, six widths. HTTP fixtures only, no DB writes.');
  }finally{await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
