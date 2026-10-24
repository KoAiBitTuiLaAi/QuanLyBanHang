# Trạng thái sprint 1–8

## S7-02 — Ghi nhận thanh toán và đối trừ vào công nợ (Backend) (22/10/2026)

Nhánh `feature/S7-02/ghi-nhan-thanh-toan`. Đã đọc `AGENTS.md` và hai skill trong `SKILLS`. Yêu cầu người dùng: **chỉ làm Backend**.

| Tiêu chí trong ảnh / hạng mục | Backend | Frontend | Đã nối đúng | Quyền / kết quả |
|---|:---:|:---:|:---:|---|
| Ghi số tiền, hình thức thu, ngày thu, người thu, ghi chú | Có | Chưa làm (chỉ làm BE) | Đã sẵn sàng kết nối | `POST /api/finance/debt-payments`: lưu phiếu thu `DebtPayment`, các trường `amount`, `paymentMethod`, `paymentDate`, `recordedBy`, `referenceCode`, `note`, `paymentCode` sinh tự động `PT-YYYYMMDD-XXXX`. Yêu cầu `FINANCE_MANAGE` hoặc `ROLE_ADMIN`. |
| Chọn đối trừ vào hoá đơn cụ thể, hoặc tự động đối trừ theo nguyên tắc hoá đơn cũ nhất trước (FIFO) | Có | Chưa làm (chỉ làm BE) | Đã sẵn sàng kết nối | Mặc định `autoAllocate = true`: tự động tìm các đơn hàng còn nợ theo `recordedAt` cũ nhất và cấn trừ lần lượt; hoặc `autoAllocate = false` và gửi danh sách `allocations` chỉ định cụ thể từng đơn. Lưu chi tiết đối trừ vào `DebtPaymentAllocation`. Hỗ trợ API xem trước đối trừ `POST /api/finance/debt-payments/preview-allocation`. |
| Cho phép thu một phần; hoá đơn chuyển sang Thanh toán một phần | Có | Chưa làm (chỉ làm BE) | Đã sẵn sàng kết nối | Hoá đơn (`Order`) cập nhật `paidAmount` và `paymentStatus`: chưa trả = `UNPAID`, trả một phần = `PARTIALLY_PAID` ("Thanh toán một phần"), trả đủ = `PAID` ("Đã thanh toán"). |
| Không cho thu vượt tổng công nợ còn lại | Có | Chưa làm (chỉ làm BE) | Đã sẵn sàng kết nối | Kiểm tra tổng nợ còn lại của các đơn hàng đã ghi sổ của đại lý; nếu số tiền thu vượt quá tổng nợ còn lại hoặc số tiền đối trừ vượt quá nợ hoá đơn cụ thể, server ném ngoại lệ và trả mã lỗi `400 Bad Request`. |
| Đã ghi nhận thì không xoá, chỉ lập bút toán điều chỉnh | Có | Chưa làm (chỉ làm BE) | Đã sẵn sàng kết nối | `DELETE /api/finance/debt-payments/{id}` bị chặn và trả `400 Bad Request`. Cung cấp API lập bút toán điều chỉnh `POST /api/finance/debt-payments/{id}/adjust` với `REVERSAL` (hoàn trả toàn bộ) hoặc `PARTIAL_REVERSAL` (điều chỉnh giảm một phần), sinh mã `BTDC-YYYYMMDD-XXXX`, khôi phục công nợ và trạng thái hoá đơn tương ứng. |
| Tra cứu để biết chính xác đại lý còn nợ hoá đơn nào | Có | Chưa làm (chỉ làm BE) | Đã sẵn sàng kết nối | API `GET /api/finance/dealers/{dealerId}/invoices` và `GET /api/finance/dealers/{dealerId}/unpaid-invoices`: trả danh sách hoá đơn kèm số tiền tổng, đã trả, còn nợ, số ngày quá hạn, trạng thái thanh toán và lịch sử đối trừ. API `GET /api/finance/orders/{orderId}/settlements`. Yêu cầu `FINANCE_VIEW_DEBT` hoặc `ROLE_ADMIN`. |
| Ma trận phân quyền động (Permission matrix) | Có | Chưa làm (chỉ làm BE) | Đã sẵn sàng kết nối | Kết nối chặt chẽ với ma trận phân quyền DB (`RolePermissionConfig`), không role-based fallback. Thu hồi quyền trên JWT hiện tại lập tức trả 403 Forbidden. `ROLE_ADMIN` luôn có toàn quyền. |

Kiểm thử: **55 tests S7-02 qua 100%** (9 integration tests trong `DebtSettlementIntegrationTest`, 26 service tests trong `DealerDebtServiceTest`, 20 controller tests trong `FinanceControllerTest`), toàn bộ **815/815 test backend qua**. Đã cập nhật hợp đồng API tại `backend/API_DOCUMENTATION.md`.

---

## S8-02 — Sửa tích hợp frontend/backend sau đồng bộ develop (24/10/2026)

Đã đọc AGENTS.md và toàn bộ hai SKILLS. Đồng bộ `origin/develop` tại `67c5f57` vào nhánh frontend bằng commit `2267fc9`; giữ các story/menu/tài liệu khác khi giải quyết xung đột. Những sửa lỗi dưới đây đang ở working tree, chưa commit/push và không gộp nhánh này vào develop. Phối hợp Antigravity **Opus 4.6 Thinking** để rà rủi ro/kiểm thử, Codex kiểm chứng và chốt thay đổi.

| Hạng mục | Backend | Frontend | Kết nối/kiểm chứng |
|---|---|---|---|
| Phiếu thực và chứng từ liên kết hóa đơn gốc | DRAFT → CONFIRMED sinh InvoiceAdjustment(DECREASE); nháp mới hủy được | Tài chính → Điều chỉnh công nợ: chọn hóa đơn, nhập lượng thực trả/lý do, lưu nháp, xem tiền rồi xác nhận | Gọi `/api/invoices/returns`, không tạo phiếu trả giả từ hóa đơn, không dùng API điều chỉnh hóa đơn thủ công thay thế |
| Giá đã bán, chiết khấu và làm tròn | Lấy thành tiền sau chiết khấu của dòng gốc; làm tròn lũy kế, khóa DB và kiểm tra lại lượng còn trả | Nhập lượng trống mặc định; chuỗi thập phân/BigInt giữ chính xác, hiển thị giá lịch sử, không tự định giá | 13 ca tích hợp ReturnOrder gồm bảo toàn số lẻ, trả vượt/đồng thời, rollback và validation |
| Công nợ, quá hạn và hạn mức | Giảm đúng hóa đơn/đơn nguồn trước FIFO thanh toán; sửa trừ kép/thiếu giảm trừ giữa các API; response confirm có số dư server | Hiển thị số dư từ giao dịch xác nhận, không tự tính hạn mức hoặc dựng số dư lịch sử | Đối chiếu API công nợ, hóa đơn và hồ sơ đại lý; kiểm tra kết hợp điều chỉnh thủ công, thanh toán và nhiều đơn |
| Hóa đơn gốc bất biến | Không sửa bất kỳ cột hóa đơn, kể cả adjustedTotalAmount, khi xác nhận trả | Phiếu xác nhận chỉ đọc, hiện mã chứng từ và liên kết hóa đơn; không PDF giả | So sánh nguyên dòng DB của hóa đơn trước/sau; xác nhận lại 409, không sinh giảm nợ trùng |
| Ma trận quyền DB hai phía | VIEW đọc lịch sử; ADJUST chọn nguồn/lập/xác nhận/hủy; ADMIN luôn cho phép | Menu nhận VIEW hoặc ADJUST; nút theo đúng quyền, không đòi quyền catalog/khách hàng/công nợ ngoài luồng | Non-admin một vai trò, cấp/thu hồi cùng JWT; xóa form/chi tiết và bỏ phản hồi trễ khi quyền thay đổi |
| An toàn thao tác và bố cục | Transaction bao cả đánh giá công nợ; lỗi rollback toàn bộ | Chống gửi kép; 400 giữ form, 409 đọc lại chi tiết; kết quả ghi chưa rõ khóa gửi lại để đối chiếu; shell/gutters dùng chung, bảng cuộn nội bộ | 16 ca Chrome HTTP fixture, 222 kiểm tra bố cục (37 trang × 6 cỡ màn hình), điều hướng click/back/forward/reload/mobile qua |
| Tài liệu | API_DOCUMENTATION mô tả lookup và số dư/công thức mới | FUNCTION_MAP và FRONTEND_ROUTES cập nhật đúng vị trí | Không tự nhập kho S8-01, không thêm nghiệp vụ hoàn tiền hoặc lưu dữ liệu nghiệp vụ trên trình duyệt |

Kiểm thử đã qua: **214/214 Node**, **13/13 Python routes**, **111 ca backend** trong ReturnOrderIntegrationTest, DealerDebtServiceTest, InvoiceServiceTest, DealerServiceTest, RolePermissionConfigTest, PermissionRevocationTest, InvoiceSecurityTest, InvoiceControllerTest, InvoiceIntegrationTest và DealerSelfDebtPortalIntegrationTest (cộng các lượt chạy tập trung, 0 failure/error/skip ở báo cáo cuối từng bộ). Chrome: **16/16 luồng S8-02**, **222/222 lượt bố cục**, điều hướng chung qua. Chrome chạy code trang thật với API fixture; backend dùng H2 cách ly, chưa kiểm chứng giao dịch trên MySQL thật; không thay đổi dữ liệu nghiệp vụ thật.

Phần phụ thuộc từ develop: sửa tên getter DTO trong InventoryTurnoverExcelExportService và tên API/model cũ trong ba bộ test S8-07 vì chúng chặn bước compile toàn bộ backend/test. Chỉ sửa tương thích biên dịch, không phát triển lại thuật toán S8-07; không tuyên bố đã nghiệm thu cả S8-07. Bộ portal cũ được cách ly H2, bổ sung nhóm hàng/đơn vị quy đổi cho fixture, đọc đúng accessToken ở root JSON đăng nhập và parse JSON bytes để không đọc sai UTF-8. Nhật ký kiểm tra ở `E:/DevTemp/UserTemp/s802-final-check.log` (96 ca qua, portal lỗi trước khi sửa fixture) và `s802-portal-final.log` (3 portal qua); hai bộ InvoiceController/InvoiceIntegration có 12 ca qua ở báo cáo Surefire riêng. Đã dừng server kiểm thử riêng cổng 5539 sau xác minh.

## S8-02 — Lập chứng từ điều chỉnh giảm trừ công nợ khi trả hàng (Frontend) — 23/10/2026

**Lưu vết bản prototype cũ, không phải nghiệm thu hiện tại.** Các khẳng định “đã nối đúng”, số lượng trả dựng sẵn, công thức chưa trừ chiết khấu, cập nhật adjustedTotalAmount và yêu cầu quyền công nợ trong bảng cũ bên dưới đã bị thay thế bởi bản sửa 24/10 ở trên.

Nhánh `FE-S-802`. Đã đọc `AGENTS.md` và hai skill trong `SKILLS/SKILL_frontend_design.md` và `SKILLS/SKILL_backend.md`. Triển khai frontend tuân thủ đầy đủ 4 tiêu chí nghiệp vụ trong ảnh yêu cầu; giữ nguyên app-shell và bố cục chia sẻ `app-navigation.css`. Theo yêu cầu người dùng: "chỉ làm frontend".

| Tiêu chí trong ảnh / hạng mục | Backend | Frontend | Đã kết nối đúng | Ma trận quyền & Ghi chú |
|---|:---:|:---:|:---:|---|
| Chứng từ điều chỉnh sinh từ phiếu trả hàng đã xác nhận, giữ liên kết tới hoá đơn gốc | Có | Có | Có | Menu Tài chính → Điều chỉnh công nợ (`/finance/debt-adjustment`). Bảng phiếu trả hàng đã xác nhận giữ liên kết đối chiếu hoá đơn gốc `HD-xxxx` và mã phiếu trả `TH-xxxx-01`. |
| Giảm trừ đúng theo giá đã bán trên hoá đơn gốc, không phải giá hiện hành | Có | Có | Có | Dòng hàng điều chỉnh khóa theo đúng `unitPrice` của hoá đơn gốc; thành tiền giảm trừ = `SL trả * Đơn giá HĐ gốc`, tuyệt đối không lấy giá niêm yết hiện hành của danh mục/bảng giá. |
| Công nợ và hạn mức khả dụng của đại lý được cập nhật ngay | Có | Có | Có | Khung tính toán hiển thị trực quan trước và sau điều chỉnh: Công nợ hiện tại, Giảm trừ, Công nợ sau điều chỉnh và Hạn mức khả dụng mới. Ngay sau khi lập chứng từ thành công, gọi API cập nhật công nợ đại lý (`/api/dealers/{id}/debt`) và kích hoạt đánh giá lại (`/api/finance/debt/{id}/evaluate`). |
| Hoá đơn gốc vẫn giữ nguyên, không bị sửa | Có | Có | Có | Hoá đơn gốc được bảo toàn nguyên vẹn: các trường `items`, `totalAmount`, `subtotalAmount` không bị sửa/xoá (chặn PUT/DELETE 400 Bad Request); chỉ cập nhật `adjustedTotalAmount` và lưu vết chứng từ điều chỉnh liên kết. |
| Ma trận phân quyền database-backed & Không fallback vai trò | Có | Có | Có | Bắt buộc quyền `INVOICE_VIEW` để xem danh sách/chi tiết chứng từ; `INVOICE_ADJUST` để lập chứng từ điều chỉnh; `FINANCE_VIEW_DEBT` để xem hạn mức công nợ. Thu hồi quyền trên phiên hiện tại lập tức ẩn/vô hiệu hoá nút thao tác, đóng modal và chặn request; không dùng role-based fallback. `ROLE_ADMIN` luôn toàn quyền. |

---

## Đồng bộ develop → feature/S8-02/lap-chung-tu-dieu-chinh (24/10/2026)

Chỉ đồng bộ `origin/develop` tại `c09694f`, xử lý 5 file xung đột; không phát triển thêm hoặc sửa lỗi ngoài phạm vi. Giữ cả tài liệu/status của hai phía, hai phương thức khóa hóa đơn đang có caller riêng, handler tham số của develop và handler ResponseStatus của S8-02. Phần công nợ giữ cách tổng hợp hóa đơn/đơn chưa xuất của develop cùng khoản giảm trừ trả hàng S8-02; chưa nghiệm thu tích hợp tài chính sau đồng bộ do lỗi build bên dưới. Bổ sung dòng S8-02 vào FUNCTION_MAP theo quy định vừa nhận từ develop.

| Hạng mục kiểm tra sau đồng bộ | Kết quả |
|---|---|
| Frontend JS / server routes | 209/209 unit tests JS và 13/13 tests server/route qua |
| Backend ReturnOrderIntegrationTest, DealerDebtServiceTest, DealerSelfDebtPortalIntegrationTest, RolePermissionConfigTest | **Chưa chạy được test**: bước compile dừng ở InventoryTurnoverExcelExportService (S8-07), gọi getter getPhysicalQuantity/getReservedQuantity/getAvailableQuantity/getSlowMovingValue không có trong DTO |
| Nguồn lỗi build S8-07 | Service xuất Excel và ba DTO tương ứng giống hệt origin/develop; không phát sinh từ 5 file giải quyết xung đột. Giữ nguyên, không sửa trong lượt chỉ đồng bộ |
| Phạm vi DB | Không chạy backend nghiệp vụ, không reset DB/seed hoặc tạo/sửa/hủy chứng từ |

## S8-09 — Công nợ quá hạn theo tuổi nợ (24/10/2026, chỉ frontend)

Vị trí: **Báo cáo → Công nợ theo tuổi nợ**, `/reports/debt-aging`. Nhánh `feature/S8-09-frontend-xem_bao_cao_cong_no_qua_han_theo_tuoi_no`. Đã đọc AGENTS.md và hai SKILLS; giữ shell/content frame SaleStock. Không sửa bất kỳ file backend/schema/quyền mặc định; chưa commit/push/merge.

| Yêu cầu | Backend | Frontend | Kết nối/kiểm chứng |
|---|---|---|---|
| Tổng hợp nhóm tuổi nợ và nhân viên | Chưa có API/DTO/service S8-09 | Đã dựng bộ lọc ngày chốt/nhân viên/chưa phân công, ba chỉ số, bảng nhóm và nhân viên; ngưỡng từ adapter, không tự tính nghiệp vụ | **Chưa nối thật**. Adapter sản phẩm available=false, thông báo chờ backend, số liệu “—”, không có request phỏng đoán/dữ liệu demo |
| Đại lý rủi ro cao nhất | Chưa có tiêu chí/xếp hạng S8-09 | Sẵn sàng hiển thị riskBasis/riskRank/riskLabel từ server, giữ đồng hạng, mở dòng ưu tiên và phân trang 20/50/100 | Logic kiểm thử bằng fixture riêng; không lấy nợ cao nhất hoặc cờ vi phạm làm xếp hạng thay backend |
| Xuất Excel | Chưa có snapshot báo cáo thật | Sẵn logic bốn sheet theo toàn bộ snapshot, giữ chính xác tiền dạng text, không formula/hyperlink, tên file theo ngày/bộ lọc | **Khóa trên bản chạy thật đến khi nối API**; kiểm thử file tải xuống đủ dòng, vượt giới hạn an toàn Number, chống công thức |
| Ma trận quyền DB | REPORT_FINANCE đã có catalog; API 403 riêng S8-09 chưa tồn tại để nghiệm thu | Menu/guard/nút theo REPORT_FINANCE, ADMIN luôn có quyền; không fallback ACCOUNTANT, scope nội bộ loại đại lý-only | Test non-admin một vai trò VIEWER, cấp/thu hồi cùng JWT, hủy dữ liệu trễ; quyền API backend thật **chờ triển khai** |
| Lỗi/đổi bộ lọc/xuất bất đồng bộ | Không thay đổi backend | Xóa số cũ và khóa xuất khi thay bộ lọc, mất quyền, dữ liệu lỗi/không đầy đủ; hủy đọc cũ, bỏ phản hồi trễ, chống bấm xuất liên tiếp | Kiểm thử Chrome cách ly; báo cáo rỗng hợp lệ khác với thiếu API, không hiện 0 giả |
| Tài liệu/đối chiếu | Chỉ đọc source để xác nhận thiếu S8-09 | FUNCTION_MAP và FRONTEND_ROUTES đã bổ sung vị trí; tài liệu hợp đồng **đề xuất**, không giả thành API có sẵn | `docs/S8-09_FRONTEND_CONTRACT.md`; phải thống nhất với thành viên backend trước khi bật adapter |

Phối hợp Antigravity CLI bằng model **Claude Opus 4.6 (Thinking)** rà luồng xuất Excel/quyền/race/formula. Nhận xét được đối chiếu với code và kiểm thử, bổ sung các ca đổi bộ lọc giữa lúc xuất, thu hồi giữa hai lần kiểm tra, bấm kép và corpus `=`, `+`, `-`, `@`, tab/CR. Không thay bảo vệ frontend bằng backend mới ngoài phạm vi.

Kết quả cuối: **209/209 unit tests JS**, **13/13 tests server/route**, **18/18 kịch bản Chrome S8-09** và **216/216 kiểm tra bố cục trên 36 trang × 6 kích thước** qua. Đã xem ảnh pending-backend/desktop/mobile và đọc lại file Excel tải xuống bằng bộ đọc XLSX. Kiểm tra FUNCTION_MAP có 69 story, không trùng ID, đúng thứ tự. `git diff --check` sạch; không có diff trong backend. Khi chạy đồng thời hai bộ Chrome trên server Python thử nghiệm, từng gặp timeout khởi tạo/trạng thái sidebar; chạy lại từng bộ riêng đều qua, không đổi code shell để che lỗi test.

Kiểm thử frontend dùng script/trang thật + adapter HTTP mock **chỉ trong tests**, không tạo/sửa/xóa dữ liệu DB nghiệp vụ. Không coi các fixture là bằng chứng API đã hoạt động hoặc backend đã thực thi REPORT_FINANCE. Lệnh: `node --test tests/*.test.cjs`; `python -B -m unittest discover -s tests -p test_frontend_server.py`; `node tests/debt-aging.browser.cjs`; `node tests/content-layout.browser.cjs` (`NAV_TEST_URL` trỏ frontend cổng 5538).

## S8-07 — Báo cáo tồn kho và vòng quay hàng để phát hiện hàng chậm luân chuyển đang chôn vốn (Backend) — 24/10/2026

Phạm vi thực hiện: **Chỉ làm Backend (BE)** theo đúng yêu cầu của người dùng ("Đọc agents.md, làm theo yêu cầu trong ảnh, chỉ làm BE"), tuân thủ nghiêm ngặt `AGENTS.md` và `SKILLS/SKILL_backend.md`.

| Tiêu chí / Hạng mục (S8-07) | Backend | Frontend | Đã kết nối đúng / Ma trận phân quyền |
|---|:---:|:---:|---|
| **AC1: Giá trị tồn theo kho và theo nhóm hàng** | ✅ Đã hoàn thành | ❌ Chưa làm (theo yêu cầu chỉ làm BE) | API `GET /api/reports/inventory-turnover`, `/by-warehouse`, `/by-category`, `/by-warehouse-category` tổng hợp đầy đủ số lượng tồn thực tế, giữ chỗ, khả dụng, xuất trong kỳ, giá trị tồn kho (`inventoryValue`), tỷ trọng giá trị (`valueSharePercent`) và giá trị hàng chậm luân chuyển đang chôn vốn (`slowMovingValue`) theo từng kho, từng nhóm hàng (hỗ trợ cả nhóm cha – con) và ma trận Kho × Nhóm hàng. |
| **AC2: Số ngày tồn bình quân của từng SKU, chỉ ra hàng chậm luân chuyển đang chôn vốn** | ✅ Đã hoàn thành | ❌ Chưa làm (theo yêu cầu chỉ làm BE) | API `GET /api/reports/inventory-turnover/skus` và `/slow-moving` tính tồn bình quân `averageInventoryQuantity = (openingQuantity + closingQuantity) / 2`, hệ số vòng quay `turnoverRate`, số ngày tồn bình quân `averageDaysInInventory = (averageInventoryQuantity * periodDays) / outboundQuantity`, phân loại trạng thái luân chuyển (`FAST_MOVING`, `NORMAL_MOVING`, `SLOW_MOVING`, `DEAD_STOCK`, `OUT_OF_STOCK`), đánh dấu `slowMoving = true` và tính giá trị vốn bị chôn (`tiedUpCapitalValue`) kèm thuyết minh nguyên nhân. |
| **AC3: Xuất Excel (`.xlsx`)** | ✅ Đã hoàn thành | ❌ Chưa làm (theo yêu cầu chỉ làm BE) | API `GET /api/reports/inventory-turnover/export` (alias `/export/excel`, `/excel`) sinh file `.xlsx` gồm 4 sheet (`Giá trị tồn theo kho`, `Giá trị tồn theo nhóm hàng`, `Vòng quay & Số ngày tồn SKU`, `Hàng chậm luân chuyển`), định dạng tiền tệ/số lượng chuẩn và chống Spreadsheet Formula Injection (`=`, `+`, `-`, `@`). |
| **Thực thi Ma trận phân quyền DB (`RolePermissionConfig`) & Phạm vi kho** | ✅ Đã hoàn thành | ❌ Chưa làm (theo yêu cầu chỉ làm BE) | Kiểm tra quyền tại cả `SecurityConfig`, `@PreAuthorize` ở `InventoryTurnoverReportController` và `InventoryTurnoverReportServiceImpl`. Bắt buộc có quyền `REPORT_INVENTORY` (hoặc `ROLE_ADMIN`) từ ma trận DB; không dùng role-based fallback. Thu hồi `REPORT_INVENTORY` khỏi `ROLE_WAREHOUSE_MANAGER` lập tức trả về `403 Forbidden` trên cùng JWT; giới hạn phạm vi kho theo phân công của người dùng (`user.warehouses`). |

Kiểm thử Backend S8-07: Gồm `InventoryTurnoverReportServiceTest` (4 unit tests), `InventoryTurnoverReportSecurityTest` (4 `@WebMvcTest` security matrix tests), và `InventoryTurnoverReportIntegrationTest` (4 `@SpringBootTest` + H2 MySQL mode end-to-end tests kiểm chứng giá trị tồn theo kho/nhóm hàng, số ngày tồn bình quân SKU, phát hiện hàng chậm luân chuyển đang chôn vốn, xuất Excel `.xlsx` 4 sheet, giới hạn phạm vi kho và thu hồi quyền `REPORT_INVENTORY` tức thời trên cùng JWT). Đã cập nhật `backend/API_DOCUMENTATION.md` và `docs/FUNCTION_MAP.md`.

## S7-01 — Frontend phát hành hóa đơn bán hàng — 23/10/2026

| Hạng mục | Backend | Frontend | Kết nối / ma trận quyền |
|---|---|---|---|
| Tra cứu và phiếu nguồn | Dùng API S7-01 sẵn có, không thêm API/schema | Tài chính → Hóa đơn bán hàng; lọc/phân trang, chọn đại lý và nhiều phiếu đã giao trong kỳ | INVOICE_VIEW; không cần quyền quản lý đại lý/kho/đơn hàng riêng |
| Xem trước và phát hành | Tính tiền theo deliveredQuantity, cấp số trong transaction, kiểm tra phiếu trùng | Đối chiếu lượng đặt/thực giao/lập hóa đơn và tổng server; xác nhận rõ trước ghi | VIEW + CREATE, ADMIN toàn quyền; thay lựa chọn hủy preview, số liệu đổi phải xác nhận lại, không tự sinh số hoặc tính tiền JS |
| Bất biến và điều chỉnh | Dùng API chứng từ riêng hiện có | Không có sửa/xóa hóa đơn gốc; tăng/giảm tiền, thông tin, hủy bằng chứng từ và lịch sử | VIEW + ADJUST, bắt buộc lý do/xác nhận; số tiền sau điều chỉnh lấy từ server |
| In và PDF | Sửa lỗi PDF chứng từ bỏ qua invoiceId trong URL, nay kiểm tra đúng hóa đơn trước xuất | Bearer fetch PDF/HTML, Blob kiểm tra MIME/chữ ký; bản in sandbox + CSP, bỏ script/event/tài nguyên ngoài | VIEW; đóng/thu hồi giải phóng tài liệu, bỏ phản hồi trễ |
| Độ an toàn thao tác | Giữ luồng ghi S7-01 đã có | Khóa bấm lặp; 400 giữ thông tin, network/5xx sau ghi khóa tới Tải lại để kiểm tra | Không tự retry lệnh tài chính, không ghi DB thật trong kiểm thử frontend |
| Kiểm thử | 32 test S7-01 PASS (service/controller/security/integration), H2 từ bản sao source cách ly | 176 test Node + 11 test route PASS; Chrome luồng S7-01/6 widths PASS; layout 192 kiểm tra / 32 trang PASS | JWT hiện hành: VIEW/CREATE/ADJUST cấp-thu hồi hiệu lực; non-admin chỉ một role, ADMIN luôn được phép; không kiểm thử ghi DB thật |

Đọc AGENTS và hai skill repo; cập nhật đúng dòng S7-01 trong FUNCTION_MAP, FRONTEND_ROUTES và API docs cho bản sửa PDF. Phối hợp Antigravity CLI rà hợp đồng API và code chỉ đọc; Codex kiểm chứng các góp ý, giữ CSP/style nội tuyến đúng mẫu in thật và bổ sung khóa ghi khi kết quả HTTP 5xx chưa rõ. Không yêu cầu Antigravity sửa file hay dùng DB thật. Chưa commit/push/merge.

Log backend: `E:/DevTemp/UserTemp/s701-4fff3d8cee6e4cd58df7280eaccdf6fc/backend-verified.log`. Ảnh QA Chrome dùng API fixture tại `E:/DevTemp/UserTemp/qlbh-s701/`, không phải chứng từ phát hành trên DB thật. Chỉ chạy các test backend liên quan S7-01, không tuyên bố toàn bộ suite dự án đã xanh. Không ghi vào backend/target đang chạy, không reset DB.

## S6-09 — Chỉ frontend theo dõi giao hàng của đại lý — 23/10/2026

| Hạng mục | Backend | Frontend | Kết nối / quyền |
|---|---|---|---|
| Vị trí chức năng | Không sửa | Kinh doanh → Đơn hàng của tôi → Theo dõi giao hàng; dùng chi tiết hiện có | GET cổng đại lý sẵn có, không gọi tracking đơn chung/chuyến kho |
| Dòng thời gian 5 mốc | Chưa triển khai backend S6-09 riêng; API cũ thiếu lịch sử soạn/xuất/giao | Đã duyệt, Đang soạn, Đã xuất, Đang giao, Đã giao; mốc thiếu hiện chưa có dữ liệu | Chỉ hiển thị trạng thái/ngày được xác nhận; không suy diễn đã đi qua mọi bước, không dựng giờ hoặc ETA xe |
| Thực giao từng dòng | Dùng deliveredQuantity và shortageQuantity từ chi tiết cổng đại lý | Giữ số lẻ/số 0, phân biệt chưa cập nhật; cảnh báo còn nợ hàng | Không lấy số đặt thay số giao, không gửi lệnh ghi |
| Ma trận và phạm vi | Đọc đối chiếu controller/service có ORDER_VIEW + scope đại lý; không chỉnh BE | ORDER_VIEW + phạm vi cổng đại lý, ADMIN toàn quyền; thu hồi đóng/xóa dữ liệu | Chrome fixture kiểm tra cấp/thu hồi, phản hồi trễ và HTTP 403; không xác nhận đã thử ma trận trên DB thật trong lượt này |
| Khả dụng | Không thêm API/schema | Cập nhật trạng thái, lỗi/thử lại, focus bàn phím, bảng cuộn trong cửa sổ, 320–1920px | S5-02 đặt lại đơn giữ nguyên; không lưu dữ liệu nghiệp vụ trên browser storage |
| Kiểm thử | Không chạy hoặc sửa backend trong lượt frontend này | 170 test Node, 11 test route PASS; Chrome S6-09 và hồi quy S5-01/02 PASS; layout 186 kiểm tra / 31 trang / 6 widths PASS | Chrome chạy trang/script thật với API fixture cách ly GET-only cho S6-09, không ghi DB thật |

Chỉ triển khai frontend theo xác nhận của người dùng; backend S6-09 chưa ai làm nên story vẫn **Một phần**, chưa hoàn thiện lịch sử giao end-to-end. Cập nhật đúng dòng S6-09 trong FUNCTION_MAP và FRONTEND_ROUTES; không commit/push/merge.

Phối hợp Antigravity CLI rà soát chỉ đọc bản diff/adapter; áp dụng góp ý hợp lệ về kiểm tra kiểu dữ liệu, mốc hiện tại và nhãn bảng/focus. Đối chiếu code gốc trước khi dùng đề xuất: giữ `SHIPPED` là tên cũ của **Đã xuất** theo `OrderStatus`, không tự đổi sang Đang giao; nút đặt lại vẫn được bật trong `renderDetail`. Antigravity không sửa file, chạy lệnh ghi hoặc truy cập DB. Ảnh kiểm thử fixture tại `E:/DevTemp/UserTemp/qlbh-s609/`.

## S6-07 — Frontend theo dõi hàng đã xuất/chưa giao xong — 23/10/2026

| Hạng mục | Backend | Frontend | Kết nối / quyền |
|---|---|---|---|
| Danh sách/chi tiết chuyến | Dùng API GET sẵn có, scope kho và ma trận DB | Kho hàng → Theo dõi chuyến giao; lọc, phân trang, xem điểm giao/dòng hàng | VIEW hoặc EXPORT, ADMIN toàn quyền; không cần quyền đơn/công nợ, không fallback role |
| Tiền hàng/tiến độ | Sửa remainingPoints/Value loại hàng đã xác nhận trả kho; thêm tổng trách nhiệm mọi trạng thái và phần lịch sử chưa xử lý | Tổng tiền toàn scope kho, số đang chạy/đã giao/còn lại; cảnh báo hàng còn ở chuyến hoàn tất/hủy | Tổng hợp từ backend, không cộng trang hiện tại hoặc tự suy đoán số liệu |
| Quá hạn/hàng chưa giao được | Cờ/phút quá hạn server; bổ sung outstandingOnly=true để lọc mọi trạng thái | Nhãn/cảnh báo quá hạn; FAILED chưa trả vẫn thuộc chuyến, đã trả cần cờ xác nhận | Chỉ đọc; không tự hoàn tồn, không thêm POST/PUT/PATCH/DELETE kể cả ADMIN |
| Quyền/độ ổn định | Test JWT cũ grant/revoke, ADMIN và 403 ngoài kho | Xóa dữ liệu khi mất quyền, chặn phản hồi cũ; lỗi/rỗng/thử lại, cập nhật mỗi phút khi hiện tab | Không lưu nghiệp vụ browser storage; không tạo dữ liệu trên DB thật |
| Kiểm thử | 34 test backend liên quan PASS, JPA/H2 cách ly từ bản sao source | 163 test Node + 11 test route PASS; Chrome S6-07 và luồng S6-05/S6-08 PASS; 186 kiểm tra layout / 31 trang / 6 cỡ màn PASS | Không build vào target backend đang chạy; không reset DB |

