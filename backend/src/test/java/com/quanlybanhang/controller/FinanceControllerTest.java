package com.quanlybanhang.controller;

import com.quanlybanhang.dto.request.DebtLimitRequest;
import com.quanlybanhang.dto.response.ApiResponse;
import com.quanlybanhang.model.Dealer;
import com.quanlybanhang.model.Product;
import com.quanlybanhang.repository.DealerRepository;
import com.quanlybanhang.repository.ProductRepository;
import com.quanlybanhang.service.AuditLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;

import java.math.BigDecimal;
import java.util.Map;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FinanceControllerTest {

    @Mock
    private DealerRepository dealerRepository;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private Authentication authentication;

    @Mock
    private com.quanlybanhang.service.DealerDebtService dealerDebtService;

    private FinanceController financeController;

    @BeforeEach
    void setUp() {
        financeController = new FinanceController(dealerRepository, auditLogService, productRepository, dealerDebtService);
    }

    @Test
    @DisplayName("Cập nhật hạn mức công nợ và số ngày nợ tối đa phải lưu DB và ghi nhật ký kèm lý do (S3-05)")
    void updateDebtLimitWritesAuditLog() {
        Dealer dealer = Dealer.builder()
                .id(7L)
                .name("Đại lý Minh Anh")
                .debtLimit(new BigDecimal("20000000"))
                .maxDebtDays(30)
                .build();
        DebtLimitRequest request = new DebtLimitRequest();
        request.setDebtLimit(new BigDecimal("50000000"));
        request.setMaxDebtDays(45);
        request.setReason("Tăng hạn mức quý 4");

        when(dealerRepository.findById(7L)).thenReturn(Optional.of(dealer));
        when(authentication.getName()).thenReturn("accountant01");

        ApiResponse response = financeController.updateDebtLimit(7L, request, authentication);

        assertEquals(new BigDecimal("50000000"), dealer.getDebtLimit());
        assertEquals(45, dealer.getMaxDebtDays());
        assertEquals("Đã cập nhật hạn mức công nợ", response.getMessage());
        assertEquals(new BigDecimal("50000000"), ((Map<?, ?>) response.getData()).get("debtLimit"));
        assertEquals(45, ((Map<?, ?>) response.getData()).get("maxDebtDays"));
        assertEquals("Tăng hạn mức quý 4", ((Map<?, ?>) response.getData()).get("reason"));
        verify(dealerRepository).save(dealer);
        verify(auditLogService).logAction(
                "DEBT_LIMIT", "7", "UPDATE", "20000000 (30 ngày)", "50000000 (45 ngày)", "Tăng hạn mức quý 4", "accountant01");
    }

    @Test
    @DisplayName("Bắt buộc nhập lý do khi thay đổi hạn mức công nợ (S3-05)")
    void updateDebtLimitRequiresReason() {
        Dealer dealer = Dealer.builder()
                .id(7L)
                .name("Đại lý Minh Anh")
                .debtLimit(new BigDecimal("20000000"))
                .maxDebtDays(30)
                .build();
        DebtLimitRequest request = new DebtLimitRequest();
        request.setDebtLimit(new BigDecimal("50000000"));
        request.setMaxDebtDays(45);
        request.setReason("   "); // empty / blank

        when(dealerRepository.findById(7L)).thenReturn(Optional.of(dealer));

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () ->
                financeController.updateDebtLimit(7L, request, authentication));
        verify(dealerRepository, never()).save(dealer);
    }

    @Test
    @DisplayName("Không tạo nhật ký giả khi hạn mức và số ngày nợ không thay đổi (S3-05)")
    void unchangedDebtLimitDoesNotWriteAuditLog() {
        Dealer dealer = Dealer.builder()
                .id(7L)
                .name("Đại lý Minh Anh")
                .debtLimit(new BigDecimal("50000000.00"))
                .maxDebtDays(30)
                .build();
        DebtLimitRequest request = new DebtLimitRequest();
        request.setDebtLimit(new BigDecimal("50000000"));
        request.setMaxDebtDays(30);
        request.setReason("Kiểm tra không đổi");

        when(dealerRepository.findById(7L)).thenReturn(Optional.of(dealer));

        ApiResponse response = financeController.updateDebtLimit(7L, request, authentication);

        assertEquals("Hạn mức công nợ không thay đổi", response.getMessage());
        verify(dealerRepository, never()).save(dealer);
        verify(auditLogService, never()).logAction(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("API xem công nợ trả về cả hạn mức tiền và số ngày nợ tối đa (S3-05)")
    void getDebtReturnsMaxDebtDays() {
        Dealer dealer = Dealer.builder()
                .id(1L)
                .name("Đại lý Cấp 1")
                .debtLimit(new BigDecimal("100000000"))
                .maxDebtDays(60)
                .build();
        when(dealerRepository.findAll()).thenReturn(java.util.List.of(dealer));

        ApiResponse response = financeController.getDebt();
        assertEquals(true, response.isSuccess());

        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> list = (java.util.List<Map<String, Object>>) response.getData();
        assertEquals(1, list.size());
        assertEquals(1L, list.get(0).get("dealerId"));
        assertEquals("Đại lý Cấp 1", list.get(0).get("customerName"));
        assertEquals(new BigDecimal("100000000"), list.get(0).get("debtLimit"));
        assertEquals(60, list.get(0).get("maxDebtDays"));
        assertNull(list.get(0).get("debtAmount"));
        assertEquals(false, list.get(0).get("debtAmountAvailable"));
    }

    @Test
    void financeReturnsEmptyListsWhenCatalogIsEmpty() {
        when(productRepository.findAll()).thenReturn(List.of());
        assertEquals(List.of(), financeController.getCostPrice().getData());
        assertEquals(List.of(), financeController.getProfitMargin().getData());
    }

    @Test
    @SuppressWarnings("unchecked")
    void financeUsesStoredPricesAndKeepsNegativeMargin() {
        Product product = Product.builder().id(9L).code("CATALOG-9").name("Hàng trong danh mục")
                .price(new BigDecimal("300")).costPrice(new BigDecimal("400")).unit("hộp").build();
        when(productRepository.findAll()).thenReturn(List.of(product));
        Map<String, Object> cost = ((List<Map<String, Object>>) financeController.getCostPrice().getData()).get(0);
        assertEquals("CATALOG-9", cost.get("productCode"));
        assertEquals(product.getCostPrice(), cost.get("costPrice"));
        Map<String, Object> margin = ((List<Map<String, Object>>) financeController.getProfitMargin().getData()).get(0);
        assertEquals(new BigDecimal("-33.33"), margin.get("marginPercent"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void marginIsUnavailableForZeroSellingPrice() {
        Product product = Product.builder().id(9L).code("CATALOG-9")
                .price(BigDecimal.ZERO).costPrice(new BigDecimal("400")).build();
        when(productRepository.findAll()).thenReturn(List.of(product));
        Map<String, Object> margin = ((List<Map<String, Object>>) financeController.getProfitMargin().getData()).get(0);
        assertNull(margin.get("marginPercent"));
    }

    @Test
    @DisplayName("S7-04: API lay cau hinh kiem soat rui ro cong no")
    void getDebtRiskConfig_returnsConfig() {
        com.quanlybanhang.dto.response.DebtRiskConfigResponse config = com.quanlybanhang.dto.response.DebtRiskConfigResponse.builder()
                .defaultAction(com.quanlybanhang.model.DebtFlagAction.BLOCK)
                .overdueAction(com.quanlybanhang.model.DebtFlagAction.BLOCK)
                .limitExceededAction(com.quanlybanhang.model.DebtFlagAction.REQUIRE_APPROVAL)
                .autoFlagEnabled(true)
                .build();
        when(dealerDebtService.getDebtRiskConfig()).thenReturn(config);

        ApiResponse response = financeController.getDebtRiskConfig();

        assertEquals(true, response.isSuccess());
        assertEquals(config, response.getData());
    }

    @Test
    @DisplayName("S7-04: API cap nhat cau hinh kiem soat rui ro cong no")
    void updateDebtRiskConfig_callsService() {
        com.quanlybanhang.dto.request.DebtRiskConfigRequest req = com.quanlybanhang.dto.request.DebtRiskConfigRequest.builder()
                .defaultAction(com.quanlybanhang.model.DebtFlagAction.REQUIRE_APPROVAL)
                .build();
        com.quanlybanhang.dto.response.DebtRiskConfigResponse res = com.quanlybanhang.dto.response.DebtRiskConfigResponse.builder()
                .defaultAction(com.quanlybanhang.model.DebtFlagAction.REQUIRE_APPROVAL)
                .build();
        when(authentication.getName()).thenReturn("accountant1");
        when(dealerDebtService.updateDebtRiskConfig(req, "accountant1")).thenReturn(res);

        ApiResponse response = financeController.updateDebtRiskConfig(req, authentication);

        assertEquals(true, response.isSuccess());
        assertEquals(res, response.getData());
    }

    @Test
    @DisplayName("S7-04: API lay danh sach dai ly bi gan co rui ro")
    void getFlaggedDealers_returnsList() {
        com.quanlybanhang.dto.response.FlaggedDealerResponse flagged = com.quanlybanhang.dto.response.FlaggedDealerResponse.builder()
                .dealerId(1L)
                .dealerCode("DL001")
                .debtFlagType(com.quanlybanhang.model.DebtFlagType.DEBT_LIMIT_EXCEEDED)
                .debtFlagReason("Vuot han muc no")
                .build();
        when(dealerDebtService.getFlaggedDealers()).thenReturn(List.of(flagged));

        ApiResponse response = financeController.getFlaggedDealers();

        assertEquals(true, response.isSuccess());
        assertEquals(1, ((List<?>) response.getData()).size());
    }

    @Test
    @DisplayName("S7-04: API danh gia lai cong no toan bo dai ly")
    void evaluateAllDealersDebt_callsService() {
        com.quanlybanhang.dto.response.DebtEvaluationSummaryResponse summary = com.quanlybanhang.dto.response.DebtEvaluationSummaryResponse.builder()
                .totalEvaluated(5)
                .totalFlagged(1)
                .build();
        when(dealerDebtService.evaluateAllDealersDebt()).thenReturn(summary);

        ApiResponse response = financeController.evaluateAllDealersDebt();

        assertEquals(true, response.isSuccess());
        assertEquals(summary, response.getData());
    }

    @Test
    @DisplayName("S7-04: API ghi nhan thu tien cong no va tra ve HTTP 201 Created")
    void recordDebtPayment_returnsCreated() {
        com.quanlybanhang.dto.request.DebtPaymentRequest req = com.quanlybanhang.dto.request.DebtPaymentRequest.builder()
                .dealerId(1L)
                .amount(new BigDecimal("10000000"))
                .build();
        com.quanlybanhang.dto.response.DebtPaymentResponse res = com.quanlybanhang.dto.response.DebtPaymentResponse.builder()
                .paymentCode("PT-20261022-0001")
                .amount(new BigDecimal("10000000"))
                .dealerStillFlagged(false)
                .build();
        when(authentication.getName()).thenReturn("accountant1");
        when(dealerDebtService.recordPayment(req, "accountant1")).thenReturn(res);

        var responseEntity = financeController.recordDebtPayment(req, authentication);

        assertEquals(org.springframework.http.HttpStatus.CREATED, responseEntity.getStatusCode());
        assertEquals(true, responseEntity.getBody().isSuccess());
        assertEquals(res, responseEntity.getBody().getData());
    }

    @Test
    @DisplayName("S7-04: API lay danh sach phieu thu tien co phan trang")
    void getDebtPayments_returnsPagedList() {
        org.springframework.data.domain.Page<com.quanlybanhang.dto.response.DebtPaymentResponse> paged = org.springframework.data.domain.Page.empty();
        when(dealerDebtService.getPayments(org.mockito.ArgumentMatchers.eq(1L), org.mockito.ArgumentMatchers.any())).thenReturn(paged);

        ApiResponse response = financeController.getDebtPayments(1L, 0, 10);

        assertEquals(true, response.isSuccess());
    }

    @Test
    @DisplayName("S7-02: API chi tiet phieu thu tien va phan bo")
    void getDebtPaymentDetail_callsService() {
        com.quanlybanhang.dto.response.DebtPaymentResponse detail = com.quanlybanhang.dto.response.DebtPaymentResponse.builder()
                .id(10L)
                .paymentCode("PT-20261022-0001")
                .amount(new BigDecimal("5000000"))
                .build();
        when(dealerDebtService.getPaymentById(10L)).thenReturn(detail);

        ApiResponse response = financeController.getDebtPaymentDetail(10L);

        assertEquals(true, response.isSuccess());
        assertEquals(detail, response.getData());
    }

    @Test
    @DisplayName("S7-02: API lap but toan dieu chinh tra ve HTTP 201 Created")
    void adjustDebtPayment_returnsCreated() {
        com.quanlybanhang.dto.request.DebtPaymentAdjustmentRequest req = com.quanlybanhang.dto.request.DebtPaymentAdjustmentRequest.builder()
                .reason("Nhap nham phieu thu")
                .adjustmentType("REVERSAL")
                .build();
        com.quanlybanhang.dto.response.DebtPaymentResponse adjRes = com.quanlybanhang.dto.response.DebtPaymentResponse.builder()
                .paymentCode("BTDC-20261022-0001")
                .amount(new BigDecimal("-5000000"))
                .paymentType("ADJUSTMENT")
                .build();
        when(authentication.getName()).thenReturn("accountant1");
        when(dealerDebtService.adjustPayment(10L, req, "accountant1")).thenReturn(adjRes);

        var responseEntity = financeController.adjustDebtPayment(10L, req, authentication);

        assertEquals(org.springframework.http.HttpStatus.CREATED, responseEntity.getStatusCode());
        assertEquals(true, responseEntity.getBody().isSuccess());
        assertEquals(adjRes, responseEntity.getBody().getData());
    }

    @Test
    @DisplayName("S7-02: Chan xoa phieu thu da ghi nhan -> nem ResponseStatusException 400 Bad Request")
    void deleteDebtPayment_throwsBadRequestException() {
        org.springframework.web.server.ResponseStatusException ex = org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> financeController.deleteDebtPayment(10L));
        assertEquals(org.springframework.http.HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertEquals("Đã ghi nhận thanh toán thì không được xoá, chỉ lập bút toán điều chỉnh.", ex.getReason());
    }

    @Test
    @DisplayName("S7-02: API danh sach hoa don cong no cua dai ly")
    void getDealerInvoices_callsService() {
        com.quanlybanhang.dto.response.DealerInvoiceResponse inv = com.quanlybanhang.dto.response.DealerInvoiceResponse.builder()
                .orderId(101L)
                .orderCode("DH-001")
                .paymentStatus("PARTIALLY_PAID")
                .build();
        when(dealerDebtService.getDealerInvoices(1L, "UNPAID")).thenReturn(List.of(inv));

        ApiResponse response = financeController.getDealerInvoices(1L, "UNPAID");

        assertEquals(true, response.isSuccess());
        assertEquals(1, ((List<?>) response.getData()).size());
    }

    @Test
    @DisplayName("S7-02: API danh sach hoa don con no cua dai ly")
    void getDealerUnpaidInvoices_callsService() {
        com.quanlybanhang.dto.response.DealerInvoiceResponse inv = com.quanlybanhang.dto.response.DealerInvoiceResponse.builder()
                .orderId(102L)
                .orderCode("DH-002")
                .paymentStatus("UNPAID")
                .build();
        when(dealerDebtService.getDealerUnpaidInvoices(1L)).thenReturn(List.of(inv));

        ApiResponse response = financeController.getDealerUnpaidInvoices(1L);

        assertEquals(true, response.isSuccess());
        assertEquals(1, ((List<?>) response.getData()).size());
    }

    @Test
    @DisplayName("S7-02: API xem truoc doi tru theo FIFO")
    void previewDebtPaymentAllocation_callsService() {
        com.quanlybanhang.dto.response.PaymentAllocationPreviewResponse preview = com.quanlybanhang.dto.response.PaymentAllocationPreviewResponse.builder()
                .dealerId(1L)
                .paymentAmount(new BigDecimal("5000000"))
                .totalAllocated(new BigDecimal("5000000"))
                .build();
        when(dealerDebtService.previewPaymentAllocation(1L, new BigDecimal("5000000"))).thenReturn(preview);

        ApiResponse response = financeController.previewDebtPaymentAllocation(1L, new BigDecimal("5000000"));

        assertEquals(true, response.isSuccess());
        assertEquals(preview, response.getData());
    }

    @Test
    @DisplayName("S7-02: API lich su doi tru cua hoa don")
    void getOrderSettlements_callsService() {
        com.quanlybanhang.dto.response.DebtPaymentAllocationResponse alloc = com.quanlybanhang.dto.response.DebtPaymentAllocationResponse.builder()
                .id(1L)
                .orderId(101L)
                .allocatedAmount(new BigDecimal("2000000"))
                .build();
        when(dealerDebtService.getOrderSettlements(101L)).thenReturn(List.of(alloc));

        ApiResponse response = financeController.getOrderSettlements(101L);

        assertEquals(true, response.isSuccess());
        assertEquals(1, ((List<?>) response.getData()).size());
    }
}
