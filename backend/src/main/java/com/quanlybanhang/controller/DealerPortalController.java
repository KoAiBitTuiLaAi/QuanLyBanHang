package com.quanlybanhang.controller;

import com.quanlybanhang.dto.request.DealerPortalOrderRequest;
import com.quanlybanhang.dto.request.OrderRequest;
import com.quanlybanhang.dto.response.*;
import com.quanlybanhang.model.*;
import com.quanlybanhang.repository.PriceListRepository;
import com.quanlybanhang.repository.ProductRepository;
import com.quanlybanhang.repository.UnitConversionRepository;
import com.quanlybanhang.repository.UserRepository;
import com.quanlybanhang.service.DealerDebtService;
import com.quanlybanhang.service.DealerDeliveryPointService;
import com.quanlybanhang.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.security.Principal;
import java.time.LocalDate;
import java.util.*;

/**
 * Controller Cổng dành cho Đại lý (S4-10, S5-01).
 *
 * Tính năng chính:
 * - Đăng nhập bằng tài khoản đại lý:
 *   + Xem thông tin hồ sơ đại lý, người phụ trách, điểm giao hàng (GET /api/portal/profile)
 *   + Chỉ thấy sản phẩm và giá thuộc nhóm khách hàng của mình (GET /api/portal/catalog hoặc /products)
 *   + Xem hạn mức, công nợ hiện tại và công nợ còn lại thời gian thực (GET /api/portal/debt)
 *   + Tự đặt hàng bất kỳ lúc nào, đơn gửi lên luôn ở trạng thái Chờ duyệt (POST /api/portal/orders)
 *   + Theo dõi lịch sử và trạng thái đơn hàng của chính mình (GET /api/portal/orders, GET /api/portal/orders/{id})
 */
@RestController
@RequestMapping("/api/portal")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('DEALER', 'ADMIN')")
public class DealerPortalController {

    private final OrderService orderService;
    private final DealerDebtService dealerDebtService;
    private final DealerDeliveryPointService deliveryPointService;
    private final UserRepository userRepository;
    private final PriceListRepository priceListRepository;
    private final ProductRepository productRepository;
    private final UnitConversionRepository unitConversionRepository;

    /**
     * Lấy thực thể Dealer liên kết với người dùng đang đăng nhập.
     * Admin có thể truyền tham số dealerId tùy chọn để kiểm tra/hỗ trợ.
     */
    private Dealer resolveAuthenticatedDealer(Principal principal, Long requestedDealerId) {
        if (principal == null) {
            throw new AccessDeniedException("Yêu cầu đăng nhập");
        }
        User currentUser = userRepository.findByUsername(principal.getName())
                .orElseThrow(() -> new AccessDeniedException("Không tìm thấy thông tin tài khoản: " + principal.getName()));

        boolean isAdmin = currentUser.getRoles().stream()
                .anyMatch(r -> r.getName() == ERole.ROLE_ADMIN);

        if (isAdmin && requestedDealerId != null) {
            // Admin được chỉ định dealerId để mô phỏng
            return currentUser.getDealer() != null && currentUser.getDealer().getId().equals(requestedDealerId)
                    ? currentUser.getDealer()
                    : null;
        }

        if (currentUser.getDealer() == null) {
            throw new AccessDeniedException("Tài khoản chưa được liên kết với hồ sơ đại lý nào");
        }
        return currentUser.getDealer();
    }

