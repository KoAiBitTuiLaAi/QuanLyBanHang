# Bảng Trạng Thái Triển Khai Tính Năng (Feature Status)

Theo quy định tại `AGENTS.md`, bảng dưới đây theo dõi tiến độ hoàn thành và trạng thái kết nối ma trận phân quyền (Permission Matrix) giữa Backend và Frontend:

| Story | Tính năng (Feature) | Backend Done | Frontend Done | Kết nối Ma trận Phân quyền (Permission Matrix) | Ghi chú (Notes) |
| :--- | :--- | :---: | :---: | :---: | :--- |
| **S4-07** | **Xem danh sách đơn hàng kèm bộ lọc & Tổng tiền toàn công ty (Order Listing with Multi-filters & Total Amount)** | ✅ Đã hoàn thành | ⏳ Chưa thực hiện (Yêu cầu chỉ làm BE) | ✅ Đã kết nối đầy đủ (`ORDER_VIEW`) | - **Vai trò**: Quản lý kinh doanh (`ROLE_SALES_MANAGER`, `ORDER_VIEW`).<br>- **Bộ lọc**: Lọc đa tiêu chí phía server theo trạng thái, đại lý, nhân viên kinh doanh, khu vực và khoảng thời gian tạo đơn.<br>- **Tổng tiền**: Tự động tính và trả về tổng tiền (`totalAmount`) của tập kết quả đang lọc kèm endpoint `/api/orders/summary`.<br>- **Phân quyền RLS**: Nhân viên kinh doanh (`ROLE_SALES`) chỉ thấy đơn của đại lý mình phụ trách; Quản lý kinh doanh & Admin xem toàn công ty.<br>- **Bảo mật**: Chặn truy cập chi tiết đơn của đại lý khác với `403 Forbidden` (`AccessDeniedException`). |
| **S4-09** | **Sao chép đơn cũ thành đơn mới (Order Copy to Draft / New Order)** | ✅ Đã hoàn thành | ✅ Đã hoàn thành | ✅ Đã kết nối đầy đủ (`ORDER_CREATE`) | - **Vai trò**: Nhân viên kinh doanh (`ROLE_SALES`, `ORDER_CREATE`).<br>- **Dòng hàng**: Sao chép toàn bộ dòng hàng sang đơn mới / draft.<br>- **Giá & Chiết khấu**: Tự động áp lại bảng giá hiện hành (`findEffectivePriceLists`) và chính sách chiết khấu sản lượng hiện hành (`calculateOrderDiscount`), không kế thừa giá cũ.<br>- **Trạng thái**: Luôn bắt đầu ở trạng thái Nháp (`DRAFT`).<br>- **Phân quyền Backend**: `@PreAuthorize("hasAuthority('ORDER_CREATE') or ...")` và RLS kiểm tra quyền phụ trách đại lý.<br>- **Phân quyền Frontend**: Kiểm tra `Auth.hasPermission('ORDER_CREATE')` trước khi hiển thị nút sao chép và thực hiện sao chép. |
| **S5-01** | **Theo dõi trạng thái đơn hàng của đại lý** | ✅ Có ở nhánh backend S5-01, chưa gộp vào `develop` | ✅ Đã nối API trên nhánh frontend hiện tại | ✅ Frontend giới hạn menu/route theo `ROLE_DEALER` + `ORDER_VIEW`; backend API giới hạn dữ liệu theo đại lý | Frontend chỉ dùng hợp đồng `GET /api/portal/orders` và `GET /api/portal/orders/{id}`. Không kéo code backend và không merge nhánh backend. |
| **S5-04** | **Lập phiếu nhập kho từ nhà cung cấp & Quản lý lô, hạn sử dụng (Supplier Goods Receipt with Lot & Expiry Management)** | ✅ Đã hoàn thành | ⏳ Chưa thực hiện (Yêu cầu chỉ làm BE) | ✅ Đã kết nối đầy đủ (`WAREHOUSE_IMPORT`, `WAREHOUSE_VIEW`, `WAREHOUSE_ADJUST`) | - **Vai trò**: Nhân viên kho (`ROLE_WAREHOUSE_STAFF`), Quản lý kho (`ROLE_WAREHOUSE_MANAGER`), Admin (`ROLE_ADMIN`).<br>- **Khai báo**: Nhà cung cấp, số chứng từ, ngày nhập, kho nhập.<br>- **Đơn vị tính & Quy đổi**: Nhập đơn vị bất kỳ, tự động quy về đơn vị cơ sở và đóng băng thông tin khi ghi sổ.<br>- **Quản lý Lô & HSD**: Mặt hàng có `manageLot = true` bắt buộc nhập số lô và hạn sử dụng.<br>- **Nháp & Xác nhận**: Phiếu nháp (`DRAFT`) không cộng tồn; xác nhận phiếu (`CONFIRMED`) mới cộng tồn tổng và cập nhật tồn theo lô (`warehouse_lot_inventories`).<br>- **Bảo mật**: Phiếu đã xác nhận không thể sửa/xóa; chỉ lập phiếu điều chỉnh (`POST /vouchers/{id}/adjust`, phân quyền cho Quản lý kho và Admin). |

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

