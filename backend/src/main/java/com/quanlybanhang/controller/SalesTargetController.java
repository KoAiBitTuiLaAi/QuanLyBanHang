package com.quanlybanhang.controller;

import com.quanlybanhang.dto.request.BatchSalesTargetRequest;
import com.quanlybanhang.dto.request.SalesTargetRequest;
import com.quanlybanhang.dto.request.UpdateSalesTargetRequest;
import com.quanlybanhang.dto.response.*;
import com.quanlybanhang.model.SalesTargetScopeType;
import com.quanlybanhang.service.SalesTargetService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST Controller Quản lý Chỉ tiêu doanh số và Theo dõi tiến độ theo thời gian thực (S8-04).
 *
 * Phân quyền từ ma trận quyền trong DB (RolePermissionConfig):
 * - Xem chỉ tiêu, tiến độ cá nhân, tổng hợp tiến độ, danh mục và lịch sử chỉnh sửa:
 *   hasAnyAuthority('REPORT_SALES', 'ORDER_APPROVE', 'ROLE_ADMIN')
 * - Đặt/cập nhật/khóa/xóa chỉ tiêu:
 *   hasAnyAuthority('ORDER_APPROVE', 'ROLE_ADMIN')
 * - Không dùng fallback theo role (ngoài ROLE_ADMIN được ưu tiên toàn hệ thống).
 */
@RestController
@RequestMapping({"/api/sales-targets", "/api/reports/sales-targets", "/api/manager/sales-targets"})
@RequiredArgsConstructor
public class SalesTargetController {

    private final SalesTargetService salesTargetService;

    /**
     * GET /api/sales-targets/options — Danh mục nhân viên kinh doanh và khu vực để chọn khi đặt/lọc chỉ tiêu.
     */
    @GetMapping("/options")
    @PreAuthorize("hasAnyAuthority('REPORT_SALES', 'ORDER_APPROVE', 'ROLE_ADMIN')")
    public ResponseEntity<SalesTargetOptionsResponse> getOptions() {
        return ResponseEntity.ok(salesTargetService.getOptions());
    }

