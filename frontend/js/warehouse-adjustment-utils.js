(function (root, factory) {
  if (typeof module === "object" && module.exports) {
    module.exports = factory();
  } else {
    root.WarehouseAdjustmentUtils = factory();
  }
})(typeof self !== "undefined" ? self : this, function () {
  "use strict";

  const REASONS = {
    DAMAGED: {
      code: "DAMAGED",
      label: "Hàng hỏng",
      description: "Hàng bị hư hỏng, vỡ, biến chất trong quá trình lưu kho",
    },
    LOST: {
      code: "LOST",
      label: "Hàng mất",
      description: "Hàng bị hao hụt, mất mát, không tìm thấy khi đối soát",
    },
    EXPIRED: {
      code: "EXPIRED",
      label: "Hàng hết hạn phải huỷ",
      description: "Hàng quá hạn sử dụng, không còn được phép lưu hành hoặc bán",
    },
    OTHER: {
      code: "OTHER",
      label: "Lý do khác",
      description: "Lý do bất khả kháng hoặc sai lệch kho đặc thù khác",
    },
  };

  const STATUSES = {
    PENDING_APPROVAL: {
      code: "PENDING_APPROVAL",
      label: "Chờ phê duyệt",
      badgeClass: "badge--warning",
    },
    APPROVED: {
      code: "APPROVED",
      label: "Đã phê duyệt",
      badgeClass: "badge--success",
    },
    RECORDED: {
      code: "RECORDED",
      label: "Đã ghi sổ",
      badgeClass: "badge--success",
    },
    REJECTED: {
      code: "REJECTED",
      label: "Đã từ chối",
      badgeClass: "badge--danger",
    },
    DRAFT: {
      code: "DRAFT",
      label: "Bản nháp",
      badgeClass: "badge--draft",
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
   * Định dạng số lượng (tối đa 4 số thập phân).
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
   * Tính giá trị tổn thất của một dòng hàng (Số lượng * Đơn giá).
   */
  function calculateLineLoss(quantity, unitPrice) {
    const q = Number(quantity);
    const p = Number(unitPrice);
    if (!Number.isFinite(q) || q <= 0 || !Number.isFinite(p) || p < 0) {
      return 0;
    }
    return Math.round(q * p * 100) / 100;
  }

  /**
   * Tính tổng giá trị tổn thất toàn phiếu.
   */
  function calculateTotalLoss(items) {
    if (!Array.isArray(items) || items.length === 0) return 0;
    return items.reduce((sum, item) => {
      const lineLoss = item.lossValue != null && Number.isFinite(Number(item.lossValue))
        ? Number(item.lossValue)
        : calculateLineLoss(item.quantity, item.unitPrice || item.price);
      return sum + lineLoss;
    }, 0);
  }

  /**
   * Kiểm tra tính hợp lệ của việc lập phiếu điều chỉnh.
   */
  function validateAdjustmentDraft(data) {
    const errors = [];

    if (!data.warehouseId) {
      errors.push("Vui lòng chọn kho thực hiện điều chỉnh");
    }

    if (!data.reason || !REASONS[data.reason]) {
      errors.push("Bắt buộc chọn lý do điều chỉnh (Hàng hỏng, Hàng mất, hoặc Hàng hết hạn phải huỷ)");
    }

    if (!data.reasonDetail || !data.reasonDetail.trim()) {
      errors.push("Bắt buộc ghi rõ chi tiết/giải trình nguyên nhân điều chỉnh");
    } else if (data.reasonDetail.trim().length > 500) {
      errors.push("Chi tiết lý do không được vượt quá 500 ký tự");
    }

    if (!Array.isArray(data.items) || data.items.length === 0) {
      errors.push("Phiếu điều chỉnh phải có ít nhất một mặt hàng");
    } else {
      data.items.forEach((item, index) => {
        const lineNum = index + 1;
        if (!item.productId) {
          errors.push(`Dòng ${lineNum}: Chưa chọn sản phẩm`);
        }
        const qty = Number(item.quantity);
        if (!Number.isFinite(qty) || qty <= 0) {
          errors.push(`Dòng ${lineNum}: Số lượng điều chỉnh giảm phải lớn hơn 0`);
        }
        if (item.availableQuantity != null && qty > Number(item.availableQuantity)) {
          errors.push(
            `Dòng ${lineNum}: Số lượng điều chỉnh (${qty}) vượt quá tồn khả dụng (${item.availableQuantity})`
          );
        }
        const price = Number(item.unitPrice || item.price);
        if (!Number.isFinite(price) || price < 0) {
          errors.push(`Dòng ${lineNum}: Đơn giá không hợp lệ để tính giá trị tổn thất`);
        }
      });
    }

    const totalLoss = calculateTotalLoss(data.items || []);
    if (totalLoss <= 0 && (!data.items || data.items.length > 0)) {
      errors.push("Bắt buộc ghi rõ giá trị tổn thất (tổng giá trị tổn thất phải lớn hơn 0)");
    }

    return {
      isValid: errors.length === 0,
      errors,
    };
  }

  /**
   * Kiểm tra điều kiện phê duyệt phiếu điều chỉnh.
   * Ràng buộc:
   * 1. Chỉ Quản lý kho (WAREHOUSE_APPROVE hoặc ROLE_ADMIN) được duyệt.
   * 2. Người lập và người duyệt KHÔNG ĐƯỢC LÀ MỘT.
   */
  function canApproveAdjustment(voucher, currentUser, auth) {
    if (!voucher) return { allowed: false, reason: "Phiếu không tồn tại" };
    if (!currentUser || !currentUser.username) {
      return { allowed: false, reason: "Chưa xác thực người dùng" };
    }

    // Kiểm tra quyền phê duyệt
    const hasApprovePermission =
      auth &&
      (auth.hasRole("ROLE_ADMIN") || auth.hasPermission("WAREHOUSE_APPROVE"));

    if (!hasApprovePermission) {
      return {
        allowed: false,
        reason: "Chỉ Quản lý kho có quyền WAREHOUSE_APPROVE mới được phê duyệt phiếu điều chỉnh",
      };
    }

    // Kiểm tra trạng thái phiếu
    const status = (voucher.status || "").toUpperCase();
    if (status !== "PENDING_APPROVAL" && status !== "DRAFT") {
      return {
        allowed: false,
        reason: `Phiếu ở trạng thái ${STATUSES[status]?.label || status}, không thể phê duyệt`,
      };
    }

    // Ràng buộc kiểm soát nội bộ: Người lập và người duyệt không được là một
    const creator = voucher.createdBy ? voucher.createdBy.trim().toLowerCase() : "";
    const currentUsername = currentUser.username.trim().toLowerCase();

    if (creator && creator === currentUsername) {
      return {
        allowed: false,
        isCreator: true,
        reason: "Người lập và người duyệt không được là một. Cần Quản lý kho khác hoặc cấp thẩm quyền độc lập phê duyệt.",
      };
    }

    return {
      allowed: true,
      reason: "Đủ điều kiện phê duyệt",
    };
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
   * Chuẩn hóa lý do từ trường adjustmentReason hoặc note.
   */
  function parseAdjustmentReason(rawReason) {
    if (!rawReason) return { reasonCode: "OTHER", reasonLabel: "Lý do khác", detail: "" };
    const text = String(rawReason).trim();
    for (const [code, info] of Object.entries(REASONS)) {
      if (text.startsWith(`[${code}]`)) {
        return {
          reasonCode: code,
          reasonLabel: info.label,
          detail: text.replace(`[${code}]`, "").trim(),
        };
      }
    }
    // Tìm kiếm từ khóa nếu không có tiền tố tag
    const lower = text.toLowerCase();
    if (lower.includes("hỏng") || lower.includes("vỡ") || lower.includes("móp")) {
      return { reasonCode: "DAMAGED", reasonLabel: REASONS.DAMAGED.label, detail: text };
    }
    if (lower.includes("mất") || lower.includes("thất thoát") || lower.includes("hao hụt")) {
      return { reasonCode: "LOST", reasonLabel: REASONS.LOST.label, detail: text };
    }
    if (lower.includes("hết hạn") || lower.includes("quá hạn") || lower.includes("hủy")) {
      return { reasonCode: "EXPIRED", reasonLabel: REASONS.EXPIRED.label, detail: text };
    }
    return { reasonCode: "OTHER", reasonLabel: "Lý do khác", detail: text };
  }

  return {
    REASONS,
    STATUSES,
    formatMoney,
    formatQuantity,
    calculateLineLoss,
    calculateTotalLoss,
    validateAdjustmentDraft,
    canApproveAdjustment,
    getStockCardUrl,
    parseAdjustmentReason,
  };
});

