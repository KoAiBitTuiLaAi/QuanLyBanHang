package com.quanlybanhang.dto.response;

import com.quanlybanhang.model.InvoiceStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Thông tin hóa đơn gốc để Nhân viên kinh doanh chọn lập phiếu trả hàng (S8-01).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EligibleReturnInvoiceResponse {

    private Long invoiceId;
    private String invoiceNumber;
    private String invoiceCode;
    private LocalDateTime invoiceDate;
    private InvoiceStatus status;

    private Long dealerId;
    private String dealerCode;
    private String dealerName;

    private Long warehouseId;
    private String warehouseCode;
    private String warehouseName;

    private BigDecimal totalAmount;
    private BigDecimal totalDeliveredQuantity;
    private BigDecimal totalReturnedQuantity;
    private BigDecimal totalReturnableQuantity;

    private List<EligibleReturnInvoiceItemResponse> items;
}

