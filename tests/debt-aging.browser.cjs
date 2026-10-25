// Chrome uses real UI/scripts; most scenarios replace only the adapter transport for isolated API fixtures.
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),os=require('node:os');
const {chromium}=require('playwright'),F=require('./debt-aging-fixture.cjs'),U=require('../frontend/js/debt-aging-utils.js'),XLSX=require('../frontend/js/vendor/xlsx.full.min.js');
const base=process.env.NAV_TEST_URL||'http://127.0.0.1:5538',output=path.join(os.tmpdir(),'qlbh-s809');
const pause=ms=>new Promise(resolve=>setTimeout(resolve,ms));
const token='fixture.'+Buffer.from(JSON.stringify({exp:Math.floor(Date.now()/1000)+3600})).toString('base64url')+'.fixture';
const adapter=`const DebtAgingApi={available:true,async options({signal}){return this.read('/__test_aging/options',{signal});},async report(filters,{signal}){return this.read('/__test_aging/report?'+new URLSearchParams({asOfDate:filters.asOfDate,employeeId:filters.employeeId??'',unassignedOnly:String(filters.unassignedOnly)}),{signal});},async read(url,options){const response=await Auth.apiFetch(url,options);const body=await response.json();if(!response.ok||!body.success)throw Error(body.message||'Không tải được báo cáo');return body.data;}};`;
(async()=>{
  fs.mkdirSync(output,{recursive:true});const browser=await chromium.launch({channel:'chrome',headless:true});const errors=[],unexpected=[];let cases=0;
  async function setup(config={}){
    const state={permissions:config.permissions??['REPORT_FINANCE'],roles:config.admin?['ROLE_ADMIN']:config.roles??['ROLE_VIEWER'],calls:[],options:F.options(),optionsStatus:200,optionsDelay:0,reportStatus:200,reportDelay:30,permissionDelay:0,exportChecks:0,revokeSecondCheck:false,complete:true,size:45,hostile:false};
    const user={id:8,username:'aging-fixture',fullName:'Nhân viên kiểm thử',roles:state.roles,permissions:state.permissions,warehouses:[],territories:[]};
    const context=await browser.newContext({viewport:{width:1440,height:1000},reducedMotion:'reduce',timezoneId:'America/Los_Angeles',acceptDownloads:true});
    await context.addInitScript(({user,token})=>{if(!sessionStorage.getItem('accessToken')){sessionStorage.setItem('user',JSON.stringify(user));sessionStorage.setItem('accessToken',token);}},{user,token});
    if(config.adapter!==false)await context.route('**/js/debt-aging-api.js',route=>route.fulfill({contentType:'application/javascript',body:adapter}));
    await context.route('http://localhost:8080/api/**',async route=>{
      const req=route.request(),url=new URL(req.url()),endpoint=url.pathname.slice(4),headers={'Access-Control-Allow-Origin':base,'Access-Control-Allow-Headers':'Authorization,Content-Type','Access-Control-Allow-Methods':'GET,OPTIONS'};
      if(req.method()==='OPTIONS')return route.fulfill({status:204,headers});
      state.calls.push({endpoint,query:url.search,method:req.method(),auth:req.headers().authorization});let data,status=200;
      if(endpoint==='/auth/permissions'){await pause(state.permissionDelay);if(state.revokeSecondCheck&&++state.exportChecks===2)state.permissions=[];data={roles:state.roles,permissions:state.permissions};}
      else if(endpoint==='/reports/debt-aging/options'){data=structuredClone(state.options);await pause(state.optionsDelay);}
      else if(endpoint==='/reports/debt-aging'){
        const filters={asOfDate:url.searchParams.get('asOfDate'),employeeId:url.searchParams.get('employeeId')?Number(url.searchParams.get('employeeId')):null,unassignedOnly:url.searchParams.get('unassignedOnly')==='true'};
        status=state.reportStatus;data=F.report(filters,state.size);data.complete=state.complete;await pause(state.reportDelay);
      }
      else if(endpoint==='/__test_aging/options'){status=state.optionsStatus;data=structuredClone(state.options);await pause(state.optionsDelay);}
      else if(endpoint==='/__test_aging/report'){
        const filters={asOfDate:url.searchParams.get('asOfDate'),employeeId:url.searchParams.get('employeeId')?Number(url.searchParams.get('employeeId')):null,unassignedOnly:url.searchParams.get('unassignedOnly')==='true'};
        status=state.reportStatus;data=F.report(filters,state.size);data.complete=state.complete;
        if(state.hostile){data.dealers[0].dealerName='=HYPERLINK("https://invalid","x")';data.dealers[0].riskLabel='<img src=x onerror="window.__agingXss=1">';data.riskBasis='<script>window.__agingXss=2</script>';}
        await pause(state.reportDelay);
      }else{unexpected.push(endpoint);status=404;}
      await route.fulfill({status,headers,contentType:'application/json',body:JSON.stringify({success:status<400,data,message:status<400?'OK':'Lỗi kiểm thử '+status})}).catch(()=>{});
    });
    const page=await context.newPage();page.setDefaultTimeout(12000);page.on('pageerror',error=>errors.push(error.message));
    await page.goto(base+'/reports/debt-aging');
    const ready=async()=>{
      try{await page.waitForFunction(()=>document.getElementById('aging-load')&&!document.getElementById('aging-load').disabled);}
      catch(error){const diagnostic=await page.evaluate(()=>({url:location.href,auth:typeof Auth,api:typeof DebtAgingApi,utils:typeof DebtAgingUtils,message:document.getElementById('aging-error')?.textContent,snapshot:document.getElementById('aging-snapshot')?.textContent}));throw Error(error.message+' '+JSON.stringify({diagnostic,calls:state.calls,errors}));}
    };
    const loaded=()=>page.locator('#aging-snapshot').filter({hasText:'Dữ liệu tạo lúc'}).waitFor();
    const load=async()=>{await ready();await page.locator('#aging-load').click();await loaded();};
    const grant=async permissions=>{state.permissions=permissions;await page.evaluate(async()=>Auth.refreshPermissions());};
    return {context,page,state,ready,loaded,load,grant};
  }
  const check=async(name,run)=>{await run();cases++;console.log('PASS '+name);};
  try{
    await check('Production adapter loads options and a scoped report from the implemented S8-09 endpoints',async()=>{
      const s=await setup({adapter:false});await s.ready();assert.equal(await s.page.locator('#aging-pending').isVisible(),false);
      assert.equal(await s.page.locator('#aging-as-of').inputValue(),'2026-10-24');assert.ok(s.state.calls.some(call=>call.endpoint==='/reports/debt-aging/options'));
      await s.page.locator('#aging-employee').selectOption('1');await s.load();assert.ok(s.state.calls.some(call=>call.endpoint==='/reports/debt-aging'&&call.query.includes('employeeId=1')));
      assert.equal(await s.page.locator('#aging-count').textContent(),'15');assert.ok(s.state.calls.every(call=>call.method==='GET'&&call.auth==='Bearer '+token));
      await s.context.close();
    });
    await check('Isolated adapter fixture loads server options, no automatic report, then exact totals and server-ranked priority',async()=>{
      const s=await setup();await s.ready();assert.equal(s.state.calls.filter(c=>c.endpoint.endsWith('/report')).length,0);assert.equal(await s.page.locator('#aging-as-of').inputValue(),'2026-10-24');
      await s.load();const report=F.report();assert.equal(await s.page.locator('#aging-total').textContent(),U.money(report.totals.overdueAmount));assert.equal(await s.page.locator('#aging-count').textContent(),'45');assert.equal(await s.page.locator('#aging-oldest').textContent(),'90 ngày');
      assert.match(await s.page.locator('#aging-priority').textContent(),/Đại lý 1/);assert.doesNotMatch(await s.page.locator('#aging-priority').textContent(),/Đại lý 2/);assert.match(await s.page.locator('#aging-risk-basis').textContent(),/Tiêu chí xếp hạng từ máy chủ/);assert.match(await s.page.locator('#aging-buckets').textContent(),/1–14/);
      assert.ok(s.state.calls.every(c=>c.method==='GET'&&c.auth==='Bearer '+token));await s.context.close();
    });
    await check('Bucket/employee views, local paging and priority navigation preserve the same snapshot',async()=>{
      const s=await setup();await s.load();const before=s.state.calls.length;await s.page.locator('#aging-group').selectOption('employee');assert.equal(await s.page.locator('#aging-employees tr').count(),3);assert.match(await s.page.locator('#aging-employees').textContent(),/Chưa phân công/);
      await s.page.locator('#aging-next').click();assert.equal(await s.page.locator('#aging-page').textContent(),'Trang 2/3');await s.page.locator('#aging-next').click();assert.equal(await s.page.locator('#aging-dealers tr').count(),5);assert.equal(await s.page.locator('#aging-next').isDisabled(),true);
      await s.page.locator('[data-priority="1"]').click();assert.equal(await s.page.locator('#aging-page').textContent(),'Trang 1/3');assert.equal(await s.page.evaluate(()=>document.activeElement.dataset.dealer),'1');
      await s.page.locator('#aging-size').selectOption('50');assert.equal(await s.page.locator('#aging-dealers tr').count(),45);assert.equal(s.state.calls.length,before);await s.context.close();
    });
    await check('Excel exports all rows with exact digits and four sheets, not only the visible page',async()=>{
      const s=await setup();await s.load();const downloadEvent=s.page.waitForEvent('download');await s.page.locator('#aging-export').click();const download=await downloadEvent;assert.match(download.suggestedFilename(),/Cong-no-tuoi-no_2026-10-24_tat-ca.xlsx/);
      const destination=path.join(output,'aging-report.xlsx');await download.saveAs(destination);const book=XLSX.read(fs.readFileSync(destination),{type:'buffer'}),rows=XLSX.utils.sheet_to_json(book.Sheets['Dai ly rui ro'],{header:1});assert.equal(book.SheetNames.length,4);assert.equal(rows.length,46);assert.ok(rows[2].includes('9007199254740993.25'));assert.equal(book.Sheets['Dai ly rui ro'].I3.t,'s');
      assert.ok(!s.state.calls.some(call=>call.method!=='GET'));await s.context.close();
    });
    await check('Editing filters clears old results/Excel; employee and unassigned reports stay scoped',async()=>{
      const s=await setup();await s.load();await s.page.locator('#aging-employee').selectOption('1');assert.equal(await s.page.locator('#aging-total').textContent(),'—');assert.equal(await s.page.locator('#aging-export').isDisabled(),true);await s.load();assert.equal(await s.page.locator('#aging-count').textContent(),'15');
      assert.ok((await s.page.locator('#aging-dealers tr').allTextContents()).every(text=>text.includes('Nguyễn An')));
      await s.page.locator('#aging-employee').selectOption('unassigned');await s.load();assert.ok((await s.page.locator('#aging-dealers tr').allTextContents()).every(text=>text.includes('Chưa phân công')));assert.match(s.state.calls.at(-1).query,/unassignedOnly=true/);await s.context.close();
    });
    await check('Future/invalid employee filters never call report; server day is not the browser day',async()=>{
      const s=await setup();await s.ready();await s.page.locator('#aging-as-of').fill('2026-10-25');await s.page.evaluate(()=>document.getElementById('aging-filters').dispatchEvent(new Event('submit',{cancelable:true})));await s.page.locator('#aging-error').filter({hasText:'Không chọn ngày sau'}).waitFor();assert.equal(s.state.calls.filter(c=>c.endpoint.endsWith('/report')).length,0);
      await s.page.locator('#aging-as-of').fill('2026-10-23');await s.load();assert.match(await s.page.locator('#aging-snapshot').textContent(),/23\/10\/2026/);await s.context.close();
    });
    await check('Delayed old filters cannot repaint after a new scoped report',async()=>{
      const s=await setup();await s.ready();s.state.reportDelay=700;await s.page.locator('#aging-employee').selectOption('1');await s.page.locator('#aging-load').click();await pause(100);
      await s.page.locator('#aging-employee').selectOption('2');s.state.reportDelay=20;await s.load();await pause(800);assert.equal(await s.page.locator('#aging-count').textContent(),'15');assert.ok((await s.page.locator('#aging-dealers tr').allTextContents()).every(text=>text.includes('Trần Bình')));await s.context.close();
    });
    await check('Network/server and partial-report failures clear numbers and never enable Excel',async()=>{
      const s=await setup();await s.load();s.state.reportStatus=500;await s.page.locator('#aging-load').click();await s.page.locator('#aging-error').filter({hasText:'500'}).waitFor();assert.equal(await s.page.locator('#aging-total').textContent(),'—');assert.equal(await s.page.locator('#aging-export').isDisabled(),true);
      s.state.reportStatus=200;s.state.complete=false;await s.page.locator('#aging-load').click();await s.page.locator('#aging-error').filter({hasText:'Báo cáo chưa đầy đủ'}).waitFor();assert.equal(await s.page.locator('#aging-total').textContent(),'—');s.state.complete=true;await s.load();await s.context.close();
    });
    await check('Empty complete report displays genuine zeros, no priority and no export',async()=>{
      const s=await setup();s.state.size=0;await s.load();assert.equal(await s.page.locator('#aging-total').textContent(),'0 ₫');assert.equal(await s.page.locator('#aging-count').textContent(),'0');assert.equal(await s.page.locator('#aging-page').textContent(),'0 trang');assert.match(await s.page.locator('#aging-priority').textContent(),/Không có đại lý quá hạn/);assert.equal(await s.page.locator('#aging-export').isDisabled(),true);await s.context.close();
    });
    await check('Options failure disables filters and supports explicit retry',async()=>{
      const s=await setup();await s.ready();s.state.optionsStatus=500;await s.page.locator('#aging-reload').click();await s.page.locator('#aging-error').filter({hasText:'500'}).waitFor();assert.equal(await s.page.locator('#aging-load').isDisabled(),true);s.state.optionsStatus=200;await s.page.locator('#aging-reload').click();await s.ready();await s.context.close();
    });
    await check('Revoking REPORT_FINANCE during report drops late data; same JWT regrant restores options',async()=>{
      const s=await setup();await s.ready();s.state.reportDelay=700;await s.page.locator('#aging-load').click();await pause(100);await s.grant([]);await s.page.locator('#error-code').filter({hasText:'403'}).waitFor();await pause(800);assert.equal(await s.page.locator('#aging-workspace').count(),0);
      s.state.permissions=['REPORT_FINANCE'];await s.page.goto(base+'/reports/debt-aging');await s.ready();assert.equal(await s.page.evaluate(()=>Auth.getAccessToken()),token);assert.equal(await s.page.locator('#aging-total').textContent(),'—');await s.context.close();
    });
    await check('Export revalidates current permission before download and blocks revocation in flight',async()=>{
      const s=await setup();await s.load();let downloads=0;s.page.on('download',()=>downloads++);s.state.permissionDelay=500;await s.page.locator('#aging-export').click();await pause(100);s.state.permissions=[];await s.page.locator('#error-code').filter({hasText:'403'}).waitFor();await pause(600);assert.equal(downloads,0);await s.context.close();
    });
    await check('Filter invalidation while export checks permissions prevents a stale download',async()=>{
      const s=await setup();await s.load();let downloads=0;s.page.on('download',()=>downloads++);s.state.permissionDelay=400;await s.page.locator('#aging-export').click();await s.page.evaluate(()=>document.getElementById('aging-as-of').dispatchEvent(new Event('input')));await pause(650);assert.equal(downloads,0);assert.equal(await s.page.locator('#aging-export').isDisabled(),true);assert.equal(await s.page.locator('#aging-load').isDisabled(),false);await s.context.close();
    });
    await check('Revocation between the first and second export checks prevents download',async()=>{
      const s=await setup();await s.load();let downloads=0;s.page.on('download',()=>downloads++);s.state.revokeSecondCheck=true;await s.page.locator('#aging-export').click();await s.page.locator('#error-code').filter({hasText:'403'}).waitFor();assert.equal(s.state.exportChecks,2);assert.equal(downloads,0);await s.context.close();
    });
    await check('Rapid synthetic double click starts only one permission-checked download',async()=>{
      const s=await setup();await s.load();let downloads=0;s.page.on('download',()=>downloads++);s.state.permissionDelay=150;const event=s.page.waitForEvent('download');
      await s.page.evaluate(()=>{const button=document.getElementById('aging-export');button.dispatchEvent(new Event('click'));button.dispatchEvent(new Event('click'));});await event;await pause(400);assert.equal(downloads,1);assert.equal(await s.page.locator('#aging-export').isDisabled(),false);await s.context.close();
    });
    await check('Matrix permission, not accountant role or debt/dashboard grants; ADMIN bypass and dealer exclusion',async()=>{
      for(const permissions of [[],['FINANCE_VIEW_DEBT','FINANCE_MANAGE','DASHBOARD_VIEW']]){const s=await setup({permissions,roles:['ROLE_ACCOUNTANT']});await s.page.locator('#error-code').filter({hasText:'403'}).waitFor();assert.equal(s.state.calls.filter(c=>c.endpoint.startsWith('/__test_aging')).length,0);await s.context.close();}
      const admin=await setup({permissions:[],admin:true});await admin.load();assert.equal(await admin.page.locator('[data-page="debt-aging"]').count(),1);await admin.context.close();
      const dealer=await setup({permissions:['REPORT_FINANCE'],roles:['ROLE_DEALER']});await dealer.page.locator('#error-code').filter({hasText:'403'}).waitFor();assert.equal(dealer.state.calls.filter(c=>c.endpoint.startsWith('/__test_aging')).length,0);await dealer.context.close();
    });
    await check('Untrusted labels are text in HTML and Excel, never active scripts/formulas',async()=>{
      const s=await setup();s.state.hostile=true;await s.load();assert.equal(await s.page.locator('#aging-priority img,#aging-dealers img,#aging-risk-basis script').count(),0);assert.equal(await s.page.evaluate(()=>window.__agingXss),undefined);assert.match(await s.page.locator('#aging-dealers').textContent(),/<img/);
      const event=s.page.waitForEvent('download');await s.page.locator('#aging-export').click();const download=await event;const destination=path.join(output,'hostile-text.xlsx');await download.saveAs(destination);const sheet=XLSX.read(fs.readFileSync(destination),{type:'buffer'}).Sheets['Dai ly rui ro'];assert.equal(sheet.C2.t,'s');assert.match(sheet.C2.v,/^=HYPERLINK/);assert.equal(sheet.C2.f,undefined);await s.context.close();
    });
    await check('Shared gutters and no document overflow on desktop/tablet/mobile, wide reports scroll inside',async()=>{
      const s=await setup();await s.load();for(const width of [1920,1440,1024,768,390,320]){
        await s.page.setViewportSize({width,height:1000});await s.page.locator('#aging-group').selectOption('employee');await pause(70);
        const layout=await s.page.evaluate(()=>{const node=document.querySelector('.aging-page'),rect=node.getBoundingClientRect(),outer=node.parentElement.getBoundingClientRect(),style=getComputedStyle(node);return{doc:document.documentElement.scrollWidth,viewport:document.documentElement.clientWidth,left:rect.left-outer.left+parseFloat(style.paddingLeft),right:outer.right-rect.right+parseFloat(style.paddingRight)};});
        assert.ok(layout.doc<=layout.viewport+1,JSON.stringify(layout));assert.ok(Math.abs(layout.left-layout.right)<2,JSON.stringify(layout));if([1440,390].includes(width))await s.page.screenshot({path:path.join(output,'report-fixture-'+width+'.png'),fullPage:true});
      }await s.context.close();
    });
    assert.deepEqual(errors,[]);assert.deepEqual(unexpected,[]);console.log(JSON.stringify({passed:true,cases,output,backendConnected:false}));
  }finally{await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
