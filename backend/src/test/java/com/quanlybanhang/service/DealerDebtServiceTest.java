package com.quanlybanhang.service;

import com.quanlybanhang.dto.request.OrderDebtCheckRequest;
import com.quanlybanhang.dto.response.DealerDebtResponse;
import com.quanlybanhang.dto.response.OrderDebtCheckResponse;
import com.quanlybanhang.exception.DealerDebtOverdueException;
import com.quanlybanhang.exception.DealerLockedException;
import com.quanlybanhang.model.*;
import com.quanlybanhang.repository.DealerRepository;
import com.quanlybanhang.repository.OrderRepository;
import com.quanlybanhang.repository.UserRepository;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.quanlybanhang.dto.response.DebtPaymentResponse;
import com.quanlybanhang.dto.response.FlaggedDealerResponse;

@ExtendWith(MockitoExtension.class)
@DisplayName("DealerDebtService - Kiểm soát công nợ, hạn mức và chặn nợ quá hạn (S4-02)")
class DealerDebtServiceTest {

    @Mock
    private DealerRepository dealerRepository;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private UserRepository userRepository;
    @Mock
    private com.quanlybanhang.repository.DebtPaymentRepository debtPaymentRepository;
    @Mock
    private com.quanlybanhang.repository.DebtRiskConfigRepository debtRiskConfigRepository;
    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private DealerDebtServiceImpl dealerDebtService;

    private User salesUser1;
    private User salesUser2;
    private Dealer dealer;

    @Test
    void readingMissingRiskConfigReturnsDefaultsWithoutWriting() {
        when(debtRiskConfigRepository.findFirstByOrderByIdAsc()).thenReturn(Optional.empty());
        var result = dealerDebtService.getDebtRiskConfig();
        assertThat(result.getDefaultAction()).isEqualTo(DebtFlagAction.BLOCK);
        assertThat(result.getId()).isNull();
        org.mockito.Mockito.verify(debtRiskConfigRepository, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void configReadDatabaseFailuresAreNotReplacedWithInventedDefaults() {
        when(debtRiskConfigRepository.findFirstByOrderByIdAsc()).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("Database unavailable"));
        assertThatThrownBy(() -> dealerDebtService.getDebtRiskConfig())
                .isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class).hasMessage("Database unavailable");
        org.mockito.Mockito.verify(debtRiskConfigRepository, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());
    }

