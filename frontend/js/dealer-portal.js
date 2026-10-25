/**
 * dealer-portal.js — S4-10 Cổng tự đặt hàng dành cho Đại lý.
 *
 * Tính năng chính:
 * - Đại lý đăng nhập chỉ thấy sản phẩm và giá thuộc nhóm khách hàng của mình.
 * - Danh mục sản phẩm được lọc chính xác theo bảng giá đang hiệu lực của nhóm khách hàng.
 * - Thêm sản phẩm vào giỏ hàng với quy cách đóng gói (thùng, lốc, lon...).
 * - Xem tổng tiền, chiết khấu và công nợ còn lại trước khi gửi đơn.
 * - Cảnh báo vượt hạn mức hoặc quá hạn công nợ.
 * - Đơn gửi lên luôn ở trạng thái Chờ duyệt, do nhân viên phụ trách xác nhận.
 * - Giao diện mobile-first thuận tiện thao tác bất kỳ lúc nào trên điện thoại.
 *
 * Tuân thủ:
 * - Không lưu dữ liệu nghiệp vụ vào localStorage/sessionStorage.
 * - Không hardcode dữ liệu trong JavaScript.
 * - Sử dụng Auth.apiFetch cho tất cả kết nối API.
 */
 (async () => {
  /* ── Auth guard ─────────────────────────────────────────── */
  if (typeof Auth !== 'undefined' && !Auth.requireLogin()) return;
  await Auth.refreshPermissions();

  if (!Auth.hasAnyRole('ROLE_DEALER', 'ROLE_ADMIN')
      || !['ORDER_CREATE', 'ORDER_VIEW', 'PRODUCT_VIEW'].every(permission => Auth.hasPermission(permission))) {
    location.href = '/error?status=403';
    return;
  }

  /* ── DOM helpers ────────────────────────────────────────── */
  const $ = (id) => document.getElementById(id);
  const escHtml = (str) =>
    String(str ?? '').replace(/[&<>'"]/g, (c) =>
      ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', "'": '&#39;', '"': '&quot;' })[c],
    );

  const formatVND = (val) =>
    new Intl.NumberFormat('vi-VN', { style: 'currency', currency: 'VND' }).format(Number(val) || 0);

  const showToast = (msg, type) => {
    if (typeof Toast !== 'undefined' && Toast.show) {
      Toast.show(msg, type);
    } else {
      console.log(`[Toast ${type}]: ${msg}`);
    }
  };

  const GROUP_NAMES = {
    DAI_LY_CAP_1: 'Đại lý cấp 1',
    DAI_LY_CAP_2: 'Đại lý cấp 2',
    KHACH_LE: 'Khách lẻ',
  };

  /* ── In-Memory State ────────────────────────────────────── */
  const state = {
    user: null,
    dealers: [],
    currentDealer: null,
    deliveryPoints: [],
    effectivePriceList: null,
    allProducts: [],
    catalog: [], // Products filtered by effective price list
    categories: new Set(),
    activeCategory: 'all',
    searchQuery: '',
    debtInfo: null,
    cart: [], // [{ productId, sku, name, unit, conversionFactor, basePrice, unitPrice, quantity, total }]
    preview: null,
    previewKey: '',
    previewError: '',
    previewRequest: null,
    previewVersion: 0,
    confirmedPreview: null,
  };

  /* ── Utility: Today's date YYYY-MM-DD ───────────────────── */
  function todayDate() {
    const d = new Date();
    const mm = String(d.getMonth() + 1).padStart(2, '0');
    const dd = String(d.getDate()).padStart(2, '0');
    return `${d.getFullYear()}-${mm}-${dd}`;
  }

  /* ── Load Dealer Context ────────────────────────────────── */
  async function loadDealerContext() {
    state.user = Auth.getUser();
    try {
      const ready = await DealerPortalContext.init('dp-dealer-switcher-wrap', 'dp-dealer-select');
      if (!ready) {
        $('topbar-dealer-name').textContent = 'Chọn đại lý';
        $('info-dealer-status').textContent = 'Chưa chọn đại lý';
        $('info-dealer-assigned').textContent = 'Chọn đại lý để xem danh mục, công nợ và đặt hàng.';
        $('dp-catalog-count').textContent = 'Chưa chọn đại lý';
        $('dp-products-grid').innerHTML = '<p>Chọn đại lý để xem sản phẩm có thể đặt.</p>';
        $('btn-submit-order').disabled = true;
        return;
      }
      const res = await Auth.apiFetch(DealerPortalContext.url('/portal/profile'));
      const result = await res.json();
      if (!res.ok || !result.success || !result.data) {
        throw new Error(result.message || 'Không tải được hồ sơ đại lý');
      }
      const profile = result.data;
      state.currentDealer = { ...profile, assignedUser: { fullName: profile.salesUserFullName } };
      state.dealers = [state.currentDealer];
      const switcher = $('dp-dealer-switcher-wrap');
      if (switcher) switcher.hidden = !DealerPortalContext.isAdmin;
      await applyDealer(state.currentDealer);
    } catch (err) {
      state.currentDealer = null;
      showToast(err.message, 'error');
    }
  }

  /* ── Switch Dealer ──────────────────────────────────────── */
  async function switchDealer(dealer) {
    state.currentDealer = dealer;
    state.cart = []; // clear cart for previous dealer
    updateCartUI();
    await applyDealer(dealer);
  }

  /* ── Apply Dealer ───────────────────────────────────────── */
  async function applyDealer(dealer) {
    if (!dealer) return;

    // Render Dealer Header
    const topName = $('topbar-dealer-name');
    if (topName) topName.textContent = dealer.name;

    const groupLabel = GROUP_NAMES[dealer.customerGroup] || dealer.customerGroup || 'Đại lý';
    const topBadge = $('topbar-group-badge');
    if (topBadge) topBadge.textContent = groupLabel;

    const infoName = $('info-dealer-name');
    if (infoName) infoName.textContent = dealer.name;

    const infoCode = $('info-dealer-code');
    if (infoCode) infoCode.textContent = dealer.code || `DL-${dealer.id}`;

    const infoAssigned = $('info-dealer-assigned');
    if (infoAssigned) {
      infoAssigned.textContent = dealer.assignedUser?.fullName
        ? `Nhân viên phụ trách: ${dealer.assignedUser.fullName}`
        : 'Chưa phân công nhân viên';
    }

    // Load Delivery Points
    await loadDeliveryPoints(dealer.id);

    // Load Debt & Credit info
    await loadDebtInfo(dealer.id);

    // Load Price List & Filter Products
    await loadCatalogForDealer(dealer);
  }

  /* ── Load Delivery Points ───────────────────────────────── */
  async function loadDeliveryPoints(dealerId) {
    const sel = $('dp-delivery-point-select');
    if (!sel) return;
    sel.innerHTML = '<option value="">— Đang tải điểm giao —</option>';
    try {
      const res = await Auth.apiFetch(DealerPortalContext.url('/portal/delivery-points'));
      const result = await res.json();
      state.deliveryPoints = (res.ok && result.success ? result.data : []) || [];

      if (state.deliveryPoints.length === 0) {
        sel.innerHTML = '<option value="">Trụ sở chính của đại lý</option>';
      } else {
        sel.innerHTML =
          '<option value="">— Chọn điểm giao hàng —</option>' +
          state.deliveryPoints
            .map(
              (p) =>
                `<option value="${p.id}" ${p.isDefault ? 'selected' : ''}>${escHtml(p.name)} (${escHtml(p.address || '')})</option>`,
            )
            .join('');
      }
    } catch {
      sel.innerHTML = '<option value="">Trụ sở chính của đại lý</option>';
    }
  }

  /* ── Load Debt Info ─────────────────────────────────────── */
  async function loadDebtInfo() {
    if (!Auth.hasAnyRole('ROLE_ADMIN') && !Auth.hasPermission('FINANCE_VIEW_DEBT')) {
      state.debtInfo = { unavailable: true, isBlocked: false };
      renderDebtMetrics();
      return;
    }
    try {
      const res = await Auth.apiFetch(DealerPortalContext.url('/portal/debt'));
      const result = await res.json();
      if (!res.ok || !result.success || !result.data) {
        throw new Error(result.message || 'Không tải được công nợ, vui lòng thử lại');
      }
      if (!Auth.hasAnyRole('ROLE_ADMIN') && !Auth.hasPermission('FINANCE_VIEW_DEBT')) return;
      state.debtInfo = result.data;
    } catch (err) {
      state.debtInfo = { isBlocked: true, blockReason: err.message, unavailable: true };
      showToast(err.message, 'error');
    }
    renderDebtMetrics();
  }

  function renderDebtMetrics() {
    const canViewDebt = Auth.hasAnyRole('ROLE_ADMIN') || Auth.hasPermission('FINANCE_VIEW_DEBT');
    const info = state.debtInfo || {};
    const limit = Number(info.debtLimit) || 0;
    const current = Number(info.currentDebt) || 0;
    const avail = info.remainingLimit != null ? Number(info.remainingLimit) : Math.max(0, limit - current);

    const valLimit = $('val-debt-limit');
    if (valLimit) valLimit.textContent = !canViewDebt ? 'Không có quyền xem' : info.unavailable ? 'Chưa tải được' : formatVND(limit);

    const valCurrent = $('val-current-debt');
    if (valCurrent) valCurrent.textContent = !canViewDebt ? 'Không có quyền xem' : info.unavailable ? 'Chưa tải được' : formatVND(current);

    const valAvail = $('val-avail-debt');
    if (valAvail) valAvail.textContent = !canViewDebt ? 'Không có quyền xem' : info.unavailable ? 'Chưa tải được' : formatVND(avail);

    const alertEl = $('dp-debt-overdue-alert');
    if (alertEl) {
      if (canViewDebt && (info.hasOverdueDebt || info.isBlocked)) {
        alertEl.hidden = false;
        const msgEl = $('dp-debt-overdue-msg');
        if (msgEl) {
          msgEl.textContent =
            info.blockReason || 'Đại lý có khoản nợ quá hạn chưa thanh toán. Vui lòng liên hệ nhân viên phụ trách.';
        }
      } else {
        alertEl.hidden = true;
      }
    }
  }

  /* ── Load Catalog for Dealer Customer Group ─────────────── */
  async function loadCatalogForDealer() {
    const grid = $('dp-products-grid');
    try {
      const res = await Auth.apiFetch(DealerPortalContext.url('/portal/catalog'));
      const result = await res.json();
      if (!res.ok || !result.success || !Array.isArray(result.data)) {
        throw new Error(result.message || 'Không tải được sản phẩm và bảng giá');
      }
      state.catalog = result.data.map((product) => ({
        ...product,
        categoryName: product.categoryName || 'Khác',
        units: product.units || [],
      })).filter((product) => product.units.length > 0);
      state.categories = new Set(state.catalog.map((product) => product.categoryName));
      renderCategoryPills();
      renderCatalog();
    } catch (err) {
      state.catalog = [];
      if (grid) grid.innerHTML = `<div class="dp-empty-state"><p>${escHtml(err.message)}</p></div>`;
    }
  }

  /* ── Render Category Pills ──────────────────────────────── */
  function renderCategoryPills() {
    const list = $('dp-category-list');
    if (!list) return;
    const categories = ['all', ...Array.from(state.categories).sort()];

    list.innerHTML = categories
      .map((cat) => {
        const isAll = cat === 'all';
        const label = isAll ? 'Tất cả sản phẩm' : cat;
        const active = state.activeCategory === cat ? 'dp-cat-pill--active' : '';
        return `<button class="dp-cat-pill ${active}" data-cat="${escHtml(cat)}" type="button" role="tab">${escHtml(label)}</button>`;
      })
      .join('');

    list.querySelectorAll('.dp-cat-pill').forEach((btn) => {
      btn.addEventListener('click', () => {
        state.activeCategory = btn.dataset.cat;
        renderCategoryPills();
        renderCatalog();
      });
    });
  }

  /* ── Render Catalog ─────────────────────────────────────── */
  function renderCatalog() {
    const grid = $('dp-products-grid');
    const emptyEl = $('dp-empty-catalog');
    const countEl = $('dp-catalog-count');

    let filtered = state.catalog;

    // Filter by active category
    if (state.activeCategory !== 'all') {
      filtered = filtered.filter((p) => p.categoryName === state.activeCategory);
    }

    // Filter by search query
    if (state.searchQuery) {
      const q = state.searchQuery.toLowerCase();
      filtered = filtered.filter(
        (p) => p.name.toLowerCase().includes(q) || p.sku.toLowerCase().includes(q),
      );
    }

    if (countEl) countEl.textContent = `${filtered.length} sản phẩm`;

    if (filtered.length === 0) {
      if (grid) grid.innerHTML = '';
      if (emptyEl) emptyEl.hidden = false;
      return;
    }

    if (emptyEl) emptyEl.hidden = true;
    if (!grid) return;

    grid.innerHTML = filtered
      .map((prod) => {
        const defaultUnit = prod.units[0];
        const unitOptions = prod.units
          .map(
            (u) =>
              `<option value="${escHtml(u.unitName)}" data-price="${u.price}" data-factor="${u.factor}">${escHtml(u.unitName)} (${formatVND(u.price)})</option>`,
          )
          .join('');

        return `
          <article class="dp-product-card" data-product-id="${prod.id}">
            <div class="dp-card-top">
              <span class="dp-card-category">${escHtml(prod.categoryName)}</span>
              <h3 class="dp-card-name">${escHtml(prod.name)}</h3>
              <span class="dp-card-sku">SKU: ${escHtml(prod.sku)}</span>
              <div class="dp-card-pricing">
                <span class="dp-card-price" id="card-price-${prod.id}">${formatVND(defaultUnit.price)}</span>
                <span class="dp-card-price-unit" id="card-unit-label-${prod.id}">/ ${escHtml(defaultUnit.unitName)}</span>
              </div>
            </div>

            <div class="dp-card-unit-select-wrap">
              <label class="dp-card-unit-label" for="select-unit-${prod.id}">Quy cách đặt:</label>
              <select class="dp-card-unit-select" id="select-unit-${prod.id}">
                ${unitOptions}
              </select>
            </div>

            <div class="dp-card-actions">
              <div class="dp-stepper">
                <button type="button" class="dp-stepper__btn dp-stepper__btn--minus" data-stepper-minus="${prod.id}" aria-label="Giảm">−</button>
                <input type="number" class="dp-stepper__input" id="qty-input-${prod.id}" value="1" min="1" max="9999" />
                <button type="button" class="dp-stepper__btn dp-stepper__btn--plus" data-stepper-plus="${prod.id}" aria-label="Tăng">+</button>
              </div>
              <button type="button" class="dp-card-add-btn" data-add-to-cart="${prod.id}">
                <svg viewBox="0 0 24 24" width="16" height="16">
                  <path d="M12 5v14M5 12h14"/>
                </svg>
                <span>Thêm</span>
              </button>
            </div>
          </article>
        `;
      })
      .join('');

    // Attach card event listeners
    filtered.forEach((prod) => {
      const unitSelect = $(`select-unit-${prod.id}`);
      const priceEl = $(`card-price-${prod.id}`);
      const unitLabelEl = $(`card-unit-label-${prod.id}`);
      const qtyInput = $(`qty-input-${prod.id}`);

      if (unitSelect) {
        unitSelect.addEventListener('change', () => {
          const selectedOption = unitSelect.selectedOptions[0];
          const price = Number(selectedOption.dataset.price) || 0;
          if (priceEl) priceEl.textContent = formatVND(price);
          if (unitLabelEl) unitLabelEl.textContent = `/ ${selectedOption.value}`;
        });
      }

      const minusBtn = grid.querySelector(`[data-stepper-minus="${prod.id}"]`);
      const plusBtn = grid.querySelector(`[data-stepper-plus="${prod.id}"]`);
      const addBtn = grid.querySelector(`[data-add-to-cart="${prod.id}"]`);

      minusBtn?.addEventListener('click', () => {
        const val = Math.max(1, (parseInt(qtyInput.value, 10) || 1) - 1);
        qtyInput.value = val;
      });

      plusBtn?.addEventListener('click', () => {
        const val = (parseInt(qtyInput.value, 10) || 1) + 1;
        qtyInput.value = val;
      });

      addBtn?.addEventListener('click', () => {
        const selectedOption = unitSelect.selectedOptions[0];
        const unitName = selectedOption.value;
        const factor = Number(selectedOption.dataset.factor) || 1;
        const unitPrice = Number(selectedOption.dataset.price) || 0;
        const qty = Math.max(1, parseInt(qtyInput.value, 10) || 1);

        addToCart(prod, unitName, factor, unitPrice, qty);
      });
    });
  }

  /* ── Add to Cart ────────────────────────────────────────── */
  function addToCart(prod, unitName, factor, unitPrice, qty) {
    const existing = state.cart.find((it) => it.productId === prod.id && it.unit === unitName);
    if (existing) {
      existing.quantity += qty;
      existing.total = existing.quantity * existing.unitPrice;
    } else {
      state.cart.push({
        productId: prod.id,
        sku: prod.sku,
        name: prod.name,
        unit: unitName,
        conversionFactor: factor,
        basePrice: prod.basePrice,
        unitPrice: unitPrice,
        quantity: qty,
        total: qty * unitPrice,
      });
    }

    showToast(`Đã thêm ${qty} ${unitName} '${prod.name}' vào giỏ hàng`, 'success');
    updateCartUI();
  }

  /* ── Update Cart UI ─────────────────────────────────────── */
  function cartKey() {
    return JSON.stringify([state.currentDealer?.id, state.cart.map(item => [item.productId, item.unit, item.quantity])]);
  }

  function canOrder() {
    return ['ORDER_CREATE', 'ORDER_VIEW', 'PRODUCT_VIEW'].every(permission => Auth.hasPermission(permission));
  }

  function previewReady() {
    return canOrder() && state.preview && state.previewKey === cartKey() && !state.previewError
      && !state.preview.debt.isBlocked && !state.preview.debt.hasOverdueDebt
      && !state.preview.pricing.items.some(item => item.exceedsStock);
  }

  async function refreshCartPreview(force = false) {
    const key = cartKey();
    if (!state.cart.length || !state.currentDealer || !canOrder()) return false;
    if (state.previewRequest?.key === key) return state.previewRequest.promise;
    if (!force && previewReady()) return true;
    const version = ++state.previewVersion;
    state.previewError = '';
    state.preview = null;
    const promise = (async () => {
      try {
        const response = await Auth.apiFetch(DealerPortalContext.url('/portal/orders/preview'), {
          method: 'POST', body: JSON.stringify({ items: state.cart.map(item => ({
            productId: item.productId, unit: item.unit, quantity: item.quantity,
          })) }),
        });
        const result = await response.json();
        if (version !== state.previewVersion || key !== cartKey() || !canOrder()) return false;
        const data = result.data;
        if (!response.ok || !result.success) throw new Error(result.message || 'Không tính được tổng tiền hiện hành.');
        if (!data?.debt || !Array.isArray(data.pricing?.items)
            || data.pricing.items.length !== state.cart.length
            || !['subtotalAmount', 'discountAmount', 'totalAmount'].every(field => data.pricing[field] != null && Number.isFinite(Number(data.pricing[field])))) {
          throw new Error('Kết quả tính tiền không hợp lệ. Vui lòng thử lại.');
        }
        state.preview = data;
        state.debtInfo = data.debt;
        state.cart.forEach((item, index) => {
          const priced = data.pricing.items[index];
          item.unitPrice = Number(priced.price);
          item.total = Number(priced.totalAmount);
        });
        return Boolean(previewReady());
      } catch (error) {
        if (version === state.previewVersion && key === cartKey()) {
          state.preview = null;
          state.previewError = error.message || 'Không tính được tổng tiền hiện hành.';
        }
        return false;
      } finally {
        if (version === state.previewVersion) {
          state.previewRequest = null;
          updateCartUI();
        }
      }
    })();
    state.previewRequest = { key, promise };
    updateCartUI();
    return promise;
  }

  function updateCartUI() {
    const key = cartKey();
    if (state.previewKey !== key) {
      state.previewKey = key;
      state.preview = null;
      state.previewError = '';
      state.previewVersion++;
      state.previewRequest = null;
      if (state.cart.length && state.currentDealer) Promise.resolve().then(() => refreshCartPreview());
    }
    const totalItems = state.cart.reduce((sum, it) => sum + it.quantity, 0);
    const pricing = state.preview?.pricing;
    const subtotal = Number(pricing?.subtotalAmount || 0);
    const total = Number(pricing?.totalAmount || 0);
    const amountText = value => !state.cart.length || pricing ? formatVND(value) : state.previewError ? 'Chưa tính được' : 'Đang tính…';

    // Update Header Badge & Mobile Bottom Bar
    const badge = $('cart-badge-count');
    if (badge) badge.textContent = totalItems;

    const mobCount = $('mobile-cart-items-count');
    if (mobCount) mobCount.textContent = `${totalItems} món`;

    const mobTotal = $('mobile-cart-grand-total');
    if (mobTotal) mobTotal.textContent = amountText(total);

    // Render Cart Drawer Items
    const listEl = $('dp-cart-items-list');
    const emptyEl = $('dp-empty-cart');
    const submitBtn = $('btn-submit-order');

    if (state.cart.length === 0) {
      if (listEl) listEl.innerHTML = '';
      if (emptyEl) emptyEl.hidden = false;
      if (submitBtn) submitBtn.disabled = true;
    } else {
      if (emptyEl) emptyEl.hidden = true;
      if (submitBtn) submitBtn.disabled = !previewReady() || Boolean(state.previewRequest);
      if (listEl) {
        listEl.innerHTML = state.cart
          .map(
            (it, idx) => `
            <div class="dp-cart-item">
              <div class="dp-cart-item__info">
                <strong class="dp-cart-item__name">${escHtml(it.name)}</strong>
                <span class="dp-cart-item__sku">SKU: ${escHtml(it.sku)} • ĐVT: ${escHtml(it.unit)}</span>
                <span class="dp-cart-item__unit-price">${formatVND(it.unitPrice)} / ${escHtml(it.unit)}</span>
                <span class="dp-cart-item__line-total">Thành tiền: ${formatVND(it.total)}</span>
              </div>
              <div class="dp-cart-item__actions">
                <button class="dp-cart-item__del" data-del-cart="${idx}" aria-label="Xoá dòng" title="Xoá">
                  <svg viewBox="0 0 24 24" width="16" height="16"><polyline points="3 6 5 6 21 6"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>
                </button>
                <div class="dp-stepper">
                  <button type="button" class="dp-stepper__btn" data-cart-minus="${idx}">−</button>
                  <input type="number" class="dp-stepper__input" data-cart-input="${idx}" value="${it.quantity}" min="1" max="9999" />
                  <button type="button" class="dp-stepper__btn" data-cart-plus="${idx}">+</button>
                </div>
              </div>
            </div>
          `,
          )
          .join('');

        // Attach cart item handlers
        listEl.querySelectorAll('[data-del-cart]').forEach((btn) => {
          btn.addEventListener('click', () => {
            const idx = Number(btn.dataset.delCart);
            state.cart.splice(idx, 1);
            updateCartUI();
          });
        });

        listEl.querySelectorAll('[data-cart-minus]').forEach((btn) => {
          btn.addEventListener('click', () => {
            const idx = Number(btn.dataset.cartMinus);
            if (state.cart[idx].quantity > 1) {
              state.cart[idx].quantity--;
              state.cart[idx].total = state.cart[idx].quantity * state.cart[idx].unitPrice;
              updateCartUI();
            }
          });
        });

        listEl.querySelectorAll('[data-cart-plus]').forEach((btn) => {
          btn.addEventListener('click', () => {
            const idx = Number(btn.dataset.cartPlus);
            state.cart[idx].quantity++;
            state.cart[idx].total = state.cart[idx].quantity * state.cart[idx].unitPrice;
            updateCartUI();
          });
        });

        listEl.querySelectorAll('[data-cart-input]').forEach((input) => {
          input.addEventListener('change', () => {
            const idx = Number(input.dataset.cartInput);
            const val = Math.max(1, parseInt(input.value, 10) || 1);
            state.cart[idx].quantity = val;
            state.cart[idx].total = val * state.cart[idx].unitPrice;
            updateCartUI();
          });
        });
      }
    }

    // Financial breakdown in cart
    const subtotalEl = $('dp-subtotal-val');
    if (subtotalEl) subtotalEl.textContent = amountText(subtotal);

    const discEl = $('dp-discount-val');
    if (discEl) discEl.textContent = amountText(Number(pricing?.discountAmount || 0));

    const totalEl = $('dp-total-val');
    if (totalEl) totalEl.textContent = amountText(total);

    const previewMessage = $('dp-preview-message');
    if (previewMessage) {
      previewMessage.hidden = !state.cart.length || Boolean(pricing && previewReady());
      previewMessage.textContent = state.previewError || (pricing?.items.some(item => item.exceedsStock)
        ? 'Có mặt hàng vượt tồn khả dụng. Vui lòng giảm số lượng.'
        : state.debtInfo?.blockReason || 'Đang kiểm tra giá, chiết khấu, tồn kho và công nợ từ máy chủ…');
    }
    const retry = $('btn-refresh-cart-preview');
    if (retry) { retry.hidden = !state.previewError; retry.disabled = Boolean(state.previewRequest); }

    // Debt assessment
    const debt = state.debtInfo || {};
    const avail = debt.remainingLimit != null ? Number(debt.remainingLimit) : Math.max(0, (Number(debt.debtLimit) || 0) - (Number(debt.currentDebt) || 0));
    const currAvailEl = $('dp-curr-available-debt');
    if (currAvailEl) currAvailEl.textContent = formatVND(avail);

    const projected = avail - total;
    const projEl = $('dp-projected-remaining-debt');
    if (projEl) projEl.textContent = amountText(projected);

    const warnEl = $('dp-debt-warning-msg');
    if (warnEl) {
      if (debt.hasOverdueDebt || debt.isBlocked) {
        warnEl.hidden = false;
        warnEl.textContent = 'Cảnh báo: Đại lý có nợ quá hạn. Đơn hàng sẽ bị chặn hoặc cần kế toán xử lý.';
      } else if (total > avail && (Number(debt.debtLimit) || 0) > 0) {
        warnEl.hidden = false;
        warnEl.textContent = `Đơn hàng vượt hạn mức công nợ còn lại (${formatVND(Math.abs(projected))}). Đơn sẽ cần Quản lý duyệt trước khi giao.`;
      } else {
        warnEl.hidden = true;
      }
    }
  }

  /* ── Open / Close Cart Drawer ───────────────────────────── */
  function openCartDrawer() {
    const drawer = $('dp-cart-drawer');
    if (drawer) drawer.hidden = false;
    if (typeof document !== 'undefined' && document.body) {
      document.body.style.overflow = 'hidden';
    }
    updateCartUI();
    refreshCartPreview(true);
  }

  function closeCartDrawer() {
    const drawer = $('dp-cart-drawer');
    if (drawer) drawer.hidden = true;
    if (typeof document !== 'undefined' && document.body) {
      document.body.style.overflow = '';
    }
  }

  /* ── Order Submission Flow ──────────────────────────────── */
  async function openConfirmModal() {
    if (state.cart.length === 0) {
      showToast('Giỏ hàng chưa có sản phẩm nào!', 'warning');
      return;
    }

    const deliveryPointId = $('dp-delivery-point-select')?.value;
    const deliveryDate = $('dp-delivery-date-input')?.value;

    if (!deliveryDate) {
      showToast('Vui lòng chọn ngày giao hàng mong muốn!', 'warning');
      $('dp-delivery-date-input')?.focus();
      return;
    }

    if (!await refreshCartPreview(true)) {
      showToast(state.previewError || state.debtInfo?.blockReason || 'Vui lòng kiểm tra lại tồn kho và công nợ.', 'error');
      return;
    }
    const total = Number(state.preview.pricing.totalAmount);
    state.confirmedPreview = { key: cartKey(), total };
    const content = $('dp-confirm-content');
    if (content) {
      content.innerHTML = `
        <div><strong>Đại lý:</strong> ${escHtml(state.currentDealer?.name)} (${escHtml(state.currentDealer?.code)})</div>
        <div><strong>Số lượng dòng hàng:</strong> ${state.cart.length} sản phẩm (${state.cart.reduce((s, it) => s + it.quantity, 0)} kiện/đơn vị)</div>
        <div><strong>Ngày giao dự kiến:</strong> ${escHtml(deliveryDate)}</div>
        <div style="font-size: 1rem; font-weight: 700; color: var(--teal-deep); margin-top: 6px;">Tổng thanh toán: ${formatVND(total)}</div>
      `;
    }

    const debtNotice = $('dp-confirm-debt-notice');
    if (debtNotice) {
      debtNotice.innerHTML = `
        <p>Trạng thái đơn: <strong style="color: var(--amber)">Chờ duyệt</strong>.</p>
        <p>Sau khi gửi, nhân viên kinh doanh phụ trách sẽ kiểm tra công nợ và duyệt xuất hàng cho quý đại lý.</p>
      `;
    }

    const confirmModal = $('dp-confirm-modal');
    if (confirmModal) confirmModal.hidden = false;
  }

  function closeConfirmModal() {
    const confirmModal = $('dp-confirm-modal');
    if (confirmModal) confirmModal.hidden = true;
  }

  async function submitDealerOrder() {
    if (!state.currentDealer || !state.cart.length || !state.debtInfo || state.debtInfo.isBlocked
        || state.debtInfo.hasOverdueDebt || state.currentDealer.locked || state.currentDealer.active === false) {
      showToast(state.debtInfo?.blockReason || 'Đại lý hiện chưa thể gửi đơn hàng.', 'error');
      return;
    }
    const btn = $('btn-confirm-send');
    if (btn) {
      btn.disabled = true;
      btn.textContent = 'Đang gửi đơn…';
    }

    const deliveryPointId = $('dp-delivery-point-select')?.value;
    const deliveryDate = $('dp-delivery-date-input')?.value;
    const note = $('dp-order-note')?.value?.trim() || '';

    const payload = {
      deliveryPointId: deliveryPointId ? Number(deliveryPointId) : null,
      expectedDeliveryDate: deliveryDate || null,
      note: note ? `[Đại lý tự đặt qua cổng] ${note}` : '[Đại lý tự đặt qua cổng]',
      items: state.cart.map((it) => ({
        productId: it.productId,
        unit: it.unit,
        quantity: it.quantity,
      })),
    };

    try {
      const confirmed = state.confirmedPreview;
      if (!await refreshCartPreview(true)) throw new Error(state.previewError || state.debtInfo?.blockReason || 'Chưa thể gửi đơn với giá và tồn kho hiện tại.');
      if (confirmed && (confirmed.key !== cartKey() || confirmed.total !== Number(state.preview.pricing.totalAmount))) {
        await openConfirmModal();
        throw new Error('Giá hoặc giỏ hàng đã thay đổi. Vui lòng xem tổng tiền mới và xác nhận lại.');
      }
      const res = await Auth.apiFetch(DealerPortalContext.url('/portal/orders'), {
        method: 'POST',
        body: JSON.stringify(payload),
      });

      const result = await res.json();
      if (!res.ok || !result.success) {
        throw new Error(result.message || 'Không thể gửi đơn hàng');
      }

      const created = result.data;
      closeConfirmModal();
      closeCartDrawer();

      // Show success modal
      openSuccessModal(created);

      // Reset cart and refresh debt
      state.cart = [];
      updateCartUI();
      await loadDebtInfo(state.currentDealer.id);
    } catch (err) {
      console.error('Order creation error:', err);
      const alertEl = $('dp-confirm-alert');
      if (alertEl) {
        alertEl.textContent = err.message || 'Lỗi gửi đơn hàng. Vui lòng thử lại.';
        alertEl.hidden = false;
      }
      showToast(err.message || 'Lỗi gửi đơn hàng', 'error');
    } finally {
      if (btn) {
        btn.disabled = false;
        btn.textContent = 'Xác nhận gửi đơn';
      }
    }
  }

  function openSuccessModal(order) {
    const successCode = $('dp-success-code');
    if (successCode) successCode.textContent = order?.orderCode || 'Đơn hàng mới';

    const details = $('dp-success-details');
    if (details) {
      details.innerHTML = `
        <div><strong>Mã đơn hàng:</strong> ${escHtml(order?.orderCode || '')}</div>
        <div><strong>Đại lý:</strong> ${escHtml(state.currentDealer?.name || '')}</div>
        <div><strong>Tổng tiền:</strong> ${formatVND(order?.totalAmount || 0)}</div>
        <div><strong>Trạng thái:</strong> <span style="color: var(--amber); font-weight: 700;">Chờ duyệt</span></div>
      `;
    }

    const successModal = $('dp-success-modal');
    if (successModal) successModal.hidden = false;
  }

  function closeSuccessModal() {
    const successModal = $('dp-success-modal');
    if (successModal) successModal.hidden = true;
  }

  /* ── Search Handler ─────────────────────────────────────── */
  function setupSearch() {
    const input = $('dp-search-input');
    const clearBtn = $('dp-search-clear');
    if (!input) return;

    input.addEventListener('input', () => {
      state.searchQuery = input.value.trim();
      if (clearBtn) clearBtn.hidden = !state.searchQuery;
      renderCatalog();
    });

    if (clearBtn) {
      clearBtn.addEventListener('click', () => {
        input.value = '';
        state.searchQuery = '';
        clearBtn.hidden = true;
        renderCatalog();
        input.focus();
      });
    }
  }

  /* ── Init ────────────────────────────────────────────────── */
  function init() {
    const deliveryDateInput = $('dp-delivery-date-input');
    if (deliveryDateInput) {
      deliveryDateInput.min = todayDate();
      deliveryDateInput.value = todayDate();
    }

    $('btn-open-cart')?.addEventListener('click', openCartDrawer);
    $('btn-mobile-view-cart')?.addEventListener('click', openCartDrawer);
    $('btn-close-cart')?.addEventListener('click', closeCartDrawer);
    $('btn-continue-shopping')?.addEventListener('click', closeCartDrawer);
    $('dp-cart-drawer')?.addEventListener('click', (e) => {
      if (e.target === $('dp-cart-drawer')) closeCartDrawer();
    });

    $('btn-submit-order')?.addEventListener('click', openConfirmModal);
    $('btn-refresh-cart-preview')?.addEventListener('click', () => refreshCartPreview(true));
    $('btn-close-confirm')?.addEventListener('click', closeConfirmModal);
    $('btn-cancel-confirm')?.addEventListener('click', closeConfirmModal);
    $('btn-confirm-send')?.addEventListener('click', submitDealerOrder);

    $('btn-close-success')?.addEventListener('click', closeSuccessModal);
    $('btn-success-new-order')?.addEventListener('click', closeSuccessModal);

    setupSearch();
    loadDealerContext();
  }

  // Export for testing
  const DealerPortal = {
    state,
    formatVND,
    applyDealer,
    addToCart,
    updateCartUI,
    refreshCartPreview,
    submitDealerOrder,
    openCartDrawer,
    closeCartDrawer,
    openConfirmModal,
  };

  if (typeof window !== 'undefined') {
    window.DealerPortal = DealerPortal;
  }
  if (typeof module !== 'undefined' && module.exports) {
    module.exports = DealerPortal;
  }

  if (typeof document !== 'undefined') {
    if (document.readyState === 'loading') {
      document.addEventListener('DOMContentLoaded', init);
    } else {
      init();
    }
  }
  globalThis.addEventListener?.('auth:permissions-changed', () => {
    renderDebtMetrics();
    state.preview = null;
    state.previewVersion++;
    state.previewRequest = null;
    if (!canOrder()) { closeCartDrawer(); closeConfirmModal(); }
    else if (state.cart.length) refreshCartPreview(true);
    updateCartUI();
  });
})();
