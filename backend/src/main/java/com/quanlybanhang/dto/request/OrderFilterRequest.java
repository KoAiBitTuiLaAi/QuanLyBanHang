package com.quanlybanhang.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

/**
 * DTO chứa bộ lọc danh sách đơn hàng cho Quản lý kinh doanh (S4-07).
 * Hỗ trợ lọc theo:
 * - Trạng thái đơn hàng (status)
 * - Đại lý (dealerId)
 * - Nhân viên kinh doanh phụ trách (salesUserId / assignedUserId / employeeId / userId)
 * - Khu vực / địa bàn (territoryId)
 * - Khoảng thời gian tạo đơn (startDate & endDate hoặc fromDate & toDate)
 * - Từ khoá tìm kiếm mã đơn, tên đại lý, tên khách hàng (keyword / search)
 * - Điều kiện phê duyệt (approvalRequired, approvalReason)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderFilterRequest {

    /** Trạng thái đơn hàng (RECORDED, PENDING_APPROVAL, CANCELLED, DRAFT, v.v.). */
    private String status;

    /** ID đại lý. */
    private Long dealerId;

    /** ID nhân viên kinh doanh phụ trách đại lý. */
    private Long salesUserId;

    /** Alias cho salesUserId. */
    private Long assignedUserId;

    /** Alias cho salesUserId. */
    private Long employeeId;

    /** Alias cho salesUserId. */
    private Long userId;

    /** ID khu vực / địa bàn của đại lý. */
    private Long territoryId;

    /** Ngày bắt đầu khoảng thời gian tạo đơn (YYYY-MM-DD). */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate startDate;

    /** Ngày kết thúc khoảng thời gian tạo đơn (YYYY-MM-DD). */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate endDate;

    /** Alias cho startDate. */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate fromDate;

    /** Alias cho endDate. */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate toDate;

    /** Từ khoá tìm kiếm nhanh (mã đơn, mã/tên đại lý, tên khách). */
    private String keyword;

    /** Alias cho keyword. */
    private String search;

    /** Cờ đơn hàng cần phê duyệt. */
    private Boolean approvalRequired;

    /** Lý do cần phê duyệt. */
    private String approvalReason;

    public Long getResolvedSalesUserId() {
        if (salesUserId != null) return salesUserId;
        if (assignedUserId != null) return assignedUserId;
        if (employeeId != null) return employeeId;
        return userId;
    }

    public LocalDate getResolvedStartDate() {
        return startDate != null ? startDate : fromDate;
    }

    public LocalDate getResolvedEndDate() {
        return endDate != null ? endDate : toDate;
    }

    public String getResolvedKeyword() {
        if (keyword != null && !keyword.isBlank()) return keyword.trim();
        if (search != null && !search.isBlank()) return search.trim();
        return null;
    }

    public boolean isEmpty() {
        return (status == null || status.isBlank())
                && dealerId == null
                && getResolvedSalesUserId() == null
                && territoryId == null
                && getResolvedStartDate() == null
                && getResolvedEndDate() == null
                && getResolvedKeyword() == null
                && approvalRequired == null
                && (approvalReason == null || approvalReason.isBlank());
    }
}

