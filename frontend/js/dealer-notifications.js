/**
 * dealer-notifications.js — S7-08: Thông báo tự động nhắc hạn thanh toán và công nợ Cổng Đại lý.
 * 
 * Nghiệp vụ S7-08:
 * - Đại lý nhận thông báo nhắc hạn thanh toán trước 3 ngày và khi quá hạn.
 * - Danh sách thông báo trong ứng dụng kèm phân trang và lọc chưa đọc / loại thông báo.
 * - Đếm số lượng thông báo chưa đọc, hiển thị badge trực quan.
 * - Đánh dấu đã đọc từng thông báo hoặc toàn bộ.
 * - Liên kết nhanh đến hóa đơn liên quan trên Cổng đại lý.
 * - Tuân thủ ma trận quyền cơ sở dữ liệu: NOTIFICATION_VIEW và vai trò ROLE_ADMIN.
 */

(async () => {
  if (typeof Auth !== "undefined" && !Auth.requireLogin()) return;
  if (typeof Auth !== "undefined") await Auth.ready;

  const $ = (id) => document.getElementById(id);

  const escapeHtml = (value) =>
    String(value ?? "").replace(/[&<>'"]/g, (char) => ({
      "&": "&amp;",
      "<": "&lt;",
      ">": "&gt;",
      "'": "&#39;",
      '"': "&quot;",
    })[char]);

  const formatDateTime = (value) => {
    if (!value) return "Chưa xác định";
    const date = new Date(value);
    return Number.isNaN(date.getTime())
      ? "Chưa xác định"
      : new Intl.DateTimeFormat("vi-VN", {
          day: "2-digit",
          month: "2-digit",
          year: "numeric",
          hour: "2-digit",
          minute: "2-digit",
        }).format(date);
  };

  // ── Permission & Role Guard ──────────────────────────────────
  function checkPermissions() {
    const isAdmin = Auth.hasRole("ROLE_ADMIN");
    const isDealer = Auth.hasRole("ROLE_DEALER");

    if (!isAdmin && !isDealer) {
      if (typeof sessionStorage !== "undefined") {
        sessionStorage.setItem("errorMessage", "Trang này chỉ dành cho Đại lý và Quản trị viên.");
      }
      window.location.href = "/error?status=403";
      return false;
    }

    const hasNotifView = isAdmin || Auth.hasPermission("NOTIFICATION_VIEW");
    const workspace = $("dn-workspace");
    const accessNotice = $("dn-access-denied");

    if (!hasNotifView) {
      if (workspace) workspace.hidden = true;
      if (accessNotice) {
        accessNotice.hidden = false;
        accessNotice.textContent = "Bạn chưa có quyền xem thông báo (NOTIFICATION_VIEW). Liên hệ quản trị viên để cấp quyền.";
      }
      return false;
    }

    if (workspace) workspace.hidden = false;
    if (accessNotice) accessNotice.hidden = true;
    return true;
  }

  if (!checkPermissions()) return;

  // Lắng nghe sự kiện thu hồi/cấp quyền từ server
  window.addEventListener("auth:permissions-changed", () => {
    if (!checkPermissions()) return;
    loadAll();
  });

  // ── State ───────────────────────────────────────────────────
  const state = {
    notifications: [],
    filteredNotifications: [],
    totalElements: 0,
    totalPages: 0,
    currentPage: 0,
    pageSize: 20,
    unreadOnly: false,
    selectedType: "",
    unreadCount: 0,
    loading: false,
  };

  // ── Alert Helper ─────────────────────────────────────────────
  function showAlert(message, type = "error") {
    const alertBox = $("dn-alert");
    if (!alertBox) return;
    if (!message) {
      alertBox.hidden = true;
      alertBox.textContent = "";
      alertBox.className = "dn-alert";
      return;
    }
    alertBox.textContent = message;
    alertBox.className = `dn-alert dn-alert--${type}`;
    alertBox.hidden = false;
    if (type === "success") {
      setTimeout(() => {
        alertBox.hidden = true;
      }, 5000);
    }
  }

  // ── Admin Switcher ───────────────────────────────────────────
  let hasValidDealer = true;
  if (DealerPortalContext.isAdmin) {
    try {
      hasValidDealer = await DealerPortalContext.init("dp-dealer-switcher-wrap", "dp-dealer-select");
      if (!hasValidDealer) {
        showAlert("Vui lòng chọn đại lý cần xem thông báo từ danh sách ở góc phải.", "error");
        const list = $("dn-list");
        if (list) {
          list.innerHTML = `
            <div class="dn-empty">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/></svg>
              <h3>Chưa chọn đại lý</h3>
              <p>Vui lòng chọn đại lý từ danh sách ở trên để xem thông báo.</p>
            </div>
          `;
        }
        return;
      }
    } catch (err) {
      showAlert(err.message || "Không thể khởi tạo danh sách đại lý.", "error");
      return;
    }
  }

  // ── Unread Count ─────────────────────────────────────────────
  async function loadUnreadCount() {
    try {
      const endpoint = "/portal/notifications/unread-count";
      const response = await Auth.apiFetch(DealerPortalContext.url(endpoint));
      if (!response.ok) return;

      const res = await response.json();
      const count = res.data?.unreadCount ?? 0;
      state.unreadCount = count;

      const badge = $("dn-unread-badge");
      const tabCount = $("dn-unread-tab-count");
      const readAllBtn = $("btn-read-all");

      if (count > 0) {
        if (badge) {
          badge.textContent = count > 99 ? "99+" : String(count);
          badge.hidden = false;
        }
        if (tabCount) tabCount.textContent = `(${count})`;
        if (readAllBtn) readAllBtn.disabled = false;
      } else {
        if (badge) badge.hidden = true;
        if (tabCount) tabCount.textContent = "";
        if (readAllBtn) readAllBtn.disabled = true;
      }
    } catch (err) {
      console.warn("Lỗi khi tải số lượng thông báo chưa đọc:", err);
    }
  }

  // ── Notifications List ───────────────────────────────────────
  async function loadNotifications(page = 0) {
    state.currentPage = page;
    state.loading = true;

    const listContainer = $("dn-list");
    listContainer.innerHTML = `<div class="dn-loading">Đang tải thông báo…</div>`;

    const params = new URLSearchParams({
      page: String(page),
      size: String(state.pageSize),
    });
    if (state.unreadOnly) {
      params.append("unreadOnly", "true");
    }

    try {
      const endpoint = `/portal/notifications?${params.toString()}`;
      const response = await Auth.apiFetch(DealerPortalContext.url(endpoint));

      if (!response.ok) {
        if (response.status === 403) {
          throw new Error("Bạn không có quyền truy cập thông báo (403 Forbidden).");
        }
        throw new Error(`Máy chủ phản hồi lỗi ${response.status}`);
      }

      const res = await response.json();
      const pageData = res.data || res;

      state.notifications = pageData.content || (Array.isArray(pageData) ? pageData : []);
      state.totalElements = pageData.totalElements ?? state.notifications.length;
      state.totalPages = pageData.totalPages ?? (Math.ceil(state.totalElements / state.pageSize) || 1);

      applyTypeFilter();
      renderPagination();
    } catch (err) {
      showAlert(err.message || "Lỗi tải thông báo.", "error");
      listContainer.innerHTML = `
        <div class="dn-empty">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg>
          <h3>Không thể tải thông báo</h3>
          <p>${escapeHtml(err.message)}</p>
        </div>
      `;
    } finally {
      state.loading = false;
    }
  }

  function applyTypeFilter() {
    if (!state.selectedType) {
      state.filteredNotifications = state.notifications;
    } else {
      state.filteredNotifications = state.notifications.filter(
        (n) => n.type === state.selectedType
      );
    }
    renderNotifications(state.filteredNotifications);
  }

  function renderNotifications(items) {
    const listContainer = $("dn-list");

    if (!items || items.length === 0) {
      listContainer.innerHTML = `
        <div class="dn-empty">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9"/>
            <path d="M13.73 21a2 2 0 0 1-3.46 0"/>
          </svg>
          <h3>Chưa có thông báo nào</h3>
          <p>${state.unreadOnly ? "Bạn đã đọc hết tất cả thông báo." : "Hiện tại không có thông báo nào phù hợp với bộ lọc."}</p>
        </div>
      `;
      return;
    }

    const html = items.map((notif) => {
      const isUnread = !notif.isRead;
      const isOverdue = notif.type === "PAYMENT_OVERDUE";
      const isDueSoon = notif.type === "PAYMENT_DUE_BEFORE_3_DAYS";

      let itemClass = "dn-item";
      let iconClass = "dn-item__icon dn-item__icon--system";
      let tagClass = "dn-item__tag dn-item__tag--system";
      let iconSvg = `<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="12" y1="16" x2="12" y2="12"/><line x1="12" y1="8" x2="12.01" y2="8"/></svg>`;

      if (isOverdue) {
        itemClass += " dn-item--overdue";
        iconClass = "dn-item__icon dn-item__icon--overdue";
        tagClass = "dn-item__tag dn-item__tag--overdue";
        iconSvg = `<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2"><path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/></svg>`;
      } else if (isDueSoon) {
        itemClass += " dn-item--due-soon";
        iconClass = "dn-item__icon dn-item__icon--due-soon";
        tagClass = "dn-item__tag dn-item__tag--due-soon";
        iconSvg = `<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg>`;
      }

      if (isUnread) {
        itemClass += " dn-item--unread";
      }

      // Link đến hóa đơn liên quan (nếu có)
      let invoiceBtn = "";
      if (notif.referenceCode) {
        invoiceBtn = `
          <a href="/dealer-invoices" class="btn btn--secondary btn--sm" style="font-size: 0.75rem; padding: 4px 10px; text-decoration: none;">
            Xem hóa đơn ${escapeHtml(notif.referenceCode)} &rarr;
          </a>
        `;
      }

      let markReadBtn = "";
      if (isUnread) {
        markReadBtn = `
          <button type="button" class="btn btn--secondary btn--sm btn-mark-read" data-id="${notif.id}" style="font-size: 0.75rem; padding: 4px 10px;">
            Đã đọc
          </button>
        `;
      }

      return `
        <article class="${itemClass}" data-id="${notif.id}">
          <div class="${iconClass}" aria-hidden="true">
            ${iconSvg}
          </div>
          <div class="dn-item__content">
            <header class="dn-item__header">
              <h2 class="dn-item__title">
                ${isUnread ? '<span class="dn-dot" aria-label="Chưa đọc"></span>' : ''}
                ${escapeHtml(notif.title || "Thông báo")}
              </h2>
              <time class="dn-item__time" datetime="${notif.createdAt}">${formatDateTime(notif.createdAt)}</time>
            </header>
            <p class="dn-item__message">${escapeHtml(notif.message || "")}</p>
            <div class="dn-item__meta">
              <span class="${tagClass}">${escapeHtml(notif.typeLabel || notif.type || "Thông báo")}</span>
              ${notif.referenceCode ? `<span>Mã: <strong>${escapeHtml(notif.referenceCode)}</strong></span>` : ''}
              <div class="dn-item__actions">
                ${invoiceBtn}
                ${markReadBtn}
              </div>
            </div>
          </div>
        </article>
      `;
    }).join("");

    listContainer.innerHTML = html;

    // Gắn sự kiện đánh dấu đã đọc
    listContainer.querySelectorAll(".btn-mark-read").forEach((btn) => {
      btn.addEventListener("click", () => markAsRead(btn.dataset.id));
    });
  }

  // ── Pagination ──────────────────────────────────────────────
  function renderPagination() {
    const prevBtn = $("dn-prev-page");
    const nextBtn = $("dn-next-page");
    const pageInfo = $("dn-page-info");
    const summary = $("dn-page-summary");

    const start = state.totalElements === 0 ? 0 : state.currentPage * state.pageSize + 1;
    const end = Math.min((state.currentPage + 1) * state.pageSize, state.totalElements);

    if (summary) summary.textContent = `Hiển thị ${start}–${end} trên tổng ${state.totalElements} thông báo`;
    if (pageInfo) pageInfo.textContent = `Trang ${state.currentPage + 1} / ${Math.max(1, state.totalPages)}`;

    if (prevBtn) prevBtn.disabled = state.currentPage <= 0;
    if (nextBtn) nextBtn.disabled = state.currentPage >= state.totalPages - 1;
  }

  $("dn-prev-page")?.addEventListener("click", () => {
    if (state.currentPage > 0) loadNotifications(state.currentPage - 1);
  });

  $("dn-next-page")?.addEventListener("click", () => {
    if (state.currentPage < state.totalPages - 1) loadNotifications(state.currentPage + 1);
  });

  // ── Mark as Read (1 item) ────────────────────────────────────
  async function markAsRead(id) {
    if (!id) return;
    try {
      const endpoint = `/portal/notifications/${id}/read`;
      const response = await Auth.apiFetch(DealerPortalContext.url(endpoint), {
        method: "PUT",
      });

      if (!response.ok) {
        throw new Error(`Lỗi đánh dấu đã đọc (${response.status})`);
      }

      // Cập nhật trạng thái item trên giao diện ngay lập tức
      const itemElem = document.querySelector(`.dn-item[data-id="${id}"]`);
      if (itemElem) {
        itemElem.classList.remove("dn-item--unread");
        const dot = itemElem.querySelector(".dn-dot");
        if (dot) dot.remove();
        const btn = itemElem.querySelector(".btn-mark-read");
        if (btn) btn.remove();
      }

      // Giảm unreadCount
      await loadUnreadCount();
      showAlert("Đã đánh dấu thông báo là đã đọc.", "success");
    } catch (err) {
      showAlert(err.message || "Không thể cập nhật trạng thái đã đọc.", "error");
    }
  }

  // ── Mark All as Read ─────────────────────────────────────────
  async function markAllAsRead() {
    try {
      showAlert("");
      const endpoint = "/portal/notifications/read-all";
      const response = await Auth.apiFetch(DealerPortalContext.url(endpoint), {
        method: "PUT",
      });

      if (!response.ok) {
        throw new Error(`Lỗi đánh dấu tất cả đã đọc (${response.status})`);
      }

      showAlert("Đã đánh dấu toàn bộ thông báo là đã đọc.", "success");
      await loadAll();
    } catch (err) {
      showAlert(err.message || "Không thể đánh dấu tất cả là đã đọc.", "error");
    }
  }

  // ── Filter Events ────────────────────────────────────────────
  $("tab-all")?.addEventListener("click", () => {
    state.unreadOnly = false;
    $("tab-all").classList.add("dn-tab--active");
    $("tab-all").setAttribute("aria-selected", "true");
    $("tab-unread").classList.remove("dn-tab--active");
    $("tab-unread").setAttribute("aria-selected", "false");
    loadNotifications(0);
  });

  $("tab-unread")?.addEventListener("click", () => {
    state.unreadOnly = true;
    $("tab-unread").classList.add("dn-tab--active");
    $("tab-unread").setAttribute("aria-selected", "true");
    $("tab-all").classList.remove("dn-tab--active");
    $("tab-all").setAttribute("aria-selected", "false");
    loadNotifications(0);
  });

  $("dn-type-filter")?.addEventListener("change", (e) => {
    state.selectedType = e.target.value;
    applyTypeFilter();
  });

  $("btn-reload")?.addEventListener("click", () => {
    loadAll();
  });

  $("btn-read-all")?.addEventListener("click", () => {
    markAllAsRead();
  });

  async function loadAll() {
    showAlert("");
    await Promise.all([loadUnreadCount(), loadNotifications(0)]);
  }

  // Khởi động
  loadAll();
})();

