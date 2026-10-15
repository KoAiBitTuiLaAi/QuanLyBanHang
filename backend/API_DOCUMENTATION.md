# SaleStock API — Sprint 1

Base URL: `http://localhost:8080/api`

Mọi response nghiệp vụ dùng cấu trúc `{ "success": boolean, "message": string, "data": any }`.

## Xác thực

| Method | Endpoint | Quyền | Mô tả |
|---|---|---|---|
| POST | `/auth/login` | Công khai | Đăng nhập; khóa 15 phút ở lần sai thứ 5 |
| POST | `/auth/refresh` | Công khai | Gia hạn access token bằng refresh token |
| POST | `/auth/forgot-password` | Công khai | Gửi liên kết đặt lại có hạn 30 phút |
| POST | `/auth/reset-password` | Công khai | Đặt lại mật khẩu; token dùng một lần |
| POST | `/auth/logout` | Có access hoặc refresh token | Thu hồi ngay các token hiện có; vẫn xử lý khi access token vừa hết hạn |
| POST | `/auth/change-password` | Đã đăng nhập | Đổi mật khẩu và thu hồi mọi phiên khác |
| GET | `/auth/me` | Đã đăng nhập | Hồ sơ, vai trò và phạm vi làm việc hiện tại |

### Đăng nhập

```json
POST /api/auth/login
{
  "username": "admin",
  "password": "<APP_BOOTSTRAP_ADMIN_PASSWORD>"
}
```

Sai tên đăng nhập hoặc mật khẩu luôn trả `401` cùng thông báo `Tên đăng nhập hoặc mật khẩu không đúng`. Lần sai thứ 5 trả `423` với `data.type = TEMPORARY_LOCK`.

Response thành công gồm `accessToken`, `refreshToken`, thông tin người dùng, `roles`, `permissions`, `warehouses`, `territories` và `mustChangePassword`.

### Đăng xuất

```json
POST /api/auth/logout
Authorization: Bearer <access-token>
{
  "refreshToken": "<refresh-token>"
}
```

### Đổi mật khẩu

```json
POST /api/auth/change-password
Authorization: Bearer <access-token>
{
  "currentPassword": "TempPass123",
  "newPassword": "MatKhauMoi123"
}
```

Mật khẩu mới tối thiểu 8 ký tự, có chữ và số. `tokenVersion` của tài khoản được tăng nên mọi JWT cũ mất hiệu lực ngay phía server.

## Quản trị tài khoản

Tất cả endpoint dưới `/admin/**` yêu cầu `ROLE_ADMIN` và được kiểm tra tại server.

| Method | Endpoint | Mô tả |
|---|---|---|
| GET | `/admin/users` | Tìm kiếm, lọc và phân trang (mặc định 20) |
| GET | `/admin/users/{id}` | Chi tiết tài khoản |
| POST | `/admin/users` | Tạo tài khoản, sinh mật khẩu tạm và gửi email |
| POST | `/admin/users/import/preview` | Kiểm tra trước từng dòng dữ liệu Excel đã được frontend chuyển thành JSON |
| POST | `/admin/users/import` | Nhập các dòng hợp lệ, bỏ qua dòng lỗi và trả báo cáo tổng kết |
| PUT | `/admin/users/{id}` | Sửa hồ sơ, nhiều vai trò, kho và địa bàn |
| POST | `/admin/users/{id}/lock` | Khóa, thu hồi phiên và cảnh báo bàn giao đại lý |
| POST | `/admin/users/{id}/unlock` | Mở khóa |
| GET | `/admin/assignment-options` | Danh sách 7 vai trò, kho và địa bàn |
| GET | `/admin/permissions` | Ma trận quyền và danh mục mã quyền/nhãn hiển thị của 7 vai trò; ma trận được đọc từ bảng `role_permissions` |
| PUT | `/admin/permissions/{role}` | Khai báo/cập nhật quyền cho vai trò; quyền được lưu vào database, ADMIN luôn được chuẩn hóa thành toàn bộ quyền |
| POST | `/admin/permissions/reset` | Khôi phục ma trận quyền 7 vai trò về mặc định ban đầu và lưu vào database |

### Tìm kiếm

```text
GET /api/admin/users?search=an&role=ROLE_SALES&active=true&page=0&size=20
```

`search` khớp tên, tài khoản hoặc số điện thoại. `size` tối đa 100.

### Tạo/cập nhật

```json
{
  "username": "nguyenvana",
  "email": "a@company.vn",
  "fullName": "Nguyễn Văn A",
  "phone": "0901234567",
  "roles": ["ROLE_SALES", "ROLE_VIEWER"],
  "warehouseIds": [],
  "territoryIds": [1]
}
```

Người có `ROLE_WAREHOUSE_MANAGER` hoặc `ROLE_WAREHOUSE_STAFF` bắt buộc có ít nhất một `warehouseId`. Quản trị viên không thể tự bỏ `ROLE_ADMIN` hoặc tự khóa tài khoản đang dùng.

### Khóa tài khoản

```json
POST /api/admin/users/15/lock
{
  "reason": "Nhân sự đã nghỉ việc"
}
```

Lý do là bắt buộc. Response trả `assignedDealerCount`; các đại lý liên quan được đánh dấu `handoverRequired = true`.

## Hồ sơ cá nhân

| Method | Endpoint | Quyền | Mô tả |
|---|---|---|---|
| GET | `/users/profile` | Đã đăng nhập | Xem hồ sơ, vai trò, kho, địa bàn và URL ảnh đại diện/thumbnail hiện tại |
| PUT | `/users/profile` | Đã đăng nhập | Chỉ cập nhật họ tên và số điện thoại Việt Nam |

## Kho & Tồn kho

| Method | Endpoint | Quyền | Mô tả |
|---|---|---|---|
| POST | `/warehouse/adjust` | `ROLE_WAREHOUSE_MANAGER`, `ROLE_ADMIN` | Điều chỉnh tồn kho; Nhân viên kinh doanh và Thủ kho bị cấm |
| POST | `/warehouse/import` | `ROLE_WAREHOUSE_STAFF`, `ROLE_WAREHOUSE_MANAGER`, `ROLE_ADMIN` | Nhập kho |
| POST | `/warehouse/export` | `ROLE_WAREHOUSE_STAFF`, `ROLE_WAREHOUSE_MANAGER`, `ROLE_ADMIN` | Xuất kho |
| GET | `/warehouse/options` | Vai trò được xem kho | Danh sách kho đang hoạt động dùng cho bộ chọn frontend |
| GET | `/warehouse/inventory` | `ROLE_WAREHOUSE_STAFF`, `ROLE_WAREHOUSE_MANAGER`, `ROLE_SALES_MANAGER`, `ROLE_ADMIN`, `ROLE_VIEWER` | Xem danh sách tồn kho |
| GET | `/warehouse/vouchers` | `ROLE_WAREHOUSE_STAFF`, `ROLE_WAREHOUSE_MANAGER`, `ROLE_SALES_MANAGER`, `ROLE_ADMIN`, `ROLE_VIEWER` | Xem các phiếu kho và snapshot quy đổi |

Mỗi thay đổi tồn kho được ghi tự động vào `/admin/audit-logs` với người thực hiện và giá trị trước/sau.

Các API nhập/xuất/điều chỉnh chỉ trả thành công sau khi lưu phiếu và tồn kho.
Body trống, `null`, `{}`, danh sách sản phẩm rỗng hoặc số lượng không hợp lệ trả
`400 Bad Request`. Payload đơn giản vẫn hỗ trợ `productId`, `quantity`, `unit`;
không tự thay số lượng thiếu bằng 1. `delta` chỉ dùng cho điều chỉnh tồn kho.
Kho phải tồn tại và đang hoạt động; nếu có nhiều kho phải truyền `warehouseId`.
Nếu chưa có kho, API báo thiếu cấu hình, không tự tạo kho mặc định.

## Tài chính (Bảo mật giá vốn & biên lợi nhuận)

| Method | Endpoint | Quyền | Mô tả |
|---|---|---|---|
| GET | `/finance/cost-price` | `ROLE_ADMIN`, `ROLE_SALES_MANAGER` | Xem giá vốn sản phẩm |
| GET | `/finance/profit-margin` | `ROLE_SALES_MANAGER` | Xem biên lợi nhuận — chỉ Quản lý kinh doanh |
| GET | `/finance/debt` | `ROLE_ADMIN`, `ROLE_SALES_MANAGER`, `ROLE_ACCOUNTANT` | Xem hạn mức tiền và số ngày nợ tối đa từ hồ sơ đại lý; số dư công nợ hiện chưa có nguồn dữ liệu |
| PUT | `/finance/debt/{dealerId}/limit` | `ROLE_ACCOUNTANT`, `ROLE_SALES_MANAGER`, `ROLE_ADMIN` | Khai báo / cập nhật hạn mức công nợ và số ngày nợ tối đa (S3-05); bắt buộc nhập lý do, tự động ghi nhật ký giá trị trước/sau kèm lý do |

`cost-price` và `profit-margin` đọc danh mục sản phẩm trong database, trả `[]`
khi danh mục rỗng. Giá bán và giá vốn đều theo đơn vị cơ sở của sản phẩm.
`marginPercent = (sellingPrice - costPrice) / sellingPrice * 100`, làm tròn 2
chữ số thập phân; trả `null` khi giá bán bằng 0 hoặc thiếu giá. Đây là biên lợi
nhuận theo giá danh mục hiện tại.

`debtAmount` trả `null`, `debtAmountAvailable` trả `false` vì hệ thống chưa có sổ
công nợ và phiếu thu để tính số dư. Không xem tổng tiền đơn hàng là số tiền chưa
thu, không trả số dư 0 giả. Trang hạn mức tiếp tục dùng `debtLimit` và
`maxDebtDays` thực tế.

Body cập nhật hạn mức công nợ (S3-05):

```json
{
  "debtLimit": 50000000,
  "maxDebtDays": 30,
  "reason": "Điều chỉnh hạn mức quý 4 theo doanh số"
}
```

Response ví dụ:
```json
{
  "success": true,
  "message": "Đã cập nhật hạn mức công nợ",
  "data": {
    "dealerId": 1,
    "customerName": "Đại lý Cấp 1 Minh Phát",
    "debtLimit": 50000000,
    "maxDebtDays": 30,
    "reason": "Điều chỉnh hạn mức quý 4 theo doanh số"
  }
}
```

## Quản lý nhóm hàng nhiều cấp & Sản phẩm (S2-06)

Hỗ trợ Quản lý kinh doanh xem và phân tích doanh số theo ngành hàng/phân nhóm nhiều cấp thay vì chỉ xem từng mã sản phẩm lẻ.

### Quy tắc nghiệp vụ cốt lõi:
1. **Cấu trúc cây tối thiểu 3 cấp**: Cấp 1 (Ngành hàng, `parent = null`, `level = 1`) -> Cấp 2 (Phân nhóm, `level = 2`) -> Cấp 3+ (Nhóm con / chi tiết, `level >= 3`). Tự động tính cấp bậc và chống vòng lặp chu trình (không thể chọn con cháu làm cha).
2. **Chuyển sản phẩm giữa các nhóm**: Hỗ trợ chuyển một hoặc nhiều sản phẩm đồng thời sang nhóm đích.
3. **Nhóm còn sản phẩm thì không xoá được**: Nếu nhóm hàng trực tiếp hoặc bất kỳ nhóm con nào của nó còn sản phẩm, thao tác xoá bị chặn và trả về lỗi `400 Bad Request` (`CategoryNotEmptyException`).
4. **Xem doanh số theo ngành hàng**: Tự động tính tổng doanh số (`totalRevenue`) và tổng số lượng sản phẩm (`totalProductCount`) cộng dồn của toàn bộ cây con bên dưới.

### Danh sách API Nhóm hàng (`/api/categories`)

