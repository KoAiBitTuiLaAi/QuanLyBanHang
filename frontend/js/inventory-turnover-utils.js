(function (root, factory) {
  if (typeof module === "object" && module.exports) {
    module.exports = factory();
  } else {
    root.InventoryTurnoverUtils = factory();
  }
})(typeof self !== "undefined" ? self : this, function () {
  "use strict";

  const TURNOVER_STATUSES = {
    FAST_MOVING: {
      code: "FAST_MOVING",
      label: "Luân chuyển nhanh",
      badgeClass: "badge--success",
      description: "Hàng bán chạy, hệ số vòng quay cao",
    },
    NORMAL_MOVING: {
      code: "NORMAL_MOVING",
      label: "Bình thường",
      badgeClass: "badge--info",
      description: "Luân chuyển ổn định theo kế hoạch",
    },
    SLOW_MOVING: {
      code: "SLOW_MOVING",
      label: "Chậm luân chuyển",
      badgeClass: "badge--warning",
      description: "Số ngày tồn vượt ngưỡng cảnh báo, đang đọng vốn",
    },
    DEAD_STOCK: {
      code: "DEAD_STOCK",
      label: "Chết kho (không xuất)",
      badgeClass: "badge--danger",
      description: "Tồn kho không phát sinh xuất trong kỳ, chôn vốn nghiêm trọng",
    },
    OUT_OF_STOCK: {
      code: "OUT_OF_STOCK",
      label: "Hết hàng",
      badgeClass: "badge--neutral",
      description: "Tồn cuối kỳ bằng 0",
    },
  };

  /**
   * Định dạng tiền tệ VND (vi-VN).
   */
  function formatMoney(amount) {
    if (amount == null || amount === "") return "0 đ";
    const num = Number(amount);
    if (!Number.isFinite(num)) return "0 đ";
    return new Intl.NumberFormat("vi-VN", {
      style: "currency",
      currency: "VND",
    }).format(num);
  }

  /**
   * Định dạng số lượng (tối đa 4 chữ số thập phân).
   */
  function formatQuantity(qty) {
    if (qty == null || qty === "") return "0";
    const num = Number(qty);
    if (!Number.isFinite(num)) return "0";
    return new Intl.NumberFormat("vi-VN", {
      maximumFractionDigits: 4,
    }).format(num);
  }

  /**
   * Định dạng hệ số vòng quay (2 chữ số thập phân).
   */
  function formatTurnoverRate(rate) {
    if (rate == null || rate === "") return "0.00";
    const num = Number(rate);
    if (!Number.isFinite(num)) return "0.00";
    return num.toLocaleString("vi-VN", {
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    });
  }

  /**
   * Định dạng số ngày tồn bình quân.
   */
  function formatDays(days) {
    if (days == null || days === "") return "0";
    const num = Number(days);
    if (!Number.isFinite(num)) return "0";
    return Math.round(num).toLocaleString("vi-VN");
  }

  /**
   * Định dạng tỷ lệ phần trăm (1-2 chữ số thập phân).
   */
  function formatPercent(pct) {
    if (pct == null || pct === "") return "0%";
    const num = Number(pct);
    if (!Number.isFinite(num)) return "0%";
    return num.toLocaleString("vi-VN", {
      minimumFractionDigits: 1,
      maximumFractionDigits: 1,
    }) + "%";
  }

  /**
   * Kiểm tra quyền xem báo cáo tồn kho và vòng quay hàng.
   * Yêu cầu quyền REPORT_INVENTORY hoặc vai trò ROLE_ADMIN.
   * Tuyệt đối không fallback theo role tĩnh.
   */
  function canViewInventoryReport(auth) {
    if (!auth) return false;
    return auth.hasRole("ROLE_ADMIN") || auth.hasPermission("REPORT_INVENTORY");
  }

  /**
   * Kiểm tra tính hợp lệ của khoảng thời gian bộ lọc.
   */
  function validateDateRange(fromDateStr, toDateStr) {
    if (!fromDateStr || !toDateStr) return { valid: true };
    const from = new Date(fromDateStr);
    const to = new Date(toDateStr);
    if (isNaN(from.getTime()) || isNaN(to.getTime())) {
      return { valid: false, error: "Định dạng ngày không hợp lệ" };
    }
    if (from > to) {
      return { valid: false, error: "Từ ngày không được sau Đến ngày" };
    }
    return { valid: true };
  }

  /**
   * Tạo URL dẫn đến Thẻ kho của sản phẩm.
   */
  function getStockCardUrl(productId, warehouseId) {
    const params = new URLSearchParams();
    if (productId) params.set("productId", productId);
    if (warehouseId) params.set("warehouseId", warehouseId);
    const query = params.toString();
    return `/warehouse/stock-card${query ? "?" + query : ""}`;
  }

  /**
   * Lấy thông tin trạng thái luân chuyển.
   */
  function getTurnoverStatus(statusCode) {
    if (!statusCode) return TURNOVER_STATUSES.NORMAL_MOVING;
    const key = String(statusCode).toUpperCase();
    return TURNOVER_STATUSES[key] || {
      code: key,
      label: key,
      badgeClass: "badge--neutral",
      description: "",
    };
  }

  return {
    TURNOVER_STATUSES,
    formatMoney,
    formatQuantity,
    formatTurnoverRate,
    formatDays,
    formatPercent,
    canViewInventoryReport,
    validateDateRange,
    getStockCardUrl,
    getTurnoverStatus,
  };
});

