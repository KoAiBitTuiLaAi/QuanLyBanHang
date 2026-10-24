// Actual Chrome and app code; isolated HTTP fixtures, no business DB writes.
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),os=require('node:os');
const {chromium}=require('playwright'),U=require('../frontend/js/debt-adjustment-utils.js'),F=require('./financial-returns-fixture.cjs');
const base=process.env.NAV_TEST_URL||'http://127.0.0.1:5539',output=path.join(os.tmpdir(),'qlbh-s802'),pause=ms=>new Promise(resolve=>setTimeout(resolve,ms));
const token='fixture.'+Buffer.from(JSON.stringify({exp:Math.floor(Date.now()/1000)+3600})).toString('base64url')+'.fixture';
(async()=>{
 fs.mkdirSync(output,{recursive:true});const browser=await chromium.launch({channel:'chrome',headless:true});const errors=[],unexpected=[];let cases=0;
 async function setup(permissions=['INVOICE_VIEW','INVOICE_ADJUST'],admin=false){
  const state={permissions:[...permissions],calls:[],rows:[F.detail()],source:F.source(),createStatus:200,confirmStatus:200,listStatus:200,sourceDelay:0,detailDelay:0,postDelay:100,totalSource:1,confirmedQty:0n,confirmedAmount:0n};
  const roles=admin?['ROLE_ADMIN']:['ROLE_VIEWER'],user={id:8,username:'financial-fixture',fullName:'Nhân viên kiểm thử',roles,permissions:state.permissions,warehouses:[],territories:[]};
  const context=await browser.newContext({viewport:{width:1440,height:1000},reducedMotion:'reduce'});
  await context.addInitScript(({user,token})=>{if(!sessionStorage.getItem('accessToken')){sessionStorage.setItem('user',JSON.stringify(user));sessionStorage.setItem('accessToken',token);}},{user,token});
  await context.route('http://localhost:8080/api/**',async route=>{
   const req=route.request(),url=new URL(req.url()),endpoint=url.pathname.slice(4),method=req.method(),body=req.postData()?req.postDataJSON():null;
   const headers={'Access-Control-Allow-Origin':base,'Access-Control-Allow-Headers':'Authorization,Content-Type','Access-Control-Allow-Methods':'GET,POST,OPTIONS'};
   if(method==='OPTIONS')return route.fulfill({status:204,headers});state.calls.push({endpoint,query:url.search,method,body,auth:req.headers().authorization});let data,status=200;
   const can=p=>admin||state.permissions.includes(p),source=()=>{const data=structuredClone(state.source),row=data.items[0];row.confirmedReturnQuantity=String(state.confirmedQty/10000n)+'.'+String(state.confirmedQty%10000n).padStart(4,'0');row.remainingQuantity=String((30000n-state.confirmedQty)/10000n)+'.'+String((30000n-state.confirmedQty)%10000n).padStart(4,'0');row.previouslyCreditedAmount=String(state.confirmedAmount/100n)+'.'+String(state.confirmedAmount%100n).padStart(2,'0');return data;};
   const credit=quantity=>{const cumulative=state.confirmedQty+U.decimal(quantity);const rounded=(10000n*cumulative+15000n)/30000n;const value=rounded-state.confirmedAmount;return `${value/100n}.${String(value%100n).padStart(2,'0')}`;};
   if(endpoint==='/auth/permissions')data={roles,permissions:state.permissions};
   else if(endpoint==='/invoices/returns'&&method==='GET'){
    if(!can('INVOICE_VIEW'))status=403;else{status=state.listStatus;const rows=state.rows.filter(row=>(!url.searchParams.get('status')||row.status===url.searchParams.get('status'))&&(!url.searchParams.get('dealerId')||String(row.dealerId)===url.searchParams.get('dealerId'))),number=Number(url.searchParams.get('page')||0);data={content:rows.slice(number*20,number*20+20),number,totalPages:Math.ceil(rows.length/20),totalElements:rows.length};}
   }else if(endpoint==='/invoices/returns/eligible-invoices'){
    if(!can('INVOICE_ADJUST'))status=403;else{const number=Number(url.searchParams.get('page')||0),rows=Array.from({length:state.totalSource},(_,i)=>({invoiceId:10+i,invoiceCode:'HD-'+(i+1),invoiceNumber:String(i+1),dealerId:20,dealerCode:'DL20',dealerName:'Đại lý Minh Phát'}));data={content:rows.slice(number*20,number*20+20),number,totalPages:Math.ceil(rows.length/20),totalElements:rows.length};}
   }else if(/^\/invoices\/returns\/eligible-invoices\/\d+$/.test(endpoint)){
    if(!can('INVOICE_ADJUST'))status=403;else{data=source();await pause(state.sourceDelay);}
   }else if(endpoint==='/invoices/returns'&&method==='POST'){
    if(!can('INVOICE_ADJUST'))status=403;else{await pause(state.postDelay);status=state.createStatus;if(status===200){status=201;data=F.detail(state.rows.length+1,'DRAFT',body.items[0].returnQuantity,credit(body.items[0].returnQuantity));data.returnReason=body.returnReason;data.note=body.note;state.rows.push(data);}}
   }else if(/^\/invoices\/returns\/\d+\/(confirm|cancel)$/.test(endpoint)){
    if(!can('INVOICE_ADJUST'))status=403;else{await pause(state.postDelay);const row=state.rows.find(item=>String(item.id)===endpoint.split('/')[3]);if(row.status!=='DRAFT')status=409;else if(endpoint.endsWith('/confirm')){status=state.confirmStatus;if(status===200){row.status='CONFIRMED';row.statusLabel='Đã xác nhận';row.totalReturnAmount=credit(row.items[0].returnQuantity);row.items[0].returnAmount=row.totalReturnAmount;row.invoiceAdjustment={id:100+row.id,invoiceId:10,adjustmentCode:'DCTH-'+row.id,adjustmentType:'DECREASE',adjustmentAmount:'-'+row.totalReturnAmount};state.confirmedQty+=U.decimal(row.items[0].returnQuantity);state.confirmedAmount+=U.decimal(row.totalReturnAmount,2);row.currentDebtAfter='66.67';row.remainingLimitAfter='133.33';data=row;}}else{row.status='CANCELLED';row.statusLabel='Đã hủy';row.cancelReason=body.cancelReason;data=row;}}
   }else if(/^\/invoices\/returns\/\d+$/.test(endpoint)){
    if(!can('INVOICE_VIEW'))status=403;else{data=structuredClone(state.rows.find(row=>String(row.id)===endpoint.split('/').at(-1)));await pause(state.detailDelay);}
   }else{unexpected.push({endpoint,method});status=404;}
   await route.fulfill({status,headers,contentType:'application/json',body:JSON.stringify({success:status<400,data,message:status<400?'OK':'Lỗi kiểm thử '+status})}).catch(()=>{});
  });
  const page=await context.newPage();page.setDefaultTimeout(12000);page.on('pageerror',error=>errors.push(error.message));page.on('dialog',dialog=>dialog.accept());await page.goto(base+'/finance/debt-adjustment');
  const canRead=admin||permissions.includes('INVOICE_VIEW'),canWrite=admin||permissions.includes('INVOICE_ADJUST');
  if(canRead)await page.locator('#fr-count').filter({hasText:'phiếu'}).waitFor();else if(canWrite)await page.locator('#fr-new').waitFor({state:'visible'});
  const prepare=async()=>{await page.locator('#fr-new').click();await page.locator('[data-source="10"]').click();await page.locator('#fr-create-form').waitFor({state:'visible'});};
  const fill=async(quantity='1')=>{await page.locator('[data-qty="101"]').fill(quantity);await page.locator('#fr-reason').fill('Trả hàng thực tế');await page.locator('#fr-create-ack').check();};
  const save=async()=>{await page.locator('#fr-save').click();await page.locator('#fr-success').filter({hasText:'Đã lưu'}).waitFor();};
  const open=async()=>{await page.locator('[data-return="1"]').click();await page.locator('#fr-detail').waitFor({state:'visible'});};
  const grant=async permissions=>{state.permissions=permissions;await page.evaluate(async()=>Auth.refreshPermissions());};
  return {state,page,context,prepare,fill,save,open,grant};
 }
 const check=async(name,run)=>{await run();cases++;console.log('PASS '+name);};
 try{
  await check('Persist actual draft from exact invoice item and show server credit; no fabricated return quantity or catalog calls',async()=>{
   const s=await setup();await s.prepare();assert.equal(await s.page.locator('[data-qty="101"]').inputValue(),'');await s.fill('1,0000');await s.save();
   const post=s.state.calls.find(call=>call.method==='POST');assert.deepEqual(post.body,{invoiceId:10,returnReason:'Trả hàng thực tế',note:'',items:[{invoiceItemId:101,returnQuantity:'1.0000'}]});assert.equal(s.state.rows.at(-1).status,'DRAFT');assert.match(await s.page.locator('#fr-detail-info').textContent(),/33,33 ₫/);assert.match(await s.page.locator('#fr-adjustment').textContent(),/chưa sinh/);assert.ok(s.state.calls.every(call=>call.auth==='Bearer '+token));await s.context.close();
  });
  await check('Confirmation posts once, creates immutable linked DECREASE and shows authoritative live balances',async()=>{
   const s=await setup();await s.prepare();await s.fill();await s.save();await s.page.locator('#fr-confirm').click();assert.equal(s.state.calls.filter(call=>call.endpoint.endsWith('/confirm')).length,0);await s.page.locator('#fr-confirm-ack').check();
   await s.page.evaluate(()=>{const button=document.getElementById('fr-confirm');button.dispatchEvent(new Event('click'));button.dispatchEvent(new Event('click'));});await s.page.locator('#fr-success').filter({hasText:'Đã xác nhận'}).waitFor();assert.equal(s.state.calls.filter(call=>call.endpoint.endsWith('/confirm')).length,1);assert.match(await s.page.locator('#fr-adjustment').textContent(),/DCTH-2/);assert.match(await s.page.locator('#fr-balances').textContent(),/66,67 ₫.*133,33 ₫/);assert.equal(await s.page.locator('#fr-draft-actions').isVisible(),false);await s.context.close();
  });
  await check('Quantity precision, zero, reason and capacity are validated before writing',async()=>{
   const s=await setup();await s.prepare();for(const quantity of ['0','4','1.00001']){await s.fill(quantity);await s.page.locator('#fr-save').click();await s.page.locator('#fr-error').waitFor({state:'visible'});assert.equal(s.state.calls.filter(call=>call.method==='POST').length,0);}await s.context.close();
  });
  await check('Server 400 preserves draft input; refresh capacity keeps inputs for correction',async()=>{
   const s=await setup();s.state.createStatus=400;await s.prepare();await s.fill('2');await s.page.locator('#fr-save').click();await s.page.locator('#fr-error').filter({hasText:'400'}).waitFor();assert.equal(await s.page.locator('[data-qty="101"]').inputValue(),'2');s.state.confirmedQty=20000n;s.state.confirmedAmount=6667n;await s.page.locator('#fr-source-refresh').click();await s.page.locator('#fr-success').filter({hasText:'Đã cập nhật'}).waitFor();assert.equal(await s.page.locator('[data-qty="101"]').inputValue(),'2');await s.page.locator('#fr-create-ack').check();await s.page.locator('#fr-save').click();await s.page.locator('#fr-error').filter({hasText:'không vượt 1'}).waitFor();await s.context.close();
  });
  await check('INVOICE_ADJUST-only can lookup/create/confirm without VIEW, FINANCE or customer permissions',async()=>{
   const s=await setup(['INVOICE_ADJUST']);assert.equal(await s.page.locator('#fr-history').isVisible(),false);await s.prepare();await s.fill();await s.save();await s.page.locator('#fr-confirm-ack').check();await s.page.locator('#fr-confirm').click();await s.page.locator('#fr-success').filter({hasText:'Đã xác nhận'}).waitFor();assert.ok(!s.state.calls.some(call=>call.method==='GET'&&/^\/invoices\/returns(?:\?|$|\/\d+$)/.test(call.endpoint)));await s.context.close();
  });
  await check('INVOICE_VIEW-only reads persisted history, never calls lookup or enables mutation',async()=>{
   const s=await setup(['INVOICE_VIEW']);await s.open();assert.equal(await s.page.locator('#fr-new').isVisible(),false);assert.equal(await s.page.locator('#fr-draft-actions').isVisible(),false);assert.ok(!s.state.calls.some(call=>call.endpoint.includes('/eligible-invoices')||call.method==='POST'));await s.context.close();
  });
  await check('Cancel persists reason only for drafts and cannot edit or confirm a cancelled return',async()=>{
   const s=await setup();await s.open();await s.page.locator('#fr-cancel').click();assert.equal(s.state.calls.filter(call=>call.method==='POST').length,0);await s.page.locator('#fr-cancel-reason').fill('Nhập nhầm hóa đơn');await s.page.locator('#fr-cancel').click();await s.page.locator('#fr-success').filter({hasText:'Đã hủy'}).waitFor();assert.equal(await s.page.locator('#fr-draft-actions').isVisible(),false);assert.deepEqual(s.state.calls.find(call=>call.endpoint.endsWith('/cancel')).body,{cancelReason:'Nhập nhầm hóa đơn'});await s.context.close();
  });
  await check('Ambiguous 500 write is blocked from retry and does not claim no transaction happened',async()=>{
   const s=await setup();s.state.createStatus=500;await s.prepare();await s.fill();await s.page.locator('#fr-save').click();await s.page.locator('#fr-error').filter({hasText:'Chưa xác định giao dịch'}).waitFor();assert.equal(await s.page.locator('#fr-save').isDisabled(),true);await s.page.evaluate(()=>document.getElementById('fr-create-form').dispatchEvent(new Event('submit',{cancelable:true})));assert.equal(s.state.calls.filter(call=>call.method==='POST').length,1);await s.context.close();
  });
  await check('Live revoke ADJUST clears drafts/details and prevents late source responses while VIEW remains',async()=>{
   const s=await setup();s.state.sourceDelay=700;await s.page.locator('#fr-new').click();await s.page.locator('[data-source="10"]').click();await pause(100);await s.grant(['INVOICE_VIEW']);await pause(800);assert.equal(await s.page.locator('#fr-new').isVisible(),false);assert.equal(await s.page.locator('#fr-editor').isVisible(),false);assert.equal(await s.page.locator('#fr-source-lines tr').count(),0);await s.context.close();
  });
  await check('Revocation during POST never paints a late success or financial balances',async()=>{
   const s=await setup();s.state.postDelay=700;await s.prepare();await s.fill();await s.page.locator('#fr-save').click();await pause(100);await s.grant(['INVOICE_VIEW']);await pause(800);assert.equal(await s.page.locator('#fr-detail').isVisible(),false);assert.equal(await s.page.locator('#fr-success').isVisible(),false);assert.equal(await s.page.locator('#fr-balances').isVisible(),false);await s.context.close();
  });
  await check('History/filter/source pages use server pagination and do not import S8-01 warehouse returns',async()=>{
   const s=await setup();for(let id=2;id<=25;id++)s.state.rows.push(F.detail(id));s.state.totalSource=25;await s.page.locator('#fr-reload').click();await s.page.locator('#fr-page').filter({hasText:'1/2'}).waitFor();await s.page.locator('#fr-next').click();await s.page.locator('#fr-page').filter({hasText:'2/2'}).waitFor();assert.equal(await s.page.locator('#fr-rows tr').count(),5);await s.page.locator('#fr-new').click();await s.page.locator('#fr-source-page').filter({hasText:'1/2'}).waitFor();await s.page.locator('#fr-source-next').click();await s.page.locator('#fr-source-page').filter({hasText:'2/2'}).waitFor();assert.ok(s.state.calls.every(call=>call.endpoint==='/auth/permissions'||call.endpoint.startsWith('/invoices/returns')));await s.context.close();
  });
  await check('New draft ignores an older pending detail; failed writes preserve inputs even when another source is clicked',async()=>{
   const s=await setup();s.state.detailDelay=600;await s.page.locator('[data-return="1"]').click();await s.prepare();await s.fill();await pause(700);assert.equal(await s.page.locator('#fr-detail').isVisible(),false);s.state.postDelay=500;s.state.createStatus=400;await s.page.locator('#fr-save').click();await s.page.locator('[data-source="10"]').click();await s.page.locator('#fr-error').filter({hasText:'400'}).waitFor();assert.equal(await s.page.locator('#fr-reason').inputValue(),'Trả hàng thực tế');assert.equal(await s.page.locator('[data-qty="101"]').inputValue(),'1');await s.context.close();
  });
  await check('Concurrent confirmation conflict refreshes authoritative detail without sending a second credit',async()=>{
   const s=await setup();await s.open();Object.assign(s.state.rows[0],F.detail(1,'CONFIRMED'));await s.page.locator('#fr-confirm-ack').check();await s.page.locator('#fr-confirm').click();await s.page.locator('#fr-error').filter({hasText:'409'}).waitFor();await s.page.locator('#fr-adjustment').filter({hasText:'DCTH-1'}).waitFor();assert.equal(await s.page.locator('#fr-draft-actions').isVisible(),false);assert.equal(s.state.calls.filter(call=>call.endpoint.endsWith('/confirm')).length,1);await s.context.close();
  });
  await check('History error is explicit, not empty success; external text cannot inject HTML',async()=>{
   const s=await setup();s.state.rows[0].returnReason='<img src=x onerror="window.__financialXss=1">';await s.open();assert.equal(await s.page.locator('#fr-detail-info img').count(),0);assert.equal(await s.page.evaluate(()=>window.__financialXss),undefined);s.state.listStatus=500;await s.page.locator('#fr-reload').click();await s.page.locator('#fr-error').filter({hasText:'500'}).waitFor();assert.match(await s.page.locator('#fr-rows').textContent(),/Không tải được/);await s.context.close();
  });
  await check('No role fallback, dealer exclusion and ADMIN bypass follow the shared matrix',async()=>{
   const denied=await setup(['FINANCE_VIEW_DEBT']);await denied.page.locator('#error-code').filter({hasText:'403'}).waitFor();assert.equal(denied.state.calls.filter(call=>call.endpoint.startsWith('/invoices/returns')).length,0);await denied.context.close();const admin=await setup([],true);await admin.open();assert.equal(await admin.page.locator('#fr-draft-actions').isVisible(),true);await admin.context.close();
  });
  await check('All input starts blank; desktop/tablet/mobile share gutters and tables scroll internally',async()=>{
   const s=await setup();await s.prepare();await s.fill();for(const width of [1920,1440,1024,768,390,320]){await s.page.setViewportSize({width,height:1000});await pause(70);const layout=await s.page.evaluate(()=>{const node=document.querySelector('.financial-returns'),rect=node.getBoundingClientRect(),outer=node.parentElement.getBoundingClientRect(),style=getComputedStyle(node);return{doc:document.documentElement.scrollWidth,viewport:document.documentElement.clientWidth,left:rect.left-outer.left+parseFloat(style.paddingLeft),right:outer.right-rect.right+parseFloat(style.paddingRight)};});assert.ok(layout.doc<=layout.viewport+1,JSON.stringify(layout));assert.ok(Math.abs(layout.left-layout.right)<2,JSON.stringify(layout));if([1440,390].includes(width))await s.page.screenshot({path:path.join(output,'editor-'+width+'.png'),fullPage:true});}await s.context.close();
  });
  assert.deepEqual(errors,[]);assert.deepEqual(unexpected,[]);console.log(JSON.stringify({passed:true,cases,output}));
 }finally{await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
