(() => {
  if (!Auth.requireLogin()) return;

  const user = Auth.getUser();
  const sidebar = document.getElementById("sidebar");
  const overlay = document.createElement("div");
  overlay.className = "sidebar-overlay";
  document.body.appendChild(overlay);

  const escapeHtml = (value) => String(value ?? "").replace(/[&<>'"]/g, (char) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", "'": "&#39;", '"': "&quot;",
  })[char]);

  function openSidebar(open) {
    sidebar.classList.toggle("sidebar--open", open);
    overlay.classList.toggle("sidebar-overlay--visible", open);
  }

  document.getElementById("sidebar-toggle")?.addEventListener("click", () => openSidebar(true));
  document.getElementById("sidebar-close")?.addEventListener("click", () => openSidebar(false));
  overlay.addEventListener("click", () => openSidebar(false));

  document.getElementById("user-name").textContent = user.fullName || user.username;
  const avatarInitial = (user.fullName || user.username || "U").trim().charAt(0).toUpperCase();
  const avatarImage = document.getElementById("user-avatar-image");
  const avatarFallback = document.getElementById("user-avatar-fallback");
  avatarFallback.textContent = avatarInitial;

  async function renderCurrentAvatar() {
    avatarImage.hidden = true;
    avatarFallback.hidden = false;
    if (user.avatarThumbnailUrl
      && await Auth.setProtectedImage(avatarImage, user.avatarThumbnailUrl)) {
      avatarImage.hidden = false;
      avatarFallback.hidden = true;
    }
  }
  renderCurrentAvatar();
  const roleNames = (user.roles || []).map(MenuConfig.getRoleDisplayName);
  const scope = [...(user.warehouses || []), ...(user.territories || [])].map((item) => item.name);
  document.getElementById("user-role").textContent = [roleNames.join(", "), scope.join(", ")].filter(Boolean).join(" · ");
  document.getElementById("welcome-greeting").textContent = `Chào ${user.fullName || user.username}`;
  document.getElementById("current-time").textContent = new Intl.DateTimeFormat("vi-VN", {
    weekday: "long", day: "2-digit", month: "2-digit", year: "numeric",
  }).format(new Date());

  const workDescriptions = {
    "order-approval": "Xem xét và phê duyệt đơn hàng vượt hạn mức hoặc bán dưới giá sàn.",
    "debt-limits": "Tra cứu và khai báo hạn mức tiền, số ngày nợ tối đa của đại lý.",
    users: "Tạo tài khoản, gán vai trò và quản lý nhân sự.",
    "audit-logs": "Tra cứu thay đổi tồn kho, giá và hạn mức công nợ.",
    products: "Tra cứu mã hàng, đơn vị tính và quy cách đóng gói.",
    "product-groups": "Tra cứu nhóm hàng và sản phẩm trong từng nhóm.",
    "price-lists": "Khai báo giá bán theo nhóm khách hàng và ngày hiệu lực.",
    "discount-policies": "Khai báo chiết khấu theo sản lượng cho SKU hoặc nhóm hàng.",
    suppliers: "Tra cứu thông tin liên hệ và điều khoản thanh toán.",
    "product-units": "Khai báo hệ số quy đổi lon, lốc và thùng theo từng mã hàng.",
    "import-export": "Lập phiếu nhập, xuất và tra cứu chứng từ kho.",
    warehouse: "Xem số lượng tồn và quy đổi theo đơn vị đóng gói.",
    profile: "Cập nhật họ tên, số điện thoại và ảnh đại diện.",
  };
  const workOrder = ["order-approval", "users", "audit-logs", "debt-limits", "price-lists", "discount-policies", "products", "product-groups",
    "import-export", "warehouse", "product-units", "suppliers", "profile"];
  const availableWork = MenuConfig.getMenuForUser(user);
  const work = workOrder.map((id) => availableWork.find((item) => item.id === id))
    .filter(Boolean).slice(0, 5);
  document.getElementById("work-list").innerHTML = work.map((item) => `
    <a class="work-link" href="${escapeHtml(item.href)}">
      <span class="work-link__icon"><svg viewBox="0 0 24 24" aria-hidden="true">${item.icon}</svg></span>
      <span class="work-link__text"><span class="work-link__title">${escapeHtml(item.label)}</span>
        <span class="work-link__description">${escapeHtml(workDescriptions[item.id] || "")}</span></span>
      <span class="work-link__open">Mở</span>
    </a>`).join("");

  function showToast(message, type = "error") {
    let container = document.getElementById("toast-container");
    if (!container) { container = document.createElement("div"); container.id = "toast-container"; document.body.appendChild(container); }
    const toast = document.createElement("div");
    toast.className = `toast toast--${type}`;
    toast.innerHTML = `<span class="toast__message">${escapeHtml(message)}</span>`;
    container.appendChild(toast);
    requestAnimationFrame(() => toast.classList.add("toast--visible"));
    setTimeout(() => { toast.classList.remove("toast--visible"); toast.remove(); }, 4200);
  }

  const avatarModal = document.getElementById("modal-avatar");
  const avatarForm = document.getElementById("form-avatar");
  const avatarFile = document.getElementById("avatar-file");
  const avatarPreviewImage = document.getElementById("avatar-preview-image");
  const avatarPreviewFallback = document.getElementById("avatar-preview-fallback");
  const avatarMessage = document.getElementById("avatar-message");
  const avatarSubmit = document.getElementById("btn-avatar-submit");
  const allowedAvatarTypes = new Set(["image/jpeg", "image/png"]);
  const maxAvatarSize = 2 * 1024 * 1024;
  let avatarPreviewUrl = null;

  function clearAvatarPreviewUrl() {
    if (avatarPreviewUrl) {
      URL.revokeObjectURL(avatarPreviewUrl);
      avatarPreviewUrl = null;
    }
  }

  function showAvatarMessage(message = "") {
    avatarMessage.textContent = message;
    avatarMessage.hidden = !message;
  }

  function resetAvatarPreview() {
    clearAvatarPreviewUrl();
    avatarPreviewFallback.textContent = avatarInitial;
    if (!avatarImage.hidden && avatarImage.src) {
      avatarPreviewImage.src = avatarImage.src;
      avatarPreviewImage.hidden = false;
      avatarPreviewFallback.hidden = true;
    } else {
      avatarPreviewImage.removeAttribute("src");
      avatarPreviewImage.hidden = true;
      avatarPreviewFallback.hidden = false;
    }
  }

  function validateAvatarFile(file) {
    if (!file) return "Vui lòng chọn ảnh đại diện.";
    if (!allowedAvatarTypes.has(file.type)) {
      return "Chỉ chấp nhận ảnh JPG, JPEG hoặc PNG.";
    }
    if (file.size > maxAvatarSize) {
      return "Ảnh đại diện không được vượt quá 2MB.";
    }
    return "";
  }

  function setAvatarModal(open) {
    avatarModal.hidden = !open;
    if (open) {
      avatarForm.reset();
      showAvatarMessage();
      resetAvatarPreview();
      avatarFile.focus();
    } else {
      clearAvatarPreviewUrl();
    }
  }

  document.getElementById("btn-avatar")?.addEventListener("click", () => setAvatarModal(true));
  document.querySelectorAll("[data-close-avatar]").forEach((button) => {
    button.addEventListener("click", () => setAvatarModal(false));
  });

  avatarFile?.addEventListener("change", () => {
    const file = avatarFile.files?.[0];
    const validationMessage = validateAvatarFile(file);
    showAvatarMessage(validationMessage);
    clearAvatarPreviewUrl();
    if (validationMessage) {
      resetAvatarPreview();
      return;
    }
    avatarPreviewUrl = URL.createObjectURL(file);
    avatarPreviewImage.src = avatarPreviewUrl;
    avatarPreviewImage.hidden = false;
    avatarPreviewFallback.hidden = true;
  });

  avatarForm?.addEventListener("submit", async (event) => {
    event.preventDefault();
    const file = avatarFile.files?.[0];
    const validationMessage = validateAvatarFile(file);
    if (validationMessage) {
      showAvatarMessage(validationMessage);
      return;
    }

    avatarSubmit.disabled = true;
    avatarSubmit.textContent = "Đang lưu...";
    showAvatarMessage();
    try {
      const formData = new FormData();
      formData.append("file", file);
      const response = await Auth.apiFetch("/auth/avatar", {
        method: "POST",
        body: formData,
      });
      const result = await response.json().catch(() => null);
      if (!response.ok || !result?.success) {
        throw new Error(result?.message || "Không thể tải ảnh đại diện lên.");
      }
      user.avatarUrl = result.data.avatarUrl;
      user.avatarThumbnailUrl = result.data.avatarThumbnailUrl;
      sessionStorage.setItem("user", JSON.stringify(user));
      await renderCurrentAvatar();
      setAvatarModal(false);
      showToast(result.message || "Đã cập nhật ảnh đại diện.", "success");
    } catch (error) {
      showAvatarMessage(error.message || "Không thể tải ảnh đại diện lên.");
    } finally {
      avatarSubmit.disabled = false;
      avatarSubmit.textContent = "Lưu ảnh";
    }
  });

  document.querySelectorAll("#btn-logout, #btn-logout-topbar").forEach((button) => button.addEventListener("click", Auth.logout));
  const modal = document.getElementById("modal-change-password");
  function setModal(open) { modal.hidden = !open; if (open) document.getElementById("cp-current")?.focus(); }
  document.getElementById("btn-change-password")?.addEventListener("click", () => setModal(true));
  document.querySelectorAll("[data-close-modal]").forEach((button) => button.addEventListener("click", () => setModal(false)));

  document.getElementById("form-change-password")?.addEventListener("submit", async (event) => {
    event.preventDefault();
    const currentPassword = document.getElementById("cp-current").value;
    const newPassword = document.getElementById("cp-new").value;
    const confirm = document.getElementById("cp-confirm").value;
    if (!/^(?=.*[A-Za-z])(?=.*\d).{8,}$/.test(newPassword)) return showToast("Mật khẩu mới cần ít nhất 8 ký tự, có chữ và số.");
    if (newPassword !== confirm) return showToast("Xác nhận mật khẩu chưa khớp.");
    const button = document.getElementById("btn-cp-submit");
    button.disabled = true;
    try {
      const response = await Auth.apiFetch("/auth/change-password", { method: "POST", body: JSON.stringify({ currentPassword, newPassword }) });
      const result = await response.json();
      if (!response.ok || !result.success) throw new Error(result.message || "Không đổi được mật khẩu");
      showToast(result.message, "success");
      setTimeout(() => { Auth.clearSession(); window.location.href = "/login"; }, 1100);
    } catch (error) { showToast(error.message); }
    finally { button.disabled = false; }
  });

  if (user.mustChangePassword) {
    showToast("Bạn đang dùng mật khẩu tạm. Hãy đổi mật khẩu để tiếp tục.", "warning");
    setModal(true);
  }

})();
