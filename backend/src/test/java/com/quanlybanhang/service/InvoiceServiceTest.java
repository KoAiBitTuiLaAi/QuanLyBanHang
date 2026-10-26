package com.quanlybanhang.service;

import com.quanlybanhang.dto.request.CreateInvoiceAdjustmentRequest;
import com.quanlybanhang.dto.request.CreateInvoiceRequest;
import com.quanlybanhang.dto.request.InvoiceAdjustmentItemRequest;
import com.quanlybanhang.dto.request.InvoicePreviewRequest;
import com.quanlybanhang.dto.response.EligibleDealerResponse;
import com.quanlybanhang.dto.response.EligibleDeliveryOrderResponse;
import com.quanlybanhang.dto.response.InvoiceAdjustmentResponse;
import com.quanlybanhang.dto.response.InvoiceItemResponse;
import com.quanlybanhang.dto.response.InvoicePreviewResponse;
import com.quanlybanhang.dto.response.InvoiceResponse;
import com.quanlybanhang.model.*;
import com.quanlybanhang.repository.*;
import com.quanlybanhang.service.impl.InvoiceServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("InvoiceServiceTest - Kiểm thử nghiệp vụ Phát hành Hóa đơn Bán hàng (S7-01)")
class InvoiceServiceTest {

    @Mock
    private InvoiceRepository invoiceRepository;
    @Mock
    private InvoiceAdjustmentRepository adjustmentRepository;
    @Mock
    private DealerRepository dealerRepository;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private ProductRepository productRepository;
    @Mock
    private InvoiceSequenceService invoiceSequenceService;
    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private InvoiceServiceImpl invoiceService;

    private Dealer dealerA;
    private Dealer dealerB;
    private Product productBia;
    private Product productNuoc;
    private Order order1;
    private Order order2;

    @BeforeEach
    void setUp() {
        dealerA = Dealer.builder()
                .id(1L)
                .code("DL-001")
                .name("Đại lý Minh Phát")
                .taxCode("0101234567")
                .address("123 Phố Huế, Hà Nội")
                .phone("0912345678")
                .active(true)
                .build();

        dealerB = Dealer.builder()
                .id(2L)
                .code("DL-002")
                .name("Đại lý Hoàng Gia")
                .taxCode("0109876543")
                .active(true)
                .build();

        productBia = Product.builder()
                .id(10L)
                .code("BIA-HN")
                .name("Bia Hà Nội Lon 330ml")
                .unit("thùng")
                .price(new BigDecimal("300000.00"))
                .build();

        productNuoc = Product.builder()
                .id(20L)
                .code("NUOC-NGOT")
                .name("Nước ngọt Coca Lon 330ml")
                .unit("thùng")
                .price(new BigDecimal("200000.00"))
                .build();

        // Đơn 1: Đặt 10 thùng Bia nhưng thực giao chỉ 8 thùng (giao thiếu 2)
        OrderItem item1 = OrderItem.builder()
                .id(101L)
                .product(productBia)
                .unit("thùng")
                .quantity(new BigDecimal("10")) // Đặt 10
                .deliveredQuantity(new BigDecimal("8")) // Thực giao 8
                .price(new BigDecimal("300000.00"))
                .discountPercent(new BigDecimal("5.00"))
                .deliveryNote("Xe tải hết chỗ, giao trước 8 thùng")
                .build();

        order1 = Order.builder()
                .id(1001L)
                .orderCode("DH-20261022-001")
                .dealer(dealerA)
                .status("DELIVERED")
                .actualDeliveryDate(LocalDate.of(2026, 10, 21))
                .items(new ArrayList<>(List.of(item1)))
                .build();
        item1.setOrder(order1);

        // Đơn 2: Đặt 5 thùng Nước, thực giao đủ 5 thùng
        OrderItem item2 = OrderItem.builder()
                .id(102L)
                .product(productNuoc)
                .unit("thùng")
                .quantity(new BigDecimal("5"))
                .deliveredQuantity(new BigDecimal("5"))
                .price(new BigDecimal("200000.00"))
                .discountPercent(BigDecimal.ZERO)
                .build();

        order2 = Order.builder()
                .id(1002L)
                .orderCode("DH-20261022-002")
                .dealer(dealerA)
                .status("DELIVERED")
                .actualDeliveryDate(LocalDate.of(2026, 10, 22))
                .items(new ArrayList<>(List.of(item2)))
                .build();
        item2.setOrder(order2);
    }

