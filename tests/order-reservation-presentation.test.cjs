const { test } = require('node:test');
const assert = require('node:assert/strict');
const Reservation = require('../frontend/js/order-reservation.js');
const Inventory = require('../frontend/js/order-inventory.js');
test('reservation display trusts the backend flag, not merely APPROVED/RECORDED', () => {
  assert.equal(Reservation.describe({ status: 'RECORDED', inventoryReserved: false }).held, false);
  assert.match(Reservation.describe({ status: 'RECORDED', inventoryReserved: false }).label, /Không còn/);
  assert.equal(Reservation.describe({ status: 'PICKING', inventoryReserved: true }).held, true);
  assert.match(Reservation.describe({ status: 'EXPIRED', inventoryReserved: false }).label, /đã được nhả/);
  assert.match(Reservation.describe({ status: 'EXPORTED', inventoryReserved: false }).label, /Đã xuất/);
});
test('a passed client deadline does not invent a successful DB release', () => {
  const row = { status: 'RECORDED', inventoryReserved: true, reservationExpiresAt: '2026-10-20T00:00:00Z' };
  assert.match(Reservation.describe(row, '2026-10-22T00:00:00Z').label, /chờ hệ thống nhả/);
  assert.equal(Inventory.isOrderReservationActive(row, '2026-10-22T00:00:00Z'), true);
});
test('server stock totals take precedence over incomplete or stale visible order lists', () => {
  const stock = [{ productId: 1, baseQuantity: 100, reservedQuantity: 70, availableQuantity: 30, baseUnit: 'lon' }];
  const map = Inventory.buildInventoryMap(stock, [{ status: 'RECORDED', items: [{ productId: 1, baseQuantity: 5 }] }]);
  assert.equal(map.get(1).reservedBase, 70); assert.equal(map.get(1).availableBase, 30);
});
test('pending, explicitly released and completed orders never keep a presentation reservation', () => {
  assert.equal(Inventory.isOrderReservationActive({ status: 'PENDING_APPROVAL' }), false);
  assert.equal(Inventory.isOrderReservationActive({ status: 'RECORDED', inventoryReserved: false }), false);
  assert.equal(Inventory.isOrderReservationActive({ status: 'COMPLETED', inventoryReserved: false }), false);
});
