package com.quanlybanhang.controller;

import com.quanlybanhang.dto.response.ApiResponse;
import com.quanlybanhang.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/reports/dashboard")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN') or hasAuthority('DASHBOARD_VIEW')")
public class DashboardController {
    private final DashboardService dashboard;
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public org.springframework.http.ResponseEntity<ApiResponse> invalidParameter() {
        return org.springframework.http.ResponseEntity.badRequest().body(ApiResponse.error("Tham số phân trang phải là số nguyên hợp lệ"));
    }
    @GetMapping
    public ApiResponse overview(@RequestParam(defaultValue="20") int overdueLimit) {
        return ApiResponse.success("Dashboard điều hành bán hàng và kho",dashboard.overview(overdueLimit));
    }
    @GetMapping("/overdue-dealers")
    public ApiResponse overdue(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {
        return ApiResponse.success("Đại lý quá hạn theo số tiền giảm dần",dashboard.overdueDealers(page,size));
    }
}