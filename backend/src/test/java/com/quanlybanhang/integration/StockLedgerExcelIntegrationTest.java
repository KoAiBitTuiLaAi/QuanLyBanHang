package com.quanlybanhang.integration;

import com.fasterxml.jackson.databind.*;
import com.quanlybanhang.config.RolePermissionConfig;
import com.quanlybanhang.model.*;
import com.quanlybanhang.repository.*;
import com.quanlybanhang.security.JwtUtils;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@SpringBootTest(properties={
 "spring.datasource.url=jdbc:h2:mem:stock_excel_test;MODE=MySQL;DB_CLOSE_DELAY=-1",
 "spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=",
 "spring.jpa.hibernate.ddl-auto=create-drop","spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
 "spring.jpa.show-sql=false","app.jwt.secret=stock-excel-test-secret-at-least-32-characters",
 "app.bootstrap.admin-password=StockExcel2026!","app.bootstrap.admin-email=stockexcel@example.invalid",
 "app.seed-demo-users=false","app.seed-demo-data=false","app.stock-ledger.export.max-rows=1500"})
@AutoConfigureMockMvc @Transactional @DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class StockLedgerExcelIntegrationTest {
 @Autowired MockMvc mvc;
 @Autowired ObjectMapper json;
 @Autowired UserRepository users;
 @Autowired RoleRepository roles;
 @Autowired WarehouseRepository warehouses;
 @Autowired ProductRepository products;
 @Autowired CategoryRepository categories;
 @Autowired WarehouseInventoryRepository inventories;
 @Autowired StockMinimumRepository minimums;
 @Autowired RolePermissionConfig matrix;
 @Autowired JwtUtils jwt;
 @MockBean(name="stockLedgerExportClock") Clock clock;
 Warehouse a,b;
 Category category;
 User user;
 String token,admin;
 final String base="/api/warehouse/stock-ledger";
 @BeforeEach void setup() {
  when(clock.getZone()).thenReturn(ZoneId.of("Asia/Ho_Chi_Minh"));
  when(clock.instant()).thenReturn(Instant.parse("2026-10-22T18:00:00Z"));
  matrix.updateRolePermissions(ERole.ROLE_WAREHOUSE_MANAGER,Set.of(Permission.WAREHOUSE_VIEW));
  a=warehouses.saveAndFlush(Warehouse.builder().code("KHO-A").name("Kho A").build());
  b=warehouses.saveAndFlush(Warehouse.builder().code("KHO-B").name("Kho B").build());
  user=users.saveAndFlush(User.builder().username(UUID.randomUUID().toString()).email(UUID.randomUUID()+"@example.invalid").password("unused")
   .roles(new HashSet<>(Set.of(roles.findByName(ERole.ROLE_WAREHOUSE_MANAGER).orElseThrow()))).warehouses(new HashSet<>(Set.of(a))).build());
  token=jwt.generateAccessToken(user.getUsername(),0L); admin=jwt.generateAccessToken("admin",0L);
  category=categories.saveAndFlush(Category.builder().code("CAT-A").name("Nhóm A").build());
  var other=categories.saveAndFlush(Category.builder().code("CAT-B").name("Nhóm B").build());
  var first=product("SKU%_A","=HYPERLINK(\"https://example.invalid\")",category);
  var second=product("SKU-B","Sản phẩm B",category);
  product("SKU-C","Chưa có tồn",category);
  var fourth=product("SKU-D","Khác nhóm",other);
  stock(a,first,"12.3456","2.1111");stock(a,second,"3","3");stock(a,fourth,"9","0");
  stock(b,first,"999","0");
  minimums.saveAndFlush(StockMinimum.builder().warehouse(a).product(first).minimumQuantity(new BigDecimal("11"))
   .baseUnit("lon").updatedBy("admin").updatedAt(LocalDateTime.now()).build());
 }
 Product product(String code,String name,Category group) {
  return products.saveAndFlush(Product.builder().code(code).name(name).category(group).unit("lon").price(new BigDecimal("999"))
   .costPrice(new BigDecimal("555")).build());
 }
 void stock(Warehouse w,Product p,String physical,String reserved) {
  inventories.saveAndFlush(WarehouseInventory.builder().warehouse(w).product(p).baseUnit("lon")
   .baseQuantity(new BigDecimal(physical)).reservedQuantity(new BigDecimal(reserved)).build());
 }
 MockHttpServletResponse response(String suffix,String auth,int expected) throws Exception {
  var req=get(java.net.URI.create(base+suffix));if(auth!=null)req.header("Authorization","Bearer "+auth);
  var r=mvc.perform(req).andReturn().getResponse();assertThat(r.getStatus()).as("HTTP %s: %s", suffix, String.valueOf(r.getContentType()).contains("json") ? r.getContentAsString() : "XLSX response").isEqualTo(expected);return r;
 }
 XSSFWorkbook book(MockHttpServletResponse r) throws Exception {return new XSSFWorkbook(new ByteArrayInputStream(r.getContentAsByteArray()));}
 @Test void fileHeadersTemplateThreeNumericColumnsAndFormulaSafety() throws Exception {
  var result=response("/export?warehouseId="+a.getId(),token,200);
  assertThat(result.getContentType()).isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
  assertThat(ContentDisposition.parse(result.getHeader("Content-Disposition")).getFilename()).isEqualTo("So-ton-kho_KHO-A_2026-10-23.xlsx");
  assertThat(result.getHeader("Cache-Control")).contains("no-store");
  try(var workbook=book(result)) {
   var sheet=workbook.getSheet("Sổ tồn kho");
   assertThat(sheet.getRow(0).getLastCellNum()).isEqualTo((short)12);
   assertThat(sheet.getRow(0).getCell(6).getStringCellValue()).isEqualTo("Tồn thực tế");
   assertThat(sheet.getRow(0).getCell(7).getStringCellValue()).isEqualTo("Đang giữ chỗ");
   assertThat(sheet.getRow(0).getCell(8).getStringCellValue()).isEqualTo("Tồn khả dụng");
   assertThat(sheet.getLastRowNum()).isEqualTo(4);
   var row=sheet.getRow(1);
   assertThat(row.getCell(1).getCellType()).isEqualTo(CellType.STRING);
   assertThat(row.getCell(1).getStringCellValue()).startsWith("=HYPERLINK");
   assertThat(row.getCell(6).getCellType()).isEqualTo(CellType.NUMERIC);
   assertThat(row.getCell(6).getNumericCellValue()).isEqualTo(12.3456);
   assertThat(row.getCell(7).getNumericCellValue()).isEqualTo(2.1111);
   assertThat(row.getCell(8).getNumericCellValue()).isEqualTo(10.2345);
   assertThat(sheet.getPaneInformation().isFreezePane()).isTrue();
   assertThat(row.getCell(10).getNumericCellValue()).isEqualTo(11);
  }
 }
 @Test void exportMatchesEveryAppliedFilterAndLiteralKeyword() throws Exception {
  String filters="?warehouseId="+a.getId()+"&categoryId="+category.getId()+"&stockStatus=IN_STOCK&belowMinimum=true&keyword=SKU%25_";
  var screen=json.readTree(response(filters,token,200).getContentAsByteArray()).path("data").path("content");
  try(var workbook=book(response("/export"+filters,token,200))) {
   var sheet=workbook.getSheetAt(0);assertThat(sheet.getLastRowNum()).isEqualTo(screen.size()).isEqualTo(1);
   assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo(screen.get(0).path("productCode").asText());
   assertThat(sheet.getRow(1).getCell(8).getNumericCellValue()).isEqualTo(screen.get(0).path("availableQuantity").asDouble());
  }
  try(var workbook=book(response("/export?stockStatus=FULLY_RESERVED",token,200))) {
   assertThat(workbook.getSheetAt(0).getLastRowNum()).isEqualTo(1);
   assertThat(workbook.getSheetAt(0).getRow(1).getCell(0).getStringCellValue()).isEqualTo("SKU-B");
  }
 }
 @Test void scopeAndPermissionRevocationAreEnforcedWithSameJwt() throws Exception {
  response("/export?warehouseId="+b.getId(),token,403);
  matrix.updateRolePermissions(ERole.ROLE_WAREHOUSE_MANAGER,Set.of(Permission.WAREHOUSE_MANAGE,Permission.REPORT_INVENTORY));
  response("/export",token,403);
  matrix.updateRolePermissions(ERole.ROLE_WAREHOUSE_MANAGER,Set.of(Permission.WAREHOUSE_VIEW));response("/export",token,200);
  matrix.updateRolePermissions(ERole.ROLE_WAREHOUSE_MANAGER,Set.of());response("/export",token,403);
  response("/export",null,401);response("/export?warehouseId="+b.getId(),admin,200);
 }
 @Test void noMatchesNoAssignmentsAndMultipleWarehousesRemainValidWorkbooks() throws Exception {
  try(var workbook=book(response("/export?keyword=nonexistent",token,200))) {assertThat(workbook.getSheetAt(0).getLastRowNum()).isZero();}
  var multi=response("/export",admin,200);
  assertThat(ContentDisposition.parse(multi.getHeader("Content-Disposition")).getFilename()).contains("KHO-A-KHO-B_2026-10-23");
  try(var workbook=book(multi)){assertThat(workbook.getSheetAt(0).getLastRowNum()).isEqualTo(8);}
  user.setWarehouses(new HashSet<>());users.saveAndFlush(user);
  try(var workbook=book(response("/export",token,200))){assertThat(workbook.getSheetAt(0).getLastRowNum()).isZero();}
 }
 @Test void malformedAndInvalidFiltersReturn400() throws Exception {
  response("/export?warehouseId=abc",token,400);response("/export?categoryId=-1",token,400);
  response("/export?stockStatus=BOGUS",token,400);response("/export?belowMinimum=bogus",token,400);
  response("/export?keyword="+"x".repeat(151),token,400);
 }
 @Test void exportIncludesAllBatchesAndNeverSilentlyTruncates() throws Exception {
  var rows=new ArrayList<Product>();
  for(int i=0;i<1002;i++)rows.add(Product.builder().code(String.format("BULK-%04d",i)).name("Hàng "+i).category(category).unit("lon").build());
  products.saveAllAndFlush(rows);
  try(var workbook=book(response("/export?keyword=BULK-",token,200))) {
   var sheet=workbook.getSheetAt(0);assertThat(sheet.getLastRowNum()).isEqualTo(1002);
   assertThat(sheet.getRow(1002).getCell(0).getStringCellValue()).isEqualTo("BULK-1001");
  }
  rows.clear();
  for(int i=1002;i<1501;i++)rows.add(Product.builder().code(String.format("BULK-%04d",i)).name("Hàng "+i).category(category).unit("lon").build());
  products.saveAllAndFlush(rows);
  var temporaryBefore = temporarySheets();
  response("/export?keyword=BULK-",token,400);
  assertThat(temporarySheets()).isEqualTo(temporaryBefore);
 }
 private Set<String> temporarySheets() throws Exception {
  var folder=java.nio.file.Path.of(System.getProperty("java.io.tmpdir"),"poifiles");
  if(!java.nio.file.Files.isDirectory(folder))return Set.of();
  try(var files=java.nio.file.Files.list(folder)) {
   return files.map(path->path.getFileName().toString()).filter(name->name.startsWith("poi-sxssf-sheet"))
    .collect(java.util.stream.Collectors.toSet());
  }
 }
}