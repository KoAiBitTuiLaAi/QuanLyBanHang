package com.quanlybanhang.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Yêu cầu lập bút toán điều chỉnh cho một phiếu thu đã ghi nhận (S7-02).
 * Đã ghi nhận thì không xoá, chỉ lập bút toán điều chỉnh.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DebtPaymentAdjustmentRequest {

    /** Lý do điều chỉnh - bắt buộc */
    @NotBlank(message = "Lý do điều chỉnh không được để trống")
    private String reason;

    /** Ngày lập bút toán điều chỉnh (mặc định ngày hiện tại nếu null) */
    private LocalDate adjustmentDate;

    /**
     * Loại điều chỉnh:
     * - REVERSAL: Huỷ bỏ toàn bộ khoản thu (lập bút toán điều chỉnh hoàn trả toàn bộ số tiền)
     * - PARTIAL_REVERSAL: Giảm bớt số tiền thu
     * - REALLOCATE: Điều chỉnh lại phân bổ sang các hoá đơn khác
     */
    @Builder.Default
    private String adjustmentType = "REVERSAL";

    /** Số tiền điều chỉnh giảm (nếu là PARTIAL_REVERSAL) */
    private BigDecimal adjustmentAmount;

    /** Danh sách phân bổ mới nếu là REALLOCATE hoặc điều chỉnh chỉ định */
    private List<PaymentAllocationItemRequest> newAllocations;

    /** Ghi chú bổ sung */
    private String note;

    /** Người lập bút toán điều chỉnh */
    private String recordedBy;
}
