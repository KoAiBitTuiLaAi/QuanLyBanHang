# Bảng Trạng Thái Triển Khai Tính Năng (Feature Status)

Theo quy định tại `AGENTS.md`, bảng dưới đây theo dõi tiến độ hoàn thành và trạng thái kết nối ma trận phân quyền (Permission Matrix) giữa Backend và Frontend:

| Story | Tính năng (Feature) | Backend Done | Frontend Done | Kết nối Ma trận Phân quyền (Permission Matrix) | Ghi chú (Notes) |
| :--- | :--- | :---: | :---: | :---: | :--- |
| **S4-09** | **Sao chép đơn cũ thành đơn mới (Order Copy to Draft / New Order)** | ✅ Đã hoàn thành | ✅ Đã hoàn thành | ✅ Đã kết nối đầy đủ (`ORDER_CREATE`) | - **Vai trò**: Nhân viên kinh doanh (`ROLE_SALES`, `ORDER_CREATE`).<br>- **Dòng hàng**: Sao chép toàn bộ dòng hàng sang đơn mới / draft.<br>- **Giá & Chiết khấu**: Tự động áp lại bảng giá hiện hành (`findEffectivePriceLists`) và chính sách chiết khấu sản lượng hiện hành (`calculateOrderDiscount`), không kế thừa giá cũ.<br>- **Trạng thái**: Luôn bắt đầu ở trạng thái Nháp (`DRAFT`).<br>- **Phân quyền Backend**: `@PreAuthorize("hasAuthority('ORDER_CREATE') or ...")` và RLS kiểm tra quyền phụ trách đại lý.<br>- **Phân quyền Frontend**: Kiểm tra `Auth.hasPermission('ORDER_CREATE')` trước khi hiển thị nút sao chép và thực hiện sao chép. |
| **S5-01** | **Theo dõi trạng thái đơn hàng của Đại lý (Dealer Order Tracking & Delivery Status)** | ✅ Đã hoàn thành | ⏳ Chờ thực hiện | ✅ Đã kết nối đầy đủ (`ORDER_VIEW`, `ORDER_CREATE`) | - **Vai trò**: Đại lý (`ROLE_DEALER`).<br>- **Tiêu chí 1**: Danh sách đơn kèm trạng thái, ngày giao dự kiến, tổng tiền (`GET /api/portal/orders`).<br>- **Tiêu chí 2**: Xem chi tiết dòng hàng, số lượng thực giao và số lượng giao thiếu (`GET /api/portal/orders/{id}`).<br>- **Tiêu chí 3**: Cô lập dữ liệu (Row-Level Security) chỉ xem đơn của chính mình, chặn truy cập chéo (403 Forbidden).<br>- **Kho & Giao hàng**: API `PUT /api/orders/{id}/delivery` dành cho thủ kho ghi nhận số lượng thực giao.<br>- **Phạm vi**: Chỉ làm BE theo yêu cầu. |

---

## Chi tiết kết nối phân quyền S4-09 (Permission Matrix Details)

- **Mã quyền (Permission Code)**: `ORDER_CREATE`
- **Các vai trò được cấp quyền**:
  - `ROLE_ADMIN`: Toàn quyền quản trị
  - `ROLE_SALES_MANAGER`: Quản lý kinh doanh
  - `ROLE_SALES`: Nhân viên kinh doanh (phụ trách đại lý)
- **Backend**:
  - `OrderController.java`:
    - `POST /api/orders/{id}/copy`: `@PreAuthorize("hasAuthority('ORDER_CREATE') or hasAnyRole('SALES', 'SALES_MANAGER', 'ADMIN', 'WAREHOUSE_MANAGER', 'WAREHOUSE_STAFF')")`
    - `POST /api/orders/{id}/copy-to-draft`: `@PreAuthorize("hasAuthority('ORDER_CREATE') or hasAnyRole('SALES', 'SALES_MANAGER', 'ADMIN', 'WAREHOUSE_MANAGER', 'WAREHOUSE_STAFF')")`
  - `OrderServiceImpl.java`: `validateSalesAccess` kiểm soát truy cập phân quyền dòng dữ liệu (Row-level Security) cho nhân viên kinh doanh theo đại lý phụ trách hoặc đơn do chính mình tạo.
- **Frontend**:
  - `dealer-transactions.js`: Kiểm tra `Auth.hasPermission('ORDER_CREATE')` để hiển thị nút hành động "Sao chép đơn".
  - `create-order.js`: Tự động nạp dữ liệu từ bản nháp đã sao chép, gọi bảng giá và chiết khấu hiện hành.

---

## Chi tiết kết nối Ma trận phân quyền S5-01 (Dealer Portal)

### 1. Phía Backend (Đã kết nối đầy đủ - PROPERLY CONNECTED)
- **Role mới**: Bổ sung `ROLE_DEALER` ("Đại lý") vào enum `ERole`.
- **Cấu hình quyền mặc định (`RolePermissionConfig`)**:
  - `ROLE_DEALER` được cấp các quyền mặc định: `EPermission.ORDER_VIEW`, `EPermission.ORDER_CREATE`.
  - Thiết lập cơ chế ràng buộc bảo mật (`ROLE_RESTRICTIONS`): Đại lý tuyệt đối không thể được gán các quyền quản lý kho (`INVENTORY_ADJUST`, `INVENTORY_VIEW`, `INVENTORY_IMPORT`, `INVENTORY_EXPORT`), không có quyền xem giá vốn / biên lợi nhuận (`COST_PRICE_VIEW`, `PROFIT_MARGIN_VIEW`).
  - Ma trận quyền động (`/api/admin/permissions`) tự động nhận diện `ROLE_DEALER`, cho phép quản trị viên xem và quản lý trong ma trận.
- **Kiểm soát bảo mật mức hàng (Row-Level Security & Data Isolation)**:
  - Tài khoản người dùng có trường `dealer_id` liên kết với đại lý đại diện.
  - Các endpoint `/api/portal/orders` và `/api/portal/orders/{id}` được bảo vệ bằng `@PreAuthorize("hasAnyRole('DEALER', 'ADMIN')")`.
  - Tầng nghiệp vụ (`OrderServiceImpl`) lọc dữ liệu chặt chẽ theo `dealer_id`. Nếu một đại lý cố tình truy cập ID đơn hàng của đại lý khác, hệ thống lập tức ném ngoại lệ `AccessDeniedException` và trả về `403 Forbidden`.
  - Nhân viên kinh doanh (`ROLE_SALES`) chỉ xem được đơn hàng của các đại lý mà mình phụ trách.
  - Quản trị viên (`ROLE_ADMIN`) và Quản lý kinh doanh (`ROLE_SALES_MANAGER`) có quyền xem toàn bộ.
- **Tiến trình giao hàng & thiếu hàng (Delivery Shortage Tracking)**:
  - Cung cấp API `PUT /api/orders/{id}/delivery` dành riêng cho Kho / Quản trị viên (`ROLE_WAREHOUSE_STAFF`, `ROLE_WAREHOUSE_MANAGER`, `ROLE_ADMIN`).
  - Tính toán tự động `shortageQuantity` (`quantity - deliveredQuantity`), cờ `isShortage`, và order-level `hasShortage`.

### 2. Phía Frontend (Chờ thực hiện - PENDING)
- Theo yêu cầu của người dùng, sprint này chỉ tập trung triển khai Backend ("chỉ làm BE").
- Phía Frontend sẽ kế thừa dữ liệu ma trận quyền từ API `/api/admin/permissions` khi triển khai giao diện màn hình Cổng Đại lý (Dealer Portal) ở sprint tiếp theo.
