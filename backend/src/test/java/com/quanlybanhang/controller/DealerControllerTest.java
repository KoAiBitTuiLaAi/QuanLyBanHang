package com.quanlybanhang.controller;

import com.quanlybanhang.config.SecurityConfig;
import com.quanlybanhang.dto.request.AssignDealerRequest;
import com.quanlybanhang.dto.request.BulkHandoverRequest;
import com.quanlybanhang.dto.response.BulkHandoverResponse;
import com.quanlybanhang.dto.response.DealerAssignmentHistoryResponse;
import com.quanlybanhang.dto.response.DealerDebtResponse;
import com.quanlybanhang.dto.response.DealerOptionsResponse;
import com.quanlybanhang.dto.response.DealerResponse;
import com.quanlybanhang.exception.DealerHasTransactionsException;
import com.quanlybanhang.exception.GlobalExceptionHandler;
import com.quanlybanhang.model.CustomerGroup;
import com.quanlybanhang.security.AuthEntryPointJwt;
import com.quanlybanhang.security.JwtAuthenticationFilter;
import com.quanlybanhang.security.RestAccessDeniedHandler;
import com.quanlybanhang.security.UserDetailsServiceImpl;
import com.quanlybanhang.service.DealerDebtService;
import com.quanlybanhang.service.DealerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DealerController.class)
@Import({SecurityConfig.class, RestAccessDeniedHandler.class, AuthEntryPointJwt.class, GlobalExceptionHandler.class})
class DealerControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private DealerService dealerService;

    @MockBean
    private DealerDebtService dealerDebtService;

    @MockBean
    private UserDetailsServiceImpl userDetailsService;

    @MockBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @MockBean
    private com.quanlybanhang.service.OrderService orderService;

    @BeforeEach
    void passThroughJwtFilter() throws Exception {
        doAnswer(invocation -> {
            jakarta.servlet.FilterChain chain = invocation.getArgument(2);
            chain.doFilter(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(jwtAuthenticationFilter).doFilter(any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACCOUNTANT", "SALES_MANAGER", "ADMIN", "SALES", "VIEWER"})
    @DisplayName("Các vai trò hợp lệ được phép xem danh sách đại lý (200 OK)")
    void allowedRolesCanReadDealers(String role) throws Exception {
        when(dealerService.getAllDealers(any(), any(), any(), any(), any(), any(), any())).thenReturn(
                new PageImpl<>(List.of(DealerResponse.builder()
                        .id(1L)
                        .code("DL001")
                        .name("Đại lý Cấp 1 Minh Phát")
                        .customerGroup(CustomerGroup.DAI_LY_CAP_1)
                        .customerGroupDisplayName("Đại lý cấp 1")
                        .active(true)
                        .build()))
        );

        mvc.perform(get("/api/dealers").with(user("reader").roles(role)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].code").value("DL001"))
                .andExpect(jsonPath("$.data.content[0].name").value("Đại lý Cấp 1 Minh Phát"))
                .andExpect(jsonPath("$.data.content[0].customerGroupDisplayName").value("Đại lý cấp 1"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACCOUNTANT", "SALES_MANAGER", "ADMIN"})
    @DisplayName("Kế toán, Quản lý kinh doanh và Admin được phép tạo đại lý mới (200 OK)")
    void managementRolesCanCreateDealer(String role) throws Exception {
        DealerResponse created = DealerResponse.builder()
                .id(1L)
                .code("DL001")
                .name("Đại lý Cấp 1 Minh Phát")
                .customerGroup(CustomerGroup.DAI_LY_CAP_1)
                .debtLimit(new BigDecimal("100000000"))
                .maxDebtDays(30)
                .active(true)
                .build();

        when(dealerService.createDealer(any(), any())).thenReturn(created);

        mvc.perform(post("/api/dealers").with(user("staff").roles(role))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                          "code": "DL001",
                          "name": "Đại lý Cấp 1 Minh Phát",
                          "customerGroup": "DAI_LY_CAP_1",
                          "taxCode": "0101234567",
                          "phone": "0981234567",
                          "email": "minhphat@dealer.vn",
                          "address": "123 Lê Lợi, TP.HCM",
                          "debtLimit": 100000000,
                          "maxDebtDays": 30,
                          "active": true
                        }
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.code").value("DL001"))
                .andExpect(jsonPath("$.data.name").value("Đại lý Cấp 1 Minh Phát"))
                .andExpect(jsonPath("$.data.maxDebtDays").value(30));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SALES", "VIEWER", "WAREHOUSE_STAFF", "WAREHOUSE_MANAGER"})
    @DisplayName("Các vai trò không đủ quyền bị chặn khi cố gắng tạo, sửa hoặc xóa đại lý (403 Forbidden)")
    void nonManagementRolesCannotModifyDealers(String role) throws Exception {
        mvc.perform(post("/api/dealers").with(user("user").roles(role))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                          "code": "DL001",
                          "name": "Đại lý Test",
                          "customerGroup": "DAI_LY_CAP_1"
                        }
                        """))
                .andExpect(status().isForbidden());

        mvc.perform(put("/api/dealers/1").with(user("user").roles(role))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                          "code": "DL001",
                          "name": "Đại lý Test",
                          "customerGroup": "DAI_LY_CAP_1"
                        }
                        """))
                .andExpect(status().isForbidden());

        mvc.perform(delete("/api/dealers/1").with(user("user").roles(role)))
                .andExpect(status().isForbidden());

        mvc.perform(patch("/api/dealers/1/status").param("active", "false").with(user("user").roles(role)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(dealerService);
    }

    @Test
    @DisplayName("Chưa đăng nhập thì không thể truy cập API đại lý (401 Unauthorized)")
    void unauthenticatedAccessRejected() throws Exception {
        mvc.perform(get("/api/dealers"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Xóa đại lý đã phát sinh giao dịch phải trả về 400 Bad Request kèm thông báo chi tiết (S3-03)")
    void deleteDealerWithTransactionsReturnsBadRequest() throws Exception {
        doThrow(new DealerHasTransactionsException("DL001", "Đại lý Minh Phát"))
                .when(dealerService).deleteDealer(eq(1L), any());

        mvc.perform(delete("/api/dealers/1").with(user("accountant").roles("ACCOUNTANT")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(
                        "Đại lý 'Đại lý Minh Phát' (DL001) đã phát sinh giao dịch không thể xoá, chỉ có thể chuyển sang trạng thái ngừng giao dịch."));
    }

    @Test
    @DisplayName("Dữ liệu khai báo thiếu mã hoặc tên đại lý bị từ chối (400 Bad Request)")
    void invalidDataRejected() throws Exception {
        mvc.perform(post("/api/dealers").with(user("accountant").roles("ACCOUNTANT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                          "code": "",
                          "name": "",
                          "customerGroup": null
                        }
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        verifyNoInteractions(dealerService);
    }

    @Test
    @DisplayName("Khai báo số dòng mỗi trang vượt quá 200 bị từ chối")
    void excessivePageSizeRejected() throws Exception {
        mvc.perform(get("/api/dealers?size=250").with(user("accountant").roles("ACCOUNTANT")))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(dealerService);
    }

    @Test
    @DisplayName("Lấy danh mục tham chiếu options trả về 200 OK")
    void getOptionsReturnsOk() throws Exception {
        when(dealerService.getDealerOptions()).thenReturn(
                DealerOptionsResponse.builder()
                        .customerGroups(List.of(new DealerOptionsResponse.CustomerGroupOption("DAI_LY_CAP_1", "Đại lý cấp 1")))
                        .territories(List.of())
                        .assignedUsers(List.of())
                        .build());

        mvc.perform(get("/api/dealers/options").with(user("accountant").roles("ACCOUNTANT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.customerGroups[0].value").value("DAI_LY_CAP_1"));
    }

    @Test
    @DisplayName("Khai báo số ngày nợ tối đa âm bị từ chối (400 Bad Request)")
    void negativeMaxDebtDaysRejected() throws Exception {
        mvc.perform(post("/api/dealers").with(user("accountant").roles("ACCOUNTANT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                          "code": "DL002",
                          "name": "Đại lý Test",
                          "customerGroup": "DAI_LY_CAP_1",
                          "maxDebtDays": -5
                        }
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        verifyNoInteractions(dealerService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACCOUNTANT", "SALES_MANAGER", "ADMIN"})
    @DisplayName("Kế toán công nợ, Quản lý KD và Admin được phép khoá giao dịch đại lý kèm cảnh báo đơn dở (S3-07)")
    void allowedRolesCanLockDealer(String role) throws Exception {
        DealerResponse lockedDealer = DealerResponse.builder()
                .id(1L)
                .code("DL001")
                .name("Đại lý Minh Phát")
                .locked(true)
                .lockReason("Mất khả năng thanh toán")
                .inProgressOrderCount(3L)
                .build();

        when(dealerService.lockDealer(eq(1L), eq("Mất khả năng thanh toán"), any())).thenReturn(lockedDealer);

        mvc.perform(post("/api/dealers/1/lock").with(user("accountant").roles(role))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                          "reason": "Mất khả năng thanh toán"
                        }
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.locked").value(true))
                .andExpect(jsonPath("$.data.lockReason").value("Mất khả năng thanh toán"))
                .andExpect(jsonPath("$.message").value(
                        "Đã khoá giao dịch với đại lý 'Đại lý Minh Phát'. Cảnh báo: Đại lý hiện có 3 đơn hàng đang dở dang cần theo dõi xử lý."));
    }

    @Test
    @DisplayName("Khoá giao dịch đại lý bắt buộc phải nhập lý do (S3-07)")
    void lockDealerRequiresReason() throws Exception {
        mvc.perform(post("/api/dealers/1/lock").with(user("accountant").roles("ACCOUNTANT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                          "reason": ""
                        }
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.reason").value("Bắt buộc nhập lý do khoá"));

        verify(dealerService, never()).lockDealer(any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"SALES", "VIEWER", "WAREHOUSE_STAFF", "WAREHOUSE_MANAGER"})
    @DisplayName("Các vai trò không đủ quyền bị chặn khi khoá hoặc mở khoá đại lý (403 Forbidden - S3-07)")
    void unauthorizedRolesCannotLockOrUnlockDealer(String role) throws Exception {
        mvc.perform(post("/api/dealers/1/lock").with(user("user").roles(role))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                          "reason": "Test lý do"
                        }
                        """))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/dealers/1/unlock").with(user("user").roles(role)))
                .andExpect(status().isForbidden());

        verify(dealerService, never()).lockDealer(any(), any(), any());
        verify(dealerService, never()).unlockDealer(any(), any());
    }

    @Test
    @DisplayName("Kế toán công nợ mở khoá giao dịch cho đại lý thành công (S3-07)")
    void unlockDealerSuccess() throws Exception {
        DealerResponse unlockedDealer = DealerResponse.builder()
                .id(1L)
                .code("DL001")
                .name("Đại lý Minh Phát")
                .locked(false)
                .lockReason(null)
                .build();

        when(dealerService.unlockDealer(eq(1L), any())).thenReturn(unlockedDealer);

        mvc.perform(post("/api/dealers/1/unlock").with(user("accountant").roles("ACCOUNTANT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.locked").value(false))
                .andExpect(jsonPath("$.message").value("Đã mở khoá giao dịch cho đại lý 'Đại lý Minh Phát'"));
    }

    @Test
    @DisplayName("Xem danh sách đơn hàng đang dở dang của đại lý (S3-07)")
    void getInProgressOrdersSuccess() throws Exception {
        when(dealerService.getInProgressOrders(1L)).thenReturn(List.of(
                com.quanlybanhang.dto.response.OrderResponse.builder()
                        .id(10L)
                        .orderCode("DH-100")
                        .status("PENDING_APPROVAL")
                        .dealerLocked(true)
                        .dealerLockWarning("Cảnh báo: Đại lý đang bị khoá giao dịch")
                        .build()
        ));

        mvc.perform(get("/api/dealers/1/in-progress-orders").with(user("accountant").roles("ACCOUNTANT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].orderCode").value("DH-100"))
                .andExpect(jsonPath("$.data[0].dealerLocked").value(true));
    }

    // ═════════════════════════════════════════════════════════════════════
    // S3-06: PHÂN CÔNG & CHUYỂN GIAO ĐỊA BÀN CONTROLLER TESTS
    // ═════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("[S3-06] Quản lý kinh doanh được phép phân công đại lý cho nhân viên (200 OK)")
    void salesManagerCanAssignDealer() throws Exception {
        DealerResponse res = DealerResponse.builder().id(1L).code("DL001").name("Đại lý 1").assignedUserId(2L).build();
        when(dealerService.assignDealer(eq(1L), any(), any())).thenReturn(res);

        mvc.perform(post("/api/dealers/1/assign")
                        .with(user("sales_mgr").roles("SALES_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                          "assignedUserId": 2,
                          "reason": "Phân công phụ trách địa bàn Quận 1"
                        }
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assignedUserId").value(2));
    }

    @Test
    @DisplayName("[S3-06] Quản lý kinh doanh được phép chuyển giao địa bàn và đại lý hàng loạt (200 OK)")
    void salesManagerCanBulkHandover() throws Exception {
        BulkHandoverResponse res = BulkHandoverResponse.builder()
                .totalTransferred(2)
                .fromUserId(10L)
                .toUserId(20L)
                .transferredDealerIds(List.of(1L, 2L))
                .reason("Nhân viên nghỉ việc")
                .transferredAt(LocalDateTime.now())
                .build();

        when(dealerService.bulkHandover(any(), any())).thenReturn(res);

        mvc.perform(post("/api/dealers/handover/bulk")
                        .with(user("sales_mgr").roles("SALES_MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                          "fromUserId": 10,
                          "toUserId": 20,
                          "reason": "Nhân viên nghỉ việc"
                        }
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalTransferred").value(2))
                .andExpect(jsonPath("$.data.fromUserId").value(10))
                .andExpect(jsonPath("$.data.toUserId").value(20));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SALES", "ACCOUNTANT", "VIEWER", "WAREHOUSE_STAFF"})
    @DisplayName("[S3-06] Nhân viên kinh doanh, kế toán, viewer không được phép gọi API chuyển giao hàng loạt (403 Forbidden)")
    void unauthorizedRolesCannotBulkHandover(String role) throws Exception {
        mvc.perform(post("/api/dealers/handover/bulk")
                        .with(user("staff").roles(role))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                        {
                          "fromUserId": 10,
                          "toUserId": 20,
                          "reason": "Thử chuyển trái phép"
                        }
                        """))
                .andExpect(status().isForbidden());

        verify(dealerService, never()).bulkHandover(any(), any());
    }

    @Test
    @DisplayName("[S3-06] Quản lý kinh doanh xem danh sách đại lý cần bàn giao (200 OK)")
    void salesManagerCanGetPendingHandover() throws Exception {
        when(dealerService.getPendingHandoverDealers()).thenReturn(
                List.of(DealerResponse.builder().id(1L).code("DL01").handoverRequired(true).build())
        );

        mvc.perform(get("/api/dealers/handover/pending").with(user("sales_mgr").roles("SALES_MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].code").value("DL01"))
                .andExpect(jsonPath("$.data[0].handoverRequired").value(true));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SALES_MANAGER", "ADMIN", "ACCOUNTANT", "VIEWER"})
    @DisplayName("[S3-06] Các vai trò được phép tra cứu lịch sử chuyển giao đại lý (200 OK)")
    void allowedRolesCanGetHandoverHistory(String role) throws Exception {
        when(dealerService.getHandoverHistory(any(), any(), any(), any(), any(), any(), any())).thenReturn(
                new PageImpl<>(List.of(DealerAssignmentHistoryResponse.builder()
                        .id(1L)
                        .dealerCode("DL01")
                        .fromUsername("sales_old")
                        .toUsername("sales_new")
                        .reason("Bàn giao")
                        .build()))
        );

        mvc.perform(get("/api/dealers/handover/history").with(user("reader").roles(role)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].dealerCode").value("DL01"))
                .andExpect(jsonPath("$.data.content[0].fromUsername").value("sales_old"));
    }

    @Test
    @DisplayName("[S3-06] Nhân viên kinh doanh xem đại lý không thuộc quyền phụ trách bị trả về 403 Forbidden")
    void salesAccessDeniedWhenViewingOtherDealer() throws Exception {
        doThrow(new AccessDeniedException("Bạn chỉ có quyền xem đại lý do mình phụ trách"))
                .when(dealerService).getDealerById(999L);

        mvc.perform(get("/api/dealers/999").with(user("sales01").roles("SALES")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Bạn không có quyền thực hiện chức năng này"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACCOUNTANT", "SALES_MANAGER", "ADMIN", "SALES", "VIEWER"})
    @DisplayName("[S4-02] Các vai trò hợp lệ được phép xem thông tin công nợ đại lý (200 OK)")
    void allowedRolesCanGetDealerDebt(String role) throws Exception {
        DealerDebtResponse debtResponse = DealerDebtResponse.builder()
                .dealerId(1L)
                .dealerCode("DL01")
                .dealerName("Đại lý Cấp 1 Minh Phát")
                .currentDebt(new BigDecimal("15000000"))
                .debtLimit(new BigDecimal("50000000"))
                .remainingLimit(new BigDecimal("35000000"))
                .maxDebtDays(30)
                .hasOverdueDebt(false)
                .isBlocked(false)
                .build();

        when(dealerDebtService.getDealerDebtInfo(1L)).thenReturn(debtResponse);

        mvc.perform(get("/api/dealers/1/debt").with(user("reader").roles(role)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.dealerCode").value("DL01"))
                .andExpect(jsonPath("$.data.currentDebt").value(15000000))
                .andExpect(jsonPath("$.data.remainingLimit").value(35000000));
    }

    @Test
    @DisplayName("[S4-02] Nhân viên kinh doanh xem công nợ đại lý không thuộc quyền phụ trách bị trả về 403 Forbidden")
    void salesAccessDeniedWhenViewingOtherDealerDebt() throws Exception {
        when(dealerDebtService.getDealerDebtInfo(999L))
                .thenThrow(new AccessDeniedException("Bạn chỉ có quyền xem công nợ của đại lý do mình phụ trách"));

        mvc.perform(get("/api/dealers/999/debt").with(user("sales01").roles("SALES")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Bạn không có quyền thực hiện chức năng này"));
    }
}
