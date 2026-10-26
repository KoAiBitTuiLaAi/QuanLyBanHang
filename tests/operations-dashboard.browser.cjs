// Real Chrome / application scripts; read-only mocked HTTP, no real DB writes.
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),os=require('node:os');
const {chromium}=require('playwright'),F=require('./operations-dashboard-fixture.cjs');
const base=process.env.NAV_TEST_URL||'http://127.0.0.1:5537',output=path.join(os.tmpdir(),'qlbh-s805');
const pause=ms=>new Promise(resolve=>setTimeout(resolve,ms));
const token='fixture.'+Buffer.from(JSON.stringify({exp:Math.floor(Date.now()/1000)+3600})).toString('base64url')+'.fixture';
(async()=>{
  fs.mkdirSync(output,{recursive:true});const browser=await chromium.launch({channel:'chrome',headless:true});
  const errors=[],unexpected=[],timings=[];let cases=0;
  async function setup(options={}){
    const state={permissions:options.permissions??['DASHBOARD_VIEW'],roles:options.admin?['ROLE_ADMIN']:options.roles??['ROLE_VIEWER'],calls:[],data:options.data??F.overview(),overviewDelay:options.delay??50,pageDelay:0,status:200,pageStatus:200,invalid:false,total:options.data?.totalOverdueDealers??45,huge:!!options.huge,sequence:0};
    const context=await browser.newContext({viewport:{width:1440,height:1000},reducedMotion:'reduce',timezoneId:'America/Los_Angeles'});
    const user={id:8,username:'dashboard-fixture',fullName:'Nhân viên kiểm thử',roles:state.roles,permissions:state.permissions,warehouses:[],territories:[]};
    await context.addInitScript(({user,token})=>{if(!sessionStorage.getItem('accessToken')){sessionStorage.setItem('user',JSON.stringify(user));sessionStorage.setItem('accessToken',token);}window.__opsEvents=[];window.addEventListener('operations-dashboard:ready',e=>window.__opsEvents.push(e.detail));},{user,token});
    await context.route('http://localhost:8080/api/**',async route=>{
      const req=route.request(),url=new URL(req.url()),endpoint=url.pathname.slice(4),method=req.method();
      const headers={'Access-Control-Allow-Origin':base,'Access-Control-Allow-Headers':'Authorization,Content-Type','Access-Control-Allow-Methods':'GET,OPTIONS'};
      if(method==='OPTIONS')return route.fulfill({status:204,headers});
      state.calls.push({endpoint,method,query:url.search,auth:req.headers().authorization});
      let data,status=200,huge=false;
      if(endpoint==='/auth/permissions')data={roles:state.roles,permissions:state.permissions};
      else if(endpoint==='/reports/dashboard'){
        status=state.status;data=structuredClone(state.data);huge=state.huge;
        data.overdueDealers=Array.from({length:Math.min(Number(url.searchParams.get('overdueLimit')),data.totalOverdueDealers)},(_,i)=>data.overdueDealers[i]??F.dealer(i));
        state.sequence++;if(state.invalid)delete data.metrics;await pause(state.overviewDelay);
      }else if(endpoint==='/reports/dashboard/overdue-dealers'){
        status=state.pageStatus;const number=Number(url.searchParams.get('page')),size=Number(url.searchParams.get('size')),total=state.total;
        data={number,totalPages:Math.ceil(total/size),totalElements:total,content:Array.from({length:Math.max(0,Math.min(size,total-number*size))},(_,i)=>F.dealer(number*size+i))};await pause(state.pageDelay);
      }else if(endpoint==='/users/profile'||endpoint==='/auth/me')data=user;
      else if(['/finance/debt','/finance/debt/flagged-dealers','/dealers/active'].includes(endpoint))data=[];
      else if(endpoint==='/warehouse/stock-alerts')data={content:[],totalElements:0,totalPages:0};
      else{unexpected.push({endpoint,method});status=404;}
      let body=JSON.stringify({success:status<400,data,message:status<400?'Thành công':'Lỗi kiểm thử '+status});
      // Exercise the real BigDecimal JSON numeric shape, beyond JS safe integer.
      if(huge)body=body.replace('"todaySales":"3500000.25"','"todaySales":9007199254740993.25');
      await route.fulfill({status,headers,contentType:'application/json',body}).catch(()=>{});
    });
    const page=await context.newPage();page.setDefaultTimeout(12000);page.on('pageerror',error=>errors.push(error.message));
    await page.goto(base+(options.route??'/reports/dashboard'));
    const ready=()=>page.locator('#ops-performance').filter({hasText:'giây'}).waitFor();
    const grant=async permissions=>{state.permissions=permissions;await page.evaluate(async()=>Auth.refreshPermissions());};
    return {context,page,state,ready,grant};
  }
  const check=async(name,run)=>{await run();cases++;console.log('PASS '+name);};
  try{
    await check('Six exact KPIs, one initial aggregate GET, full server month and unassigned territory',async()=>{
      const s=await setup({huge:true});await s.ready();
      assert.equal(await s.page.locator('#ops-today-sales').textContent(),'9.007.199.254.740.993,25 ₫');
      for(const [id,text]of [['ops-month-sales','125.000.000,75 ₫'],['ops-pending-approval','6'],['ops-pending-export','8'],['ops-overdue-debt','21.000.000 ₫'],['ops-low-stock','3']])assert.equal(await s.page.locator('#'+id).textContent(),text);
      assert.equal(await s.page.locator('#ops-daily-chart circle').count(),31);assert.equal(await s.page.locator('#ops-daily-chart .ops-future').count(),7);assert.equal(await s.page.locator('#ops-daily-table tr').count(),31);
      assert.match(await s.page.locator('#ops-today-label').textContent(),/24\/10\/2026/);assert.match(await s.page.locator('#ops-snapshot').textContent(),/Asia\/Ho_Chi_Minh/);
      assert.equal(await s.page.locator('.ops-territory').count(),3);assert.match(await s.page.locator('#ops-territory-chart').textContent(),/Chưa phân khu vực/);
      assert.equal(s.state.calls.filter(c=>c.endpoint==='/reports/dashboard').length,1);assert.equal(s.state.calls.filter(c=>c.endpoint.endsWith('/overdue-dealers')).length,0);
      assert.ok(s.state.calls.every(c=>c.method==='GET'&&c.auth==='Bearer '+token));
      const timing=await s.page.evaluate(()=>window.__opsEvents.at(-1));assert.ok(timing.navigationMs<2000,JSON.stringify(timing));timings.push(timing);await s.context.close();
    });
    await check('Descending overdue dealers, next/previous and page size use server pagination',async()=>{
      const s=await setup();await s.ready();assert.equal(await s.page.locator('#ops-dealers tr').count(),20);assert.equal(await s.page.locator('#ops-page').textContent(),'Trang 1/3');
      await s.page.locator('#ops-next').click();await s.page.locator('#ops-page').filter({hasText:'2/3'}).waitFor();assert.match(await s.page.locator('#ops-dealers tr').first().textContent(),/DL-21/);
      await s.page.locator('#ops-next').click();await s.page.locator('#ops-page').filter({hasText:'3/3'}).waitFor();assert.equal(await s.page.locator('#ops-dealers tr').count(),5);assert.equal(await s.page.locator('#ops-next').isDisabled(),true);
      await s.page.locator('#ops-prev').click();await s.page.locator('#ops-page').filter({hasText:'2/3'}).waitFor();
      await s.page.locator('#ops-size').selectOption('10');await s.page.locator('#ops-page').filter({hasText:'1/5'}).waitFor();assert.equal(await s.page.locator('#ops-dealers tr').count(),10);
      assert.equal(s.state.calls.filter(c=>c.endpoint==='/reports/dashboard').length,1);assert.match(s.state.calls.at(-1).query,/size=10/);await s.context.close();
    });
    await check('Resolved debts while paging correct the last page and handle total zero',async()=>{
      const s=await setup();await s.ready();await s.page.locator('#ops-next').click();await s.page.locator('#ops-page').filter({hasText:'2/3'}).waitFor();
      s.state.total=5;await s.page.locator('#ops-next').click();await s.page.locator('#ops-page').filter({hasText:'1/1'}).waitFor();assert.equal(await s.page.locator('#ops-dealers tr').count(),5);
      s.state.total=0;await s.page.locator('#ops-size').selectOption('10');await s.page.locator('#ops-page').filter({hasText:'0 trang'}).waitFor();assert.match(await s.page.locator('#ops-dealers').textContent(),/Không có đại lý/);await s.context.close();
    });
    await check('Empty genuine data shows zeros, not errors, and all leap-month dates',async()=>{
      const data=F.overview(0,'2028-02',29);Object.keys(data.metrics).forEach(key=>data.metrics[key]=0);data.dailySales.forEach(row=>row.sales=0);data.territorySales=[];
      const s=await setup({data});await s.ready();assert.equal(await s.page.locator('#ops-error').isVisible(),false);assert.equal(await s.page.locator('#ops-today-sales').textContent(),'0 ₫');assert.equal(await s.page.locator('#ops-low-stock').textContent(),'0');
      assert.equal(await s.page.locator('#ops-daily-chart circle').count(),29);assert.equal(await s.page.locator('#ops-daily-empty').isVisible(),true);assert.match(await s.page.locator('#ops-territory-chart').textContent(),/Chưa có doanh số/);await s.context.close();
    });
    await check('HTTP failure and malformed/incomplete API clear all stale numbers, successful retry restores',async()=>{
      const s=await setup();await s.ready();s.state.status=500;await s.page.locator('#ops-reload').click();await s.page.locator('#ops-error').filter({hasText:'500'}).waitFor();assert.equal(await s.page.locator('#ops-today-sales').textContent(),'—');assert.equal(await s.page.locator('#ops-daily-chart circle').count(),0);
      s.state.status=200;s.state.invalid=true;await s.page.locator('#ops-reload').click();await s.page.locator('#ops-error').filter({hasText:'chưa đầy đủ'}).waitFor();assert.equal(await s.page.locator('#ops-today-sales').textContent(),'—');
      s.state.invalid=false;await s.page.locator('#ops-reload').click();await s.ready();assert.equal(await s.page.locator('#ops-error').isVisible(),false);assert.match(await s.page.locator('#ops-performance').textContent(),/Tải lại API/);await s.context.close();
    });
    await check('Page failure stays explicit and retains the dated KPI snapshot',async()=>{
      const s=await setup();await s.ready();s.state.pageStatus=500;await s.page.locator('#ops-next').click();await s.page.locator('#ops-page-error').filter({hasText:'500'}).waitFor();assert.equal(await s.page.locator('#ops-today-sales').textContent(),'3.500.000,25 ₫');assert.match(await s.page.locator('#ops-dealers').textContent(),/Không tải được/);await s.context.close();
    });
    await check('Rapid reload drops old delayed responses; only the newest snapshot appears',async()=>{
      const s=await setup();await s.ready();s.state.overviewDelay=700;s.state.data.metrics.todaySales='111';await s.page.locator('#ops-reload').click();
      await s.page.waitForFunction(()=>document.getElementById('ops-snapshot').textContent.startsWith('Đang tải'));await pause(100);s.state.overviewDelay=20;s.state.data.metrics.todaySales='222';await s.page.locator('#ops-reload').click();await s.ready();await pause(800);
      assert.equal(await s.page.locator('#ops-today-sales').textContent(),'222 ₫');assert.equal(await s.page.locator('#ops-error').isVisible(),false);await s.context.close();
    });
    await check('Matrix revocation during overview aborts sensitive content and redirects; same JWT regrant works',async()=>{
      const s=await setup();await s.ready();s.state.overviewDelay=700;await s.page.locator('#ops-reload').click();await pause(100);await s.grant([]);await s.page.waitForURL('**/error?status=403');await pause(800);
      assert.equal(await s.page.locator('[data-page="operations-dashboard"]').count(),0);assert.equal(await s.page.locator('#ops-workspace').count(),0);
      s.state.permissions=['DASHBOARD_VIEW'];s.state.overviewDelay=20;await s.page.goto(base+'/reports/dashboard');await s.ready();assert.equal(await s.page.evaluate(()=>Auth.getAccessToken()),token);await s.context.close();
    });
    await check('Matrix revocation during a delayed dealer page never repaints revoked details',async()=>{
      const s=await setup();await s.ready();s.state.pageDelay=700;await s.page.locator('#ops-next').click();await pause(100);await s.grant([]);await s.page.waitForURL('**/error?status=403');await pause(750);assert.equal(await s.page.locator('#ops-dealers').count(),0);await s.context.close();
    });
    await check('API 403 with the current JWT refreshes permissions and blocks further dashboard calls',async()=>{
      const s=await setup();await s.ready();s.state.permissions=[];s.state.status=403;await s.page.locator('#ops-reload').click();
      // Both the existing global page guard and Auth.apiFetch redirect on 403.
      await s.page.locator('#error-code').filter({hasText:'403'}).waitFor();
      assert.equal(await s.page.evaluate(()=>sessionStorage.getItem('accessToken')),token);assert.equal(await s.page.evaluate(()=>JSON.parse(sessionStorage.getItem('user')).permissions.includes('DASHBOARD_VIEW')),false);assert.equal(await s.page.locator('[data-page="operations-dashboard"]').count(),0);await s.context.close();
    });
    await check('No role fallback or unrelated grant; direct access makes no dashboard calls',async()=>{
      for(const permissions of [[],['REPORT_SALES','FINANCE_VIEW_DEBT']]){
        const s=await setup({permissions,roles:['ROLE_SALES_MANAGER']});await s.page.waitForURL('**/error?status=403');assert.equal(s.state.calls.filter(c=>c.endpoint.startsWith('/reports/dashboard')).length,0);await s.context.close();
      }
      const s=await setup({permissions:['DASHBOARD_VIEW'],roles:['ROLE_DEALER']});await s.page.waitForURL('**/error?status=403');assert.equal(s.state.calls.filter(c=>c.endpoint.startsWith('/reports/dashboard')).length,0);await s.context.close();
    });
    await check('ADMIN remains allowed without permission list; VIEWER with grant is allowed',async()=>{
      const s=await setup({permissions:[],admin:true});await s.ready();assert.equal(await s.page.locator('[data-page="operations-dashboard"]').count(),1);await s.context.close();
    });
    await check('Generic landing and finance-only debt view retain their own access; shortcut is permission filtered',async()=>{
      const s=await setup({permissions:[],route:'/dashboard'});await s.page.waitForFunction(()=>!document.getElementById('sidebar-nav').inert);assert.ok(s.page.url().endsWith('/dashboard'));assert.equal(s.state.calls.filter(c=>c.endpoint.startsWith('/reports/dashboard')).length,0);
      assert.equal(await s.page.locator('a[href="/reports/dashboard"]').count(),0);await s.grant(['DASHBOARD_VIEW']);await s.page.locator('a[href="/reports/dashboard"]').first().waitFor();assert.ok(await s.page.locator('a[href="/reports/dashboard"]').count()>=2);await s.context.close();
      const debt=await setup({permissions:['FINANCE_VIEW_DEBT'],route:'/dashboard?view=debt-limits'});await debt.page.locator('#debt-limits-view').waitFor({state:'visible'});assert.equal(debt.state.calls.filter(c=>c.endpoint.startsWith('/reports/dashboard')).length,0);await debt.context.close();
    });
    await check('External names render as text and never execute HTML',async()=>{
      const data=F.overview();data.territorySales[0].territoryName='<img src=x onerror="window.__opsXss=1">';data.overdueDealers[0].dealerName='<script>window.__opsXss=2</script>';
      const s=await setup({data});await s.ready();assert.match(await s.page.locator('#ops-territory-chart').textContent(),/<img/);assert.equal(await s.page.locator('#ops-territory-chart img,#ops-dealers script').count(),0);assert.equal(await s.page.evaluate(()=>window.__opsXss),undefined);await s.context.close();
    });
    await check('Slow API is honestly flagged above two seconds; later fast reload is measured separately',async()=>{
      const s=await setup({delay:2150});await s.ready();assert.match(await s.page.locator('#ops-performance').textContent(),/vượt mốc 2 giây/);assert.equal(await s.page.evaluate(()=>window.__opsEvents.at(-1).withinTwoSeconds),false);
      s.state.overviewDelay=30;await s.page.locator('#ops-reload').click();await s.ready();await s.page.waitForFunction(()=>window.__opsEvents.length===2);assert.equal(await s.page.evaluate(()=>window.__opsEvents.at(-1).withinTwoSeconds),true);await s.context.close();
    });
    await check('Desktop/tablet/mobile share outer gutters, internal chart/table scroll and readable content',async()=>{
      const s=await setup();await s.ready();for(const width of [1920,1440,1024,768,390,320]){
        await s.page.setViewportSize({width,height:1000});await pause(80);
        const layout=await s.page.evaluate(()=>{const main=document.querySelector('.operations-dashboard-page'),rect=main.getBoundingClientRect(),outer=main.parentElement.getBoundingClientRect(),style=getComputedStyle(main);return{viewport:document.documentElement.clientWidth,doc:document.documentElement.scrollWidth,left:rect.left-outer.left+parseFloat(style.paddingLeft),right:outer.right-rect.right+parseFloat(style.paddingRight),charts:document.querySelectorAll('#ops-daily-chart circle').length};});
        assert.ok(layout.doc<=layout.viewport+1,JSON.stringify(layout));assert.ok(Math.abs(layout.left-layout.right)<2,JSON.stringify(layout));assert.equal(layout.charts,31);
        if([1440,390].includes(width))await s.page.screenshot({path:path.join(output,'dashboard-'+width+'.png'),fullPage:true});
      }
      await s.context.close();
    });
    assert.deepEqual(errors,[]);assert.deepEqual(unexpected,[]);console.log(JSON.stringify({passed:true,cases,timings,output}));
  }finally{await browser.close();}
})().catch(error=>{console.error(error);process.exitCode=1;});
