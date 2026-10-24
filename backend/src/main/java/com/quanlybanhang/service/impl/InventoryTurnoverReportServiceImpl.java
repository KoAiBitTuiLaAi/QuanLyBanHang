package com.quanlybanhang.service.impl;

import com.quanlybanhang.dto.response.*;
import com.quanlybanhang.model.*;
import com.quanlybanhang.repository.*;
import com.quanlybanhang.service.InventoryTurnoverReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Triển khai nghiệp vụ Báo cáo tồn kho và vòng quay hàng (S8-07):
 * 1. Giá trị tồn theo kho và theo nhóm hàng.
 * 2. Số ngày tồn bình quân của từng SKU, chỉ ra hàng chậm luân chuyển đang chôn vốn.
 * 3. Hỗ trợ dữ liệu xuất Excel đầy đủ.
 *
 * Phân quyền ma trận DB (RolePermissionConfig):
 * - Bắt buộc có quyền REPORT_INVENTORY hoặc ROLE_ADMIN.
 * - Tuyệt đối không dùng role-based fallback.
 * - Giới hạn phạm vi kho theo phân công của người dùng (nếu tài khoản có gán kho cụ thể).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class InventoryTurnoverReportServiceImpl implements InventoryTurnoverReportService {

    public static final int DEFAULT_PERIOD_DAYS = 30;
    public static final int DEFAULT_SLOW_MOVING_THRESHOLD_DAYS = 60;
    private static final int MAX_PERIOD_DAYS = 3660;
    private static final Set<String> VALID_VOUCHER_STATUSES = Set.of("RECORDED", "CONFIRMED", "ADJUSTED");
    private static final Set<String> VALID_TURNOVER_STATUSES = Set.of(
            "ALL", "FAST_MOVING", "NORMAL_MOVING", "SLOW_MOVING", "DEAD_STOCK", "OUT_OF_STOCK"
    );

    private final WarehouseRepository warehouseRepository;
    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;
    private final WarehouseInventoryRepository inventoryRepository;
    private final WarehouseVoucherRepository voucherRepository;
    private final OrderRepository orderRepository;
    private final UserRepository userRepository;

    @Autowired(required = false)
    private WarehouseTransferRepository transferRepository;

    @Autowired(required = false)
    private WarehouseStockCountRepository stockCountRepository;

    @Autowired(required = false)
    private DealerReturnRepository dealerReturnRepository;

    @Autowired(required = false)
    private DeliveryTripStopItemRepository deliveryTripStopItemRepository;

    @Autowired(required = false)
    private OrderStatusHistoryRepository orderStatusHistoryRepository;

    @Override
    public InventoryTurnoverOptionsResponse getOptions() {
        requireReportPermission();
        List<Warehouse> allowedWarehouses = resolveVisibleWarehouses();
        List<Category> categories = categoryRepository.findAllByOrderByLevelAscNameAsc();

        return InventoryTurnoverOptionsResponse.builder()
                .warehouses(allowedWarehouses.stream()
                        .map(w -> new NamedResourceResponse(w.getId(), w.getCode(), w.getName()))
                        .toList())
                .categories(categories.stream()
                        .map(c -> InventoryTurnoverOptionsResponse.CategoryOption.builder()
                                .id(c.getId())
                                .code(c.getCode())
                                .name(c.getName())
                                .level(c.getLevel())
                                .parentId(c.getParent() != null ? c.getParent().getId() : null)
                                .parentName(c.getParent() != null ? c.getParent().getName() : null)
                                .build())
                        .toList())
                .periodDaysOptions(List.of(7, 30, 60, 90, 180, 365))
                .defaultPeriodDays(DEFAULT_PERIOD_DAYS)
                .slowMovingThresholdDaysOptions(List.of(30, 45, 60, 90, 120, 180))
                .defaultSlowMovingThresholdDays(DEFAULT_SLOW_MOVING_THRESHOLD_DAYS)
                .turnoverStatuses(List.of(
                        new InventoryTurnoverOptionsResponse.TurnoverStatusOption(
                                "FAST_MOVING", "Luân chuyển nhanh", "Hàng có tốc độ xuất nhanh, số ngày tồn bình quân thấp"),
                        new InventoryTurnoverOptionsResponse.TurnoverStatusOption(
                                "NORMAL_MOVING", "Luân chuyển bình thường", "Hàng có số ngày tồn bình quân dưới ngưỡng chậm luân chuyển"),
                        new InventoryTurnoverOptionsResponse.TurnoverStatusOption(
                                "SLOW_MOVING", "Chậm luân chuyển", "Hàng có số ngày tồn bình quân vượt hoặc bằng ngưỡng chậm luân chuyển"),
                        new InventoryTurnoverOptionsResponse.TurnoverStatusOption(
                                "DEAD_STOCK", "Tồn đọng / Không luân chuyển", "Hàng đang có tồn kho nhưng không phát sinh xuất trong kỳ, đang chôn vốn"),
                        new InventoryTurnoverOptionsResponse.TurnoverStatusOption(
                                "OUT_OF_STOCK", "Hết hàng", "Không còn tồn kho cuối kỳ")
                ))
                .canViewReport(true)
                .canExportExcel(true)
                .build();
    }

    @Override
    public InventoryTurnoverReportResponse getReport(
            Long warehouseId,
            Long categoryId,
            LocalDate fromDate,
            LocalDate toDate,
            Integer periodDays,
            Integer slowMovingThresholdDays,
            String keyword,
            Boolean slowMovingOnly,
            String turnoverStatus,
            Boolean includeZeroStock) {

        requireReportPermission();
        ReportQuery query = validateAndResolveQuery(
                warehouseId, categoryId, fromDate, toDate, periodDays, slowMovingThresholdDays,
                keyword, slowMovingOnly, turnoverStatus, includeZeroStock);

        if (query.scopeWarehouses().isEmpty()) {
            return buildEmptyReport(query);
        }

        return computeReport(query);
    }

    @Override
    public List<WarehouseInventoryValuationResponse> getValuationByWarehouse(
            Long warehouseId,
            Long categoryId,
            LocalDate fromDate,
            LocalDate toDate,
            Integer periodDays,
            Integer slowMovingThresholdDays) {
        return getReport(warehouseId, categoryId, fromDate, toDate, periodDays, slowMovingThresholdDays,
                null, null, null, true).getWarehouseValuations();
    }

    @Override
    public List<CategoryInventoryValuationResponse> getValuationByCategory(
            Long warehouseId,
            Long categoryId,
            LocalDate fromDate,
            LocalDate toDate,
            Integer periodDays,
            Integer slowMovingThresholdDays) {
        return getReport(warehouseId, categoryId, fromDate, toDate, periodDays, slowMovingThresholdDays,
                null, null, null, true).getCategoryValuations();
    }

    @Override
    public List<WarehouseCategoryValuationResponse> getValuationByWarehouseAndCategory(
            Long warehouseId,
            Long categoryId,
            LocalDate fromDate,
            LocalDate toDate,
            Integer periodDays,
            Integer slowMovingThresholdDays) {
        return getReport(warehouseId, categoryId, fromDate, toDate, periodDays, slowMovingThresholdDays,
                null, null, null, true).getWarehouseCategoryValuations();
    }

    @Override
    public Page<SkuInventoryTurnoverResponse> getSkuTurnovers(
            Long warehouseId,
            Long categoryId,
            LocalDate fromDate,
            LocalDate toDate,
            Integer periodDays,
            Integer slowMovingThresholdDays,
            String keyword,
            Boolean slowMovingOnly,
            String turnoverStatus,
            Boolean includeZeroStock,
            int page,
            int size) {

        if (page < 0 || size < 1 || size > 500) {
            throw new IllegalArgumentException("Trang phải không âm; số dòng mỗi trang từ 1 đến 500");
        }
        InventoryTurnoverReportResponse report = getReport(
                warehouseId, categoryId, fromDate, toDate, periodDays, slowMovingThresholdDays,
                keyword, slowMovingOnly, turnoverStatus, includeZeroStock);
        List<SkuInventoryTurnoverResponse> allItems = report.getSkuItems();
        int total = allItems.size();
        int fromIdx = Math.min(page * size, total);
        int toIdx = Math.min(fromIdx + size, total);
        List<SkuInventoryTurnoverResponse> pageContent = allItems.subList(fromIdx, toIdx);
        return new PageImpl<>(pageContent, PageRequest.of(page, size), total);
    }

    @Override
    public List<SkuInventoryTurnoverResponse> getSlowMovingSkus(
            Long warehouseId,
            Long categoryId,
            LocalDate fromDate,
            LocalDate toDate,
            Integer periodDays,
            Integer slowMovingThresholdDays,
            String keyword) {
        InventoryTurnoverReportResponse report = getReport(
                warehouseId, categoryId, fromDate, toDate, periodDays, slowMovingThresholdDays,
                keyword, true, null, false);
        return report.getSlowMovingItems();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // PERMISSION & SCOPE ENFORCEMENT
    // ═════════════════════════════════════════════════════════════════════════

    private void requireReportPermission() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            throw new AccessDeniedException("Bạn chưa đăng nhập");
        }
        Set<String> authorities = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
        if (!authorities.contains("ROLE_ADMIN") && !authorities.contains(Permission.REPORT_INVENTORY.name())) {
            throw new AccessDeniedException("Bạn không có quyền xem báo cáo tồn kho và vòng quay hàng (REPORT_INVENTORY)");
        }
    }

    private List<Warehouse> resolveVisibleWarehouses() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Set<String> authorities = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
        List<Warehouse> allWarehouses = warehouseRepository.findAllByOrderByCodeAsc();
        if (authorities.contains("ROLE_ADMIN")) {
            return allWarehouses;
        }
        if (userRepository == null) {
            return allWarehouses;
        }
        Optional<User> userOpt = userRepository.findByUsername(auth.getName());
        if (userOpt.isEmpty()) {
            return allWarehouses;
        }
        User user = userOpt.get();
        if (user.getWarehouses() != null && !user.getWarehouses().isEmpty()) {
            Set<Long> assignedIds = user.getWarehouses().stream()
                    .map(Warehouse::getId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            return allWarehouses.stream()
                    .filter(w -> assignedIds.contains(w.getId()))
                    .toList();
        }
        return allWarehouses;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // VALIDATION & QUERY RESOLUTION
    // ═════════════════════════════════════════════════════════════════════════

    private record ReportQuery(
            Warehouse selectedWarehouse,
            List<Warehouse> scopeWarehouses,
            Set<Long> scopeWarehouseIds,
            Category selectedCategory,
            Set<Long> targetCategoryIds,
            LocalDate fromDate,
            LocalDate toDate,
            LocalDateTime fromDateTime,
            LocalDateTime toDateTime,
            int periodDays,
            int slowMovingThresholdDays,
            String keyword,
            boolean slowMovingOnly,
            String turnoverStatus,
            boolean includeZeroStock
    ) {}

    private ReportQuery validateAndResolveQuery(
            Long warehouseId,
            Long categoryId,
            LocalDate fromDate,
            LocalDate toDate,
            Integer periodDays,
            Integer slowMovingThresholdDays,
            String keyword,
            Boolean slowMovingOnly,
            String turnoverStatus,
            Boolean includeZeroStock) {

        if (warehouseId != null && warehouseId < 1) {
            throw new IllegalArgumentException("Mã kho không hợp lệ");
        }
        if (categoryId != null && categoryId < 1) {
            throw new IllegalArgumentException("Mã nhóm hàng không hợp lệ");
        }
        if (keyword != null && keyword.length() > 150) {
            throw new IllegalArgumentException("Từ khóa tìm kiếm tối đa 150 ký tự");
        }

        int threshold = slowMovingThresholdDays != null ? slowMovingThresholdDays : DEFAULT_SLOW_MOVING_THRESHOLD_DAYS;
        if (threshold < 1 || threshold > MAX_PERIOD_DAYS) {
            throw new IllegalArgumentException("Ngưỡng số ngày chậm luân chuyển phải từ 1 đến " + MAX_PERIOD_DAYS + " ngày");
        }

        if (periodDays != null && (periodDays < 1 || periodDays > MAX_PERIOD_DAYS)) {
            throw new IllegalArgumentException("Số ngày kỳ báo cáo phải từ 1 đến " + MAX_PERIOD_DAYS + " ngày");
        }

        LocalDate resolvedToDate;
        LocalDate resolvedFromDate;
        int requestedDays = periodDays != null ? periodDays : DEFAULT_PERIOD_DAYS;

        if (fromDate == null && toDate == null) {
            resolvedToDate = LocalDate.now();
            resolvedFromDate = resolvedToDate.minusDays(requestedDays - 1L);
        } else if (fromDate != null && toDate == null) {
            resolvedFromDate = fromDate;
            resolvedToDate = periodDays != null ? fromDate.plusDays(requestedDays - 1L) : LocalDate.now();
        } else if (fromDate == null) {
            resolvedToDate = toDate;
            resolvedFromDate = toDate.minusDays(requestedDays - 1L);
        } else {
            resolvedFromDate = fromDate;
            resolvedToDate = toDate;
        }

        if (resolvedFromDate.isAfter(resolvedToDate)) {
            throw new IllegalArgumentException("Từ ngày không được lớn hơn đến ngày");
        }

        long spanDays = ChronoUnit.DAYS.between(resolvedFromDate, resolvedToDate) + 1L;
        if (spanDays < 1 || spanDays > MAX_PERIOD_DAYS) {
            throw new IllegalArgumentException("Khoảng thời gian báo cáo phải từ 1 đến " + MAX_PERIOD_DAYS + " ngày");
        }

        String normalizedStatus = null;
        if (turnoverStatus != null && !turnoverStatus.isBlank()) {
            normalizedStatus = turnoverStatus.trim().toUpperCase(Locale.ROOT);
            if (!VALID_TURNOVER_STATUSES.contains(normalizedStatus)) {
                throw new IllegalArgumentException("Trạng thái luân chuyển không hợp lệ: " + turnoverStatus);
            }
            if ("ALL".equals(normalizedStatus)) {
                normalizedStatus = null;
            }
        }

        List<Warehouse> visibleWarehouses = resolveVisibleWarehouses();
        Map<Long, Warehouse> visibleMap = visibleWarehouses.stream()
                .collect(Collectors.toMap(Warehouse::getId, w -> w, (a, b) -> a, LinkedHashMap::new));

        Warehouse selectedWarehouse = null;
        List<Warehouse> scopeWarehouses;
        if (warehouseId != null) {
            Warehouse target = warehouseRepository.findById(warehouseId)
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy kho với ID: " + warehouseId));
            if (!visibleMap.containsKey(warehouseId)) {
                throw new AccessDeniedException("Bạn không có quyền xem báo cáo của kho này");
            }
            selectedWarehouse = target;
            scopeWarehouses = List.of(target);
        } else {
            scopeWarehouses = visibleWarehouses;
        }

        Set<Long> scopeWarehouseIds = scopeWarehouses.stream()
                .map(Warehouse::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Category selectedCategory = null;
        Set<Long> targetCategoryIds = null;
        if (categoryId != null) {
            selectedCategory = categoryRepository.findById(categoryId)
                    .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy nhóm hàng với ID: " + categoryId));
            targetCategoryIds = resolveCategorySubtreeIds(categoryId);
        }

        String normalizedKeyword = (keyword != null && !keyword.isBlank())
                ? keyword.trim().toLowerCase(Locale.ROOT)
                : null;

        return new ReportQuery(
                selectedWarehouse,
                scopeWarehouses,
                scopeWarehouseIds,
                selectedCategory,
                targetCategoryIds,
                resolvedFromDate,
                resolvedToDate,
                resolvedFromDate.atStartOfDay(),
                resolvedToDate.atTime(LocalTime.MAX),
                (int) spanDays,
                threshold,
                normalizedKeyword,
                Boolean.TRUE.equals(slowMovingOnly),
                normalizedStatus,
                includeZeroStock == null || includeZeroStock
        );
    }

    private Set<Long> resolveCategorySubtreeIds(Long rootCategoryId) {
        List<Category> allCategories = categoryRepository.findAllByOrderByLevelAscNameAsc();
        Map<Long, List<Long>> childrenMap = new HashMap<>();
        for (Category cat : allCategories) {
            if (cat.getParent() != null && cat.getParent().getId() != null) {
                childrenMap.computeIfAbsent(cat.getParent().getId(), k -> new ArrayList<>()).add(cat.getId());
            }
        }
        Set<Long> result = new LinkedHashSet<>();
        Deque<Long> queue = new ArrayDeque<>();
        queue.add(rootCategoryId);
        while (!queue.isEmpty()) {
            Long current = queue.poll();
            if (result.add(current)) {
                List<Long> children = childrenMap.getOrDefault(current, List.of());
                queue.addAll(children);
            }
        }
        return result;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // CORE VALUATION & TURNOVER ENGINE
    // ═════════════════════════════════════════════════════════════════════════

    private record CellKey(Long warehouseId, Long productId) {}

    private static class MovementAccumulator {
        BigDecimal inboundInPeriod = BigDecimal.ZERO;
        BigDecimal outboundInPeriod = BigDecimal.ZERO;
        BigDecimal netDeltaBefore = BigDecimal.ZERO;
        BigDecimal netDeltaInPeriod = BigDecimal.ZERO;
        BigDecimal netDeltaAfter = BigDecimal.ZERO;
        LocalDateTime lastOutboundAt;
        LocalDateTime lastInboundAt;

        void recordMovement(LocalDateTime time, BigDecimal signedChange, boolean isTrueInbound, boolean isTrueOutbound,
                            LocalDateTime fromStart, LocalDateTime toEnd) {
            if (signedChange == null || signedChange.signum() == 0) return;
            LocalDateTime effectiveTime = time != null ? time : fromStart;
            if (effectiveTime.isBefore(fromStart)) {
                netDeltaBefore = netDeltaBefore.add(signedChange);
            } else if (effectiveTime.isAfter(toEnd)) {
                netDeltaAfter = netDeltaAfter.add(signedChange);
            } else {
                netDeltaInPeriod = netDeltaInPeriod.add(signedChange);
                if (isTrueInbound && signedChange.signum() > 0) {
                    inboundInPeriod = inboundInPeriod.add(signedChange);
                }
                if (isTrueOutbound && signedChange.signum() < 0) {
                    outboundInPeriod = outboundInPeriod.add(signedChange.abs());
                }
            }
            if (!effectiveTime.isAfter(toEnd)) {
                if (isTrueOutbound && signedChange.signum() < 0) {
                    if (lastOutboundAt == null || effectiveTime.isAfter(lastOutboundAt)) {
                        lastOutboundAt = effectiveTime;
                    }
                }
                if (isTrueInbound && signedChange.signum() > 0) {
                    if (lastInboundAt == null || effectiveTime.isAfter(lastInboundAt)) {
                        lastInboundAt = effectiveTime;
                    }
                }
            }
        }
    }

    private record CellValuation(
            Warehouse warehouse,
            Product product,
            BigDecimal unitCost,
            BigDecimal openingQuantity,
            BigDecimal inboundQuantity,
            BigDecimal outboundQuantity,
            BigDecimal closingQuantity,
            BigDecimal reservedQuantity,
            BigDecimal availableQuantity,
            BigDecimal inTransitQuantity,
            BigDecimal averageInventoryQuantity,
            BigDecimal openingValue,
            BigDecimal inboundValue,
            BigDecimal outboundValue,
            BigDecimal inventoryValue,
            BigDecimal reservedValue,
            BigDecimal availableValue,
            BigDecimal averageInventoryValue,
            LocalDateTime lastOutboundAt,
            LocalDateTime lastInboundAt,
            boolean slowMovingAtWarehouse
    ) {}

    private InventoryTurnoverReportResponse computeReport(ReportQuery query) {
        List<Product> allProducts = productRepository.findAll().stream()
                .sorted(Comparator.comparing(Product::getCode, Comparator.nullsLast(String::compareToIgnoreCase))
                        .thenComparing(Product::getId, Comparator.nullsLast(Long::compareTo)))
                .toList();

        // Filter products by category subtree if specified
        List<Product> candidateProducts = allProducts.stream()
                .filter(p -> {
                    if (query.targetCategoryIds() != null) {
                        Long catId = p.getCategory() != null ? p.getCategory().getId() : null;
                        if (catId == null || !query.targetCategoryIds().contains(catId)) {
                            return false;
                        }
                    }
                    if (query.keyword() != null) {
                        String code = p.getCode() != null ? p.getCode().toLowerCase(Locale.ROOT) : "";
                        String name = p.getName() != null ? p.getName().toLowerCase(Locale.ROOT) : "";
                        return code.contains(query.keyword()) || name.contains(query.keyword());
                    }
                    return true;
                })
                .toList();

        Set<Long> candidateProductIds = candidateProducts.stream()
                .map(Product::getId)
                .collect(Collectors.toSet());

        // 1. Load current physical inventories in scope warehouses
        List<WarehouseInventory> inventoryRows = inventoryRepository.findAllByOrderByProduct_CodeAsc().stream()
                .filter(inv -> inv.getWarehouse() != null && query.scopeWarehouseIds().contains(inv.getWarehouse().getId()))
                .filter(inv -> inv.getProduct() != null && candidateProductIds.contains(inv.getProduct().getId()))
                .toList();

        Map<CellKey, WarehouseInventory> inventoryMap = new HashMap<>();
        for (WarehouseInventory inv : inventoryRows) {
            inventoryMap.put(new CellKey(inv.getWarehouse().getId(), inv.getProduct().getId()), inv);
        }

        // 2. Collect all historical movements for candidate products in scope warehouses
        Map<CellKey, MovementAccumulator> movements = new HashMap<>();
        Map<Long, BigDecimal[]> fallbackImportCostAccumulator = new HashMap<>(); // productId -> [totalCost, totalBaseQty]
        Set<Long> shipmentBackedOrderIds = new HashSet<>();

        collectVoucherMovements(query, candidateProductIds, movements, fallbackImportCostAccumulator, shipmentBackedOrderIds);
        collectOrderMovements(query, candidateProductIds, movements, shipmentBackedOrderIds);
        collectTransferMovements(query, candidateProductIds, movements);
        collectStockCountMovements(query, candidateProductIds, movements);
        collectDealerReturnMovements(query, candidateProductIds, movements);
        collectDeliveryReturnMovements(query, candidateProductIds, movements);

        // 3. Resolve effective unit cost per product
        Map<Long, BigDecimal> productUnitCosts = new HashMap<>();
        for (Product p : candidateProducts) {
            BigDecimal cost = p.getCostPrice();
            if (cost == null || cost.signum() <= 0) {
                BigDecimal[] imp = fallbackImportCostAccumulator.get(p.getId());
                if (imp != null && imp[1].signum() > 0) {
                    cost = imp[0].divide(imp[1], 2, RoundingMode.HALF_UP);
                } else {
                    cost = BigDecimal.ZERO;
                }
            } else {
                cost = cost.setScale(2, RoundingMode.HALF_UP);
            }
            productUnitCosts.put(p.getId(), cost);
        }

        // 4. Compute CellValuation for every (Warehouse, Product)
        Map<CellKey, CellValuation> cellValuations = new LinkedHashMap<>();
        for (Warehouse w : query.scopeWarehouses()) {
            for (Product p : candidateProducts) {
                CellKey key = new CellKey(w.getId(), p.getId());
                WarehouseInventory inv = inventoryMap.get(key);
                MovementAccumulator mov = movements.get(key);

                BigDecimal physicalNow = inv != null && inv.getBaseQuantity() != null
                        ? inv.getBaseQuantity().max(BigDecimal.ZERO) : BigDecimal.ZERO;
                BigDecimal reservedNow = inv != null && inv.getReservedQuantity() != null
                        ? inv.getReservedQuantity().max(BigDecimal.ZERO) : BigDecimal.ZERO;
                BigDecimal availableNow = physicalNow.subtract(reservedNow).max(BigDecimal.ZERO);
                BigDecimal inTransitNow = inv != null && inv.getInTransitQuantity() != null
                        ? inv.getInTransitQuantity().max(BigDecimal.ZERO) : BigDecimal.ZERO;

                BigDecimal inboundInPeriod = mov != null ? mov.inboundInPeriod : BigDecimal.ZERO;
                BigDecimal outboundInPeriod = mov != null ? mov.outboundInPeriod : BigDecimal.ZERO;
                BigDecimal deltaBefore = mov != null ? mov.netDeltaBefore : BigDecimal.ZERO;
                BigDecimal deltaPeriod = mov != null ? mov.netDeltaInPeriod : BigDecimal.ZERO;
                BigDecimal deltaAfter = mov != null ? mov.netDeltaAfter : BigDecimal.ZERO;

                BigDecimal closingQty = physicalNow.subtract(deltaAfter).max(BigDecimal.ZERO);
                BigDecimal totalRecordedDelta = deltaBefore.add(deltaPeriod).add(deltaAfter);
                BigDecimal unrecordedSeed = physicalNow.subtract(totalRecordedDelta).max(BigDecimal.ZERO);
                BigDecimal openingQty = unrecordedSeed.add(deltaBefore).max(BigDecimal.ZERO);

                BigDecimal avgQty;
                if (openingQty.signum() > 0 || closingQty.signum() > 0) {
                    avgQty = openingQty.add(closingQty).divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
                } else if (inboundInPeriod.signum() > 0 || outboundInPeriod.signum() > 0) {
                    avgQty = inboundInPeriod.max(outboundInPeriod).divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
                } else {
                    avgQty = BigDecimal.ZERO;
                }

                BigDecimal unitCost = productUnitCosts.getOrDefault(p.getId(), BigDecimal.ZERO);
                BigDecimal openingVal = openingQty.multiply(unitCost).setScale(2, RoundingMode.HALF_UP);
                BigDecimal inboundVal = inboundInPeriod.multiply(unitCost).setScale(2, RoundingMode.HALF_UP);
                BigDecimal outboundVal = outboundInPeriod.multiply(unitCost).setScale(2, RoundingMode.HALF_UP);
                BigDecimal closingVal = closingQty.multiply(unitCost).setScale(2, RoundingMode.HALF_UP);
                BigDecimal reservedVal = reservedNow.multiply(unitCost).setScale(2, RoundingMode.HALF_UP);
                BigDecimal availableVal = availableNow.multiply(unitCost).setScale(2, RoundingMode.HALF_UP);
                BigDecimal avgVal = avgQty.multiply(unitCost).setScale(2, RoundingMode.HALF_UP);

                boolean slowAtWh = false;
                if (closingQty.signum() > 0) {
                    if (outboundInPeriod.signum() == 0) {
                        slowAtWh = true;
                    } else {
                        BigDecimal cellDio = avgQty.multiply(BigDecimal.valueOf(query.periodDays()))
                                .divide(outboundInPeriod, 2, RoundingMode.HALF_UP);
                        slowAtWh = cellDio.compareTo(BigDecimal.valueOf(query.slowMovingThresholdDays())) >= 0;
                    }
                }

                cellValuations.put(key, new CellValuation(
                        w, p, unitCost,
                        openingQty, inboundInPeriod, outboundInPeriod, closingQty,
                        reservedNow, availableNow, inTransitNow, avgQty,
                        openingVal, inboundVal, outboundVal, closingVal,
                        reservedVal, availableVal, avgVal,
                        mov != null ? mov.lastOutboundAt : null,
                        mov != null ? mov.lastInboundAt : null,
                        slowAtWh
                ));
            }
        }

        // 5. Build SKU Turnover Responses across scopeWarehouses
        List<SkuInventoryTurnoverResponse> allSkuResponses = new ArrayList<>();
        for (Product p : candidateProducts) {
            BigDecimal openingQty = BigDecimal.ZERO;
            BigDecimal inboundQty = BigDecimal.ZERO;
            BigDecimal outboundQty = BigDecimal.ZERO;
            BigDecimal closingQty = BigDecimal.ZERO;
            BigDecimal reservedQty = BigDecimal.ZERO;
            BigDecimal availableQty = BigDecimal.ZERO;
            BigDecimal inTransitQty = BigDecimal.ZERO;
            LocalDateTime lastOutboundAt = null;
            LocalDateTime lastInboundAt = null;

            List<SkuInventoryTurnoverResponse.WarehouseSkuBreakdown> whBreakdowns = new ArrayList<>();
            Warehouse primaryWarehouse = query.selectedWarehouse();

            for (Warehouse w : query.scopeWarehouses()) {
                CellValuation cv = cellValuations.get(new CellKey(w.getId(), p.getId()));
                if (cv == null) continue;
                openingQty = openingQty.add(cv.openingQuantity());
                inboundQty = inboundQty.add(cv.inboundQuantity());
                outboundQty = outboundQty.add(cv.outboundQuantity());
                closingQty = closingQty.add(cv.closingQuantity());
                reservedQty = reservedQty.add(cv.reservedQuantity());
                availableQty = availableQty.add(cv.availableQuantity());
                inTransitQty = inTransitQty.add(cv.inTransitQuantity());

                if (cv.lastOutboundAt() != null && (lastOutboundAt == null || cv.lastOutboundAt().isAfter(lastOutboundAt))) {
                    lastOutboundAt = cv.lastOutboundAt();
                }
                if (cv.lastInboundAt() != null && (lastInboundAt == null || cv.lastInboundAt().isAfter(lastInboundAt))) {
                    lastInboundAt = cv.lastInboundAt();
                }

                if (cv.closingQuantity().signum() > 0 || cv.outboundQuantity().signum() > 0 || cv.inboundQuantity().signum() > 0 || query.selectedWarehouse() != null) {
                    whBreakdowns.add(SkuInventoryTurnoverResponse.WarehouseSkuBreakdown.builder()
                            .warehouseId(w.getId())
                            .warehouseCode(w.getCode())
                            .warehouseName(w.getName())
                            .physicalQuantity(cv.closingQuantity())
                            .reservedQuantity(cv.reservedQuantity())
                            .availableQuantity(cv.availableQuantity())
                            .outboundQuantity(cv.outboundQuantity())
                            .inventoryValue(cv.inventoryValue())
                            .build());
                    if (primaryWarehouse == null && cv.closingQuantity().signum() > 0) {
                        primaryWarehouse = w;
                    }
                }
            }

            if (primaryWarehouse == null && !query.scopeWarehouses().isEmpty()) {
                primaryWarehouse = query.scopeWarehouses().get(0);
            }

            BigDecimal avgQty;
            if (openingQty.signum() > 0 || closingQty.signum() > 0) {
                avgQty = openingQty.add(closingQty).divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
            } else if (inboundQty.signum() > 0 || outboundQty.signum() > 0) {
                avgQty = inboundQty.max(outboundQty).divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
            } else {
                avgQty = BigDecimal.ZERO;
            }

            BigDecimal unitCost = productUnitCosts.getOrDefault(p.getId(), BigDecimal.ZERO);
            BigDecimal sellingPrice = p.getPrice() != null ? p.getPrice().setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
            BigDecimal openingVal = openingQty.multiply(unitCost).setScale(2, RoundingMode.HALF_UP);
            BigDecimal inboundVal = inboundQty.multiply(unitCost).setScale(2, RoundingMode.HALF_UP);
            BigDecimal outboundVal = outboundQty.multiply(unitCost).setScale(2, RoundingMode.HALF_UP);
            BigDecimal closingVal = closingQty.multiply(unitCost).setScale(2, RoundingMode.HALF_UP);
            BigDecimal reservedVal = reservedQty.multiply(unitCost).setScale(2, RoundingMode.HALF_UP);
            BigDecimal availableVal = availableQty.multiply(unitCost).setScale(2, RoundingMode.HALF_UP);
            BigDecimal avgVal = avgQty.multiply(unitCost).setScale(2, RoundingMode.HALF_UP);

            BigDecimal periodDaysBd = BigDecimal.valueOf(query.periodDays());
            BigDecimal dailyOutboundRate = outboundQty.divide(periodDaysBd, 4, RoundingMode.HALF_UP);
            BigDecimal dailyOutboundVal = outboundVal.divide(periodDaysBd, 2, RoundingMode.HALF_UP);

            BigDecimal turnoverRate = BigDecimal.ZERO;
            BigDecimal annualizedTurnover = BigDecimal.ZERO;
            if (avgQty.signum() > 0 && outboundQty.signum() > 0) {
                turnoverRate = outboundQty.divide(avgQty, 4, RoundingMode.HALF_UP);
                annualizedTurnover = turnoverRate.multiply(BigDecimal.valueOf(365))
                        .divide(periodDaysBd, 2, RoundingMode.HALF_UP);
                turnoverRate = turnoverRate.setScale(2, RoundingMode.HALF_UP).signum() > 0
                        ? turnoverRate.setScale(2, RoundingMode.HALF_UP)
                        : turnoverRate;
            }

            Long daysSinceLastOutbound = lastOutboundAt != null
                    ? Math.max(0L, ChronoUnit.DAYS.between(lastOutboundAt.toLocalDate(), query.toDate()))
                    : null;

            BigDecimal averageDaysInInventory;
            BigDecimal estimatedDaysOfSupply;
            boolean slowMoving = false;
            String status;
            String statusLabel;
            String slowReason = null;

            if (closingQty.signum() == 0 && avgQty.signum() == 0) {
                averageDaysInInventory = BigDecimal.ZERO;
                estimatedDaysOfSupply = BigDecimal.ZERO;
                status = "OUT_OF_STOCK";
                statusLabel = "Hết hàng";
            } else if (outboundQty.signum() > 0) {
                averageDaysInInventory = avgQty.multiply(periodDaysBd)
                        .divide(outboundQty, 2, RoundingMode.HALF_UP);
                estimatedDaysOfSupply = dailyOutboundRate.signum() > 0
                        ? closingQty.divide(dailyOutboundRate, 2, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO;

                if (closingQty.signum() > 0 && averageDaysInInventory.compareTo(BigDecimal.valueOf(query.slowMovingThresholdDays())) >= 0) {
                    slowMoving = true;
                    status = "SLOW_MOVING";
                    statusLabel = "Chậm luân chuyển";
                    slowReason = String.format(Locale.ROOT,
                            "Số ngày tồn bình quân %.2f ngày vượt ngưỡng chậm luân chuyển (%d ngày); tồn %s %s đang chôn vốn %s VND",
                            averageDaysInInventory, query.slowMovingThresholdDays(),
                            closingQty.stripTrailingZeros().toPlainString(), p.getUnit(),
                            closingVal.toPlainString());
                } else if (closingQty.signum() == 0) {
                    status = "OUT_OF_STOCK";
                    statusLabel = "Hết hàng";
                } else if (averageDaysInInventory.compareTo(BigDecimal.valueOf(Math.max(1, query.slowMovingThresholdDays() / 2))) <= 0) {
                    status = "FAST_MOVING";
                    statusLabel = "Luân chuyển nhanh";
                } else {
                    status = "NORMAL_MOVING";
                    statusLabel = "Luân chuyển bình thường";
                }
            } else {
                // closingQty > 0 (or avgQty > 0) and outboundQty == 0 in period
                long holdingDays = Math.max(query.periodDays(), query.slowMovingThresholdDays());
                if (daysSinceLastOutbound != null && daysSinceLastOutbound > holdingDays) {
                    holdingDays = daysSinceLastOutbound;
                } else if (lastInboundAt != null) {
                    long daysSinceInbound = Math.max(1L, ChronoUnit.DAYS.between(lastInboundAt.toLocalDate(), query.toDate()) + 1L);
                    holdingDays = Math.max(holdingDays, daysSinceInbound);
                }
                averageDaysInInventory = BigDecimal.valueOf(holdingDays).setScale(2, RoundingMode.HALF_UP);
                estimatedDaysOfSupply = averageDaysInInventory;

                if (closingQty.signum() > 0) {
                    slowMoving = true;
                    status = "DEAD_STOCK";
                    statusLabel = "Tồn đọng / Không luân chuyển";
                    slowReason = String.format(Locale.ROOT,
                            "Không phát sinh xuất kho trong kỳ (%d ngày); tồn đọng %s %s đang chôn vốn %s VND",
                            query.periodDays(),
                            closingQty.stripTrailingZeros().toPlainString(), p.getUnit(),
                            closingVal.toPlainString());
                } else {
                    status = "OUT_OF_STOCK";
                    statusLabel = "Hết hàng";
                }
            }

            BigDecimal tiedUpValue = slowMoving ? closingVal : BigDecimal.ZERO;
            Category cat = p.getCategory();

            allSkuResponses.add(SkuInventoryTurnoverResponse.builder()
                    .productId(p.getId())
                    .productCode(p.getCode())
                    .productName(p.getName())
                    .baseUnit(p.getUnit())
                    .packingSpecification(p.getPackingSpecification())
                    .active(p.getActive())
                    .categoryId(cat != null ? cat.getId() : null)
                    .categoryCode(cat != null ? cat.getCode() : null)
                    .categoryName(cat != null ? cat.getName() : "Chưa phân nhóm")
                    .warehouseId(query.selectedWarehouse() != null ? query.selectedWarehouse().getId()
                            : (primaryWarehouse != null ? primaryWarehouse.getId() : null))
                    .warehouseCode(query.selectedWarehouse() != null ? query.selectedWarehouse().getCode()
                            : (query.scopeWarehouses().size() == 1 && primaryWarehouse != null ? primaryWarehouse.getCode() : "ALL"))
                    .warehouseName(query.selectedWarehouse() != null ? query.selectedWarehouse().getName()
                            : (query.scopeWarehouses().size() == 1 && primaryWarehouse != null ? primaryWarehouse.getName() : "Tất cả kho trong phạm vi"))
                    .warehouseCount((int) whBreakdowns.stream().filter(b -> b.getPhysicalQuantity().signum() > 0).count())
                    .unitCost(unitCost)
                    .sellingPrice(sellingPrice)
                    .openingQuantity(openingQty)
                    .inboundQuantity(inboundQty)
                    .outboundQuantity(outboundQty)
                    .closingQuantity(closingQty)
                    .reservedQuantity(reservedQty)
                    .availableQuantity(availableQty)
                    .inTransitQuantity(inTransitQty)
                    .averageInventoryQuantity(avgQty)
                    .openingValue(openingVal)
                    .inboundValue(inboundVal)
                    .outboundValue(outboundVal)
                    .inventoryValue(closingVal)
                    .reservedValue(reservedVal)
                    .availableValue(availableVal)
                    .averageInventoryValue(avgVal)
                    .dailyOutboundRate(dailyOutboundRate)
                    .dailyOutboundValue(dailyOutboundVal)
                    .turnoverRate(turnoverRate)
                    .annualizedTurnoverRate(annualizedTurnover)
                    .averageDaysInInventory(averageDaysInInventory)
                    .estimatedDaysOfSupply(estimatedDaysOfSupply)
                    .daysSinceLastOutbound(daysSinceLastOutbound)
                    .lastOutboundAt(lastOutboundAt)
                    .lastInboundAt(lastInboundAt)
                    .slowMoving(slowMoving)
                    .turnoverStatus(status)
                    .turnoverStatusLabel(statusLabel)
                    .tiedUpCapitalValue(tiedUpValue)
                    .slowMovingReason(slowReason)
                    .warehouseBreakdown(whBreakdowns)
                    .build());
        }

        // Map productId -> SkuInventoryTurnoverResponse for consistent slow-moving lookup
        Map<Long, SkuInventoryTurnoverResponse> skuMap = allSkuResponses.stream()
                .collect(Collectors.toMap(SkuInventoryTurnoverResponse::getProductId, s -> s));

        // Filter SKU items according to query flags (includeZeroStock, slowMovingOnly, turnoverStatus)
        List<SkuInventoryTurnoverResponse> filteredSkuItems = allSkuResponses.stream()
                .filter(sku -> {
                    if (!query.includeZeroStock()
                            && sku.getClosingQuantity().signum() == 0
                            && sku.getOutboundQuantity().signum() == 0
                            && sku.getInboundQuantity().signum() == 0) {
                        return false;
                    }
                    if (query.slowMovingOnly() && !sku.isSlowMoving()) {
                        return false;
                    }
                    if (query.turnoverStatus() != null && !query.turnoverStatus().equalsIgnoreCase(sku.getTurnoverStatus())) {
                        return false;
                    }
                    return true;
                })
                .collect(Collectors.toList());

        // Grand total inventory value across filtered SKUs
        BigDecimal grandTotalValue = filteredSkuItems.stream()
                .map(SkuInventoryTurnoverResponse::getInventoryValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        for (SkuInventoryTurnoverResponse sku : filteredSkuItems) {
            sku.setValueSharePercent(grandTotalValue.signum() > 0
                    ? sku.getInventoryValue().multiply(BigDecimal.valueOf(100))
                            .divide(grandTotalValue, 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO);
        }

        // Sort SKU items: slow-moving first or by inventoryValue DESC, averageDaysInInventory DESC, code ASC
        filteredSkuItems.sort(Comparator
                .comparing(SkuInventoryTurnoverResponse::isSlowMoving).reversed()
                .thenComparing(SkuInventoryTurnoverResponse::getInventoryValue, Comparator.reverseOrder())
                .thenComparing(SkuInventoryTurnoverResponse::getAverageDaysInInventory, Comparator.reverseOrder())
                .thenComparing(SkuInventoryTurnoverResponse::getProductCode, Comparator.nullsLast(String::compareToIgnoreCase)));

        Set<Long> includedProductIds = filteredSkuItems.stream()
                .map(SkuInventoryTurnoverResponse::getProductId)
                .collect(Collectors.toSet());

        // 6. Build Warehouse Valuations (Giá trị tồn theo kho)
        List<WarehouseInventoryValuationResponse> warehouseValuations = buildWarehouseValuations(
                query, candidateProducts, includedProductIds, cellValuations, skuMap, grandTotalValue);

        // 7. Build Category Valuations (Giá trị tồn theo nhóm hàng)
        List<CategoryInventoryValuationResponse> categoryValuations = buildCategoryValuations(
                query, filteredSkuItems, grandTotalValue);

        // 8. Build Warehouse × Category Valuations (Giá trị tồn theo Kho × Nhóm hàng)
        List<WarehouseCategoryValuationResponse> warehouseCategoryValuations = buildWarehouseCategoryValuations(
                query, candidateProducts, includedProductIds, cellValuations, skuMap, grandTotalValue);

        // 9. Build Slow-Moving Items list sorted by tiedUpCapitalValue DESC, averageDaysInInventory DESC
        List<SkuInventoryTurnoverResponse> slowMovingItems = filteredSkuItems.stream()
                .filter(SkuInventoryTurnoverResponse::isSlowMoving)
                .sorted(Comparator
                        .comparing(SkuInventoryTurnoverResponse::getTiedUpCapitalValue, Comparator.reverseOrder())
                        .thenComparing(SkuInventoryTurnoverResponse::getAverageDaysInInventory, Comparator.reverseOrder())
                        .thenComparing(SkuInventoryTurnoverResponse::getProductCode, Comparator.nullsLast(String::compareToIgnoreCase)))
                .toList();

        // 10. Overall Report KPIs
        BigDecimal totalOpeningQty = filteredSkuItems.stream().map(SkuInventoryTurnoverResponse::getOpeningQuantity).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalInboundQty = filteredSkuItems.stream().map(SkuInventoryTurnoverResponse::getInboundQuantity).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalOutboundQty = filteredSkuItems.stream().map(SkuInventoryTurnoverResponse::getOutboundQuantity).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalPhysicalQty = filteredSkuItems.stream().map(SkuInventoryTurnoverResponse::getClosingQuantity).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalReservedQty = filteredSkuItems.stream().map(SkuInventoryTurnoverResponse::getReservedQuantity).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalAvailableQty = filteredSkuItems.stream().map(SkuInventoryTurnoverResponse::getAvailableQuantity).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalInTransitQty = filteredSkuItems.stream().map(SkuInventoryTurnoverResponse::getInTransitQuantity).reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalReservedVal = filteredSkuItems.stream().map(SkuInventoryTurnoverResponse::getReservedValue).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalAvailableVal = filteredSkuItems.stream().map(SkuInventoryTurnoverResponse::getAvailableValue).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalAvgVal = filteredSkuItems.stream().map(SkuInventoryTurnoverResponse::getAverageInventoryValue).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalOutboundVal = filteredSkuItems.stream().map(SkuInventoryTurnoverResponse::getOutboundValue).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal slowMovingVal = slowMovingItems.stream().map(SkuInventoryTurnoverResponse::getTiedUpCapitalValue).reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal tiedUpRatio = grandTotalValue.signum() > 0
                ? slowMovingVal.multiply(BigDecimal.valueOf(100)).divide(grandTotalValue, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        BigDecimal overallTurnover = BigDecimal.ZERO;
        BigDecimal overallAnnualizedTurnover = BigDecimal.ZERO;
        BigDecimal overallDio = BigDecimal.ZERO;

        if (totalAvgVal.signum() > 0 && totalOutboundVal.signum() > 0) {
            overallTurnover = totalOutboundVal.divide(totalAvgVal, 4, RoundingMode.HALF_UP);
            overallAnnualizedTurnover = overallTurnover.multiply(BigDecimal.valueOf(365))
                    .divide(BigDecimal.valueOf(query.periodDays()), 2, RoundingMode.HALF_UP);
            overallDio = totalAvgVal.multiply(BigDecimal.valueOf(query.periodDays()))
                    .divide(totalOutboundVal, 2, RoundingMode.HALF_UP);
            overallTurnover = overallTurnover.setScale(2, RoundingMode.HALF_UP).signum() > 0
                    ? overallTurnover.setScale(2, RoundingMode.HALF_UP) : overallTurnover;
        } else if (totalPhysicalQty.signum() > 0 && totalOutboundQty.signum() == 0) {
            overallDio = BigDecimal.valueOf(Math.max(query.periodDays(), query.slowMovingThresholdDays())).setScale(2, RoundingMode.HALF_UP);
        }

        int inStockCount = (int) filteredSkuItems.stream().filter(s -> s.getClosingQuantity().signum() > 0).count();
        int outOfStockCount = (int) filteredSkuItems.stream().filter(s -> s.getClosingQuantity().signum() == 0).count();
        int fastCount = (int) filteredSkuItems.stream().filter(s -> "FAST_MOVING".equals(s.getTurnoverStatus())).count();
        int normalCount = (int) filteredSkuItems.stream().filter(s -> "NORMAL_MOVING".equals(s.getTurnoverStatus())).count();
        int deadCount = (int) filteredSkuItems.stream().filter(s -> "DEAD_STOCK".equals(s.getTurnoverStatus())).count();

        return InventoryTurnoverReportResponse.builder()
                .fromDate(query.fromDate())
                .toDate(query.toDate())
                .periodDays(query.periodDays())
                .slowMovingThresholdDays(query.slowMovingThresholdDays())
                .generatedAt(LocalDateTime.now())
                .warehouseId(query.selectedWarehouse() != null ? query.selectedWarehouse().getId() : null)
                .warehouseCode(query.selectedWarehouse() != null ? query.selectedWarehouse().getCode() : null)
                .warehouseName(query.selectedWarehouse() != null ? query.selectedWarehouse().getName() : null)
                .categoryId(query.selectedCategory() != null ? query.selectedCategory().getId() : null)
                .categoryCode(query.selectedCategory() != null ? query.selectedCategory().getCode() : null)
                .categoryName(query.selectedCategory() != null ? query.selectedCategory().getName() : null)
                .totalWarehouses(warehouseValuations.size())
                .totalCategories(categoryValuations.size())
                .totalSkus(filteredSkuItems.size())
                .inStockSkuCount(inStockCount)
                .outOfStockSkuCount(outOfStockCount)
                .fastMovingSkuCount(fastCount)
                .normalMovingSkuCount(normalCount)
                .slowMovingSkuCount(slowMovingItems.size())
                .deadStockSkuCount(deadCount)
                .totalOpeningQuantity(totalOpeningQty)
                .totalInboundQuantityInPeriod(totalInboundQty)
                .totalOutboundQuantityInPeriod(totalOutboundQty)
                .totalPhysicalQuantity(totalPhysicalQty)
                .totalReservedQuantity(totalReservedQty)
                .totalAvailableQuantity(totalAvailableQty)
                .totalInTransitQuantity(totalInTransitQty)
                .totalInventoryValue(grandTotalValue)
                .totalReservedValue(totalReservedVal)
                .totalAvailableValue(totalAvailableVal)
                .totalAverageInventoryValue(totalAvgVal)
                .totalOutboundValueInPeriod(totalOutboundVal)
                .slowMovingInventoryValue(slowMovingVal)
                .tiedUpCapitalRatioPercent(tiedUpRatio)
                .overallTurnoverRate(overallTurnover)
                .overallAnnualizedTurnoverRate(overallAnnualizedTurnover)
                .overallAverageDaysInInventory(overallDio)
                .warehouseValuations(warehouseValuations)
                .categoryValuations(categoryValuations)
                .warehouseCategoryValuations(warehouseCategoryValuations)
                .skuItems(filteredSkuItems)
                .slowMovingItems(slowMovingItems)
                .build();
    }

    private List<WarehouseInventoryValuationResponse> buildWarehouseValuations(
            ReportQuery query,
            List<Product> candidateProducts,
            Set<Long> includedProductIds,
            Map<CellKey, CellValuation> cellValuations,
            Map<Long, SkuInventoryTurnoverResponse> skuMap,
            BigDecimal grandTotalValue) {

        List<WarehouseInventoryValuationResponse> list = new ArrayList<>();
        for (Warehouse w : query.scopeWarehouses()) {
            int skuCount = 0;
            int inStockCount = 0;
            int slowCount = 0;
            int deadCount = 0;
            BigDecimal openingQty = BigDecimal.ZERO;
            BigDecimal inboundQty = BigDecimal.ZERO;
            BigDecimal outboundQty = BigDecimal.ZERO;
            BigDecimal physicalQty = BigDecimal.ZERO;
            BigDecimal reservedQty = BigDecimal.ZERO;
            BigDecimal availableQty = BigDecimal.ZERO;
            BigDecimal inTransitQty = BigDecimal.ZERO;
            BigDecimal totalVal = BigDecimal.ZERO;
            BigDecimal reservedVal = BigDecimal.ZERO;
            BigDecimal availableVal = BigDecimal.ZERO;
            BigDecimal avgVal = BigDecimal.ZERO;
            BigDecimal outboundVal = BigDecimal.ZERO;
            BigDecimal slowVal = BigDecimal.ZERO;

            for (Product p : candidateProducts) {
                if (!includedProductIds.contains(p.getId())) continue;
                CellValuation cv = cellValuations.get(new CellKey(w.getId(), p.getId()));
                if (cv == null) continue;

                boolean hasActivityOrStock = cv.closingQuantity().signum() > 0
                        || cv.openingQuantity().signum() > 0
                        || cv.inboundQuantity().signum() > 0
                        || cv.outboundQuantity().signum() > 0;
                if (hasActivityOrStock) {
                    skuCount++;
                }
                if (cv.closingQuantity().signum() > 0) {
                    inStockCount++;
                }

                openingQty = openingQty.add(cv.openingQuantity());
                inboundQty = inboundQty.add(cv.inboundQuantity());
                outboundQty = outboundQty.add(cv.outboundQuantity());
                physicalQty = physicalQty.add(cv.closingQuantity());
                reservedQty = reservedQty.add(cv.reservedQuantity());
                availableQty = availableQty.add(cv.availableQuantity());
                inTransitQty = inTransitQty.add(cv.inTransitQuantity());

                totalVal = totalVal.add(cv.inventoryValue());
                reservedVal = reservedVal.add(cv.reservedValue());
                availableVal = availableVal.add(cv.availableValue());
                avgVal = avgVal.add(cv.averageInventoryValue());
                outboundVal = outboundVal.add(cv.outboundValue());

                SkuInventoryTurnoverResponse overallSku = skuMap.get(p.getId());
                boolean isSlow = cv.closingQuantity().signum() > 0
                        && (cv.slowMovingAtWarehouse() || (overallSku != null && overallSku.isSlowMoving()));
                if (isSlow) {
                    slowCount++;
                    slowVal = slowVal.add(cv.inventoryValue());
                    if (cv.outboundQuantity().signum() == 0) {
                        deadCount++;
                    }
                }
            }

            BigDecimal sharePercent = grandTotalValue.signum() > 0
                    ? totalVal.multiply(BigDecimal.valueOf(100)).divide(grandTotalValue, 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            BigDecimal slowRatioPercent = totalVal.signum() > 0
                    ? slowVal.multiply(BigDecimal.valueOf(100)).divide(totalVal, 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;

            BigDecimal turnover = BigDecimal.ZERO;
            BigDecimal annualized = BigDecimal.ZERO;
            BigDecimal dio = BigDecimal.ZERO;
            if (avgVal.signum() > 0 && outboundVal.signum() > 0) {
                turnover = outboundVal.divide(avgVal, 4, RoundingMode.HALF_UP);
                annualized = turnover.multiply(BigDecimal.valueOf(365))
                        .divide(BigDecimal.valueOf(query.periodDays()), 2, RoundingMode.HALF_UP);
                dio = avgVal.multiply(BigDecimal.valueOf(query.periodDays()))
                        .divide(outboundVal, 2, RoundingMode.HALF_UP);
                turnover = turnover.setScale(2, RoundingMode.HALF_UP).signum() > 0
                        ? turnover.setScale(2, RoundingMode.HALF_UP) : turnover;
            } else if (physicalQty.signum() > 0 && outboundQty.signum() == 0) {
                dio = BigDecimal.valueOf(Math.max(query.periodDays(), query.slowMovingThresholdDays())).setScale(2, RoundingMode.HALF_UP);
            }

            list.add(WarehouseInventoryValuationResponse.builder()
                    .warehouseId(w.getId())
                    .warehouseCode(w.getCode())
                    .warehouseName(w.getName())
                    .defective(w.isDefectiveWarehouse())
                    .active(w.getActive())
                    .skuCount(skuCount)
                    .inStockSkuCount(inStockCount)
                    .slowMovingSkuCount(slowCount)
                    .deadStockSkuCount(deadCount)
                    .openingQuantity(openingQty)
                    .inboundQuantityInPeriod(inboundQty)
                    .outboundQuantityInPeriod(outboundQty)
                    .totalPhysicalQuantity(physicalQty)
                    .totalReservedQuantity(reservedQty)
                    .totalAvailableQuantity(availableQty)
                    .totalInTransitQuantity(inTransitQty)
                    .totalInventoryValue(totalVal)
                    .reservedInventoryValue(reservedVal)
                    .availableInventoryValue(availableVal)
                    .averageInventoryValue(avgVal)
                    .outboundValueInPeriod(outboundVal)
                    .slowMovingInventoryValue(slowVal)
                    .slowMovingValueRatioPercent(slowRatioPercent)
                    .valueSharePercent(sharePercent)
                    .turnoverRate(turnover)
                    .annualizedTurnoverRate(annualized)
                    .averageDaysInInventory(dio)
                    .build());
        }

        list.sort(Comparator.comparing(WarehouseInventoryValuationResponse::getTotalInventoryValue, Comparator.reverseOrder())
                .thenComparing(WarehouseInventoryValuationResponse::getWarehouseCode, Comparator.nullsLast(String::compareToIgnoreCase)));
        return list;
    }

    private List<CategoryInventoryValuationResponse> buildCategoryValuations(
            ReportQuery query,
            List<SkuInventoryTurnoverResponse> skuItems,
            BigDecimal grandTotalValue) {

        Map<Long, Category> categoryMap = categoryRepository.findAllByOrderByLevelAscNameAsc().stream()
                .collect(Collectors.toMap(Category::getId, c -> c, (a, b) -> a, LinkedHashMap::new));

        Map<Long, List<SkuInventoryTurnoverResponse>> grouped = new LinkedHashMap<>();
        for (SkuInventoryTurnoverResponse sku : skuItems) {
            Long catId = sku.getCategoryId() != null ? sku.getCategoryId() : -1L;
            grouped.computeIfAbsent(catId, k -> new ArrayList<>()).add(sku);
        }

        List<CategoryInventoryValuationResponse> result = new ArrayList<>();
        for (Map.Entry<Long, List<SkuInventoryTurnoverResponse>> entry : grouped.entrySet()) {
            Long catId = entry.getKey();
            List<SkuInventoryTurnoverResponse> items = entry.getValue();
            Category cat = categoryMap.get(catId);

            int skuCount = items.size();
            int inStockCount = (int) items.stream().filter(i -> i.getClosingQuantity().signum() > 0).count();
            int slowCount = (int) items.stream().filter(SkuInventoryTurnoverResponse::isSlowMoving).count();
            int deadCount = (int) items.stream().filter(i -> "DEAD_STOCK".equals(i.getTurnoverStatus())).count();

            BigDecimal openingQty = items.stream().map(SkuInventoryTurnoverResponse::getOpeningQuantity).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal inboundQty = items.stream().map(SkuInventoryTurnoverResponse::getInboundQuantity).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal outboundQty = items.stream().map(SkuInventoryTurnoverResponse::getOutboundQuantity).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal physicalQty = items.stream().map(SkuInventoryTurnoverResponse::getClosingQuantity).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal reservedQty = items.stream().map(SkuInventoryTurnoverResponse::getReservedQuantity).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal availableQty = items.stream().map(SkuInventoryTurnoverResponse::getAvailableQuantity).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal inTransitQty = items.stream().map(SkuInventoryTurnoverResponse::getInTransitQuantity).reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal totalVal = items.stream().map(SkuInventoryTurnoverResponse::getInventoryValue).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal reservedVal = items.stream().map(SkuInventoryTurnoverResponse::getReservedValue).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal availableVal = items.stream().map(SkuInventoryTurnoverResponse::getAvailableValue).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal avgVal = items.stream().map(SkuInventoryTurnoverResponse::getAverageInventoryValue).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal outboundVal = items.stream().map(SkuInventoryTurnoverResponse::getOutboundValue).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal slowVal = items.stream().map(SkuInventoryTurnoverResponse::getTiedUpCapitalValue).reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal sharePercent = grandTotalValue.signum() > 0
                    ? totalVal.multiply(BigDecimal.valueOf(100)).divide(grandTotalValue, 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            BigDecimal slowRatioPercent = totalVal.signum() > 0
                    ? slowVal.multiply(BigDecimal.valueOf(100)).divide(totalVal, 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;

            BigDecimal turnover = BigDecimal.ZERO;
            BigDecimal annualized = BigDecimal.ZERO;
            BigDecimal dio = BigDecimal.ZERO;
            if (avgVal.signum() > 0 && outboundVal.signum() > 0) {
                turnover = outboundVal.divide(avgVal, 4, RoundingMode.HALF_UP);
                annualized = turnover.multiply(BigDecimal.valueOf(365))
                        .divide(BigDecimal.valueOf(query.periodDays()), 2, RoundingMode.HALF_UP);
                dio = avgVal.multiply(BigDecimal.valueOf(query.periodDays()))
                        .divide(outboundVal, 2, RoundingMode.HALF_UP);
                turnover = turnover.setScale(2, RoundingMode.HALF_UP).signum() > 0
                        ? turnover.setScale(2, RoundingMode.HALF_UP) : turnover;
            } else if (physicalQty.signum() > 0 && outboundQty.signum() == 0) {
                dio = BigDecimal.valueOf(Math.max(query.periodDays(), query.slowMovingThresholdDays())).setScale(2, RoundingMode.HALF_UP);
            }

            SkuInventoryTurnoverResponse sample = items.get(0);
            result.add(CategoryInventoryValuationResponse.builder()
                    .categoryId(catId == -1L ? null : catId)
                    .categoryCode(cat != null ? cat.getCode() : sample.getCategoryCode())
                    .categoryName(cat != null ? cat.getName() : sample.getCategoryName())
                    .level(cat != null ? cat.getLevel() : 1)
                    .parentCategoryId(cat != null && cat.getParent() != null ? cat.getParent().getId() : null)
                    .parentCategoryName(cat != null && cat.getParent() != null ? cat.getParent().getName() : null)
                    .skuCount(skuCount)
                    .inStockSkuCount(inStockCount)
                    .slowMovingSkuCount(slowCount)
                    .deadStockSkuCount(deadCount)
                    .openingQuantity(openingQty)
                    .inboundQuantityInPeriod(inboundQty)
                    .outboundQuantityInPeriod(outboundQty)
                    .totalPhysicalQuantity(physicalQty)
                    .totalReservedQuantity(reservedQty)
                    .totalAvailableQuantity(availableQty)
                    .totalInTransitQuantity(inTransitQty)
                    .totalInventoryValue(totalVal)
                    .reservedInventoryValue(reservedVal)
                    .availableInventoryValue(availableVal)
                    .averageInventoryValue(avgVal)
                    .outboundValueInPeriod(outboundVal)
                    .slowMovingInventoryValue(slowVal)
                    .slowMovingValueRatioPercent(slowRatioPercent)
                    .valueSharePercent(sharePercent)
                    .turnoverRate(turnover)
                    .annualizedTurnoverRate(annualized)
                    .averageDaysInInventory(dio)
                    .build());
        }

        result.sort(Comparator.comparing(CategoryInventoryValuationResponse::getTotalInventoryValue, Comparator.reverseOrder())
                .thenComparing(CategoryInventoryValuationResponse::getCategoryCode, Comparator.nullsLast(String::compareToIgnoreCase)));
        return result;
    }

    private List<WarehouseCategoryValuationResponse> buildWarehouseCategoryValuations(
            ReportQuery query,
            List<Product> candidateProducts,
            Set<Long> includedProductIds,
            Map<CellKey, CellValuation> cellValuations,
            Map<Long, SkuInventoryTurnoverResponse> skuMap,
            BigDecimal grandTotalValue) {

        record WhCatKey(Long warehouseId, Long categoryId) {}
        Map<WhCatKey, List<CellValuation>> grouped = new LinkedHashMap<>();

        for (Warehouse w : query.scopeWarehouses()) {
            for (Product p : candidateProducts) {
                if (!includedProductIds.contains(p.getId())) continue;
                CellValuation cv = cellValuations.get(new CellKey(w.getId(), p.getId()));
                if (cv == null) continue;
                if (cv.closingQuantity().signum() == 0 && cv.outboundQuantity().signum() == 0 && cv.inboundQuantity().signum() == 0) {
                    continue;
                }
                Long catId = p.getCategory() != null ? p.getCategory().getId() : -1L;
                grouped.computeIfAbsent(new WhCatKey(w.getId(), catId), k -> new ArrayList<>()).add(cv);
            }
        }

        List<WarehouseCategoryValuationResponse> result = new ArrayList<>();
        for (List<CellValuation> cells : grouped.values()) {
            CellValuation first = cells.get(0);
            Warehouse w = first.warehouse();
            Category c = first.product().getCategory();

            int skuCount = cells.size();
            int inStockCount = (int) cells.stream().filter(cv -> cv.closingQuantity().signum() > 0).count();
            int slowCount = 0;
            BigDecimal physicalQty = BigDecimal.ZERO;
            BigDecimal reservedQty = BigDecimal.ZERO;
            BigDecimal availableQty = BigDecimal.ZERO;
            BigDecimal outboundQty = BigDecimal.ZERO;
            BigDecimal totalVal = BigDecimal.ZERO;
            BigDecimal availableVal = BigDecimal.ZERO;
            BigDecimal avgVal = BigDecimal.ZERO;
            BigDecimal outboundVal = BigDecimal.ZERO;
            BigDecimal slowVal = BigDecimal.ZERO;

            for (CellValuation cv : cells) {
                physicalQty = physicalQty.add(cv.closingQuantity());
                reservedQty = reservedQty.add(cv.reservedQuantity());
                availableQty = availableQty.add(cv.availableQuantity());
                outboundQty = outboundQty.add(cv.outboundQuantity());
                totalVal = totalVal.add(cv.inventoryValue());
                availableVal = availableVal.add(cv.availableValue());
                avgVal = avgVal.add(cv.averageInventoryValue());
                outboundVal = outboundVal.add(cv.outboundValue());

                SkuInventoryTurnoverResponse overallSku = skuMap.get(cv.product().getId());
                if (cv.closingQuantity().signum() > 0
                        && (cv.slowMovingAtWarehouse() || (overallSku != null && overallSku.isSlowMoving()))) {
                    slowCount++;
                    slowVal = slowVal.add(cv.inventoryValue());
                }
            }

            BigDecimal sharePercent = grandTotalValue.signum() > 0
                    ? totalVal.multiply(BigDecimal.valueOf(100)).divide(grandTotalValue, 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            BigDecimal turnover = avgVal.signum() > 0 && outboundVal.signum() > 0
                    ? outboundVal.divide(avgVal, 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            BigDecimal dio = avgVal.signum() > 0 && outboundVal.signum() > 0
                    ? avgVal.multiply(BigDecimal.valueOf(query.periodDays())).divide(outboundVal, 2, RoundingMode.HALF_UP)
                    : (physicalQty.signum() > 0 ? BigDecimal.valueOf(Math.max(query.periodDays(), query.slowMovingThresholdDays())).setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO);

            result.add(WarehouseCategoryValuationResponse.builder()
                    .warehouseId(w.getId())
                    .warehouseCode(w.getCode())
                    .warehouseName(w.getName())
                    .categoryId(c != null ? c.getId() : null)
                    .categoryCode(c != null ? c.getCode() : null)
                    .categoryName(c != null ? c.getName() : "Chưa phân nhóm")
                    .skuCount(skuCount)
                    .inStockSkuCount(inStockCount)
                    .slowMovingSkuCount(slowCount)
                    .totalPhysicalQuantity(physicalQty)
                    .totalReservedQuantity(reservedQty)
                    .totalAvailableQuantity(availableQty)
                    .outboundQuantityInPeriod(outboundQty)
                    .totalInventoryValue(totalVal)
                    .availableInventoryValue(availableVal)
                    .outboundValueInPeriod(outboundVal)
                    .slowMovingInventoryValue(slowVal)
                    .valueSharePercent(sharePercent)
                    .turnoverRate(turnover)
                    .averageDaysInInventory(dio)
                    .build());
        }

        result.sort(Comparator.comparing(WarehouseCategoryValuationResponse::getWarehouseCode, Comparator.nullsLast(String::compareToIgnoreCase))
                .thenComparing(WarehouseCategoryValuationResponse::getTotalInventoryValue, Comparator.reverseOrder()));
        return result;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // MOVEMENT COLLECTORS
    // ═════════════════════════════════════════════════════════════════════════

    private void collectVoucherMovements(
            ReportQuery query,
            Set<Long> candidateProductIds,
            Map<CellKey, MovementAccumulator> movements,
            Map<Long, BigDecimal[]> fallbackImportCosts,
            Set<Long> shipmentBackedOrderIds) {

        List<WarehouseVoucher> vouchers = voucherRepository.findAll();
        for (WarehouseVoucher v : vouchers) {
            if (v == null || v.getWarehouse() == null || v.getWarehouse().getId() == null) continue;
            String status = v.getStatus() != null ? v.getStatus().trim().toUpperCase(Locale.ROOT) : "RECORDED";
            if (!VALID_VOUCHER_STATUSES.contains(status)) continue;

            if (v.getShipmentOrderId() != null) {
                shipmentBackedOrderIds.add(v.getShipmentOrderId());
            }

            Long whId = v.getWarehouse().getId();
            boolean inScope = query.scopeWarehouseIds().contains(whId);
            LocalDateTime eventTime = v.getRecordedAt() != null ? v.getRecordedAt() : v.getVoucherDate();

            if (v.getItems() == null) continue;
            for (WarehouseVoucherItem item : v.getItems()) {
                if (item == null || item.getProduct() == null || item.getProduct().getId() == null) continue;
                Long prodId = item.getProduct().getId();
                if (!candidateProductIds.contains(prodId)) continue;

                BigDecimal baseQty = item.getBaseQuantity() != null ? item.getBaseQuantity() : BigDecimal.ZERO;
                if (baseQty.signum() == 0) continue;

                if (v.getType() == WarehouseVoucherType.IMPORT && baseQty.signum() > 0) {
                    BigDecimal lineTotal = item.getTotalAmount();
                    if ((lineTotal == null || lineTotal.signum() <= 0) && item.getPrice() != null && item.getQuantity() != null) {
                        lineTotal = item.getPrice().multiply(item.getQuantity());
                    }
                    if (lineTotal != null && lineTotal.signum() > 0) {
                        BigDecimal[] acc = fallbackImportCosts.computeIfAbsent(prodId,
                                k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
                        acc[0] = acc[0].add(lineTotal);
                        acc[1] = acc[1].add(baseQty);
                    }
                }

                if (!inScope) continue;

                CellKey key = new CellKey(whId, prodId);
                MovementAccumulator acc = movements.computeIfAbsent(key, k -> new MovementAccumulator());
                if (v.getType() == WarehouseVoucherType.IMPORT) {
                    acc.recordMovement(eventTime, baseQty.abs(), true, false, query.fromDateTime(), query.toDateTime());
                } else if (v.getType() == WarehouseVoucherType.EXPORT) {
                    acc.recordMovement(eventTime, baseQty.abs().negate(), false, true, query.fromDateTime(), query.toDateTime());
                } else if (v.getType() == WarehouseVoucherType.ADJUSTMENT) {
                    acc.recordMovement(eventTime, baseQty, false, false, query.fromDateTime(), query.toDateTime());
                }
            }
        }
    }

    private void collectOrderMovements(
            ReportQuery query,
            Set<Long> candidateProductIds,
            Map<CellKey, MovementAccumulator> movements,
            Set<Long> shipmentBackedOrderIds) {

        List<Order> orders = orderRepository.findAll();
        for (Order o : orders) {
            if (o == null || o.getWarehouse() == null || o.getWarehouse().getId() == null) continue;
            if (!query.scopeWarehouseIds().contains(o.getWarehouse().getId())) continue;
            if (!OrderStatus.isExportedOrLater(o.getStatus())) continue;
            if (o.getId() != null && shipmentBackedOrderIds.contains(o.getId())) continue;

            LocalDateTime eventTime = resolveOrderExportTime(o, query.fromDateTime());
            if (o.getItems() == null) continue;
            for (OrderItem item : o.getItems()) {
                if (item == null || item.getProduct() == null || item.getProduct().getId() == null) continue;
                Long prodId = item.getProduct().getId();
                if (!candidateProductIds.contains(prodId)) continue;

                BigDecimal exportedBaseQty = resolveOrderItemExportedBaseQty(item);
                if (exportedBaseQty.signum() <= 0) continue;

                CellKey key = new CellKey(o.getWarehouse().getId(), prodId);
                MovementAccumulator acc = movements.computeIfAbsent(key, k -> new MovementAccumulator());
                acc.recordMovement(eventTime, exportedBaseQty.negate(), false, true, query.fromDateTime(), query.toDateTime());
            }
        }
    }

    private LocalDateTime resolveOrderExportTime(Order order, LocalDateTime fallback) {
        if (orderStatusHistoryRepository != null && order.getId() != null) {
            List<OrderStatusHistory> histories = orderStatusHistoryRepository.findByOrderIdOrderByChangedAtAsc(order.getId());
            for (OrderStatusHistory h : histories) {
                if (h.getNewStatus() != null && Set.of("EXPORTED", "SHIPPED", "DELIVERED")
                        .contains(h.getNewStatus().toUpperCase(Locale.ROOT)) && h.getChangedAt() != null) {
                    return h.getChangedAt();
                }
            }
        }
        if (order.getActualDeliveryDate() != null) {
            return order.getActualDeliveryDate().atTime(12, 0);
        }
        if (order.getRecordedAt() != null) {
            return order.getRecordedAt();
        }
        if (order.getCreatedAt() != null) {
            return order.getCreatedAt();
        }
        return fallback;
    }

    private BigDecimal resolveOrderItemExportedBaseQty(OrderItem item) {
        if (item.getDeliveredBaseQuantity() != null && item.getDeliveredBaseQuantity().signum() > 0) {
            return item.getDeliveredBaseQuantity();
        }
        BigDecimal factor = item.getConversionFactor() != null && item.getConversionFactor().signum() > 0
                ? item.getConversionFactor() : BigDecimal.ONE;
        if (item.getDeliveredQuantity() != null && item.getDeliveredQuantity().signum() > 0) {
            return item.getDeliveredQuantity().multiply(factor);
        }
        if (item.getExportedQuantity() != null && item.getExportedQuantity().signum() > 0) {
            return item.getExportedQuantity().multiply(factor);
        }
        return item.getBaseQuantity() != null ? item.getBaseQuantity() : BigDecimal.ZERO;
    }

    private void collectTransferMovements(
            ReportQuery query,
            Set<Long> candidateProductIds,
            Map<CellKey, MovementAccumulator> movements) {

        if (transferRepository == null) return;
        List<WarehouseTransfer> transfers = transferRepository.findAll();
        for (WarehouseTransfer t : transfers) {
            if (t == null || t.getItems() == null) continue;
            Long fromWh = t.getFromWarehouse() != null ? t.getFromWarehouse().getId() : null;
            Long toWh = t.getToWarehouse() != null ? t.getToWarehouse().getId() : null;
            boolean fromInScope = fromWh != null && query.scopeWarehouseIds().contains(fromWh);
            boolean toInScope = toWh != null && query.scopeWarehouseIds().contains(toWh);
            if (!fromInScope && !toInScope) continue;

            for (WarehouseTransferItem item : t.getItems()) {
                if (item == null || item.getProduct() == null || item.getProduct().getId() == null) continue;
                Long prodId = item.getProduct().getId();
                if (!candidateProductIds.contains(prodId)) continue;

                BigDecimal dispatchedBase = item.getBaseQuantity() != null ? item.getBaseQuantity() : BigDecimal.ZERO;
                if (dispatchedBase.signum() <= 0) continue;

                if (fromInScope && t.getDispatchedAt() != null) {
                    CellKey fromKey = new CellKey(fromWh, prodId);
                    MovementAccumulator fromAcc = movements.computeIfAbsent(fromKey, k -> new MovementAccumulator());
                    // Count as outbound only if the destination warehouse is outside the report scope
                    boolean countAsOutbound = !toInScope && t.getStatus() != WarehouseTransferStatus.CANCELLED;
                    fromAcc.recordMovement(t.getDispatchedAt(), dispatchedBase.negate(), false, countAsOutbound,
                            query.fromDateTime(), query.toDateTime());

                    if (t.getStatus() == WarehouseTransferStatus.CANCELLED && t.getCancelledAt() != null) {
                        fromAcc.recordMovement(t.getCancelledAt(), dispatchedBase, false, false,
                                query.fromDateTime(), query.toDateTime());
                    }
                }

                if (toInScope && t.getStatus() == WarehouseTransferStatus.COMPLETED && t.getReceivedAt() != null) {
                    BigDecimal receivedBase = item.getReceivedBaseQuantity() != null
                            ? item.getReceivedBaseQuantity() : dispatchedBase;
                    CellKey toKey = new CellKey(toWh, prodId);
                    MovementAccumulator toAcc = movements.computeIfAbsent(toKey, k -> new MovementAccumulator());
                    boolean countAsInbound = !fromInScope;
                    toAcc.recordMovement(t.getReceivedAt(), receivedBase, countAsInbound, false,
                            query.fromDateTime(), query.toDateTime());
                }
            }
        }
    }

    private void collectStockCountMovements(
            ReportQuery query,
            Set<Long> candidateProductIds,
            Map<CellKey, MovementAccumulator> movements) {

        if (stockCountRepository == null) return;
        List<WarehouseStockCount> counts = stockCountRepository.findAll();
        for (WarehouseStockCount sc : counts) {
            if (sc == null || sc.getStatus() != StockCountStatus.COMPLETED || sc.getWarehouse() == null) continue;
            Long whId = sc.getWarehouse().getId();
            if (whId == null || !query.scopeWarehouseIds().contains(whId)) continue;
            if (sc.getItems() == null) continue;

            for (WarehouseStockCountItem item : sc.getItems()) {
                if (item == null || item.getProduct() == null || item.getProduct().getId() == null) continue;
                Long prodId = item.getProduct().getId();
                if (!candidateProductIds.contains(prodId)) continue;
                BigDecimal diff = item.getDifferenceQuantity();
                if (diff == null || diff.signum() == 0) continue;

                CellKey key = new CellKey(whId, prodId);
                movements.computeIfAbsent(key, k -> new MovementAccumulator())
                        .recordMovement(sc.getCompletedAt(), diff, false, false, query.fromDateTime(), query.toDateTime());
            }
        }
    }

    private void collectDealerReturnMovements(
            ReportQuery query,
            Set<Long> candidateProductIds,
            Map<CellKey, MovementAccumulator> movements) {

        if (dealerReturnRepository == null) return;
        List<DealerReturn> returns = dealerReturnRepository.findAll();
        for (DealerReturn dr : returns) {
            if (dr == null || dr.getStatus() != DealerReturnStatus.CONFIRMED || dr.getItems() == null) continue;
            for (DealerReturnItem item : dr.getItems()) {
                if (item == null || item.getProduct() == null || item.getTargetWarehouse() == null) continue;
                Long whId = item.getTargetWarehouse().getId();
                Long prodId = item.getProduct().getId();
                if (whId == null || prodId == null || !query.scopeWarehouseIds().contains(whId)
                        || !candidateProductIds.contains(prodId)) continue;

                BigDecimal baseReturn = item.getBaseReturnQuantity();
                if (baseReturn == null || baseReturn.signum() <= 0) continue;

                CellKey key = new CellKey(whId, prodId);
                movements.computeIfAbsent(key, k -> new MovementAccumulator())
                        .recordMovement(dr.getConfirmedAt(), baseReturn, true, false, query.fromDateTime(), query.toDateTime());
            }
        }
    }

    private void collectDeliveryReturnMovements(
            ReportQuery query,
            Set<Long> candidateProductIds,
            Map<CellKey, MovementAccumulator> movements) {

        if (deliveryTripStopItemRepository == null) return;
        for (Long prodId : candidateProductIds) {
            List<DeliveryTripStopItem> items = deliveryTripStopItemRepository.findForStockCard(prodId, query.scopeWarehouseIds());
            for (DeliveryTripStopItem item : items) {
                if (item == null || item.getStop() == null || item.getStop().getTrip() == null
                        || item.getStop().getTrip().getWarehouse() == null) continue;
                Long whId = item.getStop().getTrip().getWarehouse().getId();
                BigDecimal qty = item.getQuantity();
                if (whId == null || qty == null || qty.signum() <= 0) continue;

                CellKey key = new CellKey(whId, prodId);
                movements.computeIfAbsent(key, k -> new MovementAccumulator())
                        .recordMovement(item.getStop().getReturnedAt(), qty, true, false, query.fromDateTime(), query.toDateTime());
            }
        }
    }

    private InventoryTurnoverReportResponse buildEmptyReport(ReportQuery query) {
        return InventoryTurnoverReportResponse.builder()
                .fromDate(query.fromDate())
                .toDate(query.toDate())
                .periodDays(query.periodDays())
                .slowMovingThresholdDays(query.slowMovingThresholdDays())
                .generatedAt(LocalDateTime.now())
                .warehouseId(query.selectedWarehouse() != null ? query.selectedWarehouse().getId() : null)
                .warehouseCode(query.selectedWarehouse() != null ? query.selectedWarehouse().getCode() : null)
                .warehouseName(query.selectedWarehouse() != null ? query.selectedWarehouse().getName() : null)
                .categoryId(query.selectedCategory() != null ? query.selectedCategory().getId() : null)
                .categoryCode(query.selectedCategory() != null ? query.selectedCategory().getCode() : null)
                .categoryName(query.selectedCategory() != null ? query.selectedCategory().getName() : null)
                .build();
    }
}

