package com.quanlybanhang.integration;

import com.fasterxml.jackson.databind.*;
import com.quanlybanhang.config.RolePermissionConfig;
import com.quanlybanhang.model.*;
import com.quanlybanhang.repository.*;
import com.quanlybanhang.security.JwtUtils;
import com.quanlybanhang.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(properties={
 "spring.datasource.url=jdbc:h2:mem:return_order_test;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
 "spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=",
 "spring.jpa.hibernate.ddl-auto=create-drop","spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
 "spring.jpa.show-sql=false","app.jwt.secret=return-order-test-secret-at-least-32-characters",
 "app.bootstrap.admin-password=ReturnTest2026!","app.bootstrap.admin-email=returns@example.invalid",
 "app.seed-demo-users=false","app.seed-demo-data=false"})
@AutoConfigureMockMvc @DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class ReturnOrderIntegrationTest {
 @Autowired MockMvc mvc;
 @Autowired ObjectMapper json;
 @Autowired JdbcTemplate jdbc;
 @Autowired UserRepository users;
 @Autowired RoleRepository roles;
 @Autowired ProductRepository products;
 @Autowired CategoryRepository categories;
 @Autowired DealerRepository dealers;
 @Autowired OrderRepository orders;
 @Autowired InvoiceRepository invoices;
 @Autowired InvoiceAdjustmentRepository adjustments;
 @Autowired ReturnOrderRepository returns;
 @Autowired DebtPaymentRepository payments;
 @Autowired RolePermissionConfig matrix;
 @Autowired JwtUtils jwt;
 @Autowired PasswordEncoder passwords;
 @Autowired DealerService dealerService;
 @SpyBean DealerDebtServiceImpl debt;
 String token, adminToken;
 Dealer dealer;
 Invoice invoice;
 Long lineId;
 Product product;
 Order order;
 final String base="/api/invoices/returns";
 @BeforeEach void setup() {
  String key=UUID.randomUUID().toString().substring(0,8);
  matrix.updateRolePermissions(ERole.ROLE_ACCOUNTANT,Set.of(Permission.INVOICE_VIEW,Permission.INVOICE_ADJUST));
  var user=users.save(User.builder().username("rt"+key).email(key+"@example.invalid").password(passwords.encode("ReturnTest2026!"))
   .roles(Set.of(roles.findByName(ERole.ROLE_ACCOUNTANT).orElseThrow())).build());
  token=jwt.generateAccessToken(user.getUsername(),0L); adminToken=jwt.generateAccessToken("admin",0L);
  var category=categories.save(Category.builder().code(key).name("Hàng hóa").build());
  product=products.save(Product.builder().code(key).name("Sản phẩm").unit("thùng").category(category).price(new BigDecimal("33.34")).active(true).build());
  dealer=dealers.save(Dealer.builder().code(key).name("Đại lý").debtLimit(new BigDecimal("200")).maxDebtDays(5).build());
  order=orders.save(Order.builder().orderCode("DH-"+key).dealer(dealer).status("DELIVERED").totalAmount(new BigDecimal("100.00"))
   .recordedAt(LocalDateTime.now().minusDays(30)).createdAt(LocalDateTime.now().minusDays(30)).build());
  invoice=Invoice.builder().invoiceNumber(key).invoiceCode("HD-"+key).invoiceDate(LocalDateTime.now().minusDays(25))
   .dealer(dealer).dealerCode(dealer.getCode()).dealerName(dealer.getName()).subtotalAmount(new BigDecimal("100.02"))
   .discountAmount(new BigDecimal("0.02")).totalAmount(new BigDecimal("100.00")).adjustedTotalAmount(new BigDecimal("100.00"))
   .orders(new ArrayList<>(List.of(order))).createdBy("original").note("Immutable invoice").build();
  invoice.addItem(InvoiceItem.builder().order(order).product(product).productCode(product.getCode()).productName(product.getName()).unit("thùng")
   .orderedQuantity(new BigDecimal("3")).deliveredQuantity(new BigDecimal("3")).invoicedQuantity(new BigDecimal("3"))
   .unitPrice(new BigDecimal("33.34")).subtotalAmount(new BigDecimal("100.02")).discountAmount(new BigDecimal("0.02"))
   .totalAmount(new BigDecimal("100.00")).build());
  invoice=invoices.saveAndFlush(invoice); lineId=invoice.getItems().get(0).getId();
 }
 JsonNode call(MockHttpServletRequestBuilder req,String auth,Object body,int expected) throws Exception {
  if(auth!=null)req.header("Authorization","Bearer "+auth);
  if(body!=null)req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body));
  var response=mvc.perform(req).andReturn().getResponse();
  assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(expected);
  return response.getContentAsByteArray().length==0?json.nullNode():json.readTree(response.getContentAsByteArray()).path("data");
 }
 Map<String,Object> body(String quantity) {
  return Map.of("invoiceId",invoice.getId(),"returnReason","Trả hàng thực tế","items",List.of(Map.of("invoiceItemId",lineId,"returnQuantity",quantity)));
 }
 long draft(String quantity) throws Exception { return call(post(base),token,body(quantity),201).path("id").asLong(); }
 JsonNode confirm(long id,int expected) throws Exception { return call(post(base+"/"+id+"/confirm"),token,null,expected); }
 @Test void historicalPriceCreditAndImmutableInvoice() throws Exception {
  var original=jdbc.queryForMap("select * from invoices where id=?",invoice.getId());
  var originalItem=jdbc.queryForMap("select * from invoice_items where id=?",lineId);
  product.setPrice(new BigDecimal("9999")); products.saveAndFlush(product);
  long id=draft("1");
  assertThat(debt.getDealerDebtInfo(dealer.getId()).getCurrentDebt()).isEqualByComparingTo("100");
  var result=confirm(id,200);
  assertThat(result.path("totalReturnAmount").decimalValue()).isEqualByComparingTo("33.33");
  assertThat(result.path("invoiceAdjustment").path("adjustmentAmount").decimalValue()).isEqualByComparingTo("-33.33");
  assertThat(result.path("invoiceAdjustment").path("invoiceId").asLong()).isEqualTo(invoice.getId());
  assertThat(result.path("items").get(0).path("invoiceItemId").asLong()).isEqualTo(lineId);
  assertThat(debt.getDealerDebtInfo(dealer.getId()).getCurrentDebt()).isEqualByComparingTo("66.67");
  assertThat(debt.getDealerDebtInfo(dealer.getId()).getRemainingLimit()).isEqualByComparingTo("133.33");
  assertThat(debt.getDealerDebtInfo(dealer.getId()).getOverdueAmount()).isEqualByComparingTo("66.67");
  assertThat(dealerService.getDealerById(dealer.getId()).getCurrentDebt()).isEqualByComparingTo("66.67");
  assertThat(jdbc.queryForMap("select * from invoices where id=?",invoice.getId())).isEqualTo(original);
  assertThat(jdbc.queryForMap("select * from invoice_items where id=?",lineId)).isEqualTo(originalItem);
  call(get("/api/invoices/"+invoice.getId()+"/adjustments"),token,null,200);
 }
 @Test void splitReturnsPreserveEveryCentAndRevokeOverdueFlag() throws Exception {
  BigDecimal sum=BigDecimal.ZERO;
  for(int i=0;i<3;i++)sum=sum.add(confirm(draft("1"),200).path("totalReturnAmount").decimalValue());
  assertThat(sum).isEqualByComparingTo("100");
  var info=debt.getDealerDebtInfo(dealer.getId());
  assertThat(info.getCurrentDebt()).isEqualByComparingTo("0");
  assertThat(info.getRemainingLimit()).isEqualByComparingTo("200");
  assertThat(info.getHasOverdueDebt()).isFalse();
  assertThat(dealerService.getDealerById(dealer.getId()).getHasOverdueDebt()).isFalse();
  assertThat(dealers.findById(dealer.getId()).orElseThrow().getDebtFlagged()).isFalse();
  call(post(base),token,body("0.0001"),400);
 }
 @Test void cannotOverReturnAcrossDraftsOrConfirmTwice() throws Exception {
  long first=draft("2"); long second=draft("2");
  confirm(first,200); confirm(first,409); confirm(second,400);
  assertThat(returns.findById(second).orElseThrow().getStatus()).isEqualTo(ReturnOrderStatus.DRAFT);
  assertThat(adjustments.findByInvoiceIdOrderByCreatedAtDesc(invoice.getId())).hasSize(1);
 }
 @Test void validationCancellationAndReadApis() throws Exception {
  call(post(base),token,body("0"),400); call(post(base),token,body("-1"),400);
  call(post(base),token,body("1.00001"),400); call(post(base),token,body("4"),400);
  var duplicate=new HashMap<>(body("1")); duplicate.put("items",List.of(Map.of("invoiceItemId",lineId,"returnQuantity","1"),Map.of("invoiceItemId",lineId,"returnQuantity","1")));
  call(post(base),token,duplicate,400);
  var foreign=new HashMap<>(body("1")); foreign.put("items",List.of(Map.of("invoiceItemId",9999999,"returnQuantity","1"))); call(post(base),token,foreign,400);
  var blank=new HashMap<>(body("1")); blank.put("returnReason"," "); call(post(base),token,blank,400);
  long id=draft("1");
  call(get(base+"/"+id),token,null,200);
  call(get(base).param("invoiceId",invoice.getId().toString()),token,null,200);
  call(get(base+"/by-invoice/"+invoice.getId()),token,null,200);
  call(get(base).param("status","unknown"),token,null,400);
  call(post(base+"/"+id+"/cancel"),token,Map.of("cancelReason","Nhập nhầm"),200); confirm(id,409);
  assertThat(debt.getDealerDebtInfo(dealer.getId()).getCurrentDebt()).isEqualByComparingTo("100");
  call(get(base+"/9999999"),token,null,404);
  invoice.setStatus(InvoiceStatus.CANCELLED); invoices.saveAndFlush(invoice); call(post(base),token,body("1"),400);
 }
 @Test void permissionsGrantRevokeWithExistingJwtAndAdmin() throws Exception {
  long id=draft("1"); matrix.updateRolePermissions(ERole.ROLE_ACCOUNTANT,Set.of());
  call(post(base),token,body("1"),403); confirm(id,403);
  call(post(base+"/"+id+"/cancel"),token,Map.of("cancelReason","x"),403);
  call(get(base+"/"+id),token,null,403); call(get(base),token,null,403);
  call(get(base+"/by-invoice/"+invoice.getId()),token,null,403); call(get(base),null,null,401);
  matrix.updateRolePermissions(ERole.ROLE_ACCOUNTANT,Set.of(Permission.INVOICE_ADJUST));
  confirm(id,200); call(get(base),token,null,403);
  matrix.updateRolePermissions(ERole.ROLE_ACCOUNTANT,Set.of(Permission.INVOICE_VIEW)); call(get(base+"/"+id),token,null,200);
  call(post(base),token,body("1"),403); call(post(base),adminToken,body("1"),201);
 }
 @Test void atomicRollbackWhenDebtEvaluationFails() throws Exception {
  long id=draft("1");
  doThrow(new IllegalStateException("Simulated debt failure")).when(debt).evaluateDealerDebt(dealer.getId());
  confirm(id,400);
  assertThat(returns.findById(id).orElseThrow().getStatus()).isEqualTo(ReturnOrderStatus.DRAFT);
  assertThat(adjustments.findByInvoiceIdOrderByCreatedAtDesc(invoice.getId())).isEmpty();
 }
 @Test void concurrentConfirmationsCannotOverReturn() throws Exception {
  long first=draft("2"), second=draft("2");
  var pool=Executors.newFixedThreadPool(2); var start=new CountDownLatch(1);
  java.util.function.LongFunction<Callable<Integer>> task=id->()->{
   start.await(); return mvc.perform(post(base+"/"+id+"/confirm").header("Authorization","Bearer "+token)).andReturn().getResponse().getStatus();
  };
  try {
   var one=pool.submit(task.apply(first)); var two=pool.submit(task.apply(second)); start.countDown();
   assertThat(List.of(one.get(20,TimeUnit.SECONDS),two.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,400);
   assertThat(adjustments.findByInvoiceIdOrderByCreatedAtDesc(invoice.getId())).hasSize(1);
  }finally{pool.shutdownNow();}
 }
 @Test void previousPaymentIsNotCountedTwiceAndCreditNeverCreatesNegativeDebt() throws Exception {
  payments.saveAndFlush(DebtPayment.builder().paymentCode("PT-"+UUID.randomUUID()).dealer(dealer)
   .amount(new BigDecimal("80")).paymentDate(LocalDate.now()).build());
  assertThat(debt.getDealerDebtInfo(dealer.getId()).getCurrentDebt()).isEqualByComparingTo("20");
  confirm(draft("1"),200);
  assertThat(debt.getDealerDebtInfo(dealer.getId()).getCurrentDebt()).isEqualByComparingTo("0");
  assertThat(dealerService.getDealerById(dealer.getId()).getCurrentDebt()).isEqualByComparingTo("0");
  assertThat(debt.getDealerDebtInfo(dealer.getId()).getRemainingLimit()).isEqualByComparingTo("200");
 }
 @Test void fullReturnOfOneOrderDoesNotClearOverdueDebtOfAnother() throws Exception {
  orders.saveAndFlush(Order.builder().orderCode("OTHER-"+UUID.randomUUID()).dealer(dealer).status("DELIVERED").totalAmount(new BigDecimal("50"))
   .recordedAt(LocalDateTime.now().minusDays(40)).createdAt(LocalDateTime.now().minusDays(40)).build());
  confirm(draft("3"),200);
  var info=debt.getDealerDebtInfo(dealer.getId());
  assertThat(info.getCurrentDebt()).isEqualByComparingTo("50"); assertThat(info.getOverdueAmount()).isEqualByComparingTo("50");
 }
}