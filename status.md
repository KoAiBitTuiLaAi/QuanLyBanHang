# Bảng Trạng Thái Triển Khai Tính Năng (Feature Status)

Theo quy định tại `AGENTS.md`, bảng dưới đây theo dõi tiến độ hoàn thành và trạng thái kết nối ma trận phân quyền (Permission Matrix) giữa Backend và Frontend:

| Story | Tính năng (Feature) | Backend Done | Frontend Done | Kết nối Ma trận Phân quyền (Permission Matrix) | Ghi chú (Notes) |
| :--- | :--- | :---: | :---: | :---: | :--- |
| **S4-07** | **Xem danh sách đơn hàng kèm bộ lọc & Tổng tiền toàn công ty (Order Listing with Multi-filters & Total Amount)** | ✅ Đã hoàn thành | ⏳ Chưa thực hiện (Yêu cầu chỉ làm BE) | ✅ Đã kết nối đầy đủ (`ORDER_VIEW`) | - **Vai trò**: Quản lý kinh doanh (`ROLE_SALES_MANAGER`, `ORDER_VIEW`).<br>- **Bộ lọc**: Lọc đa tiêu chí phía server theo trạng thái, đại lý, nhân viên kinh doanh, khu vực và khoảng thời gian tạo đơn.<br>- **Tổng tiền**: Tự động tính và trả về tổng tiền (`totalAmount`) của tập kết quả đang lọc kèm endpoint `/api/orders/summary`.<br>- **Phân quyền RLS**: Nhân viên kinh doanh (`ROLE_SALES`) chỉ thấy đơn của đại lý mình phụ trách; Quản lý kinh doanh & Admin xem toàn công ty.<br>- **Bảo mật**: Chặn truy cập chi tiết đơn của đại lý khác với `403 Forbidden` (`AccessDeniedException`). |
| **S4-09** | **Sao chép đơn cũ thành đơn mới (Order Copy to Draft / New Order)** | ✅ Đã hoàn thành | ✅ Đã hoàn thành | ✅ Đã kết nối đầy đủ (`ORDER_CREATE`) | - **Vai trò**: Nhân viên kinh doanh (`ROLE_SALES`, `ORDER_CREATE`).<br>- **Dòng hàng**: Sao chép toàn bộ dòng hàng sang đơn mới / draft.<br>- **Giá & Chiết khấu**: Tự động áp lại bảng giá hiện hành (`findEffectivePriceLists`) và chính sách chiết khấu sản lượng hiện hành (`calculateOrderDiscount`), không kế thừa giá cũ.<br>- **Trạng thái**: Luôn bắt đầu ở trạng thái Nháp (`DRAFT`).<br>- **Phân quyền Backend**: `@PreAuthorize("hasAuthority('ORDER_CREATE') or ...")` và RLS kiểm tra quyền phụ trách đại lý.<br>- **Phân quyền Frontend**: Kiểm tra `Auth.hasPermission('ORDER_CREATE')` trước khi hiển thị nút sao chép và thực hiện sao chép. |
| **S4-10** | **Cổng tự đặt hàng dành cho Đại lý (Dealer Self-Ordering Portal)** | ✅ Đã hoàn thành | ⏳ Đã có trên nhánh FE `feature/S4-10-chuc-nang-dat-hang-cho-dai-ly(frontend)`, chưa gộp vào `develop` | ✅ Đã kết nối đầy đủ (`ROLE_DEALER`, `ORDER_CREATE`, `ORDER_VIEW`, `PRODUCT_VIEW`) | - **Vai trò**: Khách hàng đại lý (`ROLE_DEALER`).<br>- **Xem giá & sản phẩm**: Chỉ thấy sản phẩm & giá thuộc bảng giá hiệu lực của nhóm khách hàng của mình (`DAI_LY_CAP_1`, `DAI_LY_CAP_2`, `KHACH_LE`), kèm quy cách đóng gói (thùng/lốc...).<br>- **Công nợ còn lại**: Xem hạn mức, công nợ hiện tại và công nợ còn lại thời gian thực trước khi đặt hàng.<br>- **Trạng thái đơn**: Bắt buộc luôn ở trạng thái Chờ duyệt (`PENDING_APPROVAL`, `requiresApproval = true`), chờ NVKD phụ trách xác nhận.<br>- **Phê duyệt đơn**: NVKD phụ trách đại lý (`assignedUser`) có quyền xác nhận (`PUT /orders/{id}/approve`) hoặc từ chối (`PUT /orders/{id}/reject`). NVKD khác bị chặn `403 Forbidden`.<br>- **Bảo mật RLS**: Đại lý bị khoá giao dịch bị chặn đặt hàng (`400 Bad Request`). Đại lý chỉ xem được đơn hàng của chính mình. |
| **S5-01** | **Theo dõi trạng thái đơn hàng của đại lý** | ✅ Có ở nhánh backend S5-01, chưa gộp vào `develop` | ✅ Đã nối API trên nhánh frontend hiện tại | ✅ Frontend giới hạn menu/route theo `ROLE_DEALER` + `ORDER_VIEW`; backend API giới hạn dữ liệu theo đại lý | Frontend chỉ dùng hợp đồng `GET /api/portal/orders` và `GET /api/portal/orders/{id}`. Không kéo code backend và không merge nhánh backend. |

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

