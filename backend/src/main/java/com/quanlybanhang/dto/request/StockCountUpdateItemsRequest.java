package com.quanlybanhang.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockCountUpdateItemsRequest {

    @NotEmpty(message = "Danh sách sản phẩm kiểm đếm không được để trống")
    @Valid
    private List<StockCountItemUpdateRequest> items;

    @Size(max = 500)
    private String note;
}