| Method | Endpoint | Quyền | Mô tả |
|---|---|---|---|
| GET | `/categories/tree` | Đã đăng nhập | Xem cấu trúc cây phân cấp kèm tổng hợp doanh số và số sản phẩm từng nhánh |
| GET | `/categories` | Đã đăng nhập | Danh sách nhóm hàng dạng phẳng |
| GET | `/categories/{id}` | Đã đăng nhập | Xem chi tiết nhóm hàng và cây con |
| GET | `/categories/{id}/revenue` | Đã đăng nhập | Xem tổng hợp doanh số toàn nhánh của ngành hàng / nhóm hàng |
| POST | `/categories` | `ROLE_SALES_MANAGER`, `ROLE_ADMIN` | Tạo nhóm hàng mới (tự động gán `level = parent.level + 1`) |
| PUT | `/categories/{id}` | `ROLE_SALES_MANAGER`, `ROLE_ADMIN` | Cập nhật tên, mô tả, chuyển nhóm cha (ngăn ngừa chu trình) |
| DELETE | `/categories/{id}` | `ROLE_SALES_MANAGER`, `ROLE_ADMIN` | Xóa nhóm hàng (**Chặn nếu còn sản phẩm** -> 400 Bad Request) |
| POST | `/categories/move-products` | `ROLE_SALES_MANAGER`, `ROLE_ADMIN` | Chuyển hàng loạt sản phẩm sang nhóm hàng đích |
| PUT | `/categories/products/{productId}/move?targetCategoryId={id}` | `ROLE_SALES_MANAGER`, `ROLE_ADMIN` | Chuyển 1 sản phẩm sang nhóm hàng đích |

### Danh sách API Quản lý danh mục sản phẩm (S2-05)

Doanh số nhóm hàng (`directRevenue`, `totalRevenue`) và sản phẩm (`revenue`)
được tổng hợp từ `order_items.total_amount` sau chiết khấu của các đơn
`RECORDED`. Đơn chờ duyệt (`PENDING_APPROVAL`), bị từ chối (`REJECTED`), đã huỷ
(`CANCELLED`) và bản nháp không được tính. Chưa có giao dịch thì doanh số bằng
0. Nhóm cha cộng cả các nhóm con; sản phẩm chuyển nhóm được tính vào nhóm hiện
tại. Cột `products.revenue` cũ không được dùng làm nguồn số liệu.

`revenue` và `hasTransactions` là kết quả chỉ đọc; gửi hai trường này khi
tạo/sửa sản phẩm không làm thay đổi doanh số hay lịch sử giao dịch.

Hỗ trợ Quản lý kinh doanh quản lý danh mục sản phẩm chuẩn hóa toàn công ty (gọi tên và mã SKU thống nhất).

#### Quy tắc nghiệp vụ cốt lõi:
1. **Khai báo đầy đủ**: Mã SKU, tên sản phẩm, nhóm hàng, đơn vị tính cơ sở (cái, chai, hộp, kg...), quy cách đóng gói (Thùng 24 lon, Hộp 10 vỉ...), giá vốn, giá bán, ảnh sản phẩm, trạng thái.
2. **Mã SKU là duy nhất**: Hệ thống ràng buộc duy nhất trên toàn cơ sở dữ liệu (`unique`) và kiểm tra khi tạo mới / cập nhật.
3. **Giá vốn bảo mật**: CHỈ DUY NHẤT Quản lý kinh doanh (`ROLE_SALES_MANAGER`) xem và chỉnh sửa được giá vốn. Với các vai trò khác (kể cả Admin, Sales, Thủ kho, Kế toán), trường `costPrice` luôn trả về `null` và không thể sửa đổi giá vốn khi cập nhật.
4. **Bảo vệ sản phẩm có giao dịch**: Kiểm tra dòng đơn hàng hoặc phiếu kho thực tế trong database (kể cả đơn đã huỷ). Sản phẩm có lịch sử giao dịch thì **KHÔNG ĐƯỢC PHÉP XOÁ**, chỉ có thể chuyển sang trạng thái **Ngừng kinh doanh** (`active = false`). Cờ và doanh số nhập tay cũ không quyết định quyền xoá. Thao tác xoá bị chặn trả `400 Bad Request` (`ProductHasTransactionsException`).

| Method | Endpoint | Quyền | Mô tả |
|---|---|---|---|
| GET | `/products?categoryId={id}&search={q}&active={true/false}` | Đã đăng nhập | Danh sách sản phẩm (hỗ trợ lọc nhóm, tìm kiếm theo SKU/tên, trạng thái; giá vốn chỉ trả về cho Sales Manager) |
| GET | `/products/{id}` | Đã đăng nhập | Xem chi tiết sản phẩm kèm danh sách đơn vị quy đổi (giá vốn bảo mật) |
| GET | `/products/{id}/price-history` | `ROLE_SALES_MANAGER`, `ROLE_ADMIN` | Xem lịch sử thay đổi giá của một sản phẩm (giá cũ, giá mới, người sửa, thời điểm) |
| POST | `/products` | `ROLE_SALES_MANAGER`, `ROLE_ADMIN` | Tạo sản phẩm mới, có thể khai báo `unitConversions` (chỉ Sales Manager gán được giá vốn ban đầu) |
| PUT | `/products/{id}` | `ROLE_SALES_MANAGER`, `ROLE_ADMIN` | Cập nhật thông tin sản phẩm (chỉ Sales Manager sửa được giá vốn; vai trò khác giữ nguyên giá vốn cũ) |
| PATCH | `/products/{id}/status?active={true/false}` | `ROLE_SALES_MANAGER`, `ROLE_ADMIN` | Chuyển đổi trạng thái Đang kinh doanh / Ngừng kinh doanh |
| DELETE | `/products/{id}` | `ROLE_SALES_MANAGER`, `ROLE_ADMIN` | Xóa sản phẩm (**Chặn nếu đã phát sinh giao dịch** -> 400 Bad Request) |
| POST | `/products/upload-image` | `ROLE_SALES_MANAGER`, `ROLE_ADMIN` | Tải ảnh sản phẩm lên server (hỗ trợ JPG, PNG, WEBP tối đa 5MB) |
| GET | `/products/images/{fileName}` | Đã đăng nhập | Xem ảnh sản phẩm |
| GET | `/products/import/template` | `ROLE_SALES_MANAGER`, `ROLE_ADMIN` | Tải tệp Excel nhập liệu (`.xlsx`) chỉ có tiêu đề, sheet nhóm hàng thực tế và hướng dẫn |
| POST | `/products/import/preview` | `ROLE_SALES_MANAGER`, `ROLE_ADMIN` | Xem trước và kiểm tra lỗi từng dòng từ tệp Excel (`multipart/form-data`) hoặc JSON (`application/json`) |
| POST | `/products/import` | `ROLE_SALES_MANAGER`, `ROLE_ADMIN` | Nhập danh mục sản phẩm hàng loạt từ Excel (`multipart/form-data`) hoặc JSON (`application/json`). SKU đã tồn tại thì cập nhật thay vì tạo mới |

### Nhập danh mục sản phẩm hàng loạt từ Excel (S2-08)

Hỗ trợ Quản lý kinh doanh đưa đến 5.000 mã hàng hiện có vào hệ thống một cách nhanh chóng, chính xác mà không phải gõ tay.

#### Quy tắc nghiệp vụ cốt lõi:
1. **Tải tệp mẫu chuẩn (.xlsx)**:
   - Tệp gồm 3 sheet:
     - Sheet 1: `Sản phẩm` chỉ có tiêu đề các cột: `Mã SKU (*)`, `Tên sản phẩm (*)`, `Mã/Tên nhóm hàng (*)`, `Đơn vị tính cơ sở`, `Quy cách đóng gói`, `Giá bán`, `Giá vốn`, `Trạng thái`, `Mô tả`. Người dùng tự nhập danh mục thực tế; tải rồi nhập lại tệp trống không tạo sản phẩm demo.
     - Sheet 2: `Danh mục nhóm hàng` hiển thị toàn bộ mã và tên nhóm hàng hiện hành giúp người dùng dễ dàng sao chép.
     - Sheet 3: `Hướng dẫn` quy định rõ nguyên tắc nhập liệu.
2. **Xem trước & báo lỗi theo từng dòng**:
   - Endpoint: `POST /api/products/import/preview` (hỗ trợ cả tệp Excel `multipart/form-data` hoặc JSON array `application/json`).
   - Kiểm tra và báo lỗi chi tiết cho từng dòng:
     - SKU trống hoặc vượt quá 50 ký tự.
     - Tên sản phẩm trống hoặc vượt quá 255 ký tự.
     - Nhóm hàng không tìm thấy (tra cứu thông minh theo ID, Mã nhóm hoặc Tên nhóm hàng).
     - Trùng lặp mã SKU nội bộ trong cùng tệp tải lên (chỉ rõ dòng bị trùng).
     - Giá bán hoặc giá vốn âm.
     - Ô giá không phải số, trạng thái không hợp lệ, giá có quá 2 chữ số thập phân hoặc quá 13 chữ số phần nguyên.
     - Công thức giá trong `.xlsx` chưa có kết quả đã tính; không tự thay bằng 0.
     - Độ dài đơn vị tính, quy cách đóng gói, mô tả.
   - Thống kê tổng hợp: `totalRows`, `validRows`, `invalidRows`, `createCount`, `updateCount`.
3. **Cập nhật thay vì tạo mới khi SKU đã tồn tại**:
   - Tự động đối chiếu mã SKU trong cơ sở dữ liệu (không phân biệt hoa thường).
   - Trong bản xem trước:
     - SKU đã có trong hệ thống: đánh dấu `action = "UPDATE"` và `isUpdate = true`, kèm `existingProductId`.
     - SKU mới: đánh dấu `action = "CREATE"` và `isUpdate = false`.
   - Khi thực hiện nhập (`POST /api/products/import`):
     - Dòng `UPDATE`: cập nhật tên, nhóm hàng, đơn vị, quy cách, giá bán, giá vốn (nếu có quyền), trạng thái, mô tả cho sản phẩm hiện có. Tự động ghi nhật ký kiểm toán (Audit Log) nếu giá bán thay đổi.
       Ô giá bán, đơn vị cơ sở và trạng thái để trống giữ giá trị cũ; giá vốn để trống giữ nguyên giá vốn.
     - Dòng `CREATE`: tạo mới sản phẩm; doanh số và lịch sử giao dịch được tính từ đơn hàng/phiếu kho thực tế.
4. **Bảo mật giá vốn**:
   - CHỈ Quản lý kinh doanh (`ROLE_SALES_MANAGER` hoặc quyền `FINANCE_VIEW_COST_PRICE`) mới được xem và sửa giá vốn khi nhập từ Excel.
   - Nếu người dùng khác (ví dụ Quản trị viên không có quyền giá vốn) tải tệp có cột giá vốn: trường giá vốn sẽ được bỏ qua, giữ nguyên giá vốn hiện tại của SKU để đảm bảo tuyệt đối bí mật kinh doanh.
5. **Hiệu năng xử lý 5.000 mã hàng**:
   - Cơ chế truy vấn theo lô (`chunk` 500 SKU) và lưu hàng loạt (`saveAll`), tránh tình trạng N+1 query và không nghẽn cơ sở dữ liệu.
   - Thống kê doanh số/lịch sử giao dịch trong kết quả nhập được tải theo lô, không truy vấn riêng cho mỗi SKU.
6. **Tệp Excel và định dạng giá**:
   - `.xlsx`/`.xls` tối đa 20 MB; giới hạn multipart toàn ứng dụng là 20 MB/tệp và 21 MB/yêu cầu. Ảnh đại diện vẫn được service giới hạn 2 MB.
   - Nhận số trong ô Excel, chuỗi số như `12.50`, `12,50`, `18.000`, `1.234,56`, `1,234.56`, kèm hậu tố tiền tệ VND/đ/₫. Các nhóm ba chữ số được xem là phân cách hàng nghìn.
   - Dòng đọc sai giá/trạng thái được đánh dấu lỗi và bỏ qua khi nhập; các dòng hợp lệ vẫn được lưu. Xem trước không ghi database.

#### Cấu trúc Response kiểm tra / nhập (`ProductImportResponse`):
```json
{
  "success": true,
  "message": "Kết quả kiểm tra tệp nhập sản phẩm",
  "data": {
    "totalRows": 2,
    "validRows": 2,
    "invalidRows": 0,
    "createCount": 1,
    "updateCount": 1,
    "importedRows": 0,
    "rows": [
      {
        "rowNumber": 2,
        "sku": "HEIN-330",
        "name": "Bia Heineken lon 330ml",
        "category": "BEV-BEER",
        "resolvedCategoryId": 1,
        "resolvedCategoryName": "Bia các loại",
        "unit": "lon",
        "packingSpecification": "Thùng 24 lon",
        "price": 18000,
        "costPrice": 14500,
        "active": true,
        "description": "Bia cao cấp",
        "action": "UPDATE",
        "isUpdate": true,
        "existingProductId": 5,
        "valid": true,
        "errors": []
      },
      {
        "rowNumber": 3,
        "sku": "NEW-PROD-01",
        "name": "Sản phẩm mới 01",
        "category": "BEV-WATER",
        "resolvedCategoryId": 2,
        "resolvedCategoryName": "Nước giải khát",
        "unit": "chai",
        "packingSpecification": "Lốc 6 chai",
        "price": 10000,
        "costPrice": 7500,
        "active": true,
        "description": "Sản phẩm mới",
        "action": "CREATE",
        "isUpdate": false,
        "existingProductId": null,
        "valid": true,
        "errors": []
      }
    ]
  }
}
```

