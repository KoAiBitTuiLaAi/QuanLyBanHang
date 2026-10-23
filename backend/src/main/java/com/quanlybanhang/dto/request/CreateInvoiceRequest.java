package com.quanlybanhang.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Yêu cầu phát hành hóa đơn bán hàng từ các phiếu giao (S7-01, S7-07).
 * Hỗ trợ gộp nhiều phiếu giao của cùng một đại lý trong kỳ thành 1 hóa đơn.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateInvoiceRequest {

    @NotNull(message = "ID đại lý không được để trống")
    private Long dealerId;

    /**
     * Danh sách ID đơn hàng đã giao (phiếu giao) cần gộp xuất hóa đơn.
     */
    @NotEmpty(message = "Danh sách phiếu giao hàng không được để trống")
    private List<Long> orderIds;

    /**
     * Ngày phát hành hóa đơn (mặc định là thời điểm hiện tại nếu để trống).
     */
    private LocalDateTime invoiceDate;

    /**
     * Hạn thanh toán hóa đơn (tùy chọn; mặc định tính theo ngày phát hành + số ngày nợ tối đa của đại lý).
     */
    private LocalDate dueDate;

    /**
     * Ghi chú hóa đơn.
     */
    private String note;
}
