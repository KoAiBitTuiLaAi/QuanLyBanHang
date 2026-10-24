package com.quanlybanhang.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanlybanhang.config.RolePermissionConfig;
import com.quanlybanhang.config.SecurityConfig;
import com.quanlybanhang.controller.InvoiceController;
import com.quanlybanhang.dto.request.CreateInvoiceAdjustmentRequest;
import com.quanlybanhang.dto.request.CreateInvoiceRequest;
import com.quanlybanhang.dto.response.InvoiceAdjustmentResponse;
import com.quanlybanhang.dto.response.InvoiceResponse;
import com.quanlybanhang.exception.GlobalExceptionHandler;
import com.quanlybanhang.model.InvoiceAdjustmentType;
import com.quanlybanhang.service.InvoicePdfService;
import com.quanlybanhang.service.InvoiceService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(InvoiceController.class)
@Import({SecurityConfig.class, RolePermissionConfig.class, RestAccessDeniedHandler.class, AuthEntryPointJwt.class, GlobalExceptionHandler.class, ObjectMapper.class})
@DisplayName("InvoiceSecurityTest - Kiểm thử phân quyền ma trận Database-Backed cho Hóa đơn bán hàng (S7-01)")
class InvoiceSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private InvoiceService invoiceService;

    @MockBean
    private InvoicePdfService invoicePdfService;

    @MockBean
    private UserDetailsServiceImpl userDetailsService;

    @MockBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @BeforeEach
    void passThrough() throws Exception {
        doAnswer(i -> {
            ((FilterChain) i.getArgument(2)).doFilter(i.getArgument(0), i.getArgument(1));
            return null;
        }).when(jwtAuthenticationFilter).doFilter(any(), any(), any());
    }

    @Test
    @DisplayName("Chưa đăng nhập -> 401 Unauthorized")
    void unauthenticated_Returns401() throws Exception {
        mockMvc.perform(get("/api/invoices"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/invoices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Không có quyền INVOICE_VIEW -> GET /api/invoices trả 403 Forbidden")
    void getInvoices_WithoutPermission_Returns403() throws Exception {
        mockMvc.perform(get("/api/invoices")
                        .with(user("sales").authorities(new SimpleGrantedAuthority("ROLE_SALES"), new SimpleGrantedAuthority("ORDER_VIEW"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Có quyền INVOICE_VIEW -> GET /api/invoices/1 trả 200 OK")
    void getInvoices_WithPermission_Returns200() throws Exception {
        when(invoiceService.getById(1L))
                .thenReturn(InvoiceResponse.builder().id(1L).invoiceCode("HD-00000001").build());

        mockMvc.perform(get("/api/invoices/1")
                        .with(user("viewer").authorities(new SimpleGrantedAuthority("ROLE_VIEWER"), new SimpleGrantedAuthority("INVOICE_VIEW"))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Không có quyền INVOICE_CREATE -> POST /api/invoices trả 403 Forbidden (chặn cả khi có role Kế toán nếu bị thu hồi quyền, không fallback)")
    void issueInvoice_WithoutPermission_Returns403() throws Exception {
        CreateInvoiceRequest request = CreateInvoiceRequest.builder()
                .dealerId(1L)
                .orderIds(List.of(101L))
                .build();

        // Kế toán nhưng bị thu hồi quyền INVOICE_CREATE trên ma trận -> Phải bị chặn 403 (không fallback theo role)
        mockMvc.perform(post("/api/invoices")
                        .with(user("accountant").authorities(new SimpleGrantedAuthority("ROLE_ACCOUNTANT"), new SimpleGrantedAuthority("INVOICE_VIEW")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Có quyền INVOICE_CREATE -> POST /api/invoices trả 200 OK")
    void issueInvoice_WithPermission_Returns200() throws Exception {
        CreateInvoiceRequest request = CreateInvoiceRequest.builder()
                .dealerId(1L)
                .orderIds(List.of(101L))
                .build();

        when(invoiceService.issueInvoice(any(), any())).thenReturn(InvoiceResponse.builder().id(1L).invoiceCode("HD-00000001").build());

        mockMvc.perform(post("/api/invoices")
                        .with(user("accountant").authorities(new SimpleGrantedAuthority("ROLE_ACCOUNTANT"), new SimpleGrantedAuthority("INVOICE_CREATE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Không có quyền INVOICE_ADJUST -> POST /api/invoices/1/adjustments trả 403 Forbidden")
    void createAdjustment_WithoutPermission_Returns403() throws Exception {
        CreateInvoiceAdjustmentRequest request = CreateInvoiceAdjustmentRequest.builder()
                .adjustmentType(InvoiceAdjustmentType.DECREASE)
                .adjustmentReason("Chiết khấu bổ sung")
                .adjustmentAmount(new BigDecimal("50000"))
                .build();

        mockMvc.perform(post("/api/invoices/1/adjustments")
                        .with(user("staff").authorities(new SimpleGrantedAuthority("ROLE_SALES"), new SimpleGrantedAuthority("INVOICE_VIEW")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Có quyền INVOICE_ADJUST -> POST /api/invoices/1/adjustments trả 200 OK")
    void createAdjustment_WithPermission_Returns200() throws Exception {
        CreateInvoiceAdjustmentRequest request = CreateInvoiceAdjustmentRequest.builder()
                .adjustmentType(InvoiceAdjustmentType.DECREASE)
                .adjustmentReason("Chiết khấu bổ sung")
                .adjustmentAmount(new BigDecimal("50000"))
                .build();

        when(invoiceService.createAdjustment(eq(1L), any(), any()))
                .thenReturn(InvoiceAdjustmentResponse.builder().id(10L).adjustmentCode("DC-00000001-01").build());

        mockMvc.perform(post("/api/invoices/1/adjustments")
                        .with(user("accountant").authorities(new SimpleGrantedAuthority("ROLE_ACCOUNTANT"), new SimpleGrantedAuthority("INVOICE_ADJUST")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Quản trị viên ROLE_ADMIN luôn có toàn quyền phát hành và xem hóa đơn")
    void admin_AlwaysAllowed() throws Exception {
        CreateInvoiceRequest request = CreateInvoiceRequest.builder()
                .dealerId(1L)
                .orderIds(List.of(101L))
                .build();

        when(invoiceService.issueInvoice(any(), any())).thenReturn(InvoiceResponse.builder().id(1L).invoiceCode("HD-00000001").build());

        mockMvc.perform(post("/api/invoices")
                        .with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        when(invoiceService.getById(1L))
                .thenReturn(InvoiceResponse.builder().id(1L).invoiceCode("HD-00000001").build());

        mockMvc.perform(get("/api/invoices/1")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk());
    }
}
