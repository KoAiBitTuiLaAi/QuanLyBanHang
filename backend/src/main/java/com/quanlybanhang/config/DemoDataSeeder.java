package com.quanlybanhang.config;

import com.quanlybanhang.dto.request.OrderItemRequest;
import com.quanlybanhang.dto.request.OrderRequest;
import com.quanlybanhang.dto.request.DebtPaymentRequest;
import com.quanlybanhang.dto.request.DebtPaymentAdjustmentRequest;
import com.quanlybanhang.dto.request.PaymentAllocationItemRequest;
import com.quanlybanhang.dto.request.WarehouseTransferItemRequest;
import com.quanlybanhang.dto.request.WarehouseTransferRequest;
import com.quanlybanhang.dto.request.WarehouseVoucherItemRequest;
import com.quanlybanhang.dto.request.WarehouseVoucherRequest;
import com.quanlybanhang.dto.response.OrderResponse;
import com.quanlybanhang.dto.response.WarehouseVoucherResponse;
import com.quanlybanhang.model.*;
import com.quanlybanhang.repository.*;
import com.quanlybanhang.service.AuditLogService;
import com.quanlybanhang.service.DealerDebtService;
import com.quanlybanhang.service.OrderService;
import com.quanlybanhang.service.UnitConversionService;
import com.quanlybanhang.service.WarehouseService;
import com.quanlybanhang.service.WarehouseTransferService;
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
    private final WarehouseTransferService warehouseTransferService;
    private final UnitConversionService unitConversionService;
    private final AuditLogService auditLogService;
    private final WarehouseInventoryRepository inventories;
    private final WarehouseLotInventoryRepository lotInventories;
    private final StockMinimumRepository stockMinimums;
    private final WarehouseStockCountRepository stockCounts;
    private final PickingBatchRepository pickingBatches;
    private final DeliveryTripRepository deliveryTrips;
    private final InvoiceRepository invoices;
    private final ReturnOrderRepository returnOrders;
    private final DealerReturnRepository dealerReturns;
    private final CashCollectionRepository cashCollections;
    private final DebtReconciliationRepository reconciliations;
    private final SalesTargetRepository salesTargets;
    private final NotificationRepository notifications;
    private final WarehouseTransferRepository warehouseTransfers;
    private final WarehouseLocationRepository warehouseLocations;
    private final WarehouseLocationProductRepository warehouseLocationProducts;
    private final DebtRiskConfigRepository debtRiskConfigs;
    private final InvoiceAdjustmentRepository invoiceAdjustments;
    private final DebtPaymentRepository debtPayments;
    private final DealerDebtService dealerDebtService;
    private final OrderDraftRepository orderDrafts;
    private final PaymentReminderLogRepository paymentReminderLogs;

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
            createWarehouseLocations(scope, catalog);
            createOrders(scope, catalog, customers, prices);
            createOrderDraftExample(catalog, customers.get("cap1"));
            createSupplementalWorkflows(scope, catalog, customers);
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
                    .packingSpecification(packing)
                    .manageLot(Set.of("KEN330", "TIGER330", "COCA330", "LAVIE500").contains(suffix))
                    .build());
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
        var builder = WarehouseVoucherItemRequest.builder().productId(product.getId()).unit(unit).quantity(decimal(quantity))
                .price(product.getCostPrice().multiply(factor)).note(NOTE);
        if (Boolean.TRUE.equals(product.getManageLot())) {
            builder.lotNumber("DEMO-LOT-" + product.getCode().replace("DEMO-SP-", "") + "-001")
                   .expiryDate(LocalDate.now().plusMonths(6))
                   .manufacturingDate(LocalDate.now().minusMonths(1));
        }
        return builder.build();
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
        // Spread demo sales over the month so dashboard charts and aging reports are useful at first glance.
        seedOrder("DEMO-DH-THANG-01", customers.get("cap1"), prices,
                List.of(orderItem(catalog.get("COCA330"), "thùng", "1", null)), "RECORDED", scope.hcm());
        seedOrder("DEMO-DH-THANG-02", customers.get("cap2"), prices,
                List.of(orderItem(catalog.get("TIGER330"), "thùng", "1", null)), "RECORDED", scope.hcm());
        seedOrder("DEMO-DH-THANG-03", customers.get("cap1"), prices,
                List.of(orderItem(catalog.get("LAVIE500"), "thùng", "1", null)), "RECORDED", scope.hcm());
        seedOrder("DEMO-DH-THANG-04", customers.get("cap2"), prices,
                List.of(orderItem(catalog.get("ANKER65W"), "cái", "1", null)), "RECORDED", scope.hcm());
        seedOrder("DEMO-DH-THANG-05", customers.get("cap2"), prices,
                List.of(orderItem(catalog.get("PHILIPS"), "chiếc", "1", null)), "RECORDED", scope.hcm());
        seedOrder("DEMO-DH-THANG-06", customers.get("cap2"), prices,
                List.of(orderItem(catalog.get("S24U"), "chiếc", "1", null)), "RECORDED", scope.hcm());
        seedOrder("DEMO-DH-QUAHAN-HN-01", customers.get("hanoi"), prices,
                List.of(orderItem(catalog.get("TIGER330"), "thùng", "1", null)), "RECORDED", scope.hanoi());
        seedOrder("DEMO-DH-CHODUYET", customers.get("cap2"), prices, List.of(
                orderItem(catalog.get("IP15PM"), "chiếc", "1", "10000000")), "PENDING_APPROVAL", scope.hcm());
        seedOrder("DEMO-DH-DAHUY", customers.get("cap1"), prices, List.of(
                orderItem(catalog.get("COCA330"), "thùng", "1", null)), "CANCELLED", scope.hcm());
        seedOrder("DEMO-DH-TUCHOI", customers.get("cap2"), prices, List.of(
                orderItem(catalog.get("S24U"), "chiếc", "1", "10000000")), "REJECTED", scope.hcm());
        backdateNewDemoReportOrders();
    }

    private void backdateNewDemoReportOrders() {
        Map<String, Integer> ageDaysByCode = Map.ofEntries(
                Map.entry("DEMO-DH-DOUONG", 1), Map.entry("DEMO-DH-DIENTU", 20),
                Map.entry("DEMO-DH-KHACHLE", 5), Map.entry("DEMO-DH-HANOI", 80),
                Map.entry("DEMO-DH-THANG-01", 2), Map.entry("DEMO-DH-THANG-02", 4),
                Map.entry("DEMO-DH-THANG-03", 7), Map.entry("DEMO-DH-THANG-04", 10),
                Map.entry("DEMO-DH-THANG-05", 14), Map.entry("DEMO-DH-THANG-06", 20),
                Map.entry("DEMO-DH-QUAHAN-HN-01", 80));
        LocalDateTime now = LocalDateTime.now();
        for (var entry : ageDaysByCode.entrySet()) {
            orders.findByOrderCode(entry.getKey()).ifPresent(order -> {
                LocalDateTime createdAt = order.getCreatedAt();
                LocalDateTime recordedAt = order.getRecordedAt();
                // Only age rows created in this seed pass; leave previously present/edited records alone on reruns.
                if (createdAt == null || recordedAt == null || recordedAt.isBefore(createdAt.minusHours(1))
                        || recordedAt.isAfter(now.plusMinutes(1))) return;
                order.setRecordedAt(LocalDate.now().minusDays(entry.getValue()).atTime(10, 0));
                orders.save(order);
            });
        }
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

    /** Adds opt-in, demo-prefixed examples for workflows that are not covered by the basic catalog/orders seed. */
    private void createSupplementalWorkflows(Scope scope, Catalog catalog, Map<String, Dealer> customers) {
        createLotStock(scope, catalog);
        createStockMinimums(scope, catalog);
        createStockCountDraft(scope, catalog);
        createPendingAdjustment(scope, catalog);
        createTransferInTransit(scope, catalog);
        createPickingExample(scope, orders.findByOrderCode("DEMO-DH-DOUONG").orElse(null));
        createDeliveryTripExample(scope, customers.get("cap1"), orders.findByOrderCode("DEMO-DH-DOUONG").orElse(null));
        createDeliveryOutcomeExamples(scope, customers);

        Invoice invoice = createInvoiceExample(catalog, customers.get("cap1"));
        createAgingInvoiceExamples();
        createInvoiceAdjustmentExample(invoice);
        createReturnExamples(scope, invoice, catalog.get("KEN330"));
        createCollectionExample(customers.get("cap1"));
        createAdditionalCollections(customers);
        createDebtPaymentExample(customers.get("cap1"));
        createDebtRiskConfigExample();
        createReconciliationExamples(customers);
        createSalesTargetExample(scope);
        createDealerNotificationExample(customers.get("cap1"), invoice);
        createPaymentReminderExample(invoice);
    }

    private void createOrderDraftExample(Catalog catalog, Dealer dealer) {
        String username = "sales.demo";
        if (!users.existsByUsername(username) || orderDrafts.findByUsername(username).isPresent()) return;
        Product product = catalog.get("KEN330");
        DealerDeliveryPoint point = deliveryPoints.findByDealerId(dealer.getId()).stream().findFirst().orElse(null);
        String draftJson = String.format(java.util.Locale.ROOT,
                "{\"dealerId\":%d,\"deliveryPointId\":%s,\"deliveryDate\":\"%s\",\"note\":\"%s\","
                        + "\"items\":[{\"_key\":\"demo-1\",\"productId\":%d,\"sku\":\"%s\","
                        + "\"name\":\"Sản phẩm demo\",\"unitConversions\":[],\"selectedUnitId\":\"%s\","
                        + "\"qty\":\"2\",\"basePrice\":\"%s\",\"unitPrice\":\"%s\","
                        + "\"floorPrice\":\"%s\",\"manualPrice\":false,\"discountAmount\":\"0\"}],\"_seq\":1}",
                dealer.getId(), point == null ? "null" : point.getId().toString(), LocalDate.now().plusDays(2), NOTE,
                product.getId(), product.getCode(), product.getUnit(), product.getPrice().toPlainString(),
                product.getPrice().toPlainString(), product.getPrice().toPlainString());
        orderDrafts.save(OrderDraft.builder().username(username).draftData(draftJson).build());
    }

    private void createWarehouseLocations(Scope scope, Catalog catalog) {
        for (Warehouse warehouse : List.of(scope.hcm(), scope.hanoi())) {
            String suffix = warehouse.equals(scope.hcm()) ? "HCM" : "HN";
            WarehouseLocation zone = warehouseLocations.findByWarehouseIdAndCode(warehouse.getId(), "DEMO-KHU-A")
                    .orElseGet(() -> warehouseLocations.save(WarehouseLocation.builder().warehouse(warehouse)
                            .code("DEMO-KHU-A").name("Khu A (Demo)").locationType(LocationType.ZONE)
                            .description(NOTE).pickingSequence(1).active(true).build()));
            WarehouseLocation drinksShelf = getOrCreateShelf(warehouse, zone, "DEMO-KE-DO-UONG", "Kệ đồ uống (Demo)", 1);
            WarehouseLocation electronicsShelf = getOrCreateShelf(warehouse, zone, "DEMO-KE-DIEN-TU", "Kệ điện tử (Demo)", 2);
            for (String sku : List.of("KEN330", "TIGER330", "COCA330", "LAVIE500")) {
                assignProductToLocation(drinksShelf, catalog.get(sku), "Kho " + suffix + " (Demo)");
            }
            for (String sku : List.of("IP15PM", "S24U", "ANKER65W", "PHILIPS")) {
                assignProductToLocation(electronicsShelf, catalog.get(sku), "Kho " + suffix + " (Demo)");
            }
        }
    }

    private WarehouseLocation getOrCreateShelf(Warehouse warehouse, WarehouseLocation zone, String code,
                                                String name, int sequence) {
        return warehouseLocations.findByWarehouseIdAndCode(warehouse.getId(), code).orElseGet(() ->
                warehouseLocations.save(WarehouseLocation.builder().warehouse(warehouse).parent(zone).code(code)
                        .name(name).locationType(LocationType.SHELF).description(NOTE)
                        .pickingSequence(sequence).active(true).build()));
    }

    private void assignProductToLocation(WarehouseLocation location, Product product, String note) {
        if (product == null || warehouseLocationProducts.existsByLocationIdAndProductId(location.getId(), product.getId())) return;
        warehouseLocationProducts.save(WarehouseLocationProduct.builder().location(location).product(product)
                .isDefault(true).note(note).build());
    }

    private void createLotStock(Scope scope, Catalog catalog) {
        for (String sku : List.of("KEN330", "TIGER330", "COCA330", "LAVIE500")) {
            Product product = catalog.get(sku);
            if (!Boolean.TRUE.equals(product.getManageLot())) continue;
            for (Warehouse warehouse : List.of(scope.hcm(), scope.hanoi())) {
                BigDecimal quantity = inventories.findByWarehouseIdAndProductId(warehouse.getId(), product.getId())
                        .map(WarehouseInventory::getBaseQuantity).orElse(BigDecimal.ZERO);
                if (quantity.signum() <= 0) continue;
                BigDecimal expired = quantity.multiply(decimal("0.05")).setScale(4, RoundingMode.DOWN);
                BigDecimal soon = quantity.multiply(decimal("0.25")).setScale(4, RoundingMode.DOWN);
                BigDecimal fresh = quantity.subtract(expired).subtract(soon);
                saveLot(warehouse, product, "DEMO-LO-" + sku + "-HET-HAN-" + warehouse.getCode(),
                        LocalDate.now().minusDays(7), expired);
                saveLot(warehouse, product, "DEMO-LO-" + sku + "-GAN-HAN-" + warehouse.getCode(),
                        LocalDate.now().plusDays(14), soon);
                saveLot(warehouse, product, "DEMO-LO-" + sku + "-MOI-" + warehouse.getCode(),
                        LocalDate.now().plusMonths(8), fresh);
            }
        }
    }

    private void saveLot(Warehouse warehouse, Product product, String lotNumber, LocalDate expiry, BigDecimal quantity) {
        if (lotInventories.findByWarehouseIdAndProductIdAndLotNumber(warehouse.getId(), product.getId(), lotNumber).isPresent()) return;
        lotInventories.save(WarehouseLotInventory.builder().warehouse(warehouse).product(product).lotNumber(lotNumber)
                .manufacturingDate(expiry.minusMonths(12)).expiryDate(expiry).baseQuantity(quantity)
                .baseUnit(product.getUnit()).build());
    }

    private void createStockMinimums(Scope scope, Catalog catalog) {
        for (Warehouse warehouse : List.of(scope.hcm(), scope.hanoi())) {
            for (String sku : List.of("KEN330", "TIGER330", "COCA330", "LAVIE500", "ANKER65W")) {
                Product product = catalog.get(sku);
                if (stockMinimums.findByWarehouseIdAndProductId(warehouse.getId(), product.getId()).isPresent()) continue;
                stockMinimums.save(StockMinimum.builder().warehouse(warehouse).product(product)
                        .minimumQuantity(sku.endsWith("330") ? decimal("240") : decimal("20"))
                        .baseUnit(product.getUnit()).updatedBy(ACTOR).updatedAt(LocalDateTime.now()).build());
            }
        }
    }

    private void createStockCountDraft(Scope scope, Catalog catalog) {
        String code = "DEMO-KK-HCM-CHO-DEM";
        if (stockCounts.existsByCode(code)) return;
        WarehouseStockCount count = WarehouseStockCount.builder().code(code).warehouse(scope.hcm())
                .status(StockCountStatus.DRAFT).note(NOTE + ": phiếu nháp để nhập số đếm thực tế")
                .createdBy(ACTOR).build();
        for (String sku : List.of("KEN330", "TIGER330", "COCA330", "LAVIE500")) {
            Product product = catalog.get(sku);
            WarehouseInventory inventory = inventories.findByWarehouseIdAndProductId(scope.hcm().getId(), product.getId()).orElse(null);
            if (inventory == null) continue;
            count.addItem(WarehouseStockCountItem.builder().product(product).baseUnit(product.getUnit())
                    .bookQuantity(inventory.getBaseQuantity()).snapshotCaptured(true).snapshotInventoryId(inventory.getId())
                    .snapshotInventoryVersion(inventory.getVersion()).note("Nhập số đếm để xem chênh lệch").build());
        }
        stockCounts.save(count);
    }

    private void createPendingAdjustment(Scope scope, Catalog catalog) {
        createPendingAdjustmentVoucher("DEMO-DC-TON-HONG-HCM-001", scope.hcm(), catalog.get("PHILIPS"), BigDecimal.ONE);
        createPendingAdjustmentVoucher("DEMO-DC-TON-HN-001", scope.hanoi(), catalog.get("COCA330"), new BigDecimal("2"));
    }

    private void createPendingAdjustmentVoucher(String code, Warehouse warehouse, Product product, BigDecimal quantity) {
        if (vouchers.existsByVoucherCode(code)) return;
        BigDecimal loss = product.getCostPrice().multiply(quantity).setScale(2, RoundingMode.HALF_UP);
        WarehouseVoucher voucher = WarehouseVoucher.builder().voucherCode(code).type(WarehouseVoucherType.ADJUSTMENT)
                .warehouse(warehouse).status("PENDING_APPROVAL").voucherDate(LocalDateTime.now())
                .adjustmentReason("Hàng hỏng cần quản lý kho kiểm tra trước khi ghi giảm tồn (Demo)")
                .adjustmentLossValue(loss).createdBy(ACTOR).recordedAt(LocalDateTime.now()).note(NOTE).build();
        voucher.addItem(WarehouseVoucherItem.builder().product(product).unit(product.getUnit()).quantity(quantity)
                .conversionFactor(BigDecimal.ONE).baseUnit(product.getUnit()).baseQuantity(quantity)
                .price(product.getCostPrice()).totalAmount(loss).lossValue(loss).note("Hàng hỏng chờ duyệt (Demo)").build());
        vouchers.save(voucher);
    }

    private void createTransferInTransit(Scope scope, Catalog catalog) {
        createTransfer(scope, catalog.get("ANKER65W"), "DEMO-CK-HCM-HN-ANKER-001");
        createTransfer(scope, catalog.get("PHILIPS"), "DEMO-CK-HCM-HN-PHILIPS-001");
    }

    private void createTransfer(Scope scope, Product product, String code) {
        if (warehouseTransfers.existsByTransferCode(code)) return;
        var request = WarehouseTransferRequest.builder().transferCode(code)
                .fromWarehouseId(scope.hcm().getId()).toWarehouseId(scope.hanoi().getId())
                .note(NOTE + ": phiếu chuyển đang trên đường; chưa nhận tại kho đến")
                .items(List.of(WarehouseTransferItemRequest.builder().productId(product.getId())
                        .unit(product.getUnit()).quantity(BigDecimal.ONE).note("Chuyển mẫu 1 sản phẩm (Demo)").build()))
                .build();
        var previous = org.springframework.security.core.context.SecurityContextHolder.getContext();
        var bootstrap = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
        bootstrap.setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                ACTOR, "", List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))));
        try {
            org.springframework.security.core.context.SecurityContextHolder.setContext(bootstrap);
            warehouseTransferService.create(request, ACTOR);
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.setContext(previous);
        }
    }

    private void createPickingExample(Scope scope, Order order) {
        String code = "DEMO-SOAN-HCM-001";
        if (order == null || pickingBatches.existsByCode(code)) return;
        PickingBatch batch = PickingBatch.builder().code(code).warehouse(scope.hcm()).status("COMPLETED")
                .note(NOTE + ": lượt soạn mẫu đã hoàn tất").createdBy(ACTOR)
                .createdAt(LocalDateTime.now().minusHours(2)).updatedAt(LocalDateTime.now().minusHours(1))
                .completedAt(LocalDateTime.now().minusHours(1)).build();
        int position = 1;
        for (OrderItem line : order.getItems()) {
            batch.getItems().add(PickingBatchItem.builder().batch(batch).order(order).orderItem(line)
                    .orderCode(order.getOrderCode()).productCode(line.getProduct().getCode())
                    .productName(line.getProduct().getName()).unit(line.getUnit()).baseUnit(line.getBaseUnit())
                    .quantity(line.getQuantity()).conversionFactor(line.getConversionFactor())
                    .pickedQuantity(line.getQuantity()).checked(true).locationDisplay("Khu A / Kệ demo")
                    .routePosition(position++).build());
        }
        pickingBatches.save(batch);
    }

    private void createDeliveryTripExample(Scope scope, Dealer dealer, Order order) {
        String code = "DEMO-CHUYEN-GIAO-HCM-001";
        if (order == null || deliveryTrips.existsByTripCode(code)) return;
        WarehouseVoucher shipment = vouchers.findByVoucherCode("DEMO-PXK-DOUONG").orElse(null);
        DealerDeliveryPoint point = deliveryPoints.findByDealerId(dealer.getId()).stream().findFirst().orElse(null);
        LocalDateTime now = LocalDateTime.now();
        DeliveryTrip trip = DeliveryTrip.builder().tripCode(code).warehouse(scope.hcm()).driverName("Tài xế Demo")
                .driverPhone("0900000099").vehiclePlate("51D-DEMO").routeName("Tuyến Đông TP.HCM (Demo)")
                .territory(scope.east()).status(DeliveryTripStatus.READY).departureTime(now.plusHours(1))
                .expectedCompletionTime(now.plusHours(5)).createdBy(ACTOR).note(NOTE).build();
        DeliveryTripStop stop = DeliveryTripStop.builder().order(order).orderCode(order.getOrderCode())
                .voucherCode(shipment == null ? null : shipment.getVoucherCode()).shipmentVoucher(shipment)
                .dealer(dealer).dealerName(dealer.getName()).deliveryPoint(point)
                .receiverName(point == null ? dealer.getName() : point.getReceiverName())
                .receiverPhone(point == null ? dealer.getPhone() : point.getPhone())
                .deliveryAddress(point == null ? dealer.getAddress() : point.getAddress()).stopOrder(1)
                .status(DeliveryStopStatus.PENDING).deliveryAmount(order.getTotalAmount()).note(NOTE).build();
        addDeliverySnapshotItems(stop, order, shipment);
        trip.addStop(stop);
        deliveryTrips.save(trip);
    }

    private void addDeliverySnapshotItems(DeliveryTripStop stop, Order order, WarehouseVoucher shipment) {
        if (shipment == null) return;
        for (OrderItem orderItem : order.getItems()) {
            WarehouseVoucherItem shipped = shipment.getItems().stream()
                    .filter(item -> item.getProduct().getId().equals(orderItem.getProduct().getId()))
                    .findFirst().orElse(null);
            if (shipped == null) continue;
            stop.addItem(DeliveryTripStopItem.builder().product(orderItem.getProduct())
                    .productCode(orderItem.getProduct().getCode()).productName(orderItem.getProduct().getName())
                    .unit(shipped.getUnit()).sourceVoucherItemId(shipped.getId()).orderItemId(orderItem.getId())
                    .conversionFactor(shipped.getConversionFactor()).baseQuantity(shipped.getBaseQuantity())
                    .baseUnit(shipped.getBaseUnit()).quantity(shipped.getQuantity()).unitPrice(shipped.getPrice())
                    .totalPrice(shipped.getTotalAmount()).lotNumber(shipped.getLotNumber())
                    .manufacturingDate(shipped.getManufacturingDate()).expiryDate(shipped.getExpiryDate()).build());
        }
    }

    private void createDeliveryOutcomeExamples(Scope scope, Map<String, Dealer> customers) {
        Order deliveredOrder = orders.findByOrderCode("DEMO-DH-DIENTU").orElse(null);
        WarehouseVoucher deliveredVoucher = vouchers.findByVoucherCode("DEMO-PXK-DIENTU").orElse(null);
        if (deliveredOrder != null && deliveredVoucher != null
                && !deliveryTrips.existsByTripCode("DEMO-CHUYEN-GIAO-HCM-DONE-001")) {
            LocalDateTime deliveredAt = LocalDateTime.now().minusDays(1);
            DeliveryTrip trip = DeliveryTrip.builder().tripCode("DEMO-CHUYEN-GIAO-HCM-DONE-001")
                    .warehouse(scope.hcm()).driverName("Tài xế Demo").driverPhone("0900000098")
                    .vehiclePlate("51D-DEMO").routeName("Tuyến mẫu đã hoàn tất").territory(scope.west())
                    .status(DeliveryTripStatus.COMPLETED).departureTime(deliveredAt.minusHours(3))
                    .expectedCompletionTime(deliveredAt.minusHours(1)).actualCompletionTime(deliveredAt)
                    .createdBy(ACTOR).note(NOTE).build();
            Dealer dealer = customers.get("cap2");
            DealerDeliveryPoint point = deliveryPoints.findByDealerId(dealer.getId()).stream().findFirst().orElse(null);
            byte[] proof = java.util.Base64.getDecoder().decode(
                    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/pWQAAAAASUVORK5CYII=");
            DeliveryTripStop stop = DeliveryTripStop.builder().order(deliveredOrder).orderCode(deliveredOrder.getOrderCode())
                    .voucherCode(deliveredVoucher.getVoucherCode()).shipmentVoucher(deliveredVoucher).dealer(dealer)
                    .dealerName(dealer.getName()).deliveryPoint(point)
                    .receiverName(point == null ? dealer.getName() : point.getReceiverName())
                    .receiverPhone(point == null ? dealer.getPhone() : point.getPhone())
                    .deliveryAddress(point == null ? dealer.getAddress() : point.getAddress()).stopOrder(1)
                    .status(DeliveryStopStatus.DELIVERED).deliveryAmount(deliveredOrder.getTotalAmount())
                    .signedProof(proof).proofContentType("image/png").confirmedBy(ACTOR)
                    .deliveredAt(deliveredAt).note(NOTE).build();
            addDeliverySnapshotItems(stop, deliveredOrder, deliveredVoucher);
            trip.addStop(stop);
            deliveryTrips.save(trip);
        }

        Order failedOrder = orders.findByOrderCode("DEMO-DH-HANOI").orElse(null);
        WarehouseVoucher failedVoucher = vouchers.findByVoucherCode("DEMO-PXK-HANOI").orElse(null);
        if (failedOrder != null && failedVoucher != null
                && !deliveryTrips.existsByTripCode("DEMO-CHUYEN-GIAO-HN-FAILED-001")) {
            Dealer dealer = customers.get("hanoi");
            DealerDeliveryPoint point = deliveryPoints.findByDealerId(dealer.getId()).stream().findFirst().orElse(null);
            LocalDateTime failedAt = LocalDateTime.now().minusHours(2);
            DeliveryTrip trip = DeliveryTrip.builder().tripCode("DEMO-CHUYEN-GIAO-HN-FAILED-001")
                    .warehouse(scope.hanoi()).driverName("Tài xế Demo").driverPhone("0900000097")
                    .vehiclePlate("29D-DEMO").routeName("Tuyến mẫu có điểm giao lỗi").territory(scope.north())
                    .status(DeliveryTripStatus.IN_PROGRESS).departureTime(failedAt.minusHours(2))
                    .expectedCompletionTime(LocalDateTime.now().plusHours(2)).createdBy(ACTOR).note(NOTE).build();
            DeliveryTripStop stop = DeliveryTripStop.builder().order(failedOrder).orderCode(failedOrder.getOrderCode())
                    .voucherCode(failedVoucher.getVoucherCode()).shipmentVoucher(failedVoucher).dealer(dealer)
                    .dealerName(dealer.getName()).deliveryPoint(point)
                    .receiverName(point == null ? dealer.getName() : point.getReceiverName())
                    .receiverPhone(point == null ? dealer.getPhone() : point.getPhone())
                    .deliveryAddress(point == null ? dealer.getAddress() : point.getAddress()).stopOrder(1)
                    .status(DeliveryStopStatus.FAILED).deliveryAmount(failedOrder.getTotalAmount())
                    .failedReason("Đại lý đóng cửa tại thời điểm giao (Demo)").failedAt(failedAt)
                    .returnedToWarehouse(false).note(NOTE).build();
            addDeliverySnapshotItems(stop, failedOrder, failedVoucher);
            trip.addStop(stop);
            deliveryTrips.save(trip);
        }
    }

    private Invoice createInvoiceExample(Catalog catalog, Dealer dealer) {
        String code = "DEMO-HD-00000001";
        return invoices.findByInvoiceCode(code).orElseGet(() -> {
            Product product = catalog.get("KEN330");
            BigDecimal quantity = decimal("2");
            BigDecimal unitPrice = product.getPrice();
            BigDecimal subtotal = quantity.multiply(unitPrice);
            Invoice invoice = Invoice.builder().invoiceNumber("DEMO00000001").invoiceCode(code)
                    .invoiceDate(LocalDateTime.now().minusDays(2)).dueDate(LocalDate.now().plusDays(3))
                    .dealer(dealer).dealerCode(dealer.getCode()).dealerName(dealer.getName())
                    .dealerTaxCode(dealer.getTaxCode()).dealerAddress(dealer.getAddress()).dealerPhone(dealer.getPhone())
                    .status(InvoiceStatus.ISSUED).subtotalAmount(subtotal).discountAmount(BigDecimal.ZERO)
                    .totalAmount(subtotal).adjustedTotalAmount(subtotal).isPaid(false).paidAmount(BigDecimal.ZERO)
                    .note(NOTE + ": hóa đơn minh họa còn hạn thanh toán").createdBy(ACTOR).build();
            invoice.addItem(InvoiceItem.builder().product(product).productCode(product.getCode()).productName(product.getName())
                    .unit(product.getUnit()).orderedQuantity(quantity).deliveredQuantity(quantity).invoicedQuantity(quantity)
                    .unitPrice(unitPrice).subtotalAmount(subtotal).discountAmount(BigDecimal.ZERO).totalAmount(subtotal)
                    .note(NOTE).build());
            return invoices.save(invoice);
        });
    }

    private void createAgingInvoiceExamples() {
        createInvoiceFromSeedOrder("DEMO-DH-THANG-06", "DEMO-HD-00000002", "DEMO00000002");
        createInvoiceFromSeedOrder("DEMO-DH-QUAHAN-HN-01", "DEMO-HD-00000003", "DEMO00000003");
    }

    private void createInvoiceFromSeedOrder(String orderCode, String invoiceCode, String invoiceNumber) {
        if (invoices.findByInvoiceCode(invoiceCode).isPresent()) return;
        Order order = orders.findByOrderCode(orderCode).orElse(null);
        if (order == null || order.getItems().isEmpty()) return;
        LocalDateTime issueDate = order.getRecordedAt() != null ? order.getRecordedAt() : order.getCreatedAt();
        int creditDays = order.getDealer().getMaxDebtDays() == null ? 0 : order.getDealer().getMaxDebtDays();
        Invoice invoice = Invoice.builder().invoiceNumber(invoiceNumber).invoiceCode(invoiceCode)
                .invoiceDate(issueDate).dueDate(issueDate.toLocalDate().plusDays(creditDays))
                .dealer(order.getDealer()).dealerCode(order.getDealer().getCode()).dealerName(order.getDealer().getName())
                .dealerTaxCode(order.getDealer().getTaxCode()).dealerAddress(order.getDealer().getAddress())
                .dealerPhone(order.getDealer().getPhone()).status(InvoiceStatus.ISSUED)
                .subtotalAmount(order.getSubtotalAmount()).discountAmount(order.getDiscountAmount())
                .totalAmount(order.getTotalAmount()).adjustedTotalAmount(order.getTotalAmount())
                .isPaid(false).paidAmount(BigDecimal.ZERO).note(NOTE + ": hóa đơn demo quá hạn theo dữ liệu giao hàng")
                .createdBy(ACTOR).build();
        invoice.addOrder(order);
        for (OrderItem orderItem : order.getItems()) {
            BigDecimal quantity = orderItem.getDeliveredQuantity() != null
                    ? orderItem.getDeliveredQuantity() : orderItem.getQuantity();
            BigDecimal subtotal = quantity.multiply(orderItem.getPrice());
            BigDecimal discount = orderItem.getDiscountAmount() == null ? BigDecimal.ZERO : orderItem.getDiscountAmount();
            invoice.addItem(InvoiceItem.builder().order(order).orderCode(order.getOrderCode())
                    .orderItemId(orderItem.getId()).product(orderItem.getProduct())
                    .productCode(orderItem.getProduct().getCode()).productName(orderItem.getProduct().getName())
                    .unit(orderItem.getUnit()).orderedQuantity(orderItem.getQuantity())
                    .deliveredQuantity(quantity).invoicedQuantity(quantity).unitPrice(orderItem.getPrice())
                    .subtotalAmount(subtotal).discountAmount(discount).totalAmount(subtotal.subtract(discount))
                    .note(NOTE).build());
        }
        invoices.save(invoice);
    }

    private void createReturnExamples(Scope scope, Invoice invoice, Product product) {
        InvoiceItem line = invoice.getItems().stream().filter(item -> item.getProduct().getId().equals(product.getId()))
                .findFirst().orElse(null);
        if (line == null) return;
        String code = "DEMO-TR-NHAP-001";
        if (returnOrders.findAll().stream().noneMatch(row -> code.equals(row.getReturnCode()))) {
            ReturnOrder order = ReturnOrder.builder().returnCode(code).invoice(invoice).dealer(invoice.getDealer())
                    .status(ReturnOrderStatus.DRAFT).returnReason("Bao bì móp, chờ kiểm tra hàng trả (Demo)")
                    .totalReturnAmount(line.getUnitPrice()).createdBy(ACTOR).note(NOTE).build();
            BigDecimal qty = BigDecimal.ONE;
            order.addItem(ReturnOrderItem.builder().product(product).productCode(product.getCode())
                    .productName(product.getName()).unit(line.getUnit()).invoiceItem(line).returnQuantity(qty)
                    .invoiceUnitPrice(line.getUnitPrice()).invoiceDiscountPerUnit(BigDecimal.ZERO)
                    .returnAmount(line.getUnitPrice().multiply(qty)).note(NOTE).build());
            returnOrders.save(order);
        }
        String dealerReturnCode = "DEMO-PTH-CHO-NHAN-001";
        if (dealerReturns.existsByReturnCode(dealerReturnCode)) return;
        DealerReturn dealerReturn = DealerReturn.builder().returnCode(dealerReturnCode).invoice(invoice)
                .invoiceCode(invoice.getInvoiceCode()).dealer(invoice.getDealer()).dealerCode(invoice.getDealerCode())
                .dealerName(invoice.getDealerName()).warehouse(scope.hcm()).defectiveWarehouse(scope.hcm())
                .status(DealerReturnStatus.PENDING).returnReason("Chờ quản lý kho xác nhận hàng nhận (Demo)")
                .createdBy(ACTOR).note(NOTE).build();
        BigDecimal qty = new BigDecimal("0.25");
        dealerReturn.addItem(DealerReturnItem.builder().invoiceItem(line).product(product).productCode(product.getCode())
                .productName(product.getName()).manageLot(Boolean.TRUE.equals(product.getManageLot())).unit(line.getUnit())
                .conversionFactor(BigDecimal.ONE).baseUnit(product.getUnit()).deliveredQuantity(line.getDeliveredQuantity())
                .previouslyReturnedQuantity(BigDecimal.ZERO).returnQuantity(qty).baseReturnQuantity(qty)
                .unitPrice(line.getUnitPrice()).totalAmount(line.getUnitPrice().multiply(qty))
                .returnReason("Chờ kiểm tra tình trạng hàng (Demo)").defective(false).itemCondition("GOOD")
                .targetWarehouse(scope.hcm()).lotNumber(Boolean.TRUE.equals(product.getManageLot()) ? "DEMO-LO-KEN330-MOI-DEMO-KHO-HCM" : null)
                .expiryDate(Boolean.TRUE.equals(product.getManageLot()) ? LocalDate.now().plusMonths(8) : null)
                .manufacturingDate(Boolean.TRUE.equals(product.getManageLot()) ? LocalDate.now().minusMonths(4) : null)
                .note(NOTE).build());
        dealerReturns.save(dealerReturn);
    }

    private void createCollectionExample(Dealer dealer) {
        if (cashCollections.findAll().stream().anyMatch(row -> row.getNote() != null
                && row.getNote().contains("DEMO-COLLECTION-PENDING"))) return;
        User employee = users.findByUsername(ACTOR).orElse(null);
        if (employee == null) return;
        byte[] receipt = java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/pWQAAAAASUVORK5CYII=");
        CashCollection collection = new CashCollection();
        collection.setDealer(dealer);
        collection.setEmployee(employee);
        collection.setRouteName("Tuyến Đông TP.HCM (Demo)");
        collection.setAmount(new BigDecimal("250000"));
        collection.setCollectionDate(LocalDate.now());
        collection.setNote("DEMO-COLLECTION-PENDING: " + NOTE + ": chờ kế toán xác nhận");
        collection.setStatus(CashCollection.Status.PENDING);
        collection.setReceipt(receipt);
        collection.setReceiptContentType("image/png");
        collection.setCreatedAt(LocalDateTime.now());
        cashCollections.save(collection);
    }

    private void createAdditionalCollections(Map<String, Dealer> customers) {
        User employee = users.findByUsername(ACTOR).orElse(null);
        if (employee == null) return;
        createRouteCollection("DEMO-COLLECTION-ROUTE-002", customers.get("cap2"), employee,
                "Tuyến Tây TP.HCM (Demo)", new BigDecimal("420000"), LocalDate.now().minusDays(1));
        createRouteCollection("DEMO-COLLECTION-ROUTE-003", customers.get("hanoi"), employee,
                "Tuyến Hà Nội (Demo)", new BigDecimal("680000"), LocalDate.now().minusDays(2));
        createRouteCollection("DEMO-COLLECTION-ROUTE-004", customers.get("cap1"), employee,
                "Tuyến Đông TP.HCM (Demo)", new BigDecimal("315000"), LocalDate.now());
    }

    private void createRouteCollection(String code, Dealer dealer, User employee, String route,
                                      BigDecimal amount, LocalDate date) {
        if (cashCollections.findAll().stream().anyMatch(row -> row.getNote() != null && row.getNote().contains(code))) return;
        byte[] receipt = java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/pWQAAAAASUVORK5CYII=");
        CashCollection collection = new CashCollection();
        collection.setDealer(dealer);
        collection.setEmployee(employee);
        collection.setRouteName(route);
        collection.setAmount(amount);
        collection.setCollectionDate(date);
        collection.setNote(code + ": " + NOTE + ": chờ kế toán xác nhận");
        collection.setStatus(CashCollection.Status.PENDING);
        collection.setReceipt(receipt);
        collection.setReceiptContentType("image/png");
        collection.setCreatedAt(date.atTime(11, 0));
        cashCollections.save(collection);
    }

    private void createReconciliationExamples(Map<String, Dealer> customers) {
        createReconciliation("demo-s7-06-reconciliation-001", "DEMO-DC-2026-001", customers.get("cap1"), 30, 38_000);
        createReconciliation("demo-s7-06-reconciliation-002", "DEMO-DC-2026-002", customers.get("cap2"), 60, 2_450_000);
        createReconciliation("demo-s7-06-reconciliation-003", "DEMO-DC-2026-003", customers.get("hanoi"), 90, 5_780_000);
    }

    private void createReconciliation(String key, String code, Dealer dealer, int periodDays, long closingBalance) {
        if (reconciliations.findByRequestKey(key).isPresent()) return;
        String snapshot = String.format(java.util.Locale.ROOT,
                "{\"openingBalance\":%d,\"invoicedAmount\":%d,\"paymentAmount\":0,\"closingBalance\":%d,\"currency\":\"VND\"}",
                closingBalance / 2, closingBalance - closingBalance / 2, closingBalance);
        reconciliations.save(DebtReconciliation.builder().code(code).requestKey(key)
                .dealer(dealer).from(LocalDate.now().minusDays(periodDays)).to(LocalDate.now())
                .snapshotJson(snapshot).createdBy(ACTOR).createdAt(LocalDateTime.now())
                .status(DebtReconciliation.Status.PENDING).build());
    }

    private void createSalesTargetExample(Scope scope) {
        int year = LocalDate.now().getYear();
        int month = LocalDate.now().getMonthValue();
        String period = year + String.format("%02d", month);
        createTerritoryTarget(scope.east(), period, "HCM-DONG", new BigDecimal("50000000"));
        createTerritoryTarget(scope.west(), period, "HCM-TAY", new BigDecimal("42000000"));
        createTerritoryTarget(scope.north(), period, "HA-NOI", new BigDecimal("60000000"));
    }

    private void createTerritoryTarget(Territory territory, String period, String suffix, BigDecimal amount) {
        String code = "DEMO-CT-" + period + "-" + suffix;
        String scopeKey = SalesTarget.buildScopeKey(Integer.parseInt(period.substring(0, 4)),
                Integer.parseInt(period.substring(4, 6)), null, territory.getId());
        if (salesTargets.existsByTargetCode(code) || salesTargets.existsByScopeKey(scopeKey)) return;
        salesTargets.save(SalesTarget.builder().targetCode(code).scopeKey(scopeKey)
                .targetYear(Integer.parseInt(period.substring(0, 4))).targetMonth(Integer.parseInt(period.substring(4, 6)))
                .scopeType(SalesTargetScopeType.TERRITORY).territory(territory).targetAmount(amount)
                .locked(false).note(NOTE).createdBy(ACTOR).updatedBy(ACTOR).build());
    }

    private void createDealerNotificationExample(Dealer dealer, Invoice invoice) {
        if (notifications.findAll().stream().anyMatch(row -> invoice.getInvoiceCode().equals(row.getReferenceCode()))) return;
        notifications.save(Notification.builder().dealer(dealer).title("Hóa đơn demo đã phát hành")
                .message("Hóa đơn " + invoice.getInvoiceCode() + " đang chờ thanh toán. Đây là dữ liệu minh họa.")
                .type(NotificationType.SYSTEM).referenceType("INVOICE").referenceId(invoice.getId())
                .referenceCode(invoice.getInvoiceCode()).isRead(false).build());
    }

    private void createInvoiceAdjustmentExample(Invoice invoice) {
        String code = "DEMO-HDDC-00000001";
        if (invoiceAdjustments.findByAdjustmentCode(code).isPresent()) return;
        InvoiceAdjustment adjustment = InvoiceAdjustment.builder().adjustmentCode(code).invoice(invoice)
                .adjustmentType(InvoiceAdjustmentType.INFORMATION)
                .adjustmentReason("Bổ sung ghi chú địa chỉ giao hàng theo yêu cầu đại lý (Demo)")
                .adjustmentAmount(BigDecimal.ZERO).newDealerAddress(invoice.getDealerAddress())
                .adjustedBy(ACTOR).note(NOTE).build();
        invoice.addAdjustment(adjustment);
        invoice.setStatus(InvoiceStatus.ADJUSTED);
        invoices.save(invoice);
    }

    private void createDebtPaymentExample(Dealer dealer) {
        DebtPayment payment = debtPayments.findAll().stream().filter(row -> row.getNote() != null
                && row.getNote().contains("DEMO-DEBT-PAYMENT-ALLOCATED")).findFirst().orElse(null);
        if (payment == null) {
            Order order = orders.findByOrderCode("DEMO-DH-DOUONG").orElse(null);
            if (order == null || !"RECORDED".equals(order.getStatus())) return;
            BigDecimal remaining = order.getTotalAmount().subtract(order.getPaidAmount()).max(BigDecimal.ZERO);
            BigDecimal amount = remaining.min(new BigDecimal("50000"));
            if (amount.signum() <= 0) return;
            dealerDebtService.recordPayment(DebtPaymentRequest.builder().dealerId(dealer.getId()).amount(amount)
                    .paymentDate(LocalDate.now().minusDays(1)).paymentMethod("CHUYEN_KHOAN")
                    .referenceCode("DEMO-UNC-0001").recordedBy(ACTOR)
                    .note("DEMO-DEBT-PAYMENT-ALLOCATED: " + NOTE)
                    .autoAllocate(false).allocations(List.of(PaymentAllocationItemRequest.builder().orderId(order.getId())
                            .amount(amount).note("Khoản demo đối trừ một phần vào đơn đã ghi nhận").build()))
                    .build(), ACTOR);
            payment = debtPayments.findAll().stream().filter(row -> row.getNote() != null
                    && row.getNote().contains("DEMO-DEBT-PAYMENT-ALLOCATED")).findFirst().orElse(null);
        }
        if (payment != null && !debtPayments.existsByOriginalPaymentIdAndPaymentType(payment.getId(), "ADJUSTMENT")) {
            dealerDebtService.adjustPayment(payment.getId(), DebtPaymentAdjustmentRequest.builder()
                    .reason("Điều chỉnh mẫu giảm một phần khoản thu (Demo)").adjustmentType("PARTIAL_REVERSAL")
                    .adjustmentAmount(new BigDecimal("10000")).adjustmentDate(LocalDate.now())
                    .recordedBy(ACTOR).build(), ACTOR);
        }
    }

    private void createDebtRiskConfigExample() {
        // Never replace a real system setting with a demo default.
        if (debtRiskConfigs.findFirstByOrderByIdAsc().isPresent()) return;
        debtRiskConfigs.save(DebtRiskConfig.builder().defaultAction(DebtFlagAction.BLOCK)
                .overdueAction(DebtFlagAction.BLOCK).limitExceededAction(DebtFlagAction.REQUIRE_APPROVAL)
                .autoFlagEnabled(true).updatedBy(ACTOR).build());
    }

    private void createPaymentReminderExample(Invoice invoice) {
        LocalDate today = LocalDate.now();
        if (paymentReminderLogs.existsByInvoiceIdAndReminderDate(invoice.getId(), today)) return;
        paymentReminderLogs.save(PaymentReminderLog.builder().invoice(invoice).dealer(invoice.getDealer())
                .reminderDate(today).reminderType(PaymentReminderType.BEFORE_DUE_3_DAYS).daysCount(3)
                .amount(invoice.getAdjustedTotalAmount()).emailSent(false).inAppNotificationSent(false)
                .emailError("Bản ghi demo: không gửi email trong quá trình seed")
                .note("DEMO: minh họa nhắc hóa đơn trước hạn; không phát email/thông báo thật")
                .sentAt(LocalDateTime.now()).build());
    }

    private void audit(String type, String code, String value) {
        auditLogService.logAction(type, code, "CREATE", null, value, NOTE, ACTOR);
    }

    private static BigDecimal decimal(String value) { return new BigDecimal(value); }
}
