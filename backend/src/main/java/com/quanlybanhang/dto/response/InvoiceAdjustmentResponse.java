package com.quanlybanhang.dto.response;

import com.quanlybanhang.model.InvoiceAdjustmentType;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvoiceAdjustmentResponse {
    private Long id;
    private String adjustmentCode;
    private Long invoiceId;
    private String invoiceCode;
    private String invoiceNumber;
    private InvoiceAdjustmentType adjustmentType;
    private String adjustmentTypeLabel;
    private String adjustmentReason;
    private LocalDateTime adjustmentDate;
    private BigDecimal adjustmentAmount;
    private String newDealerName;
    private String newDealerTaxCode;
    private String newDealerAddress;
    private String adjustedBy;
    private LocalDateTime createdAt;
    private String note;
    private List<InvoiceAdjustmentItemResponse> items;
}

