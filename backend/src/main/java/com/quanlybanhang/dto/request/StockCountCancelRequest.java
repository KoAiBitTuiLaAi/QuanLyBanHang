package com.quanlybanhang.dto.request;

import lombok.*;
import jakarta.validation.constraints.Size;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockCountCancelRequest {
    @Size(max = 500)
    private String reason;
}
