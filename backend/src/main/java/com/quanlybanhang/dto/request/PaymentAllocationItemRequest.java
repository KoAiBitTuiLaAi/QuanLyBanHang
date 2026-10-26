package com.quanlybanhang.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.math.BigDecimal;

/**
 * Chỉ định đối trừ vào một hoá đơn / đơn hàng cụ thể (S7-02).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentAllocationItemRequest {

    @NotNull(message = "ID hoá đơn / đơn hàng không được để trống")
    private Long orderId;

    @NotNull(message = "Số tiền đối trừ không được để trống")
    @DecimalMin(value = "0.01", message = "Số tiền đối trừ phải lớn hơn 0")
    private BigDecimal amount;

    private String note;
}
