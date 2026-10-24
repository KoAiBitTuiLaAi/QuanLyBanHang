package com.quanlybanhang.repository;

import com.quanlybanhang.model.Order;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long>, JpaSpecificationExecutor<Order> {

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select o from Order o where o.id = :id")
    Optional<Order> findByIdForUpdate(@org.springframework.data.repository.query.Param("id") Long id);

    @org.springframework.data.jpa.repository.Query("select o.id from Order o where o.inventoryReserved = true and o.reservationExpiresAt is not null and o.reservationExpiresAt <= :now order by o.id")
    List<Long> findExpiredReservationIds(@org.springframework.data.repository.query.Param("now") java.time.LocalDateTime now);

    Optional<Order> findByOrderCode(String orderCode);

    boolean existsByOrderCode(String orderCode);

    List<Order> findAllByOrderByCreatedAtDesc();

    boolean existsByPriceListId(Long priceListId);

    boolean existsByDealerId(Long dealerId);

    List<Order> findByDealerIdOrderByCreatedAtDesc(Long dealerId);

    List<Order> findByStatus(String status);

    long countByDealerIdAndStatusNotIn(Long dealerId, java.util.Collection<String> statuses);

    List<Order> findByDealerIdAndStatusNotInOrderByCreatedAtDesc(Long dealerId, java.util.Collection<String> statuses);

    List<Order> findByDealerIdAndStatus(Long dealerId, String status);

    List<Order> findByDealerIdAndStatusOrderByRecordedAtAsc(Long dealerId, String status);

    List<Order> findByDealerIdAndStatusInOrderByRecordedAtAsc(Long dealerId, java.util.Collection<String> statuses);

    List<Order> findByDealerIdAndStatusInAndPaymentStatusInOrderByRecordedAtAsc(
            Long dealerId, java.util.Collection<String> statuses, java.util.Collection<String> paymentStatuses);

    List<Order> findByDealerIdAndStatusInAndPaymentStatusOrderByRecordedAtAsc(
            Long dealerId, java.util.Collection<String> statuses, String paymentStatus);

    @org.springframework.data.jpa.repository.Query("SELECT COALESCE(SUM(o.totalAmount), 0) FROM Order o WHERE o.dealer.id = :dealerId AND o.status IN :statuses")
    java.math.BigDecimal sumTotalAmountByDealerIdAndStatusIn(
            @org.springframework.data.repository.query.Param("dealerId") Long dealerId,
            @org.springframework.data.repository.query.Param("statuses") java.util.Collection<String> statuses);

    @org.springframework.data.jpa.repository.Query("SELECT COALESCE(SUM(o.totalAmount), 0) FROM Order o WHERE o.dealer.id = :dealerId AND o.status = :status")
    java.math.BigDecimal sumTotalAmountByDealerIdAndStatus(
            @org.springframework.data.repository.query.Param("dealerId") Long dealerId,
            @org.springframework.data.repository.query.Param("status") String status);

    /**
     * S5-06: Tìm các đơn hàng đang giữ chỗ tồn kho mà đã vượt quá thời hạn giữ chỗ.
     */
    @org.springframework.data.jpa.repository.Query("SELECT o FROM Order o WHERE o.inventoryReserved = true AND o.reservationExpiresAt IS NOT NULL AND o.reservationExpiresAt <= :now")
    List<Order> findExpiredReservations(@org.springframework.data.repository.query.Param("now") java.time.LocalDateTime now);
    /**
     * S4-04: Lấy danh sách các đơn hàng đã mua của đại lý trong một khoảng thời gian (loại trừ đơn huỷ, từ chối, chờ duyệt).
     */
    @org.springframework.data.jpa.repository.Query("SELECT o FROM Order o WHERE o.dealer.id = :dealerId AND o.status NOT IN :excludedStatuses AND COALESCE(o.recordedAt, o.createdAt) >= :cutoffDate ORDER BY COALESCE(o.recordedAt, o.createdAt) DESC, o.id DESC")
    List<Order> findPurchasedOrdersInPeriod(
            @org.springframework.data.repository.query.Param("dealerId") Long dealerId,
            @org.springframework.data.repository.query.Param("excludedStatuses") java.util.Collection<String> excludedStatuses,
            @org.springframework.data.repository.query.Param("cutoffDate") java.time.LocalDateTime cutoffDate);


    Optional<Order> findByIdAndDealerId(Long id, Long dealerId);

    List<Order> findByDealerAssignedUserIdOrderByCreatedAtDesc(Long assignedUserId);
}
