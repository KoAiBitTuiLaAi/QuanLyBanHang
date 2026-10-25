'use strict';
/**
 * delivery-routes.test.cjs — S6-06: Kiểm thử phân tuyến giao hàng và in danh sách điểm giao.
 *
 * Tiêu chí trong ảnh:
 *  1. Gom nhiều phiếu xuất vào một chuyến giao theo khu vực
 *  2. Gán tài xế và phương tiện cho chuyến
 *  3. In danh sách điểm giao của chuyến kèm địa chỉ và số điện thoại người nhận
 *
 * Kiểm tra:
 *  - Tiện ích trích xuất và gom nhóm khu vực (extractArea, groupOrdersByArea)
 *  - Kiểm tra hợp lệ dữ liệu phân tuyến (validateTripData)
 *  - Xây dựng payload CreateDeliveryTripRequest chuẩn API (buildCreateTripPayload)
 *  - Sinh bản in lộ trình chuẩn chứa địa chỉ và số điện thoại người nhận (generateManifestHtml)
 *  - Cấu hình menu phân quyền động DB (menu-config.js)
 *  - Tuyệt đối không lưu dữ liệu kinh doanh vào localStorage/sessionStorage
 */

const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const Utils = require('../frontend/js/warehouse-delivery-routes-utils.js');
const menuSource = fs.readFileSync(path.join(__dirname, '../frontend/js/menu-config.js'), 'utf8');
const menu = vm.runInNewContext(`${menuSource}\nMenuConfig;`, { URLSearchParams });
const ids = user => Array.from(menu.getMenuForUser(user), item => item.id);

/* ── TEST 1: Trích xuất và gom nhóm phiếu xuất theo khu vực ─────────── */
test('S6-06: Trích xuất và gom nhóm phiếu xuất theo khu vực giao hàng', () => {
  // Trích xuất khu vực từ địa chỉ
  assert.equal(Utils.extractArea('Số 45, Đường Lê Duẩn, Quận 1, TP. Hồ Chí Minh'), 'Quận 1');
  assert.equal(Utils.extractArea('120 Nguyễn Thị Minh Khai, Phường 6, Quận 3, TP.HCM'), 'Quận 3');
  assert.equal(Utils.extractArea('Km 10, Quốc lộ 1A, Huyện Bình Chánh, TP.HCM'), 'Huyện Bình Chánh');
  assert.equal(Utils.extractArea('Số 88, Phố Cầu Giấy, Quận Cầu Giấy, Hà Nội'), 'Quận Cầu Giấy');
  assert.equal(Utils.extractArea(''), 'Khác / Chưa rõ');
  assert.equal(Utils.extractArea(null), 'Khác / Chưa rõ');

  // Gom nhóm danh sách đơn hàng theo khu vực
  const sampleOrders = [
    { orderId: 1, orderCode: 'DH-01', deliveryAddress: 'Số 10 Lê Duẩn, Quận 1, TP.HCM', totalAmount: 1000000 },
    { orderId: 2, orderCode: 'DH-02', deliveryAddress: 'Số 20 Nam Kỳ Khởi Nghĩa, Quận 1, TP.HCM', totalAmount: 2000000 },
    { orderId: 3, orderCode: 'DH-03', deliveryAddress: 'Số 80 Cách Mạng Tháng 8, Quận 3, TP.HCM', totalAmount: 1500000 }
  ];

  const groups = Utils.groupOrdersByArea(sampleOrders);
  assert.ok(groups['Quận 1'], 'Phải gom được nhóm Quận 1');
  assert.equal(groups['Quận 1'].length, 2, 'Quận 1 phải có 2 đơn hàng');
  assert.ok(groups['Quận 3'], 'Phải gom được nhóm Quận 3');
  assert.equal(groups['Quận 3'].length, 1, 'Quận 3 phải có 1 đơn hàng');
});

