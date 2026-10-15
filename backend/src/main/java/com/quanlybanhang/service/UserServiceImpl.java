package com.quanlybanhang.service;

import com.quanlybanhang.dto.request.UserProfileRequest;
import com.quanlybanhang.dto.response.ApiResponse;
import com.quanlybanhang.dto.response.NamedResourceResponse;
import com.quanlybanhang.dto.response.UserProfileResponse;
import com.quanlybanhang.model.User;
import com.quanlybanhang.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.stream.Collectors;

@Service
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;

    public UserServiceImpl(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserProfileResponse getUserProfile(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy người dùng"));
        
        return UserProfileResponse.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .phone(user.getPhone())
                .avatarUrl(AvatarService.avatarUrl(user, false))
                .avatarThumbnailUrl(AvatarService.avatarUrl(user, true))
                .roles(user.getRoles().stream()
                        .map(role -> role.getName().name())
                        .collect(Collectors.toList()))
                .warehouses(user.getWarehouses().stream()
                        .map(warehouse -> new NamedResourceResponse(
                                warehouse.getId(), warehouse.getCode(), warehouse.getName()))
                        .toList())
                .territories(user.getTerritories().stream()
                        .map(territory -> new NamedResourceResponse(
                                territory.getId(), territory.getCode(), territory.getName()))
                        .toList())
                .dealerId(user.getDealer() != null ? user.getDealer().getId() : null)
                .dealerCode(user.getDealer() != null ? user.getDealer().getCode() : null)
                .dealerName(user.getDealer() != null ? user.getDealer().getName() : null)
                .active(user.getActive())
                .build();
    }

    @Override
    @Transactional
    public ApiResponse updateUserProfile(String username, UserProfileRequest request) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new RuntimeException("Không tìm thấy người dùng"));
        
        if (request.getFullName() != null && !request.getFullName().isBlank()) {
            user.setFullName(request.getFullName().trim());
        }
        
        if (request.getPhone() != null && !request.getPhone().isBlank()) {
            user.setPhone(request.getPhone().trim());
        }
        
        userRepository.save(user);
        
        return ApiResponse.success("Cập nhật hồ sơ thành công");
    }
}
