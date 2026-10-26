package com.quanlybanhang.service;

import com.quanlybanhang.dto.request.ProductImportRowRequest;
import com.quanlybanhang.dto.request.ProductRequest;
import com.quanlybanhang.dto.response.ProductImportResponse;
import com.quanlybanhang.dto.response.ProductImportRowResponse;
import com.quanlybanhang.dto.response.ProductResponse;
import com.quanlybanhang.exception.ProductHasTransactionsException;
import com.quanlybanhang.model.Category;
import com.quanlybanhang.model.Product;
import com.quanlybanhang.repository.CategoryRepository;
import com.quanlybanhang.repository.ProductRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProductService - Quản lý danh mục sản phẩm (S2-05)")
class ProductServiceTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private com.quanlybanhang.repository.ProductPriceHistoryRepository productPriceHistoryRepository;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private ProductExcelService productExcelService;

    @Mock
    private com.quanlybanhang.repository.OrderItemRepository orderItemRepository;

    @Mock
    private com.quanlybanhang.repository.WarehouseVoucherItemRepository warehouseVoucherItemRepository;

    @Mock
    private com.quanlybanhang.repository.WarehouseLotInventoryRepository lotInventoryRepository;

    @InjectMocks
    private ProductServiceImpl productService;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void mockSalesManagerAuth() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("sales.mgr", "pass",
                        List.of(new SimpleGrantedAuthority("ROLE_SALES_MANAGER"), new SimpleGrantedAuthority("FINANCE_VIEW_COST_PRICE")))
        );
    }

    private void mockSalesStaffAuth() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("sales.staff", "pass",
                        List.of(new SimpleGrantedAuthority("ROLE_SALES")))
        );
    }

    @Test
    @DisplayName("Tạo sản phẩm mới thành công với đầy đủ mã SKU, quy cách đóng gói, giá vốn (với Quản lý kinh doanh)")
    void createProduct_SalesManager_Success() {
        mockSalesManagerAuth();

        Category category = Category.builder().id(10L).code("NC-PHONE").name("Smartphone").build();
        ProductRequest request = ProductRequest.builder()
                .sku("SP-IP15")
                .name("iPhone 15")
                .categoryId(10L)
                .price(new BigDecimal("25000000"))
                .costPrice(new BigDecimal("20000000"))
                .unit("chiếc")
                .packingSpecification("Hộp 1 máy kèm cáp")
                .imageUrl("https://example.com/ip15.jpg")
                .active(true)
                .build();

        when(productRepository.existsByCodeIgnoreCase("SP-IP15")).thenReturn(false);
        when(categoryRepository.findById(10L)).thenReturn(Optional.of(category));
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> {
            Product p = invocation.getArgument(0);
            p.setId(100L);
            return p;
        });

        ProductResponse response = productService.create(request);

        assertThat(response).isNotNull();
        assertThat(response.getCode()).isEqualTo("SP-IP15");
        assertThat(response.getSku()).isEqualTo("SP-IP15");
        assertThat(response.getCategoryId()).isEqualTo(10L);
        assertThat(response.getCategoryName()).isEqualTo("Smartphone");
        assertThat(response.getPrice()).isEqualByComparingTo(new BigDecimal("25000000"));
        assertThat(response.getCostPrice()).isEqualByComparingTo(new BigDecimal("20000000"));
        assertThat(response.getUnit()).isEqualTo("chiếc");
        assertThat(response.getPackingSpecification()).isEqualTo("Hộp 1 máy kèm cáp");
        assertThat(response.getImageUrl()).isEqualTo("https://example.com/ip15.jpg");
        assertThat(response.getActive()).isTrue();
        assertThat(response.getStatus()).isEqualTo("Đang kinh doanh");
    }

    @Test
    @DisplayName("Giá vốn bị ẩn (null) khi người xem không phải Quản lý kinh doanh")
    void getById_NonSalesManager_HidesCostPrice() {
        mockSalesStaffAuth();

        Category category = Category.builder().id(10L).code("CAT10").name("Nhóm 10").build();
        Product product = Product.builder()
                .id(1L)
                .code("SP01")
                .name("iPhone 15")
                .category(category)
                .price(new BigDecimal("25000000"))
                .costPrice(new BigDecimal("20000000"))
                .revenue(BigDecimal.ZERO)
                .unit("chiếc")
                .active(true)
                .hasTransactions(false)
                .build();

        when(productRepository.findById(1L)).thenReturn(Optional.of(product));

        ProductResponse response = productService.getById(1L);

        assertThat(response).isNotNull();
        assertThat(response.getPrice()).isEqualByComparingTo(new BigDecimal("25000000"));
        assertThat(response.getCostPrice()).isNull(); // Bảo mật: giá vốn chỉ Quản lý kinh doanh xem được
    }

    @Test
    @DisplayName("Tạo sản phẩm thất bại khi trùng mã SKU")
    void createProduct_DuplicateSku_ThrowsException() {
        ProductRequest request = ProductRequest.builder().sku("SP01").name("iPhone 15").categoryId(1L).build();
        when(productRepository.existsByCodeIgnoreCase("SP01")).thenReturn(true);

        assertThatThrownBy(() -> productService.create(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("đã tồn tại");
    }

    @Test
    @DisplayName("Cập nhật sản phẩm chuyển sang nhóm hàng mới thành công")
    void updateProduct_ChangeCategory_Success() {
        mockSalesManagerAuth();

        Category oldCat = Category.builder().id(10L).code("CAT10").name("Nhóm Cũ").build();
        Category newCat = Category.builder().id(20L).code("CAT20").name("Nhóm Mới").build();
        Product product = Product.builder().id(1L).code("SP01").name("Tên Cũ").category(oldCat).build();

        ProductRequest request = ProductRequest.builder()
                .sku("SP01")
                .name("Tên Mới")
                .categoryId(20L)
                .price(new BigDecimal("100000"))
                .costPrice(new BigDecimal("70000"))
                .packingSpecification("Thùng 12 cái")
                .build();

        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        when(productRepository.existsByCodeIgnoreCaseAndIdNot("SP01", 1L)).thenReturn(false);
        when(categoryRepository.findById(20L)).thenReturn(Optional.of(newCat));
        when(productRepository.save(any(Product.class))).thenReturn(product);

        ProductResponse response = productService.update(1L, request);

        assertThat(response.getName()).isEqualTo("Tên Mới");
        assertThat(product.getCategory()).isEqualTo(newCat);
        assertThat(product.getCostPrice()).isEqualByComparingTo(new BigDecimal("70000"));
        assertThat(product.getPackingSpecification()).isEqualTo("Thùng 12 cái");
    }

    @Test
    @DisplayName("Người không phải Quản lý kinh doanh không sửa được giá vốn khi cập nhật sản phẩm")
    void updateProduct_NonSalesManager_PreservesExistingCostPrice() {
        mockSalesStaffAuth();

        Category cat = Category.builder().id(10L).code("CAT10").name("Nhóm 10").build();
        Product product = Product.builder()
                .id(1L)
                .code("SP01")
                .name("Tên Cũ")
                .category(cat)
                .costPrice(new BigDecimal("15000000"))
                .build();

        // Cố tình truyền costPrice mới
        ProductRequest request = ProductRequest.builder()
                .sku("SP01")
                .name("Tên Mới")
                .categoryId(10L)
                .costPrice(new BigDecimal("99999999"))
                .build();

        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        when(productRepository.existsByCodeIgnoreCaseAndIdNot("SP01", 1L)).thenReturn(false);
        when(categoryRepository.findById(10L)).thenReturn(Optional.of(cat));
        when(productRepository.save(any(Product.class))).thenReturn(product);

        productService.update(1L, request);

        // Giá vốn gốc 15000000 vẫn được bảo toàn
        assertThat(product.getCostPrice()).isEqualByComparingTo(new BigDecimal("15000000"));
    }

    @Test
    @DisplayName("Sản phẩm đã phát sinh giao dịch thì KHÔNG xoá được, ném ProductHasTransactionsException")
    void deleteProduct_WithTransactions_ThrowsException() {
        Product product = Product.builder()
                .id(1L)
                .code("SP-BAN-CHAY")
                .name("Sản phẩm đã bán")
                .revenue(new BigDecimal("1000000"))
                .hasTransactions(true)
                .build();

        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        when(orderItemRepository.existsByProductId(1L)).thenReturn(true);

        assertThatThrownBy(() -> productService.delete(1L))
                .isInstanceOf(ProductHasTransactionsException.class)
                .hasMessageContaining("đã phát sinh giao dịch không thể xoá, chỉ có thể chuyển sang trạng thái ngừng kinh doanh");

        verify(productRepository, never()).delete(any());
    }

    @Test
    void realWarehouseHistoryProtectsProductEvenWhenLegacyFlagIsFalse() {
        Product product = Product.builder().id(5L).code("WAREHOUSE-5").hasTransactions(false).build();
        when(productRepository.findById(5L)).thenReturn(Optional.of(product));
        when(warehouseVoucherItemRepository.existsByProductId(5L)).thenReturn(true);
        assertThatThrownBy(() -> productService.delete(5L)).isInstanceOf(ProductHasTransactionsException.class);
        verify(productRepository, never()).delete(any());
    }

    @Test
    void legacySeedRevenueAndFlagDoNotFabricateSalesOrTransactions() {
        Product product = Product.builder().id(5L).code("LEGACY-5")
                .revenue(new BigDecimal("150000000")).hasTransactions(true).build();
        when(productRepository.findById(5L)).thenReturn(Optional.of(product));
        ProductResponse response = productService.getById(5L);
        assertThat(response.getRevenue()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(response.getHasTransactions()).isFalse();
        assertThat(response.getCanDelete()).isTrue();
        productService.delete(5L);
        verify(productRepository).delete(product);
    }

    @Test
    void productRevenueComesFromRecordedOrderLines() {
        Product product = Product.builder().id(5L).code("LEGACY-5").revenue(new BigDecimal("150000000")).build();
        when(productRepository.findById(5L)).thenReturn(Optional.of(product));
        when(productRepository.sumRevenueByProductId(5L)).thenReturn(new BigDecimal("12345.67"));
        assertThat(productService.getById(5L).getRevenue()).isEqualByComparingTo("12345.67");
    }

    @Test
    void catalogLoadsRealStatisticsInBatches() {
        Product first = Product.builder().id(1L).code("CATALOG-1").build();
        Product second = Product.builder().id(2L).code("CATALOG-2").build();
        ProductRepository.ProductRevenue revenue = mock(ProductRepository.ProductRevenue.class);
        when(revenue.getProductId()).thenReturn(1L);
        when(revenue.getRevenue()).thenReturn(new BigDecimal("123.45"));
        when(productRepository.findAll()).thenReturn(List.of(first, second));
        when(productRepository.sumRevenueByProductIds(List.of(1L, 2L))).thenReturn(List.of(revenue));
        when(warehouseVoucherItemRepository.findProductIdsWithTransactions(List.of(1L, 2L)))
                .thenReturn(java.util.Set.of(1L));
        List<ProductResponse> products = productService.getAll(null);
        assertThat(products.get(0).getRevenue()).isEqualByComparingTo("123.45");
        assertThat(products.get(0).getCanDelete()).isFalse();
        assertThat(products.get(1).getRevenue()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(products.get(1).getCanDelete()).isTrue();
        verify(productRepository, never()).sumRevenueByProductId(any());
        verify(orderItemRepository, never()).existsByProductId(any());
        verify(warehouseVoucherItemRepository, never()).existsByProductId(any());
    }

    @Test
    @DisplayName("Sản phẩm chưa phát sinh giao dịch xoá thành công")
    void deleteProduct_NoTransactions_Success() {
        Product product = Product.builder()
                .id(2L)
                .code("SP-MOI")
                .name("Sản phẩm chưa bán")
                .revenue(BigDecimal.ZERO)
                .hasTransactions(false)
                .build();

        when(productRepository.findById(2L)).thenReturn(Optional.of(product));

        productService.delete(2L);

        verify(productRepository).delete(product);
    }

    @Test
    @DisplayName("Cập nhật trạng thái ngừng kinh doanh thành công")
    void updateStatus_DiscontinueProduct_Success() {
        Product product = Product.builder()
                .id(1L)
                .code("SP01")
                .name("Sản phẩm 1")
                .active(true)
                .build();

        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        when(productRepository.save(any(Product.class))).thenReturn(product);

        ProductResponse response = productService.updateStatus(1L, false);

        assertThat(product.getActive()).isFalse();
        assertThat(response.getActive()).isFalse();
        assertThat(response.getStatus()).isEqualTo("Ngừng kinh doanh");
    }

    @Test
    @DisplayName("Lấy danh sách sản phẩm có bộ lọc tìm kiếm và trạng thái")
    void getAll_WithFilters_ReturnsFilteredList() {
        Category cat = Category.builder().id(10L).code("CAT10").name("Nhóm 10").build();
        Product p1 = Product.builder().id(1L).code("SP-IP15").name("iPhone 15").category(cat).active(true).build();
        Product p2 = Product.builder().id(2L).code("SP-S24").name("Samsung S24").category(cat).active(false).build();

        when(productRepository.findByCategoryId(10L)).thenReturn(List.of(p1, p2));

        // Lọc active = true
        List<ProductResponse> activeList = productService.getAll(10L, null, true);
        assertThat(activeList).hasSize(1);
        assertThat(activeList.get(0).getCode()).isEqualTo("SP-IP15");

        // Tìm kiếm theo từ khoá "Samsung"
        List<ProductResponse> searchList = productService.getAll(10L, "samsung", null);
        assertThat(searchList).hasSize(1);
        assertThat(searchList.get(0).getCode()).isEqualTo("SP-S24");
    }

    @Test
    @DisplayName("S2-08: Tải tệp Excel mẫu thành công")
    void generateImportTemplate_Success() {
        Category cat = Category.builder().id(1L).code("BEV").name("Đồ uống").build();
        when(categoryRepository.findAllByOrderByLevelAscNameAsc()).thenReturn(List.of(cat));
        when(productExcelService.generateTemplate(List.of(cat))).thenReturn(new byte[]{1, 2, 3});

        byte[] result = productService.generateImportTemplate();

        assertThat(result).isNotNull().isEqualTo(new byte[]{1, 2, 3});
        verify(productExcelService).generateTemplate(List.of(cat));
    }

    @Test
    void importBlankOptionalCellsPreservesExistingPriceUnitAndInactiveStatus() {
        Category category = Category.builder().id(1L).code("CAT").name("Nhóm").build();
        Product existing = Product.builder().id(1L).code("EXISTING").name("Cũ").category(category)
                .price(new BigDecimal("12000")).unit("chai").active(false).build();
        when(categoryRepository.findAll()).thenReturn(List.of(category));
        when(productRepository.findByCodeUpperIn(anyCollection())).thenReturn(List.of(existing));
        when(productRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
        var row = ProductImportRowRequest.builder().sku("existing").name("Mới").category("CAT").build();
        var result = productService.importProducts(List.of(row));
        assertThat(result.getImportedRows()).isEqualTo(1);
        assertThat(existing.getName()).isEqualTo("Mới");
        assertThat(existing.getPrice()).isEqualByComparingTo("12000");
        assertThat(existing.getUnit()).isEqualTo("chai");
        assertThat(existing.getActive()).isFalse();
    }

    @Test
    void excelParseErrorsAndMoneyOverflowPreventSavingThoseRows() {
        Category category = Category.builder().id(1L).code("CAT").name("Nhóm").build();
        when(categoryRepository.findAll()).thenReturn(List.of(category));
        var badFormat = ProductImportRowRequest.builder().sku("BAD").name("Sai giá").category("CAT")
                .parseErrors(List.of("Giá bán không phải số hợp lệ")).build();
        var overflow = ProductImportRowRequest.builder().sku("OVERFLOW").name("Quá lớn").category("CAT")
                .price(new BigDecimal("100000000000000")).build();
        var precision = ProductImportRowRequest.builder().sku("PRECISION").name("Quá lẻ").category("CAT")
                .price(new BigDecimal("1.2345")).build();
        var result = productService.importProducts(List.of(badFormat, overflow, precision));
        assertThat(result.getInvalidRows()).isEqualTo(3);
        assertThat(result.getImportedRows()).isZero();
        assertThat(result.getRows().get(0).getErrors()).contains("Giá bán không phải số hợp lệ");
        assertThat(result.getRows().get(1).getErrors()).anyMatch(error -> error.contains("giới hạn lưu trữ"));
        assertThat(result.getRows().get(2).getErrors()).anyMatch(error -> error.contains("2 chữ số"));
        verify(productRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("S2-08: Xem trước phát hiện lỗi từng dòng (SKU trống, Tên trống, Nhóm hàng không tồn tại, SKU trùng trong tệp)")
    void previewImport_DetectsRowErrors() {
        Category cat = Category.builder().id(1L).code("BEV").name("Đồ uống").build();
        when(categoryRepository.findAll()).thenReturn(List.of(cat));
        when(productRepository.findByCodeUpperIn(anyCollection())).thenReturn(List.of());

        List<ProductImportRowRequest> rows = List.of(
                ProductImportRowRequest.builder().rowNumber(2).sku("").name("SP 1").category("BEV").build(),
                ProductImportRowRequest.builder().rowNumber(3).sku("SP02").name("").category("BEV").build(),
                ProductImportRowRequest.builder().rowNumber(4).sku("SP03").name("SP 3").category("UNKNOWN_CAT").build(),
                ProductImportRowRequest.builder().rowNumber(5).sku("SP04").name("SP 4").category("BEV").price(new BigDecimal("-100")).build(),
                ProductImportRowRequest.builder().rowNumber(6).sku("SP05").name("SP 5").category("BEV").build(),
                ProductImportRowRequest.builder().rowNumber(7).sku("SP05").name("SP 5 trùng").category("BEV").build()
        );

        ProductImportResponse response = productService.previewImport(rows);

        assertThat(response.getTotalRows()).isEqualTo(6);
        assertThat(response.getValidRows()).isEqualTo(1); // Chỉ dòng 6 hợp lệ
        assertThat(response.getInvalidRows()).isEqualTo(5);

        // Dòng 2: thiếu SKU
        assertThat(response.getRows().get(0).isValid()).isFalse();
        assertThat(response.getRows().get(0).getErrors()).anyMatch(e -> e.contains("Mã SKU không được để trống"));

        // Dòng 3: thiếu Tên
        assertThat(response.getRows().get(1).isValid()).isFalse();
        assertThat(response.getRows().get(1).getErrors()).anyMatch(e -> e.contains("Tên sản phẩm không được để trống"));

        // Dòng 4: sai Nhóm hàng
        assertThat(response.getRows().get(2).isValid()).isFalse();
        assertThat(response.getRows().get(2).getErrors()).anyMatch(e -> e.contains("Không tìm thấy nhóm hàng"));

        // Dòng 5: giá bán âm
        assertThat(response.getRows().get(3).isValid()).isFalse();
        assertThat(response.getRows().get(3).getErrors()).anyMatch(e -> e.contains("Giá bán không được âm"));

        // Dòng 7: trùng SKU với dòng 6
        assertThat(response.getRows().get(5).isValid()).isFalse();
        assertThat(response.getRows().get(5).getErrors()).anyMatch(e -> e.contains("trùng lặp với dòng 6"));
    }

    @Test
    @DisplayName("S2-08: Xem trước đánh dấu rõ UPDATE khi SKU đã tồn tại và CREATE khi SKU chưa tồn tại")
    void previewImport_MarksUpdateAndCreateAccurately() {
        Category cat = Category.builder().id(1L).code("BEV").name("Đồ uống").build();
        Product existing = Product.builder().id(100L).code("SP-EXIST").name("Sản phẩm cũ").category(cat).build();

        when(categoryRepository.findAll()).thenReturn(List.of(cat));
        when(productRepository.findByCodeUpperIn(anyCollection())).thenReturn(List.of(existing));

        List<ProductImportRowRequest> rows = List.of(
                ProductImportRowRequest.builder().rowNumber(2).sku("SP-EXIST").name("Cập nhật SP cũ").category("BEV").build(),
                ProductImportRowRequest.builder().rowNumber(3).sku("SP-NEW").name("Tạo SP mới").category("BEV").build()
        );

        ProductImportResponse response = productService.previewImport(rows);

        assertThat(response.getTotalRows()).isEqualTo(2);
        assertThat(response.getValidRows()).isEqualTo(2);
        assertThat(response.getUpdateCount()).isEqualTo(1);
        assertThat(response.getCreateCount()).isEqualTo(1);

        // Dòng 1: tồn tại -> action = "UPDATE", isUpdate = true
        ProductImportRowResponse r1 = response.getRows().get(0);
        assertThat(r1.isUpdate()).isTrue();
        assertThat(r1.getAction()).isEqualTo("UPDATE");
        assertThat(r1.getExistingProductId()).isEqualTo(100L);

        // Dòng 2: mới -> action = "CREATE", isUpdate = false
        ProductImportRowResponse r2 = response.getRows().get(1);
        assertThat(r2.isUpdate()).isFalse();
        assertThat(r2.getAction()).isEqualTo("CREATE");
        assertThat(r2.getExistingProductId()).isNull();
    }

    @Test
    @DisplayName("S2-08: Nhập hàng loạt - cập nhật sản phẩm tồn tại và tạo mới sản phẩm chưa có")
    void importProducts_UpdatesExistingAndCreatesNew_Success() {
        mockSalesManagerAuth();

        Category cat = Category.builder().id(1L).code("BEV").name("Đồ uống").build();
        Product existing = Product.builder()
                .id(100L)
                .code("SP-EXIST")
                .name("Tên cũ")
                .category(cat)
                .price(new BigDecimal("10000"))
                .costPrice(new BigDecimal("8000"))
                .active(true)
                .build();

        when(categoryRepository.findAll()).thenReturn(List.of(cat));
        when(productRepository.findByCodeUpperIn(anyCollection())).thenReturn(List.of(existing));
        when(productRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        List<ProductImportRowRequest> rows = List.of(
                ProductImportRowRequest.builder()
                        .rowNumber(2)
                        .sku("SP-EXIST")
                        .name("Tên mới sau cập nhật")
                        .category("BEV")
                        .price(new BigDecimal("12000"))
                        .costPrice(new BigDecimal("9000"))
                        .unit("lon")
                        .build(),
                ProductImportRowRequest.builder()
                        .rowNumber(3)
                        .sku("SP-NEW")
                        .name("Sản phẩm mới toanh")
                        .category("BEV")
                        .price(new BigDecimal("20000"))
                        .costPrice(new BigDecimal("15000"))
                        .unit("chai")
                        .build()
        );

        ProductImportResponse result = productService.importProducts(rows);

        assertThat(result.getImportedRows()).isEqualTo(2);
        assertThat(result.getUpdateCount()).isEqualTo(1);
        assertThat(result.getCreateCount()).isEqualTo(1);

        // Kiểm tra audit log thay đổi giá bán cho sản phẩm cũ
        verify(auditLogService).logAction(eq("PRICE"), eq("100"), eq("UPDATE"), eq("10000"), eq("12000"), any());

        // Kiểm tra sản phẩm cũ đã được cập nhật giá trị
        assertThat(existing.getName()).isEqualTo("Tên mới sau cập nhật");
        assertThat(existing.getPrice()).isEqualTo(new BigDecimal("12000"));
        assertThat(existing.getCostPrice()).isEqualTo(new BigDecimal("9000"));
        assertThat(existing.getUnit()).isEqualTo("lon");

        verify(productRepository).saveAll(anyList());
    }

    @Test
    @DisplayName("S2-08: Bảo mật giá vốn - Nhân viên không phải Quản lý kinh doanh không sửa được giá vốn khi nhập")
    void importProducts_PreservesCostPriceForNonSalesManager() {
        mockSalesStaffAuth(); // Vai trò ROLE_SALES

        Category cat = Category.builder().id(1L).code("BEV").name("Đồ uống").build();
        Product existing = Product.builder()
                .id(100L)
                .code("SP-EXIST")
                .name("Sản phẩm cũ")
                .category(cat)
                .price(new BigDecimal("10000"))
                .costPrice(new BigDecimal("8000")) // Giá vốn gốc
                .active(true)
                .build();

        when(categoryRepository.findAll()).thenReturn(List.of(cat));
        when(productRepository.findByCodeUpperIn(anyCollection())).thenReturn(List.of(existing));
        when(productRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        List<ProductImportRowRequest> rows = List.of(
                ProductImportRowRequest.builder()
                        .rowNumber(2)
                        .sku("SP-EXIST")
                        .name("Sản phẩm sửa tên")
                        .category("BEV")
                        .costPrice(new BigDecimal("99999")) // Thử sửa giá vốn
                        .build()
        );

        ProductImportResponse result = productService.importProducts(rows);

        assertThat(result.getImportedRows()).isEqualTo(1);
        // Giá vốn gốc 8000 PHẢI ĐƯỢC GIỮ NGUYÊN, không bị đổi thành 99999
        assertThat(existing.getCostPrice()).isEqualTo(new BigDecimal("8000"));
    }
}

