package com.quanlybanhang.service;

import com.quanlybanhang.dto.request.OrderStatusUpdateRequest;
import com.quanlybanhang.dto.response.OrderApprovalHistoryResponse;
import com.quanlybanhang.dto.response.OrderResponse;
import com.quanlybanhang.model.Dealer;
import com.quanlybanhang.model.Order;
import com.quanlybanhang.model.OrderApprovalHistory;
import com.quanlybanhang.repository.OrderApprovalHistoryRepository;
import com.quanlybanhang.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("OrderApprovalServiceTest - S4-05 Nghiệp vụ Duyệt đơn hàng")
class OrderApprovalServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderApprovalHistoryRepository orderApprovalHistoryRepository;

    @Mock
    private AuditLogService auditLogService;

    @Mock private com.quanlybanhang.repository.UserRepository userRepository;

    @InjectMocks
    private OrderServiceImpl orderService;

    private Order pendingOrder;
    private Dealer dealer;

    @BeforeEach
    void setUp() {
        orderService.setUserRepository(userRepository);
        org.mockito.Mockito.lenient().when(userRepository.findByUsername(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(call -> Optional.of(com.quanlybanhang.model.User.builder().id(99L).username(call.getArgument(0)).build()));
        org.mockito.Mockito.lenient().when(orderRepository.findByIdForUpdate(org.mockito.ArgumentMatchers.anyLong()))
                .thenAnswer(call -> orderRepository.findById(call.getArgument(0)));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("manager01", "",
                        List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ORDER_APPROVE"))));
        orderService.setOrderApprovalHistoryRepository(orderApprovalHistoryRepository);

        dealer = Dealer.builder()
                .id(10L)
                .code("DL-001")
                .name("Đại lý Đại Phát")
                .debtLimit(new BigDecimal("100000000"))
                .active(true)
                .build();

        pendingOrder = Order.builder()
                .id(100L)
                .orderCode("DH-20261015-001")
                .dealer(dealer)
                .status("PENDING_APPROVAL")
                .requiresApproval(true)
                .approvalReason("OVER_DEBT_LIMIT")
                .totalAmount(new BigDecimal("120000000"))
                .items(new ArrayList<>())
                .createdAt(LocalDateTime.now())
                .build();
    }

    @org.junit.jupiter.api.AfterEach
    void clearAuthentication() { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }

    @Test
    @DisplayName("Duyệt đơn hàng thành công -> chuyển sang giữ chỗ tồn kho (RECORDED) và lưu lịch sử duyệt")
    void approveOrder_Success() {
        when(orderRepository.findById(100L)).thenReturn(Optional.of(pendingOrder));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        OrderResponse response = orderService.approveOrder(100L, "Đã thỏa thuận gia hạn công nợ", "manager01");

        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo("RECORDED");
        assertThat(pendingOrder.getStatus()).isEqualTo("RECORDED");
        assertThat(pendingOrder.getApprovedBy()).isEqualTo("manager01");

        ArgumentCaptor<OrderApprovalHistory> historyCaptor = ArgumentCaptor.forClass(OrderApprovalHistory.class);
        verify(orderApprovalHistoryRepository).save(historyCaptor.capture());
        OrderApprovalHistory history = historyCaptor.getValue();
        assertThat(history.getAction()).isEqualTo("APPROVED");
        assertThat(history.getComment()).isEqualTo("Đã thỏa thuận gia hạn công nợ");
        assertThat(history.getPerformedBy()).isEqualTo("manager01");
        assertThat(history.getOrderCode()).isEqualTo("DH-20261015-001");

        verify(auditLogService).logAction(eq("INVOICE"), eq("DH-20261015-001"), eq("APPROVE"),
                eq("PENDING_APPROVAL"), any(), eq("manager01"));
    }

    @Test
    @DisplayName("Duyệt đơn không ở trạng thái PENDING_APPROVAL -> ném ngoại lệ")
    void approveOrder_NotPending_ThrowsException() {
        pendingOrder.setStatus("RECORDED");
        when(orderRepository.findById(100L)).thenReturn(Optional.of(pendingOrder));

        assertThatThrownBy(() -> orderService.approveOrder(100L, "Duyệt", "manager01"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PENDING_APPROVAL");
    }

    @Test
    @DisplayName("Từ chối đơn bắt buộc phải có lý do/ý kiến -> nếu thiếu hoặc để trống thì ném ngoại lệ 400")
    void rejectOrder_MissingReason_ThrowsException() {
        assertThatThrownBy(() -> orderService.rejectOrder(100L, null, "manager01"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Vui lòng nhập lý do/ý kiến");

        assertThatThrownBy(() -> orderService.rejectOrder(100L, "   ", "manager01"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Vui lòng nhập lý do/ý kiến");
    }

    @Test
    @DisplayName("Từ chối đơn hợp lệ -> chuyển sang REJECTED và lưu lịch sử duyệt")
    void rejectOrder_Success() {
        when(orderRepository.findById(100L)).thenReturn(Optional.of(pendingOrder));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        OrderResponse response = orderService.rejectOrder(100L, "Vượt quá hạn mức công nợ tối đa", "manager01");

        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo("REJECTED");
        assertThat(pendingOrder.getStatus()).isEqualTo("REJECTED");
        assertThat(pendingOrder.getApprovedBy()).isEqualTo("manager01");

        ArgumentCaptor<OrderApprovalHistory> historyCaptor = ArgumentCaptor.forClass(OrderApprovalHistory.class);
        verify(orderApprovalHistoryRepository).save(historyCaptor.capture());
        OrderApprovalHistory history = historyCaptor.getValue();
        assertThat(history.getAction()).isEqualTo("REJECTED");
        assertThat(history.getComment()).isEqualTo("Vượt quá hạn mức công nợ tối đa");
        assertThat(history.getPerformedBy()).isEqualTo("manager01");
    }

    @Test
    @DisplayName("Trả lại sửa đơn bắt buộc phải có lý do/ý kiến -> nếu thiếu hoặc để trống thì ném ngoại lệ 400")
    void returnOrder_MissingReason_ThrowsException() {
        assertThatThrownBy(() -> orderService.returnOrder(100L, null, "manager01"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Vui lòng nhập lý do/ý kiến");

        assertThatThrownBy(() -> orderService.returnOrder(100L, "", "manager01"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Vui lòng nhập lý do/ý kiến");
    }

    @Test
    @DisplayName("Trả lại sửa đơn hợp lệ -> chuyển sang RETURNED và lưu lịch sử duyệt")
    void returnOrder_Success() {
        when(orderRepository.findById(100L)).thenReturn(Optional.of(pendingOrder));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        OrderResponse response = orderService.returnOrder(100L, "Vui lòng giảm số lượng để không vượt trần công nợ", "manager01");

        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo("RETURNED");
        assertThat(pendingOrder.getStatus()).isEqualTo("RETURNED");

        ArgumentCaptor<OrderApprovalHistory> historyCaptor = ArgumentCaptor.forClass(OrderApprovalHistory.class);
        verify(orderApprovalHistoryRepository).save(historyCaptor.capture());
        OrderApprovalHistory history = historyCaptor.getValue();
        assertThat(history.getAction()).isEqualTo("RETURNED");
        assertThat(history.getComment()).isEqualTo("Vui lòng giảm số lượng để không vượt trần công nợ");
    }

    @Test
    @DisplayName("updateOrderStatus qua PATCH điều hướng đúng hành động")
    void updateOrderStatus_DispatchesCorrectly() {
        when(orderRepository.findById(100L)).thenReturn(Optional.of(pendingOrder));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        OrderStatusUpdateRequest req = new OrderStatusUpdateRequest();
        req.setStatus("APPROVED");
        req.setComment("Duyệt đơn");

        OrderResponse response = orderService.updateOrderStatus(100L, req, "manager01");
        assertThat(response.getStatus()).isEqualTo("RECORDED");
    }

    @Test
    @DisplayName("Lịch sử duyệt đơn hàng (OrderApprovalHistory) bất biến: ném UnsupportedOperationException khi cố sửa hoặc xoá")
    void orderApprovalHistory_ImmutableAudit() {
        OrderApprovalHistory history = OrderApprovalHistory.builder()
                .id(1L)
                .order(pendingOrder)
                .orderCode("DH-001")
                .action("APPROVED")
                .comment("Hợp lệ")
                .performedBy("admin")
                .build();

        assertThatThrownBy(history::onUpdate)
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("không được phép chỉnh sửa");

        assertThatThrownBy(history::onDelete)
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("không được phép xoá");
    }

    @Test
    @DisplayName("getOrderApprovalHistory trả về danh sách lịch sử theo thứ tự thời gian")
    void getOrderApprovalHistory_ReturnsHistoryList() {
        OrderApprovalHistory h1 = OrderApprovalHistory.builder()
                .id(1L)
                .order(pendingOrder)
                .orderCode("DH-001")
                .action("PENDING_APPROVAL")
                .comment("Đơn hàng cần duyệt")
                .performedBy("sales01")
                .createdAt(LocalDateTime.now().minusHours(2))
                .build();

        OrderApprovalHistory h2 = OrderApprovalHistory.builder()
                .id(2L)
                .order(pendingOrder)
                .orderCode("DH-001")
                .action("APPROVED")
                .comment("Đồng ý duyệt")
                .performedBy("manager01")
                .createdAt(LocalDateTime.now())
                .build();

        when(orderApprovalHistoryRepository.findByOrderIdOrderByCreatedAtAsc(100L))
                .thenReturn(List.of(h1, h2));

        List<OrderApprovalHistoryResponse> list = orderService.getOrderApprovalHistory(100L);
        assertThat(list).hasSize(2);
        assertThat(list.get(0).getAction()).isEqualTo("PENDING_APPROVAL");
        assertThat(list.get(1).getAction()).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("getOrders phân trang và lọc danh sách đơn hàng")
    @SuppressWarnings("unchecked")
    void getOrders_PaginationAndFilter() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<Order> orderPage = new PageImpl<>(List.of(pendingOrder), pageable, 1);

        when(orderRepository.findAll(any(Specification.class), eq(pageable))).thenReturn(orderPage);

        Page<OrderResponse> result = orderService.getOrders("PENDING_APPROVAL", "OVER_DEBT_LIMIT", "Đại lý", true, pageable);
        assertThat(result).isNotNull();
        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent().get(0).getOrderCode()).isEqualTo("DH-20261015-001");
        assertThat(result.getContent().get(0).getDealerName()).isEqualTo("Đại lý Đại Phát");
    }
}
