package com.quanlybanhang.config;

import com.quanlybanhang.dto.request.OrderItemRequest;
import com.quanlybanhang.dto.request.OrderRequest;
import com.quanlybanhang.dto.request.WarehouseVoucherItemRequest;
import com.quanlybanhang.dto.request.WarehouseVoucherRequest;
import com.quanlybanhang.dto.response.OrderResponse;
import com.quanlybanhang.dto.response.WarehouseVoucherResponse;
import com.quanlybanhang.model.*;
import com.quanlybanhang.repository.*;
import com.quanlybanhang.service.AuditLogService;
import com.quanlybanhang.service.OrderService;
import com.quanlybanhang.service.UnitConversionService;
import com.quanlybanhang.service.WarehouseService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Bộ dữ liệu demo có quan hệ đầy đủ, chỉ thêm bản ghi chưa có.
 * Toàn bộ một lần seed nằm trong cùng transaction, bao gồm sổ kho và nhật ký.
 */
@Component
@RequiredArgsConstructor
public class DemoDataSeeder {

    private static final Logger logger = LoggerFactory.getLogger(DemoDataSeeder.class);
    private static final String ACTOR = "admin";
    private static final String NOTE = "Dữ liệu demo để kiểm thử SaleStock";
    private static final LocalDate START = LocalDate.of(2020, 1, 1);
    private static final LocalDate END = LocalDate.of(2099, 12, 31);

    private final WarehouseRepository warehouses;
    private final TerritoryRepository territories;
    private final UserRepository users;
    private final RoleRepository roles;
    private final PasswordEncoder passwordEncoder;
    private final CategoryRepository categories;
    private final ProductRepository products;
    private final UnitConversionRepository conversions;
    private final SupplierRepository suppliers;
    private final DealerRepository dealers;
    private final DealerDeliveryPointRepository deliveryPoints;
    private final DealerAssignmentHistoryRepository assignmentHistories;
    private final PriceListRepository priceLists;
    private final VolumeDiscountPolicyRepository policies;
    private final ProductPriceHistoryRepository priceHistory;
    private final OrderRepository orders;
    private final WarehouseVoucherRepository vouchers;
    private final OrderService orderService;
    private final WarehouseService warehouseService;
    private final UnitConversionService unitConversionService;
    private final AuditLogService auditLogService;

    private record Scope(Warehouse hcm, Warehouse hanoi, Territory east, Territory west, Territory north) {}
    private record DemoUser(String username, String name, ERole role) {}
    private record Catalog(Map<String, Product> products, Category beer, Category softDrinks) {
        Product get(String code) { return products.get(code); }
    }

    @Transactional
    public void seed(boolean seedUsers, boolean seedData, String password) {
        if (!seedUsers && !seedData) return;
        if (seedUsers && !StringUtils.hasText(password)) {
            throw new IllegalStateException("APP_DEMO_USER_PASSWORD là bắt buộc khi bật APP_SEED_DEMO_USERS");
        }

        Scope scope = createScope();
        if (seedUsers) createDemoUsers(scope, password);
        if (seedData) {
            Catalog catalog = createCatalog();
            Map<String, Supplier> sources = createSuppliers();
            Map<String, Dealer> customers = createDealers(scope);
            Map<CustomerGroup, PriceList> prices = createPriceLists(catalog);
            createDiscounts(catalog);
            createStock(scope, catalog, sources);
            createOrders(scope, catalog, customers, prices);
        }
        logger.info("Đã kiểm tra/khởi tạo dữ liệu demo (tài khoản: {}, nghiệp vụ: {}). Bản ghi đã có được giữ nguyên.",
                seedUsers, seedData);
    }

    private Scope createScope() {
        return new Scope(
                warehouse("DEMO-KHO-HCM", "Kho TP.HCM (Demo)"),
                warehouse("DEMO-KHO-HN", "Kho Hà Nội (Demo)"),
                territory("DEMO-HCM-DONG", "TP.HCM - Khu Đông (Demo)"),
                territory("DEMO-HCM-TAY", "TP.HCM - Khu Tây (Demo)"),
                territory("DEMO-HN", "Hà Nội (Demo)"));
    }