## Chi tiết kết nối phân quyền S5-04 (Permission Matrix Details)

- **Mã quyền (Permission Codes)**:
  - `WAREHOUSE_IMPORT`: Lập phiếu nhập kho từ nhà cung cấp, lưu phiếu nháp, xác nhận phiếu nhập kho, sửa/xóa phiếu nháp.
  - `WAREHOUSE_ADJUST`: Điều chỉnh tồn kho, lập phiếu điều chỉnh cho phiếu kho đã xác nhận.
  - `WAREHOUSE_VIEW`: Xem danh sách phiếu kho, chi tiết phiếu kho, tồn kho tổng và tồn kho chi tiết theo lô.
- **Các vai trò được cấp quyền trong ma trận phân quyền**:
  - `ROLE_ADMIN`: Toàn quyền quản trị hệ thống, toàn bộ quyền kho.
  - `ROLE_WAREHOUSE_MANAGER`: Quản lý kho, toàn quyền quản lý kho bao gồm `WAREHOUSE_IMPORT`, `WAREHOUSE_VIEW`, `WAREHOUSE_ADJUST`. Được phép lập phiếu điều chỉnh cho các phiếu đã xác nhận.
  - `ROLE_WAREHOUSE_STAFF`: Nhân viên kho / Thủ kho. Được cấp `WAREHOUSE_IMPORT` và `WAREHOUSE_VIEW`. Được phép lập phiếu nhập kho, lưu nháp, xác nhận phiếu nhập khi xe dỡ hàng, sửa/xóa phiếu nháp. **Không có** quyền `WAREHOUSE_ADJUST` (bị chặn với `403 Forbidden` nếu cố tình điều chỉnh tồn kho hoặc điều chỉnh phiếu).
  - `ROLE_SALES`: Nhân viên kinh doanh. Bị chặn mọi quyền nhập kho và điều chỉnh kho (`403 Forbidden`).
  - `ROLE_SALES_MANAGER`, `ROLE_ACCOUNTANT`, `ROLE_VIEWER`: Có quyền `WAREHOUSE_VIEW` để xem tồn kho và chứng từ, không có quyền nhập kho hay điều chỉnh kho.
