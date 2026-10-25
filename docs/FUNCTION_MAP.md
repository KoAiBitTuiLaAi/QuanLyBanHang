# Vị trí chức năng theo Product Backlog

Đối chiếu Product Backlog với code trên nhánh đang xem; cập nhật ngày 24/10/2026. Mỗi dòng là vị trí thao tác hiện có, không phải kết luận toàn bộ tiêu chí của story đã đạt. Menu/nút chỉ hiện khi tài khoản có quyền tương ứng.

`Chỉ backend`: chưa có màn hình/menu. `Một phần`: chỉ mô tả phần hiện có, không coi story đã hoàn tất.

## Sprint 1 — Tài khoản, phân quyền và quản trị người dùng

- S1-01: Trang đăng nhập → Nhập tài khoản, mật khẩu → Đăng nhập.
- S1-02: Mọi trang đã đăng nhập → Tự gia hạn phiên; nút Đăng xuất trên thanh trên cùng hoặc cuối menu trái.
- S1-03: Trang đăng nhập → Quên mật khẩu → Nhập email → Mở liên kết đặt lại mật khẩu trong email.
- S1-04: Tổng quan → Nút Đổi mật khẩu trên thanh trên cùng → Nhập mật khẩu hiện tại và mật khẩu mới.
- S1-05: Hệ thống → Quản lý tài khoản → Ma trận quyền → Chọn quyền theo vai trò, Lưu ma trận quyền.
- S1-06: Menu trái trên các trang → Các mục theo quyền; cuối menu hiển thị tên, vai trò và kho/địa bàn.
- S1-07: Tự mở Trang lỗi khi sai đường dẫn/thiếu quyền → Quay lại hoặc về Tổng quan.
- S1-08: Hệ thống → Quản lý tài khoản → Tạo tài khoản, Sửa, tìm kiếm và lọc tài khoản.
- S1-09: Hệ thống → Quản lý tài khoản → Tạo tài khoản/Sửa → Chọn vai trò, kho và địa bàn.
- S1-10: Hệ thống → Quản lý tài khoản → Khoá/Mở khoá tại dòng nhân sự → Nhập lý do khi khoá.

## Sprint 2 — Danh mục hàng hoá và bảng giá

- S2-01: Hệ thống → Quản lý tài khoản → Nhập Excel → Tải tệp mẫu, chọn tệp, xem trước, nhập dòng hợp lệ.
- S2-02: Hồ sơ cá nhân → Thông tin liên hệ → Sửa họ tên, số điện thoại → Lưu thay đổi.
- S2-03: Hồ sơ cá nhân → Đổi ảnh đại diện → Chọn ảnh JPG/PNG.
- S2-04: Hệ thống → Nhật ký thao tác → Lọc người dùng, đối tượng, khoảng thời gian → Xem giá trị trước/sau.
- S2-05: Kinh doanh → Sản phẩm → Thêm/Sửa sản phẩm, tìm kiếm, ngừng kinh doanh.
- S2-06: Kinh doanh → Nhóm hàng → Thêm nhóm gốc/nhóm con, sửa nhóm, chuyển sản phẩm sang nhóm khác.
- S2-07: Kho hàng → Đơn vị tính → Thêm/Sửa đơn vị quy đổi theo SKU; dùng đơn vị đã khai báo khi tạo đơn hoặc lập chứng từ kho.
- S2-08: Kinh doanh → Sản phẩm → Nhập Excel → Tải file mẫu, chọn tệp, xem trước tạo mới/cập nhật, Nhập dữ liệu.
- S2-09: Kho hàng → Nhà cung cấp → Thêm/Sửa, tìm kiếm, ngừng giao dịch.
- S2-10: Kinh doanh → Bảng giá → Tạo bảng giá/Sửa/Tạo phiên bản → Nhóm khách hàng, ngày hiệu lực, giá bán và giá sàn.

## Sprint 3 — Chiết khấu và hồ sơ đại lý

