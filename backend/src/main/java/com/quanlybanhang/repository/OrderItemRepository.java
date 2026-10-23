package com.quanlybanhang.repository;

import com.quanlybanhang.model.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Set;
import java.util.Collection;

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
}
