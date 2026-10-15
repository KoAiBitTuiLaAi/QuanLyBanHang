package com.quanlybanhang.dto.response;

import lombok.*;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WarehouseTransferItemResponse {

    private Long id;

    private Long productId;
    private String productCode;
    private String productName;

    private String unit;
    private BigDecimal quantity;
    private BigDecimal conversionFactor;
    private String baseUnit;
    private BigDecimal baseQuantity;

    private BigDecimal receivedQuantity;
    private BigDecimal receivedBaseQuantity;
    private BigDecimal discrepancyQuantity;
    private BigDecimal discrepancyBaseQuantity;
    private String discrepancyReason;

    private String note;
}