- S3-01: Kinh doanh → Chiết khấu sản lượng → Tạo/Sửa chính sách → SKU/nhóm hàng, các bậc chiết khấu.
- S3-02: Kinh doanh → Sản phẩm → Lịch sử giá tại dòng sản phẩm → Lọc lịch sử, xem giá cũ/mới và người sửa.
- S3-03: Kinh doanh → Đại lý → Thêm đại lý/Sửa thông tin, ngừng giao dịch.
- S3-04: Kinh doanh → Đại lý → Điểm giao hàng tại dòng đại lý → Thêm/Sửa, chọn mặc định; chọn lại khi Tạo đơn hàng.
- S3-05: Tài chính → Hạn mức công nợ → Khai báo hạn mức → Hạn mức tiền, số ngày nợ, lý do thay đổi.
- S3-06: Kinh doanh → Đại lý → Phân công người phụ trách, Chuyển giao hàng loạt, Xem lịch sử phân công.
- S3-07: Kinh doanh → Đại lý → Khoá giao dịch/Mở khoá giao dịch tại dòng đại lý → Lý do khoá.
- S3-08: Kinh doanh → Đại lý → Ô tìm kiếm và bộ lọc khu vực, nhóm khách hàng, người phụ trách, trạng thái.
- S3-09: Kinh doanh → Tạo đơn hàng → Chọn đại lý/điểm giao/ngày giao, thêm hàng → Lưu nháp hoặc Ghi nhận đơn.

## Sprint 4 — Áp giá tự động, kiểm tồn và duyệt đơn

- S4-01: Kinh doanh → Tạo đơn hàng → Thêm/đổi số lượng dòng hàng → Tự áp giá, chiết khấu; cảnh báo giá dưới sàn.
- S4-02: Kinh doanh → Tạo đơn hàng → Chọn đại lý → Xem công nợ, hạn mức còn lại và cảnh báo/chặn đơn.
- S4-03: Kinh doanh → Tạo đơn hàng → Dòng hàng → Xem tồn khả dụng, cảnh báo vượt tồn, Đặt tối đa.
- S4-04: Kinh doanh → Tạo đơn hàng → Chọn đại lý → Lịch sử mua hàng 3 tháng gần nhất → Thêm hàng/Thêm tất cả vào đơn.
- S4-05: Kinh doanh → Duyệt đơn hàng → Mở đơn chờ duyệt → Duyệt, Từ chối, Trả lại sửa và lịch sử duyệt.
- S4-06: Kinh doanh → Đơn hàng → Xem chi tiết → Theo dõi vòng đời, Chuyển bước tiếp theo, Huỷ đơn kèm lý do.
- S4-07: Kinh doanh → Đơn hàng → Bộ lọc trạng thái, đại lý, nhân viên, khu vực, ngày → Danh sách và tổng tiền.
- S4-08: Kinh doanh → Tạo đơn hàng → Ghi nhận đơn thành công → Bản PDF xem trước, In đơn hàng.
- S4-09: Kinh doanh → Đại lý → Lịch sử đơn hàng → Sao chép → Mở bản nháp tại Tạo đơn hàng, áp lại giá hiện hành.
- S4-10: Kinh doanh → Cổng đại lý → Chọn sản phẩm → Xem giỏ hàng → Thông tin giao nhận, công nợ → Gửi đơn hàng.

## Sprint 5 — Cổng đại lý và nghiệp vụ tồn kho

- S5-01: Kinh doanh → Đơn hàng của tôi → Lọc trạng thái/ngày → Xem chi tiết, ngày giao dự kiến, số lượng thực giao và thiếu.
- S5-02: Kinh doanh → Đơn hàng của tôi → Đặt lại/Đặt lại đơn này → Chọn toàn bộ/một phần hàng, sửa số lượng → Xác nhận đặt hàng.
- S5-03: Kho hàng → Kho & vị trí lưu → Thêm/Sửa kho, vị trí khu/kệ, gán sản phẩm; Kho phục vụ đại lý → Gán kho.
- S5-04: Kho hàng → Đơn vị tính → Lập chứng từ → Nhập kho (Nhà cung cấp) → Chọn NCC/kho, nhập chứng từ, đơn vị, lô/HSD → Lưu bản nháp/Xác nhận ghi sổ kho.
- S5-05: Kho hàng → Tồn kho → Lọc SKU, nhóm hàng, kho, trạng thái → Xem tồn thực tế, giữ chỗ và khả dụng.
- S5-06: Tự giữ tồn khi duyệt đơn, nhả khi huỷ/hết hạn; Kinh doanh → Đơn hàng → Xem chi tiết để xem giữ chỗ; Kho hàng → Tồn kho để đối chiếu.
- S5-07: Kho hàng → Chuyển kho nội bộ → Lập phiếu chuyển → Chọn kho đi/đến, hàng → Xuất chuyển; Chi tiết → Xác nhận thực nhận/Hủy.
- S5-08: Kho hàng → Đơn vị tính → Kiểm kê kho → Tạo phiếu kiểm kê mới theo kho/nhóm hàng → Nhập thực đếm, Lưu nháp tiến độ → Nhập lý do, Chốt kiểm kê & Cân bằng kho.
- S5-09: Kho hàng → Ngưỡng tồn tối thiểu → Khai báo SKU/kho; xem cảnh báo tại Tổng quan hoặc Kho hàng → Tồn kho → Lọc dưới mức tối thiểu.

