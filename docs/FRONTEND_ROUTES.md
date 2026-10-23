# Chạy frontend và đường dẫn trang

## S6-08 — Sửa frontend, chưa làm backend (23/10/2026)

- Menu **Kho hàng → Xác nhận giao hàng**, URL `/warehouse/delivery`. Tách object menu khỏi Kiểm kê kho để không ghi đè ID/URL và không gắn nhầm EXPORT cho kiểm kê. Bổ sung script nghiệp vụ bị thiếu và `app-transition.js` trong head.
- Cần **ORDER_VIEW + WAREHOUSE_EXPORT** theo ma trận DB (hoặc ADMIN). Đọc GET `/orders?page=0&size=20&keyword=...&status=...` và GET `/orders/{id}` hiện có; không gửi POST/PUT/PATCH/DELETE. Scope đơn do backend hiện có quyết định, không role fallback. Lọc kho và số đếm chỉ áp dụng trang hiện tại, có nhãn rõ; tìm từ khóa/trạng thái/phân trang qua server.
- Đơn đã xuất mở được form giao nhận/giao thất bại. Nhập người nhận, thời gian, ghi chú và chọn/xem trước ảnh JPG/PNG/WEBP tối đa 10MB; ảnh phải decode được, có đổi/xóa/phóng to. Chỉ giữ tạm trong bộ nhớ/blob URL, không local/sessionStorage hay tải lên server. Đóng form/reload/thu hồi quyền xóa bản xem trước và giải phóng blob URL.
- **Chưa có backend S6-08:** nút lưu giao hàng và hoàn kho bị khóa kể cả ADMIN; chặn submit giả bằng JS, không báo lưu thành công, không tạo phiếu hay cộng/trừ tồn/đổi trạng thái/kích hoạt hoá đơn. Các số liệu ảnh/hoàn kho/điều kiện hoá đơn chưa có API hiển thị “—”, không suy từ tổng tiền hay trạng thái đơn. Cần triển khai backend và kiểm thử lại trước khi mở các nút ghi.

## S6-04 — Gợi ý lô xuất FEFO (23/10/2026)

- **Kho hàng → Lô & hạn sử dụng → Gợi ý xuất FEFO**, URL `/warehouse/lots?tab=fefo`. Dùng khung trang chung, không thêm menu/sidebar riêng.
- Chọn kho được phân công, SKU và số lượng theo đơn vị cơ sở; **Lấy gợi ý FEFO** gọi API, không tự tính thứ tự/hạn dùng/tồn trong trình duyệt. Lô mặc định là lô còn hạn có hạn dùng gần nhất và đủ số lượng cho một lần xuất. Lô thiếu lượng/quá hạn/thiếu HSD không chọn được trong luồng FEFO này. Không có lô đủ thì giảm lượng và lấy lại gợi ý, không tự gộp/trừ nhiều lô.
- Có thể chọn lô khác còn hạn và đủ lượng, nhưng bắt buộc lý do. Đánh dấu xác nhận rồi **Xác nhận xuất lô** mới POST phiếu xuất. Kết quả hiển thị mã phiếu/lô do server trả; tải lại tồn qua API, không giảm tồn giả ở JS. Lô quá hạn chỉ xử lý theo luồng ghi đè S6-01 hiện có, không được gợi ý mặc định.
- Trang Lô cần VIEW hoặc MANAGE; tab FEFO cần thêm VIEW hoặc EXPORT, nút ghi cần EXPORT và `canExport` từ API. ADMIN luôn có quyền; không dùng vai trò kho thay quyền DB. Thu hồi quyền xóa gợi ý/form, ẩn tab hoặc nút; bỏ qua phản hồi cũ. Người chỉ VIEW xem gợi ý nhưng không xuất.
- Đổi kho/SKU/lượng làm mất gợi ý cũ; phải lấy lại trước khi xuất. Lỗi nghiệp vụ giữ dữ liệu để sửa; nếu mất kết nối/5xx không rõ kết quả ghi, xóa khả năng gửi lại ngay và nhắc kiểm tra Sổ cái kho để tránh xuất lặp. Không lưu nghiệp vụ vào local/sessionStorage.
- Cần build/restart backend để nhận sửa FEFO và khóa tồn; không có thay đổi schema, không cần xóa DB. Backend kiểm tra lại lô và quyền khi ghi sổ, không tin `suggestedLotNumber` từ client.

## S6-03 — Phiếu soạn hàng (23/10/2026)

