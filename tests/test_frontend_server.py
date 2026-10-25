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
            "/warehouse/inventory": "Tồn kho theo SKU và kho",
            "/warehouse/lots": "Lô & hạn sử dụng",
            "/warehouse/delivery": "Xác nhận giao hàng",
            "/warehouse/delivery-routes": "Phân tuyến giao hàng",
            "/warehouse/delivery-trips": "Theo dõi chuyến giao",
            "/warehouse/order-shipments": "Xuất kho theo đơn",
            "/warehouse/dealer-returns": "Trả hàng đại lý",
            "/warehouse/stock-card": "Thẻ kho sản phẩm",
            "/warehouses": "Kho & vị trí lưu",
            "/products": "Sản phẩm",
            "/product-groups": "Nhóm hàng",
            "/price-lists": "Bảng giá",
            "/dealers": "Đại lý",
            "/orders": "Đơn hàng",
            "/invoices": "Hóa đơn bán hàng",
            "/cash-collections": "Thu tiền theo tuyến",
            "/create-order": "Tạo đơn hàng",
            "/dealer-orders": "Đơn hàng của tôi",
            "/dealer-portal": "Cổng đặt hàng đại lý",
            "/order-approval": "Duyệt đơn",
            "/suppliers": "Nhà cung cấp",
            "/profile": "Hồ sơ cá nhân",
            "/login": "Đăng nhập",
            "/forgot-password": "Lấy lại quyền truy cập",
            "/finance/debt-adjustment": "Điều chỉnh công nợ",
            "/reset-password?token=test-token": "Đặt mật khẩu mới",
        }
        for path, title in pages.items():
            with self.subTest(path=path):
                status, headers, body = self.get(path)
                self.assertEqual(200, status)
                self.assertIn(title, body.decode("utf-8"))
                self.assertEqual("no-store", headers["Cache-Control"])
        for path in ["/js/auth.js", "/js/order-pdf.js", "/js/invoices.js", "/js/invoice-utils.js", "/css/invoices.css", "/js/delivery-confirmation.js", "/js/warehouse-fefo.js", "/js/dealer-delivery-tracking.js", "/css/dealer-delivery-tracking.css", "/css/dashboard.css", "/assets/brand-mark.svg"]:
            with self.subTest(asset=path):
                self.assertEqual(200, self.get(path)[0])

    def test_old_links_redirect_and_preserve_query(self):
        for old, clean in {
            "/": "/login",
            "/frontend/index.html": "/login",
            "/frontend/dashboard.html": "/dashboard",
            "/dashboard.html": "/dashboard",
            "/dealers.html": "/dealers",
            "/cash-collections.html": "/cash-collections",
            "/frontend/warehouse/dealer-returns.html": "/warehouse/dealer-returns",
            "/frontend/admin/users.html": "/admin/users",
            "/admin/users/": "/admin/users",
            "/warehouse/units?tab=inventory&warehouseId=4": "/warehouse/inventory?warehouseId=4",
            "/frontend/warehouse/units.html?tab=ledger": "/warehouse/units?tab=ledger",
            "/frontend/reset-password.html?token=sample-token": "/reset-password?token=sample-token",
        }.items():
            with self.subTest(old=old):
                status, headers, body = self.get(old)
                self.assertEqual(308, status)
                self.assertEqual(clean, headers["Location"])
                self.assertEqual(b"", body)

    def test_s503_warehouse_page_and_legacy_url(self):
        status, _, body = self.get("/warehouses")
        self.assertEqual(200, status)
        self.assertIn('id="wh-dealers"', body.decode("utf-8"))
        self.assertIn('/js/warehouses.js', body.decode("utf-8"))
        self.assertEqual(200, self.get("/warehouses", method="HEAD")[0])
        status, headers, _ = self.get("/warehouses.html?warehouseId=1")
        self.assertEqual(308, status)
        self.assertEqual("/warehouses?warehouseId=1", headers["Location"])

    def test_s805_dashboard_route_and_legacy_url(self):
        status, _, body = self.get('/reports/dashboard')
        self.assertEqual(200, status)
        html = body.decode('utf-8')
        for text in ['Dashboard điều hành', 'ops-dealers', 'operations-dashboard.js', 'app-shell.js', 'app-navigation.css']:
            self.assertIn(text, html)
        self.assertEqual(200, self.get('/reports/dashboard', method='HEAD')[0])
        status, headers, _ = self.get('/operations-dashboard.html')
        self.assertEqual(308, status)
        self.assertEqual('/reports/dashboard', headers['Location'])

    def test_s809_debt_aging_frontend_route_and_pending_backend(self):
        status, _, body = self.get('/reports/debt-aging')
        self.assertEqual(200, status)
        html = body.decode('utf-8')
        for text in ['Công nợ theo tuổi nợ', 'aging-pending', 'debt-aging-api.js', 'app-shell.js', 'app-navigation.css']:
            self.assertIn(text, html)
        self.assertEqual(200, self.get('/reports/debt-aging', method='HEAD')[0])
        status, headers, _ = self.get('/debt-aging.html')
        self.assertEqual(308, status)
        self.assertEqual('/reports/debt-aging', headers['Location'])

    def test_s507_transfer_route(self):
        status, _, body = self.get('/warehouse/transfers')
        self.assertEqual(status, 200)
        self.assertIn('warehouse-transfers.js', body.decode('utf-8'))

    def test_s603_picking_route_and_legacy_url(self):
        status, _, body = self.get('/warehouse/picking')
        self.assertEqual(status, 200)
        html = body.decode('utf-8')
        for text in ['Phiếu soạn hàng', 'pick-lines', 'warehouse-picking.js', 'app-shell.js', 'app-navigation.css']:
            self.assertIn(text, html)
        self.assertEqual(200, self.get('/warehouse/picking', method='HEAD')[0])
        status, headers, _ = self.get('/warehouse/picking.html')
        self.assertEqual(308, status)
        self.assertEqual('/warehouse/picking', headers['Location'])

    def test_s508_stocktake_route_keeps_the_shared_shell_and_server_backed_scripts(self):
        status, _, body = self.get("/warehouse/units?tab=stocktake")
        self.assertEqual(200, status)
        html = body.decode("utf-8")
        for text in ["panel-stocktake", "stocktake-message", "stocktake-history-body", "/js/warehouse-stocktake.js", "/js/app-navigation.js"]:
            self.assertIn(text, html)
        self.assertEqual(1, html.count('id="sidebar"'))
        self.assertEqual(1, html.count('class="main-area"'))

    def test_s509_minimum_route(self):
        status, _, body = self.get('/warehouse/minimums')
        self.assertEqual(status, 200)
        self.assertIn('stock-minimums.js', body.decode('utf-8'))

    def test_s505_stock_ledger_route_and_old_inventory_tab(self):
        status, _, body = self.get("/warehouse/inventory")
        self.assertEqual(200, status)
        html = body.decode("utf-8")
        for text in ['Tồn thực tế', 'Đang giữ chỗ', 'Tồn khả dụng', '/js/stock-ledger.js']:
            self.assertIn(text, html)
        self.assertNotIn('/js/warehouse-units.js', html)
        self.assertEqual(200, self.get("/warehouse/inventory", method="HEAD")[0])
        status, headers, _ = self.get("/warehouse/units?tab=inventory&warehouseId=5&keyword=SKU-01")
        self.assertEqual(308, status)
        self.assertEqual("/warehouse/inventory?warehouseId=5&keyword=SKU-01", headers["Location"])
        self.assertEqual(200, self.get("/warehouse/units?tab=units")[0])

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