## Sprint 6 — Xuất kho và giao hàng

- S6-01: Kho hàng → Lô & hạn sử dụng → Tồn theo lô/Cấu hình theo SKU → Bật/tắt quản lý lô, ngưỡng cảnh báo, Xuất lô/ghi đè quá hạn kèm lý do.
- S6-02: Kho hàng → Thẻ kho sản phẩm → Chọn SKU, lọc kho/ngày → Xem nhập, xuất, chuyển, điều chỉnh, tồn sau phát sinh và Chứng từ gốc.
- S6-03: Kho hàng → Phiếu soạn hàng → Tạo lượt soạn, gộp đơn → Mở lượt, nhập thực soạn/lý do thiếu, Lưu dòng → Hoàn tất/Hủy lượt.
- S6-04: Kho hàng → Lô & hạn sử dụng → Gợi ý xuất FEFO → Chọn kho/SKU/số lượng, lấy gợi ý, chọn lô khác kèm lý do, Xác nhận xuất lô.
- S6-05: Kho hàng → Xuất kho theo đơn → Chờ xuất kho, Xác nhận xuất kho, Còn nợ hàng (xuất bù), Các phiếu đã xác nhận (chỉ đọc).
- S6-06: Kho hàng → Phân tuyến giao hàng → Chọn kho, gom phiếu theo khu vực, gán tài xế/xe, lập lộ trình và in danh sách điểm giao.
- S6-07: Kho hàng → Theo dõi chuyến giao → Tiền hàng trên đường, điểm đã giao/còn lại, lọc kho/trạng thái/quá hạn, Xem điểm giao và chuyến còn hàng chưa xử lý.
- S6-08: Một phần: Kho hàng → Xác nhận giao hàng → Xem đơn thật, mở form/xem trước ảnh; backend có API xác nhận ảnh và tải chứng từ theo chuyến/điểm giao, frontend chưa nối lưu xác nhận/hoàn kho.
- S6-09: Một phần (frontend): Kinh doanh → Đơn hàng của tôi → Theo dõi giao hàng → 5 mốc, thực giao từng dòng; mốc thiếu API hiện “Chưa có dữ liệu”.

## Sprint 7 — Tài chính và công nợ

- S7-01: Tài chính → Hóa đơn bán hàng → Gộp phiếu đã giao, xem trước/phát hành theo thực giao, xem hóa đơn, lập chứng từ điều chỉnh, in/PDF.
- S7-02: Tài chính → Ghi nhận thanh toán → Ghi số tiền, hình thức thu, đối trừ tự động FIFO hoặc chọn hoá đơn cụ thể, thu một phần, tra cứu nợ và lập bút toán điều chỉnh.
- S7-04: Tự đánh giá rủi ro nợ định kỳ và sau thu tiền; chỉ backend → `/api/finance/debt/config` (cấu hình), `/api/finance/debt/flagged-dealers` (cảnh báo).
- S7-05: Tài chính → Thu tiền theo tuyến → Ghi khoản thu kèm biên lai, xem chi tiết, xác nhận nhận tiền, đối chiếu theo nhân viên/ngày thu.
- S7-06: Tài chính → Đối chiếu công nợ → Lập/lưu biên bản theo kỳ, xem lịch sử và PDF; đại lý vào cùng mục → Mở biên bản → Xác nhận khớp hoặc phản hồi lệch, lưu DB.
- S7-07: Kinh doanh → Công nợ của tôi → Xem tổng còn nợ, hạn mức còn lại, danh sách hoá đơn kèm hạn thanh toán, xem và tải PDF hoá đơn đã phát hành.
- S7-08: Tự động chạy hàng ngày lúc 7h; Kinh doanh → Thông báo của tôi → Xem nhắc hạn thanh toán trước 3 ngày, quá hạn, lọc chưa đọc, đánh dấu đã đọc.
- S7-09: Tài chính → Ghi nhận thanh toán → In phiếu thu tiền công nợ (mã duy nhất, thông tin đại lý, số tiền bằng số/chữ, hình thức, người thu), in hoặc tải file PDF.

