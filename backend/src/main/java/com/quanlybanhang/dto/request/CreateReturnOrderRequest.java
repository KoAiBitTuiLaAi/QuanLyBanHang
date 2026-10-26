package com.quanlybanhang.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Yeu cau tao phieu tra hang (S8-02).
 * Bat buoc lien ket toi hoa don goc va nhap ly do.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateReturnOrderRequest {

    @NotNull(message = "ID hoa don goc khong duoc de trong")
    @Positive
    private Long invoiceId;

    @NotBlank(message = "Ly do tra hang khong duoc de trong")
    @Size(max = 500)
    private String returnReason;

    @PastOrPresent
    private LocalDateTime returnDate;

    @Size(max = 500)
    private String note;

    @NotEmpty(message = "Danh sach dong hang tra khong duoc de trong")
    @Valid
    @Size(max = 500)
    private List<@NotNull ReturnOrderItemRequest> items;
}