    private Warehouse warehouse(String code, String name) {
        return warehouses.findByCode(code).orElseGet(() ->
                warehouses.save(Warehouse.builder().code(code).name(name).build()));
    }

    private Territory territory(String code, String name) {
        return territories.findByCode(code).orElseGet(() ->
                territories.save(Territory.builder().code(code).name(name).build()));
    }

    private void createDemoUsers(Scope scope, String password) {
        List<DemoUser> accounts = List.of(
                new DemoUser("sales.manager", "Quản lý kinh doanh Demo", ERole.ROLE_SALES_MANAGER),
                new DemoUser("sales.demo", "Nhân viên kinh doanh Demo", ERole.ROLE_SALES),
                new DemoUser("warehouse.manager", "Quản lý kho Demo", ERole.ROLE_WAREHOUSE_MANAGER),
                new DemoUser("warehouse.demo", "Thủ kho Demo", ERole.ROLE_WAREHOUSE_STAFF),
                new DemoUser("accountant.demo", "Kế toán Demo", ERole.ROLE_ACCOUNTANT),
                new DemoUser("viewer.demo", "Người xem Demo", ERole.ROLE_VIEWER),
                new DemoUser("dealer.demo", "Đại lý Minh Phát Demo", ERole.ROLE_DEALER));
        for (DemoUser account : accounts) {
            if (users.existsByUsername(account.username())) continue;
            Role role = roles.findByName(account.role())
                    .orElseThrow(() -> new IllegalStateException("Không tìm thấy role " + account.role()));
            Set<Warehouse> assignedWarehouses = switch (account.role()) {
                case ROLE_WAREHOUSE_MANAGER, ROLE_WAREHOUSE_STAFF -> Set.of(scope.hcm(), scope.hanoi());
                default -> Set.of();
            };
            Set<Territory> assignedTerritories = account.role() == ERole.ROLE_SALES
                    ? Set.of(scope.east(), scope.west(), scope.north()) : Set.of();
            users.save(User.builder()
                    .username(account.username()).email(account.username() + "@demo.invalid")
                    .password(passwordEncoder.encode(password)).fullName(account.name())
                    .roles(new HashSet<>(Set.of(role))).warehouses(new HashSet<>(assignedWarehouses))
                    .territories(new HashSet<>(assignedTerritories))
                    .active(true).build());
            audit("USER", account.username(), account.role().name());
        }
    }

    private Catalog createCatalog() {
        Category electronics = category("DEMO-NG-DIENTU", "Điện tử (Demo)", null);
        Category devices = category("DEMO-PN-THIETBI", "Thiết bị di động (Demo)", electronics);
        Category phones = category("DEMO-NC-DIENTHOAI", "Điện thoại thông minh (Demo)", devices);
        Category accessories = category("DEMO-NC-PHUKIEN", "Phụ kiện điện thoại (Demo)", devices);
        Category home = category("DEMO-NG-GIADUNG", "Gia dụng (Demo)", null);
        Category kitchen = category("DEMO-PN-NHABEP", "Thiết bị nhà bếp (Demo)", home);
        Category appliances = category("DEMO-NC-NOICHIEN", "Nồi chiên không dầu (Demo)", kitchen);
        Category beverages = category("DEMO-NG-DOUONG", "Đồ uống (Demo)", null);
        Category beerGroup = category("DEMO-PN-BIA", "Bia (Demo)", beverages);
        Category beer = category("DEMO-NC-BIALON", "Bia lon (Demo)", beerGroup);
        Category drinksGroup = category("DEMO-PN-GIAIKHAT", "Nước giải khát (Demo)", beverages);
        Category drinks = category("DEMO-NC-NUOCUONG", "Nước uống đóng lon/chai (Demo)", drinksGroup);

        Map<String, Product> catalog = new LinkedHashMap<>();
        addProduct(catalog, "KEN330", "Bia Heineken Sleek 330ml (Demo)", beer, "lon", "19000", "15000", "Thùng 24 lon, lốc 6 lon");
        addProduct(catalog, "TIGER330", "Bia Tiger 330ml (Demo)", beer, "lon", "17000", "13000", "Thùng 24 lon, lốc 6 lon");
        addProduct(catalog, "COCA330", "Coca-Cola 330ml (Demo)", drinks, "lon", "10000", "6500", "Thùng 24 lon, lốc 6 lon");
        addProduct(catalog, "LAVIE500", "Nước La Vie 500ml (Demo)", drinks, "chai", "6000", "4000", "Thùng 24 chai, lốc 6 chai");
        addProduct(catalog, "IP15PM", "iPhone 15 Pro Max 256GB (Demo)", phones, "chiếc", "29990000", "24000000", "Hộp 1 máy kèm cáp USB-C");
        addProduct(catalog, "S24U", "Samsung Galaxy S24 Ultra 512GB (Demo)", phones, "chiếc", "26990000", "21500000", "Hộp 1 máy kèm bút S-Pen");
        addProduct(catalog, "ANKER65W", "Củ sạc Anker GaN 65W (Demo)", accessories, "cái", "650000", "400000", "Hộp 1 củ sạc 3 cổng");
        addProduct(catalog, "PHILIPS", "Nồi chiên Philips XXL HD9650 (Demo)", appliances, "chiếc", "4500000", "3200000", "Thùng 1 nồi kèm vỉ nướng");
        for (String code : List.of("KEN330", "TIGER330", "COCA330", "LAVIE500")) {
            Product product = catalog.get(code);
            conversion(product, "lốc", "6");
            conversion(product, "thùng", "24");
        }
        return new Catalog(catalog, beer, drinks);
    }

