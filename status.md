# Bảng Trạng Thái Triển Khai Tính Năng (Feature Status)

Theo quy định tại `AGENTS.md`, bảng dưới đây theo dõi tiến độ hoàn thành và trạng thái kết nối ma trận phân quyền (Permission Matrix) giữa Backend và Frontend:

| Story | Tính năng (Feature) | Backend Done | Frontend Done | Kết nối Ma trận Phân quyền (Permission Matrix) | Ghi chú (Notes) |
| :--- | :--- | :---: | :---: | :---: | :--- |
| **S4-07** | **Xem danh sách đơn hàng kèm bộ lọc & Tổng tiền toàn công ty (Order Listing with Multi-filters & Total Amount)** | ✅ Đã hoàn thành | ⏳ Chưa thực hiện (Yêu cầu chỉ làm BE) | ✅ Đã kết nối đầy đủ (`ORDER_VIEW`) | - **Vai trò**: Quản lý kinh doanh (`ROLE_SALES_MANAGER`, `ORDER_VIEW`).<br>- **Bộ lọc**: Lọc đa tiêu chí phía server theo trạng thái, đại lý, nhân viên kinh doanh, khu vực và khoảng thời gian tạo đơn.<br>- **Tổng tiền**: Tự động tính và trả về tổng tiền (`totalAmount`) của tập kết quả đang lọc kèm endpoint `/api/orders/summary`.<br>- **Phân quyền RLS**: Nhân viên kinh doanh (`ROLE_SALES`) chỉ thấy đơn của đại lý mình phụ trách; Quản lý kinh doanh & Admin xem toàn công ty.<br>- **Bảo mật**: Chặn truy cập chi tiết đơn của đại lý khác với `403 Forbidden` (`AccessDeniedException`). |
| **S4-09** | **Sao chép đơn cũ thành đơn mới (Order Copy to Draft / New Order)** | ✅ Đã hoàn thành | ✅ Đã hoàn thành | ✅ Đã kết nối đầy đủ (`ORDER_CREATE`) | - **Vai trò**: Nhân viên kinh doanh (`ROLE_SALES`, `ORDER_CREATE`).<br>- **Dòng hàng**: Sao chép toàn bộ dòng hàng sang đơn mới / draft.<br>- **Giá & Chiết khấu**: Tự động áp lại bảng giá hiện hành (`findEffectivePriceLists`) và chính sách chiết khấu sản lượng hiện hành (`calculateOrderDiscount`), không kế thừa giá cũ.<br>- **Trạng thái**: Luôn bắt đầu ở trạng thái Nháp (`DRAFT`).<br>- **Phân quyền Backend**: `@PreAuthorize("hasAuthority('ORDER_CREATE') or ...")` và RLS kiểm tra quyền phụ trách đại lý.<br>- **Phân quyền Frontend**: Kiểm tra `Auth.hasPermission('ORDER_CREATE')` trước khi hiển thị nút sao chép và thực hiện sao chép. |
| **S5-01** | **Theo dõi trạng thái đơn hàng của đại lý** | ✅ Có ở nhánh backend S5-01, chưa gộp vào `develop` | ✅ Đã nối API trên nhánh frontend hiện tại | ✅ Frontend giới hạn menu/route theo `ROLE_DEALER` + `ORDER_VIEW`; backend API giới hạn dữ liệu theo đại lý | Frontend chỉ dùng hợp đồng `GET /api/portal/orders` và `GET /api/portal/orders/{id}`. Không kéo code backend và không merge nhánh backend. |
| **S5-02** | **Đại lý đặt lại đơn đã mua trước đây để lấy hàng định kỳ (Dealer Re-order from Previous Order)** | ⏳ Chờ đồng bộ nhánh backend (Đã có sẵn API core: `/portal/orders`, `/products`, `/price-lists/effective`, `POST /orders`) | ✅ Đã hoàn thành (Theo yêu cầu chỉ làm frontend) | ✅ Đã kết nối đầy đủ (`ROLE_DEALER`, `ORDER_CREATE`, `ORDER_VIEW`) | - **Vai trò**: Đại lý (`ROLE_DEALER`, `ORDER_CREATE`), Quản trị viên (`ROLE_ADMIN`).<br>- **Dòng hàng**: Cho phép chọn đặt lại toàn bộ hoặc một phần dòng hàng qua checkbox và stepper số lượng linh hoạt.<br>- **Hàng ngừng kinh doanh**: Tự động nhận diện qua catalog sản phẩm (`active = false` hoặc `status = INACTIVE`), loại bỏ khỏi danh sách đặt lại và hiển thị thông báo chi tiết danh sách mặt hàng bị loại.<br>- **Bảng giá hiện hành**: Áp lại theo bảng giá hiệu lực hiện hành (`/price-lists/effective`) hoặc giá catalog hiện hành, không giữ giá cũ.<br>- **Phân quyền**: Nút "Đặt lại" và modal đặt lại chỉ hiển thị/cho phép thao tác khi người dùng có quyền `ORDER_CREATE` theo ma trận phân quyền. |

