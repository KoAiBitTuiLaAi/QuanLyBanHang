package com.quanlybanhang.integration;

import com.fasterxml.jackson.databind.*;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import com.quanlybanhang.config.RolePermissionConfig;
import com.quanlybanhang.model.*;
import com.quanlybanhang.repository.*;
import com.quanlybanhang.security.JwtUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(properties={
 "spring.datasource.url=jdbc:h2:mem:debt_receipt_test;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
 "spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=",
 "spring.jpa.hibernate.ddl-auto=create-drop","spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
 "spring.jpa.show-sql=false","app.jwt.secret=debt-receipt-test-secret-at-least-32-characters",
 "app.bootstrap.admin-password=ReceiptTest2026!","app.bootstrap.admin-email=receipt@example.invalid",
 "app.seed-demo-users=false","app.seed-demo-data=false","app.payment-reminder.scheduler.enabled=false"})
@AutoConfigureMockMvc @DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class DebtReceiptIntegrationTest {
 @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired DealerRepository dealers;
 @Autowired UserRepository users; @Autowired RoleRepository roles; @Autowired WarehouseRepository warehouses;
 @Autowired DebtPaymentRepository payments; @Autowired DebtPaymentAllocationRepository allocations;
 @Autowired OrderRepository orders; @Autowired RolePermissionConfig matrix; @Autowired JwtUtils jwt;
 Dealer dealer; DebtPayment payment; User reader; String token,admin,key;
 @BeforeEach void setup(){
  key=UUID.randomUUID().toString().substring(0,8);
  matrix.updateRolePermissions(ERole.ROLE_VIEWER,Set.of(Permission.FINANCE_VIEW_DEBT));
  matrix.updateRolePermissions(ERole.ROLE_ACCOUNTANT,Set.of());
  reader=user("reader-"+key,ERole.ROLE_VIEWER);token=jwt.generateAccessToken(reader.getUsername(),0L);admin=jwt.generateAccessToken("admin",0L);
  dealer=dealers.saveAndFlush(Dealer.builder().code("DL-"+key).name("Đại lý Nguyễn Ánh "+key).address("Hà Nội").build());
  payment=payments.saveAndFlush(DebtPayment.builder().dealer(dealer).paymentCode("PT-"+key).amount(new BigDecimal("1234567.89"))
   .paymentDate(LocalDate.of(2026,10,25)).paymentMethod("CHUYEN_KHOAN").recordedBy("Lê Văn Thu")
   .referenceCode("UNC-"+key).note("<img src=x onerror=alert(1)> & thu tiền").build());
 }
 User user(String name,ERole role){return users.saveAndFlush(User.builder().username(name).email(name+"@example.invalid").password("unused")
  .roles(new HashSet<>(Set.of(roles.findByName(role).orElseThrow()))).build());}
 String path(){return "/api/finance/debt-payments/"+payment.getId()+"/receipt";}
 MvcResult getAs(String suffix,String auth,int status) throws Exception{
  var result=mvc.perform(get(path()+suffix).header("Authorization","Bearer "+auth)).andReturn();
  assertThat(result.getResponse().getStatus()).as("%s: %s",suffix,result.getResponse().getContentAsString()).isEqualTo(status);return result;
 }
 @Test void jsonUsesPersistedCodeAmountAndCollectorAndRepeatedReadsNeverCreateEntries() throws Exception{
  long before=payments.count(),allocationCount=allocations.count();
  for(int i=0;i<2;i++){
   var r=getAs("",token,200).getResponse();var data=json.readTree(r.getContentAsByteArray()).path("data");
   assertThat(data.path("paymentCode").asText()).isEqualTo(payment.getPaymentCode());
   assertThat(data.path("amount").decimalValue()).isEqualByComparingTo("1234567.89");
   assertThat(data.path("recordedBy").asText()).isEqualTo("Lê Văn Thu");assertThat(r.getHeader("Cache-Control")).contains("no-store");
   getAs("/pdf",token,200);getAs("/print",token,200);
  }
  assertThat(payments.count()).isEqualTo(before);assertThat(allocations.count()).isEqualTo(allocationCount);
  assertThat(payments.findById(payment.getId()).orElseThrow().getAmount()).isEqualByComparingTo(payment.getAmount());
 }
 @Test void pdfContainsVietnameseAndExactAmountWithSafeDownloadHeaders() throws Exception{
  var r=getAs("/pdf",token,200).getResponse();assertThat(r.getContentType()).startsWith("application/pdf");
  assertThat(r.getHeader("Content-Disposition")).contains("attachment").contains(".pdf");assertThat(r.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
  var pdf=new PdfReader(r.getContentAsByteArray());String text=new PdfTextExtractor(pdf).getTextFromPage(1);pdf.close();
  assertThat(text).contains(payment.getPaymentCode(),"Nguyễn Ánh","Lê Văn Thu","1.234.567,89");
 }
 @Test void htmlEscapesStoredMarkupAndDoesNotExecuteScripts() throws Exception{
  var r=getAs("/print",token,200).getResponse();String html=r.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
  assertThat(html).contains("&lt;img").doesNotContain("<img src=x","<script");
  assertThat(r.getHeader("Content-Security-Policy")).contains("default-src 'none'");
 }
 @Test void revokingViewRevokesAllExportsOnSameJwtEvenIfManageStillGrantedAndGrantRestoresThem() throws Exception{
  getAs("/pdf",token,200);matrix.updateRolePermissions(ERole.ROLE_VIEWER,Set.of(Permission.FINANCE_MANAGE));
  for(String suffix:List.of("","/print","/pdf"))getAs(suffix,token,403);
  matrix.updateRolePermissions(ERole.ROLE_VIEWER,Set.of(Permission.FINANCE_VIEW_DEBT));getAs("/pdf",token,200);
 }
 @Test void accountantRoleDoesNotReplaceMissingViewAndAdminAlwaysReads() throws Exception{
  var accountant=user("accountant-"+key,ERole.ROLE_ACCOUNTANT);getAs("/pdf",jwt.generateAccessToken(accountant.getUsername(),0L),403);
  matrix.updateRolePermissions(ERole.ROLE_ADMIN,Set.of());getAs("/pdf",admin,200);
 }
 @Test void multipleRolesUnionIsHonouredWithoutStalePermissionFallback() throws Exception{
  reader.setRoles(new HashSet<>(Set.of(roles.findByName(ERole.ROLE_VIEWER).orElseThrow(),roles.findByName(ERole.ROLE_ACCOUNTANT).orElseThrow())));users.saveAndFlush(reader);
  matrix.updateRolePermissions(ERole.ROLE_VIEWER,Set.of());matrix.updateRolePermissions(ERole.ROLE_ACCOUNTANT,Set.of(Permission.FINANCE_VIEW_DEBT));getAs("/pdf",token,200);
  matrix.updateRolePermissions(ERole.ROLE_ACCOUNTANT,Set.of());getAs("/pdf",token,403);
 }
 @Test void salesReaderCannotExportAnotherSalespersonsReceipt() throws Exception{
  matrix.updateRolePermissions(ERole.ROLE_SALES,Set.of(Permission.FINANCE_VIEW_DEBT));var sales=user("sales-"+key,ERole.ROLE_SALES);String t=jwt.generateAccessToken(sales.getUsername(),0L);
  for(String suffix:List.of("","/pdf","/print"))getAs(suffix,t,403);
  dealer.setAssignedUser(sales);dealers.saveAndFlush(dealer);getAs("/pdf",t,200);
 }
 @Test void warehouseScopeIsEnforcedForGrantedFinanceRead() throws Exception{
  matrix.updateRolePermissions(ERole.ROLE_WAREHOUSE_STAFF,Set.of(Permission.FINANCE_VIEW_DEBT));var staff=user("warehouse-"+key,ERole.ROLE_WAREHOUSE_STAFF);
  var w=warehouses.saveAndFlush(Warehouse.builder().code("W-"+key).name("Kho test").build());staff.setWarehouses(new HashSet<>(Set.of(w)));users.saveAndFlush(staff);String t=jwt.generateAccessToken(staff.getUsername(),0L);
  getAs("/pdf",t,403);dealer.setWarehouse(w);dealers.saveAndFlush(dealer);getAs("/pdf",t,200);
 }
 @Test void unknownInvalidAndUnauthenticatedRequestsReturnAppropriateStatus() throws Exception{
  mvc.perform(get(path()+"/pdf")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
  for(String id:List.of("0","-1","999999999")){
   int expected=id.equals("999999999")?404:400;var r=mvc.perform(get("/api/finance/debt-payments/"+id+"/receipt/pdf").header("Authorization","Bearer "+token)).andReturn();assertThat(r.getResponse().getStatus()).isEqualTo(expected);
  }
 }
 @Test void negativeAdjustmentsAreClearlyLabelledAndNeverPrintedAsNewIncome() throws Exception{
  var adjustment=payments.saveAndFlush(DebtPayment.builder().paymentCode("BTDC-"+key).dealer(dealer).paymentType("ADJUSTMENT").originalPayment(payment)
   .amount(new BigDecimal("-10.25")).paymentDate(LocalDate.of(2026,10,25)).recordedBy("Kế toán Nguyễn An").adjustmentReason("Điều chỉnh thu thừa").build());
  String originalCode=payment.getPaymentCode();payment=adjustment;
  var data=json.readTree(getAs("",token,200).getResponse().getContentAsByteArray()).path("data");assertThat(data.path("amount").decimalValue()).isEqualByComparingTo("-10.25");
  String html=getAs("/print",token,200).getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);assertThat(html).contains("ĐIỀU CHỈNH",originalCode);
 }
 @Test void mismatchedAllocationsFailClosedWithoutLeakingOtherDealerDocuments() throws Exception{
  var foreign=dealers.saveAndFlush(Dealer.builder().code("F-"+key).name("Foreign dealer").build());
  var o=orders.saveAndFlush(Order.builder().orderCode("SECRET-"+key).dealer(foreign).status("RECORDED").build());
  allocations.saveAndFlush(DebtPaymentAllocation.builder().payment(payment).order(o).amount(BigDecimal.ONE).build());
  for(String suffix:List.of("","/pdf","/print"))assertThat(getAs(suffix,token,409).getResponse().getContentAsString()).doesNotContain(o.getOrderCode());
 }
 @Test void concurrentlyRecordedPaymentsForDifferentDealersGetUniquePersistentCodes() throws Exception{
  var other=dealers.saveAndFlush(Dealer.builder().code("CONCURRENT-"+key).name("Other test dealer").build());
  var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
  try{
   var futures=List.of(dealer.getId(),other.getId()).stream().map(id->pool.submit(()->{
    start.await();var result=mvc.perform(post("/api/finance/debt-payments").header("Authorization","Bearer "+admin).contentType("application/json")
      .content(json.writeValueAsString(Map.of("dealerId",id,"amount","123.45","paymentDate","2026-10-25","paymentMethod","TIEN_MAT","recordedBy","Test collector")))).andReturn();
    assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(201);
    return json.readTree(result.getResponse().getContentAsByteArray()).path("data").path("paymentCode").asText();
   })).toList();start.countDown();Set<String> codes=new HashSet<>();for(var future:futures)codes.add(future.get(30,TimeUnit.SECONDS));assertThat(codes).hasSize(2);assertThat(codes).allMatch(c->c.startsWith("PT-20261025-")&&c.length()<=50);
  }finally{pool.shutdownNow();}
 }
}