    private Category category(String code, String name, Category parent) {
        return categories.findByCode(code).orElseGet(() -> {
            Category saved = categories.save(Category.builder().code(code).name(name).description(NOTE)
                    .parent(parent).level(parent == null ? 1 : parent.getLevel() + 1).build());
            audit("CATEGORY", code, name);
            return saved;
        });
    }

    private void addProduct(Map<String, Product> catalog, String suffix, String name, Category category,
                            String unit, String price, String cost, String packing) {
        String code = "DEMO-SP-" + suffix;
        Product product = products.findByCode(code).orElseGet(() -> {
            Product saved = products.save(Product.builder().code(code).name(name).category(category)
                    .description(NOTE).unit(unit).price(decimal(price)).costPrice(decimal(cost))
                    .packingSpecification(packing).build());
            priceHistory.save(ProductPriceHistory.builder().product(saved).oldPrice(BigDecimal.ZERO)
                    .newPrice(saved.getPrice()).modifiedBy(ACTOR).build());
            audit("PRICE", code, "Giá bán khởi tạo: " + price);
            return saved;
        });
        catalog.put(suffix, product);
    }

    private void conversion(Product product, String unit, String factor) {
        if (conversions.existsByProductIdAndUnitNameIgnoreCase(product.getId(), unit)) return;
        BigDecimal multiplier = decimal(factor);
        conversions.save(UnitConversion.builder().product(product).unitName(unit).conversionFactor(multiplier)
                .price(product.getPrice().multiply(multiplier)).costPrice(product.getCostPrice().multiply(multiplier))
                .description("1 " + unit + " = " + factor + " " + product.getUnit() + " (Demo)").build());
    }

    private Map<String, Supplier> createSuppliers() {
        return Map.of(
                "drinks", supplier("DEMO-NCC-DOUONG", "Nhà cung cấp Đồ uống (Demo)", "30 ngày", "TP.HCM"),
                "electronics", supplier("DEMO-NCC-DIENTU", "Nhà cung cấp Điện tử (Demo)", "15 ngày", "TP.HCM"),
                "home", supplier("DEMO-NCC-GIADUNG", "Nhà cung cấp Gia dụng (Demo)", "Thanh toán khi nhận hàng", "Hà Nội"));
    }

