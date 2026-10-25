const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { test } = require('node:test');

const source = fs.readFileSync(path.join(__dirname, '../frontend/js/menu-config.js'), 'utf8');
const menu = vm.runInNewContext(`${source}\nMenuConfig;`, { URLSearchParams });
const ids = user => Array.from(menu.getMenuForUser(user), item => item.id);

test('S7-07 dealer debt page allows either independent permission and hides after both are revoked', () => {
  for (const permission of ['FINANCE_VIEW_DEBT', 'INVOICE_VIEW']) {
    assert.ok(ids({roles:['ROLE_DEALER'], permissions:[permission]}).includes('dealer-invoices'));
    assert.ok(!ids({roles:['ROLE_ACCOUNTANT'], permissions:[permission]}).includes('dealer-invoices'));
  }
  assert.ok(!ids({roles:['ROLE_DEALER'], permissions:['ORDER_VIEW']}).includes('dealer-invoices'));
  assert.ok(ids({roles:['ROLE_ADMIN'], permissions:[]}).includes('dealer-invoices'));
});

test('S8-02 financial returns allow VIEW or ADJUST, no extra dealer or finance grant and no accountant fallback',()=>{
 for(const role of ['ROLE_VIEWER','ROLE_ACCOUNTANT','ROLE_SALES']){
  assert.ok(!ids({roles:[role],permissions:[]}).includes('debt-adjustment'));
  assert.ok(!ids({roles:[role],permissions:['FINANCE_VIEW_DEBT','FINANCE_MANAGE']}).includes('debt-adjustment'));
  for(const permission of ['INVOICE_VIEW','INVOICE_ADJUST'])assert.ok(ids({roles:[role],permissions:[permission]}).includes('debt-adjustment'));
 }
 assert.ok(ids({roles:['ROLE_ADMIN'],permissions:[]}).includes('debt-adjustment'));
 assert.ok(!ids({roles:['ROLE_DEALER'],permissions:['INVOICE_ADJUST','INVOICE_VIEW']}).includes('debt-adjustment'));
 assert.equal(menu.getPageForLocation(new URL('http://test/finance/debt-adjustment')).id,'debt-adjustment');
});

test('S8-09 aging report uses only REPORT_FINANCE matrix, no accountant fallback',()=>{
  for(const role of ['ROLE_VIEWER','ROLE_ACCOUNTANT','ROLE_SALES_MANAGER']){
    assert.ok(!ids({roles:[role],permissions:[]}).includes('debt-aging'));
    assert.ok(!ids({roles:[role],permissions:['FINANCE_VIEW_DEBT','FINANCE_MANAGE','DASHBOARD_VIEW']}).includes('debt-aging'));
    assert.ok(ids({roles:[role],permissions:['REPORT_FINANCE']}).includes('debt-aging'));
  }
  assert.ok(ids({roles:['ROLE_ADMIN'],permissions:[]}).includes('debt-aging'));
  assert.ok(!ids({roles:['ROLE_DEALER'],permissions:['REPORT_FINANCE']}).includes('debt-aging'));
  assert.equal(menu.getPageForLocation(new URL('http://test/reports/debt-aging')).id,'debt-aging');
});

test('S8-05 dashboard follows DASHBOARD_VIEW without locking the generic landing or debt view', () => {
  for (const role of ['ROLE_VIEWER','ROLE_SALES_MANAGER','ROLE_ACCOUNTANT','ROLE_WAREHOUSE_MANAGER']) {
    assert.ok(!ids({roles:[role],permissions:[]}).includes('operations-dashboard'));
    assert.ok(!ids({roles:[role],permissions:['REPORT_SALES','FINANCE_VIEW_DEBT']}).includes('operations-dashboard'));
    assert.ok(ids({roles:[role],permissions:['DASHBOARD_VIEW']}).includes('operations-dashboard'));
  }
  assert.ok(ids({roles:['ROLE_ADMIN'],permissions:[]}).includes('operations-dashboard'));
  assert.ok(!ids({roles:['ROLE_DEALER'],permissions:['DASHBOARD_VIEW']}).includes('operations-dashboard'));
  assert.equal(menu.getPageForLocation(new URL('http://test/reports/dashboard')).id,'operations-dashboard');
  assert.notEqual(menu.getPageForLocation(new URL('http://test/dashboard')).id,'operations-dashboard');
  assert.notEqual(menu.getPageForLocation(new URL('http://test/dashboard?view=debt-limits')).id,'operations-dashboard');
});

