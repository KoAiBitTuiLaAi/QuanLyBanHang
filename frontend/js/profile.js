(() => {
  if (!Auth.requireLogin()) return;
  const $ = (id) => document.getElementById(id);
  const roleName = (role) => MenuConfig.getRoleDisplayName(role);
  const allowedAvatarTypes = new Set(["image/jpeg", "image/png"]);
  const maxAvatarSize = 2 * 1024 * 1024;
  let currentProfile = null;

  function showMessage(message, error = false) {
    const element = $("profile-message");
    element.textContent = message;
    element.className = `profile-message${error ? " profile-message--error" : ""}`;
    element.hidden = !message;
  }

  function names(items) {
    return items?.length ? items.map((item) => item.name).join(", ") : "Không áp dụng";
  }

  function showAvatarMessage(message = "", error = false) {
    const element = $("profile-avatar-message");
    element.textContent = message;
    element.className = `profile-avatar-message${error ? " profile-avatar-message--error" : ""}`;
    element.hidden = !message;
  }

  function validateAvatarFile(file) {
    if (!file) return "Vui lòng chọn ảnh đại diện.";
    if (!allowedAvatarTypes.has(file.type)) return "Chỉ chấp nhận ảnh JPG, JPEG hoặc PNG.";
    if (file.size > maxAvatarSize) return "Ảnh đại diện không được vượt quá 2MB.";
    return "";
  }

  async function renderProfileAvatar(profile = currentProfile) {
    const image = $("profile-avatar-image");
    const fallback = $("profile-avatar-fallback");
    const cachedUser = Auth.getUser() || {};
    const displayName = profile?.fullName || profile?.username || cachedUser.fullName || cachedUser.username || "U";
    const profileHasAvatarData = profile
      && (Object.hasOwn(profile, "avatarUrl") || Object.hasOwn(profile, "avatarThumbnailUrl"));
    const avatarUrl = profileHasAvatarData
      ? profile.avatarUrl || profile.avatarThumbnailUrl
      : cachedUser.avatarUrl || cachedUser.avatarThumbnailUrl;

    fallback.textContent = displayName.trim().charAt(0).toUpperCase();
    image.alt = `Ảnh đại diện của ${displayName}`;
    image.hidden = true;
    fallback.hidden = false;
    if (!avatarUrl && image.dataset.objectUrl) {
      URL.revokeObjectURL(image.dataset.objectUrl);
      delete image.dataset.objectUrl;
      image.removeAttribute("src");
    }
    if (avatarUrl && await Auth.setProtectedImage(image, avatarUrl)) {
      image.hidden = false;
      fallback.hidden = true;
    }
  }

  async function loadProfile() {
    const response = await Auth.apiFetch("/users/profile");
    const result = await response.json().catch(() => ({}));
    if (!response.ok || !result.success) throw new Error(result.message || "Không tải được hồ sơ.");
    let profile = result.data;
    const profileResponseHasAvatar = Object.hasOwn(profile, "avatarUrl")
      || Object.hasOwn(profile, "avatarThumbnailUrl");
    if (!profileResponseHasAvatar) {
      const meResponse = await Auth.apiFetch("/auth/me");
      const meResult = await meResponse.json().catch(() => ({}));
      if (meResponse.ok && meResult.success && meResult.data) {
        profile = {
          ...profile,
          avatarUrl: meResult.data.avatarUrl || null,
          avatarThumbnailUrl: meResult.data.avatarThumbnailUrl || null,
        };
      }
    }
    currentProfile = profile;
    const cachedUser = Auth.getUser() || {};
    const profileHasAvatarData = Object.hasOwn(profile, "avatarUrl")
      || Object.hasOwn(profile, "avatarThumbnailUrl");
    if (profileHasAvatarData) {
      sessionStorage.setItem("user", JSON.stringify({
        ...cachedUser,
        avatarUrl: profile.avatarUrl || null,
        avatarThumbnailUrl: profile.avatarThumbnailUrl || null,
      }));
    }
    await renderProfileAvatar(profile);
    $("profile-account").textContent = `${profile.fullName || profile.username} · ${profile.email}`;
    $("profile-username").value = profile.username || "";
    $("profile-email").value = profile.email || "";
    $("profile-full-name").value = profile.fullName || "";
    $("profile-phone").value = profile.phone || "";
    $("profile-roles").textContent = (profile.roles || []).map(roleName).join(", ") || "—";
    $("profile-warehouses").textContent = names(profile.warehouses);
    $("profile-territories").textContent = names(profile.territories);
  }

  $("profile-avatar-change").addEventListener("click", () => {
    showAvatarMessage();
    $("profile-avatar-file").click();
  });

  $("profile-avatar-file").addEventListener("change", async (event) => {
    const file = event.target.files?.[0];
    const validationMessage = validateAvatarFile(file);
    if (validationMessage) {
      showAvatarMessage(validationMessage, true);
      event.target.value = "";
      return;
    }

    const button = $("profile-avatar-change");
    button.disabled = true;
    button.textContent = "Đang cập nhật…";
    showAvatarMessage();
    try {
      const formData = new FormData();
      formData.append("file", file);
      const response = await Auth.apiFetch("/auth/avatar", {
        method: "POST",
        body: formData,
      });
      const result = await response.json().catch(() => ({}));
      if (!response.ok || !result.success) {
        throw new Error(result.message || "Không thể tải ảnh đại diện lên.");
      }

      const cachedUser = Auth.getUser() || {};
      sessionStorage.setItem("user", JSON.stringify({
        ...cachedUser,
        avatarUrl: result.data.avatarUrl,
        avatarThumbnailUrl: result.data.avatarThumbnailUrl,
      }));
      currentProfile = {
        ...currentProfile,
        avatarUrl: result.data.avatarUrl,
        avatarThumbnailUrl: result.data.avatarThumbnailUrl,
      };
      await renderProfileAvatar(currentProfile);
      window.dispatchEvent(new CustomEvent('auth:profile-changed'));
      showAvatarMessage(result.message || "Đã cập nhật ảnh đại diện.");
    } catch (error) {
      showAvatarMessage(error.message || "Không thể tải ảnh đại diện lên.", true);
    } finally {
      button.disabled = false;
      button.textContent = "Đổi ảnh đại diện";
      event.target.value = "";
    }
  });

  $("profile-form").addEventListener("submit", async (event) => {
    event.preventDefault();
    const fullName = $("profile-full-name").value.trim();
    const phone = $("profile-phone").value.trim();
    if (!/^(0|\+84|84)[35789]\d{8}$/.test(phone)) {
      showMessage("Số điện thoại không đúng định dạng Việt Nam.", true);
      return;
    }
    const button = $("profile-save");
    button.disabled = true;
    showMessage("");
    try {
      const response = await Auth.apiFetch("/users/profile", {
        method: "PUT",
        body: JSON.stringify({ fullName, phone }),
      });
      const result = await response.json().catch(() => ({}));
      if (!response.ok || !result.success) throw new Error(result.message || "Không cập nhật được hồ sơ.");
      const cached = Auth.getUser() || {};
      sessionStorage.setItem("user", JSON.stringify({ ...cached, fullName, phone }));
      showMessage(result.message || "Đã cập nhật hồ sơ.");
      await loadProfile();
      window.dispatchEvent(new CustomEvent('auth:profile-changed'));
    } catch (error) {
      showMessage(error.message || "Không thể kết nối máy chủ.", true);
    } finally {
      button.disabled = false;
    }
  });

  loadProfile().catch((error) => showMessage(error.message, true));
})();
