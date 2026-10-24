package com.quanlybanhang.dto.response;

import com.quanlybanhang.model.LocationType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Response chi tiết vị trí lưu trong kho (S5-03).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WarehouseLocationResponse {

    private Long id;
    private Long warehouseId;
    private String warehouseCode;
    private String warehouseName;

    private String code;
    private String name;
    private LocationType locationType;
    private String locationTypeDisplayName;

    // Vị trí cha (nếu là kệ nằm trong khu)
    private Long parentLocationId;
    private String parentLocationCode;
    private String parentLocationName;

    private String description;
    private Integer pickingSequence;
    private Boolean active;

    /** Tên hiển thị đầy đủ, vd: "Khu A > Kệ 01" */
    private String fullDisplayName;

    /** Tổng số sản phẩm đang được gán vị trí này */
    private Integer productCount;

    /** Danh sách kệ con nếu vị trí này là KHU (ZONE) */
    private List<WarehouseLocationResponse> childShelves;

    /** Danh sách sản phẩm được gán tại vị trí này */
    private List<ProductLocationResponse> products;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
