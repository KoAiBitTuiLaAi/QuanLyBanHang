const assert = require('node:assert/strict');
const { test } = require('node:test');
const OrderInventory = require('../frontend/js/order-inventory.js');

test('S5-06: Đơn chuyển sang Đã duyệt (APPROVED / RECORDED) thì tồn tương ứng được giữ chỗ ngay, làm giảm tồn khả dụng', () => {
  // Kho có 100 lon bia thực tế
  const initialInventory = [{ productId: 10, baseQuantity: 100, baseUnit: 'lon' }];

  // Ban đầu đơn đang Chờ duyệt (hoặc chưa duyệt), giả định đơn chuyển sang APPROVED
  const approvedOrders = [
    {
      id: 101,
      status: 'APPROVED',
      items: [{ productId: 10, quantity: 30, conversionFactor: 1, baseQuantity: 30 }],
    },
  ];

  // Tính bảng tồn kho tổng hợp
  const inventoryMap = OrderInventory.buildInventoryMap(initialInventory, approvedOrders);
  const stock = inventoryMap.get(10);

  // Tồn thực tế vẫn là 100 (chưa xuất kho)
  assert.equal(stock.physicalBase, 100);
  // Tồn giữ chỗ ngay lập tức là 30 lon để hàng không bị người khác bán mất
  assert.equal(stock.reservedBase, 30);
  // Tồn khả dụng chỉ còn 70 lon
  assert.equal(stock.availableBase, 70);

  // Người khác cố đặt 75 lon -> Bị chặn
  const checkOver = OrderInventory.validateStock(75, stock.availableBase);
  assert.equal(checkOver.valid, false);
  assert.equal(checkOver.maxAllowed, 70);
  assert.match(checkOver.message, /Vượt tồn khả dụng/);

  // Người khác đặt 70 lon -> Được phép
  const checkExact = OrderInventory.validateStock(70, stock.availableBase);
  assert.equal(checkExact.valid, true);
});

test('S5-06: Huỷ đơn (CANCELLED) hoặc Từ chối đơn (REJECTED) thì tồn được nhả tự động', () => {
  const initialInventory = [{ productId: 10, baseQuantity: 100, baseUnit: 'lon' }];

  // Đơn 30 lon bị huỷ
  const cancelledOrders = [
    {
      id: 101,
      status: 'CANCELLED',
      items: [{ productId: 10, quantity: 30, conversionFactor: 1, baseQuantity: 30 }],
    },
  ];

  const mapCancelled = OrderInventory.buildInventoryMap(initialInventory, cancelledOrders);
  const stockCancelled = mapCancelled.get(10);
  // Tồn giữ chỗ phải về 0
  assert.equal(stockCancelled.reservedBase, 0);
  // Tồn khả dụng hồi phục nguyên vẹn 100 lon
  assert.equal(stockCancelled.availableBase, 100);

  // Tương tự khi đơn bị từ chối duyệt (REJECTED)
  const rejectedOrders = [
    {
      id: 102,
      status: 'REJECTED',
      items: [{ productId: 10, quantity: 40, conversionFactor: 1, baseQuantity: 40 }],
    },
  ];
  const mapRejected = OrderInventory.buildInventoryMap(initialInventory, rejectedOrders);
  const stockRejected = mapRejected.get(10);
  assert.equal(stockRejected.reservedBase, 0);
  assert.equal(stockRejected.availableBase, 100);
});

test('S5-06: Đơn quá hạn giữ chỗ (reservationExpiresAt <= now) thì tồn được nhả tự động', () => {
  const initialInventory = [{ productId: 10, baseQuantity: 100, baseUnit: 'lon' }];

  // Đơn được duyệt lúc 09:00, có hạn giữ chỗ đến 10:00:00
  const orders = [
    {
      id: 103,
      status: 'APPROVED',
      reservationExpiresAt: '2026-10-20T10:00:00.000Z',
      items: [{ productId: 10, quantity: 25, conversionFactor: 1, baseQuantity: 25 }],
    },
  ];

  // Tại thời điểm 10:05:00 (đã quá hạn giữ chỗ)
  const pastDeadline = new Date('2026-10-20T10:05:00.000Z');

  // Đơn quá hạn -> isOrderReservationActive trả về false
  assert.equal(OrderInventory.isOrderReservationActive(orders[0], pastDeadline), false);

  // Tồn giữ chỗ tự động nhả về 0 lon
  const reserved = OrderInventory.calculateReservedByProduct(orders, 10, null, pastDeadline);
  assert.equal(reserved, 0);

  // Tồn khả dụng trong kho phục hồi về 100 lon
  const mapExpired = OrderInventory.buildInventoryMap(initialInventory, orders, pastDeadline);
  assert.equal(mapExpired.get(10).availableBase, 100);
});

