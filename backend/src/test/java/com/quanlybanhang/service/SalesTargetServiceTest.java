package com.quanlybanhang.service;

import com.quanlybanhang.dto.request.BatchSalesTargetRequest;
import com.quanlybanhang.dto.request.SalesTargetRequest;
import com.quanlybanhang.dto.request.UpdateSalesTargetRequest;
import com.quanlybanhang.dto.response.MySalesTargetProgressResponse;
import com.quanlybanhang.dto.response.SalesTargetResponse;
import com.quanlybanhang.dto.response.SalesTargetSummaryResponse;
import com.quanlybanhang.model.*;
import com.quanlybanhang.repository.*;
import com.quanlybanhang.security.UserDetailsImpl;
import com.quanlybanhang.service.impl.SalesTargetServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SalesTargetServiceTest {

    @Mock
    private SalesTargetRepository salesTargetRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private TerritoryRepository territoryRepository;
    @Mock
    private AuditLogRepository auditLogRepository;
    @Mock
    private AuditLogService auditLogService;

    private SalesTargetServiceImpl salesTargetService;

    private User salesUser1;
    private User salesUser2;
    private Territory territoryHcm;
    private Territory territoryHn;

    @BeforeEach
    void setUp() {
        salesTargetService = new SalesTargetServiceImpl(
                salesTargetRepository,
                userRepository,
                territoryRepository,
                auditLogRepository,
                auditLogService
        );

        Role salesRole = Role.builder().id(1L).name(ERole.ROLE_SALES).build();
        territoryHcm = Territory.builder()
                .id(100L)
                .code("KV-HCM")
                .name("Khu vực TP.HCM")
                .active(true)
                .build();
        territoryHn = Territory.builder()
                .id(200L)
                .code("KV-HN")
                .name("Khu vực Hà Nội")
                .active(true)
                .build();

        salesUser1 = User.builder()
                .id(10L)
                .username("sales01")
                .fullName("Nguyễn Văn Sales 1")
                .email("sales01@test.local")
                .active(true)
                .roles(Set.of(salesRole))
                .territories(Set.of(territoryHcm))
                .build();
        salesUser2 = User.builder()
                .id(20L)
                .username("sales02")
                .fullName("Trần Thị Sales 2")
                .email("sales02@test.local")
                .active(true)
                .roles(Set.of(salesRole))
                .territories(Set.of(territoryHn))
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAsManager() {
        UserDetailsImpl principal = new UserDetailsImpl(
                1L, "manager01", "manager@test.local", "Quản lý KD", "pwd", true, 0L,
                List.of(
                        new SimpleGrantedAuthority("ROLE_SALES_MANAGER"),
                        new SimpleGrantedAuthority("ORDER_APPROVE"),
                        new SimpleGrantedAuthority("REPORT_SALES")
                )
        );
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private void authenticateAsSales(User user) {
        UserDetailsImpl principal = new UserDetailsImpl(
                user.getId(), user.getUsername(), user.getEmail(), user.getFullName(), "pwd", true, 0L,
                List.of(
                        new SimpleGrantedAuthority("ROLE_SALES"),
                        new SimpleGrantedAuthority("REPORT_SALES")
                )
        );
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    @Test
    void createTarget_forEmployeeAndTerritory_computesRealTimeProgressAndWritesAuditLog() {
        authenticateAsManager();
        YearMonth currentYm = YearMonth.now();

        when(userRepository.findById(10L)).thenReturn(Optional.of(salesUser1));
        when(territoryRepository.findById(100L)).thenReturn(Optional.of(territoryHcm));
        when(salesTargetRepository.existsByScopeKey(anyString())).thenReturn(false);
        when(salesTargetRepository.existsByTargetCode(anyString())).thenReturn(false);
        when(salesTargetRepository.save(any(SalesTarget.class))).thenAnswer(inv -> {
            SalesTarget st = inv.getArgument(0);
            st.setId(501L);
            st.setCreatedAt(LocalDateTime.now());
            st.setUpdatedAt(LocalDateTime.now());
            return st;
        });

        Dealer dealer = Dealer.builder()
                .id(300L)
                .code("DL-HCM-01")
                .name("Đại lý Quận 1")
                .assignedUser(salesUser1)
                .territory(territoryHcm)
                .build();
        Order order = Order.builder()
                .id(900L)
                .orderCode("DH-001")
                .dealer(dealer)
                .status(OrderStatus.DELIVERED.name())
                .totalAmount(new BigDecimal("60000000"))
                .recordedAt(LocalDateTime.now())
                .build();
        when(salesTargetRepository.findValidOrdersInPeriod(anyCollection(), any(), any()))
                .thenReturn(List.of(order));
        when(auditLogRepository.findByEntityTypeAndEntityIdOrderByCreatedAtDesc(eq("SALES_TARGET"), eq("501")))
                .thenReturn(List.of());

        SalesTargetRequest request = SalesTargetRequest.builder()
                .year(currentYm.getYear())
                .month(currentYm.getMonthValue())
                .userId(10L)
                .territoryId(100L)
                .targetAmount(new BigDecimal("100000000"))
                .note("Chỉ tiêu tháng hiện tại")
                .build();

        SalesTargetResponse response = salesTargetService.createTarget(request, "manager01");

        assertThat(response.getId()).isEqualTo(501L);
        assertThat(response.getScopeType()).isEqualTo(SalesTargetScopeType.EMPLOYEE_TERRITORY);
        assertThat(response.getUserId()).isEqualTo(10L);
        assertThat(response.getTerritoryId()).isEqualTo(100L);
        assertThat(response.getTargetAmount()).isEqualByComparingTo("100000000");
        assertThat(response.getActualAmount()).isEqualByComparingTo("60000000");
        assertThat(response.getRemainingAmount()).isEqualByComparingTo("40000000");
        assertThat(response.getCompletionRate()).isEqualByComparingTo("60.00");
        assertThat(response.getOrderCount()).isEqualTo(1);
        assertThat(response.getExpectedAmountToDate()).isGreaterThan(BigDecimal.ZERO);
        assertThat(response.getLocked()).isFalse();

        verify(auditLogService).logAction(
                eq("SALES_TARGET"),
                eq("501"),
                eq("CREATE"),
                isNull(),
                contains("100000000.00"),
                isNull(),
                eq("manager01"));
    }

    @Test
    void createTarget_pastPeriod_autoLocksTargetImmediately() {
        authenticateAsManager();
        YearMonth pastYm = YearMonth.now().minusMonths(1);

        when(userRepository.findById(10L)).thenReturn(Optional.of(salesUser1));
        when(salesTargetRepository.existsByScopeKey(anyString())).thenReturn(false);
        when(salesTargetRepository.existsByTargetCode(anyString())).thenReturn(false);
        when(salesTargetRepository.save(any(SalesTarget.class))).thenAnswer(inv -> {
            SalesTarget st = inv.getArgument(0);
            st.setId(502L);
            return st;
        });
        when(salesTargetRepository.findValidOrdersInPeriod(anyCollection(), any(), any())).thenReturn(List.of());
        when(auditLogRepository.findByEntityTypeAndEntityIdOrderByCreatedAtDesc(eq("SALES_TARGET"), eq("502")))
                .thenReturn(List.of());

        SalesTargetRequest request = SalesTargetRequest.builder()
                .year(pastYm.getYear())
                .month(pastYm.getMonthValue())
                .userId(10L)
                .targetAmount(new BigDecimal("80000000"))
                .reason("Bổ sung chỉ tiêu tháng trước theo biên bản họp")
                .build();

        SalesTargetResponse response = salesTargetService.createTarget(request, "manager01");

        assertThat(response.getPastPeriod()).isTrue();
        assertThat(response.getLocked()).isTrue();
    }

    @Test
    void updateTarget_whenPastPeriodOrLocked_requiresReasonAndLogsAuditWithReason() {
        authenticateAsManager();
        YearMonth pastYm = YearMonth.now().minusMonths(1);

        SalesTarget existing = SalesTarget.builder()
                .id(77L)
                .targetCode("CT-202601-1001")
                .scopeKey(SalesTarget.buildScopeKey(pastYm.getYear(), pastYm.getMonthValue(), 10L, null))
                .targetYear(pastYm.getYear())
                .targetMonth(pastYm.getMonthValue())
                .scopeType(SalesTargetScopeType.EMPLOYEE)
                .user(salesUser1)
                .targetAmount(new BigDecimal("50000000"))
                .locked(false)
                .build();

        when(salesTargetRepository.findByIdForUpdate(77L)).thenReturn(Optional.of(existing));

        UpdateSalesTargetRequest withoutReason = UpdateSalesTargetRequest.builder()
                .targetAmount(new BigDecimal("75000000"))
                .note("Tăng chỉ tiêu")
                .build();

        assertThatThrownBy(() -> salesTargetService.updateTarget(77L, withoutReason, "manager01"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bắt buộc phải nhập lý do");

        when(salesTargetRepository.save(any(SalesTarget.class))).thenAnswer(inv -> inv.getArgument(0));
        when(salesTargetRepository.findValidOrdersInPeriod(anyCollection(), any(), any())).thenReturn(List.of());
        when(auditLogRepository.findByEntityTypeAndEntityIdOrderByCreatedAtDesc(eq("SALES_TARGET"), eq("77")))
                .thenReturn(List.of());

        UpdateSalesTargetRequest withReason = UpdateSalesTargetRequest.builder()
                .targetAmount(new BigDecimal("75000000"))
                .note("Điều chỉnh theo quyết định BGĐ")
                .reason("Điều chỉnh chỉ tiêu quý sau đối soát doanh số thực tế")
                .build();

        SalesTargetResponse updated = salesTargetService.updateTarget(77L, withReason, "manager01");

        assertThat(updated.getTargetAmount()).isEqualByComparingTo("75000000");
        assertThat(updated.getLocked()).isTrue();
        assertThat(updated.getLastModifyReason()).isEqualTo("Điều chỉnh chỉ tiêu quý sau đối soát doanh số thực tế");

        verify(auditLogService).logAction(
                eq("SALES_TARGET"),
                eq("77"),
                eq("UPDATE_LOCKED_TARGET"),
                contains("50000000.00"),
                contains("75000000.00"),
                eq("Điều chỉnh chỉ tiêu quý sau đối soát doanh số thực tế"),
                eq("manager01"));
    }

    @Test
    void getMyProgress_returnsEmployeeOwnTargetsAndTerritoryBreakdownInRealTime() {
        authenticateAsSales(salesUser1);
        YearMonth ym = YearMonth.now();

        SalesTarget empTarget = SalesTarget.builder()
                .id(1L)
                .targetCode("CT-EMP-1")
                .scopeKey(SalesTarget.buildScopeKey(ym.getYear(), ym.getMonthValue(), 10L, null))
                .targetYear(ym.getYear())
                .targetMonth(ym.getMonthValue())
                .scopeType(SalesTargetScopeType.EMPLOYEE)
                .user(salesUser1)
                .targetAmount(new BigDecimal("120000000"))
                .locked(false)
                .build();

        SalesTarget empTerritoryTarget = SalesTarget.builder()
                .id(2L)
                .targetCode("CT-EMP-TER-1")
                .scopeKey(SalesTarget.buildScopeKey(ym.getYear(), ym.getMonthValue(), 10L, 100L))
                .targetYear(ym.getYear())
                .targetMonth(ym.getMonthValue())
                .scopeType(SalesTargetScopeType.EMPLOYEE_TERRITORY)
                .user(salesUser1)
                .territory(territoryHcm)
                .targetAmount(new BigDecimal("80000000"))
                .locked(false)
                .build();

        Dealer dealerHcm = Dealer.builder()
                .id(301L)
                .code("DL-HCM")
                .name("Đại lý HCM")
                .assignedUser(salesUser1)
                .territory(territoryHcm)
                .build();

        Order o1 = Order.builder()
                .id(1001L)
                .orderCode("DH-1001")
                .dealer(dealerHcm)
                .status(OrderStatus.DELIVERED.name())
                .totalAmount(new BigDecimal("50000000"))
                .recordedAt(LocalDateTime.now())
                .build();
        Order o2 = Order.builder()
                .id(1002L)
                .orderCode("DH-1002")
                .dealer(dealerHcm)
                .status(OrderStatus.APPROVED.name())
                .totalAmount(new BigDecimal("40000000"))
                .recordedAt(LocalDateTime.now())
                .build();

        when(userRepository.findByUsername("sales01")).thenReturn(Optional.of(salesUser1));
        when(salesTargetRepository.findByTargetYearAndTargetMonthAndUserId(ym.getYear(), ym.getMonthValue(), 10L))
                .thenReturn(List.of(empTarget, empTerritoryTarget));
        when(salesTargetRepository.findValidOrdersInPeriod(anyCollection(), any(), any()))
                .thenReturn(List.of(o1, o2));
        when(salesTargetRepository.findByTargetYearAndTargetMonthAndScopeType(
                ym.getYear(), ym.getMonthValue(), SalesTargetScopeType.TERRITORY))
                .thenReturn(List.of());
        when(territoryRepository.findAllById(anyIterable())).thenReturn(List.of(territoryHcm));

        MySalesTargetProgressResponse progress = salesTargetService.getMyProgress(
                ym.getYear(), ym.getMonthValue(), null, "sales01");

        assertThat(progress.getUserId()).isEqualTo(10L);
        assertThat(progress.getUsername()).isEqualTo("sales01");
        assertThat(progress.getTotalTargetAmount()).isEqualByComparingTo("120000000");
        assertThat(progress.getActualAmount()).isEqualByComparingTo("90000000");
        assertThat(progress.getRemainingAmount()).isEqualByComparingTo("30000000");
        assertThat(progress.getCompletionRate()).isEqualByComparingTo("75.00");
        assertThat(progress.getOrderCount()).isEqualTo(2);
        assertThat(progress.getTerritoryBreakdown()).hasSize(1);
        assertThat(progress.getTerritoryBreakdown().get(0).getTerritoryCode()).isEqualTo("KV-HCM");
        assertThat(progress.getTerritoryBreakdown().get(0).getEmployeeActualAmount()).isEqualByComparingTo("90000000");
    }

    @Test
    void salesUser_cannotViewOtherEmployeeTargets() {
        authenticateAsSales(salesUser1);
        when(userRepository.findByUsername("sales01")).thenReturn(Optional.of(salesUser1));

        assertThatThrownBy(() -> salesTargetService.searchTargets(
                2026, 10, null, 20L, null, null, null, null, null))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("chính mình");

        SalesTarget otherUserTarget = SalesTarget.builder()
                .id(88L)
                .targetCode("CT-OTHER")
                .scopeKey("2026-10:U:20:T:ALL")
                .targetYear(2026)
                .targetMonth(10)
                .scopeType(SalesTargetScopeType.EMPLOYEE)
                .user(salesUser2)
                .targetAmount(new BigDecimal("50000000"))
                .build();
        when(salesTargetRepository.findById(88L)).thenReturn(Optional.of(otherUserTarget));

        assertThatThrownBy(() -> salesTargetService.getById(88L))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void upsertBatch_updatesLockedTargetOnlyWhenReasonProvided() {
        authenticateAsManager();
        YearMonth pastYm = YearMonth.now().minusMonths(2);

        SalesTarget lockedExisting = SalesTarget.builder()
                .id(99L)
                .targetCode("CT-LOCKED")
                .scopeKey(SalesTarget.buildScopeKey(pastYm.getYear(), pastYm.getMonthValue(), 10L, null))
                .targetYear(pastYm.getYear())
                .targetMonth(pastYm.getMonthValue())
                .scopeType(SalesTargetScopeType.EMPLOYEE)
                .user(salesUser1)
                .targetAmount(new BigDecimal("40000000"))
                .locked(true)
                .build();

        when(userRepository.findById(10L)).thenReturn(Optional.of(salesUser1));
        when(salesTargetRepository.findByScopeKeyForUpdate(lockedExisting.getScopeKey()))
                .thenReturn(Optional.of(lockedExisting));
        when(salesTargetRepository.save(any(SalesTarget.class))).thenAnswer(inv -> inv.getArgument(0));
        when(salesTargetRepository.findValidOrdersInPeriod(anyCollection(), any(), any())).thenReturn(List.of());
        when(auditLogRepository.findByEntityTypeAndEntityIdOrderByCreatedAtDesc(eq("SALES_TARGET"), eq("99")))
                .thenReturn(List.of());

        BatchSalesTargetRequest batchWithoutReason = BatchSalesTargetRequest.builder()
                .year(pastYm.getYear())
                .month(pastYm.getMonthValue())
                .targets(List.of(SalesTargetRequest.builder()
                        .userId(10L)
                        .targetAmount(new BigDecimal("55000000"))
                        .build()))
                .build();

        assertThatThrownBy(() -> salesTargetService.upsertBatch(batchWithoutReason, "manager01"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bắt buộc phải có lý do");

        BatchSalesTargetRequest batchWithReason = BatchSalesTargetRequest.builder()
                .year(pastYm.getYear())
                .month(pastYm.getMonthValue())
                .reason("Điều chỉnh hàng loạt chỉ tiêu kỳ trước theo phê duyệt")
                .targets(List.of(SalesTargetRequest.builder()
                        .userId(10L)
                        .targetAmount(new BigDecimal("55000000"))
                        .build()))
                .build();

        List<SalesTargetResponse> responses = salesTargetService.upsertBatch(batchWithReason, "manager01");
        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).getTargetAmount()).isEqualByComparingTo("55000000");
        assertThat(responses.get(0).getLastModifyReason()).isEqualTo("Điều chỉnh hàng loạt chỉ tiêu kỳ trước theo phê duyệt");
    }

    @Test
    void getSummary_aggregatesEmployeeAndTerritoryProgressAccurately() {
        authenticateAsManager();
        YearMonth ym = YearMonth.now();

        SalesTarget empTarget = SalesTarget.builder()
                .id(1L)
                .targetCode("CT-S1")
                .scopeKey(SalesTarget.buildScopeKey(ym.getYear(), ym.getMonthValue(), 10L, null))
                .targetYear(ym.getYear())
                .targetMonth(ym.getMonthValue())
                .scopeType(SalesTargetScopeType.EMPLOYEE)
                .user(salesUser1)
                .targetAmount(new BigDecimal("100000000"))
                .locked(false)
                .build();

        SalesTarget terTarget = SalesTarget.builder()
                .id(2L)
                .targetCode("CT-T1")
                .scopeKey(SalesTarget.buildScopeKey(ym.getYear(), ym.getMonthValue(), null, 100L))
                .targetYear(ym.getYear())
                .targetMonth(ym.getMonthValue())
                .scopeType(SalesTargetScopeType.TERRITORY)
                .territory(territoryHcm)
                .targetAmount(new BigDecimal("150000000"))
                .locked(false)
                .build();

        Dealer dealerHcm = Dealer.builder()
                .id(301L)
                .code("DL-HCM")
                .name("Đại lý HCM")
                .assignedUser(salesUser1)
                .territory(territoryHcm)
                .build();

        Order o1 = Order.builder()
                .id(2001L)
                .orderCode("DH-2001")
                .dealer(dealerHcm)
                .status(OrderStatus.DELIVERED.name())
                .totalAmount(new BigDecimal("110000000"))
                .recordedAt(LocalDateTime.now())
                .build();

        when(salesTargetRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class), any(org.springframework.data.domain.Sort.class)))
                .thenReturn(List.of(empTarget, terTarget));
        when(salesTargetRepository.findValidOrdersInPeriod(anyCollection(), any(), any()))
                .thenReturn(List.of(o1));

        SalesTargetSummaryResponse summary = salesTargetService.getSummary(
                ym.getYear(), ym.getMonthValue(), null, null, null);

        assertThat(summary.getTotalTargets()).isEqualTo(2);
        assertThat(summary.getEmployeeTargetCount()).isEqualTo(1);
        assertThat(summary.getTerritoryTargetCount()).isEqualTo(1);
        assertThat(summary.getTotalActualAmount()).isEqualByComparingTo("220000000");
        assertThat(summary.getAchievedCount()).isEqualTo(1);
    }
}
