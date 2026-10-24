package com.quanlybanhang.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Response chi tiết thông tin kho hàng (S5-03).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WarehouseResponse {

    private Long id;
    private String code;
    private String name;
    private String address;

    // Thông tin người phụ trách kho
    private Long managerUserId;
    private String managerUsername;
    private String managerFullName;
    private String managerName;

    private Boolean active;

    /** Kho hàng lỗi / hàng hỏng (S8-01) */
    private Boolean defective;

    /** Tổng số vị trí lưu trong kho */
    private Integer locationCount;

    /** Tổng số đại lý gán kho này làm kho phục vụ mặc định */
    private Integer defaultDealerCount;

    /** Danh sách vị trí lưu (nếu yêu cầu chi tiết) */
    private List<WarehouseLocationResponse> locations;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
