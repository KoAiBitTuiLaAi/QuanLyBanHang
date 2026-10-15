package com.quanlybanhang.config;

import com.quanlybanhang.dto.request.WarehouseVoucherRequest;
import com.quanlybanhang.model.*;
import com.quanlybanhang.repository.*;
import com.quanlybanhang.service.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Checks relations, stock movements and repeat runs using real JPA repositories. */
@DataJpaTest(showSql = false, properties = {
        "spring.datasource.url=jdbc:h2:mem:demo-seed;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({DemoDataSeeder.class, OrderServiceImpl.class, WarehouseServiceImpl.class,
        UnitConversionServiceImpl.class, VolumeDiscountPolicyServiceImpl.class,
        DealerDebtServiceImpl.class,
        AuditLogServiceImpl.class, DemoDataSeederTest.EncoderConfig.class})
class DemoDataSeederTest {
    @TestConfiguration
    static class EncoderConfig {
        @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
    }

    @Autowired DemoDataSeeder seeder;
    @Autowired PasswordEncoder encoder;
    @Autowired EntityManager entityManager;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired WarehouseService warehouseService;
    @Autowired WarehouseRepository warehouses;
    @Autowired TerritoryRepository territories;
    @Autowired RoleRepository roles;
    @Autowired UserRepository users;
    @Autowired CategoryRepository categories;
    @Autowired ProductRepository products;
    @Autowired SupplierRepository suppliers;
    @Autowired UnitConversionRepository conversions;
    @Autowired DealerRepository dealers;
    @Autowired DealerDeliveryPointRepository deliveryPoints;
    @Autowired DealerAssignmentHistoryRepository assignments;
    @Autowired PriceListRepository priceLists;
    @Autowired PriceListItemRepository priceItems;
    @Autowired ProductPriceHistoryRepository priceHistory;
    @Autowired VolumeDiscountPolicyRepository policies;
    @Autowired VolumeDiscountTierRepository tiers;
    @Autowired OrderRepository orders;
    @Autowired WarehouseVoucherRepository vouchers;
    @Autowired WarehouseInventoryRepository inventories;
    @Autowired AuditLogRepository auditLogs;

    @BeforeEach
    void systemAccounts() {
        for (ERole role : ERole.values()) roles.save(new Role(role));
        users.save(User.builder().username("admin").email("admin@example.invalid")
                .password(encoder.encode("admin-password")).fullName("Quản trị viên")
                .roles(Set.of(roles.findByName(ERole.ROLE_ADMIN).orElseThrow())).build());
    }

    @Test
    void disabledSeedDoesNotWriteBusinessData() {
        Map<String, Long> before = counts();
        seeder.seed(false, false, null);
        assertThat(counts()).isEqualTo(before);
    }

    @Test
    void missingPasswordFailsBeforeWritingScopesOrUsers() {
        Map<String, Long> before = counts();
        assertThatThrownBy(() -> seeder.seed(true, true, "  "))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("APP_DEMO_USER_PASSWORD");
        assertThat(counts()).isEqualTo(before);
    }

    @Test
    void usersOnlyCreatesEveryNonAdminRoleWithEncodedPasswordsAndScopes() {
        seeder.seed(true, false, "demo-password");
        entityManager.flush();
        entityManager.clear();
        assertThat(users.count()).isEqualTo(8);
        assertThat(warehouses.count()).isEqualTo(2);
        assertThat(territories.count()).isEqualTo(3);
        assertThat(products.count()).isZero();
        assertThat(orders.count()).isZero();
        for (User user : users.findAll()) {
            if ("admin".equals(user.getUsername())) continue;
            assertThat(user.getRoles()).hasSize(1);
            assertThat(user.getPassword()).isNotEqualTo("demo-password");
            assertThat(encoder.matches("demo-password", user.getPassword())).isTrue();
        }
        assertThat(users.findByUsername("sales.demo").orElseThrow().getTerritories()).hasSize(3);
        for (String name : List.of("warehouse.manager", "warehouse.demo")) {
            assertThat(users.findByUsername(name).orElseThrow().getWarehouses()).hasSize(2);
        }
        assertThat(users.findByUsername("warehouse.manager").orElseThrow().getRoles())
                .extracting(Role::getName).containsExactly(ERole.ROLE_WAREHOUSE_MANAGER);
        assertThat(users.findByUsername("dealer.demo").orElseThrow().getRoles())
                .extracting(Role::getName).containsExactly(ERole.ROLE_DEALER);
    }

