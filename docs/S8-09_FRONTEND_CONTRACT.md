# S8-09 — Điểm nối frontend chờ backend

## Chưa phải API đã triển khai

Đây là **đề xuất bàn giao, chưa được thống nhất với backend**, không phải tài liệu endpoint đang tồn tại. Ngày 24/10/2026, nhánh này chưa có controller/service/DTO S8-09. Chỉ làm frontend, không sửa backend/schema hoặc quyền mặc định.

Trang **Báo cáo → Công nợ theo tuổi nợ**, `/reports/debt-aging`, dùng quyền `REPORT_FINANCE` đã có trong catalog ma trận DB. ADMIN luôn được phép; tài khoản chỉ đại lý không vào báo cáo nội bộ. Backend cần xác nhận quyền đọc/xuất và phạm vi nhân viên khi triển khai thật; FINANCE_VIEW_DEBT, DASHBOARD_VIEW hoặc tên vai trò ACCOUNTANT không thay thế quyền này.

`frontend/js/debt-aging-api.js` có `available: false`. Hai phương thức từ chối và không gửi request; UI báo rõ chưa kết nối, số liệu “—”, khóa bộ lọc/tải báo cáo/xuất Excel. Không lấy API dashboard/đại lý bị gắn cờ/sổ công nợ đơn lẻ để tự dựng nhóm tuổi nợ hoặc thứ hạng rủi ro. Không có dữ liệu mẫu trong code sản phẩm.

## Adapter cần thống nhất

Chưa ấn định URL, phương thức HTTP, tên query hoặc envelope. Khi có hợp đồng backend được xác nhận, adapter dùng `Auth.apiFetch`, tôn trọng `AbortSignal`, giữ chính xác tiền BigDecimal thành chuỗi **trước khi** JSON.parse làm mất chữ số và chuẩn hóa qua:

```js
DebtAgingApi.available // chỉ true sau khi nối/kiểm thử API thật
DebtAgingApi.options({ signal }) // Promise<Options>
DebtAgingApi.report(filters, { signal }) // Promise<FullReport>
```

Bộ test thay riêng asset adapter và mock `/__test_aging/*`; đây là đường dẫn **chỉ kiểm thử**, không phải API sản phẩm hay đề xuất URL.

### Options (ví dụ, không phải ngưỡng nghiệp vụ đã chốt)

```json
{
  "defaultAsOfDate": "2026-10-24",
  "timezone": "Asia/Ho_Chi_Minh",
  "buckets": [
    {"key": "recent", "label": "Quá hạn 1–14 ngày", "minDays": 1, "maxDays": 14},
    {"key": "middle", "label": "Quá hạn 15–44 ngày", "minDays": 15, "maxDays": 44},
    {"key": "old", "label": "Quá hạn từ 45 ngày", "minDays": 45, "maxDays": null}
  ],
  "employees": [{"id": 1, "code": "NV01", "name": "Nguyễn An"}]
}
```

Các ngưỡng trên chỉ minh họa/test. Máy chủ định nghĩa nhóm liên tiếp, không chồng lấn, từ 1 ngày quá hạn đến vô hạn; tối đa 20 nhóm. Cần thống nhất tuổi nợ tính từ hạn thanh toán hay ngày chứng từ, FIFO/thanh toán/ngày hiệu lực và nhân viên gốc hay nhân viên hiện tại. Frontend không tính ngày/rủi ro từ đồng hồ thiết bị, không hardcode 30/60/90.

`defaultAsOfDate` trong đề xuất là ngày nghiệp vụ mới nhất được phép chốt. Nếu ngày mặc định và ngày tối đa khác nhau, cần bổ sung trường riêng và sửa validator. `employees` là phạm vi người dùng được xem; chưa phân công dùng `employeeId: null`, không giả ID 0. UI thêm Tất cả nhân viên/Chưa phân công, không gọi danh sách tài khoản quản trị.

### Bộ lọc đã áp dụng

```js
{ asOfDate: '2026-10-24', employeeId: null, unassignedOnly: false } // tất cả
{ asOfDate: '2026-10-24', employeeId: 1, unassignedOnly: false }    // một nhân viên
{ asOfDate: '2026-10-24', employeeId: null, unassignedOnly: true }  // chưa phân công
```

Ngày phải hợp lệ và không sau ngày nghiệp vụ tối đa. Không gửi tên, điểm rủi ro hoặc tiền client tính lên server.

### FullReport (ví dụ một đại lý)

