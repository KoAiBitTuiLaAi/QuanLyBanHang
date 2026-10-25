package com.quanlybanhang.service.impl;

import com.quanlybanhang.dto.request.*;
import com.quanlybanhang.dto.response.*;
import com.quanlybanhang.model.*;
import com.quanlybanhang.repository.*;
import com.quanlybanhang.service.AuditLogService;
import com.quanlybanhang.service.DeliveryTripService;
import com.quanlybanhang.repository.TerritoryRepository;
import com.quanlybanhang.repository.WarehouseVoucherRepository;
import com.quanlybanhang.service.DeliveryTripPdfService;
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
    private static final long MAX_PROOF_BYTES = 5L * 1024 * 1024;
    private static final long MAX_PROOF_PIXELS = 20_000_000L;

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
    private final OrderStatusHistoryRepository orderStatusHistoryRepository;
    private final TerritoryRepository territoryRepository;
    private final WarehouseVoucherRepository warehouseVoucherRepository;
    private final DeliveryTripPdfService deliveryTripPdfService;

    private final WarehouseLotInventoryRepository lotInventoryRepository;

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

        List<NamedResourceResponse> territoryResponses = territoryRepository != null
                ? territoryRepository.findAllByActiveTrueOrderByNameAsc().stream()
                .map(t -> new NamedResourceResponse(t.getId(), t.getCode(), t.getName()))
                .toList()
                : List.of();

        return DeliveryTripOptionsResponse.builder()
                .warehouses(warehouseResponses)
                .territories(territoryResponses)
                .tripStatuses(tripStatuses)
                .stopStatuses(stopStatuses)
                .canManage(scope.write())
                .failureReasons(Arrays.stream(DeliveryFailureReason.values()).map(r -> DeliveryTripOptionsResponse.StatusOption.builder()
                        .value(r.name()).label(r.getLabel()).build()).toList())
                .proofMaxBytes(MAX_PROOF_BYTES).proofMaxPixels(MAX_PROOF_PIXELS)
                .proofContentTypes(List.of("image/png", "image/jpeg"))
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public Page<DeliveryTripResponse> searchTrips(Long warehouseId, DeliveryTripStatus status,
                                                 Boolean isOverdue, String keyword, int page, int size) {
        return searchTrips(warehouseId, status, isOverdue, keyword, page, size, false);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<DeliveryTripResponse> searchTrips(Long warehouseId, DeliveryTripStatus status,
            Boolean isOverdue, String keyword, int page, int size, boolean outstandingOnly) {
        Scope scope = checkScope(false);

        if (warehouseId != null) {
            scope.require(warehouseId);
        }

        if (page < 0 || size < 1 || size > 200) {
            throw new IllegalArgumentException("Trang phải không âm; số dòng mỗi trang từ 1 đến 200");
        }
        if (keyword != null && keyword.length() > 150) throw new IllegalArgumentException("Từ khóa tối đa 150 ký tự");

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

            if (outstandingOnly) {
                var sub = query.subquery(Long.class);
                var stop = sub.from(DeliveryTripStop.class);
                sub.select(stop.get("id")).where(cb.equal(stop.get("trip"), root),
                        cb.notEqual(stop.get("status"), DeliveryStopStatus.DELIVERED),
                        cb.or(cb.isNull(stop.get("returnedToWarehouse")), cb.isFalse(stop.get("returnedToWarehouse"))));
                predicates.add(cb.exists(sub));
            }

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
                            cb.greaterThanOrEqualTo(root.get("expectedCompletionTime"), now),
                            cb.isNull(root.get("expectedCompletionTime"))
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
                    .totalOutstandingValue(BigDecimal.ZERO).totalOutstandingTrips(0L)
                    .closedOutstandingValue(BigDecimal.ZERO).closedOutstandingTrips(0L)
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

        // Read-only liability reconciliation, including historical completed/cancelled trips.
        List<DeliveryTrip> outstanding = tripRepository.findAllByWarehouseIdIn(allowedWarehouseIds).stream()
                .filter(t -> t.getRemainingPoints() > 0).toList();
        List<DeliveryTrip> closedOutstanding = outstanding.stream()
                .filter(t -> t.getStatus() != DeliveryTripStatus.IN_PROGRESS).toList();

        return DeliveryTripSummaryResponse.builder()
                .totalOutstandingValue(outstanding.stream().map(DeliveryTrip::getRemainingValue).reduce(BigDecimal.ZERO, BigDecimal::add))
                .totalOutstandingTrips((long) outstanding.size())
                .closedOutstandingValue(closedOutstanding.stream().map(DeliveryTrip::getRemainingValue).reduce(BigDecimal.ZERO, BigDecimal::add))
                .closedOutstandingTrips((long) closedOutstanding.size())
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
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public DeliveryTripResponse createTrip(CreateDeliveryTripRequest request, String username) {
        Scope scope = checkScope(true);
        scope.require(request.getWarehouseId());
        Warehouse warehouse = warehouseRepository.findById(request.getWarehouseId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy kho xuất phát"));
        if (!Boolean.TRUE.equals(warehouse.getActive())) throw new IllegalArgumentException("Kho đã ngừng hoạt động");
        String driver = requiredText(request.getDriverName(), 100, "Tên tài xế");
        String vehicle = requiredText(request.getVehiclePlate(), 30, "Biển số xe");
        LocalDateTime departure = request.getDepartureTime() == null ? LocalDateTime.now() : request.getDepartureTime();
        if (request.getExpectedCompletionTime() == null || !request.getExpectedCompletionTime().isAfter(departure))
            throw new IllegalArgumentException("Thời gian dự kiến hoàn thành phải sau lúc xuất phát");
        String code = StringUtils.hasText(request.getTripCode())
                ? requiredText(request.getTripCode(), 50, "Mã chuyến") : generateTripCode();
        if (tripRepository.existsByTripCode(code)) throw new IllegalArgumentException("Mã chuyến đã tồn tại");
        DeliveryTrip trip = DeliveryTrip.builder().tripCode(code).warehouse(warehouse)
                .driverName(driver).driverPhone(optionalText(request.getDriverPhone(), 20))
                .vehiclePlate(vehicle).routeName(optionalText(request.getRouteName(), 255))
                .status(DeliveryTripStatus.IN_PROGRESS).departureTime(departure)
                .expectedCompletionTime(request.getExpectedCompletionTime()).note(optionalText(request.getNote(), 500))
                .createdBy(username).createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        if (request.getTerritoryId() != null) trip.setTerritory(territoryRepository.findById(request.getTerritoryId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy khu vực")));
        int modes = (request.getStops() == null || request.getStops().isEmpty() ? 0 : 1)
                + (request.getOrderIds() == null || request.getOrderIds().isEmpty() ? 0 : 1)
                + (request.getShipmentVoucherIds() == null || request.getShipmentVoucherIds().isEmpty() ? 0 : 1);
        if (modes != 1) throw new IllegalArgumentException("Chọn một danh sách điểm giao, phiếu xuất hoặc đơn hàng");
        List<CreateDeliveryTripStopRequest> selections = new ArrayList<>();
        if (request.getStops() != null) selections.addAll(request.getStops());
        if (request.getOrderIds() != null) request.getOrderIds().forEach(id ->
                selections.add(CreateDeliveryTripStopRequest.builder().orderId(id).build()));
        if (request.getShipmentVoucherIds() != null) request.getShipmentVoucherIds().forEach(id ->
                selections.add(CreateDeliveryTripStopRequest.builder().shipmentVoucherId(id).build()));
        if (selections.isEmpty() || selections.size() > 100 || selections.stream().anyMatch(Objects::isNull))
            throw new IllegalArgumentException("Chọn từ 1 đến 100 điểm giao");
        // Lock parent orders in a stable order. Voucher ownership is checked before touching any order.
        Map<Long, WarehouseVoucher> requestedVouchers = new HashMap<>();
        Set<Long> orderIds = new TreeSet<>();
        for (var selection : selections) {
            Long orderId = selection.getOrderId();
            if (selection.getShipmentVoucherId() != null) {
                var voucher = warehouseVoucherRepository.findById(selection.getShipmentVoucherId())
                        .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy phiếu xuất"));
                requireShipment(voucher, warehouse.getId(), scope);
                if (orderId != null && !orderId.equals(voucher.getShipmentOrderId()))
                    throw new IllegalArgumentException("Phiếu xuất không thuộc đơn đã chọn");
                orderId = voucher.getShipmentOrderId();
                requestedVouchers.put(selection.getShipmentVoucherId(), voucher);
            }
            if (orderId != null) {
                if (orderId < 1) throw new IllegalArgumentException("Mã đơn không hợp lệ");
                orderIds.add(orderId);
            }
        }
        Map<Long, Order> locked = new HashMap<>();
        for (Long id : orderIds) {
            Order order = orderRepository.findByIdForUpdate(id)
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn hàng"));
            if (order.getWarehouse() == null) throw new IllegalArgumentException("Đơn không có kho xuất");
            scope.require(order.getWarehouse().getId());
            if (!warehouse.getId().equals(order.getWarehouse().getId()))
                throw new AccessDeniedException("Đơn hàng thuộc kho khác");
            if (!Set.of("EXPORTED", "SHIPPED", "SHIPPING").contains(order.getStatus()))
                throw new IllegalArgumentException("Chỉ gán chuyến cho đơn đã xuất kho và chưa giao đủ");
            locked.put(id, order);
        }
        Set<Long> usedVouchers = new HashSet<>(), legacyOrders = new HashSet<>();
        for (var selection : selections) {
            WarehouseVoucher selected = requestedVouchers.get(selection.getShipmentVoucherId());
            Long orderId = selected == null ? selection.getOrderId() : selected.getShipmentOrderId();
            if (orderId == null) {
                // Keep historical/manual itinerary support, but these points cannot restock inventory.
                DeliveryTripStop manual = DeliveryTripStop.builder().trip(trip).stopOrder(trip.getStops().size() + 1)
                        .status(DeliveryStopStatus.PENDING).deliveryAddress(requiredText(selection.getDeliveryAddress(), 500, "Địa chỉ"))
                        .receiverName(optionalText(selection.getReceiverName(), 100)).receiverPhone(optionalText(selection.getReceiverPhone(), 20))
                        .dealerName(optionalText(selection.getDealerName(), 255)).orderCode(optionalText(selection.getOrderCode(), 50))
                        .deliveryAmount(selection.getDeliveryAmount() == null ? BigDecimal.ZERO : selection.getDeliveryAmount())
                        .note(optionalText(selection.getNote(), 500)).returnedToWarehouse(false).build();
                if (manual.getDeliveryAmount().signum() < 0) throw new IllegalArgumentException("Tiền hàng không được âm");
                trip.addStop(manual);
                continue;
            }
            Order order = locked.get(orderId);
            List<WarehouseVoucher> sources = selected == null
                    ? warehouseVoucherRepository.findByShipmentOrderIdOrderByIdAsc(orderId) : List.of(selected);
            if (sources.isEmpty()) {
                if(order.getItems().isEmpty())throw new IllegalArgumentException("Đơn cũ không có dòng hàng; cần đối soát trước khi giao");
                if (Boolean.TRUE.equals(order.getBackordered()) || order.getItems().stream().anyMatch(i -> i.getExportedQuantity() != null))
                    throw new IllegalArgumentException("Không tìm thấy phiếu xuất đã xác nhận; cần đối soát chứng từ");
                if (!legacyOrders.add(orderId) || !stopRepository.findByOrderIdForUpdate(orderId).isEmpty())
                    throw new IllegalArgumentException("Đơn cũ đã được gán chuyến; không tạo lặp");
                DeliveryTripStop stop = orderStop(trip, order, null, selection);
                for (var line : order.getItems()) stop.addItem(legacyShipmentLine(line, stop));
                trip.addStop(stop);
            } else {
                boolean added = false;
                for (var candidate : sources) {
                    requireShipment(candidate, warehouse.getId(), scope);
                    if (selected == null && stopRepository.existsByShipmentVoucherId(candidate.getId())) continue;
                    WarehouseVoucher voucher = warehouseVoucherRepository.findByIdForUpdate(candidate.getId())
                            .orElseThrow(() -> new IllegalArgumentException("Phiếu xuất không còn tồn tại"));
                    if (!usedVouchers.add(voucher.getId()) || stopRepository.existsByShipmentVoucherId(voucher.getId()))
                        throw new IllegalArgumentException("Phiếu xuất đã được gán chuyến; không tạo lặp");
                    DeliveryTripStop stop = orderStop(trip, order, voucher, selection);
                    for (var line : voucher.getItems()) {
                        if (line.getQuantity() == null || line.getQuantity().signum() < 0)
                            throw new IllegalArgumentException("Dòng phiếu xuất không hợp lệ");
                        if (line.getQuantity().signum() == 0) continue;
                        OrderItem original = order.getItems().stream().filter(i -> i.getId().equals(line.getShipmentOrderItemId())).findFirst()
                                .orElseThrow(() -> new IllegalArgumentException("Dòng phiếu xuất không liên kết dòng đơn gốc"));
                        if (!original.getProduct().getId().equals(line.getProduct().getId()) || !original.getUnit().equals(line.getUnit())
                                || original.getConversionFactor().compareTo(line.getConversionFactor()) != 0)
                            throw new IllegalArgumentException("Quy đổi phiếu xuất không khớp đơn gốc");
                        validateShipmentQuantity(line.getQuantity(), line.getConversionFactor(), line.getBaseQuantity());
                        stop.addItem(DeliveryTripStopItem.builder().stop(stop).product(line.getProduct())
                                .productCode(line.getProduct().getCode()).productName(line.getProduct().getName())
                                .sourceVoucherItemId(line.getId()).orderItemId(original.getId()).unit(line.getUnit())
                                .quantity(line.getQuantity()).conversionFactor(line.getConversionFactor()).baseUnit(line.getBaseUnit())
                                .baseQuantity(line.getBaseQuantity()).lotNumber(line.getLotNumber())
                                .manufacturingDate(line.getManufacturingDate()).expiryDate(line.getExpiryDate())
                                .unitPrice(original.getPrice()).totalPrice(deliveryLineValue(original, line.getQuantity())).build());
                    }
                    if (stop.getItems().isEmpty()) throw new IllegalArgumentException("Phiếu xuất không có hàng để giao");
                    stop.setDeliveryAmount(stop.getItems().stream().map(DeliveryTripStopItem::getTotalPrice).reduce(BigDecimal.ZERO, BigDecimal::add));
                    trip.addStop(stop); added = true;
                }
                if (!added) throw new IllegalArgumentException("Không còn phiếu xuất chưa gán chuyến của đơn này");
            }
        }
        if (trip.getStops().size() > 100 || trip.getStops().stream().mapToInt(s -> s.getItems().size()).sum() > 1000)
            throw new IllegalArgumentException("Chuyến vượt 100 điểm hoặc 1000 dòng hàng");
        DeliveryTrip saved = tripRepository.saveAndFlush(trip);
        for (Order order : locked.values()) {
            if (!"SHIPPING".equals(order.getStatus())) {
                String old = order.getStatus(); order.setStatus("SHIPPING"); orderRepository.save(order);
                orderStatusHistoryRepository.save(OrderStatusHistory.builder().order(order).previousStatus(old)
                        .newStatus("SHIPPING").statusDisplayName("Đang giao").changedBy(username).changedAt(departure)
                        .note("Gán chuyến " + code).build());
            }
        }
        auditLogService.logAction("DELIVERY_TRIP", code, "CREATE_TRIP", null, "IN_PROGRESS",
                "Tạo chuyến từ chứng từ xuất, " + saved.getStops().size() + " điểm", username);
        return mapToTripResponse(saved);
    }

    private void requireShipment(WarehouseVoucher voucher, Long warehouseId, Scope scope) {
        if (voucher.getWarehouse() == null) throw new IllegalArgumentException("Phiếu không có kho");
        scope.require(voucher.getWarehouse().getId());
        if (!warehouseId.equals(voucher.getWarehouse().getId())) throw new AccessDeniedException("Phiếu xuất thuộc kho khác");
        if (voucher.getType() != WarehouseVoucherType.EXPORT || !"CONFIRMED".equals(voucher.getStatus()) || voucher.getShipmentOrderId() == null)
            throw new IllegalArgumentException("Chỉ gán phiếu xuất theo đơn đã xác nhận");
    }
    private DeliveryTripStop orderStop(DeliveryTrip trip, Order order, WarehouseVoucher voucher, CreateDeliveryTripStopRequest input) {
        var point = order.getDeliveryPoint(); var dealer = order.getDealer();
        String address = point != null ? point.getAddress() : dealer == null ? null : dealer.getAddress();
        return DeliveryTripStop.builder().trip(trip).order(order).orderCode(order.getOrderCode())
                .shipmentVoucher(voucher).voucherCode(voucher == null ? null : voucher.getVoucherCode())
                .dealer(dealer).dealerName(dealer == null ? order.getCustomerName() : dealer.getName()).deliveryPoint(point)
                .receiverName(point == null ? dealer == null ? order.getCustomerName() : dealer.getName() : point.getReceiverName())
                .receiverPhone(point == null ? dealer == null ? null : dealer.getPhone() : point.getPhone())
                .deliveryAddress(StringUtils.hasText(address) ? address : "Tại kho")
                .deliveryAmount(order.getTotalAmount() == null ? BigDecimal.ZERO : order.getTotalAmount())
                .stopOrder(trip.getStops().size() + 1).status(DeliveryStopStatus.PENDING).returnedToWarehouse(false)
                .note(optionalText(input.getNote(), 500)).build();
    }
    private DeliveryTripStopItem legacyShipmentLine(OrderItem line, DeliveryTripStop stop) {
        validateShipmentQuantity(line.getQuantity(), line.getConversionFactor(), line.getBaseQuantity());
        if (Boolean.TRUE.equals(line.getProduct().getManageLot()))
            throw new IllegalArgumentException("Đơn cũ quản lý lô thiếu phiếu xuất; cần đối soát lô trước khi giao");
        return DeliveryTripStopItem.builder().stop(stop).product(line.getProduct()).productCode(line.getProduct().getCode())
                .productName(line.getProduct().getName()).orderItemId(line.getId()).unit(line.getUnit())
                .quantity(line.getQuantity()).conversionFactor(line.getConversionFactor()).baseQuantity(line.getBaseQuantity())
                .baseUnit(line.getBaseUnit()).unitPrice(line.getPrice()).totalPrice(deliveryLineValue(line, line.getQuantity())).build();
    }
    private BigDecimal deliveryLineValue(OrderItem line, BigDecimal quantity) {
        if (line.getQuantity() == null || line.getQuantity().signum() <= 0) throw new IllegalArgumentException("Dòng đơn không có số lượng");
        return (line.getTotalAmount() == null ? BigDecimal.ZERO : line.getTotalAmount())
                .multiply(quantity).divide(line.getQuantity(), 2, java.math.RoundingMode.HALF_UP);
    }
    private void validateShipmentQuantity(BigDecimal quantity, BigDecimal factor, BigDecimal base) {
        if (quantity == null || factor == null || base == null || quantity.signum() <= 0 || factor.signum() <= 0
                || base.signum() <= 0 || quantity.multiply(factor).compareTo(base) != 0 || base.stripTrailingZeros().scale() > 4)
            throw new IllegalArgumentException("Số lượng/quy đổi chứng từ xuất không hợp lệ");
    }
    private String optionalText(String value, int max) {
        if (!StringUtils.hasText(value)) return null;
        if (value.trim().length() > max) throw new IllegalArgumentException("Nội dung vượt " + max + " ký tự");
        return value.trim();
    }
    private String requiredText(String value, int max, String label) {
        String text = optionalText(value, max);
        if (text == null) throw new IllegalArgumentException(label + " không được để trống");
        return text;
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
        if (proof == null || proof.isEmpty() || proof.getSize() > MAX_PROOF_BYTES) {
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
                    if ((long) reader.getWidth(0) * reader.getHeight(0) > MAX_PROOF_PIXELS)
                        throw new IllegalArgumentException("Ảnh vượt quá 20 triệu điểm ảnh");
                    if (reader.read(0) == null) throw new IllegalArgumentException("Ảnh không hợp lệ");
                    contentType = "png".equalsIgnoreCase(format) ? "image/png" : "image/jpeg";
                } finally { reader.dispose(); }
            }
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("Không đọc được ảnh chứng từ", e);
        }
        if (stop.getSignedProof() != null) throw new IllegalStateException("Chứng từ đã được xác nhận");
        // Lock the order before dirtying proof/stop rows: a later locking query can auto-flush them.
        // Returns acquire order -> inventory -> stops, so flushing a stop first would invert that order.
        if (stop.getOrder() != null) lockStopOrder(stop, trip);
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
    public DeliveryTripResponse updateTrip(Long id, UpdateDeliveryTripRequest request, String username) {
        Scope scope = checkScope(true);

        DeliveryTrip trip = tripRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy chuyến giao hàng với ID: " + id));

        scope.require(trip.getWarehouse().getId());

        if (trip.getStatus() != DeliveryTripStatus.IN_PROGRESS) {
            throw new IllegalStateException("Chỉ có thể cập nhật thông tin chuyến giao khi chuyến đang chạy (IN_PROGRESS)");
        }

        if (!StringUtils.hasText(request.getDriverName())) {
            throw new IllegalArgumentException("Tên tài xế không được để trống");
        }
        if (!StringUtils.hasText(request.getVehiclePlate())) {
            throw new IllegalArgumentException("Biển số xe không được để trống");
        }

        trip.setDriverName(request.getDriverName().trim());
        trip.setDriverPhone(request.getDriverPhone() != null ? request.getDriverPhone().trim() : null);
        trip.setVehiclePlate(request.getVehiclePlate().trim());

        if (request.getRouteName() != null) {
            trip.setRouteName(request.getRouteName().trim());
        }

        if (request.getTerritoryId() != null) {
            Territory territory = territoryRepository.findById(request.getTerritoryId())
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy khu vực với ID: " + request.getTerritoryId()));
            trip.setTerritory(territory);
        }

        if (request.getExpectedCompletionTime() != null) {
            if (trip.getDepartureTime() != null && request.getExpectedCompletionTime().isBefore(trip.getDepartureTime())) {
                throw new IllegalArgumentException("Thời gian dự kiến hoàn thành phải sau thời điểm xuất phát");
            }
            trip.setExpectedCompletionTime(request.getExpectedCompletionTime());
        }

        if (request.getNote() != null) {
            trip.setNote(request.getNote().trim());
        }

        trip.setUpdatedAt(LocalDateTime.now());
        DeliveryTrip saved = tripRepository.save(trip);

        auditLogService.logAction("DELIVERY_TRIP", saved.getTripCode(), "UPDATE_TRIP",
                null, saved.getStatus().name(),
                "Cập nhật tài xế (" + saved.getDriverName() + "), phương tiện (" + saved.getVehiclePlate()
                        + ") và tuyến giao " + (saved.getRouteName() != null ? saved.getRouteName() : ""),
                username);

        return mapToTripResponse(saved);
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
            stop.setFailedReason(requiredText((request.getFailureReasonCode() == null ? "" : request.getFailureReasonCode().getLabel() + ": ")
                    + request.getFailedReason().trim(), 500, "Lý do giao thất bại"));
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
            if (stop.getOrder() != null) lockStopOrder(stop, trip);
            stop.setStatus(DeliveryStopStatus.DELIVERED);
            stop.setFailedReason(null);
            stop.setFailedAt(null);
            stop.setNote(request.getNote() != null ? request.getNote().trim() : stop.getNote());

            applyDeliveredGoods(stop, trip, username);

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

    private Order lockStopOrder(DeliveryTripStop stop, DeliveryTrip trip) {
        Order order = orderRepository.findByIdForUpdate(stop.getOrder().getId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy đơn gốc"));
        if (order.getWarehouse() == null || !trip.getWarehouse().getId().equals(order.getWarehouse().getId()))
            throw new AccessDeniedException("Đơn gốc không thuộc kho chuyến giao");
        return order;
    }

    private OrderItem normalizeShipmentLine(Order order, DeliveryTripStopItem item) {
        if (item.getProduct() == null || item.getQuantity() == null || item.getQuantity().signum() <= 0)
            throw new IllegalArgumentException("Dòng giao không hợp lệ");
        List<OrderItem> matches = order.getItems().stream().filter(i -> item.getOrderItemId() != null
                ? item.getOrderItemId().equals(i.getId())
                : i.getProduct().getId().equals(item.getProduct().getId()) && Objects.equals(i.getUnit(), item.getUnit())).toList();
        if (matches.size() != 1) throw new IllegalArgumentException("Dòng giao cũ không xác định được dòng đơn; cần đối soát");
        OrderItem original = matches.get(0);
        if (!original.getProduct().getId().equals(item.getProduct().getId()) || !Objects.equals(original.getUnit(), item.getUnit()))
            throw new IllegalArgumentException("Dòng giao không khớp đơn gốc");
        if (item.getBaseQuantity() == null) {
            if (Boolean.TRUE.equals(original.getProduct().getManageLot()))
                throw new IllegalArgumentException("Dòng giao cũ thiếu lô xuất; cần đối soát, không tự suy đoán lô");
            item.setConversionFactor(original.getConversionFactor()); item.setBaseUnit(original.getBaseUnit());
            item.setBaseQuantity(item.getQuantity().multiply(original.getConversionFactor())); item.setOrderItemId(original.getId());
        }
        validateShipmentQuantity(item.getQuantity(), item.getConversionFactor(), item.getBaseQuantity());
        if (item.getConversionFactor().compareTo(original.getConversionFactor()) != 0 || !Objects.equals(item.getBaseUnit(), original.getBaseUnit()))
            throw new IllegalArgumentException("Quy đổi giao không khớp số liệu đóng băng của đơn");
        return original;
    }

    private void applyDeliveredGoods(DeliveryTripStop stop, DeliveryTrip trip, String username) {
        if (stop.getOrder() == null) return; // Manual itinerary never creates invoice/stock movements.
        Order order = lockStopOrder(stop, trip);
        if (!Set.of("EXPORTED", "SHIPPED", "SHIPPING").contains(order.getStatus()))
            throw new IllegalStateException("Chỉ xác nhận đơn đã xuất/đang giao và chưa giao đủ");
        if (stop.getItems().isEmpty()) throw new IllegalArgumentException("Điểm giao thiếu chi tiết phiếu xuất; cần đối soát");
        Map<OrderItem, BigDecimal> increments = new LinkedHashMap<>();
        for (var item : stop.getItems()) increments.merge(normalizeShipmentLine(order, item), item.getQuantity(), BigDecimal::add);
        for (var entry : increments.entrySet()) {
            OrderItem item = entry.getKey(); BigDecimal prior = item.getDeliveredQuantity() == null ? BigDecimal.ZERO : item.getDeliveredQuantity();
            BigDecimal delivered = prior.add(entry.getValue());
            if (delivered.compareTo(item.effectiveExportedQuantity()) > 0 || delivered.compareTo(item.getQuantity()) > 0)
                throw new IllegalArgumentException("Thực giao vượt lượng đã xuất của dòng đơn");
            item.setDeliveredQuantity(delivered); item.setDeliveredBaseQuantity(delivered.multiply(item.getConversionFactor()));
        }
        boolean complete = order.getItems().stream().allMatch(i -> i.getDeliveredQuantity() != null && i.getDeliveredQuantity().compareTo(i.getQuantity()) == 0);
        String previous = order.getStatus(); order.setStatus(complete ? "DELIVERED" : "SHIPPING");
        if (complete) order.setActualDeliveryDate(stop.getDeliveredAt().toLocalDate());
        ordersHistory(order, previous, username, "Giao " + (complete ? "đủ đơn" : "một phần") + " theo chuyến " + trip.getTripCode());
        orderRepository.save(order);
    }

    private void ordersHistory(Order order, String previous, String username, String note) {
        if (orderStatusHistoryRepository != null) orderStatusHistoryRepository.save(OrderStatusHistory.builder().order(order)
                .previousStatus(previous).newStatus(order.getStatus()).statusDisplayName(OrderStatus.getDisplayName(order.getStatus()))
                .changedBy(username).changedAt(LocalDateTime.now()).note(note).build());
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

        if (trip.getStops() != null) {
            for (DeliveryTripStop stop : trip.getStops()) {
                if (stop.getOrder() != null && "SHIPPING".equalsIgnoreCase(stop.getOrder().getStatus())) {
                    Order order = stop.getOrder();
                    order.setStatus("EXPORTED");
                    orderRepository.save(order);
                    if (orderStatusHistoryRepository != null) {
                        orderStatusHistoryRepository.save(OrderStatusHistory.builder()
                                .order(order)
                                .previousStatus("SHIPPING")
                                .newStatus("EXPORTED")
                                .statusDisplayName("Đã xuất")
                                .changedBy(username)
                                .changedAt(LocalDateTime.now())
                                .note("Hủy chuyến giao " + trip.getTripCode() + ": " + cancelReason.trim())
                                .build());
                    }
                }
            }
        }

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

        boolean closedPending = stop.getStatus() == DeliveryStopStatus.PENDING && trip.getStatus() != DeliveryTripStatus.IN_PROGRESS;
        if (stop.getStatus() != DeliveryStopStatus.FAILED && !closedPending) {
            throw new IllegalStateException("Chỉ nhập trả điểm giao thất bại hoặc điểm chưa giao của chuyến đã đóng/hủy");
        }

        if (Boolean.TRUE.equals(stop.getReturnedToWarehouse())) {
            throw new IllegalStateException("Hàng của điểm giao này đã được nhập trả kho trước đó");
        }

        if (!StringUtils.hasText(request.getReturnReason())) {
            throw new IllegalArgumentException("Lý do nhập trả kho không được để trống");
        }

        if (stop.getOrder() == null || stop.getItems() == null || stop.getItems().isEmpty())
            throw new IllegalArgumentException("Điểm giao không có chứng từ xuất gốc; cần đối soát, không được tự cộng tồn");
        Order order = lockStopOrder(stop, trip);
        Map<OrderItem, BigDecimal> returned = new LinkedHashMap<>();
        for (var item : stop.getItems()) returned.merge(normalizeShipmentLine(order, item), item.getQuantity(), BigDecimal::add);
        boolean reserveAgain = !Set.of("CANCELLED", "REJECTED").contains(order.getStatus());
        for (var entry : returned.entrySet()) {
            BigDecimal delivered = entry.getKey().getDeliveredQuantity() == null ? BigDecimal.ZERO : entry.getKey().getDeliveredQuantity();
            if (reserveAgain && entry.getValue().compareTo(entry.getKey().effectiveExportedQuantity().subtract(delivered)) > 0)
                throw new IllegalArgumentException("Lượng nhập trả vượt lượng đã xuất nhưng chưa giao");
        }
        Map<Long, List<DeliveryTripStopItem>> byProduct = stop.getItems().stream()
                .collect(Collectors.groupingBy(i -> i.getProduct().getId(), TreeMap::new, Collectors.toList()));
        // Lock inventory and lots in the same product order as shipment confirmation.
        for (var entry : byProduct.entrySet()) {
            WarehouseInventory stock = inventoryRepository.findByWarehouseIdAndProductIdForUpdate(trip.getWarehouse().getId(), entry.getKey())
                    .orElseThrow(() -> new IllegalArgumentException("Thiếu dòng tồn kho gốc; cần đối soát trước khi nhập trả"));
            var lotRows = lotInventoryRepository.findByWarehouseIdAndProductIdForUpdate(trip.getWarehouse().getId(), entry.getKey());
            BigDecimal amount = entry.getValue().stream().map(DeliveryTripStopItem::getBaseQuantity).reduce(BigDecimal.ZERO, BigDecimal::add);
            for (var item : entry.getValue()) {
                if (StringUtils.hasText(item.getLotNumber())) {
                    WarehouseLotInventory lot = lotRows.stream().filter(l -> item.getLotNumber().equals(l.getLotNumber())).findFirst().orElse(null);
                    if (lot == null) {
                        lot = WarehouseLotInventory.builder().warehouse(trip.getWarehouse()).product(item.getProduct()).lotNumber(item.getLotNumber())
                                .baseUnit(item.getBaseUnit()).manufacturingDate(item.getManufacturingDate()).expiryDate(item.getExpiryDate()).build();
                        lotRows = new ArrayList<>(lotRows); lotRows.add(lot);
                    } else if (!Objects.equals(lot.getBaseUnit(), item.getBaseUnit()) || !Objects.equals(lot.getExpiryDate(), item.getExpiryDate())
                            || !Objects.equals(lot.getManufacturingDate(), item.getManufacturingDate())) {
                        throw new IllegalArgumentException("Thông tin lô gốc đã thay đổi; cần đối soát trước khi nhập trả");
                    }
                    lot.addBaseQuantity(item.getBaseQuantity()); lotInventoryRepository.save(lot);
                } else if (Boolean.TRUE.equals(item.getProduct().getManageLot())) {
                    throw new IllegalArgumentException("Không xác định được lô gốc để nhập trả");
                }
            }
            stock.setBaseQuantity(stock.getBaseQuantity().add(amount));
            if (reserveAgain) stock.setReservedQuantity((stock.getReservedQuantity() == null ? BigDecimal.ZERO : stock.getReservedQuantity()).add(amount));
            inventoryRepository.save(stock);
        }
        stop.setReturnedToWarehouse(true); stop.setReturnedAt(LocalDateTime.now());
        stop.setReturnReason(requiredText(request.getReturnReason(), 500, "Lý do nhập trả"));
        if (request.getNote() != null) stop.setNote(optionalText(request.getNote(), 500));
        if (reserveAgain) {
            returned.forEach((line, quantity) -> line.setExportedQuantity(line.effectiveExportedQuantity().subtract(quantity)));
            order.setBackordered(true); order.setInventoryReserved(true); order.setReservationExpiresAt(null);
            boolean onRoad = stopRepository.findByOrderIdForUpdate(order.getId()).stream().anyMatch(s ->
                    !s.getId().equals(stop.getId()) && s.getStatus() != DeliveryStopStatus.DELIVERED && !Boolean.TRUE.equals(s.getReturnedToWarehouse()));
            String previous = order.getStatus(); order.setStatus(onRoad ? "SHIPPING" : "PICKING");
            ordersHistory(order, previous, username, "Nhập trả chuyến " + trip.getTripCode() + "; giữ chỗ phần cần giao lại");
            orderRepository.save(order);
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
        return getEligibleOrders(warehouseId, null, null);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DeliveryEligibleOrderResponse> getEligibleOrders(Long warehouseId, Long territoryId, String keyword) {
        Scope scope = checkScope(false);
        if (warehouseId == null) throw new IllegalArgumentException("Chọn kho để tra cứu phiếu xuất");
        scope.require(warehouseId);
        Specification<Order> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("warehouse").get("id"), warehouseId));
            predicates.add(root.get("status").in("EXPORTED", "SHIPPED", "SHIPPING", "PICKING"));
            if (territoryId != null) predicates.add(cb.equal(root.get("dealer").get("territory").get("id"), territoryId));
            if (StringUtils.hasText(keyword)) {
                String term = "%" + keyword.trim().toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
                predicates.add(cb.or(cb.like(cb.lower(root.get("orderCode")), term, '!'),
                        cb.like(cb.lower(root.get("customerName")), term, '!')));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        return orderRepository.findAll(spec, Sort.by(Sort.Direction.DESC, "createdAt")).stream().flatMap(order -> {
            var vouchers = warehouseVoucherRepository.findByShipmentOrderIdOrderByIdAsc(order.getId());
            if (!vouchers.isEmpty()) return vouchers.stream()
                    .filter(v -> v.getType() == WarehouseVoucherType.EXPORT && "CONFIRMED".equals(v.getStatus())
                            && v.getWarehouse() != null && warehouseId.equals(v.getWarehouse().getId())
                            && !stopRepository.existsByShipmentVoucherId(v.getId())
                            && v.getItems().stream().anyMatch(i -> i.getQuantity() != null && i.getQuantity().signum() > 0))
                    .map(v -> eligibleShipment(order, v));
            boolean legacy = Set.of("EXPORTED", "SHIPPED").contains(order.getStatus())
                    && !Boolean.TRUE.equals(order.getBackordered())
                    && order.getItems().stream().allMatch(i -> i.getExportedQuantity() == null)
                    && !stopRepository.existsByOrderId(order.getId());
            return legacy ? java.util.stream.Stream.of(eligibleShipment(order, null)) : java.util.stream.Stream.<DeliveryEligibleOrderResponse>empty();
        }).toList();
    }
    private DeliveryEligibleOrderResponse eligibleShipment(Order order, WarehouseVoucher voucher) {
        DealerDeliveryPoint point = order.getDeliveryPoint(); Dealer dealer = order.getDealer();
        Territory territory = dealer == null ? null : dealer.getTerritory();
        BigDecimal amount = order.getTotalAmount();
        if (voucher != null) {
            amount = voucher.getItems().stream().filter(v -> v.getQuantity() != null && v.getQuantity().signum() > 0)
                    .map(v -> order.getItems().stream().filter(i -> i.getId().equals(v.getShipmentOrderItemId())).findFirst()
                            .map(i -> deliveryLineValue(i, v.getQuantity())).orElse(BigDecimal.ZERO))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }
        return DeliveryEligibleOrderResponse.builder().orderId(order.getId()).orderCode(order.getOrderCode())
                .dealerId(dealer == null ? null : dealer.getId()).dealerName(dealer == null ? order.getCustomerName() : dealer.getName())
                .deliveryPointId(point == null ? null : point.getId())
                .receiverName(point != null ? point.getReceiverName() : dealer == null ? order.getCustomerName() : dealer.getName())
                .receiverPhone(point != null ? point.getPhone() : dealer == null ? null : dealer.getPhone())
                .deliveryAddress(point != null ? point.getAddress() : dealer == null ? "Tại kho" : dealer.getAddress())
                .expectedDeliveryDate(order.getExpectedDeliveryDate()).totalAmount(amount).status(order.getStatus())
                .territoryId(territory == null ? null : territory.getId()).territoryCode(territory == null ? null : territory.getCode())
                .territoryName(territory == null ? null : territory.getName())
                .shipmentVoucherId(voucher == null ? null : voucher.getId()).shipmentVoucherCode(voucher == null ? null : voucher.getVoucherCode()).build();
    }

    @Override
    @Transactional(readOnly = true)
    public DeliveryTripManifestResponse getManifest(Long tripId, String username) {
        return deliveryTripPdfService.getManifest(tripId, username);
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] generateManifestPdf(Long tripId, String username) {
        return deliveryTripPdfService.generateManifestPdf(tripId, username);
    }

    @Override
    @Transactional(readOnly = true)
    public String generateManifestPrintHtml(Long tripId, String username) {
        return deliveryTripPdfService.generateManifestPrintHtml(tripId, username);
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
                .shipmentVoucherId(stop.getShipmentVoucher() == null ? null : stop.getShipmentVoucher().getId())
                .voucherCode(stop.getVoucherCode())
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
                .returnReason(stop.getReturnReason())
                .canConfirm(checkScope(false).write() && stop.getOrder() != null && stop.getTrip().getStatus() == DeliveryTripStatus.IN_PROGRESS
                        && stop.getStatus() != DeliveryStopStatus.DELIVERED && !Boolean.TRUE.equals(stop.getReturnedToWarehouse()) && stop.getSignedProof() == null)
                .canReturn(checkScope(false).write() && stop.getOrder() != null && !Boolean.TRUE.equals(stop.getReturnedToWarehouse())
                        && (stop.getStatus() == DeliveryStopStatus.FAILED || (stop.getStatus() == DeliveryStopStatus.PENDING && stop.getTrip().getStatus() != DeliveryTripStatus.IN_PROGRESS)))
                .orderStatus(stop.getOrder() == null ? null : stop.getOrder().getStatus())
                .orderStatusDisplayName(stop.getOrder() == null ? null : OrderStatus.getDisplayName(stop.getOrder().getStatus()))
                .orderFullyDelivered(stop.getOrder() != null && "DELIVERED".equals(stop.getOrder().getStatus()))
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
                .baseQuantity(item.getBaseQuantity()).baseUnit(item.getBaseUnit())
                .lotNumber(item.getLotNumber()).expiryDate(item.getExpiryDate())
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
