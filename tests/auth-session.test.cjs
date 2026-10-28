const assert = require('node:assert/strict');
const {test} = require('node:test');
const fs = require('node:fs');
const vm = require('node:vm');
const jwt = seconds => 'test.' + Buffer.from(JSON.stringify({exp:Math.floor(Date.now()/1000)+seconds})).toString('base64') + '.test';
const user = {id:12,username:'staff',roles:['ROLE_VIEWER'],permissions:['PRODUCT_VIEW']};
const data = (extra={}) => ({...user,accessToken:jwt(3600),refreshToken:null,...extra});
const response = (value,status=200) => ({ok:status<400,status,json:async()=>value});
function deferred() { let resolve; const promise=new Promise(r=>resolve=r); return {promise,resolve}; }
const tick = () => new Promise(resolve=>setImmediate(resolve));

function setup(fetcher,{logged=false,legacy=false,exp=3600}={}) {
  const session=new Map(), notifications=[], requests=[], notices=[];
  if(logged) { session.set('accessToken',jwt(exp));session.set('user',JSON.stringify(user)); }
  if(legacy) session.set('refreshToken','legacy-refresh');
  let reloads=0, channel;
  const location={href:'/finance/debt-statement',reload:()=>reloads++};
  const context=vm.createContext({
    sessionStorage:{getItem:k=>session.get(k)??null,setItem:(k,v)=>session.set(k,String(v)),removeItem:k=>session.delete(k)},
    window:{location,addEventListener(){},dispatchEvent(){}},
    document:{querySelectorAll:()=>[],createElement:()=>({setAttribute(){},append(){}}),body:{prepend:n=>notices.push(n)}},
    BroadcastChannel:class { constructor(){channel=this;} postMessage(message){notifications.push(message);} },
    CustomEvent:class {constructor(type,opts){this.type=type;this.detail=opts?.detail;}},
    fetch:async(url,opts={})=>{requests.push({url,opts});return fetcher(url,opts);},
    atob:value=>Buffer.from(value,'base64').toString(), setInterval(){},clearInterval(){},console:{warn(){},error(){}},
  });
  const auth=vm.runInContext(fs.readFileSync(require.resolve('../frontend/js/auth.js'),'utf8')+'\nAuth;',context);
  return {auth,session,requests,notifications,notices,location,reloads:()=>reloads,receive:message=>channel.onmessage({data:message})};
}

test('new tab restores once via HttpOnly cookie, then reloads its original destination',async()=>{
  const pending=deferred();const s=setup(()=>pending.promise);
  assert.equal(s.auth.requireLogin(),false);assert.equal(s.auth.requireLogin(),false);
  assert.equal(s.requests.length,1);
  assert.equal(s.requests[0].opts.credentials,'include');
  assert.equal(s.requests[0].opts.headers['X-Browser-Session'],'1');
  assert.equal(s.requests[0].opts.body,'{}');
  pending.resolve(response(data()));await tick();
  assert.equal(s.auth.isLoggedIn(),true);assert.equal(s.reloads(),1);
  assert.equal(s.location.href,'/finance/debt-statement');
  assert.equal(s.session.has('refreshToken'),false);
  assert.equal(s.auth.hasPermission('PRODUCT_VIEW'),true);
});
test('missing or expired cookie goes to login without a reload loop',async()=>{
  const s=setup(()=>response({success:false},401));
  s.auth.requireLogin();await tick();
  assert.equal(s.location.href,'/login');assert.equal(s.reloads(),0);assert.equal(s.requests.length,1);
  assert.equal(s.auth.isLoggedIn(),false);
});
test('network failure preserves an existing tab session and never marks it expired',async()=>{
  const s=setup(()=>{throw new TypeError('offline');},{logged:true});
  assert.equal(await s.auth.restoreSession(),null);
  assert.equal(s.auth.isLoggedIn(),true);assert.equal(s.location.href,'/finance/debt-statement');
});
test('new-tab server failure offers retry instead of treating it as an invalid cookie',async()=>{
  const s=setup(()=>response({success:false},503));s.auth.requireLogin();await tick();
  assert.equal(s.notices.length,1);assert.equal(s.location.href,'/finance/debt-statement');
  assert.equal(s.session.has('sessionMessage'),false);
});
test('logout clears every tab immediately and late refresh cannot restore credentials',async()=>{
  const pending=deferred();
  const s=setup(url=>url.endsWith('/refresh')?pending.promise:response({success:true}),{logged:true});
  const restore=s.auth.restoreSession();const logout=s.auth.logout();
  assert.equal(s.auth.isLoggedIn(),false);
  assert.equal(s.notifications[0].type,'logout');
  pending.resolve(response(data()));await restore;await logout;
  assert.equal(s.auth.isLoggedIn(),false);assert.equal(s.auth.getUser(),null);assert.equal(s.location.href,'/login');
  assert.equal(s.requests.find(r=>r.url.endsWith('/logout')).opts.credentials,'include');
});
test('late permission response cannot resurrect a cleared user',async()=>{
  const pending=deferred();const s=setup(()=>pending.promise,{logged:true});
  const sync=s.auth.syncPermissions();s.auth.clearSession();
  pending.resolve(response({success:true,data:{roles:['ROLE_ADMIN'],permissions:[]}}));await sync;
  assert.equal(s.auth.getUser(),null);assert.equal(s.auth.hasPermission('PRODUCT_VIEW'),false);
});
test('receiving logout aborts a pending restore and does not broadcast credentials',async()=>{
  const pending=deferred();const s=setup(()=>pending.promise);
  const restore=s.auth.restoreSession();s.receive({type:'logout'});
  pending.resolve(response(data()));await restore;
  assert.equal(s.auth.isLoggedIn(),false);assert.equal(s.location.href,'/login');assert.equal(s.notifications.length,0);
});
test('browser login stores canonical user/access only and broadcasts a token-free notification',async()=>{
  const s=setup(()=>response(data({refreshToken:'must-never-be-stored'})));
  await s.auth.login({username:'staff',password:'not-a-real-secret'});
  assert.equal(s.auth.isLoggedIn(),true);assert.equal(s.auth.getRefreshToken(),null);
  assert.equal(s.requests[0].opts.credentials,'include');
  assert.equal(JSON.stringify(s.notifications),JSON.stringify([{type:'login'}]));
});
test('account switch during refresh stops a business write instead of replaying it as the new user',async()=>{
  const s=setup(()=>response(data({id:30,username:'different-account'})),{logged:true,exp:1});
  await assert.rejects(s.auth.apiFetch('/orders',{method:'POST',body:'{}'}),/Session changed/);
  assert.equal(s.requests.length,1);assert.equal(s.reloads(),1);
  assert.equal(s.auth.getUser().username,'different-account');
});
test('legacy refresh credential is sent only for migration and then removed',async()=>{
  const s=setup(()=>response(data()),{logged:true,legacy:true});
  assert.equal(await s.auth.restoreSession(),true);
  assert.equal(JSON.parse(s.requests[0].opts.body).refreshToken,'legacy-refresh');
  assert.equal(s.auth.getRefreshToken(),null);
});
test('late authenticated API response is rejected after cross-tab logout',async()=>{
  const pending=deferred();const s=setup(()=>pending.promise,{logged:true});
  const request=s.auth.apiFetch('/products');s.receive({type:'logout'});
  pending.resolve(response({success:true,data:[{id:9}]}));
  await assert.rejects(request,/Session changed/);
});
