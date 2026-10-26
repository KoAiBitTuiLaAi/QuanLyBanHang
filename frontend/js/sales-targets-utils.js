/**
 * sales-targets-utils.js — Tiện ích cho trang Chỉ tiêu doanh số (S8-04).
 * Module CommonJS/browser-dual: export khi chạy trong Node, gán window khi trên trình duyệt.
 */
(function (root, factory) {
  if (typeof module !== 'undefined' && module.exports) {
    module.exports = factory();
  } else {
    root.SalesTargetUtils = factory();
  }
}(typeof self !== 'undefined' ? self : this, function () {
  'use strict';

  /* ── HẰNG SỐ ─────────────────────────────────────────────────────── */
  const VALID_PERIODS = /^\d{4}-(0[1-9]|1[0-2])$/;
  const MAX_REASON_LENGTH = 500;

  /* ── ĐỊNH DẠNG TIỀN TỆ ───────────────────────────────────────────── */
  /**
   * Định dạng số nguyên sang chuỗi tiền VND (không dùng Intl để đảm bảo
   * đồng nhất trong cả Node và trình duyệt).
   * @param {number|string|null} raw
   * @returns {string}
   */
  function formatMoney(raw) {
    if (raw == null || raw === '') return '0 đ';
    const n = Number(raw);
    if (!Number.isFinite(n)) return '0 đ';
    const rounded = Math.round(n);
    const parts = Math.abs(rounded).toString().split('').reverse();
    const grouped = [];
    for (let i = 0; i < parts.length; i++) {
      if (i > 0 && i % 3 === 0) grouped.push('.');
      grouped.push(parts[i]);
    }
    const body = grouped.reverse().join('');
    return (rounded < 0 ? '-' : '') + body + '\u00a0₫';
  }

  /**
   * Tính phần trăm tiến độ (0–100, làm tròn 1 chữ số thập phân).
   * @param {number} actual
   * @param {number} target
   * @returns {number}
   */
  function calcProgress(actual, target) {
    if (!target || target <= 0) return 0;
    const pct = (Number(actual) / Number(target)) * 100;
    if (!Number.isFinite(pct)) return 0;
    return Math.round(pct * 10) / 10;
  }

  /**
   * Trả về nhãn trạng thái tiến độ.
   * @param {number} pct 0-100+
   * @returns {'danger'|'caution'|'ok'|'full'}
   */
  function progressLevel(pct) {
    if (pct >= 100) return 'full';
    if (pct >= 70)  return 'ok';
    if (pct >= 40)  return 'caution';
    return 'danger';
  }

  /* ── KIỂM TRA KỲ ─────────────────────────────────────────────────── */
  /**
   * Kiểm tra chuỗi kỳ tháng hợp lệ "YYYY-MM".
   * @param {string} period
   * @returns {boolean}
   */
  function isValidPeriod(period) {
    return typeof period === 'string' && VALID_PERIODS.test(period.trim());
  }

  /**
   * So sánh kỳ với tháng hiện tại.
   * @param {string} period  "YYYY-MM"
   * @param {string} [now]   "YYYY-MM" (dùng cho test)
   * @returns {'past'|'current'|'future'}
   */
  function comparePeriod(period, now) {
    const ref = now || new Date().toISOString().slice(0, 7);
    if (period < ref) return 'past';
    if (period === ref) return 'current';
    return 'future';
  }

  /**
   * Kiểm tra chỉ tiêu kỳ đã qua → nên bị khoá.
   * @param {string} period
   * @param {string} [now]
   * @returns {boolean}
   */
  function isPeriodLocked(period, now) {
    return comparePeriod(period, now) === 'past';
  }

  /* ── KIỂM TRA DỮ LIỆU ĐẦU VÀO ───────────────────────────────────── */
  /**
   * Validate form đặt/sửa chỉ tiêu.
   * @param {{ employeeId, targetAmount, period, reason, isLocked, originalAmount }} draft
   * @returns {{ isValid: boolean, errors: string[] }}
   */
  function validateTargetDraft(draft) {
    const errors = [];
    if (!draft.employeeId) {
      errors.push('Bắt buộc chọn nhân viên.');
    }
    if (!isValidPeriod(draft.period)) {
      errors.push('Kỳ tháng không hợp lệ (định dạng YYYY-MM).');
    }
    const amount = Number(draft.targetAmount);
    if (!Number.isFinite(amount) || amount <= 0) {
      errors.push('Chỉ tiêu phải là số lớn hơn 0.');
    }
    if (amount > 999_999_999_999) {
      errors.push('Chỉ tiêu không được vượt quá 999 tỷ.');
    }
    // Nếu kỳ đã khoá (đã qua), bắt buộc nhập lý do sửa
    if (draft.isLocked) {
      const reason = (draft.reason || '').trim();
      if (!reason) {
        errors.push('Kỳ đã khoá — Bắt buộc nhập lý do sửa để ghi nhật ký.');
      } else if (reason.length > MAX_REASON_LENGTH) {
        errors.push(`Lý do sửa không được vượt quá ${MAX_REASON_LENGTH} ký tự.`);
      }
    }
    return { isValid: errors.length === 0, errors };
  }

  /**
   * Validate lý do sửa chỉ tiêu kỳ đã khoá.
   * @param {string} reason
   * @returns {{ isValid: boolean, error: string }}
   */
  function validateEditReason(reason) {
    const r = (reason || '').trim();
    if (!r) return { isValid: false, error: 'Bắt buộc nhập lý do sửa.' };
    if (r.length > MAX_REASON_LENGTH) {
      return { isValid: false, error: `Lý do không được vượt quá ${MAX_REASON_LENGTH} ký tự (hiện ${r.length}).` };
    }
    return { isValid: true, error: '' };
  }

  /* ── TÊN HIỂN THỊ ────────────────────────────────────────────────── */
  /**
   * Tên nhãn so sánh kỳ.
   * @param {'past'|'current'|'future'} rel
   */
  function periodRelLabel(rel) {
    if (rel === 'past')    return 'Kỳ đã khoá';
    if (rel === 'current') return 'Tháng hiện tại';
    return 'Kỳ tương lai';
  }

  /**
   * Tên tháng tiếng Việt.
   * @param {string} period "YYYY-MM"
   */
  function periodLabel(period) {
    if (!period || !VALID_PERIODS.test(period)) return period || '';
    const [year, month] = period.split('-');
    return `Tháng ${parseInt(month, 10)}/${year}`;
  }

  /* ── THOÁT HTML ──────────────────────────────────────────────────── */
  /**
   * Thoát ký tự HTML đặc biệt.
   * @param {*} str
   * @returns {string}
   */
  function escape(str) {
    if (str == null) return '';
    return String(str)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#039;');
  }

  return {
    formatMoney,
    calcProgress,
    progressLevel,
    isValidPeriod,
    comparePeriod,
    isPeriodLocked,
    validateTargetDraft,
    validateEditReason,
    periodRelLabel,
    periodLabel,
    escape,
    MAX_REASON_LENGTH,
  };
}));