    @Test
    void dataOnlyDoesNotCreateOrAssignDemoUsers() {
        seeder.seed(false, true, null);
        assertThat(users.count()).isEqualTo(1);
        assertThat(dealers.findAll()).allMatch(dealer -> dealer.getAssignedUser() == null);
        assertThat(assignments.count()).isZero();
        assertThat(products.count()).isEqualTo(8);
        assertThat(orders.count()).isEqualTo(7);
    }

    @Test
    void completeDatasetHasValidRelationsDiscountsRevenueAndBalancedStock() {
        seeder.seed(true, true, "demo-password");
        entityManager.flush();
        entityManager.clear();
        assertThat(counts()).containsAllEntriesOf(Map.ofEntries(
                Map.entry("users", 8L), Map.entry("warehouses", 2L), Map.entry("territories", 3L),
                Map.entry("categories", 12L), Map.entry("products", 8L), Map.entry("suppliers", 3L),
                Map.entry("conversions", 8L), Map.entry("dealers", 5L), Map.entry("deliveryPoints", 6L),
                Map.entry("assignments", 5L), Map.entry("priceLists", 3L), Map.entry("priceItems", 24L),
                Map.entry("priceHistory", 8L), Map.entry("policies", 3L), Map.entry("tiers", 5L),
                Map.entry("orders", 7L), Map.entry("vouchers", 8L), Map.entry("inventories", 12L)));
        assertThat(categories.findAll()).allSatisfy(category ->
                assertThat(category.getLevel()).isEqualTo(category.getParent() == null ? 1 : category.getParent().getLevel() + 1));
        assertThat(priceLists.findAll()).allMatch(price -> Boolean.TRUE.equals(price.getHasOrders()));
        assertThat(vouchers.findByTypeOrderByRecordedAtDesc(WarehouseVoucherType.IMPORT))
                .allMatch(voucher -> voucher.getSupplier() != null);
        assertThat(orders.findByStatus("RECORDED")).hasSize(4);
        assertThat(orders.findByStatus("PENDING_APPROVAL")).hasSize(1);
        assertThat(orders.findByStatus("CANCELLED")).hasSize(1);
        assertThat(orders.findByStatus("REJECTED")).hasSize(1);
        assertThat(orders.findByOrderCode("DEMO-DH-DOUONG").orElseThrow().getDiscountAmount()).isPositive();
        assertThat(orders.findByOrderCode("DEMO-DH-HANOI").orElseThrow().getDiscountAmount()).isPositive();
        assertThat(orders.findByOrderCode("DEMO-DH-CHODUYET").orElseThrow().getRequiresApproval()).isTrue();
        assertThat(auditLogs.findAll()).anyMatch(log -> "INVENTORY".equals(log.getEntityType()))
                .anyMatch(log -> "INVOICE".equals(log.getEntityType()));

        List<WarehouseVoucher> movements = vouchers.findAll();
        for (WarehouseInventory inventory : inventories.findAll()) {
            BigDecimal expected = BigDecimal.ZERO;
            for (WarehouseVoucher voucher : movements) {
                if (!voucher.getWarehouse().getId().equals(inventory.getWarehouse().getId())) continue;
                for (WarehouseVoucherItem item : voucher.getItems()) {
                    assertThat(item.getBaseQuantity()).isEqualByComparingTo(item.getQuantity().multiply(item.getConversionFactor()));
                    if (!item.getProduct().getId().equals(inventory.getProduct().getId())) continue;
                    expected = voucher.getType() == WarehouseVoucherType.IMPORT
                            ? expected.add(item.getBaseQuantity()) : expected.subtract(item.getBaseQuantity());
                }
            }
            assertThat(inventory.getBaseQuantity()).isEqualByComparingTo(expected).isNotNegative();
        }
        Long kenId = products.findByCode("DEMO-SP-KEN330").orElseThrow().getId();
        Long hcmId = warehouses.findByCode("DEMO-KHO-HCM").orElseThrow().getId();
        assertThat(inventories.findByWarehouseIdAndProductId(hcmId, kenId).orElseThrow().getBaseQuantity())
                .isEqualByComparingTo("840");
        for (Product product : products.findAll()) {
            BigDecimal expectedRevenue = orders.findByStatus("RECORDED").stream().flatMap(order -> order.getItems().stream())
                    .filter(item -> item.getProduct().getId().equals(product.getId())).map(OrderItem::getTotalAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(products.sumRevenueByProductId(product.getId())).isEqualByComparingTo(expectedRevenue);
            assertThat(product.getRevenue()).isZero();
        }
    }

    @Test
    void rerunPreservesEditedAccountsCatalogPricingOrdersAndStock() {
        seeder.seed(true, true, "first-password");
        User sales = users.findByUsername("sales.demo").orElseThrow();
        sales.setPassword(encoder.encode("changed-password"));
        sales.setActive(false);
        sales.setFullName("Tên do người dùng sửa");
        sales.getTerritories().clear();
        Product ken = products.findByCode("DEMO-SP-KEN330").orElseThrow();
        ken.setName("Sản phẩm đã sửa");
        ken.setPrice(new BigDecimal("21000"));
        Dealer dealer = dealers.findByCode("DEMO-DL-MINHPHAT").orElseThrow();
        dealer.setDebtLimit(new BigDecimal("54321"));
        dealer.setLocked(true);
        dealer.setLockReason("Lý do đã sửa");
        PriceList prices = priceLists.findByCode("DEMO-BG-DAI_LY_CAP_1").orElseThrow();
        prices.setActive(false);
        policies.findByCode("DEMO-CK-KEN").orElseThrow().setActive(false);
        orders.findByOrderCode("DEMO-DH-DOUONG").orElseThrow().setStatus("CANCELLED");
        Warehouse hcm = warehouses.findByCode("DEMO-KHO-HCM").orElseThrow();
        warehouseService.adjustInventory(WarehouseVoucherRequest.builder().voucherCode("MANUAL-ADJUST")
                .warehouseId(hcm.getId()).productId(ken.getId()).unit("lon").quantity(new BigDecimal("900")).build(), "admin");
        entityManager.flush();
        Map<String, Long> before = counts();
        seeder.seed(true, true, "new-bootstrap-password");
        entityManager.flush();
        entityManager.clear();
        assertThat(counts()).isEqualTo(before);
        User savedSales = users.findByUsername("sales.demo").orElseThrow();
        assertThat(encoder.matches("changed-password", savedSales.getPassword())).isTrue();
        assertThat(savedSales.getActive()).isFalse();
        assertThat(savedSales.getFullName()).isEqualTo("Tên do người dùng sửa");
        assertThat(savedSales.getTerritories()).isEmpty();
        assertThat(products.findById(ken.getId()).orElseThrow().getName()).isEqualTo("Sản phẩm đã sửa");
        assertThat(products.findById(ken.getId()).orElseThrow().getPrice()).isEqualByComparingTo("21000");
        assertThat(dealers.findById(dealer.getId()).orElseThrow().getDebtLimit()).isEqualByComparingTo("54321");
        assertThat(dealers.findById(dealer.getId()).orElseThrow().getLockReason()).isEqualTo("Lý do đã sửa");
        assertThat(priceLists.findById(prices.getId()).orElseThrow().getActive()).isFalse();
        assertThat(policies.findByCode("DEMO-CK-KEN").orElseThrow().getActive()).isFalse();
        assertThat(orders.findByOrderCode("DEMO-DH-DOUONG").orElseThrow().getStatus()).isEqualTo("CANCELLED");
        assertThat(inventories.findByWarehouseIdAndProductId(hcm.getId(), ken.getId()).orElseThrow().getBaseQuantity())
                .isEqualByComparingTo("900");
    }

    @Test
    void databaseFailureRollsBackUsersScopesAndAllBusinessWrites() {
        TestTransaction.flagForRollback();
        TestTransaction.end();
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            systemAccounts();
            users.save(User.builder().username("email.owner").email("warehouse.manager@demo.invalid")
                    .password(encoder.encode("other-password")).build());
            seeder.seed(true, true, "demo-password");
        })).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(users.count()).isZero();
        assertThat(roles.count()).isZero();
        assertThat(warehouses.count()).isZero();
        assertThat(territories.count()).isZero();
        assertThat(products.count()).isZero();
        assertThat(vouchers.count()).isZero();
        assertThat(auditLogs.count()).isZero();
    }

    private Map<String, Long> counts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("users", users.count());
        counts.put("warehouses", warehouses.count());
        counts.put("territories", territories.count());
        counts.put("categories", categories.count());
        counts.put("products", products.count());
        counts.put("suppliers", suppliers.count());
        counts.put("conversions", conversions.count());
        counts.put("dealers", dealers.count());
        counts.put("deliveryPoints", deliveryPoints.count());
        counts.put("assignments", assignments.count());
        counts.put("priceLists", priceLists.count());
        counts.put("priceItems", priceItems.count());
        counts.put("priceHistory", priceHistory.count());
        counts.put("policies", policies.count());
        counts.put("tiers", tiers.count());
        counts.put("orders", orders.count());
        counts.put("vouchers", vouchers.count());
        counts.put("inventories", inventories.count());
        counts.put("auditLogs", auditLogs.count());
        return counts;
    }
}
