# SaleStock — Quản lý bán hàng và kho

Ứng dụng gồm:

- Backend Spring Boot 3, Java 17, Spring Security, JWT, JPA và MySQL.
- Frontend HTML, CSS và JavaScript thuần.
- Đăng nhập, gia hạn phiên, quên/đổi mật khẩu và phân quyền theo 7 vai trò.
- Quản trị tài khoản, gán kho/địa bàn và khóa hoặc mở khóa nhân sự.

## 1. Yêu cầu

Cài sẵn:

- Java Development Kit 17 trở lên.
- MySQL 8.
- Python 3.9 trở lên để phục vụ frontend. Có thể dùng một HTTP server khác nếu muốn.

Kiểm tra nhanh trong PowerShell:

```powershell
java -version
mysql --version
python --version
```

Repo đã có Maven Wrapper nên không bắt buộc cài Maven riêng.

## 2. Chuẩn bị MySQL

Backend có thể tự tạo database `quanlybanhang` nếu tài khoản MySQL có quyền tạo database. Có thể tạo trước bằng MySQL:

```sql
CREATE DATABASE quanlybanhang
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;
```

Không nên lưu mật khẩu MySQL thật vào Git. Trước khi chạy backend, đặt thông tin kết nối bằng biến môi trường trong PowerShell:

```powershell
$env:SPRING_DATASOURCE_URL="jdbc:mysql://localhost:3306/quanlybanhang?createDatabaseIfNotExist=true&useSSL=false&serverTimezone=Asia/Ho_Chi_Minh&allowPublicKeyRetrieval=true"
$env:SPRING_DATASOURCE_USERNAME="root"
$env:SPRING_DATASOURCE_PASSWORD="mat-khau-mysql-cua-ban"
$env:APP_JWT_SECRET="chuoi-bi-mat-rieng-cua-moi-truong-it-nhat-32-ky-tu"
$env:APP_BOOTSTRAP_ADMIN_PASSWORD="mat-khau-admin-ban-tu-chon"
$env:APP_BOOTSTRAP_ADMIN_EMAIL="email-quan-tri-thuc-te-cua-ban"
```

`APP_JWT_SECRET` là bắt buộc và phải là chuỗi bí mật riêng cho từng môi trường.
Không commit giá trị thật của biến này vào repository.

Các giá trị mặc định khác nằm trong [application.properties](backend/src/main/resources/application.properties).

## 3. Chạy backend

Nếu các bí mật từng được commit, cần xoay vòng chúng vì xóa khỏi file không loại bỏ chúng khỏi lịch sử Git.

Mở PowerShell tại thư mục repo:

```powershell
cd D:\GitHub\QuanLyBanHang\backend
.\mvnw.cmd spring-boot:run
```

Khi thấy ứng dụng khởi động thành công, API hoạt động tại:

```text
http://localhost:8080/api
```

Kiểm tra endpoint công khai:

```powershell
Invoke-RestMethod http://localhost:8080/api/test/public
```

Để dừng backend, nhấn `Ctrl+C` trong cửa sổ PowerShell đang chạy.

## 4. Chạy frontend

Giữ backend hoạt động. Mở một cửa sổ PowerShell khác tại thư mục gốc của repo:

```powershell
cd D:\GitHub\QuanLyBanHang
python serve_frontend.py
```

Sau đó mở:

```text
http://localhost:5500/login
```

Có thể chạy `.\run_frontend.ps1` từ PowerShell. Mở `/dashboard`, `/products`,
`/profile`, `/admin/users`, `/admin/audit-logs` hoặc `/warehouse/units`.
Các đường dẫn cũ `/frontend/*.html` tự chuyển sang đường dẫn mới.
Server chỉ phục vụ thư mục frontend; không mở trang bằng `file://`.

## 5. Tài khoản đăng nhập

### Tài khoản luôn được tạo

