package com.quanlybanhang.dto.request;

import jakarta.validation.constraints.*;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.math.BigDecimal;

/**
 * Dong hang trong phieu tra hang (S8-02).
 * invoiceItemId: ID dong hang tren hoa don goc de lay gia chinh xac.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReturnOrderItemRequest {

    @NotNull(message = "ID dong hang hoa don khong duoc de trong")
    @Positive
    private Long invoiceItemId;

    @NotNull(message = "So luong tra khong duoc de trong")
    @DecimalMin(value = "0.0001", message = "So luong tra phai lon hon 0")
    @Digits(integer = 11, fraction = 4)
    private BigDecimal returnQuantity;

    @Size(max = 255)
    private String note;
}