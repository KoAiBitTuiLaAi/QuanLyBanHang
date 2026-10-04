const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');

function setup(page, response) {
  const notices = [];
  const requests = [];
  class Input {
    constructor(value='') { this.value=value; this.attributes={}; }
    setAttribute(key,value) { this.attributes[key]=value; }
    removeAttribute(key) { delete this.attributes[key]; }
    addEventListener() {}
    focus() { this.focused=true; }
    checkValidity() { return this.valid !== false; }
  }
  const inputs = page==='forgot'
    ? {email:new Input('qa@example.test')}
    : {'new-password':new Input('Test1234'),'confirm-password':new Input('Test1234')};
  const button={disabled:false};
  const form={
    addEventListener(name,handler) { this[name]=handler; },
    querySelector() { return button; },
    reset() { this.resetCalled=true; Object.values(inputs).forEach(input=>input.value=''); },
  };
  const document={
    getElementById(id) { return id===`${page}-form` ? form : inputs[id]; },
    querySelectorAll() { return Object.values(inputs); },
  };
  const fetch=async(url, options)=>{
    requests.push({url,body:JSON.parse(options.body)});
    if (response instanceof Error) throw response;
    return response;
  };
  vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../frontend/js/password-reset.js'),'utf8'), {
    document,fetch,Toast:{show:(message,type)=>notices.push({message,type})},
    location:{search:'?token=sample-token'},URLSearchParams,
  });
  return {form,button,inputs,notices,requests,async submit(){form.submit({preventDefault(){},currentTarget:form});await new Promise(setImmediate);}};
}

test('forgot password success uses a success toast and clears the submitted email',async()=>{
  const app=setup('forgot',{ok:true,json:async()=>({success:true,message:'Check your email'})});
  await app.submit();
  assert.deepEqual(app.notices,[{message:'Check your email',type:'success'}]);
  assert.equal(app.requests[0].body.email,'qa@example.test');
  assert.ok(app.form.resetCalled);
  assert.equal(app.button.disabled,false);
});

test('invalid email uses an error toast without submitting',async()=>{
  const app=setup('forgot');app.inputs.email.valid=false;
  await app.submit();
  assert.equal(app.notices[0].type,'error');
  assert.equal(app.requests.length,0);
  assert.equal(app.inputs.email.attributes['aria-invalid'],'true');
  assert.ok(app.inputs.email.focused);
});

test('reset password mismatch uses an error toast without changing any password',async()=>{
  const app=setup('reset');app.inputs['confirm-password'].value='Different123';
  await app.submit();
  assert.equal(app.notices[0].message,'Hai mật khẩu chưa khớp.');
  assert.equal(app.notices[0].type,'error');
  assert.equal(app.requests.length,0);
});

test('reset success submits the URL token, uses a toast and clears the fields',async()=>{
  const app=setup('reset',{ok:true,json:async()=>({success:true,message:'Password reset'})});
  await app.submit();
  assert.deepEqual(app.requests[0].body,{token:'sample-token',newPassword:'Test1234'});
  assert.equal(app.notices[0].type,'success');
  assert.ok(app.form.resetCalled);
  assert.equal(app.button.disabled,false);
});

test('server rejection keeps entered values and allows retry',async()=>{
  const app=setup('reset',{ok:false,json:async()=>({success:false,message:'Link expired'})});
  await app.submit();
  assert.deepEqual(app.notices,[{message:'Link expired',type:'error'}]);
  assert.equal(app.inputs['new-password'].value,'Test1234');
  assert.equal(app.form.resetCalled,undefined);
  assert.equal(app.button.disabled,false);
});

test('network failure uses an error toast and preserves the email',async()=>{
  const app=setup('forgot',new Error('offline'));
  await app.submit();
  assert.equal(app.notices[0].type,'error');
  assert.equal(app.inputs.email.value,'qa@example.test');
  assert.equal(app.button.disabled,false);
});

test('HTTP 200 with success=false is still shown as an error',async()=>{
  const app=setup('forgot',{ok:true,json:async()=>({success:false,message:'Rejected'})});
  await app.submit();
  assert.equal(app.notices[0].type,'error');
  assert.equal(app.form.resetCalled,undefined);
});
