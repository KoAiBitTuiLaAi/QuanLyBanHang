package com.quanlybanhang.repository;

import com.quanlybanhang.model.DealerReturnItem;
import com.quanlybanhang.model.DealerReturnStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;

@Repository
public interface DealerReturnItemRepository extends JpaRepository<DealerReturnItem, Long> {

    @Query("""
            SELECT COALESCE(SUM(ri.returnQuantity), 0)
            FROM DealerReturnItem ri
            JOIN ri.dealerReturn r
            WHERE ri.invoiceItem.id = :invoiceItemId
              AND r.status IN :statuses
            """)
    BigDecimal sumReturnQuantityByInvoiceItemIdAndStatuses(
            @Param("invoiceItemId") Long invoiceItemId,
            @Param("statuses") Collection<DealerReturnStatus> statuses);

    @Query("""
            SELECT COALESCE(SUM(ri.returnQuantity), 0)
            FROM DealerReturnItem ri
            JOIN ri.dealerReturn r
            WHERE ri.invoiceItem.id = :invoiceItemId
              AND r.id <> :excludeReturnId
              AND r.status IN :statuses
            """)
    BigDecimal sumReturnQuantityByInvoiceItemIdExcludingReturn(
            @Param("invoiceItemId") Long invoiceItemId,
            @Param("excludeReturnId") Long excludeReturnId,
            @Param("statuses") Collection<DealerReturnStatus> statuses);

    @Query("""
            SELECT ri
            FROM DealerReturnItem ri
            JOIN FETCH ri.dealerReturn r
            JOIN FETCH ri.invoiceItem ii
            WHERE r.invoice.id = :invoiceId
              AND r.status IN :statuses
            """)
    List<DealerReturnItem> findByInvoiceIdAndStatuses(
            @Param("invoiceId") Long invoiceId,
            @Param("statuses") Collection<DealerReturnStatus> statuses);

    @Query("""
            SELECT ri
            FROM DealerReturnItem ri
            JOIN FETCH ri.dealerReturn r
            JOIN FETCH ri.targetWarehouse w
            WHERE ri.product.id = :productId
              AND w.id IN :warehouseIds
              AND r.status = com.quanlybanhang.model.DealerReturnStatus.CONFIRMED
            """)
    List<DealerReturnItem> findForStockCard(
            @Param("productId") Long productId,
            @Param("warehouseIds") Collection<Long> warehouseIds);
}

