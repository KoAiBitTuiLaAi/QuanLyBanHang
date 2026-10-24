package com.quanlybanhang.repository;

import com.quanlybanhang.model.*;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.math.BigDecimal;
import java.util.*;

public interface ReturnOrderRepository extends JpaRepository<ReturnOrder, Long>, JpaSpecificationExecutor<ReturnOrder> {
    List<ReturnOrder> findByInvoiceIdOrderByCreatedAtDesc(Long invoiceId);
    @Query("select r.invoice.id from ReturnOrder r where r.id = :id")
    Optional<Long> findInvoiceId(@Param("id") Long id);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ReturnOrder r where r.id = :id")
    Optional<ReturnOrder> findForUpdate(@Param("id") Long id);
    @Query("select coalesce(sum(i.returnQuantity),0) from ReturnOrderItem i where i.invoiceItem.id = :itemId and i.returnOrder.status = com.quanlybanhang.model.ReturnOrderStatus.CONFIRMED")
    BigDecimal confirmedQuantity(@Param("itemId") Long itemId);
    @Query("select coalesce(sum(i.returnAmount),0) from ReturnOrderItem i where i.invoiceItem.id = :itemId and i.returnOrder.status = com.quanlybanhang.model.ReturnOrderStatus.CONFIRMED")
    BigDecimal confirmedAmount(@Param("itemId") Long itemId);
    interface OrderCredit { Long getOrderId(); BigDecimal getAmount(); }
    @Query("""
        select i.invoiceItem.order.id as orderId, sum(i.returnAmount) as amount
        from ReturnOrderItem i
        where i.returnOrder.status = com.quanlybanhang.model.ReturnOrderStatus.CONFIRMED
          and i.returnOrder.dealer.id = :dealerId and i.invoiceItem.order.status in :statuses
        group by i.invoiceItem.order.id
        """)
    List<OrderCredit> creditsByOrder(@Param("dealerId") Long dealerId, @Param("statuses") List<String> statuses);
}