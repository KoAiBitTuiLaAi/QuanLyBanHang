package com.quanlybanhang.dto.response;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * DTO đại diện cho hoá đơn / đơn hàng trong quản lý công nợ (S7-02).
 * Kế toán công nợ theo dõi để biết chính xác đại lý còn nợ hoá đơn nào.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DealerInvoiceResponse {

    private Long orderId;
    private String orderCode;
    private Long dealerId;
    private String dealerCode;
    private String dealerName;

    /** Ngày phát sinh hoá đơn / ghi nhận nợ */
    private LocalDateTime recordedAt;
    private LocalDate invoiceDate;

    /** Tổng giá trị hoá đơn */
    private BigDecimal totalAmount;

    /** Số tiền đã thanh toán / đối trừ */
    private BigDecimal paidAmount;

    /** Số tiền còn nợ lại của hoá đơn */
    private BigDecimal remainingAmount;

    /** Trạng thái thanh toán: UNPAID, PARTIALLY_PAID, PAID */
    private String paymentStatus;

    /** Tên hiển thị trạng thái thanh toán: Chưa thanh toán, Thanh toán một phần, Đã thanh toán */
    private String paymentStatusDisplayName;

    /** Trạng thái đơn hàng: RECORDED, PICKING, EXPORTED, DELIVERED, CLOSED... */
    private String orderStatus;
    private String orderStatusDisplayName;

    /** Số ngày quá hạn nếu có */
    private Integer overdueDays;

    /** Đã quá hạn hay chưa */
    private Boolean isOverdue;

    /** Lịch sử các lần đối trừ vào hoá đơn này */
    private List<DebtPaymentAllocationResponse> allocations;
}