Ở lần chạy đầu tiên, backend tự tạo tài khoản quản trị bằng mật khẩu trong
`APP_BOOTSTRAP_ADMIN_PASSWORD`:

| Tên đăng nhập | Mật khẩu | Vai trò |
|---|---|---|
| `admin` | Giá trị `APP_BOOTSTRAP_ADMIN_PASSWORD` | Quản trị hệ thống |

Dùng mật khẩu riêng cho mỗi môi trường và cập nhật hồ sơ quản trị bằng thông tin thực tế.

### Tài khoản và dữ liệu demo

Hai chế độ seed mặc định tắt. Để tạo bộ dữ liệu demo trong MySQL, đặt các biến
sau trước khi khởi động backend (hoặc dùng `run_backend_example.ps1` sau khi
cấu hình kết nối database và các bí mật của môi trường):

```powershell
$env:APP_SEED_DEMO_USERS="true"
$env:APP_DEMO_USER_PASSWORD="mat-khau-demo-ban-tu-chon"
$env:APP_SEED_DEMO_DATA="true"
cd D:\GitHub\QuanLyBanHang\backend
.\mvnw.cmd spring-boot:run
```

| Biến | Mặc định | Tác dụng |
|---|---|---|
| `APP_SEED_DEMO_USERS` | `false` | Tạo các tài khoản bên dưới cùng 2 kho và 3 địa bàn demo |
| `APP_DEMO_USER_PASSWORD` | Không có | Bắt buộc khi bật seed tài khoản; mã hóa bằng password encoder của hệ thống |
| `APP_SEED_DEMO_DATA` | `false` | Tạo danh mục và giao dịch mẫu; có thể bật riêng mà không tạo tài khoản demo |

| Tên đăng nhập | Mật khẩu của tài khoản mới | Vai trò |
|---|---|---|
| `sales.manager` | Giá trị `APP_DEMO_USER_PASSWORD` | Quản lý kinh doanh |
| `sales.demo` | Giá trị `APP_DEMO_USER_PASSWORD` | Nhân viên kinh doanh, được gán 3 địa bàn demo |
| `warehouse.manager` | Giá trị `APP_DEMO_USER_PASSWORD` | Quản lý kho, được gán 2 kho demo |
| `warehouse.demo` | Giá trị `APP_DEMO_USER_PASSWORD` | Thủ kho, được gán 2 kho demo |
| `accountant.demo` | Giá trị `APP_DEMO_USER_PASSWORD` | Kế toán |
| `viewer.demo` | Giá trị `APP_DEMO_USER_PASSWORD` | Chỉ xem |

Tài khoản `admin` vẫn dùng mật khẩu bootstrap riêng. Tên đăng nhập đã tồn tại
được giữ nguyên mật khẩu, vai trò, trạng thái và phạm vi; thay đổi biến mật khẩu
không đặt lại các tài khoản cũ. Seeder không gửi email đến địa chỉ demo.

Bộ dữ liệu nghiệp vụ demo gồm:

- 2 kho, 3 địa bàn và 3 nhà cung cấp.
- 12 nhóm hàng theo cây 3 cấp; 8 sản phẩm điện tử, gia dụng và đồ uống.
- 8 đơn vị quy đổi (lốc 6, thùng 24) và lịch sử giá khởi tạo cho sản phẩm mới.
- 5 đại lý thuộc 3 nhóm khách hàng, gồm 1 đại lý bị khóa; hạn mức tiền/số ngày nợ,
  6 điểm giao hàng và lịch sử phân công khi có `sales.demo` đang hoạt động.
- 3 bảng giá, mỗi bảng có 8 dòng; 3 chính sách chiết khấu theo SKU/nhóm hàng,
  có bậc phần trăm và số tiền trên đơn vị cơ sở. Hiệu lực demo: 2020–2099.
- 4 phiếu nhập gắn nhà cung cấp, 4 phiếu xuất gắn mã đơn trong ghi chú và
  12 dòng tồn kho tính theo đơn vị cơ sở.
