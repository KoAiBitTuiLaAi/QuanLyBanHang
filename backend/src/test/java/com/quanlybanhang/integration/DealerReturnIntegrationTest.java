package com.quanlybanhang.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanlybanhang.config.RolePermissionConfig;
import com.quanlybanhang.model.*;
import com.quanlybanhang.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Kiểm thử tích hợp toàn trình (End-to-End Integration Test) cho S8-01:
 * Lập phiếu trả hàng từ đại lý và nhập lại kho.
 *
 * Kiểm chứng đủ 4 tiêu chí nghiệm thu + phân quyền ma trận động (Database-Backed Permission Matrix):
 * 1. Chọn hoá đơn gốc, chọn dòng hàng và số lượng trả, bắt buộc chọn lý do trả.
 * 2. Số lượng trả không vượt số lượng đã giao trừ phần đã trả trước đó.
 * 3. Hàng trả về nhập lại đúng lô nếu SKU có quản lý lô; hàng hỏng nhập vào kho hàng lỗi.
 * 4. Phiếu trả phải được Quản lý kho xác nhận nhận hàng mới cộng tồn.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:dealer_return_s801;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.show-sql=false",
        "app.jwt.secret=s801-dealer-return-test-secret-key-at-least-32-chars",
        "app.bootstrap.admin-password=AdminPass2026",
        "app.bootstrap.admin-email=admin@example.invalid",
        "app.seed-demo-users=false",
        "app.seed-demo-data=false",
        "app.order.reservation-check-interval-ms=3600000"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DealerReturnIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private UserRepository users;
    @Autowired private RoleRepository roles;
    @Autowired private WarehouseRepository warehouses;
    @Autowired private ProductRepository products;
    @Autowired private CategoryRepository categories;
    @Autowired private DealerRepository dealers;
    @Autowired private UnitConversionRepository conversions;
    @Autowired private WarehouseInventoryRepository inventories;
    @Autowired private WarehouseLotInventoryRepository lotInventories;
    @Autowired private OrderRepository orders;
    @Autowired private InvoiceRepository invoices;
    @Autowired private PasswordEncoder passwords;
    @Autowired private RolePermissionConfig matrix;

    private Warehouse mainWarehouse;
    private Warehouse defectiveWarehouse;
    private Warehouse otherWarehouse;
    private Product lotProduct;
    private Product regularProduct;
    private Dealer dealer;
    private Invoice invoice;
    private InvoiceItem invoiceItemLot;
    private InvoiceItem invoiceItemRegular;

    private String salesToken;
    private String strangerSalesToken;
    private String warehouseStaffToken;
    private String warehouseManagerToken;
    private String otherWarehouseManagerToken;
    private String adminToken;

    @BeforeEach
    void setUpFixtures() throws Exception {
        String key = UUID.randomUUID().toString().substring(0, 8);

        mainWarehouse = warehouses.save(Warehouse.builder()
                .code("KHO-CHINH-" + key).name("Kho Thương Mại " + key).active(true).defective(false).build());
        defectiveWarehouse = warehouses.save(Warehouse.builder()
                .code("KHO-LOI-" + key).name("Kho Hàng Lỗi " + key).active(true).defective(true).build());
        otherWarehouse = warehouses.save(Warehouse.builder()
                .code("KHO-KHAC-" + key).name("Kho Chi Nhánh Khác " + key).active(true).defective(false).build());

        Category cat = categories.save(Category.builder().code("CAT-" + key).name("Đồ uống " + key).build());

        // Sản phẩm 1: Có quản lý lô (1 thùng = 24 lon)
        lotProduct = products.save(Product.builder()
                .code("BIA-LOT-" + key).name("Bia Quản Lý Lô " + key)
                .category(cat).unit("lon").manageLot(true)
                .price(new BigDecimal("20000")).costPrice(new BigDecimal("14000")).active(true).build());
        conversions.save(UnitConversion.builder()
                .product(lotProduct).unitName("thùng").conversionFactor(new BigDecimal("24"))
                .isBaseUnit(false).price(new BigDecimal("480000")).build());

        // Sản phẩm 2: Không quản lý lô
        regularProduct = products.save(Product.builder()
                .code("NUOC-" + key).name("Nước Giải Khát " + key)
                .category(cat).unit("chai").manageLot(false)
                .price(new BigDecimal("10000")).costPrice(new BigDecimal("6000")).active(true).build());

        // Khởi tạo tồn kho & tồn lô tại kho chính (sau khi đã xuất hàng cho đại lý)
        inventories.save(WarehouseInventory.builder()
                .warehouse(mainWarehouse).product(lotProduct)
                .baseQuantity(new BigDecimal("120")).baseUnit("lon").build());
        lotInventories.save(WarehouseLotInventory.builder()
                .warehouse(mainWarehouse).product(lotProduct)
                .lotNumber("LOT-2026-A").expiryDate(LocalDate.now().plusMonths(8))
                .manufacturingDate(LocalDate.now().minusMonths(2))
                .baseQuantity(new BigDecimal("120")).baseUnit("lon").build());

        inventories.save(WarehouseInventory.builder()
                .warehouse(mainWarehouse).product(regularProduct)
                .baseQuantity(new BigDecimal("50")).baseUnit("chai").build());

        // Tạo người dùng theo đúng vai trò
        User sales = createUser("sales-" + key, ERole.ROLE_SALES, Set.of());
        User strangerSales = createUser("stranger-sales-" + key, ERole.ROLE_SALES, Set.of());
        User whStaff = createUser("wh-staff-" + key, ERole.ROLE_WAREHOUSE_STAFF, Set.of(mainWarehouse, defectiveWarehouse));
        User whManager = createUser("wh-manager-" + key, ERole.ROLE_WAREHOUSE_MANAGER, Set.of(mainWarehouse, defectiveWarehouse));
        User otherWhManager = createUser("other-wh-mgr-" + key, ERole.ROLE_WAREHOUSE_MANAGER, Set.of(otherWarehouse));

        dealer = dealers.save(Dealer.builder()
                .code("DL-" + key).name("Đại lý Minh Phát " + key)
                .assignedUser(sales).warehouse(mainWarehouse)
                .customerGroup(CustomerGroup.DAI_LY_CAP_1)
                .debtLimit(new BigDecimal("100000000")).active(true).build());

        // Tạo đơn hàng đã giao và hóa đơn gốc
        Order order = Order.builder()
                .orderCode("DH-" + key).dealer(dealer).customerName(dealer.getName())
                .customerGroup(CustomerGroup.DAI_LY_CAP_1).warehouse(mainWarehouse)
                .status("DELIVERED").actualDeliveryDate(LocalDate.now().minusDays(1))
                .createdBy(sales.getUsername()).build();

        OrderItem oiLot = OrderItem.builder()
                .order(order).product(lotProduct).unit("thùng")
                .conversionFactor(new BigDecimal("24")).baseUnit("lon")
                .quantity(new BigDecimal("10")).baseQuantity(new BigDecimal("240"))
                .deliveredQuantity(new BigDecimal("10")).deliveredBaseQuantity(new BigDecimal("240"))
                .price(new BigDecimal("480000")).subtotalAmount(new BigDecimal("4800000"))
                .totalAmount(new BigDecimal("4800000")).build();

        OrderItem oiReg = OrderItem.builder()
                .order(order).product(regularProduct).unit("chai")
                .conversionFactor(BigDecimal.ONE).baseUnit("chai")
                .quantity(new BigDecimal("30")).baseQuantity(new BigDecimal("30"))
                .deliveredQuantity(new BigDecimal("20")).deliveredBaseQuantity(new BigDecimal("20")) // Đặt 30 nhưng thực giao 20
                .price(new BigDecimal("10000")).subtotalAmount(new BigDecimal("200000"))
                .totalAmount(new BigDecimal("200000")).build();

        order.addItem(oiLot);
        order.addItem(oiReg);
        order = orders.save(order);
        oiLot = order.getItems().get(0);
        oiReg = order.getItems().get(1);

        Invoice inv = Invoice.builder()
                .invoiceNumber("9" + key.substring(0, 7).replaceAll("[^0-9]", "1"))
                .invoiceCode("HD-" + key.toUpperCase())
                .invoiceDate(LocalDateTime.now())
                .dealer(dealer).dealerCode(dealer.getCode()).dealerName(dealer.getName())
                .status(InvoiceStatus.ISSUED)
                .subtotalAmount(new BigDecimal("5000000"))
                .discountAmount(BigDecimal.ZERO)
                .totalAmount(new BigDecimal("5000000"))
                .createdBy("admin").build();
        inv.addOrder(order);

        invoiceItemLot = InvoiceItem.builder()
                .order(order).orderCode(order.getOrderCode()).orderItemId(oiLot.getId())
                .product(lotProduct).productCode(lotProduct.getCode()).productName(lotProduct.getName())
                .unit("thùng").orderedQuantity(new BigDecimal("10"))
                .deliveredQuantity(new BigDecimal("10")).invoicedQuantity(new BigDecimal("10"))
                .unitPrice(new BigDecimal("480000")).subtotalAmount(new BigDecimal("4800000"))
                .discountAmount(BigDecimal.ZERO).totalAmount(new BigDecimal("4800000")).build();

        invoiceItemRegular = InvoiceItem.builder()
                .order(order).orderCode(order.getOrderCode()).orderItemId(oiReg.getId())
                .product(regularProduct).productCode(regularProduct.getCode()).productName(regularProduct.getName())
                .unit("chai").orderedQuantity(new BigDecimal("30"))
                .deliveredQuantity(new BigDecimal("20")).invoicedQuantity(new BigDecimal("20"))
                .unitPrice(new BigDecimal("10000")).subtotalAmount(new BigDecimal("200000"))
                .discountAmount(BigDecimal.ZERO).totalAmount(new BigDecimal("200000")).build();

        inv.addItem(invoiceItemLot);
        inv.addItem(invoiceItemRegular);
        invoice = invoices.save(inv);
        invoiceItemLot = invoice.getItems().get(0);
        invoiceItemRegular = invoice.getItems().get(1);

        salesToken = login(sales.getUsername(), "Pass123456");
        strangerSalesToken = login(strangerSales.getUsername(), "Pass123456");
        warehouseStaffToken = login(whStaff.getUsername(), "Pass123456");
        warehouseManagerToken = login(whManager.getUsername(), "Pass123456");
        otherWarehouseManagerToken = login(otherWhManager.getUsername(), "Pass123456");
        adminToken = login("admin", "AdminPass2026");
    }

    @Test
    @DisplayName("Toàn trình S8-01: Chọn hóa đơn gốc, kiểm tra số lượng trả <= đã giao - đã trả, nhập lại đúng lô & hàng hỏng vào kho lỗi, chỉ cộng tồn khi Quản lý kho xác nhận")
    void endToEndDealerReturnWorkflow() throws Exception {
        // 1. Nhân viên kinh doanh tra cứu thông tin hóa đơn gốc để lập phiếu trả
        JsonNode eligibleInv = call(get("/api/dealer-returns/eligible-invoices/" + invoice.getId()), salesToken, null, 200)
                .get("data");
        assertThat(eligibleInv.get("invoiceCode").asText()).isEqualTo(invoice.getInvoiceCode());
        assertThat(eligibleInv.get("items").size()).isEqualTo(2);

        // 2. Kiểm tra bắt buộc chọn lý do trả: bỏ trống lý do -> 400 Bad Request
        call(post("/api/dealer-returns"), salesToken, Map.of(
                "invoiceId", invoice.getId(),
                "items", List.of(Map.of(
                        "invoiceItemId", invoiceItemRegular.getId(),
                        "returnQuantity", 2,
                        "returnReason", ""
                ))
        ), 400);

        // 3. Kiểm tra SKU có quản lý lô: không truyền số lô hoặc truyền sai lô -> 400 Bad Request
        call(post("/api/dealer-returns"), salesToken, Map.of(
                "invoiceId", invoice.getId(),
                "items", List.of(Map.of(
                        "invoiceItemId", invoiceItemLot.getId(),
                        "returnQuantity", 2,
                        "returnReason", "Đổi trả thương mại"
                ))
        ), 400);

        call(post("/api/dealer-returns"), salesToken, Map.of(
                "invoiceId", invoice.getId(),
                "items", List.of(Map.of(
                        "invoiceItemId", invoiceItemLot.getId(),
                        "returnQuantity", 2,
                        "returnReason", "Đổi trả thương mại",
                        "lotNumber", "LOT-KHONG-TON-TAI"
                ))
        ), 400);

        // 4. Kiểm tra hàng hỏng không được nhập vào kho thương mại thông thường -> 400 Bad Request
        call(post("/api/dealer-returns"), salesToken, Map.of(
                "invoiceId", invoice.getId(),
                "defectiveWarehouseId", mainWarehouse.getId(),
                "items", List.of(Map.of(
                        "invoiceItemId", invoiceItemRegular.getId(),
                        "returnQuantity", 3,
                        "returnReason", "Hàng hỏng bể vỡ",
                        "defective", true
                ))
        ), 400);

        // 5. Nhân viên kinh doanh lập phiếu trả hợp lệ (Lần 1):
        // - Trả 3 thùng Bia (72 lon) hàng tốt thuộc lô LOT-2026-A -> kho chính
        // - Trả 5 chai Nước hàng hỏng -> kho hàng lỗi
        JsonNode createdReturn = call(post("/api/dealer-returns"), salesToken, Map.of(
                "invoiceId", invoice.getId(),
                "defectiveWarehouseId", defectiveWarehouse.getId(),
                "note", "Phiếu trả đợt 1",
                "items", List.of(
                        Map.of(
                                "invoiceItemId", invoiceItemLot.getId(),
                                "returnQuantity", 3,
                                "returnReason", "Giao dư theo thỏa thuận",
                                "defective", false,
                                "lotNumber", "LOT-2026-A"
                        ),
                        Map.of(
                                "invoiceItemId", invoiceItemRegular.getId(),
                                "returnQuantity", 5,
                                "returnReason", "Hàng hỏng khi vận chuyển",
                                "defective", true
                        )
                )
        ), 200).get("data");

        long returnId1 = createdReturn.get("id").asLong();
        assertThat(createdReturn.get("status").asText()).isEqualTo("PENDING");
        assertThat(createdReturn.get("inventoryAdded").asBoolean()).isFalse();

        // 6. KIỂM CHỨNG TIÊU CHÍ 4: Khi phiếu đang PENDING (chưa được Quản lý kho xác nhận),
        // tồn kho thực tế và tồn lô TUYỆT ĐỐI CHƯA ĐƯỢC CỘNG!
        assertThat(inventories.findByWarehouseIdAndProductId(mainWarehouse.getId(), lotProduct.getId())
                .orElseThrow().getBaseQuantity()).isEqualByComparingTo("120");
        assertThat(lotInventories.findByWarehouseIdAndProductIdAndLotNumber(mainWarehouse.getId(), lotProduct.getId(), "LOT-2026-A")
                .orElseThrow().getBaseQuantity()).isEqualByComparingTo("120");
        assertThat(inventories.findByWarehouseIdAndProductId(defectiveWarehouse.getId(), regularProduct.getId()))
                .isEmpty();

        // 7. Nhân viên kinh doanh (ROLE_SALES) và Thủ kho (ROLE_WAREHOUSE_STAFF) KHÔNG ĐƯỢC xác nhận nhận hàng trả -> 403 Forbidden
        call(post("/api/dealer-returns/" + returnId1 + "/confirm"), salesToken, Map.of(), 403);
        call(post("/api/dealer-returns/" + returnId1 + "/confirm"), warehouseStaffToken, Map.of(), 403);

        // Quản lý kho khác không được phân công kho này -> 403 Forbidden
        call(post("/api/dealer-returns/" + returnId1 + "/confirm"), otherWarehouseManagerToken, Map.of(), 403);

        // 8. Quản lý kho phụ trách xác nhận nhận hàng trả -> 200 OK & Cộng tồn kho!
        JsonNode confirmedReturn = call(post("/api/dealer-returns/" + returnId1 + "/confirm"), warehouseManagerToken,
                Map.of("note", "Đã kiểm nhận đủ hàng trả"), 200).get("data");

        assertThat(confirmedReturn.get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(confirmedReturn.get("inventoryAdded").asBoolean()).isTrue();

        // Kiểm chứng tồn kho sau khi Quản lý kho xác nhận:
        // - Bia (hàng tốt, quản lý lô): cộng 3 thùng = 72 lon vào kho chính và đúng lô LOT-2026-A (120 + 72 = 192 lon)
        assertThat(inventories.findByWarehouseIdAndProductId(mainWarehouse.getId(), lotProduct.getId())
                .orElseThrow().getBaseQuantity()).isEqualByComparingTo("192");
        assertThat(lotInventories.findByWarehouseIdAndProductIdAndLotNumber(mainWarehouse.getId(), lotProduct.getId(), "LOT-2026-A")
                .orElseThrow().getBaseQuantity()).isEqualByComparingTo("192");

        // - Nước (hàng hỏng): nhập vào kho hàng lỗi (5 chai), kho chính vẫn giữ nguyên 50 chai!
        assertThat(inventories.findByWarehouseIdAndProductId(mainWarehouse.getId(), regularProduct.getId())
                .orElseThrow().getBaseQuantity()).isEqualByComparingTo("50");
        assertThat(inventories.findByWarehouseIdAndProductId(defectiveWarehouse.getId(), regularProduct.getId())
                .orElseThrow().getBaseQuantity()).isEqualByComparingTo("5");

        // 9. KIỂM CHỨNG TIÊU CHÍ 2: Số lượng trả lần sau không vượt số lượng đã giao trừ phần đã trả trước đó
        // Dòng Nước: đặt 30, thực giao 20, đã trả 5 -> chỉ còn được trả tối đa 15 chai!
        // Thử trả 16 chai -> bị chặn 400 Bad Request!
        call(post("/api/dealer-returns"), salesToken, Map.of(
                "invoiceId", invoice.getId(),
                "items", List.of(Map.of(
                        "invoiceItemId", invoiceItemRegular.getId(),
                        "returnQuantity", 16,
                        "returnReason", "Trả vượt số lượng còn lại",
                        "defective", false
                ))
        ), 400);

        // Trả tiếp đúng 15 chai còn lại -> thành công (200 OK)
        JsonNode secondReturn = call(post("/api/dealer-returns"), salesToken, Map.of(
                "invoiceId", invoice.getId(),
                "items", List.of(Map.of(
                        "invoiceItemId", invoiceItemRegular.getId(),
                        "returnQuantity", 15,
                        "returnReason", "Đổi trả phần còn lại",
                        "defective", false
                ))
        ), 200).get("data");
        long returnId2 = secondReturn.get("id").asLong();

        // Khi đã trả hết (5 + 15 = 20 / 20), thử trả thêm 1 chai nữa -> bị chặn 400 Bad Request
        call(post("/api/dealer-returns"), salesToken, Map.of(
                "invoiceId", invoice.getId(),
                "items", List.of(Map.of(
                        "invoiceItemId", invoiceItemRegular.getId(),
                        "returnQuantity", 1,
                        "returnReason", "Trả thêm khi đã hết hạn mức trả"
                ))
        ), 400);

        // Nếu hủy phiếu trả thứ 2 (đang PENDING), hạn mức trả 15 chai được giải phóng
        call(post("/api/dealer-returns/" + returnId2 + "/cancel"), salesToken,
                Map.of("reason", "Nhập nhầm số lượng"), 200);

        JsonNode eligibleAfterCancel = call(get("/api/dealer-returns/eligible-invoices/" + invoice.getId()), salesToken, null, 200)
                .get("data");
        for (JsonNode itemNode : eligibleAfterCancel.get("items")) {
            if (itemNode.get("invoiceItemId").asLong() == invoiceItemRegular.getId()) {
                assertThat(itemNode.get("deliveredQuantity").decimalValue()).isEqualByComparingTo("20");
                assertThat(itemNode.get("previouslyReturnedQuantity").decimalValue()).isEqualByComparingTo("5");
                assertThat(itemNode.get("returnableQuantity").decimalValue()).isEqualByComparingTo("15");
            }
        }
    }

    @Test
    @DisplayName("Phân quyền ma trận động (Database-Backed Permission Matrix) & Giới hạn phạm vi đại lý")
    void dynamicPermissionRevocationAndDataScope() throws Exception {
        // 1. Nhân viên kinh doanh khác không phụ trách đại lý này -> 403 Forbidden
        call(get("/api/dealer-returns/eligible-invoices/" + invoice.getId()), strangerSalesToken, null, 403);
        call(post("/api/dealer-returns"), strangerSalesToken, Map.of(
                "invoiceId", invoice.getId(),
                "items", List.of(Map.of(
                        "invoiceItemId", invoiceItemRegular.getId(),
                        "returnQuantity", 1,
                        "returnReason", "Trả hàng"
                ))
        ), 403);

        // 2. Thu hồi động quyền ORDER_CREATE khỏi ROLE_SALES -> phiên JWT đang mở lập tức nhận 403 Forbidden
        Set<Permission> originalSalesPerms = matrix.getPermissions(ERole.ROLE_SALES);
        Set<Permission> originalWhMgrPerms = matrix.getPermissions(ERole.ROLE_WAREHOUSE_MANAGER);
        try {
            Set<Permission> withoutCreate = EnumSet.copyOf(originalSalesPerms);
            withoutCreate.remove(Permission.ORDER_CREATE);
            matrix.updateRolePermissions(ERole.ROLE_SALES, withoutCreate);

            call(post("/api/dealer-returns"), salesToken, Map.of(
                    "invoiceId", invoice.getId(),
                    "items", List.of(Map.of(
                            "invoiceItemId", invoiceItemRegular.getId(),
                            "returnQuantity", 1,
                            "returnReason", "Trả hàng"
                    ))
            ), 403);

            // Cấp lại quyền ORDER_CREATE -> tạo lại thành công 200 OK
            matrix.updateRolePermissions(ERole.ROLE_SALES, originalSalesPerms);
            long returnId = call(post("/api/dealer-returns"), salesToken, Map.of(
                    "invoiceId", invoice.getId(),
                    "items", List.of(Map.of(
                            "invoiceItemId", invoiceItemRegular.getId(),
                            "returnQuantity", 2,
                            "returnReason", "Đổi trả hợp lệ"
                    ))
            ), 200).at("/data/id").asLong();

            // 3. Thu hồi động quyền WAREHOUSE_APPROVE khỏi ROLE_WAREHOUSE_MANAGER -> xác nhận bị chặn 403 Forbidden ngay lập tức
            Set<Permission> withoutApprove = EnumSet.copyOf(originalWhMgrPerms);
            withoutApprove.remove(Permission.WAREHOUSE_APPROVE);
            matrix.updateRolePermissions(ERole.ROLE_WAREHOUSE_MANAGER, withoutApprove);

            call(post("/api/dealer-returns/" + returnId + "/confirm"), warehouseManagerToken, Map.of(), 403);

            // Cấp lại WAREHOUSE_APPROVE -> xác nhận thành công 200 OK
            matrix.updateRolePermissions(ERole.ROLE_WAREHOUSE_MANAGER, originalWhMgrPerms);
            call(post("/api/dealer-returns/" + returnId + "/confirm"), warehouseManagerToken, Map.of(), 200);

            // 4. ROLE_ADMIN luôn có toàn quyền
            call(get("/api/dealer-returns/" + returnId), adminToken, null, 200);
        } finally {
            matrix.updateRolePermissions(ERole.ROLE_SALES, originalSalesPerms);
            matrix.updateRolePermissions(ERole.ROLE_WAREHOUSE_MANAGER, originalWhMgrPerms);
        }
    }

    private User createUser(String username, ERole roleName, Set<Warehouse> assignedWarehouses) {
        Role role = roles.findByName(roleName).orElseGet(() -> roles.save(new Role(roleName)));
        return users.save(User.builder()
                .username(username)
                .email(username + "@example.invalid")
                .password(passwords.encode("Pass123456"))
                .fullName(username)
                .active(true)
                .roles(new HashSet<>(Set.of(role)))
                .warehouses(new HashSet<>(assignedWarehouses))
                .build());
    }

    private String login(String username, String password) throws Exception {
        MvcResult res = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", username, "password", password))))
                .andReturn();
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        return json.readTree(res.getResponse().getContentAsString()).at("/data/accessToken").asText();
    }

    private JsonNode call(MockHttpServletRequestBuilder builder, String token, Object body, int expectedStatus) throws Exception {
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        MvcResult result = mvc.perform(builder).andReturn();
        assertThat(result.getResponse().getStatus())
                .as("Response body: %s", result.getResponse().getContentAsString())
                .isEqualTo(expectedStatus);
        return json.readTree(result.getResponse().getContentAsString());
    }
}

