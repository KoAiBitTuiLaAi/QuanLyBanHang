/**
 * discount-policies.js — Quản lý chính sách chiết khấu theo sản lượng (S3-01).
 *
 * Nghiệp vụ:
 *  - Bậc chiết khấu theo số lượng mua của một SKU hoặc nhóm hàng
 *  - Chiết khấu tính theo % hoặc theo số tiền cố định (VNĐ) trên đơn vị
 *  - Khi nhiều chính sách cùng áp dụng → lấy mức có lợi nhất cho khách
 *
 * API endpoints (chờ backend):
 *  GET    /discount-policies            → { success, data: Policy[] }
 *  POST   /discount-policies            → { success, data: Policy, message }
 *  PUT    /discount-policies/:id        → { success, data: Policy, message }
 *  DELETE /discount-policies/:id        → { success, message }
 *  PATCH  /discount-policies/:id/status → { success, message }
 *  GET    /products                     → { success, data: Product[] }
 *  GET    /categories                   → { success, data: Category[] }
 */
(() => {
  if (!Auth.requireLogin()) return;

  const canManage = Auth.hasRole("ROLE_SALES_MANAGER");

  /* ── State ── */
  const state = {
    policies: [],
    products: [],
    categories: [],
    editingPolicy: null,
    tiers: [], // working tiers inside the modal
  };

  /* ── Helpers ── */
  const $ = (id) => document.getElementById(id);
  const escapeHtml = (v) =>
    String(v ?? "").replace(/[&<>'"]/g, (c) =>
      ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", "'": "&#39;", '"': "&quot;" })[c]
    );

  const formatDate = (dateStr) => {
    if (!dateStr) return "";
    try {
      return new Intl.DateTimeFormat("vi-VN", {
        day: "2-digit",
        month: "2-digit",
        year: "numeric",
      }).format(new Date(dateStr));
    } catch {
      return dateStr;
    }
  };

  const formatDiscount = (tier, discountType) => {
    if (discountType === "PERCENT") return `${tier.discountValue}%`;
    return new Intl.NumberFormat("vi-VN", {
      style: "currency",
      currency: "VND",
      maximumFractionDigits: 0,
    }).format(Number(tier.discountValue) || 0);
  };

  /* ── Toast ── */
  function showToast(message, type = "success") {
    let container = $("toast-container");
    if (!container) {
      container = document.createElement("div");
      container.id = "toast-container";
      document.body.appendChild(container);
    }
    const toast = document.createElement("div");
    toast.className = `toast toast--${type}`;
    toast.setAttribute("role", "status");
    toast.innerHTML = `<span class="toast__message">${escapeHtml(message)}</span>`;
    container.appendChild(toast);
    requestAnimationFrame(() => toast.classList.add("toast--visible"));
    setTimeout(() => {
      toast.classList.remove("toast--visible");
      setTimeout(() => toast.remove(), 300);
    }, 4200);
  }

  /* ── Form message ── */
  function setFormMessage(message = "") {
    const el = $("policy-form-message");
    el.textContent = message;
    el.hidden = !message;
  }

  /* ── Load reference data ── */
  async function loadReferenceData() {
    try {
      const [prodRes, catRes] = await Promise.all([
        Auth.apiFetch("/products"),
        Auth.apiFetch("/categories"),
      ]);
      const [prodData, catData] = await Promise.all([prodRes.json(), catRes.json()]);
      state.products = prodData.success ? (prodData.data || []) : [];
      state.categories = catData.success ? (catData.data || []) : [];
    } catch {
      // Non-fatal: we can still show policies
    }
  }

  /* ── Load policies ── */
  async function loadPolicies() {
    $("policies-body").innerHTML =
      '<tr><td class="catalog-empty" colspan="6">Đang tải danh sách chính sách…</td></tr>';
    try {
      const res = await Auth.apiFetch("/discount-policies");
      const result = await res.json();
      if (!res.ok || !result.success) throw new Error(result.message || "Không tải được chính sách.");
      state.policies = result.data || [];
      renderPolicies();
    } catch (err) {
      $("policy-summary").textContent = "Không tải được dữ liệu";
      $("policies-body").innerHTML = `<tr><td class="catalog-empty" colspan="6">${escapeHtml(err.message || "Không thể kết nối tới máy chủ.")}</td></tr>`;
    }
  }

  /* ── Filtered list ── */
  function filteredPolicies() {
    const keyword = $("policy-search").value.trim().toLocaleLowerCase("vi");
    const scope = $("scope-filter").value;
    const status = $("status-filter").value;

    return state.policies.filter((p) => {
      const active = p.active !== false;
      const matchesKeyword =
        !keyword ||
        String(p.name || "").toLocaleLowerCase("vi").includes(keyword) ||
        String(p.scopeTargetCode || "").toLocaleLowerCase("vi").includes(keyword) ||
        String(p.scopeTargetName || "").toLocaleLowerCase("vi").includes(keyword);
      const matchesScope = !scope || p.scopeType === scope;
      const matchesStatus =
        !status ||
        (status === "active" && active) ||
        (status === "inactive" && !active);
      return matchesKeyword && matchesScope && matchesStatus;
    });
  }

  /* ── Render table ── */
  function renderPolicies() {
    const policies = filteredPolicies();
    $("policy-summary").textContent = `${policies.length} / ${state.policies.length} chính sách`;

    if (!policies.length) {
      $("policies-body").innerHTML =
        '<tr><td class="catalog-empty" colspan="6">Không tìm thấy chính sách nào. Hãy thay đổi bộ lọc.</td></tr>';
      return;
    }

    $("policies-body").innerHTML = policies
      .map((policy) => {
        const active = policy.active !== false;
        const tiers = policy.tiers || [];
        const discountType = policy.discountType || "PERCENT";

        const tierChips = tiers
          .slice()
          .sort((a, b) => a.minQty - b.minQty)
          .map(
            (t) =>
              `<span class="tier-chip" data-view-tiers="${policy.id}" title="Xem tất cả bậc">
                ≥${t.minQty} → ${formatDiscount(t, discountType)}
              </span>`
          )
          .join("");

        const scopeClass =
          policy.scopeType === "SKU" ? "scope-badge--sku" : "scope-badge--group";
        const scopeLabel = policy.scopeType === "SKU" ? "SKU" : "Nhóm hàng";

        const startLabel = formatDate(policy.startDate);
        const endLabel = formatDate(policy.endDate);
        const validityHtml = startLabel || endLabel
          ? `${startLabel || "—"}<span class="validity-end">đến ${endLabel || "vô thời hạn"}</span>`
          : `<span style="color:var(--ink-400)">Vô thời hạn</span>`;

        const actions = canManage
          ? `<button class="policy-btn" type="button" data-edit-policy="${policy.id}">Sửa</button>
             <button class="policy-btn" type="button" data-toggle-policy="${policy.id}">${active ? "Tắt" : "Bật"}</button>
             <button class="policy-btn policy-btn--danger" type="button" data-delete-policy="${policy.id}">Xoá</button>`
          : "";

        return `
          <tr>
            <td>
              <div class="policy-name-cell">
                <strong>${escapeHtml(policy.name)}</strong>
                <small>${discountType === "PERCENT" ? "Chiết khấu theo %" : "Chiết khấu theo số tiền cố định"}</small>
              </div>
            </td>
            <td>
              <span class="scope-badge ${scopeClass}">${scopeLabel}</span>
              <span class="scope-target">${escapeHtml(policy.scopeTargetName || policy.scopeTargetCode || "—")}</span>
            </td>
            <td>
              <div class="tiers-summary">
                ${tierChips || '<span style="color:var(--ink-400);font-size:.75rem">Chưa có bậc</span>'}
              </div>
            </td>
            <td class="validity-cell">${validityHtml}</td>
            <td>
              <span class="policy-status ${active ? "" : "policy-status--inactive"}">
                ${active ? "Đang áp dụng" : "Đã tắt"}
              </span>
            </td>
            <td><div class="policy-actions">${actions}</div></td>
          </tr>`;
      })
      .join("");
  }

  /* ── Tiers in modal ── */
  function renderTiersEditor() {
    const list = $("tiers-list");
    const empty = $("tiers-empty");
    const discountType = $("policy-discount-type").value;
    $("discount-col-label").textContent =
      discountType === "PERCENT" ? "Chiết khấu (%)" : "Chiết khấu (VNĐ/đv)";

    if (!state.tiers.length) {
      list.innerHTML = "";
      empty.hidden = false;
      return;
    }

    empty.hidden = true;
    list.innerHTML = state.tiers
      .map(
        (tier, index) => `
        <div class="tier-row" data-tier-index="${index}">
          <input
            type="number"
            min="0"
            step="1"
            placeholder="0"
            value="${tier.minQty ?? ""}"
            data-tier-field="minQty"
            aria-label="Số lượng tối thiểu bậc ${index + 1}"
          />
          <input
            type="number"
            min="0"
            step="1"
            placeholder="(không giới hạn)"
            value="${tier.maxQty ?? ""}"
            data-tier-field="maxQty"
            aria-label="Số lượng tối đa bậc ${index + 1}"
          />
          <input
            type="number"
            min="0"
            step="${discountType === "PERCENT" ? "0.01" : "1000"}"
            placeholder="${discountType === "PERCENT" ? "0.00" : "0"}"
            value="${tier.discountValue ?? ""}"
            data-tier-field="discountValue"
            aria-label="Giá trị chiết khấu bậc ${index + 1}"
          />
          <button
            type="button"
            class="tier-remove-btn"
            data-remove-tier="${index}"
            aria-label="Xoá bậc ${index + 1}"
          >
            <svg viewBox="0 0 24 24"><path d="M18 6 6 18M6 6l12 12" /></svg>
          </button>
        </div>`
      )
      .join("");
  }

  /* ── Scope select options ── */
  function populateScopeTarget() {
    const scopeType = $("policy-scope-type").value;
    const select = $("policy-scope-target");
    $("scope-target-label").textContent =
      scopeType === "SKU" ? "Chọn sản phẩm (SKU)" : "Chọn nhóm hàng";

    const items =
      scopeType === "SKU"
        ? state.products.map(
            (p) => `<option value="${p.id}">${escapeHtml(p.code + " — " + p.name)}</option>`
          )
        : state.categories.map(
            (c) => `<option value="${c.id}">${escapeHtml(c.name)}</option>`
          );

    select.innerHTML = `<option value="">-- Chọn --</option>${items.join("")}`;
  }

  /* ── Open modal ── */
  function openPolicyModal(policy = null) {
    if (!canManage) return;
    state.editingPolicy = policy;
    state.tiers = policy?.tiers
      ? policy.tiers.map((t) => ({ ...t }))
      : [];

    const form = $("policy-form");
    form.querySelectorAll("[aria-invalid]").forEach((el) =>
      el.removeAttribute("aria-invalid")
    );
    setFormMessage();

    $("policy-id").value = policy?.id || "";
    $("policy-modal-title").textContent = policy
      ? "Cập nhật chính sách chiết khấu"
      : "Thêm chính sách chiết khấu";

    $("policy-name").value = policy?.name || "";
    $("policy-scope-type").value = policy?.scopeType || "SKU";
    $("policy-discount-type").value = policy?.discountType || "PERCENT";
    $("policy-start-date").value = policy?.startDate
      ? policy.startDate.slice(0, 10)
      : "";
    $("policy-end-date").value = policy?.endDate
      ? policy.endDate.slice(0, 10)
      : "";

    populateScopeTarget();
    if (policy?.scopeTargetId) {
      $("policy-scope-target").value = policy.scopeTargetId;
    }

    renderTiersEditor();
    $("policy-modal").hidden = false;
    $("policy-name").focus();
  }

  function closePolicyModal() {
    $("policy-modal").hidden = true;
    state.editingPolicy = null;
    state.tiers = [];
  }

  /* ── Tiers detail drawer ── */
  function openTiersDrawer(policyId) {
    const policy = state.policies.find((p) => String(p.id) === String(policyId));
    if (!policy) return;

    $("tiers-drawer-title").textContent = escapeHtml(policy.name);
    $("tiers-drawer-subtitle").textContent =
      (policy.scopeType === "SKU" ? "SKU: " : "Nhóm hàng: ") +
      (policy.scopeTargetName || policy.scopeTargetCode || "—");

    const discountType = policy.discountType || "PERCENT";
    $("drawer-discount-col").textContent =
      discountType === "PERCENT" ? "Chiết khấu (%)" : "Chiết khấu (VNĐ/đv)";

    const tiers = (policy.tiers || []).slice().sort((a, b) => a.minQty - b.minQty);

    $("tiers-drawer-body").innerHTML = tiers.length
      ? tiers
          .map(
            (t) => `
          <tr>
            <td>${t.minQty ?? 0}</td>
            <td>${t.maxQty != null ? t.maxQty : "—"}</td>
            <td>${formatDiscount(t, discountType)}</td>
          </tr>`
          )
          .join("")
      : `<tr><td class="catalog-empty" colspan="3">Chưa có bậc chiết khấu nào.</td></tr>`;

    $("tiers-drawer").hidden = false;
  }

  /* ── Validate form ── */
  function validateForm() {
    let error = "";

    const requiredFields = ["policy-name", "policy-scope-target"];
    requiredFields.forEach((id) => {
      const el = $(id);
      if (!String(el.value).trim()) {
        el.setAttribute("aria-invalid", "true");
        error = error || "Vui lòng nhập đầy đủ thông tin bắt buộc.";
      } else {
        el.removeAttribute("aria-invalid");
      }
    });

    if (error) return error;

    if (!state.tiers.length) return "Cần thêm ít nhất một bậc chiết khấu.";

    // Validate each tier
    const tierRows = document.querySelectorAll(".tier-row");
    let tierError = "";
    tierRows.forEach((row) => {
      const idx = Number(row.dataset.tierIndex);
      const minInput = row.querySelector('[data-tier-field="minQty"]');
      const valInput = row.querySelector('[data-tier-field="discountValue"]');

      const minQty = Number(minInput.value);
      const discountValue = Number(valInput.value);

      if (!minInput.value.trim() || isNaN(minQty) || minQty < 0) {
        minInput.setAttribute("aria-invalid", "true");
        tierError = tierError || `Bậc ${idx + 1}: Số lượng tối thiểu không hợp lệ.`;
      }

      const discountType = $("policy-discount-type").value;
      if (!valInput.value.trim() || isNaN(discountValue) || discountValue < 0) {
        valInput.setAttribute("aria-invalid", "true");
        tierError = tierError || `Bậc ${idx + 1}: Giá trị chiết khấu không hợp lệ.`;
      }
      if (discountType === "PERCENT" && discountValue > 100) {
        valInput.setAttribute("aria-invalid", "true");
        tierError = tierError || `Bậc ${idx + 1}: Chiết khấu % không được vượt 100%.`;
      }
    });
    if (tierError) return tierError;

    // Check for duplicate minQty
    const minQtys = state.tiers.map((t) => Number(t.minQty));
    const unique = new Set(minQtys);
    if (unique.size !== minQtys.length) {
      return "Các bậc không được có cùng số lượng tối thiểu.";
    }

    // Check startDate ≤ endDate
    const start = $("policy-start-date").value;
    const end = $("policy-end-date").value;
    if (start && end && start > end) {
      return "Ngày bắt đầu phải trước ngày kết thúc.";
    }

    return "";
  }

  /* ── Save policy ── */
  $("policy-form").addEventListener("submit", async (e) => {
    e.preventDefault();

    // Sync tier values from inputs
    document.querySelectorAll(".tier-row").forEach((row) => {
      const idx = Number(row.dataset.tierIndex);
      state.tiers[idx].minQty = Number(
        row.querySelector('[data-tier-field="minQty"]').value
      );
      const maxVal = row.querySelector('[data-tier-field="maxQty"]').value;
      state.tiers[idx].maxQty = maxVal !== "" ? Number(maxVal) : null;
      state.tiers[idx].discountValue = Number(
        row.querySelector('[data-tier-field="discountValue"]').value
      );
    });

    const validationError = validateForm();
    if (validationError) {
      setFormMessage(validationError);
      return;
    }

    const saveBtn = $("save-policy");
    saveBtn.disabled = true;
    saveBtn.textContent = "Đang lưu…";
    setFormMessage();

    const payload = {
      name: $("policy-name").value.trim(),
      scopeType: $("policy-scope-type").value,
      scopeTargetId: Number($("policy-scope-target").value),
      discountType: $("policy-discount-type").value,
      startDate: $("policy-start-date").value || null,
      endDate: $("policy-end-date").value || null,
      tiers: state.tiers
        .slice()
        .sort((a, b) => a.minQty - b.minQty)
        .map((t) => ({
          minQty: t.minQty,
          maxQty: t.maxQty ?? null,
          discountValue: t.discountValue,
        })),
    };

    try {
      const editing = state.editingPolicy;
      const res = await Auth.apiFetch(
        editing ? `/discount-policies/${editing.id}` : "/discount-policies",
        { method: editing ? "PUT" : "POST", body: JSON.stringify(payload) }
      );
      const result = await res.json().catch(() => null);
      if (!res.ok || !result?.success) {
        throw new Error(result?.message || "Không lưu được chính sách.");
      }
      closePolicyModal();
      showToast(result.message || "Đã lưu chính sách.");
      await loadPolicies();
    } catch (err) {
      setFormMessage(err.message || "Không thể kết nối tới máy chủ.");
    } finally {
      saveBtn.disabled = false;
      saveBtn.textContent = "Lưu chính sách";
    }
  });

  /* ── Event delegation ── */

  // Scope type change → repopulate target dropdown
  $("policy-scope-type").addEventListener("change", populateScopeTarget);

  // Discount type change → re-render tiers
  $("policy-discount-type").addEventListener("change", renderTiersEditor);

  // Add tier
  $("add-tier").addEventListener("click", () => {
    state.tiers.push({ minQty: "", maxQty: null, discountValue: "" });
    renderTiersEditor();
    // Focus last row's first input
    const rows = $("tiers-list").querySelectorAll(".tier-row");
    if (rows.length) rows[rows.length - 1].querySelector("input")?.focus();
  });

  // Remove tier (delegated)
  $("tiers-list").addEventListener("click", (e) => {
    const btn = e.target.closest("[data-remove-tier]");
    if (!btn) return;
    const idx = Number(btn.dataset.removeTier);
    state.tiers.splice(idx, 1);
    renderTiersEditor();
  });

  // Create button
  $("create-policy").addEventListener("click", () => openPolicyModal());

  // Table actions (delegated)
  $("policies-body").addEventListener("click", (e) => {
    const editBtn = e.target.closest("[data-edit-policy]");
    const toggleBtn = e.target.closest("[data-toggle-policy]");
    const deleteBtn = e.target.closest("[data-delete-policy]");
    const viewTiersBtn = e.target.closest("[data-view-tiers]");

    if (editBtn) {
      const policy = state.policies.find(
        (p) => String(p.id) === editBtn.dataset.editPolicy
      );
      if (policy) openPolicyModal(policy);
      return;
    }

    if (toggleBtn) {
      const policy = state.policies.find(
        (p) => String(p.id) === toggleBtn.dataset.togglePolicy
      );
      if (!policy) return;
      Auth.apiFetch(
        `/discount-policies/${policy.id}/status?active=${policy.active === false}`,
        { method: "PATCH" }
      )
        .then(async (res) => {
          const result = await res.json();
          if (!res.ok || !result.success) throw new Error(result.message);
          showToast(result.message);
          await loadPolicies();
        })
        .catch((err) =>
          showToast(err.message || "Không cập nhật được trạng thái.", "error")
        );
      return;
    }

    if (deleteBtn) {
      const policy = state.policies.find(
        (p) => String(p.id) === deleteBtn.dataset.deletePolicy
      );
      if (!policy || !confirm(`Xoá chính sách "${policy.name}"?`)) return;
      Auth.apiFetch(`/discount-policies/${policy.id}`, { method: "DELETE" })
        .then(async (res) => {
          const result = await res.json();
          if (!res.ok || !result.success) throw new Error(result.message);
          showToast(result.message);
          await loadPolicies();
        })
        .catch((err) =>
          showToast(err.message || "Không xoá được chính sách.", "error")
        );
      return;
    }

    if (viewTiersBtn) {
      openTiersDrawer(viewTiersBtn.dataset.viewTiers);
    }
  });

  // Close modals
  document.querySelectorAll("[data-close-policy]").forEach((btn) =>
    btn.addEventListener("click", closePolicyModal)
  );
  $("close-tiers-drawer").addEventListener("click", () => {
    $("tiers-drawer").hidden = true;
  });

  // Click outside to close
  $("policy-modal").addEventListener("click", (e) => {
    if (e.target === $("policy-modal")) closePolicyModal();
  });
  $("tiers-drawer").addEventListener("click", (e) => {
    if (e.target === $("tiers-drawer")) $("tiers-drawer").hidden = true;
  });

  // Escape key
  document.addEventListener("keydown", (e) => {
    if (e.key !== "Escape") return;
    if (!$("policy-modal").hidden) { closePolicyModal(); return; }
    if (!$("tiers-drawer").hidden) { $("tiers-drawer").hidden = true; }
  });

  // Filters
  [$("policy-search"), $("scope-filter"), $("status-filter")].forEach((el) => {
    el.addEventListener(el.tagName === "INPUT" ? "input" : "change", renderPolicies);
  });
  $("clear-filters").addEventListener("click", () => {
    $("policy-search").value = "";
    $("scope-filter").value = "";
    $("status-filter").value = "";
    renderPolicies();
  });

  /* ── Boot ── */
  $("create-policy").hidden = !canManage;

  Promise.all([loadReferenceData(), loadPolicies()]);
})();
