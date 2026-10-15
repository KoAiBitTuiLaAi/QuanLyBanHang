package com.quanlybanhang.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WarehouseTransferRequest {

    private String transferCode;

    @NotNull(message = "Kho xuất (kho đi) không được để trống")
    private Long fromWarehouseId;

    @NotNull(message = "Kho nhập (kho đến) không được để trống")
    private Long toWarehouseId;

    private String note;

    @NotEmpty(message = "Phiếu chuyển kho phải có ít nhất 1 sản phẩm")
    private List<@NotNull @Valid WarehouseTransferItemRequest> items;
}