## Đơn vị tính quy đổi của SKU & Nhập xuất kho theo thùng/lon (S2-07)

Hỗ trợ Nhân viên kho khai báo đơn vị quy đổi theo cách kho đang gọi hàng (thùng, lốc...), để nhập xuất theo thùng mà sổ sách tồn kho và đơn hàng vẫn ghi nhận chính xác theo số lượng đơn vị cơ sở (số lon).

### Quy tắc nghiệp vụ cốt lõi:
1. **Mỗi SKU khai báo được nhiều đơn vị**: 1 đơn vị cơ sở (`unit`, vd: "lon" với hệ số 1) và nhiều đơn vị quy đổi (vd: "lốc" hệ số 6, "thùng" hệ số 24). Hệ số quy đổi luôn > 0.
2. **Đơn hàng và phiếu kho nhập theo đơn vị nào cũng quy về đơn vị cơ sở khi ghi sổ**:
   - Khi nhập/xuất kho theo "thùng", hệ thống tính: `baseQuantity = quantity * conversionFactor` (vd: 10 thùng * 24 = 240 lon). Sổ sách tồn kho (`warehouse_inventories`) luôn lưu trữ và cộng/trừ theo đơn vị cơ sở.
   - Khi tạo đơn hàng theo "thùng" hoặc "lốc", dòng đơn hàng lưu trữ số lượng quy về đơn vị cơ sở tương ứng.
3. **Đổi hệ số quy đổi không làm sai lệch các giao dịch đã ghi trước đó**:
   - Mọi dòng phiếu kho (`warehouse_voucher_items`) và dòng đơn hàng (`order_items`) đều **đóng băng** (snapshot) các giá trị `unit`, `conversionFactor`, `baseUnit`, `baseQuantity` tại thời điểm ghi sổ.
   - Khi nhà sản xuất thay đổi quy cách đóng gói hoặc người dùng sửa hệ số quy đổi của SKU (vd: đổi thùng từ 24 lon thành 20 lon), toàn bộ phiếu kho, đơn hàng và số tồn kho đã ghi sổ trước đó **hoàn toàn không bị thay đổi hay sai lệch**. Giao dịch mới phát sinh sau đó sẽ áp dụng hệ số mới.

### Danh sách API Đơn vị quy đổi (`/api/products/{productId}/unit-conversions`)

| Method | Endpoint | Quyền | Mô tả |
|---|---|---|---|
| GET | `/products/{productId}/unit-conversions` | Đã đăng nhập | Xem danh sách các đơn vị tính của SKU (luôn có đơn vị cơ sở hệ số 1.0 và các đơn vị phụ) |
| POST | `/products/{productId}/unit-conversions` | `ROLE_WAREHOUSE_STAFF`, `ROLE_WAREHOUSE_MANAGER`, `ROLE_SALES_MANAGER`, `ROLE_ADMIN` | Khai báo đơn vị quy đổi mới (vd: "thùng" với hệ số 24) |
| PUT | `/products/{productId}/unit-conversions/{id}` | `ROLE_WAREHOUSE_STAFF`, `ROLE_WAREHOUSE_MANAGER`, `ROLE_SALES_MANAGER`, `ROLE_ADMIN` | Cập nhật tên đơn vị, hệ số quy đổi |
| DELETE | `/products/{productId}/unit-conversions/{id}` | `ROLE_WAREHOUSE_STAFF`, `ROLE_WAREHOUSE_MANAGER`, `ROLE_SALES_MANAGER`, `ROLE_ADMIN` | Xóa đơn vị quy đổi |

### Danh sách API Kho & Phiếu kho (`/api/warehouse`)

| Method | Endpoint | Quyền | Mô tả |
|---|---|---|---|
| POST | `/warehouse/import` | `ROLE_WAREHOUSE_STAFF`, `ROLE_WAREHOUSE_MANAGER`, `ROLE_ADMIN` | Lập phiếu nhập kho (hỗ trợ nhập theo thùng/lốc, tự động quy về đơn vị cơ sở khi ghi sổ) |
| POST | `/warehouse/export` | `ROLE_WAREHOUSE_STAFF`, `ROLE_WAREHOUSE_MANAGER`, `ROLE_ADMIN` | Lập phiếu xuất kho (quy về đơn vị cơ sở và kiểm tra tồn kho theo đơn vị cơ sở) |
| POST | `/warehouse/adjust` | `ROLE_WAREHOUSE_MANAGER`, `ROLE_ADMIN` | Điều chỉnh tồn kho |
| GET | `/warehouse/inventory` | `ROLE_WAREHOUSE_STAFF`, `ROLE_WAREHOUSE_MANAGER`, `ROLE_SALES_MANAGER`, `ROLE_ADMIN`, `ROLE_VIEWER` | Xem tồn kho sổ sách theo đơn vị cơ sở (số lon) kèm bảng quy đổi tương đương theo thùng, lốc |
| GET | `/warehouse/vouchers` | `ROLE_WAREHOUSE_STAFF`, `ROLE_WAREHOUSE_MANAGER`, `ROLE_SALES_MANAGER`, `ROLE_ADMIN`, `ROLE_VIEWER` | Danh sách phiếu kho đã ghi sổ kèm thông tin đóng băng hệ số |
| GET | `/warehouse/vouchers/{id}` | `ROLE_WAREHOUSE_STAFF`, `ROLE_WAREHOUSE_MANAGER`, `ROLE_SALES_MANAGER`, `ROLE_ADMIN`, `ROLE_VIEWER` | Chi tiết phiếu kho |

`GET /warehouse/inventory?warehouseId={id}` chỉ đọc tồn đã ghi nhận. Kho chưa có
tồn trả danh sách rỗng; endpoint không tạo tồn mặc định hoặc ghi dữ liệu khi đọc.
Frontend chọn kho từ `/warehouse/options` và hiển thị 0 cho SKU chưa có bản ghi tồn.
Khi chưa cấu hình kho, trang vẫn tải sản phẩm và đơn vị quy đổi, báo thiếu kho
trên màn lập phiếu và tồn kho, đồng thời khoá nút ghi phiếu. Chưa có dữ liệu định
mức tồn kho thì hiển thị `Chưa thiết lập`; không gán ngưỡng 200 hoặc nhãn an toàn
cho tất cả SKU.

### Danh sách API Chuyển kho nội bộ (S5-07)

Hỗ trợ Nhân viên kho lập phiếu chuyển kho nội bộ để điều hàng từ kho thừa sang kho thiếu mà số liệu hai bên đều chính xác, minh bạch.

| Method | Endpoint | Quyền | Mô tả |
|---|---|---|---|
| POST | `/warehouse/transfers` (hoặc `/warehouse/transfer`) | `WAREHOUSE_TRANSFER`, `WAREHOUSE_EXPORT`, `ROLE_WAREHOUSE_STAFF`, `ROLE_WAREHOUSE_MANAGER`, `ROLE_ADMIN` | Lập phiếu chuyển kho nội bộ. Trừ tồn kho tại kho xuất, ghi nhận hàng đang trên đường (`inTransitQuantity`), CHƯA cộng vào kho nhập |
| GET | `/warehouse/transfers` | `WAREHOUSE_TRANSFER`, `WAREHOUSE_VIEW`, `ROLE_WAREHOUSE_STAFF`, `ROLE_WAREHOUSE_MANAGER`, `ROLE_SALES_MANAGER`, `ROLE_ADMIN`, `ROLE_VIEWER` | Danh sách phiếu chuyển kho (hỗ trợ lọc `fromWarehouseId`, `toWarehouseId`, `status`) |
| GET | `/warehouse/transfers/{id}` | `WAREHOUSE_TRANSFER`, `WAREHOUSE_VIEW`, `ROLE_WAREHOUSE_STAFF`, `ROLE_WAREHOUSE_MANAGER`, `ROLE_SALES_MANAGER`, `ROLE_ADMIN`, `ROLE_VIEWER` | Chi tiết phiếu chuyển kho |
| POST | `/warehouse/transfers/{id}/receive` | `WAREHOUSE_TRANSFER`, `WAREHOUSE_IMPORT`, `ROLE_WAREHOUSE_STAFF`, `ROLE_WAREHOUSE_MANAGER`, `ROLE_ADMIN` | Kho đến xác nhận nhận hàng. Nhận đủ hoặc thực nhận; tồn kho đến mới được cộng; chênh lệch bắt buộc nhập lý do |
| POST | `/warehouse/transfers/{id}/cancel` | `WAREHOUSE_TRANSFER`, `WAREHOUSE_APPROVE`, `ROLE_WAREHOUSE_MANAGER`, `ROLE_ADMIN` | Hủy phiếu chuyển kho khi đang trên đường (`IN_TRANSIT`); hoàn tồn cho kho xuất và xóa hàng trên đường |

#### Quy tắc nghiệp vụ chuyển kho (S5-07):
1. **Lập phiếu chuyển**:
   - Chọn kho xuất (`fromWarehouseId`), kho nhập (`toWarehouseId`), danh sách sản phẩm, số lượng và đơn vị tính (thùng, lốc, lon...).
   - Kho xuất và kho nhập không được trùng nhau. Cả hai kho phải đang hoạt động (`active = true`).
   - Kiểm tra tồn kho cơ sở tại kho xuất: nếu tồn kho không đủ để chuyển, hệ thống chặn với lỗi `400 Bad Request` ("Kho xuất không đủ tồn kho...").
   - Khi tạo thành công: tồn kho tại kho xuất bị trừ ngay lập tức; hàng được ghi nhận là đang trên đường chuyển (`status = IN_TRANSIT`, `inTransitQuantity` tại kho nhập tăng lên tương ứng); tồn kho khả dụng (`baseQuantity`) tại kho nhập **CHƯA ĐƯỢC CỘNG**.
2. **Xác nhận nhận hàng tại kho đến**:
   - Chỉ được xác nhận khi phiếu ở trạng thái `IN_TRANSIT`.
   - Nếu nhận đủ: `receivedQuantity` bằng số lượng xuất, tồn kho đến được cộng đủ, `inTransitQuantity` trừ về 0, trạng thái thành `COMPLETED`.
   - Nếu có chênh lệch (`receivedQuantity != quantity`): **BẮT BUỘC** phải nhập lý do (`discrepancyReason`). Nếu không nhập lý do, hệ thống trả về lỗi `400 Bad Request` ("Chênh lệch khi nhận hàng bắt buộc phải nhập lý do").
   - Tồn kho tại kho nhập chỉ được cộng đúng số lượng thực nhận (`receivedBaseQuantity`).
3. **Hủy phiếu chuyển kho**:
   - Chỉ áp dụng cho phiếu đang ở trạng thái `IN_TRANSIT`.
   - Hoàn lại tồn kho cho kho xuất và xóa lượng đang trên đường tại kho nhập. Trạng thái phiếu thành `CANCELLED`.

#### Ví dụ Request tạo phiếu chuyển kho (`POST /api/warehouse/transfers`):
```json
{
  "fromWarehouseId": 1,
  "toWarehouseId": 2,
  "note": "Điều hàng giải phóng tồn kho Hà Nội chuyển về kho Hải Phòng",
  "items": [
    {
      "productId": 10,
      "unit": "thùng",
      "quantity": 10,
      "note": "Hàng chuyển đợt 1"
    }
  ]
}
```

#### Ví dụ Request xác nhận nhận hàng có chênh lệch (`POST /api/warehouse/transfers/1/receive`):
```json
{
  "note": "Đã nhận hàng đợt 1 tại kho Hải Phòng",
  "items": [
    {
      "itemId": 1,
      "receivedQuantity": 9,
      "discrepancyReason": "Bể vỡ 1 thùng trong quá trình vận chuyển trên đường"
    }
  ]
}
```