- 7 đơn: 4 đã ghi sổ, 1 chờ duyệt vì bán dưới giá sàn, 1 đã hủy, 1 bị từ chối.
  Doanh số và chiết khấu được tính từ các đơn đã ghi sổ qua service hiện có.
- Nhật ký thực hiện tạo danh mục, ghi đơn và thay đổi tồn kho.

Bổ sung S8-04: chỉ tiêu của `sales.demo`, `sales.manager` và ba khu vực demo trong tháng trước, tháng hiện tại và tháng tới. Kỳ trước có nhật ký sửa chỉ tiêu kèm lý do; tiến độ lấy từ đơn của đại lý được phân công. Tài khoản Quản trị viên không được tạo chỉ tiêu cá nhân giả; đăng nhập `sales.demo` để xem tab **Tiến độ của tôi** có dữ liệu. Chạy seed lại không ghi đè các chỉ tiêu đã sửa.

Phiếu tài chính demo `DEMO-TR-NHAP-DA-NHAN-002` liên kết phiếu kho đã xác nhận `DEMO-PTH-DA-NHAN-002`; nhận kho dùng service nghiệp vụ, chỉ cộng tồn một lần.

Các số lượng trên mô tả bộ demo ban đầu; seed bổ sung thêm dữ liệu cho các luồng đã triển khai. Mã nghiệp vụ có tiền tố
`DEMO-`, tên/ghi chú có nhãn Demo. Có thể tìm các mã này trên trang quản lý.
Seeder tìm từng bản ghi theo mã/tên đăng nhập, thêm phần còn thiếu và không
ghi đè dữ liệu đã lưu. Khởi động lại không nhân đôi đơn/phiếu hoặc cộng tồn
kho lần nữa. Toàn bộ một lần seed demo dùng cùng transaction; lỗi sẽ rollback.
Kho/địa bàn được tạo khi bật một trong hai chế độ vì là quan hệ bắt buộc.

Để ngừng seed ở các lần chạy sau:

```powershell
$env:APP_SEED_DEMO_USERS="false"
$env:APP_SEED_DEMO_DATA="false"
```

Tắt seed không xóa bản ghi đã tạo. Nếu không bật seed, database mới chỉ được
khởi tạo vai trò và `admin`, với `APP_BOOTSTRAP_ADMIN_PASSWORD` cùng
`APP_BOOTSTRAP_ADMIN_EMAIL` thực tế. Chỉ bật dữ liệu demo trong môi trường
phát triển/kiểm thử. Số dư công nợ và định mức tồn kho vẫn cần nguồn dữ liệu
nghiệp vụ tương ứng; seeder chỉ tạo hạn mức trong hồ sơ đại lý.

## 6. Ví dụ gọi API đăng nhập

PowerShell:

```powershell
$loginBody = @{
  username = "admin"
  password = "mat-khau-admin-da-cau-hinh"
} | ConvertTo-Json

$session = Invoke-RestMethod `
  -Method Post `
  -Uri "http://localhost:8080/api/auth/login" `
  -ContentType "application/json" `
  -Body $loginBody

$session.accessToken
```

Gọi API cần đăng nhập:

```powershell
$headers = @{ Authorization = "Bearer $($session.accessToken)" }
Invoke-RestMethod `
  -Uri "http://localhost:8080/api/auth/me" `
  -Headers $headers
```

Đăng xuất và thu hồi cả access token lẫn refresh token:

```powershell
$logoutBody = @{ refreshToken = $session.refreshToken } | ConvertTo-Json
Invoke-RestMethod `
  -Method Post `
  -Uri "http://localhost:8080/api/auth/logout" `
  -Headers $headers `
  -ContentType "application/json" `
  -Body $logoutBody