/* ── TEST 2: Validate dữ liệu phân tuyến & gán tài xế, phương tiện ───── */
test('S6-06: Kiểm tra hợp lệ dữ liệu phân tuyến, gán tài xế và phương tiện', () => {
  // Thiếu thông tin bắt buộc
  const invalidEmpty = Utils.validateTripData({});
  assert.equal(invalidEmpty.isValid, false);
  assert.ok(invalidEmpty.errors.some(e => e.includes('Kho xuất phát')));
  assert.ok(invalidEmpty.errors.some(e => e.includes('tài xế')));
  assert.ok(invalidEmpty.errors.some(e => e.includes('Biển số xe')));
  assert.ok(invalidEmpty.errors.some(e => e.includes('Thời gian dự kiến hoàn thành')));
  assert.ok(invalidEmpty.errors.some(e => e.includes('ít nhất 1 điểm giao')));

  // Thời gian dự kiến trước thời gian xuất phát
  const invalidTime = Utils.validateTripData({
    warehouseId: 1,
    driverName: 'Trần Văn Bình',
    vehiclePlate: '29C-123.45',
    departureTime: '2026-10-25T14:00',
    expectedCompletionTime: '2026-10-25T13:00',
    stops: [{ orderId: 1, deliveryAddress: 'Hà Nội' }]
  });
  assert.equal(invalidTime.isValid, false);
  assert.ok(invalidTime.errors.some(e => e.includes('sau thời điểm xuất phát')));

  // Dữ liệu hợp lệ đầy đủ
  const valid = Utils.validateTripData({
    warehouseId: 1,
    driverName: 'Trần Văn Bình',
    driverPhone: '0912345678',
    vehiclePlate: '29C-123.45',
    departureTime: '2026-10-25T14:00',
    expectedCompletionTime: '2026-10-25T17:00',
    stops: [
      { orderId: 1, deliveryAddress: 'Địa chỉ 1', totalAmount: 1000000 },
      { orderId: 2, deliveryAddress: 'Địa chỉ 2', totalAmount: 2000000 }
    ]
  });
  assert.equal(valid.isValid, true);
  assert.equal(valid.errors.length, 0);
});

/* ── TEST 3: Xây dựng payload gửi lên backend CreateDeliveryTripRequest ─ */
test('S6-06: Xây dựng payload chuyến giao bảo toàn thứ tự điểm dừng để xe chạy một vòng', () => {
  const formData = {
    warehouseId: '2',
    tripCode: 'CG-20261025-01',
    driverName: 'Nguyễn Văn Tài',
    driverPhone: '0988776655',
    vehiclePlate: '51D-888.99',
    departureTime: '2026-10-25T08:00',
    expectedCompletionTime: '2026-10-25T12:00',
    note: 'Chạy vòng Quận 1 sang Quận 7'
  };

  const stagedStops = [
    {
      orderId: 101,
      orderCode: 'DH-101',
      dealerId: 5,
      dealerName: 'Đại lý Kim Liên',
      deliveryPointId: 10,
      receiverName: 'Chị Mai',
      receiverPhone: '0901112233',
      deliveryAddress: '15 Lê Duẩn, Q1, TP.HCM',
      deliveryAmount: 5000000,
      note: 'Giao trước 9h'
    },
    {
      orderId: 102,
      orderCode: 'DH-102',
      dealerId: 8,
      dealerName: 'Đại lý Tân Thuận',
      deliveryPointId: 12,
      receiverName: 'Anh Tuấn',
      receiverPhone: '0904445566',
      deliveryAddress: '200 Nguyễn Thị Thập, Q7, TP.HCM',
      deliveryAmount: 8000000,
      note: 'Giao sau 10h'
    }
  ];

  const payload = Utils.buildCreateTripPayload(formData, stagedStops);

  assert.equal(payload.warehouseId, 2);
  assert.equal(payload.tripCode, 'CG-20261025-01');
  assert.equal(payload.driverName, 'Nguyễn Văn Tài');
  assert.equal(payload.driverPhone, '0988776655');
  assert.equal(payload.vehiclePlate, '51D-888.99');
  assert.equal(payload.stops.length, 2);

  // Bảo toàn thứ tự điểm dừng
  assert.equal(payload.stops[0].stopOrder, 1);
  assert.equal(payload.stops[0].orderId, 101);
  assert.equal(payload.stops[0].receiverName, 'Chị Mai');
  assert.equal(payload.stops[0].receiverPhone, '0901112233');
  assert.equal(payload.stops[0].deliveryAddress, '15 Lê Duẩn, Q1, TP.HCM');

  assert.equal(payload.stops[1].stopOrder, 2);
  assert.equal(payload.stops[1].orderId, 102);
  assert.equal(payload.stops[1].receiverName, 'Anh Tuấn');
  assert.equal(payload.stops[1].receiverPhone, '0904445566');
  assert.equal(payload.stops[1].deliveryAddress, '200 Nguyễn Thị Thập, Q7, TP.HCM');
});