---

## Chi tiết kết nối phân quyền S4-07 (Permission Matrix Details)

- **Mã quyền (Permission Code)**: `ORDER_VIEW`
- **Các vai trò được cấp quyền trong ma trận phân quyền**:
  - `ROLE_ADMIN`: Toàn quyền quản trị hệ thống
  - `ROLE_SALES_MANAGER`: Quản lý kinh doanh (xem tình hình bán hàng toàn công ty)
  - `ROLE_SALES`: Nhân viên kinh doanh (phụ trách đại lý)
  - `ROLE_WAREHOUSE_MANAGER`: Quản lý kho
  - `ROLE_WAREHOUSE_STAFF`: Nhân viên kho
  - `ROLE_ACCOUNTANT`: Kế toán
  - `ROLE_VIEWER`: Người xem báo cáo
- **Backend**:
  - `OrderController.java`:
    - `GET /api/orders`: `@PreAuthorize("hasAuthority('ORDER_VIEW') or hasAnyRole('ADMIN', 'SALES_MANAGER', 'SALES', 'WAREHOUSE_MANAGER', 'WAREHOUSE_STAFF', 'ACCOUNTANT', 'VIEWER')")`
    - `GET /api/orders/summary`: `@PreAuthorize("hasAuthority('ORDER_VIEW') or hasAnyRole('ADMIN', 'SALES_MANAGER', 'SALES', 'WAREHOUSE_MANAGER', 'WAREHOUSE_STAFF', 'ACCOUNTANT', 'VIEWER')")`
    - `GET /api/orders/{id}`: `@PreAuthorize("hasAuthority('ORDER_VIEW') or hasAnyRole('ADMIN', 'SALES_MANAGER', 'SALES', 'WAREHOUSE_MANAGER', 'WAREHOUSE_STAFF', 'ACCOUNTANT', 'VIEWER')")`
  - `OrderServiceImpl.java`:
    - `getOrders(OrderFilterRequest filter)`:
      - Xây dựng JPA Specification lọc động theo trạng thái, đại lý, nhân viên, khu vực, khoảng thời gian, từ khóa tìm kiếm.
      - Tự động tính tổng tiền (`totalAmount`) và tổng số đơn (`totalOrders`) của toàn bộ tập kết quả đang lọc.
      - Phân quyền dòng dữ liệu (Row-level Security): Nếu là `ROLE_SALES` (không có quyền quản lý/admin), tự động ép điều kiện `dealer.assignedUser = currentUser`.
    - `getOrderById(Long id, String username)`:
      - Kiểm soát quyền xem: nhân viên kinh doanh cố tình truy cập đơn của đại lý không do mình phụ trách sẽ bị chặn với `403 Forbidden` (`AccessDeniedException`).
    - `mapToResponse(Order order)`: Làm giàu dữ liệu trả về với thông tin đại lý (`dealerCode`, `dealerName`), nhân viên phụ trách (`salesUserId`, `salesUserName`, `salesUserFullName`), và khu vực (`territoryId`, `territoryCode`, `territoryName`).
- **Frontend**:
  - Đã sẵn sàng kết nối qua `Auth.hasPermission('ORDER_VIEW')` và `Auth.hasRole('ROLE_SALES_MANAGER')` khi triển khai giao diện màn hình danh sách đơn hàng cho Quản lý kinh doanh.

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

