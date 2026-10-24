/**
 * debt-statement-utils.js — Tiện ích xử lý số liệu, ngày tháng và ma trận phân quyền
 * cho chức năng S8-10: Sổ chi tiết công nợ đại lý.
 */
const DebtStatementUtils = (() => {
  "use strict";

  const escapeHtml = value => String(value ?? "").replace(/[&<>'"]/g, char => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", "'": "&#39;", '"': "&quot;",
  })[char]);

  /**
   * Định dạng tiền tệ VND chính xác.
   * Xử lý chuỗi, số thực hoặc BigDecimal từ server.
   */
  function formatMoney(value) {
    if (value === null || value === undefined || value === "") return "—";
    const num = Number(value);
    if (isNaN(num)) return String(value);

    // Dùng NumberFormat vi-VN
    const formatted = new Intl.NumberFormat("vi-VN", {
      maximumFractionDigits: 2,
      minimumFractionDigits: 0,
    }).format(Math.abs(num));

    if (num < 0) {
      return `-${formatted} ₫`;
    }
    return `${formatted} ₫`;
  }

  /**
   * Định dạng ngày dd/MM/yyyy.
   */
  function formatDate(dateStr) {
    if (!dateStr) return "—";
    try {
      const parts = String(dateStr).split("T")[0].split("-");
      if (parts.length === 3) {
        return `${parts[2]}/${parts[1]}/${parts[0]}`;
      }
      const d = new Date(dateStr);
      if (isNaN(d.getTime())) return String(dateStr);
      return d.toLocaleDateString("vi-VN");
    } catch {
      return String(dateStr);
    }
  }

  /**
   * Định dạng ngày giờ dd/MM/yyyy HH:mm.
   */
  function formatDateTime(isoStr) {
    if (!isoStr) return "—";
    try {
      const [datePart, timePart] = String(isoStr).split("T");
      if (datePart && timePart) {
        const [y, m, d] = datePart.split("-");
        const [hh, mm] = timePart.split(":");
        if (y && m && d && hh && mm) {
          return `${d}/${m}/${y} ${hh}:${mm}`;
        }
      }
      const dt = new Date(isoStr);
      if (isNaN(dt.getTime())) return String(isoStr);
      const day = String(dt.getDate()).padStart(2, "0");
      const month = String(dt.getMonth() + 1).padStart(2, "0");
      const year = dt.getFullYear();
      const hours = String(dt.getHours()).padStart(2, "0");
      const minutes = String(dt.getMinutes()).padStart(2, "0");
      return `${day}/${month}/${year} ${hours}:${minutes}`;
    } catch {
      return String(isoStr);
    }
  }

  /**
   * Kiểm tra tính hợp lệ của khoảng ngày lọc from - to.
   */
  function validateDateRange(from, to) {
    if (!from || !to) {
      return { valid: false, error: "Vui lòng chọn đầy đủ ngày bắt đầu và ngày kết thúc." };
    }
    const dateRegex = /^\d{4}-\d{2}-\d{2}$/;
    if (!dateRegex.test(from) || !dateRegex.test(to)) {
      return { valid: false, error: "Định dạng ngày không hợp lệ (phải là YYYY-MM-DD)." };
    }
    if (from > to) {
      return { valid: false, error: "Ngày bắt đầu không được lớn hơn ngày kết thúc." };
    }
    const [yFrom] = from.split("-").map(Number);
    const [yTo] = to.split("-").map(Number);
    if (yFrom < 1000 || yTo > 9998) {
      return { valid: false, error: "Năm nằm ngoài phạm vi cho phép (1000 - 9998)." };
    }
    return { valid: true };
  }

  /**
   * Kiểm tra quyền xem sổ chi tiết công nợ theo ma trận DB:
   * Yêu cầu quyền FINANCE_VIEW_DEBT hoặc vai trò ROLE_ADMIN.
   * Tuyệt đối không fallback theo vai trò (ROLE_ACCOUNTANT hay bất kỳ role nào khác không được tự động cấp quyền).
   */
  function canViewDebtStatement(currentUser) {
    if (!currentUser) return false;

    // ROLE_ADMIN luôn có quyền
    const roles = Array.isArray(currentUser.roles) ? currentUser.roles : [];
    if (roles.includes("ROLE_ADMIN")) {
      return true;
    }

    // Kiểm tra quyền từ ma trận DB
    const permissions = Array.isArray(currentUser.permissions) ? currentUser.permissions : [];
    return permissions.includes("FINANCE_VIEW_DEBT");
  }

  /**
   * Lấy thông tin nhãn và CSS badge cho từng loại chứng từ phát sinh.
   */
  function getSourceTypeInfo(sourceType) {
    switch (String(sourceType || "").toUpperCase()) {
      case "ORDER":
        return {
          label: "Đơn hàng",
          badgeClass: "badge--order",
          color: "#2563eb",
          bgColor: "#eff6ff",
        };
      case "PAYMENT":
        return {
          label: "Phiếu thu",
          badgeClass: "badge--payment",
          color: "#059669",
          bgColor: "#ecfdf5",
        };
      case "INVOICE_ADJUSTMENT":
        return {
          label: "Điều chỉnh HĐ",
          badgeClass: "badge--adjustment",
          color: "#d97706",
          bgColor: "#fffbeb",
        };
      default:
        return {
          label: sourceType || "Chứng từ",
          badgeClass: "badge--default",
          color: "#64748b",
          bgColor: "#f1f5f9",
        };
    }
  }

  /**
   * Lấy khoảng ngày mặc định: Từ đầu tháng hiện tại đến ngày hôm nay.
   */
  function getDefaultDateRange() {
    const now = new Date();
    const year = now.getFullYear();
    const month = String(now.getMonth() + 1).padStart(2, "0");
    const day = String(now.getDate()).padStart(2, "0");

    const from = `${year}-${month}-01`;
    const to = `${year}-${month}-${day}`;
    return { from, to };
  }

  /**
   * Xây dựng URL tải file export PDF hoặc Excel.
   */
  function buildExportUrl(dealerId, from, to, format) {
    const cleanDealerId = encodeURIComponent(String(dealerId));
    const cleanFrom = encodeURIComponent(String(from));
    const cleanTo = encodeURIComponent(String(to));
    const cleanFormat = encodeURIComponent(String(format).toLowerCase());
    return `/api/finance/debt/${cleanDealerId}/statement/export?from=${cleanFrom}&to=${cleanTo}&format=${cleanFormat}`;
  }

  return {
    escapeHtml,
    formatMoney,
    formatDate,
    formatDateTime,
    validateDateRange,
    canViewDebtStatement,
    getSourceTypeInfo,
    getDefaultDateRange,
    buildExportUrl,
  };
})();

if (typeof module !== "undefined" && module.exports) {
  module.exports = DebtStatementUtils;
}