### Danh sách API Danh mục nhà cung cấp (S2-09)

Hỗ trợ Nhân viên kho quản lý danh mục nhà cung cấp, để phiếu nhập kho luôn gắn đúng nguồn hàng nhằm truy nguyên khi có lô lỗi.

| Method | Endpoint | Quyền | Mô tả |
|---|---|---|---|
| GET | `/suppliers` | `ROLE_WAREHOUSE_STAFF`, `ROLE_WAREHOUSE_MANAGER`, `ROLE_ADMIN`, `ROLE_VIEWER` | Lấy danh sách nhà cung cấp (phân trang, tìm kiếm) |
| GET | `/suppliers/{id}` | `ROLE_WAREHOUSE_STAFF`, `ROLE_WAREHOUSE_MANAGER`, `ROLE_ADMIN`, `ROLE_VIEWER` | Xem chi tiết nhà cung cấp |
| POST | `/suppliers` | `ROLE_WAREHOUSE_STAFF`, `ROLE_WAREHOUSE_MANAGER`, `ROLE_ADMIN` | Tạo mới nhà cung cấp |
| PUT | `/suppliers/{id}` | `ROLE_WAREHOUSE_STAFF`, `ROLE_WAREHOUSE_MANAGER`, `ROLE_ADMIN` | Cập nhật thông tin nhà cung cấp |
| PATCH | `/suppliers/{id}/status?active=false` | `ROLE_WAREHOUSE_STAFF`, `ROLE_WAREHOUSE_MANAGER`, `ROLE_ADMIN` | Ngừng giao dịch; dùng `active=true` để khôi phục, giữ nguyên thông tin và phiếu kho |
| DELETE | `/suppliers/{id}` | `ROLE_WAREHOUSE_STAFF`, `ROLE_WAREHOUSE_MANAGER`, `ROLE_ADMIN` | Xóa nhà cung cấp (Chặn và trả lỗi nếu đã có phiếu kho, chỉ cho phép ngừng giao dịch `active=false`) |

`GET /suppliers` nhận `keyword`, `page` (bắt đầu từ 0) và `size` (1–200, mặc định 10).
Kết quả là `{"success":true,"data":{"content":[...],"totalElements":0,"totalPages":0,"last":true}}`.
Frontend đọc `data.content` và tải các trang trước khi tìm kiếm/lọc trạng thái.

`POST` và `PUT` nhận `code` (tối đa 30 ký tự), `name` (120), `taxCode` (30),
`contactPerson` (120), `contactPhone` (số di động Việt Nam), `email` (150),
`paymentTerms` (200), `address` (300), `notes` (1000) và `active` (boolean).
Các trường liên hệ, địa chỉ và ghi chú được lưu trong bảng `suppliers` và trả lại trong response.
Trạng thái dùng `active`, không dùng chuỗi `status`; người liên hệ dùng `contactPerson`.

**Mã nhà cung cấp (góp ý S2-09):**

- `code` không bắt buộc khi tạo. Bỏ qua trường, gửi `null`, chuỗi rỗng hoặc chỉ khoảng trắng thì backend sinh mã `NCC` + 9 chữ số ngẫu nhiên và kiểm tra trùng trước khi lưu.
- Mã nhập tay được trim, chuẩn hoá Unicode/hoa chữ và thêm tiền tố `NCC` nếu chưa có. Ví dụ `abc@1` → `NCCABC@1`; `ncc001` → `NCC001`. Chấp nhận chữ, số và ký tự; không nhận ký tự điều khiển.
- Giới hạn 30 ký tự tính cả tiền tố. Form có tiền tố `NCC` riêng và phần nhập tối đa 27 ký tự.
- Trùng không phân biệt hoa/thường trả `400 Bad Request`. Ràng buộc `UNIQUE` ở DB chặn cả yêu cầu đồng thời; lỗi trả thông báo mã bị trùng, không lộ SQL.
- Khi cập nhật, mã để trống giữ mã hiện có. Nếu gửi mã mới qua API thì cũng phải duy nhất, loại trừ ID đang sửa. Form giữ mã đã cấp khi sửa thông tin NCC.


### Danh sách API Đơn hàng (`/api/orders`)

| Method | Endpoint | Quyền | Mô tả |
|---|---|---|---|
| GET | `/orders` | `ORDER_VIEW` hoặc `ROLE_ADMIN`, `ROLE_SALES_MANAGER`, `ROLE_SALES`, `ROLE_WAREHOUSE_MANAGER`, `ROLE_WAREHOUSE_STAFF`, `ROLE_ACCOUNTANT`, `ROLE_VIEWER` | Xem danh sách đơn hàng kèm bộ lọc (trạng thái, đại lý, nhân viên, khu vực, khoảng thời gian) và tổng tiền tập kết quả đang lọc (S4-07). Quản lý kinh doanh xem toàn công ty; Sales chỉ thấy đơn của đại lý mình phụ trách. |
| GET | `/orders/summary` | `ORDER_VIEW` hoặc các vai trò có quyền xem đơn | Thống kê nhanh tổng tiền và số lượng đơn hàng theo bộ lọc đang chọn (S4-07). |
| GET | `/orders/{id}` | `ORDER_VIEW` hoặc các vai trò có quyền xem đơn | Xem chi tiết đơn hàng (kèm thông tin bảng giá, giá sàn, trạng thái phê duyệt và kiểm soát quyền đại lý cho Sales). |
| GET | `/orders/draft` | Vai trò tạo đơn | Lấy bản nháp tạo đơn của người dùng hiện tại |
| PUT | `/orders/draft` | Vai trò tạo đơn | Lưu đè bản nháp tạo đơn của người dùng hiện tại |
| DELETE | `/orders/draft` | Vai trò tạo đơn | Xoá bản nháp tạo đơn của người dùng hiện tại |
| POST | `/orders` | `ROLE_SALES`, `ROLE_SALES_MANAGER`, `ROLE_ADMIN`, `ROLE_WAREHOUSE_MANAGER`, `ROLE_WAREHOUSE_STAFF` | Tạo đơn hàng cho đại lý (tự động áp bảng giá hiệu lực theo nhóm khách hàng; chặn nếu SKU ngoài bảng giá; bán dưới giá sàn chuyển sang `PENDING_APPROVAL`) |
| POST | `/orders/line-item/pricing` | `ROLE_SALES`, `ROLE_SALES_MANAGER`, `ROLE_ADMIN`, `ROLE_WAREHOUSE_MANAGER`, `ROLE_WAREHOUSE_STAFF` | Tra cứu giá bảng giá, giá sàn, tính chiết khấu sản lượng và kiểm tra cảnh báo dưới giá sàn cho một dòng sản phẩm |
| POST | `/orders/calculate` | `ROLE_SALES`, `ROLE_SALES_MANAGER`, `ROLE_ADMIN`, `ROLE_WAREHOUSE_MANAGER`, `ROLE_WAREHOUSE_STAFF` | Tính toán lại chiết khấu sản lượng và tổng tiền toàn bộ đơn hàng khi thay đổi số lượng |
| PUT | `/orders/{id}/cancel` | `ROLE_SALES`, `ROLE_SALES_MANAGER`, `ROLE_ADMIN` | Hủy đơn hàng |
| PUT | `/orders/{id}/approve` | `ROLE_SALES_MANAGER`, `ROLE_ADMIN` | Phê duyệt đơn hàng bán dưới giá sàn (chuyển trạng thái sang `RECORDED`) |
| PUT | `/orders/{id}/reject?reason={ly_do}` | `ROLE_SALES_MANAGER`, `ROLE_ADMIN` | Từ chối đơn hàng bán dưới giá sàn (chuyển trạng thái sang `REJECTED`) |

### Xem danh sách đơn hàng kèm bộ lọc & Tổng tiền bán hàng toàn công ty (S4-07)

Dành cho vai trò **Quản lý kinh doanh (`ROLE_SALES_MANAGER`)** nhằm nắm bắt tình hình bán hàng toàn công ty trong một màn hình:
1. **Bộ lọc đa tiêu chí phía Server**:
   - `status`: Lọc theo trạng thái đơn hàng (`RECORDED`, `PENDING_APPROVAL`, `CANCELLED`, `DRAFT`, `REJECTED`).
   - `dealerId`: Lọc theo ID đại lý.
   - `salesUserId` (hoặc `assignedUserId`, `employeeId`, `userId`): Lọc theo ID nhân viên kinh doanh phụ trách đại lý.
   - `territoryId`: Lọc theo ID khu vực / địa bàn của đại lý.
   - `startDate`, `endDate` (hoặc `fromDate`, `toDate`): Lọc theo khoảng thời gian tạo đơn (`YYYY-MM-DD`).
   - `keyword` (hoặc `search`): Tìm kiếm nhanh theo mã đơn hàng, tên khách hàng, tên hoặc mã đại lý.
   - `approvalRequired`, `approvalReason`: Lọc các đơn cần phê duyệt hoặc theo lý do phê duyệt.
2. **Hiển thị tổng tiền của tập kết quả đang lọc**:
   - Trường `totalAmount` trong `ApiResponse` trả về chính xác tổng giá trị tiền hàng (`totalAmount`) của toàn bộ các đơn hàng thỏa mãn điều kiện lọc.
   - Trường `totalOrders` / `totalElements` trả về tổng số đơn hàng trong tập kết quả lọc.
   - Endpoint `GET /api/orders/summary` hỗ trợ lấy nhanh tổng tiền và số lượng mà không cần nạp toàn bộ danh sách.
3. **Phân quyền dòng dữ liệu (Row-Level Security / RLS)**:
   - **Quản lý kinh doanh (`ROLE_SALES_MANAGER`)** và **Quản trị viên (`ROLE_ADMIN`)**: Toàn quyền xem và lọc đơn hàng của tất cả đại lý trên toàn công ty.
   - **Nhân viên kinh doanh (`ROLE_SALES`)**: Hệ thống tự động giới hạn chỉ hiển thị các đơn hàng thuộc đại lý mà nhân viên đó đang được phân công phụ trách (`dealer.assignedUser = currentUser`). Đơn hàng của đại lý khác hoặc đại lý chưa phân công sẽ tự động bị loại bỏ khỏi kết quả.
   - Khi nhân viên kinh doanh truy cập chi tiết đơn hàng (`GET /api/orders/{id}`) của đại lý do người khác phụ trách: Hệ thống ném ngoại lệ `AccessDeniedException` và trả về `403 Forbidden`.
4. **Làm giàu dữ liệu phản hồi (`OrderResponse`)**:
   Mỗi đơn hàng trong danh sách được bổ sung đầy đủ thông tin:
   - `dealerId`, `dealerCode`, `dealerName`
   - `salesUserId`, `salesUserName`, `salesUserFullName`
   - `territoryId`, `territoryCode`, `territoryName`

### Tự động áp dụng Bảng giá, Chiết khấu sản lượng & Kiểm soát Giá sàn khi Đặt hàng (S4-01)

Nhằm đảm bảo tính chính xác và minh bạch trong chính sách giá khi tạo đơn:
1. **Giá tự động lấy từ bảng giá đang hiệu lực của nhóm khách hàng mà đại lý thuộc về**:
   - Khi chọn đại lý (hoặc nhóm khách hàng), hệ thống tự động tìm bảng giá đang có hiệu lực (`startDate <= orderDate <= endDate`).
   - Đơn giá bán (`sellingPrice`) và giá sàn (`floorPrice`) được tự động nhân theo hệ số quy đổi đơn vị tính (thùng, lốc, lon...).
2. **Chiết khấu theo sản lượng tự động tính lại mỗi khi số lượng thay đổi**:
   - Khi số lượng thay đổi (hoặc khi thêm mặt hàng mới), hệ thống tự động đánh giá các chính sách chiết khấu theo sản lượng (`VolumeDiscountPolicy`) đang hiệu lực đối với nhóm khách hàng đó.
   - Luôn tự động chọn chính sách tối ưu nhất (chiết khấu cao nhất) cho khách hàng.
   - Endpoint `POST /api/orders/line-item/pricing` hỗ trợ preview ngay trên màn hình khi nhập số lượng cho từng mặt hàng.
   - Endpoint `POST /api/orders/calculate` tính lại chiết khấu và tổng thanh toán toàn đơn hàng.
