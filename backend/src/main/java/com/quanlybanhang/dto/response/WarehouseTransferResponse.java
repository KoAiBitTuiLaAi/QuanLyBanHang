package com.quanlybanhang.dto.response;

import com.quanlybanhang.model.WarehouseTransferStatus;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WarehouseTransferResponse {

    private Long id;
    private String transferCode;

    private Long fromWarehouseId;
    private String fromWarehouseCode;
    private String fromWarehouseName;

    private Long toWarehouseId;
    private String toWarehouseCode;
    private String toWarehouseName;

    private WarehouseTransferStatus status;
    private String statusDescription;

    private String note;
    private String receiveNote;
    private String cancelReason;

    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime dispatchedAt;

    private String receivedBy;
    private LocalDateTime receivedAt;

    private String cancelledBy;
    private LocalDateTime cancelledAt;

    private List<WarehouseTransferItemResponse> items;
}

