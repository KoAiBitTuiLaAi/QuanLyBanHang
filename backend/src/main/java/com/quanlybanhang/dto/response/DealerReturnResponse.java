package com.quanlybanhang.dto.response;

import com.quanlybanhang.model.DealerReturnStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Response chi tiết phiếu trả hàng từ đại lý và nhập lại kho (S8-01).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DealerReturnResponse {

    private Long id;
    private Long version;
    private String returnCode;

    private Long invoiceId;
    private String invoiceCode;
    private String invoiceNumber;

    private Long dealerId;
    private String dealerCode;
    private String dealerName;

    private Long warehouseId;
    private String warehouseCode;
    private String warehouseName;

    private Long defectiveWarehouseId;
    private String defectiveWarehouseCode;
    private String defectiveWarehouseName;

    private DealerReturnStatus status;
    private String statusDisplayName;

    /** Cho biết phiếu trả đã được Quản lý kho xác nhận cộng tồn hay chưa. */
    private Boolean inventoryAdded;

    private String returnReason;
    private BigDecimal totalReturnQuantity;
    private BigDecimal totalAmount;
    private String note;

    private String createdBy;
    private LocalDateTime createdAt;

    private String confirmedBy;
    private LocalDateTime confirmedAt;
    private String confirmNote;

    private String cancelledBy;
    private LocalDateTime cancelledAt;
    private String cancelReason;

    private LocalDateTime updatedAt;

    private Boolean canConfirm;
    private Boolean canCancel;

    private List<DealerReturnItemResponse> items;
}

