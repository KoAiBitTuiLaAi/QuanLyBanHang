package com.quanlybanhang.integration;

import com.fasterxml.jackson.databind.*;
import com.quanlybanhang.config.RolePermissionConfig;
import com.quanlybanhang.model.*;
import com.quanlybanhang.repository.*;
import com.quanlybanhang.security.JwtUtils;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.CellType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ContentDisposition;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@SpringBootTest(properties={
 "spring.datasource.url=jdbc:h2:mem:debt_statement_test;MODE=MySQL;DB_CLOSE_DELAY=-1",
 "spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=",
 "spring.jpa.hibernate.ddl-auto=create-drop","spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
 "spring.jpa.show-sql=false","app.jwt.secret=debt-statement-test-secret-at-least-32-characters",
 "app.bootstrap.admin-password=Statement2026!","app.bootstrap.admin-email=statement@example.invalid",
 "app.seed-demo-users=false","app.seed-demo-data=false","app.debt-statement.max-rows=100"})
@AutoConfigureMockMvc @Transactional @DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class DealerDebtStatementIntegrationTest {
 @Autowired MockMvc mvc;
 @Autowired ObjectMapper json;
 @Autowired DealerRepository dealers;
 @Autowired UserRepository users;
 @Autowired RoleRepository roles;
 @Autowired OrderRepository orders;
 @Autowired DebtPaymentRepository payments;
 @Autowired InvoiceRepository invoices;
 @Autowired InvoiceAdjustmentRepository adjustments;
 @Autowired RolePermissionConfig matrix;
 @Autowired JwtUtils jwt;
 Dealer dealer;
 String token,admin;
 final String period="?from=2026-10-01&to=2026-10-31";
 @BeforeEach void setup() {
  matrix.updateRolePermissions(ERole.ROLE_ACCOUNTANT,Set.of(Permission.FINANCE_VIEW_DEBT));
  var user=users.saveAndFlush(User.builder().username(UUID.randomUUID().toString()).email(UUID.randomUUID()+"@example.invalid").password("unused")
   .roles(new HashSet<>(Set.of(roles.findByName(ERole.ROLE_ACCOUNTANT).orElseThrow()))).build());
  token=jwt.generateAccessToken(user.getUsername(),0L);admin=jwt.generateAccessToken("admin",0L);
  dealer=dealers.saveAndFlush(Dealer.builder().code("DL-A").name("Đại lý Nguyễn Ánh").build());
 }
 void order(String code,String date,String amount,String status) {
  orders.saveAndFlush(Order.builder().orderCode(code).dealer(dealer).totalAmount(new BigDecimal(amount)).status(status).recordedAt(LocalDateTime.parse(date)).build());
 }
 void payment(String code,String date,String amount) {
  payments.saveAndFlush(DebtPayment.builder().paymentCode(code).dealer(dealer).paymentDate(LocalDate.parse(date)).amount(new BigDecimal(amount)).note("Thu tiền đại lý").build());
 }
 Invoice invoice() {
  return invoices.saveAndFlush(Invoice.builder().invoiceNumber(UUID.randomUUID().toString().substring(0,8)).invoiceCode("HD-"+UUID.randomUUID())
   .invoiceDate(LocalDateTime.parse("2026-10-15T00:00:00")).dealer(dealer).dealerCode(dealer.getCode()).dealerName(dealer.getName()).totalAmount(new BigDecimal("200")).build());
 }
 void adjustment(Invoice invoice,String code,String date,String amount,InvoiceAdjustmentType type) {
  adjustments.saveAndFlush(InvoiceAdjustment.builder().invoice(invoice).adjustmentCode(code).adjustmentDate(LocalDateTime.parse(date))
   .adjustmentAmount(new BigDecimal(amount)).adjustmentType(type).adjustmentReason("Điều chỉnh đối chiếu").build());
 }
 void fixture() {
  order("BEFORE","2026-09-20T10:00:00","1000","RECORDED");payment("PAY-BEFORE","2026-09-25","200");
  order("ORDER-FIRST","2026-10-01T00:00:00","100","RECORDED");payment("PAY-FIRST","2026-10-01","30");
  order("ORDER-MID","2026-10-15T12:00:00","200","DELIVERED");
  var invoice=invoice();adjustment(invoice,"ADJ-REDUCE","2026-10-18T12:00:00","-50",InvoiceAdjustmentType.DECREASE);
  adjustment(invoice,"ADJ-INCREASE","2026-10-18T12:00:00","20",InvoiceAdjustmentType.INCREASE);
  adjustment(invoice,"ADJ-INFO","2026-10-18T12:00:00","0",InvoiceAdjustmentType.INFORMATION);
  payment("PAY-LAST","2026-10-31","100");order("AFTER","2026-11-01T00:00:00","999","RECORDED");
  order("DRAFT","2026-10-15T12:00:00","999","DRAFT");order("CANCELLED","2026-10-15T12:00:00","999","CANCELLED");
 }
 MockHttpServletResponse call(String suffix,String auth,int expected) throws Exception {
  var req=get(java.net.URI.create("/api/finance/debt/"+dealer.getId()+"/statement"+suffix));if(auth!=null)req.header("Authorization","Bearer "+auth);
  var response=mvc.perform(req).andReturn().getResponse();
  assertThat(response.getStatus()).as("HTTP %s (%s)",suffix,String.valueOf(response.getContentType()).contains("json")?response.getContentAsString():"binary export").isEqualTo(expected);return response;
 }
 JsonNode report() throws Exception{return json.readTree(call(period,token,200).getContentAsByteArray()).path("data");}
 @Test void fullLedgerOpeningTotalsRunningBalanceAndNoInvoiceDoubleCount() throws Exception {
  fixture();var report=report();
  assertThat(report.path("openingBalance").decimalValue()).isEqualByComparingTo("800");
  assertThat(report.path("totalIncrease").decimalValue()).isEqualByComparingTo("320");
  assertThat(report.path("totalDecrease").decimalValue()).isEqualByComparingTo("180");
  assertThat(report.path("closingBalance").decimalValue()).isEqualByComparingTo("940");
  assertThat(report.path("entries").size()).isEqualTo(6);
  assertThat(report.path("entries").get(0).path("documentCode").asText()).isEqualTo("ORDER-FIRST");
  BigDecimal running=new BigDecimal("800");
  for(var entry:report.path("entries")){
   running=running.add(entry.path("increase").decimalValue()).subtract(entry.path("decrease").decimalValue());
   assertThat(entry.path("balance").decimalValue()).isEqualByComparingTo(running);
  }
 }
 @Test void dateFiltersKeepOpeningWithoutLosingPreviousMovements() throws Exception {
  fixture();var r=json.readTree(call("?from=2026-10-19&to=2026-10-31",token,200).getContentAsByteArray()).path("data");
  assertThat(r.path("openingBalance").decimalValue()).isEqualByComparingTo("1040");
  assertThat(r.path("entries").size()).isEqualTo(1);
  assertThat(r.path("closingBalance").decimalValue()).isEqualByComparingTo("940");
 }
 @Test void emptyPeriodAndCreditBalanceArePreserved() throws Exception {
  var empty=report();assertThat(empty.path("entries").size()).isZero();assertThat(empty.path("closingBalance").decimalValue()).isEqualByComparingTo("0");
  payment("ADVANCE","2026-09-30","200");
  var credit=report();assertThat(credit.path("openingBalance").decimalValue()).isEqualByComparingTo("-200");
  assertThat(credit.path("closingBalance").decimalValue()).isEqualByComparingTo("-200");
 }
 @Test void cancellationAdjustmentAndNegativePaymentRemainSignedMovements() throws Exception {
  order("SALE","2026-10-01T10:00:00","200","DELIVERED");
  adjustment(invoice(),"CANCEL-ADJ","2026-10-02T10:00:00","-200",InvoiceAdjustmentType.CANCEL);
  payment("REVERSE-PAY","2026-10-03","-50");
  var r=report();assertThat(r.path("totalIncrease").decimalValue()).isEqualByComparingTo("250");
  assertThat(r.path("totalDecrease").decimalValue()).isEqualByComparingTo("200");assertThat(r.path("closingBalance").decimalValue()).isEqualByComparingTo("50");
 }
 @Test void exportPdfAndExcelMatchLedgerAndRetainVietnamese() throws Exception {
  fixture();
  var xlsx=call("/export"+period+"&format=xlsx",token,200);
  assertThat(ContentDisposition.parse(xlsx.getHeader("Content-Disposition")).getFilename()).isEqualTo("So-cong-no_DL-A_2026-10-01_2026-10-31.xlsx");
  try(var workbook=new XSSFWorkbook(new ByteArrayInputStream(xlsx.getContentAsByteArray()))) {
   var sheet=workbook.getSheetAt(0);assertThat(sheet.getRow(5).getCell(5).getNumericCellValue()).isEqualTo(800);
   assertThat(sheet.getRow(12).getCell(5).getNumericCellValue()).isEqualTo(940);
   assertThat(sheet.getRow(4).getCell(5).getStringCellValue()).isEqualTo("Số dư lũy kế");
  }
  var pdf=call("/export"+period+"&format=pdf",token,200);assertThat(pdf.getContentType()).isEqualTo("application/pdf");
  var reader=new PdfReader(pdf.getContentAsByteArray());
  try {
   String text=new PdfTextExtractor(reader).getTextFromPage(1);
   assertThat(text).contains("SỔ CHI TIẾT CÔNG NỢ","Đại lý Nguyễn Ánh","940,00","PAY-LAST");
  }finally{reader.close();}
 }
 @Test void revocationOnExistingJwtAndSalesScope() throws Exception {
  matrix.updateRolePermissions(ERole.ROLE_ACCOUNTANT,Set.of(Permission.FINANCE_MANAGE));
  call(period,token,403);call("/export"+period+"&format=xlsx",token,403);call("/export"+period+"&format=pdf",token,403);
  matrix.updateRolePermissions(ERole.ROLE_ACCOUNTANT,Set.of(Permission.FINANCE_VIEW_DEBT));call(period,token,200);
  call(period,null,401);call("/export"+period+"&format=pdf",admin,200);
  matrix.updateRolePermissions(ERole.ROLE_SALES,Set.of(Permission.FINANCE_VIEW_DEBT));
  var sales=users.saveAndFlush(User.builder().username("sales-statement").email("sales-statement@example.invalid").password("unused")
   .roles(new HashSet<>(Set.of(roles.findByName(ERole.ROLE_SALES).orElseThrow()))).build());
  String salesToken=jwt.generateAccessToken(sales.getUsername(),0L);call(period,salesToken,403);
  dealer.setAssignedUser(sales);dealers.saveAndFlush(dealer);call(period,salesToken,200);
 }
 @Test void validationAndRowLimitDoNotProducePartialExports() throws Exception {
  call("?from=2026-11-01&to=2026-10-01",token,400);call("?from=bad&to=2026-10-31",token,400);call("",token,400);
  call("/export"+period+"&format=csv",token,400);
  for(int i=0;i<101;i++)order("LIMIT-"+i,"2026-10-01T10:00:00","1","RECORDED");
  call(period,token,400);call("/export"+period+"&format=pdf",token,400);call("/export"+period+"&format=xlsx",token,400);
 }
 @Test void multipagePdfRepeatsHeadersAndTextCannotBecomeExcelFormula() throws Exception {
  for(int i=0;i<65;i++)order("LONG-"+i,"2026-10-01T10:00:00","1","RECORDED");
  var row=payments.saveAndFlush(DebtPayment.builder().paymentCode("=1+1").dealer(dealer).paymentDate(LocalDate.of(2026,10,2))
   .amount(BigDecimal.ONE).note("=HYPERLINK(\"https://example.invalid\")").build());
  var pdf=call("/export"+period+"&format=pdf",token,200);
  var reader=new PdfReader(pdf.getContentAsByteArray());
  try {assertThat(reader.getNumberOfPages()).isGreaterThan(1);assertThat(new PdfTextExtractor(reader).getTextFromPage(2)).contains("Số chứng từ","Số dư lũy kế");}finally{reader.close();}
  try(var workbook=new XSSFWorkbook(new ByteArrayInputStream(call("/export"+period+"&format=xlsx",token,200).getContentAsByteArray()))) {
   assertThat(workbook.getSheetAt(0).getRow(71).getCell(1).getCellType()).isEqualTo(CellType.STRING);
   assertThat(workbook.getSheetAt(0).getRow(71).getCell(1).getStringCellValue()).isEqualTo("=1+1");
  }
  // Generated solely for local visual QA; ignored build outputs are not committed.
  var folder=java.nio.file.Path.of("target","statement-qa");java.nio.file.Files.createDirectories(folder);
  java.nio.file.Files.write(folder.resolve("debt-statement.pdf"),pdf.getContentAsByteArray());
 }
 @Test void otherDealerMovementsNeverLeakAndMissingDealerReturns404() throws Exception {
  fixture();var other=dealers.saveAndFlush(Dealer.builder().code("DL-B").name("Đại lý khác").build());
  orders.saveAndFlush(Order.builder().orderCode("OTHER-DEALER").dealer(other).status("RECORDED").recordedAt(LocalDateTime.parse("2026-10-01T00:00:00"))
   .totalAmount(new BigDecimal("999999")).build());
  assertThat(report().path("closingBalance").decimalValue()).isEqualByComparingTo("940");
  assertThat(mvc.perform(get("/api/finance/debt/999999999/statement"+period).header("Authorization","Bearer "+token))
   .andReturn().getResponse().getStatus()).isEqualTo(404);
 }
 @Test void exactFinancialValuesBeyondExcelPrecisionAreWrittenAsText() throws Exception {
  payment("BIG-AMOUNT","2026-10-01","9007199254740993.01");
  json.enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
  assertThat(report().path("closingBalance").decimalValue()).isEqualByComparingTo("-9007199254740993.01");
  try(var workbook=new XSSFWorkbook(new ByteArrayInputStream(call("/export"+period+"&format=xlsx",token,200).getContentAsByteArray()))) {
   var cell=workbook.getSheetAt(0).getRow(6).getCell(4);
   assertThat(cell.getCellType()).isEqualTo(CellType.STRING);assertThat(cell.getStringCellValue()).isEqualTo("9007199254740993.01");
  }
 }
}