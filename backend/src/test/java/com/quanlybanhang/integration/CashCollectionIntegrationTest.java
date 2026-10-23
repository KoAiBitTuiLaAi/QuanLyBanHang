package com.quanlybanhang.integration;

import com.fasterxml.jackson.databind.*;
import com.quanlybanhang.config.RolePermissionConfig;
import com.quanlybanhang.model.*;
import com.quanlybanhang.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(properties={
 "spring.datasource.url=jdbc:h2:mem:cash_collection_test;MODE=MySQL;DB_CLOSE_DELAY=-1",
 "spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=",
 "spring.jpa.hibernate.ddl-auto=create-drop","spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
 "spring.jpa.show-sql=false","app.jwt.secret=cash-collection-test-secret-at-least-32-characters",
 "app.bootstrap.admin-password=CashTest2026!","app.bootstrap.admin-email=cash@example.invalid",
 "app.seed-demo-users=false","app.seed-demo-data=false"})
@AutoConfigureMockMvc @DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class CashCollectionIntegrationTest {
 @Autowired MockMvc mvc;
 @Autowired ObjectMapper json;
 @Autowired UserRepository users;
 @Autowired RoleRepository roles;
 @Autowired DealerRepository dealers;
 @Autowired TerritoryRepository territories;
 @Autowired CashCollectionRepository collections;
 @Autowired RolePermissionRepository storedPermissions;
 @Autowired RolePermissionConfig matrix;
 @Autowired PasswordEncoder passwords;
 User sales, accountant, other;
 Dealer dealer;
 String salesToken, accountantToken, otherToken, adminToken;
 LocalDate day=LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));
 byte[] image;
 @BeforeEach void setup() throws Exception {
  matrix.updateRolePermissions(ERole.ROLE_SALES,Set.of(Permission.COLLECTION_CREATE));
  matrix.updateRolePermissions(ERole.ROLE_ACCOUNTANT,Set.of(Permission.COLLECTION_CONFIRM,Permission.COLLECTION_RECONCILE));
  sales=account(ERole.ROLE_SALES); accountant=account(ERole.ROLE_ACCOUNTANT); other=account(ERole.ROLE_SALES);
  var territory=territories.save(Territory.builder().code(UUID.randomUUID().toString().substring(0,12)).name("Tuyến A").build());
  dealer=dealers.save(Dealer.builder().code(UUID.randomUUID().toString()).name("Cửa hàng A").assignedUser(sales).territory(territory).build());
  salesToken=login(sales.getUsername()); accountantToken=login(accountant.getUsername()); otherToken=login(other.getUsername()); adminToken=login("admin");
  var out=new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"png",out); image=out.toByteArray();
 }
 User account(ERole role) {
  String key=UUID.randomUUID().toString();
  return users.save(User.builder().username(key).email(key+"@example.invalid").password(passwords.encode("CashTest2026!"))
   .fullName(key).roles(Set.of(roles.findByName(role).orElseThrow())).build());
 }
 String login(String name) throws Exception {
  return call(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(Map.of("username",name,"password","CashTest2026!"))),null,200).path("accessToken").asText();
 }
 JsonNode call(MockHttpServletRequestBuilder req,String token,int status) throws Exception {
  if(token!=null) req.header("Authorization","Bearer "+token);
  var response=mvc.perform(req).andReturn().getResponse();
  assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
  return response.getContentAsByteArray().length==0?json.nullNode():json.readTree(response.getContentAsByteArray());
 }
 JsonNode create(String token,String amount,LocalDate date,byte[] bytes,int status) throws Exception {
  return call(multipart("/api/finance/cash-collections")
   .file(new MockMultipartFile("data","","application/json",json.writeValueAsBytes(Map.of("dealerId",dealer.getId(),"amount",amount,"collectionDate",date.toString(),"note","Thu tại cửa hàng"))))
   .file(new MockMultipartFile("receipt","bill.png","image/png",bytes)),token,status).path("data");
 }
 MockHttpServletRequestBuilder query(String suffix) {
  return get("/api/finance/cash-collections"+suffix).param("from",day.toString()).param("to",day.toString()).param("employeeId",sales.getId().toString());
 }
 @Test void fullLifecycleAndReconciliation() throws Exception {
  long first=create(salesToken,"100.50",day,image,201).path("id").asLong();
  long second=create(salesToken,"50.25",day,image,201).path("id").asLong();
  assertThat(call(get("/api/finance/cash-collections/"+first),salesToken,200).path("data").path("status").asText()).isEqualTo("PENDING");
  call(post("/api/finance/cash-collections/"+first+"/confirm"),salesToken,403);
  var confirmed=call(post("/api/finance/cash-collections/"+first+"/confirm"),accountantToken,200).path("data");
  assertThat(confirmed.path("status").asText()).isEqualTo("RECEIVED");
  assertThat(confirmed.path("receivedBy").asText()).isEqualTo(accountant.getUsername());
  assertThat(confirmed.path("receivedAt").isNull()).isFalse();
  call(post("/api/finance/cash-collections/"+first+"/confirm"),accountantToken,409);
  var report=call(query("/reconciliation"),accountantToken,200).path("data").get(0);
  assertThat(report.path("collected").decimalValue()).isEqualByComparingTo("150.75");
  assertThat(report.path("received").decimalValue()).isEqualByComparingTo("100.50");
  assertThat(report.path("pending").decimalValue()).isEqualByComparingTo("50.25");
  assertThat(collections.findById(second).orElseThrow().getStatus()).isEqualTo(CashCollection.Status.PENDING);
  var receipt=mvc.perform(get("/api/finance/cash-collections/"+first+"/receipt").header("Authorization","Bearer "+salesToken)).andReturn().getResponse();
  assertThat(receipt.getStatus()).isEqualTo(200); assertThat(receipt.getContentAsByteArray()).isEqualTo(image);
  assertThat(receipt.getContentType()).isEqualTo("image/png");
 }
 @Test void ownershipAndIndependentConfirmation() throws Exception {
  create(otherToken,"10",day,image,403);
  long id=create(salesToken,"10",day,image,201).path("id").asLong();
  call(get("/api/finance/cash-collections/"+id),otherToken,403);
  call(get("/api/finance/cash-collections/"+id+"/receipt"),otherToken,403);
  call(query(""),otherToken,403); call(query("/reconciliation"),otherToken,403);
  matrix.updateRolePermissions(ERole.ROLE_SALES,Set.of(Permission.COLLECTION_CREATE,Permission.COLLECTION_CONFIRM));
  call(post("/api/finance/cash-collections/"+id+"/confirm"),salesToken,403);
  call(post("/api/finance/cash-collections/"+id+"/confirm"),adminToken,200);
 }
 @Test void validationAndMissingResources() throws Exception {
  create(salesToken,"0",day,image,400); create(salesToken,"-1",day,image,400);
  create(salesToken,"1.001",day,image,400); create(salesToken,"1",day.plusDays(1),image,400);
  create(salesToken,"1",day,new byte[0],400); create(salesToken,"1",day,"fake png".getBytes(),400);
  create(salesToken,"1",day,new byte[5*1024*1024+1],400);
  call(get("/api/finance/cash-collections/999999999"),salesToken,404);
  call(post("/api/finance/cash-collections/999999999/confirm"),accountantToken,404);
  call(get("/api/finance/cash-collections").param("from",day.toString()).param("to",day.minusDays(1).toString()),salesToken,400);
 }
 @Test void grantAndRevokeOnSameJwt() throws Exception {
  matrix.updateRolePermissions(ERole.ROLE_SALES,Set.of());
  create(salesToken,"1",day,image,403); call(query(""),salesToken,403);
  matrix.updateRolePermissions(ERole.ROLE_SALES,Set.of(Permission.COLLECTION_CREATE));
  long id=create(salesToken,"1",day,image,201).path("id").asLong();
  matrix.updateRolePermissions(ERole.ROLE_ACCOUNTANT,Set.of());
  call(post("/api/finance/cash-collections/"+id+"/confirm"),accountantToken,403);
  call(query("/reconciliation"),accountantToken,403);
  matrix.updateRolePermissions(ERole.ROLE_ACCOUNTANT,Set.of(Permission.COLLECTION_CONFIRM,Permission.COLLECTION_RECONCILE));
  call(post("/api/finance/cash-collections/"+id+"/confirm"),accountantToken,200);
  call(query("/reconciliation"),accountantToken,200);
  matrix.updateRolePermissions(ERole.ROLE_SALES,Set.of());
  call(get("/api/finance/cash-collections/"+id),salesToken,403);
  call(get("/api/finance/cash-collections/"+id+"/receipt"),salesToken,403);
  assertThat(storedPermissions.findAll().stream().anyMatch(p->p.getRole()==ERole.ROLE_SALES && p.getPermission()==Permission.COLLECTION_CREATE)).isFalse();
  call(query("/reconciliation"),adminToken,200);
 }
 @Test void missingPartsAndInvalidParameters() throws Exception {
  call(multipart("/api/finance/cash-collections")
   .file(new MockMultipartFile("data","","application/json",json.writeValueAsBytes(Map.of("dealerId",dealer.getId(),"amount","1","collectionDate",day.toString())))),salesToken,400);
  call(get("/api/finance/cash-collections"),salesToken,400);
  call(get("/api/finance/cash-collections").param("from","bad").param("to",day.toString()),salesToken,400);
 }
 @Test void concurrentConfirmationRecordsOnce() throws Exception {
  long id=create(salesToken,"123",day,image,201).path("id").asLong();
  var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
  var start=new java.util.concurrent.CountDownLatch(1);
  java.util.concurrent.Callable<Integer> task=()->{
   start.await();
   return mvc.perform(post("/api/finance/cash-collections/"+id+"/confirm").header("Authorization","Bearer "+accountantToken)).andReturn().getResponse().getStatus();
  };
  try {
   var one=pool.submit(task); var two=pool.submit(task); start.countDown();
   assertThat(List.of(one.get(15,java.util.concurrent.TimeUnit.SECONDS),two.get(15,java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
   assertThat(call(query("/reconciliation"),accountantToken,200).path("data").get(0).path("received").decimalValue()).isEqualByComparingTo("123");
  } finally { pool.shutdownNow(); }
 }
 @Test void employeeGroupingAndRouteHistory() throws Exception {
  long first=create(salesToken,"10",day,image,201).path("id").asLong();
  dealer.setAssignedUser(other); dealer.setTerritory(null); dealers.save(dealer);
  create(otherToken,"20",day,image,201);
  var report=call(get("/api/finance/cash-collections/reconciliation").param("from",day.toString()).param("to",day.toString()),accountantToken,200).path("data");
  assertThat(report.size()).isGreaterThanOrEqualTo(2);
  assertThat(call(get("/api/finance/cash-collections/"+first),salesToken,200).path("data").path("routeName").asText()).isEqualTo("Tuyến A");
 }
 @Test void authenticationAndDayGrouping() throws Exception {
  call(query(""),null,401);
  create(salesToken,"10",day.minusDays(1),image,201);
  create(salesToken,"20",day,image,201);
  var result=call(query("/reconciliation"),salesToken,200).path("data");
  assertThat(result.size()).isEqualTo(1); assertThat(result.get(0).path("collected").decimalValue()).isEqualByComparingTo("20");
 }
}