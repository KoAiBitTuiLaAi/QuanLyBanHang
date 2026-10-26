package com.quanlybanhang.service;

import com.quanlybanhang.dto.response.*;
import com.quanlybanhang.model.*;
import com.quanlybanhang.repository.*;
import com.quanlybanhang.service.impl.InventoryTurnoverReportServiceImpl;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit test cho Báo cáo tồn kho và vòng quay hàng (S8-07):
 * - Giá trị tồn theo kho và theo nhóm hàng
 * - Số ngày tồn bình quân của từng SKU, chỉ ra hàng chậm luân chuyển đang chôn vốn
 * - Xuất Excel (.xlsx)
 */
@ExtendWith(MockitoExtension.class)
class InventoryTurnoverReportServiceTest {

    @Mock
    private WarehouseRepository warehouseRepository;
    @Mock
    private CategoryRepository categoryRepository;
    @Mock
    private ProductRepository productRepository;
    @Mock
    private WarehouseInventoryRepository inventoryRepository;
    @Mock
    private WarehouseVoucherRepository voucherRepository;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private InventoryTurnoverReportServiceImpl reportService;

    private final InventoryTurnoverExcelExportService excelExportService = new InventoryTurnoverExcelExportService();

    private Warehouse warehouseNorth;
    private Warehouse warehouseSouth;
    private Category catBeverage;
    private Category catFood;
    private Product fastProduct;
    private Product slowProduct;
    private Product deadProduct;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "warehouse_mgr",
                        "N/A",
                        List.of(
                                new SimpleGrantedAuthority("ROLE_WAREHOUSE_MANAGER"),
                                new SimpleGrantedAuthority("REPORT_INVENTORY")
                        )
                )
        );

        warehouseNorth = Warehouse.builder()
                .id(1L)
                .code("KHO-BAC")
                .name("Kho Miền Bắc")
                .active(true)
                .build();

        warehouseSouth = Warehouse.builder()
                .id(2L)
                .code("KHO-NAM")
                .name("Kho Miền Nam")
                .active(true)
                .build();

        catBeverage = Category.builder()
                .id(10L)
                .code("NHOM-DO-UONG")
                .name("Đồ uống")
                .level(1)
                .build();

        catFood = Category.builder()
                .id(20L)
                .code("NHOM-THUC-PHAM")
                .name("Thực phẩm khô")
                .level(1)
                .build();

        fastProduct = Product.builder()
                .id(101L)
                .code("SKU-FAST-01")
                .name("Nước tăng lực lon 330ml")
                .unit("Lon")
                .costPrice(new BigDecimal("10000"))
                .price(new BigDecimal("15000"))
                .category(catBeverage)
                .active(true)
                .build();

        slowProduct = Product.builder()
                .id(102L)
                .code("SKU-SLOW-02")
                .name("Trà thảo mộc cao cấp")
                .unit("Chai")
                .costPrice(new BigDecimal("25000"))
                .price(new BigDecimal("35000"))
                .category(catBeverage)
                .active(true)
                .build();

        deadProduct = Product.builder()
                .id(103L)
                .code("SKU-DEAD-03")
                .name("Bánh quy hộp thiếc")
                .unit("Hộp")
                .costPrice(new BigDecimal("100000"))
                .price(new BigDecimal("140000"))
                .category(catFood)
                .active(true)
                .build();

        lenient().when(userRepository.findByUsername("warehouse_mgr")).thenReturn(Optional.empty());
        lenient().when(warehouseRepository.findAllByOrderByCodeAsc())
                .thenReturn(List.of(warehouseNorth, warehouseSouth));
        lenient().when(warehouseRepository.findById(1L)).thenReturn(Optional.of(warehouseNorth));
        lenient().when(warehouseRepository.findById(2L)).thenReturn(Optional.of(warehouseSouth));
        lenient().when(categoryRepository.findAllByOrderByLevelAscNameAsc())
                .thenReturn(List.of(catBeverage, catFood));
        lenient().when(productRepository.findAll())
                .thenReturn(List.of(fastProduct, slowProduct, deadProduct));
        lenient().when(orderRepository.findAll()).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("S8-07: Tính đúng giá trị tồn theo kho, theo nhóm hàng, số ngày tồn bình quân SKU và phát hiện hàng chậm luân chuyển đang chôn vốn")
    void shouldCalculateValuationByWarehouseAndCategoryAndIdentifySlowMovingSkus() {
        LocalDate fromDate = LocalDate.of(2026, 3, 1);
        LocalDate toDate = LocalDate.of(2026, 3, 30); // 30 ngày

        // Tồn hiện tại cuối kỳ:
        // 1. fastProduct tại Kho Bắc: tồn cuối = 100 lon (đơn giá vốn 10,000 => giá trị 1,000,000)
        //    Trong kỳ xuất 300 lon => tồn đầu kỳ = 400 lon, tồn bình quân = 250 lon
        //    Số ngày tồn bình quân = (250 * 30) / 300 = 25.00 ngày (< ngưỡng 60 ngày => KHÔNG chậm luân chuyển)
        WarehouseInventory invFastNorth = WarehouseInventory.builder()
                .id(1L)
                .warehouse(warehouseNorth)
                .product(fastProduct)
                .baseQuantity(new BigDecimal("100"))
                .reservedQuantity(new BigDecimal("10"))
                .build();

        // 2. slowProduct tại Kho Bắc: tồn cuối = 180 chai (đơn giá vốn 25,000 => giá trị 4,500,000)
        //    Trong kỳ chỉ xuất 20 chai => tồn đầu kỳ = 200 chai, tồn bình quân = 190 chai
        //    Số ngày tồn bình quân = (190 * 30) / 20 = 285.00 ngày (>= ngưỡng 60 ngày => CHẬM LUÂN CHUYỂN, chôn vốn 4,500,000)
        WarehouseInventory invSlowNorth = WarehouseInventory.builder()
                .id(2L)
                .warehouse(warehouseNorth)
                .product(slowProduct)
                .baseQuantity(new BigDecimal("180"))
                .reservedQuantity(BigDecimal.ZERO)
                .build();

        // 3. deadProduct tại Kho Nam: tồn cuối = 50 hộp (đơn giá vốn 100,000 => giá trị 5,000,000)
        //    Trong kỳ xuất = 0 hộp => TỒN ĐỌNG KHÔNG XUẤT (DEAD_STOCK => CHẬM LUÂN CHUYỂN, chôn vốn 5,000,000)
        WarehouseInventory invDeadSouth = WarehouseInventory.builder()
                .id(3L)
                .warehouse(warehouseSouth)
                .product(deadProduct)
                .baseQuantity(new BigDecimal("50"))
                .reservedQuantity(BigDecimal.ZERO)
                .build();

        when(inventoryRepository.findAllByOrderByProduct_CodeAsc()).thenReturn(List.of(invFastNorth, invSlowNorth, invDeadSouth));

        // Phiếu xuất trong kỳ (15/03/2026) tại Kho Bắc
        WarehouseVoucher exportVoucher = WarehouseVoucher.builder()
                .id(500L)
                .voucherCode("PX-2026-001")
                .type(WarehouseVoucherType.EXPORT)
                .status("CONFIRMED")
                .warehouse(warehouseNorth)
                .voucherDate(LocalDateTime.of(2026, 3, 15, 10, 0))
                .build();

        WarehouseVoucherItem exportFastItem = WarehouseVoucherItem.builder()
                .id(501L)
                .voucher(exportVoucher)
                .product(fastProduct)
                .unit("Lon")
                .quantity(new BigDecimal("300"))
                .conversionFactor(BigDecimal.ONE)
                .baseQuantity(new BigDecimal("300"))
                .build();

        WarehouseVoucherItem exportSlowItem = WarehouseVoucherItem.builder()
                .id(502L)
                .voucher(exportVoucher)
                .product(slowProduct)
                .unit("Chai")
                .quantity(new BigDecimal("20"))
                .conversionFactor(BigDecimal.ONE)
                .baseQuantity(new BigDecimal("20"))
                .build();

        exportVoucher.setItems(List.of(exportFastItem, exportSlowItem));
        when(voucherRepository.findAll()).thenReturn(List.of(exportVoucher));

        InventoryTurnoverReportResponse report = reportService.getReport(
                null, null, fromDate, toDate, null, 60, null, false, null, false
        );

        assertThat(report.getPeriodDays()).isEqualTo(30);
        assertThat(report.getSlowMovingThresholdDays()).isEqualTo(60);

        // Tổng giá trị tồn = 1,000,000 + 4,500,000 + 5,000,000 = 10,500,000 VND
        assertThat(report.getTotalInventoryValue()).isEqualByComparingTo("10500000.00");
        // Tổng vốn chôn ở hàng chậm luân chuyển = 4,500,000 (slowProduct) + 5,000,000 (deadProduct) = 9,500,000 VND
        assertThat(report.getSlowMovingInventoryValue()).isEqualByComparingTo("9500000.00");
        assertThat(report.getSlowMovingSkuCount()).isEqualTo(2);
        assertThat(report.getDeadStockSkuCount()).isEqualTo(1);

        // Kiểm tra giá trị tồn theo kho
        List<WarehouseInventoryValuationResponse> byWarehouse = report.getWarehouseValuations();
        assertThat(byWarehouse).hasSize(2);
        WarehouseInventoryValuationResponse northVal = byWarehouse.stream()
                .filter(w -> w.getWarehouseCode().equals("KHO-BAC"))
                .findFirst()
                .orElseThrow();
        assertThat(northVal.getInventoryValue()).isEqualByComparingTo("5500000.00");
        assertThat(northVal.getSlowMovingInventoryValue()).isEqualByComparingTo("4500000.00");
        assertThat(northVal.getSlowMovingSkuCount()).isEqualTo(1);

        WarehouseInventoryValuationResponse southVal = byWarehouse.stream()
                .filter(w -> w.getWarehouseCode().equals("KHO-NAM"))
                .findFirst()
                .orElseThrow();
        assertThat(southVal.getInventoryValue()).isEqualByComparingTo("5000000.00");
        assertThat(southVal.getSlowMovingInventoryValue()).isEqualByComparingTo("5000000.00");
        assertThat(southVal.getSlowMovingSkuCount()).isEqualTo(1);

        // Kiểm tra giá trị tồn theo nhóm hàng
        List<CategoryInventoryValuationResponse> byCategory = report.getCategoryValuations();
        assertThat(byCategory).hasSize(2);
        CategoryInventoryValuationResponse bevVal = byCategory.stream()
                .filter(c -> c.getCategoryCode().equals("NHOM-DO-UONG"))
                .findFirst()
                .orElseThrow();
        assertThat(bevVal.getInventoryValue()).isEqualByComparingTo("5500000.00");
        assertThat(bevVal.getSlowMovingInventoryValue()).isEqualByComparingTo("4500000.00");

        CategoryInventoryValuationResponse foodVal = byCategory.stream()
                .filter(c -> c.getCategoryCode().equals("NHOM-THUC-PHAM"))
                .findFirst()
                .orElseThrow();
        assertThat(foodVal.getInventoryValue()).isEqualByComparingTo("5000000.00");
        assertThat(foodVal.getSlowMovingInventoryValue()).isEqualByComparingTo("5000000.00");

        // Kiểm tra chi tiết từng SKU
        SkuInventoryTurnoverResponse fastSku = report.getSkuItems().stream()
                .filter(s -> s.getProductCode().equals("SKU-FAST-01"))
                .findFirst()
                .orElseThrow();
        assertThat(fastSku.isSlowMoving()).isFalse();
        assertThat(fastSku.getAverageDaysInInventory()).isEqualByComparingTo("25.00");
        assertThat(fastSku.getTurnoverRate()).isEqualByComparingTo("1.2000");
        assertThat(fastSku.getTiedUpCapitalValue()).isEqualByComparingTo("0.00");

        SkuInventoryTurnoverResponse slowSku = report.getSkuItems().stream()
                .filter(s -> s.getProductCode().equals("SKU-SLOW-02"))
                .findFirst()
                .orElseThrow();
        assertThat(slowSku.isSlowMoving()).isTrue();
        assertThat(slowSku.getTurnoverStatus()).isEqualTo("SLOW_MOVING");
        assertThat(slowSku.getAverageDaysInInventory()).isEqualByComparingTo("285.00");
        assertThat(slowSku.getTiedUpCapitalValue()).isEqualByComparingTo("4500000.00");

        SkuInventoryTurnoverResponse deadSku = report.getSkuItems().stream()
                .filter(s -> s.getProductCode().equals("SKU-DEAD-03"))
                .findFirst()
                .orElseThrow();
        assertThat(deadSku.isSlowMoving()).isTrue();
        assertThat(deadSku.getTurnoverStatus()).isEqualTo("DEAD_STOCK");
        assertThat(deadSku.getAverageDaysInInventory()).isEqualByComparingTo("60");
        assertThat(deadSku.getTiedUpCapitalValue()).isEqualByComparingTo("5000000.00");

        // Danh sách slowMovingItems sắp xếp theo giá trị chôn vốn giảm dần
        assertThat(report.getSlowMovingItems())
                .extracting(SkuInventoryTurnoverResponse::getProductCode)
                .containsExactly("SKU-DEAD-03", "SKU-SLOW-02");
    }

    @Test
    @DisplayName("S8-07: Xuất Excel (.xlsx) tạo đủ 4 sheet và chống công thức độc hại (Formula Injection)")
    void shouldExportExcelWithFourSheetsAndSanitizeFormulas() throws Exception {
        LocalDate fromDate = LocalDate.of(2026, 3, 1);
        LocalDate toDate = LocalDate.of(2026, 3, 30);

        fastProduct.setName("=CMD|' /C calc'!A0");
        WarehouseInventory inv = WarehouseInventory.builder()
                .id(1L)
                .warehouse(warehouseNorth)
                .product(fastProduct)
                .baseQuantity(new BigDecimal("50"))
                .reservedQuantity(BigDecimal.ZERO)
                .build();
        when(inventoryRepository.findAllByOrderByProduct_CodeAsc()).thenReturn(List.of(inv));
        when(voucherRepository.findAll()).thenReturn(List.of());

        InventoryTurnoverReportResponse report = reportService.getReport(
                1L, null, fromDate, toDate, null, 60, null, false, null, false
        );

        byte[] excelBytes = excelExportService.exportToExcel(report);
        assertThat(excelBytes).isNotEmpty();

        String filename = excelExportService.buildFileName(report);
        assertThat(filename).isEqualTo("Bao-cao-ton-kho-vong-quay_KHO-BAC_2026-03-01_2026-03-30.xlsx");

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(excelBytes))) {
            assertThat(wb.getNumberOfSheets()).isEqualTo(4);
            assertThat(wb.getSheetName(0)).isEqualTo("Giá trị tồn theo kho");
            assertThat(wb.getSheetName(1)).isEqualTo("Giá trị tồn theo nhóm hàng");
            assertThat(wb.getSheetName(2)).isEqualTo("Vòng quay & Số ngày tồn SKU");
            assertThat(wb.getSheetName(3)).isEqualTo("Hàng chậm luân chuyển");

            Sheet skuSheet = wb.getSheetAt(2);
            String productNameCellValue = skuSheet.getRow(3).getCell(2).getStringCellValue();
            assertThat(productNameCellValue).startsWith("'=CMD");
        }
    }

    @Test
    @DisplayName("S8-07: Từ chối truy cập kho ngoài phạm vi được gán cho tài khoản")
    void shouldDenyWarehouseOutsideUserAssignedScope() {
        User scopedManager = User.builder()
                .id(99L)
                .username("warehouse_mgr")
                .warehouses(Set.of(warehouseNorth))
                .build();
        when(userRepository.findByUsername("warehouse_mgr")).thenReturn(Optional.of(scopedManager));

        assertThatThrownBy(() -> reportService.getReport(
                2L, null, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 30),
                null, 60, null, false, null, false
        ))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("quyền xem báo cáo của kho này");
    }

    @Test
    @DisplayName("S8-07: Không có quyền REPORT_INVENTORY (dù có ROLE_WAREHOUSE_MANAGER) bị chặn ngay ở tầng service")
    void shouldDenyWhenReportInventoryAuthorityMissingEvenWithWarehouseManagerRole() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "warehouse_mgr_revoked",
                        "N/A",
                        List.of(new SimpleGrantedAuthority("ROLE_WAREHOUSE_MANAGER"))
                )
        );

        assertThatThrownBy(() -> reportService.getOptions())
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("REPORT_INVENTORY");
    }
}
