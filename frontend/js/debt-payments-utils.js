/**
 * debt-payments-utils.js
 * Tiện ích và hàm kiểm tra hợp lệ cho chức năng Ghi nhận thanh toán và đối trừ công nợ (S7-02).
 */
(function (root, factory) {
  if (typeof module !== 'undefined' && module.exports) {
    module.exports = factory();
  } else {
    root.DebtPaymentsUtils = factory();
  }
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  'use strict';

  /**
   * Định dạng tiền tệ VND chuẩn Việt Nam.
   * Ví dụ: 1500000 -> "1.500.000 ₫"
   */
  function formatMoney(amount) {
    if (amount === null || amount === undefined || amount === '') {
      return '—';
    }
    const num = Number(amount);
    if (!Number.isFinite(num)) {
      return '—';
    }
    const parts = Math.round(num).toString().split('.');
    parts[0] = parts[0].replace(/\B(?=(\d{3})+(?!\d))/g, '.');
    return parts.join('.') + ' ₫';
  }

  /**
   * Phân tích chuỗi số tiền người dùng nhập thành số thực.
   */
  function parseMoney(val) {
    if (val === null || val === undefined) return 0;
    if (typeof val === 'number') return Number.isFinite(val) ? val : 0;
    const cleaned = String(val).replace(/\./g, '').replace(/,/g, '.').replace(/[^\d.-]/g, '').trim();
    const num = parseFloat(cleaned);
    return Number.isFinite(num) ? num : 0;
  }

  /**
   * Định dạng ngày ISO YYYY-MM-DD -> DD/MM/YYYY
   */
  function formatDate(isoDate) {
    if (!isoDate) return '—';
    try {
      const parts = String(isoDate).split('T')[0].split('-');
      if (parts.length === 3) {
        return `${parts[2]}/${parts[1]}/${parts[0]}`;
      }
      return isoDate;
    } catch {
      return '—';
    }
  }

  /**
   * Định dạng ngày giờ ISO YYYY-MM-DDTHH:mm:ss -> DD/MM/YYYY HH:mm
   */
  function formatDateTime(isoDateTime) {
    if (!isoDateTime) return '—';
    try {
      const dt = String(isoDateTime).split('T');
      const dParts = dt[0].split('-');
      const dateStr = dParts.length === 3 ? `${dParts[2]}/${dParts[1]}/${dParts[0]}` : dt[0];
      if (dt.length > 1) {
        const timeParts = dt[1].split(':');
        return `${dateStr} ${timeParts[0]}:${timeParts[1]}`;
      }
      return dateStr;
    } catch {
      return '—';
    }
  }

  /**
   * Thông tin hiển thị cho hình thức thu tiền.
   */
  function getPaymentMethodInfo(method) {
    switch (method) {
      case 'TIEN_MAT':
        return { label: 'Tiền mặt', badgeClass: 'badge--cash' };
      case 'CHUYEN_KHOAN':
      case 'BANK_TRANSFER':
        return { label: 'Chuyển khoản', badgeClass: 'badge--transfer' };
      case 'KHAC':
      default:
        return { label: method || 'Khác', badgeClass: 'badge--other' };
    }
  }

  /**
   * Thông tin hiển thị cho loại phiếu thu.
   */
  function getPaymentTypeInfo(type) {
    if (type === 'ADJUSTMENT') {
      return { label: 'Bút toán điều chỉnh', badgeClass: 'badge--adjustment' };
    }
    return { label: 'Phiếu thu', badgeClass: 'badge--payment' };
  }

  /**
   * Thông tin hiển thị cho trạng thái thanh toán hoá đơn.
   */
  function getInvoicePaymentStatusInfo(status) {
    switch (status) {
      case 'PAID':
        return { label: 'Đã thanh toán', badgeClass: 'badge--paid' };
      case 'PARTIALLY_PAID':
        return { label: 'Thanh toán một phần', badgeClass: 'badge--partial' };
      case 'UNPAID':
      default:
        return { label: 'Chưa thanh toán', badgeClass: 'badge--unpaid' };
    }
  }

  /**
   * Kiểm tra hợp lệ dữ liệu ghi nhận thanh toán (S7-02).
   */
  function validatePaymentPayload(payload, currentRemainingDebt) {
    const errors = [];

    if (!payload || typeof payload !== 'object') {
      return { isValid: false, errors: ['Dữ liệu phiếu thu không hợp lệ.'] };
    }

    if (!payload.dealerId) {
      errors.push('Vui lòng chọn đại lý thanh toán.');
    }

    const amount = Number(payload.amount);
    if (!Number.isFinite(amount) || amount <= 0) {
      errors.push('Số tiền thu phải lớn hơn 0.');
    } else if (currentRemainingDebt !== null && currentRemainingDebt !== undefined) {
      const maxDebt = Number(currentRemainingDebt);
      if (Number.isFinite(maxDebt) && amount > maxDebt) {
        errors.push(`Số tiền thu (${formatMoney(amount)}) không được vượt quá tổng công nợ còn lại của đại lý (${formatMoney(maxDebt)}).`);
      }
    }

    if (!payload.paymentMethod || !['TIEN_MAT', 'CHUYEN_KHOAN', 'KHAC'].includes(payload.paymentMethod)) {
      errors.push('Vui lòng chọn hình thức thu tiền hợp lệ (Tiền mặt, Chuyển khoản hoặc Khác).');
    }

    if (!payload.paymentDate) {
      errors.push('Vui lòng chọn ngày thu tiền.');
    } else if (!/^\d{4}-\d{2}-\d{2}$/.test(payload.paymentDate)) {
      errors.push('Ngày thu tiền phải có định dạng YYYY-MM-DD.');
    }

    if (payload.note && String(payload.note).length > 500) {
      errors.push('Ghi chú không được vượt quá 500 ký tự.');
    }

    if (payload.referenceCode && String(payload.referenceCode).length > 100) {
      errors.push('Mã tham chiếu không được vượt quá 100 ký tự.');
    }

    if (payload.recordedBy && String(payload.recordedBy).length > 100) {
      errors.push('Tên người thu không được vượt quá 100 ký tự.');
    }

    // Nếu chọn đối trừ cụ thể (autoAllocate === false)
    if (payload.autoAllocate === false) {
      if (!Array.isArray(payload.allocations) || payload.allocations.length === 0) {
        errors.push('Vui lòng chỉ định ít nhất một hoá đơn để đối trừ khi tắt tự động FIFO.');
      } else {
        let totalAllocated = 0;
        for (let i = 0; i < payload.allocations.length; i++) {
          const item = payload.allocations[i];
          if (!item.orderId) {
            errors.push(`Dòng ${i + 1}: Thiếu mã hoá đơn/đơn hàng đối trừ.`);
          }
          const itemAmount = Number(item.amount);
          if (!Number.isFinite(itemAmount) || itemAmount <= 0) {
            errors.push(`Dòng ${i + 1}: Số tiền đối trừ phải lớn hơn 0.`);
          } else {
            if (item.orderRemainingAmount !== undefined && item.orderRemainingAmount !== null) {
              const maxItemDebt = Number(item.orderRemainingAmount);
              if (Number.isFinite(maxItemDebt) && itemAmount > maxItemDebt) {
                errors.push(`Dòng ${i + 1} (${item.orderCode || 'HĐ'}): Số tiền đối trừ (${formatMoney(itemAmount)}) vượt quá số nợ còn lại của hoá đơn (${formatMoney(maxItemDebt)}).`);
              }
            }
            totalAllocated += itemAmount;
          }
        }

        // Kiểm tra tổng tiền đối trừ phải khớp với số tiền phiếu thu
        if (Number.isFinite(amount) && amount > 0) {
          if (Math.abs(totalAllocated - amount) > 0.01) {
            errors.push(`Tổng số tiền đối trừ các hoá đơn (${formatMoney(totalAllocated)}) phải bằng chính xác số tiền phiếu thu (${formatMoney(amount)}). Chênh lệch: ${formatMoney(Math.abs(totalAllocated - amount))}.`);
          }
        }
      }
    }

    return {
      isValid: errors.length === 0,
      errors: errors
    };
  }

  /**
   * Kiểm tra hợp lệ dữ liệu lập bút toán điều chỉnh (S7-02).
   */
  function validateAdjustmentPayload(payload, originalPaymentAmount) {
    const errors = [];

    if (!payload || typeof payload !== 'object') {
      return { isValid: false, errors: ['Dữ liệu bút toán điều chỉnh không hợp lệ.'] };
    }

    if (!payload.reason || !String(payload.reason).trim()) {
      errors.push('Bắt buộc nhập lý do lập bút toán điều chỉnh.');
    } else if (String(payload.reason).trim().length > 500) {
      errors.push('Lý do điều chỉnh không được vượt quá 500 ký tự.');
    }

    const type = payload.adjustmentType || 'REVERSAL';
    if (!['REVERSAL', 'PARTIAL_REVERSAL'].includes(type)) {
      errors.push('Loại điều chỉnh không hợp lệ (chỉ chấp nhận REVERSAL hoặc PARTIAL_REVERSAL).');
    }

    if (type === 'PARTIAL_REVERSAL') {
      const adjAmount = Number(payload.adjustmentAmount);
      if (!Number.isFinite(adjAmount) || adjAmount <= 0) {
        errors.push('Số tiền điều chỉnh giảm phải lớn hơn 0.');
      } else if (originalPaymentAmount !== null && originalPaymentAmount !== undefined) {
        const orig = Number(originalPaymentAmount);
        if (Number.isFinite(orig) && adjAmount > orig) {
          errors.push(`Số tiền điều chỉnh giảm (${formatMoney(adjAmount)}) không được vượt quá số tiền phiếu thu gốc (${formatMoney(orig)}).`);
        }
      }
    }

    if (payload.adjustmentDate && !/^\d{4}-\d{2}-\d{2}$/.test(payload.adjustmentDate)) {
      errors.push('Ngày điều chỉnh phải có định dạng YYYY-MM-DD.');
    }

    if (payload.note && String(payload.note).length > 500) {
      errors.push('Ghi chú không được vượt quá 500 ký tự.');
    }

    if (payload.recordedBy && String(payload.recordedBy).length > 100) {
      errors.push('Người lập điều chỉnh không được vượt quá 100 ký tự.');
    }

    return {
      isValid: errors.length === 0,
      errors: errors
    };
  }

  /**
   * Kiểm tra quyền xem phiếu thu & công nợ đại lý theo Permission Matrix (không role fallback).
   */
  function canViewDebtPayments(user) {
    if (!user || typeof user !== 'object') return false;
    if (Array.isArray(user.roles) && user.roles.includes('ROLE_ADMIN')) return true;
    return Array.isArray(user.permissions) && user.permissions.includes('FINANCE_VIEW_DEBT');
  }

  /**
   * Kiểm tra quyền lập phiếu thu & lập bút toán điều chỉnh theo Permission Matrix (không role fallback).
   */
  function canManageDebtPayments(user) {
    if (!user || typeof user !== 'object') return false;
    if (Array.isArray(user.roles) && user.roles.includes('ROLE_ADMIN')) return true;
    return Array.isArray(user.permissions) && user.permissions.includes('FINANCE_MANAGE');
  }

  return {
    formatMoney: formatMoney,
    parseMoney: parseMoney,
    formatDate: formatDate,
    formatDateTime: formatDateTime,
    getPaymentMethodInfo: getPaymentMethodInfo,
    getPaymentTypeInfo: getPaymentTypeInfo,
    getInvoicePaymentStatusInfo: getInvoicePaymentStatusInfo,
    validatePaymentPayload: validatePaymentPayload,
    validateAdjustmentPayload: validateAdjustmentPayload,
    canViewDebtPayments: canViewDebtPayments,
    canManageDebtPayments: canManageDebtPayments
  };
});

