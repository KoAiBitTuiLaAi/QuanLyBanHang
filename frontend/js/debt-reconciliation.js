/**
 * debt-reconciliation.js — Xử lý logic nghiệp vụ Biên bản đối chiếu công nợ định kỳ (S7-06).
 *
 * Tính năng:
 * - Tổng hợp biên bản đối chiếu công nợ theo kỳ (đầu kỳ, phát sinh tăng/giảm, cuối kỳ).
 * - Xuất file PDF biên bản và hỗ trợ in ấn trực tiếp.
 * - Cho phép đại lý xác nhận khớp đúng hoặc gửi phản hồi lệch ngay trên giao diện.
 * - Thực thi ma trận phân quyền DB: Bắt buộc FINANCE_VIEW_DEBT hoặc ROLE_ADMIN.
 * - Cách ly dữ liệu đại lý theo RLS (đại lý chỉ xem và đối chiếu của chính mình).
 * - Đồng bộ tức thời khi ma trận quyền thay đổi (auth:permissions-changed).
 * - Tuyệt đối không lưu dữ liệu kinh doanh vào localStorage hay sessionStorage.
 */
(async () => {
  "use strict";

  if (typeof Auth !== "undefined" && !Auth.requireLogin()) {
    return;
  }

  const $ = id => document.getElementById(id);
  const escapeHtml = DebtReconciliationUtils.escapeHtml;
  const {
    formatMoney,
    formatDate,
    formatDateTime,
    validateDateRange,
    canAccessReconciliation,
    isDealerUser,
    getSourceTypeInfo,
    validateDiscrepancyFeedback,
    buildExportUrl,
  } = DebtReconciliationUtils;

  // In-memory state (không dùng browser storage)
  const state = {
    dealers: [],
    currentDealer: null,
    currentStatement: null,
    loading: false,
    exportingPdf: false,
    // Phản hồi đối chiếu lưu trong bộ nhớ phiên làm việc
    reconciliationFeedback: null,
  };

  /**
   * Đồng bộ phân quyền
   */
  async function syncPermissions() {
    if (typeof Auth !== "undefined") {
      await Auth.refreshPermissions();
    }
    const currentUser = typeof Auth !== "undefined" ? Auth.getUser() : null;
    const hasPermission = canAccessReconciliation(currentUser);

    const accessNotice = $("reconcile-access");
    const filterCard = document.querySelector(".reconcile-filter-card");
    const resultPanel = $("reconcile-result-panel");
    const initialState = $("reconcile-initial-state");
    const errorNotice = $("reconcile-error");

    if (!hasPermission) {
      if (accessNotice) accessNotice.hidden = false;
      if (filterCard) filterCard.hidden = true;
      if (resultPanel) resultPanel.hidden = true;
      if (initialState) initialState.hidden = true;
      if (errorNotice) errorNotice.hidden = true;
      state.currentStatement = null;
      return false;
    }

    if (accessNotice) accessNotice.hidden = true;
    if (filterCard) filterCard.hidden = false;
    return true;
  }

  /**
   * Hiển thị thông báo lỗi
   */
  function showError(msg) {
    const errorEl = $("reconcile-error");
    if (!errorEl) return;
    if (!msg) {
      errorEl.hidden = true;
      errorEl.textContent = "";
    } else {
      errorEl.hidden = false;
      errorEl.textContent = String(msg);
      errorEl.scrollIntoView({ behavior: "smooth", block: "nearest" });
    }
  }

  /**
   * Tải danh mục đại lý
   */
  async function loadDealers() {
    const select = $("filter-dealer");
    if (!select) return;

    select.innerHTML = '<option value="">-- Đang tải danh sách đại lý... --</option>';

    try {
      let dealerList = [];

      try {
        const res = await Auth.apiFetch("/dealers?size=200");
        const json = await res.json().catch(() => null);
        if (res.ok && json?.success && json?.data?.content && Array.isArray(json.data.content)) {
          dealerList = json.data.content.map(d => ({
            id: d.id,
            code: d.code || "",
            name: d.name || d.customerName || `Đại lý #${d.id}`,
            phone: d.phone || d.phoneNumber || "",
            address: d.address || "",
            contactPerson: d.contactPerson || d.ownerName || "Đại diện đại lý",
          }));
        } else if (res.ok && json?.success && Array.isArray(json.data)) {
          dealerList = json.data.map(d => ({
            id: d.id,
            code: d.code || "",
            name: d.name || d.customerName || `Đại lý #${d.id}`,
            phone: d.phone || d.phoneNumber || "",
            address: d.address || "",
            contactPerson: d.contactPerson || d.ownerName || "Đại diện đại lý",
          }));
        }
      } catch {
        // Fallback
      }

      if (!dealerList.length) {
        const res2 = await Auth.apiFetch("/finance/debt");
        const json2 = await res2.json().catch(() => null);
        if (res2.ok && json2?.success && Array.isArray(json2.data)) {
          dealerList = json2.data.map(d => ({
            id: d.dealerId,
            code: d.dealerCode || "",
            name: d.customerName || `Đại lý #${d.dealerId}`,
            phone: "",
            address: "",
            contactPerson: "Đại diện đại lý",
          }));
        }
      }

      state.dealers = dealerList;

      const user = Auth.getUser();
      const isDealer = isDealerUser(user);
      const assignedDealerId = user?.dealerId || user?.dealer?.id;

      if (isDealer && assignedDealerId) {
        state.dealers = state.dealers.filter(d => String(d.id) === String(assignedDealerId));
      }

      select.innerHTML = '<option value="">-- Chọn đại lý đối chiếu --</option>' +
        state.dealers.map(d => {
          const display = d.code ? `[${d.code}] ${d.name}` : d.name;
          return `<option value="${d.id}">${escapeHtml(display)}</option>`;
        }).join("");

      if (isDealer && assignedDealerId) {
        select.value = String(assignedDealerId);
        select.disabled = true;
      }
    } catch (err) {
      select.innerHTML = '<option value="">-- Không thể tải danh sách đại lý --</option>';
      showError("Lỗi tải danh mục đại lý: " + (err.message || "Vui lòng thử lại."));
    }
  }

  /**
   * Thiết lập ngày theo phím chọn nhanh
   */
  function setQuickRange(type) {
    const fromInput = $("filter-from");
    const toInput = $("filter-to");
    if (!fromInput || !toInput) return;

    const now = new Date();
    const pad = n => String(n).padStart(2, "0");
    const formatDateObj = d => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;

    let fromDate, toDate;

    switch (type) {
      case "this-month":
        fromDate = new Date(now.getFullYear(), now.getMonth(), 1);
        toDate = now;
        break;
      case "last-month":
        fromDate = new Date(now.getFullYear(), now.getMonth() - 1, 1);
        toDate = new Date(now.getFullYear(), now.getMonth(), 0);
        break;
      case "last-30-days":
        fromDate = new Date(now.getTime() - 30 * 24 * 60 * 60 * 1000);
        toDate = now;
        break;
      case "this-quarter": {
        const quarterMonth = Math.floor(now.getMonth() / 3) * 3;
        fromDate = new Date(now.getFullYear(), quarterMonth, 1);
        toDate = now;
        break;
      }
      case "this-year":
        fromDate = new Date(now.getFullYear(), 0, 1);
        toDate = now;
        break;
      default:
        return;
    }

    fromInput.value = formatDateObj(fromDate);
    toInput.value = formatDateObj(toDate);

    document.querySelectorAll(".btn-chip").forEach(btn => {
      btn.classList.toggle("btn-chip--active", btn.dataset.range === type);
    });
  }

  /**
   * Tải biên bản đối chiếu công nợ
   */
  async function loadReconciliation(dealerId, from, to) {
    showError(null);
    state.loading = true;

    const initialState = $("reconcile-initial-state");
    const loadingState = $("reconcile-loading-state");
    const resultPanel = $("reconcile-result-panel");
    const btnSubmit = $("btn-create-reconcile");
    const btnPdf = $("btn-export-pdf");
    const btnPrint = $("btn-print");

    if (initialState) initialState.hidden = true;
    if (loadingState) loadingState.hidden = false;
    if (resultPanel) resultPanel.hidden = true;
    if (btnSubmit) btnSubmit.disabled = true;
    if (btnPdf) btnPdf.disabled = true;
    if (btnPrint) btnPrint.disabled = true;

    try {
      const url = `/finance/debt/${encodeURIComponent(dealerId)}/statement?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`;
      const response = await Auth.apiFetch(url);
      const json = await response.json().catch(() => null);

      if (!response.ok || !json?.success || !json?.data) {
        throw new Error(json?.message || `Lỗi tải số liệu đối chiếu công nợ (HTTP ${response.status}).`);
      }

      state.currentStatement = json.data;

      // Tìm thông tin đại lý
      const matchedDealer = state.dealers.find(d => String(d.id) === String(dealerId));
      state.currentDealer = matchedDealer || {
        id: json.data.dealerId,
        code: json.data.dealerCode || "",
        name: json.data.dealerName || `Đại lý #${json.data.dealerId}`,
        phone: "",
        address: "",
        contactPerson: "Đại diện đại lý",
      };

      renderDocument(json.data, state.currentDealer);

      if (btnPdf) btnPdf.disabled = false;
      if (btnPrint) btnPrint.disabled = false;
    } catch (err) {
      showError(err.message || "Không thể tải biên bản đối chiếu công nợ.");
      if (initialState) initialState.hidden = false;
    } finally {
      state.loading = false;
      if (loadingState) loadingState.hidden = true;
      if (btnSubmit) btnSubmit.disabled = false;
    }
  }

  /**
   * Vẽ biên bản đối chiếu lên giao diện
   */
  function renderDocument(data, dealer) {
    const resultPanel = $("reconcile-result-panel");
    if (!resultPanel) return;

    // 1. Tiêu đề kỳ đối chiếu
    const periodText = $("doc-period-text");
    if (periodText) {
      periodText.textContent = `Kỳ đối chiếu: Từ ngày ${formatDate(data.from)} đến ngày ${formatDate(data.to)}`;
    }

    // 2. Thông tin Bên B (Đại lý)
    const partyBTitle = $("doc-party-b-title");
    const docDealerCode = $("doc-dealer-code");
    const docDealerContact = $("doc-dealer-contact");
    const docDealerPhone = $("doc-dealer-phone");
    const docDealerAddress = $("doc-dealer-address");
    const docDealerSigName = $("doc-dealer-sig-name");

    const dealerName = dealer.name || data.dealerName || `Đại lý #${data.dealerId}`;
    if (partyBTitle) partyBTitle.textContent = `BÊN B (ĐẠI LÝ): ${dealerName.toUpperCase()}`;
    if (docDealerCode) docDealerCode.textContent = dealer.code || data.dealerCode || "—";
    if (docDealerContact) docDealerContact.textContent = dealer.contactPerson || "Chủ đại lý";
    if (docDealerPhone) docDealerPhone.textContent = dealer.phone || "—";
    if (docDealerAddress) docDealerAddress.textContent = dealer.address || "—";
    if (docDealerSigName) docDealerSigName.textContent = dealer.contactPerson || dealerName;

    // 3. 4 chỉ số KPI tổng hợp
    const kpiOpening = $("kpi-opening");
    const kpiIncrease = $("kpi-increase");
    const kpiDecrease = $("kpi-decrease");
    const kpiClosing = $("kpi-closing");

    if (kpiOpening) kpiOpening.textContent = formatMoney(data.openingBalance);
    if (kpiIncrease) kpiIncrease.textContent = formatMoney(data.totalIncrease);
    if (kpiDecrease) kpiDecrease.textContent = formatMoney(data.totalDecrease);

    if (kpiClosing) {
      kpiClosing.textContent = formatMoney(data.closingBalance);
      const isCredit = Number(data.closingBalance) < 0;
      kpiClosing.classList.toggle("value--credit", isCredit);
    }

    // 4. Bảng kê chi tiết chứng từ
    const tbody = $("reconcile-tbody");
    if (tbody) {
      let rowsHtml = "";

      // Dòng mở đầu: Số dư nợ đầu kỳ
      rowsHtml += `
        <tr class="table-row--opening">
          <td class="text-center">—</td>
          <td>—</td>
          <td><span class="badge badge--default">Đầu kỳ</span></td>
          <td><strong>Số dư nợ đầu kỳ</strong> (Tính đến trước ngày ${formatDate(data.from)})</td>
          <td class="text-right">—</td>
          <td class="text-right">—</td>
          <td class="text-right amount-balance ${Number(data.openingBalance) < 0 ? 'value--credit' : ''}">
            ${formatMoney(data.openingBalance)}
          </td>
        </tr>
      `;

      const entries = Array.isArray(data.entries) ? data.entries : [];
      if (!entries.length) {
        rowsHtml += `
          <tr>
            <td colspan="7" class="statement-empty" style="text-align: center; padding: 20px; color: #64748b;">
              Trong kỳ không phát sinh giao dịch mua hàng hoặc thanh toán mới.
            </td>
          </tr>
        `;
      } else {
        entries.forEach((entry, idx) => {
          const typeInfo = getSourceTypeInfo(entry.sourceType);
          const hasInc = Number(entry.increase) > 0;
          const hasDec = Number(entry.decrease) > 0;
          const isCredit = Number(entry.balance) < 0;

          rowsHtml += `
            <tr>
              <td class="text-center">${idx + 1}</td>
              <td>${formatDateTime(entry.occurredAt)}</td>
              <td>
                <span class="badge ${typeInfo.badgeClass}">${typeInfo.label}</span>
                <strong style="margin-left: 4px;">${escapeHtml(entry.documentCode || "—")}</strong>
              </td>
              <td>${escapeHtml(entry.description || "—")}</td>
              <td class="text-right ${hasInc ? 'value--increase' : ''}">
                ${hasInc ? formatMoney(entry.increase) : "—"}
              </td>
              <td class="text-right ${hasDec ? 'value--decrease' : ''}">
                ${hasDec ? formatMoney(entry.decrease) : "—"}
              </td>
              <td class="text-right amount-balance ${isCredit ? 'value--credit' : ''}">
                ${formatMoney(entry.balance)}
              </td>
            </tr>
          `;
        });
      }

      // Dòng tổng kết cuối
      rowsHtml += `
        <tr class="table-row--total">
          <td class="text-center">—</td>
          <td>—</td>
          <td><strong>Tổng cộng</strong></td>
          <td><strong>Tổng phát sinh trong kỳ &amp; Dư nợ cuối kỳ</strong></td>
          <td class="text-right value--increase"><strong>${formatMoney(data.totalIncrease)}</strong></td>
          <td class="text-right value--decrease"><strong>${formatMoney(data.totalDecrease)}</strong></td>
          <td class="text-right amount-balance ${Number(data.closingBalance) < 0 ? 'value--credit' : ''}">
            <strong>${formatMoney(data.closingBalance)}</strong>
          </td>
        </tr>
      `;

      tbody.innerHTML = rowsHtml;
    }

    // 5. Kết luận
    const docEndDateText = $("doc-end-date-text");
    const docClosingWords = $("doc-closing-words");
    if (docEndDateText) docEndDateText.textContent = formatDate(data.to);
    if (docClosingWords) docClosingWords.textContent = formatMoney(data.closingBalance);

    // 6. Cập nhật trạng thái đối chiếu
    renderFeedbackStatus();

    resultPanel.hidden = false;
  }

  /**
   * Cập nhật trạng thái phản hồi / đối chiếu
   */
  function renderFeedbackStatus() {
    const badge = $("reconcile-status-badge");
    const desc = $("reconcile-status-desc");
    const alertBox = $("discrepancy-alert-box");
    const alertContent = $("discrepancy-alert-content");

    if (!state.reconciliationFeedback) {
      if (badge) {
        badge.className = "status-badge status-badge--pending";
        badge.textContent = "Chờ đại lý xác nhận";
      }
      if (desc) desc.textContent = "(Đang chờ đại lý kiểm tra và xác nhận số liệu)";
      if (alertBox) alertBox.hidden = true;
      return;
    }

    const fb = state.reconciliationFeedback;

    if (fb.type === "MATCHED") {
      if (badge) {
        badge.className = "status-badge status-badge--matched";
        badge.textContent = "Đã xác nhận khớp đúng";
      }
      if (desc) desc.textContent = `(Đại lý đã ký duyệt lúc ${fb.time})`;
      if (alertBox) alertBox.hidden = true;
    } else if (fb.type === "DISCREPANCY") {
      if (badge) {
        badge.className = "status-badge status-badge--discrepancy";
        badge.textContent = "Phản hồi lệch số liệu";
      }
      if (desc) desc.textContent = `(Chênh lệch: ${formatMoney(fb.difference)})`;
      if (alertBox && alertContent) {
        alertBox.hidden = false;
        alertContent.innerHTML = `
          <div>- Số dư theo hệ thống SaleStock: <strong>${formatMoney(fb.systemBalance)}</strong></div>
          <div>- Số dư đại lý ghi nhận: <strong>${formatMoney(fb.dealerClaimedAmount)}</strong></div>
          <div>- Khoản chênh lệch (Hệ thống - Đại lý): <strong style="color: #dc2626;">${formatMoney(fb.difference)}</strong></div>
          <div style="margin-top: 6px;">- Lý do / Chứng từ chưa khớp: <em>${escapeHtml(fb.reason)}</em></div>
          <div style="margin-top: 4px; font-size: 0.8125rem; color: #475569;">Thời điểm gửi phản hồi: ${fb.time}</div>
        `;
      }
    }
  }

  /**
   * Xuất PDF biên bản đối chiếu
   */
  async function exportPdf() {
    if (state.exportingPdf) return;
    if (!state.currentStatement) {
      showError("Chưa có dữ liệu biên bản đối chiếu để xuất.");
      return;
    }

    const { dealerId, dealerCode, from, to } = state.currentStatement;
    const btn = $("btn-export-pdf");
    const originalText = btn ? btn.textContent : "";

    try {
      state.exportingPdf = true;
      if (btn) {
        btn.disabled = true;
        btn.textContent = "Đang xuất PDF...";
      }

      const exportUrl = buildExportUrl(dealerId, from, to);
      const response = await Auth.apiFetch(exportUrl);

      if (!response.ok) {
        const errorJson = await response.json().catch(() => null);
        throw new Error(errorJson?.message || `Lỗi tải tệp PDF biên bản (HTTP ${response.status})`);
      }

      const blob = await response.blob();
      const cleanCode = (dealerCode || String(dealerId)).replace(/[^\w.-]/g, "_");
      const filename = `Bien-ban-doi-chieu_${cleanCode}_${from}_${to}.pdf`;

      const downloadUrl = window.URL.createObjectURL(blob);
      const a = document.createElement("a");
      a.style.display = "none";
      a.href = downloadUrl;
      a.download = filename;
      document.body.appendChild(a);
      a.click();
      setTimeout(() => {
        document.body.removeChild(a);
        window.URL.revokeObjectURL(downloadUrl);
      }, 200);

    } catch (err) {
      showError("Lỗi xuất PDF: " + (err.message || "Vui lòng thử lại."));
    } finally {
      state.exportingPdf = false;
      if (btn) {
        btn.disabled = false;
        btn.textContent = originalText;
      }
    }
  }

  /**
   * Khởi tạo trang
   */
  async function init() {
    const hasPerm = await syncPermissions();
    if (!hasPerm) return;

    // Khoảng ngày mặc định
    const defaultRange = DebtReconciliationUtils.getDefaultDateRange();
    const fromInput = $("filter-from");
    const toInput = $("filter-to");
    if (fromInput) fromInput.value = defaultRange.from;
    if (toInput) toInput.value = defaultRange.to;

    // Active chip Tháng này
    const btnThisMonth = document.querySelector('.btn-chip[data-range="this-month"]');
    if (btnThisMonth) btnThisMonth.classList.add("btn-chip--active");

    await loadDealers();

    // Query params
    const params = new URLSearchParams(window.location.search);
    const paramDealer = params.get("dealerId");
    const paramFrom = params.get("from");
    const paramTo = params.get("to");

    if (paramFrom && fromInput) fromInput.value = paramFrom;
    if (paramTo && toInput) toInput.value = paramTo;

    if (paramDealer) {
      const select = $("filter-dealer");
      if (select) {
        select.value = paramDealer;
        const validation = validateDateRange(fromInput.value, toInput.value);
        if (validation.valid) {
          loadReconciliation(paramDealer, fromInput.value, toInput.value);
        }
      }
    }

    // Quick range clicks
    document.querySelectorAll(".btn-chip").forEach(btn => {
      btn.addEventListener("click", () => setQuickRange(btn.dataset.range));
    });

    // Submit filter form
    const form = $("reconcile-filter-form");
    if (form) {
      form.addEventListener("submit", (e) => {
        e.preventDefault();
        const dealerId = $("filter-dealer")?.value;
        const from = $("filter-from")?.value;
        const to = $("filter-to")?.value;

        if (!dealerId) {
          showError("Vui lòng chọn đại lý cần lập biên bản đối chiếu.");
          return;
        }

        const validation = validateDateRange(from, to);
        if (!validation.valid) {
          showError(validation.error);
          return;
        }

        loadReconciliation(dealerId, from, to);
      });
    }

    // Export PDF click
    const btnPdf = $("btn-export-pdf");
    if (btnPdf) {
      btnPdf.addEventListener("click", exportPdf);
    }

    // Print click
    const btnPrint = $("btn-print");
    if (btnPrint) {
      btnPrint.addEventListener("click", () => window.print());
    }

    // Đại lý xác nhận khớp đúng
    const btnConfirmMatch = $("btn-dealer-confirm-match");
    if (btnConfirmMatch) {
      btnConfirmMatch.addEventListener("click", () => {
        if (!state.currentStatement) return;
        const nowStr = new Date().toLocaleString("vi-VN");
        state.reconciliationFeedback = {
          type: "MATCHED",
          time: nowStr,
          systemBalance: state.currentStatement.closingBalance,
        };
        renderFeedbackStatus();
      });
    }

    // Đại lý phản hồi lệch số liệu (mở modal)
    const btnReportDiff = $("btn-dealer-report-diff");
    const modal = $("modal-discrepancy");
    const btnCloseModal = $("btn-close-modal");
    const btnCancelModal = $("btn-cancel-modal");
    const inputSysBal = $("modal-system-balance");
    const inputDealerAmt = $("modal-dealer-amount");
    const inputDiffAmt = $("modal-diff-amount");
    const formDisc = $("form-discrepancy");
    const modalError = $("modal-disc-error");

    if (btnReportDiff && modal) {
      btnReportDiff.addEventListener("click", () => {
        if (!state.currentStatement) return;
        const sysBal = state.currentStatement.closingBalance;
        if (inputSysBal) inputSysBal.value = formatMoney(sysBal);
        if (inputDealerAmt) inputDealerAmt.value = "";
        if (inputDiffAmt) inputDiffAmt.value = "0 ₫";
        if (modalError) modalError.hidden = true;
        modal.hidden = false;
      });
    }

    function closeModal() {
      if (modal) modal.hidden = true;
    }

    if (btnCloseModal) btnCloseModal.addEventListener("click", closeModal);
    if (btnCancelModal) btnCancelModal.addEventListener("click", closeModal);

    // Tính chênh lệch động khi nhập tiền
    if (inputDealerAmt && inputDiffAmt) {
      inputDealerAmt.addEventListener("input", () => {
        const sys = Number(state.currentStatement?.closingBalance || 0);
        const dealer = Number(inputDealerAmt.value || 0);
        const diff = sys - dealer;
        inputDiffAmt.value = formatMoney(diff);
      });
    }

    // Gửi phản hồi lệch
    if (formDisc) {
      formDisc.addEventListener("submit", (e) => {
        e.preventDefault();
        if (!state.currentStatement) return;

        const dealerAmt = inputDealerAmt?.value;
        const reason = $("modal-diff-reason")?.value;

        const val = validateDiscrepancyFeedback(
          state.currentStatement.closingBalance,
          dealerAmt,
          reason
        );

        if (!val.valid) {
          if (modalError) {
            modalError.hidden = false;
            modalError.textContent = val.error;
          }
          return;
        }

        const nowStr = new Date().toLocaleString("vi-VN");
        state.reconciliationFeedback = {
          type: "DISCREPANCY",
          time: nowStr,
          systemBalance: val.systemBalance,
          dealerClaimedAmount: val.dealerClaimedAmount,
          difference: val.difference,
          reason: val.reason,
        };

        renderFeedbackStatus();
        closeModal();
      });
    }

    // Lắng nghe đổi quyền
    window.addEventListener("auth:permissions-changed", async () => {
      await syncPermissions();
    });
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();

