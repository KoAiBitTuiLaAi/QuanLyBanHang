package com.quanlybanhang.dto;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;

public final class PickingBatchDtos {
    private PickingBatchDtos() { }
    public record Create(@NotNull @Positive Long warehouseId,
            @NotEmpty @Size(max = 100) List<@NotNull @Positive Long> orderIds,
            @Size(max = 50) String code, @Size(max = 500) String note) { }
    public record UpdateLine(@NotNull @PositiveOrZero Long version,
            @DecimalMin("0") @Digits(integer = 11, fraction = 4) BigDecimal pickedQuantity,
            @NotNull Boolean checked, @Size(max = 500) String shortageReason) { }
    public record Version(@NotNull @PositiveOrZero Long version) { }
    public record Cancel(@NotNull @PositiveOrZero Long version, @NotBlank @Size(max = 500) String reason) { }
    public record Named(Long id, String code, String name) { }
    public record Status(String value, String label) { }
    public record Options(List<Named> warehouses, List<Status> statuses, boolean canCreate,
            int maxOrders, int maxLines, int quantityScale, String routeStrategy) { }
    public record EligibleOrder(Long id, String orderCode, String customerName, String status,
            LocalDate expectedDeliveryDate, int lineCount) { }
    public record Summary(Long id, Long version, String code, Long warehouseId, String warehouseCode,
            String warehouseName, String status, String statusLabel, int orderCount, int lineCount,
            int checkedCount, int shortageCount, LocalDateTime createdAt, String createdBy) { }
    public record Line(Long id, Long orderId, String orderCode, Long productId, String productCode,
            String productName, String unit, String baseUnit, BigDecimal quantity, BigDecimal conversionFactor,
            BigDecimal pickedQuantity, BigDecimal pickedBaseQuantity, Boolean checked, BigDecimal shortageQuantity,
            String shortageReason, Long locationId, String locationCode, String locationDisplay, int routePosition) { }
    public record Detail(Summary summary, String note, String cancelReason, LocalDateTime completedAt,
            List<Line> items, boolean canEdit, boolean canComplete, boolean canCancel, String blockedReason) { }
}