Không làm giao diện tạo/phân tuyến chuyến S6-06 hoặc ghi giao nhận S6-08. Backend chỉ sửa/tăng cường phần đọc cần cho số liệu S6-07; không đổi schema/luồng ghi. Cập nhật FUNCTION_MAP đúng dòng S6-07 theo thứ tự, API docs và FRONTEND_ROUTES. Chưa commit/push/merge.

Minh chứng kiểm thử: `E:/DevTemp/UserTemp/s607-20261023/backend-focused.log`, `node.log`, `layout.log`, `browser-delivery-trips.log`; ảnh QA từ Chrome tại `E:/DevTemp/UserTemp/qlbh-s607/`. Chrome dùng trang/script thật với API fixture (GET-only, không phải ảnh/số liệu DB thật để báo cáo). Chỉ chạy các lớp backend liên quan S6-07, không tuyên bố toàn bộ suite dự án đã xanh.

## S6-05 — Xác nhận xuất kho, nợ hàng và xuất bù — 23/10/2026

| Hạng mục | Backend | Frontend | Kết nối / quyền |
|---|---|---|---|
| Xuất theo đơn | API kho trả lượng đặt/đã xuất/còn nợ; xác nhận tạo phiếu bất biến | Kho hàng → Xuất kho theo đơn; mở từ chi tiết đơn | VIEW hoặc EXPORT đọc, EXPORT ghi, ADMIN toàn quyền; kho phân công rõ |
| Xuất thiếu / xuất bù | Trừ đúng tồn thực + giữ chỗ; giữ phần còn nợ, phiên bản + UUID chống ghi lặp | Chờ xuất kho / Còn nợ hàng / Đã có phiếu xuất; lý do thiếu từng dòng | DB thật là nguồn dữ liệu; không lưu nghiệp vụ trên trình duyệt |
| Lô và tra cứu | Khóa tồn/lô, FEFO, ghi đúng lô; thẻ kho không đếm ORDER + VOUCHER hai lần | Chọn lô hoặc FEFO tự động; phiếu xác nhận chỉ đọc | Không xuất quá hạn trong luồng này; thu hồi quyền chặn server và nút/menu |
| Liên thông vòng đời | Không sửa thực giao; chặn giao/đóng/gán chuyến toàn đơn khi còn nợ; API xuất cũ dùng chung nghiệp vụ | Nút xuất ở chi tiết đơn dẫn tới màn phiếu mới | Không kích hoạt hóa đơn giả; phiếu cũ không sửa/xóa/điều chỉnh trực tiếp |
| Kiểm tra | 912 test backend PASS trong lượt hồi quy đã tách lỗi cũ, gồm 15 test tích hợp S6-05; JPA/H2 cách ly từ bản sao source | 163 test Node + 11 test route PASS; Chrome S6-05 và 5 luồng liên quan PASS; 180 kiểm tra layout / 30 trang / 6 cỡ màn PASS | JWT hiện hành: thu hồi EXPORT → 403/nút bị khóa, cấp lại dùng được; kiểm tra ADMIN, kho ngoài phạm vi, rollback, lô, đồng thời/idempotency |

Sửa kèm lỗi biên dịch từ backend S8-01 đã gộp: lambda bắt biến `targetWh` bị gán lại; chỉ chỉnh thông báo ngoại lệ để build được, không đổi nghiệp vụ trả hàng. Cập nhật API docs, routes và đúng dòng S6-05 trong FUNCTION_MAP. Cần restart backend/frontend để nạp code/API/route/cột bổ sung; không reset hoặc xóa DB.

**Giới hạn kết quả toàn dự án:** không tuyên bố toàn bộ suite gốc xanh. Lượt 912 PASS loại 3 lớp S8-01 (`DealerReturnIntegrationTest`, `DealerReturnSecurityTest`, `DealerReturnServiceTest`, tổng 15 test) vì còn 6 lỗi có sẵn; đã chạy lại chính 3 lớp trên bản HEAD trước S6-05 (chỉ sửa lỗi lambda để biên dịch) và tái hiện đúng 6 lỗi. Ngoài ra loại `DealerSelfDebtPortalIntegrationTest` (3 test) vì thiếu cấu hình H2/test profile và trỏ MySQL; lượt kiểm tra đầu bị từ chối kết nối, không thực hiện được DDL/ghi dữ liệu. Không sửa nghiệp vụ S8-01 hay reset DB để ép test xanh. Bổ sung `OrderShipmentService` vào test slice của `DemoDataSeederTest`; 7 test seed cách ly đã PASS.

Log xác minh ở `E:/DevTemp/UserTemp/s605-20261023/`: `backend-verified.log` (912 PASS), `backend-final.log` (15 S6-05 PASS trong lượt kiểm tra tập trung), `baseline-return.log` (6 lỗi S8-01 trên code gốc), `node-final.log`, `layout.log`, `browser-order-shipments.log`, `orders-final-v3.log`. Các lần chạy Chrome dùng trang/script thật với API fixture, không phải kiểm thử ghi phiếu trên DB thật. Không commit/push/merge; đã dừng frontend fixture riêng cổng 5525.

## S8-04 — Đặt chỉ tiêu doanh số và theo dõi tiến độ theo thời gian thực (Backend) — 23/10/2026

Phạm vi thực hiện: **Chỉ làm Backend (BE)** theo đúng yêu cầu của người dùng ("Đọc agents.md, làm theo yêu cầu trong ảnh, chỉ làm BE"), tuân thủ nghiêm ngặt `AGENTS.md` và `SKILLS/SKILL_backend.md`.

| Tiêu chí / Hạng mục (S8-04) | Backend | Frontend | Đã kết nối đúng / Ma trận phân quyền |
|---|:---:|:---:|---|
| **AC1: Đặt chỉ tiêu theo tháng cho từng nhân viên và từng khu vực** | ✅ Đã hoàn thành | ❌ Chưa làm (theo yêu cầu chỉ làm BE) | Hỗ trợ 3 phạm vi `EMPLOYEE` (theo nhân viên), `TERRITORY` (theo khu vực), `EMPLOYEE_TERRITORY` (nhân viên tại khu vực) qua `POST /api/sales-targets` và `POST /api/sales-targets/batch`. Ràng buộc duy nhất theo `scopeKey` (`YYYY-MM:U:<userId\|ALL>:T:<territoryId\|ALL>`). Yêu cầu quyền `ORDER_APPROVE` (hoặc `ROLE_ADMIN`) từ ma trận quyền DB. |
| **AC2: Nhân viên xem được tiến độ của chính mình theo thời gian thực ngay giữa tháng** | ✅ Đã hoàn thành | ❌ Chưa làm (theo yêu cầu chỉ làm BE) | API `GET /api/sales-targets/my-progress` (và `/summary`, `/api/sales-targets`) tổng hợp doanh số thực tế (`actualAmount`) trực tiếp từ các đơn hàng hợp lệ trong kỳ (`OrderStatus.debtBearingStatuses()`) theo thời gian thực; tính tỷ lệ hoàn thành (`completionRate`), mức chuẩn cần đạt giữa tháng (`expectedAmountToDate`), độ lệch tiến độ (`pacingVarianceAmount`), tốc độ cần đạt mỗi ngày còn lại (`requiredDailyRunRate`) và trạng thái (`ACHIEVED`, `ON_TRACK`, `BEHIND`, `AT_RISK`, `NOT_STARTED`). Nhân viên `ROLE_SALES` chỉ xem được tiến độ của chính mình (RLS chặn 403 nếu xem của nhân viên khác). |
| **AC3: Chỉ tiêu đã qua kỳ thì khoá lại, sửa phải có lý do và được ghi nhật ký** | ✅ Đã hoàn thành | ❌ Chưa làm (theo yêu cầu chỉ làm BE) | Chỉ tiêu thuộc kỳ đã qua (`YearMonth < YearMonth.now()`) tự động có hiệu lực khoá (`locked = true`, `pastPeriod = true`), kèm `SalesTargetLockScheduler` và API `POST /api/sales-targets/lock-past-periods`. Khi sửa (`PUT /api/sales-targets/{id}` hoặc `POST /api/sales-targets/batch`) chỉ tiêu đã qua kỳ hoặc đã khoá, bắt buộc có `reason` (thiếu trả `400 Bad Request`) và ghi nhận `AuditLog` (`entityType = "SALES_TARGET"`, action `UPDATE_LOCKED_TARGET`) kèm giá trị trước/sau, lý do, người sửa, tra cứu tại `GET /api/sales-targets/{id}/audit-logs`. |
| **Thực thi Ma trận phân quyền DB (`RolePermissionConfig`) & Không fallback role** | ✅ Đã hoàn thành | ❌ Chưa làm (theo yêu cầu chỉ làm BE) | Kiểm tra quyền tại cả `SecurityConfig`, `@PreAuthorize` ở `SalesTargetController` và `SalesTargetServiceImpl`. Đặt/sửa/khoá/xoá bắt buộc `ORDER_APPROVE` (hoặc `ROLE_ADMIN`); xem bắt buộc `REPORT_SALES` hoặc `ORDER_APPROVE` (hoặc `ROLE_ADMIN`). Thu hồi quyền trên ma trận DB lập tức trả về `403 Forbidden` ở request kế tiếp trên cùng JWT; `ROLE_ADMIN` luôn được phép. |

Kiểm thử Backend S8-04: **15/15 tests qua (100% PASS)** gồm `SalesTargetServiceTest` (7 unit tests), `SalesTargetSecurityTest` (5 `@WebMvcTest` security matrix tests), và `SalesTargetIntegrationTest` (3 `@SpringBootTest` + H2 MySQL mode end-to-end tests kiểm chứng tiến độ đơn hàng thời gian thực, tự động khoá kỳ cũ, bắt buộc lý do + ghi `AuditLog` khi sửa kỳ đã khoá, RLS theo nhân viên, và thu hồi quyền tức thời trên cùng JWT). Đã cập nhật `backend/API_DOCUMENTATION.md` và `docs/FUNCTION_MAP.md`.

## S6-08 — Sửa lỗi frontend, chưa triển khai backend — 23/10/2026

Phạm vi bổ sung do người dùng yêu cầu ngay trên nhánh S6-04; đã hỏi và xác nhận **chỉ frontend**. Không bổ sung API lưu ảnh/người nhận/hoàn kho S6-08.

| Hạng mục | Backend | Frontend | Kết nối / ma trận quyền |
|---|---|---|---|
| Menu và script bị thiếu | Không sửa | Tách menu Xác nhận giao hàng khỏi Kiểm kê; thêm `delivery-confirmation.js`, `app-transition.js` trong head | Menu/guard cần ORDER_VIEW + WAREHOUSE_EXPORT; ADMIN luôn vào được. Kiểm kê trở về VIEW hoặc ADJUST, không còn nhận nhầm EXPORT |
| Danh sách/chi tiết đơn thật | Dùng GET `/orders`, `/orders/{id}` sẵn có | Có — phân trang server, từ khóa/trạng thái, lỗi/tải lại; lọc kho và thống kê theo trang có nhãn | Không dùng danh sách demo/fallback; quyền/scope đơn do API quyết định. Thu hồi quyền xóa dữ liệu/form, bỏ phản hồi trễ |
| Form người nhận/thời điểm và xem trước ảnh | Chưa có API S6-08 | Có — nhập tạm, kiểm tra loại/kích thước/decode ảnh, đổi/xóa/phóng to; không lưu local/sessionStorage | Blob chỉ ở bộ nhớ, giải phóng khi đóng/reload/revoke; không khẳng định đã xác thực chữ ký hay lưu chứng từ |
| Ghi xác nhận/hoàn kho/điều kiện hoá đơn | **Chưa triển khai** | **Khóa nút lưu**, chặn submit, giải thích thiếu API; không giả số phiếu/kho/mã chứng từ hoặc thông báo thành công | Không gửi lệnh ghi nào từ trang S6-08, không đổi trạng thái đơn, tồn, công nợ hoặc hoá đơn |

Kiểm thử: Node toàn repo **163/163 qua** (gồm 5 test client S6-08 và test menu tách biệt/cấp-thu hồi); 2 lỗi tồn từ PR S6-08 đã hết. Chrome S6-08 qua GET-only, menu, phân trang/tìm, form, ảnh hợp lệ/không hợp lệ, XSS, lỗi tải, revoke/phản hồi trễ và 6 widths 320–1920px. Python routes **11/11**, bố cục cuối **174 checks / 29 trang / 6 widths** qua. API intercept fixtures không ghi DB thật. `docs/FUNCTION_MAP.md` và `docs/FRONTEND_ROUTES.md` cập nhật rõ S6-08 còn một phần; không tuyên bố S6-08 đạt toàn bộ AC.

## S6-04 — Frontend gợi ý lô xuất FEFO và sửa backend liên quan — 23/10/2026

Nhánh `feature/S6-04-frontend-he_thong_goi_y_lo_xuat_theo_nguyen_tac`. Đã đọc AGENTS.md và hai skill của repo. Dùng lại trang Lô & hạn sử dụng và khung `app-navigation.css`; không thêm shell/menu riêng. Vị trí đã cập nhật đúng dòng S6-04 trong `docs/FUNCTION_MAP.md`: **Kho hàng → Lô & hạn sử dụng → Gợi ý xuất FEFO**, URL `/warehouse/lots?tab=fefo`.

| Yêu cầu / hạng mục | Backend | Frontend | Kết nối / ma trận quyền |
|---|---|---|---|
| Mặc định lô HSD gần nhất còn đủ lượng | Có — dùng cùng hàm chọn FEFO cho gợi ý và ghi phiếu; sửa không tự chọn lô quá hạn/thiếu HSD, hỗ trợ SKU legacy còn tồn lô | Có — chọn kho/SKU/lượng theo đơn vị cơ sở, lấy gợi ý, tự chọn radio lô server trả; bảng giữ thứ tự server | GET `/warehouse/export/suggest-lot`; không tính FEFO bằng JS, không tự gộp nhiều lô khi một lô không đủ |
| Chọn lô khác phải có lý do | Có — tự tính lại gợi ý khi ghi, bỏ qua `suggestedLotNumber` giả; khác gợi ý hiện tại thì chặn nếu thiếu lý do | Có — hiển thị lô/lượng sẽ xuất, lý do bắt buộc khi đổi lô, checkbox xác nhận ghi sổ | POST `/warehouse/export` gửi đúng lô và lý do; lỗi 400 giữ input để sửa; lô quá hạn chỉ theo luồng ghi đè S6-01, không thuộc mặc định FEFO |
| Trừ đúng tồn lô đã chọn | Có — khóa tồn vật lý trước rồi current-read/khóa các lô theo ID; phiếu/tồn/lô/audit cùng transaction; không dùng tồn giữ chỗ của đơn khác | Có — chỉ ghi khi xác nhận, chống gửi lặp; hiện mã phiếu/lô thực từ API, tải lại tồn sau thành công | Hai yêu cầu cùng xuất lô không đủ cho cả hai: một thành công, một 400; không trừ lô khác, không âm/ghi đè số dư cũ |
| Quyền đọc/ghi và phiên hiện tại | Đọc gợi ý VIEW hoặc EXPORT, đọc options VIEW hoặc MANAGE; ghi EXPORT; ADMIN luôn được phép, scope kho giữ theo service | Trang cần VIEW/MANAGE; tab cần thêm VIEW/EXPORT, nút ghi cần EXPORT + `canExport` từ options; VIEW chỉ xem gợi ý | Không role fallback. Thu hồi xóa form/gợi ý và ẩn tab/nút; bỏ phản hồi đọc/ghi trễ, API JWT hiện tại trả 403 khi mất quyền |
| Điện thoại / thao tác lỗi | Validate lượng dương, 11 chữ số nguyên/4 số lẻ; kiểm lại dữ liệu khi ghi | Có — frame chung, form co về một cột, bảng cuộn bên trong, controls 44px; đổi đầu vào xóa gợi ý cũ | Chrome 320–1920px, không tràn ngang toàn trang. Mất kết nối/5xx không rõ kết quả POST thì chặn gửi lại ngay, nhắc kiểm tra Sổ cái trước |

Kiểm chứng: toàn bộ backend **866/866 qua** (`clean test`, bản sao backend/H2 tạm); tập trung **78 test backend qua**, gồm 6 ca S6-04 mới qua HTTP/JWT/JPA (loại quá hạn/thiếu HSD, validation/legacy/scope, single-role grant/revoke cùng JWT, giả gợi ý/thay đổi FEFO, xuất đồng thời, rollback khi tồn giữ chỗ không đủ). **11 test Node S6-04 và 6 test S6-01 qua**. Chrome S6-04 qua luồng gợi ý/chọn lô/lý do/ghi/lỗi/quyền/phản hồi trễ/XSS/không có lô đủ và 6 widths; Chrome S6-01, nhập kho S5-04 và thẻ kho S6-02 hồi quy qua. Python router **11/11**. Bố cục chung cuối **174 checks / 29 trang / 6 widths** qua, bao gồm trang S6-08 sau sửa frontend.

Lượt Node ban đầu **155/157 qua**, phát hiện 2 lỗi sẵn từ S6-08; sau đó người dùng yêu cầu sửa frontend S6-08 ngay trên nhánh này, kết quả cuối **163/163 qua** (xem mục trên). Kiểm thử dùng HTTP fixtures cho Chrome và H2 cách ly, không phải nghiệm thu xuất hàng trên MySQL thật. Không tạo/sửa/hủy phiếu hoặc seed/reset DB thật; không khởi động lại backend thật. Cần build/restart backend để nhận sửa FEFO/khóa tồn; **không thay schema và không cần xóa DB**. Chưa commit/push/merge.

## S6-03 — Phiếu soạn hàng từ đơn đã duyệt (frontend + backend) — 23/10/2026

Nhánh `feature/S6-03-frontend-va-backend-tao_phieu_soan_hang`. Đã đọc AGENTS.md và hai skill trong SKILLS. Triển khai theo 5 tiêu chí trong ảnh; giữ shared shell/frame hiện có, lưu nghiệp vụ ở DB. Theo yêu cầu cập nhật của người dùng: chỉ commit local, không tự push/merge develop. Không restart backend hoặc ghi/reset DB thật trong quá trình kiểm thử.

| Tiêu chí / hạng mục | Backend | Frontend | Kết nối / ma trận quyền |
|---|---|---|---|
| Tạo phiếu từ đơn đã duyệt, còn giữ chỗ | Có — kiểm tra trạng thái, hạn giữ chỗ, kho đang hoạt động và phạm vi được gán | Có — chọn kho, tìm/chọn đơn có thể soạn | `/warehouse/picking-batches/eligible-orders` + POST; không đòi thêm ORDER_VIEW/PRODUCT_VIEW |
| Dòng hàng có vị trí, sắp theo tuyến kho | Có — snapshot vị trí mặc định đang dùng, mã/tên SKU, lượng/đơn vị/hệ số; thứ tự khu/kệ có thể cấu hình bằng `pickingSequence`, fallback mã tự nhiên | Có — thẻ theo tuyến, thể hiện đơn gốc/SKU/vị trí/lượng cần; hàng chưa gán vị trí hiện cuối tuyến | Cấu hình tuyến trong Thêm/Sửa vị trí; vị trí/số lượng chốt từ server, thay danh mục không sửa snapshot cũ |
| Gộp nhiều đơn một lượt | Có — cùng kho, tối đa 100 đơn/500 dòng; khóa đơn theo ID + claim DB duy nhất chống trùng đồng thời | Có — chọn nhiều đơn qua các trang, có bộ đếm đơn đã chọn | Mỗi dòng vẫn gắn đơn gốc để không mất phân bổ; đơn chuyển PICKING cùng giao dịch, không xuất/trừ tồn |
| Đánh dấu đã soạn, lưu thực soạn từng dòng | Có — lưu null/0/số lẻ đúng nghĩa, quy đổi theo hệ số đóng băng; DB version + khóa phiếu trả 409 khi cũ | Có — input số, checkbox, Lưu dòng; chống gửi trùng, giữ input khi lỗi và giữ dòng khác đang nhập dở | PUT từng dòng; phản hồi DB cập nhật tiến độ; reload đọc dữ liệu thật từ API, không dùng local/sessionStorage cho nghiệp vụ |
| Soạn thiếu bắt buộc lý do | Có — lượng 0..yêu cầu, tối đa 4 số lẻ; thiếu kể cả 0 bắt buộc lý do, không chỉ validate JS | Có — cảnh báo ngay trên dòng, không tự điền lượng đặt thành thực soạn | API vẫn chặn yêu cầu giả/bỏ qua UI; đánh dấu xong phải có thực soạn |
| Hoàn tất/hủy lượt | Có — hoàn tất khi tất cả dòng đã soạn, bất biến sau hoàn tất; hủy phải lý do, giải phóng claim để tạo lượt mới | Có — chỉ bật hoàn tất sau lưu đủ dòng và không còn thay đổi chưa lưu; xem lịch sử, hủy an toàn | Mọi lỗi kể cả audit rollback trạng thái/dữ liệu/claim; không tự cập nhật thực giao hoặc hoàn tồn |
| Điện thoại | API giữ payload hữu hạn/phân trang, dữ liệu thực soạn lưu lại được khi reload | Có — thẻ theo điểm dừng, input/nút tối thiểu 44px, bố cục 320–1920px, không tràn ngang toàn trang | Dùng `app-navigation.css`, không tạo outer frame riêng; đã xem screenshot mobile/desktop |
| Phân quyền và giới hạn kho | Đọc WAREHOUSE_VIEW hoặc WAREHOUSE_EXPORT; ghi WAREHOUSE_EXPORT; ADMIN luôn có quyền, nghiệp vụ vẫn bắt buộc | Menu/guard và nút theo cùng ma trận; chỉ VIEW thì chỉ xem; grant/revoke trong phiên hiện tại | Role không cấp quyền thay DB; ngoài kho bị 403, không có kho gán không được đọc toàn hệ thống; phản hồi trễ không phục hồi form sau thu hồi |

**Kiểm thử cuối:** Maven `clean test` trong bản sao backend/H2 tạm: **860 tests, 0 failures/errors**, trong đó **17 ca PickingBatchIntegrationTest** qua HTTP/JWT thật (gộp đơn, tuyến tự nhiên/cấu hình/snapshot, thiếu/0, hoàn tất/hủy, version cũ, đồng thời, kho ngừng hoạt động, scope, cấp/thu hồi JWT hiện tại, ADMIN và rollback audit). Node **146/146**; Python routes **11/11**. Chrome trang/script thật + HTTP fixtures S6-03 pass: chọn nhiều trang, tạo trùng, giữ input khi lỗi/409, giữ dòng khác nhập dở, hoàn tất/reload/hủy, read-only, grant/revoke + phản hồi đọc/ghi trễ, XSS, lọc/rỗng/retry và 6 widths. Hồi quy warehouses (kèm lưu/đọc thứ tự tuyến), S5 orders/reservation/import/stocktake/transfers, stock-card/lots/navigation pass; shared layout **168 checks / 28 trang / 6 widths (320–1920px)** pass. `git diff --cached --check` sạch. API_DOCUMENTATION.md và docs/FRONTEND_ROUTES.md đã cập nhật.

**Triển khai / giới hạn:** cần build/restart backend để Hibernate theo cấu hình hiện có tạo ba bảng picking và thêm cột thứ tự cho vị trí; restart frontend server để nhận route Python mới. Kiểm thử dùng H2 tạm trong bản sao backend và Chrome với HTTP fixtures, không phải UAT MySQL thật. Tuyến theo cấu hình/mã khu-kệ, không tuyên bố tối ưu đường ngắn nhất theo tọa độ. Lượt soạn chỉ ghi thực soạn, chưa thay luồng chứng từ xuất/giao hàng.

## S7-08 — Nhắc hạn thanh toán tự động cho Đại lý (Backend) — 23/10/2026

Nguồn tiêu chí: User story S7-08 (*"Là Đại lý, tôi muốn nhận thông báo nhắc hạn thanh toán hóa đơn, để không bị bất ngờ bị khoá bán vì quên deadline"*). Phạm vi thực hiện: **chỉ làm Backend** theo đúng yêu cầu người dùng, tuân thủ nghiêm ngặt `AGENTS.md` và ma trận phân quyền database-backed.

| Tiêu chí / hạng mục | Backend | Frontend | Kết nối / ma trận quyền |
|---|---|---|---|
| Gửi email và thông báo trong app trước hạn 3 ngày | Đã hoàn thành: `PaymentReminderServiceImpl.processPaymentReminders()` phát hiện hóa đơn có `dueDate` cách ngày quét đúng 3 ngày (hoặc 1–3 ngày nếu chưa từng nhắc loại này), tạo in-app `Notification` qua `NotificationService.sendInAppNotification()` với `NotificationType.PAYMENT_DUE_BEFORE_3_DAYS`, đồng thời gửi email HTML qua `PaymentReminderMailService` (`SimpleMailMessage`); log kết quả vào `PaymentReminderLog` | Chưa triển khai (theo yêu cầu "chỉ làm BE") | Hóa đơn phải ở trạng thái `ISSUED`/`ADJUSTED` và `isPaid = false`; email lấy từ `Dealer.email`; log trả `emailError` nếu gửi thất bại mà không làm crash luồng xử lý hóa đơn khác |
| Gửi email và thông báo trong app khi quá hạn | Đã hoàn thành: Phát hiện `dueDate < targetDate`, tạo in-app `Notification` với `NotificationType.PAYMENT_OVERDUE`, gửi email cảnh báo OVERDUE với số ngày quá hạn; log kết quả vào `PaymentReminderLog` | Chưa triển khai (theo yêu cầu "chỉ làm BE") | Cùng điều kiện lọc với trước hạn; nội dung email cảnh báo nguy cơ khoá đặt hàng tự động theo chính sách rủi ro công nợ |
| Tác vụ định kỳ chạy mỗi ngày một lần | Đã hoàn thành: `PaymentReminderScheduler` dùng `@Scheduled(cron = "${app.payment-reminder.cron:0 0 7 * * ?}")` — mặc định 7:00 sáng mỗi ngày; có thể tắt bằng `app.payment-reminder.scheduler.enabled=false`; log summary: tổng quét, trước hạn 3 ngày, quá hạn, bỏ qua, lỗi | Chưa triển khai (theo yêu cầu "chỉ làm BE") | `@ConditionalOnProperty` cho phép tắt scheduler trong môi trường test mà không cần mock; API trigger thủ công `POST /api/finance/payment-reminders/trigger?targetDate=YYYY-MM-DD` cho phép chạy bù |
| Không gửi trùng nhiều lần cho cùng hóa đơn trong cùng ngày | Đã hoàn thành: `PaymentReminderLog` có ràng buộc `UNIQUE(invoice_id, reminder_date)`; trước khi gửi kiểm tra `PaymentReminderLogRepository.existsByInvoiceIdAndReminderDate()`; nếu luồng song song cùng thắng thì `DataIntegrityViolationException` được bắt an toàn, hóa đơn bị skip thay vì crash | Chưa triển khai (theo yêu cầu "chỉ làm BE") | Ràng buộc DB là lớp bảo vệ cuối chống gửi trùng; không cần distributed lock cho tải thực tế |
| Đại lý xem thông báo trong ứng dụng | Đã hoàn thành: `GET /api/portal/notifications` (phân trang, lọc `unreadOnly`), `GET /api/portal/notifications/unread-count`, `PUT /api/portal/notifications/{id}/read`, `PUT /api/portal/notifications/read-all`; Entity `Notification` với các trường `title`, `message`, `type`, `referenceType`, `referenceId`, `referenceCode`, `isRead`, `readAt`, `createdAt` | Chưa triển khai (theo yêu cầu "chỉ làm BE") | Bắt buộc quyền `NOTIFICATION_VIEW` hoặc `ROLE_ADMIN`; đại lý chỉ xem thông báo của chính mình; ADMIN có thể xem của bất kỳ đại lý nào qua `?dealerId=` |
| Thêm `dueDate` vào hóa đơn | Đã hoàn thành: Thêm trường `dueDate` (`LocalDate`) vào `Invoice`; tự tính mặc định = `invoiceDate + maxDebtDays` (đại lý, fallback 30 ngày) trong `@PrePersist`; thêm `getEffectiveDueDate()` trả giá trị an toàn khi `dueDate = null` (trường hợp mock/test); thêm `dueDate` vào `CreateInvoiceRequest` (optional) và `InvoiceResponse` | Chưa triển khai (theo yêu cầu "chỉ làm BE") | `InvoiceServiceImpl` tự tính `dueDate` khi tạo hóa đơn nếu request không truyền; không cần migration — `@PrePersist` xử lý cả hóa đơn mới |
| Tra cứu nhật ký gửi nhắc | Đã hoàn thành: `GET /api/finance/payment-reminders/logs?dealerId=&invoiceId=&reminderType=&page=&size=` — lọc đa tiêu chí, phân trang server-side giảm dần theo `sentAt`; `PaymentReminderLogResponse` gồm: `invoiceCode`, `dealerCode`, `reminderType`, `daysCount`, `amount`, `emailSent`, `emailError`, `inAppNotificationSent`, `sentAt` | Chưa triển khai (theo yêu cầu "chỉ làm BE") | Bắt buộc quyền `FINANCE_VIEW_DEBT` hoặc `ROLE_ADMIN` để xem nhật ký; trigger nhắc thủ công bắt buộc `FINANCE_MANAGE` hoặc `ROLE_ADMIN` |
| Ma trận phân quyền DB và thu hồi động trên JWT hiện tại | Đã hoàn thành: Khai báo quyền mới `NOTIFICATION_VIEW` trong `Permission` enum; mặc định cấp cho `ROLE_DEALER`, `ROLE_ACCOUNTANT`, `ROLE_SALES_MANAGER`; tất cả endpoint dùng `@PreAuthorize` với quyền cụ thể, không fallback role; thu hồi quyền trên JWT hiện tại trả 403 trên request kế tiếp | Chưa triển khai (theo yêu cầu "chỉ làm BE") | `ROLE_ADMIN` luôn toàn quyền; ma trận phân quyền database-backed là nguồn sự thật duy nhất |

Kiểm chứng: **28 test backend mới cho S7-08**:
1. `PaymentReminderServiceTest` (6 tests): Gửi nhắc trước hạn 3 ngày (email + in-app), gửi nhắc quá hạn, chặn gửi trùng cùng ngày, bỏ qua xa hạn (>3 ngày), bỏ qua đã thanh toán/đã hủy, xử lý graceful khi email thất bại (vẫn tạo in-app + ghi lỗi vào log).
2. `NotificationServiceTest` (6 tests): Tạo thông báo hợp lệ, validation đại lý/tiêu đề/nội dung rỗng, lấy danh sách có phân trang, lọc chưa đọc, đánh dấu đã đọc (bao gồm chặn sửa của đại lý khác), đánh dấu tất cả đã đọc.
3. `DealerNotificationControllerTest` (5 tests): `GET /notifications` trả 200, `GET /unread-count` trả số đúng, `PUT /{id}/read` trả 200, `PUT /read-all` trả số đã cập nhật, `GET /notifications` chặn 401 khi chưa đăng nhập.
4. `PaymentReminderControllerTest` (4 tests): `POST /trigger` trả summary, `POST /trigger?targetDate=` chạy ngày chỉ định, `GET /logs` trả trang phân trang.
5. `PaymentReminderSchedulerTest` (2 tests): Scheduler gọi service đúng 1 lần, không throw exception khi service lỗi.
6. `PaymentReminderSecurityTest` (8 tests): Chặn 401 khi chưa xác thực, 403 khi thiếu `NOTIFICATION_VIEW` cho portal notifications, 200 khi có `NOTIFICATION_VIEW`, 200 với `ROLE_ADMIN`, 403 khi `PUT /{id}/read` thiếu quyền, 403 trigger thiếu `FINANCE_MANAGE`, 200 trigger với `FINANCE_MANAGE`, 403/200 cho GET logs với `FINANCE_VIEW_DEBT`.
7. `NotificationServiceTest` (3 tests bổ sung phần tích hợp): Đánh dấu đã đọc — thông báo đã đọc không cập nhật lại `readAt`; đánh dấu đã đọc — throw 403 khi đại lý khác; `markAllAsRead` — gọi repository đúng.

