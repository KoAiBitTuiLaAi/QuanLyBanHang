/**
 * debt-payments.js
 * Logic xử lý cho chức năng Ghi nhận thanh toán và đối trừ công nợ (S7-02).
 * Phân quyền dựa trên Ma trận quyền (FINANCE_VIEW_DEBT, FINANCE_MANAGE, ROLE_ADMIN).
 * Tuyệt đối không lưu dữ liệu kinh doanh vào localStorage/sessionStorage.
 */
(() => {
  'use strict';

  const $ = (id) => document.getElementById(id);

  // Trạng thái ứng dụng cục bộ trong phiên trang
  const state = {
    dealers: [],
    currentPage: 0,
    pageSize: 15,
    totalPages: 1,
    totalElements: 0,
    selectedDealerId: '',
    selectedType: '',
    payments: [],
    currentDetailPayment: null,
    currentReceiptPayment: null,
    // Tab đang hoạt động: 'payments' hoặc 'invoices'
    activeTab: 'payments',
    // Dữ liệu khi tạo phiếu thu
    create: {
      dealerId: null,
      currentDebt: 0,
      unpaidInvoices: [],
      fifoPreview: null,
      mode: 'fifo', // 'fifo' hoặc 'manual'
    },
    // Dữ liệu khi xem hoá đơn đại lý
    invoices: {
      dealerId: null,
      status: '',
      list: [],
    },
  };

  /**
   * Escape HTML chống XSS
   */
  function escapeHtml(str) {
    if (str === null || str === undefined) return '';
    return String(str)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#039;');
  }

  /**
   * Đồng bộ phân quyền giao diện (Permission Matrix)
   */
  function syncPermissions() {
    const user = Auth.getUser();
    const canView = DebtPaymentsUtils.canViewDebtPayments(user);
    const canManage = DebtPaymentsUtils.canManageDebtPayments(user);

    const accessDenied = $('dp-access-denied');
    const manageNotice = $('dp-manage-notice');
    const contentArea = $('dp-content-area');
    const btnCreate = $('dp-btn-create-payment');

    if (!canView) {
      if (accessDenied) accessDenied.hidden = false;
      if (contentArea) contentArea.hidden = true;
      if (btnCreate) btnCreate.hidden = true;
      if (manageNotice) manageNotice.hidden = true;
      closeAllModals();
      return false;
    }

    if (accessDenied) accessDenied.hidden = true;
    if (contentArea) contentArea.hidden = false;

    if (btnCreate) {
      btnCreate.hidden = !canManage;
    }

    if (manageNotice) {
      manageNotice.hidden = canManage;
    }

    // Nếu người dùng mất quyền FINANCE_MANAGE thì đóng các modal tạo/sửa
    if (!canManage) {
      const createModal = $('modal-payment-create');
      const adjustModal = $('modal-adjustment-create');
      if (createModal && !createModal.hidden) createModal.hidden = true;
      if (adjustModal && !adjustModal.hidden) adjustModal.hidden = true;
      const btnAdjFromDetail = $('btn-open-adjust-from-detail');
      if (btnAdjFromDetail) btnAdjFromDetail.hidden = true;
    }

    const canPrint = DebtPaymentsUtils.canPrintReceipt(user);
    const receiptModal = $('modal-receipt-print');
    if (!canPrint && receiptModal && !receiptModal.hidden) {
      receiptModal.hidden = true;
    }

    return true;
  }

  /**
   * Hiển thị thông báo
   */
  function showGlobalMessage(type, message) {
    const errorEl = $('dp-global-error');
    const successEl = $('dp-global-success');
    if (!errorEl || !successEl) return;

    if (type === 'error') {
      errorEl.textContent = message;
      errorEl.hidden = false;
      successEl.hidden = true;
    } else if (type === 'success') {
      successEl.textContent = message;
      successEl.hidden = false;
      errorEl.hidden = true;
      setTimeout(() => {
        successEl.hidden = true;
      }, 5000);
    } else {
      errorEl.hidden = true;
      successEl.hidden = true;
    }
  }

  /**
   * Đóng tất cả các modal
   */
  function closeAllModals() {
    ['modal-payment-create', 'modal-payment-detail', 'modal-adjustment-create', 'modal-order-settlements', 'modal-receipt-print'].forEach((id) => {
      const el = $(id);
      if (el) el.hidden = true;
    });
  }

  /**
   * Tải danh sách đại lý cho các dropdown
   */
  async function loadDealers() {
    try {
      let dealerList = [];
      try {
        const res = await Auth.apiFetch('/dealers?size=200');
        const json = await res.json().catch(() => null);
        if (res.ok && json?.success && json?.data?.content && Array.isArray(json.data.content)) {
          dealerList = json.data.content.map((d) => ({
            id: d.id,
            code: d.code || '',
            name: d.name || d.customerName || `Đại lý #${d.id}`,
            debt: d.currentDebt || 0,
          }));
        } else if (res.ok && json?.success && Array.isArray(json.data)) {
          dealerList = json.data.map((d) => ({
            id: d.id,
            code: d.code || '',
            name: d.name || d.customerName || `Đại lý #${d.id}`,
            debt: d.currentDebt || 0,
          }));
        }
      } catch (err) {
        // Fallback
      }

      if (!dealerList.length) {
        const res2 = await Auth.apiFetch('/finance/debt');
        const json2 = await res2.json().catch(() => null);
        if (res2.ok && json2?.success && Array.isArray(json2.data)) {
          dealerList = json2.data.map((d) => ({
            id: d.dealerId,
            code: d.dealerCode || '',
            name: d.customerName || `Đại lý #${d.dealerId}`,
            debt: d.currentDebt || 0,
          }));
        }
      }

      state.dealers = dealerList;

      // Đổ vào các select
      const filterSelect = $('filter-dealer-select');
      const createSelect = $('create-dealer-select');
      const invoicesSelect = $('invoices-dealer-select');

      const optionsHtml = '<option value="">Tất cả đại lý</option>' +
        dealerList.map((d) => `<option value="${d.id}">${escapeHtml(d.code ? `${d.code} - ${d.name}` : d.name)}</option>`).join('');

      const requiredOptionsHtml = '<option value="">-- Chọn đại lý --</option>' +
        dealerList.map((d) => `<option value="${d.id}">${escapeHtml(d.code ? `${d.code} - ${d.name}` : d.name)}</option>`).join('');

      if (filterSelect) filterSelect.innerHTML = optionsHtml;
      if (createSelect) createSelect.innerHTML = requiredOptionsHtml;
      if (invoicesSelect) invoicesSelect.innerHTML = requiredOptionsHtml;
    } catch (e) {
      console.error('Lỗi khi tải danh sách đại lý:', e);
    }
  }

  /**
   * Tải danh sách phiếu thu tiền & bút toán điều chỉnh (S7-02 / S7-04)
   */
  async function loadPayments(page = 0) {
    if (!syncPermissions()) return;

    state.currentPage = page;
    const tbody = $('payments-tbody');
    if (tbody) {
      tbody.innerHTML = '<tr><td colspan="8" style="text-align: center; color: var(--ink-500); padding: 24px;">Đang tải danh sách phiếu thu tiền…</td></tr>';
    }

    try {
      let url = `/finance/debt-payments?page=${state.currentPage}&size=${state.pageSize}`;
      if (state.selectedDealerId) {
        url += `&dealerId=${encodeURIComponent(state.selectedDealerId)}`;
      }

      const res = await Auth.apiFetch(url);
      const json = await res.json().catch(() => null);

      if (!res.ok || !json?.success) {
        throw new Error(json?.message || `Lỗi tải danh sách phiếu thu (HTTP ${res.status})`);
      }

      const pageData = json.data || {};
      let content = pageData.content || [];

      // Lọc loại phiếu ở client nếu người dùng chọn
      if (state.selectedType) {
        content = content.filter((p) => (p.paymentType || 'PAYMENT') === state.selectedType);
      }

      state.payments = content;
      state.totalPages = pageData.totalPages || 1;
      state.totalElements = pageData.totalElements || 0;

      renderPaymentsTable();
    } catch (err) {
      if (tbody) {
        tbody.innerHTML = `<tr><td colspan="8" style="text-align: center; color: #dc2626; padding: 24px;">${escapeHtml(err.message)}</td></tr>`;
      }
      showGlobalMessage('error', err.message);
    }
  }

  /**
   * Render bảng danh sách phiếu thu
   */
  function renderPaymentsTable() {
    const tbody = $('payments-tbody');
    if (!tbody) return;

    if (!state.payments.length) {
      tbody.innerHTML = '<tr><td colspan="8" style="text-align: center; color: var(--ink-500); padding: 28px;">Không tìm thấy phiếu thu tiền nào.</td></tr>';
      updatePaginationControls();
      return;
    }

    const canManage = DebtPaymentsUtils.canManageDebtPayments(Auth.getUser());

    tbody.innerHTML = state.payments.map((p) => {
      const typeInfo = DebtPaymentsUtils.getPaymentTypeInfo(p.paymentType);
      const methodInfo = DebtPaymentsUtils.getPaymentMethodInfo(p.paymentMethod);
      const isAdjustment = p.paymentType === 'ADJUSTMENT';
      const amountColor = isAdjustment ? '#c2410c' : '#15803d';

      return `
        <tr>
          <td><strong style="color: var(--navy-900);">${escapeHtml(p.paymentCode || `PT-${p.id}`)}</strong></td>
          <td>${DebtPaymentsUtils.formatDate(p.paymentDate || p.createdAt)}</td>
          <td>
            <div>${escapeHtml(p.dealerName || `Đại lý #${p.dealerId}`)}</div>
            ${p.dealerCode ? `<span style="font-size: 0.75rem; color: var(--ink-500);">${escapeHtml(p.dealerCode)}</span>` : ''}
          </td>
          <td class="num" style="font-weight: 700; color: ${amountColor};">
            ${DebtPaymentsUtils.formatMoney(p.amount)}
          </td>
          <td><span class="badge ${methodInfo.badgeClass}">${escapeHtml(methodInfo.label)}</span></td>
          <td>${escapeHtml(p.recordedBy || '—')}</td>
          <td><span class="badge ${typeInfo.badgeClass}">${escapeHtml(typeInfo.label)}</span></td>
          <td style="text-align: right; white-space: nowrap;">
            <button class="btn btn--secondary btn--sm" data-action="view-detail" data-id="${p.id}" type="button">
              Chi tiết
            </button>
            <button class="btn btn--secondary btn--sm" data-action="print-receipt" data-id="${p.id}" type="button" style="color: var(--teal-deep, #0f766e); margin-left: 6px;">
              In / PDF
            </button>
            ${!isAdjustment && canManage ? `
              <button class="btn btn--secondary btn--sm" data-action="adjust-payment" data-id="${p.id}" type="button" style="color: #c2410c; margin-left: 6px;">
                Điều chỉnh
              </button>
            ` : ''}
          </td>
        </tr>
      `;
    }).join('');

    updatePaginationControls();
  }

  /**
   * Cập nhật nút phân trang
   */
  function updatePaginationControls() {
    const prevBtn = $('payments-btn-prev');
    const nextBtn = $('payments-btn-next');
    const pageInfo = $('payments-page-info');
    const countInfo = $('payments-count-info');

    if (prevBtn) prevBtn.disabled = state.currentPage <= 0;
    if (nextBtn) nextBtn.disabled = state.currentPage >= state.totalPages - 1;
    if (pageInfo) pageInfo.textContent = `Trang ${state.currentPage + 1} / ${Math.max(1, state.totalPages)}`;
    if (countInfo) countInfo.textContent = `Tổng cộng: ${state.totalElements} phiếu thu`;
  }

  /**
   * Mở modal chi tiết phiếu thu
   */
  async function openPaymentDetail(id) {
    if (!syncPermissions()) return;

    const modal = $('modal-payment-detail');
    const infoContainer = $('payment-detail-info');
    const allocationsTbody = $('payment-allocations-tbody');
    const adjustBanner = $('payment-adjustment-banner');
    const btnAdjust = $('btn-open-adjust-from-detail');

    if (allocationsTbody) {
      allocationsTbody.innerHTML = '<tr><td colspan="7" style="text-align:center; padding:16px;">Đang tải chi tiết đối trừ…</td></tr>';
    }
    if (modal) modal.hidden = false;

    try {
      const res = await Auth.apiFetch(`/finance/debt-payments/${id}`);
      const json = await res.json().catch(() => null);

      if (!res.ok || !json?.success) {
        throw new Error(json?.message || 'Không thể tải chi tiết phiếu thu');
      }

      const p = json.data;
      state.currentDetailPayment = p;
      state.currentReceiptPayment = p;

      const typeInfo = DebtPaymentsUtils.getPaymentTypeInfo(p.paymentType);
      const methodInfo = DebtPaymentsUtils.getPaymentMethodInfo(p.paymentMethod);
      const isAdjustment = p.paymentType === 'ADJUSTMENT';

      if (infoContainer) {
        infoContainer.innerHTML = `
          <div class="dp-stat-card">
            <div class="dp-stat-card__label">Mã phiếu</div>
            <div class="dp-stat-card__val" style="font-size: 1.125rem;">${escapeHtml(p.paymentCode || `PT-${p.id}`)}</div>
            <div style="margin-top: 4px;"><span class="badge ${typeInfo.badgeClass}">${escapeHtml(typeInfo.label)}</span></div>
          </div>
          <div class="dp-stat-card">
            <div class="dp-stat-card__label">Số tiền thu</div>
            <div class="dp-stat-card__val ${isAdjustment ? 'dp-stat-card__val--danger' : 'dp-stat-card__val--success'}">
              ${DebtPaymentsUtils.formatMoney(p.amount)}
            </div>
            <div style="font-size: 0.75rem; color: var(--ink-500); margin-top: 4px;">
              Hình thức: <span class="badge ${methodInfo.badgeClass}">${escapeHtml(methodInfo.label)}</span>
            </div>
          </div>
          <div class="dp-stat-card">
            <div class="dp-stat-card__label">Đại lý</div>
            <div class="dp-stat-card__val" style="font-size: 1rem;">${escapeHtml(p.dealerName || `Đại lý #${p.dealerId}`)}</div>
            <div style="font-size: 0.75rem; color: var(--ink-500); margin-top: 4px;">${escapeHtml(p.dealerCode || '')}</div>
          </div>
          <div class="dp-stat-card">
            <div class="dp-stat-card__label">Thời gian &amp; Người thu</div>
            <div style="font-weight: 600; color: var(--ink-900); font-size: 0.875rem;">${DebtPaymentsUtils.formatDate(p.paymentDate)}</div>
            <div style="font-size: 0.75rem; color: var(--ink-500); margin-top: 4px;">Người thu: ${escapeHtml(p.recordedBy || '—')}</div>
          </div>
        `;
      }

      if (adjustBanner) {
        if (isAdjustment) {
          adjustBanner.innerHTML = `Bút toán điều chỉnh cho phiếu gốc: <strong>${escapeHtml(p.originalPaymentCode || `PT-${p.originalPaymentId}`)}</strong>. Lý do điều chỉnh: <em>${escapeHtml(p.adjustmentReason || '—')}</em>`;
          adjustBanner.hidden = false;
        } else {
          adjustBanner.hidden = true;
        }
      }

      const canManage = DebtPaymentsUtils.canManageDebtPayments(Auth.getUser());
      if (btnAdjust) {
        btnAdjust.hidden = isAdjustment || !canManage;
      }

      const canPrint = DebtPaymentsUtils.canPrintReceipt(Auth.getUser());
      const btnPrintDetail = $('btn-print-from-detail');
      const btnPdfDetail = $('btn-download-pdf-from-detail');
      if (btnPrintDetail) btnPrintDetail.hidden = !canPrint;
      if (btnPdfDetail) btnPdfDetail.hidden = !canPrint;

      // Render danh sách allocations
      const allocations = p.allocations || [];
      if (allocationsTbody) {
        if (!allocations.length) {
          allocationsTbody.innerHTML = '<tr><td colspan="7" style="text-align:center; padding:16px; color: var(--ink-500);">Không có chi tiết đối trừ hoá đơn (phiếu thu không phân bổ hoá đơn).</td></tr>';
        } else {
          allocationsTbody.innerHTML = allocations.map((a) => {
            const statusInfo = DebtPaymentsUtils.getInvoicePaymentStatusInfo(a.orderPaymentStatus);
            return `
              <tr>
                <td><strong>${escapeHtml(a.orderCode || `HĐ #${a.orderId}`)}</strong></td>
                <td class="num" style="font-weight: 700; color: var(--teal-deep);">
                  ${DebtPaymentsUtils.formatMoney(a.allocatedAmount)}
                </td>
                <td class="num">${DebtPaymentsUtils.formatMoney(a.orderTotalAmount)}</td>
                <td class="num">${DebtPaymentsUtils.formatMoney(a.orderPaidAmount)}</td>
                <td class="num" style="font-weight: 600; color: ${a.orderRemainingAmount > 0 ? '#dc2626' : '#15803d'};">
                  ${DebtPaymentsUtils.formatMoney(a.orderRemainingAmount)}
                </td>
                <td><span class="badge ${statusInfo.badgeClass}">${escapeHtml(a.orderPaymentStatusDisplayName || statusInfo.label)}</span></td>
                <td>${escapeHtml(a.note || '—')}</td>
              </tr>
            `;
          }).join('');
        }
      }
    } catch (err) {
      if (allocationsTbody) {
        allocationsTbody.innerHTML = `<tr><td colspan="7" style="text-align:center; color:#dc2626; padding:16px;">${escapeHtml(err.message)}</td></tr>`;
      }
    }
  }

  /**
   * Mở modal xem trước và in phiếu thu / tải PDF (S7-09).
   */
  async function openReceiptPrintModal(idOrPayment) {
    const user = Auth.getUser();
    if (!DebtPaymentsUtils.canPrintReceipt(user)) {
      showGlobalMessage('error', 'Bạn không có quyền xem hoặc in phiếu thu.');
      return;
    }

    let payment = null;
    if (typeof idOrPayment === 'object' && idOrPayment !== null) {
      payment = idOrPayment;
    } else {
      payment = state.payments.find((p) => String(p.id) === String(idOrPayment));
      if (!payment || !payment.allocations) {
        try {
          const res = await Auth.apiFetch(`/finance/debt-payments/${idOrPayment}`);
          const json = await res.json().catch(() => null);
          if (res.ok && json?.success && json?.data) {
            payment = json.data;
          }
        } catch (e) {
          console.warn('Không tải được allocations chi tiết, dùng thông tin phiếu thu sẵn có:', e);
        }
      }
    }

    if (!payment) {
      showGlobalMessage('error', 'Không tìm thấy thông tin phiếu thu để in.');
      return;
    }

    state.currentReceiptPayment = payment;

    const printArea = $('dp-receipt-print-area');
    if (printArea) {
      printArea.innerHTML = DebtPaymentsUtils.renderReceiptHtml(payment);
    }

    const modal = $('modal-receipt-print');
    if (modal) {
      modal.hidden = false;
    }
  }

  /**
   * Tạo và tải file PDF phiếu thu về máy (S7-09).
   */
  function downloadReceiptPdf(payment) {
    const target = payment || state.currentReceiptPayment || state.currentDetailPayment;
    if (!target) {
      showGlobalMessage('error', 'Chưa có thông tin phiếu thu để tải PDF.');
      return;
    }

    const user = Auth.getUser();
    if (!DebtPaymentsUtils.canPrintReceipt(user)) {
      showGlobalMessage('error', 'Bạn không có quyền tải PDF phiếu thu.');
      return;
    }

    try {
      const blob = DebtPaymentsUtils.generateReceiptPdfBlob(target);
      if (!blob) {
        throw new Error('Không thể tạo nội dung file PDF.');
      }
      const pCode = target.paymentCode || `PT-${target.id || 'NEW'}`;
      const fileName = `phieu-thu-${pCode}.pdf`;

      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = fileName;
      document.body.appendChild(link);
      link.click();
      document.body.removeChild(link);
      setTimeout(() => URL.revokeObjectURL(url), 10000);
    } catch (err) {
      showGlobalMessage('error', 'Lỗi khi xuất file PDF: ' + err.message);
    }
  }

  /**
   * Mở modal lập phiếu thu tiền mới
   */
  async function openCreatePaymentModal() {
    if (!DebtPaymentsUtils.canManageDebtPayments(Auth.getUser())) {
      showGlobalMessage('error', 'Bạn cần quyền FINANCE_MANAGE để lập phiếu thu tiền.');
      return;
    }

    const modal = $('modal-payment-create');
    const form = $('form-payment-create');
    const errorEl = $('payment-create-error');
    const banner = $('dealer-debt-info-banner');

    if (form) form.reset();
    if (errorEl) errorEl.hidden = true;
    if (banner) banner.hidden = true;

    // Reset create state
    state.create.dealerId = null;
    state.create.currentDebt = 0;
    state.create.unpaidInvoices = [];
    state.create.fifoPreview = null;
    state.create.mode = 'fifo';

    const radioFifo = document.querySelector('input[name="allocationMode"][value="fifo"]');
    if (radioFifo) radioFifo.checked = true;
    toggleAllocationMode('fifo');

    // Mặc định ngày hôm nay
    const dateInput = $('create-payment-date');
    if (dateInput) {
      dateInput.value = new Date().toISOString().split('T')[0];
    }

    // Mặc định người thu
    const recordedByInput = $('create-recorded-by');
    if (recordedByInput) {
      const user = Auth.getUser();
      recordedByInput.value = user?.fullName || user?.username || '';
    }

    const amountFormatted = $('create-amount-formatted');
    if (amountFormatted) amountFormatted.textContent = '';

    if (modal) modal.hidden = false;
  }

  /**
   * Chuyển đổi giữa chế độ FIFO và Chỉ định hoá đơn thủ công
   */
  function toggleAllocationMode(mode) {
    state.create.mode = mode;
    const fifoSection = $('fifo-mode-section');
    const manualSection = $('manual-mode-section');

    if (mode === 'fifo') {
      if (fifoSection) fifoSection.hidden = false;
      if (manualSection) manualSection.hidden = true;
    } else {
      if (fifoSection) fifoSection.hidden = true;
      if (manualSection) manualSection.hidden = false;
      updateManualAllocationSummary();
    }
  }

  /**
   * Xử lý khi chọn đại lý trong form tạo phiếu thu
   */
  async function handleDealerChangeForCreate(dealerId) {
    state.create.dealerId = dealerId;
    const banner = $('dealer-debt-info-banner');
    const debtText = $('dealer-current-debt-text');
    const manualTbody = $('manual-invoices-tbody');
    const fifoPreviewContainer = $('fifo-preview-container');

    if (fifoPreviewContainer) fifoPreviewContainer.hidden = true;

    if (!dealerId) {
      if (banner) banner.hidden = true;
      state.create.currentDebt = 0;
      state.create.unpaidInvoices = [];
      if (manualTbody) {
        manualTbody.innerHTML = '<tr><td colspan="6" style="text-align: center; color: var(--ink-500); padding: 16px;">Vui lòng chọn đại lý trước để tải danh sách hoá đơn còn nợ.</td></tr>';
      }
      return;
    }

    if (manualTbody) {
      manualTbody.innerHTML = '<tr><td colspan="6" style="text-align: center; color: var(--ink-500); padding: 16px;">Đang tải danh sách hoá đơn còn nợ…</td></tr>';
    }

    try {
      // 1. Tải danh sách hoá đơn còn nợ từ API /finance/dealers/{dealerId}/unpaid-invoices
      const res = await Auth.apiFetch(`/finance/dealers/${dealerId}/unpaid-invoices`);
      const json = await res.json().catch(() => null);

      if (!res.ok || !json?.success) {
        throw new Error(json?.message || 'Không thể tải hoá đơn nợ của đại lý');
      }

      const invoices = json.data || [];
      state.create.unpaidInvoices = invoices;

      // Tính tổng nợ còn lại từ các hoá đơn
      let totalDebt = 0;
      for (const inv of invoices) {
        totalDebt += (inv.remainingAmount || 0);
      }
      state.create.currentDebt = totalDebt;

      if (banner && debtText) {
        debtText.textContent = DebtPaymentsUtils.formatMoney(totalDebt);
        banner.hidden = false;
      }

      // Render bảng thủ công
      renderManualInvoicesTable();
    } catch (err) {
      if (manualTbody) {
        manualTbody.innerHTML = `<tr><td colspan="6" style="text-align: center; color: #dc2626; padding: 16px;">${escapeHtml(err.message)}</td></tr>`;
      }
    }
  }

  /**
   * Render bảng nhập số tiền cấn trừ thủ công
   */
  function renderManualInvoicesTable() {
    const tbody = $('manual-invoices-tbody');
    if (!tbody) return;

    const invoices = state.create.unpaidInvoices || [];
    if (!invoices.length) {
      tbody.innerHTML = '<tr><td colspan="6" style="text-align: center; color: var(--ink-500); padding: 16px;">Đại lý này hiện không còn hoá đơn nợ nào cần thanh toán.</td></tr>';
      updateManualAllocationSummary();
      return;
    }

    tbody.innerHTML = invoices.map((inv, idx) => `
      <tr data-order-id="${inv.orderId}">
        <td>
          <strong>${escapeHtml(inv.orderCode || `Đơn #${inv.orderId}`)}</strong>
          ${inv.isOverdue ? '<span class="badge badge--overdue" style="margin-left: 4px;">Quá hạn</span>' : ''}
        </td>
        <td>${DebtPaymentsUtils.formatDate(inv.recordedAt || inv.invoiceDate)}</td>
        <td class="num" style="font-weight: 600; color: #dc2626;">
          ${DebtPaymentsUtils.formatMoney(inv.remainingAmount)}
        </td>
        <td>
          <input type="number" class="manual-alloc-input" data-order-id="${inv.orderId}" data-max="${inv.remainingAmount}" min="0" max="${inv.remainingAmount}" step="1000" placeholder="0" style="width: 100%; height: 32px; padding: 0 8px; font-size: 0.8125rem;">
        </td>
        <td>
          <button class="btn btn--secondary btn--sm btn-fill-all" data-order-id="${inv.orderId}" data-max="${inv.remainingAmount}" type="button" style="padding: 0 6px;">Trả hết</button>
        </td>
        <td>
          <input type="text" class="manual-alloc-note" data-order-id="${inv.orderId}" maxlength="255" placeholder="Ghi chú" style="width: 100%; height: 32px; padding: 0 8px; font-size: 0.8125rem;">
        </td>
      </tr>
    `).join('');

    // Gắn sự kiện tính toán tổng khi nhập tiền cấn trừ
    tbody.querySelectorAll('.manual-alloc-input').forEach((input) => {
      input.addEventListener('input', () => {
        const val = Number(input.value || 0);
        const max = Number(input.dataset.max || 0);
        if (val > max) {
          input.style.borderColor = '#dc2626';
        } else {
          input.style.borderColor = '';
        }
        updateManualAllocationSummary();
      });
    });

    tbody.querySelectorAll('.btn-fill-all').forEach((btn) => {
      btn.addEventListener('click', () => {
        const orderId = btn.dataset.orderId;
        const max = btn.dataset.max;
        const input = tbody.querySelector(`.manual-alloc-input[data-order-id="${orderId}"]`);
        if (input) {
          input.value = max;
          input.dispatchEvent(new Event('input'));
        }
      });
    });

    updateManualAllocationSummary();
  }

  /**
   * Cập nhật dòng tóm tắt số tiền đã phân bổ
   */
  function updateManualAllocationSummary() {
    const summaryEl = $('manual-allocation-summary');
    if (!summaryEl) return;

    const amountInput = $('create-amount');
    const totalAmount = DebtPaymentsUtils.parseMoney(amountInput?.value);

    let totalAllocated = 0;
    document.querySelectorAll('.manual-alloc-input').forEach((inp) => {
      totalAllocated += DebtPaymentsUtils.parseMoney(inp.value);
    });

    const isMatch = Math.abs(totalAllocated - totalAmount) < 0.01 && totalAmount > 0;
    summaryEl.className = 'dp-allocation-summary' + (isMatch ? '' : ' mismatch');
    summaryEl.textContent = `Đã phân bổ: ${DebtPaymentsUtils.formatMoney(totalAllocated)} / Số tiền thu: ${DebtPaymentsUtils.formatMoney(totalAmount)}`;
  }

  /**
   * Xem trước đối trừ tự động FIFO
   */
  async function previewFifoAllocation() {
    const dealerId = state.create.dealerId;
    const amountVal = $('create-amount')?.value;
    const amount = DebtPaymentsUtils.parseMoney(amountVal);
    const errorEl = $('payment-create-error');
    const container = $('fifo-preview-container');
    const tbody = $('fifo-preview-tbody');

    if (errorEl) errorEl.hidden = true;

    if (!dealerId) {
      if (errorEl) {
        errorEl.textContent = 'Vui lòng chọn đại lý thanh toán trước khi xem trước đối trừ.';
        errorEl.hidden = false;
      }
      return;
    }

    if (!amount || amount <= 0) {
      if (errorEl) {
        errorEl.textContent = 'Vui lòng nhập số tiền thu lớn hơn 0 để xem trước đối trừ.';
        errorEl.hidden = false;
      }
      return;
    }

    if (tbody) {
      tbody.innerHTML = '<tr><td colspan="5" style="text-align: center; padding: 12px;">Đang tính toán xem trước đối trừ FIFO…</td></tr>';
    }
    if (container) container.hidden = false;

    try {
      const res = await Auth.apiFetch(`/finance/debt-payments/preview-allocation?dealerId=${dealerId}&amount=${amount}`, {
        method: 'POST',
      });
      const json = await res.json().catch(() => null);

      if (!res.ok || !json?.success) {
        throw new Error(json?.message || 'Không thể xem trước đối trừ FIFO');
      }

      const previewData = json.data;
      state.create.fifoPreview = previewData;

      const allocations = previewData.allocations || [];
      if (!allocations.length) {
        if (tbody) {
          tbody.innerHTML = '<tr><td colspan="5" style="text-align: center; color: var(--ink-500); padding: 12px;">Không có hoá đơn nào được cấn trừ (đại lý không có nợ).</td></tr>';
        }
        return;
      }

      if (tbody) {
        tbody.innerHTML = allocations.map((a) => {
          const statusInfo = DebtPaymentsUtils.getInvoicePaymentStatusInfo(a.orderPaymentStatus);
          return `
            <tr>
              <td><strong>${escapeHtml(a.orderCode || `HĐ #${a.orderId}`)}</strong></td>
              <td class="num">${DebtPaymentsUtils.formatMoney(a.orderTotalAmount)}</td>
              <td class="num" style="font-weight: 700; color: var(--teal-deep);">${DebtPaymentsUtils.formatMoney(a.allocatedAmount)}</td>
              <td class="num" style="font-weight: 600; color: ${a.orderRemainingAmount > 0 ? '#dc2626' : '#15803d'};">
                ${DebtPaymentsUtils.formatMoney(a.orderRemainingAmount)}
              </td>
              <td><span class="badge ${statusInfo.badgeClass}">${escapeHtml(a.orderPaymentStatusDisplayName || statusInfo.label)}</span></td>
            </tr>
          `;
        }).join('');
      }
    } catch (err) {
      if (tbody) {
        tbody.innerHTML = `<tr><td colspan="5" style="text-align: center; color: #dc2626; padding: 12px;">${escapeHtml(err.message)}</td></tr>`;
      }
    }
  }

  /**
   * Submit form tạo phiếu thu tiền
   */
  async function submitPaymentCreate(e) {
    e.preventDefault();
    const errorEl = $('payment-create-error');
    const submitBtn = $('btn-submit-payment');
    if (errorEl) errorEl.hidden = true;

    const dealerId = state.create.dealerId;
    const amountVal = $('create-amount')?.value;
    const amount = DebtPaymentsUtils.parseMoney(amountVal);
    const paymentMethod = $('create-payment-method')?.value;
    const paymentDate = $('create-payment-date')?.value;
    const recordedBy = $('create-recorded-by')?.value?.trim();
    const referenceCode = $('create-reference-code')?.value?.trim();
    const note = $('create-note')?.value?.trim();
    const autoAllocate = state.create.mode === 'fifo';

    const payload = {
      dealerId: dealerId ? Number(dealerId) : null,
      amount: amount,
      paymentMethod: paymentMethod,
      paymentDate: paymentDate,
      recordedBy: recordedBy || undefined,
      referenceCode: referenceCode || undefined,
      note: note || undefined,
      autoAllocate: autoAllocate,
    };

    if (!autoAllocate) {
      // Thu thập allocations từ các dòng nhập
      const allocations = [];
      document.querySelectorAll('#manual-invoices-tbody tr[data-order-id]').forEach((row) => {
        const orderId = Number(row.dataset.orderId);
        const allocInput = row.querySelector('.manual-alloc-input');
        const noteInput = row.querySelector('.manual-alloc-note');
        const allocAmount = DebtPaymentsUtils.parseMoney(allocInput?.value);
        const maxDebt = Number(allocInput?.dataset.max || 0);

        if (allocAmount > 0) {
          allocations.push({
            orderId: orderId,
            amount: allocAmount,
            orderRemainingAmount: maxDebt,
            note: noteInput?.value?.trim() || undefined,
          });
        }
      });
      payload.allocations = allocations;
    }

    // Validate payload bằng utils
    const validation = DebtPaymentsUtils.validatePaymentPayload(payload, state.create.currentDebt);
    if (!validation.isValid) {
      if (errorEl) {
        errorEl.innerHTML = validation.errors.map(err => `<div>${escapeHtml(err)}</div>`).join('');
        errorEl.hidden = false;
      }
      return;
    }

    // Gửi request
    if (submitBtn) {
      submitBtn.disabled = true;
      submitBtn.textContent = 'Đang ghi nhận…';
    }

    try {
      const res = await Auth.apiFetch('/finance/debt-payments', {
        method: 'POST',
        body: JSON.stringify(payload),
      });
      const json = await res.json().catch(() => null);

      if (!res.ok || !json?.success) {
        throw new Error(json?.message || `Lỗi ghi nhận thu tiền (HTTP ${res.status})`);
      }

      showGlobalMessage('success', json.message || 'Ghi nhận phiếu thu tiền và đối trừ công nợ thành công.');
      closeAllModals();

      // Tải lại danh sách
      await loadPayments(0);

      // S7-09: Tự động mở xem trước phiếu thu để kế toán in chứng từ ngay tại chỗ cho đại lý
      if (json.data && DebtPaymentsUtils.canPrintReceipt(Auth.getUser())) {
        openReceiptPrintModal(json.data);
      }
    } catch (err) {
      if (errorEl) {
        errorEl.textContent = err.message;
        errorEl.hidden = false;
      }
    } finally {
      if (submitBtn) {
        submitBtn.disabled = false;
        submitBtn.textContent = 'Xác nhận ghi thu tiền';
      }
    }
  }

  /**
   * Mở modal lập bút toán điều chỉnh cho một phiếu thu
   */
  function openAdjustmentModal(payment) {
    if (!DebtPaymentsUtils.canManageDebtPayments(Auth.getUser())) {
      showGlobalMessage('error', 'Bạn cần quyền FINANCE_MANAGE để lập bút toán điều chỉnh.');
      return;
    }

    state.currentDetailPayment = payment;

    const modal = $('modal-adjustment-create');
    const form = $('form-adjustment-create');
    const errorEl = $('adjustment-error');
    const origCode = $('adj-orig-code');
    const origAmount = $('adj-orig-amount');
    const typeSelect = $('adj-type-select');
    const amountGroup = $('adj-amount-group');
    const amountInput = $('adj-amount-input');
    const amountFormatted = $('adj-amount-formatted');
    const dateInput = $('adj-date-input');
    const recordedByInput = $('adj-recorded-by-input');

    if (form) form.reset();
    if (errorEl) errorEl.hidden = true;

    if (origCode) origCode.textContent = payment.paymentCode || `PT-${payment.id}`;
    if (origAmount) origAmount.textContent = DebtPaymentsUtils.formatMoney(payment.amount);

    if (typeSelect) typeSelect.value = 'REVERSAL';
    if (amountGroup) amountGroup.hidden = true;
    if (amountFormatted) amountFormatted.textContent = '';

    if (dateInput) dateInput.value = new Date().toISOString().split('T')[0];
    if (recordedByInput) {
      const user = Auth.getUser();
      recordedByInput.value = user?.fullName || user?.username || '';
    }

    if (modal) modal.hidden = false;
  }

  /**
   * Submit form lập bút toán điều chỉnh
   */
  async function submitAdjustmentCreate(e) {
    e.preventDefault();
    const payment = state.currentDetailPayment;
    if (!payment) return;

    const errorEl = $('adjustment-error');
    const submitBtn = $('btn-submit-adjust');
    if (errorEl) errorEl.hidden = true;

    const adjustmentType = $('adj-type-select')?.value;
    const reason = $('adj-reason-input')?.value?.trim();
    const adjustmentDate = $('adj-date-input')?.value;
    const recordedBy = $('adj-recorded-by-input')?.value?.trim();
    const note = $('adj-note-input')?.value?.trim();

    let adjustmentAmount = undefined;
    if (adjustmentType === 'PARTIAL_REVERSAL') {
      adjustmentAmount = DebtPaymentsUtils.parseMoney($('adj-amount-input')?.value);
    }

    const payload = {
      adjustmentType: adjustmentType,
      reason: reason,
      adjustmentAmount: adjustmentAmount,
      adjustmentDate: adjustmentDate || undefined,
      recordedBy: recordedBy || undefined,
      note: note || undefined,
    };

    const validation = DebtPaymentsUtils.validateAdjustmentPayload(payload, payment.amount);
    if (!validation.isValid) {
      if (errorEl) {
        errorEl.innerHTML = validation.errors.map(err => `<div>${escapeHtml(err)}</div>`).join('');
        errorEl.hidden = false;
      }
      return;
    }

    if (submitBtn) {
      submitBtn.disabled = true;
      submitBtn.textContent = 'Đang lập bút toán…';
    }

    try {
      const res = await Auth.apiFetch(`/finance/debt-payments/${payment.id}/adjust`, {
        method: 'POST',
        body: JSON.stringify(payload),
      });
      const json = await res.json().catch(() => null);

      if (!res.ok || !json?.success) {
        throw new Error(json?.message || `Lỗi lập bút toán điều chỉnh (HTTP ${res.status})`);
      }

      showGlobalMessage('success', json.message || 'Lập bút toán điều chỉnh thành công.');
      closeAllModals();

      // Tải lại danh sách
      await loadPayments(state.currentPage);
    } catch (err) {
      if (errorEl) {
        errorEl.textContent = err.message;
        errorEl.hidden = false;
      }
    } finally {
      if (submitBtn) {
        submitBtn.disabled = false;
        submitBtn.textContent = 'Xác nhận lập bút toán';
      }
    }
  }

  /**
   * Tra cứu danh sách hoá đơn theo đại lý (Tab 2)
   */
  async function loadDealerInvoices() {
    if (!syncPermissions()) return;

    const dealerSelect = $('invoices-dealer-select');
    const statusSelect = $('invoices-status-select');
    const tbody = $('invoices-tbody');
    const summaryCards = $('invoices-summary-cards');

    const dealerId = dealerSelect?.value;
    const status = statusSelect?.value;

    if (!dealerId) {
      showGlobalMessage('error', 'Vui lòng chọn đại lý để tra cứu hoá đơn.');
      return;
    }

    state.invoices.dealerId = dealerId;
    state.invoices.status = status;

    if (tbody) {
      tbody.innerHTML = '<tr><td colspan="8" style="text-align: center; color: var(--ink-500); padding: 24px;">Đang tải danh sách hoá đơn…</td></tr>';
    }

    try {
      let url = `/finance/dealers/${dealerId}/invoices`;
      if (status) {
        url += `?status=${encodeURIComponent(status)}`;
      }

      const res = await Auth.apiFetch(url);
      const json = await res.json().catch(() => null);

      if (!res.ok || !json?.success) {
        throw new Error(json?.message || 'Không thể tải danh sách hoá đơn');
      }

      const list = json.data || [];
      state.invoices.list = list;

      // Tính toán tóm tắt
      let totalDebt = 0;
      let unpaidCount = 0;
      let overdueCount = 0;

      list.forEach((inv) => {
        const remaining = inv.remainingAmount || 0;
        totalDebt += remaining;
        if (remaining > 0) unpaidCount++;
        if (inv.isOverdue || (inv.overdueDays && inv.overdueDays > 0)) overdueCount++;
      });

      const statTotalDebt = $('stat-total-debt');
      const statUnpaid = $('stat-unpaid-invoices');
      const statOverdue = $('stat-overdue-invoices');

      if (statTotalDebt) statTotalDebt.textContent = DebtPaymentsUtils.formatMoney(totalDebt);
      if (statUnpaid) statUnpaid.textContent = unpaidCount;
      if (statOverdue) statOverdue.textContent = overdueCount;
      if (summaryCards) summaryCards.hidden = false;

      // Render bảng
      if (!list.length) {
        if (tbody) tbody.innerHTML = '<tr><td colspan="8" style="text-align: center; color: var(--ink-500); padding: 24px;">Không tìm thấy hoá đơn nào theo tiêu chí chọn.</td></tr>';
        return;
      }

      if (tbody) {
        tbody.innerHTML = list.map((inv) => {
          const statusInfo = DebtPaymentsUtils.getInvoicePaymentStatusInfo(inv.paymentStatus);
          return `
            <tr>
              <td><strong>${escapeHtml(inv.orderCode || `Đơn #${inv.orderId}`)}</strong></td>
              <td>${DebtPaymentsUtils.formatDate(inv.recordedAt || inv.invoiceDate)}</td>
              <td class="num">${DebtPaymentsUtils.formatMoney(inv.totalAmount)}</td>
              <td class="num">${DebtPaymentsUtils.formatMoney(inv.paidAmount)}</td>
              <td class="num" style="font-weight: 700; color: ${inv.remainingAmount > 0 ? '#dc2626' : '#15803d'};">
                ${DebtPaymentsUtils.formatMoney(inv.remainingAmount)}
              </td>
              <td><span class="badge ${statusInfo.badgeClass}">${escapeHtml(inv.paymentStatusDisplayName || statusInfo.label)}</span></td>
              <td>
                ${inv.isOverdue
                  ? `<span class="badge badge--overdue">Quá hạn ${inv.overdueDays || 1} ngày</span>`
                  : '<span style="color: var(--ink-400); font-size: 0.8125rem;">—</span>'
                }
              </td>
              <td style="text-align: right;">
                <button class="btn btn--secondary btn--sm" data-action="view-order-settlements" data-order-id="${inv.orderId}" data-order-code="${escapeHtml(inv.orderCode || '')}" type="button">
                  Lịch sử đối trừ
                </button>
              </td>
            </tr>
          `;
        }).join('');
      }
    } catch (err) {
      if (tbody) {
        tbody.innerHTML = `<tr><td colspan="8" style="text-align: center; color: #dc2626; padding: 24px;">${escapeHtml(err.message)}</td></tr>`;
      }
    }
  }

  /**
   * Mở modal lịch sử đối trừ của một hoá đơn cụ thể
   */
  async function openOrderSettlements(orderId, orderCode) {
    if (!syncPermissions()) return;

    const modal = $('modal-order-settlements');
    const titleEl = $('modal-order-settlements-title');
    const summaryEl = $('order-settlements-summary');
    const tbody = $('order-settlements-tbody');

    if (titleEl) titleEl.textContent = `Lịch sử đối trừ hoá đơn ${orderCode || `#${orderId}`}`;
    if (summaryEl) summaryEl.textContent = `Đang tải lịch sử các lần đối trừ vào hoá đơn ${orderCode || `#${orderId}`}…`;
    if (tbody) {
      tbody.innerHTML = '<tr><td colspan="4" style="text-align:center; padding: 16px;">Đang tải dữ liệu…</td></tr>';
    }
    if (modal) modal.hidden = false;

    try {
      const res = await Auth.apiFetch(`/finance/orders/${orderId}/settlements`);
      const json = await res.json().catch(() => null);

      if (!res.ok || !json?.success) {
        throw new Error(json?.message || 'Không thể tải lịch sử đối trừ');
      }

      const settlements = json.data || [];
      if (summaryEl) {
        summaryEl.textContent = `Hoá đơn ${orderCode || `#${orderId}`} có ${settlements.length} lần cấn trừ công nợ.`;
      }

      if (!settlements.length) {
        if (tbody) {
          tbody.innerHTML = '<tr><td colspan="4" style="text-align:center; padding: 16px; color: var(--ink-500);">Chưa có lần thu tiền nào đối trừ vào hoá đơn này.</td></tr>';
        }
        return;
      }

      if (tbody) {
        tbody.innerHTML = settlements.map((s) => `
          <tr>
            <td><strong>${escapeHtml(s.paymentCode || `PT #${s.paymentId || '—'}`)}</strong></td>
            <td class="num" style="font-weight: 700; color: var(--teal-deep);">${DebtPaymentsUtils.formatMoney(s.allocatedAmount)}</td>
            <td>${DebtPaymentsUtils.formatDateTime(s.allocatedAt || s.createdAt)}</td>
            <td>${escapeHtml(s.note || '—')}</td>
          </tr>
        `).join('');
      }
    } catch (err) {
      if (tbody) {
        tbody.innerHTML = `<tr><td colspan="4" style="text-align:center; color: #dc2626; padding: 16px;">${escapeHtml(err.message)}</td></tr>`;
      }
    }
  }

  /**
   * Khởi tạo các sự kiện lắng nghe
   */
  function setupEventListeners() {
    // ── Nút Tải lại trên thanh tiêu đề ──
    $('dp-btn-reload')?.addEventListener('click', () => {
      if (state.activeTab === 'payments') {
        loadPayments(state.currentPage);
      } else {
        loadDealerInvoices();
      }
    });

    // ── Nút Lập phiếu thu tiền trên thanh tiêu đề ──
    $('dp-btn-create-payment')?.addEventListener('click', openCreatePaymentModal);

    // ── Đóng Modal khi click nút [data-close-modal] ──
    document.querySelectorAll('[data-close-modal]').forEach((btn) => {
      btn.addEventListener('click', () => {
        const modalType = btn.dataset.closeModal;
        const modal = $(`modal-${modalType}`);
        if (modal) modal.hidden = true;
      });
    });

    // ── Chuyển đổi Tab ──
    $('tab-btn-payments')?.addEventListener('click', () => {
      state.activeTab = 'payments';
      $('tab-btn-payments').classList.add('active');
      $('tab-btn-invoices').classList.remove('active');
      $('panel-payments').hidden = false;
      $('panel-invoices').hidden = true;
    });

    $('tab-btn-invoices')?.addEventListener('click', () => {
      state.activeTab = 'invoices';
      $('tab-btn-invoices').classList.add('active');
      $('tab-btn-payments').classList.remove('active');
      $('panel-invoices').hidden = false;
      $('panel-payments').hidden = true;
    });

    // ── Lọc danh sách phiếu thu ──
    $('filter-payments-form')?.addEventListener('submit', (e) => {
      e.preventDefault();
      state.selectedDealerId = $('filter-dealer-select')?.value || '';
      loadPayments(0);
    });

    $('btn-reset-payment-filter')?.addEventListener('click', () => {
      const select = $('filter-dealer-select');
      if (select) select.value = '';
      state.selectedDealerId = '';
      loadPayments(0);
    });

    // ── Phân trang phiếu thu ──
    $('payments-btn-prev')?.addEventListener('click', () => {
      if (state.currentPage > 0) loadPayments(state.currentPage - 1);
    });

    $('payments-btn-next')?.addEventListener('click', () => {
      if (state.currentPage < state.totalPages - 1) loadPayments(state.currentPage + 1);
    });

    // ── Bấm Chi tiết hoặc Điều chỉnh trên bảng phiếu thu ──
    $('payments-tbody')?.addEventListener('click', (e) => {
      const btn = e.target.closest('button[data-action]');
      if (!btn) return;
      const action = btn.dataset.action;
      const id = btn.dataset.id;
      if (!id) return;

      if (action === 'view-detail') {
        openPaymentDetail(id);
      } else if (action === 'print-receipt') {
        openReceiptPrintModal(id);
      } else if (action === 'adjust-payment') {
        const payment = state.payments.find((p) => String(p.id) === String(id));
        if (payment) openAdjustmentModal(payment);
      }
    });

    // ── Nút mở modal điều chỉnh từ modal chi tiết ──
    $('btn-open-adjust-from-detail')?.addEventListener('click', () => {
      if (state.currentDetailPayment) {
        $('modal-payment-detail').hidden = true;
        openAdjustmentModal(state.currentDetailPayment);
      }
    });

    // ── Nút In phiếu thu và Tải PDF từ modal chi tiết (S7-09) ──
    $('btn-print-from-detail')?.addEventListener('click', () => {
      if (state.currentDetailPayment) {
        openReceiptPrintModal(state.currentDetailPayment);
      }
    });

    $('btn-download-pdf-from-detail')?.addEventListener('click', () => {
      if (state.currentDetailPayment) {
        downloadReceiptPdf(state.currentDetailPayment);
      }
    });

    // ── Thao tác trên Modal xem trước & In phiếu thu (S7-09) ──
    $('btn-print-receipt-action')?.addEventListener('click', () => {
      window.print();
    });
    $('btn-print-receipt-action-footer')?.addEventListener('click', () => {
      window.print();
    });

    $('btn-download-receipt-pdf')?.addEventListener('click', () => {
      downloadReceiptPdf(state.currentReceiptPayment);
    });
    $('btn-download-receipt-pdf-footer')?.addEventListener('click', () => {
      downloadReceiptPdf(state.currentReceiptPayment);
    });

    // ── Thay đổi đại lý trong form tạo phiếu thu ──
    $('create-dealer-select')?.addEventListener('change', (e) => {
      handleDealerChangeForCreate(e.target.value);
    });

    // ── Định dạng tiền khi nhập số tiền thu ──
    $('create-amount')?.addEventListener('input', (e) => {
      const num = DebtPaymentsUtils.parseMoney(e.target.value);
      const fmt = $('create-amount-formatted');
      if (fmt) {
        fmt.textContent = num > 0 ? `= ${DebtPaymentsUtils.formatMoney(num)}` : '';
      }
      updateManualAllocationSummary();
    });

    // ── Radio chuyển chế độ đối trừ ──
    document.querySelectorAll('input[name="allocationMode"]').forEach((radio) => {
      radio.addEventListener('change', (e) => {
        toggleAllocationMode(e.target.value);
      });
    });

    // ── Nút Xem trước đối trừ FIFO ──
    $('btn-preview-fifo')?.addEventListener('click', previewFifoAllocation);

    // ── Submit form tạo phiếu thu ──
    $('form-payment-create')?.addEventListener('submit', submitPaymentCreate);

    // ── Thay đổi loại điều chỉnh trong form điều chỉnh ──
    $('adj-type-select')?.addEventListener('change', (e) => {
      const isPartial = e.target.value === 'PARTIAL_REVERSAL';
      const group = $('adj-amount-group');
      if (group) group.hidden = !isPartial;
    });

    // ── Định dạng tiền khi nhập số tiền điều chỉnh giảm ──
    $('adj-amount-input')?.addEventListener('input', (e) => {
      const num = DebtPaymentsUtils.parseMoney(e.target.value);
      const fmt = $('adj-amount-formatted');
      if (fmt) {
        fmt.textContent = num > 0 ? `= ${DebtPaymentsUtils.formatMoney(num)}` : '';
      }
    });

    // ── Submit form điều chỉnh ──
    $('form-adjustment-create')?.addEventListener('submit', submitAdjustmentCreate);

    // ── Submit tra cứu hoá đơn nợ đại lý (Tab 2) ──
    $('filter-invoices-form')?.addEventListener('submit', (e) => {
      e.preventDefault();
      loadDealerInvoices();
    });

    // ── Bấm Lịch sử đối trừ trên bảng hoá đơn ──
    $('invoices-tbody')?.addEventListener('click', (e) => {
      const btn = e.target.closest('button[data-action="view-order-settlements"]');
      if (!btn) return;
      const orderId = btn.dataset.orderId;
      const orderCode = btn.dataset.orderCode;
      if (orderId) openOrderSettlements(orderId, orderCode);
    });

    // ── Lắng nghe sự kiện ma trận quyền thay đổi (auth:permissions-changed) ──
    window.addEventListener('auth:permissions-changed', () => {
      const canView = syncPermissions();
      if (canView) {
        if (state.activeTab === 'payments') {
          loadPayments(state.currentPage);
        } else {
          loadDealerInvoices();
        }
      }
    });
  }

  /**
   * Khởi động trang khi nạp xong DOM
   */
  async function init() {
    if (!Auth.requireLogin()) return;

    setupEventListeners();
    const canView = syncPermissions();
    if (canView) {
      await loadDealers();
      await loadPayments(0);
    }
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();

