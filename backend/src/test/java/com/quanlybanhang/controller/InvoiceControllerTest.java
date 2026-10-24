package com.quanlybanhang.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.quanlybanhang.dto.request.CreateInvoiceAdjustmentRequest;
import com.quanlybanhang.dto.request.CreateInvoiceRequest;
import com.quanlybanhang.dto.request.InvoicePreviewRequest;
import com.quanlybanhang.dto.response.*;
import com.quanlybanhang.model.InvoiceAdjustmentType;
import com.quanlybanhang.model.InvoiceStatus;
import com.quanlybanhang.security.AuthEntryPointJwt;
import com.quanlybanhang.security.RestAccessDeniedHandler;
import com.quanlybanhang.security.JwtAuthenticationFilter;
import com.quanlybanhang.security.UserDetailsServiceImpl;
import com.quanlybanhang.service.InvoicePdfService;
import com.quanlybanhang.service.InvoiceService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(InvoiceController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("InvoiceControllerTest - Kiểm thử REST Endpoints Hóa đơn bán hàng (S7-01)")
class InvoiceControllerTest {

    @Test
    void adjustmentPdfMustBelongToRequestedInvoice() throws Exception {
        when(invoiceService.getAdjustments(1L)).thenReturn(List.of(
                InvoiceAdjustmentResponse.builder().id(11L).invoiceId(1L).build()));
        mockMvc.perform(get("/api/invoices/1/adjustments/22/pdf"))
                .andExpect(status().isBadRequest());
        verify(invoicePdfService, never()).generateAdjustmentPdf(anyLong(), anyString());
        when(invoicePdfService.generateAdjustmentPdf(eq(11L), anyString()))
                .thenReturn("%PDF-test".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        mockMvc.perform(get("/api/invoices/1/adjustments/11/pdf"))
                .andExpect(status().isOk()).andExpect(content().contentType(MediaType.APPLICATION_PDF));
    }

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

    @MockBean
    private AuthEntryPointJwt authEntryPointJwt;

    @MockBean
    private RestAccessDeniedHandler restAccessDeniedHandler;

    @BeforeEach
    void setUp() throws Exception {
        doAnswer(invocation -> {
            FilterChain chain = invocation.getArgument(2);
            chain.doFilter(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(jwtAuthenticationFilter).doFilter(any(), any(), any());
    }

    @Test
    @DisplayName("GET /api/invoices/eligible-orders - Trả về danh sách phiếu giao hợp lệ")
    void getEligibleOrders_ReturnsSuccess() throws Exception {
        EligibleDeliveryOrderResponse resp = EligibleDeliveryOrderResponse.builder()
                .orderId(101L)
                .orderCode("DH-001")
                .actualDeliveryDate(LocalDate.now())
                .deliveredTotalAmount(new BigDecimal("1500000.00"))
                .itemCount(2)
                .build();

        when(invoiceService.getEligibleOrders(eq(1L), any(), any())).thenReturn(List.of(resp));

        mockMvc.perform(get("/api/invoices/eligible-orders")
                        .param("dealerId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].orderCode").value("DH-001"))
                .andExpect(jsonPath("$.data[0].deliveredTotalAmount").value(1500000.00));
    }

    @Test
    @DisplayName("POST /api/invoices/preview - Xem trước thông tin hóa đơn")
    void previewInvoice_ReturnsSuccess() throws Exception {
        InvoicePreviewRequest request = InvoicePreviewRequest.builder()
                .dealerId(1L)
                .orderIds(List.of(101L, 102L))
                .build();

        InvoicePreviewResponse preview = InvoicePreviewResponse.builder()
                .dealerId(1L)
                .dealerCode("DL-001")
                .dealerName("Đại lý Minh Phát")
                .totalOrders(2)
                .subtotalAmount(new BigDecimal("3000000.00"))
                .discountAmount(new BigDecimal("150000.00"))
                .totalAmount(new BigDecimal("2850000.00"))
                .build();

        when(invoiceService.previewInvoice(any(), any())).thenReturn(preview);

        mockMvc.perform(post("/api/invoices/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.dealerCode").value("DL-001"))
                .andExpect(jsonPath("$.data.totalOrders").value(2))
                .andExpect(jsonPath("$.data.totalAmount").value(2850000.00));
    }

    @Test
    @DisplayName("POST /api/invoices - Phát hành hóa đơn thành công")
    void issueInvoice_ReturnsSuccess() throws Exception {
        CreateInvoiceRequest request = CreateInvoiceRequest.builder()
                .dealerId(1L)
                .orderIds(List.of(101L))
                .note("Hóa đơn giao đợt 1")
                .build();

        InvoiceResponse response = InvoiceResponse.builder()
                .id(1L)
                .invoiceNumber("00000001")
                .invoiceCode("HD-00000001")
                .status(InvoiceStatus.ISSUED)
                .statusLabel("Đã phát hành")
                .dealerId(1L)
                .dealerCode("DL-001")
                .dealerName("Đại lý Minh Phát")
                .totalAmount(new BigDecimal("2280000.00"))
                .build();

        when(invoiceService.issueInvoice(any(), any())).thenReturn(response);

        mockMvc.perform(post("/api/invoices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.invoiceNumber").value("00000001"))
                .andExpect(jsonPath("$.data.invoiceCode").value("HD-00000001"))
                .andExpect(jsonPath("$.data.totalAmount").value(2280000.00));
    }

    @Test
    @DisplayName("GET /api/invoices/{id} - Lấy chi tiết hóa đơn")
    void getById_ReturnsSuccess() throws Exception {
        InvoiceResponse response = InvoiceResponse.builder()
                .id(1L)
                .invoiceNumber("00000001")
                .invoiceCode("HD-00000001")
                .status(InvoiceStatus.ISSUED)
                .dealerCode("DL-001")
                .dealerName("Đại lý Minh Phát")
                .totalAmount(new BigDecimal("2280000.00"))
                .build();

        when(invoiceService.getById(1L)).thenReturn(response);

        mockMvc.perform(get("/api/invoices/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.invoiceCode").value("HD-00000001"));
    }

    @Test
    @DisplayName("GET /api/invoices - Tìm kiếm và phân trang hóa đơn")
    void search_ReturnsPage() throws Exception {
        InvoiceResponse response = InvoiceResponse.builder()
                .id(1L)
                .invoiceNumber("00000001")
                .invoiceCode("HD-00000001")
                .status(InvoiceStatus.ISSUED)
                .totalAmount(new BigDecimal("2280000.00"))
                .build();

        when(invoiceService.search(any(), any(), any(), any(), any(), eq(0), eq(20)))
                .thenReturn(new PageImpl<>(List.of(response)));

        mockMvc.perform(get("/api/invoices")
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content[0].invoiceCode").value("HD-00000001"));
    }

    @Test
    @DisplayName("POST /api/invoices/{id}/adjustments - Lập chứng từ điều chỉnh")
    void createAdjustment_ReturnsSuccess() throws Exception {
        CreateInvoiceAdjustmentRequest request = CreateInvoiceAdjustmentRequest.builder()
                .adjustmentType(InvoiceAdjustmentType.DECREASE)
                .adjustmentReason("Chiết khấu bổ sung thanh toán sớm")
                .adjustmentAmount(new BigDecimal("100000.00"))
                .build();

        InvoiceAdjustmentResponse adjResponse = InvoiceAdjustmentResponse.builder()
                .id(10L)
                .adjustmentCode("DC-00000001-01")
                .invoiceId(1L)
                .invoiceCode("HD-00000001")
                .adjustmentType(InvoiceAdjustmentType.DECREASE)
                .adjustmentReason("Chiết khấu bổ sung thanh toán sớm")
                .adjustmentAmount(new BigDecimal("-100000.00"))
                .build();

        when(invoiceService.createAdjustment(eq(1L), any(), any())).thenReturn(adjResponse);

        mockMvc.perform(post("/api/invoices/1/adjustments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.adjustmentCode").value("DC-00000001-01"))
                .andExpect(jsonPath("$.data.adjustmentAmount").value(-100000.00));
    }

    @Test
    @DisplayName("GET /api/invoices/{id}/pdf - Xuất file PDF thành công kèm header application/pdf")
    void getInvoicePdf_ReturnsPdfBytes() throws Exception {
        byte[] pdfBytes = "%PDF-1.4 Fake PDF Content".getBytes();
        when(invoicePdfService.generateInvoicePdf(eq(1L), any())).thenReturn(pdfBytes);

        InvoiceResponse response = InvoiceResponse.builder()
                .id(1L)
                .invoiceNumber("00000001")
                .build();
        when(invoiceService.getById(1L)).thenReturn(response);

        mockMvc.perform(get("/api/invoices/1/pdf"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string("Content-Disposition", "inline; filename=\"hoa-don-00000001.pdf\""));
    }

    @Test
    @DisplayName("GET /api/invoices/{id}/print-preview - Xem trước bản in HTML")
    void getPrintPreview_ReturnsHtml() throws Exception {
        String html = "<html><body><h1>HÓA ĐƠN BÁN HÀNG</h1></body></html>";
        when(invoicePdfService.generateInvoicePrintHtml(eq(1L), any())).thenReturn(html);

        mockMvc.perform(get("/api/invoices/1/print-preview"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("HÓA ĐƠN BÁN HÀNG")));
    }
}