test('S8-01 returns follow existing read matrix, including IMPORT-only, with no role grant', () => {
  for (const role of ['ROLE_VIEWER','ROLE_SALES','ROLE_WAREHOUSE_MANAGER','ROLE_WAREHOUSE_STAFF']) {
    assert.ok(!ids({roles:[role],permissions:[]}).includes('dealer-returns'));
    assert.ok(!ids({roles:[role],permissions:['ORDER_CANCEL']}).includes('dealer-returns'));
    for (const permission of ['ORDER_VIEW','ORDER_CREATE','INVOICE_VIEW','WAREHOUSE_VIEW','WAREHOUSE_APPROVE','WAREHOUSE_IMPORT'])
      assert.ok(ids({roles:[role],permissions:[permission]}).includes('dealer-returns'));
  }
  assert.ok(ids({roles:['ROLE_ADMIN'],permissions:[]}).includes('dealer-returns'));
  assert.equal(menu.getPageForLocation(new URL('http://test/warehouse/dealer-returns')).id,'dealer-returns');
});

test('S7-05 menu follows each collection permission and revocations, not finance roles', () => {
  for (const role of ['ROLE_SALES','ROLE_ACCOUNTANT','ROLE_VIEWER','ROLE_WAREHOUSE_STAFF']) {
    assert.ok(!ids({roles:[role],permissions:[]}).includes('cash-collections'));
    assert.ok(!ids({roles:[role],permissions:['FINANCE_MANAGE','FINANCE_VIEW_DEBT']}).includes('cash-collections'));
    for (const permission of ['COLLECTION_CREATE','COLLECTION_CONFIRM','COLLECTION_RECONCILE'])
      assert.ok(ids({roles:[role],permissions:[permission]}).includes('cash-collections'));
  }
  assert.ok(ids({roles:['ROLE_ADMIN'],permissions:[]}).includes('cash-collections'));
  assert.equal(menu.getPageForLocation(new URL('http://test/cash-collections')).id, 'cash-collections');
});

test('S7-01 invoices use INVOICE_VIEW matrix, not accountant role or unrelated finance grants',()=>{
  for(const role of ['ROLE_ACCOUNTANT','ROLE_VIEWER','ROLE_SALES','ROLE_WAREHOUSE_STAFF']){
    assert.ok(!ids({roles:[role],permissions:[]}).includes('invoices'));
    assert.ok(!ids({roles:[role],permissions:['FINANCE_VIEW_DEBT','INVOICE_CREATE']}).includes('invoices'));
    assert.ok(ids({roles:[role],permissions:['INVOICE_VIEW']}).includes('invoices'));
  }
  assert.ok(ids({roles:['ROLE_ADMIN'],permissions:[]}).includes('invoices'));
  assert.ok(!ids({roles:['ROLE_DEALER'],permissions:['INVOICE_VIEW']}).includes('invoices'));
  assert.equal(menu.getPageForLocation(new URL('http://test/invoices')).id,'invoices');
});

test('S6-08 delivery and stocktaking remain separate and follow their own matrix grants',()=>{
  assert.ok(menu.menuItems.some(i=>i.id==='delivery-confirmation'&&i.href==='/warehouse/delivery'));
  const has=permissions=>ids({roles:['ROLE_VIEWER'],permissions}).includes('delivery-confirmation');
  assert.equal(has(['ORDER_VIEW','WAREHOUSE_EXPORT']),true);
  assert.equal(has(['ORDER_VIEW']),false);assert.equal(has(['WAREHOUSE_EXPORT']),false);
  assert.ok(ids({roles:['ROLE_ADMIN'],permissions:[]}).includes('delivery-confirmation'));
  const stocktake=menu.menuItems.find(i=>i.id==='warehouse-stocktake');assert.equal(stocktake.permission,undefined);
});

