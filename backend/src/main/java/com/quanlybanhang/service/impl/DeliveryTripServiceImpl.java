package com.quanlybanhang.service.impl;

import com.quanlybanhang.dto.request.*;
import com.quanlybanhang.dto.response.*;
import com.quanlybanhang.model.*;
import com.quanlybanhang.repository.*;
import com.quanlybanhang.service.AuditLogService;
import com.quanlybanhang.service.DeliveryTripService;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * Implementation của DeliveryTripService (S6-07).
 *
 * Nghiệp vụ cốt lõi:
 * 1. Danh sách chuyến giao đang chạy kèm số điểm đã giao và còn lại.
 * 2. Theo dõi tiền hàng đang nằm trên đường (giá trị hàng chưa giao xong).
 * 3. Hàng chưa giao được vẫn thuộc trách nhiệm của chuyến, KHÔNG tự về kho!
 * 4. Cảnh báo chuyến quá thời gian dự kiến (isOverdue, overdueMinutes, overdueWarning).
 * 5. Tuân thủ ma trận phân quyền từ DB (WAREHOUSE_VIEW, WAREHOUSE_EXPORT, ROLE_ADMIN)
 *    và phạm vi kho được giao (warehouse scoping).
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class DeliveryTripServiceImpl implements DeliveryTripService {

    private final DeliveryTripRepository tripRepository;
    private final DeliveryTripStopRepository stopRepository;
    private final WarehouseRepository warehouseRepository;
    private final WarehouseInventoryRepository inventoryRepository;
    private final OrderRepository orderRepository;
    private final DealerRepository dealerRepository;
    private final DealerDeliveryPointRepository deliveryPointRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;

    // ── SCOPE & SECURITY HELPER ──
    private record Scope(boolean global, boolean write, Set<Long> allowedWarehouseIds) {
        boolean contains(Long id) {
            return global || (id != null && allowedWarehouseIds.contains(id));
        }

        void require(Long id) {
            if (!contains(id)) {
                throw new AccessDeniedException("Kho này không thuộc phạm vi được giao");
            }
        }
    }

    private Scope checkScope(boolean mutation) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new AccessDeniedException("Bạn chưa được xác thực");
        }

        Set<String> grants = new HashSet<>();
        auth.getAuthorities().forEach(a -> grants.add(a.getAuthority()));

        boolean admin = grants.contains("ROLE_ADMIN");
        boolean write = admin || grants.contains("WAREHOUSE_EXPORT");
        boolean read = write || grants.contains("WAREHOUSE_VIEW");

        if (mutation && !write) {
            throw new AccessDeniedException("Bạn không có quyền thao tác chuyến giao hàng (yêu cầu WAREHOUSE_EXPORT hoặc ADMIN)");
        }
        if (!mutation && !read) {
            throw new AccessDeniedException("Bạn không có quyền xem chuyến giao hàng (yêu cầu WAREHOUSE_VIEW hoặc WAREHOUSE_EXPORT hoặc ADMIN)");
        }

        if (admin) {
            return new Scope(true, true, Set.of());
        }

        User user = userRepository.findByUsername(auth.getName())
                .orElseThrow(() -> new AccessDeniedException("Không tìm thấy tài khoản người dùng"));

        Set<Long> assignedIds = new HashSet<>();
        if (user.getWarehouses() != null) {
            user.getWarehouses().forEach(w -> assignedIds.add(w.getId()));
        }

        boolean warehouseRole = grants.contains("ROLE_WAREHOUSE_STAFF") || grants.contains("ROLE_WAREHOUSE_MANAGER");
        // Giới hạn dữ liệu theo kho được gán; vai trò kho không được gán kho sẽ rỗng
        return new Scope(assignedIds.isEmpty() && !warehouseRole, write, assignedIds);
    }

    private List<Long> resolveAllowedWarehouseIds(Scope scope) {
        if (scope.global()) {
            return warehouseRepository.findAllByActiveTrueOrderByNameAsc().stream()
                    .map(Warehouse::getId)
                    .toList();
        }
        return new ArrayList<>(scope.allowedWarehouseIds());
    }

    @Override
    @Transactional(readOnly = true)
    public DeliveryTripOptionsResponse getOptions() {
        Scope scope = checkScope(false);
        List<Warehouse> activeWarehouses = warehouseRepository.findAllByActiveTrueOrderByNameAsc().stream()
                .filter(w -> scope.contains(w.getId()))
                .toList();

        List<NamedResourceResponse> warehouseResponses = activeWarehouses.stream()
                .map(w -> new NamedResourceResponse(w.getId(), w.getCode(), w.getName()))
                .toList();

        List<DeliveryTripOptionsResponse.StatusOption> tripStatuses = Arrays.stream(DeliveryTripStatus.values())
                .map(s -> DeliveryTripOptionsResponse.StatusOption.builder()
                        .value(s.name())
                        .label(s.getDisplayName())
                        .build())
                .toList();

        List<DeliveryTripOptionsResponse.StatusOption> stopStatuses = Arrays.stream(DeliveryStopStatus.values())
                .map(s -> DeliveryTripOptionsResponse.StatusOption.builder()
                        .value(s.name())
                        .label(s.getDisplayName())
                        .build())
                .toList();

        return DeliveryTripOptionsResponse.builder()
                .warehouses(warehouseResponses)
                .tripStatuses(tripStatuses)
                .stopStatuses(stopStatuses)
                .canManage(scope.write())
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public Page<DeliveryTripResponse> searchTrips(Long warehouseId, DeliveryTripStatus status,
                                                 Boolean isOverdue, String keyword, int page, int size) {
        Scope scope = checkScope(false);

        if (warehouseId != null) {
            scope.require(warehouseId);
        }

        if (page < 0 || size < 1 || size > 200) {
            throw new IllegalArgumentException("Trang phải không âm; số dòng mỗi trang từ 1 đến 200");
        }

        List<Long> allowedWarehouseIds = warehouseId != null
                ? List.of(warehouseId)
                : resolveAllowedWarehouseIds(scope);

        if (allowedWarehouseIds.isEmpty()) {
            return Page.empty(PageRequest.of(page, size));
        }

        Specification<DeliveryTrip> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            // 1. Phân quyền phạm vi kho
            predicates.add(root.get("warehouse").get("id").in(allowedWarehouseIds));

            // 2. Lọc trạng thái chuyến
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }

            // 3. Lọc cảnh báo quá hạn dự kiến (S6-07)
            if (isOverdue != null) {
                LocalDateTime now = LocalDateTime.now();
                if (Boolean.TRUE.equals(isOverdue)) {
                    predicates.add(cb.equal(root.get("status"), DeliveryTripStatus.IN_PROGRESS));
                    predicates.add(cb.lessThan(root.get("expectedCompletionTime"), now));
                } else {
                    predicates.add(cb.or(
                            cb.notEqual(root.get("status"), DeliveryTripStatus.IN_PROGRESS),
                            cb.greaterThanOrEqualTo(root.get("expectedCompletionTime"), now)
                    ));
                }
            }

            // 4. Tìm kiếm từ khóa
            if (StringUtils.hasText(keyword)) {
                String term = "%" + keyword.trim().toLowerCase(Locale.ROOT)
                        .replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";

                Predicate tripCodeLike = cb.like(cb.lower(root.get("tripCode")), term, '!');
                Predicate driverLike = cb.like(cb.lower(root.get("driverName")), term, '!');
                Predicate plateLike = cb.like(cb.lower(root.get("vehiclePlate")), term, '!');

                predicates.add(cb.or(tripCodeLike, driverLike, plateLike));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };

        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<DeliveryTrip> tripsPage = tripRepository.findAll(spec, pageRequest);

        return tripsPage.map(this::mapToTripResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public DeliveryTripSummaryResponse getSummary(Long warehouseId) {
        Scope scope = checkScope(false);

        if (warehouseId != null) {
            scope.require(warehouseId);
        }

        List<Long> allowedWarehouseIds = warehouseId != null
                ? List.of(warehouseId)
                : resolveAllowedWarehouseIds(scope);

        if (allowedWarehouseIds.isEmpty()) {
            return DeliveryTripSummaryResponse.builder()
                    .totalRunningTrips(0L)
                    .totalOverdueTrips(0L)
                    .totalValueOnRoad(BigDecimal.ZERO)
                    .totalDeliveredValue(BigDecimal.ZERO)
                    .totalTripValue(BigDecimal.ZERO)
                    .totalPoints(0L)
                    .totalDeliveredPoints(0L)
                    .totalRemainingPoints(0L)
                    .totalFailedPoints(0L)
                    .totalPendingPoints(0L)
                    .build();
        }

        List<DeliveryTrip> runningTrips = tripRepository.findAllByWarehouseIdInAndStatus(
                allowedWarehouseIds, DeliveryTripStatus.IN_PROGRESS);

        long totalRunningTrips = runningTrips.size();
        long totalOverdueTrips = runningTrips.stream().filter(DeliveryTrip::isOverdue).count();

        BigDecimal totalValueOnRoad = BigDecimal.ZERO;
        BigDecimal totalDeliveredValue = BigDecimal.ZERO;
        BigDecimal totalTripValue = BigDecimal.ZERO;

        long totalPoints = 0L;
        long totalDeliveredPoints = 0L;
        long totalRemainingPoints = 0L;
        long totalFailedPoints = 0L;
        long totalPendingPoints = 0L;

        for (DeliveryTrip trip : runningTrips) {
            totalTripValue = totalTripValue.add(trip.getTotalValue());
            totalDeliveredValue = totalDeliveredValue.add(trip.getDeliveredValue());
            // Tiền hàng nằm trên đường = Giá trị chưa giao xong (bao gồm chờ giao + chưa giao được vẫn thuộc chuyến)
            totalValueOnRoad = totalValueOnRoad.add(trip.getRemainingValue());

            totalPoints += trip.getTotalPoints();
            totalDeliveredPoints += trip.getDeliveredPoints();
            totalRemainingPoints += trip.getRemainingPoints();
            totalFailedPoints += trip.getFailedPoints();
            totalPendingPoints += trip.getPendingPoints();
        }

        return DeliveryTripSummaryResponse.builder()
                .totalRunningTrips(totalRunningTrips)
                .totalOverdueTrips(totalOverdueTrips)
                .totalValueOnRoad(totalValueOnRoad)
                .totalDeliveredValue(totalDeliveredValue)
                .totalTripValue(totalTripValue)
                .totalPoints(totalPoints)
                .totalDeliveredPoints(totalDeliveredPoints)
                .totalRemainingPoints(totalRemainingPoints)
                .totalFailedPoints(totalFailedPoints)
                .totalPendingPoints(totalPendingPoints)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public DeliveryTripResponse getTripById(Long id) {
        Scope scope = checkScope(false);
        DeliveryTrip trip = tripRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy chuyến giao hàng với ID: " + id));

        scope.require(trip.getWarehouse().getId());
        return mapToTripResponse(trip);
    }

    @Override
    public DeliveryTripResponse createTrip(CreateDeliveryTripRequest request, String username) {
        Scope scope = checkScope(true);

        if (request.getWarehouseId() == null) {
            throw new IllegalArgumentException("Kho xuất phát không được để trống");
        }
        scope.require(request.getWarehouseId());

        Warehouse warehouse = warehouseRepository.findById(request.getWarehouseId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy kho xuất phát"));

        if (!Boolean.TRUE.equals(warehouse.getActive())) {
            throw new IllegalArgumentException("Kho xuất phát đã ngừng hoạt động");
        }

        if (!StringUtils.hasText(request.getDriverName())) {
            throw new IllegalArgumentException("Tên tài xế không được để trống");
        }
        if (!StringUtils.hasText(request.getVehiclePlate())) {
            throw new IllegalArgumentException("Biển số xe không được để trống");
        }

        LocalDateTime departureTime = request.getDepartureTime() != null
                ? request.getDepartureTime()
                : LocalDateTime.now();

        if (request.getExpectedCompletionTime() == null) {
            throw new IllegalArgumentException("Thời gian dự kiến hoàn thành không được để trống");
        }

        if (request.getExpectedCompletionTime().isBefore(departureTime)) {
            throw new IllegalArgumentException("Thời gian dự kiến hoàn thành phải sau thời điểm xuất phát");
        }

        String tripCode = StringUtils.hasText(request.getTripCode())
                ? request.getTripCode().trim()
                : generateTripCode();

        if (tripRepository.existsByTripCode(tripCode)) {
            throw new IllegalArgumentException("Mã chuyến giao hàng " + tripCode + " đã tồn tại");
        }

        DeliveryTrip trip = DeliveryTrip.builder()
                .tripCode(tripCode)
                .warehouse(warehouse)
                .driverName(request.getDriverName().trim())
                .driverPhone(request.getDriverPhone() != null ? request.getDriverPhone().trim() : null)
                .vehiclePlate(request.getVehiclePlate().trim())
                .status(DeliveryTripStatus.IN_PROGRESS)
                .departureTime(departureTime)
                .expectedCompletionTime(request.getExpectedCompletionTime())
                .note(request.getNote() != null ? request.getNote().trim() : null)
                .createdBy(username)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        int stopOrderCounter = 1;

        // 1. Tạo điểm giao từ orderIds nếu có
        if (request.getOrderIds() != null && !request.getOrderIds().isEmpty()) {
            for (Long orderId : request.getOrderIds()) {
                Order order = orderRepository.findById(orderId)
                        .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn hàng ID: " + orderId));

                if (order.getWarehouse() != null && !order.getWarehouse().getId().equals(warehouse.getId())) {
                    throw new IllegalArgumentException("Đơn hàng " + order.getOrderCode() + " thuộc kho khác, không thể gán vào chuyến này");
                }

                DealerDeliveryPoint dp = order.getDeliveryPoint();
                String receiver = dp != null ? dp.getReceiverName() : (order.getDealer() != null ? order.getDealer().getName() : order.getCustomerName());
                String phone = dp != null ? dp.getPhone() : (order.getDealer() != null ? order.getDealer().getPhone() : null);
                String address = dp != null ? dp.getAddress() : (order.getDealer() != null ? order.getDealer().getAddress() : "Tại kho");

                DeliveryTripStop stop = DeliveryTripStop.builder()
                        .trip(trip)
                        .order(order)
                        .orderCode(order.getOrderCode())
                        .dealer(order.getDealer())
                        .dealerName(order.getDealer() != null ? order.getDealer().getName() : order.getCustomerName())
                        .deliveryPoint(dp)
                        .receiverName(receiver)
                        .receiverPhone(phone)
                        .deliveryAddress(address)
                        .stopOrder(stopOrderCounter++)
                        .status(DeliveryStopStatus.PENDING)
                        .deliveryAmount(order.getTotalAmount() != null ? order.getTotalAmount() : BigDecimal.ZERO)
                        .returnedToWarehouse(false)
                        .build();

                // Chuyển items từ đơn hàng vào chi tiết điểm giao
                if (order.getItems() != null) {
                    for (OrderItem oi : order.getItems()) {
                        BigDecimal itemPrice = oi.getPrice() != null ? oi.getPrice() : BigDecimal.ZERO;
                        BigDecimal itemTotal = oi.getSubtotalAmount() != null ? oi.getSubtotalAmount()
                                : (oi.getQuantity() != null ? oi.getQuantity().multiply(itemPrice) : BigDecimal.ZERO);

                        DeliveryTripStopItem item = DeliveryTripStopItem.builder()
                                .stop(stop)
                                .product(oi.getProduct())
                                .productCode(oi.getProduct() != null ? oi.getProduct().getCode() : null)
                                .productName(oi.getProduct() != null ? oi.getProduct().getName() : null)
                                .unit(oi.getUnit())
                                .quantity(oi.getQuantity())
                                .unitPrice(itemPrice)
                                .totalPrice(itemTotal)
                                .build();
                        stop.addItem(item);
                    }
                }

                trip.addStop(stop);
            }
        }

        // 2. Tạo điểm giao tùy chỉnh từ request.stops nếu có
        if (request.getStops() != null && !request.getStops().isEmpty()) {
            for (CreateDeliveryTripStopRequest sReq : request.getStops()) {
                DeliveryTripStop stop = DeliveryTripStop.builder()
                        .trip(trip)
                        .orderCode(sReq.getOrderCode())
                        .dealerName(sReq.getDealerName())
                        .receiverName(sReq.getReceiverName())
                        .receiverPhone(sReq.getReceiverPhone())
                        .deliveryAddress(sReq.getDeliveryAddress().trim())
                        .stopOrder(sReq.getStopOrder() != null ? sReq.getStopOrder() : stopOrderCounter++)
                        .status(DeliveryStopStatus.PENDING)
                        .deliveryAmount(sReq.getDeliveryAmount() != null ? sReq.getDeliveryAmount() : BigDecimal.ZERO)
                        .returnedToWarehouse(false)
                        .note(sReq.getNote())
                        .build();

                if (sReq.getOrderId() != null) {
                    orderRepository.findById(sReq.getOrderId()).ifPresent(stop::setOrder);
                }
                if (sReq.getDealerId() != null) {
                    dealerRepository.findById(sReq.getDealerId()).ifPresent(stop::setDealer);
                }
                if (sReq.getDeliveryPointId() != null) {
                    deliveryPointRepository.findById(sReq.getDeliveryPointId()).ifPresent(stop::setDeliveryPoint);
                }

                if (sReq.getItems() != null) {
                    for (CreateDeliveryTripStopItemRequest iReq : sReq.getItems()) {
                        Product prod = iReq.getProductId() != null ? productRepository.findById(iReq.getProductId()).orElse(null) : null;
                        DeliveryTripStopItem item = DeliveryTripStopItem.builder()
                                .stop(stop)
                                .product(prod)
                                .productCode(prod != null ? prod.getCode() : iReq.getProductCode())
                                .productName(prod != null ? prod.getName() : iReq.getProductName())
                                .unit(iReq.getUnit())
                                .quantity(iReq.getQuantity() != null ? iReq.getQuantity() : BigDecimal.ZERO)
                                .unitPrice(iReq.getUnitPrice() != null ? iReq.getUnitPrice() : BigDecimal.ZERO)
                                .totalPrice(iReq.getQuantity() != null && iReq.getUnitPrice() != null
                                        ? iReq.getQuantity().multiply(iReq.getUnitPrice()) : BigDecimal.ZERO)
                                .build();
                        stop.addItem(item);
                    }
                }

                trip.addStop(stop);
            }
        }

        if (trip.getStops().isEmpty()) {
            throw new IllegalArgumentException("Chuyến giao hàng phải có ít nhất 1 điểm giao");
        }

        DeliveryTrip saved = tripRepository.save(trip);

        auditLogService.logAction("DELIVERY_TRIP", saved.getTripCode(), "CREATE_TRIP",
                null, saved.getStatus().name(),
                "Tạo chuyến giao hàng " + saved.getTripCode() + " với " + saved.getTotalPoints()
                        + " điểm giao, tổng tiền " + saved.getTotalValue() + " đ",
                username);

        return mapToTripResponse(saved);
    }

    @Override
    public DeliveryTripResponse confirmDelivery(Long tripId, Long stopId, String receiverName,
            LocalDateTime deliveredAt, org.springframework.web.multipart.MultipartFile proof, String username) {
        Scope scope = checkScope(true);
        DeliveryTrip trip = tripRepository.findByIdForUpdate(tripId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy chuyến giao hàng"));
        scope.require(trip.getWarehouse().getId());
        DeliveryTripStop stop = trip.getStops().stream().filter(s -> s.getId().equals(stopId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Điểm giao không thuộc chuyến"));
        if (!StringUtils.hasText(receiverName) || receiverName.trim().length() > 100) {
            throw new IllegalArgumentException("Người nhận thực tế bắt buộc, tối đa 100 ký tự");
        }
        if (deliveredAt == null || deliveredAt.isAfter(LocalDateTime.now())
                || (trip.getDepartureTime() != null && deliveredAt.isBefore(trip.getDepartureTime()))) {
            throw new IllegalArgumentException("Thời điểm giao phải từ lúc xuất phát đến hiện tại");
        }
        if (proof == null || proof.isEmpty() || proof.getSize() > 5 * 1024 * 1024) {
            throw new IllegalArgumentException("Ảnh biên bản có chữ ký bắt buộc, tối đa 5 MB");
        }
        byte[] bytes;
        String contentType;
        try {
            bytes = proof.getBytes();
            try (var input = javax.imageio.ImageIO.createImageInputStream(new java.io.ByteArrayInputStream(bytes))) {
                var readers = javax.imageio.ImageIO.getImageReaders(input);
                if (!readers.hasNext()) throw new IllegalArgumentException("Chứng từ phải là ảnh PNG hoặc JPEG hợp lệ");
                var reader = readers.next();
                try {
                    String format = reader.getFormatName();
                    if (!"png".equalsIgnoreCase(format) && !"jpeg".equalsIgnoreCase(format))
                        throw new IllegalArgumentException("Chỉ chấp nhận PNG hoặc JPEG");
                    reader.setInput(input);
                    if ((long) reader.getWidth(0) * reader.getHeight(0) > 20_000_000L)
                        throw new IllegalArgumentException("Ảnh vượt quá 20 triệu điểm ảnh");
                    if (reader.read(0) == null) throw new IllegalArgumentException("Ảnh không hợp lệ");
                    contentType = "png".equalsIgnoreCase(format) ? "image/png" : "image/jpeg";
                } finally { reader.dispose(); }
            }
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("Không đọc được ảnh chứng từ", e);
        }
        if (stop.getSignedProof() != null) throw new IllegalStateException("Chứng từ đã được xác nhận");
        stop.setSignedProof(bytes);
        stop.setProofContentType(contentType);
        stop.setReceiverName(receiverName.trim());
        stop.setDeliveredAt(deliveredAt);
        stop.setConfirmedBy(username);
        return updateStopStatus(tripId, stopId,
                UpdateDeliveryStopStatusRequest.builder().status(DeliveryStopStatus.DELIVERED).build(), username);
    }

    @Override
    @Transactional(readOnly = true)
    public org.springframework.http.ResponseEntity<byte[]> getDeliveryProof(Long tripId, Long stopId) {
        Scope scope = checkScope(false);
        DeliveryTrip trip = tripRepository.findById(tripId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy chuyến giao hàng"));
        scope.require(trip.getWarehouse().getId());
        DeliveryTripStop stop = trip.getStops().stream().filter(s -> s.getId().equals(stopId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Điểm giao không thuộc chuyến"));
        if (stop.getSignedProof() == null) return org.springframework.http.ResponseEntity.notFound().build();
        return org.springframework.http.ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Disposition", "attachment; filename=delivery-proof." +
                        ("image/png".equals(stop.getProofContentType()) ? "png" : "jpg"))
                .contentType(org.springframework.http.MediaType.parseMediaType(stop.getProofContentType()))
                .body(stop.getSignedProof());
    }

    @Override
    public DeliveryTripResponse updateStopStatus(Long tripId, Long stopId,
                                                UpdateDeliveryStopStatusRequest request, String username) {
        Scope scope = checkScope(true);

        DeliveryTrip trip = tripRepository.findByIdForUpdate(tripId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy chuyến giao hàng với ID: " + tripId));
        scope.require(trip.getWarehouse().getId());

        if (trip.getStatus() != DeliveryTripStatus.IN_PROGRESS) {
            throw new IllegalStateException("Chỉ có thể cập nhật điểm giao khi chuyến đang chạy (IN_PROGRESS)");
        }

        DeliveryTripStop stop = trip.getStops().stream()
                .filter(s -> s.getId().equals(stopId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy điểm giao ID: " + stopId + " trong chuyến " + trip.getTripCode()));

        if (stop.getStatus() == DeliveryStopStatus.DELIVERED || Boolean.TRUE.equals(stop.getReturnedToWarehouse())) {
            throw new IllegalStateException("Không thể sửa điểm đã giao hoặc đã nhập trả kho");
        }
        DeliveryStopStatus targetStatus = request.getStatus();
        if (targetStatus == null) {
            throw new IllegalArgumentException("Trạng thái giao hàng không được để trống");
        }

        if ((request.getFailedReason() != null && request.getFailedReason().length() > 500)
                || (request.getNote() != null && request.getNote().length() > 500))
            throw new IllegalArgumentException("Lý do và ghi chú tối đa 500 ký tự");
        if (targetStatus == DeliveryStopStatus.FAILED) {
            if (!StringUtils.hasText(request.getFailedReason())) {
                throw new IllegalArgumentException("Bắt buộc phải nhập lý do khi điểm giao chưa giao được");
            }
            stop.setStatus(DeliveryStopStatus.FAILED);
            stop.setFailedReason(request.getFailedReason().trim());
            stop.setFailedAt(LocalDateTime.now());
            stop.setNote(request.getNote() != null ? request.getNote().trim() : stop.getNote());

            // ══════════════════════════════════════════════════════════════════════════════
            // QUY TẮC CỐT LÕI S6-07:
            // "Hàng chưa giao được vẫn thuộc trách nhiệm của chuyến, không tự về kho"
            // -> KHÔNG tự động cộng tồn kho! Không tự động hoàn tồn kho!
            // -> returnedToWarehouse giữ nguyên là FALSE.
            // -> Hàng vẫn nằm trên xe / chuyến giao, tiền hàng vẫn tính là "nằm trên đường".
            // ══════════════════════════════════════════════════════════════════════════════
            stop.setReturnedToWarehouse(false);

            log.info("S6-07 Invariant: Điểm giao {} trong chuyến {} chưa giao được. Lý do: {}. Hàng KHÔNG tự về kho.",
                    stop.getId(), trip.getTripCode(), stop.getFailedReason());

            auditLogService.logAction("DELIVERY_TRIP", trip.getTripCode(), "STOP_FAILED",
                    null, DeliveryStopStatus.FAILED.name(),
                    "Điểm giao " + stop.getStopOrder() + " chưa giao được: " + stop.getFailedReason()
                            + ". Hàng vẫn thuộc trách nhiệm của chuyến, không tự động về kho.",
                    username);

        } else if (targetStatus == DeliveryStopStatus.DELIVERED) {
            if (stop.getSignedProof() == null || !StringUtils.hasText(stop.getConfirmedBy())) {
                throw new IllegalArgumentException("Phải xác nhận giao hàng kèm ảnh biên bản có chữ ký qua API confirm-delivery");
            }
            stop.setStatus(DeliveryStopStatus.DELIVERED);
            stop.setFailedReason(null);
            stop.setFailedAt(null);
            stop.setNote(request.getNote() != null ? request.getNote().trim() : stop.getNote());

            // Cập nhật trạng thái Order nếu có liên kết
            if (stop.getOrder() != null) {
                Order order = orderRepository.findByIdForUpdate(stop.getOrder().getId())
                        .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn hàng"));
                if (!Set.of("EXPORTED", "SHIPPED").contains(order.getStatus())) {
                    throw new IllegalStateException("Chỉ xác nhận đơn đã xuất kho và chưa giao");
                }
                Map<String, BigDecimal> actual = new HashMap<>();
                for (DeliveryTripStopItem item : stop.getItems()) {
                    if (item.getProduct() == null || item.getQuantity() == null || item.getQuantity().signum() <= 0)
                        throw new IllegalArgumentException("Chi tiết hàng giao không hợp lệ");
                    actual.merge(item.getProduct().getId() + ":" + item.getUnit(), item.getQuantity(), BigDecimal::add);
                }
                Map<String, BigDecimal> expected = new HashMap<>();
                for (OrderItem item : order.getItems())
                    expected.merge(item.getProduct().getId() + ":" + item.getUnit(), item.getQuantity(), BigDecimal::add);
                if (actual.size() != expected.size() || expected.entrySet().stream().anyMatch(e ->
                        !actual.containsKey(e.getKey()) || actual.get(e.getKey()).compareTo(e.getValue()) != 0))
                    throw new IllegalArgumentException("Xác nhận S6-08 yêu cầu giao đủ hàng của đơn; chi tiết chuyến không khớp");
                if (!"DELIVERED".equalsIgnoreCase(order.getStatus()) && !"CLOSED".equalsIgnoreCase(order.getStatus())) {
                    order.setStatus("DELIVERED");
                    order.setActualDeliveryDate(stop.getDeliveredAt().toLocalDate());
                    // Chuyến giao toàn bộ đơn; ghi thực giao để S7-01 phát hành hóa đơn.
                    for (OrderItem item : order.getItems()) {
                        item.setDeliveredQuantity(item.getQuantity());
                        item.setDeliveredBaseQuantity(item.getQuantity().multiply(
                                item.getConversionFactor() != null ? item.getConversionFactor() : BigDecimal.ONE));
                    }
                    orderRepository.save(order);
                }
            }

            auditLogService.logAction("DELIVERY_TRIP", trip.getTripCode(), "STOP_DELIVERED",
                    null, DeliveryStopStatus.DELIVERED.name(),
                    "Giao hàng thành công tại điểm " + stop.getStopOrder() + " (" + stop.getDeliveryAddress()
                            + "), số tiền: " + stop.getDeliveryAmount() + " đ",
                    username);
        } else {
            stop.setStatus(targetStatus);
        }

        trip.setUpdatedAt(LocalDateTime.now());
        DeliveryTrip saved = tripRepository.save(trip);

        return mapToTripResponse(saved);
    }

    @Override
    public DeliveryTripResponse completeTrip(Long id, String username) {
        Scope scope = checkScope(true);

        DeliveryTrip trip = tripRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy chuyến giao hàng với ID: " + id));
        scope.require(trip.getWarehouse().getId());

        if (trip.getStatus() != DeliveryTripStatus.IN_PROGRESS) {
            throw new IllegalStateException("Chỉ chuyến đang chạy (IN_PROGRESS) mới có thể hoàn tất");
        }

        trip.setStatus(DeliveryTripStatus.COMPLETED);
        trip.setActualCompletionTime(LocalDateTime.now());
        trip.setUpdatedAt(LocalDateTime.now());

        long failedCount = trip.getFailedPoints();
        BigDecimal failedAmount = trip.getFailedValue();

        String completionNote = "Hoàn tất chuyến giao hàng " + trip.getTripCode();
        if (failedCount > 0) {
            completionNote += ". Lưu ý: Có " + failedCount + " điểm chưa giao được (" + failedAmount
                    + " đ) vẫn thuộc trách nhiệm chuyến/tài xế lưu giữ, chưa nhập kho trả hàng.";
        }

        DeliveryTrip saved = tripRepository.save(trip);

        auditLogService.logAction("DELIVERY_TRIP", saved.getTripCode(), "COMPLETE_TRIP",
                DeliveryTripStatus.IN_PROGRESS.name(), DeliveryTripStatus.COMPLETED.name(),
                completionNote, username);

        return mapToTripResponse(saved);
    }

    @Override
    public DeliveryTripResponse cancelTrip(Long id, String cancelReason, String username) {
        Scope scope = checkScope(true);

        DeliveryTrip trip = tripRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy chuyến giao hàng với ID: " + id));
        scope.require(trip.getWarehouse().getId());

        if (trip.getStatus() == DeliveryTripStatus.COMPLETED) {
            throw new IllegalStateException("Không thể hủy chuyến giao đã hoàn thành");
        }
        if (trip.getStatus() == DeliveryTripStatus.CANCELLED) {
            throw new IllegalStateException("Chuyến giao hàng này đã bị hủy trước đó");
        }

        if (!StringUtils.hasText(cancelReason)) {
            throw new IllegalArgumentException("Bắt buộc phải nhập lý do khi hủy chuyến giao hàng");
        }

        if (trip.getDeliveredPoints() > 0) {
            throw new IllegalStateException("Không thể hủy chuyến giao đã có điểm giao hàng thành công");
        }

        String oldStatus = trip.getStatus().name();
        trip.setStatus(DeliveryTripStatus.CANCELLED);
        trip.setCancelReason(cancelReason.trim());
        trip.setUpdatedAt(LocalDateTime.now());

        DeliveryTrip saved = tripRepository.save(trip);

        auditLogService.logAction("DELIVERY_TRIP", saved.getTripCode(), "CANCEL_TRIP",
                oldStatus, DeliveryTripStatus.CANCELLED.name(),
                "Hủy chuyến giao hàng: " + cancelReason.trim(), username);

        return mapToTripResponse(saved);
    }

    @Override
    public DeliveryTripStopResponse returnStopGoodsToWarehouse(Long tripId, Long stopId,
                                                              ReturnStopGoodsRequest request, String username) {
        Scope scope = checkScope(true);

        DeliveryTrip trip = tripRepository.findByIdForUpdate(tripId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy chuyến giao hàng với ID: " + tripId));
        scope.require(trip.getWarehouse().getId());

        DeliveryTripStop stop = trip.getStops().stream()
                .filter(s -> s.getId().equals(stopId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy điểm giao ID: " + stopId));

        if (stop.getStatus() != DeliveryStopStatus.FAILED) {
            throw new IllegalStateException("Chỉ điểm giao chưa giao được (FAILED) mới có thể thực hiện nhập trả kho");
        }

        if (Boolean.TRUE.equals(stop.getReturnedToWarehouse())) {
            throw new IllegalStateException("Hàng của điểm giao này đã được nhập trả kho trước đó");
        }

        if (!StringUtils.hasText(request.getReturnReason())) {
            throw new IllegalArgumentException("Lý do nhập trả kho không được để trống");
        }

        // Thực hiện nhập kho trả hàng có kiểm soát
        stop.setReturnedToWarehouse(true);
        stop.setReturnedAt(LocalDateTime.now());
        if (request.getNote() != null) {
            stop.setNote(stop.getNote() != null ? stop.getNote() + " | " + request.getNote().trim() : request.getNote().trim());
        }

        // Hoàn tồn kho thực tế cho các sản phẩm tại điểm này
        if (stop.getItems() != null) {
            for (DeliveryTripStopItem item : stop.getItems()) {
                if (item.getProduct() != null && item.getQuantity() != null && item.getQuantity().compareTo(BigDecimal.ZERO) > 0) {
                    var invOpt = inventoryRepository.findByWarehouseIdAndProductId(
                            trip.getWarehouse().getId(), item.getProduct().getId());

                    if (invOpt.isPresent()) {
                        WarehouseInventory inv = invOpt.get();
                        inv.setBaseQuantity(inv.getBaseQuantity().add(item.getQuantity()));
                        inventoryRepository.save(inv);
                    }
                }
            }
        }

        stopRepository.save(stop);

        auditLogService.logAction("DELIVERY_TRIP", trip.getTripCode(), "RETURN_TO_WAREHOUSE",
                "false", "true",
                "Nhập trả kho hàng chưa giao được của điểm " + stop.getStopOrder()
                        + ". Lý do: " + request.getReturnReason().trim(),
                username);

        return mapToStopResponse(stop);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DeliveryEligibleOrderResponse> getEligibleOrders(Long warehouseId) {
        Scope scope = checkScope(false);

        if (warehouseId == null) {
            throw new IllegalArgumentException("Vui lòng chọn kho để tra cứu đơn hàng");
        }
        scope.require(warehouseId);

        // Lấy các đơn hàng ở trạng thái EXPORTED thuộc kho này
        Specification<Order> spec = (root, query, cb) -> cb.and(
                cb.equal(root.get("warehouse").get("id"), warehouseId),
                cb.or(
                        cb.equal(root.get("status"), "EXPORTED"),
                        cb.equal(root.get("status"), "SHIPPED")
                )
        );

        List<Order> orders = orderRepository.findAll(spec, Sort.by(Sort.Direction.DESC, "createdAt"));

        return orders.stream()
                .map(order -> {
                    DealerDeliveryPoint dp = order.getDeliveryPoint();
                    String receiver = dp != null ? dp.getReceiverName() : (order.getDealer() != null ? order.getDealer().getName() : order.getCustomerName());
                    String phone = dp != null ? dp.getPhone() : (order.getDealer() != null ? order.getDealer().getPhone() : null);
                    String address = dp != null ? dp.getAddress() : (order.getDealer() != null ? order.getDealer().getAddress() : "Tại kho");

                    return DeliveryEligibleOrderResponse.builder()
                            .orderId(order.getId())
                            .orderCode(order.getOrderCode())
                            .dealerId(order.getDealer() != null ? order.getDealer().getId() : null)
                            .dealerName(order.getDealer() != null ? order.getDealer().getName() : order.getCustomerName())
                            .deliveryPointId(dp != null ? dp.getId() : null)
                            .receiverName(receiver)
                            .receiverPhone(phone)
                            .deliveryAddress(address)
                            .expectedDeliveryDate(order.getExpectedDeliveryDate())
                            .totalAmount(order.getTotalAmount())
                            .status(order.getStatus())
                            .build();
                })
                .toList();
    }

    // ── MAPPING HELPERS ──

    private DeliveryTripResponse mapToTripResponse(DeliveryTrip trip) {
        List<DeliveryTripStopResponse> stopResponses = trip.getStops() != null
                ? trip.getStops().stream()
                .sorted(Comparator.comparing(DeliveryTripStop::getStopOrder, Comparator.nullsLast(Integer::compareTo)))
                .map(this::mapToStopResponse)
                .toList()
                : List.of();

        return DeliveryTripResponse.from(trip, stopResponses);
    }

    private DeliveryTripStopResponse mapToStopResponse(DeliveryTripStop stop) {
        List<DeliveryTripStopItemResponse> itemResponses = stop.getItems() != null
                ? stop.getItems().stream().map(this::mapToItemResponse).toList()
                : List.of();

        return DeliveryTripStopResponse.builder()
                .id(stop.getId())
                .orderId(stop.getOrder() != null ? stop.getOrder().getId() : null)
                .orderCode(stop.getOrderCode())
                .dealerId(stop.getDealer() != null ? stop.getDealer().getId() : null)
                .dealerName(stop.getDealerName())
                .deliveryPointId(stop.getDeliveryPoint() != null ? stop.getDeliveryPoint().getId() : null)
                .receiverName(stop.getReceiverName())
                .receiverPhone(stop.getReceiverPhone())
                .deliveryAddress(stop.getDeliveryAddress())
                .stopOrder(stop.getStopOrder())
                .status(stop.getStatus())
                .statusDisplayName(stop.getStatus() != null ? stop.getStatus().getDisplayName() : null)
                .deliveryAmount(stop.getDeliveryAmount())
                .deliveredAt(stop.getDeliveredAt())
                .hasSignedProof(stop.getSignedProof() != null)
                .confirmedBy(stop.getConfirmedBy())
                .failedReason(stop.getFailedReason())
                .failedAt(stop.getFailedAt())
                .returnedToWarehouse(stop.getReturnedToWarehouse())
                .returnedAt(stop.getReturnedAt())
                .note(stop.getNote())
                .items(itemResponses)
                .build();
    }

    private DeliveryTripStopItemResponse mapToItemResponse(DeliveryTripStopItem item) {
        return DeliveryTripStopItemResponse.builder()
                .id(item.getId())
                .productId(item.getProduct() != null ? item.getProduct().getId() : null)
                .productCode(item.getProductCode())
                .productName(item.getProductName())
                .unit(item.getUnit())
                .quantity(item.getQuantity())
                .unitPrice(item.getUnitPrice())
                .totalPrice(item.getTotalPrice())
                .build();
    }

    private String generateTripCode() {
        String dateStr = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        for (int i = 0; i < 10; i++) {
            int randomNum = ThreadLocalRandom.current().nextInt(100, 999);
            String candidate = "CG-" + dateStr + "-" + randomNum;
            if (!tripRepository.existsByTripCode(candidate)) {
                return candidate;
            }
        }
        return "CG-" + dateStr + "-" + System.currentTimeMillis() % 10000;
    }
}
