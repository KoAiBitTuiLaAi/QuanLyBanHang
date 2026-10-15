# Bảng Trạng Thái Triển Khai Tính Năng (Feature Status)

Theo quy định tại `AGENTS.md`, bảng dưới đây theo dõi tiến độ hoàn thành và trạng thái kết nối ma trận phân quyền (Permission Matrix) giữa Backend và Frontend:

| Story | Tính năng (Feature) | Backend Done | Frontend Done | Kết nối Ma trận Phân quyền (Permission Matrix) | Ghi chú (Notes) |
| :--- | :--- | :---: | :---: | :---: | :--- |
| **S4-04** | **Xem lịch sử mua hàng của đại lý & Thêm nhanh nhóm hàng mua lần trước vào đơn mới** | ✅ Đã hoàn thành | ⏳ Sẵn sàng kết nối (Yêu cầu chỉ làm BE; FE đã có sẵn panel gọi API) | ✅ Đã kết nối đầy đủ (`ORDER_CREATE`, `ORDER_VIEW`, `CUSTOMER_VIEW`) | - **Vai trò**: Nhân viên kinh doanh (`ROLE_SALES`, `ROLE_SALES_MANAGER`, `ROLE_ADMIN`).<br>- **Lịch sử 3 tháng & Số lượng bình quân**: Tổng hợp các đơn hàng đã ghi sổ (`RECORDED`) trong 3 tháng gần nhất (`findPurchasedOrdersInPeriod`), tính toán số lượng bình quân (`avgQty`) theo đơn vị mua gần nhất, tự động quy đổi đơn vị nếu có phát sinh khác đơn vị.<br>- **Thêm nhanh nhóm hàng mua lần trước**: Đánh dấu `inLastOrder = true` cho các mặt hàng trong đơn gần nhất và ưu tiên sắp xếp lên đầu danh sách; cung cấp endpoint `GET /last-order-items` và nạp thẳng vào bản nháp tạo đơn qua `POST /last-order-to-draft` (tự động áp lại giá/chiết khấu hiện hành).<br>- **Phân quyền Backend**: `@PreAuthorize("hasAuthority('ORDER_CREATE') or hasAuthority('ORDER_VIEW') or ...")` và RLS `validateSalesAccess` kiểm tra người phụ trách đại lý (`dealer.getAssignedUser()`).<br>- **Phân quyền Frontend**: `create-order.js` kiểm tra `hasPermission('ORDER_CREATE')` và gọi `GET /dealers/{dealerId}/purchase-history?months=3`. |
| **S4-09** | **Sao chép đơn cũ thành đơn mới (Order Copy to Draft / New Order)** | ✅ Đã hoàn thành | ✅ Đã hoàn thành | ✅ Đã kết nối đầy đủ (`ORDER_CREATE`) | - **Vai trò**: Nhân viên kinh doanh (`ROLE_SALES`, `ORDER_CREATE`).<br>- **Dòng hàng**: Sao chép toàn bộ dòng hàng sang đơn mới / draft.<br>- **Giá & Chiết khấu**: Tự động áp lại bảng giá hiện hành (`findEffectivePriceLists`) và chính sách chiết khấu sản lượng hiện hành (`calculateOrderDiscount`), không kế thừa giá cũ.<br>- **Trạng thái**: Luôn bắt đầu ở trạng thái Nháp (`DRAFT`).<br>- **Phân quyền Backend**: `@PreAuthorize("hasAuthority('ORDER_CREATE') or ...")` và RLS kiểm tra quyền phụ trách đại lý.<br>- **Phân quyền Frontend**: Kiểm tra `Auth.hasPermission('ORDER_CREATE')` trước khi hiển thị nút sao chép và thực hiện sao chép. |

---

## Chi tiết kết nối phân quyền S4-04 (Permission Matrix Details)

- **Mã quyền (Permission Code)**: `ORDER_CREATE`, `ORDER_VIEW`, `CUSTOMER_VIEW`
- **Các vai trò được cấp quyền**:
  - `ROLE_ADMIN`: Toàn quyền quản trị hệ thống (xem được toàn bộ đại lý).
  - `ROLE_SALES_MANAGER`: Quản lý kinh doanh (xem được toàn bộ đại lý).
  - `ROLE_SALES`: Nhân viên kinh doanh (chỉ xem và thao tác với đại lý do mình được phân công phụ trách).
  - `ROLE_WAREHOUSE_MANAGER`, `ROLE_WAREHOUSE_STAFF`: Hỗ trợ tạo đơn và kiểm tra lịch sử giao dịch.
- **Backend**:
  - `DealerController.java`:
    - `GET /api/dealers/{id}/purchase-history?months={n}&lastOrderOnly={true/false}`: `@PreAuthorize("hasAuthority('ORDER_CREATE') or hasAuthority('ORDER_VIEW') or hasAuthority('CUSTOMER_VIEW') or hasAnyRole('SALES', 'SALES_MANAGER', 'ADMIN', 'WAREHOUSE_MANAGER', 'WAREHOUSE_STAFF')")`
    - `GET /api/dealers/{id}/last-order-items`: `@PreAuthorize("hasAuthority('ORDER_CREATE') or hasAuthority('ORDER_VIEW') or hasAuthority('CUSTOMER_VIEW') or hasAnyRole('SALES', 'SALES_MANAGER', 'ADMIN', 'WAREHOUSE_MANAGER', 'WAREHOUSE_STAFF')")`
    - `POST /api/dealers/{id}/last-order-to-draft`: `@PreAuthorize("hasAuthority('ORDER_CREATE') or hasAnyRole('SALES', 'SALES_MANAGER', 'ADMIN', 'WAREHOUSE_MANAGER', 'WAREHOUSE_STAFF')")`
  - `OrderController.java`:
    - `POST /api/orders/dealer/{dealerId}/last-order-to-draft`: `@PreAuthorize("hasAuthority('ORDER_CREATE') or hasAnyRole('SALES', 'SALES_MANAGER', 'ADMIN', 'WAREHOUSE_MANAGER', 'WAREHOUSE_STAFF')")`
  - `DealerServiceImpl.java`: Row-Level Security (RLS) `validateSalesAccess` kiểm tra người phụ trách đại lý `dealer.getAssignedUser()` trùng khớp với người dùng hiện tại khi người dùng mang vai trò `ROLE_SALES`. Nếu đại lý chưa phân công hoặc do nhân viên khác phụ trách, server trả về mã lỗi `403 Forbidden`.
  - `OrderServiceImpl.java`: RLS `validateSalesAccessForDealer` kiểm tra quyền phụ trách đại lý khi sao chép nhóm hàng đơn gần nhất vào bản nháp tạo đơn của người dùng.
- **Frontend**:
  - `create-order.js`: Kiểm tra vai trò (`hasAnyRole('ROLE_SALES', 'ROLE_SALES_MANAGER', 'ROLE_ADMIN', ...)`) và quyền (`Auth.hasPermission('ORDER_CREATE')`). Khi người dùng chọn đại lý, tự động gọi API `GET /dealers/{dealerId}/purchase-history?months=3` để hiển thị danh sách các sản phẩm đại lý đã mua trong 3 tháng gần nhất kèm số lượng bình quân (`avgQty`), hỗ trợ thêm từng sản phẩm hoặc nhấn "Thêm tất cả vào đơn".

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