---

## S7-01 — Phát hành hoá đơn bán hàng từ phiếu đã giao (Backend) — 22/10/2026

Nhánh: `S7-01/phat-hanh-hoa-don-ban-hang-tu-phieu-da-giao-BE`. Nguồn tiêu chí: User story S7-01 (*"Là Kế toán công nợ, tôi muốn phát hành hoá đơn bán hàng từ phiếu đã giao, để xuất hoá đơn đúng số hàng thực giao thay vì số hàng đặt"*). Phạm vi thực hiện: **chỉ làm Backend** theo đúng yêu cầu người dùng, tuân thủ nghiêm ngặt `AGENTS.md` và ma trận phân quyền database-backed.

| Tiêu chí / hạng mục | Backend | Frontend | Kết nối / ma trận quyền |
|---|---|---|---|
| Hoá đơn sinh từ số lượng thực giao, không phải số lượng đặt | Đã hoàn thành: `POST /api/invoices`, `POST /api/invoices/preview`. Dòng hóa đơn chốt `invoicedQuantity = deliveredQuantity` (lấy từ phiếu giao DELIVERED, S5-01), bỏ qua mặt hàng không thực giao (`deliveredQuantity = 0`); tính tiền = `deliveredQuantity * unitPrice - discountAmount` | Chưa triển khai (theo yêu cầu "chỉ làm BE") | Bắt buộc quyền `INVOICE_CREATE` hoặc `ROLE_ADMIN`; tính toán hoàn toàn phía server từ DB, không tin client |
| Số hoá đơn tự sinh theo dãy liên tục, không trùng và không nhảy cóc | Đã hoàn thành: `InvoiceSequenceService` dùng cơ chế khóa bi quan `PESSIMISTIC_WRITE` trên bảng `invoice_sequences` trong cùng transaction phát hành hóa đơn; số hóa đơn định dạng 8 chữ số `00000001` kèm mã `HD-00000001`; nếu lỗi/rollback bộ đếm tự rollback theo, đảm bảo không có khoảng trống (không nhảy cóc) và không trùng lặp | Chưa triển khai (theo yêu cầu "chỉ làm BE") | Bắt buộc quyền `INVOICE_CREATE` hoặc `ROLE_ADMIN`; chạy an toàn tuyệt đối dưới tải đồng thời |
| Gộp nhiều phiếu giao của cùng đại lý trong kỳ thành một hoá đơn | Đã hoàn thành: API hỗ trợ gộp danh sách phiếu giao `orderIds` thuộc cùng đại lý `dealerId`; chặn gộp phiếu giao khác đại lý (400), chặn phiếu chưa hoàn tất giao `DELIVERED` (400), chặn xuất hóa đơn trùng lặp (ràng buộc unique `order_id` trên `invoice_orders`); API `GET /eligible-orders` lọc phiếu giao theo kỳ (`startDate`, `endDate`) và `GET /dealers-with-eligible-orders` | Chưa triển khai (theo yêu cầu "chỉ làm BE") | Bắt buộc quyền `INVOICE_VIEW` hoặc `ROLE_ADMIN` để tra cứu; `INVOICE_CREATE` hoặc `ROLE_ADMIN` để xem trước/phát hành |
| Hoá đơn đã phát hành không sửa và không xoá, chỉ lập chứng từ điều chỉnh | Đã hoàn thành: Chặn `PUT /api/invoices/{id}` và `DELETE /api/invoices/{id}` bằng 400 Bad Request; hỗ trợ lập chứng từ điều chỉnh `POST /api/invoices/{id}/adjustments` với 4 loại: `INCREASE` (tăng tiền/SL), `DECREASE` (giảm tiền/SL/chiết khấu), `INFORMATION` (điều chỉnh thông tin), `CANCEL` (hủy hóa đơn); bắt buộc nhập lý do điều chỉnh `adjustmentReason`; tự động chuyển trạng thái hóa đơn sang `ADJUSTED` hoặc `CANCELLED`, cập nhật `adjustedTotalAmount` và ghi nhật ký AuditLog | Chưa triển khai (theo yêu cầu "chỉ làm BE") | Lập điều chỉnh bắt buộc `INVOICE_ADJUST` hoặc `ROLE_ADMIN`; tra cứu điều chỉnh bắt buộc `INVOICE_VIEW` hoặc `ROLE_ADMIN` |
| In hoặc xuất PDF được | Đã hoàn thành: `GET /api/invoices/{id}/pdf` xuất file PDF hóa đơn chuẩn A4 theo thiết kế doanh nghiệp (OpenPDF, tiếng Việt); `GET /api/invoices/{id}/print-preview` trả HTML bản in trực tiếp trên trình duyệt; `GET /api/invoices/{id}/adjustments/{adjustmentId}/pdf` xuất PDF chứng từ điều chỉnh | Chưa triển khai (theo yêu cầu "chỉ làm BE") | Bắt buộc quyền `INVOICE_VIEW` hoặc `ROLE_ADMIN` |
| Ma trận phân quyền DB và thu hồi động trên JWT hiện tại | Đã hoàn thành: Khai báo 3 quyền mới trong ma trận DB: `INVOICE_VIEW`, `INVOICE_CREATE`, `INVOICE_ADJUST`; mặc định cấp cho `ROLE_ACCOUNTANT` (Kế toán công nợ); `ROLE_ADMIN` luôn toàn quyền; thu hồi quyền trên phiên JWT hiện tại lập tức có hiệu lực và trả về 403 Forbidden trên API request kế tiếp; không dùng role-based fallback | Chưa triển khai (theo yêu cầu "chỉ làm BE") | Ma trận phân quyền database-backed là nguồn sự thật duy nhất |

Kiểm chứng: **30 test backend mới qua 100%**:
1. `InvoiceServiceTest` (12 tests): Nghiệp vụ hóa đơn sinh từ số lượng thực giao, số tự sinh liên tục không trùng và không nhảy cóc, gộp nhiều phiếu giao cùng đại lý, chặn khác đại lý / chưa giao / xuất lặp, chặn sửa/xóa hóa đơn đã phát hành, lập chứng từ điều chỉnh tăng/giảm/hủy kèm bắt buộc lý do, cập nhật số tiền sau điều chỉnh, xem trước và tra cứu phiếu giao hợp lệ.
2. `InvoiceControllerTest` (8 tests): REST Endpoints, validation DTO, HTTP status, phân trang, serialize JSON, tải file PDF (`application/pdf`) và xem trước bản in HTML.
3. `InvoiceSecurityTest` (8 tests): Phân quyền ma trận, chặn 401 khi chưa đăng nhập, chặn 403 khi thiếu quyền `INVOICE_VIEW`/`INVOICE_CREATE`/`INVOICE_ADJUST` (chặn cả khi có vai trò Kế toán nếu bị thu hồi quyền - không fallback role), cho phép khi có quyền hoặc vai trò admin.
4. `InvoiceIntegrationTest` (2 tests): Kiểm thử tích hợp toàn diện trên HTTP/JWT/JPA/H2 MySQL mode: toàn trình tạo đơn, giao hàng thiếu số lượng, xem trước và phát hành hóa đơn theo lượng thực giao, chặn sửa/xóa, lập chứng từ điều chỉnh, tải PDF hợp lệ (`%PDF-`), và thu hồi quyền `INVOICE_CREATE` động trên token JWT hiện tại lập tức trả 403 Forbidden.
Đã cập nhật hợp đồng tại `backend/API_DOCUMENTATION.md`.

---


## S5 — Sửa lỗi tích hợp trên `fix/sprint-5` (22/10/2026)

Đã đối chiếu các lỗi xác nhận trong audit Sprint 5; các mục bên dưới là trạng thái sau sửa, các phần cũ giữ để tham khảo lịch sử. Đã đọc AGENTS.md và hai skill trong SKILLS. Không commit/push/merge, không ghi DB thật, không restart backend thật.

| Hạng mục S5 | Backend | Frontend | Kết nối / quyền / kiểm chứng |
|---|---|---|---|
| S5-01 Theo dõi đơn và thực giao | Bổ sung kiểm tra dòng thuộc đơn, ID không trùng, thực giao 0..số đặt, 4 số lẻ, độ dài ghi chú; trả `canUpdateDelivery` theo ma trận + kho + trạng thái | Form thực giao tại chi tiết `/orders`; ngày, số lượng từng dòng, ghi chú; chuyển Đã giao phải qua form; không mặc định số đặt thành số giao. Cổng đại lý hiển thị đúng nhãn/lọc Đang soạn, Đã xuất, Đã đóng, Hết hạn | Nối `PUT /orders/{id}/delivery`; quyền `WAREHOUSE_EXPORT`, kho được gán, ADMIN vẫn phải tuân thủ nghiệp vụ. Chrome kiểm tra lỗi giữ input, chống bấm kép, grant/revoke và phản hồi trễ; H2 kiểm tra thiếu/đủ/scope/403 |
| S5-02 Đặt lại đơn | Dùng API chuyên biệt hiện có để chọn mặt hàng và kiểm tra lại bảng giá khi ghi | Thay đọc catalog + tạo đơn chung bằng preview/reorder của đơn gốc; chọn một phần/toàn bộ, số lượng lẻ; ngày mặc định từ server; hiển thị tạm tính trước CK, cảnh báo server | `ORDER_VIEW` + `ORDER_CREATE`, không đòi thêm `PRODUCT_VIEW`; đóng/xóa form khi đổi quyền, không khôi phục dữ liệu từ phản hồi cũ, chống gửi trùng. Node + Chrome pass |
| S5-03 Kho/vị trí | Giữ nguyên | Giữ nguyên | Chrome hồi quy kho/vị trí + quyền pass |
| S5-04 Phiếu kho / điều chỉnh | Đường điều chỉnh trực tiếp kiểm tra `WAREHOUSE_ADJUST` và phạm vi kho; chặn tồn âm/dưới giữ chỗ | Giữ luồng nhập/nháp/xác nhận/điều chỉnh hiện có | H2 kiểm tra kho ngoài phạm vi bị 403 và thu hồi quyền trên JWT hiện tại; Chrome phiếu nhập pass |
| S5-05 Tồn thực / giữ chỗ / khả dụng | Bảo vệ tính nhất quán ba lượng khi có xuất/điều chỉnh độc lập với đơn | Giữ trang tồn kho hiện có | Chrome hồi quy lọc/phân trang/ba lượng và quyền pass |
| S5-06 Giữ chỗ khi duyệt | Xuất trực tiếp chỉ được lấy tồn khả dụng; điều chỉnh không xuống dưới giữ chỗ, kể cả ADMIN. Kiểm tra dưới khóa dòng tồn | Gia cố toast tránh chuyển động ngang; giữ hiển thị số giữ chỗ/hết hạn theo server | H2: duyệt cạnh tranh xuất kho, rollback phiếu nhiều dòng, biên khả dụng, grant/revoke pass; Chrome luồng giữ chỗ pass. Phép đo responsive chờ hai frame để Chrome áp viewport/media query, không che overflow toàn trang |
| S5-07 Chuyển kho | Giữ nguyên | Giữ nguyên | Chrome hồi quy luồng chuyển/nhận/chênh lệch + quyền pass |
| S5-08 Kiểm kê | Giữ nguyên | Giữ nguyên | Chrome hồi quy nháp DB/chốt/lý do + ma trận `WAREHOUSE_ADJUST` pass |
| S5-09 Tồn tối thiểu | Giữ nguyên | Giữ nguyên | Chrome hồi quy cấu hình/cảnh báo/dashboard + quyền pass. Sửa predicate test chờ bảng vẽ lại để không dereference ô chưa tồn tại |

**Kiểm thử:** Maven `clean test` trong bản sao backend riêng, H2 tạm: **813 tests, 0 failures/errors**; Node **145/145**; Python routes **10/10**. Chrome chạy trang/script thật với HTTP fixtures: S5 orders mới, reservation, warehouses, import, stock-ledger, transfers, stocktake, minimums đều pass; hồi quy thêm stock-card, lots, units-read. Bố cục chung **162 checks / 27 trang / 6 widths (320–1920px)** pass. `git diff --check` sạch. Cập nhật API_DOCUMENTATION.md và docs/FRONTEND_ROUTES.md.

**Giới hạn:** chưa chạy các thao tác ghi trên MySQL/DB thật của người dùng; Chrome dùng API giả lập kiểm soát, không thay cho UAT dữ liệu thật. Phần giao thiếu chỉ ghi nhận thực giao, không tự nhập trả tồn. Cần build/restart backend để áp dụng Java mới; chưa thực hiện trong lượt này. Không tự migration/đối soát dữ liệu lịch sử.

## S6-02 — Frontend thẻ kho, kết nối API và sửa số dư (22/10/2026)

**Trạng thái mới nhất; phần “chỉ backend” phía dưới là lịch sử.** Nhánh `feature/S6-02-xem_the_kho_cua_mot_san_pham`. Đã đọc AGENTS.md và hai skill SKILLS; không tự commit/push/merge. Không ghi vào DB thật, không khởi động lại backend thật.

| Tiêu chí trong ảnh / hạng mục | Backend | Frontend | Đã nối đúng | Quyền / kết quả |
|---|:---:|:---:|:---:|---|
| Toàn bộ nhập, xuất, chuyển, điều chỉnh theo thời gian | Có | Có | Có | Menu Kho hàng → Thẻ kho sản phẩm (`/warehouse/stock-card`); WAREHOUSE_VIEW hoặc ADMIN. Bảng theo thời gian server, biến động có dấu/đơn vị đóng băng. Nối cả đơn đã xuất, kiểm kê đã chốt, nhập trả chuyến giao và chuyển kho nhận/hủy; không lấy nháp hoặc đếm giữ chỗ thành xuất. |
| Tồn sau phát sinh, tồn trước trang và phân trang | Có | Có | Có | Tính toàn bộ lịch sử trước khi lọc thời gian/phân trang. Đối chiếu tồn đầu lịch sử từ tồn vật lý hiện tại trong scope, không tạo phiếu giả. Sửa openingBalance trang đầu/lọc ngày/page bị chặn về trang cuối/khoảng không phát sinh. Sửa phiếu điều chỉnh mới lưu chênh lệch có dấu thay vì lượng đích. |
| Liên kết chứng từ gốc từng dòng | Có | Có | Có | Mã chứng từ là liên kết mở bản xem chỉ đọc các phát sinh của SKU trong chứng từ gốc; permalink giữ SKU/kho/ngày/page/size/source. API kiểm lại loại/ID chứng từ, đúng SKU và phạm vi kho; không đọc toàn bộ giá bán/khách hàng hay cho sửa/hủy. Có trạng thái tải/lỗi; đóng hộp thoại bỏ phản hồi đến muộn. |
| Lọc theo kho và khoảng thời gian | Có | Có | Có | Bộ chọn từ API riêng, không buộc PRODUCT_VIEW. Chọn SKU, kho, từ/đến ngày bao gồm cả hai ngày; from > to trả 400; kho ngoài scope trả 403. Bộ lọc lưu trong URL để tải lại, không lưu dữ liệu nghiệp vụ trong browser storage. |
| Ma trận quyền và bố cục chung | Có | Có | Có | Cấp/thu hồi trên JWT cũ, không role fallback, ADMIN toàn quyền; mất quyền đóng dialog/xóa dữ liệu/ẩn menu, không phục hồi phản hồi cũ. Tồn kho có link chọn sẵn SKU và kho. Dùng shell/content frame chung; bảng cuộn nội bộ trên mobile. |

Kiểm thử cuối: **806/806 test backend qua** (`mvn clean test`), gồm 4 test tích hợp S6-02 mới qua HTTP/JWT/JPA/H2: options/grant-revoke, filter/page/seed/source/scope, kiểm kê + đơn xuất một lần + chuyển đa kho, điều chỉnh âm lưu đúng dấu; service có thêm test nhập trả chuyến và số dư qua lọc ngày. **144 test JavaScript và 10 test định tuyến qua**. Chrome S6-02 qua bộ chọn/lọc ngày bao gồm cả ngày, phân trang server/tồn trước trang, tải lại/permalink chứng từ, lỗi không giữ số cũ, XSS, revoke khi đang tải source rồi grant lại, 6 kích thước. Hồi quy sổ tồn và điều hướng click/Back/Forward/Reload/mobile/reduced-motion qua, sidebar giữ scroll 552 → 552. Bố cục chung **162 kiểm tra trên 27 trang × 6 kích thước** qua; đã xem ảnh desktop và dialog mobile. `git diff --check` sạch. Backend chạy trong bản sao tạm với H2; Chrome dùng HTTP fixtures, không phải ảnh minh chứng dữ liệu thật. Hai skill của repo định hướng dùng khung chung và để tính toán/quyền/dữ liệu nghiệp vụ ở server, không tự dựng bộ số tồn trong JS.

Giới hạn: chưa nghiệm thu MySQL thật. Dữ liệu seed/migration/chỉnh trực tiếp DB chỉ đối chiếu tồn đầu, không tự tạo lịch sử. Phiếu điều chỉnh tuyệt đối **cũ** chưa lưu số biến động có dấu cần đối soát/migration riêng; không đoán dấu hoặc tự sửa dữ liệu lịch sử. Bản xem chứng từ chủ ý chỉ hiển thị phần liên quan SKU/scope (không phải toàn bộ chứng từ bán hàng). Không đổi schema. Cần build/chạy lại backend và server frontend để nhận API/route mới.

## Ghi chú triển khai backend S6-02 trước đây (lịch sử)

Cập nhật **22/10/2026**, hoàn thành tính năng **S6-02 — Thẻ kho (Stock Card)** (backend); không merge hoặc lấy code từ nhánh khác.

## S6-02 — Thẻ kho sản phẩm (Stock Card backend) — 22/10/2026

Nguồn tiêu chí: User story S6-02 (*"Là Nhân viên kho, tôi muốn xem thẻ kho của một sản phẩm, để truy nguyên được từ phiếu nào làm số tồn bị lệch."*). Phạm vi chỉ làm **backend** theo yêu cầu người dùng, tuân thủ `AGENTS.md` và ma trận phân quyền.

| Tiêu chí / hạng mục | Backend | Frontend | Kết nối / ma trận quyền |
|---|---|---|---|
| Danh sách toàn bộ phát sinh theo thứ tự thời gian | API `GET /api/warehouse/stock-card/{productId}` hợp nhất tất cả các loại phát sinh: nhập kho (`IMPORT`), xuất kho (`EXPORT`), điều chỉnh kho (`ADJUSTMENT`), xuất chuyển kho (`TRANSFER_DISPATCH`), nhận chuyển kho (`TRANSFER_RECEIVE`), hủy chuyển kho hoàn tồn (`TRANSFER_CANCEL`). Dữ liệu được sắp xếp thứ tự thời gian tăng dần (`eventTime` ASC, sau đó `sourceId` ASC) | Chưa làm (giữ nguyên theo yêu cầu "chỉ làm backend") | Quyền `WAREHOUSE_VIEW` hoặc `ROLE_ADMIN`; kiểm tra phân quyền trực tiếp qua ma trận DB, không fallback role. `ROLE_ADMIN` luôn được phép |
| Tồn sau phát sinh (running balance) và liên kết chứng từ gốc | Mỗi dòng trả về `baseQuantityChange` (số lượng có dấu theo đơn vị cơ sở), `runningBaseBalance` (tồn tích lũy sau phát sinh theo đơn vị cơ sở), `baseUnit`, `displayQuantity` & `displayUnit` (số lượng và đơn vị hiển thị trên chứng từ), cùng liên kết chứng từ gốc qua `sourceType` (`VOUCHER`/`TRANSFER`), `sourceId` và `sourceCode` | Chưa làm (giữ nguyên theo yêu cầu "chỉ làm backend") | Tính toán chuẩn xác ở tầng service; hỗ trợ chuyển kho đa trạng thái (IN_TRANSIT, COMPLETED, CANCELLED) phản ánh đúng từng mốc xuất và nhận |
| Lọc theo kho và khoảng thời gian | Hỗ trợ tham số truy vấn `warehouseId`, `fromDate` (ISO 8601), `toDate` (ISO 8601). Kiểm tra phạm vi kho phân công: nếu người dùng truy cập kho ngoài phạm vi được phân công sẽ bị chặn `403 Forbidden` | Chưa làm (giữ nguyên theo yêu cầu "chỉ làm backend") | Phạm vi kho lấy từ cấu hình phân công kho trong DB (`StockLedgerService.visibleWarehousesFor("WAREHOUSE_VIEW")`); tài khoản không được phân công kho nào sẽ trả thẻ kho rỗng |
| Phân trang và tồn đầu kỳ (Opening balance) | Hỗ trợ phân trang server-side (`page`, `size` tối đa 500). Đánh số thứ tự liên tục `rowNumber`. Với `page > 0`, trả về `openingBalance` (số dư tồn kho trước dòng đầu tiên của trang hiện tại) để frontend duy trì tính liên tục của số dư | Chưa làm (giữ nguyên theo yêu cầu "chỉ làm backend") | Kiểm tra tính hợp lệ tham số phân trang (`page >= 0`, `size` từ 1 đến 500); `productId` hợp lệ; từ chối tham số sai bằng `400 Bad Request` |

Kiểm chứng cuối: **649 test backend qua**, bao gồm 11 test service `StockCardServiceTest` và 5 test controller `StockCardControllerTest`. Kiểm chứng đầy đủ:
- Thứ tự thời gian và tồn lũy kế running balance cho nhập, xuất, điều chỉnh.
- Chuyển kho nội bộ sinh đúng sự kiện DISPATCH (giảm kho đi) và RECEIVE (tăng kho đến khi hoàn thành).
- Phiếu chuyển kho bị hủy (CANCELLED) sinh sự kiện DISPATCH và CANCEL (hoàn tồn lại kho đi kèm lý do hủy).
- Phiếu chuyển kho đang chuyển (IN_TRANSIT) chỉ sinh sự kiện DISPATCH.
- Lọc theo khoảng thời gian (`fromDate`, `toDate`) chính xác.
- Phân trang server-side và tính `openingBalance` chuẩn xác từ trang 1 trở đi.
- Thu hồi quyền `WAREHOUSE_VIEW` trên token JWT lập tức trả `403 Forbidden` mà không fallback role.
- Cố tình xem kho ngoài phạm vi phân công lập tức trả `403 Forbidden`.
- Người dùng chưa đăng nhập trả `401 Unauthorized`.
- `ROLE_ADMIN` luôn có toàn quyền xem thẻ kho của bất kỳ sản phẩm và kho nào.
Đã cập nhật hợp đồng tại `backend/API_DOCUMENTATION.md`. Không có bất kỳ thay đổi nào ở frontend hay schema DB.


# Trạng thái phát triển — QuanLyBanHang

## S6-01 — Frontend lô & hạn sử dụng, nối API và sửa lỗi backend (22/10/2026)

**Cập nhật mới nhất cho S6-01; mục “chỉ backend” bên dưới là lịch sử.** Nhánh `feature/S6-01-frontend-theo_doi_lo_va_han_su_dung`. Đã đọc AGENTS.md và hai skill trong SKILLS. Không tự commit/push/merge.

| Yêu cầu trong ảnh | Backend | Frontend | Đã kết nối | Quyền / hành vi |
|---|:---:|:---:|:---:|---|
| Bật/tắt quản lý lô theo từng SKU | Có | Có | Có | Tab Cấu hình theo SKU, PATCH `/products/{id}/manage-lot`; PRODUCT_EDIT hoặc WAREHOUSE_MANAGE. Ngưỡng lưu DB. Không tắt khi còn tồn lô dương; sửa thông tin sản phẩm không gửi manageLot phải giữ cấu hình cũ. |
| Số lô, NSX, HSD, số lượng còn lại | Có | Có | Có | Menu Kho hàng → Lô & hạn sử dụng (`/warehouse/lots`); VIEW hoặc MANAGE. API chỉ trả lô trong phạm vi kho được phân công; kho ngoài phạm vi trả 403. Lọc kho/SKU/từ khóa/trạng thái, phân trang hiển thị. Bổ sung ô NSX trên phiếu nhập, giữ ngày qua lưu nháp/tải lại. Ngày chưa khai báo ở dữ liệu cũ hiển thị rõ, không tự bịa ngày. |
| Cảnh báo sắp hết hạn với ngưỡng khai báo được | Có | Có | Có | Ngưỡng từng SKU từ DB; số đếm/cờ quá hạn và ngày đối chiếu lấy từ server, không tự tính theo đồng hồ trình duyệt. Ngưỡng xem nhanh không sửa cấu hình. Cảnh báo chỉ đếm lô còn tồn dương. |
| Chặn xuất quá hạn; xác nhận ghi đè kèm lý do | Có | Có | Có | EXPORT để xuất; thêm APPROVE hoặc MANAGE để ghi đè. Checkbox xác nhận và lý do bắt buộc, tối đa 255 ký tự; ADMIN cũng phải nhập. Hạn dùng đọc từ lô DB, bỏ ngày client giả mạo. Lô thiếu HSD không xuất. Phiếu/tồn/lô/audit cùng transaction. |
| Ma trận và bố cục chung | Có | Có | Có | Không role fallback, ADMIN luôn được phép; cấp/thu hồi trên phiên cũ; giao diện đóng dialog, xóa dữ liệu cũ và ẩn hành động khi mất quyền. Dùng app shell/content frame chung, bảng tự cuộn; không tạo shell riêng. |

Sửa backend trong phạm vi: bổ sung `/warehouse/lots/options` trả kho/SKU, trạng thái, giới hạn ngưỡng và khả năng thao tác; chặn đọc lô xuyên phạm vi; chặn giả ngày HSD; kiểm lô cho SKU legacy tắt quản lý nhưng còn tồn; tên hành động ghi đè đổi thành `EXPIRED_LOT_OVERRIDE` vừa cột audit 20 ký tự, entity ID dùng ID kho/SKU + số lô để không vượt 100 ký tự. Không thay schema DB.

Kiểm thử cuối: `mvn clean test` **785/785 qua**, gồm 11 test tích hợp xuất lô (5 test S6-01 mới: scope/options/grant-revoke, giả HSD/ghi đè/audit, biên ngày/ngưỡng, toggle/giữ cấu hình qua sửa sản phẩm, SKU legacy/thiếu HSD). **137 test Node, 10 test router qua**. Chrome S6-01 qua đọc/cấu hình/lưu lỗi/ghi đè/quyền/XSS và 6 kích thước; hồi quy nhập kho (bao gồm NSX lưu/tải lại), kiểm kê, sổ tồn và điều hướng qua. Bố cục chung **156 kiểm tra trên 26 trang × 6 kích thước** qua; một lượt chạy thiếu bộ nhớ máy đã được chạy lại thành công sau khi backend tests kết thúc. `git diff --check` sạch. Hai skill của repo định hướng dùng shell chung và kiểm quyền/dữ liệu server, không tạo bố cục hay bộ quy tắc nghiệp vụ riêng trong JS.

Giới hạn: test backend thực hiện trong bản sao tạm, H2 in-memory; Chrome dùng HTTP fixtures rõ ràng, không phải dữ liệu nghiệp vụ thật. Chưa nghiệm thu S6-01 trên MySQL thật. Không tạo/sửa/xóa đơn, phiếu hoặc ma trận trên DB thật, không restart backend thật. Muốn chạy bản mới cần build/khởi động lại backend và server frontend để nạp API/route mới.

## S5-06 — Đồng bộ develop và hoàn thiện giữ chỗ tồn kho (2026-10-22)

**Đây là trạng thái nghiệm thu mới; các mục phía dưới là lịch sử.** Nhánh làm việc: `feature/S5-06-chuc-nang-giu-cho-ton-kho(frontend)`. Đã đồng bộ `origin/develop` tại `43b842e` (commit đồng bộ hiện có `91bdcc9`). Không gộp vào develop; phần sửa/kiểm thử bên dưới chưa được trợ lý commit hoặc push.

| Yêu cầu / luồng | Backend | Frontend | Kết nối đúng | Kiểm chứng / quyền |
|---|:---:|:---:|:---:|---|
| Đơn chờ duyệt chưa giữ; được duyệt giữ đúng lượng theo đơn vị cơ sở | ✅ | ✅ | ✅ | Khóa dòng đơn trước, tồn theo SKU tăng dần; `RECORDED` tăng reserved, không trừ vật lý. API `ORDER_APPROVE`; trang duyệt cần thêm `ORDER_VIEW`. Không role fallback; ADMIN luôn được phép. |
| Không bán lượng đã giữ cho đơn khác | ✅ | ✅ | ✅ | Hai đơn cạnh tranh không cùng vượt tồn; tạo đơn đọc `/orders/check-inventory`, không cộng từ danh sách đơn có thể thiếu/trễ. Chỉ cần `ORDER_CREATE`, không buộc thêm `ORDER_VIEW`. |
| Hủy nhả đúng lượng giữ một lần | ✅ | ✅ | ✅ | `ORDER_CANCEL` + phạm vi kho/đại lý; có lý do; đã xuất không hủy. Hủy lặp/hủy cùng lúc hết hạn không làm mất phần giữ của đơn khác. |
| Tự nhả khi quá hạn | ✅ | ✅ | ✅ | Scheduler mặc định mỗi 60 giây, từng đơn một giao dịch mới, đọc lại dưới khóa; `EXPIRED`. UI đọc cờ server; quá hạn nhưng server chưa nhả vẫn ghi chờ hệ thống, không tự sửa tồn bằng giờ trình duyệt. |
| Giao dịch nguyên tử, lỗi rollback | ✅ | ✅ | ✅ | Cùng transaction cho đơn + tồn + lịch sử + audit; lỗi thiếu SKU, audit hoặc hạn giữ không hợp lệ không để lại giữ chỗ/trạng thái nửa chừng. UI giữ ghi chú khi API lỗi, không báo duyệt thành công. |
| Xuất kho không nhả/lấy phần giữ của đơn khác | ✅ | ✅ | ✅ | Khóa cùng dòng đơn; xuất giảm vật lý và phần giữ của đơn; scheduler sau xuất không nhả lần nữa. Tracking trả cờ và thời điểm giữ/hết hạn. |
| Ma trận quyền thay đổi trên phiên đang dùng | ✅ | ✅ | ✅ | JWT thật của non-admin: cấp quyền hoạt động, thu hồi trả 403, không cần đăng nhập lại; UI đóng/ẩn/vô hiệu hóa hành động và bỏ phản hồi chi tiết cũ. Quyền không vượt phạm vi kho. |
| Tương thích sổ tồn/kiểm kê S5-08 sau đồng bộ | ✅ | ✅ | ✅ | Giữ chỗ qua JPA tăng phiên bản tồn; kiểm kê không đè giữ chỗ. Sửa thêm việc refresh số đếm sau khi chờ khóa phiếu, tránh chốt bằng số cũ khi lưu số đếm đồng thời. |
| Sửa lỗi rollback-only khi mở Đơn vị tính (yêu cầu bổ sung) | ✅ | ✅ | ✅ | Trang units không tải `/orders`; lịch sử bán chỉ tải khi mở sổ cái và có `ORDER_VIEW`. Đã mở web thật, hiện 8 SKU và không có toast lỗi; không ghi đơn/phiếu. |
| Sửa gốc rollback-only ở cấu hình công nợ | ✅ | — | ✅ | `getOrCreateConfig` không còn tự INSERT khi đọc bảng cấu hình rỗng, không nuốt lỗi truy vấn DB. Quyền GET/PUT giữ theo ma trận hiện có. Test GET config/đơn/công nợ không tạo cấu hình; chỉ PUT có quyền mới lưu. Đã khởi động lại backend theo cho phép của người dùng; cả ba API thật trả 200. |

### Lưu ý triển khai

