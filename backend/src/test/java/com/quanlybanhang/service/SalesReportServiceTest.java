package com.quanlybanhang.service;

import com.quanlybanhang.dto.request.SalesReportFilterRequest;
import com.quanlybanhang.dto.response.SalesReportOptionsResponse;
import com.quanlybanhang.dto.response.SalesReportPageResponse;
import com.quanlybanhang.dto.response.SalesReportRowResponse;
import com.quanlybanhang.model.*;
import com.quanlybanhang.repository.*;
import com.quanlybanhang.service.impl.SalesReportServiceImpl;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Unit test: Báo cáo doanh số đa chiều (S8-06)")
class SalesReportServiceTest {

    @Mock private OrderItemRepository orderItemRepository;
    @Mock private DealerRepository dealerRepository;
    @Mock private TerritoryRepository territoryRepository;
    @Mock private CategoryRepository categoryRepository;
    @Mock private UserRepository userRepository;
    @Mock private SalesReportExcelExportService excelExportService;

    @InjectMocks
    private SalesReportServiceImpl salesReportService;

    private User salesManagerUser;
    private User salesUser;
    private Territory territoryNorth;
    private Territory territorySouth;
    private Dealer dealerA;
    private Dealer dealerB;
    private Category catBeverage;
    private Category catFood;
    private Product prodCola;
    private Product prodSnack;

    @BeforeEach
    void setUp() {
        Role mgrRole = Role.builder().name(ERole.ROLE_SALES_MANAGER).build();
        Role salesRole = Role.builder().name(ERole.ROLE_SALES).build();

        salesManagerUser = User.builder()
                .id(10L)
                .username("sales.mgr")
                .fullName("Quản lý Kinh doanh")
                .roles(Set.of(mgrRole))
                .active(true)
                .build();

        salesUser = User.builder()
                .id(11L)
                .username("sales.rep")
                .fullName("Nhân viên Sales")
                .roles(Set.of(salesRole))
                .active(true)
                .build();

        territoryNorth = Territory.builder().id(1L).code("MB").name("Miền Bắc").active(true).build();
        territorySouth = Territory.builder().id(2L).code("MN").name("Miền Nam").active(true).build();

        dealerA = Dealer.builder()
                .id(101L)
                .code("DL-01")
                .name("Đại lý Hải Đăng")
                .territory(territoryNorth)
                .assignedUser(salesManagerUser)
                .active(true)
                .build();

        dealerB = Dealer.builder()
                .id(102L)
                .code("DL-02")
                .name("Đại lý Phương Nam")
                .territory(territorySouth)
                .assignedUser(salesUser)
                .active(true)
                .build();

        catBeverage = Category.builder().id(1001L).code("CAT-BEV").name("Nước giải khát").level(1).build();
        catFood = Category.builder().id(1002L).code("CAT-FOOD").name("Bánh kẹo").level(1).build();

        prodCola = Product.builder()
                .id(2001L)
                .code("SKU-COLA")
                .name("Nước ngọt Cola 330ml")
                .category(catBeverage)
                .price(new BigDecimal("10000"))
                .costPrice(new BigDecimal("7000"))
                .unit("lon")
                .build();

        prodSnack = Product.builder()
                .id(2002L)
                .code("SKU-SNACK")
                .name("Snack khoai tây 50g")
                .category(catFood)
                .price(new BigDecimal("15000"))
                .costPrice(new BigDecimal("9000"))
                .unit("gói")
                .build();

        lenient().when(territoryRepository.findAllByActiveTrueOrderByNameAsc()).thenReturn(List.of(territoryNorth, territorySouth));
        lenient().when(dealerRepository.findByActiveTrueOrderByNameAsc()).thenReturn(List.of(dealerA, dealerB));
        lenient().when(categoryRepository.findAllByOrderByLevelAscNameAsc()).thenReturn(List.of(catBeverage, catFood));
        lenient().when(categoryRepository.findAll()).thenReturn(List.of(catBeverage, catFood));
        lenient().when(userRepository.findAll()).thenReturn(List.of(salesManagerUser, salesUser));
    }

