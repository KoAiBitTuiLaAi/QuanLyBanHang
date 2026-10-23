package com.quanlybanhang.service;

import com.quanlybanhang.dto.request.BatchSalesTargetRequest;
import com.quanlybanhang.dto.request.SalesTargetRequest;
import com.quanlybanhang.dto.request.UpdateSalesTargetRequest;
import com.quanlybanhang.dto.response.*;
import com.quanlybanhang.model.SalesTargetScopeType;

import java.util.List;

/**
 * Service Quản lý Chỉ tiêu doanh số và Theo dõi tiến độ theo thời gian thực (S8-04).
 */
public interface SalesTargetService {

    SalesTargetOptionsResponse getOptions();

    SalesTargetResponse createTarget(SalesTargetRequest request, String username);

    List<SalesTargetResponse> upsertBatch(BatchSalesTargetRequest request, String username);

    SalesTargetResponse updateTarget(Long id, UpdateSalesTargetRequest request, String username);

    SalesTargetResponse lockTarget(Long id, String reason, String username);

    int lockPastPeriodTargets(String username);

    void deleteTarget(Long id, String username);

    SalesTargetResponse getById(Long id);

    List<SalesTargetResponse> searchTargets(
            Integer year,
            Integer month,
            String period,
            Long userId,
            Long territoryId,
            SalesTargetScopeType scopeType,
            Boolean locked,
            String progressStatus,
            String keyword);

    MySalesTargetProgressResponse getMyProgress(Integer year, Integer month, String period, String username);

    SalesTargetSummaryResponse getSummary(
            Integer year,
            Integer month,
            String period,
            Long userId,
            Long territoryId);

    List<SalesTargetAuditLogResponse> getAuditLogs(Long id);
}