    /**
     * S4-10: Lấy thông tin hồ sơ đại lý đang đăng nhập kèm nhân viên phụ trách, công nợ và điểm giao hàng.
     */
    @GetMapping("/profile")
    public ApiResponse getProfile(Principal principal) {
        Dealer dealer = resolveAuthenticatedDealer(principal, null);
        DealerDebtResponse debt = dealerDebtService.getDealerDebtInfo(dealer);
        List<DealerDeliveryPointResponse> points = deliveryPointService.getDeliveryPointsByDealer(dealer.getId());

        User sales = dealer.getAssignedUser();

        DealerPortalProfileResponse response = DealerPortalProfileResponse.builder()
                .id(dealer.getId())
                .code(dealer.getCode())
                .name(dealer.getName())
                .customerGroup(dealer.getCustomerGroup())
                .customerGroupName(dealer.getCustomerGroup() != null ? dealer.getCustomerGroup().getDisplayName() : null)
                .phone(dealer.getPhone())
                .email(dealer.getEmail())
                .address(dealer.getAddress())
                .taxCode(dealer.getTaxCode())
                .active(dealer.getActive())
                .locked(dealer.getLocked())
                .lockReason(dealer.getLockReason())
                .salesUserId(sales != null ? sales.getId() : null)
                .salesUserName(sales != null ? sales.getUsername() : null)
                .salesUserFullName(sales != null ? sales.getFullName() : null)
                .salesUserPhone(sales != null ? sales.getPhone() : null)
                .salesUserEmail(sales != null ? sales.getEmail() : null)
                .debtLimit(debt.getDebtLimit())
                .currentDebt(debt.getCurrentDebt())
                .remainingLimit(debt.getRemainingLimit())
                .hasOverdueDebt(debt.getHasOverdueDebt())
                .isBlocked(debt.getIsBlocked())
                .blockReason(debt.getBlockReason())
                .deliveryPoints(points)
                .build();

        return ApiResponse.success("Thông tin đại lý", response);
    }

    /**
     * S4-10: Lấy danh mục sản phẩm và giá chỉ thuộc nhóm khách hàng của đại lý đang đăng nhập.
     * Quy tắc bảo mật: Không hiển thị sản phẩm hoặc bảng giá của các nhóm khách hàng khác.
     */
    @GetMapping({"/catalog", "/products"})
    public ApiResponse getCatalog(Principal principal) {
        Dealer dealer = resolveAuthenticatedDealer(principal, null);
        CustomerGroup group = dealer.getCustomerGroup();
        if (group == null) {
            group = CustomerGroup.DAI_LY_CAP_1;
        }

        // 1. Tìm bảng giá có hiệu lực của nhóm khách hàng đại lý
        List<PriceList> effectiveLists = priceListRepository.findEffectivePriceLists(group, LocalDate.now());
        if (effectiveLists.isEmpty()) {
            return ApiResponse.success("Chưa có bảng giá hiệu lực cho nhóm khách hàng của đại lý", Collections.emptyList());
        }

        PriceList priceList = effectiveLists.get(0);
        Map<Long, PriceListItem> priceMap = new HashMap<>();
        if (priceList.getItems() != null) {
            for (PriceListItem pli : priceList.getItems()) {
                if (pli.getProduct() != null) {
                    priceMap.put(pli.getProduct().getId(), pli);
                }
            }
        }

        // 2. Lấy tất cả sản phẩm đang hoạt động có trong bảng giá
        List<Product> activeProducts = productRepository.findByActiveTrueOrderByNameAsc();
        List<DealerPortalCatalogItemResponse> catalog = new ArrayList<>();

        for (Product product : activeProducts) {
            PriceListItem pli = priceMap.get(product.getId());
            if (pli == null) {
                // Chỉ hiển thị sản phẩm có trong bảng giá hiệu lực của nhóm đại lý!
                continue;
            }

            BigDecimal baseSellingPrice = pli.getSellingPrice();
            BigDecimal baseFloorPrice = pli.getFloorPrice();

            // Lấy danh sách quy cách đóng gói (thùng, lốc...)
            List<DealerPortalCatalogItemResponse.PackagingUnitInfo> units = new ArrayList<>();
            // Đơn vị cơ sở
            units.add(DealerPortalCatalogItemResponse.PackagingUnitInfo.builder()
                    .unitName(product.getUnit())
                    .factor(BigDecimal.ONE)
                    .price(baseSellingPrice)
                    .build());

            // Các đơn vị quy đổi
            List<UnitConversion> conversions = unitConversionRepository.findByProductIdOrderByConversionFactorAsc(product.getId());
            for (UnitConversion uc : conversions) {
                if (Boolean.TRUE.equals(uc.getIsBaseUnit())) {
                    continue; // Đã thêm đơn vị cơ sở ở trên
                }
                BigDecimal factor = uc.getConversionFactor() != null ? uc.getConversionFactor() : BigDecimal.ONE;
                BigDecimal unitPrice = uc.getPrice() != null ? uc.getPrice() : baseSellingPrice.multiply(factor);
                units.add(DealerPortalCatalogItemResponse.PackagingUnitInfo.builder()
                        .unitName(uc.getUnitName())
                        .factor(factor)
                        .price(unitPrice)
                        .build());
            }

            catalog.add(DealerPortalCatalogItemResponse.builder()
                    .id(product.getId())
                    .sku(product.getCode())
                    .name(product.getName())
                    .categoryId(product.getCategory() != null ? product.getCategory().getId() : null)
                    .categoryName(product.getCategory() != null ? product.getCategory().getName() : null)
                    .baseUnit(product.getUnit())
                    .basePrice(baseSellingPrice)
                    .floorPrice(baseFloorPrice)
                    .imageUrl(product.getImageUrl())
                    .units(units)
                    .build());
        }

        return ApiResponse.success("Danh mục sản phẩm của đại lý", catalog);
    }