    private void authenticate(String username, String... authorities) {
        var authList = java.util.Arrays.stream(authorities)
                .map(SimpleGrantedAuthority::new)
                .toList();
        var token = new UsernamePasswordAuthenticationToken(username, "pass", authList);
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    private Order createOrder(Long id, String code, Dealer dealer, User creator, LocalDateTime recordedAt) {
        return Order.builder()
                .id(id)
                .orderCode(code)
                .dealer(dealer)
                .createdBy(creator != null ? creator.getUsername() : "system")
                .status("APPROVED")
                .recordedAt(recordedAt)
                .createdAt(recordedAt)
                .build();
    }

    private OrderItem createItem(Long id, Order order, Product product, BigDecimal qty, BigDecimal totalAmount) {
        return OrderItem.builder()
                .id(id)
                .order(order)
                .product(product)
                .unit(product.getUnit())
                .quantity(qty)
                .baseQuantity(qty)
                .totalAmount(totalAmount)
                .subtotalAmount(totalAmount)
                .build();
    }

    @Test
    @DisplayName("Chưa xác thực ném AccessDeniedException")
    void unauthenticatedThrowsAccessDenied() {
        SecurityContextHolder.clearContext();
        SalesReportFilterRequest filter = SalesReportFilterRequest.builder().build();

        assertThatThrownBy(() -> salesReportService.getReport(filter))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("getOptions trả về đầy đủ danh sách khu vực, đại lý, nhóm hàng và nhân viên")
    void getOptions_returnsAllOptions() {
        authenticate("sales.mgr", "REPORT_SALES");

        SalesReportOptionsResponse options = salesReportService.getOptions();

        assertThat(options.getRegions()).hasSize(2);
        assertThat(options.getDealers()).hasSize(2);
        assertThat(options.getCategories()).hasSize(2);
        assertThat(options.getEmployees()).hasSize(2);
    }

    @Test
    @DisplayName("getReport nhóm theo SKU với đầy đủ doanh số, sản lượng và biên lợi nhuận khi có quyền FINANCE_VIEW_PROFIT_MARGIN")
    void getReport_groupBySKU_withProfitMargin() {
        authenticate("sales.mgr", "ROLE_SALES_MANAGER", "REPORT_SALES", "FINANCE_VIEW_PROFIT_MARGIN");

        LocalDateTime now = LocalDateTime.now();
        Order order1 = createOrder(1L, "ORD-01", dealerA, salesManagerUser, now);
        Order order2 = createOrder(2L, "ORD-02", dealerB, salesUser, now);

        // Item 1: Cola, qty 100, rev 1,000,000, cost = 100 * 7,000 = 700,000 -> profit = 300,000 -> margin = 30.00%
        OrderItem item1 = createItem(10L, order1, prodCola, new BigDecimal("100"), new BigDecimal("1000000"));
        // Item 2: Cola, qty 50, rev 500,000, cost = 50 * 7,000 = 350,000 -> total Cola: rev 1,500,000, cost 1,050,000 -> margin = 30.00%
        OrderItem item2 = createItem(11L, order2, prodCola, new BigDecimal("50"), new BigDecimal("500000"));
        // Item 3: Snack, qty 20, rev 300,000, cost = 20 * 9,000 = 180,000 -> profit = 120,000 -> margin = 40.00%
        OrderItem item3 = createItem(12L, order1, prodSnack, new BigDecimal("20"), new BigDecimal("300000"));

        when(orderItemRepository.findValidOrderItemsInPeriod(anyList(), any(), any()))
                .thenReturn(List.of(item1, item2, item3));

        SalesReportFilterRequest filter = SalesReportFilterRequest.builder()
                .groupBy("SKU")
                .fromDate(LocalDate.now().withDayOfMonth(1))
                .toDate(LocalDate.now())
                .build();

        SalesReportPageResponse response = salesReportService.getReport(filter);

        assertThat(response.getContent()).hasSize(2);
        SalesReportRowResponse row1 = response.getContent().get(0);
        assertThat(row1.getCode()).isEqualTo("SKU-COLA");
        assertThat(row1.getRevenue()).isEqualByComparingTo(new BigDecimal("1500000"));
        assertThat(row1.getQuantity()).isEqualByComparingTo(new BigDecimal("150"));
        assertThat(row1.getGrossMargin()).isEqualByComparingTo(new BigDecimal("30.00"));

        SalesReportRowResponse row2 = response.getContent().get(1);
        assertThat(row2.getCode()).isEqualTo("SKU-SNACK");
        assertThat(row2.getRevenue()).isEqualByComparingTo(new BigDecimal("300000"));
        assertThat(row2.getQuantity()).isEqualByComparingTo(new BigDecimal("20"));
        assertThat(row2.getGrossMargin()).isEqualByComparingTo(new BigDecimal("40.00"));

        // Summary KPI
        assertThat(response.getSummary().getTotalRevenue()).isEqualByComparingTo(new BigDecimal("1800000"));
        assertThat(response.getSummary().getTotalQuantity()).isEqualByComparingTo(new BigDecimal("170"));
        // Total profit = 300,000 + 150,000 + 120,000 = 570,000 / 1,800,000 = 31.67%
        assertThat(response.getSummary().getAverageGrossMargin()).isEqualByComparingTo(new BigDecimal("31.67"));
    }

    @Test
    @DisplayName("getReport khi người dùng không có FINANCE_VIEW_PROFIT_MARGIN thì grossMargin phải bằng null (bảo mật biên lợi nhuận)")
    void getReport_withoutProfitMarginPermission_hidesGrossMargin() {
        // Chỉ có REPORT_SALES, không có FINANCE_VIEW_PROFIT_MARGIN
        authenticate("viewer", "REPORT_SALES");

        LocalDateTime now = LocalDateTime.now();
        Order order = createOrder(1L, "ORD-01", dealerA, salesManagerUser, now);
        OrderItem item = createItem(10L, order, prodCola, new BigDecimal("100"), new BigDecimal("1000000"));

        when(orderItemRepository.findValidOrderItemsInPeriod(anyList(), any(), any()))
                .thenReturn(List.of(item));

        SalesReportFilterRequest filter = SalesReportFilterRequest.builder()
                .groupBy("SKU")
                .fromDate(LocalDate.now().withDayOfMonth(1))
                .toDate(LocalDate.now())
                .build();

        SalesReportPageResponse response = salesReportService.getReport(filter);

        assertThat(response.getContent()).hasSize(1);
        assertThat(response.getContent().get(0).getGrossMargin()).isNull();
        assertThat(response.getSummary().getAverageGrossMargin()).isNull();
    }

    @Test
    @DisplayName("getReport nhóm theo Khu vực (REGION)")
    void getReport_groupByRegion() {
        authenticate("admin", "ROLE_ADMIN");

        LocalDateTime now = LocalDateTime.now();
        Order order1 = createOrder(1L, "ORD-01", dealerA, salesManagerUser, now); // dealerA: Miền Bắc
        Order order2 = createOrder(2L, "ORD-02", dealerB, salesUser, now);        // dealerB: Miền Nam

        OrderItem item1 = createItem(10L, order1, prodCola, new BigDecimal("10"), new BigDecimal("100000"));
        OrderItem item2 = createItem(11L, order2, prodCola, new BigDecimal("25"), new BigDecimal("250000"));

        when(orderItemRepository.findValidOrderItemsInPeriod(anyList(), any(), any()))
                .thenReturn(List.of(item1, item2));

        SalesReportFilterRequest filter = SalesReportFilterRequest.builder()
                .groupBy("REGION")
                .fromDate(LocalDate.now().withDayOfMonth(1))
                .toDate(LocalDate.now())
                .build();

        SalesReportPageResponse response = salesReportService.getReport(filter);

        assertThat(response.getContent()).hasSize(2);
        // Sắp xếp doanh số giảm dần: Miền Nam 250k trước Miền Bắc 100k
        assertThat(response.getContent().get(0).getName()).isEqualTo("Miền Nam");
        assertThat(response.getContent().get(0).getRevenue()).isEqualByComparingTo(new BigDecimal("250000"));
        assertThat(response.getContent().get(1).getName()).isEqualTo("Miền Bắc");
        assertThat(response.getContent().get(1).getRevenue()).isEqualByComparingTo(new BigDecimal("100000"));
    }

    @Test
    @DisplayName("getReport nhóm theo Nhân viên (EMPLOYEE)")
    void getReport_groupByEmployee() {
        authenticate("admin", "ROLE_ADMIN");

        LocalDateTime now = LocalDateTime.now();
        Order order1 = createOrder(1L, "ORD-01", dealerA, salesManagerUser, now); // phụ trách: salesManagerUser
        Order order2 = createOrder(2L, "ORD-02", dealerB, salesUser, now);        // phụ trách: salesUser

        OrderItem item1 = createItem(10L, order1, prodCola, new BigDecimal("10"), new BigDecimal("500000"));
        OrderItem item2 = createItem(11L, order2, prodCola, new BigDecimal("20"), new BigDecimal("1000000"));

        when(orderItemRepository.findValidOrderItemsInPeriod(anyList(), any(), any()))
                .thenReturn(List.of(item1, item2));

        SalesReportFilterRequest filter = SalesReportFilterRequest.builder()
                .groupBy("EMPLOYEE")
                .fromDate(LocalDate.now().withDayOfMonth(1))
                .toDate(LocalDate.now())
                .build();

        SalesReportPageResponse response = salesReportService.getReport(filter);

        assertThat(response.getContent()).hasSize(2);
        assertThat(response.getContent().get(0).getCode()).isEqualTo("sales.rep");
        assertThat(response.getContent().get(0).getRevenue()).isEqualByComparingTo(new BigDecimal("1000000"));
        assertThat(response.getContent().get(1).getCode()).isEqualTo("sales.mgr");
        assertThat(response.getContent().get(1).getRevenue()).isEqualByComparingTo(new BigDecimal("500000"));
    }

    @Test
    @DisplayName("getReport nhóm theo Đại lý (DEALER)")
    void getReport_groupByDealer() {
        authenticate("admin", "ROLE_ADMIN");

        LocalDateTime now = LocalDateTime.now();
        Order order1 = createOrder(1L, "ORD-01", dealerA, salesManagerUser, now);
        OrderItem item1 = createItem(10L, order1, prodCola, new BigDecimal("10"), new BigDecimal("200000"));

        when(orderItemRepository.findValidOrderItemsInPeriod(anyList(), any(), any()))
                .thenReturn(List.of(item1));

        SalesReportFilterRequest filter = SalesReportFilterRequest.builder()
                .groupBy("DEALER")
                .fromDate(LocalDate.now().withDayOfMonth(1))
                .toDate(LocalDate.now())
                .build();

        SalesReportPageResponse response = salesReportService.getReport(filter);

        assertThat(response.getContent()).hasSize(1);
        assertThat(response.getContent().get(0).getName()).isEqualTo("Đại lý Hải Đăng");
        assertThat(response.getContent().get(0).getRevenue()).isEqualByComparingTo(new BigDecimal("200000"));
    }

    @Test
    @DisplayName("getReport nhóm theo Nhóm hàng (CATEGORY)")
    void getReport_groupByCategory() {
        authenticate("admin", "ROLE_ADMIN");

        LocalDateTime now = LocalDateTime.now();
        Order order1 = createOrder(1L, "ORD-01", dealerA, salesManagerUser, now);
        OrderItem item1 = createItem(10L, order1, prodCola, new BigDecimal("10"), new BigDecimal("100000")); // Nước giải khát
        OrderItem item2 = createItem(11L, order1, prodSnack, new BigDecimal("5"), new BigDecimal("75000"));   // Bánh kẹo

        when(orderItemRepository.findValidOrderItemsInPeriod(anyList(), any(), any()))
                .thenReturn(List.of(item1, item2));

        SalesReportFilterRequest filter = SalesReportFilterRequest.builder()
                .groupBy("CATEGORY")
                .fromDate(LocalDate.now().withDayOfMonth(1))
                .toDate(LocalDate.now())
                .build();

        SalesReportPageResponse response = salesReportService.getReport(filter);

        assertThat(response.getContent()).hasSize(2);
        assertThat(response.getContent().get(0).getName()).isEqualTo("Nước giải khát");
        assertThat(response.getContent().get(0).getRevenue()).isEqualByComparingTo(new BigDecimal("100000"));
        assertThat(response.getContent().get(1).getName()).isEqualTo("Bánh kẹo");
        assertThat(response.getContent().get(1).getRevenue()).isEqualByComparingTo(new BigDecimal("75000"));
    }

    @Test
    @DisplayName("getReport khi bật compare=true tính chính xác previousRevenue và changePercent")
    void getReport_withCompare_computesPreviousAndChange() {
        authenticate("admin", "ROLE_ADMIN");

        LocalDate from = LocalDate.of(2026, 3, 1);
        LocalDate to = LocalDate.of(2026, 3, 31);

        // Kỳ này: Cola 2,000,000
        Order currOrder = createOrder(1L, "ORD-01", dealerA, salesManagerUser, from.atTime(10, 0));
        OrderItem currItem = createItem(10L, currOrder, prodCola, new BigDecimal("200"), new BigDecimal("2000000"));

        // Kỳ trước: Cola 1,000,000
        Order prevOrder = createOrder(2L, "ORD-PREV", dealerA, salesManagerUser, from.minusMonths(1).atTime(10, 0));
        OrderItem prevItem = createItem(11L, prevOrder, prodCola, new BigDecimal("100"), new BigDecimal("1000000"));

        // Mock 2 lần gọi: lần 1 cho kỳ này, lần 2 cho kỳ trước
        when(orderItemRepository.findValidOrderItemsInPeriod(anyList(), any(), any()))
                .thenReturn(List.of(currItem))
                .thenReturn(List.of(prevItem));

        SalesReportFilterRequest filter = SalesReportFilterRequest.builder()
                .groupBy("SKU")
                .fromDate(from)
                .toDate(to)
                .compare(true)
                .build();

        SalesReportPageResponse response = salesReportService.getReport(filter);

        assertThat(response.getContent()).hasSize(1);
        SalesReportRowResponse row = response.getContent().get(0);
        assertThat(row.getRevenue()).isEqualByComparingTo(new BigDecimal("2000000"));
        assertThat(row.getPreviousRevenue()).isEqualByComparingTo(new BigDecimal("1000000"));
        // Tăng từ 1M lên 2M -> +100%
        assertThat(row.getChangePercent()).isEqualByComparingTo(new BigDecimal("100.00"));
    }
}
