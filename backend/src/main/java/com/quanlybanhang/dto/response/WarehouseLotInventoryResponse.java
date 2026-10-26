package com.quanlybanhang.dto.response;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WarehouseLotInventoryResponse {
    private Long id;
    private Long warehouseId;
    private String warehouseCode;
    private String warehouseName;
    private Long productId;
    private String productCode;
    private String productName;
    private String lotNumber;
    private LocalDate expiryDate;
    private LocalDate manufacturingDate;
    private BigDecimal baseQuantity;
    private String baseUnit;
    private LocalDateTime updatedAt;

    /**
     * Ngưỡng cảnh báo áp dụng (số ngày) (S6-01).
     */
    private Integer warningThresholdDays;

    /**
     * Số ngày còn lại đến hạn sử dụng. Âm nếu đã quá hạn.
     */
    private Long daysUntilExpiry;

    /**
     * true nếu lô đã quá hạn sử dụng.
     */
    private Boolean isExpired;

    /**
     * true nếu lô sắp hết hạn (trong ngưỡng cảnh báo).
     */
    private Boolean isExpiringSoon;

    /**
     * Trạng thái hạn dùng: "EXPIRED", "EXPIRING_SOON", "ACTIVE".
     */
    private String status;
}
