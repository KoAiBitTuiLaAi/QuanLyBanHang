package com.quanlybanhang.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.quanlybanhang.config.RolePermissionConfig;
import com.quanlybanhang.model.*;
import com.quanlybanhang.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:delivery_trip_test;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.open-in-view=false",
        "spring.jpa.show-sql=false",
        "app.jwt.secret=delivery-trip-test-secret-at-least-32-chars",
        "app.bootstrap.admin-password=DeliveryTripTest2026",
        "app.bootstrap.admin-email=trip@example.invalid",
        "app.seed-demo-users=false",
        "app.seed-demo-data=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("DeliveryTripIntegrationTest - Tích hợp toàn diện Chuyến giao hàng & Tiền hàng trên đường (S6-07)")
class DeliveryTripIntegrationTest {

    private static final String API = "/api/warehouse/delivery-trips";

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private WarehouseRepository warehouses;

    @Autowired
    private WarehouseInventoryRepository inventories;

    @Autowired
    private ProductRepository products;

    @Autowired
    private CategoryRepository categories;

    @Autowired
    private UserRepository users;

    @Autowired
    private RoleRepository roles;

    @Autowired
    private PasswordEncoder passwords;

    @Autowired
    private RolePermissionConfig matrix;

    private Warehouse warehouse;
    private Warehouse outside;
    private Category category;
    private Product prod1;
    private Product prod2;
    private String adminToken;
    private String staffToken;

    @BeforeEach
    void setUp() {
        String key = UUID.randomUUID().toString().substring(0, 8);
        warehouse = warehouses.save(Warehouse.builder().code("WH-" + key).name("Kho chính " + key).active(true).build());
        outside = warehouses.save(Warehouse.builder().code("OUT-" + key).name("Kho ngoài " + key).active(true).build());

        category = categories.save(Category.builder().code("CAT-" + key).name("Danh mục " + key).build());

        prod1 = products.save(Product.builder().code("P1-" + key).name("Bia Hà Nội").category(category).unit("thùng")
                .price(new BigDecimal("250000.00")).costPrice(new BigDecimal("200000.00")).build());
        prod2 = products.save(Product.builder().code("P2-" + key).name("Bia Sài Gòn").category(category).unit("thùng")
                .price(new BigDecimal("300000.00")).costPrice(new BigDecimal("240000.00")).build());

        inventories.save(WarehouseInventory.builder().warehouse(warehouse).product(prod1).baseUnit("thùng")
                .baseQuantity(new BigDecimal("100.0000")).build());
        inventories.save(WarehouseInventory.builder().warehouse(warehouse).product(prod2).baseUnit("thùng")
                .baseQuantity(new BigDecimal("100.0000")).build());

        // Phân quyền ban đầu cho ROLE_WAREHOUSE_STAFF
        matrix.updateRolePermissions(ERole.ROLE_WAREHOUSE_STAFF, Set.of(Permission.WAREHOUSE_VIEW, Permission.WAREHOUSE_EXPORT));

        User staff = users.save(User.builder()
                .username("staff-" + key)
                .email("staff-" + key + "@example.invalid")
                .password(passwords.encode("DeliveryTripTest2026"))
                .roles(Set.of(roles.findByName(ERole.ROLE_WAREHOUSE_STAFF).orElseThrow()))
                .warehouses(Set.of(warehouse)) // Chỉ gán warehouse, không gán outside
                .active(true)
                .build());

        adminToken = login("admin");
        staffToken = login(staff.getUsername());
    }

