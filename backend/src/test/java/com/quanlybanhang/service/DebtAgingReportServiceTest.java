package com.quanlybanhang.service;

import com.quanlybanhang.dto.response.*;
import com.quanlybanhang.model.*;
import com.quanlybanhang.repository.*;
import com.quanlybanhang.service.impl.DebtAgingReportServiceImpl;
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
@DisplayName("Unit test: Báo cáo công nợ quá hạn theo tuổi nợ (S8-09)")
class DebtAgingReportServiceTest {

    @Mock private DealerRepository dealerRepository;
    @Mock private UserRepository userRepository;
    @Mock private InvoiceRepository invoiceRepository;
    @Mock private OrderRepository orderRepository;
    @Mock private DebtPaymentRepository debtPaymentRepository;
    @Mock private ReturnOrderRepository returnOrderRepository;
    @Mock private DebtAgingExcelExportService excelExportService;

    @InjectMocks
    private DebtAgingReportServiceImpl reportService;

    private User adminUser;
    private User salesUser1;
    private User salesUser2;
    private Dealer dealerA;
    private Dealer dealerB;
    private Dealer dealerUnassigned;

    @BeforeEach
    void setUp() {
        Role adminRole = Role.builder().name(ERole.ROLE_ADMIN).build();
        Role salesRole = Role.builder().name(ERole.ROLE_SALES).build();

        adminUser = User.builder().id(1L).username("admin").fullName("Quản trị viên").roles(Set.of(adminRole)).active(true).build();
        salesUser1 = User.builder().id(2L).username("nv01").fullName("Nguyễn An").roles(Set.of(salesRole)).active(true).build();
        salesUser2 = User.builder().id(3L).username("nv02").fullName("Trần Bình").roles(Set.of(salesRole)).active(true).build();

        dealerA = Dealer.builder()
                .id(10L).code("DL-A").name("Đại lý Alpha")
                .assignedUser(salesUser1).maxDebtDays(30).active(true).build();

        dealerB = Dealer.builder()
                .id(20L).code("DL-B").name("Đại lý Beta")
                .assignedUser(salesUser2).maxDebtDays(15).active(true).build();

        dealerUnassigned = Dealer.builder()
                .id(30L).code("DL-C").name("Đại lý Gamma")
                .assignedUser(null).maxDebtDays(30).active(true).build();

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", "pwd",
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN"), new SimpleGrantedAuthority("REPORT_FINANCE")))
        );

