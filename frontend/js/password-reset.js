(() => {
  const API = "http://localhost:8080/api/auth";
  const showError = (message, input) => {
    Toast.show(message, "error");
    if (input) {
      input.setAttribute("aria-invalid", "true");
      input.focus();
    }
  };

  document.querySelectorAll("input").forEach(input => {
    input.addEventListener("input", () => input.removeAttribute("aria-invalid"));
  });

  const submit = async (form, path, body) => {
    const button = form.querySelector("button");
    button.disabled = true;
    try {
      const response = await fetch(`${API}/${path}`, { method:"POST", headers:{"Content-Type":"application/json"}, body:JSON.stringify(body) });
      const result = await response.json().catch(() => ({}));
      const ok = response.ok && result.success !== false;
      Toast.show(result.message || (ok ? "Yêu cầu đã được xử lý." : "Không thể xử lý yêu cầu."), ok ? "success" : "error");
      if (ok) form.reset();
    } catch { showError("Không thể kết nối máy chủ. Vui lòng thử lại."); }
    finally { button.disabled = false; }
  };
  document.getElementById("forgot-form")?.addEventListener("submit", (e) => {
    e.preventDefault();
    const email = document.getElementById("email");
    email.value = email.value.trim();
    if (!email.value) return showError("Vui lòng nhập email công việc.", email);
    if (!email.checkValidity()) return showError("Email không đúng định dạng.", email);
    submit(e.currentTarget, "forgot-password", {email:email.value});
  });
  document.getElementById("reset-form")?.addEventListener("submit", (e) => {
    e.preventDefault();
    const passwordInput = document.getElementById("new-password");
    const confirmation = document.getElementById("confirm-password");
    const password = passwordInput.value;
    if (!/^(?=.*[A-Za-z])(?=.*\d).{8,100}$/.test(password)) return showError("Mật khẩu cần từ 8–100 ký tự, có chữ và số.", passwordInput);
    if (password !== confirmation.value) return showError("Hai mật khẩu chưa khớp.", confirmation);
    submit(e.currentTarget, "reset-password", {token:new URLSearchParams(location.search).get("token") || "", newPassword:password});
  });
})();
