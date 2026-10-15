package com.quanlybanhang.controller;

import com.quanlybanhang.dto.response.ApiResponse;
import com.quanlybanhang.dto.response.OrderResponse;
import com.quanlybanhang.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;

/**
 * Controller Cổng đại lý (S5-01).
 * Cung cấp API theo dõi trạng thái đơn hàng của đại lý.
 * - Danh sách đơn kèm trạng thái, ngày giao dự kiến, tổng tiền.
 * - Xem chi tiết dòng hàng và số lượng thực giao nếu giao thiếu.
 * - Chỉ thấy đơn của chính mình.
 */
@RestController
@RequestMapping("/api/portal/orders")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('DEALER', 'ADMIN')")
public class DealerPortalController {

    private final OrderService orderService;

    /**
     * S5-01: Lấy danh sách đơn hàng của chính đại lý đăng nhập.
     * Trả về danh sách đơn kèm: trạng thái, ngày giao dự kiến, tổng tiền, cảnh báo giao thiếu (nếu có).
     */
    @GetMapping
    public ApiResponse getMyOrders(Principal principal) {
        if (principal == null) {
            throw new org.springframework.security.access.AccessDeniedException("Yêu cầu đăng nhập");
        }
        List<OrderResponse> orders = orderService.getOrdersForDealer(principal.getName());
        return ApiResponse.success("Danh sách đơn hàng của đại lý", orders);
    }

    /**
     * S5-01: Xem chi tiết đơn hàng của chính mình.
     * Trả về chi tiết các dòng hàng kèm số lượng đặt, đơn giá, thành tiền,
     * số lượng thực giao và số lượng thiếu nếu bị giao thiếu.
     */
    @GetMapping("/{id}")
    public ApiResponse getMyOrderDetail(@PathVariable Long id, Principal principal) {
        if (principal == null) {
            throw new org.springframework.security.access.AccessDeniedException("Yêu cầu đăng nhập");
        }
        OrderResponse order = orderService.getOrderByIdForDealer(id, principal.getName());
        return ApiResponse.success("Chi tiết đơn hàng", order);
    }
}

