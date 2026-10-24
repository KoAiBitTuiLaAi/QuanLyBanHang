package com.quanlybanhang.controller;

import com.quanlybanhang.dto.request.BatchLotSuggestionRequest;
import com.quanlybanhang.dto.request.ProductLocationRequest;
import com.quanlybanhang.dto.request.WarehouseLocationRequest;
import com.quanlybanhang.dto.request.WarehouseRequest;
import com.quanlybanhang.dto.request.WarehouseVoucherRequest;
import com.quanlybanhang.dto.response.*;
import com.quanlybanhang.model.LocationType;
import com.quanlybanhang.dto.request.WarehouseTransferReceiveRequest;
import com.quanlybanhang.dto.request.WarehouseTransferRequest;
import com.quanlybanhang.dto.response.ApiResponse;
import com.quanlybanhang.dto.response.WarehouseInventoryResponse;
import com.quanlybanhang.dto.response.WarehouseTransferResponse;
import com.quanlybanhang.dto.response.WarehouseVoucherResponse;
import com.quanlybanhang.model.WarehouseTransferStatus;
import com.quanlybanhang.model.WarehouseVoucherType;
import com.quanlybanhang.service.WarehouseService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.security.Principal;
import java.util.List;
import java.util.Map;

/**
 * Controller Quản lý Kho, Vị trí lưu kho & Tồn kho (S1-05, S2-07 & S5-03).
 *
 * S5-03:
 * - Khai báo nhiều kho: mã, tên, địa chỉ, người phụ trách, trạng thái
 * - Khai báo vị trí lưu trong kho ở mức kệ hoặc khu (ZONE, SHELF)
 * - Gán sản phẩm vào vị trí kệ để biết chính xác hàng nằm ở kệ nào khi soạn đơn
 * Controller Quản lý Kho & Tồn kho (S1-05, S2-07, S5-04).
 *
 * Quy tắc nghiệp vụ cốt lõi:
 * - Nhân viên kinh doanh (ROLE_SALES) và Thủ kho (ROLE_WAREHOUSE_STAFF)
 *   KHÔNG ĐƯỢC PHÉP điều chỉnh tồn kho (/api/warehouse/adjust, /api/warehouse/vouchers/{id}/adjust).
 *   Chỉ Quản lý kho (ROLE_WAREHOUSE_MANAGER) và Quản trị viên (ROLE_ADMIN) được điều chỉnh.
 * - Thủ kho (ROLE_WAREHOUSE_STAFF) được phép nhập/xuất kho (/api/warehouse/import, /api/warehouse/export)
 *   và xem tồn kho (/api/warehouse/inventory).
 * - S2-07: Nhập/xuất theo bất kỳ đơn vị nào (thùng, lốc, lon) đều được tự động quy về
 *   đơn vị cơ sở (số lon) để ghi sổ sách kho.
 * - S5-04:
 *   + Khai báo nhà cung cấp, số chứng từ, ngày nhập, kho nhập.
 *   + Nhập theo đơn vị tính bất kỳ, quy về đơn vị cơ sở khi ghi sổ.
 *   + Ghi nhận số lô và hạn sử dụng cho mặt hàng có quản lý lô.
 *   + Xác nhận phiếu mới cộng tồn; phiếu nháp không ảnh hưởng tồn.
 *   + Phiếu đã xác nhận không sửa được, chỉ lập phiếu điều chỉnh.
 */
@RestController
@RequestMapping("/api/warehouse")
@RequiredArgsConstructor
public class WarehouseController {

    private final WarehouseService warehouseService;

    // ═══════════════════════════════════════════════════════════════
    // 1. DANH SÁCH OPTIONS NHANH
    // ═══════════════════════════════════════════════════════════════

    @GetMapping("/options")
    @PreAuthorize("hasRole('ADMIN') or (hasAnyAuthority('WAREHOUSE_VIEW', 'ORDER_CREATE', 'WAREHOUSE_IMPORT', 'WAREHOUSE_EXPORT', 'WAREHOUSE_MANAGE'))")
    public ApiResponse warehouseOptions() {
        return ApiResponse.success("Danh sách kho đang hoạt động", warehouseService.getWarehouseOptions());
    }