- Khôi phục các trường kho, tính giá, DTO kiểm tra tồn và cập nhật hồ sơ từ develop bị nhánh cũ làm mất; không đưa lại phân quyền role cứng hoặc tính giữ chỗ nghiệp vụ trên trình duyệt.
- Bổ sung `orders.version BIGINT NOT NULL DEFAULT 0`. Khởi động lại backend để `ddl-auto=update` bổ sung cột trên môi trường phát triển; môi trường dùng migration phải thêm cột trước khi triển khai. Không xóa/reset DB.
- Giữ bố cục/app shell hiện có theo `SKILLS/SKILL_frontend_design.md`; không tạo layout riêng. Nút gợi ý số lượng tối đa không bị mất cú bấm vì blur; phản hồi tồn cũ bị bỏ ngay trong thời gian debounce.
- Lỗi toast bổ sung đã xác minh bằng API thật: `/orders`, `/dealers/1/debt`, `/finance/debt/config` trả rollback-only; lookup sản phẩm/tồn/đơn vị quy đổi trả 200. Truy vấn MySQL bằng kết nối read-only xác nhận bảng `debt_risk_configs` tồn tại, đủ cột và có 0 dòng. Không thêm dòng thủ công hoặc reset DB để né lỗi; sửa logic đọc/ghi trong code. Đăng nhập phát sinh xử lý phiên thông thường, sau đó chỉ gọi GET và SELECT; không tạo/sửa/hủy nghiệp vụ thật.
- Sau khi người dùng cho phép, đã khởi động lại đúng backend 8080 bằng mã hiện tại, tắt seed demo/users cho tiến trình đó. Web thật `localhost:5500/warehouse/units?tab=ledger` hiển thị 8 SKU và 21 dòng sổ cái, không có toast lỗi; `/orders`, `/dealers/1/debt`, `/finance/debt/config` đều HTTP 200. Sau kiểm tra, bảng cấu hình vẫn 0 dòng và không có đơn quá hạn giữ chỗ cần nhả. Backend 8080 và frontend 5500 của người dùng được giữ chạy; server fixture tạm 5538 đã dừng.

### Kiểm thử nghiệm thu

- JavaScript: **130/130**; Python routing: **10/10**.
- Chrome API fixtures (không ghi DB thật): S5-06, nhập kho S5-04, kiểm kê S5-08, luồng S4, thông báo ma trận, units khi orders lỗi và hồi phục ledger; bố cục **150 kiểm tra / 25 trang / 6 kích thước** và navigation click/back/forward/reload/mobile/reduced motion đều đã qua. Các bài nghiệp vụ dùng reduced motion để tách khỏi việc Chrome hủy native ViewTransition khi teardown; bài navigation riêng kiểm tra native animation bình thường.
- Backend: **780/780**, `mvn clean test` trên bản sao với H2 cách ly; không dùng lớp test cũ từ incremental build. Bao gồm 15 ca HTTP/JWT/JPA S5-06, 20 ca hardening kiểm kê (có ca điều khiển khóa đọc số đếm vừa commit), và kiểm tra đọc config/đơn/công nợ không tự ghi DB. Không chạy bộ test nghiệp vụ trên MySQL người dùng.

## Lịch sử S5-06 của nhánh trước đồng bộ (không phải kết quả nghiệm thu hiện tại)
> Cập nhật lần cuối: 2026-10-20

## Bảng theo dõi kết nối Ma trận phân quyền & Tính năng

| Mã tính năng | Tên tính năng | Backend | Frontend | Đã kết nối đúng? | Ghi chú quyền & Phân quyền |
|---|---|:---:|:---:|:---:|---|
| **S5-04** | Phiếu nhập kho (Voucher Nhập kho, NCC, Lô, HSD, Nháp) | ✅ | ✅ | ✅ | Quyền `WAREHOUSE_IMPORT`, `WAREHOUSE_VIEW` cho `ROLE_ADMIN`, `ROLE_WAREHOUSE_MANAGER`, `ROLE_WAREHOUSE_STAFF` |
| **S5-06** | Giữ chỗ tồn kho khi đơn được duyệt (Inventory Reservation) | ✅ | ✅ | ✅ | Quyền `ORDER_CREATE`, `ORDER_VIEW`, `ORDER_APPROVE` cho `ROLE_SALES`, `ROLE_SALES_MANAGER`, `ROLE_ADMIN` |

---

## Chi tiết tính năng S5-06: Giữ chỗ tồn kho khi đơn được duyệt

### 1. Nghiệp vụ đã triển khai (Frontend)

| # | Hạng mục nghiệp vụ | Backend | Frontend | Kết nối đúng? | Mô tả chi tiết |
|---|-------------------|:-------:|:--------:|:-------------:|----------------|
| 1 | Giữ chỗ tồn kho ngay khi đơn chuyển sang Đã duyệt (`APPROVED` / `RECORDED`) | ✅ | ✅ | ✅ | Đơn được duyệt lập tức đưa vào tập `HOLDING_ORDER_STATUSES`, tính vào `reservedBase`, làm giảm tồn khả dụng của SKU để hàng không bị người khác bán mất. |
| 2 | Tự động nhả tồn khi đơn bị huỷ (`CANCELLED`) hoặc từ chối (`REJECTED`) | ✅ | ✅ | ✅ | Đơn chuyển trạng thái huỷ hoặc từ chối sẽ tự động loại khỏi tập giữ chỗ, trả lại tồn khả dụng cho kho. |
| 3 | Tự động nhả tồn khi đơn quá hạn giữ chỗ (`reservationExpiresAt <= now`) | ✅ | ✅ | ✅ | Kiểm tra mốc thời gian giữ chỗ; nếu đã hết hạn, hệ thống tự động nhả tồn mà không cần thao tác thủ công. |
| 4 | Hiển thị cột Tồn khả dụng trên bảng tạo đơn hàng | — | ✅ | ✅ | Bổ sung cột thứ 4 trong bảng dòng hàng (`col-stock`), hiển thị tồn khả dụng theo đơn vị tính đã chọn kèm chi tiết (Thực tế / Giữ chỗ). |
| 5 | Chặn đặt vượt tồn khả dụng & Gợi ý số lượng tối đa | — | ✅ | ✅ | Cảnh báo trực quan khi nhập vượt tồn, cung cấp nút **"Đặt tối đa"** để nhanh chóng điều chỉnh số lượng hợp lệ, chặn xác nhận đơn nếu vượt tồn. |
| 6 | Tự động xác định kho phục vụ cho đại lý theo thứ tự ưu tiên | — | ✅ | ✅ | Tự động nạp kho gắn với đại lý -> kho phụ trách của nhân viên -> kho mặc định đầu tiên; tự tải tồn kho thực tế và đơn hàng. |
| 7 | Phát hiện xung đột đồng thời tránh âm tồn | ✅ | ✅ | ✅ | Kiểm tra tồn tức thời trước khi gửi đơn, phát hiện trường hợp hai người cùng chốt đơn khi tồn sắp hết để chặn không làm âm kho. |
| 8 | Hiển thị trạng thái giữ chỗ trên modal chi tiết đơn duyệt | — | ✅ | ✅ | Modal chi tiết đơn duyệt hiển thị rõ ràng: "Đang giữ chỗ tồn kho", "Chờ duyệt (Chưa giữ chỗ)", hoặc "Đã nhả tồn". |

### 2. Ma trận phân quyền (Permission Matrix)

| Hành động | Quyền (Permission) | Roles cho phép | Kiểm tra Frontend | Kiểm tra Backend |
|-----------|--------------------|----------------|:-----------------:|:----------------:|
| Tạo đơn hàng & Kiểm tra tồn giữ chỗ | `ORDER_CREATE` | `ROLE_SALES`, `ROLE_SALES_MANAGER`, `ROLE_ADMIN` | ✅ `Auth.hasAnyRole` & `OrderInventory` | ✅ `@PreAuthorize("hasAnyRole('SALES', 'SALES_MANAGER', 'ADMIN', ...)")` |
| Xem danh sách đơn & Tồn khả dụng | `ORDER_VIEW` | `ROLE_SALES`, `ROLE_SALES_MANAGER`, `ROLE_ADMIN`, `ROLE_WAREHOUSE_MANAGER` | ✅ Menu-config & role guard | ✅ `@PreAuthorize` |
| Phê duyệt đơn hàng (kích hoạt giữ chỗ tồn) | `ORDER_APPROVE` | `ROLE_SALES_MANAGER`, `ROLE_ADMIN` | ✅ Role guard `order-approval.js` | ✅ `PUT /orders/{id}/approve` (`hasAnyRole('SALES_MANAGER', 'ADMIN')`) |
| Huỷ đơn hàng (kích hoạt nhả tồn tự động) | `ORDER_CANCEL` | `ROLE_SALES`, `ROLE_SALES_MANAGER`, `ROLE_ADMIN` | ✅ Action menu | ✅ `PUT /orders/{id}/cancel` |

### 3. Kết quả Unit Tests (50/50 Tests Passed)

- `tests/order-reservation-s5-06.test.cjs`: 6/6 tests pass (bao gồm giữ chỗ khi duyệt, nhả tồn khi huỷ, tự động nhả tồn khi quá hạn, quy đổi theo đơn vị tính, xung đột đồng thời).
- `tests/order-inventory.test.cjs`: 7/7 tests pass.
- `tests/create-order-debt.test.cjs`: 4/4 tests pass.
- `tests/warehouse-import-voucher.test.cjs`: 3/3 tests pass.
- `tests/menu-config.test.cjs`: 13/13 tests pass.
- `tests/dealer-transactions.test.cjs`: 5/5 tests pass.
- `tests/password-reset.test.cjs`: 7/7 tests pass.
- `tests/products-import.test.cjs`: 5/5 tests pass.
- Tất cả unit tests đều chạy thành công 100%.

## Lịch sử trạng thái từ develop
## S7-04 — Hệ thống tự cảnh báo và khoá bán công nợ (Backend) — 22/10/2026

Nhánh: `feature/S7-04-he--thong-tu-canh-bao-BE`.
Nguồn tiêu chí: User story S7-04 (*"Hệ thống tự cảnh báo và khoá bán công nợ"*):
1. **Đại lý vượt hạn mức tiền hoặc có khoản quá số ngày nợ cho phép được gắn cờ tự động**.
2. **Đại lý bị gắn cờ thì đơn mới bị chặn hoặc chuyển sang cần duyệt, theo cấu hình**.
3. **Tác vụ đánh giá lại chạy mỗi ngày một lần và chạy lại sau mỗi lần ghi nhận thu tiền**.
Phạm vi thực hiện: **Chỉ làm Backend (BE)** theo đúng yêu cầu người dùng, tuân thủ nghiêm ngặt `AGENTS.md` và ma trận phân quyền database-backed.

| Tiêu chí / hạng mục | Backend | Frontend | Kết nối / ma trận quyền |
|---|---|---|---|
| **AC1: Tự động gắn cờ đại lý vi phạm công nợ** (vượt hạn mức tiền hoặc quá số ngày nợ cho phép) | **Đã hoàn thành**: Mở rộng `Dealer` lưu cờ vi phạm: `debtFlagged`, `debtFlagType` (`NONE`, `DEBT_LIMIT_EXCEEDED`, `OVERDUE`, `BOTH`), `debtFlagReason`, `debtFlaggedAt`, `lastDebtEvaluatedAt`. Đánh giá công nợ theo FIFO (thu tiền trừ nợ đơn cũ trước): vượt `debtLimit` hoặc quá `maxDebtDays` sẽ được tự động gắn cờ kèm lý do chi tiết; nếu an toàn sẽ tự động gỡ cờ (`debtFlagged = false`, `debtFlagType = NONE`). | **Chưa triển khai** (Theo yêu cầu: "chỉ làm BE") | API `GET /api/finance/debt/flagged-dealers` sẵn sàng kết nối. Yêu cầu quyền `FINANCE_VIEW_DEBT` hoặc `FINANCE_MANAGE` hoặc `ROLE_ADMIN` từ ma trận DB. Thu hồi quyền trả 403 ngay lập tức. |
| **AC2: Chặn (BLOCK) hoặc chuyển cần duyệt (REQUIRE_APPROVAL) đơn mới khi đại lý bị gắn cờ** | **Đã hoàn thành**: Entity `DebtRiskConfig` lưu cấu hình hệ thống (`limitExceededAction`, `overdueAction`, `defaultAction`, `autoFlagEnabled`). Hỗ trợ cấu hình riêng cho từng đại lý qua `Dealer.debtActionConfig` (`DEFAULT`, `BLOCK`, `REQUIRE_APPROVAL`). Khi đại lý bị gắn cờ: nếu action là `BLOCK` thì chặn hoàn toàn tạo đơn hàng (`DealerDebtBlockedException` -> HTTP 400 Bad Request kèm lý do và mã đại lý); nếu action là `REQUIRE_APPROVAL` thì đơn mới được phép ghi nhận nhưng tự động chuyển sang `PENDING_APPROVAL`, `orderRequiresApproval = true`, bổ sung cảnh báo công nợ vào chi tiết đơn để Quản lý kinh doanh phê duyệt. | **Chưa triển khai** (Theo yêu cầu: "chỉ làm BE") | Quyền tạo đơn `ORDER_CREATE` kiểm tra tự động trạng thái cờ công nợ của đại lý tại `OrderServiceImpl`. Quyền cấu hình hành vi công nợ (`PUT /api/finance/debt/config`, `PUT /api/finance/debt/{dealerId}/action-config`) bắt buộc `FINANCE_MANAGE` hoặc `ROLE_ADMIN`. |
| **AC3: Tác vụ đánh giá lại chạy định kỳ mỗi ngày một lần và chạy lại tự động sau khi thu tiền** | **Đã hoàn thành**: Scheduler `DebtAssessmentScheduler` chạy định kỳ mỗi ngày một lần vào 01:00 AM (`@Scheduled(cron = "${app.debt.evaluation.cron:0 0 1 * * ?}")`) quét toàn bộ đại lý để gắn cờ hoặc gỡ cờ. Entity `DebtPayment` và API `POST /api/finance/debt-payments` ghi nhận phiếu thu tiền công nợ; ngay sau khi lưu phiếu thu, hệ thống tự động kích hoạt tác vụ đánh giá lại công nợ ngay lập tức cho đại lý đó, gỡ cờ tự động nếu công nợ đã về mức an toàn. Hỗ trợ kích hoạt đánh giá thủ công qua `POST /api/finance/debt/evaluate` và `POST /api/finance/debt/{dealerId}/evaluate`. | **Chưa triển khai** (Theo yêu cầu: "chỉ làm BE") | Ghi nhận thu tiền và kích hoạt đánh giá yêu cầu quyền `FINANCE_MANAGE` hoặc `ROLE_ADMIN`. Tra cứu lịch sử thu tiền yêu cầu `FINANCE_VIEW_DEBT`, `FINANCE_MANAGE` hoặc `ROLE_ADMIN`. |
| **Phân quyền ma trận DB & Không fallback vai trò** | **Đã hoàn thành**: Toàn bộ 8 endpoints S7-04 kết nối chặt chẽ với ma trận phân quyền database-backed (`RolePermissionConfig`), không sử dụng role-based fallback. Thu hồi quyền trên cùng một token JWT lập tức trả về `403 Forbidden` trên request kế tiếp mà không cần đăng nhập lại. Tài khoản `ROLE_ADMIN` luôn có toàn quyền truy cập. | **Chưa triển khai** (Frontend sẽ ẩn/hiện menu, nút cấu hình và nút thu tiền tương ứng theo ma trận quyền khi phát triển FE) | Đã kiểm chứng qua bộ test bảo mật `DebtRiskSecurityTest` và kiểm thử tích hợp `DebtRiskIntegrationTest`: 401 khi chưa đăng nhập, 403 khi thiếu quyền hoặc bị thu hồi quyền động, 200/201 khi có quyền, ADMIN full access. |

### Kiểm chứng Backend S7-04

Đã triển khai và vượt qua **42/42 tests tự động (100% PASS)**:
1. `DealerDebtServiceTest` (**18/18 tests passed**): Kiểm thử toàn bộ nghiệp vụ đánh giá công nợ, tính nợ theo FIFO, tự động gắn cờ `DEBT_LIMIT_EXCEEDED`, `OVERDUE`, `BOTH`, xử lý `BLOCK` ném ngoại lệ `DealerDebtBlockedException`, xử lý `REQUIRE_APPROVAL` cho phép tạo đơn, tự động gỡ cờ sau khi ghi nhận phiếu thu tiền, cấu hình hệ thống và cấu hình riêng đại lý.
2. `DebtAssessmentSchedulerTest` (**2/2 tests passed**): Kiểm thử tác vụ định kỳ tự động chạy đánh giá công nợ toàn bộ đại lý và xử lý khi cấu hình tắt tự động đánh giá.
3. `FinanceControllerTest` (**13/13 tests passed**): Kiểm thử REST APIs cho cấu hình rủi ro công nợ, cấu hình riêng đại lý, danh sách đại lý gắn cờ, kích hoạt đánh giá thủ công, ghi nhận thu tiền, tra cứu lịch sử thu tiền phân trang.
4. `DebtRiskSecurityTest` (**6/6 tests passed**): Kiểm thử ma trận phân quyền động, xác minh mã lỗi 401 khi chưa đăng nhập, 403 khi thiếu quyền `FINANCE_VIEW_DEBT` / `FINANCE_MANAGE` (không fallback theo role), 200/201 khi được cấp quyền, xác minh `ROLE_ADMIN` toàn quyền.
5. `DebtRiskIntegrationTest` (**3/3 tests passed**): Kiểm thử tích hợp toàn trình HTTP/JWT/JPA/H2:
   - Luồng vượt hạn mức: Tự gắn cờ `DEBT_LIMIT_EXCEEDED` -> Chặn tạo đơn hàng (400 Bad Request) khi action `BLOCK` -> Đổi action sang `REQUIRE_APPROVAL` -> Tạo đơn thành công với trạng thái `PENDING_APPROVAL` -> Ghi nhận thu nợ `DebtPayment` -> Tự động đánh giá lại và gỡ cờ thành công.
   - Luồng nợ quá hạn: Tự gắn cờ `OVERDUE` khi có đơn vượt `maxDebtDays` -> Thu nợ cấn trừ đúng đơn nợ cũ -> Tự động gỡ cờ.
   - Luồng ma trận phân quyền: Thu hồi quyền tức thì trả về 403 Forbidden trên cùng token JWT hiện tại; cấp lại quyền hoạt động bình thường; Admin luôn được phép.

Đã cập nhật chi tiết hợp đồng API tại `backend/API_DOCUMENTATION.md`.

---

## S6-07 — Theo dõi hàng đã xuất nhưng chưa giao xong & Tiền hàng trên đường (Backend) — 22/10/2026

Nhánh: `S6-07/theo-doi-hang-da-xuat-nhung-chua-giao-xong-BE`. Nguồn tiêu chí: User story S6-07 (*"Là Quản lý kho, tôi muốn theo dõi hàng đã xuất nhưng chưa giao xong, để biết đang có bao nhiêu tiền hàng nằm trên đường"*). Phạm vi thực hiện: **chỉ làm Backend** theo đúng yêu cầu người dùng, tuân thủ nghiêm ngặt `AGENTS.md` và ma trận phân quyền database-backed.

| Tiêu chí / hạng mục | Backend | Frontend | Kết nối / ma trận quyền |
|---|---|---|---|
| Danh sách chuyến giao đang chạy kèm số điểm đã giao và còn lại | Đã hoàn thành: `GET /api/warehouse/delivery-trips`, trả danh sách chuyến phân trang, tính toán tự động `totalPoints`, `deliveredPoints`, `remainingPoints` (số điểm còn lại chưa giao xong), `failedPoints`, `pendingPoints`; lọc theo `warehouseId`, `status` (mặc định hoặc lọc `IN_PROGRESS`), `keyword` | Chưa triển khai (theo yêu cầu "chỉ làm BE") | Bắt buộc `WAREHOUSE_VIEW` hoặc `WAREHOUSE_EXPORT` hoặc `ROLE_ADMIN`; phạm vi dữ liệu cách ly theo các kho được gán (`user.getWarehouses()`), truy cập kho ngoài phạm vi trả 403 |
| Theo dõi tổng tiền hàng đang nằm trên đường | Đã hoàn thành: `GET /api/warehouse/delivery-trips/summary` và trường `remainingValue` trong từng chuyến (`remainingValue = totalValue - deliveredValue`, bao gồm giá trị hàng tại các điểm chờ giao và các điểm chưa giao được); tổng hợp `totalValueOnRoad` cho Quản lý kho biết chính xác đang có bao nhiêu tiền hàng trên đường | Chưa triển khai (theo yêu cầu "chỉ làm BE") | Bắt buộc `WAREHOUSE_VIEW` hoặc `WAREHOUSE_EXPORT` hoặc `ROLE_ADMIN`; tính toán hoàn toàn phía server từ DB, không tin client |
| Hàng chưa giao được vẫn thuộc trách nhiệm chuyến, KHÔNG tự về kho | Đã hoàn thành: `PUT /api/warehouse/delivery-trips/{id}/stops/{stopId}/status` cập nhật `FAILED`, bắt buộc lý do `failedReason`; cờ `returnedToWarehouse = false`; **tuyệt đối KHÔNG tự động cộng lại tồn kho** `WarehouseInventory`; hàng vẫn nằm trên xe/chuyến giao và tính trong tiền hàng trên đường; chỉ nhập kho khi gọi API hoàn trả tường minh `POST /stops/{stopId}/return-to-warehouse` | Chưa triển khai (theo yêu cầu "chỉ làm BE") | Bắt buộc `WAREHOUSE_EXPORT` hoặc `ROLE_ADMIN`; chặn 403 nếu thiếu quyền; ghi nhận AuditLog chi tiết hành vi giao thất bại kèm lý do |
| Cảnh báo chuyến quá thời gian dự kiến | Đã hoàn thành: So sánh thời điểm hiện tại với `expectedCompletionTime` khi chuyến ở trạng thái `IN_PROGRESS`; tự động bật cờ `isOverdue = true`, tính số phút quá hạn `overdueMinutes`, trả chuỗi cảnh báo trực quan `overdueWarning`; hỗ trợ lọc nhanh chuyến trễ hẹn bằng query `isOverdue=true`; thống kê `totalOverdueTrips` trong summary | Chưa triển khai (theo yêu cầu "chỉ làm BE") | Bắt buộc `WAREHOUSE_VIEW` hoặc `WAREHOUSE_EXPORT` hoặc `ROLE_ADMIN` |
| Quản lý vòng đời chuyến giao và điểm dừng | Đã hoàn thành: `POST /api/warehouse/delivery-trips` (tạo chuyến từ đơn hàng đã xuất hoặc điểm tùy chỉnh), `POST /{id}/complete` (hoàn tất chuyến), `POST /{id}/cancel` (hủy chuyến bắt buộc lý do, chặn hủy khi đã giao điểm nào), `GET /{id}` (chi tiết chuyến), `GET /options`, `GET /eligible-orders` | Chưa triển khai (theo yêu cầu "chỉ làm BE") | Thao tác ghi bắt buộc `WAREHOUSE_EXPORT` hoặc `ROLE_ADMIN`; xem bắt buộc `WAREHOUSE_VIEW` hoặc `WAREHOUSE_EXPORT` hoặc `ROLE_ADMIN` |
| Phân quyền ma trận DB, cách ly kho và thu hồi động | Đã hoàn thành: Kiểm tra quyền động qua `SecurityContext` dựa trên ma trận phân quyền DB; thu hồi quyền trên phiên JWT hiện tại lập tức trả 403 Forbidden không fallback theo role; giới hạn dữ liệu kho theo `user.getWarehouses()`; `ROLE_ADMIN` luôn toàn quyền | Chưa triển khai (theo yêu cầu "chỉ làm BE") | Không dùng fallback vai trò; nguồn sự thật duy nhất là ma trận phân quyền database-backed |

Kiểm chứng: **32 test backend mới qua 100%**:
1. `DeliveryTripServiceTest` (13 tests): Nghiệp vụ tạo chuyến, tính số điểm đã giao / còn lại, tính tiền hàng trên đường, bất biến hàng chưa giao được không tự về kho, nhập kho trả hàng tường minh, cảnh báo quá thời gian dự kiến, thống kê summary và giới hạn kho.
2. `DeliveryTripControllerTest` (8 tests): REST Endpoints, validation DTO, HTTP status, phân trang, serialize JSON.
3. `DeliveryTripSecurityTest` (7 tests): Phân quyền ma trận, chặn 401 khi chưa đăng nhập, 403 khi thiếu quyền, chặn vai trò kho không có quyền xuất kho (không fallback role), cho phép tài khoản có quyền hoặc admin, kiểm tra các thao tác ghi/đọc.
4. `DeliveryTripIntegrationTest` (4 tests): Kiểm thử tích hợp toàn diện trên HTTP/JWT/JPA/H2 MySQL mode, kiểm tra luồng vòng đời chuyến giao, tính chính xác tiền hàng trên đường, bất biến tồn kho không tăng khi giao thất bại, cảnh báo quá thời gian dự kiến, cách ly dữ liệu kho và thu hồi quyền động có hiệu lực ngay trên JWT hiện tại.
Đã cập nhật tài liệu hợp đồng API tại `backend/API_DOCUMENTATION.md`.

---

## S6-04 — Gợi ý lô xuất theo nguyên tắc hạn dùng gần nhất trước (FEFO) — 22/10/2026

Nhánh: `S6-04/he-thong-goi-y-lo-xuat-theo-nguyen-tac`.
Đối chiếu 3 tiêu chí trong backlog người dùng yêu cầu:
1. Mặc định gợi ý lô có hạn sử dụng gần nhất còn đủ số lượng.
2. Thủ kho chọn lô khác được nhưng phải nhập lý do.
3. Xuất kho trừ đúng tồn của lô đã chọn.
Theo yêu cầu người dùng: **chỉ triển khai Backend (BE)**.

| Tiêu chí / hạng mục | Backend | Frontend | Kết nối / ma trận quyền |
|---|---|---|---|
| **AC1: Gợi ý lô theo FEFO (hạn dùng gần nhất còn đủ số lượng)** | **Đã xong**: Thêm API `GET /api/warehouse/export/suggest-lot` và `POST /api/warehouse/export/suggest-lots`. Sắp xếp lô theo FEFO (ưu tiên lô chưa hết hạn, ngày hết hạn sớm nhất lên đầu, tiếp đến ngày sản xuất cũ hơn). Tự động tìm lô đầu tiên có tồn `>= requestedBaseQuantity`. Nếu không có lô đủ, trả về `hasSufficientStock = false`, `suggestedLot = null` và toàn bộ danh sách `availableLots` sắp xếp theo FEFO để thủ kho lựa chọn. | **Chưa làm** (yêu cầu người dùng: chỉ làm BE) | Quyền `WAREHOUSE_EXPORT` hoặc `WAREHOUSE_VIEW` từ ma trận DB (hoặc `ROLE_ADMIN`). Giới hạn phạm vi kho được phân công; thu hồi quyền trả 403 ngay lập tức. |
| **AC2: Chọn lô khác phải nhập lý do** | **Đã xong**: Trong `POST /api/warehouse/export`, nếu không truyền `lotNumber`, hệ thống tự áp dụng lô FEFO gợi ý (không cần lý do). Nếu thủ kho chủ động chọn đúng lô gợi ý: thành công không cần lý do. Nếu thủ kho chọn lô khác với lô gợi ý: **bắt buộc nhập lý do** qua `overrideReason` / `lotReason` / `note`. Nếu để trống lý do: chặn và trả lỗi 400 Bad Request. Lưu `suggestedLotNumber` và `overrideReason` vào `WarehouseVoucherItem`. | **Chưa làm** (yêu cầu người dùng: chỉ làm BE) | Quyền `WAREHOUSE_EXPORT` từ ma trận DB (hoặc `ROLE_ADMIN`). Khi gửi lô khác thiếu lý do, server trả thông báo rõ ràng: `"Thủ kho chọn lô '...' khác với lô gợi ý hạn dùng gần nhất ('...'), bắt buộc phải nhập lý do"`. |
| **AC3: Trừ đúng tồn của lô đã chọn & chặn âm kho** | **Đã xong**: Khi ghi sổ phiếu xuất kho, hệ thống khóa dòng tồn kho và trừ chính xác số lượng xuất quy đổi cơ sở vào tồn của lô đã chọn (`WarehouseLotInventory.baseQuantity`). Chặn xuất vượt quá tồn lô đã chọn (trả 400 nếu `lotInv.baseQuantity < baseQty`), chặn chọn lô không tồn tại hoặc đã hết hàng. Cập nhật đồng bộ tồn tổng và ghi `AuditLog`. | **Chưa làm** (yêu cầu người dùng: chỉ làm BE) | Kiểm tra quyền `WAREHOUSE_EXPORT` tại cả Controller và Service. Toàn bộ thao tác trừ tồn kho tổng và tồn lô nằm trong một `@Transactional`, lỗi bất kỳ điểm nào đều rollback toàn bộ. |
| **Ma trận phân quyền & Giới hạn phạm vi kho** | **Đã xong**: Quyền lấy trực tiếp từ database-backed permission matrix trên request hiện tại qua `RolePermissionConfig`, không dùng role fallback hay default permission. Thu hồi quyền trên phiên đang dùng có hiệu lực ngay lập tức (trả 403). `ROLE_ADMIN` luôn có toàn quyền. Giới hạn phạm vi kho theo phân công tài khoản (`requireWarehouse`), tài khoản ngoài phạm vi trả 403. | **Chưa làm** (Frontend hiển thị/ẩn nút xuất kho và modal chọn lô theo ma trận sẽ được triển khai ở task FE) | Đã kiểm chứng: Thu hồi `WAREHOUSE_EXPORT` trả 403 ngay lập tức; cấp lại trả 200; gọi kho ngoài phạm vi trả 403; Admin luôn thành công. |

### Kiểm chứng Backend S6-04
- **Unit Tests**: `WarehouseExportLotSuggestionTest` (**9/9 passed**)
  1. `suggestLot_Need10_SkipsLotA_SuggestsLotB`: Bỏ qua lô A không đủ hàng, gợi ý lô B có HSD gần nhất trong các lô đủ hàng.
  2. `suggestLot_Need3_SuggestsLotA`: Khi cần 3 hộp, lô A đủ hàng và date gần nhất -> gợi ý lô A.
  3. `suggestLot_Need100_NoSingleLotSufficient`: Không có lô đơn lẻ nào đủ -> `suggestedLot = null`, `hasSufficientStock = false`, trả toàn bộ danh sách FEFO.
  4. `exportGoods_NoLotNumberProvided_AutoPicksFefoLot`: Không truyền lô -> tự động chọn lô FEFO và trừ đúng tồn lô đó.
  5. `exportGoods_SelectSameAsSuggestedLot_SuccessWithoutReason`: Chọn đúng lô gợi ý -> thành công không cần lý do.
  6. `exportGoods_SelectDifferentLotWithoutReason_ThrowsException`: Chọn lô khác không có lý do -> ném ngoại lệ yêu cầu lý do.
  7. `exportGoods_SelectDifferentLotWithReason_SuccessAndRecordsReason`: Chọn lô khác có lý do -> thành công, lưu `suggestedLotNumber` và `overrideReason`, trừ đúng tồn lô đã chọn.
  8. `exportGoods_ExceedLotStock_ThrowsException`: Xuất vượt tồn lô đã chọn -> ném ngoại lệ không đủ tồn.
  9. `exportGoods_NonExistentLot_ThrowsException`: Chọn lô không có trong kho -> ném ngoại lệ.
- **Integration Tests**: `WarehouseExportLotIntegrationTest` (**6/6 passed**)
  1. `suggestLot_FEFO_ReturnsLotWithNearestExpiryAndSufficientStock`: Gọi GET `/api/warehouse/export/suggest-lot` trả 200 OK và đúng lô FEFO.
  2. `suggestLots_Batch_ReturnsSuggestions`: Gọi POST `/api/warehouse/export/suggest-lots` trả 200 OK danh sách gợi ý hàng loạt.
  3. `exportGoods_SelectDifferentLotWithoutReason_Returns400`: Chọn lô khác không có lý do trả 400 Bad Request.
  4. `exportGoods_SelectDifferentLotWithReason_SuccessAndDeductsSelectedLot`: Chọn lô khác có lý do trả 200 OK và trừ đúng tồn lô đã chọn.
  5. `permissionMatrix_RevokeAndGrant_ImmediateEnforcement`: Thu hồi `WAREHOUSE_EXPORT` trả 403 Forbidden ngay lập tức trên token cũ; cấp lại trả 200 OK.
  6. `warehouseScope_OutsideWarehouse_Forbidden`: Truy cập kho ngoài phạm vi trả 403 Forbidden; ADMIN được phép.
- **Toàn bộ bộ test kho liên quan**: `Warehouse*Test` (**78/78 passed**).
- **Tài liệu API**: Đã cập nhật đầy đủ hợp đồng API tại `backend/API_DOCUMENTATION.md`.

---

## S5-04 — Hoàn thiện frontend và backend — 22/10/2026

Nhánh: `feature/S5-04-chuc-nang-lap-phieu-nhap-kho(frontend)`. Đối chiếu 5 tiêu chí trong ảnh backlog do người dùng cung cấp; phần này thay thế các ghi chú S5-04 chưa đầy đủ bên dưới.

