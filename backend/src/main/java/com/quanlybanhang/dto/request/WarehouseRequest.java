package com.quanlybanhang.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request tạo mới hoặc cập nhật thông tin kho hàng (S5-03).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WarehouseRequest {

    @NotBlank(message = "Mã kho không được để trống")
    @Size(max = 50, message = "Mã kho không được vượt quá 50 ký tự")
    private String code;

    @NotBlank(message = "Tên kho không được để trống")
    @Size(max = 150, message = "Tên kho không được vượt quá 150 ký tự")
    private String name;

    @Size(max = 255, message = "Địa chỉ không được vượt quá 255 ký tự")
    private String address;

    /** ID người dùng phụ trách kho (ROLE_WAREHOUSE_MANAGER / STAFF) */
    private Long managerUserId;

    /** Tên người phụ trách (nếu nhập trực tiếp hoặc bổ sung) */
    @Size(max = 100, message = "Tên người phụ trách không được vượt quá 100 ký tự")
    private String managerName;

    /** Trạng thái: true = Hoạt động, false = Ngừng hoạt động */
    private Boolean active;

    /** Đánh dấu kho hàng lỗi / hàng hỏng (S8-01) */
    private Boolean defective;
}
