package com.quanlybanhang.dto.request;

import com.quanlybanhang.model.LocationType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request tạo mới hoặc cập nhật vị trí lưu trong kho (S5-03).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WarehouseLocationRequest {

    /** ID kho chứa vị trí này (bắt buộc nếu không nằm trên URL) */
    private Long warehouseId;

    /** Optional on create: server generates a code. Blank on update preserves the existing code. */
    @Size(max = 50, message = "Mã vị trí không được vượt quá 50 ký tự")
    private String code;

    @NotBlank(message = "Tên vị trí không được để trống")
    @Size(max = 120, message = "Tên vị trí không được vượt quá 120 ký tự")
    private String name;

    /** Loại vị trí: ZONE (Khu) hoặc SHELF (Kệ) */
    @NotNull(message = "Loại vị trí không được để trống")
    private LocationType locationType;

    /** ID vị trí cha (vd: Kệ nằm trong Khu vực cha) */
    private Long parentLocationId;

    @Size(max = 500, message = "Mô tả không được vượt quá 500 ký tự")
    private String description;

    @jakarta.validation.constraints.Min(0)
    @jakarta.validation.constraints.Max(1000000)
    private Integer pickingSequence;

    /** Trạng thái vị trí: true = Đang dùng, false = Tạm ngừng */
    private Boolean active;
}
