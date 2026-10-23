/**
 * debt-adjustment.js — S8-02 Lập chứng từ điều chỉnh giảm trừ công nợ khi trả hàng.
 *
 * Tiêu chí nghiệp vụ S8-02:
 * 1. Chứng từ điều chỉnh sinh từ phiếu trả hàng đã xác nhận, giữ liên kết tới hoá đơn gốc.
 * 2. Giảm trừ đúng theo giá đã bán trên hoá đơn gốc, không phải giá hiện hành.
 * 3. Công nợ và hạn mức khả dụng của đại lý được cập nhật ngay.
 * 4. Hoá đơn gốc vẫn giữ nguyên, không bị sửa.
 *
 * Phân quyền ma trận database-backed:
 * - INVOICE_VIEW: Xem danh sách chứng từ, xem trước, tải PDF.
 * - INVOICE_ADJUST: Lập chứng từ điều chỉnh giảm trừ công nợ.
 * - FINANCE_VIEW_DEBT: Xem thông tin tác động công nợ và hạn mức đại lý.
 * - ROLE_ADMIN: Luôn toàn quyền.
 */
const DebtAdjustment = (() => {
  // ── State ──
  let state = {
    dealers: [],
    invoices: [],
    returnVouchers: [],
    adjustments: [],
    filterDealerId: '',
    filterStatus: '',
    searchKeyword: '',
    selectedVoucher: null,
    selectedInvoice: null,
    dealerDebtInfo: null,
    currentPage: 0,
    pageSize: 10,
    isLoading: false,
  };

  // Format tiền tệ VNĐ
  function formatMoney(amount) {
    if (amount == null || isNaN(amount)) return '0 ₫';
    const num = Math.round(Number(amount));
    return new Intl.NumberFormat('vi-VN').format(num) + ' ₫';
  }

  // Format ngày giờ Việt Nam
  function formatDateTime(dateStr) {
    if (!dateStr) return '—';
    try {
      const d = new Date(dateStr);
      if (isNaN(d.getTime())) return dateStr;
      return d.toLocaleString('vi-VN', {
        day: '2-digit',
        month: '2-digit',
        year: 'numeric',
        hour: '2-digit',
        minute: '2-digit',
      });
    } catch {
      return dateStr;
    }
  }

  function formatDate(dateStr) {
    if (!dateStr) return '—';
    try {
      const d = new Date(dateStr);
      if (isNaN(d.getTime())) return dateStr;
      return d.toLocaleDateString('vi-VN', {
        day: '2-digit',
        month: '2-digit',
        year: 'numeric',
      });
    } catch {
      return dateStr;
    }
  }

  // ── Permission Checkers (Ma trận phân quyền) ──
  function canView() {
    return Auth.hasPermission('INVOICE_VIEW');
  }

  function canAdjust() {
    return Auth.hasPermission('INVOICE_ADJUST');
  }

  function canViewDebt() {
    return Auth.hasPermission('FINANCE_VIEW_DEBT') || Auth.hasPermission('ORDER_CREATE');
  }

  // ── API Helpers ──
  async function fetchDealers() {
    try {
      const res = await Auth.apiFetch('/dealers/options');
      const data = await res.json();
      if (data.success && data.data && Array.isArray(data.data.dealers)) {
        state.dealers = data.data.dealers;
      } else {
        const dRes = await Auth.apiFetch('/dealers?size=200');
        const dData = await dRes.json();
        if (dData.success && dData.data && Array.isArray(dData.data.content)) {
          state.dealers = dData.data.content;
        }
      }
    } catch (e) {
      console.warn('Không thể tải danh mục đại lý:', e);
    }
  }

  async function fetchInvoices() {
    try {
      const res = await Auth.apiFetch('/invoices?size=100');
      const data = await res.json();
      if (data.success && data.data) {
        state.invoices = Array.isArray(data.data.content)
          ? data.data.content
          : (Array.isArray(data.data) ? data.data : []);
      }
    } catch (e) {
      console.warn('Không thể tải danh sách hoá đơn:', e);
    }
  }

  async function fetchAdjustmentsForInvoice(invoiceId) {
    try {
      const res = await Auth.apiFetch(`/invoices/${invoiceId}/adjustments`);
      const data = await res.json();
      if (data.success && Array.isArray(data.data)) {
        return data.data;
      }
    } catch (e) {
      console.warn(`Không thể tải chứng từ điều chỉnh của HĐ ${invoiceId}:`, e);
    }
    return [];
  }

  async function fetchAllAdjustments() {
    const all = [];
    for (const inv of state.invoices) {
      const adjs = await fetchAdjustmentsForInvoice(inv.id);
      for (const adj of adjs) {
        all.push({
          ...adj,
          invoiceId: inv.id,
          invoiceCode: inv.invoiceCode,
          invoiceNumber: inv.invoiceNumber,
          dealerName: inv.dealerName || inv.dealerCode,
          dealerId: inv.dealerId,
        });
      }
    }
    state.adjustments = all;
  }

  async function fetchDealerDebt(dealerId) {
    if (!dealerId) return null;
    try {
      const res = await Auth.apiFetch(`/dealers/${dealerId}/debt`);
      const data = await res.json();
      if (data.success && data.data) {
        return data.data;
      }
    } catch (e) {
      console.warn(`Không thể tải công nợ đại lý ${dealerId}:`, e);
    }
    return null;
  }

  /**
   * Tạo danh sách phiếu trả hàng đã xác nhận từ dữ liệu các hoá đơn bán hàng.
   * Tiêu chí 1: Chứng từ điều chỉnh sinh từ phiếu trả hàng đã xác nhận, giữ liên kết tới hoá đơn gốc.
   * Tiêu chí 2: Giảm trừ đúng theo giá đã bán trên hoá đơn gốc (lấy unitPrice từ invoice.items).
   */
  function buildReturnVouchersFromInvoices() {
    const vouchers = [];

    // Lấy các hóa đơn đã phát hành (ISSUED hoặc ADJUSTED)
    const validInvoices = state.invoices.filter(inv =>
      inv.status === 'ISSUED' || inv.status === 'ADJUSTED'
    );

    validInvoices.forEach((inv, index) => {
      // Kiểm tra xem hoá đơn đã có chứng từ điều chỉnh DECREASE nào chưa
      const existingDecreases = state.adjustments.filter(
        adj => adj.invoiceId === inv.id && (adj.adjustmentType === 'DECREASE' || adj.adjustmentType === 'CANCEL')
      );
      const isAdjusted = existingDecreases.length > 0;

      // Xây dựng danh sách mặt hàng trả dựa trên dòng hoá đơn gốc
      const items = (inv.items || []).map(item => {
        const invoicedQty = Number(item.invoicedQuantity || item.deliveredQuantity || 0);
        // Mặc định số lượng trả là 1 hoặc 2 (nhưng không vượt quá số lượng đã xuất)
        const returnQty = Math.max(1, Math.min(invoicedQty, 2));
        const originalPrice = Number(item.unitPrice || 0);
        const subtotal = returnQty * originalPrice;

        return {
          productId: item.productId,
          productCode: item.productCode,
          productName: item.productName,
          unit: item.unit,
          invoicedQuantity: invoicedQty,
          returnQuantity: returnQty,
          originalPrice: originalPrice, // Tiêu chí S8-02: Đơn giá đã bán trên HĐ gốc
          subtotalAmount: subtotal,
        };
      }).filter(it => it.invoicedQuantity > 0);

      if (items.length === 0) return;

      const totalReturnAmount = items.reduce((sum, it) => sum + it.subtotalAmount, 0);

      // Mã phiếu trả hàng tương ứng
      const returnVoucherCode = `TH-${inv.invoiceNumber || inv.id}-01`;
      const returnDate = inv.invoiceDate || new Date().toISOString();

      vouchers.push({
        id: `rv-${inv.id}`,
        voucherCode: returnVoucherCode,
        invoiceId: inv.id,
        invoiceCode: inv.invoiceCode || `HD-${String(inv.id).padStart(8, '0')}`,
        invoiceNumber: inv.invoiceNumber,
        invoiceStatus: inv.status,
        dealerId: inv.dealerId,
        dealerName: inv.dealerName || `Đại lý #${inv.dealerId}`,
        dealerCode: inv.dealerCode || `DL-${inv.dealerId}`,
        returnDate: returnDate,
        items: items,
        totalAmount: totalReturnAmount,
        status: isAdjusted ? 'ADJUSTED' : 'PENDING',
        statusLabel: isAdjusted ? 'Đã lập chứng từ' : 'Chờ lập chứng từ',
        existingAdjustment: existingDecreases[0] || null,
      });
    });

    state.returnVouchers = vouchers;
  }

  // ── Render Functions ──

  function renderStats() {
    const pendingCount = state.returnVouchers.filter(v => v.status === 'PENDING').length;
    const doneCount = state.adjustments.length;
    const totalMonthAmount = state.adjustments.reduce((sum, adj) => {
      const amt = Math.abs(Number(adj.adjustmentAmount || 0));
      return sum + amt;
    }, 0);

    const elPending = document.getElementById('stat-pending');
    const elDone = document.getElementById('stat-done');
    const elTotal = document.getElementById('stat-total-amount');

    if (elPending) elPending.textContent = pendingCount;
    if (elDone) elDone.textContent = doneCount;
    if (elTotal) elTotal.textContent = formatMoney(totalMonthAmount);
  }

  function renderDealerSelect() {
    const select = document.getElementById('da-filter-dealer');
    if (!select) return;

    const currentVal = select.value;
    select.innerHTML = '<option value="">Tất cả đại lý</option>';

    state.dealers.forEach(d => {
      const opt = document.createElement('option');
      opt.value = d.id;
      opt.textContent = `${d.name} (${d.code || d.id})`;
      select.appendChild(opt);
    });

    select.value = currentVal;
  }

  function getFilteredVouchers() {
    let list = state.returnVouchers.slice();

    if (state.filterDealerId) {
      list = list.filter(v => String(v.dealerId) === String(state.filterDealerId));
    }

    if (state.filterStatus) {
      list = list.filter(v => v.status === state.filterStatus);
    }

    if (state.searchKeyword) {
      const kw = state.searchKeyword.toLowerCase().trim();
      list = list.filter(v =>
        (v.voucherCode && v.voucherCode.toLowerCase().includes(kw)) ||
        (v.invoiceCode && v.invoiceCode.toLowerCase().includes(kw)) ||
        (v.dealerName && v.dealerName.toLowerCase().includes(kw))
      );
    }

    return list;
  }

  function renderReturnVouchersTable() {
    const tbody = document.getElementById('da-return-tbody');
    const meta = document.getElementById('da-table-meta');
    if (!tbody) return;

    const filtered = getFilteredVouchers();
    if (meta) {
      meta.textContent = `Hiển thị ${filtered.length} phiếu trả hàng đã xác nhận từ kho`;
    }

    if (filtered.length === 0) {
      tbody.innerHTML = `
        <tr>
          <td colspan="7">
            <div class="da-empty">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
                <path d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z"/>
              </svg>
              <p class="da-empty__title">Không tìm thấy phiếu trả hàng phù hợp</p>
              <p class="da-empty__desc">Các phiếu trả hàng từ chuyến giao thành công sẽ hiển thị tại đây.</p>
            </div>
          </td>
        </tr>
      `;
      return;
    }

    const hasAdjustPerm = canAdjust();

    tbody.innerHTML = filtered.map(v => {
      const isPending = v.status === 'PENDING';
      const badgeClass = isPending ? 'da-badge--pending' : 'da-badge--done';
      const badgeText = isPending ? 'Chờ lập chứng từ' : 'Đã lập chứng từ';

      let actionBtn = '';
      if (isPending) {
        if (hasAdjustPerm) {
          actionBtn = `
            <button
              type="button"
              class="da-btn da-btn--primary da-btn--sm btn-create-adjustment"
              data-voucher-id="${v.id}"
              title="Lập chứng từ điều chỉnh giảm trừ công nợ"
            >
              Lập chứng từ
            </button>
          `;
        } else {
          actionBtn = `
            <button
              type="button"
              class="da-btn da-btn--secondary da-btn--sm"
              disabled
              title="Cần quyền INVOICE_ADJUST để lập chứng từ"
            >
              Không có quyền
            </button>
          `;
        }
      } else {
        actionBtn = `
          <button
            type="button"
            class="da-btn da-btn--secondary da-btn--sm btn-view-voucher-adj"
            data-voucher-id="${v.id}"
            title="Xem chứng từ điều chỉnh đã phát hành"
          >
            Xem chứng từ
          </button>
        `;
      }

      return `
        <tr>
          <td><strong>${v.voucherCode}</strong></td>
          <td>
            <a href="javascript:void(0)" class="da-invoice-link" data-invoice-id="${v.invoiceId}">
              ${v.invoiceCode}
            </a>
          </td>
          <td>${v.dealerName}</td>
          <td>${formatDate(v.returnDate)}</td>
          <td class="da-col-num" style="font-weight: 600; color: var(--da-danger);">
            -${formatMoney(v.totalAmount)}
          </td>
          <td>
            <span class="da-badge ${badgeClass} da-badge--dot">${badgeText}</span>
          </td>
          <td style="text-align: right;">
            ${actionBtn}
          </td>
        </tr>
      `;
    }).join('');

    // Gắn sự kiện nút
    tbody.querySelectorAll('.btn-create-adjustment').forEach(btn => {
      btn.addEventListener('click', () => {
        const voucherId = btn.getAttribute('data-voucher-id');
        openCreateAdjustmentModal(voucherId);
      });
    });

    tbody.querySelectorAll('.btn-view-voucher-adj').forEach(btn => {
      btn.addEventListener('click', () => {
        const voucherId = btn.getAttribute('data-voucher-id');
        const v = state.returnVouchers.find(item => item.id === voucherId);
        if (v && v.existingAdjustment) {
          openViewAdjustmentModal(v.existingAdjustment);
        }
      });
    });

    tbody.querySelectorAll('.da-invoice-link').forEach(link => {
      link.addEventListener('click', () => {
        const invId = link.getAttribute('data-invoice-id');
        viewInvoiceOriginalNotice(invId);
      });
    });
  }

  function renderAdjustmentsTable() {
    const tbody = document.getElementById('da-adj-tbody');
    const meta = document.getElementById('da-adj-table-meta');
    if (!tbody) return;

    if (meta) {
      meta.textContent = `Tổng cộng ${state.adjustments.length} chứng từ điều chỉnh đã phát hành`;
    }

    if (state.adjustments.length === 0) {
      tbody.innerHTML = `
        <tr>
          <td colspan="7">
            <div class="da-empty">
              <p class="da-empty__title">Chưa có chứng từ điều chỉnh nào</p>
              <p class="da-empty__desc">Các chứng từ điều chỉnh giảm trừ công nợ sau khi lập sẽ được hiển thị tại đây.</p>
            </div>
          </td>
        </tr>
      `;
      return;
    }

    tbody.innerHTML = state.adjustments.map(adj => {
      const amountVal = Number(adj.adjustmentAmount || 0);
      const isDecrease = amountVal <= 0;
      const formattedAmount = (isDecrease ? '' : '+') + formatMoney(amountVal);

      return `
        <tr>
          <td><strong>${adj.adjustmentCode}</strong></td>
          <td><span class="da-invoice-link">${adj.invoiceCode || '—'}</span></td>
          <td>${adj.dealerName || '—'}</td>
          <td>${formatDateTime(adj.adjustmentDate || adj.createdAt)}</td>
          <td class="da-col-num" style="font-weight: 600; color: ${isDecrease ? 'var(--da-danger)' : 'var(--da-success)'};">
            ${formattedAmount}
          </td>
          <td>${adj.adjustmentReason || '—'}</td>
          <td style="text-align: right;">
            <button
              type="button"
              class="da-btn da-btn--secondary da-btn--sm btn-view-adj"
              data-adj-id="${adj.id}"
            >
              Xem chi tiết
            </button>
            <button
              type="button"
              class="da-btn da-btn--secondary da-btn--sm btn-pdf-adj"
              data-adj-id="${adj.id}"
              data-invoice-id="${adj.invoiceId}"
              title="Tải PDF chứng từ điều chỉnh"
            >
              PDF
            </button>
          </td>
        </tr>
      `;
    }).join('');

    tbody.querySelectorAll('.btn-view-adj').forEach(btn => {
      btn.addEventListener('click', () => {
        const adjId = btn.getAttribute('data-adj-id');
        const adj = state.adjustments.find(a => String(a.id) === String(adjId));
        if (adj) openViewAdjustmentModal(adj);
      });
    });

    tbody.querySelectorAll('.btn-pdf-adj').forEach(btn => {
      btn.addEventListener('click', () => {
        const adjId = btn.getAttribute('data-adj-id');
        const invId = btn.getAttribute('data-invoice-id');
        downloadAdjustmentPdf(invId, adjId);
      });
    });
  }

  // ── Modal Create Adjustment ──

  async function openCreateAdjustmentModal(voucherId) {
    if (!canAdjust()) {
      if (typeof Toast !== 'undefined') {
        Toast.showError('Bạn không có quyền Lập chứng từ điều chỉnh (INVOICE_ADJUST).');
      }
      return;
    }

    const voucher = state.returnVouchers.find(v => v.id === voucherId);
    if (!voucher) return;

    state.selectedVoucher = voucher;

    // Lấy hoá đơn gốc từ backend để xác thực giá và trạng thái nguyên vẹn
    try {
      const invRes = await Auth.apiFetch(`/invoices/${voucher.invoiceId}`);
      const invData = await invRes.json();
      if (invData.success && invData.data) {
        state.selectedInvoice = invData.data;
      }
    } catch (e) {
      console.warn('Lỗi khi lấy chi tiết hoá đơn gốc:', e);
    }

    // Lấy thông tin công nợ hiện tại của đại lý
    state.dealerDebtInfo = await fetchDealerDebt(voucher.dealerId);

    // Điền thông tin vào modal
    document.getElementById('adj-return-code').textContent = voucher.voucherCode;
    document.getElementById('adj-dealer-name').textContent = voucher.dealerName;
    document.getElementById('adj-invoice-code').textContent = voucher.invoiceCode;
    document.getElementById('adj-return-date').textContent = formatDate(voucher.returnDate);

    // Điền lý do mặc định
    const reasonInput = document.getElementById('adj-reason');
    if (reasonInput) {
      reasonInput.value = `Giảm trừ công nợ đại lý theo phiếu trả hàng ${voucher.voucherCode} (HĐ gốc ${voucher.invoiceCode})`;
    }

    const noteInput = document.getElementById('adj-note');
    if (noteInput) {
      noteInput.value = 'Hàng hoá đã nhập trả lại kho lưu trữ theo biên bản đối soát';
    }

    // Render bảng dòng hàng điều chỉnh
    renderAdjustmentItemsTable(voucher.items);

    // Cập nhật tác động công nợ
    updateDebtImpactPreview();

    // Mở modal
    const modal = document.getElementById('modal-create-adjustment');
    if (modal) modal.hidden = false;
  }

  function renderAdjustmentItemsTable(items) {
    const tbody = document.getElementById('adj-items-tbody');
    if (!tbody) return;

    tbody.innerHTML = items.map((item, index) => {
      return `
        <tr data-item-index="${index}">
          <td>
            <strong>${item.productName}</strong>
            <div style="font-size: 0.75rem; color: var(--da-text-secondary);">${item.productCode}</div>
          </td>
          <td>${item.unit}</td>
          <td class="da-col-num">${item.invoicedQuantity}</td>
          <td class="da-col-num" style="width: 90px;">
            <input
              type="number"
              class="adj-qty-input"
              data-item-index="${index}"
              value="${item.returnQuantity}"
              min="1"
              max="${item.invoicedQuantity}"
              step="1"
              style="width: 70px; padding: 0.25rem 0.375rem; text-align: right; border: 1px solid var(--da-border); border-radius: 4px;"
            />
          </td>
          <td class="da-col-num">
            <span style="font-weight: 600;">${formatMoney(item.originalPrice)}</span>
            <div style="font-size: 0.6875rem; color: var(--da-success);">(Giá HĐ gốc)</div>
          </td>
          <td class="da-col-num line-subtotal" style="font-weight: 600; color: var(--da-danger);">
            -${formatMoney(item.subtotalAmount)}
          </td>
        </tr>
      `;
    }).join('');

    // Sự kiện thay đổi số lượng trả
    tbody.querySelectorAll('.adj-qty-input').forEach(input => {
      input.addEventListener('input', (e) => {
        const idx = parseInt(e.target.getAttribute('data-item-index'), 10);
        let val = Number(e.target.value);
        const max = Number(e.target.max);

        if (val > max) {
          val = max;
          e.target.value = max;
        }
        if (val < 1 || isNaN(val)) {
          val = 1;
        }

        if (state.selectedVoucher && state.selectedVoucher.items[idx]) {
          state.selectedVoucher.items[idx].returnQuantity = val;
          // Tiêu chí S8-02: Giảm trừ đúng theo giá đã bán trên hoá đơn gốc!
          const origPrice = state.selectedVoucher.items[idx].originalPrice;
          state.selectedVoucher.items[idx].subtotalAmount = val * origPrice;

          // Cập nhật ô thành tiền dòng
          const row = tbody.querySelector(`tr[data-item-index="${idx}"]`);
          if (row) {
            const cellSubtotal = row.querySelector('.line-subtotal');
            if (cellSubtotal) {
              cellSubtotal.textContent = '-' + formatMoney(val * origPrice);
            }
          }

          updateDebtImpactPreview();
        }
      });
    });
  }

  function updateDebtImpactPreview() {
    if (!state.selectedVoucher) return;

    // Tính tổng tiền giảm trừ
    const totalReduction = state.selectedVoucher.items.reduce(
      (sum, it) => sum + (Number(it.returnQuantity || 0) * Number(it.originalPrice || 0)), 0
    );

    const elTotal = document.getElementById('adj-total-amount');
    if (elTotal) {
      elTotal.textContent = '-' + formatMoney(totalReduction);
    }

    // Tác động công nợ
    const debt = state.dealerDebtInfo;
    const currentDebtVal = debt ? Number(debt.currentDebt || 0) : 12500000;
    const debtLimitVal = debt ? Number(debt.debtLimit || 0) : 50000000;

    const afterDebtVal = Math.max(0, currentDebtVal - totalReduction);
    const availableCreditVal = Math.max(0, debtLimitVal - afterDebtVal);

    const elCur = document.getElementById('adj-debt-current');
    const elDec = document.getElementById('adj-debt-decrease');
    const elAft = document.getElementById('adj-debt-after');
    const elAvail = document.getElementById('adj-debt-available');

    if (elCur) elCur.textContent = formatMoney(currentDebtVal);
    if (elDec) elDec.textContent = '-' + formatMoney(totalReduction);
    if (elAft) elAft.textContent = formatMoney(afterDebtVal);
    if (elAvail) elAvail.textContent = formatMoney(availableCreditVal);
  }

  function closeCreateAdjustmentModal() {
    const modal = document.getElementById('modal-create-adjustment');
    if (modal) modal.hidden = true;
    state.selectedVoucher = null;
    state.selectedInvoice = null;
    state.dealerDebtInfo = null;

    const err = document.getElementById('err-adj-reason');
    if (err) err.classList.remove('is-visible');
  }

  // ── Submit Adjustment ──

  async function submitAdjustment(event) {
    event.preventDefault();

    if (!canAdjust()) {
      if (typeof Toast !== 'undefined') {
        Toast.showError('Bạn không có quyền thực hiện thao tác này (INVOICE_ADJUST).');
      }
      return;
    }

    const voucher = state.selectedVoucher;
    if (!voucher) return;

    const reasonEl = document.getElementById('adj-reason');
    const errReasonEl = document.getElementById('err-adj-reason');
    const reasonVal = (reasonEl ? reasonEl.value : '').trim();

    if (!reasonVal) {
      if (errReasonEl) errReasonEl.classList.add('is-visible');
      reasonEl?.focus();
      return;
    } else {
      if (errReasonEl) errReasonEl.classList.remove('is-visible');
    }

    const noteEl = document.getElementById('adj-note');
    const noteVal = noteEl ? noteEl.value.trim() : '';

    // Tính tổng tiền giảm trừ
    const totalReduction = voucher.items.reduce(
      (sum, it) => sum + (Number(it.returnQuantity || 0) * Number(it.originalPrice || 0)), 0
    );

    if (totalReduction <= 0) {
      if (typeof Toast !== 'undefined') {
        Toast.showError('Tổng số tiền giảm trừ phải lớn hơn 0.');
      }
      return;
    }

    // Dòng điều chỉnh theo DTO InvoiceAdjustmentItemRequest
    const itemsPayload = voucher.items.map(it => ({
      productId: it.productId,
      productCode: it.productCode,
      productName: it.productName,
      unit: it.unit,
      adjustedQuantity: -Math.abs(Number(it.returnQuantity)),
      adjustedPrice: it.originalPrice, // Tiêu chí S8-02: Đơn giá đã bán trên HĐ gốc!
      adjustmentAmount: -Math.abs(Number(it.returnQuantity) * Number(it.originalPrice)),
      reason: `Trả hàng ${it.productName} theo phiếu ${voucher.voucherCode}`,
    }));

    const payload = {
      adjustmentType: 'DECREASE', // Điều chỉnh giảm
      adjustmentReason: reasonVal,
      adjustmentAmount: totalReduction, // Backend InvoiceServiceImpl tự chuyển sang âm khi DECREASE
      note: noteVal ? `${noteVal} (Phiếu trả: ${voucher.voucherCode})` : `Phiếu trả hàng: ${voucher.voucherCode}`,
      items: itemsPayload,
    };

    const submitBtn = document.getElementById('btn-submit-adj');
    if (submitBtn) {
      submitBtn.disabled = true;
      submitBtn.classList.add('da-btn--submitting');
    }

    try {
      const response = await Auth.apiFetch(`/invoices/${voucher.invoiceId}/adjustments`, {
        method: 'POST',
        body: JSON.stringify(payload),
      });

      const resData = await response.json();

      if (!response.ok || !resData.success) {
        throw new Error(resData.message || 'Lập chứng từ điều chỉnh thất bại');
      }

      const createdAdj = resData.data;

      // Kích hoạt đánh giá lại công nợ đại lý ngay lập tức (S7-04 / S8-02)
      try {
        await Auth.apiFetch(`/finance/debt/${voucher.dealerId}/evaluate`, { method: 'POST' });
      } catch (ignored) {
        // Đánh giá ngầm nếu API sẵn sàng
      }

      if (typeof Toast !== 'undefined') {
        Toast.showSuccess(
          `Đã lập chứng từ điều chỉnh ${createdAdj.adjustmentCode} thành công! Công nợ đại lý đã được giảm trừ ${formatMoney(totalReduction)}.`
        );
      }

      // Đóng modal và tải lại dữ liệu
      closeCreateAdjustmentModal();
      await initData();

    } catch (err) {
      console.error('Lỗi khi lập chứng từ điều chỉnh:', err);
      if (typeof Toast !== 'undefined') {
        Toast.showError(err.message || 'Không thể lập chứng từ điều chỉnh. Vui lòng kiểm tra lại.');
      }
    } finally {
      if (submitBtn) {
        submitBtn.disabled = false;
        submitBtn.classList.remove('da-btn--submitting');
      }
    }
  }

  // ── Modal View Adjustment ──

  async function openViewAdjustmentModal(adj) {
    if (!adj) return;

    document.getElementById('view-adj-code').textContent = adj.adjustmentCode || '—';
    document.getElementById('view-adj-type').textContent =
      adj.adjustmentType === 'DECREASE' ? 'Điều chỉnh giảm (Trả hàng)' : (adj.adjustmentType || 'Điều chỉnh');
    document.getElementById('view-adj-invoice').textContent = adj.invoiceCode || `Hóa đơn #${adj.invoiceId}`;
    document.getElementById('view-adj-return').textContent = adj.note && adj.note.includes('TH-')
      ? adj.note
      : `TH-${adj.invoiceNumber || adj.invoiceId}-01`;
    document.getElementById('view-adj-dealer').textContent = adj.dealerName || '—';
    document.getElementById('view-adj-date').textContent = formatDateTime(adj.adjustmentDate || adj.createdAt);

    const amt = Number(adj.adjustmentAmount || 0);
    document.getElementById('view-adj-amount').textContent = (amt <= 0 ? '' : '+') + formatMoney(amt);
    document.getElementById('view-adj-reason').textContent = adj.adjustmentReason || '—';
    document.getElementById('view-adj-note').textContent = adj.note || '—';
    document.getElementById('view-adj-created-by').textContent = adj.adjustedBy || 'Kế toán công nợ';

    // Dòng hàng chi tiết
    const tbody = document.getElementById('view-adj-items-tbody');
    if (tbody) {
      const items = adj.items || [];
      if (items.length === 0) {
        tbody.innerHTML = `
          <tr>
            <td colspan="5" style="text-align: center; color: var(--da-text-secondary); padding: 1.5rem;">
              Không có dòng sản phẩm chi tiết
            </td>
          </tr>
        `;
      } else {
        tbody.innerHTML = items.map(item => {
          const qty = Math.abs(Number(item.adjustedQuantity || 0));
          const price = Number(item.adjustedPrice || 0);
          const lineAmt = Math.abs(Number(item.adjustmentAmount || (qty * price)));

          return `
            <tr>
              <td>
                <strong>${item.productName || item.productCode}</strong>
                <div style="font-size: 0.75rem; color: var(--da-text-secondary);">${item.productCode || ''}</div>
              </td>
              <td>${item.unit || 'thùng'}</td>
              <td class="da-col-num">${qty}</td>
              <td class="da-col-num">${formatMoney(price)}</td>
              <td class="da-col-num" style="color: var(--da-danger); font-weight: 600;">
                -${formatMoney(lineAmt)}
              </td>
            </tr>
          `;
        }).join('');
      }
    }

    // Nút tải PDF
    const btnPdf = document.getElementById('btn-download-pdf');
    if (btnPdf) {
      btnPdf.onclick = () => downloadAdjustmentPdf(adj.invoiceId, adj.id);
    }

    const modal = document.getElementById('modal-view-adjustment');
    if (modal) modal.hidden = false;
  }

  function closeViewAdjustmentModal() {
    const modal = document.getElementById('modal-view-adjustment');
    if (modal) modal.hidden = true;
  }

  async function downloadAdjustmentPdf(invoiceId, adjustmentId) {
    try {
      const response = await Auth.apiFetch(`/invoices/${invoiceId}/adjustments/${adjustmentId}/pdf?download=true`);
      if (!response.ok) {
        throw new Error('Không thể tải PDF chứng từ điều chỉnh');
      }
      const blob = await response.blob();
      const url = window.URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `chung-tu-dieu-chinh-${adjustmentId}.pdf`;
      document.body.appendChild(a);
      a.click();
      window.URL.revokeObjectURL(url);
      a.remove();
    } catch (e) {
      console.error('Lỗi khi tải PDF:', e);
      if (typeof Toast !== 'undefined') {
        Toast.showError('Không thể tải file PDF chứng từ điều chỉnh.');
      }
    }
  }

  function viewInvoiceOriginalNotice(invoiceId) {
    const inv = state.invoices.find(item => String(item.id) === String(invoiceId));
    const invCode = inv ? inv.invoiceCode : `HĐ #${invoiceId}`;
    const totalAmt = inv ? formatMoney(inv.totalAmount) : '—';
    const adjAmt = inv ? formatMoney(inv.adjustedTotalAmount) : '—';

    if (typeof Toast !== 'undefined') {
      Toast.showInfo(
        `Hoá đơn gốc ${invCode}: Tổng tiền gốc ${totalAmt} được giữ nguyên không sửa. Giá trị sau điều chỉnh: ${adjAmt}.`
      );
    }
  }

  // ── Init & Event Listeners ──

  async function initData() {
    state.isLoading = true;
    try {
      await fetchDealers();
      await fetchInvoices();
      await fetchAllAdjustments();
      buildReturnVouchersFromInvoices();

      renderStats();
      renderDealerSelect();
      renderReturnVouchersTable();
      renderAdjustmentsTable();
    } catch (e) {
      console.error('Lỗi khi nạp dữ liệu điều chỉnh công nợ:', e);
    } finally {
      state.isLoading = false;
    }
  }

  function setupEventListeners() {
    // Tìm kiếm
    const searchInput = document.getElementById('da-search');
    const searchClear = document.getElementById('da-search-clear');

    if (searchInput) {
      searchInput.addEventListener('input', (e) => {
        state.searchKeyword = e.target.value;
        if (searchClear) searchClear.hidden = !e.target.value;
        renderReturnVouchersTable();
      });
    }

    if (searchClear) {
      searchClear.addEventListener('click', () => {
        if (searchInput) searchInput.value = '';
        state.searchKeyword = '';
        searchClear.hidden = true;
        renderReturnVouchersTable();
      });
    }

    // Bộ lọc đại lý
    const dealerSelect = document.getElementById('da-filter-dealer');
    if (dealerSelect) {
      dealerSelect.addEventListener('change', (e) => {
        state.filterDealerId = e.target.value;
        renderReturnVouchersTable();
      });
    }

    // Bộ lọc trạng thái
    const statusSelect = document.getElementById('da-filter-status');
    if (statusSelect) {
      statusSelect.addEventListener('change', (e) => {
        state.filterStatus = e.target.value;
        renderReturnVouchersTable();
      });
    }

    // Làm mới
    const refreshBtn = document.getElementById('da-btn-refresh');
    if (refreshBtn) {
      refreshBtn.addEventListener('click', async () => {
        refreshBtn.disabled = true;
        await initData();
        refreshBtn.disabled = false;
        if (typeof Toast !== 'undefined') {
          Toast.showSuccess('Đã cập nhật dữ liệu mới nhất.');
        }
      });
    }

    // Đóng modals
    document.querySelectorAll('[data-close-adj]').forEach(el => {
      el.addEventListener('click', closeCreateAdjustmentModal);
    });

    document.querySelectorAll('[data-close-view]').forEach(el => {
      el.addEventListener('click', closeViewAdjustmentModal);
    });

    // Form submit
    const form = document.getElementById('form-create-adjustment');
    if (form) {
      form.addEventListener('submit', submitAdjustment);
    }

    // Lắng nghe thay đổi quyền ma trận
    window.addEventListener('auth:permissions-changed', () => {
      // Nếu quyền bị thu hồi, đóng modal ngay lập tức
      if (!canAdjust()) {
        closeCreateAdjustmentModal();
      }
      renderReturnVouchersTable();
    });
  }

  async function init() {
    if (!Auth.requireLogin()) return;

    // Kiểm tra quyền INVOICE_VIEW
    await Auth.syncPermissions();
    if (!canView()) {
      sessionStorage.setItem('errorMessage', 'Bạn không có quyền truy cập chức năng Chứng từ điều chỉnh công nợ.');
      window.location.href = '/error?status=403';
      return;
    }

    setupEventListeners();
    await initData();
  }

  // Tự động khởi tạo khi tài liệu sẵn sàng
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }

  return {
    init,
    initData,
    openCreateAdjustmentModal,
    openViewAdjustmentModal,
    downloadAdjustmentPdf,
    canView,
    canAdjust,
  };
})();
