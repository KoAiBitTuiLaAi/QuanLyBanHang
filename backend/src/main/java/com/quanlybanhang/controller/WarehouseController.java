package com.quanlybanhang.controller;

import com.quanlybanhang.dto.request.WarehouseTransferReceiveRequest;
import com.quanlybanhang.dto.request.WarehouseTransferRequest;
import com.quanlybanhang.dto.request.WarehouseVoucherRequest;
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

import java.security.Principal;
import java.util.List;
import java.util.Map;

/**
 * Controller Quản lý Kho & Tồn kho (S1-05 & S2-07).
 *
 * Quy tắc nghiệp vụ cốt lõi:
 * - Nhân viên kinh doanh (ROLE_SALES) và Thủ kho (ROLE_WAREHOUSE_STAFF)
 *   KHÔNG ĐƯỢC PHÉP điều chỉnh tồn kho (/api/warehouse/adjust).
 *   Chỉ Quản lý kho (ROLE_WAREHOUSE_MANAGER) và Quản trị viên (ROLE_ADMIN) được điều chỉnh.
 * - Thủ kho (ROLE_WAREHOUSE_STAFF) được phép nhập/xuất kho (/api/warehouse/import, /api/warehouse/export)
 *   và xem tồn kho (/api/warehouse/inventory).
 * - S2-07: Nhập/xuất theo bất kỳ đơn vị nào (thùng, lốc, lon) đều được tự động quy về
 *   đơn vị cơ sở (số lon) để ghi sổ sách kho.
 */
@RestController
@RequestMapping("/api/warehouse")
@RequiredArgsConstructor
public class WarehouseController {

    private final WarehouseService warehouseService;

    @GetMapping("/options")
    @PreAuthorize("hasAnyRole('WAREHOUSE_STAFF', 'WAREHOUSE_MANAGER', 'SALES_MANAGER', 'ADMIN', 'VIEWER')")
    public ApiResponse warehouseOptions() {
        return ApiResponse.success("Danh sách kho đang hoạt động", warehouseService.getWarehouseOptions());
    }

    /**
     * API điều chỉnh tồn kho — CHỈ Quản lý kho (ROLE_WAREHOUSE_MANAGER) và Quản trị viên.
     * Đảm bảo Nhân viên kinh doanh (ROLE_SALES) và Thủ kho (ROLE_WAREHOUSE_STAFF) không sửa được tồn kho!
     */
    @PostMapping("/adjust")
    @PreAuthorize("hasAnyRole('WAREHOUSE_MANAGER', 'ADMIN')")
    public ApiResponse adjustInventory(
            @Valid @RequestBody WarehouseVoucherRequest request,
            Principal principal) {
        String username = principal != null ? principal.getName() : "system";
        WarehouseVoucherResponse response = warehouseService.adjustInventory(request, username);
        return ApiResponse.success("Điều chỉnh tồn kho thành công", response);
    }

    /**
     * API nhập kho — Thủ kho, Quản lý kho, Quản trị viên.
     * Tự động quy đổi từ đơn vị nhập (thùng, lốc) về đơn vị cơ sở (lon) khi ghi sổ.
     */
    @PostMapping("/import")
    @PreAuthorize("hasAnyRole('WAREHOUSE_STAFF', 'WAREHOUSE_MANAGER', 'ADMIN')")
    public ApiResponse importGoods(
            @Valid @RequestBody WarehouseVoucherRequest request,
            Principal principal) {
        String username = principal != null ? principal.getName() : "system";
        WarehouseVoucherResponse response = warehouseService.importGoods(request, username);
        return ApiResponse.success("Tạo phiếu nhập kho thành công", response);
    }

    /**
     * API xuất kho — Thủ kho, Quản lý kho, Quản trị viên.
     * Tự động quy đổi từ đơn vị xuất (thùng, lốc) về đơn vị cơ sở (lon) khi ghi sổ.
     */
    @PostMapping("/export")
    @PreAuthorize("hasAnyRole('WAREHOUSE_STAFF', 'WAREHOUSE_MANAGER', 'ADMIN')")
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
    @PreAuthorize("hasAnyRole('WAREHOUSE_STAFF', 'WAREHOUSE_MANAGER', 'SALES_MANAGER', 'ADMIN', 'VIEWER')")
    public ApiResponse getInventory(@RequestParam(required = false) Long warehouseId) {
        List<WarehouseInventoryResponse> stock = warehouseService.getInventory(warehouseId);
        return ApiResponse.success("Danh sách tồn kho", stock);
    }

    /**
     * API xem danh sách các phiếu kho đã ghi sổ.
     */
    @GetMapping("/vouchers")
    @PreAuthorize("hasAnyRole('WAREHOUSE_STAFF', 'WAREHOUSE_MANAGER', 'SALES_MANAGER', 'ADMIN', 'VIEWER')")
    public ApiResponse getVouchers(
            @RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) WarehouseVoucherType type) {
        List<WarehouseVoucherResponse> vouchers = warehouseService.getVouchers(warehouseId, type);
        return ApiResponse.success("Danh sách phiếu kho", vouchers);
    }

    /**
     * API xem chi tiết phiếu kho theo ID.
     */
    @GetMapping("/vouchers/{id}")
    @PreAuthorize("hasAnyRole('WAREHOUSE_STAFF', 'WAREHOUSE_MANAGER', 'SALES_MANAGER', 'ADMIN', 'VIEWER')")
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
    @PreAuthorize("hasAuthority('WAREHOUSE_TRANSFER') or hasAuthority('WAREHOUSE_EXPORT') or hasAnyRole('WAREHOUSE_STAFF', 'WAREHOUSE_MANAGER', 'ADMIN')")
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
    @PreAuthorize("hasAnyAuthority('WAREHOUSE_TRANSFER', 'WAREHOUSE_VIEW') or hasAnyRole('WAREHOUSE_STAFF', 'WAREHOUSE_MANAGER', 'SALES_MANAGER', 'ADMIN', 'VIEWER')")
    public ApiResponse getTransfers(
            @RequestParam(required = false) Long fromWarehouseId,
            @RequestParam(required = false) Long toWarehouseId,
            @RequestParam(required = false) WarehouseTransferStatus status) {
        List<WarehouseTransferResponse> list = warehouseService.getTransfers(fromWarehouseId, toWarehouseId, status);
        return ApiResponse.success("Danh sách phiếu chuyển kho", list);
    }

    /**
     * Chi tiết phiếu chuyển kho nội bộ theo ID.
     */
    @GetMapping("/transfers/{id}")
    @PreAuthorize("hasAnyAuthority('WAREHOUSE_TRANSFER', 'WAREHOUSE_VIEW') or hasAnyRole('WAREHOUSE_STAFF', 'WAREHOUSE_MANAGER', 'SALES_MANAGER', 'ADMIN', 'VIEWER')")
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
    @PreAuthorize("hasAuthority('WAREHOUSE_TRANSFER') or hasAuthority('WAREHOUSE_IMPORT') or hasAnyRole('WAREHOUSE_STAFF', 'WAREHOUSE_MANAGER', 'ADMIN')")
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
    @PreAuthorize("hasAuthority('WAREHOUSE_TRANSFER') or hasAuthority('WAREHOUSE_APPROVE') or hasAnyRole('WAREHOUSE_MANAGER', 'ADMIN')")
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
