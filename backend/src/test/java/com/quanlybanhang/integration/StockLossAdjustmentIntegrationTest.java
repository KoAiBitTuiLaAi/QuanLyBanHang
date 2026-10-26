package com.quanlybanhang.integration;

import com.fasterxml.jackson.databind.*;
import com.quanlybanhang.config.RolePermissionConfig;
import com.quanlybanhang.model.*;
import com.quanlybanhang.repository.*;
import com.quanlybanhang.security.JwtUtils;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(properties={
 "spring.datasource.url=jdbc:h2:mem:stock_loss_s803;MODE=MySQL;DB_CLOSE_DELAY=-1",
 "spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=",
 "spring.jpa.hibernate.ddl-auto=create-drop","spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
 "spring.jpa.show-sql=false","app.jwt.secret=stock-loss-test-key-at-least-thirty-two-characters",
 "app.bootstrap.admin-password=LossTest2026!","app.bootstrap.admin-email=loss@example.invalid",
 "app.seed-demo-users=false","app.seed-demo-data=false","app.payment-reminder.scheduler.enabled=false"})
@AutoConfigureMockMvc @DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class StockLossAdjustmentIntegrationTest {
 @Autowired MockMvc mvc;
 @Autowired ObjectMapper json;
 @Autowired JdbcTemplate jdbc;
 @Autowired JwtUtils jwt;
 @Autowired RolePermissionConfig matrix;
 @Autowired UserRepository users;
 @Autowired RoleRepository roles;
 @Autowired WarehouseRepository warehouses;
 @Autowired ProductRepository products;
 @Autowired CategoryRepository categories;
 @Autowired WarehouseInventoryRepository inventories;
 @Autowired WarehouseLotInventoryRepository lots;
 Warehouse warehouse; Product product; String admin,manager;
 @BeforeEach void setup() {
  String key=UUID.randomUUID().toString().substring(0,8);
  matrix.updateRolePermissions(ERole.ROLE_WAREHOUSE_MANAGER,Set.of(Permission.WAREHOUSE_VIEW,Permission.WAREHOUSE_APPROVE));
  warehouse=warehouses.save(Warehouse.builder().code(key).name("Kho kiểm thử").active(true).build());
  var user=users.save(User.builder().username(key).email(key+"@example.invalid").password("unused")
   .active(true).roles(Set.of(roles.findByName(ERole.ROLE_WAREHOUSE_MANAGER).orElseThrow())).warehouses(Set.of(warehouse)).build());
  admin=jwt.generateAccessToken("admin",0);manager=jwt.generateAccessToken(user.getUsername(),user.getTokenVersion());
  var category=categories.save(Category.builder().code(key).name("Nhóm hàng").build());
  product=products.save(Product.builder().code(key).name("Hàng quản lý lô").unit("cái").category(category).manageLot(true).active(true).build());
  inventories.save(WarehouseInventory.builder().warehouse(warehouse).product(product).baseUnit("cái")
   .baseQuantity(new BigDecimal("10")).reservedQuantity(new BigDecimal("4")).build());
  lots.save(WarehouseLotInventory.builder().warehouse(warehouse).product(product).baseUnit("cái").lotNumber("LOT-1")
   .baseQuantity(new BigDecimal("10")).expiryDate(java.time.LocalDate.now().plusDays(30)).build());
 }
 JsonNode postJson(String path,String token,Object body,int expected) throws Exception {
  var req=post(path).header("Authorization","Bearer "+token);
  if(body!=null)req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body));
  var response=mvc.perform(req).andReturn().getResponse();
  assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(expected);
  return json.readTree(response.getContentAsByteArray()).path("data");
 }
 Map<String,Object> row(Long id,String lot,String qty) {
  var result=new HashMap<String,Object>(Map.of("productId",id,"quantity",qty,"unit","cái","lossValue","10","price","5"));
  if(lot!=null)result.put("lotNumber",lot);return result;
 }
 Map<String,Object> body(List<?> rows) {return Map.of("warehouseId",warehouse.getId(),"adjustmentReason","[DAMAGED] Hàng hỏng kiểm thử","items",rows);}
 long draft(List<?> rows) throws Exception {return postJson("/api/warehouse/adjustment-vouchers",admin,body(rows),200).path("id").asLong();}
 String approve(long id) {return "/api/warehouse/adjustment-vouchers/"+id+"/approve";}
 @Test void managedLotIsRequiredAndMustBelongToTheWarehouse() throws Exception {
  postJson("/api/warehouse/adjustment-vouchers",admin,body(List.of(row(product.getId(),null,"2"))),400);
  postJson("/api/warehouse/adjustment-vouchers",admin,body(List.of(row(product.getId(),"OTHER","2"))),400);
  assertThat(jdbc.queryForObject("select base_quantity from warehouse_inventories where warehouse_id=?",BigDecimal.class,warehouse.getId())).isEqualByComparingTo("10");
 }
 @Test void distinctApproverPostsBothLotAndAggregateStockAndCannotPostTwice() throws Exception {
  long id=draft(List.of(row(product.getId(),"LOT-1","2")));
  postJson(approve(id),admin,null,403);
  postJson(approve(id),manager,null,200);postJson(approve(id),manager,null,400);
  assertThat(jdbc.queryForObject("select base_quantity from warehouse_inventories where warehouse_id=?",BigDecimal.class,warehouse.getId())).isEqualByComparingTo("8");
  assertThat(jdbc.queryForObject("select base_quantity from warehouse_lot_inventories where warehouse_id=?",BigDecimal.class,warehouse.getId())).isEqualByComparingTo("8");
  var response=mvc.perform(get("/api/warehouse/stock-card/"+product.getId()).header("Authorization","Bearer "+admin)
   .param("warehouseId",warehouse.getId().toString())).andReturn().getResponse();
  assertThat(response.getStatus()).isEqualTo(200);
  assertThat(response.getContentAsString()).contains("ADJUSTMENT");
 }
 @Test void revocationOfApproveTakesEffectOnExistingJwtAndGrantRestoresIt() throws Exception {
  long id=draft(List.of(row(product.getId(),"LOT-1","2")));
  matrix.updateRolePermissions(ERole.ROLE_WAREHOUSE_MANAGER,Set.of(Permission.WAREHOUSE_VIEW));
  postJson(approve(id),manager,null,403);
  matrix.updateRolePermissions(ERole.ROLE_WAREHOUSE_MANAGER,Set.of(Permission.WAREHOUSE_VIEW,Permission.WAREHOUSE_APPROVE));
  postJson(approve(id),manager,null,200);
 }
 @Test void insufficientSecondLineRollsBackEarlierLotAndInventoryChanges() throws Exception {
  var other=products.save(Product.builder().code(UUID.randomUUID().toString()).name("Hàng khác").unit("cái").category(product.getCategory()).active(true).build());
  inventories.save(WarehouseInventory.builder().warehouse(warehouse).product(other).baseUnit("cái").baseQuantity(new BigDecimal("5")).reservedQuantity(new BigDecimal("4")).build());
  long id=draft(List.of(row(product.getId(),"LOT-1","2"),row(other.getId(),null,"2")));
  postJson(approve(id),manager,null,400);
  assertThat(jdbc.queryForObject("select base_quantity from warehouse_inventories where warehouse_id=? and product_id=?",BigDecimal.class,warehouse.getId(),product.getId())).isEqualByComparingTo("10");
  assertThat(jdbc.queryForObject("select base_quantity from warehouse_lot_inventories where warehouse_id=?",BigDecimal.class,warehouse.getId())).isEqualByComparingTo("10");
  assertThat(jdbc.queryForObject("select status from warehouse_vouchers where id=?",String.class,id)).isEqualTo("PENDING_APPROVAL");
 }
}
