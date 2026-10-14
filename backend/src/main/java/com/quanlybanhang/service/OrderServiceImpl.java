package com.quanlybanhang.service;

import com.quanlybanhang.dto.request.OrderCalculateRequest;
import com.quanlybanhang.dto.request.OrderItemRequest;
import com.quanlybanhang.dto.request.OrderLineItemPricingRequest;
import com.quanlybanhang.dto.request.OrderRequest;
import com.quanlybanhang.dto.response.*;
import com.quanlybanhang.exception.DealerLockedException;
import com.quanlybanhang.exception.OrderCannotBeCancelledException;
import com.quanlybanhang.model.*;
import com.quanlybanhang.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final DealerRepository dealerRepository;
    private final DealerDeliveryPointRepository deliveryPointRepository;
    private final PriceListRepository priceListRepository;
    private final UnitConversionService unitConversionService;
    private final AuditLogService auditLogService;
    private final VolumeDiscountPolicyService volumeDiscountPolicyService;
    private final DealerDebtService dealerDebtService;
    private final WarehouseRepository warehouseRepository;
    private final WarehouseInventoryRepository inventoryRepository;
    private final OrderStatusHistoryRepository orderStatusHistoryRepository;
    private final UserRepository userRepository;

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

    @Override
    @Transactional
    public OrderResponse createOrder(OrderRequest request, String username) {
        if (request.getItems() == null || request.getItems().isEmpty()) {
            throw new IllegalArgumentException("Đơn hàng phải có ít nhất 1 sản phẩm");
        }

        String orderCode = request.getOrderCode();
        if (orderCode == null || orderCode.isBlank()) {
            orderCode = "DH-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"));
        } else if (orderRepository.existsByOrderCode(orderCode.trim())) {
            throw new IllegalArgumentException("Mã đơn hàng '" + orderCode + "' đã tồn tại");
        }

        // 1. Xác định đại lý và nhóm khách hàng (S2-10)
        Dealer dealer = null;
        CustomerGroup customerGroup = request.getCustomerGroup();

        if (request.getDealerId() != null) {
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

        // 2. Tự động tìm bảng giá hiệu lực phù hợp (S2-10, S4-01)
        PriceList priceList = null;
        if (request.getPriceListId() != null) {
            priceList = priceListRepository.findById(request.getPriceListId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy bảng giá với ID: " + request.getPriceListId()));
            if (customerGroup == null) {
                customerGroup = priceList.getCustomerGroup();
            }
        } else if (customerGroup != null) {
            LocalDate queryDate = request.getExpectedDeliveryDate() != null ? request.getExpectedDeliveryDate() : LocalDate.now();
            List<PriceList> effectivePriceLists = priceListRepository.findEffectivePriceLists(customerGroup, queryDate);
            if (!effectivePriceLists.isEmpty()) {
                priceList = effectivePriceLists.get(0);
            }
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
                .expectedDeliveryDate(request.getExpectedDeliveryDate())
                .priceList(priceList)
                .note(request.getNote())
                .status("RECORDED")
                .requiresApproval(false)
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

        // Nếu có sản phẩm bán dưới giá sàn hoặc đơn vượt hạn mức công nợ -> chuyển sang trạng thái CHỜ DUYỆT (PENDING_APPROVAL)
        if (orderRequiresApproval) {
            order.setStatus("PENDING_APPROVAL");
            order.setRequiresApproval(true);
            order.setApprovalReason(String.join("; ", belowFloorReasons));
            order.setInventoryReserved(false);
        } else {
            order.setStatus("RECORDED");
            order.setRequiresApproval(false);
            if (request.getWarehouseId() != null) {
                order.setWarehouse(resolveDefaultWarehouse(request.getWarehouseId()));
                reserveInventory(order);
            }
        }

        Order saved = orderRepository.save(order);
        if (orderRequiresApproval) {
            recordStatusHistory(saved, null, "PENDING_APPROVAL", "Đơn hàng chờ Quản lý kinh doanh duyệt: " + saved.getApprovalReason(), username);
        } else {
            recordStatusHistory(saved, null, "RECORDED", "Đơn hàng đã được duyệt/ghi nhận, đã giữ chỗ tồn kho", username);
        }
        String auditSuffix = orderRequiresApproval ? " (Chờ duyệt: " + order.getApprovalReason() + ")" : "";
        auditLogService.logAction("INVOICE", saved.getOrderCode(), "CREATE", null,
                "Tổng tiền: " + saved.getTotalAmount().toPlainString() + auditSuffix,
                username != null ? username : "system");

        return mapToResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderResponse> getAllOrders() {
        List<Order> orders = orderRepository.findAllByOrderByCreatedAtDesc();
        if (isSalesOnlyUser()) {
            User currentUser = getCurrentUser();
            if (currentUser != null) {
                orders = orders.stream()
                        .filter(o -> (o.getCreatedBy() != null && o.getCreatedBy().equalsIgnoreCase(currentUser.getUsername()))
                                || (o.getDealer() != null && o.getDealer().getAssignedUser() != null
                                && o.getDealer().getAssignedUser().getId().equals(currentUser.getId())))
                        .collect(Collectors.toList());
            }
        }
        return orders.stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public OrderResponse getOrderById(Long id) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn hàng với ID: " + id));
        checkSalesAuthorizationForOrder(order, "xem");
        return mapToResponse(order);
    }

    @Override
    @Transactional
    public OrderResponse cancelOrder(Long id, String reason, String username) {
        if (reason == null || reason.trim().isBlank()) {
            throw new IllegalArgumentException("Lý do huỷ đơn hàng không được để trống");
        }
        String trimmedReason = reason.trim();

        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn hàng với ID: " + id));

        // RLS kiểm tra quyền hạn cho SALES: chỉ huỷ được đơn của mình hoặc đại lý phụ trách
        checkSalesAuthorizationForOrder(order, "huỷ");

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
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn hàng với ID: " + id));

        String oldStatus = order.getStatus();
        String targetStatus = status.trim().toUpperCase();

        if ("CANCELLED".equalsIgnoreCase(oldStatus)) {
            throw new IllegalArgumentException("Không thể cập nhật trạng thái của đơn hàng đã bị huỷ");
        }
        if ("CANCELLED".equalsIgnoreCase(targetStatus)) {
            throw new IllegalArgumentException("Vui lòng sử dụng chức năng huỷ đơn hàng kèm lý do huỷ");
        }

        try {
            OrderStatus.valueOf(targetStatus);
        } catch (IllegalArgumentException e) {
            if (!"PROCESSING".equals(targetStatus) && !"SHIPPED".equals(targetStatus) && !"COMPLETED".equals(targetStatus)) {
                throw new IllegalArgumentException("Trạng thái đơn hàng không hợp lệ: " + status);
            }
        }

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
        int currentStepOrder = resolveStepOrder(currentStatus);

        // Step 1: DRAFT
        steps.add(buildStep(1, "DRAFT", "Nháp", "Đơn hàng đang ở trạng thái nháp",
                resolveStepState(1, currentStepOrder, isCancelled, isRejected, false),
                historyByStatus.get("DRAFT"), order.getCreatedAt(), order.getCreatedBy()));

        // Step 2: PENDING_APPROVAL
        boolean skippedApproval = !wasApprovalRequired && !historyByStatus.containsKey("PENDING_APPROVAL");
        steps.add(buildStep(2, "PENDING_APPROVAL", "Chờ duyệt", "Chờ Quản lý kinh doanh duyệt (giá sàn / công nợ)",
                skippedApproval ? "SKIPPED" : resolveStepState(2, currentStepOrder, isCancelled, isRejected, false),
                historyByStatus.get("PENDING_APPROVAL"), null, null));

        // Step 3: APPROVED
        OrderStatusHistory approvedHist = historyByStatus.get("RECORDED");
        if (approvedHist == null) approvedHist = historyByStatus.get("APPROVED");
        steps.add(buildStep(3, "APPROVED", "Đã duyệt", "Đơn hàng đã được duyệt, sẵn sàng soạn hàng",
                resolveStepState(3, currentStepOrder, isCancelled, isRejected, false),
                approvedHist, order.getRecordedAt(), order.getApprovedBy() != null ? order.getApprovedBy() : order.getCreatedBy()));

        // Step 4: PICKING
        OrderStatusHistory pickingHist = historyByStatus.get("PICKING");
        if (pickingHist == null) pickingHist = historyByStatus.get("PROCESSING");
        steps.add(buildStep(4, "PICKING", "Đang soạn hàng", "Kho đang thực hiện soạn hàng và đóng gói",
                resolveStepState(4, currentStepOrder, isCancelled, isRejected, false),
                pickingHist, null, null));

        // Step 5: EXPORTED
        OrderStatusHistory exportedHist = historyByStatus.get("EXPORTED");
        if (exportedHist == null) exportedHist = historyByStatus.get("SHIPPED");
        steps.add(buildStep(5, "EXPORTED", "Đã xuất", "Đã xuất kho, đang vận chuyển đến đại lý",
                resolveStepState(5, currentStepOrder, isCancelled, isRejected, false),
                exportedHist, null, null));

        // Step 6: DELIVERED
        steps.add(buildStep(6, "DELIVERED", "Đã giao", "Đã giao hàng thành công đến đại lý",
                resolveStepState(6, currentStepOrder, isCancelled, isRejected, false),
                historyByStatus.get("DELIVERED"), null, null));

        // Step 7: CLOSED
        OrderStatusHistory closedHist = historyByStatus.get("CLOSED");
        if (closedHist == null) closedHist = historyByStatus.get("COMPLETED");
        steps.add(buildStep(7, "CLOSED", "Đóng", "Đơn hàng hoàn tất toàn bộ chu trình và đóng",
                resolveStepState(7, currentStepOrder, isCancelled, isRejected, false),
                closedHist, null, null));

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
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn hàng với ID: " + id));

        if (!"PENDING_APPROVAL".equalsIgnoreCase(order.getStatus())) {
            throw new IllegalArgumentException("Chỉ có thể phê duyệt đơn hàng đang ở trạng thái Chờ duyệt (PENDING_APPROVAL)");
        }

        order.setStatus("RECORDED");
        order.setApprovedBy(username != null ? username : "system");
        order.setApprovedAt(LocalDateTime.now());
        if (order.getWarehouse() != null) {
            reserveInventory(order);
        }

        Order saved = orderRepository.save(order);
        recordStatusHistory(saved, "PENDING_APPROVAL", "RECORDED", "Quản lý kinh doanh phê duyệt đơn hàng, đã giữ chỗ tồn kho", username);

        Dealer dealer = order.getDealer();
        boolean dealerLocked = dealer != null && Boolean.TRUE.equals(dealer.getLocked());
        String auditDetail = "Quản lý kinh doanh duyệt đơn bán dưới giá sàn" +
                (dealerLocked ? " [Cảnh báo: Đại lý '" + dealer.getName() + "' đang bị khoá giao dịch. Lý do: " + dealer.getLockReason() + "]" : "");
        auditLogService.logAction("INVOICE", saved.getOrderCode(), "APPROVE", "PENDING_APPROVAL",
                auditDetail, username != null ? username : "system");
        return mapToResponse(saved);
    }

    @Override
    @Transactional
    public OrderResponse rejectOrder(Long id, String reason, String username) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn hàng với ID: " + id));

        if (!"PENDING_APPROVAL".equalsIgnoreCase(order.getStatus())) {
            throw new IllegalArgumentException("Chỉ có thể từ chối đơn hàng đang ở trạng thái Chờ duyệt (PENDING_APPROVAL)");
        }

        order.setStatus("REJECTED");
        order.setApprovedBy(username != null ? username : "system");
        order.setApprovedAt(LocalDateTime.now());
        if (reason != null && !reason.isBlank()) {
            order.setNote((order.getNote() != null ? order.getNote() + " | " : "") + "Lý do từ chối: " + reason.trim());
        }

        Order saved = orderRepository.save(order);
        recordStatusHistory(saved, "PENDING_APPROVAL", "REJECTED", "Từ chối duyệt: " + (reason != null ? reason : ""), username);
        auditLogService.logAction("INVOICE", saved.getOrderCode(), "REJECT", "PENDING_APPROVAL",
                "Từ chối đơn bán dưới giá sàn: " + (reason != null ? reason : ""), username != null ? username : "system");
        return mapToResponse(saved);
    }

    private Product resolveProduct(OrderItemRequest itemReq) {
        if (itemReq.getProductId() != null) {
            return productRepository.findById(itemReq.getProductId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy sản phẩm với ID: " + itemReq.getProductId()));
        }
        if (itemReq.getProductCode() != null && !itemReq.getProductCode().isBlank()) {
            return productRepository.findByCode(itemReq.getProductCode().trim().toUpperCase())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy sản phẩm với mã: " + itemReq.getProductCode()));
        }
        throw new IllegalArgumentException("Cần cung cấp productId hoặc productCode");
    }

    private OrderResponse mapToResponse(Order order) {
        List<OrderItemResponse> items = order.getItems() != null
                ? order.getItems().stream().map(this::mapToItemResponse).collect(Collectors.toList())
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

        return OrderResponse.builder()
                .id(order.getId())
                .orderCode(order.getOrderCode())
                .customerName(order.getCustomerName())
                .customerGroup(order.getCustomerGroup())
                .dealerId(order.getDealer() != null ? order.getDealer().getId() : null)
                .deliveryPointId(order.getDeliveryPoint() != null ? order.getDeliveryPoint().getId() : null)
                .expectedDeliveryDate(order.getExpectedDeliveryDate())
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
                .requiresApproval(order.getRequiresApproval())
                .approvalReason(order.getApprovalReason())
                .approvedBy(order.getApprovedBy())
                .approvedAt(order.getApprovedAt())
                .subtotalAmount(order.getSubtotalAmount() != null ? order.getSubtotalAmount() : order.getTotalAmount())
                .discountAmount(order.getDiscountAmount() != null ? order.getDiscountAmount() : BigDecimal.ZERO)
                .totalAmount(order.getTotalAmount())
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
                .build();
    }

    private String formatMoney(BigDecimal amount) {
        if (amount == null) return "0 đ";
        return new java.text.DecimalFormat("#,### đ").format(amount);
    }

    private OrderItemResponse mapToItemResponse(OrderItem item) {
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
                .price(item.getPrice())
                .floorPrice(item.getFloorPrice())
                .belowFloorPrice(item.getBelowFloorPrice())
                .subtotalAmount(item.getSubtotalAmount() != null ? item.getSubtotalAmount() : item.getTotalAmount())
                .discountAmount(item.getDiscountAmount() != null ? item.getDiscountAmount() : BigDecimal.ZERO)
                .discountPercent(item.getDiscountPercent())
                .appliedPolicyId(item.getAppliedPolicyId())
                .appliedPolicyCode(item.getAppliedPolicyCode())
                .appliedPolicyName(item.getAppliedPolicyName())
                .totalAmount(item.getTotalAmount())
                .note(item.getNote())
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

        PriceList priceList = null;
        LocalDate orderDate = request.getOrderDate() != null ? request.getOrderDate() : LocalDate.now();

        if (request.getPriceListId() != null) {
            priceList = priceListRepository.findById(request.getPriceListId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy bảng giá với ID: " + request.getPriceListId()));
            if (customerGroup == null) {
                customerGroup = priceList.getCustomerGroup();
            }
        } else if (customerGroup != null) {
            List<PriceList> effectivePriceLists = priceListRepository.findEffectivePriceLists(customerGroup, orderDate);
            if (!effectivePriceLists.isEmpty()) {
                priceList = effectivePriceLists.get(0);
            } else {
                throw new IllegalArgumentException(String.format("Không tìm thấy bảng giá hiệu lực cho nhóm khách hàng '%s'. Không thể thêm dòng hàng.",
                        customerGroup.getDisplayName()));
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
                    .build());
        }

        return OrderCalculateResponse.builder()
                .dealerId(dealer != null ? dealer.getId() : null)
                .dealerName(dealer != null ? dealer.getName() : null)
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

    private boolean isSalesOnlyUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return false;
        }
        boolean hasSales = auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_SALES".equals(a.getAuthority()));
        boolean hasManagerOrAdmin = auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority())
                        || "ROLE_SALES_MANAGER".equals(a.getAuthority())
                        || "ROLE_ACCOUNTANT".equals(a.getAuthority())
                        || "ROLE_WAREHOUSE_MANAGER".equals(a.getAuthority())
                        || "ROLE_WAREHOUSE_STAFF".equals(a.getAuthority())
                        || "ROLE_VIEWER".equals(a.getAuthority()));
        return hasSales && !hasManagerOrAdmin;
    }

    private User getCurrentUser() {
        if (userRepository == null) {
            return null;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null) {
            return null;
        }
        return userRepository.findByUsername(auth.getName()).orElse(null);
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

    private void reserveInventory(Order order) {
        if (Boolean.TRUE.equals(order.getInventoryReserved())) {
            return;
        }
        if (warehouseRepository == null || inventoryRepository == null) {
            order.setInventoryReserved(true);
            return;
        }
        Warehouse warehouse = order.getWarehouse();
        if (warehouse == null) {
            return;
        }
        if (warehouse != null && order.getItems() != null) {
            for (OrderItem item : order.getItems()) {
                if (item.getProduct() != null && item.getBaseQuantity() != null && item.getBaseQuantity().compareTo(BigDecimal.ZERO) > 0) {
                    Product product = item.getProduct();
                    BigDecimal baseQty = item.getBaseQuantity();
                    WarehouseInventory inventory = inventoryRepository
                            .findByWarehouseIdAndProductId(warehouse.getId(), product.getId())
                            .orElseGet(() -> WarehouseInventory.builder()
                                    .warehouse(order.getWarehouse())
                                    .product(product)
                                    .baseQuantity(BigDecimal.ZERO)
                                    .reservedQuantity(BigDecimal.ZERO)
                                    .baseUnit(product.getUnit())
                                    .build());
                    inventory.addReservedQuantity(baseQty);
                    inventoryRepository.save(inventory);
                }
            }
            order.setInventoryReserved(true);
        }
    }

    private void releaseReservedInventory(Order order) {
        if (!Boolean.TRUE.equals(order.getInventoryReserved())) {
            return;
        }
        if (warehouseRepository == null || inventoryRepository == null) {
            order.setInventoryReserved(false);
            return;
        }
        Warehouse warehouse = order.getWarehouse();
        if (warehouse == null) {
            warehouse = resolveDefaultWarehouse(null);
        }
        if (warehouse != null && order.getItems() != null) {
            for (OrderItem item : order.getItems()) {
                if (item.getProduct() != null && item.getBaseQuantity() != null && item.getBaseQuantity().compareTo(BigDecimal.ZERO) > 0) {
                    Product product = item.getProduct();
                    inventoryRepository.findByWarehouseIdAndProductId(warehouse.getId(), product.getId())
                            .ifPresent(inv -> {
                                inv.subtractReservedQuantity(item.getBaseQuantity());
                                inventoryRepository.save(inv);
                            });
                }
            }
        }
        order.setInventoryReserved(false);
    }

    private void deductInventoryOnExport(Order order) {
        if (warehouseRepository == null || inventoryRepository == null) {
            order.setInventoryReserved(false);
            return;
        }
        Warehouse warehouse = order.getWarehouse();
        if (warehouse == null) {
            warehouse = resolveDefaultWarehouse(null);
            if (warehouse != null) {
                order.setWarehouse(warehouse);
            }
        }
        if (warehouse != null && order.getItems() != null) {
            for (OrderItem item : order.getItems()) {
                if (item.getProduct() != null && item.getBaseQuantity() != null && item.getBaseQuantity().compareTo(BigDecimal.ZERO) > 0) {
                    Product product = item.getProduct();
                    BigDecimal baseQty = item.getBaseQuantity();
                    WarehouseInventory inv = inventoryRepository
                            .findByWarehouseIdAndProductId(warehouse.getId(), product.getId())
                            .orElseGet(() -> WarehouseInventory.builder()
                                    .warehouse(order.getWarehouse())
                                    .product(product)
                                    .baseQuantity(BigDecimal.ZERO)
                                    .reservedQuantity(BigDecimal.ZERO)
                                    .baseUnit(product.getUnit())
                                    .build());
                    if (Boolean.TRUE.equals(order.getInventoryReserved())) {
                        inv.subtractReservedQuantity(baseQty);
                    }
                    inv.subtractBaseQuantity(baseQty);
                    inventoryRepository.save(inv);
                }
            }
        }
        order.setInventoryReserved(false);
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
}
