package com.quanlybanhang.dto.response;

import com.quanlybanhang.model.WarehouseVoucherType;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WarehouseVoucherResponse {

    private Long id;
    private String voucherCode;
    private String documentNumber;
    private LocalDateTime voucherDate;
    private WarehouseVoucherType type;
    private Long warehouseId;
    private String warehouseCode;
    private String warehouseName;
    private Long supplierId;
    private String supplierCode;
    private String supplierName;
    private String status;
    private Long referenceVoucherId;
    private String referenceVoucherCode;
    private String adjustmentReason;
    private String note;
    private String createdBy;
    private LocalDateTime recordedAt;
    private List<WarehouseVoucherItemResponse> items;
}