test('S5-06: Đơn chưa quá hạn giữ chỗ (reservationExpiresAt > now) thì vẫn giữ chỗ an toàn', () => {
  const initialInventory = [{ productId: 10, baseQuantity: 100, baseUnit: 'lon' }];

  const orders = [
    {
      id: 104,
      status: 'APPROVED',
      reservationExpiresAt: '2026-10-20T12:00:00.000Z',
      items: [{ productId: 10, quantity: 25, conversionFactor: 1, baseQuantity: 25 }],
    },
  ];

  // Tại thời điểm 11:30:00 (vẫn trong thời hạn giữ chỗ)
  const withinDeadline = new Date('2026-10-20T11:30:00.000Z');

  assert.equal(OrderInventory.isOrderReservationActive(orders[0], withinDeadline), true);

  const reserved = OrderInventory.calculateReservedByProduct(orders, 10, null, withinDeadline);
  assert.equal(reserved, 25);

  const mapActive = OrderInventory.buildInventoryMap(initialInventory, orders, withinDeadline);
  assert.equal(mapActive.get(10).availableBase, 75);
});

test('S5-06: Cột Tồn khả dụng quy đổi theo đơn vị tính và cung cấp gợi ý đặt tối đa', () => {
  // Có 50 lon khả dụng, 1 thùng = 24 lon
  const inventoryMap = new Map([
    [10, { physicalBase: 60, reservedBase: 10, availableBase: 50, baseUnit: 'lon' }]
  ]);

  // Quy đổi theo đơn vị thùng (hệ số 24)
  const stockInfoThung = OrderInventory.getProductStockInfo(inventoryMap, 10, 24);
  assert.equal(stockInfoThung.physicalBase, 60);
  assert.equal(stockInfoThung.reservedBase, 10);
  assert.equal(stockInfoThung.availableBase, 50);
  // 50 lon / 24 = 2 thùng tối đa
  assert.equal(stockInfoThung.availableInUnit, 2);
  assert.equal(stockInfoThung.isOutOfStock, false);

  // Kiểm tra đặt 3 thùng -> Bị chặn, gợi ý tối đa 2 thùng
  const check = OrderInventory.validateStock(3, stockInfoThung.availableInUnit);
  assert.equal(check.valid, false);
  assert.equal(check.maxAllowed, 2);

  // Quy đổi theo đơn vị lon lẻ (hệ số 1) -> 50 lon
  const stockInfoLon = OrderInventory.getProductStockInfo(inventoryMap, 10, 1);
  assert.equal(stockInfoLon.availableInUnit, 50);
});

test('S5-06: Phát hiện xung đột đồng thời khi đơn khác vừa duyệt làm giảm tồn khả dụng tức thời', () => {
  const initialInventory = [{ productId: 10, baseQuantity: 10, baseUnit: 'hộp' }];

  // Lúc ban đầu chưa có đơn nào giữ chỗ -> khả dụng 10 hộp
  const map1 = OrderInventory.buildInventoryMap(initialInventory, []);
  assert.equal(map1.get(10).availableBase, 10);

  // Sales A muốn đặt 8 hộp (hợp lệ tại thời điểm bắt đầu)
  assert.equal(OrderInventory.validateStock(8, map1.get(10).availableBase).valid, true);

  // Trong lúc Sales A chuẩn bị chốt, đơn của Sales B được duyệt (APPROVED) chiếm 6 hộp
  const concurrentApprovedOrders = [
    {
      id: 201,
      status: 'APPROVED',
      items: [{ productId: 10, quantity: 6, conversionFactor: 1, baseQuantity: 6 }],
    },
  ];

  // Trước khi gửi đơn, hệ thống cập nhật lại tồn kho tức thời
  const map2 = OrderInventory.buildInventoryMap(initialInventory, concurrentApprovedOrders);
  const latestStock = map2.get(10);
  assert.equal(latestStock.availableBase, 4);

  // Đơn 8 hộp của Sales A bị chặn ngay lập tức, tránh làm âm tồn kho
  const finalCheck = OrderInventory.validateStock(8, latestStock.availableBase);
  assert.equal(finalCheck.valid, false);
  assert.equal(finalCheck.maxAllowed, 4);
});