## Chi tiết kết nối phân quyền S5-02 (Permission Matrix Details)

- **Mã quyền kết nối**: `ORDER_CREATE` (Tạo và đặt lại đơn hàng), `ORDER_VIEW` (Xem danh sách đơn hàng đã mua)
- **Các vai trò được phép thao tác**:
  - `ROLE_DEALER`: Đại lý chính thức (theo dõi đơn hàng và chủ động đặt lại định kỳ)
  - `ROLE_ADMIN`: Quản trị viên hệ thống
- **Phía Frontend**:
  - **Trang giao diện**: `dealer-orders.html` và `dealer-orders.js`
  - **Kiểm tra phân quyền động**:
    - Khi tải trang, hàm `canReorder()` kiểm tra `Auth.hasPermission('ORDER_CREATE')` (hoặc vai trò `ROLE_DEALER`/`ROLE_ADMIN`).
    - Nếu quyền `ORDER_CREATE` bị vô hiệu hóa trong bảng ma trận phân quyền người dùng (`/admin/users`), nút **Đặt lại** ở từng dòng đơn hàng và nút **Đặt lại đơn này** trong modal chi tiết đơn hàng sẽ tự động bị ẩn.
    - Chặn mở modal đặt lại nếu không có quyền `ORDER_CREATE`, có thông báo cảnh báo toast rõ ràng.
  - **Xử lý nghiệp vụ chọn dòng hàng (Toàn bộ hoặc một phần)**:
    - Khi mở modal đặt lại, mặc định tất cả các dòng hàng đang còn hoạt động được chọn (chọn toàn bộ).
    - Người dùng có thể bỏ chọn từng dòng hàng độc lập qua checkbox (chọn một phần dòng hàng), điều chỉnh số lượng đặt lại định kỳ qua bộ nút tăng/giảm stepper (`min="1"`).
    - Thao tác chọn/bỏ chọn và thay đổi số lượng cập nhật ngay lập tức thành tiền dòng và tổng tiền đơn hàng theo thời gian thực.
  - **Xử lý mặt hàng đã ngừng kinh doanh (Discontinued items)**:
    - Tự động đối soát từng sản phẩm trong đơn cũ với catalog sản phẩm (`/products`).
    - Bất kỳ sản phẩm nào có cờ `active = false` hoặc `status = 'INACTIVE'` sẽ được **tự động loại bỏ hoàn toàn** khỏi bảng danh sách đặt lại.
    - Hiển thị khối thông báo cảnh báo rõ ràng (`#dealer-reorder-discontinued-alert`): nêu rõ danh sách tên sản phẩm, mã sản phẩm, đơn vị tính đã ngừng kinh doanh để đại lý nắm bắt.
    - Nếu toàn bộ mặt hàng trong đơn cũ đều đã ngừng kinh doanh, hệ thống hiển thị thông báo lỗi và khóa nút gửi đơn đặt lại.
  - **Áp lại giá theo bảng giá hiện hành (Repricing)**:
    - Tra cứu bảng giá có hiệu lực hiện hành (`/price-lists/effective?customerGroup=...`) theo nhóm khách hàng của đại lý.
    - Tự động áp đơn giá hiện hành cho từng dòng sản phẩm, nhân theo hệ số quy đổi đơn vị tính (nếu đặt theo quy cách khác đơn vị cơ sở). Không kế thừa giá cũ của đơn hàng trước.
    - Hiển thị badge **Giá hiện hành** bên cạnh đơn giá mới.
  - **Gửi đơn đặt lại**:
    - Gửi request đến API backend cổng đại lý (thử `/portal/orders/reorder`, `/portal/orders`, hoặc fallback `/orders`).
    - Reset dữ liệu, tự động làm mới danh sách đơn hàng và hiển thị toast thông báo thành công kèm mã đơn mới.
- **Phía Backend**:
  - Role `ROLE_DEALER` trong cấu hình phân quyền backend (`RolePermissionConfig`) sở hữu mặc định các quyền `ORDER_VIEW` và `ORDER_CREATE`.
  - Dữ liệu đơn hàng được cô lập theo `dealer_id` của tài khoản đăng nhập (Row-Level Security).


