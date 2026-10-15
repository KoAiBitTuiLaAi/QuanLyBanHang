package com.quanlybanhang.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * DTO trả về khi đăng nhập thành công - chứa JWT token và thông tin user.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JwtResponse {
    private String accessToken;
    private String refreshToken;

    @Builder.Default
    private String tokenType = "Bearer";

    private Long id;
    private String username;
    private String email;
    private String fullName;
    private String avatarUrl;
    private String avatarThumbnailUrl;
    private List<String> roles;
    private List<String> permissions;
    private List<NamedResourceResponse> warehouses;
    private List<NamedResourceResponse> territories;
    private Long dealerId;
    private String dealerCode;
    private String dealerName;
    private Boolean mustChangePassword;
}
