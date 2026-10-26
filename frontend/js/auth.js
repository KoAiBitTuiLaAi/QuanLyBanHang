/**
 * auth.js — Module chung cho xác thực và quản lý phiên (S1-02).
 *
 * Tính năng:
 * - Auto-refresh token khi còn hoạt động
 * - Kiểm tra phiên hết hạn → redirect login
 * - Đăng xuất (gọi API + xoá phiên trình duyệt)
 * - API helper gắn token tự động
 */
const Auth = (() => {
  const API_BASE = "http://localhost:8080/api";

  // Thời gian trước khi access token hết hạn để refresh (5 phút)
  const REFRESH_BEFORE_MS = 5 * 60 * 1000;

  // Interval kiểm tra token (mỗi 60 giây)
  const CHECK_INTERVAL_MS = 60 * 1000;

  let refreshTimer = null;
  let refreshRequest = null;
  let sessionExpiryHandled = false;
  let permissionRequest = null;
  let sessionGeneration = 0;
  let restoringPage = false;
  let sessionChannel = null;
  const permissionBindings = [];

  // Only notifications travel between tabs; credentials stay out of localStorage and messages.
  try {
    if (typeof BroadcastChannel !== "undefined") {
      sessionChannel = new BroadcastChannel("qlbh-auth-session");
      sessionChannel.onmessage = ({data}) => {
        if (data?.type !== "logout" && data?.type !== "login") return;
        clearSession();
        if (data.type === "logout") {
          sessionStorage.setItem("sessionSignedOut", "1");
          window.location.href = getLoginPageUrl();
        } else {
          sessionStorage.removeItem("sessionSignedOut");
          window.location.reload();
        }
      };
    }
  } catch { /* Backend revocation still applies when the browser disables messaging. */ }

  function notifySession(type) {
    try { sessionChannel?.postMessage({type}); } catch { /* Closing tab. */ }
  }

  function saveSession(data) {
    if (!data?.accessToken || !data.username || !Array.isArray(data.roles)) {
      throw new Error("Máy chủ trả phiên đăng nhập không hợp lệ.");
    }
    sessionStorage.setItem("accessToken", data.accessToken);
    // New browser sessions use an HttpOnly cookie; never persist a returned refresh credential.
    sessionStorage.removeItem("refreshToken");
    sessionStorage.removeItem("sessionSignedOut");
    sessionStorage.setItem("user", JSON.stringify({
      id:data.id, username:data.username, email:data.email, fullName:data.fullName,
      roles:data.roles, permissions:data.permissions || [], warehouses:data.warehouses || [],
      territories:data.territories || [], mustChangePassword:Boolean(data.mustChangePassword),
      avatarUrl:data.avatarUrl || null, avatarThumbnailUrl:data.avatarThumbnailUrl || null,
    }));
    sessionExpiryHandled = false;
  }

  async function login(credentials) {
    const version = sessionGeneration;
    const response = await fetch(`${API_BASE}/auth/login`, {
      method:"POST", credentials:"include", cache:"no-store",
      headers:{"Content-Type":"application/json", "X-Browser-Session":"1"},
      body:JSON.stringify(credentials),
    });
    const data = await response.json().catch(() => null);
    if (version !== sessionGeneration) throw new Error("Phiên đăng nhập đã thay đổi. Vui lòng thử lại.");
    if (response.ok) {
      sessionGeneration++;
      saveSession(data);
      notifySession("login");
    }
    return {response, data};
  }

  function permissionKey(user) {
    return JSON.stringify([(user?.roles || []).slice().sort(), (user?.permissions || []).slice().sort()]);
  }

  function applyPermissionBindings() {
    for (const binding of permissionBindings) {
      document.querySelectorAll(binding.selector).forEach(element => {
        const allowed = binding.permissions.every(hasPermission);
        if (!allowed) {
          if (!element.hasAttribute("data-permission-hidden")) {
            element.dataset.permissionWasHidden = String(element.hidden);
          }
          element.setAttribute("data-permission-hidden", "");
          element.hidden = true;
        } else if (element.hasAttribute("data-permission-hidden")) {
          element.hidden = element.dataset.permissionWasHidden === "true";
          element.removeAttribute("data-permission-hidden");
        }
      });
    }
  }

  function bindPermissions(selector, ...permissions) {
    permissionBindings.push({ selector, permissions });
    applyPermissionBindings();
  }

  async function syncPermissions() {
    if (!isLoggedIn()) return false;
    if (permissionRequest) return permissionRequest;
    permissionRequest = (async () => {
      const version = sessionGeneration;
      const token = getAccessToken();
      try {
        const response = await fetch(`${API_BASE}/auth/permissions`, {
          headers: { Authorization: `Bearer ${token}` },
          cache: "no-store",
        });
        if (version !== sessionGeneration || token !== getAccessToken()) return false;
        if (response.status === 401) { await refreshAccessToken(); return false; }
        if (!response.ok) return false;
        const result = await response.json();
        if (version !== sessionGeneration || token !== getAccessToken()) return false;
        if (!result.success || !Array.isArray(result.data?.permissions)) return false;
        const previous = getUser();
        const next = { ...previous, ...result.data };
        sessionStorage.setItem("user", JSON.stringify(next));
        if (permissionKey(previous) !== permissionKey(next)) {
          applyPermissionBindings();
          window.dispatchEvent(new CustomEvent("auth:permissions-changed", { detail: next }));
        }
        return true;
      } catch {
        return false;
      } finally {
        permissionRequest = null;
      }
    })();
    return permissionRequest;
  }

  // ── Token helpers ──

  function getAccessToken() {
    return sessionStorage.getItem("accessToken");
  }

  function getRefreshToken() {
    return sessionStorage.getItem("refreshToken");
  }

  function getUser() {
    try {
      return JSON.parse(sessionStorage.getItem("user"));
    } catch {
      return null;
    }
  }

  function isLoggedIn() {
    return !!getAccessToken() && !!getUser();
  }

  function clearSession() {
    sessionGeneration++;
    sessionStorage.removeItem("accessToken");
    sessionStorage.removeItem("refreshToken");
    sessionStorage.removeItem("user");
    stopAutoRefresh();
  }

  // ── Parse JWT expiration ──

  function getTokenExpiration(token) {
    try {
      const payload = JSON.parse(atob(token.split(".")[1]));
      return payload.exp * 1000; // Convert to ms
    } catch {
      return 0;
    }
  }

  function isTokenExpiringSoon(token) {
    const expiresAt = getTokenExpiration(token);
    return Date.now() > expiresAt - REFRESH_BEFORE_MS;
  }

  function isTokenExpired(token) {
    const expiresAt = getTokenExpiration(token);
    return Date.now() > expiresAt;
  }

  // ── Auto-refresh (S1-02) ──

  async function refreshAccessToken({silent = false} = {}) {
    if (!refreshRequest) refreshRequest = performTokenRefresh();
    const request = refreshRequest;
    try {
      const result = await request;
      if (result === false && !silent) handleSessionExpired();
      return result;
    } finally {
      if (refreshRequest === request) refreshRequest = null;
    }
  }

  function restoreSession() { return refreshAccessToken({silent:true}); }

  // Đồng bộ quyền hiện tại từ DB khi mở trang. Access token có thể vẫn còn hạn
  // nhưng ma trận quyền đã được quản trị viên thay đổi.
  async function refreshPermissions() {
    return syncPermissions();
  }

  async function performTokenRefresh() {
    const version = sessionGeneration;
    const refreshToken = getRefreshToken();

    try {
      const response = await fetch(`${API_BASE}/auth/refresh`, {
        method: "POST",
        credentials: "include", cache: "no-store",
        headers: { "Content-Type": "application/json", "X-Browser-Session":"1" },
        body: JSON.stringify(refreshToken ? { refreshToken } : {}),
      });
      if (version !== sessionGeneration) return null;

      if (response.ok) {
        const data = await response.json();
        if (version !== sessionGeneration) return null;
        const previousUser = getUser();
        saveSession(data);
        const nextUser = getUser();
        if (previousUser && previousUser.username !== nextUser.username) {
          // Do not replay a pending business write under a different account.
          sessionGeneration++;
          window.location.reload();
          return null;
        }
        if (permissionKey(previousUser) !== permissionKey(nextUser)) {
          applyPermissionBindings();
          window.dispatchEvent(new CustomEvent("auth:permissions-changed", { detail: nextUser }));
        }
        return true;
      }

      // Chỉ kết luận phiên hết hạn khi server từ chối refresh token.
      if (response.status >= 400 && response.status < 500) {
        return false;
      }

      console.warn("Token refresh failed because the server is unavailable");
      return null;
    } catch {
      // Lỗi mạng không đồng nghĩa phiên đã hết hạn; giữ phiên để thử lại sau.
      console.warn("Token refresh failed, will retry");
      return null;
    }
  }

  function startAutoRefresh() {
    stopAutoRefresh();
    refreshTimer = setInterval(async () => {
      const token = getAccessToken();
      if (token && isTokenExpiringSoon(token)) {
        await refreshAccessToken();
      }
      await syncPermissions();
    }, Math.min(CHECK_INTERVAL_MS, 15000));
  }

  function stopAutoRefresh() {
    if (refreshTimer) {
      clearInterval(refreshTimer);
      refreshTimer = null;
    }
  }

  // ── Session expiry (S1-02) ──

  function handleSessionExpired() {
    if (sessionExpiryHandled) return;
    sessionExpiryHandled = true;
    clearSession();
    // Store message to show on login page
    sessionStorage.setItem(
      "sessionMessage",
      "Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.",
    );
    window.location.href = getLoginPageUrl();
  }

  // ── Logout (S1-02) ──

  async function logout() {
    const token = getAccessToken();
    const refreshToken = getRefreshToken();
    clearSession();
    sessionStorage.setItem("sessionSignedOut", "1");
    notifySession("logout");
    {
      try {
        const headers = { "Content-Type": "application/json", "X-Browser-Session":"1" };
        if (token) headers.Authorization = `Bearer ${token}`;

        await fetch(`${API_BASE}/auth/logout`, {
          method: "POST",
          credentials: "include", cache: "no-store",
          headers,
          body: JSON.stringify({ refreshToken }),
        });
      } catch {
        // Ignore errors — still clear local session
      }
    }

    notifySession("logout");
    window.location.href = getLoginPageUrl();
  }

  // ── Authenticated fetch helper ──

  async function apiFetch(url, options = {}) {
    const version = sessionGeneration;
    let token = getAccessToken();

    // Check if token needs refresh first
    if (token && isTokenExpiringSoon(token)) {
      const refreshed = await refreshAccessToken();
      if (version !== sessionGeneration) throw new Error("Session changed");
      if (refreshed) {
        token = getAccessToken();
      } else if (refreshed === false) {
        throw new Error("Session expired");
      }
    }

    if (!token) {
      handleSessionExpired();
      throw new Error("No access token");
    }

    const isFormData = typeof FormData !== "undefined"
      && options.body instanceof FormData;
    const headers = {
      Authorization: `Bearer ${token}`,
      ...options.headers,
    };
    if (!isFormData && !headers["Content-Type"]) {
      headers["Content-Type"] = "application/json";
    }

    const requestUrl = url.startsWith("http")
      ? url
      : url.startsWith("/api/")
        ? `${API_BASE.replace(/\/api$/, "")}${url}`
        : `${API_BASE}${url}`;
    const response = await fetch(
      requestUrl,
      { ...options, headers },
    );
    if (version !== sessionGeneration) throw new Error("Session changed");

    // Cập nhật giao diện cả khi token chưa hết hạn nhưng quyền vừa bị thu hồi.
    if (response.status === 403) await syncPermissions();

    // Handle 401 — try refresh once
    if (response.status === 401) {
      const refreshed = await refreshAccessToken();
      if (version !== sessionGeneration) throw new Error("Session changed");
      if (refreshed) {
        const retryToken = getAccessToken();
        const retryResponse = await fetch(
          requestUrl,
          {
            ...options,
            headers: { ...headers, Authorization: `Bearer ${retryToken}` },
          },
        );
        if (version !== sessionGeneration) throw new Error("Session changed");
        return retryResponse;
      } else if (refreshed === false) {
        throw new Error("Session expired");
      }

      throw new Error("Không thể gia hạn phiên do mất kết nối máy chủ");
    }

    if (response.status === 403) {
      const message = await response.clone().json().catch(() => null);
      sessionStorage.setItem(
        "errorMessage",
        message?.message || "Bạn không có quyền truy cập chức năng này.",
      );
      window.location.href = getErrorPageUrl(403);
    }

    return response;
  }

  async function setProtectedImage(image, url) {
    if (!image || !url) return false;
    try {
      const response = await apiFetch(url);
      if (!response.ok) return false;
      const blob = await response.blob();
      if (!blob.type.startsWith("image/")) return false;
      const previousUrl = image.dataset.objectUrl;
      const objectUrl = URL.createObjectURL(blob);
      image.src = objectUrl;
      image.dataset.objectUrl = objectUrl;
      if (previousUrl) URL.revokeObjectURL(previousUrl);
      return true;
    } catch {
      return false;
    }
  }

  // ── Guard: require login ──

  function requireLogin() {
    if (!isLoggedIn()) {
      if (sessionStorage.getItem("sessionSignedOut")) {
        window.location.href = getLoginPageUrl();
        return false;
      }
      if (!restoringPage) {
        restoringPage = true;
        const version = sessionGeneration;
        restoreSession().then(restored => {
          if (version !== sessionGeneration) return;
          if (restored) window.location.reload();
          else if (restored === false) window.location.href = getLoginPageUrl();
          else {
            // Keep the cookie on transient failures and give the page a retry action.
            const notice = document.createElement("div");
            notice.setAttribute("role", "alert");
            notice.textContent = "Không thể kết nối máy chủ để kiểm tra phiên đăng nhập. ";
            const retry = document.createElement("button");
            retry.textContent = "Thử lại";
            retry.onclick = () => window.location.reload();
            notice.append(retry);
            document.body.prepend(notice);
          }
        });
      }
      return false;
    }

    const token = getAccessToken();
    if (isTokenExpired(token)) {
      // Refresh sẽ tự chuyển về đăng nhập nếu server xác nhận phiên đã hết hạn.
      refreshAccessToken();
    }

    startAutoRefresh();
    return true;
  }

  // ── Guard: require specific role ──

  function hasRole(role) {
    const user = getUser();
    return user?.roles?.includes(role) || false;
  }

  function hasAnyRole(...roles) {
    const user = getUser();
    return roles.some((r) => user?.roles?.includes(r));
  }

  function hasPermission(permission) {
    const user = getUser();
    return user?.roles?.includes("ROLE_ADMIN")
      || user?.permissions?.includes(permission)
      || false;
  }

  function hasAnyPermission(...permissions) {
    return permissions.some((permission) => hasPermission(permission));
  }

  function getErrorPageUrl(status) {
    return `/error?status=${status}`;
  }

  function getLoginPageUrl() {
    return "/login";
  }

  if (typeof window !== "undefined") {
    window.addEventListener("focus", syncPermissions);
    if (typeof MutationObserver !== "undefined" && typeof document !== "undefined") {
      new MutationObserver(applyPermissionBindings).observe(document.documentElement, { childList: true, subtree: true });
    }
  }

  // ── Public API ──
  return {
    login,
    restoreSession,
    getAccessToken,
    getRefreshToken,
    getUser,
    isLoggedIn,
    logout,
    apiFetch,
    setProtectedImage,
    requireLogin,
    refreshPermissions,
    syncPermissions,
    bindPermissions,
    get ready() { return syncPermissions(); },
    hasRole,
    hasAnyRole,
    hasPermission,
    hasAnyPermission,
    clearSession,
    API_BASE,
  };
})();
