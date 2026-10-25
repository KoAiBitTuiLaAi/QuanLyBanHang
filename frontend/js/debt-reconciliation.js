/** S7-06: saved statements and dealer responses are persisted by the backend. */
(() => {
  "use strict";
  if (!Auth.requireLogin()) return;
  const $ = id => document.getElementById(id);
  const { escapeHtml, formatMoney, formatDate, formatDateTime, validateDateRange,
    canAccessReconciliation, isDealerUser, getSourceTypeInfo, moneyCents, moneyDifference, buildExportUrl } = DebtReconciliationUtils;
  const state = { currentStatement: null, detail: null, page: 0, pages: 0,
    busy: false, exporting: false, uncertain: false, epoch: 0, requestKey: null };
  const controllers = new Set();
  const allowed = () => canAccessReconciliation(Auth.getUser());
  const dealer = () => isDealerUser(Auth.getUser());
  const canCreate = () => allowed() && !dealer() && (Auth.hasRole("ROLE_ADMIN") || Auth.hasPermission("FINANCE_MANAGE"));
  const base = () => dealer() ? "/portal/debt-reconciliations" : "/finance/debt-reconciliations";
  const statusLabel = status => ({PENDING: "Chờ đại lý xác nhận", CONFIRMED: "Đã xác nhận khớp đúng", DISPUTED: "Phản hồi lệch số liệu"})[status] || status;
  function showError(message) {
    $("reconcile-error").textContent = message || "";
    $("reconcile-error").hidden = !message;
  }
  function controls() {
    const pending = state.detail?.status === "PENDING";
    $("btn-create-reconcile").hidden = !canCreate();
    $("btn-create-reconcile").disabled = state.busy || state.uncertain || !canCreate();
    $("btn-list-reconcile").disabled = state.busy || !allowed();
    $("btn-export-pdf").disabled = !state.detail || state.busy || state.exporting || !allowed();
    $("btn-print").disabled = !state.detail || state.busy || !allowed();
    $("dealer-actions-wrap").hidden = !dealer() || !pending || !allowed();
    for (const id of ["btn-dealer-confirm-match", "btn-dealer-report-diff", "btn-submit-discrepancy"])
      $(id).disabled = state.busy || state.uncertain || !dealer() || !pending || !allowed();
    $("reconcile-prev").disabled = state.busy || state.page <= 0;
    $("reconcile-next").disabled = state.busy || state.page + 1 >= state.pages;
    $("reconcile-loading-state").hidden = !state.busy || !allowed();
  }
  function clearDetail() {
    state.detail = state.currentStatement = null;
    $("reconcile-result-panel").hidden = true;
    $("reconcile-tbody").replaceChildren();
    $("discrepancy-alert-content").replaceChildren();
    for (const id of ["doc-party-b-title", "doc-dealer-code", "doc-dealer-contact", "doc-dealer-phone",
      "doc-dealer-address", "doc-dealer-sig-name", "doc-period-text", "doc-closing-words", "doc-created-by",
      "kpi-opening", "kpi-increase", "kpi-decrease", "kpi-closing", "reconcile-status-desc"])
      $(id).textContent = "";
    $("form-discrepancy").reset();
    $("modal-discrepancy").hidden = true;
  }
  function invalidate() {
    state.epoch++;
    controllers.forEach(c => c.abort()); controllers.clear();
    state.busy = state.exporting = false;
    clearDetail();
  }
  function permissionChange() {
    invalidate();
    $("reconcile-history-rows").replaceChildren();
    $("reconcile-history").hidden = true;
    $("filter-dealer").replaceChildren();
    $("reconcile-access").hidden = allowed();
    document.querySelector(".reconcile-filter-card").hidden = !allowed();
    $("reconcile-initial-state").hidden = !allowed();
    showError(null);
    controls();
    if (allowed()) loadOptions().then(() => loadList(0)).catch(reportError);
  }
  async function request(path, options = {}, binary = false) {
    if (!allowed()) throw new Error("Bạn không có quyền đối chiếu công nợ.");
    const epoch = state.epoch;
    const controller = new AbortController();
    controllers.add(controller);
    try {
      const response = await Auth.apiFetch(path, {...options, signal: controller.signal});
      const body = binary && response.ok ? await response.blob() : await response.json().catch(() => null);
      if (epoch !== state.epoch || !allowed()) throw new DOMException("Stale response", "AbortError");
      if (!response.ok || !binary && (!body?.success || !body.data)) {
        const error = new Error(body?.message || "Không tải được dữ liệu (HTTP " + response.status + ").");
        error.status = response.status;
        throw error;
      }
      if (binary && !String(response.headers.get("content-type")).includes("application/pdf"))
        throw new Error("Máy chủ không trả về tệp PDF hợp lệ.");
      return binary ? body : body.data;
    } finally { controllers.delete(controller); }
  }
  function reportError(error) {
    if (error.name !== "AbortError" && allowed()) showError(error.message || "Không kết nối được máy chủ.");
  }
  async function loadOptions() {
    const epoch = state.epoch, select = $("filter-dealer");
    const result = await request(base() + "/options?keyword=" + encodeURIComponent($("dealer-keyword").value));
    if (epoch !== state.epoch) return;
    select.replaceChildren(new Option(dealer() ? "Đại lý của tôi" : "Tất cả đại lý", ""));
    for (const d of result.content || []) select.add(new Option("[" + d.code + "] " + d.name, String(d.id)));
    if (dealer() && result.content?.length === 1) select.value = String(result.content[0].id);
    select.disabled = dealer();
    $("dealer-keyword").hidden = dealer();
    $("btn-find-dealers").hidden = dealer();
  }
  function filters(page) {
    const from = $("filter-from").value, to = $("filter-to").value;
    const validation = validateDateRange(from, to);
    if (!validation.valid) throw new Error(validation.error);
    const query = new URLSearchParams({from, to, page: String(page)});
    if ($("filter-dealer").value) query.set("dealerId", $("filter-dealer").value);
    return query;
  }
  function renderList(result) {
    state.page = result.number || 0; state.pages = result.totalPages || 0;
    const tbody = $("reconcile-history-rows"); tbody.replaceChildren();
    for (const record of result.content || []) {
      const row = document.createElement("tr");
      for (const text of [record.code, record.dealerName, formatDate(record.from) + " – " + formatDate(record.to),
        formatMoney(record.closingBalance), statusLabel(record.status)]) {
        const cell = document.createElement("td"); cell.textContent = text; row.append(cell);
      }
      const cell = document.createElement("td"), button = document.createElement("button");
      button.type = "button"; button.className = "btn btn--secondary"; button.textContent = "Xem";
      button.addEventListener("click", () => openRecord(record.id)); cell.append(button); row.append(cell); tbody.append(row);
    }
    if (!result.content?.length) {
      const row = tbody.insertRow(), cell = row.insertCell();
      cell.colSpan = 6; cell.textContent = "Chưa có biên bản đã lưu trong kỳ được chọn.";
    }
    $("reconcile-history").hidden = false;
    $("reconcile-page-number").textContent = state.pages ? "Trang " + (state.page + 1) + "/" + state.pages : "0 biên bản";
  }
  async function loadList(page = 0) {
    if (state.busy || !allowed()) return;
    let query;
    try { query = filters(page); } catch (error) { reportError(error); return; }
    invalidate(); const epoch = state.epoch;
    state.busy = true; showError(null); controls();
    $("reconcile-history-rows").replaceChildren();
    try {
      const data = await request(base() + "?" + query);
      renderList(data); state.uncertain = false;
      // A successful refresh resolves an unknown write; do not silently issue another POST.
      state.requestKey = null;
    } catch (error) { reportError(error); }
    finally { if (epoch === state.epoch) { state.busy = false; controls(); } }
  }
  function display(detail) {
    state.detail = detail; state.currentStatement = detail.statement;
    const s = detail.statement;
    renderDocument(s, {name: s.dealerName, code: s.dealerCode, address: s.dealerAddress, phone: s.dealerPhone});
    $("doc-created-by").textContent = detail.createdBy;
    $("doc-dealer-contact").textContent = detail.respondedBy || "Chưa có phản hồi";
    $("doc-dealer-sig-name").textContent = "Đại diện đại lý";
    $("doc-period-text").textContent = detail.code + " | " + formatDate(s.from) + " – " + formatDate(s.to)
      + " | Lập: " + formatDateTime(detail.createdAt);
    $("reconcile-initial-state").hidden = true;
    controls();
  }
  async function openRecord(id) {
    if (state.busy || !allowed()) return;
    invalidate(); const epoch = state.epoch;
    state.busy = true; showError(null); controls();
    try { display(await request(base() + "/" + encodeURIComponent(id))); }
    catch (error) { reportError(error); }
    finally { if (epoch === state.epoch) { state.busy = false; controls(); } }
  }
  async function createRecord() {
    if (state.busy || state.uncertain || !canCreate()) return;
    let query;
    try { query = filters(0); } catch (error) { reportError(error); return; }
    if (!query.get("dealerId")) { showError("Chọn một đại lý để lập biên bản."); return; }
    if (!window.confirm("Lưu biên bản theo số liệu hiện tại và gửi lên cổng đại lý để đối chiếu?")) return;
    invalidate(); const epoch = state.epoch;
    state.busy = true; showError(null); controls();
    state.requestKey ||= crypto.randomUUID();
    try {
      const detail = await request("/finance/debt-reconciliations", {method: "POST",
        body: JSON.stringify({dealerId: query.get("dealerId"), from: query.get("from"), to: query.get("to"), requestKey: state.requestKey})});
      state.requestKey = null;
      const data = await request(base() + "?" + query);
      renderList(data); display(detail);
    } catch (error) {
      if (epoch === state.epoch && error.name !== "AbortError") {
        state.uncertain = !error.status || error.status >= 500 || error.status === 409;
        reportError(error);
        if (state.uncertain) showError("Chưa xác định kết quả lưu. Bấm Tải danh sách để kiểm tra trước khi lập thêm biên bản.");
      }
    } finally { if (epoch === state.epoch) { state.busy = false; controls(); } }
  }
  function renderFeedbackStatus() {
    const d = state.detail;
    if (!d) return;
    const badge = $("reconcile-status-badge");
    badge.textContent = statusLabel(d.status);
    badge.className = "status-badge status-badge--" + ({PENDING: "pending", CONFIRMED: "matched", DISPUTED: "discrepancy"})[d.status];
    $("reconcile-status-desc").textContent = d.respondedAt
      ? "Người phản hồi: " + d.respondedBy + " | " + formatDateTime(d.respondedAt)
      : "Số liệu giữ nguyên tại thời điểm lập; chờ đại lý kiểm tra.";
    $("discrepancy-alert-box").hidden = d.status !== "DISPUTED";
    $("discrepancy-alert-content").textContent = d.status === "DISPUTED"
      ? "Đại lý ghi nhận: " + formatMoney(d.claimedBalance) + "; chênh lệch: " + formatMoney(d.difference) + "; lý do: " + d.responseReason : "";
  }
  async function respond(status) {
    const detail = state.detail;
    if (state.busy || state.uncertain || !dealer() || !allowed() || detail?.status !== "PENDING") return;
    const payload = {version: detail.version, status};
    if (status === "DISPUTED") {
      payload.claimedBalance = $("modal-dealer-amount").value.trim();
      payload.reason = $("modal-diff-reason").value.trim();
      const difference = moneyDifference(detail.statement.closingBalance, payload.claimedBalance);
      if (!/^-?\d{1,22}(\.\d{1,2})?$/.test(payload.claimedBalance) || difference === null
          || moneyCents(difference) === 0n || !payload.reason || payload.reason.length > 500) {
        $("modal-disc-error").hidden = false;
        $("modal-disc-error").textContent = "Nhập số dư khác hệ thống (tối đa 2 số lẻ) và lý do không quá 500 ký tự.";
        return;
      }
    } else if (!window.confirm("Xác nhận khớp đúng số liệu biên bản " + detail.code + "?")) return;
    const epoch = state.epoch; state.busy = true; controls(); showError(null);
    try {
      const updated = await request("/portal/debt-reconciliations/" + detail.id + "/response", {method: "POST", body: JSON.stringify(payload)});
      display(updated); $("modal-discrepancy").hidden = true;
      renderList(await request(base() + "?" + filters(state.page)));
    } catch (error) {
      if (epoch === state.epoch && error.name !== "AbortError") {
        reportError(error);
        if (!error.status || error.status >= 500 || error.status === 409) {
          state.uncertain = true;
          showError("Chưa xác định kết quả phản hồi hoặc biên bản đã đổi. Bấm Tải danh sách rồi mở lại biên bản.");
        }
      }
    } finally { if (epoch === state.epoch) { state.busy = false; controls(); } }
  }
  async function exportPdf() {
    if (!allowed() || !state.detail || state.exporting) return;
    const epoch = state.epoch, id = state.detail.id, code = state.detail.code;
    state.exporting = true; controls();
    try {
      const blob = await request(buildExportUrl(id, dealer()), {}, true);
      if (epoch !== state.epoch || state.detail?.id !== id) return;
      const url = URL.createObjectURL(blob), a = document.createElement("a");
      a.href = url; a.download = "Bien-ban-" + code.replace(/[^\w.-]/g, "_") + ".pdf";
      a.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (error) { reportError(error); }
    finally { if (epoch === state.epoch) { state.exporting = false; controls(); } }
  }
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


  async function init() {
    window.addEventListener("auth:permissions-changed", permissionChange);
    await Auth.refreshPermissions();
    $("reconcile-filter-form").addEventListener("submit", e => {e.preventDefault(); loadList(0);});
    $("btn-create-reconcile").addEventListener("click", createRecord);
    $("btn-find-dealers").addEventListener("click", () => loadOptions().catch(reportError));
    $("reconcile-prev").addEventListener("click", () => loadList(state.page - 1));
    $("reconcile-next").addEventListener("click", () => loadList(state.page + 1));
    $("btn-export-pdf").addEventListener("click", exportPdf);
    $("btn-print").addEventListener("click", () => { if (allowed() && state.detail) window.print(); });
    $("btn-dealer-confirm-match").addEventListener("click", () => respond("CONFIRMED"));
    $("btn-dealer-report-diff").addEventListener("click", () => {
      if (!dealer() || state.detail?.status !== "PENDING" || !allowed()) return;
      $("form-discrepancy").reset(); $("modal-disc-error").hidden = true;
      $("modal-system-balance").value = formatMoney(state.detail.statement.closingBalance);
      $("modal-discrepancy").hidden = false;
    });
    for (const id of ["btn-close-modal", "btn-cancel-modal"]) $(id).addEventListener("click", () => $("modal-discrepancy").hidden = true);
    $("modal-dealer-amount").addEventListener("input", () => {
      $("modal-diff-amount").value = formatMoney(moneyDifference(state.currentStatement?.closingBalance, $("modal-dealer-amount").value));
    });
    $("form-discrepancy").addEventListener("submit", e => {e.preventDefault(); respond("DISPUTED");});
    document.querySelectorAll(".btn-chip").forEach(btn => btn.addEventListener("click", () => {setQuickRange(btn.dataset.range); invalidate(); controls();}));
    for (const id of ["filter-from", "filter-to", "filter-dealer"])
      $(id).addEventListener("change", () => {invalidate(); controls();});
    setQuickRange("this-month");
    if (!allowed()) {permissionChange(); return;}
    const params = new URLSearchParams(location.search);
    if (params.get("from")) $("filter-from").value = params.get("from");
    if (params.get("to")) $("filter-to").value = params.get("to");
    try {
      await loadOptions();
      if (params.get("dealerId") && !dealer()) $("filter-dealer").value = params.get("dealerId");
      controls(); await loadList(0);
      if (params.get("id")) await openRecord(params.get("id"));
    } catch (error) {reportError(error);}
  }
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", init);
  else init();
})();

