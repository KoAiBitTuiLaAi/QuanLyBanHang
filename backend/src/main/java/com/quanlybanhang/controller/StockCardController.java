package com.quanlybanhang.controller;

import com.quanlybanhang.dto.response.ApiResponse;
import com.quanlybanhang.service.StockCardService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

/**
 * Controller thẻ kho (S6-02).
 *
 * <p>Endpoint: {@code GET /api/warehouse/stock-card/{productId}}
 *
 * <p>Quyền: {@code WAREHOUSE_VIEW} hoặc {@code ROLE_ADMIN}.
 * Phạm vi kho áp theo phân công trong ma trận quyền DB (không fallback role).
 */
@RestController
@RequestMapping("/api/warehouse/stock-card")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN') or hasAuthority('WAREHOUSE_VIEW')")
public class StockCardController {

    private final StockCardService service;

    @GetMapping("/options")
    public ApiResponse options(){return ApiResponse.success("Bộ chọn thẻ kho",service.options());}

    @GetMapping("/{productId}/sources/{sourceType}/{sourceId}")
    public ApiResponse source(@PathVariable Long productId,@PathVariable String sourceType,@PathVariable Long sourceId,
            @RequestParam(required=false) Long warehouseId){return ApiResponse.success("Chứng từ gốc — các phát sinh của SKU",service.source(productId,sourceType,sourceId,warehouseId));}

    /**
     * Lấy thẻ kho phân trang của một sản phẩm.
     *
     * @param productId   ID sản phẩm (bắt buộc)
     * @param warehouseId Lọc theo kho (tùy chọn)
     * @param fromDate    Từ ngày (tùy chọn, ISO 8601)
     * @param toDate      Đến ngày (tùy chọn, ISO 8601)
     * @param page        Trang 0-based (mặc định 0)
     * @param size        Số dòng mỗi trang (mặc định 50)
     */
    @GetMapping("/{productId}")
    public ApiResponse getStockCard(
            @PathVariable Long productId,
            @RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime toDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.success("Thẻ kho sản phẩm",
                service.getStockCard(productId, warehouseId, fromDate, toDate, page, size));
    }
}