## Chi tiết kết nối phân quyền S4-10 (Permission Matrix Details)

- **Vai trò mới (Role)**: `ROLE_DEALER` (Khách hàng đại lý tự đặt hàng)
- **Mã quyền được cấp trong ma trận (RolePermissionConfig)**:
  - `ORDER_CREATE`: Quyền tạo đơn đặt hàng mới
  - `ORDER_VIEW`: Quyền xem danh sách và chi tiết đơn hàng của chính mình
  - `PRODUCT_VIEW`: Quyền xem danh mục sản phẩm và bảng giá hiệu lực của nhóm khách hàng mình
- **Các vai trò liên quan trong quy trình**:
  - `ROLE_DEALER`: Tạo đơn qua cổng tự đặt hàng, kiểm tra công nợ còn lại, xem đơn hàng của mình.
  - `ROLE_SALES`: Nhân viên kinh doanh phụ trách đại lý (`assignedUser`), xác nhận (`approve`) hoặc từ chối (`reject`) đơn hàng của đại lý. Bị chặn nếu không phải người phụ trách.
  - `ROLE_SALES_MANAGER` & `ROLE_ADMIN`: Có toàn quyền duyệt/từ chối mọi đơn hàng hoặc đơn bán dưới giá sàn.
- **Backend**:
  - `ERole.java`: Bổ sung `ROLE_DEALER`.
  - `RolePermissionConfig.java`: Khai báo quyền mặc định cho `ROLE_DEALER` (`ORDER_VIEW`, `ORDER_CREATE`, `PRODUCT_VIEW`). Chặn gán quyền kho cho `ROLE_DEALER`.
  - `User.java`: Thêm liên kết `@ManyToOne JoinColumn(name = "dealer_id") private Dealer dealer;`.
  - `DealerPortalController.java` (`/api/portal/**`):
    - `GET /api/portal/profile`: Lấy thông tin hồ sơ đại lý, thông tin NVKD phụ trách, công nợ và danh sách điểm giao hàng.
    - `GET /api/portal/catalog`: Chỉ trả về sản phẩm và đơn giá trong bảng giá có hiệu lực của nhóm khách hàng đại lý (`DAI_LY_CAP_1`, `DAI_LY_CAP_2`, `KHACH_LE`), kèm quy cách đóng gói quy đổi.
    - `GET /api/portal/debt`: Tra cứu hạn mức và công nợ còn lại (`remainingLimit`) thời gian thực.
    - `POST /api/portal/orders`: Tự tạo đơn hàng, luôn ép trạng thái `PENDING_APPROVAL` và `requiresApproval = true` kèm lý do chờ NVKD phụ trách xác nhận. Chặn nếu đại lý bị khoá (`DealerLockedException` -> `400 Bad Request`).
    - `GET /api/portal/orders`: Lấy danh sách lịch sử đơn hàng của chính đại lý.
    - `GET /api/portal/orders/{id}`: Xem chi tiết đơn hàng của đại lý (bảo mật RLS, chặn xem đơn của đại lý khác).
  - `OrderServiceImpl.java`:
    - `createOrder`: Khi người tạo là `ROLE_DEALER`, đơn hàng **luôn luôn** chuyển sang trạng thái `PENDING_APPROVAL` (`requiresApproval = true`, `approvalReason = "[Đại lý tự đặt qua cổng] Chờ nhân viên phụ trách xác nhận"`).
    - `approveOrder`: Cho phép NVKD phụ trách đại lý (`assignedUser`) xác nhận đơn do đại lý tự đặt. Bị chặn nếu là NVKD khác.
    - `rejectOrder`: Cho phép NVKD phụ trách đại lý từ chối đơn hàng kèm lý do.
    - `getOrders` & `getOrderById`: Áp dụng Row-Level Security, đại lý chỉ thấy đơn hàng của chính mình.
- **Frontend**:
  - Đã có nhánh frontend `feature/S4-10-chuc-nang-dat-hang-cho-dai-ly(frontend)`.
  - Phân quyền Frontend: Menu và route Cổng đại lý hiển thị khi người dùng có `ROLE_DEALER` và quyền `ORDER_CREATE`/`ORDER_VIEW`.


