package com.quanlybanhang.service;

import com.quanlybanhang.dto.response.*;
import org.springframework.data.domain.Page;

import java.time.LocalDate;
import java.util.List;

/**
 * Service xử lý Báo cáo tồn kho và vòng quay hàng (S8-07):
 * - Giá trị tồn theo kho và theo nhóm hàng
 * - Số ngày tồn bình quân của từng SKU, chỉ ra hàng chậm luân chuyển đang chôn vốn
 * - Xuất Excel
 */
public interface InventoryTurnoverReportService {

    InventoryTurnoverOptionsResponse getOptions();

    InventoryTurnoverReportResponse getReport(
            Long warehouseId,
            Long categoryId,
            LocalDate fromDate,
            LocalDate toDate,
            Integer periodDays,
            Integer slowMovingThresholdDays,
            String keyword,
            Boolean slowMovingOnly,
            String turnoverStatus,
            Boolean includeZeroStock);

    List<WarehouseInventoryValuationResponse> getValuationByWarehouse(
            Long warehouseId,
            Long categoryId,
            LocalDate fromDate,
            LocalDate toDate,
            Integer periodDays,
            Integer slowMovingThresholdDays);

    List<CategoryInventoryValuationResponse> getValuationByCategory(
            Long warehouseId,
            Long categoryId,
            LocalDate fromDate,
            LocalDate toDate,
            Integer periodDays,
            Integer slowMovingThresholdDays);

    List<WarehouseCategoryValuationResponse> getValuationByWarehouseAndCategory(
            Long warehouseId,
            Long categoryId,
            LocalDate fromDate,
            LocalDate toDate,
            Integer periodDays,
            Integer slowMovingThresholdDays);

    Page<SkuInventoryTurnoverResponse> getSkuTurnovers(
            Long warehouseId,
            Long categoryId,
            LocalDate fromDate,
            LocalDate toDate,
            Integer periodDays,
            Integer slowMovingThresholdDays,
            String keyword,
            Boolean slowMovingOnly,
            String turnoverStatus,
            Boolean includeZeroStock,
            int page,
            int size);

    List<SkuInventoryTurnoverResponse> getSlowMovingSkus(
            Long warehouseId,
            Long categoryId,
            LocalDate fromDate,
            LocalDate toDate,
            Integer periodDays,
            Integer slowMovingThresholdDays,
            String keyword);
}

