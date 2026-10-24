package com.quanlybanhang.dto.response;

import lombok.*;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WarehouseVoucherItemResponse {

    private Long id;
    private Long productId;
    private String productCode;
    private String productName;

    /**
     * Đơn vị tính được sử dụng lúc lập phiếu (vd: "thùng").
     */
    private String unit;

    /**
     * Số lượng theo đơn vị lúc lập phiếu (vd: 10 thùng).
     */
    private BigDecimal quantity;

    /**
     * Hệ số quy đổi đóng băng tại thời điểm ghi sổ (vd: 24.0).
     */
    private BigDecimal conversionFactor;

    /**
     * Đơn vị cơ sở đóng băng tại thời điểm ghi sổ (vd: "lon").
     */
    private String baseUnit;

    /**
     * Số lượng đã quy về đơn vị cơ sở khi ghi sổ (vd: 240 lon = 10 * 24.0).
     */
    private BigDecimal baseQuantity;

    private BigDecimal price;
    private BigDecimal totalAmount;
    private String lotNumber;
    private java.time.LocalDate expiryDate;
    private java.time.LocalDate manufacturingDate;

    /**
     * Số lô hệ thống gợi ý theo FEFO (S6-04).
     */
    private String suggestedLotNumber;

    /**
     * Lý do chọn lô khác lô gợi ý FEFO (S6-04).
     */
    private String overrideReason;

    private Boolean manageLot;
    private Boolean overrideExpiredLot;
    private String expiredLotOverrideReason;
    private String note;
}