    // ═══════════════════════════════════════════════════════════════
    // 2. KHAI BÁO & QUẢN LÝ NHIỀU KHO (S5-03)
    // ═══════════════════════════════════════════════════════════════

    @GetMapping("/warehouses")
    @PreAuthorize("hasRole('ADMIN') or (hasAnyAuthority('WAREHOUSE_VIEW', 'WAREHOUSE_MANAGE'))")
    public ApiResponse getAllWarehouses(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean active) {
        List<WarehouseResponse> warehouses = warehouseService.getAllWarehouses(keyword, active);
        return ApiResponse.success("Lấy danh sách kho thành công", warehouses);
    }

    @GetMapping("/warehouses/{id}")
    @PreAuthorize("hasRole('ADMIN') or (hasAnyAuthority('WAREHOUSE_VIEW', 'WAREHOUSE_MANAGE'))")
    public ApiResponse getWarehouseById(@PathVariable Long id) {
        WarehouseResponse warehouse = warehouseService.getWarehouseById(id);
        return ApiResponse.success("Lấy thông tin chi tiết kho thành công", warehouse);
    }

    @PostMapping("/warehouses")
    @PreAuthorize("hasRole('ADMIN') or (hasAuthority('WAREHOUSE_MANAGE'))")
    public ApiResponse createWarehouse(
            @Valid @RequestBody WarehouseRequest request,
            Principal principal) {
        String username = principal != null ? principal.getName() : "system";
        WarehouseResponse warehouse = warehouseService.createWarehouse(request, username);
        return ApiResponse.success("Khai báo kho mới thành công", warehouse);
    }

    @PutMapping("/warehouses/{id}")
    @PreAuthorize("hasRole('ADMIN') or (hasAuthority('WAREHOUSE_MANAGE'))")
    public ApiResponse updateWarehouse(
            @PathVariable Long id,
            @Valid @RequestBody WarehouseRequest request,
            Principal principal) {
        String username = principal != null ? principal.getName() : "system";
        WarehouseResponse warehouse = warehouseService.updateWarehouse(id, request, username);
        return ApiResponse.success("Cập nhật thông tin kho thành công", warehouse);
    }

    @DeleteMapping("/warehouses/{id}")
    @PreAuthorize("hasRole('ADMIN') or (hasAuthority('WAREHOUSE_MANAGE'))")
    public ApiResponse deleteWarehouse(@PathVariable Long id, Principal principal) {
        String username = principal != null ? principal.getName() : "system";
        warehouseService.deleteWarehouse(id, username);
        return ApiResponse.success("Xóa/ngừng hoạt động kho thành công", null);
    }

    // ═══════════════════════════════════════════════════════════════
    // 3. KHAI BÁO VỊ TRÍ LƯU TRONG KHO: MỨC KỆ HOẶC KHU (S5-03)
    // ═══════════════════════════════════════════════════════════════

