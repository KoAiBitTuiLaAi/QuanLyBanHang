package com.quanlybanhang.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * DTO khai báo điểm giao trong chuyến (S6-07).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateDeliveryTripStopRequest {

    private Long orderId;

    /** Preferred source for partial deliveries; all goods and money are read from this voucher. */
    private Long shipmentVoucherId;

    private String orderCode;

    private Long dealerId;

    private String dealerName;

    private Long deliveryPointId;

    private String receiverName;

    private String receiverPhone;

    private String deliveryAddress;

    @DecimalMin(value = "0.0", message = "Giá trị tiền hàng không được âm")
    private BigDecimal deliveryAmount;

    private Integer stopOrder;

    private String note;

    @Valid
    private List<CreateDeliveryTripStopItemRequest> items;

    @jakarta.validation.constraints.AssertTrue(message = "Điểm thủ công cần địa chỉ và giá trị tiền hàng")
    public boolean isSourceOrManualDetailsValid() {
        return orderId != null || shipmentVoucherId != null
                || (deliveryAddress != null && !deliveryAddress.isBlank() && deliveryAmount != null);
    }
}

