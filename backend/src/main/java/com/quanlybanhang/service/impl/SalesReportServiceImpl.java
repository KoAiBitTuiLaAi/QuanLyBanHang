package com.quanlybanhang.service.impl;

import com.quanlybanhang.dto.request.SalesReportFilterRequest;
import com.quanlybanhang.dto.response.*;
import com.quanlybanhang.model.*;
import com.quanlybanhang.repository.*;
import com.quanlybanhang.service.SalesReportExcelExportService;
import com.quanlybanhang.service.SalesReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Triển khai nghiệp vụ Báo cáo doanh số đa chiều (S8-06):
 * - Nhóm theo 5 chiều: nhân viên, khu vực, đại lý, nhóm hàng hoặc SKU
 * - Hiển thị doanh số, sản lượng và biên lợi nhuận gộp
 * - Bảo mật biên lợi nhuận: chỉ người dùng có quyền FINANCE_VIEW_PROFIT_MARGIN hoặc ROLE_ADMIN được xem
 * - So sánh với cùng kỳ trước (compare=true)
 * - Xuất file Excel (.xlsx) chuẩn nghiệp vụ
 * - Tuân thủ nghiêm ngặt ma trận phân quyền database (không dùng role-based fallback)
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SalesReportServiceImpl implements SalesReportService {

    private final OrderItemRepository orderItemRepository;
    private final DealerRepository dealerRepository;
    private final TerritoryRepository territoryRepository;
    private final CategoryRepository categoryRepository;
    private final UserRepository userRepository;
    private final SalesReportExcelExportService excelExportService;

    @Override
    public SalesReportOptionsResponse getOptions() {
        validateViewPermission();

        // 1. Khu vực hoạt động
        List<NamedResourceResponse> regions = territoryRepository.findAllByActiveTrueOrderByNameAsc().stream()
                .map(t -> new NamedResourceResponse(t.getId(), t.getCode(), t.getName()))
                .toList();

        // 2. Đại lý đang giao dịch
        List<NamedResourceResponse> dealers = dealerRepository.findByActiveTrueOrderByNameAsc().stream()
                .map(d -> new NamedResourceResponse(d.getId(), d.getCode(), d.getName()))
                .toList();

        // 3. Nhóm hàng
        List<NamedResourceResponse> categories = categoryRepository.findAllByOrderByLevelAscNameAsc().stream()
                .map(c -> new NamedResourceResponse(c.getId(), c.getCode(), c.getName()))
                .toList();

        // 4. Nhân viên kinh doanh
        List<NamedResourceResponse> employees = userRepository.findAll().stream()
                .filter(u -> Boolean.TRUE.equals(u.getActive()) && u.getRoles() != null && u.getRoles().stream()
                        .anyMatch(r -> r.getName() == ERole.ROLE_SALES || r.getName() == ERole.ROLE_SALES_MANAGER))
                .sorted(Comparator.comparing(u -> u.getFullName() != null && !u.getFullName().isBlank() ? u.getFullName() : u.getUsername(), String.CASE_INSENSITIVE_ORDER))
                .map(u -> new NamedResourceResponse(
                        u.getId(),
                        u.getUsername(),
                        (u.getFullName() != null && !u.getFullName().isBlank()) ? u.getFullName() : u.getUsername()))
                .toList();

        return SalesReportOptionsResponse.builder()
                .regions(regions)
                .dealers(dealers)
                .categories(categories)
                .employees(employees)
                .build();
    }

    @Override
    public SalesReportPageResponse getReport(SalesReportFilterRequest filter) {
        validateViewPermission();

        SalesReportFilterRequest safeFilter = sanitizeFilter(filter);
        boolean canSeeMargin = canCurrentUserSeeMargin();

        // 1. Phân phạm vi dữ liệu nếu nhân viên sales đơn thuần (Data Scoping)
        applyDataScopingIfNeeded(safeFilter);

        // 2. Khoảng thời gian kỳ hiện tại
        LocalDateTime start = safeFilter.getFromDate().atStartOfDay();
        LocalDateTime end = safeFilter.getToDate().atTime(LocalTime.MAX);

        // 3. Lấy dữ liệu dòng đơn hàng hợp lệ
        List<OrderItem> currentItems = orderItemRepository.findValidOrderItemsInPeriod(
                OrderStatus.debtBearingStatuses(), start, end);

        // Chuẩn bị tập category con nếu có lọc theo nhóm hàng
        Set<Long> categoryIds = resolveCategoryHierarchy(safeFilter.getCategoryId());

        // Bản đồ tra cứu người dùng để ánh xạ chính xác người tạo đơn
        Map<String, User> userByUsernameMap = buildUserLookupMap();

        // Lọc các item kỳ hiện tại
        List<OrderItem> filteredCurrentItems = filterItems(currentItems, safeFilter, categoryIds, userByUsernameMap);

        // 4. Xử lý so sánh cùng kỳ nếu bật compare=true
        Map<String, BigDecimal> previousRevenueMap = new HashMap<>();
        if (Boolean.TRUE.equals(safeFilter.getCompare())) {
            PeriodRange prevPeriod = calculatePreviousPeriod(safeFilter.getFromDate(), safeFilter.getToDate());
            LocalDateTime prevStart = prevPeriod.from().atStartOfDay();
            LocalDateTime prevEnd = prevPeriod.to().atTime(LocalTime.MAX);

            List<OrderItem> prevItems = orderItemRepository.findValidOrderItemsInPeriod(
                    OrderStatus.debtBearingStatuses(), prevStart, prevEnd);
            List<OrderItem> filteredPrevItems = filterItems(prevItems, safeFilter, categoryIds, userByUsernameMap);

            previousRevenueMap = aggregateRevenueByKey(filteredPrevItems, safeFilter.getGroupBy(), userByUsernameMap);
        }

        // 5. Tổng hợp theo chiều nhóm được chọn
        String groupBy = safeFilter.getGroupBy().toUpperCase(Locale.ROOT);
        Map<String, GroupAggregate> aggregateMap = new LinkedHashMap<>();

        for (OrderItem item : filteredCurrentItems) {
            GroupKey key = extractGroupKey(item, groupBy, userByUsernameMap);
            GroupAggregate agg = aggregateMap.computeIfAbsent(key.mapKey(), k -> new GroupAggregate(key));

            BigDecimal revenue = item.getTotalAmount() != null ? item.getTotalAmount()
                    : (item.getSubtotalAmount() != null ? item.getSubtotalAmount() : BigDecimal.ZERO);
            BigDecimal quantity = item.getBaseQuantity() != null ? item.getBaseQuantity()
                    : (item.getQuantity() != null ? item.getQuantity() : BigDecimal.ZERO);

            BigDecimal unitCost = (item.getProduct() != null && item.getProduct().getCostPrice() != null)
                    ? item.getProduct().getCostPrice() : BigDecimal.ZERO;
            BigDecimal cost = quantity.multiply(unitCost);

            agg.revenue = agg.revenue.add(revenue);
            agg.quantity = agg.quantity.add(quantity);
            agg.cost = agg.cost.add(cost);
        }

        // Nếu so sánh cùng kỳ, bổ sung cả các mục chỉ có doanh số ở kỳ trước (doanh số kỳ này = 0)
        if (Boolean.TRUE.equals(safeFilter.getCompare())) {
            for (Map.Entry<String, BigDecimal> entry : previousRevenueMap.entrySet()) {
                if (!aggregateMap.containsKey(entry.getKey())) {
                    // Tạo entry rỗng cho mục này
                    GroupKey key = parseGroupKeyFromMapKey(entry.getKey(), groupBy);
                    if (key != null) {
                        aggregateMap.put(entry.getKey(), new GroupAggregate(key));
                    }
                }
            }
        }

        // 6. Xây dựng danh sách dòng kết quả
        BigDecimal grandTotalRevenue = BigDecimal.ZERO;
        BigDecimal grandTotalQuantity = BigDecimal.ZERO;
        BigDecimal grandTotalCost = BigDecimal.ZERO;

        List<SalesReportRowResponse> rows = new ArrayList<>();
        for (Map.Entry<String, GroupAggregate> entry : aggregateMap.entrySet()) {
            GroupAggregate agg = entry.getValue();

            grandTotalRevenue = grandTotalRevenue.add(agg.revenue);
            grandTotalQuantity = grandTotalQuantity.add(agg.quantity);
            grandTotalCost = grandTotalCost.add(agg.cost);

            BigDecimal grossMargin = null;
            if (canSeeMargin) {
                if (agg.revenue.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal profit = agg.revenue.subtract(agg.cost);
                    grossMargin = profit.multiply(new BigDecimal("100"))
                            .divide(agg.revenue, 2, RoundingMode.HALF_UP);
                } else {
                    grossMargin = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
                }
            }

            BigDecimal previousRevenue = null;
            BigDecimal changePercent = null;
            if (Boolean.TRUE.equals(safeFilter.getCompare())) {
                previousRevenue = previousRevenueMap.getOrDefault(entry.getKey(), BigDecimal.ZERO);
                if (previousRevenue.compareTo(BigDecimal.ZERO) > 0) {
                    changePercent = agg.revenue.subtract(previousRevenue)
                            .multiply(new BigDecimal("100"))
                            .divide(previousRevenue, 2, RoundingMode.HALF_UP);
                } else if (agg.revenue.compareTo(BigDecimal.ZERO) > 0) {
                    changePercent = new BigDecimal("100.00");
                } else {
                    changePercent = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
                }
            }

            rows.add(SalesReportRowResponse.builder()
                    .id(agg.key.id())
                    .code(agg.key.code())
                    .name(agg.key.name())
                    .revenue(agg.revenue.setScale(0, RoundingMode.HALF_UP))
                    .quantity(agg.quantity.setScale(2, RoundingMode.HALF_UP))
                    .grossMargin(grossMargin)
                    .previousRevenue(previousRevenue != null ? previousRevenue.setScale(0, RoundingMode.HALF_UP) : null)
                    .changePercent(changePercent)
                    .build());
        }

        // Sắp xếp giảm dần theo doanh số, sau đó theo tên
        rows.sort((r1, r2) -> {
            int cmp = r2.getRevenue().compareTo(r1.getRevenue());
            if (cmp != 0) return cmp;
            return String.CASE_INSENSITIVE_ORDER.compare(r1.getName() != null ? r1.getName() : "", r2.getName() != null ? r2.getName() : "");
        });

        // 7. Tính tổng hợp KPI đầu bảng
        BigDecimal averageGrossMargin = null;
        if (canSeeMargin) {
            if (grandTotalRevenue.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal totalProfit = grandTotalRevenue.subtract(grandTotalCost);
                averageGrossMargin = totalProfit.multiply(new BigDecimal("100"))
                        .divide(grandTotalRevenue, 2, RoundingMode.HALF_UP);
            } else {
                averageGrossMargin = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            }
        }

        SalesReportSummaryResponse summary = SalesReportSummaryResponse.builder()
                .totalRevenue(grandTotalRevenue.setScale(0, RoundingMode.HALF_UP))
                .totalQuantity(grandTotalQuantity.setScale(2, RoundingMode.HALF_UP))
                .averageGrossMargin(averageGrossMargin)
                .build();

        // 8. Phân trang kết quả
        long totalElements = rows.size();
        int page = safeFilter.getPage();
        int size = safeFilter.getSize();
        int totalPages = size > 0 ? (int) Math.ceil((double) totalElements / size) : 1;
        if (totalPages == 0) totalPages = 1;

        int fromIndex = Math.min(page * size, (int) totalElements);
        int toIndex = Math.min(fromIndex + size, (int) totalElements);
        List<SalesReportRowResponse> pagedContent = rows.subList(fromIndex, toIndex);

        boolean isLast = (page + 1) >= totalPages;

        return SalesReportPageResponse.builder()
                .content(pagedContent)
                .page(page)
                .size(size)
                .totalElements(totalElements)
                .totalPages(totalPages)
                .last(isLast)
                .summary(summary)
                .build();
    }

    @Override
    public byte[] exportExcel(SalesReportFilterRequest filter) {
        validateViewPermission();

        SalesReportFilterRequest safeFilter = sanitizeFilter(filter);
        boolean canSeeMargin = canCurrentUserSeeMargin();

        // Xuất toàn bộ dữ liệu (không cắt theo kích thước trang hiển thị)
        safeFilter.setPage(0);
        safeFilter.setSize(Integer.MAX_VALUE / 2);

        SalesReportPageResponse report = getReport(safeFilter);
        return excelExportService.exportToExcel(safeFilter, report, canSeeMargin);
    }

    @Override
    public String buildExportFilename(SalesReportFilterRequest filter) {
        String from = filter != null && filter.getFromDate() != null ? filter.getFromDate().toString() : "dau-ky";
        String to = filter != null && filter.getToDate() != null ? filter.getToDate().toString() : "cuoi-ky";
        return "Bao-cao-doanh-so_" + from + "_" + to + ".xlsx";
    }

    // ── Helper methods ──

    private void validateViewPermission() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new AccessDeniedException("Chưa xác thực danh tính người dùng.");
        }
        boolean hasAccess = auth.getAuthorities().stream().anyMatch(a ->
                "ROLE_ADMIN".equals(a.getAuthority()) ||
                "REPORT_SALES".equals(a.getAuthority()) ||
                "ORDER_VIEW".equals(a.getAuthority())
        );
        if (!hasAccess) {
            throw new AccessDeniedException("Bạn không có quyền xem báo cáo doanh số.");
        }
    }

    private boolean canCurrentUserSeeMargin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return false;
        return auth.getAuthorities().stream().anyMatch(a ->
                "ROLE_ADMIN".equals(a.getAuthority()) ||
                "FINANCE_VIEW_PROFIT_MARGIN".equals(a.getAuthority())
        );
    }

    private void applyDataScopingIfNeeded(SalesReportFilterRequest filter) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return;

        Set<String> authorities = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());

        boolean isSelfScopedSales = !authorities.contains("ROLE_ADMIN")
                && !authorities.contains("ROLE_SALES_MANAGER")
                && !authorities.contains("ROLE_ACCOUNTANT")
                && !authorities.contains("ROLE_VIEWER")
                && !authorities.contains("ORDER_APPROVE")
                && authorities.contains("ROLE_SALES");

        if (isSelfScopedSales) {
            userRepository.findByUsername(auth.getName()).ifPresent(user -> {
                filter.setEmployeeId(user.getId());
            });
        }
    }

    private SalesReportFilterRequest sanitizeFilter(SalesReportFilterRequest filter) {
        SalesReportFilterRequest safe = filter != null ? filter : new SalesReportFilterRequest();

        LocalDate now = LocalDate.now();
        if (safe.getFromDate() == null) {
            safe.setFromDate(now.withDayOfMonth(1));
        }
        if (safe.getToDate() == null) {
            safe.setToDate(now);
        }
        if (safe.getFromDate().isAfter(safe.getToDate())) {
            LocalDate tmp = safe.getFromDate();
            safe.setFromDate(safe.getToDate());
            safe.setToDate(tmp);
        }

        if (safe.getGroupBy() == null || safe.getGroupBy().isBlank()) {
            safe.setGroupBy("SKU");
        }
        safe.setGroupBy(safe.getGroupBy().trim().toUpperCase(Locale.ROOT));

        if (safe.getPage() == null || safe.getPage() < 0) {
            safe.setPage(0);
        }
        if (safe.getSize() == null || safe.getSize() <= 0) {
            safe.setSize(20);
        }
        return safe;
    }

    private Set<Long> resolveCategoryHierarchy(Long categoryId) {
        if (categoryId == null) return null;
        Set<Long> result = new HashSet<>();
        result.add(categoryId);

        List<Category> allCategories = categoryRepository.findAll();
        boolean added;
        do {
            added = false;
            for (Category c : allCategories) {
                if (c.getParent() != null && result.contains(c.getParent().getId())) {
                    if (result.add(c.getId())) {
                        added = true;
                    }
                }
            }
        } while (added);

        return result;
    }

    private Map<String, User> buildUserLookupMap() {
        Map<String, User> map = new HashMap<>();
        userRepository.findAll().forEach(u -> {
            if (u.getUsername() != null) {
                map.put(u.getUsername().trim().toLowerCase(Locale.ROOT), u);
            }
        });
        return map;
    }

    private List<OrderItem> filterItems(
            List<OrderItem> items,
            SalesReportFilterRequest filter,
            Set<Long> categoryIds,
            Map<String, User> userByUsernameMap) {

        return items.stream().filter(item -> {
            Order order = item.getOrder();
            if (order == null) return false;
            Dealer dealer = order.getDealer();

            // Lọc theo khu vực
            if (filter.getRegionId() != null) {
                Territory territory = dealer != null ? dealer.getTerritory() : null;
                if (territory == null || !filter.getRegionId().equals(territory.getId())) {
                    return false;
                }
            }

            // Lọc theo đại lý
            if (filter.getDealerId() != null) {
                if (dealer == null || !filter.getDealerId().equals(dealer.getId())) {
                    return false;
                }
            }

            // Lọc theo nhóm hàng (bao gồm cả nhóm con)
            if (categoryIds != null && !categoryIds.isEmpty()) {
                Product product = item.getProduct();
                Category category = product != null ? product.getCategory() : null;
                if (category == null || !categoryIds.contains(category.getId())) {
                    return false;
                }
            }

            // Lọc theo nhân viên
            if (filter.getEmployeeId() != null) {
                User assignedUser = dealer != null ? dealer.getAssignedUser() : null;
                if (assignedUser != null && assignedUser.getId() != null) {
                    if (!filter.getEmployeeId().equals(assignedUser.getId())) {
                        return false;
                    }
                } else if (order.getCreatedBy() != null && !order.getCreatedBy().isBlank()) {
                    User creator = userByUsernameMap.get(order.getCreatedBy().trim().toLowerCase(Locale.ROOT));
                    if (creator == null || !filter.getEmployeeId().equals(creator.getId())) {
                        return false;
                    }
                } else {
                    return false;
                }
            }

            return true;
        }).toList();
    }

    private Map<String, BigDecimal> aggregateRevenueByKey(
            List<OrderItem> items,
            String groupBy,
            Map<String, User> userByUsernameMap) {

        Map<String, BigDecimal> revMap = new HashMap<>();
        for (OrderItem item : items) {
            GroupKey key = extractGroupKey(item, groupBy, userByUsernameMap);
            BigDecimal revenue = item.getTotalAmount() != null ? item.getTotalAmount()
                    : (item.getSubtotalAmount() != null ? item.getSubtotalAmount() : BigDecimal.ZERO);
            revMap.merge(key.mapKey(), revenue, BigDecimal::add);
        }
        return revMap;
    }

    private GroupKey extractGroupKey(OrderItem item, String groupBy, Map<String, User> userByUsernameMap) {
        Order order = item.getOrder();
        Dealer dealer = order != null ? order.getDealer() : null;
        Product product = item.getProduct();

        return switch (groupBy) {
            case "EMPLOYEE" -> {
                User assignedUser = dealer != null ? dealer.getAssignedUser() : null;
                if (assignedUser != null) {
                    String name = (assignedUser.getFullName() != null && !assignedUser.getFullName().isBlank())
                            ? assignedUser.getFullName() : assignedUser.getUsername();
                    yield new GroupKey("EMP_" + assignedUser.getId(), assignedUser.getId(), assignedUser.getUsername(), name);
                } else if (order != null && order.getCreatedBy() != null && !order.getCreatedBy().isBlank()) {
                    String username = order.getCreatedBy().trim();
                    User creator = userByUsernameMap.get(username.toLowerCase(Locale.ROOT));
                    if (creator != null) {
                        String name = (creator.getFullName() != null && !creator.getFullName().isBlank())
                                ? creator.getFullName() : creator.getUsername();
                        yield new GroupKey("EMP_" + creator.getId(), creator.getId(), creator.getUsername(), name);
                    } else {
                        yield new GroupKey("CREATOR_" + username, null, username, username);
                    }
                } else {
                    yield new GroupKey("UNASSIGNED_EMP", null, null, "Chưa phân công");
                }
            }
            case "REGION" -> {
                Territory territory = dealer != null ? dealer.getTerritory() : null;
                if (territory != null) {
                    yield new GroupKey("REG_" + territory.getId(), territory.getId(), territory.getCode(), territory.getName());
                } else {
                    yield new GroupKey("UNASSIGNED_REG", null, null, "Chưa phân khu vực");
                }
            }
            case "DEALER" -> {
                if (dealer != null) {
                    yield new GroupKey("DLR_" + dealer.getId(), dealer.getId(), dealer.getCode(), dealer.getName());
                } else {
                    yield new GroupKey("UNASSIGNED_DLR", null, null, "Khách lẻ / Trực tiếp");
                }
            }
            case "CATEGORY" -> {
                Category cat = product != null ? product.getCategory() : null;
                if (cat != null) {
                    yield new GroupKey("CAT_" + cat.getId(), cat.getId(), cat.getCode(), cat.getName());
                } else {
                    yield new GroupKey("UNASSIGNED_CAT", null, null, "Chưa phân nhóm");
                }
            }
            default -> { // SKU
                if (product != null) {
                    yield new GroupKey("SKU_" + product.getId(), product.getId(), product.getCode(), product.getName());
                } else {
                    yield new GroupKey("UNASSIGNED_SKU", null, null, "Mặt hàng không xác định");
                }
            }
        };
    }

    private GroupKey parseGroupKeyFromMapKey(String mapKey, String groupBy) {
        if (mapKey.startsWith("UNASSIGNED_")) {
            return switch (groupBy) {
                case "EMPLOYEE" -> new GroupKey(mapKey, null, null, "Chưa phân công");
                case "REGION" -> new GroupKey(mapKey, null, null, "Chưa phân khu vực");
                case "DEALER" -> new GroupKey(mapKey, null, null, "Khách lẻ / Trực tiếp");
                case "CATEGORY" -> new GroupKey(mapKey, null, null, "Chưa phân nhóm");
                default -> new GroupKey(mapKey, null, null, "Mặt hàng không xác định");
            };
        }
        try {
            if (mapKey.startsWith("EMP_")) {
                Long id = Long.parseLong(mapKey.substring(4));
                return userRepository.findById(id).map(u -> new GroupKey(
                        mapKey,
                        u.getId(),
                        u.getUsername(),
                        u.getFullName() != null && !u.getFullName().isBlank() ? u.getFullName() : u.getUsername())
                ).orElse(null);
            } else if (mapKey.startsWith("REG_")) {
                Long id = Long.parseLong(mapKey.substring(4));
                return territoryRepository.findById(id).map(t -> new GroupKey(
                        mapKey, t.getId(), t.getCode(), t.getName())).orElse(null);
            } else if (mapKey.startsWith("DLR_")) {
                Long id = Long.parseLong(mapKey.substring(4));
                return dealerRepository.findById(id).map(d -> new GroupKey(
                        mapKey, d.getId(), d.getCode(), d.getName())).orElse(null);
            } else if (mapKey.startsWith("CAT_")) {
                Long id = Long.parseLong(mapKey.substring(4));
                return categoryRepository.findById(id).map(c -> new GroupKey(
                        mapKey, c.getId(), c.getCode(), c.getName())).orElse(null);
            } else if (mapKey.startsWith("SKU_")) {
                Long id = Long.parseLong(mapKey.substring(4));
                return orderItemRepository.findById(id).map(i -> new GroupKey(
                        mapKey, i.getProduct().getId(), i.getProduct().getCode(), i.getProduct().getName())).orElse(null);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private PeriodRange calculatePreviousPeriod(LocalDate fromDate, LocalDate toDate) {
        // Nếu chọn nguyên tháng
        YearMonth ym = YearMonth.from(fromDate);
        if (fromDate.getDayOfMonth() == 1 && toDate.equals(ym.atEndOfMonth())) {
            YearMonth prevMonth = ym.minusMonths(1);
            return new PeriodRange(prevMonth.atDay(1), prevMonth.atEndOfMonth());
        }

        // Nếu chọn khoảng ngày bất kỳ
        long days = ChronoUnit.DAYS.between(fromDate, toDate) + 1;
        LocalDate prevTo = fromDate.minusDays(1);
        LocalDate prevFrom = prevTo.minusDays(days - 1);
        return new PeriodRange(prevFrom, prevTo);
    }

    private record PeriodRange(LocalDate from, LocalDate to) {}

    private record GroupKey(String mapKey, Long id, String code, String name) {}

    private static class GroupAggregate {
        final GroupKey key;
        BigDecimal revenue = BigDecimal.ZERO;
        BigDecimal quantity = BigDecimal.ZERO;
        BigDecimal cost = BigDecimal.ZERO;

        GroupAggregate(GroupKey key) {
            this.key = key;
        }
    }
}