| Tiêu chí / hạng mục | Backend | Frontend | Kết nối / ma trận quyền |
|---|---|---|---|
| NCC, số chứng từ, ngày nhập, kho nhập | Lưu các trường riêng; kiểm tra NCC/kho hoạt động và phạm vi kho | Chọn NCC từ danh mục thật, nhập chứng từ/ngày/kho, xem lại chi tiết | `/warehouse/import/options` trả danh mục hẹp, không cần quyền quản lý NCC; không nhét dữ liệu vào note |
| Nhập đơn vị bất kỳ đã cấu hình | Quy đổi số lượng lẻ bằng BigDecimal; xác nhận mới chốt hệ số hiện hành; phiếu đã ghi sổ giữ snapshot | Chọn đơn vị, xem số cơ sở dự kiến, không làm tròn mất số lẻ; sửa lỗi blur số lượng làm mất nội dung ô số lô | Payload gửi `unit/quantity`, server quyết định quy đổi và tồn |
| Số lô, hạn sử dụng | Bắt buộc với `manageLot`; lưu riêng từng dòng, cộng tồn lô; chặn cùng lô khác HSD | Trường lô/HSD, thông báo thiếu lô, khôi phục khi sửa nháp và hiện trong chi tiết | Product lookup trả `manageLot`; gửi `lotNumber/expiryDate`, giữ ngày sản xuất cũ nếu mở lại |
| Nháp không ảnh hưởng tồn; xác nhận mới cộng tồn | Nháp lưu DB; khóa phiếu/kho/tồn khi xác nhận; chặn xác nhận lặp; lỗi rollback cả tồn/lô/phiếu/nhật ký | Lưu/mở lại/sửa/xóa nháp theo ID server; tải lại trang không mất nháp; khóa nút khi đang gửi | IMPORT lưu/sửa/xóa nháp; APPROVE xác nhận. Nhập trực tiếp cũng cần APPROVE, không có đường tắt cộng tồn |
| Phiếu xác nhận bất biến, sai sót lập điều chỉnh | Chặn sửa/xóa phiếu xác nhận; phiếu điều chỉnh riêng cùng kho, tham chiếu phiếu gốc, lý do bắt buộc; không âm tồn/lô hoặc giảm dưới tồn giữ chỗ | Chi tiết chỉ đọc; người có quyền được nhập lượng tăng/giảm và lý do để lập phiếu điều chỉnh | ADJUST điều chỉnh; số lượng có dấu ghi trên phiếu điều chỉnh, giữ nguyên dòng phiếu gốc |
| Ma trận, phạm vi và đồng bộ nhiều phiên | Quyền DB trên request hiện tại, ADMIN luôn được phép; kho được giao giới hạn dữ liệu; version chặn sửa đè nháp cũ | Menu/tab/nút cùng quyền; IMPORT-only/APPROVE-only dùng được mà không cần VIEW; không khôi phục dữ liệu từ phản hồi trễ sau thu hồi quyền | Không fallback vai trò; dữ liệu nghiệp vụ không lưu local/session storage |
| Bố cục và hồi quy | Giữ nghiệp vụ kiểm kê/chuyển kho đang có; cập nhật demo bootstrap theo hợp đồng mới | Giữ app shell, sidebar/topbar và khung nội dung chung; bảng dòng hàng cuộn riêng, không bóp nhỏ ô nhập trên mobile | Hồi quy S5-08, điều hướng và bố cục dùng chung |

Kiểm chứng: **663 test backend qua** trên bản sao cách ly, H2 in-memory, gồm **14 test tích hợp S5-04 HTTP/JWT/ma trận/JPA**: đồng thời, rollback, phạm vi kho, cấp/thu hồi quyền trên token cũ, không lộ phiếu xuất qua danh mục nhập. **120 test JavaScript**, **10 test định tuyến** qua. Chrome S5-04 qua lưu nháp/tải lại/sửa/xác nhận/lỗi/điều chỉnh/quyền/XSS/desktop–tablet–mobile; Chrome S5-08 qua toàn bộ luồng và 6 kích thước. Bố cục **150 trường hợp (25 trang × 6 kích thước)** qua; điều hướng click/Back/Forward/Reload/mobile/reduced-motion qua, sidebar **472 → 472**.

Triển khai: bổ sung `warehouse_vouchers.version BIGINT NOT NULL DEFAULT 0`; cấu hình hiện tại `ddl-auto=update` tự thêm khi backend khởi động, môi trường dùng migration riêng cần thêm cột tương ứng. **Không xóa/reset DB**. Chưa chạy hay ghi dữ liệu nghiệp vụ vào MySQL thật; chỉ H2 và HTTP fixture. Không tự commit/push/merge develop. Nhánh đã chứa develop `c04ace5`, không còn file unmerged tại lần kiểm tra này. Cần commit/push toàn bộ thay đổi mới rồi tạo/cập nhật PR.

## Lịch sử — S5-04 đồng bộ develop và xử lý xung đột — 21/10/2026

Phạm vi: đưa `origin/develop` tại `c04ace5` vào nhánh `feature/S5-04-chuc-nang-lap-phieu-nhap-kho(frontend)`; giữ thay đổi nhập kho của nhánh và các sửa ma trận/khởi tạo theo tab/kiểm kê mới từ develop. Không tự commit, push hoặc merge vào develop.

| Hạng mục | Backend | Frontend | Kết nối / ma trận quyền |
|---|---|---|---|
| Xung đột `warehouse-units.js` | Nhận nguyên backend develop | Giữ NCC, số chứng từ, lô/HSD của S5-04 và toàn bộ client kiểm kê S5-08; giữ COMPLETED và khởi tạo lười theo tab | Giữ `/products/lookup`; chỉ nạp đơn khi có ORDER_VIEW; chỉ nạp NCC khi có quyền API tương ứng |
| Ghi sổ / lưu nháp phiếu nhập-xuất | Giữ nguyên API và kiểm tra quyền của develop | Cả nút và hàm lưu nháp kiểm đúng quyền IMPORT/EXPORT/ORDER_CREATE hiện tại, không dùng role fallback | Giữ scope kho, thu hồi quyền và ADMIN từ hệ thống hiện tại |
| Phạm vi nghiệm thu | Chưa thay nghiệp vụ backend nhập kho | Nháp S5-04 của nhánh vẫn chỉ ở bộ nhớ; dữ liệu NCC/chứng từ/lô/HSD còn gửi theo note | Hết conflict không đồng nghĩa S5-04 đã hoàn chỉnh: cần đợt nối API nháp DB và trường nghiệp vụ riêng; không xác nhận lại các dấu hoàn tất trong ghi chú cũ bên dưới |

Kiểm chứng tích hợp: **118 test JavaScript qua**, bao gồm 5 test nhập kho đã cập nhật harness theo Auth API/khởi tạo theo tab và kiểm cấp/thu hồi quyền; **10 test định tuyến qua**. Chrome hồi quy S5-08 qua snapshot, lưu/mở lại/chốt/hủy, lỗi giữ input, đổi kho, grant/revoke, phản hồi trễ và 6 kích thước; kiểm bố cục **150 trường hợp = 25 trang × 6 kích thước** qua. Backend không khác `origin/develop`, không sửa/chạy dữ liệu MySQL thật. Đã đánh dấu hai file conflict là resolved; merge đồng bộ cần được người dùng hoàn tất bằng Continue merge/commit trước khi push. Phần dưới giữ lịch sử của cả hai nhánh.

## Ghi chú S5-04 cũ của nhánh — 20/10/2026 (lịch sử, chưa phải nghiệm thu hiện tại)

## S5-04: Phiếu nhập kho (Warehouse Import Voucher)

### Tổng quan tính năng

| # | Tính năng | Backend | Frontend | Kết nối đúng? |
|---|-----------|:-------:|:--------:|:-------------:|
| 1 | Dropdown nhà cung cấp (`/suppliers?size=100`) | ✅ | ✅ | ✅ |
| 2 | Lưu bản nháp phiếu kho (DRAFT, không gọi API ghi sổ) | — (local) | ✅ | ✅ |
| 3 | Xác nhận ghi sổ phiếu nhập kho (`POST /warehouse/import`) | ✅ | ✅ | ✅ |
| 4 | Ghi số chứng từ gốc (`docNumber`) vào ghi chú phiếu | ✅ | ✅ | ✅ |
| 5 | Ghi tên nhà cung cấp (`supplierName`) vào ghi chú phiếu | ✅ | ✅ | ✅ |
| 6 | Ghi số lô & hạn sử dụng vào note từng item | ✅ | ✅ | ✅ |
| 7 | Status label mở rộng: DRAFT, ADJUSTED | — | ✅ | ✅ |
| 8 | Hiển thị nháp trên sổ cái + nút "Sửa nháp" | — (local) | ✅ | ✅ |
| 9 | Modal chi tiết phiếu: hiện lô/HSD + thông báo bất biến RECORDED | ✅ | ✅ | ✅ |
| 10 | Snapshot hệ số quy đổi tại thời điểm ghi sổ | ✅ | ✅ | ✅ |

### Ma trận phân quyền (Permission Matrix)

| Hành động | Permission | Roles cho phép | FE kiểm tra | BE kiểm tra |
|-----------|-----------|----------------|:-----------:|:-----------:|
| Xem tồn kho / sổ cái | `WAREHOUSE_VIEW` | ADMIN, SALES_MANAGER, WAREHOUSE_MANAGER, WAREHOUSE_STAFF, WAREHOUSE_VIEWER | ✅ `canManageUnits` | ✅ |
| Tạo phiếu nhập/xuất kho | `WAREHOUSE_IMPORT` | ADMIN, WAREHOUSE_MANAGER, WAREHOUSE_STAFF | ✅ `canRecordVouchers` | ✅ |
| Lưu nháp phiếu | `WAREHOUSE_IMPORT` | ADMIN, WAREHOUSE_MANAGER, WAREHOUSE_STAFF | ✅ `canRecordVouchers` | — (local) |
| Quản lý đơn vị quy đổi | `WAREHOUSE_VIEW` | ADMIN, WAREHOUSE_MANAGER, WAREHOUSE_STAFF, SALES_MANAGER | ✅ `canManageUnits` | ✅ |
| Tab "Nhập / Xuất kho" | `WAREHOUSE_IMPORT` | ADMIN, WAREHOUSE_MANAGER, WAREHOUSE_STAFF | ✅ ẩn tab | ✅ menu-config |

### Unit Tests

| Test | Mô tả | Kết quả |
|------|--------|:-------:|
| `S5-04: Khởi tạo tải danh sách NCC` | Gọi `/suppliers?size=100`, populate dropdown | ✅ Pass |
| `S5-04: Lưu nháp không gọi API` | Không POST `/warehouse/import`, hiện toast | ✅ Pass |
| `S5-04: Xác nhận ghi sổ` | POST `/warehouse/import` với voucherCode, docNumber, supplierName | ✅ Pass |

### File đã sửa

- `frontend/js/warehouse-units.js` — Toàn bộ logic S5-04
- `tests/warehouse-import-voucher.test.cjs` — 3 unit tests

## Trạng thái sprint 1–5 từ develop đã đồng bộ

## S6-01 — Theo dõi Lô và Hạn sử dụng của Hàng hoá (Backend) — 22/10/2026

Nguồn yêu cầu: Ảnh user story S6-01 người dùng cung cấp trong chat (*"Là Nhân viên kho, tôi muốn theo dõi lô và hạn sử dụng của hàng hoá, để không giao nhầm hàng sắp hết hạn cho đại lý"*). Phạm vi thực hiện: **chỉ làm BE** theo chỉ đạo trực tiếp của người dùng, tuân thủ AGENTS.md và ma trận phân quyền.

| Tiêu chí / hạng mục | Backend | Frontend | Kết nối / ma trận quyền |
|---|---|---|---|
| Bật hoặc tắt quản lý lô theo từng SKU | Đã hoàn thành: `PATCH /api/products/{id}/manage-lot` (và `PUT /api/products/{id}`), hỗ trợ bật/tắt cờ `manageLot` và cấu hình `expiryWarningDays`; mặc định 30 ngày nếu không khai báo cụ thể | Chưa triển khai (chỉ làm backend theo yêu cầu) | Quyền `PRODUCT_EDIT` hoặc `WAREHOUSE_MANAGE` hoặc `ROLE_ADMIN`; ma trận database-backed, thu hồi quyền lập tức trả 403 không fallback role |
| Mỗi lô có số lô, ngày sản xuất, hạn sử dụng và số lượng còn lại | Đã hoàn thành: `GET /api/warehouse/inventory/lots`, trả về đầy đủ `lotNumber`, `manufacturingDate`, `expiryDate`, `baseQuantity` (số lượng còn lại), `baseUnit`, `daysUntilExpiry`, `status` ("EXPIRED", "EXPIRING_SOON", "ACTIVE"), `warningThresholdDays`, `isExpired`, `isExpiringSoon` | Chưa triển khai (chỉ làm backend theo yêu cầu) | Quyền `WAREHOUSE_VIEW` hoặc `WAREHOUSE_MANAGE` hoặc `ROLE_ADMIN` |
| Cảnh báo lô sắp hết hạn theo ngưỡng khai báo được | Đã hoàn thành: `GET /api/warehouse/lot-expiry-alerts` (thống kê tổng cảnh báo, số lô quá hạn, số lô sắp hết hạn kèm danh sách chi tiết các lô có tồn > 0 sắp hết hạn theo ngưỡng SKU hoặc ngưỡng tùy chọn `thresholdDays`), `GET /api/warehouse/lot-thresholds` (xem danh sách ngưỡng theo SKU), `PUT /api/warehouse/lot-thresholds/{productId}` (khai báo ngưỡng ngày cảnh báo cho SKU) | Chưa triển khai (chỉ làm backend theo yêu cầu) | Xem cảnh báo & ngưỡng: `WAREHOUSE_VIEW` hoặc `WAREHOUSE_MANAGE` hoặc `ROLE_ADMIN`; Khai báo/sửa ngưỡng: `WAREHOUSE_MANAGE` hoặc `ROLE_ADMIN` |
| Không cho xuất lô đã quá hạn, trừ khi Quản lý kho xác nhận ghi đè kèm lý do | Đã hoàn thành: `POST /api/warehouse/export` và `POST /api/warehouse/vouchers/{id}/confirm`. Tự động kiểm tra hạn sử dụng của lô khi xuất. Nếu quá hạn: Nhân viên kho bình thường bị chặn 403 Forbidden; Quản lý kho bắt buộc phải bật cờ `overrideExpiredLot = true` và nhập lý do `expiredLotOverrideReason` không rỗng; lưu vết phiếu kho, chi tiết dòng và AuditLog `OVERRIDE_EXPIRED_EXPORT` | Chưa triển khai (chỉ làm backend theo yêu cầu) | Quản lý kho: `WAREHOUSE_APPROVE` hoặc `WAREHOUSE_MANAGE` hoặc `ROLE_ADMIN`. Thu hồi quyền `WAREHOUSE_APPROVE` trên phiên JWT lập tức chặn xuất lô quá hạn với lỗi 403, không fallback role |

Kiểm chứng: **24 test backend mới qua toàn bộ**, bao gồm:
- 11 test service `WarehouseLotExpiryServiceTest`: xem tồn theo lô đầy đủ số lượng/NSX/HSD, cảnh báo sắp hết hạn theo ngưỡng SKU và ngưỡng tùy chọn, cập nhật ngưỡng cảnh báo SKU, chặn xuất lô quá hạn đối với nhân viên kho (403 AccessDenied), chặn Quản lý kho nếu thiếu cờ ghi đè hoặc thiếu lý do, cho phép Quản lý kho xuất kèm lý do và ghi audit log, chặn xuất vượt quá tồn lô.
- 9 test bảo mật `WarehouseLotSecurityTest`: ma trận phân quyền database-backed, chặn 401 khi chưa đăng nhập, chặn 403 khi thiếu quyền xem kho, cho phép Thủ kho có `WAREHOUSE_VIEW` xem tồn lô và cảnh báo, chặn Thủ kho sửa ngưỡng (403), cho phép Quản lý kho có `WAREHOUSE_MANAGE` sửa ngưỡng (200), chặn Thủ kho xuất lô quá hạn (403), cho phép Quản lý kho xuất có lý do (200), thu hồi `WAREHOUSE_APPROVE` lập tức chặn 403, `ROLE_ADMIN` luôn có toàn quyền.
- 4 test controller `ProductManageLotControllerTest`: bật/tắt quản lý lô và cập nhật ngưỡng theo SKU qua PATCH API với quyền `PRODUCT_EDIT` / `WAREHOUSE_MANAGE`.
- Hồi quy toàn bộ kho: 60/60 test liên quan đến kho và kiểm kê đều pass.

Cập nhật **21/10/2026**: hoàn thiện S5-08 trên nhánh `feature/S5-08-chuc-nang-kiem-ke-chenh-lech(frontend)`, sau khi người dùng đồng bộ develop tại merge commit `5d9008c`. Các mục phía dưới giữ lịch sử những đợt triển khai trước; trạng thái S5-08 mới nhất nằm ở bảng ngay dưới đây. Đợt sửa này không tự commit, merge vào develop hoặc push.

## S5-08 — Hoàn thiện frontend, backend và đối chiếu tiêu chí Jira — 21/10/2026

Nguồn nghiệm thu: ảnh user story S5-08 người dùng cung cấp trong chat. Người dùng đã xác nhận tiêu chí chốt phiếu áp dụng **WAREHOUSE_ADJUST theo AGENTS.md**, không khóa cứng theo `ROLE_WAREHOUSE_MANAGER`; `ROLE_ADMIN` luôn có toàn quyền.

| Tiêu chí / hạng mục | Backend | Frontend | Kết nối / ma trận quyền |
|---|---|---|---|
| Tạo phiếu theo kho hoặc nhóm hàng, chốt tồn sổ tại thời điểm tạo | POST `/warehouse/stock-counts`; snapshot từ tồn DB, đơn vị cơ sở, ID và phiên bản tồn; sinh mã tại server | Chọn kho và nhóm hàng bằng ID từ options; nhận mã, người tạo, thời điểm và tồn sổ từ server, không sinh snapshot bằng JavaScript | VIEW hoặc ADJUST, ADMIN; giới hạn kho được giao tại API; chỉ nạp API kiểm kê khi mở tab, không đòi PRODUCT_VIEW/ORDER_VIEW hoặc các API phiếu kho cũ |
| Nhập số đếm và tự tính chênh lệch từng dòng | Kiểm số >=0, tối đa 4 chữ số thập phân, chênh lệch = thực đếm - tồn sổ; từ chối dòng ngoài phiếu, trùng hoặc sai ID | Phân biệt trống/null với số 0, nhãn chưa đếm/khớp/thừa/thiếu, tính hiển thị theo cùng độ chính xác; ghi chú tối đa 500 ký tự | PUT `/{id}/items`; không thay tồn kho khi lưu nháp; backend luôn tính lại, không nhận chênh lệch do client tự gửi |
| Lưu/mở lại phiếu và lịch sử | Phiếu, snapshot, số đếm, ghi chú và trạng thái lưu DB; danh sách phân trang, chi tiết theo ID | Tạo, lưu nháp, reload, mở lại từ lịch sử, hủy phiếu nháp; chuyển kho không dùng lại phiếu kho cũ; giữ input/lý do khi API lỗi | GET options/list/detail, POST create/cancel và PUT items đúng hợp đồng mới; không lưu nghiệp vụ trong browser storage, không dùng `/warehouse/adjust` cho kiểm kê |
| Chốt về số thực đếm, bắt buộc lý do | POST `/{id}/complete`; tất cả dòng phải đã đếm, lý do không được trống; cập nhật tồn, trạng thái và audit trong cùng transaction | Chốt/nháp/hủy khóa trong lúc gửi; phiếu đã chốt/hủy chỉ đọc; báo riêng trường hợp ghi thành công nhưng tải lịch sử thất bại để tránh gửi lặp | WAREHOUSE_ADJUST hoặc ADMIN; backend kiểm lại quyền trên yêu cầu hiện tại, frontend đối chiếu quyền và canEdit/canAdjust từ server |
| Chống chốt đồng thời, ghi đè chuyển động và mất tồn giữ chỗ | Khóa phiếu -> kho -> các dòng tồn theo SKU tăng dần; kiểm snapshot ID/version/đơn vị/số lượng trước mọi ghi; thực đếm không thấp hơn lượng đang giữ chỗ; lỗi rollback toàn phiếu và audit | Hiện lỗi và giữ số đếm/lý do; nếu tồn đã đổi, yêu cầu hủy và lập phiếu mới thay vì âm thầm ghi đè | Đã kiểm chốt cùng phiếu, sửa/hủy cạnh tranh chốt, đặt giữ chỗ đồng thời, nhiều dòng lỗi và lỗi audit, chuyển động về lại số lượng cũ |
| Ma trận trên phiên đang mở | Grant/revoke trên JWT cũ có hiệu lực, không fallback role; nhiều role nhận hợp quyền, giới hạn kho được giao | Menu kiểm kê nhận VIEW hoặc ADJUST; chốt chỉ ADJUST/ADMIN; thu hồi quyền xóa dữ liệu nhạy cảm, vô hiệu phản hồi cũ; vai trò VIEWER được cấp ADJUST vẫn sử dụng được | Tiêu chí “Quản lý kho chốt” được diễn giải theo quyền chốt người dùng đã xác nhận, không kiểm vai trò cứng |
| Bố cục và tương thích | Giữ API/luồng các sprint khác | Giữ shared shell, sidebar/topbar và lề chung; bảng cuộn nội bộ; thêm menu kiểm kê mà giữ menu tồn tối thiểu/chuyển kho | Kiểm desktop/tablet/mobile; không đổi layout chung của các trang khác |

Kiểm chứng cuối: **649 test backend qua**, trong đó **51 test kiểm kê** (19 tích hợp HTTP/JWT/H2, 17 service, 9 controller, 6 security), chạy trên bản sao tạm, không sửa MySQL thật; **113 test JavaScript qua**; **10 test định tuyến/bảo vệ file qua**. Chrome với fixture HTTP qua tạo snapshot, lưu/mở lại nháp, zero/thập phân, lỗi giữ input, chốt/hủy, chống gửi lặp, lỗi tải lịch sử sau ghi, đổi kho, grant/revoke và phản hồi trễ, ADMIN, XSS, 6 kích thước. Đã xem ảnh desktop/mobile. Kiểm bố cục chung **150 kiểm tra = 25 trang × 6 kích thước**; hồi quy sổ tồn S5-05, chuyển kho S5-07 và điều hướng click/Back/Forward/Reload/mobile/reduced-motion qua, sidebar giữ scroll **472 -> 472**, không lỗi JavaScript.

Triển khai: cần khởi động lại backend để nhận entity/service mới và cập nhật schema theo cấu hình `ddl-auto=update`. Bổ sung `warehouse_inventories.version` (mặc định 0), metadata snapshot trên `warehouse_stock_count_items` (`snapshot_captured`, `snapshot_inventory_id`, `snapshot_inventory_version`) và nới ghi chú dòng lên 500 ký tự. Phiếu nháp cũ chưa có marker snapshot vẫn xem/hủy được nhưng cần lập lại để chốt an toàn, không tự gán tồn hiện tại vào snapshot cũ. Chưa nghiệm thu MySQL thật; không chạy SQL hay thay dữ liệu/schema DB đang dùng trong đợt kiểm thử này.

## S5-02 — Đặt lại đơn hàng cũ trên Cổng đại lý (backend) — 21/10/2026

Nguồn tiêu chí: User story S5-02 (*"Là Đại lý, tôi muốn đặt lại một đơn đã mua trước đây, để lấy hàng định kỳ mà không phải chọn lại từng mặt hàng"*). Thực hiện trên nhánh `feature/S5-02/dat-lai-don-da-mua`; phạm vi chỉ làm **backend** theo yêu cầu người dùng, tuân thủ AGENTS.md và ma trận phân quyền.

| Tiêu chí / hạng mục | Backend | Frontend | Kết nối / ma trận quyền |
|---|---|---|---|
| Chọn đơn cũ và đặt lại toàn bộ hoặc một phần dòng hàng | API xem trước `GET /api/portal/orders/{id}/reorder-preview` và tạo đơn `POST /api/portal/orders/{id}/reorder`. Hỗ trợ đặt lại toàn bộ (khi body `items` rỗng/null) hoặc đặt lại từng dòng đã chọn | Chưa làm (giữ nguyên theo yêu cầu "chỉ làm backend") | Preview yêu cầu `ORDER_VIEW` + `ORDER_CREATE` hoặc `ROLE_ADMIN`; Đặt lại yêu cầu `ORDER_CREATE` hoặc `ROLE_ADMIN`; tài khoản DEALER cách ly dữ liệu theo `dealerId`, đơn ngoài phạm vi trả 403 |
| Mặt hàng ngừng kinh doanh / hết bảng giá bị loại bỏ kèm cảnh báo | Phân loại `availableItems` vs `discontinuedItems` (kiểm tra `product.active == false` hoặc không có trong bảng giá hiệu lực hiện hành của nhóm khách hàng). Loại bỏ khỏi đơn mới và trả danh sách cảnh báo `warnings`/`excludedItems`; nếu toàn bộ bị loại bỏ trả 400 | Chưa làm (giữ nguyên theo yêu cầu "chỉ làm backend") | Kiểm tra trực tiếp từ bảng giá và trạng thái sản phẩm hiện hành trong DB; không tin dữ liệu client gửi |
| Áp lại giá theo bảng giá / chính sách hiện hành | Lấy bảng giá hiệu lực hiện hành theo nhóm khách hàng của đại lý tại thời điểm đặt lại; tính giá theo quy cách đơn vị quy đổi (`unitPrice = basePrice * factor`); không kế thừa giá cũ của đơn gốc | Chưa làm (giữ nguyên theo yêu cầu "chỉ làm backend") | Đơn giá được tính toán hoàn toàn phía server từ DB; preview hiển thị `originalUnitPrice` vs `currentUnitPrice` và cờ `priceChanged` |
| Đơn mới ở trạng thái Chờ duyệt | Đơn mới tạo thành công có trạng thái `PENDING_APPROVAL`, ghi nhận audit action `REORDER_FROM` kèm mã đơn gốc | Chưa làm (giữ nguyên theo yêu cầu "chỉ làm backend") | Quyền tạo đơn `ORDER_CREATE` kiểm tra động qua DB matrix; thu hồi quyền trên phiên JWT hiện tại lập tức chặn 403 không fallback role |

Kiểm chứng cuối: **24 test backend qua** (7 test service `OrderReorderServiceTest`, 14 test controller `DealerPortalControllerTest`, 3 test tích hợp `DealerPortalReorderIntegrationTest` trên HTTP/JWT/JPA/H2). Kiểm chứng đầy đủ: đặt lại toàn bộ/một phần, loại bỏ hàng ngừng kinh doanh / hết hiệu lực giá, tính lại giá theo bảng giá hiện hành, đơn mới tạo ở PENDING_APPROVAL, đại lý bị khóa/ngừng hoạt động trả lỗi, cách ly đa đại lý (đại lý khác truy cập trả 403), và thu hồi quyền `ORDER_CREATE` trên cùng token JWT lập tức trả 403 mà không fallback về role. Đã cập nhật hợp đồng tại `backend/API_DOCUMENTATION.md`.

## S5-08 — Kiểm kê kho và ghi nhận chênh lệch (Backend) — 21/10/2026

Nguồn yêu cầu: S5-08 theo ảnh mô tả. Đã đọc AGENTS.md và hai skill trong SKILLS. Thực hiện backend theo yêu cầu "chỉ làm backend".

| Tiêu chí / hạng mục | Backend | Frontend | Kết nối / ma trận quyền |
|---|---|---|---|
| Tạo phiếu kiểm kê theo kho hoặc theo nhóm hàng | Đã hoàn thành: `POST /api/warehouse/stock-counts`, chốt số lượng tồn sổ sách (`bookQuantity`) theo đơn vị cơ sở tại thời điểm tạo; lọc theo kho hoặc nhóm hàng; tự sinh mã `PKK-yyyyMMddHHmmssSSS`; trạng thái `DRAFT` | Chưa triển khai (chỉ làm backend theo yêu cầu) | Quyền `WAREHOUSE_VIEW` hoặc `WAREHOUSE_ADJUST` hoặc `ROLE_ADMIN`; kiểm tra phạm vi kho được phân công |
| Nhập số đếm thực tế, tính chênh lệch từng dòng | Đã hoàn thành: `PUT /api/warehouse/stock-counts/{id}/items`, nhận `actualQuantity` cho từng dòng sản phẩm, tự động tính `differenceQuantity = actualQuantity - bookQuantity` và lưu ghi chú dòng; chặn số lượng âm | Chưa triển khai (chỉ làm backend theo yêu cầu) | Quyền `WAREHOUSE_VIEW` hoặc `WAREHOUSE_ADJUST` hoặc `ROLE_ADMIN`; chỉ cập nhật khi phiếu ở trạng thái `DRAFT` |
| Chốt kiểm kê điều chỉnh tồn về số thực đếm và bắt buộc nhập lý do | Đã hoàn thành: `POST /api/warehouse/stock-counts/{id}/complete`, cập nhật tồn kho `WarehouseInventory.baseQuantity = actualQuantity`, bắt buộc nhập lý do `reason` (báo lỗi nếu để trống), chuyển trạng thái `COMPLETED`, ghi nhận AuditLog chi tiết | Chưa triển khai (chỉ làm backend theo yêu cầu) | Bắt buộc quyền `WAREHOUSE_ADJUST` hoặc `ROLE_ADMIN`; ghi nhận `completedBy`, `completedAt`, `adjustmentReason` |
| Chỉ Quản lý kho được chốt phiếu kiểm kê | Đã hoàn thành: Controller & Service bắt buộc quyền `WAREHOUSE_ADJUST` hoặc `ROLE_ADMIN`; Thủ kho (`ROLE_WAREHOUSE_STAFF`) không có quyền điều chỉnh bị chặn 403 Forbidden; thu hồi `WAREHOUSE_ADJUST` từ Quản lý kho có hiệu lực ngay lập tức (không fallback role) | Chưa triển khai (chỉ làm backend theo yêu cầu) | Ma trận phân quyền database-backed là nguồn sự thật duy nhất; đã kiểm thử cấp/thu hồi quyền trực tiếp trên MockMvc |
| Xem, tìm kiếm và hủy phiếu kiểm kê | Đã hoàn thành: `GET /options`, `GET /` (tìm kiếm/lọc kho, nhóm hàng, trạng thái, keyword, phân trang), `GET /{id}` (chi tiết kèm toàn bộ dòng kiểm kê), `POST /{id}/cancel` (hủy phiếu DRAFT) | Chưa triển khai (chỉ làm backend theo yêu cầu) | `WAREHOUSE_VIEW` hoặc `WAREHOUSE_ADJUST` hoặc `ROLE_ADMIN`; kiểm tra phạm vi kho |

Kiểm chứng: **28 test backend qua**, bao gồm 13 test `StockCountServiceTest` (tạo phiếu, snapshot tồn sổ theo kho/nhóm hàng, nhập số đếm, tính chênh lệch, chốt kiểm kê cập nhật tồn kho, bắt buộc lý do, chặn số âm/chưa đếm hết/phiếu đã chốt, kiểm tra quyền và phạm vi kho), 9 test `StockCountControllerTest` (endpoint REST, validation, HTTP status), 6 test `StockCountSecurityTest` (phân quyền ma trận, chặn 401 khi chưa đăng nhập, 403 khi thiếu quyền, chặn Thủ kho không được chốt kiểm kê, cho phép Quản lý kho và Admin chốt, thu hồi quyền WAREHOUSE_ADJUST lập tức trả 403).

## S5-09 — Tồn tối thiểu và cảnh báo (backend + frontend) — 20/10/2026

Nguồn AC: `4. Product Backlog!A62:F62`. Thực hiện trên nhánh `feature/S5-09-frontend-va-backend-canh-bao-ton-duoi-muc-toi-thieu`, nền `38b9795`; đã đọc AGENTS.md và hai skill trong SKILLS. Không merge/pull/lấy code nhánh khác, không tự commit.

