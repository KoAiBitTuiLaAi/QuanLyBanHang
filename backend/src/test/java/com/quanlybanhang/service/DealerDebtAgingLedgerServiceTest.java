package com.quanlybanhang.service;

import com.quanlybanhang.dto.response.DealerDebtAgingLedgerResponse;
import com.quanlybanhang.dto.response.DealerDebtAgingRowResponse;
import com.quanlybanhang.dto.response.DealerDebtAgingSummaryResponse;
import com.quanlybanhang.model.*;
import com.quanlybanhang.repository.*;
import com.quanlybanhang.service.impl.DealerDebtAgingLedgerServiceImpl;
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
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Unit test: Sổ công nợ theo đại lý với phân tích tuổi nợ (S7-03)")
class DealerDebtAgingLedgerServiceTest {

    @Mock private DealerRepository dealerRepository;
    @Mock private UserRepository userRepository;
    @Mock private InvoiceRepository invoiceRepository;
    @Mock private OrderRepository orderRepository;
    @Mock private DebtPaymentRepository debtPaymentRepository;
    @Mock private ReturnOrderRepository returnOrderRepository;
    @Mock private DealerDebtAgingExcelExportService excelExportService;

    @InjectMocks
    private DealerDebtAgingLedgerServiceImpl ledgerService;

    private User adminUser;
    private User accountantUser;
    private User salesUser;
    private Dealer dealerA;
    private Dealer dealerB;
    private Dealer dealerC;

    @BeforeEach
    void setUp() {
        Role adminRole = Role.builder().name(ERole.ROLE_ADMIN).build();
        Role accountantRole = Role.builder().name(ERole.ROLE_ACCOUNTANT).build();
        Role salesRole = Role.builder().name(ERole.ROLE_SALES).build();

        adminUser = User.builder().id(1L).username("admin").fullName("Quản trị viên").roles(Set.of(adminRole)).active(true).build();
        accountantUser = User.builder().id(2L).username("accountant").fullName("Kế toán công nợ").roles(Set.of(accountantRole)).active(true).build();
        salesUser = User.builder().id(3L).username("salesman").fullName("Nhân viên bán hàng").roles(Set.of(salesRole)).active(true).build();

        dealerA = Dealer.builder()
                .id(101L).code("DL-ALPHA").name("Đại lý Alpha")
                .phone("0901234567").address("123 Lê Lợi, Q1")
                .assignedUser(salesUser).debtLimit(new BigDecimal("100000000")).maxDebtDays(30).active(true).build();

        dealerB = Dealer.builder()
                .id(102L).code("DL-BETA").name("Đại lý Beta")
                .phone("0912345678").address("456 Hai Bà Trưng, Q3")
                .assignedUser(salesUser).debtLimit(new BigDecimal("50000000")).maxDebtDays(15).active(true).build();

        dealerC = Dealer.builder()
                .id(103L).code("DL-GAMMA").name("Đại lý Gamma")
                .phone("0987654321").address("789 Trần Hưng Đạo, Q5")
                .assignedUser(null).debtLimit(new BigDecimal("30000000")).maxDebtDays(30).active(true).build();

        // Mặc định đăng nhập với Kế toán công nợ có quyền FINANCE_VIEW_DEBT
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("accountant", "pwd",
                        List.of(new SimpleGrantedAuthority("ROLE_ACCOUNTANT"), new SimpleGrantedAuthority("FINANCE_VIEW_DEBT")))
        );