3. **Sửa giá thủ công nhưng dưới giá sàn thì đơn bị đánh dấu cần duyệt**:
   - Nhân viên kinh doanh được phép sửa đơn giá bán thủ công (`manualPrice` / `price`).
   - Nếu giá bán thủ công < giá sàn (`floorPrice` đã quy đổi theo đơn vị tính):
     - Dòng sản phẩm được đánh dấu `belowFloorPrice = true`.
     - Đơn hàng được gắn cờ `requiresApproval = true`, trạng thái chuyển thành **`PENDING_APPROVAL` (Chờ duyệt)** và ghi nhận `approvalReason` chi tiết (Ví dụ: `Sản phẩm 'Bia Heineken Sleek lon 330ml' bán giá 14000 < giá sàn 15500 (lon)`).
     - Chỉ Quản lý kinh doanh (`ROLE_SALES_MANAGER`) hoặc Admin mới có quyền phê duyệt (`PUT /api/orders/{id}/approve`) hoặc từ chối (`PUT /api/orders/{id}/reject`).
4. **Chặn thêm dòng hàng nếu SKU không có trong bảng giá hiệu lực**:
   - Nếu nhóm khách hàng chưa có bảng giá hiệu lực: Hệ thống chặn và trả về lỗi `400 Bad Request` kèm thông báo rõ lý do:
     `"Không tìm thấy bảng giá hiệu lực cho nhóm khách hàng '{nhóm}'. Không thể thêm dòng hàng cho sản phẩm '{tên}'."`
   - Nếu sản phẩm (SKU) không có trong bảng giá hiệu lực của nhóm khách hàng đó: Hệ thống chặn và trả về lỗi `400 Bad Request`:
     `"Sản phẩm '{tên}' (mã: {mã}) chưa có trong bảng giá hiệu lực của nhóm khách hàng '{nhóm}'. Không thể thêm dòng hàng."`

#### Request / Response `POST /api/orders/line-item/pricing`

**Request:**
```json
POST /api/orders/line-item/pricing
{
  "dealerId": 1,
  "productId": 10,
  "unit": "thùng",
  "quantity": 5,
  "manualPrice": 360000,
  "orderDate": "2026-10-13"
}
```

**Response thành công (`200 OK`):**
```json
{
  "success": true,
  "message": "Tính giá dòng hàng thành công",
  "data": {
    "productId": 10,
    "productCode": "SP-KEN330",
    "productName": "Bia Heineken Sleek lon 330ml",
    "dealerId": 1,
    "customerGroup": "DAI_LY_CAP_1",
    "customerGroupName": "Đại lý cấp 1",
    "priceListCode": "BG-DLC1-2026",
    "unit": "thùng",
    "conversionFactor": 24.0,
    "baseUnit": "lon",
    "quantity": 5.0,
    "baseQuantity": 120.0,
    "priceListSellingPrice": 396000.0,
    "floorPrice": 372000.0,
    "unitPrice": 360000.0,
    "isManualPrice": true,
    "belowFloorPrice": true,
    "requiresApproval": true,
    "approvalReason": "Sản phẩm 'Bia Heineken Sleek lon 330ml' bán giá 360000 < giá sàn 372000.0000 (thùng)",
    "subtotalAmount": 1800000.0,
    "discountAmount": 90000.0,
    "discountPercent": 5.0,
    "appliedPolicyCode": "CK-SL-01",
    "appliedPolicyName": "Chiết khấu sản lượng Đại lý Cấp 1",
    "discountReason": "Đạt mốc sản lượng >= 100 lon (5%)",
    "totalAmount": 1710000.0
  }
}
```

#### Request / Response `POST /api/orders/calculate`

**Request:**
```json
POST /api/orders/calculate
{
  "dealerId": 1,
  "items": [
    {
      "productId": 10,
      "unit": "lon",
      "quantity": 200
    }
  ]
}
```

**Response thành công (`200 OK`):**
```json
{
  "success": true,
  "message": "Tính toán đơn hàng thành công",
  "data": {
    "dealerId": 1,
    "customerGroup": "DAI_LY_CAP_1",
    "customerGroupName": "Đại lý cấp 1",
    "priceListCode": "BG-DLC1-2026",
    "subtotalAmount": 3300000.0,
    "discountAmount": 330000.0,
    "totalAmount": 2970000.0,
    "requiresApproval": false,
    "items": [
      {
        "productId": 10,
        "productCode": "SP-KEN330",
        "productName": "Bia Heineken Sleek lon 330ml",
        "unit": "lon",
        "quantity": 200.0,
        "price": 16500.0,
        "floorPrice": 15500.0,
        "belowFloorPrice": false,
        "subtotalAmount": 3300000.0,
        "discountAmount": 330000.0,
        "discountPercent": 10.0,
        "appliedPolicyCode": "CK-SL-02",
        "selectionReason": "Chiết khấu theo bậc số lượng >= 200 lon (10%)",
        "totalAmount": 2970000.0
      }
    ]
  }
}
```

### Bản nháp tạo đơn và ngày giao mong muốn (S3-09)

- `GET`, `PUT`, `DELETE /api/orders/draft` dành cho `ROLE_SALES`,
  `ROLE_SALES_MANAGER`, `ROLE_ADMIN`, `ROLE_WAREHOUSE_MANAGER`,
  `ROLE_WAREHOUSE_STAFF`. Chủ bản nháp lấy từ tài khoản đã xác thực; request
  không được chọn chủ bản nháp của người khác.
- `PUT` nhận một **đối tượng JSON** và lưu trong bảng `order_drafts`, mỗi tài
  khoản có một bản nháp. Frontend sử dụng `dealerId`, `deliveryPointId`,
  `deliveryDate` (`yyyy-MM-dd`), `note`, `items`, `_seq`. Mỗi dòng trong `items`
  giữ `_key`, `productId`, `sku`, `name`, `unitConversions`, `selectedUnitId`,
  `qty`, `basePrice`, `unitPrice`, `discountAmount` để tiếp tục soạn đơn.
- `GET` trả `{ "success": true, "message": "...", "data": { ... } }` khi có
  nháp. Khi chưa có nháp, thuộc tính `data` được bỏ khỏi response.
- `DELETE` xoá bản nháp của chính tài khoản hiện tại. Luồng frontend đợi các
  lần lưu nháp đang chạy kết thúc trước khi xoá, tránh ghi lại nháp sau khi
  người dùng đã tạo đơn.
- Khi mở nháp, frontend lấy lại sản phẩm và đơn vị quy đổi từ API, tải bảng
  giá hiệu lực theo đại lý và tính lại chiết khấu. Giá/chiết khấu trong nháp
  phục vụ khôi phục màn hình, không quyết định số tiền backend ghi nhận.
- Khi tạo đơn thật bằng `POST /api/orders`, ngày giao dùng trường
  **`expectedDeliveryDate`** (`yyyy-MM-dd`), không dùng `deliveryDate`.
  Response đơn hàng trả lại `expectedDeliveryDate` cùng `deliveryPointId`.

## Khai báo Bảng giá theo nhóm khách hàng & Thời gian hiệu lực (S2-10)

Hỗ trợ Quản lý kinh doanh khai báo bảng giá theo nhóm khách hàng và thời gian hiệu lực, để đại lý cấp 1 và cấp 2 tự động nhận đúng giá của mình.

### Quy tắc nghiệp vụ cốt lõi:
1. **Nhiều bảng giá song song**: Hệ thống hỗ trợ đồng thời nhiều bảng giá cho từng phân khúc khách hàng: Đại lý cấp 1 (`DAI_LY_CAP_1`), Đại lý cấp 2 (`DAI_LY_CAP_2`), Khách lẻ (`KHACH_LE`).
2. **Hiệu lực theo thời gian**: Mỗi bảng giá bắt buộc có ngày bắt đầu (`startDate`) và ngày kết thúc (`endDate`). Tại một thời điểm, hệ thống tự động tìm bảng giá có hiệu lực mới nhất theo nhóm khách hàng (`startDate <= ngày <= endDate`).
3. **Giá bán & Giá sàn**: Mỗi dòng giá khai báo giá bán (`sellingPrice`) và giá sàn (`floorPrice` <= `sellingPrice`).
   - Nếu nhân viên bán dưới giá sàn (hoặc quy đổi theo đơn vị tính thùng/lốc mà đơn giá < giá sàn quy đổi): Đơn hàng **không được ghi nhận ngay** mà chuyển sang trạng thái **`PENDING_APPROVAL` (Chờ duyệt)** và gán cờ `requiresApproval = true`.
   - **Chỉ Quản lý kinh doanh (`ROLE_SALES_MANAGER`)** hoặc Admin mới có quyền phê duyệt (`/api/orders/{id}/approve`) hoặc từ chối (`/api/orders/{id}/reject`).
4. **Bảo vệ bảng giá đã phát sinh đơn**:
   - Khi bảng giá đã được dùng để tạo bất kỳ đơn hàng nào (`hasOrders = true` hoặc đã có liên kết đơn hàng trong cơ sở dữ liệu), hệ thống **nghiêm cấm sửa hoặc xóa** bảng giá này (trả lỗi `400 Bad Request` kèm ngoại lệ `PriceListLockedException`).
   - Người dùng bắt buộc phải dùng tính năng **Tạo phiên bản mới** (`POST /api/price-lists/{id}/new-version`). Phiên bản mới sẽ tự động tăng số version (v2, v3...), sao chép danh sách sản phẩm và cho phép điều chỉnh giá bán/giá sàn cũng như thời gian hiệu lực mới.
5. **Đại lý tự động nhận đúng giá**: Khi tạo đơn hàng có gắn `dealerId` hoặc `customerGroup`, nếu không nhập giá bán, hệ thống tự động tra cứu bảng giá hiệu lực tương ứng và áp dụng đúng đơn giá (tự động nhân hệ số quy đổi nếu đặt theo thùng/lốc).

### Danh sách API Bảng giá (`/api/price-lists`)

| Method | Endpoint | Quyền | Mô tả |
|---|---|---|---|
| GET | `/price-lists?customerGroup={cg}&active={true/false}&keyword={q}` | Đã đăng nhập | Xem danh sách các bảng giá (hỗ trợ lọc theo nhóm khách hàng, trạng thái, tìm kiếm mã/tên) |
| GET | `/price-lists/{id}` | Đã đăng nhập | Xem chi tiết bảng giá kèm toàn bộ danh sách dòng giá (sản phẩm, giá bán, giá sàn) |
| GET | `/price-lists/customer-groups` | Đã đăng nhập | Danh sách các nhóm khách hàng (Đại lý cấp 1, Đại lý cấp 2, Khách lẻ) |
| GET | `/price-lists/effective?customerGroup={cg}&date={yyyy-MM-dd}` | Đã đăng nhập | Tìm bảng giá đang có hiệu lực cho nhóm khách hàng tại thời điểm chỉ định |
| POST | `/price-lists` | `ROLE_SALES_MANAGER`, `ROLE_ADMIN` | Tạo bảng giá mới (bắt buộc mã, tên, nhóm khách hàng, ngày bắt đầu/kết thúc, danh sách dòng giá) |
| PUT | `/price-lists/{id}` | `ROLE_SALES_MANAGER`, `ROLE_ADMIN` | Sửa thông tin bảng giá (**Chặn nếu bảng giá đã phát sinh đơn hàng** -> 400 Bad Request) |
| DELETE | `/price-lists/{id}` | `ROLE_SALES_MANAGER`, `ROLE_ADMIN` | Xóa bảng giá (**Chặn nếu bảng giá đã phát sinh đơn hàng** -> 400 Bad Request) |
| POST | `/price-lists/{id}/new-version` | `ROLE_SALES_MANAGER`, `ROLE_ADMIN` | Tạo phiên bản mới kế thừa từ bảng giá cũ (tăng version, sao chép sản phẩm, reset trạng thái phát sinh đơn) |

#### Ví dụ Request tạo bảng giá (`POST /api/price-lists`):
```json
{
  "code": "BG-DLC1-2026",
  "name": "Bảng giá Đại lý cấp 1 năm 2026",
  "customerGroup": "DAI_LY_CAP_1",
  "startDate": "2026-01-01",
  "endDate": "2026-12-31",
  "active": true,
  "description": "Bảng giá ưu đãi dành cho hệ thống Đại lý cấp 1",
  "items": [
    {
      "productId": 10,
      "sellingPrice": 16500,
      "floorPrice": 15500,
      "note": "Giá áp dụng theo lon"
    }
  ]
}
```

