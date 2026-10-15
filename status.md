# Bảng Trạng Thái Triển Khai Tính Năng (Feature Status)

Theo quy định tại `AGENTS.md`, bảng dưới đây theo dõi tiến độ hoàn thành và trạng thái kết nối ma trận phân quyền (Permission Matrix) giữa Backend và Frontend:

| Story | Tính năng (Feature) | Backend Done | Frontend Done | Kết nối Ma trận Phân quyền (Permission Matrix) | Ghi chú (Notes) |
| :--- | :--- | :---: | :---: | :---: | :--- |
| **S4-07** | **Xem danh sách đơn hàng kèm bộ lọc & Tổng tiền toàn công ty (Order Listing with Multi-filters & Total Amount)** | ✅ Đã hoàn thành | ⏳ Chưa thực hiện (Yêu cầu chỉ làm BE) | ✅ Đã kết nối đầy đủ (`ORDER_VIEW`) | - **Vai trò**: Quản lý kinh doanh (`ROLE_SALES_MANAGER`, `ORDER_VIEW`).<br>- **Bộ lọc**: Lọc đa tiêu chí phía server theo trạng thái, đại lý, nhân viên kinh doanh, khu vực và khoảng thời gian tạo đơn.<br>- **Tổng tiền**: Tự động tính và trả về tổng tiền (`totalAmount`) của tập kết quả đang lọc kèm endpoint `/api/orders/summary`.<br>- **Phân quyền RLS**: Nhân viên kinh doanh (`ROLE_SALES`) chỉ thấy đơn của đại lý mình phụ trách; Quản lý kinh doanh & Admin xem toàn công ty.<br>- **Bảo mật**: Chặn truy cập chi tiết đơn của đại lý khác với `403 Forbidden` (`AccessDeniedException`). |
| **S4-09** | **Sao chép đơn cũ thành đơn mới (Order Copy to Draft / New Order)** | ✅ Đã hoàn thành | ✅ Đã hoàn thành | ✅ Đã kết nối đầy đủ (`ORDER_CREATE`) | - **Vai trò**: Nhân viên kinh doanh (`ROLE_SALES`, `ORDER_CREATE`).<br>- **Dòng hàng**: Sao chép toàn bộ dòng hàng sang đơn mới / draft.<br>- **Giá & Chiết khấu**: Tự động áp lại bảng giá hiện hành (`findEffectivePriceLists`) và chính sách chiết khấu sản lượng hiện hành (`calculateOrderDiscount`), không kế thừa giá cũ.<br>- **Trạng thái**: Luôn bắt đầu ở trạng thái Nháp (`DRAFT`).<br>- **Phân quyền Backend**: `@PreAuthorize("hasAuthority('ORDER_CREATE') or ...")` và RLS kiểm tra quyền phụ trách đại lý.<br>- **Phân quyền Frontend**: Kiểm tra `Auth.hasPermission('ORDER_CREATE')` trước khi hiển thị nút sao chép và thực hiện sao chép. |
| **S5-01** | **Theo dõi trạng thái đơn hàng của đại lý** | ✅ Có ở nhánh backend S5-01, chưa gộp vào `develop` | ✅ Đã nối API trên nhánh frontend hiện tại | ✅ Frontend giới hạn menu/route theo `ROLE_DEALER` + `ORDER_VIEW`; backend API giới hạn dữ liệu theo đại lý | Frontend chỉ dùng hợp đồng `GET /api/portal/orders` và `GET /api/portal/orders/{id}`. Không kéo code backend và không merge nhánh backend. |
| **S5-07** | **Lập phiếu chuyển kho nội bộ (Internal Warehouse Transfer)** | ✅ Đã hoàn thành | ⏳ Chưa thực hiện (Yêu cầu chỉ làm BE) | ✅ Đã kết nối đầy đủ (`WAREHOUSE_TRANSFER`, `WAREHOUSE_EXPORT`, `WAREHOUSE_IMPORT`) | - **Vai trò**: Nhân viên kho (`ROLE_WAREHOUSE_STAFF`), Quản lý kho (`ROLE_WAREHOUSE_MANAGER`), Admin (`ROLE_ADMIN`).<br>- **Lập phiếu chuyển**: Chọn kho đi, kho đến (khác nhau), danh sách hàng & số lượng. Trừ tồn kho tại kho đi ngay khi xuất; hàng ghi nhận đang trên đường (`inTransitQuantity`), **CHƯA cộng vào kho đến**.<br>- **Xác nhận nhận hàng**: Kho đến xác nhận nhận đủ/thực nhận thì tồn kho đến mới được cộng theo số lượng thực nhận, hàng trên đường được xóa về 0.<br>- **Chênh lệch**: Khi nhận có chênh lệch (`received != sent`), **bắt buộc phải nhập lý do**; nếu không nhập lý do sẽ bị từ chối với lỗi `400 Bad Request`.<br>- **Hủy phiếu**: Hỗ trợ hủy phiếu khi đang trên đường (`IN_TRANSIT`), hoàn lại tồn kho xuất và xóa hàng trên đường.<br>- **Phân quyền Backend**: `@PreAuthorize("hasAuthority('WAREHOUSE_TRANSFER') or ...")`. Chặn Nhân viên kinh doanh (`ROLE_SALES`) thực hiện chuyển kho (`403 Forbidden`).<br>- **Phân quyền Frontend**: Ma trận quyền tại `GET /api/admin/permissions` đã tự động nạp `WAREHOUSE_TRANSFER`, sẵn sàng cho giao diện frontend kiểm tra qua `Auth.hasPermission('WAREHOUSE_TRANSFER')`. |


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

