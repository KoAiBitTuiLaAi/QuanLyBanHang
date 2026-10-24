package com.quanlybanhang.dto.request;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
public record CashCollectionRequest(@NotNull @Positive Long dealerId,
 @NotNull @DecimalMin("0.01") @Digits(integer=17,fraction=2) BigDecimal amount,
 @NotNull @PastOrPresent LocalDate collectionDate, @Size(max=500) String note) {}