    /**
     * GET /api/sales-targets/my-progress — Nhân viên xem tiến độ chỉ tiêu của chính mình theo thời gian thực.
     */
    @GetMapping({"/my-progress", "/me"})
    @PreAuthorize("hasAnyAuthority('REPORT_SALES', 'ORDER_APPROVE', 'ROLE_ADMIN')")
    public ResponseEntity<MySalesTargetProgressResponse> getMyProgress(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false) String period,
            Authentication authentication) {
        String username = authentication != null ? authentication.getName() : "system";
        return ResponseEntity.ok(salesTargetService.getMyProgress(year, month, period, username));
    }

    /**
     * GET /api/sales-targets/summary — Tổng hợp chỉ tiêu & tiến độ theo nhân viên và theo khu vực trong kỳ.
     */
    @GetMapping({"/summary", "/progress", "/dashboard"})
    @PreAuthorize("hasAnyAuthority('REPORT_SALES', 'ORDER_APPROVE', 'ROLE_ADMIN')")
    public ResponseEntity<SalesTargetSummaryResponse> getSummary(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false) String period,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Long territoryId) {
        return ResponseEntity.ok(salesTargetService.getSummary(year, month, period, userId, territoryId));
    }

    /**
     * GET /api/sales-targets — Tra cứu danh sách chỉ tiêu doanh số kèm tiến độ thực tế.
     */
    @GetMapping
    @PreAuthorize("hasAnyAuthority('REPORT_SALES', 'ORDER_APPROVE', 'ROLE_ADMIN')")
    public ResponseEntity<List<SalesTargetResponse>> searchTargets(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false) String period,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Long territoryId,
            @RequestParam(required = false) SalesTargetScopeType scopeType,
            @RequestParam(required = false) Boolean locked,
            @RequestParam(required = false) String progressStatus,
            @RequestParam(required = false) String keyword) {
        return ResponseEntity.ok(salesTargetService.searchTargets(
                year, month, period, userId, territoryId, scopeType, locked, progressStatus, keyword));
    }

    /**
     * GET /api/sales-targets/{id} — Chi tiết chỉ tiêu doanh số kèm tiến độ thực tế và nhật ký chỉnh sửa.
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('REPORT_SALES', 'ORDER_APPROVE', 'ROLE_ADMIN')")
    public ResponseEntity<SalesTargetResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(salesTargetService.getById(id));
    }

    /**
     * GET /api/sales-targets/{id}/audit-logs — Nhật ký tạo/chỉnh sửa/khóa của một chỉ tiêu doanh số.
     */
    @GetMapping("/{id}/audit-logs")
    @PreAuthorize("hasAnyAuthority('REPORT_SALES', 'ORDER_APPROVE', 'ROLE_ADMIN')")
    public ResponseEntity<List<SalesTargetAuditLogResponse>> getAuditLogs(@PathVariable Long id) {
        return ResponseEntity.ok(salesTargetService.getAuditLogs(id));
    }

    /**
     * POST /api/sales-targets — Đặt mới chỉ tiêu doanh số theo tháng cho nhân viên hoặc khu vực.
     */
    @PostMapping
    @PreAuthorize("hasAnyAuthority('ORDER_APPROVE', 'ROLE_ADMIN')")
    public ResponseEntity<SalesTargetResponse> createTarget(
            @Valid @RequestBody SalesTargetRequest request,
            Authentication authentication) {
        String username = authentication != null ? authentication.getName() : "system";
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(salesTargetService.createTarget(request, username));
    }

    /**
     * POST /api/sales-targets/batch — Đặt hoặc cập nhật hàng loạt chỉ tiêu doanh số trong một kỳ.
     */
    @PostMapping("/batch")
    @PreAuthorize("hasAnyAuthority('ORDER_APPROVE', 'ROLE_ADMIN')")
    public ResponseEntity<List<SalesTargetResponse>> upsertBatch(
            @Valid @RequestBody BatchSalesTargetRequest request,
            Authentication authentication) {
        String username = authentication != null ? authentication.getName() : "system";
        return ResponseEntity.ok(salesTargetService.upsertBatch(request, username));
    }

    /**
     * PUT /api/sales-targets/{id} — Cập nhật chỉ tiêu doanh số.
     * Nếu chỉ tiêu đã qua kỳ hoặc đã khóa, bắt buộc truyền reason và hệ thống ghi nhật ký AuditLog.
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ORDER_APPROVE', 'ROLE_ADMIN')")
    public ResponseEntity<SalesTargetResponse> updateTarget(
            @PathVariable Long id,
            @Valid @RequestBody UpdateSalesTargetRequest request,
            Authentication authentication) {
        String username = authentication != null ? authentication.getName() : "system";
        return ResponseEntity.ok(salesTargetService.updateTarget(id, request, username));
    }

    /**
     * POST /api/sales-targets/{id}/lock — Khóa thủ công một chỉ tiêu doanh số.
     */
    @PostMapping("/{id}/lock")
    @PreAuthorize("hasAnyAuthority('ORDER_APPROVE', 'ROLE_ADMIN')")
    public ResponseEntity<SalesTargetResponse> lockTarget(
            @PathVariable Long id,
            @RequestBody(required = false) Map<String, String> body,
            Authentication authentication) {
        String username = authentication != null ? authentication.getName() : "system";
        String reason = body != null ? body.get("reason") : null;
        return ResponseEntity.ok(salesTargetService.lockTarget(id, reason, username));
    }

    /**
     * POST /api/sales-targets/lock-past-periods — Khóa toàn bộ các chỉ tiêu đã qua kỳ nhưng chưa khóa trong DB.
     */
    @PostMapping("/lock-past-periods")
    @PreAuthorize("hasAnyAuthority('ORDER_APPROVE', 'ROLE_ADMIN')")
    public ResponseEntity<Map<String, Object>> lockPastPeriods(Authentication authentication) {
        String username = authentication != null ? authentication.getName() : "system";
        int lockedCount = salesTargetService.lockPastPeriodTargets(username);
        return ResponseEntity.ok(Map.of(
                "lockedCount", lockedCount,
                "message", "Đã khóa " + lockedCount + " chỉ tiêu doanh số đã qua kỳ"
        ));
    }

    /**
     * DELETE /api/sales-targets/{id} — Xóa chỉ tiêu chưa khóa thuộc kỳ hiện tại hoặc tương lai.
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('ORDER_APPROVE', 'ROLE_ADMIN')")
    public ResponseEntity<ApiResponse> deleteTarget(
            @PathVariable Long id,
            Authentication authentication) {
        String username = authentication != null ? authentication.getName() : "system";
        salesTargetService.deleteTarget(id, username);
        return ResponseEntity.ok(ApiResponse.success("Đã xóa chỉ tiêu doanh số thành công"));
    }
}
