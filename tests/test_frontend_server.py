import contextlib
import io
import threading
import unittest
from urllib.error import HTTPError
from urllib.request import HTTPRedirectHandler, Request, build_opener

from serve_frontend import make_server


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class FrontendRoutingTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.logs = io.StringIO()
        cls.log_capture = contextlib.redirect_stderr(cls.logs)
        cls.log_capture.__enter__()
        cls.server = make_server(port=0)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.origin = f"http://127.0.0.1:{cls.server.server_port}"
        cls.client = build_opener(NoRedirect())

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join()
        cls.log_capture.__exit__(None, None, None)

    def get(self, path, method="GET"):
        try:
            response = self.client.open(Request(self.origin + path, method=method), timeout=3)
        except HTTPError as response_error:
            response = response_error
        with response:
            return response.status, response.headers, response.read()

    def test_clean_routes_serve_the_right_pages_and_assets(self):
        pages = {
            "/dashboard": "Tổng quan | SaleStock",
            "/admin/users": "Quản lý tài khoản",
            "/admin/audit-logs": "Nhật ký thao tác",
            "/warehouse/units?tab=inventory": "Đơn vị tính",
            "/products": "Sản phẩm",
            "/product-groups": "Nhóm hàng",
            "/price-lists": "Bảng giá",
            "/dealers": "Đại lý",
            "/create-order": "Tạo đơn hàng",
            "/dealer-orders": "Đơn hàng của tôi",
            "/suppliers": "Nhà cung cấp",
            "/profile": "Hồ sơ cá nhân",
            "/login": "Đăng nhập",
            "/forgot-password": "Lấy lại quyền truy cập",
            "/reset-password?token=test-token": "Đặt mật khẩu mới",
        }
        for path, title in pages.items():
            with self.subTest(path=path):
                status, headers, body = self.get(path)
                self.assertEqual(200, status)
                self.assertIn(title, body.decode("utf-8"))
                self.assertEqual("no-store", headers["Cache-Control"])
        for path in ["/js/auth.js", "/css/dashboard.css", "/assets/brand-mark.svg"]:
            with self.subTest(asset=path):
                self.assertEqual(200, self.get(path)[0])

    def test_old_links_redirect_and_preserve_query(self):
        for old, clean in {
            "/": "/login",
            "/frontend/index.html": "/login",
            "/frontend/dashboard.html": "/dashboard",
            "/dashboard.html": "/dashboard",
            "/dealers.html": "/dealers",
            "/frontend/admin/users.html": "/admin/users",
            "/admin/users/": "/admin/users",
            "/frontend/warehouse/units.html?tab=ledger": "/warehouse/units?tab=ledger",
            "/frontend/reset-password.html?token=sample-token": "/reset-password?token=sample-token",
        }.items():
            with self.subTest(old=old):
                status, headers, body = self.get(old)
                self.assertEqual(308, status)
                self.assertEqual(clean, headers["Location"])
                self.assertEqual(b"", body)

    def test_head_works_without_response_body(self):
        status, headers, body = self.get("/dashboard", method="HEAD")
        self.assertEqual(200, status)
        self.assertGreater(int(headers["Content-Length"]), 0)
        self.assertEqual(b"", body)

    def test_unknown_routes_and_private_workspace_files_are_not_served(self):
        for path in ["/missing", "/admin/missing", "/AGENTS.md", "/run_backend.ps1", "/.git/config",
                     "/js/", "/frontend/backend/pom.xml", "/assets/../../run_backend.ps1",
                     "/assets/%2e%2e/%2e%2e/run_backend.ps1", "/js/%252e%252e/%252e%252e/run_backend.ps1"]:
            with self.subTest(path=path):
                status, _, body = self.get(path)
                self.assertEqual(404, status)
                self.assertIn(b'data-server-status="404"', body)
                self.assertNotIn(b"APP_JWT_SECRET", body)

    def test_password_reset_token_does_not_appear_in_access_logs(self):
        self.get("/reset-password?token=should-never-be-logged")
        self.assertNotIn("should-never-be-logged", self.logs.getvalue())


if __name__ == "__main__":
    unittest.main()
