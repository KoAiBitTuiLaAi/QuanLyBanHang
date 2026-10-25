# Kiểm tra Sprint 6 trên web và MySQL thật — 25/10/2026

Nhánh: `fix/Sprint6`. Frontend `http://localhost:5500`, backend `http://localhost:8080`; kiểm tra trên Chrome bằng API và database đang chạy, không chặn/thay thế phản hồi bằng fixture. Người dùng đã cho phép tạo/xác nhận dữ liệu kiểm thử trên máy riêng.

## Kết quả theo story

| Story | Kiểm tra trực tiếp | Kết quả |
|---|---|---|
| S6-01 | SKU kiểm thử bật quản lý lô; lưu ngưỡng cảnh báo 7 ngày; chặn xuất quá hạn thiếu xác nhận; Admin xuất ngoại lệ có lý do; Thủ kho không có nút ghi đè, API ghi đè trả 403 | Đạt các ca đã chạy |
| S6-02 | Đọc 7 phát sinh theo thời gian; đối chiếu nhập/xuất/nhập trả; mở chứng từ nhập trả 4 thùng = 96 lon, tồn sau nhập trả 1.680 lon; tồn cuối 1.584 lon | Đạt |
| S6-03 | Gom 2 đơn vào lượt soạn, lấy vị trí Kệ A01, lưu thực soạn từng dòng và hoàn tất | Đạt |
| S6-04 | Gợi ý lô EARLY; chọn lô LATE kèm lý do; xác nhận trên web trừ đúng 1 lon ở LATE (120 → 119), EARLY giữ 72 | Đạt |
| S6-05 | Xuất trước 4/10 thùng có lý do; xuất bù 6 khi SHIPPING; đơn nhập trả mở lại phần cần xuất; phiếu xuất đã xác nhận hiển thị chỉ đọc | Đạt các ca đã chạy |
| S6-06 | Gom phiếu xuất thật vào chuyến, gán tài xế/xe, in bảng kê và tải PDF hợp lệ; dòng hàng mang đúng lượng cơ sở, lô và phiếu gốc | Đạt |
| S6-07 | Chuyến kiểm thử quá thời gian dự kiến xuất hiện trong bộ lọc quá hạn; giao thất bại không tăng tồn; sau xử lý giao/nhập trả, giá trị còn thuộc chuyến của kho kiểm thử bằng 0 | Đạt |
| S6-08 | Lưu người nhận, thời điểm, ảnh kiểm thử ký nhận; xem/tải chứng từ bảo vệ bằng quyền; ghi thất bại, xác nhận thực nhận nhập trả; nhập trả lặp bị chặn và tồn không tăng lần hai | Đạt |
| S6-09 | Trên cổng đại lý: giao 4 thùng vẫn SHIPPING, thực giao 4, không có mốc giao đủ giả; sau giao bù hiển thị DELIVERED, thực giao 10, đủ 5 mốc theo dữ liệu thật | Đạt |

Đã xác nhận liên thông hóa đơn: xem trước bị chặn khi mới giao 4/10; được phép khi giao đủ 10. Không phát hành hóa đơn tài chính thật trong đợt kiểm thử này.

## Lỗi thực tế phát hiện và sửa

Gán SKU vào kệ thất bại trên MySQL với `Data too long for column 'action'`: `audit_logs.action` chỉ có 20 ký tự, trong khi `ASSIGN_PRODUCT_LOCATION` dài hơn giới hạn. Giao dịch bị hoàn tác nên chưa gán được vị trí soạn hàng.

- Sửa `AuditLog.action` thành `VARCHAR(100)` trong mô hình JPA.
- Mở rộng đúng cột này trên database local đang chạy, giữ charset/collation và toàn bộ 79 bản ghi có trước thời điểm thay đổi; không reset database.
- Thử lại gán vị trí thành công, sau đó toàn trình soạn/xuất/giao/nhập trả trên web thành công.
- Bổ sung `AuditLogPersistenceTest`: lưu và đọc nguyên tên hành động dài trên H2; 1 test qua. H2 của test dùng đúng dialect H2.

Môi trường khác có thể nhận thay đổi cột qua cấu hình `ddl-auto=update` hiện tại khi khởi động backend. Với môi trường quản lý schema thủ công, cần mở rộng cột theo mô hình mới và giữ nguyên thuộc tính cột hiện hữu.

## Phân quyền và giao diện

- Admin: thao tác trên bộ dữ liệu kiểm thử và ghi đè xuất quá hạn có xác nhận/lý do.
- `warehouse.demo`: dùng quyền hiện tại từ DB, không đọc được chuyến, ảnh ký nhận hoặc thẻ kho của kho kiểm thử ngoài phân công (403); quyền EXPORT không đủ để ghi đè lô quá hạn (403).
- `viewer.demo`: có VIEW, không EXPORT; đọc được điểm giao trong phạm vi, nút ghi bị ẩn và API cập nhật trả 403.
- Ca cấp/thu hồi quyền động với JWT cũ thuộc bằng chứng H2 của đợt sửa trước. Đợt web này kiểm tra các quyền hiện tại trên tài khoản thật.
- 36 kiểm tra bố cục: 9 màn hình × 1440, 768, 390, 320 px; không tràn ngang toàn trang. Bảng rộng cuộn trong vùng riêng. Không ghi nhận lỗi JavaScript trong các luồng đã chạy.

## Dữ liệu kiểm thử còn trên máy

Tiền tố: **`S6LIVE-25287137`**. Các chứng từ có ghi rõ KIỂM THỬ, ảnh là chứng từ kiểm thử không có giá trị.

- Kho kiểm thử ID 3; đại lý ID 6; SKU FEFO kiểm thử ID 9; nhà cung cấp ID 4.
- Hai đơn ID 8 và 9 đã giao đủ; lượt soạn đã hoàn tất; 4 chuyến kiểm thử đã hoàn tất, không còn tiền hàng treo trong kho kiểm thử.
- SKU demo dùng cho đơn được nhập vào kho kiểm thử riêng; tồn ở kho kiểm thử kết thúc 1.584 lon, không còn giữ chỗ cho hai đơn này.
- SKU FEFO kiểm thử có lô quá hạn riêng để kiểm quyền; có 2 lon kiểm thử quá hạn tại kho ID 1, 5 lon tại kho kiểm thử ID 3. Các lô này không được chọn để giao bình thường.
- Giữ chứng từ để người dùng đối chiếu; không xóa lịch sử đã xác nhận.

Ảnh và kết quả máy đọc nằm tại `E:/DevTemp/UserTemp/s6-live-2026-10-25/`, gồm `pages.json`, `responsive.json`, `flow-requests.json` và ảnh từng màn hình. Tệp kết quả không lưu mật khẩu hay token.

Minh chứng tiêu biểu trên máy kiểm thử:

- [Thẻ kho và chứng từ nhập trả](E:/DevTemp/UserTemp/s6-live-2026-10-25/S6-02-live-return-source.png)
- [Giao diện giao hàng trên điện thoại](E:/DevTemp/UserTemp/s6-live-2026-10-25/S6-08-live-mobile.png)
- [Cổng đại lý sau khi giao đủ](E:/DevTemp/UserTemp/s6-live-2026-10-25/S6-09-DELIVERED.png)

Phạm vi nghiệm thu: các ca nêu trên trên database local và Chrome. Các ca cạnh tranh đồng thời/hoàn tác lỗi audit có bằng chứng H2 trước đó; chưa kiểm thử tải lớn hoặc chạy đồng thời nhiều người trên MySQL local.