    private Supplier supplier(String code, String name, String terms, String city) {
        return suppliers.findByCode(code).orElseGet(() -> {
            Supplier saved = suppliers.save(Supplier.builder().code(code).name(name).paymentTerms(terms)
                    .contactPerson("Liên hệ Demo").email(code.toLowerCase(java.util.Locale.ROOT) + "@demo.invalid")
                    .address("Địa chỉ minh họa tại " + city).notes(NOTE).build());
            audit("SUPPLIER", code, name);
            return saved;
        });
    }

    private Map<String, Dealer> createDealers(Scope scope) {
        Map<String, Dealer> customers = new LinkedHashMap<>();
        customers.put("cap1", dealer("DEMO-DL-MINHPHAT", "Đại lý Minh Phát (Demo)", CustomerGroup.DAI_LY_CAP_1,
                scope.east(), "100000000", 30, "123 Lê Lợi, TP.HCM (Demo)", false, scope.hcm()));
        customers.put("cap2", dealer("DEMO-DL-TANBINH", "Đại lý Tân Bình (Demo)", CustomerGroup.DAI_LY_CAP_2,
                scope.west(), "50000000", 15, "456 Hoàng Văn Thụ, TP.HCM (Demo)", false, scope.hcm()));
        customers.put("hanoi", dealer("DEMO-DL-HANOI", "Đại lý Hà Nội (Demo)", CustomerGroup.DAI_LY_CAP_1,
                scope.north(), "80000000", 30, "25 Trần Duy Hưng, Hà Nội (Demo)", false, scope.hanoi()));
        customers.put("retail", dealer("DEMO-DL-KHACHLE", "Khách lẻ (Demo)", CustomerGroup.KHACH_LE,
                scope.east(), "0", 0, "10 Nguyễn Huệ, TP.HCM (Demo)", false, scope.hcm()));
        customers.put("locked", dealer("DEMO-DL-TAMKHOA", "Đại lý tạm khóa (Demo)", CustomerGroup.DAI_LY_CAP_2,
                scope.west(), "20000000", 7, "20 Cộng Hòa, TP.HCM (Demo)", true, scope.hcm()));
        for (Dealer dealer : customers.values()) {
            deliveryPoint(dealer, dealer.getName(), dealer.getAddress(), "0900000001");
        }
        deliveryPoint(customers.get("cap1"), "Kho phụ Minh Phát (Demo)", "50 Võ Văn Kiệt, TP.HCM (Demo)", "0900000002");
        users.findByUsername("dealer.demo").ifPresent(dealerUser -> {
            dealerUser.setDealer(customers.get("cap1"));
            users.save(dealerUser);
        });
        return customers;
    }

    private Dealer dealer(String code, String name, CustomerGroup group, Territory territory,
                          String limit, int days, String address, boolean locked, Warehouse warehouse) {
        return dealers.findByCode(code).orElseGet(() -> {
            User sales = users.findByUsername("sales.demo")
                    .filter(user -> Boolean.TRUE.equals(user.getActive()) && user.getRoles().stream()
                            .anyMatch(role -> role.getName() == ERole.ROLE_SALES)).orElse(null);
            Dealer saved = dealers.save(Dealer.builder().code(code).name(name).customerGroup(group)
                    .territory(territory).warehouse(warehouse).assignedUser(sales).debtLimit(decimal(limit)).maxDebtDays(days)
                    .address(address).phone("0900000001").email(code.toLowerCase(java.util.Locale.ROOT) + "@demo.invalid")
                    .locked(locked).lockReason(locked ? "Tình huống demo: tạm dừng bán để kiểm tra công nợ" : null)
                    .lockedBy(locked ? ACTOR : null).lockedAt(locked ? LocalDateTime.now() : null).build());
            if (sales != null) {
                User admin = users.findByUsername(ACTOR).orElseThrow();
                assignmentHistories.save(DealerAssignmentHistory.builder().dealer(saved).dealerCode(code).dealerName(name)
                        .toUser(sales).toUserName(sales.getFullName()).territory(territory).territoryName(territory.getName())
                        .transferType("INITIAL_ASSIGNMENT").reason(NOTE).performedBy(admin).performedByName(admin.getFullName()).build());
            }
            audit("DEALER", code, name);
            if (locked) auditLogService.logAction("DEALER", code, "LOCK", "false", "true", saved.getLockReason(), ACTOR);
            return saved;
        });
    }

