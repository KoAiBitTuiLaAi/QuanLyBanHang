package com.quanlybanhang.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WarehouseVoucherItemRequest {

    private Long productId;

    private String productCode;

    /**
     * Đơn vị tính khi nhập/xuất (vd: "thùng", "lốc", "lon").
     * Nếu bỏ trống, hệ thống sẽ mặc định dùng đơn vị cơ sở của sản phẩm.
     */
    private String unit;

    @NotNull(message = "Số lượng không được để trống")
    @DecimalMin(value = "0.0001", message = "Số lượng phải lớn hơn 0")
    @jakarta.validation.constraints.Digits(integer = 11, fraction = 4)
    private BigDecimal quantity;

    private BigDecimal price;

    /**
     * Số lô hàng (S5-04). Bắt buộc nếu sản phẩm có manageLot = true khi nhập kho.
     * Khi xuất kho (S6-04): nếu bỏ trống hệ thống tự áp dụng lô FEFO gợi ý;
     * nếu chọn lô khác lô gợi ý thì bắt buộc nhập overrideReason.
     */
    @jakarta.validation.constraints.Size(max = 50)
    private String lotNumber;

    /**
     * Hạn sử dụng (S5-04).
     */
    private java.time.LocalDate expiryDate;

    /**
     * Ngày sản xuất (S5-04). Tùy chọn.
     */
    private java.time.LocalDate manufacturingDate;

    /**
     * Số lô hệ thống gợi ý theo FEFO (S6-04).
     */
    @jakarta.validation.constraints.Size(max = 50)
    private String suggestedLotNumber;

    /**
     * Lý do chọn lô khác lô gợi ý FEFO (S6-04). Bắt buộc khi thủ kho chọn lô khác lô gợi ý.
     */
    @jakarta.validation.constraints.Size(max = 255)
    private String overrideReason;

    /**
     * Bí danh của overrideReason để hỗ trợ linh hoạt tham số request (S6-04).
     */
    @jakarta.validation.constraints.Size(max = 255)
    private String lotReason;

    @jakarta.validation.constraints.Size(max = 255)
    private String note;

    /**
     * Xác nhận ghi đè xuất lô đã quá hạn ở cấp độ dòng sản phẩm (S6-01).
     */
    private Boolean overrideExpiredLot;

    /**
     * Lý do ghi đè xuất lô đã quá hạn (S6-01).
     */
    @jakarta.validation.constraints.Size(max = 255)
    private String expiredLotOverrideReason;

    public String getEffectiveOverrideReason() {
        if (overrideReason != null && !overrideReason.isBlank()) {
            return overrideReason.trim();
        }
        if (lotReason != null && !lotReason.isBlank()) {
            return lotReason.trim();
        }
        return null;
    }
}
