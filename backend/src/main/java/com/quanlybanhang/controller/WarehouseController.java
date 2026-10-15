package com.quanlybanhang.controller;

import com.quanlybanhang.dto.request.WarehouseVoucherRequest;
import com.quanlybanhang.dto.response.ApiResponse;
import com.quanlybanhang.dto.response.WarehouseInventoryResponse;
import com.quanlybanhang.dto.response.WarehouseLotInventoryResponse;
import com.quanlybanhang.dto.response.WarehouseVoucherResponse;
import com.quanlybanhang.model.WarehouseVoucherType;
import com.quanlybanhang.service.WarehouseService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;

/**
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

    @GetMapping("/options")
    @PreAuthorize("hasAuthority('WAREHOUSE_VIEW') or hasAnyRole('WAREHOUSE_STAFF', 'WAREHOUSE_MANAGER', 'SALES_MANAGER', 'ADMIN', 'VIEWER')")
    public ApiResponse warehouseOptions() {
        return ApiResponse.success("Danh sách kho đang hoạt động", warehouseService.getWarehouseOptions());
    }

    /**
     * API điều chỉnh tồn kho — CHỈ Quản lý kho (ROLE_WAREHOUSE_MANAGER) và Quản trị viên (ROLE_ADMIN).
     * Đảm bảo Nhân viên kinh doanh (ROLE_SALES) và Thủ kho (ROLE_WAREHOUSE_STAFF) không sửa được tồn kho!
     */
    @PostMapping("/adjust")
    @PreAuthorize("hasAuthority('WAREHOUSE_ADJUST') or hasAnyRole('WAREHOUSE_MANAGER', 'ADMIN')")
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
    @PreAuthorize("hasAuthority('WAREHOUSE_ADJUST') or hasAnyRole('WAREHOUSE_MANAGER', 'ADMIN')")
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
    @PostMapping("/import")
    @PreAuthorize("hasAuthority('WAREHOUSE_IMPORT') or hasAnyRole('WAREHOUSE_STAFF', 'WAREHOUSE_MANAGER', 'ADMIN')")
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
    @PreAuthorize("hasAuthority('WAREHOUSE_IMPORT') or hasAnyRole('WAREHOUSE_STAFF', 'WAREHOUSE_MANAGER', 'ADMIN')")
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
    @PreAuthorize("hasAuthority('WAREHOUSE_IMPORT') or hasAnyRole('WAREHOUSE_STAFF', 'WAREHOUSE_MANAGER', 'ADMIN')")
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
    @PreAuthorize("hasAuthority('WAREHOUSE_IMPORT') or hasAnyRole('WAREHOUSE_STAFF', 'WAREHOUSE_MANAGER', 'ADMIN')")
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
    @PreAuthorize("hasAuthority('WAREHOUSE_IMPORT') or hasAnyRole('WAREHOUSE_STAFF', 'WAREHOUSE_MANAGER', 'ADMIN')")
    public ApiResponse deleteVoucher(
            @PathVariable Long id,
            Principal principal) {
        String username = principal != null ? principal.getName() : "system";
        warehouseService.deleteVoucher(id, username);
        return ApiResponse.success("Xóa phiếu kho nháp thành công", null);
    }

    /**
     * API xuất kho — Thủ kho, Quản lý kho, Quản trị viên.
     * Tự động quy đổi từ đơn vị xuất (thùng, lốc) về đơn vị cơ sở (lon) khi ghi sổ.
     */
    @PostMapping("/export")
    @PreAuthorize("hasAuthority('WAREHOUSE_EXPORT') or hasAnyRole('WAREHOUSE_STAFF', 'WAREHOUSE_MANAGER', 'ADMIN')")
    public ApiResponse exportGoods(
            @Valid @RequestBody WarehouseVoucherRequest request,
            Principal principal) {
        String username = principal != null ? principal.getName() : "system";
        WarehouseVoucherResponse response = warehouseService.exportGoods(request, username);
        return ApiResponse.success("Tạo phiếu xuất kho thành công", response);
    }

    /**
     * API xem danh sách tồn kho — Thủ kho, Quản lý kho, Quản lý KD, Admin, Viewer.
     * Sổ sách hiển thị số lượng theo đơn vị cơ sở (số lon) kèm các quy đổi tương đương (thùng, lốc).
     */
    @GetMapping("/inventory")
    @PreAuthorize("hasAuthority('WAREHOUSE_VIEW') or hasAnyRole('WAREHOUSE_STAFF', 'WAREHOUSE_MANAGER', 'SALES_MANAGER', 'ADMIN', 'VIEWER')")
    public ApiResponse getInventory(@RequestParam(required = false) Long warehouseId) {
        List<WarehouseInventoryResponse> stock = warehouseService.getInventory(warehouseId);
        return ApiResponse.success("Danh sách tồn kho", stock);
    }

    /**
     * API xem danh sách tồn kho theo Lô & Hạn sử dụng (S5-04).
     */
    @GetMapping("/inventory/lots")
    @PreAuthorize("hasAuthority('WAREHOUSE_VIEW') or hasAnyRole('WAREHOUSE_STAFF', 'WAREHOUSE_MANAGER', 'SALES_MANAGER', 'ADMIN', 'VIEWER')")
    public ApiResponse getLotInventory(
            @RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) Long productId) {
        List<WarehouseLotInventoryResponse> stock = warehouseService.getLotInventory(warehouseId, productId);
        return ApiResponse.success("Danh sách tồn kho theo lô", stock);
    }

    /**
     * API xem danh sách các phiếu kho đã tạo (lọc theo kho, loại phiếu, trạng thái, nhà cung cấp).
     */
    @GetMapping("/vouchers")
    @PreAuthorize("hasAuthority('WAREHOUSE_VIEW') or hasAnyRole('WAREHOUSE_STAFF', 'WAREHOUSE_MANAGER', 'SALES_MANAGER', 'ADMIN', 'VIEWER')")
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
    @PreAuthorize("hasAuthority('WAREHOUSE_VIEW') or hasAnyRole('WAREHOUSE_STAFF', 'WAREHOUSE_MANAGER', 'SALES_MANAGER', 'ADMIN', 'VIEWER')")
    public ApiResponse getVoucherById(@PathVariable Long id) {
        WarehouseVoucherResponse voucher = warehouseService.getVoucherById(id);
        return ApiResponse.success("Chi tiết phiếu kho", voucher);
    }
}
