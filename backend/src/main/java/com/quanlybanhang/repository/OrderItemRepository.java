package com.quanlybanhang.repository;

import com.quanlybanhang.model.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Set;

@Repository
public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from OrderItem i where i.order.id=:orderId order by i.id")
    List<OrderItem> findByOrderIdForUpdate(@Param("orderId") Long orderId);

    @Query("select i from OrderItem i join fetch i.order o join fetch o.warehouse w where i.product.id=:productId and w.id in :warehouseIds and exists (select h.id from OrderStatusHistory h where h.order=o and upper(h.newStatus) in ('EXPORTED','SHIPPED')) order by o.id,i.id")
    List<OrderItem> findForStockCard(@Param("productId") Long productId, @Param("warehouseIds") Collection<Long> warehouseIds);

    List<OrderItem> findByOrderId(Long orderId);

    List<OrderItem> findByProductId(Long productId);

    boolean existsByProductId(Long productId);

    @Query("SELECT DISTINCT i.product.id FROM OrderItem i WHERE i.product.id IN :productIds")
    Set<Long> findProductIdsWithTransactions(@Param("productIds") Collection<Long> productIds);

    /**
     * S8-06: Truy vấn chi tiết các dòng đơn hàng hợp lệ trong kỳ kèm sản phẩm, nhóm hàng, đại lý,
     * nhân viên phụ trách và khu vực để tổng hợp báo cáo doanh số đa chiều không gây N+1 queries.
     */
    @Query("SELECT i FROM OrderItem i " +
            "JOIN FETCH i.order o " +
            "JOIN FETCH i.product p " +
            "LEFT JOIN FETCH p.category c " +
            "LEFT JOIN FETCH o.dealer d " +
            "LEFT JOIN FETCH d.assignedUser u " +
            "LEFT JOIN FETCH d.territory t " +
            "WHERE o.status IN :statuses " +
            "AND COALESCE(o.recordedAt, o.createdAt) >= :start " +
            "AND COALESCE(o.recordedAt, o.createdAt) <= :end")
    List<OrderItem> findValidOrderItemsInPeriod(
            @Param("statuses") Collection<String> statuses,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end);
}
