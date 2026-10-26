package com.quanlybanhang.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Request ghi nhận thu tiền / thanh toán công nợ của đại lý (S7-04).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DebtPaymentRequest {

    @NotNull(message = "ID đại lý không được để trống")
    private Long dealerId;

    @NotNull(message = "Số tiền thu không được để trống")
    @DecimalMin(value = "0.01", message = "Số tiền thu phải lớn hơn 0")
    private BigDecimal amount;

    private LocalDate paymentDate;

    private String paymentMethod;

    private String referenceCode;

    private String note;

    /** Người thu tiền (S7-02) */
    private String recordedBy;

    /** Tự động đối trừ theo nguyên tắc hoá đơn cũ nhất trước (FIFO) (S7-02) */
    @Builder.Default
    private Boolean autoAllocate = true;

    /** Danh sách hoá đơn chỉ định đối trừ cụ thể (S7-02) */
    private java.util.List<PaymentAllocationItemRequest> allocations;
}

