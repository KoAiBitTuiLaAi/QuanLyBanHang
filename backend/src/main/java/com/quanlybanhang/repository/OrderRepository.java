package com.quanlybanhang.repository;

import com.quanlybanhang.model.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByOrderCode(String orderCode);

    boolean existsByOrderCode(String orderCode);

    List<Order> findAllByOrderByCreatedAtDesc();

    boolean existsByPriceListId(Long priceListId);

    boolean existsByDealerId(Long dealerId);

    List<Order> findByStatus(String status);

    long countByDealerIdAndStatusNotIn(Long dealerId, java.util.Collection<String> statuses);

    List<Order> findByDealerIdAndStatusNotInOrderByCreatedAtDesc(Long dealerId, java.util.Collection<String> statuses);

    List<Order> findByDealerIdAndStatus(Long dealerId, String status);

    List<Order> findByDealerIdAndStatusOrderByRecordedAtAsc(Long dealerId, String status);

    @org.springframework.data.jpa.repository.Query("SELECT COALESCE(SUM(o.totalAmount), 0) FROM Order o WHERE o.dealer.id = :dealerId AND o.status = :status")
    java.math.BigDecimal sumTotalAmountByDealerIdAndStatus(
            @org.springframework.data.repository.query.Param("dealerId") Long dealerId,
            @org.springframework.data.repository.query.Param("status") String status);

    /**
     * S4-04: Lấy danh sách các đơn hàng đã mua của đại lý trong một khoảng thời gian (loại trừ đơn huỷ, từ chối, chờ duyệt).
     */
    @org.springframework.data.jpa.repository.Query("SELECT o FROM Order o WHERE o.dealer.id = :dealerId AND o.status NOT IN :excludedStatuses AND COALESCE(o.recordedAt, o.createdAt) >= :cutoffDate ORDER BY COALESCE(o.recordedAt, o.createdAt) DESC, o.id DESC")
    List<Order> findPurchasedOrdersInPeriod(
            @org.springframework.data.repository.query.Param("dealerId") Long dealerId,
            @org.springframework.data.repository.query.Param("excludedStatuses") java.util.Collection<String> excludedStatuses,
            @org.springframework.data.repository.query.Param("cutoffDate") java.time.LocalDateTime cutoffDate);
}