        lenient().when(userRepository.findByUsername("admin")).thenReturn(Optional.of(adminUser));
    }

    @Test
    @DisplayName("getOptions trả về ngày chốt hôm nay, múi giờ và 3 nhóm tuổi nợ mặc định")
    void testGetOptions() {
        when(userRepository.findAll()).thenReturn(List.of(adminUser, salesUser1, salesUser2));

        DebtAgingOptionsResponse options = reportService.getOptions();

        assertThat(options).isNotNull();
        assertThat(options.getTimezone()).isEqualTo("Asia/Ho_Chi_Minh");
        assertThat(options.getBuckets()).hasSize(3);
        assertThat(options.getBuckets().get(0).getKey()).isEqualTo("recent");
        assertThat(options.getBuckets().get(0).getMinDays()).isEqualTo(1);
        assertThat(options.getBuckets().get(0).getMaxDays()).isEqualTo(14);

        assertThat(options.getBuckets().get(1).getKey()).isEqualTo("middle");
        assertThat(options.getBuckets().get(1).getMinDays()).isEqualTo(15);
        assertThat(options.getBuckets().get(1).getMaxDays()).isEqualTo(44);

        assertThat(options.getBuckets().get(2).getKey()).isEqualTo("old");
        assertThat(options.getBuckets().get(2).getMinDays()).isEqualTo(45);
        assertThat(options.getBuckets().get(2).getMaxDays()).isNull();

        assertThat(options.getEmployees()).hasSize(2);
        assertThat(options.getEmployees().get(0).getCode()).isEqualTo("nv01");
    }

    @Test
    @DisplayName("Không cho phép chọn ngày chốt báo cáo trong tương lai")
    void testFutureAsOfDateThrows() {
        LocalDate futureDate = LocalDate.now().plusDays(2);
        assertThatThrownBy(() -> reportService.getReport(futureDate, null, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Không chọn ngày sau ngày chốt tối đa");
    }

    @Test
    @DisplayName("Tính toán nợ quá hạn theo nhóm tuổi nợ và xác định đại lý rủi ro cao nhất (rank 1)")
    void testCalculateOverdueAndRiskRanking() {
        LocalDate asOfDate = LocalDate.of(2026, 10, 25);

        when(dealerRepository.findAll()).thenReturn(List.of(dealerA, dealerB));

        // Dealer A: Hóa đơn ngày 2026-08-01, dueDate = 2026-08-31 (quá hạn ~55 ngày -> nhóm "old")
        Invoice invA = Invoice.builder()
                .id(101L).invoiceCode("HD-101").dealer(dealerA)
                .invoiceDate(LocalDateTime.of(2026, 8, 1, 9, 0))
                .dueDate(LocalDate.of(2026, 8, 31))
                .totalAmount(new BigDecimal("10000000.00"))
                .adjustedTotalAmount(new BigDecimal("10000000.00"))
                .status(InvoiceStatus.ISSUED)
                .build();

        // Dealer B: Hóa đơn ngày 2026-10-10, dueDate = 2026-10-20 (quá hạn 5 ngày -> nhóm "recent")
        Invoice invB = Invoice.builder()
                .id(102L).invoiceCode("HD-102").dealer(dealerB)
                .invoiceDate(LocalDateTime.of(2026, 10, 10, 9, 0))
                .dueDate(LocalDate.of(2026, 10, 20))
                .totalAmount(new BigDecimal("20000000.00"))
                .adjustedTotalAmount(new BigDecimal("20000000.00"))
                .status(InvoiceStatus.ISSUED)
                .build();

        when(debtPaymentRepository.sumAmountByDealerIdAndPaymentDateLessThanEqual(eq(10L), eq(asOfDate))).thenReturn(BigDecimal.ZERO);
        when(debtPaymentRepository.sumAmountByDealerIdAndPaymentDateLessThanEqual(eq(20L), eq(asOfDate))).thenReturn(BigDecimal.ZERO);

        when(invoiceRepository.findByDealerIdOrderByInvoiceDateAscIdAsc(10L)).thenReturn(List.of(invA));
        when(invoiceRepository.findByDealerIdOrderByInvoiceDateAscIdAsc(20L)).thenReturn(List.of(invB));

        when(orderRepository.findByDealerIdAndStatusInOrderByRecordedAtAsc(eq(10L), any())).thenReturn(Collections.emptyList());
        when(orderRepository.findByDealerIdAndStatusInOrderByRecordedAtAsc(eq(20L), any())).thenReturn(Collections.emptyList());

        DebtAgingReportResponse report = reportService.getReport(asOfDate, null, false);

        assertThat(report).isNotNull();
        assertThat(report.getComplete()).isTrue();
        assertThat(report.getCurrency()).isEqualTo("VND");
        assertThat(report.getTotals().getDealerCount()).isEqualTo(2);
        assertThat(report.getTotals().getOverdueAmount()).isEqualTo("30000000.00");
        assertThat(report.getTotals().getMaxOverdueDays()).isEqualTo(55);

        // Đại lý A có số ngày quá hạn 55 ngày > Đại lý B (5 ngày) nên Đại lý A xếp rủi ro cao nhất (rank 1)
        List<DebtAgingDealerRowResponse> dealers = report.getDealers();
        assertThat(dealers).hasSize(2);
        assertThat(dealers.get(0).getDealerId()).isEqualTo(10L);
        assertThat(dealers.get(0).getRiskRank()).isEqualTo(1);
        assertThat(dealers.get(0).getRiskLabel()).isEqualTo("Ưu tiên cao");
        assertThat(dealers.get(0).getBuckets().get("old")).isEqualTo("10000000.00");
        assertThat(dealers.get(0).getBuckets().get("recent")).isEqualTo("0.00");

        assertThat(dealers.get(1).getDealerId()).isEqualTo(20L);
        assertThat(dealers.get(1).getRiskRank()).isEqualTo(2);
        assertThat(dealers.get(1).getBuckets().get("recent")).isEqualTo("20000000.00");

        // Kiểm tra tổng hợp theo nhóm tuổi nợ
        List<DebtAgingBucketTotalResponse> bucketTotals = report.getBucketTotals();
        assertThat(bucketTotals).hasSize(3);
        assertThat(bucketTotals.get(0).getBucketKey()).isEqualTo("recent");
        assertThat(bucketTotals.get(0).getAmount()).isEqualTo("20000000.00");
        assertThat(bucketTotals.get(0).getDealerCount()).isEqualTo(1);

        assertThat(bucketTotals.get(1).getBucketKey()).isEqualTo("middle");
        assertThat(bucketTotals.get(1).getAmount()).isEqualTo("0.00");
        assertThat(bucketTotals.get(1).getDealerCount()).isEqualTo(0);

        assertThat(bucketTotals.get(2).getBucketKey()).isEqualTo("old");
        assertThat(bucketTotals.get(2).getAmount()).isEqualTo("10000000.00");
        assertThat(bucketTotals.get(2).getDealerCount()).isEqualTo(1);

        // Kiểm tra tổng hợp theo nhân viên
        List<DebtAgingEmployeeTotalResponse> empTotals = report.getEmployeeTotals();
        assertThat(empTotals).hasSize(2);
    }

    @Test
    @DisplayName("Báo cáo rỗng khi không có đại lý nào nợ quá hạn")
    void testEmptyReportWhenNoOverdue() {
        LocalDate asOfDate = LocalDate.of(2026, 10, 25);
        when(dealerRepository.findAll()).thenReturn(List.of(dealerA));
        when(debtPaymentRepository.sumAmountByDealerIdAndPaymentDateLessThanEqual(eq(10L), eq(asOfDate))).thenReturn(BigDecimal.ZERO);
        when(invoiceRepository.findByDealerIdOrderByInvoiceDateAscIdAsc(10L)).thenReturn(Collections.emptyList());
        when(orderRepository.findByDealerIdAndStatusInOrderByRecordedAtAsc(eq(10L), any())).thenReturn(Collections.emptyList());

        DebtAgingReportResponse report = reportService.getReport(asOfDate, null, false);

        assertThat(report.getTotals().getDealerCount()).isEqualTo(0);
        assertThat(report.getTotals().getOverdueAmount()).isEqualTo("0.00");
        assertThat(report.getTotals().getMaxOverdueDays()).isEqualTo(0);
        assertThat(report.getDealers()).isEmpty();
        assertThat(report.getEmployeeTotals()).isEmpty();
        assertThat(report.getBucketTotals()).hasSize(3);
    }

    @Test
    @DisplayName("Lọc theo đại lý chưa phân công (unassignedOnly = true)")
    void testFilterUnassignedOnly() {
        LocalDate asOfDate = LocalDate.of(2026, 10, 25);
        when(dealerRepository.findAll()).thenReturn(List.of(dealerA, dealerUnassigned));

        Invoice invUnassigned = Invoice.builder()
                .id(103L).invoiceCode("HD-103").dealer(dealerUnassigned)
                .invoiceDate(LocalDateTime.of(2026, 9, 1, 9, 0))
                .dueDate(LocalDate.of(2026, 10, 1))
                .totalAmount(new BigDecimal("5000000.00"))
                .adjustedTotalAmount(new BigDecimal("5000000.00"))
                .status(InvoiceStatus.ISSUED)
                .build();

        when(debtPaymentRepository.sumAmountByDealerIdAndPaymentDateLessThanEqual(eq(30L), eq(asOfDate))).thenReturn(BigDecimal.ZERO);
        when(invoiceRepository.findByDealerIdOrderByInvoiceDateAscIdAsc(30L)).thenReturn(List.of(invUnassigned));
        when(orderRepository.findByDealerIdAndStatusInOrderByRecordedAtAsc(eq(30L), any())).thenReturn(Collections.emptyList());

        DebtAgingReportResponse report = reportService.getReport(asOfDate, null, true);

        assertThat(report.getDealers()).hasSize(1);
        assertThat(report.getDealers().get(0).getDealerId()).isEqualTo(30L);
        assertThat(report.getDealers().get(0).getEmployeeId()).isNull();
        assertThat(report.getDealers().get(0).getEmployeeName()).isEqualTo("Chưa phân công");
    }
}

