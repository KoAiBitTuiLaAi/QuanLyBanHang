package com.quanlybanhang.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.util.List;

/**
 * Request gợi ý lô hàng loạt cho nhiều sản phẩm cần xuất kho (S6-04).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BatchLotSuggestionRequest {

    @NotNull(message = "Kho xuất không được để trống")
    private Long warehouseId;

    @NotEmpty(message = "Danh sách sản phẩm không được để trống")
    private List<@NotNull @Valid WarehouseVoucherItemRequest> items;
}

