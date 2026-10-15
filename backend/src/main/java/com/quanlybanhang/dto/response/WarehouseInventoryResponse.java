package com.quanlybanhang.dto.response;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * Response DTO xem tồn kho (S2-07).
 * Hiển thị số lượng thực tế theo đơn vị cơ sở (số lon)
 * cùng thông tin quy đổi tương đương theo các đơn vị khác (thùng, lốc...).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WarehouseInventoryResponse {

    private Long id;
    private Long warehouseId;
    private String warehouseCode;
    private String warehouseName;

    private Long productId;
    private String productCode;
    private String productName;

    /**
     * Số lượng tồn ghi sổ theo đơn vị cơ sở (vd: 240 lon).
     */
    private BigDecimal baseQuantity;

    /**
     * Số lượng hàng đang trên đường chuyển đến kho này theo đơn vị cơ sở.
     */
    private BigDecimal inTransitQuantity;

    /**
     * Tên đơn vị cơ sở (vd: "lon").
     */
    private String baseUnit;

    /**
     * Quy đổi tương đương theo các đơn vị tính khác (vd: "thùng": 10.0, "lốc": 40.0).
     */
    private Map<String, BigDecimal> convertedViews;

    private LocalDateTime updatedAt;
}