    @GetMapping("/locations")
    @PreAuthorize("hasRole('ADMIN') or (hasAnyAuthority('WAREHOUSE_VIEW', 'WAREHOUSE_MANAGE'))")
    public ApiResponse getLocations(
            @RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) LocationType type,
            @RequestParam(required = false) Long parentId,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) String keyword) {
        List<WarehouseLocationResponse> locations = warehouseService.getLocations(warehouseId, type, parentId, active, keyword);
        return ApiResponse.success("Lấy danh sách vị trí lưu kho thành công", locations);
    }

    @GetMapping("/warehouses/{warehouseId}/locations")
    @PreAuthorize("hasRole('ADMIN') or (hasAnyAuthority('WAREHOUSE_VIEW', 'WAREHOUSE_MANAGE'))")
    public ApiResponse getLocationsByWarehouse(
            @PathVariable Long warehouseId,
            @RequestParam(required = false) LocationType type,
            @RequestParam(required = false) Long parentId,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) String keyword) {
        List<WarehouseLocationResponse> locations = warehouseService.getLocations(warehouseId, type, parentId, active, keyword);
        return ApiResponse.success("Lấy danh sách vị trí kho thành công", locations);
    }

    @GetMapping("/locations/{id}")
    @PreAuthorize("hasRole('ADMIN') or (hasAnyAuthority('WAREHOUSE_VIEW', 'WAREHOUSE_MANAGE'))")
    public ApiResponse getLocationById(@PathVariable Long id) {
        WarehouseLocationResponse location = warehouseService.getLocationById(id);
        return ApiResponse.success("Lấy chi tiết vị trí lưu kho thành công", location);
    }

    @PostMapping("/locations")
    @PreAuthorize("hasRole('ADMIN') or (hasAuthority('WAREHOUSE_MANAGE'))")
    public ApiResponse createLocation(
            @Valid @RequestBody WarehouseLocationRequest request,
            Principal principal) {
        String username = principal != null ? principal.getName() : "system";
        WarehouseLocationResponse location = warehouseService.createLocation(request.getWarehouseId(), request, username);
        return ApiResponse.success("Khai báo vị trí lưu kho thành công", location);
    }

    @PostMapping("/warehouses/{warehouseId}/locations")
    @PreAuthorize("hasRole('ADMIN') or (hasAuthority('WAREHOUSE_MANAGE'))")
    public ApiResponse createLocationForWarehouse(
            @PathVariable Long warehouseId,
            @Valid @RequestBody WarehouseLocationRequest request,
            Principal principal) {
        String username = principal != null ? principal.getName() : "system";
        WarehouseLocationResponse location = warehouseService.createLocation(warehouseId, request, username);
        return ApiResponse.success("Khai báo vị trí lưu kho thành công", location);
    }

    @PutMapping("/locations/{id}")
    @PreAuthorize("hasRole('ADMIN') or (hasAuthority('WAREHOUSE_MANAGE'))")
    public ApiResponse updateLocation(
            @PathVariable Long id,
            @Valid @RequestBody WarehouseLocationRequest request,
            Principal principal) {
        String username = principal != null ? principal.getName() : "system";
        WarehouseLocationResponse location = warehouseService.updateLocation(id, request, username);
        return ApiResponse.success("Cập nhật vị trí lưu kho thành công", location);
    }

    @DeleteMapping("/locations/{id}")
    @PreAuthorize("hasRole('ADMIN') or (hasAuthority('WAREHOUSE_MANAGE'))")
    public ApiResponse deleteLocation(@PathVariable Long id, Principal principal) {
        String username = principal != null ? principal.getName() : "system";
        warehouseService.deleteLocation(id, username);
        return ApiResponse.success("Xóa vị trí lưu kho thành công", null);
    }

    // ═══════════════════════════════════════════════════════════════
    // 4. GÁN SẢN PHẨM VÀO KỆ / KHU (S5-03)
    // ═══════════════════════════════════════════════════════════════

    @PostMapping("/locations/{locationId}/products")
    @PreAuthorize("hasRole('ADMIN') or (hasAuthority('WAREHOUSE_MANAGE'))")
    public ApiResponse assignProductToLocation(
            @PathVariable Long locationId,
            @Valid @RequestBody ProductLocationRequest request,
            Principal principal) {
        String username = principal != null ? principal.getName() : "system";
        ProductLocationResponse response = warehouseService.assignProductToLocation(locationId, request, username);
        return ApiResponse.success("Gán sản phẩm vào vị trí kệ thành công", response);
    }

    @DeleteMapping("/locations/{locationId}/products/{productId}")
    @PreAuthorize("hasRole('ADMIN') or (hasAuthority('WAREHOUSE_MANAGE'))")
    public ApiResponse removeProductFromLocation(
            @PathVariable Long locationId,
            @PathVariable Long productId,
            Principal principal) {
        String username = principal != null ? principal.getName() : "system";
        warehouseService.removeProductFromLocation(locationId, productId, username);
        return ApiResponse.success("Bỏ gán sản phẩm khỏi vị trí kệ thành công", null);
    }

    @GetMapping("/locations/{locationId}/products")
    @PreAuthorize("hasRole('ADMIN') or (hasAnyAuthority('WAREHOUSE_VIEW', 'WAREHOUSE_MANAGE'))")
    public ApiResponse getProductsByLocation(@PathVariable Long locationId) {
        List<ProductLocationResponse> list = warehouseService.getProductsByLocation(locationId);
        return ApiResponse.success("Danh sách sản phẩm lưu tại vị trí", list);
    }

    @GetMapping("/products/{productId}/locations")
    @PreAuthorize("hasRole('ADMIN') or (hasAnyAuthority('WAREHOUSE_VIEW', 'WAREHOUSE_MANAGE'))")
    public ApiResponse getLocationsByProduct(
            @PathVariable Long productId,
            @RequestParam(required = false) Long warehouseId) {
        List<ProductLocationResponse> list = warehouseService.getLocationsByProduct(productId, warehouseId);
        return ApiResponse.success("Vị trí lưu kho của sản phẩm", list);
    }

    @GetMapping("/warehouses/{warehouseId}/products/{productId}/location")
    @PreAuthorize("hasRole('ADMIN') or (hasAnyAuthority('WAREHOUSE_VIEW', 'WAREHOUSE_MANAGE'))")
    public ApiResponse getProductLocationInWarehouse(
            @PathVariable Long warehouseId,
            @PathVariable Long productId) {
        ProductLocationResponse response = warehouseService.getProductLocationInWarehouse(warehouseId, productId);
        return ApiResponse.success("Vị trí kệ chính xác của sản phẩm trong kho", response);
    }

    // ═══════════════════════════════════════════════════════════════
    // 5. DANH SÁCH SOẠN ĐƠN HÀNG KÈM VỊ TRÍ KỆ CHÍNH XÁC (S5-03)
    // ═══════════════════════════════════════════════════════════════

    @GetMapping("/orders/{orderId}/picking-list")
    @PreAuthorize("hasRole('ADMIN') or (hasAuthority('ORDER_VIEW'))")
    public ApiResponse getOrderPickingList(@PathVariable Long orderId) {
        OrderPickingListResponse pickingList = warehouseService.getOrderPickingList(orderId);
        return ApiResponse.success("Danh sách vị trí kệ soạn hàng theo đơn", pickingList);
    }

    // ═══════════════════════════════════════════════════════════════
    // 6. NHẬP / XUẤT / ĐIỀU CHỈNH / TỒN KHO (S1-05, S2-07, S4-03)
    // ═══════════════════════════════════════════════════════════════

    /**
     * API điều chỉnh tồn kho — CHỈ Quản lý kho (ROLE_WAREHOUSE_MANAGER) và Quản trị viên (ROLE_ADMIN).
     * Đảm bảo Nhân viên kinh doanh (ROLE_SALES) và Thủ kho (ROLE_WAREHOUSE_STAFF) không sửa được tồn kho!
     */
    @PostMapping("/adjust")
    @PreAuthorize("hasRole('ADMIN') or (hasAuthority('WAREHOUSE_ADJUST'))")
    public ApiResponse adjustInventory(
            @Valid @RequestBody WarehouseVoucherRequest request,
            Principal principal) {
        String username = principal != null ? principal.getName() : "system";
        WarehouseVoucherResponse response = warehouseService.adjustInventory(request, username);
        return ApiResponse.success("Điều chỉnh tồn kho thành công", response);
    }

    /**
     * API lập phiếu điều chỉnh từ phiếu kho đã xác nhận (S5-04).
     * Chỉ Quản lý kho và Quản trị viên được quyền điều chỉnh.
     */
    @PostMapping("/vouchers/{id}/adjust")
    @PreAuthorize("hasRole('ADMIN') or (hasAuthority('WAREHOUSE_ADJUST'))")
    public ApiResponse adjustVoucher(
            @PathVariable Long id,
            @Valid @RequestBody WarehouseVoucherRequest request,
            Principal principal) {
        String username = principal != null ? principal.getName() : "system";
        WarehouseVoucherResponse response = warehouseService.adjustVoucher(id, request, username);
        return ApiResponse.success("Lập phiếu điều chỉnh kho thành công", response);
    }

    /**
     * API nhập kho — Thủ kho, Quản lý kho, Quản trị viên (S2-07 & S5-04).
     * Nhận nhà cung cấp, số chứng từ, ngày nhập, kho nhập, số lô, hạn sử dụng.
     * Nếu request có draft=true hoặc status="DRAFT": lưu nháp, chưa cộng tồn kho.
     * Nếu không: ghi sổ và cộng tồn kho ngay.
     */
    @GetMapping("/import/options")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('WAREHOUSE_IMPORT', 'WAREHOUSE_APPROVE', 'WAREHOUSE_ADJUST')")
    public ApiResponse getImportOptions() {
        return ApiResponse.success("Danh mục lập phiếu nhập", warehouseService.getImportOptions());
    }

    @GetMapping("/imports")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('WAREHOUSE_IMPORT', 'WAREHOUSE_APPROVE', 'WAREHOUSE_ADJUST')")
    public ApiResponse getImports(@RequestParam(required = false) Long warehouseId) {
        return ApiResponse.success("Phiếu nhập kho", warehouseService.getImports(warehouseId));
    }

    @PostMapping("/import")
    @PreAuthorize("hasRole('ADMIN') or (hasAuthority('WAREHOUSE_IMPORT'))")
    public ApiResponse importGoods(
            @Valid @RequestBody WarehouseVoucherRequest request,
            Principal principal) {
        String username = principal != null ? principal.getName() : "system";
        WarehouseVoucherResponse response = warehouseService.importGoods(request, username);
        boolean isDraft = response != null && "DRAFT".equalsIgnoreCase(response.getStatus());
        String message = isDraft
                ? "Lưu phiếu nhập kho nháp thành công (chưa cộng tồn kho)"
                : "Tạo phiếu nhập kho thành công";
        return ApiResponse.success(message, response);
    }

    /**
     * API tạo nhanh phiếu nhập kho ở trạng thái Nháp (DRAFT) (S5-04).
     */
    @PostMapping("/import/draft")
    @PreAuthorize("hasRole('ADMIN') or (hasAuthority('WAREHOUSE_IMPORT'))")
    public ApiResponse createImportDraft(
            @Valid @RequestBody WarehouseVoucherRequest request,
            Principal principal) {
        String username = principal != null ? principal.getName() : "system";
        request.setStatus("DRAFT");
        request.setDraft(true);
        WarehouseVoucherResponse response = warehouseService.importGoods(request, username);
        return ApiResponse.success("Lưu phiếu nhập kho nháp thành công (chưa cộng tồn kho)", response);
    }

    /**
     * API xác nhận phiếu kho nháp — Thủ kho, Quản lý kho, Admin (S5-04).
     * Khi xác nhận mới thực hiện cộng tồn kho và cập nhật sổ tồn kho lô.
     */
    @PostMapping("/vouchers/{id}/confirm")
    @PreAuthorize("hasRole('ADMIN') or (hasAuthority('WAREHOUSE_APPROVE'))")
    public ApiResponse confirmVoucher(
            @PathVariable Long id,
            Principal principal) {
        String username = principal != null ? principal.getName() : "system";
        WarehouseVoucherResponse response = warehouseService.confirmVoucher(id, username);
        return ApiResponse.success("Xác nhận phiếu kho thành công, đã cộng tồn kho", response);
    }

    /**
     * API cập nhật phiếu kho nháp (S5-04).
     * Phiếu đã xác nhận (CONFIRMED / RECORDED) KHÔNG SỬA ĐƯỢC, sẽ trả lỗi để yêu cầu lập phiếu điều chỉnh.
     */
    @PutMapping("/vouchers/{id}")
    @PreAuthorize("hasRole('ADMIN') or (hasAuthority('WAREHOUSE_IMPORT'))")
    public ApiResponse updateVoucher(
            @PathVariable Long id,
            @Valid @RequestBody WarehouseVoucherRequest request,
            Principal principal) {
        String username = principal != null ? principal.getName() : "system";
        WarehouseVoucherResponse response = warehouseService.updateVoucher(id, request, username);
        return ApiResponse.success("Cập nhật phiếu kho thành công", response);
    }

    /**
     * API xóa phiếu kho nháp (S5-04).
     * Phiếu đã xác nhận KHÔNG THỂ XÓA, chỉ được lập phiếu điều chỉnh.
     */
    @DeleteMapping("/vouchers/{id}")
    @PreAuthorize("hasRole('ADMIN') or (hasAuthority('WAREHOUSE_IMPORT'))")
    public ApiResponse deleteVoucher(
            @PathVariable Long id,
            Principal principal) {
        String username = principal != null ? principal.getName() : "system";
        warehouseService.deleteVoucher(id, username);
        return ApiResponse.success("Xóa phiếu kho nháp thành công", null);
    }

    /**
     * API gợi ý lô xuất theo nguyên tắc FEFO (Hạn dùng gần nhất trước - S6-04).
     * Mặc định gợi ý lô có hạn sử dụng gần nhất còn đủ số lượng.
     */
    @GetMapping("/export/suggest-lot")
    @PreAuthorize("hasRole('ADMIN') or (hasAnyAuthority('WAREHOUSE_EXPORT', 'WAREHOUSE_VIEW'))")
    public ApiResponse suggestLotForExport(
            @RequestParam Long warehouseId,
            @RequestParam Long productId,
            @RequestParam(required = false, defaultValue = "1") BigDecimal quantity,
            @RequestParam(required = false) String unit) {
        LotSuggestionResponse response = warehouseService.suggestLotForExport(warehouseId, productId, quantity, unit);
        return ApiResponse.success("Gợi ý lô xuất kho theo FEFO", response);
    }

    /**
     * API gợi ý lô xuất hàng loạt cho nhiều sản phẩm theo FEFO (S6-04).
     */
    @PostMapping("/export/suggest-lots")
    @PreAuthorize("hasRole('ADMIN') or (hasAnyAuthority('WAREHOUSE_EXPORT', 'WAREHOUSE_VIEW'))")
    public ApiResponse suggestLotsForExport(
            @Valid @RequestBody BatchLotSuggestionRequest request) {
        List<LotSuggestionResponse> response = warehouseService.suggestLotsForExport(request);
        return ApiResponse.success("Gợi ý lô xuất kho hàng loạt theo FEFO", response);
    }

    /**
     * API xuất kho — Thủ kho, Quản lý kho, Quản trị viên.
     * Tự động quy đổi từ đơn vị xuất (thùng, lốc) về đơn vị cơ sở (lon) khi ghi sổ.
     */
    @PostMapping("/export")
    @PreAuthorize("hasRole('ADMIN') or (hasAuthority('WAREHOUSE_EXPORT'))")
    public ApiResponse exportGoods(
            @Valid @RequestBody WarehouseVoucherRequest request,
            Principal principal) {
        String username = principal != null ? principal.getName() : "system";
        WarehouseVoucherResponse response = warehouseService.exportGoods(request, username);
        return ApiResponse.success("Tạo phiếu xuất kho thành công", response);
    }

    /**
     * API xem danh sách tồn kho — Thủ kho, Quản lý kho, Quản lý KD, Admin, Viewer.
     * Sổ sách hiển thị số lượng theo đơn vị cơ sở (số lon) kèm các quy đổi tương đương (thùng, lốc)
     * và thông tin vị trí kệ/khu lưu trữ (S5-03).
     */
    @GetMapping("/inventory")
    @PreAuthorize("hasRole('ADMIN') or (hasAnyAuthority('WAREHOUSE_VIEW', 'ORDER_CREATE', 'WAREHOUSE_IMPORT', 'WAREHOUSE_EXPORT', 'WAREHOUSE_MANAGE'))")
    public ApiResponse getInventory(@RequestParam(required = false) Long warehouseId) {
        List<WarehouseInventoryResponse> stock = warehouseService.getInventory(warehouseId);
        return ApiResponse.success("Danh sách tồn kho", stock);
    }

    /**
     * API xem danh sách tồn kho theo Lô & Hạn sử dụng (S5-04, S6-01).
     * Hỗ trợ lọc theo kho, sản phẩm, trạng thái hạn dùng (EXPIRED, EXPIRING_SOON, ACTIVE), ngưỡng cảnh báo, từ khóa.
     */
    @GetMapping("/lots/options")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('WAREHOUSE_VIEW','WAREHOUSE_MANAGE')")
    public ApiResponse getLotManagementOptions() {
        return ApiResponse.success("Bộ chọn lô và hạn sử dụng", warehouseService.getLotManagementOptions());
    }

    @GetMapping("/inventory/lots")
    @PreAuthorize("hasRole('ADMIN') or (hasAnyAuthority('WAREHOUSE_VIEW', 'WAREHOUSE_MANAGE'))")
    public ApiResponse getLotInventory(
            @RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer thresholdDays,
            @RequestParam(required = false) String keyword) {
        List<WarehouseLotInventoryResponse> stock;
        if (status == null && thresholdDays == null && keyword == null) {
            stock = warehouseService.getLotInventory(warehouseId, productId);
        } else {
            stock = warehouseService.getLotInventory(warehouseId, productId, status, thresholdDays, keyword);
        }
        return ApiResponse.success("Danh sách tồn kho theo lô", stock);
    }

    /**
     * API cảnh báo các lô sắp hết hạn hoặc đã quá hạn theo ngưỡng khai báo được (S6-01).
     */
    @GetMapping("/lot-expiry-alerts")
    @PreAuthorize("hasRole('ADMIN') or (hasAnyAuthority('WAREHOUSE_VIEW', 'WAREHOUSE_MANAGE'))")
    public ApiResponse getLotExpiryAlerts(
            @RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) Long productId,
            @RequestParam(required = false) Integer thresholdDays,
            @RequestParam(required = false) String status) {
        LotExpiryAlertResponse alerts = warehouseService.getLotExpiryAlerts(
                warehouseId, productId, thresholdDays, status);
        return ApiResponse.success("Danh sách cảnh báo hạn sử dụng lô", alerts);
    }

    /**
     * API xem danh sách ngưỡng cảnh báo hạn sử dụng theo từng SKU (S6-01).
     */
    @GetMapping("/lot-thresholds")
    @PreAuthorize("hasRole('ADMIN') or (hasAnyAuthority('WAREHOUSE_VIEW', 'WAREHOUSE_MANAGE'))")
    public ApiResponse getLotThresholds(@RequestParam(required = false) String keyword) {
        List<LotThresholdResponse> thresholds = warehouseService.getLotThresholds(keyword);
        return ApiResponse.success("Danh sách ngưỡng cảnh báo hạn sử dụng", thresholds);
    }

    /**
     * API Quản lý kho khai báo/cập nhật ngưỡng cảnh báo hạn sử dụng cho SKU (S6-01).
     */
    @PutMapping("/lot-thresholds/{productId}")
    @PreAuthorize("hasRole('ADMIN') or (hasAuthority('WAREHOUSE_MANAGE'))")
    public ApiResponse updateLotThreshold(
            @PathVariable Long productId,
            @Valid @RequestBody com.quanlybanhang.dto.request.LotThresholdRequest request,
            Principal principal) {
        String username = principal != null ? principal.getName() : "system";
        LotThresholdResponse response = warehouseService.updateLotThreshold(
                productId, request.getThresholdDays(), username);
        return ApiResponse.success("Cập nhật ngưỡng cảnh báo thành công", response);
    }

    /**
     * API xem danh sách các phiếu kho đã tạo (lọc theo kho, loại phiếu, trạng thái, nhà cung cấp).
     */
    @GetMapping("/vouchers")
    @PreAuthorize("hasRole('ADMIN') or (hasAuthority('WAREHOUSE_VIEW'))")
    public ApiResponse getVouchers(
            @RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) WarehouseVoucherType type,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long supplierId) {
        List<WarehouseVoucherResponse> vouchers = warehouseService.getVouchers(warehouseId, type, status, supplierId);
        return ApiResponse.success("Danh sách phiếu kho", vouchers);
    }

    /**
     * API xem chi tiết phiếu kho theo ID.
     */
    @GetMapping("/vouchers/{id}")
    @PreAuthorize("hasRole('ADMIN') or (hasAuthority('WAREHOUSE_VIEW'))")
    public ApiResponse getVoucherById(@PathVariable Long id) {
        WarehouseVoucherResponse voucher = warehouseService.getVoucherById(id);
        return ApiResponse.success("Chi tiết phiếu kho", voucher);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // API CHUYỂN KHO NỘI BỘ (S5-07)
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Lập phiếu chuyển kho nội bộ — Thủ kho, Quản lý kho, Quản trị viên.
     * Hàng xuất khỏi kho đi, ghi nhận đang trên đường chuyển, CHƯA cộng vào kho đến.
     */
    @PostMapping(value = {"/transfers", "/transfer"})
    @PreAuthorize("hasRole('ADMIN') or (hasAuthority('WAREHOUSE_TRANSFER'))")
    public ApiResponse createTransfer(
            @Valid @RequestBody WarehouseTransferRequest request,
            Principal principal) {
        String username = principal != null ? principal.getName() : "system";
        WarehouseTransferResponse response = warehouseService.createTransfer(request, username);
        return ApiResponse.success("Lập phiếu chuyển kho thành công", response);
    }

    /**
     * Danh sách phiếu chuyển kho nội bộ.
     */
    @GetMapping("/transfers")
    @PreAuthorize("hasRole('ADMIN') or (hasAnyAuthority('WAREHOUSE_VIEW', 'WAREHOUSE_TRANSFER'))")
    public ApiResponse getTransfers(
            @RequestParam(required = false) Long fromWarehouseId,
            @RequestParam(required = false) Long toWarehouseId,
            @RequestParam(required = false) WarehouseTransferStatus status) {
        List<WarehouseTransferResponse> list = warehouseService.getTransfers(fromWarehouseId, toWarehouseId, status);
        return ApiResponse.success("Danh sách phiếu chuyển kho", list);
    }

    @GetMapping("/transfers/options")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('WAREHOUSE_VIEW', 'WAREHOUSE_TRANSFER')")
    public ApiResponse transferOptions() {
        return ApiResponse.success("Bộ chọn chuyển kho", warehouseService.getTransferOptions());
    }

    @GetMapping("/transfers/products")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('WAREHOUSE_VIEW', 'WAREHOUSE_TRANSFER')")
    public ApiResponse transferProducts(@RequestParam Long warehouseId, @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success("Hàng tại kho đi", warehouseService.getTransferStock(warehouseId, keyword, page, size));
    }

    @GetMapping("/transfers/search")
    @PreAuthorize("hasRole('ADMIN') or hasAnyAuthority('WAREHOUSE_VIEW', 'WAREHOUSE_TRANSFER')")
    public ApiResponse searchTransfers(@RequestParam(required = false) Long fromWarehouseId,
            @RequestParam(required = false) Long toWarehouseId, @RequestParam(required = false) WarehouseTransferStatus status,
            @RequestParam(required = false) String keyword, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success("Danh sách phiếu chuyển kho", warehouseService.searchTransfers(
                fromWarehouseId, toWarehouseId, status, keyword, page, size));
    }

    /**
     * Chi tiết phiếu chuyển kho nội bộ theo ID.
     */
    @GetMapping("/transfers/{id}")
    @PreAuthorize("hasRole('ADMIN') or (hasAnyAuthority('WAREHOUSE_VIEW', 'WAREHOUSE_TRANSFER'))")
    public ApiResponse getTransferById(@PathVariable Long id) {
        WarehouseTransferResponse transfer = warehouseService.getTransferById(id);
        return ApiResponse.success("Chi tiết phiếu chuyển kho", transfer);
    }

    /**
     * Kho đến xác nhận nhận hàng.
     * Khi kho đến xác nhận nhận đủ/thực nhận thì tồn mới được cộng.
     * Chênh lệch khi nhận bắt buộc phải nhập lý do giải trình.
     */
    @PostMapping("/transfers/{id}/receive")
    @PreAuthorize("hasRole('ADMIN') or (hasAuthority('WAREHOUSE_TRANSFER'))")
    public ApiResponse receiveTransfer(
            @PathVariable Long id,
            @RequestBody(required = false) WarehouseTransferReceiveRequest request,
            Principal principal) {
        String username = principal != null ? principal.getName() : "system";
        WarehouseTransferResponse response = warehouseService.receiveTransfer(id, request, username);
        return ApiResponse.success("Xác nhận nhận hàng thành công", response);
    }

    /**
     * Hủy phiếu chuyển kho nội bộ (nếu chưa nhận hàng).
     * Hoàn lại tồn kho cho kho xuất và xóa hàng đang trên đường.
     */
    @PostMapping("/transfers/{id}/cancel")
    @PreAuthorize("hasRole('ADMIN') or (hasAuthority('WAREHOUSE_TRANSFER'))")
    public ApiResponse cancelTransfer(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, String> body,
            Principal principal) {
        String username = principal != null ? principal.getName() : "system";
        String reason = body != null ? body.get("reason") : null;
        WarehouseTransferResponse response = warehouseService.cancelTransfer(id, reason, username);
        return ApiResponse.success("Hủy phiếu chuyển kho thành công", response);
    }
}
