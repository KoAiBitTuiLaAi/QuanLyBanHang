package com.quanlybanhang.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.*;

import java.time.LocalDate;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderRequest {

    private String orderCode;

    private String customerName;

    /**
     * Nhóm khách hàng (DAI_LY_CAP_1, DAI_LY_CAP_2, KHACH_LE).
     * Dùng để tự động áp bảng giá tương ứng (S2-10).
     */
    private com.quanlybanhang.model.CustomerGroup customerGroup;

    /**
     * ID đại lý (nếu đặt cho đại lý cụ thể, hệ thống tự lấy customerGroup của đại lý đó).
     */
    private Long dealerId;

    /**
     * ID điểm giao hàng của đại lý (S3-04).
     */
    private Long deliveryPointId;

    /** Ngày giao mong muốn do nhân viên kinh doanh chọn khi tạo đơn (S3-09). */
    private LocalDate expectedDeliveryDate;

    /**
     * Chỉ định rõ ID bảng giá (nếu không truyền, hệ thống tự động tìm bảng giá hiệu lực).
     */
    private Long priceListId;

    /**
     * ID kho xuất hàng và giữ chỗ tồn kho (S4-06). Nếu không chỉ định, lấy kho mặc định đang hoạt động.
     */
    private Long warehouseId;

    private String note;

    @NotEmpty(message = "Đơn hàng phải có ít nhất 1 sản phẩm")
    @Valid
    private List<OrderItemRequest> items;
}
