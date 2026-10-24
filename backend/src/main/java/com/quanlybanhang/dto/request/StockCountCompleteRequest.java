package com.quanlybanhang.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockCountCompleteRequest {

    @NotBlank(message = "Bắt buộc nhập lý do khi chốt kiểm kê")
    @Size(max = 500)
    private String reason;

    /**
     * Tùy chọn: Gửi kèm số đếm thực tế để cập nhật và chốt trong 1 lần gọi.
     */
    @Valid
    private List<StockCountItemUpdateRequest> items;
}