test('S6-03 picking menu uses live VIEW/EXPORT matrix for every internal role, ADMIN always allowed', () => {
  for (const role of ['ROLE_VIEWER','ROLE_SALES','ROLE_WAREHOUSE_STAFF','ROLE_WAREHOUSE_MANAGER']) {
    assert.ok(!ids({roles:[role],permissions:[]}).includes('warehouse-picking'));
    for (const permission of ['WAREHOUSE_VIEW','WAREHOUSE_EXPORT'])
      assert.ok(ids({roles:[role],permissions:[permission]}).includes('warehouse-picking'));
  }
  assert.ok(ids({roles:['ROLE_ADMIN'],permissions:[]}).includes('warehouse-picking'));
  assert.equal(menu.getPageForLocation(new URL('http://test/warehouse/picking')).id, 'warehouse-picking');
});

test('S6-02 stock card is VIEW only, ADMIN allowed, no hardcoded warehouse role grant',()=>{
  for(const role of ['ROLE_VIEWER','ROLE_SALES','ROLE_WAREHOUSE_MANAGER','ROLE_WAREHOUSE_STAFF']){
    assert.ok(!ids({roles:[role],permissions:[]}).includes('warehouse-stock-card'));
    assert.ok(ids({roles:[role],permissions:['WAREHOUSE_VIEW']}).includes('warehouse-stock-card'));
  }
  assert.ok(ids({roles:['ROLE_ADMIN'],permissions:[]}).includes('warehouse-stock-card'));
  assert.equal(menu.getPageForLocation(new URL('http://test/warehouse/stock-card?productId=2')).id,'warehouse-stock-card');
});

test('S6-01 menu grants/revocations follow VIEW or MANAGE, never warehouse role fallback', () => {
  for(const role of ['ROLE_VIEWER','ROLE_WAREHOUSE_MANAGER','ROLE_WAREHOUSE_STAFF']) {
    assert.ok(!ids({roles:[role],permissions:[]}).includes('warehouse-lots'));
    for(const permission of ['WAREHOUSE_VIEW','WAREHOUSE_MANAGE'])
      assert.ok(ids({roles:[role],permissions:[permission]}).includes('warehouse-lots'));
  }
  assert.ok(ids({roles:['ROLE_ADMIN'],permissions:[]}).includes('warehouse-lots'));
});

test('S5-08 merge preserves stocktaking, stock minimums and warehouse transfers as distinct menus', () => {
  const pages = [
    ['warehouse-lots', '/warehouse/lots'],
    ['warehouse-stocktake', '/warehouse/units?tab=stocktake'],
    ['stock-minimums', '/warehouse/minimums'],
    ['warehouse-transfers', '/warehouse/transfers'],
  ];
  const adminMenu = ids({ roles: ['ROLE_ADMIN'], permissions: [] });
  for (const [id, href] of pages) {
    assert.equal(menu.menuItems.filter(item => item.id === id).length, 1, id);
    assert.ok(adminMenu.includes(id), id);
    assert.equal(menu.getPageForLocation(new URL(href, 'http://localhost')).id, id);
  }
});

test('merged stocktaking menu follows VIEW or ADJUST grants and revocations without role fallback', () => {
  for (const role of ['ROLE_WAREHOUSE_MANAGER', 'ROLE_WAREHOUSE_STAFF', 'ROLE_VIEWER', 'ROLE_SALES']) {
    for (const permission of ['WAREHOUSE_VIEW', 'WAREHOUSE_ADJUST']) {
      assert.ok(ids({ roles: [role], permissions: [permission] }).includes('warehouse-stocktake'), `${role}: ${permission}`);
    }
    assert.ok(!ids({ roles: [role], permissions: [] }).includes('warehouse-stocktake'), role);
  }
  assert.ok(ids({ roles: ['ROLE_ADMIN'], permissions: [] }).includes('warehouse-stocktake'));
});

