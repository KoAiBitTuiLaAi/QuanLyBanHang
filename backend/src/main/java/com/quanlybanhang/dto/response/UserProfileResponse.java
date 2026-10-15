package com.quanlybanhang.dto.response;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class UserProfileResponse {
    private Long id;
    private String username;
    private String email;
    private String fullName;
    private String phone;
    private String avatarUrl;
    private String avatarThumbnailUrl;
    private List<String> roles;
    private List<NamedResourceResponse> warehouses;
    private List<NamedResourceResponse> territories;
    private Long dealerId;
    private String dealerCode;
    private String dealerName;
    private Boolean active;
}