| Tiêu chí / hạng mục | Backend | Frontend | Kết nối / ma trận quyền |
|---|---|---|---|
| Khai báo mức tối thiểu theo SKU và kho | Đã làm bảng riêng có unique SKU–kho, lượng decimal, đơn vị snapshot, người/thời điểm; transaction, khóa kho và audit | Đã làm `/warehouse/minimums`, menu **Kho hàng → Ngưỡng tồn tối thiểu**; tìm/lọc/phân trang, lưu/sửa/bỏ khai báo; lỗi lưu giữ input | GET options/list, PUT/DELETE `/warehouse/stock-minimums`; WAREHOUSE_MANAGE hoặc ADMIN; không cần VIEW/PRODUCT_VIEW và không lộ số tồn qua DTO cấu hình |
| Hàng dưới ngưỡng nổi bật trong sổ tồn | Bổ sung minimumQuantity/minimumUnit/minimumUnitMatches/belowMinimum và lọc server `belowMinimum=true` | Tô nền/viền cảnh báo kèm nhãn, có cột ngưỡng và bộ lọc; xuất Excel kèm ngưỡng/cảnh báo | WAREHOUSE_VIEW hoặc ADMIN; không thay ba lượng/trạng thái tồn S5-05, không tính nghiệp vụ bằng JS |
| Cảnh báo trên dashboard kho | GET `/warehouse/stock-alerts`, phân trang và phạm vi kho từ DB; không dùng cờ cache | Khối cảnh báo ở **Tổng quan**, tổng số SKU–kho và 5 dòng đầu, link mở sổ tồn đúng bộ lọc, tải lại/định kỳ 30 giây khi trang hiện | Chỉ WAREHOUSE_VIEW hoặc ADMIN; lỗi API không hiện thành không có hàng thiếu |
| Ngưỡng và thay đổi tồn | So tồn khả dụng (thực tế trừ giữ chỗ, không tính đang chuyển), nghiêm ngặt `<`; bằng ngưỡng/0/chưa khai báo không cảnh báo; SKU chưa có tồn = 0 mà không tự tạo tồn | Ghi rõ cơ sở so sánh; phân biệt chưa khai báo và mức 0; đơn vị đổi báo cần khai báo lại | Ngưỡng theo đúng kho/SKU/đơn vị; kho/SKU ngừng hoạt động không cảnh báo; không tự đổi snapshot cũ |
| Ma trận và phạm vi | Grant/revoke có hiệu lực ngay trên JWT cũ; MANAGE và VIEW độc lập; kho ngoài phạm vi trả 403; role kho chưa gán kho trả tập rỗng | Đồng bộ quyền trước nạp, thu hồi ẩn menu/đóng form/xóa dữ liệu cảnh báo; chặn trang cấu hình, không role fallback | Đã kiểm vai trò không mặc định (VIEWER), STAFF thiếu phân công rồi gán lại, Admin luôn được phép |
| Bố cục/tương thích | API tồn cũ giữ nguyên; cấu hình không sửa tồn thực tế/giữ chỗ | Dùng app-shell/app-navigation và lề chung, bảng cuộn nội bộ, form native dialog; desktop/mobile | Không lưu ngưỡng vào browser storage; tài liệu API và route đã cập nhật |

Kiểm chứng cuối: **583 test backend qua**, bao gồm 6 test S5-09 tích hợp HTTP/JWT/JPA/H2: ranh giới thập phân/equality, độc lập SKU–kho, mức 0/bỏ khai báo, tồn chưa phát sinh và biến động giữ chỗ, dữ liệu sai/đơn vị đổi/kho-SKU ngừng hoạt động, grant/revoke trên cùng token, scope kho và xóa hợp lệ SKU/kho không bị cấu hình cản trở. **88 test JS qua**, **8 test route/bảo vệ file liên quan qua**. Chrome `stock-minimums.browser.cjs` qua cấu hình/lưu/bỏ, giữ input khi lỗi, validation, XSS, lọc/tô cảnh báo sổ tồn, dashboard khi bổ sung hàng/lỗi API, live grant/revoke, không role fallback, ADMIN và 6 kích thước. Hồi quy `stock-ledger.browser.cjs` và `warehouse-transfers.browser.cjs` qua. Bố cục chung **144 kiểm tra trên 24 trang × 6 kích thước**; đã xem ảnh desktop/mobile/form 320px. Điều hướng chung qua click/Back/Forward/Reload/mobile/reduced-motion, sidebar giữ scroll **432 → 432**, không lỗi JS. `git diff --check` sạch. Bộ test route đầy đủ có assertion tên Cổng đại lý lệch có sẵn (xem các cập nhật trước); không đổi trang ngoài phạm vi.

Giới hạn: có bảng DB mới `warehouse_stock_minimums`; cần khởi động lại **backend** để tạo bảng theo `ddl-auto=update` và **server frontend** để nhận route. Chưa nghiệm thu MySQL thật, không thay dữ liệu/schema DB thật; kiểm tích hợp trên H2/MySQL-mode. Không triển khai kiểm kê S5-08 hoặc thay đổi nghiệp vụ giữ chỗ S5-06. Không tự commit/merge/push.

## S5-07 — Chuyển kho nội bộ (backend + frontend) — 20/10/2026

Nguồn tiêu chí: `4. Product Backlog!A60:F60`. Thực hiện trên nhánh `feature/S5-07-backend-va-frontend-chuyen_kho_noi_bo`, nền `develop` tại `c74485d`; không merge/pull/lấy code nhánh khác, không tự commit.

| Tiêu chí / hạng mục | Backend | Frontend | Kết nối và ma trận quyền |
|---|---|---|---|
| Chọn kho đi, kho đến, danh sách hàng và số lượng | Đã có; củng cố kiểm kho hoạt động/không trùng, SKU duy nhất, đơn vị quy đổi đóng băng, số lượng tối đa 4 số thập phân, mã tùy chọn/tự sinh | Đã làm màn `/warehouse/transfers`, menu **Kho hàng → Chuyển kho nội bộ**, tạo phiếu theo đơn vị cơ sở, tìm hàng theo mã/tên | POST `/warehouse/transfers`; options/products hẹp không đòi thêm PRODUCT_VIEW/WAREHOUSE_VIEW/ORDER_VIEW để tạo |
| Hàng đang chuyển chưa cộng kho đến | Trừ tồn thực tế kho đi, chỉ tăng inTransit ở kho đến; chặn dùng phần đã giữ chỗ cho đơn; khóa kho và dòng tồn | Giải thích rõ hai mốc xuất/nhận, hiển thị trạng thái từ API | Dùng cùng sổ tồn với S5-05, không suy lượng giữ chỗ bằng danh sách đơn trên trình duyệt |
| Kho đến xác nhận thực nhận | Khóa phiếu, cộng đúng thực nhận, gỡ lượng đang chuyển của phiếu; chặn thao tác lặp/đồng thời | Chi tiết có số đã chuyển/thực nhận, Điền nhận đủ và Xác nhận thực nhận | Chỉ WAREHOUSE_TRANSFER/ADMIN và kho đến trong phạm vi; server trả canReceive |
| Chênh lệch bắt buộc lý do | Thiếu/thừa/nhận 0 đều yêu cầu lý do; lưu theo dòng, snapshot hệ số, transaction rollback khi lỗi | Nhập và đối chiếu chênh lệch theo dòng; không mất số lượng/lý do khi POST lỗi | API kiểm lại tất cả dòng; chặn ID giả, trùng, mâu thuẫn hoặc thiếu dòng |
| Hủy khi đang chuyển | Bắt buộc lý do, hoàn tồn kho đi và gỡ inTransit; đã nhận/đã hủy không thao tác lại | Hiện thao tác theo quyền/phạm vi; xem lại lý do hủy | Chỉ WAREHOUSE_TRANSFER/ADMIN và kho đi trong phạm vi; server trả canCancel |
| Xem, tìm/lọc và phân trang | Danh sách/chi tiết chỉ phiếu có một đầu kho trong phạm vi; lọc kho/trạng thái/mã, phân trang server | Có bộ lọc, tải lại, phân trang, lỗi/rỗng rõ ràng, hủy phản hồi cũ | WAREHOUSE_VIEW hoặc WAREHOUSE_TRANSFER; quyền được cấp cho vai trò bất kỳ đều áp dụng; không role fallback |
| Lưu ma trận quyền và bố cục | JWT hiện tại bị thu hồi quyền trả 403; quyền role kho không thay thế quyền bị bỏ; ADMIN luôn được phép | Thu hồi TRANSFER thành chỉ đọc nếu còn VIEW; mất cả hai thì ẩn menu/chặn trang; đóng form/chi tiết và hủy read đang chờ | Dùng shell/topbar/transition chung, lề cân và bảng cuộn nội bộ; không lưu nghiệp vụ vào browser storage |

Kiểm chứng: **577 test backend qua**, bao gồm HTTP/JWT/JPA/H2 và các ca chuyển vượt tồn, cạnh tranh với giữ chỗ đơn hàng, nhận/hủy đồng thời, nhận thừa/thiếu, rollback nhiều dòng, snapshot hệ số và grant/revoke trên token cũ. **87 test JS qua**; test route S5-07/S5-05 qua. Chrome `warehouse-transfers.browser.cjs` qua luồng lập/nhận/hủy, XSS, lỗi/retry và giữ input, phản hồi cũ, ma trận/ADMIN, sáu kích thước 1920/1440/1024/768/390/320. Kiểm bố cục chung: **138 kiểm tra trên 23 trang × 6 kích thước**; đã xem ảnh desktop/mobile.

Kiểm tra điều hướng chung qua: click/Back/Forward/Reload, nội dung trang, mobile và reduced-motion; sidebar giữ scroll **392 → 392**, không có lỗi JS. **7 test route/bảo vệ file liên quan qua**, `git diff --check` sạch. Bộ route đầy đủ vẫn có assertion tên Cổng đại lý lệch trước đợt này, không sửa trang ngoài phạm vi.

Giới hạn: chưa chạy trên MySQL thật, không thay đổi dữ liệu/schema DB thật. Cần khởi động lại **backend và server frontend** để nhận service/API/route mới. Không thay đổi nghiệp vụ S5-06 hoặc triển khai kiểm kê/cảnh báo S5-08/09.

## S5-08 — Kiểm kê kho và ghi nhận chênh lệch (Frontend hoàn thiện) — 20/10/2026

User Story: Là Quản lý kho, tôi muốn thực hiện kiểm kê kho và ghi nhận chênh lệch, để đưa sổ sách về khớp thực tế và biết lệch ở đâu.
- Vị trí giao diện: Phân hệ **Kho hàng → Đơn vị tính / Kiểm kê kho** (`/warehouse/units?tab=stocktake`), tab **Kiểm kê kho** (`#tab-stocktake`).
- Tuân thủ ma trận phân quyền: `ROLE_ADMIN` toàn quyền; `ROLE_WAREHOUSE_MANAGER` yêu cầu quyền `WAREHOUSE_ADJUST`; các vai trò khác không được phép chốt phiếu.

| Hạng mục / Tiêu chí chấp nhận (AC) | Backend | Frontend | Kết nối / Ma trận quyền (BE ↔ FE) |
|---|---|---|---|
| **1. Khởi tạo phiếu kiểm kê (Freeze Snapshot)**: Chọn phạm vi kiểm kê toàn kho hoặc theo nhóm hàng, hệ thống chốt tồn sổ sách tại thời điểm tạo | Đã có sẵn: `GET /api/warehouse/inventory` (lấy tồn sổ thực tế) và `GET /api/warehouse/options` (danh sách kho). | Đã hoàn thành: Khởi tạo phiếu kiểm kê với mã `PKK-YYYYMM-XXX`, phạm vi Toàn kho hoặc Nhóm hàng; chốt đóng băng số tồn sổ sách (`bookQuantity = inventoryBaseQty`); khởi tạo số đếm thực tế bằng số tồn sổ, chênh lệch ban đầu 0 (`MATCH`). | Đã kết nối: Cần quyền `WAREHOUSE_VIEW` để truy cập tab và tải tồn kho; không phụ thuộc role cứng; thu hồi quyền xem ẩn tab kiểm kê. |
| **2. Kiểm đếm thực tế & tính chênh lệch từng dòng**: Nhập số thực tế đếm, hệ thống tự động tính toán chênh lệch (Khớp, Thừa, Thiếu) kèm nhãn trực quan | Không xử lý tính toán dòng hàng tạm thời (chỉ nhận kết quả khi chốt phiếu). | Đã hoàn thành: Tính chênh lệch `actualQuantity - bookQuantity` theo thời gian thực; hiển thị badge màu xanh lá (Khớp - 0), xanh ngọc (Thừa - `+X`), đỏ (Thiếu - `-X`); ô nhập ghi chú chênh lệch từng dòng; strip 4 thẻ chỉ số tổng quan (Tổng mặt hàng, Khớp, Thừa, Thiếu) cập nhật trực tiếp khi nhập. | Logic hoàn toàn reactive trên frontend; module hóa độc lập tại `frontend/js/warehouse-stocktake.js` phục vụ cả UI và unit test. |
| **3. Bắt buộc nhập lý do điều chỉnh**: Khi chốt phiếu kiểm kê, bắt buộc nhập lý do điều chỉnh tồn kho phục vụ kiểm toán | Kiểm tra `request.getAdjustmentReason()` hoặc ghi chú trong `WarehouseServiceImpl.recordVoucher`. | Đã hoàn thành: Validate bắt buộc nhập lý do vào ô `#stocktake-adjustment-reason` (chặn chuỗi rỗng / khoảng trắng); hiển thị cảnh báo toast nếu thiếu. | Đã kết nối: Lý do được gửi trong payload lên API `POST /api/warehouse/adjust` qua trường `adjustmentReason` và `note`. |
| **4. Chốt kiểm kê & Cân bằng tồn kho**: Điều chỉnh tồn sổ sách về số thực đếm, lưu phiếu điều chỉnh kho | Đã có sẵn: API `POST /api/warehouse/adjust` (`@PreAuthorize("hasRole('ADMIN') or hasAuthority('WAREHOUSE_ADJUST')")`), `type = ADJUSTMENT`, cập nhật `inventory.setBaseQuantity(baseQty)` cho từng mặt hàng khi không truyền delta. | Đã hoàn thành: Gửi request `POST /api/warehouse/adjust` với danh sách mặt hàng có `quantity` bằng số đếm thực tế; cập nhật trạng thái phiếu sang `RECORDED`; đưa vào lịch sử kiểm kê; tự động reload lại tồn kho và sổ cái. | Đã kết nối: API nhận payload, cập nhật `baseQuantity` trong bảng tồn kho khớp chính xác với số đếm thực tế; trả về voucher `PDC`/`PKK` trạng thái `RECORDED`. |
| **5. Phân quyền chốt phiếu (Chỉ Quản lý kho có quyền WAREHOUSE_ADJUST)**: Không cho phép vai trò khác chốt phiếu | `WarehouseController.adjustInventory` kiểm tra `@PreAuthorize("hasRole('ADMIN') or (hasAuthority('WAREHOUSE_ADJUST'))")`. | Đã hoàn thành: `canAdjustInventory` kiểm tra người dùng là `ROLE_ADMIN` hoặc (`ROLE_WAREHOUSE_MANAGER` có quyền `WAREHOUSE_ADJUST`). Nếu không đủ quyền: vô hiệu hóa nút `#btn-confirm-stocktake`, hiển thị cảnh báo `#stocktake-role-hint`, chặn thao tác click và validate. | Đã kết nối: Khi thu hồi quyền `WAREHOUSE_ADJUST` khỏi `ROLE_WAREHOUSE_MANAGER`, backend trả về 403 Forbidden và frontend vô hiệu hóa nút chốt phiếu ngay trên phiên hiện tại. ROLE_SALES, ROLE_WAREHOUSE_STAFF không được phép chốt phiếu. |
| **6. Lưu nháp tiến độ kiểm kê**: Cho phép lưu tạm tiến độ đếm mà không tác động tới tồn kho | Không đổi | Đã hoàn thành: Nút `#btn-save-draft-stocktake` lưu trạng thái đếm và ghi chú của các dòng hàng hiện tại; giữ trạng thái `DRAFT`; không gọi API ghi sổ điều chỉnh kho. | Lưu nháp an toàn cục bộ trong phiên làm việc của thủ kho / quản lý kho. |

Kiểm chứng: **92 test JavaScript qua** (toàn bộ test suite không có failure), bao gồm 6 bài test chuyên sâu cho S5-08 trong `tests/warehouse-stocktake-s5-08.test.cjs`:
1. Chốt số tồn sổ tại thời điểm tạo phiếu (Toàn bộ kho hoặc theo nhóm hàng).
2. Tự động tính chênh lệch từng dòng và tổng hợp metrics (Khớp, Thừa, Thiếu).
3. Bắt buộc nhập lý do khi chốt phiếu kiểm kê.
4. Xây dựng payload chuẩn cho API `POST /api/warehouse/adjust` đưa tồn kho về số thực đếm.
5. Kiểm tra nghiêm ngặt ma trận phân quyền: ROLE_ADMIN luôn được phép, ROLE_WAREHOUSE_MANAGER phải có WAREHOUSE_ADJUST, thu hồi quyền bị chặn ngay lập tức, các vai trò khác (STAFF, SALES) bị từ chối.
6. Chặn số lượng thực tế âm hoặc không hợp lệ.

## S5-05 — Sổ tồn theo SKU và kho (backend + frontend) — 20/10/2026

Nhánh `feature/S5-05-frontend-va-backend-xem_so_ton_kho_theoSKU_va_kho`, bắt đầu từ develop `b6b07a4`. Đã đọc AGENTS.md và cả hai skill trong SKILLS. Menu **Kho hàng → Tồn kho**, URL `/warehouse/inventory`; link/tab cũ `?tab=inventory` chuyển tới trang mới. Trang dùng app-shell, app-navigation và khung nội dung chung, không đặt outer width/gutter riêng.

| AC / hạng mục | Backend | Frontend | Kết nối / ma trận quyền |
|---|---|---|---|
| Ba cột tồn thực tế, giữ chỗ, khả dụng theo SKU/kho | API read-only mới `/api/warehouse/stock-ledger`; truy vấn DB phân trang, khả dụng không âm, không cộng hàng đang chuyển; SKU chưa có bản ghi tồn trả 0 qua LEFT JOIN, không ghi DB | Hiển thị ba cột riêng, đơn vị theo sổ tồn đã ghi nhận, kho và nhóm hàng ở từng dòng; không cộng tổng lượng giữa SKU khác đơn vị | `WAREHOUSE_VIEW` hoặc ADMIN ở cả controller/service và FE; không phụ thuộc PRODUCT_VIEW, ORDER_VIEW hoặc quyền nhập kho |
| Lọc kho, nhóm hàng, trạng thái tồn; tìm mã/tên | Lọc trên server, keyword không phân biệt hoa thường và escape `%/_/!`; trạng thái IN_STOCK/OUT_OF_STOCK/FULLY_RESERVED; metadata bộ lọc từ `/options` | Tìm theo mã/tên, lọc kết hợp, đặt lại, phân trang/đổi số dòng, phản hồi chậm không ghi đè bộ lọc mới | Tập kho lấy lại từ DB mỗi request; tài khoản đã gán kho chỉ xem kho được gán; role kho thiếu phân công không được fallback toàn hệ thống; ID kho ngoài phạm vi trả 403 |
| Lưu thay đổi ma trận quyền | Token đang đăng nhập mất WAREHOUSE_VIEW bị chặn 403 ở cả list/options; role kho/MANAGE/IMPORT không thay thế quyền này; ADMIN luôn được phép | Thu hồi quyền ẩn menu/chặn trang, xóa dòng đang xem và hủy yêu cầu/xuất đang chờ | Đã kiểm tài khoản STAFF chỉ có VIEW, revoke rồi grant lại cùng token; VIEWER chỉ có VIEW dùng được trang không cần role kho |
| Xuất Excel và trạng thái lỗi | Dùng cùng API và bộ lọc, mỗi trang export kiểm lại quyền/phạm vi | Xuất toàn bộ kết quả bộ lọc đã áp dụng, đủ ba lượng tồn; lỗi tải không biến thành tồn 0, có Tải lại; thiếu kho/không có kết quả có hướng dẫn rõ | Không trả giá bán/giá vốn; không lưu nghiệp vụ vào browser storage; đổi bộ lọc hoặc mất quyền hủy xuất |
| Tương thích và bố cục | Không thêm schema, seed, quyền mới; không đổi API inventory cũ phục vụ S4 | Giữ sidebar/topbar/chuyển cảnh chung, bảng cuộn bên trong trên màn hình nhỏ; các tab đơn vị tính/chứng từ/sổ cái cũ giữ nguyên | Không merge, pull, chuyển nhánh hoặc tự commit |

Kiểm chứng cuối: **68 test backend qua** (StockLedgerControllerTest 3, StockLedgerServiceTest 5, WarehouseControllerTest 10, WarehouseServiceTest 28, SprintMergeIntegrationTest 22). Tích hợp dùng HTTP/JWT/JPA/H2, kiểm lượng thập phân và đơn vị gốc, SKU chưa có tồn không tạo bản ghi, trạng thái, tìm ký tự đặc biệt, phân trang, phạm vi kho và thu hồi quyền trên phiên cũ. **86 test JS qua**; **6 test route/bảo vệ file liên quan qua**. Chrome `tests/stock-ledger.browser.cjs` kiểm ba cột/bộ lọc/phân trang/export toàn bộ, lỗi/retry/empty, phản hồi cũ, chống chèn HTML, sáu kích thước và grant/revoke/ADMIN. `tests/content-layout.browser.cjs`: **132 kiểm tra trên 22 trang × 6 kích thước**, lề cân và không tràn ngang toàn trang; đã xem ảnh desktop/mobile. `tests/navigation.browser.cjs` qua, sidebar giữ scroll 352→352 qua điều hướng; `git diff --check` sạch.

Giới hạn: chưa chạy với MySQL thật; không đổi dữ liệu DB thật. Cần khởi động lại **backend và server frontend** để nhận API/route mới. Không gộp cảnh báo tồn tối thiểu S5-09 vào trạng thái tồn S5-05. Case test route cũ lệch tên Cổng đại lý ngoài phạm vi đã được ghi ở phần S5-03; không sửa nội dung trang đó.

## S5-03 — Mã vị trí tùy chọn, tự sinh tại backend — 20/10/2026

| Hạng mục | Backend | Frontend | Kết nối / ma trận quyền |
|---|---|---|---|
| Tạo khu/kệ với mã để trống | Tự sinh theo từng kho: `KHU-001`, `KE-001`; lấy số lớn nhất của tiền tố hiện có rồi tăng, bỏ qua mã chữ tùy chỉnh; khóa ghi trên kho và unique constraint chống trùng đồng thời | Bỏ dấu bắt buộc và required của mã vị trí; có hướng dẫn để trống tự sinh, gửi mã rỗng và hiển thị mã từ API, không sinh ở JavaScript | Giữ `WAREHOUSE_MANAGE` hoặc ADMIN cho mọi POST/PUT; không thêm quyền/fallback role |
| Mã riêng và sửa vị trí | Mã riêng chỉ trim/viết hoa như trước, trùng trong cùng kho trả lỗi; khác kho được cùng mã; sửa với mã trống/null giữ mã hiện có | Cho nhập mã nhãn có sẵn; sửa mã để trống có giải thích giữ mã cũ | Đã kiểm cấp quyền rồi thu hồi ngay trên cùng token, POST mã trống trả 403 sau thu hồi |

Kiểm chứng lần bổ sung: **59 test backend qua** (12 controller, 28 service, 19 tích hợp H2/JWT/JPA). Kiểm bốn POST tạo kệ đồng thời nhận bốn mã khác nhau, mã tay `KE-009` khiến mã tiếp theo bắt đầu `KE-010`, kho khác bắt đầu `KE-001`, chuỗi quá 999, giữ mã khi sửa và chặn mã tay trùng. **85 test JavaScript qua**; Chrome kiểm form không bắt buộc mã, hiển thị mã backend trả về và giữ mã khi sửa trống, cùng các luồng/quyền/kích thước của trang. Đã xem ảnh form. Không thay schema hoặc MySQL thật; cần khởi động lại backend. **Mã kho vẫn nhập bắt buộc**, chỉ mã vị trí được tự sinh. Cập nhật hợp đồng tại backend/API_DOCUMENTATION.md.

## S5-03 — Frontend quản lý nhiều kho và vị trí lưu — 20/10/2026

Nhánh `feature/S5-03-frontend-quan_ly_nhieu_kho_va_vi_tri_luu`. Menu **Kho hàng → Kho & vị trí lưu**, URL `/warehouses`. Đọc AGENTS.md và cả hai SKILLS; tái sử dụng app-shell/app-navigation, nội dung nằm trong khung chung, không tạo lề/chiều rộng ngoài riêng.

| Hạng mục / AC | Backend | Frontend | Kết nối / ma trận quyền |
|---|---|---|---|
| Nhiều kho: mã, tên, địa chỉ, người phụ trách, trạng thái | Tái sử dụng GET/POST/PUT `/api/warehouses` | Danh sách, tìm kiếm/lọc trạng thái, thêm/sửa kho, người phụ trách nhập tên | Xem với `WAREHOUSE_VIEW` hoặc `WAREHOUSE_MANAGE`; thay đổi với `WAREHOUSE_MANAGE`; không cần quyền xem tài khoản |
| Vị trí lưu: khu/kệ trong từng kho | Tái sử dụng API vị trí; sửa bỏ cha bằng `parentLocationId=0`; chặn khu con/cycle và đổi khu đang chứa con thành kệ | Chọn kho xem vị trí; thêm/sửa khu/kệ, chọn khu cha trong cùng kho, mô tả và trạng thái | Cùng quyền xem/quản lý trên cả FE và BE; không tự đổi kiểu vị trí cũ khi sửa |
| Kho phục vụ mặc định của đại lý | Bổ sung projection tối thiểu GET/PUT `/api/warehouses/dealer-assignments`; lưu DB và audit; chặn kho ngừng hoạt động | Danh sách đại lý có tìm kiếm/phân trang, gán/chuyển kho mặc định, không sửa kho đơn hàng đã có | Chỉ `WAREHOUSE_MANAGE` hoặc ADMIN; không yêu cầu thêm quyền khách hàng/tài chính, không trả dữ liệu công nợ |
| Sửa lỗi audit khi gán kho | Mã cũ 24 ký tự vượt cột action VARCHAR(20), làm rollback; đổi thành `ASSIGN_WAREHOUSE`, cả luồng mới và endpoint khách hàng cũ | Báo thành công sau API thành công; giữ dữ liệu form và báo lỗi server nếu thất bại | Không đổi schema hoặc dữ liệu DB thật; không nới quyền endpoint khách hàng cũ |
| Cấp/thu hồi quyền trên phiên đang mở | API lấy quyền hiện tại, không fallback role quản lý kho | Đồng bộ trước khi ghi; thu hồi quyền đóng form và ẩn thao tác; giữ quyền xem thì chỉ đọc, mất cả hai quyền chuyển 403 | ADMIN vẫn toàn quyền; kiểm bằng tài khoản VIEWER chỉ có quyền được cấp, không có role thứ hai |

Kiểm chứng: **80 test backend qua**, gồm WarehouseResourceControllerTest, WarehouseServiceTest, DealerServiceTest và **18 test tích hợp HTTP/JWT/JPA/H2**, có gán kho/lưu vị trí và thu hồi quyền ngay trên cùng access token. **85 test JavaScript qua**. `tests/warehouses.browser.cjs` chạy Chrome/API mock kiểm thêm/sửa kho, khu/kệ, bỏ cha, gán đại lý, lỗi lưu, grant/revoke, readonly, ADMIN và 6 kích thước 1920/1440/1024/768/390/320. `tests/content-layout.browser.cjs` kiểm **126 trường hợp trên 21 trang × 6 kích thước**, khung cân lề, không tràn ngang toàn trang. Đã xem ảnh desktop/mobile. Thêm kiểm thử route `/warehouses` và legacy URL.

Giới hạn kiểm chứng: không chạy/lưu dữ liệu vào MySQL thật; backend sửa cần được khởi động lại để có endpoint mới. Bộ test route cũ có một assertion ngoài S5-03 đang chờ chuỗi “Cổng đại lý” trong khi trang hiện tại dùng “Cổng đặt hàng đại lý”; giữ nguyên nội dung trang đó, không coi lỗi có sẵn này là lỗi S5-03. Không merge, pull, đổi nhánh hay tự commit.

## Màu thông báo ma trận quyền — 20/10/2026

| Hạng mục | Backend | Frontend | Kết nối / phân quyền |
|---|---|---|---|
| Phản hồi lưu/khôi phục quyền | Không đổi | Thành công nền xanh nhạt/chữ xanh; không có thay đổi dùng màu trung tính; lỗi thật vẫn đỏ. Reset trạng thái màu theo từng thông báo, dùng role status/alert tương ứng | Giữ nguyên API, payload, kiểm quyền ADMIN và đồng bộ phiên; chỉ đổi hiển thị |

Chrome/API mock: `tests/permission-feedback.browser.cjs` kiểm lưu xanh, không thay đổi không gọi PUT, lỗi sau thành công đổi về đỏ, khôi phục mặc định xanh; đã xem ảnh. **84 test JS hiện có qua**. Không sửa hoặc lưu ma trận DB thật, giữ nguyên các thay đổi khác đang có.

## Rút gọn giá trị dài trong Nhật ký thao tác — 20/10/2026

| Hạng mục | Backend | Frontend | Kết nối / phân quyền |
|---|---|---|---|
| Giá trị trước/sau trong danh sách | Không đổi | Giới hạn nhãn 240px, hiển thị ellipsis khi dài; bỏ giới hạn % gây vòng lặp kích thước trong table auto-layout | Chỉ cách hiển thị; không cắt dữ liệu API hoặc đổi quyền truy cập nhật ký |
| Đọc đầy đủ | Không đổi | Tooltip chứa nguyên văn; nút Xem giữ toàn bộ trước/sau; bảng trong modal tự xuống dòng kể cả chuỗi không có dấu cách | Giữ escape HTML cả nội dung lẫn thuộc tính title; không đổi dữ liệu xuất Excel |

Kiểm chứng: `tests/audit-values.browser.cjs` chạy Chrome/API mock ở **1920/1440/390/320px**, chuỗi hàng nghìn ký tự có/không có khoảng trắng, giá trị ngắn và null: ellipsis hoạt động, bảng không bị kéo rộng hàng nghìn pixel, toàn trang không tràn ngang, popup còn nguyên dữ liệu và không chèn được HTML. Đã xem ảnh desktop/mobile; **84 test JS hiện có qua**. Không sửa backend/DB, giữ nguyên các thay đổi Cổng đại lý đang có trong worktree.

## Cổng đại lý dùng menu và khung nội dung chung — 20/10/2026

Phần này thay thế mô tả cổng đại lý “standalone” ở các mốc trước bên dưới.

| Hạng mục | Backend | Frontend | Kết nối / phân quyền |
|---|---|---|---|
| Menu trên Cổng đại lý | Không đổi | Dùng `app-shell.js` và `app-navigation.js`; có sidebar, breadcrumb, profile/logout như các trang khác; khung `.content` dùng lề/chiều rộng chung | Menu và guard dùng ma trận hiện tại; ADMIN thấy chức năng quản trị, DEALER không nhận thêm quyền |
| Giỏ hàng và ngữ cảnh đại lý | Không đổi | Chuyển các node điều khiển vào slot thanh trên, không clone/trùng ID; giữ chọn đại lý ADMIN, catalog, giỏ và preview | Giữ toàn bộ endpoint `/portal/*`, không gọi API thiếu ngữ cảnh và không đổi phạm vi dữ liệu |
| Điện thoại | Không đổi | Nút mở/đóng menu chung; thanh giỏ dưới thuộc vùng nội dung, trở thành inert khi menu mở; drawer/modal ở ngoài shell để không bị khóa theo menu | Thu hồi ORDER_CREATE khi đang mở cổng vẫn chuyển 403, không chỉ ẩn một nút |

Kiểm chứng: **84 test JS qua**. Chrome thật/API mock kiểm ADMIN chọn đại lý rồi tải catalog, DEALER có menu đúng quyền, giỏ mở từ thanh trên và thanh dưới, mobile menu mở/đóng, thu hồi quyền và các luồng S4 hiện có; không pageerror. Đã xem ảnh desktop và mobile. Bài căn lề mở rộng thành **20 trang/tab × 6 kích thước = 120 ca qua**, gồm Cổng đại lý tại 1920/1440/1024/768/390/320px; hồi quy điều hướng, giữ cuộn và transition chờ quyền qua. Không thay đổi backend, không chạy/sửa DB thật.

## Menu đứng yên, chỉ chuyển cảnh nội dung — 20/10/2026