test('admin can reach implemented account, audit and catalog pages', () => {
  const visible = ids({roles:['ROLE_ADMIN'], permissions:['USER_VIEW', 'PRODUCT_VIEW', 'WAREHOUSE_VIEW', 'WAREHOUSE_IMPORT']});
  for (const id of ['dashboard', 'profile', 'users', 'audit-logs', 'products', 'product-groups', 'suppliers', 'price-lists', 'discount-policies', 'orders', 'order-approval']) assert.ok(visible.includes(id));
  for (const id of ['customers', 'finance', 'reports']) assert.ok(!visible.includes(id));
});

test('admin keeps management menus when the session has stale permissions', () => {
  const visible = ids({roles:['ROLE_ADMIN'], permissions:[]});
  for (const id of ['products', 'price-lists', 'discount-policies', 'debt-limits', 'users', 'dealer-orders', 'dealer-portal']) {
    assert.ok(visible.includes(id), id);
  }
});

test('S5-03 warehouse menu follows view/manage permissions without a warehouse role fallback', () => {
  for (const role of ['ROLE_VIEWER', 'ROLE_SALES', 'ROLE_WAREHOUSE_MANAGER']) {
    for (const permission of ['WAREHOUSE_VIEW', 'WAREHOUSE_MANAGE']) {
      assert.ok(ids({ roles: [role], permissions: [permission] }).includes('warehouses'));
    }
    assert.ok(!ids({ roles: [role], permissions: [] }).includes('warehouses'));
  }
  assert.ok(ids({ roles: ['ROLE_ADMIN'], permissions: [] }).includes('warehouses'));
  assert.equal(menu.getPageForLocation(new URL('http://localhost/warehouses')).id, 'warehouses');
});

test('S5-05 keeps the inventory menu on the permission matrix and resolves old links', () => {
  const item = menu.menuItems.find(item => item.id === 'warehouse');
  assert.equal(item.href, '/warehouse/inventory');
  assert.ok(ids({ roles: ['ROLE_VIEWER'], permissions: ['WAREHOUSE_VIEW'] }).includes('warehouse'));
  assert.ok(!ids({ roles: ['ROLE_WAREHOUSE_MANAGER'], permissions: ['WAREHOUSE_MANAGE'] }).includes('warehouse'));
  assert.equal(menu.getPageForLocation(new URL('http://localhost/warehouse/units?tab=inventory')).id, 'warehouse');
});

test('dealer menus require the permissions of every API used by the page', () => {
  const permissions = ['ORDER_VIEW', 'ORDER_CREATE', 'PRODUCT_VIEW'];
  assert.ok(ids({ roles: ['ROLE_DEALER'], permissions }).includes('dealer-portal'));
  for (const revoked of permissions) {
    const visible = ids({ roles: ['ROLE_DEALER'], permissions: permissions.filter(p => p !== revoked) });
    assert.ok(!visible.includes('dealer-portal'), revoked);
    assert.equal(visible.includes('dealer-orders'), revoked !== 'ORDER_VIEW');
  }
});

test('dealer-only accounts never see internal menus rejected by portal data scoping', () => {
  const visible = ids({ roles: ['ROLE_DEALER'], permissions: ['ORDER_VIEW', 'PRODUCT_VIEW', 'USER_VIEW', 'WAREHOUSE_VIEW'] });
  assert.ok(visible.includes('dealer-orders'));
  for (const id of ['orders', 'products', 'users', 'warehouse']) assert.ok(!visible.includes(id));
  assert.ok(ids({ roles: ['ROLE_DEALER', 'ROLE_SALES'], permissions: ['ORDER_VIEW'] }).includes('orders'));
});