/* ── TEST 4: In danh sách điểm giao kèm địa chỉ và số điện thoại ───── */
test('S6-06: Tạo bản in danh sách điểm giao thể hiện đầy đủ địa chỉ và số điện thoại người nhận', () => {
  const trip = {
    tripCode: 'CG-20261025-001',
    warehouseName: 'Kho Tổng Hà Nội',
    driverName: 'Trần Văn Bình',
    driverPhone: '0912345678',
    vehiclePlate: '29C-123.45',
    departureTime: '2026-10-25T08:00:00',
    expectedCompletionTime: '2026-10-25T11:30:00',
    note: 'Giao theo tuyến đường vành đai',
    stops: [
      {
        stopOrder: 1,
        orderCode: 'DH-HN-001',
        receiverName: 'Nguyễn Văn An',
        receiverPhone: '0987654321',
        deliveryAddress: 'Số 12 Chùa Bộc, Đống Đa, Hà Nội',
        deliveryAmount: 12500000,
        note: 'Giao trước 9h30'
      },
      {
        stopOrder: 2,
        orderCode: 'DH-HN-002',
        receiverName: 'Trần Thị Bích',
        receiverPhone: '0911223344',
        deliveryAddress: 'Số 88 Nguyễn Trãi, Thanh Xuân, Hà Nội',
        deliveryAmount: 17200000,
        note: 'Gọi trước 15 phút'
      }
    ]
  };

  const html = Utils.generateManifestHtml(trip);

  // Tiêu đề & Thông tin chuyến
  assert.ok(html.includes('LỘ TRÌNH PHÂN TUYẾN &amp; DANH SÁCH ĐIỂM GIAO'));
  assert.ok(html.includes('CG-20261025-001'));
  assert.ok(html.includes('Kho Tổng Hà Nội'));

  // Thông tin tài xế & phương tiện
  assert.ok(html.includes('Trần Văn Bình'));
  assert.ok(html.includes('0912345678'));
  assert.ok(html.includes('29C-123.45'));

  // Bắt buộc: Kèm địa chỉ và số điện thoại người nhận
  assert.ok(html.includes('Nguyễn Văn An'));
  assert.ok(html.includes('0987654321'), 'Phải chứa số điện thoại người nhận điểm 1');
  assert.ok(html.includes('Số 12 Chùa Bộc, Đống Đa, Hà Nội'), 'Phải chứa địa chỉ giao điểm 1');

  assert.ok(html.includes('Trần Thị Bích'));
  assert.ok(html.includes('0911223344'), 'Phải chứa số điện thoại người nhận điểm 2');
  assert.ok(html.includes('Số 88 Nguyễn Trãi, Thanh Xuân, Hà Nội'), 'Phải chứa địa chỉ giao điểm 2');

  // Tổng cộng
  assert.ok(html.includes('29.700.000'), 'Tổng tiền hàng phải đúng');
});

/* ── TEST 5: Cấu hình Menu & Phân quyền ma trận DB ─────────────────── */
test('S6-06: Menu Phân tuyến giao hàng tuân thủ ma trận quyền DB, không dùng role fallback', () => {
  // ROLE_ADMIN luôn có quyền
  assert.ok(ids({ roles: ['ROLE_ADMIN'], permissions: [] }).includes('delivery-routes'));

  // Các vai trò nội bộ chỉ truy cập khi có quyền WAREHOUSE_VIEW hoặc WAREHOUSE_EXPORT
  for (const role of ['ROLE_WAREHOUSE_MANAGER', 'ROLE_WAREHOUSE_STAFF', 'ROLE_VIEWER', 'ROLE_SALES']) {
    // Không có quyền -> Không hiện menu
    assert.ok(!ids({ roles: [role], permissions: [] }).includes('delivery-routes'),
      `Role ${role} không có quyền không được hiện menu`);

    // Có WAREHOUSE_VIEW -> Hiện menu
    assert.ok(ids({ roles: [role], permissions: ['WAREHOUSE_VIEW'] }).includes('delivery-routes'),
      `Role ${role} có WAREHOUSE_VIEW phải hiện menu`);

    // Có WAREHOUSE_EXPORT -> Hiện menu
    assert.ok(ids({ roles: [role], permissions: ['WAREHOUSE_EXPORT'] }).includes('delivery-routes'),
      `Role ${role} có WAREHOUSE_EXPORT phải hiện menu`);

    // Quyền khác không liên quan -> Không hiện menu
    assert.ok(!ids({ roles: [role], permissions: ['ORDER_VIEW', 'FINANCE_VIEW_DEBT'] }).includes('delivery-routes'));
  }

  // Đại lý (ROLE_DEALER) không có quyền truy cập menu kho nội bộ
  assert.ok(!ids({ roles: ['ROLE_DEALER'], permissions: ['WAREHOUSE_VIEW', 'WAREHOUSE_EXPORT'] }).includes('delivery-routes'));

  // Kiểm tra giải quyết URL chính xác
  assert.equal(menu.getPageForLocation(new URL('http://test/warehouse/delivery-routes')).id, 'delivery-routes');
});

/* ── TEST 6: Đảm bảo không lưu dữ liệu kinh doanh vào localStorage ─── */
test('S6-06: Không lưu dữ liệu kinh doanh vào localStorage/sessionStorage', () => {
  const code = fs.readFileSync(path.join(__dirname, '../frontend/js/warehouse-delivery-routes.js'), 'utf8');
  assert.ok(!code.includes('localStorage.setItem'), 'Không dùng localStorage.setItem');
  assert.ok(!code.includes('sessionStorage.setItem'), 'Không dùng sessionStorage.setItem');
});
