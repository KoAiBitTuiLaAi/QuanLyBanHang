// Exercise the shared navigation against every sidebar page; business scripts are
// isolated so this test targets footer links without issuing business requests.
const assert = require('node:assert/strict');
const {chromium} = require('playwright');
const base = process.env.NAV_TEST_URL || 'http://127.0.0.1:5531';
const token = 'fixture.'+Buffer.from(JSON.stringify({exp:Math.floor(Date.now()/1000)+3600})).toString('base64url')+'.fixture';

(async()=>{
  const browser=await chromium.launch({channel:'chrome',headless:true});
  try {
    const context=await browser.newContext({viewport:{width:1440,height:900},reducedMotion:'reduce'});
    const user={id:1,username:'admin',fullName:'Quản trị viên',email:'admin@example.invalid',phone:'0901234567',roles:['ROLE_ADMIN'],permissions:[],warehouses:[],territories:[]};
    let avatarVersion=1;
    let shellOnly=true;
    const png=Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aS9sAAAAASUVORK5CYII=','base64');
    const avatarData=()=>({avatarUrl:avatarVersion ? `/auth/users/1/avatar?v=${avatarVersion}` : null,avatarThumbnailUrl:avatarVersion ? `/auth/users/1/avatar?thumbnail=true&v=${avatarVersion}` : null});
    await context.addInitScript(({user,token})=>{
      sessionStorage.setItem('user',JSON.stringify(user));sessionStorage.setItem('accessToken',token);
    },{user,token});
    const common=new Set(['auth.js','menu-config.js','app-shell.js','app-navigation.js','app-transition.js','profile.js']);
    await context.route('**/js/**',async route=>{
      const script=new URL(route.request().url()).pathname.split('/').pop();
      if(!shellOnly && ['orders.js','create-order.js'].includes(script))await new Promise(resolve=>setTimeout(resolve,150));
      return !shellOnly || common.has(script) ? route.continue() : route.fulfill({contentType:'application/javascript',body:''});
    });
    await context.route('http://localhost:8080/api/**',route=>{
      const request=route.request(),url=new URL(request.url());
      const headers={'Access-Control-Allow-Origin':base,'Access-Control-Allow-Headers':'Authorization,Content-Type','Access-Control-Allow-Methods':'GET,POST,OPTIONS'};
      if(request.method()==='OPTIONS')return route.fulfill({status:204,headers});
      if(url.pathname.endsWith('/auth/users/1/avatar')) {
        assert.ok(request.headers().authorization?.startsWith('Bearer '));
        return route.fulfill({status:200,headers,contentType:'image/png',body:png});
      }
      let data=[];
      if(url.pathname.endsWith('/auth/permissions'))data={roles:user.roles,permissions:[]};
      if(url.pathname.endsWith('/users/profile'))data={...user,...avatarData()};
      if(url.pathname.endsWith('/orders'))data={content:[],totalElements:0,totalPages:0};
      if(url.pathname.endsWith('/orders/draft'))data=null;
      if(url.pathname.endsWith('/auth/avatar')&&request.method()==='POST'){avatarVersion++;data=avatarData();}
      return route.fulfill({status:200,headers,contentType:'application/json',body:JSON.stringify({success:true,data})});
    });
    const page=await context.newPage();
    const errors=[];page.on('pageerror',e=>errors.push(e.message));
    await page.goto(base+'/orders');
    await page.waitForFunction(()=>!document.getElementById('sidebar-nav').inert);
    assert.equal(await page.locator('.sidebar__user-info').evaluate(el=>el.tagName),'A','orders footer name must be a real profile link');
    await page.waitForFunction(()=>{const image=document.querySelector('.sidebar__avatar img');return image&&!image.hidden&&image.naturalWidth>0;},null,{timeout:3000});
    const routes=await page.evaluate(()=>Array.from(new Set(MenuConfig.getMenuForUser(Auth.getUser()).map(item=>item.href))));
    for(const href of routes) {
      await page.goto(base+href);
      await page.waitForFunction(()=>document.getElementById('sidebar-nav') && !document.getElementById('sidebar-nav').inert);
      const info=page.locator('.sidebar__user-info');
      await page.waitForFunction(()=>{const image=document.querySelector('.sidebar__avatar img');return image&&!image.hidden&&image.naturalWidth>0;});
      assert.equal(await info.count(),1,href);
      assert.equal(await info.getAttribute('href'),'/profile',href);
      assert.equal(await info.locator('a,button').count(),0,`nested interactive element at ${href}`);
      const avatar=page.locator('.sidebar__avatar');
      const avatarTag=await avatar.evaluate(el=>el.tagName);
      if(await avatar.getAttribute('id')==='btn-avatar') assert.equal(avatarTag,'BUTTON',`avatar upload changed at ${href}`);
      else {assert.equal(avatarTag,'A',href);assert.equal(await avatar.getAttribute('href'),'/profile',href);}
      await info.click();
      await page.waitForURL(base+'/profile');
    }
    const previousAvatar=await page.locator('.sidebar__avatar img').getAttribute('src');
    await page.locator('#profile-avatar-file').setInputFiles({name:'avatar.png',mimeType:'image/png',buffer:png});
    await page.waitForFunction(previous=>{const image=document.querySelector('.sidebar__avatar img');return image?.src!==previous&&!image.hidden&&image.naturalWidth>0;},previousAvatar);
    assert.equal(avatarVersion,2);
    shellOnly=false;
    for(const href of ['/orders','/create-order']) {
      await page.goto(base+href);
      await page.waitForLoadState('networkidle');
      await page.waitForFunction(()=>{const image=document.querySelector('.sidebar__avatar img');return image&&!image.hidden&&image.naturalWidth>0;});
    }
    await page.goto(base+'/dashboard');
    await page.waitForFunction(()=>{const image=document.querySelector('.sidebar__avatar img');return image&&!image.hidden&&image.naturalWidth>0;});
    const dashboardAvatar=await page.locator('.sidebar__avatar img').getAttribute('src');
    await page.locator('#btn-avatar').click();
    await page.locator('#avatar-file').setInputFiles({name:'avatar.png',mimeType:'image/png',buffer:png});
    await page.locator('#btn-avatar-submit').click();
    await page.waitForFunction(previous=>{const image=document.querySelector('.sidebar__avatar img');return image?.src!==previous&&!image.hidden&&image.naturalWidth>0;},dashboardAvatar);
    assert.equal(avatarVersion,3);
    await page.goto(base+'/create-order');
    await page.locator('.sidebar__user-info').focus();
    await page.keyboard.press('Enter');
    await page.waitForURL(base+'/profile');
    await page.setViewportSize({width:390,height:844});
    await page.goto(base+'/orders');
    await page.locator('#sidebar-toggle').click();
    await page.locator('.sidebar__user-name').click();
    await page.waitForURL(base+'/profile');
    avatarVersion=0;
    await page.evaluate(()=>AppNavigation.refreshProfile());
    assert.equal(await page.locator('.sidebar__avatar img').isVisible(),false);
    assert.equal(await page.locator('.sidebar__avatar span').isVisible(),true);
    assert.deepEqual(errors,[]);
    console.log(`PASS footer profile link and authenticated saved avatar on ${routes.length} sidebar destinations; live upload update, keyboard Enter, mobile and preserved avatar-upload buttons.`);
  } finally {await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
