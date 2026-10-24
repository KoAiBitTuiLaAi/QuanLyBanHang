package com.quanlybanhang.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.quanlybanhang.config.RolePermissionConfig;
import com.quanlybanhang.model.*;
import com.quanlybanhang.model.Order;
import com.quanlybanhang.repository.*;
import com.quanlybanhang.service.AuditLogService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** HTTP, real JWT/matrix, JPA transactions and locking; disposable H2 only. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.datasource.url=jdbc:h2:mem:order_shipments;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
    "spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop","spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
    "spring.jpa.open-in-view=false","spring.jpa.show-sql=false",
    "app.jwt.secret=shipment-test-secret-at-least-32-characters-long",
    "app.bootstrap.admin-password=ShipmentTest2026","app.bootstrap.admin-email=shipment@example.invalid",
    "app.seed-demo-users=false","app.seed-demo-data=false","app.order.reservation-check-interval-ms=3600000"
})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class OrderShipmentIntegrationTest {
    @Autowired TestRestTemplate http;
    @Autowired WarehouseRepository warehouses;
    @Autowired WarehouseInventoryRepository stocks;
    @Autowired WarehouseLotInventoryRepository lots;
    @Autowired ProductRepository products;
    @Autowired CategoryRepository categories;
    @Autowired OrderRepository orders;
    @Autowired WarehouseVoucherRepository vouchers;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired PasswordEncoder passwords;
    @Autowired RolePermissionConfig matrix;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @SpyBean AuditLogService audit;
    Warehouse warehouse,outside;Product product;String jwt,admin;long id,line;

    @BeforeEach void setup(){
        String key=UUID.randomUUID().toString().substring(0,8);
        warehouse=warehouses.save(Warehouse.builder().code("SHIP-"+key).name("Kho xuất "+key).build());
        outside=warehouses.save(Warehouse.builder().code("OUT-"+key).name("Kho ngoài").build());
        var category=categories.save(Category.builder().code("CAT-"+key).name("Hàng kiểm thử").build());
        product=products.save(Product.builder().code("SKU-"+key).name("Sản phẩm kiểm thử").category(category).unit("lon").price(BigDecimal.TEN).build());
        stocks.save(WarehouseInventory.builder().warehouse(warehouse).product(product).baseUnit("lon")
                .baseQuantity(new BigDecimal("200")).reservedQuantity(new BigDecimal("120")).build());
        matrix.updateRolePermissions(ERole.ROLE_VIEWER,Set.of(Permission.WAREHOUSE_VIEW,Permission.WAREHOUSE_EXPORT,Permission.WAREHOUSE_ADJUST,Permission.ORDER_VIEW,Permission.ORDER_CANCEL));
        var actor=users.save(User.builder().username("ship-"+key).email(key+"@example.invalid").password(passwords.encode("ShipmentTest2026"))
                .roles(Set.of(roles.findByName(ERole.ROLE_VIEWER).orElseThrow())).warehouses(Set.of(warehouse)).build());
        jwt=login(actor.getUsername());admin=login("admin");
        id=ready(warehouse,"10","10");line=get(id).path("items").get(0).path("id").asLong();
    }
    @Test void partialAndFollowupExportProtectOtherHoldsAndKeepImmutableHistory(){
        var first=confirm(body("4","Thiếu hàng chờ lượt sau"),200).path("data");stock("160","80");
        assertThat(first.path("backordered").asBoolean()).isTrue();assertThat(first.path("statusLabel").asText()).contains("Còn nợ");
        assertThat(first.path("items").get(0).path("exported").decimalValue()).isEqualByComparingTo("4");
        assertThat(first.path("items").get(0).path("remaining").decimalValue()).isEqualByComparingTo("6");
        assertThat(orders.findById(id).orElseThrow().getReservationExpiresAt()).isNull();
        assertThat(queue("BACKORDER").path("totalElements").asInt()).isEqualTo(1);
        call(HttpMethod.PUT,"/api/orders/"+id+"/cancel",jwt,Map.of("reason","Hủy"),400);
        call(HttpMethod.PUT,"/api/orders/"+id+"/status",jwt,Map.of("status","DELIVERED"),400);
        call(HttpMethod.PUT,"/api/orders/"+id+"/delivery",jwt,Map.of("items",List.of(Map.of("orderItemId",line,"deliveredQuantity",4))),400);
        var second=confirm(body("6",null),200).path("data");stock("100","20");
        assertThat(second.path("backordered").asBoolean()).isFalse();assertThat(second.path("canConfirm").asBoolean()).isFalse();
        assertThat(second.path("receipts").size()).isEqualTo(2);assertThat(queue("BACKORDER").path("totalElements").asInt()).isZero();
        assertThat(queue("HISTORY").path("totalElements").asInt()).isEqualTo(1);
        assertThat(second.path("receipts").get(0).path("items").get(0).path("quantity").decimalValue()).isEqualByComparingTo("4");
        long voucher=second.path("receipts").get(0).path("id").asLong();
        call(HttpMethod.PUT,"/api/warehouse/vouchers/"+voucher,admin,Map.of("warehouseId",warehouse.getId(),"productId",product.getId(),"quantity",1,"unit","lon"),400);
        call(HttpMethod.DELETE,"/api/warehouse/vouchers/"+voucher,admin,null,400);
        call(HttpMethod.POST,"/api/warehouse/vouchers/"+voucher+"/adjust",admin,Map.of("adjustmentReason","Test"),400);
        stock("100","20");
    }
    @Test void stockCardCountsShipmentVouchersOnceAndLinksEachOriginal(){
        confirm(body("4","Xuất thiếu"),200);confirm(body("6",null),200);
        var card=call(HttpMethod.GET,"/api/warehouse/stock-card/"+product.getId()+"?warehouseId="+warehouse.getId(),jwt,null,200).path("data");
        assertThat(card.path("totalElements").asInt()).isEqualTo(2);
        for(var e:card.path("entries"))assertThat(e.path("sourceType").asText()).isEqualTo("VOUCHER");
    }
    @Test void retriesWithSameKeyAreIdempotentAndDifferentPayloadIsRejected(){
        var body=body("4","Thiếu");confirm(body,200);confirm(body,200);stock("160","80");
        assertThat(get(id).path("receipts").size()).isEqualTo(1);
        var changed=new HashMap<>(body);changed.put("note","Đổi nội dung");confirm(changed,409);stock("160","80");
    }
    @Test void repeatedPartialExportsAlwaysAdvanceVersion(){
        confirm(body("2","Thiếu"),200);var stale=body("2","Thiếu");confirm(stale,200);
        var another=new HashMap<>(stale);another.put("requestKey",UUID.randomUUID().toString());confirm(another,409);stock("160","80");
    }
    @Test void concurrentDoubleClickWithDifferentKeysCannotDeductTwice()throws Exception{
        var a=body("4","Thiếu");var b=new HashMap<>(a);b.put("requestKey",UUID.randomUUID().toString());
        var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try{var x=pool.submit(()->{start.await();return exchange(HttpMethod.POST,path()+"/confirm",jwt,a).getStatusCode().value();});
            var y=pool.submit(()->{start.await();return exchange(HttpMethod.POST,path()+"/confirm",jwt,b).getStatusCode().value();});
            start.countDown();assertThat(List.of(x.get(30,TimeUnit.SECONDS),y.get(30,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
        }finally{pool.shutdownNow();}stock("160","80");assertThat(get(id).path("receipts").size()).isEqualTo(1);
    }
    @Test void zeroOverQuantityMissingReasonDuplicateAndForeignLinesDoNotChangeStock(){
        confirm(body("0","Hết hàng"),400);confirm(body("11",null),400);confirm(body("4",null),400);
        var duplicated=body("4","Thiếu");duplicated.put("items",List.of(input(line,"4","Thiếu"),input(line,"4","Thiếu")));confirm(duplicated,400);
        var foreign=body("4","Thiếu");foreign.put("items",List.of(input(line+99999,"4","Thiếu")));confirm(foreign,400);
        confirm(body("1.00001","Thiếu"),400);stock("200","120");assertThat(get(id).path("receipts").size()).isZero();
    }
    @Test void expiryAndWrongStateRejectWithoutReleasingOtherReservations(){
        var o=orders.findById(id).orElseThrow();o.setReservationExpiresAt(LocalDateTime.now().minusMinutes(1));orders.save(o);
        confirm(body("10",null),409);stock("200","120");
        o=orders.findById(id).orElseThrow();o.setReservationExpiresAt(null);o.setStatus("PENDING_APPROVAL");orders.save(o);
        confirm(body("10",null),409);stock("200","120");
    }
    @Test void matrixRevocationAppliesToExistingJwtAndAdminBypassesMissingGrant(){
        var b=body("10",null);
        matrix.updateRolePermissions(ERole.ROLE_VIEWER,Set.of(Permission.WAREHOUSE_VIEW));confirm(b,403);
        assertThat(get(id).path("canConfirm").asBoolean()).isFalse();
        matrix.updateRolePermissions(ERole.ROLE_VIEWER,Set.of());call(HttpMethod.GET,path(),jwt,null,403);
        matrix.updateRolePermissions(ERole.ROLE_VIEWER,Set.of(Permission.WAREHOUSE_EXPORT));confirm(b,200);
        long other=ready(outside,"2","1");stocks.save(WarehouseInventory.builder().warehouse(outside).product(product).baseUnit("lon").baseQuantity(BigDecimal.TEN).reservedQuantity(new BigDecimal("2")).build());
        matrix.updateRolePermissions(ERole.ROLE_ADMIN,Set.of());
        var d=call(HttpMethod.GET,"/api/warehouse/order-shipments/"+other,admin,null,200).path("data");
        call(HttpMethod.POST,"/api/warehouse/order-shipments/"+other+"/confirm",admin,Map.of("version",d.path("version").asLong(),"requestKey",UUID.randomUUID().toString(),"items",List.of(input(d.path("items").get(0).path("id").asLong(),"2",null))),200);
    }
    @Test void explicitWarehouseAssignmentCannotBeBypassed(){
        long foreign=ready(outside,"2","1");
        call(HttpMethod.GET,"/api/warehouse/order-shipments/"+foreign,jwt,null,403);
        call(HttpMethod.POST,"/api/warehouse/order-shipments/"+foreign+"/confirm",jwt,body("10",null),403);
        call(HttpMethod.GET,"/api/warehouse/order-shipments?warehouseId="+outside.getId(),jwt,null,403);
    }
    @Test void auditFailureRollsBackOrderStocksLotsAndVoucher(){
        product.setManageLot(true);products.save(product);lot("CURRENT",200,LocalDate.now().plusDays(30));
        doThrow(new IllegalStateException("Injected audit failure")).when(audit).logAction(eq("ORDER"),anyString(),eq("CONFIRM_SHIPMENT"),anyString(),anyString(),anyString());
        var response=exchange(HttpMethod.POST,path()+"/confirm",jwt,body("4","Thiếu"));assertThat(response.getStatusCode().isError()).isTrue();
        stock("200","120");assertThat(get(id).path("receipts").size()).isZero();assertThat(get(id).path("items").get(0).path("exported").decimalValue()).isZero();
        assertThat(lots.findByWarehouseIdAndProductIdAndLotNumber(warehouse.getId(),product.getId(),"CURRENT").orElseThrow().getBaseQuantity()).isEqualByComparingTo("200");
    }
    @Test void missingSecondSkuRollsBackEarlierLotAndZeroLinesKeepTheirShortageReason(){
        product.setManageLot(true);products.save(product);lot("CURRENT",200,LocalDate.now().plusDays(30));
        var second=products.save(Product.builder().code(product.getCode()+"-B").name("Mặt hàng thứ hai").category(product.getCategory()).unit("lon").price(BigDecimal.TEN).manageLot(true).build());
        stocks.save(WarehouseInventory.builder().warehouse(warehouse).product(second).baseUnit("lon").baseQuantity(BigDecimal.TEN).reservedQuantity(BigDecimal.TEN).build());
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status->{
            var o=orders.findById(id).orElseThrow();o.addItem(OrderItem.builder().product(second).unit("lon").quantity(BigDecimal.TEN).conversionFactor(BigDecimal.ONE).baseQuantity(BigDecimal.TEN).baseUnit("lon").build());orders.save(o);
        });
        long secondLine=get(id).path("items").get(1).path("id").asLong();
        var b=body("4","Thiếu");b.put("items",List.of(input(line,"4","Thiếu"),input(secondLine,"10",null)));
        confirm(b,409);stock("200","120");assertThat(lots.findByWarehouseIdAndProductIdAndLotNumber(warehouse.getId(),product.getId(),"CURRENT").orElseThrow().getBaseQuantity()).isEqualByComparingTo("200");
        b=body("4","Thiếu");b.put("items",List.of(input(line,"4","Thiếu"),input(secondLine,"0","Chưa có lô còn hạn")));
        var d=confirm(b,200).path("data");stock("160","80");
        assertThat(d.path("receipts").get(0).path("items").size()).isEqualTo(2);
        assertThat(d.path("receipts").get(0).path("items").get(1).path("reason").asText()).isEqualTo("Chưa có lô còn hạn");
    }
    @Test void fefoConsumesExactLotAndManualOverrideRequiresReason(){
        product.setManageLot(true);products.save(product);lot("EARLY",60,LocalDate.now().plusDays(5));lot("LATE",140,LocalDate.now().plusDays(30));
        var b=body("4","Thiếu");confirm(b,200);stock("160","80");
        assertThat(lots.findByWarehouseIdAndProductIdAndLotNumber(warehouse.getId(),product.getId(),"EARLY").orElseThrow().getBaseQuantity()).isEqualByComparingTo("20");
        var manual=body("1",null);manual.put("items",List.of(Map.of("orderItemId",line,"quantity",1,"lotNumber","LATE")));confirm(manual,400);
        manual=body("1","Chọn lô còn nguyên kiện");manual.put("items",List.of(Map.of("orderItemId",line,"quantity",1,"lotNumber","LATE","reason","Chọn lô còn nguyên kiện")));confirm(manual,200);
        assertThat(lots.findByWarehouseIdAndProductIdAndLotNumber(warehouse.getId(),product.getId(),"LATE").orElseThrow().getBaseQuantity()).isEqualByComparingTo("130");
    }
    @Test void expiredLotsAndInsufficientLotStockRollbackEverything(){
        product.setManageLot(true);products.save(product);lot("OLD",200,LocalDate.now().minusDays(1));
        confirm(body("10",null),409);stock("200","120");
        lot("NEW",5,LocalDate.now().plusDays(10));confirm(body("4","Thiếu"),409);stock("200","120");
    }
    @Test void repeatedSkuCannotFallBackToUntrackedStockAfterFirstLineEmptiesLastLegacyLot(){
        // manageLot=false but imported lot stock still exists: every line must respect it.
        lot("LEGACY",100,LocalDate.now().plusDays(30));
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status->{
            var o=orders.findById(id).orElseThrow();o.addItem(OrderItem.builder().product(product).unit("thùng").quantity(BigDecimal.ONE)
                    .conversionFactor(BigDecimal.TEN).baseQuantity(BigDecimal.TEN).baseUnit("lon").build());orders.save(o);
        });
        long secondLine=get(id).path("items").get(1).path("id").asLong();
        var b=body("10",null);b.put("items",List.of(input(line,"10",null),input(secondLine,"1",null)));
        confirm(b,409);stock("200","120");
        assertThat(lots.findByWarehouseIdAndProductIdAndLotNumber(warehouse.getId(),product.getId(),"LEGACY").orElseThrow().getBaseQuantity()).isEqualByComparingTo("100");
    }
    @Test void legacyExportedOrdersAreNotReexportedOrInventedAsBackorders(){
        var o=orders.findById(id).orElseThrow();o.setStatus("EXPORTED");o.setInventoryReserved(false);orders.save(o);
        var d=get(id);assertThat(d.path("items").get(0).path("exported").decimalValue()).isEqualByComparingTo("10");
        assertThat(d.path("canConfirm").asBoolean()).isFalse();confirm(body("10",null),409);
    }
    private void lot(String number,int q,LocalDate expiry){lots.save(WarehouseLotInventory.builder().warehouse(warehouse).product(product).lotNumber(number).baseQuantity(BigDecimal.valueOf(q)).baseUnit("lon").expiryDate(expiry).build());}
    private long ready(Warehouse w,String q,String f){
        var o=Order.builder().orderCode("DH-"+UUID.randomUUID()).warehouse(w).status("RECORDED").customerName("Khách kiểm thử").customerGroup(CustomerGroup.KHACH_LE)
                .inventoryReserved(true).reservedAt(LocalDateTime.now()).reservationExpiresAt(LocalDateTime.now().plusDays(1)).items(new ArrayList<>()).build();
        o.addItem(OrderItem.builder().product(product).unit("thùng").quantity(new BigDecimal(q)).conversionFactor(new BigDecimal(f)).baseQuantity(new BigDecimal(q).multiply(new BigDecimal(f))).baseUnit("lon").build());return orders.save(o).getId();
    }
    private String path(){return "/api/warehouse/order-shipments/"+id;}
    private JsonNode get(long n){return call(HttpMethod.GET,"/api/warehouse/order-shipments/"+n,jwt,null,200).path("data");}
    private JsonNode queue(String queue){return call(HttpMethod.GET,"/api/warehouse/order-shipments?queue="+queue+"&warehouseId="+warehouse.getId(),jwt,null,200).path("data");}
    private Map<String,Object> body(String q,String reason){var b=new HashMap<String,Object>();b.put("version",get(id).path("version").asLong());b.put("requestKey",UUID.randomUUID().toString());b.put("items",List.of(input(line,q,reason)));return b;}
    private Map<String,Object> input(long item,String q,String reason){var i=new HashMap<String,Object>();i.put("orderItemId",item);i.put("quantity",q);if(reason!=null)i.put("reason",reason);return i;}
    private JsonNode confirm(Object b,int expected){return call(HttpMethod.POST,path()+"/confirm",jwt,b,expected);}
    private void stock(String physical,String held){var s=stocks.findByWarehouseIdAndProductId(warehouse.getId(),product.getId()).orElseThrow();assertThat(s.getBaseQuantity()).isEqualByComparingTo(physical);assertThat(s.getReservedQuantity()).isEqualByComparingTo(held);}
    private String login(String user){return call(HttpMethod.POST,"/api/auth/login",null,Map.of("username",user,"password","ShipmentTest2026"),200).path("accessToken").asText();}
    private ResponseEntity<JsonNode> exchange(HttpMethod m,String p,String token,Object b){var h=new HttpHeaders();h.setContentType(MediaType.APPLICATION_JSON);if(token!=null)h.setBearerAuth(token);return http.exchange(p,m,new HttpEntity<>(b,h),JsonNode.class);}
    private JsonNode call(HttpMethod m,String p,String token,Object b,int expected){var r=exchange(m,p,token,b);assertThat(r.getStatusCode().value()).as("%s %s: %s",m,p,r.getBody()).isEqualTo(expected);return r.getBody();}
}
