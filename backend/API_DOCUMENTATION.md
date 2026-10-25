# SaleStock API — Sprint 1–7

Base URL: `http://localhost:8080/api`

Mọi response nghiệp vụ dùng cấu trúc `{ "success": boolean, "message": string, "data": any }`.

## S7-06 — Biên bản đối chiếu công nợ đã lưu và phản hồi đại lý (25/10/2026)

Trang `/finance/debt-reconciliation`, menu **Tài chính → Đối chiếu công nợ**, dùng cho kế toán và đại lý theo quyền. Tạo biên bản chụp số liệu đầu kỳ, tăng/giảm, cuối kỳ và từng chứng từ trong một transaction `REPEATABLE_READ`; toàn bộ bản số liệu và thông tin đại lý được lưu tại `debt_reconciliations.snapshot_json`. Không sửa/xóa bản đã lưu, không tính lại khi xem/PDF. Có thể lập một biên bản mới cho cùng kỳ để đối chiếu lại sau khi xử lý phản hồi.

| Method | Endpoint (sau `/api`) | Quyền / hành vi |
|---|---|---|
| GET | `/finance/debt-reconciliations/options?keyword=&page=0` | `FINANCE_VIEW_DEBT`; tìm đại lý trong phạm vi, 50 dòng/trang |
| POST | `/finance/debt-reconciliations` | `FINANCE_VIEW_DEBT` **và** `FINANCE_MANAGE`; Admin được phép; tài khoản có scope đại lý không được lập thay kế toán |
| GET | `/finance/debt-reconciliations?dealerId=&from=&to=&page=0` | Danh sách biên bản đã lưu, 20 dòng/trang; ngày lọc chọn các kỳ nằm trọn trong khoảng; bỏ cả `from`/`to` để xem mọi kỳ |
| GET | `/finance/debt-reconciliations/{id}` | Chi tiết bản số liệu và phản hồi đã lưu |
| GET | `/finance/debt-reconciliations/{id}/pdf` | PDF tiếng Việt, mã biên bản, kỳ, đầu kỳ/phát sinh/cuối kỳ, trạng thái và hai ô ký xác nhận; `Cache-Control: no-store` |
| GET | `/portal/debt-reconciliations/options` | Chỉ đại lý liên kết tài khoản; Admin có thể tra cứu |
| GET | `/portal/debt-reconciliations` và `/{id}`, `/{id}/pdf` | `FINANCE_VIEW_DEBT`, scope chính đại lý từ DB; không tin `dealerId` từ client |
| POST | `/portal/debt-reconciliations/{id}/response` | Chỉ tài khoản đại lý liên kết có `FINANCE_VIEW_DEBT`; kế toán/Admin không xác nhận thay |

Tạo biên bản:

```json
{"dealerId":10,"from":"2026-10-01","to":"2026-10-25","requestKey":"bed44420-bb8c-4a2b-af14-209d98b545de"}
```

`requestKey` là UUID do client cấp. Gửi lại cùng khóa, người lập, đại lý và kỳ trả biên bản cũ; tái dùng cho dữ liệu khác trả `409`. Unique constraint ngăn tạo trùng đồng thời; yêu cầu thua cuộc có thể nhận `409`, phải tải lại danh sách trước khi thao tác tiếp. Chỉ POST chủ động mới tạo bản ghi; mở trang/lọc/tải PDF không tự lập biên bản.

Xác nhận hoặc phản hồi:

```json
{"version":0,"status":"CONFIRMED"}
```

```json
{"version":0,"status":"DISPUTED","claimedBalance":"950000.25","reason":"Chưa ghi nhận phiếu thu PT-002"}
```

Chỉ cho chuyển `PENDING → CONFIRMED` hoặc `PENDING → DISPUTED` một lần. Bắt buộc version hiện tại; số dư phản hồi lệch phải khác cuối kỳ, tối đa 22 chữ số nguyên/2 chữ số lẻ; lý do sau trim không trống, tối đa 500 ký tự. Số chênh do server tính `cuối kỳ hệ thống - số dư đại lý`. Xác nhận khớp dùng số dư đã lưu. Pessimistic row lock + `@Version` bảo vệ phản hồi đồng thời; bản đã phản hồi/version cũ trả `409`. Lưu người/thời gian phản hồi và audit log trong cùng transaction, không tự sinh bút toán điều chỉnh công nợ.

Các trường tiền trả bằng **chuỗi thập phân** (`"950000.25"`), cả snapshot và chênh lệch. Trình duyệt dùng số nguyên BigInt theo xu để không mất độ chính xác. Frontend không tự ghi trạng thái xác nhận khi chưa có kết quả server; khi mất kết nối lúc POST, khóa ghi tiếp và yêu cầu tải lại danh sách. Thu hồi quyền làm ẩn/khóa màn hình và hủy/loại phản hồi trễ; JWT hiện tại nhận `403` ở request sau. Vai trò chỉ giới hạn phạm vi; SALES chỉ thấy đại lý phụ trách, DEALER chỉ đại lý liên kết, không có role fallback thay quyền DB.

Nguồn số liệu dùng cùng sổ công nợ S8-10: đơn ghi sổ theo `recorded_at/created_at`, phiếu thu theo `payment_date`, điều chỉnh hóa đơn có dấu theo `adjustment_date` (bao gồm chứng từ giảm nợ trả hàng); không cộng lặp hóa đơn và đơn hàng. Đây là số liệu từ chứng từ/trạng thái hiện có tại **thời điểm lập**, không phải cơ chế tái dựng lịch sử thay đổi đã bị xóa; giữ số dư âm. Phiếu phát sinh/backdate sau đó không sửa snapshot cũ: cần lập bản mới khi muốn cập nhật. Áp dụng `app.debt-statement.max-rows`; vượt giới hạn trả `400`, không lưu/PDF một phần. Phản hồi trên cổng là xác nhận ứng dụng, không phải chữ ký số. Chưa có luồng gửi email; đại lý truy cập biên bản qua cổng.


## S6-07 — Frontend theo dõi chuyến giao và sửa số liệu đọc (23/10/2026)

Menu **Kho hàng → Theo dõi chuyến giao**, `/warehouse/delivery-trips`. VIEW hoặc EXPORT theo ma trận DB; ADMIN luôn có quyền. Các API theo dõi dùng scope kho sẵn có, không dùng vai trò thay grant.

| Method | Endpoint sau `/api` | Hợp đồng đọc |
|---|---|---|
| GET | `/warehouse/delivery-trips/options` | Kho trong phạm vi, `tripStatuses`, `stopStatuses`, `canManage` hiện có. Giao diện này không bật thao tác ghi theo `canManage` |
| GET | `/warehouse/delivery-trips?warehouseId=1&status=IN_PROGRESS&isOverdue=true&keyword=CG&page=0&size=20&outstandingOnly=true` | Spring Page trong `data`; các filter là tùy chọn, page >=0, size 1–200, keyword <=150. `outstandingOnly` mặc định false; true dùng EXISTS điểm chưa DELIVERED và chưa xác nhận trả kho, áp được mọi trạng thái chuyến |
| GET | `/warehouse/delivery-trips/summary?warehouseId=1` | Chỉ lọc kho, không lọc keyword/status/isOverdue của danh sách. Trả số liệu các chuyến IN_PROGRESS cũ và 4 trường trách nhiệm toàn trạng thái mới bên dưới |
| GET | `/warehouse/delivery-trips/{id}` | Chi tiết chuyến, tiến độ/giá trị, `isOverdue/overdueMinutes/overdueWarning`, stops và items. Ngoài scope → 403; không thay đổi hàng/tồn/trạng thái |

Trường summary bổ sung (không đổi schema):

- `totalOutstandingValue`: tiền hàng chưa giao/chưa xác nhận trả kho trên **mọi trạng thái**; `totalOutstandingTrips`: số chuyến có điểm chưa xử lý xong (cả trường hợp giá trị 0).
- `closedOutstandingValue`, `closedOutstandingTrips`: phần còn thuộc chuyến COMPLETED/CANCELLED, để đổi trạng thái không làm biến mất trách nhiệm. Đây là số liệu đọc bổ sung, không thay đổi API hoàn tất/hủy.
- Các trường cũ `totalRunningTrips/totalValueOnRoad/totalDeliveredPoints/totalRemainingPoints/totalOverdueTrips/...` tiếp tục chỉ cho chuyến đang chạy. `remainingPoints/remainingValue` đã sửa để loại điểm `returnedToWarehouse=true`; FAILED chưa trả vẫn tính, không cộng tồn tự động. `failedPoints/failedValue` vẫn là lịch sử điểm giao thất bại, kể cả sau khi trả.
- Filter `isOverdue=false` bao gồm trường hợp thiếu thời gian dự kiến (phù hợp `isOverdue=false` trong DTO); không tự coi quá hạn chỉ vì đồng hồ trình duyệt khác server.

Frontend S6-07 chỉ gửi GET, không gọi các POST/PUT tạo chuyến/giao/hoàn/hủy/trả kho hiện có. Không sửa chúng trong nhánh này. Không seed/reset DB hoặc nâng schema để kiểm tra giao diện.

## S6-05 — Xác nhận xuất theo đơn, xuất thiếu và xuất bù (23/10/2026)

| Method | Endpoint (sau `/api`) | Hợp đồng |
|---|---|---|
| GET | `/warehouse/order-shipments/options` | Kho hoạt động trong phạm vi, `canConfirm`, `quantityScale`, chính sách giữ chỗ |
| GET | `/warehouse/order-shipments?queue=READY&warehouseId=1&keyword=DH&page=0&size=20` | `queue`: READY/BACKORDER/HISTORY; Spring Page trong `data`; size 1–100. BACKORDER là đơn đã xuất một phần còn lượng chưa xuất |
| GET | `/warehouse/order-shipments/{orderId}` | `version`, trạng thái, `backordered`, `canConfirm`, `blockedReason`; từng dòng: ordered/exported/remaining, đơn vị/hệ số đóng băng, lô còn hạn; receipts là các phiếu đã xác nhận, chỉ đọc |
| POST | `/warehouse/order-shipments/{orderId}/confirm` | Xác nhận ngay một phiếu bất biến. Body bên dưới; không có API sửa/xóa phiếu này |

```json
{
  "version": 2,
  "requestKey": "dbb77869-ff94-44bc-adb7-6c8df0cf98b5",
  "note": "Xuất lượt đầu",
  "items": [{"orderItemId": 10, "quantity": "4", "lotNumber": null, "reason": "Còn thiếu, xuất bù lượt sau"}]
}
```

- GET cần WAREHOUSE_VIEW hoặc WAREHOUSE_EXPORT; POST cần WAREHOUSE_EXPORT. Ma trận DB được nạp lại mỗi request, kể cả JWT cũ; ADMIN luôn được phép. Mọi tài khoản không phải ADMIN chỉ thao tác kho được gán rõ; không gán kho không được xem toàn cục. Không cần cấp thêm ORDER_VIEW để dùng màn kho.
- Gửi đủ các dòng của đơn, không trùng/ngoại đơn; quantity >=0, tối đa 11 số nguyên/4 số lẻ, không vượt phần chưa xuất, ít nhất một dòng >0. Mọi dòng xuất thiếu cần reason (<=255); lượng 0 cũng được lưu kèm lý do. Quy đổi cơ sở phải biểu diễn chính xác trong 4 số lẻ. Không nhận giá/hệ số/SL cơ sở do client sửa.
- Chỉ đơn đã duyệt/đang soạn còn giữ chỗ, chưa hết hạn hoặc EXPORTED còn nợ được xuất. Khóa order → order items → tồn vật lý theo SKU → lô; tính/trừ đúng lượng thực xuất và giữ chỗ trong một transaction cùng phiếu, lịch sử, audit. Tồn vật lý không được thấp hơn tổng giữ chỗ; lỗi bất kỳ hoàn tác toàn bộ. Mỗi lượt tăng `shipmentSequence` để phiên bản đơn tăng kể cả xuất một phần lặp lại.
- Lô mặc định theo FEFO còn hạn và đủ lượng; kiểm lại trên bản ghi khóa, trừ đúng lô. SKU tắt manageLot nhưng còn tồn lô vẫn xử lý theo lô. Lô thủ công khác gợi ý cần lý do; lô quá hạn/thiếu HSD/không đủ bị chặn. Không tự gộp lô hoặc đổi lô âm thầm.
- UUID + hash nội dung được lưu trên phiếu. Cùng key/nội dung trả lại kết quả hiện tại, không trừ lần hai; cùng key khác nội dung hoặc version cũ trả 409. Không tự retry bằng key mới khi mất kết quả; đọc lại đơn/phiếu để đối chiếu.
- Đơn xuất một phần vẫn EXPORTED, `backordered=true`, `inventoryReserved=true`; chỉ phần chưa xuất tiếp tục giữ chỗ, xóa hạn giữ chỗ để scheduler không bỏ quên nợ hàng sau lượt đầu. Xuất bù đủ → `backordered=false`, hết giữ chỗ. Không sửa `deliveredQuantity`, không tự ghi nhận doanh thu/giao hàng. Chặn hủy đơn đã xuất và chặn giao/đóng/gán chuyến toàn đơn khi còn nợ.
- PUT `/orders/{id}/status` sang EXPORTED dùng chung nghiệp vụ xuất đủ còn lại (giữ tương thích API cũ), không trừ tồn hai lần; UI mở phiếu để người dùng nhập lượng thực. Thẻ kho dùng các phiếu VOUCHER mới và bỏ phát sinh ORDER tương ứng, tránh đếm hai lần. Đơn lịch sử chưa có `exportedQuantity` vẫn suy lượng đã xuất từ trạng thái cũ, không tạo nợ hàng giả.
- Schema bổ sung nullable/backward-compatible: `orders.backordered`, `orders.shipment_sequence`, `order_items.exported_quantity`, `warehouse_vouchers.shipment_order_id/shipment_request_key` (unique)/`shipment_request_hash`, `warehouse_voucher_items.shipment_order_item_id`. Cơ chế `ddl-auto=update` hiện có thêm cột lúc khởi động; sao lưu DB trước triển khai, không xóa bảng/dữ liệu. Giữ phiếu xuất cũ; phiếu liên kết đơn không sửa/xóa/điều chỉnh qua API phiếu chung, xử lý trả hàng theo luồng riêng.

## S6-04 — Gợi ý FEFO và giao diện xuất theo lô (23/10/2026)

Giao diện **Kho hàng → Lô & hạn sử dụng → Gợi ý xuất FEFO**, `/warehouse/lots?tab=fefo`. Dùng các API hiện có, không đổi schema:

| Method | Endpoint (sau `/api`) | Hợp đồng hiện tại |
|---|---|---|
| GET | `/warehouse/lots/options` | Kho đang hoạt động trong phạm vi, SKU/đơn vị cơ sở, `canExport`; VIEW hoặc MANAGE, ADMIN toàn quyền |
| GET | `/warehouse/export/suggest-lot?warehouseId=1&productId=10&quantity=10&unit=lon` | VIEW hoặc EXPORT; lượng >0, tối đa 11 chữ số nguyên/4 số lẻ. Trả kho/SKU/lượng/đơn vị, `suggestedLot`, `availableLots`, `message`, `hasSufficientStock` |
| POST | `/warehouse/export/suggest-lots` | Quyền tương tự GET; danh sách yêu cầu gợi ý, không ghi tồn |
| POST | `/warehouse/export` | EXPORT; `{warehouseId,items:[{productId,quantity,unit,lotNumber,suggestedLotNumber?,overrideReason?}]}`. Server tự sinh mã phiếu, kiểm quyền/scope, đọc lại tồn/lô và ghi phiếu+tồn+audit cùng giao dịch |

- `suggestedLot` chỉ là **một lô còn hạn, có HSD, đủ lượng**, sắp HSD gần nhất, sau đó NSX/ID. HSD hôm nay vẫn còn hạn. Không lấy lô quá hạn/thiếu HSD làm mặc định, kể cả ADMIN. `hasSufficientStock` là có một lô hợp lệ đủ lượng, không phải tổng tồn chia nhiều lô. `availableLots` vẫn trả các lô có tồn để giải thích trường hợp bị chặn; UI không cho chọn quá hạn/thiếu HSD trong tab FEFO.
- SKU legacy tắt `manageLot` nhưng còn tồn theo lô vẫn được gợi ý theo lô để khớp quy tắc xuất S6-01. SKU không quản lý và không có tồn lô trả danh sách rỗng/thông báo; FEFO không thay luồng xuất hàng không quản lý lô.
- Server bỏ qua gợi ý do client giả mạo và tính lại dưới khóa khi xuất. Khác lô gợi ý hiện tại bắt buộc lý do; không tự đổi lô người dùng đã chọn khi tồn thay đổi. Không đủ tồn lô hoặc tồn khả dụng (thực tế trừ giữ chỗ) thì 400 và rollback toàn bộ. Lô quá hạn chỉ có thể xuất qua cơ chế ghi đè S6-01 có quyền, xác nhận và lý do; không bỏ kiểm tra này.
- Xuất lấy khóa tồn vật lý trước, rồi khóa các lô cùng kho/SKU theo ID bằng current read để hai yêu cầu đồng thời không dùng chung số dư lô cũ. Chỉ lô đã chọn bị trừ; không giải phóng giữ chỗ của đơn khác. Thu hồi quyền trả 403 trên JWT hiện tại, không cần đăng nhập lại.

## S6-03 — Phiếu soạn hàng, frontend + backend (23/10/2026)

Trang `/warehouse/picking`, menu **Kho hàng → Phiếu soạn hàng**. Quyền đọc: `WAREHOUSE_VIEW` **hoặc** `WAREHOUSE_EXPORT`; quyền tạo/lưu dòng/hoàn tất/hủy: `WAREHOUSE_EXPORT`. `ROLE_ADMIN` luôn được phép; người dùng khác chỉ thao tác kho được gán, kể cả khi role không phải Thủ kho. Không có kho được gán không đồng nghĩa truy cập toàn bộ kho. Thu hồi quyền có hiệu lực với JWT hiện tại ở request tiếp theo; UI đóng/xóa form và vô hiệu phản hồi trễ khi nhận thay đổi quyền.

| Method | Endpoint (sau `/api`) | Hợp đồng |
|---|---|---|
| GET | `/warehouse/picking-batches/options` | Kho đang dùng trong phạm vi, nhãn trạng thái, `canCreate`, `maxOrders=100`, `maxLines=500`, `quantityScale=4`, mô tả tuyến |
| GET | `/warehouse/picking-batches/eligible-orders?warehouseId=1&page=0&size=20&keyword=DH` | Chỉ đơn cùng kho, đã duyệt/đang soạn (`RECORDED/APPROVED/PICKING/PROCESSING`), còn giữ chỗ, chưa hết hạn và chưa có lượt soạn chưa hủy. Trả `Page<EligibleOrder>` không có giá/công nợ |
| GET | `/warehouse/picking-batches?warehouseId=1&status=IN_PROGRESS&keyword=PSH&page=0&size=20` | `Page<Summary>`, lọc trong phạm vi kho; `size` 1–100, `page` không âm |
| GET | `/warehouse/picking-batches/{id}` | `Detail{summary,note,cancelReason,completedAt,items,canEdit,canComplete,canCancel,blockedReason}` |
| POST | `/warehouse/picking-batches` | `{warehouseId,orderIds:[101,102],code?:"PSH-001",note?:"..."}`; mã trống tự sinh; 1–100 đơn không trùng, cùng kho, tối đa 500 dòng |
| PUT | `/warehouse/picking-batches/{id}/items/{itemId}` | `{version:0,pickedQuantity:1.5,checked:true,shortageReason:"Thiếu tại kệ"}`. `version` là phiên bản phiếu đang xem; số lượng theo đơn vị đóng băng của dòng đơn gốc |
| POST | `/warehouse/picking-batches/{id}/complete` | `{version:1}`; mọi dòng đã lưu/đánh dấu, số thực soạn hợp lệ; hoàn tất rồi không sửa/hủy |
| POST | `/warehouse/picking-batches/{id}/cancel` | `{version:1,reason:"Đổi tuyến soạn"}`; chỉ lượt đang soạn, bắt buộc lý do; giải phóng quyền chọn các đơn cho lượt mới, không đổi tồn |

`Summary` gồm `id,version,code,warehouseId,warehouseCode,warehouseName,status,statusLabel,orderCount,lineCount,checkedCount,shortageCount,createdAt,createdBy`. Mỗi dòng trả `id,orderId,orderCode,productId,productCode,productName,unit,baseUnit,quantity,conversionFactor,pickedQuantity,pickedBaseQuantity,checked,shortageQuantity,shortageReason,locationId,locationCode,locationDisplay,routePosition`. Không tự mặc định số đặt thành thực soạn; chưa nhập trả null, số 0 được lưu nguyên.

Quy tắc dữ liệu:

- Tạo mới chốt số lượng/đơn vị/hệ số từ đơn và vị trí mặc định đang dùng trong kho. Dòng thuộc nhiều đơn được giữ liên kết riêng, nhưng sắp liền theo tuyến khu/kệ. Cột `pickingSequence` (0–1.000.000 hoặc null) được bổ sung vào API tạo/sửa/đọc vị trí hiện có; cấu hình ở **Kho & vị trí lưu → Thêm/Sửa vị trí → Thứ tự đường đi khi soạn**. Số nhỏ trong cùng khu đi trước, chưa khai báo thì so mã tự nhiên (`KE-2` trước `KE-10`), chưa có vị trí hợp lệ xếp cuối và hiển thị cảnh báo. Đây là thứ tự tuyến được khai báo, không tuyên bố đường ngắn nhất theo tọa độ.
- Sau khi tạo, thay đổi tên/thứ tự/đơn vị trong danh mục không sửa lại snapshot phiếu cũ. Đơn đã duyệt chuyển sang `PICKING` trong cùng giao dịch; không trừ tồn thực hoặc nhả giữ chỗ.
- Thực soạn từ 0 đến số yêu cầu, tối đa 11 chữ số nguyên và 4 số lẻ; quy đổi cơ sở cũng phải hợp lệ. Có ghi thực soạn thiếu thì bắt buộc lý do, kể cả 0 và dòng chưa đánh dấu. Đánh dấu đã soạn bắt buộc nhập thực soạn. API kiểm tra lại trạng thái/giữ chỗ/hạn của đơn và trạng thái kho mỗi lần lưu/hoàn tất; vẫn cho hủy lượt bị chặn để đối soát.
- Khóa đơn theo ID tăng dần, claim DB duy nhất cho mỗi đơn chống tạo trùng đồng thời. Claim của phiếu hoàn tất được giữ để không soạn lại cùng đơn; hủy lượt giải phóng claim nhưng giữ các dòng lịch sử. Parent `@Version` + khóa phiếu ngăn ghi đè, phiên bản cũ trả **409**; frontend giữ nguyên dữ liệu nhập để người dùng đối chiếu/tải lại. Mọi lỗi giao dịch, kể cả audit, rollback cả dữ liệu/trạng thái/claim.
- Phiếu soạn **không phải** phiếu xuất/giao hàng; không tự cập nhật `deliveredQuantity` hoặc nhập trả phần thiếu. Việc xuất/giao vẫn theo luồng chứng từ riêng. Không lưu nghiệp vụ vào localStorage/sessionStorage.

Schema mới do cấu hình Hibernate `ddl-auto=update` hiện có tạo khi triển khai: `picking_batches`, `picking_batch_items`, `picking_order_claims`; thêm `warehouse_locations.picking_sequence`. Kiểm thử chạy trên H2 tạm và Chrome HTTP fixtures, không migration/reset/ghi dữ liệu DB thật.

## S5 — bản sửa tích hợp 22/10/2026

Hợp đồng dưới đây ưu tiên hơn các ví dụ/role mặc định lịch sử ở cuối tài liệu.

| API | Quyền và ràng buộc mới được kiểm thử |
|---|---|
| `POST /warehouse/export` | `WAREHOUSE_EXPORT` hoặc ADMIN; chỉ xuất tối đa tồn khả dụng (= tồn thực − giữ chỗ), kiểm tra dưới khóa dòng tồn. Không được dùng hàng giữ chỗ của đơn khác, kể cả ADMIN. |
| `POST /warehouse/adjust` | `WAREHOUSE_ADJUST` hoặc ADMIN; kiểm tra kho được phân công cho người không phải ADMIN. Tồn sau điều chỉnh không âm và không dưới giữ chỗ. Một dòng lỗi hoàn lại cả giao dịch. |
| `PUT /orders/{id}/delivery` | `WAREHOUSE_EXPORT` hoặc ADMIN, đồng thời kiểm tra phạm vi kho. Chỉ từ đã xuất sang đã giao (hoặc cập nhật lại khi đã giao). ID dòng phải thuộc đơn, không lặp; số thực giao 0..số đặt, tối đa 4 số lẻ; ghi chú tối đa 500 ký tự. Dòng không gửi giữ nguyên theo hợp đồng cập nhật từng phần. |
| `GET /orders/{id}` | `OrderResponse.canUpdateDelivery` là khả năng chỉnh thực giao theo quyền, kho và vòng đời hiện tại; không thay thế kiểm tra quyền khi ghi. Trả thực giao/ngày giao/ghi chú/thiếu từ DB. |
| `GET /portal/orders/{id}/reorder-preview` | ADMIN hoặc đại lý có `ORDER_VIEW` + `ORDER_CREATE`, giới hạn đơn thuộc đại lý. Frontend dùng `sourceOrderId`, `sourceOrderCode`, `defaultExpectedDeliveryDate`, `availableItems[].sourceItemId/currentPrice`, `discontinuedItems`, không yêu cầu thêm `PRODUCT_VIEW`. |
| `POST /portal/orders/{id}/reorder` | Dùng API đặt lại chuyên biệt, không gọi tạo đơn chung. Gửi `deliveryPointId`, `expectedDeliveryDate`, `note`, `items[{productId,productCode,unit,quantity,note}]`; không gửi giá. Backend kiểm tra lại mặt hàng/bảng giá khi ghi và trả `data: {order, excludedItems, warnings}`. |

Frontend đóng/xóa form và vô hiệu phản hồi đang chờ khi quyền thay đổi; chống bấm kép. Thu hồi quyền có hiệu lực trên API với JWT hiện tại. Form thực giao gửi toàn bộ các dòng được nhập; không tự coi số đặt là số đã giao. Ghi nhận thực giao không tự nhập trả phần giao thiếu; việc trả kho vẫn dùng chứng từ riêng.

## Ma trận quyền — cập nhật 20/10/2026

**Quyền DB, không phải role mặc định, quyết định truy cập nghiệp vụ.** Các bảng role cũ bên dưới chỉ mô tả cấu hình mặc định; hợp đồng quyền ở mục này được ưu tiên. ROLE_ADMIN luôn được phép. Vai trò vẫn có thể giới hạn phạm vi đơn/kho/đại lý; không bỏ kiểm tra phạm vi khi cấp thêm quyền.

| Method / endpoint | Quyền / hợp đồng |
|---|---|
| GET `/auth/permissions` | Đã đăng nhập; trả `{roles: [...], permissions: [...]}` từ Authentication hiện tại, không tin danh sách quyền do client gửi |
| GET `/admin/permissions` | ADMIN; giữ nguyên catalog quyền và matrix |
| PUT `/admin/permissions` | ADMIN; body là object `{ "ROLE_SALES": ["ORDER_VIEW", "ORDER_CREATE"], "ROLE_VIEWER": [] }`; chỉ thay vai trò có trong body, lưu toàn bộ thay đổi trong một transaction; trả matrix/catalog mới. Body rỗng không hợp lệ |
| PUT `/admin/permissions/{role}` | ADMIN; array quyền, vẫn tương thích API cũ; `[]` là thu hồi hết quyền và được lưu qua restart |
| GET `/products/lookup`, `/products/lookup/{id}` | Một trong PRODUCT_VIEW/CREATE/EDIT, ORDER_CREATE, WAREHOUSE_VIEW/MANAGE/IMPORT/EXPORT/APPROVE/ADJUST; danh mục tham chiếu tối thiểu, gồm `manageLot`, không trả giá vốn hoặc doanh thu; list hỗ trợ `search`, `active` |
| GET `/categories/options` | PRODUCT_VIEW/CREATE/EDIT; chỉ id/code/name cho bộ chọn, không trả doanh thu |
| Sản phẩm / nhóm hàng | GET chi tiết/danh sách: PRODUCT_VIEW; POST: PRODUCT_CREATE; PUT/PATCH/chuyển nhóm: PRODUCT_EDIT; DELETE: PRODUCT_DELETE. Lịch sử giá: FINANCE_VIEW_COST_PRICE |
| Nhập sản phẩm Excel | Preview/import cần cả PRODUCT_CREATE + PRODUCT_EDIT vì file có thể chứa SKU tạo mới và cập nhật; template cần một trong hai |
| Bảng giá / chiết khấu | Đọc: một trong PRODUCT_VIEW/PRODUCT_EDIT/ORDER_CREATE; ghi: PRODUCT_EDIT; lấy bảng hiệu lực/tính chiết khấu để đặt đơn: ORDER_CREATE |
| Nhà cung cấp / đơn vị quy đổi | Nhà cung cấp đọc: WAREHOUSE_VIEW/MANAGE, ghi: WAREHOUSE_MANAGE. Đơn vị đọc hỗ trợ quyền tham chiếu của sản phẩm/đặt đơn/kho; ghi: WAREHOUSE_MANAGE |
| Kho | Nhập: WAREHOUSE_IMPORT; xuất: WAREHOUSE_EXPORT; xác nhận phiếu: WAREHOUSE_APPROVE; điều chỉnh/chốt kiểm kê: WAREHOUSE_ADJUST; xem/lập kiểm kê: WAREHOUSE_VIEW; chuyển: WAREHOUSE_TRANSFER; danh mục kho/vị trí ghi: WAREHOUSE_MANAGE |
| Vòng đời đơn / giao hàng | WAREHOUSE_EXPORT; chỉ WAREHOUSE_IMPORT không cho phép xuất/chuyển vòng đời đơn. Giữ các kiểm tra phạm vi kho, thứ tự trạng thái và điều kiện hủy |
| Sao chép đơn / đơn gần nhất | Cả ORDER_CREATE + ORDER_VIEW, áp dụng cho `/orders/{id}/copy`, `/copy-to-draft`, `/orders/dealer/{dealerId}/last-order-to-draft` và đường tương ứng dưới `/dealers` |
| Giá vốn / lợi nhuận / công nợ | FINANCE_VIEW_COST_PRICE / FINANCE_VIEW_PROFIT_MARGIN / FINANCE_VIEW_DEBT; thay đổi hạn mức hoặc ngày nợ cần FINANCE_MANAGE, kể cả gửi trường này qua POST/PUT hồ sơ đại lý |
| Tài khoản | Đọc USER_VIEW; tạo/import USER_CREATE + USER_ASSIGN_ROLE; sửa USER_EDIT, đổi vai trò còn cần USER_ASSIGN_ROLE; khóa/mở USER_LOCK. Chỉ quản lý ma trận và audit log yêu cầu riêng ADMIN |

Sau khi lưu thành công, request tiếp theo của token đang đăng nhập dùng quyền mới; không phải đăng nhập lại. Frontend đồng bộ qua `/auth/permissions` khi mở trang, focus và mỗi 15 giây khi trang hoạt động; browser có thể trì hoãn timer của tab nền. API vẫn chặn ngay ở server kể cả giao diện chưa kịp cập nhật. Người dùng nhiều vai trò nhận hợp quyền của mọi vai trò được gán.

Tài khoản chỉ có ROLE_DEALER dùng API `/portal/**` và hồ sơ cá nhân, không truy cập API nghiệp vụ nội bộ. Menu tuân theo cùng phạm vi; tài khoản có thêm vai trò nội bộ vẫn phải có permission và qua kiểm tra phạm vi tương ứng.

## Hợp đồng đã đồng bộ ngày 19/10/2026

Phần này cập nhật hợp đồng của bản ghép sprint 1–5. Các giới hạn nghiệm thu được ghi ở
[`status.md`](../status.md) và [báo cáo rà soát](../docs/SPRINT_1_5_AUDIT_20261018.md).

### Cổng đại lý và tài khoản liên kết

Tài khoản `ROLE_DEALER` phải có `dealerId` gắn với hồ sơ đại lý. Quản trị viên chọn
đại lý ở form tài khoản; `GET /admin/assignment-options` trả thêm `dealers` gồm
`id`, `code`, `name`. `POST/PUT /admin/users` nhận `dealerId`; thiếu liên kết khi
có vai trò đại lý trả 400. Thông tin tài khoản trả lại có `dealerId/dealerCode/dealerName`.

| Method | Endpoint | Quyền |
|---|---|---|
| GET | `/portal/dealers` | Chỉ ADMIN; danh sách lựa chọn `{id, code, name}` từ DB, gồm cả đại lý ngừng hoạt động để tra đơn cũ |
| GET | `/portal/profile` | DEALER + ORDER_VIEW, hoặc ADMIN chọn đại lý |
| GET | `/portal/catalog` (alias `/portal/products`) | DEALER + PRODUCT_VIEW, hoặc ADMIN chọn đại lý |
| GET | `/portal/debt` | DEALER + FINANCE_VIEW_DEBT, hoặc ADMIN chọn đại lý |
| GET | `/portal/delivery-points` | DEALER + ORDER_VIEW, hoặc ADMIN chọn đại lý |
| GET | `/portal/orders` | DEALER + ORDER_VIEW, hoặc ADMIN; danh sách giới hạn theo đại lý trong ngữ cảnh |
| GET | `/portal/orders/{id}` | DEALER + ORDER_VIEW, hoặc ADMIN; đơn ngoài đại lý trong ngữ cảnh trả 403 |
| POST | `/portal/orders` | DEALER + ORDER_CREATE, hoặc ADMIN; server xác định đại lý và giá |
| GET | `/portal/orders/{id}/reorder-preview` | DEALER + ORDER_VIEW + ORDER_CREATE, hoặc ADMIN; xem trước đặt lại đơn cũ (S5-02) |
| POST | `/portal/orders/{id}/reorder` | DEALER + ORDER_CREATE, hoặc ADMIN; đặt lại đơn cũ (toàn bộ hoặc một phần) (S5-02) |

ADMIN không cần liên kết với hồ sơ đại lý. Frontend gọi `GET /portal/dealers`, yêu cầu
chọn đại lý rồi gửi query `dealerId` cho mọi API dữ liệu cổng (kể cả POST/chi tiết/đặt lại).
Ví dụ: `GET /portal/profile?dealerId=10`, `GET /portal/orders?dealerId=10`,
`POST /portal/orders?dealerId=10`, `GET /portal/orders/12/reorder-preview?dealerId=10`,
`POST /portal/orders/12/reorder?dealerId=10`. ADMIN không chọn và không có liên kết trả 400 với
thông báo chọn đại lý; ID không tồn tại trả 400. Liên kết sẵn của ADMIN vẫn được hỗ trợ
khi không có query. DEALER luôn dùng liên kết của tài khoản; query chỉ được trùng ID đó,
chỉ định đại lý khác trả 403 cho cả đọc và tạo đơn. `dealerId` trong body không chọn
được đại lý. Bộ chọn FE giữ ngữ cảnh trong URL, không lưu vào bộ nhớ trình duyệt hay
thay đổi liên kết tài khoản. Tài khoản DEALER bị chặn API nội bộ như `/products`,
`/orders`, `/dealers`, `/warehouse/**`; sử dụng hợp đồng cổng để đọc dữ liệu của mình.
Xác thực, hồ sơ cá nhân và ảnh sản phẩm có xác thực vẫn sử dụng API tương ứng.

`catalog` trả `id/sku/name/categoryId/categoryName/baseUnit/basePrice/units`;
mỗi đơn vị có `unitName/factor/price`. Giá đơn vị = giá nhóm hiệu lực × hệ số,
không lấy giá quy cách chung và không trả giá vốn. Đại lý chưa có nhóm trả 400;
không tự coi là nhóm cấp 1.

Body đặt hàng thông thường (`POST /portal/orders`):

```json
{
  "deliveryPointId": 1,
  "expectedDeliveryDate": "2026-10-20",
  "note": "Giao buổi sáng",
  "items": [{ "productId": 10, "unit": "thùng", "quantity": 2 }]
}
```

### Đặt lại đơn đã mua trên Cổng đại lý (S5-02)

Đại lý có thể xem trước và đặt lại một đơn đã mua trước đây:

#### 1. Xem trước đặt lại đơn: `GET /portal/orders/{id}/reorder-preview`
- **Quyền yêu cầu:** `ROLE_ADMIN` hoặc (`ROLE_DEALER` + `ORDER_VIEW` + `ORDER_CREATE`).
- **Query param:** `dealerId` (hỗ trợ cho ADMIN chọn đại lý).
- **Quy tắc nghiệp vụ:**
  - Kiểm tra quyền truy cập đơn gốc theo phạm vi đại lý (đơn thuộc đại lý khác trả 403).
  - Tách các dòng hàng thành hai nhóm:
    - `availableItems`: Mặt hàng còn kinh doanh (`active = true`) và có trong bảng giá hiệu lực hiện hành của nhóm đại lý. Giá được tính lại theo đơn giá hiện hành (kèm danh sách đơn vị quy đổi có sẵn).
    - `discontinuedItems`: Mặt hàng đã ngừng kinh doanh (`active = false`) hoặc không còn trong bảng giá hiệu lực hiện hành, kèm lý do (`reason`).
  - Cung cấp danh sách `warnings`, cờ `canReorder` (`true` nếu có ít nhất 1 mặt hàng khả dụng), thông tin công nợ hiện tại (`currentDebt`, `debtLimit`, `remainingCredit`).
- **Response mẫu:**
```json
{
  "success": true,
  "message": "Xem trước đặt lại đơn hàng thành công",
  "data": {
    "originalOrderId": 101,
    "originalOrderCode": "ORD-20261015-001",
    "dealerId": 5,
    "dealerCode": "DL005",
    "dealerName": "Đại lý Ánh Dương",
    "canReorder": true,
    "totalAmount": 1500000,
    "currentDebt": 5000000,
    "debtLimit": 20000000,
    "remainingCredit": 15000000,
    "warnings": [
      "Mặt hàng 'Sản phẩm Cũ (SKU-OLD)' đã ngừng kinh doanh và sẽ được loại khỏi đơn đặt lại."
    ],
    "availableItems": [
      {
        "lineItemId": 1,
        "productId": 10,
        "productCode": "SKU-010",
        "productName": "Sữa tươi Ba Vì 1L",
        "unit": "thùng",
        "factor": 12,
        "originalUnitPrice": 240000,
        "currentUnitPrice": 250000,
        "priceChanged": true,
        "originalQuantity": 5,
        "recommendedQuantity": 5,
        "subtotal": 1250000,
        "availableUnits": [
          { "unitName": "hộp", "factor": 1, "price": 20833 },
          { "unitName": "thùng", "factor": 12, "price": 250000 }
        ]
      }
    ],
    "discontinuedItems": [
      {
        "productId": 99,
        "productCode": "SKU-OLD",
        "productName": "Sản phẩm Cũ",
        "unit": "chai",
        "quantity": 10,
        "originalUnitPrice": 15000,
        "reason": "Sản phẩm đã ngừng kinh doanh"
      }
    ]
  }
}
```

#### 2. Thực hiện đặt lại đơn: `POST /portal/orders/{id}/reorder`
- **Quyền yêu cầu:** `ROLE_ADMIN` hoặc (`ROLE_DEALER` + `ORDER_CREATE`).
- **Query param:** `dealerId` (hỗ trợ cho ADMIN chọn đại lý).
- **Request body (`DealerPortalReorderRequest`):**
  - `items`: Danh sách dòng hàng chọn đặt lại (`productId`, `unit`, `quantity`). Nếu `items` là `null` hoặc rỗng `[]`, hệ thống tự động đặt lại **toàn bộ** các dòng hàng còn khả dụng từ đơn gốc theo số lượng/đơn vị ban đầu.
  - `deliveryPointId`: Điểm giao hàng (tùy chọn, mặc định lấy theo điểm giao hàng của đơn gốc nếu hợp lệ).
  - `expectedDeliveryDate`: Ngày giao dự kiến (tùy chọn).
  - `note`: Ghi chú đơn mới (tùy chọn).
```json
{
  "deliveryPointId": 1,
  "expectedDeliveryDate": "2026-10-25",
  "note": "Đặt lại định kỳ",
  "items": [
    { "productId": 10, "unit": "thùng", "quantity": 5 }
  ]
}
```
- **Quy tắc tạo đơn:**
  - Loại bỏ hoàn toàn các mặt hàng đã ngừng kinh doanh hoặc không còn trong bảng giá hiệu lực hiện hành.
  - Nếu tất cả các mặt hàng đều bị ngừng kinh doanh / không hợp lệ: trả lỗi 400 kèm thông báo rõ ràng.
  - Áp dụng đơn giá theo bảng giá / chính sách hiện hành (không kế thừa đơn giá cũ).
  - Đơn mới luôn được tạo ở trạng thái Chờ duyệt (`PENDING_APPROVAL`).
  - Ghi nhận Audit Log hành động `REORDER_FROM` kèm mã đơn gốc.
- **Response mẫu (`DealerPortalReorderResponse`):**
```json
{
  "success": true,
  "message": "Đặt lại đơn hàng thành công",
  "data": {
    "order": {
      "id": 205,
      "code": "ORD-20261021-002",
      "status": "PENDING_APPROVAL",
      "totalAmount": 1250000,
      "orderDate": "2026-10-21"
    },
    "excludedItems": [
      {
        "productId": 99,
        "productCode": "SKU-OLD",
        "productName": "Sản phẩm Cũ",
        "unit": "chai",
        "quantity": 10,
        "originalUnitPrice": 15000,
        "reason": "Sản phẩm đã ngừng kinh doanh"
      }
    ],
    "warnings": [
      "Có 1 mặt hàng đã ngừng kinh doanh hoặc hết hiệu lực giá được loại khỏi đơn mới: SKU-OLD"
    ]
  }
}
```

### Danh sách, duyệt đơn và lịch sử

`GET /orders` nhận `status`, `dealerId`, `salesUserId` (alias `assignedUserId`,
`employeeId`, `userId`), `territoryId`, `createdBy`, `keyword/search`,
`approvalRequired`, `approvalReason`, `startDate/endDate` (alias `fromDate/toDate`,
`dateFrom/dateTo`). `page` bắt đầu 0, `size` từ 1–100. Không gửi phân trang thì giữ
list đầy đủ tương thích các trang hiện có.

Response trả `data` và alias `content` là danh sách, `totalAmount/totalOrders/totalElements`
của toàn tập lọc; có `totalPages` khi yêu cầu phân trang. Tổng tiền được tính trước
khi cắt trang. API không được đổi sang một hợp đồng phân trang khác chỉ để phục vụ
một trong hai màn hình.

- `PUT /orders/{id}/status`: vòng đời kho; `PATCH /orders/{id}/status`: duyệt đơn
  `APPROVED/REJECTED/RETURNED`. Request hỗ trợ `status`, `note`, `comment`, `reason`.
- Các thao tác duyệt/trả lại của màn hình S4-05 yêu cầu `ORDER_APPROVE` trong
  ma trận quyền; `ROLE_ADMIN` luôn được phép. Nhân viên kinh doanh chỉ dùng
  `ORDER_CREATE` để xác nhận đơn do đại lý tự đặt, đúng đại lý được phân công
  và không có vi phạm cần Quản lý kinh doanh xử lý.
- Từ chối/trả lại phải có ý kiến; hủy phải có lý do. `PUT /orders/{id}/approve`
  nhận tùy chọn `reservationHours`. Hạn giữ chỗ mặc định hiện là 48 giờ khi không
  có thời điểm yêu cầu/ngày giao tương lai.
- `GET /dealers/{id}/purchase-history?months=3` trả mảng `productId/sku/code/name/unit/avgQty`,
  `lastOrderQuantity/lastOrderUnit/inLastOrder/lastOrderCode`. Không tổng hợp các đơn
  `DRAFT/PENDING_APPROVAL/REJECTED/RETURNED/CANCELLED/EXPIRED`.
- `GET /orders/{id}/pdf` trả `application/pdf`, tùy chọn `download=true`;
  `/orders/{id}/barcode` trả PNG. FE gọi PDF bằng JWT rồi hiển thị blob của đơn đã lưu.
  Tải PDF lỗi không có nghĩa tạo đơn lỗi và không được gửi lại POST tạo đơn.

### Dữ liệu nhạy cảm

`GET /finance/cost-price` và `/finance/profit-margin` chỉ cho `ROLE_SALES_MANAGER`.
Tài khoản chỉ có ADMIN bị 403 theo yêu cầu backlog. Các vấn đề ma trận/phạm vi còn
lại của API nội bộ chưa được xem là nghiệm thu xong; xem bảng trạng thái.

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
| GET | `/warehouse/options` | `ORDER_CREATE` hoặc `WAREHOUSE_VIEW` từ ma trận phân quyền (`ROLE_ADMIN` luôn được phép) | Danh sách kho đang hoạt động dùng cho bộ chọn frontend (S4-03) |
| GET | `/warehouse/inventory` | `ORDER_CREATE` hoặc `WAREHOUSE_VIEW` từ ma trận phân quyền (`ROLE_ADMIN` luôn được phép) | Xem danh sách tồn kho (gồm tồn thực tế `baseQuantity`, tồn giữ chỗ `reservedQuantity` và tồn khả dụng `availableQuantity`) (S4-03) |
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
| GET | `/finance/debt` | `FINANCE_VIEW_DEBT` | Xem hạn mức tiền và số ngày nợ tối đa từ hồ sơ đại lý; số dư công nợ hiện chưa có nguồn dữ liệu |
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
| GET | `/warehouse/import/options` | Một trong `WAREHOUSE_IMPORT`, `WAREHOUSE_APPROVE`, `WAREHOUSE_ADJUST`; hoặc ADMIN | `{warehouses, suppliers}`: kho trong phạm vi, NCC hoạt động, chỉ id/code/name; không đòi quyền quản lý NCC |
| GET | `/warehouse/imports?warehouseId=1` | Như options | Phiếu nhập và phiếu điều chỉnh có tham chiếu trong phạm vi kho; dùng cho sổ phiếu khi không có WAREHOUSE_VIEW |
| POST | `/warehouse/import` | `WAREHOUSE_IMPORT`; ghi sổ ngay còn cần `WAREHOUSE_APPROVE`; hoặc ADMIN | Tương thích nhập trực tiếp; `draft=true`/`status=DRAFT` không cộng tồn |
| POST | `/warehouse/import/draft` | `WAREHOUSE_IMPORT` hoặc ADMIN | Lưu nháp DB, không thay tồn tổng/lô |
| POST | `/warehouse/vouchers/{id}/confirm` | `WAREHOUSE_APPROVE` hoặc ADMIN | Khóa phiếu/kho/tồn, chốt quy đổi hiện hành, cộng tồn đúng một lần |
| PUT | `/warehouse/vouchers/{id}` | `WAREHOUSE_IMPORT` hoặc ADMIN | Chỉ sửa phiếu nhập DRAFT, gửi `version` từ lần đọc gần nhất để chặn ghi đè phiên khác |
| DELETE | `/warehouse/vouchers/{id}` | `WAREHOUSE_IMPORT` hoặc ADMIN | Chỉ xóa phiếu nhập DRAFT |
| POST | `/warehouse/vouchers/{id}/adjust` | `WAREHOUSE_ADJUST` hoặc ADMIN | Cùng kho phiếu gốc; bắt buộc lý do; không âm tồn/lô hoặc giảm dưới tồn giữ chỗ; giữ nguyên dòng phiếu gốc |
| POST | `/warehouse/export` | `WAREHOUSE_EXPORT` hoặc `ROLE_ADMIN` | Lập phiếu xuất kho (quy về đơn vị cơ sở, gợi ý/áp dụng lô FEFO, trừ đúng tồn của lô đã chọn và kiểm tra tồn kho theo đơn vị cơ sở) (S6-04) |
| GET | `/warehouse/export/suggest-lot` | `WAREHOUSE_EXPORT` hoặc `WAREHOUSE_VIEW` hoặc `ROLE_ADMIN` | Gợi ý lô xuất theo FEFO (hạn dùng gần nhất còn đủ số lượng) cho sản phẩm tại kho (S6-04) |
| POST | `/warehouse/export/suggest-lots` | `WAREHOUSE_EXPORT` hoặc `WAREHOUSE_VIEW` hoặc `ROLE_ADMIN` | Gợi ý lô xuất hàng loạt theo FEFO cho danh sách sản phẩm tại kho (S6-04) |
| POST | `/warehouse/adjust` | `WAREHOUSE_ADJUST` hoặc `ROLE_WAREHOUSE_MANAGER`, `ROLE_ADMIN` | Điều chỉnh tồn kho trực tiếp |
| GET | `/warehouse/inventory` | `ORDER_CREATE` hoặc `WAREHOUSE_VIEW` từ ma trận phân quyền (`ROLE_ADMIN` luôn được phép) | Xem tồn kho sổ sách theo đơn vị cơ sở kèm bảng quy đổi tương đương theo thùng, lốc |
| GET | `/warehouse/inventory/lots` | `WAREHOUSE_VIEW` hoặc các vai trò có quyền xem kho | Xem danh sách tồn kho chi tiết theo từng Số lô và Hạn sử dụng (S5-04) |
| GET | `/warehouse/vouchers` | `WAREHOUSE_VIEW` hoặc các vai trò có quyền xem kho | Danh sách phiếu kho (hỗ trợ lọc theo `warehouseId`, `type`, `status`: DRAFT/CONFIRMED/ADJUSTED, `supplierId`) |
| GET | `/warehouse/vouchers/{id}` | `WAREHOUSE_VIEW` hoặc các vai trò có quyền xem kho | Chi tiết phiếu kho kèm thông tin NCC, số chứng từ, số lô, HSD và đóng băng quy đổi |
| GET | `/warehouse/stock-card/{productId}` | `WAREHOUSE_VIEW` hoặc `ROLE_ADMIN` | Thẻ kho sản phẩm (S6-02): xem toàn bộ phát sinh nhập/xuất/điều chỉnh/chuyển kho theo thứ tự thời gian, tồn tích lũy và liên kết chứng từ gốc |
| GET | `/warehouse/stock-counts/options` | `WAREHOUSE_VIEW` hoặc `WAREHOUSE_ADJUST` hoặc `ROLE_ADMIN` | Tùy chọn kiểm kê (kho trong phạm vi, nhóm hàng, trạng thái, canAdjust) |
| GET | `/warehouse/stock-counts` | `WAREHOUSE_VIEW` hoặc `WAREHOUSE_ADJUST` hoặc `ROLE_ADMIN` | Danh sách phiếu kiểm kê (lọc theo warehouseId, categoryId, status, keyword; phân trang) |
| GET | `/warehouse/stock-counts/{id}` | `WAREHOUSE_VIEW` hoặc `WAREHOUSE_ADJUST` hoặc `ROLE_ADMIN` | Chi tiết phiếu kiểm kê kèm danh sách sản phẩm, tồn sổ, số thực đếm, chênh lệch |
| POST | `/warehouse/stock-counts` | `WAREHOUSE_VIEW` hoặc `WAREHOUSE_ADJUST` hoặc `ROLE_ADMIN` | Tạo phiếu kiểm kê kho (theo kho hoặc theo nhóm hàng, chốt tồn sổ tại thời điểm tạo) |
| PUT | `/warehouse/stock-counts/{id}/items` | `WAREHOUSE_VIEW` hoặc `WAREHOUSE_ADJUST` hoặc `ROLE_ADMIN` | Cập nhật số đếm thực tế cho các dòng sản phẩm, tự động tính chênh lệch từng dòng |
| POST | `/warehouse/stock-counts/{id}/complete` | `WAREHOUSE_ADJUST` từ ma trận hoặc `ROLE_ADMIN` | Chốt kiểm kê trong phạm vi kho, bắt buộc nhập lý do; không yêu cầu vai trò Quản lý kho |
| POST | `/warehouse/stock-counts/{id}/cancel` | `WAREHOUSE_VIEW` hoặc `WAREHOUSE_ADJUST` hoặc `ROLE_ADMIN` | Hủy phiếu kiểm kê ở trạng thái mới tạo (DRAFT) |

### Thẻ kho sản phẩm — Truy nguyên biến động tồn kho (S6-02)

**Cập nhật frontend + sửa backend 22/10/2026:**

- Trang `/warehouse/stock-card`, menu Kho hàng → Thẻ kho sản phẩm; liên kết từ Tồn kho chọn sẵn SKU và kho. Tất cả API dưới đây có tiền tố `/api/warehouse/stock-card`, cần WAREHOUSE_VIEW từ ma trận hoặc ADMIN, kể cả API chứng từ gốc. Không cần PRODUCT_VIEW/ORDER_VIEW; không trả giá bán hoặc hồ sơ khách hàng.
- `GET /options`: `data={warehouses:[{id,code,name}],products:[{id,code,name}],pageSizes:[20,50,100],defaultPageSize:50}`. Kho theo phân công (gồm kho ngừng hoạt động để truy lịch sử); danh mục gồm SKU hiện tại và ngừng kinh doanh.
- `GET /{productId}` giữ bộ tham số cũ. Kiểm fromDate <= toDate (400 khi ngược); thiếu quyền/sai scope kho trả 403. UI ngày bắt đầu đổi thành 00:00:00, ngày kết thúc thành 23:59:59.999999999 để bao gồm cả ngày.
- Tính lịch sử đầy đủ **trước khi lọc ngày và phân trang**. Tồn đầu lịch sử đối chiếu với tổng tồn vật lý DB trong scope trừ toàn bộ phát sinh đã ghi; không sinh phiếu giả cho dữ liệu seed/migration. Tồn trước trang = tồn sau dòng đầu trừ biến động dòng đó. `openingBalance` trả ở cả trang 0 nếu tồn đầu khác 0 hoặc có fromDate; null chỉ có nghĩa 0 ở trang đầu không lọc từ. Khi khoảng lọc không có dòng, vẫn trả tồn tại cuối khoảng lọc. Page vượt cuối trả currentPage đã chặn về trang cuối, openingBalance theo trang thực tế.
- Nguồn gồm phiếu đã ghi sổ RECORDED/CONFIRMED/ADJUSTED (không lấy DRAFT/CANCELLED), chuyển kho xuất/nhận/hủy, kiểm kê COMPLETED (chênh lệch có dấu), đơn đã có lịch sử EXPORTED/SHIPPED (mỗi dòng đơn chỉ ghi một lần ở lần xuất đầu tiên), điểm giao đã xác nhận nhập trả kho. Không tính giữ chỗ là trừ tồn vật lý.
- `sourceType`: VOUCHER, TRANSFER, STOCK_COUNT, ORDER, DELIVERY_RETURN. Riêng DELIVERY_RETURN, sourceId là ID điểm giao và sourceCode là mã chuyến. Ngày/đơn vị/biến động từ bản ghi nghiệp vụ, không tính trong JS.
- `GET /{productId}/sources/{sourceType}/{sourceId}?warehouseId=`: bản xem chứng từ gốc chỉ đọc **giới hạn các phát sinh của SKU trong scope kho**; response `data={sourceType,sourceId,sourceCode,productCode,productName,entries:[StockCardEntryResponse]}`. Không giới hạn 500 dòng đầu của thẻ kho khi tìm chứng từ. Không tìm thấy chứng từ đúng SKU/scope trả 403, không lộ thông tin kho ngoài scope. Đây không phải API sửa/hủy hay đọc toàn bộ dữ liệu bán hàng.
- Chỉnh luồng `/warehouse/adjust` để dòng phiếu lưu `baseQuantity` là chênh lệch vật lý có dấu (sau - trước), không dùng lượng đích làm số tăng tồn. Không đổi cách điều chỉnh tồn hay quyền hiện tại. **Giới hạn lịch sử:** phiếu điều chỉnh tuyệt đối tạo trước bản sửa, thiếu số biến động có dấu, cần đối soát/migration riêng; không tự suy đoán audit hoặc sửa lại dữ liệu cũ. Dữ liệu bị chỉnh ngoài hệ thống không thể truy nguyên thành chứng từ.
- Các phép đọc thẻ kho cùng transaction read-only REPEATABLE_READ; không ghi nghiệp vụ, không thêm schema. Quyền của JWT cũ vẫn nạp từ DB mỗi request, nhiều role lấy hợp quyền; ADMIN luôn được phép.

Dành cho **Nhân viên kho (`ROLE_WAREHOUSE_STAFF`)**, Quản lý kho và Quản trị viên (`ROLE_ADMIN`):
- **User story**: *"Là Nhân viên kho, tôi muốn xem thẻ kho của một sản phẩm, để truy nguyên được từ phiếu nào làm số tồn bị lệch."*
- **Endpoint**: `GET /api/warehouse/stock-card/{productId}`
- **Quyền**: `WAREHOUSE_VIEW` hoặc `ROLE_ADMIN`. Phạm vi kho tự động giới hạn theo danh sách kho được phân công trong DB (không fallback role; ADMIN xem toàn bộ).
- **Query parameters**:
  - `warehouseId` (tùy chọn, `Long`): Lọc theo một kho cụ thể. Nếu truyền kho ngoài phạm vi được phân công, hệ thống trả về `403 Forbidden`.
  - `fromDate` (tùy chọn, ISO 8601): Lọc biến động từ thời điểm này (bao gồm).
  - `toDate` (tùy chọn, ISO 8601): Lọc biến động đến thời điểm này (bao gồm).
  - `page` (tùy chọn, `int`, mặc định `0`): Chỉ số trang (0-based).
  - `size` (tùy chọn, `int`, mặc định `50`, tối đa `500`): Số dòng mỗi trang.
- **Dữ liệu trả về**:
  - Thông tin sản phẩm: `productId`, `productCode`, `productName`, `baseUnit`.
  - Danh sách dòng biến động `entries` được sắp xếp theo thời gian tăng dần (`eventTime` ASC, sau đó `sourceId` ASC):
    - `rowNumber`: Số thứ tự dòng liên tục trong trang (1-based).
    - `eventTime`: Thời điểm phát sinh sự kiện (thời điểm ghi sổ phiếu kho hoặc điều phối/nhận/hủy chuyển kho).
    - `eventType`: Loại phát sinh (`IMPORT`, `EXPORT`, `ADJUSTMENT`, `TRANSFER_DISPATCH`, `TRANSFER_RECEIVE`, `TRANSFER_CANCEL`).
    - `eventTypeLabel`: Nhãn hiển thị tiếng Việt ("Nhập kho", "Xuất kho", "Điều chỉnh", "Xuất chuyển kho", "Nhận chuyển kho", "Hủy chuyển kho (hoàn tồn)").
    - `baseQuantityChange`: Số lượng biến động theo đơn vị cơ sở (có dấu: `+` khi tăng tồn, `-` khi giảm tồn).
    - `runningBaseBalance`: Tồn kho lũy kế theo đơn vị cơ sở sau khi phát sinh sự kiện này.
    - `baseUnit`: Đơn vị cơ sở của sản phẩm.
    - `displayQuantity`: Số lượng theo đơn vị ghi trên chứng từ gốc.
    - `displayUnit`: Đơn vị ghi trên chứng từ gốc.
    - `warehouseId`, `warehouseCode`, `warehouseName`: Kho diễn ra phát sinh.
    - `sourceType`: Loại chứng từ gốc (`VOUCHER` cho phiếu kho, `TRANSFER` cho phiếu chuyển kho).
    - `sourceId`: ID chứng từ gốc (dùng để điều hướng mở chi tiết chứng từ).
    - `sourceCode`: Mã chứng từ gốc (`voucherCode` hoặc `transferCode`).
    - `note`: Ghi chú trên chứng từ hoặc dòng hàng / lý do hủy chuyển kho.
  - Phân trang: `totalElements`, `totalPages`, `currentPage`, `pageSize`.
  - `openingBalance`: Tồn vật lý trước dòng đầu tiên của trang thực tế. Trang đầu cũng có giá trị nếu có tồn đầu lịch sử hoặc lọc từ ngày; null chỉ biểu thị 0 ở trang đầu không lọc từ. Các trang sau tiếp tục số dư trang trước, không tính lại từ 0.

### Kiểm kê kho và ghi nhận chênh lệch (S5-08)

Dùng quyền từ ma trận phân quyền, không dùng tên vai trò để cấp quyền. Tất cả đường dẫn dưới đây có tiền tố `/api/warehouse/stock-counts`.

1. **Tạo phiếu kiểm kê theo kho hoặc theo nhóm hàng**:
   - `warehouseId` (bắt buộc): Kho cần kiểm kê.
   - `categoryId` (tùy chọn): Nhóm hàng cần kiểm kê. Nếu để trống, hệ thống đưa toàn bộ sản phẩm đang hoạt động trong kho vào danh sách kiểm kê.
   - `code` (tùy chọn, tối đa 50 ký tự): Mã phiếu kiểm kê (tự sinh `PKK-yyyyMMddHHmmssSSS-xxxxxxxx` nếu để trống).
   - `note` (tùy chọn): Ghi chú phiếu kiểm kê.
   - **Chốt tồn sổ sách tại thời điểm tạo**: Hệ thống khóa kho và tồn theo thứ tự ID sản phẩm để snapshot số lượng hiện tại (`bookQuantity`), ID và phiên bản tồn kho. Đơn vị được đóng băng từ dòng tồn kho hiện có; nếu chưa có tồn thì dùng đơn vị cơ sở sản phẩm và số lượng 0. Trạng thái ban đầu là `DRAFT`; tạo phiếu chưa điều chỉnh tồn.
   - `items` (tùy chọn): Nhập trước số đếm qua các dòng `{ "productId": 123, "actualQuantity": 0, "note": "Đã đếm" }`.
2. **Nhập số đếm thực tế & Tự động tính chênh lệch từng dòng**:
   - `PUT /{id}/items` nhận `{ "items": [{ "productId": 123, "actualQuantity": 0 }], "note": "Ghi chú phiếu" }`. Chỉ sửa phiếu `DRAFT`; các sản phẩm không được gửi lên giữ nguyên.
   - `actualQuantity` theo đơn vị cơ sở đóng băng, >= 0, tối đa 11 chữ số nguyên và 4 chữ số thập phân. `0` nghĩa là đã đếm và không có hàng; `null`/bỏ trường nghĩa là chưa đếm (hoặc xóa số đã nhập). `differenceQuantity` bằng `null` khi chưa đếm.
   - Hệ thống tự động tính `differenceQuantity = actualQuantity - bookQuantity`:
     - Giá trị dương (> 0): Thừa thực tế so với sổ sách.
     - Giá trị âm (< 0): Thiếu thực tế so với sổ sách (hao hụt, mất mát...).
     - Bằng 0: Số thực tế khớp hoàn toàn với sổ sách.
   - Cho phép ghi chú riêng cho từng dòng và ghi chú phiếu, đều tối đa 500 ký tự. Từ chối dòng null, thiếu `productId`, ID sản phẩm lặp hoặc không thuộc snapshot, số âm, số vượt độ chính xác, kể cả trong `items` gửi lúc tạo hoặc chốt.
3. **Chốt kiểm kê và Điều chỉnh tồn kho về số thực đếm**:
   - `POST /{id}/complete` nhận `{ "reason": "Kiểm kê định kỳ", "items": [...] }`; `items` tùy chọn để lưu và chốt trong cùng giao dịch. **Bắt buộc nhập lý do giải trình** (`reason` không được để trống, tối đa 500 ký tự).
   - Kiểm tra tất cả sản phẩm trong phiếu đã có số thực đếm.
   - Khóa bi quan phiếu trước khi cập nhật/chốt/hủy; khi chốt tiếp tục khóa kho rồi từng dòng tồn theo ID sản phẩm tăng dần. Thứ tự khóa tồn thống nhất với đơn hàng và chuyển kho. Hai lần chốt đồng thời chỉ một lần thành công.
   - Kiểm tra toàn bộ snapshot trước khi ghi tồn. Nếu ID/phiên bản/số lượng/đơn vị tồn thay đổi, từ chối với thông báo yêu cầu **hủy phiếu và tạo phiếu kiểm kê mới**. Bao gồm chuyển động xuất rồi nhập trả đúng lượng cũ, và dòng tồn mới được tạo sau snapshot số 0. Thay đổi tên đơn vị trên danh mục sản phẩm không thay đơn vị đã đóng băng trên phiếu.
   - Nếu `actualQuantity < reservedQuantity` đang giữ chỗ, từ chối toàn bộ và yêu cầu xử lý đơn hàng giữ chỗ. Khi hợp lệ mới cập nhật `WarehouseInventory.baseQuantity = actualQuantity`; giữ nguyên reserved/in-transit và đơn vị đóng băng.
   - Ghi nhận nhật ký thao tác `AuditLog` cho từng sản phẩm bị điều chỉnh lệch tồn và cho toàn bộ phiếu kiểm kê.
   - Chuyển trạng thái phiếu sang `COMPLETED`, lưu người chốt và thời điểm chốt. Số đếm gửi kèm, tồn, trạng thái và audit cùng giao dịch: lỗi bất kỳ dòng nào hoặc lỗi ghi audit đều rollback toàn bộ.
4. **Phân quyền ma trận và Giới hạn quyền chốt kiểm kê**:
   - Đọc danh sách/chi tiết/options, tạo, nhập số đếm và hủy cần `WAREHOUSE_VIEW` **hoặc** `WAREHOUSE_ADJUST`. Chốt chỉ cần `WAREHOUSE_ADJUST`. Bất kỳ vai trò nào (ví dụ VIEWER) được cấp ADJUST đều có thể chốt; vai trò Quản lý kho thiếu quyền không được phép. `ROLE_ADMIN` luôn được phép.
   - JWT hiện tại được nạp lại quyền theo ma trận ở mỗi request; cấp/thu hồi quyền có hiệu lực ở request kế tiếp. Nhiều vai trò lấy hợp quyền.
   - Phạm vi kho đã gán được áp dụng cho mọi vai trò; riêng vai trò kho không có kho được gán thì phạm vi rỗng. Vai trò khác không có kho được gán giữ phạm vi toàn cục như các chức năng kho hiện có. ADMIN có phạm vi toàn cục.
   - `/options` trả `warehouses`, `categories`, `statuses`, `canAdjust` theo quyền. Chi tiết trả `items`; danh sách phân trang trả bản tóm tắt, gồm `totalItems`, `matchedItems`, `diffItems`, `uncountedItems`. `canEdit` chỉ true khi có VIEW hoặc ADJUST, đúng phạm vi và phiếu DRAFT; `canAdjust` trên phiếu chỉ true khi có ADJUST, đúng phạm vi và DRAFT. Không suy ra quyền từ tên vai trò.
5. **Hủy và phản hồi lỗi**:
   - `POST /{id}/cancel` nhận body tùy chọn `{ "reason": "Chọn nhầm kho" }` (lý do tối đa 500 ký tự). Chỉ hủy phiếu DRAFT; lưu người/thời điểm/lý do, không thay tồn. Phiếu COMPLETED/CANCELLED không sửa/chốt/hủy lại.
   - Giữ envelope `{ "success": true, "message": "...", "data": ... }` và đường dẫn hiện có. Request hợp lệ thành công trả HTTP 200; dữ liệu không hợp lệ, snapshot cũ, thiếu số đếm, dưới tồn giữ chỗ hoặc trạng thái không hợp lệ trả 400 với `success: false` và thông báo; thiếu quyền hoặc sai phạm vi trả 403; chưa xác thực trả 401.
6. **Triển khai schema và phiếu cũ**:
   - Bổ sung `warehouse_inventories.version` (`BIGINT NOT NULL DEFAULT 0`, JPA `@Version`) và `warehouse_stock_count_items.snapshot_captured` (`BOOLEAN NOT NULL DEFAULT FALSE`), `snapshot_inventory_id`, `snapshot_inventory_version` (BIGINT nullable). Đây là metadata nội bộ, không thêm trường bắt buộc vào DTO.
   - Mở rộng `warehouse_stock_count_items.note` lên `VARCHAR(500)` để thống nhất giới hạn ghi chú toàn tính năng.
   - Các phiếu cũ có `snapshot_captured = false` vẫn xem/hủy được nhưng không chốt được: cần hủy và kiểm kê lại. Không backfill snapshot cũ bằng tồn hiện tại vì sẽ bỏ qua chuyển động sau khi lập phiếu.
   - Phiên bản tăng cho thay đổi tồn qua JPA (kể cả giữ chỗ/đang chuyển được cập nhật qua JPA), nên có thể cần kiểm kê lại dù lượng vật lý bằng cũ. Luồng giữ chỗ S5-06 dùng cập nhật JPA dưới khóa để tăng phiên bản; completion vẫn đọc reserved mới dưới khóa để bảo vệ lượng giữ chỗ. Không dùng SQL ngoài ứng dụng để sửa tồn mà bỏ qua phiên bản.
   - Sau khi lấy khóa phiếu kiểm kê, refresh lại header và các dòng trước khi lưu/chốt/hủy. Truy vấn join có thể đã đọc số đếm trước lúc phải đợi khóa; không dùng bản số đếm cũ đó để chốt tồn. Kiểm thử điều khiển khóa xác minh completion đang chờ phải lấy số đếm vừa commit.

### Lập phiếu nhập kho từ nhà cung cấp & Quản lý lô, hạn sử dụng (S5-04)

Dành cho người dùng được cấp quyền trong **ma trận DB**, không khóa cứng theo vai trò. Mọi thao tác tuân thủ kho được giao; vai trò kho không được giao kho thì phạm vi rỗng. ADMIN luôn được phép.
1. **Khai báo thông tin chứng từ khi xe dỡ hàng**:
   - `supplierId` hoặc `supplierCode`: Nhà cung cấp giao hàng (phải tồn tại và đang hoạt động `active = true`).
   - `documentNumber`: Số chứng từ đi kèm (số hóa đơn đỏ, biên bản giao nhận hàng).
   - `voucherDate`: Ngày nhập kho thực tế, dạng `2026-10-21T00:00:00`; bắt buộc khi nhập trực tiếp, được lưu riêng ở DB.
   - `warehouseId`: Kho nhập hàng (phải tồn tại và đang hoạt động).
   - `voucherCode`: Bỏ trống để server sinh mã `PNK-<UUID>`; không lấy số thứ tự từ số phiếu đang hiển thị trên trình duyệt.
2. **Nhập theo đơn vị tính bất kỳ, quy về đơn vị cơ sở khi ghi sổ**:
   - Dòng hàng có thể chọn bất kỳ đơn vị tính nào đã được cấu hình trong bảng quy đổi của SKU (vd: thùng, lốc, bao, chai, lon...).
   - Hệ thống tự động nhân với hệ số quy đổi đóng băng `conversionFactor` để tính `baseQuantity = quantity * factor`.
   - Nháp chỉ chứa quy đổi dự kiến; khi xác nhận server tính lại hệ số hiện hành và đóng băng đơn vị/hệ số/số lượng cơ sở. Đổi quy đổi sau xác nhận không thay phiếu cũ.
3. **Quản lý Số lô và Hạn sử dụng**:
   - Trên sản phẩm (`Product`), cờ `manageLot = true` xác định mặt hàng được quản lý theo lô (vd: bia, nước giải khát, thực phẩm...).
   - Khi lập phiếu nhập kho:
     - Nếu `manageLot == true`: Bắt buộc phải nhập `lotNumber` (số lô) và `expiryDate` (hạn sử dụng dạng `YYYY-MM-DD`). Thiếu một trong hai sẽ bị chặn và báo lỗi rõ ràng. Nếu có `manufacturingDate` (ngày sản xuất), hạn sử dụng phải sau ngày sản xuất.
     - Nếu `manageLot == false`: Số lô và hạn sử dụng không bắt buộc.
4. **Vòng đời phiếu: Nháp vs Xác nhận**:
   - **Phiếu nháp (`status = "DRAFT"` hoặc `draft: true`)**:
     - Lưu lại thông tin kiểm đếm tại cửa kho.
     - **Hoàn toàn KHÔNG cộng tồn kho** (`WarehouseInventory` và `WarehouseLotInventory` không đổi).
     - Cho phép chỉnh sửa thông tin, đổi hàng hóa, đổi số lượng qua `PUT /api/warehouse/vouchers/{id}`.
     - Cho phép xóa bỏ phiếu nháp qua `DELETE /api/warehouse/vouchers/{id}`.
   - **Xác nhận phiếu (`POST /api/warehouse/vouchers/{id}/confirm`)**:
     - Sau khi xe dỡ hàng xong và kiểm đếm đủ, nhân viên kho bấm xác nhận.
     - Hệ thống kiểm tra hợp lệ toàn bộ phiếu, sau đó **cộng tồn kho tổng** và **cập nhật số lượng vào sổ theo dõi tồn theo lô** (`WarehouseLotInventory`).
     - Trạng thái phiếu chuyển thành `CONFIRMED`.
5. **Phiếu đã xác nhận không sửa được, chỉ lập phiếu điều chỉnh**:
   - Mọi hành vi cố tình gọi `PUT` hoặc `DELETE` trên phiếu đã ở trạng thái `CONFIRMED` / `RECORDED` đều bị hệ thống chặn với ngoại lệ `IllegalStateException` ("Phiếu đã xác nhận không sửa được, chỉ lập phiếu điều chỉnh").
   - Khi có sai sót, người có `WAREHOUSE_ADJUST` hoặc ADMIN dùng `POST /api/warehouse/vouchers/{id}/adjust`:
     - Phiếu điều chỉnh tự động liên kết đến `referenceVoucherId` và `referenceVoucherCode`.
     - Ghi nhận `adjustmentReason` (lý do điều chỉnh).
     - Điều chỉnh lại số lượng tồn kho theo chênh lệch và cập nhật trạng thái phiếu gốc sang `ADJUSTED`.

Frontend dùng luồng `POST /warehouse/import/draft` → `PUT /warehouse/vouchers/{id}` nếu sửa → `POST /warehouse/vouchers/{id}/confirm`. Người chỉ có IMPORT lưu/sửa/xóa nháp nhưng không được xác nhận. Người chỉ có APPROVE mở sổ phiếu và xác nhận nhưng không sửa nháp. Thu hồi quyền trên token đang dùng có hiệu lực từ request tiếp theo; FE ẩn/khóa thao tác khi nhận thay đổi quyền.

Các trường header gửi riêng: `supplierId`, `documentNumber` (tối đa 100 ký tự), `voucherDate`, `warehouseId`, `note` (500). Mỗi dòng gửi `productId`, `unit`, `quantity` (dương, tối đa 4 số lẻ), `lotNumber` (50), `expiryDate`, tùy chọn `manufacturingDate`, `note` (255). Không nhét NCC/lô/HSD vào note. Cùng SKU/kho/số lô phải cùng HSD; không ghi đè ngày lô cũ.

Điều chỉnh từ UI dùng một dòng và `delta` có dấu theo đơn vị dòng, `quantity=abs(delta)` trong request, `adjustmentReason` 1–500 ký tự. Response phiếu điều chỉnh ghi lượng thay đổi có dấu và tham chiếu phiếu gốc. API cũng hỗ trợ số đích khi không gửi delta: nếu chọn lô, tổng kho chỉ thay đổi theo chênh lệch của lô đó, không ghi đè tổng bằng số của một lô.

Schema bổ sung `warehouse_vouchers.version BIGINT NOT NULL DEFAULT 0` cho kiểm soát phiên sửa. Cấu hình phát triển hiện tại `ddl-auto=update` tự bổ sung khi khởi động; môi trường dùng migration thủ công cần thêm cột tương đương trước khi chạy. Không xóa hoặc khởi tạo lại DB.

### Danh sách API Chuyển kho nội bộ (S5-07)

Hỗ trợ Nhân viên kho lập phiếu chuyển kho nội bộ để điều hàng từ kho thừa sang kho thiếu mà số liệu hai bên đều chính xác, minh bạch.

| Method | Endpoint | Quyền | Mô tả |
|---|---|---|---|
| POST | `/warehouse/transfers` (hoặc `/warehouse/transfer`) | `WAREHOUSE_TRANSFER` hoặc ADMIN | Kho đi thuộc phạm vi được giao; trừ tồn khả dụng ở kho đi, ghi hàng đang trên đường tại kho đến; không cộng tồn thực tế kho đến |
| GET | `/warehouse/transfers` | `WAREHOUSE_TRANSFER` hoặc `WAREHOUSE_VIEW` hoặc ADMIN | Giữ DTO danh sách cũ; chỉ phiếu có kho đi hoặc kho đến thuộc phạm vi; lọc `fromWarehouseId`, `toWarehouseId`, `status` |
| GET | `/warehouse/transfers/search` | Như API danh sách | Trả `data.content`, `totalElements`, `totalPages`, `number`, `last`; thêm `keyword` theo mã phiếu, `page` mặc định 0, `size` mặc định 20 (1–200) |
| GET | `/warehouse/transfers/options` | Như API danh sách | `sourceWarehouses`: kho hoạt động trong phạm vi; `destinationWarehouses`: danh mục kho đến hoạt động; `statuses`: value/label từ server; `canCreate`: quyền thao tác hiện tại |
| GET | `/warehouse/transfers/products?warehouseId=1&keyword=SKU&page=0&size=20` | Như API danh sách | Kho đi phải trong phạm vi; sản phẩm đang hoạt động có sổ tồn tại kho; DTO hẹp `productId/productCode/productName/unit/physicalQuantity/reservedQuantity/availableQuantity`, phân trang; không cần thêm quyền sản phẩm/đơn hàng, không trả giá vốn/giá bán |
| GET | `/warehouse/transfers/{id}` | Như API danh sách | Chi tiết trong phạm vi, kèm `canReceive`, `canCancel` do server kiểm quyền và kho tương ứng |
| POST | `/warehouse/transfers/{id}/receive` | `WAREHOUSE_TRANSFER` hoặc ADMIN | Chỉ kho đến trong phạm vi; khóa phiếu và tồn; nhận đủ/thực nhận; chênh lệch bắt buộc lý do |
| POST | `/warehouse/transfers/{id}/cancel` | `WAREHOUSE_TRANSFER` hoặc ADMIN | Chỉ kho đi trong phạm vi; bắt buộc `reason` 1–500 ký tự; chỉ IN_TRANSIT; hoàn tồn kho đi, xóa phần đang chuyển của phiếu |

Quyền lấy từ ma trận DB ở mỗi request, không có fallback role. Bỏ `WAREHOUSE_TRANSFER` vẫn có thể xem nếu còn `WAREHOUSE_VIEW`, nhưng cả ba API POST đều trả 403. Bỏ cả hai thì không được xem/menu bị ẩn. ADMIN luôn toàn quyền. Kho được gán giới hạn mọi vai trò; riêng vai trò kho không được gán kho thì phạm vi rỗng, không tự mở toàn hệ thống. Không yêu cầu quyền IMPORT/EXPORT/PRODUCT_VIEW làm điều kiện phụ.

Frontend: **Kho hàng → Chuyển kho nội bộ**, URL `/warehouse/transfers`, dùng app shell và khung nội dung chung. UI lập phiếu chọn sản phẩm theo **đơn vị cơ sở của tồn kho**; API vẫn hỗ trợ đơn vị quy đổi và đóng băng hệ số tại thời điểm tạo phiếu.

#### Quy tắc nghiệp vụ chuyển kho (S5-07):
1. **Lập phiếu chuyển**:
   - Chọn kho xuất (`fromWarehouseId`), kho nhập (`toWarehouseId`), danh sách sản phẩm, số lượng và đơn vị tính (thùng, lốc, lon...).
   - Kho xuất và kho nhập không được trùng nhau. Cả hai kho phải đang hoạt động (`active = true`).
   - Kiểm tra **tồn khả dụng = tồn thực tế − tồn giữ chỗ**; không lấy hàng đã giữ chỗ để chuyển. Khóa ghi kho/phiếu/tồn theo thứ tự ổn định để bảo vệ nhận/hủy lặp, hai phiếu chuyển đồng thời hoặc chuyển cạnh tranh với đơn đặt hàng.
   - Từ 1–200 dòng, không trùng SKU; số lượng dương tối đa 4 số thập phân. Mã phiếu để trống/null thì tự sinh ở backend, nhập riêng thì dùng mã đó (tối đa 50 ký tự, duy nhất).
   - Khi tạo thành công: tồn kho tại kho xuất bị trừ ngay lập tức; hàng được ghi nhận là đang trên đường chuyển (`status = IN_TRANSIT`, `inTransitQuantity` tại kho nhập tăng lên tương ứng); tồn kho khả dụng (`baseQuantity`) tại kho nhập **CHƯA ĐƯỢC CỘNG**.
2. **Xác nhận nhận hàng tại kho đến**:
   - Chỉ được xác nhận khi phiếu ở trạng thái `IN_TRANSIT`.
   - Nếu nhận đủ: `receivedQuantity` bằng số lượng xuất, tồn kho đến được cộng đủ, `inTransitQuantity` trừ về 0, trạng thái thành `COMPLETED`.
   - Nếu có chênh lệch (`receivedQuantity != quantity`), kể cả nhận thừa hoặc 0: **BẮT BUỘC** lý do `discrepancyReason` 1–500 ký tự; không nhập trả 400. Lưu số chuyển, thực nhận, chênh lệch và lý do theo dòng để đối chiếu.
   - Body vắng/null, `items` vắng hoặc `[]` giữ tương thích nhận đủ. Nếu truyền danh sách thực nhận thì phải có đủ tất cả dòng; dùng `itemId` (ưu tiên) hoặc `productId` duy nhất thuộc phiếu; từ chối dòng lạ/trùng/mâu thuẫn ID, thiếu/âm số lượng. Toàn bộ cập nhật nằm trong một transaction, lỗi ở dòng sau không làm cộng tồn dòng trước.
   - Tồn kho tại kho nhập chỉ được cộng đúng số lượng thực nhận (`receivedBaseQuantity`).
3. **Hủy phiếu chuyển kho**:
   - Chỉ áp dụng cho phiếu đang ở trạng thái `IN_TRANSIT`.
   - Hoàn lại tồn kho cho kho xuất và xóa lượng đang trên đường tại kho nhập. Trạng thái phiếu thành `CANCELLED`.
   - Gửi `{ "reason": "Lý do hủy phiếu" }`. Phiếu đã nhận hoặc đã hủy không thể nhận/hủy lần nữa. Lịch sử xuất/nhận/hủy được ghi audit; không thay đổi schema hoặc seed trong S5-07.

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
| POST | `/orders` | `ORDER_CREATE` | Tạo đơn hàng cho đại lý (tự động áp bảng giá hiệu lực theo nhóm khách hàng; chặn nếu SKU ngoài bảng giá; bán dưới giá sàn chuyển sang `PENDING_APPROVAL`) |
| POST | `/orders/line-item/pricing` | `ORDER_CREATE` | Tra cứu giá bảng giá, giá sàn, tính chiết khấu sản lượng và kiểm tra cảnh báo dưới giá sàn cho một dòng sản phẩm |
| POST | `/orders/calculate` | `ORDER_CREATE` | Tính toán lại chiết khấu sản lượng và tổng tiền toàn bộ đơn hàng khi thay đổi số lượng |
| PUT | `/orders/{id}/cancel` | `ORDER_CANCEL` | Hủy đơn hàng kèm lý do bắt buộc; tự động giải phóng tồn kho giữ chỗ (`reservedQuantity`); chặn huỷ đơn đã xuất kho trở đi (`EXPORTED`, `DELIVERED`, `CLOSED`) |
| GET | `/orders/{id}/tracking` | `ORDER_VIEW` | Theo dõi vòng đời đơn hàng (7 bước chính và nhánh Huỷ, timeline lịch sử, người thực hiện, lý do huỷ/từ chối; áp dụng RLS theo người phụ trách/kho) |
| PUT | `/orders/{id}/status` | `WAREHOUSE_IMPORT` hoặc `WAREHOUSE_EXPORT` | Cập nhật từng bước kế tiếp của vòng đời đơn hàng; chỉ nhận `PICKING`, `EXPORTED`, `DELIVERED`, `CLOSED`, giới hạn theo kho được gán và tự động trừ tồn kho/giải phóng giữ chỗ khi chuyển sang `EXPORTED` |
| PUT | `/orders/{id}/delivery` | `WAREHOUSE_EXPORT` | Cập nhật thực giao; chỉ chuyển `EXPORTED` sang `DELIVERED`, không được bỏ qua bước vòng đời và không dùng role fallback |
| PUT | `/orders/{id}/approve` | `ORDER_APPROVE` hoặc `ROLE_ADMIN` | Phê duyệt đơn hàng (chuyển trạng thái sang `RECORDED`) và giữ chỗ tồn kho sau khi kiểm quyền/phạm vi kho; xác nhận đơn cổng dùng endpoint `/confirm` với `ORDER_CONFIRM`, không dùng `ORDER_CREATE` để thay quyền duyệt |
| PUT | `/orders/{id}/reject?reason={ly_do}` | `ORDER_APPROVE` hoặc `ROLE_ADMIN`; `ORDER_CREATE` chỉ cho NVKD xử lý đơn cổng được phân công | Từ chối đơn hàng với lý do bắt buộc (chuyển trạng thái sang `REJECTED`) |
| PUT | `/orders/{id}/return?reason={ly_do}` | `ORDER_APPROVE` hoặc `ROLE_ADMIN` | Trả lại đơn để sửa với lý do bắt buộc (chuyển trạng thái sang `RETURNED`) |
| PATCH | `/orders/{id}/status` | `ORDER_APPROVE` hoặc `ROLE_ADMIN` | Duyệt, từ chối hoặc trả lại đơn trên màn hình S4-05 |
| GET | `/orders/{id}/approval-history` | `ORDER_APPROVE` hoặc `ROLE_ADMIN` | Xem lịch sử duyệt bất biến của đơn hàng |
| POST | `/orders/{id}/copy` | `ORDER_CREATE`, `ROLE_SALES`, `ROLE_SALES_MANAGER`, `ROLE_ADMIN` | Sao chép đơn cũ thành đơn mới ở trạng thái Nháp (áp lại giá và chiết khấu hiện hành) (S4-09) |
| POST | `/orders/{id}/copy-to-draft` | `ORDER_CREATE`, `ROLE_SALES`, `ROLE_SALES_MANAGER`, `ROLE_ADMIN` | Sao chép các dòng hàng của đơn cũ vào bản nháp tạo đơn của người dùng (S4-09) |
| POST | `/orders/dealer/{dealerId}/last-order-to-draft` | `ORDER_CREATE`, `ROLE_SALES`, `ROLE_SALES_MANAGER`, `ROLE_ADMIN` | Thêm nhanh cả nhóm hàng đã mua lần trước của đại lý vào bản nháp tạo đơn mới (S4-04) |

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
   - Nếu request truyền `priceListId`, backend bắt buộc bảng giá đó thuộc đúng nhóm khách hàng, đang `active` và còn hiệu lực tại `orderDate`; không thể dùng bảng giá của nhóm khác hoặc bảng giá đã hết hiệu lực để tính/chốt đơn.
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

### Theo dõi Vòng đời & Huỷ đơn hàng (S4-06)

Hỗ trợ Nhân viên kinh doanh theo dõi vòng đời đơn hàng trực quan và thực hiện huỷ đơn hàng khi phát sinh nhu cầu, tự động giải phóng lượng hàng giữ chỗ trong kho và ngăn chặn huỷ sai quy trình khi hàng đã rời kho.

#### 1. Chuẩn hóa Vòng đời đơn hàng 7 bước
Vòng đời đơn hàng được quản trị qua 7 bước chuẩn:
1. **Nháp (`DRAFT`)**: Đơn mới tạo hoặc đang lưu nháp.
2. **Chờ duyệt (`PENDING_APPROVAL`)**: Đơn có dòng hàng bán dưới giá sàn hoặc chính sách đặc biệt, chờ Quản lý kinh doanh duyệt.
3. **Đã duyệt (`APPROVED` / `RECORDED`)**: Đơn đã được duyệt hoặc ghi nhận hợp lệ, hệ thống tự động giữ chỗ tồn kho tương ứng (`reservedQuantity`) trong kho chỉ định.
4. **Đang soạn hàng (`PICKING`)**: Kho nhận lệnh và tiến hành lấy hàng, đóng gói.
5. **Đã xuất (`EXPORTED`)**: Hàng hóa đã rời kho. Tại thời điểm này, hệ thống tự động trừ số lượng tồn thực tế (`quantity`) và giải phóng số lượng giữ chỗ (`reservedQuantity`).
6. **Đã giao (`DELIVERED`)**: Đơn hàng đã giao thành công tới điểm giao hàng của đại lý.
7. **Đóng (`CLOSED`)**: Đơn hàng hoàn tất chu trình bán và giao hàng.

Nhánh rẽ kết thúc sớm:
- **Huỷ (`CANCELLED`)**: Đơn bị huỷ bởi người có thẩm quyền trước khi xuất kho.
- **Từ chối duyệt (`REJECTED`)**: Quản lý từ chối duyệt đơn bán dưới giá sàn.

#### 2. Quy tắc huỷ đơn hàng & Giải phóng tồn kho giữ chỗ
- **Lý do huỷ là bắt buộc**: Khi gọi `PUT /api/orders/{id}/cancel`, trường `reason` là bắt buộc (gửi qua JSON body `{ "reason": "Khách hàng đổi ý" }` hoặc query parameter `?reason=...`). Nếu để trống hoặc chỉ chứa khoảng trắng, hệ thống từ chối với mã lỗi `400 Bad Request`: `"Lý do huỷ đơn hàng không được để trống"`.
- **Chặn huỷ đơn đã xuất kho**: Nếu đơn hàng đang ở trạng thái `EXPORTED`, `DELIVERED`, hoặc `CLOSED`, hệ thống nghiêm cấm huỷ và trả lỗi `400 Bad Request` kèm thông báo:
  `"Đơn đã xuất kho thì không huỷ được, phải đi đường trả hàng"`
  (Ngoại lệ `OrderCannotBeCancelledException`).
- **Tự động hoàn lại hàng giữ chỗ**: Khi đơn hàng được duyệt, kho đã ghi nhận `reservedQuantity` cho từng sản phẩm. Khi huỷ đơn thành công, hệ thống tự động trừ `reservedQuantity` tương ứng, nâng lại số lượng tồn khả dụng (`availableQuantity`) và đặt `inventoryReserved = false`.
- **Lưu vết đầy đủ vào Database**: Khi huỷ thành công, trạng thái đơn chuyển sang `CANCELLED`, lưu `cancelReason`, `cancelledBy`, `cancelledAt` vào bảng `orders`, ghi một bản ghi vào bảng `order_status_histories` và ghi một bản ghi vào bảng `audit_logs`.

#### 3. Phân quyền dòng dữ liệu (Row-Level Security - RLS)
- Người dùng có `ORDER_VIEW`/`ORDER_CANCEL` trong ma trận quyền chỉ được xem hoặc huỷ theo đúng phạm vi dữ liệu của mình; Nhân viên kinh doanh (`ROLE_SALES`) chỉ được phép theo dõi và huỷ những đơn hàng:
  - Do chính tài khoản đó tạo ra (`order.createdBy == currentUser`), HOẶC
  - Đơn hàng thuộc các đại lý được phân công phụ trách (`assignedDealers`).
- Nếu Nhân viên kinh doanh cố tình truy cập hoặc huỷ đơn hàng của nhân sự khác / đại lý khác, hệ thống chặn lại với lỗi `403 Forbidden` (`"Bạn không có quyền truy cập hoặc huỷ đơn hàng này"`).
- Tài khoản có quyền phù hợp được cấp trong ma trận; `ROLE_ADMIN` luôn được phép. Tài khoản kho chỉ được cập nhật vòng đời trong các kho đã được gán.

#### 4. API Theo dõi Vòng đời (`GET /api/orders/{id}/tracking`)
**Response thành công (`200 OK`):**
```json
{
  "success": true,
  "message": "Lấy thông tin tiến trình đơn hàng thành công",
  "data": {
    "orderId": 12,
    "orderCode": "DH-20261014-0012",
    "dealerId": 1,
    "dealerName": "Đại lý Cấp 1 Minh Phát",
    "warehouseId": 1,
    "warehouseName": "Kho Tổng Hà Nội",
    "currentStatus": "PICKING",
    "currentStatusDisplayName": "Đang soạn hàng",
    "canCancel": true,
    "isExported": false,
    "inventoryReserved": true,
    "cancelReason": null,
    "cancelledBy": null,
    "cancelledAt": null,
    "steps": [
      {
        "stepIndex": 1,
        "stepKey": "DRAFT",
        "stepName": "Nháp",
        "state": "COMPLETED",
        "completedAt": "2026-10-14T08:00:00",
        "completedBy": "sales1",
        "note": "Khởi tạo đơn hàng"
      },
      {
        "stepIndex": 2,
        "stepKey": "PENDING_APPROVAL",
        "stepName": "Chờ duyệt",
        "state": "SKIPPED",
        "completedAt": null,
        "completedBy": null,
        "note": null
      },
      {
        "stepIndex": 3,
        "stepKey": "APPROVED",
        "stepName": "Đã duyệt",
        "state": "COMPLETED",
        "completedAt": "2026-10-14T08:05:00",
        "completedBy": "sales1",
        "note": "Ghi nhận đơn và đặt trước tồn kho"
      },
      {
        "stepIndex": 4,
        "stepKey": "PICKING",
        "stepName": "Đang soạn hàng",
        "state": "CURRENT",
        "completedAt": "2026-10-14T08:30:00",
        "completedBy": "kho_hn",
        "note": "Đang gom hàng theo phiếu xuất"
      },
      {
        "stepIndex": 5,
        "stepKey": "EXPORTED",
        "stepName": "Đã xuất kho",
        "state": "UPCOMING",
        "completedAt": null,
        "completedBy": null,
        "note": null
      },
      {
        "stepIndex": 6,
        "stepKey": "DELIVERED",
        "stepName": "Đã giao hàng",
        "state": "UPCOMING",
        "completedAt": null,
        "completedBy": null,
        "note": null
      },
      {
        "stepIndex": 7,
        "stepKey": "CLOSED",
        "stepName": "Đóng đơn",
        "state": "UPCOMING",
        "completedAt": null,
        "completedBy": null,
        "note": null
      }
    ],
    "history": [
      {
        "id": 101,
        "previousStatus": "DRAFT",
        "newStatus": "APPROVED",
        "statusDisplayName": "Đã duyệt",
        "note": "Ghi nhận đơn và đặt trước tồn kho",
        "changedBy": "sales1",
        "changedAt": "2026-10-14T08:05:00"
      },
      {
        "id": 102,
        "previousStatus": "APPROVED",
        "newStatus": "PICKING",
        "statusDisplayName": "Đang soạn hàng",
        "note": "Đang gom hàng theo phiếu xuất",
        "changedBy": "kho_hn",
        "changedAt": "2026-10-14T08:30:00"
      }
    ]
  }
}
```

#### 5. API Cập nhật trạng thái vòng đời (`PUT /api/orders/{id}/status`)
**Request:**
```json
PUT /api/orders/12/status
{
  "status": "EXPORTED",
  "note": "Đã hoàn thành xuất kho bàn giao bên vận chuyển"
}
```
**Response (`200 OK`):**
Trả thông tin đơn hàng `OrderResponse` với `status = "EXPORTED"`, `statusDisplayName = "Đã xuất"`, `isExported = true`, cập nhật tồn kho thực tế và lưu vết `order_status_histories`.

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
| GET | `/price-lists/effective?customerGroup={cg}&date={yyyy-MM-dd}` | `ORDER_CREATE` | Tìm bảng giá đang có hiệu lực cho nhóm khách hàng tại thời điểm chỉ định khi tạo đơn |
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
| GET | `/dealers` | `CUSTOMER_VIEW` hoặc `ORDER_VIEW` | Danh sách đại lý (tìm kiếm, lọc theo nhóm, khu vực, người phụ trách, trạng thái, phân trang) |
| GET | `/dealers/active` | `ORDER_CREATE` | Danh sách đại lý đang hoạt động (phục vụ dropdown chọn đại lý khi tạo đơn) |
| GET | `/dealers/options` | `CUSTOMER_VIEW` hoặc `ORDER_VIEW` | Danh mục tham chiếu tạo/sửa đại lý (nhóm khách hàng, khu vực, người phụ trách) |
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
| GET | `/dealers/{id}/purchase-history?months={n}&lastOrderOnly={true/false}` | `ORDER_CREATE`, `ORDER_VIEW`, `CUSTOMER_VIEW`, `ROLE_SALES`, `ROLE_SALES_MANAGER`, `ROLE_ADMIN`, `ROLE_WAREHOUSE_MANAGER`, `ROLE_WAREHOUSE_STAFF` | Lấy lịch sử mua hàng trong N tháng gần nhất kèm số lượng bình quân (mặc định 3 tháng) và cờ `inLastOrder`. RLS: SALES chỉ xem đại lý mình phụ trách (S4-04) |
| GET | `/dealers/{id}/last-order-items` | `ORDER_CREATE`, `ORDER_VIEW`, `CUSTOMER_VIEW`, `ROLE_SALES`, `ROLE_SALES_MANAGER`, `ROLE_ADMIN`, `ROLE_WAREHOUSE_MANAGER`, `ROLE_WAREHOUSE_STAFF` | Lấy danh sách nhóm hàng đã mua trong đơn hàng gần nhất của đại lý (S4-04) |
| POST | `/dealers/{id}/last-order-to-draft` | `ORDER_CREATE`, `ROLE_SALES`, `ROLE_SALES_MANAGER`, `ROLE_ADMIN`, `ROLE_WAREHOUSE_MANAGER`, `ROLE_WAREHOUSE_STAFF` | Thêm nhanh cả nhóm hàng đã mua lần trước vào bản nháp tạo đơn của người dùng (S4-04) |

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
| GET | `/dealers/{id}/debt` | `ORDER_CREATE` hoặc `FINANCE_VIEW_DEBT` | Lấy chi tiết công nợ, hạn mức, số ngày quá hạn và trạng thái cho phép đặt hàng của đại lý (áp dụng Row-Level Security cho `ROLE_SALES`). Alias: `GET /dealers/{id}/credit-info` |
| POST | `/orders/check-debt` | `ORDER_CREATE` hoặc `FINANCE_VIEW_DEBT` | Kiểm tra sơ bộ công nợ và dự phóng trước khi đặt đơn (pre-check trên giao diện tạo đơn) |
| GET | `/orders/check-debt` | `ORDER_CREATE` hoặc `FINANCE_VIEW_DEBT` | Tra cứu nhanh hạn mức công nợ theo query param `dealerId` và `orderAmount` |

#### Kết nối màn hình tạo đơn và giới hạn hiện tại (kiểm tra 14/10/2026)

- Frontend S4-02 gọi `GET /api/dealers/{id}/debt`, đọc `hasOverdueDebt`, `isBlocked` và `blockReason`, sau đó gọi `POST /api/orders/check-debt` mỗi khi tổng đơn thay đổi.
  Không có API `/api/finance/debt/{id}/check`, cũng không có trường `oldestOverdueDays` trong DTO này.
- Nếu API công nợ lỗi, frontend hiển thị lý do và chặn chốt đơn cho tới khi tải lại được dữ liệu.
- `currentDebt` hiện được suy ra từ tổng đơn `RECORDED`. Chưa có sổ công nợ/phiếu thu/phân bổ thanh toán,
  nên chưa thể xác định số dư còn phải thu sau khi khách thanh toán. Không dùng kết quả này như số dư kế toán đã đối soát.
- `debtLimit = 0` hiện không kích hoạt kiểm tra vượt hạn mức; `maxDebtDays = 0` không kích hoạt kiểm tra quá hạn.
- Kiểm tra công nợ không thay thế kiểm tra tồn. Với tính năng S5-06, đơn hàng khi được Quản lý duyệt sẽ tự động kích hoạt giữ chỗ tồn kho trong DB (`reservedQuantity`), đảm bảo hàng không bị bán mất.
- Chi tiết các API đã nối và các tiêu chí Sprint 4: [Báo cáo Sprint 4](../docs/SPRINT_4_AUDIT_20261014.md).

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

### Hiển thị tồn khả dụng và chặn đặt vượt tồn kho phục vụ (S4-03 - SCRUM-83)

Hỗ trợ Nhân viên kinh doanh (`ROLE_SALES`) nhìn thấy tồn khả dụng và bị chặn khi đặt vượt tồn kho phục vụ của đại lý, nhằm tránh việc hứa hẹn với đại lý mặt hàng mà kho đã hết hoặc không đủ giao.

#### Quy tắc nghiệp vụ cốt lõi:
1. **Công thức tồn khả dụng**:
   - `Tồn khả dụng (availableQuantity) = max(0, Tồn thực tế (baseQuantity) - Tồn đang giữ chỗ (reservedQuantity))`
   - Tất cả phép tính tồn kho được quy chuẩn về đơn vị cơ sở (`baseUnit`, ví dụ: lon, chai, chiếc).
   - Khi trả về cho người dùng hoặc màn hình đặt hàng, hệ thống hỗ trợ quy đổi tồn khả dụng theo đơn vị đã chọn (`availableInUnit`) và số lượng nguyên tối đa còn đặt được (`maxAllowedInUnit = floor(availableQuantity / factor)`).

2. **Kho phục vụ của đại lý**:
   - Mỗi đại lý có một kho phục vụ mặc định được cấu hình trong hồ sơ đại lý (`warehouseId` / `warehouseCode` / `warehouseName`).
   - Khi tạo đơn hàng (`POST /api/orders`) hoặc tính toán đơn hàng (`POST /api/orders/calculate`), nếu request không chỉ định `warehouseId`, hệ thống sẽ tự động ưu tiên lấy kho phục vụ của đại lý; nếu đại lý chưa gán kho thì lấy kho đang hoạt động đầu tiên.

3. **Chặn đặt vượt tồn khả dụng & Gợi ý số lượng tối đa**:
   - Khi chốt đơn (`POST /api/orders`):
     + Nếu số lượng đặt quy về đơn vị cơ sở vượt quá tồn khả dụng tại kho phục vụ: Hệ thống chặn và trả về lỗi **HTTP 400 Bad Request**.
     + Thông báo lỗi hiển thị rõ ràng: mặt hàng, mã hàng, kho phục vụ, tồn khả dụng hiện tại, và gợi ý số lượng tối đa có thể đặt theo đơn vị tính đã chọn (ví dụ: *"Vượt tồn khả dụng cho sản phẩm 'Bia Heineken' (mã: SP01) tại kho 'Kho Tổng'. Tồn khả dụng hiện tại: 48 lon. Số lượng tối đa có thể đặt là 2 thùng."*).
     + Nếu tồn khả dụng bằng 0: Thông báo rõ *"Mặt hàng '...' đã hết tồn khả dụng trong kho phục vụ '...'. Số lượng tối đa có thể đặt là 0 ...".*

4. **Giữ chỗ và Giải phóng tồn kho (Vòng đời đơn hàng)**:
   - **Khi tạo đơn hợp lệ ghi nhận ngay (`RECORDED`)**: tăng `reservedQuantity` và đánh dấu `inventoryReserved = true`. Đơn chờ duyệt (`PENDING_APPROVAL`) chưa giữ chỗ; lúc được duyệt phải kiểm tra lại tồn và giữ chỗ nguyên tử (xem S5-06).
   - **Khi xuất đơn** (`PUT /api/orders/{id}/status` với `EXPORTED`, sau `PICKING`): trừ tồn thực tế và lượng giữ riêng của đơn trong cùng giao dịch; không lấy lượng giữ của đơn khác. Phiếu xuất thủ công phải kiểm tra tồn khả dụng, không phải đường để nhả giữ chỗ của đơn.
   - **Khi huỷ đơn** (`PUT /api/orders/{id}/cancel`) hoặc **từ chối đơn** (`PUT /api/orders/{id}/reject`): Nếu đơn đã được giữ chỗ (`inventoryReserved = true`), hệ thống tự động hoàn trả lại tồn giữ chỗ (`reservedQuantity = reservedQuantity - baseQuantity`) và cập nhật `inventoryReserved = false`.

5. **Chống Race Condition / Bán âm kho khi chốt đơn đồng thời (Concurrency Safety)**:
   - Sử dụng cơ chế khóa bi quan `LockModeType.PESSIMISTIC_WRITE` (`SELECT ... FOR UPDATE`) trên bảng `warehouse_inventories`.
   - Khóa dòng đơn trước, sau đó các dòng sản phẩm theo thứ tự `productId` tăng dần để giảm nguy cơ deadlock giữa các luồng giữ/nhả/xuất tồn.
   - Khi 2 người dùng (hoặc 2 tiến trình) cùng chốt đơn mặt hàng sắp hết: Người chốt trước giữ chỗ thành công (HTTP 201 Created), người chốt sau ngay lập tức bị chặn với HTTP 400 Bad Request, tồn kho thực tế và tồn khả dụng tuyệt đối không bao giờ bị âm (`acceptedOrders <= 1`).

6. **Phân quyền theo ma trận quyền**:
   - Người dùng có `ORDER_CREATE` hoặc `WAREHOUSE_VIEW` trong ma trận được phép tra cứu danh sách kho hoạt động (`GET /api/warehouse/options`) và danh sách tồn kho (`GET /api/warehouse/inventory`). `ROLE_ADMIN` luôn được phép.
   - Thao tác điều chỉnh tồn kho (`POST /api/warehouse/adjust`) vẫn nghiêm ngặt chỉ dành cho Quản lý kho (`ROLE_WAREHOUSE_MANAGER`) và Quản trị viên (`ROLE_ADMIN`).

| Method | Endpoint | Quyền | Mô tả |
|---|---|---|---|
| POST | `/orders/check-inventory` | `ORDER_CREATE` hoặc `WAREHOUSE_VIEW` từ ma trận phân quyền (`ROLE_ADMIN` luôn được phép) | Kiểm tra nhanh tồn khả dụng và gợi ý số lượng tối đa trước khi đặt hàng |
| POST | `/orders/calculate` | `ORDER_CREATE` | Tính toán giá, chiết khấu và tồn khả dụng (`availableQuantity`, `availableInUnit`, `maxAllowedInUnit`, `exceedsStock`) cho từng dòng đơn |
| GET | `/warehouse/inventory` | `ORDER_CREATE` hoặc `WAREHOUSE_VIEW` từ ma trận phân quyền (`ROLE_ADMIN` luôn được phép) | Xem tồn thực tế, tồn giữ chỗ và tồn khả dụng kèm quy đổi tương đương |
| GET | `/warehouse/options` | `ORDER_CREATE` hoặc `WAREHOUSE_VIEW` từ ma trận phân quyền (`ROLE_ADMIN` luôn được phép) | Danh sách kho đang hoạt động |

#### Ví dụ Request kiểm tra tồn khả dụng (`POST /api/orders/check-inventory`):
```json
{
  "warehouseId": 1,
  "dealerId": 5,
  "productId": 10,
  "unit": "thùng",
  "quantity": 3
}
```

#### Ví dụ Response khi còn đủ tồn khả dụng:
```json
{
  "success": true,
  "message": "Kiểm tra tồn khả dụng thành công",
  "data": {
    "warehouseId": 1,
    "warehouseCode": "KHO-TONG",
    "warehouseName": "Kho Tổng Hà Nội",
    "productId": 10,
    "productCode": "SP-KEN330",
    "productName": "Bia Heineken Sleek lon 330ml",
    "unit": "thùng",
    "quantity": 2,
    "conversionFactor": 24,
    "baseUnit": "lon",
    "baseQuantity": 48,
    "physicalQuantity": 100,
    "reservedQuantity": 10,
    "availableQuantity": 90,
    "availableInUnit": 3.75,
    "maxAllowedInUnit": 3,
    "valid": true,
    "message": "Còn đủ tồn khả dụng trong kho 'Kho Tổng Hà Nội'. Tồn khả dụng: 3.75 thùng (90 lon)."
  }
}
```

#### Ví dụ Response khi vượt tồn khả dụng:
```json
{
  "success": true,
  "message": "Kiểm tra tồn khả dụng thành công",
  "data": {
    "warehouseId": 1,
    "warehouseCode": "KHO-TONG",
    "warehouseName": "Kho Tổng Hà Nội",
    "productId": 10,
    "productCode": "SP-KEN330",
    "productName": "Bia Heineken Sleek lon 330ml",
    "unit": "thùng",
    "quantity": 5,
    "conversionFactor": 24,
    "baseUnit": "lon",
    "baseQuantity": 120,
    "physicalQuantity": 60,
    "reservedQuantity": 12,
    "availableQuantity": 48,
    "availableInUnit": 2.0,
    "maxAllowedInUnit": 2,
    "valid": false,
    "message": "Vượt tồn khả dụng cho sản phẩm 'Bia Heineken Sleek lon 330ml' (mã: SP-KEN330) tại kho 'Kho Tổng Hà Nội'. Tồn khả dụng hiện tại: 48 lon. Số lượng tối đa có thể đặt là 2 thùng."
  }
}
```

## Quản lý danh mục kho & Vị trí lưu kho (S5-03)

Hệ thống cho phép Quản lý kho (`ROLE_WAREHOUSE_MANAGER`) và Quản trị viên (`ROLE_ADMIN`) khai báo nhiều kho, phân cấp vị trí lưu trữ (Khu vực `ZONE`, Kệ `SHELF`), gán sản phẩm vào vị trí, và gán kho phục vụ mặc định cho từng đại lý để phục vụ xuất danh sách soạn hàng (Picking List) chính xác.

### Danh mục kho hàng

| Method | Endpoint | Quyền | Mô tả |
|---|---|---|---|
| GET | `/api/warehouses` | Xem danh mục kho | Lấy danh sách kho hàng kèm bộ lọc `keyword`, `active` |
| GET | `/api/warehouses/{id}` | Xem chi tiết kho | Xem chi tiết thông tin kho hàng |
| POST | `/api/warehouses` | `WAREHOUSE_MANAGE` hoặc `ADMIN` | Tạo mới kho hàng (mã, tên, địa chỉ, người phụ trách, trạng thái) |
| PUT | `/api/warehouses/{id}` | `WAREHOUSE_MANAGE` hoặc `ADMIN` | Cập nhật thông tin kho hàng |
| DELETE | `/api/warehouses/{id}` | `WAREHOUSE_MANAGE` hoặc `ADMIN` | Xóa kho hàng (tự động chuyển `active = false` nếu đã có phiếu kho hoặc vị trí lưu) |
| GET | `/api/warehouses/{id}/locations` | Xem vị trí kho | Lấy danh sách vị trí lưu trữ của một kho |
| GET | `/api/warehouses/{id}/tree` | Xem vị trí kho | Lấy cấu trúc cây phân cấp vị trí (Khu vực -> Kệ) |

#### Tạo mới kho hàng:
```http
POST /api/warehouses
Content-Type: application/json

{
  "code": "KHO-HN",
  "name": "Kho Hà Nội",
  "address": "123 Phố Huế, Q. Hai Bà Trưng, Hà Nội",
  "managerUserId": 2,
  "managerName": "Trần Quản Lý",
  "active": true
}
```

### Vị trí lưu kho (Khu vực / Kệ)

| Method | Endpoint | Quyền | Mô tả |
|---|---|---|---|
| GET | `/api/warehouse-locations` | Xem vị trí kho | Lấy danh sách vị trí kho kèm bộ lọc `warehouseId`, `locationType`, `parentId`, `active`, `keyword` |
| GET | `/api/warehouse-locations/{id}` | Xem vị trí kho | Xem chi tiết vị trí, danh sách kệ con (nếu là Khu), và sản phẩm đang lưu |
| POST | `/api/warehouse-locations` | `WAREHOUSE_MANAGE` hoặc `ADMIN` | Khai báo vị trí lưu mới (`ZONE` hoặc `SHELF` trực thuộc `ZONE`) |
| PUT | `/api/warehouse-locations/{id}` | `WAREHOUSE_MANAGE` hoặc `ADMIN` | Cập nhật vị trí lưu kho |
| DELETE | `/api/warehouse-locations/{id}` | `WAREHOUSE_MANAGE` hoặc `ADMIN` | Xóa vị trí lưu kho (chặn xóa nếu có kệ con hoặc sản phẩm đang gán) |
| POST | `/api/warehouse-locations/{locationId}/products` | `WAREHOUSE_MANAGE` hoặc `ADMIN` | Gán sản phẩm vào vị trí kệ/khu (hỗ trợ cờ `isDefault`) |
| DELETE | `/api/warehouse-locations/{locationId}/products/{productId}` | `WAREHOUSE_MANAGE` hoặc `ADMIN` | Hủy gán sản phẩm khỏi vị trí kệ/khu |
| GET | `/api/warehouse-locations/{locationId}/products` | Xem vị trí kho | Lấy danh sách sản phẩm được gán tại vị trí |

#### Khai báo vị trí Kệ thuộc Khu:
```http
POST /api/warehouse-locations
Content-Type: application/json

{
  "warehouseId": 1,
  "code": "SHELF-A1",
  "name": "Kệ A1 - Tầng 2",
  "locationType": "SHELF",
  "parentLocationId": 10,
  "description": "Kệ đồ uống giải khát",
  "active": true
}
```

#### Gán sản phẩm vào Kệ lưu trữ:
```http
POST /api/warehouse-locations/15/products
Content-Type: application/json

{
  "productId": 10,
  "isDefault": true,
  "note": "Kệ chính lấy hàng bia Sleek"
}
```

### Danh sách soạn hàng (Picking List) & Gán kho mặc định đại lý

| Method | Endpoint | Quyền | Mô tả |
|---|---|---|---|
| GET | `/api/orders/{id}/picking-list` | `ORDER_VIEW` hoặc thủ kho | Xuất danh sách soạn hàng hiển thị chính xác tên Kệ, Khu vực, tồn khả dụng |
| PUT | `/api/dealers/{id}/default-warehouse` | `DEALER_MANAGE`, `WAREHOUSE_MANAGE` hoặc `ADMIN` | Gán kho phục vụ mặc định cho đại lý |

#### Ví dụ Response Picking List (`GET /api/orders/1001/picking-list`):
```json
{
  "success": true,
  "message": "Lấy danh sách soạn hàng thành công",
  "data": {
    "orderId": 1001,
    "orderCode": "ORD-20261018-001",
    "orderStatus": "PENDING",
    "expectedDeliveryDate": "2026-10-20",
    "dealerId": 5,
    "dealerCode": "DL-HN",
    "dealerName": "Đại lý Hà Nội",
    "warehouseId": 1,
    "warehouseCode": "KHO-HCM",
    "warehouseName": "Kho Tổng TP.HCM",
    "warehouseAddress": "Lô 5 KCN Tân Bình, TP.HCM",
    "items": [
      {
        "orderItemId": 501,
        "productId": 10,
        "productCode": "SP-KEN330",
        "productName": "Bia Heineken Sleek lon 330ml",
        "unit": "thùng",
        "quantity": 10,
        "locationId": 202,
        "locationCode": "SHELF-A1",
        "shelfName": "Kệ A1 - Tầng 2",
        "zoneName": "Khu Đồ Uống",
        "locationDisplay": "Khu Đồ Uống > Kệ A1 - Tầng 2",
        "availableQuantity": 500
      }
    ]
  }
}
```

### Giữ chỗ tồn kho khi đơn được duyệt & Tự động nhả tồn quá hạn / huỷ đơn (S5-06)

Hỗ trợ Nhân viên kinh doanh có tồn được giữ chỗ ngay khi đơn được duyệt, để chắc chắn hàng đã chốt cho khách của mình không bị người khác bán mất.

#### Quy tắc nghiệp vụ cốt lõi:
1. **Giữ chỗ tồn kho ngay khi đơn được duyệt (`approveOrder`)**:
   - Khi đơn hàng tạo ở trạng thái Chờ duyệt (`PENDING_APPROVAL`, ví dụ do bán dưới giá sàn hoặc vượt hạn mức công nợ), tồn kho chưa bị trừ và chưa bị giữ chỗ (`inventoryReserved = false`).
   - Khi người dùng được cấp `ORDER_APPROVE` từ ma trận DB hoặc Quản trị viên (`ROLE_ADMIN`) phê duyệt đơn (`PUT /api/orders/{id}/approve`, hoặc `PATCH /api/orders/{id}/status` với `APPROVED`):
     - Khóa ghi dòng đơn hàng trước, sau đó khóa các dòng tồn tại kho phục vụ theo `productId` tăng dần. Duyệt, hủy, hết hạn và xuất kho cùng khóa dòng đơn nên không giữ/nhả hai lần khi chạy đồng thời. Vai trò không thay thế quyền; người được gán kho chỉ thao tác trong kho được gán.
     - Nếu tồn khả dụng không đủ: ném ngoại lệ `IllegalArgumentException` kèm thông báo chi tiết, giao dịch `@Transactional` tự động hoàn lại (rollback) toàn bộ, không thay đổi trạng thái đơn và không giữ chỗ tồn kho.
     - Nếu tồn khả dụng đủ: hệ thống tự động tăng `reservedQuantity` tại kho tương ứng với số lượng cơ sở của đơn hàng, chuyển trạng thái đơn sang `RECORDED` (Đã duyệt / Đã ghi sổ), đánh dấu `inventoryReserved = true`, gán thời điểm giữ chỗ `reservedAt` và thời điểm hết hạn giữ chỗ `reservationExpiresAt`.
     - `reservationHours` phải lớn hơn 0 (ví dụ `?reservationHours=24`). Nếu không truyền: dùng cuối ngày giao dự kiến khi còn ở tương lai, nếu không thì 48 giờ. Không có cấu hình `app.order.default-reservation-hours`. Ngày hết hạn tường minh khi tạo đơn phải ở tương lai.

2. **Giao dịch `@Transactional` nguyên tử (Atomic Transaction & Rollback)**:
   - Toàn bộ thao tác kiểm tra tồn kho, tăng tồn giữ chỗ (`reservedQuantity`), cập nhật trạng thái đơn hàng (`RECORDED`), và ghi nhật ký kiểm toán (`AuditLog`) diễn ra trong cùng một transaction `@Transactional`.
   - Bất kỳ lỗi nào xảy ra giữa chừng đều làm toàn bộ giao dịch rollback, đảm bảo tính toàn vẹn dữ liệu tuyệt đối (không có tình trạng đơn đã duyệt nhưng chưa giữ chỗ, hoặc ngược lại).

3. **Tự động giải phóng tồn kho khi huỷ hoặc từ chối đơn**:
   - Khi huỷ đơn (`PUT /api/orders/{id}/cancel`, bắt buộc lý do) hoặc từ chối đơn chờ duyệt (`PUT /api/orders/{id}/reject`): khóa dòng đơn và tồn, chỉ trừ chính xác lượng đã giữ của đơn. Nếu sổ giữ chỗ không khớp, từ chối và rollback thay vì ép về 0 làm mất giữ chỗ của đơn khác. Cập nhật `inventoryReserved = false`, xóa `reservationExpiresAt` trong cùng giao dịch; đã xuất kho không được hủy. Giữ chỗ không giảm tồn vật lý; xuất kho mới giảm tồn vật lý và lượng giữ tương ứng.

4. **Tự động quét và nhả tồn khi đơn quá hạn giữ chỗ (Background Scheduler)**:
   - Background Task (`OrderReservationScheduler`) chạy định kỳ (mặc định mỗi phút một lần qua cấu hình `app.order.reservation-check-interval-ms:60000`).
   - Scheduler tự động tìm tất cả các đơn hàng có `inventoryReserved = true` và `reservationExpiresAt <= now()`.
   - Với mỗi đơn quá hạn: hệ thống tự động giải phóng tồn giữ chỗ (`reservedQuantity`), chuyển trạng thái đơn hàng sang `EXPIRED`, xóa cờ giữ chỗ, ghi chú tự động lý do hết hạn và lưu vết nhật ký kiểm toán (`AuditLog`).
   - Quét ID rồi xử lý từng đơn bằng giao dịch riêng (`REQUIRES_NEW`), đọc lại cờ/hạn/trạng thái sau khi khóa; worker khác, hủy hoặc xuất đã xử lý thì bỏ qua. Không gom khóa nhiều dòng đơn vào một giao dịch quét dài. Thời gian trong ghi chú là hạn gốc trước khi xóa.
   - API thủ công `POST /api/orders/release-expired-reservations` kiểm quyền DB và phạm vi kho/đại lý của người gọi; không nhả đơn ngoài phạm vi. Scheduler nội bộ tự xử lý toàn hệ thống. Lỗi giao dịch giữ nguyên đơn/tồn của đơn lỗi; các đơn đã xử lý ở giao dịch riêng không bị hoàn lại.

| Method | Endpoint | Quyền | Mô tả |
|---|---|---|---|
| PUT | `/orders/{id}/approve?reservationHours={hours}` | `ORDER_APPROVE` hoặc ADMIN | Phê duyệt đơn hàng chờ duyệt trong phạm vi, giữ chỗ và thiết lập thời hạn; HTTP 200 |
| PATCH | `/orders/{id}/status` | `ORDER_APPROVE` hoặc ADMIN | Body `{"status":"APPROVED","comment":"..."}`; dùng cùng nghiệp vụ giữ chỗ như PUT approve |
| PUT | `/orders/{id}/confirm` | `ORDER_CONFIRM` hoặc ADMIN | Xác nhận đơn cổng không vi phạm của đại lý được phân công; cũng giữ chỗ nguyên tử |
| PUT | `/orders/{id}/cancel` | `ORDER_CANCEL` hoặc ADMIN | Huỷ đơn trong phạm vi; nhả giữ chỗ chính xác một lần |
| PUT | `/orders/{id}/reject` | `ORDER_APPROVE` hoặc ADMIN | Từ chối đơn hàng chờ duyệt; không cho từ chối lại đơn đã duyệt |
| POST | `/orders/release-expired-reservations` | Một trong `ORDER_APPROVE`, `ORDER_CANCEL`, `WAREHOUSE_APPROVE`, hoặc ADMIN | Quét và nhả các đơn quá hạn trong phạm vi người gọi |

Frontend: trang Duyệt đơn cần cả `ORDER_VIEW` và `ORDER_APPROVE`; tạo đơn chỉ cần `ORDER_CREATE`, đọc tồn qua `/orders/check-inventory`, không tải danh sách đơn để tự cộng giữ chỗ. Chi tiết và tracking đọc `inventoryReserved`, `reservedAt`, `reservationExpiresAt` từ server. Hạn đã qua nhưng cờ còn true hiển thị “Quá hạn — chờ hệ thống nhả tồn”; đồng hồ trình duyệt không sửa tồn hoặc tự báo đã nhả. Thu hồi quyền vô hiệu hóa/ẩn hành động và bỏ phản hồi cũ, backend từ chối JWT hiện có ở request kế tiếp. ADMIN luôn được phép.

Schema bổ sung `orders.version BIGINT NOT NULL DEFAULT 0` để tránh cập nhật cũ ghi đè thao tác đồng thời. Cấu hình phát triển `ddl-auto=update` thêm cột khi khởi động lại backend; môi trường quản lý migration phải thêm cột tương đương trước khi triển khai. Không xóa/reset DB. Dòng tồn được cập nhật qua JPA dưới khóa, nên tăng `warehouse_inventories.version` và vô hiệu hóa snapshot kiểm kê cũ đúng cách.

#### Ví dụ Response phê duyệt đơn hàng kèm giữ chỗ tồn kho (`PUT /api/orders/1/approve?reservationHours=24`):
```json
{
  "success": true,
  "message": "Phê duyệt đơn hàng thành công",
  "data": {
    "id": 1,
    "orderCode": "DH-20261018-0001",
    "status": "RECORDED",
    "inventoryReserved": true,
    "reservedAt": "2026-10-18T07:00:00",
    "reservationExpiresAt": "2026-10-19T07:00:00",
    "approvedBy": "sales.manager",
    "approvedAt": "2026-10-18T07:00:00",
    "totalAmount": 15000000.0
  }
}
```

### Xem Lịch sử mua hàng & Thêm nhanh nhóm hàng mua lần trước vào đơn mới (S4-04)

Hỗ trợ Nhân viên kinh doanh xem lịch sử mua hàng của đại lý ngay khi gõ đơn, giúp gợi ý đúng các mặt hàng khách hàng vẫn lấy đều đặn thay vì phải hỏi lại, đồng thời hỗ trợ thêm nhanh toàn bộ nhóm hàng đã mua trong đơn hàng gần nhất vào đơn mới.

#### Quy tắc nghiệp vụ cốt lõi:
1. **Lịch sử mua hàng 3 tháng gần nhất & Số lượng bình quân**:
   - Khoảng thời gian mặc định: 3 tháng gần nhất tính từ thời điểm hiện tại (`months = 3`). Hỗ trợ tuỳ chỉnh số tháng qua tham số query `?months={n}`.
   - Chỉ tổng hợp các đơn hàng hợp lệ đã ghi sổ (`RECORDED`). Đơn hàng bị huỷ (`CANCELLED`), bị từ chối (`REJECTED`) hoặc đang chờ duyệt (`PENDING_APPROVAL`) không được tính vào lịch sử mua hàng.
   - Tính toán số lượng bình quân (`avgQty`): Số lượng bình quân mỗi đơn mà đại lý đã đặt cho từng sản phẩm trong kỳ (`totalQuantity / orderCount`), được quy về đơn vị đặt hàng phổ biến/gần nhất (ví dụ: thùng, lốc, lon, cái).
2. **Thêm nhanh nhóm hàng mua lần trước**:
   - Hệ thống tự động xác định đơn hàng hợp lệ gần nhất của đại lý và đánh dấu cờ `inLastOrder = true` cho các mặt hàng thuộc đơn này.
   - Các mặt hàng thuộc đơn gần nhất được sắp xếp ưu tiên hiển thị lên đầu danh sách để nhân viên kinh doanh dễ nhận biết.
   - Hỗ trợ lấy riêng nhóm hàng đơn gần nhất qua:
     - `GET /api/dealers/{id}/purchase-history?lastOrderOnly=true`
     - `GET /api/dealers/{id}/last-order-items`
   - Hỗ trợ nạp thẳng nhóm hàng đơn gần nhất vào bản nháp tạo đơn qua:
     - `POST /api/dealers/{id}/last-order-to-draft`
     - `POST /api/orders/dealer/{dealerId}/last-order-to-draft`
     Khi nạp vào bản nháp, hệ thống tự động áp lại bảng giá và chính sách chiết khấu sản lượng hiện hành theo S4-01/S4-09.
3. **Phân quyền bảo mật theo đại lý phụ trách (Row-Level Security - RLS)**:
   - Nhân viên kinh doanh (`ROLE_SALES`) **chỉ được phép xem lịch sử và thêm nhanh đơn của đại lý do chính mình phụ trách** (`dealer.assignedUser`).
   - Nếu cố tình truy cập đại lý của nhân viên khác hoặc đại lý chưa phân công, hệ thống sẽ từ chối truy cập và trả về mã lỗi `403 Forbidden`.
   - Quản lý kinh doanh (`ROLE_SALES_MANAGER`) và Quản trị viên (`ROLE_ADMIN`) có quyền xem lịch sử của mọi đại lý trên toàn hệ thống.

#### Ví dụ Request lấy lịch sử mua hàng (`GET /api/dealers/1/purchase-history?months=3`):
```http
GET /api/dealers/1/purchase-history?months=3
Authorization: Bearer <token_nhan_vien_sales>
```
Response:
```json
{
  "success": true,
  "message": "Lấy lịch sử mua hàng của đại lý thành công",
  "data": [
    {
      "productId": 10,
      "sku": "HEINEKEN-LON",
      "code": "HEINEKEN-LON",
      "name": "Bia Heineken Lon 330ml",
      "unit": "thùng",
      "avgQty": 15,
      "orderCount": 2,
      "totalQuantity": 30,
      "lastOrderDate": "2026-10-10",
      "lastOrderQuantity": 10,
      "lastOrderUnit": "thùng",
      "inLastOrder": true,
      "lastOrderCode": "DH-20261010-001"
    },
    {
      "productId": 20,
      "sku": "TIGER-LON",
      "code": "TIGER-LON",
      "name": "Bia Tiger Lon 330ml",
      "unit": "thùng",
      "avgQty": 5,
      "orderCount": 1,
      "totalQuantity": 5,
      "lastOrderDate": "2026-10-10",
      "lastOrderQuantity": 5,
      "lastOrderUnit": "thùng",
      "inLastOrder": true,
      "lastOrderCode": "DH-20261010-001"
    }
  ]
}
```

#### Ví dụ Request thêm nhanh nhóm hàng mua lần trước vào bản nháp (`POST /api/dealers/1/last-order-to-draft`):
```http
POST /api/dealers/1/last-order-to-draft
Authorization: Bearer <token_nhan_vien_sales>
```
Response:
```json
{
  "success": true,
  "message": "Đã thêm nhanh nhóm hàng mua lần trước vào bản nháp tạo đơn",
  "data": {
    "dealerId": 1,
    "deliveryPointId": 2,
    "deliveryDate": null,
    "note": "",
    "items": [
      {
        "_key": "copy-1001-0",
        "productId": 10,
        "selectedUnitId": "thùng",
        "qty": 10,
        "basePrice": 0,
        "unitPrice": 0,
        "floorPrice": 0,
        "manualPrice": false,
        "discountAmount": 0
      }
    ],
    "_seq": 1
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

---

## Các API Bổ sung Sprint 4 (Quản lý Đơn hàng Nâng cao & Phê duyệt)

### 1. Phê duyệt đơn hàng vượt hạn mức / bán dưới giá sàn (S4-05)
- **PATCH `/api/orders/{id}/status`**: Cập nhật trạng thái duyệt đơn (`APPROVED`, `REJECTED`, `RETURNED`).
  - Quyền: `ORDER_APPROVE`, `ROLE_SALES_MANAGER`, `ROLE_ADMIN`.
  - Body: `{ "status": "APPROVED|REJECTED|RETURNED", "comment": "Ý kiến phê duyệt" }`.
  - Quy tắc: Khi `REJECTED` hoặc `RETURNED`, bắt buộc phải nhập `comment` (nếu thiếu hoặc rỗng sẽ trả về `400 Bad Request`).
  - Đơn được duyệt mới chuyển sang trạng thái giữ chỗ tồn kho (`RECORDED`) và cho phép xuất kho.
  - Lịch sử phê duyệt được ghi nhận vào nhật ký kiểm toán hệ thống (`audit_logs`) không cho phép chỉnh sửa/xoá.
- **PUT `/api/orders/{id}/approve`**: Phê duyệt nhanh đơn hàng kèm ý kiến.
- **PUT `/api/orders/{id}/reject`**: Từ chối đơn hàng kèm ý kiến bắt buộc.
- **PUT `/api/orders/{id}/return`**: Trả lại đơn để nhân viên kinh doanh chỉnh sửa kèm ý kiến bắt buộc.

### 2. Huỷ đơn hàng có lý do bắt buộc (S4-06)
- **PUT `/api/orders/{id}/cancel`**: Huỷ đơn hàng.
  - Quyền: `ORDER_CANCEL` trong ma trận quyền hoặc `ROLE_ADMIN`.
  - Query params hoặc Body: `reason` (hoặc JSON `{ "reason": "Lý do huỷ" }`). Bắt buộc phải có lý do (không được để trống).
  - Quy tắc chặn: Không được phép huỷ đơn hàng đã xuất kho (`EXPORTED`) hoặc đã giao hàng (`DELIVERED`).
  - Lưu trữ: Lưu `cancelReason`, `cancelledBy`, `cancelledAt` vào bảng `orders` và ghi nhật ký kiểm toán.

### 3. Tra cứu lịch sử mua hàng của Đại lý (S4-04)
- **GET `/api/dealers/{id}/purchase-history?months=3`**: Tra cứu các mặt hàng đại lý thường mua trong N tháng gần nhất.
  - Quyền: `ROLE_SALES`, `ROLE_SALES_MANAGER`, `ROLE_ADMIN`, `ROLE_ACCOUNTANT`, `ROLE_VIEWER`.
  - Hỗ trợ nhân viên kinh doanh gợi ý nhanh sản phẩm khi lên đơn mới.
  - Response: Danh sách sản phẩm kèm tổng số lượng quy về đơn vị chuẩn, đơn vị cơ sở, số lượng trung bình tháng, số lần mua, giá mua gần nhất và top 5 đơn hàng gần nhất.

### 4. Sao chép đơn hàng (S4-09)
- **POST `/api/orders/{id}/copy`**: Tạo đơn hàng mới từ một đơn hàng đã có trong quá khứ.
  - Quyền: `ROLE_SALES`, `ROLE_SALES_MANAGER`, `ROLE_ADMIN`.
  - Quy tắc: Giữ nguyên đại lý, điểm giao hàng và số lượng dòng hàng; tự động áp bảng giá hiệu lực hiện hành và tính lại chiết khấu sản lượng. Kiểm tra chặn nếu đại lý đang bị khoá giao dịch.
  - Response: Đơn hàng mới ở trạng thái `RECORDED` hoặc `PENDING_APPROVAL` (nếu vượt hạn mức / dưới sàn).

### 5. In phiếu đặt hàng (S4-08)
- **GET `/api/orders/{id}/print`**: Xuất bản in phiếu đặt hàng định dạng HTML chuyên nghiệp sẵn sàng in hoặc lưu PDF (`window.print()`).
  - Content-Type: `text/html;charset=UTF-8`.
  - Nội dung: Thông tin công ty, mã đơn hàng, ngày tạo, thông tin đại lý, địa chỉ giao hàng, bảng chi tiết sản phẩm, đơn vị, đơn giá, chiết khấu, thành tiền, tổng thanh toán và các ô ký nhận.

### 6. Bộ lọc đơn hàng nâng cao (S4-07)
- **GET `/api/orders`**: Hỗ trợ đầy đủ bộ lọc kết hợp:
  - `status`: Trạng thái đơn (`DRAFT`, `PENDING_APPROVAL`, `RECORDED`, `CANCELLED`, `EXPORTED`, `DELIVERED`, ...).
  - `approvalReason`: Lý do cần duyệt (`BELOW_FLOOR_PRICE`, `OVER_DEBT_LIMIT`, ...).
  - `approvalRequired`: `true/false` (lọc các đơn cần duyệt).
  - `search`: Tìm kiếm theo mã đơn hoặc tên/mã đại lý.
  - `dealerId`: Lọc theo ID đại lý.
  - `createdBy`: Lọc theo tên người tạo đơn.
  - `territoryId`: Lọc theo khu vực/địa bàn của đại lý.
  - `dateFrom` & `dateTo`: Khoảng ngày tạo đơn (`yyyy-MM-dd`).
  - `page`, `size`, `sort`: Phân trang và sắp xếp.



## Cổng Đại lý (Dealer Portal) — Sprint 5 (S5-01)

Base URL: `http://localhost:8080/api`

Cung cấp tính năng dành riêng cho Đại lý (`ROLE_DEALER`) theo dõi trạng thái đơn hàng và tiến trình giao nhận hàng hóa của chính mình mà không cần gọi điện hỏi nhân viên kinh doanh.

### Nguyên tắc bảo mật & Cô lập dữ liệu (Row-Level Security)
- Tài khoản đại lý được liên kết với một bản ghi `Dealer` thông qua trường `dealer_id` trên thực thể User.
- API lấy danh sách đơn `/api/portal/orders` và API xem chi tiết `/api/portal/orders/{id}` giới hạn theo `dealer_id` liên kết của DEALER; ADMIN chọn ngữ cảnh bằng query `dealerId` (xem hợp đồng ngày 19/10/2026 ở đầu tài liệu).
- Nếu người dùng thuộc một đại lý cố tình truy cập vào ID đơn hàng của đại lý khác, server sẽ chặn và trả về lỗi `403 Forbidden` (`Bạn không có quyền thực hiện chức năng này`).
- Nhân viên kinh doanh (`ROLE_SALES`) chỉ xem được đơn hàng của các đại lý mà mình được phân công phụ trách.
- Quản trị viên (`ROLE_ADMIN`) và Quản lý kinh doanh (`ROLE_SALES_MANAGER`) có thể xem toàn bộ đơn hàng.
- `ROLE_DEALER` được cấp quyền mặc định trong Ma trận phân quyền (`RolePermissionConfig`): `ORDER_VIEW`, `ORDER_CREATE`. Tuyệt đối không có quyền thao tác kho hay xem giá vốn.

### Danh sách API

| Method | Endpoint | Quyền | Mô tả |
|---|---|---|---|
| GET | `/api/portal/orders` | `ROLE_DEALER`, `ROLE_ADMIN` | Danh sách đơn hàng của đại lý: trạng thái, ngày giao dự kiến, tổng tiền, cảnh báo giao thiếu |
| GET | `/api/portal/orders/{id}` | `ROLE_DEALER`, `ROLE_ADMIN` | Chi tiết đơn hàng của đại lý: dòng hàng, số lượng đặt, đơn giá, số lượng thực giao, số lượng thiếu, ghi chú giao hàng |
| PUT | `/api/orders/{id}/delivery` | `ROLE_WAREHOUSE_STAFF`, `ROLE_WAREHOUSE_MANAGER`, `ROLE_ADMIN` | Cập nhật tiến độ giao nhận: trạng thái đơn, ngày giao thực tế, số lượng thực giao cho từng dòng sản phẩm |

---

### 1. Danh sách đơn hàng của đại lý (S5-01 Tiêu chí 1)

```text
GET /api/portal/orders
Authorization: Bearer <access-token>
```

#### Response thành công (HTTP 200 OK):
```json
{
  "success": true,
  "message": "Danh sách đơn hàng của đại lý",
  "data": [
    {
      "id": 101,
      "orderCode": "DH-MINHPHAT-001",
      "customerName": "Đại lý Cấp 1 Minh Phát",
      "dealerId": 1,
      "status": "DELIVERED",
      "expectedDeliveryDate": "2026-10-18",
      "actualDeliveryDate": "2026-10-17",
      "totalAmount": 15000000.00,
      "hasShortage": true,
      "totalLineItems": 2,
      "deliveryNote": "Giao đợt 1, còn thiếu sản phẩm Heineken do kho hết hàng",
      "createdAt": "2026-10-15T09:00:00"
    }
  ]
}
```

---

### 2. Chi tiết dòng hàng và số lượng thực giao nếu giao thiếu (S5-01 Tiêu chí 2)

```text
GET /api/portal/orders/101
Authorization: Bearer <access-token>
```

#### Response thành công (HTTP 200 OK):
```json
{
  "success": true,
  "message": "Chi tiết đơn hàng",
  "data": {
    "id": 101,
    "orderCode": "DH-MINHPHAT-001",
    "customerName": "Đại lý Cấp 1 Minh Phát",
    "dealerId": 1,
    "status": "DELIVERED",
    "expectedDeliveryDate": "2026-10-18",
    "actualDeliveryDate": "2026-10-17",
    "totalAmount": 15000000.00,
    "hasShortage": true,
    "deliveryNote": "Đã giao đợt 1 có thiếu 2 thùng bia Heineken",
    "items": [
      {
        "id": 201,
        "productId": 10,
        "productCode": "SP-KEN330",
        "productName": "Bia Heineken lon 330ml",
        "unit": "thùng",
        "quantity": 10.0000,
        "price": 450000.00,
        "totalAmount": 4500000.00,
        "deliveredQuantity": 8.0000,
        "shortageQuantity": 2.0000,
        "isShortage": true,
        "deliveryNote": "Kho tạm thời thiếu 2 thùng, sẽ giao bù đợt 2"
      },
      {
        "id": 202,
        "productId": 11,
        "productCode": "SP-TIGER330",
        "productName": "Bia Tiger lon 330ml",
        "unit": "thùng",
        "quantity": 5.0000,
        "price": 380000.00,
        "totalAmount": 1900000.00,
        "deliveredQuantity": 5.0000,
        "shortageQuantity": 0.0000,
        "isShortage": false,
        "deliveryNote": "Giao đủ"
      }
    ]
  }
}
```

#### Response lỗi khi truy cập đơn của đại lý khác (HTTP 403 Forbidden):
```json
{
  "success": false,
  "message": "Bạn không có quyền thực hiện chức năng này"
}
```

---

### 3. Cập nhật tiến độ giao hàng và số lượng thực giao (Dành cho Kho)

```json
PUT /api/orders/101/delivery
Authorization: Bearer <access-token>
{
  "status": "DELIVERED",
  "actualDeliveryDate": "2026-10-17",
  "deliveryNote": "Giao hàng đợt 1",
  "items": [
    {
      "orderItemId": 201,
      "deliveredQuantity": 8.0000,
      "deliveryNote": "Kho thiếu 2 thùng"
    },
    {
      "orderItemId": 202,
      "deliveredQuantity": 5.0000,
      "deliveryNote": "Giao đủ"
    }
  ]
}
```




---

## S6-09 — Theo dõi trạng thái giao hàng của đơn (Đại lý & Nhân viên) — 25/10/2026

Cho phép Đại lý (`ROLE_DEALER`) và Nhân viên nội bộ (`ROLE_SALES`, `ROLE_WAREHOUSE_STAFF`, `ROLE_ADMIN`,...) theo dõi tiến trình giao hàng theo thời gian thực để chủ động bố trí người nhận hàng đúng lúc xe tới:
1. **Hiển thị 5 mốc thời gian rõ ràng**:
   - `approved`: Đã duyệt
   - `picking`: Đang soạn hàng (lấy từ phiếu soạn hàng S6-03 hoặc lịch sử trạng thái)
   - `exported`: Đã xuất kho (lấy từ phiếu xuất kho S6-05)
   - `shipping`: Đang giao hàng (lấy từ thời điểm gán chuyến xe S6-06 hoặc cập nhật trạng thái)
   - `delivered`: Đã giao hàng thành công (lấy từ điểm dừng hoàn thành S6-07/S6-08)
   Mỗi mốc có `milestoneKey`, `label`, `status` (`COMPLETED`, `CURRENT`, `PENDING`, `UNKNOWN`), `timestamp` và `description`.
2. **Xem được số lượng thực giao từng dòng hàng**:
   - `quantity`: Số lượng đặt
   - `exportedQuantity`: Số lượng thực xuất từ kho
   - `deliveredQuantity`: Số lượng thực giao đến tay đại lý
   - `shortageQuantity`: Số lượng thiếu hụt nếu có giao thiếu
   - `isShortage`: Cờ cảnh báo thiếu
   - `deliveryNote`: Ghi chú giao hàng từng dòng
3. **Thông tin phương tiện và tài xế vận chuyển**:
   - `tripCode`: Mã chuyến giao hàng
   - `driverName`, `driverPhone`: Tên và số điện thoại tài xế
   - `vehiclePlate`: Biển số xe giao hàng
   - `departureTime`: Thời điểm xe bắt đầu xuất phát
   - `expectedCompletionTime`: Thời gian dự kiến xe tới giao

### Danh sách Endpoint S6-09:

| Method | Endpoint | Quyền yêu cầu | Mô tả |
|---|---|---|---|
| `GET` | `/api/portal/orders/{id}/tracking` (alias `/api/portal/orders/{id}/delivery-tracking`) | `ORDER_VIEW` hoặc `ROLE_ADMIN` | Dành cho Đại lý (hoặc Admin chọn `dealerId`): Xem 5 mốc giao hàng, thông tin chuyến xe và thực giao từng dòng |
| `GET` | `/api/orders/{id}/delivery-tracking` | `ORDER_VIEW` hoặc `ROLE_ADMIN` | Dành cho Nhân viên nội bộ: Xem 5 mốc giao hàng, thông tin chuyến xe và thực giao từng dòng |

#### Request mẫu (Đại lý):
```text
GET /api/portal/orders/101/tracking
Authorization: Bearer <access-token>
```

#### Response thành công (HTTP 200 OK):
```json
{
  "success": true,
  "message": "Theo dõi giao hàng của đơn",
  "data": {
    "orderId": 101,
    "orderCode": "DH-MINHPHAT-001",
    "dealerId": 1,
    "dealerName": "Đại lý Minh Phát",
    "deliveryAddress": "123 Nguyễn Huệ, Quận 1, TP.HCM",
    "contactPerson": "Nguyễn Văn Nhận",
    "contactPhone": "0912345678",
    "status": "SHIPPING",
    "statusDisplayName": "Đang giao",
    "createdAt": "2026-10-25T08:00:00",
    "approvedAt": "2026-10-25T09:00:00",
    "pickingAt": "2026-10-25T10:15:00",
    "exportedAt": "2026-10-25T11:30:00",
    "shippingAt": "2026-10-25T13:00:00",
    "deliveredAt": null,
    "hasShortage": true,
    "milestones": [
      {
        "milestoneKey": "approved",
        "label": "Đã duyệt",
        "status": "COMPLETED",
        "timestamp": "2026-10-25T09:00:00",
        "description": "Đơn hàng đã được duyệt kinh doanh"
      },
      {
        "milestoneKey": "picking",
        "label": "Đang soạn",
        "status": "COMPLETED",
        "timestamp": "2026-10-25T10:15:00",
        "description": "Thủ kho đã tạo lượt soạn hàng"
      },
      {
        "milestoneKey": "exported",
        "label": "Đã xuất",
        "status": "COMPLETED",
        "timestamp": "2026-10-25T11:30:00",
        "description": "Đã xuất kho theo phiếu xuất PX-20261025-001"
      },
      {
        "milestoneKey": "shipping",
        "label": "Đang giao",
        "status": "CURRENT",
        "timestamp": "2026-10-25T13:00:00",
        "description": "Đang vận chuyển bởi tài xế Nguyễn Văn Tài - Biển số 29C-12345"
      },
      {
        "milestoneKey": "delivered",
        "label": "Đã giao",
        "status": "PENDING",
        "timestamp": null,
        "description": "Chưa hoàn tất giao hàng"
      }
    ],
    "deliveryTrip": {
      "tripId": 10,
      "tripCode": "CX-20261025-001",
      "driverName": "Nguyễn Văn Tài",
      "driverPhone": "0909123456",
      "vehiclePlate": "29C-12345",
      "departureTime": "2026-10-25T13:00:00",
      "expectedCompletionTime": "2026-10-25T15:30:00",
      "actualCompletionTime": null,
      "tripStatus": "IN_PROGRESS",
      "stopStatus": "IN_TRANSIT",
      "stopOrder": 1,
      "receiverName": "Nguyễn Văn Nhận",
      "receiverPhone": "0912345678",
      "deliveryAddress": "123 Nguyễn Huệ, Quận 1, TP.HCM",
      "deliveredAt": null,
      "note": "Giao cổng sau",
      "failedReason": null
    },
    "items": [
      {
        "orderItemId": 201,
        "productId": 10,
        "productCode": "SP-KEN330",
        "productName": "Bia Heineken lon 330ml",
        "unit": "thùng",
        "quantity": 10.0,
        "exportedQuantity": 8.0,
        "remainingExportQuantity": 2.0,
        "deliveredQuantity": 8.0,
        "shortageQuantity": 2.0,
        "isShortage": true,
        "deliveryNote": "Kho thiếu 2 thùng xuất sau"
      }
    ]
  }
}
```

#### Ràng buộc bảo mật & Ma trận phân quyền:
- Đại lý bắt buộc phải có quyền `ORDER_VIEW` trong ma trận DB. Thu hồi quyền lập tức trả về `403 Forbidden`.
- Row-Level Security: Đại lý chỉ được xem đơn hàng của chính mình. Truy cập đơn của đại lý khác bị chặn `403 Forbidden`.
- `ROLE_ADMIN` luôn có toàn quyền và có thể truyền `?dealerId=...` để tra cứu theo ngữ cảnh đại lý.


## Cổng tự đặt hàng dành cho Đại lý (S4-10)

Cổng tự đặt hàng trực tuyến dành riêng cho khách hàng đại lý (`ROLE_DEALER`), cho phép đại lý chủ động kiểm tra giá, xem công nợ còn lại và gửi đơn đặt hàng 24/7 trên cả máy tính và thiết bị di động.

### Nguyên tắc bảo mật & Phân quyền dữ liệu (Row-Level Security)
1. **Phân quyền vai trò:** Chỉ người dùng có vai trò `ROLE_DEALER` hoặc `ROLE_ADMIN` mới có quyền truy cập các endpoint `/api/portal/**`.
2. **Cô lập dữ liệu danh mục & bảng giá:** Đại lý chỉ được xem các sản phẩm và đơn giá nằm trong **bảng giá có hiệu lực** thuộc nhóm khách hàng của chính đại lý đó (`DAI_LY_CAP_1`, `DAI_LY_CAP_2`, `KHACH_LE`). Tuyệt đối không nhìn thấy sản phẩm hoặc bảng giá của nhóm khách hàng khác.
3. **Cô lập công nợ & đơn hàng:** Đại lý chỉ xem được hạn mức công nợ, công nợ hiện tại, công nợ còn lại và danh sách đơn hàng thuộc mã đại lý của chính mình.
4. **Kiểm soát rủi ro khoá giao dịch:** Nếu đại lý đang bị khoá (`locked = true`) hoặc có nợ quá hạn, hệ thống chặn gửi đơn đặt hàng và trả lỗi HTTP 400 Bad Request.
5. **Quy trình phê duyệt bắt buộc:** Mọi đơn hàng do đại lý tự đặt gửi lên hệ thống **bắt buộc luôn ở trạng thái Chờ duyệt** (`PENDING_APPROVAL`, `requiresApproval = true`, `approvalReason = "[Đại lý tự đặt qua cổng] Chờ nhân viên phụ trách xác nhận"`). Đơn hàng sẽ được nhân viên kinh doanh được phân công phụ trách đại lý đó (`assignedUser`) xác nhận hoặc từ chối trước khi chuyển sang trạng thái ghi sổ (`RECORDED`).

### Danh sách API Cổng Đại lý

| Method | Endpoint | Quyền | Mô tả |
|---|---|---|---|
| GET | `/portal/profile` | `ROLE_DEALER`, `ROLE_ADMIN` | Lấy hồ sơ đại lý, thông tin NVKD phụ trách, công nợ và danh sách điểm giao hàng |
| GET | `/portal/catalog` | `ROLE_DEALER`, `ROLE_ADMIN` | Lấy danh mục sản phẩm và giá theo bảng giá hiệu lực của nhóm khách hàng đại lý |
| GET | `/portal/debt` | `ROLE_DEALER`, `ROLE_ADMIN` | Tra cứu hạn mức, công nợ hiện tại và công nợ còn lại thời gian thực |
| POST | `/portal/orders` | `ROLE_DEALER`, `ROLE_ADMIN` | Tạo đơn đặt hàng mới (luôn ở trạng thái `PENDING_APPROVAL`) |
| GET | `/portal/orders` | `ROLE_DEALER`, `ROLE_ADMIN` | Lấy danh sách lịch sử đơn hàng của chính đại lý |
| GET | `/portal/orders/{id}` | `ROLE_DEALER`, `ROLE_ADMIN` | Xem chi tiết đơn hàng của đại lý |

#### 1. Lấy thông tin hồ sơ đại lý
```http
GET /api/portal/profile
Authorization: Bearer <dealer-jwt-token>
```
Response `200 OK`:
```json
{
  "success": true,
  "message": "Lấy thông tin hồ sơ đại lý thành công",
  "data": {
    "id": 100,
    "code": "DL-MINHPHAT",
    "name": "Đại lý Minh Phát",
    "customerGroup": "DAI_LY_CAP_1",
    "customerGroupName": "Đại lý cấp 1",
    "phone": "0987654321",
    "email": "dealer.minhphat@example.com",
    "address": "123 Đường Giải Phóng, Hà Nội",
    "taxCode": "0101234567",
    "active": true,
    "locked": false,
    "lockReason": null,
    "salesUserId": 2,
    "salesUserName": "sales.demo",
    "salesUserFullName": "Nguyễn Văn Kinh Doanh",
    "salesUserPhone": "0901234567",
    "salesUserEmail": "sales.demo@company.vn",
    "debtLimit": 50000000,
    "currentDebt": 15000000,
    "remainingLimit": 35000000,
    "hasOverdueDebt": false,
    "isBlocked": false,
    "blockReason": null,
    "deliveryPoints": [
      {
        "id": 1,
        "dealerId": 100,
        "name": "Kho chính Minh Phát",
        "contactName": "Anh Minh",
        "phone": "0987654321",
        "address": "123 Đường Giải Phóng, Hà Nội",
        "isDefault": true
      }
    ]
  }
}
```

#### 2. Lấy danh mục sản phẩm & đơn giá theo bảng giá đại lý
```http
GET /api/portal/catalog
Authorization: Bearer <dealer-jwt-token>
```
Response `200 OK`:
```json
{
  "success": true,
  "message": "Lấy danh mục sản phẩm thành công",
  "data": [
    {
      "id": 10,
      "sku": "SP-KEN330",
      "name": "Bia Heineken Sleek lon 330ml",
      "categoryId": 1,
      "categoryName": "Bia",
      "baseUnit": "lon",
      "basePrice": 18000,
      "floorPrice": 15000,
      "imageUrl": "/images/heineken.png",
      "units": [
        {
          "unitName": "lon",
          "factor": 1,
          "price": 18000
        },
        {
          "unitName": "thùng",
          "factor": 24,
          "price": 430000
        }
      ]
    }
  ]
}
```

#### 3. Tra cứu công nợ thời gian thực
```http
GET /api/portal/debt
Authorization: Bearer <dealer-jwt-token>
```
Response `200 OK`:
```json
{
  "success": true,
  "message": "Lấy thông tin công nợ thành công",
  "data": {
    "dealerId": 100,
    "dealerCode": "DL-MINHPHAT",
    "dealerName": "Đại lý Minh Phát",
    "currentDebt": 15000000,
    "debtLimit": 50000000,
    "remainingLimit": 35000000,
    "maxDebtDays": 30,
    "hasOverdueDebt": false,
    "overdueAmount": 0,
    "isBlocked": false,
    "blockReason": null
  }
}
```

#### 4. Đại lý gửi đơn đặt hàng
```http
POST /api/portal/orders
Authorization: Bearer <dealer-jwt-token>
Content-Type: application/json

{
  "deliveryPointId": 1,
  "expectedDeliveryDate": "2026-10-20",
  "note": "Giao vào buổi sáng",
  "items": [
    {
      "productId": 10,
      "unit": "thùng",
      "quantity": 10
    }
  ]
}
```
Response `201 Created`:
```json
{
  "success": true,
  "message": "Đặt hàng thành công. Đơn hàng đang ở trạng thái Chờ duyệt.",
  "data": {
    "id": 1001,
    "orderCode": "DH20261015-0001",
    "dealerId": 100,
    "dealerName": "Đại lý Minh Phát",
    "status": "PENDING_APPROVAL",
    "requiresApproval": true,
    "approvalReason": "[Đại lý tự đặt qua cổng] Chờ nhân viên phụ trách xác nhận",
    "subtotalAmount": 4300000,
    "discountAmount": 0,
    "totalAmount": 4300000
  }
}
```

#### 5. Xác nhận / Từ chối đơn hàng của đại lý (Dành cho NVKD phụ trách)
- **Xác nhận đơn:** `PUT /api/orders/{id}/approve` (Quyền: NVKD phụ trách đại lý đó, hoặc `SALES_MANAGER`/`ADMIN`). Chuyển đơn sang trạng thái `RECORDED`.
- **Từ chối đơn:** `PUT /api/orders/{id}/reject` (Quyền: NVKD phụ trách đại lý đó, hoặc `SALES_MANAGER`/`ADMIN`). Chuyển đơn sang trạng thái `REJECTED`.
*Lưu ý: Nếu NVKD không phải người phụ trách đại lý của đơn hàng cố tình duyệt hoặc từ chối, hệ thống sẽ trả về lỗi HTTP 403 Forbidden.*


### In và Xuất PDF Đơn hàng kèm Mã vạch tra cứu (S4-08)

Hỗ trợ Nhân viên kinh doanh (`ROLE_SALES`) in hoặc xuất file PDF phiếu đơn hàng ngay tại chỗ để đại lý xem lại và ký xác nhận đơn trước khi nhân viên rời đi. Đồng thời mẫu in tích hợp mã vạch chuẩn Code 128 và mã đơn hàng rõ ràng giúp bộ phận kho tra cứu nhanh và đối chiếu xuất kho bằng máy quét mã vạch.

Quy tắc nghiệp vụ:
1. **Vai trò & Ma trận phân quyền**:
   - Mã quyền xem/in: `ORDER_VIEW`.
   - Tài khoản chỉ có `ORDER_CREATE` được xuất PDF/mã vạch cho đơn do chính mình tạo; tài khoản có `ORDER_VIEW` được xem theo phạm vi dữ liệu của mình.
   - `ROLE_ADMIN` luôn được phép theo ma trận quyền.
2. **Bảo mật phân quyền cấp dòng (Row-Level Security - RLS)**:
   - Nhân viên kinh doanh (`ROLE_SALES`) chỉ được phép xem, in hoặc xuất PDF các đơn hàng của đại lý do chính mình phụ trách (`assignedUser`) hoặc đơn do chính mình tạo (`createdBy`).
   - Cố tình truy cập hoặc in đơn hàng của đại lý khác sẽ bị chặn với mã lỗi **HTTP 403 Forbidden** (`AccessDeniedException`).
   - Quản lý kinh doanh, Quản trị viên, Kế toán và Kho có quyền xem và in PDF đơn hàng toàn công ty.
3. **Nội dung đầy đủ trên Mẫu in PDF**:
   - **Đầu trang (Header)**: Thông tin công ty phân phối (SaleStock), Hotline, Email và **Mã vạch Code 128 kèm mã đơn hàng** ở góc trên bên phải để kho tra cứu nhanh.
   - **Thông tin đại lý**: Tên đại lý, mã đại lý, phân nhóm đại lý (Cấp 1 / Cấp 2 / Khách lẻ), mã số thuế, địa bàn/khu vực, nhân viên kinh doanh phụ trách, số điện thoại, địa chỉ.
   - **Thông tin giao hàng**: Điểm giao hàng, tên người nhận, số điện thoại người nhận, địa chỉ giao hàng, chỉ dẫn giao nhận, ngày giao dự kiến, trạng thái đơn hàng.
   - **Bảng chi tiết dòng hàng**: STT, Mã SKU, Tên sản phẩm, ĐVT đặt hàng, Số lượng, Quy đổi về đơn vị cơ sở (số lon đóng băng), Đơn giá, Thành tiền trước chiết khấu, Chiết khấu dòng hàng, Tổng cộng sau chiết khấu.
   - **Tổng tiền & Chiết khấu**: Tạm tính trước chiết khấu (`subtotalAmount`), Chiết khấu sản lượng (`discountAmount`), Tổng tiền thanh toán (`totalAmount`), Số tiền viết bằng chữ tiếng Việt chuẩn ("Bằng chữ: ..."), Ghi chú đơn hàng.
   - **Khung chữ ký xác nhận 3 bên**:
     + Đại diện Đại lý (Ký, ghi rõ họ tên - xác nhận đơn hàng tại chỗ trước khi rời đi).
     + Nhân viên kinh doanh (Ký & ghi rõ họ tên).
     + Bộ phận Kho / Giao nhận (Ký & ghi rõ họ tên - quét mã vạch đối soát xuất kho).
   - **Chân trang (Footer)**: Đánh số trang tự động ("Trang X") và lưu ý đối soát.
4. **Hỗ trợ Font Unicode tiếng Việt**: Tích hợp sẵn bộ font Arial Unicode tiếng Việt, đảm bảo hiển thị chuẩn xác không lỗi dấu trên cả Windows, Linux và Docker.

| Method | Endpoint | Quyền | Mô tả |
|---|---|---|---|
| GET | `/orders/{id}/pdf` | `ORDER_VIEW` hoặc `ORDER_CREATE` | Xuất file PDF phiếu xác nhận đơn hàng kèm mã vạch. Tài khoản chỉ có `ORDER_CREATE` chỉ được xuất đơn do chính mình tạo; mặc định trả `inline`, thêm `?download=true` để tải file về máy |
| GET | `/orders/{id}/barcode` | `ORDER_VIEW` hoặc `ORDER_CREATE` | Lấy ảnh mã vạch (Barcode Code 128) dạng PNG; tài khoản chỉ có `ORDER_CREATE` chỉ được lấy mã vạch đơn do chính mình tạo |

#### Ví dụ Request xuất PDF xem trước (`GET /api/orders/1/pdf`):
```http
GET /api/orders/1/pdf HTTP/1.1
Host: localhost:8080
Authorization: Bearer <access-token>
```
Response Headers:
```http
HTTP/1.1 200 OK
Content-Type: application/pdf
Content-Disposition: inline; filename="don-hang-DH20261016-0001.pdf"
```
Response Body: Binary data PDF (`%PDF-...`)

#### Ví dụ Request tải file PDF đính kèm (`GET /api/orders/1/pdf?download=true`):
```http
GET /api/orders/1/pdf?download=true HTTP/1.1
Host: localhost:8080
Authorization: Bearer <access-token>
```
Response Headers:
```http
HTTP/1.1 200 OK
Content-Type: application/pdf
Content-Disposition: attachment; filename="don-hang-DH20261016-0001.pdf"
```

#### Ví dụ Request lấy mã vạch Code 128 (`GET /api/orders/1/barcode`):
```http
GET /api/orders/1/barcode HTTP/1.1
Host: localhost:8080
Authorization: Bearer <access-token>
```
Response Headers:
```http
HTTP/1.1 200 OK
Content-Type: image/png
```
Response Body: Binary data PNG (ảnh mã vạch Code 128)

### S5-09 — Ngưỡng tồn tối thiểu và cảnh báo — 20/10/2026

Nguồn AC: `4. Product Backlog!A62:F62`. Menu **Kho hàng → Ngưỡng tồn tối thiểu**, URL `/warehouse/minimums`; cảnh báo hiển thị trong **Tồn kho** và **Tổng quan**. Các endpoint dưới đây có prefix `/api`.

| Method | Endpoint | Quyền ma trận | Dữ liệu / hành vi |
|---|---|---|---|
| GET | `/warehouse/stock-minimums/options` | `WAREHOUSE_MANAGE` hoặc ADMIN | Kho được phép khai báo và danh mục nhóm hàng tối thiểu `{id,code,name}` |
| GET | `/warehouse/stock-minimums` | `WAREHOUSE_MANAGE` hoặc ADMIN | Page khai báo theo SKU–kho; không trả tồn thực tế/giữ chỗ/khả dụng, giá bán hoặc giá vốn |
| PUT | `/warehouse/stock-minimums/{warehouseId}/{productId}` | `WAREHOUSE_MANAGE` hoặc ADMIN | Tạo/cập nhật mức tối thiểu; body `{"minimumQuantity":7.2501,"baseUnit":"lon"}`; trả projection khai báo |
| DELETE | `/warehouse/stock-minimums/{warehouseId}/{productId}` | `WAREHOUSE_MANAGE` hoặc ADMIN | Bỏ khai báo, idempotent; không đổi lượng tồn hoặc giữ chỗ |
| GET | `/warehouse/stock-alerts` | `WAREHOUSE_VIEW` hoặc ADMIN | Page dòng dưới ngưỡng với cùng DTO sổ tồn; mặc định `page=0&size=5` |
| GET | `/warehouse/stock-ledger?belowMinimum=true` | `WAREHOUSE_VIEW` hoặc ADMIN | Bổ sung bộ lọc dưới ngưỡng vào sổ tồn; kết hợp các bộ lọc S5-05 hiện có |

Hai API danh sách khai báo/cảnh báo nhận `warehouseId`, `categoryId`, `keyword` (mã/tên, tối đa 150 ký tự, `%/_/!` được escape), `page >= 0`, `size` 1–200. Khai báo mặc định 20 dòng; thứ tự mã kho/mã SKU/ID ổn định. ID kho/nhóm/sản phẩm phải dương. Kho ngoài phạm vi trả 403; dữ liệu không hợp lệ hoặc SKU không tồn tại khi lưu trả 400.

Projection khai báo gồm `warehouseId/Code/Name`, `productId/Code/Name`, `categoryName`, `baseUnit`, `minimumQuantity`, `minimumUnit`, `minimumUnitMatches`, `warehouseActive`, `productActive`, `updatedBy`, `updatedAt`. Người chỉ có MANAGE không phải xin thêm VIEW/PRODUCT_VIEW để cấu hình và cũng không đọc được số tồn qua API này.

Sổ tồn/cảnh báo bổ sung `minimumQuantity` (null nếu chưa khai báo), `minimumUnit` (đơn vị lúc lưu), `minimumUnitMatches`, `belowMinimum`. Ba trạng thái tồn S5-05 không thay đổi; cờ dưới ngưỡng là điều kiện độc lập do backend tính, frontend không tự suy cảnh báo.

Quy tắc:

- Ngưỡng riêng cho mỗi `(warehouse_id, product_id)`, đơn vị cơ sở tại kho. Mức tối thiểu không âm, tối đa 11 chữ số nguyên và 4 số thập phân; bắt buộc gửi lại `baseUnit` hiện tại. Kho/SKU ngừng hoạt động không được khai báo mới hoặc sửa.
- Cảnh báo khi **`max(tồn thực tế − giữ chỗ, 0) < mức tối thiểu`**; không cộng hàng đang chuyển. Bằng ngưỡng không cảnh báo. Mức 0 khác chưa khai báo nhưng cả hai không phát cảnh báo. SKU chưa có dòng tồn được xem khả dụng 0; cấu hình không tự tạo dòng tồn vật lý.
- Chỉ cảnh báo kho/SKU đang hoạt động và đơn vị ngưỡng còn khớp. Nếu đơn vị sổ tồn đổi, giữ snapshot cũ, hiển thị yêu cầu khai báo lại, không tự đổi số hay diễn giải ngưỡng theo đơn vị mới.
- Cảnh báo truy vấn lại số tồn hiện tại, không lưu cờ cảnh báo lỗi thời. Dashboard nạp khi mở/quay lại cửa sổ và mỗi 30 giây khi trang hiển thị; có Tải lại. Lỗi đọc hiển thị chưa xác định, không coi là không có hàng thiếu.
- Token hiện tại kiểm lại ma trận trên mỗi API; MANAGE không thay VIEW hoặc ngược lại. Thu hồi MANAGE chặn toàn bộ cấu hình bằng 403 và ẩn menu/đóng form; thu hồi VIEW chặn ledger/alerts và xóa khối cảnh báo. Không fallback theo role. ADMIN luôn được phép; vai trò chỉ giới hạn phạm vi.
- Phạm vi thống nhất S5-05: tài khoản đã gán kho chỉ thao tác tập kho đó; vai trò kho thiếu phân công nhận tập rỗng, không được xem/cấu hình toàn hệ thống. Vai trò nội bộ khác không gán kho giữ quy ước toàn hệ thống nhưng vẫn cần đúng quyền. Đại lý vẫn chịu DealerApiScopeFilter hiện có.

Lưu trữ mới: bảng **`warehouse_stock_minimums`**, unique `(warehouse_id, product_id)`, `minimum_quantity DECIMAL(15,4)`, `base_unit`, `updated_by`, `updated_at`. Ghi trong transaction, khóa kho chống upsert trùng đồng thời, audit `INVENTORY / SET_MINIMUM` và `CLEAR_MINIMUM`. FK xóa cấu hình phụ thuộc khi SKU/kho chưa phát sinh giao dịch được xóa hợp lệ; không thay quy tắc chặn xóa dữ liệu có giao dịch hiện có.

Cần khởi động lại backend để Hibernate tạo bảng theo cấu hình `ddl-auto=update` hiện hành; tài khoản DB phải có quyền CREATE/ALTER. Nếu môi trường dùng `validate` hoặc migration riêng, phải triển khai bảng tương ứng trước khi sử dụng API sổ tồn mới. Không thay quyền đã lưu, seed dữ liệu hay đổi schema DB thật trong đợt triển khai mã này. Kiểm thử dùng H2/MySQL-mode, chưa nghiệm thu MySQL thật. Khởi động lại server frontend để nhận route mới.

### S5-05 — Sổ tồn theo SKU và kho — 20/10/2026

Menu **Kho hàng → Tồn kho**, URL `/warehouse/inventory`. Link cũ `/warehouse/units?tab=inventory` được chuyển sang trang mới và giữ các bộ lọc còn lại. Các phân hệ đơn vị tính/chứng từ/sổ cái kho và API tồn cũ phục vụ S4 vẫn giữ nguyên.

| Method | Endpoint (prefix `/api`) | Quyền | Nội dung |
|---|---|---|---|
| GET | `/warehouse/stock-ledger/options` | `WAREHOUSE_VIEW` hoặc ADMIN | `warehouses`, `categories` dạng `{id,code,name}`, `stockStatuses` dạng `{value,label}`; kho chỉ nằm trong phạm vi được phép |
| GET | `/warehouse/stock-ledger` | `WAREHOUSE_VIEW` hoặc ADMIN | Spring Page trong `data`: `content`, `totalElements`, `totalPages`, `number`, `last`… |

Query: `warehouseId` (bỏ trống = tất cả kho trong phạm vi), `categoryId` (nhóm hàng cụ thể), `keyword` (mã SKU/tên sản phẩm, không phân biệt hoa thường, tối đa 150 ký tự), `stockStatus`, `page` (từ 0), `size` (1–200, mặc định 20). Tìm kiếm dùng parameter binding, `%`, `_`, `!` trong từ khóa được tìm như ký tự thường. Sắp xếp cố định theo mã kho/mã SKU/ID để phân trang ổn định.

Mỗi dòng gồm `warehouseId/Code/Name`, `productId/Code/Name`, `categoryId/Name`, `baseUnit`, `physicalQuantity`, `reservedQuantity`, `availableQuantity`, `stockStatus`, `stockStatusLabel`, `updatedAt`. Không trả giá bán/giá vốn/thông tin tài chính. Đơn vị lấy từ sổ tồn đã ghi nhận, fallback đơn vị sản phẩm chỉ khi chưa có bản ghi tồn.

Ba lượng tồn đọc trực tiếp từ DB: thực tế = base_quantity, giữ chỗ = reserved_quantity, khả dụng = max(thực tế − giữ chỗ, 0). Hàng đang chuyển chưa được cộng vào kho nhận. SKU chưa có bản ghi tồn tại kho được trả về 0 qua LEFT JOIN, **không tự tạo hoặc sửa bản ghi DB**; sản phẩm/kho ngừng hoạt động vẫn tra cứu được để không che tồn cũ. Không cộng tổng số lượng giữa SKU khác đơn vị.

| `stockStatus` | Nhãn | Điều kiện |
|---|---|---|
| `IN_STOCK` | Còn khả dụng | Tồn thực tế > 0 và còn khả dụng > 0 |
| `OUT_OF_STOCK` | Hết tồn | Tồn thực tế <= 0, kể cả SKU chưa phát sinh tồn |
| `FULLY_RESERVED` | Đã giữ hết | Tồn thực tế > 0 nhưng khả dụng = 0 |

Đây không phải cảnh báo dưới ngưỡng tối thiểu của S5-09. Không thêm bảng, cột, dữ liệu seed, quyền mới hoặc tự cấp lại quyền mặc định.

Phạm vi dữ liệu được nạp lại từ tài khoản DB mỗi request: ADMIN xem mọi kho; tài khoản có phân công kho chỉ xem tập kho được gán. Vai trò kho thiếu phân công trả danh sách rỗng, không fallback toàn hệ thống. Vai trò nội bộ khác không gán kho giữ quy ước phạm vi toàn hệ thống nhưng vẫn **bắt buộc WAREHOUSE_VIEW**. Truy vấn ID kho ngoài phạm vi trả 403. Vai trò kho/WAREHOUSE_MANAGE/WAREHOUSE_IMPORT không thay thế quyền xem này. Cả controller và service kiểm quyền, token cũ bị chặn ngay sau khi quyền bị thu hồi; tài khoản chỉ là đại lý vẫn bị chặn API nội bộ bởi DealerApiScopeFilter.

Frontend chỉ dùng hai API mới; không đòi thêm PRODUCT_VIEW, ORDER_VIEW, CUSTOMER_VIEW hoặc quyền nhập kho để xem sổ tồn. Lỗi tải không được hiện như tồn bằng 0; dữ liệu phản hồi cũ không ghi đè bộ lọc mới. Xuất Excel tải toàn bộ trang kết quả theo **bộ lọc đã áp dụng**, quyền/phạm vi được kiểm tại mỗi request; đổi bộ lọc hoặc mất quyền hủy xuất. Dữ liệu nghiệp vụ chỉ ở DB và bộ nhớ trang, không lưu localStorage/sessionStorage.

### S5-03 — Màn hình Kho & vị trí lưu — 20/10/2026

Frontend tại `/warehouses` (menu **Kho hàng → Kho & vị trí lưu**), dùng các API có sẵn dưới đây.
Mọi đường dẫn trong bảng có prefix `/api`. ADMIN luôn được phép; các vai trò khác bắt buộc có quyền trong ma trận DB, không fallback theo tên vai trò.

| Method | Endpoint | Quyền | Dữ liệu / hành vi |
|---|---|---|---|
| GET | `/warehouses` | `WAREHOUSE_VIEW` hoặc `WAREHOUSE_MANAGE` | Danh sách kho, mã/tên/địa chỉ/người phụ trách/trạng thái và số vị trí/đại lý |
| GET | `/warehouses/{id}` | `WAREHOUSE_VIEW` hoặc `WAREHOUSE_MANAGE` | Chi tiết và `locations` của kho |
| POST / PUT | `/warehouses`, `/warehouses/{id}` | `WAREHOUSE_MANAGE` | `code`, `name`, `address`, `managerName` (hoặc `managerUserId` theo API có sẵn), `active` |
| POST / PUT | `/warehouses/{id}/locations`, `/warehouse-locations/{id}` | `WAREHOUSE_MANAGE` | `code`, `name`, `locationType`, `parentLocationId`, `description`, `active` |
| GET | `/warehouses/dealer-assignments?keyword=&page=0&size=20` | `WAREHOUSE_MANAGE` | **Bổ sung**: Page gồm `content`, `totalElements`, `last`; mỗi dòng chỉ `id/code/name/active/warehouseId/warehouseCode/warehouseName`, không cung cấp công nợ/thông tin tài chính. `page >= 0`, `size` từ 1–200 |
| PUT | `/warehouses/dealer-assignments/{dealerId}` | `WAREHOUSE_MANAGE` | **Bổ sung**: body `{"warehouseId": 1}`; gán kho đang hoạt động, lưu DB và audit; response cùng projection tối thiểu, không sửa kho của đơn đã tồn tại |

Luồng kho phục vụ dùng projection riêng để quyền quản lý kho không phải phụ thuộc `CUSTOMER_VIEW`, `CUSTOMER_EDIT`, `USER_VIEW` hoặc nhận thêm dữ liệu tài chính. Endpoint khách hàng `/dealers/{id}/default-warehouse` vẫn giữ hợp đồng và quyền cũ.

Vị trí mới ở mức `ZONE` (khu) hoặc `SHELF` (kệ). Kệ chỉ nhận khu cha thuộc cùng kho. `parentLocationId: 0` có nghĩa bỏ khu cha (sửa lỗi nhánh xử lý trước đây không chạy); bỏ trường khi cập nhật vẫn giữ cha cũ. Khu không làm con của khu khác và không được đổi thành kệ nếu đang chứa vị trí con. Kiểu vị trí cũ của backend vẫn được giữ khi chỉnh sửa, không tự chuyển đổi dữ liệu.

**Mã vị trí là tùy chọn** (không áp dụng cho mã kho):

- Tạo mới: không gửi `code`, gửi `null`, chuỗi rỗng hoặc toàn khoảng trắng thì server tự sinh `KHU-001` cho khu, `KE-001` cho kệ; các kiểu cũ dùng tên enum làm tiền tố. Số thứ tự theo từng kho và tiền tố, tiếp tục sau mã số lớn nhất đang có, kể cả mã số được nhập tay; sau 999 tiếp tục 1000.
- Có nhập `code`: giữ mã riêng, chỉ chuẩn hóa bỏ khoảng trắng đầu/cuối và viết hoa như hợp đồng cũ; trùng mã trong cùng kho trả 400, không âm thầm thay bằng mã tự sinh. Kho khác có thể dùng cùng mã.
- Cập nhật: `code` trống/null/không gửi giữ nguyên mã cũ, không tự cấp lại mã khi đổi tên hoặc loại vị trí. Nhập mã khác sẽ kiểm tra trùng trước khi đổi. Tối đa 50 ký tự.
- Cấp mã thực hiện trong transaction backend, khóa ghi trên kho (cả tạo mới với mã tay và tự sinh, và đổi mã), đồng thời giữ unique constraint `(warehouse_id, code)`. Frontend không tính số thứ tự hoặc lưu counter trong trình duyệt. Không thêm bảng/cột DB.

Mã audit gán kho dùng `ASSIGN_WAREHOUSE` (16 ký tự), thay mã cũ 24 ký tự vượt giới hạn `audit_logs.action VARCHAR(20)` làm rollback giao dịch. Áp dụng cho cả endpoint kho mới và endpoint khách hàng cũ.

Không đổi schema, không tạo quyền mới, không tự cấp thêm quyền vào ma trận đã lưu. Thu hồi `WAREHOUSE_MANAGE` phải chặn API ghi và API danh sách gán đại lý bằng 403 ở request kế tiếp; giữ `WAREHOUSE_VIEW` thì trang còn xem được nhưng không có thao tác sửa/gán.

### Bổ sung sửa lỗi Sprint 4 — 20/10/2026

Các đường dẫn bên dưới có prefix `/api`. Quyền lấy từ ma trận DB ở mỗi request; token đã đăng nhập không giữ quyền bị thu hồi. ADMIN luôn toàn quyền, nhưng vẫn phải tuân thủ trạng thái và ràng buộc dữ liệu hợp lệ.

| API / trường | Quyền | Hành vi |
|---|---|---|
| `POST /portal/orders/preview` | `ORDER_CREATE` + `PRODUCT_VIEW` + `ORDER_VIEW`, hoặc ADMIN | Tính giá, chiết khấu, tồn khả dụng và công nợ dự kiến; không lưu đơn/giữ tồn |
| `PUT /orders/{id}/confirm` | `ORDER_CONFIRM`, hoặc ADMIN | Xác nhận đơn cổng đang chờ duyệt, không vi phạm; chỉ người phụ trách đại lý hoặc ADMIN; kiểm lại công nợ/tồn trước giữ chỗ |
| `PUT /orders/{id}/approve` | `ORDER_APPROVE`, hoặc ADMIN | Luồng duyệt, kể cả xử lý ngoại lệ giá/công nợ; không fallback theo role hoặc ORDER_CREATE |
| `OrderResponse.canConfirmPortalOrder` | Theo người đang gọi | Gợi ý hiện nút xác nhận: quyền, người phụ trách, đơn cổng, trạng thái và vi phạm đã ghi nhận; API vẫn kiểm lại điều kiện hiện tại |
| `OrderResponse.nextLifecycleStatus` | `WAREHOUSE_EXPORT` và kho được phân công, hoặc ADMIN | Bước tiếp theo hoặc null: RECORDED/APPROVED → PICKING → EXPORTED → DELIVERED → CLOSED |

Preview nhận cùng body với `POST /portal/orders`:

```json
{
  "expectedDeliveryDate": "2026-10-25",
  "items": [{"productId": 101, "unit": "thùng", "quantity": 2}]
}
```

Đại lý được xác định từ tài khoản đăng nhập; ADMIN dùng query `dealerId` để chọn ngữ cảnh. Không tin giá, nhóm khách hàng, kho hoặc dealerId do đại lý tự gửi trong body. Response `data` gồm `pricing` (OrderCalculateResponse: items/subtotalAmount/discountAmount/totalAmount và thông tin tồn) và `debt` (OrderDebtCheckResponse: currentDebt/debtLimit/projectedDebt/remainingLimit/isBlocked/hasOverdueDebt…). Tạo đơn vẫn tính lại trên server, không tin số tiền preview do client cung cấp.

Frontend bắt buộc tính lại trước xác nhận/gửi; lỗi preview chặn gửi và có nút thử lại. Thay đổi tổng tiền sau xác nhận phải xác nhận lại, phản hồi cũ không được ghi đè giỏ mới. API `confirm` trả 403 khi thiếu quyền/sai người phụ trách, 400 khi không phải đơn cổng chờ duyệt hoặc có vi phạm. Đơn vi phạm cần luồng `approve`, không cấp quyền duyệt rộng chỉ để xác nhận đơn thường.

`ORDER_CONFIRM` có nhãn **Xác nhận đơn đại lý** trong ma trận. Mặc định cho SALES/SALES_MANAGER chỉ áp dụng DB mới hoặc thao tác khôi phục mặc định. DB đã cấu hình **không tự được bổ sung quyền**: Admin cần tích và lưu cho vai trò mong muốn, không ghi đè các thiết lập quyền đã lưu.

Quy tắc nhất quán khi tính/tạo đơn:

- Giá và chiết khấu dùng ngày đặt hiện tại, không dùng ngày giao mong muốn; sản phẩm ngừng kinh doanh bị chặn.
- Đơn có đại lý bắt buộc dùng kho phục vụ đang hoạt động gắn với đại lý. Gửi warehouseId khác trả 403; thiếu kho phục vụ trả 400, không chọn kho đầu tiên thay thế.
- Công nợ và quá hạn tính chung các trạng thái đã ghi nhận: APPROVED, RECORDED, PICKING, PROCESSING, EXPORTED, SHIPPED, DELIVERED, CLOSED, COMPLETED. Chuyển vòng đời không có nghĩa đã thu tiền. Hiện chưa có sổ thu tiền để đối trừ số dư, nên không tự xóa nợ khi giao/đóng đơn.
- Luồng tạo dữ liệu demo nội bộ dùng SecurityContext bootstrap ADMIN riêng và khôi phục trong finally; service duyệt không còn chấp nhận lời gọi không xác thực.

### Gợi ý lô xuất theo nguyên tắc FEFO và Xuất kho theo lô (S6-04)

Hệ thống hỗ trợ quản lý xuất kho theo phương pháp FEFO (First Expired, First Out - Hạn sử dụng gần nhất trước), giúp hàng cũ được ưu tiên xuất trước, giảm thiểu tình trạng hàng hóa hết hạn phải tiêu hủy.

#### 1. Nguyên tắc gợi ý lô (FEFO Suggestion)
- **Mặc định gợi ý lô có hạn sử dụng gần nhất còn đủ số lượng**:
  - Khi cần xuất một số lượng hàng hóa (quy đổi về đơn vị cơ sở `baseQuantity`), hệ thống rà soát tất cả các lô còn tồn kho (`baseQuantity > 0`) của sản phẩm tại kho chỉ định.
  - Các lô chưa hết hạn (`expiryDate >= today`) được ưu tiên sắp xếp trước theo thứ tự `expiryDate` tăng dần (ngày hết hạn sớm nhất lên đầu). Nếu cùng hạn sử dụng, ưu tiên lô có ngày sản xuất (`manufacturingDate`) cũ hơn.
  - Lô đầu tiên có tồn kho đáp ứng đủ số lượng yêu cầu (`lot.baseQuantity >= requestedBaseQuantity`) được chọn làm lô gợi ý mặc định (`suggestedLot`).
  - Nếu không có lô đơn lẻ nào đủ số lượng yêu cầu, `suggestedLot` trả về `null`, cờ `hasSufficientStock = false`, và `availableLots` trả về toàn bộ danh sách các lô còn hàng sắp xếp theo FEFO để người dùng tham khảo chia dòng hoặc xuất theo từng phần.

#### 2. Quyền chọn lô khác và Bắt buộc nhập lý do
- Thủ kho có thể chủ động chọn một lô khác (ví dụ: khách hàng yêu cầu lấy lô date xa, hoặc chọn lô gần cửa bốc dỡ):
  - **Trường hợp chọn đúng lô gợi ý** (hoặc không truyền `lotNumber` để hệ thống tự áp dụng lô gợi ý): Không bắt buộc nhập lý do.
  - **Trường hợp chọn lô khác với lô gợi ý**: Thủ kho **bắt buộc phải nhập lý do** (qua trường `overrideReason`, bí danh `lotReason`, hoặc `note`). Nếu để trống lý do, hệ thống sẽ từ chối tạo phiếu xuất kho và trả lỗi `400 Bad Request` (`"Thủ kho chọn lô '...' khác với lô gợi ý hạn dùng gần nhất ('...'), bắt buộc phải nhập lý do"`).
  - Lý do chọn lô và mã lô gợi ý ban đầu được ghi nhận vào dòng phiếu kho (`WarehouseVoucherItem.suggestedLotNumber`, `WarehouseVoucherItem.overrideReason`) để phục vụ kiểm toán và báo cáo.

#### 3. Xuất kho trừ đúng tồn của lô đã chọn & Chặn âm kho
- Khi ghi sổ phiếu xuất kho (`POST /api/warehouse/export`):
  - Hệ thống kiểm tra số lượng tồn của lô đã chọn trong bảng `warehouse_lot_inventories`.
  - Trừ chính xác số lượng xuất quy đổi cơ sở vào tồn của lô đó (`lotInv.subtractBaseQuantity(baseQty)`).
  - Nếu lô được chọn không đủ tồn (`lotInv.baseQuantity < baseQty`) hoặc lô không tồn tại trong kho: Hệ thống từ chối giao dịch, quăng lỗi `400 Bad Request` và rollback toàn bộ thay đổi.
  - Đồng thời cập nhật giảm số lượng tồn kho tổng thể của sản phẩm tại kho (`warehouse_inventories.baseQuantity`).

#### 4. Chi tiết Request / Response API Gợi ý lô

##### `GET /api/warehouse/export/suggest-lot`
- **Quyền**: `WAREHOUSE_EXPORT` hoặc `WAREHOUSE_VIEW` từ ma trận phân quyền, hoặc `ROLE_ADMIN`. Kiểm tra phạm vi kho phân công.
- **Query Params**:
  - `warehouseId` (Long, bắt buộc): ID kho xuất hàng.
  - `productId` (Long, bắt buộc): ID sản phẩm cần xuất.
  - `quantity` (BigDecimal, tùy chọn, mặc định 1): Số lượng cần xuất.
  - `unit` (String, tùy chọn): Đơn vị tính (nếu bỏ trống dùng đơn vị cơ sở).

**Ví dụ Response (`200 OK`):**
```json
{
  "success": true,
  "message": "Gợi ý lô xuất kho theo FEFO",
  "data": {
    "warehouseId": 1,
    "warehouseCode": "KHO-HCM",
    "warehouseName": "Kho Hồ Chí Minh",
    "productId": 10,
    "productCode": "SP-SUA-100",
    "productName": "Sữa tươi tiệt trùng 1L",
    "manageLot": true,
    "requestedQuantity": 10.0,
    "requestedUnit": "lon",
    "requestedBaseQuantity": 10.0,
    "baseUnit": "lon",
    "suggestedLot": {
      "lotId": 2,
      "lotNumber": "LOT-2026-B",
      "expiryDate": "2026-11-20",
      "manufacturingDate": "2026-10-10",
      "availableQuantity": 20.0,
      "baseUnit": "lon",
      "isExpired": false,
      "daysUntilExpiry": 29,
      "sufficientForRequest": true
    },
    "availableLots": [
      {
        "lotId": 1,
        "lotNumber": "LOT-2026-A",
        "expiryDate": "2026-11-01",
        "manufacturingDate": "2026-09-20",
        "availableQuantity": 5.0,
        "baseUnit": "lon",
        "isExpired": false,
        "daysUntilExpiry": 10,
        "sufficientForRequest": false
      },
      {
        "lotId": 2,
        "lotNumber": "LOT-2026-B",
        "expiryDate": "2026-11-20",
        "manufacturingDate": "2026-10-10",
        "availableQuantity": 20.0,
        "baseUnit": "lon",
        "isExpired": false,
        "daysUntilExpiry": 29,
        "sufficientForRequest": true
      }
    ],
    "hasSufficientStock": true,
    "message": "Gợi ý xuất lô 'LOT-2026-B' (hạn dùng gần nhất còn đủ số lượng)"
  }
}
```

##### `POST /api/warehouse/export/suggest-lots`
- **Quyền**: `WAREHOUSE_EXPORT` hoặc `WAREHOUSE_VIEW` hoặc `ROLE_ADMIN`.
- **Request Body**:
```json
{
  "warehouseId": 1,
  "items": [
    {
      "productId": 10,
      "quantity": 10,
      "unit": "lon"
    }
  ]
}
```
- **Response**: Trả về danh sách `List<LotSuggestionResponse>` cho từng mặt hàng.

##### `POST /api/warehouse/export`
- **Quyền**: `WAREHOUSE_EXPORT` hoặc `ROLE_ADMIN`. Kiểm tra phạm vi kho phân công.
- **Request Body** (Ví dụ khi thủ kho chọn lô khác và nhập lý do):
```json
{
  "warehouseId": 1,
  "type": "EXPORT",
  "voucherCode": "PXK-202610-001",
  "note": "Xuất hàng giao đại lý",
  "items": [
    {
      "productId": 10,
      "quantity": 10,
      "unit": "lon",
      "lotNumber": "LOT-2026-C",
      "overrideReason": "Khách hàng yêu cầu date xa để vận chuyển tỉnh"
    }
  ]
}
```
- **Response**: Trả về `WarehouseVoucherResponse` chứa `suggestedLotNumber` và `overrideReason` trên từng dòng sản phẩm.

### S6-01 - Theo dõi Lô và Hạn sử dụng của Hàng hoá (Backend) - 22/10/2026

Tất cả các endpoint dưới đây có prefix `/api`. Quyền được lấy từ ma trận phân quyền database-backed, không dùng vai trò cứng. `ROLE_ADMIN` luôn có toàn quyền.

#### Danh sách API Lô & Hạn sử dụng (S6-01):

Cập nhật kết nối frontend 22/10/2026: trang `/warehouse/lots` dùng `GET /api/warehouse/lots/options` (WAREHOUSE_VIEW hoặc WAREHOUSE_MANAGE; ADMIN luôn được phép). Response envelope `data` gồm `warehouses` đang hoạt động trong phạm vi phân công, `products` gồm cả SKU bật/tắt lô (LotThresholdResponse), `statuses`, `minimumThresholdDays`, `maximumThresholdDays`, `asOfDate`, `canConfigure`, `canExport`, `canOverrideExpiredLot`. Các cờ là khả năng thực tế của phiên hiện tại; không thay thế kiểm quyền ở API ghi.

`GET /inventory/lots` và `/lot-expiry-alerts` không có warehouseId chỉ trả lô trong phạm vi kho; chỉ định kho ngoài phạm vi trả 403. Ngưỡng xem nhanh phải >= 1; trạng thái danh sách không hợp lệ trả 400. Ngày hết hạn bằng hôm nay chưa quá hạn; quá hạn khi trước ngày server. Cảnh báo chỉ đếm lô còn số lượng dương.

PATCH cấu hình SKU lưu `manageLot` và `expiryWarningDays` (số nguyên >=1). Không được tắt quản lý lô khi SKU còn lượng tồn lô dương; trả 400, không đổi cấu hình. Bảo vệ này cũng áp dụng khi cập nhật sản phẩm đầy đủ.

Xuất hàng luôn đối chiếu hạn dùng/ngày sản xuất từ lô trong DB, không dùng ngày client gửi để vượt chặn quá hạn. SKU legacy tắt quản lý lô nhưng còn tồn lô vẫn bị kiểm tra lô khi xuất. Lô thiếu hạn dùng không được ghi sổ xuất. Ghi đè cần EXPORT và (APPROVE hoặc MANAGE), `overrideExpiredLot=true`, lý do không rỗng tối đa 255 ký tự; ADMIN vẫn phải xác nhận/lý do. Thiếu quyền trả 403; thiếu xác nhận/lý do trả 400. Phiếu/tồn/lô/nhật ký cùng transaction.

| Method | Endpoint | Quyền ma trận | Mô tả |
|---|---|---|---|
| `PATCH` | `/products/{id}/manage-lot` | `PRODUCT_EDIT` hoặc `WAREHOUSE_MANAGE` hoặc `ROLE_ADMIN` | Bật/tắt quản lý lô theo từng SKU, có thể cấu hình kèm `expiryWarningDays` |
| `GET` | `/warehouse/inventory/lots` | `WAREHOUSE_VIEW` hoặc `WAREHOUSE_MANAGE` hoặc `ROLE_ADMIN` | Danh sách tồn kho theo lô (hỗ trợ lọc `warehouseId`, `productId`, `status`: EXPIRED/EXPIRING_SOON/ACTIVE/ALL, `thresholdDays`, `keyword`) |
| `GET` | `/warehouse/lot-expiry-alerts` | `WAREHOUSE_VIEW` hoặc `WAREHOUSE_MANAGE` hoặc `ROLE_ADMIN` | Danh sách cảnh báo lô sắp hết hạn / quá hạn kèm tổng hợp thống kê theo ngưỡng |
| `GET` | `/warehouse/lot-thresholds` | `WAREHOUSE_VIEW` hoặc `WAREHOUSE_MANAGE` hoặc `ROLE_ADMIN` | Danh sách ngưỡng cảnh báo hạn dùng theo từng SKU có quản lý lô |
| `PUT` | `/warehouse/lot-thresholds/{productId}` | `WAREHOUSE_MANAGE` hoặc `ROLE_ADMIN` | Khai báo / cập nhật ngưỡng cảnh báo hạn dùng (số ngày) cho SKU |
| `POST` | `/warehouse/export` | `WAREHOUSE_EXPORT` hoặc `ROLE_ADMIN` | Lập phiếu xuất kho. Lô quá hạn BỊ CHẶN trừ khi Quản lý kho (`WAREHOUSE_APPROVE` / `WAREHOUSE_MANAGE` / `ROLE_ADMIN`) xác nhận ghi đè kèm lý do |
| `POST` | `/warehouse/vouchers/{id}/confirm` | `WAREHOUSE_APPROVE` hoặc `ROLE_ADMIN` | Xác nhận phiếu kho nháp (nếu là phiếu xuất có lô quá hạn, bắt buộc đã được ghi đè kèm lý do) |

#### 1. Bật hoặc tắt quản lý lô theo từng SKU
- **Endpoint**: `PATCH /api/products/{id}/manage-lot`
- **Request Body**:
```json
{
  "manageLot": true,
  "expiryWarningDays": 20
}
```
- **Response**: Trả về `ProductResponse` với `manageLot: true`, `expiryWarningDays: 20`.
- **Quy tắc**: Khi bật `manageLot = true`, khi nhập kho hoặc xuất kho bắt buộc phải chọn/nhập số lô. Ngưỡng cảnh báo hạn sử dụng mặc định là 30 ngày nếu không khai báo cụ thể.

#### 2. Mỗi lô có số lô, ngày sản xuất, hạn sử dụng và số lượng còn lại
- **Endpoint**: `GET /api/warehouse/inventory/lots`
- **Query params**:
  - `warehouseId` (tùy chọn)
  - `productId` (tùy chọn)
  - `status` (tùy chọn: `ALL`, `EXPIRED`, `EXPIRING_SOON`, `ACTIVE`)
  - `thresholdDays` (tùy chọn: ghi đè ngưỡng cảnh báo khi tính `expiringSoon`)
  - `keyword` (tùy chọn: tìm theo số lô, mã SKU, tên hàng)
- **Response item**:
```json
{
  "id": 102,
  "warehouseId": 1,
  "warehouseCode": "KHO-HCM",
  "warehouseName": "Kho HCM",
  "productId": 10,
  "productCode": "SP-SUA01",
  "productName": "Sữa tươi tiệt trùng 1L",
  "lotNumber": "LOT-2026-02",
  "manufacturingDate": "2026-09-02",
  "expiryDate": "2026-10-29",
  "baseQuantity": 50.0,
  "baseUnit": "hộp",
  "warningThresholdDays": 15,
  "daysUntilExpiry": 7,
  "isExpired": false,
  "isExpiringSoon": true,
  "status": "EXPIRING_SOON",
  "updatedAt": "2026-10-22T00:00:00"
}
```

#### 3. Cảnh báo lô sắp hết hạn theo ngưỡng khai báo được
- **Endpoint**: `GET /api/warehouse/lot-expiry-alerts`
- **Query params**: `warehouseId`, `productId`, `thresholdDays`, `status` (`ALL`, `EXPIRED`, `EXPIRING_SOON`)
- **Response**:
```json
{
  "success": true,
  "message": "Danh sách cảnh báo hạn sử dụng lô",
  "data": {
    "totalAlerts": 2,
    "expiredCount": 1,
    "expiringSoonCount": 1,
    "defaultThresholdDays": 30,
    "items": [
      {
        "id": 103,
        "lotNumber": "LOT-2026-03",
        "expiryDate": "2026-10-19",
        "daysUntilExpiry": -3,
        "isExpired": true,
        "isExpiringSoon": false,
        "status": "EXPIRED",
        "baseQuantity": 20.0,
        "baseUnit": "hộp"
      },
      {
        "id": 102,
        "lotNumber": "LOT-2026-02",
        "expiryDate": "2026-10-29",
        "daysUntilExpiry": 7,
        "isExpired": false,
        "isExpiringSoon": true,
        "status": "EXPIRING_SOON",
        "baseQuantity": 50.0,
        "baseUnit": "hộp"
      }
    ]
  }
}
```
- **Khai báo cấu hình ngưỡng SKU**: `PUT /api/warehouse/lot-thresholds/{productId}`
```json
{
  "thresholdDays": 15
}
```

#### 4. Không cho xuất lô đã quá hạn, trừ khi Quản lý kho xác nhận ghi đè kèm lý do
- **Endpoint**: `POST /api/warehouse/export`
- **Quy tắc chặn nghiêm ngặt**:
  - Khi xuất hàng có chỉ định `lotNumber`, hệ thống tự động kiểm tra `expiryDate` của lô trong kho.
  - Nếu lô hàng đã quá hạn sử dụng (`expiryDate < today`):
    - Tài khoản không có quyền Quản lý kho (thiếu `WAREHOUSE_APPROVE`, `WAREHOUSE_MANAGE` và không phải `ROLE_ADMIN`): **Bị chặn 403 Forbidden** với thông điệp: `"Lô hàng '...' của sản phẩm '...' đã quá hạn sử dụng (...). Chỉ Quản lý kho mới có quyền xác nhận ghi đè xuất lô đã quá hạn."`
    - Tài khoản Quản lý kho nhưng chưa xác nhận cờ ghi đè (`overrideExpiredLot != true`): **Bị chặn 400 Bad Request** với thông điệp: `"Lô hàng '...' đã quá hạn sử dụng (...). Cần Quản lý kho xác nhận ghi đè để xuất hàng."`
    - Tài khoản Quản lý kho xác nhận ghi đè nhưng bỏ trống lý do: **Bị chặn 400 Bad Request** với thông điệp: `"Bắt buộc phải nhập lý do khi Quản lý kho xác nhận ghi đè xuất lô đã quá hạn '...'."`
    - Tài khoản Quản lý kho xác nhận ghi đè (`overrideExpiredLot = true`) VÀ có lý do hợp lệ (`expiredLotOverrideReason` không rỗng): **Cho phép xuất hàng**. Phiếu kho và các dòng chi tiết ghi nhận `overrideExpiredLot = true`, lưu lại `expiredLotOverrideReason`, và ghi Audit Log hành động `EXPIRED_LOT_OVERRIDE` (19 ký tự, phù hợp cột action 20 ký tự).
- **Request Body mẫu ghi đè xuất lô quá hạn**:
```json
{
  "warehouseId": 1,
  "overrideExpiredLot": true,
  "expiredLotOverrideReason": "Xuất hàng trả về nhà cung cấp để tiêu huỷ theo biên bản số 12/BB-TH",
  "items": [
    {
      "productId": 10,
      "lotNumber": "LOT-2026-03",
      "quantity": 20,
      "unit": "hộp"
    }
  ]
}
```

---

## S6-07 — Theo dõi hàng đã xuất nhưng chưa giao xong & Tiền hàng trên đường (22/10/2026)

> Cập nhật 23/10/2026: xem mục S6-07 đầu tài liệu cho frontend chỉ đọc, `outstandingOnly`, số liệu trách nhiệm mọi trạng thái và cách loại hàng đã xác nhận trả kho. Các API ghi dưới đây giữ nguyên, không được giao diện theo dõi gọi.

Tài liệu hợp đồng API phục vụ Quản lý kho theo dõi các chuyến giao hàng đang chạy, kiểm soát số điểm đã giao / còn lại, số tiền hàng đang nằm trên đường, và cảnh báo chuyến giao quá thời gian dự kiến.

### 1. Nguyên tắc nghiệp vụ cốt lõi

1. **Theo dõi tiền hàng trên đường**:
   - `totalValue`: Tổng tiền hàng xuất phát của chuyến.
   - `deliveredValue`: Giá trị hàng đã giao thành công tại các điểm `DELIVERED`.
   - `remainingValue` (Tiền hàng trên đường): Bằng `totalValue - deliveredValue`. Bao gồm giá trị hàng tại các điểm chờ giao (`PENDING`) và các điểm chưa giao được (`FAILED`).
2. **Quy tắc bất biến hàng chưa giao được**:
   - Hàng tại điểm giao chưa giao được (`status = FAILED`) **vẫn thuộc trách nhiệm của chuyến/tài xế, tuyệt đối KHÔNG tự động hoàn về tồn kho**.
   - `returnedToWarehouse` được khởi tạo là `false`. Tồn kho `WarehouseInventory` không đổi khi giao thất bại.
   - Chỉ khi thực hiện thủ tục nhập kho trả hàng tường minh qua API `POST /stops/{stopId}/return-to-warehouse`, tồn kho mới được ghi nhận cộng lại.
3. **Cảnh báo chuyến quá thời gian dự kiến**:
   - Chuyến ở trạng thái `IN_PROGRESS` có thời điểm hiện tại vượt qua `expectedCompletionTime` sẽ được gắn cờ `isOverdue = true`, đi kèm số phút quá hạn `overdueMinutes` và thông báo cảnh báo `overdueWarning`.
   - Hỗ trợ lọc nhanh danh sách chuyến quá hạn bằng tham số `isOverdue=true`.
4. **Phân quyền ma trận (Database-backed Permission Matrix)**:
   - Các API xem/tra cứu/thống kê (`GET`): Bắt buộc quyền `WAREHOUSE_VIEW` hoặc `WAREHOUSE_EXPORT` hoặc `ROLE_ADMIN`.
   - Các API thao tác nghiệp vụ (tạo chuyến, cập nhật tiến trình, hoàn tất, hủy, nhập trả kho): Bắt buộc quyền `WAREHOUSE_EXPORT` hoặc `ROLE_ADMIN`.
   - Thu hồi quyền trên phiên JWT hiện tại lập tức có hiệu lực và trả về `403 Forbidden` trên API request kế tiếp (không fallback theo role).
   - Phân vùng dữ liệu kho (Warehouse Scoping): Tài khoản kho chỉ được thao tác và xem các chuyến giao thuộc kho được phân công (`user.getWarehouses()`). Truy cập kho ngoài phạm vi trả về `403 Forbidden`.

### 2. Danh sách Endpoints (`/api/warehouse/delivery-trips`)

| Method | Endpoint | Quyền yêu cầu | Mô tả |
|---|---|---|---|
| `GET` | `/options` | `WAREHOUSE_VIEW` hoặc `WAREHOUSE_EXPORT` hoặc `ADMIN` | Lấy danh sách kho trong phạm vi quyền, danh mục trạng thái chuyến và điểm giao |
| `GET` | `` | `WAREHOUSE_VIEW` hoặc `WAREHOUSE_EXPORT` hoặc `ADMIN` | Tìm kiếm, lọc và phân trang danh sách chuyến giao hàng (`warehouseId`, `status`, `isOverdue`, `keyword`, `page`, `size`) |
| `GET` | `/summary` | `WAREHOUSE_VIEW` hoặc `WAREHOUSE_EXPORT` hoặc `ADMIN` | Thống kê tổng hợp: số chuyến đang chạy, số chuyến quá hạn, tổng tiền hàng trên đường, tổng điểm đã giao/còn lại |
| `GET` | `/{id}` | `WAREHOUSE_VIEW` hoặc `WAREHOUSE_EXPORT` hoặc `ADMIN` | Xem chi tiết chuyến giao hàng, danh sách điểm giao và các mặt hàng chi tiết |
| `POST` | `` | `WAREHOUSE_EXPORT` hoặc `ADMIN` | Tạo mới chuyến giao hàng (phân tuyến theo khu vực `territoryId`, `routeName`, gom theo `shipmentVoucherIds` hoặc `orderIds` hoặc `stops` tùy chỉnh) |
| `PUT` | `/{id}` | `WAREHOUSE_EXPORT` hoặc `ADMIN` | Cập nhật thông tin chuyến đang giao (gán tài xế, SĐT, biển số xe, tên tuyến đường, khu vực `territoryId`, giờ dự kiến, ghi chú) |
| `PUT` | `/{id}/stops/{stopId}/status` | `WAREHOUSE_EXPORT` hoặc `ADMIN` | Cập nhật trạng thái điểm giao (`DELIVERED` hoặc `FAILED` kèm lý do). Hàng FAILED không tự về kho |
| `POST` | `/{id}/complete` | `WAREHOUSE_EXPORT` hoặc `ADMIN` | Hoàn tất chuyến giao hàng. Cảnh báo nếu còn điểm chưa giao được |
| `POST` | `/{id}/cancel` | `WAREHOUSE_EXPORT` hoặc `ADMIN` | Hủy chuyến giao hàng (bắt buộc lý do; chặn hủy nếu đã có điểm giao thành công) |
| `POST` | `/{id}/stops/{stopId}/return-to-warehouse` | `WAREHOUSE_EXPORT` hoặc `ADMIN` | Thao tác tường minh: Nhập trả lại kho hàng chưa giao được của điểm giao thất bại |
| `GET` | `/eligible-orders` | `WAREHOUSE_VIEW` hoặc `WAREHOUSE_EXPORT` hoặc `ADMIN` | Danh sách đơn hàng/phiếu xuất đã xuất kho sẵn sàng gán chuyến, hỗ trợ lọc theo kho (`warehouseId`), khu vực (`territoryId`), và từ khóa (`keyword`) |
| `GET` | `/{id}/manifest` | `WAREHOUSE_VIEW` hoặc `WAREHOUSE_EXPORT` hoặc `ADMIN` | Bảng kê danh sách điểm giao của chuyến kèm tên, SĐT, địa chỉ người nhận, mã phiếu xuất, số tiền và hàng hóa chi tiết |
| `GET` | `/{id}/manifest/pdf` | `WAREHOUSE_VIEW` hoặc `WAREHOUSE_EXPORT` hoặc `ADMIN` | Xuất file PDF bảng kê danh sách điểm giao chuyến (A4, OpenPDF) phục vụ tài xế giao hàng |
| `GET` | `/{id}/manifest/print` | `WAREHOUSE_VIEW` hoặc `WAREHOUSE_EXPORT` hoặc `ADMIN` | Xuất HTML bảng kê điểm giao hỗ trợ xem trước và in trực tiếp (`window.print()`) |

### 3. Payload mẫu

#### Tạo mới chuyến giao hàng (`POST /api/warehouse/delivery-trips`)

```json
{
  "warehouseId": 1,
  "tripCode": "CG-20261022-001",
  "driverName": "Nguyễn Văn Lái",
  "driverPhone": "0988123456",
  "vehiclePlate": "29C-123.45",
  "departureTime": "2026-10-22T08:00:00",
  "expectedCompletionTime": "2026-10-22T12:00:00",
  "note": "Giao hàng khu vực quận Hoàn Kiếm",
  "stops": [
    {
      "orderCode": "DH-001",
      "receiverName": "Đại lý Mai Linh",
      "receiverPhone": "0912345678",
      "deliveryAddress": "123 Phố Huế, Hoàn Kiếm, Hà Nội",
      "deliveryAmount": 15000000.00,
      "stopOrder": 1,
      "items": [
        {
          "productId": 10,
          "productCode": "BIA-HN",
          "productName": "Bia Hà Nội Lon 330ml",
          "unit": "thùng",
          "quantity": 50,
          "unitPrice": 300000.00
        }
      ]
    }
  ]
}
```

#### Cập nhật trạng thái điểm giao (`PUT /api/warehouse/delivery-trips/{id}/stops/{stopId}/status`)

```json
{
  "status": "FAILED",
  "failedReason": "Cửa hàng đóng cửa, hẹn giao lại vào sáng mai",
  "note": "Đã gọi điện xác nhận với chủ cửa hàng"
}
```
*(Ghi chú: Khi `status` là `FAILED`, hàng vẫn ở trên xe thuộc trách nhiệm chuyến giao, không cộng lại tồn kho).*

#### Nhập trả kho hàng chưa giao được (`POST /api/warehouse/delivery-trips/{id}/stops/{stopId}/return-to-warehouse`)

```json
{
  "returnReason": "Thủ kho đã kiểm đếm và nhận lại 50 thùng hàng nguyên vẹn",
  "note": "Biên bản hoàn trả số BB-0123"
}
```

#### Cập nhật chuyến giao hàng (`PUT /api/warehouse/delivery-trips/{id}`)

```json
{
  "driverName": "Trần Văn Bình",
  "driverPhone": "0912987654",
  "vehiclePlate": "30A-999.88",
  "routeName": "Tuyến Đông Anh - Mê Linh",
  "territoryId": 2,
  "expectedCompletionTime": "2026-10-25T17:30:00",
  "note": "Ưu tiên giao điểm số 1 trước 10h sáng"
}
```

#### Bảng kê điểm giao của chuyến (`GET /api/warehouse/delivery-trips/{id}/manifest`)

```json
{
  "id": 1,
  "tripCode": "CG-20261025-001",
  "status": "IN_PROGRESS",
  "driverName": "Trần Văn Bình",
  "driverPhone": "0912987654",
  "vehiclePlate": "30A-999.88",
  "warehouseName": "Kho Tổng Hà Nội",
  "routeName": "Tuyến Đông Anh - Mê Linh",
  "territoryName": "Miền Bắc",
  "totalStops": 2,
  "totalAmount": 25000000.00,
  "stops": [
    {
      "stopId": 10,
      "stopOrder": 1,
      "orderId": 101,
      "orderCode": "DH-001",
      "voucherCode": "PX-20261025-001",
      "receiverName": "Đại lý Thành Đạt",
      "receiverPhone": "0988776655",
      "deliveryAddress": "Khu công nghiệp Thăng Long, Đông Anh, Hà Nội",
      "deliveryAmount": 15000000.00,
      "status": "PENDING",
      "items": [
        {
          "productName": "Sữa chua Nếp Cẩm",
          "unit": "thùng",
          "quantity": 50,
          "unitPrice": 300000.00
        }
      ]
    }
  ]
}
```


---

## S7-04 — Hệ thống tự cảnh báo và khoá bán công nợ (22/10/2026)

Tài liệu hợp đồng API phục vụ Bộ phận Kế toán - Tài chính và Quản lý kinh doanh quản lý rủi ro công nợ tự động, kiểm soát hạn mức nợ tiền, số ngày cho phép nợ, tự động khóa bán hoặc chuyển duyệt đơn khi đại lý vi phạm, và tự động gỡ cờ khi thu hồi công nợ.

### 1. Nguyên tắc nghiệp vụ cốt lõi

1. **Tự động gắn cờ đại lý vi phạm công nợ**:
   - **Vượt hạn mức nợ tiền (`DEBT_LIMIT_EXCEEDED`)**: Khi công nợ hiện tại (`currentDebt = tổng đơn ghi sổ - tổng tiền đã thu`) lớn hơn hạn mức nợ cho phép (`debtLimit`) của đại lý.
   - **Khoản nợ quá số ngày cho phép (`OVERDUE`)**: Khi đại lý có ít nhất một đơn hàng đã ghi sổ vượt quá số ngày nợ tối đa (`maxDebtDays`) tính từ ngày ghi sổ/tạo đơn mà chưa được thanh toán đủ theo nguyên tắc phân bổ thu tiền FIFO (tiền thu ưu tiên cấn trừ đơn nợ cũ nhất trước).
   - **Cả hai vi phạm (`BOTH`)**: Đại lý đồng thời vừa vượt hạn mức tiền vừa có khoản nợ quá hạn.
   - Cờ vi phạm gồm: `debtFlagged` (boolean), `debtFlagType` (`NONE`, `DEBT_LIMIT_EXCEEDED`, `OVERDUE`, `BOTH`), `debtFlagReason` (mô tả chi tiết số tiền vượt và số ngày nợ quá hạn), `debtFlaggedAt` và `lastDebtEvaluatedAt`.

2. **Hành vi xử lý đơn hàng mới theo cấu hình kiểm soát rủi ro**:
   - **Cấu hình toàn hệ thống (`DebtRiskConfig`)**:
     - `limitExceededAction`: Hành vi mặc định khi vượt hạn mức tiền (`BLOCK` hoặc `REQUIRE_APPROVAL`).
     - `overdueAction`: Hành vi mặc định khi có nợ quá hạn (`BLOCK` hoặc `REQUIRE_APPROVAL`).
     - `defaultAction`: Hành vi dự phòng chung (`BLOCK` hoặc `REQUIRE_APPROVAL`).
     - `autoFlagEnabled`: Bật/tắt tính năng tự động gắn cờ.
     - Khi bảng chưa có bản ghi, các API GET/đọc đơn/đọc công nợ chỉ trả cấu hình mặc định trong bộ nhớ (`id = null`), **không INSERT trong giao dịch read-only**. Chỉ `PUT /api/finance/debt/config` với `FINANCE_MANAGE` hoặc ADMIN mới lưu cấu hình. Mặc định chưa lưu: `BLOCK`/`BLOCK`/`REQUIRE_APPROVAL`/`true` theo thứ tự default/overdue/limit/autoFlag. Lỗi truy vấn DB phải được ném ra, không nuốt lỗi rồi dựng cấu hình thành công.
   - **Cấu hình riêng biệt cho từng đại lý (`Dealer.debtActionConfig`)**:
     - `DEFAULT`: Áp dụng theo cấu hình hệ thống tương ứng với loại vi phạm.
     - `BLOCK`: Khóa bán hoàn toàn. Nhân viên kinh doanh / đại lý tạo đơn mới sẽ bị chặn ngay lập tức, API trả về `400 Bad Request` kèm thông báo chi tiết lý do bị khóa và mã đại lý.
     - `REQUIRE_APPROVAL`: Cho phép ghi nhận tạo đơn nhưng đơn hàng tự động đánh dấu `orderRequiresApproval = true`, trạng thái chuyển `PENDING_APPROVAL`, bổ sung cảnh báo vi phạm công nợ vào đơn để Quản lý kinh doanh phê duyệt.

3. **Tác vụ đánh giá công nợ tự động và tự động gỡ cờ**:
   - **Tác vụ định kỳ hằng ngày**: Chạy tự động mỗi ngày một lần vào lúc 01:00 AM (`@Scheduled(cron = "${app.debt.evaluation.cron:0 0 1 * * ?}")`) quét toàn bộ đại lý, kiểm tra công nợ và ngày nợ thực tế để cập nhật cờ rủi ro.
   - **Tự động chạy lại sau khi thu tiền**: Sau mỗi lần ghi nhận phiếu thu tiền công nợ (`POST /api/finance/debt-payments`), hệ thống tự động kích hoạt đánh giá lại công nợ ngay lập tức cho đại lý đó. Nếu công nợ giảm về dưới hạn mức và không còn nợ quá hạn, hệ thống tự động gỡ cờ (`debtFlagged = false`, `debtFlagType = NONE`, `debtFlagReason = null`).
   - **Đánh giá thủ công theo yêu cầu**: Hỗ trợ gọi API chạy đánh giá toàn bộ đại lý (`POST /api/finance/debt/evaluate`) hoặc một đại lý cụ thể (`POST /api/finance/debt/{dealerId}/evaluate`).

4. **Phân quyền ma trận (Database-backed Permission Matrix)**:
   - Nguồn chân lý duy nhất là ma trận phân quyền lưu trong database, không dùng fallback vai trò mặc định.
   - Các API đọc / tra cứu (`GET /api/finance/debt/config`, `/flagged-dealers`, `/debt-payments`): Bắt buộc quyền `FINANCE_VIEW_DEBT` hoặc `FINANCE_MANAGE` hoặc `ROLE_ADMIN`.
   - Các API ghi / thay đổi cấu hình / thu tiền / đánh giá công nợ (`PUT /api/finance/debt/config`, `PUT /api/finance/debt/{dealerId}/action-config`, `POST /api/finance/debt-payments`, `POST /api/finance/debt/evaluate`, `POST /api/finance/debt/{dealerId}/evaluate`): Bắt buộc quyền `FINANCE_MANAGE` hoặc `ROLE_ADMIN`.
   - Thu hồi quyền `FINANCE_MANAGE` hoặc `FINANCE_VIEW_DEBT` có hiệu lực ngay lập tức trên các request tiếp theo của cùng token JWT, trả về `403 Forbidden`.
   - `ROLE_ADMIN` luôn có toàn quyền truy cập.

---

### 2. Danh sách Endpoints Quản lý Rủi ro Công nợ & Thu tiền

| Method | Endpoint | Quyền yêu cầu | Mô tả |
|---|---|---|---|
| `GET` | `/api/finance/debt/config` | `FINANCE_VIEW_DEBT`, `FINANCE_MANAGE` hoặc `ADMIN` | Đọc cấu hình, không tạo bản ghi khi bảng còn rỗng; tránh lỗi rollback-only của MySQL do ghi trong giao dịch chỉ đọc |
| `PUT` | `/api/finance/debt/config` | `FINANCE_MANAGE` hoặc `ADMIN` | Cập nhật cấu hình rủi ro công nợ hệ thống (hành vi khi vượt hạn mức, khi quá hạn, bật/tắt tự động) |
| `PUT` | `/api/finance/debt/{dealerId}/action-config` | `FINANCE_MANAGE` hoặc `ADMIN` | Cập nhật hành vi xử lý công nợ riêng biệt cho một đại lý (`DEFAULT`, `BLOCK`, `REQUIRE_APPROVAL`) |
| `GET` | `/api/finance/debt/flagged-dealers` | `FINANCE_VIEW_DEBT`, `FINANCE_MANAGE` hoặc `ADMIN` | Lấy danh sách các đại lý đang bị gắn cờ cảnh báo công nợ |
| `POST` | `/api/finance/debt/evaluate` | `FINANCE_MANAGE` hoặc `ADMIN` | Chạy tác vụ đánh giá lại công nợ thủ công cho toàn bộ đại lý |
| `POST` | `/api/finance/debt/{dealerId}/evaluate` | `FINANCE_MANAGE` hoặc `ADMIN` | Chạy tác vụ đánh giá lại công nợ cho một đại lý cụ thể |
| `POST` | `/api/finance/debt-payments` | `FINANCE_MANAGE` hoặc `ADMIN` | Ghi nhận phiếu thu tiền công nợ đại lý (tự động đánh giá lại và gỡ cờ) |
| `GET` | `/api/finance/debt-payments` | `FINANCE_VIEW_DEBT`, `FINANCE_MANAGE` hoặc `ADMIN` | Tra cứu lịch sử thu tiền công nợ có lọc theo `dealerId`, `paymentMethod`, khoảng ngày và phân trang |

---

### 3. Chi tiết Hợp đồng Payload và Request/Response Mẫu

#### 3.1 Cấu hình kiểm soát rủi ro hệ thống (`PUT /api/finance/debt/config`)

**Request Body:**
```json
{
  "defaultAction": "BLOCK",
  "overdueAction": "REQUIRE_APPROVAL",
  "limitExceededAction": "BLOCK",
  "autoFlagEnabled": true
}
```

**Response Body:**
```json
{
  "success": true,
  "message": "Đã cập nhật cấu hình kiểm soát rủi ro công nợ",
  "data": {
    "id": 1,
    "defaultAction": "BLOCK",
    "overdueAction": "REQUIRE_APPROVAL",
    "limitExceededAction": "BLOCK",
    "autoFlagEnabled": true,
    "updatedAt": "2026-10-22T16:30:00"
  }
}
```

---

#### 3.2 Cấu hình hành vi xử lý công nợ riêng cho đại lý (`PUT /api/finance/debt/{dealerId}/action-config`)

**Request Body:**
```json
{
  "action": "REQUIRE_APPROVAL"
}
```

**Response Body:**
```json
{
  "success": true,
  "message": "Đã cập nhật cấu hình hành vi đại lý",
  "data": {
    "id": 12,
    "code": "DL-001",
    "name": "Đại lý Cấp 1 Minh Phát",
    "debtLimit": 50000000.00,
    "maxDebtDays": 30,
    "currentDebt": 55000000.00,
    "debtFlagged": true,
    "debtFlagType": "DEBT_LIMIT_EXCEEDED",
    "debtActionConfig": "REQUIRE_APPROVAL"
  }
}
```

---

#### 3.3 Tra cứu danh sách đại lý bị gắn cờ (`GET /api/finance/debt/flagged-dealers`)

**Response Body:**
```json
{
  "success": true,
  "message": "Danh sách đại lý đang bị gắn cờ cảnh báo công nợ",
  "data": [
    {
      "dealerId": 12,
      "dealerCode": "DL-001",
      "dealerName": "Đại lý Cấp 1 Minh Phát",
      "currentDebt": 55000000.00,
      "debtLimit": 50000000.00,
      "excessDebtAmount": 5000000.00,
      "maxDebtDays": 30,
      "maxOverdueDays": 0,
      "overdueAmount": 0.00,
      "debtFlagType": "DEBT_LIMIT_EXCEEDED",
      "debtFlagReason": "Vượt hạn mức nợ: Nợ hiện tại (55,000,000 đ) vượt hạn mức (50,000,000 đ) một khoản 5,000,000 đ",
      "debtFlaggedAt": "2026-10-22T01:00:00",
      "lastDebtEvaluatedAt": "2026-10-22T01:00:00",
      "configuredAction": "REQUIRE_APPROVAL",
      "effectiveAction": "REQUIRE_APPROVAL"
    }
  ]
}
```

---

#### 3.4 Kích hoạt tác vụ đánh giá công nợ (`POST /api/finance/debt/evaluate`)

**Response Body:**
```json
{
  "success": true,
  "message": "Đã thực hiện tác vụ đánh giá lại công nợ toàn bộ đại lý",
  "data": {
    "evaluatedAt": "2026-10-22T16:35:00",
    "totalEvaluated": 15,
    "totalFlagged": 2,
    "newlyFlagged": 1,
    "unflagged": 0,
    "limitExceededCount": 1,
    "overdueCount": 1,
    "bothCount": 0,
    "flaggedDealers": [
      {
        "dealerId": 12,
        "dealerCode": "DL-001",
        "dealerName": "Đại lý Cấp 1 Minh Phát",
        "currentDebt": 55000000.00,
        "debtLimit": 50000000.00,
        "excessDebtAmount": 5000000.00,
        "maxDebtDays": 30,
        "maxOverdueDays": 0,
        "overdueAmount": 0.00,
        "debtFlagType": "DEBT_LIMIT_EXCEEDED",
        "debtFlagReason": "Vượt hạn mức nợ: Nợ hiện tại (55,000,000 đ) vượt hạn mức (50,000,000 đ) một khoản 5,000,000 đ",
        "debtFlaggedAt": "2026-10-22T16:35:00",
        "lastDebtEvaluatedAt": "2026-10-22T16:35:00",
        "configuredAction": "DEFAULT",
        "effectiveAction": "BLOCK"
      }
    ]
  }
}
```

---

#### 3.5 Ghi nhận phiếu thu tiền công nợ (`POST /api/finance/debt-payments`)

**Request Body:**
```json
{
  "dealerId": 12,
  "amount": 10000000.00,
  "paymentMethod": "BANK_TRANSFER",
  "referenceCode": "UNC-20261022-88",
  "note": "Thu nợ chuyển khoản đợt 2"
}
```

**Response Body:**
```json
{
  "success": true,
  "message": "Ghi nhận thu tiền công nợ thành công",
  "data": {
    "id": 101,
    "paymentCode": "PT-20261022-0001",
    "dealerId": 12,
    "dealerCode": "DL-001",
    "dealerName": "Đại lý Cấp 1 Minh Phát",
    "amount": 10000000.00,
    "paymentDate": "2026-10-22T16:40:00",
    "paymentMethod": "BANK_TRANSFER",
    "referenceCode": "UNC-20261022-88",
    "note": "Thu nợ chuyển khoản đợt 2",
    "recordedBy": "accountant_user",
    "createdAt": "2026-10-22T16:40:00"
  }
}
```
*(Ghi chú: Ngay sau khi phản hồi 201 Created, công nợ đại lý được trừ 10,000,000 đ, hệ thống tự động chạy đánh giá lại và gỡ cờ `debtFlagged = false` nếu công nợ còn lại <= hạn mức nợ và không còn đơn quá hạn).*

---

#### 3.6 Tra cứu danh sách phiếu thu tiền (`GET /api/finance/debt-payments?dealerId=12&page=0&size=20`)

**Response Body:**
```json
{
  "success": true,
  "message": "Danh sách lịch sử thu tiền công nợ",
  "data": {
    "content": [
      {
        "id": 101,
        "paymentCode": "PT-20261022-0001",
        "dealerId": 12,
        "dealerCode": "DL-001",
        "dealerName": "Đại lý Cấp 1 Minh Phát",
        "amount": 10000000.00,
        "paymentDate": "2026-10-22T16:40:00",
        "paymentMethod": "BANK_TRANSFER",
        "referenceCode": "UNC-20261022-88",
        "note": "Thu nợ chuyển khoản đợt 2",
        "recordedBy": "accountant_user",
        "createdAt": "2026-10-22T16:40:00"
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 1,
    "totalPages": 1
  }
}
```

---

## S7-02 — Ghi nhận thanh toán và đối trừ vào công nợ (Backend)

Hỗ trợ Kế toán công nợ (`ROLE_ACCOUNTANT`) ghi nhận thanh toán từ đại lý và đối trừ vào công nợ, để biết chính xác đại lý còn nợ những hoá đơn nào.

### Quy tắc nghiệp vụ cốt lõi:
1. **Thông tin phiếu thu đầy đủ**:
   - Ghi nhận số tiền (`amount` > 0), hình thức thu (`paymentMethod`: `TIEN_MAT`, `CHUYEN_KHOAN`, `KHAC`), ngày thu (`paymentDate`), người thu (`recordedBy`), mã chứng từ tham chiếu (`referenceCode`), và ghi chú (`note`).
   - Mã phiếu thu tự động sinh theo mẫu: `PT-YYYYMMDD-XXXX`.
2. **Đối trừ linh hoạt**:
   - **Tự động đối trừ theo nguyên tắc hoá đơn cũ nhất trước (FIFO)**: Khi `autoAllocate = true` (hoặc không truyền danh sách đối trừ cụ thể), hệ thống tự động tìm các đơn hàng/hoá đơn còn nợ theo thứ tự `recordedAt` cũ nhất trước và cấn trừ lần lượt cho tới khi hết số tiền thu.
   - **Chọn đối trừ vào hoá đơn cụ thể**: Khi `autoAllocate = false` và truyền mảng `allocations`: Kế toán chỉ định rõ đơn hàng nào (`orderId`) được đối trừ bao nhiêu tiền (`amount`). Tổng số tiền đối trừ phải khớp chính xác với số tiền phiếu thu.
3. **Cho phép thu một phần (Partial Payment)**:
   - Một hoá đơn khi được thanh toán một phần (`paidAmount < totalAmount`) sẽ chuyển trạng thái sang **"Thanh toán một phần"** (`paymentStatus = "PARTIALLY_PAID"`).
   - Khi đã thanh toán đủ (`paidAmount >= totalAmount`), chuyển trạng thái sang **"Đã thanh toán"** (`paymentStatus = "PAID"`).
   - Đơn hàng chưa thanh toán có trạng thái `UNPAID`.
4. **Không cho thu vượt tổng công nợ còn lại**:
   - Nếu số tiền thu lớn hơn tổng số nợ còn lại của các đơn hàng đã ghi sổ của đại lý: Hệ thống chặn và trả lỗi **HTTP 400 Bad Request** kèm thông báo chi tiết: *"Số tiền thu (...) không được vượt quá tổng công nợ còn lại của đại lý (...)"*.
   - Với đối trừ chỉ định cụ thể: Không được đối trừ vượt quá số nợ còn lại của từng hoá đơn.
5. **Bất biến: Đã ghi nhận thì không xoá, chỉ lập bút toán điều chỉnh**:
   - API xoá phiếu thu `DELETE /api/finance/debt-payments/{id}` bị chặn và luôn trả về **HTTP 400 Bad Request**: *"Đã ghi nhận thanh toán thì không được xoá, chỉ lập bút toán điều chỉnh"*.
   - Hỗ trợ lập bút toán điều chỉnh qua `POST /api/finance/debt-payments/{id}/adjust`:
     + `REVERSAL`: Huỷ bỏ toàn bộ khoản thu (lập bút toán điều chỉnh hoàn trả toàn bộ số tiền âm, khôi phục nợ và trạng thái các hoá đơn đã đối trừ). Mã bút toán điều chỉnh: `BTDC-YYYYMMDD-XXXX`.
     + `PARTIAL_REVERSAL`: Điều chỉnh giảm một phần số tiền thu (`adjustmentAmount`), hoàn trả tiền từ các hoá đơn được đối trừ gần nhất trở về trước.
6. **Ma trận phân quyền**:
   - Ghi nhận thanh toán và lập bút toán điều chỉnh: Bắt buộc `FINANCE_MANAGE` hoặc `ROLE_ADMIN`.
   - Tra cứu hoá đơn, xem trước đối trừ, xem lịch sử: Bắt buộc `FINANCE_VIEW_DEBT` hoặc `FINANCE_MANAGE` hoặc `ROLE_ADMIN`.
   - Nguồn sự thật duy nhất là ma trận phân quyền trong DB (`RolePermissionConfig`), không fallback theo role cứng.

| Method | Endpoint | Quyền | Mô tả |
|---|---|---|---|
| POST | `/api/finance/debt-payments` | `FINANCE_MANAGE` hoặc ADMIN | Ghi nhận thanh toán và đối trừ vào công nợ (FIFO hoặc cụ thể) |
| GET | `/api/finance/debt-payments/{id}` | `FINANCE_VIEW_DEBT` hoặc ADMIN | Chi tiết phiếu thu tiền kèm danh sách các hoá đơn được đối trừ |
| POST | `/api/finance/debt-payments/{id}/adjust` | `FINANCE_MANAGE` hoặc ADMIN | Lập bút toán điều chỉnh cho phiếu thu đã ghi nhận |
| DELETE | `/api/finance/debt-payments/{id}` | `FINANCE_MANAGE` hoặc ADMIN | Chặn xoá phiếu thu, luôn trả 400 Bad Request |
| GET | `/api/finance/dealers/{dealerId}/invoices` | `FINANCE_VIEW_DEBT` hoặc ADMIN | Danh sách hoá đơn công nợ của đại lý (lọc theo `status`: `UNPAID`, `PARTIALLY_PAID`, `PAID`) |
| GET | `/api/finance/dealers/{dealerId}/unpaid-invoices` | `FINANCE_VIEW_DEBT` hoặc ADMIN | Danh sách các hoá đơn còn nợ của đại lý |
| POST | `/api/finance/debt-payments/preview-allocation` | `FINANCE_VIEW_DEBT` hoặc ADMIN | Xem trước đối trừ tự động theo FIFO trước khi lưu phiếu thu |
| GET | `/api/finance/orders/{orderId}/settlements` | `FINANCE_VIEW_DEBT` hoặc ADMIN | Lịch sử các lần đối trừ vào một đơn hàng/hoá đơn cụ thể |

#### Ví dụ Request Ghi nhận thanh toán có đối trừ cụ thể (`POST /api/finance/debt-payments`):
```json
{
  "dealerId": 12,
  "amount": 15000000,
  "paymentDate": "2026-10-22",
  "paymentMethod": "CHUYEN_KHOAN",
  "referenceCode": "UNC-20261022-99",
  "note": "Thanh toán đợt 1 cho 2 hoá đơn",
  "recordedBy": "Kế toán Nguyễn Văn A",
  "autoAllocate": false,
  "allocations": [
    {
      "orderId": 101,
      "amount": 10000000,
      "note": "Trả hết hoá đơn DH-001"
    },
    {
      "orderId": 102,
      "amount": 5000000,
      "note": "Trả một phần hoá đơn DH-002"
    }
  ]
}
```

#### Ví dụ Response:
```json
{
  "success": true,
  "message": "Ghi nhận thu tiền thành công và đã đánh giá lại công nợ",
  "data": {
    "id": 55,
    "paymentCode": "PT-20261022-0005",
    "dealerId": 12,
    "dealerCode": "DL-001",
    "dealerName": "Đại lý Cấp 1 Minh Phát",
    "amount": 15000000.00,
    "paymentDate": "2026-10-22",
    "paymentMethod": "CHUYEN_KHOAN",
    "referenceCode": "UNC-20261022-99",
    "note": "Thanh toán đợt 1 cho 2 hoá đơn",
    "recordedBy": "Kế toán Nguyễn Văn A",
    "paymentType": "PAYMENT",
    "allocations": [
      {
        "id": 1,
        "orderId": 101,
        "orderCode": "DH-001",
        "allocatedAmount": 10000000.00,
        "orderTotalAmount": 10000000.00,
        "orderPaidAmount": 10000000.00,
        "orderRemainingAmount": 0.00,
        "orderPaymentStatus": "PAID",
        "orderPaymentStatusDisplayName": "Đã thanh toán"
      },
      {
        "id": 2,
        "orderId": 102,
        "orderCode": "DH-002",
        "allocatedAmount": 5000000.00,
        "orderTotalAmount": 12000000.00,
        "orderPaidAmount": 5000000.00,
        "orderRemainingAmount": 7000000.00,
        "orderPaymentStatus": "PARTIALLY_PAID",
        "orderPaymentStatusDisplayName": "Thanh toán một phần"
      }
    ],
    "remainingDebt": 7000000.00,
    "dealerStillFlagged": false
  }
}
```

#### Ví dụ Request Lập bút toán điều chỉnh (`POST /api/finance/debt-payments/55/adjust`):
```json
{
  "reason": "Khách hàng chuyển nhầm đại lý, lập bút toán hoàn trả toàn bộ",
  "adjustmentType": "REVERSAL",
  "recordedBy": "Kế toán trưởng"
}
```

## S7-01 — Phát hành hóa đơn bán hàng từ phiếu đã giao (22/10/2026)

**Bổ sung 23/10/2026:** `GET /api/invoices/{id}/adjustments/{adjustmentId}/pdf` kiểm tra chứng từ thuộc đúng hóa đơn `{id}` trước khi tạo PDF. Sai cặp hóa đơn/chứng từ trả `400 Bad Request`, không gọi dịch vụ xuất PDF. Quyền đọc vẫn là `INVOICE_VIEW` hoặc ADMIN; không đổi schema hay hợp đồng phát hành.

Tài liệu hợp đồng API phục vụ Kế toán công nợ phát hành hóa đơn bán hàng từ các phiếu giao hàng đã hoàn thành, xuất đúng số lượng thực giao thay vì số lượng đặt, quản lý dãy số hóa đơn liên tục không nhảy cóc, và hỗ trợ chứng từ điều chỉnh cho hóa đơn bất biến.

### 1. Nguyên tắc nghiệp vụ cốt lõi

1. **Hóa đơn sinh từ số lượng thực giao (Delivered Quantity)**:
   - Số lượng xuất trên hóa đơn (`invoicedQuantity`) được lấy từ `deliveredQuantity` của từng dòng hàng trên phiếu giao đã hoàn tất (`status = DELIVERED`), không lấy theo `orderedQuantity`.
   - Các dòng hàng có `deliveredQuantity == 0` hoặc chưa giao sẽ không được tính tiền vào hóa đơn.
   - Thành tiền = `invoicedQuantity * unitPrice - discountAmount`.
2. **Số hóa đơn tự sinh liên tục, không trùng và không nhảy cóc**:
   - Sử dụng cơ chế khóa bi quan (`PESSIMISTIC_WRITE`) trên bảng `invoice_sequences` trong cùng transaction phát hành hóa đơn.
   - Định dạng số 8 chữ số Ả Rập chuẩn: `00000001`, `00000002`... đi kèm mã hóa đơn `HD-00000001`.
   - Nếu transaction gặp lỗi hoặc rollback, bộ đếm tự động rollback theo, đảm bảo tuyệt đối không có khoảng trống (không nhảy cóc) và không trùng số.
3. **Gộp nhiều phiếu giao của cùng đại lý trong kỳ**:
   - Hỗ trợ gộp 1 hoặc nhiều phiếu giao (`orderIds`) của cùng một đại lý (`dealerId`) thành một hóa đơn duy nhất.
   - Ràng buộc kiểm tra nghiêm ngặt: Tất cả phiếu giao phải thuộc cùng đại lý và ở trạng thái `DELIVERED`.
   - Chặn xuất hóa đơn lặp: Mỗi phiếu giao chỉ được xuất hóa đơn 1 lần duy nhất trong toàn hệ thống (ràng buộc unique `order_id` trên bảng `invoice_orders`).
4. **Hóa đơn đã phát hành không sửa và không xóa**:
   - Hóa đơn ở trạng thái `ISSUED` là bất biến. Mọi thao tác `PUT` hoặc `DELETE` trực tiếp vào hóa đơn đều bị chặn với mã lỗi `400 Bad Request`.
   - Khi có sai sót, chỉ được lập **Chứng từ điều chỉnh** (`InvoiceAdjustment`):
     - `INCREASE`: Điều chỉnh tăng tiền / số lượng.
     - `DECREASE`: Điều chỉnh giảm tiền / số lượng / chiết khấu.
     - `INFORMATION`: Điều chỉnh thông tin (tên đại lý, MST, địa chỉ...).
     - `CANCEL`: Điều chỉnh hủy hóa đơn.
   - Bắt buộc nhập lý do điều chỉnh (`adjustmentReason`). Hóa đơn sau khi điều chỉnh chuyển trạng thái `ADJUSTED` (hoặc `CANCELLED` nếu hủy).
5. **In và xuất file PDF**:
   - `GET /api/invoices/{id}/pdf`: Xuất file PDF hóa đơn chuẩn A4 theo thiết kế doanh nghiệp (OpenPDF).
   - `GET /api/invoices/{id}/print-preview`: Trả về mẫu in HTML hỗ trợ in trực tiếp từ trình duyệt (`window.print`).
   - `GET /api/invoices/{id}/adjustments/{adjustmentId}/pdf`: Xuất file PDF chứng từ điều chỉnh hóa đơn.
6. **Phân quyền ma trận (Database-backed Permission Matrix)**:
   - Các API xem/tra cứu/tải PDF (`GET`): Bắt buộc quyền `INVOICE_VIEW` hoặc `ROLE_ADMIN`.
   - Các API phát hành hóa đơn / xem trước (`POST /api/invoices`, `POST /api/invoices/preview`): Bắt buộc quyền `INVOICE_CREATE` hoặc `ROLE_ADMIN`.
   - API lập chứng từ điều chỉnh (`POST /api/invoices/{id}/adjustments`): Bắt buộc quyền `INVOICE_ADJUST` hoặc `ROLE_ADMIN`.
   - Thu hồi quyền trên phiên JWT hiện tại lập tức có hiệu lực và trả về `403 Forbidden` trên request kế tiếp (không fallback theo role).

### 2. Danh sách Endpoints (`/api/invoices`)

| Method | Endpoint | Quyền yêu cầu | Mô tả |
|---|---|---|---|
| `GET` | `/dealers-with-eligible-orders` | `INVOICE_VIEW` hoặc `ADMIN` | Danh sách đại lý có phiếu giao hàng chưa xuất hóa đơn |
| `GET` | `/eligible-orders` | `INVOICE_VIEW` hoặc `ADMIN` | Danh sách phiếu giao hàng hợp lệ sẵn sàng xuất hóa đơn của đại lý (`dealerId`, `startDate`, `endDate`) |
| `POST` | `/preview` | `INVOICE_CREATE` hoặc `ADMIN` | Xem trước thông tin hóa đơn gộp trước khi phát hành chính thức |
| `POST` | `` | `INVOICE_CREATE` hoặc `ADMIN` | Phát hành hóa đơn bán hàng chính thức từ các phiếu giao hàng |
| `GET` | `` | `INVOICE_VIEW` hoặc `ADMIN` | Tìm kiếm, lọc và phân trang danh sách hóa đơn (`dealerId`, `status`, `startDate`, `endDate`, `keyword`, `page`, `size`) |
| `GET` | `/{id}` | `INVOICE_VIEW` hoặc `ADMIN` | Xem chi tiết hóa đơn, các dòng hàng thực giao, phiếu giao liên kết và chứng từ điều chỉnh |
| `PUT` | `/{id}` | `INVOICE_CREATE` hoặc `ADMIN` | Chặn sửa hóa đơn đã phát hành (luôn trả 400 Bad Request) |
| `DELETE` | `/{id}` | `INVOICE_CREATE` hoặc `ADMIN` | Chặn xóa hóa đơn đã phát hành (luôn trả 400 Bad Request) |
| `POST` | `/{id}/adjustments` | `INVOICE_ADJUST` hoặc `ADMIN` | Lập chứng từ điều chỉnh cho hóa đơn đã phát hành (bắt buộc lý do) |
| `GET` | `/{id}/adjustments` | `INVOICE_VIEW` hoặc `ADMIN` | Danh sách chứng từ điều chỉnh của hóa đơn |
| `GET` | `/{id}/pdf` | `INVOICE_VIEW` hoặc `ADMIN` | Xuất file PDF hóa đơn bán hàng (`application/pdf`) |
| `GET` | `/{id}/print-preview` | `INVOICE_VIEW` hoặc `ADMIN` | Xem trước bản in hóa đơn dạng HTML (`text/html`) |
| `GET` | `/{id}/adjustments/{adjustmentId}/pdf` | `INVOICE_VIEW` hoặc `ADMIN` | Xuất file PDF chứng từ điều chỉnh (`application/pdf`) |

### 3. Payload mẫu

#### Phát hành hóa đơn (`POST /api/invoices`)

```json
{
  "dealerId": 1,
  "orderIds": [101, 102],
  "invoiceDate": "2026-10-22T14:30:00",
  "note": "Xuất hóa đơn đợt 1 tháng 10/2026"
}
```

Response:
```json
{
  "success": true,
  "message": "Phát hành hóa đơn thành công",
  "data": {
    "id": 1,
    "invoiceNumber": "00000001",
    "invoiceCode": "HD-00000001",
    "invoiceDate": "2026-10-22T14:30:00",
    "status": "ISSUED",
    "statusLabel": "Đã phát hành",
    "dealerId": 1,
    "dealerCode": "DL-TEST-001",
    "dealerName": "Đại lý Bia Mai Linh",
    "dealerTaxCode": "0109988776",
    "subtotalAmount": 3850000.00,
    "discountAmount": 0.00,
    "totalAmount": 3850000.00,
    "adjustedTotalAmount": 3850000.00,
    "items": [
      {
        "orderId": 101,
        "orderCode": "PG-20261022-001",
        "productCode": "SP-BIA-HN",
        "productName": "Bia Hà Nội Lon 330ml",
        "unit": "thùng",
        "orderedQuantity": 10,
        "deliveredQuantity": 7,
        "invoicedQuantity": 7,
        "unitPrice": 300000.00,
        "subtotalAmount": 2100000.00,
        "totalAmount": 2100000.00
      }
    ]
  }
}
```

#### Lập chứng từ điều chỉnh (`POST /api/invoices/{id}/adjustments`)

```json
{
  "adjustmentType": "DECREASE",
  "adjustmentReason": "Chiết khấu bổ sung hỗ trợ đại lý 50,000 do bao bì cấn móp",
  "adjustmentAmount": 50000.00,
  "note": "Đã có biên bản kiểm tra hàng lỗi với đại lý"
}
```


## S6-08 — Xác nhận giao hàng có chứng từ

- `POST /api/warehouse/delivery-trips/{id}/stops/{stopId}/confirm-delivery`
  - Bearer token; `WAREHOUSE_EXPORT` từ ma trận DB hoặc `ROLE_ADMIN`; kiểm tra kho được giao.
  - `multipart/form-data`: `receiverName` (bắt buộc, tối đa 100 ký tự), `deliveredAt` (ISO local datetime theo múi giờ ứng dụng, ví dụ `2026-10-25T14:00:00`), `proof` (file ảnh biên bản có chữ ký).
  - Thời điểm giao nằm trong khoảng xuất phát đến hiện tại. File PNG/JPEG được kiểm tra nội dung thực, không chỉ MIME/tên file; tối đa 5 MB, 20 triệu pixel.
  - 200: `ApiResponse<DeliveryTripResponse>`. Điểm giao có `receiverName`, `deliveredAt`, `confirmedBy`, `hasSignedProof=true`.
  - 400: thiếu/sai dữ liệu, chuyến không đang chạy, điểm đã giao/đã trả kho, đơn chưa xuất hoặc chi tiết hàng không khớp. 401: chưa đăng nhập. 403: thiếu quyền hoặc ngoài phạm vi kho.
  - Lưu ảnh nhị phân và thông tin xác nhận trong DB cùng giao dịch với trạng thái điểm/đơn; khóa chuyến và đơn khi cập nhật. Không ghi file public hay lưu localStorage. Không tự kiểm chứng nội dung chữ ký trong ảnh.
- `GET /api/warehouse/delivery-trips/{id}/stops/{stopId}/proof`
  - `WAREHOUSE_VIEW` hoặc `WAREHOUSE_EXPORT` từ DB hoặc `ROLE_ADMIN`, cùng phạm vi kho.
  - Trả ảnh dưới dạng attachment, MIME do backend nhận diện, `Cache-Control: no-store`, `X-Content-Type-Options: nosniff`; 404 khi điểm chưa có chứng từ. Không trả bytes ảnh trong JSON danh sách.
- `PUT .../stops/{stopId}/status` không thể chuyển `DELIVERED` nếu chưa có xác nhận ảnh. Điểm đã giao hoặc đã trả kho không được sửa trạng thái.
- Giao thất bại: `status=FAILED`, bắt buộc `failedReason` tối đa 500 ký tự; ghi nhận hàng đang trên chuyến. Khi thực nhận hàng về kho, gọi `POST .../stops/{stopId}/return-to-warehouse` với `returnReason` để ghi nhận hoàn kho một lần. Giữ quy tắc S6-07: FAILED không tự tăng tồn kho.
- Đơn liên kết chuyến phải dùng confirm-delivery, không được đi tắt qua `PUT /api/orders/{id}/delivery` hoặc chuyển trạng thái đơn sang DELIVERED. Đơn chưa thuộc chuyến giữ luồng S5-01 hiện có.
- Kết nối Sprint 7: chỉ sau xác nhận hợp lệ mới đặt đơn EXPORTED/SHIPPED thành DELIVERED, ghi ngày giao thực tế và số lượng thực giao/quy đổi cơ sở. S6-08 xác nhận giao đủ đơn, đối chiếu sản phẩm, đơn vị và số lượng với chi tiết chuyến. API hóa đơn S7-01 hiện có sẽ đọc các dữ liệu này; phát hành hóa đơn vẫn là thao tác riêng có quyền INVOICE_CREATE.
- Schema: Hibernate `ddl-auto=update` hiện có bổ sung `delivery_trip_stops.signed_proof MEDIUMBLOB`, `proof_content_type VARCHAR(30)`, `confirmed_by VARCHAR(100)` (nullable cho dữ liệu cũ). Không backfill ảnh hay tự coi các xác nhận cũ là có chứng từ.
- Frontend cần gửi FormData bằng token, thêm người nhận/thời gian/file, gọi proof có xác thực, đồng bộ quyền và ẩn/vô hiệu hóa nút khi thiếu WAREHOUSE_EXPORT. Branch này chỉ triển khai backend, chưa có UI S6-08.

## S8-08 — Xuất báo cáo tồn kho ra Excel

### Endpoint

`GET /api/warehouse/stock-ledger/export`

Yêu cầu Bearer JWT và `WAREHOUSE_VIEW` từ ma trận quyền DB, cùng quyền của sổ tồn hiện có. `ROLE_ADMIN` luôn có quyền. `REPORT_INVENTORY`, `WAREHOUSE_MANAGE` hoặc vai trò kho không thay thế quyền xem bị thiếu. Thu hồi WAREHOUSE_VIEW làm request tiếp theo với JWT cũ trả 403.

| Tham số | Kiểu | Ý nghĩa |
|---|---|---|
| `warehouseId` | Long dương, tùy chọn | Kho đang được lọc; nếu bỏ trống, xuất tất cả kho trong phạm vi tài khoản |
| `categoryId` | Long dương, tùy chọn | Nhóm hàng đang lọc |
| `keyword` | String tối đa 150 ký tự | Tìm mã/tên SKU, không phân biệt hoa thường; `%`, `_`, `!` được tìm như ký tự thông thường |
| `stockStatus` | Enum tùy chọn | `IN_STOCK`, `OUT_OF_STOCK`, `FULLY_RESERVED` |
| `belowMinimum` | Boolean, mặc định false | Chỉ lấy dòng dưới mức tối thiểu nếu true |

Ví dụ:

```text
GET /api/warehouse/stock-ledger/export?warehouseId=1&categoryId=3&stockStatus=IN_STOCK&belowMinimum=true
Authorization: Bearer <access-token>
```

Frontend phải gửi **cùng bộ lọc đã áp dụng cho bảng**, không lấy giá trị chưa được người dùng áp dụng. Backend không lưu trạng thái lọc trên trình duyệt và không đoán bộ lọc từ request trước. Không phân trang dữ liệu xuất: `page`, `size` của bảng không giới hạn file; xuất toàn bộ dòng khớp bộ lọc, cùng thứ tự mã kho/mã SKU/ID SKU.

### Response tải tệp

- 200, body là tệp **XLSX thực**, không có envelope JSON.
- Content-Type: `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`.
- Content-Disposition: attachment với filename UTF-8; CORS expose Content-Disposition để frontend đọc được tên file.
- Cache-Control: no-store.
- Một sheet `Sổ tồn kho`, dòng đầu là tiêu đề, cố định dòng tiêu đề và có AutoFilter.

Mẫu giữ nguyên 12 cột đang dùng trong `frontend/js/stock-ledger.js`, theo đúng thứ tự:

| STT | Cột | Kiểu ô |
|---|---|---|
| 1 | Mã SKU | Text |
| 2 | Sản phẩm | Text |
| 3 | Nhóm hàng | Text |
| 4 | Mã kho | Text |
| 5 | Kho | Text |
| 6 | Đơn vị | Text |
| 7 | Tồn thực tế | Number, tối đa 4 chữ số thập phân |
| 8 | Đang giữ chỗ | Number, tối đa 4 chữ số thập phân |
| 9 | Tồn khả dụng | Number, tối đa 4 chữ số thập phân |
| 10 | Trạng thái tồn | Text |
| 11 | Mức tối thiểu | Number, hoặc ô trống nếu chưa cấu hình |
| 12 | Cảnh báo tối thiểu | Text |

**Ba cột tồn được giữ nguyên riêng biệt**, cùng đơn vị cơ sở và cùng giá trị với API sổ tồn. Tồn khả dụng = max(0, tồn thực tế - đang giữ chỗ); không gộp cột, không trừ giữ chỗ hai lần và không cộng hàng đang chuyển vào. Không xuất giá vốn, giá bán hay dữ liệu tài chính. Các chuỗi bắt đầu bằng `=`, `+`, `-`, `@` được ghi là ô text, không phải công thức Excel. Mã SKU có số 0 đầu vẫn là text.

### Tên tệp

Ngày xuất dùng clock Việt Nam (`Asia/Ho_Chi_Minh`, có thể cấu hình `app.stock-ledger.export.zone`).

- Một kho: `So-ton-kho_KHO-A_2026-10-23.xlsx`.
- Nhiều kho: `So-ton-kho_KHO-A-KHO-B_2026-10-23.xlsx`.
- Nếu chuỗi mã kho quá dài, dùng mã kho đầu và số kho còn lại: `So-ton-kho_KHO-A-VA-9-KHO_2026-10-23.xlsx`. Mã kho của từng dòng vẫn có đầy đủ trong sheet.
- Không được phân công kho nào: tệp chỉ có tiêu đề, tên `So-ton-kho_KHONG-CO-KHO_2026-10-23.xlsx`.
- Ký tự điều khiển và ký tự không an toàn trong mã kho được thay bằng `_`; không cho chèn đường dẫn hoặc header.

### Bộ lọc, phạm vi và độ đầy đủ

UI và export dùng chung `StockLedgerService.prepareQuery` để kiểm tra dữ liệu, escape keyword và tính phạm vi kho theo quyền DB. Tài khoản có phân công kho chỉ xuất được các kho đó; nhân sự kho không có phân công không được tự xem tất cả. Truyền warehouseId ngoài phạm vi trả 403. ADMIN có thể xuất mọi kho. Phạm vi kho và các dòng không có bản ghi tồn được xử lý đúng như bảng sổ tồn hiện tại.

Truy vấn xuất dùng lại SELECT_ROWS, FROM_FILTERS và BELOW_MINIMUM của StockLedgerRepository; đọc Slice 1.000 dòng/lô, không COUNT mỗi lô. SXSSF chỉ giữ cửa sổ 100 dòng trong bộ nhớ, dùng tệp tạm có nén và dọn trong cả nhánh thành công/lỗi. Output XLSX được hoàn tất rồi mới trả về để không trả một phần file khi lỗi. Read-only REPEATABLE_READ giữ cùng snapshot DB qua các lô trên MySQL.

Giới hạn mặc định **100.000 dòng** (`app.stock-ledger.export.max-rows`, cấu hình từ 1 đến 1.048.575 dòng). Vượt giới hạn trả 400 yêu cầu thu hẹp bộ lọc; **không âm thầm cắt bớt dữ liệu**. Kết quả lọc rỗng vẫn là workbook hợp lệ có đủ tiêu đề.

### Lỗi

- 400: mã kho/nhóm âm hoặc 0, keyword quá dài, trạng thái/boolean/mã không đúng định dạng, hoặc vượt giới hạn dòng.
- 401: chưa xác thực.
- 403: thiếu WAREHOUSE_VIEW hoặc kho ngoài phạm vi.
- 500: lỗi hệ thống khi tạo tệp; không trả dữ liệu nội bộ hay một tệp XLSX bị cắt dở.

### Trạng thái kết nối và kiểm thử

Chỉ triển khai backend. Nút xuất frontend hiện có vẫn tạo file phía client với tên cũ `So-ton-kho.xlsx`; **chưa đổi frontend sang endpoint mới** theo yêu cầu chỉ backend. Nút hiện đã dùng WAREHOUSE_VIEW và phản ứng với sự kiện thu hồi quyền; API mới kiểm tra lại quyền ở server. Cần nối nút với endpoint mới để người dùng giao diện nhận tên tệp mã kho/ngày do backend cung cấp.

29/29 kiểm thử qua, 0 failure/error/skip: StockLedgerExcelIntegrationTest (6), StockLedgerServiceTest (5), StockLedgerControllerTest (3), PermissionRevocationTest (1), RolePermissionConfigTest (14). Đã mở lại XLSX bằng Apache POI để kiểm tra tiêu đề/kiểu ô/số liệu, đối chiếu bộ lọc với API bảng, kiểm thử ký tự wildcard và formula text, xuất đủ 1.002 dòng qua hai lô, từ chối vượt giới hạn mà không rò tệp tạm, tên tệp theo mốc ngày Việt Nam, quyền/địa bàn kho và thu hồi trên JWT cũ. DB kiểm thử H2 MySQL mode; chưa chạy MySQL thật hoặc nghiệm thu trực tiếp bằng ứng dụng Microsoft Excel.

```text
mvn -q clean "-Dtest=StockLedgerExcelIntegrationTest,StockLedgerServiceTest,StockLedgerControllerTest,PermissionRevocationTest,RolePermissionConfigTest" test
```

## S8-02 — Chứng từ điều chỉnh giảm công nợ khi trả hàng

Base path: `/api/invoices/returns`. Yêu cầu Bearer JWT; dữ liệu lưu trong database.

### Phân quyền

- `INVOICE_ADJUST`: lập phiếu trả hàng, xác nhận để sinh chứng từ giảm công nợ, hủy phiếu nháp.
- `INVOICE_VIEW`: xem danh sách, chi tiết và phiếu trả theo hóa đơn.
- Tra cứu hóa đơn nguồn cho người lập chỉ cần `INVOICE_ADJUST`, không cần quyền khách hàng, công nợ hoặc VIEW. Lookup không cấp quyền đọc toàn bộ lịch sử phiếu trả.
- `ROLE_ADMIN`: luôn có quyền API. Không có fallback cho ACCOUNTANT hoặc vai trò khác.
- Quyền được đọc qua ma trận DB hiện có, có hiệu lực ở request tiếp theo kể cả JWT cũ. Người chỉ có ADJUST không tự động được VIEW, và ngược lại. Khi thử thu hồi phải bảo đảm không có vai trò thứ hai cấp cùng quyền.

### API

| Method | Path tương đối | Quyền | Kết quả |
|---|---|---|---|
| POST | base path | INVOICE_ADJUST | 201, tạo phiếu DRAFT, chưa giảm công nợ |
| POST | `/{id}/confirm` | INVOICE_ADJUST | 200, xác nhận trả và tạo InvoiceAdjustment(DECREASE) trong cùng transaction |
| POST | `/{id}/cancel` | INVOICE_ADJUST | 200, chỉ hủy phiếu DRAFT |
| GET | base path | INVOICE_VIEW | Page, lọc `dealerId`, `invoiceId`, `status`; `page=0`, `size=20` (giới hạn 1–100) |
| GET | `/{id}` | INVOICE_VIEW | Chi tiết kèm chứng từ điều chỉnh nếu đã xác nhận |
| GET | `/by-invoice/{invoiceId}` | INVOICE_VIEW | Danh sách phiếu trả liên kết hóa đơn gốc |
| GET | `/options` | INVOICE_VIEW hoặc INVOICE_ADJUST | Danh sách mã/nhãn trạng thái phiếu trả |
| GET | `/eligible-invoices` | INVOICE_ADJUST | Page hóa đơn ISSUED/ADJUSTED, tìm `keyword` theo mã/số hóa đơn hoặc mã/tên đại lý; tối đa 100 ký tự |
| GET | `/eligible-invoices/{invoiceId}` | INVOICE_ADJUST | Dòng hóa đơn gốc, số đã trả CONFIRMED và số còn được trả |

Các API Page nhận `page >= 0`, `size` từ 1 đến 100; giá trị ngoài khoảng trả 400, không âm thầm cắt giới hạn. Lookup dòng trả `invoiceItemId`, snapshot sản phẩm/đơn vị, `invoicedQuantity`, `confirmedReturnQuantity`, `remainingQuantity`, `invoiceUnitPrice`, `originalDiscountAmount`, `originalLineTotal`, `previouslyCreditedAmount`. Chỉ các dòng liên kết đơn gốc, có lượng xuất hóa đơn dương được đưa vào nguồn; kiểm tra lại điều kiện lúc tạo/xác nhận. Hóa đơn hết số được trả có thể vẫn xuất hiện trong lookup nhưng không thể lập thêm lượng trả.

POST tạo phiếu:

```json
{
  "invoiceId": 12,
  "returnReason": "Đại lý trả lại hàng",
  "returnDate": "2026-10-23T08:00:00",
  "note": "Đã kiểm tra hàng trả",
  "items": [{"invoiceItemId": 41, "returnQuantity": 2.0000, "note": "Hàng trả của hóa đơn gốc"}]
}
```

- `invoiceId` và `invoiceItemId`: bắt buộc, dương. Dòng hàng phải thuộc hóa đơn gốc và có liên kết đơn hàng gốc.
- `returnReason`: bắt buộc, không chỉ khoảng trắng, tối đa 500 ký tự.
- `returnDate`: tùy chọn (mặc định thời điểm lập), không ở tương lai. `note`: tối đa 500 ký tự.
- `items`: 1–500 dòng; không nhận phần tử null hoặc dòng hóa đơn trùng lặp. Note của dòng tối đa 255 ký tự.
- `returnQuantity`: > 0, tối đa 11 chữ số nguyên và 4 chữ số thập phân. Tổng trả đã CONFIRMED cộng số lượng lần này không được vượt lượng xuất hóa đơn. Kiểm tra lại lúc confirm vì nhiều phiếu DRAFT có thể cùng tồn tại.
- Không nhận đơn giá/thành tiền hay trạng thái từ client. Không cho trả trên hóa đơn CANCELLED.

POST hủy phiếu nháp:

```json
{"cancelReason":"Nhập nhầm số lượng"}
```

Lý do hủy bắt buộc, tối đa 500 ký tự. Không có API sửa/xóa phiếu hoặc hủy khoản trả đã xác nhận.

### Trạng thái, liên kết và tiền giảm trừ

Response dùng envelope `ApiResponse` hiện có. Phiếu trả gồm `id`, `returnCode`, `invoiceId`, `invoiceCode`, `invoiceNumber`, thông tin đại lý, `status`/`statusLabel`, `returnReason`, `returnDate`, `totalReturnAmount`, người/thời điểm lập và xác nhận, `cancelReason`, `note`, `items`, `invoiceAdjustment`.

Response xác nhận thành công còn có `currentDebtAfter`, `remainingLimitAfter`: số dư được server tính trong chính giao dịch đó. Hai trường này không lưu như số dư lịch sử và không có trong các lần GET sau; không suy diễn thành số dư hiện tại khi xem phiếu cũ.

Mỗi dòng trả có `invoiceItemId` để truy vết chính xác dòng hóa đơn gốc, snapshot sản phẩm/đơn vị, `invoiceUnitPrice`, `invoiceDiscountPerUnit`, `returnQuantity`, `returnAmount`. Khi xác nhận:

1. Khóa hóa đơn và phiếu trả trong DB, kiểm tra còn DRAFT và còn số lượng được trả.
2. Tính tiền từ **thành tiền sau chiết khấu của dòng hóa đơn gốc**, theo tỷ lệ số lượng trả. Không đọc giá hiện hành của sản phẩm hoặc bảng giá.
3. Dùng chênh lệch tổng làm tròn lũy kế (2 chữ số thập phân): `round(originalLineTotal * cumulativeReturnedQty / invoicedQty, 2) - previouslyCreditedAmount`. Ví dụ dòng 3 đơn vị trị giá 100.00, trả 3 lần từng đơn vị: 33.33, 33.34, 33.33, tổng chính xác 100.00.
4. Ghi `InvoiceAdjustment` loại `DECREASE`, số tiền âm, liên kết hóa đơn gốc. Phiếu trả liên kết duy nhất tới chứng từ đã sinh. Mã phiếu/chứng từ dùng UUID, không trùng do đếm sequence đồng thời.
5. Chuyển phiếu thành CONFIRMED, lưu người/thời điểm; ghi audit RETURN_ORDER và INVOICE_ADJUSTMENT. Chỉ khoản CONFIRMED tác động công nợ.
6. Tính lại cảnh báo công nợ trong cùng transaction. Nếu bước này lỗi, rollback cả phiếu xác nhận và chứng từ, không nuốt lỗi.

**Hóa đơn gốc giữ nguyên toàn bộ cột**, gồm trạng thái, tổng tiền, adjustedTotalAmount, thông tin khách hàng và các dòng hàng. Chứng từ là bản ghi mới; có thể tra cứu qua response phiếu trả hoặc API hiện có `GET /api/invoices/{id}/adjustments`. Không dùng việc sửa `Invoice.adjustedTotalAmount` để tính giảm nợ S8-02.

### Công nợ và hạn mức

Với từng hóa đơn: `netInvoice = max(0, adjustedTotalAmount (hoặc totalAmount nếu null) - khoản trả CONFIRMED của chính hóa đơn)`.

`currentDebt = max(0, tổng netInvoice + tổng nợ đơn chưa xuất hóa đơn sau giảm trừ đúng đơn - tổng thanh toán)`.

`remainingLimit = debtLimit - currentDebt`.

Khoản trả gắn đúng hóa đơn và đơn nguồn qua `ReturnOrderItem.invoiceItem.order`. Giảm số dư đúng hóa đơn/đơn trước, sau đó phân bổ quỹ thanh toán theo FIFO hiện có; không trừ tiền trả lần hai ở tổng công nợ. Tổng nợ, nợ quá hạn, còn nợ từng hóa đơn và hồ sơ đại lý cùng dùng số dư đã giảm trừ. Quỹ thanh toán vẫn là quỹ chung của đại lý theo thiết kế cũ, không được đánh dấu là đã phân bổ cố định cho một hóa đơn. Không tạo phiếu thu giả, không thay đổi tiền đã thanh toán. Số dư vượt nợ hiện tại không tự tạo phiếu hoàn tiền hoặc số dư nợ âm.

S8-02 là luồng tài chính trả hàng; không tạo phiếu nhập kho hoặc cập nhật tồn kho vật lý.

### Mã lỗi

- 400: dữ liệu không hợp lệ, dòng không thuộc hóa đơn, trả vượt, hóa đơn đã hủy, trạng thái lọc sai.
- 401: chưa xác thực; 403: không có quyền theo ma trận.
- 404: không có hóa đơn hoặc phiếu trả.
- 409: xác nhận/hủy phiếu không còn DRAFT. Xác nhận lại không sinh chứng từ thứ hai.

### Lưu trữ, frontend và kiểm thử

Hibernate `ddl-auto=update` hiện có tạo `return_orders`, `return_order_items`, khóa ngoại tới invoice/item/dealer/adjustment, version và unique adjustment link. Branch này chưa chạy migration hay kiểm thử trên MySQL thật; bộ tích hợp dùng H2 MySQL mode. Nếu môi trường đã có dữ liệu từ bản S8-02 viết dở, cần đối chiếu/backfill liên kết invoice_item_id trước khi áp ràng buộc NOT NULL.

Frontend đã nối ở **Tài chính → Điều chỉnh công nợ**, `/finance/debt-adjustment`: tra cứu nguồn, lưu nháp, xem chi tiết, xác nhận và hủy nháp. Không dùng API điều chỉnh hóa đơn thủ công để giả lập phiếu trả; không gọi nhập kho vật lý. Kiểm thử Chrome dùng HTTP fixtures; kiểm thử backend dùng H2 cách ly, chưa nghiệm thu ghi chứng từ trên MySQL thật.

Lệnh kiểm thử liên quan:

```text
mvn -q "-Dtest=ReturnOrderIntegrationTest,InvoiceServiceTest,InvoiceIntegrationTest,InvoiceControllerTest,InvoiceSecurityTest,DealerDebtServiceTest,DealerServiceTest,DebtRiskIntegrationTest,DebtRiskSecurityTest,PermissionRevocationTest,RolePermissionConfigTest" test
```

`ReturnOrderIntegrationTest` gồm 13 ca: giữ 9 ca giá lịch sử/hóa đơn bất biến, làm tròn, trả vượt, validation/hủy, quyền cùng JWT/ADMIN, rollback, đồng thời, thanh toán và đúng đơn nguồn; bổ sung lookup ADJUST-only, đối chiếu số dư giữa API công nợ/hóa đơn/hồ sơ đại lý, điều chỉnh thủ công kết hợp trả hàng không trừ kép, phân trang và dòng gốc thiếu dữ liệu trả 400.

Kết quả và phạm vi kiểm tra cập nhật tại `status.md`; không coi báo cáo kiểm thử cũ trước đồng bộ là nghiệm thu bản tích hợp mới.

## S8-05 — Dashboard điều hành bán hàng và kho

### API và quyền

| Method | URL | Tham số | Kết quả |
|---|---|---|---|
| GET | `/api/reports/dashboard` | `overdueLimit=20`, từ 1 đến 100 | Toàn bộ thẻ KPI, biểu đồ ngày/khu vực và danh sách đầu các đại lý quá hạn |
| GET | `/api/reports/dashboard/overdue-dealers` | `page=0` không âm, `size=20` từ 1 đến 100 | Page đại lý quá hạn, cho phép tra cứu toàn bộ danh sách |

Yêu cầu Bearer JWT và quyền **DASHBOARD_VIEW** trong ma trận DB; ROLE_ADMIN luôn được phép. Đây là quyền xem số liệu **toàn công ty**, gồm cả công nợ và kho, không phải quyền xem dữ liệu cá nhân. REPORT_SALES/REPORT_INVENTORY/REPORT_FINANCE hoặc tên vai trò riêng lẻ không thay thế quyền này. Hai endpoint cùng kiểm tra quyền.

Đối với DB mới, quyền được khởi tạo cho SALES_MANAGER và ADMIN. Với ma trận DB đã cấu hình, giữ nguyên các lựa chọn hiện có: ADMIN phải cấp DASHBOARD_VIEW từ màn hình quản trị quyền. Không tự cấp lại quyền đã bị thu hồi. Quyền được nạp theo request từ cơ chế JWT/ma trận hiện có; thu hồi có hiệu lực ngay với token cũ. Khi kiểm tra nhiều vai trò, phải chắc chắn không có vai trò khác đang cấp quyền.

### Response tổng quan

Dùng envelope `ApiResponse` hiện có, `data` gồm:

| Trường | Nội dung |
|---|---|
| `date`, `month` | Ngày và tháng hiện tại của dashboard, dạng YYYY-MM-DD và YYYY-MM |
| `generatedAt`, `timezone` | Thời điểm tạo response có offset; mặc định Asia/Ho_Chi_Minh |
| `metrics.todaySales` | Doanh số đơn ghi nhận trong ngày |
| `metrics.monthSales` | Doanh số từ đầu tháng đến hết ngày hiện tại |
| `metrics.pendingApprovalOrders` | Tổng đơn PENDING_APPROVAL trong toàn bộ backlog, không giới hạn tháng |
| `metrics.pendingExportOrders` | Tổng đơn APPROVED/RECORDED/PICKING/PROCESSING chưa xuất, không giới hạn tháng |
| `metrics.overdueDebt` | Tổng tiền thực sự quá hạn còn chưa thanh toán của tất cả đại lý; không chỉ tổng của danh sách đầu |
| `metrics.belowMinimumSkus` | Số SKU khác nhau thiếu tồn tối thiểu ở ít nhất một kho hoạt động |
| `dailySales[]` | `{date,sales}`, đủ mọi ngày trong tháng, ngày trống và ngày tương lai bằng 0 |
| `territorySales[]` | `{territoryId,territoryName,sales}`, doanh số tháng theo khu vực, giảm dần theo tiền |
| `overdueDealers[]` | Danh sách đầu theo `overdueLimit`, xếp giảm dần theo overdueAmount, trùng số tiền thì dealerId tăng dần |
| `totalOverdueDealers` | Tổng số đại lý còn tiền quá hạn để frontend biết có thể xem thêm |

Mỗi đại lý quá hạn gồm `dealerId`, `dealerCode`, `dealerName`, `territoryId`, `territoryName`, `currentDebt`, `overdueAmount`, `maxOverdueDays`. `currentDebt` là toàn bộ tiền chưa trả, có thể lớn hơn phần đã quá hạn. API phân trang trả cùng kiểu dòng trong Spring Page (`content`, `totalElements`, `totalPages`, ...). Trang vượt cuối vẫn trả đúng totalElements.

### Quy tắc số liệu

- Doanh số dùng `Order.totalAmount` sau chiết khấu, ngày ghi nhận `COALESCE(recordedAt,createdAt)`, và cùng tập trạng thái ghi nợ hiện có: APPROVED, RECORDED, PICKING, PROCESSING, EXPORTED, SHIPPED, DELIVERED, CLOSED, COMPLETED. Không cộng DRAFT, PENDING_APPROVAL, CANCELLED, REJECTED, EXPIRED. Đây là doanh số đơn đã ghi nhận, không phải tiền thu hoặc chỉ giá trị hóa đơn.
- Khoảng ngày dùng cận đầu bao gồm, cận cuối không bao gồm; tháng hiện tại tính đến trước 00:00 ngày mai. Đơn tương lai không vào doanh số. Biểu đồ khu vực dùng địa bàn hiện tại của đại lý; đơn chưa gắn khu vực được gom vào `territoryId=null`, tên `Chưa phân khu vực`, để không mất doanh số. Không join dòng đơn nên không nhân đôi tổng tiền.
- Khoản thanh toán lấy từ sổ `debt_payments` hiện có trên branch. SQL dùng running SUM theo đại lý, thứ tự ngày ghi nhận và ID, phân bổ tiền đã trả theo FIFO trước khi tính phần quá hạn. Đến đúng ngày hạn chưa tính quá hạn. maxDebtDays=0 nghĩa là không áp thời hạn nợ. Không lấy số tiền hoặc trạng thái từ cờ debtFlagged đã lưu; dữ liệu mới được tính trực tiếp.
- Bao gồm cả đại lý ngừng hoạt động nếu vẫn còn nợ quá hạn. Đại lý đã trả đủ hoặc trả dư không còn trong danh sách. S8-05 không tự tạo bút toán hay sửa sổ công nợ. Các tính năng tài chính chưa được merge vào branch này không được tự nhập từ branch khác.
- Tồn dưới tối thiểu dùng đúng biểu thức của StockLedgerRepository: tồn khả dụng = max(0, tồn thực - giữ chỗ), chỉ so khi có cấu hình minimum và đơn vị khớp. Kho/SKU phải đang hoạt động. SKU thiếu ở nhiều kho chỉ đếm một lần. Chưa có dòng tồn được xem là 0; chưa cấu hình mức tối thiểu không xem là thiếu; bằng mức tối thiểu không xem là thiếu. Không cộng hàng đang chuyển vào tồn khả dụng.
- Clock riêng cho dashboard, mặc định Việt Nam, có thể cấu hình `app.dashboard.zone`; kiểm thử cố định clock để kiểm tra ranh giới ngày/tháng và năm nhuận.

### Hiệu năng và tính nhất quán

Một request tổng quan thực hiện **5 truy vấn tổng hợp DB** cho dữ liệu nghiệp vụ (ngoài truy vấn xác thực/phân quyền): theo ngày, khu vực, backlog, SKU thiếu và công nợ. Không gọi service công nợ từng đại lý, không tải entity graph hay toàn bộ đơn vào Java. FIFO chạy bằng window functions và derived tables; SQL dành cho MySQL 8+.

Response được dựng trong read-only transaction REPEATABLE_READ để các phần dùng cùng snapshot trên MySQL. Không cache response nên dữ liệu được đọc lại ở mỗi request, không cache quyền.

Bài đo tích hợp qua MockMvc HTTP/JWT/DB với **10.000 đơn, 1.000 đại lý, 1.000 SKU có mức tối thiểu**: 3 request đầy đủ, gồm request đầu sau khi nạp dữ liệu, thời gian lớn nhất ở lần chạy hồi quy **210 ms**, đều dưới 2.000 ms. Bài đo xác minh cả tiền quá hạn > 0 và các KPI để tránh đo một response rỗng. Đây là bằng chứng trên H2 MySQL mode tại máy kiểm thử; chưa đo MySQL thật, mạng hoặc thời gian render frontend.

### Lỗi và frontend

401 nếu chưa xác thực; 403 nếu thiếu hoặc bị thu hồi DASHBOARD_VIEW; 400 khi page/size/overdueLimit ngoài giới hạn hoặc không phải số nguyên. Không cung cấp endpoint ghi dữ liệu.

Frontend đã nối (24/10/2026): **Báo cáo → Dashboard điều hành**, `/reports/dashboard`, lối tắt ở Tổng quan → Công việc. Trang riêng dùng DASHBOARD_VIEW/ADMIN ở menu, guard và API; không khóa trang Tổng quan chung hoặc màn Hạn mức công nợ bằng quyền dashboard. Một request tổng quan gồm sáu KPI/hai biểu đồ/trang đầu đại lý; endpoint phân trang chỉ gọi khi đổi trang/cỡ trang. Thu hồi quyền xóa dữ liệu, bỏ phản hồi trễ và chuyển trang lỗi 403 trên phiên JWT hiện tại. Không đổi hợp đồng hoặc code backend.

Chrome trang/script thật với HTTP fixtures riêng đo mở trang đến hiển thị **205 ms**; bài API chậm trên 2 giây hiển thị cảnh báo. Hồi quy backend 24/10 với 10.000 đơn/1.000 đại lý/1.000 SKU: tối đa **355 ms**. Hai phép đo độc lập, chưa chứng minh SLA end-to-end dưới 2 giây trên MySQL/mạng thật. Xem `docs/FRONTEND_ROUTES.md` và `status.md` để biết đầy đủ phạm vi kiểm thử.

Kiểm thử: DashboardIntegrationTest (8 ca), RolePermissionConfigTest, PermissionRevocationTest, DebtRiskSecurityTest, DealerDebtServiceTest, DebtRiskIntegrationTest; tổng **53 tests passed**, 0 failure/error/skip ở kết quả cuối từng bộ. Assertion audit tiền cũ được sửa để không phụ thuộc dấu phân cách locale; không thay đổi nghiệp vụ thanh toán.

```text
mvn -q "-Dtest=DashboardIntegrationTest,RolePermissionConfigTest,PermissionRevocationTest,DebtRiskSecurityTest,DealerDebtServiceTest,DebtRiskIntegrationTest" test
```

## S7-05 — Ghi nhận khoản thu theo tuyến và đối chiếu tiền nộp

Base URL: `/api/finance/cash-collections`. Tất cả API yêu cầu Bearer access token.

### Quyền và phạm vi dữ liệu

| Quyền từ ma trận DB | Thao tác |
|---|---|
| `COLLECTION_CREATE` | Ghi thu tại cửa hàng do chính nhân viên đang đăng nhập phụ trách; xem khoản thu/biên lai và đối chiếu của chính mình |
| `COLLECTION_CONFIRM` | Xem khoản thu/biên lai toàn bộ nhân viên; xác nhận đã nhận đủ tiền |
| `COLLECTION_RECONCILE` | Xem khoản thu/biên lai và báo cáo toàn bộ nhân viên |
| `ROLE_ADMIN` | Toàn quyền API; quy tắc không tự xác nhận khoản thu của chính mình vẫn áp dụng |

Mỗi request nạp quyền qua cơ chế JWT/ma trận DB hiện có. Không fallback theo vai trò SALES/ACCOUNTANT. Thu hồi quyền có hiệu lực ở request kế tiếp với cùng JWT. Người có nhiều vai trò nhận hợp quyền: để kiểm tra thu hồi phải bỏ quyền từ tất cả vai trò liên quan.

DB mới khởi tạo quyền: SALES được CREATE, ACCOUNTANT được CONFIRM + RECONCILE, SALES_MANAGER được RECONCILE. **DB đã có cấu hình giữ nguyên ma trận**: ADMIN cần cấp các quyền mới ở màn hình quản trị quyền; không tự thêm lại quyền đã bị thu hồi. Danh mục quyền trong màn hình hiện tại được tải động từ backend.

### Endpoints

| Method | Path | Input | Output |
|---|---|---|---|
| POST | `/` (không cần dấu `/` cuối) | multipart: `data` là JSON, `receipt` là file | 201, khoản thu ở trạng thái `PENDING` |
| GET | `/` | `from`, `to` bắt buộc YYYY-MM-DD; `employeeId` tùy chọn; `page=0`, `size=20` | Page khoản thu mới nhất trước; size giới hạn 1–100 |
| GET | `/{id}` | ID khoản thu | Chi tiết khoản thu |
| GET | `/{id}/receipt` | ID khoản thu | Bytes JPEG/PNG, `Cache-Control: no-store`, yêu cầu quyền và kiểm tra chủ sở hữu |
| POST | `/{id}/confirm` | Không có body | Xác nhận kế toán đã nhận đủ tiền, chuyển `PENDING` → `RECEIVED` |
| GET | `/reconciliation` | `from`, `to`, `employeeId` tùy chọn | Mảng tổng hợp theo nhân viên và ngày thu |
| GET | `/dealers` | `keyword` tùy chọn (tối đa 150 ký tự), `page=0`, `size=20` (1–100) | Page cửa hàng đang hoạt động có thể ghi thu; chỉ `COLLECTION_CREATE` hoặc ADMIN |

Picker `/dealers` chỉ trả `id`, `code`, `name`, `routeName`, sắp xếp tên rồi ID. Nhân viên chỉ thấy cửa hàng được giao cho chính mình, kể cả khi có thêm quyền CONFIRM/RECONCILE; ADMIN xem mọi cửa hàng đang hoạt động. Không cần quyền CUSTOMER_VIEW/ORDER_VIEW và không nhận tham số đổi nhân viên để vượt phạm vi. Ngừng giao dịch hoặc bàn giao cửa hàng sau khi tải picker vẫn được kiểm tra lại khi POST ghi thu.

Ví dụ phần `data` (Content-Type `application/json`):

```json
{"dealerId":12,"amount":250000.00,"collectionDate":"2026-10-23","note":"Thu tiền tại cửa hàng"}
```

- `dealerId`: ID dương, cửa hàng đang hoạt động và do người ghi thu phụ trách; ADMIN được ghi ngoài phạm vi phụ trách.
- `amount`: bắt buộc > 0, tối đa 17 chữ số phần nguyên, 2 chữ số thập phân; dùng BigDecimal.
- `collectionDate`: bắt buộc, không ở tương lai. Service kiểm tra theo Asia/Ho_Chi_Minh.
- `note`: tùy chọn, tối đa 500 ký tự.
- `receipt`: bắt buộc, tối đa 5 MiB; nội dung thực phải giải mã được bằng JPEG/PNG, tối đa 20 megapixel. Không tin tên file hoặc MIME do client khai báo.
- Nhân viên ghi thu lấy từ danh tính JWT, không nhận employeeId/status/receivedBy từ client khi tạo.
- `routeName`: lưu snapshot tên địa bàn (`Dealer.territory`) của cửa hàng tại thời điểm thu. Dự án chưa có entity tuyến riêng. Phân công cửa hàng thay đổi không đổi lịch sử khoản thu.
- Biên lai và dữ liệu khoản thu lưu trong DB; Hibernate tạo bảng `cash_collections` theo cơ chế `ddl-auto=update` hiện tại.

Các response JSON dùng envelope `ApiResponse` hiện có. Chi tiết gồm `id`, `dealerId`, `dealerName`, `employeeId`, `employeeName`, `routeName`, `amount`, `collectionDate`, `note`, `status`, `createdAt`, `receivedAt`, `receivedBy`, `receiptUrl`. Ảnh chỉ trả qua endpoint riêng đã xác thực.

Ví dụ `data` đối chiếu:

```json
[{"employeeId":8,"employeeName":"Nguyễn Văn A","date":"2026-10-23","collected":350000.00,"received":250000.00,"pending":100000.00,"count":2}]
```

`received` là tiền thuộc các khoản thu đã được kế toán xác nhận, được nhóm theo **ngày thu gốc**, không phải ngày kế toán nhận tiền. `pending = collected - received`. Khoảng ngày tối đa chênh lệch 366 ngày, from <= to. Không truyền employeeId: nhân viên chỉ thấy mình, người có quyền đối chiếu thấy tất cả. Ngày không có khoản thu không có dòng tổng hợp. Tổng được tính bằng truy vấn GROUP BY trong DB.

### Kiểm soát ghi nhận và mã lỗi

- Khoản thu không có API sửa/xóa. CREATE và CONFIRM_RECEIVED ghi audit trong transaction, lưu người và thời điểm thao tác.
- Xác nhận nhận đủ toàn bộ khoản thu một lần. Không tự xác nhận khoản thu của chính mình. Khóa dòng DB ngăn hai request xác nhận đồng thời; chỉ một request thành công.
- 400: dữ liệu/ảnh không hợp lệ, thiếu multipart part, thiếu hoặc sai tham số lọc.
- 401: chưa xác thực. 403: thiếu quyền, cửa hàng ngoài phạm vi, xem dữ liệu người khác hoặc tự xác nhận.
- 404: không tìm thấy cửa hàng/khoản thu. 409: khoản thu đã được xác nhận.
- Request vượt giới hạn multipart chung dùng xử lý lỗi upload hiện tại (giới hạn toàn ứng dụng 20 MB/file, 21 MB/request); S7-05 kiểm tra thêm ngưỡng ảnh 5 MiB.

Phạm vi S7-05 là đối chiếu khoản thu với tiền nhân viên nộp. Không tự tạo DebtPayment hoặc đối trừ hóa đơn/công nợ S7-02; không hỗ trợ nộp một phần. Frontend tại **Tài chính → Thu tiền theo tuyến**, `/cash-collections`: ghi thu multipart kèm ảnh, xem chi tiết/biên lai có xác thực, xác nhận nhận đủ và đối chiếu nhân viên/ngày. `COLLECTION_CONFIRM` đơn lẻ không truy cập `/reconciliation`; chỉ CREATE hoặc RECONCILE được xem bảng này. Gửi tiền dưới dạng chuỗi thập phân để không mất chữ số khi qua JavaScript; backend nhận BigDecimal và vẫn kiểm tra giới hạn như trên.

Kiểm thử: `mvn -q "-Dtest=CashCollectionIntegrationTest,RolePermissionConfigTest,PermissionRevocationTest,FinanceControllerTest,DebtRiskSecurityTest" test` — 43 tests passed (9 test tích hợp S7-05, gồm picker độc lập quyền và phạm vi cửa hàng); DB kiểm thử H2 MySQL mode, chưa chạy MySQL thực tế.

---

## S7-07 — Đại lý tự xem công nợ, hạn mức còn lại và tải lại hóa đơn (23/10/2026)

### Cập nhật backend và kết nối frontend — 25/10/2026

- Màn hình `Kinh doanh → Công nợ của tôi` (`/dealer-invoices`) dùng `/portal/debt/summary` với `FINANCE_VIEW_DEBT` và `/portal/invoices/**` với `INVOICE_VIEW`, độc lập nhau; Admin luôn được phép. API lấy đại lý từ liên kết tài khoản DB; tài khoản nội bộ không có vai trò đại lý không được dùng portal. Admin chọn `dealerId` để hỗ trợ, đại lý không thể đổi sang hồ sơ khác.
- Khi thiếu `INVOICE_VIEW`, cả `/portal/debt` và `/portal/debt/summary` trả `invoices: []`. `/portal/profile` không trả giá trị công nợ/hạn mức nếu thiếu `FINANCE_VIEW_DEBT` (các trường tiền là `null`). Cả endpoint cũ `/portal/debt` và endpoint summary đều yêu cầu `FINANCE_VIEW_DEBT`; `ORDER_VIEW` đơn lẻ không đọc được tổng nợ. Cổng tự đặt hàng S4-10 bỏ gọi API tổng nợ khi thiếu quyền, hiển thị thiếu quyền trên thẻ công nợ; giỏ hàng vẫn được kiểm tra bằng API preview đơn hàng hiện có. Trang S7-07 không tự chuyển sang endpoint khác khi bị từ chối quyền.
- Phân trang danh sách: `page >= 0`, `1 <= size <= 100`; trang rất lớn trả nội dung rỗng, không tràn số. Khoảng ngày đảo ngược hoặc `paymentStatus` không hợp lệ trả `400`; trạng thái hợp lệ: `UNPAID`, `PARTIALLY_PAID`, `PAID`, `OVERDUE`, `CANCELLED` (không phân biệt hoa/thường).
- `InvoiceResponse.effectiveAmount` là giá trị phải thu sau điều chỉnh và các khoản trả hàng **đã xác nhận**. Trả hàng nháp không giảm nợ. Giữ nguyên `totalAmount` và `adjustedTotalAmount` trên hóa đơn gốc; `paidAmount`, `remainingAmount`, `paymentStatus`, `isPaid` cùng dựa vào phân bổ FIFO hiện có. Hóa đơn hủy vẫn được xem/tải như chứng từ lịch sử, có trạng thái `CANCELLED`, số còn nợ bằng `0`, `isPaid = false`.
- Bổ sung `exactAmounts` song song với các trường số cũ trên `DealerDebtResponse`, `InvoiceResponse`, `InvoiceItemResponse`, `InvoiceAdjustmentResponse`. Giá trị là chuỗi thập phân từ `BigDecimal.toPlainString()` (hoặc `null`), ví dụ `"remainingAmount": "3499999.75"`; frontend ưu tiên chuỗi này để giữ phần lẻ và số lớn. Trường số cũ được giữ để tương thích client khác. Dòng hóa đơn dùng `invoicedQuantity`, `totalAmount`; điều chỉnh dùng `adjustmentTypeLabel`, `adjustmentReason`.
- Frontend hiển thị `availableOrderLimit` do server tính, không tự chặn khi chính sách quá hạn vẫn cho lấy hàng. Tổng nợ không âm; tiền nộp thừa không làm hạn mức khả dụng vượt hạn mức cấp. Bảng/xuất Excel ghi rõ chỉ tổng/xuất trang hiện tại; giá trị phải thu hiện tại tách khỏi tổng hóa đơn gốc trong chi tiết.
- PDF và HTML bản in trả `Cache-Control: no-store`. PDF giữ phần lẻ tiền, ưu tiên font tiếng Việt đóng gói; định dạng số an toàn khi nhiều yêu cầu đồng thời. HTML escape dữ liệu tên/địa chỉ/sản phẩm/ghi chú; iframe xem trước không cho chạy script. GET chỉ đọc và dựng tài liệu, không phát hành lại hoặc sửa chứng từ.
- Thu hồi quyền trên JWT hiện tại có hiệu lực ở request kế tiếp; frontend hủy yêu cầu và xóa dữ liệu/modal/bản in cũ, bỏ kết quả trả muộn. Kiểm thử bằng H2 cách ly và Chrome với HTTP fixture; không ghi dữ liệu vào DB nghiệp vụ thật.

Tài liệu hợp đồng API cho phép Đại lý (`ROLE_DEALER`) tự tra cứu công nợ của chính mình qua Cổng Đại lý (`/api/portal/**`), để biết còn nợ bao nhiêu, còn được lấy hàng bao nhiêu, xem danh sách hóa đơn kèm số tiền, hạn thanh toán, trạng thái, và xem/tải lại hóa đơn đã phát hành.

### 1. Nguyên tắc nghiệp vụ cốt lõi

1. **Hiển thị tổng còn nợ và hạn mức còn lại**:
   - `currentDebt`: Tổng công nợ còn lại hiện tại của đại lý (tính từ tổng giá trị các hóa đơn hiệu lực `adjustedTotalAmount` cộng các đơn hàng ghi sổ chưa xuất hóa đơn `uninvoicedOrderDebt`, trừ đi tổng số tiền đã thu `totalPaid` theo nguyên tắc không tính trùng lặp).
   - `debtLimit`: Hạn mức công nợ tối đa được cấp cho đại lý.
   - `remainingLimit`: Phần hạn mức còn lại (`debtLimit - currentDebt`).
   - `availableOrderLimit`: Hạn mức còn được lấy hàng thực tế (`0` nếu đại lý đang bị khoá giao dịch hoặc bị chặn do quá hạn công nợ `isBlocked = true`, ngược lại bằng `max(0, remainingLimit)`).
2. **Danh sách hóa đơn kèm số tiền, hạn thanh toán, trạng thái**:
   - Mỗi hóa đơn (`InvoiceResponse`) trả về:
     - `totalAmount` (tổng tiền hóa đơn gốc), `adjustedTotalAmount` (tổng tiền sau điều chỉnh), `paidAmount` (số tiền đã thanh toán phân bổ FIFO), `remainingAmount` (số tiền còn nợ của hóa đơn).
     - `dueDate`: Hạn thanh toán của hóa đơn (mặc định = `invoiceDate + dealer.maxDebtDays`).
     - `status` & `statusLabel`: Trạng thái phát hành hóa đơn (`ISSUED` - Đã phát hành, `ADJUSTED` - Đã điều chỉnh, `CANCELLED` - Đã hủy).
     - `paymentStatus` & `paymentStatusLabel`: Trạng thái thanh toán công nợ của hóa đơn theo phân bổ FIFO:
       - `UNPAID` ("Chưa thanh toán")
       - `PARTIALLY_PAID` ("Thanh toán một phần")
       - `PAID` ("Đã thanh toán")
       - `OVERDUE` ("Quá hạn thanh toán")
       - `CANCELLED` ("Đã hủy")
     - `isOverdue`, `overdueDays`, `daysUntilDue`: Cờ quá hạn, số ngày quá hạn hoặc số ngày còn lại đến hạn thanh toán.
3. **Xem và tải lại hóa đơn đã phát hành**:
   - Đại lý xem chi tiết hóa đơn đã phát hành (`GET /api/portal/invoices/{id}`), xem bản in HTML (`GET /api/portal/invoices/{id}/print-preview`), tải lại file PDF hóa đơn (`GET /api/portal/invoices/{id}/pdf`) và tải lại file PDF chứng từ điều chỉnh (`GET /api/portal/invoices/{id}/adjustments/{adjustmentId}/pdf`).
4. **Cách ly dữ liệu cấp dòng (Row-Level Security) & Ma trận phân quyền DB**:
   - Đại lý chỉ được xem công nợ, lịch sử thanh toán và xem/tải hóa đơn của **chính đại lý mình**. Mọi nỗ lực truyền `dealerId` của đại lý khác hoặc truy cập `invoiceId` của đại lý khác đều bị chặn với `403 Forbidden`.
   - Quyền truy cập được kiểm soát chặt chẽ từ ma trận phân quyền DB (`RolePermissionConfig`):
     - `FINANCE_VIEW_DEBT`: Xem tổng quan công nợ và lịch sử thanh toán (`GET /api/portal/debt/summary`, `GET /api/portal/debt/payments`, `GET /api/portal/debt`).
     - `INVOICE_VIEW`: Xem danh sách, chi tiết, bản in HTML và tải lại PDF hóa đơn (`GET /api/portal/invoices/**`, `GET /api/portal/debt/invoices/**`).
     - Thu hồi quyền trên ma trận lập tức trả về `403 Forbidden` trên phiên JWT hiện tại mà không có bất kỳ role-based fallback nào. `ROLE_ADMIN` luôn có toàn quyền.

### 2. Danh sách Endpoints (`/api/portal`)

| Method | Endpoint | Quyền yêu cầu | Mô tả |
|---|---|---|---|
| `GET` | `/api/portal/debt` | `FINANCE_VIEW_DEBT` hoặc `ADMIN` | Tra cứu công nợ/hạn mức; danh sách hóa đơn chỉ kèm khi có INVOICE_VIEW |
| `GET` | `/api/portal/debt/summary` | `FINANCE_VIEW_DEBT` hoặc `ADMIN` | Xem tổng quan công nợ, tổng còn nợ, hạn mức còn lại, hạn mức còn được lấy hàng, thống kê hóa đơn và lịch sử thanh toán |
| `GET` | `/api/portal/debt/payments` | `FINANCE_VIEW_DEBT` hoặc `ADMIN` | Xem lịch sử các đợt thanh toán công nợ của chính đại lý |
| `GET` | `/api/portal/invoices` (alias `/api/portal/debt/invoices`) | `INVOICE_VIEW` hoặc `ADMIN` | Danh sách hóa đơn phân trang kèm số tiền, hạn thanh toán, trạng thái (`status`, `paymentStatus`, `startDate`, `endDate`, `keyword`, `page`, `size`) |
| `GET` | `/api/portal/invoices/{id}` (alias `/api/portal/debt/invoices/{id}`) | `INVOICE_VIEW` hoặc `ADMIN` | Xem chi tiết hóa đơn đã phát hành của chính đại lý |
| `GET` | `/api/portal/invoices/{id}/pdf` (alias `/api/portal/debt/invoices/{id}/pdf`) | `INVOICE_VIEW` hoặc `ADMIN` | Xem hoặc tải lại file PDF hóa đơn đã phát hành của chính đại lý (`application/pdf`) |
| `GET` | `/api/portal/invoices/{id}/print-preview` (alias `/api/portal/debt/invoices/{id}/print-preview`) | `INVOICE_VIEW` hoặc `ADMIN` | Xem bản in HTML hóa đơn đã phát hành của chính đại lý (`text/html;charset=UTF-8`) |
| `GET` | `/api/portal/invoices/{id}/adjustments` | `INVOICE_VIEW` hoặc `ADMIN` | Danh sách chứng từ điều chỉnh của hóa đơn thuộc đại lý |
| `GET` | `/api/portal/invoices/{id}/adjustments/{adjustmentId}/pdf` | `INVOICE_VIEW` hoặc `ADMIN` | Tải lại file PDF chứng từ điều chỉnh của hóa đơn thuộc đại lý (`application/pdf`) |

### 3. Payload mẫu (`GET /api/portal/debt/summary`)

```json
{
  "success": true,
  "message": "Tổng quan công nợ và hạn mức của đại lý",
  "data": {
    "dealerId": 100,
    "dealerCode": "DL-001",
    "dealerName": "Đại lý Minh Phát",
    "currentDebt": 13000000.00,
    "debtLimit": 50000000.00,
    "remainingLimit": 37000000.00,
    "availableOrderLimit": 37000000.00,
    "totalInvoicedAmount": 25000000.00,
    "uninvoicedOrderDebt": 0.00,
    "totalPaid": 12000000.00,
    "totalInvoiceCount": 2,
    "unpaidInvoiceCount": 1,
    "overdueInvoiceCount": 0,
    "paidInvoiceCount": 1,
    "maxDebtDays": 30,
    "hasOverdueDebt": false,
    "overdueAmount": 0.00,
    "maxOverdueDays": 0,
    "isBlocked": false,
    "invoices": [
      {
        "id": 2,
        "invoiceNumber": "00000002",
        "invoiceCode": "HD-00000002",
        "invoiceDate": "2026-10-18T09:00:00",
        "dueDate": "2026-11-17",
        "status": "ISSUED",
        "statusLabel": "Đã phát hành",
        "totalAmount": 15000000.00,
        "adjustedTotalAmount": 15000000.00,
        "paidAmount": 2000000.00,
        "remainingAmount": 13000000.00,
        "paymentStatus": "PARTIALLY_PAID",
        "paymentStatusLabel": "Thanh toán một phần",
        "isOverdue": false,
        "overdueDays": 0,
        "daysUntilDue": 25
      }
    ],
    "payments": [
      {
        "id": 10,
        "paymentCode": "PT-20261023-0001",
        "amount": 12000000.00,
        "paymentDate": "2026-10-23",
        "paymentMethod": "CHUYEN_KHOAN",
        "referenceCode": "UNC-S707-001"
      }
    ]
  }
}
```

---

## S8-10 — Sổ chi tiết công nợ một đại lý, PDF và Excel

### Endpoints và quyền

| Method | URL | Tham số bắt buộc | Kết quả |
|---|---|---|---|
| GET | `/api/finance/debt/{dealerId}/statement` | `from`, `to` dạng YYYY-MM-DD | JSON ApiResponse chứa toàn bộ sổ trong kỳ và số dư đầu/cuối kỳ |
| GET | `/api/finance/debt/{dealerId}/statement/export` | `from`, `to`, `format=pdf` hoặc `format=xlsx` | Tệp PDF hoặc XLSX thực, không có envelope JSON |

Yêu cầu Bearer JWT và **FINANCE_VIEW_DEBT** từ ma trận DB. ADMIN luôn có quyền; FINANCE_MANAGE hoặc vai trò ACCOUNTANT đơn lẻ không thay thế quyền bị thiếu. Service và controller đều kiểm tra quyền. Thu hồi quyền có hiệu lực ở request kế tiếp với cùng token, trên JSON và cả hai định dạng xuất.

Phạm vi dữ liệu giữ quy tắc công nợ hiện tại: nhân viên SALES không có vai trò tài chính/quản lý rộng hơn chỉ xem đại lý được giao; tài khoản có ROLE_DEALER chỉ xem đại lý liên kết. Dealer-only vẫn chịu scope filter cổng đại lý hiện có. Vai trò chỉ thu hẹp phạm vi, không cấp quyền thay ma trận. ADMIN được bỏ qua hạn chế phạm vi. Không thêm quyền mới hay tự thay đổi ma trận đã lưu.

Ví dụ:

```text
GET /api/finance/debt/12/statement?from=2026-10-01&to=2026-10-31
GET /api/finance/debt/12/statement/export?from=2026-10-01&to=2026-10-31&format=pdf
GET /api/finance/debt/12/statement/export?from=2026-10-01&to=2026-10-31&format=xlsx
```

### Số liệu và thứ tự

Một sổ hợp nhất các nguồn đang có trên branch:

| sourceType | Nguồn | Ngày phát sinh | Số tiền có dấu |
|---|---|---|---|
| ORDER | Đơn đang thuộc trạng thái ghi nợ | recordedAt, fallback createdAt | totalAmount |
| PAYMENT | debt_payments | paymentDate lúc 00:00 (dữ liệu gốc chỉ lưu ngày) | -amount |
| INVOICE_ADJUSTMENT | Điều chỉnh INCREASE/DECREASE/CANCEL có tiền khác 0 | adjustmentDate | adjustmentAmount đã có dấu trong DB |

Tập trạng thái đơn: APPROVED, RECORDED, PICKING, PROCESSING, EXPORTED, SHIPPED, DELIVERED, CLOSED, COMPLETED. Không cộng đơn nháp/chờ duyệt/hủy/từ chối/hết hạn. Hóa đơn gốc **không được cộng thêm một lần nữa** vì tiền nợ đã ghi từ đơn. Điều chỉnh INFORMATION và điều chỉnh không có tiền không phải dòng tăng/giảm công nợ. Khoản thu âm nếu có là bút toán tăng trở lại theo đúng dấu số tiền.

- Số dư đầu kỳ = tổng các phát sinh có ngày trước 00:00 ngày from.
- Kỳ lọc bao gồm cả from và to: >= from 00:00 và < (to+1 ngày) 00:00.
- Dòng sắp theo thời điểm, rồi loại nguồn ORDER → PAYMENT → INVOICE_ADJUSTMENT, rồi ID tăng dần để ổn định khi trùng thời điểm.
- Phát sinh tăng/giảm luôn không âm. Số dư từng dòng = số dư trước + tăng - giảm.
- Số dư cuối kỳ = đầu kỳ + tổng tăng - tổng giảm. **Không cắt số dư âm về 0**: âm phản ánh thanh toán dư/credit, cần được giữ để đối chiếu.
- Kỳ không có dòng vẫn trả đúng đầu kỳ/cuối kỳ, không tạo dòng giả. Dữ liệu đại lý khác không được đưa vào sổ.
- Dùng BigDecimal và số tiền chính xác từ DB. JSON giữ biểu diễn số thập phân; client tài chính cần tránh ép tiền lớn sang số dấu phẩy động không chính xác.

Sổ là đối chiếu từ chứng từ **hiện còn trong DB và các trạng thái hiện tại**, không tái dựng lịch sử đã xóa hoặc lịch sử đổi trạng thái không được lưu. Không sửa đơn, hóa đơn, phiếu thu hoặc tạo phát sinh mới khi xuất. Phần sổ bao gồm điều chỉnh hóa đơn và giữ số dư có dấu; có thể khác API tổng hợp công nợ cũ trên branch vốn chỉ lấy tổng đơn trừ thu tiền và cắt âm về 0. S8-10 không thay đổi hành vi API tổng hợp cũ.

### JSON data

Các trường: `dealerId`, `dealerCode`, `dealerName`, `from`, `to`, `openingBalance`, `totalIncrease`, `totalDecrease`, `closingBalance`, `entries`.

Mỗi entry gồm `occurredAt`, `sourceType`, `sourceId`, `documentCode`, `description`, `increase`, `decrease`, `balance`. Source type/ID và mã chứng từ dùng để truy vết bản ghi gốc. Không đọc dữ liệu từ localStorage.

### Tệp xuất

Cả hai định dạng dùng cùng DTO/snapshot sổ, gồm thông tin đại lý, kỳ lọc, số dư đầu kỳ, toàn bộ dòng và tổng cuối kỳ. Cột: **Ngày phát sinh | Số chứng từ | Diễn giải | Phát sinh tăng | Phát sinh giảm | Số dư lũy kế**. Đơn vị VND, hiển thị hai chữ số thập phân.

- PDF: A4 ngang, nhúng phông Arial Unicode từ resource dự án; bảng lặp tiêu đề ở trang sau và có số trang. Hỗ trợ tiếng Việt, diễn giải xuống dòng và sổ nhiều trang.
- Excel: sheet `Chi tiết công nợ`, cố định phần đầu, dòng tiêu đề lặp khi in. Mã/diễn giải là ô text, không thực thi chuỗi bắt đầu bằng dấu `=` như công thức. Tiền có <=15 chữ số có nghĩa ghi Number; tiền lớn hơn khả năng chính xác của Excel được ghi nguyên văn dạng text để không làm sai số dư.
- Content-Type: `application/pdf` hoặc `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`.
- Content-Disposition: attachment, filename UTF-8. Ví dụ `So-cong-no_DL-A_2026-10-01_2026-10-31.pdf` và `.xlsx`. Mã đại lý được làm sạch để không chèn đường dẫn/header.
- Cache-Control: no-store. CORS expose Content-Disposition để frontend lấy tên file.
- Dùng SXSSF 100 dòng cho workbook, dọn tệp tạm trước khi đóng kể cả khi có lỗi. PDF/XLSX được hoàn tất trước khi trả response, không trả nửa tệp.

### Giới hạn và lỗi

dealerId phải dương; from <= to, hỗ trợ năm 1000–9998 phù hợp khoảng ngày SQL. Không giới hạn số năm một kỳ một cách tùy ý. Mặc định tối đa **20.000 dòng trong kỳ**, cấu hình `app.debt-statement.max-rows` từ 1 đến 100.000. Truy vấn lấy maxRows+1 để phát hiện vượt, trả 400 yêu cầu thu hẹp ngày, **không âm thầm cắt dòng**. Phát sinh trước kỳ vẫn tính đầy đủ vào openingBalance bằng SUM riêng.

Các truy vấn theo cùng dealerId, trong read-only REPEATABLE_READ transaction để opening và dòng kỳ khớp snapshot trên MySQL. Không gọi API riêng từng chứng từ hoặc tải entity graph để tính số dư.

- 400: sai/thiếu ngày, dealerId không hợp lệ, from > to, format không hỗ trợ hoặc vượt số dòng.
- 401: chưa xác thực. 403: thiếu quyền hoặc ngoài phạm vi đại lý.
- 404: đại lý không tồn tại. 500: lỗi tạo tệp/hệ thống, không trả nội dung lỗi nội bộ.

### Kết nối và kiểm thử

Chỉ backend; frontend chưa có luồng gọi API sổ chi tiết hoặc tải hai định dạng này. Quyền FINANCE_VIEW_DEBT đã có trong ma trận quản trị và menu công nợ hiện có, nhưng chưa nghiệm thu ẩn/hiện nút xuất S8-10 cho đến khi frontend triển khai.

**44/44 tests qua**, 0 failure/error/skip: DealerDebtStatementIntegrationTest (10), FinanceControllerTest (13), DebtRiskSecurityTest (6), PermissionRevocationTest (1), RolePermissionConfigTest (14). Kiểm thử có dữ liệu đầu kỳ, các ngày biên, loại trừ dòng không ghi nợ, điều chỉnh/hủy/thu âm, số dư âm, không cộng trùng hóa đơn, cách ly đại lý, quyền với JWT cũ, Excel tiền lớn, PDF nhiều trang và giới hạn không xuất thiếu. Đã render và xem toàn bộ 3 trang PDF kiểm thử bằng Poppler: tiếng Việt, lặp header, số trang và số dư hiển thị đúng. DB H2 MySQL mode; chưa kiểm thử MySQL hoặc giao diện thực tế.

```text
mvn -q clean "-Dtest=DealerDebtStatementIntegrationTest,FinanceControllerTest,DebtRiskSecurityTest,PermissionRevocationTest,RolePermissionConfigTest" test
```


---

## S8-01 — Lập phiếu trả hàng từ đại lý và nhập lại kho (Backend — 23/10/2026)

Hỗ trợ Nhân viên kinh doanh lập phiếu trả hàng từ đại lý gắn với hoá đơn gốc và Quản lý kho xác nhận nhận hàng để nhập lại kho theo đúng quy trình.

### 1. Quy tắc nghiệp vụ cốt lõi (Acceptance Criteria S8-01)

1. **Chọn hoá đơn gốc, chọn dòng hàng và số lượng trả, bắt buộc chọn lý do trả**:
   - Phiếu trả hàng bắt buộc gắn với `invoiceId` (hoá đơn gốc đang hiệu lực `ISSUED` hoặc `ADJUSTED`; chặn hoá đơn `CANCELLED`).
   - Mỗi dòng trả hàng (`items`) tham chiếu tới `invoiceItemId` hoặc `productId` thuộc hoá đơn gốc, số lượng trả dương (`quantity > 0`) và **bắt buộc có lý do trả (`returnReason`)**.
2. **Số lượng trả không vượt số lượng đã giao trừ phần đã trả trước đó**:
   - Với từng dòng hoá đơn gốc, hệ thống tính:
     - `deliveredQuantity`: Số lượng đã giao / lập hoá đơn (`InvoiceItem.invoicedQuantity`).
     - `alreadyReturnedQuantity`: Tổng số lượng đã lập phiếu trả trước đó ở trạng thái `PENDING` hoặc `CONFIRMED` (không tính phiếu `CANCELLED`).
     - `remainingReturnableQuantity = deliveredQuantity - alreadyReturnedQuantity`.
   - Nếu số lượng trả (kể cả cộng dồn các dòng cùng `invoiceItemId` trong một request) vượt `remainingReturnableQuantity`, hệ thống chặn với `400 Bad Request`.
   - Khi lập phiếu trả, hệ thống dùng khoá bi quan (`PESSIMISTIC_WRITE`) trên hoá đơn gốc để chống vượt số lượng trả dưới tải đồng thời.
3. **Hàng trả về nhập lại đúng lô nếu SKU có quản lý lô; hàng hỏng nhập vào kho hàng lỗi**:
   - **SKU quản lý lô (`Product.manageLot = true`)**: Bắt buộc cung cấp `lotNumber` (hoặc tự động kế thừa từ phiếu xuất kho gốc nếu có). Khi xác nhận nhận hàng, hệ thống kiểm tra và nhập lại đúng lô trong `WarehouseLotInventory` (nếu lô chưa tồn tại tại kho đích như kho hàng lỗi thì bắt buộc có `expiryDate` hoặc kế thừa HSD từ lô gốc).
   - **Phân loại hàng tốt / hàng hỏng (`damaged` / `condition`)**:
     - Hàng tốt (`damaged = false`, `condition = GOOD`): Nhập về kho thường (`warehouseId` chỉ định hoặc kho của đơn hàng gốc). Chặn nếu chọn kho hàng lỗi cho hàng tốt.
     - Hàng hỏng (`damaged = true` hoặc `condition = DAMAGED`/`DEFECTIVE` hoặc lý do trả thuộc nhóm hàng hỏng/lỗi): Bắt buộc định tuyến vào **kho hàng lỗi** (`Warehouse.defective = true`). Nếu request truyền kho thường cho dòng hàng hỏng sẽ bị chặn `400 Bad Request`; nếu không truyền kho riêng, hệ thống tự động chọn kho hàng lỗi đang hoạt động (hoặc khởi tạo kho hàng lỗi mặc định `KHO-HANG-LOI`).
4. **Phiếu trả phải được Quản lý kho xác nhận nhận hàng mới cộng tồn**:
   - Khi Nhân viên kinh doanh lập phiếu (`POST /api/dealer-returns`), phiếu ở trạng thái `PENDING` (`Chờ kho xác nhận`) — **tuyệt đối chưa cộng tồn kho** (`WarehouseInventory` và `WarehouseLotInventory` giữ nguyên).
   - Chỉ khi người dùng có quyền `WAREHOUSE_APPROVE` (Quản lý kho) hoặc `ROLE_ADMIN` gọi `POST /api/dealer-returns/{id}/confirm`, phiếu mới chuyển sang `CONFIRMED` (`Đã nhập lại kho`), cộng tồn kho vật lý (`WarehouseInventory.baseQuantity`), cộng tồn theo lô (`WarehouseLotInventory.quantity`), hiển thị trên Thẻ kho (`DEALER_RETURN`), và ghi `AuditLog`.
5. **Ma trận phân quyền DB & Phạm vi dữ liệu (RLS)**:
   - Lập phiếu trả hàng: Bắt buộc quyền `ORDER_CREATE` (hoặc `ROLE_ADMIN`). Nhân viên kinh doanh chỉ được lập phiếu cho đại lý mình phụ trách hoặc hoá đơn/đơn hàng mình tạo.
   - Xác nhận nhận hàng trả và cộng tồn: Bắt buộc quyền `WAREHOUSE_APPROVE` (hoặc `ROLE_ADMIN`) và kho nhận hàng phải nằm trong phạm vi kho được phân công.
   - Xem phiếu trả / tra cứu hoá đơn đủ điều kiện trả: Bắt buộc một trong các quyền `ORDER_VIEW`, `ORDER_CREATE`, `INVOICE_VIEW`, `WAREHOUSE_VIEW`, `WAREHOUSE_APPROVE`, `WAREHOUSE_IMPORT` (hoặc `ROLE_ADMIN`).
   - Không dùng role-based fallback; thu hồi quyền trên ma trận DB có hiệu lực ngay ở request kế tiếp trên cùng JWT.

### 2. Danh sách Endpoints (`/api/dealer-returns`, `/api/warehouse/dealer-returns`, `/api/returns`)

| Method | Endpoint | Quyền yêu cầu | Mô tả |
|---|---|---|---|
| `GET` | `/api/dealer-returns/options` | Quyền xem/tạo phiếu trả hoặc kho | Danh mục lý do trả hàng chuẩn, kho thường, kho hàng lỗi, trạng thái và cờ quyền `canCreate`, `canConfirm` |
| `GET` | `/api/dealer-returns/eligible-invoices` | Quyền xem/tạo phiếu trả hoặc kho | Danh sách hoá đơn gốc đủ điều kiện trả hàng kèm từng dòng hàng và số lượng còn được trả |
| `GET` | `/api/dealer-returns/eligible-invoices/{invoiceId}` | Quyền xem/tạo phiếu trả hoặc kho | Chi tiết một hoá đơn gốc kèm số lượng đã giao, đã trả và còn được trả của từng dòng |
| `POST` | `/api/dealer-returns` | `ORDER_CREATE` hoặc `ROLE_ADMIN` | Lập phiếu trả hàng từ đại lý ở trạng thái `PENDING` (chưa cộng tồn kho) |
| `POST` | `/api/dealer-returns/{id}/confirm` | `WAREHOUSE_APPROVE` hoặc `ROLE_ADMIN` | Quản lý kho xác nhận đã nhận hàng trả, chuyển sang `CONFIRMED` và cộng tồn kho / tồn lô |
| `POST` | `/api/dealer-returns/{id}/cancel` | `ORDER_CREATE`, `ORDER_CANCEL`, hoặc `WAREHOUSE_APPROVE` | Huỷ phiếu trả hàng đang ở trạng thái `PENDING` kèm lý do bắt buộc |
| `GET` | `/api/dealer-returns/{id}` | Quyền xem/tạo phiếu trả hoặc kho | Xem chi tiết phiếu trả hàng |
| `GET` | `/api/dealer-returns` | Quyền xem/tạo phiếu trả hoặc kho | Danh sách phiếu trả hàng phân trang (`dealerId`, `invoiceId`, `warehouseId`, `status`, `keyword`, `startDate`, `endDate`, `page`, `size`) |


---

## S8-01 — Lập phiếu trả hàng từ đại lý và nhập lại kho (Backend — 23/10/2026)

Hỗ trợ Nhân viên kinh doanh lập phiếu trả hàng từ đại lý gắn với hoá đơn gốc và Quản lý kho xác nhận nhận hàng để nhập lại kho theo đúng quy trình.

### 1. Quy tắc nghiệp vụ cốt lõi (Acceptance Criteria S8-01)

1. **Chọn hoá đơn gốc, chọn dòng hàng và số lượng trả, bắt buộc chọn lý do trả**:
   - Phiếu trả hàng bắt buộc gắn với `invoiceId` (hoá đơn gốc đang hiệu lực `ISSUED` hoặc `ADJUSTED`; chặn hoá đơn `CANCELLED`).
   - Mỗi dòng trả hàng (`items`) tham chiếu tới `invoiceItemId` hoặc `productId` thuộc hoá đơn gốc, số lượng trả dương (`quantity > 0`) và **bắt buộc có lý do trả (`returnReason`)**.
2. **Số lượng trả không vượt số lượng đã giao trừ phần đã trả trước đó**:
   - Với từng dòng hoá đơn gốc, hệ thống tính:
     - `deliveredQuantity`: Số lượng đã giao / lập hoá đơn (`InvoiceItem.invoicedQuantity`).
     - `alreadyReturnedQuantity`: Tổng số lượng đã lập phiếu trả trước đó ở trạng thái `PENDING` hoặc `CONFIRMED` (không tính phiếu `CANCELLED`).
     - `remainingReturnableQuantity = deliveredQuantity - alreadyReturnedQuantity`.
   - Nếu số lượng trả (kể cả cộng dồn các dòng cùng `invoiceItemId` trong một request) vượt `remainingReturnableQuantity`, hệ thống chặn với `400 Bad Request`.
   - Khi lập phiếu trả, hệ thống dùng khoá bi quan (`PESSIMISTIC_WRITE`) trên hoá đơn gốc để chống vượt số lượng trả dưới tải đồng thời.
3. **Hàng trả về nhập lại đúng lô nếu SKU có quản lý lô; hàng hỏng nhập vào kho hàng lỗi**:
   - **SKU quản lý lô (`Product.manageLot = true`)**: Bắt buộc cung cấp `lotNumber` (hoặc tự động kế thừa từ phiếu xuất kho gốc nếu có). Khi xác nhận nhận hàng, hệ thống kiểm tra và nhập lại đúng lô trong `WarehouseLotInventory` (nếu lô chưa tồn tại tại kho đích như kho hàng lỗi thì bắt buộc có `expiryDate` hoặc kế thừa HSD từ lô gốc).
   - **Phân loại hàng tốt / hàng hỏng (`damaged` / `condition`)**:
     - Hàng tốt (`damaged = false`, `condition = GOOD`): Nhập về kho thường (`warehouseId` chỉ định hoặc kho của đơn hàng gốc). Chặn nếu chọn kho hàng lỗi cho hàng tốt.
     - Hàng hỏng (`damaged = true` hoặc `condition = DAMAGED`/`DEFECTIVE` hoặc lý do trả thuộc nhóm hàng hỏng/lỗi): Bắt buộc định tuyến vào **kho hàng lỗi** (`Warehouse.defective = true`). Nếu request truyền kho thường cho dòng hàng hỏng sẽ bị chặn `400 Bad Request`; nếu không truyền kho riêng, hệ thống tự động chọn kho hàng lỗi đang hoạt động (hoặc khởi tạo kho hàng lỗi mặc định `KHO-HANG-LOI`).
4. **Phiếu trả phải được Quản lý kho xác nhận nhận hàng mới cộng tồn**:
   - Khi Nhân viên kinh doanh lập phiếu (`POST /api/dealer-returns`), phiếu ở trạng thái `PENDING` (`Chờ kho xác nhận`) — **tuyệt đối chưa cộng tồn kho** (`WarehouseInventory` và `WarehouseLotInventory` giữ nguyên).
   - Chỉ khi người dùng có quyền `WAREHOUSE_APPROVE` (Quản lý kho) hoặc `ROLE_ADMIN` gọi `POST /api/dealer-returns/{id}/confirm`, phiếu mới chuyển sang `CONFIRMED` (`Đã nhập lại kho`), cộng tồn kho vật lý (`WarehouseInventory.baseQuantity`), cộng tồn theo lô (`WarehouseLotInventory.quantity`), hiển thị trên Thẻ kho (`DEALER_RETURN`), và ghi `AuditLog`.
5. **Ma trận phân quyền DB & Phạm vi dữ liệu (RLS)**:
   - Lập phiếu trả hàng: Bắt buộc quyền `ORDER_CREATE` (hoặc `ROLE_ADMIN`). Nhân viên kinh doanh chỉ được lập phiếu cho đại lý mình phụ trách hoặc hoá đơn/đơn hàng mình tạo.
   - Xác nhận nhận hàng trả và cộng tồn: Bắt buộc quyền `WAREHOUSE_APPROVE` (hoặc `ROLE_ADMIN`) và kho nhận hàng phải nằm trong phạm vi kho được phân công.
   - Xem phiếu trả / tra cứu hoá đơn đủ điều kiện trả: Bắt buộc một trong các quyền `ORDER_VIEW`, `ORDER_CREATE`, `INVOICE_VIEW`, `WAREHOUSE_VIEW`, `WAREHOUSE_APPROVE`, `WAREHOUSE_IMPORT` (hoặc `ROLE_ADMIN`).
   - Không dùng role-based fallback; thu hồi quyền trên ma trận DB có hiệu lực ngay ở request kế tiếp trên cùng JWT.

### 2. Danh sách Endpoints (`/api/dealer-returns`, `/api/warehouse/dealer-returns`, `/api/returns`)

| Method | Endpoint | Quyền yêu cầu | Mô tả |
|---|---|---|---|
| `GET` | `/api/dealer-returns/options` | Quyền xem/tạo phiếu trả hoặc kho | Danh mục lý do trả hàng chuẩn, kho thường, kho hàng lỗi, trạng thái và cờ quyền `canCreate`, `canConfirm` |
| `GET` | `/api/dealer-returns/eligible-invoices` | Quyền xem/tạo phiếu trả hoặc kho | Danh sách hoá đơn gốc đủ điều kiện trả hàng kèm từng dòng hàng và số lượng còn được trả |
| `GET` | `/api/dealer-returns/eligible-invoices/{invoiceId}` | Quyền xem/tạo phiếu trả hoặc kho | Chi tiết một hoá đơn gốc kèm số lượng đã giao, đã trả và còn được trả của từng dòng |
| `POST` | `/api/dealer-returns` | `ORDER_CREATE` hoặc `ROLE_ADMIN` | Lập phiếu trả hàng từ đại lý ở trạng thái `PENDING` (chưa cộng tồn kho) |
| `POST` | `/api/dealer-returns/{id}/confirm` | `WAREHOUSE_APPROVE` hoặc `ROLE_ADMIN` | Quản lý kho xác nhận đã nhận hàng trả, chuyển sang `CONFIRMED` và cộng tồn kho / tồn lô |
| `POST` | `/api/dealer-returns/{id}/cancel` | `ORDER_CREATE`, `ORDER_CANCEL`, hoặc `WAREHOUSE_APPROVE` | Huỷ phiếu trả hàng đang ở trạng thái `PENDING` kèm lý do bắt buộc |
| `GET` | `/api/dealer-returns/{id}` | Quyền xem/tạo phiếu trả hoặc kho | Xem chi tiết phiếu trả hàng |
| `GET` | `/api/dealer-returns` | Quyền xem/tạo phiếu trả hoặc kho | Danh sách phiếu trả hàng phân trang (`dealerId`, `invoiceId`, `warehouseId`, `status`, `keyword`, `startDate`, `endDate`, `page`, `size`) |


---

## S8-04 — Đặt chỉ tiêu doanh số theo tháng và theo dõi tiến độ theo thời gian thực (Backend — 23/10/2026)

Hỗ trợ Quản lý kinh doanh đặt chỉ tiêu doanh số theo tháng cho từng nhân viên và từng khu vực, theo dõi tiến độ thực đạt theo thời gian thực ngay giữa tháng, tự động khoá chỉ tiêu đã qua kỳ, và yêu cầu lý do kèm nhật ký khi chỉnh sửa chỉ tiêu đã khoá.

### 1. Quy tắc nghiệp vụ cốt lõi (Acceptance Criteria S8-04)

1. **Đặt chỉ tiêu theo tháng cho từng nhân viên và từng khu vực**:
   - Hỗ trợ 3 loại phạm vi (`SalesTargetScopeType`):
     - `EMPLOYEE`: Chỉ tiêu tháng cho từng nhân viên kinh doanh (`userId != null`, `territoryId == null`).
     - `TERRITORY`: Chỉ tiêu tháng cho từng khu vực (`userId == null`, `territoryId != null`).
     - `EMPLOYEE_TERRITORY`: Chỉ tiêu tháng cho nhân viên tại một khu vực cụ thể (`userId != null`, `territoryId != null`).
   - Ràng buộc duy nhất theo khoá chuẩn `scopeKey` (`YYYY-MM:U:<userId|ALL>:T:<territoryId|ALL>`), hỗ trợ đặt đơn lẻ (`POST /api/sales-targets`) hoặc đặt/cập nhật hàng loạt (`POST /api/sales-targets/batch`).
2. **Nhân viên xem được tiến độ của chính mình theo thời gian thực**:
   - Doanh số thực đạt (`actualAmount`) được tổng hợp trực tiếp theo thời gian thực từ các đơn hàng hợp lệ trong kỳ (`OrderStatus.debtBearingStatuses()`: `APPROVED`, `RECORDED`, `PICKING`, `PROCESSING`, `EXPORTED`, `SHIPPED`, `DELIVERED`, `CLOSED`, `COMPLETED`) theo mốc `COALESCE(recordedAt, createdAt)`.
   - Tính toán đầy đủ các chỉ số tiến độ ngay giữa tháng (Mid-month Pacing):
     - `completionRate` / `progressPercent`: Tỷ lệ hoàn thành mục tiêu (%).
     - `remainingAmount` & `overachievedAmount`: Số tiền còn thiếu hoặc vượt chỉ tiêu.
     - `expectedAmountToDate`: Mức doanh số chuẩn cần đạt tính đến ngày hiện tại trong tháng.
     - `pacingVarianceAmount`: Chênh lệch giữa thực đạt hiện tại và mức chuẩn giữa tháng (`actualAmount - expectedAmountToDate`).
     - `requiredDailyRunRate`: Doanh số bình quân mỗi ngày cần đạt trong những ngày còn lại của tháng để về đích.
     - `progressStatus`: `ACHIEVED`, `ON_TRACK`, `BEHIND`, `AT_RISK`, `NOT_STARTED`.
3. **Chỉ tiêu đã qua kỳ thì khoá lại, sửa phải có lý do và được ghi nhật ký**:
   - Chỉ tiêu thuộc kỳ trước kỳ hiện tại (`YearMonth < YearMonth.now()`) tự động có hiệu lực khoá (`locked = true`, `pastPeriod = true`). Tác vụ định kỳ `SalesTargetLockScheduler` và API `POST /api/sales-targets/lock-past-periods` tự động chốt khoá trong DB.
   - Khi cập nhật (`PUT /api/sales-targets/{id}` hoặc `POST /api/sales-targets/batch`) một chỉ tiêu đã qua kỳ hoặc đã bị khoá (`locked = true`), **bắt buộc phải truyền `reason` không rỗng** (nếu thiếu trả về `400 Bad Request`).
   - Mọi thao tác tạo, sửa, sửa chỉ tiêu đã khoá (`UPDATE_LOCKED_TARGET`), khoá, xoá đều lưu vào `AuditLog` (`entityType = "SALES_TARGET"`) kèm giá trị cũ (`oldValue`), giá trị mới (`newValue`), lý do (`reason`), người thực hiện (`username`) và thời gian (`createdAt`), tra cứu qua `GET /api/sales-targets/{id}/audit-logs`.
4. **Ma trận phân quyền DB & Phạm vi dữ liệu (RLS)**:
   - Đặt / sửa / khoá / xoá chỉ tiêu: Bắt buộc quyền `ORDER_APPROVE` (hoặc `ROLE_ADMIN`).
   - Xem chỉ tiêu, tiến độ cá nhân, báo cáo tổng hợp, lịch sử chỉnh sửa: Bắt buộc quyền `REPORT_SALES` hoặc `ORDER_APPROVE` (hoặc `ROLE_ADMIN`).
   - Nhân viên kinh doanh (`ROLE_SALES` thuần tuý, không có `ORDER_APPROVE`) chỉ được xem chỉ tiêu và tiến độ của chính mình (`userId == currentUser.id`) và chỉ tiêu khu vực thuộc địa bàn được phân công; truy cập chỉ tiêu của nhân viên khác trả về `403 Forbidden`.
   - Không dùng role-based fallback; thu hồi quyền trên ma trận DB có hiệu lực ngay lập tức trên cùng JWT.

### 2. Danh sách Endpoints (`/api/sales-targets`, `/api/reports/sales-targets`, `/api/manager/sales-targets`)

| Method | Endpoint | Quyền yêu cầu | Mô tả |
|---|---|---|---|
| `GET` | `/api/sales-targets/options` | `REPORT_SALES` hoặc `ORDER_APPROVE` hoặc `ROLE_ADMIN` | Danh mục nhân viên KD, khu vực, loại phạm vi, trạng thái tiến độ và cờ quyền `canView`, `canManage` |
| `GET` | `/api/sales-targets/my-progress` (alias `/me`) | `REPORT_SALES` hoặc `ORDER_APPROVE` hoặc `ROLE_ADMIN` | Nhân viên xem tiến độ thực đạt của chính mình theo thời gian thực ngay giữa tháng kèm bóc tách theo khu vực |
| `GET` | `/api/sales-targets/summary` (alias `/progress`, `/dashboard`) | `REPORT_SALES` hoặc `ORDER_APPROVE` hoặc `ROLE_ADMIN` | Báo cáo tổng hợp chỉ tiêu & tiến độ theo nhân viên và theo khu vực trong kỳ |
| `GET` | `/api/sales-targets` | `REPORT_SALES` hoặc `ORDER_APPROVE` hoặc `ROLE_ADMIN` | Tra cứu danh sách chỉ tiêu doanh số (`year`, `month`, `period`, `userId`, `territoryId`, `scopeType`, `locked`, `progressStatus`, `keyword`) |
| `GET` | `/api/sales-targets/{id}` | `REPORT_SALES` hoặc `ORDER_APPROVE` hoặc `ROLE_ADMIN` | Chi tiết một chỉ tiêu doanh số kèm tiến độ thực tế và nhật ký chỉnh sửa |
| `GET` | `/api/sales-targets/{id}/audit-logs` | `REPORT_SALES` hoặc `ORDER_APPROVE` hoặc `ROLE_ADMIN` | Danh sách nhật ký tạo/sửa/khoá của một chỉ tiêu doanh số |
| `POST` | `/api/sales-targets` | `ORDER_APPROVE` hoặc `ROLE_ADMIN` | Đặt mới chỉ tiêu doanh số tháng cho nhân viên hoặc khu vực |
| `POST` | `/api/sales-targets/batch` | `ORDER_APPROVE` hoặc `ROLE_ADMIN` | Đặt hoặc cập nhật hàng loạt chỉ tiêu doanh số trong kỳ |
| `PUT` | `/api/sales-targets/{id}` | `ORDER_APPROVE` hoặc `ROLE_ADMIN` | Cập nhật chỉ tiêu doanh số (nếu đã qua kỳ hoặc đã khoá, bắt buộc có `reason` và ghi nhật ký `AuditLog`) |
| `POST` | `/api/sales-targets/{id}/lock` | `ORDER_APPROVE` hoặc `ROLE_ADMIN` | Khoá thủ công một chỉ tiêu doanh số kèm lý do |
| `POST` | `/api/sales-targets/lock-past-periods` | `ORDER_APPROVE` hoặc `ROLE_ADMIN` | Khoá toàn bộ các chỉ tiêu đã qua kỳ nhưng chưa gắn cờ khoá trong DB |
| `DELETE` | `/api/sales-targets/{id}` | `ORDER_APPROVE` hoặc `ROLE_ADMIN` | Xoá chỉ tiêu chưa khoá thuộc kỳ hiện tại hoặc tương lai |

---

## S8-04 — Đặt chỉ tiêu doanh số theo tháng và theo dõi tiến độ theo thời gian thực (Backend — 23/10/2026)

Hỗ trợ Quản lý kinh doanh đặt chỉ tiêu doanh số theo tháng cho từng nhân viên và từng khu vực, theo dõi tiến độ thực đạt theo thời gian thực ngay giữa tháng, tự động khoá chỉ tiêu đã qua kỳ, và yêu cầu lý do kèm nhật ký khi chỉnh sửa chỉ tiêu đã khoá.

### 1. Quy tắc nghiệp vụ cốt lõi (Acceptance Criteria S8-04)

1. **Đặt chỉ tiêu theo tháng cho từng nhân viên và từng khu vực**:
   - Hỗ trợ 3 loại phạm vi (`SalesTargetScopeType`):
     - `EMPLOYEE`: Chỉ tiêu tháng cho từng nhân viên kinh doanh (`userId != null`, `territoryId == null`).
     - `TERRITORY`: Chỉ tiêu tháng cho từng khu vực (`userId == null`, `territoryId != null`).
     - `EMPLOYEE_TERRITORY`: Chỉ tiêu tháng cho nhân viên tại một khu vực cụ thể (`userId != null`, `territoryId != null`).
   - Ràng buộc duy nhất theo khoá chuẩn `scopeKey` (`YYYY-MM:U:<userId|ALL>:T:<territoryId|ALL>`), hỗ trợ đặt đơn lẻ (`POST /api/sales-targets`) hoặc đặt/cập nhật hàng loạt (`POST /api/sales-targets/batch`).
2. **Nhân viên xem được tiến độ của chính mình theo thời gian thực**:
   - Doanh số thực đạt (`actualAmount`) được tổng hợp trực tiếp theo thời gian thực từ các đơn hàng hợp lệ trong kỳ (`OrderStatus.debtBearingStatuses()`: `APPROVED`, `RECORDED`, `PICKING`, `PROCESSING`, `EXPORTED`, `SHIPPED`, `DELIVERED`, `CLOSED`, `COMPLETED`) theo mốc `COALESCE(recordedAt, createdAt)`.
   - Tính toán đầy đủ các chỉ số tiến độ ngay giữa tháng (Mid-month Pacing):
     - `completionRate` / `progressPercent`: Tỷ lệ hoàn thành mục tiêu (%).
     - `remainingAmount` & `overachievedAmount`: Số tiền còn thiếu hoặc vượt chỉ tiêu.
     - `expectedAmountToDate`: Mức doanh số chuẩn cần đạt tính đến ngày hiện tại trong tháng.
     - `pacingVarianceAmount`: Chênh lệch giữa thực đạt hiện tại và mức chuẩn giữa tháng (`actualAmount - expectedAmountToDate`).
     - `requiredDailyRunRate`: Doanh số bình quân mỗi ngày cần đạt trong những ngày còn lại của tháng để về đích.
     - `progressStatus`: `ACHIEVED`, `ON_TRACK`, `BEHIND`, `AT_RISK`, `NOT_STARTED`.
3. **Chỉ tiêu đã qua kỳ thì khoá lại, sửa phải có lý do và được ghi nhật ký**:
   - Chỉ tiêu thuộc kỳ trước kỳ hiện tại (`YearMonth < YearMonth.now()`) tự động có hiệu lực khoá (`locked = true`, `pastPeriod = true`). Tác vụ định kỳ `SalesTargetLockScheduler` và API `POST /api/sales-targets/lock-past-periods` tự động chốt khoá trong DB.
   - Khi cập nhật (`PUT /api/sales-targets/{id}` hoặc `POST /api/sales-targets/batch`) một chỉ tiêu đã qua kỳ hoặc đã bị khoá (`locked = true`), **bắt buộc phải truyền `reason` không rỗng** (nếu thiếu trả về `400 Bad Request`).
   - Mọi thao tác tạo, sửa, sửa chỉ tiêu đã khoá (`UPDATE_LOCKED_TARGET`), khoá, xoá đều lưu vào `AuditLog` (`entityType = "SALES_TARGET"`) kèm giá trị cũ (`oldValue`), giá trị mới (`newValue`), lý do (`reason`), người thực hiện (`username`) và thời gian (`createdAt`), tra cứu qua `GET /api/sales-targets/{id}/audit-logs`.
4. **Ma trận phân quyền DB & Phạm vi dữ liệu (RLS)**:
   - Đặt / sửa / khoá / xoá chỉ tiêu: Bắt buộc quyền `ORDER_APPROVE` (hoặc `ROLE_ADMIN`).
   - Xem chỉ tiêu, tiến độ cá nhân, báo cáo tổng hợp, lịch sử chỉnh sửa: Bắt buộc quyền `REPORT_SALES` hoặc `ORDER_APPROVE` (hoặc `ROLE_ADMIN`).
   - Nhân viên kinh doanh (`ROLE_SALES` thuần tuý, không có `ORDER_APPROVE`) chỉ được xem chỉ tiêu và tiến độ của chính mình (`userId == currentUser.id`) và chỉ tiêu khu vực thuộc địa bàn được phân công; truy cập chỉ tiêu của nhân viên khác trả về `403 Forbidden`.
   - Không dùng role-based fallback; thu hồi quyền trên ma trận DB có hiệu lực ngay lập tức trên cùng JWT.

### 2. Danh sách Endpoints (`/api/sales-targets`, `/api/reports/sales-targets`, `/api/manager/sales-targets`)

| Method | Endpoint | Quyền yêu cầu | Mô tả |
|---|---|---|---|
| `GET` | `/api/sales-targets/options` | `REPORT_SALES` hoặc `ORDER_APPROVE` hoặc `ROLE_ADMIN` | Danh mục nhân viên KD, khu vực, loại phạm vi, trạng thái tiến độ và cờ quyền `canView`, `canManage` |
| `GET` | `/api/sales-targets/my-progress` (alias `/me`) | `REPORT_SALES` hoặc `ORDER_APPROVE` hoặc `ROLE_ADMIN` | Nhân viên xem tiến độ thực đạt của chính mình theo thời gian thực ngay giữa tháng kèm bóc tách theo khu vực |
| `GET` | `/api/sales-targets/summary` (alias `/progress`, `/dashboard`) | `REPORT_SALES` hoặc `ORDER_APPROVE` hoặc `ROLE_ADMIN` | Báo cáo tổng hợp chỉ tiêu & tiến độ theo nhân viên và theo khu vực trong kỳ |
| `GET` | `/api/sales-targets` | `REPORT_SALES` hoặc `ORDER_APPROVE` hoặc `ROLE_ADMIN` | Tra cứu danh sách chỉ tiêu doanh số (`year`, `month`, `period`, `userId`, `territoryId`, `scopeType`, `locked`, `progressStatus`, `keyword`) |
| `GET` | `/api/sales-targets/{id}` | `REPORT_SALES` hoặc `ORDER_APPROVE` hoặc `ROLE_ADMIN` | Chi tiết một chỉ tiêu doanh số kèm tiến độ thực tế và nhật ký chỉnh sửa |
| `GET` | `/api/sales-targets/{id}/audit-logs` | `REPORT_SALES` hoặc `ORDER_APPROVE` hoặc `ROLE_ADMIN` | Danh sách nhật ký tạo/sửa/khoá của một chỉ tiêu doanh số |
| `POST` | `/api/sales-targets` | `ORDER_APPROVE` hoặc `ROLE_ADMIN` | Đặt mới chỉ tiêu doanh số tháng cho nhân viên hoặc khu vực |
| `POST` | `/api/sales-targets/batch` | `ORDER_APPROVE` hoặc `ROLE_ADMIN` | Đặt hoặc cập nhật hàng loạt chỉ tiêu doanh số trong kỳ |
| `PUT` | `/api/sales-targets/{id}` | `ORDER_APPROVE` hoặc `ROLE_ADMIN` | Cập nhật chỉ tiêu doanh số (nếu đã qua kỳ hoặc đã khoá, bắt buộc có `reason` và ghi nhật ký `AuditLog`) |
| `POST` | `/api/sales-targets/{id}/lock` | `ORDER_APPROVE` hoặc `ROLE_ADMIN` | Khoá thủ công một chỉ tiêu doanh số kèm lý do |
| `POST` | `/api/sales-targets/lock-past-periods` | `ORDER_APPROVE` hoặc `ROLE_ADMIN` | Khoá toàn bộ các chỉ tiêu đã qua kỳ nhưng chưa gắn cờ khoá trong DB |
| `DELETE` | `/api/sales-targets/{id}` | `ORDER_APPROVE` hoặc `ROLE_ADMIN` | Xoá chỉ tiêu chưa khoá thuộc kỳ hiện tại hoặc tương lai |


---

## S8-07 — Báo cáo tồn kho và vòng quay hàng để phát hiện hàng chậm luân chuyển đang chôn vốn (Backend — 24/10/2026)

Hỗ trợ Quản lý kho xem báo cáo giá trị tồn kho theo từng kho và theo từng nhóm hàng, tính hệ số vòng quay và số ngày tồn bình quân (Days Inventory Outstanding / Average Days in Inventory) của từng SKU, chỉ ra mặt hàng chậm luân chuyển đang chôn vốn, và xuất toàn bộ báo cáo ra file Excel (`.xlsx`).

### 1. Quy tắc nghiệp vụ cốt lõi (Acceptance Criteria S8-07)

1. **Giá trị tồn theo kho và theo nhóm hàng**:
   - Đơn giá vốn quy chuẩn của từng SKU: `unitCost = COALESCE(costPrice > 0, price > 0, 0)`.
   - Giá trị tồn kho cuối kỳ của SKU tại kho: `inventoryValue = closingQuantity * unitCost`.
   - Tổng hợp đầy đủ theo 3 chiều:
     - **Theo kho (`warehouseValuations` / `/by-warehouse`)**: Tổng số SKU có tồn, số SKU chậm luân chuyển, tồn đầu kỳ, nhập trong kỳ, xuất trong kỳ, tồn thực tế cuối kỳ (`physicalQuantity`), tồn đang giữ chỗ (`reservedQuantity`), tồn khả dụng (`availableQuantity`), giá trị tồn kho (`inventoryValue`), tỷ trọng giá trị tồn (`valueSharePercent`), giá trị hàng chậm luân chuyển đang chôn vốn (`slowMovingValue`), hệ số vòng quay và số ngày tồn bình quân của kho.
     - **Theo nhóm hàng (`categoryValuations` / `/by-category`)**: Tổng hợp tồn lượng, giá trị tồn, tỷ trọng giá trị, số SKU và giá trị vốn bị chôn ở hàng chậm luân chuyển theo từng nhóm hàng (hỗ trợ lọc cả cây nhóm hàng cha – con).
     - **Theo cặp Kho × Nhóm hàng (`warehouseCategoryValuations` / `/by-warehouse-category`)**: Bóc tách ma trận giá trị tồn và giá trị chôn vốn theo từng kho kết hợp từng nhóm hàng.
2. **Số ngày tồn bình quân của từng SKU, chỉ ra hàng chậm luân chuyển đang chôn vốn**:
   - Tồn kho bình quân trong kỳ (`periodDays`, mặc định `30` ngày):
     - `averageInventoryQuantity = (openingQuantity + closingQuantity) / 2`
   - Khi có phát sinh xuất kho trong kỳ (`outboundQuantity > 0`):
     - Hệ số vòng quay trong kỳ: `turnoverRate = outboundQuantity / averageInventoryQuantity`
     - Hệ số vòng quay quy đổi năm: `annualizedTurnoverRate = turnoverRate * 365 / periodDays`
     - **Số ngày tồn bình quân (`averageDaysInInventory` / DIO)**: `averageDaysInInventory = (averageInventoryQuantity * periodDays) / outboundQuantity`
   - **Phát hiện hàng chậm luân chuyển đang chôn vốn (`slowMoving = true`)**:
     - Khi SKU còn tồn kho cuối kỳ (`closingQuantity > 0`) và thuộc một trong hai trường hợp:
       1. `outboundQuantity == 0` trong suốt kỳ phân tích $\rightarrow$ phân loại `DEAD_STOCK` (*Tồn đọng không xuất*).
       2. `averageDaysInInventory >= slowMovingThresholdDays` (mặc định `60` ngày) $\rightarrow$ phân loại `SLOW_MOVING` (*Chậm luân chuyển*).
     - Giá trị vốn bị chôn của SKU: `tiedUpCapitalValue = inventoryValue` (nếu `slowMoving == true`, ngược lại `0`).
     - Cung cấp danh sách chuyên biệt `slowMovingItems` (và endpoint `/slow-moving`) sắp xếp giảm dần theo `tiedUpCapitalValue` kèm thuyết minh nguyên nhân (`slowMovingReason`) và số ngày kể từ lần xuất kho gần nhất (`daysSinceLastOutbound`).
3. **Xuất Excel (`.xlsx`)**:
   - Endpoint `GET /api/reports/inventory-turnover/export` (alias `/export/excel`, `/excel`) xuất file `.xlsx` gồm 4 sheet:
     - Sheet 1: `Giá trị tồn theo kho` (Tổng quan KPI + Bảng giá trị tồn theo kho + Bảng giá trị tồn theo Kho × Nhóm hàng).
     - Sheet 2: `Giá trị tồn theo nhóm hàng`.
     - Sheet 3: `Vòng quay & Số ngày tồn SKU`.
     - Sheet 4: `Hàng chậm luân chuyển` (Danh sách SKU đang chôn vốn).
   - Tự động làm sạch ô văn bản mở đầu bằng `=`, `+`, `-`, `@` để chống Spreadsheet Formula Injection.
4. **Ma trận phân quyền DB & Phạm vi kho**:
   - Bắt buộc có quyền `REPORT_INVENTORY` (hoặc `ROLE_ADMIN`) được đọc trực tiếp từ ma trận phân quyền DB (`RolePermissionConfig`).
   - Tuyệt đối không dùng role-based fallback: nếu thu hồi `REPORT_INVENTORY` khỏi `ROLE_WAREHOUSE_MANAGER`, mọi request kế tiếp trên cùng JWT lập tức trả `403 Forbidden`.
   - Nếu tài khoản được gán danh sách kho cụ thể (`user.warehouses`), báo cáo tự động giới hạn trong các kho được phân công và trả `403 Forbidden` nếu truyền `warehouseId` ngoài phạm vi.

### 2. Danh sách Endpoints (`/api/reports/inventory-turnover`, `/api/reports/inventory`, `/api/warehouse/reports/inventory-turnover`, `/api/warehouse/inventory-turnover`)

| Method | Endpoint | Quyền yêu cầu | Mô tả |
|---|---|---|---|
| `GET` | `/api/reports/inventory-turnover/options` | `REPORT_INVENTORY` hoặc `ROLE_ADMIN` | Bộ lọc kho trong phạm vi phân quyền, nhóm hàng, các ngưỡng mặc định và danh mục trạng thái luân chuyển |
| `GET` | `/api/reports/inventory-turnover` | `REPORT_INVENTORY` hoặc `ROLE_ADMIN` | Báo cáo tổng hợp giá trị tồn theo kho, theo nhóm hàng, theo kho × nhóm hàng, vòng quay từng SKU và danh sách hàng chậm luân chuyển |
| `GET` | `/api/reports/inventory-turnover/by-warehouse` (alias `/warehouses`) | `REPORT_INVENTORY` hoặc `ROLE_ADMIN` | Giá trị tồn kho, giá trị chôn vốn và số ngày tồn bình quân theo từng kho |
| `GET` | `/api/reports/inventory-turnover/by-category` (alias `/categories`) | `REPORT_INVENTORY` hoặc `ROLE_ADMIN` | Giá trị tồn kho, giá trị chôn vốn và số ngày tồn bình quân theo từng nhóm hàng |
| `GET` | `/api/reports/inventory-turnover/by-warehouse-category` | `REPORT_INVENTORY` hoặc `ROLE_ADMIN` | Giá trị tồn kho chi tiết theo cặp Kho × Nhóm hàng |
| `GET` | `/api/reports/inventory-turnover/skus` (alias `/items`) | `REPORT_INVENTORY` hoặc `ROLE_ADMIN` | Danh sách phân trang số ngày tồn bình quân và hệ số vòng quay của từng SKU |
| `GET` | `/api/reports/inventory-turnover/slow-moving` | `REPORT_INVENTORY` hoặc `ROLE_ADMIN` | Danh sách các SKU chậm luân chuyển đang chôn vốn (sắp xếp theo giá trị chôn vốn giảm dần) |
| `GET` | `/api/reports/inventory-turnover/export` (alias `/export/excel`, `/excel`) | `REPORT_INVENTORY` hoặc `ROLE_ADMIN` | Xuất báo cáo tồn kho và vòng quay hàng ra file Excel (`.xlsx`) |
---

## S8-07 — Báo cáo tồn kho và vòng quay hàng để phát hiện hàng chậm luân chuyển đang chôn vốn (Backend — 24/10/2026)

Hỗ trợ Quản lý kho xem báo cáo giá trị tồn kho theo từng kho và theo từng nhóm hàng, tính hệ số vòng quay và số ngày tồn bình quân (Days Inventory Outstanding / Average Days in Inventory) của từng SKU, chỉ ra mặt hàng chậm luân chuyển đang chôn vốn, và xuất toàn bộ báo cáo ra file Excel (`.xlsx`).

### 1. Quy tắc nghiệp vụ cốt lõi (Acceptance Criteria S8-07)

1. **Giá trị tồn theo kho và theo nhóm hàng**:
   - Đơn giá vốn quy chuẩn của từng SKU: `unitCost = COALESCE(costPrice > 0, price > 0, 0)`.
   - Giá trị tồn kho cuối kỳ của SKU tại kho: `inventoryValue = closingQuantity * unitCost`.
   - Tổng hợp đầy đủ theo 3 chiều:
     - **Theo kho (`warehouseValuations` / `/by-warehouse`)**: Tổng số SKU có tồn, số SKU chậm luân chuyển, tồn đầu kỳ, nhập trong kỳ, xuất trong kỳ, tồn thực tế cuối kỳ (`physicalQuantity`), tồn đang giữ chỗ (`reservedQuantity`), tồn khả dụng (`availableQuantity`), giá trị tồn kho (`inventoryValue`), tỷ trọng giá trị tồn (`valueSharePercent`), giá trị hàng chậm luân chuyển đang chôn vốn (`slowMovingValue`), hệ số vòng quay và số ngày tồn bình quân của kho.
     - **Theo nhóm hàng (`categoryValuations` / `/by-category`)**: Tổng hợp tồn lượng, giá trị tồn, tỷ trọng giá trị, số SKU và giá trị vốn bị chôn ở hàng chậm luân chuyển theo từng nhóm hàng (hỗ trợ lọc cả cây nhóm hàng cha – con).
     - **Theo cặp Kho × Nhóm hàng (`warehouseCategoryValuations` / `/by-warehouse-category`)**: Bóc tách ma trận giá trị tồn và giá trị chôn vốn theo từng kho kết hợp từng nhóm hàng.
2. **Số ngày tồn bình quân của từng SKU, chỉ ra hàng chậm luân chuyển đang chôn vốn**:
   - Tồn kho bình quân trong kỳ (`periodDays`, mặc định `30` ngày):
     - `averageInventoryQuantity = (openingQuantity + closingQuantity) / 2`
   - Khi có phát sinh xuất kho trong kỳ (`outboundQuantity > 0`):
     - Hệ số vòng quay trong kỳ: `turnoverRate = outboundQuantity / averageInventoryQuantity`
     - Hệ số vòng quay quy đổi năm: `annualizedTurnoverRate = turnoverRate * 365 / periodDays`
     - **Số ngày tồn bình quân (`averageDaysInInventory` / DIO)**: `averageDaysInInventory = (averageInventoryQuantity * periodDays) / outboundQuantity`
   - **Phát hiện hàng chậm luân chuyển đang chôn vốn (`slowMoving = true`)**:
     - Khi SKU còn tồn kho cuối kỳ (`closingQuantity > 0`) và thuộc một trong hai trường hợp:
       1. `outboundQuantity == 0` trong suốt kỳ phân tích $\rightarrow$ phân loại `DEAD_STOCK` (*Tồn đọng không xuất*).
       2. `averageDaysInInventory >= slowMovingThresholdDays` (mặc định `60` ngày) $\rightarrow$ phân loại `SLOW_MOVING` (*Chậm luân chuyển*).
     - Giá trị vốn bị chôn của SKU: `tiedUpCapitalValue = inventoryValue` (nếu `slowMoving == true`, ngược lại `0`).
     - Cung cấp danh sách chuyên biệt `slowMovingItems` (và endpoint `/slow-moving`) sắp xếp giảm dần theo `tiedUpCapitalValue` kèm thuyết minh nguyên nhân (`slowMovingReason`) và số ngày kể từ lần xuất kho gần nhất (`daysSinceLastOutbound`).
3. **Xuất Excel (`.xlsx`)**:
   - Endpoint `GET /api/reports/inventory-turnover/export` (alias `/export/excel`, `/excel`) xuất file `.xlsx` gồm 4 sheet:
     - Sheet 1: `Giá trị tồn theo kho` (Tổng quan KPI + Bảng giá trị tồn theo kho + Bảng giá trị tồn theo Kho × Nhóm hàng).
     - Sheet 2: `Giá trị tồn theo nhóm hàng`.
     - Sheet 3: `Vòng quay & Số ngày tồn SKU`.
     - Sheet 4: `Hàng chậm luân chuyển` (Danh sách SKU đang chôn vốn).
   - Tự động làm sạch ô văn bản mở đầu bằng `=`, `+`, `-`, `@` để chống Spreadsheet Formula Injection.
4. **Ma trận phân quyền DB & Phạm vi kho**:
   - Bắt buộc có quyền `REPORT_INVENTORY` (hoặc `ROLE_ADMIN`) được đọc trực tiếp từ ma trận phân quyền DB (`RolePermissionConfig`).
   - Tuyệt đối không dùng role-based fallback: nếu thu hồi `REPORT_INVENTORY` khỏi `ROLE_WAREHOUSE_MANAGER`, mọi request kế tiếp trên cùng JWT lập tức trả `403 Forbidden`.
   - Nếu tài khoản được gán danh sách kho cụ thể (`user.warehouses`), báo cáo tự động giới hạn trong các kho được phân công và trả `403 Forbidden` nếu truyền `warehouseId` ngoài phạm vi.

### 2. Danh sách Endpoints (`/api/reports/inventory-turnover`, `/api/reports/inventory`, `/api/warehouse/reports/inventory-turnover`, `/api/warehouse/inventory-turnover`)

| Method | Endpoint | Quyền yêu cầu | Mô tả |
|---|---|---|---|
| `GET` | `/api/reports/inventory-turnover/options` | `REPORT_INVENTORY` hoặc `ROLE_ADMIN` | Bộ lọc kho trong phạm vi phân quyền, nhóm hàng, các ngưỡng mặc định và danh mục trạng thái luân chuyển |
| `GET` | `/api/reports/inventory-turnover` | `REPORT_INVENTORY` hoặc `ROLE_ADMIN` | Báo cáo tổng hợp giá trị tồn theo kho, theo nhóm hàng, theo kho × nhóm hàng, vòng quay từng SKU và danh sách hàng chậm luân chuyển |
| `GET` | `/api/reports/inventory-turnover/by-warehouse` (alias `/warehouses`) | `REPORT_INVENTORY` hoặc `ROLE_ADMIN` | Giá trị tồn kho, giá trị chôn vốn và số ngày tồn bình quân theo từng kho |
| `GET` | `/api/reports/inventory-turnover/by-category` (alias `/categories`) | `REPORT_INVENTORY` hoặc `ROLE_ADMIN` | Giá trị tồn kho, giá trị chôn vốn và số ngày tồn bình quân theo từng nhóm hàng |
| `GET` | `/api/reports/inventory-turnover/by-warehouse-category` | `REPORT_INVENTORY` hoặc `ROLE_ADMIN` | Giá trị tồn kho chi tiết theo cặp Kho × Nhóm hàng |
| `GET` | `/api/reports/inventory-turnover/skus` (alias `/items`) | `REPORT_INVENTORY` hoặc `ROLE_ADMIN` | Danh sách phân trang số ngày tồn bình quân và hệ số vòng quay của từng SKU |
| `GET` | `/api/reports/inventory-turnover/slow-moving` | `REPORT_INVENTORY` hoặc `ROLE_ADMIN` | Danh sách các SKU chậm luân chuyển đang chôn vốn (sắp xếp theo giá trị chôn vốn giảm dần) |
| `GET` | `/api/reports/inventory-turnover/export` (alias `/export/excel`, `/excel`) | `REPORT_INVENTORY` hoặc `ROLE_ADMIN` | Xuất báo cáo tồn kho và vòng quay hàng ra file Excel (`.xlsx`) |
