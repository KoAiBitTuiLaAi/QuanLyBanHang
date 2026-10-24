package com.quanlybanhang.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CancelReturnOrderRequest {

    @NotBlank(message = "Ly do huy khong duoc de trong")
    @jakarta.validation.constraints.Size(max = 500)
    private String cancelReason;
}