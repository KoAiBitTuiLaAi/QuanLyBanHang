package com.quanlybanhang.repository;

import com.quanlybanhang.model.Order;
import com.quanlybanhang.model.SalesTarget;
import com.quanlybanhang.model.SalesTargetScopeType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface SalesTargetRepository extends JpaRepository<SalesTarget, Long>, JpaSpecificationExecutor<SalesTarget> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM SalesTarget t WHERE t.id = :id")
    Optional<SalesTarget> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM SalesTarget t WHERE t.scopeKey = :scopeKey")
    Optional<SalesTarget> findByScopeKeyForUpdate(@Param("scopeKey") String scopeKey);

    Optional<SalesTarget> findByScopeKey(String scopeKey);

    boolean existsByScopeKey(String scopeKey);

    boolean existsByTargetCode(String targetCode);

    @Query("SELECT t FROM SalesTarget t " +
            "LEFT JOIN FETCH t.user " +
            "LEFT JOIN FETCH t.territory " +
            "WHERE t.targetYear = :year AND t.targetMonth = :month " +
            "ORDER BY t.scopeType ASC, t.id ASC")
    List<SalesTarget> findByTargetYearAndTargetMonthOrderByScopeTypeAscIdAsc(
            @Param("year") Integer year,
            @Param("month") Integer month);

    @Query("SELECT t FROM SalesTarget t " +
            "LEFT JOIN FETCH t.user " +
            "LEFT JOIN FETCH t.territory " +
            "WHERE t.targetYear = :year AND t.targetMonth = :month AND t.user.id = :userId " +
            "ORDER BY t.scopeType ASC, t.id ASC")
    List<SalesTarget> findByTargetYearAndTargetMonthAndUserId(
            @Param("year") Integer year,
            @Param("month") Integer month,
            @Param("userId") Long userId);

    @Query("SELECT t FROM SalesTarget t " +
            "LEFT JOIN FETCH t.user " +
            "LEFT JOIN FETCH t.territory " +
            "WHERE t.targetYear = :year AND t.targetMonth = :month AND t.scopeType = :scopeType " +
            "ORDER BY t.id ASC")
    List<SalesTarget> findByTargetYearAndTargetMonthAndScopeType(
            @Param("year") Integer year,
            @Param("month") Integer month,
            @Param("scopeType") SalesTargetScopeType scopeType);

    @Query("SELECT t FROM SalesTarget t WHERE t.locked = false AND " +
            "(t.targetYear < :currentYear OR (t.targetYear = :currentYear AND t.targetMonth < :currentMonth))")
    List<SalesTarget> findUnlockedPastTargets(
            @Param("currentYear") Integer currentYear,
            @Param("currentMonth") Integer currentMonth);

    /**
     * S8-04: Truy vấn toàn bộ đơn hàng hợp lệ trong kỳ tháng (kèm đại lý, nhân viên phụ trách và khu vực)
     * để tính tiến độ doanh số theo thời gian thực không gây N+1 queries.
     */
    @Query("SELECT DISTINCT o FROM Order o " +
            "LEFT JOIN FETCH o.dealer d " +
            "LEFT JOIN FETCH d.assignedUser " +
            "LEFT JOIN FETCH d.territory " +
            "WHERE o.status IN :statuses " +
            "AND COALESCE(o.recordedAt, o.createdAt) >= :periodStart " +
            "AND COALESCE(o.recordedAt, o.createdAt) <= :periodEnd")
    List<Order> findValidOrdersInPeriod(
            @Param("statuses") Collection<String> statuses,
            @Param("periodStart") LocalDateTime periodStart,
            @Param("periodEnd") LocalDateTime periodEnd);
}

