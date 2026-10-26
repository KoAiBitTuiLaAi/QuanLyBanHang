const assert = require('node:assert/strict');
const { test } = require('node:test');
const OrderInventory = require('../frontend/js/order-inventory.js');

test('Tồn khả dụng bằng tồn thực tế trừ tồn đang giữ chỗ cho đơn khác', () => {
  // Thực tế 100, giữ chỗ 30 -> khả dụng 70
  assert.equal(OrderInventory.calculateAvailable(100, 30), 70);

  // Giữ chỗ lớn hơn thực tế -> khả dụng không âm (về 0)
  assert.equal(OrderInventory.calculateAvailable(50, 60), 0);

  // Tồn thực tế 0, giữ chỗ 10 -> 0
  assert.equal(OrderInventory.calculateAvailable(0, 10), 0);

  // Không có giữ chỗ -> bằng tồn thực tế
  assert.equal(OrderInventory.calculateAvailable(150, 0), 150);

  // Giá trị null/undefined -> an toàn về 0
  assert.equal(OrderInventory.calculateAvailable(null, null), 0);
});

test('Quy đổi tồn khả dụng theo đơn vị tính với hệ số quy đổi tương ứng', () => {
  // 1 thùng = 24 lon; có 50 lon khả dụng -> tối đa đặt được 2 thùng (48 lon)
  assert.equal(OrderInventory.convertStockToUnit(50, 24), 2);

  // Có đúng 48 lon -> 2 thùng
  assert.equal(OrderInventory.convertStockToUnit(48, 24), 2);

  // Có 23 lon -> 0 thùng
  assert.equal(OrderInventory.convertStockToUnit(23, 24), 0);

  // Đơn vị cơ sở (hệ số 1): 50 lon -> 50 lon
  assert.equal(OrderInventory.convertStockToUnit(50, 1), 50);

  // Hệ số không hợp lệ hoặc <= 0 -> trả về 0
  assert.equal(OrderInventory.convertStockToUnit(50, 0), 0);
  assert.equal(OrderInventory.convertStockToUnit(50, -5), 0);
});

test('Chỉ đơn đã duyệt giữ chỗ, không giữ chỗ cho đơn chờ duyệt', () => {
  const orders = [
    {
      id: 1,
      status: 'RECORDED',
      items: [
        { productId: 10, quantity: 2, conversionFactor: 24, baseQuantity: 48 },
        { productId: 20, quantity: 5, conversionFactor: 1, baseQuantity: 5 },
      ],
    },
    {
      id: 2,
      status: 'PENDING_APPROVAL',
      items: [
        { productId: 10, quantity: 1, conversionFactor: 24, baseQuantity: 24 },
      ],
    },
    {
      id: 3,
      status: 'CANCELLED', // Đã huỷ -> không giữ chỗ
      items: [
        { productId: 10, quantity: 3, conversionFactor: 24, baseQuantity: 72 },
      ],
    },
    {
      id: 4,
      status: 'REJECTED', // Từ chối -> không giữ chỗ
      items: [
        { productId: 10, quantity: 1, conversionFactor: 24, baseQuantity: 24 },
      ],
    },
  ];

  // SKU 10: 48 từ đơn đã ghi nhận; đơn chờ duyệt không giữ chỗ.
  assert.equal(OrderInventory.calculateReservedByProduct(orders, 10), 48);

  // SKU 20: 5 lon
  assert.equal(OrderInventory.calculateReservedByProduct(orders, 20), 5);

  // SKU 99 không có trong đơn -> 0
  assert.equal(OrderInventory.calculateReservedByProduct(orders, 99), 0);
});

test('Đặt vượt tồn khả dụng bị chặn và có gợi ý số lượng tối đa còn đặt được', () => {
  // Còn 5 thùng, yêu cầu 8 thùng -> bị chặn, gợi ý tối đa 5
  const resultOver = OrderInventory.validateStock(8, 5);
  assert.equal(resultOver.valid, false);
  assert.equal(resultOver.maxAllowed, 5);
  assert.match(resultOver.message, /Vượt tồn khả dụng/);
  assert.match(resultOver.message, /5/);

  // Còn 5 thùng, yêu cầu 5 thùng -> hợp lệ
  const resultExact = OrderInventory.validateStock(5, 5);
  assert.equal(resultExact.valid, true);
  assert.equal(resultExact.maxAllowed, 5);

  // Còn 5 thùng, yêu cầu 3 thùng -> hợp lệ
  const resultOk = OrderInventory.validateStock(3, 5);
  assert.equal(resultOk.valid, true);

  // Hết hàng (tồn 0), yêu cầu 1 -> bị chặn với thông báo hết hàng
  const resultZero = OrderInventory.validateStock(1, 0);
  assert.equal(resultZero.valid, false);
  assert.equal(resultZero.maxAllowed, 0);
  assert.match(resultZero.message, /hết tồn khả dụng/);

  // Số lượng <= 0 -> bị chặn
  const resultInvalid = OrderInventory.validateStock(0, 5);
  assert.equal(resultInvalid.valid, false);
  assert.match(resultInvalid.message, /lớn hơn 0/);
});

