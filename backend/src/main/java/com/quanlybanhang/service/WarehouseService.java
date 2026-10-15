package com.quanlybanhang.service;

import com.quanlybanhang.dto.request.WarehouseVoucherRequest;
import com.quanlybanhang.dto.response.NamedResourceResponse;
import com.quanlybanhang.dto.response.WarehouseInventoryResponse;
import com.quanlybanhang.dto.response.WarehouseTransferResponse;
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

    WarehouseVoucherResponse getVoucherById(Long id);

    // Phiếu chuyển kho nội bộ (S5-07)
    WarehouseTransferResponse createTransfer(com.quanlybanhang.dto.request.WarehouseTransferRequest request, String username);

    WarehouseTransferResponse receiveTransfer(Long transferId, com.quanlybanhang.dto.request.WarehouseTransferReceiveRequest request, String username);

    WarehouseTransferResponse cancelTransfer(Long transferId, String reason, String username);

    List<com.quanlybanhang.dto.response.WarehouseTransferResponse> getTransfers(
            Long fromWarehouseId, Long toWarehouseId, com.quanlybanhang.model.WarehouseTransferStatus status);

    com.quanlybanhang.dto.response.WarehouseTransferResponse getTransferById(Long id);
}
