// Real Chrome + frontend scripts, isolated API responses; no business DB mutations.
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),os=require('node:os');
const {chromium}=require('playwright');
const U=require('../frontend/js/dealer-return-utils.js');
const base=process.env.NAV_TEST_URL||'http://127.0.0.1:5536';
const output=path.join(os.tmpdir(),'qlbh-s801');
const pause=ms=>new Promise(resolve=>setTimeout(resolve,ms));
const token='fixture.'+Buffer.from(JSON.stringify({exp:Math.floor(Date.now()/1000)+3600})).toString('base64url')+'.fixture';
const rights=['ORDER_VIEW','ORDER_CREATE','WAREHOUSE_APPROVE','ORDER_CANCEL'];
const qty=value=>`${value/10000n}.${String(value%10000n).padStart(4,'0')}`;
(async()=>{
  fs.mkdirSync(output,{recursive:true});const browser=await chromium.launch({channel:'chrome',headless:true});
  const errors=[],unexpected=[];let cases=0;
  async function setup(permissions=rights,admin=false){
    const roles=admin?['ROLE_ADMIN']:['ROLE_VIEWER']; // default role cannot grant the actions
    const state={permissions:[...permissions],calls:[],createStatus:200,confirmStatus:200,cancelStatus:200,listStatus:200,optionsStatus:200,sourceDelay:0,detailDelay:0,listDelay:0,optionsCanCreate:true,optionsCanConfirm:true,detailCanConfirm:true,detailCanCancel:true};
    const items=[
      {invoiceItemId:101,productId:1,productCode:'SKU-LO',productName:'Hàng quản lý lô',orderCode:'DH01',unit:'thùng',baseUnit:'chai',conversionFactor:'12',manageLot:true,deliveredQuantity:'10',previouslyReturnedQuantity:'4',confirmedReturnedQuantity:'2',pendingReturnedQuantity:'2',returnableQuantity:'6',availableLots:[{lotNumber:'LO-01',expiryDate:'2027-12-31',manufacturingDate:'2025-01-01',currentBaseQuantity:'0'}]},
      {invoiceItemId:102,productId:2,productCode:'SKU-TOT',productName:'<img src=x onerror=alert(1)>',orderCode:'DH01',unit:'cái',baseUnit:'cái',conversionFactor:'1',manageLot:false,deliveredQuantity:'5',previouslyReturnedQuantity:'0',confirmedReturnedQuantity:'0',pendingReturnedQuantity:'0',returnableQuantity:'5',availableLots:[]},
      {invoiceItemId:103,productId:3,productCode:'SKU-THUC-TE',productName:'Lô không còn trong kho gốc',orderCode:'DH02',unit:'hộp',baseUnit:'hộp',conversionFactor:'1',manageLot:true,deliveredQuantity:'2',previouslyReturnedQuantity:'0',confirmedReturnedQuantity:'0',pendingReturnedQuantity:'0',returnableQuantity:'2',availableLots:[]},
    ];
    state.invoices=[{invoiceId:10,invoiceCode:'HD-001',invoiceNumber:'0001',invoiceDate:'2026-10-23T08:00:00',status:'ISSUED',dealerId:20,dealerCode:'DL20',dealerName:'Đại lý Minh Phát',warehouseId:1,warehouseCode:'K01',warehouseName:'Kho chính',totalReturnableQuantity:'13',items},
      {invoiceId:11,invoiceCode:'HD-HET',invoiceNumber:'0002',invoiceDate:'2026-10-23T09:00:00',status:'ISSUED',dealerId:20,dealerCode:'DL20',dealerName:'Đại lý Minh Phát',warehouseId:1,warehouseName:'Kho chính',totalReturnableQuantity:'0',items:[]}];
    const itemResponse=(item,index=0)=>{const source=items.find(row=>row.invoiceItemId===item.invoiceItemId);return {...source,...item,id:index+1,returnQuantity:item.returnQuantity||'1',baseReturnQuantity:qty(U.decimal(item.returnQuantity||'1').value*U.decimal(source.conversionFactor).value/10000n),targetWarehouseId:item.targetWarehouseId||1,targetWarehouseName:item.defective?'Kho hàng lỗi':'Kho chính',targetWarehouseCode:item.defective?'KLOI':'K01',lotNumber:item.lotNumber||null,expiryDate:item.expiryDate||null,manufacturingDate:item.manufacturingDate||null};};
    state.rows=[{id:1,returnCode:'PTH-001',invoiceId:10,invoiceCode:'HD-001',dealerId:20,dealerName:'Đại lý Minh Phát',dealerCode:'DL20',warehouseId:1,warehouseCode:'K01',warehouseName:'Kho chính',defectiveWarehouseId:2,defectiveWarehouseName:'Kho hàng lỗi',status:'PENDING',inventoryAdded:false,note:'<script>window.__returnXss=1</script>',createdBy:'sales',createdAt:'2026-10-24T08:00:00',confirmedBy:null,confirmedAt:null,items:[itemResponse({invoiceItemId:101,returnQuantity:'1',defective:false,lotNumber:'LO-01',expiryDate:'2027-12-31'}),itemResponse({invoiceItemId:102,returnQuantity:'1',defective:true,targetWarehouseId:2,returnReason:'DAMAGED'},1)]}];
    const user={id:8,username:'return-fixture',fullName:'Nhân viên kiểm thử',roles,permissions:state.permissions,warehouses:[{id:1,name:'Kho chính'}],territories:[]};
    const context=await browser.newContext({viewport:{width:1440,height:1000},reducedMotion:'reduce'});
    await context.addInitScript(({user,token})=>{sessionStorage.setItem('user',JSON.stringify(user));sessionStorage.setItem('accessToken',token);},{user,token});
    await context.route('http://localhost:8080/api/**',async route=>{
      const req=route.request(),url=new URL(req.url()),endpoint=url.pathname.slice(4),method=req.method();
      const headers={'Access-Control-Allow-Origin':base,'Access-Control-Allow-Headers':'Authorization,Content-Type','Access-Control-Allow-Methods':'GET,POST,OPTIONS'};
      if(method==='OPTIONS')return route.fulfill({status:204,headers});
      const body=req.postData()?req.postDataJSON():null;state.calls.push({endpoint,method,body,query:url.search,auth:req.headers().authorization});
      const has=p=>admin||state.permissions.includes(p),read=()=>U.readPermissions.some(has);
      let data,status=200,message='Thành công';
      const withFlags=row=>({...row,canConfirm:row.status==='PENDING'&&has('WAREHOUSE_APPROVE')&&state.detailCanConfirm,canCancel:row.status==='PENDING'&&['ORDER_CREATE','ORDER_CANCEL','WAREHOUSE_APPROVE'].some(has)&&state.detailCanCancel});
      if(endpoint==='/auth/permissions')data={roles,permissions:state.permissions};
      else if(!read()){status=403;message='Quyền đã bị thu hồi';}
      else if(endpoint==='/dealer-returns/options'){
        status=state.optionsStatus;data={warehouses:[{id:1,code:'K01',name:'Kho chính'}],defectiveWarehouses:[{id:2,code:'KLOI',name:'Kho hàng lỗi'},{id:3,code:'KLOI2',name:'Kho lỗi 2'}],returnReasons:[{code:'DAMAGED',label:'Hàng hỏng',defectiveDefault:true},{code:'EXCHANGE',label:'Đổi trả',defectiveDefault:false},{code:'OTHER',label:'Lý do khác',defectiveDefault:false}],statuses:[{code:'PENDING',label:'Chờ kho xác nhận'},{code:'CONFIRMED',label:'Đã nhập kho'},{code:'CANCELLED',label:'Đã hủy'}],canCreate:has('ORDER_CREATE')&&state.optionsCanCreate,canConfirm:has('WAREHOUSE_APPROVE')&&state.optionsCanConfirm};
      }else if(endpoint==='/dealer-returns/eligible-invoices'){
        if(!U.sourcePermissions.some(has))status=403;
        else{const rows=state.invoices.filter(row=>!url.searchParams.get('keyword')||row.invoiceCode.includes(url.searchParams.get('keyword')));const page=Number(url.searchParams.get('page')||0);data={content:rows.slice(page*20,page*20+20),totalPages:Math.ceil(rows.length/20),totalElements:rows.length};}
      }else if(/^\/dealer-returns\/eligible-invoices\/\d+$/.test(endpoint)){
        if(!U.sourcePermissions.some(has))status=403;else{data=structuredClone(state.invoices.find(row=>String(row.invoiceId)===endpoint.split('/').at(-1)));if(state.sourceDelay)await pause(state.sourceDelay);}
      }else if(endpoint==='/dealer-returns'&&method==='GET'){
        const rows=state.rows.filter(row=>(!url.searchParams.get('status')||row.status===url.searchParams.get('status'))&&(!url.searchParams.get('warehouseId')||String(row.warehouseId)===url.searchParams.get('warehouseId')));
        const page=Number(url.searchParams.get('page')||0);const snapshot=rows.slice(page*20,page*20+20).map(withFlags);
        if(state.listDelay)await pause(state.listDelay);status=state.listStatus;data={content:snapshot,totalPages:Math.ceil(rows.length/20),totalElements:rows.length};
      }else if(endpoint==='/dealer-returns'&&method==='POST'){
        if(!has('ORDER_CREATE'))status=403;
        else{await pause(120);status=state.createStatus;if(status===200){data=withFlags({...state.rows[0],id:2,returnCode:'PTH-002',note:body.note,warehouseId:body.warehouseId,status:'PENDING',inventoryAdded:false,items:body.items.map(itemResponse)});state.rows.push(data);}}
      }else if(/^\/dealer-returns\/\d+$/.test(endpoint)){
        data=withFlags(state.rows.find(row=>String(row.id)===endpoint.split('/').at(-1)));if(state.detailDelay)await pause(state.detailDelay);
      }else if(/^\/dealer-returns\/\d+\/(confirm|cancel)$/.test(endpoint)){
        const row=state.rows.find(row=>String(row.id)===endpoint.split('/')[2]),confirm=endpoint.endsWith('/confirm');
        const allowed=confirm?has('WAREHOUSE_APPROVE'):['ORDER_CREATE','ORDER_CANCEL','WAREHOUSE_APPROVE'].some(has);
        if(!allowed)status=403;
        else{await pause(120);status=confirm?state.confirmStatus:state.cancelStatus;if(status===200){row.status=confirm?'CONFIRMED':'CANCELLED';row.inventoryAdded=confirm;if(confirm){row.confirmedBy='quan-ly-kho';row.confirmedAt='2026-10-24T09:00:00';row.confirmNote=body.note;}else row.cancelReason=body.reason;data=withFlags(row);}}
      }else{unexpected.push({endpoint,method});status=404;message='Unexpected fixture API';}
      if(status>=400)message='Lỗi kiểm thử '+status;
      await route.fulfill({status,headers,contentType:'application/json',body:JSON.stringify({success:status<400,data,message})}).catch(()=>{});
    });
    const page=await context.newPage();page.on('pageerror',error=>errors.push(error.message));page.setDefaultTimeout(12000);
    await page.goto(base+'/warehouse/dealer-returns');if(admin||permissions.length)await page.locator('#dr-count').filter({hasText:'phiếu'}).waitFor();
    const grant=async permissions=>{state.permissions=permissions;await page.evaluate(async()=>Auth.refreshPermissions());};
    async function prepare(){await page.locator('#dr-new').waitFor({state:'visible'});await page.locator('#dr-new').click();await page.locator('[data-invoice="10"]').click();await page.locator('#dr-editor').waitFor({state:'visible'});}
    async function add(id=101,quantity='1',reason='EXCHANGE'){
      await page.locator(`[data-add-line="${id}"]`).click();let line=page.locator('[data-draft]').last();const key=await line.getAttribute('data-draft');
      line=page.locator(`[data-draft="${key}"]`);await line.locator('[data-field="quantity"]').fill(quantity);await line.locator('[data-field="reason"]').selectOption(reason);
      if(id===101)await line.locator('[data-field="lotNumber"]').selectOption('LO-01');
      return line;
    }
    async function open(){await page.locator('[data-return="1"]').click();await page.locator('#dr-detail').waitFor({state:'visible'});}
    async function submit(){await page.locator('#dr-create-check').check();await page.locator('#dr-save').click();}
    return {context,page,state,prepare,add,open,submit,grant};
  }
  const check=async(name,run)=>{await run();cases++;console.log('PASS '+name);};
  try{
    await check('Sales CREATE-only uses narrow existing endpoints, exact unit quantities, split lines and no stock increment',async()=>{
      const s=await setup(['ORDER_CREATE']);await s.prepare();await s.add(101,'1,2500');await s.add(101,'0.7500');await s.page.locator('#dr-create-check').check();
      await s.page.evaluate(()=>{const form=document.getElementById('dr-create-form');form.dispatchEvent(new Event('submit',{cancelable:true}));form.dispatchEvent(new Event('submit',{cancelable:true}));});
      await s.page.locator('#dr-success').filter({hasText:'PTH-002'}).waitFor();const posts=s.state.calls.filter(call=>call.method==='POST');assert.equal(posts.length,1);
      assert.equal(posts[0].body.items[0].returnQuantity,'1.2500');assert.equal(posts[0].body.items[1].returnQuantity,'0.7500');assert.equal(posts[0].body.items[0].expiryDate,'2027-12-31');
      assert.deepEqual(Object.keys(posts[0].body).sort(),['invoiceId','items','note','warehouseId']);assert.equal(s.state.rows.at(-1).inventoryAdded,false);
      assert.equal(await s.page.locator('#dr-confirm-open').isVisible(),false);assert.ok(s.state.calls.every(call=>call.auth==='Bearer '+token));assert.ok(!s.state.calls.some(call=>call.endpoint.startsWith('/invoices')||call.endpoint.startsWith('/warehouses')));await s.context.close();
    });
    await check('Cumulative cap includes pending returns; zero and five-decimal quantities are blocked',async()=>{
      const s=await setup(['ORDER_CREATE']);await s.prepare();await s.add(101,'3');let line=await s.add(101,'3.0001');await s.submit();await s.page.locator('#dr-create-error').filter({hasText:'vượt 6'}).waitFor();
      await line.locator('[data-field="quantity"]').fill('0');await s.submit();await s.page.locator('#dr-create-error').filter({hasText:'lớn hơn 0'}).waitFor();
      await line.locator('[data-field="quantity"]').fill('1.00001');await s.submit();await s.page.locator('#dr-create-error').filter({hasText:'4 số thập phân'}).waitFor();assert.equal(s.state.calls.filter(c=>c.method==='POST').length,0);await s.context.close();
    });
    await check('Missing reason, wrong lot and damaged warehouse cannot be submitted',async()=>{
      const s=await setup(['ORDER_CREATE']);await s.prepare();await s.page.locator('[data-add-line="101"]').click();let line=s.page.locator('[data-draft]').last();await line.locator('[data-field="quantity"]').fill('1');await s.submit();assert.equal(s.state.calls.filter(c=>c.method==='POST').length,0);
      await line.locator('[data-field="reason"]').selectOption('DAMAGED');assert.equal(await line.locator('[data-field="defective"]').inputValue(),'true');assert.equal(await line.locator('[data-field="defective"]').isDisabled(),true);
      await line.locator('[data-field="lotNumber"]').selectOption('LO-01');await s.submit();assert.equal(s.state.calls.filter(c=>c.method==='POST').length,0);
      await line.locator('[data-field="defectiveWarehouseId"]').selectOption('2');await s.submit();await s.page.locator('#dr-success').filter({hasText:'PTH-002'}).waitFor();
      const sent=s.state.calls.find(c=>c.method==='POST').body.items[0];assert.equal(sent.defective,true);assert.equal(sent.targetWarehouseId,2);await s.context.close();
    });
    await check('Actual manual lot requires real expiry; OTHER reason requires explanation',async()=>{
      const s=await setup(['ORDER_CREATE']);await s.prepare();const line=await s.add(103,'1','OTHER');await line.locator('[data-field="lotNumber"]').fill('LO-THUC-TE');await s.submit();assert.equal(s.state.calls.filter(c=>c.method==='POST').length,0);
      await line.locator('[data-field="expiryDate"]').fill('2027-06-30');await line.locator('[data-field="manufacturingDate"]').fill('2025-06-30');await s.submit();assert.equal(s.state.calls.filter(c=>c.method==='POST').length,0);
      await line.locator('[data-field="otherReason"]').fill('Khách đổi nguyên kiện');await s.submit();await s.page.locator('#dr-success').filter({hasText:'PTH-002'}).waitFor();
      const sent=s.state.calls.find(c=>c.method==='POST').body.items[0];assert.equal(sent.lotNumber,'LO-THUC-TE');assert.equal(sent.expiryDate,'2027-06-30');assert.equal(sent.returnReason,'Khách đổi nguyên kiện');await s.context.close();
    });
    await check('Warehouse confirmation posts once, honors flags and reflects server stock result only',async()=>{
      const s=await setup(['WAREHOUSE_APPROVE']);await s.open();assert.equal(await s.page.locator('#dr-new').isVisible(),false);await s.page.locator('#dr-confirm-open').click();await s.page.locator('#dr-confirm-check').check();await s.page.locator('#dr-confirm-note').fill('Đã nhận đủ theo phiếu');
      await s.page.evaluate(()=>{const form=document.getElementById('dr-confirm-form');form.dispatchEvent(new Event('submit',{cancelable:true}));form.dispatchEvent(new Event('submit',{cancelable:true}));});
      await s.page.locator('#dr-success').filter({hasText:'Đã cộng tồn'}).waitFor();assert.equal(s.state.calls.filter(c=>c.method==='POST').length,1);assert.equal(await s.page.locator('#dr-confirm-open').isVisible(),false);assert.equal(await s.page.locator('#dr-cancel-open').isVisible(),false);await s.context.close();
    });
    await check('Read-only IMPORT never calls eligible source, even when detail is viewed',async()=>{
      const s=await setup(['WAREHOUSE_IMPORT']);await s.open();assert.equal(await s.page.locator('#dr-new').isVisible(),false);assert.equal(await s.page.locator('#dr-view-invoice').isVisible(),false);assert.equal(await s.page.locator('#dr-confirm-open').isVisible(),false);assert.equal(await s.page.locator('#dr-cancel-open').isVisible(),false);
      assert.ok(!s.state.calls.some(c=>c.endpoint.includes('/eligible-invoices')||c.method==='POST'));await s.context.close();
    });
    await check('Server flags restrict actions; ADMIN has matrix access without role-specific fallbacks',async()=>{
      const s=await setup([],true);s.state.optionsCanCreate=false;s.state.optionsCanConfirm=false;s.state.detailCanCancel=false;await s.page.locator('#dr-reload').click();await s.open();
      assert.equal(await s.page.locator('#dr-new').isVisible(),false);assert.equal(await s.page.locator('#dr-confirm-open').isVisible(),false);assert.equal(await s.page.locator('#dr-cancel-open').isVisible(),false);await s.context.close();
    });
    await check('Cancellation requires reason and remains a separate non-stock action',async()=>{
      const s=await setup(['ORDER_VIEW','ORDER_CANCEL']);await s.open();await s.page.locator('#dr-cancel-open').click();await s.page.locator('#dr-cancel-check').check();await s.page.locator('#dr-cancel-save').click();assert.equal(s.state.calls.filter(c=>c.method==='POST').length,0);
      await s.page.locator('#dr-cancel-reason').fill('Đại lý không trả nữa');await s.page.locator('#dr-cancel-save').click();await s.page.locator('#dr-success').filter({hasText:'Đã hủy'}).waitFor();
      assert.equal(s.state.rows[0].status,'CANCELLED');assert.equal(s.state.rows[0].inventoryAdded,false);assert.deepEqual(s.state.calls.find(c=>c.method==='POST').body,{reason:'Đại lý không trả nữa'});await s.context.close();
    });
    await check('400 retains draft for correction; explicit source refresh checks new remaining quantities',async()=>{
      const s=await setup(['ORDER_CREATE']);s.state.createStatus=400;await s.prepare();await s.add(101,'5');await s.submit();await s.page.locator('#dr-create-error').filter({hasText:'400'}).waitFor();assert.equal(await s.page.locator('[data-field="quantity"]').inputValue(),'5');
      s.state.invoices[0].items[0].returnableQuantity='2';await s.page.locator('#dr-invoice-refresh').click();await s.page.locator('#dr-create-error').filter({hasText:'Đã cập nhật'}).waitFor();await s.submit();await s.page.locator('#dr-create-error').filter({hasText:'vượt 2'}).waitFor();assert.equal(s.state.calls.filter(c=>c.method==='POST').length,1);await s.context.close();
    });
    await check('Unknown write result blocks retries and 409 refreshes rather than repeating stock update',async()=>{
      const s=await setup(['ORDER_CREATE']);s.state.createStatus=500;await s.prepare();await s.add();await s.submit();await s.page.locator('#dr-error').filter({hasText:'Chưa xác định'}).waitFor();assert.equal(await s.page.locator('#dr-save').isDisabled(),true);assert.equal(s.state.calls.filter(c=>c.method==='POST').length,1);await s.context.close();
      const t=await setup(['WAREHOUSE_APPROVE']);t.state.confirmStatus=409;await t.open();await t.page.locator('#dr-confirm-open').click();await t.page.locator('#dr-confirm-check').check();await t.page.locator('#dr-confirm-save').click();await t.page.locator('#dr-error').filter({hasText:'Phiếu đã thay đổi'}).waitFor();assert.equal(t.state.calls.filter(c=>c.method==='POST').length,1);await t.context.close();
    });
    await check('Filter names match actual controller; list/invoice pagination stays server-side',async()=>{
      const s=await setup();for(let i=2;i<=23;i++)s.state.rows.push({...s.state.rows[0],id:i,returnCode:'PTH-'+i});await s.page.locator('#dr-reload').click();await s.page.locator('#dr-page').filter({hasText:'1/2'}).waitFor();assert.equal(await s.page.locator('#dr-list tr').count(),20);await s.page.locator('#dr-next').click();await s.page.locator('#dr-page').filter({hasText:'2/2'}).waitFor();assert.equal(await s.page.locator('#dr-list tr').count(),3);
      await s.page.locator('#dr-from').fill('2026-10-20');await s.page.locator('#dr-to').fill('2026-10-24');await s.page.locator('#dr-filter-save').click();await s.page.locator('#dr-count').filter({hasText:'phiếu'}).waitFor();assert.ok(s.state.calls.some(c=>c.query.includes('fromDate=2026-10-20')&&c.query.includes('toDate=2026-10-24')));assert.ok(!s.state.calls.some(c=>c.query.includes('startDate=')));
      for(let i=12;i<=34;i++)s.state.invoices.push({...s.state.invoices[0],invoiceId:i,invoiceCode:'HD-'+i});await s.page.locator('#dr-new').click();await s.page.locator('#dr-invoice-page').filter({hasText:'1/2'}).waitFor();await s.page.locator('#dr-invoice-next').click();await s.page.locator('#dr-invoice-page').filter({hasText:'2/2'}).waitFor();await s.context.close();
    });
    await check('API failures are not empty success; unsafe text stays text and source is read-only',async()=>{
      const s=await setup(['ORDER_VIEW']);await s.open();assert.equal(await s.page.locator('#dr-detail-body script,#dr-detail-body img').count(),0);await s.page.locator('#dr-view-invoice').click();await s.page.locator('#dr-source-body table').waitFor();assert.equal(await s.page.locator('#dr-source-body img').count(),0);assert.equal(await s.page.evaluate(()=>window.__returnXss),undefined);
      s.state.listStatus=500;s.state.optionsStatus=500;await s.page.locator('#dr-reload').click();await s.page.locator('#dr-error').filter({hasText:'500'}).waitFor();await s.page.locator('#dr-options-error').filter({hasText:'500'}).waitFor();assert.equal(await s.page.locator('#dr-new').isVisible(),false);await s.context.close();
    });
    await check('Revocation discards drafts and stale source/detail; grant restores current UI',async()=>{
      const s=await setup();await s.prepare();await s.add();await s.grant(['ORDER_VIEW']);assert.equal(await s.page.locator('#dr-create').isVisible(),false);assert.equal(await s.page.locator('[data-draft]').count(),0);assert.equal(await s.page.locator('#dr-new').isVisible(),false);
      await s.grant(['ORDER_CREATE','ORDER_VIEW']);await s.page.locator('#dr-new').waitFor({state:'visible'});await s.page.locator('#dr-new').click();s.state.sourceDelay=400;await s.page.locator('[data-invoice="10"]').click();await pause(60);await s.grant(['ORDER_VIEW']);await pause(500);assert.equal(await s.page.locator('#dr-editor').isVisible(),false);
      await s.page.locator('#dr-count').filter({hasText:'phiếu'}).waitFor();s.state.detailDelay=400;await s.page.locator('[data-return="1"]').click();await pause(60);await s.grant(['WAREHOUSE_IMPORT']);await pause(500);assert.equal(await s.page.locator('#dr-detail').isVisible(),false);
      await s.grant([]);await s.page.waitForURL('**/error?status=403');assert.equal(await s.page.locator('a[href="/warehouse/dealer-returns"]').count(),0);await s.context.close();
    });
    await check('Shared shell and draft are usable on desktop, tablet and 320px mobile',async()=>{
      const s=await setup();await s.prepare();await s.add();await s.add(102,'1','DAMAGED');await s.page.locator('[data-draft]').last().locator('[data-field="defectiveWarehouseId"]').selectOption('2');
      for(const width of [1440,768,390,320]){await s.page.setViewportSize({width,height:1000});await s.page.evaluate(()=>window.scrollTo(0,0));await pause(70);const layout=await s.page.evaluate(()=>({width:document.documentElement.clientWidth,scroll:document.documentElement.scrollWidth,content:document.querySelector('main.content').getBoundingClientRect().toJSON(),outer:document.querySelector('.main-area').getBoundingClientRect().toJSON()}));assert.ok(layout.scroll<=layout.width+1,JSON.stringify(layout));assert.ok(Math.abs((layout.content.left-layout.outer.left)-(layout.outer.right-layout.content.right))<=2,JSON.stringify(layout));await s.page.screenshot({path:path.join(output,'dealer-returns-'+width+'.png'),fullPage:true});}
      await s.context.close();
    });
    assert.deepEqual(errors,[]);assert.deepEqual(unexpected,[]);console.log(`${cases} S8-01 browser scenarios passed. Screenshots: ${output}`);
  }finally{await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