        lenient().when(userRepository.findByUsername("accountant")).thenReturn(Optional.of(accountantUser));
    }

    @Test
    @DisplayName("Tính toán chính xác: phải thu, đã thu, còn nợ và phân bổ theo 4 nhóm tuổi nợ 0-30, 31-60, 61-90, >90 ngày")
    void testCalculationOfReceivablePaidAndAgingBuckets() {
        LocalDate asOfDate = LocalDate.of(2026, 10, 25);

        when(dealerRepository.findAll()).thenReturn(List.of(dealerA));

        // Hóa đơn 1: 100 ngày trước (tuổi > 90), tiền 50tr
        Invoice invOld = Invoice.builder()
                .id(1L).invoiceCode("HD-001").dealer(dealerA)
                .totalAmount(new BigDecimal("50000000"))
                .invoiceDate(asOfDate.minusDays(100).atStartOfDay())
                .dueDate(asOfDate.minusDays(70))
                .status(InvoiceStatus.ISSUED)
                .build();

        // Hóa đơn 2: 70 ngày trước (tuổi 61-90), tiền 30tr
        Invoice invMid = Invoice.builder()
                .id(2L).invoiceCode("HD-002").dealer(dealerA)
                .totalAmount(new BigDecimal("30000000"))
                .invoiceDate(asOfDate.minusDays(70).atStartOfDay())
                .dueDate(asOfDate.minusDays(40))
                .status(InvoiceStatus.ISSUED)
                .build();

        // Hóa đơn 3: 45 ngày trước (tuổi 31-60), tiền 20tr
        Invoice invRecent = Invoice.builder()
                .id(3L).invoiceCode("HD-003").dealer(dealerA)
                .totalAmount(new BigDecimal("20000000"))
                .invoiceDate(asOfDate.minusDays(45).atStartOfDay())
                .dueDate(asOfDate.minusDays(15))
                .status(InvoiceStatus.ISSUED)
                .build();

        // Hóa đơn 4: 10 ngày trước (tuổi 0-30), tiền 10tr
        Invoice invNew = Invoice.builder()
                .id(4L).invoiceCode("HD-004").dealer(dealerA)
                .totalAmount(new BigDecimal("10000000"))
                .invoiceDate(asOfDate.minusDays(10).atStartOfDay())
                .dueDate(asOfDate.plusDays(20))
                .status(InvoiceStatus.ISSUED)
                .build();

        when(invoiceRepository.findByDealerIdOrderByInvoiceDateAscIdAsc(101L))
                .thenReturn(List.of(invOld, invMid, invRecent, invNew));

        when(orderRepository.findByDealerIdAndStatusInOrderByRecordedAtAsc(eq(101L), any()))
                .thenReturn(Collections.emptyList());

        // Đã thu 40tr -> Đối trừ FIFO: trả 40tr cho HD-001 (50tr), còn nợ 10tr ở nhóm > 90 ngày
        when(debtPaymentRepository.sumAmountByDealerIdAndPaymentDateLessThanEqual(101L, asOfDate))
                .thenReturn(new BigDecimal("40000000"));

        DealerDebtAgingLedgerResponse response = ledgerService.getLedger(asOfDate, false, null, null);

        assertThat(response).isNotNull();
        assertThat(response.getDealers()).hasSize(1);

        DealerDebtAgingRowResponse row = response.getDealers().get(0);
        // Tổng phải thu = 50 + 30 + 20 + 10 = 110tr
        assertThat(row.getTotalReceivable()).isEqualByComparingTo("110000000");
        // Đã thu = 40tr
        assertThat(row.getTotalPaid()).isEqualByComparingTo("40000000");
        // Còn nợ = 110 - 40 = 70tr
        assertThat(row.getRemainingDebt()).isEqualByComparingTo("70000000");

        // Nhóm > 90 ngày: 50tr - 40tr = 10tr
        assertThat(row.getBucketOver90()).isEqualByComparingTo("10000000");
        // Nhóm 61-90 ngày: 30tr
        assertThat(row.getBucket61To90()).isEqualByComparingTo("30000000");
        // Nhóm 31-60 ngày: 20tr
        assertThat(row.getBucket31To60()).isEqualByComparingTo("20000000");
        // Nhóm 0-30 ngày: 10tr
        assertThat(row.getBucket0To30()).isEqualByComparingTo("10000000");

        // Kiểm tra tính nhất quán: Tổng 4 nhóm = Còn nợ
        BigDecimal sumBuckets = row.getBucket0To30().add(row.getBucket31To60()).add(row.getBucket61To90()).add(row.getBucketOver90());
        assertThat(sumBuckets).isEqualByComparingTo(row.getRemainingDebt());

        // Quá hạn: HD-001 (10tr), HD-002 (30tr), HD-003 (20tr) đã qua dueDate -> overdueDebt = 60tr
        // HD-004 (10tr) dueDate trong tương lai -> inTermDebt = 10tr
        assertThat(row.getOverdueDebt()).isEqualByComparingTo("60000000");
        assertThat(row.getInTermDebt()).isEqualByComparingTo("10000000");
        assertThat(row.getIsOverdue()).isTrue();
        assertThat(row.getMaxDebtAgeDays()).isEqualTo(100);
        assertThat(row.getMaxOverdueDays()).isEqualTo(70);
    }

    @Test
    @DisplayName("Lọc riêng nhóm quá hạn (overdueOnly = true): chỉ trả về đại lý có nợ quá hạn")
    void testFilterOverdueOnly() {
        LocalDate asOfDate = LocalDate.of(2026, 10, 25);
        when(dealerRepository.findAll()).thenReturn(List.of(dealerA, dealerB));

        // Dealer A có nợ quá hạn
        Invoice invOverdue = Invoice.builder()
                .id(1L).invoiceCode("HD-A").dealer(dealerA)
                .totalAmount(new BigDecimal("15000000"))
                .invoiceDate(asOfDate.minusDays(40).atStartOfDay())
                .dueDate(asOfDate.minusDays(10)) // Quá hạn 10 ngày
                .status(InvoiceStatus.ISSUED)
                .build();
        when(invoiceRepository.findByDealerIdOrderByInvoiceDateAscIdAsc(101L)).thenReturn(List.of(invOverdue));
        when(debtPaymentRepository.sumAmountByDealerIdAndPaymentDateLessThanEqual(101L, asOfDate)).thenReturn(BigDecimal.ZERO);

        // Dealer B chỉ có nợ trong hạn (chưa tới dueDate)
        Invoice invInTerm = Invoice.builder()
                .id(2L).invoiceCode("HD-B").dealer(dealerB)
                .totalAmount(new BigDecimal("20000000"))
                .invoiceDate(asOfDate.minusDays(5).atStartOfDay())
                .dueDate(asOfDate.plusDays(10)) // Trong hạn
                .status(InvoiceStatus.ISSUED)
                .build();
        when(invoiceRepository.findByDealerIdOrderByInvoiceDateAscIdAsc(102L)).thenReturn(List.of(invInTerm));
        when(debtPaymentRepository.sumAmountByDealerIdAndPaymentDateLessThanEqual(102L, asOfDate)).thenReturn(BigDecimal.ZERO);

        // 1. Khi overdueOnly = true: chỉ dealerA xuất hiện
        DealerDebtAgingLedgerResponse overdueOnlyResponse = ledgerService.getLedger(asOfDate, true, null, null);
        assertThat(overdueOnlyResponse.getDealers()).hasSize(1);
        assertThat(overdueOnlyResponse.getDealers().get(0).getDealerId()).isEqualTo(101L);
        assertThat(overdueOnlyResponse.getSummary().getDealerCount()).isEqualTo(1);
        assertThat(overdueOnlyResponse.getSummary().getOverdueDealerCount()).isEqualTo(1);

        // 2. Khi overdueOnly = false: cả dealerA và dealerB đều xuất hiện
        DealerDebtAgingLedgerResponse allResponse = ledgerService.getLedger(asOfDate, false, null, null);
        assertThat(allResponse.getDealers()).hasSize(2);
        assertThat(allResponse.getSummary().getDealerCount()).isEqualTo(2);
        assertThat(allResponse.getSummary().getOverdueDealerCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Sắp xếp danh sách đại lý: tập trung đòi những khoản nợ lâu nhất trước")
    void testSortingOldestDebtFirst() {
        LocalDate asOfDate = LocalDate.of(2026, 10, 25);
        when(dealerRepository.findAll()).thenReturn(List.of(dealerA, dealerB, dealerC));

        // Dealer A: có nợ tuổi 40 ngày (nhóm 31-60)
        Invoice invA = Invoice.builder().id(1L).invoiceCode("HD-A").dealer(dealerA)
                .totalAmount(new BigDecimal("10000000")).invoiceDate(asOfDate.minusDays(40).atStartOfDay())
                .status(InvoiceStatus.ISSUED).build();
        when(invoiceRepository.findByDealerIdOrderByInvoiceDateAscIdAsc(101L)).thenReturn(List.of(invA));

        // Dealer B: có nợ tuổi 120 ngày (nhóm >90 ngày) -> Phải đứng ĐẦU BẢNG
        Invoice invB = Invoice.builder().id(2L).invoiceCode("HD-B").dealer(dealerB)
                .totalAmount(new BigDecimal("5000000")).invoiceDate(asOfDate.minusDays(120).atStartOfDay())
                .status(InvoiceStatus.ISSUED).build();
        when(invoiceRepository.findByDealerIdOrderByInvoiceDateAscIdAsc(102L)).thenReturn(List.of(invB));

        // Dealer C: có nợ tuổi 75 ngày (nhóm 61-90 ngày) -> Phải đứng THỨ HAI
        Invoice invC = Invoice.builder().id(3L).invoiceCode("HD-C").dealer(dealerC)
                .totalAmount(new BigDecimal("8000000")).invoiceDate(asOfDate.minusDays(75).atStartOfDay())
                .status(InvoiceStatus.ISSUED).build();
        when(invoiceRepository.findByDealerIdOrderByInvoiceDateAscIdAsc(103L)).thenReturn(List.of(invC));

        DealerDebtAgingLedgerResponse response = ledgerService.getLedger(asOfDate, false, null, null);

        assertThat(response.getDealers()).hasSize(3);
        // Thứ tự ưu tiên đòi nợ: Dealer B (>90 ngày) -> Dealer C (61-90 ngày) -> Dealer A (31-60 ngày)
        assertThat(response.getDealers().get(0).getDealerId()).isEqualTo(102L); // Dealer B
        assertThat(response.getDealers().get(1).getDealerId()).isEqualTo(103L); // Dealer C
        assertThat(response.getDealers().get(2).getDealerId()).isEqualTo(101L); // Dealer A
    }

    @Test
    @DisplayName("Tổng cộng ở chân bảng (Summary) khớp chính xác với tổng các cột đại lý chi tiết")
    void testSummaryRowMatchesColumnsSum() {
        LocalDate asOfDate = LocalDate.of(2026, 10, 25);
        when(dealerRepository.findAll()).thenReturn(List.of(dealerA, dealerB));

        Invoice invA = Invoice.builder().id(1L).invoiceCode("HD-A").dealer(dealerA)
                .totalAmount(new BigDecimal("100000000")).invoiceDate(asOfDate.minusDays(20).atStartOfDay())
                .dueDate(asOfDate.minusDays(5)).status(InvoiceStatus.ISSUED).build();
        when(invoiceRepository.findByDealerIdOrderByInvoiceDateAscIdAsc(101L)).thenReturn(List.of(invA));
        when(debtPaymentRepository.sumAmountByDealerIdAndPaymentDateLessThanEqual(101L, asOfDate)).thenReturn(new BigDecimal("30000000"));

        Invoice invB = Invoice.builder().id(2L).invoiceCode("HD-B").dealer(dealerB)
                .totalAmount(new BigDecimal("50000000")).invoiceDate(asOfDate.minusDays(50).atStartOfDay())
                .dueDate(asOfDate.minusDays(20)).status(InvoiceStatus.ISSUED).build();
        when(invoiceRepository.findByDealerIdOrderByInvoiceDateAscIdAsc(102L)).thenReturn(List.of(invB));
        when(debtPaymentRepository.sumAmountByDealerIdAndPaymentDateLessThanEqual(102L, asOfDate)).thenReturn(new BigDecimal("10000000"));

        DealerDebtAgingLedgerResponse response = ledgerService.getLedger(asOfDate, false, null, null);

        DealerDebtAgingSummaryResponse summary = response.getSummary();
        assertThat(summary).isNotNull();
        // Tổng phải thu = 100 + 50 = 150tr
        assertThat(summary.getTotalReceivable()).isEqualByComparingTo("150000000");
        // Tổng đã thu = 30 + 10 = 40tr
        assertThat(summary.getTotalPaid()).isEqualByComparingTo("40000000");
        // Tổng còn nợ = 150 - 40 = 110tr
        assertThat(summary.getTotalRemainingDebt()).isEqualByComparingTo("110000000");

        // Tổng các nhóm tuổi nợ
        BigDecimal sumBuckets = summary.getTotalBucket0To30()
                .add(summary.getTotalBucket31To60())
                .add(summary.getTotalBucket61To90())
                .add(summary.getTotalBucketOver90());
        assertThat(sumBuckets).isEqualByComparingTo(summary.getTotalRemainingDebt());

        // Tổng nợ trong hạn + nợ quá hạn = Tổng còn nợ
        BigDecimal sumTerms = summary.getTotalInTermDebt().add(summary.getTotalOverdueDebt());
        assertThat(sumTerms).isEqualByComparingTo(summary.getTotalRemainingDebt());

        assertThat(summary.getDealerCount()).isEqualTo(2);
        assertThat(summary.getOverdueDealerCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("Từ chối ngày chốt trong tương lai (ném IllegalArgumentException)")
    void testRejectsFutureAsOfDate() {
        LocalDate futureDate = LocalDate.now().plusDays(10);
        assertThatThrownBy(() -> ledgerService.getLedger(futureDate, false, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Không được chọn ngày chốt trong tương lai");
    }

    @Test
    @DisplayName("Từ chối truy cập khi chưa xác thực hoặc thiếu quyền FINANCE_VIEW_DEBT")
    void testAccessDeniedWithoutPermission() {
        // Đăng nhập tài khoản không có quyền FINANCE_VIEW_DEBT
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("guest", "pwd",
                        List.of(new SimpleGrantedAuthority("PRODUCT_VIEW")))
        );

        assertThatThrownBy(() -> ledgerService.getLedger(LocalDate.now(), false, null, null))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("FINANCE_VIEW_DEBT");
    }

    @Test
    @DisplayName("Phân quyền cấp dòng (Row-Level Security): Nhân viên kinh doanh chỉ xem được đại lý mình phụ trách")
    void testSalesStaffRowLevelSecurity() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("salesman", "pwd",
                        List.of(new SimpleGrantedAuthority("ROLE_SALES"), new SimpleGrantedAuthority("FINANCE_VIEW_DEBT")))
        );
        when(userRepository.findByUsername("salesman")).thenReturn(Optional.of(salesUser));

        // dealerA được gán cho salesUser (id=3), dealerC không gán cho salesUser
        when(dealerRepository.findAll()).thenReturn(List.of(dealerA, dealerC));

        Invoice invA = Invoice.builder().id(1L).invoiceCode("HD-A").dealer(dealerA)
                .totalAmount(new BigDecimal("10000000")).invoiceDate(LocalDate.now().minusDays(5).atStartOfDay())
                .dueDate(LocalDate.now().plusDays(25)).status(InvoiceStatus.ISSUED).build();
        when(invoiceRepository.findByDealerIdOrderByInvoiceDateAscIdAsc(101L)).thenReturn(List.of(invA));

        DealerDebtAgingLedgerResponse response = ledgerService.getLedger(LocalDate.now(), false, null, null);

        // Chỉ dealerA xuất hiện trong kết quả
        assertThat(response.getDealers()).hasSize(1);
        assertThat(response.getDealers().get(0).getDealerId()).isEqualTo(101L);
    }

    @Test
    @DisplayName("Xuất Excel (.xlsx) gọi dịch vụ export và trả về mảng byte")
    void testExportExcel() {
        LocalDate asOfDate = LocalDate.of(2026, 10, 25);
        when(dealerRepository.findAll()).thenReturn(List.of(dealerA));

        byte[] fakeExcel = new byte[]{1, 2, 3, 4, 5};
        when(excelExportService.exportToExcel(any())).thenReturn(fakeExcel);

        byte[] result = ledgerService.exportExcel(asOfDate, false, null, null);
        assertThat(result).isNotNull().isEqualTo(fakeExcel);
    }
}