```js
{
  asOfDate: '2026-10-24', generatedAt: '2026-10-24T08:30:00+07:00',
  timezone: 'Asia/Ho_Chi_Minh', currency: 'VND', complete: true,
  filters: { asOfDate: '2026-10-24', employeeId: null, unassignedOnly: false },
  buckets: [ /* cùng định nghĩa Options */ ],
  riskBasis: 'Mô tả tiêu chí/thứ tự ưu tiên do backend quyết định',
  totals: { overdueAmount: '150.25', dealerCount: 1, maxOverdueDays: 90 },
  bucketTotals: [
    { bucketKey: 'recent', amount: '50.00', dealerCount: 1 },
    { bucketKey: 'middle', amount: '0.00', dealerCount: 0 },
    { bucketKey: 'old', amount: '100.25', dealerCount: 1 }
  ],
  employeeTotals: [{
    employeeId: 1, employeeName: 'Nguyễn An', dealerCount: 1,
    overdueAmount: '150.25', buckets: { recent: '50.00', middle: '0.00', old: '100.25' }
  }],
  dealers: [{
    dealerId: 1, dealerCode: 'DL01', dealerName: 'Đại lý A',
    employeeId: 1, employeeName: 'Nguyễn An', overdueAmount: '150.25',
    maxOverdueDays: 90, riskRank: 1, riskLabel: 'Ưu tiên cao',
    buckets: { recent: '50.00', middle: '0.00', old: '100.25' }
  }]
}
```

- Một snapshot **toàn bộ** kết quả trong bộ lọc, tối đa 5.000 đại lý. `complete: true` bắt buộc. Nếu cần dữ liệu lớn hơn, thống nhất pagination/snapshot/export server riêng rồi sửa adapter/UI; không cắt dòng hoặc xuất trang đầu như toàn báo cáo.
- Tiền là chuỗi thập phân không âm, tối đa 35 chữ số nguyên + 2 chữ số lẻ. Số đếm/ngày/rank/ID là số nguyên an toàn. Tổng nhóm/nhân viên/đại lý/toàn báo cáo phải khớp; UI chỉ đối chiếu bằng BigInt, không tự phân tuổi nợ hoặc tính điểm rủi ro.
- Mỗi đại lý một dòng, đã sắp `riskRank` tăng dần; được đồng hạng. Phần ưu tiên lấy hạng đầu server trả, không tự gọi đại lý nợ tiền cao nhất là rủi ro cao nhất. `riskBasis` giải thích tiêu chí; giữ nguyên nhãn/ngưỡng của server.
- Một đại lý có thể ở nhiều nhóm nên không cộng số đại lý các nhóm thành tổng. Bộ lọc áp dụng cho cả tổng, bảng nhóm, nhân viên, xếp hạng và Excel. Nhóm chưa phân công có ID null, tên do server cung cấp.
- Báo cáo rỗng hợp lệ vẫn có nhóm, totals/bucketTotals = 0, employeeTotals/dealers rỗng. API lỗi, thiếu trường hoặc chưa nối không phải số 0.

## Excel và quyền

- SheetJS nội bộ, không CDN; bốn sheet: thông tin/bộ lọc/tiêu chí, nhóm tuổi nợ, nhân viên, toàn bộ đại lý theo rủi ro. Trang UI 20/50/100 dòng không giới hạn dữ liệu xuất.
- Xuất đúng snapshot đã tải, tên file có ngày/phạm vi nhân viên. Thay bộ lọc xóa dữ liệu và khóa xuất đến khi tải lại; thu hồi quyền/rời trang vô hiệu hóa phản hồi trễ. Kiểm tra lại quyền trước khi dựng file và trước khi tải xuống; đây là bảo vệ UI, không thay thế quyền backend hoặc ngăn sao chép dữ liệu đã đọc hợp lệ.
- Mọi ô là text, không formula/hyperlink, tiền thập phân giữ đủ chữ số, tránh giới hạn 15 chữ số của Excel. Nếu cần ô số để tính toán phải thống nhất chính sách riêng, không làm tròn qua Number âm thầm.
- Không thêm dữ liệu nghiệp vụ vào localStorage/sessionStorage. Backend/API 403 thật chưa thể nghiệm thu khi S8-09 chưa có.

## Khi backend sẵn sàng

1. Xác nhận DTO/URL/quyền/phạm vi, ngày chốt, nhóm tuổi nợ, tiêu chí rủi ro và giới hạn dữ liệu.
2. Nối adapter thật, cập nhật validator/fixtures theo hợp đồng; không bật dữ liệu test ở môi trường thật.
3. Test grant/revoke cùng JWT, ADMIN, ACCOUNTANT không quyền, nhiều vai trò và phạm vi đại lý/nhân viên.
4. Đối chiếu tổng/nhóm/nhân viên/đại lý/Excel với DB sau thu tiền/đổi phân công; nghiệm thu end-to-end.
5. Cập nhật FUNCTION_MAP/status thành “đã nối” chỉ sau các bước trên.