- Menu **Kho hàng → Phiếu soạn hàng**, URL `/warehouse/picking` (URL cũ `/warehouse/picking.html` tự chuyển về URL sạch).
- **Tạo lượt soạn**: chọn kho, chọn một hoặc nhiều đơn đã duyệt còn giữ chỗ (có thể chọn qua nhiều trang), nhập mã riêng hoặc để trống tự sinh. Backend kiểm tra và chốt snapshot ở DB.
- Mở lượt để đi theo thứ tự khu/kệ; mỗi thẻ có đơn gốc, sản phẩm, số yêu cầu, thực soạn, lý do thiếu, checkbox **Đã soạn dòng này** và **Lưu dòng**. Thực soạn 0 không bị đổi thành số đặt; thiếu bắt buộc lý do. Lưu một dòng không xóa các dòng khác đang nhập dở.
- **Hoàn tất lượt soạn** chỉ bật khi mọi dòng đã lưu/đánh dấu và không có thay đổi chưa lưu. Phiếu hoàn tất chỉ xem; hủy lượt đang soạn phải có lý do và không hoàn/trừ tồn tự động.
- Trình bày dạng thẻ, nút/ô nhập tối thiểu 44px cho điện thoại; giữ shared app shell và frame trong `app-navigation.css`. Quyền đọc VIEW hoặc EXPORT, quyền ghi EXPORT theo DB; scope kho do API. Thu hồi quyền đóng/xóa form, hủy GET đang chờ, bỏ qua phản hồi ghi cũ và ẩn/tắt hành động tương ứng.
- Chỉnh tuyến ở **Kho & vị trí lưu → Thêm/Sửa vị trí → Thứ tự đường đi khi soạn**; chỉ áp dụng lượt tạo mới. Chưa khai báo thì theo mã khu/kệ tự nhiên; hàng chưa gán vị trí hiện riêng ở cuối tuyến.

## S5 — tích hợp bản sửa 22/10/2026

- `/orders` → Xem chi tiết → **Số lượng thực giao**: nhập ngày, số lượng từng dòng (kể cả 0), ghi chú; **Điền giao đủ** chỉ điền khi người dùng bấm. Nút chuyển sang Đã giao đưa tới form này. Chỉ được sửa nếu backend trả `canUpdateDelivery` và còn `WAREHOUSE_EXPORT`; người chỉ có quyền xem đọc được dữ liệu đã lưu.
- `/dealer-orders` → **Đặt lại**: dùng `reorder-preview` và `/reorder` của đơn gốc; giá/mặt hàng/ngày mặc định do server cung cấp. Tạm tính hiển thị trước chiết khấu; server kiểm tra lại khi đặt. Chỉ cần `ORDER_VIEW` + `ORDER_CREATE`, không đòi `PRODUCT_VIEW` riêng.
- Hai trang đóng form, chặn phản hồi cũ và cập nhật nút khi quyền được cấp/thu hồi trong phiên hiện tại; không dùng role thay quyền nghiệp vụ.

Từ thư mục repo, chạy một trong hai lệnh:

```powershell
.\run_frontend.ps1
# Hoặc:
python serve_frontend.py
```