test('Xây dựng bảng tồn kho tổng hợp từ tồn thực tế và đơn hàng hiện hữu', () => {
  const inventoryList = [
    { productId: 10, baseQuantity: 100, baseUnit: 'lon' },
    { productId: 20, baseQuantity: 40, baseUnit: 'chai' },
  ];
  const ordersList = [
    {
      id: 1,
      status: 'RECORDED',
      items: [{ productId: 10, baseQuantity: 30, baseUnit: 'lon' }],
    },
  ];

  const map = OrderInventory.buildInventoryMap(inventoryList, ordersList);
  const item10 = map.get(10);
  assert.equal(item10.physicalBase, 100);
  assert.equal(item10.reservedBase, 30);
  assert.equal(item10.availableBase, 70);

  const item20 = map.get(20);
  assert.equal(item20.physicalBase, 40);
  assert.equal(item20.reservedBase, 0);
  assert.equal(item20.availableBase, 40);
});

test('Hai người cùng chốt đơn trên SKU sắp hết: phát hiện tồn giảm tức thời và chặn không âm tồn', () => {
  // Kho ban đầu có 10 sản phẩm
  const initialInventory = [{ productId: 10, baseQuantity: 10, baseUnit: 'hộp' }];
  const emptyOrders = [];

  // Người thứ nhất đang tạo đơn 8 hộp
  const mapBefore = OrderInventory.buildInventoryMap(initialInventory, emptyOrders);
  const stockBefore = mapBefore.get(10);
  assert.equal(stockBefore.availableBase, 10);
  assert.equal(OrderInventory.validateStock(8, stockBefore.availableBase).valid, true);

  // Người thứ hai chốt đơn 6 hộp trước (đơn được ghi nhận vào hệ thống)
  const concurrentOrders = [
    {
      id: 101,
      status: 'RECORDED',
      items: [{ productId: 10, baseQuantity: 6, baseUnit: 'hộp' }],
    },
  ];

  // Người thứ nhất bấm xác nhận tạo đơn -> kiểm tra lại trước khi gửi
  const mapAfter = OrderInventory.buildInventoryMap(initialInventory, concurrentOrders);
  const stockAfter = mapAfter.get(10);

  // Tồn khả dụng mới = 10 - 6 = 4 hộp
  assert.equal(stockAfter.availableBase, 4);

  // Kiểm tra 8 hộp so với tồn mới -> BỊ CHẶN, gợi ý tối đa còn 4 hộp
  const validateResult = OrderInventory.validateStock(8, stockAfter.availableBase);
  assert.equal(validateResult.valid, false);
  assert.equal(validateResult.maxAllowed, 4);
  assert.match(validateResult.message, /Vượt tồn khả dụng/);
  // Không cho phép gửi để đảm bảo không bị âm tồn!
});

test('Xác định kho phục vụ cho đại lý theo thứ tự ưu tiên', () => {
  const warehouses = [
    { id: 1, code: 'KHO_TONG', name: 'Kho Tổng Hà Nội' },
    { id: 2, code: 'KHO_MN', name: 'Kho Miền Nam' },
  ];

  // Ưu tiên 1: Dealer có warehouseId
  const dealerWithWarehouse = { id: 5, warehouseId: 2 };
  assert.equal(OrderInventory.resolveServingWarehouse(dealerWithWarehouse, {}, warehouses).id, 2);

  // Ưu tiên 2: User có warehouse
  const userWithWarehouse = { warehouses: [{ id: 2 }] };
  assert.equal(OrderInventory.resolveServingWarehouse({}, userWithWarehouse, warehouses).id, 2);

  // Ưu tiên 3: Mặc định kho đầu tiên
  assert.equal(OrderInventory.resolveServingWarehouse({}, {}, warehouses).id, 1);
});