test('permission removal hides permission-gated menus for non-admin roles', () => {
  const sales = ids({roles:['ROLE_SALES'], permissions:['PRODUCT_VIEW', 'CUSTOMER_VIEW']});
  assert.ok(sales.includes('products'));
  assert.ok(sales.includes('product-groups'));
  assert.ok(sales.includes('dealers'));
  assert.ok(!sales.includes('create-order'));
  assert.ok(ids({roles:['ROLE_SALES'], permissions:['PRODUCT_VIEW', 'ORDER_CREATE', 'CUSTOMER_VIEW']}).includes('create-order'));
  assert.ok(!ids({roles:['ROLE_SALES'], permissions:['PRODUCT_VIEW', 'ORDER_CREATE']}).includes('dealers'));
});

test('granted permissions enable menus regardless of the default role', () => {
  for (const role of ['ROLE_WAREHOUSE_STAFF', 'ROLE_VIEWER']) {
    const visible = ids({roles:[role], permissions:['USER_VIEW', 'PRODUCT_VIEW', 'WAREHOUSE_VIEW', 'WAREHOUSE_IMPORT']});
    assert.ok(visible.includes('products'));
    assert.ok(visible.includes('warehouse'));
    assert.ok(visible.includes('users'));
    assert.ok(!ids({roles:[role], permissions:[]}).includes('users'));
    assert.ok(!visible.includes('audit-logs'));
    assert.ok(!visible.includes('price-lists'));
  }
});

test('sales manager can use groups, prices and conversions; permission removal hides product pages', () => {
  const visible = ids({roles:['ROLE_SALES_MANAGER'], permissions:['PRODUCT_VIEW', 'PRODUCT_EDIT', 'WAREHOUSE_VIEW']});
  for (const id of ['product-groups', 'price-lists', 'product-units']) assert.ok(visible.includes(id));
  assert.ok(!ids({roles:['ROLE_SALES_MANAGER'], permissions:[]}).includes('products'));
});

test('every visible page has an absolute, extensionless link even from a nested page', () => {
  for (const item of menu.menuItems) {
    assert.ok(item.href.startsWith('/'), item.id);
    assert.ok(!item.href.includes('.html'), item.id);
    assert.equal(menu.resolveHref(item.href), item.href);
  }
});

test('debt limit menu follows the finance permission matrix', () => {
  assert.ok(ids({roles:['ROLE_ADMIN'], permissions:[]}).includes('debt-limits'));
  for (const role of ['ROLE_ACCOUNTANT', 'ROLE_SALES_MANAGER', 'ROLE_VIEWER', 'ROLE_SALES', 'ROLE_WAREHOUSE_STAFF', 'ROLE_WAREHOUSE_MANAGER']) {
    assert.ok(ids({roles:[role], permissions:['FINANCE_VIEW_DEBT']}).includes('debt-limits'));
    assert.ok(!ids({roles:[role], permissions:[]}).includes('debt-limits'));
  }
});

test('debt limit menu uses the existing dashboard route', () => {
  const item = menu.menuItems.find(item => item.id === 'debt-limits');
  assert.equal(item.href, '/dashboard?view=debt-limits');
});

test('order approval menu requires both viewing and approving orders', () => {
  assert.ok(ids({
    roles: ['ROLE_SALES_MANAGER'],
    permissions: ['ORDER_APPROVE', 'ORDER_VIEW'],
  }).includes('order-approval'));
  assert.ok(!ids({
    roles: ['ROLE_SALES_MANAGER'],
    permissions: ['ORDER_APPROVE'],
  }).includes('order-approval'));
});

const pageAt = href => menu.getPageForLocation(new URL(href, 'http://127.0.0.1:5500'));

test('special dashboard view wins over the generic dashboard, independent of unrelated query values', () => {
  assert.equal(pageAt('/dashboard?other=1&view=debt-limits').id, 'debt-limits');
  assert.equal(pageAt('/dashboard?view=unknown').id, 'dashboard');
  assert.equal(pageAt('/dashboard/').id, 'dashboard');
});