    private void deliveryPoint(Dealer dealer, String receiver, String address, String phone) {
        List<DealerDeliveryPoint> existing = deliveryPoints.findByDealerId(dealer.getId());
        if (existing.stream().anyMatch(point -> address.equals(point.getAddress()))) return;
        deliveryPoints.save(DealerDeliveryPoint.builder().dealer(dealer).receiverName(receiver).address(address)
                .phone(phone).directionNote(NOTE).isDefault(existing.isEmpty()).build());
    }

    private Map<CustomerGroup, PriceList> createPriceLists(Catalog catalog) {
        Map<CustomerGroup, PriceList> result = new LinkedHashMap<>();
        for (CustomerGroup group : CustomerGroup.values()) {
            String code = "DEMO-BG-" + group.name();
            PriceList priceList = priceLists.findByCode(code).orElseGet(() -> {
                BigDecimal ratio = switch (group) {
                    case DAI_LY_CAP_1 -> decimal("0.92");
                    case DAI_LY_CAP_2 -> decimal("0.96");
                    case KHACH_LE -> BigDecimal.ONE;
                };
                PriceList created = PriceList.builder().code(code).name("Bảng giá " + group.name() + " (Demo)")
                        .customerGroup(group).startDate(START).endDate(END).description(NOTE).createdBy(ACTOR).build();
                for (Product product : catalog.products().values()) {
                    created.addItem(PriceListItem.builder().product(product)
                            .sellingPrice(product.getPrice().multiply(ratio).setScale(2, RoundingMode.HALF_UP))
                            .floorPrice(product.getPrice().multiply(decimal("0.88")).setScale(2, RoundingMode.HALF_UP))
                            .note(NOTE).build());
                }
                PriceList saved = priceLists.save(created);
                audit("PRICE_LIST", code, created.getName());
                return saved;
            });
            result.put(group, priceList);
        }
        return result;
    }

    private void createDiscounts(Catalog catalog) {
        if (!policies.existsByCode("DEMO-CK-KEN")) {
            VolumeDiscountPolicy policy = policy("DEMO-CK-KEN", "Chiết khấu Heineken (Demo)", DiscountTargetType.PRODUCT)
                    .product(catalog.get("KEN330")).build();
            policy.addTier(tier("48", "119.9999", DiscountType.PERCENTAGE, "3", 1));
            policy.addTier(tier("120", "239.9999", DiscountType.PERCENTAGE, "5", 2));
            policy.addTier(tier("240", null, DiscountType.PERCENTAGE, "7", 3));
            policies.save(policy);
            audit("DISCOUNT_POLICY", policy.getCode(), policy.getName());
        }
        categoryDiscount("DEMO-CK-BIA", "Chiết khấu nhóm bia (Demo)", catalog.beer(), "120", "300");
        categoryDiscount("DEMO-CK-NUOC", "Chiết khấu nước giải khát (Demo)", catalog.softDrinks(), "240", "500");
    }

    private void categoryDiscount(String code, String name, Category category, String minimum, String amount) {
        if (policies.existsByCode(code)) return;
        VolumeDiscountPolicy policy = policy(code, name, DiscountTargetType.CATEGORY).category(category).build();
        policy.addTier(tier(minimum, null, DiscountType.FIXED_AMOUNT, amount, 1));
        policies.save(policy);
        audit("DISCOUNT_POLICY", code, name);
    }

    private VolumeDiscountPolicy.VolumeDiscountPolicyBuilder policy(String code, String name, DiscountTargetType type) {
        return VolumeDiscountPolicy.builder().code(code).name(name).targetType(type).startDate(START).endDate(END)
                .description(NOTE).createdBy(ACTOR);
    }

    private VolumeDiscountTier tier(String min, String max, DiscountType type, String value, int order) {
        return VolumeDiscountTier.builder().minQuantity(decimal(min)).maxQuantity(max == null ? null : decimal(max))
                .discountType(type).discountValue(decimal(value)).tierOrder(order).build();
    }

