const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');

function setup() {
  class Element {
    constructor(tag) { this.tag = tag; this.children = []; this.attributes = {}; this.classes = new Set(); this.classList = {add:value=>this.classes.add(value),remove:value=>this.classes.delete(value)}; }
    appendChild(child) { this.children.push(child); child.parent = this; }
    setAttribute(name,value) { this.attributes[name] = value; }
    addEventListener() {}
    remove() { this.removed = true; this.parent.children = this.parent.children.filter(child => child !== this); }
  }
  const body = new Element('body');
  const timers = [];
  const document = {body,createElement:tag=>new Element(tag),getElementById:id=>body.children.find(child=>child.id===id)};
  const source = fs.readFileSync(path.join(__dirname,'../frontend/js/toast.js'),'utf8');
  const toast = vm.runInNewContext(`${source}\nToast;`, {document,requestAnimationFrame:callback=>callback(),setTimeout:(callback,delay)=>timers.push({callback,delay})});
  return {toast,body,timers};
}

test('server messages are text and cannot inject HTML into a toast', () => {
  const {toast} = setup();
  const message = '<img src=x onerror="alert(1)">';
  const notification = toast.show(message,'error');
  const text = notification.children.find(child=>child.className==='toast__message');
  assert.equal(text.textContent,message);
  assert.equal(text.innerHTML,undefined);
  assert.equal(notification.attributes.role,'alert');
});

test('toasts expire even if no animationend event fires', () => {
  const {toast,body,timers} = setup();
  const notification = toast.show('Saved','success');
  assert.ok(notification.classes.has('toast--visible'));
  const expiry = timers.shift();
  assert.equal(expiry.delay,4000);
  expiry.callback();
  assert.ok(notification.classes.has('toast--exit'));
  timers.shift().callback();
  assert.equal(body.children[0].children.length,0);
});

test('existing warning durations are preserved and unknown types fall back to error', () => {
  const {toast,timers} = setup();
  assert.equal(toast.show('Locked','warning',8000).className,'toast toast--warning');
  assert.equal(timers[0].delay,8000);
  assert.equal(toast.show('Failed','invalid').className,'toast toast--error');
});
