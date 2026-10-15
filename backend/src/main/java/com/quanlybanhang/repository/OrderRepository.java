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

    List<Order> findByDealerIdOrderByCreatedAtDesc(Long dealerId);

    Optional<Order> findByIdAndDealerId(Long id, Long dealerId);

    List<Order> findByDealerAssignedUserIdOrderByCreatedAtDesc(Long assignedUserId);
}