| Hạng mục | Backend | Frontend | Kết nối / phân quyền |
|---|---|---|---|
| Giữ menu khi đổi chức năng | Không đổi | Giữ snapshot menu cũ đứng yên, không fade; chỉ nhả sau khi menu trang mới khôi phục cuộn và đồng bộ quyền xong | Giữ `Auth.ready`, menu inert khi chờ; không cấp quyền bằng snapshot |
| Chỉ chuyển cảnh phần trắng | Không đổi | Đặt tên transition trên khung nội dung thực, không chụp cả `.main-area`; fade 100–160ms, không trượt; topbar nằm trong nền tĩnh để tránh blur chữ | Không sửa DOM form, replay script, gom trang thành SPA hay cache dữ liệu nghiệp vụ |
| Mạng chậm / chuyển nhanh | Không đổi | Watchdog 1 giây chỉ kết thúc giữ ảnh, không mở khóa quyền; xử lý transition bị hủy và trang đang parse; callback cũ không phá transition mới | Backend vẫn kiểm ma trận ở từng request; frontend vẫn chặn 403 khi bị thu hồi quyền |
| Reload / Back / Forward / mobile | Không đổi | Giữ điều hướng HTML gốc, history và vị trí menu; giảm chuyển động tắt transition; browser không hỗ trợ dùng tải trang bình thường | Không can thiệp liên kết, submit, tải file hay API; không sửa DB |