Mở [trang đăng nhập](http://localhost:5500/login) hoặc
[trang tổng quan](http://localhost:5500/dashboard).
Backend vẫn chạy ở cổng 8080 theo cấu hình trong README.

| Trang | Đường dẫn |
|---|---|
| Đăng nhập | `/login` |
| Tổng quan | `/dashboard` |
| Hồ sơ | `/profile` |
| Sản phẩm | `/products` |
| Nhóm hàng | `/product-groups` |
| Bảng giá | `/price-lists` |
| Chiết khấu sản lượng | `/discount-policies` |
| Hồ sơ, tìm kiếm và khoá đại lý | `/dealers` |
| Tạo đơn hàng | `/create-order` |
| Đơn hàng của tôi / theo dõi đơn đại lý | `/dealer-orders` |
| Cổng đại lý tự đặt hàng | `/dealer-portal` |
| Hạn mức công nợ | `/dashboard?view=debt-limits` |
| Nhà cung cấp | `/suppliers` |
| Đơn vị tính | `/warehouse/units?tab=units` |
| Lập chứng từ | `/warehouse/units?tab=receipts` |
| Sổ cái kho | `/warehouse/units?tab=ledger` |
| Kiểm kê kho (S5-08) | `/warehouse/units?tab=stocktake` — VIEW hoặc ADJUST để xem/tạo/đếm, ADJUST để chốt; phiếu và số đếm lưu qua `/warehouse/stock-counts` |
| Tồn kho | `/warehouse/inventory` (tab cũ `?tab=inventory` được chuyển tiếp) |
| Ngưỡng tồn tối thiểu (S5-09) | `/warehouse/minimums` |
| Lô & hạn sử dụng (S6-01) | `/warehouse/lots` — VIEW hoặc MANAGE để xem; PRODUCT_EDIT hoặc MANAGE cấu hình SKU; EXPORT để xuất; APPROVE hoặc MANAGE để ghi đè quá hạn kèm xác nhận và lý do. ADMIN luôn được phép. |
| Thẻ kho sản phẩm (S6-02) | `/warehouse/stock-card` — WAREHOUSE_VIEW hoặc ADMIN. Chọn SKU, lọc kho/ngày, phân trang server và mở chứng từ gốc chỉ đọc; từ Tồn kho có liên kết chọn sẵn SKU/kho. Query productId/warehouseId/fromDate/toDate (ngày ISO)/page/size và sourceType/sourceId hỗ trợ mở lại. |
| Tài khoản nhân sự | `/admin/users` |
| Nhật ký thao tác | `/admin/audit-logs` |
| Quên mật khẩu | `/forgot-password` |
| Đặt lại mật khẩu | `/reset-password?token=...` |
| Trang lỗi | `/error?status=403` |

`/` mở trang đăng nhập. Các liên kết cũ như `/frontend/dashboard.html`,
`/admin/users.html` hoặc `/frontend/reset-password.html?token=...` được chuyển
sang đường dẫn mới; tham số truy vấn được giữ lại.

Server dùng thư viện chuẩn Python, không cần cài thêm package. Khi thêm trang,
cập nhật `ROUTES` trong `serve_frontend.py` và menu trong `frontend/js/menu-config.js`.
Không dùng `python -m http.server` cho các đường dẫn ngắn này.

S5-09: **Kho hàng → Ngưỡng tồn tối thiểu** cần `WAREHOUSE_MANAGE` (**Quản lý danh mục kho và vị trí**) hoặc ADMIN. Khai báo theo SKU + kho, phân biệt mức 0 với chưa khai báo. **Tồn kho** và khối cảnh báo trên **Tổng quan** cần `WAREHOUSE_VIEW` (**Xem tồn kho**), không lấy quyền thay thế từ vai trò hoặc MANAGE. `/warehouse/inventory?belowMinimum=true` chỉ hiện hàng có tồn khả dụng dưới ngưỡng. Cả hai quyền tuân theo ma trận đã lưu và phạm vi kho; khởi động lại backend/frontend sau cập nhật mã.

ADMIN mở `/dealer-orders` hoặc `/dealer-portal` sẽ chọn đại lý từ danh sách DB,
không cần gắn hồ sơ đại lý vào tài khoản admin. Sau khi chọn, URL có `?dealerId=<id>`;
mọi API cổng dùng cùng ngữ cảnh đó. Trang chưa chọn đại lý không gọi API dữ liệu
thiếu ngữ cảnh. DEALER không có bộ chọn, chỉ sử dụng hồ sơ đã liên kết và quyền
trong ma trận: theo dõi cần `ORDER_VIEW`, đặt hàng cần cả `ORDER_CREATE`, `ORDER_VIEW`,
`PRODUCT_VIEW`; nút đặt lại cần thêm `ORDER_CREATE` và `PRODUCT_VIEW`.

## Breadcrumb và mục sidebar hiện tại

Các trang nghiệp vụ dùng chung `app-navigation.js` và `app-navigation.css`.
Tên trang, nhóm chức năng và đường dẫn lấy từ `MenuConfig`:

- Trang tổng quan: `Tổng quan`.
- Hồ sơ: `Tổng quan › Hồ sơ cá nhân`.
- Các trang còn lại: `Tổng quan › Nhóm chức năng › Trang hiện tại`.
- `Tổng quan` dẫn về `/dashboard`; tên nhóm là nhãn khi chưa có trang nhóm riêng.
- Mỗi trang có một mục sidebar được tô sáng và gắn `aria-current="page"`.
  So khớp gồm cả tham số `view`/`tab`, nên công nợ không tô sáng thêm Tổng quan,
  các tab kho không chọn nhầm nhau.
- Kho có bốn mục tương ứng: Đơn vị tính, Nhập / Xuất kho, Sổ cái kho, Tồn kho.
  Khi đổi tab trên màn hình kho, breadcrumb và sidebar cập nhật ngay.
- Vai trò chỉ xem có mục Đơn vị tính tương ứng tab đọc đã có; các nút tạo/sửa
  đơn vị và lập chứng từ vẫn bị ẩn theo quyền hiện có.
- Khi thêm trang, khai báo metadata trong `MenuConfig` và tải module điều hướng
  sau `menu-config.js` (sau `app-shell.js` nếu trang cần dựng shell). Các script
  nghiệp vụ không tự dựng lại menu hoặc gán trạng thái active riêng.

Đã xác minh ngày 11/10/2026: **16 trang/tab ở 1280px và 360px**, bốn lần đổi tab
kho, đường dẫn mặc định/cũ, menu sales và chế độ chỉ xem. Kết quả:
[navigation-checks-20261011.json](navigation-checks-20261011.json).

![Tạo đơn: breadcrumb và mục sidebar được chọn](evidence/navigation-create-order-desktop-20261011.png)

![Menu tạo đơn trên mobile](evidence/navigation-create-order-sidebar-mobile-20261011.png)

## Kiểm tra

```powershell
python -m unittest discover -s tests -p test_frontend_server.py
node --test tests/menu-config.test.cjs
```

Đã kiểm tra đăng nhập chuyển sang `/dashboard`, điều hướng tới trang quản trị,
đường dẫn kho có tham số `tab`, vai trò chỉ xem và bố cục 360px.
Ảnh minh họa: [desktop](evidence/ui-clean-dashboard.jpg),
[mobile](evidence/ui-clean-dashboard-mobile.jpg).
