/**
 * frontend/js/stock-ledger-utils.js
 * Tiện ích hỗ trợ sổ tồn kho và xuất báo cáo tồn kho ra Excel (S8-08).
 * Tuân thủ Database-backed Permission Matrix và không dùng role-based fallback.
 */
(function (root, factory) {
  if (typeof module === 'object' && module.exports) {
    module.exports = factory();
  } else {
    root.StockLedgerUtils = factory();
  }
})(typeof self !== 'undefined' ? self : this, function () {
  'use strict';

  /**
   * Escape HTML chống tấn công XSS khi hiển thị dữ liệu văn bản.
   */
  function escapeHtml(value) {
    return String(value ?? '').replace(/[&<>"']/g, c => ({
      '&': '&amp;',
      '<': '&lt;',
      '>': '&gt;',
      '"': '&quot;',
      "'": '&#39;',
    })[c]);
  }

  /**
   * Định dạng số lượng theo chuẩn Việt Nam (tối đa 4 chữ số thập phân).
   */
  function formatQuantity(value) {
    if (value == null || value === '') return '0';
    const num = Number(value);
    if (!Number.isFinite(num)) return '0';
    return new Intl.NumberFormat('vi-VN', { maximumFractionDigits: 4 }).format(num);
  }

  /**
   * Xây dựng URL API xuất Excel theo bộ lọc đang áp dụng.
   * Khớp chính xác các tham số máy chủ backend (StockLedgerExportController):
   * warehouseId, categoryId, keyword, stockStatus, belowMinimum
   */
  function buildExportUrl(filters = {}) {
    const params = new URLSearchParams();
    if (filters.warehouseId) params.set('warehouseId', String(filters.warehouseId));
    if (filters.categoryId) params.set('categoryId', String(filters.categoryId));
    if (filters.keyword && String(filters.keyword).trim()) params.set('keyword', String(filters.keyword).trim());
    if (filters.stockStatus) params.set('stockStatus', String(filters.stockStatus));
    if (filters.belowMinimum === true || filters.belowMinimum === 'true') params.set('belowMinimum', 'true');
    const qs = params.toString();
    return `/warehouse/stock-ledger/export${qs ? '?' + qs : ''}`;
  }

  /**
   * Trích xuất tên tệp từ header Content-Disposition trả về từ máy chủ.
   * Hỗ trợ chuẩn RFC 5987 / RFC 6266 (filename*=UTF-8''...) và filename="..." thông thường.
   */
  function parseContentDispositionFilename(disposition) {
    if (!disposition || typeof disposition !== 'string') return null;

    // Ưu tiên cú pháp filename*=UTF-8''... (RFC 5987)
    const starMatch = disposition.match(/filename\*\s*=\s*(?:UTF-8''|utf-8'')([^;]+)/i);
    if (starMatch && starMatch[1]) {
      const raw = starMatch[1].trim().replace(/^["']|["']$/g, '');
      try {
        return decodeURIComponent(raw);
      } catch {
        return raw;
      }
    }

    // Cú pháp filename="..." hoặc filename=...
    const match = disposition.match(/filename\s*=\s*"([^"]+)"|filename\s*=\s*([^;]+)/i);
    if (match) {
      const raw = (match[1] || match[2] || '').trim();
      if (raw) return raw;
    }

    return null;
  }

  /**
   * Tạo tên tệp mặc định khi header Content-Disposition không có hoặc không hợp lệ.
   * Cấu trúc: So-ton-kho_<Mã kho>_<YYYY-MM-DD>.xlsx
   * Đảm bảo đáp ứng tiêu chí: "Tên tệp chứa mã kho và ngày xuất".
   */
  function buildFallbackFilename(warehouseCode, date) {
    const code = (warehouseCode && String(warehouseCode).trim())
      ? String(warehouseCode).trim().replace(/[^\p{L}\p{N}._-]/gu, '_')
      : 'TAT-CA-KHO';
    const dateStr = (date && String(date).trim())
      ? String(date).trim()
      : new Date().toISOString().slice(0, 10);
    return `So-ton-kho_${code}_${dateStr}.xlsx`;
  }

  /**
   * Kiểm tra quyền xem/xuất sổ tồn kho theo ma trận phân quyền DB.
   * Yêu cầu quyền WAREHOUSE_VIEW hoặc vai trò ROLE_ADMIN.
   * Tuyệt đối không dùng vai trò để thay thế quyền (no role-based fallback).
   */
  function canViewStockLedger(auth) {
    if (!auth) return false;
    if (typeof auth.hasRole === 'function' && auth.hasRole('ROLE_ADMIN')) return true;
    if (typeof auth.hasPermission === 'function' && auth.hasPermission('WAREHOUSE_VIEW')) return true;
    return false;
  }

  return {
    escapeHtml,
    formatQuantity,
    buildExportUrl,
    parseContentDispositionFilename,
    buildFallbackFilename,
    canViewStockLedger,
  };
});

