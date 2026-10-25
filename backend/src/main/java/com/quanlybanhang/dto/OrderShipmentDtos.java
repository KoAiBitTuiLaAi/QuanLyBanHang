package com.quanlybanhang.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.List;

public final class OrderShipmentDtos {
    private OrderShipmentDtos() {}
    public record Confirm(@NotNull @PositiveOrZero Long version,
            @NotBlank @Pattern(regexp="[0-9a-fA-F-]{36}") String requestKey,
            @Size(max=500) String note, @NotEmpty @Size(max=500) List<@NotNull @Valid Input> items) {}
    public record Input(@NotNull @Positive Long orderItemId,
            @NotNull @DecimalMin("0") @Digits(integer=11, fraction=4) BigDecimal quantity,
            @Size(max=50) String lotNumber, @Size(max=255) String reason) {}
    public record Named(Long id, String code, String name) {}
    public record Options(List<Named> warehouses, boolean canConfirm, int quantityScale, String reservationPolicy) {}
    public record Summary(Long id, String orderCode, String customerName, Long warehouseId,
            String warehouseName, String statusLabel, boolean backordered) {}
    public record Lot(String lotNumber, LocalDate expiryDate, BigDecimal baseQuantity) {}
    public record Line(Long id, String code, String name, String unit, String baseUnit, BigDecimal conversionFactor,
            BigDecimal ordered, BigDecimal exported, BigDecimal remaining, boolean lotManaged, List<Lot> lots) {}
    public record ReceiptLine(Long orderItemId, String productCode, String productName, String unit,
            BigDecimal quantity, BigDecimal baseQuantity, String lotNumber, String reason) {}
    public record Receipt(Long id, String code, String status, LocalDateTime confirmedAt, String confirmedBy,
            String note, List<ReceiptLine> items) {}
    public record Detail(Long id, Long version, String orderCode, String customerName, String warehouseName,
            String statusLabel, boolean backordered, boolean canConfirm, String blockedReason,
            List<Line> items, List<Receipt> receipts) {}
}
