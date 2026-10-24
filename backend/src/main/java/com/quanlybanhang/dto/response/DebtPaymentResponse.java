package com.quanlybanhang.dto.response;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Response kết quả ghi nhận thu tiền (S7-04).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DebtPaymentResponse {
    private Long id;
    private String paymentCode;
    private Long dealerId;
    private String dealerCode;
    private String dealerName;
    private BigDecimal amount;
    private LocalDate paymentDate;
    private String paymentMethod;
    private String referenceCode;
    private String note;
    private String recordedBy;
    private LocalDateTime createdAt;

    /** Công nợ còn lại của đại lý sau khi trừ khoản thu */
    private BigDecimal remainingDebt;

    /** Trạng thái cờ rủi ro của đại lý sau khi đánh giá lại */
    private Boolean dealerStillFlagged;

    /** Lý do cờ rủi ro sau khi đánh giá lại (nếu còn) */
    private String debtFlagReason;

    /** Loại phiếu: PAYMENT hoặc ADJUSTMENT (S7-02) */
    private String paymentType;

    /** ID phiếu thu gốc nếu là bút toán điều chỉnh (S7-02) */
    private Long originalPaymentId;

    /** Mã phiếu thu gốc nếu là bút toán điều chỉnh (S7-02) */
    private String originalPaymentCode;

    /** Lý do lập bút toán điều chỉnh (S7-02) */
    private String adjustmentReason;

    /** Danh sách chi tiết đối trừ vào từng hoá đơn (S7-02) */
    private java.util.List<DebtPaymentAllocationResponse> allocations;
}

