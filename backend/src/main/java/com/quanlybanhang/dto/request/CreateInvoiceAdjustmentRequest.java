package com.quanlybanhang.dto.request;

import com.quanlybanhang.model.InvoiceAdjustmentType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * Yêu cầu lập chứng từ điều chỉnh cho hóa đơn đã phát hành (S7-01).
 * Hóa đơn đã phát hành không sửa và không xóa, chỉ lập chứng từ điều chỉnh.
 * Bắt buộc nhập lý do điều chỉnh.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateInvoiceAdjustmentRequest {

    @NotNull(message = "Loại điều chỉnh không được để trống")
    private InvoiceAdjustmentType adjustmentType;

    /**
     * Lý do điều chỉnh (bắt buộc).
     */
    @NotBlank(message = "Lý do điều chỉnh không được để trống")
    private String adjustmentReason;

    /**
     * Tổng số tiền điều chỉnh (+ hoặc -).
     * Mặc định là 0 nếu điều chỉnh thông tin hoặc hủy.
     */
    private BigDecimal adjustmentAmount;

    // Các thông tin điều chỉnh đại lý (dùng khi loại điều chỉnh là INFORMATION)
    private String newDealerName;
    private String newDealerTaxCode;
    private String newDealerAddress;

    private String note;

    /**
     * Danh sách dòng điều chỉnh sản phẩm (nếu điều chỉnh theo dòng hàng).
     */
    private List<InvoiceAdjustmentItemRequest> items;
}