test('warehouse views resolve distinct sidebar entries with a stable default', () => {
  for (const [tab, expected] of [['units', 'product-units'], ['receipts', 'import-export'], ['ledger', 'warehouse-ledger'], ['inventory', 'warehouse']]) {
    assert.equal(pageAt(`/warehouse/units?warehouseId=4&tab=${tab}`).id, expected);
  }
  assert.equal(pageAt('/warehouse/units').id, 'product-units');
  assert.equal(pageAt('/warehouse/units/?tab=invalid').id, 'product-units');
});

test('all menu links resolve back to their own page instead of another view on the same path', () => {
  for (const item of menu.menuItems) assert.equal(pageAt(item.href).id, item.id, item.href);
  assert.equal(pageAt('/not-a-page'), null);
});

test('S5-09: minimum configuration follows MANAGE, never a warehouse role or VIEW fallback', () => {
  assert.ok(menu.getMenuForUser({roles:['ROLE_VIEWER'],permissions:['WAREHOUSE_MANAGE']}).some(item=>item.id==='stock-minimums'));
  assert.ok(!menu.getMenuForUser({roles:['ROLE_WAREHOUSE_MANAGER'],permissions:['WAREHOUSE_VIEW']}).some(item=>item.id==='stock-minimums'));
  assert.ok(menu.getMenuForUser({roles:['ROLE_ADMIN'],permissions:[]}).some(item=>item.id==='stock-minimums'));
  assert.equal(pageAt('/warehouse/minimums').id,'stock-minimums');
});

test('S5-07: transfer menu follows matrix permission, not a fixed role', () => {
  for (const permission of ['WAREHOUSE_TRANSFER', 'WAREHOUSE_VIEW']) {
    assert.ok(menu.getMenuForUser({roles:['ROLE_VIEWER'],permissions:[permission]}).some(item=>item.id==='warehouse-transfers'));
  }
  assert.ok(!menu.getMenuForUser({roles:['ROLE_WAREHOUSE_STAFF'],permissions:[]}).some(item=>item.id==='warehouse-transfers'));
  assert.ok(menu.getMenuForUser({roles:['ROLE_ADMIN'],permissions:[]}).some(item=>item.id==='warehouse-transfers'));
  assert.equal(pageAt('/warehouse/transfers').id, 'warehouse-transfers');
});

test('breadcrumbs use home, actual section and current page; section labels have no misleading destination', () => {
  const crumbs = menu.getBreadcrumbs(pageAt('/create-order'));
  assert.deepEqual(Array.from(crumbs, crumb => crumb.label), ['Tổng quan', 'Kinh doanh', 'Tạo đơn hàng']);
  assert.equal(crumbs[0].href, '/dashboard');
  assert.equal(crumbs[1].href, undefined);
  assert.equal(crumbs[2].current, true);
  assert.deepEqual(Array.from(menu.getBreadcrumbs(pageAt('/profile')), crumb => crumb.label), ['Tổng quan', 'Hồ sơ cá nhân']);
  assert.deepEqual(Array.from(menu.getBreadcrumbs(pageAt('/dashboard')), crumb => crumb.label), ['Tổng quan']);
});

test('group labels remain correct when earlier menu items are hidden by permissions', () => {
  const sales = menu.getMenuForUser({ roles: ['ROLE_SALES'], permissions: ['ORDER_CREATE'] });
  assert.equal(sales.find(item => item.id === 'create-order').section, 'Kinh doanh');
  const warehouse = menu.getMenuForUser({ roles: ['ROLE_VIEWER'], permissions: ['WAREHOUSE_VIEW'] });
  assert.ok(warehouse.some(item => item.id === 'product-units'));
  assert.equal(warehouse.find(item => item.id === 'warehouse-ledger').section, 'Kho hàng');
  assert.ok(!warehouse.some(item => item.id === 'import-export'));
});