**Lưu ý chính xác về kiến trúc:** đây là giữ nguyên menu **về hiển thị**, không phải chỉ fetch một mảnh HTML. Trình duyệt vẫn nạp tài liệu đích và các script khởi tạo riêng như cũ, nên không phải di trú vòng đời của từng trang sang SPA/PJAX. Dùng [cross-document view transitions của trình duyệt](https://developer.chrome.com/docs/web-platform/view-transitions/cross-document); không cam kết hiệu ứng này trên browser không hỗ trợ.

Kiểm chứng: **84 test JS qua**. Chrome thật/API mock: `tests/menu-transition.browser.cjs` xác nhận menu cũ opacity 1 và đứng yên khi chờ quyền, menu mới opacity 0, chỉ khung nội dung có tên transition, cuộn 312px → 312px; form tạo tài khoản mở được, watchdog không mở menu khi quyền chưa đồng bộ, chuyển trang liên tiếp và thu hồi quyền của VIEWER đều qua, không pageerror. Đã xem ảnh giữa/sau chuyển cảnh và sửa lỗi topbar bị blur trong snapshot. `tests/navigation.browser.cjs` qua Back/Forward/Reload/mobile/reduced-motion; `tests/s4-workflows.browser.cjs` qua giỏ/xác nhận/vòng đời/thu hồi quyền; `tests/content-layout.browser.cjs` qua **114 ca căn lề**. Không thử MySQL thật, không thay đổi logic backend, không merge/chuyển nhánh.

## Đồng bộ căn lề các trang trong menu — 20/10/2026

| Hạng mục | Backend | Frontend | Kết nối / phân quyền |
|---|---|---|---|
| Khung nội dung chung | Không thay đổi | Căn giữa cùng khung tối đa 1.240px như Chiết khấu sản lượng; lề tối thiểu 28px desktop, 16px mobile; đồng nhất khoảng cách trên/dưới | Chỉ CSS, giữ nguyên API, page guard và nội dung; không đổi sidebar/topbar |
| Các trang đang lệch trái | Không thay đổi | Áp dụng cho Tổng quan/Hạn mức công nợ, hồ sơ, sản phẩm/nhóm hàng, đơn hàng/tạo/duyệt/đơn đại lý, bảng giá/chiết khấu, đại lý/NCC, bốn tab kho, tài khoản và nhật ký | Không thêm quyền, không đổi điều kiện hiển thị menu/action; các bảng rộng vẫn cuộn trong bảng, không che cột bằng CSS |
| Bộ lọc Đơn hàng trên điện thoại | Không thay đổi | Cho hai trường ngày tự xuống dòng; bỏ flex-basis dạng hàng khi toolbar chuyển thành cột để không tạo chiều cao trống lớn | Giữ đủ trường lọc và sự kiện, không đổi logic tìm/lọc |

Kiểm chứng: `tests/content-layout.browser.cjs` chạy Chrome thật với API mock, **19 trang/tab × 6 chiều rộng (1920/1440/1024/768/390/320) = 114 ca qua**: lề trái/phải bằng nhau, đủ gutter, khung không vượt 1.240px, không tràn ngang toàn trang; sidebar giữ 260px, không pageerror. Đã xem ảnh desktop và mobile. Cổng đại lý độc lập, trang auth/in và modal không bị áp khung này.

Hồi quy: **79 test JS qua**, bài browser giữ cuộn menu **312px → 312px**, Back/Forward/Reload/mobile/reduced-motion qua; bài browser S4 (giỏ, xác nhận, vòng đời, thu hồi quyền đang mở trang) qua. Không thay đổi backend/DB; chưa kiểm mọi browser hoặc dữ liệu thật.

## Sửa các phát hiện còn lại Sprint 4 — 20/10/2026

Phần này cập nhật kết quả sau audit bên dưới; các nhận xét “chưa sửa” trong audit là mốc trước lần sửa này.

| Story | Backend | Frontend | BE ↔ FE / ma trận quyền |
|---|---|---|---|
| S4-01 | Đồng nhất giá ngày đặt với preview/catalog, không đổi giá theo ngày giao; chặn sản phẩm ngừng bán và pricing ngoài đại lý được giao | Dùng lại API tính giá | ORDER_CREATE và phạm vi dữ liệu hiện hành; API không tin giá client |
| S4-02 | Công nợ/quá hạn không biến mất khi soạn/xuất/giao/đóng đơn; summary đại lý dùng cùng tập trạng thái | Hiển thị dữ liệu nợ server | Giữ FINANCE_VIEW_DEBT và ORDER_CREATE cho các luồng tương ứng |
| S4-03 | Tạo/tính đơn/kiểm tồn dùng đúng kho phục vụ; chặn đổi kho, kho thiếu hoặc ngừng hoạt động; giữ cơ chế khóa tồn hiện có | Nhận lỗi có lý do từ API, không chọn kho thay đại lý | Quyền không thay thế ràng buộc kho; có kiểm thử HTTP sai kho và đồng thời |
| S4-04 | Không phát hiện thêm lỗi cần sửa trong lần rà này | Giữ lịch sử mua hàng | Giữ CUSTOMER_VIEW, giới hạn đại lý |
| S4-05 | Bỏ fallback duyệt từ ROLE_SALES/ORDER_CREATE và lời gọi không xác thực; xác nhận thường tách endpoint riêng | Luồng duyệt/từ chối/trả lại giữ nguyên | ORDER_APPROVE; quyền xác nhận không được xử lý vi phạm |
| S4-06 | Trả bước vòng đời tiếp theo theo quyền và kho của người gọi | Thêm nút chuyển bước trong Đơn hàng → Chi tiết; tải lại tracking sau thao tác | WAREHOUSE_EXPORT + kho được giao; bỏ quyền 403 và ẩn nút |
| S4-07 | Giữ danh sách, bộ lọc, tổng tập lọc | Tải lại khả năng thao tác khi quyền đổi | ORDER_VIEW, không dùng role để cấp quyền |
| S4-08 | Giữ in/PDF/barcode và giới hạn dữ liệu | Không đổi | ORDER_VIEW; không phát hiện thêm lỗi trong các ca hồi quy |
| S4-09 | Giữ copy nháp và tính lại giá/chiết khấu | Không đổi | ORDER_CREATE + ORDER_VIEW; các ca hồi quy hiện có qua |
| S4-10 | Thêm preview server; ORDER_CONFIRM riêng, đúng đại lý phụ trách; kiểm công nợ/tồn trước xác nhận | Giỏ hiển thị chiết khấu/tổng/nợ từ server; chặn preview lỗi; chống phản hồi trễ; giá đổi cần xác nhận lại | Preview dùng ORDER_CREATE + PRODUCT_VIEW + ORDER_VIEW; xác nhận ORDER_CONFIRM, không cấp quyền duyệt ngoại lệ |

**Triển khai:** Với ma trận đã lưu trong DB, Admin phải tích **Xác nhận đơn đại lý** (`ORDER_CONFIRM`) cho vai trò cần xác nhận rồi lưu. Không tự thêm quyền vào cấu hình cũ, không sửa DB thật, không merge/chuyển nhánh.

Kiểm chứng: **538 test backend, 79 test JS, 5 test định tuyến qua**, không failure/error/skip; hai bài Chrome hồi quy điều hướng và luồng S4 đều qua. Chi tiết và giới hạn trong [báo cáo sửa Sprint 4](docs/SPRINT_4_FIXES_20261020.md). Chưa tuyên bố nghiệm thu dữ liệu MySQL thật hoặc tích hợp thu tiền; đóng đơn không tự coi là thanh toán.

## Đối chiếu Excel Sprint 4 và sửa chuyển trang — 20/10/2026

Đã đọc sheet `4. Product Backlog`, `A42:F51`, đối chiếu AC với BE/FE trên nhánh hiện tại (không lấy cột trạng thái Excel làm trạng thái code).
**Chưa thể nghiệm thu toàn Sprint 4**: S4-01/04/05/06/07/08/09 cơ bản có luồng chính; S4-02 còn tính nợ chỉ từ RECORDED, S4-03 chưa ràng buộc kho client với kho phục vụ đại lý, S4-10 còn lệch xác nhận của sales và tổng giỏ khi có chiết khấu. Đây là phát hiện audit, chưa sửa nghiệp vụ trong đợt sửa chuyển trang.

| Hạng mục | Backend | Frontend | BE ↔ FE / quyền |
|---|---|---|---|
| Menu tự nhảy lên đầu | Không đổi | Đã sửa giữ tọa độ cuộn khi bấm/chuyển trang, Back/Forward/Reload; không dựng lại menu nếu không đổi | Giữ Auth.ready, menu inert trong lúc đồng bộ, guard theo ma trận; có test thu hồi quyền |
| Giật/chớp chuyển trang | Không đổi | Đã thêm transition gốc của browser chỉ cho nội dung trắng, ổn định scrollbar, xử lý transition hủy; giảm chuyển động tắt animation | Không đổi API, form và logic nghiệp vụ; vẫn load trang HTML, không giả chuyển thành SPA |
| Audit S4-01–10 theo Excel | Đã đọc code/test | Đã đọc luồng gọi API/UI | 7 story cơ bản đạt luồng chính, 3 story còn vấn đề; xem báo cáo để phân biệt AC, quyền và phạm vi dữ liệu |

Kiểm chứng đợt này: **529 test backend, 74 test JS, 5 test định tuyến đều qua**. Chrome thật với API mock: 1440×800 và 390×844, menu giữ **312px → 312px**, Back/Forward/Reload, nội dung trang, menu mobile và reduced-motion qua, không pageerror. Không sửa DB thật.
Chi tiết và bằng chứng: [báo cáo đối chiếu Sprint 4](docs/SPRINT_4_AUDIT_20261020.md).

## Sửa ma trận quyền — 20/10/2026

Phần này thay thế các nhận xét **phân quyền** cũ ngày 19/10 bên dưới cho những đường truy cập được liệt kê; không thay thế nghiệm thu nghiệp vụ từng story.

| Phạm vi | Backend | Frontend | BE ↔ FE / ma trận quyền |
|---|---|---|---|
| Lưu ma trận | Đã sửa: lưu nhiều vai trò trong một transaction, flush DELETE trước INSERT; chỉ đổi cache sau khi lưu thành công; giữ trạng thái vai trò không có quyền | Đã sửa: gửi một yêu cầu, chỉ gồm các vai trò thay đổi | Đã nối `PUT /admin/permissions`; chỉ ADMIN được lưu; ADMIN luôn toàn quyền |
| Phiên đang đăng nhập | JWT nạp quyền hiện tại từ ma trận cho từng request; thêm endpoint đọc quyền của chính phiên | Đồng bộ khi mở trang, focus và mỗi 15 giây; cập nhật menu, guard và các nút liên quan; không reload trang chỉ vì quyền không thay đổi | Đã nối `GET /auth/permissions`; đã test cùng token: cấp → bỏ quyền/403 → cấp lại |
| S4-01–03: tạo đơn, giá, công nợ, tồn | Các API đặt hàng và tham chiếu kiểm quyền; không dùng role để bù quyền thiếu; sửa hồ sơ đại lý không được đổi hạn mức/ngày nợ nếu thiếu FINANCE_MANAGE | Đọc sản phẩm tham chiếu theo ORDER_CREATE thay vì buộc PRODUCT_VIEW; quản lý công nợ theo FINANCE_MANAGE | Đã nối; giữ phạm vi đại lý/kho theo dữ liệu |
| S4-04, S4-07–09: lịch sử, danh sách, in, sao chép | ORDER_VIEW để xem/in; mọi đường sao chép đơn gần nhất cần cả ORDER_CREATE và ORDER_VIEW | Menu theo quyền, không chặn Thủ kho bằng role; tạo đơn không tự gọi lịch sử khi thiếu ORDER_VIEW | Đã nối cả API sao chép cũ `/orders/dealer/{id}/last-order-to-draft` |
| S4-05–06: duyệt, hủy, vòng đời | ORDER_APPROVE / ORDER_CANCEL / ORDER_VIEW; chuyển vòng đời và cập nhật giao hàng dùng WAREHOUSE_EXPORT, WAREHOUSE_IMPORT không thay thế quyền xuất | Nút duyệt/hủy theo quyền hiện tại; guard trang kiểm cả quyền xem lẫn thao tác | Đã nối; tiếp tục kiểm phạm vi đơn/kho và điều kiện trạng thái |
| S4-10 và cổng theo dõi | Giữ quyền ORDER_CREATE / ORDER_VIEW / PRODUCT_VIEW và phạm vi đại lý liên kết; tài khoản nhiều vai trò không bị filter cổng chặn toàn bộ API nội bộ | Đại lý chỉ có một vai trò chỉ thấy menu cổng; tài khoản nội bộ được cấp quyền không bị menu role cứng chặn | Đã nối; đây là sửa quyền, chưa bổ sung các AC nghiệp vụ còn thiếu của S4-10 |
| Danh mục hỗ trợ | CRUD sản phẩm/nhóm theo PRODUCT_CREATE/EDIT/DELETE; bảng giá/chiết khấu theo PRODUCT_EDIT; nhà cung cấp/đơn vị theo WAREHOUSE_MANAGE; nhập/xuất/duyệt phiếu theo quyền riêng | Thay role cứng bằng quyền thao tác; ẩn/đóng các nút và hộp sửa liên quan khi thu hồi quyền | Đã nối; dữ liệu lookup không trả giá vốn/doanh thu; giá vốn không còn tự cấp theo role quản lý |
| Tài khoản | USER_VIEW/CREATE/EDIT/LOCK và USER_ASSIGN_ROLE; sửa tài khoản không được đổi vai trò nếu thiếu USER_ASSIGN_ROLE | Danh sách theo USER_VIEW; tạo/import cần USER_CREATE + USER_ASSIGN_ROLE; checkbox vai trò bị khóa nếu không được gán | Đã nối; quản lý ma trận và nhật ký hệ thống vẫn dành riêng ADMIN |

Quyền đọc và quyền sửa là độc lập: muốn thao tác qua trang danh sách cần quyền xem trang đó cùng quyền thao tác. Người có nhiều vai trò nhận hợp quyền, nên bỏ quyền ở một vai trò không thu hồi quyền vẫn được cấp bởi vai trò khác. Đại lý một vai trò vẫn bị giới hạn trong cổng riêng; tick quyền không mở dữ liệu nội bộ ngoài phạm vi này.

Kiểm chứng: `mvn -q test` **529/529**, `node --test tests/*.test.cjs` **68/68**; kiểm cú pháp JavaScript và `git diff --check` đều qua. Có kiểm thử HTTP + Spring Security + database H2 thật cho lưu ma trận, giữ token cũ, thu hồi/cấp lại quyền; kiểm thử vai trò rỗng qua nạp lại cấu hình, hợp quyền nhiều vai trò, lưu thất bại không đổi cache, và phiên frontend tự ẩn/hiện nút. Các fixture bảo mật cũ được sửa để cấp authority rõ ràng; không dùng role mặc định làm bằng chứng được cấp quyền. Chưa thao tác thay đổi ma trận/database thật của người dùng, chưa kiểm thử trực tiếp toàn bộ màn hình trên trình duyệt.

**Chưa thể nghiệm thu toàn bộ sprint 1–5.** Có backend hoặc giao diện chưa đồng nghĩa với đủ tính năng, nối đúng và kiểm quyền đầy đủ.

- Nội dung backlog có **48 story**, từ S1-01 đến S5-09. Tiêu đề ghi 76 story/8 sprint nhưng không có nội dung sprint 6–8.
- Bản tích hợp chứa 13 PR: **#78, #82, #87, #91, #92, #95, #96, #97, #98, #99, #100, #101, #102**. PR #95 và #100 được chuyển từ `main` sang `develop`.
- **501 test backend, 62 test JavaScript và 5 test định tuyến đều qua**; `mvn verify` tạo được JAR. Có 7 test tích hợp HTTP + Spring Security + H2 thật, không mock service/repository.
- Chưa nghiệm thu trên MySQL thật, SMTP thật, toàn bộ giao diện 360px hoặc tệp 5.000 SKU trong đợt này.

## Sửa lỗi 19/10/2026 — Admin mở “Đơn hàng của tôi” và “Cổng đại lý” bị 403

Nguyên nhân: menu/guard cho phép ADMIN nhưng API yêu cầu tài khoản gắn với một hồ sơ đại lý.
Admin giờ chọn đại lý từ DB trên cả hai trang; cùng `dealerId` được gửi qua danh sách,
chi tiết, catalog, hồ sơ, điểm giao, công nợ và POST đặt/đặt lại. Không cần sửa liên kết
tài khoản admin; ngữ cảnh chọn nằm trong URL và không lưu vào localStorage/sessionStorage.

| Hạng mục | Backend | Frontend | BE ↔ FE | Ma trận quyền BE / FE |
|---|---|---|---|---|
| Admin xem đơn đại lý | Đã xong: chọn ngữ cảnh, lọc đúng đại lý, chặn chi tiết ngoài ngữ cảnh | Đã xong: bộ chọn, hướng dẫn khi chưa chọn, tải danh sách/chi tiết | Đã nối `/portal/dealers`, `/portal/orders`, `/portal/orders/{id}` và query `dealerId` | Đã đối chiếu: ADMIN có toàn bộ quyền; DEALER cần ORDER_VIEW và đúng đại lý liên kết |
| Admin đặt hàng trên cổng | Đã xong: hồ sơ/catalog/công nợ/điểm giao theo đại lý đã chọn; mọi đơn cổng chờ duyệt, chưa giữ tồn, ghi người thực hiện thật | Đã xong: bộ chọn và giỏ hàng gửi đúng ngữ cảnh | Đã nối GET cổng và POST `/portal/orders?dealerId=…` | Đã đối chiếu: menu + guard FE cần ORDER_CREATE, ORDER_VIEW, PRODUCT_VIEW; BE kiểm đúng quyền của từng API, ADMIN được hỗ trợ |
| Đặt lại đơn hàng | Đã xong: dùng luồng tạo đơn cổng, kiểm đại lý và tính giá server | Đã xong: giữ ngữ cảnh ở chi tiết/catalog/POST, ẩn nút khi thiếu ORDER_CREATE hoặc PRODUCT_VIEW | Đã nối qua cùng API cổng | Đã đối chiếu: ORDER_VIEW để xem đơn, ORDER_CREATE để gửi đơn, PRODUCT_VIEW để đọc giá hiện hành |
| Chống đổi đại lý / thu hồi quyền | Đã xong: DEALER đổi query sang đại lý khác bị 403; danh sách lựa chọn chỉ ADMIN; thu hồi quyền có hiệu lực trên token đang dùng | Đã xong: DEALER không có bộ chọn và không gửi ngữ cảnh URL giả; menu/guard/nút theo quyền | Đã kiểm bằng HTTP + Security + H2 và test JavaScript | Đã kết nối cho phạm vi sửa lỗi này; các hạn chế ma trận ở phần sprint bên dưới vẫn còn |

Kiểm chứng: `mvn verify` **501/501**, `node --test tests/*.test.cjs` **62/62**,
test định tuyến Python **5/5**, kiểm cú pháp JS và `git diff --check` qua.
Test mới xác nhận admin không liên kết đại lý vẫn mở/chọn/xem/đặt/đặt lại; API thiếu
ngữ cảnh trả 400 hướng dẫn chọn, ID không tồn tại trả 400, truy cập sai đại lý hoặc
thu hồi quyền trả 403. Chưa thử trực tiếp bản sửa trên trình duyệt/MySQL thật.
Đã cập nhật [hợp đồng API](backend/API_DOCUMENTATION.md) và [đường dẫn frontend](docs/FRONTEND_ROUTES.md).

## Cách đọc bảng

**Có** = có mã triển khai; **một phần** = thiếu nghiệp vụ của story; **chưa** = chưa có triển khai đáp ứng story. Cột kết nối nói về hợp đồng API/luồng sử dụng, không thay thế nghiệm thu AC. “Quyền một phần” nghĩa là đã kiểm vai trò nhưng chưa chặn đồng bộ ở BE/FE khi thu hồi quyền trong ma trận.

## Sprint 1 — Tài khoản và phân quyền

| Task | Tính năng | Backend | Frontend | BE ↔ FE | Quyền BE / FE | Nghiệm thu / còn thiếu |
|---|---|---|---|---|---|---|
| S1-01 | Đăng nhập, khóa sau 5 lần sai | Có | Có | Đã nối `/auth/login` | Công khai trước đăng nhập | Đã sửa thông báo sai mật khẩu giống tài khoản không tồn tại; giữ khóa 15 phút. Có test. |
| S1-02 | Gia hạn phiên, đăng xuất | Có | Có | Đã nối refresh/logout | JWT, blacklist, trạng thái tài khoản | FE còn lưu token/hồ sơ trong `sessionStorage`, trái skill backend; refresh token có hạn cố định. |
| S1-03 | Quên và đặt lại mật khẩu | Có | Có | Đã nối forgot/reset | Token 30 phút, dùng một lần theo service | Có test; cần thử nhận email SMTP thật. |
| S1-04 | Đổi mật khẩu | Có | Có | Đã nối từ hồ sơ | Người đăng nhập; tăng `tokenVersion` | Kiểm mật khẩu hiện tại/độ mạnh, thu hồi phiên cũ. Có test. |
| S1-05 | Ma trận quyền | Một phần | Có | Đã nối đọc/lưu ma trận | **Chưa đồng bộ toàn hệ thống** | Nhiều API dùng quyền hoặc vai trò nên gỡ quyền vẫn gọi được. Đã chặn giá vốn/biên lợi nhuận với ADMIN; cổng có test thu hồi quyền thật. |
| S1-06 | Menu theo quyền, mobile | Có | Có | Menu lấy quyền từ phiên | Một phần, phụ thuộc S1-05 | Đã thêm quyền menu Đơn hàng/Duyệt đơn, sửa menu cổng. Cần kiểm toàn bộ trang ở 360px. |
| S1-07 | Trang lỗi chung | Có | Có | Đã nối 403/404/phiên hết hạn | Route lỗi/xác thực | Có trang chung và hành động quay lại; test định tuyến qua. |
| S1-08 | CRUD, tìm và phân trang tài khoản | Có | Có | Đã nối `/admin/users` | ADMIN hai phía | Có gửi mật khẩu tạm; cần SMTP thật. Đã nối chọn đại lý cho tài khoản đại lý. |
| S1-09 | Nhiều vai trò, kho/địa bàn | Một phần | Có | Đã nối dữ liệu gán | ADMIN; kiểm kho bắt buộc, chống tự bỏ ADMIN | Gán được kho nhưng service phiếu kho/chuyển kho chưa giới hạn đủ theo kho được giao. |
| S1-10 | Khóa/mở tài khoản, bàn giao | Có | Có | Đã nối lock/unlock | ADMIN; thu hồi phiên server | Có lý do, tăng phiên bản token, cảnh báo đại lý cần bàn giao; có test. |

## Sprint 2 — Danh mục và bảng giá

| Task | Tính năng | Backend | Frontend | BE ↔ FE | Quyền BE / FE | Nghiệm thu / còn thiếu |
|---|---|---|---|---|---|---|
| S2-01 | Nhập người dùng Excel | Một phần | Có | Đã nối mẫu/preview/import | ADMIN hai phía | `importUsers()` gọi `create()` cùng service nên không qua transaction proxy; lỗi mail có thể để lại tài khoản dù báo dòng nhập thất bại. |
| S2-02 | Cập nhật hồ sơ | Có | Có | Đã nối `/users/profile` | Chỉ hồ sơ người đăng nhập | DTO chỉ sửa tên/điện thoại, kiểm định dạng; có test. |
| S2-03 | Ảnh đại diện | Có | Có | Đã nối upload/đọc ảnh | Người đăng nhập | JPG/PNG, 2 MB, ảnh vuông/thumbnail có service và test. |
| S2-04 | Nhật ký tồn, giá, công nợ, hóa đơn | Một phần | Có | Đã nối `/admin/audit-logs` | ADMIN hai phía | Có lọc/snapshot nhiều luồng; sửa công nợ qua hồ sơ đại lý vẫn tránh được lý do/audit công nợ. |
| S2-05 | Sản phẩm/SKU | Có | Có | Đã nối CRUD/ảnh/trạng thái | Một phần về thu hồi quyền | SKU duy nhất, chặn xóa theo giao dịch thật; giá vốn chỉ QL kinh doanh. |
| S2-06 | Nhóm hàng nhiều cấp | Có | Có | Đã nối cây/chuyển nhóm/doanh số | Một phần về thu hồi quyền | Có cây 3 cấp, chặn vòng lặp/xóa nhóm còn sản phẩm; có test. |
| S2-07 | Quy đổi đơn vị | Có | Có | Đã nối đơn vị/đơn/phiếu kho | Một phần; còn phạm vi kho | Có snapshot hệ số giao dịch cũ; test tính toàn vẹn qua. |
| S2-08 | Nhập sản phẩm Excel | Có | Có | Đã nối mẫu/preview/import | Một phần về thu hồi quyền | Có nhận diện SKU cập nhật và lỗi dòng; chưa thử 5.000 SKU đợt này. |
| S2-09 | Nhà cung cấp | Có | Có | Đã nối CRUD/dữ liệu phiếu nhập | Một phần về thu hồi quyền | Chặn xóa NCC có giao dịch; FE phiếu nhập mới chưa chọn NCC, xem S5-04. |
| S2-10 | Bảng giá nhóm và phiên bản | Một phần | Có | Đã nối quản lý bảng giá | Một phần về thu hồi quyền | Có hiệu lực/giá sàn/khóa bảng đã dùng; tạo/tính đơn nhận `priceListId` chưa kiểm đủ nhóm, active và hiệu lực. |

## Sprint 3 — Chiết khấu và đại lý

| Task | Tính năng | Backend | Frontend | BE ↔ FE | Quyền BE / FE | Nghiệm thu / còn thiếu |
|---|---|---|---|---|---|---|
| S3-01 | Chiết khấu sản lượng | Có | Có | Đã nối chính sách/tính đơn nội bộ | Một phần về thu hồi quyền | SKU/nhóm, %/số tiền, chọn mức có lợi nhất. Cổng chưa xem trước chiết khấu server. |
| S3-02 | Lịch sử giá | Có | Có | Đã nối `/products/{id}/price-history` | Có vai trò; còn hạn chế ma trận | Có giá cũ/mới/người sửa/thời điểm, API chỉ đọc. Hiện là giá sản phẩm; cần phân biệt giá từng bảng/nhóm. |
| S3-03 | Hồ sơ đại lý | Một phần | Có | Đã nối CRUD/nhóm/phụ trách | Có RLS danh sách; quyền một phần | Đường chỉ định bảng giá ở đơn chưa chặt. Ô ghi chú hồ sơ chưa có trường lưu. |
| S3-04 | Nhiều điểm giao | Một phần | Có | Đã nối CRUD/chọn điểm | **Thiếu kiểm vai trò và phạm vi API điểm giao** | Còn cho bỏ hết điểm mặc định; chưa tự chọn lại khi xóa điểm mặc định. |
| S3-05 | Hạn mức và ngày nợ | Một phần | Có | Đã nối `/finance/debt/{id}/limit` | **BE/FE chưa thống nhất** | FE chỉ kế toán/QL kinh doanh, BE còn cho ADMIN; PUT hồ sơ còn đường sửa công nợ khác. |
| S3-06 | Phân công và bàn giao | Một phần | Có | Đã nối phân công/bàn giao/lịch sử | **Phạm vi chưa xuyên suốt** | Danh sách giới hạn theo sales; tạo đơn nội bộ/điểm giao còn đường thao tác ngoài tuyến. Ghi chú bàn giao chưa lưu. |
| S3-07 | Khóa/mở giao dịch đại lý | Có | Có | Đã nối nội bộ/cổng | Server kiểm khóa; quyền một phần | Đơn cổng bị chặn khi khóa; đơn cũ có cảnh báo. Cần nghiệm thu cùng các đường xử lý vòng đời. |
| S3-08 | Tìm và lọc đại lý | Có | Có | Đã nối tìm kiếm/bộ lọc | Có RLS danh sách | Có khu vực/nhóm/phụ trách/trạng thái; còn hạn chế ma trận chung. |
| S3-09 | Tạo đơn, nháp, ngày/điểm giao | Một phần | Có | Đã nối đơn/nháp DB/tính tiền/tồn | **Còn hở phạm vi tạo đơn nội bộ** | Cần chặn tạo ngoài tuyến, kiểm bảng giá; chưa thử lại 15 dòng ở 360px. |

## Sprint 4 — Kiểm tra và xử lý đơn

| Task | Tính năng | Backend | Frontend | BE ↔ FE | Quyền BE / FE | Nghiệm thu / còn thiếu |
|---|---|---|---|---|---|---|
| S4-01 | Áp giá và chiết khấu | Có | Có | Đã nối `/orders/calculate`, `/orders/line-item/pricing` và `/price-lists/effective` | Toàn bộ luồng đặt hàng dùng `ORDER_CREATE` từ ma trận; Admin luôn được phép; không còn role fallback ở API bảng giá hiệu lực | Tự động áp bảng giá/chiết khấu, kiểm giá sàn; `priceListId` bắt buộc đúng nhóm, `active` và hiệu lực theo ngày. |
| S4-02 | Kiểm hạn mức công nợ | Có | Có | Đã nối `GET /dealers/{id}/debt` và `POST /orders/check-debt`; frontend hiển thị hiện tại/hạn mức/còn lại, cảnh báo vượt hạn mức và chặn nợ quá hạn | Đã nối `ORDER_CREATE`/`FINANCE_VIEW_DEBT` theo ma trận; `ROLE_ADMIN` luôn được phép | Vượt hạn mức chuyển đơn sang chờ duyệt; nợ quá hạn chặn tạo đơn. Chưa có sổ thu tiền riêng nên số dư hiện tính từ đơn `RECORDED`. |
| S4-03 | Tồn khả dụng, chống bán vượt tồn | Có | Có | Frontend gọi `/orders/check-inventory`, hiển thị tồn thực tế/giữ chỗ/khả dụng và chặn gửi đơn khi vượt tồn; backend giữ chỗ bằng khóa và cập nhật nguyên tử | `ORDER_CREATE` hoặc `WAREHOUSE_VIEW`; frontend kiểm tra `ORDER_CREATE`; `ROLE_ADMIN` luôn được phép | Đã bổ sung kiểm tra quyền theo ma trận và hàng rào cập nhật nguyên tử để hai đơn đồng thời không cùng giữ chỗ vượt tồn. Đơn chờ duyệt vẫn giữ chỗ khi được duyệt theo luồng S5-06. |
| S4-04 | Lịch sử 3 tháng, thêm đơn gần nhất | Có | Có | **Đã sửa nối luồng chính** | `ORDER_CREATE`/`ORDER_VIEW`/`CUSTOMER_VIEW` theo ma trận; menu và API chi tiết không còn role fallback | FE chờ đồng bộ quyền trước khi guard; màn Đại lý hiển thị theo `CUSTOMER_VIEW` hoặc `ORDER_VIEW`, các nút lịch sử/đơn đang dở chỉ hiện khi có `ORDER_VIEW`; thêm tất cả chỉ lấy đơn gần nhất qua áp giá/tồn. |
| S4-05 | Duyệt/Từ chối/Trả lại sửa | Có | Có | Đã nối PATCH, danh sách phân trang và lịch sử duyệt | Frontend yêu cầu cả `ORDER_VIEW` và `ORDER_APPROVE`; backend dùng `ORDER_VIEW` cho danh sách/chi tiết và `ORDER_APPROVE` cho thao tác; ADMIN luôn được phép | Bắt buộc lý do từ chối/trả lại, lưu lịch sử `APPROVED/REJECTED/RETURNED` bất biến, chỉ giữ chỗ sau khi kiểm quyền; đã bỏ role fallback và chặn sales duyệt đơn cổng có vi phạm. |
| S4-06 | Vòng đời và hủy đơn | Có | Có | Đã nối hủy, tracking và lịch sử trạng thái; frontend hiển thị 7 bước chính và nhánh `Huỷ` từ `/orders/{id}/tracking` | `ORDER_CANCEL`/`ORDER_VIEW` và `WAREHOUSE_IMPORT`/`WAREHOUSE_EXPORT` từ ma trận; cập nhật giao hàng dùng `WAREHOUSE_EXPORT`; ADMIN luôn được phép; giới hạn thao tác theo kho được gán | Bắt buộc lý do hủy, tự nhả giữ chỗ, nhánh hủy hiển thị đúng bước dừng và chặn sau xuất kho; chỉ cho chuyển từng bước kế tiếp, không nhảy/lùi; không còn role fallback trên luồng giao hàng. |
| S4-07 | Danh sách, lọc, tổng tiền | Có | Có | **Đã nối, thống nhất phân trang/DTO** | `ORDER_VIEW` theo ma trận; API dữ liệu phụ dùng `ORDER_VIEW`/`CUSTOMER_VIEW`, không còn role fallback | GET `/orders` trả tổng toàn tập lọc, số phần tử, phân trang tùy chọn; trang `/orders` cũng lọc phía FE. |
| S4-08 | In/PDF và barcode | Có | Có | **Đã nối PDF backend sau tạo đơn** | `ORDER_VIEW` theo ma trận; endpoint HTML/PDF/barcode đều kiểm tra quyền xem và RLS | Đã đồng bộ quyền giữa nút frontend và cả endpoint cũ `/print`; nhân viên kinh doanh/đại lý không thể in đơn ngoài phạm vi. |
| S4-09 | Sao chép đơn, áp lại giá | Có | Có | Đã nối nháp DB/nạp giá mới | API copy yêu cầu đồng thời `ORDER_CREATE` + `ORDER_VIEW`; kiểm tra phạm vi theo dữ liệu đơn | Nút và API thống nhất; dùng kiểm tra phạm vi xem đơn thay vì fallback role khi sao chép. |
| S4-10 | Cổng đại lý tự đặt | Một phần | Có | **Đã sửa dùng `/portal/**`** | `ORDER_VIEW`/`ORDER_CREATE`/`PRODUCT_VIEW` theo ma trận; `DEALER`/`ADMIN` chỉ là phạm vi cổng; ADMIN luôn được phép | Đã nối gán đại lý cho tài khoản; chặn API nội bộ/giả giá, luôn PENDING_APPROVAL. Thiếu preview chiết khấu server và UI sales xác nhận đơn cổng. |

## Sprint 5 — Theo dõi đơn và tồn kho

| Task | Tính năng | Backend | Frontend | BE ↔ FE | Quyền BE / FE | Nghiệm thu / còn thiếu |
|---|---|---|---|---|---|---|
| S5-01 | Đại lý theo dõi đơn, thực giao/giao thiếu | Có | Có | Đã nối `/portal/orders`/chi tiết | **Đại lý tài khoản + ORDER_VIEW** | Có trạng thái/ngày/dòng hàng/giao thiếu. Kho nhập thực giao còn thiếu UI và cần thống nhất vòng đời. |
| S5-02 | Đặt lại toàn bộ/một phần | Một phần | Có | **Đã sửa dùng catalog/POST cổng thật** | ORDER_CREATE/ORDER_VIEW; BE kiểm lại | Bỏ `/portal/orders/reorder` chưa có, bỏ retry POST sang API khác/giá cũ; loại hàng không còn catalog. Thiếu tổng sau chiết khấu xem trước. |
| S5-03 | Nhiều kho, khu/kệ, kho phục vụ | Có | **Chưa** | **Chưa có màn hình quản lý tương ứng** | Có WAREHOUSE_MANAGE ở BE; thiếu FE | Có API kho/vị trí/gán SKU/kho đại lý/picking list. Dropdown kho không thay thế màn hình quản lý kho/kệ. |
| S5-04 | Phiếu nhập NCC, lô, nháp/xác nhận | Có | **Một phần** | **Chưa nối đủ** | Có quyền nhập/điều chỉnh; thiếu scope kho | Test cộng tồn đúng lúc. FE phiếu cũ chưa gửi đủ supplierId/chứng từ/lô/HSD, chưa có xác nhận nháp mới. |
| S5-05 | Sổ tồn thực tế/giữ chỗ/khả dụng | Có | **Một phần** | **Chưa đủ 3 cột** | Có API tồn; quyền/scope chưa đủ | API có ba số; FE chỉ nạp `inventoryBaseQty`. Cần hai cột còn lại và lọc trạng thái tồn. |
| S5-06 | Giữ chỗ, hủy/quá hạn nhả tồn | Một phần | Có qua duyệt/hủy | Đã nối luồng chính | Quyền duyệt/hủy còn hạn chế chung | Có scheduler/transaction/khóa tồn. Cần xử lý đồng thời cùng đơn, đơn thiếu kho, bảo vệ tồn đã giữ trong xuất/chuyển. |
| S5-07 | Chuyển kho, đang đi, nhận chênh lệch | Có | Có | Đã nối tạo/danh sách/chi tiết/nhận/hủy; xem phần cập nhật S5-07 đầu file | WAREHOUSE_TRANSFER để ghi, VIEW hoặc TRANSFER để đọc; kiểm scope kho và thu hồi quyền, ADMIN luôn được phép | Đã kiểm khóa đồng thời, bảo vệ tồn đã giữ, nhận/hủy lặp, lý do chênh lệch và rollback. Chưa nghiệm thu MySQL thật. |
| S5-08 | Kiểm kê, chốt chênh lệch | Có | Có | Đã nối API `/warehouse/stock-counts`, phiếu/snapshot/số đếm lưu DB; xem cập nhật S5-08 đầu file | VIEW hoặc ADJUST để đọc/tạo/đếm, ADJUST để chốt; ADMIN luôn được phép; kiểm phạm vi kho và grant/revoke | Đã kiểm 4 tiêu chí ảnh backlog theo xác nhận quyền từ người dùng, khóa cạnh tranh, snapshot cũ, tồn giữ chỗ và rollback; chưa nghiệm thu MySQL thật. |
| S5-09 | Tồn tối thiểu và cảnh báo | Có | Có | Đã nối cấu hình, cảnh báo sổ tồn và dashboard; xem cập nhật S5-09 đầu file | WAREHOUSE_MANAGE để khai báo, WAREHOUSE_VIEW để xem; ma trận DB ở cả BE/FE, thu hồi trên phiên cũ, kiểm phạm vi kho | Đã triển khai hai AC; cảnh báo dựa trên tồn khả dụng, bằng ngưỡng không cảnh báo. Có bảng DB mới; chưa nghiệm thu MySQL thật. |

## Sprint 6 — Quản lý nâng cao và hạn sử dụng

| Task | Tính năng | Backend | Frontend | BE ↔ FE | Quyền BE / FE | Nghiệm thu / còn thiếu |
|---|---|---|---|---|---|---|
| S6-01 | Theo dõi lô và hạn sử dụng hàng hoá | Có | Có | Đã nối API thực; xem cập nhật S6-01 đầu file | VIEW/MANAGE xem; PRODUCT_EDIT/MANAGE cấu hình; EXPORT + APPROVE/MANAGE ghi đè quá hạn có xác nhận/lý do; ADMIN toàn quyền | Đã triển khai 4 tiêu chí trong ảnh, sửa scope kho/HSD DB/audit; nghiệm thu H2 và Chrome fixtures, chưa nghiệm thu MySQL thật. |
| S6-04 | Gợi ý lô xuất theo FEFO và xuất kho theo lô | Có | **Chưa** (chỉ làm BE theo yêu cầu) | **Đã có API sẵn sàng kết nối** | Đầy đủ ma trận: `WAREHOUSE_EXPORT`/`WAREHOUSE_VIEW` gợi ý lô; `WAREHOUSE_EXPORT` xuất kho theo lô & bắt buộc nhập lý do khi chọn lô khác; ADMIN toàn quyền | Đã hoàn thành 100% backend theo 3 tiêu chí trong ảnh mô tả. Đã kiểm thử 15 test (unit, service, security MockMvc). Chưa làm frontend theo chỉ đạo "chỉ làm BE". |

## Sprint 7 — Kế toán công nợ và hoá đơn

| Task | Tính năng | Backend | Frontend | BE ↔ FE | Quyền BE / FE | Nghiệm thu / còn thiếu |
|---|---|---|---|---|---|---|
| S7-01 | Phát hành hoá đơn bán hàng từ phiếu đã giao | Có | **Chưa** (chỉ làm BE theo yêu cầu) | **Đã có API sẵn sàng kết nối** | Đầy đủ ma trận: INVOICE_VIEW, INVOICE_CREATE, INVOICE_ADJUST từ ma trận DB; ADMIN toàn quyền | Đã hoàn thành 100% backend theo 5 tiêu chí user story. Sinh theo thực giao, số nhảy liên tục, gộp phiếu giao cùng đại lý, hóa đơn bất biến chỉ lập điều chỉnh, xuất PDF và HTML print preview. Đã kiểm thử 30 test mới (unit, controller, security, integration). Chưa làm FE theo yêu cầu. |
| S7-04 | Hệ thống tự cảnh báo và khoá bán công nợ | Có | **Chưa** (chỉ làm BE theo yêu cầu) | **Đã có API sẵn sàng kết nối** | FINANCE_VIEW_DEBT, FINANCE_MANAGE từ ma trận DB; ADMIN toàn quyền | Đã hoàn thành 100% backend: tự gắn cờ đại lý quá hạn/vượt hạn mức, chặn/cần duyệt khi tạo đơn, scheduler đánh giá hàng ngày và sau thu tiền. 24 test mới. Chưa làm FE theo yêu cầu. |
| S7-07 | Đại lý tự xem công nợ, hạn mức còn lại và tải lại hoá đơn | Có | **Chưa** (chỉ làm BE theo yêu cầu) | **Đã có API sẵn sàng kết nối** | FINANCE_VIEW_DEBT, INVOICE_VIEW, ORDER_VIEW từ ma trận DB; cách ly RLS theo đại lý liên kết; ADMIN toàn quyền, không role fallback | Đã hoàn thành 100% backend: hiển thị tổng còn nợ, hạn mức còn lại, hạn mức còn được lấy hàng, danh sách hóa đơn kèm số tiền/hạn thanh toán/trạng thái FIFO, xem và tải lại PDF/bản in hóa đơn & điều chỉnh. Thu hồi quyền trên JWT hiện tại trả 403. Chưa làm FE theo yêu cầu. |

## Việc cần làm tiếp

1. Đồng bộ quyền với ma trận và giới hạn dữ liệu kho/đại lý ở mọi đường đọc/ghi, đặc biệt điểm giao, tạo đơn, sửa công nợ và phiếu kho.
2. Sửa transaction import tài khoản; kiểm bảng giá chỉ định; thống nhất nguồn công nợ/vòng đời đơn/xuất hàng.
3. Hoàn thiện UI S5-04; nghiệm thu S5-03/S5-05/S5-07/S5-08/S5-09 trên MySQL thật theo các cập nhật đầu file.
4. Nối preview chiết khấu cho cổng/đặt lại, UI sales xác nhận đơn cổng; nghiệm thu MySQL/SMTP/360px theo AC.

Chi tiết: [báo cáo rà soát](docs/SPRINT_1_5_AUDIT_20261018.md).

## S8-02 — Lập chứng từ điều chỉnh giảm công nợ khi trả hàng (2026-10-23)

Branch: `feature/S8-02/lap-chung-tu-dieu-chinh`. Repository: `C:\Users\Admin\Documents\DUANgITHUB\QuanLyBanHang` (repo GitHub Desktop). Tiếp tục từ 11 file backend viết dở chưa commit; chỉ làm backend.

| Tiêu chí trong yêu cầu | Backend | Frontend | Kết nối/phân quyền và bằng chứng |
|---|---|---|---|
| Chứng từ sinh từ phiếu trả đã xác nhận, giữ liên kết hóa đơn gốc | Hoàn thành: tạo DRAFT, xác nhận sinh InvoiceAdjustment(DECREASE), liên kết duy nhất phiếu → chứng từ → hóa đơn; từng dòng lưu invoiceItemId gốc | Chưa triển khai theo yêu cầu chỉ backend | POST `/api/invoices/returns`, `/{id}/confirm`; xác nhận trùng trả 409; DB lock chặn hai phiếu cùng trả vượt số lượng |
| Giảm trừ theo giá đã bán trên hóa đơn gốc | Hoàn thành: dùng tổng tiền dòng gốc sau chiết khấu, tính tỷ lệ lượng trả, làm tròn lũy kế bảo toàn số lẻ | Chưa triển khai | Test thay giá sản phẩm hiện hành sang 9999 vẫn giảm đúng 33.33; trả 3 lần bảo toàn tổng 100.00 |
| Công nợ và hạn mức khả dụng cập nhật ngay | Hoàn thành: trừ khoản trả CONFIRMED khỏi công nợ ở DealerDebtService và hồ sơ đại lý; giảm nợ đúng đơn nguồn trước phân bổ thanh toán FIFO; đánh giá lại cảnh báo cùng transaction | Chưa triển khai | Kiểm thử nợ 100 → 66.67, hạn mức còn 133.33; trả hết gỡ nợ quá hạn; rollback nếu đánh giá công nợ lỗi |
| Hóa đơn gốc giữ nguyên, không sửa | Hoàn thành: không gọi setters/save trên hóa đơn khi confirm trả hàng, chỉ ghi chứng từ riêng | Chưa triển khai | Test so sánh toàn bộ hàng DB của `invoices` và `invoice_items` trước/sau xác nhận, khớp hoàn toàn; tra chứng từ qua API hóa đơn hiện có |
| Phân quyền từ ma trận DB | Hoàn thành: INVOICE_ADJUST cho lập/xác nhận/hủy, INVOICE_VIEW cho tra cứu; ADMIN luôn có quyền API; không fallback vai trò | Ma trận quản trị hiện có tải catalog động; chưa có trang/menu/nút nghiệp vụ S8-02 | Test non-admin chỉ một vai trò, cấp/thu hồi quyền trên cùng JWT, request tiếp theo trả 403; VIEW và ADJUST tách riêng |
| Tài liệu và kiểm thử | Hoàn thành: cập nhật `backend/API_DOCUMENTATION.md` | Không sửa frontend | 112/112 tests của 11 bộ liên quan qua, 0 failure/error/skip; gồm 9 ca tích hợp S8-02 |

Luồng tài chính này không tự nhập kho vật lý hoặc tạo phiếu hoàn tiền. Chỉ phiếu đã CONFIRMED ảnh hưởng công nợ; DRAFT/CANCELLED không ảnh hưởng. Kiểm thử trên H2 MySQL mode, chưa chạy MySQL thật. Nếu có dữ liệu từ bản viết dở, cần backfill liên kết dòng hóa đơn trước khi áp NOT NULL. Frontend chưa nối API S8-02 nên chưa nghiệm thu ẩn/hiện hành động trên giao diện.

Đã sửa một assertion audit tiền trong `DealerDebtServiceTest` vốn cố định dấu phẩy thành cách kiểm theo locale đang chạy; dữ liệu tiền và nghiệp vụ vẫn được kiểm tra đúng.

Lệnh kiểm thử:

```text
mvn -q "-Dtest=ReturnOrderIntegrationTest,InvoiceServiceTest,InvoiceIntegrationTest,InvoiceControllerTest,InvoiceSecurityTest,DealerDebtServiceTest,DealerServiceTest,DebtRiskIntegrationTest,DebtRiskSecurityTest,PermissionRevocationTest,RolePermissionConfigTest" test
```

## S8-05 — Dashboard điều hành bán hàng và kho (cập nhật frontend 2026-10-24)

Backend đã có trên develop; frontend nối tại branch `feature/S8-05-frontend-xem_dashboard_dieu_hanh_ban_hang_va_kho`. Vị trí: **Báo cáo → Dashboard điều hành**, `/reports/dashboard`; lối tắt từ **Tổng quan → Công việc**. Không sửa code/backend/schema, không commit/push/merge trong lượt này.

| Yêu cầu | Backend | Frontend | Kết nối và kiểm chứng |
|---|---|---|---|
| KPI doanh số hôm nay/tháng này, đơn chờ duyệt/chờ xuất, nợ quá hạn, SKU dưới tồn tối thiểu | Đã có: DB, công nợ FIFO, tồn khả dụng/minimum hiện có | Đã nối đủ sáu thẻ, giữ nguyên chữ số tiền BigDecimal; thời điểm và ngày/tháng theo máy chủ | Một GET `/api/reports/dashboard?overdueLimit=20`, không gọi riêng từng nguồn hoặc tự tính lại KPI |
| Biểu đồ doanh số từng ngày trong tháng và theo khu vực | Đã có đủ ngày và nhóm chưa có khu vực | SVG ngày + bảng số liệu, thanh doanh số khu vực; không CDN, hỗ trợ bàn phím/màn hình nhỏ | Test đủ 28/29/30/31 ngày, ngày doanh số 0/ngày tương lai và múi giờ thiết bị khác máy chủ |
| Đại lý quá hạn, xếp theo số tiền | Đã có FIFO/sort giảm dần, tổng và endpoint phân trang | Trang đầu nhúng trong overview, đổi trang/cỡ 10/20/50/100 dùng API page; tự sửa trang vượt cuối khi số đại lý giảm | Test trước/sau/cỡ trang, tổng về 0, lỗi trang không xóa KPI đã chụp; thứ tự giữ từ server |
| Toàn bộ dữ liệu tải dưới 2 giây | Hồi quy tích hợp 24/10: tối đa 355 ms, 3 request đầy đủ trên 10.000 đơn/1.000 đại lý/1.000 SKU | Chrome HTTP fixture: 205 ms từ điều hướng đến dữ liệu vẽ xong; có số đo/cảnh báo thật khi vượt mốc, tải lại đo riêng | Chưa nghiệm thu SLA MySQL/mạng thật. Phép đo BE và Chrome độc lập, không cộng thành một số end-to-end |
| Ma trận quyền DB | DASHBOARD_VIEW hoặc ADMIN, thu hồi trả 403 với JWT cũ; không sửa backend | Menu/guard/API, thu hồi xóa dữ liệu và hủy/bỏ phản hồi trễ; VIEWER được cấp quyền dùng được, vai trò quản lý không tự cấp quyền | Test thu hồi khi đang tải overview/page và API 403; Tổng quan chung/Công nợ giữ quyền riêng, đại lý không vào màn nội bộ |
| Tài liệu và hồi quy | 22/22 tests DashboardIntegrationTest (8) + RolePermissionConfigTest (14) qua | 198 unit JS, 12 test route, 16 kịch bản Chrome và 210 kiểm tra bố cục (35 trang × 6 kích thước) qua | Cập nhật FUNCTION_MAP/FRONTEND_ROUTES/API_DOCUMENTATION; xem ảnh desktop/mobile, rà cùng Antigravity rồi đối chiếu kết quả bằng kiểm thử |

DB mới khởi tạo DASHBOARD_VIEW cho SALES_MANAGER và ADMIN. DB đã cấu hình giữ nguyên ma trận: ADMIN cần chủ động cấp quyền mới. Ứng dụng không dùng dữ liệu giả/browser storage nghiệp vụ; fixtures chỉ nằm trong tests. API read-only với snapshot REPEATABLE_READ; danh sách đọc lại khi đổi trang có thể mới hơn các KPI/biểu đồ đã chụp, UI ghi rõ và có Tải lại để đồng bộ. Không tạo/sửa/xóa dữ liệu DB nghiệp vụ.

Kiểm thử: `node --test tests/*.test.cjs`; `python -B -m unittest discover -s tests -p test_frontend_server.py`; `node tests/operations-dashboard.browser.cjs`; `node tests/content-layout.browser.cjs`; `mvn -q "-Dtest=DashboardIntegrationTest,RolePermissionConfigTest" test`. Chrome dùng script thật/HTTP fixtures cách ly trên frontend cổng 5537, backend tests dùng H2 MySQL mode; chưa nghiệm thu dữ liệu MySQL đang chạy. Các kết quả 53 tests BE ở lần triển khai backend trước là lịch sử, không thay thế 22 tests đã chạy lại lượt này.

## S7-05 — Ghi nhận khoản thu theo tuyến (cập nhật 2026-10-24)

Backend đã có trên `develop`; frontend nối API tại nhánh `feature/frontend/S7-05/thu-tien-theo-tuyen`. Vị trí: **Tài chính → Thu tiền theo tuyến**, `/cash-collections`.

| Yêu cầu | Backend | Frontend | Kết nối và phân quyền |
|---|---|---|---|
| Ghi khoản thu tại cửa hàng kèm ảnh biên lai | Multipart JSON + JPEG/PNG lưu DB; nhân viên từ JWT; bổ sung picker `/dealers` có phân trang, chỉ cửa hàng hoạt động được giao (ADMIN toàn bộ) | Đã nối: tìm/chọn cửa hàng, ngày thu, tiền chính xác tới 17+2 chữ số, ghi chú, kiểm tra/xem trước ảnh 5 MiB/20 MP, xác nhận trước lưu | POST `/api/finance/cash-collections`, `COLLECTION_CREATE`; không yêu cầu thêm CUSTOMER_VIEW/ORDER_VIEW, không lưu dữ liệu nghiệp vụ trong browser storage |
| Chờ nộp đến khi kế toán xác nhận nhận tiền | `PENDING` → `RECEIVED`, lưu người/thời điểm, audit; khóa dòng chống xác nhận trùng; không tự xác nhận | Đã nối: trạng thái Chờ nộp/Đã nhận tiền; xem chi tiết, biên lai, tích đã nhận đủ; ẩn xác nhận cho khoản thu của mình kể cả ADMIN | POST `/{id}/confirm`, `COLLECTION_CONFIRM`; chặn bấm kép, xử lý 409 không tự retry; lỗi chưa xác định kết quả yêu cầu tải lại kiểm tra |
| Đối chiếu theo từng nhân viên và ngày | Tổng thu, đã nhận, còn chờ; lọc ngày/nhân viên; snapshot địa bàn tại lúc thu | Đã nối bảng và tổng tiền theo ngày thu gốc, lọc mã nhân viên/khoảng ngày; không cộng tiền qua Number | GET `/reconciliation`, RECONCILE xem toàn bộ, CREATE đơn lẻ chỉ mình; CONFIRM đơn lẻ không gọi API này |
| Danh sách, chi tiết, ảnh riêng tư | Phân trang, kiểm tra chủ sở hữu ở chi tiết và ảnh, ảnh không public | Đã nối phân trang server, ngày thu/tuyến/nhân viên/số tiền/trạng thái; ảnh tải bằng Bearer + blob và được giải phóng khi đóng | CREATE chỉ xem mình; CONFIRM/RECONCILE xem toàn bộ; không sửa/xóa khoản thu, không để dữ liệu cũ giả thành kết quả rỗng khi lỗi |
| Ma trận quyền DB | Ba quyền COLLECTION, cấp/thu hồi có hiệu lực request kế tiếp; không fallback theo vai trò | Menu/trang/nút theo quyền, đóng form/xóa dữ liệu nhạy cảm/chặn phản hồi cũ khi đổi quyền, ADMIN luôn có quyền nhưng vẫn không tự xác nhận | Đã kiểm thử non-admin một vai trò VIEWER nhận/thu hồi quyền trên cùng phiên; API thu hồi cùng JWT trả 403, picker không lộ cửa hàng khác |
| Tài liệu và kiểm thử | 43/43 tests của 5 bộ qua; 9 bài tích hợp S7-05 gồm picker, ownership, ảnh, đối chiếu, lịch sử tuyến và xác nhận đồng thời | 181 unit tests frontend, 11 tests route, 11 kịch bản Chrome HTTP fixtures cách ly và 198 kiểm tra bố cục của 33 trang; kiểm tra desktop/tablet/mobile, ảnh, multipart, XSS, cấp/thu hồi quyền, bấm kép và lỗi API | Cập nhật API_DOCUMENTATION, FRONTEND_ROUTES và FUNCTION_MAP; backend H2 MySQL mode, chưa nghiệm thu MySQL thật; không tạo khoản thu trong DB nghiệp vụ |

DB đã có cấu hình phải được ADMIN cấp quyền mới từ ma trận; không tự chèn quyền vào vai trò đã cấu hình. DB mới khởi tạo SALES=CREATE, ACCOUNTANT=CONFIRM+RECONCILE, SALES_MANAGER=RECONCILE. Kiểm thử trên H2 MySQL mode, chưa kiểm thử MySQL thật. Tuyến dùng địa bàn hiện có (`Dealer.territory`); đối chiếu theo ngày thu gốc. Xác nhận nhận đủ từng khoản, không có nộp một phần hoặc tự đối trừ công nợ S7-02.

Lệnh kiểm thử: `mvn -q "-Dtest=CashCollectionIntegrationTest,RolePermissionConfigTest,PermissionRevocationTest,FinanceControllerTest,DebtRiskSecurityTest" test`.

## S8-10 — Xuất sổ chi tiết công nợ đại lý (2026-10-23)

Branch `feature/S8-10/xuat-so-chi-tiet-cong-no` trong repo GitHub Desktop `C:\Users\Admin\Documents\DUANgITHUB\QuanLyBanHang`. Chỉ backend; không commit/push.

| Yêu cầu | Backend | Frontend | Kết nối/phân quyền và bằng chứng |
|---|---|---|---|
| Liệt kê phát sinh tăng/giảm theo thời gian và số dư lũy kế | Hoàn thành: hợp nhất đơn ghi nợ, phiếu thu và điều chỉnh hóa đơn; thứ tự thời điểm/nguồn/ID; tính đầu kỳ, tăng/giảm, cuối kỳ bằng BigDecimal | Chưa triển khai | GET `/api/finance/debt/{dealerId}/statement`; test không cộng lại hóa đơn gốc, giữ credit âm và cách ly đại lý |
| Lọc khoảng thời gian | Hoàn thành: from/to bao gồm hai ngày biên, opening lấy toàn bộ trước kỳ; kỳ rỗng vẫn có số dư đúng | Chưa triển khai | Test đầu kỳ 800, tăng 320, giảm 180, cuối kỳ 940; đổi từ ngày 19 thì đầu kỳ 1040, cuối kỳ vẫn 940 |
| Xuất PDF | Hoàn thành: A4 ngang, font Unicode nhúng, thông tin đại lý/kỳ, bảng lặp header và số trang | Chưa nối | GET `/statement/export?from=...&to=...&format=pdf`; mở/trích text PDF và render kiểm tra cả 3 trang bằng Poppler |
| Xuất Excel | Hoàn thành: cùng dữ liệu với PDF/JSON, sáu cột, đầu kỳ/tổng/cuối kỳ; tiền lớn hơn 15 chữ số có nghĩa ghi text chính xác | Chưa nối | format=xlsx; test mở lại POI, số dư 940, số tiền lớn 9007199254740993.01 không bị sai và chuỗi công thức là text |
| Ma trận quyền DB và phạm vi đại lý | Hoàn thành: FINANCE_VIEW_DEBT, ADMIN luôn có quyền, không fallback FINANCE_MANAGE/role; scope SALES được giao và DEALER liên kết | Quyền/menu công nợ hiện có; chưa có nút/luồng S8-10 để nghiệm thu ẩn/hiện | Test non-admin một vai trò, thu hồi cùng JWT trả 403 cho JSON/PDF/XLSX; SALES ngoài phạm vi bị từ chối |
| Tài liệu và hồi quy | Đã cập nhật API_DOCUMENTATION.md; 44/44 tests qua, 0 lỗi/skip | Không sửa frontend | 10 ca tích hợp sổ và 34 test hồi quy tài chính/phân quyền; H2 MySQL mode, chưa MySQL thật |

Không sửa dữ liệu tài chính hoặc hành vi API tổng hợp cũ. Sổ giữ số dư có dấu và bao gồm điều chỉnh; API tổng hợp cũ trên branch chưa trừ/cộng điều chỉnh và có cắt âm nên số hiển thị có thể khác sổ. Dữ liệu xuất là chứng từ hiện có/trạng thái hiện tại, không tái dựng lịch sử đã xóa. Mặc định tối đa 20.000 dòng kỳ, vượt trả 400 chứ không xuất thiếu; SUM đầu kỳ không bị cắt theo giới hạn dòng kỳ.

Lệnh kiểm thử: `mvn -q clean "-Dtest=DealerDebtStatementIntegrationTest,FinanceControllerTest,DebtRiskSecurityTest,PermissionRevocationTest,RolePermissionConfigTest" test`.

## S8-01 — Frontend trả hàng đại lý (2026-10-24)

Nhánh `feature/S8-01-frontend-nhap_phieu_tra_hang_tu_dai_ly_va_nhap_lai_kho`. Phạm vi **chỉ frontend**, giữ nguyên toàn bộ `backend/`; backend thuộc thành viên khác. Vị trí: **Kho hàng → Trả hàng đại lý**, `/warehouse/dealer-returns`.

| Yêu cầu | Backend hiện có | Frontend | Kết nối / kiểm chứng |
|---|---|---|---|
| Hóa đơn gốc, dòng trả, lý do | Dùng API `/dealer-returns/options`, `/eligible-invoices`, chi tiết nguồn và POST tạo sẵn có | Đã nối tìm/phân trang hóa đơn, chọn/tách nhiều dòng trả, bắt buộc lý do; OTHER cần nội dung | Chỉ gửi invoiceItemId, số trả theo đơn vị hóa đơn, lý do, tình trạng/lô/kho/ghi chú; không gửi tồn/trạng thái/giá do client tự tính |
| Không trả vượt số đã giao trừ phần trả trước | Dùng `returnableQuantity`, đã tính cả PENDING + CONFIRMED | Đã kiểm tra số dương/4 số thập phân bằng BigInt, cộng dồn mọi dòng cùng invoiceItemId; có nút cập nhật số còn được trả | 400 giữ bản nháp cho sửa; không âm thầm xóa số người dùng nhập khi kiểm tra lại |
| Lô đúng và hàng hỏng vào kho lỗi | Dùng availableLots và hai danh mục kho từ options | Chọn lô, giữ ngày gốc; chỉ nhập số lô/HSD thực tế khi thiếu dữ liệu, không tự sinh; hàng hỏng bắt buộc kho lỗi | Không tự tạo kho lỗi, không lấy tồn hiện tại làm trần hàng nhập trả; kiểm chứng lô xuất gốc/transaction thuộc backend |
| Chỉ xác nhận nhận hàng mới cộng tồn | Dùng POST confirm và cờ inventoryAdded, không sửa BE | Có chi tiết từng dòng/lô/kho; nhận đủ + xác nhận; cờ inventoryAdded từ API là nguồn hiển thị tồn | WAREHOUSE_APPROVE + options.canConfirm + detail.canConfirm + PENDING; chống xác nhận lặp; hủy riêng có lý do, chỉ cho PENDING |
| Ma trận quyền và dữ liệu thật | Theo các quyền/DTO sẵn có; không sửa role/permission backend | Menu/trang/nút theo quyền, cờ server có hiệu lực bổ sung; cấp/thu hồi xóa nháp/chi tiết và bỏ phản hồi cũ | IMPORT đơn lẻ không gọi hóa đơn nguồn; không role fallback, không dữ liệu demo/bản nháp trong browser storage |
| Giao diện và tài liệu | Không chỉnh backend/API docs | Dùng chung shell/gutter, hỗ trợ desktop/tablet/mobile; cập nhật FUNCTION_MAP và FRONTEND_ROUTES | 190 test Node, 11 test route, 14 kịch bản Chrome và 204 kiểm tra bố cục / 34 trang qua; HTTP fixtures cách ly, không ghi DB nghiệp vụ, chưa nghiệm thu MySQL thật |

**Lưu ý bàn giao backend:** bộ `DealerReturnSecurityTest` hiện có chạy 6 ca, 3 qua/3 thất bại. Các ca `admin_AlwaysAllowed`, `createReturn_WithOrderCreatePermission_Returns200`, `createReturn_WithoutOrderCreatePermission_Returns403` nhận HTTP 400 (`HttpMessageNotReadableException`) thay vì 200/403 khi gửi JSON do test dựng từ DTO. Không sửa backend hoặc bộ test để ép xanh; cần thành viên backend kiểm tra. Không tuyên bố toàn bộ S8-01 đã nghiệm thu end-to-end.