- **Backend**:
  - `WarehouseController.java`:
    - `POST /api/warehouse/import`: `@PreAuthorize("hasAuthority('WAREHOUSE_IMPORT') or hasAnyRole('WAREHOUSE_STAFF', 'WAREHOUSE_MANAGER', 'ADMIN')")`
    - `POST /api/warehouse/import/draft`: `@PreAuthorize("hasAuthority('WAREHOUSE_IMPORT') or hasAnyRole('WAREHOUSE_STAFF', 'WAREHOUSE_MANAGER', 'ADMIN')")`
    - `POST /api/warehouse/vouchers/{id}/confirm`: `@PreAuthorize("hasAuthority('WAREHOUSE_IMPORT') or hasAnyRole('WAREHOUSE_STAFF', 'WAREHOUSE_MANAGER', 'ADMIN')")`
    - `PUT /api/warehouse/vouchers/{id}`: `@PreAuthorize("hasAuthority('WAREHOUSE_IMPORT') or hasAnyRole('WAREHOUSE_STAFF', 'WAREHOUSE_MANAGER', 'ADMIN')")`
    - `DELETE /api/warehouse/vouchers/{id}`: `@PreAuthorize("hasAuthority('WAREHOUSE_IMPORT') or hasAnyRole('WAREHOUSE_STAFF', 'WAREHOUSE_MANAGER', 'ADMIN')")`
    - `POST /api/warehouse/vouchers/{id}/adjust`: `@PreAuthorize("hasAuthority('WAREHOUSE_ADJUST') or hasAnyRole('WAREHOUSE_MANAGER', 'ADMIN')")`
    - `POST /api/warehouse/adjust`: `@PreAuthorize("hasAuthority('WAREHOUSE_ADJUST') or hasAnyRole('WAREHOUSE_MANAGER', 'ADMIN')")`
    - `GET /api/warehouse/inventory/lots`: `@PreAuthorize("hasAuthority('WAREHOUSE_VIEW') or hasAnyRole('WAREHOUSE_STAFF', 'WAREHOUSE_MANAGER', 'SALES_MANAGER', 'ADMIN', 'VIEWER')")`
    - `GET /api/warehouse/vouchers`: `@PreAuthorize("hasAuthority('WAREHOUSE_VIEW') or hasAnyRole('WAREHOUSE_STAFF', 'WAREHOUSE_MANAGER', 'SALES_MANAGER', 'ADMIN', 'VIEWER')")`
    - `GET /api/warehouse/vouchers/{id}`: `@PreAuthorize("hasAuthority('WAREHOUSE_VIEW') or hasAnyRole('WAREHOUSE_STAFF', 'WAREHOUSE_MANAGER', 'SALES_MANAGER', 'ADMIN', 'VIEWER')")`
  - `SecurityConfig.java`:
    - Endpoint điều chỉnh kho được bảo vệ nghiêm ngặt: `.requestMatchers("/api/warehouse/adjust", "/api/warehouse/adjust/**", "/api/warehouse/vouchers/*/adjust").hasAnyRole("ADMIN", "WAREHOUSE_MANAGER")`
  - `WarehouseServiceImpl.java`:
    - Ràng buộc nhà cung cấp (`Supplier`): Kiểm tra tồn tại và cờ `active = true`.
    - Ràng buộc quản lý lô (`manageLot`): Kiểm tra bắt buộc `lotNumber` và `expiryDate` đối với các sản phẩm có `manageLot = true`; kiểm tra `expiryDate` không được trước `manufacturingDate`.
    - Phân tách vòng đời phiếu:
      - Trạng thái `DRAFT`: Lưu phiếu nháp, hoàn toàn không tác động đến tồn kho.
      - Trạng thái `CONFIRMED`: Khi xác nhận phiếu, hệ thống mới thực hiện cộng tồn kho tổng (`WarehouseInventory`) và cập nhật sổ tồn theo từng lô (`WarehouseLotInventory`).
    - Tính bất biến của phiếu đã xác nhận: Chặn sửa/xóa phiếu đã `CONFIRMED` / `RECORDED`, yêu cầu lập phiếu điều chỉnh `ADJUSTMENT`.
- **Frontend**:
  - `menu-config.js`: Route `/warehouse/units?tab=receipts` được cấu hình yêu cầu permission `WAREHOUSE_IMPORT` và các vai trò `ROLE_ADMIN`, `ROLE_WAREHOUSE_MANAGER`, `ROLE_WAREHOUSE_STAFF`.
  - Đã sẵn sàng kết nối API khi triển khai giao diện màn hình nhập kho cho Nhân viên kho.