## Chi tiết kết nối phân quyền S5-07 (Permission Matrix Details)

- **Mã quyền (Permission Code)**: `WAREHOUSE_TRANSFER` ("Chuyển kho nội bộ")
- **Các vai trò được cấp quyền trong ma trận phân quyền**:
  - `ROLE_ADMIN`: Toàn quyền quản trị hệ thống (luôn có tất cả quyền).
  - `ROLE_WAREHOUSE_MANAGER`: Quản lý kho (toàn quyền lập, nhận, hủy phiếu chuyển kho nội bộ).
  - `ROLE_WAREHOUSE_STAFF`: Nhân viên kho (được phép lập phiếu chuyển kho và xác nhận nhận hàng).
  - `ROLE_SALES`: Nhân viên kinh doanh bị **cấm hoàn toàn** quyền chuyển kho (`WAREHOUSE_TRANSFER`), chặn cập nhật qua ma trận phân quyền và trả về `403 Forbidden` khi cố truy cập.
- **Backend**:
  - `WarehouseController.java`:
    - `POST /api/warehouse/transfers` & `POST /api/warehouse/transfer`: `@PreAuthorize("hasAuthority('WAREHOUSE_TRANSFER') or hasAuthority('WAREHOUSE_EXPORT') or hasAnyRole('WAREHOUSE_STAFF', 'WAREHOUSE_MANAGER', 'ADMIN')")`
    - `GET /api/warehouse/transfers`: `@PreAuthorize("hasAnyAuthority('WAREHOUSE_TRANSFER', 'WAREHOUSE_VIEW') or hasAnyRole('WAREHOUSE_STAFF', 'WAREHOUSE_MANAGER', 'SALES_MANAGER', 'ADMIN', 'VIEWER')")`
    - `GET /api/warehouse/transfers/{id}`: `@PreAuthorize("hasAnyAuthority('WAREHOUSE_TRANSFER', 'WAREHOUSE_VIEW') or hasAnyRole('WAREHOUSE_STAFF', 'WAREHOUSE_MANAGER', 'SALES_MANAGER', 'ADMIN', 'VIEWER')")`
    - `POST /api/warehouse/transfers/{id}/receive`: `@PreAuthorize("hasAuthority('WAREHOUSE_TRANSFER') or hasAuthority('WAREHOUSE_IMPORT') or hasAnyRole('WAREHOUSE_STAFF', 'WAREHOUSE_MANAGER', 'ADMIN')")`
    - `POST /api/warehouse/transfers/{id}/cancel`: `@PreAuthorize("hasAuthority('WAREHOUSE_TRANSFER') or hasAuthority('WAREHOUSE_APPROVE') or hasAnyRole('WAREHOUSE_MANAGER', 'ADMIN')")`
  - `WarehouseServiceImpl.java`:
    - `createTransfer`:
      - Kiểm tra tính hợp lệ của kho xuất và kho nhập (khác nhau, cùng active).
      - Đóng băng đơn vị tính, hệ số quy đổi và số lượng cơ sở.
      - Trừ tồn kho cơ sở tại kho xuất ngay khi xuất chuyển.
      - Tăng `inTransitQuantity` tại kho nhập để ghi nhận hàng đang trên đường (tồn kho cơ sở `baseQuantity` của kho nhập **chưa được cộng**).
      - Trạng thái phiếu: `IN_TRANSIT` ("Đang trên đường").
    - `receiveTransfer`:
      - Khi kho đến xác nhận nhận hàng, trừ lượng hàng trên đường (`inTransitQuantity`).
      - Cộng tồn kho cơ sở tại kho đến theo số lượng thực nhận (`receivedBaseQuantity`).
      - Nếu có chênh lệch giữa số lượng xuất và nhận (`discrepancyQuantity != 0`), **bắt buộc phải nhập lý do**; nếu để trống trả về lỗi `400 Bad Request`.
      - Trạng thái phiếu: `COMPLETED` ("Đã nhận hàng").
    - `cancelTransfer`:
      - Áp dụng khi phiếu ở trạng thái `IN_TRANSIT`.
      - Hoàn lại tồn kho cho kho xuất và xóa hàng đang trên đường tại kho nhập.
      - Trạng thái phiếu: `CANCELLED` ("Đã hủy").
  - `RolePermissionConfig.java`:
    - Gán mặc định `WAREHOUSE_TRANSFER` cho `ROLE_WAREHOUSE_STAFF` và `ROLE_WAREHOUSE_MANAGER`.
    - Bảo vệ ma trận: Từ chối nếu Admin cố tình gán `WAREHOUSE_TRANSFER` cho `ROLE_SALES`.
- **Frontend**:
  - Endpoint `GET /api/admin/permissions` tự động nạp `WAREHOUSE_TRANSFER` vào danh mục quyền của hệ thống và hiển thị trên màn hình quản trị ma trận phân quyền (`admin-users.js`).
  - Sẵn sàng tích hợp kiểm tra qua `Auth.hasPermission('WAREHOUSE_TRANSFER')` hoặc `Auth.hasAnyRole('ROLE_WAREHOUSE_STAFF', 'ROLE_WAREHOUSE_MANAGER', 'ROLE_ADMIN')` khi xây dựng giao diện chuyển kho.