    @Test
    @DisplayName("Luồng đầy đủ S6-07: Tạo chuyến, theo dõi tiền hàng trên đường, hàng FAILED không tự về kho")
    void testDeliveryTripLifecycleAndInTransitMoneyTracking() throws Exception {
        // 1. Tạo chuyến giao hàng với 2 điểm
        Map<String, Object> createReq = Map.of(
                "warehouseId", warehouse.getId(),
                "driverName", "Nguyễn Văn Lái",
                "vehiclePlate", "29B-999.99",
                "expectedCompletionTime", LocalDateTime.now().plusHours(4).toString(),
                "stops", List.of(
                        Map.of(
                                "deliveryAddress", "Điểm giao 1 - Hoàn Kiếm",
                                "deliveryAmount", 10000000.00,
                                "stopOrder", 1
                        ),
                        Map.of(
                                "deliveryAddress", "Điểm giao 2 - Đống Đa",
                                "deliveryAmount", 20000000.00,
                                "stopOrder", 2,
                                "items", List.of(
                                        Map.of(
                                                "productId", prod2.getId(),
                                                "quantity", 50,
                                                "unitPrice", 400000
                                        )
                                )
                        )
                )
        );

        JsonNode createRes = call(HttpMethod.POST, API, staffToken, createReq, 200);
        long tripId = createRes.at("/data/id").asLong();
        long stop1Id = createRes.at("/data/stops/0/id").asLong();
        long stop2Id = createRes.at("/data/stops/1/id").asLong();

        assertThat(createRes.at("/data/status").asText()).isEqualTo("IN_PROGRESS");
        assertThat(createRes.at("/data/totalPoints").asInt()).isEqualTo(2);
        assertThat(createRes.at("/data/remainingPoints").asInt()).isEqualTo(2);
        assertThat(createRes.at("/data/deliveredPoints").asInt()).isEqualTo(0);
        assertThat(createRes.at("/data/totalValue").asDouble()).isEqualTo(30000000.00);
        assertThat(createRes.at("/data/remainingValue").asDouble()).isEqualTo(30000000.00);

        // 2. Kiểm tra summary: Tiền hàng nằm trên đường = 30.000.000 đ
        JsonNode summary1 = call(HttpMethod.GET, API + "/summary?warehouseId=" + warehouse.getId(), staffToken, null, 200).get("data");
        assertThat(summary1.get("totalRunningTrips").asLong()).isEqualTo(1L);
        assertThat(summary1.get("totalValueOnRoad").asDouble()).isEqualTo(30000000.00);
        assertThat(summary1.get("totalDeliveredValue").asDouble()).isEqualTo(0.00);

        // 3. Giao thành công Điểm 1 (10tr)
        Map<String, Object> updateDeliveredReq = Map.of(
                "status", "DELIVERED",
                "note", "Khách đã nhận đủ hàng"
        );
        call(HttpMethod.PUT, API + "/" + tripId + "/stops/" + stop1Id + "/status", staffToken, updateDeliveredReq, 400);
        JsonNode deliveredRes = confirm(tripId, stop1Id, staffToken, 200).get("data");
        assertThat(deliveredRes.at("/stops/0/hasSignedProof").asBoolean()).isTrue();
        HttpHeaders proofHeaders = new HttpHeaders();
        proofHeaders.setBearerAuth(staffToken);
        String proofUrl = API + "/" + tripId + "/stops/" + stop1Id + "/proof";
        var downloaded = http.exchange(proofUrl, HttpMethod.GET, new HttpEntity<>(proofHeaders), byte[].class);
        assertThat(downloaded.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(downloaded.getBody()).isEqualTo(imageBytes());
        assertThat(downloaded.getHeaders().getCacheControl()).isEqualTo("no-store");
        confirm(tripId, stop1Id, staffToken, 400);
        matrix.updateRolePermissions(ERole.ROLE_WAREHOUSE_STAFF, Set.of(Permission.WAREHOUSE_VIEW));
        confirm(tripId, stop2Id, staffToken, 403);
        matrix.updateRolePermissions(ERole.ROLE_WAREHOUSE_STAFF, Set.of());
        assertThat(http.exchange(proofUrl, HttpMethod.GET, new HttpEntity<>(proofHeaders), byte[].class)
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        matrix.updateRolePermissions(ERole.ROLE_WAREHOUSE_STAFF, Set.of(Permission.WAREHOUSE_VIEW, Permission.WAREHOUSE_EXPORT));
        assertThat(deliveredRes.get("deliveredPoints").asLong()).isEqualTo(1L);
        assertThat(deliveredRes.get("remainingPoints").asLong()).isEqualTo(1L);
        assertThat(deliveredRes.get("deliveredValue").asDouble()).isEqualTo(10000000.00);
        assertThat(deliveredRes.get("remainingValue").asDouble()).isEqualTo(20000000.00);

        // 4. Giao thất bại Điểm 2 (20tr)
        Map<String, Object> updateFailedReq = Map.of(
                "status", "FAILED",
                "failedReason", "Cửa hàng đóng cửa, hẹn hôm sau"
        );
        JsonNode failedRes = call(HttpMethod.PUT, API + "/" + tripId + "/stops/" + stop2Id + "/status", staffToken, updateFailedReq, 200).get("data");
        assertThat(failedRes.get("failedPoints").asLong()).isEqualTo(1L);
        assertThat(failedRes.get("remainingPoints").asLong()).isEqualTo(1L); // Vẫn còn 1 điểm chưa hoàn tất
        assertThat(failedRes.get("failedValue").asDouble()).isEqualTo(20000000.00);
        assertThat(failedRes.get("remainingValue").asDouble()).isEqualTo(20000000.00);

        // ══════════════════════════════════════════════════════════════════════════════
        // INVARIANT CỐT LÕI: Hàng chưa giao được vẫn thuộc trách nhiệm chuyến, KHÔNG tự về kho!
        // ══════════════════════════════════════════════════════════════════════════════
        WarehouseInventory inv2 = inventories.findByWarehouseIdAndProductId(warehouse.getId(), prod2.getId()).orElseThrow();
        // Tồn kho vẫn là 100, tuyệt đối KHÔNG tự động tăng!
        assertThat(inv2.getBaseQuantity()).isEqualByComparingTo(new BigDecimal("100.0000"));

        // Tiền hàng trên đường vẫn là 20.000.000 đ
        JsonNode summary2 = call(HttpMethod.GET, API + "/summary?warehouseId=" + warehouse.getId(), staffToken, null, 200).get("data");
        assertThat(summary2.get("totalValueOnRoad").asDouble()).isEqualTo(20000000.00);
        assertThat(summary2.get("totalFailedPoints").asLong()).isEqualTo(1L);
        assertThat(summary2.get("totalRemainingPoints").asLong()).isEqualTo(1L);

        // 5. Thao tác nhập kho trả hàng tường minh (Explicit return)
        Map<String, Object> returnReq = Map.of(
                "returnReason", "Thủ kho kiểm đếm nhận lại 50 thùng bia"
        );
        JsonNode returnRes = call(HttpMethod.POST, API + "/" + tripId + "/stops/" + stop2Id + "/return-to-warehouse", staffToken, returnReq, 200).get("data");
        assertThat(returnRes.get("returnedToWarehouse").asBoolean()).isTrue();

        // Bây giờ tồn kho mới được cộng thêm 50 thùng (100 + 50 = 150)
        WarehouseInventory restockedInv2 = inventories.findByWarehouseIdAndProductId(warehouse.getId(), prod2.getId()).orElseThrow();
        assertThat(restockedInv2.getBaseQuantity()).isEqualByComparingTo(new BigDecimal("150.0000"));
    }

    @Test
    @DisplayName("Cảnh báo chuyến quá thời gian dự kiến (isOverdue = true)")
    void testOverdueTripWarning() {
        // Tạo chuyến với expectedCompletionTime trong quá khứ (1 tiếng trước)
        Map<String, Object> createReq = Map.of(
                "warehouseId", warehouse.getId(),
                "driverName", "Tài xế Quá Hạn",
                "vehiclePlate", "30E-123.45",
                "departureTime", LocalDateTime.now().minusHours(3).toString(),
                "expectedCompletionTime", LocalDateTime.now().minusHours(1).toString(),
                "stops", List.of(
                        Map.of(
                                "deliveryAddress", "Điểm trễ hẹn",
                                "deliveryAmount", 5000000.00
                        )
                )
        );

        JsonNode tripRes = call(HttpMethod.POST, API, adminToken, createReq, 200).get("data");
        long tripId = tripRes.get("id").asLong();

        // Kiểm tra chi tiết chuyến: cảnh báo quá hạn
        JsonNode detail = call(HttpMethod.GET, API + "/" + tripId, adminToken, null, 200).get("data");
        assertThat(detail.get("isOverdue").asBoolean()).isTrue();
        assertThat(detail.get("overdueMinutes").asLong()).isGreaterThanOrEqualTo(59L);
        assertThat(detail.get("overdueWarning").asText()).contains("quá thời gian dự kiến");

        // Lọc chuyến quá hạn: GET ?isOverdue=true
        JsonNode searchOverdue = call(HttpMethod.GET, API + "?isOverdue=true&warehouseId=" + warehouse.getId(), adminToken, null, 200).get("data");
        assertThat(searchOverdue.get("content").size()).isGreaterThanOrEqualTo(1);

        // Summary báo 1 chuyến quá hạn
        JsonNode summary = call(HttpMethod.GET, API + "/summary?warehouseId=" + warehouse.getId(), adminToken, null, 200).get("data");
        assertThat(summary.get("totalOverdueTrips").asLong()).isGreaterThanOrEqualTo(1L);
    }

    @Test
    @DisplayName("Cách ly dữ liệu theo kho (Warehouse Scoping): Nhân viên không được truy cập kho ngoài phạm vi -> 403")
    void testWarehouseScopingIsolation() {
        // Staff được gán warehouse, không được gán outside
        Map<String, Object> outsideReq = Map.of(
                "warehouseId", outside.getId(),
                "driverName", "Tài xế Ngoài",
                "vehiclePlate", "51C-777.77",
                "expectedCompletionTime", LocalDateTime.now().plusHours(3).toString(),
                "stops", List.of(
                        Map.of("deliveryAddress", "Kho ngoài", "deliveryAmount", 1000000.00)
                )
        );

        // Staff tạo chuyến cho kho ngoài -> 403 Forbidden
        call(HttpMethod.POST, API, staffToken, outsideReq, 403);

        // Admin tạo chuyến cho kho ngoài -> 200 OK
        long foreignTripId = call(HttpMethod.POST, API, adminToken, outsideReq, 200).at("/data/id").asLong();

        // Staff xem chi tiết chuyến của kho ngoài -> 403 Forbidden
        call(HttpMethod.GET, API + "/" + foreignTripId, staffToken, null, 403);
    }

    @Test
    @DisplayName("Thu hồi quyền động trên JWT hiện tại: Thu hồi WAREHOUSE_VIEW lập tức bị chặn 403 không fallback role")
    void testDynamicPermissionRevocation() {
        // 1. Staff gọi GET thành công
        call(HttpMethod.GET, API + "/options", staffToken, null, 200);

        // 2. Thu hồi toàn bộ quyền khỏi ROLE_WAREHOUSE_STAFF trong ma trận
        matrix.updateRolePermissions(ERole.ROLE_WAREHOUSE_STAFF, Set.of());

        // 3. Gọi lại ngay trên CÙNG TOKEN JWT -> Lập tức trả 403 Forbidden
        call(HttpMethod.GET, API + "/options", staffToken, null, 403);
        call(HttpMethod.GET, API, staffToken, null, 403);
        call(HttpMethod.GET, API + "/summary", staffToken, null, 403);

        // 4. Cấp lại quyền WAREHOUSE_VIEW
        matrix.updateRolePermissions(ERole.ROLE_WAREHOUSE_STAFF, Set.of(Permission.WAREHOUSE_VIEW));

        // 5. Token hoạt động lại bình thường -> 200 OK
        call(HttpMethod.GET, API + "/options", staffToken, null, 200);
    }

    private byte[] imageBytes() throws Exception {
        var out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    private JsonNode confirm(long tripId, long stopId, String token, int status) throws Exception {
        var body = new org.springframework.util.LinkedMultiValueMap<String, Object>();
        body.add("receiverName", "Người ký nhận");
        body.add("deliveredAt", LocalDateTime.now().toString());
        body.add("proof", new org.springframework.core.io.ByteArrayResource(imageBytes()) {
            @Override public String getFilename() { return "signed.png"; }
        });
        var headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        var response = http.exchange(API + "/" + tripId + "/stops/" + stopId + "/confirm-delivery",
                HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
        assertThat(response.getStatusCode().value()).as("%s", response.getBody()).isEqualTo(status);
        return response.getBody();
    }

    private String login(String username) {
        ResponseEntity<JsonNode> res = http.postForEntity("/api/auth/login",
                Map.of("username", username, "password", "DeliveryTripTest2026"), JsonNode.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        return Objects.requireNonNull(res.getBody()).get("accessToken").asText();
    }

    private JsonNode call(HttpMethod method, String path, String jwt, Object body, int expectedStatus) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (jwt != null) {
            headers.setBearerAuth(jwt);
        }
        ResponseEntity<JsonNode> response = http.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
        assertThat(response.getStatusCode().value())
                .as("%s %s expected %d but was %d, body: %s", method, path, expectedStatus, response.getStatusCode().value(), response.getBody())
                .isEqualTo(expectedStatus);
        return response.getBody();
    }
}

