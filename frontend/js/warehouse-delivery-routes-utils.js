/**
 * warehouse-delivery-routes-utils.js — Tiện ích phân tuyến giao hàng (S6-06).
 *
 * Nghiệp vụ S6-06:
 * - Gom nhiều phiếu xuất vào một chuyến giao theo khu vực
 * - Gán tài xế và phương tiện cho chuyến
 * - In danh sách điểm giao của chuyến kèm địa chỉ và số điện thoại người nhận
 *
 * Tuân thủ ma trận phân quyền:
 * - WAREHOUSE_VIEW: Xem danh sách đơn đủ điều kiện và chuyến phân tuyến
 * - WAREHOUSE_EXPORT: Tạo chuyến, phân tuyến, gán tài xế và xe
 * - ROLE_ADMIN: Toàn quyền
 * - Không dùng vai trò cố định làm fallback thay thế quyền.
 * - Tuyệt đối không lưu dữ liệu kinh doanh vào localStorage/sessionStorage.
 */
(() => {
  'use strict';

  /**
   * Định dạng tiền tệ VND chuẩn Việt Nam.
   */
  function formatMoney(amount) {
    if (amount == null || amount === '') return '0 ₫';
    const num = Number(amount);
    if (!Number.isFinite(num)) return '0 ₫';
    return new Intl.NumberFormat('vi-VN', {
      style: 'currency',
      currency: 'VND',
      maximumFractionDigits: 0
    }).format(num);
  }

  /**
   * Định dạng số lượng hoặc số điểm.
   */
  function formatNumber(val) {
    if (val == null || val === '') return '0';
    const num = Number(val);
    if (!Number.isFinite(num)) return '0';
    return new Intl.NumberFormat('vi-VN').format(num);
  }

  /**
   * Định dạng ngày giờ hiển thị.
   */
  function formatDateTime(dateStr) {
    if (!dateStr) return 'Chưa có dữ liệu';
    const d = new Date(dateStr);
    if (!Number.isFinite(d.getTime())) return String(dateStr);
    return d.toLocaleString('vi-VN', {
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit'
    });
  }

  /**
   * Định dạng giá trị input datetime-local từ đối tượng Date hoặc timestamp.
   */
  function toDatetimeLocalValue(date) {
    const d = date ? new Date(date) : new Date();
    if (!Number.isFinite(d.getTime())) return '';
    const pad = n => String(n).padStart(2, '0');
    const YYYY = d.getFullYear();
    const MM = pad(d.getMonth() + 1);
    const DD = pad(d.getDate());
    const hh = pad(d.getHours());
    const mm = pad(d.getMinutes());
    return `${YYYY}-${MM}-${DD}T${hh}:${mm}`;
  }

  /**
   * Trích xuất khu vực (Quận / Huyện / Thị xã / Tỉnh / Thành phố) từ chuỗi địa chỉ giao hàng.
   * Giúp Quản lý kho gom các đơn có cùng địa bàn vào một chuyến xe chạy vòng tối ưu.
   */
  function extractArea(address) {
    if (!address || typeof address !== 'string') return 'Khác / Chưa rõ';
    const clean = address.trim();

    // Tìm kiếm các mẫu Quận/Huyện/Thị xã/Thành phố thông dụng
    const patterns = [
      /(?:Quận|Q\.)\s*([0-9A-Za-zÀ-ỹ\s]+?)(?:,|$)/i,
      /(?:Huyện|H\.)\s*([0-9A-Za-zÀ-ỹ\s]+?)(?:,|$)/i,
      /(?:Thị xã|TX\.)\s*([0-9A-Za-zÀ-ỹ\s]+?)(?:,|$)/i,
      /(?:Thành phố|TP\.)\s*([0-9A-Za-zÀ-ỹ\s]+?)(?:,|$)/i,
      /(?:Phường|P\.)\s*([0-9A-Za-zÀ-ỹ\s]+?)(?:,|$)/i
    ];

    for (const pattern of patterns) {
      const match = clean.match(pattern);
      if (match && match[0]) {
        return match[0].replace(/,\s*$/, '').trim();
      }
    }

    // Nếu có dấu phẩy, lấy phần áp chót hoặc cuối
    const parts = clean.split(',').map(p => p.trim()).filter(Boolean);
    if (parts.length >= 2) {
      return parts[parts.length - 2];
    }
    return parts[0] || 'Khác / Chưa rõ';
  }

  /**
   * Gom nhóm danh sách đơn hàng đủ điều kiện theo khu vực giao hàng.
   */
  function groupOrdersByArea(orders) {
    if (!Array.isArray(orders)) return {};
    const groups = {};
    for (const order of orders) {
      const area = extractArea(order.deliveryAddress);
      if (!groups[area]) {
        groups[area] = [];
      }
      groups[area].push(order);
    }
    return groups;
  }

  /**
   * Kiểm tra tính hợp lệ dữ liệu phân tuyến trước khi gửi lên API.
   */
  function validateTripData(data) {
    const errors = [];

    if (!data.warehouseId) {
      errors.push('Kho xuất phát không được để trống.');
    }

    if (!data.driverName || !data.driverName.trim()) {
      errors.push('Tên tài xế không được để trống.');
    }

    if (!data.vehiclePlate || !data.vehiclePlate.trim()) {
      errors.push('Biển số xe không được để trống.');
    }

    if (!data.expectedCompletionTime) {
      errors.push('Thời gian dự kiến hoàn thành không được để trống.');
    } else if (data.departureTime) {
      const dep = new Date(data.departureTime).getTime();
      const exp = new Date(data.expectedCompletionTime).getTime();
      if (Number.isFinite(dep) && Number.isFinite(exp) && exp < dep) {
        errors.push('Thời gian dự kiến hoàn thành phải sau thời điểm xuất phát.');
      }
    }

    if (!Array.isArray(data.stops) || data.stops.length === 0) {
      errors.push('Chuyến giao hàng phải có ít nhất 1 điểm giao.');
    }

    return {
      isValid: errors.length === 0,
      errors
    };
  }

  /**
   * Xây dựng payload CreateDeliveryTripRequest gửi đến POST /api/warehouse/delivery-trips.
   * Giữ nguyên thứ tự stopOrder đã sắp xếp để xe chạy một vòng giao nhiều điểm.
   */
  function buildCreateTripPayload(formData, stagedStops) {
    const stops = (stagedStops || []).map((stop, index) => ({
      orderId: stop.orderId || null,
      orderCode: stop.orderCode || null,
      dealerId: stop.dealerId || null,
      dealerName: stop.dealerName || null,
      deliveryPointId: stop.deliveryPointId || null,
      receiverName: stop.receiverName || '',
      receiverPhone: stop.receiverPhone || '',
      deliveryAddress: (stop.deliveryAddress || '').trim(),
      deliveryAmount: Number(stop.deliveryAmount ?? stop.totalAmount ?? 0),
      stopOrder: index + 1,
      note: (stop.note || '').trim() || null
    }));

    return {
      warehouseId: Number(formData.warehouseId),
      tripCode: formData.tripCode ? formData.tripCode.trim() : null,
      driverName: formData.driverName.trim(),
      driverPhone: formData.driverPhone ? formData.driverPhone.trim() : null,
      vehiclePlate: formData.vehiclePlate.trim(),
      departureTime: formData.departureTime ? formData.departureTime : null,
      expectedCompletionTime: formData.expectedCompletionTime,
      note: formData.note ? formData.note.trim() : null,
      stops
    };
  }

  /**
   * Tạo mã HTML phiếu in danh sách điểm giao của chuyến kèm địa chỉ và số điện thoại người nhận.
   * Bản in được thiết kế chuyên nghiệp, chuẩn mực tài liệu vận chuyển nội bộ.
   */
  function generateManifestHtml(trip) {
    if (!trip) return '<p>Không có dữ liệu chuyến giao.</p>';

    const stops = Array.isArray(trip.stops) ? [...trip.stops] : [];
    stops.sort((a, b) => Number(a.stopOrder || 0) - Number(b.stopOrder || 0));

    const totalStops = stops.length;
    const totalAmount = stops.reduce((sum, s) => sum + Number(s.deliveryAmount || 0), 0);

    const rowsHtml = stops.map((s, idx) => {
      const order = s.stopOrder || (idx + 1);
      const code = s.orderCode || '—';
      const receiver = s.receiverName || s.dealerName || '—';
      const phone = s.receiverPhone || '—';
      const address = s.deliveryAddress || '—';
      const amount = formatMoney(s.deliveryAmount);
      const note = s.note || '';

      return `
        <tr>
          <td class="text-center font-bold">${order}</td>
          <td class="font-mono text-center">${escapeHtml(code)}</td>
          <td>
            <strong>${escapeHtml(receiver)}</strong>
            ${s.dealerName && s.dealerName !== s.receiverName ? `<div class="sub-text">${escapeHtml(s.dealerName)}</div>` : ''}
          </td>
          <td class="font-bold">${escapeHtml(phone)}</td>
          <td>${escapeHtml(address)}</td>
          <td class="text-right">${amount}</td>
          <td>${escapeHtml(note)}</td>
          <td class="sig-cell"></td>
        </tr>
      `;
    }).join('');

    return `
      <div class="manifest-document">
        <header class="manifest-header">
          <div class="manifest-brand">
            <h2 class="company-name">HỆ THỐNG PHÂN PHỐI SALESTOCK</h2>
            <p class="company-sub">Lộ trình điều phối &amp; Bảng kê điểm giao hàng</p>
          </div>
          <div class="manifest-code-block">
            <div class="manifest-trip-code">${escapeHtml(trip.tripCode || 'CG-DRAFT')}</div>
            <div class="manifest-date">Ngày lập: ${formatDateTime(trip.createdAt || new Date())}</div>
          </div>
        </header>

        <h1 class="manifest-title">LỘ TRÌNH PHÂN TUYẾN &amp; DANH SÁCH ĐIỂM GIAO</h1>

        <section class="manifest-meta-grid">
          <div class="meta-item">
            <span class="meta-label">Kho xuất phát:</span>
            <span class="meta-value font-bold">${escapeHtml(trip.warehouseName || trip.warehouseCode || 'Kho hàng')}</span>
          </div>
          <div class="meta-item">
            <span class="meta-label">Tài xế phụ trách:</span>
            <span class="meta-value font-bold">${escapeHtml(trip.driverName || '—')}</span>
          </div>
          <div class="meta-item">
            <span class="meta-label">Số điện thoại tài xế:</span>
            <span class="meta-value">${escapeHtml(trip.driverPhone || '—')}</span>
          </div>
          <div class="meta-item">
            <span class="meta-label">Biển số phương tiện:</span>
            <span class="meta-value font-bold font-mono">${escapeHtml(trip.vehiclePlate || '—')}</span>
          </div>
          <div class="meta-item">
            <span class="meta-label">Thời điểm xuất phát:</span>
            <span class="meta-value">${formatDateTime(trip.departureTime)}</span>
          </div>
          <div class="meta-item">
            <span class="meta-label">Dự kiến hoàn thành:</span>
            <span class="meta-value font-bold">${formatDateTime(trip.expectedCompletionTime)}</span>
          </div>
        </section>

        ${trip.note ? `<p class="manifest-note"><strong>Ghi chú lộ trình:</strong> ${escapeHtml(trip.note)}</p>` : ''}

        <table class="manifest-table">
          <thead>
            <tr>
              <th style="width: 45px;">Điểm</th>
              <th style="width: 120px;">Mã phiếu xuất</th>
              <th>Người nhận / Đại lý</th>
              <th style="width: 120px;">Số điện thoại</th>
              <th>Địa chỉ giao hàng</th>
              <th style="width: 120px;">Tiền hàng</th>
              <th style="width: 100px;">Ghi chú</th>
              <th style="width: 100px;">Ký nhận</th>
            </tr>
          </thead>
          <tbody>
            ${rowsHtml || '<tr><td colspan="8" class="text-center">Chưa có điểm giao trong chuyến.</td></tr>'}
          </tbody>
          <tfoot>
            <tr>
              <th colspan="5" class="text-right">Tổng cộng (${totalStops} điểm giao):</th>
              <th class="text-right font-bold">${formatMoney(totalAmount)}</th>
              <th colspan="2"></th>
            </tr>
          </tfoot>
        </table>

        <footer class="manifest-signatures">
          <div class="sig-box">
            <p class="sig-role">Quản lý kho phân tuyến</p>
            <p class="sig-note">(Ký, ghi rõ họ tên)</p>
            <div class="sig-space"></div>
            <p class="sig-name">${escapeHtml(trip.createdBy || '')}</p>
          </div>
          <div class="sig-box">
            <p class="sig-role">Tài xế nhận hàng</p>
            <p class="sig-note">(Ký, ghi rõ họ tên)</p>
            <div class="sig-space"></div>
            <p class="sig-name">${escapeHtml(trip.driverName || '')}</p>
          </div>
          <div class="sig-box">
            <p class="sig-role">Thủ kho xuất hàng</p>
            <p class="sig-note">(Ký, ghi rõ họ tên)</p>
            <div class="sig-space"></div>
          </div>
        </footer>
      </div>
    `;
  }

  function escapeHtml(str) {
    if (str == null) return '';
    return String(str).replace(/[&<>"']/g, c => ({
      '&': '&amp;',
      '<': '&lt;',
      '>': '&gt;',
      '"': '&quot;',
      "'": '&#39;'
    }[c]));
  }

  /**
   * Kiểm tra quyền người dùng theo ma trận DB.
   */
  function canManageRoutes(user) {
    if (!user) return false;
    if (user.roles?.includes('ROLE_ADMIN')) return true;
    return user.permissions?.includes('WAREHOUSE_EXPORT') || false;
  }

  function canViewRoutes(user) {
    if (!user) return false;
    if (user.roles?.includes('ROLE_ADMIN')) return true;
    return user.permissions?.includes('WAREHOUSE_VIEW') ||
           user.permissions?.includes('WAREHOUSE_EXPORT') || false;
  }

  const DeliveryRoutesUtils = {
    formatMoney,
    formatNumber,
    formatDateTime,
    toDatetimeLocalValue,
    extractArea,
    groupOrdersByArea,
    validateTripData,
    buildCreateTripPayload,
    generateManifestHtml,
    canManageRoutes,
    canViewRoutes,
    escapeHtml
  };

  if (typeof module !== 'undefined' && module.exports) {
    module.exports = DeliveryRoutesUtils;
  } else {
    window.DeliveryRoutesUtils = DeliveryRoutesUtils;
  }
})();