    @Test
    @DisplayName("Tiêu chí 1: Hóa đơn sinh từ số lượng thực giao, không phải số lượng đặt")
    void issueInvoice_CalculatesFromDeliveredQuantityNotOrderedQuantity() {
        when(dealerRepository.findById(1L)).thenReturn(Optional.of(dealerA));
        when(orderRepository.findAllById(List.of(1001L))).thenReturn(List.of(order1));
        when(invoiceRepository.findAlreadyInvoicedOrderIds(List.of(1001L))).thenReturn(Collections.emptyList());
        when(invoiceSequenceService.nextSequentialNumber(any(), any(), eq(8)))
                .thenReturn(new InvoiceSequenceService.NextNumberResult("00000001", "HD-00000001", 1L));
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(i -> {
            Invoice inv = i.getArgument(0);
            inv.setId(1L);
            return inv;
        });

        CreateInvoiceRequest request = CreateInvoiceRequest.builder()
                .dealerId(1L)
                .orderIds(List.of(1001L))
                .build();

        InvoiceResponse response = invoiceService.issueInvoice(request, "accountant01");

        assertThat(response).isNotNull();
        assertThat(response.getInvoiceNumber()).isEqualTo("00000001");
        assertThat(response.getInvoiceCode()).isEqualTo("HD-00000001");
        assertThat(response.getItems()).hasSize(1);

        InvoiceItemResponse itemResp = response.getItems().get(0);
        // Kiểm tra đúng số lượng thực giao
        assertThat(itemResp.getOrderedQuantity()).isEqualByComparingTo("10");
        assertThat(itemResp.getDeliveredQuantity()).isEqualByComparingTo("8");
        assertThat(itemResp.getInvoicedQuantity()).isEqualByComparingTo("8");

        // Subtotal = 8 * 300,000 = 2,400,000 (thay vì 10 * 300,000 = 3,000,000)
        assertThat(itemResp.getSubtotalAmount()).isEqualByComparingTo("2400000.00");
        // Chiết khấu 5% của 2,400,000 = 120,000
        assertThat(itemResp.getDiscountAmount()).isEqualByComparingTo("120000.00");
        // Total = 2,400,000 - 120,000 = 2,280,000
        assertThat(itemResp.getTotalAmount()).isEqualByComparingTo("2280000.00");

        assertThat(response.getSubtotalAmount()).isEqualByComparingTo("2400000.00");
        assertThat(response.getDiscountAmount()).isEqualByComparingTo("120000.00");
        assertThat(response.getTotalAmount()).isEqualByComparingTo("2280000.00");

        verify(auditLogService).logAction(eq("INVOICE"), eq("HD-00000001"), eq("ISSUE_INVOICE"), any(), eq("ISSUED"), any(), eq("accountant01"));
    }

    @Test
    @DisplayName("Tiêu chí 2: Số hóa đơn tự sinh theo dãy liên tục, không trùng và không nhảy cóc")
    void issueInvoice_GeneratesSequentialNumberViaSequenceService() {
        when(dealerRepository.findById(1L)).thenReturn(Optional.of(dealerA));
        when(orderRepository.findAllById(List.of(1001L))).thenReturn(List.of(order1));
        when(invoiceRepository.findAlreadyInvoicedOrderIds(List.of(1001L))).thenReturn(Collections.emptyList());
        when(invoiceSequenceService.nextSequentialNumber(
                InvoiceSequenceService.DEFAULT_SEQUENCE_KEY,
                InvoiceSequenceService.DEFAULT_PREFIX,
                InvoiceSequenceService.DEFAULT_PADDING_LENGTH))
                .thenReturn(new InvoiceSequenceService.NextNumberResult("00000005", "HD-00000005", 5L));
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(i -> i.getArgument(0));

        CreateInvoiceRequest request = CreateInvoiceRequest.builder()
                .dealerId(1L)
                .orderIds(List.of(1001L))
                .build();

        InvoiceResponse response = invoiceService.issueInvoice(request, "accountant01");

        assertThat(response.getInvoiceNumber()).isEqualTo("00000005");
        assertThat(response.getInvoiceCode()).isEqualTo("HD-00000005");
        verify(invoiceSequenceService).nextSequentialNumber(
                InvoiceSequenceService.DEFAULT_SEQUENCE_KEY,
                InvoiceSequenceService.DEFAULT_PREFIX,
                InvoiceSequenceService.DEFAULT_PADDING_LENGTH);
    }

