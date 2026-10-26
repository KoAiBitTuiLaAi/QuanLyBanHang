const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const { test } = require('node:test');

function setup({ top = 0, from = 0, type = 'push', native = true, page = 'dashboard' } = {}) {
  const listeners = new Map();
  let user = { id: 7, roles: ['ROLE_VIEWER'], permissions: ['PRODUCT_VIEW'] };
  let entryState = top ? { saleStockNavigation: { owner: '7', top } } : undefined;
  let replacements = 0;
  let readySignals = 0;
  let finishSync;
  const ready = new Promise(resolve => { finishSync = resolve; });
  const addEventListener = (name, fn) => listeners.set(name, [...(listeners.get(name) || []), fn]);
  function node() {
    return {
      dataset: {}, childElementCount: 0, scrollTop: 0, inert: false, children: [],
      append(child) { this.children.push(child); },
      querySelector() { return { textContent: '' }; },
      classList: { toggle() {} }, setAttribute() {}, removeAttribute() {}, addEventListener,
      replaceChildren(fragment) { this.children = fragment.children; this.childElementCount = this.children.length; replacements++; },
    };
  }
  const nav = node();
  const history = { state: entryState, replaceState(state) { this.state = state; } };
  const navigation = {
    currentEntry: { getState: () => entryState },
    activation: { navigationType: type, from: { getState: () => ({ saleStockNavigation: { owner: '7', top: from } }) } },
    updateCurrentEntry({ state }) { entryState = state; },
  };
  const emit = name => (listeners.get(name) || []).forEach(fn => fn({}));
  const menu = [
    { id: 'dashboard', href: '/dashboard', label: 'Tổng quan', roles: ['ROLE_VIEWER'] },
    { id: 'products', href: '/products', label: 'Sản phẩm', section: 'Kinh doanh', icon: '' },
  ];
  const context = vm.createContext({
    window: { navigation: native ? navigation : undefined, history, location: { pathname: '/' + page }, addEventListener, setTimeout, clearTimeout,
      AppTransition: { navigationReady() { readySignals++; } } },
    document: {
      getElementById: id => id === 'sidebar-nav' ? nav : null,
      createDocumentFragment: node, createElement: node,
      querySelector: () => null, querySelectorAll: () => [], addEventListener,
    },
    Auth: { isLoggedIn: () => true, getUser: () => user, ready, bindPermissions() {} },
    MenuConfig: {
      getMenuForUser: current => menu.filter(item => item.id === 'dashboard' || current.permissions.includes('PRODUCT_VIEW')),
      getPageForLocation: () => menu.find(item => item.id === page),
    },
  });
  vm.runInContext(fs.readFileSync(require.resolve('../frontend/js/app-navigation.js'), 'utf8'), context);
  return {
    nav, history, context, emit, finishSync,
    replacements: () => replacements, state: () => entryState,
    readySignals: () => readySignals,
    revoke: () => { user = { ...user, permissions: [] }; },
  };
}

test('new pages inherit sidebar scroll before permissions finish loading, without rebuilding an unchanged menu', async () => {
  const app = setup({ from: 420 });
  assert.equal(app.nav.scrollTop, 420);
  assert.equal(app.nav.inert, true);
  assert.equal(app.readySignals(), 0);
  app.finishSync();
  await new Promise(setImmediate);
  assert.equal(app.nav.inert, false);
  assert.equal(app.readySignals(), 1);
  assert.equal(app.replacements(), 1);
  app.emit('pageshow');
  assert.equal(app.nav.scrollTop, 420);
});

test('back/forward uses the destination entry scroll, not the previous page scroll', () => {
  const app = setup({ top: 550, from: 200, type: 'traverse' });
  assert.equal(app.nav.scrollTop, 550);
  app.nav.scrollTop = 610;
  app.emit('click');
  assert.equal(app.state().saleStockNavigation.top, 610);
  app.nav.scrollTop = 0;
  app.emit('pageshow');
  assert.equal(app.nav.scrollTop, 610);
});

test('permission changes preserve menu scroll and still revoke the page', async () => {
  const app = setup({ top: 450, page: 'products' });
  app.finishSync();
  await new Promise(setImmediate);
  app.revoke();
  app.emit('auth:permissions-changed');
  assert.equal(app.nav.scrollTop, 450);
  assert.equal(app.context.window.location.href, '/error?status=403');
  assert.equal(app.nav.children.filter(child => child.dataset.page === 'products').length, 0);
});

test('browsers without Navigation API retain history-state restoration and normal navigation', () => {
  const app = setup({ top: 230, native: false });
  assert.equal(app.nav.scrollTop, 230);
  app.nav.scrollTop = 300;
  app.emit('pagehide');
  assert.equal(app.history.state.saleStockNavigation.top, 300);
});