#### Ví dụ Request tạo phiên bản mới (`POST /api/price-lists/{id}/new-version`):
```json
{
  "newCode": "BG-DLC1-2026-V2",
  "newName": "Bảng giá Đại lý cấp 1 năm 2026 (v2)",
  "startDate": "2026-06-01",
  "endDate": "2026-12-31",
  "description": "Điều chỉnh giá bán do biến động chi phí nguyên vật liệu"
}
```
*(Nếu `items` để trống, hệ thống tự động kế thừa toàn bộ danh sách dòng giá của phiên bản tiền nhiệm).*

## Khai báo Chính sách chiết khấu theo sản lượng (S3-01)

Hỗ trợ Quản lý kinh doanh khai báo chính sách chiết khấu theo sản lượng, để khuyến khích đại lý lấy nhiều hàng mà không phải thoả thuận miệng từng lần.

### Quy tắc nghiệp vụ cốt lõi:

1. **Bậc chiết khấu theo số lượng mua của một SKU hoặc một nhóm hàng**:
   - Chính sách có thể áp dụng cho một SKU cụ thể (`targetType = PRODUCT`) hoặc áp dụng cho một nhóm hàng (`targetType = CATEGORY`).
   - Khi áp dụng cho nhóm hàng, toàn bộ các nhóm con trong cây phân cấp và các sản phẩm thuộc nhánh nhóm hàng đó đều được hưởng chính sách.
   - Sản lượng mua được tự động quy về **đơn vị cơ sở** (base quantity, ví dụ số lon/chai/cái) theo hệ số quy đổi đóng băng tại thời điểm tạo đơn (S2-07), đảm bảo tính công bằng và chính xác dù đại lý đặt theo lon, lốc hay thùng.
   - Khi đặt nhiều sản phẩm cùng thuộc một nhóm hàng trong một đơn hàng, hệ thống **tự động cộng dồn tổng sản lượng** của tất cả sản phẩm trong nhóm để xác định bậc chiết khấu đạt được.

2. **Cách tính chiết khấu**:
   - **Theo phần trăm (`PERCENTAGE`)**: Tính theo tỷ lệ % trên thành tiền của dòng sản phẩm (`discountAmount = subtotalAmount * discountPercent / 100`).
   - **Theo số tiền trên đơn vị (`FIXED_AMOUNT`)**: Tính theo số tiền cố định trên một đơn vị cơ sở (`discountAmount = discountValue * baseQuantity`).

3. **QUY TẮC CỐT LÕI: Nhiều chính sách cùng áp dụng thì lấy chính sách có lợi nhất cho khách**:
   - Khi một mặt hàng trong đơn hàng đủ điều kiện áp dụng nhiều chính sách chiết khấu cùng một lúc (ví dụ: vừa có chính sách riêng theo SKU, vừa thuộc nhóm hàng có chính sách chiết khấu theo nhóm; hoặc có đồng thời chính sách theo % và chính sách theo số tiền/đơn vị):
   - **Hệ thống tự động so sánh số tiền chiết khấu thực tế (VND)** mà khách hàng sẽ nhận được theo từng chính sách hợp lệ.
   - **Quy tắc giải quyết**: Luôn tự động chọn chính sách mang lại số tiền chiết khấu **LỚN NHẤT** cho khách hàng (số tiền khách hàng phải thanh toán là THẤP NHẤT).
   - Nếu có hai chính sách cùng mang lại mức giảm bằng nhau, hệ thống ưu tiên chính sách trực tiếp theo SKU trước chính sách theo nhóm hàng.
   - Thông tin chính sách được chọn (`appliedPolicyId`, `appliedPolicyCode`, `appliedPolicyName`, `discountAmount`, `discountPercent`) được lưu vết đóng băng vào dòng đơn hàng (`OrderItem`) và hiển thị minh bạch cho khách hàng và kế toán.
   - API tính thử (`POST /api/volume-discounts/calculate`) trả về đầy đủ danh sách các chính sách ứng viên (`candidatePolicies`) kèm số tiền chiết khấu tính toán của từng chính sách và lý do lựa chọn (`selectionReason`), giúp nhân viên kinh doanh và đại lý dễ dàng đối chiếu.

4. **Bảo mật và phân quyền**:
   - Chỉ **Quản lý kinh doanh (`ROLE_SALES_MANAGER`)** và **Quản trị hệ thống (`ROLE_ADMIN`)** có quyền Khai báo, Sửa, Đổi trạng thái (`active`) và Xóa chính sách chiết khấu.
   - Các vai trò khác (`ROLE_SALES`, `ROLE_ACCOUNTANT`, `ROLE_VIEWER`) có quyền xem danh sách, xem chi tiết và tính toán thử chiết khấu (`/api/volume-discounts/calculate`).
   - Mọi thao tác quản lý chính sách chiết khấu đều được ghi nhận vào nhật ký hệ thống (`AuditLog`).

### Danh sách API Chính sách chiết khấu theo sản lượng (`/api/volume-discounts`)

| Method | Endpoint | Quyền | Mô tả |
|---|---|---|---|
| GET | `/volume-discounts` | Đã đăng nhập | Xem danh sách chính sách chiết khấu (lọc theo targetType, productId, categoryId, customerGroup, active, keyword) |
| GET | `/volume-discounts/{id}` | Đã đăng nhập | Xem chi tiết chính sách chiết khấu kèm các bậc sản lượng |
| POST | `/volume-discounts` | `ROLE_SALES_MANAGER`, `ROLE_ADMIN` | Khai báo chính sách chiết khấu mới |
| PUT | `/volume-discounts/{id}` | `ROLE_SALES_MANAGER`, `ROLE_ADMIN` | Cập nhật thông tin và các bậc chiết khấu |
| PATCH | `/volume-discounts/{id}/status?active=true/false` | `ROLE_SALES_MANAGER`, `ROLE_ADMIN` | Bật/tắt trạng thái hoạt động của chính sách |
| DELETE | `/volume-discounts/{id}` | `ROLE_SALES_MANAGER`, `ROLE_ADMIN` | Xóa chính sách chiết khấu |
| POST | `/volume-discounts/calculate` | Đã đăng nhập | Tính toán thử chiết khấu cho danh sách mặt hàng dự kiến đặt |

#### Ví dụ Request khai báo chính sách chiết khấu (`POST /api/volume-discounts`):
```json
{
  "code": "CK-KEN-2026",
  "name": "Chiết khấu sản lượng bia Heineken Sleek lon 330ml",
  "description": "Khuyến khích đại lý cấp 1 và cấp 2 lấy số lượng lớn theo thùng",
  "targetType": "PRODUCT",
  "productId": 10,
  "customerGroup": null,
  "startDate": "2026-01-01",
  "endDate": "2026-12-31",
  "active": true,
  "tiers": [
    {
      "minQuantity": 48,
      "maxQuantity": 119,
      "discountType": "PERCENTAGE",
      "discountValue": 3.0
    },
    {
      "minQuantity": 120,
      "maxQuantity": 239,
      "discountType": "PERCENTAGE",
      "discountValue": 5.0
    },
    {
      "minQuantity": 240,
      "maxQuantity": null,
      "discountType": "PERCENTAGE",
      "discountValue": 8.0
    }
  ]
}
```

#### Ví dụ Request tính toán thử chiết khấu (`POST /api/volume-discounts/calculate`):
```json
{
  "customerGroup": "DAI_LY_CAP_1",
  "items": [
    {
      "productId": 10,
      "unit": "thùng",
      "quantity": 5,
      "price": 396000
    }
  ]
}
```

### Danh sách API Quản lý hồ sơ đại lý (S3-03) và Tìm kiếm nhanh đại lý (S3-08)

Hỗ trợ Kế toán công nợ và Quản lý kinh doanh quản lý hồ sơ đại lý chuẩn (S3-03), đồng thời hỗ trợ Nhân viên kinh doanh tìm kiếm nhanh khách trong tuyến trên thiết bị di động khi đứng ngoài đường (S3-08).

Quy tắc nghiệp vụ:
1. Khai báo mã đại lý, tên, mã số thuế, nhóm khách hàng, khu vực, người phụ trách, trạng thái giao dịch.
2. Mã đại lý là duy nhất trong toàn hệ thống.
3. Nhóm khách hàng quyết định bảng giá được áp dụng (`CustomerGroup`: `DAI_LY_CAP_1`, `DAI_LY_CAP_2`, `KHACH_LE`).
4. Đại lý đã phát sinh giao dịch (đơn hàng) thì không được xóa, chỉ được chuyển sang trạng thái ngừng giao dịch (`active = false`).

| Method | Endpoint | Quyền | Mô tả |
|---|---|---|---|
| GET | `/dealers` | `ROLE_ACCOUNTANT`, `ROLE_SALES_MANAGER`, `ROLE_ADMIN`, `ROLE_SALES`, `ROLE_VIEWER` | Danh sách đại lý (tìm kiếm, lọc theo nhóm, khu vực, người phụ trách, trạng thái, phân trang) |
| GET | `/dealers/active` | `ROLE_ACCOUNTANT`, `ROLE_SALES_MANAGER`, `ROLE_ADMIN`, `ROLE_SALES`, `ROLE_VIEWER` | Danh sách đại lý đang hoạt động (phục vụ dropdown chọn đại lý khi tạo đơn) |
| GET | `/dealers/options` | `ROLE_ACCOUNTANT`, `ROLE_SALES_MANAGER`, `ROLE_ADMIN`, `ROLE_SALES`, `ROLE_VIEWER` | Danh mục tham chiếu tạo/sửa đại lý (nhóm khách hàng, khu vực, người phụ trách) |
| GET | `/dealers/{id}` | `ROLE_ACCOUNTANT`, `ROLE_SALES_MANAGER`, `ROLE_ADMIN`, `ROLE_SALES`, `ROLE_VIEWER` | Chi tiết hồ sơ đại lý |
| POST | `/dealers` | `ROLE_ACCOUNTANT`, `ROLE_SALES_MANAGER`, `ROLE_ADMIN` | Khai báo hồ sơ đại lý mới (kiểm tra tính duy nhất của mã đại lý) |
| PUT | `/dealers/{id}` | `ROLE_ACCOUNTANT`, `ROLE_SALES_MANAGER`, `ROLE_ADMIN` | Cập nhật hồ sơ đại lý (kiểm tra trùng mã) |
| PATCH | `/dealers/{id}/status?active=false` | `ROLE_ACCOUNTANT`, `ROLE_SALES_MANAGER`, `ROLE_ADMIN` | Ngừng giao dịch; truyền `active=true` để khôi phục giao dịch |
| POST | `/dealers/{id}/lock` | `ROLE_ACCOUNTANT`, `ROLE_SALES_MANAGER`, `ROLE_ADMIN` | **Khoá giao dịch đại lý (S3-07)**: Bắt buộc nhập lý do khoá; dừng bán ngay; cảnh báo nếu có đơn dở dang |
| POST | `/dealers/{id}/unlock` | `ROLE_ACCOUNTANT`, `ROLE_SALES_MANAGER`, `ROLE_ADMIN` | **Mở khoá giao dịch đại lý (S3-07)**: Khôi phục khả năng giao dịch và đặt hàng |
| GET | `/dealers/{id}/in-progress-orders` | `ROLE_ACCOUNTANT`, `ROLE_SALES_MANAGER`, `ROLE_ADMIN`, `ROLE_SALES`, `ROLE_VIEWER` | **Danh sách đơn hàng đang dở dang (S3-07)**: Đơn hàng cần theo dõi khi khoá đại lý |
| DELETE | `/dealers/{id}` | `ROLE_ACCOUNTANT`, `ROLE_SALES_MANAGER`, `ROLE_ADMIN` | Xóa đại lý (**Chặn và trả lỗi 400 nếu đại lý đã phát sinh giao dịch/đơn hàng**, chỉ cho phép ngừng giao dịch) |

`GET /dealers` nhận các tham số query:
- `keyword`: Tìm kiếm tương đối theo mã, tên, MST, SĐT
- `customerGroup`: `DAI_LY_CAP_1` | `DAI_LY_CAP_2` | `KHACH_LE`
- `territoryId`: Lọc theo khu vực
- `assignedUserId`: Lọc theo nhân viên kinh doanh phụ trách
- `active`: `true` | `false`
- `locked`: `true` | `false` (Lọc theo trạng thái khoá giao dịch - S3-07)
- `page`: Trang (bắt đầu từ 0)
- `size`: Số bản ghi mỗi trang (1-200, mặc định 10)

