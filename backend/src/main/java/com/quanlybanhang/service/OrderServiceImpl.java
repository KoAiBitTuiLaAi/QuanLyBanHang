package com.quanlybanhang.service;

import com.quanlybanhang.dto.request.DealerPortalReorderItemRequest;
import com.quanlybanhang.dto.request.DealerPortalReorderRequest;
import com.quanlybanhang.dto.request.OrderCalculateRequest;
import com.quanlybanhang.dto.request.OrderInventoryCheckRequest;
import com.quanlybanhang.dto.request.OrderItemRequest;
import com.quanlybanhang.dto.request.OrderLineItemPricingRequest;
import com.quanlybanhang.dto.request.OrderRequest;
import com.quanlybanhang.dto.response.*;
import com.quanlybanhang.exception.DealerLockedException;
import com.quanlybanhang.exception.OrderCannotBeCancelledException;
import com.quanlybanhang.model.*;
import com.quanlybanhang.dto.request.OrderStatusUpdateRequest;
import com.quanlybanhang.repository.DealerRepository;
import com.quanlybanhang.repository.DealerDeliveryPointRepository;
import com.quanlybanhang.repository.OrderApprovalHistoryRepository;
import com.quanlybanhang.repository.OrderRepository;
import com.quanlybanhang.repository.PriceListRepository;
import com.quanlybanhang.repository.ProductRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.quanlybanhang.dto.request.OrderFilterRequest;
import com.quanlybanhang.dto.response.OrderFilterResult;
import com.quanlybanhang.model.OrderDraft;
import com.quanlybanhang.repository.OrderDraftRepository;
import com.quanlybanhang.repository.UserRepository;
import com.quanlybanhang.repository.WarehouseLocationProductRepository;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import com.quanlybanhang.repository.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import com.quanlybanhang.repository.WarehouseRepository;
import com.quanlybanhang.repository.WarehouseInventoryRepository;

@Service
public class OrderServiceImpl implements OrderService {

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager reservationTransactions;

    @Autowired
    private DeliveryTripStopRepository deliveryTripStopRepository;

    private void requireTripConfirmation(Long orderId) {
        if (deliveryTripStopRepository != null && deliveryTripStopRepository.existsByOrderId(orderId)) {
            throw new IllegalArgumentException("Đơn thuộc chuyến giao: phải dùng confirm-delivery kèm chứng từ S6-08");
        }
    }

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final DealerRepository dealerRepository;
    private final DealerDeliveryPointRepository deliveryPointRepository;
    private final PriceListRepository priceListRepository;
    private final WarehouseRepository warehouseRepository;
    private final WarehouseInventoryRepository inventoryRepository;
    private final UnitConversionService unitConversionService;
    private final AuditLogService auditLogService;
    private final VolumeDiscountPolicyService volumeDiscountPolicyService;
    private final DealerDebtService dealerDebtService;
    private final OrderStatusHistoryRepository orderStatusHistoryRepository;

