(() => {
  // ── Configuration ──
  const API_BASE = "http://localhost:8080/api";

  // ── DOM refs ──
  const form = document.querySelector(".login-form");
  const usernameInput = document.querySelector("#username");
  const passwordInput = document.querySelector("#password");
  const passwordToggle = document.querySelector("[data-password-toggle]");
  const submitButton = document.querySelector(".submit-button");

  // ── Password visibility toggle ──
  if (passwordInput && passwordToggle) {
    passwordToggle.addEventListener("click", () => {
      const shouldShowPassword = passwordInput.type === "password";

      passwordInput.type = shouldShowPassword ? "text" : "password";
      passwordToggle.classList.toggle("is-visible", shouldShowPassword);
      passwordToggle.setAttribute("aria-pressed", String(shouldShowPassword));
      passwordToggle.setAttribute(
        "aria-label",
        shouldShowPassword ? "Ẩn mật khẩu" : "Hiện mật khẩu",
      );

      passwordInput.focus({ preventScroll: true });
    });
  }

  const showToast = Toast.show;

  // ── Field validation styling ──
  function setFieldError(input, hasError) {
    const group = input.closest(".field-group");
    if (group) {
      group.classList.toggle("field-group--error", hasError);
    }
  }

  function clearAllErrors() {
    document.querySelectorAll(".field-group--error").forEach((el) => {
      el.classList.remove("field-group--error");
    });
  }

  // ── Loading state ──
  function setLoading(isLoading) {
    if (!submitButton) return;

    submitButton.disabled = isLoading;
    submitButton.classList.toggle("submit-button--loading", isLoading);

    const label = submitButton.querySelector("span");
    const arrow = submitButton.querySelector("svg");

    if (isLoading) {
      if (label) label.textContent = "Đang xử lý...";
      if (arrow) arrow.style.display = "none";
    } else {
      if (label) label.textContent = "Đăng nhập";
      if (arrow) arrow.style.display = "";
    }
  }

  // ── Token & user storage ──
  function saveAuthData(data) {
    sessionStorage.setItem("accessToken", data.accessToken);
    sessionStorage.setItem("refreshToken", data.refreshToken);
    sessionStorage.setItem(
      "user",
      JSON.stringify({
        id: data.id,
        username: data.username,
        email: data.email,
        fullName: data.fullName,
        roles: data.roles,
        permissions: data.permissions || [],
        warehouses: data.warehouses || [],
        territories: data.territories || [],
        mustChangePassword: Boolean(data.mustChangePassword),
        avatarUrl: data.avatarUrl || null,
        avatarThumbnailUrl: data.avatarThumbnailUrl || null,
      }),
    );
  }

  // ── Role-based redirect (S1-01) ──
  function getRedirectUrl(roles) {
    // Admin → admin dashboard
    if (roles.includes("ROLE_ADMIN")) return "/dashboard";
    // Sales Manager → sales overview
    if (roles.includes("ROLE_SALES_MANAGER")) return "/dashboard";
    // Warehouse Manager → warehouse overview
    if (roles.includes("ROLE_WAREHOUSE_MANAGER")) return "/dashboard";
    // All other roles → general dashboard
    return "/dashboard";
  }

  // ── Check if already logged in ──
  function checkExistingSession() {
    const token = sessionStorage.getItem("accessToken");
    const user = sessionStorage.getItem("user");
    if (token && user) {
      try {
        const userData = JSON.parse(user);
        window.location.href = getRedirectUrl(userData.roles || []);
      } catch {
        // Invalid stored data, clear and stay on login
        sessionStorage.removeItem("accessToken");
        sessionStorage.removeItem("refreshToken");
        sessionStorage.removeItem("user");
      }
    }
  }

  // ── Shake form animation ──
  function shakeForm() {
    if (!form) return;
    form.classList.add("login-form--shake");
    form.addEventListener(
      "animationend",
      () => form.classList.remove("login-form--shake"),
      { once: true },
    );
  }

  // ── Login handler ──
  async function handleLogin(e) {
    e.preventDefault();
    clearAllErrors();

    const username = usernameInput.value.trim();
    const password = passwordInput.value;

    // Client-side validation
    let hasError = false;

    if (!username) {
      setFieldError(usernameInput, true);
      hasError = true;
    }

    if (!password) {
      setFieldError(passwordInput, true);
      hasError = true;
    }

    if (hasError) {
      showToast("Vui lòng nhập đầy đủ thông tin đăng nhập.", "error");
      return;
    }

    setLoading(true);

    try {
      const response = await fetch(`${API_BASE}/auth/login`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
        },
        body: JSON.stringify({ username, password }),
      });

      if (response.ok) {
        // ✅ Đăng nhập thành công
        const data = await response.json();
        saveAuthData(data);

        showToast(
          `Đăng nhập thành công! Chào mừng ${data.fullName}.`,
          "success",
        );

        // Redirect theo vai trò
        setTimeout(() => {
          window.location.href = getRedirectUrl(data.roles);
        }, 800);
        return;
      }

      // ── Xử lý các mã lỗi ──
      const errorData = await response.json().catch(() => null);

      switch (response.status) {
        case 401:
          // Sai username/password — message chung, không tiết lộ
          showToast(
            errorData?.message || "Tên đăng nhập hoặc mật khẩu không đúng.",
            "error",
          );
          setFieldError(usernameInput, true);
          setFieldError(passwordInput, true);
          shakeForm();
          break;

        case 423:
          // Khoá tạm do sai quá nhiều lần (S1-01)
          const remaining = errorData?.data?.remainingMinutes || 15;
          showToast(
            `Tài khoản bị khoá tạm thời. Thử lại sau ${remaining} phút.`,
            "warning",
            8000,
          );
          setFieldError(usernameInput, true);
          break;

        case 403:
          // Bị admin khoá
          if (errorData?.data?.type === "ADMIN_LOCK") {
            showToast(
              "Tài khoản đã bị khoá. Vui lòng liên hệ quản trị viên.",
              "error",
              6000,
            );
          } else {
            showToast(
              errorData?.message || "Bạn không có quyền truy cập.",
              "error",
            );
          }
          setFieldError(usernameInput, true);
          break;

        default:
          showToast(
            errorData?.message || "Đã xảy ra lỗi. Vui lòng thử lại.",
            "error",
          );
      }
    } catch (err) {
      console.error("Login error:", err);
      showToast(
        "Không thể kết nối đến máy chủ. Vui lòng kiểm tra lại kết nối.",
        "error",
      );
    } finally {
      setLoading(false);
    }
  }

  // ── Clear field error on input ──
  if (usernameInput) {
    usernameInput.addEventListener("input", () =>
      setFieldError(usernameInput, false),
    );
  }
  if (passwordInput) {
    passwordInput.addEventListener("input", () =>
      setFieldError(passwordInput, false),
    );
  }

  // ── Init ──
  const sessionMessage = sessionStorage.getItem("sessionMessage");
  if (sessionMessage) {
    sessionStorage.removeItem("sessionMessage");
    setTimeout(() => showToast(sessionMessage, "warning", 6000), 0);
  }
  checkExistingSession();

  if (form) {
    form.addEventListener("submit", handleLogin);
  }
})();
