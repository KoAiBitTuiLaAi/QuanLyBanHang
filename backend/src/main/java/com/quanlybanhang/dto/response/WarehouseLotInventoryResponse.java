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
}