### Khoá / Mở giao dịch với đại lý (S3-07)

Hỗ trợ Kế toán công nợ dừng bán ngay cho các đại lý có dấu hiệu mất khả năng thanh toán.

Quy tắc nghiệp vụ:
1. **Đại lý bị khoá không tạo được đơn mới**:
   - Mọi nỗ lực tạo đơn mới (`POST /api/orders`) chỉ định đại lý bị khoá đều bị từ chối với mã lỗi `400 Bad Request` kèm thông báo chi tiết và lý do khoá.
   - Endpoint `GET /api/dealers/active` tự động loại trừ đại lý bị khoá để ngăn chặn việc chọn đại lý bị khoá trên giao diện/cổng đặt hàng.
2. **Bắt buộc nhập lý do khoá**:
   - Request `POST /api/dealers/{id}/lock` bắt buộc có trường `reason` (không được để trống, tối đa 500 ký tự). Nếu để trống sẽ trả về `400 Bad Request`.
3. **Đơn đang dở của đại lý bị khoá vẫn xử lý tiếp được nhưng có cảnh báo**:
   - Khi khoá đại lý, nếu đại lý còn đơn hàng dở dang (`PENDING_APPROVAL`, `RECORDED`), API trả về thông báo thành công kèm cảnh báo số lượng đơn dở dang cần theo dõi.
   - Các đơn hàng này vẫn được phép tiếp tục phê duyệt (`PUT /api/orders/{id}/approve`), huỷ (`PUT /api/orders/{id}/cancel`), hoặc từ chối (`PUT /api/orders/{id}/reject`).
   - Khi truy vấn chi tiết đơn hàng hoặc thực hiện xử lý đơn hàng, hệ thống trả về thông tin `dealerLocked: true` kèm `dealerLockWarning` và ghi rõ cảnh báo trong nhật ký kiểm toán (Audit Log).

#### Ví dụ Request khoá đại lý (`POST /api/dealers/{id}/lock`):
```json
{
  "reason": "Nợ quá hạn 90 ngày, có dấu hiệu mất khả năng thanh toán"
}
```

#### Ví dụ Response khoá đại lý thành công có cảnh báo đơn dở:
```json
{
  "success": true,
  "message": "Đã khoá giao dịch với đại lý 'Đại lý Cấp 1 Minh Phát'. Cảnh báo: Đại lý hiện có 2 đơn hàng đang dở dang cần theo dõi xử lý.",
  "data": {
    "id": 1,
    "code": "DL-MINHPHAT",
    "name": "Đại lý Cấp 1 Minh Phát",
    "locked": true,
    "lockReason": "Nợ quá hạn 90 ngày, có dấu hiệu mất khả năng thanh toán",
    "lockedAt": "2026-10-08T15:30:00",
    "lockedBy": "accountant01",
    "inProgressOrderCount": 2,
    "active": true
  }
}
```

#### Ví dụ Request mở khoá đại lý (`POST /api/dealers/{id}/unlock`):
```json
POST /api/dealers/1/unlock
```
Response:
```json
{
  "success": true,
  "message": "Đã mở khoá giao dịch cho đại lý 'Đại lý Cấp 1 Minh Phát'",
  "data": {
    "id": 1,
    "code": "DL-MINHPHAT",
    "name": "Đại lý Cấp 1 Minh Phát",
    "locked": false,
    "lockReason": null,
    "lockedAt": null,
    "lockedBy": null,
    "inProgressOrderCount": 2,
    "active": true
  }
}
```

#### Ví dụ Request tạo đại lý (`POST /api/dealers`):
```json
{
  "code": "DL-MINHPHAT",
  "name": "Đại lý Cấp 1 Minh Phát",
  "taxCode": "0101234567",
  "customerGroup": "DAI_LY_CAP_1",
  "territoryId": 1,
  "assignedUserId": 2,
  "phone": "0981234567",
  "email": "minhphat@dealer.vn",
  "address": "123 Lê Lợi, Quận 1, TP.HCM",
  "debtLimit": 100000000,
  "maxDebtDays": 30,
  "active": true
}
```

#### Ví dụ Response (`GET /api/dealers/{id}`):
```json
{
  "success": true,
  "message": "Lấy chi tiết đại lý thành công",
  "data": {
    "id": 1,
    "code": "DL-MINHPHAT",
    "name": "Đại lý Cấp 1 Minh Phát",
    "taxCode": "0101234567",
    "customerGroup": "DAI_LY_CAP_1",
    "customerGroupDisplayName": "Đại lý cấp 1",
    "territoryId": 1,
    "territoryCode": "HCM-DONG",
    "territoryName": "TP.HCM - Khu Đông",
    "assignedUserId": 2,
    "assignedUsername": "sales.demo",
    "assignedUserFullName": "Nhân viên kinh doanh Demo",
    "active": true,
    "handoverRequired": false,
    "debtLimit": 100000000.00,
    "maxDebtDays": 30,
    "phone": "0981234567",
    "email": "minhphat@dealer.vn",
    "address": "123 Lê Lợi, Quận 1, TP.HCM",
    "hasTransactions": true,
    "createdAt": "2026-10-07T09:00:00",
    "updatedAt": null
  }
}
```

### Luồng giao diện S3-07

Trang `/dealers` dùng dữ liệu server và các API khoá/mở khoá nêu trên. Lý do khoá
là bắt buộc, tối đa 500 ký tự. Nút **Đơn đang dở** đọc
`GET /api/dealers/{id}/in-progress-orders` và hiển thị cảnh báo công nợ.
Quản lý kinh doanh hoặc Admin có thể duyệt/từ chối đơn `PENDING_APPROVAL` bằng
`PUT /api/orders/{id}/approve` hoặc `PUT /api/orders/{id}/reject`.
Trang `/create-order` là màn hình tạo đơn nội bộ; chưa có cổng đại lý riêng.
Giao diện không lưu hồ sơ đại lý hay trạng thái đơn vào `localStorage`.

### Danh sách API Phân công & Chuyển giao địa bàn đại lý (S3-06)

Hỗ trợ Quản lý kinh doanh (`ROLE_SALES_MANAGER`) và Quản trị viên (`ROLE_ADMIN`) phân công nhân viên kinh doanh theo đại lý và khu vực, đảm bảo mỗi đại lý đều có một người chịu trách nhiệm chăm sóc và xử lý bàn giao hàng loạt khi nhân viên nghỉ việc.

Quy tắc nghiệp vụ:
1. Mỗi đại lý được gán một nhân viên kinh doanh phụ trách chính (`assignedUser`).
2. **Bảo mật dữ liệu (Row-Level Security)**: Nhân viên kinh doanh (`ROLE_SALES`) chỉ nhìn thấy các đại lý do mình phụ trách trong danh sách và chi tiết. Cố gắng xem đại lý của người khác sẽ bị từ chối truy cập (403 Forbidden).
3. **Chuyển giao địa bàn hàng loạt**: Khi nhân viên nghỉ việc hoặc điều chuyển, Quản lý kinh doanh/Admin có thể chuyển giao hàng loạt toàn bộ hoặc một phần đại lý từ nhân viên cũ sang nhân viên tiếp nhận mới (theo danh sách hoặc theo địa bàn).
4. **Lưu lịch sử đầy đủ**: Mỗi lần phân công hoặc chuyển giao đều được lưu vết chi tiết trong cơ sở dữ liệu (`dealer_assignment_histories`) bao gồm người giao, người nhận, đại lý, khu vực, lý do, người thực hiện và thời gian chuyển giao.

| Method | Endpoint | Quyền | Mô tả |
|---|---|---|---|
| POST | `/dealers/{id}/assign` | `ROLE_SALES_MANAGER`, `ROLE_ADMIN` | Gán hoặc thay đổi nhân viên phụ trách chính cho một đại lý (ghi lịch sử phân công) |
| POST | `/dealers/handover/bulk` | `ROLE_SALES_MANAGER`, `ROLE_ADMIN` | Chuyển giao địa bàn và đại lý hàng loạt từ nhân viên cũ sang nhân viên mới (khi nghỉ việc/điều chuyển) |
| GET | `/dealers/handover/pending` | `ROLE_SALES_MANAGER`, `ROLE_ADMIN` | Danh sách các đại lý đang cần bàn giao (có cờ `handoverRequired` do nhân viên bị khoá/nghỉ) |
| GET | `/dealers/handover/history` | `ROLE_SALES_MANAGER`, `ROLE_ADMIN`, `ROLE_ACCOUNTANT`, `ROLE_VIEWER` | Tra cứu lịch sử chuyển giao địa bàn và đại lý (phân trang, lọc theo người giao, người nhận, đại lý, khu vực, khoảng thời gian) |
| GET | `/dealers/{id}/assignment-history` | `ROLE_SALES_MANAGER`, `ROLE_ADMIN`, `ROLE_ACCOUNTANT`, `ROLE_SALES`, `ROLE_VIEWER` | Lịch sử phân công của riêng một đại lý cụ thể |

#### Ví dụ Request Phân công một đại lý (`POST /api/dealers/1/assign`):
```json
{
  "assignedUserId": 2,
  "reason": "Phân công phụ trách chính khu vực Quận 1"
}
```

#### Ví dụ Request Chuyển giao hàng loạt (`POST /api/dealers/handover/bulk`):
```json
{
  "fromUserId": 5,
  "toUserId": 2,
  "territoryId": 1,
  "dealerIds": [1, 2, 3],
  "reason": "Nhân viên Nguyễn Văn A nghỉ việc, bàn giao toàn bộ địa bàn cho Trần Văn B",
  "notes": "Đã bàn giao hồ sơ công nợ và đơn hàng đang xử lý"
}
```
*(Nếu `dealerIds` và `territoryId` để trống, hệ thống tự động chuyển giao TOÀN BỘ đại lý do `fromUserId` phụ trách sang `toUserId`).*

#### Ví dụ Response Chuyển giao hàng loạt:
```json
{
  "success": true,
  "message": "Chuyển giao địa bàn hàng loạt thành công",
  "data": {
    "totalTransferred": 3,
    "fromUserId": 5,
    "fromUsername": "sales.old",
    "fromUserFullName": "Nguyễn Văn A",
    "toUserId": 2,
    "toUsername": "sales.new",
    "toUserFullName": "Trần Văn B",
    "territoryId": 1,
    "territoryName": "TP.HCM - Khu Đông",
    "transferredDealerIds": [1, 2, 3],
    "transferredDealerNames": ["Đại lý Cấp 1 Minh Phát", "Đại lý Cấp 2 Tân Bình", "Đại lý An Phú"],
    "reason": "Nhân viên Nguyễn Văn A nghỉ việc, bàn giao toàn bộ địa bàn cho Trần Văn B",
    "transferredAt": "2026-10-08T14:30:00",
    "performedByUsername": "sales.manager"
  }
}
```

Khi phối hợp S3-06 với S3-07, `GET /api/dealers/active` chỉ trả đại lý đang giao dịch
và chưa bị khoá. Với người dùng chỉ có vai trò `SALES`, danh sách này và
`GET /api/dealers/{id}/in-progress-orders` đều giới hạn theo nhân viên phụ trách;
xem đơn đang dở của đại lý do người khác phụ trách trả HTTP 403.

### Kiểm soát hạn mức công nợ và chặn nợ quá hạn (S4-02 - SCRUM-82)

Hỗ trợ Nhân viên kinh doanh (`ROLE_SALES`) được hiển thị công nợ, hạn mức và chặn ngay khi đơn vượt hạn mức công nợ hoặc đại lý có khoản nợ quá hạn, giúp ngăn chặn rủi ro tài chính và tránh tình trạng chốt đơn rồi mới bị Kế toán bắt huỷ.

Quy tắc nghiệp vụ:
1. **Hiển thị thông tin công nợ**:
   - Khi chọn đại lý trên màn hình tạo đơn hoặc xem chi tiết đại lý, hệ thống hiển thị:
     + Công nợ hiện tại (`currentDebt`): Tổng số tiền các đơn hàng đã ghi sổ (`RECORDED`).
     + Hạn mức công nợ (`debtLimit`): Hạn mức được cấp theo hồ sơ đại lý.
     + Hạn mức còn lại (`remainingLimit`): `debtLimit - currentDebt`; có thể âm khi đã vượt hạn mức.
     + Số ngày được phép nợ (`maxDebtDays`).