    @Test
    @DisplayName("Tiêu chí 3: Gộp nhiều phiếu giao của cùng đại lý trong kỳ thành một hóa đơn được")
    void issueInvoice_CombinesMultipleDeliverySlipsOfSameDealer() {
        when(dealerRepository.findById(1L)).thenReturn(Optional.of(dealerA));
        when(orderRepository.findAllById(List.of(1001L, 1002L))).thenReturn(List.of(order1, order2));
        when(invoiceRepository.findAlreadyInvoicedOrderIds(List.of(1001L, 1002L))).thenReturn(Collections.emptyList());
        when(invoiceSequenceService.nextSequentialNumber(any(), any(), eq(8)))
                .thenReturn(new InvoiceSequenceService.NextNumberResult("00000002", "HD-00000002", 2L));
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(i -> {
            Invoice inv = i.getArgument(0);
            inv.setId(2L);
            return inv;
        });

        CreateInvoiceRequest request = CreateInvoiceRequest.builder()
                .dealerId(1L)
                .orderIds(List.of(1001L, 1002L))
                .note("Hóa đơn gộp 2 phiếu giao đợt 1")
                .build();

        InvoiceResponse response = invoiceService.issueInvoice(request, "accountant01");

        assertThat(response.getItems()).hasSize(2);
        assertThat(response.getOrders()).hasSize(2);

        // Đơn 1: 8 * 300,000 = 2,400,000 - 120,000 = 2,280,000
        // Đơn 2: 5 * 200,000 = 1,000,000 - 0 = 1,000,000
        // Subtotal = 3,400,000; Discount = 120,000; Total = 3,280,000
        assertThat(response.getSubtotalAmount()).isEqualByComparingTo("3400000.00");
        assertThat(response.getDiscountAmount()).isEqualByComparingTo("120000.00");
        assertThat(response.getTotalAmount()).isEqualByComparingTo("3280000.00");
    }