    private void createStock(Scope scope, Catalog catalog, Map<String, Supplier> sources) {
        importStock("DEMO-PNK-HCM-DOUONG", scope.hcm(), sources.get("drinks"), List.of(
                stockItem(catalog.get("KEN330"), "thùng", "40"), stockItem(catalog.get("TIGER330"), "thùng", "30"),
                stockItem(catalog.get("COCA330"), "thùng", "50"), stockItem(catalog.get("LAVIE500"), "thùng", "50")));
        importStock("DEMO-PNK-HCM-DIENTU", scope.hcm(), sources.get("electronics"), List.of(
                stockItem(catalog.get("IP15PM"), "chiếc", "20"), stockItem(catalog.get("S24U"), "chiếc", "20"),
                stockItem(catalog.get("ANKER65W"), "cái", "60")));
        importStock("DEMO-PNK-HCM-GIADUNG", scope.hcm(), sources.get("home"), List.of(
                stockItem(catalog.get("PHILIPS"), "chiếc", "30")));
        importStock("DEMO-PNK-HN-DOUONG", scope.hanoi(), sources.get("drinks"), List.of(
                stockItem(catalog.get("KEN330"), "thùng", "20"), stockItem(catalog.get("TIGER330"), "thùng", "20"),
                stockItem(catalog.get("COCA330"), "thùng", "30"), stockItem(catalog.get("LAVIE500"), "thùng", "30")));
    }

    private WarehouseVoucherItemRequest stockItem(Product product, String unit, String quantity) {
        BigDecimal factor = unitConversionService.getConversionFactor(product, unit);
        return WarehouseVoucherItemRequest.builder().productId(product.getId()).unit(unit).quantity(decimal(quantity))
                .price(product.getCostPrice().multiply(factor)).note(NOTE).build();
    }

