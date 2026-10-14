"""Serve SaleStock pages at clean URLs using only the Python standard library."""

from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import quote, unquote, urlsplit
import argparse


FRONTEND_ROOT = Path(__file__).resolve().parent / "frontend"
ROUTES = {
    "/login": "index.html",
    "/dashboard": "dashboard.html",
    "/profile": "profile.html",
    "/products": "products.html",
    "/product-groups": "product-groups.html",
    "/price-lists": "price-lists.html",
    "/discount-policies": "discount-policies.html",
    "/dealers": "dealers.html",
    "/create-order": "create-order.html",
    "/order-approval": "order-approval.html",
    "/suppliers": "suppliers.html",
    "/product-units": "product-units.html",
    "/warehouse/units": "warehouse/units.html",
    "/admin/users": "admin/users.html",
    "/admin/audit-logs": "admin/audit-logs.html",
    "/forgot-password": "forgot-password.html",
    "/reset-password": "reset-password.html",
    "/error": "error.html",
}
LEGACY_ROUTES = {"/" + filename: route for route, filename in ROUTES.items()}
ASSET_FOLDERS = {"assets", "css", "js"}


class FrontendRequestHandler(SimpleHTTPRequestHandler):
    server_version = "SaleStock"
    sys_version = ""

    def __init__(self, *args, directory=None, **kwargs):
        self.frontend_root = Path(directory or FRONTEND_ROOT).resolve()
        super().__init__(*args, directory=str(self.frontend_root), **kwargs)

    def do_GET(self):
        self._route_request(head_only=False)

    def do_HEAD(self):
        self._route_request(head_only=True)

    def end_headers(self):
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "strict-origin-when-cross-origin")
        super().end_headers()

    def log_request(self, code="-", size="-"):
        # Reset-password tokens and other query values do not belong in logs.
        request_parts = self.requestline.split(" ")
        target = urlsplit(request_parts[1]).path if len(request_parts) > 1 else "-"
        self.log_message('"%s %s %s" %s %s', self.command, target, self.request_version, code, size)

    def _redirect(self, target, query=""):
        self.send_response(308)
        self.send_header("Location", target + ("?" + query if query else ""))
        self.send_header("Content-Length", "0")
        self.end_headers()

    def _not_found(self, head_only):
        body = (self.frontend_root / "error.html").read_text(encoding="utf-8")
        body = body.replace('<html lang="vi">', '<html lang="vi" data-server-status="404">', 1)
        payload = body.encode("utf-8")
        self.send_response(404)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        if not head_only:
            self.wfile.write(payload)

    def _serve_file(self, relative_path, head_only):
        original_path = self.path
        self.path = quote("/" + relative_path, safe="/")
        try:
            if head_only:
                super().do_HEAD()
            else:
                super().do_GET()
        finally:
            self.path = original_path

    def _route_request(self, head_only):
        url = urlsplit(self.path)
        path = unquote(url.path)
        parts = path.split("/")
        if "\\" in path or "\x00" in path or any(part in {".", ".."} for part in parts):
            self._not_found(head_only)
            return

        if path in {"/", "/frontend", "/frontend/"}:
            self._redirect("/login", url.query)
            return

        if path.startswith("/frontend/"):
            legacy_path = path[len("/frontend"):]
            destination = LEGACY_ROUTES.get(legacy_path)
            if destination:
                self._redirect(destination, url.query)
                return
            if legacy_path.lstrip("/").split("/")[0] in ASSET_FOLDERS:
                self._redirect(legacy_path, url.query)
                return

        if path in LEGACY_ROUTES:
            self._redirect(LEGACY_ROUTES[path], url.query)
            return
        if path.endswith("/") and path.rstrip("/") in ROUTES:
            self._redirect(path.rstrip("/"), url.query)
            return
        if path in ROUTES:
            self._serve_file(ROUTES[path], head_only)
            return

        relative_path = path.lstrip("/")
        if relative_path.split("/")[0] in ASSET_FOLDERS:
            try:
                candidate = (self.frontend_root / relative_path).resolve()
            except (OSError, ValueError):
                self._not_found(head_only)
                return
            if candidate.is_relative_to(self.frontend_root) and candidate.is_file():
                self._serve_file(relative_path, head_only)
                return
        self._not_found(head_only)


def make_server(host="127.0.0.1", port=5500, frontend_root=FRONTEND_ROOT):
    handler = partial(FrontendRequestHandler, directory=str(frontend_root))
    return ThreadingHTTPServer((host, port), handler)


def main():
    parser = argparse.ArgumentParser(description="Serve the SaleStock frontend at /dashboard, /products, etc.")
    parser.add_argument("--port", type=int, default=5500)
    parser.add_argument("--host", default="127.0.0.1")
    args = parser.parse_args()
    with make_server(args.host, args.port) as server:
        print(f"SaleStock: http://localhost:{server.server_port}/login", flush=True)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass


if __name__ == "__main__":
    main()