    @BeforeEach
    void setUp() {
        Role salesRole = Role.builder().id(1L).name(ERole.ROLE_SALES).build();

        salesUser1 = User.builder()
                .id(101L)
                .username("sales1")
                .fullName("Nhân viên Kinh doanh 1")
                .active(true)
                .roles(Set.of(salesRole))
                .build();

        salesUser2 = User.builder()
                .id(102L)
                .username("sales2")
                .fullName("Nhân viên Kinh doanh 2")
                .active(true)
                .roles(Set.of(salesRole))
                .build();

        dealer = Dealer.builder()
                .id(1L)
                .code("DL001")
                .name("Đại lý Minh Phát")
                .assignedUser(salesUser1)
                .debtLimit(new BigDecimal("50000000"))
                .maxDebtDays(30)
                .active(true)
                .locked(false)
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate(String username, String... roles) {
        List<SimpleGrantedAuthority> authorities = java.util.Arrays.stream(roles)
                .map(r -> new SimpleGrantedAuthority(r.startsWith("ROLE_") ? r : "ROLE_" + r))
                .toList();
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(username, "password", authorities);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    @DisplayName("Tính toán công nợ hiện tại và hạn mức còn lại chính xác khi đại lý chưa có nợ")
    void getDealerDebtInfo_noOrders_returnsZeroDebt() {
        when(dealerRepository.findById(1L)).thenReturn(Optional.of(dealer));
        when(orderRepository.sumTotalAmountByDealerIdAndStatusIn(1L, OrderStatus.debtBearingStatuses())).thenReturn(BigDecimal.ZERO);
        when(orderRepository.findByDealerIdAndStatusInOrderByRecordedAtAsc(1L, OrderStatus.debtBearingStatuses())).thenReturn(List.of());

        DealerDebtResponse response = dealerDebtService.getDealerDebtInfo(1L);

        assertThat(response).isNotNull();
        assertThat(response.getDealerId()).isEqualTo(1L);
        assertThat(response.getCurrentDebt()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(response.getDebtLimit()).isEqualByComparingTo(new BigDecimal("50000000"));
        assertThat(response.getRemainingLimit()).isEqualByComparingTo(new BigDecimal("50000000"));
        assertThat(response.getHasOverdueDebt()).isFalse();
        assertThat(response.getIsBlocked()).isFalse();
    }

    @Test
    @DisplayName("Tính toán công nợ hiện tại và hạn mức còn lại chính xác khi đại lý có đơn hàng ghi sổ")
    void getDealerDebtInfo_withRecordedOrders_calculatesRemaining() {
        when(dealerRepository.findById(1L)).thenReturn(Optional.of(dealer));
        when(orderRepository.sumTotalAmountByDealerIdAndStatusIn(1L, OrderStatus.debtBearingStatuses())).thenReturn(new BigDecimal("35000000"));

        Order order = Order.builder()
                .id(10L)
                .orderCode("DH-001")
                .totalAmount(new BigDecimal("35000000"))
                .recordedAt(LocalDateTime.now().minusDays(10)) // Trong hạn 30 ngày
                .status("RECORDED")
                .build();

        when(orderRepository.findByDealerIdAndStatusInOrderByRecordedAtAsc(1L, OrderStatus.debtBearingStatuses())).thenReturn(List.of(order));

        DealerDebtResponse response = dealerDebtService.getDealerDebtInfo(1L);

        assertThat(response.getCurrentDebt()).isEqualByComparingTo(new BigDecimal("35000000"));
        assertThat(response.getRemainingLimit()).isEqualByComparingTo(new BigDecimal("15000000"));
        assertThat(response.getHasOverdueDebt()).isFalse();
        assertThat(response.getIsBlocked()).isFalse();
    }

    @Test
    @DisplayName("Phát hiện khoản nợ quá hạn khi đơn hàng ghi sổ đã vượt quá số ngày nợ cho phép (maxDebtDays)")
    void getDealerDebtInfo_withOverdueOrder_flagsOverdueAndBlocked() {
        when(dealerRepository.findById(1L)).thenReturn(Optional.of(dealer));
        when(orderRepository.sumTotalAmountByDealerIdAndStatusIn(1L, OrderStatus.debtBearingStatuses())).thenReturn(new BigDecimal("20000000"));

        // maxDebtDays = 30, đơn phát sinh từ 45 ngày trước -> quá hạn 15 ngày
        Order overdueOrder = Order.builder()
                .id(10L)
                .orderCode("DH-OLD")
                .totalAmount(new BigDecimal("20000000"))
                .recordedAt(LocalDateTime.now().minusDays(45))
                .status("RECORDED")
                .build();

        when(orderRepository.findByDealerIdAndStatusInOrderByRecordedAtAsc(1L, OrderStatus.debtBearingStatuses())).thenReturn(List.of(overdueOrder));

        DealerDebtResponse response = dealerDebtService.getDealerDebtInfo(1L);

        assertThat(response.getHasOverdueDebt()).isTrue();
        assertThat(response.getMaxOverdueDays()).isEqualTo(15);
        assertThat(response.getOverdueAmount()).isEqualByComparingTo(new BigDecimal("20000000"));
        assertThat(response.getOldestOverdueOrderCode()).isEqualTo("DH-OLD");
        assertThat(response.getIsBlocked()).isTrue();
        assertThat(response.getBlockReason()).contains("khoản nợ quá hạn 15 ngày (vượt quá giới hạn cho phép 30 ngày)");
    }

    @Test
    @DisplayName("Chặn tạo đơn hoàn toàn khi đại lý có khoản nợ quá hạn quá số ngày cho phép")
    void validateDealerCanCreateOrder_throwsExceptionWhenOverdue() {
        when(orderRepository.sumTotalAmountByDealerIdAndStatusIn(1L, OrderStatus.debtBearingStatuses())).thenReturn(new BigDecimal("10000000"));

        Order overdueOrder = Order.builder()
                .id(11L)
                .orderCode("DH-QUAHAN")
                .totalAmount(new BigDecimal("10000000"))
                .recordedAt(LocalDateTime.now().minusDays(40)) // quá hạn 10 ngày
                .status("RECORDED")
                .build();

        when(orderRepository.findByDealerIdAndStatusInOrderByRecordedAtAsc(1L, OrderStatus.debtBearingStatuses())).thenReturn(List.of(overdueOrder));

        assertThatThrownBy(() -> dealerDebtService.validateDealerCanCreateOrder(dealer))
                .isInstanceOf(DealerDebtOverdueException.class)
                .hasMessageContaining("quá hạn 10 ngày")
                .hasMessageContaining("chặn tạo đơn hoàn toàn");
    }

    @Test
    @DisplayName("Chặn tạo đơn khi đại lý bị khoá giao dịch (S3-07)")
    void validateDealerCanCreateOrder_throwsExceptionWhenLocked() {
        dealer.setLocked(true);
        dealer.setLockReason("Có dấu hiệu mất khả năng thanh toán");

        assertThatThrownBy(() -> dealerDebtService.validateDealerCanCreateOrder(dealer))
                .isInstanceOf(DealerLockedException.class)
                .hasMessageContaining("Có dấu hiệu mất khả năng thanh toán");
    }

    @Test
    @DisplayName("Kiểm tra sơ bộ vượt hạn mức công nợ: trả về cảnh báo và đánh dấu cần duyệt")
    void checkOrderDebt_exceedsLimit_requiresApproval() {
        when(dealerRepository.findById(1L)).thenReturn(Optional.of(dealer));
        when(orderRepository.sumTotalAmountByDealerIdAndStatusIn(1L, OrderStatus.debtBearingStatuses())).thenReturn(new BigDecimal("40000000"));
        when(orderRepository.findByDealerIdAndStatusInOrderByRecordedAtAsc(1L, OrderStatus.debtBearingStatuses())).thenReturn(List.of());

        // Hạn mức 50tr, nợ hiện tại 40tr, đơn mới 15tr -> tổng 55tr > 50tr
        OrderDebtCheckRequest request = OrderDebtCheckRequest.builder()
                .dealerId(1L)
                .orderAmount(new BigDecimal("15000000"))
                .build();

        OrderDebtCheckResponse response = dealerDebtService.checkOrderDebt(request);

        assertThat(response.getExceedsLimit()).isTrue();
        assertThat(response.getRequiresApproval()).isTrue();
        assertThat(response.getProjectedDebt()).isEqualByComparingTo(new BigDecimal("55000000"));
        assertThat(response.getApprovalReason()).contains("Vượt hạn mức công nợ");
    }

    @Test
    @DisplayName("Kiểm tra sơ bộ không vượt hạn mức công nợ: không yêu cầu duyệt")
    void checkOrderDebt_withinLimit_notRequiresApproval() {
        when(dealerRepository.findById(1L)).thenReturn(Optional.of(dealer));
        when(orderRepository.sumTotalAmountByDealerIdAndStatusIn(1L, OrderStatus.debtBearingStatuses())).thenReturn(new BigDecimal("20000000"));
        when(orderRepository.findByDealerIdAndStatusInOrderByRecordedAtAsc(1L, OrderStatus.debtBearingStatuses())).thenReturn(List.of());

        // Hạn mức 50tr, nợ hiện tại 20tr, đơn mới 10tr -> tổng 30tr <= 50tr
        OrderDebtCheckResponse response = dealerDebtService.checkOrderDebt(1L, new BigDecimal("10000000"));

        assertThat(response.getExceedsLimit()).isFalse();
        assertThat(response.getRequiresApproval()).isFalse();
        assertThat(response.getProjectedDebt()).isEqualByComparingTo(new BigDecimal("30000000"));
    }

    @Test
    @DisplayName("Bảo mật cấp dòng: Nhân viên kinh doanh chỉ xem được công nợ đại lý do mình phụ trách")
    void rowLevelSecurity_salesUserOnlyAccessesOwnDealer() {
        authenticate("sales1", "ROLE_SALES");
        when(userRepository.findByUsername("sales1")).thenReturn(Optional.of(salesUser1));
        when(dealerRepository.findById(1L)).thenReturn(Optional.of(dealer));
        when(orderRepository.sumTotalAmountByDealerIdAndStatusIn(1L, OrderStatus.debtBearingStatuses())).thenReturn(BigDecimal.ZERO);
        when(orderRepository.findByDealerIdAndStatusInOrderByRecordedAtAsc(1L, OrderStatus.debtBearingStatuses())).thenReturn(List.of());

        // Sales1 phụ trách đại lý 1 -> xem được bình thường
        DealerDebtResponse res = dealerDebtService.getDealerDebtInfo(1L);
        assertThat(res).isNotNull();

        // Bây giờ authenticate sales2 (không phụ trách đại lý 1) -> bị từ chối 403
        authenticate("sales2", "ROLE_SALES");
        when(userRepository.findByUsername("sales2")).thenReturn(Optional.of(salesUser2));

        assertThatThrownBy(() -> dealerDebtService.getDealerDebtInfo(1L))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("chỉ có quyền xem đại lý do mình phụ trách");
    }

    @Test
    @DisplayName("S7-04: Khấu trừ công nợ khi đã có phiếu ghi nhận thu tiền")
    void getDealerDebtInfo_withPayments_reducesCurrentDebt() {
        when(dealerRepository.findById(1L)).thenReturn(Optional.of(dealer));
        when(orderRepository.sumTotalAmountByDealerIdAndStatusIn(1L, OrderStatus.debtBearingStatuses()))
                .thenReturn(new BigDecimal("50000000"));
        when(debtPaymentRepository.sumAmountByDealerId(1L)).thenReturn(new BigDecimal("20000000"));
        when(orderRepository.findByDealerIdAndStatusInOrderByRecordedAtAsc(1L, OrderStatus.debtBearingStatuses()))
                .thenReturn(List.of());

        DealerDebtResponse response = dealerDebtService.getDealerDebtInfo(1L);

        assertThat(response.getCurrentDebt()).isEqualByComparingTo(new BigDecimal("30000000"));
        assertThat(response.getTotalPaid()).isEqualByComparingTo(new BigDecimal("20000000"));
        assertThat(response.getRemainingLimit()).isEqualByComparingTo(new BigDecimal("20000000"));
    }

    @Test
    @DisplayName("S7-04: Phiếu thu tiền khấu trừ đơn cũ trước theo FIFO, gỡ nợ quá hạn")
    void getDealerDebtInfo_withPayments_clearsOldestOverdueDebtUsingFifo() {
        when(dealerRepository.findById(1L)).thenReturn(Optional.of(dealer));
        when(orderRepository.sumTotalAmountByDealerIdAndStatusIn(1L, OrderStatus.debtBearingStatuses()))
                .thenReturn(new BigDecimal("30000000"));
        when(debtPaymentRepository.sumAmountByDealerId(1L)).thenReturn(new BigDecimal("10000000"));

        Order order1 = Order.builder()
                .id(1L).orderCode("DH-OLD").totalAmount(new BigDecimal("10000000"))
                .recordedAt(LocalDateTime.now().minusDays(45)).status("RECORDED").build();
        Order order2 = Order.builder()
                .id(2L).orderCode("DH-NEW").totalAmount(new BigDecimal("20000000"))
                .recordedAt(LocalDateTime.now().minusDays(5)).status("RECORDED").build();

        when(orderRepository.findByDealerIdAndStatusInOrderByRecordedAtAsc(1L, OrderStatus.debtBearingStatuses()))
                .thenReturn(List.of(order1, order2));

        DealerDebtResponse response = dealerDebtService.getDealerDebtInfo(1L);

        assertThat(response.getHasOverdueDebt()).isFalse();
        assertThat(response.getOverdueAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(response.getCurrentDebt()).isEqualByComparingTo(new BigDecimal("20000000"));
    }

    @Test
    @DisplayName("S7-04: Đánh giá lại tự động gắn cờ DEBT_LIMIT_EXCEEDED khi công nợ vượt hạn mức")
    void evaluateDealerDebt_whenExceedingDebtLimit_flagsDealerAutomatically() {
        when(dealerRepository.findById(1L)).thenReturn(Optional.of(dealer));
        when(orderRepository.sumTotalAmountByDealerIdAndStatusIn(1L, OrderStatus.debtBearingStatuses()))
                .thenReturn(new BigDecimal("60000000"));
        when(debtPaymentRepository.sumAmountByDealerId(1L)).thenReturn(BigDecimal.ZERO);
        when(orderRepository.findByDealerIdAndStatusInOrderByRecordedAtAsc(1L, OrderStatus.debtBearingStatuses()))
                .thenReturn(List.of());

        com.quanlybanhang.dto.response.FlaggedDealerResponse response = dealerDebtService.evaluateDealerDebt(1L);

        assertThat(response.getDebtFlagType()).isEqualTo(DebtFlagType.DEBT_LIMIT_EXCEEDED);
        assertThat(response.getDebtFlagReason()).contains("Vượt hạn mức nợ");
        assertThat(dealer.getDebtFlagged()).isTrue();
        assertThat(dealer.getDebtFlagType()).isEqualTo(DebtFlagType.DEBT_LIMIT_EXCEEDED);
        verify(dealerRepository).save(dealer);
    }

    @Test
    @DisplayName("S7-04: Đánh giá lại tự động gắn cờ OVERDUE khi có khoản nợ quá hạn ngày")
    void evaluateDealerDebt_whenOverdue_flagsDealerAutomatically() {
        when(dealerRepository.findById(1L)).thenReturn(Optional.of(dealer));
        when(orderRepository.sumTotalAmountByDealerIdAndStatusIn(1L, OrderStatus.debtBearingStatuses()))
                .thenReturn(new BigDecimal("20000000"));
        when(debtPaymentRepository.sumAmountByDealerId(1L)).thenReturn(BigDecimal.ZERO);

        Order order = Order.builder()
                .id(1L).orderCode("DH-OVERDUE").totalAmount(new BigDecimal("20000000"))
                .recordedAt(LocalDateTime.now().minusDays(40)).status("RECORDED").build();
        when(orderRepository.findByDealerIdAndStatusInOrderByRecordedAtAsc(1L, OrderStatus.debtBearingStatuses()))
                .thenReturn(List.of(order));

        com.quanlybanhang.dto.response.FlaggedDealerResponse response = dealerDebtService.evaluateDealerDebt(1L);

        assertThat(response.getDebtFlagType()).isEqualTo(DebtFlagType.OVERDUE);
        assertThat(response.getDebtFlagReason()).contains("Nợ quá hạn 10 ngày");
        assertThat(dealer.getDebtFlagged()).isTrue();
        verify(dealerRepository).save(dealer);
    }

    @Test
    @DisplayName("S7-04: Đánh giá lại tự động gắn cờ BOTH khi vừa vượt hạn mức vừa quá hạn")
    void evaluateDealerDebt_whenBoth_flagsBoth() {
        when(dealerRepository.findById(1L)).thenReturn(Optional.of(dealer));
        when(orderRepository.sumTotalAmountByDealerIdAndStatusIn(1L, OrderStatus.debtBearingStatuses()))
                .thenReturn(new BigDecimal("70000000"));
        when(debtPaymentRepository.sumAmountByDealerId(1L)).thenReturn(BigDecimal.ZERO);

        Order order = Order.builder()
                .id(1L).orderCode("DH-BOTH").totalAmount(new BigDecimal("70000000"))
                .recordedAt(LocalDateTime.now().minusDays(50)).status("RECORDED").build();
        when(orderRepository.findByDealerIdAndStatusInOrderByRecordedAtAsc(1L, OrderStatus.debtBearingStatuses()))
                .thenReturn(List.of(order));

        com.quanlybanhang.dto.response.FlaggedDealerResponse response = dealerDebtService.evaluateDealerDebt(1L);

        assertThat(response.getDebtFlagType()).isEqualTo(DebtFlagType.BOTH);
        assertThat(response.getDebtFlagReason()).contains("Vượt hạn mức nợ").contains("Nợ quá hạn 20 ngày");
        assertThat(dealer.getDebtFlagged()).isTrue();
    }

    @Test
    @DisplayName("S7-04: Đánh giá lại tự động gỡ cờ khi đại lý không còn vi phạm")
    void evaluateDealerDebt_whenResolved_unflagsDealerAutomatically() {
        dealer.setDebtFlagged(true);
        dealer.setDebtFlagType(DebtFlagType.DEBT_LIMIT_EXCEEDED);
        dealer.setDebtFlagReason("Vượt hạn mức nợ cũ");

        when(dealerRepository.findById(1L)).thenReturn(Optional.of(dealer));
        when(orderRepository.sumTotalAmountByDealerIdAndStatusIn(1L, OrderStatus.debtBearingStatuses()))
                .thenReturn(new BigDecimal("30000000"));
        when(debtPaymentRepository.sumAmountByDealerId(1L)).thenReturn(BigDecimal.ZERO);
        when(orderRepository.findByDealerIdAndStatusInOrderByRecordedAtAsc(1L, OrderStatus.debtBearingStatuses()))
                .thenReturn(List.of());

        com.quanlybanhang.dto.response.FlaggedDealerResponse response = dealerDebtService.evaluateDealerDebt(1L);

        assertThat(response.getDebtFlagType()).isEqualTo(DebtFlagType.NONE);
        assertThat(dealer.getDebtFlagged()).isFalse();
        assertThat(dealer.getDebtFlagReason()).isNull();
        verify(dealerRepository).save(dealer);
    }

    @Test
    @DisplayName("S7-04: Ghi nhận thu tiền tự động lưu phiếu và chạy lại đánh giá công nợ")
    void recordPayment_savesAndAutoReEvaluates() {
        when(dealerRepository.findById(1L)).thenReturn(Optional.of(dealer));
        when(orderRepository.sumTotalAmountByDealerIdAndStatusIn(1L, OrderStatus.debtBearingStatuses()))
                .thenReturn(new BigDecimal("50000000"));
        when(debtPaymentRepository.sumAmountByDealerId(1L)).thenReturn(new BigDecimal("30000000"));
        when(orderRepository.findByDealerIdAndStatusInOrderByRecordedAtAsc(1L, OrderStatus.debtBearingStatuses()))
                .thenReturn(List.of());

        com.quanlybanhang.dto.request.DebtPaymentRequest request = com.quanlybanhang.dto.request.DebtPaymentRequest.builder()
                .dealerId(1L)
                .amount(new BigDecimal("30000000"))
                .paymentMethod("CHUYEN_KHOAN")
                .referenceCode("UNC-999")
                .note("Thu tiền đại lý Minh Phát")
                .build();

        DebtPaymentResponse res = dealerDebtService.recordPayment(request, "accountant1");

        assertThat(res).isNotNull();
        assertThat(res.getAmount()).isEqualByComparingTo(new BigDecimal("30000000"));
        assertThat(res.getRemainingDebt()).isEqualByComparingTo(new BigDecimal("20000000"));
        verify(debtPaymentRepository).save(org.mockito.ArgumentMatchers.any(DebtPayment.class));
        verify(auditLogService).logAction(
                org.mockito.ArgumentMatchers.eq("DEBT_PAYMENT"),
                org.mockito.ArgumentMatchers.eq("1"),
                org.mockito.ArgumentMatchers.eq("CREATE"),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.contains(new java.text.DecimalFormat("#,###").format(new BigDecimal("30000000"))),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq("accountant1"));
    }

    @Test
    @DisplayName("S7-04: Chặn tạo đơn mới khi đại lý bị gắn cờ và cấu hình là BLOCK")
    void validateDealerCanCreateOrder_whenFlaggedAndBlockConfig_throwsBlockedException() {
        dealer.setDebtFlagged(true);
        dealer.setDebtFlagReason("Vượt hạn mức nợ 10tr");
        dealer.setDebtActionConfig(DebtFlagAction.BLOCK);

        assertThatThrownBy(() -> dealerDebtService.validateDealerCanCreateOrder(dealer))
                .isInstanceOf(com.quanlybanhang.exception.DealerDebtBlockedException.class)
                .hasMessageContaining("Đại lý 'Đại lý Minh Phát' (DL001) đang bị gắn cờ cảnh báo rủi ro công nợ")
                .hasMessageContaining("chặn tạo đơn mới theo cấu hình");
    }

    @Test
    @DisplayName("S7-04: Cho phép tạo đơn khi đại lý bị gắn cờ nhưng cấu hình là REQUIRE_APPROVAL")
    void validateDealerCanCreateOrder_whenFlaggedAndRequireApprovalConfig_doesNotThrow() {
        dealer.setDebtFlagged(true);
        dealer.setDebtFlagReason("Vượt hạn mức nợ 10tr");
        dealer.setDebtActionConfig(DebtFlagAction.REQUIRE_APPROVAL);

        when(orderRepository.sumTotalAmountByDealerIdAndStatusIn(1L, OrderStatus.debtBearingStatuses()))
                .thenReturn(new BigDecimal("60000000"));
        when(debtPaymentRepository.sumAmountByDealerId(1L)).thenReturn(BigDecimal.ZERO);
        when(orderRepository.findByDealerIdAndStatusInOrderByRecordedAtAsc(1L, OrderStatus.debtBearingStatuses()))
                .thenReturn(List.of());

        // Không ném exception
        dealerDebtService.validateDealerCanCreateOrder(dealer);
    }

    @Test
    @DisplayName("S7-04: Sơ bộ đơn mới yêu cầu duyệt khi đại lý bị gắn cờ và cấu hình REQUIRE_APPROVAL")
    void checkOrderDebt_flaggedAndRequireApproval_requiresApproval() {
        dealer.setDebtFlagged(true);
        dealer.setDebtFlagReason("Vượt hạn mức công nợ");
        dealer.setDebtActionConfig(DebtFlagAction.REQUIRE_APPROVAL);

        when(dealerRepository.findById(1L)).thenReturn(Optional.of(dealer));
        when(orderRepository.sumTotalAmountByDealerIdAndStatusIn(1L, OrderStatus.debtBearingStatuses()))
                .thenReturn(new BigDecimal("20000000"));
        when(debtPaymentRepository.sumAmountByDealerId(1L)).thenReturn(BigDecimal.ZERO);
        when(orderRepository.findByDealerIdAndStatusInOrderByRecordedAtAsc(1L, OrderStatus.debtBearingStatuses()))
                .thenReturn(List.of());

        OrderDebtCheckResponse response = dealerDebtService.checkOrderDebt(1L, new BigDecimal("5000000"));

        assertThat(response.getRequiresApproval()).isTrue();
        assertThat(response.getIsBlocked()).isFalse();
        assertThat(response.getApprovalReason()).contains("Đại lý đang bị gắn cờ rủi ro công nợ");
    }
}

