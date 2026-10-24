/**
 * warehouse-units.js — Quản lý Đơn vị tính quy đổi & Nhập xuất kho (S2-07).
 *
 * Nghiệp vụ cốt lõi:
 * 1. Mỗi SKU khai báo được nhiều đơn vị: lon, lốc, thùng kèm hệ số quy đổi về đơn vị cơ sở.
 * 2. Đơn hàng và phiếu kho nhập theo đơn vị nào cũng quy về đơn vị cơ sở khi ghi sổ.
 * 3. Đổi hệ số quy đổi không làm sai lệch các giao dịch đã ghi trước đó (Cơ chế Snapshot).
 */

(async () => {
  if (!Auth.requireLogin()) return;
  await Auth.ready;

  const user = Auth.getUser() || {};
  let canManageUnits = Auth.hasPermission("WAREHOUSE_MANAGE");
  let canRecordVouchers = Auth.hasAnyPermission("WAREHOUSE_IMPORT", "WAREHOUSE_EXPORT");
  let canViewOrders = Auth.hasPermission("ORDER_VIEW");
  const canUseImports = () => Auth.hasAnyPermission("WAREHOUSE_IMPORT", "WAREHOUSE_APPROVE", "WAREHOUSE_ADJUST");
  const canReadLedger = () => Auth.hasPermission("WAREHOUSE_VIEW") || canUseImports();
  const canReadStocktake = () => WarehouseStocktake.canUserReadStocktake(Auth.getUser());
  const $ = (id) => document.getElementById(id);
  const escapeHtml = (v) => String(v ?? "").replace(/[&<>'"]/g, (c) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", "'": "&#39;", '"': "&quot;",
  })[c]);
  const formatNumber = (num) => new Intl.NumberFormat("vi-VN").format(Number(num) || 0);
  const statusLabel = (status) => ({
    RECORDED: "Đã ghi sổ", PENDING_APPROVAL: "Chờ duyệt",
    DRAFT: "Bản nháp", CONFIRMED: "Đã xác nhận", COMPLETED: "Đã chốt", ADJUSTED: "Đã điều chỉnh",
    CANCELLED: "Đã hủy", REJECTED: "Đã từ chối",
  })[status] || status || "—";

  // ══════════════════════════════════════════════════
  // Data from the API
  // ══════════════════════════════════════════════════
  const Store = {
    products: [],
    receipts: [],
    suppliers: [],
    drafts: [],
    warehouses: [],
    loaded: false,
    ordersLoaded: false,
    warehouseId: null,
    epoch: 0,

    async request(path, options) {
      const response = await Auth.apiFetch(path, options);
      const result = await response.json().catch(() => ({}));
      if (!response.ok || !result.success) {
        throw new Error(result.message || "Không thể tải dữ liệu từ máy chủ.");
      }
      return result.data;
    },

    async init() {
      const epoch = ++this.epoch;
      const includeOrders = canViewOrders && document.querySelector('.nav-tab--active')?.dataset.tab === 'ledger-audit';
      this.loaded = false;
      const importOptions = canUseImports() ? await this.request("/warehouse/import/options") : null;
      const warehouses = importOptions?.warehouses || await this.request("/warehouse/options");
      if (epoch !== this.epoch) return false;
      this.warehouses = warehouses || [];
      const currentWarehouse = $("active-warehouse-select")?.value;
      const preferredWarehouse = warehouses.find(warehouse => String(warehouse.id) === currentWarehouse)
        || warehouses.find(warehouse => user.warehouses?.some(scope => scope.id === warehouse.id))
        || warehouses[0];
      this.warehouseId = preferredWarehouse?.id || null;
      for (const id of ["active-warehouse-select", "receipt-warehouse"]) {
        const select = $(id);
        if (select) {
          select.innerHTML = warehouses.length ? warehouses.map(warehouse =>
            `<option value="${warehouse.id}">${escapeHtml(warehouse.name)}</option>`).join("")
            : '<option value="">Chưa cấu hình kho</option>';
          select.value = this.warehouseId ? String(this.warehouseId) : "";
          select.disabled = !this.warehouseId;
        }
      }
      $("btn-export-inventory-excel").disabled = !this.warehouseId;
      const [products, inventory, vouchers, orders, suppliersData] = await Promise.all([
        this.request("/products/lookup"),
        this.warehouseId && Auth.hasPermission("WAREHOUSE_VIEW") ? this.request(`/warehouse/inventory?warehouseId=${this.warehouseId}`) : [],
        this.warehouseId && Auth.hasPermission("WAREHOUSE_VIEW") ? this.request(`/warehouse/vouchers?warehouseId=${this.warehouseId}`)
          : this.warehouseId && canUseImports() ? this.request(`/warehouse/imports?warehouseId=${this.warehouseId}`) : [],
        // Sales history is only needed by the ledger tab. A broken/unavailable orders
        // API must not prevent loading units, stock or receipt forms, even for ADMIN.
        includeOrders ? this.request("/orders") : [],
        importOptions?.suppliers || [],
      ]);
      if (epoch !== this.epoch) return false;

      const rawSuppliers = Array.isArray(suppliersData)
        ? suppliersData
        : (suppliersData?.content || []);
      this.suppliers = rawSuppliers;

      const conversions = await Promise.all((products || []).map((product) =>
        this.request(`/products/${product.id}/unit-conversions`)));
      if (epoch !== this.epoch) return false;

      this.products = (products || []).map((product, index) => {
        const stock = (inventory || []).find((item) => item.productId === product.id);
        return {
          id: product.id,
          sku: product.code,
          name: product.name,
          category: product.categoryName || "Chưa phân nhóm",
          baseUnit: product.unit,
          price: product.price,
          costPrice: product.costPrice,
          manageLot: Boolean(product.manageLot),
          inventoryBaseQty: stock?.baseQuantity || 0,
          units: (conversions[index] || []).map((unit) => ({
            id: unit.id,
            name: unit.unitName,
            isBase: Boolean(unit.isBaseUnit),
            conversionRate: Number(unit.conversionFactor),
            barcode: unit.barcode || "",
            note: unit.description || "",
          })),
        };
      });

      const voucherRows = (vouchers || []).map((voucher) => this.mapVoucher(voucher));
      const orderRows = (orders || []).map((order) => ({
        id: order.orderCode,
        backendId: order.id,
        type: "ORDER",
        date: (order.recordedAt || order.createdAt || "").slice(0, 10),
        createdAt: order.recordedAt || order.createdAt,
        warehouse: "Đơn hàng bán",
        partner: order.customerName || "—",
        createdBy: order.createdBy,
        notes: order.note || "",
        status: order.status,
        items: (order.items || []).map((item) => ({
          sku: item.productCode,
          productName: item.productName,
          inputUnit: item.unit,
          inputQuantity: Number(item.quantity),
          conversionRateSnapshot: Number(item.conversionFactor),
          baseUnit: item.baseUnit,
          baseQuantity: Number(item.baseQuantity),
          notes: item.note || "",
        })),
      }));
      this.receipts = [...voucherRows, ...orderRows].sort((a, b) =>
        new Date(b.createdAt || 0) - new Date(a.createdAt || 0));

      for (const [id, field, label] of [
        ["sku-category-filter", "category", "Tất cả nhóm hàng"],
        ["sku-baseunit-filter", "baseUnit", "Tất cả đơn vị cơ sở"],
      ]) {
        const select = $(id);
        if (select) {
          const current = select.value;
          const values = [...new Set(this.products.map(product => product[field]).filter(Boolean))].sort();
          select.innerHTML = `<option value="">${label}</option>` + values.map(value =>
            `<option value="${escapeHtml(value)}">${escapeHtml(value)}</option>`).join("");
          if (values.includes(current)) select.value = current;
        }
      }
      this.loaded = true;
      this.ordersLoaded = includeOrders;
      return true;
    },

    mapVoucher(voucher) {
      return {
        id: voucher.voucherCode,
        backendId: voucher.id,
        version: voucher.version,
        type: voucher.type,
        date: (voucher.voucherDate || voucher.recordedAt || "").slice(0, 10),
        createdAt: voucher.recordedAt,
        warehouseId: voucher.warehouseId,
        warehouse: voucher.warehouseName || voucher.warehouseCode || "—",
        supplierId: voucher.supplierId,
        supplierName: voucher.supplierName,
        docNumber: voucher.documentNumber,
        referenceVoucherCode: voucher.referenceVoucherCode,
        adjustmentReason: voucher.adjustmentReason,
        partner: voucher.supplierName || "—",
        createdBy: voucher.createdBy,
        notes: voucher.note || "",
        status: voucher.status,
        items: (voucher.items || []).map((item) => ({
          sku: item.productCode,
          productId: item.productId,
          productName: item.productName,
          inputUnit: item.unit,
          inputQuantity: Number(item.quantity),
          conversionRateSnapshot: Number(item.conversionFactor),
          baseUnit: item.baseUnit,
          baseQuantity: Number(item.baseQuantity),
          notes: item.note || "",
          batchNumber: item.lotNumber || "",
          expiryDate: item.expiryDate || "",
          manufacturingDate: item.manufacturingDate || "",
        })),
      };
    },

    async reloadFromServer() {
      await this.init();
    },

    getProductBySku(sku) {
      return this.products.find((p) => p.sku === sku);
    },

    getUnit(sku, unitName) {
      const prod = this.getProductBySku(sku);
      if (!prod) return null;
      return prod.units.find((u) => u.name.toLowerCase() === unitName.toLowerCase());
    },

    /**
     * Sửa hoặc thêm đơn vị tính quy đổi cho SKU.
     * Quy tắc S2-07: Đổi hệ số KHÔNG làm đổi các receipts đã lưu!
     */
    async saveUnit(sku, { name, conversionRate, barcode, note }) {
      const prod = this.getProductBySku(sku);
      if (!prod) throw new Error("Không tìm thấy SKU: " + sku);

      const cleanName = name.trim().toLowerCase();
      const rate = Number(conversionRate);
      if (!rate || rate <= 0) throw new Error("Hệ số quy đổi phải lớn hơn 0.");

      const unit = prod.units.find((u) => u.name.toLowerCase() === cleanName);
      const isNew = !unit;
      const oldRate = unit ? unit.conversionRate : rate;
      if (unit?.isBase) throw new Error("Không thể sửa đơn vị cơ sở tại màn hình quy đổi.");
      const payload = {
        unitName: cleanName,
        conversionFactor: rate,
        barcode: barcode || "",
        description: note || `1 ${cleanName} = ${rate} ${prod.baseUnit}`,
      };
      await this.request(
        unit ? `/products/${prod.id}/unit-conversions/${unit.id}` : `/products/${prod.id}/unit-conversions`,
        { method: unit ? "PUT" : "POST", body: JSON.stringify(payload) },
      );
      await this.init();
      return { product: this.getProductBySku(sku), unit, oldRate, newRate: rate, isNew };
    },

    /**
     * Xóa đơn vị tính (chỉ cho phép nếu không phải đơn vị cơ sở).
     */
    async deleteUnit(sku, unitName) {
      const prod = this.getProductBySku(sku);
      if (!prod) return;
      const unit = prod.units.find((u) => u.name.toLowerCase() === unitName.toLowerCase());
      if (!unit) return;
      if (unit.isBase) throw new Error("Không thể xóa đơn vị cơ sở lưu kho.");

      await this.request(`/products/${prod.id}/unit-conversions/${unit.id}`, { method: "DELETE" });
      await this.init();
    },

    /**
     * Lưu bản nháp phiếu kho (không ảnh hưởng tồn kho, cho phép chỉnh sửa lại).
     */
    async saveDraftReceipt(receiptData) {
      const epoch = this.epoch;
      const permission = { IMPORT: "WAREHOUSE_IMPORT", EXPORT: "WAREHOUSE_EXPORT", ORDER: "ORDER_CREATE" }[receiptData.type];
      if (!permission || !Auth.hasPermission(permission)) throw new Error("Bạn không có quyền lưu nháp loại phiếu này.");
      if (!receiptData.items || !receiptData.items.length) {
        throw new Error("Vui lòng thêm ít nhất một mặt hàng.");
      }
      if (receiptData.type !== "IMPORT") throw new Error("Chức năng lưu nháp chỉ áp dụng cho phiếu nhập kho.");
      if (!receiptData.warehouseId || !receiptData.supplierId || !receiptData.docNumber || !receiptData.date) {
        throw new Error("Vui lòng chọn kho, nhà cung cấp, ngày nhập và nhập số chứng từ.");
      }
      const payload = {
        voucherCode: receiptData.id || null,
        version: receiptState.version,
        warehouseId: receiptData.warehouseId,
        supplierId: receiptData.supplierId,
        documentNumber: receiptData.docNumber,
        voucherDate: `${receiptData.date}T00:00:00`,
        note: receiptData.notes,
        draft: true,
        status: "DRAFT",
        items: receiptData.items.map(item => {
          const product = this.getProductBySku(item.sku);
          if (!product) throw new Error("Mặt hàng không còn trong danh mục. Hãy tải lại.");
          const quantity = Number(item.inputQuantity);
          if (!Number.isFinite(quantity) || quantity <= 0) throw new Error(`Số lượng ${product.name} phải lớn hơn 0.`);
          if (product.manageLot && (!item.batchNumber?.trim() || !item.expiryDate)) throw new Error(`Mặt hàng ${product.name} phải có số lô và hạn sử dụng.`);
          return { productId: product.id, unit: item.inputUnit, quantity,
            lotNumber: item.batchNumber?.trim() || null, expiryDate: item.expiryDate || null,
            manufacturingDate: item.manufacturingDate || null, note: item.notes || "" };
        }),
      };
      const saved = await this.request(receiptState.backendId ? `/warehouse/vouchers/${receiptState.backendId}` : "/warehouse/import/draft", {
        method: receiptState.backendId ? "PUT" : "POST", body: JSON.stringify(payload),
      });
      if (epoch !== this.epoch || !Auth.hasPermission("WAREHOUSE_IMPORT")) throw new Error("Quyền hoặc phạm vi đã thay đổi. Hãy tải lại để kiểm tra phiếu đã lưu.");
      const draft = this.mapVoucher(saved);
      receiptState.backendId = saved.id;
      receiptState.version = saved.version;
      $("receipt-code").value = saved.voucherCode;
      this.receipts = [draft, ...this.receipts.filter(r => r.backendId !== draft.backendId || r.type === "ORDER")];
      return draft;
    },

    /**
     * Lưu và ghi sổ phiếu kho / đơn hàng.
     * Quy tắc S2-07 / S5-04:
     * - Chốt snapshot tỷ lệ quy đổi tại thời điểm ghi sổ!
     * - Ghi nhận số lô và hạn sử dụng.
     * - Phiếu đã xác nhận cộng tồn kho thực tế và không thể sửa lại!
     */
    async recordReceipt(receiptData) {
      if (receiptData.type === "IMPORT") {
        if (!Auth.hasPermission("WAREHOUSE_APPROVE")) throw new Error("Bạn cần quyền xác nhận nhập/xuất kho để cộng tồn. Có thể lưu nháp trước.");
        const draft = await this.saveDraftReceipt(receiptData);
        return this.confirmImport(draft);
      }
      if (!receiptData.items || !receiptData.items.length) {
        throw new Error("Vui lòng thêm ít nhất một mặt hàng.");
      }

      const requestItems = [];
      for (const item of receiptData.items) {
        const prod = this.getProductBySku(item.sku);
        if (!prod) throw new Error(`SKU ${item.sku} không tồn tại.`);

        const unit = prod.units.find((u) => u.name.toLowerCase() === item.inputUnit.toLowerCase());
        if (!unit) throw new Error(`Đơn vị ${item.inputUnit} không hợp lệ với SKU ${item.sku}.`);

        const inputQty = Number(item.inputQuantity);
        if (!inputQty || inputQty <= 0) throw new Error(`Số lượng mặt hàng ${prod.name} phải lớn hơn 0.`);

        // Ghi nhận số lô và hạn sử dụng vào note của item để lưu trữ an toàn tương thích backend
        const itemNoteParts = [];
        if (item.batchNumber) itemNoteParts.push(`Lô: ${item.batchNumber}`);
        if (item.expiryDate) itemNoteParts.push(`HSD: ${item.expiryDate}`);
        if (item.notes) itemNoteParts.push(item.notes);

        requestItems.push({
          productId: prod.id,
          productCode: prod.sku,
          unit: unit.name,
          quantity: inputQty,
          note: itemNoteParts.join(" | "),
        });
      }
      const code = receiptData.id || this.generateReceiptCode(receiptData.type);
      if (receiptData.type === "ORDER") {
        await this.request("/orders", { method: "POST", body: JSON.stringify({
          orderCode: code, customerName: receiptData.partner || "Khách hàng",
          note: receiptData.notes || "", items: requestItems,
        }) });
      } else {
        const fullNoteParts = [];
        if (receiptData.docNumber) fullNoteParts.push(`Số C/Từ: ${receiptData.docNumber}`);
        if (receiptData.supplierName || receiptData.partner) fullNoteParts.push(`NCC: ${receiptData.supplierName || receiptData.partner}`);
        if (receiptData.notes) fullNoteParts.push(receiptData.notes);

        await this.request(`/warehouse/${receiptData.type === "EXPORT" ? "export" : "import"}`, {
          method: "POST",
          body: JSON.stringify({
            voucherCode: code,
            warehouseId: receiptData.warehouseId || user.warehouses?.[0]?.id || null,
            note: fullNoteParts.join(" · "),
            items: requestItems,
          }),
        });

        // Xóa khỏi danh sách drafts nếu phiếu này từng được lưu nháp
        this.drafts = this.drafts.filter((d) => d.id !== code);
      }
      await this.init();
      return this.receipts.find((receipt) => receipt.id === code) || { id: code };
    },

    generateReceiptCode(type) {
      if (type === "IMPORT") return ""; // The server allocates the unique code on first save.
      const prefix = type === "IMPORT" ? "PNK" : type === "EXPORT" ? "PXK" : "DH";
      const ym = new Date().toISOString().slice(0, 7).replace("-", "");
      const count = this.receipts.filter((r) => r.type === type).length + this.drafts.filter((r) => r.type === type).length + 1;
      return `${prefix}-${ym}-${String(count).padStart(3, "0")}`;
    },

    async confirmImport(draft) {
      const epoch = this.epoch;
      if (!Auth.hasPermission("WAREHOUSE_APPROVE")) throw new Error("Bạn không có quyền xác nhận phiếu nhập kho.");
      const result = await this.request(`/warehouse/vouchers/${draft.backendId}/confirm`, { method: "POST" });
      if (epoch !== this.epoch || !Auth.hasPermission("WAREHOUSE_APPROVE")) throw new Error("Quyền hoặc phạm vi đã thay đổi. Hãy tải lại để kiểm tra trạng thái phiếu.");
      const recorded = this.mapVoucher(result);
      this.receipts = [recorded, ...this.receipts.filter(r => r.backendId !== recorded.backendId || r.type === "ORDER")];
      return recorded;
    },

    /**
     * Phân rã số lượng cơ sở (lon) thành các đơn vị đóng gói lớn (thùng, lốc, lon lẻ)
     * Rất hữu ích cho thủ kho kiểm đếm thực tế!
     */
    breakdownInventory(sku, baseQty) {
      const prod = this.getProductBySku(sku);
      if (!prod || !prod.units) return `${formatNumber(baseQty)} (cơ sở)`;

      // Sắp xếp các đơn vị có hệ số từ cao xuống thấp (không tính đơn vị cơ sở)
      const sortedUnits = [...prod.units]
        .filter((u) => !u.isBase && u.conversionRate > 1)
        .sort((a, b) => b.conversionRate - a.conversionRate);

      let rem = Number(baseQty) || 0;
      const parts = [];

      for (const u of sortedUnits) {
        if (rem >= u.conversionRate) {
          const count = Math.floor(rem / u.conversionRate);
          parts.push(`${formatNumber(count)} ${u.name}`);
          rem %= u.conversionRate;
        }
      }

      if (rem > 0 || !parts.length) {
        parts.push(`${formatNumber(rem)} ${prod.baseUnit} lẻ`);
      }

      return parts.join(" + ");
    },
  };

  const stocktake = WarehouseStocktake.createClient({
    request: (path, options) => Store.request(path, options),
    getUser: () => Auth.getUser(),
    onChange: () => renderStocktakePanel(),
  });
  let legacyLoad = null;
  function stocktakeIsActive() { return document.querySelector(".nav-tab--active")?.dataset.tab === "stocktake"; }
  function ensureLegacyData() {
    const needsOrders = canViewOrders && document.querySelector('.nav-tab--active')?.dataset.tab === 'ledger-audit';
    if (Store.loaded && (!needsOrders || Store.ordersLoaded)) return Promise.resolve(true);
    if (legacyLoad) return legacyLoad.then(applied => applied ? ensureLegacyData() : false);
    const loading = Store.init().then(applied => {
      if (!applied) return false;
      renderSkuTable(); updateMetrics(); prepareReceiptForm(); renderLedgerTable(); renderInventoryTable();
      if (stocktakeIsActive()) renderStocktakePanel();
      return true;
    }).catch(error => { showToast(error.message || "Không tải được dữ liệu kho.", "error"); return false; })
      .finally(() => { if (legacyLoad === loading) legacyLoad = null; });
    legacyLoad = loading;
    return loading;
  }

  // ══════════════════════════════════════════════════
  // 2. UI SHELL & NAVIGATION SETUP
  // ══════════════════════════════════════════════════
  const sidebar = $("sidebar");
  const initialTabParam = new URLSearchParams(window.location.search).get("tab");
  const overlay = document.createElement("div");
  overlay.className = "sidebar-overlay";
  document.body.appendChild(overlay);

  function openSidebar(open) {
    sidebar.classList.toggle("sidebar--open", open);
    overlay.classList.toggle("sidebar-overlay--visible", open);
  }

  $("sidebar-toggle")?.addEventListener("click", () => openSidebar(true));
  $("sidebar-close")?.addEventListener("click", () => openSidebar(false));
  overlay.addEventListener("click", () => openSidebar(false));

  // User Header info
  $("user-name").textContent = user.fullName || user.username;
  const avatarFallback = $("user-avatar-fallback");
  const avatarImage = $("user-avatar-image");
  avatarFallback.textContent = (user.fullName || user.username || "K").trim().charAt(0).toUpperCase();

  if (user.avatarThumbnailUrl) {
    Auth.setProtectedImage(avatarImage, user.avatarThumbnailUrl).then((ok) => {
      if (ok) {
        avatarImage.hidden = false;
        avatarFallback.hidden = true;
      }
    });
  }
  $("btn-avatar")?.addEventListener("click", () => {
    window.location.href = "/profile";
  });

  const roleNames = (user.roles || []).map(MenuConfig.getRoleDisplayName);
  const scope = [...(user.warehouses || []), ...(user.territories || [])].map((item) => item.name);
  $("user-role").textContent = [roleNames.join(", "), scope.join(", ")].filter(Boolean).join(" · ") || "—";

  document.querySelectorAll("#btn-logout, #btn-logout-topbar").forEach((btn) => btn.addEventListener("click", Auth.logout));

  // ══════════════════════════════════════════════════
  // 3. TOAST NOTIFICATIONS
  // ══════════════════════════════════════════════════
  function showToast(message, type = "success") {
    let container = document.getElementById("toast-container");
    if (!container) {
      container = document.createElement("div");
      container.id = "toast-container";
      document.body.appendChild(container);
    }
    const toast = document.createElement("div");
    toast.className = `toast toast--${type}`;
    toast.innerHTML = `<span class="toast__message">${escapeHtml(message)}</span>`;
    container.appendChild(toast);
    requestAnimationFrame(() => toast.classList.add("toast--visible"));
    setTimeout(() => {
      toast.classList.remove("toast--visible");
      toast.remove();
    }, 4000);
  }

  // ══════════════════════════════════════════════════
  // 4. TAB NAVIGATION
  // ══════════════════════════════════════════════════
  const tabs = document.querySelectorAll(".nav-tab");
  const panels = document.querySelectorAll(".tab-panel");

  function switchTab(tabId) {
    const allowed = tabId === "sku-units" ? Auth.hasAnyPermission("WAREHOUSE_VIEW", "WAREHOUSE_MANAGE")
      : tabId === "create-receipt" ? canRecordVouchers
      : tabId === "stocktake" ? canReadStocktake()
      : tabId === "ledger-audit" ? canReadLedger() : Auth.hasPermission("WAREHOUSE_VIEW");
    if (!allowed) return;
    document.title = tabId === "stocktake" ? "Kiểm kê kho & Chênh lệch | SaleStock" : "Đơn vị tính & Nhập / Xuất kho | SaleStock";
    document.querySelector(".units-main > h1").textContent = tabId === "stocktake" ? "Kiểm kê kho & Chênh lệch" : "Kho hàng";
    if (tabId === "inventory-report") {
      window.location.href = "/warehouse/inventory";
      return;
    }
    tabs.forEach((tab) => {
      const active = tab.dataset.tab === tabId;
      tab.classList.toggle("nav-tab--active", active);
      tab.setAttribute("aria-selected", active ? "true" : "false");
    });
    panels.forEach((panel) => {
      const active = panel.id === `panel-${tabId}`;
      panel.hidden = !active;
      panel.classList.toggle("tab-panel--active", active);
    });

    if (tabId === "sku-units") renderSkuTable();
    if (tabId === "create-receipt" && !receiptState.backendId) prepareReceiptForm();
    if (tabId === "ledger-audit") renderLedgerTable();
    if (tabId === "inventory-report") renderInventoryTable();
    document.querySelector(".units-metrics-grid").hidden = tabId === "stocktake";
    if (tabId === "stocktake") {
      renderStocktakePanel();
      if (!stocktake.state.ready && !stocktake.state.pending) loadStocktakes();
    } else {
      if (Store.loaded) renderWarehouseSelector(Store.warehouses, Store.warehouseId, !Store.warehouseId);
      ensureLegacyData();
    }
    const tabParam = {
      "sku-units": "units",
      "create-receipt": "receipts",
      "ledger-audit": "ledger",
      "inventory-report": "inventory",
      "stocktake": "stocktake",
    }[tabId];
    if (tabParam) {
      const currentUrl = new URL(window.location.href);
      currentUrl.searchParams.set("tab", tabParam);
      window.history.replaceState(null, "", currentUrl);
    }
    AppNavigation.refresh();
    updateMetrics();
  }

  tabs.forEach((tab) => {
    tab.addEventListener("click", () => switchTab(tab.dataset.tab));
  });

  $("tab-create-receipt").hidden = !canRecordVouchers;
  $("btn-open-create-unit-modal").hidden = !canManageUnits;

  // ══════════════════════════════════════════════════
  // 5. UPDATE METRICS STRIP
  // ══════════════════════════════════════════════════
  function updateMetrics() {
    if (!Store.loaded) return;
    $("metric-sku-count").textContent = Store.products.length;
    const totalUnits = Store.products.reduce((acc, p) => acc + p.units.filter((unit) => !unit.isBase).length, 0);
    $("metric-unit-count").textContent = `${totalUnits} đơn vị`;
    $("metric-ledger-count").textContent = `${Store.receipts.length} chứng từ`;
  }

  // ══════════════════════════════════════════════════
  // 6. PANEL 1: SKU UNITS MANAGEMENT (TAB 1)
  // ══════════════════════════════════════════════════
  function renderSkuTable() {
    const search = $("sku-search-input").value.trim().toLowerCase();
    const categoryFilter = $("sku-category-filter").value;
    const baseFilter = $("sku-baseunit-filter").value;

    const filtered = Store.products.filter((p) => {
      const matchSearch = !search
        || p.name.toLowerCase().includes(search)
        || p.sku.toLowerCase().includes(search)
        || p.units.some((u) => u.name.toLowerCase().includes(search));
      const matchCat = !categoryFilter || p.category === categoryFilter;
      const matchBase = !baseFilter || p.baseUnit === baseFilter;
      return matchSearch && matchCat && matchBase;
    });

    const tbody = $("sku-table-body");
    if (!filtered.length) {
      tbody.innerHTML = `<tr><td colspan="5" class="empty">Không tìm thấy sản phẩm nào phù hợp điều kiện lọc.</td></tr>`;
      return;
    }

    tbody.innerHTML = filtered.map((p) => {
      const baseUnitObj = p.units.find((u) => u.isBase) || { name: p.baseUnit, conversionRate: 1 };
      const convertedUnits = p.units.filter((u) => !u.isBase);

      const unitChipsHtml = [
        `<span class="unit-chip unit-chip--base" title="Đơn vị cơ sở ghi sổ kho">
          <strong>${escapeHtml(baseUnitObj.name)}</strong>
          <span class="unit-chip__rate">×1 (Cơ sở)</span>
        </span>`,
        ...convertedUnits.map((u) => `
          <span class="unit-chip unit-chip--converted" title="1 ${escapeHtml(u.name)} = ${u.conversionRate} ${escapeHtml(p.baseUnit)}">
            ${escapeHtml(u.name)}
            <span class="unit-chip__rate">×${u.conversionRate}</span>
          </span>
        `)
      ].join("");

      const breakdown = Store.breakdownInventory(p.sku, p.inventoryBaseQty);

      return `
        <tr>
          <td>
            <div class="sku-identity">
              <span class="sku-name">${escapeHtml(p.name)}</span>
              <span class="sku-code">${escapeHtml(p.sku)}</span>
              <span class="sku-category-tag">${escapeHtml(p.category)}</span>
            </div>
          </td>
          <td>
            <span class="badge badge--base">
              ${escapeHtml(p.baseUnit)} (Hệ số: 1)
            </span>
          </td>
          <td>
            <div class="unit-tag-cluster">
              ${unitChipsHtml}
            </div>
          </td>
          <td>
            ${Store.warehouseId ? `
            <div class="inventory-dual-pill">
              <span class="inventory-dual-pill__base">${formatNumber(p.inventoryBaseQty)} ${escapeHtml(p.baseUnit)}</span>
              <span class="inventory-dual-pill__packages">≈ ${escapeHtml(breakdown)}</span>
            </div>
            ` : '<span>Chưa cấu hình kho</span>'}
          </td>
          <td class="th-actions">
            <div class="table-row-actions">
              <button class="action-btn action-btn--primary" data-action="add-unit" data-sku="${p.sku}" title="Thêm đơn vị quy đổi mới">
                + Đơn vị
              </button>
              <button class="action-btn" data-action="edit-unit" data-sku="${p.sku}" title="Sửa hệ số quy đổi">
                Sửa hệ số
              </button>
            </div>
          </td>
        </tr>
      `;
    }).join("");
    tbody.querySelectorAll("[data-action]").forEach(button => { button.hidden = !canManageUnits; });
  }

  $("sku-search-input")?.addEventListener("input", renderSkuTable);
  $("sku-category-filter")?.addEventListener("change", renderSkuTable);
  $("sku-baseunit-filter")?.addEventListener("change", renderSkuTable);

  $("btn-reload-data")?.addEventListener("click", async () => {
    const button = $("btn-reload-data");
    button.disabled = true;
    try {
      await Store.reloadFromServer();
      renderSkuTable();
      prepareReceiptForm();
      renderLedgerTable();
      renderInventoryTable();
      updateMetrics();
      showToast("Đã tải lại dữ liệu từ máy chủ.");
    } catch (error) {
      showToast(error.message || "Không tải được dữ liệu kho.", "error");
    } finally {
      button.disabled = false;
    }
  });

  // Event delegation for table action buttons
  $("sku-table-body")?.addEventListener("click", (e) => {
    const btn = e.target.closest("[data-action]");
    if (!btn) return;
    const action = btn.dataset.action;
    const sku = btn.dataset.sku;

    if (action === "add-unit") openUnitModal(sku, null);
    if (action === "edit-unit") {
      const prod = Store.getProductBySku(sku);
      const convertedUnits = prod?.units.filter((u) => !u.isBase) || [];
      if (!convertedUnits.length) {
        openUnitModal(sku, null);
      } else {
        openUnitModal(sku, convertedUnits[0]);
      }
    }
  });

  // ══════════════════════════════════════════════════
  // 7. PANEL 2: RECEIPT & ORDER BUILDER (TAB 2)
  // ══════════════════════════════════════════════════
  const receiptState = {
    type: "IMPORT",
    backendId: null,
    version: null,
    busy: false,
    lines: []
  };

  function createDefaultReceiptLine(id = "line-1") {
    const product = Store.products[0];
    if (!product) return null;
    const unit = product.units.find((item) => !item.isBase) || product.units[0];
    if (!unit) return null;
    return { id, sku: product.sku, inputUnit: unit.name, inputQuantity: 1, notes: "" };
  }

  function updateReceiptAvailability() {
    const needsWarehouse = receiptState.type !== "ORDER";
    const permission = { IMPORT: "WAREHOUSE_IMPORT", EXPORT: "WAREHOUSE_EXPORT", ORDER: "ORDER_CREATE" }[receiptState.type];
    const canSave = Auth.hasPermission(permission) && Store.loaded && Store.products.length && (!needsWarehouse || Store.warehouseId);
    $("btn-save-receipt").disabled = !canSave || receiptState.busy || (receiptState.type === "IMPORT" && !Auth.hasPermission("WAREHOUSE_APPROVE"));
    $("btn-save-receipt").hidden = receiptState.type === "IMPORT" && !Auth.hasPermission("WAREHOUSE_APPROVE");
    if ($("btn-save-draft-receipt")) {
      $("btn-save-draft-receipt").disabled = !canSave || receiptState.busy;
      $("btn-save-draft-receipt").hidden = receiptState.type !== "IMPORT" || !Auth.hasPermission("WAREHOUSE_IMPORT");
    }
    $("warehouse-configuration-message").hidden = !Store.loaded || !needsWarehouse || Boolean(Store.warehouseId);
    $("receipt-warehouse").closest(".form-field").hidden = !needsWarehouse;
    document.querySelectorAll('#panel-create-receipt input, #panel-create-receipt select, #btn-reset-receipt, #btn-add-line-item, .btn-remove-line').forEach(element => {
      if (element.type !== "radio") element.disabled = receiptState.busy || !Auth.hasPermission(permission);
    });
  }

  function populateSupplierSelect() {
    const select = $("receipt-supplier-select");
    if (!select) return;
    const currentVal = select.value;
    select.innerHTML = '<option value="">-- Chọn Nhà cung cấp --</option>' + (Store.suppliers || []).map((s) => `
      <option value="${s.id}" data-name="${escapeHtml(s.name)}">${escapeHtml(s.code ? s.code + ' - ' + s.name : s.name)}</option>
    `).join("");
    if (currentVal) select.value = currentVal;
  }

  function prepareReceiptForm(existingData = null) {
    receiptState.backendId = existingData?.backendId || null;
    receiptState.version = existingData?.version ?? null;
    updateReceiptAvailability();
    populateSupplierSelect();
    $("receipt-date").value = existingData?.date || new Date().toISOString().slice(0, 10);
    $("receipt-code").value = existingData?.id || Store.generateReceiptCode(receiptState.type);
    $("receipt-code").placeholder = "Tự sinh khi lưu phiếu";
    $("btn-regen-code").hidden = receiptState.type === "IMPORT";
    $("receipt-partner").hidden = receiptState.type === "IMPORT";
    if (existingData) {
      if (existingData.supplierId && $("receipt-supplier-select")) {
        $("receipt-supplier-select").value = String(existingData.supplierId);
      }
      if ($("receipt-doc-number")) {
        $("receipt-doc-number").value = existingData.docNumber || "";
      }
      $("receipt-partner").value = existingData.partner || existingData.supplierName || "";
      $("receipt-notes").value = existingData.notes || "";
      if (existingData.warehouseId) $("receipt-warehouse").value = String(existingData.warehouseId);
      receiptState.lines = existingData.items?.length ? existingData.items.map((it, idx) => ({
        id: "line-" + (idx + 1),
        sku: it.sku,
        inputUnit: it.inputUnit,
        inputQuantity: it.inputQuantity,
        batchNumber: it.batchNumber || "",
        expiryDate: it.expiryDate || "",
        manufacturingDate: it.manufacturingDate || "",
        notes: it.notes || ""
      })) : [];
    } else {
      if (!receiptState.lines.length) {
        const defaultLine = createDefaultReceiptLine();
        receiptState.lines = defaultLine ? [defaultLine] : [];
      }
    }
    renderLineItems();
  }

  $("btn-regen-code")?.addEventListener("click", () => {
    $("receipt-code").value = Store.generateReceiptCode(receiptState.type);
  });

  // Supplier select change -> sync partner input
  $("receipt-supplier-select")?.addEventListener("change", (e) => {
    const selectedOption = e.target.selectedOptions[0];
    if (selectedOption && selectedOption.dataset.name) {
      $("receipt-partner").value = selectedOption.dataset.name;
    }
  });

  // Receipt type switcher (Radio pills)
  document.querySelectorAll("#receipt-type-radios input[type='radio']").forEach((radio) => {
    radio.addEventListener("change", (e) => {
      if (receiptState.busy) return;
      receiptState.backendId = null;
      receiptState.version = null;
      receiptState.type = e.target.value;
      document.querySelectorAll(".receipt-type-pill").forEach((pill) => {
        pill.classList.toggle("receipt-type-pill--active", pill.querySelector("input").checked);
      });
      $("receipt-code").value = Store.generateReceiptCode(receiptState.type);
      $("btn-regen-code").hidden = receiptState.type === "IMPORT";
      $("receipt-partner").hidden = receiptState.type === "IMPORT";

      const supplierWrap = $("receipt-supplier-select")?.closest(".form-field");
      const partnerLabel = $("label-receipt-partner") || $("label-receipt-supplier");
      const partnerInput = $("receipt-partner");
      const docNumberField = $("receipt-doc-number")?.closest(".form-field");

      if (receiptState.type === "IMPORT") {
        if (partnerLabel) partnerLabel.textContent = "Nhà cung cấp";
        if (supplierWrap) supplierWrap.hidden = false;
        if (docNumberField) docNumberField.hidden = false;
        partnerInput.placeholder = "Hoặc nhập tên NCC / đối tác...";
      } else if (receiptState.type === "EXPORT") {
        if (partnerLabel) partnerLabel.textContent = "Kho đích / Chi nhánh nhận";
        if (supplierWrap) supplierWrap.hidden = true;
        if (docNumberField) docNumberField.hidden = false;
        partnerInput.placeholder = "VD: Kho Trung Chuyển Cầu Giấy (HN)...";
      } else {
        if (partnerLabel) partnerLabel.textContent = "Khách hàng / Đại lý phân phối";
        if (supplierWrap) supplierWrap.hidden = true;
        if (docNumberField) docNumberField.hidden = true;
        partnerInput.placeholder = "VD: Đại lý Phương Nam (Hà Nội)...";
      }
      updateReceiptAvailability();
    });
  });

  function renderLineItems() {
    const tbody = $("line-items-body");
    if (!Store.products.length) {
      tbody.innerHTML = '<tr><td colspan="8" class="empty">Chưa có sản phẩm thật trong cơ sở dữ liệu.</td></tr>';
      updateReceiptSummary();
      return;
    }
    tbody.innerHTML = receiptState.lines.map((line, index) => {
      const prod = Store.getProductBySku(line.sku) || Store.products[0];
      const validUnits = prod?.units || [];
      const currentUnit = validUnits.find((u) => u.name.toLowerCase() === (line.inputUnit || "").toLowerCase()) || validUnits[0];
      const rate = currentUnit?.conversionRate || 1;
      const baseQty = (Number(line.inputQuantity) || 0) * rate;

      const skuOptions = Store.products.map((p) => `
        <option value="${escapeHtml(p.sku)}" ${p.sku === prod.sku ? "selected" : ""}>
          ${escapeHtml(p.name)} (${p.sku}) [Cơ sở: ${escapeHtml(p.baseUnit)}]
        </option>
      `).join("");

      const unitOptions = validUnits.map((u) => `
        <option value="${escapeHtml(u.name)}" ${u.name.toLowerCase() === currentUnit.name.toLowerCase() ? "selected" : ""}>
          ${escapeHtml(u.name)} (×${u.conversionRate} ${escapeHtml(prod.baseUnit)})
        </option>
      `).join("");

      return `
        <tr data-line-id="${line.id}">
          <td>
            <select class="line-sku-select" data-index="${index}">
              ${skuOptions}
            </select>
          </td>
          <td>
            <select class="line-unit-select" data-index="${index}">
              ${unitOptions}
            </select>
          </td>
          <td>
            <input type="number" class="line-qty-input" data-index="${index}" min="0.0001" step="0.0001" value="${escapeHtml(line.inputQuantity)}" />
          </td>
          <td>
            <input type="text" class="line-batch-input" data-index="${index}" maxlength="50" ${prod.manageLot ? 'required' : ''} placeholder="${prod.manageLot ? 'Số lô (bắt buộc)' : 'Số lô (nếu có)'}" value="${escapeHtml(line.batchNumber || '')}" />
          </td>
          <td>
            <label>Ngày sản xuất<input type="date" class="line-manufacturing-input" data-index="${index}" value="${escapeHtml(line.manufacturingDate || '')}" /></label>
            <label>Hạn sử dụng<input type="date" class="line-expiry-input" data-index="${index}" ${prod.manageLot ? 'required' : ''} value="${escapeHtml(line.expiryDate || '')}" /></label>
          </td>
          <td>
            <div class="conversion-rate-cell">
              1 ${escapeHtml(currentUnit.name)} = ${rate} ${escapeHtml(prod.baseUnit)}
            </div>
          </td>
          <td>
            <div class="base-qty-cell">
              <span class="base-qty-badge">
                ${formatNumber(baseQty)} ${escapeHtml(prod.baseUnit)}
              </span>
              <span class="base-qty-formula">
                ${line.inputQuantity || 0} ${escapeHtml(currentUnit.name)} × ${rate}
              </span>
            </div>
          </td>
          <td style="text-align: center">
            <button type="button" class="btn-remove-line" data-index="${index}" title="Xóa dòng này">
              <svg viewBox="0 0 24 24" width="16" height="16"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
            </button>
          </td>
        </tr>
      `;
    }).join("");

    updateReceiptSummary();
  }

  function updateReceiptSummary() {
    let totalBaseQty = 0;
    const packageParts = [];

    receiptState.lines.forEach((line) => {
      const prod = Store.getProductBySku(line.sku) || Store.products[0];
      const unit = prod?.units.find((u) => u.name.toLowerCase() === (line.inputUnit || "").toLowerCase()) || prod?.units[0];
      if (!prod || !unit) return;
      const rate = unit?.conversionRate || 1;
      const baseQty = (Number(line.inputQuantity) || 0) * rate;
      totalBaseQty += baseQty;
      if (line.inputQuantity > 0) {
        packageParts.push(`${line.inputQuantity} ${unit.name}`);
      }
    });

    $("summary-line-count").textContent = `${receiptState.lines.length} mặt hàng`;
    $("summary-packages-count").textContent = packageParts.join(", ") || "0";
    $("summary-base-qty-total").textContent = `${formatNumber(totalBaseQty)} (Tổng lon/cơ sở)`;
  }

  // Handle changes in line items table
  $("line-items-body")?.addEventListener("change", (e) => {
    const target = e.target;
    const index = Number(target.dataset.index);
    if (isNaN(index) || !receiptState.lines[index]) return;

    if (target.classList.contains("line-sku-select")) {
      const newSku = target.value;
      const prod = Store.getProductBySku(newSku);
      receiptState.lines[index].sku = newSku;
      receiptState.lines[index].batchNumber = "";
      receiptState.lines[index].expiryDate = "";
      receiptState.lines[index].manufacturingDate = "";
      // Default to the first bulk unit or base unit
      const bulkUnit = prod.units.find((u) => !u.isBase) || prod.units[0];
      receiptState.lines[index].inputUnit = bulkUnit.name;
      renderLineItems();
    } else if (target.classList.contains("line-unit-select")) {
      receiptState.lines[index].inputUnit = target.value;
      renderLineItems();
    } else if (target.classList.contains("line-qty-input")) {
      receiptState.lines[index].inputQuantity = target.value;
      // Keep the row/input nodes: replacing them on blur loses focus and the next field's input.
      updateReceiptSummary();
    } else if (target.classList.contains("line-batch-input")) {
      receiptState.lines[index].batchNumber = target.value.trim();
    } else if (target.classList.contains("line-expiry-input")) {
      receiptState.lines[index].expiryDate = target.value;
    } else if (target.classList.contains("line-manufacturing-input")) {
      receiptState.lines[index].manufacturingDate = target.value;
    }
  });

  $("line-items-body")?.addEventListener("input", (e) => {
    const target = e.target;
    const index = Number(target.dataset.index);
    if (isNaN(index) || !receiptState.lines[index]) return;

    if (target.classList.contains("line-qty-input")) {
      receiptState.lines[index].inputQuantity = target.value;
      // Live update cell without re-rendering entire table
      const row = target.closest("tr");
      const prod = Store.getProductBySku(receiptState.lines[index].sku);
      const unit = prod.units.find((u) => u.name.toLowerCase() === (receiptState.lines[index].inputUnit || "").toLowerCase()) || prod.units[0];
      const rate = unit?.conversionRate || 1;
      const baseQty = (Number(target.value) || 0) * rate;
      row.querySelector(".base-qty-badge").textContent = `${formatNumber(baseQty)} ${prod.baseUnit}`;
      row.querySelector(".base-qty-formula").textContent = `${target.value || 0} ${unit.name} × ${rate}`;
      updateReceiptSummary();
    } else if (target.classList.contains("line-batch-input")) {
      receiptState.lines[index].batchNumber = target.value.trim();
    } else if (target.classList.contains("line-expiry-input")) {
      receiptState.lines[index].expiryDate = target.value;
    } else if (target.classList.contains("line-manufacturing-input")) {
      receiptState.lines[index].manufacturingDate = target.value;
    }
  });

  $("line-items-body")?.addEventListener("click", (e) => {
    const btn = e.target.closest(".btn-remove-line");
    if (!btn) return;
    const index = Number(btn.dataset.index);
    if (receiptState.lines.length <= 1) {
      showToast("Phiếu phải có ít nhất một mặt hàng.", "warning");
      return;
    }
    receiptState.lines.splice(index, 1);
    renderLineItems();
  });

  $("btn-add-line-item")?.addEventListener("click", () => {
    if (!Store.products.length) {
      showToast("Chưa có sản phẩm để thêm vào phiếu.", "warning");
      return;
    }
    const randomSku = Store.products[receiptState.lines.length % Store.products.length];
    const defaultUnit = randomSku.units.find((u) => !u.isBase) || randomSku.units[0];
    receiptState.lines.push({
      id: "line-" + Date.now(),
      sku: randomSku.sku,
      inputUnit: defaultUnit.name,
      inputQuantity: 10,
      batchNumber: "",
      expiryDate: "",
      notes: ""
    });
    renderLineItems();
  });

  $("btn-reset-receipt")?.addEventListener("click", () => {
    if (confirm("Làm mới toàn bộ thông tin phiếu?")) {
      const defaultLine = createDefaultReceiptLine();
      receiptState.lines = defaultLine ? [defaultLine] : [];
      if ($("receipt-supplier-select")) $("receipt-supplier-select").value = "";
      if ($("receipt-doc-number")) $("receipt-doc-number").value = "";
      $("receipt-partner").value = "";
      $("receipt-notes").value = "";
      prepareReceiptForm();
    }
  });

  // Action: Save Draft (Lưu nháp - Không ảnh hưởng tồn kho, cho phép chỉnh sửa)
  $("btn-save-draft-receipt")?.addEventListener("click", async () => {
    if (receiptState.busy) return;
    receiptState.busy = true;
    updateReceiptAvailability();
    try {
      const code = $("receipt-code").value.trim() || Store.generateReceiptCode(receiptState.type);
      const date = $("receipt-date").value;
      const warehouseId = Number($("receipt-warehouse").value) || null;
      const warehouse = $("receipt-warehouse").selectedOptions[0]?.textContent || "";
      const supplierSelect = $("receipt-supplier-select");
      const supplierId = supplierSelect?.value ? Number(supplierSelect.value) : null;
      const supplierName = supplierSelect?.selectedOptions[0]?.dataset?.name || "";
      const partner = $("receipt-partner").value.trim();
      const docNumber = $("receipt-doc-number") ? $("receipt-doc-number").value.trim() : "";
      const notes = $("receipt-notes").value.trim();

      const draft = await Store.saveDraftReceipt({
        id: code,
        type: receiptState.type,
        date: date,
        warehouse: warehouse,
        warehouseId: warehouseId,
        supplierId: supplierId,
        supplierName: supplierName,
        docNumber: docNumber,
        partner: partner,
        notes: notes,
        items: receiptState.lines
      });

      showToast(`Đã lưu bản nháp phiếu ${draft.id}. Tồn kho thực tế chưa bị thay đổi.`, "success");
      renderLedgerTable();
      switchTab("ledger-audit");
    } catch (err) {
      showToast(err.message, "error");
    } finally {
      receiptState.busy = false;
      updateReceiptAvailability();
    }
  });

  // Submit Receipt and Record into Stock Ledger with Frozen Snapshot
  $("btn-save-receipt")?.addEventListener("click", async () => {
    if (receiptState.busy) return;
    receiptState.busy = true;
    updateReceiptAvailability();
    try {
      const permission = { IMPORT: "WAREHOUSE_IMPORT", EXPORT: "WAREHOUSE_EXPORT", ORDER: "ORDER_CREATE" }[receiptState.type];
      if (!permission || !Auth.hasPermission(permission)) throw new Error("Bạn không có quyền ghi loại phiếu này.");
      const code = $("receipt-code").value.trim() || Store.generateReceiptCode(receiptState.type);
      const date = $("receipt-date").value;
      const warehouseId = Number($("receipt-warehouse").value) || null;
      if (receiptState.type !== "ORDER" && !warehouseId) {
        throw new Error("Chưa có kho đang hoạt động. Vui lòng cấu hình kho trước khi ghi phiếu.");
      }
      const warehouse = $("receipt-warehouse").selectedOptions[0]?.textContent || "";
      const supplierSelect = $("receipt-supplier-select");
      const supplierId = supplierSelect?.value ? Number(supplierSelect.value) : null;
      const supplierName = supplierSelect?.selectedOptions[0]?.dataset?.name || "";
      const partner = $("receipt-partner").value.trim();
      const docNumber = $("receipt-doc-number") ? $("receipt-doc-number").value.trim() : "";
      const notes = $("receipt-notes").value.trim();

      const receiptRecord = await Store.recordReceipt({
        id: code,
        type: receiptState.type,
        date: date,
        warehouse: warehouse,
        warehouseId: warehouseId,
        supplierId: supplierId,
        supplierName: supplierName,
        docNumber: docNumber,
        partner: partner,
        notes: notes,
        items: receiptState.lines
      });

      showToast(`Đã ghi sổ phiếu ${receiptRecord.id}. Tồn kho đã được cập nhật thành công!`, "success");

      // Reset form lines
      const defaultLine = createDefaultReceiptLine();
      receiptState.lines = defaultLine ? [defaultLine] : [];
      if ($("receipt-supplier-select")) $("receipt-supplier-select").value = "";
      if ($("receipt-doc-number")) $("receipt-doc-number").value = "";
      $("receipt-partner").value = "";
      $("receipt-notes").value = "";
      prepareReceiptForm();

      // Navigate to Ledger Audit Tab to show the recorded transaction
      switchTab("ledger-audit");
    } catch (err) {
      showToast(err.message, "error");
    } finally {
      receiptState.busy = false;
      updateReceiptAvailability();
    }
  });

  // ══════════════════════════════════════════════════
  // 8. PANEL 3: LEDGER AUDIT & SNAPSHOT INTEGRITY (TAB 3)
  // ══════════════════════════════════════════════════
  function renderLedgerTable() {
    const search = $("ledger-search-input").value.trim().toLowerCase();
    const typeFilter = $("ledger-type-filter").value;
    const skuFilter = $("ledger-sku-filter").value;

    const tbody = $("ledger-table-body");
    const allRecords = [...Store.drafts, ...Store.receipts];
    const filtered = allRecords.filter((r) => {
      const matchType = !typeFilter || r.type === typeFilter;
      const matchSearch = !search
        || r.id.toLowerCase().includes(search)
        || r.partner?.toLowerCase().includes(search)
        || r.items.some((i) => i.productName.toLowerCase().includes(search) || i.sku.toLowerCase().includes(search) || i.inputUnit.toLowerCase().includes(search));
      const matchSku = !skuFilter || r.items.some((i) => i.sku === skuFilter);
      return matchType && matchSearch && matchSku;
    });

    if (!filtered.length) {
      tbody.innerHTML = `<tr><td colspan="9" class="empty">Không có giao dịch nào phù hợp điều kiện tra cứu.</td></tr>`;
      return;
    }

    // Populate SKU filter dropdown
    const skuOptionsHtml = `<option value="">Tất cả SKU</option>` + Store.products.map((p) => `
      <option value="${escapeHtml(p.sku)}" ${p.sku === skuFilter ? "selected" : ""}>${escapeHtml(p.sku)} - ${escapeHtml(p.name)}</option>
    `).join("");
    $("ledger-sku-filter").innerHTML = skuOptionsHtml;

    tbody.innerHTML = filtered.map((r) => {
      const typeBadge = r.type === "IMPORT"
        ? `<span class="badge badge--import"> Nhập kho</span>`
        : r.type === "EXPORT"
        ? `<span class="badge badge--export"> Xuất kho</span>`
        : r.type === "ADJUSTMENT" ? `<span class="badge badge--draft">Điều chỉnh</span>` : `<span class="badge badge--order"> Đơn hàng</span>`;

      const statusBadgeClass = r.status === "DRAFT" ? "badge--draft" : "badge--pill";

      return r.items.map((item, idx) => `
        <tr>
          ${idx === 0 ? `
            <td rowspan="${r.items.length}">
              <span class="user-name" style="font-family: var(--font-mono); font-weight:700">${escapeHtml(r.id)}</span>
              <span class="user-meta">${escapeHtml(r.createdAt || r.date)}</span>
              <span class="user-meta" style="color:var(--ink-700)">${escapeHtml(r.partner || "")}</span>
            </td>
            <td rowspan="${r.items.length}">${typeBadge}</td>
          ` : ""}
          <td>
            <span class="sku-name">${escapeHtml(item.productName)}</span>
            <span class="sku-code">${escapeHtml(item.sku)}</span>
            ${item.batchNumber ? `<div style="font-size:0.75rem; color:var(--ink-500)">Lô: <strong>${escapeHtml(item.batchNumber)}</strong></div>` : ""}
            ${item.expiryDate ? `<div style="font-size:0.75rem; color:var(--ink-500)">HSD: ${escapeHtml(item.expiryDate)}</div>` : ""}
          </td>
          <td>
            <strong style="font-size:0.9375rem; color:var(--navy-900)">
              ${formatNumber(item.inputQuantity)} ${escapeHtml(item.inputUnit)}
            </strong>
          </td>
          <td>
            <span class="snapshot-rate-badge" title="Hệ số quy đổi đã chốt tại thời điểm lập phiếu - Không bao giờ đổi">
              <svg viewBox="0 0 24 24" width="12" height="12"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/></svg>
              × ${formatNumber(item.conversionRateSnapshot)} (${escapeHtml(item.baseUnit)}/${escapeHtml(item.inputUnit)})
            </span>
          </td>
          <td>
            <span class="base-recorded-badge">
              ${formatNumber(item.baseQuantity)} ${escapeHtml(item.baseUnit)}
            </span>
          </td>
          ${idx === 0 ? `
            <td rowspan="${r.items.length}">
              <span style="font-size:0.8125rem">${escapeHtml(r.warehouse)}</span>
              <span class="user-meta">${escapeHtml(r.createdBy)}</span>
            </td>
            <td rowspan="${r.items.length}">
              <span class="badge ${statusBadgeClass}">${escapeHtml(statusLabel(r.status))}</span>
            </td>
            <td rowspan="${r.items.length}" class="th-actions">
              ${r.status === "DRAFT" && r.type === "IMPORT" && Auth.hasPermission("WAREHOUSE_IMPORT") ? `
                <button class="action-btn action-btn--primary" data-edit-draft="${escapeHtml(r.id)}" title="Tiếp tục chỉnh sửa bản nháp">
                  Sửa nháp
                </button>
              ` : ""}
                <button class="action-btn" data-view-receipt="${escapeHtml(r.id)}" title="Xem chi tiết phiếu">
                  Chi tiết
                </button>
            </td>
          ` : ""}
        </tr>
      `).join("");
    }).join("");
  }

  $("ledger-search-input")?.addEventListener("input", renderLedgerTable);
  $("ledger-type-filter")?.addEventListener("change", renderLedgerTable);
  $("ledger-sku-filter")?.addEventListener("change", renderLedgerTable);

  $("ledger-table-body")?.addEventListener("click", (e) => {
    const viewBtn = e.target.closest("[data-view-receipt]");
    if (viewBtn) {
      openReceiptDetailModal(viewBtn.dataset.viewReceipt);
      return;
    }
    const editDraftBtn = e.target.closest("[data-edit-draft]");
    if (editDraftBtn) {
      const draftId = editDraftBtn.dataset.editDraft;
      const draft = Store.receipts.find((d) => d.id === draftId && d.status === "DRAFT" && d.type === "IMPORT");
      if (draft) {
        receiptState.type = draft.type;
        document.querySelectorAll("#receipt-type-radios input[type='radio']").forEach((radio) => {
          radio.checked = radio.value === draft.type;
        });
        document.querySelectorAll(".receipt-type-pill").forEach((pill) => {
          pill.classList.toggle("receipt-type-pill--active", pill.querySelector("input").checked);
        });
        switchTab("create-receipt");
        prepareReceiptForm(draft);
      }
    }
  });

  // ══════════════════════════════════════════════════
  // Inventory report
  // ══════════════════════════════════════════════════
  function renderInventoryTable() {
    const search = $("inv-search-input").value.trim().toLowerCase();
    const tbody = $("inventory-table-body");
    if (Store.loaded && !Store.warehouseId) {
      tbody.innerHTML = '<tr><td colspan="6" class="empty">Chưa cấu hình kho đang hoạt động để xem tồn kho.</td></tr>';
      return;
    }

    const filtered = Store.products.filter((p) => !search || p.name.toLowerCase().includes(search) || p.sku.toLowerCase().includes(search));

    if (!filtered.length) {
      tbody.innerHTML = `<tr><td colspan="6" class="empty">Không tìm thấy sản phẩm.</td></tr>`;
      return;
    }

    tbody.innerHTML = filtered.map((p) => {
      const breakdown = Store.breakdownInventory(p.sku, p.inventoryBaseQty);
      return `
        <tr>
          <td>
            <span class="sku-name">${escapeHtml(p.name)}</span>
            <span class="sku-code">${escapeHtml(p.sku)}</span>
          </td>
          <td>
            <span class="badge badge--base">${escapeHtml(p.baseUnit)}</span>
          </td>
          <td>
            <strong style="font-size:1.0625rem; color:var(--navy-900); font-variant-numeric: tabular-nums">
              ${formatNumber(p.inventoryBaseQty)} ${escapeHtml(p.baseUnit)}
            </strong>
          </td>
          <td>
            <div class="inventory-breakdown">
              <span class="breakdown-formula">
                ${escapeHtml(breakdown)}
              </span>
              <span class="breakdown-hint">
                Quy đổi tự động dựa trên quy cách đóng gói khai báo
              </span>
            </div>
          </td>
          <td>
            <span>Chưa thiết lập</span>
          </td>
          <td>
            <button class="action-btn action-btn--primary" data-action="quick-import" data-sku="${p.sku}">
              + Nhập hàng
            </button>
          </td>
        </tr>
      `;
    }).join("");
    tbody.querySelectorAll("[data-action=quick-import]").forEach(button => { button.hidden = !Auth.hasPermission("WAREHOUSE_IMPORT"); });
  }

  $("inv-search-input")?.addEventListener("input", renderInventoryTable);

  $("inventory-table-body")?.addEventListener("click", (e) => {
    const btn = e.target.closest("[data-action='quick-import']");
    if (!btn) return;
    const sku = btn.dataset.sku;
    receiptState.type = "IMPORT";
    const prod = Store.getProductBySku(sku);
    const bulkUnit = prod?.units.find((u) => !u.isBase) || prod?.units[0];
    receiptState.lines = [
      { id: "line-1", sku: sku, inputUnit: bulkUnit.name, inputQuantity: 10, notes: "" }
    ];
    switchTab("create-receipt");
  });

  // Excel Export feature using XLSX
  $("btn-export-inventory-excel")?.addEventListener("click", () => {
    if (!Store.warehouseId) {
      showToast("Chưa cấu hình kho để xuất bảng tồn kho.", "warning");
      return;
    }
    if (typeof XLSX === "undefined") {
      showToast("Thư viện Excel đang tải, vui lòng thử lại.", "warning");
      return;
    }
    const data = Store.products.map((p) => ({
      "Mã SKU": p.sku,
      "Tên sản phẩm": p.name,
      "Ngành hàng": p.category,
      "Đơn vị cơ sở": p.baseUnit,
      "Tồn kho cơ sở": p.inventoryBaseQty,
      "Quy đổi thực tế kho": Store.breakdownInventory(p.sku, p.inventoryBaseQty)
    }));
    const worksheet = XLSX.utils.json_to_sheet(data);
    const workbook = XLSX.utils.book_new();
    XLSX.utils.book_append_sheet(workbook, worksheet, "TonKhoQuyDoi");
    XLSX.writeFile(workbook, "Ton_Kho_Quy_Doi_SaleStock.xlsx");
    showToast("Đã xuất file Excel tồn kho quy đổi thành công!");
  });

  $("btn-export-ledger-excel")?.addEventListener("click", () => {
    if (typeof XLSX === "undefined") {
      showToast("Thư viện Excel đang tải, vui lòng thử lại.", "warning");
      return;
    }
    const rows = [];
    Store.receipts.forEach((r) => {
      r.items.forEach((item) => {
        rows.push({
          "Số chứng từ": r.id,
          "Loại phiếu": r.type,
          "Ngày ghi sổ": r.createdAt || r.date,
          "Kho hàng": r.warehouse,
          "Đối tác": r.partner,
          "Mã SKU": item.sku,
          "Tên sản phẩm": item.productName,
          "Đơn vị gọi hàng": item.inputUnit,
          "Số lượng gọi hàng": item.inputQuantity,
          "Hệ số ghi sổ": item.conversionRateSnapshot,
          "Đơn vị cơ sở": item.baseUnit,
          "Số lượng cơ sở ghi sổ": item.baseQuantity,
          "Người lập": r.createdBy
        });
      });
    });
    const worksheet = XLSX.utils.json_to_sheet(rows);
    const workbook = XLSX.utils.book_new();
    XLSX.utils.book_append_sheet(workbook, worksheet, "SoCaiKho");
    XLSX.writeFile(workbook, "so-cai-kho.xlsx");
    showToast("Đã xuất sổ cái kho.");
  });

  // ══════════════════════════════════════════════════
  // 11. MODAL 1: ADD/EDIT UNIT CONVERSION FOR SKU
  // ══════════════════════════════════════════════════
  const modalUnit = $("modal-unit-edit");
  const formUnit = $("form-unit-edit");

  function openUnitModal(defaultSku = null, existingUnit = null) {
    formUnit.reset();
    $("unit-form-error").hidden = true;

    // Populate SKU select
    const skuSelect = $("unit-sku-select");
    skuSelect.innerHTML = Store.products.map((p) => `
      <option value="${p.sku}" ${p.sku === defaultSku ? "selected" : ""}>
        ${p.sku} - ${p.name} (Đơn vị cơ sở: ${p.baseUnit})
      </option>
    `).join("");

    if (existingUnit) {
      $("modal-unit-title").textContent = `Chỉnh sửa hệ số: ${existingUnit.name}`;
      $("unit-edit-mode").value = "edit";
      $("unit-name-input").value = existingUnit.name;
      $("unit-name-input").readOnly = existingUnit.isBase;
      $("unit-rate-input").value = existingUnit.conversionRate;
      $("unit-barcode-input").value = existingUnit.barcode || "";
      $("unit-notes-input").value = existingUnit.note || "";
      $("unit-edit-snapshot-warning").hidden = false;
    } else {
      $("modal-unit-title").textContent = "Thêm đơn vị quy đổi mới";
      $("unit-edit-mode").value = "create";
      $("unit-name-input").value = "";
      $("unit-name-input").readOnly = false;
      $("unit-rate-input").value = 24;
      $("unit-barcode-input").value = "";
      $("unit-notes-input").value = "";
      $("unit-edit-snapshot-warning").hidden = true;
    }

    updateFormulaPreview();
    modalUnit.hidden = false;
  }

  function closeUnitModal() {
    modalUnit.hidden = true;
  }

  document.querySelectorAll("[data-close-unit-modal]").forEach((btn) => {
    btn.addEventListener("click", closeUnitModal);
  });
  $("btn-open-create-unit-modal")?.addEventListener("click", () => openUnitModal());

  function updateFormulaPreview() {
    const sku = $("unit-sku-select").value;
    const prod = Store.getProductBySku(sku);
    const unitName = $("unit-name-input").value.trim() || "đơn vị";
    const rate = $("unit-rate-input").value || "1";

    $("preview-unit-name").textContent = unitName;
    $("preview-rate").textContent = rate;
    $("preview-base-unit").textContent = prod ? prod.baseUnit : "đơn vị cơ sở";
  }

  $("unit-sku-select")?.addEventListener("change", updateFormulaPreview);
  $("unit-name-input")?.addEventListener("input", updateFormulaPreview);
  $("unit-rate-input")?.addEventListener("input", updateFormulaPreview);

  document.querySelectorAll(".quick-unit-suggestions .btn-chip").forEach((chip) => {
    chip.addEventListener("click", () => {
      $("unit-name-input").value = chip.dataset.val;
      if (chip.dataset.val === "thùng") $("unit-rate-input").value = 24;
      else if (chip.dataset.val === "lốc") $("unit-rate-input").value = 6;
      else if (chip.dataset.val === "thùng 30") $("unit-rate-input").value = 30;
      else if (chip.dataset.val === "két") $("unit-rate-input").value = 24;
      updateFormulaPreview();
    });
  });

  formUnit?.addEventListener("submit", async (e) => {
    e.preventDefault();
    const sku = $("unit-sku-select").value;
    const name = $("unit-name-input").value.trim();
    const rate = $("unit-rate-input").value;
    const barcode = $("unit-barcode-input").value.trim();
    const note = $("unit-notes-input").value.trim();

    if (!name) {
      $("unit-form-error").hidden = false;
      $("unit-form-error").textContent = "Vui lòng nhập tên đơn vị tính.";
      return;
    }

    try {
      await Store.saveUnit(sku, {
        name: name,
        conversionRate: rate,
        barcode: barcode,
        note: note
      });

      closeUnitModal();
      renderSkuTable();
      updateMetrics();
      showToast(`Đã lưu đơn vị "${name}" cho SKU ${sku}. Hệ số áp dụng cho các giao dịch mới!`, "success");
    } catch (err) {
      $("unit-form-error").hidden = false;
      $("unit-form-error").textContent = err.message;
    }
  });

  // ══════════════════════════════════════════════════
  // 12. MODAL 2: RATE HISTORY AUDIT
  // ══════════════════════════════════════════════════
  const modalReceipt = $("modal-receipt-detail");

  function openReceiptDetailModal(receiptId) {
    const r = Store.receipts.find((item) => item.id === receiptId) || Store.drafts.find((item) => item.id === receiptId);
    if (!r) return;

    $("detail-receipt-code").textContent = r.id;
    const typeLabel = r.type === "IMPORT" ? "Phiếu nhập kho" : r.type === "EXPORT" ? "Phiếu xuất kho" : r.type === "ADJUSTMENT" ? "Phiếu điều chỉnh" : "Đơn hàng bán";
    $("modal-receipt-title").textContent = `Chi tiết ${typeLabel}`;

    const itemsHtml = r.items.map((item, idx) => `
      <tr>
        <td>${idx + 1}</td>
        <td>
          <span class="sku-name">${escapeHtml(item.productName)}</span>
          <span class="sku-code">${escapeHtml(item.sku)}</span>
          ${item.batchNumber ? `<div style="font-size:0.75rem; color:var(--ink-500)">Lô: <strong>${escapeHtml(item.batchNumber)}</strong></div>` : ""}
          ${item.expiryDate ? `<div style="font-size:0.75rem; color:var(--ink-500)">HSD: ${escapeHtml(item.expiryDate)}</div>` : ""}
        </td>
        <td><strong>${formatNumber(item.inputQuantity)} ${escapeHtml(item.inputUnit)}</strong></td>
        <td>
          <span class="snapshot-rate-badge">
            × ${formatNumber(item.conversionRateSnapshot)} (${escapeHtml(item.baseUnit)}/${escapeHtml(item.inputUnit)})
          </span>
        </td>
        <td>
          <strong class="text-emerald">${formatNumber(item.baseQuantity)} ${escapeHtml(item.baseUnit)}</strong>
        </td>
        <td style="font-size:0.75rem; color:var(--ink-500)">${escapeHtml(item.notes || "")}</td>
      </tr>
    `).join("");

    const isRecorded = ["RECORDED", "CONFIRMED", "ADJUSTED"].includes(r.status);

    $("receipt-detail-content").innerHTML = `
      <div class="receipt-detail-grid">
        <div class="meta-item">
          <span>Ngày lập:</span>
          <strong>${escapeHtml(r.date)}</strong>
        </div>
        <div class="meta-item">
          <span>Kho thực hiện:</span>
          <strong>${escapeHtml(r.warehouse)}</strong>
        </div>
        <div class="meta-item">
          <span>Người lập phiếu:</span>
          <strong>${escapeHtml(r.createdBy)}</strong>
        </div>
        <div class="meta-item">
          <span>Đối tác / Nguồn hàng:</span>
          <strong>${escapeHtml(r.partner || "—")}</strong>
        </div>
        <div class="meta-item">
          <span>Số chứng từ:</span>
          <strong>${escapeHtml(r.docNumber || "—")}</strong>
        </div>
        <div class="meta-item">
          <span>Trạng thái:</span>
          <strong>${escapeHtml(statusLabel(r.status))}</strong>
        </div>
        <div class="meta-item">
          <span>Ghi chú:</span>
          <strong>${escapeHtml(r.notes || "Không")}</strong>
        </div>
      </div>

      ${r.referenceVoucherCode ? `<p>Điều chỉnh cho: ${escapeHtml(r.referenceVoucherCode)} · ${escapeHtml(r.adjustmentReason)}</p>` : ""}
      ${r.type === "IMPORT" && r.status === "DRAFT" ? `
        <p>Phiếu nháp chưa cộng tồn. Hệ số quy đổi sẽ được chốt lại tại thời điểm xác nhận.</p>
        ${Auth.hasPermission("WAREHOUSE_APPROVE") ? `<button class="btn btn--primary" data-confirm-import="${escapeHtml(r.id)}">Xác nhận nhập kho</button>` : ""}
        ${Auth.hasPermission("WAREHOUSE_IMPORT") ? `<button class="btn btn--secondary" data-delete-import="${escapeHtml(r.id)}">Xóa bản nháp</button>` : ""}
      ` : ""}

      ${isRecorded ? `
        <div class="alert-notice alert-notice--info" style="margin: 16px 0; display:flex; align-items:center; gap:10px; padding:10px 14px; background:#f0fdf4; border:1px solid #bbf7d0; border-radius:6px; color:#166534; font-size:0.8125rem">
          <svg viewBox="0 0 24 24" width="16" height="16"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/></svg>
          <span><strong>Chứng từ đã ghi sổ:</strong> Đã cộng tồn kho thực tế. Dữ liệu phiếu đã đóng băng bất biến, không thể sửa đổi (nếu có sai lệch cần lập phiếu điều chỉnh kho).</span>
        </div>
      ` : ""}

      <div class="table-container">
        <table class="data-table">
          <thead>
            <tr>
              <th style="width: 40px">#</th>
              <th>Mặt hàng / SKU</th>
              <th>Đơn vị chứng từ</th>
              <th>Hệ số ghi sổ</th>
              <th>Số lượng cơ sở ghi sổ</th>
              <th>Ghi chú</th>
            </tr>
          </thead>
          <tbody>
            ${itemsHtml}
          </tbody>
        </table>
      </div>

      ${r.type === "IMPORT" && isRecorded && Auth.hasPermission("WAREHOUSE_ADJUST") ? `
        <form id="import-adjustment-form" data-voucher="${escapeHtml(r.id)}" class="receipt-form-meta">
          <div class="form-field form-field--span2"><h3>Lập phiếu điều chỉnh</h3><p>Giữ nguyên phiếu gốc. Nhập lượng tăng (+) hoặc giảm (-) theo đơn vị đã chọn trên dòng gốc.</p></div>
          <div class="form-field"><label for="import-adjustment-line">Mặt hàng / lô</label>
            <select id="import-adjustment-line">${r.items.map((item, index) => `<option value="${index}">${escapeHtml(item.productName)} · ${escapeHtml(item.batchNumber || "Không quản lý lô")} (${escapeHtml(item.inputUnit)})</option>`).join("")}</select></div>
          <div class="form-field"><label for="import-adjustment-delta">Số lượng tăng / giảm</label><input id="import-adjustment-delta" type="number" step="0.0001" required /></div>
          <div class="form-field form-field--span2"><label for="import-adjustment-reason">Lý do điều chỉnh (bắt buộc)</label><textarea id="import-adjustment-reason" maxlength="500" required></textarea></div>
          <button class="btn btn--primary" type="submit">Lập phiếu điều chỉnh</button>
        </form>
      ` : ""}

    `;

    modalReceipt.hidden = false;
  }

  let detailBusy = false;
  $("receipt-detail-content").addEventListener("click", async event => {
    const confirmButton = event.target.closest("[data-confirm-import]");
    const deleteButton = event.target.closest("[data-delete-import]");
    const button = confirmButton || deleteButton;
    if (!button || detailBusy) return;
    const code = confirmButton?.dataset.confirmImport || deleteButton?.dataset.deleteImport;
    const record = Store.receipts.find(row => row.id === code && row.type === "IMPORT" && row.status === "DRAFT");
    if (!record) return;
    const permission = confirmButton ? "WAREHOUSE_APPROVE" : "WAREHOUSE_IMPORT";
    if (!Auth.hasPermission(permission)) { showToast("Quyền thao tác đã bị thu hồi.", "error"); return; }
    if (!window.confirm(confirmButton ? "Xác nhận phiếu nhập và cộng tồn kho?" : "Xóa bản nháp này? Tồn kho không thay đổi.")) return;
    detailBusy = true; button.disabled = true;
    try {
      if (confirmButton) {
        await Store.confirmImport(record);
        if (receiptState.backendId === record.backendId) {
          receiptState.lines = [];
          for (const id of ["receipt-supplier-select", "receipt-doc-number", "receipt-partner", "receipt-notes"]) $(id).value = "";
          prepareReceiptForm();
        }
        openReceiptDetailModal(code);
      }
      else {
        await Store.request(`/warehouse/vouchers/${record.backendId}`, { method: "DELETE" });
        Store.receipts = Store.receipts.filter(row => row !== record);
        if (receiptState.backendId === record.backendId) prepareReceiptForm();
        modalReceipt.hidden = true;
      }
      renderLedgerTable(); updateMetrics();
      showToast(confirmButton ? "Đã xác nhận phiếu nhập và cộng tồn." : "Đã xóa phiếu nháp.");
    } catch (error) { showToast(error.message, "error"); }
    finally { detailBusy = false; button.disabled = false; }
  });

  $("receipt-detail-content").addEventListener("submit", async event => {
    if (event.target.id !== "import-adjustment-form") return;
    event.preventDefault();
    if (detailBusy || !Auth.hasPermission("WAREHOUSE_ADJUST")) return;
    const record = Store.receipts.find(row => row.id === event.target.dataset.voucher && row.type === "IMPORT");
    const line = record?.items[Number($("import-adjustment-line").value)];
    const delta = Number($("import-adjustment-delta").value);
    const reason = $("import-adjustment-reason").value.trim();
    if (!line || !Number.isFinite(delta) || !delta || !reason) { showToast("Nhập lượng tăng/giảm khác 0 và lý do điều chỉnh.", "error"); return; }
    if (!window.confirm("Ghi phiếu điều chỉnh và cập nhật tồn kho?")) return;
    detailBusy = true;
    const button = event.target.querySelector('button[type="submit"]'); button.disabled = true;
    try {
      const data = await Store.request(`/warehouse/vouchers/${record.backendId}/adjust`, { method: "POST", body: JSON.stringify({
        warehouseId: record.warehouseId, adjustmentReason: reason, delta,
        items: [{ productId: line.productId, unit: line.inputUnit, quantity: Math.abs(delta),
          lotNumber: line.batchNumber || null, expiryDate: line.expiryDate || null, manufacturingDate: line.manufacturingDate || null }],
      }) });
      record.status = "ADJUSTED";
      Store.receipts.unshift(Store.mapVoucher(data));
      renderLedgerTable(); updateMetrics(); modalReceipt.hidden = true;
      showToast("Đã lập phiếu điều chỉnh. Phiếu gốc được giữ nguyên.");
    } catch (error) { showToast(error.message, "error"); }
    finally { detailBusy = false; button.disabled = false; }
  });

  document.querySelectorAll("[data-close-receipt-modal]").forEach((btn) => {
    btn.addEventListener("click", () => { modalReceipt.hidden = true; });
  });

  $("btn-print-receipt")?.addEventListener("click", () => {
    window.print();
  });

  // ══════════════════════════════════════════════════
  // 10. PANEL 5: KIỂM KÊ KHO VÀ GHI NHẬN CHÊNH LỆCH (S5-08)
  // ══════════════════════════════════════════════════

  function stocktakeMessage(message = "", type = "error") {
    const element = $("stocktake-message");
    element.textContent = message;
    element.dataset.type = type;
    element.hidden = !message;
  }

  function renderWarehouseSelector(warehouses, selected, disabled) {
    const select = $("active-warehouse-select");
    select.innerHTML = warehouses.length ? warehouses.map(w =>
      `<option value="${escapeHtml(w.id)}">${escapeHtml(w.name)}</option>`).join("")
      : '<option value="">Chưa cấu hình kho</option>';
    select.value = selected == null ? "" : String(selected);
    select.disabled = disabled;
  }

  function renderStocktakePanel() {
    const state = stocktake.state, st = state.current;
    const allowed = stocktake.canRead(), editable = stocktake.canEdit(), completable = stocktake.canComplete();
    $("panel-stocktake").setAttribute("aria-busy", String(state.pending));
    if (stocktakeIsActive()) {
      renderWarehouseSelector(state.options?.warehouses || [], state.warehouseId, state.pending || !allowed || !state.warehouseId);
    }
    const scope = $("stocktake-scope-filter"), previousScope = scope.value;
    scope.innerHTML = '<option value="ALL">Toàn bộ mặt hàng trong kho</option>' +
      (state.options?.categories || []).map(c => `<option value="${escapeHtml(c.id)}">${escapeHtml(c.name)}</option>`).join("");
    if ([...scope.options].some(option => option.value === previousScope)) scope.value = previousScope;
    scope.disabled = state.pending || !allowed || !state.ready;
    $("btn-start-stocktake").hidden = !allowed;
    $("btn-start-stocktake").disabled = state.pending || !state.ready || !state.warehouseId;
    $("btn-reload-stocktake").disabled = state.pending || !allowed;
    $("btn-toggle-stocktake-history").disabled = state.pending || !allowed;
    $("stocktake-active-card").hidden = !allowed || !st;
    $("stocktake-empty-state").hidden = !allowed || Boolean(st);
    $("stocktake-voucher-code").textContent = st?.code || "—";
    $("stocktake-scope-badge").textContent = st ? (st.categoryName || "Toàn bộ kho") : "—";
    $("stocktake-status-badge").textContent = st?.statusLabel || statusLabel(st?.status);
    $("stocktake-warehouse-name").textContent = st?.warehouseName || "—";
    $("stocktake-frozen-date").textContent = st?.createdAt?.replace("T", " ").slice(0, 19) || "—";
    $("stocktake-created-by").textContent = st?.createdBy || "—";
    $("stocktake-adjustment-reason").value = state.reason;
    $("stocktake-adjustment-reason").disabled = state.pending || !completable;
    $("stocktake-note").value = st?.note || "";
    $("stocktake-note").disabled = state.pending || !editable;
    $("stocktake-cancel-reason").value = state.cancelReason;
    $("stocktake-cancel-reason").disabled = state.pending || !editable;
    $("btn-save-draft-stocktake").hidden = !editable;
    $("btn-save-draft-stocktake").disabled = state.pending || !editable;
    $("btn-confirm-stocktake").hidden = !completable;
    $("btn-confirm-stocktake").disabled = state.pending || !completable;
    $("btn-cancel-stocktake").hidden = !editable;
    $("btn-cancel-stocktake").disabled = state.pending || !editable;
    $("stocktake-role-hint").hidden = !st || st.status !== "DRAFT" || completable;
    const tbody = $("stocktake-table-body");
    tbody.innerHTML = (allowed ? st?.items || [] : []).map((item, idx) => {
      const diff = WarehouseStocktake.calculateDifference(item.actualQuantity, item.bookQuantity);
      return `<tr data-idx="${idx}">
        <td>${idx + 1}</td>
        <td><div class="sku-identity"><span class="sku-name">${escapeHtml(item.productName)}</span><span class="sku-code">${escapeHtml(item.productCode)}</span></div></td>
        <td>${escapeHtml(item.categoryName || "—")}</td><td>${escapeHtml(item.baseUnit)}</td>
        <td class="stocktake-number">${formatNumber(item.bookQuantity)}</td>
        <td><input type="number" step="any" min="0" class="form-control stocktake-actual-input" data-idx="${idx}"
          aria-label="Thực tế đếm ${escapeHtml(item.productCode)}" value="${escapeHtml(item.actualQuantity ?? "")}" ${state.pending || !editable ? "disabled" : ""} /></td>
        <td><span class="stocktake-diff-badge stocktake-diff-badge--${diff.status.toLowerCase()}" id="diff-badge-${idx}">${escapeHtml(diff.label)}</span></td>
        <td><input type="text" maxlength="500" class="form-control stocktake-note-input" data-idx="${idx}"
          aria-label="Ghi chú ${escapeHtml(item.productCode)}" placeholder="Ghi chú chênh lệch" value="${escapeHtml(item.note || "")}" ${state.pending || !editable ? "disabled" : ""} /></td>
      </tr>`;
    }).join("");
    tbody.querySelectorAll(".stocktake-actual-input").forEach(input => input.addEventListener("input", event => {
      if (!stocktake.canEdit() || state.pending) return;
      const item = state.current.items[Number(event.target.dataset.idx)];
      item.actualQuantity = event.target.validity.badInput ? NaN : event.target.value === "" ? null : event.target.value;
      state.dirty = true;
      const diff = WarehouseStocktake.calculateDifference(item.actualQuantity, item.bookQuantity);
      const badge = $(`diff-badge-${event.target.dataset.idx}`);
      badge.className = `stocktake-diff-badge stocktake-diff-badge--${diff.status.toLowerCase()}`;
      badge.textContent = diff.label;
      updateStocktakeMetrics();
    }));
    tbody.querySelectorAll(".stocktake-note-input").forEach(input => input.addEventListener("input", event => {
      if (!stocktake.canEdit() || state.pending) return;
      state.current.items[Number(event.target.dataset.idx)].note = event.target.value;
      state.dirty = true;
    }));
    updateStocktakeMetrics();
    renderStocktakeHistory();
  }

  function updateStocktakeMetrics() {
    const metrics = WarehouseStocktake.calculateMetrics(stocktake.state.current?.items);
    for (const key of ["total", "match", "surplus", "deficit", "uncounted"]) {
      $(`st-metric-${key}`).textContent = String(metrics[key]);
    }
  }

  function renderStocktakeHistory() {
    const state = stocktake.state;
    $("stocktake-history-body").innerHTML = state.history.length ? state.history.map(h => `
      <tr>
        <td><strong class="receipt-code-pill">${escapeHtml(h.code)}</strong></td>
        <td>${escapeHtml(h.createdAt?.replace("T", " ").slice(0, 19) || "—")}</td>
        <td>${escapeHtml(h.warehouseName)}</td><td>${escapeHtml(h.categoryName || "Toàn bộ kho")}</td>
        <td>${escapeHtml(h.createdBy)}</td><td>${escapeHtml(h.adjustmentReason || h.cancelReason || h.note || "—")}</td>
        <td>${escapeHtml(h.statusLabel || state.options?.statuses?.find(s => s.code === h.status)?.label || statusLabel(h.status))}</td>
        <td><button type="button" class="btn btn--secondary btn--xs" data-view-stocktake="${escapeHtml(h.id)}" ${state.pending ? "disabled" : ""}>Chi tiết</button></td>
      </tr>`).join("") : '<tr><td colspan="8" class="empty">Chưa có phiếu kiểm kê trong kho này.</td></tr>';
    $("stocktake-history-page").textContent = `Trang ${state.totalPages ? state.page + 1 : 0} / ${state.totalPages || 0} · ${state.totalElements || 0} phiếu`;
    $("stocktake-history-prev").disabled = state.pending || state.page <= 0 || !stocktake.canRead();
    $("stocktake-history-next").disabled = state.pending || state.page + 1 >= state.totalPages || !stocktake.canRead();
  }

  function mayLeaveStocktake() {
    return !stocktake.state.dirty || window.confirm("Có thay đổi chưa lưu. Bỏ các thay đổi này? Phiếu đã lưu vẫn có trong lịch sử.");
  }

  async function performStocktake(action, success, refreshHistory = false) {
    if (stocktake.state.pending || !stocktake.canRead()) return;
    stocktakeMessage();
    try {
      await action();
      if (!stocktake.canRead()) return;
      stocktakeMessage(success, "success");
      if (refreshHistory) {
        // The write succeeded. A history failure must never offer a duplicate write.
        try { await stocktake.history(); }
        catch (error) {
          if (error.code !== "STALE_STOCKTAKE" && stocktake.canRead()) stocktakeMessage(`${success} Tuy nhiên, chưa tải lại được lịch sử: ${error.message}`, "warning");
        }
      }
    } catch (error) {
      if (error.code !== "STALE_STOCKTAKE" && stocktake.canRead()) stocktakeMessage(error.message || "Không thể xử lý phiếu kiểm kê.");
    }
  }

  function loadStocktakes() {
    return performStocktake(() => stocktake.initialize($("active-warehouse-select").value), "Đã tải danh sách kiểm kê.", false);
  }
  $("btn-start-stocktake").addEventListener("click", () => {
    if (!mayLeaveStocktake()) return;
    const categoryId = $("stocktake-scope-filter").value;
    performStocktake(() => stocktake.create(categoryId), "Đã tạo phiếu kiểm kê và chốt tồn sổ từ máy chủ.", true);
  });
  $("btn-save-draft-stocktake").addEventListener("click", () =>
    performStocktake(() => stocktake.save(), "Đã lưu nháp tiến độ kiểm kê.", true));
  $("btn-confirm-stocktake").addEventListener("click", () =>
    performStocktake(async () => { await stocktake.complete(); Store.loaded = false; }, "Đã chốt kiểm kê và điều chỉnh tồn kho.", true));
  $("btn-cancel-stocktake").addEventListener("click", () => {
    if (!window.confirm("Hủy phiếu kiểm kê này? Tồn kho sẽ không thay đổi.")) return;
    performStocktake(() => stocktake.cancel(), "Đã hủy phiếu kiểm kê.", true);
  });
  $("btn-reload-stocktake").addEventListener("click", () => {
    if (!mayLeaveStocktake()) return;
    performStocktake(async () => {
      await stocktake.initialize(stocktake.state.warehouseId);
      if (stocktake.state.current) await stocktake.open(stocktake.state.current.id);
    }, "Đã tải lại dữ liệu kiểm kê.");
  });
  $("btn-toggle-stocktake-history").addEventListener("click", () => {
    const section = $("stocktake-history-section");
    section.hidden = !section.hidden;
    $("btn-stocktake-history-text").textContent = section.hidden ? "Xem lịch sử kiểm kê" : "Ẩn lịch sử kiểm kê";
  });
  $("stocktake-history-body").addEventListener("click", event => {
    const button = event.target.closest("[data-view-stocktake]");
    if (!button || !mayLeaveStocktake()) return;
    performStocktake(() => stocktake.open(button.dataset.viewStocktake), "Đã mở phiếu kiểm kê.");
  });
  $("stocktake-history-prev").addEventListener("click", () =>
    performStocktake(() => stocktake.history(stocktake.state.page - 1), "Đã tải lịch sử kiểm kê."));
  $("stocktake-history-next").addEventListener("click", () =>
    performStocktake(() => stocktake.history(stocktake.state.page + 1), "Đã tải lịch sử kiểm kê."));
  for (const [id, field] of [["stocktake-adjustment-reason", "reason"], ["stocktake-cancel-reason", "cancelReason"]]) {
    $(id).addEventListener("input", event => {
      if (!stocktake.canEdit() || stocktake.state.pending) return;
      stocktake.state[field] = event.target.value;
      stocktake.state.dirty = true;
    });
  }
  $("stocktake-note").addEventListener("input", event => {
    if (!stocktake.canEdit() || stocktake.state.pending) return;
    stocktake.state.current.note = event.target.value;
    stocktake.state.dirty = true;
  });
  window.addEventListener("beforeunload", event => {
    if (stocktake.state.dirty) { event.preventDefault(); event.returnValue = ""; }
  });

  const requestedTab = {
    receipts: "create-receipt", ledger: "ledger-audit", inventory: "inventory-report", stocktake: "stocktake",
  }[initialTabParam] || "sku-units";
  $("active-warehouse-select").addEventListener("change", async () => {
    if (stocktakeIsActive()) {
      const selected = $("active-warehouse-select").value;
      if (stocktake.state.pending || !mayLeaveStocktake()) { renderStocktakePanel(); return; }
      await performStocktake(() => stocktake.selectWarehouse(selected), "Đã đổi kho kiểm kê.");
      return;
    }
    try {
      await Store.init();
      switchTab(document.querySelector(".nav-tab--active")?.dataset.tab || requestedTab);
    } catch (error) { showToast(error.message || "Không tải được dữ liệu kho.", "error"); }
  });

  function syncWarehouseActions() {
    canManageUnits = Auth.hasPermission("WAREHOUSE_MANAGE");
    canRecordVouchers = Auth.hasAnyPermission("WAREHOUSE_IMPORT", "WAREHOUSE_EXPORT");
    canViewOrders = Auth.hasPermission("ORDER_VIEW");
    tabs.forEach(tab => {
      tab.hidden = !(tab.dataset.tab === "sku-units" ? Auth.hasAnyPermission("WAREHOUSE_VIEW", "WAREHOUSE_MANAGE")
        : tab.dataset.tab === "create-receipt" ? canRecordVouchers
        : tab.dataset.tab === "stocktake" ? canReadStocktake()
        : tab.dataset.tab === "ledger-audit" ? canReadLedger() : Auth.hasPermission("WAREHOUSE_VIEW"));
    });
    $("tab-create-receipt").hidden = !canRecordVouchers;
    $("tab-stocktake").hidden = !canReadStocktake();
    if (!canReadStocktake()) $("panel-stocktake").hidden = true;
    $("btn-open-create-unit-modal").hidden = !canManageUnits;

    document.querySelectorAll("#receipt-type-radios input").forEach(radio => {
      const permission = { IMPORT: "WAREHOUSE_IMPORT", EXPORT: "WAREHOUSE_EXPORT", ORDER: "ORDER_CREATE" }[radio.value];
      radio.disabled = !Auth.hasPermission(permission);
      radio.closest("label").hidden = radio.disabled;
    });
    const selected = document.querySelector('#receipt-type-radios input:checked');
    if (!selected || selected.disabled) {
      const first = document.querySelector('#receipt-type-radios input:not(:disabled)');
      if (first) { first.checked = true; receiptState.type = first.value; }
    }
    renderSkuTable();
    renderInventoryTable();
    renderLedgerTable();
    updateReceiptAvailability();
  }
  syncWarehouseActions();
  let permissionVersion = 0;
  window.addEventListener("auth:permissions-changed", async () => {
    const version = ++permissionVersion;
    const previousId = stocktake.state.current?.id;
    const previousWarehouse = stocktake.state.warehouseId;
    stocktake.invalidate();
    Store.epoch++;
    legacyLoad = null;
    Store.loaded = false;
    Store.receipts = [];
    Store.drafts = [];
    Store.suppliers = [];
    modalReceipt.hidden = true;
    stocktakeMessage();
    syncWarehouseActions();
    if (!stocktakeIsActive()) {
      const active = document.querySelector(".nav-tab--active");
      if (active?.hidden) {
        const next = [...tabs].find(tab => !tab.hidden);
        if (next) switchTab(next.dataset.tab);
        else panels.forEach(panel => { panel.hidden = true; });
      } else if (active) await ensureLegacyData();
    }
    // Wait for any old request; its response cannot repopulate the cleared draft.
    await stocktake.idle();
    if (version === permissionVersion && stocktakeIsActive() && canReadStocktake()) {
      $("panel-stocktake").hidden = false;
      await performStocktake(async () => {
        await stocktake.initialize(previousWarehouse);
        if (version === permissionVersion && previousId && stocktake.state.warehouseId === previousWarehouse) await stocktake.open(previousId);
      }, "Đã cập nhật quyền và dữ liệu kiểm kê.");
    }
  });
  const firstAllowedTab = [...tabs].find(tab => !tab.hidden)?.dataset.tab;
  const requestedAllowed = [...tabs].some(tab => tab.dataset.tab === requestedTab && !tab.hidden);
  if (requestedAllowed || firstAllowedTab) switchTab(requestedAllowed ? requestedTab : firstAllowedTab);
  else panels.forEach(panel => { panel.hidden = true; });
})();
