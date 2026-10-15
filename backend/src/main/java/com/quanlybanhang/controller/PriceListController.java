package com.quanlybanhang.controller;

import com.quanlybanhang.dto.request.PriceListNewVersionRequest;
import com.quanlybanhang.dto.request.PriceListRequest;
import com.quanlybanhang.dto.response.ApiResponse;
import com.quanlybanhang.dto.response.CustomerGroupResponse;
import com.quanlybanhang.dto.response.PriceListResponse;
import com.quanlybanhang.model.CustomerGroup;
import com.quanlybanhang.service.PriceListService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.LocalDate;
import java.util.List;

/**
 * Controller Quản lý Bảng giá (S2-10).
 * Cho phép Quản lý kinh doanh khai báo bảng giá theo nhóm khách hàng và thời gian hiệu lực.
 */
@RestController
@RequestMapping("/api/price-lists")
@RequiredArgsConstructor
public class PriceListController {

    private final PriceListService priceListService;
    private final com.quanlybanhang.repository.UserRepository userRepository;

    @GetMapping
    @PreAuthorize("hasAnyRole('SALES_MANAGER', 'ADMIN', 'SALES', 'ACCOUNTANT', 'VIEWER')")
    public ApiResponse getAllPriceLists(
            @RequestParam(required = false) CustomerGroup customerGroup,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) String keyword) {
        List<PriceListResponse> list = priceListService.getAllPriceLists(customerGroup, active, keyword);
        return ApiResponse.success("Danh sách bảng giá", list);
    }

    @GetMapping("/customer-groups")
    @PreAuthorize("hasAnyRole('SALES_MANAGER', 'ADMIN', 'SALES', 'ACCOUNTANT', 'VIEWER')")
    public ApiResponse getCustomerGroups() {
        List<CustomerGroupResponse> groups = priceListService.getCustomerGroups();
        return ApiResponse.success("Danh sách nhóm khách hàng", groups);
    }

    @GetMapping("/effective")
    @PreAuthorize("hasAnyRole('SALES_MANAGER', 'ADMIN', 'SALES', 'ACCOUNTANT', 'VIEWER', 'DEALER')")
    public ApiResponse getEffectivePriceList(
            @RequestParam CustomerGroup customerGroup,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        org.springframework.security.core.Authentication auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getAuthorities().stream().anyMatch(a -> "ROLE_DEALER".equals(a.getAuthority()))) {
            com.quanlybanhang.model.User currentUser = userRepository.findByUsername(auth.getName()).orElse(null);
            if (currentUser != null && currentUser.getDealer() != null) {
                if (currentUser.getDealer().getCustomerGroup() != customerGroup) {
                    throw new org.springframework.security.access.AccessDeniedException("Đại lý chỉ được phép xem bảng giá thuộc nhóm khách hàng của mình");
                }
            }
        }

        PriceListResponse priceList = priceListService.getEffectivePriceList(customerGroup, date);
        if (priceList == null) {
            return ApiResponse.builder()
                    .success(false)
                    .message("Không tìm thấy bảng giá có hiệu lực cho nhóm khách hàng này")
                    .data(null)
                    .build();
        }
        return ApiResponse.success("Bảng giá hiệu lực", priceList);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('SALES_MANAGER', 'ADMIN', 'SALES', 'ACCOUNTANT', 'VIEWER')")
    public ApiResponse getPriceListById(@PathVariable Long id) {
        PriceListResponse priceList = priceListService.getPriceListById(id);
        return ApiResponse.success("Chi tiết bảng giá", priceList);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('SALES_MANAGER', 'ADMIN')")
    public ResponseEntity<ApiResponse> createPriceList(
            @Valid @RequestBody PriceListRequest request,
            Principal principal) {
        String username = principal != null ? principal.getName() : "system";
        PriceListResponse response = priceListService.createPriceList(request, username);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Tạo bảng giá thành công", response));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SALES_MANAGER', 'ADMIN')")
    public ApiResponse updatePriceList(
            @PathVariable Long id,
            @Valid @RequestBody PriceListRequest request,
            Principal principal) {
        String username = principal != null ? principal.getName() : "system";
        PriceListResponse response = priceListService.updatePriceList(id, request, username);
        return ApiResponse.success("Cập nhật bảng giá thành công", response);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SALES_MANAGER', 'ADMIN')")
    public ApiResponse deletePriceList(@PathVariable Long id, Principal principal) {
        String username = principal != null ? principal.getName() : "system";
        priceListService.deletePriceList(id, username);
        return ApiResponse.success("Xóa bảng giá thành công", null);
    }

    @PostMapping("/{id}/new-version")
    @PreAuthorize("hasAnyRole('SALES_MANAGER', 'ADMIN')")
    public ResponseEntity<ApiResponse> createNewVersion(
            @PathVariable Long id,
            @RequestBody(required = false) PriceListNewVersionRequest request,
            Principal principal) {
        String username = principal != null ? principal.getName() : "system";
        PriceListResponse response = priceListService.createNewVersion(id, request, username);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Tạo phiên bản mới của bảng giá thành công", response));
    }
}
