package com.quanlybanhang.service;

import com.quanlybanhang.dto.request.OrderCalculateRequest;
import com.quanlybanhang.dto.request.OrderItemRequest;
import com.quanlybanhang.dto.request.OrderLineItemPricingRequest;
import com.quanlybanhang.dto.request.OrderRequest;
import com.quanlybanhang.dto.response.*;
import com.quanlybanhang.exception.DealerLockedException;
import com.quanlybanhang.model.*;
import com.quanlybanhang.repository.DealerRepository;
import com.quanlybanhang.repository.DealerDeliveryPointRepository;
import com.quanlybanhang.repository.OrderRepository;
import com.quanlybanhang.repository.PriceListRepository;
import com.quanlybanhang.repository.ProductRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.quanlybanhang.model.OrderDraft;
import com.quanlybanhang.repository.OrderDraftRepository;
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
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
    private com.quanlybanhang.repository.UserRepository userRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setUserRepository(com.quanlybanhang.repository.UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Autowired(required = false)
    private OrderDraftRepository orderDraftRepository;

    @Autowired(required = false)
    private ObjectMapper objectMapper;

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
        } else {
            order.setStatus("RECORDED");
            order.setRequiresApproval(false);
        }

        Order saved = orderRepository.save(order);
        String auditSuffix = orderRequiresApproval ? " (Chờ duyệt: " + order.getApprovalReason() + ")" : "";
        auditLogService.logAction("INVOICE", saved.getOrderCode(), "CREATE", null,
                "Tổng tiền: " + saved.getTotalAmount().toPlainString() + auditSuffix,
                username != null ? username : "system");

        return mapToResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderResponse> getAllOrders() {
        return orderRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public OrderResponse getOrderById(Long id) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn hàng với ID: " + id));
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
                    "Bạn không có quyền xem đơn hàng của đại lý khác");
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
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn hàng với ID: " + id));

        String oldStatus = order.getStatus();
        if (request.getStatus() != null && !request.getStatus().isBlank()) {
            order.setStatus(request.getStatus().trim());
        } else if ("RECORDED".equalsIgnoreCase(order.getStatus())) {
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
        auditLogService.logAction("ORDER", saved.getOrderCode(), "DELIVERY_UPDATE",
                oldStatus, saved.getStatus(),
                "Cập nhật giao hàng: ngày " + saved.getActualDeliveryDate() + ", ghi chú: " + (saved.getDeliveryNote() != null ? saved.getDeliveryNote() : "Không"),
                username != null ? username : "system");

        return mapToResponse(saved);
    }

    @Override
    @Transactional
    public OrderResponse cancelOrder(Long id, String username) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn hàng với ID: " + id));
        String oldStatus = order.getStatus();
        order.setStatus("CANCELLED");
        Order saved = orderRepository.save(order);
        auditLogService.logAction("INVOICE", saved.getOrderCode(), "CANCEL", oldStatus,
                saved.getStatus(), username != null ? username : "system");
        return mapToResponse(saved);
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

        Order saved = orderRepository.save(order);
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

        boolean hasShortage = items.stream().anyMatch(i -> Boolean.TRUE.equals(i.getIsShortage()));

        return OrderResponse.builder()
                .id(order.getId())
                .orderCode(order.getOrderCode())
                .customerName(order.getCustomerName())
                .customerGroup(order.getCustomerGroup())
                .dealerId(order.getDealer() != null ? order.getDealer().getId() : null)
                .deliveryPointId(order.getDeliveryPoint() != null ? order.getDeliveryPoint().getId() : null)
                .expectedDeliveryDate(order.getExpectedDeliveryDate())
                .actualDeliveryDate(order.getActualDeliveryDate())
                .deliveryNote(order.getDeliveryNote())
                .hasShortage(hasShortage)
                .totalLineItems(items.size())
                .priceListId(order.getPriceList() != null ? order.getPriceList().getId() : null)
                .priceListCode(order.getPriceList() != null ? order.getPriceList().getCode() : null)
                .status(order.getStatus())
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

        validateSalesAccess(sourceOrder, username);

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

        validateSalesAccess(sourceOrder, username);

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
}
