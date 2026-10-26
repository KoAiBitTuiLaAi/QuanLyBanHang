package com.quanlybanhang.dto.response;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Phản hồi gợi ý lô xuất kho theo nguyên tắc FEFO (Hạn dùng gần nhất trước - S6-04).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LotSuggestionResponse {

    private Long warehouseId;
    private String warehouseCode;
    private String warehouseName;

    private Long productId;
    private String productCode;
    private String productName;
    private Boolean manageLot;

    private BigDecimal requestedQuantity;
    private String requestedUnit;
    private BigDecimal requestedBaseQuantity;
    private String baseUnit;

    /**
     * Lô được hệ thống gợi ý mặc định (hạn dùng gần nhất còn đủ số lượng).
     * Null nếu không có lô nào còn đủ số lượng yêu cầu.
     */
    private SuggestedLotInfo suggestedLot;

    /**
     * Danh sách tất cả các lô còn hàng của sản phẩm tại kho, sắp xếp theo FEFO.
     */
    private List<SuggestedLotInfo> availableLots;

    /**
     * True nếu có ít nhất một lô còn hạn, có HSD và đủ toàn bộ số lượng yêu cầu.
     * Không tự phân bổ yêu cầu qua nhiều lô.
     */
    private Boolean hasSufficientStock;

    /**
     * Thông báo giải thích kết quả gợi ý.
     */
    private String message;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class SuggestedLotInfo {
        private Long lotId;
        private String lotNumber;
        private LocalDate expiryDate;
        private LocalDate manufacturingDate;
        private BigDecimal availableQuantity; // Số lượng tồn theo baseUnit
        private String baseUnit;
        private Boolean isExpired;
        private Long daysUntilExpiry;
        private Boolean sufficientForRequest;
    }
}

