package com.quanlybanhang.controller;

import com.quanlybanhang.dto.request.DealerPortalOrderRequest;
import com.quanlybanhang.dto.request.DealerPortalReorderRequest;
import com.quanlybanhang.dto.request.OrderRequest;
import com.quanlybanhang.dto.request.OrderItemRequest;
import com.quanlybanhang.dto.request.OrderFilterRequest;
import com.quanlybanhang.dto.request.OrderCalculateRequest;
import com.quanlybanhang.dto.response.*;
import com.quanlybanhang.model.*;
import com.quanlybanhang.repository.PriceListRepository;
import com.quanlybanhang.repository.ProductRepository;
import com.quanlybanhang.repository.UnitConversionRepository;
import com.quanlybanhang.repository.UserRepository;
import com.quanlybanhang.repository.DealerRepository;
import com.quanlybanhang.service.DealerDebtService;
import com.quanlybanhang.service.DealerDeliveryPointService;
import com.quanlybanhang.service.InvoicePdfService;
import com.quanlybanhang.service.InvoiceService;
import com.quanlybanhang.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.security.Principal;
import java.time.LocalDate;
import java.util.*;

/**
 * Controller Cổng dành cho Đại lý (S4-10, S5-01, S5-02, S7-07).
 *
 * Tính năng chính:
 * - Đăng nhập bằng tài khoản đại lý:
 *   + Xem thông tin hồ sơ đại lý, người phụ trách, điểm giao hàng (GET /api/portal/profile)
 *   + Chỉ thấy sản phẩm và giá thuộc nhóm khách hàng của mình (GET /api/portal/catalog hoặc /products)
 *   + Xem hạn mức, tổng còn nợ hiện tại và hạn mức còn lại thời gian thực (GET /api/portal/debt, GET /api/portal/debt/summary)
 *   + Xem lịch sử thanh toán công nợ của chính mình (GET /api/portal/debt/payments)
 *   + Xem danh sách hóa đơn kèm số tiền, hạn thanh toán, trạng thái (GET /api/portal/invoices, GET /api/portal/debt/invoices)
 *   + Xem chi tiết, bản in HTML và tải lại PDF hóa đơn đã phát hành của chính mình (GET /api/portal/invoices/{id}, /pdf, /print-preview)
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
    private final DealerRepository dealerRepository;

    @Autowired(required = false)
    private InvoiceService invoiceService;

    @Autowired(required = false)
    private InvoicePdfService invoicePdfService;

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
        if (!isAdmin && currentUser.getRoles().stream().noneMatch(r -> r.getName() == ERole.ROLE_DEALER))
            throw new AccessDeniedException("Cổng này chỉ dành cho tài khoản đại lý hoặc quản trị viên");

        if (isAdmin && requestedDealerId != null) {
            return dealerRepository.findById(requestedDealerId)
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đại lý được chọn"));
        }

        if (currentUser.getDealer() == null) {
            if (isAdmin) {
                throw new IllegalArgumentException("Vui lòng chọn đại lý để sử dụng cổng đại lý");
            }
            throw new AccessDeniedException("Tài khoản chưa được liên kết với hồ sơ đại lý nào");
        }
        if (requestedDealerId != null && !requestedDealerId.equals(currentUser.getDealer().getId())) {
            throw new AccessDeniedException("Bạn chỉ có quyền truy cập dữ liệu của chính đại lý mình");
        }
        return currentUser.getDealer();
    }

    /** Danh sách lựa chọn cho quản trị viên hỗ trợ đại lý, không yêu cầu liên kết tài khoản. */
    @GetMapping("/dealers")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse getDealerOptions() {
        return ApiResponse.success("Danh sách đại lý", dealerRepository.findAll(Sort.by("name", "id"))
                .stream().map(dealer -> new NamedResourceResponse(dealer.getId(), dealer.getCode(), dealer.getName()))
                .toList());
    }

    /**
     * S4-10: Lấy thông tin hồ sơ đại lý đang đăng nhập kèm nhân viên phụ trách, công nợ và điểm giao hàng.
     */
    @GetMapping("/profile")
    @PreAuthorize("hasAuthority('ORDER_VIEW')")
    public ApiResponse getProfile(Principal principal, @RequestParam(required = false) Long dealerId) {
        Dealer dealer = resolveAuthenticatedDealer(principal, dealerId);
        DealerDebtResponse debt = portalHas("FINANCE_VIEW_DEBT") ? dealerDebtService.getDealerDebtInfo(dealer) : new DealerDebtResponse();
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
    @PreAuthorize("hasAuthority('PRODUCT_VIEW')")
    public ApiResponse getCatalog(Principal principal, @RequestParam(required = false) Long dealerId) {
        Dealer dealer = resolveAuthenticatedDealer(principal, dealerId);
        CustomerGroup group = dealer.getCustomerGroup();
        if (group == null) {
            throw new IllegalArgumentException("Đại lý chưa được gán nhóm khách hàng");
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
                BigDecimal unitPrice = baseSellingPrice.multiply(factor);
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
     * S4-10, S7-07: Tra cứu thông tin công nợ, tổng còn nợ, hạn mức và hạn mức còn lại của đại lý.
     */
    @GetMapping("/debt")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('FINANCE_VIEW_DEBT')")
    public ApiResponse getDebt(Principal principal, @RequestParam(required = false) Long dealerId) {
        Dealer dealer = resolveAuthenticatedDealer(principal, dealerId);
        return ApiResponse.success("Thông tin công nợ đại lý", portalDebt(dealer));
    }

    /**
     * S7-07: Xem tổng quan công nợ của chính mình (tổng còn nợ, hạn mức được cấp, hạn mức còn lại,
     * hạn mức còn được lấy hàng, danh sách hóa đơn kèm hạn thanh toán và lịch sử thanh toán).
     */
    @GetMapping("/debt/summary")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('FINANCE_VIEW_DEBT')")
    public ApiResponse getDebtSummary(Principal principal, @RequestParam(required = false) Long dealerId) {
        Dealer dealer = resolveAuthenticatedDealer(principal, dealerId);
        return ApiResponse.success("Tổng quan công nợ và hạn mức của đại lý", portalDebt(dealer));
    }

    private DealerDebtResponse portalDebt(Dealer dealer) {
        var debt = dealerDebtService.getDealerDebtInfo(dealer);
        if (!portalHas("INVOICE_VIEW")) debt.setInvoices(Collections.emptyList());
        return debt;
    }

    private boolean portalHas(String permission) {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream().anyMatch(g ->
                "ROLE_ADMIN".equals(g.getAuthority()) || permission.equals(g.getAuthority()));
    }

    /**
     * S7-07: Đại lý xem lịch sử các đợt thanh toán công nợ của chính mình.
     */
    @GetMapping("/debt/payments")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('FINANCE_VIEW_DEBT')")
    public ApiResponse getMyDebtPayments(Principal principal, @RequestParam(required = false) Long dealerId) {
        Dealer dealer = resolveAuthenticatedDealer(principal, dealerId);
        List<DebtPaymentResponse> payments = dealerDebtService.getPaymentHistory(dealer.getId());
        return ApiResponse.success("Lịch sử thanh toán công nợ của đại lý", payments);
    }

    /**
     * S7-07 (Tiêu chí 1): Danh sách hóa đơn của chính đại lý kèm số tiền, hạn thanh toán, trạng thái.
     * Hỗ trợ lọc theo trạng thái phát hành (status), trạng thái thanh toán (paymentStatus), khoảng ngày và từ khóa.
     */
    @GetMapping({"/invoices", "/debt/invoices"})
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('INVOICE_VIEW')")
    public ApiResponse getMyInvoices(
            Principal principal,
            @RequestParam(required = false) Long dealerId,
            @RequestParam(required = false) InvoiceStatus status,
            @RequestParam(required = false) String paymentStatus,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Dealer dealer = resolveAuthenticatedDealer(principal, dealerId);
        Page<InvoiceResponse> result = invoiceService.getDealerInvoices(
                dealer.getId(), status, paymentStatus, startDate, endDate, keyword, page, size);
        return ApiResponse.success("Danh sách hóa đơn của đại lý", result);
    }

    /**
     * S7-07 (Tiêu chí 2): Xem chi tiết một hóa đơn đã phát hành của chính đại lý đăng nhập.
     * Chặn truy cập chéo hóa đơn của đại lý khác (403 Forbidden).
     */
    @GetMapping({"/invoices/{id}", "/debt/invoices/{id}"})
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('INVOICE_VIEW')")
    public ApiResponse getMyInvoiceDetail(
            @PathVariable Long id,
            Principal principal,
            @RequestParam(required = false) Long dealerId) {
        Dealer dealer = resolveAuthenticatedDealer(principal, dealerId);
        InvoiceResponse invoice = invoiceService.getDealerInvoiceById(dealer.getId(), id);
        return ApiResponse.success("Chi tiết hóa đơn của đại lý", invoice);
    }

    /**
     * S7-07 (Tiêu chí 2): Xem và tải lại file PDF hóa đơn đã phát hành của chính đại lý đăng nhập.
     * Chặn truy cập chéo hóa đơn của đại lý khác (403 Forbidden).
     */
    @GetMapping(value = {"/invoices/{id}/pdf", "/debt/invoices/{id}/pdf"}, produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('INVOICE_VIEW')")
    public ResponseEntity<byte[]> downloadMyInvoicePdf(
            @PathVariable Long id,
            @RequestParam(defaultValue = "true") boolean download,
            Principal principal,
            @RequestParam(required = false) Long dealerId) {
        Dealer dealer = resolveAuthenticatedDealer(principal, dealerId);
        // Kiểm tra quyền sở hữu hóa đơn cấp dòng dữ liệu trước khi xuất PDF
        InvoiceResponse inv = invoiceService.getDealerInvoiceById(dealer.getId(), id);

        String username = principal != null ? principal.getName() : "system";
        byte[] pdfBytes = invoicePdfService.generateInvoicePdf(id, username);

        String filename = "hoa-don-" + String.valueOf(inv.getInvoiceNumber()).replaceAll("[\\r\\n\\\\/]", "_") + ".pdf";
        String disposition = (download ? org.springframework.http.ContentDisposition.attachment()
                : org.springframework.http.ContentDisposition.inline())
                .filename(filename, java.nio.charset.StandardCharsets.UTF_8).build().toString();

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .cacheControl(org.springframework.http.CacheControl.noStore())
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdfBytes);
    }

    /**
     * S7-07 (Tiêu chí 2): Xem bản in HTML hóa đơn đã phát hành của chính đại lý đăng nhập.
     */
    @GetMapping(value = {"/invoices/{id}/print-preview", "/debt/invoices/{id}/print-preview"}, produces = MediaType.TEXT_HTML_VALUE)
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('INVOICE_VIEW')")
    public ResponseEntity<String> getMyInvoicePrintHtml(
            @PathVariable Long id,
            Principal principal,
            @RequestParam(required = false) Long dealerId) {
        Dealer dealer = resolveAuthenticatedDealer(principal, dealerId);
        // Kiểm tra quyền sở hữu hóa đơn cấp dòng dữ liệu trước khi tạo HTML in
        invoiceService.getDealerInvoiceById(dealer.getId(), id);

        String username = principal != null ? principal.getName() : "system";
        String html = invoicePdfService.generateInvoicePrintHtml(id, username);
        return ResponseEntity.ok()
                .cacheControl(org.springframework.http.CacheControl.noStore())
                .contentType(MediaType.valueOf("text/html;charset=UTF-8"))
                .body(html);
    }

    /**
     * S7-07 (Tiêu chí 2): Xem danh sách chứng từ điều chỉnh của một hóa đơn thuộc đại lý đăng nhập.
     */
    @GetMapping({"/invoices/{id}/adjustments", "/debt/invoices/{id}/adjustments"})
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('INVOICE_VIEW')")
    public ApiResponse getMyInvoiceAdjustments(
            @PathVariable Long id,
            Principal principal,
            @RequestParam(required = false) Long dealerId) {
        Dealer dealer = resolveAuthenticatedDealer(principal, dealerId);
        InvoiceResponse inv = invoiceService.getDealerInvoiceById(dealer.getId(), id);
        return ApiResponse.success("Danh sách chứng từ điều chỉnh của hóa đơn", inv.getAdjustments());
    }

    /**
     * S7-07 (Tiêu chí 2): Tải lại file PDF chứng từ điều chỉnh của hóa đơn thuộc đại lý đăng nhập.
     */
    @GetMapping(value = {"/invoices/{id}/adjustments/{adjustmentId}/pdf", "/debt/invoices/{id}/adjustments/{adjustmentId}/pdf"}, produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('INVOICE_VIEW')")
    public ResponseEntity<byte[]> downloadMyAdjustmentPdf(
            @PathVariable Long id,
            @PathVariable Long adjustmentId,
            @RequestParam(defaultValue = "true") boolean download,
            Principal principal,
            @RequestParam(required = false) Long dealerId) {
        Dealer dealer = resolveAuthenticatedDealer(principal, dealerId);
        InvoiceResponse inv = invoiceService.getDealerInvoiceById(dealer.getId(), id);

        boolean belongsToInvoice = inv.getAdjustments() != null && inv.getAdjustments().stream()
                .anyMatch(a -> adjustmentId.equals(a.getId()));
        if (!belongsToInvoice) {
            throw new AccessDeniedException("Chứng từ điều chỉnh không thuộc hóa đơn của đại lý");
        }

        String username = principal != null ? principal.getName() : "system";
        byte[] pdfBytes = invoicePdfService.generateAdjustmentPdf(adjustmentId, username);

        String filename = "chung-tu-dieu-chinh-" + adjustmentId + ".pdf";
        String disposition = download
                ? "attachment; filename=\"" + filename + "\""
                : "inline; filename=\"" + filename + "\"";

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .cacheControl(org.springframework.http.CacheControl.noStore())
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdfBytes);
    }

    /**
     * S4-10: Đại lý tự tạo đơn hàng qua cổng.
     * Quy tắc nghiệp vụ bắt buộc:
     * - Tự động gắn đơn vào đúng đại lý đăng nhập (không thể chọn sai hay đổi sang đại lý khác).
     * - Áp dụng bảng giá hiệu lực của nhóm khách hàng đại lý.
     * - Đơn gửi lên LUÔN ở trạng thái Chờ duyệt (PENDING_APPROVAL), do nhân viên phụ trách xác nhận.
     */
    @PostMapping("/orders")
    @PreAuthorize("hasAuthority('ORDER_CREATE')")
    public ResponseEntity<ApiResponse> createOrder(
            @Valid @RequestBody DealerPortalOrderRequest request,
            Principal principal, @RequestParam(required = false) Long dealerId) {
        Dealer dealer = resolveAuthenticatedDealer(principal, dealerId);

        OrderRequest orderRequest = OrderRequest.builder()
                .dealerId(dealer.getId())
                .deliveryPointId(request.getDeliveryPointId())
                .expectedDeliveryDate(request.getExpectedDeliveryDate())
                .note(request.getNote())
                .items(request.getItems().stream().map(item -> OrderItemRequest.builder()
                        .productId(item.getProductId()).productCode(item.getProductCode())
                        .unit(item.getUnit()).quantity(item.getQuantity()).note(item.getNote()).build()).toList())
                .build();

        OrderResponse response = orderService.createPortalOrder(orderRequest, principal.getName());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Đặt hàng thành công. Đơn hàng đang ở trạng thái Chờ duyệt.", response));
    }

    /** Price/discount/debt preview uses the same sanitized items and order-day price as creation. */
    @PostMapping("/orders/preview")
    @PreAuthorize("hasRole('ADMIN') or (hasAuthority('ORDER_CREATE') and hasAuthority('PRODUCT_VIEW') and hasAuthority('ORDER_VIEW'))")
    public ApiResponse previewOrder(@Valid @RequestBody DealerPortalOrderRequest request,
                                    Principal principal, @RequestParam(required = false) Long dealerId) {
        Dealer dealer = resolveAuthenticatedDealer(principal, dealerId);
        List<OrderItemRequest> items = request.getItems().stream().map(item -> OrderItemRequest.builder()
                .productId(item.getProductId()).productCode(item.getProductCode()).unit(item.getUnit())
                .quantity(item.getQuantity()).note(item.getNote()).build()).toList();
        OrderCalculateResponse pricing = orderService.calculateOrder(OrderCalculateRequest.builder()
                .dealerId(dealer.getId()).orderDate(LocalDate.now()).items(items).build());
        return ApiResponse.success("Tổng tiền, chiết khấu và công nợ theo dữ liệu hiện hành", Map.of(
                "pricing", pricing,
                "debt", dealerDebtService.checkOrderDebt(dealer.getId(), pricing.getTotalAmount())));
    }

    /**
     * S4-10, S5-01: Lấy danh sách đơn hàng của riêng đại lý đăng nhập.
     */
    @GetMapping("/orders")
    @PreAuthorize("hasAuthority('ORDER_VIEW')")
    public ApiResponse getMyOrders(Principal principal, @RequestParam(required = false) Long dealerId) {
        Dealer dealer = resolveAuthenticatedDealer(principal, dealerId);
        List<OrderResponse> orders = orderService.getOrders(
                OrderFilterRequest.builder().dealerId(dealer.getId()).build()).getOrders();
        return ApiResponse.success("Danh sách đơn hàng của đại lý", orders);
    }

    /**
     * S4-10, S5-01: Xem chi tiết một đơn hàng của riêng đại lý đăng nhập.
     */
    @GetMapping("/orders/{id}")
    @PreAuthorize("hasAuthority('ORDER_VIEW')")
    public ApiResponse getMyOrderDetail(@PathVariable Long id, Principal principal,
                                       @RequestParam(required = false) Long dealerId) {
        Dealer dealer = resolveAuthenticatedDealer(principal, dealerId);
        OrderResponse order = orderService.getOrderById(id, principal.getName());
        if (!dealer.getId().equals(order.getDealerId())) {
            throw new AccessDeniedException("Đơn hàng không thuộc đại lý đang chọn");
        }
        return ApiResponse.success("Chi tiết đơn hàng", order);
    }

    /**
     * S6-09: Theo dõi trạng thái giao hàng của đơn hàng dành cho Đại lý.
     * Hiển thị 5 mốc thời gian: đã duyệt, đang soạn, đã xuất, đang giao, đã giao.
     * Xem được số lượng thực giao từng dòng hàng và thông tin xe/tài xế để chủ động bố trí người nhận.
     */
    @GetMapping({"/orders/{id}/tracking", "/orders/{id}/delivery-tracking"})
    @PreAuthorize("hasAuthority('ORDER_VIEW')")
    public ApiResponse getOrderDeliveryTracking(@PathVariable Long id, Principal principal,
                                                @RequestParam(required = false) Long dealerId) {
        Dealer dealer = resolveAuthenticatedDealer(principal, dealerId);
        DealerDeliveryTrackingResponse tracking = orderService.getDealerOrderDeliveryTracking(id, dealer.getId());
        return ApiResponse.success("Theo dõi giao hàng của đơn", tracking);
    }

    /**
     * S4-10: Lấy danh sách điểm giao hàng của đại lý đăng nhập.
     */
    @GetMapping("/delivery-points")
    @PreAuthorize("hasAuthority('ORDER_VIEW')")
    public ApiResponse getDeliveryPoints(Principal principal, @RequestParam(required = false) Long dealerId) {
        Dealer dealer = resolveAuthenticatedDealer(principal, dealerId);
        List<DealerDeliveryPointResponse> points = deliveryPointService.getDeliveryPointsByDealer(dealer.getId());
        return ApiResponse.success("Danh sách điểm giao hàng", points);
    }

    /**
     * S5-02: Xem trước đơn đặt lại từ đơn hàng cũ cho Đại lý.
     * Tự động phân loại mặt hàng còn kinh doanh vs đã ngừng kinh doanh / không còn trong bảng giá,
     * áp lại giá theo bảng giá hiệu lực hiện hành.
     */
    @GetMapping("/orders/{id}/reorder-preview")
    @PreAuthorize("hasRole('ADMIN') or (hasAuthority('ORDER_VIEW') and hasAuthority('ORDER_CREATE'))")
    public ApiResponse getReorderPreview(@PathVariable Long id, Principal principal,
                                         @RequestParam(required = false) Long dealerId) {
        Dealer dealer = resolveAuthenticatedDealer(principal, dealerId);
        DealerPortalReorderPreviewResponse preview = orderService.getPortalReorderPreview(id, principal.getName());
        if (!dealer.getId().equals(preview.getDealerId())) {
            throw new AccessDeniedException("Đơn hàng không thuộc đại lý đang chọn");
        }
        return ApiResponse.success("Xem trước đơn đặt lại", preview);
    }

    /**
     * S5-02: Đại lý đặt lại đơn hàng cũ (toàn bộ hoặc một phần dòng hàng).
     * Loại bỏ sản phẩm ngừng kinh doanh kèm thông báo, áp lại giá hiện hành, đơn ở trạng thái Chờ duyệt.
     */
    @PostMapping("/orders/{id}/reorder")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('ORDER_CREATE')")
    public ResponseEntity<ApiResponse> reorder(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) DealerPortalReorderRequest request,
            Principal principal,
            @RequestParam(required = false) Long dealerId) {
        Dealer dealer = resolveAuthenticatedDealer(principal, dealerId);
        DealerPortalReorderResponse response = orderService.createPortalReorder(id, request, principal.getName());
        if (!dealer.getId().equals(response.getOrder().getDealerId())) {
            throw new AccessDeniedException("Đơn hàng không thuộc đại lý đang chọn");
        }
        String msg = "Đặt lại đơn hàng thành công. Đơn hàng đang ở trạng thái Chờ duyệt.";
        if (response.getWarnings() != null && !response.getWarnings().isEmpty()) {
            msg += " Lưu ý: Một số mặt hàng ngừng kinh doanh hoặc không có trong bảng giá đã được loại bỏ.";
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(msg, response));
    }
}