2. **Cảnh báo vượt hạn mức (Soft Block - Chờ duyệt)**:
   - Khi tổng tiền đơn hàng mới cộng công nợ hiện tại vượt quá hạn mức (`currentDebt + newOrderTotal > debtLimit`, với `debtLimit > 0`):
     + Đơn hàng vẫn được tạo và lưu vào hệ thống nhưng được chuyển sang trạng thái chờ duyệt: `status = "PENDING_APPROVAL"`.
     + Đánh dấu cờ `requiresApproval = true` và ghi rõ lý do trong `approvalReason` (ví dụ: *"Vượt hạn mức công nợ: Công nợ hiện tại (15,000,000 đ) + Đơn mới (40,000,000 đ) = 55,000,000 đ vượt hạn mức (50,000,000 đ)"*).
     + Quản lý kinh doanh (`ROLE_SALES_MANAGER`) hoặc Quản trị viên (`ROLE_ADMIN`) có thẩm quyền phê duyệt (`PUT /api/orders/{id}/approve`) hoặc từ chối (`PUT /api/orders/{id}/reject`).
3. **Chặn tạo đơn hoàn toàn khi có nợ quá hạn (Hard Block)**:
   - Nếu đại lý có bất kỳ khoản nợ nào (đơn hàng `RECORDED`) đã quá hạn quá số ngày cho phép (`maxDebtDays` > 0 và `daysBetween(recordedAt, now) > maxDebtDays`):
     + Hệ thống **chặn tạo đơn hoàn toàn**, KHÔNG lưu đơn vào cơ sở dữ liệu.
     + Trả về mã lỗi **HTTP 400 Bad Request** với chi tiết thông tin: mã đại lý, tên đại lý, số ngày quá hạn (`overdueDays`), giới hạn cho phép (`maxDebtDays`), và tổng số tiền nợ bị quá hạn (`overdueAmount`).
     + Ngoại lệ: `DealerDebtOverdueException`.
4. **Bảo mật phân quyền cấp dòng (Row-Level Security)**:
   - Nhân viên kinh doanh (`ROLE_SALES`) chỉ được tra cứu thông tin công nợ của các đại lý do chính mình phụ trách (`assignedUser`). Truy cập công nợ của đại lý khác bị trả về **HTTP 403 Forbidden**.
   - Các vai trò Quản lý/Kế toán/Admin (`ROLE_ACCOUNTANT`, `ROLE_SALES_MANAGER`, `ROLE_ADMIN`, `ROLE_VIEWER`) có quyền tra cứu thông tin công nợ của mọi đại lý.

| Method | Endpoint | Quyền | Mô tả |
|---|---|---|---|
| GET | `/dealers/{id}/debt` | `ROLE_SALES`, `ROLE_SALES_MANAGER`, `ROLE_ADMIN`, `ROLE_ACCOUNTANT`, `ROLE_WAREHOUSE_MANAGER`, `ROLE_WAREHOUSE_STAFF`, `ROLE_VIEWER` | Lấy chi tiết công nợ, hạn mức, số ngày quá hạn và trạng thái cho phép đặt hàng của đại lý (áp dụng Row-Level Security cho `ROLE_SALES`). Alias: `GET /dealers/{id}/credit-info` |
| POST | `/orders/check-debt` | `ROLE_SALES`, `ROLE_SALES_MANAGER`, `ROLE_ADMIN`, `ROLE_WAREHOUSE_MANAGER`, `ROLE_WAREHOUSE_STAFF` | Kiểm tra sơ bộ công nợ và dự phóng trước khi đặt đơn (pre-check trên giao diện tạo đơn) |
| GET | `/orders/check-debt` | `ROLE_SALES`, `ROLE_SALES_MANAGER`, `ROLE_ADMIN`, `ROLE_WAREHOUSE_MANAGER`, `ROLE_WAREHOUSE_STAFF` | Tra cứu nhanh hạn mức công nợ theo query param `dealerId` và `orderAmount` |

#### Kết nối màn hình tạo đơn và giới hạn hiện tại (kiểm tra 14/10/2026)

- Frontend S4-02 gọi `GET /api/dealers/{id}/debt`, đọc `hasOverdueDebt`, `isBlocked` và `blockReason`.
  Không có API `/api/finance/debt/{id}/check`, cũng không có trường `oldestOverdueDays` trong DTO này.
- Nếu API công nợ lỗi, frontend hiển thị lý do và chặn chốt đơn cho tới khi tải lại được dữ liệu.
- `currentDebt` hiện được suy ra từ tổng đơn `RECORDED`. Chưa có sổ công nợ/phiếu thu/phân bổ thanh toán,
  nên chưa thể xác định số dư còn phải thu sau khi khách thanh toán. Không dùng kết quả này như số dư kế toán đã đối soát.
- `debtLimit = 0` hiện không kích hoạt kiểm tra vượt hạn mức; `maxDebtDays = 0` không kích hoạt kiểm tra quá hạn.
- Kiểm tra công nợ không thay thế kiểm tra tồn. Đơn được duyệt hiện chuyển sang `RECORDED`, chưa giữ chỗ tồn trong DB.
- Chi tiết các API đã nối và các tiêu chí Sprint 4 chưa đạt: [Báo cáo Sprint 4](../docs/SPRINT_4_AUDIT_20261014.md).

#### Ví dụ Request kiểm tra công nợ (`POST /api/orders/check-debt`):
```json
{
  "dealerId": 1,
  "orderAmount": 40000000
}
```

#### Ví dụ Response khi vượt hạn mức công nợ (cần duyệt):
```json
{
  "success": true,
  "message": "Kiểm tra công nợ thành công",
  "data": {
    "dealerId": 1,
    "dealerCode": "DL001",
    "dealerName": "Đại lý Cấp 1 Minh Phát",
    "currentDebt": 15000000,
    "debtLimit": 50000000,
    "remainingLimit": 35000000,
    "newOrderTotal": 40000000,
    "projectedDebt": 55000000,
    "exceedsLimit": true,
    "requiresApproval": true,
    "approvalReason": "Đơn hàng mới làm tổng công nợ vượt hạn mức (55,000,000 đ > 50,000,000 đ). Cần Quản lý kinh doanh phê duyệt.",
    "hasOverdueDebt": false,
    "maxOverdueDays": 0,
    "overdueAmount": 0,
    "isBlocked": false,
    "blockReason": null,
    "warningMessage": "Cảnh báo: Đơn hàng mới (40,000,000 đ) cộng công nợ hiện tại (15,000,000 đ) sẽ vượt hạn mức công nợ (50,000,000 đ). Đơn hàng sẽ cần Quản lý duyệt trước khi ghi sổ."
  }
}
```

#### Ví dụ Response lỗi khi bị chặn do nợ quá hạn (HTTP 400 Bad Request):
```json
{
  "success": false,
  "message": "Đại lý 'Đại lý Cấp 1 Minh Phát' (DL001) có khoản nợ quá hạn 45 ngày (vượt quá giới hạn cho phép 30 ngày) với tổng tiền 15,000,000 đ, bị chặn tạo đơn hoàn toàn.",
  "data": {
    "dealerCode": "DL001",
    "dealerName": "Đại lý Cấp 1 Minh Phát",
    "overdueDays": 45,
    "maxDebtDays": 30,
    "overdueAmount": 15000000,
    "errorType": "DEALER_DEBT_OVERDUE"
  }
}
```

## Bảy vai trò

- `ROLE_ADMIN`: quản trị tài khoản và hệ thống; không mặc nhiên xem giá vốn.
- `ROLE_SALES_MANAGER`: quản lý kinh doanh; vai trò duy nhất xem giá vốn và biên lợi nhuận.
- `ROLE_WAREHOUSE_MANAGER`: quản lý, điều chỉnh và phê duyệt kho.
- `ROLE_SALES`: tạo đơn, quản lý khách hàng và địa bàn; không sửa được tồn kho, không xem giá vốn.
- `ROLE_WAREHOUSE_STAFF`: nhập/xuất tại kho được gán; không sửa điều chỉnh tồn kho, không xem giá vốn.
- `ROLE_ACCOUNTANT`: công nợ và báo cáo tài chính, không xem giá vốn.
- `ROLE_VIEWER`: chỉ xem báo cáo được phép.

Mọi chức năng đều kiểm quyền ở tầng server. Endpoint chưa được khai báo hoặc không đủ quyền bị từ chối mặc định (401 khi chưa đăng nhập, 403 khi thiếu quyền).

## Chạy local

1. Tạo MySQL hoặc để ứng dụng tự tạo schema `quanlybanhang`.
2. Cấu hình SMTP thật nếu cần nhận email kích hoạt/reset.
3. Chạy `backend\\mvnw.cmd spring-boot:run`.
4. Chạy `python serve_frontend.py` hoặc `.\run_frontend.ps1` từ thư mục repo,
   rồi mở `http://localhost:5500/login`. Trang chủ là `/dashboard`, các trang quản trị
   là `/admin/users` và `/admin/audit-logs`. Các đường dẫn cũ được chuyển hướng.
   Link kích hoạt mặc định dùng `/login`; link đặt lại mật khẩu dùng `/reset-password?token=...`.

Tài khoản khởi tạo cho môi trường phát triển là `admin`; mật khẩu lấy từ
`APP_BOOTSTRAP_ADMIN_PASSWORD`. Hãy đổi ngay sau lần đăng nhập đầu tiên.

### Seed demo tùy chọn

Đặt các biến môi trường trước khi chạy backend:

```powershell
$env:APP_SEED_DEMO_USERS="true"
$env:APP_DEMO_USER_PASSWORD="mat-khau-demo-ban-tu-chon"
$env:APP_SEED_DEMO_DATA="true"
```

`APP_SEED_DEMO_USERS` và `APP_SEED_DEMO_DATA` mặc định `false`, có thể bật
độc lập. Seed tài khoản bắt buộc có `APP_DEMO_USER_PASSWORD`; không có mật khẩu
hardcode hoặc API seed công khai. Các tài khoản mới là `sales.manager`,
`sales.demo`, `warehouse.manager`, `warehouse.demo`, `accountant.demo` và
`viewer.demo`, mỗi tài khoản có vai trò tương ứng. Tài khoản cũ giữ nguyên
mật khẩu, quyền và phạm vi đã lưu. Seed không gửi email.

Hai chế độ đều cung cấp 2 kho và 3 địa bàn demo. Chế độ nghiệp vụ bổ sung
3 nhà cung cấp, cây 12 nhóm hàng/3 cấp, 8 SKU và 8 đơn vị quy đổi, lịch sử giá
khởi tạo, 5 đại lý (1 bị khóa), hạn mức công nợ, 6 điểm giao, 3 bảng giá/24 dòng,
3 chính sách/5 bậc chiết khấu, 7 đơn và 8 phiếu kho. Có 4 đơn `RECORDED`,
1 `PENDING_APPROVAL`, 1 `CANCELLED`, 1 `REJECTED`; chỉ đơn `RECORDED` góp doanh số.

Đơn và phiếu được ghi qua `OrderService`/`WarehouseService`, nên hệ số quy đổi,
giá sàn, chiết khấu, tồn kho và nhật ký tuân theo logic API. Phiếu nhập mới
được gắn nhà cung cấp. Tồn kho được tính từ 4 phiếu nhập/4 phiếu xuất, không
gán số dư hoặc doanh số giả vào trường legacy. Lịch sử phân công được tạo
cho đại lý mới nếu có tài khoản `sales.demo` đang hoạt động với vai trò Sales.
Bảng giá/chính sách demo có hiệu lực 2020–2099.

Mã nghiệp vụ bắt đầu bằng `DEMO-`; các bản ghi đã có được giữ nguyên. Seed
demo dùng cùng transaction và rollback khi lỗi. Chạy lại không nhân đôi
đơn/phiếu, không đặt lại mật khẩu hoặc cộng tồn kho lần nữa. Tắt hai cờ không
xóa dữ liệu. Số dư công nợ/định mức tồn kho chưa có nguồn dữ liệu vẫn trả theo
hợp đồng API hiện tại. Xem [README](../README.md#5-tài-khoản-đăng-nhập) để chạy
và xem danh sách tài khoản đầy đủ.