## Sprint 8 — Trả hàng và báo cáo

- S8-01: Kho hàng → Trả hàng đại lý → Lập phiếu từ hóa đơn gốc, chọn dòng/số lượng/lý do/lô và kho nhận, xem chi tiết, xác nhận nhập lại kho hoặc hủy phiếu chờ.
- S8-02: Tài chính → Điều chỉnh công nợ → Chọn hóa đơn gốc, lưu phiếu trả nháp, xác nhận giảm nợ, xem chứng từ hoặc hủy nháp.
- S8-03: Kho hàng → Phiếu điều chỉnh → Lập phiếu điều chỉnh (chọn kho, lý do hàng hỏng/mất/hết hạn, ghi rõ giá trị tổn thất), xem chi tiết, liên kết Thẻ kho, Quản lý kho phê duyệt hoặc từ chối (ràng buộc người lập và người duyệt không được là một).
- S8-04: Kinh doanh → Chỉ tiêu doanh số (`/sales-targets`) → Đặt/sửa chỉ tiêu theo nhân viên và khu vực, theo dõi tiến độ thực tế, khóa kỳ đã qua và ghi nhật ký khi sửa. (đặt/sửa/khóa chỉ tiêu tháng theo nhân viên và khu vực, sửa kỳ đã khóa kèm lý do và nhật ký), `/api/sales-targets/my-progress` (nhân viên xem tiến độ thực tế giữa tháng), `/api/sales-targets/summary` (tổng hợp tiến độ); tự động khóa chỉ tiêu đã qua kỳ.
- S8-05: Báo cáo → Dashboard điều hành → Sáu chỉ số, doanh số theo ngày/khu vực, đại lý quá hạn và phân trang; lối tắt ở Tổng quan → Công việc.
- S8-06: Một phần (frontend): Kinh doanh → Báo cáo doanh số → Chọn chiều nhóm, bộ lọc, so sánh cùng kỳ và xuất Excel; chờ API backend để tải/xuất dữ liệu thật.
- S8-07: Báo cáo → Tồn kho & Vòng quay → Xem giá trị tồn theo kho và theo nhóm hàng, số ngày tồn bình quân SKU, phát hiện hàng chậm luân chuyển đang chôn vốn và xuất Excel.
- S8-08: Một phần: Kho hàng → Tồn kho → Áp dụng bộ lọc → Xuất Excel; đã có API `/api/warehouse/stock-ledger/export` xuất XLSX theo bộ lọc, nút giao diện vẫn xuất tại trình duyệt và chưa nối API.
- S8-09: Một phần (frontend): Báo cáo → Công nợ theo tuổi nợ → Tổng hợp nhóm tuổi nợ/nhân viên, ưu tiên đại lý và Excel; chờ backend để tải dữ liệu/xuất file thật.
- S8-10: Tài chính → Sổ chi tiết công nợ → Chọn đại lý và khoảng thời gian, xem phát sinh tăng giảm kèm số dư lũy kế, xuất PDF và Excel.

## Nguồn đối chiếu

- Product Backlog: `HỆ THỐNG BÁN HÀNG & KHO.xlsx`, sheet `4. Product Backlog` (story), `5. Sprint Plan` (sprint). Không dùng cột trạng thái của Excel để kết luận code đã hoàn tất.
- Vị trí thực tế: [menu-config.js](../frontend/js/menu-config.js), [các route frontend](../serve_frontend.py), HTML/JavaScript từng trang và [controller backend](../backend/src/main/java/com/quanlybanhang/controller).
- Các story S7/S8 khác chưa có luồng tương ứng để hướng dẫn tại mốc trên nên chưa liệt kê. Khi bổ sung, cập nhật đúng dòng story trong file này.