```

Xem toàn bộ endpoint và request mẫu trong [backend/API_DOCUMENTATION.md](backend/API_DOCUMENTATION.md).

## 7. Email kích hoạt và đặt lại mật khẩu

Muốn gửi email thật, cấu hình SMTP trước khi chạy backend. Ví dụ với Gmail App Password:

```powershell
$env:SPRING_MAIL_USERNAME="email-cua-ban@gmail.com"
$env:SPRING_MAIL_PASSWORD="gmail-app-password"
```

Không dùng mật khẩu Gmail thông thường và không commit App Password vào repo.
Nếu chưa cấu hình hai biến trên, hệ thống không thể gửi email đặt lại mật khẩu thật.

Các URL frontend trong email mặc định dùng cổng `5500`:

```text
http://localhost:5500/login
http://localhost:5500/reset-password
```

## 8. Chạy kiểm thử

Báo cáo kiểm tra Sprint 3 hiện tại: [ngày 11/10/2026](docs/SPRINT_3_AUDIT_20261011.md).
Báo cáo có trạng thái frontend/backend của từng task, các lỗi còn lại trước
nghiệm thu và bằng chứng chạy API/giao diện.

Kiểm tra đường dẫn frontend và menu theo vai trò từ thư mục repo:

```powershell
python -m unittest discover -s tests -p test_frontend_server.py
node --test tests/menu-config.test.cjs
```

Xem các đường dẫn trang trong [docs/FRONTEND_ROUTES.md](docs/FRONTEND_ROUTES.md).

```powershell
cd D:\GitHub\QuanLyBanHang\backend
.\mvnw.cmd test
```

Kiểm tra cú pháp JavaScript từ thư mục gốc nếu máy đã cài Node.js:

```powershell
Get-ChildItem frontend\js -Filter *.js | ForEach-Object {
  node --check $_.FullName
}
```

## 9. Lỗi thường gặp

### CMD báo `mysql is not recognized`

MySQL Server có thể đã được cài và chạy nhưng thư mục chứa `mysql.exe` chưa nằm trong `PATH`. Với vị trí cài đặt MySQL 8.0 mặc định trên Windows, có thể chạy trực tiếp:

```cmd
"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe" -u root -p
```

Hoặc thêm thư mục MySQL tạm thời vào `PATH` của cửa sổ CMD hiện tại:

```cmd
set "PATH=%PATH%;C:\Program Files\MySQL\MySQL Server 8.0\bin"
mysql --version
mysql -u root -p
```

Thiết lập bằng `set` chỉ tồn tại trong cửa sổ CMD hiện tại. Muốn dùng lâu dài, thêm thư mục sau vào biến môi trường `Path` của Windows rồi mở lại CMD:

```text
C:\Program Files\MySQL\MySQL Server 8.0\bin
```

Kiểm tra dịch vụ MySQL từ PowerShell:

```powershell
Get-Service MySQL80
Get-NetTCPConnection -State Listen -LocalPort 3306
```

### Backend không kết nối được MySQL

- Kiểm tra dịch vụ MySQL đang chạy.
- Kiểm tra username, password và cổng `3306`.
- Kiểm tra tài khoản MySQL có quyền dùng hoặc tạo database `quanlybanhang`.

### Frontend báo `Failed to fetch`

- Đảm bảo backend đang chạy ở cổng `8080`.
- Đảm bảo frontend được mở từ `http://localhost:5500`, không phải `file://`.
- Nếu dùng cổng khác, thêm origin đó vào `app.cors.allowed-origins`.

### Không nhận được email

- Kiểm tra cấu hình SMTP và App Password.
- Kiểm tra log backend để xem lỗi gửi mail.
- Email không tồn tại vẫn nhận cùng thông báo trên giao diện nhằm tránh lộ tài khoản.

### Tài khoản bị khóa tạm

Sau 5 lần nhập sai liên tiếp, tài khoản bị khóa trong 15 phút. Đây là hành vi bảo mật dự kiến.
