package com.quanlybanhang.service;

import com.quanlybanhang.dto.request.WarehouseVoucherRequest;
import com.quanlybanhang.dto.response.WarehouseInventoryResponse;
import com.quanlybanhang.dto.response.NamedResourceResponse;
import com.quanlybanhang.dto.response.WarehouseVoucherResponse;
import com.quanlybanhang.model.WarehouseVoucherType;

import java.util.List;

public interface WarehouseService {
    List<NamedResourceResponse> getWarehouseOptions();

    WarehouseVoucherResponse importGoods(WarehouseVoucherRequest request, String username);

    WarehouseVoucherResponse exportGoods(WarehouseVoucherRequest request, String username);

    WarehouseVoucherResponse adjustInventory(WarehouseVoucherRequest request, String username);

    List<WarehouseInventoryResponse> getInventory(Long warehouseId);

    List<WarehouseVoucherResponse> getVouchers(Long warehouseId, WarehouseVoucherType type);

    List<WarehouseVoucherResponse> getVouchers(Long warehouseId, WarehouseVoucherType type, String status, Long supplierId);

    WarehouseVoucherResponse getVoucherById(Long id);

    // S5-04: Quản lý Phiếu nhập kho từ Nhà cung cấp, Lô & Hạn sử dụng, Xác nhận / Nháp / Điều chỉnh
    WarehouseVoucherResponse confirmVoucher(Long id, String username);

    WarehouseVoucherResponse updateVoucher(Long id, WarehouseVoucherRequest request, String username);

    void deleteVoucher(Long id, String username);

    WarehouseVoucherResponse adjustVoucher(Long id, WarehouseVoucherRequest request, String username);

    List<com.quanlybanhang.dto.response.WarehouseLotInventoryResponse> getLotInventory(Long warehouseId, Long productId);
}