    private void importStock(String code, Warehouse warehouse, Supplier supplier, List<WarehouseVoucherItemRequest> items) {
        if (vouchers.existsByVoucherCode(code)) return;
        // The opt-in startup job uses an explicit bootstrap context, never an API permission fallback.
        var previous = org.springframework.security.core.context.SecurityContextHolder.getContext();
        var bootstrap = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
        bootstrap.setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                ACTOR, "", List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))));
        try {
            org.springframework.security.core.context.SecurityContextHolder.setContext(bootstrap);
            warehouseService.importGoods(WarehouseVoucherRequest.builder().voucherCode(code).warehouseId(warehouse.getId())
                    .supplierId(supplier.getId()).documentNumber(code).voucherDate(LocalDateTime.now())
                    .note(NOTE).items(items).build(), ACTOR);
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.setContext(previous);
        }
    }

    private void createOrders(Scope scope, Catalog catalog, Map<String, Dealer> customers, Map<CustomerGroup, PriceList> prices) {
        seedOrder("DEMO-DH-DOUONG", customers.get("cap1"), prices, List.of(
                orderItem(catalog.get("KEN330"), "thùng", "5", null), orderItem(catalog.get("TIGER330"), "thùng", "2", null),
                orderItem(catalog.get("COCA330"), "thùng", "4", null)), "RECORDED", scope.hcm());
        seedOrder("DEMO-DH-DIENTU", customers.get("cap2"), prices, List.of(
                orderItem(catalog.get("IP15PM"), "chiếc", "2", null), orderItem(catalog.get("S24U"), "chiếc", "1", null),
                orderItem(catalog.get("ANKER65W"), "cái", "3", null)), "RECORDED", scope.hcm());
        seedOrder("DEMO-DH-KHACHLE", customers.get("retail"), prices, List.of(
                orderItem(catalog.get("PHILIPS"), "chiếc", "1", null), orderItem(catalog.get("LAVIE500"), "lốc", "2", null)),
                "RECORDED", scope.hcm());
        seedOrder("DEMO-DH-HANOI", customers.get("hanoi"), prices, List.of(
                orderItem(catalog.get("TIGER330"), "thùng", "3", null), orderItem(catalog.get("COCA330"), "thùng", "5", null),
                orderItem(catalog.get("LAVIE500"), "thùng", "5", null)), "RECORDED", scope.hanoi());
        seedOrder("DEMO-DH-CHODUYET", customers.get("cap2"), prices, List.of(
                orderItem(catalog.get("IP15PM"), "chiếc", "1", "10000000")), "PENDING_APPROVAL", scope.hcm());
        seedOrder("DEMO-DH-DAHUY", customers.get("cap1"), prices, List.of(
                orderItem(catalog.get("COCA330"), "thùng", "1", null)), "CANCELLED", scope.hcm());
        seedOrder("DEMO-DH-TUCHOI", customers.get("cap2"), prices, List.of(
                orderItem(catalog.get("S24U"), "chiếc", "1", "10000000")), "REJECTED", scope.hcm());
    }

    private OrderItemRequest orderItem(Product product, String unit, String quantity, String price) {
        return OrderItemRequest.builder().productId(product.getId()).unit(unit).quantity(decimal(quantity))
                .price(price == null ? null : decimal(price)).note(NOTE).build();
    }

    private void seedOrder(String code, Dealer dealer, Map<CustomerGroup, PriceList> prices,
                           List<OrderItemRequest> items, String outcome, Warehouse warehouse) {
        // Trusted startup-only seed, never an HTTP authorization fallback.
        // Give bootstrap its explicit ADMIN context and always restore the caller.
        var previous = org.springframework.security.core.context.SecurityContextHolder.getContext();
        var bootstrap = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
        bootstrap.setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                ACTOR, "", List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))));
        try {
            org.springframework.security.core.context.SecurityContextHolder.setContext(bootstrap);
            seedOrderAsBootstrap(code, dealer, prices, items, outcome, warehouse);
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.setContext(previous);
        }
    }

    private void seedOrderAsBootstrap(String code, Dealer dealer, Map<CustomerGroup, PriceList> prices,
                                     List<OrderItemRequest> items, String outcome, Warehouse warehouse) {
        // Chỉ chuyển trạng thái/ghi phiếu xuất trong lần tạo đầu tiên.
        if (orders.existsByOrderCode(code)) return;
        if (!Boolean.TRUE.equals(dealer.getActive()) || Boolean.TRUE.equals(dealer.getLocked())) return;
        OrderResponse response = orderService.createOrder(OrderRequest.builder().orderCode(code).dealerId(dealer.getId())
                .warehouseId(warehouse.getId())
                .priceListId(prices.get(dealer.getCustomerGroup()).getId()).expectedDeliveryDate(LocalDate.now().plusDays(3))
                .note(NOTE + ": " + outcome).items(items).build(), ACTOR);
        if ("RECORDED".equals(outcome) && "PENDING_APPROVAL".equals(response.getStatus())) {
            response = orderService.approveOrder(response.getId(), ACTOR);
        }
        if ("CANCELLED".equals(outcome)) {
            orderService.cancelOrder(response.getId(), ACTOR);
        } else if ("REJECTED".equals(outcome) && "PENDING_APPROVAL".equals(response.getStatus())) {
            orderService.rejectOrder(response.getId(), "Tình huống demo: giá bán dưới giá sàn", ACTOR);
        } else if ("RECORDED".equals(outcome) && "RECORDED".equals(response.getStatus())) {
            String voucherCode = code.replace("DEMO-DH-", "DEMO-PXK-");
            if (!vouchers.existsByVoucherCode(voucherCode)) {
                List<WarehouseVoucherItemRequest> stockItems = response.getItems().stream().map(item ->
                        stockItem(products.findById(item.getProductId()).orElseThrow(), item.getUnit(), item.getQuantity().toPlainString())).toList();
                warehouseService.exportGoods(WarehouseVoucherRequest.builder().voucherCode(voucherCode)
                        .warehouseId(warehouse.getId()).note(NOTE + ": xuất hàng cho " + code).items(stockItems).build(), ACTOR);
            }
        }
    }

    private void audit(String type, String code, String value) {
        auditLogService.logAction(type, code, "CREATE", null, value, NOTE, ACTOR);
    }

    private static BigDecimal decimal(String value) { return new BigDecimal(value); }
}
