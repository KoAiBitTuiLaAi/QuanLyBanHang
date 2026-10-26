# S8-09 — Báo cáo công nợ quá hạn theo tuổi nợ

Vị trí: **Báo cáo → Công nợ theo tuổi nợ** (`/reports/debt-aging`). Frontend gọi backend qua `Auth.apiFetch`; quyền đọc là `REPORT_FINANCE` từ ma trận DB, còn `ROLE_ADMIN` luôn được phép. Backend chịu trách nhiệm tính nhóm tuổi nợ, tổng hợp, giới hạn phạm vi nhân viên, xếp hạng rủi ro và kiểm tra quyền. Frontend chỉ xác thực cấu trúc/phạm vi phản hồi, trình bày snapshot và xuất Excel từ snapshot đầy đủ đã tải.

## API đang triển khai

Các endpoint dưới đây có tiền tố `/api` và quyền `REPORT_FINANCE` hoặc `ROLE_ADMIN`:

| Method | Endpoint | Mục đích |
|---|---|---|
| `GET` | `/api/reports/debt-aging/options` | Ngày chốt mặc định, múi giờ, nhóm tuổi nợ và nhân viên phụ trách |
| `GET` | `/api/reports/debt-aging?asOfDate=YYYY-MM-DD&employeeId={id}` | Báo cáo cho một nhân viên |
| `GET` | `/api/reports/debt-aging?asOfDate=YYYY-MM-DD&unassignedOnly=true` | Báo cáo đại lý chưa phân công |
| `GET` | `/api/reports/debt-aging?asOfDate=YYYY-MM-DD` | Báo cáo tất cả nhân viên |
| `GET` | `/api/reports/debt-aging/export` | Endpoint Excel backend; giao diện hiện xuất workbook từ snapshot đầy đủ đã tải |

JSON API trả envelope `{ success, message, data }`. Adapter yêu cầu HTTP thành công, `success: true` và có `data`; lỗi máy chủ được hiển thị, không biến thành báo cáo rỗng. Các trường tiền trong DTO là chuỗi thập phân để giữ chính xác. `complete: true` là bắt buộc; giới hạn báo cáo hiện tại là 5.000 đại lý.

## Hành vi giao diện

- Khi mở trang, tải options; không tự tải báo cáo. Người dùng chọn ngày chốt và phạm vi nhân viên rồi bấm **Xem báo cáo**.
- Báo cáo hiển thị tổng quá hạn, số đại lý, số ngày quá hạn lớn nhất, nhóm tuổi nợ, nhân viên và danh sách đại lý theo thứ hạng rủi ro do máy chủ trả về.
- Khi bộ lọc đổi hoặc quyền bị thu hồi, xóa snapshot cũ, hủy request đang chạy và khóa xuất cho tới khi tải lại thành công.
- Xuất Excel gồm bốn sheet từ toàn bộ snapshot đã xác thực, không chỉ trang đang xem; dữ liệu tiền giữ dạng text và ô không tạo formula/hyperlink.
- Backend là lớp bảo vệ quyền và phạm vi dữ liệu. Frontend ẩn/khóa chức năng khi thiếu quyền nhưng không thay cho kiểm tra quyền API.

## Kiểm chứng

Unit tests kiểm tra URL/query, envelope, lỗi HTTP/JSON và chuyển tiếp `AbortSignal`. Kiểm thử trình duyệt cô lập dùng HTTP fixture để kiểm tra luồng API, bộ lọc, dữ liệu, quyền giao diện và xuất workbook; fixture không đại diện cho kiểm tra kết nối với database đang chạy. Backend có unit/integration/security tests riêng cho tổng hợp, quyền DB và HTTP 403.
