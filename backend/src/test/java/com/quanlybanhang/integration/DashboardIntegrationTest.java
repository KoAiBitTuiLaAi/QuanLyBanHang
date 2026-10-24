package com.quanlybanhang.integration;

import com.fasterxml.jackson.databind.*;
import com.quanlybanhang.config.RolePermissionConfig;
import com.quanlybanhang.model.*;
import com.quanlybanhang.repository.*;
import com.quanlybanhang.security.JwtUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@SpringBootTest(properties={
 "spring.datasource.url=jdbc:h2:mem:dashboard_test;MODE=MySQL;DB_CLOSE_DELAY=-1",
 "spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=",
 "spring.jpa.hibernate.ddl-auto=create-drop","spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
 "spring.jpa.show-sql=false","app.jwt.secret=dashboard-integration-test-secret-at-least-32-characters",
 "app.bootstrap.admin-password=DashboardTest2026!","app.bootstrap.admin-email=dashboard@example.invalid",
 "app.seed-demo-users=false","app.seed-demo-data=false"})
@AutoConfigureMockMvc @Transactional @DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class DashboardIntegrationTest {
 @Autowired MockMvc mvc;
 @Autowired ObjectMapper json;
 @Autowired JdbcTemplate jdbc;
 @Autowired UserRepository users;
 @Autowired RoleRepository roles;
 @Autowired DealerRepository dealers;
 @Autowired OrderRepository orders;
 @Autowired DebtPaymentRepository payments;
 @Autowired TerritoryRepository territories;
 @Autowired WarehouseRepository warehouses;
 @Autowired ProductRepository products;
 @Autowired CategoryRepository categories;
 @Autowired WarehouseInventoryRepository inventory;
 @Autowired StockMinimumRepository minimums;
 @Autowired RolePermissionConfig matrix;
 @Autowired JwtUtils jwt;
 @MockBean(name="dashboardClock") Clock clock;
 String token,admin;
 final LocalDate today=LocalDate.of(2026,3,15);
 final ZoneId zone=ZoneId.of("Asia/Ho_Chi_Minh");
 @BeforeEach void setup() {
  when(clock.getZone()).thenReturn(zone);
  when(clock.instant()).thenReturn(today.atTime(12,0).atZone(zone).toInstant());
  matrix.updateRolePermissions(ERole.ROLE_SALES_MANAGER,Set.of(Permission.DASHBOARD_VIEW));
  String key=UUID.randomUUID().toString();
  User manager=users.saveAndFlush(User.builder().username(key).email(key+"@example.invalid").password("unused")
   .roles(Set.of(roles.findByName(ERole.ROLE_SALES_MANAGER).orElseThrow())).build());
  token=jwt.generateAccessToken(manager.getUsername(),0L); admin=jwt.generateAccessToken("admin",0L);
 }
 JsonNode dashboard() throws Exception{return request("",token,200);}
 JsonNode request(String suffix,String auth,int status) throws Exception {
  var req=get("/api/reports/dashboard"+suffix);
  if(auth!=null)req.header("Authorization","Bearer "+auth);
  var response=mvc.perform(req).andReturn().getResponse();
  assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
  return json.readTree(response.getContentAsByteArray()).path("data");
 }
 Dealer dealer(String name,int days,Territory area) {
  return dealers.saveAndFlush(Dealer.builder().code(UUID.randomUUID().toString()).name(name).maxDebtDays(days)
   .debtLimit(new BigDecimal("1000000")).territory(area).build());
 }
 void order(Dealer dealer,String status,String amount,LocalDate day) {
  orders.saveAndFlush(Order.builder().orderCode(UUID.randomUUID().toString()).dealer(dealer).status(status)
   .totalAmount(new BigDecimal(amount)).recordedAt(day.atTime(10,0)).createdAt(day.atTime(10,0)).build());
 }
 void pay(Dealer dealer,String amount) {
  payments.saveAndFlush(DebtPayment.builder().paymentCode(UUID.randomUUID().toString()).dealer(dealer).amount(new BigDecimal(amount)).paymentDate(today).build());
 }
 @Test void emptyDashboardIsZeroAndFillsMonth() throws Exception {
  var data=dashboard();
  assertThat(data.path("metrics").path("monthSales").decimalValue()).isEqualByComparingTo("0");
  assertThat(data.path("metrics").path("overdueDebt").decimalValue()).isEqualByComparingTo("0");
  assertThat(data.path("dailySales").size()).isEqualTo(31);
  assertThat(data.path("territorySales").size()).isZero();
  assertThat(data.path("overdueDealers").size()).isZero();
 }
 @Test void salesAndBacklogUseCorrectStatusesAndBoundaries() throws Exception {
  var area=territories.saveAndFlush(Territory.builder().code("A").name("Miền A").build());
  var a=dealer("A",0,area); var b=dealer("B",0,null);
  order(a,"DELIVERED","100",today); order(a,"RECORDED","20",today.minusDays(1));
  order(b,"APPROVED","30",today.withDayOfMonth(1));
  order(b,"CANCELLED","999",today); order(b,"REJECTED","999",today);
  order(b,"DRAFT","999",today); order(b,"PENDING_APPROVAL","999",today.minusMonths(1));
  order(b,"PICKING","40",today.minusMonths(1)); order(b,"PROCESSING","50",today.plusDays(1));
  var data=dashboard(); var metrics=data.path("metrics");
  assertThat(metrics.path("todaySales").decimalValue()).isEqualByComparingTo("100");
  assertThat(metrics.path("monthSales").decimalValue()).isEqualByComparingTo("150");
  assertThat(metrics.path("pendingApprovalOrders").asLong()).isEqualTo(1);
  assertThat(metrics.path("pendingExportOrders").asLong()).isEqualTo(4);
  assertThat(data.path("dailySales").get(14).path("sales").decimalValue()).isEqualByComparingTo("100");
  assertThat(data.path("dailySales").get(1).path("sales").decimalValue()).isEqualByComparingTo("0");
  assertThat(data.path("territorySales").get(0).path("sales").decimalValue()).isEqualByComparingTo("120");
  assertThat(data.path("territorySales").get(1).path("territoryId").isNull()).isTrue();
  assertThat(data.path("territorySales").get(1).path("sales").decimalValue()).isEqualByComparingTo("30");
 }
 @Test void overdueUsesFifoPaymentsNotStoredFlagsAndSortsByAmount() throws Exception {
  var a=dealer("A",10,null); var b=dealer("B",10,null); var c=dealer("C",0,null);
  order(a,"RECORDED","100",today.minusDays(20)); order(a,"DELIVERED","200",today.minusDays(2)); pay(a,"60");
  order(b,"CLOSED","70",today.minusDays(30));
  order(c,"RECORDED","500",today.minusDays(100));
  var fullyPaid=dealer("Paid",5,null); order(fullyPaid,"RECORDED","999",today.minusDays(30)); pay(fullyPaid,"1000");
  var dueToday=dealer("Due today",10,null); order(dueToday,"RECORDED","500",today.minusDays(10));
  var data=request("?overdueLimit=1",token,200);
  assertThat(data.path("metrics").path("overdueDebt").decimalValue()).isEqualByComparingTo("110");
  assertThat(data.path("totalOverdueDealers").asLong()).isEqualTo(2);
  assertThat(data.path("overdueDealers").size()).isEqualTo(1);
  assertThat(data.path("overdueDealers").get(0).path("dealerName").asText()).isEqualTo("B");
  var second=request("/overdue-dealers?page=1&size=1",token,200);
  assertThat(second.path("totalElements").asLong()).isEqualTo(2);
  assertThat(second.path("content").get(0).path("currentDebt").decimalValue()).isEqualByComparingTo("240");
  assertThat(second.path("content").get(0).path("overdueAmount").decimalValue()).isEqualByComparingTo("40");
  assertThat(request("/overdue-dealers?page=99&size=1",token,200).path("totalElements").asLong()).isEqualTo(2);
 }
 @Test void stockCountsDistinctSkuAndUsesAvailableUnitsAndActiveSettings() throws Exception {
  var cat=categories.saveAndFlush(Category.builder().code("CAT").name("Category").build());
  var w1=warehouses.saveAndFlush(Warehouse.builder().code("W1").name("Warehouse 1").build());
  var w2=warehouses.saveAndFlush(Warehouse.builder().code("W2").name("Warehouse 2").build());
  var p=products.saveAndFlush(Product.builder().code("SKU").name("SKU").category(cat).unit("lon").build());
  for(var w:List.of(w1,w2))minimums.saveAndFlush(StockMinimum.builder().warehouse(w).product(p).minimumQuantity(new BigDecimal("5"))
   .baseUnit("lon").updatedBy("admin").updatedAt(LocalDateTime.now()).build());
  inventory.saveAndFlush(WarehouseInventory.builder().warehouse(w1).product(p).baseQuantity(new BigDecimal("10"))
   .reservedQuantity(new BigDecimal("7")).baseUnit("lon").build());
  assertThat(dashboard().path("metrics").path("belowMinimumSkus").asLong()).isEqualTo(1);
  p.setActive(false); products.saveAndFlush(p);
  assertThat(dashboard().path("metrics").path("belowMinimumSkus").asLong()).isZero();
 }
 @Test void stockIgnoresUnconfiguredZeroEqualAndMismatchedMinimums() throws Exception {
  var cat=categories.saveAndFlush(Category.builder().code("EDGE-CAT").name("Category").build());
  var warehouse=warehouses.saveAndFlush(Warehouse.builder().code("EDGE-WH").name("Warehouse").build());
  for(int i=0;i<4;i++) {
   var p=products.saveAndFlush(Product.builder().code("EDGE-"+i).name("Product").category(cat).unit("lon").build());
   if(i<3)minimums.saveAndFlush(StockMinimum.builder().warehouse(warehouse).product(p).minimumQuantity(new BigDecimal(i==1?"0":"5"))
    .baseUnit(i==2?"thùng":"lon").updatedBy("admin").updatedAt(LocalDateTime.now()).build());
   inventory.saveAndFlush(WarehouseInventory.builder().warehouse(warehouse).product(p).baseQuantity(new BigDecimal(i==0?"5":"0"))
    .baseUnit("lon").build());
  }
  assertThat(dashboard().path("metrics").path("belowMinimumSkus").asLong()).isZero();
 }
 @Test void permissionGrantRevocationOnSameJwtAndValidation() throws Exception {
  request("",null,401);
  matrix.updateRolePermissions(ERole.ROLE_SALES_MANAGER,Set.of(Permission.REPORT_SALES,Permission.REPORT_FINANCE,Permission.REPORT_INVENTORY));
  request("",token,403); request("/overdue-dealers",token,403);
  matrix.updateRolePermissions(ERole.ROLE_SALES_MANAGER,Set.of(Permission.DASHBOARD_VIEW));
  request("",token,200); request("/overdue-dealers",token,200);
  matrix.updateRolePermissions(ERole.ROLE_SALES_MANAGER,Set.of());
  request("",token,403); request("",admin,200);
  request("?overdueLimit=abc",admin,400); request("?overdueLimit=0",admin,400); request("/overdue-dealers?page=-1",admin,400); request("/overdue-dealers?size=101",admin,400);
 }
 @Test void timezoneAndLeapMonthBoundaries() throws Exception {
  when(clock.instant()).thenReturn(Instant.parse("2024-02-29T17:30:00Z"));
  var data=dashboard(); assertThat(data.path("date").asText()).isEqualTo("2024-03-01");
  when(clock.instant()).thenReturn(Instant.parse("2024-02-28T17:30:00Z"));
  assertThat(dashboard().path("dailySales").size()).isEqualTo(29);
 }
 @Test void fullDashboardUnderTwoSecondsOnRepresentativeDataset() throws Exception {
  var batches=new ArrayList<Object[]>();
  for(int i=0;i<1000;i++)batches.add(new Object[]{"PERF-"+i,"Dealer "+i,true,false,false,1000000,10,false});
  jdbc.batchUpdate("insert into dealers(code,name,active,locked,handover_required,debt_limit,max_debt_days,debt_flagged) values(?,?,?,?,?,?,?,?)",batches);
  var ids=jdbc.queryForList("select id from dealers where code like 'PERF-%' order by id",Long.class);
  batches.clear();
  for(int i=0;i<10000;i++)batches.add(new Object[]{"PERF-O-"+i,ids.get(i%1000),i%5==0?"PENDING_APPROVAL":"DELIVERED",100,
   today.minusDays(i%30).atTime(10,0),today.minusDays(i%30).atTime(10,0),0,false,false});
  jdbc.batchUpdate("insert into orders(order_code,dealer_id,status,total_amount,recorded_at,created_at,version,inventory_reserved,requires_approval) values(?,?,?,?,?,?,?,?,?)",batches);
  var cat=categories.saveAndFlush(Category.builder().code("PERF-CAT").name("Performance").build());
  var warehouse=warehouses.saveAndFlush(Warehouse.builder().code("PERF-WH").name("Performance").build());
  batches.clear();
  for(int i=0;i<1000;i++)batches.add(new Object[]{"PERF-P-"+i,"Product "+i,cat.getId(),true,false,false,"lon"});
  jdbc.batchUpdate("insert into products(code,name,category_id,active,has_transactions,manage_lot,unit) values(?,?,?,?,?,?,?)",batches);
  var productIds=jdbc.queryForList("select id from products where code like 'PERF-P-%'",Long.class); batches.clear();
  for(Long id:productIds)batches.add(new Object[]{warehouse.getId(),id,5,"lon","admin",LocalDateTime.now()});
  jdbc.batchUpdate("insert into warehouse_stock_minimums(warehouse_id,product_id,minimum_quantity,base_unit,updated_by,updated_at) values(?,?,?,?,?,?)",batches);
  long maximum=0;
  for(int i=0;i<3;i++) {
   long start=System.nanoTime(); var data=dashboard(); long elapsed=(System.nanoTime()-start)/1_000_000;
   maximum=Math.max(maximum,elapsed);
   assertThat(data.path("metrics").path("pendingApprovalOrders").asLong()).isEqualTo(2000);
   assertThat(data.path("metrics").path("belowMinimumSkus").asLong()).isEqualTo(1000);
   assertThat(data.path("metrics").path("overdueDebt").decimalValue()).isPositive();
   assertThat(elapsed).as("Complete HTTP dashboard request %s (ms)",i+1).isLessThan(2000);
  }
  System.out.println("S8-05 dashboard max HTTP latency: "+maximum+" ms; 10000 orders, 1000 dealers, 1000 SKU minimums; 3 requests including first");
 }
}