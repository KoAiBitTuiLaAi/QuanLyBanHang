package com.quanlybanhang.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.HashSet;
import java.util.Set;

/** Dữ liệu quản trị viên dùng để tạo hoặc cập nhật tài khoản. */
@Data
public class AdminUserRequest {
    @NotBlank(message = "Tên đăng nhập không được để trống")
    @Size(min = 3, max = 50, message = "Tên đăng nhập phải từ 3-50 ký tự")
    private String username;

    @NotBlank(message = "Email không được để trống")
    @Email(message = "Email không hợp lệ")
    @Size(max = 100, message = "Email tối đa 100 ký tự")
    private String email;

    @NotBlank(message = "Họ tên không được để trống")
    @Size(max = 100, message = "Họ tên tối đa 100 ký tự")
    private String fullName;

    @Size(max = 15, message = "Số điện thoại tối đa 15 ký tự")
    private String phone;

    @NotEmpty(message = "Phải chọn ít nhất một vai trò")
    private Set<String> roles = new HashSet<>();

    private Set<Long> warehouseIds = new HashSet<>();
    private Set<Long> territoryIds = new HashSet<>();
    private Long dealerId;
}
