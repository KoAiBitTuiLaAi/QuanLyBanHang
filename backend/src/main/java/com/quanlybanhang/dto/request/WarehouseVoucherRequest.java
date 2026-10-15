package com.quanlybanhang.dto.request;

import com.quanlybanhang.model.WarehouseVoucherType;
import lombok.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WarehouseVoucherRequest {

    private String voucherCode;

    private Long warehouseId;

    private WarehouseVoucherType type;

    /**
     * Nhà cung cấp (S5-04).
     */
    private Long supplierId;
    private String supplierCode;

    /**
     * Số chứng từ đi kèm (hóa đơn, phiếu giao hàng NCC - S5-04).
     */
    private String documentNumber;

    /**
     * Ngày nhập kho / ngày chứng từ (S5-04).
     */
    private LocalDateTime voucherDate;

    /**
     * Trạng thái phiếu: "DRAFT" (nháp), "CONFIRMED" (đã xác nhận) (S5-04).
     */
    private String status;

    /**
     * Cờ lưu nháp nhanh: nếu true tương đương status = "DRAFT".
     */
    private Boolean draft;

    /**
     * Tham chiếu đến phiếu gốc khi lập phiếu điều chỉnh (S5-04).
     */
    private Long referenceVoucherId;
    private String referenceVoucherCode;

    /**
     * Lý do điều chỉnh (S5-04).
     */
    private String adjustmentReason;

    private String note;

    private List<@NotNull @Valid WarehouseVoucherItemRequest> items;

    // Các trường tương thích ngược với payload đơn giản: {"productId": 1, "quantity": 50, "unit": "thùng"} hoặc {"delta": 10}
    private Long productId;
    private BigDecimal quantity;
    private String unit;
    private BigDecimal delta;

    @JsonIgnore
    @AssertTrue(message = "Phiếu kho phải có ít nhất 1 sản phẩm")
    public boolean isProductSelectionValid() {
        return (items != null && !items.isEmpty()) || productId != null;
    }
}