    /**
     * S4-10: Tra cứu thông tin công nợ, hạn mức và công nợ còn lại của đại lý trước khi gửi đơn.
     */
    @GetMapping("/debt")
    public ApiResponse getDebt(Principal principal) {
        Dealer dealer = resolveAuthenticatedDealer(principal, null);
        DealerDebtResponse debt = dealerDebtService.getDealerDebtInfo(dealer);
        return ApiResponse.success("Thông tin công nợ đại lý", debt);
    }

    /**
     * S4-10: Đại lý tự tạo đơn hàng qua cổng.
     * Quy tắc nghiệp vụ bắt buộc:
     * - Tự động gắn đơn vào đúng đại lý đăng nhập (không thể chọn sai hay đổi sang đại lý khác).
     * - Áp dụng bảng giá hiệu lực của nhóm khách hàng đại lý.
     * - Đơn gửi lên LUÔN ở trạng thái Chờ duyệt (PENDING_APPROVAL), do nhân viên phụ trách xác nhận.
     */
    @PostMapping("/orders")
    public ResponseEntity<ApiResponse> createOrder(
            @Valid @RequestBody DealerPortalOrderRequest request,
            Principal principal) {
        Dealer dealer = resolveAuthenticatedDealer(principal, null);

        OrderRequest orderRequest = OrderRequest.builder()
                .dealerId(dealer.getId())
                .deliveryPointId(request.getDeliveryPointId())
                .expectedDeliveryDate(request.getExpectedDeliveryDate())
                .note(request.getNote())
                .items(request.getItems())
                .build();

        OrderResponse response = orderService.createOrder(orderRequest, principal.getName());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Đặt hàng thành công. Đơn hàng đang ở trạng thái Chờ duyệt.", response));
    }

    /**
     * S4-10, S5-01: Lấy danh sách đơn hàng của riêng đại lý đăng nhập.
     */
    @GetMapping("/orders")
    public ApiResponse getMyOrders(Principal principal) {
        if (principal == null) {
            throw new AccessDeniedException("Yêu cầu đăng nhập");
        }
        List<OrderResponse> orders = orderService.getOrdersForDealer(principal.getName());
        return ApiResponse.success("Danh sách đơn hàng của đại lý", orders);
    }

    /**
     * S4-10, S5-01: Xem chi tiết một đơn hàng của riêng đại lý đăng nhập.
     */
    @GetMapping("/orders/{id}")
    public ApiResponse getMyOrderDetail(@PathVariable Long id, Principal principal) {
        if (principal == null) {
            throw new AccessDeniedException("Yêu cầu đăng nhập");
        }
        OrderResponse order = orderService.getOrderByIdForDealer(id, principal.getName());
        return ApiResponse.success("Chi tiết đơn hàng", order);
    }

    /**
     * S4-10: Lấy danh sách điểm giao hàng của đại lý đăng nhập.
     */
    @GetMapping("/delivery-points")
    public ApiResponse getDeliveryPoints(Principal principal) {
        Dealer dealer = resolveAuthenticatedDealer(principal, null);
        List<DealerDeliveryPointResponse> points = deliveryPointService.getDeliveryPointsByDealer(dealer.getId());
        return ApiResponse.success("Danh sách điểm giao hàng", points);
    }
}