    @Test
    @DisplayName("Tiêu chí 3 (Chặn): Phiếu giao không thuộc cùng đại lý -> Báo lỗi 400")
    void issueInvoice_WhenOrdersBelongToDifferentDealers_ThrowsBadRequest() {
        Order orderOtherDealer = Order.builder()
                .id(1003L)
                .orderCode("DH-OTHER")
                .dealer(dealerB)
                .status("DELIVERED")
                .items(new ArrayList<>())
                .build();

        when(dealerRepository.findById(1L)).thenReturn(Optional.of(dealerA));
        when(orderRepository.findAllById(List.of(1001L, 1003L))).thenReturn(List.of(order1, orderOtherDealer));

        CreateInvoiceRequest request = CreateInvoiceRequest.builder()
                .dealerId(1L)
                .orderIds(List.of(1001L, 1003L))
                .build();

        assertThatThrownBy(() -> invoiceService.issueInvoice(request, "accountant01"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("không thuộc đại lý");
    }

    @Test
    @DisplayName("Tiêu chí 3 (Chặn): Phiếu giao chưa ở trạng thái DELIVERED -> Báo lỗi 400")
    void issueInvoice_WhenOrderNotDelivered_ThrowsBadRequest() {
        order1.setStatus("EXPORTED"); // Chưa giao xong

        when(dealerRepository.findById(1L)).thenReturn(Optional.of(dealerA));
        when(orderRepository.findAllById(List.of(1001L))).thenReturn(List.of(order1));

        CreateInvoiceRequest request = CreateInvoiceRequest.builder()
                .dealerId(1L)
                .orderIds(List.of(1001L))
                .build();

        assertThatThrownBy(() -> invoiceService.issueInvoice(request, "accountant01"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Chỉ được phát hành hóa đơn cho các phiếu đã giao hàng thành công (DELIVERED)");
    }

    @Test
    @DisplayName("Tiêu chí 3 (Chặn): Phiếu giao đã được xuất hóa đơn trước đó -> Báo lỗi 400")
    void issueInvoice_WhenOrderAlreadyInvoiced_ThrowsBadRequest() {
        when(dealerRepository.findById(1L)).thenReturn(Optional.of(dealerA));
        when(orderRepository.findAllById(List.of(1001L))).thenReturn(List.of(order1));
        when(invoiceRepository.findAlreadyInvoicedOrderIds(List.of(1001L))).thenReturn(List.of(1001L));

        CreateInvoiceRequest request = CreateInvoiceRequest.builder()
                .dealerId(1L)
                .orderIds(List.of(1001L))
                .build();

        assertThatThrownBy(() -> invoiceService.issueInvoice(request, "accountant01"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("đã được xuất hóa đơn trước đó");
    }

    @Test
    @DisplayName("Tiêu chí 4: Hóa đơn đã phát hành KHÔNG sửa và KHÔNG xóa -> Báo lỗi 400")
    void updateAndDelete_AlwaysThrowsBadRequest() {
        assertThatThrownBy(() -> invoiceService.updateInvoice(100L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Hóa đơn đã phát hành không được phép chỉnh sửa");

        assertThatThrownBy(() -> invoiceService.deleteInvoice(100L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Hóa đơn đã phát hành không được phép xóa");
    }

    @Test
    @DisplayName("Tiêu chí 4: Lập chứng từ điều chỉnh tăng/giảm thành công kèm lý do bắt buộc")
    void createAdjustment_Success_UpdatesInvoiceStatusAndAdjustedTotal() {
        Invoice existingInvoice = Invoice.builder()
                .id(100L)
                .invoiceNumber("00000001")
                .invoiceCode("HD-00000001")
                .dealer(dealerA)
                .status(InvoiceStatus.ISSUED)
                .totalAmount(new BigDecimal("2280000.00"))
                .adjustedTotalAmount(new BigDecimal("2280000.00"))
                .adjustments(new ArrayList<>())
                .build();

        when(invoiceRepository.findById(100L)).thenReturn(Optional.of(existingInvoice));
        when(adjustmentRepository.countByInvoiceId(100L)).thenReturn(0L);
        when(adjustmentRepository.save(any(InvoiceAdjustment.class))).thenAnswer(i -> {
            InvoiceAdjustment a = i.getArgument(0);
            a.setId(10L);
            return a;
        });

        CreateInvoiceAdjustmentRequest request = CreateInvoiceAdjustmentRequest.builder()
                .adjustmentType(InvoiceAdjustmentType.DECREASE)
                .adjustmentReason("Chiết khấu bổ sung cho đại lý do thanh toán sớm")
                .adjustmentAmount(new BigDecimal("100000.00")) // Giảm 100,000
                .build();

        InvoiceAdjustmentResponse adjResp = invoiceService.createAdjustment(100L, request, "accountant01");

        assertThat(adjResp).isNotNull();
        assertThat(adjResp.getAdjustmentCode()).isEqualTo("DC-00000001-01");
        assertThat(adjResp.getAdjustmentType()).isEqualTo(InvoiceAdjustmentType.DECREASE);
        assertThat(adjResp.getAdjustmentReason()).isEqualTo("Chiết khấu bổ sung cho đại lý do thanh toán sớm");
        assertThat(adjResp.getAdjustmentAmount()).isEqualByComparingTo("-100000.00");

        assertThat(existingInvoice.getStatus()).isEqualTo(InvoiceStatus.ADJUSTED);
        // Adjusted total = 2,280,000 - 100,000 = 2,180,000
        assertThat(existingInvoice.getAdjustedTotalAmount()).isEqualByComparingTo("2180000.00");

        verify(auditLogService).logAction(eq("INVOICE"), eq("HD-00000001"), eq("ADJUST_INVOICE"), eq("ISSUED"), eq("ADJUSTED"), any(), eq("accountant01"));
    }

    @Test
    @DisplayName("Tiêu chí 4: Lập chứng từ điều chỉnh thiếu lý do -> Báo lỗi 400")
    void createAdjustment_WithoutReason_ThrowsBadRequest() {
        Invoice existingInvoice = Invoice.builder()
                .id(100L)
                .status(InvoiceStatus.ISSUED)
                .build();

        when(invoiceRepository.findById(100L)).thenReturn(Optional.of(existingInvoice));

        CreateInvoiceAdjustmentRequest request = CreateInvoiceAdjustmentRequest.builder()
                .adjustmentType(InvoiceAdjustmentType.DECREASE)
                .adjustmentReason("   ") // Rỗng
                .build();

        assertThatThrownBy(() -> invoiceService.createAdjustment(100L, request, "accountant01"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Lý do điều chỉnh không được để trống");
    }

    @Test
    @DisplayName("Tiêu chí 4: Lập chứng từ điều chỉnh HỦY hóa đơn -> Chuyển trạng thái CANCELLED và về 0")
    void createAdjustment_CancelInvoice_SetsStatusCancelledAndZeroTotal() {
        Invoice existingInvoice = Invoice.builder()
                .id(100L)
                .invoiceNumber("00000001")
                .invoiceCode("HD-00000001")
                .dealer(dealerA)
                .status(InvoiceStatus.ISSUED)
                .totalAmount(new BigDecimal("2280000.00"))
                .adjustedTotalAmount(new BigDecimal("2280000.00"))
                .adjustments(new ArrayList<>())
                .build();

        when(invoiceRepository.findById(100L)).thenReturn(Optional.of(existingInvoice));
        when(adjustmentRepository.countByInvoiceId(100L)).thenReturn(0L);
        when(adjustmentRepository.save(any(InvoiceAdjustment.class))).thenAnswer(i -> i.getArgument(0));

        CreateInvoiceAdjustmentRequest request = CreateInvoiceAdjustmentRequest.builder()
                .adjustmentType(InvoiceAdjustmentType.CANCEL)
                .adjustmentReason("Hóa đơn xuất nhầm thông tin pháp nhân đại lý, hủy để lập lại")
                .build();

        InvoiceAdjustmentResponse adjResp = invoiceService.createAdjustment(100L, request, "accountant01");

        assertThat(adjResp.getAdjustmentType()).isEqualTo(InvoiceAdjustmentType.CANCEL);
        assertThat(existingInvoice.getStatus()).isEqualTo(InvoiceStatus.CANCELLED);
        assertThat(existingInvoice.getAdjustedTotalAmount()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("Tra cứu các phiếu giao hợp lệ sẵn sàng xuất hóa đơn của đại lý")
    void getEligibleOrders_ReturnsUninvoicedDeliveredOrders() {
        when(orderRepository.findAll(any(Specification.class))).thenReturn(List.of(order1, order2));
        when(invoiceRepository.findAlreadyInvoicedOrderIds(List.of(1001L, 1002L))).thenReturn(List.of(1001L)); // 1001 đã xuất

        List<EligibleDeliveryOrderResponse> result = invoiceService.getEligibleOrders(1L, null, null);

        // Chỉ còn lại order2
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getOrderId()).isEqualTo(1002L);
        assertThat(result.get(0).getOrderCode()).isEqualTo("DH-20261022-002");
    }

    @Test
    @DisplayName("Xem trước thông tin hóa đơn trước khi phát hành")
    void previewInvoice_Success() {
        when(dealerRepository.findById(1L)).thenReturn(Optional.of(dealerA));
        when(orderRepository.findAllById(List.of(1001L))).thenReturn(List.of(order1));
        when(invoiceRepository.findAlreadyInvoicedOrderIds(List.of(1001L))).thenReturn(Collections.emptyList());

        InvoicePreviewRequest request = InvoicePreviewRequest.builder()
                .dealerId(1L)
                .orderIds(List.of(1001L))
                .build();

        InvoicePreviewResponse preview = invoiceService.previewInvoice(request, "accountant01");

        assertThat(preview.getDealerCode()).isEqualTo("DL-001");
        assertThat(preview.getTotalOrders()).isEqualTo(1);
        assertThat(preview.getItems()).hasSize(1);
        assertThat(preview.getItems().get(0).getInvoicedQuantity()).isEqualByComparingTo("8");
        assertThat(preview.getTotalAmount()).isEqualByComparingTo("2280000.00");
    }
}
