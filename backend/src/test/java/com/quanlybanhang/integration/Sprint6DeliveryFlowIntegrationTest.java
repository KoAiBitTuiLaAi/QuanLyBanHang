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
    "spring.datasource.url=jdbc:h2:mem:s6_delivery_flow;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
    "spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop","spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
    "spring.jpa.open-in-view=false","spring.jpa.show-sql=false",
    "app.jwt.secret=shipment-test-secret-at-least-32-characters-long",
    "app.bootstrap.admin-password=ShipmentTest2026","app.bootstrap.admin-email=shipment@example.invalid",
    "app.seed-demo-users=false","app.seed-demo-data=false","app.order.reservation-check-interval-ms=3600000"
})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class Sprint6DeliveryFlowIntegrationTest {
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
        id=ready(warehouse,"10","10");line=get(id).path("items").get(0).path("id").asLong();prepareLots();
    }

    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired DealerRepository dealers;
    long dealerId;
    private static final String TRIPS="/api/warehouse/delivery-trips";

    private void prepareLots(){
        product.setManageLot(true); products.save(product);
        lot("ORIGINAL",200,LocalDate.now().plusDays(30));
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(tx->{
            var o=orders.findById(id).orElseThrow();
            var dealer=dealers.save(Dealer.builder().code("DL-"+UUID.randomUUID()).name("Đại lý thử nghiệm").warehouse(warehouse).active(true).build());
            dealerId=dealer.getId();o.setDealer(dealer);
            o.setTotalAmount(new BigDecimal("1000"));
            o.getItems().get(0).setTotalAmount(new BigDecimal("1000"));
            o.getItems().get(0).setPrice(new BigDecimal("100"));
        });
    }
    @Test void partialDeliveryThenFollowupExportCountsActualGoodsAndOnlyFinallyCompletesOrder(){
        long v1=export("4","Chờ hàng bổ sung");
        var eligible=call(HttpMethod.GET,TRIPS+"/eligible-orders?warehouseId="+warehouse.getId(),jwt,null,200).path("data");
        assertThat(eligible.findValuesAsText("shipmentVoucherId")).contains(""+v1);
        var t1=dispatch(v1,jwt,200).path("data"); long s1=t1.path("stops").get(0).path("id").asLong();
        assertThat(t1.path("stops").get(0).path("items").get(0).path("baseQuantity").decimalValue()).isEqualByComparingTo("40");
        deliver(t1.path("id").asLong(),s1,jwt,200);
        assertThat(orders.findById(id).orElseThrow().getStatus()).isEqualTo("SHIPPING");
        assertThat(delivered()).isEqualByComparingTo("4");
        call(HttpMethod.POST,"/api/invoices/preview",admin,Map.of("dealerId",dealerId,"orderIds",List.of(id)),400);
        stock("160","80");
        long v2=export("6",null);
        var t2=dispatch(v2,jwt,200).path("data");
        deliver(t2.path("id").asLong(),t2.path("stops").get(0).path("id").asLong(),jwt,200);
        assertThat(orders.findById(id).orElseThrow().getStatus()).isEqualTo("DELIVERED");
        assertThat(delivered()).isEqualByComparingTo("10");
        call(HttpMethod.POST,"/api/invoices/preview",admin,Map.of("dealerId",dealerId,"orderIds",List.of(id)),200);
        stock("100","20");
        deliver(t1.path("id").asLong(),s1,jwt,400);
        assertThat(delivered()).isEqualByComparingTo("10");
    }
    @Test void failedStopKeepsGoodsOnRoadUntilExplicitReturnRestoresFrozenBaseAndOriginalLot(){
        long v=export("4","Thiếu");
        var t=dispatch(v,jwt,200).path("data");long trip=t.path("id").asLong(),stop=t.path("stops").get(0).path("id").asLong();
        fail(trip,stop);stock("160","80"); lotStock("160");
        call(HttpMethod.POST,TRIPS+"/"+trip+"/stops/"+stop+"/return-to-warehouse",jwt,Map.of("returnReason","Hàng về kho nguyên lô"),200);
        stock("200","120");lotStock("200");
        assertThat(orders.findById(id).orElseThrow().getStatus()).isEqualTo("PICKING");
        assertThat(get(id).path("items").get(0).path("exported").decimalValue()).isEqualByComparingTo("0");
        call(HttpMethod.POST,TRIPS+"/"+trip+"/stops/"+stop+"/return-to-warehouse",jwt,Map.of("returnReason","Lặp"),400);
        stock("200","120");lotStock("200");
        long replacement=export("10",null);assertThat(replacement).isNotEqualTo(v);
    }
    @Test void returnAuditFailureRollsBackInventoryLotOrderAndReturnFlag(){
        var t=dispatch(export("4","Thiếu"),jwt,200).path("data");long trip=t.path("id").asLong(),stop=t.path("stops").get(0).path("id").asLong();
        fail(trip,stop);
        doThrow(new IllegalStateException("Injected audit failure")).when(audit).logAction(eq("DELIVERY_TRIP"),anyString(),eq("RETURN_TO_WAREHOUSE"),anyString(),anyString(),anyString(),anyString());
        var r=exchange(HttpMethod.POST,TRIPS+"/"+trip+"/stops/"+stop+"/return-to-warehouse",jwt,Map.of("returnReason","Về kho"));
        assertThat(r.getStatusCode().isError()).isTrue();
        stock("160","80");lotStock("160");
        var after=call(HttpMethod.GET,TRIPS+"/"+trip,jwt,null,200).path("data").path("stops").get(0);
        assertThat(after.path("returnedToWarehouse").asBoolean()).isFalse();
        assertThat(get(id).path("items").get(0).path("exported").decimalValue()).isEqualByComparingTo("4");
    }
    @Test void originalScopeStateAndUniqueVoucherClaimCannotBeBypassedByCustomStops(){
        long v=export("4","Thiếu");
        long foreign=ready(outside,"2","1");
        var b=tripBody();b.put("stops",List.of(Map.of("orderId",foreign,"deliveryAddress","X")));
        call(HttpMethod.POST,TRIPS,jwt,b,403);
        b=tripBody();b.put("stops",List.of(Map.of("orderId",id,"shipmentVoucherId",v+999999)));
        call(HttpMethod.POST,TRIPS,jwt,b,400);
        var o=orders.findById(id).orElseThrow();o.setStatus("DRAFT");orders.save(o);
        dispatch(v,jwt,400);o=orders.findById(id).orElseThrow();o.setStatus("EXPORTED");orders.save(o);
        dispatch(v,jwt,200);dispatch(v,jwt,400);
        b=tripBody();b.put("shipmentVoucherIds",List.of(v,v));call(HttpMethod.POST,TRIPS,jwt,b,400);
    }
    @Test void concurrentDispatchAndReturnAreExactlyOnce(){
        long v=export("4","Thiếu"); var pool=Executors.newFixedThreadPool(2);
        try{
            var go=new CountDownLatch(1);
            var a=pool.submit(()->{go.await();return dispatch(v,jwt,0);});
            var b=pool.submit(()->{go.await();return dispatch(v,jwt,0);});go.countDown();
            var ra=a.get(30,TimeUnit.SECONDS);var rb=b.get(30,TimeUnit.SECONDS);
            assertThat(List.of(ra.path("_status").asInt(),rb.path("_status").asInt())).containsExactlyInAnyOrder(200,400);
            var t=(ra.path("_status").asInt()==200?ra:rb).path("data");
            long trip=t.path("id").asLong(),stop=t.path("stops").get(0).path("id").asLong();fail(trip,stop);
            var start=new CountDownLatch(1);String path=TRIPS+"/"+trip+"/stops/"+stop+"/return-to-warehouse";
            var x=pool.submit(()->{start.await();return exchange(HttpMethod.POST,path,jwt,Map.of("returnReason","Về kho")).getStatusCode().value();});
            var y=pool.submit(()->{start.await();return exchange(HttpMethod.POST,path,jwt,Map.of("returnReason","Về kho")).getStatusCode().value();});
            start.countDown();assertThat(List.of(x.get(30,TimeUnit.SECONDS),y.get(30,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,400);
            stock("200","120");lotStock("200");
        }catch(Exception e){throw new AssertionError(e);}finally{pool.shutdownNow();}
    }
    @Test void existingJwtImmediatelyLosesWriteAndReadWhileAdminStillAllowed(){
        long v=export("10",null);var t=dispatch(v,jwt,200).path("data");long trip=t.path("id").asLong(),stop=t.path("stops").get(0).path("id").asLong();
        matrix.updateRolePermissions(ERole.ROLE_VIEWER,Set.of(Permission.WAREHOUSE_VIEW));
        deliver(trip,stop,jwt,403);
        call(HttpMethod.PUT,TRIPS+"/"+trip+"/stops/"+stop+"/status",jwt,Map.of("status","FAILED","failedReason","Không có người nhận"),403);
        var d=call(HttpMethod.GET,TRIPS+"/"+trip,jwt,null,200).path("data");
        assertThat(d.path("stops").get(0).path("canConfirm").asBoolean()).isFalse();
        matrix.updateRolePermissions(ERole.ROLE_VIEWER,Set.of());
        call(HttpMethod.GET,TRIPS+"/"+trip,jwt,null,403);
        matrix.updateRolePermissions(ERole.ROLE_ADMIN,Set.of());deliver(trip,stop,admin,200);
    }
    @Test void concurrentConfirmationsAcrossTwoTripsAccumulateWithoutLostUpdates(){
        long a=export("4","Thiếu"),b=export("6",null);
        var first=dispatch(a,jwt,200).path("data");var second=dispatch(b,jwt,200).path("data");
        var pool=Executors.newFixedThreadPool(2);var go=new CountDownLatch(1);
        try{
            var x=pool.submit(()->{go.await();deliver(first.path("id").asLong(),first.path("stops").get(0).path("id").asLong(),jwt,200);return true;});
            var y=pool.submit(()->{go.await();deliver(second.path("id").asLong(),second.path("stops").get(0).path("id").asLong(),jwt,200);return true;});
            go.countDown();assertThat(x.get(30,TimeUnit.SECONDS)).isTrue();assertThat(y.get(30,TimeUnit.SECONDS)).isTrue();
        }catch(Exception e){throw new AssertionError(e);}finally{pool.shutdownNow();}
        assertThat(delivered()).isEqualByComparingTo("10");assertThat(orders.findById(id).orElseThrow().getStatus()).isEqualTo("DELIVERED");
    }
    @Test void returningOneTripWhileAnotherDeliversSameOrderDoesNotDeadlockOrLoseQuantities(){
        long a=export("4","Thiếu"),b=export("6",null);
        var first=dispatch(a,jwt,200).path("data");var second=dispatch(b,jwt,200).path("data");
        long trip=first.path("id").asLong(),stop=first.path("stops").get(0).path("id").asLong();fail(trip,stop);
        var pool=Executors.newFixedThreadPool(2);var go=new CountDownLatch(1);
        try{
            var x=pool.submit(()->{go.await();return call(HttpMethod.POST,TRIPS+"/"+trip+"/stops/"+stop+"/return-to-warehouse",jwt,Map.of("returnReason","Về kho nguyên lô"),200);});
            var y=pool.submit(()->{go.await();deliver(second.path("id").asLong(),second.path("stops").get(0).path("id").asLong(),jwt,200);return true;});
            go.countDown();assertThat(x.get(30,TimeUnit.SECONDS)).isNotNull();assertThat(y.get(30,TimeUnit.SECONDS)).isTrue();
        }catch(Exception e){throw new AssertionError(e);}finally{pool.shutdownNow();}
        stock("140","60");lotStock("140");assertThat(delivered()).isEqualByComparingTo("6");
        assertThat(get(id).path("items").get(0).path("exported").decimalValue()).isEqualByComparingTo("6");
        assertThat(orders.findById(id).orElseThrow().getBackordered()).isTrue();
    }
    @Test void cancelledTripRetainsLiabilityUntilExplicitReceiptThenCanReexport(){
        var t=dispatch(export("10",null),jwt,200).path("data");long trip=t.path("id").asLong(),stop=t.path("stops").get(0).path("id").asLong();
        call(HttpMethod.POST,TRIPS+"/"+trip+"/cancel",jwt,Map.of("cancelReason","Xe hỏng, trở về kho"),200);
        stock("100","20");lotStock("100");
        var result=call(HttpMethod.GET,TRIPS+"/"+trip,jwt,null,200).path("data").path("stops").get(0);
        assertThat(result.path("canReturn").asBoolean()).isTrue();
        call(HttpMethod.POST,TRIPS+"/"+trip+"/stops/"+stop+"/return-to-warehouse",jwt,Map.of("returnReason","Đã nhận đủ hàng xe hỏng"),200);
        stock("200","120");lotStock("200");export("10",null);
    }
    @Test void confirmationAuditFailureRollsBackProofActualGoodsAndOrderStatus(){
        var t=dispatch(export("10",null),jwt,200).path("data");long trip=t.path("id").asLong(),stop=t.path("stops").get(0).path("id").asLong();
        doThrow(new IllegalStateException("Injected audit failure")).when(audit).logAction(eq("DELIVERY_TRIP"),anyString(),eq("STOP_DELIVERED"),any(),any(),any(),any());
        deliver(trip,stop,jwt,400);
        var result=call(HttpMethod.GET,TRIPS+"/"+trip,jwt,null,200).path("data").path("stops").get(0);
        assertThat(result.path("hasSignedProof").asBoolean()).isFalse();assertThat(result.path("status").asText()).isEqualTo("PENDING");
        assertThat(delivered()).isNull();assertThat(orders.findById(id).orElseThrow().getStatus()).isEqualTo("SHIPPING");stock("100","20");lotStock("100");
    }
    private BigDecimal delivered(){return jdbc.queryForObject("select delivered_quantity from order_items where id=?",BigDecimal.class,line);}
    private void lotStock(String q){assertThat(lots.findByWarehouseIdAndProductIdAndLotNumber(warehouse.getId(),product.getId(),"ORIGINAL").orElseThrow().getBaseQuantity()).isEqualByComparingTo(q);}
    private long export(String q,String reason){var d=confirm(body(q,reason),200).path("data");return d.path("receipts").get(d.path("receipts").size()-1).path("id").asLong();}
    private Map<String,Object> tripBody(){var b=new HashMap<String,Object>();b.put("warehouseId",warehouse.getId());b.put("driverName","Tài xế thử nghiệm");b.put("vehiclePlate","TEST-123");b.put("departureTime",LocalDateTime.now().minusMinutes(2).toString());b.put("expectedCompletionTime",LocalDateTime.now().plusHours(4).toString());return b;}
    private JsonNode dispatch(long voucher,String token,int expected){var b=tripBody();b.put("shipmentVoucherIds",List.of(voucher));var r=exchange(HttpMethod.POST,TRIPS,token,b);if(expected>0)assertThat(r.getStatusCode().value()).as("%s",r.getBody()).isEqualTo(expected);var n=(com.fasterxml.jackson.databind.node.ObjectNode)r.getBody();n.put("_status",r.getStatusCode().value());return n;}
    private void fail(long trip,long stop){call(HttpMethod.PUT,TRIPS+"/"+trip+"/stops/"+stop+"/status",jwt,Map.of("status","FAILED","failureReasonCode","ABSENT","failedReason","Liên hệ lại ngày mai"),200);}
    private void deliver(long trip,long stop,String token,int expected){
        var form=new org.springframework.util.LinkedMultiValueMap<String,Object>();
        form.add("receiverName","Người nhận thử nghiệm");form.add("deliveredAt",LocalDateTime.now().minusSeconds(1).toString());
        var image=new org.springframework.core.io.ByteArrayResource(Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=")){@Override public String getFilename(){return "signed.png";}};
        form.add("proof",image);var headers=new HttpHeaders();headers.setBearerAuth(token);headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        var r=http.exchange(TRIPS+"/"+trip+"/stops/"+stop+"/confirm-delivery",HttpMethod.POST,new HttpEntity<>(form,headers),JsonNode.class);
        assertThat(r.getStatusCode().value()).as("%s",r.getBody()).isEqualTo(expected);
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