    @Autowired
    public OrderServiceImpl(
            OrderRepository orderRepository,
            ProductRepository productRepository,
            DealerRepository dealerRepository,
            DealerDeliveryPointRepository deliveryPointRepository,
            PriceListRepository priceListRepository,
            UnitConversionService unitConversionService,
            AuditLogService auditLogService,
            VolumeDiscountPolicyService volumeDiscountPolicyService,
            DealerDebtService dealerDebtService,
            WarehouseRepository warehouseRepository,
            WarehouseInventoryRepository inventoryRepository,
            OrderStatusHistoryRepository orderStatusHistoryRepository,
            UserRepository userRepository) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.dealerRepository = dealerRepository;
        this.deliveryPointRepository = deliveryPointRepository;
        this.priceListRepository = priceListRepository;
        this.unitConversionService = unitConversionService;
        this.auditLogService = auditLogService;
        this.volumeDiscountPolicyService = volumeDiscountPolicyService;
        this.dealerDebtService = dealerDebtService;
        this.warehouseRepository = warehouseRepository;
        this.inventoryRepository = inventoryRepository;
        this.orderStatusHistoryRepository = orderStatusHistoryRepository;
        this.userRepository = userRepository;
    }

    /**
     * Constructor tương thích cho các unit test cũ chỉ mock 9 bean ban đầu.
     */
    public OrderServiceImpl(
            OrderRepository orderRepository,
            ProductRepository productRepository,
            DealerRepository dealerRepository,
            DealerDeliveryPointRepository deliveryPointRepository,
            PriceListRepository priceListRepository,
            UnitConversionService unitConversionService,
            AuditLogService auditLogService,
            VolumeDiscountPolicyService volumeDiscountPolicyService,
            DealerDebtService dealerDebtService) {
        this(orderRepository, productRepository, dealerRepository, deliveryPointRepository,
                priceListRepository, unitConversionService, auditLogService,
                volumeDiscountPolicyService, dealerDebtService, null, null, null, null);
    }

    private UserRepository userRepository;

    @Autowired(required = false)
    public void setUserRepository(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Autowired(required = false)
    private OrderDraftRepository orderDraftRepository;

    @Autowired(required = false)
    private ObjectMapper objectMapper;

    @Autowired(required = false)
    private WarehouseLocationProductRepository locationProductRepository;

    @Autowired(required = false)
    public void setLocationProductRepository(WarehouseLocationProductRepository locationProductRepository) {
        this.locationProductRepository = locationProductRepository;
    }

    private OrderApprovalHistoryRepository orderApprovalHistoryRepository;

    @Autowired(required = false)
    public void setOrderApprovalHistoryRepository(OrderApprovalHistoryRepository orderApprovalHistoryRepository) {
        this.orderApprovalHistoryRepository = orderApprovalHistoryRepository;
    }

    @Autowired(required = false)
    private OrderPdfService orderPdfService;

    @Autowired(required = false)
    public void setOrderPdfService(OrderPdfService orderPdfService) {
        this.orderPdfService = orderPdfService;
    }

    private OrderPdfService getPdfService() {
        if (this.orderPdfService == null) {
            this.orderPdfService = new OrderPdfServiceImpl(orderRepository);
        }
        return this.orderPdfService;
    }

    private record ItemPreData(
            Product product,
            String unit,
            BigDecimal quantity,
            BigDecimal conversionFactor,
            BigDecimal baseQuantity,
            BigDecimal price,
            BigDecimal floorPrice,
            boolean isBelowFloor,
            BigDecimal subtotal,
            String note
    ) {}

    /**
     * S4-01: Resolve the price list selected by the client or the currently
     * effective list, while keeping the dealer/customer-group boundary intact.
     */
    private PriceList resolveOrderPriceList(Long requestedPriceListId,
                                             CustomerGroup customerGroup,
                                             LocalDate orderDate) {
        if (requestedPriceListId != null) {
            PriceList requested = priceListRepository.findById(requestedPriceListId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Không tìm thấy bảng giá với ID: " + requestedPriceListId));

            if (customerGroup != null && requested.getCustomerGroup() != customerGroup) {
                throw new IllegalArgumentException(String.format(
                        "Bảng giá '%s' không thuộc nhóm khách hàng '%s'.",
                        requested.getName(), customerGroup.getDisplayName()));
            }

            if (!Boolean.TRUE.equals(requested.getActive())
                    || requested.getStartDate() == null
                    || requested.getEndDate() == null
                    || requested.getStartDate().isAfter(orderDate)
                    || requested.getEndDate().isBefore(orderDate)) {
                throw new IllegalArgumentException(String.format(
                        "Bảng giá '%s' không có hiệu lực tại ngày %s.",
                        requested.getName(), orderDate));
            }
            return requested;
        }

        if (customerGroup == null) {
            return null;
        }

        List<PriceList> effectivePriceLists = priceListRepository
                .findEffectivePriceLists(customerGroup, orderDate);
        if (effectivePriceLists.isEmpty()) {
            throw new IllegalArgumentException(String.format(
                    "Không tìm thấy bảng giá hiệu lực cho nhóm khách hàng '%s'. Không thể thêm dòng hàng.",
                    customerGroup.getDisplayName()));
        }
        return effectivePriceLists.get(0);
    }

    @Override
    @Transactional
    public OrderResponse createOrder(OrderRequest request, String username) {
        return createOrder(request, username, false);
    }

    @Override
    @Transactional
    public OrderResponse createPortalOrder(OrderRequest request, String username) {
        return createOrder(request, username, true);
    }

    private OrderResponse createOrder(OrderRequest request, String username, boolean portalOrder) {
        if (request.getItems() == null || request.getItems().isEmpty()) {
            throw new IllegalArgumentException("Đơn hàng phải có ít nhất 1 sản phẩm");
        }

        String orderCode = request.getOrderCode();
        if (orderCode == null || orderCode.isBlank()) {
            orderCode = "DH-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"));
        } else if (orderRepository.existsByOrderCode(orderCode.trim())) {
            throw new IllegalArgumentException("Mã đơn hàng '" + orderCode + "' đã tồn tại");
        }

        // 1. Xác định đại lý và nhóm khách hàng (S2-10, S4-10)
        boolean isDealer = isDealerUser();
        Dealer dealer = null;
        CustomerGroup customerGroup = request.getCustomerGroup();

        if (isDealer) {
            User currentUser = getCurrentUser();
            if (currentUser == null || currentUser.getDealer() == null) {
                throw new AccessDeniedException("Tài khoản đại lý chưa được liên kết với đại lý cụ thể");
            }
            if (request.getDealerId() != null && !currentUser.getDealer().getId().equals(request.getDealerId())) {
                throw new AccessDeniedException("Đại lý không được phép đặt hàng cho đại lý khác");
            }
            dealer = currentUser.getDealer();
            if (Boolean.TRUE.equals(dealer.getLocked())) {
                throw new DealerLockedException(dealer.getCode(), dealer.getName(), dealer.getLockReason());
            }
            if (Boolean.FALSE.equals(dealer.getActive())) {
                throw new IllegalArgumentException(String.format("Đại lý '%s' (%s) đã ngừng giao dịch, không thể tạo đơn hàng mới.",
                        dealer.getName(), dealer.getCode()));
            }
            if (dealerDebtService != null) {
                dealerDebtService.validateDealerCanCreateOrder(dealer);
            }
            customerGroup = dealer.getCustomerGroup();
        } else if (request.getDealerId() != null) {
            dealer = dealerRepository.findById(request.getDealerId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đại lý với ID: " + request.getDealerId()));
            // S3-07: Đại lý bị khoá không tạo được đơn mới, kể cả trên cổng đặt hàng
            if (Boolean.TRUE.equals(dealer.getLocked())) {
                throw new DealerLockedException(dealer.getCode(), dealer.getName(), dealer.getLockReason());
            }
            if (Boolean.FALSE.equals(dealer.getActive())) {
                throw new IllegalArgumentException(String.format("Đại lý '%s' (%s) đã ngừng giao dịch, không thể tạo đơn hàng mới.",
                        dealer.getName(), dealer.getCode()));
            }
            // S4-02: Đại lý có khoản quá hạn quá số ngày cho phép thì chặn tạo đơn hoàn toàn
            if (dealerDebtService != null) {
                dealerDebtService.validateDealerCanCreateOrder(dealer);
            }
            // S4-01: Bảng giá lấy theo nhóm khách hàng mà đại lý thuộc về
            customerGroup = dealer.getCustomerGroup();
        }

        // 2. Tự động tìm và xác thực bảng giá hiệu lực phù hợp (S2-10, S4-01)
        // Match catalog and pricing preview: shipping date does not change order-day pricing.
        LocalDate queryDate = LocalDate.now();
        PriceList priceList = resolveOrderPriceList(request.getPriceListId(), customerGroup, queryDate);
        if (customerGroup == null && priceList != null) {
            customerGroup = priceList.getCustomerGroup();
        }

        // 2b. Lấy thông tin điểm giao hàng (S3-04)
        DealerDeliveryPoint deliveryPoint = null;
        if (request.getDeliveryPointId() != null) {
            deliveryPoint = deliveryPointRepository.findById(request.getDeliveryPointId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy điểm giao hàng với ID: " + request.getDeliveryPointId()));
            if (dealer != null && !deliveryPoint.getDealer().getId().equals(dealer.getId())) {
                throw new IllegalArgumentException("Điểm giao hàng không thuộc đại lý đã chọn");
            }
        } else if (dealer != null) {
            // Lấy điểm giao hàng mặc định nếu có
            Optional<DealerDeliveryPoint> defaultPoint = deliveryPointRepository.findByDealerIdAndIsDefaultTrue(dealer.getId());
            if (defaultPoint.isPresent()) {
                deliveryPoint = defaultPoint.get();
            }
        }

        // 2c. Xác định kho phục vụ đại lý / xuất hàng (S4-03)
        Warehouse warehouse = resolveServingWarehouse(dealer, request.getWarehouseId(), true);

        // Nếu bảng giá được sử dụng, đánh dấu đã phát sinh đơn (hasOrders = true)
        if (priceList != null) {
            priceList.setHasOrders(true);
            priceListRepository.save(priceList);
        }

        // Tạo map tra cứu dòng giá từ bảng giá: productId -> PriceListItem
        Map<Long, PriceListItem> priceItemMap = new HashMap<>();
        if (priceList != null && priceList.getItems() != null) {
            for (PriceListItem pli : priceList.getItems()) {
                if (pli.getProduct() != null) {
                    priceItemMap.put(pli.getProduct().getId(), pli);
                }
            }
        }

        Order order = Order.builder()
                .orderCode(orderCode.trim())
                .customerName(request.getCustomerName() != null ? request.getCustomerName() : (dealer != null ? dealer.getName() : null))
                .customerGroup(customerGroup)
                .dealer(dealer)
                .deliveryPoint(deliveryPoint)
                .warehouse(warehouse)
                .expectedDeliveryDate(request.getExpectedDeliveryDate())
                .priceList(priceList)
                .note(request.getNote())
                .status("RECORDED")
                .requiresApproval(false)
                .inventoryReserved(false)
                .createdBy(username != null ? username : "system")
                .recordedAt(LocalDateTime.now())
                .items(new ArrayList<>())
                .build();

        boolean orderRequiresApproval = false;
        List<String> belowFloorReasons = new ArrayList<>();
        List<VolumeDiscountPolicyService.OrderItemDiscountInput> discountInputs = new ArrayList<>();
        List<ItemPreData> preDataList = new ArrayList<>();

        for (int i = 0; i < request.getItems().size(); i++) {
            OrderItemRequest itemReq = request.getItems().get(i);
            Product product = resolveProduct(itemReq);

            // S4-01: Chặn thêm dòng hàng nếu không có bảng giá hiệu lực cho SKU đó
            if (customerGroup != null) {
                if (priceList == null) {
                    throw new IllegalArgumentException(String.format("Không tìm thấy bảng giá hiệu lực cho nhóm khách hàng '%s'. Không thể thêm dòng hàng cho sản phẩm '%s'.",
                            customerGroup.getDisplayName(), product.getName()));
                }
                PriceListItem pli = priceItemMap.get(product.getId());
                if (pli == null) {
                    throw new IllegalArgumentException(String.format("Sản phẩm '%s' (mã: %s) chưa có trong bảng giá hiệu lực của nhóm khách hàng '%s'. Không thể thêm dòng hàng.",
                            product.getName(), product.getCode(), customerGroup.getDisplayName()));
                }
            }

            String inputUnit = itemReq.getUnit();
            if (inputUnit == null || inputUnit.isBlank()) {
                inputUnit = product.getUnit();
            } else {
                inputUnit = inputUnit.trim();
            }

            BigDecimal qty = itemReq.getQuantity();
            if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("Số lượng cho sản phẩm '" + product.getCode() + "' phải lớn hơn 0");
            }

            // Lấy hệ số quy đổi tại thời điểm ghi sổ đơn hàng (S2-07)
            BigDecimal factor = unitConversionService.getConversionFactor(product, inputUnit);

            // Quy đổi về đơn vị cơ sở: baseQuantity = quantity * factor
            BigDecimal baseQty = qty.multiply(factor);

            // 3. Tự động nhận đúng giá theo bảng giá hoặc kiểm tra giá sàn (S2-10, S4-01)
            PriceListItem priceListItem = priceItemMap.get(product.getId());

            BigDecimal unitSellingPrice = null;
            BigDecimal unitFloorPrice = null;

            if (priceListItem != null) {
                // Giá bảng giá tính theo đơn vị cơ sở quy đổi theo hệ số
                unitSellingPrice = priceListItem.getSellingPrice().multiply(factor);
                unitFloorPrice = priceListItem.getFloorPrice().multiply(factor);
            }

            BigDecimal price;
            if (itemReq.getPrice() != null) {
                price = itemReq.getPrice();
            } else if (unitSellingPrice != null) {
                // Đại lý tự động nhận đúng giá bán của mình từ bảng giá
                price = unitSellingPrice;
            } else {
                price = product.getPrice() != null ? product.getPrice().multiply(factor) : BigDecimal.ZERO;
            }

            // Kiểm tra bán dưới giá sàn — bán dưới giá sàn sẽ phải qua Quản lý kinh doanh duyệt
            boolean isBelowFloor = false;
            if (unitFloorPrice != null && price.compareTo(unitFloorPrice) < 0) {
                isBelowFloor = true;
                orderRequiresApproval = true;
                belowFloorReasons.add(String.format("Sản phẩm '%s' bán giá %s < giá sàn %s (%s)",
                        product.getName(), price.toPlainString(), unitFloorPrice.toPlainString(), inputUnit));
            }

            BigDecimal subtotalAmount = price.multiply(qty);

            discountInputs.add(VolumeDiscountPolicyService.OrderItemDiscountInput.builder()
                    .index(i)
                    .product(product)
                    .unit(inputUnit)
                    .quantity(qty)
                    .conversionFactor(factor)
                    .baseQuantity(baseQty)
                    .baseUnit(product.getUnit())
                    .price(price)
                    .subtotalAmount(subtotalAmount)
                    .build());

            preDataList.add(new ItemPreData(product, inputUnit, qty, factor, baseQty, price, unitFloorPrice, isBelowFloor, subtotalAmount, itemReq.getNote()));
        }

        // 3b. Giữ chỗ tồn kho (S5-06): Thực hiện khi đơn chuyển sang Đã duyệt (RECORDED).
        // Nếu đơn cần duyệt (PENDING_APPROVAL), việc giữ chỗ sẽ thực hiện khi Quản lý duyệt đơn.

        // 4. Áp dụng chính sách chiết khấu theo sản lượng (S3-01)
        VolumeDiscountPolicyService.OrderDiscountSummary discountSummary = null;
        if (volumeDiscountPolicyService != null) {
            discountSummary = volumeDiscountPolicyService.calculateOrderDiscount(discountInputs, customerGroup, LocalDate.now());
        }

        BigDecimal grandSubtotal = BigDecimal.ZERO;
        BigDecimal grandDiscount = BigDecimal.ZERO;

        for (int i = 0; i < preDataList.size(); i++) {
            ItemPreData d = preDataList.get(i);
            BigDecimal discountAmount = BigDecimal.ZERO;
            BigDecimal discountPercent = null;
            Long appliedPolicyId = null;
            String appliedPolicyCode = null;
            String appliedPolicyName = null;

            if (discountSummary != null && discountSummary.getItemResults() != null) {
                VolumeDiscountPolicyService.ItemDiscountResult res = discountSummary.getItemResults().get(i);
                if (res != null) {
                    discountAmount = res.getDiscountAmount() != null ? res.getDiscountAmount() : BigDecimal.ZERO;
                    discountPercent = res.getDiscountPercent();
                    appliedPolicyId = res.getAppliedPolicyId();
                    appliedPolicyCode = res.getAppliedPolicyCode();
                    appliedPolicyName = res.getAppliedPolicyName();
                }
            }

            BigDecimal lineTotal = d.subtotal.subtract(discountAmount).max(BigDecimal.ZERO);
            grandSubtotal = grandSubtotal.add(d.subtotal);
            grandDiscount = grandDiscount.add(discountAmount);

            // ĐÓNG BĂNG thông tin đơn vị, hệ số, giá sàn, số lượng cơ sở và chiết khấu vào dòng đơn hàng
            OrderItem item = OrderItem.builder()
                    .order(order)
                    .product(d.product)
                    .unit(d.unit)
                    .quantity(d.quantity)
                    .conversionFactor(d.conversionFactor)
                    .baseUnit(d.product.getUnit())
                    .baseQuantity(d.baseQuantity)
                    .price(d.price)
                    .floorPrice(d.floorPrice)
                    .belowFloorPrice(d.isBelowFloor)
                    .subtotalAmount(d.subtotal)
                    .discountAmount(discountAmount)
                    .discountPercent(discountPercent)
                    .appliedPolicyId(appliedPolicyId)
                    .appliedPolicyCode(appliedPolicyCode)
                    .appliedPolicyName(appliedPolicyName)
                    .totalAmount(lineTotal)
                    .note(d.note)
                    .build();

            order.addItem(item);
        }

        order.setSubtotalAmount(grandSubtotal);
        order.setDiscountAmount(grandDiscount);
        BigDecimal orderTotal = grandSubtotal.subtract(grandDiscount).max(BigDecimal.ZERO);
        order.setTotalAmount(orderTotal);

        // 5. Kiểm tra hạn mức công nợ của đại lý (S4-02):
        // Tổng đơn cộng công nợ hiện tại vượt hạn mức thì đơn bị đánh dấu cần duyệt
        if (dealer != null && dealerDebtService != null) {
            DealerDebtResponse debtInfo = dealerDebtService.getDealerDebtInfo(dealer);
            BigDecimal currentDebt = debtInfo != null && debtInfo.getCurrentDebt() != null
                    ? debtInfo.getCurrentDebt()
                    : BigDecimal.ZERO;
            BigDecimal debtLimit = debtInfo != null && debtInfo.getDebtLimit() != null
                    ? debtInfo.getDebtLimit()
                    : (dealer.getDebtLimit() != null ? dealer.getDebtLimit() : BigDecimal.ZERO);

            if (debtInfo != null && Boolean.TRUE.equals(debtInfo.getIsDebtFlagged()) && debtInfo.getEffectiveAction() == com.quanlybanhang.model.DebtFlagAction.REQUIRE_APPROVAL) {
                orderRequiresApproval = true;
                belowFloorReasons.add(String.format("Đại lý bị gắn cờ rủi ro công nợ: %s (Hệ thống chuyển cần duyệt theo cấu hình)",
                        debtInfo.getDebtFlagReason()));
            }
            if (debtInfo != null && Boolean.TRUE.equals(debtInfo.getHasOverdueDebt()) && debtInfo.getEffectiveAction() == com.quanlybanhang.model.DebtFlagAction.REQUIRE_APPROVAL) {
                orderRequiresApproval = true;
                belowFloorReasons.add(String.format("Đại lý có nợ quá hạn %d ngày (vượt quá giới hạn %d ngày), chuyển cần duyệt theo cấu hình",
                        debtInfo.getMaxOverdueDays(), debtInfo.getMaxDebtDays()));
            }
            if (dealerDebtService.checkProjectedDebtExceedsLimit(dealer, orderTotal, currentDebt)) {
                orderRequiresApproval = true;
                BigDecimal projectedDebt = currentDebt.add(orderTotal);
                belowFloorReasons.add(String.format(
                        "Vượt hạn mức công nợ: Công nợ hiện tại (%s) + Đơn mới (%s) = %s vượt hạn mức (%s)",
                        formatMoney(currentDebt),
                        formatMoney(orderTotal),
                        formatMoney(projectedDebt),
                        formatMoney(debtLimit)));
            }
        }

        // S4-10: Đơn hàng do đại lý tự đặt qua cổng luôn ở trạng thái CHỜ DUYỆT (PENDING_APPROVAL), do nhân viên phụ trách xác nhận
        if (portalOrder || isDealer) {
            order.setStatus("PENDING_APPROVAL");
            order.setRequiresApproval(true);
            String dealerReason = "[Đại lý tự đặt qua cổng] Chờ nhân viên phụ trách xác nhận";
            if (!belowFloorReasons.isEmpty()) {
                dealerReason += "; " + String.join("; ", belowFloorReasons);
            }
            order.setApprovalReason(dealerReason);
        } else if (orderRequiresApproval) {
            order.setStatus("PENDING_APPROVAL");
            order.setRequiresApproval(true);
            order.setApprovalReason(String.join("; ", belowFloorReasons));
            order.setInventoryReserved(false);
        } else {
            order.setStatus("RECORDED");
            order.setRequiresApproval(false);
            // S5-06: Đơn không cần duyệt -> Đã duyệt ngay khi tạo -> thực hiện giữ chỗ tồn kho
            reserveInventoryForOrder(order, request.getReservationHours(), request.getReservationExpiresAt());
        }

        Order saved = orderRepository.save(order);
        if (orderRequiresApproval || portalOrder || isDealer) {
            recordStatusHistory(saved, null, "PENDING_APPROVAL", "Đơn hàng chờ Quản lý kinh doanh duyệt: " + saved.getApprovalReason(), username);
        } else {
            recordStatusHistory(saved, null, "RECORDED", "Đơn hàng đã được duyệt/ghi nhận, đã giữ chỗ tồn kho", username);
        }
        if ((orderRequiresApproval || portalOrder || isDealer) && orderApprovalHistoryRepository != null) {
            try {
                OrderApprovalHistory history = OrderApprovalHistory.builder()
                        .order(saved)
                        .orderCode(saved.getOrderCode())
                        .action("PENDING_APPROVAL")
                        .comment(saved.getApprovalReason())
                        .performedBy(username != null ? username : "system")
                        .build();
                orderApprovalHistoryRepository.save(history);
            } catch (Exception ignored) {
            }
        }
        String auditSuffix = orderRequiresApproval ? " (Chờ duyệt: " + order.getApprovalReason() + ")" : "";
        auditLogService.logAction("INVOICE", saved.getOrderCode(), "CREATE", null,
                "Tổng tiền: " + saved.getTotalAmount().toPlainString() + auditSuffix,
                username != null ? username : "system");

        return mapToResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public OrderFilterResult getOrders(OrderFilterRequest filter) {
        if (filter == null) {
            filter = new OrderFilterRequest();
        }

        final OrderFilterRequest f = filter;
        final boolean salesOnly = isSalesOnlyUser();
        final boolean dealerOnly = isDealerOnlyUser();
        final User currentUser = (salesOnly || dealerOnly) ? getCurrentUser() : null;
        final Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        final String currentUsername = auth != null ? auth.getName() : null;

        Specification<Order> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            // 0. Phân quyền dữ liệu (RLS): Đại lý chỉ thấy đơn hàng của chính mình (S4-10)
            if (dealerOnly) {
                if (currentUser != null && currentUser.getDealer() != null) {
                    predicates.add(cb.equal(root.get("dealer").get("id"), currentUser.getDealer().getId()));
                } else {
                    predicates.add(cb.disjunction());
                }
            }

            // 1. Phân quyền dữ liệu (RLS): Nhân viên kinh doanh chỉ thấy đơn của đại lý mình phụ trách (S4-07)
            if (salesOnly) {
                Join<Order, Dealer> dealerJoin = root.join("dealer", JoinType.INNER);
                if (currentUser != null && currentUser.getId() != null) {
                    predicates.add(cb.equal(dealerJoin.get("assignedUser").get("id"), currentUser.getId()));
                } else if (currentUsername != null && !currentUsername.isBlank()) {
                    predicates.add(cb.equal(dealerJoin.get("assignedUser").get("username"), currentUsername));
                } else {
                    predicates.add(cb.disjunction());
                }
            }

            // 2. Lọc theo trạng thái đơn hàng (status)
            if (f.getStatus() != null && !f.getStatus().isBlank()) {
                predicates.add(cb.equal(cb.upper(root.get("status")), f.getStatus().trim().toUpperCase()));
            }

            // 3. Lọc theo đại lý (dealerId)
            if (f.getDealerId() != null) {
                predicates.add(cb.equal(root.get("dealer").get("id"), f.getDealerId()));
            }

            // 4. Lọc theo nhân viên kinh doanh phụ trách (salesUserId)
            if (!salesOnly && f.getResolvedSalesUserId() != null) {
                Join<Order, Dealer> dealerJoin = root.join("dealer", JoinType.INNER);
                predicates.add(cb.equal(dealerJoin.get("assignedUser").get("id"), f.getResolvedSalesUserId()));
            }

            // 5. Lọc theo khu vực / địa bàn (territoryId)
            if (f.getTerritoryId() != null) {
                Join<Order, Dealer> dealerJoin = root.join("dealer", JoinType.INNER);
                predicates.add(cb.equal(dealerJoin.get("territory").get("id"), f.getTerritoryId()));
            }

            // 6. Lọc theo khoảng thời gian tạo đơn
            if (f.getResolvedStartDate() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), f.getResolvedStartDate().atStartOfDay()));
            }
            if (f.getResolvedEndDate() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), f.getResolvedEndDate().atTime(LocalTime.MAX)));
            }

            if (f.getCreatedBy() != null && !f.getCreatedBy().isBlank()) {
                predicates.add(cb.equal(root.get("createdBy"), f.getCreatedBy().trim()));
            }
            // 7. Lọc theo điều kiện phê duyệt
            if (f.getApprovalRequired() != null) {
                predicates.add(cb.equal(root.get("requiresApproval"), f.getApprovalRequired()));
            }
            if (f.getApprovalReason() != null && !f.getApprovalReason().isBlank()) {
                predicates.add(cb.like(cb.lower(root.get("approvalReason")), "%" + f.getApprovalReason().trim().toLowerCase() + "%"));
            }

            // 8. Tìm kiếm nhanh từ khóa (mã đơn, tên khách, tên/mã đại lý)
            String kw = f.getResolvedKeyword();
            if (kw != null && !kw.isBlank()) {
                String pattern = "%" + kw.toLowerCase() + "%";
                Join<Order, Dealer> dealerJoin = root.join("dealer", JoinType.LEFT);
                Predicate kwPredicate = cb.or(
                        cb.like(cb.lower(root.get("orderCode")), pattern),
                        cb.like(cb.lower(root.get("customerName")), pattern),
                        cb.like(cb.lower(dealerJoin.get("name")), pattern),
                        cb.like(cb.lower(dealerJoin.get("code")), pattern)
                );
                predicates.add(kwPredicate);
            }

            return predicates.isEmpty() ? cb.conjunction() : cb.and(predicates.toArray(new Predicate[0]));
        };

        List<Order> orders = orderRepository.findAll(spec, Sort.by(Sort.Direction.DESC, "createdAt"));

        // Tính tổng tiền của tập kết quả đang lọc (S4-07)
        BigDecimal totalAmount = orders.stream()
                .map(Order::getTotalAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        long totalOrders = orders.size();
        Integer totalPages = null;
        if (f.getPage() != null || f.getSize() != null) {
            int size = f.getSize() == null ? 20 : f.getSize();
            int page = f.getPage() == null ? 0 : f.getPage();
            totalPages = (int) Math.ceil((double) totalOrders / size);
            int from = (int) Math.min((long) page * size, totalOrders);
            orders = orders.subList(from, Math.min(from + size, orders.size()));
        }
        List<OrderResponse> responses = orders.stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());

        return OrderFilterResult.builder()
                .orders(responses)
                .totalAmount(totalAmount)
                .totalOrders(totalOrders)
                .totalPages(totalPages)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderResponse> getAllOrders() {
        return getOrders(new OrderFilterRequest()).getOrders();
    }

    @Override
    @Transactional(readOnly = true)
    public OrderResponse getOrderById(Long id) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String username = auth != null ? auth.getName() : null;
        return getOrderById(id, username);
    }

    @Override
    @Transactional(readOnly = true)
    public OrderResponse getOrderById(Long id, String username) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn hàng với ID: " + id));
        validateOrderViewAccess(order, username);
        return mapToResponse(order);
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderResponse> getOrdersForDealer(String username) {
        if (username == null || username.isBlank()) {
            throw new org.springframework.security.access.AccessDeniedException("Yêu cầu thông tin đăng nhập");
        }
        User user = (userRepository != null) ? userRepository.findByUsername(username).orElse(null) : null;
        if (user == null || user.getDealer() == null) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Tài khoản của bạn chưa được liên kết với hồ sơ đại lý trong hệ thống");
        }
        Long dealerId = user.getDealer().getId();
        return orderRepository.findByDealerIdOrderByCreatedAtDesc(dealerId).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public OrderResponse getOrderByIdForDealer(Long id, String username) {
        if (username == null || username.isBlank()) {
            throw new org.springframework.security.access.AccessDeniedException("Yêu cầu thông tin đăng nhập");
        }
        User user = (userRepository != null) ? userRepository.findByUsername(username).orElse(null) : null;
        if (user == null || user.getDealer() == null) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Tài khoản của bạn chưa được liên kết với hồ sơ đại lý trong hệ thống");
        }
        Long dealerId = user.getDealer().getId();
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn hàng với ID: " + id));

        if (order.getDealer() == null || !dealerId.equals(order.getDealer().getId())) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "Bạn chỉ có quyền xem đơn hàng của chính đại lý mình");
        }
        return mapToResponse(order);
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderResponse> getOrdersForUser(String username) {
        if (username == null || username.isBlank() || "system".equalsIgnoreCase(username) || userRepository == null) {
            return getAllOrders();
        }
        User user = userRepository.findByUsername(username).orElse(null);
        if (user == null) {
            return getAllOrders();
        }

        boolean isDealer = user.getDealer() != null
                || user.getRoles().stream().anyMatch(r -> r.getName() == ERole.ROLE_DEALER);
        if (isDealer) {
            return getOrdersForDealer(username);
        }

        boolean isOnlySales = user.getRoles().stream().anyMatch(r -> r.getName() == ERole.ROLE_SALES)
                && user.getRoles().stream().noneMatch(r -> r.getName() == ERole.ROLE_ADMIN
                || r.getName() == ERole.ROLE_SALES_MANAGER
                || r.getName() == ERole.ROLE_ACCOUNTANT
                || r.getName() == ERole.ROLE_VIEWER);
        if (isOnlySales) {
            return orderRepository.findByDealerAssignedUserIdOrderByCreatedAtDesc(user.getId()).stream()
                    .map(this::mapToResponse)
                    .collect(Collectors.toList());
        }

        return getAllOrders();
    }

    @Override
    @Transactional(readOnly = true)
    public OrderResponse getOrderByIdForUser(Long id, String username) {
        if (username == null || username.isBlank() || "system".equalsIgnoreCase(username) || userRepository == null) {
            return getOrderById(id);
        }
        User user = userRepository.findByUsername(username).orElse(null);
        if (user == null) {
            return getOrderById(id);
        }

        boolean isDealer = user.getDealer() != null
                || user.getRoles().stream().anyMatch(r -> r.getName() == ERole.ROLE_DEALER);
        if (isDealer) {
            return getOrderByIdForDealer(id, username);
        }

        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn hàng với ID: " + id));

        boolean isOnlySales = user.getRoles().stream().anyMatch(r -> r.getName() == ERole.ROLE_SALES)
                && user.getRoles().stream().noneMatch(r -> r.getName() == ERole.ROLE_ADMIN
                || r.getName() == ERole.ROLE_SALES_MANAGER
                || r.getName() == ERole.ROLE_ACCOUNTANT
                || r.getName() == ERole.ROLE_VIEWER);
        if (isOnlySales) {
            if (order.getDealer() != null && order.getDealer().getAssignedUser() != null
                    && !order.getDealer().getAssignedUser().getId().equals(user.getId())) {
                throw new org.springframework.security.access.AccessDeniedException(
                        "Bạn không có quyền truy cập đơn hàng của đại lý chưa được phân công");
            }
        }

        return mapToResponse(order);
    }

    @Override
    @Transactional
    public OrderResponse updateOrderDelivery(Long id, com.quanlybanhang.dto.request.OrderDeliveryUpdateRequest request, String username) {
        Order order = orderRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn hàng với ID: " + id));

        checkWarehouseAuthorizationForOrder(order, "cập nhật giao hàng");
        requireTripConfirmation(id);

        if (request.getItems() != null) {
            Set<Long> seen = new HashSet<>();
            Map<Long, OrderItem> existing = order.getItems().stream()
                    .collect(Collectors.toMap(OrderItem::getId, item -> item));
            for (var line : request.getItems()) {
                if (line == null || line.getOrderItemId() == null || !seen.add(line.getOrderItemId())
                        || !existing.containsKey(line.getOrderItemId())) {
                    throw new IllegalArgumentException("Dòng thực giao phải thuộc đơn hàng và không được trùng lặp");
                }
                BigDecimal delivered = line.getDeliveredQuantity();
                if (delivered == null || delivered.signum() < 0
                        || delivered.stripTrailingZeros().scale() > 4
                        || delivered.compareTo(existing.get(line.getOrderItemId()).getQuantity()) > 0) {
                    throw new IllegalArgumentException("Số lượng thực giao phải từ 0 đến số lượng đặt, tối đa 4 số thập phân");
                }
            }
        }

        String oldStatus = order.getStatus();
        String requestedStatus = request.getStatus() != null && !request.getStatus().isBlank()
                ? normalizeLifecycleStatus(request.getStatus()) : null;
        if (requestedStatus != null && !"DELIVERED".equals(requestedStatus)) {
            throw new IllegalArgumentException("Cập nhật giao hàng chỉ được chuyển đơn sang trạng thái Đã giao");
        }

        boolean statusChanged = !"DELIVERED".equalsIgnoreCase(oldStatus);
        if (statusChanged) {
            // Không cho cập nhật giao hàng bỏ qua các bước Đang soạn và Đã xuất.
            validateLifecycleTransition(oldStatus, "DELIVERED");
            order.setStatus("DELIVERED");
        }

        if (request.getActualDeliveryDate() != null) {
            order.setActualDeliveryDate(request.getActualDeliveryDate());
        } else if (order.getActualDeliveryDate() == null) {
            order.setActualDeliveryDate(LocalDate.now());
        }

        if (request.getDeliveryNote() != null) {
            order.setDeliveryNote(request.getDeliveryNote().trim());
        }

        if (request.getItems() != null && !request.getItems().isEmpty()) {
            Map<Long, com.quanlybanhang.dto.request.OrderItemDeliveryRequest> itemReqMap = request.getItems().stream()
                    .collect(Collectors.toMap(com.quanlybanhang.dto.request.OrderItemDeliveryRequest::getOrderItemId, i -> i));

            for (OrderItem item : order.getItems()) {
                com.quanlybanhang.dto.request.OrderItemDeliveryRequest itemReq = itemReqMap.get(item.getId());
                if (itemReq != null) {
                    item.setDeliveredQuantity(itemReq.getDeliveredQuantity());
                    BigDecimal conversionFactor = item.getConversionFactor() != null ? item.getConversionFactor() : BigDecimal.ONE;
                    item.setDeliveredBaseQuantity(itemReq.getDeliveredQuantity().multiply(conversionFactor));
                    if (itemReq.getNote() != null) {
                        item.setDeliveryNote(itemReq.getNote().trim());
                    }
                }
            }
        }

        Order saved = orderRepository.save(order);
        if (statusChanged) {
            recordStatusHistory(saved, oldStatus, "DELIVERED", request.getDeliveryNote(), username);
        }
        auditLogService.logAction("ORDER", saved.getOrderCode(), "DELIVERY_UPDATE",
                oldStatus, saved.getStatus(),
                "Cập nhật giao hàng: ngày " + saved.getActualDeliveryDate() + ", ghi chú: " + (saved.getDeliveryNote() != null ? saved.getDeliveryNote() : "Không"),
                username != null ? username : "system");

        return mapToResponse(saved);
    }

    @Override
    @Transactional
    public OrderResponse cancelOrder(Long id, String reason, String username) {
        if (reason == null || reason.trim().isBlank()) {
            throw new IllegalArgumentException("Lý do huỷ đơn hàng không được để trống");
        }
        String trimmedReason = reason.trim();

        Order order = orderRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn hàng với ID: " + id));

        // RLS kiểm tra quyền hạn cho SALES: chỉ huỷ được đơn của mình hoặc đại lý phụ trách
        checkSalesAuthorizationForOrder(order, "huỷ");
        checkReservationWarehouseScope(order);

        String oldStatus = order.getStatus();
        if ("CANCELLED".equalsIgnoreCase(oldStatus)) {
            throw new IllegalArgumentException("Đơn hàng này đã bị huỷ trước đó");
        }

        // Đơn đã xuất kho thì không huỷ được, phải đi đường trả hàng (S4-06)
        if (OrderStatus.isExportedOrLater(oldStatus)) {
            throw new OrderCannotBeCancelledException("Đơn đã xuất kho thì không huỷ được, phải đi đường trả hàng");
        }

        // Tự động nhả tồn kho đang giữ chỗ (S4-06)
        releaseReservedInventory(order);

        order.setStatus("CANCELLED");
        order.setCancelReason(trimmedReason);
        order.setCancelledBy(username != null ? username : "system");
        order.setCancelledAt(LocalDateTime.now());
        if (order.getNote() == null || order.getNote().isBlank()) {
            order.setNote("Huỷ: " + trimmedReason);
        } else {
            order.setNote(order.getNote() + " | Huỷ: " + trimmedReason);
        }

        Order saved = orderRepository.save(order);
        recordStatusHistory(saved, oldStatus, "CANCELLED", "Huỷ đơn hàng. Lý do: " + trimmedReason, username);
        auditLogService.logAction("INVOICE", saved.getOrderCode(), "CANCEL", oldStatus,
                "Huỷ đơn: " + trimmedReason, username != null ? username : "system");

        return mapToResponse(saved);
    }

    @Override
    @Transactional
    public OrderResponse cancelOrder(Long id, String username) {
        return cancelOrder(id, "Huỷ bởi người dùng " + (username != null ? username : "system"), username);
    }

    @Override
    @Transactional
    public OrderResponse updateOrderStatus(Long id, String status, String note, String username) {
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("Trạng thái mới không được để trống");
        }
        Order order = orderRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn hàng với ID: " + id));

        checkWarehouseAuthorizationForOrder(order, "cập nhật trạng thái");

        String oldStatus = order.getStatus();
        String targetStatus = normalizeLifecycleStatus(status);
        if ("DELIVERED".equals(targetStatus)) requireTripConfirmation(id);

        if ("CANCELLED".equalsIgnoreCase(oldStatus)) {
            throw new IllegalArgumentException("Không thể cập nhật trạng thái của đơn hàng đã bị huỷ");
        }
        if ("CANCELLED".equalsIgnoreCase(targetStatus)) {
            throw new IllegalArgumentException("Vui lòng sử dụng chức năng huỷ đơn hàng kèm lý do huỷ");
        }

        if (!Set.of("PICKING", "EXPORTED", "DELIVERED", "CLOSED").contains(targetStatus)) {
            throw new IllegalArgumentException("Trạng thái vòng đời không hợp lệ: " + status
                    + ". Duyệt, từ chối và trả lại phải dùng luồng phê duyệt riêng");
        }

        validateLifecycleTransition(oldStatus, targetStatus);

        // Khi chuyển sang EXPORTED (Đã xuất): trừ tồn kho thực tế và nhả giữ chỗ (S4-06)
        if (("EXPORTED".equals(targetStatus) || "SHIPPED".equals(targetStatus))
                && !OrderStatus.isExportedOrLater(oldStatus)) {
            deductInventoryOnExport(order);
        }

        order.setStatus(targetStatus);
        if (note != null && !note.isBlank()) {
            order.setNote((order.getNote() != null ? order.getNote() + " | " : "") + note.trim());
        }

        Order saved = orderRepository.save(order);
        recordStatusHistory(saved, oldStatus, targetStatus, note, username);
        auditLogService.logAction("INVOICE", saved.getOrderCode(), "UPDATE_STATUS", oldStatus,
                "Chuyển trạng thái sang: " + OrderStatus.getDisplayName(targetStatus) + (note != null ? " (" + note + ")" : ""),
                username != null ? username : "system");

        return mapToResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public OrderTrackingResponse getOrderTracking(Long id, String username) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn hàng với ID: " + id));

        checkSalesAuthorizationForOrder(order, "theo dõi");

        List<OrderStatusHistory> historyEntities = orderStatusHistoryRepository != null
                ? orderStatusHistoryRepository.findByOrderIdOrderByChangedAtAsc(order.getId())
                : Collections.emptyList();

        List<OrderStatusHistoryResponse> historyList = historyEntities.stream()
                .map(h -> OrderStatusHistoryResponse.builder()
                        .id(h.getId())
                        .orderId(h.getOrder().getId())
                        .previousStatus(h.getPreviousStatus())
                        .newStatus(h.getNewStatus())
                        .statusDisplayName(h.getStatusDisplayName())
                        .note(h.getNote())
                        .changedBy(h.getChangedBy())
                        .changedAt(h.getChangedAt())
                        .build())
                .collect(Collectors.toList());

        String currentStatus = order.getStatus();
        boolean isCancelled = "CANCELLED".equalsIgnoreCase(currentStatus);
        boolean isRejected = "REJECTED".equalsIgnoreCase(currentStatus);
        boolean wasApprovalRequired = Boolean.TRUE.equals(order.getRequiresApproval());

        Map<String, OrderStatusHistory> historyByStatus = new HashMap<>();
        for (OrderStatusHistory h : historyEntities) {
            historyByStatus.put(h.getNewStatus(), h);
        }

        List<OrderLifecycleStepResponse> steps = new ArrayList<>();
        OrderStatusHistory cancelledHistory = historyByStatus.get("CANCELLED");
        int cancelledAtStep = cancelledHistory != null ? resolveStepOrder(cancelledHistory.getPreviousStatus()) : 0;
        if (cancelledAtStep <= 0) {
            cancelledAtStep = 1;
        }
        int currentStepOrder = isCancelled ? 8 : resolveStepOrder(currentStatus);

        // Step 1: DRAFT
        steps.add(buildStep(1, "DRAFT", "Nháp", "Đơn hàng đang ở trạng thái nháp",
                isCancelled ? resolveCancelledStepState(1, cancelledAtStep)
                        : resolveStepState(1, currentStepOrder, false, isRejected, false),
                historyByStatus.get("DRAFT"), order.getCreatedAt(), order.getCreatedBy()));

        // Step 2: PENDING_APPROVAL
        boolean skippedApproval = !wasApprovalRequired && !historyByStatus.containsKey("PENDING_APPROVAL");
        steps.add(buildStep(2, "PENDING_APPROVAL", "Chờ duyệt", "Chờ Quản lý kinh doanh duyệt (giá sàn / công nợ)",
                skippedApproval ? "SKIPPED" : (isCancelled ? resolveCancelledStepState(2, cancelledAtStep)
                        : resolveStepState(2, currentStepOrder, false, isRejected, false)),
                historyByStatus.get("PENDING_APPROVAL"), null, null));

        // Step 3: APPROVED
        OrderStatusHistory approvedHist = historyByStatus.get("RECORDED");
        if (approvedHist == null) approvedHist = historyByStatus.get("APPROVED");
        steps.add(buildStep(3, "APPROVED", "Đã duyệt", "Đơn hàng đã được duyệt, sẵn sàng soạn hàng",
                isCancelled ? resolveCancelledStepState(3, cancelledAtStep)
                        : resolveStepState(3, currentStepOrder, false, isRejected, false),
                approvedHist, order.getRecordedAt(), order.getApprovedBy() != null ? order.getApprovedBy() : order.getCreatedBy()));

        // Step 4: PICKING
        OrderStatusHistory pickingHist = historyByStatus.get("PICKING");
        if (pickingHist == null) pickingHist = historyByStatus.get("PROCESSING");
        steps.add(buildStep(4, "PICKING", "Đang soạn hàng", "Kho đang thực hiện soạn hàng và đóng gói",
                isCancelled ? resolveCancelledStepState(4, cancelledAtStep)
                        : resolveStepState(4, currentStepOrder, false, isRejected, false),
                pickingHist, null, null));

        // Step 5: EXPORTED
        OrderStatusHistory exportedHist = historyByStatus.get("EXPORTED");
        if (exportedHist == null) exportedHist = historyByStatus.get("SHIPPED");
        steps.add(buildStep(5, "EXPORTED", "Đã xuất", "Đã xuất kho, đang vận chuyển đến đại lý",
                isCancelled ? resolveCancelledStepState(5, cancelledAtStep)
                        : resolveStepState(5, currentStepOrder, false, isRejected, false),
                exportedHist, null, null));

        // Step 6: DELIVERED
        steps.add(buildStep(6, "DELIVERED", "Đã giao", "Đã giao hàng thành công đến đại lý",
                isCancelled ? resolveCancelledStepState(6, cancelledAtStep)
                        : resolveStepState(6, currentStepOrder, false, isRejected, false),
                historyByStatus.get("DELIVERED"), null, null));

        // Step 7: CLOSED
        OrderStatusHistory closedHist = historyByStatus.get("CLOSED");
        if (closedHist == null) closedHist = historyByStatus.get("COMPLETED");
        steps.add(buildStep(7, "CLOSED", "Đóng", "Đơn hàng hoàn tất toàn bộ chu trình và đóng",
                isCancelled ? resolveCancelledStepState(7, cancelledAtStep)
                        : resolveStepState(7, currentStepOrder, false, isRejected, false),
                closedHist, null, null));

        if (isCancelled) {
            steps.add(buildStep(8, "CANCELLED", "Huỷ", "Đơn hàng bị huỷ trước khi xuất kho; tồn giữ chỗ đã được nhả",
                    "CANCELLED", cancelledHistory, order.getCancelledAt(), order.getCancelledBy()));
        }

        return OrderTrackingResponse.builder()
                .orderId(order.getId())
                .orderCode(order.getOrderCode())
                .customerName(order.getCustomerName())
                .dealerId(order.getDealer() != null ? order.getDealer().getId() : null)
                .dealerName(order.getDealer() != null ? order.getDealer().getName() : null)
                .dealerCode(order.getDealer() != null ? order.getDealer().getCode() : null)
                .warehouseId(order.getWarehouse() != null ? order.getWarehouse().getId() : null)
                .warehouseCode(order.getWarehouse() != null ? order.getWarehouse().getCode() : null)
                .warehouseName(order.getWarehouse() != null ? order.getWarehouse().getName() : null)
                .currentStatus(currentStatus)
                .currentStatusDisplayName(OrderStatus.getDisplayName(currentStatus))
                .currentStep(currentStepOrder)
                .canCancel(OrderStatus.canCancel(currentStatus))
                .isExported(OrderStatus.isExportedOrLater(currentStatus))
                .inventoryReserved(order.getInventoryReserved())
                .reservedAt(order.getReservedAt())
                .reservationExpiresAt(order.getReservationExpiresAt())
                .cancelReason(order.getCancelReason())
                .cancelledBy(order.getCancelledBy())
                .cancelledAt(order.getCancelledAt())
                .expectedDeliveryDate(order.getExpectedDeliveryDate())
                .createdAt(order.getCreatedAt())
                .recordedAt(order.getRecordedAt())
                .totalAmount(order.getTotalAmount())
                .steps(steps)
                .history(historyList)
                .build();
    }

    @Override
    @Transactional
    public OrderResponse approveOrder(Long id, String username) {
        return approveOrderInternal(id, null, username, null);
    }

    @Override
    @Transactional
    public OrderResponse confirmPortalOrder(Long id, String username) {
        return approveOrderInternal(id, null, username, null, true);
    }

    @Transactional
    private OrderResponse approveOrderInternal(Long id, Integer reservationHours, String username, String historyComment) {
        return approveOrderInternal(id, reservationHours, username, historyComment, false);
    }

    private OrderResponse approveOrderInternal(Long id, Integer reservationHours, String username, String historyComment,
                                               boolean confirmation) {
        Order order = orderRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn hàng với ID: " + id));

        if (!"PENDING_APPROVAL".equalsIgnoreCase(order.getStatus())) {
            throw new IllegalArgumentException("Chỉ có thể phê duyệt đơn hàng đang ở trạng thái Chờ duyệt (PENDING_APPROVAL)");
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean isElevated;
        if (confirmation) {
            validatePortalConfirmation(order, username, auth);
            isElevated = false;
        } else {
            isElevated = validateApprovalActor(order, username, auth, true);
        }

        // S5-06: Chỉ giữ chỗ tồn sau khi đã xác thực quyền và phạm vi thao tác.
        reserveInventoryForOrder(order, reservationHours, null);

        order.setStatus("RECORDED");
        order.setApprovedBy(username != null ? username : "system");
        order.setApprovedAt(LocalDateTime.now());
        Order saved = orderRepository.save(order);
        recordStatusHistory(saved, "PENDING_APPROVAL", "RECORDED", confirmation
                ? "Nhân viên phụ trách xác nhận đơn đại lý, đã giữ chỗ tồn kho"
                : "Phê duyệt đơn hàng, đã giữ chỗ tồn kho", username);

        String approvalComment = historyComment == null || historyComment.isBlank()
                ? saved.getApprovalReason()
                : historyComment.trim();
        saveApprovalHistory(saved, "APPROVED", approvalComment, username);

        Dealer dealer = order.getDealer();
        boolean dealerLocked = dealer != null && Boolean.TRUE.equals(dealer.getLocked());
        String roleLabel = isElevated ? "Quản lý kinh doanh duyệt đơn" : "Nhân viên phụ trách xác nhận đơn";
        String auditDetail = roleLabel + " (Giữ chỗ tồn: " + (Boolean.TRUE.equals(saved.getInventoryReserved()) ? "Thành công" : "Không áp dụng") + ")" + (order.getApprovalReason() != null ? " (" + order.getApprovalReason() + ")" : "") +
                (dealerLocked ? " [Cảnh báo: Đại lý '" + dealer.getName() + "' đang bị khoá giao dịch. Lý do: " + dealer.getLockReason() + "]" : "");
        auditLogService.logAction("INVOICE", saved.getOrderCode(), "APPROVE", "PENDING_APPROVAL",
                auditDetail, username != null ? username : "system");
        return mapToResponse(saved);
    }

    @Override
    public int releaseExpiredReservations() {
        LocalDateTime now = LocalDateTime.now();
        List<Long> expiredIds = orderRepository.findExpiredReservationIds(now);
        int count = 0;
        for (Long id : expiredIds) {
            if (reservationTransactions == null) {
                // Unit-test constructors have no Spring transaction manager.
                if (releaseExpiredReservation(id, now)) count++;
            } else {
                var transaction = new org.springframework.transaction.support.TransactionTemplate(reservationTransactions);
                transaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                if (Boolean.TRUE.equals(transaction.execute(status -> releaseExpiredReservation(id, now)))) count++;
            }
        }
        return count;
    }

    private boolean releaseExpiredReservation(Long id, LocalDateTime now) {
            Order order = orderRepository.findByIdForUpdate(id).orElse(null);
            // Another cancellation, dispatch or worker may have completed while waiting for the header lock.
            if (order == null || !Boolean.TRUE.equals(order.getInventoryReserved())
                    || order.getReservationExpiresAt() == null || order.getReservationExpiresAt().isAfter(now)
                    || OrderStatus.isExportedOrLater(order.getStatus())) return false;
            Authentication actor = SecurityContextHolder.getContext().getAuthentication();
            if (actor != null && actor.isAuthenticated() && !hasAuthority(actor, "ROLE_ADMIN")) {
                try { checkReservationWarehouseScope(order); checkSalesAuthorizationForOrder(order, "nhả tồn"); }
                catch (AccessDeniedException denied) { return false; }
            }
            String oldStatus = order.getStatus();
            LocalDateTime expiredAt = order.getReservationExpiresAt();
            releaseReservedInventory(order);
            order.setStatus("EXPIRED");
            String expiryTimeStr = expiredAt.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"));
            String noteMsg = "[Hệ thống tự động nhả tồn]: Đơn hàng quá hạn giữ chỗ lúc " + expiryTimeStr;
            order.setNote((order.getNote() != null && !order.getNote().isBlank())
                    ? order.getNote() + " | " + noteMsg
                    : noteMsg);
            orderRepository.save(order);
            recordStatusHistory(order, oldStatus, "EXPIRED", noteMsg, "system");
            auditLogService.logAction("INVOICE", order.getOrderCode(), "EXPIRE_RESERVATION", oldStatus,
                    "Quá hạn giữ chỗ - tự động nhả tồn kho", "system");
            return true;
    }

    @Override
    @Transactional
    public OrderResponse rejectOrder(Long id, String reason, String username) {
        if (reason == null || reason.trim().isEmpty()) {
            throw new IllegalArgumentException("Vui lòng nhập lý do/ý kiến từ chối đơn hàng");
        }

        Order order = orderRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn hàng với ID: " + id));

        if (!"PENDING_APPROVAL".equalsIgnoreCase(order.getStatus())) {
            throw new IllegalArgumentException("Chỉ có thể từ chối đơn hàng đang ở trạng thái Chờ duyệt (PENDING_APPROVAL)");
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        validateApprovalActor(order, username, auth, false);

        releaseReservedInventory(order);
        String cleanReason = reason.trim();
        order.setStatus("REJECTED");
        order.setApprovedBy(username != null ? username : "system");
        order.setApprovedAt(LocalDateTime.now());
        order.setNote((order.getNote() != null && !order.getNote().isBlank() ? order.getNote() + " | " : "") + "Lý do từ chối: " + cleanReason);

        Order saved = orderRepository.save(order);
        recordStatusHistory(saved, "PENDING_APPROVAL", "REJECTED", "Từ chối duyệt: " + (reason != null ? reason : ""), username);

        // Lưu lịch sử duyệt bất biến (S4-05)
        saveApprovalHistory(saved, "REJECTED", cleanReason, username);

        auditLogService.logAction("INVOICE", saved.getOrderCode(), "REJECT", "PENDING_APPROVAL",
                "Từ chối đơn: " + cleanReason, username != null ? username : "system");
        return mapToResponse(saved);
    }

    private void reserveInventoryForOrder(Order order, Integer reservationHours, LocalDateTime explicitExpiry) {
        if (inventoryRepository == null) return; // Legacy Java-only constructors; Spring always injects this dependency.
        if (order == null || order.getWarehouse() == null || order.getItems() == null || order.getItems().isEmpty())
            throw new IllegalArgumentException("Đơn hàng cần kho phục vụ và mặt hàng hợp lệ trước khi giữ chỗ");

        if (Boolean.TRUE.equals(order.getInventoryReserved())) {
            return;
        }

        Warehouse warehouse = order.getWarehouse();
        if (!Boolean.TRUE.equals(warehouse.getActive())) throw new IllegalArgumentException("Kho phục vụ đã ngừng hoạt động");
        List<OrderItem> items = order.getItems();

        Map<Long, BigDecimal> requestedBaseQtyByProduct = new HashMap<>();
        for (OrderItem item : items) {
            if (item == null || item.getProduct() == null || item.getProduct().getId() == null || item.getBaseQuantity() == null)
                throw new IllegalArgumentException("Mặt hàng thiếu sản phẩm hoặc số lượng cơ sở để giữ chỗ");
            if (item.getBaseQuantity().signum() <= 0) throw new IllegalArgumentException("Số lượng giữ chỗ phải lớn hơn 0");
            requestedBaseQtyByProduct.merge(item.getProduct().getId(), item.getBaseQuantity(), BigDecimal::add);
        }

        if (requestedBaseQtyByProduct.isEmpty()) {
            throw new IllegalArgumentException("Đơn hàng không có số lượng hợp lệ để giữ chỗ");
        }

        List<Long> sortedProductIds = requestedBaseQtyByProduct.keySet().stream().sorted().toList();
        List<WarehouseInventory> inventories = inventoryRepository
                .findByWarehouseIdAndProductIdInForUpdate(warehouse.getId(), sortedProductIds);
        Map<Long, WarehouseInventory> inventoryMap = inventories.stream()
                .collect(Collectors.toMap(inv -> inv.getProduct().getId(), inv -> inv));

        for (Long pId : sortedProductIds) {
            BigDecimal totalRequestedBase = requestedBaseQtyByProduct.get(pId);
            WarehouseInventory inv = inventoryMap.get(pId);

            BigDecimal physical = (inv != null && inv.getBaseQuantity() != null) ? inv.getBaseQuantity() : BigDecimal.ZERO;
            BigDecimal reserved = (inv != null && inv.getReservedQuantity() != null) ? inv.getReservedQuantity() : BigDecimal.ZERO;
            BigDecimal available = physical.subtract(reserved).max(BigDecimal.ZERO);

            if (totalRequestedBase.compareTo(available) > 0) {
                OrderItem repItem = items.stream()
                        .filter(it -> it.getProduct() != null && pId.equals(it.getProduct().getId()))
                        .findFirst()
                        .orElse(null);
                Product product = repItem != null ? repItem.getProduct() : (inv != null ? inv.getProduct() : null);
                String prodName = product != null ? product.getName() : ("ID " + pId);
                String prodCode = product != null ? product.getCode() : String.valueOf(pId);
                String baseUnit = product != null ? product.getUnit() : "đơn vị";
                String itemUnit = repItem != null && repItem.getUnit() != null ? repItem.getUnit() : baseUnit;
                BigDecimal factor = repItem != null && repItem.getConversionFactor() != null && repItem.getConversionFactor().compareTo(BigDecimal.ZERO) > 0
                        ? repItem.getConversionFactor()
                        : BigDecimal.ONE;
                BigDecimal maxAllowedInUnit = available.divide(factor, 0, RoundingMode.FLOOR);

                if (available.compareTo(BigDecimal.ZERO) <= 0) {
                    throw new IllegalArgumentException(String.format(
                            "Mặt hàng '%s' (mã: %s) đã hết tồn khả dụng trong kho phục vụ '%s'. Tồn khả dụng hiện tại: 0 %s. Số lượng tối đa có thể đặt là 0 %s.",
                            prodName, prodCode, warehouse.getName(), baseUnit, itemUnit
                    ));
                } else {
                    BigDecimal reqQtyInUnit = repItem != null && repItem.getQuantity() != null ? repItem.getQuantity() : totalRequestedBase;
                    throw new IllegalArgumentException(String.format(
                            "Vượt tồn khả dụng cho sản phẩm '%s' (mã: %s) tại kho '%s'. Tồn khả dụng hiện tại: %s %s. Yêu cầu đặt: %s %s (%s %s). Số lượng tối đa có thể đặt là %s %s.",
                            prodName, prodCode, warehouse.getName(),
                            available.stripTrailingZeros().toPlainString(), baseUnit,
                            reqQtyInUnit.stripTrailingZeros().toPlainString(), itemUnit,
                            totalRequestedBase.stripTrailingZeros().toPlainString(), baseUnit,
                            maxAllowedInUnit.stripTrailingZeros().toPlainString(), itemUnit
                    ));
                }
            }
        }

        // The rows above are pessimistically locked, ordered by SKU. Mutate managed entities so
        // reserved stock and @Version remain coherent with other warehouse transactions.
        for (Long pId : sortedProductIds) {
            BigDecimal reqBase = requestedBaseQtyByProduct.get(pId);
            WarehouseInventory current = inventoryMap.get(pId);
            if (current == null) throw new IllegalArgumentException("Không có sổ tồn cho sản phẩm " + pId);
            current.addReservedQuantity(reqBase);
            inventoryRepository.save(current);
        }

        order.setInventoryReserved(true);
        order.setReservedAt(LocalDateTime.now());
        order.setReservationExpiresAt(calculateReservationExpiry(order, reservationHours, explicitExpiry));
    }

    private LocalDateTime calculateReservationExpiry(Order order, Integer reservationHours, LocalDateTime explicitExpiry) {
        if (explicitExpiry != null) {
            if (!explicitExpiry.isAfter(LocalDateTime.now())) throw new IllegalArgumentException("Hạn giữ chỗ phải ở tương lai");
            return explicitExpiry;
        }
        if (reservationHours != null && reservationHours <= 0) throw new IllegalArgumentException("Số giờ giữ chỗ phải lớn hơn 0");
        if (reservationHours != null && reservationHours > 0) {
            return LocalDateTime.now().plusHours(reservationHours);
        }
        if (order != null && order.getExpectedDeliveryDate() != null) {
            LocalDateTime deliveryEnd = order.getExpectedDeliveryDate().atTime(23, 59, 59);
            if (deliveryEnd.isAfter(LocalDateTime.now())) {
                return deliveryEnd;
            }
        }
        return LocalDateTime.now().plusHours(48);
    }

    private void releaseReservedInventory(Order order) {
        if (Boolean.TRUE.equals(order.getInventoryReserved()) && order.getWarehouse() != null && inventoryRepository != null && order.getItems() != null && !order.getItems().isEmpty()) {
            Map<Long, BigDecimal> baseQtyByProduct = new HashMap<>();
            for (OrderItem item : order.getItems()) {
                if (item.getProduct() != null && item.getBaseQuantity() != null) {
                    baseQtyByProduct.merge(item.getProduct().getId(), item.getBaseQuantity(), BigDecimal::add);
                }
            }

            if (!baseQtyByProduct.isEmpty()) {
                List<Long> sortedProductIds = baseQtyByProduct.keySet().stream().sorted().toList();
                List<WarehouseInventory> inventories = inventoryRepository.findByWarehouseIdAndProductIdInForUpdate(order.getWarehouse().getId(), sortedProductIds);
                Map<Long, WarehouseInventory> inventoryMap = inventories.stream()
                        .collect(Collectors.toMap(inv -> inv.getProduct().getId(), inv -> inv));

                for (Map.Entry<Long, BigDecimal> entry : baseQtyByProduct.entrySet()) {
                    WarehouseInventory inv = inventoryMap.get(entry.getKey());
                    if (inv == null || inv.getReservedQuantity() == null || inv.getReservedQuantity().compareTo(entry.getValue()) < 0)
                        throw new IllegalStateException("Số tồn giữ chỗ không khớp đơn hàng. Cần đối soát trước khi nhả tồn.");
                    inv.subtractReservedQuantity(entry.getValue());
                    inventoryRepository.save(inv);
                }
            }
            order.setInventoryReserved(false);
            order.setReservationExpiresAt(null);
        }
    }

    private Product resolveProduct(OrderItemRequest itemReq) {
        Product product;
        if (itemReq.getProductId() != null) {
            product = productRepository.findById(itemReq.getProductId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy sản phẩm với ID: " + itemReq.getProductId()));
        } else if (itemReq.getProductCode() != null && !itemReq.getProductCode().isBlank()) {
            product = productRepository.findByCode(itemReq.getProductCode().trim().toUpperCase())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy sản phẩm với mã: " + itemReq.getProductCode()));
        } else {
            throw new IllegalArgumentException("Cần cung cấp productId hoặc productCode");
        }
        if (Boolean.FALSE.equals(product.getActive())) throw new IllegalArgumentException("Sản phẩm đã ngừng kinh doanh: " + product.getCode());
        return product;
    }

    private OrderResponse mapToResponse(Order order) {
        Warehouse orderWh = order.getWarehouse() != null
                ? order.getWarehouse()
                : (order.getDealer() != null ? order.getDealer().getWarehouse() : null);

        List<OrderItemResponse> items = order.getItems() != null
                ? order.getItems().stream().map(it -> mapToItemResponse(it, orderWh)).collect(Collectors.toList())
                : Collections.emptyList();

        Dealer dealer = order.getDealer();
        boolean dealerLocked = dealer != null && Boolean.TRUE.equals(dealer.getLocked());
        String dealerLockWarning = dealerLocked
                ? String.format("Cảnh báo: Đại lý '%s' (%s) đang bị khoá giao dịch. Lý do: %s. Đơn hàng đang dở dang vẫn được xử lý nhưng cần theo dõi thu hồi công nợ.",
                        dealer.getName(), dealer.getCode(), dealer.getLockReason() != null ? dealer.getLockReason() : "Không nêu rõ")
                : null;

        boolean exceedsDebt = order.getApprovalReason() != null && order.getApprovalReason().contains("Vượt hạn mức công nợ");
        BigDecimal currentDebt = null;
        BigDecimal debtLimit = null;
        BigDecimal remainingLimit = null;

        if (dealer != null) {
            debtLimit = dealer.getDebtLimit();
            if (dealerDebtService != null) {
                try {
                    DealerDebtResponse dInfo = dealerDebtService.getDealerDebtInfo(dealer);
                    currentDebt = dInfo.getCurrentDebt();
                    remainingLimit = dInfo.getRemainingLimit();
                } catch (Exception ignored) {
                }
            }
        }

        String warning = dealerLockWarning;
        if (warning == null && Boolean.TRUE.equals(order.getRequiresApproval())) {
            warning = order.getApprovalReason();
        }

        String dealerCode = dealer != null ? dealer.getCode() : null;
        String dealerName = dealer != null ? dealer.getName() : null;
        User assignedUser = dealer != null ? dealer.getAssignedUser() : null;
        Long salesUserId = assignedUser != null ? assignedUser.getId() : null;
        String salesUserName = assignedUser != null ? assignedUser.getUsername() : null;
        String salesUserFullName = assignedUser != null ? assignedUser.getFullName() : null;
        Territory territory = dealer != null ? dealer.getTerritory() : null;
        Long territoryId = territory != null ? territory.getId() : null;
        String territoryCode = territory != null ? territory.getCode() : null;
        String territoryName = territory != null ? territory.getName() : null;
        // Danh sách lý do cần duyệt và mức vi phạm (S4-05)
        List<String> approvalReasonsList = new ArrayList<>();
        List<OrderViolationResponse> violations = new ArrayList<>();
        BigDecimal debtExceeded = null;
        BigDecimal priceGap = BigDecimal.ZERO;

        if (order.getApprovalReason() != null && !order.getApprovalReason().isBlank()) {
            String reasonText = order.getApprovalReason();
            if (reasonText.contains("hạn mức") || reasonText.contains("công nợ") || exceedsDebt) {
                approvalReasonsList.add("OVER_DEBT_LIMIT");
                if (currentDebt != null && debtLimit != null) {
                    BigDecimal projected = currentDebt.add(order.getTotalAmount() != null ? order.getTotalAmount() : BigDecimal.ZERO);
                    if (projected.compareTo(debtLimit) > 0) {
                        debtExceeded = projected.subtract(debtLimit);
                    }
                }
                violations.add(OrderViolationResponse.builder()
                        .type("OVER_DEBT_LIMIT")
                        .title("Vượt hạn mức công nợ")
                        .description(reasonText)
                        .displayValue(debtExceeded != null ? "+" + formatMoney(debtExceeded) : "Vượt hạn mức")
                        .value(debtExceeded)
                        .build());
            }

            if (reasonText.contains("giá sàn")) {
                approvalReasonsList.add("BELOW_FLOOR_PRICE");
                BigDecimal totalGap = BigDecimal.ZERO;
                if (order.getItems() != null) {
                    for (OrderItem it : order.getItems()) {
                        if (Boolean.TRUE.equals(it.getBelowFloorPrice()) && it.getFloorPrice() != null && it.getPrice() != null) {
                            BigDecimal diff = it.getFloorPrice().subtract(it.getPrice());
                            if (diff.compareTo(BigDecimal.ZERO) > 0) {
                                BigDecimal lineGap = diff.multiply(it.getQuantity() != null ? it.getQuantity() : BigDecimal.ONE);
                                totalGap = totalGap.add(lineGap);
                            }
                        }
                    }
                }
                if (totalGap.compareTo(BigDecimal.ZERO) > 0) {
                    priceGap = totalGap;
                }
                violations.add(OrderViolationResponse.builder()
                        .type("BELOW_FLOOR_PRICE")
                        .title("Bán dưới giá sàn")
                        .description(reasonText)
                        .displayValue(priceGap != null && priceGap.compareTo(BigDecimal.ZERO) > 0 ? "-" + formatMoney(priceGap) : "Dưới giá sàn")
                        .value(priceGap)
                        .build());
            }
        }

        // Lấy lịch sử duyệt đơn hàng (S4-05) - bất biến, không sửa xoá
        List<OrderApprovalHistoryResponse> historyResponses = new ArrayList<>();
        if (orderApprovalHistoryRepository != null) {
            try {
                List<OrderApprovalHistory> historyEntities = orderApprovalHistoryRepository.findByOrderIdOrderByCreatedAtAsc(order.getId());
                for (OrderApprovalHistory h : historyEntities) {
                    historyResponses.add(OrderApprovalHistoryResponse.builder()
                            .id(h.getId())
                            .orderId(order.getId())
                            .orderCode(order.getOrderCode())
                            .action(h.getAction())
                            .comment(h.getComment())
                            .performedBy(h.getPerformedBy())
                            .username(h.getPerformedBy())
                            .createdAt(h.getCreatedAt())
                            .timestamp(h.getCreatedAt())
                            .build());
                }
            } catch (Exception ignored) {
            }
        }
        if (historyResponses.isEmpty() && (Boolean.TRUE.equals(order.getRequiresApproval()) || order.getApprovedAt() != null)) {
            historyResponses.add(OrderApprovalHistoryResponse.builder()
                    .orderId(order.getId())
                    .orderCode(order.getOrderCode())
                    .action("PENDING_APPROVAL")
                    .comment(order.getApprovalReason())
                    .performedBy(order.getCreatedBy() != null ? order.getCreatedBy() : "system")
                    .username(order.getCreatedBy() != null ? order.getCreatedBy() : "system")
                    .createdAt(order.getCreatedAt())
                    .timestamp(order.getCreatedAt())
                    .build());
            if (order.getApprovedAt() != null) {
                String action = "REJECTED".equalsIgnoreCase(order.getStatus()) ? "REJECTED"
                        : "RETURNED".equalsIgnoreCase(order.getStatus()) ? "RETURNED" : "APPROVED";
                historyResponses.add(OrderApprovalHistoryResponse.builder()
                        .orderId(order.getId())
                        .orderCode(order.getOrderCode())
                        .action(action)
                        .comment(order.getNote())
                        .performedBy(order.getApprovedBy() != null ? order.getApprovedBy() : "Quản lý kinh doanh")
                        .username(order.getApprovedBy() != null ? order.getApprovedBy() : "Quản lý kinh doanh")
                        .createdAt(order.getApprovedAt())
                        .timestamp(order.getApprovedAt())
                        .build());
            }
        }
        boolean hasShortage = items.stream().anyMatch(i -> Boolean.TRUE.equals(i.getIsShortage()));

        return OrderResponse.builder()
                .id(order.getId())
                .canConfirmPortalOrder(canConfirmPortalOrder(order))
                .nextLifecycleStatus(nextLifecycleStatus(order))
                .orderCode(order.getOrderCode())
                .customerName(order.getCustomerName())
                .customerGroup(order.getCustomerGroup())
                .dealerId(dealer != null ? dealer.getId() : null)
                .dealerCode(dealerCode)
                .dealerName(dealerName)
                .salesUserId(salesUserId)
                .salesUserName(salesUserName)
                .salesUserFullName(salesUserFullName)
                .territoryId(territoryId)
                .territoryCode(territoryCode)
                .territoryName(territoryName)
                .deliveryPointId(order.getDeliveryPoint() != null ? order.getDeliveryPoint().getId() : null)
                .warehouseId(order.getWarehouse() != null ? order.getWarehouse().getId() : null)
                .warehouseCode(order.getWarehouse() != null ? order.getWarehouse().getCode() : null)
                .warehouseName(order.getWarehouse() != null ? order.getWarehouse().getName() : null)
                .expectedDeliveryDate(order.getExpectedDeliveryDate())
                .actualDeliveryDate(order.getActualDeliveryDate())
                .canUpdateDelivery(canUpdateDelivery(order))
                .deliveryNote(order.getDeliveryNote())
                .hasShortage(hasShortage)
                .totalLineItems(items.size())
                .priceListId(order.getPriceList() != null ? order.getPriceList().getId() : null)
                .priceListCode(order.getPriceList() != null ? order.getPriceList().getCode() : null)
                .warehouseId(order.getWarehouse() != null ? order.getWarehouse().getId() : null)
                .warehouseCode(order.getWarehouse() != null ? order.getWarehouse().getCode() : null)
                .warehouseName(order.getWarehouse() != null ? order.getWarehouse().getName() : null)
                .status(order.getStatus())
                .statusDisplayName(OrderStatus.getDisplayName(order.getStatus()))
                .canCancel(OrderStatus.canCancel(order.getStatus()))
                .isExported(OrderStatus.isExportedOrLater(order.getStatus()))
                .inventoryReserved(order.getInventoryReserved())
                .cancelReason(order.getCancelReason())
                .cancelledBy(order.getCancelledBy())
                .cancelledAt(order.getCancelledAt())
                .reservedAt(order.getReservedAt())
                .reservationExpiresAt(order.getReservationExpiresAt())
                .requiresApproval(order.getRequiresApproval())
                .approvalReason(order.getApprovalReason())
                .approvalReasons(approvalReasonsList)
                .violations(violations)
                .debtExceeded(debtExceeded)
                .priceGap(priceGap)
                .approvalHistory(historyResponses)
                .approvedBy(order.getApprovedBy())
                .approvedAt(order.getApprovedAt())
                .subtotalAmount(order.getSubtotalAmount() != null ? order.getSubtotalAmount() : order.getTotalAmount())
                .discountAmount(order.getDiscountAmount() != null ? order.getDiscountAmount() : BigDecimal.ZERO)
                .totalAmount(order.getTotalAmount())
                .grandTotal(order.getTotalAmount())
                .note(order.getNote())
                .createdBy(order.getCreatedBy())
                .createdAt(order.getCreatedAt())
                .recordedAt(order.getRecordedAt())
                .items(items)
                .dealerLocked(dealerLocked)
                .dealerLockWarning(dealerLockWarning)
                .warning(warning)
                .exceedsDebtLimit(exceedsDebt)
                .currentDebt(currentDebt)
                .debtLimit(debtLimit)
                .remainingDebtLimit(remainingLimit)
                .cancelReason(order.getCancelReason())
                .cancelledBy(order.getCancelledBy())
                .cancelledAt(order.getCancelledAt())
                .build();
    }

    private String formatMoney(BigDecimal amount) {
        if (amount == null) return "0 đ";
        return new java.text.DecimalFormat("#,### đ").format(amount);
    }

    private OrderItemResponse mapToItemResponse(OrderItem item) {
        return mapToItemResponse(item, item.getOrder() != null ? item.getOrder().getWarehouse() : null);
    }

    private OrderItemResponse mapToItemResponse(OrderItem item, Warehouse warehouse) {
        Long locationId = null;
        String locationCode = null;
        String locationName = null;
        String locationType = null;
        String zoneName = null;
        String shelfName = null;
        String locationDisplay = null;

        if (locationProductRepository != null && warehouse != null && item.getProduct() != null) {
            List<WarehouseLocationProduct> mappings =
                    locationProductRepository.findByWarehouseIdAndProductId(warehouse.getId(), item.getProduct().getId());
            if (!mappings.isEmpty()) {
                WarehouseLocation loc = mappings.get(0).getLocation();
                locationId = loc.getId();
                locationCode = loc.getCode();
                locationName = loc.getName();
                locationType = loc.getLocationType() != null ? loc.getLocationType().name() : null;
                if (loc.getParent() != null) {
                    zoneName = loc.getParent().getName();
                    shelfName = loc.getName();
                    locationDisplay = loc.getParent().getName() + " > " + loc.getName();
                } else if (loc.getLocationType() == LocationType.ZONE) {
                    zoneName = loc.getName();
                    locationDisplay = loc.getName();
                } else {
                    shelfName = loc.getName();
                    locationDisplay = loc.getName();
                }
            }
        }

        BigDecimal deliveredQty = item.getDeliveredQuantity();
        BigDecimal shortageQty = null;
        Boolean isShortage = false;
        if (deliveredQty != null) {
            if (item.getQuantity() != null && deliveredQty.compareTo(item.getQuantity()) < 0) {
                shortageQty = item.getQuantity().subtract(deliveredQty);
                isShortage = true;
            } else {
                shortageQty = BigDecimal.ZERO;
                isShortage = false;
            }
        }

        return OrderItemResponse.builder()
                .id(item.getId())
                .productId(item.getProduct() != null ? item.getProduct().getId() : null)
                .productCode(item.getProduct() != null ? item.getProduct().getCode() : null)
                .productName(item.getProduct() != null ? item.getProduct().getName() : null)
                .unit(item.getUnit())
                .quantity(item.getQuantity())
                .conversionFactor(item.getConversionFactor())
                .baseUnit(item.getBaseUnit())
                .baseQuantity(item.getBaseQuantity())
                .deliveredQuantity(deliveredQty)
                .deliveredBaseQuantity(item.getDeliveredBaseQuantity())
                .shortageQuantity(shortageQty)
                .isShortage(isShortage)
                .deliveryNote(item.getDeliveryNote())
                .price(item.getPrice())
                .unitPrice(item.getPrice())
                .floorPrice(item.getFloorPrice())
                .belowFloorPrice(item.getBelowFloorPrice())
                .subtotalAmount(item.getSubtotalAmount() != null ? item.getSubtotalAmount() : item.getTotalAmount())
                .discountAmount(item.getDiscountAmount() != null ? item.getDiscountAmount() : BigDecimal.ZERO)
                .discountPercent(item.getDiscountPercent())
                .appliedPolicyId(item.getAppliedPolicyId())
                .appliedPolicyCode(item.getAppliedPolicyCode())
                .appliedPolicyName(item.getAppliedPolicyName())
                .totalAmount(item.getTotalAmount())
                .lineTotal(item.getTotalAmount())
                .note(item.getNote())
                .locationId(locationId)
                .locationCode(locationCode)
                .locationName(locationName)
                .locationType(locationType)
                .zoneName(zoneName)
                .shelfName(shelfName)
                .locationDisplay(locationDisplay)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public OrderInventoryCheckResponse checkInventory(OrderInventoryCheckRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Dữ liệu kiểm tra tồn kho không được để trống");
        }
        if (request.getProductId() == null) {
            throw new IllegalArgumentException("ID sản phẩm không được để trống");
        }
        Product product = productRepository.findById(request.getProductId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy sản phẩm với ID: " + request.getProductId()));

        Dealer dealer = request.getDealerId() == null ? null : dealerRepository.findById(request.getDealerId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đại lý với ID: " + request.getDealerId()));
        Warehouse warehouse = resolveServingWarehouse(dealer, request.getWarehouseId(), true);

        String inputUnit = request.getUnit();
        if (inputUnit == null || inputUnit.isBlank()) {
            inputUnit = product.getUnit();
        } else {
            inputUnit = inputUnit.trim();
        }

        BigDecimal qty = request.getQuantity() != null ? request.getQuantity() : BigDecimal.ZERO;
        BigDecimal factor = unitConversionService.getConversionFactor(product, inputUnit);
        BigDecimal baseQty = qty.multiply(factor);

        WarehouseInventory inv = inventoryRepository != null
                ? inventoryRepository.findByWarehouseIdAndProductId(warehouse.getId(), product.getId()).orElse(null)
                : null;

        BigDecimal physical = (inv != null && inv.getBaseQuantity() != null) ? inv.getBaseQuantity() : BigDecimal.ZERO;
        BigDecimal reserved = (inv != null && inv.getReservedQuantity() != null) ? inv.getReservedQuantity() : BigDecimal.ZERO;
        BigDecimal available = physical.subtract(reserved).max(BigDecimal.ZERO);

        BigDecimal effectiveFactor = (factor != null && factor.compareTo(BigDecimal.ZERO) > 0) ? factor : BigDecimal.ONE;
        BigDecimal availableInUnit = available.divide(effectiveFactor, 2, RoundingMode.HALF_UP);
        BigDecimal maxAllowedInUnit = available.divide(effectiveFactor, 0, RoundingMode.FLOOR);

        boolean valid = baseQty.compareTo(available) <= 0;
        String message;
        if (valid) {
            message = String.format("Còn đủ tồn khả dụng trong kho '%s'. Tồn khả dụng: %s %s (%s %s).",
                    warehouse.getName(),
                    availableInUnit.stripTrailingZeros().toPlainString(), inputUnit,
                    available.stripTrailingZeros().toPlainString(), product.getUnit());
        } else {
            if (available.compareTo(BigDecimal.ZERO) <= 0) {
                message = String.format("Mặt hàng '%s' (mã: %s) đã hết tồn khả dụng tại kho phục vụ '%s'. Tối đa có thể đặt: 0 %s.",
                        product.getName(), product.getCode(), warehouse.getName(), inputUnit);
            } else {
                message = String.format("Vượt tồn khả dụng cho sản phẩm '%s' (mã: %s) tại kho '%s'. Tồn khả dụng hiện tại: %s %s. Số lượng tối đa có thể đặt là %s %s.",
                        product.getName(), product.getCode(), warehouse.getName(),
                        available.stripTrailingZeros().toPlainString(), product.getUnit(),
                        maxAllowedInUnit.stripTrailingZeros().toPlainString(), inputUnit);
            }
        }

        return OrderInventoryCheckResponse.builder()
                .warehouseId(warehouse.getId())
                .warehouseCode(warehouse.getCode())
                .warehouseName(warehouse.getName())
                .productId(product.getId())
                .productCode(product.getCode())
                .productName(product.getName())
                .unit(inputUnit)
                .quantity(qty)
                .conversionFactor(factor)
                .baseUnit(product.getUnit())
                .baseQuantity(baseQty)
                .physicalQuantity(physical)
                .reservedQuantity(reserved)
                .availableQuantity(available)
                .availableInUnit(availableInUnit)
                .maxAllowedInUnit(maxAllowedInUnit)
                .valid(valid)
                .message(message)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public OrderLineItemPricingResponse calculateLineItemPricing(OrderLineItemPricingRequest request) {
        if (request.getProductId() == null && (request.getProductCode() == null || request.getProductCode().isBlank())) {
            throw new IllegalArgumentException("Cần cung cấp productId hoặc productCode");
        }

        Dealer dealer = null;
        CustomerGroup customerGroup = request.getCustomerGroup();

        if (request.getDealerId() != null) {
            dealer = dealerRepository.findById(request.getDealerId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đại lý với ID: " + request.getDealerId()));
            if (Boolean.TRUE.equals(dealer.getLocked())) {
                throw new DealerLockedException(dealer.getCode(), dealer.getName(), dealer.getLockReason());
            }
            if (Boolean.FALSE.equals(dealer.getActive())) {
                throw new IllegalArgumentException(String.format("Đại lý '%s' (%s) đã ngừng giao dịch.",
                        dealer.getName(), dealer.getCode()));
            }
            customerGroup = dealer.getCustomerGroup();
            Authentication actor = SecurityContextHolder.getContext().getAuthentication();
            validateSalesAccessForDealer(dealer, actor != null ? actor.getName() : null);
        }

        if (customerGroup == null) {
            throw new IllegalArgumentException("Không xác định được nhóm khách hàng của đại lý để tra cứu bảng giá");
        }

        LocalDate orderDate = request.getOrderDate() != null ? request.getOrderDate() : LocalDate.now();

        // 1. Tra cứu bảng giá hiệu lực của nhóm khách hàng mà đại lý thuộc về
        List<PriceList> effectivePriceLists = priceListRepository.findEffectivePriceLists(customerGroup, orderDate);
        if (effectivePriceLists.isEmpty()) {
            throw new IllegalArgumentException(String.format("Không tìm thấy bảng giá hiệu lực cho nhóm khách hàng '%s'. Không thể thêm dòng hàng.",
                    customerGroup.getDisplayName()));
        }
        PriceList priceList = effectivePriceLists.get(0);

        // 2. Tra cứu sản phẩm
        Product product;
        if (request.getProductId() != null) {
            product = productRepository.findById(request.getProductId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy sản phẩm với ID: " + request.getProductId()));
        } else {
            product = productRepository.findByCode(request.getProductCode().trim().toUpperCase())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy sản phẩm với mã: " + request.getProductCode()));
        }

        if (Boolean.FALSE.equals(product.getActive())) {
            throw new IllegalArgumentException("Sản phẩm đã ngừng kinh doanh: " + product.getName());
        }

        // 3. Kiểm tra SKU có trong bảng giá hiệu lực hay không - nếu không có thì chặn thêm dòng hàng kèm thông báo rõ lý do
        PriceListItem priceListItem = null;
        if (priceList.getItems() != null) {
            for (PriceListItem pli : priceList.getItems()) {
                if (pli.getProduct() != null && pli.getProduct().getId().equals(product.getId())) {
                    priceListItem = pli;
                    break;
                }
            }
        }
        if (priceListItem == null) {
            throw new IllegalArgumentException(String.format("Sản phẩm '%s' (mã: %s) chưa có trong bảng giá hiệu lực của nhóm khách hàng '%s'. Không thể thêm dòng hàng.",
                    product.getName(), product.getCode(), customerGroup.getDisplayName()));
        }

        // 4. Quy đổi đơn vị và giá
        String unit = request.getUnit();
        if (unit == null || unit.isBlank()) {
            unit = product.getUnit();
        } else {
            unit = unit.trim();
        }

        BigDecimal factor = unitConversionService.getConversionFactor(product, unit);
        BigDecimal qty = request.getQuantity() != null && request.getQuantity().compareTo(BigDecimal.ZERO) > 0
                ? request.getQuantity() : BigDecimal.ONE;
        BigDecimal baseQty = qty.multiply(factor);

        BigDecimal priceListSellingPrice = priceListItem.getSellingPrice().multiply(factor);
        BigDecimal floorPrice = priceListItem.getFloorPrice().multiply(factor);

        boolean isManualPrice = request.getManualPrice() != null;
        BigDecimal appliedPrice = isManualPrice ? request.getManualPrice() : priceListSellingPrice;

        boolean isBelowFloor = appliedPrice.compareTo(floorPrice) < 0;
        String approvalReason = isBelowFloor
                ? String.format("Sản phẩm '%s' bán giá %s < giá sàn %s (%s)",
                        product.getName(), appliedPrice.toPlainString(), floorPrice.toPlainString(), unit)
                : null;

        BigDecimal subtotal = appliedPrice.multiply(qty);

        // 5. Chiết khấu theo sản lượng tính lại tự động mỗi khi số lượng thay đổi
        BigDecimal discountAmount = BigDecimal.ZERO;
        BigDecimal discountPercent = null;
        Long appliedPolicyId = null;
        String appliedPolicyCode = null;
        String appliedPolicyName = null;
        String discountReason = null;

        if (volumeDiscountPolicyService != null) {
            VolumeDiscountPolicyService.OrderItemDiscountInput discountInput = VolumeDiscountPolicyService.OrderItemDiscountInput.builder()
                    .index(0)
                    .product(product)
                    .unit(unit)
                    .quantity(qty)
                    .conversionFactor(factor)
                    .baseQuantity(baseQty)
                    .baseUnit(product.getUnit())
                    .price(appliedPrice)
                    .subtotalAmount(subtotal)
                    .build();

            VolumeDiscountPolicyService.OrderDiscountSummary summary = volumeDiscountPolicyService.calculateOrderDiscount(
                    List.of(discountInput), customerGroup, orderDate);

            if (summary != null && summary.getItemResults() != null) {
                VolumeDiscountPolicyService.ItemDiscountResult res = summary.getItemResults().get(0);
                if (res != null) {
                    discountAmount = res.getDiscountAmount() != null ? res.getDiscountAmount() : BigDecimal.ZERO;
                    discountPercent = res.getDiscountPercent();
                    appliedPolicyId = res.getAppliedPolicyId();
                    appliedPolicyCode = res.getAppliedPolicyCode();
                    appliedPolicyName = res.getAppliedPolicyName();
                    discountReason = res.getBestPolicyReason();
                }
            }
        }

        BigDecimal totalAmount = subtotal.subtract(discountAmount).max(BigDecimal.ZERO);

        return OrderLineItemPricingResponse.builder()
                .productId(product.getId())
                .productCode(product.getCode())
                .productName(product.getName())
                .categoryId(product.getCategory() != null ? product.getCategory().getId() : null)
                .categoryName(product.getCategory() != null ? product.getCategory().getName() : null)
                .dealerId(dealer != null ? dealer.getId() : null)
                .dealerName(dealer != null ? dealer.getName() : null)
                .customerGroup(customerGroup)
                .customerGroupName(customerGroup.getDisplayName())
                .priceListId(priceList.getId())
                .priceListCode(priceList.getCode())
                .priceListName(priceList.getName())
                .unit(unit)
                .conversionFactor(factor)
                .baseUnit(product.getUnit())
                .quantity(qty)
                .baseQuantity(baseQty)
                .priceListSellingPrice(priceListSellingPrice)
                .floorPrice(floorPrice)
                .unitPrice(appliedPrice)
                .isManualPrice(isManualPrice)
                .belowFloorPrice(isBelowFloor)
                .requiresApproval(isBelowFloor)
                .approvalReason(approvalReason)
                .subtotalAmount(subtotal)
                .discountAmount(discountAmount)
                .discountPercent(discountPercent)
                .appliedPolicyId(appliedPolicyId)
                .appliedPolicyCode(appliedPolicyCode)
                .appliedPolicyName(appliedPolicyName)
                .discountReason(discountReason)
                .totalAmount(totalAmount)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public OrderCalculateResponse calculateOrder(OrderCalculateRequest request) {
        if (request.getItems() == null || request.getItems().isEmpty()) {
            throw new IllegalArgumentException("Đơn hàng phải có ít nhất 1 sản phẩm để tính toán");
        }

        Dealer dealer = null;
        CustomerGroup customerGroup = request.getCustomerGroup();

        if (request.getDealerId() != null) {
            dealer = dealerRepository.findById(request.getDealerId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đại lý với ID: " + request.getDealerId()));
            if (Boolean.TRUE.equals(dealer.getLocked())) {
                throw new DealerLockedException(dealer.getCode(), dealer.getName(), dealer.getLockReason());
            }
            if (Boolean.FALSE.equals(dealer.getActive())) {
                throw new IllegalArgumentException(String.format("Đại lý '%s' (%s) đã ngừng giao dịch.",
                        dealer.getName(), dealer.getCode()));
            }
            customerGroup = dealer.getCustomerGroup();
        }

        LocalDate orderDate = request.getOrderDate() != null ? request.getOrderDate() : LocalDate.now();
        PriceList priceList = resolveOrderPriceList(request.getPriceListId(), customerGroup, orderDate);
        if (customerGroup == null && priceList != null) {
            customerGroup = priceList.getCustomerGroup();
        }

        Map<Long, PriceListItem> priceItemMap = new HashMap<>();
        if (priceList != null && priceList.getItems() != null) {
            for (PriceListItem pli : priceList.getItems()) {
                if (pli.getProduct() != null) {
                    priceItemMap.put(pli.getProduct().getId(), pli);
                }
            }
        }

        boolean orderRequiresApproval = false;
        List<String> belowFloorReasons = new ArrayList<>();
        List<VolumeDiscountPolicyService.OrderItemDiscountInput> discountInputs = new ArrayList<>();
        List<ItemPreData> preDataList = new ArrayList<>();

        for (int i = 0; i < request.getItems().size(); i++) {
            OrderItemRequest itemReq = request.getItems().get(i);
            Product product = resolveProduct(itemReq);

            if (customerGroup != null) {
                PriceListItem pli = priceItemMap.get(product.getId());
                if (pli == null) {
                    throw new IllegalArgumentException(String.format("Sản phẩm '%s' (mã: %s) chưa có trong bảng giá hiệu lực của nhóm khách hàng '%s'. Không thể thêm dòng hàng.",
                            product.getName(), product.getCode(), customerGroup.getDisplayName()));
                }
            }

            String inputUnit = itemReq.getUnit();
            if (inputUnit == null || inputUnit.isBlank()) {
                inputUnit = product.getUnit();
            } else {
                inputUnit = inputUnit.trim();
            }

            BigDecimal qty = itemReq.getQuantity();
            if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("Số lượng cho sản phẩm '" + product.getCode() + "' phải lớn hơn 0");
            }

            BigDecimal factor = unitConversionService.getConversionFactor(product, inputUnit);
            BigDecimal baseQty = qty.multiply(factor);

            PriceListItem priceListItem = priceItemMap.get(product.getId());
            BigDecimal unitSellingPrice = null;
            BigDecimal unitFloorPrice = null;

            if (priceListItem != null) {
                unitSellingPrice = priceListItem.getSellingPrice().multiply(factor);
                unitFloorPrice = priceListItem.getFloorPrice().multiply(factor);
            }

            boolean isManual = itemReq.getPrice() != null;
            BigDecimal price;
            if (isManual) {
                price = itemReq.getPrice();
            } else if (unitSellingPrice != null) {
                price = unitSellingPrice;
            } else {
                price = product.getPrice() != null ? product.getPrice().multiply(factor) : BigDecimal.ZERO;
            }

            boolean isBelowFloor = false;
            if (unitFloorPrice != null && price.compareTo(unitFloorPrice) < 0) {
                isBelowFloor = true;
                orderRequiresApproval = true;
                belowFloorReasons.add(String.format("Sản phẩm '%s' bán giá %s < giá sàn %s (%s)",
                        product.getName(), price.toPlainString(), unitFloorPrice.toPlainString(), inputUnit));
            }

            BigDecimal subtotalAmount = price.multiply(qty);

            discountInputs.add(VolumeDiscountPolicyService.OrderItemDiscountInput.builder()
                    .index(i)
                    .product(product)
                    .unit(inputUnit)
                    .quantity(qty)
                    .conversionFactor(factor)
                    .baseQuantity(baseQty)
                    .baseUnit(product.getUnit())
                    .price(price)
                    .subtotalAmount(subtotalAmount)
                    .build());

            preDataList.add(new ItemPreData(product, inputUnit, qty, factor, baseQty, price, unitFloorPrice, isBelowFloor, subtotalAmount, itemReq.getNote()));
        }

        VolumeDiscountPolicyService.OrderDiscountSummary discountSummary = null;
        if (volumeDiscountPolicyService != null) {
            discountSummary = volumeDiscountPolicyService.calculateOrderDiscount(discountInputs, customerGroup, orderDate);
        }

        // Tra cứu tồn kho phục vụ nếu có
        Warehouse warehouse = resolveServingWarehouse(dealer, request.getWarehouseId(), false);

        Map<Long, WarehouseInventory> inventoryMap = new HashMap<>();
        if (warehouse != null && inventoryRepository != null) {
            List<Long> productIds = preDataList.stream().map(d -> d.product().getId()).distinct().toList();
            List<WarehouseInventory> inventories = inventoryRepository.findByWarehouseIdAndProductIdIn(warehouse.getId(), productIds);
            for (WarehouseInventory inv : inventories) {
                if (inv.getProduct() != null) {
                    inventoryMap.put(inv.getProduct().getId(), inv);
                }
            }
        }

        BigDecimal grandSubtotal = BigDecimal.ZERO;
        BigDecimal grandDiscount = BigDecimal.ZERO;
        List<OrderCalculateItemResponse> calculatedItems = new ArrayList<>();

        for (int i = 0; i < preDataList.size(); i++) {
            ItemPreData d = preDataList.get(i);
            BigDecimal discountAmount = BigDecimal.ZERO;
            BigDecimal discountPercent = null;
            Long appliedPolicyId = null;
            String appliedPolicyCode = null;
            String appliedPolicyName = null;
            String reason = null;

            if (discountSummary != null && discountSummary.getItemResults() != null) {
                VolumeDiscountPolicyService.ItemDiscountResult res = discountSummary.getItemResults().get(i);
                if (res != null) {
                    discountAmount = res.getDiscountAmount() != null ? res.getDiscountAmount() : BigDecimal.ZERO;
                    discountPercent = res.getDiscountPercent();
                    appliedPolicyId = res.getAppliedPolicyId();
                    appliedPolicyCode = res.getAppliedPolicyCode();
                    appliedPolicyName = res.getAppliedPolicyName();
                    reason = res.getBestPolicyReason();
                }
            }

            BigDecimal lineTotal = d.subtotal().subtract(discountAmount).max(BigDecimal.ZERO);
            grandSubtotal = grandSubtotal.add(d.subtotal());
            grandDiscount = grandDiscount.add(discountAmount);

            PriceListItem priceListItem = priceItemMap.get(d.product().getId());
            BigDecimal priceListSelling = priceListItem != null ? priceListItem.getSellingPrice().multiply(d.conversionFactor()) : null;

            BigDecimal physical = BigDecimal.ZERO;
            BigDecimal reserved = BigDecimal.ZERO;
            BigDecimal available = BigDecimal.ZERO;
            BigDecimal availableInUnit = BigDecimal.ZERO;
            BigDecimal maxAllowedInUnit = BigDecimal.ZERO;
            Boolean exceedsStock = false;

            if (warehouse != null) {
                WarehouseInventory inv = inventoryMap.get(d.product().getId());
                physical = (inv != null && inv.getBaseQuantity() != null) ? inv.getBaseQuantity() : BigDecimal.ZERO;
                reserved = (inv != null && inv.getReservedQuantity() != null) ? inv.getReservedQuantity() : BigDecimal.ZERO;
                available = physical.subtract(reserved).max(BigDecimal.ZERO);
                BigDecimal factor = (d.conversionFactor() != null && d.conversionFactor().compareTo(BigDecimal.ZERO) > 0)
                        ? d.conversionFactor()
                        : BigDecimal.ONE;
                availableInUnit = available.divide(factor, 2, RoundingMode.HALF_UP);
                maxAllowedInUnit = available.divide(factor, 0, RoundingMode.FLOOR);
                exceedsStock = d.baseQuantity().compareTo(available) > 0;
            }

            calculatedItems.add(OrderCalculateItemResponse.builder()
                    .productId(d.product().getId())
                    .productCode(d.product().getCode())
                    .productName(d.product().getName())
                    .unit(d.unit())
                    .quantity(d.quantity())
                    .conversionFactor(d.conversionFactor())
                    .baseUnit(d.product().getUnit())
                    .baseQuantity(d.baseQuantity())
                    .priceListSellingPrice(priceListSelling)
                    .floorPrice(d.floorPrice())
                    .price(d.price())
                    .isManualPrice(request.getItems().get(i).getPrice() != null)
                    .belowFloorPrice(d.isBelowFloor())
                    .subtotalAmount(d.subtotal())
                    .discountAmount(discountAmount)
                    .discountPercent(discountPercent)
                    .appliedPolicyId(appliedPolicyId)
                    .appliedPolicyCode(appliedPolicyCode)
                    .appliedPolicyName(appliedPolicyName)
                    .selectionReason(reason)
                    .totalAmount(lineTotal)
                    .physicalQuantity(physical)
                    .reservedQuantity(reserved)
                    .availableQuantity(available)
                    .availableInUnit(availableInUnit)
                    .maxAllowedInUnit(maxAllowedInUnit)
                    .exceedsStock(exceedsStock)
                    .build());
        }

        return OrderCalculateResponse.builder()
                .dealerId(dealer != null ? dealer.getId() : null)
                .dealerName(dealer != null ? dealer.getName() : null)
                .warehouseId(warehouse != null ? warehouse.getId() : null)
                .warehouseCode(warehouse != null ? warehouse.getCode() : null)
                .warehouseName(warehouse != null ? warehouse.getName() : null)
                .customerGroup(customerGroup)
                .customerGroupName(customerGroup != null ? customerGroup.getDisplayName() : null)
                .priceListId(priceList != null ? priceList.getId() : null)
                .priceListCode(priceList != null ? priceList.getCode() : null)
                .priceListName(priceList != null ? priceList.getName() : null)
                .subtotalAmount(grandSubtotal)
                .discountAmount(grandDiscount)
                .totalAmount(grandSubtotal.subtract(grandDiscount).max(BigDecimal.ZERO))
                .requiresApproval(orderRequiresApproval)
                .approvalReason(orderRequiresApproval ? String.join("; ", belowFloorReasons) : null)
                .items(calculatedItems)
                .build();
    }

    /**
     * S4-09: Sao chép một đơn cũ thành đơn mới ở trạng thái Nháp (DRAFT).
     * Toàn bộ dòng hàng của đơn đã chọn được sao chép.
     * Giá và chiết khấu được áp lại theo bảng giá và chính sách hiện hành, không kế thừa giá cũ.
     * Bản sao luôn bắt đầu ở trạng thái Nháp.
     */
    @Override
    @Transactional
    public OrderResponse copyOrder(Long id, String username) {
        Order sourceOrder = orderRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn hàng với ID: " + id));

        validateOrderViewAccess(sourceOrder, username);

        if (sourceOrder.getItems() == null || sourceOrder.getItems().isEmpty()) {
            throw new IllegalArgumentException("Đơn hàng không có dòng hàng nào để sao chép");
        }

        Dealer dealer = sourceOrder.getDealer();
        if (dealer != null) {
            if (Boolean.TRUE.equals(dealer.getLocked())) {
                throw new DealerLockedException(dealer.getCode(), dealer.getName(), dealer.getLockReason());
            }
            if (Boolean.FALSE.equals(dealer.getActive())) {
                throw new IllegalArgumentException(String.format("Đại lý '%s' (%s) đã ngừng giao dịch, không thể sao chép đơn hàng.",
                        dealer.getName(), dealer.getCode()));
            }
        }

        CustomerGroup customerGroup = sourceOrder.getCustomerGroup();
        if (customerGroup == null && dealer != null) {
            customerGroup = dealer.getCustomerGroup();
        }

        LocalDate today = LocalDate.now();
        PriceList priceList = null;
        if (customerGroup != null) {
            List<PriceList> effectivePriceLists = priceListRepository.findEffectivePriceLists(customerGroup, today);
            if (!effectivePriceLists.isEmpty()) {
                priceList = effectivePriceLists.get(0);
            } else {
                throw new IllegalArgumentException(String.format("Không tìm thấy bảng giá hiệu lực cho nhóm khách hàng '%s' tại thời điểm %s. Không thể tính giá cho đơn sao chép.",
                        customerGroup.getDisplayName(), today));
            }
        }

        Map<Long, PriceListItem> priceItemMap = new HashMap<>();
        if (priceList != null && priceList.getItems() != null) {
            for (PriceListItem pli : priceList.getItems()) {
                if (pli.getProduct() != null) {
                    priceItemMap.put(pli.getProduct().getId(), pli);
                }
            }
        }

        String newOrderCode = "DH-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"));

        Order newOrder = Order.builder()
                .orderCode(newOrderCode)
                .customerName(sourceOrder.getCustomerName())
                .customerGroup(customerGroup)
                .dealer(dealer)
                .deliveryPoint(sourceOrder.getDeliveryPoint())
                .expectedDeliveryDate(today.plusDays(3))
                .priceList(priceList)
                .note(sourceOrder.getNote() != null ? sourceOrder.getNote() : "")
                .status("DRAFT")
                .requiresApproval(false)
                .createdBy(username != null ? username : "system")
                .createdAt(LocalDateTime.now())
                .recordedAt(null)
                .items(new ArrayList<>())
                .build();

        List<VolumeDiscountPolicyService.OrderItemDiscountInput> discountInputs = new ArrayList<>();
        List<ItemPreData> preDataList = new ArrayList<>();

        for (int i = 0; i < sourceOrder.getItems().size(); i++) {
            OrderItem sourceItem = sourceOrder.getItems().get(i);
            Product product = sourceItem.getProduct();
            if (product == null && sourceItem.getId() != null) {
                continue;
            }

            if (customerGroup != null) {
                PriceListItem pli = priceItemMap.get(product.getId());
                if (pli == null) {
                    throw new IllegalArgumentException(String.format("Sản phẩm '%s' (mã: %s) chưa có trong bảng giá hiệu lực hiện hành của nhóm khách hàng '%s'. Không thể sao chép dòng hàng.",
                            product.getName(), product.getCode(), customerGroup.getDisplayName()));
                }
            }

            String inputUnit = sourceItem.getUnit();
            if (inputUnit == null || inputUnit.isBlank()) {
                inputUnit = product.getUnit();
            } else {
                inputUnit = inputUnit.trim();
            }

            BigDecimal qty = sourceItem.getQuantity();
            if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("Số lượng cho sản phẩm '" + product.getCode() + "' phải lớn hơn 0");
            }

            BigDecimal factor = unitConversionService.getConversionFactor(product, inputUnit);
            BigDecimal baseQty = qty.multiply(factor);

            PriceListItem priceListItem = priceItemMap.get(product.getId());
            BigDecimal unitSellingPrice = null;
            BigDecimal unitFloorPrice = null;
            if (priceListItem != null) {
                unitSellingPrice = priceListItem.getSellingPrice().multiply(factor);
                unitFloorPrice = priceListItem.getFloorPrice().multiply(factor);
            }

            BigDecimal price;
            if (unitSellingPrice != null) {
                price = unitSellingPrice;
            } else {
                price = product.getPrice() != null ? product.getPrice().multiply(factor) : BigDecimal.ZERO;
            }

            BigDecimal subtotalAmount = price.multiply(qty);

            discountInputs.add(VolumeDiscountPolicyService.OrderItemDiscountInput.builder()
                    .index(i)
                    .product(product)
                    .unit(inputUnit)
                    .quantity(qty)
                    .conversionFactor(factor)
                    .baseQuantity(baseQty)
                    .baseUnit(product.getUnit())
                    .price(price)
                    .subtotalAmount(subtotalAmount)
                    .build());

            preDataList.add(new ItemPreData(product, inputUnit, qty, factor, baseQty, price, unitFloorPrice, false, subtotalAmount, sourceItem.getNote()));
        }

        VolumeDiscountPolicyService.OrderDiscountSummary discountSummary = null;
        if (volumeDiscountPolicyService != null) {
            discountSummary = volumeDiscountPolicyService.calculateOrderDiscount(discountInputs, customerGroup, today);
        }

        BigDecimal grandSubtotal = BigDecimal.ZERO;
        BigDecimal grandDiscount = BigDecimal.ZERO;

        for (int i = 0; i < preDataList.size(); i++) {
            ItemPreData pre = preDataList.get(i);
            BigDecimal itemDiscount = BigDecimal.ZERO;
            BigDecimal itemDiscountPercent = BigDecimal.ZERO;
            Long appliedPolicyId = null;
            String appliedPolicyCode = null;
            String appliedPolicyName = null;

            if (discountSummary != null && discountSummary.getItemResults() != null) {
                VolumeDiscountPolicyService.ItemDiscountResult disRes = discountSummary.getItemResults().get(i);
                if (disRes != null) {
                    itemDiscount = disRes.getDiscountAmount() != null ? disRes.getDiscountAmount() : BigDecimal.ZERO;
                    itemDiscountPercent = disRes.getDiscountPercent() != null ? disRes.getDiscountPercent() : BigDecimal.ZERO;
                    appliedPolicyId = disRes.getAppliedPolicyId();
                    appliedPolicyCode = disRes.getAppliedPolicyCode();
                    appliedPolicyName = disRes.getAppliedPolicyName();
                }
            }

            BigDecimal itemTotal = pre.subtotal().subtract(itemDiscount);
            if (itemTotal.compareTo(BigDecimal.ZERO) < 0) {
                itemTotal = BigDecimal.ZERO;
            }

            OrderItem orderItem = OrderItem.builder()
                    .order(newOrder)
                    .product(pre.product())
                    .unit(pre.unit())
                    .quantity(pre.quantity())
                    .conversionFactor(pre.conversionFactor())
                    .baseUnit(pre.product().getUnit())
                    .baseQuantity(pre.baseQuantity())
                    .price(pre.price())
                    .floorPrice(pre.floorPrice())
                    .belowFloorPrice(false)
                    .subtotalAmount(pre.subtotal())
                    .discountAmount(itemDiscount)
                    .discountPercent(itemDiscountPercent)
                    .appliedPolicyId(appliedPolicyId)
                    .appliedPolicyCode(appliedPolicyCode)
                    .appliedPolicyName(appliedPolicyName)
                    .totalAmount(itemTotal)
                    .note(pre.note())
                    .build();

            newOrder.getItems().add(orderItem);
            grandSubtotal = grandSubtotal.add(pre.subtotal());
            grandDiscount = grandDiscount.add(itemDiscount);
        }

        newOrder.setSubtotalAmount(grandSubtotal);
        newOrder.setDiscountAmount(grandDiscount);
        newOrder.setTotalAmount(grandSubtotal.subtract(grandDiscount));

        Order saved = orderRepository.save(newOrder);

        auditLogService.logAction("INVOICE", saved.getOrderCode(), "COPY_FROM",
                sourceOrder.getOrderCode(), saved.getStatus(), username != null ? username : "system");

        syncToDraft(saved, username);

        return mapToResponse(saved);
    }

    /**
     * S4-09: Sao chép các dòng hàng của đơn cũ trực tiếp vào bản nháp tạo đơn của người dùng.
     */
    @Override
    @Transactional
    public JsonNode copyOrderToDraft(Long id, String username) {
        Order sourceOrder = orderRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn hàng với ID: " + id));

        validateOrderViewAccess(sourceOrder, username);

        if (sourceOrder.getItems() == null || sourceOrder.getItems().isEmpty()) {
            throw new IllegalArgumentException("Đơn hàng không có dòng hàng để sao chép.");
        }

        ObjectMapper mapper = this.objectMapper != null ? this.objectMapper : new ObjectMapper();
        ObjectNode draft = mapper.createObjectNode();
        draft.put("dealerId", sourceOrder.getDealer() != null ? sourceOrder.getDealer().getId() : null);
        draft.put("deliveryPointId", sourceOrder.getDeliveryPoint() != null ? sourceOrder.getDeliveryPoint().getId() : null);
        draft.putNull("deliveryDate");
        draft.put("note", "");

        ArrayNode itemsNode = draft.putArray("items");
        int seq = 0;
        for (int i = 0; i < sourceOrder.getItems().size(); i++) {
            OrderItem item = sourceOrder.getItems().get(i);
            if (item.getProduct() == null || item.getQuantity() == null || item.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            ObjectNode itemNode = itemsNode.addObject();
            itemNode.put("_key", "copy-" + sourceOrder.getId() + "-" + i);
            itemNode.put("productId", item.getProduct().getId());
            itemNode.put("selectedUnitId", item.getUnit() != null ? item.getUnit() : item.getProduct().getUnit());
            itemNode.put("qty", item.getQuantity());
            itemNode.put("basePrice", 0);
            itemNode.put("unitPrice", 0);
            itemNode.put("floorPrice", 0);
            itemNode.put("manualPrice", false);
            itemNode.put("discountAmount", 0);
            seq++;
        }
        draft.put("_seq", seq);

        if (seq == 0) {
            throw new IllegalArgumentException("Đơn hàng không có dòng hàng hợp lệ để sao chép.");
        }

        if (orderDraftRepository != null && username != null && !username.isBlank()) {
            OrderDraft orderDraft = orderDraftRepository.findByUsername(username)
                    .orElseGet(() -> OrderDraft.builder().username(username).build());
            orderDraft.setDraftData(draft.toString());
            orderDraftRepository.save(orderDraft);
        }

        return draft;
    }

    @Override
    @Transactional
    public JsonNode copyLastOrderToDraft(Long dealerId, String username) {
        Dealer dealer = dealerRepository.findById(dealerId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đại lý với ID: " + dealerId));

        if (Boolean.TRUE.equals(dealer.getLocked())) {
            throw new DealerLockedException(dealer.getCode(), dealer.getName(), dealer.getLockReason());
        }
        if (Boolean.FALSE.equals(dealer.getActive())) {
            throw new IllegalArgumentException(String.format("Đại lý '%s' (%s) đã ngừng giao dịch, không thể sao chép đơn hàng.",
                    dealer.getName(), dealer.getCode()));
        }

        validateSalesAccessForDealer(dealer, username);

        List<Order> validOrders = orderRepository.findByDealerIdAndStatusNotInOrderByCreatedAtDesc(
                dealerId, List.of("CANCELLED", "REJECTED", "PENDING_APPROVAL"));
        if (validOrders.isEmpty()) {
            throw new IllegalArgumentException(String.format("Đại lý '%s' chưa có đơn hàng nào trước đây để sao chép.", dealer.getName()));
        }

        Order lastOrder = validOrders.get(0);
        return copyOrderToDraft(lastOrder.getId(), username);
    }

    private void validateSalesAccessForDealer(Dealer dealer, String username) {
        if (username == null || username.isBlank() || "system".equalsIgnoreCase(username) || "admin".equalsIgnoreCase(username)) {
            return;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return;
        }
        boolean isAdminOrManager = auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()) || "ROLE_SALES_MANAGER".equals(a.getAuthority()));
        if (isAdminOrManager) {
            return;
        }

        boolean isSales = auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_SALES".equals(a.getAuthority()));
        if (!isSales) {
            return;
        }

        if (dealer.getAssignedUser() != null
                && dealer.getAssignedUser().getUsername() != null
                && dealer.getAssignedUser().getUsername().equalsIgnoreCase(username)) {
            return;
        }

        throw new AccessDeniedException("Bạn không có quyền thao tác với đại lý này");
    }

    private void validateSalesAccess(Order order, String username) {
        if (username == null || username.isBlank() || "system".equalsIgnoreCase(username) || "admin".equalsIgnoreCase(username)) {
            return;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return;
        }
        boolean isAdminOrManager = auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()) || "ROLE_SALES_MANAGER".equals(a.getAuthority()));
        if (isAdminOrManager) {
            return;
        }

        boolean isSales = auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_SALES".equals(a.getAuthority()));
        if (!isSales) {
            return;
        }

        if (order.getCreatedBy() != null && order.getCreatedBy().equalsIgnoreCase(username)) {
            return;
        }

        Dealer dealer = order.getDealer();
        if (dealer != null && dealer.getAssignedUser() != null
                && dealer.getAssignedUser().getUsername() != null
                && dealer.getAssignedUser().getUsername().equalsIgnoreCase(username)) {
            return;
        }

        throw new AccessDeniedException("Bạn không có quyền sao chép đơn hàng này");
    }

    private void syncToDraft(Order saved, String username) {
        if (orderDraftRepository == null || username == null || username.isBlank()) {
            return;
        }
        try {
            ObjectMapper mapper = this.objectMapper != null ? this.objectMapper : new ObjectMapper();
            ObjectNode draft = mapper.createObjectNode();
            draft.put("dealerId", saved.getDealer() != null ? saved.getDealer().getId() : null);
            draft.put("deliveryPointId", saved.getDeliveryPoint() != null ? saved.getDeliveryPoint().getId() : null);
            draft.putNull("deliveryDate");
            draft.put("note", saved.getNote() != null ? saved.getNote() : "");

            ArrayNode itemsNode = draft.putArray("items");
            if (saved.getItems() != null) {
                for (int i = 0; i < saved.getItems().size(); i++) {
                    OrderItem item = saved.getItems().get(i);
                    ObjectNode itemNode = itemsNode.addObject();
                    itemNode.put("_key", "copy-" + saved.getId() + "-" + i);
                    itemNode.put("productId", item.getProduct() != null ? item.getProduct().getId() : null);
                    itemNode.put("selectedUnitId", item.getUnit());
                    itemNode.put("qty", item.getQuantity());
                    itemNode.put("basePrice", 0);
                    itemNode.put("unitPrice", item.getPrice());
                    itemNode.put("floorPrice", item.getFloorPrice());
                    itemNode.put("manualPrice", false);
                    itemNode.put("discountAmount", item.getDiscountAmount());
                }
                draft.put("_seq", saved.getItems().size());
            }
            OrderDraft orderDraft = orderDraftRepository.findByUsername(username)
                    .orElseGet(() -> OrderDraft.builder().username(username).build());
            orderDraft.setDraftData(draft.toString());
            orderDraftRepository.save(orderDraft);
        } catch (Exception ignored) {
        }
    }

    private boolean isSalesOnlyUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return false;
        boolean hasSales = auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_SALES".equals(a.getAuthority()));
        boolean hasElevated = auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority())
                        || "ROLE_SALES_MANAGER".equals(a.getAuthority())
                        || "ROLE_ACCOUNTANT".equals(a.getAuthority())
                        || "ROLE_VIEWER".equals(a.getAuthority())
                        || "ROLE_WAREHOUSE_MANAGER".equals(a.getAuthority())
                        || "ROLE_WAREHOUSE_STAFF".equals(a.getAuthority()));
        return hasSales && !hasElevated;
    }

    private boolean isDealerUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return false;
        return auth.getAuthorities().stream().anyMatch(a -> "ROLE_DEALER".equals(a.getAuthority()))
                && auth.getAuthorities().stream().noneMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    private boolean isDealerOnlyUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return false;
        boolean hasDealer = auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_DEALER".equals(a.getAuthority()));
        boolean hasElevated = auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority())
                        || "ROLE_SALES_MANAGER".equals(a.getAuthority())
                        || "ROLE_ACCOUNTANT".equals(a.getAuthority())
                        || "ROLE_VIEWER".equals(a.getAuthority())
                        || "ROLE_SALES".equals(a.getAuthority())
                        || "ROLE_WAREHOUSE_MANAGER".equals(a.getAuthority())
                        || "ROLE_WAREHOUSE_STAFF".equals(a.getAuthority()));
        return hasDealer && !hasElevated;
    }

    private User getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || userRepository == null) return null;
        return userRepository.findByUsername(auth.getName()).orElse(null);
    }

    private void validateOrderViewAccess(Order order, String username) {
        if (username == null || username.isBlank() || "system".equalsIgnoreCase(username) || "admin".equalsIgnoreCase(username)) {
            return;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return;
        }
        boolean isElevated = auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority())
                        || "ROLE_SALES_MANAGER".equals(a.getAuthority())
                        || "ROLE_ACCOUNTANT".equals(a.getAuthority())
                        || "ROLE_VIEWER".equals(a.getAuthority())
                        || "ROLE_WAREHOUSE_MANAGER".equals(a.getAuthority())
                        || "ROLE_WAREHOUSE_STAFF".equals(a.getAuthority()));
        if (isElevated) {
            return;
        }

        boolean isDealer = auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_DEALER".equals(a.getAuthority()));
        if (isDealer) {
            User currentUser = getCurrentUser();
            if (currentUser != null && currentUser.getDealer() != null) {
                if (order.getDealer() != null && order.getDealer().getId().equals(currentUser.getDealer().getId())) {
                    return;
                }
            }
            throw new AccessDeniedException("Bạn chỉ có quyền xem đơn hàng của chính đại lý mình");
        }

        boolean isSales = auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_SALES".equals(a.getAuthority()));
        if (!isSales) {
            return;
        }

        if (order.getCreatedBy() != null && order.getCreatedBy().equalsIgnoreCase(username)) {
            return;
        }

        Dealer dealer = order.getDealer();
        if (dealer != null && dealer.getAssignedUser() != null
                && dealer.getAssignedUser().getUsername() != null
                && dealer.getAssignedUser().getUsername().equalsIgnoreCase(username)) {
            return;
        }

        throw new AccessDeniedException("Bạn chỉ có quyền xem đơn hàng của đại lý do mình phụ trách");
    }

    private void checkSalesAuthorizationForOrder(Order order, String action) {
        if (!isSalesOnlyUser()) {
            return;
        }
        User currentUser = getCurrentUser();
        if (currentUser == null) {
            return;
        }

        boolean isCreator = order.getCreatedBy() != null && order.getCreatedBy().equalsIgnoreCase(currentUser.getUsername());
        boolean isAssignedDealer = order.getDealer() != null
                && order.getDealer().getAssignedUser() != null
                && order.getDealer().getAssignedUser().getId().equals(currentUser.getId());

        if (!isCreator && !isAssignedDealer) {
            throw new AccessDeniedException(
                    "Bạn chỉ có quyền " + action + " đơn hàng do mình tạo hoặc thuộc đại lý do mình phụ trách");
        }
    }

    /**
     * RLS cho các thao tác vòng đời do kho thực hiện. Controller đã kiểm tra
     * quyền trong ma trận; service tiếp tục giới hạn dữ liệu theo các kho được
     * gán cho tài khoản, ngoại trừ quản trị viên.
     */
    private void checkWarehouseAuthorizationForOrder(Order order, String action) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return;
        }
        if (hasAuthority(auth, "ROLE_ADMIN")) {
            return;
        }

        boolean hasWarehousePermission = hasAuthority(auth, "WAREHOUSE_EXPORT");
        if (!hasWarehousePermission) {
            throw new AccessDeniedException("Bạn không có quyền " + action + " theo ma trận phân quyền");
        }

        User currentUser = getCurrentUser();
        Long orderWarehouseId = order.getWarehouse() != null ? order.getWarehouse().getId() : null;
        boolean assigned = currentUser != null && currentUser.getWarehouses() != null
                && orderWarehouseId != null
                && currentUser.getWarehouses().stream()
                .anyMatch(warehouse -> warehouse != null && orderWarehouseId.equals(warehouse.getId()));
        if (!assigned) {
            throw new AccessDeniedException("Bạn không có quyền " + action + " ngoài phạm vi kho được phân công");
        }
    }

    /** Chỉ cho phép tiến về bước kế tiếp của vòng đời; không nhảy hoặc lùi bước. */
    private void validateLifecycleTransition(String oldStatus, String targetStatus) {
        int oldStep = resolveStepOrder(oldStatus);
        int targetStep = resolveStepOrder(targetStatus);
        if (oldStep <= 0 || targetStep <= 0) {
            throw new IllegalArgumentException("Không thể chuyển từ trạng thái không hợp lệ");
        }
        if (oldStep >= 5 && targetStep < oldStep) {
            throw new IllegalArgumentException("Không thể lùi trạng thái sau khi đơn đã xuất kho");
        }
        if (targetStep != oldStep + 1) {
            throw new IllegalArgumentException("Chỉ được chuyển đơn sang bước kế tiếp của vòng đời");
        }
    }

    private Warehouse resolveServingWarehouse(Dealer dealer, Long requestedId, boolean required) {
        Warehouse warehouse = null;
        if (dealer != null) {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated()) validateSalesAccessForDealer(dealer, auth.getName());
            warehouse = dealer.getWarehouse();
            if (warehouse == null || warehouse.getId() == null) {
                throw new IllegalArgumentException("Đại lý chưa được cấu hình kho phục vụ. Vui lòng liên hệ quản trị viên.");
            }
            if (requestedId != null && !requestedId.equals(warehouse.getId())) {
                throw new AccessDeniedException("Kho yêu cầu không phải kho phục vụ đại lý này");
            }
            if (requestedId != null && warehouseRepository != null) {
                warehouse = warehouseRepository.findById(requestedId)
                        .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy kho phục vụ với ID: " + requestedId));
            }
        } else if (requestedId != null && warehouseRepository != null) {
            warehouse = warehouseRepository.findById(requestedId)
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy kho với ID: " + requestedId));
        } else if (warehouseRepository != null) {
            warehouse = warehouseRepository.findAllByActiveTrueOrderByNameAsc().stream().findFirst().orElse(null);
        }
        if (warehouse == null && required) throw new IllegalArgumentException("Chưa có kho hàng đang hoạt động để xử lý đơn");
        if (warehouse != null && !Boolean.TRUE.equals(warehouse.getActive())) {
            throw new IllegalArgumentException("Kho phục vụ đã ngừng hoạt động: " + warehouse.getName());
        }
        return warehouse;
    }

    private Warehouse resolveDefaultWarehouse(Long warehouseId) {
        if (warehouseRepository == null) {
            return null;
        }
        if (warehouseId != null) {
            return warehouseRepository.findById(warehouseId)
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy kho hàng với ID: " + warehouseId));
        }
        List<Warehouse> active = warehouseRepository.findAllByActiveTrueOrderByNameAsc();
        if (active != null && !active.isEmpty()) {
            return active.get(0);
        }
        return warehouseRepository.findAll().stream().findFirst().orElse(null);
    }

    private void deductInventoryOnExport(Order order) {
        if (order.getWarehouse() == null || inventoryRepository == null) {
            throw new IllegalArgumentException("Đơn hàng chưa có kho xuất");
        }
        Map<Long, BigDecimal> quantities = new TreeMap<>();
        for (OrderItem item : order.getItems()) {
            quantities.merge(item.getProduct().getId(), item.getBaseQuantity(), BigDecimal::add);
        }
        List<WarehouseInventory> locked = inventoryRepository.findByWarehouseIdAndProductIdInForUpdate(
                order.getWarehouse().getId(), new ArrayList<>(quantities.keySet()));
        Map<Long, WarehouseInventory> stock = locked.stream().collect(Collectors.toMap(
                value -> value.getProduct().getId(), value -> value));
        for (var entry : quantities.entrySet()) {
            WarehouseInventory inventory = stock.get(entry.getKey());
            boolean reserved = Boolean.TRUE.equals(order.getInventoryReserved());
            if (inventory == null || inventory.getBaseQuantity().compareTo(entry.getValue()) < 0
                    || (!reserved && inventory.getAvailableQuantity().compareTo(entry.getValue()) < 0)
                    || (reserved && inventory.getReservedQuantity().compareTo(entry.getValue()) < 0)) {
                throw new IllegalArgumentException("Không đủ tồn kho để xuất sản phẩm " + entry.getKey());
            }
            if (reserved) inventory.subtractReservedQuantity(entry.getValue());
            inventory.subtractBaseQuantity(entry.getValue());
            inventoryRepository.save(inventory);
        }
        order.setInventoryReserved(false);
        order.setReservationExpiresAt(null);
    }

    private void recordStatusHistory(Order order, String prevStatus, String newStatus, String note, String changedBy) {
        if (orderStatusHistoryRepository == null || order == null) {
            return;
        }
        OrderStatusHistory history = OrderStatusHistory.builder()
                .order(order)
                .previousStatus(prevStatus)
                .newStatus(newStatus)
                .statusDisplayName(OrderStatus.getDisplayName(newStatus))
                .note(note)
                .changedBy(changedBy != null ? changedBy : "system")
                .changedAt(LocalDateTime.now())
                .build();
        orderStatusHistoryRepository.save(history);
    }

    private int resolveStepOrder(String status) {
        if (status == null) return 0;
        String s = status.trim().toUpperCase();
        return switch (s) {
            case "DRAFT" -> 1;
            case "PENDING_APPROVAL" -> 2;
            case "APPROVED", "RECORDED" -> 3;
            case "PICKING", "PROCESSING" -> 4;
            case "EXPORTED", "SHIPPED" -> 5;
            case "DELIVERED" -> 6;
            case "CLOSED", "COMPLETED" -> 7;
            default -> 0;
        };
    }

    private String resolveStepState(int stepOrder, int currentStepOrder, boolean isCancelled, boolean isRejected, boolean isSkipped) {
        if (isSkipped) return "SKIPPED";
        if (isCancelled) {
            if (stepOrder < currentStepOrder) return "COMPLETED";
            if (stepOrder == currentStepOrder) return "CANCELLED";
            return "SKIPPED";
        }
        if (isRejected && stepOrder == 2) {
            return "REJECTED";
        }
        if (stepOrder < currentStepOrder) return "COMPLETED";
        if (stepOrder == currentStepOrder) return "CURRENT";
        return "UPCOMING";
    }

    private String resolveCancelledStepState(int stepOrder, int cancelledAtStep) {
        return stepOrder <= cancelledAtStep ? "COMPLETED" : "SKIPPED";
    }

    private String normalizeLifecycleStatus(String status) {
        String normalized = status == null ? "" : status.trim().toUpperCase();
        return switch (normalized) {
            case "PROCESSING" -> "PICKING";
            case "SHIPPED" -> "EXPORTED";
            case "COMPLETED" -> "CLOSED";
            default -> normalized;
        };
    }

    private OrderLifecycleStepResponse buildStep(
            int stepOrder, String status, String displayName, String description,
            String state, OrderStatusHistory history, LocalDateTime fallbackTime, String fallbackOperator) {
        LocalDateTime timestamp = history != null ? history.getChangedAt() : fallbackTime;
        String operator = history != null ? history.getChangedBy() : fallbackOperator;
        String note = history != null ? history.getNote() : null;

        return OrderLifecycleStepResponse.builder()
                .stepOrder(stepOrder)
                .status(status)
                .displayName(displayName)
                .description(description)
                .state(state)
                .timestamp(timestamp)
                .operator(operator)
                .note(note)
                .build();
    }


    @Override
    @Transactional
    public OrderResponse returnOrder(Long id, String reason, String username) {
        if (reason == null || reason.trim().isEmpty()) {
            throw new IllegalArgumentException("Vui lòng nhập lý do/ý kiến trả lại sửa đơn hàng");
        }

        Order order = orderRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn hàng với ID: " + id));

        if (!"PENDING_APPROVAL".equalsIgnoreCase(order.getStatus())) {
            throw new IllegalArgumentException("Chỉ có thể trả lại sửa đơn hàng đang ở trạng thái Chờ duyệt (PENDING_APPROVAL)");
        }

        String cleanReason = reason.trim();
        releaseReservedInventory(order);
        order.setStatus("RETURNED");
        order.setApprovedBy(username != null ? username : "system");
        order.setApprovedAt(LocalDateTime.now());
        order.setNote((order.getNote() != null && !order.getNote().isBlank() ? order.getNote() + " | " : "") + "Lý do trả lại sửa: " + cleanReason);

        Order saved = orderRepository.save(order);

        saveApprovalHistory(saved, "RETURNED", cleanReason, username);

        auditLogService.logAction("INVOICE", saved.getOrderCode(), "RETURN", "PENDING_APPROVAL",
                "Trả lại sửa đơn: " + cleanReason, username != null ? username : "system");
        return mapToResponse(saved);
    }

    /**
     * Kiểm tra phạm vi thao tác duyệt theo quyền trong ma trận.
     * ORDER_APPROVE xử lý ngoại lệ; ORDER_CONFIRM là quyền riêng cho xác nhận
     * đơn cổng không vi phạm, trong đúng phạm vi đại lý được phân công.
     */
    private boolean canConfirmPortalOrder(Order order) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()
                || !(hasAuthority(auth, "ROLE_ADMIN") || hasAuthority(auth, "ORDER_CONFIRM"))) return false;
        boolean assigned = order.getDealer() != null && order.getDealer().getAssignedUser() != null
                && auth.getName().equalsIgnoreCase(order.getDealer().getAssignedUser().getUsername());
        return (assigned || hasAuthority(auth, "ROLE_ADMIN")) && isPortalOrder(order)
                && "PENDING_APPROVAL".equalsIgnoreCase(order.getStatus())
                && !hasApprovalViolation(order.getApprovalReason())
                && (order.getItems() == null || order.getItems().stream().noneMatch(item -> Boolean.TRUE.equals(item.getBelowFloorPrice())));
    }

    private void validatePortalConfirmation(Order order, String username, Authentication auth) {
        if (auth == null || !auth.isAuthenticated()
                || !(hasAuthority(auth, "ROLE_ADMIN") || hasAuthority(auth, "ORDER_CONFIRM"))) {
            throw new AccessDeniedException("Bạn không có quyền xác nhận đơn đại lý theo ma trận phân quyền");
        }
        if (!isPortalOrder(order)) throw new IllegalArgumentException("Chức năng này chỉ xác nhận đơn gửi từ cổng đại lý");
        boolean assigned = order.getDealer() != null && order.getDealer().getAssignedUser() != null
                && username.equalsIgnoreCase(order.getDealer().getAssignedUser().getUsername());
        if (!assigned && !hasAuthority(auth, "ROLE_ADMIN")) {
            throw new AccessDeniedException("Bạn chỉ được xác nhận đơn của đại lý mình phụ trách");
        }
        if (hasApprovalViolation(order.getApprovalReason())
                || order.getItems() != null && order.getItems().stream().anyMatch(item -> Boolean.TRUE.equals(item.getBelowFloorPrice()))) {
            throw new IllegalArgumentException("Đơn có vi phạm, cần người có quyền Duyệt đơn hàng xử lý");
        }
        if (dealerDebtService != null && order.getDealer() != null) {
            dealerDebtService.validateDealerCanCreateOrder(order.getDealer());
            DealerDebtResponse debt = dealerDebtService.getDealerDebtInfo(order.getDealer());
            if (debt != null && dealerDebtService.checkProjectedDebtExceedsLimit(order.getDealer(), order.getTotalAmount(), debt.getCurrentDebt())) {
                throw new IllegalArgumentException("Công nợ hiện tại đã vượt hạn mức, cần người có quyền Duyệt đơn hàng xử lý");
            }
        }
    }

    private boolean canUpdateDelivery(Order order) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || order.getStatus() == null) return false;
        String status = normalizeLifecycleStatus(order.getStatus());
        if (!Set.of("EXPORTED", "DELIVERED").contains(status)) return false;
        try { checkWarehouseAuthorizationForOrder(order, "cập nhật giao hàng"); return true; }
        catch (AccessDeniedException denied) { return false; }
    }

    private String nextLifecycleStatus(Order order) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || order.getStatus() == null) return null;
        try { checkWarehouseAuthorizationForOrder(order, "chuyển vòng đời đơn"); }
        catch (AccessDeniedException denied) { return null; }
        return switch (normalizeLifecycleStatus(order.getStatus())) {
            case "RECORDED", "APPROVED" -> "PICKING";
            case "PICKING" -> "EXPORTED";
            case "EXPORTED" -> "DELIVERED";
            case "DELIVERED" -> "CLOSED";
            default -> null;
        };
    }

    private boolean validateApprovalActor(Order order, String username, Authentication auth, boolean approving) {
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            throw new AccessDeniedException("Yêu cầu đăng nhập để xử lý đơn hàng");
        }

        boolean isAdmin = hasAuthority(auth, "ROLE_ADMIN");
        boolean hasApprovalPermission = isAdmin || hasAuthority(auth, "ORDER_APPROVE");
        if (hasApprovalPermission) {
            checkReservationWarehouseScope(order);
            return true;
        }

        throw new AccessDeniedException("Bạn không có quyền xử lý đơn hàng này theo ma trận phân quyền");
    }

    private boolean hasAuthority(Authentication auth, String authority) {
        return auth.getAuthorities().stream().anyMatch(a -> authority.equals(a.getAuthority()));
    }

    private void checkReservationWarehouseScope(Order order) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || hasAuthority(auth, "ROLE_ADMIN")) return;
        User actor = getCurrentUser();
        if (actor == null) throw new AccessDeniedException("Không tìm thấy tài khoản thực hiện");
        Set<Warehouse> assigned = actor.getWarehouses();
        boolean scopedRole = hasAuthority(auth, "ROLE_WAREHOUSE_STAFF") || hasAuthority(auth, "ROLE_WAREHOUSE_MANAGER");
        if ((assigned != null && !assigned.isEmpty()) || scopedRole) {
            Long warehouseId = order.getWarehouse() == null ? null : order.getWarehouse().getId();
            if (assigned == null || assigned.stream().noneMatch(w -> Objects.equals(w.getId(), warehouseId)))
                throw new AccessDeniedException("Đơn hàng ngoài phạm vi kho được phân công");
        }
    }

    private boolean isPortalOrder(Order order) {
        String reason = order.getApprovalReason();
        if (reason == null) return false;
        String normalized = reason.toLowerCase(Locale.ROOT);
        return normalized.contains("đại lý tự đặt qua cổng")
                || normalized.contains("cổng đại lý");
    }

    private boolean hasApprovalViolation(String reason) {
        if (reason == null || reason.isBlank()) return false;
        String normalized = reason.toLowerCase(Locale.ROOT);
        return normalized.contains("giá sàn")
                || normalized.contains("hạn mức")
                || normalized.contains("công nợ")
                || normalized.contains("vượt tồn")
                || normalized.contains("tồn khả dụng");
    }

    private void saveApprovalHistory(Order order, String action, String comment, String username) {
        if (orderApprovalHistoryRepository == null) return;
        orderApprovalHistoryRepository.save(OrderApprovalHistory.builder()
                    .order(order)
                    .orderCode(order.getOrderCode())
                    .action(action)
                    .comment(comment)
                    .performedBy(username != null ? username : "system")
                    .build());
    }

    @Override
    @Transactional
    public OrderResponse updateOrderStatus(Long id, OrderStatusUpdateRequest request, String username) {
        if (request == null || request.getStatus() == null || request.getStatus().isBlank()) {
            throw new IllegalArgumentException("Trạng thái không được để trống");
        }
        String status = request.getStatus().trim().toUpperCase();
        String comment = request.getEffectiveComment();
        return switch (status) {
            case "APPROVED", "RECORDED" -> approveOrder(id, comment, username);
            case "REJECTED" -> rejectOrder(id, comment, username);
            case "RETURNED" -> returnOrder(id, comment, username);
            default -> throw new IllegalArgumentException("Trạng thái duyệt không hợp lệ: " + status + ". Chỉ chấp nhận APPROVED, REJECTED, RETURNED");
        };
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderApprovalHistoryResponse> getOrderApprovalHistory(Long orderId) {
        if (orderApprovalHistoryRepository != null) {
            List<OrderApprovalHistory> list = orderApprovalHistoryRepository.findByOrderIdOrderByCreatedAtAsc(orderId);
            if (!list.isEmpty()) {
                return list.stream().map(h -> OrderApprovalHistoryResponse.builder()
                        .id(h.getId())
                        .orderId(h.getOrder().getId())
                        .orderCode(h.getOrderCode())
                        .action(h.getAction())
                        .comment(h.getComment())
                        .performedBy(h.getPerformedBy())
                        .username(h.getPerformedBy())
                        .createdAt(h.getCreatedAt())
                        .timestamp(h.getCreatedAt())
                        .build()).toList();
            }
        }
        return Collections.emptyList();
    }

    @Override
    @Transactional(readOnly = true)
    public Page<OrderResponse> getOrders(
            String status,
            String approvalReason,
            String search,
            Boolean approvalRequired,
            Long dealerId,
            String createdBy,
            Long territoryId,
            LocalDate dateFrom,
            LocalDate dateTo,
            Pageable pageable) {

        Specification<Order> spec = (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();

            // Lọc theo trạng thái
            if (status != null && !status.isBlank()) {
                String cleanStatus = status.trim().toUpperCase();
                if ("APPROVED".equals(cleanStatus)) {
                    predicates.add(cb.or(
                            cb.equal(root.get("status"), "APPROVED"),
                            cb.and(cb.equal(root.get("status"), "RECORDED"), cb.isNotNull(root.get("approvedAt"))),
                            cb.and(cb.equal(root.get("status"), "RECORDED"), cb.isTrue(root.get("requiresApproval")))
                    ));
                } else {
                    predicates.add(cb.equal(cb.upper(root.get("status")), cleanStatus));
                }
            }

            // Lọc đơn chờ duyệt
            if (Boolean.TRUE.equals(approvalRequired)) {
                predicates.add(cb.or(
                        cb.isTrue(root.get("requiresApproval")),
                        cb.equal(root.get("status"), "PENDING_APPROVAL"),
                        cb.equal(root.get("status"), "RETURNED")
                ));
            }

            // Tìm kiếm theo mã đơn, tên, đại lý
            if (search != null && !search.isBlank()) {
                String kw = "%" + search.trim().toLowerCase() + "%";
                jakarta.persistence.criteria.Join<Order, Dealer> dealerJoin = root.join("dealer", jakarta.persistence.criteria.JoinType.LEFT);
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("orderCode")), kw),
                        cb.like(cb.lower(root.get("customerName")), kw),
                        cb.like(cb.lower(dealerJoin.get("name")), kw),
                        cb.like(cb.lower(dealerJoin.get("code")), kw)
                ));
            }

            // Lọc theo lý do cần duyệt
            if (approvalReason != null && !approvalReason.isBlank()) {
                String r = approvalReason.trim().toUpperCase();
                if ("OVER_DEBT_LIMIT".equals(r)) {
                    predicates.add(cb.or(
                            cb.like(cb.lower(root.get("approvalReason")), "%hạn mức%"),
                            cb.like(cb.lower(root.get("approvalReason")), "%công nợ%")
                    ));
                } else if ("BELOW_FLOOR_PRICE".equals(r)) {
                    predicates.add(cb.like(cb.lower(root.get("approvalReason")), "%giá sàn%"));
                } else {
                    predicates.add(cb.like(cb.lower(root.get("approvalReason")), "%" + approvalReason.trim().toLowerCase() + "%"));
                }
            }

            // S4-07: Lọc theo đại lý cụ thể
            if (dealerId != null) {
                predicates.add(cb.equal(root.get("dealer").get("id"), dealerId));
            }

            // S4-07: Lọc theo người tạo đơn (nhân viên kinh doanh)
            if (createdBy != null && !createdBy.isBlank()) {
                predicates.add(cb.equal(root.get("createdBy"), createdBy.trim()));
            }

            // S4-07: Lọc theo khu vực / địa bàn của đại lý
            if (territoryId != null) {
                jakarta.persistence.criteria.Join<Order, Dealer> dJoin = root.join("dealer", jakarta.persistence.criteria.JoinType.LEFT);
                predicates.add(cb.equal(dJoin.get("territory").get("id"), territoryId));
            }

            // S4-07: Lọc theo khoảng ngày tạo đơn
            if (dateFrom != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), dateFrom.atStartOfDay()));
            }
            if (dateTo != null) {
                predicates.add(cb.lessThan(root.get("createdAt"), dateTo.plusDays(1).atStartOfDay()));
            }

            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };

        return orderRepository.findAll(spec, pageable).map(this::mapToResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public DealerPurchaseHistoryResult getDealerPurchaseHistory(Long dealerId, int months, String currentUsername) {
        Dealer dealer = dealerRepository.findById(dealerId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đại lý với ID: " + dealerId));

        LocalDate toDate = LocalDate.now();
        LocalDate fromDate = toDate.minusMonths(Math.max(1, Math.min(months, 24)));

        // Lấy các đơn hàng RECORDED của đại lý trong khoảng thời gian
        List<Order> orders = orderRepository.findAll((root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("dealer").get("id"), dealerId));
            predicates.add(cb.equal(root.get("status"), "RECORDED"));
            predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), fromDate.atStartOfDay()));
            predicates.add(cb.lessThan(root.get("createdAt"), toDate.plusDays(1).atStartOfDay()));
            return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
        });

        // Tổng hợp theo sản phẩm
        Map<Long, ProductPurchaseSummary> productMap = new LinkedHashMap<>();
        for (Order order : orders) {
            if (order.getItems() == null) continue;
            for (OrderItem item : order.getItems()) {
                if (item.getProduct() == null) continue;
                Long pid = item.getProduct().getId();
                ProductPurchaseSummary existing = productMap.get(pid);
                BigDecimal baseQty = item.getBaseQuantity() != null ? item.getBaseQuantity() : BigDecimal.ZERO;
                int existingCount = existing != null ? existing.orderCount() : 0;
                BigDecimal existingQty = existing != null ? existing.totalBaseQuantity() : BigDecimal.ZERO;
                LocalDateTime lastAt = (existing == null || order.getCreatedAt().isAfter(existing.lastOrderAt() != null ? existing.lastOrderAt() : LocalDateTime.MIN))
                        ? order.getCreatedAt() : existing.lastOrderAt();
                BigDecimal lastPrice = (existing == null || order.getCreatedAt().isAfter(existing.lastOrderAt() != null ? existing.lastOrderAt() : LocalDateTime.MIN))
                        ? (item.getPrice() != null ? item.getPrice() : BigDecimal.ZERO) : existing.lastPrice();
                Product p = item.getProduct();
                String catName = p.getCategory() != null ? p.getCategory().getName() : null;
                BigDecimal totalQty = existingQty.add(baseQty);
                double avgMonthly = months > 0 ? totalQty.doubleValue() / months : 0;
                productMap.put(pid, new ProductPurchaseSummary(
                        pid, p.getCode(), p.getName(), catName,
                        totalQty, p.getUnit(),
                        BigDecimal.valueOf(avgMonthly).setScale(2, java.math.RoundingMode.HALF_UP),
                        existingCount + 1, lastPrice, lastAt));
            }
        }

        // Danh sách đơn gần nhất (tối đa 5)
        List<RecentOrderSummary> recentOrders = orders.stream()
                .sorted(Comparator.comparing(Order::getCreatedAt).reversed())
                .limit(5)
                .map(o -> {
                    List<String> names = o.getItems() != null
                            ? o.getItems().stream()
                                .filter(it -> it.getProduct() != null)
                                .map(it -> it.getProduct().getName())
                                .collect(Collectors.toList())
                            : Collections.emptyList();
                    return new RecentOrderSummary(o.getId(), o.getOrderCode(), o.getStatus(),
                            o.getTotalAmount(), o.getCreatedAt(), names);
                })
                .collect(Collectors.toList());

        List<ProductPurchaseSummary> products = new ArrayList<>(productMap.values());
        products.sort(Comparator.comparing(ProductPurchaseSummary::totalBaseQuantity).reversed());

        return new DealerPurchaseHistoryResult(dealerId, dealer.getName(), dealer.getCode(),
                months, fromDate, toDate, products, recentOrders);
    }

    @Override
    @Transactional(readOnly = true)
    public String generateOrderPrintHtml(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn hàng với ID: " + orderId));

        Dealer dealer = order.getDealer();
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang='vi'><head><meta charset='UTF-8'>");
        sb.append("<title>Đơn hàng ").append(escapeHtml(order.getOrderCode())).append("</title>");
        sb.append("<style>");
        sb.append("body{font-family:Arial,sans-serif;font-size:13px;margin:20px;color:#222}");
        sb.append(".header{text-align:center;margin-bottom:16px}");
        sb.append(".header h2{margin:0;font-size:18px}");
        sb.append(".info-table{width:100%;margin-bottom:14px;border-collapse:collapse}");
        sb.append(".info-table td{padding:4px 8px;vertical-align:top}");
        sb.append(".info-table .label{font-weight:bold;width:160px}");
        sb.append("table.items{width:100%;border-collapse:collapse;margin-bottom:14px}");
        sb.append("table.items th,table.items td{border:1px solid #bbb;padding:5px 8px;text-align:left}");
        sb.append("table.items th{background:#f0f0f0;font-weight:bold}");
        sb.append("table.items td.num{text-align:right}");
        sb.append(".totals{text-align:right;margin-top:8px}");
        sb.append(".totals td{padding:3px 8px}");
        sb.append(".totals .label{font-weight:bold}");
        sb.append(".footer{margin-top:30px;display:flex;justify-content:space-between}");
        sb.append(".footer .sign{text-align:center;width:200px}");
        sb.append("</style></head><body>");

        // Header
        sb.append("<div class='header'>");
        sb.append("<h2>ĐƠN ĐẶT HÀNG</h2>");
        sb.append("<p>Mã đơn: <strong>").append(escapeHtml(order.getOrderCode())).append("</strong></p>");
        sb.append("</div>");

        // Thông tin đại lý và đơn hàng
        sb.append("<table class='info-table'><tr>");
        sb.append("<td><table class='info-table'>");
        sb.append("<tr><td class='label'>Đại lý:</td><td>").append(dealer != null ? escapeHtml(dealer.getName()) : "").append("</td></tr>");
        sb.append("<tr><td class='label'>Mã đại lý:</td><td>").append(dealer != null ? escapeHtml(dealer.getCode()) : "").append("</td></tr>");
        sb.append("<tr><td class='label'>Địa chỉ:</td><td>").append(dealer != null && dealer.getAddress() != null ? escapeHtml(dealer.getAddress()) : "").append("</td></tr>");
        sb.append("<tr><td class='label'>Điện thoại:</td><td>").append(dealer != null && dealer.getPhone() != null ? escapeHtml(dealer.getPhone()) : "").append("</td></tr>");
        sb.append("</table></td>");
        sb.append("<td><table class='info-table'>");
        sb.append("<tr><td class='label'>Ngày tạo:</td><td>").append(order.getCreatedAt() != null ? order.getCreatedAt().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")) : "").append("</td></tr>");
        sb.append("<tr><td class='label'>Ngày giao dự kiến:</td><td>").append(order.getExpectedDeliveryDate() != null ? order.getExpectedDeliveryDate().format(DateTimeFormatter.ofPattern("dd/MM/yyyy")) : "").append("</td></tr>");
        sb.append("<tr><td class='label'>Người tạo:</td><td>").append(order.getCreatedBy() != null ? escapeHtml(order.getCreatedBy()) : "").append("</td></tr>");
        sb.append("<tr><td class='label'>Trạng thái:</td><td>").append(escapeHtml(translateStatus(order.getStatus()))).append("</td></tr>");
        if (order.getPriceList() != null) {
            sb.append("<tr><td class='label'>Bảng giá:</td><td>").append(escapeHtml(order.getPriceList().getCode())).append("</td></tr>");
        }
        sb.append("</table></td>");
        sb.append("</tr></table>");

        // Bảng dòng hàng
        sb.append("<table class='items'><thead><tr>");
        sb.append("<th>STT</th><th>Mã SP</th><th>Tên sản phẩm</th><th>ĐVT</th><th class='num'>SL</th><th class='num'>Đơn giá</th><th class='num'>Chiết khấu</th><th class='num'>Thành tiền</th>");
        sb.append("</tr></thead><tbody>");

        java.text.DecimalFormat df = new java.text.DecimalFormat("#,###");
        int i = 1;
        if (order.getItems() != null) {
            for (OrderItem item : order.getItems()) {
                sb.append("<tr>");
                sb.append("<td>").append(i++).append("</td>");
                sb.append("<td>").append(item.getProduct() != null ? escapeHtml(item.getProduct().getCode()) : "").append("</td>");
                sb.append("<td>").append(item.getProduct() != null ? escapeHtml(item.getProduct().getName()) : "").append("</td>");
                sb.append("<td>").append(item.getUnit() != null ? escapeHtml(item.getUnit()) : "").append("</td>");
                sb.append("<td class='num'>").append(item.getQuantity() != null ? df.format(item.getQuantity()) : "").append("</td>");
                sb.append("<td class='num'>").append(item.getPrice() != null ? df.format(item.getPrice()) : "").append("</td>");
                sb.append("<td class='num'>").append(item.getDiscountAmount() != null && item.getDiscountAmount().compareTo(BigDecimal.ZERO) > 0 ? df.format(item.getDiscountAmount()) : "-").append("</td>");
                sb.append("<td class='num'>").append(item.getTotalAmount() != null ? df.format(item.getTotalAmount()) : "").append("</td>");
                sb.append("</tr>");
            }
        }
        sb.append("</tbody></table>");

        // Tổng tiền
        sb.append("<table class='totals' style='margin-left:auto'>");
        sb.append("<tr><td class='label'>Tổng tiền hàng:</td><td>").append(df.format(order.getSubtotalAmount() != null ? order.getSubtotalAmount() : BigDecimal.ZERO)).append(" đ</td></tr>");
        sb.append("<tr><td class='label'>Chiết khấu:</td><td>-").append(df.format(order.getDiscountAmount() != null ? order.getDiscountAmount() : BigDecimal.ZERO)).append(" đ</td></tr>");
        sb.append("<tr><td class='label' style='font-size:15px'>Tổng thanh toán:</td><td style='font-size:15px;font-weight:bold'>").append(df.format(order.getTotalAmount() != null ? order.getTotalAmount() : BigDecimal.ZERO)).append(" đ</td></tr>");
        sb.append("</table>");

        if (order.getNote() != null && !order.getNote().isBlank()) {
            sb.append("<p><strong>Ghi chú:</strong> ").append(escapeHtml(order.getNote())).append("</p>");
        }

        // Chữ ký
        sb.append("<div class='footer'>");
        sb.append("<div class='sign'><p>Người đặt hàng</p><br/><br/><p>(Ký, ghi rõ họ tên)</p></div>");
        sb.append("<div class='sign'><p>Người giao hàng</p><br/><br/><p>(Ký, ghi rõ họ tên)</p></div>");
        sb.append("<div class='sign'><p>Người nhận hàng</p><br/><br/><p>(Ký, ghi rõ họ tên)</p></div>");
        sb.append("</div>");

        sb.append("</body></html>");
        return sb.toString();
    }

    private String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private String translateStatus(String status) {
        if (status == null) return "";
        return switch (status.toUpperCase()) {
            case "RECORDED" -> "Đã ghi sổ";
            case "PENDING_APPROVAL" -> "Chờ duyệt";
            case "APPROVED" -> "Đã duyệt";
            case "REJECTED" -> "Từ chối";
            case "RETURNED" -> "Trả lại sửa";
            case "CANCELLED" -> "Đã huỷ";
            case "EXPORTED" -> "Đã xuất kho";
            case "DELIVERED" -> "Đã giao hàng";
            default -> status;
        };
    }

    @Override
    @Transactional
    public OrderResponse approveOrder(Long id, String comment, String username) {
        return approveOrderInternal(id, null, username,
                comment == null || comment.isBlank() ? "Đã duyệt đơn hàng" : comment.trim());
    }

    @Override
    @Transactional
    public OrderResponse approveOrder(Long id, Integer reservationHours, String username) {
        return approveOrderInternal(id, reservationHours, username, null);
    }

    @Override
    public byte[] generateOrderPdf(Long id, String username) {
        return getPdfService().generateOrderPdf(id, username);
    }

    @Override
    public byte[] generateOrderBarcode(Long id, String username) {
        return getPdfService().generateOrderBarcode(id, username);
    }

    @Override
    @Transactional(readOnly = true)
    public DealerPortalReorderPreviewResponse getPortalReorderPreview(Long sourceOrderId, String username) {
        Order sourceOrder = orderRepository.findById(sourceOrderId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn hàng với ID: " + sourceOrderId));

        validateOrderViewAccess(sourceOrder, username);

        Dealer dealer = sourceOrder.getDealer();
        if (dealer == null) {
            throw new IllegalArgumentException("Đơn hàng không gắn với đại lý cụ thể, không thể thực hiện xem trước đặt lại.");
        }
        if (Boolean.TRUE.equals(dealer.getLocked())) {
            throw new DealerLockedException(dealer.getCode(), dealer.getName(), dealer.getLockReason());
        }
        if (Boolean.FALSE.equals(dealer.getActive())) {
            throw new IllegalArgumentException(String.format("Đại lý '%s' (%s) đã ngừng giao dịch, không thể đặt lại đơn hàng.",
                    dealer.getName(), dealer.getCode()));
        }

        CustomerGroup customerGroup = sourceOrder.getCustomerGroup() != null
                ? sourceOrder.getCustomerGroup()
                : dealer.getCustomerGroup();

        LocalDate today = LocalDate.now();
        List<PriceList> effectivePriceLists = priceListRepository.findEffectivePriceLists(customerGroup, today);
        PriceList priceList = effectivePriceLists.isEmpty() ? null : effectivePriceLists.get(0);

        Map<Long, PriceListItem> priceItemMap = new HashMap<>();
        if (priceList != null && priceList.getItems() != null) {
            for (PriceListItem pli : priceList.getItems()) {
                if (pli.getProduct() != null) {
                    priceItemMap.put(pli.getProduct().getId(), pli);
                }
            }
        }

        List<DealerPortalReorderItemResponse> availableItems = new ArrayList<>();
        List<DealerPortalDiscontinuedItemResponse> discontinuedItems = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (sourceOrder.getItems() != null) {
            for (OrderItem item : sourceOrder.getItems()) {
                Product product = item.getProduct();
                if (product == null || Boolean.FALSE.equals(product.getActive())) {
                    String pName = product != null ? product.getName() : "Sản phẩm ngừng kinh doanh";
                    String pCode = product != null ? product.getCode() : "—";
                    discontinuedItems.add(DealerPortalDiscontinuedItemResponse.builder()
                            .sourceItemId(item.getId())
                            .productId(product != null ? product.getId() : null)
                            .productCode(pCode)
                            .productName(pName)
                            .unit(item.getUnit())
                            .quantity(item.getQuantity())
                            .reason("Mặt hàng đã ngừng kinh doanh")
                            .build());
                    warnings.add(String.format("Mặt hàng '%s' (mã: %s) đã ngừng kinh doanh và được loại khỏi đơn mới.", pName, pCode));
                    continue;
                }

                if (priceList == null || !priceItemMap.containsKey(product.getId())) {
                    discontinuedItems.add(DealerPortalDiscontinuedItemResponse.builder()
                            .sourceItemId(item.getId())
                            .productId(product.getId())
                            .productCode(product.getCode())
                            .productName(product.getName())
                            .unit(item.getUnit())
                            .quantity(item.getQuantity())
                            .reason("Mặt hàng không có trong bảng giá hiệu lực hiện hành")
                            .build());
                    warnings.add(String.format("Mặt hàng '%s' (mã: %s) không có trong bảng giá hiệu lực hiện hành và được loại khỏi đơn mới.", product.getName(), product.getCode()));
                    continue;
                }

                PriceListItem pli = priceItemMap.get(product.getId());
                String unit = item.getUnit() != null && !item.getUnit().isBlank() ? item.getUnit().trim() : product.getUnit();
                BigDecimal factor;
                try {
                    factor = unitConversionService.getConversionFactor(product, unit);
                } catch (Exception e) {
                    discontinuedItems.add(DealerPortalDiscontinuedItemResponse.builder()
                            .sourceItemId(item.getId())
                            .productId(product.getId())
                            .productCode(product.getCode())
                            .productName(product.getName())
                            .unit(unit)
                            .quantity(item.getQuantity())
                            .reason("Đơn vị tính '" + unit + "' không còn khả dụng cho sản phẩm này")
                            .build());
                    warnings.add(String.format("Đơn vị tính '%s' của sản phẩm '%s' không còn khả dụng và dòng hàng đã được loại bỏ.", unit, product.getName()));
                    continue;
                }

                BigDecimal currentUnitPrice = pli.getSellingPrice().multiply(factor);
                BigDecimal currentFloorPrice = pli.getFloorPrice() != null ? pli.getFloorPrice().multiply(factor) : null;
                BigDecimal subtotal = currentUnitPrice.multiply(item.getQuantity());

                List<DealerPortalCatalogItemResponse.PackagingUnitInfo> units = new ArrayList<>();
                units.add(DealerPortalCatalogItemResponse.PackagingUnitInfo.builder()
                        .unitName(product.getUnit())
                        .factor(BigDecimal.ONE)
                        .price(pli.getSellingPrice())
                        .build());
                try {
                    List<UnitConversionResponse> conversions = unitConversionService.getConversionsByProductId(product.getId());
                    if (conversions != null) {
                        for (UnitConversionResponse uc : conversions) {
                            if (Boolean.TRUE.equals(uc.getIsBaseUnit()) || product.getUnit().equalsIgnoreCase(uc.getUnitName())) continue;
                            units.add(DealerPortalCatalogItemResponse.PackagingUnitInfo.builder()
                                    .unitName(uc.getUnitName())
                                    .factor(uc.getConversionFactor())
                                    .price(pli.getSellingPrice().multiply(uc.getConversionFactor()))
                                    .build());
                        }
                    }
                } catch (Exception ignored) {
                }

                availableItems.add(DealerPortalReorderItemResponse.builder()
                        .sourceItemId(item.getId())
                        .productId(product.getId())
                        .productCode(product.getCode())
                        .productName(product.getName())
                        .unit(unit)
                        .baseUnit(product.getUnit())
                        .conversionFactor(factor)
                        .quantity(item.getQuantity())
                        .oldPrice(item.getPrice())
                        .currentPrice(currentUnitPrice)
                        .currentFloorPrice(currentFloorPrice)
                        .subtotalAmount(subtotal)
                        .units(units)
                        .build());
            }
        }

        boolean canReorder = !availableItems.isEmpty();
        if (!canReorder) {
            warnings.add("Không có mặt hàng nào trong đơn cũ có thể đặt lại.");
        }

        OrderCalculateResponse pricing = null;
        if (canReorder) {
            try {
                List<OrderItemRequest> calcItems = availableItems.stream().map(it -> OrderItemRequest.builder()
                        .productId(it.getProductId())
                        .productCode(it.getProductCode())
                        .unit(it.getUnit())
                        .quantity(it.getQuantity())
                        .build()).toList();
                pricing = calculateOrder(OrderCalculateRequest.builder()
                        .dealerId(dealer.getId())
                        .orderDate(today)
                        .items(calcItems)
                        .build());
            } catch (Exception ignored) {
            }
        }

        DealerDebtResponse debt = null;
        if (dealerDebtService != null) {
            try {
                debt = dealerDebtService.getDealerDebtInfo(dealer);
            } catch (Exception ignored) {
            }
        }

        return DealerPortalReorderPreviewResponse.builder()
                .sourceOrderId(sourceOrder.getId())
                .sourceOrderCode(sourceOrder.getOrderCode())
                .dealerId(dealer.getId())
                .dealerCode(dealer.getCode())
                .dealerName(dealer.getName())
                .deliveryPointId(sourceOrder.getDeliveryPoint() != null ? sourceOrder.getDeliveryPoint().getId() : null)
                .deliveryPointName(sourceOrder.getDeliveryPoint() != null ? sourceOrder.getDeliveryPoint().getReceiverName() : null)
                .deliveryAddress(sourceOrder.getDeliveryPoint() != null ? sourceOrder.getDeliveryPoint().getAddress() : dealer.getAddress())
                .defaultExpectedDeliveryDate(today.plusDays(3))
                .availableItems(availableItems)
                .discontinuedItems(discontinuedItems)
                .warnings(warnings)
                .canReorder(canReorder)
                .pricing(pricing)
                .debt(debt)
                .build();
    }

    @Override
    @Transactional
    public DealerPortalReorderResponse createPortalReorder(Long sourceOrderId, DealerPortalReorderRequest request, String username) {
        Order sourceOrder = orderRepository.findById(sourceOrderId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn hàng với ID: " + sourceOrderId));

        validateOrderViewAccess(sourceOrder, username);

        Dealer dealer = sourceOrder.getDealer();
        if (dealer == null) {
            throw new IllegalArgumentException("Đơn hàng không gắn với đại lý cụ thể, không thể thực hiện đặt lại.");
        }
        if (Boolean.TRUE.equals(dealer.getLocked())) {
            throw new DealerLockedException(dealer.getCode(), dealer.getName(), dealer.getLockReason());
        }
        if (Boolean.FALSE.equals(dealer.getActive())) {
            throw new IllegalArgumentException(String.format("Đại lý '%s' (%s) đã ngừng giao dịch, không thể đặt lại đơn hàng.",
                    dealer.getName(), dealer.getCode()));
        }

        CustomerGroup customerGroup = sourceOrder.getCustomerGroup() != null
                ? sourceOrder.getCustomerGroup()
                : dealer.getCustomerGroup();

        LocalDate today = LocalDate.now();
        List<PriceList> effectivePriceLists = priceListRepository.findEffectivePriceLists(customerGroup, today);
        PriceList priceList = effectivePriceLists.isEmpty() ? null : effectivePriceLists.get(0);

        Map<Long, PriceListItem> priceItemMap = new HashMap<>();
        if (priceList != null && priceList.getItems() != null) {
            for (PriceListItem pli : priceList.getItems()) {
                if (pli.getProduct() != null) {
                    priceItemMap.put(pli.getProduct().getId(), pli);
                }
            }
        }

        List<DealerPortalDiscontinuedItemResponse> discontinuedItems = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<OrderItemRequest> validItems = new ArrayList<>();

        boolean isPartialSelection = request != null && request.getItems() != null && !request.getItems().isEmpty();

        if (isPartialSelection) {
            for (DealerPortalReorderItemRequest itemReq : request.getItems()) {
                Product product = null;
                if (itemReq.getProductId() != null) {
                    product = productRepository.findById(itemReq.getProductId()).orElse(null);
                } else if (itemReq.getProductCode() != null && !itemReq.getProductCode().isBlank()) {
                    product = productRepository.findByCode(itemReq.getProductCode().trim().toUpperCase()).orElse(null);
                }

                if (product == null || Boolean.FALSE.equals(product.getActive())) {
                    String pName = product != null ? product.getName() : "Sản phẩm ngừng kinh doanh";
                    String pCode = product != null ? product.getCode() : (itemReq.getProductCode() != null ? itemReq.getProductCode() : "—");
                    discontinuedItems.add(DealerPortalDiscontinuedItemResponse.builder()
                            .productId(product != null ? product.getId() : itemReq.getProductId())
                            .productCode(pCode)
                            .productName(pName)
                            .unit(itemReq.getUnit())
                            .quantity(itemReq.getQuantity())
                            .reason("Mặt hàng đã ngừng kinh doanh")
                            .build());
                    warnings.add(String.format("Mặt hàng '%s' (mã: %s) đã ngừng kinh doanh và được loại khỏi đơn mới.", pName, pCode));
                    continue;
                }

                if (priceList == null || !priceItemMap.containsKey(product.getId())) {
                    discontinuedItems.add(DealerPortalDiscontinuedItemResponse.builder()
                            .productId(product.getId())
                            .productCode(product.getCode())
                            .productName(product.getName())
                            .unit(itemReq.getUnit())
                            .quantity(itemReq.getQuantity())
                            .reason("Mặt hàng không có trong bảng giá hiệu lực hiện hành")
                            .build());
                    warnings.add(String.format("Mặt hàng '%s' (mã: %s) không có trong bảng giá hiệu lực hiện hành và được loại khỏi đơn mới.", product.getName(), product.getCode()));
                    continue;
                }

                String unit = itemReq.getUnit() != null && !itemReq.getUnit().isBlank() ? itemReq.getUnit().trim() : product.getUnit();
                validItems.add(OrderItemRequest.builder()
                        .productId(product.getId())
                        .productCode(product.getCode())
                        .unit(unit)
                        .quantity(itemReq.getQuantity())
                        .note(itemReq.getNote())
                        .build());
            }
        } else {
            if (sourceOrder.getItems() != null) {
                for (OrderItem item : sourceOrder.getItems()) {
                    Product product = item.getProduct();
                    if (product == null || Boolean.FALSE.equals(product.getActive())) {
                        String pName = product != null ? product.getName() : "Sản phẩm ngừng kinh doanh";
                        String pCode = product != null ? product.getCode() : "—";
                        discontinuedItems.add(DealerPortalDiscontinuedItemResponse.builder()
                                .sourceItemId(item.getId())
                                .productId(product != null ? product.getId() : null)
                                .productCode(pCode)
                                .productName(pName)
                                .unit(item.getUnit())
                                .quantity(item.getQuantity())
                                .reason("Mặt hàng đã ngừng kinh doanh")
                                .build());
                        warnings.add(String.format("Mặt hàng '%s' (mã: %s) đã ngừng kinh doanh và được loại khỏi đơn mới.", pName, pCode));
                        continue;
                    }

                    if (priceList == null || !priceItemMap.containsKey(product.getId())) {
                        discontinuedItems.add(DealerPortalDiscontinuedItemResponse.builder()
                                .sourceItemId(item.getId())
                                .productId(product.getId())
                                .productCode(product.getCode())
                                .productName(product.getName())
                                .unit(item.getUnit())
                                .quantity(item.getQuantity())
                                .reason("Mặt hàng không có trong bảng giá hiệu lực hiện hành")
                                .build());
                        warnings.add(String.format("Mặt hàng '%s' (mã: %s) không có trong bảng giá hiệu lực hiện hành và được loại khỏi đơn mới.", product.getName(), product.getCode()));
                        continue;
                    }

                    String unit = item.getUnit() != null && !item.getUnit().isBlank() ? item.getUnit().trim() : product.getUnit();
                    validItems.add(OrderItemRequest.builder()
                            .productId(product.getId())
                            .productCode(product.getCode())
                            .unit(unit)
                            .quantity(item.getQuantity())
                            .note(item.getNote())
                            .build());
                }
            }
        }

        if (validItems.isEmpty()) {
            throw new IllegalArgumentException("Tất cả mặt hàng được chọn đều đã ngừng kinh doanh hoặc không còn trong bảng giá hiệu lực hiện hành, không thể đặt lại đơn.");
        }

        Long deliveryPointId = request != null && request.getDeliveryPointId() != null
                ? request.getDeliveryPointId()
                : (sourceOrder.getDeliveryPoint() != null ? sourceOrder.getDeliveryPoint().getId() : null);

        LocalDate expectedDeliveryDate = request != null && request.getExpectedDeliveryDate() != null
                ? request.getExpectedDeliveryDate()
                : today.plusDays(3);

        String note = request != null && request.getNote() != null && !request.getNote().isBlank()
                ? request.getNote()
                : (sourceOrder.getNote() != null && !sourceOrder.getNote().isBlank()
                    ? "[Đặt lại từ đơn " + sourceOrder.getOrderCode() + "] " + sourceOrder.getNote()
                    : "[Đặt lại từ đơn " + sourceOrder.getOrderCode() + "]");

        OrderRequest orderRequest = OrderRequest.builder()
                .dealerId(dealer.getId())
                .deliveryPointId(deliveryPointId)
                .expectedDeliveryDate(expectedDeliveryDate)
                .note(note)
                .items(validItems)
                .build();

        OrderResponse createdOrder = createPortalOrder(orderRequest, username);

        auditLogService.logAction("INVOICE", createdOrder.getOrderCode(), "REORDER_FROM",
                sourceOrder.getOrderCode(), createdOrder.getStatus(), username != null ? username : "system");

        return DealerPortalReorderResponse.builder()
                .order(createdOrder)
                .excludedItems(discontinuedItems)
                .warnings(warnings)
                .build();
    }
}
