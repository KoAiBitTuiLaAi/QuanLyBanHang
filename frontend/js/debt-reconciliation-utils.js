/**
 * debt-reconciliation-utils.js — Tiện ích xử lý số liệu, ngày tháng, biên bản đối chiếu
 * và ma trận phân quyền cho chức năng S7-06: Biên bản đối chiếu công nợ định kỳ.
 */
const DebtReconciliationUtils = (() => {
  "use strict";

  const escapeHtml = value => String(value ?? "").replace(/[&<>'"]/g, char => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", "'": "&#39;", '"': "&quot;",
  })[char]);

  /**
   * Định dạng tiền tệ VND chính xác.
   */
  function formatMoney(value) {
    if (value === null || value === undefined || value === "") return "—";
    const cents = moneyCents(value);
    if (cents === null) return "—";
    const absolute = cents < 0n ? -cents : cents;
    const whole = (absolute / 100n).toLocaleString("vi-VN");
    const fraction = String(absolute % 100n).padStart(2, "0").replace(/0+$/, "");
    return (cents < 0n ? "-" : "") + whole + (fraction ? "," + fraction : "") + " ₫";
  }

  function moneyCents(value) {
    const text = String(value ?? "").trim();
    // A difference between two valid 22-digit balances may have 23 integer digits.
    if (!/^-?\d{1,24}(\.\d{1,2})?$/.test(text)) return null;
    const negative = text.startsWith("-");
    const [whole, fraction = ""] = text.replace(/^-/, "").split(".");
    const cents = BigInt(whole) * 100n + BigInt(fraction.padEnd(2, "0"));
    return negative ? -cents : cents;
  }
  function moneyDifference(system, claimed) {
    const a = moneyCents(system), b = moneyCents(claimed);
    if (a === null || b === null) return null;
    const value = a - b, absolute = value < 0n ? -value : value;
    return (value < 0n ? "-" : "") + (absolute / 100n) + "." + String(absolute % 100n).padStart(2, "0");
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
   * Kiểm tra tính hợp lệ của khoảng ngày đối chiếu from - to.
   */
  function validateDateRange(from, to) {
    if (!from || !to) {
      return { valid: false, error: "Vui lòng chọn đầy đủ ngày bắt đầu và ngày kết thúc." };
    }
    const dateRegex = /^\d{4}-\d{2}-\d{2}$/;
    if (!dateRegex.test(from) || !dateRegex.test(to)) {
      return { valid: false, error: "Định dạng ngày không hợp lệ (phải là YYYY-MM-DD)." };
    }
    for (const date of [from, to]) {
      const parsed = new Date(date + "T00:00:00Z");
      if (!Number.isFinite(parsed.getTime()) || parsed.toISOString().slice(0, 10) !== date)
        return { valid: false, error: "Ngày đối chiếu không tồn tại." };
    }
    if (from > to) {
      return { valid: false, error: "Ngày bắt đầu không được lớn hơn ngày kết thúc kỳ đối chiếu." };
    }
    const [yFrom] = from.split("-").map(Number);
    const [yTo] = to.split("-").map(Number);
    if (yFrom < 1000 || yTo > 9998) {
      return { valid: false, error: "Năm nằm ngoài phạm vi cho phép (1000 - 9998)." };
    }
    return { valid: true };
  }

  /**
   * Kiểm tra quyền truy cập chức năng đối chiếu công nợ theo ma trận DB:
   * Yêu cầu quyền FINANCE_VIEW_DEBT hoặc vai trò ROLE_ADMIN.
   * Không dùng role-based fallback.
   */
  function canAccessReconciliation(currentUser) {
    if (!currentUser) return false;

    // ROLE_ADMIN luôn có toàn quyền
    const roles = Array.isArray(currentUser.roles) ? currentUser.roles : [];
    if (roles.includes("ROLE_ADMIN")) {
      return true;
    }

    // Kiểm tra quyền từ ma trận DB
    const permissions = Array.isArray(currentUser.permissions) ? currentUser.permissions : [];
    return permissions.includes("FINANCE_VIEW_DEBT");
  }

  /**
   * Kiểm tra xem người dùng hiện tại có phải là đại lý không (ROLE_DEALER và không phải ADMIN).
   */
  function isDealerUser(currentUser) {
    if (!currentUser) return false;
    const roles = Array.isArray(currentUser.roles) ? currentUser.roles : [];
    return roles.includes("ROLE_DEALER") && !roles.includes("ROLE_ADMIN");
  }

  /**
   * Lấy thông tin nhãn loại chứng từ.
   */
  function getSourceTypeInfo(sourceType) {
    switch (String(sourceType || "").toUpperCase()) {
      case "ORDER":
        return { label: "Đơn hàng", badgeClass: "badge--order" };
      case "PAYMENT":
        return { label: "Phiếu thu", badgeClass: "badge--payment" };
      case "INVOICE_ADJUSTMENT":
        return { label: "Điều chỉnh HĐ", badgeClass: "badge--adjustment" };
      default:
        return { label: sourceType || "Chứng từ", badgeClass: "badge--default" };
    }
  }

  /**
   * Xác thực và tính toán phản hồi lệch của đại lý.
   */
  function validateDiscrepancyFeedback(systemClosingBalance, dealerClaimedAmount, reason) {
    const sysBalance = String(systemClosingBalance ?? "");
    const trimmedReason = String(reason || "").trim();

    if (dealerClaimedAmount === null || dealerClaimedAmount === undefined || dealerClaimedAmount === "") {
      return { valid: false, error: "Vui lòng nhập số dư công nợ theo sổ sách của đại lý." };
    }

    const claimed = String(dealerClaimedAmount).trim();
    if (!/^-?\d{1,22}(\.\d{1,2})?$/.test(claimed) || moneyCents(systemClosingBalance) === null) {
      return { valid: false, error: "Số dư theo đại lý phải là số hợp lệ." };
    }

    if (!trimmedReason) {
      return { valid: false, error: "Vui lòng nhập lý do hoặc chi tiết các chứng từ chênh lệch." };
    }

    if (trimmedReason.length > 500) {
      return { valid: false, error: "Nội dung phản hồi không được vượt quá 500 ký tự." };
    }

    const difference = moneyDifference(sysBalance, claimed);

    return {
      valid: true,
      systemBalance: sysBalance,
      dealerClaimedAmount: claimed,
      difference: difference,
      reason: trimmedReason,
      isMatched: moneyCents(difference) === 0n,
    };
  }

  /**
   * Khoảng ngày mặc định cho kỳ đối chiếu (Tháng hiện tại).
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
   * Xây dựng URL xuất file PDF biên bản đối chiếu.
   */
  function buildExportUrl(id, portal = false) {
    if (!/^[1-9]\d*$/.test(String(id))) throw new Error("Mã biên bản không hợp lệ.");
    return `/api/${portal ? "portal" : "finance"}/debt-reconciliations/${id}/pdf`;
  }

  return {
    escapeHtml,
    moneyCents,
    moneyDifference,
    formatMoney,
    formatDate,
    formatDateTime,
    validateDateRange,
    canAccessReconciliation,
    isDealerUser,
    getSourceTypeInfo,
    validateDiscrepancyFeedback,
    getDefaultDateRange,
    buildExportUrl,
  };
})();

if (typeof module !== "undefined" && module.exports) {
  module.exports = DebtReconciliationUtils;
}

